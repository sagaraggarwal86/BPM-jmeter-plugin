package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TrendAnalyzer")
class TrendAnalyzerTest {

    private static BpmTimeBucket bucket(long epochMs, double score, double lcp,
                                        double fcp, double ttfb) {
        return new BpmTimeBucket(epochMs, score, lcp, fcp, ttfb, 0.1, 500, 1);
    }

    @Test
    @DisplayName("Returns null for null input")
    void nullInput() {
        assertNull(TrendAnalyzer.analyze(null));
    }

    @Test
    @DisplayName("Returns null for empty list")
    void emptyList() {
        assertNull(TrendAnalyzer.analyze(Collections.emptyList()));
    }

    @Test
    @DisplayName("Returns null for fewer than 4 buckets")
    void tooFewBuckets() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 80, 1200, 800, 400),
                bucket(2000, 82, 1150, 790, 390),
                bucket(3000, 81, 1180, 810, 410));
        assertNull(TrendAnalyzer.analyze(buckets));
    }

    @Test
    @DisplayName("Stable metrics produce STABLE overall stability")
    void stableMetrics() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 80, 1200, 800, 400),
                bucket(2000, 82, 1150, 790, 390),
                bucket(3000, 81, 1180, 810, 410),
                bucket(4000, 80, 1200, 800, 400));
        TrendData result = TrendAnalyzer.analyze(buckets);

        assertNotNull(result);
        assertEquals(4, result.bucketCount());
        assertEquals("STABLE", result.overallStability());
        assertTrue(result.alerts().isEmpty());
        assertFalse(result.score().degraded());
    }

    @Test
    @DisplayName("Rising LCP produces MOSTLY_STABLE with alert")
    void risingLcp() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 85, 1000, 700, 300),
                bucket(2000, 84, 1050, 710, 310),
                bucket(3000, 83, 1500, 900, 500),
                bucket(4000, 82, 1600, 950, 520));
        TrendData result = TrendAnalyzer.analyze(buckets);

        assertNotNull(result);
        assertEquals("RISING", result.lcp().direction());
        assertTrue(result.lcp().degraded());
        assertFalse(result.alerts().isEmpty());
    }

    @Test
    @DisplayName("Multiple degrading metrics produce DEGRADING stability")
    void degradingMetrics() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 90, 800, 500, 200),
                bucket(2000, 88, 850, 520, 210),
                bucket(3000, 60, 2000, 1200, 800),
                bucket(4000, 55, 2200, 1400, 900));
        TrendData result = TrendAnalyzer.analyze(buckets);

        assertNotNull(result);
        assertEquals("DEGRADING", result.overallStability());
        assertTrue(result.stabilityText().contains("degraded"));
    }

    @Test
    @DisplayName("Falling score is detected as degraded")
    void fallingScore() {
        List<BpmTimeBucket> buckets = List.of(
                bucket(1000, 90, 1000, 700, 300),
                bucket(2000, 88, 1050, 710, 310),
                bucket(3000, 70, 1100, 750, 350),
                bucket(4000, 65, 1080, 740, 340));
        TrendData result = TrendAnalyzer.analyze(buckets);

        assertNotNull(result);
        assertEquals("FALLING", result.score().direction());
        assertTrue(result.score().degraded());
    }

    @Test
    @DisplayName("MetricTrend records contain correct values")
    void metricTrendValues() {
        List<BpmTimeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            buckets.add(bucket(i * 1000L, 80, 1200, 800, 400));
        }
        TrendData result = TrendAnalyzer.analyze(buckets);

        assertNotNull(result);
        assertEquals(6, result.bucketCount());
        assertEquals("Score", result.score().name());
        assertEquals("LCP", result.lcp().name());
        assertEquals("FCP", result.fcp().name());
        assertEquals("TTFB", result.ttfb().name());
    }
}
