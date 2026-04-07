package io.github.sagaraggarwal86.jmeter.bpm.util;

import java.util.Map;

/**
 * Central repository of all compile-time constants for the Browser Performance Metrics (BPM)
 * plugin. Groups constants by concern: JMeter variable names, JSONL schema, property keys,
 * default values, bottleneck labels, column definitions, tooltips, score weights, and GUI strings.
 *
 * <p>This class is not instantiable; all members are static.
 */
public final class BpmConstants {

    /**
     * Name of the JMeterVariables key under which WebDriver Sampler stores the browser instance
     * (expected type: {@code org.openqa.selenium.chrome.ChromeDriver}).
     */
    public static final String VAR_BROWSER = "Browser";

    // ── JMeter variable names ─────────────────────────────────────────────────────────────────
    /**
     * Name of the JMeterVariables key under which BPM stores the active CDP DevTools session
     * (type: {@code CdpSessionManager}).
     */
    public static final String VAR_BPM_DEV_TOOLS = "BPM_DevTools";
    /**
     * Name of the JMeterVariables key under which BPM stores the per-thread network event buffer
     * (type: {@code MetricsBuffer}).
     */
    public static final String VAR_BPM_EVENT_BUFFER = "BPM_EventBuffer";
    /**
     * JMeterVariables key for the last collected {@code BpmResult}.
     * Set by {@code BpmCollector}, read by all {@code BpmListener} instances.
     */
    public static final String VAR_BPM_RESULT = "BPM_Result";
    /**
     * JMeterVariables key for the {@code SampleResult} identity that produced the cached
     * {@code BpmResult}. Used to detect "already collected for this sample" across listeners.
     */
    public static final String VAR_BPM_LAST_SAMPLE = "BPM_LastSample";
    /**
     * JSONL schema version embedded in every output record.
     */
    public static final String SCHEMA_VERSION = "1.0";

    // ── JSONL schema ──────────────────────────────────────────────────────────────────────────
    /**
     * Default JSONL output filename, resolved relative to JMeter's working directory.
     */
    public static final String DEFAULT_OUTPUT_FILENAME = "bpm-results.jsonl";
    /**
     * JMeter TestElement property key used to persist the GUI output path field into the JMX.
     * Resolution order in BpmListener.testStarted():
     * {@code -Jbpm.output (highest) → this property → bpm.properties → hardcoded default}.
     */
    public static final String TEST_ELEMENT_OUTPUT_PATH = "bpm.outputPath";
    /**
     * TestElement property key: start offset in seconds for filter settings.
     */
    public static final String TEST_ELEMENT_START_OFFSET = "bpm.startOffset";
    /**
     * TestElement property key: end offset in seconds for filter settings.
     */
    public static final String TEST_ELEMENT_END_OFFSET = "bpm.endOffset";
    /**
     * TestElement property key: transaction names filter pattern.
     */
    public static final String TEST_ELEMENT_TRANSACTION_NAMES = "bpm.transactionNames";
    /**
     * TestElement property key: whether transaction names filter uses regex.
     */
    public static final String TEST_ELEMENT_REGEX = "bpm.regex";
    /**
     * TestElement property key: whether transaction names filter is include (true) or exclude (false).
     */
    public static final String TEST_ELEMENT_INCLUDE = "bpm.include";
    /**
     * TestElement property key: chart interval in seconds (0 = auto).
     */
    public static final String TEST_ELEMENT_CHART_INTERVAL = "bpm.chartInterval";
    /**
     * TestElement property key: per-element column visibility as comma-separated booleans.
     * Persisted in .jmx so each listener retains its own column selection across saves/loads.
     */
    public static final String TEST_ELEMENT_COLUMN_VISIBILITY = "bpm.columnVisibility";
    /**
     * TestElement property key: stable UUID assigned once at element creation.
     * Uniquely identifies a distinct element. Used as the key in
     * {@code BpmListener.primaryByName} to allow multiple distinct BpmListener
     * elements in one test plan to each run their own setup independently.
     */
    public static final String TEST_ELEMENT_ID = "bpm.elementId";
    /**
     * Property key: enable/disable Web Vitals metric collection tier.
     */
    public static final String PROP_METRICS_WEBVITALS = "metrics.webvitals";

