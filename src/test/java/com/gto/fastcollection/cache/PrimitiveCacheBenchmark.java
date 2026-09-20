package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.bytes.Byte2ObjectFunction;
import it.unimi.dsi.fastutil.floats.Float2ObjectFunction;
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
import java.util.function.DoubleFunction;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

/**
 * Throughput comparison of the primitive-key caches: the current single-table
 * CAS core ({@code com.gto.fastcollection.cache.primitive}) against the
 * pre-refactor segmented implementation kept for reference in
 * {@code com.gto.fastcollection.cache.legacy.primitive}.
 *
 * <p>Both families expose the same API ({@code getCache}, {@code getIfPresent},
 * {@code putIfAbsent}), so the same workload drives either one and the two
 * variants differ only in the implementation under test.
 *
 * <p>Hot paths only: a hit that returns the stored value, a read-only
 * {@code getIfPresent}, and a {@code putIfAbsent} on an existing key. A
 * {@code byte} key space holds 256 distinct keys, so the 4096 row of the
 * {@code BYTE} impls is really a 256-entry table.
 *
 * <p>Like the other benchmarks this is run manually; the unit test run only
 * exercises a short smoke pass via {@link CacheBenchmarkSmokeTest}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class PrimitiveCacheBenchmark {

    public enum Impl {
        INT, LONG, DOUBLE, FLOAT, BYTE, WEAK_INT, WEAK_LONG, WEAK_DOUBLE, WEAK_FLOAT, WEAK_BYTE
    }

    /** Which implementation family is under test. */
    public enum Variant {
        CURRENT, LEGACY
    }

    @Param({"INT", "LONG", "DOUBLE", "FLOAT", "BYTE", "WEAK_INT", "WEAK_LONG", "WEAK_DOUBLE", "WEAK_FLOAT", "WEAK_BYTE"})
    private Impl impl;

    @Param({"CURRENT", "LEGACY"})
    private Variant variant;

    /** Pre-filled entry count; must be a power of two so it doubles as a mask. */
    @Param({"128", "4096"})
    private int size;

    private int mask;
    private int[] intKeys;
    private long[] longKeys;
    private double[] doubleKeys;
    private float[] floatKeys;
    private byte[] byteKeys;
    /** Strong references to the stored values: a weak-value cache must not lose them. */
    private String[] values;

    private com.gto.fastcollection.cache.primitive.IntCache<String> intCache;
    private com.gto.fastcollection.cache.primitive.LongCache<String> longCache;
    private com.gto.fastcollection.cache.primitive.DoubleCache<String> doubleCache;
    private com.gto.fastcollection.cache.primitive.FloatCache<String> floatCache;
    private com.gto.fastcollection.cache.primitive.ByteCache<String> byteCache;
    private com.gto.fastcollection.cache.primitive.WeakValueIntCache<String> weakIntCache;
    private com.gto.fastcollection.cache.primitive.WeakValueLongCache<String> weakLongCache;
    private com.gto.fastcollection.cache.primitive.WeakValueDoubleCache<String> weakDoubleCache;
    private com.gto.fastcollection.cache.primitive.WeakValueFloatCache<String> weakFloatCache;
    private com.gto.fastcollection.cache.primitive.WeakValueByteCache<String> weakByteCache;

    private com.gto.fastcollection.cache.legacy.primitive.IntCache<String> legacyIntCache;
    private com.gto.fastcollection.cache.legacy.primitive.LongCache<String> legacyLongCache;
    private com.gto.fastcollection.cache.legacy.primitive.DoubleCache<String> legacyDoubleCache;
    private com.gto.fastcollection.cache.legacy.primitive.FloatCache<String> legacyFloatCache;
    private com.gto.fastcollection.cache.legacy.primitive.ByteCache<String> legacyByteCache;
    private com.gto.fastcollection.cache.legacy.primitive.WeakValueIntCache<String> legacyWeakIntCache;
    private com.gto.fastcollection.cache.legacy.primitive.WeakValueLongCache<String> legacyWeakLongCache;
    private com.gto.fastcollection.cache.legacy.primitive.WeakValueDoubleCache<String> legacyWeakDoubleCache;
    private com.gto.fastcollection.cache.legacy.primitive.WeakValueFloatCache<String> legacyWeakFloatCache;
    private com.gto.fastcollection.cache.legacy.primitive.WeakValueByteCache<String> legacyWeakByteCache;

    @Setup
    public void setup() {
        mask = size - 1;
        intKeys = new int[size];
        longKeys = new long[size];
        doubleKeys = new double[size];
        floatKeys = new float[size];
        byteKeys = new byte[size];
        values = new String[size];
        for (int i = 0; i < size; i++) {
            intKeys[i] = i * 0x9E3779B1;
            longKeys[i] = i * 0x9E3779B97F4A7C15L;
            doubleKeys[i] = i + 0.5d;
            floatKeys[i] = i + 0.25f;
            byteKeys[i] = (byte) i;
            values[i] = "v" + i;
        }
        final IntFunction<String> intFunction = k -> "c" + k;
        final LongFunction<String> longFunction = k -> "c" + k;
        final DoubleFunction<String> doubleFunction = k -> "c" + k;
        final Float2ObjectFunction<String> floatFunction = k -> "c" + k;
        final Byte2ObjectFunction<String> byteFunction = k -> "c" + k;
        if (variant == Variant.CURRENT) {
            switch (impl) {
                case INT -> intCache = new com.gto.fastcollection.cache.primitive.IntCache<>(intFunction);
                case LONG -> longCache = new com.gto.fastcollection.cache.primitive.LongCache<>(longFunction);
                case DOUBLE -> doubleCache = new com.gto.fastcollection.cache.primitive.DoubleCache<>(doubleFunction);
                case FLOAT -> floatCache = new com.gto.fastcollection.cache.primitive.FloatCache<>(floatFunction);
                case BYTE -> byteCache = new com.gto.fastcollection.cache.primitive.ByteCache<>(byteFunction);
                case WEAK_INT -> weakIntCache = new com.gto.fastcollection.cache.primitive.WeakValueIntCache<>(intFunction);
                case WEAK_LONG -> weakLongCache = new com.gto.fastcollection.cache.primitive.WeakValueLongCache<>(longFunction);
                case WEAK_DOUBLE -> weakDoubleCache = new com.gto.fastcollection.cache.primitive.WeakValueDoubleCache<>(doubleFunction);
                case WEAK_FLOAT -> weakFloatCache = new com.gto.fastcollection.cache.primitive.WeakValueFloatCache<>(floatFunction);
                case WEAK_BYTE -> weakByteCache = new com.gto.fastcollection.cache.primitive.WeakValueByteCache<>(byteFunction);
            }
        } else {
            switch (impl) {
                case INT -> legacyIntCache = new com.gto.fastcollection.cache.legacy.primitive.IntCache<>(intFunction);
                case LONG -> legacyLongCache = new com.gto.fastcollection.cache.legacy.primitive.LongCache<>(longFunction);
                case DOUBLE -> legacyDoubleCache = new com.gto.fastcollection.cache.legacy.primitive.DoubleCache<>(doubleFunction);
                case FLOAT -> legacyFloatCache = new com.gto.fastcollection.cache.legacy.primitive.FloatCache<>(floatFunction);
                case BYTE -> legacyByteCache = new com.gto.fastcollection.cache.legacy.primitive.ByteCache<>(byteFunction);
                case WEAK_INT -> legacyWeakIntCache = new com.gto.fastcollection.cache.legacy.primitive.WeakValueIntCache<>(intFunction);
                case WEAK_LONG -> legacyWeakLongCache = new com.gto.fastcollection.cache.legacy.primitive.WeakValueLongCache<>(longFunction);
                case WEAK_DOUBLE -> legacyWeakDoubleCache = new com.gto.fastcollection.cache.legacy.primitive.WeakValueDoubleCache<>(doubleFunction);
                case WEAK_FLOAT -> legacyWeakFloatCache = new com.gto.fastcollection.cache.legacy.primitive.WeakValueFloatCache<>(floatFunction);
                case WEAK_BYTE -> legacyWeakByteCache = new com.gto.fastcollection.cache.legacy.primitive.WeakValueByteCache<>(byteFunction);
            }
        }
        for (int i = 0; i < size; i++) {
            putIfAbsentAt(i, values[i]);
        }
    }

    /** Hit path: value already present, the create function must not run. */
    @Benchmark
    public String getCacheHit() {
        return getCacheAt(ThreadLocalRandom.current().nextInt() & mask);
    }

    /** Read-only path: no store, no function. */
    @Benchmark
    public String getIfPresentHit() {
        return getIfPresentAt(ThreadLocalRandom.current().nextInt() & mask);
    }

    /** Existing key: returns the current value, never replaces it. */
    @Benchmark
    public String putIfAbsentHit() {
        return putIfAbsentAt(ThreadLocalRandom.current().nextInt() & mask, "new");
    }

    private String getCacheAt(final int i) {
        return variant == Variant.CURRENT
                ? switch (impl) {
                    case INT -> intCache.getCache(intKeys[i]);
                    case LONG -> longCache.getCache(longKeys[i]);
                    case DOUBLE -> doubleCache.getCache(doubleKeys[i]);
                    case FLOAT -> floatCache.getCache(floatKeys[i]);
                    case BYTE -> byteCache.getCache(byteKeys[i]);
                    case WEAK_INT -> weakIntCache.getCache(intKeys[i]);
                    case WEAK_LONG -> weakLongCache.getCache(longKeys[i]);
                    case WEAK_DOUBLE -> weakDoubleCache.getCache(doubleKeys[i]);
                    case WEAK_FLOAT -> weakFloatCache.getCache(floatKeys[i]);
                    case WEAK_BYTE -> weakByteCache.getCache(byteKeys[i]);
                }
                : switch (impl) {
                    case INT -> legacyIntCache.getCache(intKeys[i]);
                    case LONG -> legacyLongCache.getCache(longKeys[i]);
                    case DOUBLE -> legacyDoubleCache.getCache(doubleKeys[i]);
                    case FLOAT -> legacyFloatCache.getCache(floatKeys[i]);
                    case BYTE -> legacyByteCache.getCache(byteKeys[i]);
                    case WEAK_INT -> legacyWeakIntCache.getCache(intKeys[i]);
                    case WEAK_LONG -> legacyWeakLongCache.getCache(longKeys[i]);
                    case WEAK_DOUBLE -> legacyWeakDoubleCache.getCache(doubleKeys[i]);
                    case WEAK_FLOAT -> legacyWeakFloatCache.getCache(floatKeys[i]);
                    case WEAK_BYTE -> legacyWeakByteCache.getCache(byteKeys[i]);
                };
    }

    private String getIfPresentAt(final int i) {
        return variant == Variant.CURRENT
                ? switch (impl) {
                    case INT -> intCache.getIfPresent(intKeys[i]);
                    case LONG -> longCache.getIfPresent(longKeys[i]);
                    case DOUBLE -> doubleCache.getIfPresent(doubleKeys[i]);
                    case FLOAT -> floatCache.getIfPresent(floatKeys[i]);
                    case BYTE -> byteCache.getIfPresent(byteKeys[i]);
                    case WEAK_INT -> weakIntCache.getIfPresent(intKeys[i]);
                    case WEAK_LONG -> weakLongCache.getIfPresent(longKeys[i]);
                    case WEAK_DOUBLE -> weakDoubleCache.getIfPresent(doubleKeys[i]);
                    case WEAK_FLOAT -> weakFloatCache.getIfPresent(floatKeys[i]);
                    case WEAK_BYTE -> weakByteCache.getIfPresent(byteKeys[i]);
                }
                : switch (impl) {
                    case INT -> legacyIntCache.getIfPresent(intKeys[i]);
                    case LONG -> legacyLongCache.getIfPresent(longKeys[i]);
                    case DOUBLE -> legacyDoubleCache.getIfPresent(doubleKeys[i]);
                    case FLOAT -> legacyFloatCache.getIfPresent(floatKeys[i]);
                    case BYTE -> legacyByteCache.getIfPresent(byteKeys[i]);
                    case WEAK_INT -> legacyWeakIntCache.getIfPresent(intKeys[i]);
                    case WEAK_LONG -> legacyWeakLongCache.getIfPresent(longKeys[i]);
                    case WEAK_DOUBLE -> legacyWeakDoubleCache.getIfPresent(doubleKeys[i]);
                    case WEAK_FLOAT -> legacyWeakFloatCache.getIfPresent(floatKeys[i]);
                    case WEAK_BYTE -> legacyWeakByteCache.getIfPresent(byteKeys[i]);
                };
    }

    private String putIfAbsentAt(final int i, final String v) {
        return variant == Variant.CURRENT
                ? switch (impl) {
                    case INT -> intCache.putIfAbsent(intKeys[i], v);
                    case LONG -> longCache.putIfAbsent(longKeys[i], v);
                    case DOUBLE -> doubleCache.putIfAbsent(doubleKeys[i], v);
                    case FLOAT -> floatCache.putIfAbsent(floatKeys[i], v);
                    case BYTE -> byteCache.putIfAbsent(byteKeys[i], v);
                    case WEAK_INT -> weakIntCache.putIfAbsent(intKeys[i], v);
                    case WEAK_LONG -> weakLongCache.putIfAbsent(longKeys[i], v);
                    case WEAK_DOUBLE -> weakDoubleCache.putIfAbsent(doubleKeys[i], v);
                    case WEAK_FLOAT -> weakFloatCache.putIfAbsent(floatKeys[i], v);
                    case WEAK_BYTE -> weakByteCache.putIfAbsent(byteKeys[i], v);
                }
                : switch (impl) {
                    case INT -> legacyIntCache.putIfAbsent(intKeys[i], v);
                    case LONG -> legacyLongCache.putIfAbsent(longKeys[i], v);
                    case DOUBLE -> legacyDoubleCache.putIfAbsent(doubleKeys[i], v);
                    case FLOAT -> legacyFloatCache.putIfAbsent(floatKeys[i], v);
                    case BYTE -> legacyByteCache.putIfAbsent(byteKeys[i], v);
                    case WEAK_INT -> legacyWeakIntCache.putIfAbsent(intKeys[i], v);
                    case WEAK_LONG -> legacyWeakLongCache.putIfAbsent(longKeys[i], v);
                    case WEAK_DOUBLE -> legacyWeakDoubleCache.putIfAbsent(doubleKeys[i], v);
                    case WEAK_FLOAT -> legacyWeakFloatCache.putIfAbsent(floatKeys[i], v);
                    case WEAK_BYTE -> legacyWeakByteCache.putIfAbsent(byteKeys[i], v);
                };
    }
}
