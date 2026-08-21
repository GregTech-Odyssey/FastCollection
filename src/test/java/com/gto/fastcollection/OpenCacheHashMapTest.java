package com.gto.fastcollection;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for the hash-cache OpenHashMap variants. Every variant is
 * exercised through its type-specific API (put/get/remove/addTo/compute/merge),
 * iteration over large tables (exercising rehash) and the Custom strategy
 * variants.
 */
class OpenCacheHashMapTest {

    private static final Hash.Strategy<String> CASE_INSENSITIVE = new Hash.Strategy<>() {
        @Override
        public int hashCode(String o) {
            return o.toLowerCase().hashCode();
        }

        @Override
        public boolean equals(String a, String b) {
            return a.equalsIgnoreCase(b);
        }
    };

    @Test
    void booleanMap() {
        O2ZOpenCacheHashMap<String> m = new O2ZOpenCacheHashMap<>();
        assertThat(m.put("a", true)).isFalse(); // default return value
        assertThat(m.put("a", false)).isTrue(); // previous value
        assertThat(m.getBoolean("a")).isFalse();
        assertThat(m.getBoolean("missing")).isFalse();
        assertThat(m.getOrDefault("missing", true)).isTrue();
        assertThat(m.computeIfAbsent("x", (Predicate<String>) s -> s.length() > 0)).isTrue();
        assertThat(m.getBoolean("x")).isTrue();
        assertThat(m.containsKey("zz")).isFalse();
        assertThat(m.removeBoolean("a")).isFalse();
        assertThat(m.size()).isEqualTo(1);
        assertThat(m.merge("x", true, (a, b) -> a || b)).isTrue();
        assertThat(m.getBoolean("x")).isTrue();
    }

