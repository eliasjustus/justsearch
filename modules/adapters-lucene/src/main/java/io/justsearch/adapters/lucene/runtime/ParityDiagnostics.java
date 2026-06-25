/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Utilities for comparing committed index metadata to the SSOT expectations. */
// PERMANENT COMPAT - DO NOT REMOVE (parity checks guard schema migration safety)
public final class ParityDiagnostics {
  private ParityDiagnostics() {}

  public static final Set<String> PARITY_KEYS =
      Set.of("analyzer_fp", "schema_ver", "similarity_fp", "boosts_fp", "index_schema_fp");

  /**
   * Parity keys whose mismatch means the on-disk index <em>content</em> was built with different
   * analysis or field schema than the current SSOT catalogs — so the only correct response is to
   * rebuild the index. A mismatch on one of these is routed into {@code SCHEMA_MISMATCH}
   * auto-recovery (backup-first rebuild) instead of marking the shard read-only, so an
   * analyzer/schema-catalog change migrates transparently on upgrade rather than crashing the
   * worker (tempdoc 581 §13). The remaining parity keys ({@code similarity_fp}, {@code boosts_fp})
   * are query-time scoring config that does <em>not</em> require reindexing, so they stay
   * read-only until the config is realigned.
   */
  public static final Set<String> REBUILD_REQUIRING_KEYS =
      Set.of("analyzer_fp", "schema_ver", "index_schema_fp");

  /**
   * True if any of the supplied diffs is on a {@link #REBUILD_REQUIRING_KEYS rebuild-requiring}
   * key, i.e. the index must be rebuilt rather than merely marked read-only.
   */
  public static boolean requiresRebuild(List<Diff> diffs) {
    for (Diff d : diffs) {
      if (REBUILD_REQUIRING_KEYS.contains(d.key())) {
        return true;
      }
    }
    return false;
  }

  private static final Map<String, String> PARITY_HINTS =
      Map.of(
          "analyzer_fp", "Regenerate analyzers via SSOT tools and rebuild the index.",
          "schema_ver", "Refresh SSOT schemas and rerun commit metadata generation.",
          "similarity_fp", "Align BM25 similarity settings in `config/application.yaml`.",
          "boosts_fp", "Align `index.boosts` configuration with committed SSOT metadata.",
          "index_schema_fp", "Index schema changed (field catalog/mapping). Reindex or run schema migration.");

  public static List<Diff> diff(Map<String, String> stored, Map<String, Object> expected) {
    List<Diff> diffs = new ArrayList<>();
    for (String key : PARITY_KEYS) {
      String storedRaw = asString(stored == null ? null : stored.get(key));
      String expectedRaw = asString(expected == null ? null : expected.get(key));
      // Back-compat: legacy indexes may not have newer parity keys stamped yet.
      // Treat missing stored values as "unknown" rather than a hard mismatch.
      if ("index_schema_fp".equals(key) && (storedRaw == null || storedRaw.isBlank())) {
        continue;
      }
      if (!Objects.equals(storedRaw, expectedRaw)) {
        diffs.add(
            new Diff(
                key,
                stringify(storedRaw),
                stringify(expectedRaw),
                PARITY_HINTS.getOrDefault(
                    key, "Review SSOT documentation and align commit metadata (see Ops runbook).")));
      }
    }
    return List.copyOf(diffs);
  }

  private static String asString(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof Number n) {
      return String.valueOf(n);
    }
    if (value instanceof Boolean b) {
      return b ? "true" : "false";
    }
    return String.valueOf(value);
  }

  private static String stringify(String value) {
    return value == null || value.isBlank() ? "<missing>" : value;
  }

  public static final class Diff {
    private final String key;
    private final String stored;
    private final String expected;
    private final String hint;

    Diff(String key, String stored, String expected, String hint) {
      this.key = key;
      this.stored = stored;
      this.expected = expected;
      this.hint = hint;
    }

    public String key() {
      return key;
    }

    public String marker() {
      return "PARITY_DIFF key="
          + key
          + " stored="
          + stored
          + " expected="
          + expected
          + " hint=\""
          + hint
          + "\"";
    }
  }
}
