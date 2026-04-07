package io.github.sagaraggarwal86.jmeter.bpm.report;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full report generation workflow:
 * build report data, render HTML, save file, open browser.
 *
 * <p>Designed to run on a background thread (NOT the EDT).</p>
 */
public final class BpmReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BpmReportCoordinator.class);

    private BpmReportCoordinator() {
    }

    /**
     * Generates the report HTML without saving to disk.
     * Used by the GUI launcher which shows a save dialog.
     */
    public static GenerateResult generateHtml(Map<String, LabelAggregate> aggregates,
                                              BpmPropertiesManager props,
                                              BpmHtmlReportRenderer.RenderConfig renderConfig,
                                              List<BpmTimeBucket> timeBuckets,
                                              Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                              List<String[]> metricsTable) {
        // 1. Build report data
        ReportData data = ReportDataBuilder.build(aggregates, props, timeBuckets);
        log.info("BPM Report: Data built. {} labels, {} breaches, {} headroom risks",
                data.totalLabels(), data.breaches().size(), data.headroomRisks().size());

        // 2. Render HTML
        String html = BpmHtmlReportRenderer.render(data, renderConfig,
                timeBuckets, perLabelBuckets, metricsTable);

        String filename = "BPM_Report_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ".html";
        return new GenerateResult(html, filename);
    }

    /**
     * Generates report and saves to outputDir. Used by CLI pipeline.
     */
    public static Path generate(Map<String, LabelAggregate> aggregates,
                                BpmPropertiesManager props,
                                Path outputDir,
                                BpmHtmlReportRenderer.RenderConfig renderConfig,
                                List<BpmTimeBucket> timeBuckets,
                                Map<String, List<BpmTimeBucket>> perLabelBuckets,
                                List<String[]> metricsTable) throws IOException {
        GenerateResult result = generateHtml(aggregates, props, renderConfig,
                timeBuckets, perLabelBuckets, metricsTable);

        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }
        Path reportPath = outputDir.resolve(result.suggestedFilename());
        Files.writeString(reportPath, result.html(), StandardCharsets.UTF_8);
        log.info("BPM Report: Saved to {}", reportPath);

        openInBrowser(reportPath);
        return reportPath;
    }

    private static void openInBrowser(Path reportPath) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(reportPath.toUri());
            } else {
                log.info("BPM Report: Desktop.browse() not supported. Report saved at: {}", reportPath);
            }
        } catch (Exception e) {
            log.warn("BPM Report: Failed to open report in browser. Report saved at: {}",
                    reportPath, e);
        }
    }

    /**
     * Result of HTML generation (before saving to disk).
     */
    public record GenerateResult(String html, String suggestedFilename) {
    }
}
