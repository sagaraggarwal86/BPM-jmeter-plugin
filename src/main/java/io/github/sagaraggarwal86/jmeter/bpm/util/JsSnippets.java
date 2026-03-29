package io.github.sagaraggarwal86.jmeter.bpm.util;

/**
 * Compile-time JavaScript string constants used by BPM for CDP-based metric collection.
 *
 * <p>All constants in this class are JavaScript source strings passed to
 * {@code CdpCommandExecutor.executeScript()} or used as CDP method names in
 * {@code CdpCommandExecutor.executeCdpCommand()}. Keeping all JavaScript in one place makes
 * cross-platform testing and future maintenance straightforward.
 *
 * <p>This class is not instantiable; all members are static.
 *
 * <h2>Injection lifecycle</h2>
 * <ol>
 *   <li>{@link #INJECT_OBSERVERS} is executed once when the CDP session is opened to register
 *       persistent {@code PerformanceObserver} callbacks for LCP and CLS.</li>
 *   <li>{@link #REINJECT_OBSERVERS} is executed on CDP session re-initialisation (error recovery)
 *       to re-register observers without double-counting CLS from the previous session.</li>
 *   <li>{@link #COLLECT_WEB_VITALS} is executed once per sampler to read the accumulated values
 *       written by those observers, together with FCP and TTFB from Navigation Timing.</li>
 *   <li>{@link #CDP_METHOD_PERFORMANCE_GET_METRICS} is the CDP domain method name passed to
 *       {@code executeCdpCommand} by {@code RuntimeCollector} to retrieve Chrome runtime metrics
 *       (heap, DOM nodes, layout count, style-recalc count).</li>
 *   <li>{@link #COLLECT_RESOURCE_TIMING} is a supplementary script that drains the browser's
 *       Resource Timing buffer and clears it, used as a fallback or for cross-validation when
 *       the CDP Network domain is unavailable.</li>
 * </ol>
 */
public final class JsSnippets {

    private JsSnippets() {
        throw new UnsupportedOperationException("JsSnippets is a utility class");
    }

    // ── Observer injection ────────────────────────────────────────────────────────────────────

    /**
     * JavaScript executed once at CDP session initialisation. Registers two
     * {@code PerformanceObserver} instances that accumulate LCP and CLS values into
     * {@code window.__bpm_lcp} and {@code window.__bpm_cls} respectively.
     *
     * <p>Both observers use {@code buffered: true} so entries already recorded before injection
     * are immediately delivered to the callback. This is safe to call on page that has already
     * started rendering.
     *
     * <p>The script is idempotent: re-injecting after a CDP session re-init overwrites the
     * previous window state but does not stack duplicate observers because the session re-init
     * happens after a navigation or browser restart.
     *
     * <p><strong>Do not use this script on the CDP re-injection path.</strong>
     * Use {@link #REINJECT_OBSERVERS} instead, which resets {@code window.__bpm_cls} to zero
     * before registering a new observer, preventing CLS double-counting when a second
     * {@code PerformanceObserver} is stacked on top of a still-live first one.
     */
    public static final String INJECT_OBSERVERS = """
            (function() {
              // LCP: store the startTime of the last (most recent) largest-contentful-paint entry.
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                if (entries.length > 0) {
                  window.__bpm_lcp = entries[entries.length - 1].startTime;
                }
              }).observe({type: 'largest-contentful-paint', buffered: true});

              // CLS: accumulate layout-shift values, ignoring shifts caused by user input.
              window.__bpm_cls = window.__bpm_cls || 0;
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                for (var i = 0; i < entries.length; i++) {
                  if (!entries[i].hadRecentInput) {
                    window.__bpm_cls = (window.__bpm_cls || 0) + entries[i].value;
                  }
                }
              }).observe({type: 'layout-shift', buffered: true});

              // CHANGED: Bug 11 — Synchronous seed from performance buffer.
              // Observer callbacks are queued as tasks (async), so if collectMetrics()
              // runs immediately after injection, window.__bpm_lcp would still be 0.
              // Reading the buffer directly ensures values are available instantly.
              var lcpEntries = performance.getEntriesByType('largest-contentful-paint');
              if (lcpEntries.length > 0) {
                window.__bpm_lcp = lcpEntries[lcpEntries.length - 1].startTime;
              }
              var clsEntries = performance.getEntriesByType('layout-shift');
              var clsSum = 0;
              for (var i = 0; i < clsEntries.length; i++) {
                if (!clsEntries[i].hadRecentInput) clsSum += clsEntries[i].value;
              }
              if (clsSum > 0) window.__bpm_cls = clsSum;
            })();
            """;

