package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Layer 1 unit tests for LabelAggregate — running averages, null score handling,
 * improvement area priority, and edge cases.
 */
@DisplayName("LabelAggregate")
class LabelAggregateTest {

    private LabelAggregate agg;

    private static DerivedMetrics derivedWithScore(Integer score) {
        return new DerivedMetrics(0L, 0.0, null, 0L, null, null, 0.0,
                BpmConstants.BOTTLENECK_NONE, List.of(), score);
    }

    // ── Initial state ────────────────────────────────────────────────────────

    private static DerivedMetrics derivedWithArea(String area) {
        return new DerivedMetrics(0L, 0.0, null, 0L, null, null, 0.0,
                area, List.of(area), 50);
    }

    // ── Single update ────────────────────────────────────────────────────────

    private static DerivedMetrics minDerived() {
        return derivedWithScore(null);
    }

    // ── Multiple updates ─────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        agg = new LabelAggregate();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("New aggregate has zero samples and null average score")
    void newAggregate_zeroState() {
        assertEquals(0, agg.getSampleCount());
        assertNull(agg.getAverageScore());
        assertEquals(0, agg.getAverageRenderTime());
        assertEquals(0, agg.getAverageLcp());
        assertEquals(0, agg.getAverageFcp());
        assertEquals(0, agg.getAverageTtfb());
        assertEquals(0.0, agg.getAverageCls());
        assertEquals(0, agg.getAverageRequests());
        assertEquals(0, agg.getAverageBytes());
        assertEquals(0, agg.getTotalErrors());
        assertEquals(0, agg.getTotalWarnings());
        assertNull(agg.getAverageFrontendTime());
        assertNull(agg.getAverageHeadroom());
        assertEquals(BpmConstants.BOTTLENECK_NONE, agg.getPrimaryImprovementArea());
    }

    @Nested
    @DisplayName("Single sample update")
    class SingleUpdate {

        @Test
        @DisplayName("Updates all counters from a fully-populated sample")
        void singleUpdate_fullPopulated() {
            DerivedMetrics derived = new DerivedMetrics(500L, 50.0, 300L,
                    200L, "Stable", 40, 25.0,
                    BpmConstants.BOTTLENECK_SERVER, List.of(BpmConstants.BOTTLENECK_SERVER), 75);

            WebVitalsResult vitals = new WebVitalsResult(1800L, 2500L, 0.05, 500L);
            NetworkResult network = new NetworkResult(20, 50000L, 0, List.of());
            ConsoleResult console = new ConsoleResult(2, 1, List.of());

            agg.update(derived, vitals, network, console);

            assertEquals(1, agg.getSampleCount());
            assertEquals(75, agg.getAverageScore());
            assertEquals(500, agg.getAverageRenderTime());
            assertEquals(2500, agg.getAverageLcp());
            assertEquals(1800, agg.getAverageFcp());
            assertEquals(500, agg.getAverageTtfb());
            assertEquals(0.05, agg.getAverageCls(), 0.001);
            assertEquals(20, agg.getAverageRequests());
            assertEquals(50000, agg.getAverageBytes());
            assertEquals(2, agg.getTotalErrors());
            assertEquals(1, agg.getTotalWarnings());
            assertEquals(BpmConstants.BOTTLENECK_SERVER, agg.getPrimaryImprovementArea());
        }

        @Test
        @DisplayName("Null performanceScore produces null average")
        void singleUpdate_nullScore() {
            DerivedMetrics derived = new DerivedMetrics(0L, 0.0, null,
                    0L, null, null, 0.0,
                    BpmConstants.BOTTLENECK_NONE, List.of(), null);

            agg.update(derived, null, null, null);

            assertEquals(1, agg.getSampleCount());
            assertNull(agg.getAverageScore(), "Null score should produce null average");
        }
    }

    @Nested
    @DisplayName("Multiple sample updates")
    class MultipleUpdates {

        @Test
        @DisplayName("Average score computed over scored samples only")
        void multipleUpdates_averageIgnoresNullScores() {
            // Score=80
            agg.update(derivedWithScore(80), null, null, null);
            // Score=null (SPA-stale)
            agg.update(derivedWithScore(null), null, null, null);
            // Score=60
            agg.update(derivedWithScore(60), null, null, null);

            assertEquals(3, agg.getSampleCount());
            assertEquals(70, agg.getAverageScore(), "Average should be (80+60)/2 = 70");
        }

