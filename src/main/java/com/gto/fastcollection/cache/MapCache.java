package com.gto.fastcollection.cache;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A thread-safe cache keyed by {@code K}. All implementations are safe for
 * concurrent use by multiple threads.
 *
 * <p>Mappings follow {@code putIfAbsent} semantics: the value bound to a key is
 * decided by the first thread that stores it, and later writers never replace it
 * (the existing value is returned instead).
 *
 * <p>{@link #getCache(Object, Function)} resolves the value for a key,
 * computing it with the supplied create function when absent. The function may
 * call back into this cache to resolve dependencies (it is not executed under
 * any lock that would make such a callback deadlock), so recursive and
 * cross-key computations are always safe. {@link #getCacheRecursive} is the
 * explicit name for the same guarantee — use it when the recursive nature of
 * the create function is a hard requirement, e.g. when targeting an
 * implementation whose {@code getCache} runs the function under a lock
 * ({@link HashCache}).
 *
 * <p>An optional {@link UnaryOperator keyMappingFunction} remaps the key
 * before it is looked up or stored: the mapping decides <em>where</em> the
 * value lives, while the create function still receives the original key.
 * The identity mapping is the default (no remapping); other mappings can
 * canonicalize keys (intern by content) or redirect groups of keys to one
 * slot.
 *
 * <p>A create function returning {@code null} is not stored: the key is simply
 * left absent. Create functions must not assume they run exactly once per key —
 * concurrent computations of the same key may both run, in which case the
 * first stored value wins (the {@code ConcurrentHashMap}-backed
 * {@link HashCache#getCache(Object, Function)} is the exception, running its
 * function exactly once via {@code computeIfAbsent}).
 */
public interface MapCache<K, V> {

    /**
     * Returns the value associated with {@code k}, computing and storing it
     * with {@code createFunction} if absent. Recursive calls from the function
     * back into this cache are safe.
     *
     * @param k              the key to look up
     * @param createFunction computes the value when {@code k} is absent
     * @return the value now associated with {@code k}
     */
    V getCache(final K k, Function<? super K, ? extends V> createFunction);

    /**
     * Like {@link #getCache(Object, Function)}, but the value is looked up and
     * stored under the mapped key {@code keyMappingFunction.apply(k)}; the
     * create function still receives the original {@code k}.
     *
     * @param k                 the key to look up
     * @param createFunction    computes the value when absent
     * @param keyMappingFunction remaps {@code k} to the storage key
     * @return the value now associated with {@code k}
     */
    V getCache(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction);

    /**
     * Returns the value associated with {@code k}, computing and storing it
     * with {@code createFunction} if absent. Like
     * {@link #getCache(Object, Function)} — the function runs without holding
     * any lock, so recursive calls back into this cache are guaranteed safe.
     *
     * @param k              the key to look up
     * @param createFunction computes the value when {@code k} is absent
     * @return the value now associated with {@code k}
     */
    V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction);

    /**
     * Like {@link #getCacheRecursive(Object, Function)}, but the value is
     * looked up and stored under the mapped key
     * {@code keyMappingFunction.apply(k)}; the create function still receives
     * the original {@code k}.
     *
     * @param k                 the key to look up
     * @param createFunction    computes the value when absent
     * @param keyMappingFunction remaps {@code k} to the storage key
     * @return the value now associated with {@code k}
     */
    V getCacheRecursive(final K k, Function<? super K, ? extends V> createFunction, UnaryOperator<K> keyMappingFunction);

    /**
     * The default create function wired into this cache at construction, used by
     * the no-function {@code getCache} overloads. May be {@code null} when no
     * factory was configured; calling those overloads on such a cache then throws
     * {@link NullPointerException}.
     */
    default Function<? super K, ? extends V> createFunction() {
        return null;
    }

    /**
     * Returns the value associated with {@code k}, computing and storing it with
     * the default {@link #createFunction()} if absent. Recursive calls from the
     * function back into this cache are safe.
     *
     * @param k the key to look up
     * @return the value now associated with {@code k}
     * @throws NullPointerException if no default create function was configured
     */
    default V getCache(final K k) {
        return getCache(k, createFunction());
    }

    /**
     * Returns the value associated with {@code k}, computing and storing it with
     * the default {@link #createFunction()} if absent. The function runs without
     * holding any lock, so recursive calls back into this cache are guaranteed
     * safe.
     *
     * @param k the key to look up
     * @return the value now associated with {@code k}
     * @throws NullPointerException if no default create function was configured
     */
    default V getCacheRecursive(final K k) {
        return getCacheRecursive(k, createFunction());
    }

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

    /**
     * Removes all mappings.
     */
    void clear();

}
