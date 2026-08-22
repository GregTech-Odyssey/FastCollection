package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2LongMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2LongFunction;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.AbstractLongCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2LongMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getLong}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code 0L} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(long)}. When zero is a meaningful value, use
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
public final class Enum2LongMap<K extends Enum<K>> extends AbstractReference2LongMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final long[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final LongCollection values = new Values();

    /** Creates an empty map able to hold every constant of {@code keyType}. */
    public Enum2LongMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new long[keys.length];
    }

    /** Creates a copy of {@code map}. */
    public Enum2LongMap(Enum2LongMap<K> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /** The enum type this map is indexed by. */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public long getLong(Object key) {
        if (!isValidKey(key)) return defRetValue;
        long v = vals[((Enum<?>) key).ordinal()];
        return v != 0L ? v : defRetValue;
    }

    @Override
    public long getOrDefault(Object key, long defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        long v = vals[((Enum<?>) key).ordinal()];
        return v != 0L ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public long put(K key, long value) {
        int i = key.ordinal();
        long old = vals[i];
        vals[i] = value;
        if (value == 0L) {
            if (old != 0L) size--;
        } else if (old == 0L) {
            size++;
        }
        return old != 0L ? old : defRetValue;
    }

    @Override
    public long removeLong(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        long old = vals[i];
        if (old == 0L) return defRetValue;
        vals[i] = 0L;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != 0L;
    }

    @Override
    public boolean containsValue(long value) {
        if (value == 0L) return false;
        final var vals = Enum2LongMap.this.vals;
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
        Arrays.fill(vals, 0L);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Long> action) {
        final var vals = Enum2LongMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != 0L) action.accept(keys[i], Long.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2LongMap.Entry<K>> reference2LongEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public LongCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public long putIfAbsent(K key, long value) {
        int i = key.ordinal();
        long old = vals[i];
        if (old != 0L) return old;
        if (value != 0L) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, long value) {
        if (value == 0L || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = 0L;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, long oldValue, long newValue) {
        if (oldValue == 0L || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public long replace(K key, long value) {
        long old = vals[key.ordinal()];
        if (old == 0L) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public long computeIfAbsent(K key, java.util.function.ToLongFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        long v = vals[i];
        if (v != 0L) return v;
        long newValue = (long) mappingFunction.applyAsLong(key);
        if (newValue != 0L) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public long computeIfAbsent(K key, Reference2LongFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        long v = vals[i];
        if (v != 0L) return v;
        long newValue = mappingFunction.getLong(key);
        if (newValue != 0L) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public long computeLongIfPresent(K key, java.util.function.BiFunction<? super K, ? super Long, ? extends Long> remappingFunction) {
        int i = key.ordinal();
        long old = vals[i];
        if (old == 0L) return defRetValue;
        Long newValue = remappingFunction.apply(key, Long.valueOf(old));
        if (newValue == null) {
            vals[i] = 0L;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public long computeLong(K key, java.util.function.BiFunction<? super K, ? super Long, ? extends Long> remappingFunction) {
        int i = key.ordinal();
        long old = vals[i];
        Long newValue = remappingFunction.apply(key, old == 0L ? null : Long.valueOf(old));
        if (newValue == null) {
            if (old != 0L) {
                vals[i] = 0L;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code 0L}. A sum of {@code 0L} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public long addTo(K key, long incr) {
        int i = key.ordinal();
        long old = vals[i];
        if (old == 0L) {
            if (incr == 0L) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        long sum = (long) (old + incr);
        vals[i] = sum;
        if (sum == 0L) size--;
        return old;
    }

    @Override
    public long mergeLong(K key, long value, java.util.function.LongBinaryOperator remappingFunction) {
        int i = key.ordinal();
        long old = vals[i];
        long newValue = old == 0L ? value : remappingFunction.applyAsLong(old, value);
        if (newValue == 0L) {
            if (old != 0L) {
                vals[i] = 0L;
                size--;
            }
            return defRetValue;
        }
        if (old == 0L) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2LongMap.Entry<K>>
            implements Reference2LongMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2LongMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2LongMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /** Feeds a single reused entry to {@code consumer}: no per-entry allocation. */
        @Override
        public void fastForEach(Consumer<? super Reference2LongMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2LongMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
                if (vals[i] != 0L) {
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
            Enum2LongMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != 0L && Long.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeLong(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /** A reused, index-backed entry; valid only while its iterator stands still. */
    private final class Entry implements Reference2LongMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public long getLongValue() {
            return vals[index];
        }

        @Override
        public long setValue(long value) {
            long old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            return e.getKey() == keys[index] && Long.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Long.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Long.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2LongMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == 0L) {
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
            Enum2LongMap.this.put(keys[current], 0L);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2LongMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2LongMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements LongIterator {

        @Override
        public long nextLong() {
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
            Enum2LongMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2LongMap.this.removeLong(o);
            return true;
        }
    }

    private final class Values extends AbstractLongCollection {

        @Override
        public LongIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(long value) {
            return Enum2LongMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2LongMap.this.clear();
        }
    }
}
