package io.github.sagaraggarwal86.jmeter.bpm.ai.report;

import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.bpm.ai.provider.AiProviderRegistry;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.core.LabelAggregate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
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

    private BpmAiReportLauncher() { }

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
                              JButton triggerButton) {
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
        var futureRef = new Object() { java.util.concurrent.Future<?> future; };
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

                // 3. Generate report
                Path reportPath = BpmAiReportCoordinator.generate(
                        aggregates, props, providerConfig, outputDir);

                // 4. Success
                if (!cancelled.get()) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        triggerButton.setEnabled(true);
                        log.info("BPM AI: Report opened: {}", reportPath);
                    });
                }

            } catch (Exception e) {
                log.warn("BPM AI: Report generation failed: {}", e.getMessage());
                if (!cancelled.get()) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        triggerButton.setEnabled(true);
                        JOptionPane.showMessageDialog(parentComponent,
                                "AI report generation failed:\n\n" + e.getMessage(),
                                "BPM AI Report Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
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
                futureRef.future.cancel(true);
                triggerButton.setEnabled(true);
            }
        });

        progressDialog.setVisible(true);
    }
}
