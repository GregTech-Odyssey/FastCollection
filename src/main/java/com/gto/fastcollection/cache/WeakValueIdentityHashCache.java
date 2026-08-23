package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * A {@link MapCache} combining weak values (see {@link WeakValueHashCache}) with
 * identity-based keys (see {@link IdentityHashCache}): keys are hashed with
 * {@link System#identityHashCode} and compared with {@code ==}, values are held
 * by {@link java.lang.ref.WeakReference}. Use it to attach short-lived data to
 * object instances without keeping either the key or the value alive.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 */
public final class WeakValueIdentityHashCache<K, V> extends Segmented<WeakValueIdentityHashCache.Segment<K, V>> implements MapCache<K, V>, ICleanableCache {


    /**
     * Creates a cache with default concurrency and the given factory;
     * {@code null} is allowed and behaves like the no-factory constructor.
     */
    public WeakValueIdentityHashCache() {
        this(Concurrents.NCPU);
    }


    /**
     * Creates a cache with the given concurrency level and factory; registers
     * this cache with {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakValueIdentityHashCache(int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>());
        CacheCleaner.add(this);
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, mix, createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, mix, createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getCache(k, mix, createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).getIfAbsent(k, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        int hash = System.identityHashCode(k);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).put(k, v, mix);
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
     * A striped segment whose entries are {@code Node}s extending
     * {@link WeakReference} (weak value, strong key, identity-compared) on the
     * shared {@link HashSegment} skeleton; nodes store no hash, so resizing
     * re-derives it. Dead nodes are replaced in place by writers and dropped in
     * bulk by the inherited sweep; readers never mutate the chain.
     */
    final static class Segment<K, V> extends HashSegment<Node<K, V>> {

        private Segment() {
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Node<K, V>[] newArray(int capacity) {
            return new Node[capacity];
        }

        @Override
        protected int nodeHash(Node<K, V> node) {
            return System.identityHashCode(node.key);
        }

        @Override
        protected boolean isDead(Node<K, V> node) {
            return node.get() == null;
        }

        /**
         * Recursive variant: probes under the read lock, runs the function with
         * every lock released so it may call back into this cache, and finally
         * stores the result under the write lock — replacing a dead node if one
         * occupies the slot, or keeping another thread's value if it landed
         * first.
         */
        private V getCache(final K k, final int mix, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
            long stamp = readLock();
            try {
                Node<K, V> curr = table[mix & mask];
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
            final var k1 = keyMappingFunction.apply(k);
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<K, V> node = table[index];
                Node<K, V> prev = null;
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.key == k1) {
                        V existing = curr.get();
                        if (existing != null) {
                            return existing;
                        }
                        // value was collected: replace the dead node with the computed value
                        var n = new Node<>(k1, v, curr.next);
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
                table[index] = new Node<>(k1, v, node);
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
        private V getIfAbsent(final K k, final int mix) {
            long stamp = readLock();
            try {
                final int index = mix & mask;
                Node<K, V> curr = table[index];
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
        private V put(final K k, final V v, final int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<K, V> node = table[index];
                Node<K, V> prev = null;
                Node<K, V> curr = node;
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

    static final class Node<K, V> extends WeakReference<V> implements ChainNode<Node<K, V>> {

        private final K key;
        private volatile Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            super(value);
            this.key = key;
            this.next = next;
        }

        @Override
        public Node<K, V> getNext() {
            return next;
        }

        @Override
        public void setNext(Node<K, V> next) {
            this.next = next;
        }
    }
}