    // ── Property keys ─────────────────────────────────────────────────────────────────────────
    /**
     * Property key: enable/disable Network metric collection tier.
     */
    public static final String PROP_METRICS_NETWORK = "metrics.network";
    /**
     * Property key: enable/disable Runtime metric collection tier.
     */
    public static final String PROP_METRICS_RUNTIME = "metrics.runtime";
    /**
     * Property key: enable/disable Console metric collection tier.
     */
    public static final String PROP_METRICS_CONSOLE = "metrics.console";
    /**
     * Property key: number of slowest resources to include in JSONL per sample.
     */
    public static final String PROP_NETWORK_TOP_N = "network.topN";
    /**
     * Property key: FCP good threshold in milliseconds.
     */
    public static final String PROP_SLA_FCP_GOOD = "sla.fcp.good";
    /**
     * Property key: FCP poor threshold in milliseconds (values above this are poor).
     */
    public static final String PROP_SLA_FCP_POOR = "sla.fcp.poor";
    /**
     * Property key: LCP good threshold in milliseconds.
     */
    public static final String PROP_SLA_LCP_GOOD = "sla.lcp.good";
    /**
     * Property key: LCP poor threshold in milliseconds.
     */
    public static final String PROP_SLA_LCP_POOR = "sla.lcp.poor";
    /**
     * Property key: CLS good threshold (unitless).
     */
    public static final String PROP_SLA_CLS_GOOD = "sla.cls.good";
    /**
     * Property key: CLS poor threshold (unitless).
     */
    public static final String PROP_SLA_CLS_POOR = "sla.cls.poor";
    /**
     * Property key: TTFB good threshold in milliseconds.
     */
    public static final String PROP_SLA_TTFB_GOOD = "sla.ttfb.good";
    /**
     * Property key: TTFB poor threshold in milliseconds.
     */
    public static final String PROP_SLA_TTFB_POOR = "sla.ttfb.poor";
    /**
     * Property key: JS error count that is still considered "good" (inclusive).
     */
    public static final String PROP_SLA_JSERRORS_GOOD = "sla.jserrors.good";
    /**
     * Property key: JS error count at which classification becomes "poor" (inclusive lower bound).
     */
    public static final String PROP_SLA_JSERRORS_POOR = "sla.jserrors.poor";
    /**
     * Property key: performance score threshold for "Good" classification.
     */
    public static final String PROP_SLA_SCORE_GOOD = "sla.score.good";
    /**
     * Property key: performance score below which classification is "Poor".
     */
    public static final String PROP_SLA_SCORE_POOR = "sla.score.poor";
    /**
     * Property key: server bottleneck ratio threshold — TTFB as % of LCP.
     */
    public static final String PROP_BOTTLENECK_SERVER_RATIO = "bottleneck.server.ratio";
    /**
     * Property key: resource bottleneck ratio threshold — slowest resource as % of LCP.
     */
    public static final String PROP_BOTTLENECK_RESOURCE_RATIO = "bottleneck.resource.ratio";
    /**
     * Property key: client rendering bottleneck ratio threshold — renderTime as % of LCP.
     */
    public static final String PROP_BOTTLENECK_CLIENT_RATIO = "bottleneck.client.ratio";
    /**
     * Property key: layout thrashing factor — layoutCount vs domNodes multiplier.
     */
    public static final String PROP_BOTTLENECK_LAYOUT_THRASH_FACTOR = "bottleneck.layoutThrash.factor";
    /**
     * Property key: enable/disable console message sanitization.
     */
    public static final String PROP_SECURITY_SANITIZE = "security.sanitize";
    /**
     * Property key for debug mode flag. Supports {@code -Jbpm.debug} CLI override in addition to
     * {@code bpm.properties}.
     */
    public static final String PROP_BPM_DEBUG = "bpm.debug";
    /**
     * Property key for JSONL output path. Supports {@code -Jbpm.output} CLI override in addition
     * to {@code bpm.properties}.
     */
    public static final String PROP_BPM_OUTPUT = "bpm.output";
    /**
     * Default: Web Vitals collection enabled.
     */
    public static final boolean DEFAULT_METRICS_WEBVITALS = true;

