package io.github.sagaraggarwal86.jmeter.bpm.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 1 unit tests for {@link BpmFileUtils}.
 */
@DisplayName("BpmFileUtils")
class BpmFileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ensureParentDirectories creates nested parent directories")
    void ensureParent_createsNestedDirs() throws IOException {
        Path nested = tempDir.resolve("a/b/c/file.txt");
        BpmFileUtils.ensureParentDirectories(nested);
        assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")));
    }

    @Test
    @DisplayName("ensureParentDirectories is no-op when parent already exists")
    void ensureParent_existingDir_noOp() throws IOException {
        Path file = tempDir.resolve("file.txt");
        assertDoesNotThrow(() -> BpmFileUtils.ensureParentDirectories(file));
    }

    @Test
    @DisplayName("ensureParentDirectories handles bare filename (no parent)")
    void ensureParent_bareFilename_noOp() {
        Path bare = Path.of("file.txt");
        assertDoesNotThrow(() -> BpmFileUtils.ensureParentDirectories(bare));
    }
}
