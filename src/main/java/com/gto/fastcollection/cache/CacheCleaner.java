package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Global registry for {@link ICleanableCache} instances. A single daemon thread
 * started on class load periodically walks the registry and calls
 * {@link ICleanableCache#clearCache()} on every live cache, so caches with weak
 * referents do not need their own cleaner threads.
 *
 * <p>Entries are kept as {@link WeakReference}s so that a cache which becomes
 * unreachable is automatically removed from the registry (its reference turns
 * {@code null}) instead of leaking. The registry is an append-only
 * {@link ConcurrentLinkedDeque}: {@code add} is lock-free, and stale references
 * are only pruned lazily during each cleanup pass.
 */
public final class CacheCleaner {

    private CacheCleaner() {
    }

    private static final ConcurrentLinkedDeque<WeakReference<ICleanableCache>> CACHES = new ConcurrentLinkedDeque<>();

    static {
        // Runs every 10s; clears dead references and sweeps collected entries.
        Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r);
            thread.setName("Cache Cleaner");
            thread.setDaemon(true);
            thread.setPriority(1);
            return thread;
        }).scheduleAtFixedRate(() -> {
            var it = CACHES.iterator();
            while (it.hasNext()) {
                var cache = it.next().get();
                if (cache == null) {
                    it.remove();
                } else {
                    cache.clearCache();
                }
            }
        }, 1, 10, TimeUnit.SECONDS);
    }

    /**
     * Registers {@code cache} for periodic cleanup. Safe to call from any thread;
     * duplicate registrations are allowed (cleanup is idempotent).
     */
    public static void add(ICleanableCache cache) {
        CACHES.offerLast(new WeakReference<>(cache));
    }
}
