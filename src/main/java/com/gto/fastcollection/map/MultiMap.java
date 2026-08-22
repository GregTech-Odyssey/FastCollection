package com.gto.fastcollection.map;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceFunction;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A {@code Map<K, Collection<V>>} with the boilerplate removed: values are
 * grouped per key without the caller having to create, probe and clean up the
 * inner collections. Inner collections come from a {@link Supplier} wired in
 * at creation; a key whose collection becomes empty is removed automatically.
 *
 * <p>Not thread-safe, even if the backing map is: a {@link #put} is a
 * {@code computeIfAbsent} followed by a mutation of the inner collection.
 *
 * <p>{@link #get(Object)} returns the live inner collection (mutating it
 * bypasses the automatic empty-key cleanup) or an immutable empty list when
 * the key is absent.
 *
 * @param <K> the key type
 * @param <V> the element type
 */
public interface MultiMap<K, V> {

    /** Creates a multimap backed by {@code map}, with collections from {@code factory}. */
    static <K, V> MultiMap<K, V> create(Map<K, Collection<V>> map, Supplier<Collection<V>> factory) {
        return new MultiMapWrapper<>(map, factory);
    }

    /** Creates a multimap backed by a {@link HashMap}, with collections from {@code factory}. */
    static <K, V> MultiMap<K, V> create(Supplier<Collection<V>> factory) {
        return new MultiMapWrapper<>(new HashMap<>(), factory);
    }

    /**
     * Creates a multimap backed by a {@link Reference2ReferenceOpenHashMap}
     * (keyed by object identity), with collections from {@code factory}.
     */
    static <K, V> MultiMap<K, V> createIdentity(Supplier<Collection<V>> factory) {
        return new MultiMapWrapper<>(new Reference2ReferenceOpenHashMap<>(), factory);
    }

    /** The backing map, for direct (unwrapped) iteration. */
    Map<K, Collection<V>> getMap();

    /**
     * Returns the live collection bound to {@code key}, or an immutable empty
     * list if the key is absent.
     *
     * @param key the key to look up
     * @return the key's (possibly empty, immutable) collection of values
     */
    Collection<V> get(K key);

    /**
     * Returns whether {@code key} currently has a (necessarily non-empty)
     * collection of values.
     *
     * @param key the key to look up
     * @return whether the key is present
     */
    boolean containsKey(K key);

    /**
     * Returns whether {@code value} is contained in the collection of
     * {@code key}; the cost is that of {@link Collection#contains} on the
     * inner collection.
     *
     * @param key   the key to look up
     * @param value the value to look for
     * @return whether the key holds the value
     */
    boolean containsValue(K key, V value);

    /**
     * Adds {@code value} to the collection of {@code key}, creating the
     * collection if absent.
     *
     * @param key   the key to add under
     * @param value the value to add
     */
    void put(K key, V value);

    /**
     * Adds all of {@code values} to the collection of {@code key}, creating
     * the collection if absent.
     *
     * @param key    the key to add under
     * @param values the values to add
     */
    void putAll(K key, Collection<V> values);

    /**
     * Removes {@code value} from the collection of {@code key}; the key itself
     * is removed once its collection becomes empty.
     *
     * @param key   the key to remove from
     * @param value the value to remove
     * @return whether {@code value} was found and removed
     */
    boolean remove(K key, V value);

    /**
     * Removes all of {@code values} from the collection of {@code key},
     * keeping the remaining elements; the key itself is removed once its
     * collection becomes empty.
     *
     * @param key    the key to remove from
     * @param values the values to remove
     * @return whether any element was found and removed
     */
    boolean removeAll(K key, Collection<V> values);

    /**
     * Removes and returns the whole collection of {@code key}, or an immutable
     * empty list if the key is absent.
     *
     * @param key the key to remove
     * @return the key's former collection of values
     */
    Collection<V> remove(K key);

    /** The number of keys, i.e. of non-empty value collections. */
    int size();

    /**
     * The total number of values across all keys; walks every collection, so
     * it costs O(keys) plus the per-collection {@code size()} calls.
     */
    int valueCount();

    /**
     * Feeds every (key, value) pair to {@code action}, iterating the backing
     * map and each collection directly with no intermediate structures.
     *
     * @param action the operation to perform on each pair
     */
    void forEach(BiConsumer<? super K, ? super V> action);

    /** Whether no key has values. */
    boolean isEmpty();

    /** Removes all keys and values. */
    void clear();

    /**
     * The default wrapper. The factory field is deliberately typed as a
     * {@link Reference2ReferenceFunction}: fastutil maps accept it directly in
     * their efficient {@code computeIfAbsent} overload, and it is at the same
     * time a {@code java.util.function.Function} for plain maps, so one lambda
     * serves both paths.
     */
    final class MultiMapWrapper<K, V> implements MultiMap<K, V> {

        private final Map<K, Collection<V>> map;
        private final Reference2ReferenceFunction<Object, Collection<V>> factory;
        private final boolean isRefMap;

        private MultiMapWrapper(Map<K, Collection<V>> map, Supplier<Collection<V>> factory) {
            this.map = map;
            this.factory = unusedKey -> factory.get();
            this.isRefMap = map instanceof Reference2ReferenceMap;
        }

        @Override
        public Map<K, Collection<V>> getMap() {
            return map;
        }

        @Override
        public Collection<V> get(K key) {
            var values = map.get(key);
            if (values != null) return values;
            return Collections.emptyList();
        }

        @Override
        public boolean containsKey(K key) {
            return map.containsKey(key);
        }

        @Override
        public boolean containsValue(K key, V value) {
            var values = map.get(key);
            return values != null && values.contains(value);
        }

        @Override
        public void put(K key, V value) {
            if (isRefMap) {
                ((Reference2ReferenceMap<K, Collection<V>>) map).computeIfAbsent(key, factory).add(value);
            } else {
                map.computeIfAbsent(key, factory).add(value);
            }
        }

        @Override
        public void putAll(K key, Collection<V> values) {
            if (isRefMap) {
                ((Reference2ReferenceMap<K, Collection<V>>) map).computeIfAbsent(key, factory).addAll(values);
            } else {
                map.computeIfAbsent(key, factory).addAll(values);
            }
        }

        @Override
        public boolean remove(K key, V value) {
            var values = map.get(key);
            if (values != null && values.remove(value)) {
                if (values.isEmpty()) map.remove(key);
                return true;
            }
            return false;
        }

        @Override
        public boolean removeAll(K key, Collection<V> values) {
            var bound = map.get(key);
            if (bound == null || !bound.removeAll(values)) {
                return false;
            }
            if (bound.isEmpty()) {
                map.remove(key);
            }
            return true;
        }

        @Override
        public Collection<V> remove(K key) {
            var values = map.remove(key);
            if (values != null) return values;
            return Collections.emptyList();
        }

        @Override
        public int size() {
            return map.size();
        }

        @Override
        public int valueCount() {
            int count = 0;
            for (var values : map.values()) {
                count += values.size();
            }
            return count;
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            map.forEach((k,vs)-> vs.forEach(v->action.accept(k,v)));
        }

        @Override
        public boolean isEmpty() {
            return map.isEmpty();
        }

        @Override
        public void clear() {
            map.clear();
        }
    }
}
