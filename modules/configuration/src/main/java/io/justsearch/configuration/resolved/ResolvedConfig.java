/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable snapshot of all resolved configuration.
 *
 * <p>Built at startup by {@link ResolvedConfigBuilder}, replacing all {@code System.getProperty}
 * reads and {@code EnvRegistry.get()} calls with typed accessor methods. When the user changes
 * settings at runtime (via the GUI), a new snapshot is built and atomically swapped in via {@code
 * ConfigStore}.
 *
 * <p>The resolution uses numeric ordinals (modeled on SmallRye): each source has a fixed ordinal,
 * higher ordinal wins. Every resolved value carries a {@link ConfigResolution} trace recording which
 * source won and all sources considered.
 *
 * <p>Sub-records group related configuration:
 *
 * <ul>
 *   <li>{@link Paths} — file system paths (data dir, index path, home, models, SSOT, repo root)
 *   <li>{@link Ports} — network ports (API, AI worker, llama-server)
 *   <li>{@link Ai} — AI/inference feature flags, GPU layers, model paths
 *   <li>{@link Llm} — LLM runtime tuning (sampling, VRAM, templates, deadlines)
 *   <li>{@link Agent} — agent tool configuration (limits, compression)
 *   <li>{@link Summary} — summary pipeline configuration
 *   <li>{@link Search} — search pipeline configuration
 *   <li>{@link Telemetry} — telemetry flush and retention settings
 *   <li>{@link Policy} — enterprise policy flags
 *   <li>{@link Ui} — UI settings mode and automation flags
 * </ul>
 *
 * @see ResolvedConfigBuilder
 */
