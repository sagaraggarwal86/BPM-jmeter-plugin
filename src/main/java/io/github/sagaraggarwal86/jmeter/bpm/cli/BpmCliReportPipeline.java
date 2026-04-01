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
import io.github.sagaraggarwal86.jmeter.bpm.model.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless pipeline for CLI-based BPM AI report generation.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Parse JSONL file → list of {@link BpmResult}</li>
 *   <li>Build per-label aggregates</li>
 *   <li>Resolve AI provider from {@code ai-reporter.properties}</li>
 *   <li>Validate &amp; ping provider</li>
 *   <li>Build prompt from aggregates + SLA thresholds</li>
 *   <li>Call AI provider</li>
 *   <li>Render HTML report</li>
 *   <li>Save to file</li>
 * </ol>
 *
 * <p>Progress messages go to stderr. stdout is kept clean for piping.</p>
 */
public final class BpmCliReportPipeline {

    private static final ObjectMapper mapper = new ObjectMapper();

    private BpmCliReportPipeline() { }

    /**
     * Executes the full CLI pipeline.
     *
     * @param args parsed and validated CLI arguments
     * @return path to the generated HTML report
     * @throws IOException on any pipeline failure
     */
    public static Path execute(CliArgs args) throws IOException {
        // 1. Parse JSONL file
        progress("Parsing JSONL file: " + args.inputFile());
        ConcurrentHashMap<String, LabelAggregate> aggregates = parseJsonlFile(args.inputFile());
        if (aggregates.isEmpty()) {
            throw new IOException("No valid BPM results found in: " + args.inputFile());
        }
        progress("Parsed %d labels from %s", aggregates.size(), args.inputFile().getFileName());

        // 2. Load BPM properties (SLA thresholds)
        BpmPropertiesManager props = new BpmPropertiesManager();
        props.load();

        // 3. Resolve AI provider
        progress("Loading AI provider configuration...");
        List<AiProviderConfig> providers = AiProviderRegistry.loadConfiguredProviders(args.configFile());
        AiProviderConfig config = providers.stream()
                .filter(p -> p.providerKey.equals(args.provider()))
                .findFirst()
                .orElseThrow(() -> new AiServiceException(
                        "Provider '" + args.provider() + "' not found in " + args.configFile() + ".\n"
                                + "Available providers: " + providers.stream()
                                .map(p -> p.providerKey).toList()));

        // 4. Validate & ping
        progress("Validating %s API key...", config.displayName);
        String pingError = AiProviderRegistry.validateAndPing(config);
        if (pingError != null) {
            throw new AiServiceException("API key validation failed for " + config.displayName + ":\n" + pingError);
        }
        progress("API key validated successfully.");

        // 5. Build prompt
        progress("Building analysis prompt...");
        String systemPrompt = BpmPromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Failed to load BPM AI system prompt from JAR resource.");
        }
        BpmPromptContent prompt = BpmPromptBuilder.build(systemPrompt, aggregates, props);
        progress("Prompt built: system=%d chars, user=%d chars",
                prompt.systemPrompt().length(), prompt.userMessage().length());

        // 6. Call AI
        progress("Calling %s (%s)... This may take 30-60 seconds.", config.displayName, config.model);
        long start = System.currentTimeMillis();
        AiReportService service = new AiReportService(config);
        String markdown = service.generateReport(prompt);
        long elapsed = (System.currentTimeMillis() - start) / 1000;
        progress("Report generated in %ds (%d chars).", elapsed, markdown.length());

        // 7. Render HTML
        progress("Rendering HTML report...");
        String html = BpmHtmlReportRenderer.render(markdown, config.displayName);

        // 8. Save to file
        Path outputPath = args.outputFile();
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(outputPath, html, StandardCharsets.UTF_8);
        progress("Report saved to: %s", outputPath.toAbsolutePath());

        return outputPath;
    }

    /**
     * Parses a BPM JSONL file and builds per-label aggregates.
     */
    private static ConcurrentHashMap<String, LabelAggregate> parseJsonlFile(Path jsonlPath)
            throws IOException {
        ConcurrentHashMap<String, LabelAggregate> aggregates = new ConcurrentHashMap<>();
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
        return aggregates;
    }

    private static void progress(String format, Object... args) {
        System.err.printf("[BPM-CLI] " + format + "%n", args);
    }
}
