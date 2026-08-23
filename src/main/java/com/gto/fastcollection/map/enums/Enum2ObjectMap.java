package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceCollection;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ReferenceCollection;
import it.unimi.dsi.fastutil.objects.ReferenceSet;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2ReferenceMap} with enum keys, indexed by key ordinal
 * exactly like {@link java.util.EnumMap}: every operation is a single array
 * access with no hashing.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence
 * ({@code null} marks an empty slot) and an internal sentinel represents
 * bound {@code null} values, so null values are fully supported. Reads of
 * absent keys return {@link #defRetValue}, which defaults to
 * {@code null}.
 *
 * <p>The entry-set iterator reuses a single mutable entry (as fastutil's
 * fast iterators do): entries are valid only until the next
 * {@code next()} call.
 *
 * <p>Null keys read as absent; keys of a foreign enum type read as absent.
 * Not thread-safe.
 *
 * @param <K> the enum key type
 * @param <V> the value type
 */
public final class Enum2ObjectMap<K extends Enum<K>, V> extends AbstractReference2ReferenceMap<K, V> {

    /**
     * Distinguishes a bound {@code null} value from an empty slot.
     */
    private static final Object NULL = new Object();

    private final Class<K> keyType;
    private final K[] keys;
    private final Object[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final ReferenceCollection<V> values = new Values();

    /**
     * Creates an empty map able to hold every constant of {@code keyType}.
     */
    public Enum2ObjectMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new Object[keys.length];
    }

    /**
     * Creates a copy of {@code map}.
     */
    public Enum2ObjectMap(Enum2ObjectMap<K, V> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /**
     * The enum type this map is indexed by.
     */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public V get(Object key) {
        if (!isValidKey(key)) return defRetValue;
        Object v = vals[((Enum<?>) key).ordinal()];
        return v == null ? defRetValue : unmaskNull(v);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        Object v = vals[((Enum<?>) key).ordinal()];
        return v == null ? defaultValue : unmaskNull(v);
    }

    @Override
    public V put(K key, V value) {
        int i = key.ordinal();
        Object old = vals[i];
        // maskNull keeps a null value present as the NULL sentinel
        vals[i] = maskNull(value);
        if (old == null) size++;
        return old == null ? defRetValue : unmaskNull(old);
    }

    @Override
    public V remove(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        Object old = vals[i];
        if (old == null) return defRetValue;
        vals[i] = null;
        size--;
        return unmaskNull(old);
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != null;
    }

    @Override
    public boolean containsValue(Object value) {
        Object v = maskNull(value);
        final var vals = Enum2ObjectMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (v.equals(vals[i])) return true;
        }
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void clear() {
        if (size == 0) return;
        Arrays.fill(vals, null);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        final var vals = Enum2ObjectMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != null) action.accept(keys[i], unmaskNull(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2ReferenceMap.Entry<K, V>> reference2ReferenceEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public ReferenceCollection<V> values() {
        return values;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        int i = key.ordinal();
        Object old = vals[i];
        if (old != null) return unmaskNull(old);
        vals[i] = maskNull(value);
        size++;
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (!isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        Object v = vals[i];
        if (v == null || !Objects.equals(unmaskNull(v), value)) return false;
        vals[i] = null;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        int i = key.ordinal();
        Object v = vals[i];
        if (v == null || !Objects.equals(unmaskNull(v), oldValue)) return false;
        vals[i] = maskNull(newValue);
        return true;
    }

    @Override
    public V replace(K key, V value) {
        int i = key.ordinal();
        Object old = vals[i];
        if (old == null) return defRetValue;
        vals[i] = maskNull(value);
        return unmaskNull(old);
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
        int i = key.ordinal();
        Object old = vals[i];
        if (old != null) return unmaskNull(old);
        V newValue = mappingFunction.apply(key);
        if (newValue != null) {
            vals[i] = maskNull(newValue);
            size++;
        }
        return newValue;
    }

    @Override
    public V computeIfPresent(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        int i = key.ordinal();
        Object old = vals[i];
        if (old == null) return defRetValue;
        V newValue = remappingFunction.apply(key, unmaskNull(old));
        if (newValue == null) {
            vals[i] = null;
            size--;
            return defRetValue;
        }
        vals[i] = maskNull(newValue);
        return newValue;
    }

    @Override
    public V compute(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        int i = key.ordinal();
        Object old = vals[i];
        V newValue = remappingFunction.apply(key, unmaskNull(old));
        if (newValue == null) {
            if (old != null) {
                vals[i] = null;
                size--;
            }
            return defRetValue;
        }
        vals[i] = maskNull(newValue);
        return newValue;
    }

    @Override
    public V merge(K key, V value, java.util.function.BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (value == null) return defRetValue;
        int i = key.ordinal();
        Object old = vals[i];
        V newValue = old == null ? value : remappingFunction.apply(unmaskNull(old), value);
        if (newValue == null) {
            if (old != null) {
                vals[i] = null;
                size--;
            }
            return defRetValue;
        }
        vals[i] = maskNull(newValue);
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private static Object maskNull(Object value) {
        return value == null ? NULL : value;
    }

    @SuppressWarnings("unchecked")
    private V unmaskNull(Object value) {
        return (V) (value == NULL ? null : value);
    }

    private final class EntrySet extends AbstractObjectSet<Reference2ReferenceMap.Entry<K, V>>
            implements Reference2ReferenceMap.FastEntrySet<K, V> {

        @Override
        public ObjectIterator<Reference2ReferenceMap.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2ReferenceMap.Entry<K, V>> fastIterator() {
            return new EntryIterator();
        }

        /**
         * Feeds a single reused entry to {@code consumer}: no per-entry allocation.
         */
        @Override
        public void fastForEach(Consumer<? super Reference2ReferenceMap.Entry<K, V>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2ObjectMap.this.vals;
            final int length = vals.length;
            for (int i = 0; i < length; i++) {
                if (vals[i] != null) {
                    entry.index = i;
                    consumer.accept(entry);
                }
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2ObjectMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != null && Objects.equals(unmaskNull(vals[i]), e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            Enum2ObjectMap.this.remove(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /**
     * A reused, index-backed entry; valid only while its iterator stands still.
     */
    private final class Entry implements Reference2ReferenceMap.Entry<K, V> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public V getValue() {
            return unmaskNull(vals[index]);
        }

        @Override
        public V setValue(V value) {
            V old = getValue();
            vals[index] = maskNull(value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return e.getKey() == keys[index] && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Objects.hashCode(getValue());
        }

        @Override
        public String toString() {
            return keys[index] + "=" + getValue();
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2ObjectMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == null) {
                cursor++;
            }
            return cursor < length;
        }

        int advance() {
            if (!hasNext()) throw new NoSuchElementException();
            return current = cursor++;
        }

        public void remove() {
            if (current < 0) throw new IllegalStateException();
            Enum2ObjectMap.this.remove(keys[current]);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2ReferenceMap.Entry<K, V>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2ReferenceMap.Entry<K, V> next() {
            entry.index = advance();
            return entry;
        }
    }

    private final class KeyIterator extends IndexIterator implements ObjectIterator<K> {

        @Override
        public K next() {
            return keys[advance()];
        }
    }

    private final class ValueIterator extends IndexIterator implements ObjectIterator<V> {

        @Override
        public V next() {
            return unmaskNull(vals[advance()]);
        }
    }

    private final class KeySet extends AbstractReferenceSet<K> {

        @Override
        public ObjectIterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2ObjectMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2ObjectMap.this.remove(o);
            return true;
        }
    }

    private final class Values extends AbstractReferenceCollection<V> {

        @Override
        public ObjectIterator<V> iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(Object o) {
            return containsValue(o);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2ObjectMap.this.clear();
        }
    }
}
