package io.github.sagaraggarwal86.jmeter.bpm.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 1 unit tests for SummaryJsonWriter.
 */
@DisplayName("SummaryJsonWriter")
class SummaryJsonWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("verdict is PASS when no SLA breaches and score above threshold")
    void write_noBreachesHighScore_verdictPass() throws Exception {
        Path jsonlPath = tempDir.resolve("test.jsonl");
        Files.createFile(jsonlPath);

        List<Map<String, Object>> stats = List.of(
                Map.of("label", "Login", "score", 95, "lcp", 1500L, "bottleneck", "—", "samples", 50));

        new SummaryJsonWriter().write(jsonlPath, stats, 4000L, 50);

        Path summaryPath = tempDir.resolve("test-summary.json");
        assertTrue(Files.exists(summaryPath));

        JsonNode root = mapper.readTree(summaryPath.toFile());
        assertEquals("PASS", root.get("verdict").asText());
        assertEquals(95, root.get("overallScore").asInt());
        assertEquals(0, root.get("slaBreaches").asInt());
    }

    @Test
    @DisplayName("verdict is FAIL when LCP breaches SLA threshold")
    void write_lcpBreachesSla_verdictFail() throws Exception {
        Path jsonlPath = tempDir.resolve("test.jsonl");
        Files.createFile(jsonlPath);

        List<Map<String, Object>> stats = List.of(
                Map.of("label", "Dashboard", "score", 41, "lcp", 4200L, "bottleneck", "Server bottleneck", "samples", 46));

        new SummaryJsonWriter().write(jsonlPath, stats, 4000L, 50);

        Path summaryPath = tempDir.resolve("test-summary.json");
        JsonNode root = mapper.readTree(summaryPath.toFile());
        assertEquals("FAIL", root.get("verdict").asText());
        assertEquals(1, root.get("slaBreaches").asInt());

        JsonNode detail = root.get("details").get(0);
        assertEquals("FAIL", detail.get("lcpVerdict").asText());
        assertEquals(4000, detail.get("breachedThreshold").asLong());
    }

    @Test
    @DisplayName("deriveSummaryPath replaces .jsonl with -summary.json")
    void deriveSummaryPath_replacesExtension() {
        Path input = Path.of("/some/dir/bpm-results.jsonl");
        Path result = SummaryJsonWriter.deriveSummaryPath(input);
        assertEquals("bpm-results-summary.json", result.getFileName().toString());
    }

    @Test
    @DisplayName("SPA-stale labels with null score are excluded from overall score without NPE") // CHANGED: per-action accuracy
    void write_nullScore_excludedFromOverallScore() throws Exception {
        Path jsonlPath = tempDir.resolve("test.jsonl");
        Files.createFile(jsonlPath);

        // Mix of a scored label (full page load) and a null-score label (SPA-stale)
        List<Map<String, Object>> stats = List.of(
                Map.of("label", "Launch", "score", 100, "lcp", 500L, "bottleneck", "—", "samples", 10),
                Map.of("label", "SPA_Click", "lcp", 0L, "bottleneck", "—", "samples", 10) // no "score" key → null
        );

        // Must not throw NullPointerException
        new SummaryJsonWriter().write(jsonlPath, stats, 4000L, 50);

        Path summaryPath = tempDir.resolve("test-summary.json");
        assertTrue(Files.exists(summaryPath), "Summary file should be written even with null scores");

        JsonNode root = mapper.readTree(summaryPath.toFile());
        // Overall score should be 100 (only the scored label contributes), not 50 (which would mean null treated as 0)
        assertEquals(100, root.get("overallScore").asInt(),
                "Overall score must exclude SPA-stale null-score labels");
        assertEquals("PASS", root.get("verdict").asText());
        assertEquals(0, root.get("slaBreaches").asInt(), "lcp=0 (no data) must not count as SLA breach");

        // SPA-stale detail must have null score node
        JsonNode spaDetail = root.get("details").get(1);
        assertTrue(spaDetail.get("score").isNull(), "SPA-stale label score must be JSON null");
        assertEquals("N/A", spaDetail.get("lcpVerdict").asText(), "lcp=0 verdict must be N/A");
    }
}