    // ── Default property values ───────────────────────────────────────────────────────────────
    /**
     * Default: Network collection enabled.
     */
    public static final boolean DEFAULT_METRICS_NETWORK = true;
    /**
     * Default: Runtime collection enabled.
     */
    public static final boolean DEFAULT_METRICS_RUNTIME = true;
    /**
     * Default: Console collection enabled.
     */
    public static final boolean DEFAULT_METRICS_CONSOLE = true;
    /**
     * Default: capture top 5 slowest network resources per sample.
     */
    public static final int DEFAULT_NETWORK_TOP_N = 5;
    /**
     * Default FCP good threshold: 1800 ms (Google Core Web Vitals).
     */
    public static final long DEFAULT_SLA_FCP_GOOD = 1_800L;
    /**
     * Default FCP poor threshold: 3000 ms.
     */
    public static final long DEFAULT_SLA_FCP_POOR = 3_000L;
    /**
     * Default LCP good threshold: 2500 ms.
     */
    public static final long DEFAULT_SLA_LCP_GOOD = 2_500L;
    /**
     * Default LCP poor threshold: 4000 ms.
     */
    public static final long DEFAULT_SLA_LCP_POOR = 4_000L;
    /**
     * Default CLS good threshold: 0.1.
     */
    public static final double DEFAULT_SLA_CLS_GOOD = 0.1;
    /**
     * Default CLS poor threshold: 0.25.
     */
    public static final double DEFAULT_SLA_CLS_POOR = 0.25;
    /**
     * Default TTFB good threshold: 800 ms.
     */
    public static final long DEFAULT_SLA_TTFB_GOOD = 800L;
    /**
     * Default TTFB poor threshold: 1800 ms.
     */
    public static final long DEFAULT_SLA_TTFB_POOR = 1_800L;
    /**
     * Default JS errors "good" count: 0 errors.
     */
    public static final int DEFAULT_SLA_JSERRORS_GOOD = 0;
    /**
     * Default JS errors "poor" lower bound: 5 errors.
     */
    public static final int DEFAULT_SLA_JSERRORS_POOR = 5;
    /**
     * Default performance score "Good" threshold: ≥ 90.
     */
    public static final int DEFAULT_SLA_SCORE_GOOD = 90;
    /**
     * Default performance score "Poor" threshold: &lt; 50.
     */
    public static final int DEFAULT_SLA_SCORE_POOR = 50;
    /**
     * Default server bottleneck ratio: TTFB &gt; 60% of LCP triggers the label.
     */
    public static final double DEFAULT_BOTTLENECK_SERVER_RATIO = 60.0;
    /**
     * Default resource bottleneck ratio: slowest resource &gt; 40% of LCP.
     */
    public static final double DEFAULT_BOTTLENECK_RESOURCE_RATIO = 40.0;
    /**
     * Default client rendering bottleneck ratio: renderTime &gt; 60% of LCP.
     */
    public static final double DEFAULT_BOTTLENECK_CLIENT_RATIO = 60.0;
    /**
     * Default layout thrashing factor: layoutCount &gt; domNodes × 0.5.
     */
    public static final double DEFAULT_BOTTLENECK_LAYOUT_THRASH_FACTOR = 0.5;
    /**
     * Default: console sanitization enabled.
     */
    public static final boolean DEFAULT_SECURITY_SANITIZE = true;
    /**
     * Default: debug logging disabled.
     */
    public static final boolean DEFAULT_BPM_DEBUG = false;

    // ── Improvement Area labels ───────────────────────────────────────────────────────────────
    /**
     * Improvement Area label (priority 1): one or more failed network requests detected.
     * Always a real problem regardless of score — fix this before anything else.
     */
    public static final String BOTTLENECK_RELIABILITY = "Fix Network Failures";

    /**
     * Improvement Area label (priority 2): TTFB is consuming > 60% of LCP time.
     */
    public static final String BOTTLENECK_SERVER = "Reduce Server Response";

    /**
     * Improvement Area label (priority 3): a single asset is consuming > 40% of LCP time.
     */
    public static final String BOTTLENECK_RESOURCE = "Optimise Heavy Assets";

    /**
     * Improvement Area label (priority 4): client-side rendering is consuming > 60% of LCP time.
     */
    public static final String BOTTLENECK_CLIENT = "Reduce Render Work";

    /**
     * Improvement Area label (priority 5): excessive layout recalculations relative to DOM size.
     */
    public static final String BOTTLENECK_LAYOUT = "Reduce DOM Complexity";

    /**
     * Improvement Area label when no condition fires — all load factors are proportional.
     */
    public static final String BOTTLENECK_NONE = "None";

