package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioral guarantees that go beyond the happy path:
 * <ul>
 *   <li>{@link MapCache#getCache(Object, Function)} runs the create function at
 *       most once per key even under contention (unlike the recursive variant);</li>
 *   <li>the no-argument methods require a constructor factory and throw
 *       {@link NullPointerException} without one; an explicit {@code null}
 *       factory is accepted at construction;</li>
 *   <li>{@link HashCache} rejects a create function that calls back into the
 *       cache (the map's lock is not reentrant);</li>
 *   <li>identity-based caches key by object identity while strategy-based caches
 *       key by value.</li>
 * </ul>
 */
class MapCacheGuaranteesTest {

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

    static Stream<Arguments> caches() {
        return Stream.of(
                Arguments.of("HashCache", (Supplier<MapCache<String, String>>) HashCache::new),
                Arguments.of("IdentityHashCache", (Supplier<MapCache<String, String>>) IdentityHashCache::new),
                Arguments.of("CustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new CustomHashCache<>(VALUE_STRATEGY)),
                Arguments.of("WeakValueHashCache", (Supplier<MapCache<String, String>>) WeakValueHashCache::new),
                Arguments.of("WeakValueIdentityHashCache", (Supplier<MapCache<String, String>>) WeakValueIdentityHashCache::new),
                Arguments.of("WeakValueCustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new WeakValueCustomHashCache<>(VALUE_STRATEGY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void concurrentGetCacheRunsCreateFunctionExactlyOnce(String name, Supplier<MapCache<String, String>> factory)
            throws InterruptedException {
        MapCache<String, String> cache = factory.get();
        AtomicInteger creations = new AtomicInteger();
        // a single shared key instance so identity-based caches behave identically
        String key = new String("once");
        int threads = 8;
        int iterations = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String v = cache.getCache(key, k -> {
                            creations.incrementAndGet();
                            return "v";
                        });
                        if (!"v".equals(v)) failures.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdown();

        assertThat(failures.get()).isZero();
        // the write-locked / computeIfAbsent path must create the value only once
        assertThat(creations.get()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void noFactoryNoArgMethodsThrowNpe(String name, Supplier<MapCache<String, String>> factory) {
        MapCache<String, String> cache = factory.get();

        // no factory was wired into the constructor
        assertThatThrownBy(() -> cache.getCache("k"))
                .as("getCache(k) without a constructor factory")
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache.getCacheRecursive("k"))
                .as("getCacheRecursive(k) without a constructor factory")
                .isInstanceOf(NullPointerException.class);

        // the explicit-function methods and the read-only lookup still work
        assertThat(cache.getCache("k", k -> "v")).isEqualTo("v");
        assertThat(cache.getIfPresent("k")).isEqualTo("v");
        assertThat(cache.getCacheRecursive("k2", k -> "v2")).isEqualTo("v2");
    }

    @Test
    void nullFactoryIsAllowedAtConstruction() {
        // every factory-taking constructor accepts null: it means "no factory"
        // and only the no-argument convenience methods fail (at call time)
        List<MapCache<String, String>> caches = List.of(
                new HashCache<>(null),
                new IdentityHashCache<>(null),
                new CustomHashCache<>(VALUE_STRATEGY, null),
                new WeakValueHashCache<>(null),
                new WeakValueIdentityHashCache<>(null),
                new WeakValueCustomHashCache<>(VALUE_STRATEGY, null));

        for (MapCache<String, String> cache : caches) {
            assertThatThrownBy(() -> cache.getCache("k"))
                    .as("getCache(k) with a null factory")
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> cache.getCacheRecursive("k"))
                    .as("getCacheRecursive(k) with a null factory")
                    .isInstanceOf(NullPointerException.class);
            // explicit-function methods still work
            assertThat(cache.getCache("k", k -> "v")).isEqualTo("v");
            assertThat(cache.getCacheRecursive("k2", k -> "v2")).isEqualTo("v2");
        }
    }

    @Test
    void hashCacheRejectsRecursiveCreateFunction() {
        HashCache<String, String> cache = new HashCache<>();

        // computeIfAbsent runs the function under the map's lock; calling back
        // into the same cache for the same key must fail fast instead of deadlocking
        assertThatThrownBy(() -> cache.getCache("k", k -> cache.getCache("k", kk -> "inner")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityCacheDistinguishesEqualButDistinctKeys() {
        IdentityHashCache<String, String> cache = new IdentityHashCache<>();
        String a = new String("dup");
        String b = new String("dup");
        assertThat(a).isNotSameAs(b);

        cache.putIfAbsent(a, "va");
        cache.putIfAbsent(b, "vb");

        // identity semantics: two distinct objects are two entries even if equal
        assertThat(cache.getIfPresent(a)).isEqualTo("va");
        assertThat(cache.getIfPresent(b)).isEqualTo("vb");
    }

    @Test
    void valueStrategyCacheGroupsEqualKeys() {
        CustomHashCache<String, String> cache = new CustomHashCache<>(VALUE_STRATEGY);
        String a = new String("dup");
        String b = new String("dup");

        cache.putIfAbsent(a, "va");
        // b is equal under the strategy, so the existing entry is returned
        assertThat(cache.putIfAbsent(b, "vb")).isEqualTo("va");
        assertThat(cache.getIfPresent(b)).isEqualTo("va");
    }
}
