package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Derived performance diagnostics computed from the four raw metric tiers
 * at zero additional CDP overhead.
 *
 * <p>Corresponds to the {@code derived} field in the JSONL schema (section 4.2).
 * Computed by {@link io.github.sagaraggarwal86.jmeter.bpm.collectors.DerivedMetricsCalculator}.
 *
 * <h2>Improvement Area detection</h2>
 * <p>Detection runs top-down; the <em>first</em> matching condition becomes the
 * primary {@link #improvementArea()} label (shown in the GUI table). All matching
 * conditions are recorded in {@link #improvementAreas()} for JAAR AI analysis.
 * When no condition matches, {@code improvementArea} is {@code "None"} and
 * {@code improvementAreas} is empty.
 *
 * <h2>Server ratio display format</h2>
 * <p>{@link #serverClientRatio()} is stored as a {@code double} rounded to two
 * decimal places at computation time (e.g., {@code 32.20}). GUI and log output
 * must append the {@code %} suffix when rendering (e.g., {@code "32.20%"}).
 *
 * <h2>Performance score ranges</h2>
 * <ul>
 *   <li>Good: ≥ 90 (green)</li>
 *   <li>Needs Work: 50–89 (amber)</li>
 *   <li>Poor: &lt; 50 (red)</li>
 *   <li>{@code null}: insufficient metric data (SPA-stale action)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "renderTime", "serverClientRatio", "frontendTime", "fcpLcpGap",
        "stabilityCategory", "headroom", "failedRequestRate",
        "improvementArea", "improvementAreas", "performanceScore"
})
public record DerivedMetrics(

        /**
         * Pure client-side rendering duration in milliseconds.
         * Formula: {@code LCP − TTFB}.
         * Isolates rendering work from server response time.
         * {@code 0} when LCP or TTFB is null (SPA-stale action).
         */
        @JsonProperty("renderTime") long renderTime,

        /**
         * Percentage of total LCP time attributable to server response.
         * Formula: {@code (TTFB ÷ LCP) × 100}, rounded to two decimal places.
         * {@code 0.0} when LCP or TTFB is null.
         */
        @JsonProperty("serverClientRatio") double serverClientRatio,

        /**
         * Frontend processing time in milliseconds — time the browser spent
         * parsing HTML and executing blocking scripts before showing any content.
         * Formula: {@code FCP − TTFB}.
         * {@code null} when FCP or TTFB is null (SPA-stale action).
         */ // CHANGED: new field
        @JsonProperty("frontendTime") Long frontendTime,

        /**
         * Gap between First Contentful Paint and Largest Contentful Paint, in ms.
         * Formula: {@code LCP − FCP}.
         * {@code 0} when LCP or FCP is null.
         */
        @JsonProperty("fcpLcpGap") long fcpLcpGap,

        /**
         * Visual stability category derived from CLS value.
         * Values: {@code "Stable"} (CLS ≤ 0.10), {@code "Minor Shifts"} (≤ 0.25),
         * {@code "Unstable"} (> 0.25).
         * {@code null} when CLS is null.
         */ // CHANGED: new field
        @JsonProperty("stabilityCategory") String stabilityCategory,

        /**
         * LCP performance budget remaining as a percentage (0–100).
         * Formula: {@code max(0, 100 − (LCP / lcpPoorThreshold × 100))}.
         * Shows how much room remains before LCP hits the Poor threshold.
         * {@code null} when LCP is null (SPA-stale action).
         */ // CHANGED: new field
        @JsonProperty("headroom") Integer headroom,

        /**
         * Percentage of network requests that failed (4xx/5xx or connection error).
         * Formula: {@code (failedRequests ÷ totalRequests) × 100}.
         * {@code 0.0} when no requests failed or network data is unavailable.
         */
        @JsonProperty("failedRequestRate") double failedRequestRate,

        /**
         * Primary Improvement Area label (first-match-wins from detection table).
         * Identifies the biggest factor consuming load time — where to focus
         * if performance needs to improve. Present even when Score is 100.
         * Possible values: {@code "Fix Network Failures"}, {@code "Reduce Server Response"},
         * {@code "Optimise Heavy Assets"}, {@code "Reduce Render Work"},
         * {@code "Reduce DOM Complexity"}, or {@code "None"}.
         */ // CHANGED: renamed from bottleneck, new value strings
        @JsonProperty("improvementArea") String improvementArea,

        /**
         * All matching Improvement Area labels in detection priority order.
         * Empty list when no condition matches ({@code improvementArea} is {@code "None"}).
         * Included in JSONL for JAAR AI correlation.
         */ // CHANGED: renamed from bottlenecks
        @JsonProperty("improvementAreas") List<String> improvementAreas,

        /**
         * Composite performance score from 0 (worst) to 100 (best).
         * Weighted average: LCP 40%, FCP 15%, CLS 15%, TTFB 15%, JS errors 15%.
         * {@code null} when insufficient metric data is available (SPA-stale action).
         */
        @JsonProperty("performanceScore") Integer performanceScore

) {
    /**
     * Compact constructor: defensively copies {@code improvementAreas} and
     * normalises {@code null} to an empty list.
     */ // CHANGED: renamed parameter
    public DerivedMetrics {
        improvementAreas = improvementAreas != null ? List.copyOf(improvementAreas) : List.of();
    }
}