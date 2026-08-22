package com.gto.fastcollection.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral contracts of the map wrappers: grouping, the automatic removal
 * of empty structures, and (the former bug) {@code removeAll} keeping the
 * elements that were not asked for.
 */
class MapInterfacesTest {

    private static MultiMap<String, Integer> multiMap() {
        return MultiMap.create(ArrayList::new);
    }

    private static NestedMap<String, String, Integer> nestedMap() {
        return NestedMap.create(HashMap::new);
    }

    private static NestedMultiMap<String, String, Integer> nestedMultiMap() {
        return NestedMultiMap.create(HashMap::new, ArrayList::new);
    }

    @Test
    void multiMapGroupsValuesPerKey() {
        MultiMap<String, Integer> mm = multiMap();
        mm.put("a", 1);
        mm.put("a", 2);
        mm.put("b", 3);

        assertThat(mm.get("a")).containsExactly(1, 2);
        assertThat(mm.get("b")).containsExactly(3);
        assertThat(mm.get("missing")).isEmpty();
        assertThat(mm.isEmpty()).isFalse();
    }

    @Test
    void multiMapRemoveDropsKeyWhenLastValueGone() {
        MultiMap<String, Integer> mm = multiMap();
        mm.put("a", 1);
        mm.put("a", 2);

        assertThat(mm.remove("a", 1)).isTrue();
        assertThat(mm.get("a")).containsExactly(2);
        assertThat(mm.remove("a", 2)).isTrue();
        assertThat(mm.getMap()).doesNotContainKey("a");
    }

    @Test
    void multiMapRemoveAllKeepsUnmentionedValues() {
        MultiMap<String, Integer> mm = multiMap();
        mm.putAll("a", new ArrayList<>(List.of(1, 2, 3)));

        // regression: removeAll must not drop the whole key, only the asked values
        assertThat(mm.removeAll("a", List.of(1, 3))).isTrue();
        assertThat(mm.get("a")).containsExactly(2);

        // removing the rest drops the key
        assertThat(mm.removeAll("a", List.of(2))).isTrue();
        assertThat(mm.getMap()).doesNotContainKey("a");
        assertThat(mm.removeAll("a", List.of(2))).isFalse();
    }

    @Test
    void multiMapCountsLookupsAndIteration() {
        MultiMap<String, Integer> mm = multiMap();
        mm.put("a", 1);
        mm.put("a", 2);
        mm.put("b", 3);

        assertThat(mm.size()).isEqualTo(2);
        assertThat(mm.valueCount()).isEqualTo(3);
        assertThat(mm.containsKey("a")).isTrue();
        assertThat(mm.containsKey("missing")).isFalse();
        assertThat(mm.containsValue("a", 2)).isTrue();
        assertThat(mm.containsValue("a", 3)).isFalse();
        assertThat(mm.containsValue("missing", 1)).isFalse();

        var seen = new ArrayList<String>();
        mm.forEach((k, v) -> seen.add(k + "=" + v));
        assertThat(seen).containsExactlyInAnyOrder("a=1", "a=2", "b=3");
    }

    @Test
    void nestedMapCountsAndIteration() {
        NestedMap<String, String, Integer> nm = nestedMap();
        nm.put("x", "y", 1);
        nm.put("x", "z", 2);
        nm.put("w", "y", 3);

        assertThat(nm.size()).isEqualTo(2);
        assertThat(nm.valueCount()).isEqualTo(3);
        assertThat(nm.containsKey("x")).isTrue();
        assertThat(nm.containsKey("x", "z")).isTrue();
        assertThat(nm.containsKey("x", "missing")).isFalse();
        assertThat(nm.containsKey("missing", "y")).isFalse();

        var seen = new ArrayList<String>();
        nm.forEach((k1, k2, v) -> seen.add(k1 + "/" + k2 + "=" + v));
        assertThat(seen).containsExactlyInAnyOrder("x/y=1", "x/z=2", "w/y=3");
    }

