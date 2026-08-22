package com.gto.fastcollection;

import com.gto.fastcollection.fastutil.OpenCacheHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput comparison of {@link OpenCacheHashSet} against the JDK
 * {@link HashSet} and the fastutil {@link ObjectOpenHashSet} (the superclass
 * the cache variant extends). The cache variant stores each slot's hash, so a
 * probe compares the stored hash first and skips {@code equals} for
 * mismatches; benchmarks use both cheap keys (String) and keys with expensive
 * {@code hashCode}/{@code equals} to expose that difference.
 *
 * <p>All three sets are single-threaded structures, so the state is
 * benchmark-scoped and no locking is involved.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class OpenCacheHashSetBenchmark {

    public enum Impl {
        OPEN_CACHE, JDK_HASH_SET, FASTUTIL_OPEN
    }

    public enum KeyType {
        STRING, EXPENSIVE
    }

    @Param({"OPEN_CACHE", "JDK_HASH_SET", "FASTUTIL_OPEN"})
    private Impl impl;

    @Param({"STRING", "EXPENSIVE"})
    private KeyType keyType;

    /** Pre-filled entry count; must be a power of two so it doubles as a mask. */
    @Param({"128", "4096"})
    private int size;

    private Set<Object> set;
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
                OpenCacheHashSet<Object> s = new OpenCacheHashSet<>(size);
                for (Object k : keys) {
                    s.add(k);
                }
                set = s;
                break;
            }
            case JDK_HASH_SET: {
                Set<Object> s = new HashSet<>(size);
                for (Object k : keys) {
                    s.add(k);
                }
                set = s;
                break;
            }
            case FASTUTIL_OPEN: {
                ObjectOpenHashSet<Object> s = new ObjectOpenHashSet<>(size);
                for (Object k : keys) {
                    s.add(k);
                }
                set = s;
                break;
            }
        }
    }

    /** Hit path: the key is present, the probe walks the cluster. */
    @Benchmark
    public boolean containsHit() {
        return set.contains(keys[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /** Duplicate add: walks the cluster and reports {@code false}. */
    @Benchmark
    public boolean addExisting() {
        return set.add(keys[ThreadLocalRandom.current().nextInt() & mask]);
    }

    /**
     * {@code addOrGet} on an existing key: returns the stored instance.
     * {@code addOrGet} is specific to {@link OpenCacheHashSet}; the JDK/fastutil
     * variants have no equivalent, so for those implementations this falls back
     * to a real (non-foldable) operation so the black hole does not collapse the
     * call into a constant.
     */
    @Benchmark
    public Object addOrGetExisting() {
        if (set instanceof OpenCacheHashSet) {
            return ((OpenCacheHashSet<Object>) set).addOrGet(keys[ThreadLocalRandom.current().nextInt() & mask]);
        }
        return set.size() + (set.contains(keys[ThreadLocalRandom.current().nextInt() & mask]) ? 1 : 0);
    }
}
