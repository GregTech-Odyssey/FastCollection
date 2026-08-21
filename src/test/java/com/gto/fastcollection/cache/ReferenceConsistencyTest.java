package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The central contract of {@link MapCache} and {@link Interner} is reference
 * consistency: repeated lookups of the same key must return the <em>same
 * instance</em>, never a fresh equal one. These tests assert identity with
 * {@code isSameAs} (the existing tests mostly use value equality), and cover
 * the combinations that must keep the canonical instance: the plain and
 * function-armed {@code getCache}, {@code getIfPresent}, {@code putIfAbsent}
 * (first write wins), {@code getCacheRecursive}, {@code intern},
 * {@code addIfAbsent} (the added instance becomes canonical) and {@code clear}
 * (which intentionally breaks canonicality).
 *
 * <p>Keys are string literals so identity-based implementations behave like
 * value-based ones; create functions return a fresh {@code new String} on every
 * invocation so a repeated instance can only come from the cache, never from
 * the function.
 */
class ReferenceConsistencyTest {

    private static final Hash.Strategy<String> VALUE_STRATEGY = new Hash.Strategy<>() {
        @Override
        public int hashCode(String o) {
            return o.hashCode();
        }

        @Override
        public boolean equals(String a, String b) {
            return a.equals(b);
        }
    };

    static Stream<Arguments> caches() {
        return Stream.of(
                Arguments.of("HashCache",
                        (Supplier<MapCache<String, String>>) HashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) HashCache::new),
                Arguments.of("IdentityHashCache",
                        (Supplier<MapCache<String, String>>) IdentityHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) IdentityHashCache::new),
                Arguments.of("CustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new CustomHashCache<>(VALUE_STRATEGY),
                        (Function<Function<String, String>, MapCache<String, String>>) f -> new CustomHashCache<>(VALUE_STRATEGY, f)),
                Arguments.of("WeakValueHashCache",
                        (Supplier<MapCache<String, String>>) WeakValueHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) WeakValueHashCache::new),
                Arguments.of("WeakValueIdentityHashCache",
                        (Supplier<MapCache<String, String>>) WeakValueIdentityHashCache::new,
                        (Function<Function<String, String>, MapCache<String, String>>) WeakValueIdentityHashCache::new),
                Arguments.of("WeakValueCustomHashCache",
                        (Supplier<MapCache<String, String>>) () -> new WeakValueCustomHashCache<>(VALUE_STRATEGY),
                        (Function<Function<String, String>, MapCache<String, String>>) f -> new WeakValueCustomHashCache<>(VALUE_STRATEGY, f))
        );
    }

    static Stream<Arguments> interners() {
        return Stream.of(
                Arguments.of("HashInterner", (Supplier<Interner<String>>) HashInterner::new),
                Arguments.of("CustomHashInterner", (Supplier<Interner<String>>) () -> new CustomHashInterner<>(VALUE_STRATEGY)),
                Arguments.of("WeakHashInterner", (Supplier<Interner<String>>) WeakHashInterner::new),
                Arguments.of("WeakCustomHashInterner", (Supplier<Interner<String>>) () -> new WeakCustomHashInterner<>(VALUE_STRATEGY))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void getCacheReturnsSameInstance(String name, Supplier<MapCache<String, String>> factory,
                                     Function<Function<String, String>, MapCache<String, String>> withFunction) {
        // the constructor factory returns a fresh object on every call; only the
        // cache can make repeated lookups yield the same instance
        MapCache<String, String> cache = withFunction.apply(k -> new String("v:" + k));

        String first = cache.getCache("k");
        assertThat(first).isEqualTo("v:k");

        assertThat(cache.getCache("k")).isSameAs(first);
        assertThat(cache.getIfPresent("k")).isSameAs(first);
        // the explicit-function variant must not recompute an existing key
        assertThat(cache.getCache("k", k -> new String("other"))).isSameAs(first);
        assertThat(cache.getCacheRecursive("k")).isSameAs(first);
        assertThat(cache.getCacheRecursive("k", k -> new String("other"))).isSameAs(first);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void putIfAbsentKeepsFirstInstance(String name, Supplier<MapCache<String, String>> factory,
                                       Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = factory.get();
        String first = new String("v1");
        String second = new String("v2");

        assertThat(cache.putIfAbsent("k", first)).isSameAs(first);
        // the stored instance is returned, never the rejected one
        assertThat(cache.putIfAbsent("k", second)).isSameAs(first);
        assertThat(cache.getIfPresent("k")).isSameAs(first);
        assertThat(cache.getCache("k", k -> new String("v3"))).isSameAs(first);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caches")
    void clearRecreatesFreshInstance(String name, Supplier<MapCache<String, String>> factory,
                                     Function<Function<String, String>, MapCache<String, String>> withFunction) {
        MapCache<String, String> cache = withFunction.apply(k -> new String("v"));

        String first = cache.getCache("k");
        cache.clear();

        // canonicality is deliberately broken by clear: a new instance is created
        String rebuilt = cache.getCache("k");
        assertThat(rebuilt).isNotSameAs(first);
        assertThat(rebuilt).isEqualTo(first);
        // ... and the rebuilt instance is again the stable one
        assertThat(cache.getCache("k")).isSameAs(rebuilt);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void internCollapsesToSingleCanonicalInstance(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        String canonical = interner.intern(new String("canonical"));
        assertThat(interner.intern(new String("canonical"))).isSameAs(canonical);
        // membership probes must not disturb the canonical instance
        assertThat(interner.isPresent(new String("canonical"))).isTrue();
        assertThat(interner.intern(new String("canonical"))).isSameAs(canonical);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void addIfAbsentBecomesCanonical(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();
        String added = new String("added");

        assertThat(interner.addIfAbsent(added)).isTrue();
        // intern must resolve to the instance stored by addIfAbsent
        assertThat(interner.intern(new String("added"))).isSameAs(added);
        assertThat(interner.addIfAbsent(new String("added"))).isFalse();
        assertThat(interner.intern(new String("added"))).isSameAs(added);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("interners")
    void clearBreaksCanonicality(String name, Supplier<Interner<String>> factory) {
        Interner<String> interner = factory.get();

        String canonical = interner.intern(new String("k"));
        interner.clear();

        String fresh = interner.intern(new String("k"));
        assertThat(fresh).isNotSameAs(canonical);
        // the fresh instance is the new canonical one
        assertThat(interner.intern(new String("k"))).isSameAs(fresh);
    }
}
