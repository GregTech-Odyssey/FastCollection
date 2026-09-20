package com.gto.fastcollection.cache;

import com.gto.fastcollection.Concurrents;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;
import java.util.function.UnaryOperator;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * An {@link Interner} with the same segmented, {@link StampedLock}-guarded
 * design as {@link CustomHashCache}, using a custom {@link Hash.Strategy} for
 * hashing and equality. Use it when "equal" must be decided by application
 * logic (e.g. comparing only a subset of fields) rather than by {@code equals}.
 */
public final class CustomHashInterner<T> extends Segmented<CustomHashInterner.Segment<T>> implements Interner<T> {

    private final Hash.Strategy<? super T> strategy;

    /**
     * Creates an interner with default concurrency.
     */
    public CustomHashInterner(Hash.Strategy<? super T> strategy) {
        this(Concurrents.NCPU, strategy);
    }

    /**
     * Creates an interner with the given concurrency level.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    public CustomHashInterner(int concurrencyLevel, Hash.Strategy<? super T> strategy) {
        super(concurrencyLevel, i -> new Segment<>(strategy));
        this.strategy = strategy;
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
     * A striped segment holding canonical instances; see {@link CustomHashCache.Segment}.
     */
    final static class Segment<T> extends HashSegment<Node<T>> {
        private final Hash.Strategy<? super T> strategy;

        private Segment(Hash.Strategy<? super T> strategy) {
            this.strategy = strategy;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Node<T>[] newArray(int capacity) {
            return new Node[capacity];
        }

        @Override
        protected int nodeHash(Node<T> node) {
            return node.hash;
        }

        @Override
        protected boolean isDead(Node<T> node) {
            return false;
        }

        /**
         * Lookup under the read lock; on a miss the canonical instance is stored under the write lock.
         */
        private T intern(final T k, final int hash, int mix, UnaryOperator<T> mappingFunction) {
            long stamp = readLock();
            try {
                Node<T> curr = table[mix & mask];
                while (curr != null) {
                    var key = curr.key;
                    if (curr.hash == hash && (key == k || strategy.equals(k, key))) {
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
                final Node<T> node = table[index];
                Node<T> curr = node;
                while (curr != null) {
                    var key = curr.key;
                    if (curr.hash == hash && (key == v || strategy.equals(v, key))) {
                        return key;
                    }
                    curr = curr.next;
                }
                table[index] = new Node<>(v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlockWrite(stamp);
            }
        }

        /**
         * Read-only membership test; never inserts anything.
         */
        private boolean contains(final T k, final int hash, int mix) {
            long stamp = readLock();
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

        /**
         * Inserts only if absent, returning whether a new canonical instance was stored.
         */
        private boolean add(final T k, final int hash, int mix) {
            long stamp = writeLock();
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
    }

    /**
     * A single entry in a chain; immutable except for {@code next}.
     */
    static final class Node<T> implements ChainNode<Node<T>> {

        private final T key;
        private final int hash;
        private volatile Node<T> next;

        private Node(T key, int hash, Node<T> next) {
            this.key = key;
            this.hash = hash;
            this.next = next;
        }

        @Override
        public Node<T> getNext() {
            return next;
        }

        @Override
        public void setNext(Node<T> next) {
            this.next = next;
        }
    }
}
