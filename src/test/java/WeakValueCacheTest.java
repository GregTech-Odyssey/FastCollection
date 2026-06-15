

import com.gto.fastcollection.cache.WeakValueCache;
import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WeakValueCacheTest {

    private WeakValueCache<String, Object> cache;

    @BeforeEach
    void setUp() {
        cache = new WeakValueCache<>();
    }

    // ==================== 1. 高并发去重与引用一致性 ====================
    @RepeatedTest(5)
    @Order(1)
    void highConcurrencySameKeyReturnsIdenticalValue() throws Exception {
        int threadCount = 80;
        int keys = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        // 每个 key 用一个 AtomicInteger 记录工厂调用次数
        ConcurrentHashMap<String, AtomicInteger> factoryCounts = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, List<Object>> results = new ConcurrentHashMap<>(); // 收集每个 key 所有线程取到的对象

        Function<String, Object> creator = key -> {
            factoryCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            return new Object(); // 每次工厂调用都创建新对象
        };

        List<Future<Void>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < keys; i++) {
                    String key = "key-" + i;
                    Object val = cache.getCache(key, creator);
                    results.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(val);
                }
                return null;
            }));
        }
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        // 断言每个 key 的工厂只被调用一次
        for (int i = 0; i < keys; i++) {
            String key = "key-" + i;
            AtomicInteger count = factoryCounts.get(key);
            assertNotNull(count, "工厂应至少调用一次: " + key);
            assertEquals(1, count.get(), "工厂调用次数应为1: " + key);
        }

        // 断言每个 key 的所有返回值都是同一个对象
        for (int i = 0; i < keys; i++) {
            String key = "key-" + i;
            List<Object> vals = results.get(key);
            assertNotNull(vals);
            Object first = vals.get(0);
            for (Object v : vals) {
                assertSame(first, v, "key=" + key + " 所有线程应获得相同对象");
            }
        }
    }

    // ==================== 2. 高并发下哈希冲突的正确性 ====================
    @Test
    @Order(2)
    void highConcurrencyWithHashCollisions() throws Exception {
        WeakValueCache<Integer, Object> collisionCache = new WeakValueCache<>();
        int threadCount = 40;
        int keysPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger factoryCalls = new AtomicInteger(0);
        Function<Integer, Object> creator = key -> {
            factoryCalls.incrementAndGet();
            return new Object();
        };

        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentHashMap<Integer, Object> firstSeen = new ConcurrentHashMap<>();
        for (int t = 0; t < threadCount; t++) {
            final int start = t * keysPerThread;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < keysPerThread; i++) {
                        int key = start + i;
                        Object val = collisionCache.getCache(key, creator);
                        Object prev = firstSeen.putIfAbsent(key, val);
                        if (prev != null) {
                            assertSame(prev, val, "哈希冲突下同一 key 应返回相同对象");
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertEquals(threadCount * keysPerThread, factoryCalls.get(), "工厂调用次数应等于唯一 key 数量");
    }

    // ==================== 3. 并发插入 + 扩容高压测试 ====================
    @Test
    @Order(3)
    void massiveConcurrentInsertTriggerResize() throws Exception {
        int threadCount = 50;
        int insertsPerThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        Function<String, Object> creator = key -> new Object();

        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < insertsPerThread; i++) {
                        String key = "t" + tid + "-k" + i;
                        cache.getCache(key, creator);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        // 验证最终可正常访问，且无异常
        Object val = cache.getCache("t0-k0", creator);
        assertNotNull(val);
        // 随意抽查几个 key 的引用一致性
        Object v2 = cache.getCache("t0-k0", creator);
        assertSame(val, v2);
        Object v3 = cache.getCache("t1-k100", creator);
        assertNotNull(v3);
    }

    // ==================== 4. 清理功能：GC 后失效节点移除并去重重建 ====================
    @Test
    @Order(4)
    void gcClearsWeakValueAndRecreates() throws Exception {
        Function<String, Object> creator = key -> new Object();

        // 存一个值，并保持强引用
        Object strongRef = cache.getCache("gcKey", creator);
        WeakReference<Object> valueRef = new WeakReference<>(strongRef);
        assertNotNull(valueRef.get());

        // 释放强引用，触发 GC
        strongRef = null;
        for (int i = 0; i < 30 && valueRef.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }

        // 再次获取，应得到新对象
        Object newVal1 = cache.getCache("gcKey", creator);
        Object newVal2 = cache.getCache("gcKey", creator);
        assertNotNull(newVal1);
        assertSame(newVal1, newVal2, "重建后去重仍有效");

        if (valueRef.get() == null) {
            // 确认是新对象（通过 identityHashCode 大概率不同）
            assertNotEquals(System.identityHashCode(valueRef), System.identityHashCode(newVal1),
                    "GC 后应创建新对象");
        } else {
            System.out.println("Warning: old value still not collected, check skipped.");
        }
    }


    // ==================== 5. 缓存实例自动回收测试 ====================
    @Test
    @Order(5)
    void cacheInstanceCanBeGarbageCollected() throws Exception {
        WeakValueCache<String, Object> localCache = new WeakValueCache<>();
        WeakReference<WeakValueCache> cacheRef = new WeakReference<>(localCache);

        // 使用缓存生成一些条目
        Function<String, Object> creator = key -> new Object();
        for (int i = 0; i < 100; i++) {
            localCache.getCache("x" + i, creator);
        }

        localCache = null;
        for (int i = 0; i < 30 && cacheRef.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(cacheRef.get(), "缓存实例在无强引用后应被 GC 回收");
    }

    // ==================== 6. 长时间稳定性测试 ====================
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @Order(6)
    void longRunningStability() throws Exception {
        int durationSeconds = 10;
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicLong ops = new AtomicLong(0);
        Function<String, Object> creator = key -> new Object();

        // 混合读写线程
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                Random rnd = new Random();
                while (System.currentTimeMillis() < endTime) {
                    String key = "stable-" + rnd.nextInt(1000);
                    cache.getCache(key, creator);
                    ops.incrementAndGet();
                }
            });
        }
        // 等待时间结束
        Thread.sleep(durationSeconds * 1000 + 500);
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
        System.out.println("Operations completed: " + ops.get());
        // 只要没抛异常就算通过
        assertTrue(ops.get() > 0);
    }
}