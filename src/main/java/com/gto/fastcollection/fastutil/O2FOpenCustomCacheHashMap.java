package com.gto.fastcollection.fastutil;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.floats.*;
import it.unimi.dsi.fastutil.objects.*;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.maxFill;

public final class O2FOpenCustomCacheHashMap<K> extends Object2FloatOpenCustomHashMap<K> {

    private int[] hash;

    public O2FOpenCustomCacheHashMap(final int expected, final float f, final Strategy<? super K> strategy) {
        super(expected, f, strategy);
        hash = new int[n + 1];
    }

    public O2FOpenCustomCacheHashMap(final int expected, final Strategy<? super K> strategy) {
        super(expected, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2FOpenCustomCacheHashMap(final Strategy<? super K> strategy) {
        super(DEFAULT_INITIAL_SIZE, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2FOpenCustomCacheHashMap(final Map<? extends K, ? extends Float> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2FOpenCustomCacheHashMap(final Map<? extends K, ? extends Float> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    public O2FOpenCustomCacheHashMap(final Object2FloatMap<K> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2FOpenCustomCacheHashMap(final Object2FloatMap<K> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    private int realSize() {
        return containsNullKey ? size - 1 : size;
    }

    private float removeEntry(int pos) {
        final float oldValue = value[pos];
        size--;
        int last, slot, ch;
        K curr;
        final K[] key = this.key;
        final float[] value = this.value;
        final int[] hash = this.hash;
        final int mask = this.mask;
        a:
        for (; ; ) {
            pos = ((last = pos) + 1) & mask;
            for (; ; ) {
                if ((curr = key[pos]) == null) {
                    key[last] = null;
                    value[last] = 0f;
                    hash[last] = 0;
                    break a;
                }
                ch = hash[pos];
                slot = HashCommon.mix(ch) & mask;
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) break;
                pos = (pos + 1) & mask;
            }
            key[last] = curr;
            value[last] = value[pos];
            hash[last] = ch;
        }
        if (n > minN && size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) rehash(n / 2);
        return oldValue;
    }

    private float removeNullEntry() {
        containsNullKey = false;
        key[n] = null;
        hash[n] = 0;
        final float oldValue = value[n];
        size--;
        if (n > minN && size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) rehash(n / 2);
        return oldValue;
    }

    private int find(final K k, final int h) {
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return -(pos + 1);
        if (hash[pos] == h && (k == curr || strategy.equals(k, curr))) return pos;
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return -(pos + 1);
            if (hash[pos] == h && (k == curr || strategy.equals(k, curr))) return pos;
        }
    }

    private void insert(final int pos, final K k, final float v, final int h) {
        if (pos == n) containsNullKey = true;
        key[pos] = k;
        value[pos] = v;
        hash[pos] = h;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
    }

    @Override
    public float put(final K k, final float v) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) {
            insert(-pos - 1, k, v, h);
            return defRetValue;
        }
        final float oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }

    private float addToValue(final int pos, final float incr) {
        final float oldValue = value[pos];
        value[pos] = oldValue + incr;
        return oldValue;
    }

    @Override
    public float addTo(final K k, final float incr) {
        int pos, h = 0;
        if (k == null) {
            if (containsNullKey) return addToValue(n, incr);
            pos = n;
            containsNullKey = true;
        } else {
            h = strategy.hashCode(k);
            K curr;
            final K[] key = this.key;
            final int[] hash = this.hash;
            final int mask = this.mask;
            if ((curr = key[pos = HashCommon.mix(h) & mask]) != null) {
                do if (hash[pos] == h && (k == curr || strategy.equals(k, curr))) return addToValue(pos, incr);
                while ((curr = key[pos = (pos + 1) & mask]) != null);
            }
        }
        key[pos] = k;
        hash[pos] = h;
        value[pos] = defRetValue + incr;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
        return defRetValue;
    }

    @Override
    public float removeFloat(final Object k) {
        if (k == null) {
            if (containsNullKey) return removeNullEntry();
            return defRetValue;
        }
        K curr;
        K fk = (K) k;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        final int h = strategy.hashCode(fk);
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return defRetValue;
        if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return removeEntry(pos);
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return defRetValue;
            if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return removeEntry(pos);
        }
    }

    @Override
    public float getFloat(final Object k) {
        if (k == null) return containsNullKey ? value[n] : defRetValue;
        K fk = (K) k;
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        final int h = strategy.hashCode(fk);
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return defRetValue;
        if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return value[pos];
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return defRetValue;
            if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return value[pos];
        }
    }

    @Override
    public boolean containsKey(final Object k) {
        if (k == null) return containsNullKey;
        K fk = (K) k;
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        final int h = strategy.hashCode(fk);
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return false;
        if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return true;
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return false;
            if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return true;
        }
    }

    @Override
    public float getOrDefault(final Object k, final float defaultValue) {
        if (k == null) return containsNullKey ? value[n] : defaultValue;
        K fk = (K) k;
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        final int h = strategy.hashCode(fk);
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return defaultValue;
        if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return value[pos];
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return defaultValue;
            if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr))) return value[pos];
        }
    }

    @Override
    public float putIfAbsent(final K k, final float v) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        insert(-pos - 1, k, v, h);
        return defRetValue;
    }

    @Override
    public boolean remove(final Object k, final float v) {
        if (k == null) {
            if (containsNullKey && v == value[n]) {
                removeNullEntry();
                return true;
            }
            return false;
        }
        K fk = (K) k;
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = this.mask;
        final int h = strategy.hashCode(fk);
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return false;
        if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr)) && v == value[pos]) {
            removeEntry(pos);
            return true;
        }
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return false;
            if (hash[pos] == h && (fk == curr || strategy.equals(fk, curr)) && v == value[pos]) {
                removeEntry(pos);
                return true;
            }
        }
    }

    @Override
    public boolean replace(final K k, final float oldValue, final float v) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0 || oldValue != value[pos]) return false;
        value[pos] = v;
        return true;
    }

    @Override
    public float replace(final K k, final float v) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final float oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }

    @Override
    public float computeIfAbsent(final K k, final java.util.function.ToDoubleFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        final float newValue = it.unimi.dsi.fastutil.SafeMath.safeDoubleToFloat(mappingFunction.applyAsDouble(k));
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public float computeIfAbsent(final K k, final Object2FloatFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        if (!mappingFunction.containsKey(key)) return defRetValue;
        final float newValue = mappingFunction.getFloat(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public float computeFloatIfPresent(final K k, final BiFunction<? super K, ? super Float, ? extends Float> remappingFunction) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final Float newValue = remappingFunction.apply((k), Float.valueOf(value[pos]));
        if (newValue == null) {
            if (k == null) removeNullEntry();
            else removeEntry(pos);
            return defRetValue;
        }
        return value[pos] = newValue;
    }

    @Override
    public float computeFloat(final K k, final BiFunction<? super K, ? super Float, ? extends Float> remappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        final Float newValue = remappingFunction.apply((k), pos >= 0 ? Float.valueOf(value[pos]) : null);
        if (newValue == null) {
            if (pos >= 0) {
                if (k == null) removeNullEntry();
                else removeEntry(pos);
            }
            return defRetValue;
        }
        float newVal = newValue;
        if (pos < 0) {
            insert(-pos - 1, k, newVal, h);
            return newVal;
        }
        return value[pos] = newVal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float mergeFloat(final K k, final float v, it.unimi.dsi.fastutil.floats.FloatBinaryOperator remappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) {
            insert(-pos - 1, k, v, h);
            return v;
        }
        final float newValue = remappingFunction.apply(value[pos], v);
        return value[pos] = newValue;
    }

    @Override
    public float merge(final K k, final float v, final BiFunction<? super Float, ? super Float, ? extends Float> remappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) {
            insert(-pos - 1, k, v, h);
            return v;
        }
        final Float newValue = remappingFunction.apply(Float.valueOf(value[pos]), Float.valueOf(v));
        if (newValue == null) {
            if (k == null) removeNullEntry();
            else removeEntry(pos);
            return defRetValue;
        }
        return value[pos] = newValue;
    }

    @Override
    public void clear() {
        if (size == 0) return;
        size = 0;
        containsNullKey = false;
        Arrays.fill(key, (null));
        Arrays.fill(hash, 0);
    }

    final class MapEntry implements Entry<K>, ObjectFloatPair<K> {

        int index;

        MapEntry(final int index) {
            this.index = index;
        }

        MapEntry() {
        }

        @Override
        public K getKey() {
            return key[index];
        }

        @Override
        public K left() {
            return key[index];
        }

        @Override
        public float getFloatValue() {
            return value[index];
        }

        @Override
        public float rightFloat() {
            return value[index];
        }

        @Override
        public float setValue(final float v) {
            final float oldValue = value[index];
            value[index] = v;
            return oldValue;
        }

        @Override
        public ObjectFloatPair<K> right(final float v) {
            value[index] = v;
            return this;
        }

        @Deprecated
        @Override
        public Float getValue() {
            return value[index];
        }

        @Deprecated
        @Override
        public Float setValue(final Float v) {
            return Float.valueOf(setValue((v).floatValue()));
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry<K, Float> e = (Map.Entry<K, Float>) o;
            return strategy.equals(key[index], (e.getKey())) && (Float.floatToRawIntBits(value[index]) == Float.floatToRawIntBits((e.getValue()).floatValue()));
        }

        @Override
        public int hashCode() {
            return hash[index] ^ it.unimi.dsi.fastutil.HashCommon.float2int(value[index]);
        }

        @Override
        public String toString() {
            return key[index] + "=>" + value[index];
        }
    }

    private abstract class MapIterator<ConsumerType> {

        int pos = n;
        int last = -1;
        int c = size;
        boolean mustReturnNullKey = O2FOpenCustomCacheHashMap.this.containsNullKey;
        ObjectArrayList<K> wrapped;

        abstract void acceptOnIndex(final ConsumerType action, final int index);

        public boolean hasNext() {
            return c != 0;
        }

        public int nextEntry() {
            c--;
            if (mustReturnNullKey) {
                mustReturnNullKey = false;
                return last = n;
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            final int[] hash = O2FOpenCustomCacheHashMap.this.hash;
            final int mask = O2FOpenCustomCacheHashMap.this.mask;
            for (; ; ) {
                if (--pos < 0) {
                    last = Integer.MIN_VALUE;
                    final K k = wrapped.get(-pos - 1);
                    final int h = strategy.hashCode(k);
                    int p = HashCommon.mix(h) & mask;
                    while (!(hash[p] == h && strategy.equals(key[p], k))) p = (p + 1) & mask;
                    return p;
                }
                if (!((key[pos]) == null)) return last = pos;
            }
        }

        public void forEachRemaining(final ConsumerType action) {
            if (mustReturnNullKey) {
                mustReturnNullKey = false;
                acceptOnIndex(action, last = n);
                c--;
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            final int[] hash = O2FOpenCustomCacheHashMap.this.hash;
            while (c != 0) {
                if (--pos < 0) {
                    last = Integer.MIN_VALUE;
                    final K k = wrapped.get(-pos - 1);
                    final int h = strategy.hashCode(k);
                    int p = HashCommon.mix(h) & mask;
                    while (!(hash[p] == h && strategy.equals(key[p], k))) p = (p + 1) & mask;
                    acceptOnIndex(action, p);
                    c--;
                } else if (!((key[pos]) == null)) {
                    acceptOnIndex(action, last = pos);
                    c--;
                }
            }
        }

        private void shiftKeys(int pos) {
            int last, slot, ch;
            K curr;
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            final float[] value = O2FOpenCustomCacheHashMap.this.value;
            final int[] hash = O2FOpenCustomCacheHashMap.this.hash;
            final int mask = O2FOpenCustomCacheHashMap.this.mask;
            for (; ; ) {
                pos = ((last = pos) + 1) & mask;
                for (; ; ) {
                    if ((curr = key[pos]) == null) {
                        key[last] = null;
                        hash[last] = 0;
                        return;
                    }
                    ch = hash[pos];
                    slot = HashCommon.mix(ch) & mask;
                    if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) break;
                    pos = (pos + 1) & mask;
                }
                if (pos < last) {
                    if (wrapped == null) wrapped = new ObjectArrayList<>(2);
                    wrapped.add(key[pos]);
                }
                key[last] = curr;
                value[last] = value[pos];
                hash[last] = ch;
            }
        }

        public void remove() {
            if (last == n) {
                containsNullKey = false;
                key[n] = null;
                hash[n] = 0;
            } else if (pos >= 0) shiftKeys(last);
            else {
                O2FOpenCustomCacheHashMap.this.removeFloat(wrapped.set(-pos - 1, null));
                last = -1;
                return;
            }
            size--;
            last = -1;
        }

        public int skip(final int n) {
            int i = n;
            while (i-- != 0 && hasNext()) nextEntry();
            return n - i - 1;
        }
    }

    private final class EntryIterator extends MapIterator<Consumer<? super Entry<K>>> implements ObjectIterator<Entry<K>> {

        private MapEntry entry;

        @Override
        public MapEntry next() {
            return entry = new MapEntry(nextEntry());
        }

        @Override
        void acceptOnIndex(final Consumer<? super Entry<K>> action, final int index) {
            action.accept(entry = new MapEntry(index));
        }

        @Override
        public void remove() {
            super.remove();
            entry.index = -1;
        }
    }

    private final class FastEntryIterator extends MapIterator<Consumer<? super Entry<K>>> implements ObjectIterator<Entry<K>> {

        private final MapEntry entry = new MapEntry();

        @Override
        public MapEntry next() {
            entry.index = nextEntry();
            return entry;
        }

        @Override
        void acceptOnIndex(final Consumer<? super Entry<K>> action, final int index) {
            entry.index = index;
            action.accept(entry);
        }
    }

    private abstract class MapSpliterator<ConsumerType, SplitType extends MapSpliterator<ConsumerType, SplitType>> {

        int pos = 0;
        int max = n;
        int c = 0;
        boolean mustReturnNull = O2FOpenCustomCacheHashMap.this.containsNullKey;
        boolean hasSplit = false;

        MapSpliterator() {
        }

        MapSpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            this.pos = pos;
            this.max = max;
            this.mustReturnNull = mustReturnNull;
            this.hasSplit = hasSplit;
        }

        abstract void acceptOnIndex(final ConsumerType action, final int index);

        abstract SplitType makeForSplit(int pos, int max, boolean mustReturnNull);

        public boolean tryAdvance(final ConsumerType action) {
            if (mustReturnNull) {
                mustReturnNull = false;
                ++c;
                acceptOnIndex(action, n);
                return true;
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            while (pos < max) {
                if (!((key[pos]) == null)) {
                    ++c;
                    acceptOnIndex(action, pos++);
                    return true;
                }
                ++pos;
            }
            return false;
        }

        public void forEachRemaining(final ConsumerType action) {
            if (mustReturnNull) {
                mustReturnNull = false;
                ++c;
                acceptOnIndex(action, n);
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            while (pos < max) {
                if (!((key[pos]) == null)) {
                    acceptOnIndex(action, pos);
                    ++c;
                }
                ++pos;
            }
        }

        public long estimateSize() {
            if (!hasSplit) {
                return size - c;
            } else {
                return Math.min(size - c, (long) (((double) realSize() / n) * (max - pos)) + (mustReturnNull ? 1 : 0));
            }
        }

        public SplitType trySplit() {
            if (pos >= max - 1) return null;
            int retLen = (max - pos) >> 1;
            if (retLen <= 1) return null;
            int myNewPos = pos + retLen;
            int retPos = pos;
            SplitType split = makeForSplit(retPos, myNewPos, mustReturnNull);
            this.pos = myNewPos;
            this.mustReturnNull = false;
            this.hasSplit = true;
            return split;
        }

        public int skip(int n) {
            if (n == 0) return 0;
            int skipped = 0;
            if (mustReturnNull) {
                mustReturnNull = false;
                ++skipped;
                --n;
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            while (pos < max && n > 0) {
                if (!((key[pos++]) == null)) {
                    ++skipped;
                    --n;
                }
            }
            return skipped;
        }
    }

    private final class EntrySpliterator extends MapSpliterator<Consumer<? super Entry<K>>, EntrySpliterator> implements ObjectSpliterator<Entry<K>> {

        private static final int POST_SPLIT_CHARACTERISTICS = ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;

        EntrySpliterator() {
        }

        EntrySpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            super(pos, max, mustReturnNull, hasSplit);
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        void acceptOnIndex(final Consumer<? super Entry<K>> action, final int index) {
            action.accept(new MapEntry(index));
        }

        @Override
        EntrySpliterator makeForSplit(int pos, int max, boolean mustReturnNull) {
            return new EntrySpliterator(pos, max, mustReturnNull, true);
        }
    }

    private final class MapEntrySet extends AbstractObjectSet<Entry<K>> implements FastEntrySet<K> {

        @Override
        public ObjectIterator<Entry<K>> iterator() {
            return new EntryIterator();
        }

        @Override
        public ObjectIterator<Entry<K>> fastIterator() {
            return new FastEntryIterator();
        }

        @Override
        public ObjectSpliterator<Entry<K>> spliterator() {
            return new EntrySpliterator();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean contains(final Object o) {
            if (!(o instanceof java.util.Map.Entry<?, ?> e)) return false;
            if (e.getValue() == null || !(e.getValue() instanceof Float)) return false;
            final K k = ((K) e.getKey());
            final float v = ((Float) (e.getValue())).floatValue();
            if (((k) == null))
                return O2FOpenCustomCacheHashMap.this.containsNullKey && (Float.floatToRawIntBits(value[n]) == Float.floatToRawIntBits(v));
            K curr;
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            final int mask = O2FOpenCustomCacheHashMap.this.mask;
            int pos;
            if (((curr = key[pos = (HashCommon.mix(strategy.hashCode(k))) & mask]) == null)) return false;
            if (strategy.equals(k, curr)) return (Float.floatToRawIntBits(value[pos]) == Float.floatToRawIntBits(v));
            while (true) {
                if (((curr = key[pos = (pos + 1) & mask]) == null)) return false;
                if (strategy.equals(k, curr))
                    return (Float.floatToRawIntBits(value[pos]) == Float.floatToRawIntBits(v));
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(final Object o) {
            if (!(o instanceof java.util.Map.Entry<?, ?> e)) return false;
            if (e.getValue() == null || !(e.getValue() instanceof Float)) return false;
            final K k = ((K) e.getKey());
            final float v = ((Float) (e.getValue())).floatValue();
            if (((k) == null)) {
                if (containsNullKey && (Float.floatToRawIntBits(value[n]) == Float.floatToRawIntBits(v))) {
                    removeNullEntry();
                    return true;
                }
                return false;
            }
            K curr;
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            final int mask = O2FOpenCustomCacheHashMap.this.mask;
            int pos;
            if (((curr = key[pos = (HashCommon.mix(strategy.hashCode(k))) & mask]) == null)) return false;
            if (strategy.equals(k, curr)) {
                if ((Float.floatToRawIntBits(value[pos]) == Float.floatToRawIntBits(v))) {
                    removeEntry(pos);
                    return true;
                }
                return false;
            }
            while (true) {
                if (((curr = key[pos = (pos + 1) & mask]) == null)) return false;
                if (strategy.equals(k, curr)) {
                    if ((Float.floatToRawIntBits(value[pos]) == Float.floatToRawIntBits(v))) {
                        removeEntry(pos);
                        return true;
                    }
                }
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            O2FOpenCustomCacheHashMap.this.clear();
        }

        @Override
        public void forEach(final Consumer<? super Entry<K>> consumer) {
            if (containsNullKey) consumer.accept(new MapEntry(n));
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            for (int pos = n; pos-- != 0; ) if (!((key[pos]) == null)) consumer.accept(new MapEntry(pos));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void fastForEach(final Consumer<? super Entry<K>> consumer) {
            final MapEntry entry = new MapEntry();
            if (containsNullKey) {
                entry.index = n;
                consumer.accept(entry);
            }
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            for (int pos = n; pos-- != 0; )
                if (!((key[pos]) == null)) {
                    entry.index = pos;
                    consumer.accept(entry);
                }
        }
    }

    @Override
    public FastEntrySet<K> object2FloatEntrySet() {
        if (entries == null) entries = new MapEntrySet();
        return entries;
    }

    private final class KeyIterator extends MapIterator<Consumer<? super K>> implements ObjectIterator<K> {

        public KeyIterator() {
            super();
        }

        @Override
        void acceptOnIndex(final Consumer<? super K> action, final int index) {
            action.accept(key[index]);
        }

        @Override
        public K next() {
            return key[nextEntry()];
        }
    }

    private final class KeySpliterator extends MapSpliterator<Consumer<? super K>, KeySpliterator> implements ObjectSpliterator<K> {

        private static final int POST_SPLIT_CHARACTERISTICS = ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;

        KeySpliterator() {
        }

        KeySpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            super(pos, max, mustReturnNull, hasSplit);
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        void acceptOnIndex(final Consumer<? super K> action, final int index) {
            action.accept(key[index]);
        }

        @Override
        KeySpliterator makeForSplit(int pos, int max, boolean mustReturnNull) {
            return new KeySpliterator(pos, max, mustReturnNull, true);
        }
    }

    private final class KeySet extends AbstractObjectSet<K> {

        @Override
        public ObjectIterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public ObjectSpliterator<K> spliterator() {
            return new KeySpliterator();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void forEach(final Consumer<? super K> consumer) {
            final K[] key = O2FOpenCustomCacheHashMap.this.key;
            if (containsNullKey) consumer.accept(key[n]);
            for (int pos = n; pos-- != 0; ) {
                final K k = key[pos];
                if (!((k) == null)) consumer.accept(k);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean contains(Object k) {
            return containsKey(k);
        }

        @Override
        public boolean remove(Object k) {
            final int oldSize = size;
            O2FOpenCustomCacheHashMap.this.removeFloat(k);
            return size != oldSize;
        }

        @Override
        public void clear() {
            O2FOpenCustomCacheHashMap.this.clear();
        }
    }

    @Override
    public ObjectSet<K> keySet() {
        if (keys == null) keys = new KeySet();
        return keys;
    }

    private final class ValueIterator extends MapIterator<FloatConsumer> implements FloatIterator {

        public ValueIterator() {
            super();
        }

        @Override
        void acceptOnIndex(final FloatConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        public float nextFloat() {
            return value[nextEntry()];
        }
    }

    private final class ValueSpliterator extends MapSpliterator<FloatConsumer, ValueSpliterator> implements FloatSpliterator {

        private static final int POST_SPLIT_CHARACTERISTICS = FloatSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;

        ValueSpliterator() {
        }

        ValueSpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            super(pos, max, mustReturnNull, hasSplit);
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : FloatSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        void acceptOnIndex(final FloatConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        ValueSpliterator makeForSplit(int pos, int max, boolean mustReturnNull) {
            return new ValueSpliterator(pos, max, mustReturnNull, true);
        }
    }

    @Override
    public FloatCollection values() {
        if (values == null) values = new AbstractFloatCollection() {

            @Override
            public FloatIterator iterator() {
                return new ValueIterator();
            }

            @Override
            public FloatSpliterator spliterator() {
                return new ValueSpliterator();
            }

            /** {@inheritDoc} */
            @Override
            public void forEach(final FloatConsumer consumer) {
                final K[] key = O2FOpenCustomCacheHashMap.this.key;
                final float[] value = O2FOpenCustomCacheHashMap.this.value;
                if (containsNullKey) consumer.accept(value[n]);
                for (int pos = n; pos-- != 0; ) if (!((key[pos]) == null)) consumer.accept(value[pos]);
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(float v) {
                return containsValue(v);
            }

            @Override
            public void clear() {
                O2FOpenCustomCacheHashMap.this.clear();
            }
        };
        return values;
    }

    @SuppressWarnings("unchecked")
    protected void rehash(final int newN) {
        final K[] key = this.key;
        final float[] value = this.value;
        final int[] hash = this.hash;
        final int mask = newN - 1;
        final K[] newKey = (K[]) new Object[newN + 1];
        final float[] newValue = new float[newN + 1];
        final int[] newHash = new int[newN + 1];
        int i = n, pos, h;
        for (int j = realSize(); j-- != 0; ) {
            while (((key[--i]) == null)) ;
            if (!((newKey[pos = HashCommon.mix(h = hash[i]) & mask]) == null))
                while (!((newKey[pos = (pos + 1) & mask]) == null)) ;
            newKey[pos] = key[i];
            newValue[pos] = value[i];
            newHash[pos] = h;
        }
        newValue[newN] = value[n];
        n = newN;
        this.mask = mask;
        maxFill = maxFill(n, f);
        this.key = newKey;
        this.value = newValue;
        this.hash = newHash;
    }

    @Override
    public O2FOpenCustomCacheHashMap<K> clone() {
        O2FOpenCustomCacheHashMap<K> c = (O2FOpenCustomCacheHashMap<K>) super.clone();
        c.hash = hash.clone();
        return c;
    }

    @Override
    public int hashCode() {
        int h = 0;
        final K[] key = this.key;
        final float[] value = this.value;
        final int[] hash = this.hash;
        for (int j = realSize(), i = 0, t = 0; j-- != 0; ) {
            while (((key[i]) == null)) i++;
            if (this != key[i]) t = hash[i];
            t ^= it.unimi.dsi.fastutil.HashCommon.float2int(value[i]);
            h += t;
            i++;
        }
        if (containsNullKey) h += it.unimi.dsi.fastutil.HashCommon.float2int(value[n]);
        return h;
    }
}
