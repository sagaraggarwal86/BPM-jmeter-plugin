package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants; // CHANGED: renamed bottleneck constants
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for model POJOs: Jackson serialization roundtrip,
 * schema compliance, and DerivedMetrics precision.
 */
@DisplayName("Model POJOs")
class ModelSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("BpmResult serializes and deserializes with all fields intact")
    void bpmResult_fullRoundtrip_allFieldsPreserved() throws Exception {
        WebVitalsResult vitals = new WebVitalsResult(420L, 1180L, 0.02, 380L);
        NetworkResult network = new NetworkResult(23, 847000L, 0,
                List.of(new ResourceEntry("/static/dashboard.js", 290, 225000, 120)));
        RuntimeResult runtime = new RuntimeResult(18400000L, 1847, 12, 8);
        ConsoleResult console = new ConsoleResult(0, 1, List.of("Deprecation warning: ..."));
        DerivedMetrics derived = new DerivedMetrics(800L, 32.20, null, 760L,
                null, null, 0.0, BpmConstants.BOTTLENECK_CLIENT,
                List.of(BpmConstants.BOTTLENECK_CLIENT), 82); // CHANGED: 10-arg constructor, renamed fields

        BpmResult original = new BpmResult("1.0", "2026-03-26T14:30:22.451Z",
                "Thread Group 1-1", 3, "Login Page", true, 2340,
                vitals, network, runtime, console, derived);

        String json = mapper.writeValueAsString(original);
        BpmResult deserialized = mapper.readValue(json, BpmResult.class);

        assertEquals(original.bpmVersion(), deserialized.bpmVersion());
        assertEquals(original.threadName(), deserialized.threadName());
        assertEquals(original.iterationNumber(), deserialized.iterationNumber());
        assertEquals(original.samplerLabel(), deserialized.samplerLabel());
        assertEquals(original.samplerSuccess(), deserialized.samplerSuccess());
        assertEquals(original.samplerDuration(), deserialized.samplerDuration());
    }

    @Test
    @DisplayName("WebVitalsResult roundtrip preserves all metric values")
    void webVitalsResult_roundtrip_valuesPreserved() throws Exception {
        WebVitalsResult original = new WebVitalsResult(420L, 1180L, 0.02, 380L);
        String json = mapper.writeValueAsString(original);
        WebVitalsResult result = mapper.readValue(json, WebVitalsResult.class);

        assertEquals(420, result.fcp());
        assertEquals(1180, result.lcp());
        assertEquals(0.02, result.cls(), 0.0001);
        assertEquals(380, result.ttfb());
    }

    @Test
    @DisplayName("NetworkResult roundtrip preserves totals and slowest entries")
    void networkResult_roundtrip_entriesPreserved() throws Exception {
        ResourceEntry entry = new ResourceEntry("/api/data", 150, 50000, 80);
        NetworkResult original = new NetworkResult(10, 500000L, 1, List.of(entry));

        String json = mapper.writeValueAsString(original);
        NetworkResult result = mapper.readValue(json, NetworkResult.class);

        assertEquals(10, result.totalRequests());
        assertEquals(500000L, result.totalBytes());
        assertEquals(1, result.failedRequests());
        assertEquals(1, result.slowest().size());
        assertEquals("/api/data", result.slowest().get(0).url());
    }

    @Test
    @DisplayName("RuntimeResult roundtrip preserves all runtime metrics")
    void runtimeResult_roundtrip_valuesPreserved() throws Exception {
        RuntimeResult original = new RuntimeResult(18400000L, 1847, 12, 8);
        String json = mapper.writeValueAsString(original);
        RuntimeResult result = mapper.readValue(json, RuntimeResult.class);

        assertEquals(18400000L, result.heapUsed());
        assertEquals(1847, result.domNodes());
        assertEquals(12, result.layoutCount());
        assertEquals(8, result.styleRecalcCount());
    }

    @Test
    @DisplayName("ConsoleResult roundtrip preserves counts and messages")
    void consoleResult_roundtrip_valuesPreserved() throws Exception {
        ConsoleResult original = new ConsoleResult(2, 3, List.of("Error 1", "Error 2"));
        String json = mapper.writeValueAsString(original);
        ConsoleResult result = mapper.readValue(json, ConsoleResult.class);

        assertEquals(2, result.errors());
        assertEquals(3, result.warnings());
        assertEquals(2, result.messages().size());
    }

    @Test
    @DisplayName("DerivedMetrics serverClientRatio preserves 2 decimal precision")
    void derivedMetrics_serverClientRatio_twoDecimalPrecision() throws Exception {
        DerivedMetrics original = new DerivedMetrics(800L, 32.20, null, 760L,
                null, null, 0.0, BpmConstants.BOTTLENECK_CLIENT,
                List.of(BpmConstants.BOTTLENECK_CLIENT), 82); // CHANGED: 10-arg constructor

        String json = mapper.writeValueAsString(original);
        DerivedMetrics result = mapper.readValue(json, DerivedMetrics.class);

        assertEquals(32.20, result.serverClientRatio(), 0.001);
    }

    @Test
    @DisplayName("DerivedMetrics improvementAreas array survives roundtrip") // CHANGED: renamed
    void derivedMetrics_improvementAreasArray_preserved() throws Exception {
        DerivedMetrics original = new DerivedMetrics(800L, 32.20, null, 760L,
                null, null, 5.0, BpmConstants.BOTTLENECK_RELIABILITY,
                List.of(BpmConstants.BOTTLENECK_RELIABILITY, BpmConstants.BOTTLENECK_SERVER), 45); // CHANGED: 10-arg constructor

        String json = mapper.writeValueAsString(original);
        DerivedMetrics result = mapper.readValue(json, DerivedMetrics.class);

        assertEquals(BpmConstants.BOTTLENECK_RELIABILITY, result.improvementArea()); // CHANGED: renamed accessor
        assertEquals(2, result.improvementAreas().size()); // CHANGED: renamed accessor
        assertEquals(BpmConstants.BOTTLENECK_SERVER, result.improvementAreas().get(1)); // CHANGED: renamed accessor
    }

    @Test
    @DisplayName("BpmResult with null sub-results serializes without error")
    void bpmResult_nullSubResults_serializesCleanly() throws Exception {
        BpmResult original = new BpmResult("1.0", "2026-03-26T14:30:22Z",
                "Thread-1", 1, "Test", true, 1000,
                null, null, null, null, null);

        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        assertTrue(json.contains("\"samplerLabel\":\"Test\""));
    }
}