package com.gto.fastcollection.fastutil;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * A cached-hash open-addressing set with a custom {@link Hash.Strategy}, the
 * strategy-based counterpart of {@link OpenCacheHashSet}. Each slot stores the
 * key's strategy hash in a parallel {@code int[]} array, so probes compare the
 * stored hash first and skip {@code strategy.equals} for mismatches, and
 * {@code rehash} reuses stored hashes instead of recomputing them. Use it when
 * "equal" must be decided by application logic rather than {@code equals}.
 *
 * <p>Not thread-safe; confined to one thread.
 */
public final class OpenCustomCacheHashSet<K> extends ObjectOpenCustomHashSet<K> {

    private int[] hash;

    public OpenCustomCacheHashSet(final int expected, final float f, final Hash.Strategy<? super K> strategy) {
        super(expected, f, strategy);
        hash = new int[n + 1];
    }

    public OpenCustomCacheHashSet(final int expected, final Hash.Strategy<? super K> strategy) {
        super(expected, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public OpenCustomCacheHashSet(final Hash.Strategy<? super K> strategy) {
        super(DEFAULT_INITIAL_SIZE, DEFAULT_LOAD_FACTOR, strategy);
        hash = new int[n + 1];
    }

    public OpenCustomCacheHashSet(final Collection<? extends K> c, final float f, final Hash.Strategy<? super K> strategy) {
        super(c.size(), f, strategy);
        hash = new int[n + 1];
        addAll(c);
    }

    public OpenCustomCacheHashSet(final Collection<? extends K> c, final Hash.Strategy<? super K> strategy) {
        this(c, DEFAULT_LOAD_FACTOR, strategy);
    }

    public OpenCustomCacheHashSet(final ObjectCollection<? extends K> c, final float f, final Hash.Strategy<? super K> strategy) {
        super(c.size(), f, strategy);
        hash = new int[n + 1];
        addAll(c);
    }

    public OpenCustomCacheHashSet(final ObjectCollection<? extends K> c, final Hash.Strategy<? super K> strategy) {
        this(c, DEFAULT_LOAD_FACTOR, strategy);
    }

    public OpenCustomCacheHashSet(final Iterator<? extends K> i, final float f, final Hash.Strategy<? super K> strategy) {
        super(DEFAULT_INITIAL_SIZE, f, strategy);
        hash = new int[n + 1];
        while (i.hasNext()) add(i.next());
    }

    public OpenCustomCacheHashSet(final Iterator<? extends K> i, final Hash.Strategy<? super K> strategy) {
        this(i, DEFAULT_LOAD_FACTOR, strategy);
    }

    public OpenCustomCacheHashSet(final K[] a, final int offset, final int length, final float f, final Hash.Strategy<? super K> strategy) {
        super(Math.max(length, 0), f, strategy);
        hash = new int[n + 1];
        ObjectArrays.ensureOffsetLength(a, offset, length);
        for (int i = 0; i < length; i++) add(a[offset + i]);
    }

    public OpenCustomCacheHashSet(final K[] a, final int offset, final int length, final Hash.Strategy<? super K> strategy) {
        this(a, offset, length, DEFAULT_LOAD_FACTOR, strategy);
    }

    public OpenCustomCacheHashSet(final K[] a, final float f, final Hash.Strategy<? super K> strategy) {
        this(a, 0, a.length, f, strategy);
    }

    public OpenCustomCacheHashSet(final K[] a, final Hash.Strategy<? super K> strategy) {
        this(a, 0, a.length, DEFAULT_LOAD_FACTOR, strategy);
    }

    private int realSize() {
        return containsNull ? size - 1 : size;
    }

    @Override
    public boolean add(final K k) {
        if (k == null) {
            if (containsNull) return false;
            containsNull = true;
        } else {
            final int h = strategy.hashCode(k);
            K curr;
            final K[] key = this.key;
            final int[] hash = this.hash;
            int pos;
            if ((curr = key[pos = HashCommon.mix(h) & mask]) != null) {
                do {
                    if (hash[pos] == h && (k == curr || strategy.equals(k, curr))) return false;
                } while ((curr = key[pos = (pos + 1) & mask]) != null);
            }
            key[pos] = k;
            hash[pos] = h;
        }
        if (size++ >= maxFill) rehash(HashCommon.arraySize(size + 1, f));
        return true;
    }

    @Override
    public K addOrGet(final K k) {
        if (k == null) {
            if (containsNull) return key[n];
            containsNull = true;
        } else {
            final int h = strategy.hashCode(k);
            K curr;
            final K[] key = this.key;
            final int[] hash = this.hash;
            int pos;
            if ((curr = key[pos = HashCommon.mix(h) & mask]) != null) {
                do {
                    if (hash[pos] == h && (k == curr || strategy.equals(k, curr))) return curr;
                } while ((curr = key[pos = (pos + 1) & mask]) != null);
            }
            key[pos] = k;
            hash[pos] = h;
        }
        if (size++ >= maxFill) rehash(HashCommon.arraySize(size + 1, f));
        return k;
    }

    private boolean removeEntry(int pos) {
        size--;
        int last, slot, ch;
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        a:
        for (; ; ) {
            pos = ((last = pos) + 1) & mask;
            for (; ; ) {
                if ((curr = key[pos]) == null) {
                    key[last] = null;
                    hash[last] = 0;
                    break a;
                }
                ch = hash[pos];
                slot = HashCommon.mix(ch) & mask;
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) break;
                pos = (pos + 1) & mask;
            }
            key[last] = curr;
            hash[last] = ch;
        }
        if (n > minN && size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) rehash(n / 2);
        return true;
    }

    private boolean removeNullEntry() {
        containsNull = false;
        key[n] = null;
        hash[n] = 0;
        size--;
        if (n > minN && size < maxFill / 4 && n > DEFAULT_INITIAL_SIZE) rehash(n / 2);
        return true;
    }

    @Override
    public boolean remove(final Object k) {
        if (k == null) {
            if (containsNull) return removeNullEntry();
            return false;
        }
        @SuppressWarnings("unchecked") final K kk = (K) k;
        final int h = strategy.hashCode(kk);
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return false;
        if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return removeEntry(pos);
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return false;
            if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return removeEntry(pos);
        }
    }

