package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class CustomHashInterner<T> implements Interner<T> {

    private final Hash.Strategy<? super T> strategy;
    private final Segment<T>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    public CustomHashInterner(Hash.Strategy<? super T> strategy) {
        this(Runtime.getRuntime().availableProcessors(), strategy);
    }

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

    @Override
    public T intern(final T sample) {
        int hash = strategy.hashCode(sample);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].intern(sample, hash, mix);
    }

    @Override
    public void clear() {
        for (var seg : segments) {
            seg.clear();
        }
    }

    private final static class Segment<T> extends ReentrantLock {
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

        private T intern(final T k, final int hash, int mix) {
            lock();
            try {
                final int index = mix & this.mask;
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
                unlock();
            }
        }


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

        private void clear() {
            lock();
            try {
                if (size == 0) return;
                size = 0;
                Arrays.fill(table, null);
            } finally {
                unlock();
            }
        }
    }

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
