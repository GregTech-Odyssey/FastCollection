package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Drives one cache implementation on a fixed mixed workload for a fixed
 * wall-clock time, so that the spark standalone agent can sample it, and then
 * asks spark to stop and save the profile.
 *
 * <p>Run it with the agent attached and profiling already started, for example
 * (Windows paths shown, the agent jar comes from the spark downloads page):
 *
 * <pre>{@code
 * java -javaagent:spark.jar=start -cp <test runtime classpath> \
 *      com.gto.fastcollection.cache.SparkProfileHarness cache:CUSTOM 60
 * java -javaagent:spark.jar=start -cp <test runtime classpath> \
 *      com.gto.fastcollection.cache.SparkProfileHarness cache:LEGACY_CUSTOM 60
 * }</pre>
 *
 * <p>The first argument selects the target: {@code cache:IMPL},
 * {@code interner:IMPL} (implementation names as in {@link CacheBenchmark} and
 * {@link InternerBenchmark}, so {@code LEGACY_*} selects the pre-refactor
 * implementation kept in {@code com.gto.fastcollection.cache.legacy}) or
 * {@code primitive:INT|WEAK_INT:CURRENT|LEGACY}. The second argument is the
 * profile duration in seconds (default 45); pass {@code upload} as the third
 * argument to upload the profile and print a viewer link instead of saving it
 * to a local file.
 *
 * <p>Spark is driven reflectively on purpose: the agent jar is not a project
 * dependency, so this harness compiles without it and only needs it on the
 * classpath (via {@code -javaagent}) at runtime. Without the agent attached the
 * workload still runs; the harness then reports that no spark platform was
 * found.
 */
public final class SparkProfileHarness {

    private static final int KEY_COUNT = 4096;

    private static final Hash.Strategy<String> STRATEGY = new Hash.Strategy<>() {
        @Override
        public int hashCode(String o) {
            return o.hashCode();
        }

        @Override
        public boolean equals(String a, String b) {
            return a.equals(b);
        }
    };

    private SparkProfileHarness() {
    }

    public static void main(String[] args) throws Exception {
        final String target = args.length > 0 ? args[0] : "cache:CUSTOM";
        final int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 45;
        final boolean upload = args.length > 2 && args[2].equalsIgnoreCase("upload");
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int fixedOp = -1;
        for (String arg : args) {
            if (arg.startsWith("threads=")) {
                threads = Integer.parseInt(arg.substring("threads=".length()));
            } else if (arg.equalsIgnoreCase("readOnly")) {
                fixedOp = 0;
            } else if (arg.equalsIgnoreCase("getCache")) {
                fixedOp = 1;
            }
        }
        final String[] parts = target.split(":");
        System.out.println("[harness] target=" + target + " seconds=" + seconds + " threads=" + threads
                + " op=" + (fixedOp < 0 ? "mixed" : String.valueOf(fixedOp)) + " upload=" + upload);
        final long ops = switch (parts[0]) {
            case "cache" -> runCache(parts.length > 1 ? parts[1] : "CUSTOM", seconds, threads, fixedOp);
            case "interner" -> runInterner(parts.length > 1 ? parts[1] : "CUSTOM", seconds, threads, fixedOp);
            case "primitive" -> runPrimitive(
                    parts.length > 1 ? parts[1] : "INT",
                    parts.length > 2 ? parts[2] : "CURRENT",
                    seconds, threads, fixedOp);
            default -> throw new IllegalArgumentException("unknown target family: " + parts[0]);
        };
        System.out.println("[harness] workload finished, " + ops + " operations");
        stopProfiler(upload);
        System.out.println("[harness] done");
        System.exit(0);
    }

