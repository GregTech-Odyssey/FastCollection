package com.gto.fastcollection.map;

import com.gto.fastcollection.map.enums.*;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntFunction;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the ordinal-indexed enum maps: the primitive fast paths, the Map
 * (boxed) bridge, the views and iterators, foreign-enum key handling, and
 * Map-contract interoperability with {@link java.util.EnumMap}.
 */
class EnumMapsTest {

    private enum Color { RED, GREEN, BLUE }

    private enum Size { SMALL, LARGE }

    @Test
    void intMapPrimitiveFastPaths() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);

        assertThat(m.put(Color.RED, 10)).isZero();
        assertThat(m.put(Color.GREEN, 20)).isZero();
        assertThat(m.put(Color.RED, 11)).isEqualTo(10);
        assertThat(m.size()).isEqualTo(2);

        assertThat(m.getInt(Color.RED)).isEqualTo(11);
        assertThat(m.getInt(Color.BLUE)).isZero();
        assertThat(m.getOrDefault(Color.BLUE, -1)).isEqualTo(-1);
        assertThat(m.containsKey(Color.RED)).isTrue();
        assertThat(m.containsKey(Color.BLUE)).isFalse();
        assertThat(m.containsValue(20)).isTrue();
        assertThat(m.containsValue(99)).isFalse();

        assertThat(m.removeInt(Color.RED)).isEqualTo(11);
        assertThat(m.removeInt(Color.RED)).isZero();
        assertThat(m.size()).isEqualTo(1);
        assertThat(m.getInt(Color.RED)).isZero();
    }

    @Test
    void intMapBoxedBridgeAndDefaults() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);

        assertThat(m.get(Color.RED)).isEqualTo(1);
        // boxed get returns null when absent, matching the JDK Map contract
        assertThat(m.get(Color.BLUE)).isNull();
        assertThat(m.getOrDefault(Color.BLUE, Integer.valueOf(7))).isEqualTo(7);
        // boxed put returns null when the key was absent (JDK Map contract)
        assertThat(m.put(Color.GREEN, Integer.valueOf(2))).isNull();
        assertThat(m.remove(Color.GREEN)).isEqualTo(2);
        assertThat(m.remove(Color.GREEN)).isNull();

        // null keys read as absent, never throw on reads
        assertThat(m.get(null)).isNull();
        assertThat(m.getInt(null)).isZero();
        assertThat(m.containsKey(null)).isFalse();
    }

    @Test
    void intMapForeignEnumKeysReadAsAbsent() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);

        assertThat(m.getInt(Size.SMALL)).isZero();
        assertThat(m.containsKey(Size.SMALL)).isFalse();
        assertThat(m.removeInt(Size.LARGE)).isZero();
        assertThat(m.size()).isEqualTo(1);
    }

    @Test
    void intMapViewsAndIterators() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);
        m.put(Color.GREEN, 2);

        assertThat(m.keySet()).containsExactly(Color.RED, Color.GREEN);
        assertThat(m.keySet().contains(Color.BLUE)).isFalse();
        assertThat(m.values()).containsExactly(1, 2);
        assertThat(m.keySet().remove(Color.RED)).isTrue();
        assertThat(m.containsKey(Color.RED)).isFalse();

        assertThat(m.entrySet()).hasSize(1);
        var entry = m.entrySet().iterator().next();
        assertThat(entry.getKey()).isEqualTo(Color.GREEN);
        assertThat(entry.setValue(5)).isEqualTo(2);
        assertThat(m.getInt(Color.GREEN)).isEqualTo(5);
        assertThat(m.entrySet().contains(Map.entry(Color.GREEN, 5))).isTrue();
        assertThat(m.entrySet().remove(Map.entry(Color.GREEN, 5))).isTrue();
        assertThat(m.isEmpty()).isTrue();
    }

    @Test
    void intMapIteratorRemove() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);
        m.put(Color.GREEN, 2);
        m.put(Color.BLUE, 3);

        Iterator<Map.Entry<Color, Integer>> it = m.entrySet().iterator();
        assertThat(it.hasNext()).isTrue();
        it.next();
        it.remove();
        assertThat(m.size()).isEqualTo(2);
        assertThatThrownBy(it::remove).isInstanceOf(IllegalStateException.class);

        var seen = new HashMap<Color, Integer>();
        it.forEachRemaining(e -> seen.put(e.getKey(), e.getValue()));
        assertThat(seen).hasSize(2);

        m.clear();
        assertThat(m.size()).isZero();
        assertThat(m.entrySet().iterator().hasNext()).isFalse();
        assertThatThrownBy(() -> m.entrySet().iterator().next()).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void intMapForEachAndCopy() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);
        m.put(Color.BLUE, 3);

        var seen = new HashMap<Color, Integer>();
        m.forEach(seen::put);
        assertThat(seen).containsOnlyKeys(Color.RED, Color.BLUE);

        Enum2IntMap<Color> copy = new Enum2IntMap<>(m);
        assertThat(copy).isEqualTo(m);
        assertThat(copy.getInt(Color.BLUE)).isEqualTo(3);
    }

    @Test
    void intMapMatchesJdkEnumMapContract() {
        Enum2IntMap<Color> ours = new Enum2IntMap<>(Color.class);
        EnumMap<Color, Integer> jdk = new EnumMap<>(Color.class);
        for (Color c : Color.values()) {
            if (c != Color.BLUE) {
                // +1 so no value is the zero sentinel
                ours.put(c, c.ordinal() * 10 + 1);
                jdk.put(c, c.ordinal() * 10 + 1);
            }
        }

        assertThat(ours).isEqualTo(jdk);
        assertThat(jdk).isEqualTo(ours);
        assertThat(ours.hashCode()).isEqualTo(jdk.hashCode());
    }

    @Test
    void intMapZeroValueMeansAbsent() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);

        // storing the zero sentinel removes the mapping
        assertThat(m.put(Color.RED, 5)).isZero();
        assertThat(m.put(Color.RED, 0)).isEqualTo(5);
        assertThat(m.containsKey(Color.RED)).isFalse();
        assertThat(m).isEmpty();

        assertThat(m.put(Color.GREEN, 3)).isZero();
        assertThat(m.containsValue(0)).isFalse();
        assertThat(m.containsValue(3)).isTrue();
    }

    @Test
    void intMapdefRetValue() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.defaultReturnValue(-1);

        assertThat(m.getInt(Color.RED)).isEqualTo(-1);
        assertThat(m.removeInt(Color.RED)).isEqualTo(-1);
        assertThat(m.put(Color.RED, 7)).isEqualTo(-1);
        // present values are unaffected
        assertThat(m.getInt(Color.RED)).isEqualTo(7);
    }

    @Test
    void intMapFastutilIntegration() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);
        m.put(Color.RED, 1);
        m.put(Color.GREEN, 2);

        Reference2IntMap<Color> fm = m;
        assertThat(fm.getInt(Color.RED)).isEqualTo(1);
        assertThat(fm.getOrDefault(Color.BLUE, -5)).isEqualTo(-5);

        // fast iterator: reused entry, primitive value access
        var fast = new HashMap<Color, Integer>();
        for (var it = ((Reference2IntMap.FastEntrySet<Color>) fm.reference2IntEntrySet()).fastIterator(); it.hasNext(); ) {
            var e = it.next();
            fast.put(e.getKey(), e.getIntValue());
        }
        assertThat(fast).containsOnlyKeys(Color.RED, Color.GREEN);

        // fastForEach: zero per-entry allocation
        var sum = new int[1];
        ((Reference2IntMap.FastEntrySet<Color>) fm.reference2IntEntrySet()).fastForEach(e -> sum[0] += e.getIntValue());
        assertThat(sum[0]).isEqualTo(3);

        // primitive value collection
        IntCollection values = fm.values();
        assertThat(values.contains(1)).isTrue();
        assertThat(values.contains(9)).isFalse();
        var collected = new ArrayList<Integer>();
        for (IntIterator it = values.iterator(); it.hasNext(); ) {
            collected.add(it.nextInt());
        }
        assertThat(collected).containsExactlyInAnyOrder(1, 2);

        assertThat(fm.keySet().iterator().next()).isIn(Color.RED, Color.GREEN);
    }

    @Test
    void booleanByteCharShortMaps() {
        Enum2BooleanMap<Color> bm = new Enum2BooleanMap<>(Color.class);
        assertThat(bm.put(Color.RED, true)).isFalse();
        assertThat(bm.getBoolean(Color.RED)).isTrue();
        assertThat(bm.getBoolean(Color.BLUE)).isFalse();
        assertThat(bm.removeBoolean(Color.RED)).isTrue();
        assertThat(bm.isEmpty()).isTrue();

        Enum2ByteMap<Color> ym = new Enum2ByteMap<>(Color.class);
        assertThat(ym.put(Color.RED, (byte) 7)).isZero();
        assertThat(ym.getByte(Color.RED)).isEqualTo((byte) 7);
        assertThat(ym.getByte(Color.BLUE)).isEqualTo((byte) 0);

        Enum2CharMap<Color> cm = new Enum2CharMap<>(Color.class);
        assertThat(cm.put(Color.GREEN, 'x')).isEqualTo((char) 0);
        assertThat(cm.getChar(Color.GREEN)).isEqualTo('x');
        assertThat(cm.getOrDefault(Color.BLUE, '?')).isEqualTo('?');

        Enum2ShortMap<Color> sm = new Enum2ShortMap<>(Color.class);
        assertThat(sm.put(Color.BLUE, (short) 300)).isZero();
        assertThat(sm.getShort(Color.BLUE)).isEqualTo((short) 300);
        assertThat(sm.containsKey(Color.BLUE)).isTrue();
    }

    @Test
    void floatLongDoubleMaps() {
        Enum2FloatMap<Color> fm = new Enum2FloatMap<>(Color.class);
        assertThat(fm.put(Color.RED, 1.5F)).isZero();
        assertThat(fm.getFloat(Color.RED)).isEqualTo(1.5F);
        assertThat(fm.getFloat(Color.BLUE)).isZero();

        Enum2LongMap<Color> lm = new Enum2LongMap<>(Color.class);
        assertThat(lm.put(Color.GREEN, 1L << 42)).isZero();
        assertThat(lm.getLong(Color.GREEN)).isEqualTo(1L << 42);
        assertThat(lm.removeLong(Color.GREEN)).isEqualTo(1L << 42);
        assertThat(lm).isEmpty();

        Enum2DoubleMap<Color> dm = new Enum2DoubleMap<>(Color.class);
        assertThat(dm.put(Color.BLUE, Math.PI)).isZero();
        assertThat(dm.getDouble(Color.BLUE)).isEqualTo(Math.PI);
        assertThat(dm.containsValue(Math.PI)).isTrue();
    }

    @Test
    void objectMapSupportsNullValues() {
        Enum2ObjectMap<Color, String> m = new Enum2ObjectMap<>(Color.class);

        assertThat(m.put(Color.RED, "a")).isNull();
        assertThat(m.put(Color.GREEN, null)).isNull();
        assertThat(m.size()).isEqualTo(2);

        // a bound null is present, distinct from an empty slot
        assertThat(m.containsKey(Color.GREEN)).isTrue();
        assertThat(m.get(Color.GREEN)).isNull();
        assertThat(m.containsValue(null)).isTrue();
        assertThat(m.getOrDefault(Color.GREEN, "default")).isNull();
        assertThat(m.getOrDefault(Color.BLUE, "default")).isEqualTo("default");

        assertThat(m.remove(Color.GREEN)).isNull();
        assertThat(m.containsKey(Color.GREEN)).isFalse();
        assertThat(m.size()).isEqualTo(1);

        assertThat(m.put(Color.RED, null)).isEqualTo("a");
        assertThat(m.containsKey(Color.RED)).isTrue();
        assertThat(m.values()).singleElement().isNull();

        var copy = new Enum2ObjectMap<>(m);
        assertThat(copy).isEqualTo(m);
        assertThat(copy.containsKey(Color.RED)).isTrue();
    }
    @Test
    void intMapOptimizedDefaults() {
        Enum2IntMap<Color> m = new Enum2IntMap<>(Color.class);

        // addTo accumulates; a zero sum removes (sentinel)
        assertThat(m.addTo(Color.RED, 5)).isZero();
        assertThat(m.addTo(Color.RED, 3)).isEqualTo(5);
        assertThat(m.getInt(Color.RED)).isEqualTo(8);
        assertThat(m.addTo(Color.RED, -8)).isEqualTo(8);
        assertThat(m.containsKey(Color.RED)).isFalse();

        assertThat(m.putIfAbsent(Color.GREEN, 2)).isZero();
        assertThat(m.putIfAbsent(Color.GREEN, 9)).isEqualTo(2);
        assertThat(m.remove(Color.GREEN, 9)).isFalse();
        assertThat(m.remove(Color.GREEN, 2)).isTrue();
        assertThat(m.putIfAbsent(Color.GREEN, 4)).isZero();
        assertThat(m.replace(Color.GREEN, 4, 6)).isTrue();
        assertThat(m.replace(Color.GREEN, 9, 1)).isFalse();
        assertThat(m.replace(Color.GREEN, 7)).isEqualTo(6);

        AtomicInteger calls = new AtomicInteger();
        assertThat(m.computeIfAbsent(Color.BLUE, k -> calls.incrementAndGet() * 10)).isEqualTo(10);
        assertThat(m.computeIfAbsent(Color.BLUE, k -> 99)).isEqualTo(10);
        assertThat(calls.get()).isEqualTo(1);

        java.util.function.IntBinaryOperator sum = Integer::sum;
        assertThat(m.mergeInt(Color.BLUE, 5, sum)).isEqualTo(15);
        assertThat(m.mergeInt(Color.BLUE, -15, sum)).isZero();
        assertThat(m.containsKey(Color.BLUE)).isFalse();

        assertThat(m.computeIfAbsent(Color.RED, (Reference2IntFunction<Color>) k -> 42)).isEqualTo(42);
        assertThat(m.computeIntIfPresent(Color.RED, (k, v) -> v * 2)).isEqualTo(84);
        // GREEN holds 7 at this point; BLUE is empty (the merge above removed it)
        assertThat(m.computeInt(Color.GREEN, (k, v) -> v + 1)).isEqualTo(8);
        assertThat(m.computeInt(Color.BLUE, (k, v) -> v == null ? 1 : v + 1)).isEqualTo(1);
    }

    @Test
    void objectMapOptimizedDefaults() {
        Enum2ObjectMap<Color, String> m = new Enum2ObjectMap<>(Color.class);

        assertThat(m.putIfAbsent(Color.RED, "a")).isNull();
        assertThat(m.putIfAbsent(Color.RED, "b")).isEqualTo("a");
        assertThat(m.remove(Color.RED, "x")).isFalse();
        assertThat(m.replace(Color.RED, "a", "c")).isTrue();
        assertThat(m.replace(Color.RED, "d")).isEqualTo("c");

        assertThat(m.computeIfAbsent(Color.GREEN, k -> "g:" + k)).isEqualTo("g:GREEN");
        assertThat(m.computeIfAbsent(Color.GREEN, k -> "other")).isEqualTo("g:GREEN");
        assertThat(m.computeIfPresent(Color.GREEN, (k, v) -> v + "!")).isEqualTo("g:GREEN!");
        assertThat(m.merge(Color.BLUE, "x", (a, b) -> a + b)).isEqualTo("x");
        assertThat(m.merge(Color.BLUE, "y", (a, b) -> a + b)).isEqualTo("xy");
    }
}
