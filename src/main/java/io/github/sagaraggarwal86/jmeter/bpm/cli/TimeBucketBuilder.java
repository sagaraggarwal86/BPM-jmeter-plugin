package io.github.sagaraggarwal86.jmeter.bpm.cli;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;

import java.time.Instant;
import java.util.*;

/**
 * Builds time-series buckets from raw BPM sample data for chart rendering.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li>Auto (interval = 0): dynamically computes bucket size targeting ~50 buckets</li>
 *   <li>Fixed: uses the specified interval in seconds</li>
 * </ul>
 *
 * <p>Aggregated {@code avgScore} uses {@code -1} as a sentinel value meaning
 * "no valid scores in this bucket" (all samples were SPA-stale). Downstream
 * consumers (chart renderers, {@link io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.TrendAnalyzer})
 * must filter {@code avgScore < 0} to avoid plotting or averaging the sentinel.</p>
 */
public final class TimeBucketBuilder {

    private static final int AUTO_BUCKET_TARGET = 50;

    private TimeBucketBuilder() {
    }

    /**
     * Builds time-series buckets from raw samples (global aggregation only).
     *
     * @param samples              parsed raw samples
     * @param chartIntervalSeconds 0 for auto, or explicit interval in seconds
     * @return ordered list of time buckets
     */
    public static List<BpmTimeBucket> build(List<RawSample> samples, int chartIntervalSeconds) {
        if (samples.isEmpty()) return Collections.emptyList();
        return buildGrouped(samples, chartIntervalSeconds).globalBuckets;
    }

