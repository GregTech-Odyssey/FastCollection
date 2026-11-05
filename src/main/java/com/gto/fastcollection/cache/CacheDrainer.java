package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Background reclaimer for the weak caches: a single daemon thread that walks
 * the registered caches and asks each one to unlink the nodes whose referent the
 * GC has collected, so no write path has to pay for reclamation.
 *
 * <p>Unlike the table-scanning cleaner this replaces, a pass is <em>free</em>
 * when nothing was collected: the segment peeks its {@link java.lang.ref.ReferenceQueue}
 * without taking any lock and returns, and only a segment that really has dead
 * nodes acquires its write lock (for a bounded batch, see
 * {@link WeakHashSegment#BACKGROUND_BATCH}). The cost of a pass is therefore
 * {@code O(collected)} where the old one was {@code O(table capacity)} under an
 * exclusive lock.
 *
 * <p>Entries are held as {@link WeakReference}s, so a cache that becomes
 * unreachable simply disappears from the registry instead of leaking; stale
 * references are pruned lazily during each pass.
 */
public final class CacheDrainer {

    private CacheDrainer() {
    }

    /**
     * Pass interval. A collected entry stays counted for at most this long
     * (plus one pass), which is invisible for caches and keeps the idle cost at
     * "one queue peek per segment per interval".
     */
    private static final long INTERVAL_MS = 500L;

    private static final ConcurrentLinkedDeque<WeakReference<WeakSegmented<?>>> CACHES = new ConcurrentLinkedDeque<>();

    static {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                sweep();
            }
        }, "FastCollection Cache Drainer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Registers a cache for background reclamation. Safe from any thread;
     * duplicate registrations are allowed (draining is idempotent).
     */
    public static void add(WeakSegmented<?> cache) {
        CACHES.offerLast(new WeakReference<>(cache));
    }

    private static void sweep() {
        var it = CACHES.iterator();
        while (it.hasNext()) {
            WeakSegmented<?> cache = it.next().get();
            if (cache == null) {
                it.remove();
            } else {
                cache.drainCollected();
            }
        }
    }
}
