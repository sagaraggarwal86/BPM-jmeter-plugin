package io.github.sagaraggarwal86.jmeter.bpm.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports table data to CSV format for the Save Table feature.
 *
 * <p>Writes column headers as the first row followed by data rows.
 * Handles quoting and escaping for values containing commas, quotes,
 * or newlines per RFC 4180.</p>
 *
 * <p>Called from the GUI Save Table button. Exports exactly what is
 * visible in the table (respects label filter and column selection).</p>
 */
public final class CsvExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvExporter.class);

    private static final char COMMA = ',';
    private static final char QUOTE = '"';
    private static final String NEWLINE = System.lineSeparator();

    /**
     * Prevent instantiation.
     */
    private CsvExporter() {
    }

    /**
     * Exports data to a CSV file.
     *
     * @param outputPath    the file path to write the CSV to
     * @param columnHeaders the column header names (first row)
     * @param rows          the data rows; each inner list corresponds to one row
     *                      with values in the same order as {@code columnHeaders}
     * @throws IOException if the file cannot be written
     */
    public static void export(Path outputPath, List<String> columnHeaders, List<List<String>> rows)
            throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header row
            writeLine(writer, columnHeaders);

            // Data rows
            for (List<String> row : rows) {
                writeLine(writer, row);
            }
        }

        log.info("BPM: CSV exported to {} ({} rows, {} columns)",
                outputPath, rows.size(), columnHeaders.size());
    }

    /**
     * Writes a single CSV line from a list of values.
     */
    private static void writeLine(BufferedWriter writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(COMMA);
            }
            writer.write(escapeCsvValue(values.get(i)));
        }
        writer.write(NEWLINE);
    }

    /**
     * Escapes a single CSV value per RFC 4180.
     *
     * <p>If the value contains a comma, double quote, or newline character,
     * the entire value is enclosed in double quotes and any existing double
     * quotes are doubled.</p>
     *
     * @param value the raw value (may be null)
     * @return the escaped CSV value
     */
    static String escapeCsvValue(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.indexOf(COMMA) >= 0
                || value.indexOf(QUOTE) >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 4);
        sb.append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE) {
                sb.append(QUOTE); // double the quote
            }
            sb.append(c);
        }
        sb.append(QUOTE);
        return sb.toString();
    }
}
