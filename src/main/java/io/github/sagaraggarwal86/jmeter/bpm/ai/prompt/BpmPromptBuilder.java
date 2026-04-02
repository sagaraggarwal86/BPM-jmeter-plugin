package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds the AI prompt user message from BPM aggregated per-label metrics.
 *
 * <p>Pre-computes SLA verdicts (GOOD/NEEDS_WORK/POOR) in Java to prevent
 * model arithmetic errors. The AI receives ready-to-use verdicts and focuses
 * on narrative analysis.</p>
 */
public final class BpmPromptBuilder {

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
        String userMessage = buildUserMessage(aggregates, props, scenarioName,
                description, virtualUsers, timeBuckets);
        log.debug("build: userMessage length={} chars, {} labels, {} buckets",
                userMessage.length(), aggregates.size(),
                timeBuckets != null ? timeBuckets.size() : 0);
        return new BpmPromptContent(systemPrompt, userMessage);
    }

    private static String buildUserMessage(Map<String, LabelAggregate> aggregates,
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

        // Per-label results
        ArrayNode labelResults = root.putArray("labelResults");
        int breachCount = 0;
        List<String> breachDetails = new ArrayList<>();
        String worstLabel = null;
        int worstScore = Integer.MAX_VALUE;

        for (Map.Entry<String, LabelAggregate> entry : aggregates.entrySet()) {
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
                if (avgScore < worstScore) {
                    worstScore = avgScore;
                    worstLabel = label;
                }
                if (!"GOOD".equals(verdict)) {
                    breachCount++;
                    breachDetails.add(label + ": Score " + verdict + " (" + avgScore + ")");
                }
            } else {
                scoreNode.putNull("avg");
                scoreNode.put("verdict", "N/A");
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

        // SLA verdicts summary
        ObjectNode verdicts = root.putObject("slaVerdicts");
        if (worstLabel != null) {
            String overallVerdict = worstScore >= props.getSlaScoreGood() ? "GOOD"
                    : worstScore >= props.getSlaScorePoor() ? "NEEDS_WORK" : "POOR";
            verdicts.put("overallScore", overallVerdict);
            verdicts.put("worstLabel", worstLabel);
            verdicts.put("worstScore", worstScore);
        } else {
            verdicts.put("overallScore", "N/A");
            verdicts.putNull("worstLabel");
            verdicts.putNull("worstScore");
        }
        verdicts.put("breachCount", breachCount);
        ArrayNode breachArr = verdicts.putArray("breachDetails");
        breachDetails.forEach(breachArr::add);

        // Time-series trend analysis (optional — pre-computed, AI does zero math)
        ObjectNode trendData = TrendAnalyzer.analyze(timeBuckets);
        if (trendData != null) {
            root.set("trendAnalysis", trendData);
        }

        return root.toString();
    }

    // ── Verdict helpers ───────────────────────────────────────────────────────

    private static String scoreVerdict(int value, int good, int poor) {
        if (value >= good) return "GOOD";
        if (value >= poor) return "NEEDS_WORK";
        return "POOR";
    }

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
}
