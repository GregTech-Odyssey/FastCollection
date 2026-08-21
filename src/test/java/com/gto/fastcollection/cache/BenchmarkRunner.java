package com.gto.fastcollection.cache;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Standalone entry point for the real performance benchmarks. Unlike
 * {@link CacheBenchmarkSmokeTest} (which overrides the benchmark annotations
 * with a single quick iteration for CI), this runner does <b>not</b> override
 * the {@code @Fork} / {@code @Warmup} / {@code @Measurement} annotations, so
 * each benchmark runs with its configured forks and iterations and the numbers
 * are trustworthy.
 *
 * <p>Run from the IDE (execute {@code main}) or from Gradle:
 * {@code ./gradlew jmh} if wired up, otherwise via the test classpath.
 * Optional arguments filter which benchmarks run, e.g.:
 * {@code OpenCacheHashSetBenchmark} or {@code O2OOpenCacheHashMapBenchmark.getHit}.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String filter = args.length > 0 ? args[0] : ".*";
        Options options = new OptionsBuilder()
                // no forks/iterations override: respect the class annotations
                .include(filter)
                .build();

        new Runner(options).run();
    }
}
