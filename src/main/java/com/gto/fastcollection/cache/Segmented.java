package com.gto.fastcollection.cache;

import java.util.function.IntFunction;

/**
 * Shared segment-array routing for the striped structures: the segment count
 * is a power of two rounded up from the requested concurrency level, a key is
 * routed by the high bits of its mixed hash (the low bits pick the bucket),
 * and distinct segments proceed fully in parallel.
 *
 * @param <SEG> the concrete segment type
 */
public abstract class Segmented<SEG extends HashSegment<?>> {

    private final SEG[] segments;
    private final int segmentShift;
    private final int segmentMask;

    /**
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    @SuppressWarnings("unchecked")
    protected Segmented(int concurrencyLevel, IntFunction<? extends SEG> segmentFactory) {
        if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be positive");
        int ssize = 1;
        while (ssize < concurrencyLevel) {
            ssize <<= 1;
        }
        this.segmentShift = 32 - Integer.numberOfTrailingZeros(ssize);
        this.segmentMask = ssize - 1;
        this.segments = (SEG[]) new HashSegment[ssize];
        for (int i = 0; i < ssize; i++) {
            segments[i] = segmentFactory.apply(i);
        }
    }

    /**
     * The segment responsible for a key whose mixed hash is {@code mix}.
     */
    protected final SEG segmentFor(int mix) {
        return segments[(mix >>> segmentShift) & segmentMask];
    }

    /**
     * Clears every segment.
     */
    protected final void clearSegments() {
        for (var segment : segments) {
            segment.clear();
        }
    }

    /**
     * Reclaims every segment's collected entries, one segment write lock at a
     * time. This is what {@code clearCache()} exposes to callers; the background
     * {@link CacheDrainer} uses the incremental {@link #drainCollected()} instead.
     */
    protected final void expungeStaleEntries() {
        for (var segment : segments) {
            segment.expungeAll();
        }
    }

    /**
     * One incremental reclamation pass for the background drainer: each segment
     * unlinks a bounded batch of collected nodes, and an idle segment costs a
     * queue peek without locking.
     */
    final void drainCollected() {
        for (var segment : segments) {
            segment.expunge();
        }
    }

    /**
     * The number of entries currently held. Collected entries that have not
     * been reclaimed yet are still counted, so the result can transiently
     * over-report — the same contract as {@link java.util.WeakHashMap#size()}.
     * The read is lock-free and therefore an approximation under concurrency.
     */
    public int size() {
        int total = 0;
        for (var segment : segments) {
            total += segment.size;
        }
        return total;
    }
}
