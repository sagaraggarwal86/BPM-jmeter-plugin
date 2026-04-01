package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptBuilder;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptContent;
import io.github.sagaraggarwal86.jmeter.bpm.ai.prompt.BpmPromptLoader;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiReportService;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the full AI report generation workflow:
 * load prompt, build user message, call AI, render HTML, save file, open browser.
 *
 * <p>Designed to run on a background thread (NOT the EDT).</p>
 */
public final class BpmAiReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BpmAiReportCoordinator.class);

    private BpmAiReportCoordinator() { }

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
    public static Path generate(ConcurrentHashMap<String, LabelAggregate> aggregates,
                                BpmPropertiesManager props,
                                AiProviderConfig config,
                                Path outputDir) throws IOException {
        // 1. Load system prompt
        String systemPrompt = BpmPromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Failed to load BPM AI system prompt from JAR resource. "
                    + "The plugin JAR may be corrupt.");
        }

        // 2. Build prompt
        BpmPromptContent prompt = BpmPromptBuilder.build(systemPrompt, aggregates, props);
        log.info("BPM AI: Prompt built. System={} chars, User={} chars",
                prompt.systemPrompt().length(), prompt.userMessage().length());

        // 3. Call AI
        AiReportService service = new AiReportService(config);
        String markdown = service.generateReport(prompt);
        log.info("BPM AI: Report generated. {} chars from {}", markdown.length(), config.displayName);

        // 4. Render HTML
        String html = BpmHtmlReportRenderer.render(markdown, config.displayName);

        // 5. Save to file
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        String filename = "bpm-ai-report-" + config.providerKey + "-"
                + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".html";
        Path reportPath = outputDir.resolve(filename);
        Files.writeString(reportPath, html, StandardCharsets.UTF_8);
        log.info("BPM AI: Report saved to {}", reportPath);

        // 6. Open in browser
        openInBrowser(reportPath);

        return reportPath;
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
}