    /**
     * Friendly display names for bottleneck areas (used in report cards).
     */
    public static final Map<String, String> BOTTLENECK_DISPLAY_NAMES = Map.of(
            BOTTLENECK_SERVER, "Slow Server Response",
            BOTTLENECK_RESOURCE, "Heavy Page Assets",
            BOTTLENECK_CLIENT, "Client Rendering Overhead",
            BOTTLENECK_LAYOUT, "Excessive DOM Complexity",
            BOTTLENECK_RELIABILITY, "Failed Network Requests",
            BOTTLENECK_NONE, "Mixed Performance Issues"
    );

    // ── Stability category labels ─────────────────────────────────────────────────────────────
    /**
     * CLS ≤ {@link #DEFAULT_SLA_CLS_GOOD} (0.10) — no perceptible layout shift.
     */
    public static final String STABILITY_STABLE = "Stable";

    /**
     * CLS between good and poor thresholds — noticeable but within tolerable range.
     */
    public static final String STABILITY_MINOR_SHIFTS = "Minor Shifts";

    /**
     * CLS > {@link #DEFAULT_SLA_CLS_POOR} (0.25) — disruptive layout shift.
     */
    public static final String STABILITY_UNSTABLE = "Unstable";

    // ── Trend stability labels (from TrendAnalyzer) ──────────────────────────────────────────────
    /**
     * Trend stability: all metrics stable throughout the test.
     */
    public static final String TREND_STABLE = "STABLE";
    /**
     * Trend stability: one metric shows decline.
     */
    public static final String TREND_MOSTLY_STABLE = "MOSTLY_STABLE";
    /**
     * Trend stability: two or more metrics degraded.
     */
    public static final String TREND_DEGRADING = "DEGRADING";

    // ── SLA Verdict labels ──────────────────────────────────────────────────────────────────────
    /**
     * SLA verdict: metric meets the "good" threshold.
     */
    public static final String VERDICT_GOOD = "GOOD";
    /**
     * SLA verdict: metric is between good and poor thresholds.
     */
    public static final String VERDICT_NEEDS_WORK = "NEEDS_WORK";
    /**
     * SLA verdict: metric exceeds the "poor" threshold.
     */
    public static final String VERDICT_POOR = "POOR";
    /**
     * SLA verdict: metric is not available (e.g. SPA stale, zero value, or no data).
     */
    public static final String VERDICT_NA = "N/A";

    /**
     * Model index of the Label column (always visible).
     */
    public static final int COL_IDX_LABEL = 0;
    /**
     * Model index of the Samples column (always visible).
     */
    public static final int COL_IDX_SAMPLES = 1;

