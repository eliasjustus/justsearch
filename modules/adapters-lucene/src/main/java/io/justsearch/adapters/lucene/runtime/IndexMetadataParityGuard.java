/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** File-system backed guard that validates shard parity before opening writers/readers. */
public final class IndexMetadataParityGuard implements IndexOpenGuard {
  private static final Logger log = LoggerFactory.getLogger(IndexMetadataParityGuard.class);
  private static final String ALLOW_MISMATCH_PROP = "justsearch.index.parity.allow_mismatch";

  /**
   * One WARN per process, not one per index open: the cause is process-wide, and repeating it on
   * every generation open would bury it.
   */
  private static final java.util.concurrent.atomic.AtomicBoolean uncomputableWarned =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Resets the once-per-boot WARN latch. Test seam only: a process has exactly one boot, so no
   * production path calls this. Public rather than package-private because the only test that can
   * capture the log line lives in another module ({@code InvariantSuiteIT}, which has logback on
   * its classpath).
   */
  public static void resetUncomputableWarnedForTest() {
    uncomputableWarned.set(false);
    lastUnreadableCommitWarned.set("");
  }

  /**
   * The last directory whose commit could not be read. One unreadable index produces one line, not
   * one per reader of it: the pre-open check and the open-time guard both ask the same question of
   * the same bytes, so a corrupt index logged the same WARN twice per boot. Keyed on the path (not a
   * plain boolean) so a second, genuinely different unreadable generation still says so.
   */
  private static final java.util.concurrent.atomic.AtomicReference<String>
      lastUnreadableCommitWarned = new java.util.concurrent.atomic.AtomicReference<>("");

  private final Supplier<Path> indexPathSupplier;
  private final Supplier<Map<String, Object>> expectedMetadataSupplier;

  public IndexMetadataParityGuard(
      Supplier<Path> indexPathSupplier, Supplier<Map<String, Object>> expectedMetadataSupplier) {
    this.indexPathSupplier = Objects.requireNonNull(indexPathSupplier, "indexPathSupplier");
    this.expectedMetadataSupplier =
        Objects.requireNonNull(expectedMetadataSupplier, "expectedMetadataSupplier");
  }

