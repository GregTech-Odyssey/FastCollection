package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2DoubleMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2DoubleFunction;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.doubles.DoubleCollection;
import it.unimi.dsi.fastutil.doubles.DoubleIterator;
import it.unimi.dsi.fastutil.doubles.AbstractDoubleCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2DoubleMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getDouble}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code 0D} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(double)}. When zero is a meaningful value, use
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
public final class Enum2DoubleMap<K extends Enum<K>> extends AbstractReference2DoubleMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final double[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final DoubleCollection values = new Values();

    /** Creates an empty map able to hold every constant of {@code keyType}. */
    public Enum2DoubleMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new double[keys.length];
    }

    /** Creates a copy of {@code map}. */
    public Enum2DoubleMap(Enum2DoubleMap<K> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /** The enum type this map is indexed by. */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public double getDouble(Object key) {
        if (!isValidKey(key)) return defRetValue;
        double v = vals[((Enum<?>) key).ordinal()];
        return v != 0D ? v : defRetValue;
    }

    @Override
    public double getOrDefault(Object key, double defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        double v = vals[((Enum<?>) key).ordinal()];
        return v != 0D ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public double put(K key, double value) {
        int i = key.ordinal();
        double old = vals[i];
        vals[i] = value;
        if (value == 0D) {
            if (old != 0D) size--;
        } else if (old == 0D) {
            size++;
        }
        return old != 0D ? old : defRetValue;
    }

    @Override
    public double removeDouble(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        double old = vals[i];
        if (old == 0D) return defRetValue;
        vals[i] = 0D;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != 0D;
    }

    @Override
    public boolean containsValue(double value) {
        if (value == 0D) return false;
        final var vals = Enum2DoubleMap.this.vals;
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
        Arrays.fill(vals, 0D);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Double> action) {
        final var vals = Enum2DoubleMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != 0D) action.accept(keys[i], Double.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2DoubleMap.Entry<K>> reference2DoubleEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public DoubleCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public double putIfAbsent(K key, double value) {
        int i = key.ordinal();
        double old = vals[i];
        if (old != 0D) return old;
        if (value != 0D) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, double value) {
        if (value == 0D || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = 0D;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, double oldValue, double newValue) {
        if (oldValue == 0D || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public double replace(K key, double value) {
        double old = vals[key.ordinal()];
        if (old == 0D) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public double computeIfAbsent(K key, java.util.function.ToDoubleFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        double v = vals[i];
        if (v != 0D) return v;
        double newValue = (double) mappingFunction.applyAsDouble(key);
        if (newValue != 0D) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public double computeIfAbsent(K key, Reference2DoubleFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        double v = vals[i];
        if (v != 0D) return v;
        double newValue = mappingFunction.getDouble(key);
        if (newValue != 0D) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public double computeDoubleIfPresent(K key, java.util.function.BiFunction<? super K, ? super Double, ? extends Double> remappingFunction) {
        int i = key.ordinal();
        double old = vals[i];
        if (old == 0D) return defRetValue;
        Double newValue = remappingFunction.apply(key, Double.valueOf(old));
        if (newValue == null) {
            vals[i] = 0D;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public double computeDouble(K key, java.util.function.BiFunction<? super K, ? super Double, ? extends Double> remappingFunction) {
        int i = key.ordinal();
        double old = vals[i];
        Double newValue = remappingFunction.apply(key, old == 0D ? null : Double.valueOf(old));
        if (newValue == null) {
            if (old != 0D) {
                vals[i] = 0D;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code 0D}. A sum of {@code 0D} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public double addTo(K key, double incr) {
        int i = key.ordinal();
        double old = vals[i];
        if (old == 0D) {
            if (incr == 0D) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        double sum = (double) (old + incr);
        vals[i] = sum;
        if (sum == 0D) size--;
        return old;
    }

    @Override
    public double mergeDouble(K key, double value, java.util.function.DoubleBinaryOperator remappingFunction) {
        int i = key.ordinal();
        double old = vals[i];
        double newValue = old == 0D ? value : remappingFunction.applyAsDouble(old, value);
        if (newValue == 0D) {
            if (old != 0D) {
                vals[i] = 0D;
                size--;
            }
            return defRetValue;
        }
        if (old == 0D) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2DoubleMap.Entry<K>>
            implements Reference2DoubleMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2DoubleMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2DoubleMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /** Feeds a single reused entry to {@code consumer}: no per-entry allocation. */
        @Override
        public void fastForEach(Consumer<? super Reference2DoubleMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2DoubleMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
                if (vals[i] != 0D) {
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
            Enum2DoubleMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != 0D && Double.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeDouble(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /** A reused, index-backed entry; valid only while its iterator stands still. */
    private final class Entry implements Reference2DoubleMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public double getDoubleValue() {
            return vals[index];
        }

        @Override
        public double setValue(double value) {
            double old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            return e.getKey() == keys[index] && Double.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Double.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Double.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2DoubleMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == 0D) {
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
            Enum2DoubleMap.this.put(keys[current], 0D);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2DoubleMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2DoubleMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements DoubleIterator {

        @Override
        public double nextDouble() {
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
            Enum2DoubleMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2DoubleMap.this.removeDouble(o);
            return true;
        }
    }

    private final class Values extends AbstractDoubleCollection {

        @Override
        public DoubleIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(double value) {
            return Enum2DoubleMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2DoubleMap.this.clear();
        }
    }
}
