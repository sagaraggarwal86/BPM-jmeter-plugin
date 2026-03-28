package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Collects runtime performance metrics via the CDP {@code Performance.getMetrics} command.
 *
 * <p>Extracts:</p>
 * <ul>
 *   <li>{@code JSHeapUsedSize} — JavaScript heap memory in bytes</li>
 *   <li>{@code Nodes} — DOM node count</li>
 *   <li>{@code LayoutCount} — number of full or partial page layouts</li>
 *   <li>{@code RecalcStyleCount} — number of style recalculations</li>
 * </ul>
 *
 * <p>The CDP response structure is:
 * {@code {"metrics": [{"name": "JSHeapUsedSize", "value": 18400000}, ...]}}</p>
 */
public final class RuntimeCollector implements MetricsCollector<RuntimeResult> {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCollector.class);

    private static final String KEY_HEAP_USED = "JSHeapUsedSize";
    private static final String KEY_DOM_NODES = "Nodes";
    private static final String KEY_LAYOUT_COUNT = "LayoutCount";
    private static final String KEY_STYLE_RECALC = "RecalcStyleCount";

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    /**
     * Collects runtime metrics via CDP {@code Performance.getMetrics}.
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
        int layoutCount = 0;
        int styleRecalcCount = 0;

        Object metricsObj = response.get("metrics");
        if (metricsObj instanceof List<?> metricsList) {
            for (Object item : metricsList) {
                if (item instanceof Map<?, ?> metric) {
                    String name = String.valueOf(metric.get("name"));
                    double value = toDouble(metric.get("value"));

                    switch (name) {
                        case KEY_HEAP_USED -> heapUsed = (long) value;
                        case KEY_DOM_NODES -> domNodes = (int) value;
                        case KEY_LAYOUT_COUNT -> layoutCount = (int) value;
                        case KEY_STYLE_RECALC -> styleRecalcCount = (int) value;
                        default -> { /* ignore other metrics */ }
                    }
                }
            }
        }

        return new RuntimeResult(heapUsed, domNodes, layoutCount, styleRecalcCount);
    }
}
