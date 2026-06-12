import com.gto.fastcollection.O2OOpenCacheHashMap;
import com.gto.fastcollection.OpenCacheHashSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Test {

    public static void main(String[] args) {
        AtomicInteger c = new AtomicInteger();
        Runnable map = ()-> {
            try {
                c.getAndIncrement();
                StringBuilder stringBuilder = new StringBuilder();
                if (c.get() > 1000) stringBuilder.append("HashMap:");
                long start = System.currentTimeMillis();
                Map<String, String> m1 = new HashMap<>();
                for (int i = 0; i < 100000; i++) {
                    m1.put(of(i), of(i));
                }
                m1.forEach((k, v) -> {
                    m1.get(v);
                });
                m1.forEach((k, v) -> {
                    m1.get(v+"a");
                });
                if (c.get() > 1000)  stringBuilder.append(System.currentTimeMillis() - start);
                Thread.sleep(10);
                if (c.get() > 1000)  stringBuilder.append(", ").append("Object2ObjectOpenHashMap:");
                Map<String, String> m2 = new Object2ObjectOpenHashMap<>();
                start = System.currentTimeMillis();
                for (int i = 0; i < 100000; i++) {
                    m2.put(of(i), of(i));
                }
                m2.forEach((k, v) -> {
                    m2.get(v);
                });
                m2.forEach((k, v) -> {
                    m2.get(v+"a");
                });
                if (c.get() > 1000) stringBuilder.append(System.currentTimeMillis() - start);
                Thread.sleep(10);
                if (c.get() > 1000) stringBuilder.append(", ").append("O2OOpenCacheHashMap:");
                Map<String, String> m3 = new O2OOpenCacheHashMap<>();
                start = System.currentTimeMillis();
                for (int i = 0; i < 100000; i++) {
                    m3.put(of(i), of(i));
                }
                m3.forEach((k, v) -> {
                    m3.get(v);
                });
                m3.forEach((k, v) -> {
                    m3.get(v+"a");
                });
                if (c.get() > 1000)  stringBuilder.append(System.currentTimeMillis() - start).append("\n");
               if (!stringBuilder.isEmpty()) System.out.println(stringBuilder);
            } catch (Exception e) {}
        };
        Runnable set = ()-> {
            try {
                c.getAndIncrement();
                StringBuilder stringBuilder = new StringBuilder();
                if (c.get() > 1000) stringBuilder.append("HashSet:");
                long start = System.currentTimeMillis();
                Set<String> s1 = new HashSet<>();
                for (int i = 0; i < 100000; i++) {
                    s1.add(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s1.contains(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s1.contains(of(i)+"a");
                }
                if (c.get() > 1000)  stringBuilder.append(System.currentTimeMillis() - start);
                Thread.sleep(10);
                if (c.get() > 1000)  stringBuilder.append(", ").append("ObjectOpenHashSet:");
                Set<String> s2 = new ObjectOpenHashSet<>();
                start = System.currentTimeMillis();
                for (int i = 0; i < 100000; i++) {
                    s2.add(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s2.contains(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s2.contains(of(i)+"a");
                }
                if (c.get() > 1000) stringBuilder.append(System.currentTimeMillis() - start);
                Thread.sleep(10);
                if (c.get() > 1000) stringBuilder.append(", ").append("OpenCacheHashSet:");
                Set<String> s3 = new OpenCacheHashSet<>();
                start = System.currentTimeMillis();
                for (int i = 0; i < 100000; i++) {
                    s3.add(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s3.contains(of(i));
                }
                for (int i = 0; i < 100000; i++) {
                    s3.contains(of(i)+"a");
                }
                if (c.get() > 1000)  stringBuilder.append(System.currentTimeMillis() - start).append("\n");
                if (!stringBuilder.isEmpty())System.out.println(stringBuilder);
            } catch (Exception e) {}
        };

        IntList list = new IntArrayList(1000);
        for (int i = 0; i < 1000; i++) {
            list.add(i);
        }
        list.intStream().parallel().forEach(i -> {
            map.run();
        });
        for (int i = 0; i < 5; i++) {
            map.run();
        }
        c.set(0);
        list.intStream().parallel().forEach(i -> {
            set.run();
        });
        for (int i = 0; i < 5; i++) {
            set.run();
        }
    }

    private static String of(int i) {
        return i + ".abc";
    }
}
