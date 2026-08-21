package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference consistency of the weak-reference variants. Their contract is the
 * same as the strong ones while the referent is still strongly reachable — a
 * held instance is returned again and again — and differs only after garbage
 * collection: the collected instance may be replaced by a fresh one, which then
 * becomes the new stable canonical instance.
 *
 * <p>GC timing is not deterministic, so the re-creation tests poll with
 * {@link System#gc()} until the observed state flips (see {@link #awaitRecreation}).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WeakReferenceConsistencyTest {

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

    static Stream<Arguments> weakValueCaches() {
        return Stream.of(
                Arguments.of("WeakValueHashCache", (Supplier<MapCache<String, byte[]>>) WeakValueHashCache::new),
                Arguments.of("WeakValueIdentityHashCache", (Supplier<MapCache<String, byte[]>>) WeakValueIdentityHashCache::new),
                Arguments.of("WeakValueCustomHashCache",
                        (Supplier<MapCache<String, byte[]>>) () -> new WeakValueCustomHashCache<>(VALUE_STRATEGY))
        );
    }

    static Stream<Arguments> weakInterners() {
        return Stream.of(
                Arguments.of("WeakHashInterner", (Supplier<Interner<String>>) WeakHashInterner::new),
                Arguments.of("WeakCustomHashInterner", (Supplier<Interner<String>>) () -> new WeakCustomHashInterner<>(VALUE_STRATEGY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakValueCaches")
    void sameInstanceWhileStronglyReferenced(String name, Supplier<MapCache<String, byte[]>> factory) {
        MapCache<String, byte[]> cache = factory.get();

        byte[] held = cache.getCache("k", k -> new byte[16]);
        assertThat(cache.getCache("k", k -> new byte[16])).isSameAs(held);
        assertThat(cache.getIfPresent("k")).isSameAs(held);
        assertThat(cache.putIfAbsent("k", new byte[16])).isSameAs(held);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakValueCaches")
    void rebuildsFreshInstanceAfterGc(String name, Supplier<MapCache<String, byte[]>> factory) throws InterruptedException {
        MapCache<String, byte[]> cache = factory.get();

        byte[] big = new byte[8 * 1024 * 1024];
        cache.putIfAbsent("k", big);
        assertThat(cache.getIfPresent("k")).isSameAs(big);

        big = null; // only the cache's weak reference remains
        boolean rebuilt = awaitRecreation(() -> {
            byte[] fresh = cache.getCache("k", k -> new byte[]{1});
            // fresh instance installed and immediately stable
            return fresh.length == 1 && cache.getIfPresent("k") == fresh;
        });
        assertThat(rebuilt).as("value recreated as a fresh instance after GC").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakInterners")
    void canonicalWhileHeld(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        String held = new String("k");
        assertThat(interner.intern(held)).isSameAs(held);
        // repeated intern and probes must keep returning the held instance
        assertThat(interner.intern(new String("k"))).isSameAs(held);
        assertThat(interner.intern(new String("k"))).isSameAs(held);
        assertThat(interner.isPresent(new String("k"))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("weakInterners")
    void freshCanonicalAfterGc(String name, Supplier<Interner<String>> factory) throws InterruptedException {
        Interner<String> interner = factory.get();

        String held = new String("k");
        assertThat(interner.intern(held)).isSameAs(held);
        held = null; // only the interner's weak reference remains

        boolean reaped = awaitRecreation(() -> {
            String probe = new String("k");
            // the probe becomes canonical only if the old instance was collected
            return interner.intern(probe) == probe;
        });
        assertThat(reaped).as("collected canonical instance replaced by a fresh one").isTrue();
    }

    /**
     * Repeatedly triggers GC (and yields) until {@code condition} holds or the
     * attempt budget is exhausted.
     */
    private static boolean awaitRecreation(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            System.gc();
            Thread.sleep(100);
            if (condition.getAsBoolean()) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
