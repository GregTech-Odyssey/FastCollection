package com.gto.fastcollection.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * An {@link Interner} backed by a single {@link ConcurrentHashMap}, using the
 * map itself as the canonical-value store ({@code T -> T}). Relies on the map's
 * internal striping for concurrency; {@link ConcurrentHashMap#computeIfAbsent}
 * guarantees {@link #intern} returns a single canonical instance per key.
 */
public final class HashInterner<T> implements Interner<T> {

    private final ConcurrentHashMap<T, T> map;
    private final Function<T, T> function = Function.identity();

    /** Creates an empty interner. */
    public HashInterner() {
        this.map = new ConcurrentHashMap<>();
    }

    /** {@inheritDoc} */
    @Override
    public T intern(final T sample) {
        return map.computeIfAbsent(sample, function);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPresent(final T sample) {
        return map.containsKey(sample);
    }

    /** {@inheritDoc} */
    @Override
    public boolean addIfAbsent(final T sample) {
        return map.putIfAbsent(sample, sample) == null;
    }

    /** {@inheritDoc} */
    @Override
    public void clear() {
        map.clear();
    }
}
