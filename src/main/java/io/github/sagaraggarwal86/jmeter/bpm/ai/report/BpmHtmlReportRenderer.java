package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders AI-generated Markdown into a styled standalone HTML report
 * with a sidebar navigation derived from H2 headings, a metadata header,
 * and optional time-series performance charts.
 *
 * <p>Layout uses panel-based show/hide navigation (click sidebar items
 * to switch panels). Includes Excel export via SheetJS CDN.</p>
 */
public final class BpmHtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(BpmHtmlReportRenderer.class);
    private static final Pattern H2_PATTERN = Pattern.compile("<h2>(.*?)</h2>");
    private static final Pattern HR_PATTERN = Pattern.compile("<hr\\s*/?>\\s*");
    private static final DateTimeFormatter CHART_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FOOTER_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] SLA_HEADER_TOOLTIPS = {
            "Transaction/sampler name",
            "Performance score (0-100). Higher is better",
            "Largest Contentful Paint \u2014 time until main content is visible. Lower is better",
            "First Contentful Paint \u2014 time until first text/image appears. Lower is better",
            "Time To First Byte \u2014 server processing + network latency. Lower is better",
            "Cumulative Layout Shift \u2014 measures unexpected content movement. Lower is better"
    };
    // Unit suffixes for SLA value display: index 0=Page (unused), 1=Score, 2=LCP, 3=FCP, 4=TTFB, 5=CLS
    private static final String[] SLA_UNITS = {"", "", "ms", "ms", "ms", ""};
    private static final String[] CF_HEADERS = {
            "Transaction Name", "Severity", "Issue", "Root Cause", "User Impact", "Recommended Action"
    };
    private static final String[] CF_HEADER_TOOLTIPS = {
            "Transaction/sampler name",
            "Critical (any POOR verdict) or Warning (NEEDS_WORK only)",
            "Which metrics breach SLA thresholds",
            "Primary bottleneck driving the issue",
            "What end users experience as a result",
            "Specific action to resolve the issue"
    };
    // Root-cause diagnosis keyed by improvementArea (what's wrong)
    private static final Map<String, String> ROOT_CAUSE_MAP = Map.of(
            "Fix Network Failures", "Failed or blocked network requests",
            "Reduce Server Response", "Backend processing bottleneck",
            "Optimise Heavy Assets", "Large resources blocking render",
            "Reduce Render Work", "Client-side rendering overhead",
            "Reduce DOM Complexity", "Excessive DOM node count",
            "None", "No single bottleneck identified"
    );
    // Recommended action keyed by improvementArea (what to do)
    private static final Map<String, String> ACTION_MAP = Map.of(
            "Fix Network Failures", "Check DevTools Network tab for 4xx/5xx errors. Verify CDN and third-party resource availability.",
            "Reduce Server Response", "Profile backend response time. Check database queries, API calls, and caching headers via DevTools Timing tab.",
            "Optimise Heavy Assets", "Identify largest resources via DevTools Network tab (sort by Size). Compress images (WebP/AVIF), lazy-load below-fold content.",
            "Reduce Render Work", "Profile main thread in DevTools Performance tab. Look for long JavaScript tasks (>50ms) blocking rendering.",
            "Reduce DOM Complexity", "Check DOM node count in DevTools Elements panel. Reduce nested elements and virtualize long lists.",
            "None", "Monitor for regression"
    );

    // ── Heading extraction ──────────────────────────────────────────────────
    // Sidebar icons for navigation panels
    private static final Map<String, String> SIDEBAR_ICONS = Map.of(
            "Executive Summary", "\uD83D\uDCCA",      // chart emoji
            "Performance Metrics", "\uD83D\uDCCB",     // clipboard emoji
            "Performance Trends", "\uD83D\uDCC8",      // chart-increasing emoji
            "SLA Compliance", "\u2705",                 // check mark emoji
            "Critical Findings", "\u26A0\uFE0F",       // warning emoji
            "Risk Assessment", "\uD83D\uDEE1\uFE0F"   // shield emoji
    );

    // User impact templates keyed by which metric is worst
    private static final Map<String, String> IMPACT_TEMPLATES = Map.of(
            "LCP", "Users wait %sms before main content appears",
            "FCP", "Users see a blank screen for %sms before first paint",
            "TTFB", "Users wait %sms for server to begin responding",
            "CLS", "Users experience unexpected layout shifts (CLS %s)",
            "Score", "Overall degraded experience (Score %s)"
    );
    // Metric names in same order as verdicts/values arrays: Score, LCP, FCP, TTFB, CLS
    private static final String[] IMPACT_METRIC_NAMES = {"Score", "LCP", "FCP", "TTFB", "CLS"};

    // ── Page assembly ───────────────────────────────────────────────────────
    private static final String[] METRICS_HEADER_TOOLTIPS = {
            "Transaction/sampler name",
            "Number of samples collected",
            "Performance score (0-100). Higher is better",
            "Render Time \u2014 client-side rendering duration (LCP \u2212 TTFB)",
            "Server Ratio \u2014 percentage of load time spent on server response",
            "Frontend Time \u2014 browser processing time (FCP \u2212 TTFB)",
            "FCP-LCP Gap \u2014 delay between first paint and largest content",
            "Layout stability category based on CLS",
            "Percentage of SLA budget remaining before breach",
            "Pre-computed bottleneck classification",
            "First Contentful Paint \u2014 time until first text/image appears",
            "Largest Contentful Paint \u2014 time until main content is visible",
            "Cumulative Layout Shift \u2014 measures unexpected content movement",
            "Time To First Byte \u2014 server processing + network latency",
            "Average network requests per sample",
            "Average transfer size per sample",
            "Total JavaScript errors",
            "Total console warnings"
    };

    // ── Header ──────────────────────────────────────────────────────────────
    private static final Map<String, String> IMPROVEMENT_AREA_TOOLTIPS = Map.of(
            "Fix Network Failures", "Check failed requests in DevTools Network tab (filter by status 4xx/5xx). Verify CDN and third-party resource availability.",
            "Reduce Server Response", "Profile backend response time. Check database queries, API calls, and caching headers via DevTools Timing tab.",
            "Optimise Heavy Assets", "Identify largest resources via DevTools Network tab (sort by Size). Compress images (WebP/AVIF), lazy-load below-fold content.",
            "Reduce Render Work", "Profile main thread in DevTools Performance tab. Look for long JavaScript tasks (>50ms) blocking rendering.",
            "Reduce DOM Complexity", "Check DOM node count in DevTools (Elements panel). Reduce nested elements and virtualize long lists.",
            "None", "Performance is balanced \u2014 no single bottleneck identified."
    );

    // ── Sidebar ─────────────────────────────────────────────────────────────
    private static final String CSS = """
              <style>
                :root {
                  --color-text-primary:         #1a202c;
                  --color-text-secondary:       #4a5568;
                  --color-text-tertiary:        #718096;
                  --color-background-primary:   #ffffff;
                  --color-background-secondary: #f7fafc;
                  --color-background-tertiary:  #f0f4f8;
                  --color-border-secondary:     #cbd5e0;
                  --color-border-tertiary:      #e2e8f0;
                  --color-pass:                 #38a169;
                  --color-warning:              #d69e2e;
                  --color-fail:                 #e53e3e;
                }
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                html, body { height: 100%; }
                .rpt {
                  font-family: 'Segoe UI', system-ui, sans-serif; font-size: 14px;
                  line-height: 1.7; color: var(--color-text-primary);
                  background: var(--color-background-tertiary);
                  display: flex; flex-direction: column; min-height: 100vh;
                }
            
                /* ── Header ────────────────────────────────────────────────── */
                .rpt-header {
                  background: #1a365d;
                  color: white; padding: 24px 32px;
                  display: flex; align-items: flex-start; justify-content: space-between;
                  gap: 24px; flex-shrink: 0;
                }
                .header-left { flex: 1; min-width: 0; }
                .rpt-header h1 { font-size: 22px; font-weight: 600; margin-bottom: 4px; color: white; }
                .sub { font-size: 12px; color: rgba(255,255,255,0.7); margin-bottom: 8px; }
                .meta-grid { display: grid; grid-template-columns: auto 1fr; gap: 2px 16px; font-size: 12px; }
                .ml { color: rgba(255,255,255,0.6); font-weight: 500; white-space: nowrap; }
                .mv { color: white; }
                .header-actions { display: flex; flex-direction: column; gap: 6px; align-items: flex-end; flex-shrink: 0; padding-top: 4px; }
                .exp-btn {
                  width: 148px; padding: 7px 0;
                  border: 1px solid rgba(255,255,255,0.35); background: rgba(255,255,255,0.10);
                  cursor: pointer; border-radius: 5px; font-size: 13px; font-family: inherit;
                  color: white; white-space: nowrap; line-height: 1.3; text-align: center;
                  transition: background 0.15s;
                }
                .exp-btn:hover { background: rgba(255,255,255,0.22); }
            
                /* ── Layout ─────────────────────────────────────────────────── */
                .body-row { display: flex; align-items: stretch; flex: 1; }
                .sidebar {
                  width: 210px; flex-shrink: 0; background: #f0f4f8;
                  border-right: 1px solid #cbd5e0; padding: 12px 0;
                  position: sticky; top: 0; align-self: flex-start;
                  height: 100vh; overflow-y: auto;
                }
                .nav-item {
                  display: block; width: 100%; padding: 10px 20px; border: none;
                  background: transparent; text-align: left; font-size: 13px;
                  font-family: inherit; color: #4a5568; cursor: pointer;
                  line-height: 1.4; border-left: 3px solid transparent;
                  transition: all 0.12s;
                }
                .nav-item:hover  { background: #e2e8f0; color: #2d3748; }
                .nav-item.active { background: #fff; color: #1a365d; font-weight: 600; border-left: 3px solid #1a365d; }
                .main-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
                .content-area { flex: 1; padding: 28px 32px; background: var(--color-background-primary); overflow: auto; }
                .panel       { display: none; }
                .panel.active { display: block; }
            
                /* ── Typography ─────────────────────────────────────────────── */
                h2 {
                  font-size: 16px; font-weight: 600; color: var(--color-text-primary);
                  border-left: 3px solid #3182ce; padding-left: 11px; margin: 0 0 16px;
                }
                h3 {
                  font-size: 14px; font-weight: 600; color: #2d3748; margin: 20px 0 8px;
                  padding-bottom: 6px; border-bottom: 1px solid var(--color-border-tertiary);
                }
                p  { margin-bottom: 12px; font-size: 13px; color: var(--color-text-primary); }
                code { background: #edf2f7; padding: 2px 6px; border-radius: 4px; font-family: Consolas, monospace; font-size: 12px; color: #c53030; }
                pre  { background: #2d3748; color: #e2e8f0; padding: 14px 18px; border-radius: 6px; overflow-x: auto; margin: 12px 0 18px; }
                pre code { background: none; color: inherit; padding: 0; }
                blockquote { border-left: 4px solid #63b3ed; margin: 14px 0; padding: 10px 16px; background: #ebf8ff; border-radius: 0 6px 6px 0; font-size: 13px; }
                strong { font-weight: 700; }
            
                /* ── Tables ──────────────────────────────────────────────────── */
                .tbl-wrap { overflow-x: auto; margin: 12px 0 20px; border-radius: 6px; border: 0.5px solid var(--color-border-secondary); }
                table { border-collapse: collapse; width: 100%; background: var(--color-background-primary); font-size: 12px; }
                th {
                  background: #2d3748; color: white; padding: 8px 12px;
                  text-align: left; font-size: 11px; font-weight: 600;
                  text-transform: uppercase; letter-spacing: 0.4px; white-space: nowrap;
                  position: sticky; top: 0; z-index: 1;
                }
                td { padding: 7px 12px; border-bottom: 0.5px solid var(--color-border-tertiary); white-space: nowrap; }
                td.sla-pass, span.sla-pass { text-align: center; font-weight: 600; color: #276749; }
                td.sla-fail, span.sla-fail { text-align: center; font-weight: 700; color: #c53030; }
                td.sla-warn, span.sla-warn { text-align: center; font-weight: 600; color: #b7791f; }
                td.sla-na   { text-align: center; color: #a0aec0; }
                td.num { text-align: right; font-variant-numeric: tabular-nums; }
                tr:last-child td  { border-bottom: none; }
                tr:nth-child(even) td { background: var(--color-background-secondary); }
                tr.total-row td { font-weight: 700; background: #edf2f7; border-top: 2px solid var(--color-border-secondary); }
                td.label-cell { font-weight: 500; }
                .tbl-controls { margin-bottom: 8px; font-size: 12px; display: flex; align-items: center; gap: 8px; }
                .tbl-controls select { padding: 3px 6px; border: 1px solid var(--color-border-secondary); border-radius: 4px; font-size: 12px; font-family: inherit; }
                .tbl-scroll { overflow-y: auto; overflow-x: auto; }
                th.sort-asc::after { content: ' \\25B2'; font-size: 9px; }
                th.sort-desc::after { content: ' \\25BC'; font-size: 9px; }
                th:hover { background: #4a5568; }
                .pager { margin: 8px 0; font-size: 12px; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
                .pg-btn {
                  padding: 3px 8px; border: 1px solid var(--color-border-secondary); border-radius: 3px;
                  background: var(--color-background-primary); cursor: pointer; font-size: 11px; font-family: inherit;
                }
                .pg-btn:hover:not(:disabled) { background: #e2e8f0; }
                .pg-btn:disabled { opacity: 0.4; cursor: default; }
                .pg-active { background: #1a365d; color: white; border-color: #1a365d; }
                .pg-active:hover { background: #2a4365; }
                .pg-info { font-size: 11px; color: var(--color-text-tertiary); margin-left: 8px; }
                [data-table-id="cf"] td { white-space: normal; }
                [data-table-id="cf"] td:nth-child(1),
                [data-table-id="cf"] td:nth-child(2) { white-space: nowrap; }
                [data-table-id="cf"] .tbl-scroll { max-height: 520px; }
                .sla-summary { font-size: 13px; margin-bottom: 12px; font-weight: 500; }
                .sla-pass-summary { color: #276749; }
                .sla-fail-summary { color: #c53030; }
                .metric-desc { margin-top: 20px; padding: 14px 16px; background: var(--color-background-secondary); border: 1px solid var(--color-border-tertiary); border-radius: 6px; }
                .metric-desc h3 { font-size: 12px; margin: 0 0 8px; border: none; padding: 0; }
                .metric-desc dl { font-size: 12px; margin: 0; }
                .metric-desc dt { font-weight: 600; color: var(--color-text-primary); margin-top: 6px; }
                .metric-desc dd { color: var(--color-text-secondary); margin-left: 0; margin-bottom: 2px; }
                .sla-thresholds { margin-top: 14px; font-size: 11px; color: var(--color-text-tertiary); line-height: 1.6; }
                .tbl-search { margin-bottom: 10px; }
                .tbl-search input {
                  padding: 6px 10px; border: 1px solid var(--color-border-secondary); border-radius: 4px;
                  font-size: 12px; font-family: inherit; width: 260px;
                  transition: border-color 0.15s;
                }
                .tbl-search input:focus { outline: none; border-color: #3182ce; box-shadow: 0 0 0 2px rgba(49,130,206,0.15); }
            
                /* ── Lists ──────────────────────────────────────────────────── */
                ul, ol { margin: 8px 0 14px 22px; font-size: 13px; }
                li { margin-bottom: 5px; }
                li strong { color: #000000; }
            
                /* ── Executive Summary: actionable finding cards ─────────────── */
                .panel ul { list-style: none; margin-left: 0; padding: 0; }
                .panel ul li {
                  background: var(--color-background-secondary);
                  border: 1px solid var(--color-border-tertiary);
                  border-left: 4px solid #2b6cb0;
                  border-radius: 0 6px 6px 0;
                  padding: 10px 14px; margin-bottom: 10px;
                }
                .panel ol { list-style: decimal; margin-left: 22px; }
                .panel ol li {
                  background: transparent; border: none; border-left: none;
                  border-radius: 0; padding: 0; margin-bottom: 5px;
                }
            
                /* ── Critical Findings: severity-colored cards ──────────────── */
                .panel h3 + ul { margin-top: 0; }
                .panel h3 + ul li {
                  border-left-color: var(--color-warning);
                }
            
                /* ── KPI Cards (first panel) ────────────────────────────────── */
                .metadata-grid.kpi-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 12px; margin-bottom: 24px; }
                .kpi {
                  background: var(--color-background-secondary); border-radius: 8px;
                  padding: 14px 16px; border: 1px solid var(--color-border-tertiary);
                  transition: box-shadow 0.15s;
                }
                .kpi:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
                .kpi-label { font-size: 10px; color: var(--color-text-secondary); margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 600; }
                .kpi-value { font-size: 20px; font-weight: 500; color: var(--color-text-primary); line-height: 1.2; }
                .kpi-value.pass { color: #276749; font-weight: 700; }
                .kpi-value.fail { color: #c53030; font-weight: 700; }
            
                /* ── Charts ─────────────────────────────────────────────────── */
                .charts-section  { margin: 0; }
                .charts-note { font-size: 11px; color: var(--color-text-tertiary); margin-bottom: 12px; }
                .chart-filter { margin-bottom: 16px; font-size: 13px; }
                .chart-filter select { padding: 4px 8px; border: 1px solid var(--color-border-secondary); border-radius: 4px; font-size: 12px; font-family: inherit; }
                .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 4px; }
                .chart-box {
                  background: var(--color-background-secondary); border: 1px solid var(--color-border-tertiary);
                  border-radius: 8px; padding: 16px 18px 12px;
                  transition: box-shadow 0.15s;
                }
                .chart-box:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
                .chart-box h3 { font-size: 12px; font-weight: 600; color: var(--color-text-secondary); margin: 0 0 12px; text-transform: uppercase; letter-spacing: 0.4px; border: none; padding: 0; }
                .chart-canvas-wrap { position: relative; height: 220px; }
            
                /* ── Footer ─────────────────────────────────────────────────── */
                .footer-rpt {
                  font-size: 11px; color: var(--color-text-tertiary); padding: 12px 32px;
                  border-top: 1px solid var(--color-border-tertiary);
                  background: var(--color-background-primary); flex-shrink: 0;
                }
            
                /* ── Responsive ─────────────────────────────────────────────── */
                @media (max-width: 768px) {
                  .sidebar { display: none; }
                  .content-area { padding: 16px; }
                  .rpt-header { flex-direction: column; padding: 16px; }
                  .charts-grid { grid-template-columns: 1fr; }
                }
            
                /* ── Print / PDF ────────────────────────────────────────────── */
                @media print {
                  .sidebar { display: none !important; }
                  .body-row { display: block; }
                  .main-col { display: block; }
                  .panel { display: block !important; page-break-inside: avoid; margin-bottom: 24px; }
                  .rpt-header { background: #1a365d !important; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
                  .header-actions { display: none; }
                  .tbl-search { display: none; }
                  .tbl-controls { display: none; }
                  .chart-filter { display: none; }
                  .tbl-scroll { max-height: none !important; overflow: visible !important; }
                  .content-area { padding: 16px; overflow: visible; }
                  .rpt { min-height: auto; }
                  .footer-rpt { position: relative; }
                  table { font-size: 10px; }
                  th, td { padding: 4px 8px; }
                  .charts-grid { grid-template-columns: 1fr; }
                  .chart-canvas-wrap { height: 180px; }
                  canvas { max-width: 100%; }
                }
              </style>
            """;

    // ── Metadata KPI cards (first panel) ────────────────────────────────────

    private BpmHtmlReportRenderer() {
    }

    // ── Metrics table panel ────────────────────────────────────────────────

    /**
     * Simple overload for GUI mode (no metadata, no charts).
     */
    public static String render(String markdown, String providerName) {
        return render(markdown, new RenderConfig(providerName, "", "", ""),
                Collections.emptyList(), Collections.emptyMap());
    }

    // ── SLA Compliance panel ────────────────────────────────────────────────

    /**
     * Overload with config but no charts.
     */
    public static String render(String markdown, RenderConfig config) {
        return render(markdown, config, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Backward-compatible render with global charts only.
     */
    public static String render(String markdown, RenderConfig config,
                                List<BpmTimeBucket> timeBuckets) {
        return render(markdown, config, timeBuckets, Collections.emptyMap());
    }

    /**
     * Full render with metadata, time-series charts, per-label page filter, and metrics table.
     *
     * @param markdown        AI-generated Markdown text
     * @param config          rendering configuration with provider info, metadata, and SLA thresholds
     * @param timeBuckets     global time-series data for performance charts (may be empty)
     * @param perLabelBuckets per-label time-series data for page filter (may be empty)
     * @return complete HTML string ready to write to a file
     */
    public static String render(String markdown, RenderConfig config,
                                List<BpmTimeBucket> timeBuckets,
                                Map<String, List<BpmTimeBucket>> perLabelBuckets) {
        return render(markdown, config, timeBuckets, perLabelBuckets, Collections.emptyList());
    }

    /**
     * Full render with all features including a Performance Metrics table panel.
     *
     * @param markdown        AI-generated Markdown text
     * @param config          rendering configuration with provider info, metadata, and SLA thresholds
     * @param timeBuckets     global time-series data for performance charts (may be empty)
     * @param perLabelBuckets per-label time-series data for page filter (may be empty)
     * @param metricsTable    table data: element 0 = column headers, elements 1..n = data rows (may be empty)
     * @return complete HTML string ready to write to a file
     */
    public static String render(String markdown, RenderConfig config,
                                List<BpmTimeBucket> timeBuckets,
                                Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                List<String[]> metricsTable) {
        Objects.requireNonNull(markdown, "markdown must not be null");
        List<Extension> extensions = List.of(TablesExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder().extensions(extensions).build();
        String htmlContent = postProcessVerdicts(renderer.render(document));

        List<String> headings = extractH2Headings(htmlContent);
        boolean hasMetrics = metricsTable != null && metricsTable.size() > 1;
        boolean hasCharts = timeBuckets != null && !timeBuckets.isEmpty();
        boolean hasSla = config.slaScoreGood > 0 || config.slaScorePoor > 0;

        // Build panel order: Executive Summary → Metrics → Trends → SLA → Critical Findings → remaining AI panels
        List<String> panelHeadings = new ArrayList<>();
        if (!headings.isEmpty()) {
            panelHeadings.add(headings.get(0)); // Executive Summary (first AI panel)
        }
        if (hasMetrics) panelHeadings.add("Performance Metrics");
        if (hasCharts) panelHeadings.add("Performance Trends");
        if (hasSla) panelHeadings.add("SLA Compliance");
        if (hasMetrics) panelHeadings.add("Critical Findings");
        // Add remaining AI panels (skip first which is already added)
        for (int i = 1; i < headings.size(); i++) {
            panelHeadings.add(headings.get(i));
        }

        // Split content into panels by H2
        List<String[]> panels = splitIntoPanels(htmlContent, headings);

        return buildHtmlPage(panels, panelHeadings, config,
                hasMetrics, metricsTable, hasCharts, timeBuckets, perLabelBuckets);
    }

    // ── Critical Findings panel (Java-generated) ─────────────────────────────

    private static List<String> extractH2Headings(String html) {
        List<String> headings = new ArrayList<>();
        Matcher m = H2_PATTERN.matcher(html);
        while (m.find()) {
            headings.add(m.group(1));
        }
        return headings;
    }

    /**
     * Splits HTML content by H2 headings into title+body pairs.
     * Content before the first H2 is included in a "Summary" panel.
     */
    private static List<String[]> splitIntoPanels(String html, List<String> headings) {
        List<String[]> panels = new ArrayList<>();
        if (headings.isEmpty()) {
            panels.add(new String[]{"Report", html});
            return panels;
        }

        // Content before first H2
        int firstH2Pos = html.indexOf("<h2>");
        if (firstH2Pos > 0) {
            String preamble = html.substring(0, firstH2Pos).trim();
            if (!preamble.isEmpty()) {
                panels.add(new String[]{"Summary", preamble});
            }
        }

        // Split by H2 boundaries
        for (int i = 0; i < headings.size(); i++) {
            String heading = headings.get(i);
            String h2Tag = "<h2>" + heading + "</h2>";
            int start = html.indexOf(h2Tag);
            if (start < 0) continue;

            int contentStart = start + h2Tag.length();
            int end;
            if (i + 1 < headings.size()) {
                end = html.indexOf("<h2>" + headings.get(i + 1) + "</h2>");
                if (end < 0) end = html.length();
            } else {
                end = html.length();
            }

            String body = html.substring(contentStart, end).trim();
            panels.add(new String[]{heading, body});
        }

        return panels;
    }

    private static String buildHtmlPage(List<String[]> panels, List<String> panelHeadings,
                                        RenderConfig config,
                                        boolean hasMetrics, List<String[]> metricsTable,
                                        boolean hasCharts,
                                        List<BpmTimeBucket> timeBuckets,
                                        Map<String, List<BpmTimeBucket>> perLabelBuckets) {
        StringBuilder sb = new StringBuilder();

        // DOCTYPE + head
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("  <meta charset=\"UTF-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("  <title>Browser Performance Metrics Report</title>\n");
        sb.append("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js\"></script>\n");
        sb.append("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.mini.min.js\"></script>\n");
        sb.append(CSS);
        appendMetaScript(sb, config);
        sb.append("</head>\n<body>\n<div class=\"rpt\">\n");

        // Header
        appendHeader(sb, config);

        // Body row: sidebar + main
        sb.append("  <div class=\"body-row\">\n");
        appendSidebar(sb, panelHeadings);

        sb.append("    <div class=\"main-col\">\n");
        sb.append("      <div class=\"content-area\">\n");

        // Build lookup map for AI panels by title
        Map<String, String> aiPanelContent = new LinkedHashMap<>();
        for (String[] panel : panels) {
            aiPanelContent.put(panel[0], panel[1]);
        }
        boolean hasSla = panelHeadings.contains("SLA Compliance");

        // Render panels in panelHeadings order (interleaved AI + Java panels)
        int panelIndex = 0;
        boolean firstAiPanel = true;
        for (String heading : panelHeadings) {
            String activeClass = panelIndex == 0 ? " active" : "";
            sb.append("<div class=\"panel").append(activeClass).append("\" id=\"panel-")
                    .append(panelIndex).append("\" data-title=\"")
                    .append(escapeHtml(heading)).append("\">\n");

            if ("Performance Metrics".equals(heading) && hasMetrics) {
                appendMetricsPanel(sb, config, metricsTable);
            } else if ("Performance Trends".equals(heading) && hasCharts) {
                appendChartsPanel(sb, config, timeBuckets, perLabelBuckets);
            } else if ("SLA Compliance".equals(heading) && hasSla) {
                appendSlaPanel(sb, config, metricsTable);
            } else if ("Critical Findings".equals(heading) && hasMetrics) {
                appendCriticalFindingsPanel(sb, config, metricsTable);
            } else if (aiPanelContent.containsKey(heading)) {
                sb.append("<h2>").append(escapeHtml(heading)).append("</h2>\n");
                if (firstAiPanel) {
                    appendMetadataKpi(sb, config, metricsTable);
                    if (config.wasLabelTruncated()) {
                        sb.append("<div style=\"background:var(--color-warning-bg, #fff3cd);")
                                .append("border:1px solid var(--color-warning-border, #ffc107);")
                                .append("border-radius:6px;padding:10px 14px;margin:10px 0;")
                                .append("font-size:13px;color:var(--color-warning-text, #664d03)\">\n")
                                .append("<strong>\u26A0 Note:</strong> This test included ")
                                .append(config.totalLabels)
                                .append(" transactions. AI analysis covers the ")
                                .append(config.includedLabels)
                                .append(" most critical. For best results, keep transactions under 20 ")
                                .append("or use filters to narrow the scope.")
                                .append("</div>\n");
                    }
                    firstAiPanel = false;
                }
                sb.append(aiPanelContent.get(heading)).append("\n");
            }

            sb.append("</div>\n");
            panelIndex++;
        }

        sb.append("      </div>\n"); // content-area
        sb.append("    </div>\n");   // main-col
        sb.append("  </div>\n");     // body-row

        // Footer
        String timestamp = LocalDateTime.now().format(FOOTER_TIME_FMT);
        sb.append("  <div class=\"footer-rpt\">Generated by BPM Plugin on ")
                .append(escapeHtml(timestamp)).append(" using ")
                .append(escapeHtml(config.providerName)).append("</div>\n");
        sb.append("</div>\n"); // rpt

        // Scripts
        appendPanelScript(sb);
        appendTableScript(sb);
        appendSearchScript(sb);
        appendExcelScript(sb);

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, RenderConfig config) {
        sb.append("  <div class=\"rpt-header\">\n    <div class=\"header-left\">\n");
        sb.append("      <h1>Browser Performance Metrics Report</h1>\n");
        sb.append("      <div class=\"sub\">Generated by BPM Plugin using ")
                .append(escapeHtml(config.providerName))
                .append("&ensp;|&ensp;\u26A0 AI-Generated Analysis \u2014 Validate Before Use.</div>\n");

        // Meta-grid layout for metadata
        sb.append("      <div id=\"hdr-meta\">\n");
        List<String[]> metaRows = new ArrayList<>();
        if (!config.scenarioName.isBlank()) metaRows.add(new String[]{"Scenario Name", config.scenarioName});
        if (!config.virtualUsers.isBlank()) metaRows.add(new String[]{"Virtual Users", config.virtualUsers});
        if (!config.runDateTime.isBlank()) metaRows.add(new String[]{"Run Date/Time", config.runDateTime});
        if (!config.duration.isBlank()) metaRows.add(new String[]{"Duration", config.duration});
        if (!metaRows.isEmpty()) {
            sb.append("        <div class=\"meta-grid\" style=\"margin-top:12px\">\n");
            for (String[] row : metaRows) {
                sb.append("          <span class=\"ml\">").append(escapeHtml(row[0])).append("</span>")
                        .append("<span class=\"mv\">").append(escapeHtml(row[1])).append("</span>\n");
            }
            sb.append("        </div>\n");
        }
        sb.append("      </div>\n");

        sb.append("    </div>\n");
        sb.append("    <div class=\"header-actions\">\n");
        sb.append("      <button class=\"exp-btn\" onclick=\"exportExcel()\">&#x1F4E5;&nbsp; Export Excel</button>\n");
        sb.append("      <button class=\"exp-btn\" onclick=\"window.print()\">&#x1F5A8;&nbsp; Print / PDF</button>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
    }

    private static void appendSidebar(StringBuilder sb, List<String> panelHeadings) {
        sb.append("    <nav class=\"sidebar\">\n");
        for (int i = 0; i < panelHeadings.size(); i++) {
            String activeClass = i == 0 ? " active" : "";
            String heading = panelHeadings.get(i);
            String icon = SIDEBAR_ICONS.getOrDefault(heading, "");
            String prefix = icon.isEmpty() ? "" : icon + " ";
            sb.append("  <button class=\"nav-item").append(activeClass)
                    .append("\" data-panel=\"panel-").append(i).append("\">")
                    .append(prefix).append(escapeHtml(heading)).append("</button>\n");
        }
        sb.append("    </nav>\n");
    }

    private static void appendMetadataKpi(StringBuilder sb, RenderConfig config,
                                          List<String[]> metricsTable) {
        // Compute KPI stats from metrics table
        int totalLabels = 0;
        int passCount = 0;
        int worstScore = Integer.MAX_VALUE;
        int bestScore = Integer.MIN_VALUE;
        long weightedScoreSum = 0;
        long weightedScoreSamples = 0;
        long weightedLcpSum = 0;
        long weightedLcpSamples = 0;
        int totalErrors = 0;

        if (metricsTable != null) {
            for (int r = 1; r < metricsTable.size(); r++) {
                String[] row = metricsTable.get(r);
                if (row.length == 0 || "TOTAL".equals(row[0])) continue;
                totalLabels++;

                // Score + SLA check (must match SLA Compliance panel logic)
                String scoreVal = col(row, BpmConstants.COL_IDX_SCORE);
                boolean allGood = true;
                // Parse sample count for weighting
                String samplesVal = col(row, BpmConstants.COL_IDX_SAMPLES);
                int samples = 1;
                try {
                    if (!samplesVal.isEmpty() && !"\u2014".equals(samplesVal)) {
                        samples = Math.max(1, Integer.parseInt(samplesVal.trim()));
                    }
                } catch (NumberFormatException ignored) {
                }

                if (!"\u2014".equals(scoreVal) && !scoreVal.isEmpty()) {
                    try {
                        int score = Integer.parseInt(scoreVal.trim());
                        if (score < config.slaScoreGood) allGood = false;
                        if (score < worstScore) worstScore = score;
                        if (score > bestScore) bestScore = score;
                        weightedScoreSum += (long) score * samples;
                        weightedScoreSamples += samples;
                    } catch (NumberFormatException ignored) {
                    }
                }

                // Weighted LCP (skip SPA/null/zero LCP)
                String lcpVal = col(row, BpmConstants.COL_IDX_LCP);
                if (!lcpVal.isEmpty() && !"\u2014".equals(lcpVal)) {
                    try {
                        long lcp = Long.parseLong(lcpVal.trim());
                        if (lcp > 0) {
                            weightedLcpSum += lcp * samples;
                            weightedLcpSamples += samples;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                // Check LCP, FCP, TTFB, CLS for all labels (including SPA with null score)
                if (allGood) {
                    String lcpV = computeVerdict(2, col(row, BpmConstants.COL_IDX_LCP), config);
                    String fcpV = computeVerdict(3, col(row, BpmConstants.COL_IDX_FCP), config);
                    String ttfbV = computeVerdict(4, col(row, BpmConstants.COL_IDX_TTFB), config);
                    String clsV = computeVerdict(5, col(row, BpmConstants.COL_IDX_CLS), config);
                    if (!"GOOD".equals(lcpV) && !"N/A".equals(lcpV)) allGood = false;
                    if (!"GOOD".equals(fcpV) && !"N/A".equals(fcpV)) allGood = false;
                    if (!"GOOD".equals(ttfbV) && !"N/A".equals(ttfbV)) allGood = false;
                    if (!"GOOD".equals(clsV) && !"N/A".equals(clsV)) allGood = false;
                }
                if (allGood) passCount++;

                // Errors
                String errVal = col(row, BpmConstants.COL_IDX_ERRS);
                if (!errVal.isEmpty()) {
                    try {
                        totalErrors += Integer.parseInt(errVal.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        int failCount = totalLabels - passCount;
        String overallCss = failCount == 0 ? "pass" : "fail";
        String overallText = failCount == 0 ? "All Pass"
                : failCount + " of " + totalLabels + " Need Attention";

        sb.append("    <div class=\"metadata-grid kpi-grid\">\n");

        // Card 1: Overall verdict
        String verdictTooltip = failCount == 0
                ? "All pages meet SLA targets"
                : "See SLA Compliance panel for details";
        sb.append("      <div class=\"kpi\" title=\"").append(escapeHtml(verdictTooltip))
                .append("\"><div class=\"kpi-label\">Overall Verdict</div>")
                .append("<div class=\"kpi-value ").append(overallCss).append("\">")
                .append(escapeHtml(overallText)).append("</div></div>\n");

        // Card 2: Performance Score (Low / High / Weighted)
        sb.append("      <div class=\"kpi\" title=\"Low / High / Weighted score across all pages (weighted by sample count)\">");
        sb.append("<div class=\"kpi-label\">Performance Score</div>");
        if (worstScore < Integer.MAX_VALUE) {
            int weightedScore = (int) Math.round((double) weightedScoreSum / weightedScoreSamples);
            sb.append("<div class=\"kpi-value\" style=\"display:flex;gap:6px;justify-content:center;align-items:baseline\">");
            appendColoredScore(sb, worstScore, config);
            sb.append("<span style=\"color:var(--color-text-tertiary);font-size:16px\">/</span>");
            appendColoredScore(sb, bestScore, config);
            sb.append("<span style=\"color:var(--color-text-tertiary);font-size:16px\">/</span>");
            appendColoredScore(sb, weightedScore, config);
            sb.append("</div>");
            sb.append("<div style=\"display:flex;gap:6px;justify-content:center;font-size:10px;color:var(--color-text-tertiary);margin-top:2px\">");
            sb.append("<span style=\"min-width:28px;text-align:center\">Low</span>");
            sb.append("<span style=\"min-width:16px\"></span>");
            sb.append("<span style=\"min-width:28px;text-align:center\">High</span>");
            sb.append("<span style=\"min-width:16px\"></span>");
            sb.append("<span style=\"min-width:28px;text-align:center\">Wtd</span>");
            sb.append("</div>");
        } else {
            sb.append("<div class=\"kpi-value\">N/A</div>");
        }
        sb.append("</div>\n");

        // Card 3: Avg Load Time (weighted LCP)
        sb.append("      <div class=\"kpi\" title=\"Weighted average LCP across all pages (weighted by sample count). SPA navigations excluded.\">");
        sb.append("<div class=\"kpi-label\">Avg Load Time</div>");
        if (weightedLcpSamples > 0) {
            long weightedLcp = Math.round((double) weightedLcpSum / weightedLcpSamples);
            String lcpCss = weightedLcp <= config.slaLcpGood ? "pass"
                    : weightedLcp <= config.slaLcpPoor ? "" : "fail";
            String display = weightedLcp >= 1000
                    ? String.format("%.1fs", weightedLcp / 1000.0)
                    : weightedLcp + "ms";
            sb.append("<div class=\"kpi-value ").append(lcpCss).append("\">")
                    .append(display).append("</div>");
        } else {
            sb.append("<div class=\"kpi-value\">N/A</div>");
        }
        sb.append("</div>\n");

        sb.append("    </div>\n");
    }

    private static void appendColoredScore(StringBuilder sb, int score, RenderConfig config) {
        String css = score >= config.slaScoreGood ? "color:#276749;font-weight:700"
                : score >= config.slaScorePoor ? "color:var(--color-text-primary);font-weight:700"
                  : "color:#c53030;font-weight:700";
        sb.append("<span style=\"").append(css).append("\">").append(score).append("</span>");
    }

    private static void appendMetricsPanel(StringBuilder sb, RenderConfig config,
                                           List<String[]> metricsTable) {
        sb.append("<h2>Performance Metrics</h2>\n");
        sb.append("<div class=\"tbl-search\"><input type=\"text\" id=\"metricsSearch\" ")
                .append("placeholder=\"Search transactions\u2026\" autocomplete=\"off\"></div>\n");
        appendPaginatedTable(sb, config, metricsTable, "metrics");
        appendMetricDescriptions(sb);
    }

    private static void appendSlaPanel(StringBuilder sb, RenderConfig config,
                                       List<String[]> metricsTable) {
        sb.append("<h2>SLA Compliance</h2>\n");
        sb.append("<div class=\"tbl-search\"><input type=\"text\" id=\"slaSearch\" ")
                .append("placeholder=\"Search transactions\u2026\" autocomplete=\"off\"></div>\n");

        // SLA columns: Page | Score | Page Load (LCP) | First Paint (FCP) | Server Response (TTFB) | Visual Stability (CLS)
        String[] slaHeaders = {"Transaction Name", "Score", "Page Load", "First Paint", "Server Response", "Visual Stability"};
        int[] srcCols = {BpmConstants.COL_IDX_LABEL, BpmConstants.COL_IDX_SCORE,
                BpmConstants.COL_IDX_LCP, BpmConstants.COL_IDX_FCP,
                BpmConstants.COL_IDX_TTFB, BpmConstants.COL_IDX_CLS};

        // Build SLA rows: each cell = "rawValue|verdict"
        List<String[]> slaRows = new ArrayList<>();
        int passCount = 0;
        int totalLabels = 0;
        for (int r = 1; r < metricsTable.size(); r++) {
            String[] srcRow = metricsTable.get(r);
            if (srcRow.length == 0 || "TOTAL".equals(srcRow[0])) continue;
            totalLabels++;

            String[] slaRow = new String[6];
            slaRow[0] = srcRow[0]; // label
            boolean allPass = true;
            for (int c = 1; c < 6; c++) {
                int srcIdx = srcCols[c];
                String rawVal = col(srcRow, srcIdx);
                String verdict = computeVerdict(c, rawVal, config);
                slaRow[c] = rawVal + "|" + verdict;
                if (!"GOOD".equals(verdict) && !"N/A".equals(verdict)) allPass = false;
            }
            if (allPass) passCount++;
            slaRows.add(slaRow);
        }

        // Summary line
        if (passCount == totalLabels && totalLabels > 0) {
            sb.append("<p class=\"sla-summary sla-pass-summary\">All ").append(totalLabels)
                    .append(" transactions pass all performance targets.</p>\n");
        } else {
            int failCount = totalLabels - passCount;
            sb.append("<p class=\"sla-summary sla-fail-summary\">").append(failCount)
                    .append(" of ").append(totalLabels).append(" transactions need attention.</p>\n");
        }

        // Paginated table
        sb.append("<div class=\"paginated-tbl\" data-table-id=\"sla\">\n");
        sb.append("<div class=\"tbl-controls\"><label>Show:&nbsp;</label><select class=\"row-limit\" data-for=\"sla\">\n");
        for (int v : new int[]{10, 25, 50, 100}) {
            sb.append("  <option value=\"").append(v).append("\"")
                    .append(v == 10 ? " selected" : "").append(">").append(v).append("</option>\n");
        }
        sb.append("</select></div>\n");
        sb.append("<div class=\"pager\" data-for=\"sla\"></div>\n");
        sb.append("<div class=\"tbl-wrap tbl-scroll\" data-scroll-id=\"sla\">\n<table>\n");

        // Header with tooltips
        sb.append("<thead><tr>\n");
        for (int i = 0; i < slaHeaders.length; i++) {
            sb.append("  <th");
            if (i < SLA_HEADER_TOOLTIPS.length) {
                sb.append(" title=\"").append(escapeHtml(SLA_HEADER_TOOLTIPS[i])).append("\"");
            }
            sb.append(">").append(escapeHtml(slaHeaders[i])).append("</th>\n");
        }
        sb.append("</tr></thead>\n<tbody class=\"paginated-body\" data-body-id=\"sla\">\n");

        // Data rows sorted by label
        slaRows.sort(Comparator.comparing(r -> r[0].toLowerCase(Locale.ROOT)));
        for (String[] row : slaRows) {
            sb.append("<tr>\n");
            sb.append("  <td class=\"label-cell\">").append(escapeHtml(row[0])).append("</td>\n");
            for (int c = 1; c < row.length; c++) {
                String[] parts = row[c].split("\\|", 2);
                String rawVal = parts[0];
                String verdict = parts.length > 1 ? parts[1] : "N/A";
                String displayVerdict;
                String css;
                switch (verdict) {
                    case "GOOD" -> {
                        displayVerdict = "Pass";
                        css = "sla-pass";
                    }
                    case "NEEDS_WORK" -> {
                        displayVerdict = "Warning";
                        css = "sla-warn";
                    }
                    case "POOR" -> {
                        displayVerdict = "Fail";
                        css = "sla-fail";
                    }
                    default -> {
                        displayVerdict = null;
                        css = "sla-na";
                    }
                }
                String display;
                if (displayVerdict == null) {
                    display = "-";
                } else {
                    String unit = c < SLA_UNITS.length ? SLA_UNITS[c] : "";
                    String val = "\u2014".equals(rawVal) ? "-" : rawVal + unit;
                    display = val + " (" + displayVerdict + ")";
                }
                sb.append("  <td class=\"").append(css).append("\">")
                        .append(escapeHtml(display)).append("</td>\n");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n</div>\n</div>\n");

        // Compact threshold reference
        sb.append("<div class=\"sla-thresholds\">\n");
        sb.append("<strong>SLA Thresholds:</strong> ");
        sb.append("Score: \u2265").append(config.slaScoreGood).append(" Good, \u2265").append(config.slaScorePoor).append(" Warning");
        sb.append(" | LCP: \u2264").append(config.slaLcpGood).append("ms Good, \u2264").append(config.slaLcpPoor).append("ms Warning");
        sb.append(" | FCP: \u2264").append(config.slaFcpGood).append("ms Good, \u2264").append(config.slaFcpPoor).append("ms Warning");
        sb.append(" | TTFB: \u2264").append(config.slaTtfbGood).append("ms Good, \u2264").append(config.slaTtfbPoor).append("ms Warning");
        sb.append(" | CLS: \u2264").append(String.format(Locale.US, "%.2f", config.slaClsGood))
                .append(" Good, \u2264").append(String.format(Locale.US, "%.2f", config.slaClsPoor)).append(" Warning");
        sb.append("<br><em>Configure in: &lt;JMETER_HOME&gt;/bin/bpm.properties (sla.score.good, sla.lcp.poor, etc.)</em>\n");
        sb.append("</div>\n");
    }

    private static String computeVerdict(int slaCol, String rawVal, RenderConfig config) {
        if (rawVal == null || rawVal.isEmpty() || "\u2014".equals(rawVal)) return "N/A";
        try {
            return switch (slaCol) {
                case 1 -> { // Score (higher is better)
                    int v = Integer.parseInt(rawVal.trim());
                    yield v >= config.slaScoreGood ? "GOOD" : v >= config.slaScorePoor ? "NEEDS_WORK" : "POOR";
                }
                case 2 -> { // LCP ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield "N/A";
                    yield v <= config.slaLcpGood ? "GOOD" : v <= config.slaLcpPoor ? "NEEDS_WORK" : "POOR";
                }
                case 3 -> { // FCP ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield "N/A";
                    yield v <= config.slaFcpGood ? "GOOD" : v <= config.slaFcpPoor ? "NEEDS_WORK" : "POOR";
                }
                case 4 -> { // TTFB ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield "N/A";
                    yield v <= config.slaTtfbGood ? "GOOD" : v <= config.slaTtfbPoor ? "NEEDS_WORK" : "POOR";
                }
                case 5 -> { // CLS (lower is better)
                    double v = Double.parseDouble(rawVal.trim());
                    yield v <= config.slaClsGood ? "GOOD" : v <= config.slaClsPoor ? "NEEDS_WORK" : "POOR";
                }
                default -> "N/A";
            };
        } catch (NumberFormatException e) {
            return "N/A";
        }
    }

    private static void appendCriticalFindingsPanel(StringBuilder sb, RenderConfig config,
                                                    List<String[]> metricsTable) {
        sb.append("<h2>Critical Findings</h2>\n");

        List<String[]> findings = new ArrayList<>();
        for (int r = 1; r < metricsTable.size(); r++) {
            String[] row = metricsTable.get(r);
            if (row.length == 0 || "TOTAL".equals(row[0])) continue;

            String scoreVal = col(row, BpmConstants.COL_IDX_SCORE);
            String lcpVal = col(row, BpmConstants.COL_IDX_LCP);
            String fcpVal = col(row, BpmConstants.COL_IDX_FCP);
            String ttfbVal = col(row, BpmConstants.COL_IDX_TTFB);
            String clsVal = col(row, BpmConstants.COL_IDX_CLS);

            // Compute verdicts for the 5 SLA metrics (slaCol indices: 1=Score, 2=LCP, 3=FCP, 4=TTFB, 5=CLS)
            String scoreVerdict = computeVerdict(1, scoreVal, config);
            String lcpVerdict = computeVerdict(2, lcpVal, config);
            String fcpVerdict = computeVerdict(3, fcpVal, config);
            String ttfbVerdict = computeVerdict(4, ttfbVal, config);
            String clsVerdict = computeVerdict(5, clsVal, config);

            String[] verdicts = {scoreVerdict, lcpVerdict, fcpVerdict, ttfbVerdict, clsVerdict};
            boolean hasPoor = Arrays.stream(verdicts).anyMatch("POOR"::equals);
            boolean hasWarn = Arrays.stream(verdicts).anyMatch("NEEDS_WORK"::equals);

            if (!hasPoor && !hasWarn) continue;

            String severity = hasPoor ? "\uD83D\uDD34 Critical" : "\uD83D\uDFE1 Warning";
            String severityCss = hasPoor ? "sla-fail" : "sla-warn";

            // Build issue list
            List<String> issues = new ArrayList<>();
            addIssue(issues, "Score", scoreVal, "", scoreVerdict);
            addIssue(issues, "LCP", lcpVal, "ms", lcpVerdict);
            addIssue(issues, "FCP", fcpVal, "ms", fcpVerdict);
            addIssue(issues, "TTFB", ttfbVal, "ms", ttfbVerdict);
            addIssue(issues, "CLS", clsVal, "", clsVerdict);
            String issueText = String.join("; ", issues);

            // Root cause from improvementArea
            String area = col(row, BpmConstants.COL_IDX_IMPROVEMENT_AREA);
            String rootCause = ROOT_CAUSE_MAP.getOrDefault(area, area);

            // User impact from worst metric
            String impact = buildImpact(verdicts,
                    new String[]{scoreVal, lcpVal, fcpVal, ttfbVal, clsVal});

            // Recommended action
            String action = ACTION_MAP.getOrDefault(area, "Monitor for regression");

            findings.add(new String[]{row[0], severity, severityCss, issueText, rootCause, impact, action});
        }

        if (findings.isEmpty()) {
            sb.append("<p class=\"sla-summary sla-pass-summary\">No critical issues detected. ")
                    .append("All transactions are within acceptable thresholds.</p>\n");
            return;
        }

        // Sort by label
        findings.sort(Comparator.comparing(r -> r[0].toLowerCase(Locale.ROOT)));

        // Count summary
        long critCount = findings.stream().filter(f -> f[2].equals("sla-fail")).count();
        long warnCount = findings.size() - critCount;
        sb.append("<p class=\"sla-summary sla-fail-summary\">");
        if (critCount > 0) sb.append(critCount).append(" critical");
        if (critCount > 0 && warnCount > 0) sb.append(", ");
        if (warnCount > 0) sb.append(warnCount).append(" warning");
        sb.append(" finding").append(findings.size() > 1 ? "s" : "")
                .append(" across ").append(findings.size()).append(" transactions.</p>\n");

        // Bottleneck summary — group findings by improvementArea
        Map<String, List<String>> bottleneckGroups = new LinkedHashMap<>();
        Map<String, Boolean> bottleneckHasCritical = new LinkedHashMap<>();
        for (String[] f : findings) {
            // f[4] = rootCause, but we need the original area for grouping
            // Reverse-lookup from rootCause to area key
            String area = ROOT_CAUSE_MAP.entrySet().stream()
                    .filter(e -> e.getValue().equals(f[4]))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(f[4]);
            bottleneckGroups.computeIfAbsent(area, k -> new ArrayList<>()).add(f[0]);
            if ("sla-fail".equals(f[2])) {
                bottleneckHasCritical.put(area, true);
            } else {
                bottleneckHasCritical.putIfAbsent(area, false);
            }
        }
        if (!bottleneckGroups.isEmpty()) {
            sb.append("<div style=\"margin-bottom:16px;padding:12px 16px;background:var(--color-background-secondary);")
                    .append("border:1px solid var(--color-border-tertiary);border-radius:6px;font-size:12px\">\n");
            sb.append("<strong>").append(bottleneckGroups.size())
                    .append(" bottleneck area").append(bottleneckGroups.size() > 1 ? "s" : "")
                    .append(" detected:</strong> ");
            List<String> summaryParts = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : bottleneckGroups.entrySet()) {
                String priority = Boolean.TRUE.equals(bottleneckHasCritical.get(entry.getKey()))
                        ? "High" : "Medium";
                summaryParts.add("<strong>" + escapeHtml(entry.getKey()) + "</strong> ("
                        + entry.getValue().size() + " transaction"
                        + (entry.getValue().size() > 1 ? "s" : "") + ", " + priority + ")");
            }
            sb.append(String.join(" &nbsp;|&nbsp; ", summaryParts));
            sb.append("\n</div>\n");
        }

        // Paginated table
        sb.append("<div class=\"paginated-tbl\" data-table-id=\"cf\">\n");
        sb.append("<div class=\"tbl-controls\"><label>Show:&nbsp;</label><select class=\"row-limit\" data-for=\"cf\">\n");
        for (int v : new int[]{10, 25, 50, 100}) {
            sb.append("  <option value=\"").append(v).append("\"")
                    .append(v == 10 ? " selected" : "").append(">").append(v).append("</option>\n");
        }
        sb.append("</select></div>\n");
        sb.append("<div class=\"pager\" data-for=\"cf\"></div>\n");
        sb.append("<div class=\"tbl-wrap tbl-scroll\" data-scroll-id=\"cf\">\n<table>\n");

        // Header with tooltips
        sb.append("<thead><tr>\n");
        for (int i = 0; i < CF_HEADERS.length; i++) {
            sb.append("  <th");
            if (i < CF_HEADER_TOOLTIPS.length) {
                sb.append(" title=\"").append(escapeHtml(CF_HEADER_TOOLTIPS[i])).append("\"");
            }
            sb.append(">").append(escapeHtml(CF_HEADERS[i])).append("</th>\n");
        }
        sb.append("</tr></thead>\n<tbody class=\"paginated-body\" data-body-id=\"cf\">\n");

        for (String[] f : findings) {
            sb.append("<tr>\n");
            sb.append("  <td class=\"label-cell\">").append(escapeHtml(f[0])).append("</td>\n");
            sb.append("  <td class=\"").append(f[2]).append("\">").append(escapeHtml(f[1])).append("</td>\n");
            sb.append("  <td>").append(escapeHtml(f[3])).append("</td>\n");
            sb.append("  <td>").append(escapeHtml(f[4])).append("</td>\n");
            sb.append("  <td>").append(escapeHtml(f[5])).append("</td>\n");
            sb.append("  <td>").append(escapeHtml(f[6])).append("</td>\n");
            sb.append("</tr>\n");
        }

        sb.append("</tbody>\n</table>\n</div>\n</div>\n");
    }

    // ── Paginated table helper ──────────────────────────────────────────────

    private static String col(String[] row, int idx) {
        return idx < row.length ? row[idx] : "";
    }

    private static void addIssue(List<String> issues, String metric, String value,
                                 String unit, String verdict) {
        if ("POOR".equals(verdict)) {
            issues.add(metric + " " + value + unit + " (Fail)");
        } else if ("NEEDS_WORK".equals(verdict)) {
            issues.add(metric + " " + value + unit + " (Warning)");
        }
    }

    private static String buildImpact(String[] verdicts, String[] values) {
        // Pick the worst metric to describe impact (prefer POOR over NEEDS_WORK)
        for (String target : new String[]{"POOR", "NEEDS_WORK"}) {
            for (int i = 0; i < verdicts.length; i++) {
                if (target.equals(verdicts[i]) && !values[i].isEmpty()) {
                    return String.format(IMPACT_TEMPLATES.get(IMPACT_METRIC_NAMES[i]), values[i]);
                }
            }
        }
        return "Performance below target";
    }

    private static void appendPaginatedTable(StringBuilder sb, RenderConfig config,
                                             List<String[]> table, String tableId) {
        sb.append("<div class=\"paginated-tbl\" data-table-id=\"").append(tableId).append("\">\n");
        sb.append("<div class=\"tbl-controls\"><label>Show:&nbsp;</label><select class=\"row-limit\" data-for=\"").append(tableId).append("\">\n");
        for (int v : new int[]{10, 25, 50, 100}) {
            sb.append("  <option value=\"").append(v).append("\"")
                    .append(v == 10 ? " selected" : "").append(">").append(v).append("</option>\n");
        }
        sb.append("</select></div>\n");
        sb.append("<div class=\"pager\" data-for=\"").append(tableId).append("\"></div>\n");
        sb.append("<div class=\"tbl-wrap tbl-scroll\" data-scroll-id=\"").append(tableId).append("\">\n<table>\n");

        // Header row with tooltips
        String[] headers = table.get(0);
        sb.append("<thead><tr>\n");
        for (int i = 0; i < headers.length; i++) {
            String headerText = (i == 0 && "Label".equals(headers[i])) ? "Transaction Name" : headers[i];
            sb.append("  <th");
            if (i < METRICS_HEADER_TOOLTIPS.length) {
                sb.append(" title=\"").append(escapeHtml(METRICS_HEADER_TOOLTIPS[i])).append("\"");
            }
            sb.append(">").append(escapeHtml(headerText)).append("</th>\n");
        }
        sb.append("</tr></thead>\n<tbody class=\"paginated-body\" data-body-id=\"").append(tableId).append("\">\n");

        // Data rows only (skip TOTAL), sorted by label
        List<String[]> dataRows = new ArrayList<>();
        for (int r = 1; r < table.size(); r++) {
            String[] row = table.get(r);
            if (row.length > 0 && "TOTAL".equals(row[0])) continue;
            dataRows.add(row);
        }
        dataRows.sort(Comparator.comparing(r -> r[0].toLowerCase(Locale.ROOT)));

        // Improvement Area column index = 9
        for (String[] row : dataRows) {
            sb.append("<tr>\n");
            for (int c = 0; c < row.length; c++) {
                String cell = row[c] != null ? row[c] : "";
                String display = "\u2014".equals(cell) ? "-"
                        : (c == BpmConstants.COL_IDX_IMPROVEMENT_AREA && "None".equals(cell)) ? "-" : cell;
                String css = getCellCssClass(c, cell, config, false);
                sb.append("  <td");
                if (!css.isEmpty()) sb.append(" class=\"").append(css).append("\"");
                // Tooltip on Improvement Area cells
                if (c == BpmConstants.COL_IDX_IMPROVEMENT_AREA && IMPROVEMENT_AREA_TOOLTIPS.containsKey(cell)) {
                    sb.append(" title=\"").append(escapeHtml(IMPROVEMENT_AREA_TOOLTIPS.get(cell))).append("\"");
                }
                sb.append(">").append(escapeHtml(display)).append("</td>\n");
            }
            sb.append("</tr>\n");
        }

        sb.append("</tbody>\n</table>\n</div>\n</div>\n");
    }

    private static String getCellCssClass(int col, String value, RenderConfig config, boolean isTotalRow) {
        if (isTotalRow) return "";
        if (col == BpmConstants.COL_IDX_LABEL) return "label-cell";
        try {
            if (col == BpmConstants.COL_IDX_SCORE) {
                if ("\u2014".equals(value)) return "sla-na";
                int v = Integer.parseInt(value.trim());
                return v >= config.slaScoreGood ? "sla-pass" : v >= config.slaScorePoor ? "sla-warn" : "sla-fail";
            }
            if (col == BpmConstants.COL_IDX_FCP) {
                long v = Long.parseLong(value.trim());
                return v == 0 ? "sla-na" : v <= config.slaFcpGood ? "sla-pass" : v <= config.slaFcpPoor ? "sla-warn" : "sla-fail";
            }
            if (col == BpmConstants.COL_IDX_LCP) {
                long v = Long.parseLong(value.trim());
                return v == 0 ? "sla-na" : v <= config.slaLcpGood ? "sla-pass" : v <= config.slaLcpPoor ? "sla-warn" : "sla-fail";
            }
            if (col == BpmConstants.COL_IDX_CLS) {
                if ("\u2014".equals(value)) return "sla-na";
                double v = Double.parseDouble(value.trim());
                return v <= config.slaClsGood ? "sla-pass" : v <= config.slaClsPoor ? "sla-warn" : "sla-fail";
            }
            if (col == BpmConstants.COL_IDX_TTFB) {
                long v = Long.parseLong(value.trim());
                return v == 0 ? "sla-na" : v <= config.slaTtfbGood ? "sla-pass" : v <= config.slaTtfbPoor ? "sla-warn" : "sla-fail";
            }
            if (col == BpmConstants.COL_IDX_STABILITY
                    || col == BpmConstants.COL_IDX_IMPROVEMENT_AREA) {
                return "";
            }
            return "num";
        } catch (NumberFormatException e) {
            return col == BpmConstants.COL_IDX_LABEL ? "label-cell" : "";
        }
    }

    // ── Charts panel ────────────────────────────────────────────────────────

    private static void appendMetricDescriptions(StringBuilder sb) {
        sb.append("<details class=\"metric-desc\">\n");
        sb.append("<summary style=\"cursor:pointer;font-size:12px;font-weight:600;color:var(--color-text-secondary)\">Column Descriptions</summary>\n");
        sb.append("<div style=\"display:grid;grid-template-columns:repeat(2,1fr);gap:2px 24px;margin-top:8px\">\n");
        sb.append("<div><strong>Score</strong> \u2014 Performance score (0\u2013100), higher is better</div>\n");
        sb.append("<div><strong>Rndr</strong> \u2014 Render Time: client rendering (LCP\u2212TTFB)</div>\n");
        sb.append("<div><strong>Srvr</strong> \u2014 Server Ratio: % of load time on server</div>\n");
        sb.append("<div><strong>Front</strong> \u2014 Frontend Time: browser processing (FCP\u2212TTFB)</div>\n");
        sb.append("<div><strong>Gap</strong> \u2014 FCP\u2013LCP Gap: delay to largest content</div>\n");
        sb.append("<div><strong>Stability</strong> \u2014 Layout stability category from CLS</div>\n");
        sb.append("<div><strong>Headroom</strong> \u2014 % of SLA budget remaining</div>\n");
        sb.append("<div><strong>Improvement Area</strong> \u2014 Bottleneck classification</div>\n");
        sb.append("</div>\n</details>\n");
    }

    private static void appendChartsPanel(StringBuilder sb, RenderConfig config,
                                          List<BpmTimeBucket> timeBuckets,
                                          Map<String, List<BpmTimeBucket>> perLabelBuckets) {
        sb.append("<div class=\"charts-section\">\n");
        sb.append("  <h2>Performance Trends</h2>\n");

        // Dynamic interval text
        String intervalText;
        if (config.intervalSeconds > 0) {
            intervalText = formatInterval(config.intervalSeconds);
        } else {
            intervalText = "one time-series bucket";
        }
        sb.append("  <p class=\"charts-note\">Each point represents a ")
                .append(escapeHtml(intervalText))
                .append(" interval.");
        boolean hasSlaLines = config.slaScoreGood > 0 || config.slaLcpPoor > 0
                || config.slaFcpPoor > 0 || config.slaTtfbPoor > 0 || config.slaClsPoor > 0;
        if (hasSlaLines) {
            sb.append(" Dashed line = SLA threshold.");
        }
        boolean hasPageFilter = perLabelBuckets != null && perLabelBuckets.size() > 1;
        if (hasPageFilter) {
            sb.append(" Select a transaction to view its individual metrics.");
        }
        sb.append("</p>\n");

        // Page filter dropdown
        if (hasPageFilter) {
            sb.append("  <div class=\"chart-filter\"><label for=\"pageFilter\">Transaction Name:&nbsp;</label>\n");
            sb.append("    <select id=\"pageFilter\"><option value=\"__all__\">All Transactions</option>\n");
            List<String> sortedLabels = new ArrayList<>(perLabelBuckets.keySet());
            Collections.sort(sortedLabels);
            for (String label : sortedLabels) {
                sb.append("      <option value=\"").append(escapeHtml(label)).append("\">")
                        .append(escapeHtml(label)).append("</option>\n");
            }
            sb.append("    </select></div>\n");
        }

        sb.append("  <div class=\"charts-grid\">\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"Overall performance score (0-100). Higher is better. Green dashed line = minimum target.\">Performance Score Over Time</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartScore\"></canvas></div></div>\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"Largest Contentful Paint \u2014 time until the main visible content renders. Lower is better. Red dashed line = SLA threshold.\">LCP Over Time (ms)</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartLcp\"></canvas></div></div>\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"First Contentful Paint \u2014 time until the first text or image appears. Lower is better. Red dashed line = SLA threshold.\">FCP Over Time (ms)</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartFcp\"></canvas></div></div>\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"Time To First Byte \u2014 server response time before any content arrives. Lower is better. Red dashed line = SLA threshold.\">TTFB Over Time (ms)</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartTtfb\"></canvas></div></div>\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"Cumulative Layout Shift \u2014 measures unexpected content movement during load. Lower is better. Red dashed line = SLA threshold.\">CLS Over Time</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartCls\"></canvas></div></div>\n");
        sb.append("  <div class=\"chart-box\"><h3 title=\"Client-side rendering duration (LCP \u2212 TTFB). Lower is better. No SLA threshold.\">Render Time Over Time (ms)</h3>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartRender\"></canvas></div></div>\n");
        sb.append("  </div>\n"); // charts-grid
        sb.append("</div>\n");   // charts-section

        // Build JS data — global dataset
        sb.append("<script>\n(function() {\n");
        appendBucketDataset(sb, "bpmAll", timeBuckets);

        // Per-label datasets
        if (hasPageFilter) {
            sb.append("  var bpmPages = {};\n");
            for (Map.Entry<String, List<BpmTimeBucket>> entry : perLabelBuckets.entrySet()) {
                String varSuffix = "p" + (entry.getKey().hashCode() & 0x7fffffff);
                appendBucketDataset(sb, varSuffix, entry.getValue());
                sb.append("  bpmPages['").append(escapeJs(entry.getKey()))
                        .append("'] = { labels: ").append(varSuffix).append("Labels, ")
                        .append("scores: ").append(varSuffix).append("Scores, ")
                        .append("lcp: ").append(varSuffix).append("Lcp, ")
                        .append("fcp: ").append(varSuffix).append("Fcp, ")
                        .append("ttfb: ").append(varSuffix).append("Ttfb, ")
                        .append("cls: ").append(varSuffix).append("Cls, ")
                        .append("render: ").append(varSuffix).append("Render };\n");
            }
        }

        // SLA thresholds — Score uses "good" (green, above=pass), others use "poor" (red, above=fail)
        sb.append("  var slaScoreGood = ").append(config.slaScoreGood).append(";\n");
        sb.append("  var slaLcp = ").append(config.slaLcpPoor).append(";\n");
        sb.append("  var slaFcp = ").append(config.slaFcpPoor).append(";\n");
        sb.append("  var slaTtfb = ").append(config.slaTtfbPoor).append(";\n");
        sb.append("  var slaCls = ").append(String.format(Locale.US, "%.2f", config.slaClsPoor)).append(";\n");

        // Chart creation function with optional SLA line (slaColor: 'green' or 'red')
        sb.append("  var charts = {};\n");
        sb.append("  function bpmChart(id, title, lbls, data, color, yLabel, slaVal, slaColor) {\n");
        sb.append("    var datasets = [{\n");
        sb.append("      label: title, data: data, borderColor: color,\n");
        sb.append("      backgroundColor: color.replace('1)', '0.10)'),\n");
        sb.append("      borderWidth: 2, pointRadius: 3, pointHoverRadius: 6,\n");
        sb.append("      fill: true, tension: 0.25, spanGaps: true\n");
        sb.append("    }];\n");
        sb.append("    if (slaVal > 0) {\n");
        sb.append("      var slaData = new Array(lbls.length).fill(slaVal);\n");
        sb.append("      var sc = slaColor === 'green' ? 'rgba(39,103,73,0.7)' : 'rgba(220,38,38,0.7)';\n");
        sb.append("      var slaLabel = slaColor === 'green' ? 'SLA Target' : 'SLA Threshold';\n");
        sb.append("      datasets.push({ label: slaLabel, data: slaData,\n");
        sb.append("        borderColor: sc, borderWidth: 1.5,\n");
        sb.append("        borderDash: [6, 4], pointRadius: 0, fill: false, tension: 0 });\n");
        sb.append("    }\n");
        sb.append("    charts[id] = new Chart(document.getElementById(id), {\n");
        sb.append("      type: 'line',\n");
        sb.append("      data: { labels: lbls, datasets: datasets },\n");
        sb.append("      options: {\n");
        sb.append("        responsive: true, maintainAspectRatio: false,\n");
        sb.append("        plugins: {\n");
        sb.append("          legend: { display: slaVal > 0, labels: { font: { size: 10 }, usePointStyle: true } },\n");
        sb.append("          tooltip: { callbacks: { label: function(ctx) {\n");
        sb.append("            if (ctx.parsed.y === null) return null;\n");
        sb.append("            return ' ' + ctx.parsed.y.toFixed(2) + ' ' + yLabel;\n");
        sb.append("          }}}\n");
        sb.append("        },\n");
        sb.append("        scales: {\n");
        sb.append("          x: { title: { display: true, text: 'Time', font: { size: 11 } },\n");
        sb.append("               ticks: { font: { size: 10 }, maxRotation: 45, autoSkip: true, maxTicksLimit: 12 },\n");
        sb.append("               grid: { color: 'rgba(0,0,0,0.04)' } },\n");
        sb.append("          y: { beginAtZero: true,\n");
        sb.append("               title: { display: true, text: yLabel, font: { size: 11 } },\n");
        sb.append("               ticks: { font: { size: 11 } },\n");
        sb.append("               grid: { color: 'rgba(0,0,0,0.04)' } }\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("  }\n");

        // Initialize charts with global data
        sb.append("  bpmChart('chartScore','Performance Score',bpmAllLabels,bpmAllScores,'rgba(37,99,235,1)','Score',slaScoreGood,'green');\n");
        sb.append("  bpmChart('chartLcp','LCP',bpmAllLabels,bpmAllLcp,'rgba(220,38,38,1)','ms',slaLcp,'red');\n");
        sb.append("  bpmChart('chartFcp','FCP',bpmAllLabels,bpmAllFcp,'rgba(22,163,74,1)','ms',slaFcp,'red');\n");
        sb.append("  bpmChart('chartTtfb','TTFB',bpmAllLabels,bpmAllTtfb,'rgba(147,51,234,1)','ms',slaTtfb,'red');\n");
        sb.append("  bpmChart('chartCls','CLS',bpmAllLabels,bpmAllCls,'rgba(234,88,12,1)','CLS',slaCls,'red');\n");
        sb.append("  bpmChart('chartRender','Render Time',bpmAllLabels,bpmAllRender,'rgba(59,130,246,1)','ms',0,'red');\n");

        // Page filter handler
        if (hasPageFilter) {
            sb.append("  function updateCharts(sel) {\n");
            sb.append("    var d = (sel === '__all__') ? { labels: bpmAllLabels, scores: bpmAllScores,\n");
            sb.append("        lcp: bpmAllLcp, fcp: bpmAllFcp, ttfb: bpmAllTtfb,\n");
            sb.append("        cls: bpmAllCls, render: bpmAllRender } : bpmPages[sel];\n");
            sb.append("    if (!d) return;\n");
            sb.append("    function upd(c, lbls, data, slaVal) {\n");
            sb.append("      c.data.labels = lbls;\n");
            sb.append("      c.data.datasets[0].data = data;\n");
            sb.append("      if (c.data.datasets.length > 1 && slaVal > 0) {\n");
            sb.append("        c.data.datasets[1].data = new Array(lbls.length).fill(slaVal);\n");
            sb.append("      }\n");
            sb.append("      c.update();\n");
            sb.append("    }\n");
            sb.append("    upd(charts['chartScore'], d.labels, d.scores, slaScoreGood);\n");
            sb.append("    upd(charts['chartLcp'], d.labels, d.lcp, slaLcp);\n");
            sb.append("    upd(charts['chartFcp'], d.labels, d.fcp, slaFcp);\n");
            sb.append("    upd(charts['chartTtfb'], d.labels, d.ttfb, slaTtfb);\n");
            sb.append("    upd(charts['chartCls'], d.labels, d.cls, slaCls);\n");
            sb.append("    upd(charts['chartRender'], d.labels, d.render, 0);\n");
            sb.append("  }\n");
            sb.append("  document.getElementById('pageFilter').addEventListener('change', function() {\n");
            sb.append("    updateCharts(this.value);\n");
            sb.append("  });\n");
        }

        sb.append("})();\n</script>\n");
    }

    /**
     * Emits JS arrays for a set of time buckets: {prefix}Labels, {prefix}Scores, etc.
     */
    private static void appendBucketDataset(StringBuilder sb, String prefix,
                                            List<BpmTimeBucket> buckets) {
        List<String> labels = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        List<String> lcpVals = new ArrayList<>();
        List<String> fcpVals = new ArrayList<>();
        List<String> ttfbVals = new ArrayList<>();
        List<String> clsVals = new ArrayList<>();
        List<String> renderVals = new ArrayList<>();

        for (BpmTimeBucket b : buckets) {
            String timeLabel = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(b.epochMs), ZoneId.systemDefault())
                    .format(CHART_TIME_FMT);
            labels.add("\"" + timeLabel + "\"");
            scores.add(b.avgScore >= 0 ? String.format(Locale.US, "%.1f", b.avgScore) : "null");
            lcpVals.add(String.format(Locale.US, "%.0f", b.avgLcp));
            fcpVals.add(String.format(Locale.US, "%.0f", b.avgFcp));
            ttfbVals.add(String.format(Locale.US, "%.0f", b.avgTtfb));
            clsVals.add(b.avgCls >= 0 ? String.format(Locale.US, "%.3f", b.avgCls) : "null");
            renderVals.add(b.avgRenderTime >= 0 ? String.format(Locale.US, "%.0f", b.avgRenderTime) : "null");
        }

        sb.append("  var ").append(prefix).append("Labels = [").append(String.join(",", labels)).append("];\n");
        sb.append("  var ").append(prefix).append("Scores = [").append(String.join(",", scores)).append("];\n");
        sb.append("  var ").append(prefix).append("Lcp = [").append(String.join(",", lcpVals)).append("];\n");
        sb.append("  var ").append(prefix).append("Fcp = [").append(String.join(",", fcpVals)).append("];\n");
        sb.append("  var ").append(prefix).append("Ttfb = [").append(String.join(",", ttfbVals)).append("];\n");
        sb.append("  var ").append(prefix).append("Cls = [").append(String.join(",", clsVals)).append("];\n");
        sb.append("  var ").append(prefix).append("Render = [").append(String.join(",", renderVals)).append("];\n");
    }

    // ── Metadata JS object ──────────────────────────────────────────────────

    private static String formatInterval(int seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) return (seconds / 3600) + "-hour";
        if (seconds >= 60 && seconds % 60 == 0) return (seconds / 60) + "-minute";
        return seconds + "-second";
    }

    // ── Panel-switching script ──────────────────────────────────────────────

    private static void appendMetaScript(StringBuilder sb, RenderConfig config) {
        sb.append("<script>\nwindow.bpmMeta = {\n");
        sb.append("  scenarioName: '").append(escapeJs(config.scenarioName)).append("',\n");
        sb.append("  description:  '").append(escapeJs(config.description)).append("',\n");
        sb.append("  virtualUsers: '").append(escapeJs(config.virtualUsers)).append("',\n");
        sb.append("  providerName: '").append(escapeJs(config.providerName)).append("',\n");
        sb.append("  runDateTime:  '").append(escapeJs(config.runDateTime)).append("',\n");
        sb.append("  duration:     '").append(escapeJs(config.duration)).append("',\n");
        sb.append("  version:      '").append(escapeJs(config.version)).append("'\n");
        sb.append("};\n</script>\n");
    }

    // ── Table script (pagination + sorting) ───────────────────────────────

    private static void appendPanelScript(StringBuilder sb) {
        sb.append("<script>\n(function() {\n");
        sb.append("  document.querySelectorAll('.nav-item').forEach(function(btn) {\n");
        sb.append("    btn.addEventListener('click', function() {\n");
        sb.append("      document.querySelectorAll('.nav-item').forEach(function(b) { b.classList.remove('active'); });\n");
        sb.append("      document.querySelectorAll('.panel').forEach(function(p) { p.classList.remove('active'); });\n");
        sb.append("      btn.classList.add('active');\n");
        sb.append("      var el = document.getElementById(btn.dataset.panel);\n");
        sb.append("      if (el) el.classList.add('active');\n");
        sb.append("      if (el && el.querySelector('canvas') && typeof Chart !== 'undefined') {\n");
        sb.append("        Object.values(Chart.instances).forEach(function(inst) { inst.resize(); });\n");
        sb.append("      }\n");
        sb.append("    });\n");
        sb.append("  });\n");
        sb.append("})();\n</script>\n");
    }

    // ── Search filter script ───────────────────────────────────────────────

    private static void appendTableScript(StringBuilder sb) {
        sb.append("<script>\n(function() {\n");

        // initTable: reusable pagination + sorting for any table
        sb.append("  function initTable(tableId) {\n");
        sb.append("    var tbody = document.querySelector('[data-body-id=\"' + tableId + '\"]');\n");
        sb.append("    var sel = document.querySelector('.row-limit[data-for=\"' + tableId + '\"]');\n");
        sb.append("    var pager = document.querySelector('.pager[data-for=\"' + tableId + '\"]');\n");
        sb.append("    if (!tbody) return;\n");
        sb.append("    var allRows = [];\n");
        sb.append("    tbody.querySelectorAll('tr').forEach(function(r) { allRows.push(r); });\n");
        sb.append("    var page = 0;\n");
        sb.append("    var sortCol = -1, sortAsc = true;\n");

        // Render page
        sb.append("    function render() {\n");
        sb.append("      var limit = sel ? parseInt(sel.value) : allRows.length;\n");
        sb.append("      var total = allRows.length;\n");
        sb.append("      var pages = Math.ceil(total / limit) || 1;\n");
        sb.append("      if (page >= pages) page = pages - 1;\n");
        sb.append("      var start = page * limit;\n");
        sb.append("      allRows.forEach(function(r, i) {\n");
        sb.append("        r.style.display = (i >= start && i < start + limit) ? '' : 'none';\n");
        sb.append("      });\n");
        sb.append("      if (pager) buildPager(pager, page, pages, tableId);\n");
        sb.append("    }\n");

        // Sort
        sb.append("    var table = tbody.closest('table');\n");
        sb.append("    if (table) {\n");
        sb.append("      var ths = table.querySelectorAll('thead th');\n");
        sb.append("      ths.forEach(function(th, idx) {\n");
        sb.append("        th.style.cursor = 'pointer';\n");
        sb.append("        th.addEventListener('click', function() {\n");
        sb.append("          if (sortCol === idx) { sortAsc = !sortAsc; }\n");
        sb.append("          else { sortCol = idx; sortAsc = true; }\n");
        sb.append("          ths.forEach(function(h) { h.classList.remove('sort-asc','sort-desc'); });\n");
        sb.append("          th.classList.add(sortAsc ? 'sort-asc' : 'sort-desc');\n");
        sb.append("          allRows.sort(function(a, b) {\n");
        sb.append("            var at = (a.children[idx] || {}).textContent || '';\n");
        sb.append("            var bt = (b.children[idx] || {}).textContent || '';\n");
        sb.append("            var an = parseFloat(at.replace(/[^\\d.\\-]/g, ''));\n");
        sb.append("            var bn = parseFloat(bt.replace(/[^\\d.\\-]/g, ''));\n");
        sb.append("            if (!isNaN(an) && !isNaN(bn)) return sortAsc ? an - bn : bn - an;\n");
        sb.append("            return sortAsc ? at.localeCompare(bt) : bt.localeCompare(at);\n");
        sb.append("          });\n");
        sb.append("          allRows.forEach(function(r) { tbody.appendChild(r); });\n");
        sb.append("          page = 0;\n");
        sb.append("          render();\n");
        sb.append("        });\n");
        sb.append("      });\n");
        sb.append("    }\n");

        // Bind dropdown
        sb.append("    if (sel) {\n");
        sb.append("      sel.addEventListener('change', function() { page = 0; render(); });\n");
        sb.append("    }\n");
        sb.append("    render();\n");

        // Expose goPage for pager buttons
        sb.append("    window['goPage_' + tableId] = function(p) { page = p; render(); };\n");
        sb.append("  }\n");

        // Pager builder
        sb.append("  function buildPager(el, cur, total, tableId) {\n");
        sb.append("    var h = '';\n");
        sb.append("    if (total <= 1) { el.innerHTML = ''; return; }\n");
        sb.append("    h += '<button class=\"pg-btn\" ' + (cur === 0 ? 'disabled' : '') +\n");
        sb.append("         ' onclick=\"goPage_' + tableId + '(' + (cur - 1) + ')\">&laquo;</button> ';\n");
        sb.append("    var start = Math.max(0, cur - 2), end = Math.min(total, start + 5);\n");
        sb.append("    if (end - start < 5) start = Math.max(0, end - 5);\n");
        sb.append("    for (var i = start; i < end; i++) {\n");
        sb.append("      h += '<button class=\"pg-btn' + (i === cur ? ' pg-active' : '') + '\"'\n");
        sb.append("           + ' onclick=\"goPage_' + tableId + '(' + i + ')\">' + (i + 1) + '</button> ';\n");
        sb.append("    }\n");
        sb.append("    h += '<button class=\"pg-btn\" ' + (cur >= total - 1 ? 'disabled' : '') +\n");
        sb.append("         ' onclick=\"goPage_' + tableId + '(' + (cur + 1) + ')\">&raquo;</button>';\n");
        sb.append("    h += ' <span class=\"pg-info\">Page ' + (cur + 1) + ' of ' + total + '</span>';\n");
        sb.append("    el.innerHTML = h;\n");
        sb.append("  }\n");

        // Init all tables that have paginated-tbl wrappers
        sb.append("  document.querySelectorAll('.paginated-tbl').forEach(function(wrap) {\n");
        sb.append("    var id = wrap.dataset.tableId;\n");
        sb.append("    if (id) initTable(id);\n");
        sb.append("  });\n");

        sb.append("})();\n</script>\n");
    }

    // ── Excel export script ─────────────────────────────────────────────────

    private static void appendSearchScript(StringBuilder sb) {
        sb.append("<script>\n(function() {\n");
        sb.append("  var pairs = [\n");
        sb.append("    {input: 'metricsSearch', table: 'metrics'},\n");
        sb.append("    {input: 'slaSearch', table: 'sla'}\n");
        sb.append("  ];\n");
        sb.append("  pairs.forEach(function(p) {\n");
        sb.append("    var input = document.getElementById(p.input);\n");
        sb.append("    if (!input) return;\n");
        sb.append("    var timer;\n");
        sb.append("    input.addEventListener('input', function() {\n");
        sb.append("      clearTimeout(timer);\n");
        sb.append("      var self = this;\n");
        sb.append("      timer = setTimeout(function() {\n");
        sb.append("        var q = self.value.toLowerCase();\n");
        sb.append("        var tbody = document.querySelector('[data-body-id=\"' + p.table + '\"]');\n");
        sb.append("        if (!tbody) return;\n");
        sb.append("        tbody.querySelectorAll('tr').forEach(function(r) {\n");
        sb.append("          var label = r.querySelector('.label-cell');\n");
        sb.append("          if (!label) return;\n");
        sb.append("          r.style.display = label.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';\n");
        sb.append("        });\n");
        sb.append("      }, 200);\n");
        sb.append("    });\n");
        sb.append("  });\n");
        sb.append("})();\n</script>\n");
    }

    // ── CSS ─────────────────────────────────────────────────────────────────

    private static void appendExcelScript(StringBuilder sb) {
        sb.append("<script>\n(function() {\n");
        sb.append("  function exportExcel() {\n");
        sb.append("    if (typeof XLSX === 'undefined') {\n");
        sb.append("      alert('Excel export requires an internet connection to load SheetJS.');\n");
        sb.append("      return;\n");
        sb.append("    }\n");
        sb.append("    var wb = XLSX.utils.book_new();\n");
        sb.append("    var infoRows = [['Field', 'Value']];\n");
        sb.append("    if (window.bpmMeta) {\n");
        sb.append("      var m = window.bpmMeta;\n");
        sb.append("      if (m.scenarioName) infoRows.push(['Scenario Name', m.scenarioName]);\n");
        sb.append("      if (m.description)  infoRows.push(['Description', m.description]);\n");
        sb.append("      if (m.virtualUsers) infoRows.push(['Virtual Users', m.virtualUsers]);\n");
        sb.append("      if (m.runDateTime)  infoRows.push(['Run Date/Time', m.runDateTime]);\n");
        sb.append("      if (m.duration)     infoRows.push(['Duration', m.duration]);\n");
        sb.append("      if (m.version)      infoRows.push(['Version', m.version]);\n");
        sb.append("      if (m.providerName) infoRows.push(['AI Provider', m.providerName]);\n");
        sb.append("    }\n");
        sb.append("    var wsInfo = XLSX.utils.aoa_to_sheet(infoRows);\n");
        sb.append("    wsInfo['!cols'] = [{wch: 25}, {wch: 80}];\n");
        sb.append("    XLSX.utils.book_append_sheet(wb, wsInfo, 'Test Info');\n");
        sb.append("    document.querySelectorAll('.panel').forEach(function(panel) {\n");
        sb.append("      var title = panel.dataset.title || 'Sheet';\n");
        sb.append("      var sheetName = title.substring(0, 31);\n");
        sb.append("      var ws;\n");
        sb.append("      if (panel.querySelector('canvas')) {\n");
        sb.append("        ws = XLSX.utils.aoa_to_sheet([\n");
        sb.append("          ['Performance charts are not available in Excel export.'],\n");
        sb.append("          ['Please refer to the HTML report for interactive charts.']\n");
        sb.append("        ]);\n");
        sb.append("        ws['!cols'] = [{wch: 65}];\n");
        sb.append("      } else if (panel.querySelector('table')) {\n");
        sb.append("        ws = XLSX.utils.table_to_sheet(panel.querySelector('table'));\n");
        sb.append("      } else {\n");
        sb.append("        var text = panel.innerText || '';\n");
        sb.append("        var rows = text.split('\\n')\n");
        sb.append("          .filter(function(l) { return l.trim().length > 0; })\n");
        sb.append("          .map(function(l) { return [l.trim()]; });\n");
        sb.append("        ws = XLSX.utils.aoa_to_sheet(rows.length > 0 ? rows : [['(empty)']]);\n");
        sb.append("        ws['!cols'] = [{wch: 120}];\n");
        sb.append("      }\n");
        sb.append("      XLSX.utils.book_append_sheet(wb, ws, sheetName);\n");
        sb.append("    });\n");
        sb.append("    var provider = (window.bpmMeta && window.bpmMeta.providerName)\n");
        sb.append("      ? window.bpmMeta.providerName.replace(/\\s*\\(.*\\)/, '').replace(/[^a-zA-Z0-9_-]/g, '') : 'AI';\n");
        sb.append("    var ts = new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14);\n");
        sb.append("    XLSX.writeFile(wb, 'BPM_' + provider + '_Report_' + ts + '.xlsx');\n");
        sb.append("  }\n");
        sb.append("  window.exportExcel = exportExcel;\n");
        sb.append("})();\n</script>\n");
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Post-processes AI-rendered HTML to inject verdict coloring and strip artifacts.
     * Wraps bare verdict text in table cells with colored spans, and removes
     * horizontal rules the AI may emit between sections.
     */
    private static String postProcessVerdicts(String html) {
        // Wrap bare verdict words in table cells: | Pass | → | <span class="sla-pass">Pass</span> |
        html = html.replaceAll("<td>\\s*Pass\\s*</td>", "<td><span class=\"sla-pass\">Pass</span></td>");
        html = html.replaceAll("<td>\\s*Fail\\s*</td>", "<td><span class=\"sla-fail\">Fail</span></td>");
        html = html.replaceAll("<td>\\s*Warning\\s*</td>", "<td><span class=\"sla-warn\">Warning</span></td>");

        // Strip horizontal rules the AI may emit between sections
        html = HR_PATTERN.matcher(html).replaceAll("");

        return html;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJs(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'")
                .replace("<", "\\x3c").replace(">", "\\x3e")
                .replace("\n", "\\n").replace("\r", "");
    }

    /**
     * Report rendering configuration containing provider info and metadata.
     */
    public static final class RenderConfig {
        public final String providerName;
        public final String scenarioName;
        public final String description;
        public final String virtualUsers;
        public final String runDateTime;
        public final String duration;
        public final String version;
        public final int intervalSeconds;
        // SLA thresholds — "good" boundaries (green lines / Pass cutoff)
        public final int slaScoreGood;
        public final long slaLcpGood;
        public final long slaFcpGood;
        public final long slaTtfbGood;
        public final double slaClsGood;
        // SLA thresholds — "poor" boundaries (red lines / Fail cutoff)
        public final int slaScorePoor;
        public final long slaLcpPoor;
        public final long slaFcpPoor;
        public final long slaTtfbPoor;
        public final double slaClsPoor;
        // Label truncation info (0 = not truncated)
        public final int totalLabels;
        public final int includedLabels;

        public RenderConfig(String providerName, String scenarioName,
                            String description, String virtualUsers) {
            this(providerName, scenarioName, description, virtualUsers,
                    "", "", "", 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0);
        }

        public RenderConfig(String providerName, String scenarioName,
                            String description, String virtualUsers,
                            String runDateTime, String duration, String version) {
            this(providerName, scenarioName, description, virtualUsers,
                    runDateTime, duration, version, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0);
        }

        public RenderConfig(String providerName, String scenarioName,
                            String description, String virtualUsers,
                            String runDateTime, String duration, String version,
                            int intervalSeconds,
                            int slaScoreGood, long slaLcpGood,
                            long slaFcpGood, long slaTtfbGood, double slaClsGood,
                            int slaScorePoor, long slaLcpPoor,
                            long slaFcpPoor, long slaTtfbPoor, double slaClsPoor) {
            this(providerName, scenarioName, description, virtualUsers,
                    runDateTime, duration, version, intervalSeconds,
                    slaScoreGood, slaLcpGood, slaFcpGood, slaTtfbGood, slaClsGood,
                    slaScorePoor, slaLcpPoor, slaFcpPoor, slaTtfbPoor, slaClsPoor,
                    0, 0);
        }

        public RenderConfig(String providerName, String scenarioName,
                            String description, String virtualUsers,
                            String runDateTime, String duration, String version,
                            int intervalSeconds,
                            int slaScoreGood, long slaLcpGood,
                            long slaFcpGood, long slaTtfbGood, double slaClsGood,
                            int slaScorePoor, long slaLcpPoor,
                            long slaFcpPoor, long slaTtfbPoor, double slaClsPoor,
                            int totalLabels, int includedLabels) {
            this.providerName = Objects.requireNonNullElse(providerName, "");
            this.scenarioName = Objects.requireNonNullElse(scenarioName, "");
            this.description = Objects.requireNonNullElse(description, "");
            this.virtualUsers = Objects.requireNonNullElse(virtualUsers, "");
            this.runDateTime = Objects.requireNonNullElse(runDateTime, "");
            this.duration = Objects.requireNonNullElse(duration, "");
            this.version = Objects.requireNonNullElse(version, "");
            this.intervalSeconds = intervalSeconds;
            this.slaScoreGood = slaScoreGood;
            this.slaLcpGood = slaLcpGood;
            this.slaFcpGood = slaFcpGood;
            this.slaTtfbGood = slaTtfbGood;
            this.slaClsGood = slaClsGood;
            this.slaScorePoor = slaScorePoor;
            this.slaLcpPoor = slaLcpPoor;
            this.slaFcpPoor = slaFcpPoor;
            this.slaTtfbPoor = slaTtfbPoor;
            this.slaClsPoor = slaClsPoor;
            this.totalLabels = totalLabels;
            this.includedLabels = includedLabels;
        }

        public boolean wasLabelTruncated() {
            return totalLabels > 0 && totalLabels > includedLabels;
        }
    }
}