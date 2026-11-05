package com.gto.fastcollection.cache;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * A chain node holding the value weakly and the key strongly (weak-value caches).
 * {@code hash} is the key's stored hash; {@code next} links the chain. A node
 * whose value has been collected is dead: writers unlink it in passing and the
 * segment's reference-queue drain removes it.
 */
final class WeakReferenceValueNode<K, V> extends WeakReference<V> implements ChainNode<WeakReferenceValueNode<K, V>> {

    final K key;
    final int hash;
    volatile WeakReferenceValueNode<K, V> next;

    WeakReferenceValueNode(K key, V value, int hash, WeakReferenceValueNode<K, V> next, ReferenceQueue<Object> queue) {
        super(value, queue);
        this.key = key;
        this.hash = hash;
        this.next = next;
    }

    @Override
    public WeakReferenceValueNode<K, V> getNext() {
        return next;
    }

    @Override
    public void setNext(WeakReferenceValueNode<K, V> next) {
        this.next = next;
    }
}
