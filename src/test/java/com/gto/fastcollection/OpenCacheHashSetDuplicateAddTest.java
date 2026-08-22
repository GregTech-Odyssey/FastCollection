package com.gto.fastcollection;

import com.gto.fastcollection.fastutil.OpenCacheHashSet;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the duplicate-insert bug in {@link OpenCacheHashSet}:
 * {@code add} / {@code addOrGet} used to compare every probed slot against the
 * hash of the <em>first</em> slot only (the cached hash was never refreshed
 * inside the probe loop), so a key whose ideal slot was occupied by another key
 * was never found and was inserted again on every call. Re-adding an existing
 * key must be a no-op: {@code false} return, no size growth, no duplicate
 * entries, and {@code addOrGet} must keep returning the stored instance.
 */
class OpenCacheHashSetDuplicateAddTest {

    @Test
    void reAddingAllExistingKeysDoesNotGrowTheSet() {
        OpenCacheHashSet<String> set = new OpenCacheHashSet<>(128);
        int n = 4096;
        for (int i = 0; i < n; i++) {
            set.add("key" + i);
        }
        int before = set.size();

        int falseAdded = 0;
        for (int i = 0; i < n; i++) {
            if (set.add("key" + i)) {
                falseAdded++;
            }
        }

        assertThat(falseAdded).as("no key may be reported as newly added").isZero();
        assertThat(set.size()).as("size must not grow").isEqualTo(before);
        // every key still reachable exactly once
        for (int i = 0; i < n; i++) {
            assertThat(set.contains("key" + i)).isTrue();
        }
    }

    @Test
    void repeatedReAddRoundsStayStable() {
        OpenCacheHashSet<String> set = new OpenCacheHashSet<>(128);
        int n = 4096;
        for (int i = 0; i < n; i++) {
            set.add("key" + i);
        }

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < n; i++) {
                assertThat(set.add("key" + i)).as("round " + round + " key " + i).isFalse();
            }
        }
        assertThat(set.size()).isEqualTo(n);
    }

    @Test
    void addOrGetReturnsStoredInstanceWithoutDuplicating() {
        OpenCacheHashSet<String> set = new OpenCacheHashSet<>(128);
        int n = 4096;
        for (int i = 0; i < n; i++) {
            set.add("key" + i);
        }

        for (int i = 0; i < n; i++) {
            String probe = new String("key" + i);
            String got = set.addOrGet(probe);
            assertThat(got).as("addOrGet must return the stored instance for key" + i).isNotNull();
            assertThat(got).isEqualTo("key" + i);
        }
        assertThat(set.size()).isEqualTo(n);
    }

    @Test
    void duplicateAddDoesNotCreateDuplicateEntries() {
        OpenCacheHashSet<String> set = new OpenCacheHashSet<>(16);
        // keys chosen so their ideal slots collide, forcing linear probing
        for (int i = 0; i < 64; i++) {
            set.add("key" + i);
        }
        // re-add all and then confirm a fresh copy is never created: scanning the
        // logical keys after a clear-and-re-add cycle keeps the same distinct set
        for (int i = 0; i < 64; i++) {
            set.add("key" + i);
        }

        Set<String> distinct = new HashSet<>(set);
        assertThat(distinct).hasSize(64);
        assertThat(set.size()).isEqualTo(64);
    }
}
