package io.github.sagaraggarwal86.jmeter.bpm.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code bpm-summary.json} file at test end for CI/CD integration.
 *
 * <p>The summary contains an overall verdict (PASS/FAIL), overall score,
 * total samples, SLA breach count, and per-label details including score,
 * average LCP, LCP verdict, breached threshold (if any), and primary bottleneck.</p>
 *
 * <p>Output path is auto-derived from the JSONL path by replacing the extension
 * with {@code -summary.json}. For example: {@code bpm-results.jsonl} →
 * {@code bpm-results-summary.json}.</p>
 */
public final class SummaryJsonWriter {

    private static final Logger log = LoggerFactory.getLogger(SummaryJsonWriter.class);

    private static final String BPM_VERSION = "1.0";

    private final ObjectMapper objectMapper;

    /**
     * Creates a new summary writer.
     */
    public SummaryJsonWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Derives the summary JSON path from the JSONL path.
     *
     * <p>Replaces {@code .jsonl} extension with {@code -summary.json}.
     * If the path has no {@code .jsonl} extension, appends {@code -summary.json}.</p>
     *
     * @param jsonlPath the JSONL output path
     * @return the derived summary path
     */
    static Path deriveSummaryPath(Path jsonlPath) {
        String filename = jsonlPath.getFileName().toString();
        String summaryFilename;
        if (filename.endsWith(".jsonl")) {
            summaryFilename = filename.substring(0, filename.length() - ".jsonl".length()) + "-summary.json";
        } else {
            summaryFilename = filename + "-summary.json";
        }
        Path parent = jsonlPath.getParent();
        return parent != null ? parent.resolve(summaryFilename) : Path.of(summaryFilename);
    }

    /**
     * Writes the summary JSON file.
     *
     * @param jsonlPath    the JSONL output path (summary path is derived from this)
     * @param labelStats   per-label aggregated statistics; each entry contains:
     *                     "score" (int), "lcp" (long avg), "bottleneck" (String),
     *                     "samples" (int)
     * @param slaLcpPoor   the LCP poor threshold for verdict determination
     * @param slaScorePoor the score poor threshold
     */
    public void write(Path jsonlPath, List<Map<String, Object>> labelStats,
                      long slaLcpPoor, int slaScorePoor) {
        Path summaryPath = deriveSummaryPath(jsonlPath);
        try {
            ObjectNode root = buildSummaryJson(labelStats, slaLcpPoor, slaScorePoor);
            Path parent = summaryPath.getParent();
            if (parent != null) {                              // CHANGED: skip createDirectories for bare filename paths; passing summaryPath itself creates a directory named after the file
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(summaryPath.toFile(), root);
            log.info("BPM: Summary written to {}", summaryPath);
        } catch (IOException e) {
            log.warn("BPM: Failed to write summary to {}", summaryPath, e);
        }
    }

    /**
     * Builds the summary JSON structure.
     */
    ObjectNode buildSummaryJson(List<Map<String, Object>> labelStats,
                                long slaLcpPoor, int slaScorePoor) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("bpmVersion", BPM_VERSION);

        int totalSamples = 0;
        long totalWeightedScore = 0;
        int slaBreaches = 0;
        ArrayNode detailsArray = objectMapper.createArrayNode();

        for (Map<String, Object> stat : labelStats) {
            String label = (String) stat.get("label");
            int score = ((Number) stat.get("score")).intValue();
            long lcp = ((Number) stat.get("lcp")).longValue();
            String bottleneck = (String) stat.get("bottleneck");
            int samples = ((Number) stat.get("samples")).intValue();

            totalSamples += samples;
            totalWeightedScore += (long) score * samples;

            boolean lcpBreached = lcp > slaLcpPoor;
            if (lcpBreached) {
                slaBreaches++;
            }

            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("label", label);
            detail.put("score", score);
            detail.put("lcp", lcp);
            detail.put("lcpVerdict", lcpBreached ? "FAIL" : "PASS");
            if (lcpBreached) {
                detail.put("breachedThreshold", slaLcpPoor);
            }
            detail.put("bottleneck", bottleneck);
            detailsArray.add(detail);
        }

        int overallScore = totalSamples > 0
                ? (int) (totalWeightedScore / totalSamples)
                : 0;

        // Overall verdict: FAIL if any SLA breach or overall score below poor threshold
        boolean overallPass = slaBreaches == 0 && overallScore >= slaScorePoor;

        root.put("verdict", overallPass ? "PASS" : "FAIL");
        root.put("overallScore", overallScore);
        root.put("totalSamples", totalSamples);
        root.put("slaBreaches", slaBreaches);
        root.set("details", detailsArray);

        return root;
    }
}