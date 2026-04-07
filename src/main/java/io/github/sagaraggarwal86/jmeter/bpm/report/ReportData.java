package io.github.sagaraggarwal86.jmeter.bpm.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.BOTTLENECK_NONE;

/**
 * Pre-computed report data for HTML report generation.
 *
 * <p>Built by {@link ReportDataBuilder} from per-label aggregates and SLA thresholds.
 * Contains all verdicts, risks, and summaries needed by the Executive Summary
 * and Risk Assessment panels.</p>
 */
public record ReportData(
        // ── Summary ─────────────────────────────────────────────────────────
        int totalLabels,
        int totalSamples,
        String overallVerdict,     // GOOD, NEEDS_WORK, POOR, N/A
        int passCount,
        int warnCount,
        int criticalCount,
        int weightedScore,
        long weightedLcp,          // weighted average LCP (ms), 0 if no LCP data

        // ── Breaches ────────────────────────────────────────────────────────
        List<BreachEntry> breaches,

        // ── Best / Worst ────────────────────────────────────────────────────
        String bestLabel,
        int bestScore,
        long bestLcp,
        String worstLabel,
        int worstScore,
        long worstLcp,
        String worstImprovementArea,

        // ── JavaScript Errors ───────────────────────────────────────────────
        int totalErrors,
        int totalErrorLabels,
        List<ErrorEntry> topJsErrors,

        // ── Risk Assessment ─────────────────────────────────────────────────
        List<RiskEntry> headroomRisks,
        List<RiskEntry> boundaryRisks,
        List<String> spaLabels,

        // ── Trends (nullable) ───────────────────────────────────────────────
        TrendData trends
) {

    /**
     * Groups breach entries by improvement area, preserving insertion order.
     *
     * @param entries the breach entries to group (may be a sublist)
     * @return linked map of area → entries, with null/empty areas mapped to {@code BOTTLENECK_NONE}
     */
    public static Map<String, List<BreachEntry>> groupByArea(List<BreachEntry> entries) {
        Map<String, List<BreachEntry>> groups = new LinkedHashMap<>();
        for (BreachEntry entry : entries) {
            String area = entry.improvementArea() != null && !entry.improvementArea().isEmpty()
                    ? entry.improvementArea() : BOTTLENECK_NONE;
            groups.computeIfAbsent(area, k -> new ArrayList<>()).add(entry);
        }
        return groups;
    }

    /**
     * A label that breaches one or more SLA thresholds.
     * Ordered by severity (critical first, then warnings).
     */
    public record BreachEntry(
            String label,
            Integer score,
            String scoreVerdict,
            long lcp,
            String lcpVerdict,
            long fcp,
            String fcpVerdict,
            long ttfb,
            String ttfbVerdict,
            double cls,
            String clsVerdict,
            String improvementArea,
            boolean hasCritical   // at least one POOR verdict
    ) {
    }

    /**
     * JavaScript error count for a specific label.
     */
    public record ErrorEntry(String label, int count) {
    }

    /**
     * A risk item with the affected label and a pre-built description.
     */
    public record RiskEntry(String label, int value, String description) {
    }
}
