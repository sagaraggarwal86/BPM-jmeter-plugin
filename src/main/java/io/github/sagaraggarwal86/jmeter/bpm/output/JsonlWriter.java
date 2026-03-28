package io.github.sagaraggarwal86.jmeter.bpm.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.sagaraggarwal86.jmeter.bpm.model.BpmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes {@link BpmResult} records as JSONL (one JSON object per line).
 *
 * <p>Write strategy: {@link BufferedWriter} with flush every 10 records.
 * Final flush in {@link #close()}. File is overwritten at open time
 * (fresh for each test run).</p>
 *
 * <p>Thread-safe: the {@link #write(BpmResult)} method is synchronized
 * to support concurrent calls from multiple JMeter threads.</p>
 */
public final class JsonlWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlWriter.class);

    /**
     * Number of records between automatic flushes.
     */
    private static final int FLUSH_INTERVAL = 10;

    private final ObjectMapper objectMapper;
    private BufferedWriter writer;
    private Path outputPath;
    private int recordsSinceFlush;

    /**
     * Creates a new JSONL writer with a pre-configured Jackson ObjectMapper.
     */
    public JsonlWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Opens the JSONL output file for writing. Overwrites any existing file.
     *
     * @param path the output file path
     * @throws IOException if the file cannot be created or opened
     */
    public void open(Path path) throws IOException {
        this.outputPath = path;
        this.recordsSinceFlush = 0;
        // Ensure parent directories exist
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Overwrite (fresh for new test) — TRUNCATE_EXISTING + CREATE + WRITE
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * Serializes a {@link BpmResult} to JSON and appends it as a single line.
     * Flushes the buffer every {@value FLUSH_INTERVAL} records.
     *
     * <p>Thread-safe: synchronized to prevent interleaved writes from
     * concurrent JMeter threads.</p>
     *
     * @param result the BPM result to write
     */
    public synchronized void write(BpmResult result) {
        if (writer == null) {
            log.warn("BPM: JSONL writer not open. Skipping write.");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            writer.write(json);
            writer.newLine();
            recordsSinceFlush++;
            if (recordsSinceFlush >= FLUSH_INTERVAL) {
                writer.flush();
                recordsSinceFlush = 0;
            }
        } catch (JsonProcessingException e) {
            log.warn("BPM: Failed to serialize BpmResult to JSON. Skipping record.", e);
        } catch (IOException e) {
            log.warn("BPM: Failed to write JSONL record to {}", outputPath, e);
        }
    }

    /**
     * Flushes any buffered data to disk.
     */
    public synchronized void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            recordsSinceFlush = 0;
        } catch (IOException e) {
            log.warn("BPM: Failed to flush JSONL writer for {}", outputPath, e);
        }
    }

    /**
     * Flushes and closes the writer. Safe to call multiple times.
     */
    public synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            log.warn("BPM: Failed to close JSONL writer for {}", outputPath, e);
        } finally {
            writer = null;
            recordsSinceFlush = 0;
        }
    }

    /**
     * Returns the output file path, or null if not yet opened.
     *
     * @return the output path
     */
    public Path getOutputPath() {
        return outputPath;
    }

    /**
     * Returns whether the writer is currently open.
     *
     * @return true if open and ready for writing
     */
    public boolean isOpen() {
        return writer != null;
    }
}
