package io.github.sagaraggarwal86.jmeter.bpm.output;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layer 1 unit tests for CsvExporter.
 */
@DisplayName("CsvExporter")
class CsvExporterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("export() writes headers as first row followed by data rows")
    void export_writesHeadersAndRows() throws IOException {
        Path path = tempDir.resolve("test.csv");
        List<String> headers = List.of("Label", "Score", "Bottleneck");
        List<List<String>> rows = List.of(
                List.of("Login", "82", "Client rendering"),
                List.of("Dashboard", "41", "Server bottleneck"));

        CsvExporter.export(path, headers, rows);

        List<String> lines = Files.readAllLines(path);
        assertEquals(3, lines.size());
        assertEquals("Label,Score,Bottleneck", lines.get(0));
        assertEquals("Login,82,Client rendering", lines.get(1));
    }

    @Test
    @DisplayName("export() escapes values containing commas with double quotes")
    void export_escapesCommas() throws IOException {
        Path path = tempDir.resolve("escape.csv");
        List<String> headers = List.of("Name", "Description");
        List<List<String>> rows = List.of(
                List.of("Test", "value, with comma"));

        CsvExporter.export(path, headers, rows);

        List<String> lines = Files.readAllLines(path);
        assertEquals("Test,\"value, with comma\"", lines.get(1));
    }

    @Test
    @DisplayName("escapeCsvValue doubles existing quotes inside quoted values")
    void escapeCsvValue_doublesQuotes() {
        String result = CsvExporter.escapeCsvValue("He said \"hello\"");
        assertEquals("\"He said \"\"hello\"\"\"", result);
    }

    @Test
    @DisplayName("escapeCsvValue returns empty string for null")
    void escapeCsvValue_null() {
        assertEquals("", CsvExporter.escapeCsvValue(null));
    }

    @Test
    @DisplayName("escapeCsvValue quotes value containing newline")
    void escapeCsvValue_newline() {
        String result = CsvExporter.escapeCsvValue("line1\nline2");
        assertEquals("\"line1\nline2\"", result);
    }

    @Test
    @DisplayName("escapeCsvValue quotes value containing carriage return")
    void escapeCsvValue_carriageReturn() {
        String result = CsvExporter.escapeCsvValue("line1\rline2");
        assertEquals("\"line1\rline2\"", result);
    }

    @Test
    @DisplayName("escapeCsvValue returns plain text unchanged")
    void escapeCsvValue_plainText() {
        assertEquals("hello", CsvExporter.escapeCsvValue("hello"));
    }
}
