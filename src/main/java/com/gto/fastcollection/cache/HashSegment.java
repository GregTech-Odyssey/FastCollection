package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;

/**
 * The shared skeleton of the striped segments: an independently locked hash
 * table with separate chaining, extending {@link StampedLock} so lock calls
 * are direct. It owns the table state and the cold-path operations (resize,
 * clear, dead-entry reclamation); concrete subclasses keep their hot lookup and
 * write loops and only supply how to read a node's hash and how to create the
 * node array. Only the segment's own table is touched while its lock is held;
 * other segments proceed in parallel.
 *
 * <p>{@code table}/{@code mask} are volatile because a resize swaps them;
 * {@code size}/{@code maxFill} are only mutated under the write lock and read
 * under the read lock.
 *
 * @param <N> the concrete chain-node type of the subclass
 */
public abstract class HashSegment<N extends ChainNode<N>> extends StampedLock {

    protected volatile N[] table;
    protected volatile int mask;
    protected volatile int size;
    protected volatile int maxFill;

    protected HashSegment() {
        int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
        this.table = newArray(n);
        this.mask = n - 1;
        this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
    }

    /**
     * The stable hash a node was stored under.
     */
    protected abstract int nodeHash(N node);

    /**
     * Creates a table of the concrete node type; subclasses must return an
     * array of exactly that type so the compiler-inserted casts on
     * {@code table} accesses always succeed.
     */
    protected abstract N[] newArray(int capacity);

    /**
     * Doubles the table and rehashes every chain, splitting each chain into its
     * lower and upper half; called with the write lock held.
     */
    protected final void resize() {
        N[] oldTab = this.table;
        int oldCap = oldTab.length;
        int newCap = oldCap << 1;
        N[] newTab = newArray(newCap);
        int newMask = newCap - 1;
        for (int i = 0; i < oldCap; ++i) {
            N e;
            if ((e = oldTab[i]) != null) {
                oldTab[i] = null;
                if (e.getNext() == null) {
                    newTab[HashCommon.mix(nodeHash(e)) & newMask] = e;
                } else {
                    N loHead = null, loTail = null;
                    N hiHead = null, hiTail = null;
                    N next;
                    do {
                        next = e.getNext();
                        if ((HashCommon.mix(nodeHash(e)) & oldCap) == 0) {
                            if (loTail == null)
                                loHead = e;
                            else
                                loTail.setNext(e);
                            loTail = e;
                        } else {
                            if (hiTail == null)
                                hiHead = e;
                            else
                                hiTail.setNext(e);
                            hiTail = e;
                        }
                    } while ((e = next) != null);
                    if (loTail != null) {
                        loTail.setNext(null);
                        newTab[HashCommon.mix(nodeHash(loHead)) & newMask] = loHead;
                    }
                    if (hiTail != null) {
                        hiTail.setNext(null);
                        newTab[HashCommon.mix(nodeHash(hiHead)) & newMask] = hiHead;
                    }
                }
            }
        }
        this.table = newTab;
        this.mask = newMask;
        this.maxFill = (int) (newCap * Hash.DEFAULT_LOAD_FACTOR);
    }

    /**
     * Empties the table under the write lock; nodes still sitting in the queue
     * are dropped along with it.
     */
    protected void clear() {
        long stamp = writeLock();
        try {
            if (size != 0) {
                size = 0;
                Arrays.fill(table, null);
            }
        } finally {
            unlockWrite(stamp);
        }
    }
}
