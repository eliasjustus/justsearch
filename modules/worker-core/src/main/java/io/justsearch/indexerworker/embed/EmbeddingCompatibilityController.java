/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controls embedding/vector compatibility based on model fingerprint matching.
 *
 * <p>This controller implements the safety policy to prevent silent data corruption when
 * the embedding model changes while vectors exist in the index. It gates both embedding
 * writes (during indexing) and embedding queries (vector/hybrid search).
 *
 * <h2>States</h2>
 * <ul>
 *   <li><b>COMPATIBLE</b>: stored fingerprint == current fingerprint → allow everything</li>
 *   <li><b>BLOCKED_LEGACY</b>: stored fingerprint missing → block until forced reindex observed</li>
 *   <li><b>BLOCKED_MISMATCH</b>: stored fingerprint != current → block until forced reindex</li>
 *   <li><b>REBUILDING</b>: forced reindex triggered, waiting for completion</li>
 *   <li><b>UNAVAILABLE</b>: no current model available → embeddings unavailable</li>
 * </ul>
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li>During BLOCKED_* states, embedding writes and vector/hybrid queries are blocked.</li>
 *   <li>When a forced reindex is observed, transition to REBUILDING.</li>
 *   <li>When rebuild completes (queue=0, pending_embedding=0), stamp fingerprint and transition to COMPATIBLE.</li>
 * </ul>
 */
