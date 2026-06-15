package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

final class WeakReferenceValueNode<K, V> extends WeakReference<V> {

    final K key;
    final int hash;
    volatile WeakReferenceValueNode<K, V> next;

    WeakReferenceValueNode(K key, V value, int hash, WeakReferenceValueNode<K, V> next) {
        super(value);
        this.key = key;
        this.hash = hash;
        this.next = next;
    }
}
