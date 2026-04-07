package io.github.sagaraggarwal86.jmeter.bpm.util;

/**
 * HTML-related utilities shared across report renderers.
 *
 * <p>This class is not instantiable; all members are static.
 */
public final class HtmlUtils {

    private HtmlUtils() {
        throw new UnsupportedOperationException("HtmlUtils is a utility class");
    }

    /**
     * Escapes a string for safe inclusion in HTML content or attribute values.
     * Replaces the five XML special characters ({@code & < > " '}).
     *
     * @param text the raw text to escape
     * @return the HTML-safe text
     */
    public static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Builds a severity tag {@code <span>} for Critical or Warning display.
     *
     * @param hasCritical true for Critical (red), false for Warning (yellow)
     * @return HTML span element with appropriate CSS class
     */
    public static String severityTag(boolean hasCritical) {
        String css = hasCritical ? "severity-critical" : "severity-warning";
        String label = hasCritical ? "Critical" : "Warning";
        return "<span class=\"severity-tag " + css + "\">" + label + "</span>";
    }
}