    // ── Column model indices (full 18-column model) ───────────────────────────────────────────
    /**
     * Model index of the Score column (always visible).
     */
    public static final int COL_IDX_SCORE = 2;
    /**
     * Model index of the Render Time column — LCP − TTFB (always visible).
     */
    public static final int COL_IDX_RENDER_TIME = 3;
    /**
     * Model index of the Server Ratio column — TTFB/LCP % (always visible).
     */
    public static final int COL_IDX_SERVER_RATIO = 4;
    /**
     * Model index of the Frontend Time column — FCP − TTFB (always visible).
     */
    public static final int COL_IDX_FRONTEND_TIME = 5;
    /**
     * Model index of the FCP-LCP Gap column — LCP − FCP (always visible).
     */
    public static final int COL_IDX_FCP_LCP_GAP = 6;
    /**
     * Model index of the Stability column — CLS category (always visible).
     */
    public static final int COL_IDX_STABILITY = 7;
    /**
     * Model index of the Headroom column — LCP budget remaining % (always visible).
     */
    public static final int COL_IDX_HEADROOM = 8;
    /**
     * Model index of the Improvement Area column (always visible).
     */
    public static final int COL_IDX_IMPROVEMENT_AREA = 9;
    /**
     * @deprecated Use {@link #COL_IDX_IMPROVEMENT_AREA}.
     */
    @Deprecated
    public static final int COL_IDX_BOTTLENECK = COL_IDX_IMPROVEMENT_AREA;
    /**
     * Model index of the FCP column (raw metric, off by default).
     */
    public static final int COL_IDX_FCP = 10;
    /**
     * Model index of the LCP column (raw metric, off by default).
     */
    public static final int COL_IDX_LCP = 11;
    /**
     * Model index of the CLS column (raw metric, off by default).
     */
    public static final int COL_IDX_CLS = 12;
    /**
     * Model index of the TTFB column (raw metric, off by default).
     */
    public static final int COL_IDX_TTFB = 13;
    /**
     * Model index of the Reqs column (raw metric, off by default).
     */
    public static final int COL_IDX_REQS = 14;
    /**
     * Model index of the Size column (raw metric, off by default).
     */
    public static final int COL_IDX_SIZE = 15;
    /**
     * Model index of the Errs column (raw metric, off by default).
     */
    public static final int COL_IDX_ERRS = 16;
    /**
     * Model index of the Warns column (raw metric, off by default).
     */
    public static final int COL_IDX_WARNS = 17;
    /**
     * Total number of columns in the full table model.
     */
    public static final int TOTAL_COLUMN_COUNT = 18;
    /**
     * Number of always-visible (derived + identity) columns.
     */
    public static final int ALWAYS_VISIBLE_COLUMN_COUNT = 10;
    /**
     * Number of raw metric columns that can be toggled via the column selector.
     */
    public static final int RAW_COLUMN_COUNT = 8;
    /**
     * Column headers for the 10 always-visible columns in display order.
     * Indices align with {@code COL_IDX_LABEL} through {@code COL_IDX_IMPROVEMENT_AREA}.
     */
    public static final String[] ALWAYS_VISIBLE_HEADERS = {
            "Label", "Smpl", "Score", "Rndr(ms)", "Srvr(%)",
            "Front(ms)", "Gap(ms)", "Stability", "Headroom", "Improvement Area"
    };
    /**
     * Column headers for the 8 raw metric columns in display order.
     * Indices align with {@code COL_IDX_FCP} through {@code COL_IDX_WARNS}.
     */
    public static final String[] RAW_METRIC_HEADERS = {
            "FCP(ms)", "LCP(ms)", "CLS", "TTFB(ms)", "Reqs", "Size(KB)", "Errs", "Warns"
    };
    /**
     * All 18 column headers in full model order (always-visible first, then raw).
     */
    public static final String[] ALL_COLUMN_HEADERS = {
            // Always visible (0-9)
            "Label", "Smpl", "Score", "Rndr(ms)", "Srvr(%)",
            "Front(ms)", "Gap(ms)", "Stability", "Headroom", "Improvement Area",
            // Raw metrics (10-17)
            "FCP(ms)", "LCP(ms)", "CLS", "TTFB(ms)", "Reqs", "Size(KB)", "Errs", "Warns"
    };
    /**
     * Default visibility for each of the 8 raw metric columns (index 0 = FCP … index 7 = Warns).
     */
    public static final boolean[] RAW_COLUMNS_DEFAULT_VISIBILITY = {
            false, false, false, false, false, false, false, false
    };
    /**
     * Tooltip for the Score column.
     */
    public static final String TOOLTIP_SCORE =
            "Performance score (0-100). Weighted average of Core Web Vitals against SLA thresholds. Null for SPA actions where LCP/FCP/TTFB are not applicable.";
    /**
     * Tooltip for the Render Time column.
     */
    public static final String TOOLTIP_RENDER_TIME =
            "Render Time = LCP \u2212 TTFB. Time spent on client-side rendering after the server responded.";

