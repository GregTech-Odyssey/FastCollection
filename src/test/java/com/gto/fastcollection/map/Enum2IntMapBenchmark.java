package com.gto.fastcollection.map;

import com.gto.fastcollection.map.enums.Enum2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
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

import java.util.EnumMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Throughput comparison of {@link Enum2IntMap} against {@link EnumMap} (boxed)
 * and fastutil's {@link Reference2IntOpenHashMap} on the hot paths: a
 * primitive hit read, a miss read, an overwrite, a primitive accumulate, and
 * a full value iteration.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class Enum2IntMapBenchmark {

    /** 64 constants; the hash-based rival pays for its table at this size. */
    private enum Keys {
        K0, K1, K2, K3, K4, K5, K6, K7, K8, K9, K10, K11, K12, K13, K14, K15,
        K16, K17, K18, K19, K20, K21, K22, K23, K24, K25, K26, K27, K28, K29, K30, K31,
        K32, K33, K34, K35, K36, K37, K38, K39, K40, K41, K42, K43, K44, K45, K46, K47,
        K48, K49, K50, K51, K52, K53, K54, K55, K56, K57, K58, K59, K60, K61, K62, K63
    }

    /** A foreign enum type, so miss reads exercise the key-type check. */
    private enum Other {
        M0, M1, M2, M3, M4, M5, M6, M7
    }

    public enum Impl {
        ENUM2, JDK, FASTUTIL
    }

    @Param({"ENUM2", "JDK", "FASTUTIL"})
    private Impl impl;

    /** How many of the 64 constants are populated. */
    @Param({"16", "64"})
    private int size;

    private Enum2IntMap<Keys> enum2;
    private EnumMap<Keys, Integer> jdk;
    private Reference2IntOpenHashMap<Keys> fastutil;
    private Keys[] keys;
    private Other[] missKeys;
    private int mask;

    @Setup
    public void setup() {
        keys = Keys.values();
        missKeys = Other.values();
        mask = size - 1;
        switch (impl) {
            case ENUM2 -> {
                enum2 = new Enum2IntMap<>(Keys.class);
                for (int i = 0; i < size; i++) {
                    enum2.put(keys[i], i + 1);
                }
            }
            case JDK -> {
                jdk = new EnumMap<>(Keys.class);
                for (int i = 0; i < size; i++) {
                    jdk.put(keys[i], i + 1);
                }
            }
            case FASTUTIL -> {
                fastutil = new Reference2IntOpenHashMap<>();
                for (int i = 0; i < size; i++) {
                    fastutil.put(keys[i], i + 1);
                }
            }
        }
    }

    /** Primitive hit read: ordinal-indexed array vs hash probe vs boxed get. */
    @Benchmark
    public int getIntHit() {
        Keys k = keys[ThreadLocalRandom.current().nextInt() & mask];
        return switch (impl) {
            case ENUM2 -> enum2.getInt(k);
            case JDK -> jdk.get(k);
            case FASTUTIL -> fastutil.getInt(k);
        };
    }

    /** Miss read through the key-type check. */
    @Benchmark
    public int getIntMiss() {
        Other k = missKeys[ThreadLocalRandom.current().nextInt() & 7];
        return switch (impl) {
            case ENUM2 -> enum2.getInt(k);
            case JDK -> jdk.get(k) == null ? 0 : 1;
            case FASTUTIL -> fastutil.getInt(k);
        };
    }

    /** Overwrite of an existing key. */
    @Benchmark
    public int putExisting() {
        Keys k = keys[ThreadLocalRandom.current().nextInt() & mask];
        return switch (impl) {
            case ENUM2 -> enum2.put(k, 7);
            case JDK -> jdk.put(k, 7);
            case FASTUTIL -> fastutil.put(k, 7);
        };
    }

    /** Primitive accumulate: the counter pattern. */
    @Benchmark
    public int addTo() {
        Keys k = keys[ThreadLocalRandom.current().nextInt() & mask];
        return switch (impl) {
            case ENUM2 -> enum2.addTo(k, 1);
            case JDK -> jdk.put(k, jdk.get(k) + 1);
            case FASTUTIL -> fastutil.addTo(k, 1);
        };
    }

    /** Full pass over all values. */
    @Benchmark
    public int iterateValues() {
        int sum = 0;
        switch (impl) {
            case ENUM2:
                for (var it = enum2.values().iterator(); it.hasNext(); ) {
                    sum += it.nextInt();
                }
                break;
            case JDK:
                for (int v : jdk.values()) {
                    sum += v;
                }
                break;
            case FASTUTIL:
                for (var it = fastutil.values().intIterator(); it.hasNext(); ) {
                    sum += it.nextInt();
                }
                break;
        }
        return sum;
    }
}
