package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Top-level data model representing a single BPM sample result.
 *
 * <p>One instance is produced per {@code sampleOccurred()} call that finds a Chrome
 * DevTools session. Serialises to one JSON line in the {@code .jsonl} output file.
 *
 * <p>Schema version: {@code 1.0} — matches design document section 4.2.
 *
 * <p>Tier-level fields ({@code webVitals}, {@code network}, {@code runtime},
 * {@code console}) may be {@code null} when the corresponding tier is disabled
 * in {@code bpm.properties} or when collection fails gracefully. Null fields are
 * omitted from JSON output via {@link JsonInclude.Include#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "bpmVersion", "timestamp", "threadName", "iterationNumber",
        "samplerLabel", "samplerSuccess", "samplerDuration",
        "webVitals", "network", "runtime", "console", "derived"
})
public record BpmResult(

        /**
         * Schema version. Always {@code "1.0"} for this release.
         * Included in every record to future-proof schema evolution.
         */
        @JsonProperty("bpmVersion") String bpmVersion,

        /**
         * ISO-8601 UTC timestamp of the sample, e.g.
         * {@code "2026-03-26T14:30:22.451Z"}.
         * Enables time-based correlation with JTL data.
         */
        @JsonProperty("timestamp") String timestamp,

        /**
         * JMeter thread name, e.g. {@code "Thread Group 1-1"}.
         * Enables per-virtual-user grouping during analysis.
         */
        @JsonProperty("threadName") String threadName,

        /**
         * JMeter iteration counter for this thread.
         * Enables degradation-over-time analysis across a test run.
         */
        @JsonProperty("iterationNumber") int iterationNumber,

        /**
         * Label of the WebDriver Sampler that produced this result.
         * Duplicated from the parent SampleResult to make JSONL self-contained.
         */
        @JsonProperty("samplerLabel") String samplerLabel,

        /**
         * Whether the parent WebDriver Sampler marked this sample as successful.
         * BPM collects metrics regardless of success (failed samples are often
         * the most diagnostic). Duplicated for JSONL self-containment.
         */
        @JsonProperty("samplerSuccess") boolean samplerSuccess,

        /**
         * Duration of the parent WebDriver Sampler in milliseconds.
         * Duplicated for JSONL self-containment and JAAR correlation.
         */
        @JsonProperty("samplerDuration") long samplerDuration,

        /**
         * Core Web Vitals metrics: FCP, LCP, CLS, TTFB.
         * {@code null} when Tier 1 is disabled or collection fails.
         */
        @JsonProperty("webVitals") WebVitalsResult webVitals,

        /**
         * Network tier metrics: request count, bytes, slowest resources, failures.
         * {@code null} when Tier 2 is disabled or collection fails.
         */
        @JsonProperty("network") NetworkResult network,

        /**
         * Runtime tier metrics: JS heap, DOM nodes, layout count, style recalcs.
         * {@code null} when Tier 3 is disabled or collection fails.
         */
        @JsonProperty("runtime") RuntimeResult runtime,

        /**
         * Console tier metrics: JS error and warning counts, sanitised messages.
         * {@code null} when Tier 4 is disabled or collection fails.
         */
        @JsonProperty("console") ConsoleResult console,

        /**
         * Derived metrics computed from the raw tiers at zero additional CDP cost:
         * render time, server ratio, FCP-LCP gap, failed request rate,
         * performance score, and bottleneck labels.
         * {@code null} only if all raw tiers fail.
         */
        @JsonProperty("derived") DerivedMetrics derived

) {
}
