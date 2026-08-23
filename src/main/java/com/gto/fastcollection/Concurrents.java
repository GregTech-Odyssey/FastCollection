package com.gto.fastcollection;

/**
 * Shared runtime constants for the concurrent collections.
 */
public final class Concurrents {

    private Concurrents() {
    }

    /**
     * Cached number of available processors; the default segment count for striped structures.
     */
    public static final int NCPU = Runtime.getRuntime().availableProcessors();
}
