package com.gto.fastcollection.fastutil;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.*;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.maxFill;

public final class O2IOpenCustomCacheHashMap<K> extends Object2IntOpenCustomHashMap<K> {

    private int[] hash;

    public O2IOpenCustomCacheHashMap(final int expected, final float f, final Strategy<? super K> strategy) {
        super(expected, f, strategy);
        hash = new int[n + 1];
    }

    public O2IOpenCustomCacheHashMap(final int expected, final Strategy<? super K> strategy) {
        super(expected, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2IOpenCustomCacheHashMap(final Strategy<? super K> strategy) {
        super(DEFAULT_INITIAL_SIZE, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2IOpenCustomCacheHashMap(final Map<? extends K, ? extends Integer> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2IOpenCustomCacheHashMap(final Map<? extends K, ? extends Integer> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    public O2IOpenCustomCacheHashMap(final Object2IntMap<K> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2IOpenCustomCacheHashMap(final Object2IntMap<K> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    private int realSize() {
        return containsNullKey ? size - 1 : size;
    }

    private int removeEntry(int pos) {
        final int oldValue = value[pos];
        size--;
        int last, slot, ch;
        K curr;
        final K[] key = this.key;
        final int[] value = this.value;
        final int[] hash = this.hash;
        final int mask = this.mask;
        a:
        for (; ; ) {
            pos = ((last = pos) + 1) & mask;
            for (; ; ) {
                if ((curr = key[pos]) == null) {
                    key[last] = null;
                    value[last] = 0;
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

    private int removeNullEntry() {
        containsNullKey = false;
        key[n] = null;
        hash[n] = 0;
        final int oldValue = value[n];
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

    private void insert(final int pos, final K k, final int v, final int h) {
        if (pos == n) containsNullKey = true;
        key[pos] = k;
        value[pos] = v;
        hash[pos] = h;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
    }

    @Override
    public int put(final K k, final int v) {
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
        final int oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }

    private int addToValue(final int pos, final int incr) {
        final int oldValue = value[pos];
        value[pos] = oldValue + incr;
        return oldValue;
    }

    @Override
    public int addTo(final K k, final int incr) {
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
    public int removeInt(final Object k) {
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
    public int getInt(final Object k) {
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
    public int getOrDefault(final Object k, final int defaultValue) {
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
    public int putIfAbsent(final K k, final int v) {
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
    public boolean remove(final Object k, final int v) {
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
    public boolean replace(final K k, final int oldValue, final int v) {
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
    public int replace(final K k, final int v) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final int oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }

    @Override
    public int computeIfAbsent(final K k, final ToIntFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        final int newValue = mappingFunction.applyAsInt(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public int computeIfAbsent(final K k, final Object2IntFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        if (!mappingFunction.containsKey(key)) return defRetValue;
        final int newValue = mappingFunction.applyAsInt(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public int computeIntIfPresent(final K k, final BiFunction<? super K, ? super Integer, ? extends Integer> remappingFunction) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final Integer newValue = remappingFunction.apply((k), value[pos]);
        if (newValue == null) {
            if (k == null) removeNullEntry();
            else removeEntry(pos);
            return defRetValue;
        }
        return value[pos] = newValue;
    }

    @Override
    public int computeInt(final K k, final BiFunction<? super K, ? super Integer, ? extends Integer> remappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        final Integer newValue = remappingFunction.apply((k), pos >= 0 ? value[pos] : null);
        if (newValue == null) {
            if (pos >= 0) {
                if (k == null) removeNullEntry();
                else removeEntry(pos);
            }
            return defRetValue;
        }
        int newVal = newValue;
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
    public int mergeInt(final K k, final int v, java.util.function.IntBinaryOperator remappingFunction) {
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
        final int newValue = remappingFunction.applyAsInt(value[pos], v);
        return value[pos] = newValue;
    }

    @Override
    public int merge(final K k, final int v, final BiFunction<? super Integer, ? super Integer, ? extends Integer> remappingFunction) {
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
        final Integer newValue = remappingFunction.apply(value[pos], v);
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

    final class MapEntry implements Entry<K>, ObjectIntPair<K> {

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
        public int getIntValue() {
            return value[index];
        }

        @Override
        public int rightInt() {
            return value[index];
        }

        @Override
        public int setValue(final int v) {
            final int oldValue = value[index];
            value[index] = v;
            return oldValue;
        }

        @Override
        public ObjectIntPair<K> right(final int v) {
            value[index] = v;
            return this;
        }

        @Deprecated
        @Override
        public Integer getValue() {
            return value[index];
        }

        @Deprecated
        @Override
        public Integer setValue(final Integer v) {
            return setValue((v).intValue());
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry<K, Integer> e = (Map.Entry<K, Integer>) o;
            return strategy.equals(key[index], (e.getKey())) && ((value[index]) == (e.getValue()));
        }

        @Override
        public int hashCode() {
            return hash[index] ^ value[index];
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
        boolean mustReturnNullKey = O2IOpenCustomCacheHashMap.this.containsNullKey;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            final int[] hash = O2IOpenCustomCacheHashMap.this.hash;
            final int mask = O2IOpenCustomCacheHashMap.this.mask;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            final int[] hash = O2IOpenCustomCacheHashMap.this.hash;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            final int[] value = O2IOpenCustomCacheHashMap.this.value;
            final int[] hash = O2IOpenCustomCacheHashMap.this.hash;
            final int mask = O2IOpenCustomCacheHashMap.this.mask;
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
                O2IOpenCustomCacheHashMap.this.removeInt(wrapped.set(-pos - 1, null));
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
        boolean mustReturnNull = O2IOpenCustomCacheHashMap.this.containsNullKey;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
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
            if (e.getValue() == null || !(e.getValue() instanceof Integer)) return false;
            final K k = ((K) e.getKey());
            final int v = (Integer) (e.getValue());
            if (((k) == null)) return O2IOpenCustomCacheHashMap.this.containsNullKey && ((value[n]) == (v));
            K curr;
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            final int mask = O2IOpenCustomCacheHashMap.this.mask;
            int pos;
            if (((curr = key[pos = (HashCommon.mix(strategy.hashCode(k))) & mask]) == null)) return false;
            if (strategy.equals(k, curr)) return ((value[pos]) == (v));
            while (true) {
                if (((curr = key[pos = (pos + 1) & mask]) == null)) return false;
                if (strategy.equals(k, curr)) return ((value[pos]) == (v));
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean remove(final Object o) {
            if (!(o instanceof java.util.Map.Entry<?, ?> e)) return false;
            if (e.getValue() == null || !(e.getValue() instanceof Integer)) return false;
            final K k = ((K) e.getKey());
            final int v = (Integer) (e.getValue());
            if (((k) == null)) {
                if (containsNullKey && ((value[n]) == (v))) {
                    removeNullEntry();
                    return true;
                }
                return false;
            }
            K curr;
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            final int mask = O2IOpenCustomCacheHashMap.this.mask;
            int pos;
            if (((curr = key[pos = (HashCommon.mix(strategy.hashCode(k))) & mask]) == null)) return false;
            if (strategy.equals(k, curr)) {
                if (((value[pos]) == (v))) {
                    removeEntry(pos);
                    return true;
                }
                return false;
            }
            while (true) {
                if (((curr = key[pos = (pos + 1) & mask]) == null)) return false;
                if (strategy.equals(k, curr)) {
                    if (((value[pos]) == (v))) {
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
            O2IOpenCustomCacheHashMap.this.clear();
        }

        @Override
        public void forEach(final Consumer<? super Entry<K>> consumer) {
            if (containsNullKey) consumer.accept(new MapEntry(n));
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
            for (int pos = n; pos-- != 0; )
                if (!((key[pos]) == null)) {
                    entry.index = pos;
                    consumer.accept(entry);
                }
        }
    }

    @Override
    public FastEntrySet<K> object2IntEntrySet() {
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
            final K[] key = O2IOpenCustomCacheHashMap.this.key;
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
            O2IOpenCustomCacheHashMap.this.removeInt(k);
            return size != oldSize;
        }

        @Override
        public void clear() {
            O2IOpenCustomCacheHashMap.this.clear();
        }
    }

    @Override
    public ObjectSet<K> keySet() {
        if (keys == null) keys = new KeySet();
        return keys;
    }

    private final class ValueIterator extends MapIterator<java.util.function.IntConsumer> implements IntIterator {

        public ValueIterator() {
            super();
        }

        @Override
        void acceptOnIndex(final java.util.function.IntConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        public int nextInt() {
            return value[nextEntry()];
        }
    }

    private final class ValueSpliterator extends MapSpliterator<java.util.function.IntConsumer, ValueSpliterator> implements IntSpliterator {

        private static final int POST_SPLIT_CHARACTERISTICS = IntSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;

        ValueSpliterator() {
        }

        ValueSpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            super(pos, max, mustReturnNull, hasSplit);
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : IntSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        void acceptOnIndex(final java.util.function.IntConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        ValueSpliterator makeForSplit(int pos, int max, boolean mustReturnNull) {
            return new ValueSpliterator(pos, max, mustReturnNull, true);
        }
    }

    @Override
    public IntCollection values() {
        if (values == null) values = new AbstractIntCollection() {

            @Override
            public IntIterator iterator() {
                return new ValueIterator();
            }

            @Override
            public IntSpliterator spliterator() {
                return new ValueSpliterator();
            }

            /** {@inheritDoc} */
            @Override
            public void forEach(final java.util.function.IntConsumer consumer) {
                final K[] key = O2IOpenCustomCacheHashMap.this.key;
                final int[] value = O2IOpenCustomCacheHashMap.this.value;
                if (containsNullKey) consumer.accept(value[n]);
                for (int pos = n; pos-- != 0; ) if (!((key[pos]) == null)) consumer.accept(value[pos]);
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(int v) {
                return containsValue(v);
            }

            @Override
            public void clear() {
                O2IOpenCustomCacheHashMap.this.clear();
            }
        };
        return values;
    }

    @SuppressWarnings("unchecked")
    protected void rehash(final int newN) {
        final K[] key = this.key;
        final int[] value = this.value;
        final int[] hash = this.hash;
        final int mask = newN - 1;
        final K[] newKey = (K[]) new Object[newN + 1];
        final int[] newValue = new int[newN + 1];
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
    public O2IOpenCustomCacheHashMap<K> clone() {
        O2IOpenCustomCacheHashMap<K> c = (O2IOpenCustomCacheHashMap<K>) super.clone();
        c.hash = hash.clone();
        return c;
    }

    @Override
    public int hashCode() {
        int h = 0;
        final K[] key = this.key;
        final int[] value = this.value;
        final int[] hash = this.hash;
        for (int j = realSize(), i = 0, t = 0; j-- != 0; ) {
            while (((key[i]) == null)) i++;
            if (this != key[i]) t = hash[i];
            t ^= value[i];
            h += t;
            i++;
        }
        if (containsNullKey) h += value[n];
        return h;
    }
}
