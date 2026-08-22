package com.gto.fastcollection;

import com.gto.fastcollection.fastutil.O2OOpenCacheHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput comparison of {@link O2OOpenCacheHashMap} against the JDK
 * {@link HashMap} and the fastutil {@link Object2ObjectOpenHashMap} (the
 * superclass the cache variant extends). As with
 * {@link OpenCacheHashSetBenchmark}, the cached-hash map skips {@code equals}
 * probes via a stored per-slot hash, and reuses stored hashes on rehash.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class O2OOpenCacheHashMapBenchmark {

    public enum Impl {
        OPEN_CACHE, JDK_HASH_MAP, FASTUTIL_OPEN
    }

    public enum KeyType {
        STRING, EXPENSIVE
    }

    @Param({"OPEN_CACHE", "JDK_HASH_MAP", "FASTUTIL_OPEN"})
    private Impl impl;

    @Param({"STRING", "EXPENSIVE"})
    private KeyType keyType;

    /** Pre-filled entry count; must be a power of two so it doubles as a mask. */
    @Param({"128", "4096"})
    private int size;

    private Map<Object, Object> map;
    private Object[] keys;
    private int mask;

    @Setup
    public void setup() {
        mask = size - 1;
        keys = new Object[size];
        for (int i = 0; i < size; i++) {
            keys[i] = keyType == KeyType.STRING ? "key" + i : new ExpensiveKey("key" + i);
        }
        switch (impl) {
            case OPEN_CACHE: {
                O2OOpenCacheHashMap<Object, Object> m = new O2OOpenCacheHashMap<>(size);
                for (Object k : keys) {
                    m.put(k, k);
                }
                map = m;
                break;
            }
            case JDK_HASH_MAP: {
                Map<Object, Object> m = new HashMap<>(size);
                for (Object k : keys) {
                    m.put(k, k);
                }
                map = m;
                break;
            }
            case FASTUTIL_OPEN: {
                Object2ObjectOpenHashMap<Object, Object> m = new Object2ObjectOpenHashMap<>(size);
                for (Object k : keys) {
                    m.put(k, k);
                }
                map = m;
                break;
            }
        }
    }

    /** Hit path: value present, probe walks the cluster. */
    @Benchmark
    public Object getHit() {
        return map.get(keys[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /** Existing key: overwrite the value, return the previous one. */
    @Benchmark
    public Object putExisting() {
        return map.put(keys[ThreadLocalRandom.current().nextInt() & mask], "new");
    }

    /** Membership probe. */
    @Benchmark
    public boolean containsKeyHit() {
        return map.containsKey(keys[ThreadLocalRandom.current().nextInt() & mask]);
    }
}