public record ResolvedConfig(
    Paths paths,
    Ports ports,
    Ai ai,
    Llm llm,
    Agent agent,
    Summary summary,
    Search search,
    Telemetry telemetry,
    Policy policy,
    Ui ui,
    Watcher watcher,
    Ocr ocr,
    Index index,
    Rag rag,
    HybridSearch hybridSearch,
    Worker worker,
    Collections collections,
    WorkerAi workerAi,
    WorkerIndexer workerIndexer,
    InfraHealth infraHealth,
    InfraGrpc infraGrpc,
    Map<String, ConfigResolution> resolutions) {

  public ResolvedConfig {
    Objects.requireNonNull(paths, "paths");
    Objects.requireNonNull(ports, "ports");
    Objects.requireNonNull(ai, "ai");
    Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(search, "search");
    Objects.requireNonNull(telemetry, "telemetry");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(ui, "ui");
    Objects.requireNonNull(watcher, "watcher");
    Objects.requireNonNull(ocr, "ocr");
    Objects.requireNonNull(index, "index");
    Objects.requireNonNull(rag, "rag");
    Objects.requireNonNull(hybridSearch, "hybridSearch");
    Objects.requireNonNull(worker, "worker");
    Objects.requireNonNull(collections, "collections");
    Objects.requireNonNull(workerAi, "workerAi");
    Objects.requireNonNull(workerIndexer, "workerIndexer");
    Objects.requireNonNull(infraHealth, "infraHealth");
    Objects.requireNonNull(infraGrpc, "infraGrpc");
    resolutions = Map.copyOf(resolutions);
  }

  /** Returns the {@link ConfigResolution} for a given key, or null if not tracked. */
  public ConfigResolution resolution(String key) {
    return resolutions.get(key);
  }

  /** Creates a new builder for constructing a {@link ResolvedConfig}. */
  public static ResolvedConfigBuilder builder() {
    return new ResolvedConfigBuilder();
  }

  private static final ObjectMapper SNAPSHOT_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
      new TypeReference<>() {};

  /**
   * Writes resolved config values to a JSON file for Head→Worker propagation.
   *
   * <p>The snapshot contains all resolved (non-null) key-value pairs as a flat JSON object. The
   * Worker process loads this file at ordinal 450 via {@link
   * ResolvedConfigBuilder#contributeWorkerSnapshot(Path)}.
   *
   * @param snapshotPath path to write the snapshot file
   * @throws UncheckedIOException if writing fails
   */
  public void toWorkerSnapshot(Path snapshotPath) {
    Map<String, String> snapshot = new LinkedHashMap<>();
    for (Map.Entry<String, ConfigResolution> entry : resolutions.entrySet()) {
      if (entry.getValue().value() != null) {
        snapshot.put(entry.getKey(), entry.getValue().value());
      }
    }
    putPath(snapshot, "justsearch.data.dir", paths.dataDir());
    putPath(snapshot, "justsearch.index.base_path", paths.indexBasePath());
    putPath(snapshot, "justsearch.home", paths.home());
    putPath(snapshot, "justsearch.models.dir", paths.modelsDir());
    putPath(snapshot, "justsearch.ssot.path", paths.ssotPath());
    putPath(snapshot, "justsearch.repo.root", paths.repoRoot());
    putPath(snapshot, "justsearch.onnxruntime.native_path", paths.ortNativePath());
    putPath(snapshot, "justsearch.server.exe", ai.serverExe());
    putPath(snapshot, "justsearch.llm.model_path", ai.llmModelPath());
    putPath(snapshot, "justsearch.rerank.model_path", ai.reranker().modelPath());
    putPath(snapshot, "justsearch.ner.model_path", ai.ner().modelPath());
    putPath(snapshot, "justsearch.splade.model_path", ai.splade().modelPath());
    putPath(snapshot, "justsearch.splade.evidence_path", ai.splade().evidencePath());
    putPath(snapshot, "justsearch.rerank.chunks.model_path", ai.reranker().chunks().modelPath());
    putPath(snapshot, "justsearch.citation.scorer.model_path", ai.citationScorer().modelPath());
    try {
      Files.createDirectories(snapshotPath.getParent());
      SNAPSHOT_MAPPER.writeValue(snapshotPath.toFile(), snapshot);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write worker config snapshot", e);
    }
  }

  /**
   * Loads a worker config snapshot from a JSON file.
   *
   * @param snapshotPath path to the snapshot file
   * @return key-value pairs from the snapshot, or empty map if file doesn't exist
   */
  static Map<String, String> loadWorkerSnapshot(Path snapshotPath) {
    if (!Files.exists(snapshotPath)) return new LinkedHashMap<>();
    try {
      return SNAPSHOT_MAPPER.readValue(snapshotPath.toFile(), MAP_TYPE);
    } catch (Exception e) {
      // Best-effort; return empty map on read failure
      return new LinkedHashMap<>();
    }
  }

  // ==================== Sub-records ====================

  /**
   * File system paths resolved from env vars, sysprops, YAML, and platform defaults.
   *
   * @param dataDir root data directory for all JustSearch artifacts
   * @param indexBasePath base path for index storage (derived from dataDir if not explicit)
   * @param home JustSearch home directory
   * @param modelsDir directory for AI model files
   * @param ssotPath path to SSOT directory
   * @param repoRoot repository root path (for dev/test)
   */
  public record Paths(
      Path dataDir,
      Path indexBasePath,
      Path home,
      Path modelsDir,
      Path ssotPath,
      Path repoRoot,
      Path ortNativePath) {}

  private static void putPath(Map<String, String> snapshot, String key, Path value) {
    if (value == null) return;
    snapshot.put(key, value.toAbsolutePath().normalize().toString());
  }

  /**
   * Network ports for API and inference services.
   *
   * @param apiPort local API server port (default 8080)
   * @param serverPort llama-server HTTP port
   */
  public record Ports(int apiPort, int serverPort) {}

  /**
   * AI and inference feature configuration — model paths, GPU layers, feature flags, and VRAM
   * thresholds. LLM runtime tuning lives in {@link Llm}.
   */
  public record Ai(
      Path serverExe,
      int gpuLayers,
      Path llmModelPath,
      boolean disabled,
      boolean llmEnabled,
      String llmMode,
      String llmBackend,
      int contextSize,
      String vlmModel,
      String mmprojModel,
      boolean aiClassifyEnabled,
      boolean useThinking,
      int reasoningBudget,
      String onnxruntimeVariantId,
      String serverExeSource,
      long vramThreshold12gb,
      long vramThreshold8gb,
      long vramThreshold4gb,
      double gplReevalSizeFactor,
      Embedding embedding,
      Splade splade,
      Ner ner,
      Reranker reranker,
      CitationScorer citationScorer,
      BgeM3 bgeM3,
      Profiling profiling,
      String sparseModel,
      boolean devHotReload,
      BackfillPacing backfillPacing,
      // Tempdoc 710 Wave 2 Move 1: undeclared model-capability facts fail startup for that
      // encoder lane instead of WARN + fallback (default false until 657 ships manifests in
      // packs — see EnvRegistry.CAPABILITY_CONTRACT_STRICT).
      boolean capabilityContractStrict) {

    /** BGE-M3 multi-vector retrieval configuration. */
    public record BgeM3(
        Boolean enabled,
        Path modelPath,
        int maxSeqLen,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb) {}

    public record Embedding(
        Boolean enabled,
        String backend,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        int contextLength,
        // Tempdoc 691 Phase 1: late-chunking embed pass (single forward pass for a chunked
        // parent + its chunk docs) — default off.
        boolean lateChunkingEnabled,
        // Tempdoc 691 Phase 2: single-pass whole-doc VECTOR limit for the late-chunking path
        // (independent of contextLength — the base batch path OOMs at this length; the
        // late-chunking path is batch-1 by construction). Default 8192, clamped to
        // [contextLength, 8192].
        int lateChunkingContextLength) {}

    public record Splade(
        Boolean enabled,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        Path modelPath,
        int maxSeqLen,
        String queryMode,
        String activation,
        Path evidencePath) {}

    public record Ner(
        Boolean enabled,
        Path modelPath,
        int maxSeqLen,
        double confidenceThreshold,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb) {}

    public record Reranker(
        Boolean enabled,
        Path modelPath,
        boolean gpuEnabled,
        int gpuDeviceId,
        int gpuMemMb,
        int topK,
        int deadlineMs,
        int minHits,
        int maxSeqLen,
        int maxAvgDocLengthChars,
        // Tempdoc 643: judge-stage refinement floor (blend CE reorder with fusion order).
        boolean judgeBlendEnabled,
        double judgeBlendAlpha,
        // Tempdoc 643 (E1/E2): per-query confidence-driven arbitration + perf-skip.
        boolean judgeArbitrationEnabled,
        double judgeArbitrationAlphaDiverge,
        boolean judgeArbitrationSkipEnabled,
        ChunkReranker chunks) {

      public record ChunkReranker(
          Boolean enabled,
          Path modelPath,
          boolean gpuEnabled,
          int gpuDeviceId,
          int topK,
          int maxGpuCandidates,
          int deadlineMs,
          int minHits,
          int maxSeqLen,
          String order) {}
    }

    public record CitationScorer(
        Boolean enabled, Path modelPath, double threshold, int maxSeqLen, int deadlineMs) {}

    /**
     * ORT diagnostic-observability knobs (tempdoc 397 §14.24 FB).
     *
     * <p>Both are process-wide and policy-typed — they flow through the same resolver chain
     * as every other runtime option, so {@code /api/debug/session-policies} reflects them and
     * the {@link io.justsearch.ort.SessionOptionsApplier} reads them via
     * {@code runtime.profiling()} instead of {@code System.getenv}.
     *
     * @param ortProfilingDir directory for per-session profile files; null = disabled
     * @param verboseLogging enables ORT VERBOSE-level session logging
     */
    public record Profiling(Path ortProfilingDir, boolean verboseLogging) {}

    /**
     * Enrichment-backfill pacing knobs (tempdoc 710 Wave-1.5 Move 4). Previously bare literals in
     * {@code LoopPacingPolicy} / {@code BackfillScheduler} / {@code
     * CombinedEnrichmentBackfillOps} with zero config surface; converted 1:1 to {@code
     * justsearch.backfill.*} keys with identical defaults so behavior is unchanged unless an
     * operator explicitly overrides one for experimentation.
     *
     * @param pollBatchSize primary-indexing job-queue poll batch size. Raised from 1 to 16 in
     *     tempdoc 278 Phase 1 item 1b to amortize per-batch queue overhead (paired with item 1a's
     *     per-document {@code isUserActive()} check, which keeps larger batches responsive).
     * @param embeddingBackfillBatchSize doc-count per embedding backfill batch (parent docs and,
     *     when {@code chunkVectorsEnabled}, the chunk cache populated by the same batch size).
     * @param nerBackfillBatchSize doc-count per NER backfill batch.
     * @param disambiguationBackfillBatchSize doc-count per disambiguation backfill batch.
     * @param spladeBackfillBatchSize doc-count per idle-branch SPLADE backfill batch.
     * @param spladeInterleaveBatchSize doc-count per SPLADE batch interleaved into the primary
     *     indexing branch (tempdoc 278 Phase 4c — smaller than the idle-branch batch so
     *     interleaving stays cheap).
     * @param spladeInterleaveIntervalMs minimum time between interleaved SPLADE/BGE-M3 batches
     *     during primary indexing (tempdoc 278 Phase 4a — time-gated to limit primary-indexing
     *     overhead to ~13%).
     * @param commitIntervalMs time-based commit trigger: commit if this much time has elapsed
     *     since the last commit and at least one document is pending.
     * @param maxDocsBeforeCommit buffer-based commit trigger: commit once this many documents have
     *     been indexed since the last commit, regardless of elapsed time.
     * @param chunkSlotsPerBatch chunk-doc cache slots populated per combined-backfill batch
     *     (formerly the bare {@code chunkSlotsPerBatch = 50} literal in {@code
     *     CombinedEnrichmentBackfillOps}). Tempdoc 691 §F-1 measured this cap is NOT the
     *     dense-corpus chunk-only-tail throughput lever — that tail is GPU-embedding-compute-bound
     *     (82% ORT time at the compute floor), not cap-throttled — so this exists as a config
     *     surface for experimentation, not because raising it is known to help.
     * @param bgeM3BackfillBatchSize doc-count per idle-branch BGE-M3 backfill batch (BGE-M3's own
     *     pacing constant — previously bypassed {@code LoopPacingPolicy} entirely as a stray
     *     literal in {@code BackfillScheduler}; unified onto this record).
     * @param bgeM3InterleaveBatchSize doc-count per BGE-M3 batch interleaved into the primary
     *     indexing branch (BGE-M3's counterpart to {@code spladeInterleaveBatchSize}).
     */
    public record BackfillPacing(
        int pollBatchSize,
        int embeddingBackfillBatchSize,
        int nerBackfillBatchSize,
        int disambiguationBackfillBatchSize,
        int spladeBackfillBatchSize,
        int spladeInterleaveBatchSize,
        long spladeInterleaveIntervalMs,
        long commitIntervalMs,
        int maxDocsBeforeCommit,
        int chunkSlotsPerBatch,
        int bgeM3BackfillBatchSize,
        int bgeM3InterleaveBatchSize) {

      /**
       * The historical hardcoded values, used as a defensive fallback when no {@link
       * ResolvedConfig} is available (e.g. a test double supplying a null config supplier).
       * Identical to every field's {@code justsearch.backfill.*} default in {@link
       * ResolvedConfigBuilder} — kept in sync by construction since both originate from the same
       * pre-Move-4 literals.
       */
      public static final BackfillPacing DEFAULTS =
          new BackfillPacing(16, 100, 100, 500, 200, 10, 5_000L, 10_000L, 1000, 50, 50, 10);
    }
  }

  /** LLM runtime tuning — sampling, VRAM management, deadlines, templates, remote config. */
  public record Llm(
      String modelSha256,
      int llmGpuLayers,
      long deadlineMs,
      int maxParallel,
      int maxSessions,
      long sessionWarmupMs,
      int queueCapacity,
      double vramFraction,
      long vramProjected,
      int maxSlots,
      long vramLimitBytes,
      boolean vramAutoScale,
      long simulatedLatencyMs,
      int threads,
      int contextLength,
      int maxNewTokens,
      double temperature,
      double topP,
      double minP,
      long rngSeed,
      String backendSelector,
      String backendSupports,
      int summaryChunkTokens,
      int summaryChunkOverlap,
      String templateRoot,
      String templateSummary,
      String templateReduce) {}

  /** Agent tool configuration — search/browse limits, context compression. */
  public record Agent(
      int searchDefaultLimit,
      String searchDefaultMode,
      int browseDefaultMaxFolders,
      int maxToolResultChars,
      int maxCompletionTokens,
      boolean contextCompressionEnabled,
      int contextCompressionMinChars,
      int contextCompressionKeepLastResults) {}

  /** Summary pipeline configuration. */
  public record Summary(String pipeline, int maxTokens) {}

  /**
   * Search pipeline configuration.
   *
   * @param profile search pipeline profile name
   * @param pipeline search pipeline definition file path
   * @param collection primary index collection name
   * @param queryClassificationEnabled 306: enable query classification for CE/expansion gating
   * @param titleBoost 306: title field boost in DisjunctionMaxQuery (0 to disable)
   * @param entityBoost 326: NER entity field boost in DisjunctionMaxQuery (0 to disable)
   */
  public record Search(
      String profile,
      String pipeline,
      String collection,
      boolean queryClassificationEnabled,
      double titleBoost,
      double entityBoost,
      boolean chunkAwareEnabled,
      boolean lambdamartEnabled,
      Corrections corrections) {

    /** Spelling/fuzzy correction settings. */
    public record Corrections(
        boolean enabled,
        int dfThreshold,
        int maxEditDistance,
        boolean zeroHitRetryEnabled) {}
  }

  /**
   * Telemetry flush and retention settings.
   *
   * @param flushMs telemetry flush interval in milliseconds
   * @param metricsMaxMb max size of metrics file in MB
   * @param metricsRetentionDays metrics retention period in days
   * @param exemplarsEnabled whether telemetry exemplars are enabled
   */
  public record Telemetry(
      long flushMs, int metricsMaxMb, int metricsRetentionDays, boolean exemplarsEnabled) {}

  /**
   * Enterprise policy flags.
   *
   * @param egressBlockAll true to block all egress (isolated testing)
   * @param prodMode true if running in production mode
   * @param indexParityAllowMismatch true to allow opening index read-only on schema mismatch
   */
  public record Policy(
      boolean egressBlockAll, boolean prodMode, boolean indexParityAllowMismatch,
      String languagePolicy) {}

  /**
   * UI configuration.
   *
   * @param settingsMode persistence mode for UI settings (read-only, in-memory, etc.)
   * @param automationEnabled true if UI automation mode is enabled
   * @param requireTranslator true to require translator even in automation mode
   * @param forceDiagnostics true to force infra diagnostics overrides
   */
  public record Ui(
      String settingsMode,
      boolean automationEnabled,
      boolean requireTranslator,
      boolean forceDiagnostics) {}

  /**
   * File-system watcher configuration.
   *
   * @param strategy watcher strategy (native, polling, none)
   * @param debounceMs debounce interval in milliseconds
   * @param overflowRescanOnOverflow whether to rescan on watcher overflow
   * @param pollingIntervalMs polling interval in milliseconds (for polling strategy)
   * @param queueMaxEntries maximum watcher queue entries
   */
  public record Watcher(
      String strategy,
      Integer debounceMs,
      Boolean overflowRescanOnOverflow,
      Integer pollingIntervalMs,
      Integer queueMaxEntries) {}

  /**
   * OCR (optical character recognition) pipeline configuration.
   *
   * @param enabled whether OCR is enabled
   * @param languages list of OCR languages
   * @param triggerMinImagePixels minimum image pixels to trigger OCR
   * @param perFileTimeoutMs per-file OCR timeout in milliseconds
   * @param maxPages maximum pages to process
   * @param maxImageDimension maximum image dimension
   * @param maxImagePixels maximum total image pixels
   * @param renderDpi PDF page render DPI for OCR
   * @param workers OCR worker pool size (0 or absent = auto, derived from available cores)
   */
  public record Ocr(
      Boolean enabled,
      List<String> languages,
      Integer triggerMinImagePixels,
      Integer perFileTimeoutMs,
      Integer maxPages,
      Integer maxImageDimension,
      Integer maxImagePixels,
      Integer renderDpi,
      Integer workers) {

    public Ocr {
      languages = languages != null ? List.copyOf(languages) : List.of();
    }
  }

  /**
   * Index writer, commit, NRT, soft-delete, and vector configuration.
   *
   * @param writerRamBufferMb RAM buffer size for IndexWriter
   * @param writerMaxBufferedDocs max buffered docs before flush
   * @param writerMaxQueueDepth max writer queue depth
   * @param commitDebounceMs commit debounce interval in ms
   * @param commitPolicy commit policy (per_batch, deferred)
   * @param commitMetadataEnabled whether commit metadata is enabled
   * @param nrtTargetMaxStaleMs NRT target max stale time in ms
   * @param nrtHardMaxStaleMs NRT hard max stale time in ms
   * @param softDeletesField field used for soft deletes
   * @param softDeletesRetentionEnabled whether soft-delete retention is enabled
   * @param softDeletesRetentionDays retention period in days
   * @param softDeletesRetentionMaxVersions max versions to retain
   * @param vectorDimension vector dimension
   * @param vectorHnswM HNSW M parameter
   * @param vectorHnswEfConstruction HNSW ef_construction parameter
   * @param vectorEfSearch ef_search parameter
   * @param vectorQuantizationEnabled whether vector quantization is enabled
   * @param indexAutoRecovery whether auto-recovery is enabled for corrupted index
   * @param schemaMismatchPolicy schema mismatch handling policy
   * @param indexIntegrityCheck open-time integrity verification tier ({@code OFF} / {@code STRUCTURAL}
   *     / {@code FULL}); STRUCTURAL verifies the small commit/segment-info file checksums on open, FULL
   *     additionally verifies every segment data file's footer checksum (bounded-vs-thorough knob,
   *     tempdoc 628 G1)
   * @param indexRecoveryPolicy the single orchestration-layer corruption-recovery authority
   *     ({@code BACKUP_REBUILD} / {@code BACKUP_ONLY} / {@code FAIL_CLOSED}); BACKUP_REBUILD (default)
   *     backs up the damaged index, serves degraded, and rebuilds from the source files on disk;
   *     BACKUP_ONLY recovers to empty without an auto-rebuild; FAIL_CLOSED never auto-recovers
   *     (tempdoc 628 Stage B/G2)
   * @param migrationCutoverMaxFailedJobs max failed jobs before migration cutover is blocked
   */
  public record Index(
      Integer writerRamBufferMb,
      Integer writerMaxBufferedDocs,
      Integer writerMaxQueueDepth,
      Integer commitDebounceMs,
      String commitPolicy,
      boolean commitMetadataEnabled,
      Integer nrtTargetMaxStaleMs,
      Integer nrtHardMaxStaleMs,
      String softDeletesField,
      Boolean softDeletesRetentionEnabled,
      Integer softDeletesRetentionDays,
      Integer softDeletesRetentionMaxVersions,
      Integer vectorDimension,
      Integer vectorHnswM,
      Integer vectorHnswEfConstruction,
      Integer vectorEfSearch,
      Boolean vectorQuantizationEnabled,
      boolean indexAutoRecovery,
      String schemaMismatchPolicy,
      String indexIntegrityCheck,
      String indexRecoveryPolicy,
      int migrationCutoverMaxFailedJobs,
      String directoryType,
      Integer mergeTieredSegsPerTier,
      Integer mergeTieredMaxMergedSegmentMb,
      String similarityTextType,
      Double similarityTextK1,
      Double similarityTextB,
      String validationMode,
      String defaultLanguage,
      String tracingLevel,
      List<IndexSortItem> sort,
      Map<String, Double> boosts) {

    public Index {
      sort = sort != null ? List.copyOf(sort) : List.of();
      boosts = boosts != null
          ? java.util.Collections.unmodifiableMap(new TreeMap<>(boosts)) : Map.of();
    }

    /** Index-time sort field specification from YAML {@code index.sort[]}. */
    public record IndexSortItem(String field, Boolean reverse, String type) {}
  }

  /** Collection configuration from YAML {@code index.collections[]}. */
  public record CollectionCfg(String name, List<Path> roots, String watcherStrategy) {
    public CollectionCfg {
      roots = roots != null ? List.copyOf(roots) : List.of();
    }
  }

  /** Named collection list with primary collection derivation. */
  public record Collections(List<CollectionCfg> items) {
    public Collections {
      items = items != null ? List.copyOf(items) : List.of();
    }
  }

  /** AI worker gRPC client connection config (Head→AI worker). */
  public record WorkerAi(boolean enabled, String host, int port, long deadlineMs) {}

  /** Indexer worker gRPC client connection config (Head→Body). */
  public record WorkerIndexer(
      boolean enabled, String host, int port, long deadlineMs,
      int queueSize, int maxInFlightBytes, String backpressureMode) {}

  /** Infrastructure health check thresholds from YAML {@code infra.health.*}. */
  public record InfraHealth(
      long pollIntervalMs, long nrtStaleMs, long translatorHandshakeStaleMs,
      int annCacheReadyPercent) {}

  /** Infrastructure health gRPC server binding from YAML {@code infra.health.grpc.*}. */
  public record InfraGrpc(String host, int port) {}

  /**
   * RAG (Retrieval-Augmented Generation) retrieval configuration.
   *
   * @param retrieveMode retrieval mode (bm25, hybrid, auto)
   * @param retrieveTopK number of chunks to retrieve (YAML-level)
   * @param overretrieveFactor over-retrieval factor
   * @param diversifyMode diversification mode (position, mmr)
   * @param mmrLambda MMR lambda parameter
   * @param mmrMaxCandidates max MMR candidates
   * @param includeSurroundingContext whether to include surrounding context
   * @param chunkVectorsEnabled whether chunk-level vector retrieval is enabled
   * @param chunkSpladeEnabled whether chunk-level SPLADE enrichment is enabled (tempdoc 712:
   *     encodes chunk docs' {@code chunk_content} into the {@code splade} FeatureField so the
   *     chunk-merge sparse sub-leg has data; default false — evidence-gated flip, F-033/Q-017)
   * @param ragTopK env var override for RAG top-k (justsearch.rag.top_k)
   * @param citationMatchThreshold cosine similarity threshold for citation matching
   * @param maxChunksPerArticle 385: max chunks per parent document in RAG context (diversity cap)
   */
  public record Rag(
      String retrieveMode,
      int retrieveTopK,
      int overretrieveFactor,
      String diversifyMode,
      double mmrLambda,
      int mmrMaxCandidates,
      boolean includeSurroundingContext,
      boolean chunkVectorsEnabled,
      boolean chunkSpladeEnabled,
      int ragTopK,
      String citationMatchThreshold,
      int maxChunksPerArticle) {}

  /**
   * Hybrid-search tuning knobs for RRF, candidate limits, weights, and low-signal gating.
   *
   * @param rrfK RRF constant K
   * @param vectorSkipMinChars min query chars before vector search is attempted
   * @param candidateLimitMax max candidates per retrieval system
   * @param textCandidateMultiplier BM25 candidate multiplier
   * @param vectorCandidateMultiplier vector candidate multiplier
   * @param vectorRrfWeight vector RRF weight
   * @param bm25ScoreBoostWeight additive BM25 score boost weight
   * @param vectorLowSignalTopScoreThreshold low-signal vector top-score threshold (EUCLIDEAN score
   *     space — the dense field is EUCLIDEAN, not cosine; default 0.294 corresponds to intended
   *     cosine-score 0.40, tempdoc 702)
   * @param bm25LowSignalTopScoreThreshold low-signal BM25 top-score threshold
   * @param bm25LowSignalTotalHitsThreshold low-signal BM25 total-hits threshold
   * @param vectorOnlyCapLowSignal max vector-only docs in low-signal fusion
   * @param vectorRrfWeightLowSignal vector RRF weight for low-signal queries
   * @param fusionStrategy fusion algorithm: "rrf" (default) or "cc" (convex combination)
   * @param ccAlpha CC dense weight (0.0=pure sparse, 1.0=pure dense, default 0.5)
   * @param ccZeroExclude if true, single-leg docs use only that leg's weight as denominator
   *     instead of being penalized with 0.0 for the missing leg (default false)
   */
  public record HybridSearch(
      int rrfK,
      int vectorSkipMinChars,
      int candidateLimitMax,
      int textCandidateMultiplier,
      int vectorCandidateMultiplier,
      double vectorRrfWeight,
      double bm25ScoreBoostWeight,
      double vectorLowSignalTopScoreThreshold,
      double bm25LowSignalTopScoreThreshold,
      int bm25LowSignalTotalHitsThreshold,
      int vectorOnlyCapLowSignal,
      double vectorRrfWeightLowSignal,
      String fusionStrategy,
      double ccAlpha,
      boolean ccZeroExclude,
      double ccWeightSparse,
      double ccWeightDense,
      double ccWeightSplade,
      String branchFusionStrategy,
      boolean branchCcZeroExclude,
      double branchCcWeightWhole,
      double branchCcWeightChunk,
      double branchChunkMinWeightMultiplier,
      boolean adaptiveWeightsEnabled,
      boolean legArbitrationEnabled,
      double legArbitrationAlphaDiverge,
      double legArbitrationBm25IncoherenceMin,
      boolean legRecallCompleteEnabled,
      int legRecallCompleteTopN) {}


  /**
   * Worker resource limits and service configuration.
   *
   * @param maxBatchSize maximum files in a single batch request
   * @param maxQueueDepth maximum queue depth before rejecting
   * @param maxContentLength maximum content length in bytes
   * @param maxFileSize maximum file size in bytes
   */
  public record Worker(int maxBatchSize, long maxQueueDepth, int maxContentLength, long maxFileSize) {}
}