    // CHANGED (G-05): New constant — safe re-injection script for CDP session recovery.
    /**
     * JavaScript executed when a CDP session is re-initialised mid-test (error recovery path).
     *
     * <p>This script is functionally identical to {@link #INJECT_OBSERVERS} except that it
     * <strong>resets {@code window.__bpm_cls} to {@code 0}</strong> before registering a new
     * CLS observer. This is correct and necessary because:
     * <ol>
     *   <li>The previous session's CLS data was already captured and written to JSONL before
     *       the re-init was triggered — discarding the accumulated value causes no data loss.</li>
     *   <li>Without the reset, {@code window.__bpm_cls || 0} preserves the old value and a
     *       second {@code PerformanceObserver} for {@code layout-shift} is stacked on top of
     *       the still-live first observer, causing every subsequent layout shift to be counted
     *       twice (double-counting bug).</li>
     * </ol>
     *
     * <p>The LCP observer re-registration is safe without reset because {@code window.__bpm_lcp}
     * is a scalar that the new observer overwrites on the next LCP event — no double-counting
     * is possible for LCP.
     *
     * <p>Called exclusively from {@code CdpSessionManager.reInjectObservers()}.
     * {@link #INJECT_OBSERVERS} remains correct for first-time session initialisation.
     */
    public static final String REINJECT_OBSERVERS = """
            (function() {
              // LCP: re-register observer — safe on re-inject (scalar overwrite on next event).
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                if (entries.length > 0) {
                  window.__bpm_lcp = entries[entries.length - 1].startTime;
                }
              }).observe({type: 'largest-contentful-paint', buffered: true});

              // CLS: reset to 0 before registering a new observer to prevent double-counting.
              // The old session's CLS was already flushed to JSONL before re-init was triggered.
              window.__bpm_cls = 0;
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                for (var i = 0; i < entries.length; i++) {
                  if (!entries[i].hadRecentInput) {
                    window.__bpm_cls = (window.__bpm_cls || 0) + entries[i].value;
                  }
                }
              }).observe({type: 'layout-shift', buffered: true});

              // CHANGED: Bug 11 — Synchronous seed (same as INJECT_OBSERVERS).
              var lcpEntries = performance.getEntriesByType('largest-contentful-paint');
              if (lcpEntries.length > 0) {
                window.__bpm_lcp = lcpEntries[lcpEntries.length - 1].startTime;
              }
            })();
            """;

    // ── Web Vitals collection ─────────────────────────────────────────────────────────────────

