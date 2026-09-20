package com.gto.fastcollection.cache.primitive;

import com.gto.fastcollection.cache.CacheCleaner;
import com.gto.fastcollection.cache.ICleanableCache;
import com.gto.fastcollection.cache.OpenCacheTable;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.function.IntFunction;

/**
 * A concurrent cache from primitive {@code int} keys to values held by
 * {@link java.lang.ref.WeakReference}. Keys are mixed with
 * {@link HashCommon#mix} and compared by value; a value is dropped once
 * collected. The mixed hash is computed once per call and threaded down.
 *
 * <p>{@link #getCache(int)} / {@link #getCache(int, IntFunction)} run the create
 * function outside every lock; insertions compare-and-set on the slot value the
 * probe observed, and a lock is taken only to resize. Registered with
 * {@link CacheCleaner} for periodic dead-entry sweeping.
 *
 * @param <V> the value type
 */
public final class WeakValueIntCache<V> extends OpenCacheTable<WeakValueIntCache.Node<V>> implements ICleanableCache {

    private final IntFunction<? extends V> createFunction;


    /**
     * Creates a cache with no default create function.
     */
    public WeakValueIntCache() {
        this.createFunction = null;
        CacheCleaner.add(this);
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public WeakValueIntCache(IntFunction<? extends V> createFunction) {
        this.createFunction = createFunction;
        CacheCleaner.add(this);
    }

    /**
     * Returns the cached value for {@code k}, computing it with the default
     * create function if absent or collected. The create function must be non-null.
     */
    public V getCache(final int k) {
        return getCache(k, HashCommon.mix(k), createFunction);
    }

    /**
     * Returns the cached value for {@code k}, computing it with
     * {@code createFunction} if absent or collected. The function runs outside
     * every lock so it may recursively call back into this cache.
     */
    public V getCache(final int k, IntFunction<? extends V> createFunction) {
        return getCache(k, HashCommon.mix(k), createFunction);
    }

    /**
     * Returns the value bound to {@code k}, or {@code null} if absent or collected.
     */
    public V getIfPresent(final int k) {
        return getIfAbsent(k, HashCommon.mix(k));
    }

    /**
     * Inserts {@code v} only if {@code k} is absent or its value was collected;
     * returns the value now bound.
     */
    public V putIfAbsent(final int k, final V v) {
        return putIfAbsent(k, v, HashCommon.mix(k));
    }

    /**
     * {@inheritDoc} Drops every entry whose value has been collected.
     */
    @Override
    public void clearCache() {
        sweep();
    }

    /**
     * The table: an open-addressed slot array of weakly referenced values keyed
     * by a primitive; a collected node's slot is reusable by writers.
     */

    @Override
    protected int hashOf(Node<V> node) {
        return HashCommon.mix(node.key);
    }

    @Override
    protected boolean isDead(Node<V> node) {
        return node.get() == null;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final int k, final int mix) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) return null;
            final Node<V> node = (Node<V>) raw;
            if (node.key == k) return node.get();
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Probes without locking, runs the function outside every lock, then
     * inserts with compare-and-set; a function returning {@code null} stores
     * nothing.
     */
    private V getCache(final int k, final int mix, IntFunction<? extends V> createFunction) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) break;
            @SuppressWarnings("unchecked") final Node<V> node = (Node<V>) raw;
            if (node.key == k) {
                final V existing = node.get();
                if (existing != null) return existing;
                break;
            }
        }
        // Run outside every lock so the function may call back into this cache.
        final V value = createFunction.apply(k);
        if (value == null) return null;
        return putIfAbsent(k, value, mix);
    }

    /**
     * Inserts unless the key is bound to a live value; returns the value now
     * bound. The compare-and-set uses the slot value the probe observed 闂?         * which may be a collected node, whose slot is therefore reusable 闂?so a
     * competing writer makes it fail and the probe restart.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final int k, final V v, final int mix) {
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
                    if (node.key == k) {
                        final V existing = node.get();
                        if (existing != null) return existing;
                        // a collected entry is garbage: its slot is reusable
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = node;
                        }
                    }
                }
                if (vacant < 0) {
                    // no free slot anywhere: the only case that grows before inserting
                    resize = true;
                } else {
                    if (SLOT.compareAndSet(tab, vacant, vacantValue, new Node<>(k, v))) {
                        // replacing a collected node keeps the entry count: it was still counted
                        if (vacantValue == null) size++;
                        Reference.reachabilityFence(v);
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
     * A single slot entry holding the value weakly and the primitive key strongly.
     */
    static final class Node<V> extends WeakReference<V> {

        private final int key;

        private Node(int key, V value) {
            super(value);
            this.key = key;
        }
    }
}
