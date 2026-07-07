/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Expected-vs-actual llama-server build comparison (tempdoc 682 Item 2).
 *
 * <p>The staged llama-server build is pinned at staging time by a machine-readable
 * {@code runtime-version.txt} marker written next to the binary (the existing marker
 * convention — {@code stageLlamaServerFromPrebuilt} / {@code stageLlamaCudaVariant} in
 * {@code modules/ui/build.gradle.kts}; restored by {@code RuntimeRestoreUtil}). The
 * actually-running build is reported by llama-server's {@code GET /props} response in
 * its {@code build_info} field (e.g. {@code "b8571-089d1e0e"}).
 *
 * <p>Both sides normalize to the upstream release build tag ({@code bNNNN}); comparison
 * is exact-match on that tag and unknown-tolerant: a missing marker (externally-started
 * or adopted server, dev setups) or a {@code /props} without {@code build_info} yields
 * {@code UNKNOWN}, which is a supported state — not a warning.
 */
final class LlamaServerBuildCheck {

  /** Marker file adjacent to llama-server.exe; same name across all staging paths. */
  static final String RUNTIME_VERSION_FILE = "runtime-version.txt";

  /** Upstream llama.cpp release build tag, e.g. {@code b8571}. */
  private static final Pattern BUILD_TAG = Pattern.compile("\\bb\\d+\\b");

  private LlamaServerBuildCheck() {}

  /** Comparison verdict: both tags (nullable = unknown) plus the mismatch flag. */
  record BuildComparison(String expected, String actual, boolean mismatch) {}

  /**
   * Exact-match, unknown-tolerant comparison. {@code mismatch} is true only when BOTH
   * tags are known and differ; either side unknown means "cannot assert drift", never
   * a mismatch.
   */
  static BuildComparison compare(String expectedTag, String actualTag) {
    boolean mismatch =
        expectedTag != null && actualTag != null && !expectedTag.equals(actualTag);
    return new BuildComparison(expectedTag, actualTag, mismatch);
  }

  /**
   * Parses the expected build tag out of marker text such as
   * {@code "llama.cpp b8571 win-cuda-12.4-x64"} or {@code "llama.cpp b8571 prebuilt"}.
   *
   * @return the {@code bNNNN} tag, or null when the text is null/blank/unparseable
   */
  static String expectedFromMarker(String markerText) {
    return extractBuildTag(markerText);
  }

  /**
   * Parses the actual build tag out of a llama-server {@code /props} response's
   * {@code build_info} field (format {@code "bNNNN-<commit>"}).
   *
   * @return the {@code bNNNN} tag, or null when the field is absent/unparseable
   */
  static String actualFromProps(JsonNode propsRoot) {
    if (propsRoot == null) {
      return null;
    }
    JsonNode buildInfo = propsRoot.get("build_info");
    if (buildInfo == null || !buildInfo.isTextual()) {
      return null;
    }
    return extractBuildTag(buildInfo.asText());
  }

  /**
   * Best-effort read of the expected build tag from the {@code runtime-version.txt}
   * marker adjacent to the given llama-server executable. A missing or unreadable
   * marker returns null (expected=unknown — the supported externally-staged case).
   */
  static String readExpectedNextTo(Path serverExecutable) {
    if (serverExecutable == null) {
      return null;
    }
    Path dir = serverExecutable.getParent();
    if (dir == null) {
      return null;
    }
    Path marker = dir.resolve(RUNTIME_VERSION_FILE);
    try {
      if (!Files.isRegularFile(marker)) {
        return null;
      }
      return expectedFromMarker(Files.readString(marker, StandardCharsets.UTF_8));
    } catch (Exception e) {
      return null;
    }
  }

  private static String extractBuildTag(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Matcher m = BUILD_TAG.matcher(text);
    return m.find() ? m.group() : null;
  }
}
