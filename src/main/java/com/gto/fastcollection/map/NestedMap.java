package com.gto.fastcollection.map;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceFunction;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@code Map<K1, Map<K2, V>>} with the boilerplate removed: two-level keys
 * are stored and cleaned up without the caller having to create, probe and
 * prune the inner maps. Inner maps come from a {@link Supplier} wired in at
 * creation; a first-level key whose inner map becomes empty is removed
 * automatically.
 *
 * <p>Not thread-safe, even if the backing maps are: a {@link #put} is a
 * {@code computeIfAbsent} followed by a mutation of the inner map.
 *
 * <p>The second-level maps returned by the factory must always be of the same
 * type: the kind of the first created inner map fixes the fast path taken for
 * all later inner maps, so a factory that switches types is not supported.
 *
 * <p>{@link #get(Object)} returns the live inner map (mutating it bypasses
 * the automatic empty-key cleanup) or an immutable empty map when the key is
 * absent.
 *
 * @param <K1> the first-level key type
 * @param <K2> the second-level key type
 * @param <V>  the value type
 */
public interface NestedMap<K1, K2, V> {

    /** Creates a nested map backed by {@code map}, with inner maps from {@code factory}. */
    static <K1, K2, V> NestedMap<K1, K2, V> create(Map<K1, Map<K2, V>> map, Supplier<Map<K2, V>> factory) {
        return new NestedMapWrapper<>(map, factory);
    }

    /** Creates a nested map backed by a {@link HashMap}, with inner maps from {@code factory}. */
    static <K1, K2, V> NestedMap<K1, K2, V> create(Supplier<Map<K2, V>> factory) {
        return new NestedMapWrapper<>(new HashMap<>(), factory);
    }

    /**
     * Creates a nested map backed by a {@link Reference2ReferenceOpenHashMap}
     * (first-level keys compared by object identity), with inner maps from
     * {@code factory}; whether inner keys use identity depends on the maps
     * the factory returns.
     */
    static <K1, K2, V> NestedMap<K1, K2, V> createIdentity(Supplier<Map<K2, V>> factory) {
        return new NestedMapWrapper<>(new Reference2ReferenceOpenHashMap<>(), factory);
    }

    /** The backing map, for direct (unwrapped) iteration. */
    Map<K1, Map<K2, V>> getMap();

    /**
     * Returns the value under {@code (k1, k2)}, or {@code null} if absent.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return the bound value, or {@code null}
     */
    V get(K1 k1, K2 k2);

    /**
     * Returns the live inner map bound to {@code k1}, or an immutable empty
     * map if the key is absent.
     *
     * @param k1 the first-level key
     * @return the key's (possibly empty, immutable) inner map
     */
    Map<K2, V> get(K1 k1);

    /**
     * Returns whether {@code k1} currently has a (necessarily non-empty)
     * inner map.
     *
     * @param k1 the first-level key
     * @return whether the first-level key is present
     */
    boolean containsKey(K1 k1);

    /**
     * Returns whether a value is bound under {@code (k1, k2)}.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return whether the key pair is present
     */
    boolean containsKey(K1 k1, K2 k2);

    /**
     * Binds {@code value} under {@code (k1, k2)}, creating the inner map if
     * absent.
     *
     * @param k1    the first-level key
     * @param k2    the second-level key
     * @param value the value to bind
     * @return the previous value, or {@code null}
     */
    V put(K1 k1, K2 k2, V value);

    /**
     * Returns the value under {@code (k1, k2)}, computing and binding it with
     * {@code mappingFunction} if absent. The function may run outside any
     * lock and must not call back into this map.
     *
     * @param k1              the first-level key
     * @param k2              the second-level key
     * @param mappingFunction computes the value when absent
     * @return the current (possibly just computed) value
     */
    V computeIfAbsent(K1 k1, K2 k2, Reference2ReferenceFunction<? super K2, ? extends V> mappingFunction);

    /**
     * Removes the value under {@code (k1, k2)}; the first-level key itself is
     * removed once its inner map becomes empty.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return the removed value, or {@code null}
     */
    V remove(K1 k1, K2 k2);

    /**
     * Removes and returns the whole inner map of {@code k1}, or an immutable
     * empty map if the key is absent.
     *
     * @param k1 the first-level key
     * @return the key's former inner map
     */
    Map<K2, V> remove(K1 k1);

    /** The number of first-level keys, i.e. of non-empty inner maps. */
    int size();

    /**
     * The total number of values across all inner maps; walks every inner
     * map, so it costs O(first-level keys) plus one {@code size()} per inner
     * map.
     */
    int valueCount();

    /**
     * Feeds every first-level key, second-level key and value to
     * {@code action}, iterating both map levels directly with no intermediate
     * structures.
     *
     * @param action the operation to perform on each entry
     */
    void forEach(TriConsumer<? super K1, ? super K2, ? super V> action);

    /** Whether no first-level key has entries. */
    boolean isEmpty();

    /** Removes all keys and values. */
    void clear();

    /**
     * The default wrapper. The factory field is deliberately typed as a
     * {@link Reference2ReferenceFunction}: fastutil maps accept it directly
     * in their efficient {@code computeIfAbsent} overload, and it is at the
     * same time a {@code java.util.function.Function} for plain maps, so one
     * lambda serves both paths.
     */
    final class NestedMapWrapper<K1, K2, V> implements NestedMap<K1, K2, V> {

        private final Map<K1, Map<K2, V>> map;
        private final Reference2ReferenceFunction<Object, Map<K2, V>> factory;
        private final boolean isRefMap;

        /**
         * Fixed by the first second-level map the factory creates; second-level
         * maps must not change type (see the interface contract).
         */
        private boolean isInnerRefMap;

        private NestedMapWrapper(Map<K1, Map<K2, V>> map, Supplier<Map<K2, V>> factory) {
            this.map = map;
            this.factory = unusedKey -> {
                var innerMap = factory.get();
                if (innerMap instanceof Reference2ReferenceMap) {
                    isInnerRefMap = true;
                }
                return innerMap;
            };
            this.isRefMap = map instanceof Reference2ReferenceMap;
        }

        @Override
        public Map<K1, Map<K2, V>> getMap() {
            return map;
        }

        @Override
        public Map<K2, V> get(K1 k1) {
            var innerMap = map.get(k1);
            if (innerMap != null) return innerMap;
            return Collections.emptyMap();
        }

        @Override
        public boolean containsKey(K1 k1) {
            return map.containsKey(k1);
        }

        @Override
        public boolean containsKey(K1 k1, K2 k2) {
            var innerMap = map.get(k1);
            return innerMap != null && innerMap.containsKey(k2);
        }

        @Override
        public V get(K1 k1, K2 k2) {
            var innerMap = map.get(k1);
            return innerMap == null ? null : innerMap.get(k2);
        }

        @Override
        public V put(K1 k1, K2 k2, V value) {
            return innerMapFor(k1).put(k2, value);
        }

        @Override
        public V computeIfAbsent(K1 k1, K2 k2, Reference2ReferenceFunction<? super K2, ? extends V> mappingFunction) {
            Map<K2, V> innerMap = innerMapFor(k1);
            if (isInnerRefMap) {
                return ((Reference2ReferenceMap<K2, V>) innerMap).computeIfAbsent(k2, mappingFunction);
            }
            return innerMap.computeIfAbsent(k2, mappingFunction);
        }

        @Override
        public V remove(K1 k1, K2 k2) {
            Map<K2, V> innerMap = map.get(k1);
            if (innerMap == null) return null;
            V removed = innerMap.remove(k2);
            if (removed != null && innerMap.isEmpty()) map.remove(k1);
            return removed;
        }

        @Override
        public Map<K2, V> remove(K1 k1) {
            var innerMap = map.remove(k1);
            if (innerMap != null) return innerMap;
            return Collections.emptyMap();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public int valueCount() {
            int count = 0;
            for (var innerMap : map.values()) {
                count += innerMap.size();
            }
            return count;
        }

        @Override
        public void forEach(TriConsumer<? super K1, ? super K2, ? super V> action) {
            map.forEach((k1,vs)-> vs.forEach((k2, v)-> action.accept(k1, k2, v)));
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }

        /** The inner map of {@code k1}, created (and wired through) if absent. */
        private Map<K2, V> innerMapFor(K1 k1) {
            if (isRefMap) {
                return ((Reference2ReferenceMap<K1, Map<K2, V>>) map).computeIfAbsent(k1, factory);
            }
            return map.computeIfAbsent(k1, factory);
        }
    }
}
