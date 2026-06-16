package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

final class WeakReferenceNode<T> extends WeakReference<T> {

    final int hash;
    volatile WeakReferenceNode<T> next;

    WeakReferenceNode(T value, int hash, WeakReferenceNode<T> next) {
        super(value);
        this.hash = hash;
        this.next = next;
    }
}
