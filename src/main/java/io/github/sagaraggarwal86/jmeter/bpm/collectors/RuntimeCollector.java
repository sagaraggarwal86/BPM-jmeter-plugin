package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects runtime performance metrics via the CDP {@code Performance.getMetrics} command.
 *
 * <p>Extracts:</p>
 * <ul>
 *   <li>{@code JSHeapUsedSize} — JavaScript heap memory in bytes (point-in-time snapshot)</li>
 *   <li>{@code Nodes} — DOM node count (point-in-time snapshot)</li>
 *   <li>{@code LayoutCount} — layouts triggered <em>by this action</em> (delta)</li>
 *   <li>{@code RecalcStyleCount} — style recalculations <em>by this action</em> (delta)</li>
 * </ul>
 *
 * <p><strong>Delta computation for layout and style counts:</strong>
 * Chrome's CDP {@code LayoutCount} and {@code RecalcStyleCount} are cumulative counters
 * that reset only on full page navigation. Storing the raw values would mix counts from
 * all previous actions on the same page into each sample. This collector tracks the
 * previous raw value per thread and stores the positive delta — the number of layouts
 * and style recalculations caused by the current action specifically.</p>
 *
 * <p>On page navigation, {@link #resetThreadState} clears the baselines so the first
 * post-navigation sample correctly reflects the layouts during the new page load,
 * not a negative (invalid) delta against the previous page's counter value.</p>
 *
 * <p>Thread safety: all per-thread tracking uses {@link ConcurrentHashMap}
 * keyed by JMeter thread name.</p>
 *
 * <p>The CDP response structure is:
 * {@code {"metrics": [{"name": "JSHeapUsedSize", "value": 18400000}, ...]}}</p>
 */ // CHANGED: per-action accuracy — layoutCount and styleRecalcCount are now deltas
public final class RuntimeCollector implements MetricsCollector<RuntimeResult> {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCollector.class);

    private static final String KEY_HEAP_USED = "JSHeapUsedSize";
    private static final String KEY_DOM_NODES = "Nodes";
    private static final String KEY_LAYOUT_COUNT = "LayoutCount";
    private static final String KEY_STYLE_RECALC = "RecalcStyleCount";

    /**
     * Raw CDP LayoutCount from the previous sample, per thread.
     * Used to compute the per-action delta. Reset on page navigation.
     */ // CHANGED: per-action accuracy
    private final ConcurrentHashMap<String, Integer> previousLayoutCountByThread = new ConcurrentHashMap<>();

    /**
     * Raw CDP RecalcStyleCount from the previous sample, per thread.
     * Used to compute the per-action delta. Reset on page navigation.
     */ // CHANGED: per-action accuracy
    private final ConcurrentHashMap<String, Integer> previousStyleRecalcByThread = new ConcurrentHashMap<>();

    /**
     * Collects runtime metrics via CDP {@code Performance.getMetrics}.
     *
     * <p>{@code layoutCount} and {@code styleRecalcCount} in the returned result are
     * the <em>deltas</em> since the previous sample for this thread — the counts caused
     * by the current action, not cumulative page totals.</p>
     *
     * @param executor the CDP command executor
     * @param buffer   the metrics buffer (not used by this collector)
     * @return the runtime metrics result; never null (returns zeros on failure)
     */
    @Override
    @SuppressWarnings("unchecked")
    public RuntimeResult collect(CdpCommandExecutor executor, MetricsBuffer buffer) {
        Map<String, Object> response = executor.executeCdpCommand(
                JsSnippets.CDP_METHOD_PERFORMANCE_GET_METRICS, Map.of());

        long heapUsed = 0;
        int domNodes = 0;
        int rawLayoutCount = 0;
        int rawStyleRecalcCount = 0;

        Object metricsObj = response.get("metrics");
        if (metricsObj instanceof List<?> metricsList) {
            for (Object item : metricsList) {
                if (item instanceof Map<?, ?> metric) {
                    String name = String.valueOf(metric.get("name"));
                    double value = toDouble(metric.get("value"));

                    switch (name) {
                        case KEY_HEAP_USED -> heapUsed = (long) value;
                        case KEY_DOM_NODES -> domNodes = (int) value;
                        case KEY_LAYOUT_COUNT -> rawLayoutCount = (int) value;
                        case KEY_STYLE_RECALC -> rawStyleRecalcCount = (int) value;
                        default -> { /* ignore other metrics */ }
                    }
                }
            }
        }

        // Compute per-action deltas — CHANGED: per-action accuracy
        // previousLayoutCountByThread is absent on first sample (baseline = 0) and on navigation reset.
        // Math.max(0, ...) guards against spurious negatives if CDP resets the counter mid-session.
        String threadName = Thread.currentThread().getName();
        int prevLayout = previousLayoutCountByThread.getOrDefault(threadName, 0);
        int prevStyle = previousStyleRecalcByThread.getOrDefault(threadName, 0);
        int layoutDelta = Math.max(0, rawLayoutCount - prevLayout);
        int styleRecalcDelta = Math.max(0, rawStyleRecalcCount - prevStyle);
        previousLayoutCountByThread.put(threadName, rawLayoutCount);
        previousStyleRecalcByThread.put(threadName, rawStyleRecalcCount);

        return new RuntimeResult(heapUsed, domNodes, layoutDelta, styleRecalcDelta);
    }

    /**
     * Resets per-thread delta baselines for a specific thread after a page navigation.
     *
     * <p>Called by {@code BpmListener.collectMetrics()} when
     * {@code CdpSessionManager.ensureObserversInjected()} detects a navigation.
     * Clearing the previous values means the next collection treats the counters as
     * starting from zero — capturing only the layouts during the new page load.</p>
     *
     * @param threadName the JMeter thread name whose baselines should be reset
     */ // CHANGED: per-action accuracy
    public void resetThreadState(String threadName) {
        previousLayoutCountByThread.remove(threadName);
        previousStyleRecalcByThread.remove(threadName);
    }

    /**
     * Resets all per-thread tracking state. Called during {@code testStarted()}.
     */ // CHANGED: per-action accuracy
    public void reset() {
        previousLayoutCountByThread.clear();
        previousStyleRecalcByThread.clear();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}