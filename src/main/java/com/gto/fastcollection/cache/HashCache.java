package com.gto.fastcollection.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A {@link MapCache} backed by a single {@link ConcurrentHashMap}. The simplest
 * implementation: it relies on the map's internal striping for concurrency and
 * uses {@link ConcurrentHashMap#computeIfAbsent} so the create function is
 * guaranteed to run exactly once per key.
 *
 * <p>Because {@code computeIfAbsent} runs the function inside the map's lock,
 * the function must not call back into this cache ({@link #getCacheRecursive}
 * supports that instead), and the function must not return {@code null}
 * ({@code computeIfAbsent} throws {@link NullPointerException}).
 */
public final class HashCache<K, V> implements MapCache<K, V> {

    private final ConcurrentHashMap<K, V> map;
    private final Function<? super K, ? extends V> createFunction;

    /**
     * Creates a cache with no factory; the no-argument
     * {@link #getCache(Object)} / {@link #getCacheRecursive(Object)} methods will
     * throw {@link NullPointerException} unless a factory is supplied later.
     */
    public HashCache() {
        this(null);
    }

    /**
     * Creates a cache using {@code createFunction} for the no-argument
     * {@link #getCache(Object)} / {@link #getCacheRecursive(Object)} methods;
     * {@code null} is allowed and behaves like the no-factory constructor.
     */
    public HashCache(Function<? super K, ? extends V> createFunction) {
        this.map = new ConcurrentHashMap<>();
        this.createFunction = createFunction;
    }

    @Override
    public Function<? super K, ? extends V> createFunction() {
        return this.createFunction;
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

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCacheRecursive(final K k) {
        return getCacheRecursive(k, this.createFunction);
    }

    /** {@inheritDoc} Uses the constructor factory. */
    @Override
    public V getCache(final K k) {
        return map.computeIfAbsent(k, createFunction);
    }

    /** {@inheritDoc} */
    @Override
    public V getIfPresent(final K k) {
        return map.get(k);
    }

    /** {@inheritDoc} */
    @Override
    public V putIfAbsent(final K k, final V v) {
        V prev = map.putIfAbsent(k, v);
        return prev == null ? v : prev;
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        map.clear();
    }
}