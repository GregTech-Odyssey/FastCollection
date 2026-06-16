package com.gto.fastcollection.cache;

import java.util.function.Function;

public interface MapCache<K, V> {

    V getCache(final K k, Function<? super K, ? extends V> createFunction);

    void clear();
}