  /**
   * Reads the last commit's user data straight off the directory and diffs it against {@code
   * expected}. No writer, no {@link RuntimeSession}, no open-mode choice — which is the point: the
   * question "does this index have the shape this runtime writes?" is a property of the bytes on
   * disk, and answering it inside the open path made the answer depend on HOW the index was being
   * opened. A deferred open is a read-only open, so {@link
   * io.justsearch.adapters.lucene.runtime.ComponentsFactory} logged the mismatch instead of raising
   * it, and the automatic migration never started on the boot path most installs take (tempdoc 915
   * §C.12, open item O7).
   *
   * <p>Returns an empty list when there is no index yet, so a first launch is silent.
   *
   * <p>{@code expected} is a SUPPLIER, and lazily: building the expected metadata hashes a catalog
   * and reads model digests, and on a directory with no commits there is nothing to compare it
   * against. {@code CommitMetadataIntegrationTest.metadataSourceSupplierInvokedPerBuild} pins the
   * count, which is how an eager version of this method was caught.
   *
   * @param indexPath the generation directory to inspect
   * @param expected the metadata this runtime would commit
   * @return the parity diffs, empty when there are none
   */
  public static java.util.List<ParityDiagnostics.Diff> inspectCommittedParity(
      Path indexPath, Supplier<Map<String, Object>> expected) {
    if (indexPath == null || !Files.exists(indexPath)) {
      return java.util.List.of();
    }
    try (Directory directory = FSDirectory.open(indexPath)) {
      if (!DirectoryReader.indexExists(directory)) {
        return java.util.List.of();
      }
      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        Map<String, String> stored = reader.getIndexCommit().getUserData();
        Map<String, Object> expectedMetadata = expected.get();
        warnIfFingerprintUncomputable(expectedMetadata);
        // numDocs, not maxDoc: an index whose every document is deleted has nothing left whose
        // shape could be wrong, and migrating it would rebuild emptiness.
        return ParityDiagnostics.diff(stored, expectedMetadata, reader.numDocs());
      }
    } catch (IOException e) {
      // NOT fatal, and not a mismatch. This method answers one question — "does the last commit
      // record the shape this runtime writes?" — and anything that stops it reading the commit
      // leaves that question UNANSWERED, which is a different thing from answering "no".
      //
      // Raising here was a real regression (tempdoc 915 B4): the pre-open call site sits outside
      // RuntimeSession.openComponentsWithRecovery, where backup-then-empty corruption recovery
      // lives, so a corrupt index that used to self-heal at boot killed the Worker instead. The
      // cause is an IndexFormatTooOldException for a genuinely older Lucene major, so it also
      // swallowed the legitimate format-upgrade path. CorruptIndexException,
      // IndexFormatTooOld/TooNew and IndexNotFoundException are all IOExceptions, so one catch
      // covers them; the open that follows classifies corruption itself (ComponentsFactory
      // classifies IOException on the reader/writer open as CORRUPT_INDEX) and routes it into the
      // recovery this method must not pre-empt.
      String key = indexPath + "|" + e.getClass().getSimpleName();
      if (!key.equals(lastUnreadableCommitWarned.getAndSet(key))) {
        log.warn(
            "Could not read committed parity metadata at {} ({}: {}). Treating the index shape as"
                + " unverified for now and letting the open decide — corruption recovery, a Lucene"
                + " format upgrade and the open-time guard all live there.",
            indexPath,
            e.getClass().getSimpleName(),
            e.getMessage());
      }
      return java.util.List.of();
    }
  }

  /**
   * The mismatch a rebuild-requiring diff means, as an exception. One message, whether it is raised
   * before the open (pre-open detection) or during it (this guard) — a difference in wording would
   * read as a difference in cause.
   */
  public static IndexRuntimeIOException schemaMismatch() {
    return new IndexRuntimeIOException(
        IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
        "Index was built with a different effective index shape than this runtime produces"
            + " (index_fingerprint mismatch). Triggering schema-mismatch recovery.",
        null);
  }

  @Override
  public void checkOnOpen() {
    var diffs = inspectCommittedParity(indexPathSupplier.get(), expectedMetadataSupplier);
    if (diffs.isEmpty()) {
      return;
    }
    for (var diff : diffs) {
      log.warn(diff.marker());
    }
    if (allowMismatch()) {
          // Operator escape hatch. Nothing sets this by default any more: the Head used to set it
          // unconditionally at two sites, which is why the guard never enforced anything for its
          // whole life (tempdoc 804, tempdoc 915 §C). It stays reachable so an operator can open a
          // known-divergent index read-only for diagnosis, and nothing else.
      log.warn("Parity mismatch detected but {}=true; continuing in WARN mode.", ALLOW_MISMATCH_PROP);
      return;
    }
    // A mismatch on index_fingerprint means the bytes on disk were written under a different
    // effective physical shape than this runtime produces. Surface it as SCHEMA_MISMATCH so the
    // recovery path acts on it. This is the SECOND line of defence: KnowledgeServer decides before
    // it chooses an open mode (tempdoc 915 §C.12), because a deferred open reaches here as
    // readOnly and ComponentsFactory only logs then. boosts_fp is query-time config — it stays
    // read-only until the config is realigned, never a reindex.
    if (ParityDiagnostics.requiresRebuild(diffs)) {
      throw schemaMismatch();
    }
    throw new IllegalStateException("Shard is read-only due to parity mismatch");
  }

  /**
   * When this runtime cannot compute an {@code index_fingerprint}, the parity check declines to
   * compare rather than declaring a mismatch. Declining silently would be indistinguishable from
   * passing, so say it once per boot and name the input that went unresolved.
   */
  private static void warnIfFingerprintUncomputable(Map<String, Object> expected) {
    Object fingerprint =
        expected == null ? null : expected.get(IndexFingerprint.COMMIT_META_KEY);
    boolean computable = fingerprint != null && !String.valueOf(fingerprint).isBlank();
    if (computable || !uncomputableWarned.compareAndSet(false, true)) {
      return;
    }
    var unresolved = IndexFingerprint.indeterminateModelInputs();
    log.warn(
        "Index parity is NOT being checked: this runtime could not compute an index_fingerprint"
            + " ({}). The index is opened without verifying its physical shape; fix the model"
            + " resolution to restore the check.",
        unresolved.isEmpty() ? "no model input could be resolved" : "unresolved: " + unresolved);
  }

  private static boolean allowMismatch() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs != null) {
      return cs.get().policy().indexParityAllowMismatch();
    }
    // Fallback for early startup paths before ConfigStore is initialized
    // (e.g., Worker subprocess started without a config snapshot).
    return Boolean.getBoolean(ALLOW_MISMATCH_PROP);
  }
}
