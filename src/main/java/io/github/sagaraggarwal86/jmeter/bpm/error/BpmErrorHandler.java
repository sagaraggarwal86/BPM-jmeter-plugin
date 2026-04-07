package io.github.sagaraggarwal86.jmeter.bpm.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized error handling strategy for BPM metric collection.
 *
 * <p>Manages per-thread state transitions following the design principle:
 * "Never crash the test. Degrade gracefully."</p>
 *
 * <p>State machine per thread:</p>
 * <pre>
 *   HEALTHY ──(collection error)──→ RE_INIT_NEEDED ──(re-init fails)──→ DISABLED
 *                                         │
 *                                    (re-init succeeds)
 *                                         │
 *                                         ↓
 *                                      HEALTHY
 * </pre>
 *
 * <p>Thread-safe: all state is stored in {@link ConcurrentHashMap} instances
 * and counters use {@link AtomicInteger}.</p>
 */
public final class BpmErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(BpmErrorHandler.class);
    private final ConcurrentHashMap<String, ThreadState> threadStates = new ConcurrentHashMap<>();
    private final AtomicInteger reInitCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final LogOnceTracker logOnceTracker;

    /**
     * Creates a new error handler with the given log-once tracker.
     *
     * @param logOnceTracker tracker for suppressing duplicate log warnings
     */
    public BpmErrorHandler(LogOnceTracker logOnceTracker) {
        this.logOnceTracker = logOnceTracker;
    }

    /**
     * Handles a metric collection error. Transitions the thread from
     * {@code HEALTHY} to {@code RE_INIT_NEEDED}.
     *
     * <p>If the thread is already in {@code RE_INIT_NEEDED} or {@code DISABLED},
     * this call has no additional effect beyond incrementing the failure counter.</p>
     *
     * @param threadName the JMeter thread name
     * @param exception  the exception that occurred during collection
     */
    public void handleCollectionError(String threadName, Exception exception) {
        failureCount.incrementAndGet();
        ThreadState currentState = threadStates.getOrDefault(threadName, ThreadState.HEALTHY);

        switch (currentState) {
            case HEALTHY -> {
                threadStates.put(threadName, ThreadState.RE_INIT_NEEDED);
                log.debug("BPM: [{}] collection error", threadName, exception);
                logOnceTracker.warnOnce(threadName, "collection-error",
                        "CDP collection error. Will attempt re-init. Cause: " + exception.getMessage());
            }
            case RE_INIT_NEEDED -> {
                // Already pending re-init; another failure before re-init was attempted
                logOnceTracker.warnOnce(threadName, "collection-error-repeat",
                        "Repeated collection error before re-init. Cause: " + exception.getMessage());
            }
            case DISABLED -> {
                // Thread already disabled; silently count
            }
        }
    }

    /**
     * Handles a CDP session re-initialization error. Transitions the thread
     * from {@code RE_INIT_NEEDED} to {@code DISABLED}.
     *
     * <p>Called when the single re-init attempt fails. The thread is permanently
     * disabled for BPM collection for the remainder of the test.</p>
     *
     * @param threadName the JMeter thread name
     * @param exception  the exception that occurred during re-initialization
     */
    public void handleSessionError(String threadName, Exception exception) {
        failureCount.incrementAndGet();
        threadStates.put(threadName, ThreadState.DISABLED);
        log.debug("BPM: [{}] session re-init failed", threadName, exception);
        logOnceTracker.warnOnce(threadName, "session-disabled",
                "CDP re-init failed. BPM disabled for this thread. Cause: " + exception.getMessage());
    }

    /**
     * Marks a successful CDP session re-initialization. Transitions the thread
     * back from {@code RE_INIT_NEEDED} to {@code HEALTHY}.
     *
     * @param threadName the JMeter thread name
     */
    public void markReInitSuccess(String threadName) {
        reInitCount.incrementAndGet();
        threadStates.put(threadName, ThreadState.HEALTHY);
        // First re-init per thread at INFO (confirms recovery); subsequent at DEBUG
        // to avoid flooding the log during normal browser recycling.
        if (logOnceTracker.markOnce(threadName, "reinit-success")) {
            log.info("BPM: [{}] CDP session re-initialized successfully.", threadName);
        } else {
            log.debug("BPM: [{}] CDP session re-initialized successfully.", threadName);
        }
    }

    /**
     * Checks whether BPM collection is disabled for the given thread.
     *
     * @param threadName the JMeter thread name
     * @return true if the thread is in {@code DISABLED} state
     */
    public boolean isThreadDisabled(String threadName) {
        return threadStates.getOrDefault(threadName, ThreadState.HEALTHY) == ThreadState.DISABLED;
    }

    /**
     * Resets a disabled thread back to {@code HEALTHY} so that CDP session
     * initialization can be attempted with a new browser instance.
     *
     * <p>Called when a thread is {@code DISABLED} but a fresh browser has been
     * detected (e.g., WebDriverConfig created a new ChromeDriver after a crash).
     * Also clears the log-once tracker entries for this thread so that relevant
     * diagnostic messages are logged again for the new session.</p>
     *
     * @param threadName the JMeter thread name
     */
    public void resetThread(String threadName) {
        threadStates.put(threadName, ThreadState.HEALTHY);
        logOnceTracker.resetThread(threadName);
        log.info("BPM: [{}] Thread reset from DISABLED to HEALTHY — new browser detected.", threadName);
    }

    /**
     * Checks whether the given thread needs a CDP session re-initialization.
     *
     * @param threadName the JMeter thread name
     * @return true if the thread is in {@code RE_INIT_NEEDED} state
     */
    public boolean needsReInit(String threadName) {
        return threadStates.getOrDefault(threadName, ThreadState.HEALTHY) == ThreadState.RE_INIT_NEEDED;
    }

    /**
     * Returns the current state for the given thread.
     *
     * @param threadName the JMeter thread name
     * @return the thread's current state, defaulting to {@code HEALTHY}
     */
    public ThreadState getThreadState(String threadName) {
        return threadStates.getOrDefault(threadName, ThreadState.HEALTHY);
    }

    /**
     * Returns the total number of successful CDP re-initializations across all threads.
     *
     * @return re-init count
     */
    public int getReInitCount() {
        return reInitCount.get();
    }

    /**
     * Returns the total number of collection and session failures across all threads.
     *
     * @return failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Resets all state. Intended for use during {@code testStarted()} to
     * start fresh for a new test run.
     */
    public void reset() {
        threadStates.clear();
        reInitCount.set(0);
        failureCount.set(0);
        logOnceTracker.reset();
    }

    /**
     * Per-thread CDP session health state.
     */
    public enum ThreadState {
        /**
         * Normal operation. Metrics collection proceeds.
         */
        HEALTHY,
        /**
         * CDP session error occurred. One re-initialization attempt allowed.
         */
        RE_INIT_NEEDED,
        /**
         * Re-initialization failed. BPM is disabled for this thread.
         */
        DISABLED
    }
}