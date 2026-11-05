package com.gto.fastcollection.cache;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * A chain node holding the interned instance weakly. The referent is the value;
 * {@code hash} is the key's stored hash (so equality probes can skip mismatches
 * without dereferencing the referent), and {@code next} links the chain. The
 * node registers with its segment's reference queue, so a collected referent
 * wakes up as a drain candidate instead of lingering in the table.
 */
final class WeakReferenceNode<T> extends WeakReference<T> implements ChainNode<WeakReferenceNode<T>> {

    final int hash;
    volatile WeakReferenceNode<T> next;

    WeakReferenceNode(T value, int hash, WeakReferenceNode<T> next, ReferenceQueue<Object> queue) {
        super(value, queue);
        this.hash = hash;
        this.next = next;
    }

    @Override
    public WeakReferenceNode<T> getNext() {
        return next;
    }

    @Override
    public void setNext(WeakReferenceNode<T> next) {
        this.next = next;
    }
}
