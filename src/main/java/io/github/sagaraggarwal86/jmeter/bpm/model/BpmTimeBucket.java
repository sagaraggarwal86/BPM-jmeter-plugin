package io.github.sagaraggarwal86.jmeter.bpm.model;

/**
 * Aggregated performance metrics for a single time interval (bucket).
 * Used for time-series charts in the AI HTML report.
 */
public final class BpmTimeBucket {

    /**
     * Epoch millis representing the start of this bucket.
     */
    public final long epochMs;

    /**
     * Average performance score in this bucket (0-100, or -1 if no scores).
     */
    public final double avgScore;

    /**
     * Average LCP in milliseconds.
     */
    public final double avgLcp;

    /**
     * Average FCP in milliseconds.
     */
    public final double avgFcp;

    /**
     * Average TTFB in milliseconds.
     */
    public final double avgTtfb;

    /**
     * Average CLS (dimensionless, or -1 if no data).
     */
    public final double avgCls;

    /**
     * Average Render Time in milliseconds (LCP − TTFB, or -1 if no data).
     */
    public final double avgRenderTime;

    /**
     * Number of samples in this bucket.
     */
    public final int sampleCount;

    /**
     * Backward-compatible constructor (CLS and Render Time default to -1).
     */
    public BpmTimeBucket(long epochMs, double avgScore, double avgLcp,
                         double avgFcp, double avgTtfb, int sampleCount) {
        this(epochMs, avgScore, avgLcp, avgFcp, avgTtfb, -1, -1, sampleCount);
    }

    public BpmTimeBucket(long epochMs, double avgScore, double avgLcp,
                         double avgFcp, double avgTtfb, double avgCls,
                         double avgRenderTime, int sampleCount) {
        this.epochMs = epochMs;
        this.avgScore = avgScore;
        this.avgLcp = avgLcp;
        this.avgFcp = avgFcp;
        this.avgTtfb = avgTtfb;
        this.avgCls = avgCls;
        this.avgRenderTime = avgRenderTime;
        this.sampleCount = sampleCount;
    }
}
