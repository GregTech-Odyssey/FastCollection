package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

public final class WeakValueIdentityCache<K, V> implements ICache {

    private final Segment<K, V>[] segments;
    private final int segmentShift;
    private final int segmentMask;

    public WeakValueIdentityCache() {
        this(Runtime.getRuntime().availableProcessors());
    }

    @SuppressWarnings("unchecked")
    public WeakValueIdentityCache(int concurrencyLevel) {
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

    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        int hash = System.identityHashCode(k);
        int mix = hash * -1640531527;
        mix ^= mix >>> 16;
        return segments[(mix >>> segmentShift) & segmentMask].getCache(k,  mix, createFunction);
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

        private V getCache(final K k, final int mix, Function<? super K, ? extends V> createFunction) {
            lock();
            try {
                final int index = mix & this.mask;
                final Node<K, V> node = table[index];
                Node<K, V> prev = null;
                Node<K, V> e = node;
                while (e != null) {
                    if (e.key == k) {
                        V v = e.get();
                        if (v != null) return v;
                        return insert(index, k, prev, e.next, createFunction);
                    }
                    prev = e;
                    e = e.next;
                }
                V v = insert(index, k, null, node, createFunction);
                if (++size > maxFill) {
                    resize();
                }
                return v;
            } finally {
                unlock();
            }
        }

        private V insert(final int index,  final K k, Node<K, V> prev,
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
                unlock();
            }
        }
    }

  private  static final class Node<K, V> extends WeakReference<V> {

      private final K key;
      private  volatile Node<K, V> next;

      private    Node(K key, V value, Node<K, V> next) {
            super(value);
            this.key = key;
            this.next = next;
        }
    }
}