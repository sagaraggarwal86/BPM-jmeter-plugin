package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds the AI prompt user message from BPM aggregated per-label metrics.
 *
 * <p>Pre-computes SLA verdicts (GOOD/NEEDS_WORK/POOR) in Java to prevent
 * model arithmetic errors. The AI receives ready-to-use verdicts and focuses
 * on narrative analysis.</p>
 */
public final class BpmPromptBuilder {

    public static final int MAX_AI_LABELS = 20;
    private static final Logger log = LoggerFactory.getLogger(BpmPromptBuilder.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private BpmPromptBuilder() {
    }

    /**
     * Builds the complete AI prompt from aggregated metrics and SLA thresholds.
     * Used by GUI mode (no metadata, no time-series trends).
     */
    public static BpmPromptContent build(String systemPrompt,
                                         Map<String, LabelAggregate> aggregates,
                                         BpmPropertiesManager props) {
        return build(systemPrompt, aggregates, props, "", "", "", Collections.emptyList());
    }

    /**
     * Builds the complete AI prompt from aggregated metrics, SLA thresholds,
     * optional report metadata, and optional time-series data for trend analysis.
     *
     * @param systemPrompt loaded system prompt text
     * @param aggregates   per-label aggregates
     * @param props        properties manager with SLA thresholds
     * @param scenarioName scenario name (empty if not provided)
     * @param description  scenario description (empty if not provided)
     * @param virtualUsers virtual user count as string (empty if not provided)
     * @param timeBuckets  time-series data for trend analysis (may be empty)
     * @return assembled prompt content ready for the AI API
     */
    public static BpmPromptContent build(String systemPrompt,
                                         Map<String, LabelAggregate> aggregates,
                                         BpmPropertiesManager props,
                                         String scenarioName,
                                         String description,
                                         String virtualUsers,
                                         List<BpmTimeBucket> timeBuckets) {
        java.util.Objects.requireNonNull(aggregates, "aggregates must not be null");
        java.util.Objects.requireNonNull(props, "props must not be null");
        BuildResult result = buildUserMessage(aggregates, props, scenarioName,
                description, virtualUsers, timeBuckets);
        log.debug("build: userMessage length={} chars, {} labels (truncated={}), {} buckets",
                result.json.length(), aggregates.size(), result.truncated,
                timeBuckets != null ? timeBuckets.size() : 0);
        if (result.truncated) {
            return new BpmPromptContent(systemPrompt, result.json,
                    aggregates.size(), MAX_AI_LABELS);
        }
        return new BpmPromptContent(systemPrompt, result.json);
    }

    private static BuildResult buildUserMessage(Map<String, LabelAggregate> aggregates,
                                                BpmPropertiesManager props,
                                                String scenarioName,
                                                String description,
                                                String virtualUsers,
                                                List<BpmTimeBucket> timeBuckets) {
        ObjectNode root = mapper.createObjectNode();

        // Report metadata (if provided)
        ObjectNode metadata = root.putObject("reportMetadata");
        metadata.put("scenarioName", orNotProvided(scenarioName));
        metadata.put("description", orNotProvided(description));
        metadata.put("virtualUsers", orNotProvided(virtualUsers));

        // Test summary
        ObjectNode summary = root.putObject("testSummary");
        summary.put("totalLabels", aggregates.size());
        int totalSamples = aggregates.values().stream()
                .mapToInt(LabelAggregate::getSampleCount).sum();
        summary.put("totalSamples", totalSamples);

        // SLA thresholds
        ObjectNode sla = summary.putObject("slaThresholds");
        ObjectNode fcpSla = sla.putObject("fcp");
        fcpSla.put("good", props.getSlaFcpGood());
        fcpSla.put("poor", props.getSlaFcpPoor());
        ObjectNode lcpSla = sla.putObject("lcp");
        lcpSla.put("good", props.getSlaLcpGood());
        lcpSla.put("poor", props.getSlaLcpPoor());
        ObjectNode clsSla = sla.putObject("cls");
        clsSla.put("good", props.getSlaClsGood());
        clsSla.put("poor", props.getSlaClsPoor());
        ObjectNode ttfbSla = sla.putObject("ttfb");
        ttfbSla.put("good", props.getSlaTtfbGood());
        ttfbSla.put("poor", props.getSlaTtfbPoor());
        ObjectNode scoreSla = sla.putObject("score");
        scoreSla.put("good", props.getSlaScoreGood());
        scoreSla.put("poor", props.getSlaScorePoor());

        // Truncate labels if > MAX_AI_LABELS to stay within token budget
        Map<String, LabelAggregate> effectiveAggregates = aggregates;
        int omittedCount = 0;
        int omittedGood = 0;
        int omittedNeedsWork = 0;
        int omittedPoor = 0;
        List<String> omittedPoorLabels = new ArrayList<>();
        if (aggregates.size() > MAX_AI_LABELS) {
            log.warn("AI report: {} labels detected, sending top {} to AI. "
                            + "Labels >20 may reduce report quality. "
                            + "Consider filtering transactions for the most detailed analysis.",
                    aggregates.size(), MAX_AI_LABELS);
            // Sort by score ascending (worst first), null-score labels after scored ones
            List<Map.Entry<String, LabelAggregate>> sorted = new ArrayList<>(aggregates.entrySet());
            sorted.sort(Comparator.comparingInt(e -> {
                Integer s = e.getValue().getAverageScore();
                return s != null ? s : Integer.MAX_VALUE;
            }));
            effectiveAggregates = new LinkedHashMap<>();
            int included = 0;
            for (Map.Entry<String, LabelAggregate> e : sorted) {
                if (included < MAX_AI_LABELS) {
                    effectiveAggregates.put(e.getKey(), e.getValue());
                    included++;
                } else {
                    omittedCount++;
                    Integer s = e.getValue().getAverageScore();
                    if (s == null) {
                        // SPA — count as good for summary purposes
                        omittedGood++;
                    } else if (s >= props.getSlaScoreGood()) {
                        omittedGood++;
                    } else if (s >= props.getSlaScorePoor()) {
                        omittedNeedsWork++;
                    } else {
                        omittedPoor++;
                        omittedPoorLabels.add(e.getKey());
                    }
                }
            }
        }

        // Pre-compute best/worst from the FULL dataset (before truncation)
        // so "fastest/slowest page" reflects the actual best/worst across ALL labels.
        // Tiebreaker: best = highest score, then lowest LCP; worst = lowest score, then highest LCP.
        // This ensures "fastest page" has the best LCP among score ties, and "slowest page"
        // has the worst LCP among score ties — making the narrative semantically correct.
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

        // Per-label results
        ArrayNode labelResults = root.putArray("labelResults");
        int breachCount = 0;
        List<String> breachDetails = new ArrayList<>();
        List<String> spaLabels = new ArrayList<>();
        List<String> boundaryRisks = new ArrayList<>();
        List<String> headroomRisks = new ArrayList<>();

        for (Map.Entry<String, LabelAggregate> entry : effectiveAggregates.entrySet()) {
            String label = entry.getKey();
            LabelAggregate agg = entry.getValue();

            ObjectNode labelNode = labelResults.addObject();
            labelNode.put("label", label);
            labelNode.put("samples", agg.getSampleCount());

            // Score with verdict
            Integer avgScore = agg.getAverageScore();
            ObjectNode scoreNode = labelNode.putObject("score");
            if (avgScore != null) {
                scoreNode.put("avg", avgScore);
                String verdict = scoreVerdict(avgScore, props.getSlaScoreGood(), props.getSlaScorePoor());
                scoreNode.put("verdict", verdict);
                if (!"GOOD".equals(verdict)) {
                    breachCount++;
                    breachDetails.add(label + ": Score " + verdict + " (" + avgScore + ")");
                }
                // Pre-compute boundary risks
                if ("NEEDS_WORK".equals(verdict)
                        && avgScore >= props.getSlaScoreGood() - 5) {
                    boundaryRisks.add(label + " (score " + avgScore
                            + ") is close to the GOOD threshold — targeted optimization could upgrade its status.");
                } else if ("POOR".equals(verdict)
                        && avgScore >= props.getSlaScorePoor() - 10) {
                    boundaryRisks.add(label + " (score " + avgScore
                            + ") is near the POOR boundary — a minor regression will cause SLA failure.");
                }
            } else {
                scoreNode.putNull("avg");
                scoreNode.put("verdict", "N/A");
                spaLabels.add(label);
            }

            // LCP
            long avgLcp = agg.getAverageLcp();
            ObjectNode lcpNode = labelNode.putObject("lcp");
            lcpNode.put("avg", avgLcp);
            String lcpVerdict = msVerdict(avgLcp, props.getSlaLcpGood(), props.getSlaLcpPoor());
            lcpNode.put("verdict", lcpVerdict);
            if (avgLcp > 0 && !"GOOD".equals(lcpVerdict)) {
                breachDetails.add(label + ": LCP " + lcpVerdict + " (" + avgLcp + "ms)");
            }

            // FCP
            long avgFcp = agg.getAverageFcp();
            ObjectNode fcpNode = labelNode.putObject("fcp");
            fcpNode.put("avg", avgFcp);
            fcpNode.put("verdict", msVerdict(avgFcp, props.getSlaFcpGood(), props.getSlaFcpPoor()));

            // TTFB
            long avgTtfb = agg.getAverageTtfb();
            ObjectNode ttfbNode = labelNode.putObject("ttfb");
            ttfbNode.put("avg", avgTtfb);
            ttfbNode.put("verdict", msVerdict(avgTtfb, props.getSlaTtfbGood(), props.getSlaTtfbPoor()));

            // CLS
            double avgCls = agg.getAverageCls();
            ObjectNode clsNode = labelNode.putObject("cls");
            clsNode.put("avg", Math.round(avgCls * 1000.0) / 1000.0); // 3 decimal places
            clsNode.put("verdict", clsVerdict(avgCls, props.getSlaClsGood(), props.getSlaClsPoor()));

            // Derived metrics (plain values, no verdict)
            labelNode.put("renderTime", agg.getAverageRenderTime());
            labelNode.put("serverRatio", Math.round(agg.getAverageServerRatio() * 100.0) / 100.0);

            Long avgFrontend = agg.getAverageFrontendTime();
            if (avgFrontend != null) {
                labelNode.put("frontendTime", avgFrontend);
            } else {
                labelNode.putNull("frontendTime");
            }

            labelNode.put("fcpLcpGap", agg.getAverageFcpLcpGap());

            Integer avgHeadroom = agg.getAverageHeadroom();
            if (avgHeadroom != null) {
                labelNode.put("headroom", avgHeadroom);
                if (avgHeadroom < 30) {
                    headroomRisks.add(label + " has only " + avgHeadroom
                            + "% headroom — one traffic spike or code change could push it past the SLA threshold.");
                }
            } else {
                labelNode.putNull("headroom");
            }

            // Network & console
            labelNode.put("requests", agg.getAverageRequests());
            labelNode.put("bytesKB", agg.getAverageBytes() / 1024);
            labelNode.put("jsErrors", agg.getTotalErrors());
            labelNode.put("jsWarnings", agg.getTotalWarnings());

            // Improvement area
            labelNode.put("improvementArea", agg.getPrimaryImprovementArea());
        }

        // SLA verdicts summary (best/worst from FULL dataset, not truncated)
        ObjectNode verdicts = root.putObject("slaVerdicts");
        if (globalWorstLabel != null) {
            String overallVerdict = globalWorstScore >= props.getSlaScoreGood() ? "GOOD"
                    : globalWorstScore >= props.getSlaScorePoor() ? "NEEDS_WORK" : "POOR";
            verdicts.put("overallScore", overallVerdict);
            verdicts.put("worstLabel", globalWorstLabel);
            verdicts.put("worstScore", globalWorstScore);
            // Include LCP for worst label so AI copies directly
            LabelAggregate worstAgg = aggregates.get(globalWorstLabel);
            verdicts.put("worstLcp", worstAgg.getAverageLcp());
            verdicts.put("worstImprovementArea", worstAgg.getPrimaryImprovementArea());
        } else {
            verdicts.put("overallScore", "N/A");
            verdicts.putNull("worstLabel");
            verdicts.putNull("worstScore");
            verdicts.putNull("worstLcp");
            verdicts.putNull("worstImprovementArea");
        }
        if (globalBestLabel != null) {
            verdicts.put("bestLabel", globalBestLabel);
            verdicts.put("bestScore", globalBestScore);
            // Include LCP for best label so AI copies directly
            LabelAggregate bestAgg = aggregates.get(globalBestLabel);
            verdicts.put("bestLcp", bestAgg.getAverageLcp());
        } else {
            verdicts.putNull("bestLabel");
            verdicts.putNull("bestScore");
            verdicts.putNull("bestLcp");
        }
        verdicts.put("breachCount", breachCount);
        ArrayNode breachArr = verdicts.putArray("breachDetails");
        breachDetails.forEach(breachArr::add);

        // Pre-compute top 3 JS error labels (sorted descending) so AI copies verbatim
        ArrayNode topJsErrors = verdicts.putArray("topJsErrors");
        effectiveAggregates.entrySet().stream()
                .filter(e -> e.getValue().getTotalErrors() > 0)
                .sorted(Comparator.<Map.Entry<String, LabelAggregate>>comparingInt(
                        e -> e.getValue().getTotalErrors()).reversed())
                .limit(3)
                .forEach(e -> {
                    ObjectNode errNode = topJsErrors.addObject();
                    errNode.put("label", e.getKey());
                    errNode.put("count", e.getValue().getTotalErrors());
                });

        // Pre-computed risks (AI copies verbatim, does zero threshold math)
        ObjectNode risks = root.putObject("preComputedRisks");
        ArrayNode headroomArr = risks.putArray("headroomRisks");
        headroomRisks.forEach(headroomArr::add);
        ArrayNode boundaryArr = risks.putArray("boundaryRisks");
        boundaryRisks.forEach(boundaryArr::add);
        ArrayNode spaArr = risks.putArray("spaLabels");
        spaLabels.forEach(spaArr::add);

        // Time-series trend analysis (optional — pre-computed, AI does zero math)
        ObjectNode trendData = TrendAnalyzer.analyze(timeBuckets);
        if (trendData != null) {
            root.set("trendAnalysis", trendData);
        }

        // Label truncation summary (when >MAX_AI_LABELS labels were present)
        if (omittedCount > 0) {
            ObjectNode remaining = root.putObject("remainingLabels");
            remaining.put("total", aggregates.size());
            remaining.put("included", MAX_AI_LABELS);
            remaining.put("omitted", omittedCount);
            remaining.put("omittedAllGood", omittedGood);
            remaining.put("omittedNeedsWork", omittedNeedsWork);
            remaining.put("omittedPoor", omittedPoor);
            ArrayNode poorArr = remaining.putArray("omittedPoorLabels");
            omittedPoorLabels.forEach(poorArr::add);
        }

        return new BuildResult(root.toString(), omittedCount > 0);
    }

    private static String scoreVerdict(int value, int good, int poor) {
        if (value >= good) return "GOOD";
        if (value >= poor) return "NEEDS_WORK";
        return "POOR";
    }

    // ── Verdict helpers ───────────────────────────────────────────────────────

    private static String msVerdict(long value, long good, long poor) {
        if (value == 0) return "N/A";
        if (value <= good) return "GOOD";
        if (value <= poor) return "NEEDS_WORK";
        return "POOR";
    }

    private static String clsVerdict(double value, double good, double poor) {
        if (value <= good) return "GOOD";
        if (value <= poor) return "NEEDS_WORK";
        return "POOR";
    }

    private static String orNotProvided(String value) {
        return (value == null || value.isBlank()) ? "Not provided" : value.trim();
    }

    private static final class BuildResult {
        final String json;
        final boolean truncated;

        BuildResult(String json, boolean truncated) {
            this.json = json;
            this.truncated = truncated;
        }
    }
}
