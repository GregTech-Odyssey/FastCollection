package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CacheCleaner {

    private CacheCleaner() {
    }

    private static final ConcurrentLinkedDeque<WeakReference<ICleanableCache>> CACHES = new ConcurrentLinkedDeque<>();

    static {
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

    public static void add(ICleanableCache cache) {
        CACHES.add(new WeakReference<>(cache));
    }
}
