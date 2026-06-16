package com.gto.fastcollection.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class HashInterner<T> implements Interner<T> {

    private final ConcurrentHashMap<T, T> map;
    private final Function<T, T> function = Function.identity();

    public HashInterner() {
        this.map = new ConcurrentHashMap<>();
    }

    @Override
    public T intern(final T sample) {
        return map.computeIfAbsent(sample, function);
    }

    @Override
    public void clear() {
        map.clear();
    }
}
