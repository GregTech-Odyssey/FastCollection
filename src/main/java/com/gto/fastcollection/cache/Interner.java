package com.gto.fastcollection.cache;

public interface Interner<T> {

    T intern(final T sample);

    void clear();
}
