package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BpmHtmlReportRenderer — HTML generation, XSS safety, charts, panels.
 */
@DisplayName("BpmHtmlReportRenderer")
class BpmHtmlReportRendererTest {

    @Test
    @DisplayName("Basic markdown renders to valid HTML")
    void basicRender() {
        String html = BpmHtmlReportRenderer.render("## Hello\n\nWorld", "TestProvider");
        assertTrue(html.contains("<html"));
        assertTrue(html.contains("Hello"));
        assertTrue(html.contains("World"));
        assertTrue(html.contains("TestProvider"));
    }

    @Test
    @DisplayName("Provider name with HTML characters is escaped in output")
    void xssEscaped_providerName() {
        String html = BpmHtmlReportRenderer.render("## Test",
                "<script>alert('xss')</script>");
        assertFalse(html.contains("<script>alert"), "Provider name must not appear raw");
        assertTrue(html.contains("\\x3cscript\\x3e"), "Provider should be JS-escaped in bpmMeta");
    }

    @Test
    @DisplayName("Headings with special characters are escaped in sidebar")
    void xssEscaped_sidebarHeadings() {
        String html = BpmHtmlReportRenderer.render(
                "## Section <b>Bold</b>\n\nContent", "Provider");
        assertTrue(html.contains("&lt;b&gt;Bold&lt;/b&gt;") || !html.contains("<b>Bold</b>"),
                "Sidebar heading must be escaped");
    }

    @Test
    @DisplayName("Null markdown throws NullPointerException")
    void nullMarkdown_throws() {
        assertThrows(NullPointerException.class,
                () -> BpmHtmlReportRenderer.render(null, "Provider"));
    }

    @Test
    @DisplayName("Empty markdown renders without error")
    void emptyMarkdown() {
        String html = BpmHtmlReportRenderer.render("", "Provider");
        assertTrue(html.contains("<html"));
    }

    @Test
    @DisplayName("Render with charts includes 6 chart canvases")
    void renderWithCharts() {
        List<BpmTimeBucket> buckets = List.of(
                new BpmTimeBucket(1000L, 80.0, 1500.0, 800.0, 300.0, 0.05, 700, 5),
                new BpmTimeBucket(2000L, 75.0, 1600.0, 850.0, 320.0, 0.08, 730, 5));
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("TestAI", "Scenario", "Desc", "50");
        String html = BpmHtmlReportRenderer.render("## Report\n\nContent", config, buckets);

        assertTrue(html.contains("Chart.js"), "Should include Chart.js");
        assertTrue(html.contains("chartScore"), "Should have score chart");
        assertTrue(html.contains("chartCls"), "Should have CLS chart");
        assertTrue(html.contains("chartRender"), "Should have render time chart");
        assertTrue(html.contains("Performance Trends"), "Should have trends section");
    }

