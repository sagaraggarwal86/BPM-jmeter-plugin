package io.github.sagaraggarwal86.jmeter.bpm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.gui.BpmTableModel;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.report.BpmHtmlReportRenderer;
import io.github.sagaraggarwal86.jmeter.bpm.report.ReportData;
import io.github.sagaraggarwal86.jmeter.bpm.report.ReportDataBuilder;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmFileUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Headless pipeline for CLI-based BPM report generation.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Parse JSONL file → per-label aggregates</li>
 *   <li>Apply label filters (--search / --regex / --exclude)</li>
 *   <li>Load BPM properties (SLA thresholds)</li>
 *   <li>Build report data from aggregates + SLA thresholds</li>
 *   <li>Render HTML report</li>
 *   <li>Save to file</li>
 * </ol>
 *
 * <p>Progress messages go to stderr. stdout is kept clean for piping.</p>
 */
public final class BpmCliReportPipeline {

    private static final ObjectMapper mapper = new ObjectMapper();

    private BpmCliReportPipeline() {
    }

    /**
     * Executes the full report pipeline.
     *
     * @param args parsed and validated CLI arguments
     * @return path to the generated HTML report
     * @throws IOException on any pipeline failure
     */
    public static Path execute(CliArgs args) throws IOException {
        // 1. Parse JSONL file
        progress("Parsing JSONL file: %s", args.inputFile());
        ParseResult parsed = parseJsonlFile(args.inputFile());
        if (parsed.aggregates.isEmpty()) {
            throw new BpmParseException("No valid BPM results found in: " + args.inputFile());
        }
        Map<String, LabelAggregate> aggregates = parsed.aggregates;
        progress("Parsed %d labels from %s", aggregates.size(), args.inputFile().getFileName());

        // 2. Apply label filters
        if (args.search() != null && !args.search().isBlank()) {
            int beforeCount = aggregates.size();
            applyLabelFilter(aggregates, args.search(), args.regex(), args.exclude());
            progress("Filter applied: %d \u2192 %d labels (search=%s, regex=%s, exclude=%s)",
                    beforeCount, aggregates.size(), args.search(), args.regex(), args.exclude());
            if (aggregates.isEmpty()) {
                throw new BpmParseException("No labels remaining after filter. Check --search pattern.");
            }
        }

        // 3. Load BPM properties (SLA thresholds)
        BpmPropertiesManager props = new BpmPropertiesManager();
        props.load();

        // 4. Build time buckets for trend analysis and charts
        TimeBucketBuilder.GroupedResult grouped = TimeBucketBuilder.buildGrouped(
                parsed.rawSamples, args.chartInterval());
        List<BpmTimeBucket> timeBuckets = grouped.globalBuckets;
        if (!timeBuckets.isEmpty()) {
            progress("Built %d time buckets (%ds interval) for trend analysis and charts.",
                    timeBuckets.size(), grouped.intervalSeconds);
        }

        // 5. Build report data
        progress("Building report data...");
        ReportData data = ReportDataBuilder.build(aggregates, props, timeBuckets);
        progress("Report data: %d labels, %d breaches, %d headroom risks",
                data.totalLabels(), data.breaches().size(), data.headroomRisks().size());

        // 6. Render HTML
        progress("Rendering HTML report...");
        String virtualUsersStr = args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "";
        String runDateTime = "";
        String duration = "";
        if (!timeBuckets.isEmpty()) {
            long startMs = timeBuckets.get(0).epochMs;
            long endMs = timeBuckets.get(timeBuckets.size() - 1).epochMs;
            java.time.format.DateTimeFormatter dtFmt =
                    java.time.format.DateTimeFormatter.ofPattern("M/d/yy HH:mm:ss");
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            String startStr = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startMs), zone).format(dtFmt);
            String endStr = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(endMs), zone).format(dtFmt);
            runDateTime = startStr + " - " + endStr;
            long totalSec = (endMs - startMs) / 1000;
            long h = totalSec / 3600;
            long m = (totalSec % 3600) / 60;
            long s = totalSec % 60;
            duration = h + "h " + m + "m " + s + "s";
        }
        BpmHtmlReportRenderer.RenderConfig renderConfig = new BpmHtmlReportRenderer.RenderConfig(
                args.scenarioName(),
                args.description(),
                virtualUsersStr,
                runDateTime,
                duration,
                "",
                grouped.intervalSeconds,
                props.getSlaScoreGood(), props.getSlaLcpGood(),
                props.getSlaFcpGood(), props.getSlaTtfbGood(), props.getSlaClsGood(),
                props.getSlaScorePoor(), props.getSlaLcpPoor(),
                props.getSlaFcpPoor(), props.getSlaTtfbPoor(), props.getSlaClsPoor());
        List<String[]> metricsTable = buildMetricsTable(parsed.metricsRows, aggregates);
        String html = BpmHtmlReportRenderer.render(data, renderConfig,
                timeBuckets, grouped.perLabelBuckets, metricsTable);

        // 7. Save to file
        Path outputPath = args.outputFile();
        if (outputPath == null) {
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            outputPath = Path.of("BPM_Report_" + ts + ".html");
        }
        BpmFileUtils.ensureParentDirectories(outputPath);
        Files.writeString(outputPath, html, StandardCharsets.UTF_8);
        progress("Report saved to: %s", outputPath.toAbsolutePath());

        return outputPath;
    }

    /**
     * Filters the aggregates map in-place based on search/regex/exclude options.
     */
    private static void applyLabelFilter(Map<String, LabelAggregate> aggregates,
                                         String search, boolean regex, boolean exclude) {
        Pattern pattern = regex
                ? Pattern.compile(search, Pattern.CASE_INSENSITIVE)
                : null;
        String searchLower = search.toLowerCase(java.util.Locale.ROOT);

        aggregates.entrySet().removeIf(entry -> {
            String label = entry.getKey();
            boolean matches;
            if (pattern != null) {
                matches = pattern.matcher(label).find();
            } else {
                matches = label.toLowerCase(java.util.Locale.ROOT).contains(searchLower);
            }
            // Include mode: remove non-matching. Exclude mode: remove matching.
            return exclude ? matches : !matches;
        });
    }

    // ── Label filtering ──────────────────────────────────────────────────────

    /**
     * Parses a BPM JSONL file, builds per-label aggregates and collects
     * raw sample data for time-series charting.
     */
    private static ParseResult parseJsonlFile(Path jsonlPath) throws IOException {
        LinkedHashMap<String, LabelAggregate> aggregates = new LinkedHashMap<>();
        LinkedHashMap<String, BpmTableModel.RowData> metricsRows = new LinkedHashMap<>();
        List<TimeBucketBuilder.RawSample> rawSamples = new ArrayList<>();
        int lineCount = 0;
        int errorCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                lineCount++;
                try {
                    BpmResult result = mapper.readValue(line, BpmResult.class);
                    String label = result.samplerLabel();
                    if (label == null || label.isBlank()) continue;

                    LabelAggregate agg = aggregates.computeIfAbsent(label, k -> new LabelAggregate());
                    if (result.derived() != null) {
                        agg.update(
                                result.derived(),
                                result.webVitals(),
                                result.network(),
                                result.console()
                        );
                    }

                    // Collect metrics table row data
                    metricsRows.computeIfAbsent(label, BpmTableModel.RowData::new).update(result);

                    // Collect raw sample for charting (with label for per-page filtering)
                    if (result.timestamp() != null && result.derived() != null) {
                        DerivedMetrics d = result.derived();
                        WebVitalsResult wv = result.webVitals();
                        long lcpVal = wv != null && wv.lcp() != null ? wv.lcp() : 0;
                        long ttfbVal = wv != null && wv.ttfb() != null ? wv.ttfb() : 0;
                        rawSamples.add(new TimeBucketBuilder.RawSample(
                                result.timestamp(),
                                label,
                                d.performanceScore(),
                                lcpVal,
                                wv != null && wv.fcp() != null ? wv.fcp() : 0,
                                ttfbVal,
                                wv != null && wv.cls() != null ? wv.cls() : -1,
                                d.renderTime()
                        ));
                    }
                } catch (Exception e) {
                    errorCount++;
                    if (errorCount <= 3) {
                        progress("  Warning: failed to parse line %d: %s", lineCount, e.getMessage());
                    }
                }
            }
        }

        if (errorCount > 3) {
            progress("  ... and %d more parse errors suppressed.", errorCount - 3);
        }
        progress("Parsed %d lines, %d labels, %d errors.", lineCount, aggregates.size(), errorCount);
        return new ParseResult(aggregates, rawSamples, metricsRows);
    }

    /**
     * Builds the metrics table (header + data rows) from parsed RowData, applying label filter.
     * Adds a TOTAL row at the end.
     */
    private static List<String[]> buildMetricsTable(Map<String, BpmTableModel.RowData> metricsRows,
                                                    Map<String, LabelAggregate> filteredAggregates) {
        List<String[]> table = new ArrayList<>();
        table.add(BpmConstants.ALL_COLUMN_HEADERS);

        BpmTableModel.RowData totalRow = new BpmTableModel.RowData("TOTAL");
        for (Map.Entry<String, BpmTableModel.RowData> entry : metricsRows.entrySet()) {
            // Only include labels that survived the filter
            if (!filteredAggregates.containsKey(entry.getKey())) continue;
            BpmTableModel.RowData row = entry.getValue();
            totalRow.mergeFrom(row);
            table.add(rowToStringArray(row));
        }
        if (table.size() > 1) {
            table.add(rowToStringArray(totalRow));
        }
        return table;
    }

    private static String[] rowToStringArray(BpmTableModel.RowData row) {
        String[] cells = new String[BpmConstants.TOTAL_COLUMN_COUNT];
        for (int c = 0; c < BpmConstants.TOTAL_COLUMN_COUNT; c++) {
            Object val = row.getColumn(c);
            cells[c] = val != null ? String.valueOf(val) : "";
        }
        return cells;
    }

    // ── JSONL parsing ────────────────────────────────────────────────────────

    private static void progress(String format, Object... args) {
        System.err.printf("[BPM] " + format + "%n", args);
    }

    /**
     * Internal result of JSONL parsing — aggregates + raw samples + metrics rows.
     */
    private static final class ParseResult {
        final Map<String, LabelAggregate> aggregates;
        final List<TimeBucketBuilder.RawSample> rawSamples;
        final Map<String, BpmTableModel.RowData> metricsRows;

        ParseResult(Map<String, LabelAggregate> aggregates,
                    List<TimeBucketBuilder.RawSample> rawSamples,
                    Map<String, BpmTableModel.RowData> metricsRows) {
            this.aggregates = aggregates;
            this.rawSamples = rawSamples;
            this.metricsRows = metricsRows;
        }
    }
}
