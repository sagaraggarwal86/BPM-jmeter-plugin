package io.github.sagaraggarwal86.jmeter.bpm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe buffer for CDP events collected between sampler executions.
 *
 * <p>CDP event listeners (Network.responseReceived, Log.entryAdded, etc.)
 * push data into this buffer asynchronously. Collectors drain the buffer
 * during metric collection in {@code sampleOccurred()}.</p>
 *
 * <p>Internally uses {@link ConcurrentLinkedQueue} for lock-free
 * concurrent access from CDP event threads and the JMeter sampler thread.</p>
 */
public final class MetricsBuffer {

    private final ConcurrentLinkedQueue<Map<String, Object>> networkResponses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ConsoleEntry> consoleMessages = new ConcurrentLinkedQueue<>();

    /**
     * Adds a network response event to the buffer.
     *
     * <p>Called from the CDP Network.responseReceived event listener.</p>
     *
     * @param responseData the response data map containing URL, status, timing, size, etc.
     */
    public void addNetworkResponse(Map<String, Object> responseData) {
        if (responseData != null) {
            networkResponses.add(responseData);
        }
    }

    /**
     * Drains all buffered network responses and returns them as a list.
     *
     * <p>The buffer is emptied after this call. Subsequent calls return
     * only responses added after the drain.</p>
     *
     * @return list of network response data maps; never null, may be empty
     */
    public List<Map<String, Object>> drainNetworkResponses() {
        List<Map<String, Object>> drained = new ArrayList<>();
        Map<String, Object> entry;
        while ((entry = networkResponses.poll()) != null) {
            drained.add(entry);
        }
        return drained;
    }

    /**
     * Adds a console message (error or warning) to the buffer.
     *
     * <p>Called from the CDP Log.entryAdded event listener.</p>
     *
     * @param level the log level (e.g. "error", "warning")
     * @param text  the console message text
     */
    public void addConsoleMessage(String level, String text) {
        if (level != null && text != null) {
            consoleMessages.add(new ConsoleEntry(level, text));
        }
    }

    /**
     * Drains all buffered console messages and returns them as a list.
     *
     * <p>The buffer is emptied after this call.</p>
     *
     * @return list of console entries; never null, may be empty
     */
    public List<ConsoleEntry> drainConsoleMessages() {
        List<ConsoleEntry> drained = new ArrayList<>();
        ConsoleEntry entry;
        while ((entry = consoleMessages.poll()) != null) {
            drained.add(entry);
        }
        return drained;
    }

    /**
     * Returns the current number of buffered network responses without draining.
     *
     * @return approximate count of buffered network responses
     */
    public int networkResponseCount() {
        return networkResponses.size();
    }

    /**
     * Returns the current number of buffered console messages without draining.
     *
     * @return approximate count of buffered console messages
     */
    public int consoleMessageCount() {
        return consoleMessages.size();
    }

    /**
     * Clears all buffered data. Used during CDP session re-initialization.
     */
    public void clear() {
        networkResponses.clear();
        consoleMessages.clear();
    }

    /**
     * Immutable record representing a single console log entry.
     *
     * @param level the log level (e.g. "error", "warning")
     * @param text  the message text
     */
    public record ConsoleEntry(String level, String text) {
    }
}
