package com.gto.fastcollection.cache;

/**
 * A cache whose entries may reference garbage-collectable objects (weak values or
 * weak keys).
 *
 * <p>Entries whose referent has been collected are reclaimed through a
 * {@link java.lang.ref.ReferenceQueue}: every weak node registers with its
 * segment's queue, and the shared {@link CacheDrainer} daemon thread unlinks the
 * collected nodes — {@code O(collected)}, never a table scan, and never on the
 * caller's thread, so writes pay nothing for reclamation.
 *
 * <p>{@link #clearCache()} drains every segment synchronously, for callers that
 * need the reclamation to have happened by the time the call returns (the
 * background drainer otherwise gets to it within a few milliseconds).
 */
public interface ICleanableCache {

    /**
     * Reclaims every entry whose referent has already been garbage collected.
     * Idempotent and safe to call at any time; {@code O(collected)}, never a
     * full table scan.
     */
    void clearCache();
}
