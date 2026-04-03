package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptContent;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptLoader;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiReportService;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full AI report generation workflow:
 * load prompt, build user message, call AI, render HTML, save file, open browser.
 *
 * <p>Designed to run on a background thread (NOT the EDT).</p>
 */
public final class BpmAiReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BpmAiReportCoordinator.class);

    private BpmAiReportCoordinator() {
    }

    /**
     * Generates an AI-powered browser performance analysis report.
     *
     * @param aggregates per-label aggregates from the test or loaded file
     * @param props      properties manager with SLA thresholds
     * @param config     AI provider configuration
     * @param outputDir  directory to save the HTML report (typically alongside the JSONL file)
     * @return path to the saved HTML report
     * @throws IOException if prompt loading, AI call, or file writing fails
     */
    public static Path generate(Map<String, LabelAggregate> aggregates,
                                BpmPropertiesManager props,
                                AiProviderConfig config,
                                Path outputDir) throws IOException {
        return generate(aggregates, props, config, outputDir,
                new BpmHtmlReportRenderer.RenderConfig(config.displayName, "", "", ""),
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyList());
    }

    /**
     * Generates the AI report HTML without saving to disk.
     * Used by the GUI launcher which shows a save dialog.
     */
    public static GenerateResult generateHtml(Map<String, LabelAggregate> aggregates,
                                              BpmPropertiesManager props,
                                              AiProviderConfig config,
                                              BpmHtmlReportRenderer.RenderConfig renderConfig,
                                              List<BpmTimeBucket> timeBuckets,
                                              Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                              List<String[]> metricsTable) throws IOException {
        String html = buildHtml(aggregates, props, config, renderConfig, timeBuckets, perLabelBuckets, metricsTable);
        String safeName = Character.toUpperCase(config.providerKey.charAt(0)) + config.providerKey.substring(1);
        String filename = "BPM_" + safeName + "_Report_"
                + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ".html";
        return new GenerateResult(html, filename);
    }

    /**
     * Generates an AI-powered browser performance analysis report with full metadata and charts.
     * Saves to outputDir and opens in browser. Used by CLI pipeline.
     */
    public static Path generate(Map<String, LabelAggregate> aggregates,
                                BpmPropertiesManager props,
                                AiProviderConfig config,
                                Path outputDir,
                                BpmHtmlReportRenderer.RenderConfig renderConfig,
                                List<BpmTimeBucket> timeBuckets,
                                Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                List<String[]> metricsTable) throws IOException {
        GenerateResult result = generateHtml(aggregates, props, config, renderConfig,
                timeBuckets, perLabelBuckets, metricsTable);

        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        Path reportPath = outputDir.resolve(result.suggestedFilename());
        Files.writeString(reportPath, result.html(), StandardCharsets.UTF_8);
        log.info("BPM AI: Report saved to {}", reportPath);

        openInBrowser(reportPath);
        return reportPath;
    }

    private static String buildHtml(Map<String, LabelAggregate> aggregates,
                                    BpmPropertiesManager props,
                                    AiProviderConfig config,
                                    BpmHtmlReportRenderer.RenderConfig renderConfig,
                                    List<BpmTimeBucket> timeBuckets,
                                    Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                    List<String[]> metricsTable) throws IOException {
        // 1. Load system prompt
        String systemPrompt = BpmPromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Failed to load BPM AI system prompt from JAR resource. "
                    + "The plugin JAR may be corrupt.");
        }

        // 2. Build prompt
        BpmPromptContent prompt = BpmPromptBuilder.build(systemPrompt, aggregates, props,
                renderConfig.scenarioName, "", renderConfig.virtualUsers, timeBuckets); // CHANGED: pass metadata + time-series to AI prompt
        log.info("BPM AI: Prompt built. System={} chars, User={} chars, truncated={}",
                prompt.systemPrompt().length(), prompt.userMessage().length(), prompt.wasTruncated());

        // Propagate label truncation info to render config
        BpmHtmlReportRenderer.RenderConfig effectiveConfig = renderConfig;
        if (prompt.wasTruncated()) {
            effectiveConfig = new BpmHtmlReportRenderer.RenderConfig(
                    renderConfig.providerName, renderConfig.scenarioName,
                    renderConfig.description, renderConfig.virtualUsers,
                    renderConfig.runDateTime, renderConfig.duration, renderConfig.version,
                    renderConfig.intervalSeconds,
                    renderConfig.slaScoreGood, renderConfig.slaLcpGood,
                    renderConfig.slaFcpGood, renderConfig.slaTtfbGood, renderConfig.slaClsGood,
                    renderConfig.slaScorePoor, renderConfig.slaLcpPoor,
                    renderConfig.slaFcpPoor, renderConfig.slaTtfbPoor, renderConfig.slaClsPoor,
                    prompt.totalLabels(), prompt.includedLabels());
        }

        // 3. Call AI
        AiReportService service = new AiReportService(config);
        String markdown = service.generateReport(prompt);
        log.info("BPM AI: Report generated. {} chars from {}", markdown.length(), config.displayName);

        // 4. Render HTML
        return BpmHtmlReportRenderer.render(markdown, effectiveConfig, timeBuckets, perLabelBuckets, metricsTable);
    }

    private static void openInBrowser(Path reportPath) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(reportPath.toUri());
            } else {
                log.info("BPM AI: Desktop.browse() not supported. Report saved at: {}", reportPath);
            }
        } catch (Exception e) {
            log.warn("BPM AI: Failed to open report in browser: {}. Report saved at: {}",
                    e.getMessage(), reportPath);
        }
    }

    /**
     * Result of HTML generation (before saving to disk).
     */
    public record GenerateResult(String html, String suggestedFilename) {
    }
}
