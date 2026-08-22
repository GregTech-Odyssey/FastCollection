package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2CharMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2CharFunction;
import it.unimi.dsi.fastutil.objects.Reference2CharMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.chars.CharBinaryOperator;
import it.unimi.dsi.fastutil.chars.CharCollection;
import it.unimi.dsi.fastutil.chars.CharIterator;
import it.unimi.dsi.fastutil.chars.AbstractCharCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2CharMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getChar}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code (char) 0} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(char)}. When zero is a meaningful value, use
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
public final class Enum2CharMap<K extends Enum<K>> extends AbstractReference2CharMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final char[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final CharCollection values = new Values();

    /** Creates an empty map able to hold every constant of {@code keyType}. */
    public Enum2CharMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new char[keys.length];
    }

    /** Creates a copy of {@code map}. */
    public Enum2CharMap(Enum2CharMap<K> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /** The enum type this map is indexed by. */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public char getChar(Object key) {
        if (!isValidKey(key)) return defRetValue;
        char v = vals[((Enum<?>) key).ordinal()];
        return v != (char) 0 ? v : defRetValue;
    }

    @Override
    public char getOrDefault(Object key, char defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        char v = vals[((Enum<?>) key).ordinal()];
        return v != (char) 0 ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public char put(K key, char value) {
        int i = key.ordinal();
        char old = vals[i];
        vals[i] = value;
        if (value == (char) 0) {
            if (old != (char) 0) size--;
        } else if (old == (char) 0) {
            size++;
        }
        return old != (char) 0 ? old : defRetValue;
    }

    @Override
    public char removeChar(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        char old = vals[i];
        if (old == (char) 0) return defRetValue;
        vals[i] = (char) 0;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != (char) 0;
    }

    @Override
    public boolean containsValue(char value) {
        if (value == (char) 0) return false;
        final var vals = Enum2CharMap.this.vals;
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
        Arrays.fill(vals, (char) 0);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Character> action) {
        final var vals = Enum2CharMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != (char) 0) action.accept(keys[i], Character.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2CharMap.Entry<K>> reference2CharEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public CharCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public char putIfAbsent(K key, char value) {
        int i = key.ordinal();
        char old = vals[i];
        if (old != (char) 0) return old;
        if (value != (char) 0) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, char value) {
        if (value == (char) 0 || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = (char) 0;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, char oldValue, char newValue) {
        if (oldValue == (char) 0 || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public char replace(K key, char value) {
        char old = vals[key.ordinal()];
        if (old == (char) 0) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public char computeIfAbsent(K key, java.util.function.ToIntFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        char v = vals[i];
        if (v != (char) 0) return v;
        char newValue = (char) mappingFunction.applyAsInt(key);
        if (newValue != (char) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public char computeIfAbsent(K key, Reference2CharFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        char v = vals[i];
        if (v != (char) 0) return v;
        char newValue = mappingFunction.getChar(key);
        if (newValue != (char) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public char computeCharIfPresent(K key, java.util.function.BiFunction<? super K, ? super Character, ? extends Character> remappingFunction) {
        int i = key.ordinal();
        char old = vals[i];
        if (old == (char) 0) return defRetValue;
        Character newValue = remappingFunction.apply(key, Character.valueOf(old));
        if (newValue == null) {
            vals[i] = (char) 0;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public char computeChar(K key, java.util.function.BiFunction<? super K, ? super Character, ? extends Character> remappingFunction) {
        int i = key.ordinal();
        char old = vals[i];
        Character newValue = remappingFunction.apply(key, old == (char) 0 ? null : Character.valueOf(old));
        if (newValue == null) {
            if (old != (char) 0) {
                vals[i] = (char) 0;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code (char) 0}. A sum of {@code (char) 0} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public char addTo(K key, char incr) {
        int i = key.ordinal();
        char old = vals[i];
        if (old == (char) 0) {
            if (incr == (char) 0) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        char sum = (char) (old + incr);
        vals[i] = sum;
        if (sum == (char) 0) size--;
        return old;
    }

    @Override
    public char mergeChar(K key, char value, CharBinaryOperator remappingFunction) {
        int i = key.ordinal();
        char old = vals[i];
        char newValue = old == (char) 0 ? value : remappingFunction.apply(old, value);
        if (newValue == (char) 0) {
            if (old != (char) 0) {
                vals[i] = (char) 0;
                size--;
            }
            return defRetValue;
        }
        if (old == (char) 0) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2CharMap.Entry<K>>
            implements Reference2CharMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2CharMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2CharMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /** Feeds a single reused entry to {@code consumer}: no per-entry allocation. */
        @Override
        public void fastForEach(Consumer<? super Reference2CharMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2CharMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
                if (vals[i] != (char) 0) {
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
            Enum2CharMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != (char) 0 && Character.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeChar(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /** A reused, index-backed entry; valid only while its iterator stands still. */
    private final class Entry implements Reference2CharMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public char getCharValue() {
            return vals[index];
        }

        @Override
        public char setValue(char value) {
            char old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            return e.getKey() == keys[index] && Character.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Character.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Character.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2CharMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == (char) 0) {
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
            Enum2CharMap.this.put(keys[current], (char) 0);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2CharMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2CharMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements CharIterator {

        @Override
        public char nextChar() {
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
            Enum2CharMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2CharMap.this.removeChar(o);
            return true;
        }
    }

    private final class Values extends AbstractCharCollection {

        @Override
        public CharIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(char value) {
            return Enum2CharMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2CharMap.this.clear();
        }
    }
}
