package com.gto.fastcollection;

import com.gto.fastcollection.fastutil.OpenCacheHashSet;
import com.gto.fastcollection.fastutil.OpenCustomCacheHashSet;
import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness of {@link OpenCustomCacheHashSet}, the strategy-based counterpart
 * of {@link OpenCacheHashSet}. Covers value grouping by the strategy, canonical
 * instances via {@code addOrGet}, idempotent {@code add} (the duplicate-insert
 * regression), removal, clear and rehash consistency.
 */
class OpenCustomCacheHashSetTest {

    /** Case-insensitive strategy: "Abc" and "ABC" are the same key. */
    private static final Hash.Strategy<String> CI_STRATEGY = new Hash.Strategy<>() {
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
    void strategyGroupsEqualKeys() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(CI_STRATEGY);

        assertThat(set.add("Abc")).isTrue();
        // equal under the strategy -> not added
        assertThat(set.add("ABC")).isFalse();
        assertThat(set.contains("aBc")).isTrue();
        assertThat(set.size()).isEqualTo(1);
    }

    @Test
    void addOrGetReturnsCanonicalInstance() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(CI_STRATEGY);

        String first = new String("HeLLo");
        assertThat(set.addOrGet(first)).isSameAs(first);
        // an equal-but-distinct object resolves to the stored instance
        String second = new String("hello");
        assertThat(set.addOrGet(second)).isSameAs(first);
        assertThat(set.size()).isEqualTo(1);
    }

    @Test
    void reAddingAllExistingKeysDoesNotGrowTheSet() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(128, CI_STRATEGY);
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
        for (int i = 0; i < n; i++) {
            assertThat(set.contains("key" + i)).isTrue();
        }
    }

    @Test
    void removeOnlyAffectsMatchingKey() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(CI_STRATEGY);
        set.add("Foo");
        set.add("Bar");

        assertThat(set.remove("FOO")).isTrue(); // matches "Foo" by strategy
        assertThat(set.contains("foo")).isFalse();
        assertThat(set.contains("Bar")).isTrue();
        assertThat(set.size()).isEqualTo(1);
        assertThat(set.remove("nope")).isFalse();
    }

    @Test
    void clearThenReuseWorks() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(CI_STRATEGY);
        set.add("Alpha");
        set.clear();

        assertThat(set.isEmpty()).isTrue();
        String fresh = new String("ALPHA");
        assertThat(set.addOrGet(fresh)).isSameAs(fresh);
        assertThat(set.size()).isEqualTo(1);
    }

    @Test
    void survivesGrowthAndShrinkRehash() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(16, CI_STRATEGY);
        int n = 4096;
        // growth rehashes
        for (int i = 0; i < n; i++) {
            set.add("key" + i);
        }
        // shrink rehashes on removal
        for (int i = 0; i < n; i += 2) {
            set.remove("key" + i);
        }

        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                assertThat(set.contains("key" + i)).as("removed key" + i).isFalse();
            } else {
                assertThat(set.contains("key" + i)).as("kept key" + i).isTrue();
            }
        }
        assertThat(set.size()).isEqualTo(n / 2);
    }

    @Test
    void iterationYieldsAllDistinctKeys() {
        OpenCustomCacheHashSet<String> set = new OpenCustomCacheHashSet<>(CI_STRATEGY);
        for (int i = 0; i < 256; i++) {
            set.add("Key" + i);
        }
        Set<String> distinct = new HashSet<>();
        set.forEach(distinct::add);
        assertThat(distinct).hasSize(256);
        assertThat(set).hasSize(256);
    }
}
