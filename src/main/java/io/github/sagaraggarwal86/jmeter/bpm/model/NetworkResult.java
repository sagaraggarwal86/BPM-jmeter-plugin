package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Network tier metrics collected from the CDP Network domain event buffer.
 *
 * <p>Corresponds to the {@code network} field in the JSONL schema (section 4.2).
 *
 * <p>The {@code slowest} list contains up to {@code network.topN} (default 5)
 * slowest successful resources, followed by ALL failed resources (4xx/5xx status
 * or connection failures), regardless of their speed rank.
 *
 * <p>The buffer is drained after each collection — counts reset per sampler action,
 * not per page load.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"totalRequests", "totalBytes", "failedRequests", "slowest"})
public record NetworkResult(

        /**
         * Total number of network requests captured in the CDP buffer since the
         * last drain (i.e., since the previous sample for this thread).
         */
        @JsonProperty("totalRequests") int totalRequests,

        /**
         * Total encoded bytes transferred across all requests captured in the
         * buffer, in bytes. Zero if no response bodies were received.
         */
        @JsonProperty("totalBytes") long totalBytes,

        /**
         * Number of failed requests (HTTP 4xx/5xx or connection failures) in
         * the buffer. Included in {@code totalRequests}.
         */
        @JsonProperty("failedRequests") int failedRequests,

        /**
         * Top-N slowest successful resources plus all failed resources.
         * N is configurable via {@code network.topN} in {@code bpm.properties}
         * (default: 5). Never {@code null}; empty list when no requests were captured.
         */
        @JsonProperty("slowest") List<ResourceEntry> slowest

) {
    /**
     * Compact constructor that defensively copies the {@code slowest} list
     * and normalises {@code null} to an empty list.
     *
     * @param totalRequests  total request count (≥ 0)
     * @param totalBytes     total bytes transferred (≥ 0)
     * @param failedRequests failed request count (≥ 0, ≤ totalRequests)
     * @param slowest        resource detail entries; {@code null} treated as empty
     */
    public NetworkResult {
        slowest = slowest != null ? List.copyOf(slowest) : List.of();
    }
}