    @Test
    void nestedMultiMapPutAllRemoveAllCountsAndIteration() {
        NestedMultiMap<String, String, Integer> nmm = nestedMultiMap();
        nmm.putAll("x", "y", new ArrayList<>(List.of(1, 2, 3)));
        nmm.put("x", "z", 4);

        assertThat(nmm.size()).isEqualTo(1);
        assertThat(nmm.valueCount()).isEqualTo(4);
        assertThat(nmm.containsKey("x")).isTrue();
        assertThat(nmm.containsKey("x", "y")).isTrue();
        assertThat(nmm.containsKey("x", "missing")).isFalse();
        assertThat(nmm.containsValue("x", "y", 2)).isTrue();
        assertThat(nmm.containsValue("x", "y", 9)).isFalse();

        var seen = new ArrayList<String>();
        nmm.forEach((k1, k2, v) -> seen.add(k1 + "/" + k2 + "=" + v));
        assertThat(seen).containsExactlyInAnyOrder("x/y=1", "x/y=2", "x/y=3", "x/z=4");

        // removeAll keeps the unmentioned values; the pair drops when emptied
        assertThat(nmm.removeAll("x", "y", List.of(1, 3))).isTrue();
        assertThat(nmm.get("x", "y")).containsExactly(2);
        assertThat(nmm.removeAll("x", "y", List.of(2))).isTrue();
        assertThat(nmm.containsKey("x", "y")).isFalse();
        assertThat(nmm.containsKey("x")).isTrue();

        // the first-level key drops with its last pair
        assertThat(nmm.removeAll("x", "z", List.of(4))).isTrue();
        assertThat(nmm.getMap()).doesNotContainKey("x");
        assertThat(nmm.isEmpty()).isTrue();
    }

    @Test
    void nestedMapStoresAndCascadesCleanup() {
        NestedMap<String, String, Integer> nm = nestedMap();
        assertThat(nm.put("x", "y", 1)).isNull();
        assertThat(nm.put("x", "z", 2)).isNull();
        assertThat(nm.get("x", "y")).isEqualTo(1);
        assertThat(nm.get("x", "missing")).isNull();
        assertThat(nm.get("missing", "y")).isNull();

        // removing the last entry of an inner map drops the first-level key
        assertThat(nm.remove("x", "z")).isEqualTo(2);
        assertThat(nm.remove("x", "y")).isEqualTo(1);
        assertThat(nm.getMap()).doesNotContainKey("x");
        assertThat(nm.isEmpty()).isTrue();
    }

    @Test
    void nestedMapComputeIfAbsentRunsOnceAndCaches() {
        NestedMap<String, String, Integer> nm = nestedMap();
        AtomicInteger calls = new AtomicInteger();

        assertThat(nm.computeIfAbsent("x", "y", k -> calls.incrementAndGet())).isEqualTo(1);
        assertThat(nm.computeIfAbsent("x", "y", k -> calls.incrementAndGet())).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void nestedMultiMapGroupsUnderTwoLevels() {
        NestedMultiMap<String, String, Integer> nmm = nestedMultiMap();
        nmm.put("x", "y", 1);
        nmm.put("x", "y", 2);
        nmm.put("x", "z", 3);

        assertThat(nmm.get("x", "y")).containsExactly(1, 2);
        assertThat(nmm.get("x", "z")).containsExactly(3);
        assertThat(nmm.get("x", "missing")).isEmpty();
        assertThat(nmm.get("missing", "y")).isEmpty();
        assertThat(nmm.get("x")).containsOnlyKeys("y", "z");
    }

    @Test
    void nestedMultiMapRemoveCascadesLevelByLevel() {
        NestedMultiMap<String, String, Integer> nmm = nestedMultiMap();
        nmm.put("x", "y", 1);

        // removing the last value drops k2 and then k1
        assertThat(nmm.remove("x", "y", 1)).isTrue();
        assertThat(nmm.getMap()).doesNotContainKey("x");

        nmm.put("x", "y", 1);
        assertThat(nmm.remove("x", "y")).containsExactly(1);
        assertThat(nmm.getMap()).doesNotContainKey("x");
    }
}
