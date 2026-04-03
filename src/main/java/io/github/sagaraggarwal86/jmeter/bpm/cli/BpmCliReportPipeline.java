package io.github.sagaraggarwal86.jmeter.bpm.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptContent;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptLoader;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderRegistry;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiReportService;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiServiceException;
import io.github.sagaraggarwal86.jmeter.bpm.ai.report.BpmHtmlReportRenderer;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.gui.BpmTableModel;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;

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
 * Headless pipeline for CLI-based BPM AI report generation.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Parse JSONL file → per-label aggregates</li>
 *   <li>Apply label filters (--search / --regex / --exclude)</li>
 *   <li>Load BPM properties (SLA thresholds)</li>
 *   <li>Resolve AI provider config</li>
 *   <li>Validate &amp; ping provider</li>
 *   <li>Build prompt from aggregates + SLA thresholds + metadata</li>
 *   <li>Call AI provider</li>
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
     * Executes the full AI report pipeline.
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
            progress("Filter applied: %d → %d labels (search=%s, regex=%s, exclude=%s)",
                    beforeCount, aggregates.size(), args.search(), args.regex(), args.exclude());
            if (aggregates.isEmpty()) {
                throw new BpmParseException("No labels remaining after filter. Check --search pattern.");
            }
        }

        // 3. Load BPM properties (SLA thresholds)
        BpmPropertiesManager props = new BpmPropertiesManager();
        props.load();

        // 4. Resolve AI provider config
        Path configPath = resolveConfigFile(args);
        progress("Loading AI provider configuration from: %s", configPath);
        List<AiProviderConfig> providers = AiProviderRegistry.loadConfiguredProviders(configPath);
        if (providers.isEmpty()) {
            throw new AiServiceException(
                    "No AI providers configured in " + configPath + ".\n"
                            + "Add at least one provider API key to ai-reporter.properties.");
        }
        AiProviderConfig config = resolveProvider(args, providers, configPath);

        // 5. Validate & ping
        progress("Validating %s API key...", config.displayName);
        String pingError = AiProviderRegistry.validateAndPing(config);
        if (pingError != null) {
            throw new AiServiceException("API key validation failed for " + config.displayName + ":\n" + pingError);
        }
        progress("API key validated successfully.");

        // 6. Build prompt
        progress("Building analysis prompt...");
        String systemPrompt = BpmPromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Failed to load BPM AI system prompt from JAR resource.");
        }
        TimeBucketBuilder.GroupedResult grouped = TimeBucketBuilder.buildGrouped(
                parsed.rawSamples, args.chartInterval());
        List<BpmTimeBucket> timeBuckets = grouped.globalBuckets;
        if (!timeBuckets.isEmpty()) {
            progress("Built %d time buckets (%ds interval) for trend analysis and charts.",
                    timeBuckets.size(), grouped.intervalSeconds);
        }
        String virtualUsersStr = args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "";
        BpmPromptContent prompt = BpmPromptBuilder.build(
                systemPrompt, aggregates, props,
                args.scenarioName(), args.description(),
                virtualUsersStr,
                timeBuckets);
        progress("Prompt built: system=%d chars, user=%d chars%s",
                prompt.systemPrompt().length(), prompt.userMessage().length(),
                prompt.wasTruncated()
                        ? String.format(" (truncated: %d of %d labels included)",
                        prompt.includedLabels(), prompt.totalLabels())
                        : "");

        // 7. Call AI
        progress("Calling %s (%s)... This may take 30-60 seconds.", config.displayName, config.model);
        long start = System.currentTimeMillis();
        AiReportService service = new AiReportService(config);
        String markdown = service.generateReport(prompt);
        long elapsed = (System.currentTimeMillis() - start) / 1000;
        progress("Report generated in %ds (%d chars).", elapsed, markdown.length());

        // 8. Render HTML
        progress("Rendering HTML report...");
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
                config.displayName,
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
                props.getSlaFcpPoor(), props.getSlaTtfbPoor(), props.getSlaClsPoor(),
                prompt.totalLabels(), prompt.includedLabels());
        List<String[]> metricsTable = buildMetricsTable(parsed.metricsRows, aggregates);
        String html = BpmHtmlReportRenderer.render(markdown, renderConfig,
                timeBuckets, grouped.perLabelBuckets, metricsTable);

        // 9. Save to file
        Path outputPath = args.outputFile();
        if (outputPath == null) {
            String ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            outputPath = Path.of("BPM_" + config.displayName + "_Report_" + ts + ".html");
        }
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(outputPath, html, StandardCharsets.UTF_8);
        progress("Report saved to: %s", outputPath.toAbsolutePath());

        return outputPath;
    }

    /**
     * Resolves the AI config file path. If {@code --config} was provided, uses
     * that. Otherwise, looks at {@code $JMETER_HOME/bin/ai-reporter.properties}.
     */
    private static Path resolveConfigFile(CliArgs args) throws IOException {
        if (args.configFile() != null) {
            return args.configFile();
        }

        // Try JMETER_HOME
        String jmeterHome = System.getProperty("bpm.jmeter.home");
        if (jmeterHome == null || jmeterHome.isBlank()) {
            jmeterHome = System.getenv("JMETER_HOME");
        }
        if (jmeterHome != null && !jmeterHome.isBlank()) {
            Path defaultConfig = Path.of(jmeterHome, "bin", "ai-reporter.properties");
            if (Files.isRegularFile(defaultConfig)) {
                return defaultConfig;
            }
        }

        throw new IOException(
                "Cannot locate ai-reporter.properties.\n"
                        + "Either:\n"
                        + "  1. Set JMETER_HOME and place ai-reporter.properties in $JMETER_HOME/bin/\n"
                        + "  2. Use --config <absolute-path-to-ai-reporter.properties>");
    }

    // ── Config resolution ────────────────────────────────────────────────────

    /**
     * Resolves the AI provider. If {@code --provider} was specified, looks up
     * that provider. Otherwise, picks the first configured provider.
     */
    private static AiProviderConfig resolveProvider(CliArgs args, List<AiProviderConfig> providers,
                                                    Path configPath) throws AiServiceException {
        if (args.provider() != null) {
            return providers.stream()
                    .filter(p -> p.providerKey.equals(args.provider()))
                    .findFirst()
                    .orElseThrow(() -> new AiServiceException(
                            "Provider '" + args.provider() + "' not found in " + configPath + ".\n"
                                    + "Available providers: " + providers.stream()
                                    .map(p -> p.providerKey).toList()));
        }

        // Auto-select first configured provider
        AiProviderConfig first = providers.get(0);
        progress("Auto-selected provider: %s (first configured). Use --provider <name> to choose a different one.",
                first.displayName);
        return first;
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
        System.err.printf("[BPM-AI] " + format + "%n", args);
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
