/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.QueryType;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.EntityFacetVariants;
import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchSort;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.TimeRangeMs;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that maps the Knowledge HTTP API contract (app-api DTOs) to/from gRPC proto DTOs.
 *
 * <p>UI controllers should not import proto DTOs directly; this class is the intended boundary.
 */
final class KnowledgeSearchEngine {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeHttpApiAdapter.class);
  private static final Tracer tracer =
      GlobalOpenTelemetry.getTracer("io.justsearch.search");


  /** Target length for query-focused snippets passed to the reranker (~512 tokens). */
  private static final int RERANK_SNIPPET_LENGTH = 1500;

  /** Max tokens the LLM may generate for query expansion. */
  private static final int EXPANSION_MAX_TOKENS = 64;

  /** Hard budget (ms) for LLM expansion before falling back to base search results. */
  private static final long EXPANSION_BUDGET_MS = 1500L;

  /** Compiled pattern for validating expansion tokens (alphabetic-only, no digits or punctuation). */
  private static final Pattern ALPHA_ONLY = Pattern.compile("[a-zA-Z]+");

  /** System prompt for morphological query expansion. */
  private static final String EXPANSION_SYSTEM_PROMPT =
      "Given a search query, list morphological variants of each word: plural, past tense, "
          + "past participle, gerund, and nominalization forms. Output only the variant words, "
          + "space-separated. Include only inflectional variants sharing the same root word — "
          + "no semantic synonyms, no explanations.";

  // 250 Phase 5a: Head-side pipeline fallback counters.
  // Lazy-init via GlobalOpenTelemetry (not set at class-load time).
  private static volatile LongCounter rerankerSkippedCounter;
  private static volatile LongCounter lambdamartSkippedCounter;

  private static LongCounter rerankerSkipped() {
    LongCounter c = rerankerSkippedCounter;
    if (c == null) {
      Meter m = GlobalOpenTelemetry.get().getMeter("io.justsearch.search");
      c = m.counterBuilder("search.reranker_skipped.total").build();
      rerankerSkippedCounter = c;
    }
    return c;
  }

  private static LongCounter lambdamartSkipped() {
    LongCounter c = lambdamartSkippedCounter;
    if (c == null) {
      Meter m = GlobalOpenTelemetry.get().getMeter("io.justsearch.search");
      c = m.counterBuilder("search.lambdamart_skipped.total").build();
      lambdamartSkippedCounter = c;
    }
    return c;
  }


  /**
   * Returns true when query expansion should be started for this request.
   *
   * <p>Expansion fires when the pipeline's {@link PipelineConfig#expansionEnabled()} flag is set,
   * with SIMPLE syntax, a non-blank non-paginated query, and an available AI service. The flag is
   * independent of retrieval mode — presets set it based on whether semantic recall is already
   * provided (TEXT/SPLADE=true, HYBRID/VECTOR=false), but custom configs can override.
   *
   * <p>Package-private for contract testing.
   */
  static boolean isExpansionEligible(
      PipelineConfig config,
      SearchQuerySyntax syntax,
      String query,
      String cursor,
      boolean aiAvailable,
      QueryType queryType) {
    // 306: skip expansion for navigational/exact queries — adding morphological variants
    // to a filename or quoted phrase dilutes precision with no recall benefit.
    if (queryType == QueryType.NAVIGATIONAL || queryType == QueryType.EXACT_MATCH) return false;
    // 256-I2: expansion gated by explicit PipelineConfig flag instead of !denseEnabled()
    return config.expansionEnabled()
        && syntax == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE
        && !query.isBlank()
        && (cursor == null || cursor.isBlank())
        && aiAvailable;
  }

  /**
   * Returns true when cross-encoder reranking should be applied to the result set.
   *
   * <p>Cross-encoder eligibility is driven by the {@link PipelineConfig#crossEncoderEnabled()} flag,
   * which is set uniformly for all presets in {@link SearchPipelinePresets#expandPreset} based on {@link
   * RerankerConfig#enabled()} (256-F1). Custom PipelineConfigs can override independently.
   *
   * <p>258-B1: When the index average document length exceeds {@link
   * RerankerConfig#maxAvgDocLengthChars()}, cross-encoder is auto-disabled. MiniLM-L6-v2 truncates
   * at 512 tokens; longer documents lose most content, causing catastrophic ranking degradation.
   *
   * <p>Package-private for contract testing.
   */
  static boolean isRerankerEligible(
      PipelineConfig pipeline, RerankerConfig config, int resultCount,
      long avgContentLengthChars, QueryType queryType) {
    // 306: skip cross-encoder for navigational queries — the user is searching for a known
    // file by name/path; BM25 term matching is sufficient and CE adds 60-100ms for no gain.
    if (queryType == QueryType.NAVIGATIONAL) return false;
    if (!config.enabled() || resultCount < config.minHitsThreshold()) return false;
    if (!pipeline.crossEncoderEnabled()) return false;
    // 258-B1: auto-disable cross-encoder when documents are too long for the model
    long maxLen = config.maxAvgDocLengthChars();
    if (maxLen > 0 && avgContentLengthChars > maxLen) return false;
    return true;
  }

  @SuppressWarnings("unused") // Called from KnowledgeHttpApiAdapterHarmfulCombinationsTest
  static boolean isLambdaMartEligible(
      PipelineConfig pipeline, RerankerService rerankerService, int resultCount) {
    if (!pipeline.lambdamartEnabled()) return false;
    if (rerankerService == null || !rerankerService.isLoaded()) return false;
    return resultCount > 0;
  }

  /** 306: reads query classification enabled flag from ConfigStore (default: true). */
  private static boolean isQueryClassificationEnabled() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs == null || cs.get().search().queryClassificationEnabled();
  }

  private final KnowledgeServerBootstrap knowledgeServer;
  private final RerankerConfig rerankConfig;
  private final OnlineAiService onlineAiService;
  private final RerankerService lambdaMartReranker;
  private final QueryUnderstandingService quService;
  private final FilterNormalizationService normService;
  private final WorkerStatusCache statusCache;

  KnowledgeSearchEngine(KnowledgeServerBootstrap knowledgeServer) {
    this(knowledgeServer, OnlineAiService.unavailable(), null);
  }

  KnowledgeSearchEngine(
      KnowledgeServerBootstrap knowledgeServer, OnlineAiService onlineAiService) {
    this(knowledgeServer, onlineAiService, null);
  }

  KnowledgeSearchEngine(
      KnowledgeServerBootstrap knowledgeServer,
      OnlineAiService onlineAiService,
      RerankerService lambdaMartReranker) {
    this.knowledgeServer = Objects.requireNonNull(knowledgeServer, "knowledgeServer");
    this.onlineAiService = Objects.requireNonNull(onlineAiService, "onlineAiService");
    this.lambdaMartReranker = lambdaMartReranker; // nullable
    this.rerankConfig = RerankerConfig.fromEnv();
    this.quService = new QueryUnderstandingService(onlineAiService);
    this.normService = new FilterNormalizationService(onlineAiService);
    this.statusCache = new WorkerStatusCache(knowledgeServer);
    if (rerankConfig.enabled()) {
      log.info("Reranker enabled: topK={}, deadline={}ms, modelPath={}",
          rerankConfig.topK(), rerankConfig.deadlineBudgetMs(), rerankConfig.modelPath());
    }
  }

  // Tempdoc 556: status + facet-snapshot cache live in WorkerStatusCache; delegate.
  public KnowledgeStatus status() {
    return statusCache.status();
  }

  public String getCachedFacetSnapshot() {
    return statusCache.getCachedFacetSnapshot();
  }

  public void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    statusCache.setWorkerCapability(cap);
  }

  /**
   * 360: Returns whether the reranker config is ready. Used by
   * {@link io.justsearch.app.services.gpl.GplJobCoordinator} to check if remote reranking
   * is available (the Worker hosts the model).
   */
  public boolean isRerankerConfigured() {
    return RerankerConfig.fromEnv().isReady();
  }



  public KnowledgeSearchResponse search(KnowledgeSearchRequest req) {
    Objects.requireNonNull(req, "req");

    // 250 Phase 5c: Root span for the entire search pipeline
    Span searchSpan =
        tracer
            .spanBuilder("search")
            .setAttribute(
                "search.query_length",
                (long) (req.query() == null ? 0 : req.query().length()))
            .startSpan();
    Scope searchScope = searchSpan.makeCurrent();
    try {
      KnowledgeSearchResponse resp = doSearch(req, searchSpan);
      // 553 Phase 4a: project the canonical trace onto the root span (telemetry = a projection).
      searchSpan.setAllAttributes(SearchTraceSpanProjection.attributesOf(resp.searchTrace()));
      return resp;
    } catch (Exception e) {
      searchSpan.setStatus(StatusCode.ERROR, e.getMessage());
      searchSpan.recordException(e);
      throw e;
    } finally {
      searchScope.close();
      searchSpan.end();
    }
  }

  private KnowledgeSearchResponse doSearch(
      KnowledgeSearchRequest req, Span searchSpan) {
    long doSearchStartNs = System.nanoTime();
    RerankerConfig rerankConfig = RerankerConfig.fromEnv();

    // 363: Refresh facet snapshot for QU grounding (non-blocking, cached with TTL)
    statusCache.refreshFacetSnapshotIfStale();

    RemoteKnowledgeClient client = knowledgeServer.client();

    int requestedLimit = req.limit() == null ? 10 : Math.max(1, req.limit());
    // When reranking is enabled, fetch more candidates to improve reranking quality
    int searchLimit = rerankConfig.isReady()
        ? Math.max(requestedLimit, rerankConfig.topK())
        : requestedLimit;

    // Parse mode and query syntax early for reranking and expansion decisions
    SearchMode searchMode = SearchPipelinePresets.parseModeOrDefault(req.mode());
    SearchQuerySyntax querySyntax = SearchPipelinePresets.parseQuerySyntaxOrDefault(req.querySyntax());
    String queryText = req.query() == null ? "" : req.query();

    // 256: Phase A — derive PipelineConfig from preset or use explicit config from request.
    // Tempdoc 598 R1: when the request expresses NEITHER an explicit pipeline NOR an explicit mode,
    // the default is capability-derived AUTO (the dense leg runs iff the index is embedding-COMPATIBLE),
    // not a static keyword preset — so the main search UI reaches semantic search by default. An
    // explicit mode (incl. "text") or an explicit pipeline is an override and is honored unchanged.
    boolean denseAuto = req.pipeline() == null && (req.mode() == null || req.mode().isBlank());
    PipelineConfig pipelineConfig =
        req.pipeline() != null
            ? req.pipeline()
            : denseAuto
                ? SearchPipelinePresets.autoPreset(rerankConfig)
                : SearchPipelinePresets.expandPreset(searchMode, rerankConfig);

    // 306: Pre-retrieval query classification for CE/expansion gating.
    // Gated by config for A/B eval. When disabled, all queries are INFORMATIONAL (full pipeline).
    // Only apply query-type gating for preset pipelines — explicit PipelineConfig bypasses gating.
    boolean classificationEnabled = isQueryClassificationEnabled();
    boolean explicitPipeline = req.pipeline() != null;
    QueryType queryType = classificationEnabled
        ? QueryClassifier.classify(queryText) : QueryType.INFORMATIONAL;
    QueryType effectiveQueryType = explicitPipeline ? QueryType.INFORMATIONAL : queryType;

    // 385: Deterministic source extraction for structured queries (#10).
    // Synchronous (~0ms, regex against cached vocabulary). Results used by Phase 2/3 items.
    StructuredQueryAnalyzer.StructuredQueryAnalysis structuredAnalysis =
        StructuredQueryAnalyzer.analyze(queryText, statusCache.sourceVocabulary());
    if (log.isDebugEnabled() && !structuredAnalysis.detectedSources().isEmpty()) {
      log.debug("385: source detection: sources={}, vocabSize={}",
          structuredAnalysis.detectedSources(), statusCache.sourceVocabulary().size());
    }

    // 385: Temporal date extraction from query text (#9).
    // Synchronous (~0ms, regex). Dates fed into boost filters and answer type classification.
    TemporalQueryExtractor.TemporalExtraction temporalExtraction =
        TemporalQueryExtractor.extract(queryText);

    // 385: Answer type classification (#8). Uses date count from #9 for TEMPORAL detection.
    AnswerTypeClassifier.AnswerType answerType =
        AnswerTypeClassifier.classify(queryText, temporalExtraction.dates().size());
    String answerTypeName = answerType.name();

    // 256-I2: LLM query expansion gated by PipelineConfig.expansionEnabled().
    // 256-I4: structured skip reason tracking for pipeline execution report.
    // Fires before base search so LLM latency overlaps with gRPC round-trip.
    CompletableFuture<String> expansionFuture = null;
    boolean expansionApplied = false;
    String expansionSkipReason = null;
    long searchStartNs = System.nanoTime();
    if (isExpansionEligible(
        pipelineConfig, querySyntax, queryText, req.cursor(), onlineAiService.isAvailable(),
        effectiveQueryType)) {
      expansionFuture = startExpansionAsync(queryText);
    } else if (effectiveQueryType == QueryType.NAVIGATIONAL || effectiveQueryType == QueryType.EXACT_MATCH) {
      expansionSkipReason = "QUERY_TYPE_" + effectiveQueryType.name();
    } else if (!pipelineConfig.expansionEnabled()) {
      expansionSkipReason = "DISABLED";
    } else if (!onlineAiService.isAvailable()) {
      expansionSkipReason = "AI_UNAVAILABLE";
    } else if (querySyntax != SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE) {
      expansionSkipReason = "LUCENE_SYNTAX";
    } else if (queryText.isBlank()) {
      expansionSkipReason = "BLANK_QUERY";
    } else {
      expansionSkipReason = "PAGINATED";
    }

    // 363: Query Understanding — fire async LLM extraction for boost filters.
    // Bypass when: explicit filters/boostFilters present, blank query, paginated, AI unavailable,
    // or navigational/exact-match queries (which skip the full pipeline anyway).
    CompletableFuture<QueryUnderstandingService.QuResult> quFuture = null;
    boolean hasExplicitFilters = req.filters() != null || req.boostFilters() != null;
    if (!hasExplicitFilters
        && !queryText.isBlank()
        && (req.cursor() == null || req.cursor().isBlank())
        && effectiveQueryType != QueryType.NAVIGATIONAL
        && effectiveQueryType != QueryType.EXACT_MATCH
        && quService.isAvailable()) {
      quFuture = quService.extract(queryText, statusCache.getCachedFacetSnapshot());
    }

    // 366: Fire filter normalization async when explicit filters are present (mutually exclusive with QU)
    CompletableFuture<FilterNormalizationService.NormResult> normFuture = null;
    if (hasExplicitFilters && normService.isAvailable()) {
      normFuture = normService.normalize(req.filters(), statusCache.getCachedFacetSnapshot());
    }

    // 256-G3: PipelineConfig is the sole pipeline control on wire. Deprecated mode field no longer set.
    SearchRequest.Builder b =
        SearchRequest.newBuilder()
            .setQuery(queryText)
            .setLimit(searchLimit)
            // Tempdoc 549 Phase D2: the REST `debug` flag requests the numeric per-hit detail
            // tier — map it to include_detail (the deprecated gRPC debug field retires in E1).
            .setIncludeDetail(Boolean.TRUE.equals(req.debug()))
            .setSort(SearchPipelinePresets.parseSortOrDefault(req.sort()))
            .setQuerySyntax(querySyntax)
            .setIncludeExcerpts(Boolean.TRUE.equals(req.includeExcerpts()))
            .setPipeline(SearchPipelinePresets.toProtoPipelineConfig(pipelineConfig, denseAuto));

    if (req.cursor() != null && !req.cursor().isBlank()) {
      b.setCursor(req.cursor());
    }

    for (String p : req.projection()) {
      if (p != null && !p.isBlank()) {
        b.addProjection(p);
      }
    }

    // 366: Collect filter normalization result (if pending)
    KnowledgeSearchRequest.Filters filters = req.filters();
    FilterNormalizationService.NormResult normResultForResponse = null;
    if (normFuture != null) {
      try {
        FilterNormalizationService.NormResult normResult = normFuture.get();
        if (normResult != null && normResult.normalizedFilters() != null) {
          filters = normResult.normalizedFilters();
          normResultForResponse = normResult;
          log.debug("Filter normalization applied (source={}, latency={}ms)",
              normResult.source(), normResult.latencyMs());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        log.debug("Filter normalization failed: {}", e.getCause().getMessage());
      }
    }

    if (filters != null) {
      SearchRequestMapper.applyFilters(b, filters);
    }

    // Soft-boost filters (363): explicit boostFilters from request, OR QU-extracted boostFilters.
    KnowledgeSearchRequest.Filters boost = req.boostFilters();
    QueryUnderstandingService.QuResult quResultForResponse = null;  // 366 §3a: surface in response
    if (boost == null && quFuture != null) {
      // Collect QU result — blocks up to the QU deadline (2s), but the LLM call was
      // already running in parallel with filter/facet setup above.
      try {
        QueryUnderstandingService.QuResult quResult = quFuture.get();
        if (quResult != null && quResult.boostFilters() != null) {
          boost = quResult.boostFilters();
          quResultForResponse = quResult;
          log.debug("QU applied boost filters in {}ms", quResult.latencyMs());
        } else if (quResult != null) {
          log.debug("QU passthrough (no filters extracted) in {}ms", quResult.latencyMs());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("QU interrupted");
      } catch (ExecutionException e) {
        log.debug("QU extraction failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
      }
    }
    if (boost != null) {
      SearchRequestMapper.applyBoostFilters(b, boost, temporalExtraction);
    }

    // 385: Temporal range injection when no QU boost is active (#9)
    if (boost == null && !hasExplicitFilters) {
      SearchRequestMapper.applyTemporalOnlyBoost(b, temporalExtraction);
    }

    KnowledgeSearchRequest.Facets facets = req.facets();
    if (facets != null) {
      SearchRequestMapper.applyFacets(b, facets);
    }

    SearchRequest baseReq = b.build();

    // 385: Per-source retrieval for queries with 2+ detected sources (#6 + #1).
    // Gate: any query mentioning 2+ sources benefits from balanced per-source retrieval,
    // not just COMPARISON queries (FeB4RAG research). Answer type remains a separate hint.
    SearchResponse resp;
    boolean perSourceRetrieval = false;
    if (structuredAnalysis.detectedSources().size() >= 2) {
      resp = SearchPerSourceExecutor.execute(
          client, baseReq, structuredAnalysis.detectedSources(), searchLimit);
      perSourceRetrieval = true;
    } else {
      // Single-source: inject detected sources as boost if no boost already set
      if (!structuredAnalysis.detectedSources().isEmpty() && !b.hasBoostFilters()) {
        SearchFilters.Builder srcBoost = SearchFilters.newBuilder();
        for (String src : structuredAnalysis.detectedSources()) {
          srcBoost.addMetaSource(src.toLowerCase(Locale.ROOT));
        }
        resp = client.search(baseReq.toBuilder().setBoostFilters(srcBoost.build()).build());
      } else {
        resp = client.search(baseReq);
      }
    }

    // If LLM expansion is in flight, wait remaining budget then re-search with expanded terms.
    // Falls back to base results on timeout or error — user always gets a result.
    // 385: Skip expansion re-search when per-source retrieval was used — the topic remainder
    // already strips source name noise, and re-searching would discard the interleaved result.
    if (expansionFuture != null && !perSourceRetrieval) {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStartNs);
      long remainingMs = EXPANSION_BUDGET_MS - elapsedMs;
      if (remainingMs > 0) {
        try {
          String expansionText = expansionFuture.get(remainingMs, TimeUnit.MILLISECONDS);
          String expandedQuery = mergeExpansion(queryText, expansionText);
          if (expandedQuery != null) {
            // LUCENE syntax for expanded search: avoids withPrefixExpansion() applying to the
            // LLM-appended last token instead of the user's actual last word.
            SearchRequest expandedReq =
                baseReq.toBuilder()
                    .setQuery(expandedQuery)
                    .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                    .build();
            resp = client.search(expandedReq);
            expansionApplied = true;
            log.debug("LLM expansion applied to query");
          }
        } catch (TimeoutException e) {
          expansionFuture.cancel(true);
          expansionSkipReason = "TIMEOUT";
          log.debug(
              "LLM expansion timed out ({}ms budget), using base results", EXPANSION_BUDGET_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          expansionSkipReason = "FAILED";
        } catch (ExecutionException e) {
          expansionSkipReason = "FAILED";
          log.debug("LLM expansion failed: {}", e.getCause().getMessage());
        }
      } else {
        expansionFuture.cancel(true);
        expansionSkipReason = "TIMEOUT";
      }
    }

    // 256-F1: Cross-encoder reranking fires for all search modes when enabled.
    // Eligibility guard: isRerankerEligible checks pipeline.crossEncoderEnabled() + min hits threshold.
    List<SearchResult> results = resp.getResultsList();

    // LambdaMART reranking: fires first when a trained model is loaded.
    // 256-F2: cross-encoder now runs after LambdaMART (2-stage cascaded reranking).
    boolean lambdaMartApplied = false;
    String lambdaMartSkipReason = null;
    long lambdaMartNs = 0;
    if (!pipelineConfig.lambdamartEnabled()) {
      lambdaMartSkipReason = "PIPELINE_NOT_ELIGIBLE";
    } else if (lambdaMartReranker == null) {
      lambdaMartSkipReason = "NO_MODEL";
    } else if (!lambdaMartReranker.isLoaded()) {
      lambdaMartSkipReason = "MODEL_NOT_LOADED";
    } else if (results.isEmpty()) {
      lambdaMartSkipReason = "NO_RESULTS";
    } else {
      Span lmSpan =
          tracer.spanBuilder("search/lambdamart").setParent(Context.current()).startSpan();
      try (Scope lmScope = lmSpan.makeCurrent()) { // NOPMD - scope used for auto-close
        long lmStart = System.nanoTime();
        int n = results.size();
        float[] sparseScores = new float[n];
        float[] vectors = new float[n];
        float[] spladeScores = new float[n];
        for (int i = 0; i < n; i++) {
          SearchResult sr = results.get(i);
          // Tempdoc 549 Phase E1: LambdaMART inference reads leg scores from the always-on
          // structural per-hit trace (sparse/dense/splade-retrieval stage scores), not the retired
          // debug_scores map. Emitted regardless of include_detail, so LTR is unaffected by the
          // detail-tier gate. Tempdoc 580 §17 P5 V2: splade is now a distinct third feature.
          sparseScores[i] = SearchTraceMapper.protoStageScore(sr, "sparse-retrieval");
          vectors[i] = SearchTraceMapper.protoStageScore(sr, "dense-retrieval");
          spladeScores[i] = SearchTraceMapper.protoStageScore(sr, "splade-retrieval");
        }
        List<Integer> order = lambdaMartReranker.rerank(sparseScores, vectors, spladeScores, n);
        if (order != null) {
          List<SearchResult> reranked = new ArrayList<>(n);
          for (int idx : order) reranked.add(results.get(idx));
          results = reranked;
          lambdaMartApplied = true;
          log.debug("LambdaMART reranked {} results", n);
        } else {
          lambdaMartSkipReason = "INFERENCE_FAILED";
        }
        lambdaMartNs = System.nanoTime() - lmStart;
        // Tempdoc 553 Phase D (head): OpenInference RERANKER projection of the LTR-reordered output.
        lmSpan.setAllAttributes(
            io.justsearch.telemetry.OpenInferenceSpans.reranker(
                "lambdamart", n, SearchTraceMapper.oiDocsOf(results)));
      } finally {
        lmSpan.end();
      }
    }

    // 250: Cross-encoder execution tracking
    boolean crossEncoderApplied = false;
    String crossEncoderSkipReason = null;
    Map<String, Float> ceScoresByDocId = null;
    long crossEncoderMs = -1;

    // 256-F2: LambdaMART + cross-encoder co-execution — cross-encoder runs on LambdaMART's
    // reordered output (standard 2-stage cascaded reranking pattern).
    if (!isRerankerEligible(
        pipelineConfig, rerankConfig, results.size(), statusCache.avgContentLengthChars(), effectiveQueryType)) {
      if (effectiveQueryType == QueryType.NAVIGATIONAL) {
        crossEncoderSkipReason = "NAVIGATIONAL_QUERY";
      } else if (!rerankConfig.enabled()) {
        crossEncoderSkipReason = "DISABLED";
      } else if (results.size() < rerankConfig.minHitsThreshold()) {
        crossEncoderSkipReason = "BELOW_MIN_THRESHOLD";
      } else if (rerankConfig.maxAvgDocLengthChars() > 0
          && statusCache.avgContentLengthChars() > rerankConfig.maxAvgDocLengthChars()) {
        crossEncoderSkipReason = "DOCS_TOO_LONG";
      } else {
        crossEncoderSkipReason = "PIPELINE_NOT_ELIGIBLE";
      }
    } else if (!rerankConfig.isReady()) {
      // 360: model not configured — skip without RPC round-trip
      crossEncoderSkipReason = "MODEL_NOT_CONFIGURED";
    } else {
      // 360: Remote reranking via Worker's GPU-capable cross-encoder
      // 256-F4: PipelineConfig window overrides global config default
      int configWindow = pipelineConfig.crossEncoderWindow();
      int topK = Math.min(configWindow > 0 ? configWindow : rerankConfig.topK(), results.size());
      List<String> docTexts = new ArrayList<>(topK);
      for (int i = 0; i < topK; i++) {
        SearchResult sr = results.get(i);
        String title = sr.getFieldsMap().getOrDefault("title", "");
        String preview = sr.getFieldsMap().getOrDefault("content_preview", "");

        // Use query-focused snippet extraction for better reranker context
        // (centers snippet on first query match instead of document start)
        var spans = SearchResultMapper.extractMatchSpans(sr);
        String snippet = SearchResultMapper.extractQueryFocusedSnippet(
            preview, spans, RERANK_SNIPPET_LENGTH);
        docTexts.add(title + " " + snippet);
      }

      RerankResponse reranked;
      Span ceSpan =
          tracer.spanBuilder("search/cross_encoder").setParent(Context.current()).startSpan();
      try (Scope ceScope = ceSpan.makeCurrent()) { // NOPMD - scope used for auto-close
        reranked = knowledgeServer.client().rerank(
            req.query(), docTexts, rerankConfig.deadlineBudgetMs());
        // Tempdoc 553 Phase D (head): OpenInference RERANKER projection of the CE-scored output —
        // the reranked docs (id + CE score + content), in the cross-encoder's chosen order.
        if (reranked != null && !reranked.getSkipped()) {
          ceSpan.setAllAttributes(
              io.justsearch.telemetry.OpenInferenceSpans.reranker(
                  "cross-encoder", topK, SearchTraceMapper.ceOutputDocs(results, reranked)));
        }
      } catch (Exception e) {
        log.warn("Remote rerank failed, using original order: {}", e.getMessage());
        crossEncoderSkipReason = "RPC_FAILED";
        reranked = null;
      } finally {
        ceSpan.end();
      }

      if (reranked != null && !reranked.getSkipped()) {
        // 250 Phase 2: capture CE scores by docId before reordering
        ceScoresByDocId = new HashMap<>();
        for (int origIdx : reranked.getSortedIndicesList()) {
          if (origIdx < results.size() && origIdx < reranked.getScoresCount()) {
            ceScoresByDocId.put(
                results.get(origIdx).getId(), reranked.getScores(origIdx));
          }
        }
        // Reorder results based on reranked indices
        List<SearchResult> rerankedResults = new ArrayList<>(results.size());
        for (int idx : reranked.getSortedIndicesList()) {
          rerankedResults.add(results.get(idx));
        }
        // Append any results beyond topK that weren't reranked
        for (int i = topK; i < results.size(); i++) {
          rerankedResults.add(results.get(i));
        }
        results = rerankedResults;
        crossEncoderApplied = true;
        crossEncoderMs = reranked.getElapsedMs();
        log.debug("Reranked {} docs in {}ms (remote)", topK, reranked.getElapsedMs());
      } else if (reranked != null) {
        crossEncoderSkipReason = reranked.getSkipReason().isEmpty()
            ? "DEADLINE_EXCEEDED" : reranked.getSkipReason();
        crossEncoderMs = reranked.getElapsedMs();
        log.debug("Rerank skipped: {} ({}ms)", crossEncoderSkipReason, reranked.getElapsedMs());
      }
    }

    // Trim results to the originally requested limit (we may have fetched more for reranking)
    if (results.size() > requestedLimit) {
      results = results.subList(0, requestedLimit);
    }

    List<KnowledgeSearchResponse.Hit> hits =
        SearchResultMapper.toHits(results, ceScoresByDocId, pipelineConfig.freshnessEnabled());

    Map<String, Map<String, Long>> facetsOut = Map.of();
    if (!resp.getFacetsMap().isEmpty()) {
      Map<String, Map<String, Long>> m = new HashMap<>();
      for (var entry : resp.getFacetsMap().entrySet()) {
        FacetCounts counts = entry.getValue();
        m.put(entry.getKey(), counts == null ? Map.of() : counts.getCountsMap());
      }
      facetsOut = Map.copyOf(m);
    }

    // Phase C: extract entity facet variant breakdowns
    Map<String, List<KnowledgeSearchResponse.EntityVariantBreakdown>> variantsOut = Map.of();
    if (!resp.getEntityFacetVariantsMap().isEmpty()) {
      Map<String, List<KnowledgeSearchResponse.EntityVariantBreakdown>> vm = new HashMap<>();
      for (var vEntry : resp.getEntityFacetVariantsMap().entrySet()) {
        EntityFacetVariants proto = vEntry.getValue();
        if (proto == null || proto.getEntriesCount() == 0) continue;
        List<KnowledgeSearchResponse.EntityVariantBreakdown> breakdowns = new ArrayList<>();
        for (var bd : proto.getEntriesList()) {
          breakdowns.add(
              new KnowledgeSearchResponse.EntityVariantBreakdown(
                  bd.getCanonicalForm(), bd.getTotalCount(), bd.getVariantsMap()));
        }
        vm.put(vEntry.getKey(), breakdowns);
      }
      variantsOut = Map.copyOf(vm);
    }

    String nextCursor = resp.getNextCursor();
    if (nextCursor != null && nextCursor.isBlank()) {
      nextCursor = null;
    }
    // Tempdoc 549 Phase E5: the flat query-trace fields (effective_mode, degradation reasons,
    // qpp) are retired — read the effective mode (telemetry span) + QPP (DEBUG log) from the
    // unified trace, the single source. The degradation-reason flat locals were already dead
    // (their consumer, buildPipelineExecution, retired in E3).
    io.justsearch.ipc.SearchTrace traceScalars = resp.hasSearchTrace() ? resp.getSearchTrace() : null;
    String effectiveMode =
        traceScalars != null && !traceScalars.getEffectiveMode().isBlank()
            ? traceScalars.getEffectiveMode()
            : null;

    // P1-D: Log QPP signals at DEBUG. Use a stable hash of query text — never log raw query.
    if (log.isDebugEnabled() && traceScalars != null && traceScalars.hasQpp()) {
      io.justsearch.ipc.TraceQpp qpp = traceScalars.getQpp();
      if (qpp.getMaxIdf() > 0f || qpp.getQueryScope() > 0f) {
        log.debug(
            "qpp query_hash={} mode={} max_idf={} avg_ictf={} query_scope={}",
            Integer.toHexString(queryText.hashCode()),
            searchMode,
            qpp.getMaxIdf(),
            qpp.getAvgIctf(),
            qpp.getQueryScope());
      }
    }

    // 250 Phase 5a: Record head-side fallback counters
    if (rerankConfig.enabled() && !crossEncoderApplied) rerankerSkipped().add(1);
    if (lambdaMartReranker != null && lambdaMartReranker.isLoaded() && !lambdaMartApplied) {
      lambdamartSkipped().add(1);
    }

    // Total Head-side search time: Worker RPC + CE RPC + all overhead.
    // This is the true client-visible search latency.
    long totalSearchMs = (System.nanoTime() - doSearchStartNs) / 1_000_000;

    // 250 Phase 5c: Enrich root span with result attributes
    searchSpan.setAttribute("search.total_hits", resp.getTotalHits());
    searchSpan.setAttribute("search.took_ms", totalSearchMs);
    searchSpan.setAttribute("search.mode", effectiveMode != null ? effectiveMode : "");

    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(resp.getTotalHits())
        // Tempdoc 597: the true matched-document count (distinct from the union totalHits).
        .matchCount(resp.getMatchCount())
        .tookMs(totalSearchMs)
        .results(hits)
        .nextCursor(nextCursor)
        .facets(facetsOut)
        .facetsTruncated(resp.getFacetsTruncated())
        // Tempdoc 549 U4 (Slice 6): the 15 flat query-trace fields were removed from the response
        // record. The canonical `introspection` trace (built below from the same `resp` proto via
        // KnowledgeIntrospectionMapper + buildHeadStages) is now the single source.
        .entityFacetVariants(variantsOut)
        .indexCapabilities(buildIndexCapabilities())
        // Tempdoc 549 Phase E3: pipelineExecution retired — per-stage timing + component statuses
        // are on the unified trace (TraceStage.ms / status), composed below in mapSearchTrace.
        .queryUnderstanding(buildQueryUnderstanding(quResultForResponse, answerTypeName))
        .filterNormalization(buildFilterNormalization(normResultForResponse))
        // Tempdoc 549 Phase E4: SearchIntrospection retired — the unified stage-keyed trace is the
        // single source. Compose the worker's proto stages (Phase B) + the head's stages into one
        // app-api SearchTrace.
        .searchTrace(
            SearchTraceMapper.mapSearchTrace(
                resp,
                SearchTraceMapper.buildHeadStages(
                    pipelineConfig,
                    lambdaMartNs,
                    crossEncoderMs,
                    lambdaMartApplied,
                    lambdaMartSkipReason,
                    crossEncoderApplied,
                    crossEncoderSkipReason,
                    expansionApplied,
                    expansionSkipReason,
                    queryType)))
        .build();
  }


  private static KnowledgeSearchResponse.FilterNormalization buildFilterNormalization(
      FilterNormalizationService.NormResult normResult) {
    if (normResult == null) return null;
    return new KnowledgeSearchResponse.FilterNormalization(
        normResult.original(), normResult.normalized(), normResult.latencyMs(), normResult.source());
  }

  private static KnowledgeSearchResponse.QueryUnderstanding buildQueryUnderstanding(
      QueryUnderstandingService.QuResult quResult, String answerType) {
    Map<String, List<String>> boosts = null;
    long latencyMs = 0L;

    if (quResult != null && quResult.boostFilters() != null) {
      var filters = quResult.boostFilters();
      var b = new java.util.LinkedHashMap<String, List<String>>();
      if (!filters.metaSource().isEmpty()) b.put("meta_source", filters.metaSource());
      if (!filters.metaAuthor().isEmpty()) b.put("meta_author", filters.metaAuthor());
      if (!filters.metaCategory().isEmpty()) b.put("meta_category", filters.metaCategory());
      if (!filters.entityPersons().isEmpty()) b.put("entity_persons", filters.entityPersons());
      if (!filters.entityOrganizations().isEmpty()) b.put("entity_organizations", filters.entityOrganizations());
      if (!filters.entityLocations().isEmpty()) b.put("entity_locations", filters.entityLocations());
      if (!b.isEmpty()) boosts = b;
      latencyMs = quResult.latencyMs();
    }

    // 385: Surface answer type even when QU didn't run, but only if non-default (#8)
    boolean hasBoosts = boosts != null;
    boolean hasAnswerType = answerType != null && !"INFERENCE".equals(answerType);
    if (!hasBoosts && !hasAnswerType) return null;

    return new KnowledgeSearchResponse.QueryUnderstanding(
        boosts, latencyMs, hasAnswerType ? answerType : null);
  }


  /**
   * Fires an asynchronous LLM call to generate morphological variants of the query terms.
   *
   * <p>Uses {@link SamplingParams#DETERMINISTIC} to minimize hallucination risk. The caller
   * must wait on the returned future within {@link #EXPANSION_BUDGET_MS} and cancel on timeout.
   */
  private CompletableFuture<String> startExpansionAsync(String query) {
    CompletableFuture<String> future = new CompletableFuture<>();
    List<Map<String, Object>> messages =
        List.of(
            Map.of("role", "system", "content", EXPANSION_SYSTEM_PROMPT),
            Map.of("role", "user", "content", query));
    StringBuilder buf = new StringBuilder();
    onlineAiService.streamChat(
        messages,
        EXPANSION_MAX_TOKENS,
        buf::append,
        fr -> future.complete(buf.toString().strip()),
        future::completeExceptionally,
        SamplingParams.DETERMINISTIC);
    return future;
  }

  /**
   * Validates LLM expansion output and returns a merged query string, or {@code null} if the
   * expansion should be discarded.
   *
   * <p>Truncates expansion tokens to 3× the original query's token count (length guard), then
   * rejects if any remaining token is non-alphabetic (hallucination guard), or if no new terms
   * were added. Truncating rather than rejecting handles the common case where the model produces
   * valid morphological variants for each word but exceeds the cap due to the 5-form system prompt.
   */
  static String mergeExpansion(String originalQuery, String expansionText) {
    if (expansionText == null || expansionText.isBlank()) {
      return null;
    }
    String[] origTokens = originalQuery.strip().split("\\s+");
    String[] expandTokens = expansionText.strip().split("\\s+");
    // Length guard: truncate to 3× original token count rather than rejecting entirely.
    // The system prompt requests up to 5 variant forms per word; capping preserves valid
    // partial expansions instead of silently discarding everything.
    int maxExpand = origTokens.length * 3;
    if (expandTokens.length > maxExpand) {
      expandTokens = Arrays.copyOf(expandTokens, maxExpand);
    }
    // Hallucination guard: all expansion tokens must be pure alphabetic
    for (String t : expandTokens) {
      if (!ALPHA_ONLY.matcher(t).matches()) {
        return null;
      }
    }
    // Merge original query with new tokens (deduplicated, case-insensitive)
    Set<String> seen = new HashSet<>();
    for (String t : origTokens) {
      seen.add(t.toLowerCase(Locale.ROOT));
    }
    StringBuilder sb = new StringBuilder(originalQuery.strip());
    boolean added = false;
    for (String t : expandTokens) {
      if (seen.add(t.toLowerCase(Locale.ROOT))) {
        sb.append(' ').append(t).append("^0.3");
        added = true;
      }
    }
    return added ? sb.toString() : null;
  }

  // Tempdoc 549 Phase E2: the leg-keyed per-hit HitProvenance (and its head-side
  // mapWorkerProvenance mapper) is RETIRED. Per-hit ranking provenance is now the per-doc slice
  // of the unified stage vocabulary (mapHitStages → Hit.trace), incl. the head's cross-encoder
  // stage. The earlier debug_scores reconstruction (assembleProvenance) was retired before it.


  /**
   * Builds an IndexCapabilities snapshot from the cached Worker operational view. Returns null if
   * no cached view is available yet (before first status poll).
   */
  private KnowledgeSearchResponse.IndexCapabilities buildIndexCapabilities() {
    if (!statusCache.isWorkerReady()) {
      return null;
    }
    var view = knowledgeServer.client().cachedOperationalView();
    if (view == null) {
      return null;
    }
    Double embCoverage =
        view.enrichment().embeddingDocCount() > 0 ? view.enrichment().embeddingCoveragePercent() / 100.0 : null;
    Double spladeCoverage =
        view.enrichment().spladeDocCount() > 0 ? view.enrichment().spladeCoveragePercent() / 100.0 : null;
    Double chunkCoverage =
        view.enrichment().chunk().chunkDocCount() > 0 ? view.enrichment().chunk().chunkVectorCoveragePercent() / 100.0 : null;
    Boolean ceAvailable = rerankConfig.enabled() ? true : null;
    return new KnowledgeSearchResponse.IndexCapabilities(
        embCoverage, spladeCoverage, chunkCoverage, ceAvailable);
  }
}
