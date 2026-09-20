package com.gto.fastcollection.cache.primitive;

import com.gto.fastcollection.cache.OpenCacheTable;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.function.DoubleFunction;

/**
 * A concurrent cache from primitive {@code double} keys to strong object values.
 * Keys are mixed with {@link HashCommon#mix} and compared by value; the mixed
 * hash is computed once per call and threaded down to the probe.
 *
 * <p>{@link #getCache(int)} / {@link #getCache(int, DoubleFunction)} run the
 * create function outside every lock so it may call back into this cache, then
 * insert with compare-and-set; a lock is taken only to resize. A function
 * returning {@code null} stores nothing.
 *
 * @param <V> the value type
 */
public final class DoubleCache<V> extends OpenCacheTable<DoubleCache.Node<V>> {

    private final DoubleFunction<? extends V> createFunction;


    /**
     * Creates a cache with no default create function.
     */
    public DoubleCache() {
        this.createFunction = null;
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public DoubleCache(DoubleFunction<? extends V> createFunction) {
        this.createFunction = createFunction;
    }

    /**
     * Returns the cached value for {@code k}, computing it with the default
     * create function if absent. The create function must be non-null.
     */
    public V getCache(final double k) {
        return getCache(k, HashCommon.mix(Long.hashCode(Double.doubleToLongBits(k))), createFunction);
    }

    /**
     * Returns the cached value for {@code k}, computing it with
     * {@code createFunction} if absent. The function runs outside every lock.
     */
    public V getCache(final double k, DoubleFunction<? extends V> createFunction) {
        return getCache(k, HashCommon.mix(Long.hashCode(Double.doubleToLongBits(k))), createFunction);
    }

    /**
     * Returns the value bound to {@code k}, or {@code null} if absent.
     */
    public V getIfPresent(final double k) {
        return getIfAbsent(k, HashCommon.mix(Long.hashCode(Double.doubleToLongBits(k))));
    }

    /**
     * Inserts {@code v} only if {@code k} is absent; returns the value now bound.
     */
    public V putIfAbsent(final double k, final V v) {
        return putIfAbsent(k, v, HashCommon.mix(Long.hashCode(Double.doubleToLongBits(k))));
    }

    /**
     * The table: an open-addressed slot array of {@link Node}s, primitive keys
     * compared by value, nodes storing no hash so a resize re-derives it.
     */

    @Override
    protected int hashOf(Node<V> node) {
        return HashCommon.mix(Long.hashCode(Double.doubleToLongBits(node.key)));
    }

    @Override
    protected boolean isDead(Node<V> node) {
        return false;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final double k, final int mix) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) return null;
            final Node<V> node = (Node<V>) raw;
            if (Double.doubleToLongBits(node.key) == Double.doubleToLongBits(k)) return node.value;
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Probes without locking, runs the function outside every lock, then
     * inserts with compare-and-set; a function returning {@code null} stores
     * nothing.
     */
    private V getCache(final double k, final int mix, DoubleFunction<? extends V> createFunction) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) break;
            @SuppressWarnings("unchecked") final Node<V> node = (Node<V>) raw;
            if (Double.doubleToLongBits(node.key) == Double.doubleToLongBits(k)) return node.value;
        }
        // Run outside every lock so the function may call back into this cache.
        final V value = createFunction.apply(k);
        if (value == null) return null;
        return putIfAbsent(k, value, mix);
    }

    /**
     * Inserts unless the key is already bound; returns the value now bound.
     * The compare-and-set uses the slot value the probe observed, so a
     * competing writer makes it fail and the probe restart.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final double k, final V v, final int mix) {
        if (v == null) return null;
        for (; ; ) {
            final long stamp = readLock();
            boolean resize;
            boolean done = false;
            try {
                final Object[] tab = slots;
                final int mask = tab.length - 1;
                int vacant = -1;
                Object vacantValue = null;
                int index = mix & mask;
                for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
                    final Object raw = SLOT.getAcquire(tab, index);
                    if (raw == null) {
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = null;
                        }
                        break;
                    }
                    final Node<V> node = (Node<V>) raw;
                    if (Double.doubleToLongBits(node.key) == Double.doubleToLongBits(k)) return node.value;
                }
                if (vacant < 0) {
                    // no free slot anywhere: the only case that grows before inserting
                    resize = true;
                } else {
                    if (SLOT.compareAndSet(tab, vacant, vacantValue, new Node<>(k, v))) {
                        size++;
                        done = true;
                        resize = shouldGrow();
                    } else {
                        continue; // lost the slot to another writer: retry under a fresh stamp
                    }
                }
            } finally {
                unlockRead(stamp);
            }
            if (resize) grow();
            if (done) return v;
        }
    }

    /**
     * A single slot entry; immu
     */
    static final class Node<V> {

        private final double key;
        private final V value;

        private Node(double key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
