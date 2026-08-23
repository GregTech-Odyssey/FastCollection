package com.gto.fastcollection.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link MapCache} backed by a single {@link ConcurrentHashMap}. The simplest
 * implementation: it relies on the map's internal striping for concurrency.
 *
 * <p>{@link #getCache(Object, Function)} uses
 * {@link ConcurrentHashMap#computeIfAbsent}, so the create function runs
 * exactly once per key — but inside the map's lock, which forbids recursive
 * callbacks (a recursive call for the same key throws
 * {@link IllegalStateException}); use {@link #getCacheRecursive} for
 * recursion. {@code getCacheRecursive} runs the function outside the map and
 * merges concurrent computations with {@link ConcurrentHashMap#putIfAbsent},
 * so it is always recursion-safe.
 *
 * <p>The key-remapping overloads apply {@code keyMappingFunction} before the
 * map access, keeping the {@code computeIfAbsent} / {@code putIfAbsent}
 * semantics on the mapped key.
 */
public final class HashCache<K, V> implements MapCache<K, V> {

    private final ConcurrentHashMap<K, V> map;
    private final Function<? super K, ? extends V> createFunction;

    /**
     * Creates a cache with no default create function.
     */
    public HashCache() {
        this(null);
    }

    /**
     * Creates a cache using {@code createFunction} as the default for the
     * no-function {@link #getCache(Object)} / {@link #getCacheRecursive(Object)}
     * methods; {@code null} is allowed and behaves like the no-factory
     * constructor.
     */
    public HashCache(Function<? super K, ? extends V> createFunction) {
        this.map = new ConcurrentHashMap<>();
        this.createFunction = createFunction;
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return createFunction;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#computeIfAbsent}: the function runs exactly
     * once per key and must not call back into this cache nor return {@code null}.
     */
    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        return map.computeIfAbsent(k, createFunction);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link ConcurrentHashMap#computeIfAbsent} on the mapped key: the
     * function runs exactly once per mapped key and must not call back into
     * this cache nor return {@code null}.
     */
    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        V v = map.get(k);
        if (v != null) {
            return v;
        }
        K mapped = keyMappingFunction.apply(k);
        v = createFunction.apply(mapped);
        V prev = map.putIfAbsent(mapped, v);
        return prev == null ? v : prev;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs the function outside the map (after a plain {@link #getIfPresent})
     * so it may recursively call back into this cache. Concurrent computations of
     * the same key are merged with {@link ConcurrentHashMap#putIfAbsent}.
     */
    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction) {
        V v = map.get(k);
        if (v != null) {
            return v;
        }
        // Run outside the map so the function may call back into this cache.
        v = createFunction.apply(k);
        V prev = map.putIfAbsent(k, v);
        return prev == null ? v : prev;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Like {@link #getCacheRecursive(Object, Function)}, but binds the
     * computed value under the mapped key.
     */
    @Override
    public V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction) {
        V v = map.get(k);
        if (v != null) {
            return v;
        }
        K mapped = keyMappingFunction.apply(k);
        v = createFunction.apply(mapped);
        V prev = map.putIfAbsent(mapped, v);
        return prev == null ? v : prev;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V getIfPresent(final K k) {
        return map.get(k);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V putIfAbsent(final K k, final V v) {
        V prev = map.putIfAbsent(k, v);
        return prev == null ? v : prev;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        map.clear();
    }
}