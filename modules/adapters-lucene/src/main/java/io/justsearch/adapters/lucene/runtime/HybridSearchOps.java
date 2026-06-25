/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.HybridFusionUtils.fuseWithCC;
import static io.justsearch.adapters.lucene.runtime.HybridFusionUtils.fuseWithRRF;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.buildFilterQueryOnly;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal hybrid-search collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates the low-signal gating logic and vector-skip heuristic that were previously
 * duplicated across three near-identical hybrid search methods in the facade.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
public final class HybridSearchOps {
  private static final Logger log = LoggerFactory.getLogger(HybridSearchOps.class);

  // Fallback defaults matching ResolvedConfig documented defaults.
  // Used only when session.resolvedConfig returns null (defensive path).
  private static final int DEFAULT_CANDIDATE_LIMIT_MAX = 100;
  private static final int DEFAULT_TEXT_CANDIDATE_MULTIPLIER = 10;
  private static final int DEFAULT_VECTOR_CANDIDATE_MULTIPLIER = 10;
  private static final double DEFAULT_VECTOR_RRF_WEIGHT = 0.75;
  private static final double DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD = 0.40;
  private static final int DEFAULT_VECTOR_SKIP_MIN_CHARS = 4;
  private static final int DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL = 10;
  private static final double DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL = 0.3;

  // Tempdoc 636 Design v2 — per-query leg arbitration on the 2-way CC alpha. (adapters-lucene must
  // not read env/system properties — ArchUnit guardrail; these are plain constants. Promote to
  // ResolvedConfig.HybridSearch if the eval A/B shows they need per-deployment tuning.)
  /** Top-K hits per leg used to measure cross-leg rank overlap. */
  private static final int ARBITRATION_TOP_K = 10;
  /**
   * Min dense top score for the dense leg to be a usable signal (a sanity floor — dense is COSINE +
   * L2-normalized so score = (1+cos)/2; 0.5 = cosine 0, i.e. "not anti-correlated"). This is a weak
   * floor by design: the real selectivity is the <b>BM25-incoherence</b> condition, not a strong
   * dense-confidence bar. (Tempdoc 636 review: a dense top1−top2 gap requirement was tried and
   * measurement-rejected — it over-blocked the all-similar-docs needle regime without adding
   * discrimination; BM25-incoherence is the correct discriminator.)
   */
  private static final double ARBITRATION_DENSE_CONFIDENT_MIN = 0.5;
  /** Cross-leg top-K doc-id Jaccard at/above which the legs "agree" (no intervention). */
  private static final double ARBITRATION_OVERLAP_MAX = 0.1;

  /**
   * Common English stop words whose vector embeddings are semantically meaningless.
   */
  private static final Set<String> STOP_WORDS = Set.of(
      "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
      "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
      "be", "have", "has", "had", "do", "does", "did", "will", "would",
      "could", "should", "may", "might", "must", "shall", "can", "need",
      "it", "its", "this", "that", "these", "those", "i", "you", "he",
      "she", "we", "they", "what", "which", "who", "whom", "how", "when",
      "where", "why", "all", "each", "every", "both", "few", "more", "most",
      "other", "some", "such", "no", "not", "only", "own", "same", "so",
      "than", "too", "very", "just", "also", "now", "here", "there"
  );

  private final RuntimeSession session;
  private final TextQueryOps textQueryOps;
  private final ReadPathOps readPathOps;

  HybridSearchOps(
      RuntimeSession session,
      TextQueryOps textQueryOps,
      ReadPathOps readPathOps) {
    this.session = session;
    this.textQueryOps = textQueryOps;
    this.readPathOps = readPathOps;
  }

  /** Functional interface for the BM25/text search leg of a hybrid query. */
  @FunctionalInterface
  interface TextSearchLeg {
    SearchResult search(String queryText, int limit);
  }

  /** Functional interface for the KNN/vector search leg of a hybrid query. */
  @FunctionalInterface
  interface VectorSearchLeg {
    SearchResult search(float[] queryVector, int limit);
  }

  /** Result of low-signal gating computation for hybrid search RRF fusion. */
  record LowSignalGating(int vectorOnlyCap, double vectorWeight) {}