    // ── Column header tooltips ────────────────────────────────────────────────────────────────
    /**
     * Tooltip for the Server Ratio column.
     */
    public static final String TOOLTIP_SERVER_RATIO =
            "Server Ratio = (TTFB \u00f7 LCP) \u00d7 100. Percentage of LCP time attributable to server response. Higher = more server-dominated.";
    /**
     * Tooltip for the Frontend Time column.
     */
    public static final String TOOLTIP_FRONTEND_TIME =
            "Frontend Time = FCP \u2212 TTFB. Time the browser spent parsing HTML and executing blocking scripts before showing any content. Large values indicate render-blocking resources.";
    /**
     * Tooltip for the FCP-LCP Gap column.
     */
    public static final String TOOLTIP_FCP_LCP_GAP =
            "FCP\u2013LCP Gap = LCP \u2212 FCP. Time between first paint and largest paint. Large gap = main content loads much later than initial paint, suggesting lazy-loaded or blocking content.";
    /**
     * Tooltip for the Stability column.
     */
    public static final String TOOLTIP_STABILITY =
            "Visual Stability based on Cumulative Layout Shift (CLS). Stable = CLS \u2264 0.10 (good). Minor Shifts = CLS 0.10\u20130.25 (needs work). Unstable = CLS > 0.25 (poor).";
    /**
     * Tooltip for the Headroom column.
     */
    public static final String TOOLTIP_HEADROOM =
            "LCP Performance Budget Remaining. Percentage of LCP budget left before hitting the Poor threshold. Trending toward 0% means the action is at risk of SLA breach under load.";
    /**
     * Tooltip for the Improvement Area column.
     */
    public static final String TOOLTIP_IMPROVEMENT_AREA =
            "Improvement Area: identifies the biggest factor consuming load time for this action. Where to focus if performance needs to improve further. Not a failure indicator \u2014 present even when Score is 100.";
    /**
     * @deprecated Use {@link #TOOLTIP_IMPROVEMENT_AREA}.
     */
    @Deprecated
    public static final String TOOLTIP_BOTTLENECK = TOOLTIP_IMPROVEMENT_AREA;
    /**
     * Tooltip for the FCP column.
     */
    public static final String TOOLTIP_FCP =
            "First Contentful Paint. Time until first text or image is visible.";
    /**
     * Tooltip for the LCP column.
     */
    public static final String TOOLTIP_LCP =
            "Largest Contentful Paint. Time until the largest visible element renders.";
    /**
     * Tooltip for the CLS column.
     */
    public static final String TOOLTIP_CLS =
            "Cumulative Layout Shift score (raw). Lower is better. See Stability column for categorised view.";
    /**
     * Tooltip for the TTFB column.
     */
    public static final String TOOLTIP_TTFB =
            "Time To First Byte. Server response time from request sent to first byte received.";
    /**
     * Tooltip for the Reqs column.
     */
    public static final String TOOLTIP_REQS =
            "Average number of network requests per action.";
    /**
     * Tooltip for the Size column.
     */
    public static final String TOOLTIP_SIZE =
            "Average total transfer size per action in kilobytes.";
    /**
     * Tooltip for the Errs column.
     */
    public static final String TOOLTIP_ERRS =
            "Total JavaScript errors captured from the browser console.";
    /**
     * Tooltip for the Warns column.
     */
    public static final String TOOLTIP_WARNS =
            "Total JavaScript warnings captured from the browser console.";
    /**
     * Cell tooltip for {@link #BOTTLENECK_NONE}.
     */
    public static final String VALUE_TOOLTIP_NONE =
            "All load factors are within proportion. No specific area needs attention.";
    /**
     * Cell tooltip for {@link #BOTTLENECK_RELIABILITY}.
     */
    public static final String VALUE_TOOLTIP_RELIABILITY =
            "One or more network requests failed (4xx/5xx or connection error). Investigate failing endpoints before optimising anything else.";

    // ── Improvement Area value tooltips ──────────────────────────────────────────────────────
    /**
     * Cell tooltip for {@link #BOTTLENECK_SERVER}.
     */
    public static final String VALUE_TOOLTIP_SERVER =
            "Time To First Byte is consuming more than 60% of LCP time. Consider server-side caching, CDN placement, or backend query optimisation.";
    /**
     * Cell tooltip for {@link #BOTTLENECK_RESOURCE}.
     */
    public static final String VALUE_TOOLTIP_RESOURCE =
            "A single asset (script, image, or third-party resource) is consuming more than 40% of LCP time. Compress, lazy-load, or defer it.";
    /**
     * Cell tooltip for {@link #BOTTLENECK_CLIENT}.
     */
    public static final String VALUE_TOOLTIP_CLIENT =
            "Client-side rendering is consuming more than 60% of LCP time. Reduce JavaScript execution, defer non-critical scripts, or optimise the critical rendering path.";
    /**
     * Cell tooltip for {@link #BOTTLENECK_LAYOUT}.
     */
    public static final String VALUE_TOOLTIP_LAYOUT =
            "Excessive layout recalculations detected relative to DOM size. Avoid reading and writing DOM layout properties in the same frame, and reduce DOM depth.";
    /**
     * Cell tooltip for {@link #STABILITY_STABLE}.
     */
    public static final String VALUE_TOOLTIP_STABLE =
            "CLS \u2264 0.10. No perceptible layout shift. Visual stability is good.";
    /**
     * Cell tooltip for {@link #STABILITY_MINOR_SHIFTS}.
     */
    public static final String VALUE_TOOLTIP_MINOR_SHIFTS =
            "CLS 0.10\u20130.25. Noticeable layout shift. Users may lose their place on the page.";

