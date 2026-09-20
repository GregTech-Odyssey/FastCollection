package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.HashCommon;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} with identity-based keys: keys are hashed with
 * {@link System#identityHashCode} and compared with {@code ==}, so two distinct
 * objects are never treated as the same key regardless of their {@code equals}
 * method. This is the cache to use when you want to key by object identity (e.g.
 * per-instance attributes) rather than by value.
 *
 * <p>The cache <em>is</em> the table: it extends {@link OpenCacheTable} directly
 * (no delegation), so one open-addressed slot array serves the whole cache, reads
 * are lock-free, writes compare-and-set while holding the shared stamp, and only
 * a resize takes the exclusive stamp.
 */
public final class IdentityHashCache<K, V> extends OpenCacheTable<IdentityHashCache.Node<K, V>> implements MapCache<K, V> {

    private final Function<? super K, ? extends V> createFunction;

    /**
     * Creates a cache with no default create function.
     */
    public IdentityHashCache() {
        this.createFunction = null;
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public IdentityHashCache(Function<? super K, ? extends V> createFunction) {
        this.createFunction = createFunction;
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return createFunction;
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        return this.getCache(k, mix(k), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return this.getCache(k, mix(k), createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        return this.getCache(k, mix(k), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return this.getCache(k, mix(k), createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        return this.getIfAbsent(k, mix(k));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        return this.putIfAbsent(k, v, mix(k));
    }

    /**
     * The mixed hash of a key: computed once per public call and threaded down,
     * so no hot path recomputes it.
     */
    private static int mix(Object k) {
        return HashCommon.mix(System.identityHashCode(k));
    }

    @Override
    protected int hashOf(Node<K, V> node) {
        return mix(node.key);
    }

    @Override
    protected boolean isDead(Node<K, V> node) {
        return false;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final K k, final int mix) {
        final Object[] table = slots;
        final int mask = table.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(table, index);
            if (raw == null) return null;
            final Node<K, V> node = (Node<K, V>) raw;
            if (node.key == k) return node.value;
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Probes without locking, runs the function outside every lock so it may
     * call back into this cache, then inserts with compare-and-set, keeping the
     * value computed by another thread if one landed first. A function returning
     * {@code null} stores nothing.
     */
    @SuppressWarnings("unchecked")
    private V getCache(final K k, final int mix,
                       Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        final Object[] table = slots;
        final int mask = table.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(table, index);
            if (raw == null) break;
            final Node<K, V> node = (Node<K, V>) raw;
            if (node.key == k) return node.value;
        }
        final K mapped = keyMappingFunction.apply(k);
        final V value = createFunction.apply(mapped);
        if (value == null) return null;
        return putIfAbsent(mapped, value, mix);
    }

    /**
     * Inserts unless the key is already bound; returns the value now bound.
     * Holds the shared stamp 閳?writers stay parallel 閳?and compare-and-sets into
     * the first free slot of the probe sequence using the value the probe
     * observed there, so a competing writer makes it fail and the probe restart.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final K k, final V v, final int mix) {
        if (v == null) return null;
        for (; ; ) {
            final long stamp = readLock();
            boolean resize;
            boolean done = false;
            try {
                final Object[] table = slots;
                final int mask = table.length - 1;
                int vacant = -1;
                Object vacantValue = null;
                int index = mix & mask;
                for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
                    final Object raw = SLOT.getAcquire(table, index);
                    if (raw == null) {
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = null;
                        }
                        break;
                    }
                    final Node<K, V> node = (Node<K, V>) raw;
                    if (node.key == k) return node.value;
                }
                if (vacant < 0) {
                    // The probe walked the whole table without a free slot: this is the
                    // only case that must grow *before* inserting.
                    resize = true;
                } else if (SLOT.compareAndSet(table, vacant, vacantValue, new Node<>(k, v))) {
                    size++;
                    // Inserted first; only now look at the load factor, exactly like
                    // ConcurrentHashMap's addCount after putVal.
                    done = true;
                    resize = shouldGrow();
                } else {
                    continue; // lost the slot to another writer: retry, no resize needed
                }
            } finally {
                unlockRead(stamp);
            }
            if (resize) grow();
            if (done) return v;
        }
    }

    /**
     * A single slot entry; immutable.
     */
    static final class Node<K, V> {

        private final K key;
        private final V value;

        private Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
