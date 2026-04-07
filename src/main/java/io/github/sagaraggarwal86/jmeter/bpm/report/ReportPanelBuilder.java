package io.github.sagaraggarwal86.jmeter.bpm.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.*;
import static io.github.sagaraggarwal86.jmeter.bpm.util.HtmlUtils.escapeHtml;
import static io.github.sagaraggarwal86.jmeter.bpm.util.HtmlUtils.severityTag;

/**
 * Builds the Executive Summary and Risk Assessment HTML panels
 * from pre-computed {@link ReportData} and HTML templates.
 *
 * <p>Templates are loaded from classpath resources. Placeholders
 * ({@code {{key}}}) are replaced with computed values. Conditional
 * blocks ({@code {{#section}}...{{/section}}}) are included or
 * removed based on data availability.</p>
 */
public final class ReportPanelBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportPanelBuilder.class);

    /**
     * Maximum breach entries shown in Executive Summary (rest get "and X more" note).
     */
    private static final int MAX_BREACH_DISPLAY = 10;
    /**
     * Maximum error labels shown in Executive Summary.
     */
    private static final int MAX_ERROR_DISPLAY = 5;
    /**
     * Maximum risk entries per card in Risk Assessment.
     */
    private static final int MAX_RISK_DISPLAY = 10;

    // Cached templates — loaded once from classpath
    private static final String TEMPLATE_EXECUTIVE_SUMMARY = loadTemplate("bpm-executive-summary.html");
    private static final String TEMPLATE_RISK_ASSESSMENT = loadTemplate("bpm-risk-assessment.html");

    // Plain-language improvement area descriptions (manager/architect audience)
    private static final Map<String, String> IMPROVEMENT_DESCRIPTIONS = Map.of(
            BOTTLENECK_SERVER, "The primary bottleneck is slow server response \u2014 reducing server-side processing time would have the most impact.",
            BOTTLENECK_RESOURCE, "The page is loading heavy assets \u2014 optimising image sizes and deferring non-critical scripts would improve load times.",
            BOTTLENECK_CLIENT, "The browser is spending excessive time rendering \u2014 simplifying the page structure or reducing JavaScript work would help.",
            BOTTLENECK_LAYOUT, "The page has excessive DOM complexity \u2014 reducing nested elements would improve rendering performance.",
            BOTTLENECK_RELIABILITY, "There are failed or blocked network requests \u2014 checking resource availability and CDN health is recommended.",
            BOTTLENECK_NONE, "No single bottleneck was identified \u2014 performance is generally balanced across all areas."
    );

    private ReportPanelBuilder() {
    }

    // ── Executive Summary ───────────────────────────────────────────────────

    /**
     * Builds the Executive Summary panel HTML from report data and template.
     * The KPI cards are rendered separately (passed as pre-built HTML).
     *
     * @param data         pre-computed report data
     * @param kpiCardsHtml pre-rendered KPI cards HTML (from appendMetadataKpi)
     * @param scoreGood    SLA score "good" threshold (for explanation text)
     * @param scorePoor    SLA score "poor" threshold (for explanation text)
     * @return complete Executive Summary panel HTML
     */
    public static String buildExecutiveSummary(ReportData data, String kpiCardsHtml,
                                               int scoreGood, int scorePoor) {
        if (TEMPLATE_EXECUTIVE_SUMMARY == null) {
            log.warn("Executive summary template not available, falling back to basic rendering");
            return buildFallbackExecutiveSummary(data);
        }
        String template = TEMPLATE_EXECUTIVE_SUMMARY;

        template = template.replace("{{kpiCards}}", kpiCardsHtml);

        // ── Overall Assessment: 5 conditional paragraphs ───────────────────
        template = template.replace("{{healthParagraph}}", buildHealthParagraph(data, scoreGood, scorePoor));

        // Trend paragraph (conditional)
        TrendData trends = data.trends();
        if (trends != null) {
            template = keepSection(template, "trendParagraph");
            template = template.replace("{{trendParagraph}}", buildTrendParagraph(trends));
        } else {
            template = removeSection(template, "trendParagraph");
        }

        // Risk paragraph (conditional)
        boolean hasRisks = !data.headroomRisks().isEmpty() || !data.boundaryRisks().isEmpty()
                || !data.spaLabels().isEmpty();
        if (hasRisks) {
            template = keepSection(template, "riskParagraph");
            template = template.replace("{{riskParagraph}}", buildRiskParagraph(data));
        } else {
            template = removeSection(template, "riskParagraph");
        }

        // Actions paragraph (conditional — any breaches)
        if (!data.breaches().isEmpty()) {
            template = keepSection(template, "actionsParagraph");
            template = template.replace("{{actionsParagraph}}", buildActionsParagraph(data));
        } else {
            template = removeSection(template, "actionsParagraph");
        }

        // Errors paragraph (conditional)
        if (data.totalErrors() > 0) {
            template = keepSection(template, "errorsParagraph");
            template = template.replace("{{errorsParagraph}}", buildErrorsParagraph(data));
        } else {
            template = removeSection(template, "errorsParagraph");
        }

        // ── Breach section (expandable) ────────────────────────────────────
        if (data.breaches().isEmpty()) {
            template = removeSection(template, "breachSection");
        } else {
            template = keepSection(template, "breachSection");
            template = template.replace("{{breachIntro}}", buildBreachIntro(data));
            template = template.replace("{{breachSummaryLabel}}", buildBreachSummaryLabel(data));
            template = template.replace("{{breachItems}}", buildBreachItemsHtml(data));
            template = template.replace("{{breachMoreNote}}",
                    data.breaches().size() > MAX_BREACH_DISPLAY
                            ? "<p class=\"es-more-note\">and " + (data.breaches().size() - MAX_BREACH_DISPLAY)
                              + " more \u2014 see Critical Findings for the complete list.</p>"
                            : "");
        }

        // ── Error section (expandable) ─────────────────────────────────────
        if (data.totalErrors() == 0) {
            template = removeSection(template, "errorSection");
        } else {
            template = keepSection(template, "errorSection");
            template = template.replace("{{errorIntro}}", buildErrorIntro(data));
            template = template.replace("{{errorSummaryLabel}}", buildErrorSummaryLabel(data));
            template = template.replace("{{errorItems}}", buildErrorItemsHtml(data));
            template = template.replace("{{errorMoreNote}}", "");
        }

        return template;
    }

    // ── Overall Assessment paragraphs ───────────────────────────────────────

    private static String buildHealthParagraph(ReportData data, int scoreGood, int scorePoor) {
        StringBuilder sb = new StringBuilder("<p>");

        if (data.criticalCount() == 0 && data.warnCount() == 0) {
            // All-pass scenario — descriptive
            sb.append("The application is performing well. All ")
                    .append(data.totalLabels())
                    .append(" pages met their quality targets, and users should experience fast, ")
                    .append("responsive page loads across the board.");
            if (data.weightedScore() > 0) {
                sb.append(" Overall, the application scores <strong>")
                        .append(data.weightedScore()).append("</strong> out of 100.");
            }
            // Add best page context if available
            if (!data.bestLabel().isEmpty() && data.bestLcp() > 0) {
                String lcpDisplay = data.bestLcp() >= 1000
                        ? String.format(Locale.US, "%.1f seconds", data.bestLcp() / 1000.0)
                        : data.bestLcp() + " milliseconds";
                sb.append(" The fastest page loaded in ").append(lcpDisplay);
                if (!data.worstLabel().isEmpty() && data.worstLcp() > 0) {
                    sb.append(", and even the slowest page completed within acceptable limits");
                }
                sb.append(".");
            }
            if (data.totalErrors() == 0) {
                sb.append(" No JavaScript errors were detected, and no pages are operating close to their quality limits.");
            }
        } else {
            // Mixed or poor results
            String verdict;
            if (data.criticalCount() > data.totalLabels() / 2) {
                verdict = "is not meeting performance expectations";
            } else if (data.criticalCount() > 0) {
                verdict = "has a mixed performance profile";
            } else {
                verdict = "is performing adequately but has room for improvement";
            }
            sb.append("The application ").append(verdict).append(". ");

            if (data.passCount() > 0) {
                sb.append("While ").append(data.passCount() > 1 ? data.passCount() + " pages are" : "1 page is")
                        .append(" delivering a good user experience, ");
            }
            int failingCount = data.criticalCount() + data.warnCount();
            sb.append(failingCount).append(failingCount == 1 ? " page" : " pages")
                    .append(" fell short of quality targets and need").append(failingCount == 1 ? "s" : "")
                    .append(" attention.");

            if (data.weightedScore() > 0) {
                sb.append(" Overall, the application scores <strong>")
                        .append(data.weightedScore()).append("</strong> out of 100, placing it in the ");
                if (data.weightedScore() >= scoreGood) {
                    sb.append("\u201Cgood\u201D range.");
                } else if (data.weightedScore() >= scorePoor) {
                    sb.append("\u201Cneeds work\u201D range.");
                } else {
                    sb.append("\u201Cpoor\u201D range.");
                }
            }
        }

        sb.append("</p>");
        return sb.toString();
    }

    private static String buildTrendParagraph(TrendData trends) {
        StringBuilder sb = new StringBuilder("<p>");
        String stability = trends.overallStability();

        sb.append(trendStabilityText(stability));

        sb.append("</p>");
        return sb.toString();
    }

    private static String buildRiskParagraph(ReportData data) {
        StringBuilder sb = new StringBuilder("<p>");
        boolean first = true;

        if (!data.headroomRisks().isEmpty()) {
            sb.append(data.headroomRisks().size())
                    .append(data.headroomRisks().size() == 1 ? " page is" : " pages are")
                    .append(" operating close to ")
                    .append(data.headroomRisks().size() == 1 ? "its" : "their")
                    .append(" quality limits. ")
                    .append("A modest increase in traffic or a minor code change could push ")
                    .append(data.headroomRisks().size() == 1 ? "it" : "them")
                    .append(" into failure.");
            first = false;
        }

        if (!data.spaLabels().isEmpty()) {
            if (!first) sb.append(" Additionally, ");
            sb.append(data.spaLabels().size())
                    .append(data.spaLabels().size() == 1 ? " page involves" : " pages involve")
                    .append(" an in-app navigation where standard performance metrics cannot be captured, ")
                    .append("creating a monitoring blind spot.");
            first = false;
        }

        if (!data.boundaryRisks().isEmpty() && first) {
            sb.append(data.boundaryRisks().size())
                    .append(data.boundaryRisks().size() == 1 ? " page is" : " pages are")
                    .append(" near a quality threshold boundary. ")
                    .append("A small improvement could upgrade ")
                    .append(data.boundaryRisks().size() == 1 ? "its" : "their")
                    .append(" status; a small regression could downgrade ")
                    .append(data.boundaryRisks().size() == 1 ? "it" : "them")
                    .append(".");
        }

        sb.append("</p>");
        return sb.toString();
    }

    private static String buildActionsParagraph(ReportData data) {
        // Identify the most common bottleneck across breaches
        Map<String, Integer> bottleneckCounts = new java.util.LinkedHashMap<>();
        for (ReportData.BreachEntry breach : data.breaches()) {
            if (breach.improvementArea() != null && !breach.improvementArea().isEmpty()
                    && !BOTTLENECK_NONE.equals(breach.improvementArea())) {
                bottleneckCounts.merge(breach.improvementArea(), 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder("<p>");
        if (bottleneckCounts.isEmpty()) {
            sb.append("No single bottleneck pattern was identified across the affected pages. ")
                    .append("See the Critical Findings section for per-page recommendations.");
        } else {
            // Find the most common bottleneck
            String topBottleneck = bottleneckCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(BOTTLENECK_NONE);
            int count = bottleneckCounts.getOrDefault(topBottleneck, 0);

            String desc = IMPROVEMENT_DESCRIPTIONS.getOrDefault(topBottleneck, "");
            if (!desc.isEmpty()) {
                sb.append("The primary bottleneck across affected pages is: ").append(desc.toLowerCase());
                if (count > 1) {
                    sb.append(" This pattern affects ").append(count).append(" pages.");
                }
            }
        }
        sb.append("</p>");
        return sb.toString();
    }

    private static String buildErrorsParagraph(ReportData data) {
        int labelCount = data.totalErrorLabels();
        return "<p>JavaScript errors were detected on " + labelCount + " out of " + data.totalLabels()
                + " pages during the test. These errors may indicate broken functionality or "
                + "failed background requests that are visible to end users.</p>";
    }

    // ── Breach section ──────────────────────────────────────────────────────

    private static String buildBreachIntro(ReportData data) {
        return data.breaches().size() + " out of " + data.totalLabels()
                + " pages did not meet one or more quality targets.";
    }

    private static String buildBreachSummaryLabel(ReportData data) {
        long critCount = data.breaches().stream().filter(ReportData.BreachEntry::hasCritical).count();
        long warnCount = data.breaches().size() - critCount;
        StringBuilder sb = new StringBuilder();
        sb.append(data.breaches().size()).append(data.breaches().size() == 1 ? " page needs" : " pages need")
                .append(" attention");
        if (critCount > 0 && warnCount > 0) {
            sb.append(" (").append(critCount).append(" critical, ").append(warnCount).append(" warning)");
        } else if (critCount > 0) {
            sb.append(" (").append(critCount).append(" critical)");
        } else {
            sb.append(" (").append(warnCount).append(" warning)");
        }
        sb.append(" \u2014 show details");
        return sb.toString();
    }

    private static String buildBreachItemsHtml(ReportData data) {
        List<ReportData.BreachEntry> capped = data.breaches().size() > MAX_BREACH_DISPLAY
                ? data.breaches().subList(0, MAX_BREACH_DISPLAY) : data.breaches();
        Map<String, List<ReportData.BreachEntry>> groups = ReportData.groupByArea(capped);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ReportData.BreachEntry>> entry : groups.entrySet()) {
            String area = entry.getKey();
            List<ReportData.BreachEntry> pages = entry.getValue();
            String displayName = BOTTLENECK_DISPLAY_NAMES.getOrDefault(area, area);
            String desc = IMPROVEMENT_DESCRIPTIONS.getOrDefault(area,
                    IMPROVEMENT_DESCRIPTIONS.get(BOTTLENECK_NONE));

            sb.append("    <li class=\"es-breach-item\">\n");
            sb.append("      <p><strong>").append(escapeHtml(displayName)).append("</strong> (")
                    .append(pages.size()).append(pages.size() == 1 ? " page" : " pages")
                    .append(") \u2014 ").append(desc).append("</p>\n");

            // Nested expandable page list
            sb.append("      <details class=\"es-breach-pages\">\n");
            sb.append("        <summary>").append(pages.size())
                    .append(" affected page").append(pages.size() != 1 ? "s" : "")
                    .append(" \u2014 show list</summary>\n");
            sb.append("        <ul>\n");
            for (ReportData.BreachEntry breach : pages) {
                sb.append("          <li><strong>").append(escapeHtml(breach.label())).append("</strong> ")
                        .append(severityTag(breach.hasCritical())).append("</li>\n");
            }
            sb.append("        </ul>\n");
            sb.append("      </details>\n");
            sb.append("    </li>\n");
        }
        return sb.toString();
    }

    // ── Error section ───────────────────────────────────────────────────────

    private static String buildErrorIntro(ReportData data) {
        int labelCount = data.totalErrorLabels();
        return data.totalErrors() + " JavaScript error" + (data.totalErrors() > 1 ? "s were" : " was")
                + " detected across " + labelCount + " page" + (labelCount > 1 ? "s" : "")
                + ". These errors may indicate broken functionality or failed background "
                + "requests that are visible to end users.";
    }

    private static String buildErrorSummaryLabel(ReportData data) {
        return data.totalErrorLabels() + " affected page"
                + (data.totalErrorLabels() != 1 ? "s" : "") + " \u2014 show details";
    }

    private static String buildErrorItemsHtml(ReportData data) {
        StringBuilder sb = new StringBuilder();
        List<ReportData.ErrorEntry> entries = data.topJsErrors();
        int maxCount = entries.isEmpty() ? 1 : entries.get(0).count();

        for (int i = 0; i < entries.size(); i++) {
            ReportData.ErrorEntry entry = entries.get(i);
            sb.append("    <li><strong>").append(escapeHtml(entry.label())).append("</strong> \u2014 ");

            if (i == 0) {
                sb.append("highest concentration of errors");
                if (!data.worstLabel().isEmpty() && entry.label().equals(data.worstLabel())) {
                    sb.append(". This aligns with its poor performance, suggesting a shared root cause");
                }
            } else if (entries.size() == 1) {
                sb.append("all errors concentrated on this single page");
            } else {
                // Relative frequency based on ratio to highest
                double ratio = maxCount > 0 ? (double) entry.count() / maxCount : 0;
                if (ratio >= 0.6) {
                    sb.append("errors detected at a high frequency");
                } else if (ratio >= 0.3) {
                    sb.append("errors detected at a moderate frequency");
                } else {
                    sb.append("errors detected at a lower frequency");
                }
            }
            sb.append(".</li>\n");
        }

        if (entries.size() < data.totalErrorLabels()) {
            sb.append("    <li class=\"es-more-note\"><em>and ")
                    .append(data.totalErrorLabels() - entries.size())
                    .append(" more pages with errors \u2014 see Performance Metrics for the complete list.</em></li>\n");
        }
        return sb.toString();
    }

    /**
     * Shared trend stability prose — used in both Overall Assessment and Risk Assessment.
     */
    private static String trendStabilityText(String stability) {
        return switch (stability) {
            case TREND_STABLE -> "Performance remained stable throughout the test duration. "
                    + "There are no signs of degradation under sustained load, "
                    + "which indicates the application handles sustained load at the tested level.";
            case TREND_MOSTLY_STABLE -> "Performance was mostly stable, but one metric showed signs of decline "
                    + "over the test duration. This warrants monitoring but does not indicate a systemic issue.";
            case TREND_DEGRADING -> "Performance deteriorated as the test progressed. "
                    + "Metrics worsened over the test duration, which suggests "
                    + "the system may not sustain this load level over longer periods.";
            default -> "";
        };
    }

    private static String buildFallbackExecutiveSummary(ReportData data) {
        return "<p>Test covered " + data.totalLabels() + " pages across "
                + data.totalSamples() + " page loads. "
                + data.passCount() + " passing, "
                + data.criticalCount() + " critical, "
                + data.warnCount() + " warnings.</p>";
    }

    // ── Risk Assessment ─────────────────────────────────────────────────────

    /**
     * Builds the Risk Assessment panel HTML from report data and template.
     *
     * @param data pre-computed report data
     * @return complete Risk Assessment panel HTML
     */
    public static String buildRiskAssessment(ReportData data) {
        if (TEMPLATE_RISK_ASSESSMENT == null) {
            log.warn("Risk assessment template not available, falling back to basic rendering");
            return buildFallbackRiskAssessment(data);
        }
        String template = TEMPLATE_RISK_ASSESSMENT;

        // ── Capacity Risks (headroom) ───────────────────────────────────────
        boolean hasCapacity = !data.headroomRisks().isEmpty();
        template = template.replace("{{capacitySeverity}}", hasCapacity ? "red" : "green");
        template = template.replace("{{capacityCount}}",
                data.headroomRisks().size() + " page" + (data.headroomRisks().size() != 1 ? "s" : ""));

        if (hasCapacity) {
            template = keepSection(template, "capacityHasRisks");
            template = removeSection(template, "capacityAllClear");
            template = template.replace("{{capacityItems}}", buildRiskItemsHtml(data.headroomRisks()));
            template = template.replace("{{capacityMoreNote}}",
                    data.headroomRisks().size() > MAX_RISK_DISPLAY
                            ? "<p class=\"ra-more-note\">and " + (data.headroomRisks().size() - MAX_RISK_DISPLAY) + " more.</p>"
                            : "");
        } else {
            template = removeSection(template, "capacityHasRisks");
            template = keepSection(template, "capacityAllClear");
        }

        // ── Borderline Pages (boundary risks) ──────────────────────────────
        boolean hasBorderline = !data.boundaryRisks().isEmpty();
        template = template.replace("{{borderlineSeverity}}", hasBorderline ? "yellow" : "green");
        template = template.replace("{{borderlineCount}}",
                data.boundaryRisks().size() + " page" + (data.boundaryRisks().size() != 1 ? "s" : ""));

        if (hasBorderline) {
            template = keepSection(template, "borderlineHasRisks");
            template = removeSection(template, "borderlineAllClear");
            template = template.replace("{{borderlineItems}}", buildRiskItemsHtml(data.boundaryRisks()));
            template = template.replace("{{borderlineMoreNote}}",
                    data.boundaryRisks().size() > MAX_RISK_DISPLAY
                            ? "<p class=\"ra-more-note\">and " + (data.boundaryRisks().size() - MAX_RISK_DISPLAY) + " more.</p>"
                            : "");
        } else {
            template = removeSection(template, "borderlineHasRisks");
            template = keepSection(template, "borderlineAllClear");
        }

        // ── Unmeasured Navigations (SPA) ────────────────────────────────────
        boolean hasSpa = !data.spaLabels().isEmpty();
        template = template.replace("{{spaSeverity}}", hasSpa ? "yellow" : "green");
        template = template.replace("{{spaCount}}",
                data.spaLabels().size() + " page" + (data.spaLabels().size() != 1 ? "s" : ""));

        if (hasSpa) {
            template = keepSection(template, "spaHasLabels");
            template = removeSection(template, "spaAllClear");
            StringBuilder spaItems = new StringBuilder();
            for (String label : data.spaLabels()) {
                spaItems.append("        <li><strong>").append(escapeHtml(label))
                        .append("</strong> \u2014 performance problems on this page would go undetected by this test.</li>\n");
            }
            template = template.replace("{{spaItems}}", spaItems.toString());
        } else {
            template = removeSection(template, "spaHasLabels");
            template = keepSection(template, "spaAllClear");
        }

        // ── Performance Trend ───────────────────────────────────────────────
        TrendData trends = data.trends();
        boolean hasTrend = trends != null;

        if (hasTrend) {
            String stability = trends.overallStability();
            String severity = TREND_DEGRADING.equals(stability) ? "red"
                    : TREND_MOSTLY_STABLE.equals(stability) ? "yellow" : "green";
            template = template.replace("{{trendSeverity}}", severity);
            template = template.replace("{{trendBadge}}", stability);

            template = keepSection(template, "trendAvailable");
            template = removeSection(template, "trendNoData");

            template = template.replace("{{trendStabilityText}}", trendStabilityText(stability));

            if (!trends.alerts().isEmpty()) {
                template = keepSection(template, "trendHasAlerts");
                StringBuilder alertItems = new StringBuilder();
                for (String alert : trends.alerts()) {
                    alertItems.append("        <li>").append(escapeHtml(alert)).append("</li>\n");
                }
                template = template.replace("{{trendAlertItems}}", alertItems.toString());
            } else {
                template = removeSection(template, "trendHasAlerts");
            }
        } else {
            template = template.replace("{{trendSeverity}}", "yellow");
            template = template.replace("{{trendBadge}}", VERDICT_NA);
            template = removeSection(template, "trendAvailable");
            template = keepSection(template, "trendNoData");
        }

        return template;
    }

    private static String buildRiskItemsHtml(List<ReportData.RiskEntry> risks) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (ReportData.RiskEntry risk : risks) {
            if (count >= MAX_RISK_DISPLAY) break;
            sb.append("        <li><strong>").append(escapeHtml(risk.label()))
                    .append("</strong> \u2014 ").append(escapeHtml(risk.description()))
                    .append("</li>\n");
            count++;
        }
        return sb.toString();
    }

    private static String buildFallbackRiskAssessment(ReportData data) {
        return "<p>" + data.headroomRisks().size() + " capacity risks, "
                + data.boundaryRisks().size() + " borderline pages, "
                + data.spaLabels().size() + " unmeasured navigations.</p>";
    }

    // ── Template utilities ───────────────────────────────────────────────────

    /**
     * Loads an HTML template from the classpath.
     */
    static String loadTemplate(String resourceName) {
        try (InputStream is = ReportPanelBuilder.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (is == null) {
                log.warn("Template resource not found: {}", resourceName);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            log.warn("Failed to load template: {}", resourceName, e);
            return null;
        }
    }

    /**
     * Removes a conditional section block from the template.
     * Removes everything between {{#name}} and {{/name}} inclusive.
     */
    static String removeSection(String template, String sectionName) {
        String startTag = "{{#" + sectionName + "}}";
        String endTag = "{{/" + sectionName + "}}";
        int start = template.indexOf(startTag);
        int end = template.indexOf(endTag);
        if (start >= 0 && end >= 0) {
            return template.substring(0, start) + template.substring(end + endTag.length());
        }
        return template;
    }

    /**
     * Keeps a conditional section block — just strips the markers.
     */
    static String keepSection(String template, String sectionName) {
        return template.replace("{{#" + sectionName + "}}", "")
                .replace("{{/" + sectionName + "}}", "");
    }

}
