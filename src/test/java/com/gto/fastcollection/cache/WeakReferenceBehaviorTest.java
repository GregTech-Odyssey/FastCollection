package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that weak-value / weak-key caches drop their entries once the
 * strongly-reachable reference is gone, and that a subsequent call rebuilds
 * the entry (or accepts a fresh canonical instance).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WeakReferenceBehaviorTest {

    private static final Hash.Strategy<String> VALUE_STRATEGY = new Hash.Strategy<>() {
        @Override
        public int hashCode(String o) {
            return o.hashCode();
        }

        @Override
        public boolean equals(String a, String b) {
            return a.equals(b);
        }
    };

    @Test
    void weakValueHashCacheRecreatesAfterGc() throws InterruptedException {
        WeakValueHashCache<String, byte[]> cache = new WeakValueHashCache<>();
        byte[] big = new byte[8 * 1024 * 1024];
        cache.putIfAbsent("w", big);
        big = null; // only the cache's weak reference remains

        assertThat(awaitRecreation(() -> cache.getCache("w", k -> new byte[]{1}).length == 1))
                .as("value rebuilt with the small replacement after GC")
                .isTrue();
    }

    @Test
    void weakValueCustomHashCacheRecreatesAfterGc() throws InterruptedException {
        WeakValueCustomHashCache<String, byte[]> cache = new WeakValueCustomHashCache<>(VALUE_STRATEGY);
        byte[] big = new byte[8 * 1024 * 1024];
        cache.putIfAbsent("w", big);
        big = null;

        assertThat(awaitRecreation(() -> cache.getCache("w", k -> new byte[]{1}).length == 1))
                .as("value rebuilt with the small replacement after GC")
                .isTrue();
    }

    @Test
    void weakValueIdentityHashCacheRecreatesAfterGc() throws InterruptedException {
        WeakValueIdentityHashCache<String, byte[]> cache = new WeakValueIdentityHashCache<>();
        byte[] big = new byte[8 * 1024 * 1024];
        cache.putIfAbsent("w", big);
        big = null;

        assertThat(awaitRecreation(() -> cache.getCache("w", k -> new byte[]{1}).length == 1))
                .as("value rebuilt with the small replacement after GC")
                .isTrue();
    }

    @Test
    void weakHashInternerDropsCollectedKey() throws InterruptedException {
        WeakHashInterner<String> interner = new WeakHashInterner<>();
        String first = new String("wk");
        assertThat(interner.intern(first)).isSameAs(first);

        first = null; // only the interner's weak reference remains
        boolean reaped = awaitRecreation(() -> {
            String probe = new String("wk");
            return interner.intern(probe) == probe; // probe became canonical => old was collected
        });
        assertThat(reaped).as("old interned key collected and a fresh canonical instance accepted").isTrue();
    }

    @Test
    void weakCustomHashInternerDropsCollectedKey() throws InterruptedException {
        WeakCustomHashInterner<String> interner = new WeakCustomHashInterner<>(VALUE_STRATEGY);
        String first = new String("wk");
        assertThat(interner.intern(first)).isSameAs(first);

        first = null;
        boolean reaped = awaitRecreation(() -> {
            String probe = new String("wk");
            return interner.intern(probe) == probe;
        });
        assertThat(reaped).as("old interned key collected and a fresh canonical instance accepted").isTrue();
    }

    @Test
    void isPresentSkipsDeadNodesEarlierInTheChain() throws InterruptedException {
        // a constant hash forces every entry into the same chain
        Hash.Strategy<String> constantHash = new Hash.Strategy<>() {
            @Override
            public int hashCode(String o) {
                return 42;
            }

            @Override
            public boolean equals(String a, String b) {
                return a.equals(b);
            }
        };
        WeakCustomHashInterner<String> interner = new WeakCustomHashInterner<>(constantHash);
        String tail = new String("tail");
        interner.intern(tail);
        String head = new String("head");
        WeakReference<String> headRef = new WeakReference<>(head);
        interner.intern(head); // chain: head -> tail
        head = null;

        assertThat(awaitRecreation(() -> headRef.get() == null)).as("head instance collected").isTrue();
        // the dead head node must not hide the live canonical instance behind it
        assertThat(interner.isPresent(new String("tail"))).isTrue();
    }

    @Test
    void clearCacheRemovesDeadEntriesWithoutBreakingTheCache() throws InterruptedException {
        WeakHashInterner<String> interner = new WeakHashInterner<>();
        String doomed = new String("doomed");
        interner.intern(doomed);
        doomed = null;

        // wait until the entry is dead
        awaitRecreation(() -> !interner.isPresent(new String("doomed")));
        // the cleaner must run without errors
        interner.clearCache();

        // cache still usable
        String alive = new String("alive");
        assertThat(interner.intern(alive)).isSameAs(alive);
        assertThat(interner.isPresent(new String("alive"))).isTrue();
    }

    @Test
    void clearCacheWorksOnWeakValueCache() throws InterruptedException {
        WeakValueHashCache<String, byte[]> cache = new WeakValueHashCache<>();
        byte[] big = new byte[8 * 1024 * 1024];
        cache.putIfAbsent("w", big);
        big = null;

        awaitRecreation(() -> cache.getIfPresent("w") == null);
        cache.clearCache();
        assertThat(cache.getCache("w", k -> new byte[]{1})).hasSize(1);
    }

    /**
     * Repeatedly triggers GC (and yields) until {@code condition} holds or the
     * attempt budget is exhausted. GC timing is not deterministic, so callers
     * must interpret the result.
     */
    private static boolean awaitRecreation(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            System.gc();
            Thread.sleep(150);
            if (condition.getAsBoolean()) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
