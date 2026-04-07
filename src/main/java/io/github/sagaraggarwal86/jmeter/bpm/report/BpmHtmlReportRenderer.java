package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.*;
import static io.github.sagaraggarwal86.jmeter.bpm.util.HtmlUtils.escapeHtml;
import static io.github.sagaraggarwal86.jmeter.bpm.util.HtmlUtils.severityTag;

/**
 * Renders a styled standalone HTML report with sidebar navigation,
 * metadata header, and optional time-series performance charts.
 *
 * <p>All 6 panels (Executive Summary, Performance Metrics, Performance Trends,
 * SLA Compliance, Critical Findings, Risk Assessment) are Java-generated.</p>
 *
 * <p>Layout uses panel-based show/hide navigation (click sidebar items
 * to switch panels). Includes Excel export via SheetJS CDN.</p>
 */
public final class BpmHtmlReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(BpmHtmlReportRenderer.class);
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


    // Sidebar icons for navigation panels
    private static final Map<String, String> SIDEBAR_ICONS = Map.of(
            "Executive Summary", "\uD83D\uDCCA",      // chart emoji
            "Performance Metrics", "\uD83D\uDCCB",     // clipboard emoji
            "Performance Trends", "\uD83D\uDCC8",      // chart-increasing emoji
            "SLA Compliance", "\u2705",                 // check mark emoji
            "Critical Findings", "\u26A0\uFE0F",       // warning emoji
            "Risk Assessment", "\uD83D\uDEE1\uFE0F"   // shield emoji
    );


    // Report-friendly column header names (longer than GUI abbreviations)
    private static final Map<String, String> REPORT_HEADER_NAMES = Map.ofEntries(
            Map.entry("Label", "Transaction Name"),
            Map.entry("Smpl", "Samples"),
            Map.entry("Rndr(ms)", "Render(ms)"),
            Map.entry("Srvr(%)", "Server(%)"),
            Map.entry("Front(ms)", "Frontend(ms)"),
            Map.entry("Gap(ms)", "FCP-LCP(ms)")
    );

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

    // CSS and JS loaded from classpath resources — cached in static fields
    private static final String CSS;
    private static final String REPORT_JS;
    // Maps full-model column index → slaCol index used by computeVerdict (0 = not SLA-relevant)
    private static final Map<Integer, Integer> COL_TO_SLA = Map.of(
            BpmConstants.COL_IDX_SCORE, 1,
            BpmConstants.COL_IDX_LCP, 2,
            BpmConstants.COL_IDX_FCP, 3,
            BpmConstants.COL_IDX_TTFB, 4,
            BpmConstants.COL_IDX_CLS, 5
    );

    // ── Metadata KPI cards (first panel) ────────────────────────────────────

    static {
        String loadedCss = ReportPanelBuilder.loadTemplate("bpm-report.css");
        CSS = loadedCss != null ? "  <style>\n" + loadedCss + "\n  </style>\n"
                : "  <!-- bpm-report.css not found on classpath -->\n";
        String loadedJs = ReportPanelBuilder.loadTemplate("bpm-report.js");
        REPORT_JS = loadedJs != null ? "<script>\n" + loadedJs + "\n</script>\n" : "";
    }

    // ── Metrics table panel ────────────────────────────────────────────────

    private BpmHtmlReportRenderer() {
    }

    // ── SLA Compliance panel ────────────────────────────────────────────────

    /**
     * Simple overload for GUI mode (no metadata, no charts).
     */
    public static String render(ReportData data, RenderConfig config) {
        return render(data, config, Collections.emptyList(), Collections.emptyMap(), Collections.emptyList());
    }

    // ── Critical Findings panel (Java-generated) ─────────────────────────────

    /**
     * Full render with all features.
     *
     * @param data            pre-computed report data for Executive Summary and Risk Assessment
     * @param config          rendering configuration with metadata and SLA thresholds
     * @param timeBuckets     global time-series data for performance charts (may be empty)
     * @param perLabelBuckets per-label time-series data for page filter (may be empty)
     * @param metricsTable    table data: element 0 = column headers, elements 1..n = data rows (may be empty)
     * @return complete HTML string ready to write to a file
     */
    public static String render(ReportData data, RenderConfig config,
                                List<BpmTimeBucket> timeBuckets,
                                Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                List<String[]> metricsTable) {
        Objects.requireNonNull(data, "data must not be null");

        boolean hasMetrics = metricsTable != null && metricsTable.size() > 1;
        boolean hasCharts = timeBuckets != null && !timeBuckets.isEmpty();

        // Fixed panel order: all 6 panels
        List<String> panelHeadings = new ArrayList<>();
        panelHeadings.add("Executive Summary");
        if (hasMetrics) panelHeadings.add("Performance Metrics");
        if (hasCharts) panelHeadings.add("Performance Trends");
        boolean hasSla = config.slaScoreGood > 0 || config.slaScorePoor > 0;
        if (hasSla) panelHeadings.add("SLA Compliance");
        if (hasMetrics) panelHeadings.add("Critical Findings");
        panelHeadings.add("Risk Assessment");

        return buildHtmlPage(data, panelHeadings, config,
                hasMetrics, metricsTable, hasCharts, timeBuckets, perLabelBuckets);
    }

    private static String buildHtmlPage(ReportData data, List<String> panelHeadings,
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
        // Inline bundled libraries for offline support (falls back to CDN if resource missing)
        inlineOrCdn(sb, "chart.umd.min.js",
                "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js");
        inlineOrCdn(sb, "xlsx-style.bundle.js",
                "https://cdn.jsdelivr.net/npm/xlsx-js-style@1.2.0/dist/xlsx.bundle.js");
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

        boolean hasSla = panelHeadings.contains("SLA Compliance");

        // Render panels in fixed order — all Java-generated
        int panelIndex = 0;
        for (String heading : panelHeadings) {
            String activeClass = panelIndex == 0 ? " active" : "";
            sb.append("<div class=\"panel").append(activeClass).append("\" id=\"panel-")
                    .append(panelIndex).append("\" role=\"tabpanel\" aria-labelledby=\"tab-")
                    .append(panelIndex).append("\" data-title=\"")
                    .append(escapeHtml(heading)).append("\">\n");

            if ("Executive Summary".equals(heading)) {
                appendExecutiveSummaryPanel(sb, config, data);
            } else if ("Performance Metrics".equals(heading) && hasMetrics) {
                appendMetricsPanel(sb, config, metricsTable);
            } else if ("Performance Trends".equals(heading) && hasCharts) {
                appendChartsPanel(sb, config, timeBuckets, perLabelBuckets);
            } else if ("SLA Compliance".equals(heading) && hasSla) {
                appendSlaPanel(sb, config, metricsTable, data);
            } else if ("Critical Findings".equals(heading) && hasMetrics) {
                appendCriticalFindingsPanel(sb, data);
            } else if ("Risk Assessment".equals(heading)) {
                appendRiskAssessmentPanel(sb, data);
            }

            sb.append("</div>\n");
            panelIndex++;
        }

        sb.append("      </div>\n"); // content-area
        sb.append("    </div>\n");   // main-col
        sb.append("  </div>\n");     // body-row

        // Footer
        String timestamp = LocalDateTime.now().format(FOOTER_TIME_FMT);
        sb.append("  <div class=\"footer-rpt\">Report generated on ")
                .append(escapeHtml(timestamp)).append("</div>\n");
        sb.append("</div>\n"); // rpt

        // Static scripts (panel nav, pagination, search, export) — cached from classpath
        sb.append(REPORT_JS);

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void appendHeader(StringBuilder sb, RenderConfig config) {
        sb.append("  <div class=\"rpt-header\">\n    <div class=\"header-left\">\n");
        sb.append("      <h1>Browser Performance Metrics Report</h1>\n");

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
        sb.append("      <button class=\"exp-btn\" onclick=\"toggleTheme()\" id=\"themeToggle\">&#x1F319;&nbsp; Dark Mode</button>\n");
        sb.append("      <button class=\"exp-btn\" onclick=\"exportExcel()\">&#x1F4E5;&nbsp; Export Excel</button>\n");
        sb.append("    </div>\n");
        sb.append("  </div>\n");
    }

    private static void appendSidebar(StringBuilder sb, List<String> panelHeadings) {
        sb.append("    <nav class=\"sidebar\" role=\"tablist\" aria-label=\"Report sections\">\n");
        for (int i = 0; i < panelHeadings.size(); i++) {
            String activeClass = i == 0 ? " active" : "";
            String heading = panelHeadings.get(i);
            String icon = SIDEBAR_ICONS.getOrDefault(heading, "");
            String prefix = icon.isEmpty() ? "" : icon + " ";
            String tabId = "tab-" + i;
            String panelId = "panel-" + i;
            sb.append("  <button class=\"nav-item").append(activeClass)
                    .append("\" id=\"").append(tabId)
                    .append("\" role=\"tab\" aria-selected=\"").append(i == 0 ? "true" : "false")
                    .append("\" aria-controls=\"").append(panelId)
                    .append("\" tabindex=\"").append(i == 0 ? "0" : "-1")
                    .append("\" data-panel=\"").append(panelId).append("\">")
                    .append(prefix).append(escapeHtml(heading)).append("</button>\n");
        }
        sb.append("    </nav>\n");
    }

    private static void appendMetadataKpi(StringBuilder sb, RenderConfig config, ReportData data) {
        int failCount = data.criticalCount() + data.warnCount();
        String overallCss = failCount == 0 ? "pass" : "fail";
        String overallText = failCount == 0 ? "All Pass"
                : failCount + " of " + data.totalLabels() + " Need Attention";

        sb.append("    <div class=\"metadata-grid kpi-grid\">\n");

        // Card 1: Overall verdict
        String verdictTooltip = failCount == 0
                ? "All pages meet SLA targets"
                : "See SLA Compliance panel for details";
        sb.append("      <div class=\"kpi\" title=\"").append(escapeHtml(verdictTooltip))
                .append("\"><div class=\"kpi-label\">Overall Verdict</div>")
                .append("<div class=\"kpi-value ").append(overallCss).append("\">")
                .append(escapeHtml(overallText)).append("</div></div>\n");

        // Card 2: Performance Score — weighted score primary, range secondary
        sb.append("      <div class=\"kpi\" title=\"Weighted average across all pages (proportional to sample count). Range shows the lowest and highest individual page scores.\">");
        sb.append("<div class=\"kpi-label\">Performance Score</div>");
        if (data.weightedScore() > 0) {
            sb.append("<div class=\"kpi-value\" style=\"font-size:28px;font-weight:700;line-height:1.1\">");
            appendColoredScore(sb, data.weightedScore(), config);
            sb.append("</div>");
            if (data.worstScore() > 0 || data.bestScore() > 0) {
                sb.append("<div style=\"font-size:12px;color:var(--color-text-secondary);margin-top:4px\">");
                sb.append("Range: ");
                appendColoredScore(sb, data.worstScore(), config);
                sb.append(" <span style=\"color:var(--color-text-tertiary)\">\u2013</span> ");
                appendColoredScore(sb, data.bestScore(), config);
                sb.append("</div>");
            }
        } else {
            sb.append("<div class=\"kpi-value\">N/A</div>");
        }
        sb.append("</div>\n");

        // Card 3: Avg Load Time (weighted LCP)
        sb.append("      <div class=\"kpi\" title=\"Weighted average LCP across all pages (weighted by sample count). SPA navigations excluded.\">");
        sb.append("<div class=\"kpi-label\">Avg Load Time</div>");
        if (data.weightedLcp() > 0) {
            long weightedLcp = data.weightedLcp();
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

    private static void appendExecutiveSummaryPanel(StringBuilder sb, RenderConfig config,
                                                    ReportData data) {
        sb.append("<h2>Executive Summary</h2>\n");

        // Build KPI cards HTML into a separate buffer
        StringBuilder kpiBuffer = new StringBuilder();
        appendMetadataKpi(kpiBuffer, config, data);

        // Build the rest from template + ReportData
        String panelHtml = ReportPanelBuilder.buildExecutiveSummary(
                data, kpiBuffer.toString(), config.slaScoreGood, config.slaScorePoor);
        sb.append(panelHtml);
    }

    private static void appendRiskAssessmentPanel(StringBuilder sb, ReportData data) {
        sb.append("<h2>Risk Assessment</h2>\n");
        sb.append(ReportPanelBuilder.buildRiskAssessment(data));
    }

    private static void appendMetricsPanel(StringBuilder sb, RenderConfig config,
                                           List<String[]> metricsTable) {
        sb.append("<h2>Performance Metrics</h2>\n");
        appendMetricDescriptions(sb);
        sb.append("<div class=\"tbl-search\"><input type=\"text\" id=\"metricsSearch\" ")
                .append("placeholder=\"Search transactions\u2026\" autocomplete=\"off\"></div>\n");
        appendPaginatedTable(sb, config, metricsTable, "metrics");
    }

    private static void appendSlaPanel(StringBuilder sb, RenderConfig config,
                                       List<String[]> metricsTable, ReportData data) {
        sb.append("<h2>SLA Compliance</h2>\n");

        // Intro line
        sb.append("<p class=\"sla-intro\">Each metric is evaluated against configured quality thresholds. ")
                .append("Cells are colour-coded: ")
                .append("<span style=\"color:#276749\">\u2713 Pass</span>, ")
                .append("<span style=\"color:#b7791f\">\u26A0 Warning</span>, ")
                .append("<span style=\"color:#c53030\">\u2717 Fail</span>.</p>\n");

        // Compact threshold reference (moved above table)
        sb.append("<div class=\"sla-thresholds\">\n");
        sb.append("<strong>SLA Thresholds:</strong> ");
        sb.append("Score: \u2265").append(config.slaScoreGood).append(" Good, \u2265").append(config.slaScorePoor).append(" Warning");
        sb.append(" | LCP: \u2264").append(config.slaLcpGood).append("ms Good, \u2264").append(config.slaLcpPoor).append("ms Warning");
        sb.append(" | FCP: \u2264").append(config.slaFcpGood).append("ms Good, \u2264").append(config.slaFcpPoor).append("ms Warning");
        sb.append(" | TTFB: \u2264").append(config.slaTtfbGood).append("ms Good, \u2264").append(config.slaTtfbPoor).append("ms Warning");
        sb.append(" | CLS: \u2264").append(String.format(Locale.US, "%.2f", config.slaClsGood))
                .append(" Good, \u2264").append(String.format(Locale.US, "%.2f", config.slaClsPoor)).append(" Warning");
        sb.append("\n</div>\n");

        sb.append("<div class=\"tbl-search\"><input type=\"text\" id=\"slaSearch\" ")
                .append("placeholder=\"Search transactions\u2026\" autocomplete=\"off\"></div>\n");

        // SLA columns: Page | Score | Page Load (LCP) | First Paint (FCP) | Server Response (TTFB) | Visual Stability (CLS)
        String[] slaHeaders = {"Transaction Name", "Score", "Page Load", "First Paint", "Server Response", "Visual Stability"};
        int[] srcCols = {BpmConstants.COL_IDX_LABEL, BpmConstants.COL_IDX_SCORE,
                BpmConstants.COL_IDX_LCP, BpmConstants.COL_IDX_FCP,
                BpmConstants.COL_IDX_TTFB, BpmConstants.COL_IDX_CLS};

        // Build SLA rows: each cell = "rawValue|verdict" (computeVerdict used for per-cell display only)
        List<String[]> slaRows = new ArrayList<>();
        for (int r = 1; r < metricsTable.size(); r++) {
            String[] srcRow = metricsTable.get(r);
            if (srcRow.length == 0 || "TOTAL".equals(srcRow[0])) continue;

            String[] slaRow = new String[6];
            slaRow[0] = srcRow[0]; // label
            for (int c = 1; c < 6; c++) {
                int srcIdx = srcCols[c];
                String rawVal = col(srcRow, srcIdx);
                String verdict = computeVerdict(c, rawVal, config);
                slaRow[c] = rawVal + "|" + verdict;
            }
            slaRows.add(slaRow);
        }

        // Summary line — uses ReportData counts (single source of truth)
        int failCount = data.criticalCount() + data.warnCount();
        if (failCount == 0 && data.totalLabels() > 0) {
            sb.append("<p class=\"sla-summary sla-pass-summary\">All ").append(data.totalLabels())
                    .append(" transactions pass all performance targets.</p>\n");
        } else if (failCount > 0) {
            sb.append("<p class=\"sla-summary sla-fail-summary\">").append(failCount)
                    .append(" of ").append(data.totalLabels()).append(" transactions need attention.</p>\n");
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

        // Data rows sorted by label — color + icon only, no verdict badges
        slaRows.sort(Comparator.comparing(r -> r[0].toLowerCase(Locale.ROOT)));
        for (String[] row : slaRows) {
            sb.append("<tr>\n");
            sb.append("  <td class=\"label-cell\">").append(escapeHtml(row[0])).append("</td>\n");
            for (int c = 1; c < row.length; c++) {
                String[] parts = row[c].split("\\|", 2);
                String rawVal = parts[0];
                String verdict = parts.length > 1 ? parts[1] : VERDICT_NA;
                String css = verdictToCss(verdict);
                sb.append("  <td class=\"").append(css).append("\">");
                if (VERDICT_NA.equals(verdict)) {
                    sb.append("-");
                } else {
                    String icon = verdictToIcon(verdict);
                    String unit = c < SLA_UNITS.length ? SLA_UNITS[c] : "";
                    String val = "\u2014".equals(rawVal) ? "-" : rawVal + unit;
                    sb.append(icon).append(escapeHtml(val));
                }
                sb.append("</td>\n");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n</div>\n</div>\n");
    }

    private static String computeVerdict(int slaCol, String rawVal, RenderConfig config) {
        if (rawVal == null || rawVal.isEmpty() || "\u2014".equals(rawVal)) return VERDICT_NA;
        try {
            return switch (slaCol) {
                case 1 -> { // Score (higher is better)
                    int v = Integer.parseInt(rawVal.trim());
                    yield v >= config.slaScoreGood ? VERDICT_GOOD : v >= config.slaScorePoor ? VERDICT_NEEDS_WORK : VERDICT_POOR;
                }
                case 2 -> { // LCP ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield VERDICT_NA;
                    yield v <= config.slaLcpGood ? VERDICT_GOOD : v <= config.slaLcpPoor ? VERDICT_NEEDS_WORK : VERDICT_POOR;
                }
                case 3 -> { // FCP ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield VERDICT_NA;
                    yield v <= config.slaFcpGood ? VERDICT_GOOD : v <= config.slaFcpPoor ? VERDICT_NEEDS_WORK : VERDICT_POOR;
                }
                case 4 -> { // TTFB ms (lower is better)
                    long v = Long.parseLong(rawVal.trim());
                    if (v == 0) yield VERDICT_NA;
                    yield v <= config.slaTtfbGood ? VERDICT_GOOD : v <= config.slaTtfbPoor ? VERDICT_NEEDS_WORK : VERDICT_POOR;
                }
                case 5 -> { // CLS (lower is better)
                    double v = Double.parseDouble(rawVal.trim());
                    yield v <= config.slaClsGood ? VERDICT_GOOD : v <= config.slaClsPoor ? VERDICT_NEEDS_WORK : VERDICT_POOR;
                }
                default -> VERDICT_NA;
            };
        } catch (NumberFormatException e) {
            return VERDICT_NA;
        }
    }

    // ── Critical Findings card content builders ─────────────────────────────

    private static void appendCriticalFindingsPanel(StringBuilder sb, ReportData data) {
        sb.append("<h2>Critical Findings</h2>\n");

        List<ReportData.BreachEntry> breaches = data.breaches();
        if (breaches.isEmpty()) {
            sb.append("<p class=\"sla-summary sla-pass-summary\">No critical issues were detected. ")
                    .append("All transactions are meeting their quality targets.</p>\n");
            return;
        }

        Map<String, List<ReportData.BreachEntry>> groups = ReportData.groupByArea(breaches);
        Map<String, Boolean> groupHasCritical = new LinkedHashMap<>();
        for (Map.Entry<String, List<ReportData.BreachEntry>> e : groups.entrySet()) {
            groupHasCritical.put(e.getKey(),
                    e.getValue().stream().anyMatch(ReportData.BreachEntry::hasCritical));
        }

        int critCount = data.criticalCount();
        int warnCount = data.warnCount();
        int totalFindings = critCount + warnCount;
        sb.append("<p class=\"sla-summary sla-fail-summary\">");
        if (critCount > 0) sb.append(critCount).append(" critical");
        if (critCount > 0 && warnCount > 0) sb.append(" and ");
        if (warnCount > 0) sb.append(warnCount).append(" warning");
        sb.append(" finding").append(totalFindings > 1 ? "s" : "")
                .append(" across ").append(totalFindings).append(" transactions.</p>\n");

        // Grouped card layout — one card per bottleneck area
        sb.append("<div class=\"cf-cards\">\n");
        for (Map.Entry<String, List<ReportData.BreachEntry>> entry : groups.entrySet()) {
            String area = entry.getKey();
            List<ReportData.BreachEntry> pages = entry.getValue();
            boolean isCritical = Boolean.TRUE.equals(groupHasCritical.get(area));
            String actionPrefix = isCritical ? "Immediate" : "Recommended";
            String displayName = BOTTLENECK_DISPLAY_NAMES.getOrDefault(area, area);

            sb.append("<details class=\"cf-card\">\n");
            sb.append("  <summary><strong>").append(escapeHtml(displayName)).append("</strong> ")
                    .append("<span class=\"severity-tag ").append(isCritical ? "severity-critical" : "severity-warning")
                    .append("\">").append(pages.size()).append(pages.size() == 1 ? " transaction" : " transactions")
                    .append("</span></summary>\n");
            sb.append("  <div class=\"cf-card-body\">\n");

            sb.append("    <details class=\"cf-transaction-list\">\n");
            sb.append("      <summary>").append(pages.size())
                    .append(" affected transaction").append(pages.size() != 1 ? "s" : "")
                    .append(" \u2014 show list</summary>\n");
            sb.append("      <ul>\n");
            for (ReportData.BreachEntry page : pages) {
                sb.append("        <li><strong>").append(escapeHtml(page.label())).append("</strong> ")
                        .append(severityTag(page.hasCritical())).append("</li>\n");
            }
            sb.append("      </ul>\n");
            sb.append("    </details>\n");

            // Root cause
            sb.append("    <p><strong>Root Cause:</strong> ").append(escapeHtml(buildCardRootCause(area))).append("</p>\n");

            // User impact
            sb.append("    <p><strong>User Impact:</strong> ").append(escapeHtml(buildCardImpact(area, isCritical))).append("</p>\n");

            // 4-part recommended action
            String[] actionParts = buildCardAction(area, isCritical);
            sb.append("    <div class=\"cf-action-section\">\n");
            sb.append("      <div class=\"cf-action-label\">Recommended Action \u2014 ").append(actionPrefix).append("</div>\n");
            sb.append("      <p><strong>What to do:</strong> ").append(escapeHtml(actionParts[0])).append("</p>\n");
            sb.append("      <p><strong>Quick win:</strong> ").append(escapeHtml(actionParts[1])).append("</p>\n");
            sb.append("      <p><strong>Long-term fix:</strong> ").append(escapeHtml(actionParts[2])).append("</p>\n");
            sb.append("      <p><strong>Expected outcome:</strong> ").append(escapeHtml(actionParts[3])).append("</p>\n");
            sb.append("    </div>\n");

            sb.append("  </div>\n");
            sb.append("</details>\n");
        }
        sb.append("</div>\n");
    }

    private static String buildCardRootCause(String area) {
        return switch (area) {
            case BOTTLENECK_SERVER ->
                    "The server is taking too long to respond. Backend processing is the primary bottleneck, delaying everything downstream.";
            case BOTTLENECK_RESOURCE ->
                    "The page is loading large resources \u2014 oversized images, scripts, or stylesheets \u2014 that delay rendering.";
            case BOTTLENECK_CLIENT ->
                    "The browser is spending excessive time rendering the page. Complex layouts or heavy JavaScript execution is consuming the main thread.";
            case BOTTLENECK_LAYOUT ->
                    "The page has an excessive number of DOM elements. Complex nesting is slowing down rendering and layout calculations.";
            case BOTTLENECK_RELIABILITY ->
                    "There are failed or blocked network requests preventing resources from loading correctly.";
            default -> "No single bottleneck was identified. Performance is degraded across multiple areas.";
        };
    }

    private static String buildCardImpact(String area, boolean isCritical) {
        String severity = isCritical ? "significant" : "noticeable";
        return switch (area) {
            case BOTTLENECK_SERVER ->
                    "Users experience a " + severity + " delay before any content appears on screen. The page feels unresponsive.";
            case BOTTLENECK_RESOURCE -> isCritical
                    ? "Users experience noticeable delays as the browser waits for large resources to download before rendering content."
                    : "The page loads slowly but remains functional. Users on slower connections will feel the impact more acutely.";
            case BOTTLENECK_CLIENT ->
                    "The page feels sluggish after content loads. Interactions may be delayed while the browser completes rendering work.";
            case BOTTLENECK_LAYOUT ->
                    "The page takes longer to become interactive due to the complexity of the layout. Scrolling and interactions may feel janky.";
            case BOTTLENECK_RELIABILITY ->
                    "Some page content may be missing or broken. Users may see incomplete layouts or failed functionality.";
            default ->
                    "Users are experiencing a degraded experience. Multiple aspects of the page contribute to the slowdown.";
        };
    }

    // ── Paginated table helper ──────────────────────────────────────────────

    /**
     * Returns a 4-element array: [whatToDo, quickWin, longTermFix, expectedOutcome].
     */
    private static String[] buildCardAction(String area, boolean isCritical) {
        return switch (area) {
            case BOTTLENECK_SERVER -> new String[]{
                    "Investigate server-side processing for the affected pages. The server response time is the primary delay before any content reaches the browser.",
                    "Enable response caching for static assets. Check for redundant database queries or synchronous API calls that could be parallelised.",
                    "Profile the backend under load to identify slow queries or resource contention. Consider adding server-side caching, connection pooling, or moving heavy processing to asynchronous workflows.",
                    "Reducing server response time would directly improve how quickly users see these pages. This is the single highest-impact change."
            };
            case BOTTLENECK_RESOURCE -> new String[]{
                    "Audit the resources loaded by the affected pages. Large images, unminified scripts, or render-blocking stylesheets are delaying rendering.",
                    "Compress images to modern formats (WebP/AVIF) and defer non-critical JavaScript. These changes require no architectural work.",
                    "Implement lazy loading for below-fold content and evaluate a resource bundling strategy to reduce the number of blocking requests.",
                    "Reducing page weight would bring load times within acceptable limits and prevent further degradation under increased traffic."
            };
            case BOTTLENECK_CLIENT -> new String[]{
                    "Profile the main thread activity on the affected pages. Long JavaScript tasks or complex CSS layouts are likely blocking the browser.",
                    "Identify and break up JavaScript tasks longer than 50ms. Defer non-essential scripts that run during page load.",
                    "Simplify the DOM structure and reduce nested elements. Consider virtualising long lists or complex form sections.",
                    "Reducing main-thread work would make the affected pages feel more responsive and prevent degradation under future feature additions."
            };
            case BOTTLENECK_LAYOUT -> new String[]{
                    "Check the DOM node count on the affected pages. Excessive nesting and large element trees are slowing down rendering.",
                    "Remove unnecessary wrapper elements and flatten the DOM structure where possible.",
                    "Virtualise long lists and paginate complex data tables. Consider component-level lazy rendering for sections below the fold.",
                    "Reducing DOM complexity would improve both initial render time and ongoing interaction responsiveness."
            };
            case BOTTLENECK_RELIABILITY -> new String[]{
                    "Check the browser Network tab for failed requests (4xx/5xx errors). Verify CDN and third-party resource availability.",
                    "Fix any broken resource URLs and ensure fallback behaviour for third-party dependencies.",
                    "Implement error monitoring for critical resources. Add retry logic or graceful degradation for non-essential third-party scripts.",
                    "Resolving network failures would restore full page functionality and may improve performance scores as blocked resources are loaded correctly."
            };
            default -> new String[]{
                    "Review the affected pages holistically \u2014 no single bottleneck dominates, so targeted investigation is needed.",
                    "Check the Performance Metrics panel for the specific metrics that are underperforming.",
                    "Profile each page end-to-end to identify whether server, network, or client factors dominate.",
                    "Identifying the primary constraint would enable targeted optimisation with the highest return on effort."
            };
        };
    }

    private static String col(String[] row, int idx) {
        return idx < row.length ? row[idx] : "";
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
            String headerText = REPORT_HEADER_NAMES.getOrDefault(headers[i], headers[i]);
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

        for (String[] row : dataRows) {
            sb.append("<tr>\n");
            for (int c = 0; c < row.length; c++) {
                String cell = row[c] != null ? row[c] : "";
                String display = "\u2014".equals(cell) ? "-"
                        : (c == BpmConstants.COL_IDX_IMPROVEMENT_AREA && "None".equals(cell)) ? "-" : cell;
                // Compute verdict once for SLA-relevant columns — used for both CSS and icon
                Integer slaCol = COL_TO_SLA.get(c);
                String verdict = slaCol != null ? computeVerdict(slaCol, cell, config) : null;
                String css = verdict != null ? verdictToCss(verdict) : getCellCssClass(c, cell, config, false);
                sb.append("  <td");
                if (!css.isEmpty()) sb.append(" class=\"").append(css).append("\"");
                if (c == BpmConstants.COL_IDX_IMPROVEMENT_AREA && IMPROVEMENT_AREA_TOOLTIPS.containsKey(cell)) {
                    sb.append(" title=\"").append(escapeHtml(IMPROVEMENT_AREA_TOOLTIPS.get(cell))).append("\"");
                }
                sb.append(">");
                if (verdict != null && !"-".equals(display)) {
                    String icon = verdictToIcon(verdict);
                    sb.append(icon);
                }
                sb.append(escapeHtml(display)).append("</td>\n");
            }
            sb.append("</tr>\n");
        }

        sb.append("</tbody>\n</table>\n</div>\n</div>\n");
    }

    private static String getCellCssClass(int col, String value, RenderConfig config, boolean isTotalRow) {
        if (isTotalRow) return "";
        if (col == BpmConstants.COL_IDX_LABEL) return "label-cell";
        Integer slaCol = COL_TO_SLA.get(col);
        if (slaCol != null) {
            return verdictToCss(computeVerdict(slaCol, value, config));
        }
        if (col == BpmConstants.COL_IDX_STABILITY
                || col == BpmConstants.COL_IDX_IMPROVEMENT_AREA) {
            return "";
        }
        return "num";
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
        // Trend insight note (replaces functional text)
        sb.append("  <p class=\"charts-note\">");
        // If TrendAnalyzer data is available via config, show insight; otherwise show interval info
        sb.append("Each point represents a ").append(escapeHtml(intervalText)).append(" interval.");
        boolean hasSlaLines = config.slaScoreGood > 0 || config.slaLcpPoor > 0
                || config.slaFcpPoor > 0 || config.slaTtfbPoor > 0 || config.slaClsPoor > 0;
        if (hasSlaLines) {
            sb.append(" Dashed lines indicate SLA thresholds.");
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
                .append("<div style=\"font-size:10px;color:var(--color-text-tertiary);margin:-8px 0 8px\">No SLA threshold defined for this metric</div>")
                .append("<div class=\"chart-canvas-wrap\"><canvas id=\"chartRender\"></canvas></div></div>\n");
        sb.append("  </div>\n"); // charts-grid
        sb.append("</div>\n");   // charts-section

        // Build JS data — global dataset
        sb.append("<script>\n(function() {\n");
        appendBucketDataset(sb, "bpmAll", timeBuckets);

        // Per-label datasets
        if (hasPageFilter) {
            sb.append("  var bpmPages = {};\n");
            int perLabelIndex = 0;
            for (Map.Entry<String, List<BpmTimeBucket>> entry : perLabelBuckets.entrySet()) {
                String varSuffix = "p" + perLabelIndex++;
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
        sb.append("      var slaLabel = (slaColor === 'green' ? 'SLA Target: ' : 'SLA Threshold: ') + slaVal + (yLabel === 'Score' ? '' : ' ' + yLabel);\n");
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
        sb.append("            var v = ctx.parsed.y;\n");
        sb.append("            var fmt = yLabel === 'CLS' ? v.toFixed(3) : yLabel === 'Score' ? Math.round(v) : Math.round(v);\n");
        sb.append("            var suffix = yLabel === 'Score' ? '' : ' ' + yLabel;\n");
        sb.append("            var verdict = '';\n");
        sb.append("            if (slaVal > 0 && ctx.datasetIndex === 0) {\n");
        sb.append("              if (slaColor === 'green') { verdict = v >= slaVal ? ' (Pass)' : ' (Below target)'; }\n");
        sb.append("              else { verdict = v <= slaVal ? ' (Pass)' : ' (Exceeds threshold)'; }\n");
        sb.append("            }\n");
        sb.append("            return ' ' + fmt + suffix + verdict;\n");
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
        sb.append("  runDateTime:  '").append(escapeJs(config.runDateTime)).append("',\n");
        sb.append("  duration:     '").append(escapeJs(config.duration)).append("',\n");
        sb.append("  version:      '").append(escapeJs(config.version)).append("'\n");
        sb.append("};\n</script>\n");
    }

    // Panel switching, table pagination, search, and Excel export scripts
    // extracted to src/main/resources/bpm-report.js

    // ── Utilities ────────────────────────────────────────────────────────────

    /**
     * Inlines a bundled JS library from classpath if available; falls back to CDN {@code <script src>}.
     * This ensures the report works offline when the library JAR contains the bundled resource.
     */
    private static void inlineOrCdn(StringBuilder sb, String resourceName, String cdnUrl) {
        String content = ReportPanelBuilder.loadTemplate(resourceName);
        if (content != null) {
            sb.append("  <script>").append(content).append("</script>\n");
        } else {
            sb.append("  <script src=\"").append(cdnUrl).append("\"></script>\n");
        }
    }

    private static String escapeJs(String text) {
        return text.replace("\\", "\\\\").replace("'", "\\'")
                .replace("<", "\\x3c").replace(">", "\\x3e")
                .replace("\n", "\\n").replace("\r", "")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    /**
     * Report rendering configuration containing metadata and SLA thresholds.
     */
    public static final class RenderConfig {
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

        public RenderConfig(String scenarioName, String description, String virtualUsers) {
            this(scenarioName, description, virtualUsers,
                    "", "", "", 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0);
        }

        public RenderConfig(String scenarioName, String description, String virtualUsers,
                            String runDateTime, String duration, String version,
                            int intervalSeconds,
                            int slaScoreGood, long slaLcpGood,
                            long slaFcpGood, long slaTtfbGood, double slaClsGood,
                            int slaScorePoor, long slaLcpPoor,
                            long slaFcpPoor, long slaTtfbPoor, double slaClsPoor) {
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
        }
    }
}