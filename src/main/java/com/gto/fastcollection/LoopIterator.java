package com.gto.fastcollection;

import java.util.Iterator;

/**
 * A lightweight array iterator that is also its own {@link Iterable}. {@link #iterator()}
 * returns {@code this} after resetting the cursor, so a single instance can be
 * reused across {@code for-each} loops without allocating a new iterator each
 * time; consequently it is not safe for concurrent or nested iteration.
 *
 * @param <T> the element type
 */
public final class LoopIterator<T> implements Iterable<T>, Iterator<T> {

    public static final LoopIterator EMPTY = new LoopIterator(new Object[0]);

    public static <T> LoopIterator<T> empty() {
        return EMPTY;
    }

    public final T[] array;
    public final int size;
    private int index;
    private int remaining;

    public LoopIterator(T[] array) {
        this.array = array;
        this.size = this.array.length;
    }

    @Override
    public Iterator<T> iterator() {
        remaining = size;
        return this;
    }

    @Override
    public boolean hasNext() {
        return remaining > 0;
    }

    @Override
    public T next() {
        if (index == size) index = 0;
        T element = array[index++];
        remaining--;
        return element;
    }
}
