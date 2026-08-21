package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Throughput comparison of the six {@link MapCache} implementations on the hot
 * paths: a cache hit, a read-only {@code getIfPresent}, a {@code putIfAbsent}
 * on an existing key, and a cold-key {@code getCache} (compute + store).
 *
 * <p>Benchmarks are not part of the regular unit test run; execute them from an
 * IDE or with a manual JMH run (e.g. {@code gradle test} only runs the smoke
 * test in {@link CacheBenchmarkSmokeTest}). Results are only meaningful
 * relative to each other, and are most reliable when all implementations share
 * the JVM.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CacheBenchmark {

    public enum Impl {
        HASH, IDENTITY, CUSTOM, WEAK_VALUE_HASH, WEAK_VALUE_IDENTITY, WEAK_VALUE_CUSTOM
    }

    @Param({"HASH", "IDENTITY", "CUSTOM", "WEAK_VALUE_HASH", "WEAK_VALUE_IDENTITY", "WEAK_VALUE_CUSTOM"})
    private Impl impl;

    /** Pre-filled entry count; must be a power of two so it doubles as a mask. */
    @Param({"128", "4096"})
    private int size;

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

    private MapCache<String, String> cache;
    private String[] keys;
    private String[] coldKeys;
    private int mask;

    @Setup
    public void setup() {
        mask = size - 1;
        keys = new String[size];
        coldKeys = new String[size];
        for (int i = 0; i < size; i++) {
            keys[i] = "key" + i;
            coldKeys[i] = "cold" + i;
        }
        switch (impl) {
            case HASH:
                cache = new HashCache<>();
                break;
            case IDENTITY:
                cache = new IdentityHashCache<>();
                break;
            case CUSTOM:
                cache = new CustomHashCache<>(VALUE_STRATEGY);
                break;
            case WEAK_VALUE_HASH:
                cache = new WeakValueHashCache<>();
                break;
            case WEAK_VALUE_IDENTITY:
                cache = new WeakValueIdentityHashCache<>();
                break;
            case WEAK_VALUE_CUSTOM:
                cache = new WeakValueCustomHashCache<>(VALUE_STRATEGY);
                break;
        }
        for (String k : keys) {
            cache.putIfAbsent(k, k);
        }
    }

    /** Hit path: value already present, the create function must not run. */
    @Benchmark
    public String getCacheHit() {
        return cache.getCache(keys[ThreadLocalRandom.current().nextInt() & mask], Function.identity());
    }

    /** Read-only path: no store, no function. */
    @Benchmark
    public String getIfPresentHit() {
        return cache.getIfPresent(keys[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /** Existing key: returns the current value, never replaces it. */
    @Benchmark
    public String putIfAbsentHit() {
        return cache.putIfAbsent(keys[ThreadLocalRandom.current().nextInt() & mask], "new");
    }

    /** Cold keys: compute-and-store path (inserts once per key, then hits). */
    @Benchmark
    public String getCacheColdKey() {
        return cache.getCache(coldKeys[ThreadLocalRandom.current().nextInt() & mask], Function.identity());
    }
}
