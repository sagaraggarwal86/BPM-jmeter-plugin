package io.github.sagaraggarwal86.jmeter.bpm.ai.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BpmPromptLoader — verifies the bundled prompt resource loads.
 */
@DisplayName("BpmPromptLoader")
class BpmPromptLoaderTest {

    @Test
    @DisplayName("load() returns non-null non-empty prompt from JAR resource")
    void load_returnsPrompt() {
        String prompt = BpmPromptLoader.load();
        assertNotNull(prompt, "Bundled bpm-ai-prompt.txt must be loadable");
        assertFalse(prompt.isEmpty(), "Prompt must not be empty");
        assertTrue(prompt.contains("RULE"), "Prompt should contain rule definitions");
    }
}
