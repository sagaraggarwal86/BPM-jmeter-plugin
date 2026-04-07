package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 1 unit tests for {@link HtmlUtils}.
 */
@DisplayName("HtmlUtils")
class HtmlUtilsTest {

    // ── escapeHtml ──

    @Test
    @DisplayName("escapeHtml escapes ampersand")
    void escapeHtml_ampersand() {
        assertEquals("foo &amp; bar", HtmlUtils.escapeHtml("foo & bar"));
    }

    @Test
    @DisplayName("escapeHtml escapes less-than")
    void escapeHtml_lessThan() {
        assertEquals("&lt;script&gt;", HtmlUtils.escapeHtml("<script>"));
    }

    @Test
    @DisplayName("escapeHtml escapes greater-than")
    void escapeHtml_greaterThan() {
        assertEquals("a &gt; b", HtmlUtils.escapeHtml("a > b"));
    }

    @Test
    @DisplayName("escapeHtml escapes double quote")
    void escapeHtml_doubleQuote() {
        assertEquals("say &quot;hi&quot;", HtmlUtils.escapeHtml("say \"hi\""));
    }

    @Test
    @DisplayName("escapeHtml escapes single quote")
    void escapeHtml_singleQuote() {
        assertEquals("it&#39;s", HtmlUtils.escapeHtml("it's"));
    }

    @Test
    @DisplayName("escapeHtml handles all five characters together")
    void escapeHtml_allFive() {
        String input = "<div class=\"a\" data-x='b&c'>";
        String result = HtmlUtils.escapeHtml(input);
        assertTrue(result.contains("&lt;"));
        assertTrue(result.contains("&gt;"));
        assertTrue(result.contains("&quot;"));
        assertTrue(result.contains("&#39;"));
        assertTrue(result.contains("&amp;"));
    }

    @Test
    @DisplayName("escapeHtml returns plain text unchanged")
    void escapeHtml_plainText() {
        assertEquals("hello world", HtmlUtils.escapeHtml("hello world"));
    }

    // ── severityTag ──

    @Test
    @DisplayName("severityTag returns Critical span when hasCritical is true")
    void severityTag_critical() {
        String result = HtmlUtils.severityTag(true);
        assertEquals("<span class=\"severity-tag severity-critical\">Critical</span>", result);
    }

    @Test
    @DisplayName("severityTag returns Warning span when hasCritical is false")
    void severityTag_warning() {
        String result = HtmlUtils.severityTag(false);
        assertEquals("<span class=\"severity-tag severity-warning\">Warning</span>", result);
    }
}
