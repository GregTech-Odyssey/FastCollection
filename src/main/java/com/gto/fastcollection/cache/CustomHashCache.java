package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class CustomHashCache<K, V> implements MapCache<K, V> {

    private final Hash.Strategy<? super K> strategy;
    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    public CustomHashCache(Hash.Strategy<? super K> strategy) {
        this(strategy, Runtime.getRuntime().availableProcessors());
    }

    @SuppressWarnings("unchecked")
    public CustomHashCache(Hash.Strategy<? super K> strategy, int concurrencyLevel) {
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
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = strategy.hashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k, hash, mix, createFunction);
    }

    @Override
    public void clear() {
        for (var seg : segments) {
            seg.clear();
        }
    }


    private final static class Segment<K, V> extends ReentrantLock {
        private final Hash.Strategy<? super K> strategy;
        private volatile Node<K, V>[] table;
        private volatile int mask;
        private volatile int size;
        private volatile int maxFill;

        @SuppressWarnings("unchecked")
        private Segment(Hash.Strategy<? super K> strategy) {
            this.strategy = strategy;
            int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
            this.table = new Node[n];
            this.mask = n - 1;
            this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
        }

        private V getCache(final K k, final int hash, int mix, Function<? super K, ? extends V> createFunction) {
            lock();
            try {
                final int index = mix & this.mask;
                final Node<K, V> node = table[index];
                Node<K, V> curr = node;
                while (curr != null) {
                    if (curr.hash == hash && (k == curr.key || strategy.equals(k, curr.key))) {
                        return curr.value;
                    }
                    curr = curr.next;
                }
                final var v = createFunction.apply(k);
                table[index] = new Node<>(k, v, hash, node);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlock();
            }
        }

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
                        newTab[HashCommon.mix(e.hash) & newMask] = e;
                    } else {
                        Node<K, V> loHead = null, loTail = null;
                        Node<K, V> hiHead = null, hiTail = null;
                        Node<K, V> next;
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

    private static final class Node<K, V> {

        private final K key;
        private final V value;
        private final int hash;
        private volatile Node<K, V> next;

        private Node(K key, V value, int hash, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }
}