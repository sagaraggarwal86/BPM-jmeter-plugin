package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.collectors.*;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.error.BpmErrorHandler;
import io.github.sagaraggarwal86.jmeter.bpm.error.LogOnceTracker;
import io.github.sagaraggarwal86.jmeter.bpm.gui.BpmListenerGui; // CHANGED: §5.5 — GUI lifecycle wiring
import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.output.JsonlWriter;
// SummaryJsonWriter intentionally removed — Feature #1: summary JSON generation disabled
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmDebugLogger;
import io.github.sagaraggarwal86.jmeter.bpm.util.ConsoleSanitizer;
import org.apache.jmeter.samplers.Clearable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main BPM listener — captures browser performance metrics from WebDriver Sampler
 * executions via Chrome DevTools Protocol.
 *
 * <p>Implements {@link SampleListener} (fires on every sampler), {@link TestStateListener}
 * (test lifecycle), and {@link Clearable} (Clear/Clear All support).</p>
 *
 * <h2>Lazy Selenium loading</h2>
 * <p>No Selenium types are imported directly. Selenium availability is checked once per
 * test via {@code Class.forName("org.openqa.selenium.chrome.ChromeDriver")}. If absent,
 * BPM operates as a no-op with an info-bar warning.</p>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>{@link ConcurrentLinkedQueue} for GUI result updates</li>
 *   <li>{@link ConcurrentHashMap} for per-thread CDP executors and MetricsBuffers</li>
 *   <li>Atomic counters for health metrics</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are caught, logged, and handled via {@link BpmErrorHandler}.
 * No exception ever propagates to JMeter. The parent sampler result is never affected.</p>
 */
