package com.gto.fastcollection;

/**
 * A key whose {@code hashCode} and {@code equals} both do real work (character
 * loops), so benchmarks can show how much the cached-hash collections save by
 * skipping {@code equals} probes and reusing stored hashes. Each instance
 * carries its own char array, so value equality requires a per-character scan.
 */
final class ExpensiveKey {

    private final char[] chars;

    ExpensiveKey(String s) {
        this.chars = s.toCharArray();
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < chars.length; i++) {
            h = h * 31 + chars[i];
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ExpensiveKey other) || other.chars.length != chars.length) {
            return false;
        }
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != other.chars[i]) {
                return false;
            }
        }
        return true;
    }
}
