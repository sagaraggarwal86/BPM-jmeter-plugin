package io.github.sagaraggarwal86.jmeter.bpm.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks sensitive data patterns found in browser console messages before they are written to the
 * JSONL output file.
 *
 * <p>Sanitization is enabled by default ({@link BpmConstants#DEFAULT_SECURITY_SANITIZE}) and is
 * controlled by the {@code security.sanitize} property in {@code bpm.properties}. When disabled,
 * {@link #sanitize(String)} returns the original message unchanged — useful for local debugging
 * where raw console output is needed.
 *
 * <p>All regex patterns are compiled once at class-load time ({@code static final}) and reused
 * across threads. This class is thread-safe: the {@code enabled} flag is {@code final}, and
 * {@link Pattern#matcher} creates per-call {@link java.util.regex.Matcher} instances.
 *
 * <h2>Patterns and masking strategy</h2>
 * <p>Matched sensitive values are replaced with the literal string {@code [REDACTED]}, preserving
 * surrounding context so the error message remains useful:
 * <pre>
 * Before: "Failed: Authorization: Bearer eyJhbGciOiJIUzI1..."
 * After:  "Failed: Authorization: Bearer [REDACTED]"
 * </pre>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * ConsoleSanitizer sanitizer = new ConsoleSanitizer(propertiesManager.isSecuritySanitizeEnabled());
 * String safe = sanitizer.sanitize(rawConsoleMessage);
 * }</pre>
 */
public final class ConsoleSanitizer {

    /**
     * Replacement token for all matched sensitive values.
     */
    public static final String REDACTED = "[REDACTED]";

    // ── Compiled patterns ─────────────────────────────────────────────────────────────────────

    /**
     * Matches Bearer/Auth token header values.
     * Example: {@code Authorization: Bearer eyJhbGciOi...}
     * Group 1 (preserved): {@code Authorization: Bearer }
     * Group 2 (redacted): the token value
     */
    private static final Pattern PATTERN_BEARER_TOKEN = Pattern.compile(
            "(?i)((?:Authorization|Auth):\\s*Bearer\\s+)\\S+");

    /**
     * Matches common API key header values.
     * Example: {@code x-api-key: sk-live-4eC39Hq...}
     * Group 1 (preserved): the header name and colon+space
     */
    private static final Pattern PATTERN_API_KEY = Pattern.compile(
            "(?i)((?:x-api-key|api[-_]?key|apikey):\\s*)\\S+");

    /**
     * Matches three-part base64url-encoded JWT tokens (header.payload.signature).
     * The pattern requires at least 10 characters per segment to reduce false positives on short
     * version strings or other dot-separated identifiers.
     */
    private static final Pattern PATTERN_JWT = Pattern.compile(
            "eyJ[A-Za-z0-9+/=_-]{10,}\\.[A-Za-z0-9+/=_-]{10,}\\.[A-Za-z0-9+/=_-]{10,}");

    /**
     * Matches email addresses.
     * Example: {@code admin@company.com}
     */
    private static final Pattern PATTERN_EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    /**
     * Matches AWS access key IDs — 20-character strings beginning with {@code AKIA}.
     * Example: {@code AKIAIOSFODNN7EXAMPLE}
     */
    private static final Pattern PATTERN_AWS_ACCESS_KEY = Pattern.compile(
            "\\bAKIA[0-9A-Z]{16}\\b");

    /**
     * Matches password-like key=value pairs.
     * Example: {@code password=s3cret123} or {@code passwd=abc}
     * Group 1 (preserved): the key and equals sign
     */
    private static final Pattern PATTERN_PASSWORD = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|pass)\\s*=\\s*)\\S+");

    /**
     * Matches common database/broker connection string URIs.
     * Example: {@code mongodb://user:pass@host:27017/db}
     * The full URI (from scheme through end of non-whitespace run) is redacted.
     */
    private static final Pattern PATTERN_CONNECTION_STRING = Pattern.compile(
            "(?i)(?:mongodb|postgresql|postgres|mysql|mariadb|redis|amqp|amqps|jdbc:"
                    + "(?:mysql|postgresql|oracle|sqlserver|h2)?)://\\S+");

    /**
     * Matches 13–19 consecutive digit sequences that resemble payment card numbers.
     * A word-boundary check is applied on both sides to avoid masking version numbers embedded
     * in longer strings (e.g. {@code error_1234567890123_ok} would still match — this is
     * intentional; false positives are preferable to false negatives in a security context).
     */
    private static final Pattern PATTERN_CREDIT_CARD = Pattern.compile("\\b\\d{13,19}\\b");

    /**
     * Ordered list of all patterns paired with their replacement strings.
     * Patterns that use capturing groups preserve the non-sensitive prefix via {@code $1}.
     * Patterns without groups replace the entire match.
     */
    private static final List<PatternReplacement> PATTERNS = List.of(
            new PatternReplacement(PATTERN_BEARER_TOKEN, "$1" + REDACTED),
            new PatternReplacement(PATTERN_API_KEY, "$1" + REDACTED),
            new PatternReplacement(PATTERN_JWT, REDACTED),
            new PatternReplacement(PATTERN_EMAIL, REDACTED),
            new PatternReplacement(PATTERN_AWS_ACCESS_KEY, REDACTED),
            new PatternReplacement(PATTERN_PASSWORD, "$1" + REDACTED),
            new PatternReplacement(PATTERN_CONNECTION_STRING, REDACTED),
            new PatternReplacement(PATTERN_CREDIT_CARD, REDACTED)
    );

    // ── State ─────────────────────────────────────────────────────────────────────────────────

    private final boolean enabled;

    // ── Constructor ───────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new {@code ConsoleSanitizer}.
     *
     * @param enabled {@code true} to apply all masking patterns; {@code false} to return messages
     *                unchanged. Set from the {@code security.sanitize} property via
     *                {@code BpmPropertiesManager}.
     */
    public ConsoleSanitizer(boolean enabled) {
        this.enabled = enabled;
    }

    // ── Public API ────────────────────────────────────────────────────────────────────────────

    /**
     * Returns whether sanitization is active for this instance.
     *
     * @return {@code true} if patterns will be applied
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sanitizes a single browser console message by replacing all recognised sensitive data
     * patterns with {@link #REDACTED}.
     *
     * <p>All patterns are applied sequentially, so a message containing multiple sensitive values
     * (e.g. both a JWT and an email) will have all of them masked in a single pass through the
     * list.
     *
     * <p>When {@code enabled} is {@code false} the method returns {@code message} unchanged with
     * no allocation overhead. A {@code null} input returns {@code null}.
     *
     * @param message the raw console message from the browser Log domain; may be {@code null}
     * @return the sanitized message, or the original if sanitization is disabled, or {@code null}
     * if the input was {@code null}
     */
    public String sanitize(String message) {
        if (!enabled || message == null || message.isEmpty()) {
            return message;
        }

        String result = message;
        for (PatternReplacement pr : PATTERNS) {
            result = pr.pattern().matcher(result).replaceAll(pr.replacement());
        }
        return result;
    }

    // ── Inner record ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable pair of a compiled {@link Pattern} and its replacement string.
     *
     * @param pattern     the compiled regex pattern to match
     * @param replacement the replacement string (may use back-references such as {@code $1})
     */
    private record PatternReplacement(Pattern pattern, String replacement) {
    }
}
