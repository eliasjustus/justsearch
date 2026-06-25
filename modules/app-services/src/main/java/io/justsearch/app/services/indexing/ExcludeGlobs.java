/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.indexing;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ExcludeGlobs
 *
 * <p>Small helper to interpret UI-provided exclude patterns (glob strings) against paths under a watched root.
 *
 * <p>v1 semantics:
 * - Patterns are matched against the root-relative path (with forward slashes).
 * - Users can use {@code ** /} (double-star + slash) to match at any depth (gitignore-like).
 * - Matching is case-insensitive on Windows.
 *
 * <p>Source of truth: {@code -Djustsearch.ui.exclude_patterns=[...]} (JSON string array).
 */
public final class ExcludeGlobs {
  public static final String SYS_PROP_EXCLUDE_PATTERNS = "justsearch.ui.exclude_patterns";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<String> patterns;
  private final List<Pattern> fileMatchers;
  private final Set<String> excludeDirNamesAnyDepth;
  private final Set<String> excludeDirRelPrefixes;
  private final boolean windows;

  private ExcludeGlobs(
      List<String> patterns,
      List<Pattern> fileMatchers,
      Set<String> excludeDirNamesAnyDepth,
      Set<String> excludeDirRelPrefixes,
      boolean windows) {
    this.patterns = patterns;
    this.fileMatchers = fileMatchers;
    this.excludeDirNamesAnyDepth = excludeDirNamesAnyDepth;
    this.excludeDirRelPrefixes = excludeDirRelPrefixes;
    this.windows = windows;
  }

  /**
   * Parse a JSON-encoded pattern list (string form of a JSON array) into an ExcludeGlobs.
   *
   * <p>Tempdoc 519 §9 Block B3.0.b: moved from {@code io.justsearch.ui.indexing} to
   * {@code app-services}. The previous {@code fromSystemProperties()} factory read
   * {@code -Djustsearch.ui.exclude_patterns=[…]} directly. After the move, the
   * {@code AppServicesWorkerGuardrailsTest} ArchUnit rule bars ad-hoc
   * {@code System.getProperty} in app-services; callers (currently
   * {@code IndexingController.applyExcludes}) now read the sysprop themselves (ui is
   * not subject to the guardrail) and pass the raw JSON string here.
   */
  public static ExcludeGlobs fromRawJsonArray(String raw) {
    if (raw == null || raw.isBlank()) {
      return empty();
    }
    try {
      List<String> patterns = JSON.readValue(raw, new TypeReference<List<String>>() {});
      return fromPatterns(patterns);
    } catch (Exception e) {
      // Fail open: bad sysprop should not break indexing.
      return empty();
    }
  }

  public static ExcludeGlobs empty() {
    boolean win = isWindows();
    return new ExcludeGlobs(List.of(), List.of(), Set.of(), Set.of(), win);
  }

  public static ExcludeGlobs fromPatterns(List<String> patterns) {
    boolean win = isWindows();

    if (patterns == null || patterns.isEmpty()) {
      return empty();
    }

    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    for (String p : patterns) {
      if (p == null) continue;
      String s = p.trim();
      if (s.isBlank()) continue;
      // Normalize separators to forward slashes to keep matching deterministic across OSes.
      s = s.replace('\\', '/');
      // Expand bare patterns for .gitignore-like convenience:
      //   "dist"   → "**/dist/**" + "**/dist"  (name at any depth, file or directory)
      //   "dist/"  → "**/dist/**"              (directory at any depth)
      //   "*.log"  → "**/*.log"                (glob without slash → any depth)
      for (String expanded : expandBarePattern(s)) {
        cleaned.add(expanded);
        if (cleaned.size() >= 512) break;
      }
      if (cleaned.size() >= 512) break;
    }

    if (cleaned.isEmpty()) {
      return empty();
    }

    List<String> expanded = List.copyOf(cleaned);

    int flags = win ? Pattern.CASE_INSENSITIVE : 0;
    List<Pattern> fileMatchers = new ArrayList<>();
    for (String glob : expanded) {
      Pattern re = Pattern.compile(globToRegex(glob), flags);
      fileMatchers.add(re);
    }

    Set<String> dirNamesAnyDepth = new LinkedHashSet<>();
    Set<String> dirRelPrefixes = new LinkedHashSet<>();
    for (String glob : expanded) {
      // **/NAME/** (directory name anywhere)
      String g = glob;
      if (g.startsWith("**/") && g.endsWith("/**")) {
        String mid = g.substring(3, g.length() - 3);
        if (!mid.contains("/") && !containsMeta(mid) && !mid.isBlank()) {
          dirNamesAnyDepth.add(win ? mid.toLowerCase(Locale.ROOT) : mid);
          continue;
        }
      }
      // prefix/** where prefix has no meta (relative directory path under root)
      if (g.endsWith("/**")) {
        String prefix = g.substring(0, g.length() - 3);
        if (!prefix.isBlank() && !containsMeta(prefix)) {
          dirRelPrefixes.add(normalizeRel(prefix, win));
        }
      }
    }

    return new ExcludeGlobs(expanded, fileMatchers, dirNamesAnyDepth, dirRelPrefixes, win);
  }

  public boolean isEmpty() {
    return patterns.isEmpty();
  }

  public List<String> patterns() {
    return patterns;
  }