    /**
     * Builds time-series buckets grouped by label, plus a global "all pages" aggregation.
     *
     * @param samples              parsed raw samples (must have non-null label for per-label grouping)
     * @param chartIntervalSeconds 0 for auto, or explicit interval in seconds
     * @return grouped result with global buckets, per-label buckets, and resolved interval
     */
    public static GroupedResult buildGrouped(List<RawSample> samples, int chartIntervalSeconds) {
        if (samples.isEmpty()) {
            return new GroupedResult(Collections.emptyList(),
                    Collections.emptyMap(), 0);
        }

        // Determine time range
        long minEpoch = Long.MAX_VALUE;
        long maxEpoch = Long.MIN_VALUE;
        for (RawSample s : samples) {
            if (s.epochMs < minEpoch) minEpoch = s.epochMs;
            if (s.epochMs > maxEpoch) maxEpoch = s.epochMs;
        }

        // Resolve bucket size
        long bucketSizeMs;
        if (chartIntervalSeconds > 0) {
            bucketSizeMs = chartIntervalSeconds * 1_000L;
        } else {
            bucketSizeMs = computeAutoBucketSize(minEpoch, maxEpoch);
        }
        int intervalSeconds = (int) (bucketSizeMs / 1_000);

        // Build global buckets
        List<BpmTimeBucket> globalBuckets = buildBuckets(samples, minEpoch, bucketSizeMs);

        // Build per-label buckets
        Map<String, List<RawSample>> byLabel = new LinkedHashMap<>();
        for (RawSample s : samples) {
            String key = s.label != null ? s.label : "";
            byLabel.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        Map<String, List<BpmTimeBucket>> perLabel = new LinkedHashMap<>();
        for (Map.Entry<String, List<RawSample>> entry : byLabel.entrySet()) {
            perLabel.put(entry.getKey(), buildBuckets(entry.getValue(), minEpoch, bucketSizeMs));
        }

        return new GroupedResult(globalBuckets, perLabel, intervalSeconds);
    }

    private static List<BpmTimeBucket> buildBuckets(List<RawSample> samples,
                                                     long minEpoch, long bucketSizeMs) {
        TreeMap<Long, List<RawSample>> bucketMap = new TreeMap<>();
        for (RawSample s : samples) {
            long bucketKey = ((s.epochMs - minEpoch) / bucketSizeMs) * bucketSizeMs + minEpoch;
            bucketMap.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(s);
        }
        List<BpmTimeBucket> result = new ArrayList<>();
        for (Map.Entry<Long, List<RawSample>> entry : bucketMap.entrySet()) {
            result.add(aggregate(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static long computeAutoBucketSize(long minEpoch, long maxEpoch) {
        long durationMs = maxEpoch - minEpoch;
        if (durationMs <= 0) return 1_000L; // 1 second fallback

        long rawBucketMs = durationMs / AUTO_BUCKET_TARGET;
        // Snap to nice intervals: 1s, 5s, 10s, 15s, 30s, 1m, 2m, 5m, 10m, 15m, 30m, 1h
        long[] niceIntervals = {
                1_000, 5_000, 10_000, 15_000, 30_000,
                60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000
        };
        for (long nice : niceIntervals) {
            if (rawBucketMs <= nice) return nice;
        }
        return 3_600_000; // 1 hour max
    }

    private static BpmTimeBucket aggregate(long epochMs, List<RawSample> samples) {
        long totalLcp = 0, totalFcp = 0, totalTtfb = 0;
        long scoreSum = 0;
        int scoreCount = 0;
        double totalCls = 0;
        int clsCount = 0;
        long totalRenderTime = 0;
        int renderTimeCount = 0;

        for (RawSample s : samples) {
            totalLcp += s.lcpMs;
            totalFcp += s.fcpMs;
            totalTtfb += s.ttfbMs;
            if (s.score != null) {
                scoreSum += s.score;
                scoreCount++;
            }
            if (s.cls >= 0) {
                totalCls += s.cls;
                clsCount++;
            }
            if (s.renderTimeMs >= 0) {
                totalRenderTime += s.renderTimeMs;
                renderTimeCount++;
            }
        }

        int n = samples.size();
        double avgScore = scoreCount > 0 ? (double) scoreSum / scoreCount : -1;
        double avgLcp = (double) totalLcp / n;
        double avgFcp = (double) totalFcp / n;
        double avgTtfb = (double) totalTtfb / n;
        double avgCls = clsCount > 0 ? totalCls / clsCount : -1;
        double avgRenderTime = renderTimeCount > 0 ? (double) totalRenderTime / renderTimeCount : -1;

        return new BpmTimeBucket(epochMs, avgScore, avgLcp, avgFcp, avgTtfb,
                avgCls, avgRenderTime, n);
    }

    /**
     * A raw sample record with timestamp, label, and key metrics.
     */
    public static final class RawSample {
        public final long epochMs;
        public final String label;
        public final Integer score;  // null for SPA/stale samples
        public final long lcpMs;
        public final long fcpMs;
        public final long ttfbMs;
        public final double cls;          // -1 if no data
        public final long renderTimeMs;   // -1 if no data

        public RawSample(String isoTimestamp, Integer score, long lcpMs, long fcpMs, long ttfbMs) {
            this(isoTimestamp, null, score, lcpMs, fcpMs, ttfbMs, -1, -1);
        }

        public RawSample(String isoTimestamp, String label, Integer score,
                         long lcpMs, long fcpMs, long ttfbMs) {
            this(isoTimestamp, label, score, lcpMs, fcpMs, ttfbMs, -1, -1);
        }

        public RawSample(String isoTimestamp, String label, Integer score,
                         long lcpMs, long fcpMs, long ttfbMs,
                         double cls, long renderTimeMs) {
            this.epochMs = Instant.parse(isoTimestamp).toEpochMilli();
            this.label = label;
            this.score = score;
            this.lcpMs = lcpMs;
            this.fcpMs = fcpMs;
            this.ttfbMs = ttfbMs;
            this.cls = cls;
            this.renderTimeMs = renderTimeMs;
        }
    }

    /**
     * Result of grouped time-bucket building.
     */
    public static final class GroupedResult {
        public final List<BpmTimeBucket> globalBuckets;
        public final Map<String, List<BpmTimeBucket>> perLabelBuckets;
        public final int intervalSeconds;

        public GroupedResult(List<BpmTimeBucket> globalBuckets,
                             Map<String, List<BpmTimeBucket>> perLabelBuckets,
                             int intervalSeconds) {
            this.globalBuckets = globalBuckets;
            this.perLabelBuckets = perLabelBuckets;
            this.intervalSeconds = intervalSeconds;
        }
    }
}
