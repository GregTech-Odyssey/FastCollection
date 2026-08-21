package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * An {@link Interner} with the same segmented, {@link StampedLock}-guarded
 * design as {@link CustomHashCache}, using a custom {@link Hash.Strategy} for
 * hashing and equality. Use it when "equal" must be decided by application
 * logic (e.g. comparing only a subset of fields) rather than by {@code equals}.
 */
public final class CustomHashInterner<T> implements Interner<T> {

    private final Hash.Strategy<? super T> strategy;
    private final Segment<T>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    /** Creates an interner with default concurrency. */
    public CustomHashInterner(Hash.Strategy<? super T> strategy) {
        this(Runtime.getRuntime().availableProcessors(), strategy);
    }

    /**
     * Creates an interner with the given concurrency level.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    @SuppressWarnings("unchecked")
    public CustomHashInterner(int concurrencyLevel, Hash.Strategy<? super T> strategy) {
        this.strategy = strategy;
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
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

    /** {@inheritDoc} */
    @Override
    public T intern(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].intern(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPresent(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].contains(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean addIfAbsent(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].add(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        for (var seg : segments) {
            seg.clear();
        }
    }

    /** A striped segment holding canonical instances; see {@link CustomHashCache.Segment}. */
    private final static class Segment<T> extends StampedLock {
        private final Hash.Strategy<? super T> strategy;
        private volatile Node<T>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment(Hash.Strategy<? super T> strategy) {
            this.strategy = strategy;
            int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
            this.table = new Node[n];
            this.mask = n - 1;
            this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
        }

        /** Optimistic lookup; on a miss the canonical instance is stored under the write lock. */
        private T intern(final T k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<T> curr = table[mix & mask];
                while (curr != null) {
                    var key = curr.key;
                    if (curr.hash == hash && (key == k || strategy.equals(k, key))) {
                        if (validate(stamp)) {
                            return key;
                        }
                        break;
                    }
                    curr = curr.next;
                }
            }
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<T> node = table[index];
                Node<T> curr = node;
                while (curr != null) {
                    var key = curr.key;
                    if (curr.hash == hash && (key == k || strategy.equals(k, key))) {
                        return key;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return k;
            } finally {
                unlockWrite(stamp);
            }
        }

        /** Read-only membership test; never inserts anything. */
        private boolean contains(final T k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<T> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (curr.key == k || strategy.equals(k, curr.key))) {
                        if (validate(stamp)) {
                            return true;
                        }
                        break;
                    }
                    curr = curr.next;
                }
                if (validate(stamp)) {
                    return false;
                }
            }
            stamp = readLock();
            try {
                final int index = mix & mask;
                Node<T> curr = table[index];
                while (curr != null) {
                    if (curr.hash == hash && (curr.key == k || strategy.equals(k, curr.key))) {
                        return true;
                    }
                    curr = curr.next;
                }
                return false;
            } finally {
                unlockRead(stamp);
            }
        }

        /** Inserts only if absent, returning whether a new canonical instance was stored. */
        private boolean add(final T k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                Node<T> curr = table[mix & mask];
                while (curr != null) {
                    if (curr.hash == hash && (curr.key == k || strategy.equals(k, curr.key))) {
                        if (validate(stamp)) {
                            return false;
                        }
                        break;
                    }
                    curr = curr.next;
                }
            }
            stamp = writeLock();
            try {
                final int index = mix & mask;
                final Node<T> node = table[index];
                Node<T> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (curr.key == k || strategy.equals(k, curr.key))) {
                        return false;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(k, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return true;
            } finally {
                unlockWrite(stamp);
            }
        }


        /** Doubles the table and rehashes every chain; called with the write lock held. */
        @SuppressWarnings("unchecked")
        private void resize() {
            Node<T>[] oldTab = table;
            int oldCap = oldTab.length;
            int newCap = oldCap << 1;
            Node<T>[] newTab = new Node[newCap];
            int newMask = newCap - 1;
            for (int i = 0; i < oldCap; ++i) {
                Node<T> e;
                if ((e = oldTab[i]) != null) {
                    oldTab[i] = null;
                    if (e.next == null) {
                        newTab[HashCommon.mix(e.hash) & newMask] = e;
                    } else {
                        Node<T> loHead = null, loTail = null;
                        Node<T> hiHead = null, hiTail = null;
                        Node<T> next;
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
    private static final class Node<T> {

        private final T key;
        private final int hash;
        private volatile Node<T> next;

        private Node(T key, int hash, Node<T> next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }
    }
}
