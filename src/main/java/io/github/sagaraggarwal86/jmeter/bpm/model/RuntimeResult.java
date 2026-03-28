package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Runtime health metrics extracted via the CDP {@code Performance.getMetrics} command.
 *
 * <p>Corresponds to the {@code runtime} field in the JSONL schema (section 4.2).
 *
 * <p>These metrics reflect the browser engine's internal state at the moment of
 * collection (post-sampler action), not accumulated deltas. Layout and style
 * recalculation counts are cumulative from page load.
 *
 * <p>{@code heapUsed} is in bytes. All counts are raw integers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"heapUsed", "domNodes", "layoutCount", "styleRecalcCount"})
public record RuntimeResult(

        /**
         * JavaScript heap memory currently in use, in bytes.
         * Sourced from the CDP metric key {@code JSHeapUsedSize}.
         */
        @JsonProperty("heapUsed") long heapUsed,

        /**
         * Total number of active DOM nodes in the document.
         * Sourced from the CDP metric key {@code Nodes}.
         * High counts (>1500) often indicate memory leaks or excessive DOM depth.
         */
        @JsonProperty("domNodes") int domNodes,

        /**
         * Cumulative number of layout operations performed since page load.
         * Sourced from the CDP metric key {@code LayoutCount}.
         * Used in layout-thrashing bottleneck detection
         * ({@code layoutCount > domNodes × 0.5}).
         */
        @JsonProperty("layoutCount") int layoutCount,

        /**
         * Cumulative number of style recalculation operations since page load.
         * Sourced from the CDP metric key {@code RecalcStyleCount}.
         * High values correlate with excessive CSS rule complexity or dynamic
         * class manipulation.
         */
        @JsonProperty("styleRecalcCount") int styleRecalcCount

) {
}
