package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MapCacheTest {

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

    /**
     * All cache implementations. Each entry provides a plain factory and a factory
     * that wires a {@code Function} into the constructor. Keys in tests are
     * interned string literals so identity-based implementations ({@code ==})
     * behave like value-based ones.
     */
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
    void getCacheCreatesAndReuses(String name, Supplier<MapCache<String, String>> factory,
                                  Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        assertThat(cache.getCache("apple", k -> "created:" + k)).isEqualTo("created:apple");
        // second call must return the cached instance, not call the function again
        assertThat(cache.getCache("apple", k -> "recreated")).isEqualTo("created:apple");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void getCacheWithConstructorFunction(String name, Supplier<MapCache<String, String>> factory,
                                         Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = withFunction.apply(k -> "cf:" + k);

        assertThat(cache.getCache("key")).isEqualTo("cf:key");
        assertThat(cache.getCache("key")).isEqualTo("cf:key");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void getIfAbsentDoesNotInsert(String name, Supplier<MapCache<String, String>> factory,
                                  Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        // absent key -> null, and must not insert anything
        assertThat(cache.getIfPresent("missing")).isNull();

        cache.putIfAbsent("present", "v1");
        assertThat(cache.getIfPresent("present")).isEqualTo("v1");
        // present value must not be replaced by a later put of a different value
        cache.putIfAbsent("present", "v2");
        assertThat(cache.getIfPresent("present")).isEqualTo("v1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void putSemantics(String name, Supplier<MapCache<String, String>> factory,
                      Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        // first put returns the inserted value
        assertThat(cache.putIfAbsent("k", "first")).isEqualTo("first");
        // second put returns the previous value and keeps the first
        assertThat(cache.putIfAbsent("k", "second")).isEqualTo("first");
        assertThat(cache.getCache("k", k -> "none")).isEqualTo("first");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void clearEmpties(String name, Supplier<MapCache<String, String>> factory,
                      Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();

        cache.putIfAbsent("a", "1");
        cache.putIfAbsent("b", "2");
        cache.clear();

        assertThat(cache.getIfPresent("a")).isNull();
        assertThat(cache.getIfPresent("b")).isNull();
        // after clear, getCache must re-create
        assertThat(cache.getCache("a", k -> "fresh")).isEqualTo("fresh");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void concurrentGetAndPut(String name, Supplier<MapCache<String, String>> factory,
                             Function<Function<String, String>, MapCache<String, String>> withFunction) throws InterruptedException {
        MapCache<String, String> cache = factory.get();
        int threads = 8;
        int iterations = 500;
        // Shared key instances so identity-based implementations (==) behave like value-based ones.
        String[] keys = new String[32];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new String("key" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        String key = keys[i % keys.length];
                        cache.putIfAbsent(key, "val" + tid);
                        if (cache.getIfPresent(key) == null) failures.incrementAndGet();
                        if (cache.getCache(key, k -> "never") == null) failures.incrementAndGet();
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
        // every key must still be present after the storm
        for (String key : keys) {
            assertThat(cache.getIfPresent(key)).isNotNull();
        }
    }
}
