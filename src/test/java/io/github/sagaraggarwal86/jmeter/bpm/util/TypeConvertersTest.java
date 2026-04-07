package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layer 1 unit tests for {@link TypeConverters}.
 */
@DisplayName("TypeConverters")
class TypeConvertersTest {

    // ── toDouble ──

    @Test
    @DisplayName("toDouble returns value for Integer")
    void toDouble_integer() {
        assertEquals(42.0, TypeConverters.toDouble(42));
    }

    @Test
    @DisplayName("toDouble returns value for Double")
    void toDouble_double() {
        assertEquals(3.14, TypeConverters.toDouble(3.14), 0.001);
    }

    @Test
    @DisplayName("toDouble returns value for Long")
    void toDouble_long() {
        assertEquals(999.0, TypeConverters.toDouble(999L));
    }

    @Test
    @DisplayName("toDouble returns 0.0 for null")
    void toDouble_null() {
        assertEquals(0.0, TypeConverters.toDouble(null));
    }

    @Test
    @DisplayName("toDouble returns 0.0 for String")
    void toDouble_string() {
        assertEquals(0.0, TypeConverters.toDouble("42"));
    }

    @Test
    @DisplayName("toDouble returns 0.0 for Boolean")
    void toDouble_boolean() {
        assertEquals(0.0, TypeConverters.toDouble(true));
    }

    // ── toLong ──

    @Test
    @DisplayName("toLong returns value for Long")
    void toLong_long() {
        assertEquals(123L, TypeConverters.toLong(123L));
    }

    @Test
    @DisplayName("toLong returns value for Integer")
    void toLong_integer() {
        assertEquals(42L, TypeConverters.toLong(42));
    }

    @Test
    @DisplayName("toLong truncates Double")
    void toLong_double() {
        assertEquals(3L, TypeConverters.toLong(3.99));
    }

    @Test
    @DisplayName("toLong returns 0L for null")
    void toLong_null() {
        assertEquals(0L, TypeConverters.toLong(null));
    }

    @Test
    @DisplayName("toLong returns 0L for String")
    void toLong_string() {
        assertEquals(0L, TypeConverters.toLong("123"));
    }

    // ── toInt ──

    @Test
    @DisplayName("toInt returns value for Integer")
    void toInt_integer() {
        assertEquals(7, TypeConverters.toInt(7));
    }

    @Test
    @DisplayName("toInt returns value for Double")
    void toInt_double() {
        assertEquals(9, TypeConverters.toInt(9.8));
    }

    @Test
    @DisplayName("toInt returns 0 for null")
    void toInt_null() {
        assertEquals(0, TypeConverters.toInt(null));
    }

    @Test
    @DisplayName("toInt returns 0 for non-Number")
    void toInt_nonNumber() {
        assertEquals(0, TypeConverters.toInt("not a number"));
    }
}
