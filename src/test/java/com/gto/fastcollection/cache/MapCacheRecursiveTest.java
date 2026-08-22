package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
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
 * Verifies the reentrant {@link MapCache#getCacheRecursive} API: the create
 * function runs outside any internal lock, so it may call back into the same
 * cache to resolve dependencies. Values already present are reused without
 * re-running the function, and concurrent computations of one key converge to
 * a single stored value (first one wins).
 */
class MapCacheRecursiveTest {

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

    /** All cache implementations; same sources as {@link MapCacheTest}. */
    static Stream<Arguments> caches() {
        return Stream.of(
                Arguments.of("HashCache",
                        (Supplier<MapCache<String, String>>) HashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) HashCache::new),
                Arguments.of("IdentityHashCache",
                        (Supplier<MapCache<String, String>>) IdentityHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) IdentityHashCache::new),
                Arguments.of("CustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new CustomHashCache<>(VALUE_STRATEGY),
                        (Function<Function<String, String>, MapCache<String, String>>) f -> new CustomHashCache<>(VALUE_STRATEGY, f)),
                Arguments.of("WeakValueHashCache",
                        (Supplier<MapCache<String, String>>) WeakValueHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) WeakValueHashCache::new),
                Arguments.of("WeakValueIdentityHashCache",
                        (Supplier<MapCache<String, String>>) WeakValueIdentityHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) WeakValueIdentityHashCache::new),
                Arguments.of("WeakValueCustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new WeakValueCustomHashCache<>(VALUE_STRATEGY),
                        (Function<Function<String, String>, MapCache<String, String>>) f -> new WeakValueCustomHashCache<>(VALUE_STRATEGY, f))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void recursiveResolvesDependencies(String name, Supplier<MapCache<String, String>> factory,
                                       Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        // the outer computation reads a dependency through the same cache
        String child = cache.getCacheRecursive("child", k -> {
            String parent = cache.getCacheRecursive("parent", kk -> "root");
            return parent + "-child";
        });

        assertThat(child).isEqualTo("root-child");
        // both keys are now cached
        assertThat(cache.getIfPresent("parent")).isEqualTo("root");
        assertThat(cache.getIfPresent("child")).isEqualTo("root-child");
    }

    /** Single-segment variants: recursive keys necessarily share the one segment. */
    static Stream<Arguments> singleSegmentCaches() {
        return Stream.of(
                Arguments.of("HashCache", (Supplier<MapCache<String, String>>) HashCache::new),
                Arguments.of("IdentityHashCache", (Supplier<MapCache<String, String>>) () -> new IdentityHashCache<>(1)),
                Arguments.of("CustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new CustomHashCache<>(VALUE_STRATEGY, 1)),
                Arguments.of("WeakValueHashCache", (Supplier<MapCache<String, String>>) () -> new WeakValueHashCache<>(1)),
                Arguments.of("WeakValueIdentityHashCache",
                        (Supplier<MapCache<String, String>>) () -> new WeakValueIdentityHashCache<>(1)),
                Arguments.of("WeakValueCustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new WeakValueCustomHashCache<>(VALUE_STRATEGY, 1)));
    }

    @ParameterizedTest(name = "{0} [single segment]")
    @MethodSource("singleSegmentCaches")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void recursiveResolvesDependenciesWhenKeysShareOneSegment(String name, Supplier<MapCache<String, String>> factory) {
        MapCache<String, String> cache = factory.get();

        // with a single segment the inner call necessarily re-enters the segment
        // the outer computation is running in, so the function must never hold a lock
        String child = cache.getCacheRecursive("child", k -> {
            String parent = cache.getCacheRecursive("parent", kk -> "root");
            return parent + "-child";
        });

        assertThat(child).isEqualTo("root-child");
        assertThat(cache.getIfPresent("parent")).isEqualTo("root");
        assertThat(cache.getIfPresent("child")).isEqualTo("root-child");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void recursiveReusesCachedValueWithoutRerunning(String name, Supplier<MapCache<String, String>> factory,
                                                    Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.getCacheRecursive("k", k -> {
            calls.incrementAndGet();
            return "v1";
        })).isEqualTo("v1");
        // second call must hit the cache, not invoke the function again
        assertThat(cache.getCacheRecursive("k", k -> {
            calls.incrementAndGet();
            return "v2";
        })).isEqualTo("v1");

        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void recursiveWithConstructorFunction(String name, Supplier<MapCache<String, String>> factory,
                                          Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = withFunction.apply(k -> "cf:" + k);

        assertThat(cache.getCacheRecursive("key")).isEqualTo("cf:key");
        // cached: the constructor function must not run again
        assertThat(cache.getCacheRecursive("key")).isEqualTo("cf:key");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void clearThenRecursiveRecreates(String name, Supplier<MapCache<String, String>> factory,
                                     Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        cache.putIfAbsent("k", "old");
        cache.clear();

        assertThat(cache.getCacheRecursive("k", k -> "new")).isEqualTo("new");
        assertThat(cache.getIfPresent("k")).isEqualTo("new");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void concurrentRecursiveComputationsConvergeToSingleValue(String name, Supplier<MapCache<String, String>> factory,
                                                              Function<Function<String, String>, MapCache<String, String>> withFunction)
            throws InterruptedException {
        MapCache<String, String> cache = factory.get();
        int threads = 8;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        String[] results = new String[threads * iterations];
        AtomicInteger idx = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        // each computation yields a distinct instance; the first one stored wins
                        String v = cache.getCacheRecursive("shared", k -> new String(k + "-" + System.nanoTime()));
                        results[idx.getAndIncrement()] = v;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdown();

        // every caller must observe the same canonical value
        assertThat(Arrays.stream(results).distinct().count()).isEqualTo(1);
        assertThat(cache.getIfPresent("shared")).isEqualTo(results[0]);
    }
}
