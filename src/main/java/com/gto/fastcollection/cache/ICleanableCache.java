package com.gto.fastcollection.cache;

/**
 * A cache whose entries may reference garbage-collectable objects (weak values or
 * weak keys). {@link CacheCleaner} periodically invokes {@link #clearCache()} on
 * registered caches so that entries whose referents have been collected are
 * dropped instead of accumulating as dead nodes.
 */
public interface ICleanableCache {

    /**
     * Removes all entries whose referents have already been garbage collected.
     */
    void clearCache();
}
