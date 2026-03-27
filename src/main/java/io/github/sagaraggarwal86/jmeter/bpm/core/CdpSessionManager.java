package io.github.sagaraggarwal86.jmeter.bpm.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manages the CDP session lifecycle for a single JMeter thread.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Enable CDP domains (Network, Performance, Page, Log)</li>
 *   <li>Inject PerformanceObserver JavaScript for LCP and CLS via {@link JsSnippets#INJECT_OBSERVERS}</li>
 *   <li>Inject console capture hook via {@link JsSnippets#CONSOLE_CAPTURE_HOOK}</li>
 *   <li>Transfer captured events from browser-side buffers to {@link MetricsBuffer}</li>
 *   <li>Clean up on session close</li>
 * </ul>
 *
 * <p>Event capture strategy: Rather than using version-specific Selenium DevTools
 * event listener APIs, this class injects JavaScript hooks that buffer events
 * browser-side. Before each collection cycle, {@link #transferBufferedEvents(CdpCommandExecutor, MetricsBuffer)}
 * drains the browser-side buffers into the Java-side {@link MetricsBuffer}.</p>
 *
 * <p>Network events use the Resource Timing API via {@link JsSnippets#COLLECT_RESOURCE_TIMING}.
 * Console events use intercepted {@code console.error/warn} via {@link JsSnippets#DRAIN_CONSOLE_BUFFER}.</p>
 */
public final class CdpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(CdpSessionManager.class);

    /** CDP domains to enable for metric collection. */
    private static final String[] CDP_DOMAINS = {
            JsSnippets.CDP_DOMAIN_NETWORK,
            JsSnippets.CDP_DOMAIN_PERFORMANCE,
            JsSnippets.CDP_DOMAIN_PAGE,
            JsSnippets.CDP_DOMAIN_LOG
    };

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<Map<String, Object>>>> RESOURCE_TIMING_TYPE =
            new TypeReference<>() {};

    /**
     * Opens a CDP session: enables domains and injects all observer/hook scripts.
     *
     * <p>Called once per JMeter thread when a ChromeDriver is first detected
     * in {@code sampleOccurred()}.</p>
     *
     * @param executor the CDP command executor for this thread's browser
     * @throws RuntimeException if domain enabling or script injection fails
     */
    public void openSession(CdpCommandExecutor executor) {
        for (String domain : CDP_DOMAINS) {
            executor.enableDomain(domain);
        }

        // Inject PerformanceObserver for LCP + CLS (combined, buffered: true)
        executor.executeScript(JsSnippets.INJECT_OBSERVERS);

        // Inject console.error/warn interceptor
        executor.executeScript(JsSnippets.CONSOLE_CAPTURE_HOOK);

        log.debug("BPM: CDP session opened — domains enabled, observers injected.");
    }

    // CHANGED (G-05): reInjectObservers now re-enables CDP domains independently and executes
    // REINJECT_OBSERVERS (instead of delegating to openSession/INJECT_OBSERVERS).
    // This prevents CLS double-counting: REINJECT_OBSERVERS resets window.__bpm_cls = 0 before
    // registering a new PerformanceObserver, so a stale first observer cannot stack with the new
    // one and double every subsequent layout-shift contribution.
    // openSession() is unchanged — it remains correct for first-time initialisation.
    /**
     * Re-injects observers after a CDP session re-initialization.
     *
     * <p>Re-enables CDP domains and re-injects all JavaScript hooks using
     * {@link JsSnippets#REINJECT_OBSERVERS} instead of {@link JsSnippets#INJECT_OBSERVERS}.
     * The distinction is critical: {@code REINJECT_OBSERVERS} resets {@code window.__bpm_cls}
     * to {@code 0} before registering a new CLS observer, preventing double-counting when a
     * second {@code PerformanceObserver} would otherwise stack on a still-live first observer.</p>
     *
     * <p>The old session's CLS data was already captured and written to JSONL before this
     * re-init was triggered, so resetting the accumulator causes no data loss.</p>
     *
     * @param executor the CDP command executor
     */
    public void reInjectObservers(CdpCommandExecutor executor) { // CHANGED (G-05)
        // Re-enable all CDP domains (same as openSession — required after session re-init)
        for (String domain : CDP_DOMAINS) { // CHANGED (G-05)
            executor.enableDomain(domain); // CHANGED (G-05)
        } // CHANGED (G-05)

        // Use REINJECT_OBSERVERS (not INJECT_OBSERVERS) — resets window.__bpm_cls = 0
        executor.executeScript(JsSnippets.REINJECT_OBSERVERS); // CHANGED (G-05)

        // Console hook is idempotent — re-injecting resets window.__bpm_console
        executor.executeScript(JsSnippets.CONSOLE_CAPTURE_HOOK); // CHANGED (G-05)

        log.debug("BPM: Observers re-injected after CDP session re-init.");
    }

    /**
     * Transfers buffered events from browser-side JavaScript arrays into
     * the Java-side {@link MetricsBuffer}.
     *
     * <p>This method should be called at the beginning of each collection
     * cycle (in {@code sampleOccurred()}) to ensure the MetricsBuffer has
     * the latest events before collectors drain it.</p>
     *
     * @param executor the CDP command executor
     * @param buffer   the metrics buffer to populate
     */
    public void transferBufferedEvents(CdpCommandExecutor executor, MetricsBuffer buffer) {
        transferNetworkEvents(executor, buffer);
        transferConsoleEvents(executor, buffer);
    }

    /**
     * Closes the CDP session. Does not close the underlying WebDriver —
     * that is owned by the WebDriver Sampler.
     *
     * @param executor the CDP command executor to close
     */
    public void closeSession(CdpCommandExecutor executor) {
        if (executor == null) {
            return;
        }
        try {
            executor.close();
            log.debug("BPM: CDP session closed.");
        } catch (Exception e) {
            log.debug("BPM: Error closing CDP session (browser may already be closed): {}",
                    e.getMessage());
        }
    }

    /**
     * Drains Resource Timing entries from the browser into MetricsBuffer.
     *
     * <p>{@link JsSnippets#COLLECT_RESOURCE_TIMING} returns a JSON string
     * containing a {@code resources} array. Each resource entry is parsed
     * and added to the buffer as a network response.</p>
     */
    private void transferNetworkEvents(CdpCommandExecutor executor, MetricsBuffer buffer) {
        try {
            Object result = executor.executeScript(JsSnippets.COLLECT_RESOURCE_TIMING);
            if (result instanceof String jsonString && !jsonString.isEmpty()) {
                Map<String, List<Map<String, Object>>> parsed =
                        OBJECT_MAPPER.readValue(jsonString, RESOURCE_TIMING_TYPE);
                List<Map<String, Object>> resources = parsed.getOrDefault("resources", Collections.emptyList());
                for (Map<String, Object> resource : resources) {
                    buffer.addNetworkResponse(resource);
                }
            }
        } catch (JsonProcessingException e) {
            log.debug("BPM: Failed to parse resource timing JSON: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("BPM: Failed to transfer network events: {}", e.getMessage());
        }
    }

    /**
     * Drains console messages from the browser-side buffer into MetricsBuffer.
     *
     * <p>{@link JsSnippets#DRAIN_CONSOLE_BUFFER} returns a JavaScript array
     * of {@code {level, text}} objects that Selenium converts to a
     * {@code List<Map<String, Object>>}.</p>
     */
    private void transferConsoleEvents(CdpCommandExecutor executor, MetricsBuffer buffer) {
        try {
            Object result = executor.executeScript(JsSnippets.DRAIN_CONSOLE_BUFFER);
            if (result instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> map) {
                        String level = String.valueOf(map.get("level"));
                        String text = String.valueOf(map.get("text"));
                        buffer.addConsoleMessage(level, text);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("BPM: Failed to transfer console events: {}", e.getMessage());
        }
    }
}