  /**
   * Returns true if the given directory should be treated as excluded for the purpose of skipping traversal.
   *
   * <p>Note: full exclusion matching is done via {@link #isExcludedPath(Path, Path)}. This method is only used
   * as an optimization for common directory patterns (e.g., {@code ** /node_modules/**}).
   */
  public boolean isExcludedDirectory(Path root, Path dir) {
    if (isEmpty() || root == null || dir == null) return false;
    try {
      Path normalizedRoot = root.toAbsolutePath().normalize();
      Path normalizedDir = dir.toAbsolutePath().normalize();
      if (!normalizedDir.startsWith(normalizedRoot)) return false;
      Path rel = normalizedRoot.relativize(normalizedDir);
      if (rel == null) return false;
      String relStr = normalizeRel(rel.toString(), windows);
      if (!relStr.isBlank() && excludeDirRelPrefixes.contains(relStr)) {
        return true;
      }
      Path name = normalizedDir.getFileName();
      if (name != null) {
        String n = name.toString();
        String key = windows ? n.toLowerCase(Locale.ROOT) : n;
        return excludeDirNamesAnyDepth.contains(key);
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /** Returns true if the given path (file) is excluded by any configured glob. */
  public boolean isExcludedPath(Path root, Path path) {
    return matchingPatternIndex(root, path) >= 0;
  }

  /** Returns the index of the first pattern that would match files inside this directory, or -1. */
  public int matchingDirectoryPatternIndex(Path root, Path dir) {
    return matchingPatternIndex(root, dir.resolve("_"));
  }

  /** Returns the index of the first matching pattern for this path, or -1 if none match. */
  public int matchingPatternIndex(Path root, Path path) {
    if (isEmpty() || root == null || path == null) return -1;
    try {
      Path normalizedRoot = root.toAbsolutePath().normalize();
      Path normalizedPath = path.toAbsolutePath().normalize();
      if (!normalizedPath.startsWith(normalizedRoot)) return -1;
      Path rel = normalizedRoot.relativize(normalizedPath);
      if (rel == null) return -1;
      String relStr = normalizeRel(rel.toString(), windows);
      if (relStr.isBlank()) return -1;
      for (int i = 0; i < fileMatchers.size(); i++) {
        if (fileMatchers.get(i).matcher(relStr).matches()) return i;
      }
      return -1;
    } catch (Exception e) {
      return -1;
    }
  }

  private static boolean isWindows() {
    try {
      String os = System.getProperty("os.name", "");
      return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean containsMeta(String s) {
    return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
  }

  private static String normalizeRel(String rel, boolean windows) {
    if (rel == null) return "";
    String s = rel.replace('\\', '/');
    // Avoid accidental leading "./"
    while (s.startsWith("./")) {
      s = s.substring(2);
    }
    // No trailing slash for matching
    if (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return windows ? s.toLowerCase(Locale.ROOT) : s;
  }

  /**
   * Expands bare patterns to match at any depth, following .gitignore-like conventions.
   *
   * <ul>
   *   <li>{@code dist} (no slash, no meta) → {@code ** /dist/**} + {@code ** /dist}
   *   <li>{@code dist/} (trailing slash only) → {@code ** /dist/**}
   *   <li>{@code *.log} (no slash, has meta) → {@code ** /*.log}
   *   <li>{@code ** /dist/**} (already qualified) → unchanged
   *   <li>{@code dist/**} (has slash) → unchanged (anchored to root)
   * </ul>
   */
  static List<String> expandBarePattern(String pattern) {
    if (pattern.startsWith("**/")) {
      return List.of(pattern);
    }
    // Trailing slash only, no other slashes: "dist/" → "**/dist/**"
    if (pattern.endsWith("/")) {
      String base = pattern.substring(0, pattern.length() - 1);
      if (!base.contains("/")) {
        return List.of("**/" + base + "/**");
      }
      // Internal slashes like "src/dist/" → "src/dist/**"
      return List.of(base + "/**");
    }
    // No slash at all
    if (!pattern.contains("/")) {
      if (containsMeta(pattern)) {
        // *.log → **/*.log
        return List.of("**/" + pattern);
      }
      // dist → **/dist/** + **/dist
      return List.of("**/" + pattern + "/**", "**/" + pattern);
    }
    // Has slashes (e.g., dist/**, src/vendor/**) — anchored, leave as-is
    return List.of(pattern);
  }

  /** Converts a subset of glob syntax into a regex anchored to the whole string. */
  private static String globToRegex(String glob) {
    String g = glob == null ? "" : glob;
    // Always match the entire relative path string.
    StringBuilder out = new StringBuilder(g.length() * 2);
    out.append('^');
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (c == '*') {
        boolean isDouble = (i + 1 < g.length()) && g.charAt(i + 1) == '*';
        if (isDouble) {
          // Special case: "**/" should match zero or more directories (gitignore-like).
          boolean hasSlash = (i + 2 < g.length()) && g.charAt(i + 2) == '/';
          if (hasSlash) {
            out.append("(?:.*/)?");
            i += 2; // Skip both '*' and the following '/'
          } else {
            out.append(".*");
            i++;
          }
        } else {
          out.append("[^/]*");
        }
        continue;
      }
      if (c == '?') {
        out.append("[^/]");
        continue;
      }
      // Escape regex meta characters.
      if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('$');
    return out.toString();
  }
}
