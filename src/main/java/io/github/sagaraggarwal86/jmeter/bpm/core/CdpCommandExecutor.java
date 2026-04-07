package io.github.sagaraggarwal86.jmeter.bpm.core;

import java.util.Map;

/**
 * Abstraction over Chrome DevTools Protocol (CDP) operations.
 *
 * <p>Isolates Selenium dependency from collectors and the listener,
 * enabling unit testing with mocked implementations. The sole production
 * implementation is {@link ChromeCdpCommandExecutor}.</p>
 *
 * <p>All methods may throw {@link RuntimeException} if the CDP session
 * is disconnected or the browser has closed unexpectedly. Callers must
 * handle exceptions gracefully.</p>
 */
public interface CdpCommandExecutor {

    /**
     * Executes a JavaScript snippet in the browser context and returns the result.
     *
     * <p>Used by collectors to read PerformanceObserver buffers and Navigation Timing data.
     * The script is executed synchronously via {@code Runtime.evaluate} or equivalent.</p>
     *
     * @param script the JavaScript code to execute
     * @return the script result; may be null, a primitive wrapper, a String,
     * or a {@link Map} depending on the script's return value
     * @throws RuntimeException if script execution fails or the CDP session is invalid
     */
    Object executeScript(String script);

    /**
     * Executes a raw CDP command and returns the result map.
     *
     * <p>Used for commands like {@code Performance.getMetrics} that have no
     * JavaScript equivalent.</p>
     *
     * @param method the CDP method name (e.g. "Performance.getMetrics")
     * @param params the command parameters; may be empty but not null
     * @return the result map from the CDP response
     * @throws RuntimeException if the command fails or the CDP session is invalid
     */
    Map<String, Object> executeCdpCommand(String method, Map<String, Object> params);

    /**
     * Enables a CDP domain to start receiving events from it.
     *
     * <p>Domains must be enabled before their events can be captured.
     * Common domains: Network, Performance, Page, Log.</p>
     *
     * @param domain the CDP domain name (e.g. "Network", "Performance", "Page", "Log")
     * @throws RuntimeException if domain enabling fails
     */
    void enableDomain(String domain);

    /**
     * Closes the CDP session and releases associated resources.
     *
     * <p>After this call, all other methods on this executor will throw.
     * Safe to call multiple times.</p>
     */
    void close();
}
