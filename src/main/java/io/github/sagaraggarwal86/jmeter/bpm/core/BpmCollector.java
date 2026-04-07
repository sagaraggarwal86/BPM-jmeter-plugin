package io.github.sagaraggarwal86.jmeter.bpm.core;

import io.github.sagaraggarwal86.jmeter.bpm.collectors.*;
import io.github.sagaraggarwal86.jmeter.bpm.config.BpmPropertiesManager;
import io.github.sagaraggarwal86.jmeter.bpm.error.BpmErrorHandler;
import io.github.sagaraggarwal86.jmeter.bpm.error.LogOnceTracker;
import io.github.sagaraggarwal86.jmeter.bpm.model.*;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmDebugLogger;
import io.github.sagaraggarwal86.jmeter.bpm.util.ConsoleSanitizer;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterVariables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal CDP metric collector — shared singleton during a test run.
 *
 * <p>Owns the CDP session lifecycle, all four metric collectors, and the derived-metrics
 * calculator. Collects metrics <b>exactly once per sample per thread</b> and caches the
 * result in {@link JMeterVariables} so that every {@link BpmListener} instance can read
 * it independently — the "collect-once, read-many" pattern.</p>
 *
 * <p>Not a JMeter {@code TestElement}. Created by the first {@code BpmListener} primary
 * in {@code testStarted()} and destroyed by the last primary in {@code testEnded()}.</p>
 */
final class BpmCollector {

    private static final Logger log = LoggerFactory.getLogger(BpmCollector.class);
    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static volatile BpmCollector instance;
    private final BpmPropertiesManager propertiesManager;
    private final BpmDebugLogger debugLogger;
    private final BpmErrorHandler errorHandler;
    private final LogOnceTracker logOnceTracker;
    private final CdpSessionManager sessionManager;
    private final ConsoleSanitizer consoleSanitizer;

    private final WebVitalsCollector webVitalsCollector;
    private final NetworkCollector networkCollector;
    private final RuntimeCollector runtimeCollector;
    private final ConsoleCollector consoleCollector;
    private final DerivedMetricsCalculator derivedCalculator;

    private final ConcurrentHashMap<String, CdpCommandExecutor> executorsByThread = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MetricsBuffer> buffersByThread = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> iterationsByThread = new ConcurrentHashMap<>();

    private final AtomicInteger samplesCollected = new AtomicInteger(0);
    private final AtomicLong totalCollectionTimeMs = new AtomicLong(0);

    // Volatile pair: seleniumAvailable is set before seleniumChecked inside the synchronized
    // checkSeleniumAvailability(), so unsynchronized readers that see seleniumChecked=true
    // are guaranteed to see the final seleniumAvailable value via volatile happens-before.
    private volatile boolean seleniumAvailable;
    private volatile boolean seleniumChecked;
    private volatile String infoBarOverride;

    private BpmCollector(BpmPropertiesManager propertiesManager) {
        this.propertiesManager = propertiesManager;
        this.debugLogger = new BpmDebugLogger(propertiesManager.isDebugEnabled());
        this.logOnceTracker = new LogOnceTracker();
        this.errorHandler = new BpmErrorHandler(logOnceTracker);
        this.sessionManager = new CdpSessionManager();
        this.consoleSanitizer = new ConsoleSanitizer(propertiesManager.isSanitizeEnabled());

        this.webVitalsCollector = new WebVitalsCollector();
        this.webVitalsCollector.reset();
        this.networkCollector = new NetworkCollector(propertiesManager.getNetworkTopN());
        this.runtimeCollector = new RuntimeCollector();
        this.runtimeCollector.reset();
        this.consoleCollector = new ConsoleCollector(consoleSanitizer);
        this.derivedCalculator = new DerivedMetricsCalculator(propertiesManager);
    }

    // ── Singleton lifecycle ───────────────────────────────────────────────────────────────

    /**
     * Acquires a reference to the shared collector, creating it if this is the first call.
     *
     * @param propertiesManager configuration (used only on first call to create the instance)
     * @return the shared collector instance
     */
    static synchronized BpmCollector acquire(BpmPropertiesManager propertiesManager) {
        if (instance == null) {
            instance = new BpmCollector(propertiesManager);
            log.debug("BPM: BpmCollector created.");
        }
        refCount.incrementAndGet();
        return instance;
    }

