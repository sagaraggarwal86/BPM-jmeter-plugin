package io.github.sagaraggarwal86.jmeter.bpm.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which warnings have been logged per thread to prevent log spam.
 *
 * <p>Each unique combination of {@code threadName + warningKey} is logged
 * at most once. Subsequent calls with the same combination are silently
 * ignored. Thread-safe via {@link ConcurrentHashMap}-backed {@link Set}.</p>
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 *   tracker.warnOnce("Thread-1", "non-chrome", "Non-Chrome browser detected");
 *   tracker.warnOnce("Thread-1", "non-chrome", "Non-Chrome browser detected"); // suppressed
 * }</pre>
 */
public final class LogOnceTracker {

    private static final Logger log = LoggerFactory.getLogger(LogOnceTracker.class);

    /**
     * Separator between thread name and warning key to form a composite key.
     */
    private static final String KEY_SEPARATOR = "::";

    private final Set<String> loggedWarnings = ConcurrentHashMap.newKeySet();

    /**
     * Logs a warning message at most once for the given thread and warning key combination.
     *
     * @param threadName the JMeter thread name (e.g. "Thread Group 1-1")
     * @param warningKey a short identifier for the warning category (e.g. "non-chrome", "cdp-reinit")
     * @param message    the warning message to log
     */
    public void warnOnce(String threadName, String warningKey, String message) {
        String compositeKey = threadName + KEY_SEPARATOR + warningKey;
        if (loggedWarnings.add(compositeKey)) {
            log.warn("BPM: [{}] {}", threadName, message);
        }
    }

    /**
     * Marks a thread+key combination as seen and returns whether this is the first time.
     * Does not log anything — the caller decides how to log based on the return value.
     *
     * @param threadName the JMeter thread name
     * @param key        a short identifier for the event category
     * @return {@code true} if this is the first occurrence; {@code false} if already seen
     */
    public boolean markOnce(String threadName, String key) {
        return loggedWarnings.add(threadName + KEY_SEPARATOR + key);
    }

    /**
     * Checks whether a warning has already been logged for the given thread and key.
     *
     * @param threadName the JMeter thread name
     * @param warningKey the warning category key
     * @return true if the warning was already logged
     */
    public boolean hasWarned(String threadName, String warningKey) {
        return loggedWarnings.contains(threadName + KEY_SEPARATOR + warningKey);
    }

    /**
     * Clears all tracked warnings for a specific thread, allowing those
     * warnings to be logged again. Used when a thread recovers from
     * {@code DISABLED} state after a new browser is detected.
     *
     * @param threadName the JMeter thread name
     */
    public void resetThread(String threadName) {
        String prefix = threadName + KEY_SEPARATOR;
        loggedWarnings.removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Resets all tracked warnings. Intended for use during {@code testStarted()}
     * to allow fresh warnings for a new test run.
     */
    public void reset() {
        loggedWarnings.clear();
    }

    /**
     * Returns the number of unique warnings that have been logged.
     *
     * @return count of logged warning keys
     */
    public int getLoggedCount() {
        return loggedWarnings.size();
    }
}
