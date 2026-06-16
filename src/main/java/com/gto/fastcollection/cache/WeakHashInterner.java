package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class WeakHashInterner<T> implements Interner<T>, ICleanableCache {

    private final Segment<T>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    public WeakHashInterner() {
        this(Runtime.getRuntime().availableProcessors());
    }

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

    @Override
    public T intern(final T sample) {
        int hash = sample.hashCode();
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

    @Override
    public void clearCache() {
        for (var seg : segments) {
            seg.clearInvalid();
        }
    }

    private final static class Segment<T> extends ReentrantLock {
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

        private T intern(final T k, final int hash, int mix) {
            lock();
            try {
                final int index = mix & this.mask;
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
                unlock();
            }
        }


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

        private void clearInvalid() {
            lock();
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
                unlock();
            }
        }
    }
}