public class BpmListener extends AbstractTestElement
        implements SampleListener, TestStateListener, Clearable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BpmListener.class);

    /**
     * Outcome of the file-exists check performed at test start.
     * Determines whether the JSONL writer opens in append or overwrite mode,
     * or whether the test should be stopped before writing begins.
     */ // CHANGED: Feature #3 — file-exists dialog; CHANGED: Feature #3 (this session) — APPEND removed
    private enum FileOpenMode { OVERWRITE, DONT_START }

    // CHANGED: Static reference to the instance that received testStarted() — cloned instances
    // that receive sampleOccurred() without testStarted() delegate to this active instance.
    private static volatile BpmListener activeInstance;

    /**
     * Set to {@code true} when the user chooses "Don't Start" in the file-exists dialog.
     * Blocks all subsequent clone {@link #testStarted} calls in the same cancelled test run
     * and suppresses the GUI clear in {@link BpmListenerGui#testStarted()}.
     * Cleared only on a successful test start (just before {@code activeInstance = this})
     * or in {@link #testEnded(String)} after the engine stops.
     */ // CHANGED: Defect #2
    private static volatile boolean dontStartPending = false;

    /** Returns true if "Don't Start" was chosen and the engine stop is in progress. */ // CHANGED: Defect #2
    public static boolean isDontStartPending() { return dontStartPending; }

    /**
     * CAS lock — only the first instance to call testStarted() in a run proceeds through full
     * setup (file dialog, writer open, GUI notify). All subsequent clone instances return early.
     * Reset unconditionally in testEnded() so the next run always gets a fresh lock.
     */ // CHANGED: replace activeInstance != null guard — AtomicBoolean CAS is race-free
    private static final AtomicBoolean testStartLock = new AtomicBoolean(false);


    /**
     * True only when this instance completed a full {@link #testStarted(String)} (i.e. the test
     * actually started). False for DONT_START returns and for clones that never ran testStarted.
     * Guards {@link #testEnded(String)} so only the owner instance notifies the GUI.
     */ // CHANGED: Defect #2
    private transient boolean testActuallyStarted = false;

    /** Engine reference cached at the very start of testStarted() for use in stopTestEngine(). */ // CHANGED: Defect #2
    private transient org.apache.jmeter.engine.JMeterEngine cachedEngine;

    /**
     * Returns the currently active (initialized) BpmListener instance, or null
     * if no test is running. Used by BpmListenerGui to drain the correct queue.
     */
    public static BpmListener getActiveInstance() {
        return activeInstance;
    }

    // ── Per-test state (reset in testStarted) ──────────────────────────────────────────────

    private transient BpmPropertiesManager propertiesManager;
    private transient BpmDebugLogger debugLogger;
    private transient JsonlWriter jsonlWriter;
    // summaryJsonWriter removed — Feature #1: summary JSON generation disabled
    private transient BpmErrorHandler errorHandler;
    private transient LogOnceTracker logOnceTracker;
    private transient CdpSessionManager sessionManager;
    private transient ConsoleSanitizer consoleSanitizer;

    // Collectors
    private transient WebVitalsCollector webVitalsCollector;
    private transient NetworkCollector networkCollector;
    private transient RuntimeCollector runtimeCollector;
    private transient ConsoleCollector consoleCollector;
    private transient DerivedMetricsCalculator derivedCalculator;

    // Per-thread CDP executors and buffers
    private transient ConcurrentHashMap<String, CdpCommandExecutor> executorsByThread;
    private transient ConcurrentHashMap<String, MetricsBuffer> buffersByThread;

    /** Queue for GUI updates — drained by the 500ms Swing Timer in BpmListenerGui. */
    private transient ConcurrentLinkedQueue<BpmResult> guiUpdateQueue;

    // Health counters
    private transient AtomicInteger samplesCollected;
    private transient AtomicLong totalCollectionTimeMs;

    // Per-label running aggregates for log summary and summary JSON
    private transient ConcurrentHashMap<String, LabelAggregate> labelAggregates;

    // Selenium availability flag (checked once per test)
    private transient volatile boolean seleniumAvailable;
    private transient volatile boolean seleniumChecked;

    // Info bar override for Scenario A/C states — read by BpmListenerGui.drainGuiQueue() // CHANGED: §5.7
    private transient volatile String infoBarOverride;

    // Per-thread iteration counter
    private transient ConcurrentHashMap<String, AtomicInteger> iterationsByThread;

    // ── TestStateListener ──────────────────────────────────────────────────────────────────

    /**
     * Called when the test starts. Initializes all state, loads configuration,
     * opens the JSONL writer, and resets health counters.
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
        // CHANGED: Defect #2 — if another BpmListener in this test run already chose DONT_START,
        // skip all processing on clones so they don't proceed past the cancelled decision.
        if (dontStartPending) { return; }
        // CHANGED: replace activeInstance != null guard — CAS ensures exactly one instance wins the
        // lock regardless of timing. activeInstance could be stale (non-null from a previous run)
        // and would silently skip the dialog; AtomicBoolean.compareAndSet is always race-free.
        if (!testStartLock.compareAndSet(false, true)) {
            log.debug("BPM: testStarted() — clone instance skipped (CAS lock held by primary instance).");
            return;
        }
        log.debug("BPM: testStarted() — this instance won the CAS lock: {}", System.identityHashCode(this));

        testActuallyStarted = false; // CHANGED: Defect #2 — reset; set true only at successful test start

        // Cache engine reference NOW — called on the engine thread where context is guaranteed to
        // have the engine set. After invokeAndWait (dialog) we may be resuming on the same thread
        // but caching up-front is safer and avoids any ThreadLocal surprises.
        cachedEngine = null; // CHANGED: Defect #2
        try {
            cachedEngine = org.apache.jmeter.threads.JMeterContextService.getContext().getEngine();
        } catch (Exception ignored) {}

        // Load configuration
        propertiesManager = new BpmPropertiesManager();
        propertiesManager.load();

        debugLogger = new BpmDebugLogger(propertiesManager.isDebugEnabled());
        logOnceTracker = new LogOnceTracker();
        errorHandler = new BpmErrorHandler(logOnceTracker);
        sessionManager = new CdpSessionManager();
        consoleSanitizer = new ConsoleSanitizer(propertiesManager.isSanitizeEnabled());

        // Initialize collectors
        webVitalsCollector = new WebVitalsCollector();
        webVitalsCollector.reset(); // CHANGED: §3.5 — explicit reset guards against future singleton reuse
        networkCollector = new NetworkCollector(propertiesManager.getNetworkTopN());
        runtimeCollector = new RuntimeCollector();
        runtimeCollector.reset(); // CHANGED: per-action accuracy — clears per-thread layout/styleRecalc baselines
        consoleCollector = new ConsoleCollector(consoleSanitizer);
        derivedCalculator = new DerivedMetricsCalculator(propertiesManager);

        // Initialize writers
        jsonlWriter = new JsonlWriter();
        // summaryJsonWriter not initialized — Feature #1: summary JSON generation disabled

        String outputPath = resolveOutputPath();
        try {
            Path outputFile = Path.of(outputPath);
            // CHANGED: Feature #3 (this session) — APPEND removed; only Overwrite / Don't Start
            if (Files.exists(outputFile)) {
                // Only show the dialog when the path was explicitly provided by the user.
                // Default-path files are always silently overwritten — no dialog.
                if (isUserProvidedOutputPath()) {
                    FileOpenMode mode = resolveFileOpenMode(outputFile);
                    if (mode == FileOpenMode.DONT_START) {
                        log.info("BPM: Test cancelled — output file conflict: {}", outputFile);
                        dontStartPending = true;
                        stopTestEngine(cachedEngine);
                        return;
                    }
                    // mode == OVERWRITE: fall through to open below
                } else {
                    log.info("BPM: Default output file exists — overwriting: {}", outputFile);
                }
            }
            jsonlWriter.open(outputFile); // always overwrite (TRUNCATE_EXISTING)
            log.info("BPM: JSONL output file opened (overwrite): {}", outputPath);
        } catch (IOException e) {
            log.warn("BPM: Failed to open JSONL output file: {}. JSONL writing disabled.", outputPath, e);
        }

        // Initialize per-thread state
        executorsByThread = new ConcurrentHashMap<>();
        buffersByThread = new ConcurrentHashMap<>();
        guiUpdateQueue = new ConcurrentLinkedQueue<>();
        labelAggregates = new ConcurrentHashMap<>();
        iterationsByThread = new ConcurrentHashMap<>();

        // Health counters
        samplesCollected = new AtomicInteger(0);
        totalCollectionTimeMs = new AtomicLong(0);

        // Selenium check deferred to first sampleOccurred
        seleniumAvailable = false;
        seleniumChecked = false;
        infoBarOverride = null; // CHANGED: §5.7 — reset override on each test start

        debugLogger.log("Test started. Debug mode: {}", propertiesManager.isDebugEnabled());

        // Notify GUI of test start if running in GUI mode // CHANGED: §5.5
        try {
            if (org.apache.jmeter.gui.GuiPackage.getInstance() != null) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    var guiComp = org.apache.jmeter.gui.GuiPackage.getInstance().getGui(this);
                    if (guiComp instanceof BpmListenerGui bpmGui) {
                        bpmGui.testStarted();
                    }
                });
            }
        } catch (Exception ignored) {
            // Non-GUI mode — no GUI to notify
        }

        dontStartPending = false;   // CHANGED: Defect #2 — clear flag; test is actually starting now
        testActuallyStarted = true; // CHANGED: Defect #2 — mark this instance as fully started
        activeInstance = this; // CHANGED: register as the active instance for clone delegation
    }

    // ── SampleListener ─────────────────────────────────────────────────────────────────────

    /**
     * Called for every sampler execution in the Thread Group.
     *
     * <p>Decision tree:</p>
     * <ol>
     *   <li>Is {@code BPM_DevTools} in vars? → collect metrics</li>
     *   <li>Is {@code Browser} in vars and instanceof ChromeDriver? → init CDP session</li>
     *   <li>Otherwise → skip (not a WebDriver thread or non-Chrome)</li>
     * </ol>
     */
    @Override
    public void sampleOccurred(SampleEvent event) {
        // CHANGED: Delegate to the active (initialized) instance — JMeter may invoke
        // sampleOccurred on a cloned BpmListener that never received testStarted().
        // The clone forwards the event to the instance that owns the writers, collectors,
        // and GUI-connected queue.
        if (errorHandler == null) {
            BpmListener active = activeInstance;
            if (active != null && active != this) {
                active.sampleOccurred(event);
            }
            return;
        }
        try {
            SampleResult result = event.getResult();
            var ctx = org.apache.jmeter.threads.JMeterContextService.getContext(); // CHANGED: safe context guard (§2.1)
            JMeterVariables vars = ctx != null ? ctx.getVariables() : null;

            if (vars == null) {
                return;
            }

            String threadName = Thread.currentThread().getName(); // CHANGED: full thread name e.g. "Thread Group 1-1" (§4.2)

            // Check if thread is disabled by error handler
            if (errorHandler.isThreadDisabled(threadName)) {
                return;
            }

            // Check for existing CDP session
            Object devToolsObj = vars.getObject(BpmConstants.VAR_BPM_DEV_TOOLS);
            if (devToolsObj != null) {
                // CDP session exists — collect metrics
                collectMetrics(threadName, result, vars);
                return;
            }

            // No CDP session — try to initialize
            Object browserObj = vars.getObject(BpmConstants.VAR_BROWSER);
            if (browserObj == null) {
                // Not a WebDriver thread — skip silently
                return;
            }

            // Lazy Selenium check (once per test)
            if (!seleniumChecked) {
                checkSeleniumAvailability(threadName);
            }
            if (!seleniumAvailable) {
                return;
            }

            // Check if it's a Chrome/Chromium driver
            if (!isChromeDriver(browserObj, threadName)) {
                return;
            }

            // Initialize CDP session for this thread
            initCdpSession(browserObj, threadName, vars);

        } catch (Exception e) {
            // Never let exceptions propagate to JMeter
            log.warn("BPM: Unexpected error in sampleOccurred: {}", e.getMessage());
            if (debugLogger != null) { // CHANGED: null-guard — debugLogger is transient; may be null if testStarted() was never called
                debugLogger.log("sampleOccurred exception: {}", e.toString());
            }
        }
    }

    /** {@inheritDoc} — Not used. */
    @Override
    public void sampleStarted(SampleEvent e) {
        // No action needed
    }

    /** {@inheritDoc} — Not used. */
    @Override
    public void sampleStopped(SampleEvent e) {
        // No action needed
    }

    // ── TestStateListener (end) ────────────────────────────────────────────────────────────

    /**
     * Called when the test ends. Flushes writers, writes summary, prints log summary,
     * and closes all CDP sessions.
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
        // CHANGED: reset CAS lock unconditionally so the next run always gets a fresh gate
        testStartLock.set(false);
        // CHANGED: Defect #2 — always clear flag on test end so the next run starts clean
        dontStartPending = false;

        // CHANGED: Defect #2 — if this instance never fully started (DONT_START or clone),
        // skip all cleanup and GUI notification to preserve the displayed file data
        if (!testActuallyStarted) {
            log.debug("BPM: testEnded() on instance that never fully started — skipping.");
            return;
        }

        try {
            // Flush and close JSONL
            if (jsonlWriter != null) {
                jsonlWriter.flush();
            }

            // Summary JSON intentionally skipped — Feature #1: generation disabled

            // Print log summary
            printLogSummary();

            // Close all CDP sessions
            closeAllCdpSessions();

            // Final close of JSONL writer
            if (jsonlWriter != null) {
                jsonlWriter.close();
            }

        } catch (Exception e) {
            log.warn("BPM: Error during testEnded: {}", e.getMessage());
        }

        if (debugLogger != null) { // CHANGED: null-guard — testEnded() may be called without testStarted()
            debugLogger.log("Test ended. Total samples collected: {}",
                    samplesCollected != null ? samplesCollected.get() : 0);
        }

        // Notify GUI of test end if running in GUI mode // CHANGED: §5.5
        try {
            if (org.apache.jmeter.gui.GuiPackage.getInstance() != null) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    var guiComp = org.apache.jmeter.gui.GuiPackage.getInstance().getGui(this);
                    if (guiComp instanceof BpmListenerGui bpmGui) {
                        bpmGui.testEnded();
                    }
                });
            }
        } catch (Exception ignored) {
            // Non-GUI mode — no GUI to notify
        }

        if (activeInstance == this) { // CHANGED: clear static reference to allow GC and prevent stale delegation
            activeInstance = null;
        }
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
        if (samplesCollected != null) {
            samplesCollected.set(0);
        }
        if (totalCollectionTimeMs != null) {
            totalCollectionTimeMs.set(0);
        }
        // Note: GUI component reset (table model, score box, filters) is handled
        // by BpmListenerGui.clearData() which calls this method and then resets its own state.
    }

    // ── GUI integration ────────────────────────────────────────────────────────────────────

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
     *
     * @return the properties manager, or null if test has not started
     */
    public BpmPropertiesManager getPropertiesManager() {
        return propertiesManager;
    }

    /**
     * Returns an info-bar override message set during Scenario A (no Selenium) or
     * Scenario C (non-Chrome browser). Null if no override is active.
     * Read by BpmListenerGui.drainGuiQueue() on the EDT.
     *
     * @return info-bar override text, or null // CHANGED: §5.7
     */
    public String getInfoBarOverride() { // CHANGED: §5.7
        return infoBarOverride;
    }

    // ── Internal: Output path resolution ──────────────────────────────────────────────────

    /**
     * Returns {@code true} when the JSONL output path was explicitly set by the user — either
     * via the {@code -Jbpm.output} CLI flag or via the GUI filename field. Returns {@code false}
     * when the path is the bpm.properties/default fallback.
     *
     * <p>Used by {@link #testStarted(String)} to decide whether a file-exists dialog should be
     * shown. Default-path files are always silently overwritten; only user-provided paths trigger
     * the Append/Overwrite/Don't-Start dialog.</p>
     */ // CHANGED: Feature #2
    private boolean isUserProvidedOutputPath() {
        String jFlag = propertiesManager.getJMeterProperty("bpm.output");
        if (jFlag != null && !jFlag.isBlank()) {
            return true;
        }
        String guiPath = getPropertyAsString(BpmConstants.TEST_ELEMENT_OUTPUT_PATH, "").trim();
        return !guiPath.isEmpty();
    }

    /**
     * Resolves the effective JSONL output file path, applying priority:
     * {@code -Jbpm.output (highest) → GUI TestElement property → bpm.properties/default (lowest)}.
     */
    private String resolveOutputPath() { // CHANGED: restored missing Javadoc opening
        // 1. -J flag (CLI override — highest priority)
        String jFlag = propertiesManager.getJMeterProperty("bpm.output");
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
     * Determines how to handle an existing JSONL output file.
     *
     * <p>In CLI mode (no GuiPackage): always overwrites silently — no dialog available.</p>
     * <p>In GUI mode: blocks the calling thread via {@code SwingUtilities.invokeAndWait}
     * to show a modal JOptionPane with two choices: Overwrite or Don't Start.
     * Returns {@link FileOpenMode#DONT_START} if the dialog is dismissed without a selection.</p>
     *
     * @param outputFile the file that already exists on disk
     * @return the chosen open mode; never null
     */ // CHANGED: Feature #3; CHANGED: Feature #3 (this session) — removed Append option
    private FileOpenMode resolveFileOpenMode(Path outputFile) {
        try {
            if (org.apache.jmeter.gui.GuiPackage.getInstance() == null) {
                // CLI mode — overwrite silently; no interactive dialog available
                log.info("BPM: Output file exists — overwriting (CLI mode): {}", outputFile);
                return FileOpenMode.OVERWRITE;
            }
        } catch (Exception e) {
            return FileOpenMode.OVERWRITE;
        }

        // GUI mode — ask the user on the EDT, block until answered
        // Options: index 0 = Overwrite, index 1 / close = Don't Start
        int[] choice = {JOptionPane.CLOSED_OPTION};
        try {
            String[] options = {"Overwrite", "Don't Start"}; // CHANGED: Feature #3 — Append removed
            Runnable showDialog = () -> choice[0] = JOptionPane.showOptionDialog(
                    null,
                    "Output file already exists:\n" + outputFile
                            + "\n\nWhat would you like to do?",
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
            case 0 -> FileOpenMode.OVERWRITE;  // CHANGED: Feature #3 — index 0 is now Overwrite
            default -> FileOpenMode.DONT_START;
        };
    }

    /**
     * Requests an immediate test stop via the JMeter engine.
     * Uses the engine reference cached at the top of {@link #testStarted(String)} (obtained on
     * the engine thread before the dialog blocked it). Falls back to the GUI ActionRouter so that
     * even if the context reference is unavailable the test can still be stopped.
     *
     * @param engine the cached engine reference; may be null, in which case only the ActionRouter
     *               fallback is attempted
     */ // CHANGED: Defect #2 — accepts pre-cached engine; adds ActionRouter fallback
    private void stopTestEngine(org.apache.jmeter.engine.JMeterEngine engine) {
        // Primary: stop via cached engine reference (synchronous — prevents threads from starting)
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
        // Fallback: GUI ActionRouter (equivalent to pressing Stop in JMeter UI)
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

    // ── Internal: Selenium check ───────────────────────────────────────────────────────────

    /**
     * Checks Selenium availability via Class.forName. Called once per test.
     */
    private synchronized void checkSeleniumAvailability(String threadName) {
        if (seleniumChecked) {
            return;
        }
        seleniumChecked = true;
        try {
            Class.forName("org.openqa.selenium.chrome.ChromeDriver");
            seleniumAvailable = true;
            debugLogger.log("Selenium/ChromeDriver found on classpath.");
        } catch (ClassNotFoundException e) {
            seleniumAvailable = false;
            infoBarOverride = BpmConstants.INFO_NO_SELENIUM; // CHANGED: §5.7 — Scenario A info bar
            log.warn("BPM: {}", BpmConstants.INFO_NO_SELENIUM);
        }
    }

    /**
     * Checks if the browser object is a ChromeDriver (without importing Selenium).
     * Uses class name string comparison to avoid ClassNotFoundException.
     */
    private boolean isChromeDriver(Object browserObj, String threadName) {
        String className = browserObj.getClass().getName();
        // Match ChromeDriver or any ChromiumDriver subclass
        if (className.contains("ChromeDriver") || className.contains("ChromiumDriver")) {
            return true;
        }
        logOnceTracker.warnOnce(threadName, "non-chrome",
                "Non-Chrome browser detected (" + className + "). CDP metrics require Chrome/Chromium.");
        infoBarOverride = BpmConstants.INFO_NON_CHROME; // CHANGED: §5.7 — Scenario C info bar
        return false;
    }

    // ── Internal: CDP session init ─────────────────────────────────────────────────────────

    /**
     * Initializes a CDP session for the given thread's browser.
     */
    private void initCdpSession(Object browserObj, String threadName, JMeterVariables vars) {
        long startMs = System.currentTimeMillis();
        try {
            // Create executor via factory (no Selenium imports needed in BpmListener)
            CdpCommandExecutor executor = ChromeCdpCommandExecutor.fromBrowserObject(browserObj);

            // Create per-thread buffer
            MetricsBuffer buffer = new MetricsBuffer();

            // Open CDP session (enable domains, inject observers)
            sessionManager.openSession(executor);

            // Store in per-thread maps
            executorsByThread.put(threadName, executor);
            buffersByThread.put(threadName, buffer);

            // Store marker in JMeterVariables for subsequent sampleOccurred checks
            vars.putObject(BpmConstants.VAR_BPM_DEV_TOOLS, executor);
            vars.putObject(BpmConstants.VAR_BPM_EVENT_BUFFER, buffer);

            long durationMs = System.currentTimeMillis() - startMs;
            debugLogger.logCdpSessionOpened(threadName, durationMs);

        } catch (Exception e) {
            logOnceTracker.warnOnce(threadName, "cdp-init-failed",
                    "Failed to initialize CDP session: " + e.getMessage());
            errorHandler.handleSessionError(threadName, e);
        }
    }

    // ── Internal: Metric collection ────────────────────────────────────────────────────────

    /**
     * Collects all enabled metrics, computes derived metrics, writes JSONL, and queues for GUI.
     */
    private void collectMetrics(String threadName, SampleResult sampleResult, JMeterVariables vars) {
        CdpCommandExecutor executor = executorsByThread.get(threadName);
        MetricsBuffer buffer = buffersByThread.get(threadName);

        if (executor == null || buffer == null) {
            return;
        }

        // Check if re-init is needed
        if (errorHandler.needsReInit(threadName)) {
            attemptReInit(threadName, vars);
            executor = executorsByThread.get(threadName);
            buffer = buffersByThread.get(threadName);
            if (executor == null || errorHandler.isThreadDisabled(threadName)) {
                return;
            }
        }

        long collectionStart = System.currentTimeMillis();

        try {
            // Transfer browser-side buffered events to Java-side MetricsBuffer
            sessionManager.transferBufferedEvents(executor, buffer);

            // Re-inject observers if page navigation destroyed the JavaScript context. // CHANGED: post-navigation observer re-injection
            // Returns true if a navigation was detected — used to reset per-thread delta baselines.
            boolean navigated = sessionManager.ensureObserversInjected(executor); // CHANGED: per-action accuracy
            if (navigated) { // CHANGED: per-action accuracy — reset collector baselines so deltas start fresh on new page
                webVitalsCollector.resetThreadState(threadName);
                runtimeCollector.resetThreadState(threadName);
            }

            // Collect raw metrics per enabled tier
            long t0, vitalsMs = 0, networkMs = 0, runtimeMs = 0, consoleMs = 0;

            WebVitalsResult vitals = null;
            if (propertiesManager.isWebVitalsEnabled()) {
                t0 = System.currentTimeMillis();
                vitals = webVitalsCollector.collect(executor, buffer);
                vitalsMs = System.currentTimeMillis() - t0;
            }

            NetworkResult network = null;
            if (propertiesManager.isNetworkEnabled()) {
                t0 = System.currentTimeMillis();
                network = networkCollector.collect(executor, buffer);
                networkMs = System.currentTimeMillis() - t0;
                debugLogger.logNetworkBufferDrained(network != null ? network.totalRequests() : 0); // CHANGED: P5 — call site was missing; method existed in BpmDebugLogger but was never invoked
            }

            RuntimeResult runtime = null;
            if (propertiesManager.isRuntimeEnabled()) {
                t0 = System.currentTimeMillis();
                runtime = runtimeCollector.collect(executor, buffer);
                runtimeMs = System.currentTimeMillis() - t0;
            }

            ConsoleResult console = null;
            if (propertiesManager.isConsoleEnabled()) {
                t0 = System.currentTimeMillis();
                console = consoleCollector.collect(executor, buffer);
                consoleMs = System.currentTimeMillis() - t0;
            }

            debugLogger.logCollection(sampleResult.getSampleLabel(),
                    vitalsMs, networkMs, runtimeMs, consoleMs);

            // Compute derived metrics
            DerivedMetrics derived = derivedCalculator.compute(
                    vitals, network, runtime, console, sampleResult.getTime());

            debugLogger.logDerivedMetrics(sampleResult.getSampleLabel(),
                    derived.performanceScore(), derived.improvementArea());

            // Build BpmResult
            int iteration = iterationsByThread
                    .computeIfAbsent(threadName, k -> new AtomicInteger(0))
                    .incrementAndGet();

            BpmResult bpmResult = new BpmResult(
                    BpmConstants.SCHEMA_VERSION,
                    Instant.now().toString(),
                    threadName,
                    iteration,
                    sampleResult.getSampleLabel(),
                    sampleResult.isSuccessful(),
                    sampleResult.getTime(),
                    vitals,
                    network,
                    runtime,
                    console,
                    derived
            );

            // Write to JSONL — write() logs WARN internally when writer is not open (Defect #2)
            if (jsonlWriter != null) { // CHANGED: Defect #2 — removed isOpen() guard so write() WARN fires if writer is closed
                long writeStart = System.currentTimeMillis();
                jsonlWriter.write(bpmResult);
                debugLogger.logJsonlWrite(1, 0, System.currentTimeMillis() - writeStart);
            }

            // Queue for GUI update
            if (guiUpdateQueue != null) {
                guiUpdateQueue.offer(bpmResult);
            }

            // Update label aggregates
            updateLabelAggregate(sampleResult.getSampleLabel(), derived, vitals, network, console);

            // Update health counters
            samplesCollected.incrementAndGet();
            totalCollectionTimeMs.addAndGet(System.currentTimeMillis() - collectionStart);

        } catch (Exception e) {
            errorHandler.handleCollectionError(threadName, e);
            debugLogger.log("Collection error for thread '{}': {}", threadName, e.getMessage());
        }
    }

    /**
     * Attempts to re-initialize the CDP session for a thread.
     */
    private void attemptReInit(String threadName, JMeterVariables vars) {
        debugLogger.logCdpReInit(threadName, 1);
        try {
            // Close old executor
            CdpCommandExecutor oldExecutor = executorsByThread.remove(threadName);
            if (oldExecutor != null) {
                sessionManager.closeSession(oldExecutor);
            }

            // Get browser from vars and re-init
            Object browserObj = vars.getObject(BpmConstants.VAR_BROWSER);
            if (browserObj == null || !isChromeDriver(browserObj, threadName)) {
                errorHandler.handleSessionError(threadName,
                        new RuntimeException("Browser not available for re-init"));
                return;
            }

            CdpCommandExecutor newExecutor = ChromeCdpCommandExecutor.fromBrowserObject(browserObj);
            MetricsBuffer newBuffer = new MetricsBuffer();

            sessionManager.reInjectObservers(newExecutor); // CHANGED: Gap 1 — use reInjectObservers (not openSession) to reset CLS accumulator and prevent double-counting

            executorsByThread.put(threadName, newExecutor);
            buffersByThread.put(threadName, newBuffer);
            vars.putObject(BpmConstants.VAR_BPM_DEV_TOOLS, newExecutor);
            vars.putObject(BpmConstants.VAR_BPM_EVENT_BUFFER, newBuffer);

            errorHandler.markReInitSuccess(threadName);

        } catch (Exception e) {
            errorHandler.handleSessionError(threadName, e);
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
     * Writes the bpm-summary.json file from label aggregates.
     */
    // writeSummaryJson() removed — Feature #1: summary JSON generation disabled

    /**
     * Prints the log summary table per design doc section 4.4.
     */
    private void printLogSummary() {
        if (labelAggregates == null || labelAggregates.isEmpty()) {
            log.info("BPM: No samples collected.");
            return;
        }

        log.info("=============== BPM Summary ===============");
        log.info(String.format("%-15s | %7s | %5s | %8s | %7s | %7s | %s",
                "Label", "Samples", "Score", "Rndr(ms)", "Srvr(%)", "Gap(ms)", "Bottleneck"));

        int totalSamples = 0;
        long totalWeightedScore = 0;
        int totalScoredSamples = 0; // CHANGED: per-action accuracy — only count samples with non-null score
        long totalWeightedRender = 0;
        double totalWeightedRatio = 0;
        long totalWeightedGap = 0;

        for (Map.Entry<String, LabelAggregate> entry : labelAggregates.entrySet()) {
            LabelAggregate agg = entry.getValue();
            int samples = agg.getSampleCount();
            Integer score = agg.getAverageScore(); // CHANGED: per-action accuracy — nullable
            String scoreStr = score != null ? String.valueOf(score) : "—"; // CHANGED: per-action accuracy
            long renderTime = agg.getAverageRenderTime();
            double serverRatio = agg.getAverageServerRatio();
            long fcpLcpGap = agg.getAverageFcpLcpGap();
            String improvementArea = agg.getPrimaryImprovementArea(); // CHANGED: renamed

            log.info(String.format("%-15s | %7d | %5s | %8d | %6.2f%% | %7d | %s",
                    truncateLabel(entry.getKey()), samples, scoreStr, renderTime,
                    serverRatio, fcpLcpGap, improvementArea)); // CHANGED: renamed

            totalSamples += samples;
            if (score != null) { // CHANGED: per-action accuracy — only aggregate non-null scores
                totalWeightedScore += (long) score * samples;
                totalScoredSamples += samples;
            }
            totalWeightedRender += renderTime * samples;
            totalWeightedRatio += serverRatio * samples;
            totalWeightedGap += fcpLcpGap * samples;
        }

        if (totalSamples > 0) {
            String totalScoreStr = totalScoredSamples > 0 // CHANGED: per-action accuracy
                    ? String.valueOf((int) (totalWeightedScore / totalScoredSamples))
                    : "—";
            log.info(String.format("%-15s | %7d | %5s | %8d | %6.2f%% | %7d |",
                    "TOTAL", totalSamples,
                    totalScoreStr, // CHANGED: per-action accuracy
                    totalWeightedRender / totalSamples,
                    totalWeightedRatio / totalSamples,
                    totalWeightedGap / totalSamples));
        }

        log.info("============================================");

        if (jsonlWriter != null && jsonlWriter.getOutputPath() != null) {
            log.info("BPM results written to: {}", jsonlWriter.getOutputPath());
        }

        long avgCollectionTime = samplesCollected.get() > 0
                ? totalCollectionTimeMs.get() / samplesCollected.get()
                : 0;
        log.info("BPM: Health — {} samples collected, {} failures, avg collection time {}ms, CDP re-inits: {}",
                samplesCollected.get(),
                errorHandler.getFailureCount(),
                avgCollectionTime,
                errorHandler.getReInitCount());
    }

    /**
     * Closes all CDP sessions across all threads.
     */
    private void closeAllCdpSessions() {
        if (executorsByThread == null) {
            return;
        }
        for (Map.Entry<String, CdpCommandExecutor> entry : executorsByThread.entrySet()) {
            sessionManager.closeSession(entry.getValue());
        }
        executorsByThread.clear();
        buffersByThread.clear();
    }

    /**
     * Truncates a label to 15 characters for log summary formatting.
     */
    private static String truncateLabel(String label) {
        return label.length() > 15 ? label.substring(0, 12) + "..." : label;
    }

    // ── Inner class: LabelAggregate ────────────────────────────────────────────────────────

    /**
     * Thread-safe running aggregate for a single sampler label.
     *
     * <p>Uses synchronized methods since updates come from multiple JMeter threads.
     * Stores running totals for weighted-average computation at summary time.</p>
     */
    public static class LabelAggregate {

        private int sampleCount;
        private long totalScore;
        private int scoredSampleCount; // CHANGED: per-action accuracy — counts only samples with non-null score
        private long totalRenderTime;
        private double totalServerRatio;
        private long totalFcpLcpGap;
        private long totalLcp;
        private long totalFcp;
        private long totalTtfb;
        private double totalCls;
        private int totalRequests;
        private long totalBytes;
        private int totalErrors;
        private int totalWarnings;
        private String lastImprovementArea = BpmConstants.BOTTLENECK_NONE; // CHANGED: renamed

        // CHANGED: per-metric non-null counters
        private int lcpCount;
        private int fcpCount;
        private int ttfbCount;
        private int clsCount;

        // CHANGED: new derived metric tracking
        private long totalFrontendTime;
        private int frontendTimeCount;
        private long totalHeadroom;
        private int headroomCount;

        /**
         * Updates the aggregate with a new sample's derived and raw metrics.
         */
        public synchronized void update(DerivedMetrics derived, WebVitalsResult vitals,
                                        NetworkResult network, ConsoleResult console) {
            sampleCount++;
            totalRenderTime += derived.renderTime();
            totalServerRatio += derived.serverClientRatio();
            totalFcpLcpGap += derived.fcpLcpGap();

            // Accumulate score only when non-null — SPA-stale samples return null // CHANGED: per-action accuracy
            if (derived.performanceScore() != null) {
                totalScore += derived.performanceScore();
                scoredSampleCount++;
            }

            // Accumulate new derived fields when non-null // CHANGED: new columns
            if (derived.frontendTime() != null) {
                totalFrontendTime += derived.frontendTime();
                frontendTimeCount++;
            }
            if (derived.headroom() != null) {
                totalHeadroom += derived.headroom();
                headroomCount++;
            }

            if (vitals != null) {
                if (vitals.lcp() != null)  { totalLcp  += vitals.lcp();  lcpCount++;  }
                if (vitals.fcp() != null)  { totalFcp  += vitals.fcp();  fcpCount++;  }
                if (vitals.ttfb() != null) { totalTtfb += vitals.ttfb(); ttfbCount++; }
                if (vitals.cls() != null)  { totalCls  += vitals.cls();  clsCount++;  }
            }
            if (network != null) {
                totalRequests += network.totalRequests();
                totalBytes += network.totalBytes();
            }
            if (console != null) {
                totalErrors += console.errors();
                totalWarnings += console.warnings();
            }

            // Track the most recent non-None improvement area as representative // CHANGED: renamed
            if (!BpmConstants.BOTTLENECK_NONE.equals(derived.improvementArea())) {
                lastImprovementArea = derived.improvementArea();
            }
        }

        /** @return number of samples collected for this label */
        public synchronized int getSampleCount() { return sampleCount; }

        /**
         * @return weighted average performance score, or {@code null} if no sample
         *         for this label had enough metric data to produce a score
         */
        public synchronized Integer getAverageScore() {
            return scoredSampleCount > 0 ? (int) (totalScore / scoredSampleCount) : null;
        }

        /** @return average render time in ms */
        public synchronized long getAverageRenderTime() {
            return sampleCount > 0 ? totalRenderTime / sampleCount : 0;
        }

        /** @return average server ratio as percentage */
        public synchronized double getAverageServerRatio() {
            return sampleCount > 0 ? totalServerRatio / sampleCount : 0.0;
        }

        /** @return average FCP-LCP gap in ms */
        public synchronized long getAverageFcpLcpGap() {
            return sampleCount > 0 ? totalFcpLcpGap / sampleCount : 0;
        }

        /** @return average frontend time in ms, over samples with FCP+TTFB data only */ // CHANGED: new
        public synchronized Long getAverageFrontendTime() {
            return frontendTimeCount > 0 ? totalFrontendTime / frontendTimeCount : null;
        }

        /** @return average headroom %, over samples with LCP data only */ // CHANGED: new
        public synchronized Integer getAverageHeadroom() {
            return headroomCount > 0 ? (int) (totalHeadroom / headroomCount) : null;
        }

        /** @return average LCP in ms, over non-null samples only */
        public synchronized long getAverageLcp() {
            return lcpCount > 0 ? totalLcp / lcpCount : 0;
        }

        /** @return average FCP in ms, over non-null samples only */
        public synchronized long getAverageFcp() {
            return fcpCount > 0 ? totalFcp / fcpCount : 0;
        }

        /** @return average TTFB in ms, over non-null samples only */
        public synchronized long getAverageTtfb() {
            return ttfbCount > 0 ? totalTtfb / ttfbCount : 0;
        }

        /** @return average CLS, over non-null samples only */
        public synchronized double getAverageCls() {
            return clsCount > 0 ? totalCls / clsCount : 0.0;
        }

        /** @return average requests per sample */
        public synchronized int getAverageRequests() {
            return sampleCount > 0 ? totalRequests / sampleCount : 0;
        }

        /** @return average total bytes per sample */
        public synchronized long getAverageBytes() {
            return sampleCount > 0 ? totalBytes / sampleCount : 0;
        }

        /** @return cumulative error count */
        public synchronized int getTotalErrors() { return totalErrors; }

        /** @return cumulative warning count */
        public synchronized int getTotalWarnings() { return totalWarnings; }

        /** @return the most recently seen non-None improvement area label */ // CHANGED: renamed
        public synchronized String getPrimaryImprovementArea() { return lastImprovementArea; }
    }
}