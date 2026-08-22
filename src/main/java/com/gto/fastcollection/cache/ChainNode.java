package com.gto.fastcollection.cache;

/**
 * A chain node as seen by the shared segment machinery ({@link HashSegment}).
 * Concrete node shapes differ (strong or weak referent, with or without a
 * stored key/hash), but all link themselves with a {@code next} pointer, which
 * is all resizing and dead-entry sweeping need. The hot lookup loops in the
 * concrete segments keep reading the {@code next} field directly and never go
 * through these methods.
 *
 * @param <N> the concrete node type, so relinking stays type-safe
 */
interface ChainNode<N extends ChainNode<N>> {

    /** The successor of this node in its chain. */
    N getNext();

    /** Relinks this node to {@code next}. */
    void setNext(N next);
}
