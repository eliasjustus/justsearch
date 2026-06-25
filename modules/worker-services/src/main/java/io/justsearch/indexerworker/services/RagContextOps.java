/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.ChunkSearchOps;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.VectorUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ipc.ContextChunk;
import io.justsearch.ipc.ContextSection;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.indexing.rag.MmrSelector;
import io.justsearch.indexing.rag.TokenAwareBudgeter;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.reranker.RerankerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * RAG context retrieval logic extracted from {@link GrpcSearchService}.
 *
 * <p>Manages chunk search (BM25/hybrid), cross-encoder reranking with deadline and GPU
 * arbitration, MMR/position diversification, and token-aware context budgeting.
 */
final class RagContextOps {
  private static final Logger log = LoggerFactory.getLogger(RagContextOps.class);

  /** Minimum chunk vector coverage percentage required for chunk-level hybrid. */
  private static final double CHUNK_VECTOR_COVERAGE_THRESHOLD = 0.95;

  private final ChunkSearchOps chunkSearchOps;
  private final IndexCountOps indexCountOps;
  private final CommitOps commitOps;
  private final java.util.function.Supplier<ResolvedConfig> resolvedConfigSupplier;
  private volatile EmbeddingProvider embeddingProvider;
  private final OperationalMetrics metrics = OperationalMetrics.getInstance();

  // Phase 5: Chunk reranking
  private volatile RerankerConfig.ChunkRerankerConfig chunkRerankerConfig;
  // Note: signalBus + setSignalBus removed by tempdoc 397 §14.26 T2-E1 along with the CPU-only
  // lazy chunkReranker fallback that was the sole consumer of the signal bus in this class.

  // 360: Shared search reranker (GPU-capable). When set, used for chunk reranking too.
  private volatile CrossEncoderReranker searchReranker;

  // 385: Parent document metadata lookup for source-aware context labels (#3)
  private final DocumentFieldOps documentFieldOps;

  RagContextOps(
      ChunkSearchOps chunkSearchOps,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      java.util.function.Supplier<ResolvedConfig> resolvedConfigSupplier,
      EmbeddingProvider embeddingProvider,
      DocumentFieldOps documentFieldOps) {
    this.chunkSearchOps = chunkSearchOps;
    this.indexCountOps = indexCountOps;
    this.commitOps = commitOps;
    this.resolvedConfigSupplier = resolvedConfigSupplier;
    this.embeddingProvider = embeddingProvider;
    this.documentFieldOps = documentFieldOps;
  }

  /** Backward-compatible constructor (documentFieldOps = null → filename-only labels). */
  RagContextOps(
      ChunkSearchOps chunkSearchOps,
      IndexCountOps indexCountOps,
      CommitOps commitOps,
      java.util.function.Supplier<ResolvedConfig> resolvedConfigSupplier,
      EmbeddingProvider embeddingProvider) {
    this(chunkSearchOps, indexCountOps, commitOps, resolvedConfigSupplier, embeddingProvider, null);
  }

