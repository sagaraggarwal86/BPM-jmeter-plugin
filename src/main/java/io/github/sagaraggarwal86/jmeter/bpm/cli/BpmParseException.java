package io.github.sagaraggarwal86.jmeter.bpm.cli;

import java.io.IOException;

/**
 * Thrown when BPM JSONL input cannot be parsed or contains no usable data.
 * Mapped to exit code {@link Main#EXIT_PARSE_ERROR} in the CLI.
 */
final class BpmParseException extends IOException {

    private static final long serialVersionUID = 1L;

    BpmParseException(String message) {
        super(message);
    }
}
