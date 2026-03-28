package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Details of a single network resource captured by the CDP Network domain.
 *
 * <p>Used inside {@link NetworkResult#slowest()} to represent the top-N slowest
 * successful resources and ALL failed resources (4xx/5xx), regardless of speed.
 *
 * <p>All time values are in milliseconds; {@code size} is in bytes.
 *
 * @see NetworkResult
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"url", "duration", "size", "ttfb"})
public record ResourceEntry(

        /**
         * Full URL of the resource as reported by the CDP Network domain.
         * May be {@code null} if the URL was unavailable in the CDP event payload.
         */
        @JsonProperty("url") String url,

        /**
         * Total request duration in milliseconds
         * ({@code responseEnd - requestStart} from CDP timing).
         */
        @JsonProperty("duration") long duration,

        /**
         * Encoded response body size in bytes as reported by CDP.
         * Zero for failed requests where no body was received.
         */
        @JsonProperty("size") long size,

        /**
         * Time to First Byte for this resource in milliseconds
         * ({@code responseStart - requestStart} from CDP timing).
         * Zero for failed requests where no response headers were received.
         */
        @JsonProperty("ttfb") long ttfb

) {
}
