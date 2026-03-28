package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.collectors.ConsoleCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.DerivedMetricsCalculator;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.NetworkCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.RuntimeCollector;
import io.github.sagaraggarwal86.jmeter.bpm.collectors.WebVitalsCollector;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.error.BpmErrorHandler;
import io.github.sagaraggarwal86.jmeter.bpm.error.LogOnceTracker;
import io.github.sagaraggarwal86.jmeter.bpm.gui.BpmListenerGui; // CHANGED: §5.5 — GUI lifecycle wiring
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.model.NetworkResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.RuntimeResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.output.JsonlWriter;
import io.github.sagaraggarwal86.jmeter.bpm.output.SummaryJsonWriter;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    // ── Per-test state (reset in testStarted) ──────────────────────────────────────────────

    private transient BpmPropertiesManager propertiesManager;
    private transient BpmDebugLogger debugLogger;
    private transient JsonlWriter jsonlWriter;
    private transient SummaryJsonWriter summaryJsonWriter;
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
        consoleCollector = new ConsoleCollector(consoleSanitizer);
        derivedCalculator = new DerivedMetricsCalculator(propertiesManager);

        // Initialize writers
        jsonlWriter = new JsonlWriter();
        summaryJsonWriter = new SummaryJsonWriter();

        String outputPath = resolveOutputPath();
        try {
            jsonlWriter.open(Path.of(outputPath));
            log.info("BPM: JSONL output file: {}", outputPath);
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
            debugLogger.log("sampleOccurred exception: {}", e.toString());
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
        try {
            // Flush and close JSONL
            if (jsonlWriter != null) {
                jsonlWriter.flush();
            }

            // Write summary JSON
            writeSummaryJson();

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
     * Resolves the JSONL output path using the defined priority chain: // CHANGED: P3 — GUI field is now in the chain
     * {@code -Jbpm.output (highest) → GUI TestElement property → bpm.properties/default (lowest)}.
     */
    private String resolveOutputPath() {
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
                    derived.performanceScore(), derived.bottleneck());

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

            // Write to JSONL
            if (jsonlWriter.isOpen()) {
                long writeStart = System.currentTimeMillis();
                jsonlWriter.write(bpmResult);
                debugLogger.logJsonlWrite(1, 0, System.currentTimeMillis() - writeStart); // CHANGED: P5 — call site was missing; byte count passed as 0 (JsonlWriter does not expose last write size; avoiding API change)
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
    private void writeSummaryJson() {
        if (labelAggregates == null || labelAggregates.isEmpty()) {
            return;
        }
        if (jsonlWriter == null || jsonlWriter.getOutputPath() == null) {
            return;
        }

        List<Map<String, Object>> labelStats = new ArrayList<>();
        for (Map.Entry<String, LabelAggregate> entry : labelAggregates.entrySet()) {
            LabelAggregate agg = entry.getValue();
            Map<String, Object> stat = new HashMap<>();
            stat.put("label", entry.getKey());
            stat.put("score", agg.getAverageScore());
            stat.put("lcp", agg.getAverageLcp());
            stat.put("bottleneck", agg.getPrimaryBottleneck());
            stat.put("samples", agg.getSampleCount());
            labelStats.add(stat);
        }

        summaryJsonWriter.write(
                jsonlWriter.getOutputPath(),
                labelStats,
                propertiesManager.getSlaLcpPoor(),
                propertiesManager.getSlaScorePoor()
        );
    }

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
        long totalWeightedRender = 0;
        double totalWeightedRatio = 0;
        long totalWeightedGap = 0;

        for (Map.Entry<String, LabelAggregate> entry : labelAggregates.entrySet()) {
            LabelAggregate agg = entry.getValue();
            int samples = agg.getSampleCount();
            int score = agg.getAverageScore();
            long renderTime = agg.getAverageRenderTime();
            double serverRatio = agg.getAverageServerRatio();
            long fcpLcpGap = agg.getAverageFcpLcpGap();
            String bottleneck = agg.getPrimaryBottleneck();

            log.info(String.format("%-15s | %7d | %5d | %8d | %6.2f%% | %7d | %s",
                    truncateLabel(entry.getKey()), samples, score, renderTime,
                    serverRatio, fcpLcpGap, bottleneck));

            totalSamples += samples;
            totalWeightedScore += (long) score * samples;
            totalWeightedRender += renderTime * samples;
            totalWeightedRatio += serverRatio * samples;
            totalWeightedGap += fcpLcpGap * samples;
        }

        if (totalSamples > 0) {
            log.info(String.format("%-15s | %7d | %5d | %8d | %6.2f%% | %7d |",
                    "TOTAL", totalSamples,
                    (int) (totalWeightedScore / totalSamples),
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
        private String lastBottleneck = BpmConstants.BOTTLENECK_NONE;

        /**
         * Updates the aggregate with a new sample's derived and raw metrics.
         */
        public synchronized void update(DerivedMetrics derived, WebVitalsResult vitals,
                                        NetworkResult network, ConsoleResult console) {
            sampleCount++;
            totalScore += derived.performanceScore();
            totalRenderTime += derived.renderTime();
            totalServerRatio += derived.serverClientRatio();
            totalFcpLcpGap += derived.fcpLcpGap();

            if (vitals != null) {
                totalLcp  += vitals.lcp()  != null ? vitals.lcp()  : 0L; // CHANGED: null-safe unboxing (§3.2)
                totalFcp  += vitals.fcp()  != null ? vitals.fcp()  : 0L;
                totalTtfb += vitals.ttfb() != null ? vitals.ttfb() : 0L;
                totalCls  += vitals.cls()  != null ? vitals.cls()  : 0.0;
            }
            if (network != null) {
                totalRequests += network.totalRequests();
                totalBytes += network.totalBytes();
            }
            if (console != null) {
                totalErrors += console.errors();
                totalWarnings += console.warnings();
            }

            // Track the most recent bottleneck as representative
            if (!BpmConstants.BOTTLENECK_NONE.equals(derived.bottleneck())) {
                lastBottleneck = derived.bottleneck();
            }
        }

        /** @return number of samples collected for this label */
        public synchronized int getSampleCount() { return sampleCount; }

        /** @return weighted average performance score */
        public synchronized int getAverageScore() {
            return sampleCount > 0 ? (int) (totalScore / sampleCount) : 0;
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

        /** @return average LCP in ms */
        public synchronized long getAverageLcp() {
            return sampleCount > 0 ? totalLcp / sampleCount : 0;
        }

        /** @return average FCP in ms */
        public synchronized long getAverageFcp() {
            return sampleCount > 0 ? totalFcp / sampleCount : 0;
        }

        /** @return average TTFB in ms */
        public synchronized long getAverageTtfb() {
            return sampleCount > 0 ? totalTtfb / sampleCount : 0;
        }

        /** @return average CLS */
        public synchronized double getAverageCls() {
            return sampleCount > 0 ? totalCls / sampleCount : 0.0;
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

        /** @return the most recently seen non-none bottleneck label */
        public synchronized String getPrimaryBottleneck() { return lastBottleneck; }
    }
}