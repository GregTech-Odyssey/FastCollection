package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

/**
 * A slot entry holding the value weakly and the key strongly (the weak-value
 * caches). {@code hash} is the key's stored hash; the node is its own value
 * reference, so a collection event enqueued by the garbage collector names the
 * node directly. An entry whose value has been collected is dead and is
 * replaced by writers or dropped by the sweep.
 */
final class WeakReferenceValueNode<K, V> extends WeakReference<V> {

    final K key;
    final int hash;

    WeakReferenceValueNode(K key, V value, int hash) {
        super(value);
        this.key = key;
        this.hash = hash;
    }
}
