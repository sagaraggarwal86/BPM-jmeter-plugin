package io.github.sagaraggarwal86.jmeter.bpm.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layer 1 unit tests for MetricsBuffer.
 */
@DisplayName("MetricsBuffer")
class MetricsBufferTest {

    @Test
    @DisplayName("drainNetworkResponses returns all buffered entries and empties buffer")
    void drainNetworkResponses_returnsAllAndEmpties() {
        MetricsBuffer buffer = new MetricsBuffer();
        buffer.addNetworkResponse(Map.of("url", "/a.js"));
        buffer.addNetworkResponse(Map.of("url", "/b.js"));

        List<Map<String, Object>> drained = buffer.drainNetworkResponses();
        assertEquals(2, drained.size());
        assertEquals(0, buffer.networkResponseCount(), "Buffer should be empty after drain");
    }

    @Test
    @DisplayName("drainConsoleMessages returns all buffered entries and empties buffer")
    void drainConsoleMessages_returnsAllAndEmpties() {
        MetricsBuffer buffer = new MetricsBuffer();
        buffer.addConsoleMessage("error", "Error 1");
        buffer.addConsoleMessage("warning", "Warning 1");

        List<MetricsBuffer.ConsoleEntry> drained = buffer.drainConsoleMessages();
        assertEquals(2, drained.size());
        assertEquals("error", drained.get(0).level());
        assertEquals(0, buffer.consoleMessageCount());
    }

    @Test
    @DisplayName("Null inputs are ignored")
    void nullInputs_ignored() {
        MetricsBuffer buffer = new MetricsBuffer();
        buffer.addNetworkResponse(null);
        buffer.addConsoleMessage(null, null);
        buffer.addConsoleMessage("error", null);
        buffer.addConsoleMessage(null, "text");

        assertEquals(0, buffer.networkResponseCount());
        assertEquals(0, buffer.consoleMessageCount());
    }

    @Test
    @DisplayName("Empty drain returns empty list, not null")
    void emptyDrain_returnsEmptyList() {
        MetricsBuffer buffer = new MetricsBuffer();
        assertEquals(0, buffer.drainNetworkResponses().size());
        assertEquals(0, buffer.drainConsoleMessages().size());
    }

    @Test
    @DisplayName("clear() empties both queues")
    void clear_emptiesBothQueues() {
        MetricsBuffer buffer = new MetricsBuffer();
        buffer.addNetworkResponse(Map.of("url", "/a.js"));
        buffer.addConsoleMessage("error", "Error");

        buffer.clear();
        assertEquals(0, buffer.networkResponseCount());
        assertEquals(0, buffer.consoleMessageCount());
    }
}
