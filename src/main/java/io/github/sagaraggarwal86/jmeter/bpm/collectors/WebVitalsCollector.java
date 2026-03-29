package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects Core Web Vitals (FCP, LCP, CLS, TTFB) from the browser.
 *
 * <p>Uses {@link JsSnippets#COLLECT_WEB_VITALS} which reads:</p>
 * <ul>
 *   <li>{@code window.__bpm_lcp} — set by the injected PerformanceObserver</li>
 *   <li>{@code window.__bpm_cls} — accumulated layout shift score</li>
 *   <li>FCP from Paint Timing API ({@code first-contentful-paint} entry)</li>
 *   <li>TTFB from Navigation Timing L2 ({@code responseStart})</li>
 * </ul>
 *
 * <p><strong>SPA stale detection:</strong> For single-page application navigation,
 * the old LCP value lingers because no new {@code largest-contentful-paint} event
 * fires for client-side route changes. This collector tracks the previous LCP
 * value per thread and returns {@code null} if LCP is unchanged, indicating
 * no new LCP event occurred for this action. Same logic applies to FCP and TTFB.</p>
 *
 * <p><strong>Zero-value guard:</strong> {@code lcp=0} and {@code fcp=0} mean the browser
 * has not yet fired an LCP or FCP event (e.g. the page is still loading). These are
 * treated as {@code null} (unavailable) rather than as perfect 0 ms values, preventing
 * them from inflating the performance score.</p>
 *
 * <p><strong>Per-action CLS:</strong> CLS accumulates across the lifetime of a page view.
 * To report per-action CLS (how much layout shift this specific user action caused),
 * this collector tracks the previous CLS value per thread and returns the positive delta.
 * On page navigation (signalled by {@link #resetThreadState}), the baseline resets to 0
 * so the first post-navigation sample captures the full CLS of the new page load.</p>
 *
 * <p>Thread safety: all per-thread tracking uses {@link ConcurrentHashMap}
 * keyed by thread name.</p>
 */
public final class WebVitalsCollector implements MetricsCollector<WebVitalsResult> {

    private static final Logger log = LoggerFactory.getLogger(WebVitalsCollector.class);

    /**
     * Tracks the last seen LCP value per thread for SPA stale detection.
     * Key: thread name, Value: previous LCP in milliseconds.
     */
    private final ConcurrentHashMap<String, Double> previousLcpByThread = new ConcurrentHashMap<>();

    /** Tracks the last seen FCP value per thread for SPA stale detection. */ // CHANGED: Bug 12 — FCP/TTFB stale tracking
    private final ConcurrentHashMap<String, Double> previousFcpByThread = new ConcurrentHashMap<>();

    /** Tracks the last seen TTFB value per thread for SPA stale detection. */ // CHANGED: Bug 12 — FCP/TTFB stale tracking
    private final ConcurrentHashMap<String, Double> previousTtfbByThread = new ConcurrentHashMap<>();

    /**
     * Tracks the last seen CLS value per thread for per-action delta computation.
     * Key: thread name, Value: previous CLS score (page-session accumulated at last collection).
     * Reset to 0 on navigation via {@link #resetThreadState}.
     */ // CHANGED: per-action accuracy — CLS stored as delta, not page-session total
    private final ConcurrentHashMap<String, Double> previousClsByThread = new ConcurrentHashMap<>();

    /**
     * Collects Web Vitals metrics from the browser.
     *
     * @param executor the CDP command executor
     * @param buffer   the metrics buffer (not used by this collector)
     * @return the Web Vitals result, or {@code null} if collection fails
     */
    @Override
    @SuppressWarnings("unchecked")
    public WebVitalsResult collect(CdpCommandExecutor executor, MetricsBuffer buffer) {
        Object result = executor.executeScript(JsSnippets.COLLECT_WEB_VITALS);

        if (!(result instanceof Map<?, ?> rawMap)) {
            log.debug("BPM: COLLECT_WEB_VITALS returned unexpected type: {}",
                    result != null ? result.getClass().getName() : "null");
            return null;
        }

        Map<String, Object> vitals = (Map<String, Object>) rawMap;

        double lcp = toDouble(vitals.get("lcp"));
        double cls = toDouble(vitals.get("cls"));
        double fcp = toDouble(vitals.get("fcp"));
        double ttfb = toDouble(vitals.get("ttfb"));

        String threadName = Thread.currentThread().getName();

        // SPA stale LCP detection
        Long lcpResult;
        Double previousLcp = previousLcpByThread.get(threadName);
        if (previousLcp != null && Double.compare(previousLcp, lcp) == 0 && lcp > 0) {
            log.debug("BPM: LCP unchanged ({} ms) — SPA stale detection, marking LCP null.", lcp);
            lcpResult = null;
        } else if (lcp == 0) { // CHANGED: per-action accuracy — lcp=0 means no event fired yet, not a perfect 0 ms value
            log.debug("BPM: LCP is 0 — no LCP event fired yet, marking LCP null.");
            lcpResult = null;
        } else {
            lcpResult = Math.round(lcp);
        }
        previousLcpByThread.put(threadName, lcp);

        // SPA stale FCP detection — FCP only fires once per full page load // CHANGED: Bug 12
        Long fcpResult;
        Double previousFcp = previousFcpByThread.get(threadName);
        if (previousFcp != null && Double.compare(previousFcp, fcp) == 0 && fcp > 0) {
            log.debug("BPM: FCP unchanged ({} ms) — SPA stale detection, marking FCP null.", fcp);
            fcpResult = null;
        } else if (fcp == 0) { // CHANGED: per-action accuracy — fcp=0 means no event fired yet, not a perfect 0 ms value
            log.debug("BPM: FCP is 0 — no FCP event fired yet, marking FCP null.");
            fcpResult = null;
        } else {
            fcpResult = Math.round(fcp);
        }
        previousFcpByThread.put(threadName, fcp);

        // SPA stale TTFB detection — TTFB only updates on full page navigation // CHANGED: Bug 12
        Long ttfbResult;
        Double previousTtfb = previousTtfbByThread.get(threadName);
        if (previousTtfb != null && Double.compare(previousTtfb, ttfb) == 0 && ttfb > 0) {
            log.debug("BPM: TTFB unchanged ({} ms) — SPA stale detection, marking TTFB null.", ttfb);
            ttfbResult = null;
        } else {
            ttfbResult = Math.round(ttfb);
        }
        previousTtfbByThread.put(threadName, ttfb);

        // CLS delta — per-action layout shift, not page-session accumulated total // CHANGED: per-action accuracy
        // previousClsByThread is reset to 0 on page navigation via resetThreadState(),
        // so the first post-navigation sample captures the full CLS of the new page load.
        double previousCls = previousClsByThread.getOrDefault(threadName, 0.0);
        double clsDelta = Math.max(0.0, cls - previousCls);
        previousClsByThread.put(threadName, cls);

        return new WebVitalsResult(fcpResult, lcpResult, clsDelta, ttfbResult); // CHANGED: clsDelta replaces raw cls
    }

    /**
     * Resets per-thread delta baselines for a specific thread after a page navigation.
     *
     * <p>Called by {@code BpmListener.collectMetrics()} when
     * {@code CdpSessionManager.ensureObserversInjected()} detects a navigation.
     * Clearing the previous values causes the next collection to treat all metrics
     * as fresh (no stale detection, CLS baseline reset to 0).</p>
     *
     * @param threadName the JMeter thread name whose state should be reset
     */ // CHANGED: per-action accuracy — navigation-aware per-thread reset
    public void resetThreadState(String threadName) {
        previousLcpByThread.remove(threadName);
        previousFcpByThread.remove(threadName);
        previousTtfbByThread.remove(threadName);
        previousClsByThread.remove(threadName);
    }

    /**
     * Resets all per-thread tracking state. Called during {@code testStarted()}.
     */
    public void reset() {
        previousLcpByThread.clear();
        previousFcpByThread.clear();  // CHANGED: Bug 12
        previousTtfbByThread.clear(); // CHANGED: Bug 12
        previousClsByThread.clear();  // CHANGED: per-action accuracy
    }

    /**
     * Safely converts a JavaScript number result to double.
     */
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}