package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TrendAnalyzer — direction detection, edge cases, alerts.
 */
@DisplayName("TrendAnalyzer")
class TrendAnalyzerTest {

    private static BpmTimeBucket bucket(long epochMs, double score, double lcp,
                                        double fcp, double ttfb, int count) {
        return new BpmTimeBucket(epochMs, score, lcp, fcp, ttfb, count);
    }

    // ── Insufficient data ────────────────────────────────────────────────────

    @Test
    @DisplayName("Null input returns null")
    void nullInput_returnsNull() {
        assertNull(TrendAnalyzer.analyze(null));
    }

    @Test
    @DisplayName("Empty list returns null")
    void emptyList_returnsNull() {
        assertNull(TrendAnalyzer.analyze(Collections.emptyList()));
    }

    @Test
    @DisplayName("Fewer than MIN_BUCKETS returns null")
    void tooFewBuckets_returnsNull() {
        List<BpmTimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < TrendAnalyzer.MIN_BUCKETS - 1; i++) {
            buckets.add(bucket(i * 1000L, 80, 1000, 500, 200, 5));
        }
        assertNull(TrendAnalyzer.analyze(buckets));
    }

    // ── STABLE detection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("All -1 scores (no data) produce STABLE with zero averages")
    void allSentinelScores_stable() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, -1, 0, 0, 0, 5),
                bucket(2000, -1, 0, 0, 0, 5),
                bucket(3000, -1, 0, 0, 0, 5),
                bucket(4000, -1, 0, 0, 0, 5));

        ObjectNode result = TrendAnalyzer.analyze(buckets);
        assertNotNull(result);
        assertEquals("STABLE", result.path("metricTrends").path("score").path("direction").asText());
    }

    // ── DEGRADING detection ──────────────────────────────────────────────────

    @Test
    @DisplayName("Alerts only generated for degraded metrics")
    void alerts_onlyForDegraded() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 80, 1000, 500, 200, 5),
                bucket(2000, 80, 1000, 500, 200, 5),
                bucket(3000, 80, 3000, 500, 200, 5),
                bucket(4000, 80, 3500, 500, 200, 5));

        ObjectNode result = TrendAnalyzer.analyze(buckets);
        int alertCount = result.path("alerts").size();
        assertTrue(alertCount >= 1, "Should have at least one alert for LCP");
        // Score is stable, so no score alert
        boolean hasScoreAlert = false;
        for (var alert : result.path("alerts")) {
            if (alert.asText().contains("Score")) hasScoreAlert = true;
        }
        assertFalse(hasScoreAlert, "Stable score should not generate an alert");
    }

    // ── Sentinel handling ────────────────────────────────────────────────────

    @Test
    @DisplayName("Result contains all expected fields")
    void resultStructure() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 80, 1000, 500, 200, 5),
                bucket(2000, 80, 1000, 500, 200, 5),
                bucket(3000, 80, 1000, 500, 200, 5),
                bucket(4000, 80, 1000, 500, 200, 5));

        ObjectNode result = TrendAnalyzer.analyze(buckets);
        assertTrue(result.has("bucketCount"));
        assertTrue(result.has("metricTrends"));
        assertTrue(result.has("alerts"));
        assertTrue(result.has("overallStability"));
        assertTrue(result.has("stabilityText"));

        ObjectNode metrics = (ObjectNode) result.get("metricTrends");
        assertTrue(metrics.has("score"));
        assertTrue(metrics.has("lcp"));
        assertTrue(metrics.has("fcp"));
        assertTrue(metrics.has("ttfb"));
    }

    // ── Alerts ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stable trends")
    class StableTrends {

        @Test
        @DisplayName("Consistent scores produce STABLE direction")
        void consistentScores_stable() {
            List<BpmTimeBucket> buckets = List.of(
                    bucket(1000, 80, 1000, 500, 200, 5),
                    bucket(2000, 82, 1020, 510, 210, 5),
                    bucket(3000, 79, 990, 490, 195, 5),
                    bucket(4000, 81, 1010, 505, 205, 5));

            ObjectNode result = TrendAnalyzer.analyze(buckets);
            assertNotNull(result);
            assertEquals("STABLE", result.path("overallStability").asText());
        }
    }

    // ── Structure ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Degrading trends")
    class DegradingTrends {

        @Test
        @DisplayName("Rising LCP produces RISING direction with alert")
        void risingLcp_degrading() {
            List<BpmTimeBucket> buckets = List.of(
                    bucket(1000, 80, 1000, 500, 200, 5),
                    bucket(2000, 78, 1100, 510, 210, 5),
                    bucket(3000, 50, 3000, 600, 300, 5),
                    bucket(4000, 45, 3500, 650, 350, 5));

            ObjectNode result = TrendAnalyzer.analyze(buckets);
            assertNotNull(result);

            String lcpDir = result.path("metricTrends").path("lcp").path("direction").asText();
            assertEquals("RISING", lcpDir);

            assertTrue(result.path("metricTrends").path("lcp").path("degraded").asBoolean());
        }

        @Test
        @DisplayName("Falling score produces FALLING direction")
        void fallingScore_degrading() {
            List<BpmTimeBucket> buckets = List.of(
                    bucket(1000, 90, 1000, 500, 200, 5),
                    bucket(2000, 88, 1050, 510, 210, 5),
                    bucket(3000, 50, 1000, 500, 200, 5),
                    bucket(4000, 40, 1000, 500, 200, 5));

            ObjectNode result = TrendAnalyzer.analyze(buckets);
            String scoreDir = result.path("metricTrends").path("score").path("direction").asText();
            assertEquals("FALLING", scoreDir);
        }
    }
}
