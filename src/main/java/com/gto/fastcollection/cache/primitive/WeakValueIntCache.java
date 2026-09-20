package com.gto.fastcollection.cache.primitive;

import com.gto.fastcollection.Concurrents;
import com.gto.fastcollection.cache.CacheCleaner;
import com.gto.fastcollection.cache.ChainNode;
import com.gto.fastcollection.cache.HashSegment;
import com.gto.fastcollection.cache.ICleanableCache;
import com.gto.fastcollection.cache.Segmented;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.WeakReference;
import java.util.function.IntFunction;

/**
 * A segmented concurrent cache from primitive {@code int} keys to weak
 * object values (keys stay strong). A value is automatically dropped once it is
 * garbage collected, so the cache can hold derived data without pinning it for
 * the caller's lifetime.
 *
 * <p>Because values are weak, "present" and "absent" are time-dependent. Dead
 * entries are pruned eagerly on writes (a write to a collected key reuses the
 * node) and lazily by {@link CacheCleaner} through {@link #clearCache()}.
 *
 * <p>Concurrency mirrors the strong primitive caches: power-of-two segments,
 * each guarded by a {@link java.util.concurrent.locks.StampedLock}; the create
 * function runs outside every lock so it may call back into this cache.
 *
 * @param <V> the value type
 * @see IntCache
 */
public final class WeakValueIntCache<V> extends Segmented<WeakValueIntCache.Segment<V>> implements ICleanableCache {

    private final IntFunction<? extends V> createFunction;

    /**
     * Creates a cache with default concurrency and no default create function.
     */
    public WeakValueIntCache() {
        this(Concurrents.NCPU, null);
    }

    /**
     * Creates a cache with default concurrency and the given default create
     * function; {@code null} is allowed and behaves like the no-factory
     * constructor.
     */
    public WeakValueIntCache(IntFunction<? extends V> createFunction) {
        this(Concurrents.NCPU, createFunction);
    }

    /**
     * Creates a cache with the given concurrency level and no default create function.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakValueIntCache(int concurrencyLevel) {
        this(concurrencyLevel, null);
    }

    /**
     * Creates a cache with the given concurrency level and default create function;
     * registers this cache with {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakValueIntCache(int concurrencyLevel, IntFunction<? extends V> createFunction) {
        super(concurrencyLevel, i -> new Segment<>());
        this.createFunction = createFunction;
        CacheCleaner.add(this);
    }

    /**
     * Returns the cached value for {@code k}, computing it with the default
     * create function if absent or collected. The create function must be non-null.
     */
    public V getCache(final int k) {
        int mix = HashCommon.mix(k);
        return segmentFor(mix).getCache(k, mix, createFunction);
    }

    /**
     * Returns the cached value for {@code k}, computing it with
     * {@code createFunction} if absent or collected. The function runs outside
     * every lock so it may recursively call back into this cache.
     */
    public V getCache(final int k, IntFunction<? extends V> createFunction) {
        int mix = HashCommon.mix(k);
        return segmentFor(mix).getCache(k, mix, createFunction);
    }

    /**
     * Returns the value bound to {@code k}, or {@code null} if absent or collected.
     */
    public V getIfPresent(final int k) {
        int mix = HashCommon.mix(k);
        return segmentFor(mix).getIfAbsent(k, mix);
    }

    /**
     * Inserts {@code v} only if {@code k} is absent or its value was collected;
     * returns the value now bound.
     */
    public V putIfAbsent(final int k, final V v) {
        int mix = HashCommon.mix(k);
        return segmentFor(mix).put(k, v, mix);
    }

    /**
     * Removes every entry.
     */
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
     * A striped segment whose entries are {@code Node}s extending
     * {@link WeakReference} (weak value, strong primitive key) on the shared
     * {@link HashSegment} skeleton; nodes store no separate hash, so resizing
     * re-derives it. Dead nodes are replaced in place by writers and dropped in
     * bulk by the inherited sweep; readers never mutate the chain.
     */
    static final class Segment<V> extends HashSegment<Node<V>> {

        private Segment() {
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Node<V>[] newArray(int capacity) {
            return new Node[capacity];
        }

        @Override
        protected int nodeHash(Node<V> node) {
            return node.key;
        }

        @Override
        protected boolean isDead(Node<V> node) {
            return node.get() == null;
        }

        /**
         * Probes under the read lock, runs the function with every lock released
         * so it may call back into this cache, and finally stores the result
         * under the write lock — replacing a dead node if one occupies the slot,
         * or keeping another thread's value if it landed first.
         */
        private V getCache(final int k, final int mix, IntFunction<? extends V> createFunction) {
            long stamp = readLock();
            try {
                Node<V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<V> node = table[index];
                Node<V> prev = null;
                Node<V> curr = node;
                while (curr != null) {
                    if (curr.key == k) {
                        V existing = curr.get();
                        if (existing != null) {
                            return existing;
                        }
                        // value was collected: replace the dead node with the computed value
                        var n = new Node<>(k, v, curr.next);
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
                table[index] = new Node<>(k, v, node);
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
        private V getIfAbsent(final int k, final int mix) {
            long stamp = readLock();
            try {
                Node<V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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
        private V put(final int k, final V v, final int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<V> node = table[index];
                Node<V> prev = null;
                Node<V> curr = node;
                while (curr != null) {
                    if (curr.key == k) {
                        V old = curr.get();
                        if (old != null) return old;
                        // value was collected: replace the dead node with the new value
                        var n = new Node<>(k, v, curr.next);
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
                table[index] = new Node<>(k, v, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }
    }

    static final class Node<V> extends WeakReference<V> implements ChainNode<Node<V>> {

        private final int key;
        private volatile Node<V> next;

        private Node(int key, V value, Node<V> next) {
            super(value);
            this.key = key;
            this.next = next;
        }

        @Override
        public Node<V> getNext() {
            return next;
        }

        @Override
        public void setNext(Node<V> next) {
            this.next = next;
        }
    }
}