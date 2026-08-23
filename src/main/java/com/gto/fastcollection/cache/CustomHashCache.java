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
 * A {@link MapCache} that buckets keys into segments and protects each segment
 * with its own {@link StampedLock}. Key hashing, equality and the mapping of
 * keys to segments are all driven by a custom {@link Hash.Strategy}, so this is
 * the right cache when keys need value-based semantics that {@code hashCode} /
 * {@code equals} cannot express (e.g. field-based identity).
 *
 * <p>Thread safety: segment count is a power of two rounded up from the requested
 * concurrency level; operations on distinct segments proceed fully in parallel.
 * Reads take the segment's shared read lock while writes take the exclusive
 * write lock, so readers never block each other.
 */
public final class CustomHashCache<K, V> extends Segmented<CustomHashCache.Segment<K, V>> implements MapCache<K, V> {

    private final Hash.Strategy<? super K> strategy;

    /**
     * Creates a cache with default concurrency and the given factory;
     * {@code null} is allowed and behaves like the no-factory constructor.
     */
    public CustomHashCache(Hash.Strategy<? super K> strategy) {
        this(strategy, Concurrents.NCPU);
    }

    /**
     * Creates a cache with the given concurrency level and factory.
     *
     * @param concurrencyLevel upper bound on the number of threads concurrently
     *                         updating distinct segments; rounded up to a power of two
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public CustomHashCache(Hash.Strategy<? super K> strategy, int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>(strategy));
        this.strategy = strategy;
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
     * A striped segment: an independently locked separate-chaining hash table
     * on the shared {@link HashSegment} skeleton; keys are hashed and compared
     * by the cache's {@link Hash.Strategy}.
     */
    final static class Segment<K, V> extends HashSegment<Node<K, V>> {
        private final Hash.Strategy<? super K> strategy;

        private Segment(Hash.Strategy<? super K> strategy) {
            this.strategy = strategy;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Node<K, V>[] newArray(int capacity) {
            return new Node[capacity];
        }

        @Override
        protected int nodeHash(Node<K, V> node) {
            return node.hash;
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
        private V getCache(final K k, final int hash, int mix,
                           Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
            long stamp = readLock();
            try {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
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
                final Node<K, V> node = table[index];
                Node<K, V> curr = table[index];
                while (curr != null) {
                    if (curr.hash == hash && (k1 == curr.key || strategy.equals(k1, curr.key))) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k1, v, hash, node);
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
        private V getIfAbsent(final K k, final int hash, int mix) {
            long stamp = readLock();
            try {
                final int index = mix & mask;
                Node<K, V> curr = table[index];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
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
        private V put(final K k, final V v, final int hash, int mix) {
            long stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<K, V> node = table[index];
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k, v, hash, node);
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
        private final int hash;
        private volatile Node<K, V> next;

        private Node(K key, V value, int hash, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
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