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
 * A {@link MapCache} with identity-based keys: keys are hashed with
 * {@link System#identityHashCode} and compared with {@code ==}, so two distinct
 * objects are never treated as the same key regardless of their {@code equals}
 * method. This is the cache to use when you want to key by object identity (e.g.
 * per-instance attributes) rather than by value.
 *
 * <p>Thread safety mirrors {@link CustomHashCache}: power-of-two segments, each
 * guarded by its own {@link StampedLock}; reads take the shared read lock and
 * writes the exclusive write lock.
 */
public final class IdentityHashCache<K, V> extends Segmented<IdentityHashCache.Segment<K, V>> implements MapCache<K, V> {


    /**
     * Creates a cache with default concurrency and the given factory;
     * {@code null} is allowed and behaves like the no-factory constructor.
     */
    public IdentityHashCache() {
        this(Concurrents.NCPU);
    }


    /**
     * Creates a cache with the given concurrency level and factory.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public IdentityHashCache(int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>());
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
     * A striped segment: an independently locked separate-chaining hash table
     * on the shared {@link HashSegment} skeleton; keys are compared by identity
     * ({@code ==}) and nodes store no hash, so resizing re-derives it.
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
            return false;
        }

        /**
         * Recursive variant: probes under the read lock, runs the function with
         * every lock released so it may call back into this cache, and finally
         * stores the result under the write lock, keeping the value computed by
         * another thread if one landed first.
         */
        private V getCache(final K k, final int mix, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
            long stamp = readLock();
            try {
                Node<K, V> curr = table[mix & mask];
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
            final var k1 = keyMappingFunction.apply(k);
            stamp = writeLock();
            try {
                final int index = mix & mask;
                Node<K, V> curr = table[index];
                while (curr != null) {
                    if (curr.key == k1) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k1, v, table[index]);
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
        private V getIfAbsent(final K k, final int mix) {
            long stamp = readLock();
            try {
                final int index = mix & mask;
                Node<K, V> curr = table[index];
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
         * Inserts only if absent, returning the value now bound to the key.
         */
        private V put(final K k, final V v, final int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<K, V> node = table[index];
                Node<K, V> curr = node;
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
    static final class Node<K, V> implements ChainNode<Node<K, V>> {

        private final K key;
        private final V value;
        private volatile Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
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
