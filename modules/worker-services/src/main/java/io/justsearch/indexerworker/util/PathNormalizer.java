/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import io.justsearch.configuration.PlatformPaths;
import java.io.File;
import java.util.Locale;

/**
 * Normalizes file paths for consistent storage and querying.
 * Critical for deletion to work correctly on Windows (case-insensitive).
 */
public final class PathNormalizer {
    private static final boolean IS_WINDOWS = PlatformPaths.isWindows();

    private PathNormalizer() {}

    /**
     * Normalizes a path for storage/querying.
     * - Converts forward slashes to platform separator
     * - Lowercases on Windows (case-insensitive filesystem)
     */
    public static String normalizePath(String path) {
        if (path == null) return null;
        String normalized = path.replace('/', File.separatorChar);
        if (IS_WINDOWS) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    /**
     * Normalizes a path prefix for deletion queries.
     * Ensures trailing separator so prefix matches only children.
     */
    public static String normalizePathPrefix(String path) {
        String normalized = normalizePath(path);
        // A blank/empty prefix must stay blank so callers reject it (deleteByPathPrefix refuses a
        // match-everything delete). Appending File.separator would turn "" into a bare "/" (Linux)
        // — non-blank — silently defeating that guard on Linux (tempdoc 668).
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }
        if (!normalized.endsWith(File.separator)) {
            normalized = normalized + File.separator;
        }
        return normalized;
    }
}
