package io.github.sagaraggarwal86.jmeter.bpm.util;

/**
 * Safe type conversion utilities for values returned by JavaScript evaluation
 * and CDP commands. Converts {@link Number} instances to primitive types,
 * returning a zero default when the value is not a number.
 *
 * <p>This class is not instantiable; all members are static.
 */
public final class TypeConverters {

    private TypeConverters() {
        throw new UnsupportedOperationException("TypeConverters is a utility class");
    }

    /**
     * Converts a JavaScript/CDP result value to {@code double}.
     * Returns {@code 0.0} if the value is not a {@link Number}.
     *
     * @param value the value to convert (typically from {@code CdpCommandExecutor.executeScript})
     * @return the double value, or {@code 0.0} if not a number
     */
    public static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    /**
     * Converts a JavaScript/CDP result value to {@code long}.
     * Returns {@code 0L} if the value is not a {@link Number}.
     *
     * @param value the value to convert
     * @return the long value, or {@code 0L} if not a number
     */
    public static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /**
     * Converts a JavaScript/CDP result value to {@code int}.
     * Returns {@code 0} if the value is not a {@link Number}.
     *
     * @param value the value to convert
     * @return the int value, or {@code 0} if not a number
     */
    public static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