    @Test
    void byteMap() {
        O2BOpenCacheHashMap<String> m = new O2BOpenCacheHashMap<>();
        assertThat(m.put("a", (byte) 5)).isEqualTo((byte) 0);
        assertThat(m.getByte("a")).isEqualTo((byte) 5);
        assertThat(m.addTo("a", (byte) 3)).isEqualTo((byte) 5);
        assertThat(m.getByte("a")).isEqualTo((byte) 8);
        assertThat(m.computeIfAbsent("b", s -> (byte) 2)).isEqualTo((byte) 2);
        assertThat(m.mergeByte("b", (byte) 1, (x, y) -> (byte) (x + y))).isEqualTo((byte) 3);
        assertThat(m.removeByte("a")).isEqualTo((byte) 8);
        assertThat(m.isEmpty()).isFalse();
        m.clear();
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void shortMap() {
        O2SOpenCacheHashMap<String> m = new O2SOpenCacheHashMap<>();
        assertThat(m.put("k", (short) 7)).isEqualTo((short) 0);
        assertThat(m.getShort("k")).isEqualTo((short) 7);
        assertThat(m.addTo("k", (short) 5)).isEqualTo((short) 7);
        assertThat(m.getShort("k")).isEqualTo((short) 12);
        assertThat(m.removeShort("k")).isEqualTo((short) 12);
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void doubleMap() {
        O2DOpenCacheHashMap<String> m = new O2DOpenCacheHashMap<>();
        assertThat(m.put("d", 1.5)).isEqualTo(0.0);
        assertThat(m.getDouble("d")).isEqualTo(1.5);
        assertThat(m.addTo("d", 2.5)).isEqualTo(1.5);
        assertThat(m.getDouble("d")).isEqualTo(4.0);
        assertThat(m.computeIfAbsent("e", s -> 1.0)).isEqualTo(1.0);
        assertThat(m.mergeDouble("e", 2.0, Double::sum)).isEqualTo(3.0);
        assertThat(m.removeDouble("d")).isEqualTo(4.0);
        assertThat(m.isEmpty()).isFalse();
        m.clear();
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void floatMap() {
        O2FOpenCacheHashMap<String> m = new O2FOpenCacheHashMap<>();
        assertThat(m.put("f", 1.5f)).isEqualTo(0.0f);
        assertThat(m.getFloat("f")).isEqualTo(1.5f);
        assertThat(m.addTo("f", 2.5f)).isEqualTo(1.5f);
        assertThat(m.getFloat("f")).isEqualTo(4.0f);
        assertThat(m.computeIfAbsent("g", (java.util.function.ToDoubleFunction<String>) s -> 1.0)).isEqualTo(1.0f);
        assertThat(m.mergeFloat("g", 2.0f, (a, b) -> a + b)).isEqualTo(3.0f);
        assertThat(m.removeFloat("f")).isEqualTo(4.0f);
        assertThat(m.isEmpty()).isFalse();
        m.clear();
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void charMap() {
        O2COpenCacheHashMap<String> m = new O2COpenCacheHashMap<>();
        assertThat(m.put("c", 'a')).isEqualTo((char) 0);
        assertThat(m.getChar("c")).isEqualTo('a');
        assertThat(m.addTo("c", (char) 1)).isEqualTo('a');
        assertThat(m.getChar("c")).isEqualTo('b');
        assertThat(m.computeIfAbsent("h", s -> 'z')).isEqualTo('z');
        assertThat(m.removeChar("c")).isEqualTo('b');
        assertThat(m.isEmpty()).isFalse();
        m.clear();
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void largeTableRehashAndIteration() {
        O2DOpenCacheHashMap<String> m = new O2DOpenCacheHashMap<>();
        for (int i = 0; i < 5000; i++) {
            m.put("key" + i, i * 1.5);
        }
        assertThat(m.size()).isEqualTo(5000);
        assertThat(m.getDouble("key4999")).isEqualTo(4999 * 1.5);

        int entries = 0;
        double sum = 0;
        for (var e : m.object2DoubleEntrySet()) {
            entries++;
            sum += e.getDoubleValue();
        }
        assertThat(entries).isEqualTo(5000);
        assertThat(sum).isEqualTo(5000.0 * 4999 * 1.5 / 2);

        // iterator removal
        var it = m.object2DoubleEntrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            it.next();
            it.remove();
            removed++;
        }
        assertThat(removed).isEqualTo(5000);
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void cloneIsIndependent() {
        O2ZOpenCacheHashMap<String> original = new O2ZOpenCacheHashMap<>();
        original.put("a", true);
        original.put("b", false);

        O2ZOpenCacheHashMap<String> copy = original.clone();
        assertThat(copy.size()).isEqualTo(original.size());
        assertThat(copy.getBoolean("a")).isTrue();
        assertThat(copy.hashCode()).isEqualTo(original.hashCode());

        copy.put("a", false);
        assertThat(original.getBoolean("a")).isTrue();
    }

    @Test
    void customStrategyVariants() {
        O2ZOpenCustomCacheHashMap<String> z = new O2ZOpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        z.put("AbC", true);
        assertThat(z.getBoolean("aBc")).isTrue();
        assertThat(z.containsKey("ABC")).isTrue();
        assertThat(z.removeBoolean("abc")).isTrue();
        assertThat(z.isEmpty()).isTrue();

        O2BOpenCustomCacheHashMap<String> b = new O2BOpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        b.put("Key", (byte) 5);
        assertThat(b.getByte("KEY")).isEqualTo((byte) 5);
        assertThat(b.removeByte("key")).isEqualTo((byte) 5);

        O2SOpenCustomCacheHashMap<String> s = new O2SOpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        s.put("Key", (short) 1);
        assertThat(s.getShort("kEY")).isEqualTo((short) 1);

        O2DOpenCustomCacheHashMap<String> d = new O2DOpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        d.put("Key", 2.5);
        assertThat(d.getDouble("kEy")).isEqualTo(2.5);
        assertThat(d.removeDouble("KEY")).isEqualTo(2.5);

        O2FOpenCustomCacheHashMap<String> f = new O2FOpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        f.put("Key", 3.5f);
        assertThat(f.getFloat("KEY")).isEqualTo(3.5f);

        O2COpenCustomCacheHashMap<String> c = new O2COpenCustomCacheHashMap<>(CASE_INSENSITIVE);
        c.put("Key", 'q');
        assertThat(c.getChar("key")).isEqualTo('q');
    }

    @Test
    void mapConstructorCopiesEntries() {
        java.util.Map<String, Byte> src = new java.util.HashMap<>();
        src.put("a", (byte) 1);
        src.put("b", (byte) 2);
        O2BOpenCacheHashMap<String> m = new O2BOpenCacheHashMap<>(src);
        assertThat(m.size()).isEqualTo(2);
        assertThat(m.getByte("a")).isEqualTo((byte) 1);
        assertThat(m.getByte("b")).isEqualTo((byte) 2);
    }
}
