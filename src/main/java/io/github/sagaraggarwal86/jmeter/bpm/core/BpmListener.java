package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.gui.BpmListenerGui;
import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.output.JsonlWriter;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main BPM listener — passive consumer of browser performance metrics.
 *
 * <p>Implements {@link SampleListener} (fires on every sampler), {@link TestStateListener}
 * (test lifecycle), and {@link Clearable} (Clear/Clear All support).</p>
 *
 * <h2>Collect-once, read-many</h2>
 * <p>The actual CDP metric collection is delegated to a shared {@link BpmCollector} instance.
 * Each {@code BpmListener} instance independently reads the collected {@link BpmResult} and
 * writes to its <b>own</b> JSONL file and GUI queue. Multiple listeners never inflate metrics
 * because the collector runs exactly once per sample per thread.</p>
 *
 * <h2>Clone delegation</h2>
 * <p>JMeter's {@code AbstractTestElement.clone()} creates new instances via the no-arg
 * constructor — transient fields are NOT shared with the original. Per-thread clones
 * have {@code testInitialized=false}, so {@link #sampleOccurred} delegates to the
 * primary registered in {@link #primaryByName}. Only the primary owns mutable state
 * (queue, rawResults, writer). All shared structures are thread-safe
 * ({@link ConcurrentLinkedQueue}, {@link Collections#synchronizedList},
 * {@link ConcurrentHashMap}).</p>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are caught, logged, and handled via the collector's error handler.
 * No exception ever propagates to JMeter. The parent sampler result is never affected.</p>
 */
public class BpmListener extends AbstractTestElement
        implements SampleListener, TestStateListener, Clearable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmListener.class);
    /**
     * Active listener registry. Maps each distinct BpmListener element key to its instance.
     * Used by the GUI to look up the running listener for a given element.
     * Cleared per-element in testEnded() so each run starts fresh.
     */
    private static final ConcurrentHashMap<String, BpmListener> primaryByName = new ConcurrentHashMap<>();
    /**
     * Output-path dedup: maps each resolved JSONL output path to the first BpmListener that
     * claimed it. Prevents multiple listeners with different element keys but the same output
     * path from writing to the same file (data corruption). Second+ listeners skip JSONL setup
     * and the null-check in sampleOccurred() handles them safely.
     */
    private static final ConcurrentHashMap<String, BpmListener> primaryByOutputPath = new ConcurrentHashMap<>();
    /**
     * Pre-flight coordination: ensures the file-exists scan runs exactly once per test run.
     * The first BpmListener primary to call testStarted() wins the CAS and performs the scan
     * across ALL enabled BpmListener elements. Subsequent primaries read {@link #globalFileDecision}.
     * Reset in testEnded() when the last primary cleans up.
     */
    private static final AtomicBoolean preFlightDone = new AtomicBoolean(false);
    /**
     * CLI auto-enable flag: ensures only one disabled BpmListener is auto-enabled when
     * {@code -Jbpm.output} is passed in non-GUI mode. First listener wins; others stay disabled.
     * Reset in testEnded() when the last primary cleans up.
     */
    private static volatile boolean cliAutoEnabled = false;
    private static volatile BpmListener activeInstance;
    /**
     * Global file-exists decision, set by the pre-flight scan.
     * {@code OVERWRITE} = proceed (no conflicts or user chose Overwrite).
     * {@code DONT_START} = user chose "Don't Start JMeter Engine" — all listeners skip setup.
     * {@code null} = pre-flight has not run yet.
     */
    private static volatile FileOpenMode globalFileDecision;
    /**
     * True only when this instance completed a full {@link #testStarted(String)} (i.e. the test
     * actually started). False for DONT_START returns and for clones that never ran testStarted.
     * Guards {@link #testEnded(String)} so only the owner instance notifies the GUI.
     */
    private transient volatile boolean testActuallyStarted = false;
    /**
     * Engine reference cached at the very start of testStarted() for use in stopTestEngine().
     */
    private transient org.apache.jmeter.engine.JMeterEngine cachedEngine;
    private transient BpmPropertiesManager propertiesManager;
    private transient JsonlWriter jsonlWriter;

    // ── Per-test state (reset in testStarted) ──────────────────────────────────────────────

    private transient volatile boolean testInitialized;
    private transient ConcurrentLinkedQueue<BpmResult> guiUpdateQueue;
    private transient ConcurrentHashMap<String, LabelAggregate> labelAggregates;
    /**
     * Authoritative raw results for this listener. Written by sampleOccurred() on JMeter
     * threads, read by the GUI on EDT. The GUI reads from this list on element switch and
     * at testEnded — no queue draining or per-element caching needed (Aggregate Report pattern).
     */
    private transient List<BpmResult> rawResults;

    /**
     * Returns true if the pre-flight scan resolved to DONT_START — the user chose
     * "Don't Start JMeter Engine" in the file-exists dialog. Used by the GUI to
     * suppress testStarted clear when the test was cancelled before starting.
     */
    public static boolean isDontStartPending() {
        return globalFileDecision == FileOpenMode.DONT_START;
    }

    /**
     * Returns the currently active (initialized) BpmListener instance, or null
     * if no test is running.
     *
     * @deprecated Use {@link #getPrimaryForElement(String)} for per-element lookup.
     */
    public static BpmListener getActiveInstance() {
        return activeInstance;
    }

    /**
     * Returns the primary (initialized) BpmListener for the given element key,
     * or null if no primary is registered. The key is a composite of UUID + "|" + output path.
     * This is the per-element equivalent of {@link #getActiveInstance()}.
     */
    public static BpmListener getPrimaryForElement(String elementKey) {
        if (elementKey == null || elementKey.isEmpty()) return null;
        return primaryByName.get(elementKey);
    }

    /**
     * Builds the composite element key from a TestElement's properties.
     * Used by the GUI to look up the primary for a given element.
     */
    public static String buildElementKey(org.apache.jmeter.testelement.TestElement element) {
        String elementId = element.getPropertyAsString(BpmConstants.TEST_ELEMENT_ID, "").trim();
        if (elementId.isEmpty()) {
            elementId = element.getName();
        }
        String outputPath = element.getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, "").trim();
        return elementId + "|" + outputPath;
    }

    /**
     * Returns true if any BpmListener primary is currently running.
     */
    public static boolean isAnyTestRunning() {
        return !primaryByName.isEmpty();
    }

    /**
     * Truncates a label to 15 characters for log summary formatting.
     */
    private static String truncateLabel(String label) {
        return label.length() > 15 ? label.substring(0, 12) + "..." : label;
    }

    /**
     * Instance convenience method — delegates to {@link #buildElementKey(TestElement)}.
     */
    private String buildElementKey() {
        return buildElementKey(this);
    }

    // ── CLI auto-enable ──────────────────────────────────────────────────────────────────

    /**
     * Auto-enables one disabled BpmListener in non-GUI mode when {@code -Jbpm.output} is set.
     * JMeter calls this during execution tree building — before {@link #testStarted()}.
     * Only the first disabled listener is auto-enabled; subsequent ones stay disabled.
     * In GUI mode, always defers to the user's enabled/disabled setting.
     */
    @Override
    public boolean isEnabled() {
        if (!super.isEnabled() && !cliAutoEnabled) {
            try {
                if (org.apache.jmeter.gui.GuiPackage.getInstance() == null) {
                    String jFlag = JMeterUtils.getProperty(BpmConstants.PROP_BPM_OUTPUT);
                    if (jFlag != null && !jFlag.isBlank()) {
                        cliAutoEnabled = true;
                        log.info("BPM: Auto-enabled listener '{}' — -Jbpm.output detected in CLI mode.", getName());
                        return true;
                    }
                }
            } catch (Exception ignored) {
                // Non-GUI mode — GuiPackage.getInstance() may throw
                String jFlag = JMeterUtils.getProperty(BpmConstants.PROP_BPM_OUTPUT);
                if (jFlag != null && !jFlag.isBlank()) {
                    cliAutoEnabled = true;
                    log.info("BPM: Auto-enabled listener '{}' — -Jbpm.output detected in CLI mode.", getName());
                    return true;
                }
            }
        }
        return super.isEnabled();
    }

    // ── TestStateListener ──────────────────────────────────────────────────────────────────

    /**
     * Called when the test starts. Initializes own JSONL writer and GUI queue,
     * and acquires the shared {@link BpmCollector}.
     */
    @Override
    public void testStarted() {
        testStarted("");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testStarted(String host) {
        String elementKey = buildElementKey();

        if (globalFileDecision == FileOpenMode.DONT_START) {
            return;
        }
        // First call for a key wins; JMeter clones with the same key skip setup.
        if (primaryByName.putIfAbsent(elementKey, this) != null) {
            log.debug("BPM: testStarted() — clone skipped for key '{}'", elementKey);
            return;
        }
        log.debug("BPM: testStarted() — registered for key '{}'", elementKey);

        testActuallyStarted = false;

        // Cache engine reference early — before any blocking dialog.
        cachedEngine = org.apache.jmeter.threads.JMeterContextService.getContext().getEngine();

        // ── Pre-flight file-exists scan (runs exactly once per test run) ──────────────
        if (preFlightDone.compareAndSet(false, true)) {
            propertiesManager = new BpmPropertiesManager();
            propertiesManager.load();

            java.util.List<Path> conflicts = scanForConflictingFiles();
            if (!conflicts.isEmpty()) {
                FileOpenMode mode = resolveFileOpenMode(conflicts);
                globalFileDecision = mode;
                if (mode == FileOpenMode.DONT_START) {
                    log.info("BPM: User chose 'Don't Start' — {} conflicting file(s). "
                            + "Engine will be stopped.", conflicts.size());
                    primaryByName.remove(elementKey);
                    stopTestEngine(cachedEngine);
                    return;
                }
            } else {
                globalFileDecision = FileOpenMode.OVERWRITE;
            }
        }

        if (globalFileDecision == FileOpenMode.DONT_START) {
            primaryByName.remove(elementKey);
            return;
        }

        // Load configuration
        if (propertiesManager == null) {
            propertiesManager = new BpmPropertiesManager();
            propertiesManager.load();
        }

        // Initialize own JSONL writer — first-writer-wins by resolved output path.
        // If another listener already claimed this path, skip JSONL setup to prevent
        // dual-write corruption. The null-check in sampleOccurred() handles this safely.
        String outputPath = resolveOutputPath();
        if (primaryByOutputPath.putIfAbsent(outputPath, this) == null) {
            jsonlWriter = new JsonlWriter();
            try {
                Path outputFile = Path.of(outputPath);
                jsonlWriter.open(outputFile);
                log.info("BPM: JSONL output file opened (overwrite): {}", outputPath);
            } catch (IOException e) {
                log.warn("BPM: Failed to open JSONL output file: {}. JSONL writing disabled.", outputPath, e);
            }
        } else {
            log.info("BPM: Output path '{}' already claimed by another listener — JSONL writing skipped.", outputPath);
        }

        guiUpdateQueue = new ConcurrentLinkedQueue<>();
        labelAggregates = new ConcurrentHashMap<>();
        rawResults = Collections.synchronizedList(new ArrayList<>());
        testInitialized = true;

        BpmCollector.acquire(propertiesManager);

        try {
            BpmListenerGui gui = BpmListenerGui.getActiveGui();
            if (gui != null) {
                javax.swing.SwingUtilities.invokeLater(gui::testStarted);
            }
        } catch (Exception ignored) {
            // Non-GUI mode — no GUI to notify
        }

        testActuallyStarted = true;
        activeInstance = this;
    }

    // ── SampleListener ─────────────────────────────────────────────────────────────────────

    /**
     * Called for every sampler execution in the Thread Group.
     *
     * <p>Delegates metric collection to the shared {@link BpmCollector} (collect-once-read-many),
     * then writes the result to this listener's own JSONL file and GUI queue.</p>
     */
    @Override
    public void sampleOccurred(SampleEvent event) {
        // Clone delegation: JMeter's AbstractTestElement.clone() creates new instances
        // via the no-arg constructor — transient fields (testInitialized, guiUpdateQueue,
        // rawResults, jsonlWriter) are NOT shared with the original. Per-thread clones
        // must delegate to the primary that ran testStarted().
        if (!testInitialized) {
            BpmListener primary = primaryByName.get(buildElementKey());
            if (primary != null && primary != this && primary.testInitialized) {
                primary.sampleOccurred(event);
            }
            return;
        }
        try {
            SampleResult result = event.getResult();
            var ctx = org.apache.jmeter.threads.JMeterContextService.getContext();
            JMeterVariables vars = ctx != null ? ctx.getVariables() : null;

            if (vars == null) {
                return;
            }

            BpmCollector collector = BpmCollector.getInstance();
            if (collector == null) {
                return;
            }

            BpmResult bpmResult = collector.collectIfNeeded(vars, result);
            if (bpmResult == null) {
                return;
            }

            if (jsonlWriter != null && jsonlWriter.isOpen()) {
                jsonlWriter.write(bpmResult);
            }

            if (rawResults != null) {
                synchronized (rawResults) {
                    if (rawResults.size() < BpmConstants.MAX_RAW_RESULTS) {
                        rawResults.add(bpmResult);
                    }
                }
            }

            if (guiUpdateQueue != null) {
                guiUpdateQueue.offer(bpmResult);
            }

            updateLabelAggregate(bpmResult.samplerLabel(), bpmResult.derived(),
                    bpmResult.webVitals(), bpmResult.network(), bpmResult.console());

        } catch (Exception e) {
            log.warn("BPM: Unexpected error in sampleOccurred", e);
        }
    }

    /**
     * {@inheritDoc} — Not used.
     */
    @Override
    public void sampleStarted(SampleEvent e) {
        // No action needed
    }

    /**
     * {@inheritDoc} — Not used.
     */
    @Override
    public void sampleStopped(SampleEvent e) {
        // No action needed
    }

    // ── TestStateListener (end) ────────────────────────────────────────────────────────────

    /**
     * Called when the test ends. Flushes own JSONL writer, prints log summary,
     * and releases the shared {@link BpmCollector}.
     */
    @Override
    public void testEnded() {
        testEnded("");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testEnded(String host) {
        String elementKey = buildElementKey();
        primaryByName.remove(elementKey);

        if (primaryByName.isEmpty()) {
            preFlightDone.set(false);
            globalFileDecision = null;
            primaryByOutputPath.clear();
            cliAutoEnabled = false;
        }

        if (!testActuallyStarted) {
            log.debug("BPM: testEnded() on instance that never fully started — skipping.");
            return;
        }

        try {
            // Flush and close own JSONL
            if (jsonlWriter != null) {
                jsonlWriter.flush();
            }

            // Print own log summary
            printLogSummary();

            // Final close of own JSONL writer
            if (jsonlWriter != null) {
                jsonlWriter.close();
            }

        } catch (Exception e) {
            log.warn("BPM: Error during testEnded", e);
        }

        // Persist rawResults to the corresponding GUI-tree element so the GUI's
        // configure() finds data after the test ends. The execution tree (this) is a
        // separate copy from the GUI tree — configure() receives GUI-tree elements.
        try {
            var guiPackage = org.apache.jmeter.gui.GuiPackage.getInstance();
            if (guiPackage != null && rawResults != null) {
                String thisKey = buildElementKey();
                var nodes = guiPackage.getTreeModel().getNodesOfType(BpmListener.class);
                for (var node : nodes) {
                    if (node.getTestElement() instanceof BpmListener guiElement
                            && thisKey.equals(buildElementKey(guiElement))) {
                        guiElement.setRawResults(getRawResults());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("BPM: Failed to persist rawResults to GUI tree: {}", e.getMessage());
        }

        // Release shared collector (last listener destroys it, closing all CDP sessions)
        BpmCollector.release();

        // Notify GUI of test end if running in GUI mode.
        try {
            BpmListenerGui gui = BpmListenerGui.getActiveGui();
            if (gui != null) {
                javax.swing.SwingUtilities.invokeLater(gui::testEnded);
            }
        } catch (Exception ignored) {
            // Non-GUI mode — no GUI to notify
        }

        if (activeInstance == this) {
            activeInstance = null;
        }

        testInitialized = false;
    }

    // ── Clearable ──────────────────────────────────────────────────────────────────────────

    /**
     * Resets GUI state: table rows, health counters, label filter, performance score box,
     * column selection to defaults. Does NOT reset CDP sessions, JSONL file, or properties.
     */
    @Override
    public void clearData() {
        if (labelAggregates != null) {
            labelAggregates.clear();
        }
        if (guiUpdateQueue != null) {
            guiUpdateQueue.clear();
        }
        if (rawResults != null) {
            rawResults.clear();
        }
    }

    // ── GUI integration ────────────────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of all raw results collected by this listener.
     * Thread-safe — the backing list is synchronized.
     *
     * @return a copy of the raw results, or an empty list if none
     */
    public List<BpmResult> getRawResults() {
        if (rawResults == null) {
            return Collections.emptyList();
        }
        synchronized (rawResults) {
            return new ArrayList<>(rawResults);
        }
    }

    /**
     * Replaces this listener's raw results with the given list.
     * Used by the GUI to persist file-loaded data into the listener so it
     * survives element switches (Aggregate Report pattern: data lives in the element).
     *
     * @param results the results to store; a defensive copy is made
     */
    public void setRawResults(List<BpmResult> results) {
        rawResults = Collections.synchronizedList(new ArrayList<>(results));
    }

    /**
     * Returns the GUI update queue for draining by the Swing Timer.
     *
     * @return the concurrent queue of BpmResult objects pending GUI display
     */
    public ConcurrentLinkedQueue<BpmResult> getGuiUpdateQueue() {
        return guiUpdateQueue;
    }

    /**
     * Returns the label aggregates map for GUI table population and summary generation.
     *
     * @return concurrent map of label name to running aggregate
     */
    public ConcurrentHashMap<String, LabelAggregate> getLabelAggregates() {
        return labelAggregates;
    }

    /**
     * Returns the properties manager for GUI access to SLA thresholds.
     * Proxies to the shared {@link BpmCollector} if available, falls back to own instance.
     *
     * @return the properties manager, or null if test has not started
     */
    public BpmPropertiesManager getPropertiesManager() {
        BpmCollector collector = BpmCollector.getInstance();
        if (collector != null) {
            return collector.getPropertiesManager();
        }
        return propertiesManager;
    }

    /**
     * Returns an info-bar override message set during Scenario A (no Selenium) or
     * Scenario C (non-Chrome browser). Proxies to the shared {@link BpmCollector}.
     *
     * @return info-bar override text, or null
     */
    public String getInfoBarOverride() {
        BpmCollector collector = BpmCollector.getInstance();
        return collector != null ? collector.getInfoBarOverride() : null;
    }

    // ── Internal: Output path resolution ──────────────────────────────────────────────────

    /**
     * Resolves the effective JSONL output file path, applying priority:
     * {@code -Jbpm.output (highest) → GUI TestElement property → bpm.properties/default (lowest)}.
     */
    private String resolveOutputPath() {
        // 1. -J flag (CLI override — highest priority)
        String jFlag = propertiesManager.getJMeterProperty(BpmConstants.PROP_BPM_OUTPUT);
        if (jFlag != null && !jFlag.isBlank()) {
            return jFlag;
        }
        // 2. GUI TestElement property (set via output path field + Browse button)
        String guiPath = getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, "").trim();
        if (!guiPath.isEmpty()) {
            return guiPath;
        }
        // 3. bpm.properties default (lowest priority)
        return propertiesManager.getOutputPath();
    }

    /**
     * Scans all enabled BpmListener elements in the test plan for existing output files.
     */
    private java.util.List<Path> scanForConflictingFiles() {
        java.util.List<Path> conflicts = new java.util.ArrayList<>();

        String jFlag = propertiesManager.getJMeterProperty(BpmConstants.PROP_BPM_OUTPUT);
        if (jFlag != null && !jFlag.isBlank()) {
            Path p = Path.of(jFlag);
            if (Files.exists(p)) {
                conflicts.add(p);
            }
            return conflicts;
        }

        try {
            org.apache.jmeter.gui.GuiPackage guiPackage = org.apache.jmeter.gui.GuiPackage.getInstance();
            if (guiPackage == null) {
                return conflicts;
            }

            var treeModel = guiPackage.getTreeModel();
            java.util.List<org.apache.jmeter.gui.tree.JMeterTreeNode> nodes =
                    treeModel.getNodesOfType(BpmListener.class);

            for (org.apache.jmeter.gui.tree.JMeterTreeNode node : nodes) {
                if (!node.isEnabled()) continue;
                org.apache.jmeter.testelement.TestElement element = node.getTestElement();
                String guiPath = element.getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, "").trim();
                if (guiPath.isEmpty()) continue;
                Path p = Path.of(guiPath);
                if (Files.exists(p) && !conflicts.contains(p)) {
                    conflicts.add(p);
                }
            }
        } catch (Exception e) {
            log.warn("BPM: Failed to scan test plan for output files: {}", e.getMessage());
        }

        return conflicts;
    }

    /**
     * Shows a single dialog listing all conflicting output files and asks the user
     * whether to overwrite all or stop the engine.
     */
    private FileOpenMode resolveFileOpenMode(java.util.List<Path> conflictingFiles) {
        try {
            if (org.apache.jmeter.gui.GuiPackage.getInstance() == null) {
                log.info("BPM: {} output file(s) exist — overwriting (CLI mode)", conflictingFiles.size());
                return FileOpenMode.OVERWRITE;
            }
        } catch (Exception e) {
            return FileOpenMode.OVERWRITE;
        }

        StringBuilder fileList = new StringBuilder();
        for (Path p : conflictingFiles) {
            fileList.append("  \u2022 ").append(p).append("\n");
        }

        int[] choice = {JOptionPane.CLOSED_OPTION};
        try {
            String[] options = {"Overwrite", "Don't Start JMeter Engine"};
            String message = "BPM output file(s) already exist:\n\n"
                    + fileList
                    + "\nWhat would you like to do?";
            Runnable showDialog = () -> choice[0] = JOptionPane.showOptionDialog(
                    null,
                    message,
                    "BPM — Output File Exists",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);
            if (SwingUtilities.isEventDispatchThread()) {
                showDialog.run();
            } else {
                SwingUtilities.invokeAndWait(showDialog);
            }
        } catch (Exception e) {
            log.warn("BPM: Failed to show file-exists dialog: {}", e.getMessage());
        }

        return switch (choice[0]) {
            case 0 -> FileOpenMode.OVERWRITE;
            default -> FileOpenMode.DONT_START;
        };
    }

    /**
     * Requests an immediate test stop via the JMeter engine.
     */
    private void stopTestEngine(org.apache.jmeter.engine.JMeterEngine engine) {
        if (engine != null) {
            try {
                engine.stopTest(true);
                log.info("BPM: Test engine stop requested (immediate).");
            } catch (Exception e) {
                log.warn("BPM: engine.stopTest(true) failed: {}", e.getMessage());
            }
        } else {
            log.warn("BPM: Cached engine reference is null — relying on ActionRouter fallback.");
        }
        try {
            if (org.apache.jmeter.gui.GuiPackage.getInstance() != null) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        org.apache.jmeter.gui.action.ActionRouter.getInstance()
                                .doActionNow(new java.awt.event.ActionEvent(
                                        this, java.awt.event.ActionEvent.ACTION_PERFORMED, "stop"));
                        log.info("BPM: Test stop action sent via ActionRouter.");
                    } catch (Exception e) {
                        log.warn("BPM: ActionRouter stop failed: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("BPM: Could not access ActionRouter: {}", e.getMessage());
        }
    }

    // ── Internal: Label aggregates ─────────────────────────────────────────────────────────

    /**
     * Updates the running aggregate for a label. Thread-safe via ConcurrentHashMap.computeIfAbsent
     * and synchronized update within LabelAggregate.
     */
    private void updateLabelAggregate(String label, DerivedMetrics derived,
                                      WebVitalsResult vitals, NetworkResult network,
                                      ConsoleResult console) {
        LabelAggregate agg = labelAggregates.computeIfAbsent(label, k -> new LabelAggregate());
        agg.update(derived, vitals, network, console);
    }

    // ── Internal: Summary generation ───────────────────────────────────────────────────────

    /**
     * Prints the log summary table per design doc section 4.4.
     */
    private void printLogSummary() {
        if (labelAggregates == null || labelAggregates.isEmpty()) {
            log.debug("BPM: No samples collected (non-CDP primary or empty run).");
            return;
        }

        log.info("=============== BPM Summary ===============");
        log.info(String.format("%-15s | %7s | %5s | %8s | %7s | %7s | %s",
                "Label", "Samples", "Score", "Rndr(ms)", "Srvr(%)", "Gap(ms)", "Improvement"));

        int totalSamples = 0;
        long totalWeightedScore = 0;
        int totalScoredSamples = 0;
        long totalWeightedRender = 0;
        double totalWeightedRatio = 0;
        long totalWeightedGap = 0;

        for (Map.Entry<String, LabelAggregate> entry : labelAggregates.entrySet()) {
            LabelAggregate agg = entry.getValue();
            int samples = agg.getSampleCount();
            Integer score = agg.getAverageScore();
            String scoreStr = score != null ? String.valueOf(score) : "\u2014";
            long renderTime = agg.getAverageRenderTime();
            double serverRatio = agg.getAverageServerRatio();
            long fcpLcpGap = agg.getAverageFcpLcpGap();
            String improvementArea = agg.getPrimaryImprovementArea();
            String improvementDisplay = BpmConstants.BOTTLENECK_NONE.equals(improvementArea) ? "-" : improvementArea;

            log.info(String.format("%-15s | %7d | %5s | %8d | %6.2f%% | %7d | %s",
                    truncateLabel(entry.getKey()), samples, scoreStr, renderTime,
                    serverRatio, fcpLcpGap, improvementDisplay));

            totalSamples += samples;
            if (score != null) {
                totalWeightedScore += (long) score * samples;
                totalScoredSamples += samples;
            }
            totalWeightedRender += renderTime * samples;
            totalWeightedRatio += serverRatio * samples;
            totalWeightedGap += fcpLcpGap * samples;
        }

        if (totalSamples > 0) {
            String totalScoreStr = totalScoredSamples > 0
                    ? String.valueOf((int) (totalWeightedScore / totalScoredSamples))
                    : "\u2014";
            log.info(String.format("%-15s | %7d | %5s | %8d | %6.2f%% | %7d |",
                    "TOTAL", totalSamples,
                    totalScoreStr,
                    totalWeightedRender / totalSamples,
                    totalWeightedRatio / totalSamples,
                    totalWeightedGap / totalSamples));
        }

        log.info("============================================");

        if (jsonlWriter != null && jsonlWriter.getOutputPath() != null) {
            log.info("BPM results written to: {}", jsonlWriter.getOutputPath());
        }

        BpmCollector collector = BpmCollector.getInstance();
        if (collector != null) {
            long avgCollectionTime = collector.getSamplesCollected() > 0
                    ? collector.getTotalCollectionTimeMs() / collector.getSamplesCollected()
                    : 0;
            log.info("BPM: Health — {} samples collected, {} failures, avg collection time {}ms, CDP re-inits: {}",
                    collector.getSamplesCollected(),
                    collector.getErrorHandler().getFailureCount(),
                    avgCollectionTime,
                    collector.getErrorHandler().getReInitCount());
        }
    }
}
