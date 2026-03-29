package io.github.sagaraggarwal86.jmeter.bpm.output;

import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import io.github.sagaraggarwal86.jmeter.bpm.model.DerivedMetrics;
import io.github.sagaraggarwal86.jmeter.bpm.util.BpmConstants; // CHANGED
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for JsonlWriter.
 */
@DisplayName("JsonlWriter")
class JsonlWriterTest {

    @TempDir
    Path tempDir;

    private BpmResult createMinimalResult(String label) {
        DerivedMetrics derived = new DerivedMetrics(0L, 0.0, null, 0L,
                null, null, 0.0, BpmConstants.BOTTLENECK_NONE, List.of(), 50); // CHANGED: 10-arg constructor
        return new BpmResult("1.0", "2026-01-01T00:00:00Z", "Thread-1", 1,
                label, true, 100, null, null, null, null, derived);
    }

    @Test
    @DisplayName("open() creates file and parent directories")
    void open_createsFileAndParentDirs() throws IOException {
        Path nested = tempDir.resolve("sub").resolve("dir").resolve("output.jsonl");
        JsonlWriter writer = new JsonlWriter();
        writer.open(nested);
        writer.close();

        assertTrue(Files.exists(nested));
    }

    @Test
    @DisplayName("write() appends one JSON object per line")
    void write_appendsOneJsonPerLine() throws IOException {
        Path path = tempDir.resolve("test.jsonl");
        JsonlWriter writer = new JsonlWriter();
        writer.open(path);
        writer.write(createMinimalResult("Page A"));
        writer.write(createMinimalResult("Page B"));
        writer.close();

        List<String> lines = Files.readAllLines(path);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"Page A\""));
        assertTrue(lines.get(1).contains("\"Page B\""));
    }

    @Test
    @DisplayName("write() flushes after 10 records")
    void write_flushesEvery10Records() throws IOException {
        Path path = tempDir.resolve("flush.jsonl");
        JsonlWriter writer = new JsonlWriter();
        writer.open(path);

        for (int i = 0; i < 10; i++) {
            writer.write(createMinimalResult("Page " + i));
        }
        // After 10 writes, buffer should have been flushed
        // Verify by reading the file (should have 10 lines even without close)
        long size = Files.size(path);
        assertTrue(size > 0, "File should have data after 10 writes (auto-flush)");

        writer.close();
    }

    @Test
    @DisplayName("close() flushes remaining records and allows safe double-close")
    void close_flushesAndSafeDoubleClose() throws IOException {
        Path path = tempDir.resolve("close.jsonl");
        JsonlWriter writer = new JsonlWriter();
        writer.open(path);
        writer.write(createMinimalResult("Page 1"));

        writer.close();
        assertFalse(writer.isOpen());

        // Double close should not throw
        assertDoesNotThrow(writer::close);

        List<String> lines = Files.readAllLines(path);
        assertEquals(1, lines.size());
    }

    @Test
    @DisplayName("write() without open is a no-op (no exception)")
    void write_withoutOpen_noException() {
        JsonlWriter writer = new JsonlWriter();
        assertDoesNotThrow(() -> writer.write(createMinimalResult("Page")));
    }

    @Test
    @DisplayName("open() overwrites existing file content")
    void open_overwritesExistingFile() throws IOException {
        Path path = tempDir.resolve("overwrite.jsonl");
        Files.writeString(path, "old content\n");

        JsonlWriter writer = new JsonlWriter();
        writer.open(path);
        writer.write(createMinimalResult("New"));
        writer.close();

        List<String> lines = Files.readAllLines(path);
        assertEquals(1, lines.size());
        assertFalse(lines.get(0).contains("old content"));
        assertTrue(lines.get(0).contains("\"New\""));
    }
}