    /**
     * JavaScript executed once per sampler to read all four Web Vitals in a single round-trip.
     * Returns a plain JavaScript object that Selenium converts to a {@code Map<String, Object>}
     * with the following keys and value types:
     *
     * <table border="1">
     *   <caption>Returned map keys</caption>
     *   <tr><th>Key</th><th>Java type after Selenium conversion</th><th>Units</th></tr>
     *   <tr><td>{@code lcp}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     *   <tr><td>{@code cls}</td><td>{@code Number} (double)</td><td>unitless score</td></tr>
     *   <tr><td>{@code fcp}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     *   <tr><td>{@code ttfb}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     * </table>
     *
     * <p>All values default to {@code 0} when the underlying API entry is absent, ensuring
     * the caller never receives {@code null} values for numeric fields.
     *
     * <p>FCP is sourced from the {@code first-contentful-paint} Paint Timing entry.
     * TTFB is sourced from the Navigation Timing L2 API ({@code responseStart}).
     * LCP and CLS are sourced from the window globals set by {@link #INJECT_OBSERVERS}.
     */
    public static final String COLLECT_WEB_VITALS = """
            return (function() {
              var nav = (performance.getEntriesByType('navigation') || [])[0] || {};
              var fcpEntries = performance.getEntriesByName('first-contentful-paint') || [];
              var fcpEntry = fcpEntries.length > 0 ? fcpEntries[0] : null;
              return {
                lcp:  window.__bpm_lcp || 0,
                cls:  window.__bpm_cls || 0,
                fcp:  fcpEntry ? fcpEntry.startTime : 0,
                ttfb: nav.responseStart || 0
              };
            })();
            """;

    // ── CDP command names ─────────────────────────────────────────────────────────────────────

