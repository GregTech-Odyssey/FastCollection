package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.Reference;
import java.util.function.UnaryOperator;

/**
 * An {@link Interner} combining weak canonical instances (see
 * {@link WeakHashInterner}) with a custom {@link Hash.Strategy} for hashing and
 * equality (see {@link CustomHashInterner}). Use it when canonical instances
 * should be dropped once unused, and "equal" is decided by application logic.
 *
 * <p>Registered with {@link CacheCleaner} for periodic dead-entry sweeping.
 * Reads are lock-free; insertions compare-and-set on the slot value the probe
 * observed, and a lock is taken only to resize.
 */
public final class WeakCustomHashInterner<T> extends OpenCacheTable<WeakReferenceNode<T>> implements Interner<T>, ICleanableCache {

    private final Hash.Strategy<? super T> strategy;

    /**
     * Creates an interner with the given strategy; registers it with
     * {@link CacheCleaner}.
     */
    public WeakCustomHashInterner(Hash.Strategy<? super T> strategy) {
        this.strategy = strategy;
        CacheCleaner.add(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T intern(final T sample) {
        final int hash = strategy.hashCode(sample);
        final int mix = HashCommon.mix(hash);
        return this.intern(sample, hash, mix, Interner.identityMappingFunction());
    }

    @Override
    public T intern(T sample, UnaryOperator<T> mappingFunction) {
        final int hash = strategy.hashCode(sample);
        final int mix = HashCommon.mix(hash);
        return this.intern(sample, hash, mix, mappingFunction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPresent(final T sample) {
        final int hash = strategy.hashCode(sample);
        final int mix = HashCommon.mix(hash);
        return this.lookup(sample, hash, mix) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addIfAbsent(final T sample) {
        final int hash = strategy.hashCode(sample);
        final int mix = HashCommon.mix(hash);
        return this.insertIfAbsent(sample, hash, mix) == null;
    }

    /**
     * {@inheritDoc} Drops every entry whose instance has been collected.
     */
    @Override
    public void clearCache() {
        sweep();
    }

    /**
     * The table: an open-addressed slot array of {@link WeakReferenceNode}s
     * (weak referent, stored hash, strategy-compared).
     */
    @Override
    protected int hashOf(WeakReferenceNode<T> node) {
        return HashCommon.mix(node.hash);
    }

    @Override
    protected boolean isDead(WeakReferenceNode<T> node) {
        return node.get() == null;
    }

    /**
     * Read-only probe; returns the live canonical instance or {@code null}.
     */
    @SuppressWarnings("unchecked")
    private T lookup(final T k, final int hash, final int mix) {
        final Object[] tab = slots;
        final int mask = tab.length - 1;
        int index = mix & mask;
        for (int steps = 0; steps <= mask; steps++, index = (index + 1) & mask) {
            final Object raw = SLOT.getAcquire(tab, index);
            if (raw == null) return null;
            final WeakReferenceNode<T> node = (WeakReferenceNode<T>) raw;
            final T key = node.get();
            // collected nodes are skipped: a dead node earlier in the probe
            // must not hide a live canonical instance behind it
            if (key != null && node.hash == hash && (key == k || strategy.equals(k, key))) return key;
        }
        return null; // probe walked the whole table: treat as absent
    }

    /**
     * Returns the canonical instance equal to {@code k}, inserting the mapped
     * instance when none is interned yet; a mapping function returning
     * {@code null} stores nothing and the call returns {@code null}.
     */
    private T intern(final T k, final int hash, final int mix, UnaryOperator<T> mappingFunction) {
        final T existing = this.lookup(k, hash, mix);
        if (existing != null) return existing;
        final T v = mappingFunction.apply(k);
        if (v == null) return null;
        // The mapping function must preserve the sample's hash and equality (it is a
        // canonicalisation such as a copy), so the miss above already rules out an equal
        // instance and v can be probed and stored under the sample's hash: no second
        // lookup is needed.
        final T previous = this.insertIfAbsent(v, hash, mix);
        return previous != null ? previous : v;
    }

    /**
     * Inserts {@code k} unless an equal canonical instance is already bound,
     * returning the instance already interned, or {@code null} when this call stored {@code k}. Holds the shared stamp, so writers
     * stay parallel, and compare-and-sets into the first free slot of the probe
     * sequence using the value the probe observed there, which may be a
     * collected node whose slot is therefore reusable, so a competing writer
     * makes the compare-and-set fail and the probe restart.
     */
    @SuppressWarnings("unchecked")
    private T insertIfAbsent(final T k, final int hash, final int mix) {
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
                    final WeakReferenceNode<T> node = (WeakReferenceNode<T>) raw;
                    final T key = node.get();
                    if (key == null) {
                        // a collected entry is garbage: its slot is reusable
                        if (vacant < 0) {
                            vacant = index;
                            vacantValue = node;
                        }
                        continue;
                    }
                    if (node.hash == hash && (key == k || strategy.equals(k, key))) return key;
                }
                if (vacant < 0) {
                    // no free slot anywhere: the only case that grows before inserting
                    resize = true;
                } else {
                    if (SLOT.compareAndSet(tab, vacant, vacantValue, new WeakReferenceNode<>(k, hash))) {
                        // replacing a collected node keeps the entry count: it was still counted
                        if (vacantValue == null) size++;
                        Reference.reachabilityFence(k);
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
            if (done) return null;
        }
    }
}
