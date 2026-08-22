package com.gto.fastcollection.cache;

import org.openjdk.jmh.runner.Runner;

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
 * {@code ./gradlew jmh -Pbenchmark="CacheBenchmark"} filters which benchmarks
 * run. An optional second argument adds JMH profilers, e.g. {@code gc} for
 * allocation norms: {@code ./gradlew jmh -Pbenchmark="..." -Pprof=gc}.
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String filter = args.length > 0 ? args[0] : ".*";
        var builder = new OptionsBuilder()
                // no forks/iterations override: respect the class annotations
                .include(filter);
        if (args.length > 1 && !args[1].isEmpty()) {
            builder.addProfiler(args[1]);
        }
        new Runner(builder.build()).run();
    }
}
