/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Map;

/**
 * Public API types extracted from {@link LuceneLifecycleManager} for better organization and
 * independent usage.
 *
 * <p>These types are used as parameters and return values for the Lucene runtime search and
 * indexing operations.
 */
public final class LuceneRuntimeTypes {

  private LuceneRuntimeTypes() {
    // Utility class - no instantiation
  }

  // ==========================================================================
  // Batch Update
  // ==========================================================================

  /**
   * Result of a batch read-modify-write operation via {@link
   * LuceneLifecycleManager#updateDocumentsBatch}.
   *
   * @param updatedCount documents successfully found and updated
   * @param notFoundCount document IDs not found in the index snapshot
   */
  public record BatchUpdateResult(int updatedCount, int notFoundCount) {}

  // ==========================================================================
  // Build State
  // ==========================================================================

  /** Migration-oriented commit marker for generation build verification. */
  public enum BuildState {
    BUILDING,
    COMPLETE
  }

  // ==========================================================================
  // Search Configuration
  // ==========================================================================

  /** Sort modes for interactive TEXT searches (used for pagination stability). */
  public enum RuntimeSearchSort {
    RELEVANCE("relevance"),
    MODIFIED_DESC("modified_desc"),
    MODIFIED_ASC("modified_asc"),
    SIZE_DESC("size_desc"),
    SIZE_ASC("size_asc"),
    PATH_ASC("path_asc"),
    PATH_DESC("path_desc");

    private final String key;

    RuntimeSearchSort(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

    public static RuntimeSearchSort fromKey(String key) {
      if (key == null || key.isBlank()) return null;
      String k = key.trim().toLowerCase(java.util.Locale.ROOT);
      for (RuntimeSearchSort s : values()) {
        if (s.key.equals(k)) return s;
      }
      return null;
    }
  }

  /** Query parsing modes for TEXT/HYBRID search queries. */
  public enum QuerySyntax {
    /** Treat user input as plain text (escape Lucene operators). */
    SIMPLE,
    /** Interpret user input as Lucene QueryParser syntax (phrases/boolean/field qualifiers). */
    LUCENE,
  }

  /** Filter spec for interactive file search (Worker-side). */
  @RecordBuilder
  public record RuntimeSearchFilters(
      List<String> mime,
      List<String> language,
      List<String> fileKind,
      List<String> mimeBase,
      String pathPrefix,
      Long modifiedFromMs,
      Long modifiedToMs,
      boolean includeChunks,
      List<String> entityPersons,
      List<String> entityOrganizations,
      List<String> entityLocations,
      List<String> metaSource,
      List<String> metaAuthor,
      List<String> metaCategory,
      Long metaPublishedFromMs,
      Long metaPublishedToMs,
      List<String> docIds,
      // Tempdoc 585 §D Phase 4 (D4b) — scope to Lucene collection tag(s) (e.g. "agent-history").
      List<String> collection) {}

  // ==========================================================================
  // Search Results
  // ==========================================================================

  // ==========================================================================
  // Typed per-hit provenance (tempdoc 549 Slice 3c, U2)
  //
  // Built at the orchestrator (SearchExecutor) from the typed pre-fusion leg
  // results, NOT reconstructed downstream from the stringly-typed debugScores
  // map. adapters-lucene carries no proto dependency, so these plain records
  // mirror the `io.justsearch.ipc.HitProvenance` leg shapes; worker-services
  // (which sees both) maps them to the proto. Nullable sub-records model the
  // proto's optional/`has*` leg semantics (leg absent == null).
  // ==========================================================================

  /** One retrieval leg's per-doc placement: 1-based rank + raw leg score. */
  public record RetrieverSignal(int rank, float rawScore) {}

  /** A fusion stage's per-doc fused score and the method that produced it ("rrf" | "cc"). */
  public record FusionSignal(float score, String method) {}

  /** The 3-leg chunk-fusion per-doc signal (ranks/scores per leg; optional CC fused score). */
  public record ChunkMergeSignal(
      int sparseRank,
      int denseRank,
      int spladeRank,
      float sparseScore,
      float denseScore,
      float spladeScore,
      Float ccScore) {}

  /** The whole-vs-chunk branch-fusion per-doc signal (optional fused score + method). */
  public record BranchFusionSignal(
      float wholeBranchScore, float chunkBranchScore, Float fusionScore, String method) {}

