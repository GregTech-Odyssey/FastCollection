package com.gto.fastcollection;

import java.util.Iterator;

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
