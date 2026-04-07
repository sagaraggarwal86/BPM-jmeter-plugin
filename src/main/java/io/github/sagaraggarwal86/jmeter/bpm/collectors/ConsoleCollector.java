package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.ConsoleResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.ConsoleSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects JavaScript console error and warning metrics from the {@link MetricsBuffer}.
 *
 * <p>Drains all buffered console messages (populated by the console capture hook
 * via {@code CdpSessionManager.transferBufferedEvents()}) and:</p>
 * <ul>
 *   <li>Separates messages into errors ({@code level == "error"}) and
 *       warnings ({@code level == "warning"})</li>
 *   <li>Applies {@link ConsoleSanitizer} to redact sensitive data
 *       (tokens, keys, emails, etc.) when sanitization is enabled</li>
 *   <li>Returns counts and sanitized message list</li>
 * </ul>
 */
public final class ConsoleCollector implements MetricsCollector<ConsoleResult> {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCollector.class);

    private static final String LEVEL_ERROR = "error";
    private static final String LEVEL_WARNING = "warning";

    private final ConsoleSanitizer sanitizer;

    /**
     * Creates a console collector with the given sanitizer.
     *
     * @param sanitizer the console message sanitizer; applies redaction when enabled
     */
    public ConsoleCollector(ConsoleSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * Drains the console message buffer and computes console metrics.
     *
     * @param executor the CDP command executor (not used directly; console data
     *                 comes from the buffer populated during event transfer)
     * @param buffer   the metrics buffer to drain
     * @return the console metrics result; never null
     */
    @Override
    public ConsoleResult collect(CdpCommandExecutor executor, MetricsBuffer buffer) {
        List<MetricsBuffer.ConsoleEntry> entries = buffer.drainConsoleMessages();

        int errors = 0;
        int warnings = 0;
        List<String> messages = new ArrayList<>();

        for (MetricsBuffer.ConsoleEntry entry : entries) {
            String sanitizedText = sanitizer.sanitize(entry.text());

            switch (entry.level()) {
                case LEVEL_ERROR -> {
                    errors++;
                    messages.add(sanitizedText);
                }
                case LEVEL_WARNING -> {
                    warnings++;
                    messages.add(sanitizedText);
                }
                default -> log.debug("BPM: Ignoring console message with unexpected level: {}", entry.level());
            }
        }

        return new ConsoleResult(errors, warnings, messages);
    }
}
