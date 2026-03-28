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
        // Extract raw values — keep nullable for score computation // CHANGED: Bug 10 — null-aware scoring
        Long fcp  = vitals != null ? vitals.fcp()  : null;
        Long lcp  = vitals != null ? vitals.lcp()  : null;
        Double cls = vitals != null ? vitals.cls() : null;
        Long ttfb = vitals != null ? vitals.ttfb() : null;

        // Primitive defaults for derived metric computation
        long lcpVal  = lcp  != null ? lcp  : 0L;
        long ttfbVal = ttfb != null ? ttfb : 0L;
        long fcpVal  = fcp  != null ? fcp  : 0L;

        int totalRequests = network != null ? network.totalRequests() : 0;
        int failedRequests = network != null ? network.failedRequests() : 0;
        List<ResourceEntry> slowest = network != null ? network.slowest() : List.of();

        int domNodes = runtime != null ? runtime.domNodes() : 0;
        int layoutCount = runtime != null ? runtime.layoutCount() : 0;

        int errorCount = console != null ? console.errors() : 0;

        // Render Time = LCP - TTFB (only meaningful when both LCP and TTFB are available)
        long renderTime = (lcp != null && ttfb != null) ? Math.max(0, lcpVal - ttfbVal) : 0L; // CHANGED: Bug 10

        // Server Ratio = (TTFB / LCP) × 100, 2 decimal places
        double serverClientRatio = (lcp != null && ttfb != null && lcpVal > 0) // CHANGED: Bug 10
                ? roundToTwoDecimals((double) ttfbVal / lcpVal * 100.0)
                : 0.0;

        // FCP-LCP Gap = LCP - FCP (only meaningful when both available)
        long fcpLcpGap = (lcp != null && fcp != null) ? Math.max(0, lcpVal - fcpVal) : 0L; // CHANGED: Bug 10

        // Failed Request Rate = (failed / total) × 100
        double failedRequestRate = totalRequests > 0
                ? roundToTwoDecimals((double) failedRequests / totalRequests * 100.0)
                : 0.0;

        // Performance Score (weighted composite, null-aware) // CHANGED: Bug 10
        int performanceScore = computePerformanceScore(lcp, fcp, cls, ttfb, errorCount);

        // Bottleneck detection (all matches + first-match-wins primary)
        List<String> bottlenecks = detectBottlenecks(
                failedRequests, ttfbVal, lcpVal, slowest, renderTime, layoutCount, domNodes);
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
     * (100 = good, 50 = needs work, 0 = poor), then combined via weighted average.
     * Null metrics (unavailable/stale) are excluded and remaining weights are
     * renormalized to ensure the score stays in [0, 100].</p>
     *
     * @return 0 if no metrics are available; otherwise the renormalized weighted score
     */
    int computePerformanceScore(Long lcp, Long fcp, Double cls, Long ttfb, int errorCount) { // CHANGED: Bug 10 — boxed types for null-awareness
        double totalWeight = 0.0;
        double weightedSum = 0.0;

        if (lcp != null) {
            weightedSum += scoreMetricLong(lcp, properties.getSlaLcpGood(), properties.getSlaLcpPoor())
                    * BpmConstants.SCORE_WEIGHT_LCP;
            totalWeight += BpmConstants.SCORE_WEIGHT_LCP;
        }
        if (fcp != null) {
            weightedSum += scoreMetricLong(fcp, properties.getSlaFcpGood(), properties.getSlaFcpPoor())
                    * BpmConstants.SCORE_WEIGHT_FCP;
            totalWeight += BpmConstants.SCORE_WEIGHT_FCP;
        }
        if (cls != null) {
            weightedSum += scoreMetricDouble(cls, properties.getSlaClsGood(), properties.getSlaClsPoor())
                    * BpmConstants.SCORE_WEIGHT_CLS;
            totalWeight += BpmConstants.SCORE_WEIGHT_CLS;
        }
        if (ttfb != null) {
            weightedSum += scoreMetricLong(ttfb, properties.getSlaTtfbGood(), properties.getSlaTtfbPoor())
                    * BpmConstants.SCORE_WEIGHT_TTFB;
            totalWeight += BpmConstants.SCORE_WEIGHT_TTFB;
        }
        // Errors always contribute — they're from console capture, not browser navigation metrics
        weightedSum += scoreErrors(errorCount,
                properties.getSlaJsErrorsGood(), properties.getSlaJsErrorsPoor())
                * BpmConstants.SCORE_WEIGHT_ERRORS;
        totalWeight += BpmConstants.SCORE_WEIGHT_ERRORS;

        // Renormalize: scale to [0, 100] based on available metric weights
        return totalWeight > 0 ? (int) Math.round(weightedSum / totalWeight) : 0;
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