  /** Typed per-hit provenance: any leg may be null (it didn't run for this hit). */
  public record HitProvenanceSignals(
      RetrieverSignal bm25,
      RetrieverSignal splade,
      RetrieverSignal dense,
      FusionSignal fusion,
      ChunkMergeSignal chunkMerge,
      BranchFusionSignal branchFusion) {

    public static final HitProvenanceSignals EMPTY =
        new HitProvenanceSignals(null, null, null, null, null, null);

    public boolean isEmpty() {
      return bm25 == null
          && splade == null
          && dense == null
          && fusion == null
          && chunkMerge == null
          && branchFusion == null;
    }

    /** Returns a copy with the chunk-merge leg set, preserving the others. */
    public HitProvenanceSignals withChunkMerge(ChunkMergeSignal cm) {
      return new HitProvenanceSignals(bm25, splade, dense, fusion, cm, branchFusion);
    }

    /** Returns a copy with the branch-fusion leg set, preserving the others. */
    public HitProvenanceSignals withBranchFusion(BranchFusionSignal bf) {
      return new HitProvenanceSignals(bm25, splade, dense, fusion, chunkMerge, bf);
    }
  }

  /** A single search hit with optional debug scores and optional typed provenance. */
  public record SearchHit(
      String docId,
      float score,
      Map<String, String> fields,
      Map<String, Float> debugScores,
      HitProvenanceSignals provenance) {
    /** Constructor without typed provenance (provenance defaults to null). */
    public SearchHit(
        String docId, float score, Map<String, String> fields, Map<String, Float> debugScores) {
      this(docId, score, fields, debugScores, null);
    }

    /** Constructor without debug scores (backward compatible). */
    public SearchHit(String docId, float score, Map<String, String> fields) {
      this(docId, score, fields, Map.of(), null);
    }

    /** Returns a copy of this hit carrying the given typed provenance. */
    public SearchHit withProvenance(HitProvenanceSignals p) {
      return new SearchHit(docId, score, fields, debugScores, p);
    }
  }

  /** Search result containing hits and metadata (plus optional nextCursor for pagination). */
  public record SearchResult(List<SearchHit> hits, long totalHits, long tookMs, String nextCursor) {
    /** Backward-compatible constructor (no pagination cursor). */
    public SearchResult(List<SearchHit> hits, long totalHits, long tookMs) {
      this(hits, totalHits, tookMs, null);
    }
  }

  /**
   * Result of facet computation for a query (counts may be truncated by a safety cap).
   *
   * <p>Tempdoc 597: {@code matchedDocs} is the number of documents the scan iterated (the matched
   * population the facet values are tallied from). It is the true result-count "M" the headline binds
   * to — every per-value facet count is {@code <= matchedDocs} by construction, so the headline can
   * never read below a facet chip. Capped at the scan's {@code maxDocsScanned} (then {@code truncated}).
   */
  public record FacetsResult(
      Map<String, Map<String, Long>> facets, boolean truncated, long matchedDocs) {}

  /** Result of paginated corpus iteration (all doc IDs in the index). */
  public record ListAllDocumentIdsResult(
      List<String> docIds, long totalCount, long tookMs) {}

  /**
   * Low-level term statistics for a set of query terms, used to compute QPP signals.
   *
   * @param numDocs total non-deleted document count in the index
   * @param docFreqs per-term document frequency (number of docs containing the term)
   * @param termCollFreqs per-term collection frequency (total occurrences across all docs)
   * @param sumTotalTermFreq total term occurrences for the field across the whole collection
   */
  public record QppSignals(
      long numDocs,
      Map<String, Integer> docFreqs,
      Map<String, Long> termCollFreqs,
      long sumTotalTermFreq) {}

  // ==========================================================================
  // Folder Browse
  // ==========================================================================

  /** A single folder entry with aggregate metadata from indexed files. */
  public record FolderInfo(
      String path, String name, long fileCount, long totalSizeBytes, long lastIndexedAt) {}

  /** Result of folder enumeration under a parent path. */
  public record FolderBrowseResult(List<FolderInfo> folders, long tookMs, boolean truncated) {}

  /** Result of listing files directly within a folder. */
  public record FolderFilesResult(List<SearchHit> files, long totalCount, long tookMs) {}

