package io.github.sagaraggarwal86.jmeter.bpm.report;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmTimeBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI button handler for the "Generate HTML Report" action.
 *
 * <p>Validates inputs, generates the report, and shows a save dialog.</p>
 */
public final class BpmReportLauncher {

    private static final Logger log = LoggerFactory.getLogger(BpmReportLauncher.class);

    private BpmReportLauncher() {
    }

    /**
     * Launches the report generation workflow.
     * No progress dialog needed — generation is near-instant.
     *
     * @param parentComponent Swing parent for dialogs
     * @param aggregates      per-label aggregates (must not be empty)
     * @param props           properties manager with SLA thresholds
     * @param outputDir       directory to save the report
     * @param triggerButton   the button that was clicked (disabled during generation)
     * @param renderConfig    rendering configuration
     * @param timeBuckets     global time-series data
     * @param perLabelBuckets per-label time-series data
     * @param metricsTable    metrics table data
     */
    public static void launch(Component parentComponent,
                              ConcurrentHashMap<String, LabelAggregate> aggregates,
                              BpmPropertiesManager props,
                              Path outputDir,
                              JButton triggerButton,
                              BpmHtmlReportRenderer.RenderConfig renderConfig,
                              List<BpmTimeBucket> timeBuckets,
                              Map<String, List<BpmTimeBucket>> perLabelBuckets,
                              List<String[]> metricsTable) {
        // Validate inputs
        if (aggregates == null || aggregates.isEmpty()) {
            JOptionPane.showMessageDialog(parentComponent,
                    "No performance data available.\n\n"
                            + "Run a test or load a JSONL file first.",
                    "BPM Report", JOptionPane.WARNING_MESSAGE);
            return;
        }

        triggerButton.setEnabled(false);

        try {
            // Generate HTML
            BpmReportCoordinator.GenerateResult result =
                    BpmReportCoordinator.generateHtml(
                            aggregates, props, renderConfig,
                            timeBuckets, perLabelBuckets, metricsTable);

            // Show save dialog
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save BPM Report");
            chooser.setFileFilter(new FileNameExtensionFilter("HTML Files (*.html)", "html"));
            chooser.setSelectedFile(new File(outputDir.toFile(), result.suggestedFilename()));

            int choice = chooser.showSaveDialog(parentComponent);
            if (choice == JFileChooser.APPROVE_OPTION) {
                File selected = chooser.getSelectedFile();
                if (!selected.getName().toLowerCase().endsWith(".html")) {
                    selected = new File(selected.getAbsolutePath() + ".html");
                }
                try {
                    Files.createDirectories(selected.toPath().getParent());
                    Files.writeString(selected.toPath(), result.html(), StandardCharsets.UTF_8);
                    log.info("BPM Report: Saved to {}", selected);
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(selected.toURI());
                    }
                } catch (Exception ex) {
                    log.warn("BPM Report: Failed to save report", ex);
                    JOptionPane.showMessageDialog(parentComponent,
                            "Failed to save report:\n\n" + ex.getMessage(),
                            "BPM Report Error", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                // User cancelled — save to default location so report isn't lost
                try {
                    Path defaultPath = outputDir.resolve(result.suggestedFilename());
                    Files.createDirectories(outputDir);
                    Files.writeString(defaultPath, result.html(), StandardCharsets.UTF_8);
                    log.info("BPM Report: Save cancelled. Report saved to default: {}", defaultPath);
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(defaultPath.toUri());
                    }
                } catch (Exception ex) {
                    log.warn("BPM Report: Failed to save default report", ex);
                }
            }
        } catch (Exception e) {
            log.warn("BPM Report: Generation failed.", e);
            JOptionPane.showMessageDialog(parentComponent,
                    "Report generation failed:\n\n" + e.getMessage(),
                    "BPM Report Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            triggerButton.setEnabled(true);
        }
    }
}
