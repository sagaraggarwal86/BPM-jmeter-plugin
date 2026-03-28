package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;

/**
 * Contract for a single-category metric collector.
 *
 * <p>Each implementation collects one category of browser metrics
 * (Web Vitals, Network, Runtime, Console) from the CDP session
 * and/or the shared {@link MetricsBuffer}.</p>
 *
 * <p>Collectors are stateless singletons — all mutable state lives
 * in the {@link MetricsBuffer} or is returned in the result object.
 * Exception: {@link WebVitalsCollector} tracks previous LCP for SPA
 * stale detection on a per-thread basis.</p>
 *
 * @param <T> the result type produced by this collector
 */
public interface MetricsCollector<T> {

    /**
     * Collects metrics from the browser via CDP and/or the event buffer.
     *
     * @param executor the CDP command executor for script execution and CDP commands;
     *                 never null
     * @param buffer   the shared metrics buffer containing buffered CDP events;
     *                 never null
     * @return the collected metrics result, or null if collection was skipped
     * (e.g. SPA stale LCP detection)
     * @throws RuntimeException if CDP communication fails; callers must handle
     *                          gracefully via {@code BpmErrorHandler}
     */
    T collect(CdpCommandExecutor executor, MetricsBuffer buffer);
}