    @Override
    public boolean contains(final Object k) {
        if (k == null) return containsNull;
        @SuppressWarnings("unchecked") final K kk = (K) k;
        final int h = strategy.hashCode(kk);
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return false;
        if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return true;
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return false;
            if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return true;
        }
    }

    @Override
    public K get(final Object k) {
        if (k == null) return key[n];
        @SuppressWarnings("unchecked") final K kk = (K) k;
        final int h = strategy.hashCode(kk);
        K curr;
        final K[] key = this.key;
        final int[] hash = this.hash;
        int pos;
        if ((curr = key[pos = HashCommon.mix(h) & mask]) == null) return null;
        if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return curr;
        while (true) {
            if ((curr = key[pos = (pos + 1) & mask]) == null) return null;
            if (hash[pos] == h && (kk == curr || strategy.equals(kk, curr))) return curr;
        }
    }

    @Override
    public void clear() {
        if (size == 0) return;
        size = 0;
        containsNull = false;
        Arrays.fill(key, (null));
        Arrays.fill(hash, 0);
    }

    @Override
    public ObjectIterator<K> iterator() {
        return new SetIterator();
    }

    @Override
    public ObjectSpliterator<K> spliterator() {
        return new SetSpliterator();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void rehash(final int newN) {
        final K[] key = this.key;
        final int[] hash = this.hash;
        final int mask = newN - 1;
        final K[] newKey = (K[]) new Object[newN + 1];
        final int[] newHash = new int[newN + 1];
        int i = n, pos, h;
        K k;
        for (int j = realSize(); j-- != 0; ) {
            while ((k = key[--i]) == null) ;
            h = hash[i];
            pos = HashCommon.mix(h) & mask;
            if (newKey[pos] != null) while ((newKey[pos = (pos + 1) & mask]) != null) ;
            newKey[pos] = k;
            newHash[pos] = h;
        }
        n = newN;
        this.mask = mask;
        maxFill = HashCommon.maxFill(n, f);
        this.key = newKey;
        this.hash = newHash;
    }

    @Override
    public OpenCustomCacheHashSet<K> clone() {
        OpenCustomCacheHashSet<K> c = (OpenCustomCacheHashSet<K>) super.clone();
        c.hash = hash.clone();
        return c;
    }

    @Override
    public int hashCode() {
        int h = 0;
        final K[] key = this.key;
        final int[] hash = this.hash;
        for (int j = realSize(), i = 0; j-- != 0; ) {
            while (key[i] == null) i++;
            if (this != key[i]) h += hash[i];
            i++;
        }
        return h;
    }

    private final class SetIterator implements ObjectIterator<K> {

        int pos = n;
        int last = -1;
        int c = size;
        boolean mustReturnNull = OpenCustomCacheHashSet.this.containsNull;
        ObjectArrayList<K> wrapped;

        @Override
        public boolean hasNext() {
            return c != 0;
        }

        @Override
        public K next() {
            c--;
            final K[] key = OpenCustomCacheHashSet.this.key;
            if (mustReturnNull) {
                mustReturnNull = false;
                last = n;
                return key[n];
            }
            for (; ; ) {
                if (--pos < 0) {
                    last = Integer.MIN_VALUE;
                    return wrapped.get(-pos - 1);
                }
                if (key[pos] != null) return key[last = pos];
            }
        }

        private void shiftKeys(int pos) {
            int last, slot, ch;
            K curr;
            final K[] key = OpenCustomCacheHashSet.this.key;
            final int[] hash = OpenCustomCacheHashSet.this.hash;
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
                hash[last] = ch;
            }
        }

        @Override
        public void remove() {
            if (last == -1) throw new IllegalStateException();
            if (last == n) {
                OpenCustomCacheHashSet.this.containsNull = false;
                OpenCustomCacheHashSet.this.key[n] = null;
                OpenCustomCacheHashSet.this.hash[n] = 0;
            } else if (pos >= 0) shiftKeys(last);
            else {
                OpenCustomCacheHashSet.this.remove(wrapped.set(-pos - 1, null));
                last = -1;
                return;
            }
            size--;
            last = -1;
        }

        @Override
        public void forEachRemaining(final Consumer<? super K> action) {
            final K[] key = OpenCustomCacheHashSet.this.key;
            if (mustReturnNull) {
                mustReturnNull = false;
                last = n;
                action.accept(key[n]);
                c--;
            }
            while (c != 0) {
                if (--pos < 0) {
                    last = Integer.MIN_VALUE;
                    action.accept(wrapped.get(-pos - 1));
                    c--;
                } else if (key[pos] != null) {
                    action.accept(key[last = pos]);
                    c--;
                }
            }
        }
    }

    private final class SetSpliterator implements ObjectSpliterator<K> {

        private static final int POST_SPLIT_CHARACTERISTICS = ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS & ~java.util.Spliterator.SIZED;
        int pos = 0;
        int max = n;
        int c = 0;
        boolean mustReturnNull = OpenCustomCacheHashSet.this.containsNull;
        boolean hasSplit = false;

        SetSpliterator() {
        }

        SetSpliterator(int pos, int max, boolean mustReturnNull, boolean hasSplit) {
            this.pos = pos;
            this.max = max;
            this.mustReturnNull = mustReturnNull;
            this.hasSplit = hasSplit;
        }

        @Override
        public boolean tryAdvance(final Consumer<? super K> action) {
            if (mustReturnNull) {
                mustReturnNull = false;
                ++c;
                action.accept(key[n]);
                return true;
            }
            final K[] key = OpenCustomCacheHashSet.this.key;
            while (pos < max) {
                if (key[pos] != null) {
                    ++c;
                    action.accept(key[pos++]);
                    return true;
                } else {
                    ++pos;
                }
            }
            return false;
        }

        @Override
        public void forEachRemaining(final Consumer<? super K> action) {
            final K[] key = OpenCustomCacheHashSet.this.key;
            if (mustReturnNull) {
                mustReturnNull = false;
                action.accept(key[n]);
                ++c;
            }
            while (pos < max) {
                if (key[pos] != null) {
                    action.accept(key[pos]);
                    ++c;
                }
                ++pos;
            }
        }

        @Override
        public int characteristics() {
            return hasSplit ? POST_SPLIT_CHARACTERISTICS : ObjectSpliterators.SET_SPLITERATOR_CHARACTERISTICS;
        }

        @Override
        public long estimateSize() {
            if (!hasSplit) {
                return size - c;
            } else {
                return Math.min(size - c, (long) (((double) realSize() / n) * (max - pos)) + (mustReturnNull ? 1 : 0));
            }
        }

        @Override
        public SetSpliterator trySplit() {
            if (pos >= max - 1) return null;
            int retLen = (max - pos) >> 1;
            if (retLen <= 1) return null;
            int myNewPos = pos + retLen;
            int retPos = pos;
            SetSpliterator split = new SetSpliterator(retPos, myNewPos, mustReturnNull, true);
            this.pos = myNewPos;
            this.mustReturnNull = false;
            this.hasSplit = true;
            return split;
        }

        @Override
        public long skip(long n) {
            if (n == 0) return 0;
            long skipped = 0;
            if (mustReturnNull) {
                mustReturnNull = false;
                ++skipped;
                --n;
            }
            final K[] key = OpenCustomCacheHashSet.this.key;
            while (pos < max && n > 0) {
                if (key[pos++] != null) {
                    ++skipped;
                    --n;
                }
            }
            return skipped;
        }
    }
}
