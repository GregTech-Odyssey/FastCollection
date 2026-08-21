package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * A {@link MapCache} with identity-based keys: keys are hashed with
 * {@link System#identityHashCode} and compared with {@code ==}, so two distinct
 * objects are never treated as the same key regardless of their {@code equals}
 * method. This is the cache to use when you want to key by object identity (e.g.
 * per-instance attributes) rather than by value.
 *
 * <p>Thread safety mirrors {@link CustomHashCache}: power-of-two segments, each
 * guarded by its own {@link StampedLock}, with an optimistic fast path for reads.
 */
public final class IdentityHashCache<K, V> implements MapCache<K, V> {

    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;
    private final Function<? super K, ? extends V> createFunction;

    /** Creates a cache with default concurrency and no factory. */
    public IdentityHashCache() {
        this(Runtime.getRuntime().availableProcessors(), null);
    }

    /**
     * Creates a cache with default concurrency and the given factory.
     *
     * @throws NullPointerException if {@code createFunction} is {@code null}
     */
    public IdentityHashCache(Function<? super K, ? extends V> createFunction) {
        this(Runtime.getRuntime().availableProcessors(), createFunction);
    }

    /** Creates a cache with the given concurrency level and no factory. */
    public IdentityHashCache(int concurrencyLevel) {
        this(concurrencyLevel, null);
    }

    /**
     * Creates a cache with the given concurrency level and factory.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     * @throws NullPointerException     if {@code createFunction} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public IdentityHashCache(int concurrencyLevel, Function<? super K, ? extends V> createFunction) {
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
        this.createFunction = createFunction;
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ssize <<= 1;
        }
        this.segmentShift = 32 - Integer.numberOfTrailingZeros(ssize);
        this.segmentMask = ssize - 1;
        this.segments = new Segment[ssize];
        for (int i = 0; i < ssize; i++) {
            segments[i] = new Segment<>();
        }
    }

    /** {@inheritDoc} The function runs under the segment's write lock; it must not recurse. */
    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, mix, createFunction);
    }

    /** {@inheritDoc} */
    @Override
    public Function<? super K, ? extends V> createFunction() {
        return this.createFunction;
    }

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCache(final K k) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, mix, createFunction);
    }

    /** {@inheritDoc} Runs the function outside all locks, so it may recurse. */
    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCacheRecursive(k, mix, createFunction);
    }

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCacheRecursive(final K k) {
        return getCacheRecursive(k, this.createFunction);
    }

    /** {@inheritDoc} */
    @Override
    public V getIfPresent(final K k) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getIfAbsent(k, mix);
    }

    /** {@inheritDoc} */
    @Override
    public V putIfAbsent(final K k, final V v) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].put(k, v, mix);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        for (var seg : segments) {
            seg.clear();
        }
    }

    /**
     * A striped segment: an independently locked hash table with separate
     * chaining, extending {@link StampedLock} so lock calls are direct. Keys are
     * compared by identity ({@code ==}); the pre-mixed bucket index is passed in
     * from the cache level so no hash is recomputed here.
     */
    private final static class Segment<K, V> extends StampedLock {
        private volatile Node<K, V>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment() {
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
        private V getCache(final K k, final int mix, Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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
                    if (curr.key == k) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                final var v = createFunction.apply(k);
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
         * Recursive variant: probes optimistically, then runs the function with
         * every lock released so it may call back into this cache, and finally
         * stores the result under the write lock, keeping the value computed by
         * another thread if one landed first.
         */
        private V getCacheRecursive(final K k, final int mix, Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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
                    if (curr.key == k) {
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
                    if (curr.key == k) {
                        // another thread computed and inserted a value first
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

        /** Read-only lookup; never stores anything. */
        private V getIfAbsent(final K k, final int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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

        /** Inserts only if absent, returning the value now bound to the key. */
        private V put(final K k, final V v, final int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
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
                        newTab[HashCommon.mix(System.identityHashCode(e.key)) & newMask] = e;
                    } else {
                        Node<K, V> loHead = null, loTail = null;
                        Node<K, V> hiHead = null, hiTail = null;
                        Node<K, V> next;
                        do {
                            next = e.next;
                            if ((HashCommon.mix(System.identityHashCode(e.key)) & oldCap) == 0) {
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
                            newTab[HashCommon.mix(System.identityHashCode(loHead.key)) & newMask] = loHead;
                        }
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[HashCommon.mix(System.identityHashCode(hiHead.key)) & newMask] = hiHead;
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
        private volatile Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}