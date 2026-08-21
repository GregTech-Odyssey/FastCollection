package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

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
 * Reads use an optimistic fast path and fall back to a shared read lock, while
 * writes take the exclusive write lock, so read-heavy workloads avoid lock
 * contention almost entirely.
 */
public final class CustomHashCache<K, V> implements MapCache<K, V> {

    private final Hash.Strategy<? super K> strategy;
    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;
    private final Function<? super K, ? extends V> createFunction;

    /** Creates a cache with default concurrency and no factory. */
    public CustomHashCache(Hash.Strategy<? super K> strategy) {
        this(strategy, Runtime.getRuntime().availableProcessors(), null);
    }

    /**
     * Creates a cache with default concurrency and the given factory.
     *
     * @throws NullPointerException if {@code createFunction} is {@code null}
     */
    public CustomHashCache(Hash.Strategy<? super K> strategy, Function<? super K, ? extends V> createFunction) {
        this(strategy, Runtime.getRuntime().availableProcessors(), createFunction);
    }

    /** Creates a cache with the given concurrency level and no factory. */
    public CustomHashCache(Hash.Strategy<? super K> strategy, int concurrencyLevel) {
        this(strategy, concurrencyLevel, null);
    }

    /**
     * Creates a cache with the given concurrency level and factory.
     *
     * @param concurrencyLevel upper bound on the number of threads concurrently
     *                         updating distinct segments; rounded up to a power of two
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     * @throws NullPointerException     if {@code createFunction} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public CustomHashCache(Hash.Strategy<? super K> strategy, int concurrencyLevel,
                           Function<? super K, ? extends V> createFunction) {
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
        this.strategy = strategy;
        this.createFunction = createFunction;
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ssize <<= 1;
        }
        this.segmentShift = 32 - Integer.numberOfTrailingZeros(ssize);
        this.segmentMask = ssize - 1;
        this.segments = new Segment[ssize];
        for (int i = 0; i < ssize; i++) {
            segments[i] = new Segment<>(strategy);
        }
    }

    /** {@inheritDoc} The function runs under the segment's write lock; it must not recurse. */
    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, hash, mix, createFunction);
    }

    /** {@inheritDoc} */
    @Override
    public Function<? super K, ? extends V> createFunction() {
        return this.createFunction;
    }

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCache(final K k) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, hash, mix, createFunction);
    }

    /** {@inheritDoc} Runs the function outside all locks, so it may recurse. */
    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCacheRecursive(k, hash, mix, createFunction);
    }

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCacheRecursive(final K k) {
        return getCacheRecursive(k, this.createFunction);
    }

    /** {@inheritDoc} */
    @Override
    public V getIfPresent(final K k) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getIfAbsent(k, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public V putIfAbsent(final K k, final V v) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].put(k, v, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        for (var seg : segments) {
            seg.clear();
        }
    }


    /**
     * A striped segment: an independently locked open-addressing-free hash table
     * (separate chaining) that extends {@link StampedLock} so lock calls are
     * direct. Only the segment's own table is touched while its lock is held;
     * other segments proceed in parallel. {@code table}/{@code mask} are volatile
     * because a resize swaps them; {@code size}/{@code maxFill} are only mutated
     * under the write lock and read under the read lock.
     */
    private final static class Segment<K, V> extends StampedLock {
        private final Hash.Strategy<? super K> strategy;
        private volatile Node<K, V>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment(Hash.Strategy<? super K> strategy) {
            this.strategy = strategy;
            int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
            this.table = new Node[n];
            this.mask = n - 1;
            this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
        }

        /**
         * Fast-path read via optimistic locking; on a stale or empty probe, the
         * function is applied and the entry stored under the exclusive write lock.
         * The function must not recurse into this cache (see getCacheRecursive).
         */
        private V getCache(final K k, final int hash, int mix, Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V v = curr.value;
                        if (validate(stamp)) {
                            return v;
                        }
                        break;
                    }
                    curr = curr.next;
                }
            }
            stamp = writeLock();
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
                final var v = createFunction.apply(k);
                table[index] = new Node<>(k, v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }

        /**
         * Recursive variant: probes optimistically, then runs the function with
         * every lock released so it may call back into this cache, and finally
         * stores the result under the write lock, keeping the value computed by
         * another thread if one landed first.
         */
        private V getCacheRecursive(final K k, final int hash, int mix,
                                    Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V v = curr.value;
                        if (validate(stamp)) {
                            return v;
                        }
                        break;
                    }
                    curr = curr.next;
                }
            }
            stamp = readLock();
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
            // run outside every lock so the function may recursively call back into this cache
            final var v = createFunction.apply(k);
            if (v == null) {
                return null;
            }
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<K, V> node = table[index];
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        // another thread computed and inserted a value first
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

        /** Read-only lookup; never stores anything. */
        private V getIfAbsent(final K k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V v = curr.value;
                        if (validate(stamp)) {
                            return v;
                        }
                        break;
                    }
                    curr = curr.next;
                }
                if (validate(stamp)) {
                    return null;
                }
            }
            stamp = readLock();
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

        /** Inserts only if absent, returning the value now bound to the key. */
        private V put(final K k, final V v, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        V old = curr.value;
                        if (validate(stamp)) {
                            return old;
                        }
                        break;
                    }
                    curr = curr.next;
                }
            }
            stamp = writeLock();
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

        /** Doubles the table and rehashes every chain; called with the write lock held. */
        @SuppressWarnings("unchecked")
        private void resize() {
            Node<K, V>[] oldTab = table;
            int oldCap = oldTab.length;
            int newCap = oldCap << 1;
            Node<K, V>[] newTab = new Node[newCap];
            int newMask = newCap - 1;
            for (int i = 0; i < oldCap; ++i) {
                Node<K, V> e;
                if ((e = oldTab[i]) != null) {
                    oldTab[i] = null;
                    if (e.next == null) {
                        newTab[HashCommon.mix(e.hash) & newMask] = e;
                    } else {
                        Node<K, V> loHead = null, loTail = null;
                        Node<K, V> hiHead = null, hiTail = null;
                        Node<K, V> next;
                        do {
                            next = e.next;
                            if ((HashCommon.mix(e.hash) & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e;
                                else
                                    loTail.next = e;
                                loTail = e;
                            } else {
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null);
                        if (loTail != null) {
                            loTail.next = null;
                            newTab[HashCommon.mix(loHead.hash) & newMask] = loHead;
                        }
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[HashCommon.mix(hiHead.hash) & newMask] = hiHead;
                        }
                    }
                }
            }
            table = newTab;
            mask = newMask;
            maxFill = (int) (newCap * Hash.DEFAULT_LOAD_FACTOR);
        }

        /** Empties the table under the write lock. */
        private void clear() {
            long stamp = writeLock();
            try {
                if (size == 0) return;
                size = 0;
                Arrays.fill(table, null);
            } finally {
                unlockWrite(stamp);
            }
        }
    }

    /** A single entry in a chain; immutable except for {@code next}. */
    private static final class Node<K, V> {

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
    }
}