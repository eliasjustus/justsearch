/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/** Shared path validation utilities for agent tools. */
final class AgentToolPaths {
  private AgentToolPaths() {}

  /**
   * Resolves a relative path against indexed roots by matching the first path component against
   * root names (case-insensitive). Returns the resolved absolute path, or null if no root matches.
   */
  static String resolveRelativePath(String relativePath, List<BrowseTool.RootInfo> roots) {
    if (roots == null || roots.isEmpty()) return null;
    try {
      Path rel = Path.of(relativePath).normalize();
      if (rel.getNameCount() == 0) return null;
      String first = rel.getName(0).toString();
      for (BrowseTool.RootInfo root : roots) {
        if (root.name().equalsIgnoreCase(first)) {
          if (rel.getNameCount() == 1) return root.path();
          return Path.of(root.path())
              .resolve(rel.subpath(1, rel.getNameCount()))
              .normalize()
              .toString();
        }
      }
    } catch (InvalidPathException e) {
      // Fall through
    }
    return null;
  }

  /** Cross-platform absolute path check using {@link Path#isAbsolute()}. */
  static boolean looksAbsolute(String path) {
    if (path == null || path.isEmpty()) return false;
    try {
      return Path.of(path).isAbsolute();
    } catch (InvalidPathException e) {
      return false;
    }
  }

  /**
   * Validates that {@code path} is an absolute path under one of the provided roots. Returns
   * {@code null} if valid, or an error message string if rejected.
   *
   * @param path the path to validate
   * @param rootPaths list of absolute root folder paths
   * @param paramName the parameter name for error messages (e.g. "path_prefix", "parent_path")
   */
  static String validateAgainstRoots(String path, List<String> rootPaths, String paramName) {
    if (!looksAbsolute(path)) {
      return "Invalid "
          + paramName
          + ": \""
          + path
          + "\" is not an absolute path. Use one of the indexed root folders: "
          + formatRootsList(rootPaths);
    }
    try {
      Path normalized = Path.of(path).normalize();
      for (String root : rootPaths) {
        if (normalized.startsWith(Path.of(root).normalize())) {
          return null; // Valid — under this root
        }
      }
    } catch (InvalidPathException e) {
      return "Invalid "
          + paramName
          + ": \""
          + path
          + "\" is not a valid path. Use one of the indexed root folders: "
          + formatRootsList(rootPaths);
    }
    return "Invalid "
        + paramName
        + ": \""
        + path
        + "\" is not under any indexed root folder. Available roots: "
        + formatRootsList(rootPaths);
  }

  static String formatRootsList(List<String> roots) {
    var sb = new StringBuilder();
    for (int i = 0; i < roots.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append('"').append(roots.get(i)).append('"');
    }
    return sb.toString();
  }
}