    /**
     * CDP domain method name passed to {@code CdpCommandExecutor.executeCdpCommand()} by
     * {@code RuntimeCollector} to retrieve Chrome runtime performance metrics.
     *
     * <p>The CDP response contains a {@code metrics} array of {@code {name, value}} objects.
     * Keys of interest: {@code JSHeapUsedSize}, {@code Nodes}, {@code LayoutCount},
     * {@code RecalcStyleCount}.
     *
     * @see <a href="https://chromedevtools.github.io/devtools-protocol/tot/Performance/#method-getMetrics">
     *      CDP Performance.getMetrics</a>
     */
    public static final String CDP_METHOD_PERFORMANCE_GET_METRICS = "Performance.getMetrics";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate the
     * Performance domain before calling {@link #CDP_METHOD_PERFORMANCE_GET_METRICS}.
     */
    public static final String CDP_DOMAIN_PERFORMANCE = "Performance";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Network
     * event capture ({@code Network.responseReceived}, {@code Network.loadingFailed}, etc.).
     */
    public static final String CDP_DOMAIN_NETWORK = "Network";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Page-level
     * events (navigations, load events) used for session lifecycle management.
     */
    public static final String CDP_DOMAIN_PAGE = "Page";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Log
     * domain events, which surface browser console messages as CDP events.
     */
    public static final String CDP_DOMAIN_LOG = "Log";

    // ── Resource Timing (supplementary) ──────────────────────────────────────────────────────

    /**
     * JavaScript that reads all {@code resource} performance entries accumulated since the last
     * call (or page load), then clears the browser's performance buffer to avoid double-counting
     * on the next sample.
     *
     * <p>Returns a JSON string — not a JS object — because the resource entry array may be large
     * and is safer to transmit as a pre-serialised string that the collector parses via Jackson.
     * Each element in the {@code resources} array is an object with:
     * <ul>
     *   <li>{@code url} — the resource URL (trimmed to 512 characters to limit payload size)</li>
     *   <li>{@code duration} — total round-trip time in milliseconds</li>
     *   <li>{@code transferSize} — bytes transferred (0 for cached resources)</li>
     *   <li>{@code ttfb} — time to first byte ({@code responseStart}) in milliseconds</li>
     *   <li>{@code status} — HTTP status code if available, otherwise 0</li>
     * </ul>
     *
     * <p>This script is used as a fallback when CDP Network domain events are unavailable and
     * for cross-validation in end-to-end tests.
     */
    public static final String COLLECT_RESOURCE_TIMING = """
            return (function() {
              var entries = performance.getEntriesByType('resource') || [];
              var resources = [];
              for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                resources.push({
                  url:          (e.name || '').substring(0, 512),
                  duration:     e.duration || 0,
                  transferSize: e.transferSize || 0,
                  ttfb:         e.responseStart || 0,
                  status:       e.responseStatus || 0
                });
              }
              performance.clearResourceTimings();
              return JSON.stringify({resources: resources});
            })();
            """;

    // ── Console event capture ────────────────────────────────────────────────────────────────  // CHANGED — new constant

    /**
     * JavaScript executed once at CDP session initialisation to intercept
     * {@code console.error()} and {@code console.warn()} calls.
     *
     * <p>Captured messages are stored in {@code window.__bpm_console}, a plain
     * array of {@code {level, text}} objects. The array is drained and cleared
     * by {@link #DRAIN_CONSOLE_BUFFER} during each collection cycle.</p>
     *
     * <p>Only {@code error} and {@code warn} levels are intercepted — other
     * levels ({@code log}, {@code info}, {@code debug}) are ignored to keep
     * the buffer small and focused on actionable diagnostics.</p>
     *
     * <p>The script is idempotent: re-injecting overwrites the hooks and
     * resets the buffer.</p>
     */
    public static final String CONSOLE_CAPTURE_HOOK = """
            (function() {
              window.__bpm_console = [];
              var origError = console.error;
              var origWarn  = console.warn;
              console.error = function() {
                try {
                  var msg = Array.prototype.slice.call(arguments).join(' ');
                  window.__bpm_console.push({level: 'error', text: msg.substring(0, 2048)});
                } catch(e) {}
                return origError.apply(console, arguments);
              };
              console.warn = function() {
                try {
                  var msg = Array.prototype.slice.call(arguments).join(' ');
                  window.__bpm_console.push({level: 'warning', text: msg.substring(0, 2048)});
                } catch(e) {}
                return origWarn.apply(console, arguments);
              };
            })();
            """;

    /**                                                                                         // CHANGED — new constant
     * JavaScript that drains the browser-side console message buffer and clears it.
     *
     * <p>Returns a JavaScript array of {@code {level, text}} objects. Selenium converts
     * this to a {@code List<Map<String, Object>>} on the Java side.</p>
     *
     * <p>After draining, {@code window.__bpm_console} is reset to an empty array
     * to prevent double-counting on the next collection cycle.</p>
     */
    public static final String DRAIN_CONSOLE_BUFFER = """
            return (function() {
              var buf = window.__bpm_console || [];
              window.__bpm_console = [];
              return buf;
            })();
            """;

    // ── Observer presence detection ─────────────────────────────────────────────────────── // CHANGED: new constants for post-navigation re-injection

    /**
     * Checks whether BPM observers are active in the current page context.
     * Returns {@code true} if observers were injected into this page, {@code false}
     * if the page navigated and the JavaScript context was destroyed.
     * Used by {@code CdpSessionManager.ensureObserversInjected()}.
     */
    public static final String CHECK_OBSERVERS_PRESENT =
            "return typeof window.__bpm_observer_active !== 'undefined'";

    /**
     * Sets the observer-active marker after successful observer injection.
     * Destroyed automatically when the page navigates (JavaScript context reset).
     */
    public static final String SET_OBSERVER_MARKER =
            "window.__bpm_observer_active = true";

    // ── Resource timing buffer ────────────────────────────────────────────────────────── // CHANGED: per-action accuracy — prevents silent entry drops on pages with >150 resources

    /**
     * Raises the browser's Resource Timing buffer to 500 entries (default is 150).
     *
     * <p>Chrome silently drops the oldest entries once the buffer is full. Pages with
     * more than 150 sub-resources (ads, analytics, CDN assets) would have their oldest
     * resource timing entries silently dropped before {@link #COLLECT_RESOURCE_TIMING}
     * can drain them, causing {@code totalRequests} to be understated and the
     * {@code slowest[]} list to miss entries that loaded before the buffer filled.</p>
     *
     * <p>Called once in {@code CdpSessionManager.openSession()} and once in
     * {@code reInjectObservers()}. 500 is sufficient for all tested pages; if a page
     * fires more than 500 resources before a collection cycle the count is still capped,
     * but that is an edge case beyond practical concern.</p>
     */
    public static final String SET_RESOURCE_BUFFER_SIZE =
            "performance.setResourceTimingBufferSize(500)"; // CHANGED: per-action accuracy
}