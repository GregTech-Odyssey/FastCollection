package com.gto.fastcollection.cache;

import java.lang.ref.WeakReference;

/**
 * A slot entry holding the interned canonical instance weakly; the referent is
 * both the key and the value, {@code hash} is the sample's stored hash (so
 * probes can skip mismatches without dereferencing the referent)
 */
final class WeakReferenceNode<T> extends WeakReference<T> {

    final int hash;

    WeakReferenceNode(T value, int hash) {
        super(value);
        this.hash = hash;
    }
}