  // ==========================================================================
  // Embedding Status
  // ==========================================================================

  /**
   * Counts of chunk documents by embedding status, used for RAG readiness checks.
   *
   * @param total total number of chunk documents
   * @param completed chunks with COMPLETED embedding status
   * @param pending chunks with PENDING embedding status
   * @param failed chunks with FAILED status
   */
  public record ChunkEmbeddingCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no chunks. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }

    /** True if coverage >= threshold (typically 95% for RAG readiness). */
    public boolean isReady(double thresholdPercent) {
      return total > 0 && coveragePercent() >= thresholdPercent;
    }
  }

  /**
   * Counts of whole documents by embedding status (doc-level vector embeddings).
   *
   * @param total total number of whole (non-chunk) documents
   * @param completed documents with COMPLETED embedding status
   * @param pending documents with PENDING embedding status
   * @param failed documents with FAILED embedding status
   */
  public record EmbeddingCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no documents. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }
  }

  /**
   * Counts of whole documents by SPLADE feature extraction status.
   *
   * @param total total number of whole (non-chunk) documents
   * @param completed documents with COMPLETED SPLADE status
   * @param pending documents with PENDING SPLADE status
   * @param failed documents with FAILED SPLADE status
   */
  public record SpladeFeatureCounts(int total, int completed, int pending, int failed) {
    /** Coverage percentage (0-100), or 0 if no documents. */
    public double coveragePercent() {
      return total > 0 ? (completed * 100.0) / total : 0.0;
    }
  }

  // ==========================================================================
  // Runtime Telemetry Hooks
  // ==========================================================================

  /**
   * Tempdoc 406 runtime gauge snapshot — atomic-read view of the per-session counters
   * exposed for status / observability consumers (e.g., {@code /api/status} via the
   * Worker's {@code IndexStatusOps}). Each field is a single volatile read taken
   * independently; consumers should treat this as a "best-effort consistent" view,
   * not a transactional snapshot.
   */
  public record RuntimeGaugesSnapshot(
      long writerQueueDepth,
      long writerPendingDocs,
      long commitCount,
      long refreshLagMs) {
    public static final RuntimeGaugesSnapshot EMPTY = new RuntimeGaugesSnapshot(0L, 0L, 0L, 0L);
  }

  /** Optional telemetry hooks. */
  public interface TelemetryEvents {
    default void onHardDelete() {}

    default void onHardDelete(int count) {}

    default void onSoftDelete(int count) {}

    default void onBackpressure() {}

    default void onCommit(long latencyMs) {}

    /** Commit with caller attribution. Default delegates to {@link #onCommit(long)}. */
    default void onCommit(long latencyMs, CommitReason reason) {
      onCommit(latencyMs);
    }

    default void onValidationFailure(ValidationReason reason) {}

    // ==========================================================================
    // Tempdoc 406 substrate observability — drain / swap / lock contention.
    // All default no-op; production exporter (WorkerLuceneTelemetryAdapter)
    // bridges to LocalTelemetry counters/timers/histograms.
    // ==========================================================================

    /**
     * Fired at the start of a holder swap or drain. {@code reason} identifies the call site;
     * see {@link SwapReason} for the bounded set of values.
     */
    default void onSwapStart(SwapReason reason) {}

    /**
     * Fired after the swap's old runtime is closed. {@code durationMs} covers the
     * full swap (drain + final commit + close).
     */
    default void onSwapComplete(long durationMs, SwapReason reason) {}

    /**
     * Fired when {@code RunningRuntime.drainAndClose} cannot acquire the
     * writeBarrier write-lock before the supplied timeout. {@code writesStillPending}
     * is the queueDepth at the timeout instant.
     */
    default void onDrainTimeout(long elapsedMs, long writesStillPending) {}

    /**
     * Fired on every readLock acquire on the writeBarrier in IndexingCoordinator.
     * {@code waitNanos} is the time spent blocked. Consumers histogram this; high
     * percentile values indicate either a swap in progress (writeLock held) or
     * write contention.
     */
    default void onWriteBarrierContention(long waitNanos) {}
  }

  public interface SoftDeletesMetrics {
    void onDocsKept(long count);

    void onDocsPurged(long count);
  }
}
