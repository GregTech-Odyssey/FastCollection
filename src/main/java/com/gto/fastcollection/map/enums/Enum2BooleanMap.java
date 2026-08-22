package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2BooleanMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2BooleanFunction;
import it.unimi.dsi.fastutil.objects.Reference2BooleanMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.booleans.BooleanCollection;
import it.unimi.dsi.fastutil.booleans.BooleanIterator;
import it.unimi.dsi.fastutil.booleans.AbstractBooleanCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2BooleanMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getBoolean}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code false} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(boolean)}. When zero is a meaningful value, use
 * {@link Enum2ObjectMap} instead.
 *
 * <p>The entry-set iterator reuses a single mutable entry (as fastutil's
 * fast iterators do): entries are valid only until the next
 * {@code next()} call.
 *
 * <p>Null keys read as absent; keys of a foreign enum type read as absent.
 * Not thread-safe.
 *
 * @param <K> the enum key type
 */
public final class Enum2BooleanMap<K extends Enum<K>> extends AbstractReference2BooleanMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final boolean[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final BooleanCollection values = new Values();

    /** Creates an empty map able to hold every constant of {@code keyType}. */
    public Enum2BooleanMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new boolean[keys.length];
    }

    /** Creates a copy of {@code map}. */
    public Enum2BooleanMap(Enum2BooleanMap<K> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /** The enum type this map is indexed by. */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public boolean getBoolean(Object key) {
        if (!isValidKey(key)) return defRetValue;
        boolean v = vals[((Enum<?>) key).ordinal()];
        return v != false ? v : defRetValue;
    }

    @Override
    public boolean getOrDefault(Object key, boolean defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        boolean v = vals[((Enum<?>) key).ordinal()];
        return v != false ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public boolean put(K key, boolean value) {
        int i = key.ordinal();
        boolean old = vals[i];
        vals[i] = value;
        if (value == false) {
            if (old != false) size--;
        } else if (old == false) {
            size++;
        }
        return old != false ? old : defRetValue;
    }

    @Override
    public boolean removeBoolean(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        boolean old = vals[i];
        if (old == false) return defRetValue;
        vals[i] = false;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != false;
    }

    @Override
    public boolean containsValue(boolean value) {
        if (value == false) return false;
        final var vals = Enum2BooleanMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] == value) return true;
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
        Arrays.fill(vals, false);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Boolean> action) {
        final var vals = Enum2BooleanMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != false) action.accept(keys[i], Boolean.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2BooleanMap.Entry<K>> reference2BooleanEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public BooleanCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public boolean putIfAbsent(K key, boolean value) {
        int i = key.ordinal();
        boolean old = vals[i];
        if (old != false) return old;
        if (value != false) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, boolean value) {
        if (value == false || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = false;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, boolean oldValue, boolean newValue) {
        if (oldValue == false || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public boolean replace(K key, boolean value) {
        boolean old = vals[key.ordinal()];
        if (old == false) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public boolean computeIfAbsent(K key, java.util.function.Predicate<? super K> mappingFunction) {
        int i = key.ordinal();
        boolean v = vals[i];
        if (v != false) return v;
        boolean newValue = (boolean) mappingFunction.test(key);
        if (newValue != false) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public boolean computeIfAbsent(K key, Reference2BooleanFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        boolean v = vals[i];
        if (v != false) return v;
        boolean newValue = mappingFunction.getBoolean(key);
        if (newValue != false) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public boolean computeBooleanIfPresent(K key, java.util.function.BiFunction<? super K, ? super Boolean, ? extends Boolean> remappingFunction) {
        int i = key.ordinal();
        boolean old = vals[i];
        if (old == false) return defRetValue;
        Boolean newValue = remappingFunction.apply(key, Boolean.valueOf(old));
        if (newValue == null) {
            vals[i] = false;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public boolean computeBoolean(K key, java.util.function.BiFunction<? super K, ? super Boolean, ? extends Boolean> remappingFunction) {
        int i = key.ordinal();
        boolean old = vals[i];
        Boolean newValue = remappingFunction.apply(key, old == false ? null : Boolean.valueOf(old));
        if (newValue == null) {
            if (old != false) {
                vals[i] = false;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2BooleanMap.Entry<K>>
            implements Reference2BooleanMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2BooleanMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2BooleanMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /** Feeds a single reused entry to {@code consumer}: no per-entry allocation. */
        @Override
        public void fastForEach(Consumer<? super Reference2BooleanMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2BooleanMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
                if (vals[i] != false) {
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
            Enum2BooleanMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != false && Boolean.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeBoolean(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /** A reused, index-backed entry; valid only while its iterator stands still. */
    private final class Entry implements Reference2BooleanMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public boolean getBooleanValue() {
            return vals[index];
        }

        @Override
        public boolean setValue(boolean value) {
            boolean old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            return e.getKey() == keys[index] && Boolean.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Boolean.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Boolean.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2BooleanMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == false) {
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
            Enum2BooleanMap.this.put(keys[current], false);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2BooleanMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2BooleanMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements BooleanIterator {

        @Override
        public boolean nextBoolean() {
            return vals[advance()];
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
            Enum2BooleanMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2BooleanMap.this.removeBoolean(o);
            return true;
        }
    }

    private final class Values extends AbstractBooleanCollection {

        @Override
        public BooleanIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(boolean value) {
            return Enum2BooleanMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2BooleanMap.this.clear();
        }
    }
}
