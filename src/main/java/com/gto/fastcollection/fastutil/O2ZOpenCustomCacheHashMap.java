package com.gto.fastcollection.fastutil;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.booleans.*;
import it.unimi.dsi.fastutil.objects.*;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.maxFill;

/**
 * A cached-hash open-addressing map from object keys to primitive
 * {@code boolean} values with a custom {@link it.unimi.dsi.fastutil.Hash.Strategy}, the
 * strategy-based counterpart of {@link O2ZOpenCacheHashMap}. Each slot stores
 * the key's strategy hash in a parallel {@code int[]} array, so probes
 * short-circuit on the stored hash and reference identity ({@code ==}) before
 * falling back to {@code strategy.equals}; {@code rehash} reuses the stored
 * hashes instead of recomputing them. Use it when "equal" must be decided by
 * application logic rather than {@code equals}.
 *
 * <p>Not thread-safe; confined to one thread.
 */
public final class O2ZOpenCustomCacheHashMap<K> extends Object2BooleanOpenCustomHashMap<K> {

    private int[] hash;

    public O2ZOpenCustomCacheHashMap(final int expected, final float f, final Strategy<? super K> strategy) {
        super(expected, f, strategy);
        hash = new int[n + 1];
    }

    public O2ZOpenCustomCacheHashMap(final int expected, final Strategy<? super K> strategy) {
        super(expected, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2ZOpenCustomCacheHashMap(final Strategy<? super K> strategy) {
        super(DEFAULT_INITIAL_SIZE, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public O2ZOpenCustomCacheHashMap(final Map<? extends K, ? extends Boolean> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2ZOpenCustomCacheHashMap(final Map<? extends K, ? extends Boolean> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    public O2ZOpenCustomCacheHashMap(final Object2BooleanMap<K> m, final float f, final Strategy<? super K> strategy) {
        super(m.size(), f, strategy);
        hash = new int[n + 1];
        putAll(m);
    }

    public O2ZOpenCustomCacheHashMap(final Object2BooleanMap<K> m, final Strategy<? super K> strategy) {
        this(m, DEFAULT_LOAD_FACTOR, strategy);
    }

    private int realSize() {
        return containsNullKey ? size - 1 : size;
    }

    private boolean removeEntry(int pos) {
        final boolean oldValue = value[pos];
        size--;
        int last, slot, ch;
        K curr;
        final K[] key = this.key;
        final boolean[] value = this.value;
        final int[] hash = this.hash;
        final int mask = this.mask;
        a:
        for (; ; ) {
            pos = ((last = pos) + 1) & mask;
            for (; ; ) {
                if ((curr = key[pos]) == null) {
                    key[last] = null;
                    value[last] = false;
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

    private boolean removeNullEntry() {
        containsNullKey = false;
        key[n] = null;
        hash[n] = 0;
        final boolean oldValue = value[n];
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

    private void insert(final int pos, final K k, final boolean v, final int h) {
        if (pos == n) containsNullKey = true;
        key[pos] = k;
        value[pos] = v;
        hash[pos] = h;
        if (size++ >= maxFill) rehash(arraySize(size + 1, f));
    }

    @Override
    public boolean put(final K k, final boolean v) {
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
        final boolean oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }


    @Override
    public boolean removeBoolean(final Object k) {
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
    public boolean getBoolean(final Object k) {
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
    public boolean getOrDefault(final Object k, final boolean defaultValue) {
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
    public boolean putIfAbsent(final K k, final boolean v) {
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
    public boolean remove(final Object k, final boolean v) {
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
    public boolean replace(final K k, final boolean oldValue, final boolean v) {
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
    public boolean replace(final K k, final boolean v) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final boolean oldValue = value[pos];
        value[pos] = v;
        return oldValue;
    }

    @Override
    public boolean computeIfAbsent(final K k, final java.util.function.Predicate<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        final boolean newValue = mappingFunction.test(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public boolean computeIfAbsent(final K k, final Object2BooleanFunction<? super K> mappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos >= 0) return value[pos];
        if (!mappingFunction.containsKey(key)) return defRetValue;
        final boolean newValue = mappingFunction.getBoolean(k);
        insert(-pos - 1, k, newValue, h);
        return newValue;
    }

    @Override
    public boolean computeBooleanIfPresent(final K k, final BiFunction<? super K, ? super Boolean, ? extends Boolean> remappingFunction) {
        int pos;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            int h = strategy.hashCode(k);
            pos = find(k, h);
        }
        if (pos < 0) return defRetValue;
        final Boolean newValue = remappingFunction.apply((k), Boolean.valueOf(value[pos]));
        if (newValue == null) {
            if (k == null) removeNullEntry();
            else removeEntry(pos);
            return defRetValue;
        }
        return value[pos] = newValue;
    }

    @Override
    public boolean computeBoolean(final K k, final BiFunction<? super K, ? super Boolean, ? extends Boolean> remappingFunction) {
        int pos, h = 0;
        if (k == null) {
            pos = containsNullKey ? n : -(n + 1);
        } else {
            h = strategy.hashCode(k);
            pos = find(k, h);
        }
        final Boolean newValue = remappingFunction.apply((k), pos >= 0 ? Boolean.valueOf(value[pos]) : null);
        if (newValue == null) {
            if (pos >= 0) {
                if (k == null) removeNullEntry();
                else removeEntry(pos);
            }
            return defRetValue;
        }
        boolean newVal = newValue;
        if (pos < 0) {
            insert(-pos - 1, k, newVal, h);
            return newVal;
        }
        return value[pos] = newVal;
    }


    @Override
    public boolean merge(final K k, final boolean v, final BiFunction<? super Boolean, ? super Boolean, ? extends Boolean> remappingFunction) {
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
        final Boolean newValue = remappingFunction.apply(Boolean.valueOf(value[pos]), Boolean.valueOf(v));
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

    final class MapEntry implements Entry<K>, ObjectBooleanPair<K> {

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
        public boolean getBooleanValue() {
            return value[index];
        }

        @Override
        public boolean rightBoolean() {
            return value[index];
        }

        @Override
        public boolean setValue(final boolean v) {
            final boolean oldValue = value[index];
            value[index] = v;
            return oldValue;
        }

        @Override
        public ObjectBooleanPair<K> right(final boolean v) {
            value[index] = v;
            return this;
        }

        @Deprecated
        @Override
        public Boolean getValue() {
            return value[index];
        }

        @Deprecated
        @Override
        public Boolean setValue(final Boolean v) {
            return Boolean.valueOf(setValue((v).booleanValue()));
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(final Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry<K, Boolean> e = (Map.Entry<K, Boolean>) o;
            return strategy.equals(key[index], (e.getKey())) && ((value[index]) == ((e.getValue()).booleanValue()));
        }

        @Override
        public int hashCode() {
            return hash[index] ^ (value[index] ? 1231 : 1237);
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
        boolean mustReturnNullKey = O2ZOpenCustomCacheHashMap.this.containsNullKey;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            final int[] hash = O2ZOpenCustomCacheHashMap.this.hash;
            final int mask = O2ZOpenCustomCacheHashMap.this.mask;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            final int[] hash = O2ZOpenCustomCacheHashMap.this.hash;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            final boolean[] value = O2ZOpenCustomCacheHashMap.this.value;
            final int[] hash = O2ZOpenCustomCacheHashMap.this.hash;
            final int mask = O2ZOpenCustomCacheHashMap.this.mask;
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
                O2ZOpenCustomCacheHashMap.this.removeBoolean(wrapped.set(-pos - 1, null));
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
        boolean mustReturnNull = O2ZOpenCustomCacheHashMap.this.containsNullKey;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
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
            if (e.getValue() == null || !(e.getValue() instanceof Boolean)) return false;
            final K k = ((K) e.getKey());
            final boolean v = ((Boolean) (e.getValue())).booleanValue();
            if (((k) == null)) return O2ZOpenCustomCacheHashMap.this.containsNullKey && ((value[n]) == (v));
            K curr;
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            final int mask = O2ZOpenCustomCacheHashMap.this.mask;
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
            if (e.getValue() == null || !(e.getValue() instanceof Boolean)) return false;
            final K k = ((K) e.getKey());
            final boolean v = ((Boolean) (e.getValue())).booleanValue();
            if (((k) == null)) {
                if (containsNullKey && ((value[n]) == (v))) {
                    removeNullEntry();
                    return true;
                }
                return false;
            }
            K curr;
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            final int mask = O2ZOpenCustomCacheHashMap.this.mask;
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
            O2ZOpenCustomCacheHashMap.this.clear();
        }

        @Override
        public void forEach(final Consumer<? super Entry<K>> consumer) {
            if (containsNullKey) consumer.accept(new MapEntry(n));
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
            for (int pos = n; pos-- != 0; )
                if (!((key[pos]) == null)) {
                    entry.index = pos;
                    consumer.accept(entry);
                }
        }
    }

    @Override
    public FastEntrySet<K> object2BooleanEntrySet() {
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
            final K[] key = O2ZOpenCustomCacheHashMap.this.key;
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
            O2ZOpenCustomCacheHashMap.this.removeBoolean(k);
            return size != oldSize;
        }

        @Override
        public void clear() {
            O2ZOpenCustomCacheHashMap.this.clear();
        }
    }

    @Override
    public ObjectSet<K> keySet() {
        if (keys == null) keys = new KeySet();
        return keys;
    }

    private final class ValueIterator extends MapIterator<BooleanConsumer> implements BooleanIterator {

        public ValueIterator() {
            super();
        }

        @Override
        void acceptOnIndex(final BooleanConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        public boolean nextBoolean() {
            return value[nextEntry()];
        }
    }

    private final class ValueSpliterator extends MapSpliterator<BooleanConsumer, ValueSpliterator> implements BooleanSpliterator {

        private static final int POST_SPLIT_CHARACTERISTICS = BooleanSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;

        ValueSpliterator() {
        }

        ValueSpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            super(pos, max, mustReturnNull, hasSplit);
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : BooleanSpliterators.COLLECTION_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        void acceptOnIndex(final BooleanConsumer action, final int index) {
            action.accept(value[index]);
        }

        @Override
        ValueSpliterator makeForSplit(int pos, int max, boolean mustReturnNull) {
            return new ValueSpliterator(pos, max, mustReturnNull, true);
        }
    }

    @Override
    public BooleanCollection values() {
        if (values == null) values = new AbstractBooleanCollection() {

            @Override
            public BooleanIterator iterator() {
                return new ValueIterator();
            }

            @Override
            public BooleanSpliterator spliterator() {
                return new ValueSpliterator();
            }

            /** {@inheritDoc} */
            @Override
            public void forEach(final BooleanConsumer consumer) {
                final K[] key = O2ZOpenCustomCacheHashMap.this.key;
                final boolean[] value = O2ZOpenCustomCacheHashMap.this.value;
                if (containsNullKey) consumer.accept(value[n]);
                for (int pos = n; pos-- != 0; ) if (!((key[pos]) == null)) consumer.accept(value[pos]);
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(boolean v) {
                return containsValue(v);
            }

            @Override
            public void clear() {
                O2ZOpenCustomCacheHashMap.this.clear();
            }
        };
        return values;
    }

    @SuppressWarnings("unchecked")
    protected void rehash(final int newN) {
        final K[] key = this.key;
        final boolean[] value = this.value;
        final int[] hash = this.hash;
        final int mask = newN - 1;
        final K[] newKey = (K[]) new Object[newN + 1];
        final boolean[] newValue = new boolean[newN + 1];
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
    public O2ZOpenCustomCacheHashMap<K> clone() {
        O2ZOpenCustomCacheHashMap<K> c = (O2ZOpenCustomCacheHashMap<K>) super.clone();
        c.hash = hash.clone();
        return c;
    }

    @Override
    public int hashCode() {
        int h = 0;
        final K[] key = this.key;
        final boolean[] value = this.value;
        final int[] hash = this.hash;
        for (int j = realSize(), i = 0, t = 0; j-- != 0; ) {
            while (((key[i]) == null)) i++;
            if (this != key[i]) t = hash[i];
            t ^= (value[i] ? 1231 : 1237);
            h += t;
            i++;
        }
        if (containsNullKey) h += (value[n] ? 1231 : 1237);
        return h;
    }
}