    // ── Stability value tooltips ──────────────────────────────────────────────────────────────
    /**
     * Cell tooltip for {@link #STABILITY_UNSTABLE}.
     */
    public static final String VALUE_TOOLTIP_UNSTABLE =
            "CLS > 0.25. Disruptive layout shift. Elements visibly jump as the page loads.";
    /**
     * LCP contribution to the composite performance score: 40%.
     */
    public static final double SCORE_WEIGHT_LCP = 0.40;
    /**
     * FCP contribution to the composite performance score: 15%.
     */
    public static final double SCORE_WEIGHT_FCP = 0.15;
    /**
     * CLS contribution to the composite performance score: 15%.
     */
    public static final double SCORE_WEIGHT_CLS = 0.15;
    /**
     * TTFB contribution to the composite performance score: 15%.
     */
    public static final double SCORE_WEIGHT_TTFB = 0.15;
    /**
     * JS error count contribution to the composite performance score: 15%.
     */
    public static final double SCORE_WEIGHT_ERRORS = 0.15;

    // ── Performance score weights ─────────────────────────────────────────────────────────────
    // NOTE: These were accidentally omitted in the last edit — restored here.
    /**
     * Minimum total metric weight required to produce a non-null performance score.
     * SPA-stale samples have CLS(0.15) + errors(0.15) = 0.30, which is below this
     * threshold, so their score is {@code null} instead of a misleading 100.
     */
    public static final double SCORE_MIN_WEIGHT = 0.45;
    /**
     * Info-bar text shown before any test runs.
     */
    public static final String INFO_DEFAULT =
            "\u2139 Captures browser rendering metrics from WebDriver Samplers using"
                    + " Chrome DevTools Protocol.";
    /**
     * URL opened by the Help button in the info bar.
     */
    public static final String HELP_URL =
            "https://github.com/sagaraggarwal86/BPM-jmeter-plugin#readme";
    /**
     * Info-bar text shown when Selenium/WebDriver classes are absent (Scenario A).
     */
    public static final String INFO_NO_SELENIUM =
            "\u26A0 Selenium/WebDriver Support plugin not found."
                    + " Install it via JMeter Plugins Manager.";
    /**
     * Info-bar text shown when no WebDriver Sampler data has arrived yet (Scenario B).
     */
    public static final String INFO_WAITING = "Waiting for WebDriver Sampler data...";
    /**
     * Info-bar text shown when all active threads use a non-Chrome browser (Scenario C).
     */
    public static final String INFO_NON_CHROME =
            "\u26A0 Non-Chrome browser detected. CDP metrics require Chrome/Chromium.";

    // ── GUI info-bar state messages ───────────────────────────────────────────────────────────
    /**
     * Info-bar text shown once the first BpmResult arrives and collection is active.
     */
    public static final String INFO_COLLECTING = "\u2139 Collecting...";
    /**
     * Classpath location of the bundled default properties template inside the JAR.
     */
    public static final String DEFAULT_PROPERTIES_RESOURCE = "/bpm-default.properties";
    /**
     * File name for the BPM properties file, placed under {@code <JMETER_HOME>/bin/}.
     */
    public static final String PROPERTIES_FILENAME = "bpm.properties";
    /**
     * First-line prefix of the properties file used to detect the embedded version string.
     * Format: {@code # Browser Performance Metrics (BPM) vX.Y}.
     */
    public static final String PROPERTIES_VERSION_PREFIX = "# Browser Performance Metrics (BPM) v";
    /**
     * Maximum number of raw BpmResult records retained in the GUI for retroactive filter rebuilds.
     */
    public static final int MAX_RAW_RESULTS = 10_000;
    /**
     * Number of JSONL records written before the BufferedWriter is flushed to disk.
     */
    public static final int JSONL_FLUSH_INTERVAL = 1;

    // ── Resource paths ────────────────────────────────────────────────────────────────────────
    /**
     * Interval in milliseconds between GUI live-update timer ticks.
     */
    public static final int GUI_UPDATE_INTERVAL_MS = 500;
    /**
     * Suffix of backup files created when the properties version is upgraded.
     */
    public static final String PROPERTIES_BACKUP_SUFFIX = ".bak";

    private BpmConstants() {
        throw new UnsupportedOperationException("BpmConstants is a utility class");
    }

    // ── I/O constants ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps a verdict constant to its human-readable display label for reports.
     *
     * @param verdict one of the {@code VERDICT_*} constants
     * @return "Pass", "Warning", "Fail", or {@code null} for N/A
     */
    public static String verdictToDisplay(String verdict) {
        if (verdict == null) return null;
        return switch (verdict) {
            case VERDICT_GOOD -> "Pass";
            case VERDICT_NEEDS_WORK -> "Warning";
            case VERDICT_POOR -> "Fail";
            default -> null;
        };
    }

