package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug-mode-aware logging wrapper for the BPM plugin.
 *
 * <p>Wraps SLF4J and gates all output behind a single {@code enabled} flag that is read once
 * from {@code BpmPropertiesManager} at {@code testStarted()} and remains immutable for the
 * duration of the test. This avoids per-call property lookups in the hot path (every sampler).
 *
 * <p>When debug mode is disabled (default), all methods in this class are no-ops. When enabled,
 * messages are emitted at {@code DEBUG} level so they are controlled by the JMeter logging
 * configuration independently of BPM's own {@code INFO} output.
 *
 * <p>This class is thread-safe: the {@code enabled} flag is {@code final}, and SLF4J loggers
 * are inherently thread-safe.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * // In BpmListener.testStarted():
 * BpmDebugLogger debugLogger = new BpmDebugLogger(propertiesManager.isDebugEnabled());
 *
 * // In BpmListener.sampleOccurred():
 * long t0 = System.currentTimeMillis();
 * // ... collect vitals ...
 * debugLogger.logCollection(label, vitalsMs, networkMs, runtimeMs, consoleMs);
 * debugLogger.logDerivedMetrics(label, score, bottleneck);
 * }</pre>
 */
public final class BpmDebugLogger {

    private static final Logger LOG = LoggerFactory.getLogger(BpmDebugLogger.class);

    private final boolean enabled;

    /**
     * Creates a new {@code BpmDebugLogger}.
     *
     * @param enabled {@code true} to emit debug messages; {@code false} to suppress all output.
     *                Set from {@link BpmConstants#PROP_BPM_DEBUG} via {@code BpmPropertiesManager}
     *                or the {@code -Jbpm.debug} CLI flag.
     */
    public BpmDebugLogger(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether debug logging is active for this instance.
     *
     * @return {@code true} if debug messages will be emitted
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Logs the per-tier collection timings for a single sampler execution.
     *
     * <p>Example output:
     * <pre>
     * DEBUG - BPM: Collecting metrics for 'Login Page'
     *         [webvitals=12ms, network=8ms, runtime=3ms, console=2ms, total=25ms]
     * </pre>
     *
     * @param samplerLabel display label of the sampler
     * @param vitalsMs     elapsed time for Web Vitals collection in milliseconds
     * @param networkMs    elapsed time for Network collection in milliseconds
     * @param runtimeMs    elapsed time for Runtime collection in milliseconds
     * @param consoleMs    elapsed time for Console collection in milliseconds
     */
    public void logCollection(String samplerLabel,
                              long vitalsMs,
                              long networkMs,
                              long runtimeMs,
                              long consoleMs) {
        if (!enabled) {
            return;
        }
        long total = vitalsMs + networkMs + runtimeMs + consoleMs;
        LOG.debug("BPM: Collecting metrics for '{}' [webvitals={}ms, network={}ms,"
                        + " runtime={}ms, console={}ms, total={}ms]",
                samplerLabel, vitalsMs, networkMs, runtimeMs, consoleMs, total);
    }

    /**
     * Logs a successful CDP session open event for a JMeter thread.
     *
     * <p>Example output:
     * <pre>
     * DEBUG - BPM: CDP session opened for thread 'Thread Group 1-1' in 84ms
     * </pre>
     *
     * @param threadName JMeter thread name (from {@code JMeterContext.getThread().getThreadName()})
     * @param durationMs time taken to open the session in milliseconds
     */
    public void logCdpSessionOpened(String threadName, long durationMs) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: CDP session opened for thread '{}' in {}ms", threadName, durationMs);
    }

    /**
     * Logs derived metrics computed for a sampler.
     *
     * <p>Example output:
     * <pre>
     * DEBUG - BPM: Derived metrics computed: score=82, bottleneck=Client rendering
     * </pre>
     *
     * @param samplerLabel display label of the sampler
     * @param score        computed performance score (0-100)
     * @param bottleneck   primary bottleneck label (e.g. {@link BpmConstants#BOTTLENECK_CLIENT})
     */
    public void logDerivedMetrics(String samplerLabel, Integer score, String bottleneck) { // CHANGED: per-action accuracy — nullable Integer; null when SPA-stale
        if (!enabled) {
            return;
        }
        String scoreStr = score != null ? String.valueOf(score) : "—"; // CHANGED
        LOG.debug("BPM: Derived metrics for '{}' score={}, bottleneck={}",
                samplerLabel, scoreStr, bottleneck);
    }

    /**
     * Logs a JSONL write operation.
     *
     * <p>Example output:
     * <pre>
     * DEBUG - BPM: JSONL write: 1 record, 482 bytes, flush in 1ms
     * </pre>
     *
     * @param recordCount number of records written in this batch (typically 1)
     * @param bytes       approximate serialised size of the record in bytes
     * @param flushMs     time taken for the flush operation in milliseconds (0 if no flush)
     */
    public void logJsonlWrite(int recordCount, long bytes, long flushMs) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: JSONL write: {} record(s), {} bytes, flush in {}ms",
                recordCount, bytes, flushMs);
    }

    /**
     * Logs the number of network responses drained from the {@code MetricsBuffer} for a sample.
     *
     * <p>Example output:
     * <pre>
     * DEBUG - BPM: Network buffer drained: 23 requests captured
     * </pre>
     *
     * @param requestCount number of network response entries drained
     */
    public void logNetworkBufferDrained(int requestCount) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: Network buffer drained: {} request(s) captured", requestCount);
    }

    /**
     * Logs a CDP session re-initialisation attempt after a collection error.
     *
     * @param threadName JMeter thread name
     * @param attempt    re-init attempt number (1 = first attempt)
     */
    public void logCdpReInit(String threadName, int attempt) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: CDP session re-init attempt {} for thread '{}'", attempt, threadName);
    }

    /**
     * Logs a general-purpose debug message with no structured arguments.
     *
     * <p>Use the structured overloads above whenever possible for consistent formatting.
     *
     * @param message the message to log
     */
    public void log(String message) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: {}", message);
    }

    /**
     * Logs a general-purpose debug message with one format argument.
     *
     * @param format SLF4J format string (uses {@code {}}) placeholders
     * @param arg    single argument to substitute
     */
    public void log(String format, Object arg) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: " + format, arg);
    }

    /**
     * Logs a general-purpose debug message with two format arguments.
     *
     * @param format SLF4J format string
     * @param arg1   first argument
     * @param arg2   second argument
     */
    public void log(String format, Object arg1, Object arg2) {
        if (!enabled) {
            return;
        }
        LOG.debug("BPM: " + format, arg1, arg2);
    }
}