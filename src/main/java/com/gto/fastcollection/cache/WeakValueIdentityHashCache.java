package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} combining weak values (see {@link WeakValueHashCache}) with
 * identity-based keys (see {@link IdentityHashCache}): keys are hashed with
 * {@link System#identityHashCode} and compared with {@code ==}, values are held
 * by {@link java.lang.ref.WeakReference}. Use it to attach short-lived data to
 * object instances without keeping either the key or the value alive.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 * Concurrency mirrors {@link CustomHashCache}: one open-addressed table,
 * lock-free reads and lock-free compare-and-set writes, a lock only to resize;
 * every insertion compare-and-sets on the slot value its probe observed, so a
 */
public final class WeakValueIdentityHashCache<K, V> extends OpenCacheTable<WeakValueIdentityHashCache.Node<K, V>> implements MapCache<K, V>, ICleanableCache {

    private final Function<? super K, ? extends V> createFunction;


    /**
     * Creates a cache with no default create function.
     */
    public WeakValueIdentityHashCache() {
        this.createFunction = null;
        CacheCleaner.add(this);
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public WeakValueIdentityHashCache(Function<? super K, ? extends V> createFunction) {
        this.createFunction = createFunction;
        CacheCleaner.add(this);
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return createFunction;
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        return getCache(k, mix(k), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return getCache(k, mix(k), createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        return getCache(k, mix(k), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return getCache(k, mix(k), createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        return getIfAbsent(k, mix(k));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        return putIfAbsent(k, v, mix(k));
    }

    /**
     * {@inheritDoc} Drops every entry whose value has been collected.
     */
    @Override
    public void clearCache() {
        sweep();
    }

    /**
     * The mixed hash of a key: computed once per public call and threaded down.
     */
    private static int mix(Object k) {
        return HashCommon.mix(System.identityHashCode(k));
    }

    /**
     * The table: an open-addressed slot array of {@link Node}s (weak value,
     * strong identity-compared key) that store no hash, so a resize re-derives it.
     */

    @Override
    protected int hashOf(Node<K, V> node) {
        return mix(node.key);
    }

    @Override
    protected boolean isDead(Node<K, V> node) {
        return node.get() == null;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final K k, final int mix) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) return null;
            final Node<K, V> node = (Node<K, V>) raw;
            if (node.key == k) return node.get();
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Recursive variant: probes without locking, runs the function with
     * every lock released so it may call back into this cache, then inserts
     * with compare-and-set, keeping the value computed by another thread if
     * one landed first. A function returning {@code null} stores nothing.
     */
    private V getCache(final K k, final int mix,
                       Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) break;
            @SuppressWarnings("unchecked") final Node<K, V> node = (Node<K, V>) raw;
            if (node.key == k) {
                final V existing = node.get();
                if (existing != null) return existing;
                break;
            }
        }
        // Run outside every lock so the function may call back into this cache.
        final K mapped = keyMappingFunction.apply(k);
        final V value = createFunction.apply(mapped);
        if (value == null) return null;
        return putIfAbsent(mapped, value, mix);
    }

    /**
     * Inserts unless the key is bound to a live value; returns the value now
     * bound. Lock-free: the node is compare-and-set into the first free slot
     * of the probe sequence, and any conflict (a slot taken meanwhile, or an
     * array replaced by a resize) restarts the probe on the current array.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final K k, final V v, final int mix) {
        if (v == null) return null;
        for (; ; ) {
            final long stamp = readLock();
            boolean resize;
            boolean done = false;
            try {
                final Object[] tab = slots;
                final int mask = tab.length - 1;
                int vacant = -1;
                Object vacantValue = null;
                int index = mix & mask;
                for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
                    final Object raw = SLOT.getAcquire(tab, index);
                    if (raw == null) {
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = null;
                        }
                        break;
                    }
                    final Node<K, V> node = (Node<K, V>) raw;
                    if (node.key == k) {
                        final V existing = node.get();
                        if (existing != null) return existing;
                        // a collected entry is garbage: its slot is reusable
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = node;
                        }
                    }
                }
                if (vacant < 0) {
                    // no free slot anywhere: the only case that grows before inserting
                    resize = true;
                } else {
                    if (SLOT.compareAndSet(tab, vacant, vacantValue, new Node<>(k, v))) {
                        if (vacantValue == null) {
                            size++;
                        }
                        Reference.reachabilityFence(k);
                        Reference.reachabilityFence(v);
                        done = true;
                        resize = shouldGrow();
                    } else {
                        continue; // lost the slot to another writer: retry under a fresh stamp
                    }
                }
            } finally {
                unlockRead(stamp);
            }
            if (resize) grow();
            if (done) return v;
        }
    }

    /**
     * A single slot entry holding the value weakly and the key strongly.
     */
    static final class Node<K, V> extends WeakReference<V> {

        private final K key;

        private Node(K key, V value) {
            super(value);
            this.key = key;
        }
    }
}
