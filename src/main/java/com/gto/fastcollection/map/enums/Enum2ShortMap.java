package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.*;
import it.unimi.dsi.fastutil.shorts.ShortBinaryOperator;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import it.unimi.dsi.fastutil.shorts.AbstractShortCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2ShortMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getShort}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code (short) 0} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(short)}. When zero is a meaningful value, use
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
public final class Enum2ShortMap<K extends Enum<K>> extends AbstractReference2ShortMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final short[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final ShortCollection values = new Values();

    /**
     * Creates an empty map able to hold every constant of {@code keyType}.
     */
    public Enum2ShortMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new short[keys.length];
    }

    /**
     * Creates a copy of {@code map}.
     */
    public Enum2ShortMap(Enum2ShortMap<K> map) {
        this.keyType = map.keyType;
        this.keys = map.keys;
        this.vals = map.vals.clone();
        this.size = map.size;
    }

    /**
     * The enum type this map is indexed by.
     */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public short getShort(Object key) {
        if (!isValidKey(key)) return defRetValue;
        short v = vals[((Enum<?>) key).ordinal()];
        return v != (short) 0 ? v : defRetValue;
    }

    @Override
    public short getOrDefault(Object key, short defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        short v = vals[((Enum<?>) key).ordinal()];
        return v != (short) 0 ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public short put(K key, short value) {
        int i = key.ordinal();
        short old = vals[i];
        vals[i] = value;
        if (value == (short) 0) {
            if (old != (short) 0) size--;
        } else if (old == (short) 0) {
            size++;
        }
        return old != (short) 0 ? old : defRetValue;
    }

    @Override
    public short removeShort(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        short old = vals[i];
        if (old == (short) 0) return defRetValue;
        vals[i] = (short) 0;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != (short) 0;
    }

    @Override
    public boolean containsValue(short value) {
        if (value == (short) 0) return false;
        final var vals = Enum2ShortMap.this.vals;
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
        Arrays.fill(vals, (short) 0);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Short> action) {
        final var vals = Enum2ShortMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != (short) 0) action.accept(keys[i], Short.valueOf(vals[i]));
        }
    }

    @Override
    public FastEntrySet<K> reference2ShortEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public ShortCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public short putIfAbsent(K key, short value) {
        int i = key.ordinal();
        short old = vals[i];
        if (old != (short) 0) return old;
        if (value != (short) 0) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, short value) {
        if (value == (short) 0 || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = (short) 0;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, short oldValue, short newValue) {
        if (oldValue == (short) 0 || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public short replace(K key, short value) {
        short old = vals[key.ordinal()];
        if (old == (short) 0) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public short computeIfAbsent(K key, java.util.function.ToIntFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        short v = vals[i];
        if (v != (short) 0) return v;
        short newValue = (short) mappingFunction.applyAsInt(key);
        if (newValue != (short) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public short computeIfAbsent(K key, Reference2ShortFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        short v = vals[i];
        if (v != (short) 0) return v;
        short newValue = mappingFunction.getShort(key);
        if (newValue != (short) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public short computeShortIfPresent(K key, java.util.function.BiFunction<? super K, ? super Short, ? extends Short> remappingFunction) {
        int i = key.ordinal();
        short old = vals[i];
        if (old == (short) 0) return defRetValue;
        Short newValue = remappingFunction.apply(key, Short.valueOf(old));
        if (newValue == null) {
            vals[i] = (short) 0;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public short computeShort(K key, java.util.function.BiFunction<? super K, ? super Short, ? extends Short> remappingFunction) {
        int i = key.ordinal();
        short old = vals[i];
        Short newValue = remappingFunction.apply(key, old == (short) 0 ? null : Short.valueOf(old));
        if (newValue == null) {
            if (old != (short) 0) {
                vals[i] = (short) 0;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code (short) 0}. A sum of {@code (short) 0} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public short addTo(K key, short incr) {
        int i = key.ordinal();
        short old = vals[i];
        if (old == (short) 0) {
            if (incr == (short) 0) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        short sum = (short) (old + incr);
        vals[i] = sum;
        if (sum == (short) 0) size--;
        return old;
    }

    @Override
    public short mergeShort(K key, short value, ShortBinaryOperator remappingFunction) {
        int i = key.ordinal();
        short old = vals[i];
        short newValue = old == (short) 0 ? value : remappingFunction.apply(old, value);
        if (newValue == (short) 0) {
            if (old != (short) 0) {
                vals[i] = (short) 0;
                size--;
            }
            return defRetValue;
        }
        if (old == (short) 0) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2ShortMap.Entry<K>>
            implements Reference2ShortMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2ShortMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2ShortMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /**
         * Feeds a single reused entry to {@code consumer}: no per-entry allocation.
         */
        @Override
        public void fastForEach(Consumer<? super Reference2ShortMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2ShortMap.this.vals;
            final int length = vals.length;
            for (int i = 0; i < length; i++) {
                if (vals[i] != (short) 0) {
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
            Enum2ShortMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != (short) 0 && Short.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeShort(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /**
     * A reused, index-backed entry; valid only while its iterator stands still.
     */
    private final class Entry implements Reference2ShortMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public short getShortValue() {
            return vals[index];
        }

        @Override
        public short setValue(short value) {
            short old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return e.getKey() == keys[index] && Short.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Short.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Short.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2ShortMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == (short) 0) {
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
            Enum2ShortMap.this.put(keys[current], (short) 0);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2ShortMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2ShortMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements ShortIterator {

        @Override
        public short nextShort() {
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
            Enum2ShortMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2ShortMap.this.removeShort(o);
            return true;
        }
    }

    private final class Values extends AbstractShortCollection {

        @Override
        public ShortIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(short value) {
            return Enum2ShortMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2ShortMap.this.clear();
        }
    }
}