  /**
   * Determines if vector search should be skipped for this query.
   *
   * <p>Short-circuits vector search for:
   * <ul>
   *   <li>Queries shorter than the configured minimum characters</li>
   *   <li>Single-word queries that are common stop words</li>
   * </ul>
   *
   * @param queryText the query to evaluate
   * @return true if vector search should be skipped
   */
  boolean shouldSkipVectorSearch(String queryText) {
    ResolvedConfig rc = session.resolvedConfig;
    ResolvedConfig.HybridSearch hs = rc != null ? rc.hybridSearch() : null;
    int minChars = hs != null ? hs.vectorSkipMinChars() : DEFAULT_VECTOR_SKIP_MIN_CHARS;
    if (queryText.length() < minChars) {
      return true;
    }
    String normalized = queryText.trim().toLowerCase(Locale.ROOT);
    // Single word that's a stop word
    if (!normalized.contains(" ") && STOP_WORDS.contains(normalized)) {
      return true;
    }
    return false;
  }

  /**
   * Computes low-signal gating parameters for RRF fusion.
   *
   * <p>Evaluates the top scores and total hits from both search legs to determine whether the
   * query is "low signal" (weak match quality). When low-signal is detected, the vector
   * contribution is capped and/or down-weighted to prevent semantic noise from dominating.
   *
   * @param bm25Result the BM25/lexical search results
   * @param vectorResult the KNN/vector search results
   * @param queryText the original query text (for debug logging)
   * @param logPrefix label for the debug log message (e.g., "Hybrid gating")
   * @return gating parameters for RRF fusion
   */
  LowSignalGating computeLowSignalGating(
      SearchResult bm25Result,
      SearchResult vectorResult,
      String queryText,
      String logPrefix) {
    ResolvedConfig rc = session.resolvedConfig;
    ResolvedConfig.HybridSearch hs = rc != null ? rc.hybridSearch() : null;

    float bm25Top = bm25Result.hits().isEmpty() ? 0.0f : bm25Result.hits().get(0).score();
    long bm25TotalHits = bm25Result.totalHits();
    float vectorTop = vectorResult.hits().isEmpty() ? 0.0f : vectorResult.hits().get(0).score();

    double vectorLowSignalThreshold =
        hs != null
            ? hs.vectorLowSignalTopScoreThreshold()
            : DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD;
    double bm25LowSignalTopScoreThreshold =
        hs != null ? hs.bm25LowSignalTopScoreThreshold() : 0.0;
    int bm25LowSignalTotalHitsThreshold =
        hs != null ? hs.bm25LowSignalTotalHitsThreshold() : 0;

    boolean lowSignalByVector = vectorTop < vectorLowSignalThreshold;
    boolean lowSignalByBm25Top =
        bm25LowSignalTopScoreThreshold > 0.0 && bm25Top < bm25LowSignalTopScoreThreshold;
    boolean lowSignalByBm25TotalHits =
        bm25LowSignalTotalHitsThreshold > 0 && bm25TotalHits <= bm25LowSignalTotalHitsThreshold;
    boolean lowSignal = lowSignalByVector || lowSignalByBm25Top || lowSignalByBm25TotalHits;

    int vectorOnlyCap =
        lowSignal
            ? (hs != null
                ? hs.vectorOnlyCapLowSignal()
                : DEFAULT_VECTOR_ONLY_CAP_LOW_SIGNAL)
            : Integer.MAX_VALUE;
    double vectorWeight =
        hs != null ? hs.vectorRrfWeight() : DEFAULT_VECTOR_RRF_WEIGHT;
    if (lowSignal) {
      vectorWeight =
          hs != null
              ? hs.vectorRrfWeightLowSignal()
              : DEFAULT_VECTOR_RRF_WEIGHT_LOW_SIGNAL;
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "{}: query='{}' lowSignal={} lowSignalByVector={} lowSignalByBm25Top={}"
              + " lowSignalByBm25TotalHits={} bm25Top={} bm25TotalHits={} vectorTop={}"
              + " vectorLowSignalThreshold={} bm25LowSignalTopScoreThreshold={}"
              + " bm25LowSignalTotalHitsThreshold={} vectorOnlyCap={} vectorWeight={}",
          logPrefix,
          queryText,
          lowSignal,
          lowSignalByVector,
          lowSignalByBm25Top,
          lowSignalByBm25TotalHits,
          bm25Top,
          bm25TotalHits,
          vectorTop,
          vectorLowSignalThreshold,
          bm25LowSignalTopScoreThreshold,
          bm25LowSignalTotalHitsThreshold,
          vectorOnlyCap,
          vectorWeight);
    }

