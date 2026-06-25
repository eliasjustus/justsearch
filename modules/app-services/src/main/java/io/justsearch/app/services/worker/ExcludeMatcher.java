/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Path exclusion matcher used to filter watched-root sync batches on the Head.
 *
 * <p>Patterns are provided as a JSON string array via sysprop (UI settings mirror):
 * {@code justsearch.ui.exclude_patterns}.
 *
 * <p>This is intentionally lightweight and defensive: invalid JSON or invalid patterns result in an
 * empty matcher (no excludes), rather than failing indexing.
 */
final class ExcludeMatcher {
  private static final ObjectMapper EXCLUDE_JSON = new ObjectMapper();
  private static final TypeReference<List<String>> EXCLUDE_LIST = new TypeReference<>() {};

  private final boolean windows;
  private final List<Pattern> patterns;
  private final List<String> rawGlobs;

  private ExcludeMatcher(boolean windows, List<Pattern> patterns, List<String> rawGlobs) {
    this.windows = windows;
    this.patterns = patterns == null ? List.of() : List.copyOf(patterns);
    this.rawGlobs = rawGlobs == null ? List.of() : List.copyOf(rawGlobs);
  }

  static ExcludeMatcher empty(boolean windows) {
    return new ExcludeMatcher(windows, List.of(), List.of());
  }

  /**
   * Tempdoc 418 Phase B — exposes the cleaned glob strings so {@code RootLifecycleOps} can
   * forward them to the Worker-side ScanRoot RPC as {@code exclude_globs}. Worker applies the
   * same glob semantics inside {@code WorkerScanOps}.
   */
  List<String> patterns() {
    return rawGlobs;
  }

  static ExcludeMatcher fromSyspropJson(String rawJson, boolean windows) {
    if (rawJson == null || rawJson.isBlank()) {
      return empty(windows);
    }
    try {
      List<String> list = EXCLUDE_JSON.readValue(rawJson, EXCLUDE_LIST);
      return fromPatterns(list, windows);
    } catch (Exception e) {
      return empty(windows);
    }
  }

  static ExcludeMatcher fromPatterns(List<String> globs, boolean windows) {
    if (globs == null || globs.isEmpty()) {
      return empty(windows);
    }
    java.util.LinkedHashSet<String> cleaned = new java.util.LinkedHashSet<>();
    for (String g : globs) {
      if (g == null) continue;
      String s = g.trim();
      if (s.isBlank()) continue;
      s = s.replace('\\', '/');
      for (String expanded : expandBarePattern(s)) {
        cleaned.add(expanded);
        if (cleaned.size() >= 512) break;
      }
      if (cleaned.size() >= 512) break;
    }
    if (cleaned.isEmpty()) {
      return empty(windows);
    }
    int flags = windows ? Pattern.CASE_INSENSITIVE : 0;
    List<Pattern> compiled = new ArrayList<>();
    for (String glob : cleaned) {
      compiled.add(Pattern.compile(globToRegex(glob), flags));
    }
    return new ExcludeMatcher(windows, compiled, new ArrayList<>(cleaned));
  }

  boolean isEmpty() {
    return patterns.isEmpty();
  }

  private static boolean containsMeta(String s) {
    return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
  }

  static List<String> expandBarePattern(String pattern) {
    if (pattern.startsWith("**/")) {
      return List.of(pattern);
    }
    if (pattern.endsWith("/")) {
      String base = pattern.substring(0, pattern.length() - 1);
      if (!base.contains("/")) {
        return List.of("**/" + base + "/**");
      }
      return List.of(base + "/**");
    }
    if (!pattern.contains("/")) {
      if (containsMeta(pattern)) {
        return List.of("**/" + pattern);
      }
      return List.of("**/" + pattern + "/**", "**/" + pattern);
    }
    return List.of(pattern);
  }

  private static String globToRegex(String glob) {
    String g = glob == null ? "" : glob;
    StringBuilder out = new StringBuilder(g.length() * 2);
    out.append('^');
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (c == '*') {
        boolean isDouble = (i + 1 < g.length()) && g.charAt(i + 1) == '*';
        if (isDouble) {
          boolean hasSlash = (i + 2 < g.length()) && g.charAt(i + 2) == '/';
          if (hasSlash) {
            out.append("(?:.*/)?");
            i += 2;
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
      if ("\\\\.[]{}()+-^$|".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('$');
    return out.toString();
  }
}