    @Test
    @DisplayName("Render with empty chart buckets omits chart section")
    void renderWithoutCharts() {
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "");
        String html = BpmHtmlReportRenderer.render("## Test", config, Collections.emptyList());
        assertFalse(html.contains("chartScore"), "Should not have charts without data");
    }

    @Test
    @DisplayName("Metadata grid rendered when scenario name provided")
    void metadataGrid() {
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "My Scenario", "Description", "100");
        String html = BpmHtmlReportRenderer.render("## Test", config);
        assertTrue(html.contains("My Scenario"));
        assertTrue(html.contains("metadata-grid"));
    }

    @Test
    @DisplayName("Panel-based navigation with sidebar buttons")
    void panelNavigation() {
        String html = BpmHtmlReportRenderer.render("## Section One\n\nContent1\n\n## Section Two\n\nContent2", "Provider");
        assertTrue(html.contains("data-panel="), "Should have panel navigation");
        assertTrue(html.contains("nav-item"), "Should have sidebar nav items");
        assertTrue(html.contains("panel active"), "First panel should be active");
    }

    @Test
    @DisplayName("Excel export script is included")
    void excelExport() {
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", "Provider");
        assertTrue(html.contains("exportExcel"), "Should include Excel export function");
        assertTrue(html.contains("xlsx"), "Should include SheetJS CDN");
    }

    @Test
    @DisplayName("Subtitle shows provider name and AI disclaimer")
    void subtitlePresent() {
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", "Provider");
        assertTrue(html.contains("Generated by BPM Plugin using Provider"), "Should show BPM Plugin subtitle");
        assertTrue(html.contains("Validate Before Use"), "Should show AI disclaimer");
        assertTrue(html.contains("class=\"sub\""), "Should use sub class");
    }

    @Test
    @DisplayName("Header uses meta-grid layout for metadata")
    void headerMetaGrid() {
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "My Scenario", "",
                        "3", "11/14/23 16:00 - 19:00", "3h 0m 0s", "");
        String html = BpmHtmlReportRenderer.render("## Test", config);
        assertTrue(html.contains("meta-grid"), "Should use meta-grid layout");
        assertTrue(html.contains("My Scenario"), "Should show scenario name");
        assertTrue(html.contains("3h 0m 0s"), "Should show duration");
        assertFalse(html.contains("hm-card"), "Should not use old hm-card layout");
    }

    @Test
    @DisplayName("Title is Browser Performance Metrics Report")
    void pageTitle() {
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", "Provider");
        assertTrue(html.contains("Browser Performance Metrics Report"), "Should have updated title");
        assertFalse(html.contains("JMeter AI Performance Report"), "Should not have old title");
    }

    @Test
    @DisplayName("No AI Provider KPI card in output")
    void noAiProviderKpi() {
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("TestAI", "Scenario", "Desc", "50");
        String html = BpmHtmlReportRenderer.render("## Report\n\nContent", config);
        assertFalse(html.contains("Generated by JAAR"), "Should not have old JAAR subtitle");
        assertTrue(html.contains("Generated by BPM Plugin using TestAI"), "Should have BPM Plugin subtitle");
        assertFalse(html.contains("<div class=\"kpi-label\">AI Provider</div>"),
                "Should not render AI Provider as a KPI card");
    }

    @Test
    @DisplayName("Footer contains timestamp and provider")
    void footer() {
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", "Provider");
        assertTrue(html.contains("footer-rpt"), "Should include footer");
        assertTrue(html.contains("Generated by BPM Plugin on"), "Footer should have generation timestamp");
        assertTrue(html.contains("using Provider"), "Footer should have provider name");
    }

    @Test
    @DisplayName("Performance Metrics panel renders with Transaction Name header and search")
    void metricsPanel() {
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl", "Score"},
                new String[]{"Page A", "10", "85"},
                new String[]{"TOTAL", "10", "85"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "");
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", config,
                Collections.emptyList(), Collections.emptyMap(), metricsTable);
        assertTrue(html.contains("Performance Metrics"), "Should have metrics panel");
        assertTrue(html.contains("Transaction Name"), "Should rename Label to Transaction Name");
        assertTrue(html.contains("Page A"), "Should contain label data");
        assertTrue(html.contains("row-limit"), "Should have pagination controls");
        assertTrue(html.contains("class=\"pager\""), "Should have page navigation");
        assertTrue(html.contains("metricsSearch"), "Should have search filter input");
    }

    @Test
    @DisplayName("SLA Compliance panel renders with Transaction Name header")
    void slaPanel() {
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl", "Score", "Rndr", "Srvr", "Front", "Gap",
                        "Stability", "Headroom", "Area", "FCP", "LCP", "CLS", "TTFB",
                        "Reqs", "Size", "Errs", "Warns"},
                new String[]{"Page A", "10", "95", "500", "30", "200", "100",
                        "Stable", "60%", "None", "800", "1200", "0.050", "400",
                        "5", "100", "0", "0"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "",
                        "", "", "", 0,
                        90, 2500, 1800, 800, 0.10,
                        50, 4000, 3000, 1800, 0.25);
        String html = BpmHtmlReportRenderer.render("## Executive Summary\n\nContent", config,
                Collections.emptyList(), Collections.emptyMap(), metricsTable);
        assertTrue(html.contains("SLA Compliance"), "Should have SLA panel");
        assertTrue(html.contains("Transaction Name"), "Should use Transaction Name header");
        assertTrue(html.contains("sla-pass"), "Should have pass styling");
        assertTrue(html.contains("SLA Thresholds"), "Should have threshold reference");
        assertTrue(html.contains("bpm.properties"), "Should show config file path");
    }

    @Test
    @DisplayName("Critical Findings panel renders for transactions with issues")
    void criticalFindingsPanel() {
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl", "Score", "Rndr", "Srvr", "Front", "Gap",
                        "Stability", "Headroom", "Improvement Area", "FCP", "LCP", "CLS", "TTFB",
                        "Reqs", "Size", "Errs", "Warns"},
                new String[]{"Page A", "10", "45", "500", "30", "200", "100",
                        "Stable", "60%", "Reduce Server Response", "800", "4500", "0.050", "400",
                        "5", "100", "0", "0"},
                new String[]{"Page B", "10", "95", "500", "30", "200", "100",
                        "Stable", "60%", "None", "800", "1200", "0.050", "400",
                        "5", "100", "0", "0"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "",
                        "", "", "", 0,
                        90, 2500, 1800, 800, 0.10,
                        50, 4000, 3000, 1800, 0.25);
        String html = BpmHtmlReportRenderer.render("## Executive Summary\n\nContent", config,
                Collections.emptyList(), Collections.emptyMap(), metricsTable);
        assertTrue(html.contains("Critical Findings"), "Should have Critical Findings panel");
        assertTrue(html.contains("Page A"), "Should include failing transaction");
        assertTrue(html.contains("sla-fail"), "Should have critical severity styling");
        assertTrue(html.contains("Backend processing bottleneck"), "Should have root cause");
        assertTrue(html.contains("Profile backend response time"), "Should have recommended action");
        assertTrue(html.contains("[data-table-id=\"cf\"] td"), "Should have CF auto-sizing CSS");
    }

    @Test
    @DisplayName("Critical Findings shows no-issues message when all pass")
    void criticalFindingsAllPass() {
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl", "Score", "Rndr", "Srvr", "Front", "Gap",
                        "Stability", "Headroom", "Improvement Area", "FCP", "LCP", "CLS", "TTFB",
                        "Reqs", "Size", "Errs", "Warns"},
                new String[]{"Page B", "10", "95", "500", "30", "200", "100",
                        "Stable", "60%", "None", "800", "1200", "0.050", "400",
                        "5", "100", "0", "0"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "",
                        "", "", "", 0,
                        90, 2500, 1800, 800, 0.10,
                        50, 4000, 3000, 1800, 0.25);
        String html = BpmHtmlReportRenderer.render("## Executive Summary\n\nContent", config,
                Collections.emptyList(), Collections.emptyMap(), metricsTable);
        assertTrue(html.contains("No critical issues detected"), "Should show all-pass message");
    }

    @Test
    @DisplayName("Score chart uses green SLA line, LCP uses red")
    void scoreSlaGreenLine() {
        List<BpmTimeBucket> buckets = List.of(
                new BpmTimeBucket(1000L, 80.0, 1500.0, 800.0, 300.0, 5));
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "",
                        "", "", "", 0,
                        90, 2500, 1800, 800, 0.10,
                        50, 4000, 3000, 1800, 0.25);
        String html = BpmHtmlReportRenderer.render("## Test", config, buckets);
        assertTrue(html.contains("slaScoreGood"), "Score should use good threshold");
        assertTrue(html.contains("'green'"), "Score SLA line should be green");
    }

    @Test
    @DisplayName("Panel order: Executive Summary → Metrics → Trends → SLA → Critical Findings → AI panels")
    void panelOrder() {
        List<BpmTimeBucket> buckets = List.of(
                new BpmTimeBucket(1000L, 80.0, 1500.0, 800.0, 300.0, 5));
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl", "Score"},
                new String[]{"Page A", "10", "85"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "");
        String html = BpmHtmlReportRenderer.render("## Executive Summary\n\nContent\n\n## Risk Assessment\n\nRisks here",
                config, buckets, Collections.emptyMap(), metricsTable);

        // Check panel order via data-title attributes in panel divs
        int execIdx = html.indexOf("data-title=\"Executive Summary\"");
        int metricsIdx = html.indexOf("data-title=\"Performance Metrics\"");
        int trendsIdx = html.indexOf("data-title=\"Performance Trends\"");
        int cfIdx = html.indexOf("data-title=\"Critical Findings\"");
        int riskIdx = html.indexOf("data-title=\"Risk Assessment\"");

        assertTrue(execIdx > 0, "Executive Summary panel should exist");
        assertTrue(metricsIdx > 0, "Performance Metrics panel should exist");
        assertTrue(trendsIdx > 0, "Performance Trends panel should exist");
        assertTrue(cfIdx > 0, "Critical Findings panel should exist");
        assertTrue(riskIdx > 0, "Risk Assessment panel should exist");
        assertTrue(execIdx < metricsIdx, "Executive Summary should come before Metrics");
        assertTrue(metricsIdx < trendsIdx, "Metrics should come before Trends");
        assertTrue(trendsIdx < cfIdx, "Trends should come before Critical Findings");
        assertTrue(cfIdx < riskIdx, "Critical Findings should come before Risk Assessment");
    }

    @Test
    @DisplayName("Table script includes pagination and sorting")
    void tableScript() {
        List<String[]> metricsTable = List.of(
                new String[]{"Label", "Smpl"},
                new String[]{"Page A", "10"});
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "");
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", config,
                Collections.emptyList(), Collections.emptyMap(), metricsTable);
        assertTrue(html.contains("initTable"), "Should include table init script");
        assertTrue(html.contains("buildPager"), "Should include page-based pagination");
        assertTrue(html.contains("sort-asc"), "Should include sort indicator CSS");
        assertTrue(html.contains("class=\"pager\""), "Should have pager div");
    }

    @Test
    @DisplayName("Print CSS is included")
    void printCss() {
        String html = BpmHtmlReportRenderer.render("## Test\n\nContent", "Provider");
        assertTrue(html.contains("@media print"), "Should include print styles");
    }

    @Test
    @DisplayName("Chart filter uses Transaction Name label and All Transactions option")
    void chartFilterTransactionName() {
        List<BpmTimeBucket> globalBuckets = List.of(
                new BpmTimeBucket(1000L, 80.0, 1500.0, 800.0, 300.0, 0.05, 700, 5));
        java.util.Map<String, List<BpmTimeBucket>> perLabel = new java.util.LinkedHashMap<>();
        perLabel.put("Login", List.of(new BpmTimeBucket(1000L, 80.0, 1500.0, 800.0, 300.0, 0.05, 700, 5)));
        perLabel.put("Home", List.of(new BpmTimeBucket(1000L, 85.0, 1200.0, 700.0, 250.0, 0.03, 600, 5)));
        BpmHtmlReportRenderer.RenderConfig config =
                new BpmHtmlReportRenderer.RenderConfig("AI", "", "", "");
        String html = BpmHtmlReportRenderer.render("## Test", config, globalBuckets, perLabel);
        assertTrue(html.contains("Transaction Name:"), "Filter label should be Transaction Name");
        assertTrue(html.contains("All Transactions"), "Default option should be All Transactions");
        assertFalse(html.contains(">All Pages<"), "Should not have old All Pages label");
    }
}
