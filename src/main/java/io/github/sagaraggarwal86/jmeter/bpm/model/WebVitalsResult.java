package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Core Web Vitals raw metrics collected via Chrome DevTools Protocol
 * PerformanceObserver and Navigation Timing API.
 *
 * <p>Corresponds to the {@code webVitals} field in the JSONL schema (section 4.2).
 *
 * <p><strong>SPA navigation caveat:</strong> For client-side route changes, the
 * previous LCP value lingers in the PerformanceObserver buffer. When
 * {@link io.github.sagaraggarwal86.jmeter.bpm.collectors.WebVitalsCollector}
 * detects that LCP is unchanged from the prior sample, it sets {@code lcp}
 * to {@code null} to signal "no new LCP event for this action." Callers must
 * treat a {@code null} LCP as an incomplete result.
 *
 * <p>All time values are in milliseconds. CLS is a dimensionless score.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"fcp", "lcp", "cls", "ttfb"})
public record WebVitalsResult(

        /**
         * First Contentful Paint in milliseconds.
         * Time until the browser renders the first piece of DOM content.
         * {@code null} if the value could not be read from the PerformanceObserver buffer.
         */
        @JsonProperty("fcp") Long fcp,

        /**
         * Largest Contentful Paint in milliseconds.
         * Time until the largest visible element is rendered.
         * {@code null} for SPA actions where no new LCP event occurred (stale detection),
         * or if the value could not be read.
         */
        @JsonProperty("lcp") Long lcp,

        /**
         * Cumulative Layout Shift score (dimensionless, lower is better).
         * Accumulated for the lifetime of the page; reset on hard navigation.
         * {@code null} if the value could not be read.
         */
        @JsonProperty("cls") Double cls,

        /**
         * Time to First Byte in milliseconds, sourced from Navigation Timing
         * ({@code responseStart - requestStart}).
         * {@code null} if the value could not be read.
         */
        @JsonProperty("ttfb") Long ttfb

) {
}
