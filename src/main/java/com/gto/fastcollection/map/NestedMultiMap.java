package com.gto.fastcollection.map;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceFunction;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@code Map<K1, Map<K2, Collection<V>>>} with the boilerplate removed:
 * two-level keys with grouped values, without the caller having to create,
 * probe and prune the nested structures. Inner maps come from
 * {@code mapFactory} and the value collections from
 * {@code collectionFactory}, both wired in at creation; empty structures are
 * removed automatically level by level.
 *
 * <p>Not thread-safe, even if the backing maps are: a {@link #put} is a chain
 * of {@code computeIfAbsent} calls followed by a mutation of the inner
 * collection.
 *
 * <p>The second-level maps returned by {@code mapFactory} must always be of
 * the same type: the kind of the first created inner map fixes the fast path
 * taken for all later inner maps, so a factory that switches types is not
 * supported.
 *
 * <p>{@link #get(K1 k1, K2 k2)} returns the live inner collection (mutating
 * it bypasses the automatic empty-key cleanup) or an immutable empty list
 * when absent.
 *
 * @param <K1> the first-level key type
 * @param <K2> the second-level key type
 * @param <V>  the element type
 */
public interface NestedMultiMap<K1, K2, V> {

    /**
     * Creates a nested multimap backed by {@code map}, with inner structures from the factories.
     */
    static <K1, K2, V> NestedMultiMap<K1, K2, V> create(
            Map<K1, Map<K2, Collection<V>>> map,
            Supplier<Map<K2, Collection<V>>> mapFactory,
            Supplier<Collection<V>> collectionFactory) {
        return new NestedMultiMapWrapper<>(map, mapFactory, collectionFactory);
    }

    /**
     * Creates a nested multimap backed by a {@link HashMap}, with inner
     * structures from the factories.
     */
    static <K1, K2, V> NestedMultiMap<K1, K2, V> create(
            Supplier<Map<K2, Collection<V>>> mapFactory, Supplier<Collection<V>> collectionFactory) {
        return new NestedMultiMapWrapper<>(new HashMap<>(), mapFactory, collectionFactory);
    }

    /**
     * Creates a nested multimap backed by a
     * {@link Reference2ReferenceOpenHashMap} (first-level keys compared by
     * object identity), with inner structures from the factories; whether the
     * deeper levels use identity depends on what the factories return.
     */
    static <K1, K2, V> NestedMultiMap<K1, K2, V> createIdentity(
            Supplier<Map<K2, Collection<V>>> mapFactory, Supplier<Collection<V>> collectionFactory) {
        return new NestedMultiMapWrapper<>(new Reference2ReferenceOpenHashMap<>(), mapFactory, collectionFactory);
    }

    /**
     * The backing map, for direct (unwrapped) iteration.
     */
    Map<K1, Map<K2, Collection<V>>> getMap();

    /**
     * Returns the live value collection under {@code (k1, k2)}, or an
     * immutable empty list if absent.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return the (possibly empty, immutable) collection of values
     */
    Collection<V> get(K1 k1, K2 k2);

    /**
     * Returns the live inner map bound to {@code k1}, or an immutable empty
     * map if the key is absent.
     *
     * @param k1 the first-level key
     * @return the key's (possibly empty, immutable) inner map
     */
    Map<K2, Collection<V>> get(K1 k1);

    /**
     * Returns whether {@code k1} currently has a (necessarily non-empty)
     * inner map.
     *
     * @param k1 the first-level key
     * @return whether the first-level key is present
     */
    boolean containsKey(K1 k1);

    /**
     * Returns whether {@code (k1, k2)} currently has a (necessarily
     * non-empty) value collection.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return whether the key pair is present
     */
    boolean containsKey(K1 k1, K2 k2);

    /**
     * Returns whether {@code value} is contained in the collection under
     * {@code (k1, k2)}; the cost is that of {@link Collection#contains} on
     * the inner collection.
     *
     * @param k1    the first-level key
     * @param k2    the second-level key
     * @param value the value to look for
     * @return whether the key pair holds the value
     */
    boolean containsValue(K1 k1, K2 k2, V value);

    /**
     * Adds {@code value} under {@code (k1, k2)}, creating the inner map and
     * collection if absent.
     *
     * @param k1    the first-level key
     * @param k2    the second-level key
     * @param value the value to add
     */
    void put(K1 k1, K2 k2, V value);

    /**
     * Adds all of {@code values} under {@code (k1, k2)}, creating the inner
     * map and collection if absent.
     *
     * @param k1     the first-level key
     * @param k2     the second-level key
     * @param values the values to add
     */
    void putAll(K1 k1, K2 k2, Collection<V> values);

    /**
     * Removes {@code value} from the collection under {@code (k1, k2)};
     * empty structures are removed level by level.
     *
     * @param k1    the first-level key
     * @param k2    the second-level key
     * @param value the value to remove
     * @return whether {@code value} was found and removed
     */
    boolean remove(K1 k1, K2 k2, V value);

    /**
     * Removes all of {@code values} from the collection under
     * {@code (k1, k2)}, keeping the remaining elements; empty structures are
     * removed level by level.
     *
     * @param k1     the first-level key
     * @param k2     the second-level key
     * @param values the values to remove
     * @return whether any element was found and removed
     */
    boolean removeAll(K1 k1, K2 k2, Collection<V> values);

    /**
     * Removes and returns the whole value collection under
     * {@code (k1, k2)}, or an immutable empty list if absent; the first-level
     * key is removed once its inner map becomes empty.
     *
     * @param k1 the first-level key
     * @param k2 the second-level key
     * @return the former collection of values
     */
    Collection<V> remove(K1 k1, K2 k2);

    /**
     * Removes and returns the whole inner map of {@code k1}, or an immutable
     * empty map if the key is absent.
     *
     * @param k1 the first-level key
     * @return the key's former inner map
     */
    Map<K2, Collection<V>> remove(K1 k1);

    /**
     * The number of first-level keys, i.e. of non-empty inner maps.
     */
    int size();

    /**
     * The total number of values across all levels; walks every value
     * collection, so it costs O(key pairs) plus the per-collection
     * {@code size()} calls.
     */
    int valueCount();

    /**
     * Feeds every first-level key, second-level key and value to
     * {@code action}, iterating all levels directly with no intermediate
     * structures.
     *
     * @param action the operation to perform on each entry
     */
    void forEach(TriConsumer<? super K1, ? super K2, ? super V> action);

    /**
     * Whether no first-level key has entries.
     */
    boolean isEmpty();

    /**
     * Removes all keys and values.
     */
    void clear();

    /**
     * The default wrapper. The factory fields are deliberately typed as
     * {@link Reference2ReferenceFunction}s: fastutil maps accept them
     * directly in their efficient {@code computeIfAbsent} overload, and they
     * are at the same time {@code java.util.function.Function}s for plain
     * maps, so one lambda serves both paths.
     */
    final class NestedMultiMapWrapper<K1, K2, V> implements NestedMultiMap<K1, K2, V> {

        private final Map<K1, Map<K2, Collection<V>>> map;
        private final Reference2ReferenceFunction<Object, Map<K2, Collection<V>>> mapFactory;
        private final Reference2ReferenceFunction<Object, Collection<V>> collectionFactory;
        private final boolean isRefMap;

        /**
         * Fixed by the first second-level map the factory creates; second-level
         * maps must not change type (see the interface contract).
         */
        private boolean isInnerRefMap;

        public NestedMultiMapWrapper(
                Map<K1, Map<K2, Collection<V>>> map,
                Supplier<Map<K2, Collection<V>>> mapFactory,
                Supplier<Collection<V>> collectionFactory) {
            this.map = map;
            this.collectionFactory = unusedKey -> collectionFactory.get();
            this.mapFactory = unusedKey -> {
                var innerMap = mapFactory.get();
                if (innerMap instanceof Reference2ReferenceMap) {
                    isInnerRefMap = true;
                }
                return innerMap;
            };
            this.isRefMap = map instanceof Reference2ReferenceMap;
        }

        @Override
        public Map<K1, Map<K2, Collection<V>>> getMap() {
            return map;
        }

        @Override
        public Collection<V> get(K1 k1, K2 k2) {
            var innerMap = map.get(k1);
            if (innerMap == null) return Collections.emptyList();
            var values = innerMap.get(k2);
            if (values != null) return values;
            return Collections.emptyList();
        }

        @Override
        public Map<K2, Collection<V>> get(K1 k1) {
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
        public boolean containsValue(K1 k1, K2 k2, V value) {
            var innerMap = map.get(k1);
            if (innerMap == null) return false;
            var values = innerMap.get(k2);
            return values != null && values.contains(value);
        }

        @Override
        public void put(K1 k1, K2 k2, V value) {
            valuesFor(k1, k2).add(value);
        }

        @Override
        public void putAll(K1 k1, K2 k2, Collection<V> values) {
            valuesFor(k1, k2).addAll(values);
        }

        @Override
        public boolean removeAll(K1 k1, K2 k2, Collection<V> values) {
            var innerMap = map.get(k1);
            if (innerMap == null) return false;
            var bound = innerMap.get(k2);
            if (bound == null || !bound.removeAll(values)) {
                return false;
            }
            if (bound.isEmpty()) {
                innerMap.remove(k2);
                if (innerMap.isEmpty()) {
                    map.remove(k1);
                }
            }
            return true;
        }

        @Override
        public boolean remove(K1 k1, K2 k2, V value) {
            var innerMap = map.get(k1);
            if (innerMap == null) return false;
            var values = innerMap.get(k2);
            if (values == null) return false;
            if (values.remove(value)) {
                if (values.isEmpty()) {
                    innerMap.remove(k2);
                    if (innerMap.isEmpty()) {
                        map.remove(k1);
                    }
                }
                return true;
            }
            return false;
        }

        @Override
        public Collection<V> remove(K1 k1, K2 k2) {
            var innerMap = map.get(k1);
            if (innerMap == null) return Collections.emptyList();
            var values = innerMap.remove(k2);
            if (values == null) return Collections.emptyList();
            if (innerMap.isEmpty()) {
                map.remove(k1);
            }
            return values;
        }

        @Override
        public Map<K2, Collection<V>> remove(K1 k1) {
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
                for (var values : innerMap.values()) {
                    count += values.size();
                }
            }
            return count;
        }

        @Override
        public void forEach(TriConsumer<? super K1, ? super K2, ? super V> action) {
            map.forEach((k1, v1s) -> v1s.forEach((k2, v2s) -> v2s.forEach(v -> action.accept(k1, k2, v))));
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }

        /**
         * The value collection under {@code (k1, k2)}, created (level by level) if absent.
         */
        private Collection<V> valuesFor(K1 k1, K2 k2) {
            Map<K2, Collection<V>> innerMap;
            if (isRefMap) {
                innerMap = ((Reference2ReferenceMap<K1, Map<K2, Collection<V>>>) map).computeIfAbsent(k1, mapFactory);
            } else {
                innerMap = map.computeIfAbsent(k1, mapFactory);
            }
            if (isInnerRefMap) {
                return ((Reference2ReferenceMap<K2, Collection<V>>) innerMap).computeIfAbsent(k2, collectionFactory);
            }
            return innerMap.computeIfAbsent(k2, collectionFactory);
        }
    }
}
