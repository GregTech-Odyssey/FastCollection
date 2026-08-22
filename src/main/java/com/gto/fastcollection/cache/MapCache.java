package com.gto.fastcollection.cache;

import java.util.function.Function;

/**
 * A thread-safe cache keyed by {@code K}. All implementations are safe for
 * concurrent use by multiple threads.
 *
 * <p>Mappings follow {@code putIfAbsent} semantics: the value bound to a key is
 * decided by the first thread that stores it, and later writers never replace it
 * (the existing value is returned instead). A factory is wired into the
 * constructor and used by the no-argument convenience methods; it may be
 * {@code null}, in which case those methods throw {@link NullPointerException}.
 */
public interface MapCache<K, V> {

    /**
     * Returns the value associated with {@code k}, computing and storing it with
     * {@code createFunction} if absent. The function runs while the cache holds an
     * internal lock and must <b>not</b> call back into this cache; use
     * {@link #getCacheRecursive(Object, Function)} for reentrant computations.
     */
    V getCache(final K k, Function<? super K, ? extends V> createFunction);

    /**
     * Returns the value associated with {@code k}, or {@code null} if absent.
     * Never stores anything.
     */
    V getIfPresent(final K k);

    /**
     * Associates {@code v} with {@code k} only if {@code k} is not yet present,
     * and returns the value now associated with {@code k}: the previous value if
     * one existed, {@code v} otherwise. Existing mappings are never replaced.
     */
    V putIfAbsent(final K k, final V v);

    /** Removes all mappings. */
    void clear();

    /**
     * Returns the value associated with {@code k}, computing it with the factory
     * wired into the constructor if absent.
     *
     * @throws NullPointerException if no factory was configured in the constructor
     */
    default V getCache(final K k) {
        return getCache(k, createFunction());
    }

    /**
     * The factory wired into the constructor.
     *
     * @return the factory configured at construction time
     */
    default Function<? super K, ? extends V> createFunction() {
        return null;
    }

    /**
     * Like {@link #getCache(Object, Function)} but the {@code createFunction} runs
     * outside any internal lock, so it may recursively call back into this cache
     * (e.g. to resolve another key). When several threads compute the same key at
     * once, the function may run more than once; the first computed value wins.
     * The function must not return {@code null}.
     */
    V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction);

    /**
     * Uses the factory wired into the constructor.
     *
     * @throws NullPointerException if no factory was configured in the constructor
     */
    default V getCacheRecursive(final K k) {
        return getCacheRecursive(k, createFunction());
    }
}
