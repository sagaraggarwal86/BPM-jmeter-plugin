package io.github.sagaraggarwal86.jmeter.bpm.util;

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
     */ // CHANGED: P3 — wire GUI output path field through TestElement so it round-trips with the JMX
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
    public static final int DEFAULT_SLA_JSERRORS_POOR = 5; // CHANGED: Gap 2 — aligned with design doc §3.3 (1-5 = needs work, >5 = poor)
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
    // CHANGED: renamed from "Bottleneck labels" — values now describe the action to take,
    // not a problem statement, so score=100 with a label is not contradictory.
    /**
     * Improvement Area label (priority 1): one or more failed network requests detected.
     * Always a real problem regardless of score — fix this before anything else.
     */
    public static final String BOTTLENECK_RELIABILITY = "Fix Network Failures"; // CHANGED: renamed

    /**
     * Improvement Area label (priority 2): TTFB is consuming > 60% of LCP time.
     */
    public static final String BOTTLENECK_SERVER = "Reduce Server Response"; // CHANGED: renamed

    /**
     * Improvement Area label (priority 3): a single asset is consuming > 40% of LCP time.
     */
    public static final String BOTTLENECK_RESOURCE = "Optimise Heavy Assets"; // CHANGED: renamed

    /**
     * Improvement Area label (priority 4): client-side rendering is consuming > 60% of LCP time.
     */
    public static final String BOTTLENECK_CLIENT = "Reduce Render Work"; // CHANGED: renamed

    /**
     * Improvement Area label (priority 5): excessive layout recalculations relative to DOM size.
     */
    public static final String BOTTLENECK_LAYOUT = "Reduce DOM Complexity"; // CHANGED: renamed

    /**
     * Improvement Area label when no condition fires — all load factors are proportional.
     */
    public static final String BOTTLENECK_NONE = "None"; // CHANGED: renamed from "—"

    // ── Stability category labels ─────────────────────────────────────────────────────────────
    // CHANGED: new — CLS expressed as a human-readable category rather than raw decimal

    /** CLS ≤ {@link #DEFAULT_SLA_CLS_GOOD} (0.10) — no perceptible layout shift. */
    public static final String STABILITY_STABLE = "Stable";

    /** CLS between good and poor thresholds — noticeable but within tolerable range. */
    public static final String STABILITY_MINOR_SHIFTS = "Minor Shifts";

    /** CLS > {@link #DEFAULT_SLA_CLS_POOR} (0.25) — disruptive layout shift. */
    public static final String STABILITY_UNSTABLE = "Unstable";

    // ── Column model indices (full 18-column model) ───────────────────────────────────────────
    // CHANGED: 3 new always-visible columns added (Front, Stability, Headroom);
    // Improvement Area shifted from 6 → 9; raw columns shifted from 7-14 → 10-17.

    /** Model index of the Label column (always visible). */
    public static final int COL_IDX_LABEL = 0;

    /** Model index of the Samples column (always visible). */
    public static final int COL_IDX_SAMPLES = 1;

    /** Model index of the Score column (always visible). */
    public static final int COL_IDX_SCORE = 2;

    /** Model index of the Render Time column — LCP − TTFB (always visible). */
    public static final int COL_IDX_RENDER_TIME = 3;

    /** Model index of the Server Ratio column — TTFB/LCP % (always visible). */
    public static final int COL_IDX_SERVER_RATIO = 4;

    /** Model index of the Frontend Time column — FCP − TTFB (always visible). CHANGED: new */
    public static final int COL_IDX_FRONTEND_TIME = 5;

    /** Model index of the FCP-LCP Gap column — LCP − FCP (always visible). */
    public static final int COL_IDX_FCP_LCP_GAP = 6;

    /** Model index of the Stability column — CLS category (always visible). CHANGED: new */
    public static final int COL_IDX_STABILITY = 7;

    /** Model index of the Headroom column — LCP budget remaining % (always visible). CHANGED: new */
    public static final int COL_IDX_HEADROOM = 8;

    /** Model index of the Improvement Area column (always visible). CHANGED: renamed from COL_IDX_BOTTLENECK */
    public static final int COL_IDX_IMPROVEMENT_AREA = 9;

    /** @deprecated Use {@link #COL_IDX_IMPROVEMENT_AREA}. */
    @Deprecated public static final int COL_IDX_BOTTLENECK = COL_IDX_IMPROVEMENT_AREA;

    /** Model index of the FCP column (raw metric, off by default). */
    public static final int COL_IDX_FCP = 10;   // CHANGED: shifted from 7

    /** Model index of the LCP column (raw metric, off by default). */
    public static final int COL_IDX_LCP = 11;   // CHANGED: shifted from 8

    /** Model index of the CLS column (raw metric, off by default). */
    public static final int COL_IDX_CLS = 12;   // CHANGED: shifted from 9

    /** Model index of the TTFB column (raw metric, off by default). */
    public static final int COL_IDX_TTFB = 13;  // CHANGED: shifted from 10

    /** Model index of the Reqs column (raw metric, off by default). */
    public static final int COL_IDX_REQS = 14;  // CHANGED: shifted from 11

    /** Model index of the Size column (raw metric, off by default). */
    public static final int COL_IDX_SIZE = 15;  // CHANGED: shifted from 12

    /** Model index of the Errs column (raw metric, off by default). */
    public static final int COL_IDX_ERRS = 16;  // CHANGED: shifted from 13

    /** Model index of the Warns column (raw metric, off by default). */
    public static final int COL_IDX_WARNS = 17; // CHANGED: shifted from 14

    /** Total number of columns in the full table model. CHANGED: 15 → 18 */
    public static final int TOTAL_COLUMN_COUNT = 18;

    /** Number of always-visible (derived + identity) columns. CHANGED: 7 → 10 */
    public static final int ALWAYS_VISIBLE_COLUMN_COUNT = 10;

    /** Number of raw metric columns that can be toggled via the column selector. */
    public static final int RAW_COLUMN_COUNT = 8;

    /**
     * Column headers for the 10 always-visible columns in display order.
     * Indices align with {@code COL_IDX_LABEL} through {@code COL_IDX_IMPROVEMENT_AREA}.
     */ // CHANGED: 7 → 10 headers
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
     */ // CHANGED: 15 → 18 columns
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

    // ── Column header tooltips ────────────────────────────────────────────────────────────────

    /** Tooltip for the Score column. */
    public static final String TOOLTIP_SCORE =
            "Performance score (0-100). Weighted average of Core Web Vitals against SLA thresholds. Null for SPA actions where LCP/FCP/TTFB are not applicable.";

    /** Tooltip for the Render Time column. */
    public static final String TOOLTIP_RENDER_TIME =
            "Render Time = LCP \u2212 TTFB. Time spent on client-side rendering after the server responded.";

    /** Tooltip for the Server Ratio column. */
    public static final String TOOLTIP_SERVER_RATIO =
            "Server Ratio = (TTFB \u00f7 LCP) \u00d7 100. Percentage of LCP time attributable to server response. Higher = more server-dominated.";

    /** Tooltip for the Frontend Time column. CHANGED: new */
    public static final String TOOLTIP_FRONTEND_TIME =
            "Frontend Time = FCP \u2212 TTFB. Time the browser spent parsing HTML and executing blocking scripts before showing any content. Large values indicate render-blocking resources.";

    /** Tooltip for the FCP-LCP Gap column. */
    public static final String TOOLTIP_FCP_LCP_GAP =
            "FCP\u2013LCP Gap = LCP \u2212 FCP. Time between first paint and largest paint. Large gap = main content loads much later than initial paint, suggesting lazy-loaded or blocking content.";

    /** Tooltip for the Stability column. CHANGED: new */
    public static final String TOOLTIP_STABILITY =
            "Visual Stability based on Cumulative Layout Shift (CLS). Stable = CLS \u2264 0.10 (good). Minor Shifts = CLS 0.10\u20130.25 (needs work). Unstable = CLS > 0.25 (poor).";

    /** Tooltip for the Headroom column. CHANGED: new */
    public static final String TOOLTIP_HEADROOM =
            "LCP Performance Budget Remaining. Percentage of LCP budget left before hitting the Poor threshold. Trending toward 0% means the action is at risk of SLA breach under load.";

    /** Tooltip for the Improvement Area column. CHANGED: renamed + reworded */
    public static final String TOOLTIP_IMPROVEMENT_AREA =
            "Improvement Area: identifies the biggest factor consuming load time for this action. Where to focus if performance needs to improve further. Not a failure indicator \u2014 present even when Score is 100.";

    /** @deprecated Use {@link #TOOLTIP_IMPROVEMENT_AREA}. */
    @Deprecated public static final String TOOLTIP_BOTTLENECK = TOOLTIP_IMPROVEMENT_AREA;

    /** Tooltip for the FCP column. */
    public static final String TOOLTIP_FCP =
            "First Contentful Paint. Time until first text or image is visible.";

    /** Tooltip for the LCP column. */
    public static final String TOOLTIP_LCP =
            "Largest Contentful Paint. Time until the largest visible element renders.";

    /** Tooltip for the CLS column. */
    public static final String TOOLTIP_CLS =
            "Cumulative Layout Shift score (raw). Lower is better. See Stability column for categorised view.";

    /** Tooltip for the TTFB column. */
    public static final String TOOLTIP_TTFB =
            "Time To First Byte. Server response time from request sent to first byte received.";

    /** Tooltip for the Reqs column. */
    public static final String TOOLTIP_REQS =
            "Average number of network requests per action.";

    /** Tooltip for the Size column. */
    public static final String TOOLTIP_SIZE =
            "Average total transfer size per action in kilobytes.";

    /** Tooltip for the Errs column. */
    public static final String TOOLTIP_ERRS =
            "Total JavaScript errors captured from the browser console.";

    /** Tooltip for the Warns column. */
    public static final String TOOLTIP_WARNS =
            "Total JavaScript warnings captured from the browser console.";

    // ── Improvement Area value tooltips ──────────────────────────────────────────────────────
    // CHANGED: new — shown on cell hover in the Improvement Area column

    /** Cell tooltip for {@link #BOTTLENECK_NONE}. */
    public static final String VALUE_TOOLTIP_NONE =
            "All load factors are within proportion. No specific area needs attention.";

    /** Cell tooltip for {@link #BOTTLENECK_RELIABILITY}. */
    public static final String VALUE_TOOLTIP_RELIABILITY =
            "One or more network requests failed (4xx/5xx or connection error). Investigate failing endpoints before optimising anything else.";

    /** Cell tooltip for {@link #BOTTLENECK_SERVER}. */
    public static final String VALUE_TOOLTIP_SERVER =
            "Time To First Byte is consuming more than 60% of LCP time. Consider server-side caching, CDN placement, or backend query optimisation.";

    /** Cell tooltip for {@link #BOTTLENECK_RESOURCE}. */
    public static final String VALUE_TOOLTIP_RESOURCE =
            "A single asset (script, image, or third-party resource) is consuming more than 40% of LCP time. Compress, lazy-load, or defer it.";

    /** Cell tooltip for {@link #BOTTLENECK_CLIENT}. */
    public static final String VALUE_TOOLTIP_CLIENT =
            "Client-side rendering is consuming more than 60% of LCP time. Reduce JavaScript execution, defer non-critical scripts, or optimise the critical rendering path.";

    /** Cell tooltip for {@link #BOTTLENECK_LAYOUT}. */
    public static final String VALUE_TOOLTIP_LAYOUT =
            "Excessive layout recalculations detected relative to DOM size. Avoid reading and writing DOM layout properties in the same frame, and reduce DOM depth.";

    // ── Stability value tooltips ──────────────────────────────────────────────────────────────
    // CHANGED: new — shown on cell hover in the Stability column

    /** Cell tooltip for {@link #STABILITY_STABLE}. */
    public static final String VALUE_TOOLTIP_STABLE =
            "CLS \u2264 0.10. No perceptible layout shift. Visual stability is good.";

    /** Cell tooltip for {@link #STABILITY_MINOR_SHIFTS}. */
    public static final String VALUE_TOOLTIP_MINOR_SHIFTS =
            "CLS 0.10\u20130.25. Noticeable layout shift. Users may lose their place on the page.";

    /** Cell tooltip for {@link #STABILITY_UNSTABLE}. */
    public static final String VALUE_TOOLTIP_UNSTABLE =
            "CLS > 0.25. Disruptive layout shift. Elements visibly jump as the page loads.";

    /**
     * Returns the header tooltip for the given full-model column index.
     *
     * @param modelColumnIndex column index in the 18-column full model (0-based)
     * @return tooltip text, or {@code null} for Label and Samples (no tooltip)
     */ // CHANGED: updated for 18-column model
    public static String getTooltip(int modelColumnIndex) {
        return switch (modelColumnIndex) {
            case COL_IDX_SCORE            -> TOOLTIP_SCORE;
            case COL_IDX_RENDER_TIME      -> TOOLTIP_RENDER_TIME;
            case COL_IDX_SERVER_RATIO     -> TOOLTIP_SERVER_RATIO;
            case COL_IDX_FRONTEND_TIME    -> TOOLTIP_FRONTEND_TIME;
            case COL_IDX_FCP_LCP_GAP      -> TOOLTIP_FCP_LCP_GAP;
            case COL_IDX_STABILITY        -> TOOLTIP_STABILITY;
            case COL_IDX_HEADROOM         -> TOOLTIP_HEADROOM;
            case COL_IDX_IMPROVEMENT_AREA -> TOOLTIP_IMPROVEMENT_AREA;
            case COL_IDX_FCP              -> TOOLTIP_FCP;
            case COL_IDX_LCP              -> TOOLTIP_LCP;
            case COL_IDX_CLS              -> TOOLTIP_CLS;
            case COL_IDX_TTFB             -> TOOLTIP_TTFB;
            case COL_IDX_REQS             -> TOOLTIP_REQS;
            case COL_IDX_SIZE             -> TOOLTIP_SIZE;
            case COL_IDX_ERRS             -> TOOLTIP_ERRS;
            case COL_IDX_WARNS            -> TOOLTIP_WARNS;
            default -> null;
        };
    }

    /**
     * Returns the cell-level tooltip for a value in the Improvement Area column,
     * or {@code null} if the value is unrecognised.
     *
     * @param value the improvement area label string
     * @return tooltip explaining what the label means and how to act on it
     */ // CHANGED: new — value-level tooltips for Improvement Area column
    public static String getImprovementAreaValueTooltip(String value) {
        if (value == null) return null;
        return switch (value) {
            case "None"                    -> VALUE_TOOLTIP_NONE;
            case "Fix Network Failures"    -> VALUE_TOOLTIP_RELIABILITY;
            case "Reduce Server Response"  -> VALUE_TOOLTIP_SERVER;
            case "Optimise Heavy Assets"   -> VALUE_TOOLTIP_RESOURCE;
            case "Reduce Render Work"      -> VALUE_TOOLTIP_CLIENT;
            case "Reduce DOM Complexity"   -> VALUE_TOOLTIP_LAYOUT;
            default -> null;
        };
    }

    /**
     * Returns the cell-level tooltip for a value in the Stability column,
     * or {@code null} if the value is unrecognised.
     *
     * @param value the stability category string
     * @return tooltip explaining the CLS range represented by this category
     */ // CHANGED: new — value-level tooltips for Stability column
    public static String getStabilityValueTooltip(String value) {
        if (value == null) return null;
        return switch (value) {
            case "Stable"        -> VALUE_TOOLTIP_STABLE;
            case "Minor Shifts"  -> VALUE_TOOLTIP_MINOR_SHIFTS;
            case "Unstable"      -> VALUE_TOOLTIP_UNSTABLE;
            default -> null;
        };
    }

    // ── Performance score weights ─────────────────────────────────────────────────────────────
    // NOTE: These were accidentally omitted in the last edit — restored here.

    /** LCP contribution to the composite performance score: 40%. */
    public static final double SCORE_WEIGHT_LCP = 0.40;

    /** FCP contribution to the composite performance score: 15%. */
    public static final double SCORE_WEIGHT_FCP = 0.15;

    /** CLS contribution to the composite performance score: 15%. */
    public static final double SCORE_WEIGHT_CLS = 0.15;

    /** TTFB contribution to the composite performance score: 15%. */
    public static final double SCORE_WEIGHT_TTFB = 0.15;

    /** JS error count contribution to the composite performance score: 15%. */
    public static final double SCORE_WEIGHT_ERRORS = 0.15;

    /**
     * Minimum total metric weight required to produce a non-null performance score.
     * SPA-stale samples have CLS(0.15) + errors(0.15) = 0.30, which is below this
     * threshold, so their score is {@code null} instead of a misleading 100.
     */
    public static final double SCORE_MIN_WEIGHT = 0.45;

    // ── GUI info-bar state messages ───────────────────────────────────────────────────────────

    /** Info-bar text shown before any test runs. */
    public static final String INFO_DEFAULT =
            "\u2139 Captures browser rendering metrics from WebDriver Samplers using"
                    + " Chrome DevTools Protocol.";

    /** URL opened by the Help button in the info bar. */
    public static final String HELP_URL =
            "https://github.com/sagaraggarwal86/BPM-jmeter-plugin#readme";

    /** Info-bar text shown when Selenium/WebDriver classes are absent (Scenario A). */
    public static final String INFO_NO_SELENIUM =
            "\u26A0 Selenium/WebDriver Support plugin not found."
                    + " Install it via JMeter Plugins Manager.";

    /** Info-bar text shown when no WebDriver Sampler data has arrived yet (Scenario B). */
    public static final String INFO_WAITING = "Waiting for WebDriver Sampler data...";

    /** Info-bar text shown when all active threads use a non-Chrome browser (Scenario C). */
    public static final String INFO_NON_CHROME =
            "\u26A0 Non-Chrome browser detected. CDP metrics require Chrome/Chromium.";

    /** Info-bar text shown once the first BpmResult arrives and collection is active. */
    public static final String INFO_COLLECTING = "\u2139 Collecting...";

    // ── Resource paths ────────────────────────────────────────────────────────────────────────

    /** Classpath location of the bundled default properties template inside the JAR. */
    public static final String DEFAULT_PROPERTIES_RESOURCE = "/bpm-default.properties";

    /** File name for the BPM properties file, placed under {@code <JMETER_HOME>/bin/}. */
    public static final String PROPERTIES_FILENAME = "bpm.properties";

    /**
     * First-line prefix of the properties file used to detect the embedded version string.
     * Format: {@code # Browser Performance Metrics (BPM) vX.Y}.
     */
    public static final String PROPERTIES_VERSION_PREFIX = "# Browser Performance Metrics (BPM) v";

    // ── I/O constants ─────────────────────────────────────────────────────────────────────────

    /** Number of JSONL records written before the BufferedWriter is flushed to disk. */
    public static final int JSONL_FLUSH_INTERVAL = 10;

    /** Interval in milliseconds between GUI live-update timer ticks. */
    public static final int GUI_UPDATE_INTERVAL_MS = 500;

    /** Suffix of backup files created when the properties version is upgraded. */
    public static final String PROPERTIES_BACKUP_SUFFIX = ".bak";

    private BpmConstants() {
        throw new UnsupportedOperationException("BpmConstants is a utility class");
    }
}