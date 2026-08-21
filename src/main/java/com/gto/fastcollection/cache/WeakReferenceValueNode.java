package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

/**
 * A chain node holding the value weakly and the key strongly (weak-value caches).
 * {@code hash} is the key's stored hash; {@code next} links the chain. A node
 * whose value has been collected is dead and removed by writers or
 * {@code clearInvalid}.
 */
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
