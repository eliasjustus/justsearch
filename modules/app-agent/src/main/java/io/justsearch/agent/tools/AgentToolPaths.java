/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared path validation utilities for agent tools.
 *
 * <p>{@code public} only so {@link RootsView} is nameable from the one construction authority that
 * builds it ({@code AgentToolFactory.assemble}, in {@code app-services}); the static helpers below
 * stay package-private because their callers are all in this package.
 */
public final class AgentToolPaths {
  private AgentToolPaths() {}

  private static final Logger LOG = LoggerFactory.getLogger(AgentToolPaths.class);

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

  /**
   * Tempdoc 877 §2.4 — the indexed roots as agent tools are allowed to see them: ONE guarded
   * accessor, ONE degrade rule, ONE relative→absolute algorithm.
   *
   * <p>Before this type there were thirteen call sites on the raw {@code Supplier}, five guarded
   * copies with FOUR different answers to "the supplier threw" (return the input unchanged, return
   * null, log-and-degrade-open, propagate as a tool error) and four that did not guard at all — so
   * whether a Worker hiccup during a roots lookup surfaced as a silent pass, a rejected path or a
   * "Browse error" depended on which tool the model happened to call.
   *
   * <p><b>Degrade OPEN, deliberately.</b> {@link #validate} returns {@code null} (valid) when the
   * roots are unknown or empty, preserved verbatim from the three wrappers it replaces: "no roots
   * configured" means the tool cannot say, and the Worker's own index-membership check is the real
   * boundary regardless. Fail-closed here would turn a transient roots-lookup failure into a
   * blanket refusal to read anything.
   */
  public static final class RootsView {

    /** Nullable: a tool wired without a roots supplier (test constructors) behaves as "no roots". */
    private final Supplier<List<BrowseTool.RootInfo>> supplier;

    private final AtomicBoolean warned = new AtomicBoolean();

    private RootsView(Supplier<List<BrowseTool.RootInfo>> supplier) {
      this.supplier = supplier;
    }

    /** Null-tolerant: {@code of(null)} is a view whose {@link #roots()} is always empty. */
    public static RootsView of(Supplier<List<BrowseTool.RootInfo>> supplier) {
      return new RootsView(supplier);
    }

    /** Never throws, never null. A throwing supplier warns ONCE and reads as "no roots". */
    List<BrowseTool.RootInfo> roots() {
      if (supplier == null) {
        return List.of();
      }
      try {
        List<BrowseTool.RootInfo> got = supplier.get();
        return got == null ? List.of() : got;
      } catch (RuntimeException e) {
        if (warned.compareAndSet(false, true)) {
          LOG.warn("indexed-roots lookup failed; agent tool paths degrade open", e);
        }
        return List.of();
      }
    }

    /**
     * {@code null} when {@code path} is valid OR when the roots are unknown/empty; otherwise the
     * rejection message from {@link AgentToolPaths#validateAgainstRoots}.
     */
    String validate(String path, String paramName) {
      List<BrowseTool.RootInfo> rootInfos = roots();
      if (rootInfos.isEmpty()) {
        return null;
      }
      List<String> rootPaths = new ArrayList<>(rootInfos.size());
      for (BrowseTool.RootInfo r : rootInfos) {
        rootPaths.add(r.path());
      }
      return validateAgainstRoots(path, rootPaths, paramName);
    }

    /** The absolute form of a root-relative path, or {@code null} when no root matches. */
    String resolveRelative(String path) {
      return resolveRelativePath(path, roots());
    }
  }
}
