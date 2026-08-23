package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} whose values are held by {@link java.lang.ref.WeakReference}
 * (keys stay strong). A value is automatically removed once it is garbage
 * collected, so the cache can hold objects that would otherwise be expensive to
 * keep alive for the caller's lifetime (e.g. derived data, one-off resources)
 * without leaking them.
 *
 * <p>Because values are weak, "present" and "absent" are time-dependent: a value
 * may disappear between two calls once it is collected. Dead entries are pruned
 * eagerly on writes (a write to a collected key reuses the node) and lazily by
 * {@link CacheCleaner} through {@link #clearCache()}.
 *
 * <p>Concurrency mirrors {@link CustomHashCache}: power-of-two segments, each
 * guarded by a {@link StampedLock}; reads take the shared read lock and writes
 * the exclusive write lock.
 */
public final class WeakValueHashCache<K, V> extends Segmented<WeakValueHashCache.Segment<K, V>> implements MapCache<K, V>, ICleanableCache {


    public WeakValueHashCache() {
        this(Concurrents.NCPU);
    }


    /**
     * Creates a cache with the given concurrency level and factory; registers
     * this cache with {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakValueHashCache(int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>());
        CacheCleaner.add(this);
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = k.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = k.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = k.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = k.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, hash, mix, createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        int hash = k.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getIfAbsent(k, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        int hash = k.hashCode();
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
     * value, strong key) on the shared {@link HashSegment} skeleton. A node
     * whose value has been collected is dead: writers replace it in place when
     * they hit it, and the inherited sweep drops it in bulk. Readers never
     * mutate the chain, so the shared read lock stays a pure read.
     */
    final static class Segment<K, V> extends HashSegment<WeakReferenceValueNode<K, V>> {

        private Segment() {
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
                    if (curr.hash == hash && (curr.key == k || k.equals(curr.key))) {
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
                    if (curr.hash == hash && (curr.key == k1 || k1.equals(curr.key))) {
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
                    if (curr.hash == hash && (curr.key == k || k.equals(curr.key))) {
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
                    if (curr.hash == hash && (curr.key == k || k.equals(curr.key))) {
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