package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for DerivedMetricsCalculator: performance score,
 * all 6 improvement area labels, 3 new derived columns, edge cases.
 */ // CHANGED: updated class and method names throughout for renamed/new fields
@DisplayName("DerivedMetricsCalculator")
class DerivedMetricsCalculatorTest {

    private DerivedMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        BpmPropertiesManager props = new BpmPropertiesManager() {
            @Override
            protected Path resolvePropertiesPath() {
                return Path.of(System.getProperty("java.io.tmpdir"), "bpm-test-calc.properties");
            }
            @Override
            public String getJMeterProperty(String key) { return null; }
        };
        props.load();
        calculator = new DerivedMetricsCalculator(props);
    }

    // ── Score ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Perfect vitals produce score 100")
    void compute_perfectVitals_score100() {
        WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 200L);
        DerivedMetrics result = calculator.compute(vitals, new NetworkResult(10, 100000L, 0, List.of()),
                new RuntimeResult(5000000L, 500, 5, 3), new ConsoleResult(0, 0, List.of()), 2000);
        assertEquals(100, result.performanceScore());
    }

    @Test
    @DisplayName("Poor vitals produce score 0")
    void compute_poorVitals_score0() {
        WebVitalsResult vitals = new WebVitalsResult(5000L, 6000L, 0.5, 3000L);
        DerivedMetrics result = calculator.compute(vitals, new NetworkResult(10, 100000L, 0, List.of()),
                new RuntimeResult(5000000L, 500, 5, 3), new ConsoleResult(10, 0, List.of()), 2000);
        assertEquals(0, result.performanceScore());
    }

    @Test
    @DisplayName("SPA-stale sample (only CLS + errors) produces null score, not inflated 100")
    void compute_spaStale_nullScore() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(null, null, 0.0, null), null, null,
                new ConsoleResult(0, 0, List.of()), 1000);
        assertNull(result.performanceScore(),
                "SPA-stale: only CLS+errors contribute — weight 0.30 < SCORE_MIN_WEIGHT 0.45 → null");
    }

    @Test
    @DisplayName("All null sub-results produce safe defaults and null score")
    void compute_allNull_safeDefaults() {
        DerivedMetrics result = calculator.compute(null, null, null, null, 1000);
        assertNotNull(result);
        assertEquals(0, result.renderTime());
        assertEquals(0.0, result.serverClientRatio());
        assertEquals(BpmConstants.BOTTLENECK_NONE, result.improvementArea());
        assertNull(result.performanceScore());
    }

    // ── Existing derived metrics ──────────────────────────────────────────────

    @Test
    @DisplayName("renderTime = LCP - TTFB")
    void compute_renderTime_isLcpMinusTtfb() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1180L, 0.01, 380L), null, null, null, 2000);
        assertEquals(800, result.renderTime());
    }

    @Test
    @DisplayName("serverClientRatio has 2 decimal precision")
    void compute_serverRatio_twoDecimalPrecision() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1180L, 0.01, 380L), null, null, null, 2000);
        assertEquals(32.20, result.serverClientRatio(), 0.01);
    }

    @Test
    @DisplayName("fcpLcpGap = LCP - FCP")
    void compute_fcpLcpGap_correct() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(420L, 1180L, 0.01, 380L), null, null, null, 2000);
        assertEquals(760, result.fcpLcpGap());
    }

    @Test
    @DisplayName("failedRequestRate computed correctly")
    void compute_failedRequestRate_correct() {
        DerivedMetrics result = calculator.compute(
                null, new NetworkResult(20, 100000L, 5, List.of()), null, null, 2000);
        assertEquals(25.0, result.failedRequestRate(), 0.01);
    }

    @Test
    @DisplayName("No network data produces 0% failed request rate")
    void compute_noNetworkData_zeroFailedRate() {
        assertEquals(0.0, calculator.compute(null, null, null, null, 1000).failedRequestRate());
    }

    @Test
    @DisplayName("LCP=0 produces zero ratios without division error")
    void compute_lcpZero_noDivisionError() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(0L, 0L, 0.0, 0L), null, null, null, 2000);
        assertEquals(0.0, result.serverClientRatio());
        assertEquals(0, result.renderTime());
    }

    // ── New derived columns ───────────────────────────────────────────────────

    @Test
    @DisplayName("frontendTime = FCP - TTFB when both present") // CHANGED: new
    void compute_frontendTime_isFcpMinusTtfb() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1000L, 0.01, 200L), null, null, null, 2000);
        assertNotNull(result.frontendTime());
        assertEquals(300L, result.frontendTime(), "FCP(500) - TTFB(200) = 300ms");
    }

    @Test
    @DisplayName("frontendTime is null when FCP or TTFB is null") // CHANGED: new
    void compute_frontendTime_nullWhenSpaStale() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(null, null, 0.0, null), null, null, null, 2000);
        assertNull(result.frontendTime());
    }

    @Test
    @DisplayName("stabilityCategory = Stable when CLS <= 0.10") // CHANGED: new
    void compute_stabilityCategory_stable() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1000L, 0.05, 200L), null, null, null, 2000);
        assertEquals(BpmConstants.STABILITY_STABLE, result.stabilityCategory());
    }

    @Test
    @DisplayName("stabilityCategory = Minor Shifts when CLS between 0.10 and 0.25") // CHANGED: new
    void compute_stabilityCategory_minorShifts() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1000L, 0.15, 200L), null, null, null, 2000);
        assertEquals(BpmConstants.STABILITY_MINOR_SHIFTS, result.stabilityCategory());
    }

    @Test
    @DisplayName("stabilityCategory = Unstable when CLS > 0.25") // CHANGED: new
    void compute_stabilityCategory_unstable() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 1000L, 0.30, 200L), null, null, null, 2000);
        assertEquals(BpmConstants.STABILITY_UNSTABLE, result.stabilityCategory());
    }

    @Test
    @DisplayName("stabilityCategory is null when CLS is null") // CHANGED: new
    void compute_stabilityCategory_nullWhenClsNull() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(null, null, null, null), null, null, null, 2000);
        assertNull(result.stabilityCategory());
    }

    @Test
    @DisplayName("headroom = 90% when LCP=400ms and poor threshold=4000ms") // CHANGED: new
    void compute_headroom_fastPageHighHeadroom() {
        // 100 - (400/4000 * 100) = 90%
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(500L, 400L, 0.01, 200L), null, null, null, 2000);
        assertNotNull(result.headroom());
        assertEquals(90, result.headroom());
    }

    @Test
    @DisplayName("headroom is null when LCP is null") // CHANGED: new
    void compute_headroom_nullWhenLcpNull() {
        DerivedMetrics result = calculator.compute(
                new WebVitalsResult(null, null, 0.0, null), null, null, null, 2000);
        assertNull(result.headroom());
    }

    // ── Improvement Area Detection ────────────────────────────────────────────

    @Nested
    @DisplayName("Improvement Area Detection") // CHANGED: renamed from Bottleneck Detection
    class ImprovementAreaDetection {

        @Test
        @DisplayName("Priority 1: Fix Network Failures when failedRequests > 0")
        void improvementArea_failedRequests_fixNetworkFailures() {
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(500L, 1000L, 0.01, 200L),
                    new NetworkResult(10, 100000L, 2, List.of()), null, null, 2000);
            assertEquals(BpmConstants.BOTTLENECK_RELIABILITY, result.improvementArea());
        }

        @Test
        @DisplayName("Priority 2: Reduce Server Response when TTFB/LCP > 60%")
        void improvementArea_highTtfbRatio_reduceServerResponse() {
            // TTFB=700, LCP=1000 → 70% > 60%
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(500L, 1000L, 0.01, 700L),
                    new NetworkResult(10, 100000L, 0, List.of()), null, null, 2000);
            assertEquals(BpmConstants.BOTTLENECK_SERVER, result.improvementArea());
        }

        @Test
        @DisplayName("Priority 3: Optimise Heavy Assets when slowest[0]/LCP > 40%")
        void improvementArea_slowResource_optimiseHeavyAssets() {
            // Slowest=500ms, LCP=1000ms → 50% > 40%
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(500L, 1000L, 0.01, 200L),
                    new NetworkResult(10, 300000L, 0, List.of(new ResourceEntry("/big.js", 500, 300000, 100))),
                    null, null, 2000);
            assertEquals(BpmConstants.BOTTLENECK_RESOURCE, result.improvementArea());
        }

        @Test
        @DisplayName("Priority 4: Reduce Render Work when renderTime/LCP > 60%")
        void improvementArea_highRenderTime_reduceRenderWork() {
            // TTFB=100, LCP=1000 → renderTime=900 → 90% > 60%
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(500L, 1000L, 0.01, 100L),
                    new NetworkResult(10, 100000L, 0, List.of()), null, null, 2000);
            assertEquals(BpmConstants.BOTTLENECK_CLIENT, result.improvementArea());
        }

        @Test
        @DisplayName("Priority 5: Reduce DOM Complexity when layoutCount > domNodes * 0.5")
        void improvementArea_excessiveLayouts_reduceDomComplexity() {
            // layoutCount=600, domNodes=1000 → 600 > 500
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(400L, 1000L, 0.01, 400L),
                    new NetworkResult(10, 100000L, 0, List.of()),
                    new RuntimeResult(5000000L, 1000, 600, 50), null, 2000);
            assertTrue(result.improvementAreas().contains(BpmConstants.BOTTLENECK_LAYOUT));
        }

        @Test
        @DisplayName("No condition matched — 'None' label")
        void improvementArea_noneMatched_noneLabel() {
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(200L, 1000L, 0.01, 400L),
                    new NetworkResult(10, 100000L, 0, List.of(new ResourceEntry("/small.js", 100, 5000, 50))),
                    new RuntimeResult(5000000L, 1000, 10, 5),
                    new ConsoleResult(0, 0, List.of()), 2000);
            assertEquals(BpmConstants.BOTTLENECK_NONE, result.improvementArea());
            assertTrue(result.improvementAreas().isEmpty());
        }

        @Test
        @DisplayName("Multiple areas detected — all in array, first is primary")
        void improvementArea_multiple_allRecordedFirstIsPrimary() {
            // failedRequests > 0 AND TTFB/LCP > 60%
            DerivedMetrics result = calculator.compute(
                    new WebVitalsResult(500L, 1000L, 0.01, 700L),
                    new NetworkResult(10, 100000L, 2, List.of()), null, null, 2000);
            assertEquals(BpmConstants.BOTTLENECK_RELIABILITY, result.improvementArea());
            assertTrue(result.improvementAreas().size() >= 2);
            assertTrue(result.improvementAreas().contains(BpmConstants.BOTTLENECK_SERVER));
        }
    }
}