    /**
     * Releases a reference. When the last reference is released, shuts down the collector.
     */
    static synchronized void release() {
        if (refCount.decrementAndGet() <= 0) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
                log.debug("BPM: BpmCollector destroyed.");
            }
            refCount.set(0);
        }
    }

    static BpmCollector getInstance() {
        return instance;
    }

    // ── Core: collect-once-read-many ──────────────────────────────────────────────────────

    /**
     * Collects metrics for the current sample, or returns the cached result if another
     * listener already triggered collection for this exact {@code SampleResult}.
     *
     * @param vars         the current thread's JMeter variables
     * @param sampleResult the sampler result for this execution
     * @return the collected {@code BpmResult}, or null if collection was skipped
     */
    BpmResult collectIfNeeded(JMeterVariables vars, SampleResult sampleResult) {
        Object lastSample = vars.getObject(BpmConstants.VAR_BPM_LAST_SAMPLE);
        if (lastSample == sampleResult) {
            return (BpmResult) vars.getObject(BpmConstants.VAR_BPM_RESULT);
        }

        String threadName = Thread.currentThread().getName();

        if (errorHandler.isThreadDisabled(threadName)) {
            // A new browser may have been created by WebDriverConfig after the crash
            // that caused DISABLED. If a browser is available, reset to HEALTHY
            // so BPM can recover with the fresh session.
            Object browserObj = vars.getObject(BpmConstants.VAR_BROWSER);
            if (browserObj != null && isChromeDriver(browserObj, threadName)) {
                errorHandler.resetThread(threadName);
                // Clear stale CDP references from the dead session so the code path
                // falls through to fresh initCdpSession() with the new browser.
                vars.remove(BpmConstants.VAR_BPM_DEV_TOOLS);
                vars.remove(BpmConstants.VAR_BPM_EVENT_BUFFER);
                executorsByThread.remove(threadName);
                buffersByThread.remove(threadName);
            } else {
                return null;
            }
        }

        try {
            Object devToolsObj = vars.getObject(BpmConstants.VAR_BPM_DEV_TOOLS);
            if (devToolsObj != null) {
                BpmResult result = doCollect(threadName, sampleResult, vars);
                cacheResult(vars, sampleResult, result);
                return result;
            }

            Object browserObj = vars.getObject(BpmConstants.VAR_BROWSER);
            if (browserObj == null) {
                return null;
            }

            if (!seleniumChecked) {
                checkSeleniumAvailability(threadName);
            }
            if (!seleniumAvailable) {
                return null;
            }

            if (!isChromeDriver(browserObj, threadName)) {
                return null;
            }

            initCdpSession(browserObj, threadName, vars);

            if (!errorHandler.isThreadDisabled(threadName)
                    && executorsByThread.containsKey(threadName)) {
                BpmResult result = doCollect(threadName, sampleResult, vars);
                cacheResult(vars, sampleResult, result);
                return result;
            }

        } catch (Exception e) {
            log.warn("BPM: Unexpected error in collectIfNeeded", e);
            debugLogger.log("collectIfNeeded exception: {}", e.toString());
        }

        return null;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────────────────

    String getInfoBarOverride() {
        return infoBarOverride;
    }

    BpmPropertiesManager getPropertiesManager() {
        return propertiesManager;
    }

    BpmErrorHandler getErrorHandler() {
        return errorHandler;
    }

    int getSamplesCollected() {
        return samplesCollected.get();
    }

    long getTotalCollectionTimeMs() {
        return totalCollectionTimeMs.get();
    }

    // ── Internal: CDP session init ────────────────────────────────────────────────────────

    private synchronized void checkSeleniumAvailability(String threadName) {
        if (seleniumChecked) {
            return;
        }
        try {
            Class.forName("org.openqa.selenium.chrome.ChromeDriver");
            seleniumAvailable = true;
            debugLogger.log("Selenium/ChromeDriver found on classpath.");
        } catch (ClassNotFoundException e) {
            seleniumAvailable = false;
            infoBarOverride = BpmConstants.INFO_NO_SELENIUM;
            log.warn("BPM: {}", BpmConstants.INFO_NO_SELENIUM);
        }
        // Set after seleniumAvailable so volatile readers see the final value
        seleniumChecked = true;
    }

    private boolean isChromeDriver(Object browserObj, String threadName) {
        String className = browserObj.getClass().getName();
        if (className.contains("ChromeDriver") || className.contains("ChromiumDriver")) {
            return true;
        }
        logOnceTracker.warnOnce(threadName, "non-chrome",
                "Non-Chrome browser detected (" + className + "). CDP metrics require Chrome/Chromium.");
        infoBarOverride = BpmConstants.INFO_NON_CHROME;
        return false;
    }

    private void initCdpSession(Object browserObj, String threadName, JMeterVariables vars) {
        long startMs = System.currentTimeMillis();
        try {
            CdpCommandExecutor executor = ChromeCdpCommandExecutor.fromBrowserObject(browserObj);
            MetricsBuffer buffer = new MetricsBuffer();
            sessionManager.openSession(executor);
            registerSession(threadName, executor, buffer, vars);

            long durationMs = System.currentTimeMillis() - startMs;
            debugLogger.logCdpSessionOpened(threadName, durationMs);

        } catch (Exception e) {
            logOnceTracker.warnOnce(threadName, "cdp-init-failed",
                    "Failed to initialize CDP session: " + e.getMessage());
            errorHandler.handleSessionError(threadName, e);
        }
    }

    private void registerSession(String threadName, CdpCommandExecutor executor,
                                 MetricsBuffer buffer, JMeterVariables vars) {
        executorsByThread.put(threadName, executor);
        buffersByThread.put(threadName, buffer);
        vars.putObject(BpmConstants.VAR_BPM_DEV_TOOLS, executor);
        vars.putObject(BpmConstants.VAR_BPM_EVENT_BUFFER, buffer);
    }

    // ── Internal: Metric collection ───────────────────────────────────────────────────────

    private BpmResult doCollect(String threadName, SampleResult sampleResult, JMeterVariables vars) {
        CdpCommandExecutor executor = executorsByThread.get(threadName);
        MetricsBuffer buffer = buffersByThread.get(threadName);

        if (executor == null || buffer == null) {
            return null;
        }

        if (errorHandler.needsReInit(threadName)) {
            attemptReInit(threadName, vars);
            executor = executorsByThread.get(threadName);
            buffer = buffersByThread.get(threadName);
            if (executor == null || errorHandler.isThreadDisabled(threadName)) {
                return null;
            }
        }

        try {
            return collectFromSession(threadName, sampleResult, executor, buffer);
        } catch (Exception e) {
            errorHandler.handleCollectionError(threadName, e);
            debugLogger.log("Collection error for thread '{}': {}", threadName, e.getMessage());

            if (errorHandler.needsReInit(threadName)) {
                attemptReInit(threadName, vars);
                if (!errorHandler.isThreadDisabled(threadName)
                        && executorsByThread.containsKey(threadName)) {
                    try {
                        CdpCommandExecutor retryExecutor = executorsByThread.get(threadName);
                        MetricsBuffer retryBuffer = buffersByThread.get(threadName);
                        if (retryExecutor != null && retryBuffer != null) {
                            return collectFromSession(threadName, sampleResult, retryExecutor, retryBuffer);
                        }
                    } catch (Exception retryEx) {
                        debugLogger.log("Retry collection also failed for '{}': {}",
                                threadName, retryEx.getMessage());
                    }
                }
            }
        }

        return null;
    }

    /**
     * Shared collection sequence used by both the primary path and retry path.
     * Transfers buffered events, collects all enabled metric tiers, computes derived
     * metrics, and builds the {@link BpmResult}.
     */
    private BpmResult collectFromSession(String threadName, SampleResult sampleResult,
                                         CdpCommandExecutor executor, MetricsBuffer buffer) {
        long collectionStart = System.currentTimeMillis();

        sessionManager.transferBufferedEvents(executor, buffer);

        boolean navigated = sessionManager.ensureObserversInjected(executor);
        if (navigated) {
            webVitalsCollector.resetThreadState(threadName);
            runtimeCollector.resetThreadState(threadName);
        }

        boolean logTimings = debugLogger.isEnabled();
        long t0, vitalsMs = 0, networkMs = 0, runtimeMs = 0, consoleMs = 0;

        WebVitalsResult vitals = null;
        if (propertiesManager.isWebVitalsEnabled()) {
            t0 = logTimings ? System.currentTimeMillis() : 0;
            vitals = webVitalsCollector.collect(executor, buffer);
            vitalsMs = logTimings ? System.currentTimeMillis() - t0 : 0;
        }

        NetworkResult network = null;
        if (propertiesManager.isNetworkEnabled()) {
            t0 = logTimings ? System.currentTimeMillis() : 0;
            network = networkCollector.collect(executor, buffer);
            networkMs = logTimings ? System.currentTimeMillis() - t0 : 0;
            debugLogger.logNetworkBufferDrained(network != null ? network.totalRequests() : 0);
        }

        RuntimeResult runtime = null;
        if (propertiesManager.isRuntimeEnabled()) {
            t0 = logTimings ? System.currentTimeMillis() : 0;
            runtime = runtimeCollector.collect(executor, buffer);
            runtimeMs = logTimings ? System.currentTimeMillis() - t0 : 0;
        }

        ConsoleResult console = null;
        if (propertiesManager.isConsoleEnabled()) {
            t0 = logTimings ? System.currentTimeMillis() : 0;
            console = consoleCollector.collect(executor, buffer);
            consoleMs = logTimings ? System.currentTimeMillis() - t0 : 0;
        }

        debugLogger.logCollection(sampleResult.getSampleLabel(),
                vitalsMs, networkMs, runtimeMs, consoleMs);

        DerivedMetrics derived = derivedCalculator.compute(
                vitals, network, runtime, console, sampleResult.getTime());

        debugLogger.logDerivedMetrics(sampleResult.getSampleLabel(),
                derived.performanceScore(), derived.improvementArea());

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

        samplesCollected.incrementAndGet();
        totalCollectionTimeMs.addAndGet(System.currentTimeMillis() - collectionStart);

        return bpmResult;
    }

    private void attemptReInit(String threadName, JMeterVariables vars) {
        debugLogger.logCdpReInit(threadName, 1);
        try {
            CdpCommandExecutor oldExecutor = executorsByThread.remove(threadName);
            if (oldExecutor != null) {
                sessionManager.closeSession(oldExecutor);
            }

            Object browserObj = vars.getObject(BpmConstants.VAR_BROWSER);
            if (browserObj == null || !isChromeDriver(browserObj, threadName)) {
                errorHandler.handleSessionError(threadName,
                        new RuntimeException("Browser not available for re-init"));
                return;
            }

            CdpCommandExecutor newExecutor = ChromeCdpCommandExecutor.fromBrowserObject(browserObj);
            MetricsBuffer newBuffer = new MetricsBuffer();
            sessionManager.reInjectObservers(newExecutor);
            registerSession(threadName, newExecutor, newBuffer, vars);
            errorHandler.markReInitSuccess(threadName);

        } catch (Exception e) {
            errorHandler.handleSessionError(threadName, e);
        }
    }

    private void cacheResult(JMeterVariables vars, SampleResult sampleResult, BpmResult bpmResult) {
        if (bpmResult != null) {
            vars.putObject(BpmConstants.VAR_BPM_RESULT, bpmResult);
        }
        vars.putObject(BpmConstants.VAR_BPM_LAST_SAMPLE, sampleResult);
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────────────────

    private void shutdown() {
        for (var entry : executorsByThread.entrySet()) {
            sessionManager.closeSession(entry.getValue());
        }
        executorsByThread.clear();
        buffersByThread.clear();
        iterationsByThread.clear();
        debugLogger.log("BpmCollector shutdown. Total samples collected: {}", samplesCollected.get());
    }
}
