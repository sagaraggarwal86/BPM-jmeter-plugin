package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Browser console metrics captured from the CDP Log domain event buffer.
 *
 * <p>Corresponds to the {@code console} field in the JSONL schema (section 4.2).
 *
 * <p>Messages in {@link #messages()} are sanitised by default to redact sensitive
 * data (Bearer tokens, JWTs, emails, AWS keys, passwords, connection strings,
 * credit card patterns). Sanitisation is controlled by the
 * {@code security.sanitize} property in {@code bpm.properties}.
 *
 * <p>The buffer is drained per sampler — counts reflect events since the previous
 * drain, not the entire page session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"errors", "warnings", "messages"})
public record ConsoleResult(

        /**
         * Number of {@code console.error()} / uncaught exception log entries
         * captured in this drain interval. Used in error-score computation
         * (15% weight in performance score) and SLA coloring.
         */
        @JsonProperty("errors") int errors,

        /**
         * Number of {@code console.warn()} log entries captured in this drain
         * interval.
         */
        @JsonProperty("warnings") int warnings,

        /**
         * Sanitised text content of captured console messages (errors and
         * warnings combined). Empty list when no messages were captured.
         * Order matches CDP event arrival order.
         */
        @JsonProperty("messages") List<String> messages

) {
    /**
     * Compact constructor that defensively copies {@code messages} and
     * normalises {@code null} to an empty list.
     *
     * @param errors   error count (≥ 0)
     * @param warnings warning count (≥ 0)
     * @param messages sanitised message strings; {@code null} treated as empty
     */
    public ConsoleResult {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
