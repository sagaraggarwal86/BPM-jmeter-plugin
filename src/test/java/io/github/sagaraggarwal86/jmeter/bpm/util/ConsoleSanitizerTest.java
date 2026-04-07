package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for ConsoleSanitizer.
 */
@DisplayName("ConsoleSanitizer")
class ConsoleSanitizerTest {

    @Test
    @DisplayName("Bearer token is redacted")
    void sanitize_bearerToken_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.sig";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("eyJhbG"), "Bearer token should be redacted");
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("JWT token (three-part base64) is redacted")
    void sanitize_jwtToken_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "Token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.dGVzdHNpZw";
        String result = sanitizer.sanitize(input);
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Email address is redacted")
    void sanitize_email_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "User logged in: admin@company.com";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("admin@company.com"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("AWS access key is redacted")
    void sanitize_awsKey_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "Key: AKIAIOSFODNN7EXAMPLE";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    @DisplayName("Password-like value is redacted")
    void sanitize_password_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "Config: password=s3cretP@ss123";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("s3cretP@ss123"));
    }

    @Test
    @DisplayName("Sanitization disabled returns original message unchanged")
    void sanitize_disabled_returnsOriginal() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(false);
        String input = "Bearer eyJhbGciOiJIUzI1NiJ9 admin@test.com";
        assertEquals(input, sanitizer.sanitize(input));
    }


    @Test
    @DisplayName("Connection string URI is redacted")
    void sanitize_connectionString_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "DB: mongodb://admin:secret@host:27017/mydb";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("admin:secret@host"), "Connection string credentials should be redacted");
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    @DisplayName("Credit card number pattern is redacted")
    void sanitize_creditCard_redacted() {
        ConsoleSanitizer sanitizer = new ConsoleSanitizer(true);
        String input = "Card: 4111111111111111 on file";
        String result = sanitizer.sanitize(input);
        assertFalse(result.contains("4111111111111111"), "Credit card number should be redacted");
        assertTrue(result.contains("[REDACTED]"));
    }
}