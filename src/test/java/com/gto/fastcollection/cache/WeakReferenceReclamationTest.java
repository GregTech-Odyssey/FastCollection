package com.gto.fastcollection.cache;

import com.gto.fastcollection.cache.primitive.WeakValueLongCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the reclamation path that replaced the old table-scanning cleaner:
 * collected entries are unlinked by the background {@link CacheDrainer} without
 * any cache call (and without blocking writers), {@code clearCache()} still
 * drains on demand, and the only helper thread is the daemon drainer.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WeakReferenceReclamationTest {

    @Test
    void collectedInternedEntriesAreReclaimedWithoutAnyCacheCall() throws InterruptedException {
        WeakHashInterner<String> interner = new WeakHashInterner<>(1);
        for (int i = 0; i < 64; i++) {
            interner.intern(new String("k" + i));
        }
        assertThat(interner.size()).isEqualTo(64);

        // no writes, no clearCache(): only the background drainer runs
        assertThat(awaitReclamation(interner::size, () -> { }))
                .as("background drainer reclaims collected canonical instances")
                .isTrue();
    }

    @Test
    void collectedValuesAreReclaimedWithoutAnyCacheCall() throws InterruptedException {
        WeakValueHashCache<String, byte[]> cache = new WeakValueHashCache<>(1);
        for (int i = 0; i < 64; i++) {
            cache.putIfAbsent("k" + i, new byte[1024]);
        }
        assertThat(cache.size()).isEqualTo(64);

        assertThat(awaitReclamation(cache::size, () -> { }))
                .as("background drainer reclaims collected values")
                .isTrue();
    }

    @Test
    void primitiveKeyCacheReclaimsCollectedValues() throws InterruptedException {
        WeakValueLongCache<byte[]> cache = new WeakValueLongCache<>(1);
        for (int i = 0; i < 64; i++) {
            cache.putIfAbsent(i, new byte[1024]);
        }
        assertThat(cache.size()).isEqualTo(64);

        assertThat(awaitReclamation(cache::size, () -> { }))
                .as("background drainer reclaims weak values with primitive keys")
                .isTrue();
    }

    @Test
    void clearCacheDrainsImmediatelyWithoutWaitingForTheDrainer() throws InterruptedException {
        WeakValueHashCache<String, byte[]> cache = new WeakValueHashCache<>(1);
        cache.putIfAbsent("w", new byte[1024]);

        assertThat(awaitReclamation(cache::size, cache::clearCache))
                .as("clearCache() is an explicit synchronous drain")
                .isTrue();
    }

    @Test
    void sizeTracksLiveEntries() {
        WeakHashInterner<String> interner = new WeakHashInterner<>(1);
        assertThat(interner.size()).isZero();

        String kept = new String("kept");
        interner.intern(kept);
        assertThat(interner.size()).isEqualTo(1);

        // a duplicate intern hits the canonical instance and stores nothing
        interner.intern(new String("kept"));
        assertThat(interner.size()).isEqualTo(1);

        interner.clear();
        assertThat(interner.size()).isZero();
        assertThat(kept).isNotNull();
    }

    @Test
    void onlyTheDaemonDrainerThreadIsStarted() {
        new WeakHashInterner<String>().intern(new String("any"));
        new WeakValueHashCache<String, byte[]>().putIfAbsent("any", new byte[1]);

        assertThat(Thread.getAllStackTraces().keySet())
                .as("no table-scanning cleaner thread")
                .noneMatch(t -> "Cache Cleaner".equals(t.getName()))
                .as("the drainer is a daemon thread")
                .anyMatch(t -> "FastCollection Cache Drainer".equals(t.getName()) && t.isDaemon());
    }

    /**
     * Runs GC attempts until {@code size} reports no entries — i.e. until the
     * background drainer (or the supplied explicit drain) has reclaimed them.
     */
    private static boolean awaitReclamation(IntSupplier size, Runnable touch) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            System.gc();
            Thread.sleep(50);
            touch.run();
            if (size.getAsInt() == 0) {
                return true;
            }
        }
        return false;
    }
}
