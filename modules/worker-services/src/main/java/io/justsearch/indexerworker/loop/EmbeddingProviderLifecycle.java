/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexerworker.embed.EmbeddingTelemetryEvents;
import io.justsearch.indexerworker.embed.NoOpEmbeddingProvider;
import io.justsearch.indexerworker.embed.NoopEmbeddingTelemetryEvents;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the embedding-provider lifecycle: setters, change listeners, GPU handoff,
 * unload, the rebuild-finalize commit decision, and the post-commit
 * stored-fingerprint refresh.
 *
 * <p>Tempdoc 516 Slice 4c — extracted from {@link IndexingLoop} per Appendix A.1.
 * The 309 §33 regression history (single-Consumer slot dropped KnowledgeServer's
 * nulling on unload) is preserved by keeping the dual primary-slot + additional-
 * listener-list pattern. The {@link Object} lifecycle lock + the
 * {@code lastMainGpuActiveState} double-check pattern are preserved exactly so the
 * cross-process GPU handoff protocol from ADR-0004 keeps its semantics.
 *
 * <p>Cross-seam interactions, per Appendix A.6:
 * <ul>
 *   <li>{@link #tryFinalizeRebuild()} (constraint #11) makes the rebuild-stamp
 *       commit decision and fires the commit + ECC stamp, then returns true so the
 *       caller can reset its commit-driver bookkeeping
 *       ({@code lastCommitTime}, {@code indexedSinceCommit}). The lifecycle
 *       cannot mutate those fields directly — they belong to the loop residue.
 *   <li>{@link #refreshStoredFingerprintAfterCommit(CommitReason)} (constraint
 *       #12) is the {@link CommitOps.CommitCompletedListener} target; registered
 *       by {@code IndexingLoop} at construction so the three previously-scattered
 *       refresh call sites collapse into a single subscription.
 * </ul>
 *
 * <p>P5 boundary: a concrete class, not a strategy. Multi-listener semantics use a
 * single-slot primary + a {@link CopyOnWriteArrayList} of additionals (per the 309
 * §33 fix) — a closed pattern, not an extensible event bus.
 */
public final class EmbeddingProviderLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderLifecycle.class);

  private final WorkerSignalBus signalBus;
  private final JobQueue jobQueue;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;

  private volatile EmbeddingProvider embeddingProvider = NoOpEmbeddingProvider.INSTANCE;
  private volatile EmbeddingService embeddingServiceForLifecycle;
  private volatile EmbeddingTelemetryEvents embeddingEvents = NoopEmbeddingTelemetryEvents.INSTANCE;
  private volatile EmbeddingCompatibilityController embeddingCompatController;

  /**
   * GPU lifecycle mutex. Acquired by {@link #handleGpuStateTransition()} around the
   * double-check + unload sequence. Initial {@link #lastMainGpuActiveState} is
   * {@code false}: per 309 §33, the worker must start in "Main hasn't claimed GPU"
   * so no spurious RELOADING transition fires on startup.
   */
  private final Object embeddingLifecycleLock = new Object();
  private volatile boolean lastMainGpuActiveState = false;

  private volatile Consumer<EmbeddingProvider> embeddingProviderChangeListener;

  /** Tempdoc 518 Appendix F W4.3 — additional-listeners branch migrated to the shared
   *  substrate. The primary single-slot listener above keeps its volatile single-slot
   *  semantics (different shape per Appendix E §E.3's 2.5-of-3 framing). */
  private final io.justsearch.observable.ObservableNotifier<EmbeddingProvider>
      additionalChangeListeners =
          new io.justsearch.observable.ObservableNotifier<>(
              "EmbeddingProviderChangeListener");

  public EmbeddingProviderLifecycle(
      WorkerSignalBus signalBus,
      JobQueue jobQueue,
      IndexCountOps indexCountOps,
      CommitOps commitOps) {
    this.signalBus = signalBus;
    this.jobQueue = jobQueue;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
  }

  // ---- setters / accessors ----

  public void setEmbeddingProvider(EmbeddingProvider provider) {
    this.embeddingProvider = provider != null ? provider : NoOpEmbeddingProvider.INSTANCE;
    this.embeddingServiceForLifecycle = provider instanceof EmbeddingService es ? es : null;
  }

  public EmbeddingProvider embeddingProvider() {
    return embeddingProvider;
  }

  public void setEmbeddingTelemetryEvents(EmbeddingTelemetryEvents events) {
    this.embeddingEvents = events != null ? events : NoopEmbeddingTelemetryEvents.INSTANCE;
  }

  public void setEmbeddingCompatController(EmbeddingCompatibilityController controller) {
    this.embeddingCompatController = controller;
  }

  public EmbeddingCompatibilityController embeddingCompatController() {
    return embeddingCompatController;
  }

  public void setEmbeddingProviderChangeListener(Consumer<EmbeddingProvider> listener) {
    this.embeddingProviderChangeListener = listener;
  }

  public void addEmbeddingProviderChangeListener(Consumer<EmbeddingProvider> listener) {
    if (listener != null) {
      additionalChangeListeners.register(listener);
    }
  }

  // ---- gates ----

  /** Returns true iff embedding writes are currently permitted (per the ECC). */
  public boolean allowEmbeddingWrites() {
    var controller = embeddingCompatController;
    return controller == null || controller.allowEmbeddingWrites();
  }

  // ---- GPU handoff ----

  /**
   * Handles cross-process GPU state transitions (ADR-0004). When Main claims the GPU
   * the embedding model is unloaded if it uses VRAM; when Main releases, the
   * {@code SessionHandle}'s {@code releaseGpu()/acquire()} pair lazily reacquires on
   * next use — no reload here.
   *
   * <p>Tempdoc 397 §14.11 Stage 4b: no reload. Tempdoc 309 §33: initial state
   * preserved so no spurious RELOADING fires.
   */
  public void handleGpuStateTransition() {
    boolean currentGpuActiveState = signalBus.isMainGpuActive();
    if (currentGpuActiveState == lastMainGpuActiveState) {
      return;
    }
    synchronized (embeddingLifecycleLock) {
      if (currentGpuActiveState == lastMainGpuActiveState) {
        return;
      }
      if (currentGpuActiveState) {
        if (embeddingProvider.isUsingGpu()) {
          releaseEmbeddingGpuSession();
        } else {
          log.info("GPU transition: Main claimed GPU, but embeddings are CPU-only - continuing without unload");
        }
      } else {
        log.info(
            "GPU transition: Main released GPU — Worker will reclaim VRAM on next embed acquire");
      }
      lastMainGpuActiveState = currentGpuActiveState;
    }
  }

  /**
   * Tempdoc 598 R4: on the ADR-0004 GPU handoff (Main claims the GPU for the Online chat model) the
   * embedder yields its GPU session — freeing VRAM — but the {@link EmbeddingService} stays alive so
   * query embedding continues on the deferred CPU session. This replaces the former full
   * {@link #unloadEmbeddingService() unload-to-NoOp}, which made semantic search and RAG go dead the
   * moment chat came Online (598 PART I). The provider is NOT swapped to {@code NoOp} and listeners
   * are NOT notified, so the search path keeps issuing dense legs (now CPU-served) and AUTO
   * retrieval stays HYBRID instead of collapsing to keyword.
   *
   * <p>Bulk backfill stays paused regardless: {@code LoopPacingPolicy.shouldRunBackfill} keys off
   * {@code mainGpuActive} AND {@code isUsingGpu()} (the static config flag, still {@code true} after
   * the live GPU session is released), so the loop does not resume bulk embedding on the CPU. On the
   * falling edge (Main releases the GPU) the handle lazily re-creates its GPU session on the next
   * acquire — no reload here, exactly as before.
   *
   * <p>Package-private so tests can invoke directly.
   */
  void releaseEmbeddingGpuSession() {
    EmbeddingService svc = embeddingServiceForLifecycle;
    if (svc == null) {
      log.debug("GPU transition: No embedding service to release");
      return;
    }
    log.info(
        "GPU transition: RELEASING embedding GPU session, keeping CPU query path (Main claimed GPU"
            + " for Online Mode)");
    embeddingEvents.onUnload(EmbeddingTelemetryEvents.UnloadReason.GPU_HANDOFF);
    try {
      svc.releaseGpuSession();
    } catch (Exception e) {
      log.warn("GPU transition: Error releasing embedding GPU session", e);
    }
    log.info(
        "GPU transition: Embedding GPU session released, VRAM yielded; query-embed continues on"
            + " CPU");
  }

  /**
   * Full unload to {@code NoOp} (close + provider swap + notify). Retained for shutdown/test paths;
   * the live GPU handoff now uses {@link #releaseEmbeddingGpuSession()} (tempdoc 598 R4) so query
   * embedding survives Online. Package-private so tests can invoke directly via reflection.
   */
  void unloadEmbeddingService() {
    EmbeddingService svc = embeddingServiceForLifecycle;
    if (svc == null) {
      log.debug("GPU transition: No embedding service to unload");
      return;
    }
    log.info("GPU transition: UNLOADING embedding model (Main claimed GPU for Online Mode)");
    embeddingEvents.onUnload(EmbeddingTelemetryEvents.UnloadReason.GPU_HANDOFF);
    try {
      svc.close();
    } catch (Exception e) {
      log.warn("GPU transition: Error unloading embedding service", e);
    }
    embeddingProvider = NoOpEmbeddingProvider.INSTANCE;
    embeddingServiceForLifecycle = null;
    notifyEmbeddingProviderChange(NoOpEmbeddingProvider.INSTANCE);
    log.info("GPU transition: Embedding model unloaded, VRAM released");
  }

  private void notifyEmbeddingProviderChange(EmbeddingProvider provider) {
    Consumer<EmbeddingProvider> listener = embeddingProviderChangeListener;
    if (listener != null) {
      try {
        listener.accept(provider);
      } catch (Exception e) {
        log.warn("GPU transition: Embedding provider change listener failed", e);
      }
    }
    // Tempdoc 518 Appendix F W4.3 — substrate-driven dispatch. The notifier owns the
    // exception-swallow + log on each subscriber; we keep the WARN call-site message
    // shape so existing log scrapers and operator playbooks are unaffected.
    additionalChangeListeners.notifyAll(provider);
  }

  // ---- rebuild-finalize ----

  /**
   * Returns true iff the lifecycle has just issued the rebuild-stamp commit
   * (constraint #11). When true, the caller must reset its commit-driver
   * bookkeeping ({@code lastCommitTime}, {@code indexedSinceCommit}) and call
   * {@code metrics.recordCommit()}. Returning the decision (rather than mutating
   * those fields here) keeps the commit-driver counter a residue-only mutation.
   *
   * <p>This is the only point where an intentional empty commit is forced to
   * persist the updated fingerprint metadata.
   */
  public boolean tryFinalizeRebuild() {
    var controller = embeddingCompatController;
    if (controller == null) return false;
    if (controller.state() != EmbeddingCompatibilityController.State.REBUILDING) {
      return false;
    }

    long queueDepth;
    try {
      queueDepth = jobQueue.queueDepth();
    } catch (Exception e) {
      return false;
    }

    int pendingEmbeddings;
    try {
      pendingEmbeddings =
          indexCountOps.countByField(
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
    } catch (Exception e) {
      return false;
    }

    if (!controller.checkRebuildCompletion(queueDepth, pendingEmbeddings)) return false;

    try {
      commitOps.commitAndTrack(CommitReason.INDEXING_LOOP_REBUILD_STAMP);
      controller.onFingerprintStamped();
      return true;
    } catch (RuntimeException e) {
      log.error("Failed to commit embedding fingerprint after rebuild completion", e);
      return false;
    }
  }

  // ---- commit-completed listener target (constraint #12) ----

  /**
   * Target for {@link CommitOps.CommitCompletedListener} registration in
   * {@code IndexingLoop}'s constructor. Best-effort: syncs ECC's cached
   * {@code storedFingerprint} with what Lucene actually persisted. Replaces the
   * three previously-scattered {@code refreshEccStoredFingerprint} calls at
   * IndexingLoop's idle / time-buffer / shutdown commit sites.
   *
   * <p>Skipped for {@link CommitReason#INDEXING_LOOP_REBUILD_STAMP}: that path is the
   * rebuild-stamp commit driven by {@link #tryFinalizeRebuild()}, which calls
   * {@link EmbeddingCompatibilityController#onFingerprintStamped()} immediately after
   * the commit. {@code onFingerprintStamped} writes the new fingerprint directly into
   * ECC's stored field (and transitions state to {@code COMPATIBLE}). Calling
   * {@code refreshStoredFingerprintAfterCommit} too would re-read the same value from
   * Lucene's commit metadata — at best a redundant read, at worst a subtle ordering
   * dependency. Pre-Slice-4c the rebuild-stamp path never called refresh; preserve
   * that semantics.
   */
  public void refreshStoredFingerprintAfterCommit(CommitReason reason) {
    if (reason == CommitReason.INDEXING_LOOP_REBUILD_STAMP) {
      return;
    }
    var controller = embeddingCompatController;
    if (controller != null) {
      controller.refreshStoredFingerprintAfterCommit();
    }
  }
}