    /**
     * Stops the running profiler through spark's own command layer.
     *
     * @param upload whether spark should upload the profile (and print a viewer
     *               link) instead of writing it to a local file
     */
    private static void stopProfiler(boolean upload) throws Exception {
        final Object sender;
        final Object platform;
        try {
            final Class<?> senderClass = Class.forName("me.lucko.spark.standalone.StandaloneCommandSender");
            sender = senderClass.getField("SYSTEM_OUT").get(null);
            final Object spark = Class.forName("me.lucko.spark.api.SparkProvider").getMethod("get").invoke(null);
            final Field platformField = spark.getClass().getDeclaredField("platform");
            platformField.setAccessible(true);
            platform = platformField.get(spark);
        } catch (ReflectiveOperationException e) {
            System.out.println("[harness] no spark platform found (run with -javaagent:spark.jar=start): " + e);
            return;
        }
        final Method execute = platform.getClass().getMethod(
                "executeCommand", Class.forName("me.lucko.spark.common.command.sender.CommandSender"), String[].class);
        final String[] command = upload
                ? new String[]{"profiler", "stop", "--upload"}
                : new String[]{"profiler", "stop", "--save-to-file"};
        final Object result = execute.invoke(platform, sender, (Object) command);
        if (result instanceof CompletableFuture<?> future) {
            future.join();
        }
        System.out.println("[harness] profiler stop command completed");
    }

    private static long runCache(String name, int seconds, int threads, int fixedOp) throws InterruptedException {
        final MapCache<String, String> cache = switch (name) {
            case "HASH" -> new HashCache<>();
            case "IDENTITY" -> new IdentityHashCache<>(Function.identity());
            case "CUSTOM" -> new CustomHashCache<>(STRATEGY, Function.identity());
            case "WEAK_VALUE_HASH" -> new WeakValueHashCache<>(Function.identity());
            case "WEAK_VALUE_IDENTITY" -> new WeakValueIdentityHashCache<>(Function.identity());
            case "WEAK_VALUE_CUSTOM" -> new WeakValueCustomHashCache<>(STRATEGY, Function.identity());
            case "LEGACY_IDENTITY" -> new com.gto.fastcollection.cache.legacy.IdentityHashCache<>(Function.identity());
            case "LEGACY_CUSTOM" -> new com.gto.fastcollection.cache.legacy.CustomHashCache<>(STRATEGY, Function.identity());
            case "LEGACY_WEAK_VALUE_HASH" -> new com.gto.fastcollection.cache.legacy.WeakValueHashCache<>(Function.identity());
            case "LEGACY_WEAK_VALUE_IDENTITY" ->
                    new com.gto.fastcollection.cache.legacy.WeakValueIdentityHashCache<>(Function.identity());
            case "LEGACY_WEAK_VALUE_CUSTOM" ->
                    new com.gto.fastcollection.cache.legacy.WeakValueCustomHashCache<>(STRATEGY, Function.identity());
            default -> throw new IllegalArgumentException("unknown cache impl: " + name);
        };
        System.out.println("[harness] cache impl = " + cache.getClass().getName());
        return run(seconds, threads, fixedOp, (key, op) -> switch (op) {
            case 0 -> cache.getIfPresent(key);
            case 1 -> cache.getCache(key, Function.identity());
            case 2 -> cache.putIfAbsent(key, "v");
            default -> cache.getCache(key, Function.identity());
        });
    }

    private static long runInterner(String name, int seconds, int threads, int fixedOp) throws InterruptedException {
        final Interner<String> interner = switch (name) {
            case "HASH" -> new HashInterner<>();
            case "CUSTOM" -> new CustomHashInterner<>(STRATEGY);
            case "WEAK_HASH" -> new WeakHashInterner<>();
            case "WEAK_CUSTOM" -> new WeakCustomHashInterner<>(STRATEGY);
            case "LEGACY_CUSTOM" -> new com.gto.fastcollection.cache.legacy.CustomHashInterner<>(STRATEGY);
            case "LEGACY_WEAK_HASH" -> new com.gto.fastcollection.cache.legacy.WeakHashInterner<>();
            case "LEGACY_WEAK_CUSTOM" -> new com.gto.fastcollection.cache.legacy.WeakCustomHashInterner<>(STRATEGY);
            default -> throw new IllegalArgumentException("unknown interner impl: " + name);
        };
        System.out.println("[harness] interner impl = " + interner.getClass().getName());
        return run(seconds, threads, fixedOp, (key, op) -> switch (op) {
            case 0 -> interner.isPresent(key) ? key : null;
            case 1 -> interner.intern(key);
            case 2 -> interner.addIfAbsent(key) ? key : null;
            default -> interner.intern(key);
        });
    }

