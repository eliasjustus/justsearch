/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.asBoolean;
import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.asString;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates document validation, backpressure, and serialization of all mutating index ops.
 *
 * <p>Tempdoc 402: this is the <b>sole entry point for Lucene mutation</b>. Every mutating method
 * acquires {@link #dispatchLock} before delegating to {@link WritePathOps}, enforcing the
 * single-writer invariant that eliminates the concurrent-RMW lost-update race (393 § 1.4).
 * {@code WritePathOps} mutating methods are package-private; external callers must route through
 * this coordinator, and the lock guarantees at most one mutation is in flight at any moment.
 *
 * <p>Serialization is coarse — one global lock serializes RMW, ingest, and delete regardless of
 * {@code docId}. This was a deliberate 402 trade-off: simplicity over per-docId parallelism at
 * the current single-node concurrency scale. Upgrading to striped locking would preserve
 * different-docId parallelism if benchmarks ever demand it.
 *
 * <p>The lock is a {@link ReentrantLock}, so nested calls from the same thread don't deadlock.
 * Backpressure ({@link #guardBackpressure}) operates <em>outside</em> the lock so load-shedding
 * can reject callers without serializing on the writer path.
 */
public final class IndexingCoordinator {
  private static final Logger log = LoggerFactory.getLogger(IndexingCoordinator.class);

  private final RuntimeSession session;
  private final Supplier<WritePathOps> writeOps;

  /**
   * Single serialization primitive for all mutating ops. Reentrant so same-thread nested calls
   * (e.g. a convenience method invoked from inside a lifecycle callback) don't deadlock.
   */
  private final ReentrantLock dispatchLock = new ReentrantLock();

  IndexingCoordinator(RuntimeSession session, Supplier<WritePathOps> writeOps) {
    this.session = session;
    this.writeOps = writeOps;
  }

  /**
   * Tempdoc 406 observability — acquires the writeBarrier readLock and emits the
   * blocked-time as an {@code onWriteBarrierContention} event when telemetry is
   * wired. Negligible overhead when telemetry is null (one volatile read +
   * branch); two {@code System.nanoTime()} calls when wired.
   */
  private void acquireReadLockTimed() {
    LuceneRuntimeTypes.TelemetryEvents events = session.telemetryEvents;
    if (events == null) {
      session.writeBarrier.readLock().lock();
      return;
    }
    long start = System.nanoTime();
    session.writeBarrier.readLock().lock();
    events.onWriteBarrierContention(System.nanoTime() - start);
  }

  // ==========================================================================
  // Tempdoc 402: write-op dispatch
  // ==========================================================================

  /**
   * Synchronous op dispatch serialized against concurrent callers via {@link #dispatchLock}.
   * Used by the convenience mirrors ({@link #updateDocument(String, Map, boolean)} etc.) so
   * synchronous RMW semantics match the pre-402 call shape. Also the correct choice for
   * lifecycle paths (reset) where no dispatcher loop is running.
   */
  public <O extends IndexWriteOperation> O executeNow(O op) {
    acquireReadLockTimed();
    try {
      dispatchLock.lock();
      try {
        dispatch(op);
      } finally {
        dispatchLock.unlock();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
    return op;
  }

  /** Dispatches a single operation. Exceptions complete the op's future exceptionally. */
  private void dispatch(IndexWriteOperation op) {
    try {
      switch (op) {
        case IndexWriteOperation.UpdateDoc u ->
            u.completion()
                .complete(writeOps.get().updateDocument(u.docId(), u.updates(), u.preserveSplade()));
        case IndexWriteOperation.BatchUpdate b ->
            b.completion()
                .complete(writeOps.get().updateDocumentsBatch(b.batchUpdates(), b.preserveSplade()));
        case IndexWriteOperation.DeleteAll d -> {
          writeOps.get().deleteAll();
          d.completion().complete(null);
        }
      }
    } catch (Throwable t) {
      op.completeExceptionally(t);
    }
  }

  // ==========================================================================
  // Tempdoc 402: convenience mirror methods — same signatures as WritePathOps so callers
  // migrate by swapping the receiver. Each acquires dispatchLock via executeNow.
  // ==========================================================================

  public boolean updateDocument(String docId, Map<String, Object> updates) {
    return updateDocument(docId, updates, false);
  }

  public boolean updateDocument(String docId, Map<String, Object> updates, boolean preserveSplade) {
    if (docId == null || docId.isBlank() || updates == null || updates.isEmpty()) {
      return false;
    }
    var op = IndexWriteOperation.UpdateDoc.of(docId, updates, preserveSplade);
    executeNow(op);
    return joinOrUnwrap(op.completion());
  }

  public BatchUpdateResult updateDocumentsBatch(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates) {
    return updateDocumentsBatch(batchUpdates, false);
  }

  public BatchUpdateResult updateDocumentsBatch(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates, boolean preserveSplade) {
    if (batchUpdates == null || batchUpdates.isEmpty()) {
      return new BatchUpdateResult(0, 0);
    }
    var op = IndexWriteOperation.BatchUpdate.of(batchUpdates, preserveSplade);
    executeNow(op);
    return joinOrUnwrap(op.completion());
  }

  /** Pass-through convenience. Direct delete, no RMW envelope — takes {@link #dispatchLock}. */
  public void deleteByPathPrefix(String pathPrefix) {
    acquireReadLockTimed();
    try {
      dispatchLock.lock();
      try {
        writeOps.get().deleteByPathPrefix(pathPrefix);
      } finally {
        dispatchLock.unlock();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  /** Pass-through convenience. Takes {@link #dispatchLock} to serialize with RMW ops. */
  public int updateDocumentPaths(String oldPath, String newPath) {
    acquireReadLockTimed();
    try {
      dispatchLock.lock();
      try {
        return writeOps.get().updateDocumentPaths(oldPath, newPath);
      } finally {
        dispatchLock.unlock();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  /** Pass-through convenience. Wipes the index; used by reset. */
  public void deleteAll() {
    var op = IndexWriteOperation.DeleteAll.of();
    executeNow(op);
    joinOrUnwrap(op.completion());
  }

  /**
   * Joins a completion future, unwrapping {@link java.util.concurrent.ExecutionException} so
   * callers see the underlying runtime exception shape they had before the coordinator migration.
   */
  private static <T> T joinOrUnwrap(CompletableFuture<T> cf) {
    try {
      return cf.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted awaiting coordinator dispatch", e);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) throw re;
      if (cause instanceof Error err) throw err;
      throw new RuntimeException(cause);
    }
  }

  // ==========================================================================
  // Pre-existing mutating methods — tempdoc 402 Fix B wrapped each writeOps call in
  // dispatchLock to close the single-writer invariant across ingest + delete paths.
  // ==========================================================================

  /** Validates a document against schema requirements and configured validation mode. */
  void validate(IndexDocument document) {
    Map<String, Object> fields = document.fields();
    String idField = session.fieldMapper.idField();
    ValidationMode mode = session.validationMode;
    String idValue = fields == null ? null : asString(fields.get(idField));
    if (idValue == null || idValue.isBlank()) {
      notifyValidationFailure(ValidationReason.MISSING_ID_FIELD);
      if (mode == ValidationMode.FAIL) {
        throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.CONFIGURATION, "missing_id_field", null);
      } else {
        log.warn("Validation warn: missing_id_field");
      }
    }
    String uidValue = fields == null ? null : asString(fields.get(SchemaFields.DOC_UID));
    if (uidValue == null || uidValue.isBlank()) {
      notifyValidationFailure(ValidationReason.MISSING_UID_FIELD);
      if (mode == ValidationMode.FAIL) {
        throw new IndexRuntimeIOException(
            IndexRuntimeIOException.Reason.CONFIGURATION, "missing_uid_field", null);
      } else {
        log.warn("Validation warn: missing_uid_field");
      }
    }
    // Vector dimension check: ensure provided vector matches catalog dimension if present
    FieldMapper.FieldDef vecDef = session.fieldMapper.fieldDef("vector");
    if (vecDef != null && fields.containsKey("vector") && vecDef.vectorDim != null) {
      Object v = fields.get("vector");
      int actualDim = -1;
      boolean numeric = true;
      if (v instanceof float[] fa) {
        actualDim = fa.length;
      } else if (v instanceof double[] da) {
        actualDim = da.length;
      } else if (v instanceof List<?> list) {
        actualDim = list.size();
        numeric = list.stream().allMatch(e -> e instanceof Number);
      } else if (v != null) {
        numeric = false;
      }
      if (!numeric) {
        notifyValidationFailure(ValidationReason.VECTOR_NOT_NUMERIC);
        if (mode == ValidationMode.FAIL) {
          throw new IndexRuntimeIOException(
              IndexRuntimeIOException.Reason.CONFIGURATION, "vector_not_numeric", null);
        } else {
          log.warn("Validation warn: vector_not_numeric");
          return;
        }
      }
      if (actualDim > -1 && actualDim != vecDef.vectorDim) {
        notifyValidationFailure(ValidationReason.VECTOR_DIMENSION_MISMATCH);
        if (mode == ValidationMode.FAIL) {
          throw new IndexRuntimeIOException(
              IndexRuntimeIOException.Reason.CONFIGURATION, "vector_dimension_mismatch", null);
        } else {
          log.warn(
              "Validation warn: vector_dimension_mismatch (expected {}, got {})",
              vecDef.vectorDim,
              actualDim);
        }
      }
    }
  }

  /** Indexes a single document with backpressure enforcement and queue accounting. */
  public void indexSingle(IndexDocument document) {
    acquireReadLockTimed();
    try {
      guardBackpressure();
      try {
        validate(document);
        Map<String, Object> fields = document.fields();
        if (fields == null) {
          throw new IllegalArgumentException("IndexDocument.fields() must not be null");
        }
        dispatchLock.lock();
        try {
          writeOps.get().indexDocument(fields);
        } finally {
          dispatchLock.unlock();
        }
        session.pendingDocs.incrementAndGet();
      } finally {
        session.queueDepth.decrementAndGet();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  /**
   * Indexes a batch of documents with soft-delete/hard-delete routing.
   *
   * <p>Validation is performed inside the try block to ensure queue depth is always decremented
   * even if validation fails (fixes queue depth leak in FAIL mode).
   */
  public void indexBatch(List<IndexDocument> documents) {
    acquireReadLockTimed();
    try {
      String idField = session.fieldMapper.idField();
      guardBackpressure(documents.size());
      try {
        for (IndexDocument doc : documents) {
          validate(doc);
        }
        List<WritePathOps.DocWork> softDeletes = new ArrayList<>();
        List<WritePathOps.DocWork> updates = new ArrayList<>();
        List<String> hardDeletes = new ArrayList<>();
        for (IndexDocument doc : documents) {
          Map<String, Object> fields = doc.fields();
          if (fields == null) {
            throw new IllegalArgumentException("IndexDocument.fields() must not be null");
          }
          String idValue = asString(fields.get(idField));
          if (idValue == null || idValue.isBlank()) {
            throw new IllegalArgumentException(
                "IndexDocument missing required id field " + idField);
          }
          boolean hardDelete = asBoolean(fields.get(SchemaFields.HARD_DELETE));
          if (hardDelete) {
            hardDeletes.add(idValue);
            continue;
          }
          Document luceneDoc = session.fieldMapper.toDocument(fields);
          boolean softDelete = asBoolean(fields.get(session.softDeleteField));
          if (softDelete) {
            softDeletes.add(new WritePathOps.DocWork(idValue, luceneDoc, fields));
          } else {
            updates.add(new WritePathOps.DocWork(idValue, luceneDoc, fields));
          }
        }
        dispatchLock.lock();
        try {
          writeOps.get().applyBatch(updates, softDeletes, hardDeletes);
        } finally {
          dispatchLock.unlock();
        }
        session.pendingDocs.addAndGet(documents.size());
        TelemetryEvents events = session.telemetryEvents;
        if (events != null) {
          if (!hardDeletes.isEmpty()) events.onHardDelete(hardDeletes.size());
          int softCount = softDeletes.size();
          if (softCount > 0 && session.softDeletesMetrics == null) {
            events.onSoftDelete(softCount);
          }
        }
      } finally {
        session.queueDepth.addAndGet(-documents.size());
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  public void deleteById(String id) {
    acquireReadLockTimed();
    try {
      guardBackpressure();
      try {
        dispatchLock.lock();
        try {
          writeOps.get().deleteById(id);
        } finally {
          dispatchLock.unlock();
        }
      } finally {
        session.queueDepth.decrementAndGet();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  public void deleteByIdAndChunks(String parentDocId) {
    acquireReadLockTimed();
    try {
      guardBackpressure();
      try {
        dispatchLock.lock();
        try {
          writeOps.get().deleteByIdAndChunks(parentDocId);
        } finally {
          dispatchLock.unlock();
        }
      } finally {
        session.queueDepth.decrementAndGet();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  public void deleteChunksForParentDocId(String parentDocId) {
    acquireReadLockTimed();
    try {
      guardBackpressure();
      try {
        dispatchLock.lock();
        try {
          writeOps.get().deleteChunksForParentDocId(parentDocId);
        } finally {
          dispatchLock.unlock();
        }
      } finally {
        session.queueDepth.decrementAndGet();
      }
    } finally {
      session.writeBarrier.readLock().unlock();
    }
  }

  void guardBackpressure() {
    guardBackpressure(1);
  }

  private void guardBackpressure(int delta) {
    // Tempdoc 406 Gap G: reject writes during drain. Caller should retry on the upgraded
    // holder reference (UNAVAILABLE on the gRPC layer); see RunningRuntime.drainAndClose.
    if (session.draining) {
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.DRAINING,
          "runtime_draining",
          null);
    }
    long depth = session.queueDepth.addAndGet(delta);
    if (depth > session.maxQueueDepth) {
      session.queueDepth.addAndGet(-delta);
      TelemetryEvents events = session.telemetryEvents;
      if (events != null) events.onBackpressure();
      throw new IndexRuntimeIOException(
          IndexRuntimeIOException.Reason.BACKPRESSURE, "queue_depth_exceeded", null);
    }
  }

  private void notifyValidationFailure(ValidationReason reason) {
    TelemetryEvents events = session.telemetryEvents;
    if (events != null) {
      events.onValidationFailure(reason);
    }
  }
}
