package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import java.util.Objects;

/**
 * Immutable two-part AI request payload for the OpenAI-compatible chat-completions API.
 *
 * @param systemPrompt static analytical framework instructions
 * @param userMessage  runtime BPM test data with pre-computed verdicts
 */
public record BpmPromptContent(String systemPrompt, String userMessage) {

    public BpmPromptContent {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
    }
}