public final class EmbeddingCompatibilityController {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingCompatibilityController.class);

  /** Commit metadata key for the embedding model fingerprint. */
  public static final String COMMIT_META_KEY = "embedding_model_sha256";

  /**
   * Compatibility state enum.
   */
  public enum State {
    /** Stored fingerprint matches current → allow embedding writes + vector/hybrid queries. */
    COMPATIBLE,
    /** No stored fingerprint (legacy index) → block until forced reindex. */
    BLOCKED_LEGACY,
    /** Stored fingerprint != current → block until forced reindex. */
    BLOCKED_MISMATCH,
    /** Forced reindex triggered, waiting for completion. */
    REBUILDING,
    /** No current embedding model available → embeddings unavailable. */
    UNAVAILABLE
  }

  private final Supplier<Map<String, String>> storedMetadataSupplier;
  private final LongSupplier docCountSupplier;
  private final AtomicReference<State> state = new AtomicReference<>(State.UNAVAILABLE);
  private final AtomicReference<String> currentFingerprint = new AtomicReference<>();
  private final AtomicReference<String> storedFingerprint = new AtomicReference<>();
  private final AtomicReference<String> reasonCode = new AtomicReference<>("INITIALIZING");
  private volatile boolean rebuildRequested = false;
  private volatile boolean rebuildCompleted = false;

  /**
   * Creates a new compatibility controller.
   *
   * @param storedMetadataSupplier supplier that returns the latest commit metadata from the index
   *                               (e.g., {@code LuceneLifecycleManager::latestCommitUserDataBestEffort})
   * @param docCountSupplier supplier for current index doc count (used to treat empty/new indexes as safe)
   */
  public EmbeddingCompatibilityController(
      Supplier<Map<String, String>> storedMetadataSupplier,
      LongSupplier docCountSupplier) {
    this.storedMetadataSupplier = Objects.requireNonNull(storedMetadataSupplier, "storedMetadataSupplier");
    this.docCountSupplier = Objects.requireNonNull(docCountSupplier, "docCountSupplier");
  }

  /**
   * Initializes/refreshes the compatibility state based on current vs stored fingerprints.
   *
   * <p>Call this at Worker startup after index is opened, and whenever the index is rebuilt.
   */
  public void refresh() {
    Optional<String> current = EmbeddingFingerprint.get();
    currentFingerprint.set(current.orElse(null));

    if (current.isEmpty()) {
      state.set(State.UNAVAILABLE);
      reasonCode.set("NO_EMBEDDING_MODEL");
      log.info("Embedding compatibility: UNAVAILABLE (no embedding model found)");
      return;
    }

    Map<String, String> stored = storedMetadataSupplier.get();
    String storedFp = stored == null ? null : stored.get(COMMIT_META_KEY);
    storedFingerprint.set(storedFp);

    if (storedFp == null || storedFp.isBlank()) {
      // If the index is empty (fresh install / new generation), it's safe to start writing vectors
      // and stamp the fingerprint on the first commit.
      long docs = safeDocCount();
      if (docs == 0L) {
        state.set(State.COMPATIBLE);
        reasonCode.set("NEW_INDEX_NO_FINGERPRINT");
        log.info("Embedding compatibility: COMPATIBLE (new/empty index; fingerprint will be stamped on commit)");
        return;
      }

      state.set(State.BLOCKED_LEGACY);
      reasonCode.set("LEGACY_INDEX_NO_FINGERPRINT");
      log.warn(
          "Embedding compatibility: BLOCKED_LEGACY (index has no embedding fingerprint; docCount={}). "
              + "Embedding writes and vector/hybrid queries are blocked until a forced reindex.",
          docs);
      return;
    }

    if (storedFp.equals(current.get())) {
      state.set(State.COMPATIBLE);
      reasonCode.set("FINGERPRINT_MATCH");
      rebuildCompleted = true; // Already compatible
      log.info("Embedding compatibility: COMPATIBLE (fingerprint matches: {}...)",
          storedFp.substring(0, Math.min(16, storedFp.length())));
      return;
    }

    state.set(State.BLOCKED_MISMATCH);
    reasonCode.set("FINGERPRINT_MISMATCH");
    log.warn("Embedding compatibility: BLOCKED_MISMATCH. "
        + "Stored: {}..., Current: {}... "
        + "Embedding writes and vector/hybrid queries are blocked until a forced reindex.",
        storedFp.substring(0, Math.min(16, storedFp.length())),
        current.get().substring(0, Math.min(16, current.get().length())));
  }

  /**
   * Called when a forced reindex is observed (any ingest batch with force_reindex=true).
   *
   * <p>This triggers transition to REBUILDING state if currently blocked.
   */
  public void onForcedReindexRequested() {
    rebuildRequested = true;
    State currentState = state.get();
    if (currentState == State.BLOCKED_LEGACY || currentState == State.BLOCKED_MISMATCH) {
      state.set(State.REBUILDING);
      reasonCode.set("REBUILD_IN_PROGRESS");
      rebuildCompleted = false;
      log.info("Embedding compatibility: transitioned to REBUILDING (forced reindex observed)");
    }
  }

  /**
   * Best-effort helper: auto-start an embedding rebuild for the common "indexed before embeddings were
   * available" case.
   *
   * <p>When an index has documents but no stored embedding fingerprint, we must normally block
   * embedding writes to avoid silently mixing incompatible vectors. However, when we can prove that
   * <b>all</b> documents are still pending embeddings (and none have completed/failed embeddings),
   * it is safe to proceed with a rebuild/backfill without requiring a user-initiated forced reindex.
   *
   * <p>This is intentionally conservative: it only triggers for {@link State#BLOCKED_LEGACY} with
   * {@code LEGACY_INDEX_NO_FINGERPRINT} and requires {@code pending == docCount}.
   *
   * @param docCount total docs in the index
   * @param pending count of docs with embedding_status=PENDING
   * @param completed count of docs with embedding_status=COMPLETED
   * @param failed count of docs with embedding_status=FAILED
   * @return true if the controller transitioned to REBUILDING
   */
  public boolean maybeAutoStartRebuildForLegacyAllPending(
      long docCount,
      int pending,
      int completed,
      int failed) {
    if (state.get() != State.BLOCKED_LEGACY) return false;
    if (!"LEGACY_INDEX_NO_FINGERPRINT".equals(reasonCode.get())) return false;
    if (docCount <= 0) return false;
    if (completed != 0 || failed != 0) return false;
    if ((long) pending != docCount) return false;

    log.info(
        "Embedding compatibility: auto-starting REBUILDING (legacy index, all embeddings pending). docCount={} pending={}",
        docCount,
        pending);
    onForcedReindexRequested();
    return state.get() == State.REBUILDING;
  }

  /**
   * Called to check if rebuild is complete. Call periodically or after queue drains.
   *
   * @param queueDepth current job queue depth
   * @param pendingEmbeddingCount count of documents with embedding_status=PENDING
   * @return true if rebuild just completed (fingerprint should be stamped)
   */
  public boolean checkRebuildCompletion(long queueDepth, int pendingEmbeddingCount) {
    if (state.get() != State.REBUILDING) {
      return false;
    }

    if (queueDepth == 0 && pendingEmbeddingCount == 0) {
      log.info("Embedding compatibility: rebuild complete (queue=0, pending_embedding=0)");
      rebuildCompleted = true;
      state.set(State.COMPATIBLE);
      reasonCode.set("REBUILD_COMPLETED");
      return true;
    }

    return false;
  }

  /**
   * Called after a successful commit that stamped the new fingerprint.
   * Finalizes the transition to COMPATIBLE.
   */
  public void onFingerprintStamped() {
    String fp = currentFingerprint.get();
    storedFingerprint.set(fp);
    state.set(State.COMPATIBLE);
    reasonCode.set("FINGERPRINT_MATCH");
    log.info("Embedding compatibility: fingerprint stamped, now COMPATIBLE ({}...)",
        fp == null ? "null" : fp.substring(0, Math.min(16, fp.length())));
  }

  // ===== Gates =====

  /**
   * Returns true if embedding writes are allowed.
   *
   * <p>Writes are allowed in COMPATIBLE and REBUILDING states.
   */
  public boolean allowEmbeddingWrites() {
    State s = state.get();
    return s == State.COMPATIBLE || s == State.REBUILDING;
  }

  /**
   * Returns true if vector/hybrid queries are allowed.
   *
   * <p>Queries are only allowed in COMPATIBLE state (not during REBUILDING).
   */
  public boolean allowQueryEmbeddings() {
    return state.get() == State.COMPATIBLE;
  }

  /**
   * Returns the fingerprint to stamp in commit metadata, if stamping is allowed.
   *
   * <p>Returns non-empty only when:
   * <ul>
   *   <li>State is COMPATIBLE (already matches), or</li>
   *   <li>State is REBUILDING and rebuild has completed</li>
   * </ul>
   */
  public Optional<String> fingerprintToStamp() {
    State s = state.get();
    if (s == State.COMPATIBLE || (s == State.REBUILDING && rebuildCompleted)) {
      String fp = currentFingerprint.get();
      if (fp != null) {
        storedFingerprint.set(fp);
        reasonCode.compareAndSet("NEW_INDEX_NO_FINGERPRINT", "FINGERPRINT_MATCH");
      }
      return Optional.ofNullable(fp);
    }
    return Optional.empty();
  }

  // ===== Accessors for status reporting =====

  /** Returns the current compatibility state. */
  public State state() {
    return state.get();
  }

  /** Returns the current embedding model fingerprint (or null if unavailable). */
  public String currentFingerprint() {
    return currentFingerprint.get();
  }

  /** Returns the stored embedding model fingerprint from index (or null if missing/legacy). */
  public String storedFingerprint() {
    return storedFingerprint.get();
  }

  /** Returns a stable reason code for the current state. */
  public String reasonCode() {
    return reasonCode.get();
  }

  /** Returns true if a forced reindex has been requested. */
  public boolean isRebuildRequested() {
    return rebuildRequested;
  }

  /**
   * Re-reads the stored fingerprint from Lucene commit metadata after a successful commit.
   *
   * <p>Call this after any non-rebuild commit to keep the cached {@code storedFingerprint}
   * in sync with what was actually persisted. On a fresh index, the first commit stamps
   * the fingerprint via {@link EmbeddingMetadataOverlay}, but the ECC is never notified
   * because {@link #onFingerprintStamped()} only fires on the REBUILDING completion path.
   */
  public void refreshStoredFingerprintAfterCommit() {
    try {
      Map<String, String> stored = storedMetadataSupplier.get();
      String fp = stored == null ? null : stored.get(COMMIT_META_KEY);
      if (fp != null && !fp.isBlank()) {
        String prev = storedFingerprint.get();
        if (!fp.equals(prev)) {
          storedFingerprint.set(fp);
          log.debug("ECC: refreshed storedFingerprint after commit ({}...)",
              fp.substring(0, Math.min(16, fp.length())));
        }
      }
    } catch (Exception e) {
      log.debug("ECC: failed to refresh storedFingerprint after commit: {}", e.getMessage());
    }
  }

  private long safeDocCount() {
    try {
      return Math.max(0L, docCountSupplier.getAsLong());
    } catch (Exception ignored) {
      return -1L;
    }
  }
}
