package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import java.util.Objects;

/**
 * Immutable two-part AI request payload for the OpenAI-compatible chat-completions API.
 *
 * @param systemPrompt   static analytical framework instructions
 * @param userMessage    runtime BPM test data with pre-computed verdicts
 * @param totalLabels    total number of labels before truncation (0 if not truncated)
 * @param includedLabels number of labels included in the prompt (0 if not truncated)
 */
public record BpmPromptContent(String systemPrompt, String userMessage,
                               int totalLabels, int includedLabels) {

    public BpmPromptContent {
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
    }

    /**
     * Backward-compatible constructor for non-truncated prompts.
     */
    public BpmPromptContent(String systemPrompt, String userMessage) {
        this(systemPrompt, userMessage, 0, 0);
    }

    /**
     * Returns true if labels were truncated to fit the AI token budget.
     */
    public boolean wasTruncated() {
        return totalLabels > 0 && totalLabels > includedLabels;
    }
}
