package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

/**
 * A chain node holding the interned instance weakly. The referent is the value;
 * {@code hash} is the key's stored hash (so equality probes can skip mismatches
 * without dereferencing the referent), and {@code next} links the chain.
 */
final class WeakReferenceNode<T> extends WeakReference<T> {

    final int hash;
    volatile WeakReferenceNode<T> next;

    WeakReferenceNode(T value, int hash, WeakReferenceNode<T> next) {
        super(value);
        this.hash = hash;
        this.next = next;
    }
}
