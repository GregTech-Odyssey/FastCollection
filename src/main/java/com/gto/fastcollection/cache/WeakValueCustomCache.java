package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class WeakValueCustomCache<K, V> implements ICache {

    private final Hash.Strategy<? super K> strategy;
    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    public WeakValueCustomCache(Hash.Strategy<? super K> strategy) {
        this(strategy, Runtime.getRuntime().availableProcessors());
    }

    @SuppressWarnings("unchecked")
    public WeakValueCustomCache(Hash.Strategy<? super K> strategy, int concurrencyLevel) {
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
        this.strategy = strategy;
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
        CacheCleaner.add(this);
    }

    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[ (mix >>> segmentShift) & segmentMask].getCache(k, hash, mix, createFunction);
    }

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

    private final static class Segment<K, V> extends ReentrantLock {
        private final Hash.Strategy<? super K> strategy;
        private volatile WeakReferenceValueNode<K, V>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment(Hash.Strategy<? super K> strategy) {
            this.strategy = strategy;
            int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
            this.table = new WeakReferenceValueNode[n];
            this.mask = n - 1;
            this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
        }

        private V getCache(final K k, final int hash, int mix, Function<? super K, ? extends V> createFunction) {
            lock();
            try {
                final int index = mix & this.mask;
                final WeakReferenceValueNode<K, V> node = table[index];
                WeakReferenceValueNode<K, V> prev = null;
                WeakReferenceValueNode<K, V> e = node;
                while (e != null) {
                    if (e.hash == hash && strategy.equals(k, e.key)) {
                        V v = e.get();
                        if (v != null) return v;
                        return insert(index, hash, k, prev, e.next, createFunction);
                    }
                    prev = e;
                    e = e.next;
                }
                V v = insert(index, hash, k, null, node, createFunction);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlock();
            }
        }

        private V insert(final int index, final int hash, final K k, WeakReferenceValueNode<K, V> prev,
                         WeakReferenceValueNode<K, V> next, Function<? super K, ? extends V> createFunction) {
            final var v = createFunction.apply(k);
            var n = new WeakReferenceValueNode<>(k, v, hash, next);
            if (prev == null) {
                table[index] = n;
            } else {
                prev.next = n;
            }
            return v;
        }

        @SuppressWarnings("unchecked")
        private void resize() {
            WeakReferenceValueNode<K, V>[] oldTab = table;
            int oldCap = oldTab.length;
            int newCap = oldCap << 1;
            WeakReferenceValueNode<K, V>[] newTab = new WeakReferenceValueNode[newCap];
            int newMask = newCap - 1;
            for (int i = 0; i < oldCap; ++i) {
                WeakReferenceValueNode<K, V> e;
                if ((e = oldTab[i]) != null) {
                    oldTab[i] = null;
                    if (e.next == null) {
                        newTab[HashCommon.mix(e.hash) & newMask] = e;
                    } else {
                        WeakReferenceValueNode<K, V> loHead = null, loTail = null;
                        WeakReferenceValueNode<K, V> hiHead = null, hiTail = null;
                        WeakReferenceValueNode<K, V> next;
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
                    WeakReferenceValueNode<K, V> prev = null;
                    WeakReferenceValueNode<K, V> curr = table[i];
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