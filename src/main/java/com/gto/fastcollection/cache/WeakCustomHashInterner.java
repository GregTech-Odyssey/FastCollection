package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.UnaryOperator;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * An {@link Interner} combining weak canonical instances (see
 * {@link WeakHashInterner}) with a custom {@link Hash.Strategy} for hashing and
 * equality (see {@link CustomHashInterner}). Use it when canonical instances
 * should be dropped once unused, and "equal" is decided by application logic.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 */
public final class WeakCustomHashInterner<T> extends Segmented<WeakCustomHashInterner.Segment<T>> implements Interner<T>, ICleanableCache {

    private final Hash.Strategy<? super T> strategy;

    /**
     * Creates an interner with default concurrency.
     */
    public WeakCustomHashInterner(Hash.Strategy<? super T> strategy) {
        this(Concurrents.NCPU, strategy);
    }

    /**
     * Creates an interner with the given concurrency level; registers it with
     * {@link CacheCleaner}.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public WeakCustomHashInterner(int concurrencyLevel, Hash.Strategy<? super T> strategy) {
        super(concurrencyLevel, i -> new Segment<>(strategy));
        this.strategy = strategy;
        CacheCleaner.add(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T intern(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).intern(sample, hash, mix, Interner.identityMappingFunction());
    }

    @Override
    public T intern(T sample, UnaryOperator<T> mappingFunction) {
        int hash = strategy.hashCode(sample);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).intern(sample, hash, mix, mappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPresent(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).contains(sample, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addIfAbsent(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = HashCommon.mix(hash);
        return segmentFor(mix).add(sample, hash, mix);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        clearSegments();
    }

    /**
     * {@inheritDoc} Drops every entry whose instance has been collected.
     */
    @Override
    public void clearCache() {
        sweepSegments();
    }

    /**
     * A striped segment holding canonical instances as {@link WeakReferenceNode}s
     * (weak referent, stored hash, strategy-compared) on the shared
     * {@link HashSegment} skeleton. Dead nodes are removed in passing by
     * writers and dropped in bulk by the inherited sweep; readers never mutate
     * the chain.
     */
    final static class Segment<T> extends HashSegment<WeakReferenceNode<T>> {
        private final Hash.Strategy<? super T> strategy;

        private Segment(Hash.Strategy<? super T> strategy) {
            this.strategy = strategy;
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

        /**
         * Lookup under the read lock; on a miss the canonical instance is stored under the write lock.
         */
        private T intern(final T k, final int hash, int mix, UnaryOperator<T> mappingFunction) {
            long stamp = readLock();
            try {
                WeakReferenceNode<T> curr = table[mix & mask];
                while (curr != null) {
                    T key = curr.get();
                    if (key != null && curr.hash == hash && (key == k || strategy.equals(k, key))) {
                        return key;
                    }
                    curr = curr.next;
                }
            } finally {
                unlockRead(stamp);
            }
            final T v = mappingFunction.apply(k);
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
                        if (curr.hash == hash && (key == v || strategy.equals(v, key))) {
                            return key;
                        }
                        prev = curr;
                    }
                    curr = curr.next;
                }
                table[index] = new WeakReferenceNode<>(v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }

        /**
         * Read-only membership test; never mutates the chain.
         */
        private boolean contains(final T k, final int hash, int mix) {
            long stamp = readLock();
            try {
                final int index = mix & mask;
                WeakReferenceNode<T> curr = table[index];
                while (curr != null) {
                    T key = curr.get();
                    // collected nodes are skipped: a dead node earlier in the chain
                    // must not hide a live canonical instance behind it
                    if (key != null && curr.hash == hash && (key == k || strategy.equals(k, key))) {
                        return true;
                    }
                    curr = curr.next;
                }
                return false;
            } finally {
                unlockRead(stamp);
            }
        }

        /**
         * Inserts only if absent, returning whether a new canonical instance was stored.
         */
        private boolean add(final T k, final int hash, int mix) {
            long stamp = writeLock();
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
                        if (curr.hash == hash && (key == k || strategy.equals(k, key))) {
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
