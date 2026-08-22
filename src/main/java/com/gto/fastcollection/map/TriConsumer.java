package com.gto.fastcollection.map;

/**
 * An operation accepting three arguments, used by the nested maps to visit
 * entries without intermediate pair allocations.
 *
 * @param <A> the first argument type
 * @param <B> the second argument type
 * @param <C> the third argument type
 */
@FunctionalInterface
public interface TriConsumer<A, B, C> {

    /** Performs the operation on the given arguments. */
    void accept(A a, B b, C c);
}
