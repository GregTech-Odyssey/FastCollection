package com.gto.fastcollection.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class HashCache<K, V> implements MapCache<K, V> {

    private final ConcurrentHashMap<K, V> map;

    public HashCache() {
        this.map = new ConcurrentHashMap<>();
    }

    @Override
    public V getCache(final K k, Function<? super K, ? extends V> createFunction) {
        return map.computeIfAbsent(k, createFunction);
    }

    @Override
    public void clear() {
        map.clear();
    }
}