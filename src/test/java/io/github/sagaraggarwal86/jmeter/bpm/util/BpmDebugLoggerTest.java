package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for BpmDebugLogger — both enabled and disabled modes.
 */
@DisplayName("BpmDebugLogger")
class BpmDebugLoggerTest {

    @Test
    @DisplayName("Disabled logger reports isEnabled=false and all methods are no-ops")
    void disabled_allMethodsNoOp() {
        BpmDebugLogger logger = new BpmDebugLogger(false);
        assertFalse(logger.isEnabled());

        // All methods should complete without error when disabled
        assertDoesNotThrow(() -> logger.logCollection("Page", 1, 2, 3, 4));
        assertDoesNotThrow(() -> logger.logCdpSessionOpened("Thread-1", 50));
        assertDoesNotThrow(() -> logger.logDerivedMetrics("Page", 85, "Server response"));
        assertDoesNotThrow(() -> logger.logDerivedMetrics("Page", null, "None"));
        assertDoesNotThrow(() -> logger.logJsonlWrite(1, 500, 2));
        assertDoesNotThrow(() -> logger.logNetworkBufferDrained(10));
        assertDoesNotThrow(() -> logger.logCdpReInit("Thread-1", 1));
        assertDoesNotThrow(() -> logger.log("simple message"));
        assertDoesNotThrow(() -> logger.log("format {}", "arg1"));
        assertDoesNotThrow(() -> logger.log("format {} {}", "arg1", "arg2"));
    }

    @Test
    @DisplayName("Enabled logger reports isEnabled=true and all methods complete")
    void enabled_allMethodsComplete() {
        BpmDebugLogger logger = new BpmDebugLogger(true);
        assertTrue(logger.isEnabled());

        assertDoesNotThrow(() -> logger.logCollection("Page", 10, 20, 30, 40));
        assertDoesNotThrow(() -> logger.logCdpSessionOpened("Thread-1", 84));
        assertDoesNotThrow(() -> logger.logDerivedMetrics("Page", 82, "Client rendering"));
        assertDoesNotThrow(() -> logger.logDerivedMetrics("SPA Page", null, "None"));
        assertDoesNotThrow(() -> logger.logJsonlWrite(1, 482, 1));
        assertDoesNotThrow(() -> logger.logNetworkBufferDrained(23));
        assertDoesNotThrow(() -> logger.logCdpReInit("Thread-1", 1));
        assertDoesNotThrow(() -> logger.log("hello"));
        assertDoesNotThrow(() -> logger.log("hello {}", "world"));
        assertDoesNotThrow(() -> logger.log("hello {} {}", "world", "!"));
    }
}
