package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} whose key hashing and equality are driven by a custom
 * {@link Hash.Strategy}, so this is the right cache when keys need value-based
 * semantics that {@code hashCode} / {@code equals} cannot express (e.g.
 * field-based identity).
 *
 * <p>Thread safety: one open-addressed table, lock-free reads and lock-free
 * compare-and-set writes, with a lock taken only to resize. The key's strategy
 * hash is computed once per public call and threaded down to the probe, which
 * matches on the stored hash before ever calling the strategy.
 */
public final class CustomHashCache<K, V> extends OpenCacheTable<CustomHashCache.Node<K, V>> implements MapCache<K, V> {

    private final Hash.Strategy<? super K> strategy;
    private final Function<? super K, ? extends V> createFunction;

    /**
     * Creates a cache with no default create function.
     */
    public CustomHashCache(Hash.Strategy<? super K> strategy) {
        this.strategy = strategy;
        this.createFunction = null;
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public CustomHashCache(Hash.Strategy<? super K> strategy, Function<? super K, ? extends V> createFunction) {
        this.strategy = strategy;
        this.createFunction = createFunction;
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return createFunction;
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.getIfAbsent(k, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        var hash = strategy.hashCode(k);
        var mix = HashCommon.mix(hash);
        return this.putIfAbsent(k, v, hash, mix);
    }

    @Override
    protected int hashOf(Node<K, V> node) {
        return HashCommon.mix(node.hash);
    }

    @Override
    protected boolean isDead(Node<K, V> node) {
        return false;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final K k, final int hash, final int mix) {
        final Object[] table = slots;
        final int mask = table.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(table, index);
            if (raw == null) return null;
            final Node<K, V> node = (Node<K, V>) raw;
            if (node.hash == hash && (k == node.key || strategy.equals(k, node.key))) return node.value;
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Recursive variant: probes without locking, runs the function with
     * every lock released so it may call back into this cache, then inserts
     * with compare-and-set, keeping the value computed by another thread if
     * one landed first.
     */
    private V getCache(final K k, final int hash, final int mix,
                       Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        final Object[] table = slots;
        final int mask = table.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(table, index);
            if (raw == null) break;
            @SuppressWarnings("unchecked") final Node<K, V> node = (Node<K, V>) raw;
            if (node.hash == hash && (k == node.key || strategy.equals(k, node.key))) return node.value;
        }
        // Run outside every lock so the function may call back into this cache.
        final K mapped = keyMappingFunction.apply(k);
        final V value = createFunction.apply(mapped);
        if (value == null) return null;
        return putIfAbsent(mapped, value, hash, mix);
    }

    /**
     * Inserts unless the key is already bound; returns the value now bound.
     * Lock-free: the node is compare-and-set into the first free slot of the
     * probe sequence, and any conflict (a slot taken meanwhile, or an array
     * replaced by a resize) restarts the probe on the current array.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final K k, final V v, final int hash, final int mix) {
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
                    if (node.hash == hash && (k == node.key || strategy.equals(k, node.key))) return node.value;
                }
                if (vacant < 0) {
                    // The probe walked the whole table without a free slot: this is the
                    // only case that must grow *before* inserting.
                    resize = true;
                } else if (SLOT.compareAndSet(table, vacant, vacantValue, new Node<>(k, v, hash))) {
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
     * A single slot entry; immutable. {@code hash} is the key's strategy hash.
     */
    static final class Node<K, V> {

        private final K key;
        private final V value;
        private final int hash;

        private Node(K key, V value, int hash) {
            this.key = key;
            this.value = value;
            this.hash = hash;
        }
    }
}
