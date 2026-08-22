package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
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
 * guarded by a {@link StampedLock}; reads take the shared read lock and writes
 * the exclusive write lock.
 */
public final class WeakHashInterner<T> extends Segmented<WeakHashInterner.Segment<T>> implements Interner<T>, ICleanableCache {

    /** Creates an interner with default concurrency. */
    public WeakHashInterner() {
        this(Concurrents.NCPU);
    }

    /**
     * Creates an interner with the given concurrency level; registers it with
     * {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakHashInterner(int concurrencyLevel) {
        super(concurrencyLevel, i -> new Segment<>());
        CacheCleaner.add(this);
    }

    /** {@inheritDoc} */
    @Override
    public T intern(final T sample) {
        int hash = sample.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).intern(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPresent(final T sample) {
        int hash = sample.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).contains(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public boolean addIfAbsent(final T sample) {
        int hash = sample.hashCode();
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).add(sample, hash, mix);
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        clearSegments();
    }

    /** {@inheritDoc} Drops every entry whose instance has been collected. */
    @Override
    public void clearCache() {
        sweepSegments();
    }

    /**
     * A striped segment holding canonical instances as {@link WeakReferenceNode}s
     * (weak referent, stored hash) on the shared {@link HashSegment} skeleton.
     * A node whose referent has been collected is dead: writers remove it in
     * passing and the inherited sweep drops it in bulk; readers never mutate
     * the chain.
     */
    final static class Segment<T> extends HashSegment<WeakReferenceNode<T>> {

        private Segment() {
        }

        @Override
        @SuppressWarnings("unchecked")
        protected WeakReferenceNode<T>[] newArray(int capacity) {
            return new WeakReferenceNode[capacity];
        }

        @Override
        protected int nodeHash(WeakReferenceNode<T> node) {
            return node.hash;
        }

        @Override
        protected boolean isDead(WeakReferenceNode<T> node) {
            return node.get() == null;
        }

        /** Lookup under the read lock; on a miss the canonical instance is stored under the write lock. */
        private T intern(final T k, final int hash, int mix) {
            long stamp = readLock();
            try {
                WeakReferenceNode<T> curr = table[mix & mask];
                while (curr != null) {
                    T key = curr.get();
                    if (key != null && curr.hash == hash && (key == k || k.equals(key))) {
                        return key;
                    }
                    curr = curr.next;
                }
            }finally {
                unlockRead(stamp);
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
            long stamp = readLock();
            try {
                final int index = mix & mask;
                WeakReferenceNode<T> curr = table[index];
                while (curr != null) {
                    T key = curr.get();
                    // collected nodes are skipped: a dead node earlier in the chain
                    // must not hide a live canonical instance behind it
                    if (key != null && curr.hash == hash && (key == k || k.equals(key))) {
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
            long  stamp = writeLock();
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
    }
}
