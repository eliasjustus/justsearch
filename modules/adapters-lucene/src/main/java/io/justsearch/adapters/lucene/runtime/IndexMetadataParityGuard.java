/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** File-system backed guard that validates shard parity before opening writers/readers. */
public final class IndexMetadataParityGuard implements IndexOpenGuard {
  private static final Logger log = LoggerFactory.getLogger(IndexMetadataParityGuard.class);
  private static final String ALLOW_MISMATCH_PROP = "justsearch.index.parity.allow_mismatch";

  private final Supplier<Path> indexPathSupplier;
  private final Supplier<Map<String, Object>> expectedMetadataSupplier;

  public IndexMetadataParityGuard(
      Supplier<Path> indexPathSupplier, Supplier<Map<String, Object>> expectedMetadataSupplier) {
    this.indexPathSupplier = Objects.requireNonNull(indexPathSupplier, "indexPathSupplier");
    this.expectedMetadataSupplier =
        Objects.requireNonNull(expectedMetadataSupplier, "expectedMetadataSupplier");
  }

  @Override
  public void checkOnOpen() {
    Path indexPath = indexPathSupplier.get();
    if (indexPath == null || !Files.exists(indexPath)) {
      return;
    }
    try (Directory directory = FSDirectory.open(indexPath)) {
      if (!DirectoryReader.indexExists(directory)) {
        return;
      }
      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        Map<String, String> stored = reader.getIndexCommit().getUserData();
        Map<String, Object> expected = expectedMetadataSupplier.get();
        var diffs = ParityDiagnostics.diff(stored, expected);
        if (diffs.isEmpty()) {
          return;
        }
        for (var diff : diffs) {
          log.warn(diff.marker());
        }
        if (allowMismatch()) {
          log.warn("Parity mismatch detected but {}=true; continuing in WARN mode.", ALLOW_MISMATCH_PROP);
          return;
        }
        // A mismatch on a rebuild-requiring key (analyzer_fp / index_schema_fp / schema_ver) means
        // the index content was built with different analysis/field-schema than the current SSOT
        // catalogs. Surface it as SCHEMA_MISMATCH so RuntimeSession's recovery wrapper rebuilds the
        // index (backup-first) on upgrade instead of crashing the worker — the same treatment a
        // field-schema change already gets (tempdoc 581 §13). Query-time-only keys (similarity_fp /
        // boosts_fp) do not require a reindex and stay read-only until the config is realigned.
        if (ParityDiagnostics.requiresRebuild(diffs)) {
          throw new IndexRuntimeIOException(
              IndexRuntimeIOException.Reason.SCHEMA_MISMATCH,
              "Index built with different analyzer/schema metadata than the current SSOT catalogs"
                  + " (parity mismatch on a rebuild-requiring key). Triggering schema-mismatch"
                  + " recovery to rebuild the index.",
              null);
        }
        throw new IllegalStateException("Shard is read-only due to parity mismatch");
      }
    } catch (IOException e) {
      // Re-classify Lucene corruption (CorruptIndexException, IndexFormatTooOldException,
      // IndexFormatTooNewException) as IndexRuntimeIOException(CORRUPT_INDEX). The recovery
      // wrapper in RuntimeSession catches IndexRuntimeIOException and triggers backup-rebuild;
      // raising ISE here would bypass recovery entirely (tempdoc 406 Gap D).
      if (isCorruption(e)) {
        throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.CORRUPT_INDEX,
            "Index corruption detected during parity inspection",
            e);
      }
      throw new IllegalStateException("Failed to inspect index metadata for parity", e);
    }
  }

  /**
   * True if {@code e} or any cause in its chain is a Lucene corruption exception
   * ({@link CorruptIndexException}, {@link IndexFormatTooOldException}, {@link
   * IndexFormatTooNewException}), or if {@link LuceneRuntimeUtils#classifyIOException}
   * maps it to {@code CORRUPT_INDEX} (covers {@code NoSuchFileException} on segment files).
   */
  private static boolean isCorruption(IOException e) {
    Throwable t = e;
    while (t != null) {
      if (t instanceof CorruptIndexException
          || t instanceof IndexFormatTooOldException
          || t instanceof IndexFormatTooNewException) {
        return true;
      }
      t = t.getCause();
    }
    return LuceneRuntimeUtils.classifyIOException(e)
        == IndexRuntimeIOException.Reason.CORRUPT_INDEX;
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
