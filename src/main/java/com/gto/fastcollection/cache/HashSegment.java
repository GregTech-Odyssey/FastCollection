package com.gto.fastcollection.cache;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.HashCommon;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
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
 * <p>Collected weak entries are reclaimed through this segment's
 * {@link ReferenceQueue}: every weak node registers with it, and the shared
 * {@link CacheDrainer} daemon thread unlinks exactly the collected nodes —
 * {@code O(collected)}, never a table scan, and never on the caller's thread.
 * A drainer pass over an idle segment costs one {@code queue.poll()} and no
 * lock; a segment with collected nodes takes its write lock for at most
 * {@link #BACKGROUND_BATCH} unlinking steps, so writers are never blocked by
 * bulk reclamation. {@code clearCache()} drains the whole queue on demand.
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

    /**
     * Where collected weak nodes of this segment are enqueued by the GC; see
     * {@link #staleQueue()}.
     */
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /**
     * How many collected nodes a single drainer pass unlinks per segment before
     * yielding; bounds how long {@link CacheDrainer} holds a write lock when a
     * burst of entries died at once (the next pass continues where this one
     * stopped).
     */
    private static final int BACKGROUND_BATCH = 2048;

    protected HashSegment() {
        int n = arraySize(Hash.DEFAULT_INITIAL_SIZE, Hash.DEFAULT_LOAD_FACTOR);
        this.table = newArray(n);
        this.mask = n - 1;
        this.maxFill = (int) (n * Hash.DEFAULT_LOAD_FACTOR);
    }

    /**
     * The queue weak nodes of this segment must be constructed with, so that a
     * collected referent ends up here instead of lingering in the table.
     */
    protected final ReferenceQueue<Object> staleQueue() {
        return queue;
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
    protected final void clear() {
        long stamp = writeLock();
        try {
            if (size != 0) {
                size = 0;
                Arrays.fill(table, null);
            }
            while (queue.poll() != null) {
                // the table is empty now, so the queued nodes have nothing left to unlink
            }
        } finally {
            unlockWrite(stamp);
        }
    }

    /**
     * Unlinks collected nodes; the caller must hold this segment's write lock.
     *
     * @return the number of nodes unlinked
     */
    @SuppressWarnings("unchecked")
    private int expungeLocked(int limit) {
        int processed = 0;
        Reference<?> ref;
        while (processed < limit && (ref = queue.poll()) != null) {
            unlink((N) ref);
            processed++;
        }
        return processed;
    }

    /**
     * One background pass: unlinks collected nodes in batches of at most
     * {@link #BACKGROUND_BATCH}, re-acquiring the write lock per batch so a
     * writer never waits for more than one batch, while a single pass still
     * clears a large backlog. When nothing was collected — the common case —
     * this only peeks the queue, takes no lock and allocates nothing.
     */
    @SuppressWarnings("unchecked")
    final void expunge() {
        int processed = BACKGROUND_BATCH;
        while (processed == BACKGROUND_BATCH) {
            Reference<?> first = queue.poll();
            if (first == null) {
                return;
            }
            long stamp = writeLock();
            try {
                unlink((N) first);
                processed = 1 + expungeLocked(BACKGROUND_BATCH - 1);
            } finally {
                unlockWrite(stamp);
            }
        }
    }

    /**
     * Drains the whole queue under the write lock; used by {@code clearCache()},
     * where an unbounded drain is exactly what the caller asked for.
     */
    final void expungeAll() {
        long stamp = writeLock();
        try {
            expungeLocked(Integer.MAX_VALUE);
        } finally {
            unlockWrite(stamp);
        }
    }

    /**
     * Removes one collected node from its chain. The bucket is re-derived from
     * the node's stored hash, so a resize between enqueue and drain does not
     * matter; a node a writer already unlinked in passing is simply not found.
     */
    private void unlink(N node) {
        int index = HashCommon.mix(nodeHash(node)) & mask;
        N prev = null;
        N curr = table[index];
        while (curr != null) {
            if (curr == node) {
                if (prev == null) {
                    table[index] = curr.getNext();
                } else {
                    prev.setNext(curr.getNext());
                }
                size--;
                return;
            }
            prev = curr;
            curr = curr.getNext();
        }
    }
}
