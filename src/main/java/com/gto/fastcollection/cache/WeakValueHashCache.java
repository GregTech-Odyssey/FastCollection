package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.Reference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} whose values are held weakly and whose keys are compared
 * by {@code equals}/{@code hashCode}: the weak-value counterpart of
 * {@link IdentityHashCache}.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 * Concurrency mirrors {@link IdentityHashCache}: one open-addressed table,
 * lock-free reads and lock-free compare-and-set writes, a lock only to resize;
 * every insertion compare-and-sets on the slot value its probe observed, so a
 */
public final class WeakValueHashCache<K, V> extends OpenCacheTable<WeakReferenceValueNode<K, V>> implements MapCache<K, V>, ICleanableCache {

    private final Function<? super K, ? extends V> createFunction;

    /**
     * Creates a cache with no default create function.
     */
    public WeakValueHashCache() {
        this.createFunction = null;
        CacheCleaner.add(this);
    }

    /**
     * Creates a cache with the given default create function; {@code null} is
     * allowed and behaves like the no-factory constructor.
     */
    public WeakValueHashCache(Function<? super K, ? extends V> createFunction) {
        this.createFunction = createFunction;
        CacheCleaner.add(this);
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return createFunction;
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        return getCache(k, k.hashCode(), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCache(K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return getCache(k, k.hashCode(), createFunction, keyMappingFunction);
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        return getCache(k, k.hashCode(), createFunction, Interner.identityMappingFunction());
    }

    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        return getCache(k, k.hashCode(), createFunction, keyMappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        return getIfAbsent(k, k.hashCode());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        return putIfAbsent(k, v, k.hashCode());
    }

    /**
     * {@inheritDoc} Drops every entry whose value has been collected.
     */
    @Override
    public void clearCache() {
        sweep();
    }

    /**
     * The table: an open-addressed slot array of {@link WeakReferenceValueNode}s
     * (weak value, strong key) whose nodes carry the key's
     * hash.
     */

    @Override
    protected int hashOf(WeakReferenceValueNode<K, V> node) {
        return HashCommon.mix(node.hash);
    }

    @Override
    protected boolean isDead(WeakReferenceValueNode<K, V> node) {
        return node.get() == null;
    }

    /**
     * Read-only lookup; never stores anything.
     */
    @SuppressWarnings("unchecked")
    private V getIfAbsent(final K k, final int hash) {
        final int mix = HashCommon.mix(hash);
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) return null;
            final WeakReferenceValueNode<K, V> node = (WeakReferenceValueNode<K, V>) raw;
            if (node.hash == hash && (k == node.key || k.equals(node.key))) return node.get();
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Recursive variant: probes without locking, runs the function with
     * every lock released so it may call back into this cache, then inserts
     * with compare-and-set, keeping the value computed by another thread if
     * one landed first. A function returning {@code null} stores nothing.
     */
    private V getCache(final K k, final int hash,
                       Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        final int mix = HashCommon.mix(hash);
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) break;
            @SuppressWarnings("unchecked") final WeakReferenceValueNode<K, V> node = (WeakReferenceValueNode<K, V>) raw;
            if (node.hash == hash && (k == node.key || k.equals(node.key))) {
                final V existing = node.get();
                if (existing != null) return existing;
                break;
            }
        }
        // Run outside every lock so the function may call back into this cache.
        final K mapped = keyMappingFunction.apply(k);
        final V value = createFunction.apply(mapped);
        if (value == null) return null;
        return putIfAbsent(mapped, value, hash);
    }

    /**
     * Inserts unless the key is bound to a live value; returns the value now
     * bound. Lock-free: the node is compare-and-set into the first free slot
     * of the probe sequence, and any conflict (a slot taken meanwhile, or an
     * array replaced by a resize) restarts the probe on the current array.
     */
    @SuppressWarnings("unchecked")
    private V putIfAbsent(final K k, final V v, final int hash) {
        if (v == null) return null;
        final int mix = HashCommon.mix(hash);
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
                    final WeakReferenceValueNode<K, V> node = (WeakReferenceValueNode<K, V>) raw;
                    if (node.hash == hash && (k == node.key || k.equals(node.key))) {
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
                    if (SLOT.compareAndSet(tab, vacant, vacantValue, new WeakReferenceValueNode<>(k, v, hash))) {
                        if (vacantValue == null) {
                            size++;
                        }
                        Reference.reachabilityFence(k);
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
}
