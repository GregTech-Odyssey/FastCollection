package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2FloatMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2FloatFunction;
import it.unimi.dsi.fastutil.objects.Reference2FloatMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.floats.FloatBinaryOperator;
import it.unimi.dsi.fastutil.floats.FloatCollection;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import it.unimi.dsi.fastutil.floats.AbstractFloatCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2FloatMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getFloat}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code 0F} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(float)}. When zero is a meaningful value, use
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
public final class Enum2FloatMap<K extends Enum<K>> extends AbstractReference2FloatMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final float[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final FloatCollection values = new Values();

    /**
     * Creates an empty map able to hold every constant of {@code keyType}.
     */
    public Enum2FloatMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new float[keys.length];
    }

    /**
     * Creates a copy of {@code map}.
     */
    public Enum2FloatMap(Enum2FloatMap<K> map) {
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
    public float getFloat(Object key) {
        if (!isValidKey(key)) return defRetValue;
        float v = vals[((Enum<?>) key).ordinal()];
        return v != 0F ? v : defRetValue;
    }

    @Override
    public float getOrDefault(Object key, float defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        float v = vals[((Enum<?>) key).ordinal()];
        return v != 0F ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public float put(K key, float value) {
        int i = key.ordinal();
        float old = vals[i];
        vals[i] = value;
        if (value == 0F) {
            if (old != 0F) size--;
        } else if (old == 0F) {
            size++;
        }
        return old != 0F ? old : defRetValue;
    }

    @Override
    public float removeFloat(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        float old = vals[i];
        if (old == 0F) return defRetValue;
        vals[i] = 0F;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != 0F;
    }

    @Override
    public boolean containsValue(float value) {
        if (value == 0F) return false;
        final var vals = Enum2FloatMap.this.vals;
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
        Arrays.fill(vals, 0F);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Float> action) {
        final var vals = Enum2FloatMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != 0F) action.accept(keys[i], Float.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2FloatMap.Entry<K>> reference2FloatEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public FloatCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public float putIfAbsent(K key, float value) {
        int i = key.ordinal();
        float old = vals[i];
        if (old != 0F) return old;
        if (value != 0F) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, float value) {
        if (value == 0F || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = 0F;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, float oldValue, float newValue) {
        if (oldValue == 0F || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public float replace(K key, float value) {
        float old = vals[key.ordinal()];
        if (old == 0F) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public float computeIfAbsent(K key, java.util.function.ToDoubleFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        float v = vals[i];
        if (v != 0F) return v;
        float newValue = (float) mappingFunction.applyAsDouble(key);
        if (newValue != 0F) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public float computeIfAbsent(K key, Reference2FloatFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        float v = vals[i];
        if (v != 0F) return v;
        float newValue = mappingFunction.getFloat(key);
        if (newValue != 0F) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public float computeFloatIfPresent(K key, java.util.function.BiFunction<? super K, ? super Float, ? extends Float> remappingFunction) {
        int i = key.ordinal();
        float old = vals[i];
        if (old == 0F) return defRetValue;
        Float newValue = remappingFunction.apply(key, Float.valueOf(old));
        if (newValue == null) {
            vals[i] = 0F;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public float computeFloat(K key, java.util.function.BiFunction<? super K, ? super Float, ? extends Float> remappingFunction) {
        int i = key.ordinal();
        float old = vals[i];
        Float newValue = remappingFunction.apply(key, old == 0F ? null : Float.valueOf(old));
        if (newValue == null) {
            if (old != 0F) {
                vals[i] = 0F;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code 0F}. A sum of {@code 0F} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public float addTo(K key, float incr) {
        int i = key.ordinal();
        float old = vals[i];
        if (old == 0F) {
            if (incr == 0F) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        float sum = old + incr;
        vals[i] = sum;
        if (sum == 0F) size--;
        return old;
    }

    @Override
    public float mergeFloat(K key, float value, FloatBinaryOperator remappingFunction) {
        int i = key.ordinal();
        float old = vals[i];
        float newValue = old == 0F ? value : remappingFunction.apply(old, value);
        if (newValue == 0F) {
            if (old != 0F) {
                vals[i] = 0F;
                size--;
            }
            return defRetValue;
        }
        if (old == 0F) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2FloatMap.Entry<K>>
            implements Reference2FloatMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2FloatMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2FloatMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /**
         * Feeds a single reused entry to {@code consumer}: no per-entry allocation.
         */
        @Override
        public void fastForEach(Consumer<? super Reference2FloatMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2FloatMap.this.vals;
            final int length = vals.length;
            for (int i = 0; i < length; i++) {
                if (vals[i] != 0F) {
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
            Enum2FloatMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != 0F && Float.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeFloat(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /**
     * A reused, index-backed entry; valid only while its iterator stands still.
     */
    private final class Entry implements Reference2FloatMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public float getFloatValue() {
            return vals[index];
        }

        @Override
        public float setValue(float value) {
            float old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            return e.getKey() == keys[index] && Float.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Float.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Float.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2FloatMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == 0F) {
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
            Enum2FloatMap.this.put(keys[current], 0F);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2FloatMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2FloatMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements FloatIterator {

        @Override
        public float nextFloat() {
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
            Enum2FloatMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2FloatMap.this.removeFloat(o);
            return true;
        }
    }

    private final class Values extends AbstractFloatCollection {

        @Override
        public FloatIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(float value) {
            return Enum2FloatMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2FloatMap.this.clear();
        }
    }
}
