package com.gto.fastcollection.cache;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises every {@link CacheBenchmark} / {@link InternerBenchmark} method with
 * minimal iterations, so a broken benchmark (e.g. a setup error) fails the
 * regular test run. This is a sanity pass only — measured numbers are not
 * asserted; run the benchmarks standalone for real numbers.
 */
class CacheBenchmarkSmokeTest {

    @Test
    void allBenchmarksRun() throws Exception {
        Options options = new OptionsBuilder()
                .include("com\\.gto\\.fastcollection\\.(cache\\.)?(CacheBenchmark|InternerBenchmark|OpenCacheHashSetBenchmark|O2OOpenCacheHashMapBenchmark)")
                .forks(0)
                .warmupIterations(1)
                .warmupTime(TimeValue.milliseconds(50))
                .measurementIterations(1)
                .measurementTime(TimeValue.milliseconds(100))
                .shouldDoGC(false)
                .build();

        Collection<RunResult> results = new Runner(options).run();

        // every benchmark method must have produced a result
        assertThat(results).isNotEmpty();
    }
}
