package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderRegistry;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GUI button handler for the "Generate AI Report" action.
 *
 * <p>Validates inputs, shows a progress dialog, runs the AI report generation
 * on a background thread, and handles cancellation and errors.</p>
 */
public final class BpmAiReportLauncher {

    private static final Logger log = LoggerFactory.getLogger(BpmAiReportLauncher.class);
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BPM-AI-Report");
        t.setDaemon(true);
        return t;
    });

    private BpmAiReportLauncher() {
    }

    /**
     * Launches the AI report generation workflow.
     *
     * @param parentComponent Swing parent for dialogs
     * @param aggregates      per-label aggregates (must not be empty)
     * @param props           properties manager with SLA thresholds
     * @param providerConfig  selected AI provider configuration
     * @param outputDir       directory to save the report
     * @param triggerButton   the button that was clicked (disabled during generation)
     */
    public static void launch(Component parentComponent,
                              ConcurrentHashMap<String, LabelAggregate> aggregates,
                              BpmPropertiesManager props,
                              AiProviderConfig providerConfig,
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
                    "BPM AI Report", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (providerConfig == null) {
            JOptionPane.showMessageDialog(parentComponent,
                    "No AI provider selected.\n\n"
                            + "Please configure at least one provider in:\n"
                            + "  <JMETER_HOME>/bin/ai-reporter.properties\n\n"
                            + "Then select it from the dropdown.",
                    "BPM AI Report", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Disable button
        triggerButton.setEnabled(false);

        // Create progress dialog
        JDialog progressDialog = new JDialog(
                SwingUtilities.getWindowAncestor(parentComponent),
                "Generating AI Report...",
                Dialog.ModalityType.MODELESS);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel statusLabel = new JLabel("Validating " + providerConfig.displayName + " API key...");
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        JButton cancelButton = new JButton("Cancel");

        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(cancelButton, BorderLayout.SOUTH);
        progressDialog.setContentPane(panel);
        progressDialog.setSize(400, 130);
        progressDialog.setLocationRelativeTo(parentComponent);

        AtomicBoolean cancelled = new AtomicBoolean(false);

        // Submit background task
        var futureRef = new Object() {
            java.util.concurrent.Future<?> future;
        };
        futureRef.future = executor.submit(() -> {
            try {
                // 1. Validate API key via ping
                String pingError = AiProviderRegistry.validateAndPing(providerConfig);
                if (pingError != null) {
                    if (!cancelled.get()) {
                        SwingUtilities.invokeLater(() -> {
                            progressDialog.dispose();
                            triggerButton.setEnabled(true);
                            JOptionPane.showMessageDialog(parentComponent, pingError,
                                    "BPM AI — API Key Validation Failed", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                    return;
                }

                if (cancelled.get()) return;

                // 2. Update status
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Calling " + providerConfig.displayName
                                + "... (this may take 30-60 seconds)"));

                // 3. Generate HTML
                BpmAiReportCoordinator.GenerateResult result =
                        BpmAiReportCoordinator.generateHtml(
                                aggregates, props, providerConfig,
                                renderConfig, timeBuckets, perLabelBuckets, metricsTable);

                if (cancelled.get()) return;

                // 4. Show save dialog on EDT
                SwingUtilities.invokeAndWait(() -> {
                    progressDialog.dispose();
                    triggerButton.setEnabled(true);

                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("Save BPM AI Report");
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
                            log.info("BPM AI: Report saved to {}", selected);
                            // Open in browser
                            if (Desktop.isDesktopSupported()
                                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(selected.toURI());
                            }
                        } catch (Exception ex) {
                            log.warn("BPM AI: Failed to save report", ex);
                            JOptionPane.showMessageDialog(parentComponent,
                                    "Failed to save report:\n\n" + ex.getMessage(),
                                    "BPM AI Report Error", JOptionPane.ERROR_MESSAGE);
                        }
                    } else {
                        // User cancelled — save to default location so report isn't lost
                        try {
                            Path defaultPath = outputDir.resolve(result.suggestedFilename());
                            Files.createDirectories(outputDir);
                            Files.writeString(defaultPath, result.html(), StandardCharsets.UTF_8);
                            log.info("BPM AI: Save cancelled. Report saved to default: {}", defaultPath);
                            if (Desktop.isDesktopSupported()
                                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(defaultPath.toUri());
                            }
                        } catch (Exception ex) {
                            log.warn("BPM AI: Failed to save default report", ex);
                        }
                    }
                });

            } catch (Exception e) {
                // Suppress if user cancelled or thread was interrupted by cancellation
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    log.info("BPM AI: Report generation cancelled by user.");
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        triggerButton.setEnabled(true);
                    });
                    return;
                }
                log.warn("BPM AI: Report generation failed.", e);
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    triggerButton.setEnabled(true);
                    JOptionPane.showMessageDialog(parentComponent,
                            "AI report generation failed:\n\n" + e.getMessage(),
                            "BPM AI Report Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });

        // Cancel handler
        cancelButton.addActionListener(e -> {
            cancelled.set(true);
            futureRef.future.cancel(true);
            progressDialog.dispose();
            triggerButton.setEnabled(true);
        });

        progressDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelled.set(true);
                if (futureRef.future != null) {
                    futureRef.future.cancel(true);
                }
                triggerButton.setEnabled(true);
            }
        });

        progressDialog.setVisible(true);
    }
}