  void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
    this.embeddingProvider = embeddingProvider;
  }

  // ==================== Records ====================

  record ChunkContextResult(
      String context,
      List<LuceneRuntimeTypes.SearchHit> usedHits,
      long totalFound,
      String retrievalMode,
      String retrievalModeReason,
      boolean contextTruncated,
      List<ContextBudgeter.Section> sections,
      RagQualitySignals qualitySignals) {}

  /** Result of chunk reranking: reordered hits with their CE scores preserved. */
  record ChunkRerankResult(
      List<LuceneRuntimeTypes.SearchHit> hits,
      List<Float> ceScores) {

    static ChunkRerankResult unchanged(List<LuceneRuntimeTypes.SearchHit> hits) {
      return new ChunkRerankResult(hits, List.of());
    }

    boolean wasReranked() {
      return !ceScores.isEmpty();
    }
  }

  /** Quality signals computed during retrieval for CRAG-style confidence assessment. */
  record RagQualitySignals(
      float bestChunkScore,
      float scoreGap,
      float retrievalCoverage,
      int chunksConsidered,
      int chunksIncluded) {

    static final RagQualitySignals EMPTY = new RagQualitySignals(0f, 0f, 0f, 0, 0);
  }

  /**
   * Tempdoc 561 P-A5 — bound an excerpt to {@code maxLen} chars at a WORD boundary, never a raw
   * mid-word cut. The producer (this Worker) owns the excerpt's clean boundary so the FE renders
   * the {@code ContextCitation.excerpt} verbatim and never re-windows (the cross-process residue of
   * 559 §5 / observations C-2; the boundary-aware startLine/endLine ride alongside for navigation).
   * Walks back to the last whitespace within a 40-char lookback (so a single very long token still
   * truncates rather than returning an over-short excerpt) and appends an ellipsis when truncated.
   */
  static String clampExcerptToWordBoundary(String text, int maxLen) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLen) {
      return text;
    }
    int scan = maxLen;
    while (scan > maxLen - 40 && scan > 0 && !Character.isWhitespace(text.charAt(scan))) {
      scan--;
    }
    int cut = (scan > 0 && Character.isWhitespace(text.charAt(scan))) ? scan : maxLen;
    return text.substring(0, cut).stripTrailing() + "...";
  }

  // ==================== Configuration ====================

  /**
   * Sets the chunk reranker configuration (Phase 5).
   *
   * <p>If config is ready (enabled + model path set), the reranker will be lazily initialized.
   *
   * @param config the chunk reranker configuration
   */
  void setChunkRerankerConfig(RerankerConfig.ChunkRerankerConfig config) {
    this.chunkRerankerConfig = config;
    if (config != null && config.isReady()) {
      log.info(
          "Chunk reranking enabled: topK={}, deadline={}ms, modelPath={}, gpuEnabled={}",
          config.topK(),
          config.deadlineBudgetMs(),
          config.modelPath(),
          config.gpuEnabled());
    }
  }

  /**
   * 360: Sets the shared search reranker (GPU-capable). Tempdoc 397 §14.26 T2-E1 removed the
   * former CPU-only lazy fallback, so this is a single-owner pointer.
   */
  void setSearchReranker(CrossEncoderReranker reranker) {
    this.searchReranker = reranker;
  }

  // ==================== GPU Lifecycle ====================

  /**
   * Called when Main process claims GPU. Releases reranker GPU session to yield VRAM.
   *
   * <p>This allows Worker to release GPU resources when Main needs them for inference,
   * following the same lifecycle pattern as embedding service in IndexingLoop.
   */
  void onMainClaimedGpu() {
    CrossEncoderReranker reranker = searchReranker;
    if (reranker != null) {
      reranker.releaseGpuSession();
    }
  }

  /**
   * F1: Returns the ORT CUDA status for observability.
   *
   * <p>Exposes the reranker's GPU status for surfacing in /api/status.
   * Returns null if reranker is not initialized.
   *
   * @return current ORT CUDA status, or null if reranker not available
   */
  OrtCudaStatus getOrtCudaStatus() {
    CrossEncoderReranker reranker = searchReranker;
    return reranker != null ? reranker.getOrtCudaStatus() : null;
  }

  // ==================== Full Retrieval Flow ====================

  /**
   * Executes the full RAG retrieval flow: chunk search, proto building, and fallback.
   *
   * <p>First searches for relevant chunks. If chunks are found, builds the response with
   * structured chunk metadata and sections. If no chunks match, falls back to full document
   * search with BM25.
   *
   * @return fully-built RetrieveContextResponse for all paths
   */
  /** Legacy overload for existing callers (no filters). */
  RetrieveContextResponse executeRetrieval(
      String question, Set<String> docIds, int topK, int maxContextTokens,
      boolean allowQueryEmbeddings) {
    return executeRetrieval(
        io.justsearch.ipc.RetrieveContextRequest.newBuilder()
            .setQuestion(question)
            .addAllDocIds(docIds)
            .setTopK(topK)
            .setMaxContextTokens(maxContextTokens)
            .build(),
        docIds, topK, maxContextTokens, allowQueryEmbeddings);
  }

  /**
   * Executes the full RAG retrieval flow with filter support.
   *
   * <p>Reads entity/temporal/content filters from the proto request and threads them
   * through to chunk search via RuntimeSearchFilters.
   */
  RetrieveContextResponse executeRetrieval(
      io.justsearch.ipc.RetrieveContextRequest request,
      Set<String> docIds, int topK, int maxContextTokens,
      boolean allowQueryEmbeddings) {

    String question = request.getQuestion();

    // Build runtime filters from the proto request
    var filters = buildRagFilters(request);

    // Two-stage pre-filter: resolve document-level filters to parent doc IDs.
    // Entity, metadata, path, and date filters are stored on parent docs, not chunks.
    // We find matching parents first, then scope chunk search to those doc IDs.
    Set<String> effectiveDocIds = docIds;
    if (hasDocLevelFilters(filters)) {
      var parentFilter = QueryFilterBuilder.buildFilterQueryOnly(withIncludeChunks(filters, false));
      if (parentFilter != null) {
        Set<String> matchingParents =
            chunkSearchOps.findMatchingParentDocIds(parentFilter, 10_000);
        log.debug("RAG pre-filter: {} parent docs match document-level filters",
            matchingParents.size());
        if (matchingParents.isEmpty()) {
          return buildEmptyFilterResponse();
        }
        effectiveDocIds = docIds.isEmpty()
            ? matchingParents
            : intersectSets(docIds, matchingParents);
        if (effectiveDocIds.isEmpty()) {
          return buildEmptyFilterResponse();
        }
      }
    }

    // Full document mode: skip chunk search, return full documents directly (366 Phase 6c)
    if (request.getReturnFullDocuments()) {
      log.debug("RAG: return_full_documents=true, skipping chunk search");
      return buildFallbackWithVirtualChunks(
          question, effectiveDocIds, topK, 0, "FULL_DOCUMENT_REQUESTED", "FULL_DOCUMENT");
    }

    ChunkContextResult chunkContext =
        searchChunksWithMeta(question, effectiveDocIds, topK, maxContextTokens,
            allowQueryEmbeddings, filters, request.getExcludedChunksList());
    if (chunkContext.context() != null && !chunkContext.context().isBlank()) {
      log.debug(
          "RAG: Found {} chars from chunks (chunksUsed={}, chunksFoundTotal={})",
          chunkContext.context().length(),
          chunkContext.usedHits().size(),
          chunkContext.totalFound());
      return buildChunkResponse(chunkContext);
    }

    // Fallback: full document search with BM25, then virtual-chunk for citations (RAG-005)
    long chunksFoundInSearch = chunkContext.totalFound();
    String fallbackReason =
        chunksFoundInSearch > 0 ? "CHUNKS_BELOW_THRESHOLD" : "NO_CHUNKS_FOUND";
    log.debug(
        "RAG: Falling back to full document search (chunksFound={}, reason={})",
        chunksFoundInSearch, fallbackReason);
    metrics.recordRagFallback();
    // Tempdoc 610 §J.3 — the whole-doc fallback can't honour chunk-level exclusion, so drop any parent
    // doc whose chunks the user hid; otherwise excluding all of a scoped doc's chunks would silently
    // re-inject its full text here. (The Head's own fetchBatchFallback is filtered the same way.)
    java.util.Set<String> fallbackDocIds =
        ChunkExclusionQuery.dropExcludedParents(effectiveDocIds, request.getExcludedChunksList());
    return buildFallbackWithVirtualChunks(
        question, fallbackDocIds, topK, chunksFoundInSearch, fallbackReason, "FULLTEXT_FALLBACK");
  }

  private RetrieveContextResponse buildChunkResponse(ChunkContextResult chunkContext) {
    var qs = chunkContext.qualitySignals();
    RetrieveContextResponse.Builder out =
        RetrieveContextResponse.newBuilder()
            .setContext(chunkContext.context())
            .setUsedChunks(true)
            .setChunksFound(
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, chunkContext.totalFound())))
            .setRetrievalMode(chunkContext.retrievalMode())
            .setRetrievalModeReason(chunkContext.retrievalModeReason())
            .setContextTruncated(chunkContext.contextTruncated())
            .setQuality(io.justsearch.ipc.QualitySignals.newBuilder()
                .setBestChunkScore(qs.bestChunkScore())
                .setScoreGap(qs.scoreGap())
                .setRetrievalCoverage(qs.retrievalCoverage())
                .setChunksConsidered(qs.chunksConsidered())
                .setChunksIncluded(qs.chunksIncluded())
                .build());

    // Populate structured chunk metadata for click-to-verify UI.
    for (var hit : chunkContext.usedHits()) {
      if (hit == null) continue;
      var fields = hit.fields();
      if (fields == null) continue;
      String parentDocId = fields.getOrDefault(SchemaFields.PARENT_DOC_ID, hit.docId());
      int chunkIndex = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_INDEX), 0);
      int chunkTotal = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_TOTAL), 1);
      int startChar = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_START_CHAR), 0);
      int endChar = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_END_CHAR), 0);

      // F8 Tier 2: Line numbers and heading context for in-document navigation
      int startLine = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_START_LINE), 0);
      int endLine = ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_END_LINE), 0);
      String headingText = fields.getOrDefault(SchemaFields.CHUNK_HEADING_TEXT, "");
      int headingLevel =
          ParseUtils.parseIntSafe(fields.get(SchemaFields.CHUNK_HEADING_LEVEL), 0);

      String excerptRaw = fields.getOrDefault(SchemaFields.CHUNK_CONTENT, "");
      // Tempdoc 561 P-A5: the producer owns the excerpt's clean WORD boundary (the FE renders it
      // verbatim and never re-windows — evidenceProjection.ts / observations C-2). Snap to a word
      // boundary instead of the raw mid-word substring.
      String excerpt = clampExcerptToWordBoundary(excerptRaw, 240);

      out.addChunks(
          ContextChunk.newBuilder()
              .setParentDocId(parentDocId == null ? "" : parentDocId)
              .setChunkIndex(Math.max(0, chunkIndex))
              .setChunkTotal(Math.max(1, chunkTotal))
              .setStartChar(Math.max(0, startChar))
              .setEndChar(Math.max(0, endChar))
              .setScore(hit.score())
              .setExcerpt(excerpt == null ? "" : excerpt)
              // F8 Tier 2: In-document navigation
              .setStartLine(Math.max(0, startLine))
              .setEndLine(Math.max(0, endLine))
              .setHeadingText(headingText == null ? "" : headingText)
              .setHeadingLevel(Math.max(0, headingLevel))
              .build());
    }

    // Phase 4: Populate structured sections with chunk linkage for citation filtering
    for (int i = 0; i < chunkContext.sections().size(); i++) {
      var section = chunkContext.sections().get(i);
      out.addSections(ContextSection.newBuilder()
          .setSourceLabel(section.sourceLabel())
          .setContent(section.content())
          .setTruncated(section.truncated())
          .setSectionIndex(section.sectionIndex())
          .setChunkIndex(i)
          .build());
    }

    return out.build();
  }

  // ==================== Core Search ====================

  /**
   * Search for relevant chunks using BM25 or hybrid (BM25 + vector) search.
   *
   * <p>Uses coverage-aware retrieval to ensure representation from different
   * parts of the document (beginning, middle, end) rather than just top BM25 scores.
   *
   * <p>When maxContextTokens &gt; 0, uses token-aware budgeting to avoid over-fetching
   * context that would be truncated by the Head.
   *
   * @param allowQueryEmbeddings whether vector/hybrid queries are allowed
   */
  ChunkContextResult searchChunksWithMeta(
      String question,
      Set<String> docIds,
      int topK,
      int maxContextTokens,
      boolean allowQueryEmbeddings) {
    // Legacy overload without filters
    return searchChunksWithMeta(
        question, docIds, topK, maxContextTokens, allowQueryEmbeddings, null, List.of());
  }

  ChunkContextResult searchChunksWithMeta(
      String question,
      Set<String> docIds,
      int topK,
      int maxContextTokens,
      boolean allowQueryEmbeddings,
      LuceneRuntimeTypes.RuntimeSearchFilters ragFilters,
      List<io.justsearch.ipc.ChunkRef> excludedChunks) {
    // stage_id is cleaned up by the outer MdcContext.request() scope in GrpcSearchService
    MDC.put("stage_id", "retrieve");
    commitOps.maybeRefresh();
    long startTime = System.currentTimeMillis();
    var ragConfig = resolvedConfigSupplier.get().rag();
    String mode = ragConfig.retrieveMode();
    int overretrieveFactor = ragConfig.overretrieveFactor();
    int overRetrieveK = Math.min(topK * overretrieveFactor, 30);

    LuceneRuntimeTypes.SearchResult result;

    // Determine retrieval mode and capture reason
    boolean useHybrid = false;
    float[] queryVector = null;
    String retrievalModeReason;

    if ("hybrid".equals(mode) || "auto".equals(mode)) {
      // Try to get query embedding for hybrid search
      if (embeddingProvider.isAvailable() && allowQueryEmbeddings) {
        try {
          queryVector = embeddingProvider.embedQuery(question);
          if (queryVector != null && queryVector.length > 0) {
            useHybrid = true;
            retrievalModeReason = "HYBRID_AVAILABLE";
          } else {
            retrievalModeReason = "EMBEDDING_EMPTY";
          }
        } catch (RuntimeException e) {
          log.debug("RAG embedding generation failed: {}, using BM25", e.getMessage());
          retrievalModeReason = "EMBEDDING_GENERATION_FAILED";
        }
      } else {
        retrievalModeReason =
            embeddingProvider instanceof io.justsearch.indexerworker.embed.NoOpEmbeddingProvider
                ? "NO_EMBEDDING_SERVICE"
                : "EMBEDDING_UNAVAILABLE";
      }

      // If mode is "hybrid" but no embedding, log a warning
      if ("hybrid".equals(mode) && !useHybrid) {
        log.warn(
            Markers.append("reason_code", retrievalModeReason)
                .and(Markers.append("requested_mode", mode)),
            "RAG mode is 'hybrid' but embedding unavailable, falling back to BM25");
      }
    } else {
      retrievalModeReason = "BM25_CONFIGURED";
    }

    // Phase 6: Check if chunk vectors are available and ready
    boolean useChunkVectors = false;
    if (useHybrid && ragConfig.chunkVectorsEnabled()) {
      if (isChunkVectorCoverageReady()) {
        useChunkVectors = true;
        log.debug("RAG: chunk vectors enabled and coverage ready");
      } else {
        log.debug(
            "RAG: chunk vectors enabled but coverage insufficient, using doc-first hybrid");
        retrievalModeReason = "CHUNK_VECTOR_COVERAGE_INCOMPLETE";
      }
    }

    // Build chunk-safe filter once (mime, fileKind, mimeBase, language).
    // Document-level filters (entity, metadata, path, date) are handled by the two-stage
    // pre-filter in executeRetrieval() — only chunk-safe filters remain here.
    org.apache.lucene.search.Query chunkFilter =
        ragFilters != null ? QueryFilterBuilder.buildChunkFilterQuery(ragFilters) : null;

    // Tempdoc 610 §J.3 — drop user-hidden sources pre-search (see ChunkExclusionQuery). Empty = no-op.
    chunkFilter = ChunkExclusionQuery.compose(chunkFilter, excludedChunks);

    // Execute search
    String effectiveMode;
    if (useHybrid) {
      if (useChunkVectors) {
        log.debug(
            "RAG using chunk-level hybrid search (Phase 6) for question: '{}'", question);
        result =
            chunkSearchOps.searchChunksHybrid(
                question, queryVector, docIds, overRetrieveK, true, chunkFilter);
        effectiveMode = "CHUNK_HYBRID";
      } else {
        log.debug(
            "RAG using doc-first hybrid search (BM25 + vector) for question: '{}'", question);
        result =
            chunkSearchOps.searchChunksHybrid(
                question, queryVector, docIds, overRetrieveK, false, chunkFilter);
        effectiveMode = "HYBRID";
      }
    } else {
      log.debug("RAG using BM25 search for question: '{}'", question);
      if (chunkFilter != null || docIds.isEmpty()) {
        result = chunkSearchOps.searchChunksFiltered(question, docIds, overRetrieveK, chunkFilter);
      } else {
        result = chunkSearchOps.searchChunksForDocs(question, docIds, overRetrieveK);
      }
      effectiveMode = "BM25";
    }

    // Record metrics
    long latencyMs = System.currentTimeMillis() - startTime;
    metrics.recordRagRetrieval(effectiveMode, result.hits().size(), latencyMs);

    if (result.hits().isEmpty()) {
      return new ChunkContextResult(
          "", List.of(), result.totalHits(), effectiveMode, retrievalModeReason, false, List.of(),
          RagQualitySignals.EMPTY);
    }

    MDC.put("stage_id", "rerank");
    // Phase 5 Gap 5: Adaptive order - rerank before or after diversification based on GPU
    // GPU path: Rerank full candidate set (better quality) -> then diversify on semantic scores
    // CPU path: Diversify first (bounds work) -> then rerank bounded set
    List<LuceneRuntimeTypes.SearchHit> finalHits;
    int chunksConsidered = result.hits().size();
    List<Float> ceScores = List.of();
    if (shouldRerankChunks(effectiveMode)) {
      boolean rerankBeforeDiversify = determineRerankOrder();
      if (rerankBeforeDiversify) {
        // GPU path: rerank full set, then diversify
        var rerankResult = rerankChunks(question, result.hits());
        ceScores = rerankResult.ceScores();
        finalHits =
            diversifyChunks(question, queryVector, rerankResult.hits(), topK, allowQueryEmbeddings);
        log.debug(
            "Chunk retrieval: rerank->diversify (GPU path), {} candidates -> {} final",
            result.hits().size(),
            finalHits.size());
      } else {
        // CPU path: diversify first (bounds work), then rerank
        var diversifiedHits =
            diversifyChunks(question, queryVector, result.hits(), topK, allowQueryEmbeddings);
        var rerankResult = rerankChunks(question, diversifiedHits);
        ceScores = rerankResult.ceScores();
        finalHits = rerankResult.hits();
        log.debug(
            "Chunk retrieval: diversify->rerank (CPU path), {} diversified -> {} final",
            diversifiedHits.size(),
            finalHits.size());
      }
    } else {
      // No reranking - just diversify
      finalHits =
          diversifyChunks(question, queryVector, result.hits(), topK, allowQueryEmbeddings);
    }

    MDC.put("stage_id", "respond");

    // 385: Batch-fetch parent doc metadata for source-aware context labels (#3)
    Map<String, Map<String, String>> parentMeta = fetchParentMetadata(finalHits);

    // 385: Per-parent chunk cap for article diversity (#7)
    int maxChunksPerArticle = resolvedConfigSupplier.get().rag().maxChunksPerArticle();

    // Phase 6 (Gap 6): Budget-aware context assembly with per-parent diversity cap (385 #7).
    // Both token-aware and char-based paths share the same loop logic via runBudgetLoop().
    ArrayList<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();

    if (maxContextTokens > 0) {
      // Token-aware mode: use TokenAwareBudgeter with estimation-only (no tokenizer)
      TokenAwareBudgeter tokenBudgeter = new TokenAwareBudgeter(maxContextTokens);

      boolean contextTruncated = runBudgetLoop(
          finalHits, used, parentMeta, maxChunksPerArticle,
          (label, content) -> mapAppendResult(tokenBudgeter.appendSection(label, content)));

      float coverage = maxContextTokens > 0
          ? (float) tokenBudgeter.estimatedTokens() / maxContextTokens : 0f;
      var quality = computeQualitySignals(
          ceScores, finalHits, chunksConsidered, used.size(), coverage);
      log.info(
          "RAG context assembly (token-aware): ~{}/{} tokens, {} sections, mode={}, bestScore={}",
          tokenBudgeter.estimatedTokens(),
          maxContextTokens,
          tokenBudgeter.sections().size(),
          effectiveMode,
          quality.bestChunkScore());
      return new ChunkContextResult(
          tokenBudgeter.build(),
          List.copyOf(used),
          result.totalHits(),
          effectiveMode,
          retrievalModeReason,
          contextTruncated,
          List.copyOf(tokenBudgeter.sections()),
          quality);
    }

    // Character-based mode (default): use ContextBudgeter with 200K char limit
    ContextBudgeter budgeter = new ContextBudgeter(200_000);

    boolean contextTruncated = runBudgetLoop(
        finalHits, used, parentMeta, maxChunksPerArticle,
        (label, content) -> mapAppendResult(budgeter.appendSection(label, content)));

    var quality = computeQualitySignals(
        ceScores, finalHits, chunksConsidered, used.size(), 0f);
    log.info(
        "RAG context assembly (char-based): {} sections, {} hits, mode={}, bestScore={}",
        budgeter.sections().size(),
        result.totalHits(),
        effectiveMode,
        quality.bestChunkScore());
    return new ChunkContextResult(
        budgeter.build(),
        List.copyOf(used),
        result.totalHits(),
        effectiveMode,
        retrievalModeReason,
        contextTruncated,
        budgeter.sections(),
        quality);
  }

  /** Compute quality signals from CE scores (if reranked) or fusion scores. */
  private static RagQualitySignals computeQualitySignals(
      List<Float> ceScores,
      List<LuceneRuntimeTypes.SearchHit> finalHits,
      int chunksConsidered,
      int chunksIncluded,
      float retrievalCoverage) {
    float bestScore;
    float scoreGap;
    if (!ceScores.isEmpty()) {
      // CE scores are already sorted (highest first from rerankChunks)
      bestScore = ceScores.getFirst();
      scoreGap = ceScores.size() >= 2 ? ceScores.get(0) - ceScores.get(1) : bestScore;
    } else if (!finalHits.isEmpty()) {
      // Fall back to fusion/BM25 scores from hits
      bestScore = finalHits.getFirst().score();
      scoreGap = finalHits.size() >= 2
          ? finalHits.get(0).score() - finalHits.get(1).score() : bestScore;
    } else {
      bestScore = 0f;
      scoreGap = 0f;
    }
    return new RagQualitySignals(bestScore, scoreGap, retrievalCoverage,
        chunksConsidered, chunksIncluded);
  }

  /**
   * Builds a RuntimeSearchFilters from the proto request for threading through to chunk search.
   * Sets includeChunks=true since RAG searches chunks, not documents.
   */
  private static LuceneRuntimeTypes.RuntimeSearchFilters buildRagFilters(
      io.justsearch.ipc.RetrieveContextRequest request) {
    boolean hasFilters = !request.getPathPrefix().isEmpty()
        || request.getFileKindCount() > 0
        || request.getEntityPersonsCount() > 0
        || request.getEntityOrganizationsCount() > 0
        || request.getEntityLocationsCount() > 0
        || request.hasModifiedAt()
        || request.getMetaSourceCount() > 0
        || request.getMetaAuthorCount() > 0
        || request.getMetaCategoryCount() > 0
        || request.hasMetaPublishedAt();

    if (!hasFilters) {
      return null;
    }

    long fromMs = request.hasModifiedAt() ? request.getModifiedAt().getFromMs() : 0;
    long toMs = request.hasModifiedAt() ? request.getModifiedAt().getToMs() : 0;
    Long metaPubFrom = request.hasMetaPublishedAt() && request.getMetaPublishedAt().getFromMs() > 0
        ? request.getMetaPublishedAt().getFromMs() : null;
    Long metaPubTo = request.hasMetaPublishedAt() && request.getMetaPublishedAt().getToMs() > 0
        ? request.getMetaPublishedAt().getToMs() : null;

    // Lowercase metadata values for case-insensitive matching
    List<String> metaSrc = lowerList(request.getMetaSourceList());
    List<String> metaAuth = lowerList(request.getMetaAuthorList());
    List<String> metaCat = lowerList(request.getMetaCategoryList());

    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .mime(List.of())
        .language(List.of())
        .fileKind(request.getFileKindList())
        .mimeBase(List.of())
        .pathPrefix(request.getPathPrefix())
        .modifiedFromMs(fromMs > 0 ? fromMs : null)
        .modifiedToMs(toMs > 0 ? toMs : null)
        .includeChunks(true)
        .entityPersons(request.getEntityPersonsList())
        .entityOrganizations(request.getEntityOrganizationsList())
        .entityLocations(request.getEntityLocationsList())
        .metaSource(metaSrc)
        .metaAuthor(metaAuth)
        .metaCategory(metaCat)
        .metaPublishedFromMs(metaPubFrom)
        .metaPublishedToMs(metaPubTo)
        .build();
  }

  // ==================== RAG-005: Virtual Chunks for FULLTEXT_FALLBACK ====================

  /**
   * Virtual chunk record for fallback citation support.
   *
   * <p>Created at retrieval time from full document content, not persisted in the index.
   */
  private record VirtualChunk(
      String docId,
      String content,
      int chunkIndex,
      int chunkTotal,
      int startChar,
      int endChar,
      int startLine,
      int endLine,
      double score) {}

  /**
   * Builds a FULLTEXT_FALLBACK response with virtual chunks for citation support (RAG-005).
   *
   * <p>When chunk search returns 0 hits, this method splits full documents into virtual chunks
   * at retrieval time, scores them by query term overlap, and returns structured chunk metadata.
   * This enables the downstream citation pipeline to produce source attribution for unchunked
   * documents.
   */
  private RetrieveContextResponse buildFallbackWithVirtualChunks(
      String question, Set<String> docIds, int topK,
      long chunksFoundInSearch, String fallbackReason, String retrievalMode) {
    commitOps.maybeRefresh();
    var result = chunkSearchOps.searchFullDocsForDocs(question, docIds, topK);

    if (result.hits().isEmpty()) {
      return RetrieveContextResponse.newBuilder()
          .setContext("")
          .setUsedChunks(false)
          .setChunksFound(
              (int) Math.min(Integer.MAX_VALUE, Math.max(0L, chunksFoundInSearch)))
          .setRetrievalMode(retrievalMode)
          .setRetrievalModeReason(fallbackReason)
          .setContextTruncated(false)
          .setQuality(io.justsearch.ipc.QualitySignals.getDefaultInstance())
          .build();
    }

    Set<String> queryTerms = extractQueryTerms(question);
    List<VirtualChunk> allChunks = new ArrayList<>();

    for (var hit : result.hits()) {
      String content = hit.fields().get(SchemaFields.CONTENT);
      if (content == null || content.isBlank()) {
        content = hit.fields().get("content");
      }
      if (content == null || content.isBlank()) continue;

      List<String> chunks = ChunkSplitter.split(content);
      int chunkTotal = chunks.size();
      int searchFrom = 0;

      for (int i = 0; i < chunks.size(); i++) {
        String chunkText = chunks.get(i);
        int startChar = content.indexOf(chunkText, searchFrom);
        if (startChar < 0) startChar = searchFrom;
        int endChar = startChar + chunkText.length();
        searchFrom = startChar + 1;

        int startLine = countNewlinesBefore(content, startChar) + 1;
        int endLine = startLine + countNewlinesInRange(content, startChar, endChar);
        double score = scoreByTermOverlap(chunkText, queryTerms);

        allChunks.add(new VirtualChunk(
            hit.docId(), chunkText, i, chunkTotal,
            startChar, endChar, startLine, endLine, score));
      }
    }

    // Sort by score descending so best matches come first
    allChunks.sort((a, b) -> Double.compare(b.score(), a.score()));

    // 385: Batch-fetch parent metadata for source-aware labels (#3, issue #10)
    List<String> fallbackDocIds = allChunks.stream()
        .map(VirtualChunk::docId)
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .collect(java.util.stream.Collectors.toList());
    Map<String, Map<String, String>> fallbackParentMeta =
        (documentFieldOps != null && !fallbackDocIds.isEmpty())
            ? safeGetDocumentFieldsBatch(fallbackDocIds)
            : Map.of();

    // Build context with budget
    int maxChars = 200_000;
    ContextBudgeter budgeter = new ContextBudgeter(maxChars);
    List<VirtualChunk> usedChunks = new ArrayList<>();
    boolean contextTruncated = false;

    for (var vc : allChunks) {
      ContextBudgeter.AppendResult r =
          budgeter.appendSection(
              buildContextLabel(fallbackParentMeta.get(vc.docId()), vc.docId()), vc.content());
      if (r == ContextBudgeter.AppendResult.APPENDED
          || r == ContextBudgeter.AppendResult.APPENDED_TRUNCATED) {
        usedChunks.add(vc);
      }
      if (r == ContextBudgeter.AppendResult.STOPPED_BUDGET
          || r == ContextBudgeter.AppendResult.APPENDED_TRUNCATED) {
        contextTruncated = true;
        break;
      }
    }

    log.debug(
        "RAG fallback: virtual-chunked {} docs into {} chunks, using {} for context",
        result.hits().size(), allChunks.size(), usedChunks.size());

    // Quality signals for fallback (uses term-overlap scores, no CE)
    float bestScore = usedChunks.isEmpty() ? 0f : (float) usedChunks.getFirst().score();
    float gap = usedChunks.size() >= 2
        ? (float) (usedChunks.get(0).score() - usedChunks.get(1).score()) : bestScore;

    // Build response with virtual chunk metadata
    RetrieveContextResponse.Builder out =
        RetrieveContextResponse.newBuilder()
            .setContext(budgeter.build())
            .setUsedChunks(true)
            .setChunksFound(allChunks.size())
            .setRetrievalMode(retrievalMode)
            .setRetrievalModeReason(fallbackReason)
            .setContextTruncated(contextTruncated)
            .setQuality(io.justsearch.ipc.QualitySignals.newBuilder()
                .setBestChunkScore(bestScore)
                .setScoreGap(gap)
                .setRetrievalCoverage(0f)
                .setChunksConsidered(allChunks.size())
                .setChunksIncluded(usedChunks.size())
                .build());

    for (var vc : usedChunks) {
      // Tempdoc 561 P-A5: producer-owned word boundary (see clampExcerptToWordBoundary).
      String excerpt = clampExcerptToWordBoundary(vc.content(), 240);
      out.addChunks(
          ContextChunk.newBuilder()
              .setParentDocId(vc.docId() == null ? "" : vc.docId())
              .setChunkIndex(vc.chunkIndex())
              .setChunkTotal(Math.max(1, vc.chunkTotal()))
              .setStartChar(vc.startChar())
              .setEndChar(vc.endChar())
              .setScore((float) vc.score())
              .setExcerpt(excerpt)
              .setStartLine(vc.startLine())
              .setEndLine(vc.endLine())
              .build());
    }

    // Populate structured sections for citation linkage
    var sections = budgeter.sections();
    for (int i = 0; i < sections.size(); i++) {
      var section = sections.get(i);
      out.addSections(
          ContextSection.newBuilder()
              .setSourceLabel(section.sourceLabel())
              .setContent(section.content())
              .setTruncated(section.truncated())
              .setSectionIndex(section.sectionIndex())
              .setChunkIndex(i)
              .build());
    }

    return out.build();
  }

  /** Extracts lowercase query terms for scoring, filtering out single-char noise. */
  private static Set<String> extractQueryTerms(String question) {
    Set<String> terms = new HashSet<>();
    for (String word : question.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
      String trimmed = word.replaceAll("[^a-z0-9]", "");
      if (trimmed.length() > 1) terms.add(trimmed);
    }
    return terms;
  }

  /** Scores a chunk by fraction of query terms present (case-insensitive). */
  private static double scoreByTermOverlap(String text, Set<String> queryTerms) {
    if (queryTerms.isEmpty()) return 0.0;
    String lower = text.toLowerCase(java.util.Locale.ROOT);
    int matches = 0;
    for (String term : queryTerms) {
      if (lower.contains(term)) matches++;
    }
    return (double) matches / queryTerms.size();
  }

  /** Counts newlines in content before the given position. */
  private static int countNewlinesBefore(String content, int pos) {
    int count = 0;
    int limit = Math.min(pos, content.length());
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') count++;
    }
    return count;
  }

  /** Counts newlines in content within [start, end). */
  private static int countNewlinesInRange(String content, int start, int end) {
    int count = 0;
    int limit = Math.min(end, content.length());
    for (int i = Math.max(0, start); i < limit; i++) {
      if (content.charAt(i) == '\n') count++;
    }
    return count;
  }

  // ==================== Phase 5: Chunk Reranking ====================

  /**
   * Returns true if chunk reranking should be applied.
   *
   * <p>Only reranks for BM25 mode; hybrid mode already has semantic ranking via embeddings.
   */
  private boolean shouldRerankChunks(String retrievalMode) {
    var config = chunkRerankerConfig;
    return config != null
        && config.isReady()
        && "BM25".equalsIgnoreCase(retrievalMode)
        && getChunkReranker() != null;
  }

  /**
   * Determines whether to rerank before or after diversification.
   *
   * <p>Phase 5 Gap 5: Adaptive order based on GPU availability:
   * <ul>
   *   <li>AUTO (default): Rerank first if GPU available, else diversify first
   *   <li>BEFORE_DIVERSIFY: Always rerank first (GPU-style, higher quality)
   *   <li>AFTER_DIVERSIFY: Always diversify first (CPU-style, bounded latency)
   * </ul>
   *
   * @return true if reranking should happen before diversification
   */
  private boolean determineRerankOrder() {
    var config = chunkRerankerConfig;
    if (config == null) {
      return false; // No config, default to diversify-first
    }
    return switch (config.order()) {
      case BEFORE_DIVERSIFY -> true;
      case AFTER_DIVERSIFY -> false;
      case AUTO -> {
        CrossEncoderReranker reranker = getChunkReranker();
        yield reranker != null && reranker.isGpuAvailable();
      }
    };
  }

  /**
   * Reranks chunks using the cross-encoder reranker (Phase 5).
   *
   * <p>This improves RAG context quality by semantically scoring (query, chunk) pairs.
   * Falls back to original order if reranking fails or exceeds deadline.
   *
   * <p>Phase 5 Gap 5: Candidates are capped based on GPU availability:
   * <ul>
   *   <li>GPU available: cap to {@code maxGpuCandidates} (default 50)
   *   <li>CPU only: cap to {@code topK} (default 10)
   * </ul>
   *
   * @param question the user's question
   * @param hits the chunk hits (may be full candidate set or diversified, depending on order)
   * @return reranked hits, or original order if skipped
   */
  private ChunkRerankResult rerankChunks(
      String question, List<LuceneRuntimeTypes.SearchHit> hits) {

    var config = chunkRerankerConfig;
    if (config == null || hits.size() < config.minHitsThreshold()) {
      return ChunkRerankResult.unchanged(hits);
    }

    CrossEncoderReranker reranker = getChunkReranker();
    if (reranker == null) {
      return ChunkRerankResult.unchanged(hits);
    }

    // Phase 5 Gap 5: Cap candidates based on GPU availability
    // GPU can handle more candidates efficiently; CPU needs tighter bounds
    int maxCandidates = reranker.isGpuAvailable() ? config.maxGpuCandidates() : config.topK();
    List<LuceneRuntimeTypes.SearchHit> candidates = hits;
    boolean wasCapped = false;
    if (hits.size() > maxCandidates) {
      candidates = hits.subList(0, maxCandidates);
      wasCapped = true;
      log.debug(
          "Chunk reranking: capping candidates from {} to {} (gpuAvailable={})",
          hits.size(),
          maxCandidates,
          reranker.isGpuAvailable());
    }

    // Extract chunk texts for reranking
    List<String> chunkTexts = new ArrayList<>(candidates.size());
    for (var hit : candidates) {
      String content = hit.fields().get(SchemaFields.CHUNK_CONTENT);
      chunkTexts.add(content != null ? content : "");
    }

    // Rerank with deadline budget.
    // Phase 6 / 6.8: the `search/rerank` span now lives inside
    // CrossEncoderReranker.rerank so every rerank caller (not just
    // chunk rerank) emits a uniform span. Remove the local wrap here.
    try {
      var rerankResult = reranker.rerank(question, chunkTexts, config.deadlineBudgetMs());

      if (rerankResult.skipped()) {
        log.debug(
            Markers.append("reason_code", "rerank_skipped_deadline")
                .and(Markers.append("latency_ms", rerankResult.latencyMs())),
            "Chunk reranking skipped after {}ms (deadline exceeded)",
            rerankResult.latencyMs());
        return ChunkRerankResult.unchanged(hits);
      }

      // Reorder candidates and preserve CE scores in sorted order
      List<LuceneRuntimeTypes.SearchHit> rerankedHits = new ArrayList<>(candidates.size());
      List<Float> rerankedScores = new ArrayList<>(candidates.size());
      for (int idx : rerankResult.sortedIndices()) {
        rerankedHits.add(candidates.get(idx));
        rerankedScores.add(rerankResult.scores().get(idx));
      }

      log.debug(
          "Chunk reranking completed: {} candidates in {}ms (capped={})",
          candidates.size(),
          rerankResult.latencyMs(),
          wasCapped);
      return new ChunkRerankResult(rerankedHits, rerankedScores);

    } catch (Exception e) {
      log.warn("Chunk reranking failed, using original order", e);
      return ChunkRerankResult.unchanged(hits);
    }
  }

  /**
   * Returns the shared search reranker (GPU-capable) if the composition root wired it; else
   * {@code null}. Tempdoc 397 §14.26 T2-E1: pure getter — the former lazy CPU-only fallback
   * (via the now-deleted {@code OrtSessionAssembler.composeRerankFallback}) is gone. If the
   * composition root didn't wire a reranker, chunk reranking is disabled (returns null, caller
   * falls back to original order).
   */
  private CrossEncoderReranker getChunkReranker() {
    return searchReranker;
  }

  // ==================== Phase 6: Chunk Vector Coverage ====================

  /**
   * Checks if chunk vector coverage is sufficient for hybrid retrieval.
   *
   * <p>Fail-closed: returns false if coverage is incomplete or check fails.
   * Requires 95%+ of chunks to have vectors before enabling chunk-level hybrid.
   *
   * @return true if chunk vectors are ready for use
   */
  private boolean isChunkVectorCoverageReady() {
    try {
      int totalChunks = indexCountOps.countByField(SchemaFields.IS_CHUNK, "true");
      if (totalChunks == 0) {
        return false; // No chunks indexed
      }

      int completedChunks =
          indexCountOps.countByField(
              SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);

      double coverage = (double) completedChunks / totalChunks;
      if (coverage >= CHUNK_VECTOR_COVERAGE_THRESHOLD) {
        return true;
      }

      log.debug(
          "Chunk vector coverage: {}/{} ({}%), threshold={}%",
          completedChunks,
          totalChunks,
          String.format("%.1f", coverage * 100),
          String.format("%.1f", CHUNK_VECTOR_COVERAGE_THRESHOLD * 100));
      return false;

    } catch (Exception e) {
      log.debug("Chunk vector coverage check failed: {}", e.getMessage());
      return false; // Fail-closed
    }
  }

  // ==================== Diversification ====================

  /**
   * Selects the final chunk set using the configured diversification strategy.
   */
  private List<LuceneRuntimeTypes.SearchHit> diversifyChunks(
      String question,
      float[] queryVector,
      List<LuceneRuntimeTypes.SearchHit> hits,
      int targetK,
      boolean allowQueryEmbeddings) {

    String mode = resolvedConfigSupplier.get().rag().diversifyMode();
    if ("mmr".equals(mode)) {
      return diversifyByMmr(question, queryVector, hits, targetK, allowQueryEmbeddings);
    }
    return diversifyByPosition(hits, targetK);
  }

  /**
   * Diversifies chunk selection using Maximal Marginal Relevance (MMR) over embeddings.
   *
   * <p>Falls back to position-based diversification when embeddings are unavailable or fail.
   */
  private List<LuceneRuntimeTypes.SearchHit> diversifyByMmr(
      String question,
      float[] queryVector,
      List<LuceneRuntimeTypes.SearchHit> hits,
      int targetK,
      boolean allowQueryEmbeddings) {

    if (hits.size() <= targetK) {
      return hits;
    }

    if (!embeddingProvider.isAvailable() || !allowQueryEmbeddings) {
      log.debug(
          "MMR diversification requested but embeddings are unavailable;"
              + " using position diversification");
      return diversifyByPosition(hits, targetK);
    }

    int maxCandidates = Math.max(targetK, resolvedConfigSupplier.get().rag().mmrMaxCandidates());
    List<LuceneRuntimeTypes.SearchHit> candidates = hits;
    if (hits.size() > maxCandidates) {
      candidates = hits.subList(0, maxCandidates);
    }

    float[] qv = queryVector;
    if (qv == null || qv.length == 0) {
      try {
        qv = embeddingProvider.embedQuery(question);
      } catch (RuntimeException e) {
        log.debug("MMR query embedding failed: {}", e.getMessage());
        qv = null;
      }
    }

    List<LuceneRuntimeTypes.SearchHit> embeddedHits = new ArrayList<>(candidates.size());
    List<float[]> vectors = new ArrayList<>(candidates.size());
    for (var hit : candidates) {
      String content = hit.fields().get(SchemaFields.CHUNK_CONTENT);
      if (content == null || content.isBlank()) continue;
      try {
        float[] v = embeddingProvider.embedDocument(content);
        if (v == null || v.length == 0) continue;
        embeddedHits.add(hit);
        vectors.add(v);
      } catch (RuntimeException e) {
        log.debug("MMR chunk embedding failed: {}", e.getMessage());
      }
    }

    if (embeddedHits.size() <= targetK) {
      if (!embeddedHits.isEmpty()) {
        return embeddedHits;
      }
      return diversifyByPosition(hits, targetK);
    }

    // Use cosine(query, chunk) as relevance when we have a query vector; otherwise fall back to
    // rank.
    List<Double> relevance = new ArrayList<>(embeddedHits.size());
    boolean canUseQuerySim = qv != null && qv.length == vectors.get(0).length;
    if (canUseQuerySim) {
      for (float[] v : vectors) {
        relevance.add(VectorUtils.cosine(qv, v));
      }
    } else {
      for (int i = 0; i < embeddedHits.size(); i++) {
        relevance.add((double) (embeddedHits.size() - i));
      }
    }

    double lambda = resolvedConfigSupplier.get().rag().mmrLambda();
    List<Integer> selectedIdx = MmrSelector.select(lambda, relevance, vectors, targetK);
    if (selectedIdx.isEmpty()) {
      return diversifyByPosition(hits, targetK);
    }

    boolean[] chosen = new boolean[embeddedHits.size()];
    List<LuceneRuntimeTypes.SearchHit> selected = new ArrayList<>(targetK);
    for (int idx : selectedIdx) {
      if (idx < 0 || idx >= embeddedHits.size()) continue;
      chosen[idx] = true;
      selected.add(embeddedHits.get(idx));
    }

    // Fill any remaining slots from embedded candidates, then from full candidate list.
    if (selected.size() < targetK) {
      for (int i = 0; i < embeddedHits.size() && selected.size() < targetK; i++) {
        if (!chosen[i]) {
          selected.add(embeddedHits.get(i));
        }
      }
    }
    if (selected.size() < targetK) {
      for (var hit : candidates) {
        if (selected.size() >= targetK) break;
        if (!selected.contains(hit)) {
          selected.add(hit);
        }
      }
    }

    log.debug(
        "MMR diversification: {} candidates (embedded {}), selected {} (lambda={})",
        candidates.size(),
        embeddedHits.size(),
        selected.size(),
        String.format("%.2f", lambda));

    return selected;
  }

  /**
   * Diversifies chunk selection to ensure coverage from different document positions.
   *
   * <p>Groups chunks by position (beginning/middle/end) and selects top chunks from each group.
   * This prevents the common BM25 failure mode where all top results come from one section.
   *
   * @param hits the retrieved chunks (already sorted by BM25 score)
   * @param targetK the target number of chunks to return
   * @return diversified list of chunks
   */
  private List<LuceneRuntimeTypes.SearchHit> diversifyByPosition(
      List<LuceneRuntimeTypes.SearchHit> hits, int targetK) {

    if (hits.size() <= targetK) {
      return hits; // No diversification needed
    }

    // Group chunks by position within their document
    List<LuceneRuntimeTypes.SearchHit> beginning = new ArrayList<>();
    List<LuceneRuntimeTypes.SearchHit> middle = new ArrayList<>();
    List<LuceneRuntimeTypes.SearchHit> end = new ArrayList<>();

    for (var hit : hits) {
      String indexStr = hit.fields().get(SchemaFields.CHUNK_INDEX);
      String totalStr = hit.fields().get(SchemaFields.CHUNK_TOTAL);

      int index = ParseUtils.parseIntSafe(indexStr, 0);
      int total = ParseUtils.parseIntSafe(totalStr, 1);

      if (total <= 1) {
        // Single-chunk document: treat as middle (general content)
        middle.add(hit);
      } else {
        // Determine position: first 20% = beginning, last 20% = end, rest = middle
        double position = (double) index / total;
        if (position < 0.2) {
          beginning.add(hit);
        } else if (position > 0.8) {
          end.add(hit);
        } else {
          middle.add(hit);
        }
      }
    }

    // Select balanced representation from each group
    // Prioritize: middle (main content) > beginning (intro) > end (conclusion)
    int perGroup = Math.max(1, targetK / 3);
    int remaining = targetK;

    List<LuceneRuntimeTypes.SearchHit> selected = new ArrayList<>();
    Set<LuceneRuntimeTypes.SearchHit> selectedSet = new HashSet<>();

    // Take from middle first (usually the most important content)
    int fromMiddle = Math.min(perGroup + (targetK % 3), middle.size());
    for (int i = 0; i < fromMiddle && remaining > 0; i++) {
      selected.add(middle.get(i));
      selectedSet.add(middle.get(i));
      remaining--;
    }

    // Then from beginning
    int fromBeginning = Math.min(perGroup, beginning.size());
    for (int i = 0; i < fromBeginning && remaining > 0; i++) {
      selected.add(beginning.get(i));
      selectedSet.add(beginning.get(i));
      remaining--;
    }

    // Then from end
    int fromEnd = Math.min(perGroup, end.size());
    for (int i = 0; i < fromEnd && remaining > 0; i++) {
      selected.add(end.get(i));
      selectedSet.add(end.get(i));
      remaining--;
    }

    // If we still need more, fill from any group that has extras
    if (remaining > 0) {
      for (var hit : hits) {
        if (!selectedSet.contains(hit) && remaining > 0) {
          selected.add(hit);
          selectedSet.add(hit);
          remaining--;
        }
      }
    }

    log.debug(
        "Coverage diversification: {} beginning, {} middle, {} end"
            + " -> {} selected from {} candidates",
        fromBeginning,
        fromMiddle,
        fromEnd,
        selected.size(),
        hits.size());

    return selected;
  }

  private static List<String> lowerList(List<String> values) {
    if (values == null || values.isEmpty()) return null;
    List<String> result = new ArrayList<>(values.size());
    for (String v : values) {
      if (v != null && !v.isBlank()) result.add(v.toLowerCase(java.util.Locale.ROOT));
    }
    return result.isEmpty() ? null : result;
  }

  // ==================== Two-stage filter helpers ====================

  /** Returns true if the filters contain any document-level predicates (not stored on chunks). */
  private static boolean hasDocLevelFilters(LuceneRuntimeTypes.RuntimeSearchFilters f) {
    if (f == null) return false;
    return nonEmpty(f.entityPersons()) || nonEmpty(f.entityOrganizations())
        || nonEmpty(f.entityLocations()) || nonEmpty(f.metaSource())
        || nonEmpty(f.metaAuthor()) || nonEmpty(f.metaCategory())
        || f.metaPublishedFromMs() != null || f.metaPublishedToMs() != null
        || (f.pathPrefix() != null && !f.pathPrefix().isBlank())
        || f.modifiedFromMs() != null || f.modifiedToMs() != null;
  }

  private static boolean nonEmpty(List<?> list) {
    return list != null && !list.isEmpty();
  }

  /** Returns a copy of the filters with includeChunks overridden. */
  private static LuceneRuntimeTypes.RuntimeSearchFilters withIncludeChunks(
      LuceneRuntimeTypes.RuntimeSearchFilters f, boolean val) {
    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .mime(f.mime())
        .language(f.language())
        .fileKind(f.fileKind())
        .mimeBase(f.mimeBase())
        .pathPrefix(f.pathPrefix())
        .modifiedFromMs(f.modifiedFromMs())
        .modifiedToMs(f.modifiedToMs())
        .includeChunks(val)
        .entityPersons(f.entityPersons())
        .entityOrganizations(f.entityOrganizations())
        .entityLocations(f.entityLocations())
        .metaSource(f.metaSource())
        .metaAuthor(f.metaAuthor())
        .metaCategory(f.metaCategory())
        .metaPublishedFromMs(f.metaPublishedFromMs())
        .metaPublishedToMs(f.metaPublishedToMs())
        .build();
  }

  private static <T> Set<T> intersectSets(Set<T> a, Set<T> b) {
    Set<T> smaller = a.size() <= b.size() ? a : b;
    Set<T> larger = a.size() <= b.size() ? b : a;
    Set<T> result = new java.util.LinkedHashSet<>();
    for (T item : smaller) {
      if (larger.contains(item)) result.add(item);
    }
    return result;
  }

  private RetrieveContextResponse buildEmptyFilterResponse() {
    var emptyResult = new ChunkContextResult(
        "", List.of(), 0, "FILTERED_EMPTY", "NO_MATCHING_PARENTS", false, List.of(),
        RagQualitySignals.EMPTY);
    return buildChunkResponse(emptyResult);
  }

  // ==================== 385: Budget Loop Helper ====================

  /** Tri-state result for the budgeter-agnostic append operation. */
  private enum AppendOutcome { APPENDED, APPENDED_AND_STOPPED, SKIPPED }

  /** Maps {@link TokenAwareBudgeter.AppendResult} to the budgeter-agnostic tri-state. */
  private static AppendOutcome mapAppendResult(TokenAwareBudgeter.AppendResult r) {
    return switch (r) {
      case APPENDED -> AppendOutcome.APPENDED;
      case APPENDED_TRUNCATED, STOPPED_BUDGET -> AppendOutcome.APPENDED_AND_STOPPED;
      case SKIPPED_EMPTY -> AppendOutcome.SKIPPED;
    };
  }

  /** Maps {@link ContextBudgeter.AppendResult} to the budgeter-agnostic tri-state. */
  private static AppendOutcome mapAppendResult(ContextBudgeter.AppendResult r) {
    return switch (r) {
      case APPENDED -> AppendOutcome.APPENDED;
      case APPENDED_TRUNCATED, STOPPED_BUDGET -> AppendOutcome.APPENDED_AND_STOPPED;
      case SKIPPED_EMPTY -> AppendOutcome.SKIPPED;
    };
  }

  /**
   * 385: Shared budgeting loop with per-parent diversity cap (#7) and overflow backfill.
   *
   * <p>Used by both token-aware and char-based context assembly paths. The append function
   * abstracts the budgeter type; the caller provides a lambda that calls the concrete budgeter's
   * {@code appendSection(label, content)} and maps the result via {@link #mapAppendResult}.
   *
   * @return true if the context was truncated (budget exhausted)
   */
  private boolean runBudgetLoop(
      List<LuceneRuntimeTypes.SearchHit> hits,
      List<LuceneRuntimeTypes.SearchHit> used,
      Map<String, Map<String, String>> parentMeta,
      int maxChunksPerArticle,
      java.util.function.BiFunction<String, String, AppendOutcome> appendFn) {

    Map<String, Integer> chunksPerParent = new HashMap<>();
    List<LuceneRuntimeTypes.SearchHit> overflow = new ArrayList<>();
    boolean truncated = false;

    // Primary pass: enforce per-parent diversity cap
    for (var hit : hits) {
      String chunkContent = hit.fields().get(SchemaFields.CHUNK_CONTENT);
      String parentDocId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      if (chunkContent == null || chunkContent.isBlank()) continue;

      if (parentDocId != null
          && chunksPerParent.getOrDefault(parentDocId, 0) >= maxChunksPerArticle) {
        overflow.add(hit);
        continue;
      }

      String label = buildContextLabel(parentMeta.get(parentDocId), parentDocId);
      AppendOutcome outcome = appendFn.apply(label, chunkContent);
      if (outcome == AppendOutcome.APPENDED || outcome == AppendOutcome.APPENDED_AND_STOPPED) {
        used.add(hit);
        if (parentDocId != null) chunksPerParent.merge(parentDocId, 1, Integer::sum);
      }
      if (outcome == AppendOutcome.APPENDED_AND_STOPPED) {
        truncated = true;
        break;
      }
    }

    // Backfill from overflow if budget remains.
    // Intentionally does NOT enforce per-parent cap — the cap governs the primary pass
    // (diversity first), while backfill uses remaining budget efficiently from any source.
    if (!truncated && !overflow.isEmpty()) {
      for (var hit : overflow) {
        String chunkContent = hit.fields().get(SchemaFields.CHUNK_CONTENT);
        String parentDocId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
        if (chunkContent == null || chunkContent.isBlank()) continue;
        String label = buildContextLabel(parentMeta.get(parentDocId), parentDocId);
        AppendOutcome outcome = appendFn.apply(label, chunkContent);
        if (outcome == AppendOutcome.APPENDED || outcome == AppendOutcome.APPENDED_AND_STOPPED) {
          used.add(hit);
        }
        if (outcome == AppendOutcome.APPENDED_AND_STOPPED) {
          truncated = true;
          break;
        }
      }
    }

    return truncated;
  }

  // ==================== 385: Context Label Helpers ====================

  /**
   * Batch-fetch parent document metadata (meta_source, title) for context labels. Returns an empty
   * map when {@link #documentFieldOps} is null (backward-compatible mode).
   */
  private Map<String, Map<String, String>> fetchParentMetadata(
      List<LuceneRuntimeTypes.SearchHit> hits) {
    if (documentFieldOps == null || hits.isEmpty()) return Map.of();
    Set<String> parentDocIds = new HashSet<>();
    for (var hit : hits) {
      String pid = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      if (pid != null && !pid.isBlank()) parentDocIds.add(pid);
    }
    if (parentDocIds.isEmpty()) return Map.of();
    try {
      return documentFieldOps.getDocumentFieldsBatch(
          new ArrayList<>(parentDocIds),
          Set.of(SchemaFields.META_SOURCE, SchemaFields.TITLE));
    } catch (Exception e) {
      log.debug("385: Failed to fetch parent metadata for context labels: {}", e.getMessage());
      return Map.of();
    }
  }

  /** Safe batch lookup — used by fallback path where hits are VirtualChunk, not SearchHit. */
  private Map<String, Map<String, String>> safeGetDocumentFieldsBatch(List<String> docIds) {
    try {
      return documentFieldOps.getDocumentFieldsBatch(
          docIds, Set.of(SchemaFields.META_SOURCE, SchemaFields.TITLE));
    } catch (Exception e) {
      log.debug("385: Failed to fetch parent metadata for fallback labels: {}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Build a human-readable context label for RAG sections.
   *
   * <ul>
   *   <li>Both source + title present: {@code "TechCrunch \u2014 \"Twitch ends 70/30 split\""}
   *   <li>Source only: {@code "TechCrunch \u2014 twitch-subscription-changes.txt"}
   *   <li>Neither: {@code "twitch-subscription-changes.txt"} (current behavior)
   * </ul>
   */
  private static String buildContextLabel(Map<String, String> parentFields, String fallbackDocId) {
    String filename = ParseUtils.extractFilename(fallbackDocId);

    if (parentFields == null) return filename;

    String source = parentFields.get(SchemaFields.META_SOURCE);
    String title = parentFields.get(SchemaFields.TITLE);

    if (source != null && !source.isBlank() && title != null && !title.isBlank()) {
      return source + " \u2014 \"" + title + "\"";
    } else if (source != null && !source.isBlank()) {
      return source + " \u2014 " + filename;
    } else {
      return filename;
    }
  }
}
