package io.github.sagaraggarwal86.jmeter.bpm.ai.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AiProviderConfig constructor validation.
 */
@DisplayName("AiProviderConfig")
class AiProviderConfigTest {

    @Test
    @DisplayName("Valid config creates successfully")
    void validConfig() {
        AiProviderConfig cfg = new AiProviderConfig(
                "groq", "Groq (Free)", "gsk_abc123",
                "llama-3.3-70b", "https://api.groq.com/openai/v1",
                60, 8192, 0.3);
        assertEquals("groq", cfg.providerKey);
        assertEquals("https://api.groq.com/openai/v1/chat/completions", cfg.chatCompletionsUrl());
    }

    @Test
    @DisplayName("Blank providerKey throws IllegalArgumentException")
    void blankProviderKey() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("", "Name", "key", "model", "https://url", 60, 8192, 0.3));
    }

    @Test
    @DisplayName("Null apiKey throws IllegalArgumentException")
    void nullApiKey() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("k", "Name", null, "model", "https://url", 60, 8192, 0.3));
    }

    @Test
    @DisplayName("Timeout ≤ 0 throws IllegalArgumentException")
    void zeroTimeout() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("k", "Name", "key", "model", "https://url", 0, 8192, 0.3));
    }

    @Test
    @DisplayName("MaxTokens ≤ 0 throws IllegalArgumentException")
    void zeroMaxTokens() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("k", "Name", "key", "model", "https://url", 60, 0, 0.3));
    }

    @Test
    @DisplayName("Temperature < 0 throws IllegalArgumentException")
    void negativeTemperature() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("k", "Name", "key", "model", "https://url", 60, 8192, -0.1));
    }

    @Test
    @DisplayName("Temperature > 2.0 throws IllegalArgumentException")
    void tooHighTemperature() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiProviderConfig("k", "Name", "key", "model", "https://url", 60, 8192, 2.1));
    }

    @Test
    @DisplayName("Trailing slashes stripped from baseUrl")
    void trailingSlashStripped() {
        AiProviderConfig cfg = new AiProviderConfig(
                "k", "Name", "key", "model", "https://api.example.com/v1///",
                60, 8192, 0.3);
        assertEquals("https://api.example.com/v1", cfg.baseUrl);
    }

    @Test
    @DisplayName("toString() returns displayName (not apiKey)")
    void toStringReturnsDisplayName() {
        AiProviderConfig cfg = new AiProviderConfig(
                "k", "My Provider", "secret-key", "model", "https://url",
                60, 8192, 0.3);
        assertEquals("My Provider", cfg.toString());
        assertFalse(cfg.toString().contains("secret"));
    }
}