    return new LowSignalGating(vectorOnlyCap, vectorWeight);
  }

  /**
   * Tempdoc 636 Design v2 — per-query leg arbitration for the 2-way CC fusion alpha (dense weight).
   *
   * <p>The static {@code ccAlpha} (default 0.5) gives the lexical leg half the fused weight even when
   * it is matching generic filler — which suppresses a confident dense answer on grep-defeating
   * paraphrase queries (tempdoc 636 F-023: vector 0.82 vs hybrid 0.32). This raises alpha toward dense
   * when, and only when, two conditions hold: (a) the dense leg is <em>bounded-confident</em> (top
   * cosine ≥ {@link #ARBITRATION_DENSE_CONFIDENT_MIN}; cosine is bounded so this is a comparable
   * signal, unlike raw BM25 scores), and (b) the legs <em>diverge</em> (top-K doc-id Jaccard &lt;
   * {@link #ARBITRATION_OVERLAP_MAX}). The signal is rank-led, not score-led, per the BM25/cosine
   * score-incomparability constraint.
   *
   * <p>One-directional by design: it only ever <em>raises</em> alpha toward dense (never lowers it),
   * because the measured failure is lexical suppression of dense; the symmetric "down-weight dense on
   * keyword queries" direction is unmeasured here and left to the existing low-signal gate (RRF path).
   *
   * <p>The gate fires only when <b>all</b> hold: (a) dense clears a sanity floor (top ≥
   * {@link #ARBITRATION_DENSE_CONFIDENT_MIN}), (b) the legs diverge (Jaccard &lt;
   * {@link #ARBITRATION_OVERLAP_MAX}), and (c) <b>BM25 is incoherent</b> — its own top2/top1 ratio ≥
   * {@code bm25IncoherenceMin} (a flat top, i.e. no clear lexical winner). Condition (c) is the
   * tempdoc 636 review fix and the real discriminator: on BM25-dominant corpora (legal, email) BM25
   * returns a <em>peaked</em> winner and is usually right, so we must NOT down-weight it — a
   * confident-but-wrong dense leg otherwise wrecked courtlistener (−23%) and risked enron-qa.
   *
   * @param divergeAlpha the dense weight to apply when arbitration fires (config-tunable)
   * @param bm25IncoherenceMin the BM25 top2/top1 ratio at/above which BM25 counts as incoherent
   *     (config-tunable; higher = stricter, fires on fewer queries)
   * @return {@code baseAlpha} unchanged unless every firing condition holds; otherwise
   *     {@code max(baseAlpha, divergeAlpha)}.
   */
  static double computeArbitrationAlpha(
      SearchResult bm25, SearchResult vector, double baseAlpha, double divergeAlpha,
      double bm25IncoherenceMin) {
    if (vector == null || vector.hits() == null || vector.hits().isEmpty()) {
      return baseAlpha;
    }
    float vectorTop = vector.hits().get(0).score();
    if (vectorTop < ARBITRATION_DENSE_CONFIDENT_MIN) {
      return baseAlpha; // dense below the sanity floor → trust the blended recipe
    }
    double overlap = topKDocIdOverlap(bm25, vector, ARBITRATION_TOP_K);
    if (overlap >= ARBITRATION_OVERLAP_MAX) {
      return baseAlpha; // legs agree → trust the blended recipe
    }
    if (bm25CoherenceRatio(bm25) < bm25IncoherenceMin) {
      return baseAlpha; // BM25 has a clear (peaked) winner → coherent → do not down-weight it
    }
    return Math.max(baseAlpha, divergeAlpha);
  }

  /**
   * BM25 "flatness" = top2/top1 of its <em>own</em> scores (intra-leg, so no cross-leg
   * score-incomparability issue). ~1.0 = flat top / no clear winner (incoherent → safe to
   * down-weight); ~0 = peaked / clear lexical winner (coherent → trust it). Returns 1.0 (incoherent)
   * when BM25 has &lt; 2 hits or a non-positive top score.
   */
  static double bm25CoherenceRatio(SearchResult bm25) {
    if (bm25 == null || bm25.hits() == null || bm25.hits().size() < 2) {
      return 1.0;
    }
    float top = bm25.hits().get(0).score();
    if (top <= 0.0f) {
      return 1.0;
    }
    float second = bm25.hits().get(1).score();
    return Math.max(0.0, Math.min(1.0, (double) second / top));
  }

  /** Jaccard overlap of the top-{@code k} doc ids of two legs (rank-based; score-agnostic). */
  static double topKDocIdOverlap(SearchResult a, SearchResult b, int k) {
    Set<String> ta = topKDocIds(a, k);
    Set<String> tb = topKDocIds(b, k);
    if (ta.isEmpty() || tb.isEmpty()) {
      return 1.0; // no comparable signal → treat as "agree" (do not intervene)
    }
    int inter = 0;
    for (String id : ta) {
      if (tb.contains(id)) {
        inter++;
      }
    }
    int union = ta.size() + tb.size() - inter;
    return union == 0 ? 1.0 : (double) inter / union;
  }

  private static Set<String> topKDocIds(SearchResult r, int k) {
    Set<String> ids = new HashSet<>();
    if (r == null || r.hits() == null) {
      return ids;
    }
    int n = 0;
    for (SearchHit h : r.hits()) {
      if (n++ >= k) {
        break;
      }
      if (h.docId() != null) {
        ids.add(h.docId());
      }
    }
    return ids;
  }

  /**
   * Executes a hybrid search combining BM25 text search with KNN vector similarity.
   *
   * <p>Results are fused using Reciprocal Rank Fusion (RRF). Both searches are executed in parallel
   * using virtual threads. The caller binds concrete search implementations via the functional
   * interfaces (e.g., unfiltered, filtered by Query, filtered by RuntimeSearchFilters).
   *
   * @param textLeg the text/BM25 search leg (bound by caller with any filter)
   * @param vectorLeg the vector/KNN search leg (bound by caller with any filter)
   * @param queryText the text query (used for skip heuristic and logging)
   * @param queryVector the query vector for KNN search
   * @param limit maximum number of results to return
   * @param debug if true, enables debug scores in RRF fusion output
   * @param logPrefix label for debug log messages
   * @return search results ordered by fused RRF score (highest first)
   */
  SearchResult executeHybrid(
      TextSearchLeg textLeg,
      VectorSearchLeg vectorLeg,
      String queryText,
      float[] queryVector,
      int limit,
      boolean debug,
      String logPrefix) {
    long startTime = System.currentTimeMillis();

    // Short-circuit: skip vector search for trivial queries.
    // E2E-8: Always populate "sparse"/"vector" debug scores (matching fuseWithRRF key names)
    // so LambdaMART feature collection works even for trivial queries.
    if (shouldSkipVectorSearch(queryText)) {
      log.debug("Skipping vector search for trivial query: '{}'", queryText);
      SearchResult textResult = textLeg.search(queryText, limit);
      int[] sparseRank = {1}; // mutable counter for lambda
      List<SearchHit> annotatedHits =
          textResult.hits().stream()
              .map(
                  hit -> {
                    Map<String, Float> scores = new HashMap<>();
                    scores.put("sparse", hit.score());
                    scores.put("sparse_rank", (float) sparseRank[0]++);
                    scores.put("vector", 0.0f);
                    scores.put("vector_rank", 0f);
                    if (debug) {
                      // No RRF fusion happened â€” vector leg was skipped entirely.
                      // sparse_rrf/vector_rrf are the per-leg RRF contributions (no fusion â†’ 0).
                      // sparse_boost is the BM25 boost term (no boost applied â†’ 0).
                      // rrf_base is the raw RRF score before boost (no fusion â†’ 0).
                      // rrf = final score, which is just the raw BM25 score here.
                      scores.put("sparse_rrf", 0.0f);
                      scores.put("vector_rrf", 0.0f);
                      scores.put("sparse_boost", 0.0f);
                      scores.put("rrf_base", 0.0f);
                      scores.put("rrf", hit.score());
                    }
                    return new SearchHit(
                        hit.docId(), hit.score(), hit.fields(), Map.copyOf(scores));
                  })
              .toList();
      long tookMs = System.currentTimeMillis() - startTime;
      // Tempdoc 549 Slice 3c (U2): single BM25 leg, vector skipped, no fusion.
      SearchResult skipResult = new SearchResult(annotatedHits, textResult.totalHits(), tookMs);
      return HitProvenanceProjector.attachRetrieval(
          skipResult, HitProvenanceProjector.indexLeg(skipResult), null, null, null);
    }

    // Compute candidate limits from config
    ResolvedConfig rc = session.resolvedConfig;
    ResolvedConfig.HybridSearch hs = rc != null ? rc.hybridSearch() : null;
    int candidateMax =
        hs != null ? hs.candidateLimitMax() : DEFAULT_CANDIDATE_LIMIT_MAX;
    int textMult =
        hs != null ? hs.textCandidateMultiplier() : DEFAULT_TEXT_CANDIDATE_MULTIPLIER;
    int vectorMult =
        hs != null
            ? hs.vectorCandidateMultiplier()
            : DEFAULT_VECTOR_CANDIDATE_MULTIPLIER;

    // Ensure caps never undercut the user-visible limit
    candidateMax = Math.max(candidateMax, limit);
    int textCandidateLimit = Math.min(limit * Math.max(1, textMult), candidateMax);
    int vectorCandidateLimit = Math.min(limit * Math.max(1, vectorMult), textCandidateLimit);

    // Execute both searches in parallel using virtual threads
    SearchResult textResult;
    SearchResult vectorResult;

    try {
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        var textFuture =
            CompletableFuture.supplyAsync(
                () -> textLeg.search(queryText, textCandidateLimit), executor);
        var vectorFuture =
            CompletableFuture.supplyAsync(
                () -> vectorLeg.search(queryVector, vectorCandidateLimit), executor);
        textResult = textFuture.join();
        vectorResult = vectorFuture.join();
      } finally {
        executor.close();
      }
    } catch (Exception e) {
      log.warn("Parallel search failed, falling back to sequential: {}", e.getMessage());
      log.debug("Parallel search failed (stack trace)", e);
      textResult = textLeg.search(queryText, textCandidateLimit);
      vectorResult = vectorLeg.search(queryVector, vectorCandidateLimit);
    }

    if (log.isDebugEnabled()) {
      float bm25Top = textResult.hits().isEmpty() ? 0.0f : textResult.hits().get(0).score();
      float vectorTop =
          vectorResult.hits().isEmpty() ? 0.0f : vectorResult.hits().get(0).score();
      log.debug(
          "{} candidate stats: query='{}' bm25Top={} bm25Hits={}"
              + " vectorTop={} vectorHits={} textCandidateLimit={} vectorCandidateLimit={}",
          logPrefix,
          queryText,
          bm25Top,
          textResult.hits().size(),
          vectorTop,
          vectorResult.hits().size(),
          textCandidateLimit,
          vectorCandidateLimit);
    }

    // Select fusion strategy: CC (convex combination) or RRF (default)
    String fusionStrategy = hs != null ? hs.fusionStrategy() : "rrf";
    SearchResult fused;
    if ("cc".equals(fusionStrategy)) {
      double ccAlpha = hs != null ? hs.ccAlpha() : 0.5;
      boolean ccZeroExclude = hs != null && hs.ccZeroExclude();
      // Tempdoc 636 Design v2 — per-query leg arbitration: raise alpha toward dense when dense is
      // confident AND the legs diverge, so the lexical leg cannot suppress a confident dense answer.
      if (hs != null && hs.legArbitrationEnabled()) {
        double adaptiveAlpha =
            computeArbitrationAlpha(
                textResult, vectorResult, ccAlpha, hs.legArbitrationAlphaDiverge(),
                hs.legArbitrationBm25IncoherenceMin());
        if (adaptiveAlpha != ccAlpha) {
          log.debug(
              "{} leg-arbitration: alpha {} -> {} (query='{}', dense down-weights lexical)",
              logPrefix, ccAlpha, adaptiveAlpha, queryText);
          ccAlpha = adaptiveAlpha;
        }
      }
      fused = fuseWithCC(textResult, vectorResult, limit, ccAlpha, debug, ccZeroExclude);
    } else {
      LowSignalGating gating = computeLowSignalGating(textResult, vectorResult, queryText,
          logPrefix + " gating");
      fused =
          fuseWithRRF(
              textResult,
              vectorResult,
              limit,
              debug,
              gating.vectorOnlyCap(),
              gating.vectorWeight(),
              rc);
    }

    // Tempdoc 636 Design v3 — recall-complete rerank pool (default off): guarantee each leg's
    // top-N candidates survive into the returned list so the Head cross-encoder's rerank window
    // sees them, even when fused-score truncation would otherwise bury a confident dense answer
    // (the demonstrated buried-signal failure). Keyword-neutral: it never down-weights a leg.
    if (hs != null && hs.legRecallCompleteEnabled()) {
      int topN = hs.legRecallCompleteTopN();
      List<SearchHit> protectedHits = new ArrayList<>(HybridFusionUtils.topN(textResult, topN));
      protectedHits.addAll(HybridFusionUtils.topN(vectorResult, topN));
      List<SearchHit> spliced =
          HybridFusionUtils.spliceRecallComplete(fused.hits(), protectedHits, limit);
      if (spliced != fused.hits()) {
        log.debug(
            "{} recall-complete: spliced leg top-{} into rerank pool (query='{}')",
            logPrefix, topN, queryText);
        fused = new SearchResult(spliced, fused.totalHits(), fused.tookMs());
      }
    }

    long tookMs = System.currentTimeMillis() - startTime;
    // Tempdoc 549 Slice 3c (U2): attach typed provenance from the typed BM25 (text) + dense
    // (vector) legs and the fusion stage. bm25=textResult, dense=vectorResult, no SPLADE.
    SearchResult fusedResult = new SearchResult(fused.hits(), fused.totalHits(), tookMs);
    String fusionMethod = "cc".equals(fusionStrategy) ? "cc" : "rrf";
    return HitProvenanceProjector.attachRetrieval(
        fusedResult,
        HitProvenanceProjector.indexLeg(textResult),
        null,
        HitProvenanceProjector.indexLeg(vectorResult),
        fusionMethod);
  }

  // ==========================================================================
  // Convenience methods (absorbed from the former LuceneIndexRuntime facade)
  // ==========================================================================

  /**
   * Executes an unfiltered hybrid search combining BM25 text search with KNN vector similarity.
   *
   * @param queryText the text query for BM25 search
   * @param queryVector the query vector for KNN search
   * @param limit maximum number of results to return
   * @return search results ordered by fused RRF score
   */
  SearchResult searchHybrid(String queryText, float[] queryVector, int limit) {
    if (queryText == null || queryText.isBlank()) {
      throw new IllegalArgumentException("queryText must not be null or blank");
    }
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    if (limit <= 0) {
      limit = 10;
    }
    return executeHybrid(
        (t, l) -> textQueryOps.searchText(t, l, null),
        (v, l) -> readPathOps.searchVector(v, l),
        queryText, queryVector, limit, false, "Hybrid");
  }

  /**
   * Executes a hybrid search with an optional Lucene Query pre-filter.
   *
   * @param queryText the text query for BM25 search
   * @param queryVector the query vector for KNN search
   * @param limit maximum number of results
   * @param filter optional Lucene Query to pre-filter candidates (may be null)
   * @return search results ordered by RRF fusion score
   */
  public SearchResult searchHybridFiltered(
      String queryText, float[] queryVector, int limit, Query filter) {
    if (filter == null) {
      return searchHybrid(queryText, queryVector, limit);
    }
    if (queryText == null || queryText.isBlank()) {
      throw new IllegalArgumentException("queryText must not be null or blank");
    }
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    if (limit <= 0) {
      limit = 10;
    }
    return executeHybrid(
        (t, l) -> textQueryOps.searchTextWithFilter(t, l, filter),
        (v, l) -> readPathOps.searchVector(v, l, filter),
        queryText,
        queryVector,
        limit,
        false,
        "Filtered hybrid");
  }

  /**
   * Executes a hybrid search with debug information and optional runtime filters.
   *
   * @param queryText the text query for BM25 search
   * @param queryVector the query vector for KNN search
   * @param limit maximum number of results to return
   * @param filters optional runtime filters (may be null)
   * @return search results with debug scores populated
   */
  public SearchResult searchHybridWithDebug(
      String queryText, float[] queryVector, int limit, RuntimeSearchFilters filters) {
    if (queryText == null || queryText.isBlank()) {
      throw new IllegalArgumentException("queryText must not be null or blank");
    }
    if (queryVector == null || queryVector.length == 0) {
      throw new IllegalArgumentException("queryVector must not be null or empty");
    }
    if (limit <= 0) {
      limit = 10;
    }
    return executeHybrid(
        (t, l) -> textQueryOps.searchText(t, l, filters),
        (v, l) -> readPathOps.searchVector(v, l, buildFilterQueryOnly(filters)),
        queryText,
        queryVector,
        limit,
        true,
        "Hybrid(debug)");
  }

}
