package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 1 unit tests for SLA threshold classification (green/amber/red).
 */
@DisplayName("SLA Classification")
class SlaClassificationTest {

    private DerivedMetricsCalculator calculator;

    @BeforeEach
    void setUp() {
        BpmPropertiesManager props = new BpmPropertiesManager() {
            @Override protected Path resolvePropertiesPath() { // CHANGED: protected — cross-package override
                return Path.of(System.getProperty("java.io.tmpdir"), "bpm-sla-test.properties");
            }
            @Override public String getJMeterProperty(String key) { return null; } // CHANGED: public — must not narrow parent's public access
        };
        props.load();
        calculator = new DerivedMetricsCalculator(props);
    }

    @Test
    @DisplayName("Score ≥ 90 is Good (green), 50-89 is NeedsWork, < 50 is Poor")
    void scoreClassification_threeRanges() {
        // Good: all good vitals → score = 100
        assertEquals(100, calculator.computePerformanceScore(1000L, 1000L, 0.05, 500L, 0));
        // NeedsWork: mix of good and poor → 50-89
        int mixed = calculator.computePerformanceScore(3500L, 2500L, 0.2, 1500L, 3);
        assertTrue(mixed >= 50 && mixed < 90, "Mixed vitals should produce NeedsWork score, got: " + mixed);
        // Poor: all poor → score = 0
        assertEquals(0, calculator.computePerformanceScore(5000L, 4000L, 0.5, 2000L, 10));
    }

    @Test
    @DisplayName("LCP good ≤ 2500, needs work ≤ 4000, poor > 4000")
    void lcpThresholds_affectScore() {
        // LCP = 2500 (good boundary) → LCP score = 100
        int goodEdge = calculator.computePerformanceScore(2500L, 0L, 0.0, 0L, 0);
        // LCP = 4000 (poor boundary) → LCP score = 50
        int needsWorkEdge = calculator.computePerformanceScore(4000L, 0L, 0.0, 0L, 0);
        // LCP = 4001 → LCP score = 0
        int poor = calculator.computePerformanceScore(4001L, 0L, 0.0, 0L, 0);

        assertTrue(goodEdge > needsWorkEdge);
        assertTrue(needsWorkEdge > poor);
    }

    @Test
    @DisplayName("Error count: 0 = Good, 1-5 = NeedsWork, > 5 = Poor")
    void errorScoring_threeRanges() {
        // 0 errors → error score = 100 → contributes 15 points
        int noErrors = calculator.computePerformanceScore(0L, 0L, 0.0, 0L, 0);
        // 3 errors → error score = 50 → contributes 7.5 points
        int someErrors = calculator.computePerformanceScore(0L, 0L, 0.0, 0L, 3);
        // 10 errors → error score = 0 → contributes 0 points
        int manyErrors = calculator.computePerformanceScore(0L, 0L, 0.0, 0L, 10);

        assertTrue(noErrors > someErrors);
        assertTrue(someErrors > manyErrors);
    }

    @Test
    @DisplayName("CLS good ≤ 0.1, needs work ≤ 0.25, poor > 0.25")
    void clsThresholds_affectScore() {
        int good = calculator.computePerformanceScore(0L, 0L, 0.1, 0L, 0);
        int needsWork = calculator.computePerformanceScore(0L, 0L, 0.25, 0L, 0);
        int poor = calculator.computePerformanceScore(0L, 0L, 0.26, 0L, 0);

        assertTrue(good >= needsWork);
        assertTrue(needsWork > poor);
    }
}