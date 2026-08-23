package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * A {@link MapCache} combining weak values (see {@link WeakValueHashCache}) with
 * a custom {@link Hash.Strategy} for hashing and equality (see
 * {@link CustomHashCache}). Use it when both the weak-value semantics and
 * strategy-based key comparison are needed.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 */
public final class WeakValueCustomHashCache<K, V> extends Segmented<WeakValueCustomHashCache.Segment<K, V>> implements MapCache<K, V>, ICleanableCache {

    private final Hash.Strategy<? super K> strategy;


    /**
     * Creates a cache with default concurrency and the given factory;
     * {@code null} is allowed and behaves like the no-factory constructor.
     */
    public WeakValueCustomHashCache(Hash.Strategy<? super K> strategy) {
        this(strategy, Concurrents.NCPU);
    }


    /**
     * Creates a cache with the given concurrency level and factory; registers
     * this cache with {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakValueCustomHashCache(Hash.Strategy<? super K> strategy, int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>(strategy));
        this.strategy = strategy;
        CacheCleaner.add(this);
    }


    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getIfAbsent(k, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        int hash = strategy.hashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).put(k, v, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        clearSegments();
    }

    /**
     * {@inheritDoc} Drops every entry whose value has been collected.
     */
    @Override
    public void clearCache() {
        sweepSegments();
    }

    /**
     * A striped segment whose entries hold {@link WeakReferenceValueNode}s (weak
     * value, strong key, strategy-compared) on the shared {@link HashSegment}
     * skeleton. Dead nodes are replaced in place by writers and dropped in bulk
     * by the inherited sweep; readers never mutate the chain.
     */
    final static class Segment<K, V> extends HashSegment<WeakReferenceValueNode<K, V>> {
        private final Hash.Strategy<? super K> strategy;

        private Segment(Hash.Strategy<? super K> strategy) {
            this.strategy = strategy;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected WeakReferenceValueNode<K, V>[] newArray(int capacity) {
            return new WeakReferenceValueNode[capacity];
        }

        @Override
        protected int nodeHash(WeakReferenceValueNode<K, V> node) {
            return node.hash;
        }

        @Override
        protected boolean isDead(WeakReferenceValueNode<K, V> node) {
            return node.get() == null;
        }

        /**
         * Recursive variant: probes under the read lock, runs the function with
         * every lock released so it may call back into this cache, and finally
         * stores the result under the write lock — replacing a dead node if one
         * occupies the slot, or keeping another thread's value if it landed
         * first.
         */
        private V getCache(final K k, final int hash, int mix,
                           Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
            long stamp = readLock();
            try {
                WeakReferenceValueNode<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V v = curr.get();
                        if (v != null) return v;
                        break;
                    }
                    curr = curr.next;
                }
            } finally {
                unlockRead(stamp);
            }
            // Run outside all locks so the function may call back into this cache.
            final var v = createFunction.apply(k);
            final var k1 = keyMappingFunction.apply(k);
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final WeakReferenceValueNode<K, V> node = table[index];
                WeakReferenceValueNode<K, V> prev = null;
                WeakReferenceValueNode<K, V> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (k1 == curr.key || strategy.equals(k1, curr.key))) {
                        V existing = curr.get();
                        if (existing != null) {
                            return existing;
                        }
                        // value was collected: replace the dead node with the computed value
                        var n = new WeakReferenceValueNode<>(k1, v, hash, curr.next);
                        if (prev == null) {
                            table[index] = n;
                        } else {
                            prev.next = n;
                        }
                        return v;
                    }
                    prev = curr;
                    curr = curr.next;
                }
                table[index] = new WeakReferenceValueNode<>(k1, v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }

        /**
         * Read-only lookup; never mutates the chain.
         */
        private V getIfAbsent(final K k, final int hash, int mix) {
            long stamp = readLock();
            try {
                final int index = mix & mask;
                WeakReferenceValueNode<K, V> curr = table[index];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        return curr.get();
                    }
                    curr = curr.next;
                }
                return null;
            } finally {
                unlockRead(stamp);
            }
        }

        /**
         * Inserts only if absent, replacing a dead node; returns the value now bound.
         */
        private V put(final K k, final V v, final int hash, int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final WeakReferenceValueNode<K, V> node = table[index];
                WeakReferenceValueNode<K, V> prev = null;
                WeakReferenceValueNode<K, V> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V old = curr.get();
                        if (old != null) return old;
                        // value was collected: replace the dead node with the new value
                        var n = new WeakReferenceValueNode<>(k, v, hash, curr.next);
                        if (prev == null) {
                            table[index] = n;
                        } else {
                            prev.next = n;
                        }
                        return v;
                    }
                    prev = curr;
                    curr = curr.next;
                }
                table[index] = new WeakReferenceValueNode<>(k, v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }
    }
}