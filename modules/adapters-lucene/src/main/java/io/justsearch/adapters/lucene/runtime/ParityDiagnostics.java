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
   *
   * <p>{@code index_fingerprint_inputs} is deliberately NOT a member. It is the canonical rendering
   * the digest hashes — the same statement, not a second one — so comparing it as a key of its own
   * would report a single shape change twice, once as a digest mismatch and once as a text
   * mismatch. It is read on exactly one path: {@link #diff} falls back to it when the EXPECTED
   * digest is uncomputable, and then compares only the inputs the unresolved model does not touch
   * (tempdoc 931 §C.5).
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
   * Hint used when the index carries no {@code index_fingerprint} at all. Named rather than folded
   * into the generic mismatch hint so the log line and the status surface can say WHY the migration
   * started: not "your shape changed" but "this index has no recorded shape to compare".
   *
   * <p>It deliberately does <em>not</em> assert that the index predates the key. The same absence
   * is produced when a commit was made while a configured model digest was unresolvable, and the
   * guard cannot tell the two apart from the commit alone — a hint that named a cause it cannot
   * know would be a confident guess in an operator-facing string.
   */
  public static final String LEGACY_INDEX_HINT =
      "index-without-fingerprint: this index carries no recorded index_fingerprint, so its"
          + " physical shape cannot be verified. Rebuilding once records it.";

  private static final Map<String, String> PARITY_HINTS =
      Map.of(
          io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY,
              "The effective index shape changed (field catalog, analyzers, vector format/dimension,"
                  + " HNSW build params, chunking, or an embedding/SPLADE model). Reindex or run"
                  + " schema migration.",
          "boosts_fp", "Align `index.boosts` configuration with committed SSOT metadata.");

  /**
   * The one predicate for "this index carries no recorded shape, and that matters". Both consumers
   * call it — the open-time guard here and {@code IndexStatusOps}'s reported compatibility state —
   * because two independently written versions of this rule is how a fresh install ends up being
   * told to rebuild an index that has nothing in it yet.
   *
   * <p>The {@code docCount} term is what excludes that case. An index with zero documents has no
   * content that could have been written under the wrong shape, so there is nothing to migrate:
   * the next commit stamps the fingerprint and the question answers itself. Only an index that
   * already holds documents whose shape was never recorded needs the one-time rebuild.
   */
  public static boolean isIndexWithoutRecordedFingerprint(
      String storedFingerprint, long docCount) {
    return isBlank(storedFingerprint) && !holdsNothingToMigrate(docCount);
  }

  /**
   * True when the index holds no documents, and therefore nothing whose physical shape could be
   * wrong — whatever it has stamped. {@code CommitOps.setLiveCommitData} replaces the whole
   * user-data map on every commit, so a stale fingerprint on an empty index is re-stamped by the
   * next commit rather than being a fact about any content.
   *
   * <p>Both branches below consult it, which is the fix for an asymmetry the first cut had: the
   * exclusion was applied only where the stored side was BLANK, so an index with zero documents and
   * a stale non-blank fingerprint still took the "changed" branch and spent a full blue/green
   * migration rebuilding nothing. {@code IndexStatusOps} reports through the same predicate, so the
   * guard and the status surface cannot disagree about an empty index.
   */
  public static boolean holdsNothingToMigrate(long docCount) {
    return docCount <= 0;
  }

  public static List<Diff> diff(
      Map<String, String> stored, Map<String, Object> expected, long docCount) {
    List<Diff> diffs = new ArrayList<>();
    diffs.addAll(determinateInputDiff(stored, expected, docCount));
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
      // boosts_fp is not worth reporting, let alone acting on. An EMPTY index is not migrated at
      // all — see isIndexWithoutRecordedFingerprint.
      if (isBlank(storedRaw)) {
        if (!REBUILD_REQUIRING_KEYS.contains(key)
            || !isIndexWithoutRecordedFingerprint(storedRaw, docCount)) {
          continue;
        }
        diffs.add(new Diff(key, stringify(storedRaw), stringify(expectedRaw), LEGACY_INDEX_HINT));
        continue;
      }
      if (!Objects.equals(storedRaw, expectedRaw)) {
        if (REBUILD_REQUIRING_KEYS.contains(key) && holdsNothingToMigrate(docCount)) {
          continue;
        }
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

  /**
   * True when the digest comparison is unavailable but the fallback one is: this runtime could not
   * compute an {@code index_fingerprint}, and BOTH sides recorded the canonical inputs.
   *
   * <p>Exposed so the guard's once-per-boot WARN can tell the operator which of the two things
   * happened. "Parity is not being checked" and "parity is being checked on everything except the
   * model digest I could not read" are different facts, and logging the first when the second is
   * true is the same class of untruth as declining silently.
   */
  public static boolean determinateInputComparisonAvailable(
      Map<String, String> stored, Map<String, Object> expected) {
    String expectedFingerprint =
        asString(
            expected == null
                ? null
                : expected.get(io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY));
    if (!isBlank(expectedFingerprint)) {
      return false;
    }
    String key = io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_INPUTS_KEY;
    return !isBlank(asString(stored == null ? null : stored.get(key)))
        && !isBlank(asString(expected == null ? null : expected.get(key)));
  }

  /**
   * The fallback comparison for an uncomputable expected fingerprint (tempdoc 931 §C.5).
   *
   * <p>An {@code INDETERMINATE} model digest means one input is unknown, not that every input is.
   * Before this, an unreadable NER model file switched off the vector-dimension, chunking and
   * analyzer comparison it has nothing to do with — an index built under a genuinely different
   * physical shape opened silently for as long as that file stayed unreadable. Here the unresolved
   * model keys are dropped from BOTH renderings and the remainder is compared; any difference is
   * routed as an {@code index_fingerprint} diff, so it takes the same exception, the same policy
   * branch and the same rebuild brake as a digest mismatch. No new reason code: it is the same
   * fact, established a different way.
   *
   * <p>Empty when the digest was computable (the digest is then the better answer and is compared
   * above), when either side recorded no inputs (a legacy commit keeps today's decline), or when
   * the index holds nothing whose shape could be wrong.
   */
  private static List<Diff> determinateInputDiff(
      Map<String, String> stored, Map<String, Object> expected, long docCount) {
    if (holdsNothingToMigrate(docCount)
        || !determinateInputComparisonAvailable(stored, expected)) {
      return List.of();
    }
    String key = io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_INPUTS_KEY;
    List<io.justsearch.adapters.lucene.commit.IndexFingerprint.InputDifference> differences =
        io.justsearch.adapters.lucene.commit.IndexFingerprint.differingInputs(
            asString(stored.get(key)),
            asString(expected.get(key)),
            io.justsearch.adapters.lucene.commit.IndexFingerprint.indeterminateModelInputs());
    if (differences.isEmpty()) {
      return List.of();
    }
    List<String> paths = differences.stream().map(d -> d.path()).toList();
    return List.of(
        new Diff(
            io.justsearch.adapters.lucene.commit.IndexFingerprint.COMMIT_META_KEY,
            summarize(differences, d -> d.stored()),
            summarize(differences, d -> d.expected()),
            "The effective index shape changed on an input this runtime CAN resolve, compared via"
                + " index_fingerprint_inputs because no index_fingerprint could be computed"
                + " (unresolved model inputs: "
                + io.justsearch.adapters.lucene.commit.IndexFingerprint.indeterminateModelInputs()
                + "). Differing inputs: "
                + paths
                + ". Reindex or run schema migration."));
  }

  /** At most three {@code path=value} pairs, so a log line stays a log line. */
  private static String summarize(
      List<io.justsearch.adapters.lucene.commit.IndexFingerprint.InputDifference> differences,
      java.util.function.Function<
              io.justsearch.adapters.lucene.commit.IndexFingerprint.InputDifference, String>
          side) {
    StringBuilder sb = new StringBuilder();
    int shown = Math.min(3, differences.size());
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(differences.get(i).path()).append('=').append(side.apply(differences.get(i)));
    }
    if (differences.size() > shown) {
      sb.append(" (+").append(differences.size() - shown).append(" more)");
    }
    return sb.toString();
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
