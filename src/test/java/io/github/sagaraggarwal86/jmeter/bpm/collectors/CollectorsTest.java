package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.ConsoleSanitizer;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Layer 1 unit tests for all 4 collectors using mocked CdpCommandExecutor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Collectors")
class CollectorsTest {

    @Mock
    CdpCommandExecutor executor;

    MetricsBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new MetricsBuffer();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // WebVitalsCollector
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WebVitalsCollector")
    class WebVitalsCollectorTests {

        @Test
        @DisplayName("Normal collection returns all four vitals")
        void collect_normalValues_returnsAllFour() {
            when(executor.executeScript(JsSnippets.COLLECT_WEB_VITALS))
                    .thenReturn(Map.of("lcp", 1180.0, "fcp", 420.0, "cls", 0.02, "ttfb", 380.0));

            WebVitalsCollector collector = new WebVitalsCollector();
            WebVitalsResult result = collector.collect(executor, buffer);

            assertNotNull(result);
            assertEquals(1180, result.lcp());
            assertEquals(420, result.fcp());
            assertEquals(0.02, result.cls(), 0.0001);
            assertEquals(380, result.ttfb());
        }

        @Test
        @DisplayName("SPA stale detection: returns partial result with null LCP/FCP/TTFB when unchanged")
        void collect_lcpUnchanged_returnsPartialWithNullLcp() { // CHANGED: Bug 12 — FCP/TTFB also stale
            when(executor.executeScript(JsSnippets.COLLECT_WEB_VITALS))
                    .thenReturn(Map.of("lcp", 1180.0, "fcp", 420.0, "cls", 0.0, "ttfb", 380.0));

            WebVitalsCollector collector = new WebVitalsCollector();
            // First call — stores LCP, FCP, TTFB
            WebVitalsResult first = collector.collect(executor, buffer);
            assertNotNull(first);

            // Second call with same values — SPA stale: LCP/FCP/TTFB null, CLS preserved
            WebVitalsResult second = collector.collect(executor, buffer);
            assertNotNull(second, "Should return partial result for stale SPA navigation");
            assertNull(second.lcp(), "LCP should be null for stale SPA detection");
            assertNull(second.fcp(), "FCP should be null for stale SPA detection"); // CHANGED: Bug 12
            assertEquals(0.0, second.cls(), 0.0001, "CLS should be preserved (accumulates)");
            assertNull(second.ttfb(), "TTFB should be null for stale SPA detection"); // CHANGED: Bug 12
        }

        @Test
        @DisplayName("lcp=0 and fcp=0 are treated as unavailable (null), not perfect 0 ms values") // CHANGED: per-action accuracy — lcp=0/fcp=0 mean no event fired yet
        void collect_zeroLcpFcp_returnsNull() { // CHANGED: per-action accuracy
            when(executor.executeScript(JsSnippets.COLLECT_WEB_VITALS))
                    .thenReturn(Map.of("lcp", 0.0, "fcp", 0.0, "cls", 0.0, "ttfb", 0.0));

            WebVitalsCollector collector = new WebVitalsCollector();
            WebVitalsResult result = collector.collect(executor, buffer);

            assertNotNull(result);
            assertNull(result.lcp(), "lcp=0 means no LCP event fired — must be null, not a perfect 0 ms score"); // CHANGED: per-action accuracy
            assertNull(result.fcp(), "fcp=0 means no FCP event fired — must be null, not a perfect 0 ms score"); // CHANGED: per-action accuracy
            assertEquals(0, result.ttfb()); // ttfb=0 is a valid (very fast) first byte time, not guarded
        }

