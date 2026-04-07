package io.github.sagaraggarwal86.jmeter.bpm.report;

import java.util.List;

/**
 * Pre-computed time-series trend analysis data.
 *
 * <p>All computation happens in Java via {@link TrendAnalyzer}.
 * Contains direction labels (RISING/FALLING/STABLE), first-half vs second-half
 * averages, percentage changes, and pre-written alert sentences.</p>
 */
public record TrendData(
        int bucketCount,
        MetricTrend score,
        MetricTrend lcp,
        MetricTrend fcp,
        MetricTrend ttfb,
        List<String> alerts,
        String overallStability,   // STABLE, MOSTLY_STABLE, DEGRADING
        String stabilityText
) {

    /**
     * Per-metric trend: first-half vs second-half comparison.
     *
     * @param name          display name (Score, LCP, FCP, TTFB)
     * @param firstHalfAvg  average value in the first half of the test
     * @param secondHalfAvg average value in the second half of the test
     * @param direction     RISING, FALLING, or STABLE
     * @param changePct     absolute percentage change
     * @param degraded      true if the trend direction is bad for performance
     */
    public record MetricTrend(
            String name,
            double firstHalfAvg,
            double secondHalfAvg,
            String direction,
            int changePct,
            boolean degraded
    ) {
    }
}
