package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

import java.io.IOException;

/**
 * Domain exception thrown when the AI API returns an error, an empty response,
 * or when all retry attempts are exhausted.
 *
 * <p>Extends {@link IOException} so callers that declare {@code throws IOException}
 * need no signature change.</p>
 */
public class AiServiceException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
