package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.*;

/**
 * Pre-computes time-series trend analysis from BPM time buckets.
 *
 * <p>All computation happens in Java. Returns structured {@link TrendData}
 * with direction labels (RISING/FALLING/STABLE), first-half vs second-half
 * averages, percentage changes, and pre-written alert sentences.</p>
 *
 * <p>Requires at least 4 time buckets to produce meaningful analysis.
 * Returns {@code null} for insufficient data.</p>
 */
public final class TrendAnalyzer {

    /**
     * Minimum buckets needed for trend analysis.
     */
    static final int MIN_BUCKETS = 4;

    /**
     * Percentage change threshold below which a metric is considered STABLE.
     */
    private static final double STABLE_THRESHOLD_PCT = 10.0;

    private TrendAnalyzer() {
    }

    /**
     * Analyzes time-series data and produces pre-computed trend data.
     *
     * @param timeBuckets ordered time-series buckets
     * @return trend data, or {@code null} if insufficient data
     */
    public static TrendData analyze(List<BpmTimeBucket> timeBuckets) {
        if (timeBuckets == null || timeBuckets.size() < MIN_BUCKETS) {
            return null;
        }

        int mid = timeBuckets.size() / 2;
        List<BpmTimeBucket> firstHalf = timeBuckets.subList(0, mid);
        List<BpmTimeBucket> secondHalf = timeBuckets.subList(mid, timeBuckets.size());

        // Per-metric trends
        MetricTrendResult scoreTrend = computeTrend("Score",
                avgScore(firstHalf), avgScore(secondHalf), false);
        MetricTrendResult lcpTrend = computeTrend("LCP",
                avgLcp(firstHalf), avgLcp(secondHalf), true);
        MetricTrendResult fcpTrend = computeTrend("FCP",
                avgFcp(firstHalf), avgFcp(secondHalf), true);
        MetricTrendResult ttfbTrend = computeTrend("TTFB",
                avgTtfb(firstHalf), avgTtfb(secondHalf), true);

        // Pre-written alert sentences
        List<String> alerts = new ArrayList<>();
        addAlert(alerts, scoreTrend, "Score", "", false);
        addAlert(alerts, lcpTrend, "LCP", "ms", true);
        addAlert(alerts, fcpTrend, "FCP", "ms", true);
        addAlert(alerts, ttfbTrend, "TTFB", "ms", true);

        // Overall stability assessment
        int degradedCount = 0;
        if ("RISING".equals(lcpTrend.direction)) degradedCount++;
        if ("RISING".equals(fcpTrend.direction)) degradedCount++;
        if ("RISING".equals(ttfbTrend.direction)) degradedCount++;
        if ("FALLING".equals(scoreTrend.direction)) degradedCount++;

        String stability;
        if (degradedCount == 0) {
            stability = TREND_STABLE;
        } else if (degradedCount <= 1) {
            stability = TREND_MOSTLY_STABLE;
        } else {
            stability = TREND_DEGRADING;
        }

        String stabilityText = switch (stability) {
            case TREND_STABLE -> "Performance was stable throughout the test. No degradation detected.";
            case TREND_MOSTLY_STABLE -> "Performance was mostly stable with minor fluctuations in one metric.";
            case TREND_DEGRADING ->
                    degradedCount + " of 4 tracked metrics degraded during the test, suggesting performance instability under sustained load.";
            default -> "";
        };

        return new TrendData(
                timeBuckets.size(),
                toMetricTrend(scoreTrend),
                toMetricTrend(lcpTrend),
                toMetricTrend(fcpTrend),
                toMetricTrend(ttfbTrend),
                List.copyOf(alerts),
                stability,
                stabilityText
        );
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private static TrendData.MetricTrend toMetricTrend(MetricTrendResult r) {
        return new TrendData.MetricTrend(
                r.name, r.firstHalfAvg, r.secondHalfAvg,
                r.direction, r.changePct, r.degraded);
    }

    private static MetricTrendResult computeTrend(String name, double firstAvg, double secondAvg,
                                                  boolean higherIsBad) {
        if (firstAvg <= 0 && secondAvg <= 0) {
            return new MetricTrendResult(name, 0, 0, "STABLE", 0, false);
        }

        double base = firstAvg > 0 ? firstAvg : 1;
        double changePct = ((secondAvg - firstAvg) / base) * 100.0;
        int absChange = (int) Math.round(Math.abs(changePct));

        String direction;
        if (absChange < STABLE_THRESHOLD_PCT) {
            direction = "STABLE";
        } else if (secondAvg > firstAvg) {
            direction = "RISING";
        } else {
            direction = "FALLING";
        }

        boolean degraded;
        if ("STABLE".equals(direction)) {
            degraded = false;
        } else if (higherIsBad) {
            degraded = "RISING".equals(direction);
        } else {
            degraded = "FALLING".equals(direction);
        }

        return new MetricTrendResult(name, round1(firstAvg), round1(secondAvg),
                direction, absChange, degraded);
    }

    private static void addAlert(List<String> alerts, MetricTrendResult trend,
                                 String displayName, String unit, boolean higherIsBad) {
        if ("STABLE".equals(trend.direction)) return;
        if (!trend.degraded) return;

        String verb = higherIsBad ? "increased" : "dropped";
        alerts.add(String.format(Locale.US,
                "%s %s %d%% in the second half of the test (%.0f%s \u2192 %.0f%s).",
                displayName, verb, trend.changePct,
                trend.firstHalfAvg, unit, trend.secondHalfAvg, unit));
    }

    // ── Averaging helpers ───────────────────────────────────────────────────

    private static double avgScore(List<BpmTimeBucket> buckets) {
        double sum = 0;
        int count = 0;
        for (BpmTimeBucket b : buckets) {
            if (b.avgScore >= 0) {
                sum += b.avgScore;
                count++;
            }
        }
        return count > 0 ? sum / count : -1;
    }

    private static double avgMetric(List<BpmTimeBucket> buckets,
                                    java.util.function.ToDoubleFunction<BpmTimeBucket> extractor) {
        return buckets.stream().mapToDouble(extractor).average().orElse(0);
    }

    private static double avgLcp(List<BpmTimeBucket> buckets) {
        return avgMetric(buckets, b -> b.avgLcp);
    }

    private static double avgFcp(List<BpmTimeBucket> buckets) {
        return avgMetric(buckets, b -> b.avgFcp);
    }

    private static double avgTtfb(List<BpmTimeBucket> buckets) {
        return avgMetric(buckets, b -> b.avgTtfb);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record MetricTrendResult(
            String name,
            double firstHalfAvg,
            double secondHalfAvg,
            String direction,
            int changePct,
            boolean degraded
    ) {
    }
}
