package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

/**
 * Thread-safe running aggregate for a single sampler label.
 *
 * <p>Uses synchronized methods since updates come from multiple JMeter threads.
 * Stores running totals for weighted-average computation at summary time.</p>
 */
public class LabelAggregate {

    private int sampleCount;
    private long totalScore;
    private int scoredSampleCount;
    private long totalRenderTime;
    private double totalServerRatio;
    private long totalFcpLcpGap;
    private long totalLcp;
    private long totalFcp;
    private long totalTtfb;
    private double totalCls;
    private int totalRequests;
    private long totalBytes;
    private int totalErrors;
    private int totalWarnings;
    private String lastImprovementArea = BpmConstants.BOTTLENECK_NONE;

    private int lcpCount;
    private int fcpCount;
    private int ttfbCount;
    private int clsCount;

    private long totalFrontendTime;
    private int frontendTimeCount;
    private long totalHeadroom;
    private int headroomCount;

    /**
     * Updates the aggregate with a new sample's derived and raw metrics.
     */
    public synchronized void update(DerivedMetrics derived, WebVitalsResult vitals,
                                    NetworkResult network, ConsoleResult console) {
        sampleCount++;
        totalRenderTime += derived.renderTime();
        totalServerRatio += derived.serverClientRatio();
        totalFcpLcpGap += derived.fcpLcpGap();

        if (derived.performanceScore() != null) {
            totalScore += derived.performanceScore();
            scoredSampleCount++;
        }

        if (derived.frontendTime() != null) {
            totalFrontendTime += derived.frontendTime();
            frontendTimeCount++;
        }
        if (derived.headroom() != null) {
            totalHeadroom += derived.headroom();
            headroomCount++;
        }

        if (vitals != null) {
            if (vitals.lcp() != null) {
                totalLcp += vitals.lcp();
                lcpCount++;
            }
            if (vitals.fcp() != null) {
                totalFcp += vitals.fcp();
                fcpCount++;
            }
            if (vitals.ttfb() != null) {
                totalTtfb += vitals.ttfb();
                ttfbCount++;
            }
            if (vitals.cls() != null) {
                totalCls += vitals.cls();
                clsCount++;
            }
        }
        if (network != null) {
            totalRequests += network.totalRequests();
            totalBytes += network.totalBytes();
        }
        if (console != null) {
            totalErrors += console.errors();
            totalWarnings += console.warnings();
        }

        if (!BpmConstants.BOTTLENECK_NONE.equals(derived.improvementArea())) {
            lastImprovementArea = derived.improvementArea();
        }
    }

    /**
     * @return number of samples collected for this label
     */
    public synchronized int getSampleCount() {
        return sampleCount;
    }

    /**
     * @return weighted average performance score, or {@code null} if no sample
     * for this label had enough metric data to produce a score
     */
    public synchronized Integer getAverageScore() {
        return scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : null;
    }

    /**
     * @return average render time in ms
     */
    public synchronized long getAverageRenderTime() {
        return sampleCount > 0 ? totalRenderTime / sampleCount : 0;
    }

    /**
     * @return average server ratio as percentage
     */
    public synchronized double getAverageServerRatio() {
        return sampleCount > 0 ? totalServerRatio / sampleCount : 0.0;
    }

    /**
     * @return average FCP-LCP gap in ms
     */
    public synchronized long getAverageFcpLcpGap() {
        return sampleCount > 0 ? totalFcpLcpGap / sampleCount : 0;
    }

    /**
     * @return average frontend time in ms, over samples with FCP+TTFB data only
     */
    public synchronized Long getAverageFrontendTime() {
        return frontendTimeCount > 0 ? totalFrontendTime / frontendTimeCount : null;
    }

    /**
     * @return average headroom %, over samples with LCP data only
     */
    public synchronized Integer getAverageHeadroom() {
        return headroomCount > 0 ? (int) (totalHeadroom / headroomCount) : null;
    }

    /**
     * @return average LCP in ms, over non-null samples only
     */
    public synchronized long getAverageLcp() {
        return lcpCount > 0 ? totalLcp / lcpCount : 0;
    }

    /**
     * @return average FCP in ms, over non-null samples only
     */
    public synchronized long getAverageFcp() {
        return fcpCount > 0 ? totalFcp / fcpCount : 0;
    }

    /**
     * @return average TTFB in ms, over non-null samples only
     */
    public synchronized long getAverageTtfb() {
        return ttfbCount > 0 ? totalTtfb / ttfbCount : 0;
    }

    /**
     * @return average CLS, over non-null samples only
     */
    public synchronized double getAverageCls() {
        return clsCount > 0 ? totalCls / clsCount : 0.0;
    }

    /**
     * @return average requests per sample
     */
    public synchronized int getAverageRequests() {
        return sampleCount > 0 ? totalRequests / sampleCount : 0;
    }

    /**
     * @return average total bytes per sample
     */
    public synchronized long getAverageBytes() {
        return sampleCount > 0 ? totalBytes / sampleCount : 0;
    }

    /**
     * @return cumulative error count
     */
    public synchronized int getTotalErrors() {
        return totalErrors;
    }

    /**
     * @return cumulative warning count
     */
    public synchronized int getTotalWarnings() {
        return totalWarnings;
    }

    /**
     * @return the most recently seen non-None improvement area label
     */
    public synchronized String getPrimaryImprovementArea() {
        return lastImprovementArea;
    }
}
