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

  /**
   * The two keys that describe an index's identity: {@code index_fingerprint} — the effective
   * physical shape, a mismatch on which means the bytes on disk cannot be what this runtime would
   * write — and {@code boosts_fp}, query-time scoring config that is worth reporting but never
   * worth a reindex.
   *
   * <p>Tempdoc 915 §C replaced five keys with these two. {@code schema_ver} tracked the
   * search-intent grammar version and could never fire; {@code index_schema_fp} hashed the catalog
   * <em>file</em>, so annotation-only edits demanded a reindex of a physically compatible index
   * (tempdoc 804); {@code analyzer_fp} and the vector dimension are now inputs to
   * {@code index_fingerprint} rather than separate keys; {@code similarity_fp} (BM25 k1/b) is
   * query-time and was demoted to plain observability.
   */
  public static final Set<String> PARITY_KEYS =
      Set.of(io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY, "boosts_fp");

  /**
   * Parity keys whose mismatch means the on-disk index <em>content</em> was built with a different
   * physical shape than this runtime produces — so the only correct response is to rebuild. A
   * mismatch here is routed into {@code SCHEMA_MISMATCH}, which under the production default
   * {@code BLUE_GREEN_MIGRATE} builds a Green generation while Blue keeps serving reads.
   */
  public static final Set<String> REBUILD_REQUIRING_KEYS =
      Set.of(io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY);

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

  /**
   * Hint used when the index predates {@code index_fingerprint} entirely. Named rather than folded
   * into the generic mismatch hint so the log line and the status surface can say WHY the migration
   * started: not "your shape changed" but "this index was built before the shape was recorded".
   */
  public static final String LEGACY_INDEX_HINT =
      "legacy-index-without-fingerprint: this index predates index_fingerprint, so its physical"
          + " shape cannot be verified. Rebuilding once records it.";

  private static final Map<String, String> PARITY_HINTS =
      Map.of(
          io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY,
              "The effective index shape changed (field catalog, analyzers, vector format/dimension,"
                  + " HNSW build params, chunking, or an embedding/SPLADE model). Reindex or run"
                  + " schema migration.",
          "boosts_fp", "Align `index.boosts` configuration with committed SSOT metadata.");

  public static List<Diff> diff(Map<String, String> stored, Map<String, Object> expected) {
    List<Diff> diffs = new ArrayList<>();
    for (String key : PARITY_KEYS) {
      String storedRaw = asString(stored == null ? null : stored.get(key));
      String expectedRaw = asString(expected == null ? null : expected.get(key));
      // A blank EXPECTED means this runtime could not compute a truthful fingerprint (a
      // configured model digest was unreadable). That is an unanswered question, not a difference,
      // and spending a full rebuild on an absence of evidence would be the destructive reading.
      // The guard logs which input went unresolved rather than going silent.
      if (isBlank(expectedRaw)) {
        continue;
      }
      // A blank STORED on a rebuild-requiring key is different, and this is the case the first cut
      // of this change got wrong: every index built before this key existed has a blank stored side
      // forever, so skipping it left the guard permanently inert on exactly the installs it was
      // meant to protect. An index whose physical shape was never recorded cannot be shown to match
      // this runtime, so it is treated as a mismatch and migrated once — the deliberate one-time
      // upgrade rebuild the wave-2 release is built around. Benign keys still skip: an unverifiable
      // boosts_fp is not worth reporting, let alone acting on.
      if (isBlank(storedRaw)) {
        if (!REBUILD_REQUIRING_KEYS.contains(key)) {
          continue;
        }
        diffs.add(new Diff(key, stringify(storedRaw), stringify(expectedRaw), LEGACY_INDEX_HINT));
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

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
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
