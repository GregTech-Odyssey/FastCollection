package com.gto.fastcollection.cache;

import java.util.function.UnaryOperator;

/**
 * A thread-safe object interner: for each distinct key it retains at most one
 * canonical instance, so equal-but-distinct objects can be collapsed to a single
 * shared reference. Useful for saving memory and enabling {@code ==} comparisons
 * where values are repeatedly re-created from the same logical content.
 */
public interface Interner<T> {

    UnaryOperator IDENTITY_OPERATOR = UnaryOperator.identity();

    static <T> UnaryOperator<T> identityMappingFunction() {
        return IDENTITY_OPERATOR;
    }

    /**
     * Returns the canonical instance equal to {@code sample}, inserting
     * {@code sample} as the canonical instance if none is interned yet.
     */
    T intern(final T sample);

    T intern(final T sample, UnaryOperator<T> mappingFunction);

    /**
     * Returns whether an instance equal to {@code sample} is already interned.
     * Never inserts anything.
     */
    boolean isPresent(final T sample);

    /**
     * Inserts {@code sample} if an equal instance is not yet interned, and returns
     * whether it was newly added ({@code true}) or an equal instance was already
     * present ({@code false}).
     */
    boolean addIfAbsent(final T sample);

    /**
     * Removes all interned instances.
     */
    void clear();
}
