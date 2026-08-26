/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared path validation utilities for agent tools. */
final class AgentToolPaths {
  private AgentToolPaths() {}

  /**
   * Resolves a relative path against indexed roots by matching the first path component against
   * root names (case-insensitive). Returns the resolved absolute path, or null if no root matches
   * or if the name is ambiguous.
   */
  static String resolveRelativePath(String relativePath, List<BrowseTool.RootInfo> roots) {
    if (roots == null || roots.isEmpty()) return null;
    try {
      Path rel = Path.of(relativePath).normalize();
      if (rel.getNameCount() == 0) return null;
      String first = rel.getName(0).toString();
      BrowseTool.RootInfo match = null;
      for (BrowseTool.RootInfo root : roots) {
        if (root.name().equalsIgnoreCase(first)) {
          // Two indexed roots can share a leaf name (D:\a\docs and E:\b\docs). The reference is
          // then genuinely ambiguous, so refuse it rather than silently picking whichever root
          // the supplier happened to list first — a caller that gets a path back has no way to
          // tell it was resolved against the wrong folder (tempdoc 875 §C.5).
          if (match != null) return null;
          match = root;
        }
      }
      if (match == null) return null;
      if (rel.getNameCount() == 1) return match.path();
      return Path.of(match.path())
          .resolve(rel.subpath(1, rel.getNameCount()))
          .normalize()
          .toString();
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
      Path candidate = canonicalizeForContainment(Path.of(path));
      for (String root : rootPaths) {
        if (candidate.startsWith(canonicalizeForContainment(Path.of(root)))) {
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
    } catch (IOException e) {
      // Containment could not be PROVEN, so it is not granted: an unreadable link or mount point
      // on the way up must never resolve to "allowed" (tempdoc 875 §C.5).
      return "Invalid "
          + paramName
          + ": \""
          + path
          + "\" could not be resolved for containment checking. Use one of the indexed root"
          + " folders: "
          + formatRootsList(rootPaths);
    }
    return "Invalid "
        + paramName
        + ": \""
        + path
        + "\" is not under any indexed root folder. Available roots: "
        + formatRootsList(rootPaths);
  }

  /**
   * Resolves {@code path} through the real path of its closest EXISTING ancestor, then re-appends
   * the segments that do not exist yet. Comparing both sides of a containment check this way is
   * what stops a symlink or junction from straddling a root boundary — a plain
   * {@code normalize() + startsWith} accepts a path that only looks in-root before link resolution.
   * Mirrors {@code FileOperationExecutor#resolveClosestExistingAncestor}.
   *
   * <p>When nothing on the way up exists (a wholly fictional path, an unmounted drive) the plain
   * normalized absolute form is returned, so candidate and root still compare consistently.
   */
  private static Path canonicalizeForContainment(Path path) throws IOException {
    Path abs = path.toAbsolutePath().normalize();
    if (Files.exists(abs)) {
      return abs.toRealPath();
    }
    List<Path> missingSegments = new ArrayList<>();
    Path current = abs;
    while (current != null && !Files.exists(current)) {
      missingSegments.add(current.getFileName());
      current = current.getParent();
    }
    if (current == null) {
      return abs;
    }
    Path resolved = current.toRealPath();
    for (int i = missingSegments.size() - 1; i >= 0; i--) {
      resolved = resolved.resolve(missingSegments.get(i));
    }
    return resolved;
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