        @Test
        @DisplayName("Vitals averaged over non-null samples only")
        void multipleUpdates_vitalsAverageNonNull() {
            agg.update(minDerived(),
                    new WebVitalsResult(1000L, 2000L, 0.1, 400L), null, null);
            agg.update(minDerived(),
                    new WebVitalsResult(null, null, null, null), null, null);
            agg.update(minDerived(),
                    new WebVitalsResult(2000L, 3000L, 0.2, 600L), null, null);

            assertEquals(2500, agg.getAverageLcp());
            assertEquals(1500, agg.getAverageFcp());
            assertEquals(500, agg.getAverageTtfb());
            assertEquals(0.15, agg.getAverageCls(), 0.001);
        }

        @Test
        @DisplayName("Errors and warnings accumulate across samples")
        void multipleUpdates_errorsAccumulate() {
            agg.update(minDerived(), null, null, new ConsoleResult(3, 2, List.of()));
            agg.update(minDerived(), null, null, new ConsoleResult(1, 0, List.of()));

            assertEquals(4, agg.getTotalErrors());
            assertEquals(2, agg.getTotalWarnings());
        }

        @Test
        @DisplayName("Improvement area tracks last non-None value")
        void multipleUpdates_improvementAreaTracksLast() {
            agg.update(derivedWithArea(BpmConstants.BOTTLENECK_RELIABILITY), null, null, null);
            agg.update(derivedWithArea(BpmConstants.BOTTLENECK_NONE), null, null, null);
            agg.update(derivedWithArea(BpmConstants.BOTTLENECK_SERVER), null, null, null);

            assertEquals(BpmConstants.BOTTLENECK_SERVER, agg.getPrimaryImprovementArea());
        }

        @Test
        @DisplayName("Average server ratio and FCP-LCP gap computed correctly")
        void multipleUpdates_serverRatioAndGap() {
            DerivedMetrics d1 = new DerivedMetrics(400L, 30.0, null, 100L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 80);
            DerivedMetrics d2 = new DerivedMetrics(600L, 50.0, null, 200L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 90);
            agg.update(d1, null, null, null);
            agg.update(d2, null, null, null);

            assertEquals(500, agg.getAverageRenderTime());
            assertEquals(40.0, agg.getAverageServerRatio(), 0.001);
            assertEquals(150, agg.getAverageFcpLcpGap());
        }

        @Test
        @DisplayName("Network averages computed from non-null samples")
        void multipleUpdates_networkAverages() {
            agg.update(minDerived(), null,
                    new NetworkResult(10, 20000L, 0, List.of()), null);
            agg.update(minDerived(), null,
                    new NetworkResult(30, 60000L, 0, List.of()), null);

            assertEquals(20, agg.getAverageRequests());
            assertEquals(40000, agg.getAverageBytes());
        }
    }

    @Nested
    @DisplayName("FrontendTime and Headroom averages")
    class FrontendTimeAndHeadroom {

        @Test
        @DisplayName("Average frontendTime computed over non-null samples only")
        void frontendTime_averagedOverNonNull() {
            DerivedMetrics withFt = new DerivedMetrics(0L, 0.0, 300L, 0L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);
            DerivedMetrics withoutFt = new DerivedMetrics(0L, 0.0, null, 0L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);
            DerivedMetrics withFt2 = new DerivedMetrics(0L, 0.0, 500L, 0L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);

            agg.update(withFt, null, null, null);
            agg.update(withoutFt, null, null, null);
            agg.update(withFt2, null, null, null);

            assertEquals(400L, agg.getAverageFrontendTime(), "Average of 300 and 500");
        }

        @Test
        @DisplayName("Average frontendTime is null when no samples have frontendTime")
        void frontendTime_nullWhenNoSamples() {
            agg.update(minDerived(), null, null, null);
            assertNull(agg.getAverageFrontendTime());
        }

        @Test
        @DisplayName("Average headroom computed over non-null samples only")
        void headroom_averagedOverNonNull() {
            DerivedMetrics withH = new DerivedMetrics(0L, 0.0, null, 0L,
                    null, 40, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);
            DerivedMetrics withoutH = new DerivedMetrics(0L, 0.0, null, 0L,
                    null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);
            DerivedMetrics withH2 = new DerivedMetrics(0L, 0.0, null, 0L,
                    null, 60, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50);

            agg.update(withH, null, null, null);
            agg.update(withoutH, null, null, null);
            agg.update(withH2, null, null, null);

            assertEquals(50, agg.getAverageHeadroom(), "Average of 40 and 60");
        }

        @Test
        @DisplayName("Average headroom is null when no samples have headroom")
        void headroom_nullWhenNoSamples() {
            agg.update(minDerived(), null, null, null);
            assertNull(agg.getAverageHeadroom());
        }
    }
}