    /**
     * Maps a verdict constant to an accessibility icon prefix for HTML cells.
     *
     * @param verdict one of the {@code VERDICT_*} constants
     * @return icon string with trailing space, or empty string for N/A
     */
    public static String verdictToIcon(String verdict) {
        if (verdict == null) return "";
        return switch (verdict) {
            case VERDICT_GOOD -> "\u2713 ";
            case VERDICT_NEEDS_WORK -> "\u26A0 ";
            case VERDICT_POOR -> "\u2717 ";
            default -> "";
        };
    }

    public static String verdictToCss(String verdict) {
        if (verdict == null) return "sla-na";
        return switch (verdict) {
            case VERDICT_GOOD -> "sla-pass";
            case VERDICT_NEEDS_WORK -> "sla-warn";
            case VERDICT_POOR -> "sla-fail";
            default -> "sla-na";
        };
    }

    /**
     * Returns the header tooltip for the given full-model column index.
     *
     * @param modelColumnIndex column index in the 18-column full model (0-based)
     * @return tooltip text, or {@code null} for Label and Samples (no tooltip)
     */
    public static String getTooltip(int modelColumnIndex) {
        return switch (modelColumnIndex) {
            case COL_IDX_SCORE -> TOOLTIP_SCORE;
            case COL_IDX_RENDER_TIME -> TOOLTIP_RENDER_TIME;
            case COL_IDX_SERVER_RATIO -> TOOLTIP_SERVER_RATIO;
            case COL_IDX_FRONTEND_TIME -> TOOLTIP_FRONTEND_TIME;
            case COL_IDX_FCP_LCP_GAP -> TOOLTIP_FCP_LCP_GAP;
            case COL_IDX_STABILITY -> TOOLTIP_STABILITY;
            case COL_IDX_HEADROOM -> TOOLTIP_HEADROOM;
            case COL_IDX_IMPROVEMENT_AREA -> TOOLTIP_IMPROVEMENT_AREA;
            case COL_IDX_FCP -> TOOLTIP_FCP;
            case COL_IDX_LCP -> TOOLTIP_LCP;
            case COL_IDX_CLS -> TOOLTIP_CLS;
            case COL_IDX_TTFB -> TOOLTIP_TTFB;
            case COL_IDX_REQS -> TOOLTIP_REQS;
            case COL_IDX_SIZE -> TOOLTIP_SIZE;
            case COL_IDX_ERRS -> TOOLTIP_ERRS;
            case COL_IDX_WARNS -> TOOLTIP_WARNS;
            default -> null;
        };
    }

    /**
     * Returns the cell-level tooltip for a value in the Improvement Area column,
     * or {@code null} if the value is unrecognised.
     *
     * @param value the improvement area label string
     * @return tooltip explaining what the label means and how to act on it
     */
    public static String getImprovementAreaValueTooltip(String value) {
        if (value == null) return null;
        return switch (value) {
            case BOTTLENECK_NONE -> VALUE_TOOLTIP_NONE;
            case BOTTLENECK_RELIABILITY -> VALUE_TOOLTIP_RELIABILITY;
            case BOTTLENECK_SERVER -> VALUE_TOOLTIP_SERVER;
            case BOTTLENECK_RESOURCE -> VALUE_TOOLTIP_RESOURCE;
            case BOTTLENECK_CLIENT -> VALUE_TOOLTIP_CLIENT;
            case BOTTLENECK_LAYOUT -> VALUE_TOOLTIP_LAYOUT;
            default -> null;
        };
    }

    /**
     * Returns the cell-level tooltip for a value in the Stability column,
     * or {@code null} if the value is unrecognised.
     *
     * @param value the stability category string
     * @return tooltip explaining the CLS range represented by this category
     */
    public static String getStabilityValueTooltip(String value) {
        if (value == null) return null;
        return switch (value) {
            case STABILITY_STABLE -> VALUE_TOOLTIP_STABLE;
            case STABILITY_MINOR_SHIFTS -> VALUE_TOOLTIP_MINOR_SHIFTS;
            case STABILITY_UNSTABLE -> VALUE_TOOLTIP_UNSTABLE;
            default -> null;
        };
    }
}