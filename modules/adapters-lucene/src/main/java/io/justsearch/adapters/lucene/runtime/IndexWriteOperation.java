/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Envelope for a single write operation dispatched by {@link IndexingCoordinator} (tempdoc 402).
 *
 * <p>Operations are submitted via {@link IndexingCoordinator#executeNow(IndexWriteOperation)}.
 * Each record carries the op-specific payload plus a typed {@link CompletableFuture} that the
 * dispatcher completes. The {@code Priority} tag is informational (low-cardinality telemetry
 * dimension); v1 dispatch does not use it for scheduling.
 */
public sealed interface IndexWriteOperation
    permits IndexWriteOperation.UpdateDoc,
        IndexWriteOperation.BatchUpdate,
        IndexWriteOperation.DeleteAll {

  Priority priority();

  /** Completes the operation's future exceptionally. */
  void completeExceptionally(Throwable error);

  enum Priority {
    ADMIN,
    BACKFILL,
    LIFECYCLE
  }

  /**
   * Single-document read-modify-write. Mirrors {@link WritePathOps#updateDocument(String, Map,
   * boolean)}; returns {@code true} iff the document was found and updated.
   */
  record UpdateDoc(
      String docId,
      Map<String, Object> updates,
      boolean preserveSplade,
      Priority priority,
      CompletableFuture<Boolean> completion)
      implements IndexWriteOperation {

    public UpdateDoc {
      Objects.requireNonNull(docId, "docId");
      Objects.requireNonNull(updates, "updates");
      Objects.requireNonNull(priority, "priority");
      Objects.requireNonNull(completion, "completion");
    }

    public static UpdateDoc of(String docId, Map<String, Object> updates) {
      return of(docId, updates, false);
    }

    public static UpdateDoc of(String docId, Map<String, Object> updates, boolean preserveSplade) {
      return new UpdateDoc(
          docId, updates, preserveSplade, Priority.BACKFILL, new CompletableFuture<>());
    }

    @Override
    public void completeExceptionally(Throwable error) {
      completion.completeExceptionally(error);
    }
  }

  /**
   * Batched read-modify-write. Mirrors {@link WritePathOps#updateDocumentsBatch(List, boolean)};
   * returns a {@link BatchUpdateResult} with found / updated counts.
   */
  record BatchUpdate(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates,
      boolean preserveSplade,
      Priority priority,
      CompletableFuture<BatchUpdateResult> completion)
      implements IndexWriteOperation {

    public BatchUpdate {
      Objects.requireNonNull(batchUpdates, "batchUpdates");
      Objects.requireNonNull(priority, "priority");
      Objects.requireNonNull(completion, "completion");
    }

    public static BatchUpdate of(List<Map.Entry<String, Map<String, Object>>> batchUpdates) {
      return of(batchUpdates, false);
    }

    public static BatchUpdate of(
        List<Map.Entry<String, Map<String, Object>>> batchUpdates, boolean preserveSplade) {
      return new BatchUpdate(
          batchUpdates, preserveSplade, Priority.BACKFILL, new CompletableFuture<>());
    }

    @Override
    public void completeExceptionally(Throwable error) {
      completion.completeExceptionally(error);
    }
  }

  /**
   * Wipes every document from the index (lifecycle op used by reset + profiling). Mirrors
   * {@link WritePathOps#deleteAll()}.
   */
  record DeleteAll(Priority priority, CompletableFuture<Void> completion)
      implements IndexWriteOperation {

    public DeleteAll {
      Objects.requireNonNull(priority, "priority");
      Objects.requireNonNull(completion, "completion");
    }

    public static DeleteAll of() {
      return new DeleteAll(Priority.LIFECYCLE, new CompletableFuture<>());
    }

    @Override
    public void completeExceptionally(Throwable error) {
      completion.completeExceptionally(error);
    }
  }
}
