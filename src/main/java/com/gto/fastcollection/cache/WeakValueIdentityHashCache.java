package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Function;

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
public final class WeakValueIdentityHashCache<K, V> implements MapCache<K, V>, ICleanableCache {

    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;
    private final Function<? super K, ? extends V> createFunction;

    /** Creates a cache with default concurrency and no factory. */
    public WeakValueIdentityHashCache() {
        this(Runtime.getRuntime().availableProcessors(), null);
    }

    /**
     * Creates a cache with default concurrency and the given factory.
     *
     * @throws NullPointerException if {@code createFunction} is {@code null}
     */
    public WeakValueIdentityHashCache(Function<? super K, ? extends V> createFunction) {
        this(Runtime.getRuntime().availableProcessors(), createFunction);
    }

    /** Creates a cache with the given concurrency level and no factory. */
    public WeakValueIdentityHashCache(int concurrencyLevel) {
        this(concurrencyLevel, null);
    }

    /**
     * Creates a cache with the given concurrency level and factory; registers
     * this cache with {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     * @throws NullPointerException     if {@code createFunction} is {@code null}
     */
    @SuppressWarnings("unchecked")
    public WeakValueIdentityHashCache(int concurrencyLevel, Function<? super K, ? extends V> createFunction) {
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
        CacheCleaner.add(this);
    }

    /** {@inheritDoc} */
    @Override
    public Function<? super K, ? extends V> createFunction() {
        return this.createFunction;
    }

    /** {@inheritDoc} The function runs under the segment's write lock; it must not recurse. */
    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, mix, createFunction);
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
        return getCacheRecursive(k, createFunction);
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

    /** {@inheritDoc} Drops every entry whose value has been collected. */
    @Override
    public void clearCache() {
        for (var seg : segments) {
            seg.clearInvalid();
        }
    }

    /**
     * A striped segment whose entries are {@code Node}s extending
     * {@link WeakReference} (weak value, strong key, identity-compared). Dead
     * nodes are replaced in place by writers and swept in bulk by
     * {@link #clearInvalid()}; readers never mutate the chain.
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
         * Optimistic fast path; on a miss or a dead node the value is computed and
         * stored under the write lock (a dead node is replaced in place). The
         * function must not recurse into this cache (see getCacheRecursive).
         */
        private V getCache(final K k, final int mix, Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        V v = curr.get();
                        if (v != null && validate(stamp)) {
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
                Node<K, V> prev = null;
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.key == k) {
                        V v = curr.get();
                        if (v != null) return v;
                        return insert(index, k, prev, curr.next, createFunction);
                    }
                    prev = curr;
                    curr = curr.next;
                }
                V v = insert(index, k, null, node, createFunction);
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
         * every lock released so it may recurse, and finally stores the result
         * under the write lock — replacing a dead node if one occupies the slot,
         * or keeping another thread's value if it landed first.
         */
        private V getCacheRecursive(final K k, final int mix, Function<? super K, ? extends V> createFunction) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        V v = curr.get();
                        if (v != null && validate(stamp)) {
                            return v;
                        }
                        break;
                    }
                    curr = curr.next;
                }
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
                Node<K, V> prev = null;
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.key == k) {
                        V existing = curr.get();
                        if (existing != null) {
                            // another thread computed and inserted a value first
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

        private V insert(final int index, final K k, Node<K, V> prev,
                         Node<K, V> next, Function<? super K, ? extends V> createFunction) {
            final var v = createFunction.apply(k);
            var n = new Node<>(k, v, next);
            if (prev == null) {
                table[index] = n;
            } else {
                prev.next = n;
            }
            return v;
        }

        /** Read-only lookup; never mutates the chain. */
        private V getIfAbsent(final K k, final int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        V v = curr.get();
                        if (v != null && validate(stamp)) {
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
                        V v = curr.get();
                        // collected value: the key is absent; readers must not mutate the chain
                        if (v != null) return v;
                        return null;
                    }
                    curr = curr.next;
                }
                return null;
            } finally {
                unlockRead(stamp);
            }
        }

        /** Inserts only if absent, replacing a dead node; returns the value now bound. */
        private V put(final K k, final V v, final int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<K, V> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.key == k) {
                        V old = curr.get();
                        if (old != null && validate(stamp)) {
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

        /** Sweeps every node whose value has been collected; called under the write lock. */
        private void clearInvalid() {
            long stamp = writeLock();
            try {
                for (int i = 0; i < table.length; i++) {
                    Node<K, V> prev = null;
                    Node<K, V> curr = table[i];
                    while (curr != null) {
                        if (curr.get() == null) {
                            if (prev == null) {
                                table[i] = curr.next;
                            } else {
                                prev.next = curr.next;
                            }
                            size--;
                            curr = curr.next;
                        } else {
                            prev = curr;
                            curr = curr.next;
                        }
                    }
                }
            } finally {
                unlockWrite(stamp);
            }
        }
    }

    private static final class Node<K, V> extends WeakReference<V> {

        private final K key;
        private volatile Node<K, V> next;

        private Node(K key, V value, Node<K, V> next) {
            super(value);
            this.key = key;
            this.next = next;
        }
    }
}