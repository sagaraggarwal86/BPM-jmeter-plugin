package io.github.sagaraggarwal86.jmeter.bpm.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File system utilities shared across output writers and report generators.
 *
 * <p>This class is not instantiable; all members are static.
 */
public final class BpmFileUtils {

    private BpmFileUtils() {
        throw new UnsupportedOperationException("BpmFileUtils is a utility class");
    }

    /**
     * Ensures that the parent directory of the given path exists, creating it
     * (and any missing ancestors) if necessary. Safe to call when the path has
     * no parent component (e.g. a bare filename).
     *
     * @param path the file path whose parent directory should exist
     * @throws IOException if directory creation fails
     */
    public static void ensureParentDirectories(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
