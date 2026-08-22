package com.gto.fastcollection.map;

import com.gto.fastcollection.fastutil.O2IOpenCacheHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
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

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Structural footprint of the map families: each invocation builds a map from
 * scratch and fills it, so with {@code -prof gc} the allocation norm divided
 * by the entry count approximates the resident bytes per entry. Keys and
 * boxed values are pre-allocated in the setup so only the structure itself
 * (arrays, entries, bookkeeping) is measured.
 *
 * <p>Run with {@code ./gradlew jmh -Pbenchmark="MapFootprintBenchmark" -Pprof=gc}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class MapFootprintBenchmark {

    public enum Impl {
        O2I_OPEN_CACHE, FASTUTIL_OBJECT2INT, JDK_HASH_MAP, JDK_CHM
    }

    @Param({"O2I_OPEN_CACHE", "FASTUTIL_OBJECT2INT", "JDK_HASH_MAP", "JDK_CHM"})
    private Impl impl;

    /** Entries per map; divide the gc.alloc.rate.norm by this. */
    @Param({"1024"})
    private int size;

    private Integer[] keys;
    private Integer[] values;

    @Setup
    public void setup() {
        keys = new Integer[size];
        values = new Integer[size];
        for (int i = 0; i < size; i++) {
            keys[i] = i;
            values[i] = i + 1;
        }
    }

    @Benchmark
    public Object build() {
        switch (impl) {
            case O2I_OPEN_CACHE: {
                var m = new O2IOpenCacheHashMap<Integer>(size);
                for (int i = 0; i < size; i++) {
                    m.put(keys[i], values[i]);
                }
                return m;
            }
            case FASTUTIL_OBJECT2INT: {
                var m = new Object2IntOpenHashMap<Integer>(size);
                for (int i = 0; i < size; i++) {
                    m.put(keys[i], values[i]);
                }
                return m;
            }
            case JDK_HASH_MAP: {
                var m = new HashMap<Integer, Integer>(size);
                for (int i = 0; i < size; i++) {
                    m.put(keys[i], values[i]);
                }
                return m;
            }
            case JDK_CHM:
            default: {
                var m = new ConcurrentHashMap<Integer, Integer>(size);
                for (int i = 0; i < size; i++) {
                    m.put(keys[i], values[i]);
                }
                return m;
            }
        }
    }
}
