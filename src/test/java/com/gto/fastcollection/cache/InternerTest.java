package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InternerTest {

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

    static Stream<Arguments> interners() {
        return Stream.of(
                Arguments.of("HashInterner", (Supplier<Interner<String>>) HashInterner::new),
                Arguments.of("CustomHashInterner", (Supplier<Interner<String>>) () -> new CustomHashInterner<>(VALUE_STRATEGY)),
                Arguments.of("WeakHashInterner", (Supplier<Interner<String>>) WeakHashInterner::new),
                Arguments.of("WeakCustomHashInterner", (Supplier<Interner<String>>) () -> new WeakCustomHashInterner<>(VALUE_STRATEGY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void internDeduplicatesToSingleInstance(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        String first = new String("alpha");
        String second = new String("alpha");

        assertThat(interner.intern(first)).isSameAs(first);
        // an equal but distinct object must resolve to the already-interned instance
        assertThat(interner.intern(second)).isSameAs(first);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void containsReflectsInternedEntries(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        assertThat(interner.isPresent(new String("beta"))).isFalse();

        interner.intern(new String("beta"));
        assertThat(interner.isPresent(new String("beta"))).isTrue();
        assertThat(interner.isPresent("gamma")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void addReportsFirstInsertion(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        assertThat(interner.addIfAbsent(new String("delta"))).isTrue();
        // equal value already present -> not added
        assertThat(interner.addIfAbsent(new String("delta"))).isFalse();
        // intern after add returns the original instance
        assertThat(interner.intern(new String("delta"))).isSameAs(interner.intern(new String("delta")));
        assertThat(interner.isPresent(new String("delta"))).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void clearEmpties(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        String sample = new String("epsilon");
        interner.intern(sample);
        assertThat(interner.isPresent(sample)).isTrue();

        interner.clear();
        assertThat(interner.isPresent(sample)).isFalse();
        // after clear a fresh equal object is interned as its own canonical instance
        String fresh = new String("epsilon");
        assertThat(interner.intern(fresh)).isSameAs(fresh);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void concurrentInternConvergesToSingleCanonicalInstance(String name, Supplier<Interner<String>> factory)
            throws InterruptedException {
        Interner<String> interner = factory.get();
        int threads = 8;
        int keys = 64;
        int iterations = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        ConcurrentHashMap<String, String> canonical = new ConcurrentHashMap<>();

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        for (int k = 0; k < keys; k++) {
                            String key = "concurrent-key-" + k;
                            String winner = interner.intern(new String(key));
                            canonical.compute(key, (kk, old) -> old == null ? winner : old);
                            if (!interner.isPresent(key)) errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads finished").isTrue();
        pool.shutdown();

        assertThat(errors.get()).isZero();
        // each key must have exactly one canonical instance
        assertThat(canonical.values().stream().distinct().count()).isEqualTo(keys);
        for (int k = 0; k < keys; k++) {
            assertThat(interner.isPresent("concurrent-key-" + k)).isTrue();
        }
    }
}