        @Test
        @DisplayName("Unexpected return type returns null")
        void collect_unexpectedReturnType_returnsNull() {
            when(executor.executeScript(JsSnippets.COLLECT_WEB_VITALS))
                    .thenReturn("unexpected string");

            WebVitalsCollector collector = new WebVitalsCollector();
            assertNull(collector.collect(executor, buffer));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // NetworkCollector
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NetworkCollector")
    class NetworkCollectorTests {

        @Test
        @DisplayName("Normal collection sorts by duration and returns top N")
        void collect_multipleEntries_sortsAndReturnsTopN() {
            buffer.addNetworkResponse(Map.of("url", "/fast.js", "duration", 50L, "transferSize", 1000L, "ttfb", 20L, "status", 200));
            buffer.addNetworkResponse(Map.of("url", "/slow.js", "duration", 500L, "transferSize", 5000L, "ttfb", 100L, "status", 200));
            buffer.addNetworkResponse(Map.of("url", "/mid.js", "duration", 200L, "transferSize", 2000L, "ttfb", 50L, "status", 200));

            NetworkCollector collector = new NetworkCollector(2);
            NetworkResult result = collector.collect(executor, buffer);

            assertEquals(3, result.totalRequests());
            assertEquals(8000L, result.totalBytes());
            assertEquals(0, result.failedRequests());
            // Top 2 slowest: /slow.js (500), /mid.js (200)
            assertEquals(2, result.slowest().size());
            assertEquals("/slow.js", result.slowest().get(0).url());
        }

        @Test
        @DisplayName("Failed requests (4xx/5xx) always included in slowest list")
        void collect_failedRequests_alwaysIncluded() {
            buffer.addNetworkResponse(Map.of("url", "/ok.js", "duration", 100L, "transferSize", 1000L, "ttfb", 10L, "status", 200));
            buffer.addNetworkResponse(Map.of("url", "/error.js", "duration", 10L, "transferSize", 500L, "ttfb", 5L, "status", 500));

            NetworkCollector collector = new NetworkCollector(1);
            NetworkResult result = collector.collect(executor, buffer);

            assertEquals(1, result.failedRequests());
            // Top 1 = /ok.js (100ms), but /error.js also included because it's failed
            assertEquals(2, result.slowest().size());
        }

        @Test
        @DisplayName("Empty buffer returns zero totals")
        void collect_emptyBuffer_returnsZeros() {
            NetworkCollector collector = new NetworkCollector(5);
            NetworkResult result = collector.collect(executor, buffer);

            assertEquals(0, result.totalRequests());
            assertEquals(0L, result.totalBytes());
            assertTrue(result.slowest().isEmpty());
        }

        @Test
        @DisplayName("Buffer is drained after collection (no double counting)")
        void collect_drainsBuffer_noDuplicatesOnSecondCall() {
            buffer.addNetworkResponse(Map.of("url", "/a.js", "duration", 100L, "transferSize", 1000L, "ttfb", 10L, "status", 200));

            NetworkCollector collector = new NetworkCollector(5);
            collector.collect(executor, buffer);
            NetworkResult second = collector.collect(executor, buffer);

            assertEquals(0, second.totalRequests(), "Buffer should be empty after first drain");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // RuntimeCollector
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RuntimeCollector")
    class RuntimeCollectorTests {

        @Test
        @DisplayName("Normal collection extracts all four runtime metrics")
        void collect_normalMetrics_extractsAll() {
            Map<String, Object> response = Map.of("metrics", List.of(
                    Map.of("name", "JSHeapUsedSize", "value", 18400000.0),
                    Map.of("name", "Nodes", "value", 1847.0),
                    Map.of("name", "LayoutCount", "value", 12.0),
                    Map.of("name", "RecalcStyleCount", "value", 8.0),
                    Map.of("name", "SomeOtherMetric", "value", 999.0)));

            when(executor.executeCdpCommand(eq(JsSnippets.CDP_METHOD_PERFORMANCE_GET_METRICS), eq(Map.of())))
                    .thenReturn(response);

            RuntimeCollector collector = new RuntimeCollector();
            RuntimeResult result = collector.collect(executor, buffer);

            assertEquals(18400000L, result.heapUsed());
            assertEquals(1847, result.domNodes());
            assertEquals(12, result.layoutCount());
            assertEquals(8, result.styleRecalcCount());
        }

        @Test
        @DisplayName("Empty metrics array returns zeros")
        void collect_emptyMetrics_returnsZeros() {
            when(executor.executeCdpCommand(eq(JsSnippets.CDP_METHOD_PERFORMANCE_GET_METRICS), eq(Map.of())))
                    .thenReturn(Map.of("metrics", List.of()));

            RuntimeCollector collector = new RuntimeCollector();
            RuntimeResult result = collector.collect(executor, buffer);

            assertEquals(0L, result.heapUsed());
            assertEquals(0, result.domNodes());
        }

        @Test
        @DisplayName("Missing metrics key returns zeros gracefully")
        void collect_noMetricsKey_returnsZeros() {
            when(executor.executeCdpCommand(eq(JsSnippets.CDP_METHOD_PERFORMANCE_GET_METRICS), eq(Map.of())))
                    .thenReturn(Map.of());

            RuntimeCollector collector = new RuntimeCollector();
            RuntimeResult result = collector.collect(executor, buffer);

            assertEquals(0L, result.heapUsed());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // ConsoleCollector
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ConsoleCollector")
    class ConsoleCollectorTests {

        @Test
        @DisplayName("Separates errors and warnings correctly")
        void collect_mixedLevels_separatesCorrectly() {
            buffer.addConsoleMessage("error", "ReferenceError: x is not defined");
            buffer.addConsoleMessage("warning", "Deprecation notice");
            buffer.addConsoleMessage("error", "TypeError: null");

            ConsoleCollector collector = new ConsoleCollector(new ConsoleSanitizer(false));
            ConsoleResult result = collector.collect(executor, buffer);

            assertEquals(2, result.errors());
            assertEquals(1, result.warnings());
            assertEquals(3, result.messages().size());
        }

        @Test
        @DisplayName("Sanitization applied to messages")
        void collect_withSanitizer_redactsSensitiveData() {
            buffer.addConsoleMessage("error", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test.sig"); // CHANGED: "Token:" doesn't match Bearer pattern — requires "Authorization:" or "Auth:"

            ConsoleCollector collector = new ConsoleCollector(new ConsoleSanitizer(true));
            ConsoleResult result = collector.collect(executor, buffer);

            assertEquals(1, result.errors());
            assertTrue(result.messages().get(0).contains("[REDACTED]"));
        }

        @Test
        @DisplayName("Empty buffer returns zero counts")
        void collect_emptyBuffer_returnsZeros() {
            ConsoleCollector collector = new ConsoleCollector(new ConsoleSanitizer(false));
            ConsoleResult result = collector.collect(executor, buffer);

            assertEquals(0, result.errors());
            assertEquals(0, result.warnings());
            assertTrue(result.messages().isEmpty());
        }
    }
}