package com.gto.fastcollection.cache;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.StampedLock;

/**
 * The shared core of the caches: one open-addressed slot array with lock-free
 * reads and compare-and-set writes, in the spirit of
 * {@link java.util.concurrent.ConcurrentHashMap} 閳?the array field is volatile
 * and replaced on resize, ordinary writers use compare-and-set instead of a
 * lock, and the only exclusive lock left is the one taken to resize.
 *
 * <p>Extending {@link StampedLock} makes the stamp calls direct, exactly like
 * the retired chain-based skeleton did: a writer takes the stamp in
 * <em>shared</em> mode ({@link #readLock()}), so writers keep running in
 * parallel and the compare-and-set is what arbitrates between them, while
 * {@link #grow()} and {@link #clear()} take it exclusively. Without that edge a
 * writer could compare-and-set into an array whose transfer had already passed
 * that slot but had not published yet, and the entry would be lost 閳?the same
 * problem {@link java.util.concurrent.ConcurrentHashMap} solves by blocking
 * writers behind a bin monitor for the duration of a transfer.
 *
 * <p>Reads probe with acquire semantics and stop at a {@code null} slot, so a
 * look-up never walks past a free slot; every table keeps at least one
 * never-used slot (the load factor test in {@link #shouldGrow()}) and the probes
 * are bounded by the table length, so the approximate counters can never turn a
 * look-up into a spin. Writers compare-and-set their node into the first free
 * slot of the probe sequence, using as the expected value the slot content their
 * own probe observed: a competing writer that claimed the slot first therefore
 * makes the compare-and-set fail and the probe restart, so no write is lost and
 * two writers of one key can never both insert. There are no tombstones: a
 * collected entry's slot is reused in place by the next writer, and
 * {@link #sweep()} rebuilds the table without it.
 *
 * <p>There is no {@code Table} holder and no delegation: the cache classes
 * extend this class, so the slot array is a field, the mask is derived from its
 * length, and the counters are plain fields. {@link #size} and {@link #used} are
 * approximate 閳?they are bumped outside any lock and only drive the load factor
 * 閳?so they must not be used as an exact size.
 *
 * <p>Subclasses supply how to derive a node's hash ({@link #hashOf}) and whether
 * its referent has been collected ({@link #isDead}) 閳?both used on cold paths
 * only 閳?and keep their own inlined probe and compare-and-set loops so the hot
 * paths stay monomorphic.
 *
 * <p>Reference strength is a dimension of the caches rather than of this class:
 * a weak node holds its referent through a {@link java.lang.ref.WeakReference}
 * and {@link #isDead} reports it once collected. Dead entries need no
 * bookkeeping on the side: writers reuse a collected node's slot in place, and
 * {@link #sweep()} drops them in bulk on the periodic {@link CacheCleaner}
 * tick, exactly like the pre-refactor per-segment sweep did.
 *
 * <p>This is an implementation detail of the caches in this package: it is
 * public only so the caches of the primitive subpackage can extend it.
 *
 * @param <N> the concrete node type of the subclass
 */
public abstract class OpenCacheTable<N> extends StampedLock {

    /**
     * Per-slot access with acquire/release semantics and compare-and-set; the
     * hot loops of the subclasses use it directly.
     */
    protected static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

    private static final int INITIAL_CAPACITY = 16;

    private static final int MAX_CAPACITY = 1 << 30;

    /**
     * The current slot array; replaced, never mutated in place outside a slot.
     * Its length is a power of two, so {@code mask == slots.length - 1}.
     */
    protected volatile Object[] slots;

    /**
     * Approximate entry count and ever-used slot count (entries); written without synchronization, read for the load factor.
     */
    protected int size;

    /**
     * Creates a table with the default initial capacity.
     */
    protected OpenCacheTable() {
        this.slots = new Object[INITIAL_CAPACITY];
    }

    /**
     * The mixed hash a node is stored and probed under; used by the cold paths
     * (resize, sweep, queue removal), never by a hot loop 閳?those receive the
     * caller's already-mixed hash.
     */
    protected abstract int hashOf(N node);

    /**
     * Whether the node's referent has been collected; always false for a node
     * holding strong references only.
     */
    protected abstract boolean isDead(N node);

    /**
     * Whether the next insertion should grow the table:
     * keeps a third of the slots never used, which terminates every probe.
     */
    protected final boolean shouldGrow() {
        return (size + 1) * 3 >= slots.length * 2;
    }

    /**
     * Grows the table, or rebuilds it at the same capacity when the entries are
     * sparse, and publishes the replacement via the
     * volatile array field. Takes the exclusive stamp, so no writer is in flight
     * while the live entries are copied; callers must have released the shared
     * stamp first.
     */
    protected final void grow() {
        final long stamp = writeLock();
        try {
            final Object[] old = slots;
            final int oldLength = old.length;
            final int capacity;
            if (size * 3 < oldLength) {
                capacity = oldLength;
            } else if (oldLength >= MAX_CAPACITY) {
                return;
            } else {
                capacity = oldLength << 1;
            }
            final Object[] next = new Object[capacity];
            final int mask = capacity - 1;
            int live = 0;
            for (Object raw : old) {
                if (raw == null) continue;
                @SuppressWarnings("unchecked") final N node = (N) raw;
                if (isDead(node)) continue;
                int index = hashOf(node) & mask;
                while (next[index] != null) {
                    index = (index + 1) & mask;
                }
                next[index] = node;
                live++;
            }
            size = live;
            slots = next; // volatile publication covers the fully populated replacement
        } finally {
            unlockWrite(stamp);
        }
    }

    /**
     * Empties the table by publishing a fresh array; takes the exclusive stamp,
     * so a write either lands before the swap (and is cleared) or starts after
     * it (and survives, as if it happened after the clear). Public because it
     * also satisfies {@link MapCache#clear()}.
     */
    public final void clear() {
        final long stamp = writeLock();
        try {
            final Object[] current = slots;
            // No size/used early exit: those counters are approximate (bumped
            // outside any lock, so concurrent writers can lose updates) and
            // letting them gate the clear could skip it entirely.
            slots = new Object[current.length];
            size = 0;
        } finally {
            unlockWrite(stamp);
        }
    }

    /**
     * Rebuilds the table, dropping every collected entry and every tombstone.
     * The replacement keeps the current capacity unless the live entries no
     * longer leave a third of the slots never used, in which case it doubles;
     * that guarantee is what bounds the re-insertion probes below. Called by
     * {@link CacheCleaner} through the caches' {@code clearCache()}.
     *
     * <p>Takes the exclusive stamp, so no writer is in flight while the live
     * entries are copied, and publishes the replacement through the volatile
     * array field; readers that already snapshot the old array keep probing a
     * fully populated table.
     */
    protected final void sweep() {
        final long stamp = writeLock();
        try {
            final Object[] old = slots;
            final int capacity = old.length;
            final Object[] next = new Object[capacity];
            final int mask = capacity - 1;
            int placed = 0;
            for (Object raw : old) {
                if (raw == null) continue;
                @SuppressWarnings("unchecked")
                final N node = (N) raw;
                if (isDead(node)) continue;
                int index = hashOf(node) & mask;
                while (next[index] != null) {
                    index = (index + 1) & mask;
                }
                next[index] = node;
                placed++;
            }
            size = placed;
            slots = next;
        } finally {
            unlockWrite(stamp);
        }
    }
}
