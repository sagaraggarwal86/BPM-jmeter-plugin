package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pre-computes time-series trend analysis from BPM time buckets.
 *
 * <p>All computation happens in Java — the AI receives pre-computed
 * direction labels (RISING/FALLING/STABLE), first-half vs second-half
 * averages, percentage changes, and pre-written alert sentences.
 * The AI performs zero arithmetic.</p>
 *
 * <p>Requires at least 4 time buckets to produce meaningful analysis.
 * Returns {@code null} for insufficient data.</p>
 */
public final class TrendAnalyzer {

    /**
     * Minimum buckets needed for trend analysis.
     */
    static final int MIN_BUCKETS = 4;
    private static final ObjectMapper mapper = new ObjectMapper();
    /**
     * Percentage change threshold below which a metric is considered STABLE.
     */
    private static final double STABLE_THRESHOLD_PCT = 10.0;

    private TrendAnalyzer() {
    }

    /**
     * Analyzes time-series data and produces a pre-computed JSON object
     * for the AI prompt.
     *
     * @param timeBuckets ordered time-series buckets
     * @return JSON ObjectNode with trend data, or {@code null} if insufficient data
     */
    public static ObjectNode analyze(List<BpmTimeBucket> timeBuckets) {
        if (timeBuckets == null || timeBuckets.size() < MIN_BUCKETS) {
            return null;
        }

        int mid = timeBuckets.size() / 2;
        List<BpmTimeBucket> firstHalf = timeBuckets.subList(0, mid);
        List<BpmTimeBucket> secondHalf = timeBuckets.subList(mid, timeBuckets.size());

        ObjectNode root = mapper.createObjectNode();
        root.put("bucketCount", timeBuckets.size());
        root.put("note", "All values below are pre-computed. Do NOT recalculate.");

        // Per-metric trends
        ObjectNode metrics = root.putObject("metricTrends");

        MetricTrend scoreTrend = computeTrend("Score",
                avgScore(firstHalf), avgScore(secondHalf), false);
        MetricTrend lcpTrend = computeTrend("LCP",
                avgLcp(firstHalf), avgLcp(secondHalf), true);
        MetricTrend fcpTrend = computeTrend("FCP",
                avgFcp(firstHalf), avgFcp(secondHalf), true);
        MetricTrend ttfbTrend = computeTrend("TTFB",
                avgTtfb(firstHalf), avgTtfb(secondHalf), true);

        addTrendNode(metrics, "score", scoreTrend);
        addTrendNode(metrics, "lcp", lcpTrend);
        addTrendNode(metrics, "fcp", fcpTrend);
        addTrendNode(metrics, "ttfb", ttfbTrend);

        // Pre-written alert sentences (AI copies these verbatim)
        List<String> alerts = new ArrayList<>();
        addAlert(alerts, scoreTrend, "Score", "", false);
        addAlert(alerts, lcpTrend, "LCP", "ms", true);
        addAlert(alerts, fcpTrend, "FCP", "ms", true);
        addAlert(alerts, ttfbTrend, "TTFB", "ms", true);

        ArrayNode alertsArr = root.putArray("alerts");
        alerts.forEach(alertsArr::add);

        // Overall stability assessment
        int degradedCount = 0;
        if ("RISING".equals(lcpTrend.direction)) degradedCount++;
        if ("RISING".equals(fcpTrend.direction)) degradedCount++;
        if ("RISING".equals(ttfbTrend.direction)) degradedCount++;
        if ("FALLING".equals(scoreTrend.direction)) degradedCount++;

        String stability;
        if (degradedCount == 0) {
            stability = "STABLE";
        } else if (degradedCount <= 1) {
            stability = "MOSTLY_STABLE";
        } else {
            stability = "DEGRADING";
        }
        root.put("overallStability", stability);

        String stabilityText = switch (stability) {
            case "STABLE" -> "Performance was stable throughout the test. No degradation detected.";
            case "MOSTLY_STABLE" -> "Performance was mostly stable with minor fluctuations in one metric.";
            case "DEGRADING" ->
                    degradedCount + " of 4 tracked metrics degraded during the test, suggesting performance instability under sustained load.";
            default -> "";
        };
        root.put("stabilityText", stabilityText);

        return root;
    }

    // ── Internal data ────────────────────────────────────────────────────────

    /**
     * @param higherIsBad true for latency metrics (LCP, FCP, TTFB), false for Score
     */
    private static MetricTrend computeTrend(String name, double firstAvg, double secondAvg,
                                            boolean higherIsBad) {
        if (firstAvg <= 0 && secondAvg <= 0) {
            return new MetricTrend(name, 0, 0, "STABLE", 0, false);
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
            degraded = "RISING".equals(direction);   // higher latency = bad
        } else {
            degraded = "FALLING".equals(direction);  // lower score = bad
        }

        return new MetricTrend(name, round1(firstAvg), round1(secondAvg),
                direction, absChange, degraded);
    }

    // ── Computation helpers ──────────────────────────────────────────────────

    private static void addTrendNode(ObjectNode parent, String key, MetricTrend trend) {
        ObjectNode node = parent.putObject(key);
        node.put("firstHalfAvg", trend.firstHalfAvg);
        node.put("secondHalfAvg", trend.secondHalfAvg);
        node.put("direction", trend.direction);
        node.put("changePct", trend.changePct);
        node.put("degraded", trend.degraded);
    }

    private static void addAlert(List<String> alerts, MetricTrend trend,
                                 String displayName, String unit, boolean higherIsBad) {
        if ("STABLE".equals(trend.direction)) return;
        if (!trend.degraded) return; // only alert on degradation

        String verb = higherIsBad
                ? "increased"   // latency going up = bad
                : "dropped";    // score going down = bad

        alerts.add(String.format(Locale.US,
                "%s %s %d%% in the second half of the test (%.0f%s → %.0f%s).",
                displayName, verb, trend.changePct,
                trend.firstHalfAvg, unit, trend.secondHalfAvg, unit));
    }

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

    // ── Averaging helpers ────────────────────────────────────────────────────

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

    private record MetricTrend(
            String name,
            double firstHalfAvg,
            double secondHalfAvg,
            String direction,   // RISING, FALLING, STABLE
            int changePct,      // absolute percentage change
            boolean degraded    // true if the trend is bad for performance
    ) {
    }
}
