package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * An {@link Interner} whose canonical instances are held by
 * {@link java.lang.ref.WeakReference}. An interned instance is dropped as soon
 * as no other reference to it remains, so the interner never keeps canonical
 * instances alive on its own — suitable when callers create many short-lived
 * equal objects and you only want to share instances that are still in use.
 *
 * <p>Because references are weak, a canonical instance may disappear between
 * calls once it is collected (and a later {@link #intern} of an equal object
 * will create a fresh one). Dead entries are pruned lazily on writes and by
 * {@link CacheCleaner} through {@link #clearCache()}.
 *
 * <p>Concurrency mirrors {@link CustomHashCache}: power-of-two segments, each
 * guarded by a {@link StampedLock}, with an optimistic read fast path.
 */
public final class WeakHashInterner<T> implements Interner<T>, ICleanableCache {

    private final Segment<T>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    /** Creates an interner with default concurrency. */
    public WeakHashInterner() {
        this(Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates an interner with the given concurrency level; registers it with
     * {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    @SuppressWarnings("unchecked")
    public WeakHashInterner(int concurrencyLevel) {
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
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
    public T intern(final T sample) {
        int hash = sample.hashCode();
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].intern(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPresent(final T sample) {
        int hash = sample.hashCode();
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].contains(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean addIfAbsent(final T sample) {
        int hash = sample.hashCode();
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

    /** {@inheritDoc} Drops every entry whose instance has been collected. */
    @Override
    public void clearCache() {
        for (var seg : segments) {
            seg.clearInvalid();
        }
    }

    /**
     * A striped segment holding canonical instances as {@link WeakReferenceNode}s
     * (weak referent, stored hash). A node whose referent has been collected is
     * dead: writers remove it in passing and {@link #clearInvalid()} sweeps in
     * bulk; readers never mutate the chain.
     */
    private final static class Segment<T> extends StampedLock {
        private volatile WeakReferenceNode<T>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment() {
            int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
            this.table = new WeakReferenceNode[n];
            this.mask = n - 1;
            this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
        }

        /** Optimistic lookup; on a miss the canonical instance is stored under the write lock. */
        private T intern(final T k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                WeakReferenceNode<T> curr = table[mix & mask];
                while (curr != null) {
                    T key = curr.get();
                    if (key != null && curr.hash == hash && (key == k || k.equals(key))) {
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
                WeakReferenceNode<T> node = table[index];
                WeakReferenceNode<T> prev = null;
                WeakReferenceNode<T> curr = node;
                while (curr != null) {
                    T key = curr.get();
                    if (key == null) {
                        if (prev == null) {
                            node = curr.next;
                            table[index] = node;
                        } else {
                            prev.next = curr.next;
                        }
                        size--;
                    } else {
                        if (curr.hash == hash && (key == k || k.equals(key))) {
                            return key;
                        }
                        prev = curr;
                    }
                    curr = curr.next;
                }
                table[index] = new WeakReferenceNode<>(k, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return k;
            } finally {
                unlockWrite(stamp);
            }
        }

        /** Read-only membership test; never mutates the chain. */
        private boolean contains(final T k, final int hash, int mix) {
            long stamp = tryOptimisticRead();
            if (validate(stamp)) {
                WeakReferenceNode<T> curr = table[mix & mask];
                while (curr != null) {
                    T key = curr.get();
                    if (key != null && curr.hash == hash && (key == k || k.equals(key))) {
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
                WeakReferenceNode<T> curr = table[index];
                while (curr != null) {
                    T key = curr.get();
                    if (key == null) {
                        // collected entry: treat as absent; readers must not mutate the chain
                        return false;
                    }
                    if (curr.hash == hash && (key == k || k.equals(key))) {
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
                WeakReferenceNode<T> curr = table[mix & mask];
                while (curr != null) {
                    T key = curr.get();
                    if (key != null && curr.hash == hash && (key == k || k.equals(key))) {
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
                WeakReferenceNode<T> node = table[index];
                WeakReferenceNode<T> prev = null;
                WeakReferenceNode<T> curr = node;
                while (curr != null) {
                    T key = curr.get();
                    if (key == null) {
                        if (prev == null) {
                            node = curr.next;
                            table[index] = node;
                        } else {
                            prev.next = curr.next;
                        }
                        size--;
                    } else {
                        if (curr.hash == hash && (key == k || k.equals(key))) {
                            return false;
                        }
                        prev = curr;
                    }
                    curr = curr.next;
                }
                table[index] = new WeakReferenceNode<>(k, hash, node);
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
            WeakReferenceNode<T>[] oldTab = table;
            int oldCap = oldTab.length;
            int newCap = oldCap << 1;
            WeakReferenceNode<T>[] newTab = new WeakReferenceNode[newCap];
            int newMask = newCap - 1;
            for (int i = 0; i < oldCap; ++i) {
                WeakReferenceNode<T> e;
                if ((e = oldTab[i]) != null) {
                    oldTab[i] = null;
                    if (e.next == null) {
                        newTab[HashCommon.mix(e.hash) & newMask] = e;
                    } else {
                        WeakReferenceNode<T> loHead = null, loTail = null;
                        WeakReferenceNode<T> hiHead = null, hiTail = null;
                        WeakReferenceNode<T> next;
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

        /** Sweeps every node whose referent has been collected; called under the write lock. */
        private void clearInvalid() {
            long stamp = writeLock();
            try {
                for (int i = 0; i < table.length; i++) {
                    WeakReferenceNode<T> prev = null;
                    WeakReferenceNode<T> curr = table[i];
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
}