    private static long runPrimitive(String type, String variant, int seconds, int threads, int fixedOp) throws InterruptedException {
        final boolean legacy = variant.equalsIgnoreCase("LEGACY");
        final boolean weak = type.equalsIgnoreCase("WEAK_INT");
        if (!type.equalsIgnoreCase("INT") && !weak) {
            throw new IllegalArgumentException("only INT and WEAK_INT are wired into the profiler harness: " + type);
        }
        final IntOps ops;
        if (legacy) {
            final com.gto.fastcollection.cache.legacy.primitive.IntCache<String> strong =
                    new com.gto.fastcollection.cache.legacy.primitive.IntCache<>(k -> "c" + k);
            final com.gto.fastcollection.cache.legacy.primitive.WeakValueIntCache<String> weakCache =
                    new com.gto.fastcollection.cache.legacy.primitive.WeakValueIntCache<>(k -> "c" + k);
            ops = weak
                    ? new IntOps() {
                        @Override
                        public String get(int k) {
                            return weakCache.getCache(k);
                        }

                        @Override
                        public String present(int k) {
                            return weakCache.getIfPresent(k);
                        }

                        @Override
                        public String put(int k, String v) {
                            return weakCache.putIfAbsent(k, v);
                        }
                    }
                    : new IntOps() {
                        @Override
                        public String get(int k) {
                            return strong.getCache(k);
                        }

                        @Override
                        public String present(int k) {
                            return strong.getIfPresent(k);
                        }

                        @Override
                        public String put(int k, String v) {
                            return strong.putIfAbsent(k, v);
                        }
                    };
        } else {
            final com.gto.fastcollection.cache.primitive.IntCache<String> strong =
                    new com.gto.fastcollection.cache.primitive.IntCache<>(k -> "c" + k);
            final com.gto.fastcollection.cache.primitive.WeakValueIntCache<String> weakCache =
                    new com.gto.fastcollection.cache.primitive.WeakValueIntCache<>(k -> "c" + k);
            ops = weak
                    ? new IntOps() {
                        @Override
                        public String get(int k) {
                            return weakCache.getCache(k);
                        }

                        @Override
                        public String present(int k) {
                            return weakCache.getIfPresent(k);
                        }

                        @Override
                        public String put(int k, String v) {
                            return weakCache.putIfAbsent(k, v);
                        }
                    }
                    : new IntOps() {
                        @Override
                        public String get(int k) {
                            return strong.getCache(k);
                        }

                        @Override
                        public String present(int k) {
                            return strong.getIfPresent(k);
                        }

                        @Override
                        public String put(int k, String v) {
                            return strong.putIfAbsent(k, v);
                        }
                    };
        }
        System.out.println("[harness] primitive impl = " + type + " " + (legacy ? "LEGACY" : "CURRENT") + (weak ? " (weak)" : ""));
        return run(seconds, threads, fixedOp, (key, op) -> {
            final int intKey = key.hashCode();
            return switch (op) {
                case 0 -> ops.present(intKey);
                case 1 -> ops.get(intKey);
                case 2 -> ops.put(intKey, "v");
                default -> ops.get(intKey);
            };
        });
    }

    /**
     * What the workload needs from the target: the value bound to a key for one
     * of four operation kinds (present, compute, put, recompute).
     */
    @FunctionalInterface
    private interface Step {
        String apply(String key, int op);
    }

    private interface IntOps {
        String get(int k);

        String present(int k);

        String put(int k, String v);
    }

    private static long run(int seconds, int threads, int fixedOp, Step step) throws InterruptedException {
        final String[] keys = new String[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            keys[i] = "key" + i;
        }
        for (String key : keys) {
            step.apply(key, 2);
        }
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        final AtomicLong total = new AtomicLong();
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final Thread worker = new Thread(() -> {
                final ThreadLocalRandom random = ThreadLocalRandom.current();
                long ops = 0;
                long sink = 0;
                while (System.nanoTime() < deadline) {
                    for (int i = 0; i < 512; i++) {
                        final String key = keys[random.nextInt() & (KEY_COUNT - 1)];
                        if (step.apply(key, fixedOp < 0 ? (i & 3) : fixedOp) == null) {
                            sink++;
                        }
                        ops++;
                    }
                }
                if (sink == Long.MIN_VALUE) {
                    System.out.println(sink);
                }
                total.addAndGet(ops);
            }, "spark-workload-" + t);
            workers[t] = worker;
            worker.start();
        }
        System.out.println("[harness] running " + threads + " workload threads for " + seconds + "s");
        for (Thread worker : workers) {
            worker.join();
        }
        return total.get();
    }
}
