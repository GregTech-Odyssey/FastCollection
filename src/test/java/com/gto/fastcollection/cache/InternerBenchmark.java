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

/**
 * Throughput comparison of the four {@link Interner} implementations on the hot
 * paths: {@code intern} of an already-interned sample, read-only
 * {@code isPresent}, and {@code addIfAbsent} on an existing instance.
 *
 * <p>Like {@link CacheBenchmark}, this is a manual benchmark; the unit test run
 * only exercises a short smoke pass via {@link CacheBenchmarkSmokeTest}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class InternerBenchmark {

    public enum Impl {
        HASH, CUSTOM, WEAK_HASH, WEAK_CUSTOM
    }

    @Param({"HASH", "CUSTOM", "WEAK_HASH", "WEAK_CUSTOM"})
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

    private Interner<String> interner;
    private String[] samples;
    private int mask;

    @Setup
    public void setup() {
        mask = size - 1;
        samples = new String[size];
        for (int i = 0; i < size; i++) {
            samples[i] = "sample" + i;
        }
        switch (impl) {
            case HASH:
                interner = new HashInterner<>();
                break;
            case CUSTOM:
                interner = new CustomHashInterner<>(VALUE_STRATEGY);
                break;
            case WEAK_HASH:
                interner = new WeakHashInterner<>();
                break;
            case WEAK_CUSTOM:
                interner = new WeakCustomHashInterner<>(VALUE_STRATEGY);
                break;
        }
        for (String s : samples) {
            interner.intern(s);
        }
    }

    /** Hit path: returns the canonical instance already stored. */
    @Benchmark
    public String internHit() {
        return interner.intern(samples[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /** Read-only membership test. */
    @Benchmark
    public boolean isPresentHit() {
        return interner.isPresent(samples[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /** Existing instance: reports that nothing was added. */
    @Benchmark
    public boolean addIfAbsentHit() {
        return interner.addIfAbsent(samples[ThreadLocalRandom.current().nextInt() & mask]);
    }
}
