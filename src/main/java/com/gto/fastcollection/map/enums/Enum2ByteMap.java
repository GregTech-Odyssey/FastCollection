package com.gto.fastcollection.map.enums;

import com.gto.fastcollection.util.EnumKeys;
import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.AbstractReference2ByteMap;
import it.unimi.dsi.fastutil.objects.AbstractReferenceSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2ByteFunction;
import it.unimi.dsi.fastutil.objects.Reference2ByteMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.bytes.ByteBinaryOperator;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.bytes.AbstractByteCollection;

import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A {@link Reference2ByteMap} with enum keys, indexed by key ordinal exactly
 * like {@link java.util.EnumMap}: every operation is a single array access
 * with no hashing, and the {@code getByte}/put/remove paths never autobox.
 *
 * <p>Like {@code EnumMap}, a single value array decides presence: the
 * primitive zero value {@code (byte) 0} marks an empty slot (the analogue of
 * {@code EnumMap}'s {@code null}), so <b>storing the zero value removes the
 * mapping</b>, and {@link #containsKey} distinguishes presence from absence.
 * Reads of absent keys return {@link #defRetValue}, which defaults
 * to the zero value and can be changed with
 * {@link #defaultReturnValue(byte)}. When zero is a meaningful value, use
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
public final class Enum2ByteMap<K extends Enum<K>> extends AbstractReference2ByteMap<K> {

    private final Class<K> keyType;
    private final K[] keys;
    private final byte[] vals;
    private int size;

    private final EntrySet entrySet = new EntrySet();
    private final ReferenceSet<K> keySet = new KeySet();
    private final ByteCollection values = new Values();

    /** Creates an empty map able to hold every constant of {@code keyType}. */
    public Enum2ByteMap(Class<K> keyType) {
        this.keyType = keyType;
        this.keys = EnumKeys.universe(keyType);
        this.vals = new byte[keys.length];
    }

    /** Creates a copy of {@code map}. */
    public Enum2ByteMap(Enum2ByteMap<K> map) {
        this(map.keyType);
        System.arraycopy(map.vals, 0, vals, 0, vals.length);
        this.size = map.size;
    }

    /** The enum type this map is indexed by. */
    public Class<K> keyType() {
        return keyType;
    }

    @Override
    public byte getByte(Object key) {
        if (!isValidKey(key)) return defRetValue;
        byte v = vals[((Enum<?>) key).ordinal()];
        return v != (byte) 0 ? v : defRetValue;
    }

    @Override
    public byte getOrDefault(Object key, byte defaultValue) {
        if (!isValidKey(key)) return defaultValue;
        byte v = vals[((Enum<?>) key).ordinal()];
        return v != (byte) 0 ? v : defaultValue;
    }

    /**
     * Binds {@code value} to {@code key}. Storing the zero value removes the
     * mapping (single-array presence, like {@code EnumMap}).
     *
     * @return the previous value, or {@link #defRetValue} if absent
     */
    @Override
    public byte put(K key, byte value) {
        int i = key.ordinal();
        byte old = vals[i];
        vals[i] = value;
        if (value == (byte) 0) {
            if (old != (byte) 0) size--;
        } else if (old == (byte) 0) {
            size++;
        }
        return old != (byte) 0 ? old : defRetValue;
    }

    @Override
    public byte removeByte(Object key) {
        if (!isValidKey(key)) return defRetValue;
        int i = ((Enum<?>) key).ordinal();
        byte old = vals[i];
        if (old == (byte) 0) return defRetValue;
        vals[i] = (byte) 0;
        size--;
        return old;
    }

    @Override
    public boolean containsKey(Object key) {
        return isValidKey(key) && vals[((Enum<?>) key).ordinal()] != (byte) 0;
    }

    @Override
    public boolean containsValue(byte value) {
        if (value == (byte) 0) return false;
        final var vals = Enum2ByteMap.this.vals;
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
        Arrays.fill(vals, (byte) 0);
        size = 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super Byte> action) {
        final var vals = Enum2ByteMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
            if (vals[i] != (byte) 0) action.accept(keys[i], Byte.valueOf(vals[i]));
        }
    }

    @Override
    public ObjectSet<Reference2ByteMap.Entry<K>> reference2ByteEntrySet() {
        return entrySet;
    }

    @Override
    public ReferenceSet<K> keySet() {
        return keySet;
    }

    @Override
    public ByteCollection values() {
        return values;
    }

    /**
     * Binds {@code value} to {@code key} only if the key is absent.
     *
     * @return the existing value, or {@code defRetValue} if the key was absent
     */
    @Override
    public byte putIfAbsent(K key, byte value) {
        int i = key.ordinal();
        byte old = vals[i];
        if (old != (byte) 0) return old;
        if (value != (byte) 0) {
            vals[i] = value;
            size++;
        }
        return defRetValue;
    }

    @Override
    public boolean remove(Object key, byte value) {
        if (value == (byte) 0 || !isValidKey(key)) return false;
        int i = ((Enum<?>) key).ordinal();
        if (vals[i] != value) return false;
        vals[i] = (byte) 0;
        size--;
        return true;
    }

    @Override
    public boolean replace(K key, byte oldValue, byte newValue) {
        if (oldValue == (byte) 0 || vals[key.ordinal()] != oldValue) return false;
        put(key, newValue);
        return true;
    }

    @Override
    public byte replace(K key, byte value) {
        byte old = vals[key.ordinal()];
        if (old == (byte) 0) return defRetValue;
        put(key, value);
        return old;
    }

    @Override
    public byte computeIfAbsent(K key, java.util.function.ToIntFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        byte v = vals[i];
        if (v != (byte) 0) return v;
        byte newValue = (byte) mappingFunction.applyAsInt(key);
        if (newValue != (byte) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public byte computeIfAbsent(K key, Reference2ByteFunction<? super K> mappingFunction) {
        int i = key.ordinal();
        byte v = vals[i];
        if (v != (byte) 0) return v;
        byte newValue = mappingFunction.getByte(key);
        if (newValue != (byte) 0) {
            vals[i] = newValue;
            size++;
        }
        return newValue;
    }

    @Override
    public byte computeByteIfPresent(K key, java.util.function.BiFunction<? super K, ? super Byte, ? extends Byte> remappingFunction) {
        int i = key.ordinal();
        byte old = vals[i];
        if (old == (byte) 0) return defRetValue;
        Byte newValue = remappingFunction.apply(key, Byte.valueOf(old));
        if (newValue == null) {
            vals[i] = (byte) 0;
            size--;
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    @Override
    public byte computeByte(K key, java.util.function.BiFunction<? super K, ? super Byte, ? extends Byte> remappingFunction) {
        int i = key.ordinal();
        byte old = vals[i];
        Byte newValue = remappingFunction.apply(key, old == (byte) 0 ? null : Byte.valueOf(old));
        if (newValue == null) {
            if (old != (byte) 0) {
                vals[i] = (byte) 0;
                size--;
            }
            return defRetValue;
        }
        put(key, newValue);
        return newValue;
    }

    /**
     * Adds {@code incr} to the value of {@code key} and stores the sum; an
     * absent key starts from {@code (byte) 0}. A sum of {@code (byte) 0} removes the
     * mapping (the zero-value sentinel).
     *
     * @return the previous value, or {@code defRetValue} if the key was absent
     */
    public byte addTo(K key, byte incr) {
        int i = key.ordinal();
        byte old = vals[i];
        if (old == (byte) 0) {
            if (incr == (byte) 0) return defRetValue;
            vals[i] = incr;
            size++;
            return defRetValue;
        }
        byte sum = (byte) (old + incr);
        vals[i] = sum;
        if (sum == (byte) 0) size--;
        return old;
    }

    @Override
    public byte mergeByte(K key, byte value, ByteBinaryOperator remappingFunction) {
        int i = key.ordinal();
        byte old = vals[i];
        byte newValue = old == (byte) 0 ? value : remappingFunction.apply(old, value);
        if (newValue == (byte) 0) {
            if (old != (byte) 0) {
                vals[i] = (byte) 0;
                size--;
            }
            return defRetValue;
        }
        if (old == (byte) 0) size++;
        vals[i] = newValue;
        return newValue;
    }

    private boolean isValidKey(Object key) {
        if (key == null) return false;
        Class<?> keyClass = key.getClass();
        return keyClass == keyType || keyClass.getSuperclass() == keyType;
    }

    private final class EntrySet extends AbstractObjectSet<Reference2ByteMap.Entry<K>>
            implements Reference2ByteMap.FastEntrySet<K> {

        @Override
        public ObjectIterator<Reference2ByteMap.Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Reference2ByteMap.Entry<K>> fastIterator() {
            return new EntryIterator();
        }

        /** Feeds a single reused entry to {@code consumer}: no per-entry allocation. */
        @Override
        public void fastForEach(Consumer<? super Reference2ByteMap.Entry<K>> consumer) {
            Entry entry = new Entry();
            final var vals = Enum2ByteMap.this.vals;
        final int length = vals.length;
        for (int i = 0; i < length; i++) {
                if (vals[i] != (byte) 0) {
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
            Enum2ByteMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            if (!isValidKey(e.getKey())) return false;
            int i = ((Enum<?>) e.getKey()).ordinal();
            return vals[i] != (byte) 0 && Byte.valueOf(vals[i]).equals(e.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!contains(o)) return false;
            removeByte(((Map.Entry<?, ?>) o).getKey());
            return true;
        }
    }

    /** A reused, index-backed entry; valid only while its iterator stands still. */
    private final class Entry implements Reference2ByteMap.Entry<K> {

        private int index = -1;

        @Override
        public K getKey() {
            return keys[index];
        }

        @Override
        public byte getByteValue() {
            return vals[index];
        }

        @Override
        public byte setValue(byte value) {
            byte old = vals[index];
            put(keys[index], value);
            return old;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            var e = (Map.Entry<?, ?>) o;
            return e.getKey() == keys[index] && Byte.valueOf(vals[index]).equals(e.getValue());
        }

        @Override
        public int hashCode() {
            return keys[index].hashCode() ^ Byte.valueOf(vals[index]).hashCode();
        }

        @Override
        public String toString() {
            return keys[index] + "=" + Byte.valueOf(vals[index]);
        }
    }

    private abstract class IndexIterator {

        int cursor;
        int current = -1;

        public boolean hasNext() {
            final var vals = Enum2ByteMap.this.vals;
            final int length = vals.length;
            while (cursor < length && vals[cursor] == (byte) 0) {
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
            Enum2ByteMap.this.put(keys[current], (byte) 0);
            current = -1;
        }
    }

    private final class EntryIterator extends IndexIterator implements ObjectIterator<Reference2ByteMap.Entry<K>> {

        private final Entry entry = new Entry();

        @Override
        public Reference2ByteMap.Entry<K> next() {
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

    private final class ValueIterator extends IndexIterator implements ByteIterator {

        @Override
        public byte nextByte() {
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
            Enum2ByteMap.this.clear();
        }

        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            if (!containsKey(o)) return false;
            Enum2ByteMap.this.removeByte(o);
            return true;
        }
    }

    private final class Values extends AbstractByteCollection {

        @Override
        public ByteIterator iterator() {
            return new ValueIterator();
        }

        @Override
        public boolean contains(byte value) {
            return Enum2ByteMap.this.containsValue(value);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            Enum2ByteMap.this.clear();
        }
    }
}
