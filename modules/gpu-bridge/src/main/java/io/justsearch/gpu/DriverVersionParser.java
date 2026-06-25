/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu;

/**
 * Parses an NVIDIA driver-version string ({@code "591.59"}, {@code "535.183.01"}) into its
 * leading {@code major.minor} integer pair.
 *
 * <p>Both the NVML probe ({@link NvmlService}) and the nvidia-smi fallback
 * ({@link GpuCapabilitiesService}) read the same driver-version string format, so the parse
 * lives here once rather than as two byte-identical private copies that could drift. The
 * accepted shape is {@code <digits>.<digits>[.<anything>]}: the first dot splits major from
 * minor, any trailing suffix (a third {@code .NN} build component, whitespace, etc.) is
 * ignored.
 */
final class DriverVersionParser {

  private DriverVersionParser() {}

  /**
   * Parses {@code raw} into {@code [major, minor]}.
   *
   * @param raw the driver-version string, possibly null/blank/malformed
   * @return a two-element {@code int[]} of {@code [major, minor]}, or {@code null} if {@code raw}
   *     is null, blank, has no interior dot, or has no parseable major/minor components
   */
  static int[] parse(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isBlank()) {
      return null;
    }
    // Accept "591.59" (or any N.N form), ignore any suffix.
    int dot = s.indexOf('.');
    if (dot <= 0 || dot == s.length() - 1) {
      return null;
    }
    String a = s.substring(0, dot);
    int end = dot + 1;
    while (end < s.length()) {
      char c = s.charAt(end);
      if (c < '0' || c > '9') {
        break;
      }
      end++;
    }
    String b = s.substring(dot + 1, end);
    try {
      return new int[] {Integer.parseInt(a), Integer.parseInt(b)};
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
