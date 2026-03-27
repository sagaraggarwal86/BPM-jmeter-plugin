package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ResourceEntry;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants; // CHANGED: import for BOTTLENECK_* after G-02 moved constants here
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for DerivedMetricsCalculator: performance score,
 * all 6 bottleneck labels, edge cases.
 */
@DisplayName("DerivedMetricsCalculator")
class DerivedMetricsCalculatorTest {

    private DerivedMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        // Use defaults (auto-generated bpm.properties)
        BpmPropertiesManager props = new BpmPropertiesManager() {
            @Override
            protected Path resolvePropertiesPath() { // CHANGED: protected — cross-package override
                return Path.of(System.getProperty("java.io.tmpdir"), "bpm-test-calc.properties");
            }

            @Override
            public String getJMeterProperty(String key) { // CHANGED: public — must not narrow parent's public access
                return null;
            }
        };
        props.load();
        calculator = new DerivedMetricsCalculator(props);
    }

    @Test
    @DisplayName("Perfect vitals produce score 100")
    void compute_perfectVitals_score100() {
        WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 200L); // CHANGED: L suffix — WebVitalsResult fields are Long, not long
        NetworkResult network = new NetworkResult(10, 100000L, 0, List.of());
        RuntimeResult runtime = new RuntimeResult(5000000L, 500, 5, 3);
        ConsoleResult console = new ConsoleResult(0, 0, List.of());

        DerivedMetrics result = calculator.compute(vitals, network, runtime, console, 2000);

        assertEquals(100, result.performanceScore());
    }

    @Test
    @DisplayName("Poor vitals produce score 0")
    void compute_poorVitals_score0() {
        WebVitalsResult vitals = new WebVitalsResult(5000L, 6000L, 0.5, 3000L); // CHANGED: L suffix
        NetworkResult network = new NetworkResult(10, 100000L, 0, List.of());
        RuntimeResult runtime = new RuntimeResult(5000000L, 500, 5, 3);
        ConsoleResult console = new ConsoleResult(10, 0, List.of());

        DerivedMetrics result = calculator.compute(vitals, network, runtime, console, 2000);

        assertEquals(0, result.performanceScore());
    }

    @Test
    @DisplayName("renderTime = LCP - TTFB")
    void compute_renderTime_isLcpMinusTtfb() {
        WebVitalsResult vitals = new WebVitalsResult(500L, 1180L, 0.01, 380L); // CHANGED: L suffix
        DerivedMetrics result = calculator.compute(vitals, null, null, null, 2000);

        assertEquals(800, result.renderTime());
    }

    @Test
    @DisplayName("serverClientRatio has 2 decimal precision")
    void compute_serverRatio_twoDecimalPrecision() {
        WebVitalsResult vitals = new WebVitalsResult(500L, 1180L, 0.01, 380L); // CHANGED: L suffix
        DerivedMetrics result = calculator.compute(vitals, null, null, null, 2000);

        assertEquals(32.20, result.serverClientRatio(), 0.01);
    }

    @Test
    @DisplayName("fcpLcpGap = LCP - FCP")
    void compute_fcpLcpGap_correct() {
        WebVitalsResult vitals = new WebVitalsResult(420L, 1180L, 0.01, 380L); // CHANGED: L suffix
        DerivedMetrics result = calculator.compute(vitals, null, null, null, 2000);

        assertEquals(760, result.fcpLcpGap());
    }

    @Test
    @DisplayName("failedRequestRate computed correctly")
    void compute_failedRequestRate_correct() {
        NetworkResult network = new NetworkResult(20, 100000L, 5, List.of());
        DerivedMetrics result = calculator.compute(null, network, null, null, 2000);

        assertEquals(25.0, result.failedRequestRate(), 0.01);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Bottleneck Labels (one test per label)
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bottleneck Detection")
    class BottleneckDetection {

        @Test
        @DisplayName("Priority 1: Reliability issue when failedRequests > 0")
        void bottleneck_failedRequests_reliabilityIssue() {
            WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 200L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 2, List.of());

            DerivedMetrics result = calculator.compute(vitals, network, null, null, 2000);

            assertEquals(BpmConstants.BOTTLENECK_RELIABILITY, result.bottleneck()); // CHANGED: DerivedMetricsCalculator.BOTTLENECK_* → BpmConstants.BOTTLENECK_* (G-02)
        }

        @Test
        @DisplayName("Priority 2: Server bottleneck when TTFB/LCP > 60%")
        void bottleneck_highTtfbRatio_serverBottleneck() {
            // TTFB = 700, LCP = 1000 → 70% > 60% threshold
            WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 700L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 0, List.of());

            DerivedMetrics result = calculator.compute(vitals, network, null, null, 2000);

            assertEquals(BpmConstants.BOTTLENECK_SERVER, result.bottleneck()); // CHANGED: BpmConstants
        }

        @Test
        @DisplayName("Priority 3: Resource bottleneck when slowest[0]/LCP > 40%")
        void bottleneck_slowResource_resourceBottleneck() {
            // Slowest = 500ms, LCP = 1000ms → 50% > 40% threshold
            WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 200L); // CHANGED: L suffix
            ResourceEntry slow = new ResourceEntry("/big.js", 500, 300000, 100);
            NetworkResult network = new NetworkResult(10, 300000L, 0, List.of(slow));

            DerivedMetrics result = calculator.compute(vitals, network, null, null, 2000);

            assertEquals(BpmConstants.BOTTLENECK_RESOURCE, result.bottleneck()); // CHANGED: BpmConstants
        }

        @Test
        @DisplayName("Priority 4: Client rendering when renderTime/LCP > 60%")
        void bottleneck_highRenderTime_clientRendering() {
            // TTFB = 100, LCP = 1000 → renderTime = 900 → 90% > 60%
            // But TTFB/LCP = 10% (not server bottleneck)
            WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 100L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 0, List.of());

            DerivedMetrics result = calculator.compute(vitals, network, null, null, 2000);

            assertEquals(BpmConstants.BOTTLENECK_CLIENT, result.bottleneck()); // CHANGED: BpmConstants
        }

        @Test
        @DisplayName("Priority 5: Layout thrashing when layoutCount > domNodes * 0.5")
        void bottleneck_excessiveLayouts_layoutThrashing() {
            // layoutCount = 600, domNodes = 1000 → 600 > 1000*0.5=500
            // TTFB/LCP must be low, renderTime/LCP must be low to avoid earlier priorities
            WebVitalsResult vitals = new WebVitalsResult(400L, 1000L, 0.01, 400L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 0, List.of());
            RuntimeResult runtime = new RuntimeResult(5000000L, 1000, 600, 50);

            DerivedMetrics result = calculator.compute(vitals, network, runtime, null, 2000);

            assertTrue(result.bottlenecks().contains(BpmConstants.BOTTLENECK_LAYOUT)); // CHANGED: BpmConstants
        }

        @Test
        @DisplayName("Priority 6: No bottleneck matched — dash label")
        void bottleneck_noneMatched_dashLabel() {
            // All ratios below thresholds, no failures
            WebVitalsResult vitals = new WebVitalsResult(200L, 1000L, 0.01, 400L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 0,
                    List.of(new ResourceEntry("/small.js", 100, 5000, 50)));
            RuntimeResult runtime = new RuntimeResult(5000000L, 1000, 10, 5);
            ConsoleResult console = new ConsoleResult(0, 0, List.of());

            DerivedMetrics result = calculator.compute(vitals, network, runtime, console, 2000);

            assertEquals(BpmConstants.BOTTLENECK_NONE, result.bottleneck()); // CHANGED: BpmConstants
            assertTrue(result.bottlenecks().isEmpty());
        }

        @Test
        @DisplayName("Multiple bottlenecks detected — all recorded in array, first is primary")
        void bottleneck_multiple_allRecordedFirstIsPrimary() {
            // failedRequests > 0 AND TTFB/LCP > 60%
            WebVitalsResult vitals = new WebVitalsResult(500L, 1000L, 0.01, 700L); // CHANGED: L suffix
            NetworkResult network = new NetworkResult(10, 100000L, 2, List.of());

            DerivedMetrics result = calculator.compute(vitals, network, null, null, 2000);

            assertEquals(BpmConstants.BOTTLENECK_RELIABILITY, result.bottleneck()); // CHANGED: BpmConstants
            assertTrue(result.bottlenecks().size() >= 2);
            assertTrue(result.bottlenecks().contains(BpmConstants.BOTTLENECK_SERVER)); // CHANGED: BpmConstants
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("LCP=0 produces zero ratios without division error")
    void compute_lcpZero_noDivisionError() {
        WebVitalsResult vitals = new WebVitalsResult(0L, 0L, 0.0, 0L); // CHANGED: L suffix
        DerivedMetrics result = calculator.compute(vitals, null, null, null, 2000);

        assertEquals(0.0, result.serverClientRatio());
        assertEquals(0, result.renderTime());
    }

    @Test
    @DisplayName("All null sub-results produce safe defaults")
    void compute_allNull_safeDefaults() {
        DerivedMetrics result = calculator.compute(null, null, null, null, 1000);

        assertNotNull(result);
        assertEquals(0, result.renderTime());
        assertEquals(0.0, result.serverClientRatio());
        assertEquals(BpmConstants.BOTTLENECK_NONE, result.bottleneck()); // CHANGED: BpmConstants
    }

    @Test
    @DisplayName("No network data produces 0% failed request rate")
    void compute_noNetworkData_zeroFailedRate() {
        DerivedMetrics result = calculator.compute(null, null, null, null, 1000);
        assertEquals(0.0, result.failedRequestRate());
    }
}