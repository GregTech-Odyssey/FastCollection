package com.gto.fastcollection.cache;

import java.util.function.UnaryOperator;

/**
 * A thread-safe object interner: for each distinct key it retains at most one
 * canonical instance, so equal-but-distinct objects can be collapsed to a single
 * shared reference. Useful for saving memory and enabling {@code ==} comparisons
 * where values are repeatedly re-created from the same logical content.
 */
public interface Interner<T> {

    /**
     * The shared identity mapping (no remapping), reused by every caller.
     */
    UnaryOperator<Object> IDENTITY_OPERATOR = UnaryOperator.identity();

    /**
     * The default mapping: the identity function, so the sample is interned as-is.
     *
     * @param <T> the sample type
     * @return the shared identity mapping
     */
    @SuppressWarnings("unchecked")
    static <T> UnaryOperator<T> identityMappingFunction() {
        return (UnaryOperator<T>) IDENTITY_OPERATOR;
    }

    /**
     * Returns the canonical instance equal to {@code sample}, inserting
     * {@code sample} as the canonical instance if none is interned yet.
     */
    T intern(final T sample);

    /**
     * Like {@link #intern(Object)}, but inserts {@code mappingFunction.apply(sample)}
     * as the canonical instance when none equal is interned yet, instead of
     * {@code sample} itself. Useful for storing a derived (e.g. interned-by-content)
     * form as the canonical instance.
     */
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
