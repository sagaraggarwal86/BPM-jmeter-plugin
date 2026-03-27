package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants; // CHANGED: required after Gap 10 removed local weight constants
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ResourceEntry;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes derived metrics from raw collector results.
 *
 * <p>All derived metrics are pure computations from existing data —
 * zero additional CDP overhead. Thresholds are read from
 * {@link BpmPropertiesManager} (configurable via {@code bpm.properties}).</p>
 *
 * <h2>Derived metrics</h2>
 * <ul>
 *   <li><strong>Render Time</strong> = LCP − TTFB (pure client-side rendering)</li>
 *   <li><strong>Server Ratio</strong> = (TTFB ÷ LCP) × 100, 2 decimal places</li>
 *   <li><strong>FCP-LCP Gap</strong> = LCP − FCP</li>
 *   <li><strong>Failed Request Rate</strong> = (failed ÷ total) × 100</li>
 *   <li><strong>Performance Score</strong> = weighted composite (0-100)</li>
 *   <li><strong>Bottleneck</strong> = primary label (first match wins)</li>
 *   <li><strong>Bottlenecks</strong> = all matching labels</li>
 * </ul>
 */
public final class DerivedMetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(DerivedMetricsCalculator.class);

    // CHANGED (G-02): Removed six duplicate BOTTLENECK_* string constants.
    // All bottleneck labels are now sourced exclusively from BpmConstants (Decision #26 —
    // single source of truth). Usages below reference BpmConstants.BOTTLENECK_* directly.

    // Performance score weights delegated to BpmConstants (§3.3 — single source of truth) // CHANGED: removed local duplicates

    private final BpmPropertiesManager properties;

    /**
     * Creates a derived metrics calculator with the given properties manager.
     *
     * @param properties the configuration manager providing SLA and bottleneck thresholds
     */
    public DerivedMetricsCalculator(BpmPropertiesManager properties) {
        this.properties = properties;
    }

    /**
     * Computes all derived metrics from raw collector results.
     *
     * @param vitals          Web Vitals result; may be null (SPA stale detection)
     * @param network         Network result; may be null if tier disabled
     * @param runtime         Runtime result; may be null if tier disabled
     * @param console         Console result; may be null if tier disabled
     * @param samplerDuration the parent sampler duration in milliseconds
     * @return the computed derived metrics; never null
     */
    public DerivedMetrics compute(WebVitalsResult vitals, NetworkResult network,
                                  RuntimeResult runtime, ConsoleResult console,
                                  long samplerDuration) {
        // Extract raw values with null-safe defaults // CHANGED: null-safe unboxing (§3.2 — all metrics may be unavailable)
        long fcp  = vitals != null && vitals.fcp()  != null ? vitals.fcp()  : 0L;
        long lcp  = vitals != null && vitals.lcp()  != null ? vitals.lcp()  : 0L;
        double cls = vitals != null && vitals.cls() != null ? vitals.cls() : 0.0;
        long ttfb = vitals != null && vitals.ttfb() != null ? vitals.ttfb() : 0L;

        int totalRequests = network != null ? network.totalRequests() : 0;
        int failedRequests = network != null ? network.failedRequests() : 0;
        List<ResourceEntry> slowest = network != null ? network.slowest() : List.of();

        int domNodes = runtime != null ? runtime.domNodes() : 0;
        int layoutCount = runtime != null ? runtime.layoutCount() : 0;

        int errorCount = console != null ? console.errors() : 0;

        // Render Time = LCP - TTFB
        long renderTime = Math.max(0, lcp - ttfb);

        // Server Ratio = (TTFB / LCP) × 100, 2 decimal places
        double serverClientRatio = lcp > 0
                ? roundToTwoDecimals((double) ttfb / lcp * 100.0)
                : 0.0;

        // FCP-LCP Gap = LCP - FCP
        long fcpLcpGap = Math.max(0, lcp - fcp);

        // Failed Request Rate = (failed / total) × 100
        double failedRequestRate = totalRequests > 0
                ? roundToTwoDecimals((double) failedRequests / totalRequests * 100.0)
                : 0.0;

        // Performance Score (weighted composite)
        int performanceScore = computePerformanceScore(lcp, fcp, cls, ttfb, errorCount);

        // Bottleneck detection (all matches + first-match-wins primary)
        List<String> bottlenecks = detectBottlenecks(
                failedRequests, ttfb, lcp, slowest, renderTime, layoutCount, domNodes);
        String bottleneck = bottlenecks.isEmpty() ? BpmConstants.BOTTLENECK_NONE : bottlenecks.get(0); // CHANGED (G-02): was local BOTTLENECK_NONE

        return new DerivedMetrics(
                renderTime,
                serverClientRatio,
                fcpLcpGap,
                failedRequestRate,
                bottleneck,
                bottlenecks,
                performanceScore
        );
    }

    /**
     * Computes the weighted performance score (0-100) per design doc section 3.3.
     *
     * <p>Each metric is scored individually against its SLA thresholds
     * (100 = good, 50 = needs work, 0 = poor), then combined via weighted average.</p>
     */
    int computePerformanceScore(long lcp, long fcp, double cls, long ttfb, int errorCount) {
        double lcpScore = scoreMetricLong(lcp, properties.getSlaLcpGood(), properties.getSlaLcpPoor());
        double fcpScore = scoreMetricLong(fcp, properties.getSlaFcpGood(), properties.getSlaFcpPoor());
        double clsScore = scoreMetricDouble(cls, properties.getSlaClsGood(), properties.getSlaClsPoor());
        double ttfbScore = scoreMetricLong(ttfb, properties.getSlaTtfbGood(), properties.getSlaTtfbPoor());
        double errorScore = scoreErrors(errorCount,
                properties.getSlaJsErrorsGood(), properties.getSlaJsErrorsPoor()); // CHANGED: P4 — use configurable thresholds

        double weighted = lcpScore   * BpmConstants.SCORE_WEIGHT_LCP   // CHANGED: §3.3 single source of truth
                + fcpScore   * BpmConstants.SCORE_WEIGHT_FCP
                + clsScore   * BpmConstants.SCORE_WEIGHT_CLS
                + ttfbScore  * BpmConstants.SCORE_WEIGHT_TTFB
                + errorScore * BpmConstants.SCORE_WEIGHT_ERRORS;

        return (int) Math.round(weighted);
    }

    /**
     * Detects all applicable bottleneck labels per design doc section 3.4.
     * Order is priority order — first element is the primary bottleneck.
     */
    List<String> detectBottlenecks(int failedRequests, long ttfb, long lcp,
                                   List<ResourceEntry> slowest, long renderTime,
                                   int layoutCount, int domNodes) {
        List<String> detected = new ArrayList<>(6);

        // Priority 1: Reliability issue (failedRequests > 0)
        if (failedRequests > 0) {
            detected.add(BpmConstants.BOTTLENECK_RELIABILITY); // CHANGED (G-02): was local BOTTLENECK_RELIABILITY
        }

        // Priority 2: Server bottleneck (TTFB / LCP > threshold %)
        if (lcp > 0) {
            double serverRatio = (double) ttfb / lcp * 100.0;
            if (serverRatio > properties.getBottleneckServerRatio()) {
                detected.add(BpmConstants.BOTTLENECK_SERVER); // CHANGED (G-02): was local BOTTLENECK_SERVER
            }
        }

        // Priority 3: Resource bottleneck (slowest[0].duration / LCP > threshold %)
        if (lcp > 0 && !slowest.isEmpty()) {
            double resourceRatio = (double) slowest.get(0).duration() / lcp * 100.0;
            if (resourceRatio > properties.getBottleneckResourceRatio()) {
                detected.add(BpmConstants.BOTTLENECK_RESOURCE); // CHANGED (G-02): was local BOTTLENECK_RESOURCE
            }
        }

        // Priority 4: Client rendering (renderTime / LCP > threshold %)
        if (lcp > 0) {
            double clientRatio = (double) renderTime / lcp * 100.0;
            if (clientRatio > properties.getBottleneckClientRatio()) {
                detected.add(BpmConstants.BOTTLENECK_CLIENT); // CHANGED (G-02): was local BOTTLENECK_CLIENT
            }
        }

        // Priority 5: Layout thrashing (layoutCount > domNodes * factor)
        if (domNodes > 0) {
            double threshold = domNodes * properties.getBottleneckLayoutThrashFactor();
            if (layoutCount > threshold) {
                detected.add(BpmConstants.BOTTLENECK_LAYOUT); // CHANGED (G-02): was local BOTTLENECK_LAYOUT
            }
        }

        return detected;
    }

    /**
     * Scores a long metric: 100 if ≤ good, 50 if ≤ poor, 0 if > poor.
     */
    private static double scoreMetricLong(long value, long good, long poor) {
        if (value <= good) {
            return 100.0;
        }
        if (value <= poor) {
            return 50.0;
        }
        return 0.0;
    }

    /**
     * Scores a double metric: 100 if ≤ good, 50 if ≤ poor, 0 if > poor.
     */
    private static double scoreMetricDouble(double value, double good, double poor) {
        if (value <= good) {
            return 100.0;
        }
        if (value <= poor) {
            return 50.0;
        }
        return 0.0;
    }

    /**
     * Scores JS error count against configurable SLA thresholds.
     * 100 if count ≤ good threshold, 50 if ≤ poor threshold, 0 if above poor.
     */
    private static double scoreErrors(int errorCount, int good, int poor) {
        if (errorCount <= good) {
            return 100.0;
        }
        if (errorCount <= poor) {
            return 50.0;
        }
        return 0.0;
    }

    /**
     * Rounds a double to 2 decimal places using HALF_UP rounding.
     */
    private static double roundToTwoDecimals(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}