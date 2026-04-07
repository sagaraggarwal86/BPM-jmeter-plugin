package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants.*;

/**
 * Builds {@link ReportData} from per-label aggregates and SLA thresholds.
 *
 * <p>Pre-computes SLA verdicts (GOOD/NEEDS_WORK/POOR), risk assessments,
 * best/worst performers, and trend analysis. All data is ready for
 * direct use in HTML report templates.</p>
 */
public final class ReportDataBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportDataBuilder.class);

    private ReportDataBuilder() {
    }

    /**
     * Builds report data from aggregated metrics.
     * GUI mode (no time-series trends).
     */
    public static ReportData build(Map<String, LabelAggregate> aggregates,
                                   BpmPropertiesManager props) {
        return build(aggregates, props, Collections.emptyList());
    }

    /**
     * Builds report data from aggregated metrics with optional time-series data.
     *
     * @param aggregates  per-label aggregates (all labels, no truncation)
     * @param props       properties manager with SLA thresholds
     * @param timeBuckets time-series data for trend analysis (may be empty)
     * @return assembled report data
     */
    public static ReportData build(Map<String, LabelAggregate> aggregates,
                                   BpmPropertiesManager props,
                                   List<BpmTimeBucket> timeBuckets) {
        Objects.requireNonNull(aggregates, "aggregates must not be null");
        Objects.requireNonNull(props, "props must not be null");

        int totalSamples = aggregates.values().stream()
                .mapToInt(LabelAggregate::getSampleCount).sum();

        // ── Best / Worst (from full dataset) ────────────────────────────────
        String globalBestLabel = null;
        int globalBestScore = Integer.MIN_VALUE;
        long globalBestLcp = Long.MAX_VALUE;
        String globalWorstLabel = null;
        int globalWorstScore = Integer.MAX_VALUE;
        long globalWorstLcp = Long.MIN_VALUE;

        for (Map.Entry<String, LabelAggregate> e : aggregates.entrySet()) {
            Integer s = e.getValue().getAverageScore();
            if (s != null) {
                long lcp = e.getValue().getAverageLcp();
                if (s > globalBestScore || (s == globalBestScore && lcp < globalBestLcp)) {
                    globalBestScore = s;
                    globalBestLabel = e.getKey();
                    globalBestLcp = lcp;
                }
                if (s < globalWorstScore || (s == globalWorstScore && lcp > globalWorstLcp)) {
                    globalWorstScore = s;
                    globalWorstLabel = e.getKey();
                    globalWorstLcp = lcp;
                }
            }
        }

        // ── Per-label verdicts + breach/risk collection ─────────────────────
        List<ReportData.BreachEntry> breaches = new ArrayList<>();
        List<ReportData.RiskEntry> headroomRisks = new ArrayList<>();
        List<ReportData.RiskEntry> boundaryRisks = new ArrayList<>();
        List<String> spaLabels = new ArrayList<>();
        int passCount = 0;
        int warnCount = 0;
        int criticalCount = 0;
        long weightedScoreSum = 0;
        long weightedScoreSamples = 0;
        long weightedLcpSum = 0;
        long weightedLcpSamples = 0;

        for (Map.Entry<String, LabelAggregate> entry : aggregates.entrySet()) {
            String label = entry.getKey();
            LabelAggregate agg = entry.getValue();
            Integer avgScore = agg.getAverageScore();
            int samples = agg.getSampleCount();

            // Score verdict
            String scoreVerdict;
            if (avgScore != null) {
                scoreVerdict = scoreVerdict(avgScore, props.getSlaScoreGood(), props.getSlaScorePoor());
                weightedScoreSum += (long) avgScore * samples;
                weightedScoreSamples += samples;
            } else {
                scoreVerdict = VERDICT_NA;
                spaLabels.add(label);
            }

            // Metric verdicts + weighted LCP
            long avgLcp = agg.getAverageLcp();
            if (avgLcp > 0) {
                weightedLcpSum += avgLcp * samples;
                weightedLcpSamples += samples;
            }
            String lcpVerdict = msVerdict(avgLcp, props.getSlaLcpGood(), props.getSlaLcpPoor());
            long avgFcp = agg.getAverageFcp();
            String fcpVerdict = msVerdict(avgFcp, props.getSlaFcpGood(), props.getSlaFcpPoor());
            long avgTtfb = agg.getAverageTtfb();
            String ttfbVerdict = msVerdict(avgTtfb, props.getSlaTtfbGood(), props.getSlaTtfbPoor());
            double avgCls = agg.getAverageCls();
            String clsVerdict = clsVerdict(avgCls, props.getSlaClsGood(), props.getSlaClsPoor());

            // Classify pass/warn/critical
            String[] verdicts = {scoreVerdict, lcpVerdict, fcpVerdict, ttfbVerdict, clsVerdict};
            boolean hasPoor = Arrays.stream(verdicts).anyMatch(VERDICT_POOR::equals);
            boolean hasWarn = Arrays.stream(verdicts).anyMatch(VERDICT_NEEDS_WORK::equals);

            if (hasPoor) {
                criticalCount++;
            } else if (hasWarn) {
                warnCount++;
            } else {
                passCount++;
            }

            // Collect breaches
            if (hasPoor || hasWarn) {
                breaches.add(new ReportData.BreachEntry(
                        label, avgScore, scoreVerdict,
                        avgLcp, lcpVerdict,
                        avgFcp, fcpVerdict,
                        avgTtfb, ttfbVerdict,
                        Math.round(avgCls * 1000.0) / 1000.0, clsVerdict,
                        agg.getPrimaryImprovementArea(),
                        hasPoor));
            }

            // Boundary risks
            if (avgScore != null) {
                if (VERDICT_NEEDS_WORK.equals(scoreVerdict)
                        && avgScore >= props.getSlaScoreGood() - 5) {
                    boundaryRisks.add(new ReportData.RiskEntry(label, avgScore,
                            "just below the passing threshold. Targeted optimisation could move it from warning to passing."));
                } else if (VERDICT_POOR.equals(scoreVerdict)
                        && avgScore >= props.getSlaScorePoor() - 10) {
                    boundaryRisks.add(new ReportData.RiskEntry(label, avgScore,
                            "near the failing boundary. A minor regression would cause a more severe quality failure."));
                }
            }

            // Headroom risks
            Integer avgHeadroom = agg.getAverageHeadroom();
            if (avgHeadroom != null && avgHeadroom < 30) {
                String headroomDesc;
                if (avgHeadroom <= 5) {
                    headroomDesc = avgHeadroom + "% margin remaining. Closest to failure of any measured page.";
                } else if (avgHeadroom <= 15) {
                    headroomDesc = avgHeadroom + "% margin remaining. At risk from minor traffic or code changes.";
                } else {
                    headroomDesc = avgHeadroom + "% margin remaining. Limited buffer before quality targets are breached.";
                }
                headroomRisks.add(new ReportData.RiskEntry(label, avgHeadroom, headroomDesc));
            }
        }

        // Sort breaches: critical first, then warnings; within each group, by score ascending
        breaches.sort(Comparator.<ReportData.BreachEntry, Boolean>comparing(b -> !b.hasCritical())
                .thenComparingInt(b -> b.score() != null ? b.score() : Integer.MAX_VALUE));

        // Sort headroom risks by headroom ascending (most at risk first)
        headroomRisks.sort(Comparator.comparingInt(ReportData.RiskEntry::value));

        // ── Top JS errors ───────────────────────────────────────────────────
        int totalErrors = 0;
        int totalErrorLabels = 0;
        List<ReportData.ErrorEntry> topJsErrors = new ArrayList<>();
        aggregates.entrySet().stream()
                .filter(e -> e.getValue().getTotalErrors() > 0)
                .sorted(Comparator.<Map.Entry<String, LabelAggregate>>comparingInt(
                        e -> e.getValue().getTotalErrors()).reversed())
                .limit(5)
                .forEach(e -> topJsErrors.add(
                        new ReportData.ErrorEntry(e.getKey(), e.getValue().getTotalErrors())));
        for (LabelAggregate agg : aggregates.values()) {
            totalErrors += agg.getTotalErrors();
            if (agg.getTotalErrors() > 0) {
                totalErrorLabels++;
            }
        }

        // ── Overall verdict ─────────────────────────────────────────────────
        String overallVerdict;
        if (globalWorstLabel != null) {
            overallVerdict = globalWorstScore >= props.getSlaScoreGood() ? VERDICT_GOOD
                    : globalWorstScore >= props.getSlaScorePoor() ? VERDICT_NEEDS_WORK : VERDICT_POOR;
        } else {
            overallVerdict = VERDICT_NA;
        }

        int weightedScore = weightedScoreSamples > 0
                ? (int) Math.round((double) weightedScoreSum / weightedScoreSamples)
                : 0;
        long weightedLcp = weightedLcpSamples > 0
                ? Math.round((double) weightedLcpSum / weightedLcpSamples)
                : 0;

        String worstImprovementArea = globalWorstLabel != null
                ? aggregates.get(globalWorstLabel).getPrimaryImprovementArea()
                : "";

        // ── Trend analysis ──────────────────────────────────────────────────
        TrendData trends = TrendAnalyzer.analyze(timeBuckets);

        log.debug("build: {} labels, {} samples, {} breaches, {} headroom risks, "
                        + "{} boundary risks, {} SPA labels, trends={}",
                aggregates.size(), totalSamples, breaches.size(),
                headroomRisks.size(), boundaryRisks.size(), spaLabels.size(),
                trends != null ? trends.overallStability() : VERDICT_NA);

        return new ReportData(
                aggregates.size(), totalSamples,
                overallVerdict, passCount, warnCount, criticalCount, weightedScore, weightedLcp,
                List.copyOf(breaches),
                globalBestLabel != null ? globalBestLabel : "",
                globalBestLabel != null ? globalBestScore : 0,
                globalBestLabel != null ? globalBestLcp : 0,
                globalWorstLabel != null ? globalWorstLabel : "",
                globalWorstLabel != null ? globalWorstScore : 0,
                globalWorstLabel != null ? globalWorstLcp : 0,
                worstImprovementArea,
                totalErrors, totalErrorLabels, List.copyOf(topJsErrors),
                List.copyOf(headroomRisks), List.copyOf(boundaryRisks),
                List.copyOf(spaLabels),
                trends
        );
    }

    // ── Verdict helpers ─────────────────────────────────────────────────────

    private static String scoreVerdict(int value, int good, int poor) {
        if (value >= good) return VERDICT_GOOD;
        if (value >= poor) return VERDICT_NEEDS_WORK;
        return VERDICT_POOR;
    }

    private static String msVerdict(long value, long good, long poor) {
        if (value == 0) return VERDICT_NA;
        if (value <= good) return VERDICT_GOOD;
        if (value <= poor) return VERDICT_NEEDS_WORK;
        return VERDICT_POOR;
    }

    private static String clsVerdict(double value, double good, double poor) {
        if (value <= good) return VERDICT_GOOD;
        if (value <= poor) return VERDICT_NEEDS_WORK;
        return VERDICT_POOR;
    }
}
