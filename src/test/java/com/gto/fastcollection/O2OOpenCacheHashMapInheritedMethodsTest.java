package com.gto.fastcollection;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link O2OOpenCacheHashMap} stays consistent when inherited
 * (non-overridden) fastutil methods are used. These methods must route through
 * the virtual {@code put} / {@code rehash} (which maintain the cached
 * {@code hash[]} array) and must not leave the array misaligned with
 * {@code key[]}/{@code value[]} — otherwise lookups would silently miss, the
 * same class of bug as the duplicate-insert issue in {@link OpenCacheHashSet}.
 */
class O2OOpenCacheHashMapInheritedMethodsTest {

    @Test
    void putAllKeepsLookupsConsistent() {
        O2OOpenCacheHashMap<String, String> map = new O2OOpenCacheHashMap<>(8);
        Map<String, String> batch = new HashMap<>();
        for (int i = 0; i < 1024; i++) {
            batch.put("key" + i, "v" + i);
        }

        map.putAll(batch);

        for (int i = 0; i < 1024; i++) {
            assertThat(map.get("key" + i)).as("get after putAll key" + i).isEqualTo("v" + i);
        }
        assertThat(map.size()).isEqualTo(1024);
    }

    @Test
    void trimKeepsLookupsConsistent() {
        O2OOpenCacheHashMap<String, String> map = new O2OOpenCacheHashMap<>(1024);
        for (int i = 0; i < 1024; i++) {
            map.put("key" + i, "v" + i);
        }

        map.trim(); // shrink the table via the inherited method

        for (int i = 0; i < 1024; i++) {
            assertThat(map.get("key" + i)).as("get after trim key" + i).isEqualTo("v" + i);
        }
        assertThat(map.containsKey("key500")).isTrue();
    }

    @Test
    void ensureCapacityThenPutKeepsLookupsConsistent() {
        O2OOpenCacheHashMap<String, String> map = new O2OOpenCacheHashMap<>(4);

        map.ensureCapacity(2048); // pre-grow via the inherited method

        for (int i = 0; i < 1024; i++) {
            map.put("key" + i, "v" + i);
        }
        for (int i = 0; i < 1024; i++) {
            assertThat(map.get("key" + i)).as("get after ensureCapacity key" + i).isEqualTo("v" + i);
        }
    }

    @Test
    void inheritedRemoveEntryViaRemoveKeyStaysConsistent() {
        O2OOpenCacheHashMap<String, String> map = new O2OOpenCacheHashMap<>(16);
        for (int i = 0; i < 256; i++) {
            map.put("key" + i, "v" + i);
        }

        for (int i = 0; i < 256; i += 2) {
            map.remove("key" + i);
        }

        for (int i = 0; i < 256; i++) {
            if (i % 2 == 0) {
                assertThat(map.get("key" + i)).as("removed key" + i).isNull();
            } else {
                assertThat(map.get("key" + i)).as("kept key" + i).isEqualTo("v" + i);
            }
        }
    }
}
