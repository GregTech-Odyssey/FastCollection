package com.gto.fastcollection.cache.primitive;

import com.gto.fastcollection.Concurrents;
import com.gto.fastcollection.cache.ChainNode;
import com.gto.fastcollection.cache.HashSegment;
import com.gto.fastcollection.cache.Segmented;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.function.LongFunction;

/**
 * A segmented concurrent cache from primitive {@code long} keys to strong
 * object values. Keys are mixed with {@link HashCommon#mix} and compared by
 * value. Concurrency mirrors the object
 * caches: power-of-two segments, each guarded by a {@link java.util.concurrent.locks.StampedLock}.
 *
 * <p>{@link #getCache(long)} / {@link #getCache(long, LongFunction)} run the
 * create function outside every lock so it may call back into this cache;
 * concurrent computations of the same key are merged under the write lock.
 *
 * @param <V> the value type
 */
public final class LongCache<V> extends Segmented<LongCache.Segment<V>> {

    private final LongFunction<? extends V> createFunction;

    /**
     * Creates a cache with default concurrency and no default create function.
     */
    public LongCache() {
        this(Concurrents.NCPU, null);
    }

    /**
     * Creates a cache with default concurrency and the given default create
     * function; {@code null} is allowed and behaves like the no-factory
     * constructor.
     */
    public LongCache(LongFunction<? extends V> createFunction) {
        this(Concurrents.NCPU, createFunction);
    }

    /**
     * Creates a cache with the given concurrency level and no default create function.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public LongCache(int concurrencyLevel) {
        this(concurrencyLevel, null);
    }

    /**
     * Creates a cache with the given concurrency level and default create function.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public LongCache(int concurrencyLevel, LongFunction<? extends V> createFunction) {
        super(concurrencyLevel, i -> new Segment<>());
        this.createFunction = createFunction;
    }

    /**
     * Returns the cached value for {@code k}, computing it with the default
     * create function if absent. The create function must be non-null.
     */
    public V getCache(final long k) {
        int mix = HashCommon.mix(Long.hashCode(k));
        return segmentFor(mix).getCache(k, mix, createFunction);
    }

    /**
     * Returns the cached value for {@code k}, computing it with
     * {@code createFunction} if absent. The function runs outside every lock
     * so it may recursively call back into this cache.
     */
    public V getCache(final long k, LongFunction<? extends V> createFunction) {
        int mix = HashCommon.mix(Long.hashCode(k));
        return segmentFor(mix).getCache(k, mix, createFunction);
    }

    /**
     * Returns the value bound to {@code k}, or {@code null} if absent.
     */
    public V getIfPresent(final long k) {
        int mix = HashCommon.mix(Long.hashCode(k));
        return segmentFor(mix).getIfAbsent(k, mix);
    }

    /**
     * Inserts {@code v} only if {@code k} is absent; returns the value now bound.
     */
    public V putIfAbsent(final long k, final V v) {
        int mix = HashCommon.mix(Long.hashCode(k));
        return segmentFor(mix).put(k, v, mix);
    }

    /**
     * Removes every entry.
     */
    public void clear() {
        clearSegments();
    }

    /**
     * A striped segment: an independently locked separate-chaining hash table
     * on the shared {@link HashSegment} skeleton; primitive keys compared by
     * value, nodes store no separate hash so resizing re-derives it via
     * {@link #nodeHash}.
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
            return Long.hashCode(node.key);
        }

        @Override
        protected boolean isDead(Node<V> node) {
            return false;
        }

        /**
         * Probes under the read lock, runs the function with every lock released
         * so it may call back into this cache, and finally stores the result
         * under the write lock, keeping another thread's value if it landed first.
         */
        private V getCache(final long k, final int mix, LongFunction<? extends V> createFunction) {
            long stamp = readLock();
            try {
                Node<V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        return curr.value;
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
                Node<V> curr = table[index];
                while (curr != null) {
                    if (curr.key == k) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k, v, table[index]);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }

        /**
         * Read-only lookup; never stores anything.
         */
        private V getIfAbsent(final long k, final int mix) {
            long stamp = readLock();
            try {
                Node<V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                return null;
            } finally {
                unlockRead(stamp);
            }
        }

        /**
         * Inserts only if absent; returns the value now bound to the key.
         */
        private V put(final long k, final V v, final int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<V> node = table[index];
                Node<V> curr = node;
                while (curr != null) {
                    if (curr.key == k) {
                        return curr.value;
                    }
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

    /**
     * A single entry in a chain; immutable except for {@code next}.
     */
    static final class Node<V> implements ChainNode<Node<V>> {

        private final long key;
        private final V value;
        private volatile Node<V> next;

        private Node(long key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
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