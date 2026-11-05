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
public abstract class WeakSegmented<SEG extends WeakHashSegment<?>> extends Segmented<SEG> {


    /**
     * @throws IllegalArgumentException if {@code concurrencyLevel} is not positive
     */
    protected WeakSegmented(int concurrencyLevel, IntFunction<? extends SEG> segmentFactory) {
        super(concurrencyLevel, segmentFactory);
    }

    /**
     * Reclaims every segment's collected entries, one segment write lock at a
     * time. This is what {@code clearCache()} exposes to callers; the background
     * {@link CacheDrainer} uses the incremental {@link #drainCollected()} instead.
     */
    protected final void expungeStaleEntries() {
        HashSegment[] segments = this.segments;
        for (var segment : segments) {
            ((WeakHashSegment<?>) segment).expungeAll();
        }
    }

    /**
     * One incremental reclamation pass for the background drainer: each segment
     * unlinks a bounded batch of collected nodes, and an idle segment costs a
     * queue peek without locking.
     */
    final void drainCollected() {
        HashSegment[] segments = this.segments;
        for (var segment : segments) {
            ((WeakHashSegment<?>) segment).expunge();
        }
    }
}
