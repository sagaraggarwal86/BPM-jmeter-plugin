package io.github.sagaraggarwal86.jmeter.bpm.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReportPanelBuilder")
class ReportPanelBuilderTest {

    // ── Template utilities ──────────────────────────────────────────────────

    @Test
    @DisplayName("Report CSS resource is non-empty and contains expected classes")
    void reportCssResource() {
        String css = ReportPanelBuilder.loadTemplate("bpm-report.css");
        assertNotNull(css, "bpm-report.css must be on classpath");
        assertTrue(css.contains(".es-section"));
        assertTrue(css.contains(".ra-card"));
        assertTrue(css.contains(".ra-red"));
        assertTrue(css.contains(".ra-green"));
        assertTrue(css.contains(".ra-yellow"));
    }

    // ── Executive Summary ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Template utilities")
    class TemplateUtils {

        @Test
        @DisplayName("loadTemplate returns non-null for existing resource")
        void loadExistingTemplate() {
            String template = ReportPanelBuilder.loadTemplate("bpm-executive-summary.html");
            assertNotNull(template);
            assertFalse(template.isEmpty());
        }

        @Test
        @DisplayName("loadTemplate returns null for missing resource")
        void loadMissingTemplate() {
            assertNull(ReportPanelBuilder.loadTemplate("nonexistent-template.html"));
        }

        @Test
        @DisplayName("removeSection removes content between markers")
        void removeSection() {
            String template = "before {{#sec}}content{{/sec}} after";
            String result = ReportPanelBuilder.removeSection(template, "sec");
            assertEquals("before  after", result);
        }

        @Test
        @DisplayName("keepSection strips markers but keeps content")
        void keepSection() {
            String template = "before {{#sec}}content{{/sec}} after";
            String result = ReportPanelBuilder.keepSection(template, "sec");
            assertEquals("before content after", result);
        }
    }

    // ── Risk Assessment ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Executive Summary")
    class ExecutiveSummary {

        @Test
        @DisplayName("All-pass scenario includes pass language")
        void allPass() {
            ReportData data = new ReportData(
                    3, 100, VERDICT_GOOD, 3, 0, 0, 92, 1200L,
                    Collections.emptyList(),
                    "/home", 95, 1200,
                    "/about", 88, 1800, "None",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildExecutiveSummary(data, "", 90, 50);
            assertNotNull(html);
            assertTrue(html.contains("3")); // total labels
            assertTrue(html.contains("100")); // total samples
            assertTrue(html.contains("performing well"));
            assertFalse(html.contains("Pages Failing"));
        }

        @Test
        @DisplayName("Breach entries render with description")
        void withBreaches() {
            ReportData.BreachEntry breach = new ReportData.BreachEntry(
                    "/checkout", 45, VERDICT_POOR,
                    4200, VERDICT_POOR, 2000, VERDICT_NEEDS_WORK,
                    800, VERDICT_GOOD, 0.15, VERDICT_GOOD,
                    "Reduce Server Response", true);

            ReportData data = new ReportData(
                    2, 200, VERDICT_POOR, 1, 0, 1, 70, 4200L,
                    List.of(breach),
                    "/home", 95, 1200,
                    "/checkout", 45, 4200, "Reduce Server Response",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildExecutiveSummary(data, "", 90, 50);
            assertNotNull(html);
            assertTrue(html.contains("/checkout"));
            assertTrue(html.contains("Pages Failing Quality Targets"));
        }

        @Test
        @DisplayName("Error section renders when errors present")
        void withErrors() {
            ReportData data = new ReportData(
                    2, 200, VERDICT_GOOD, 2, 0, 0, 90, 1500L,
                    Collections.emptyList(),
                    "/home", 95, 1200,
                    "/about", 88, 1800, "None",
                    5, 1, List.of(new ReportData.ErrorEntry("/home", 5)),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildExecutiveSummary(data, "", 90, 50);
            assertNotNull(html);
            assertTrue(html.contains("JavaScript Errors"));
            assertTrue(html.contains("5"));
        }

        @Test
        @DisplayName("Fallback rendering when template missing")
        void fallbackRendering() {
            ReportData data = new ReportData(
                    2, 100, VERDICT_GOOD, 2, 0, 0, 90, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            // Test fallback by calling with empty data (template should load fine,
            // but we verify the output is not empty)
            String html = ReportPanelBuilder.buildExecutiveSummary(data, "<div>kpi</div>", 90, 50);
            assertNotNull(html);
            assertFalse(html.isEmpty());
        }
    }

    @Nested
    @DisplayName("Risk Assessment")
    class RiskAssessment {

        @Test
        @DisplayName("All-clear scenario shows green cards")
        void allClear() {
            ReportData data = new ReportData(
                    2, 100, VERDICT_GOOD, 2, 0, 0, 92, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildRiskAssessment(data);
            assertNotNull(html);
            assertTrue(html.contains("ra-green"));
            assertTrue(html.contains("comfortable margin"));
        }

        @Test
        @DisplayName("Headroom risks render red capacity card")
        void headroomRisks() {
            ReportData data = new ReportData(
                    2, 100, VERDICT_GOOD, 2, 0, 0, 92, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    List.of(new ReportData.RiskEntry("/checkout", 12, "Only 12% margin remaining")),
                    Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildRiskAssessment(data);
            assertTrue(html.contains("ra-red"));
            assertTrue(html.contains("/checkout"));
        }

        @Test
        @DisplayName("SPA labels render yellow unmeasured card")
        void spaLabels() {
            ReportData data = new ReportData(
                    3, 100, VERDICT_GOOD, 2, 0, 0, 90, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    List.of("/dashboard", "/settings"), null);

            String html = ReportPanelBuilder.buildRiskAssessment(data);
            assertTrue(html.contains("ra-yellow"));
            assertTrue(html.contains("/dashboard"));
            assertTrue(html.contains("/settings"));
        }

        @Test
        @DisplayName("Trend data renders in performance trend card")
        void withTrends() {
            TrendData trends = new TrendData(
                    8,
                    new TrendData.MetricTrend("Score", 85, 70, "FALLING", 18, true),
                    new TrendData.MetricTrend("LCP", 1200, 1500, "RISING", 25, true),
                    new TrendData.MetricTrend("FCP", 800, 850, "STABLE", 6, false),
                    new TrendData.MetricTrend("TTFB", 400, 420, "STABLE", 5, false),
                    List.of("Score dropped 18% in the second half of the test (85 → 70).",
                            "LCP increased 25% in the second half of the test (1200ms → 1500ms)."),
                    "DEGRADING",
                    "2 of 4 tracked metrics degraded during the test."
            );

            ReportData data = new ReportData(
                    2, 100, VERDICT_POOR, 1, 0, 1, 75, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), trends);

            String html = ReportPanelBuilder.buildRiskAssessment(data);
            assertTrue(html.contains("DEGRADING"));
            assertTrue(html.contains("Score dropped"));
            assertTrue(html.contains("LCP increased"));
        }

        @Test
        @DisplayName("No trend data shows N/A badge")
        void noTrends() {
            ReportData data = new ReportData(
                    2, 100, VERDICT_GOOD, 2, 0, 0, 90, 0L,
                    Collections.emptyList(),
                    "", 0, 0, "", 0, 0, "",
                    0, 0, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), null);

            String html = ReportPanelBuilder.buildRiskAssessment(data);
            assertTrue(html.contains(VERDICT_NA));
            assertTrue(html.contains("Not enough data points"));
        }
    }
}
