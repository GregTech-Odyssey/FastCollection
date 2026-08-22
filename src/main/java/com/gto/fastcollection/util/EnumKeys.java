package com.gto.fastcollection.util;

/**
 * Shared enum key universes for the {@code Enum2XMap}s. {@link Class#getEnumConstants()}
 * clones its array on every call; {@code java.util.EnumMap} avoids that via the
 * internal {@code SharedSecrets.getEnumConstantsShared}, which is not
 * accessible outside the JDK. The public-API equivalent used here is a
 * {@link ClassValue} cache: the array is cloned once per enum class and then
 * shared, uncloned, by every map instance of that key type.
 */
public final class EnumKeys {

    private EnumKeys() {
    }

    private static final ClassValue<Object[]> KEY_UNIVERSES = new ClassValue<>() {

        @Override
        protected Object[] computeValue(Class<?> type) {
            return type.getEnumConstants();
        }
    };

    /**
     * All constants of {@code keyType}, as one array shared by every caller;
     * must never be mutated.
     *
     * @param keyType the enum class
     * @param <K>     the enum type
     * @return the shared, cached key universe
     */
    @SuppressWarnings("unchecked")
    public static <K extends Enum<K>> K[] universe(Class<K> keyType) {
        return (K[]) KEY_UNIVERSES.get(keyType);
    }
}
