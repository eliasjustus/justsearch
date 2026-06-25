/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.FacetingEngine;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.TextQueryOps;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.services.HighlightingOps;
import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.input.SearchInputs;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import io.justsearch.indexerworker.services.plan.FacetCompute;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.indexerworker.util.TextAnalysisUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.EntityFacetVariants;
import io.justsearch.ipc.EntityVariantBreakdown;
import io.justsearch.ipc.ExcerptRegion;
import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.MatchSpan;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the gRPC {@link SearchResponse} from {@link SearchOutcome} + {@link SearchDecision}
 * (tempdoc 517).
 *
 * <p>Reads runtime-derived state from the outcome (hits, timings, fusion data) and
 * pre-commit state from the decision (degradation reasons, chunk-merge skip reasons,
 * facet discriminator). Owns the facet-computation step + metric emission.
 */
public final class SearchResponseBuilder {
  private static final Logger log = LoggerFactory.getLogger(SearchResponseBuilder.class);
  private static final Set<String> CHUNK_INTERNAL_FIELDS =
      Set.of(
          SchemaFields.IS_CHUNK,
          SchemaFields.PARENT_TOKEN_COUNT,
          "_chunk_source_doc_id",
          SchemaFields.CHUNK_EMBEDDING_STATUS,
          SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT);

  private static final Map<String, String> ENTITY_FACET_TYPE_MAP =
      Map.of(
          SchemaFields.ENTITY_PERSONS_RAW, "PERSON",
          SchemaFields.ENTITY_ORGANIZATIONS_RAW, "ORGANIZATION",
          SchemaFields.ENTITY_LOCATIONS_RAW, "LOCATION");

  private final IndexCountOps indexCountOps;
  private final DocumentFieldOps documentFieldOps;
  private final TextQueryOps textQueryOps;
  private final FacetingEngine facetingEngine;
  private final Supplier<Analyzer> indexAnalyzerSupplier;

  public SearchResponseBuilder(
      IndexCountOps indexCountOps,
      DocumentFieldOps documentFieldOps,
      TextQueryOps textQueryOps,
      FacetingEngine facetingEngine,
      Supplier<Analyzer> indexAnalyzerSupplier) {
    this.indexCountOps = indexCountOps;
    this.documentFieldOps = documentFieldOps;
    this.textQueryOps = textQueryOps;
    this.facetingEngine = facetingEngine;
    this.indexAnalyzerSupplier = indexAnalyzerSupplier;
  }

  public SearchResponse build(
      SearchOutcome outcome, SearchDecision decision, SearchInputs inputs) {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(inputs, "inputs");

    SearchRequest request = inputs.request();
    String queryString = request.getQuery();
    var pipeline =
        request.hasPipeline()
            ? request.getPipeline()
            : io.justsearch.ipc.PipelineConfig.newBuilder().setSparseEnabled(true).build();

    SearchResponse.Builder responseBuilder =
        toGrpcResponseBuilder(
            outcome.result(),
            outcome.retrievalMs(),
            queryString,
            pipeline,
            outcome.queryForSpans(),
            request.getIncludeExcerpts(),
            shouldIncludeDetail(request));

    // Tempdoc 549 Phase E5: the flat query-trace fields (effective_mode, vector_blocked*,
    // hybrid_fallback*, chunk_merge_applied/reason, branch_fusion_*, correction_*, splade_executed/
    // skip_reason, max_idf/avg_ictf/query_scope) are RETIRED. They duplicated the SearchTrace's
    // scalars + stages (effectiveMode / decisionKind / qpp / degradation + the per-stage nodes),
    // which SearchTraceProjector projects from the same (decision, outcome, inputs) below. The
    // unified trace is the single source.

    // Pagination cursor.
    if (outcome.result().nextCursor() != null && !outcome.result().nextCursor().isBlank()) {
      responseBuilder.setNextCursor(outcome.result().nextCursor());
    }

    // Facets — computed here (response builder owns FacetingEngine).
    LuceneRuntimeTypes.FacetsResult facetsResult =
        computeAndAttachFacets(decision, outcome, inputs, responseBuilder);

    // Tempdoc 597: the true matched-document count "M" (the headline's "Top N of M matches"), NOT
    // the bounded fused-candidate-union totalHits. When facets were computed, bind to the SCAN's own
    // matched-doc total so M >= every facet value BY CONSTRUCTION (the facet values are tallied from
    // exactly this scan — a separate searcher.count can disagree slightly on prefix-expanded queries
    // and dip below a facet). Facet-less passes (e.g. quick) fall back to the exact searcher.count.
    // It is also the "matched" rung of the trace funnel (passed to the projector below).
    long matchCount =
        facetsResult != null
            ? facetsResult.matchedDocs()
            : computeMatchCount(decision, outcome, inputs);
    responseBuilder.setMatchCount(matchCount);

    // Tempdoc 549 Phase E3: ComponentTiming retired. Per-stage timing is projected onto the
    // trace's TraceStage.ms by SearchTraceProjector (retrievalMs → FUSION.ms, chunkMergeMs →
    // CHUNK_MERGE.ms, branchFusionMs → BRANCH_FUSION.ms; head adds lambdaMart/crossEncoder ms).

    // Tempdoc 549 Phase E4: SearchIntrospection retired. The unified stage-keyed SearchTrace —
    // the single canonical artifact — is projected from the typed decision/outcome and is
    // always-on (525's always-on posture; the structural trace is lightweight). The head
    // composes its own stages onto this (Phase C).
    io.justsearch.ipc.SearchTrace workerTrace =
        SearchTraceProjector.project(decision, outcome, inputs, matchCount);
    responseBuilder.setSearchTrace(workerTrace);
    // 553 Phase 4a (worker): project the worker trace slice onto the active worker span — the
    // worker telemetry is a projection of the same record, not independent authoring.
    io.opentelemetry.api.trace.Span.current()
        .setAllAttributes(WorkerSpanProjection.attributesOf(workerTrace));

    SearchResponse response = responseBuilder.build();

    // Metrics (preserved from legacy SearchOrchestrator:902).
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.recordSearch(outcome.retrievalMs(), outcome.result().totalHits());
    if (decision instanceof SearchDecision.BlockedDecision) {
      metrics.recordVectorBlocked();
    }
    if (decision instanceof SearchDecision.MultiLegDecision ml && ml.hybridFallback().isPresent()) {
      metrics.recordHybridFallback();
    }
    if (!outcome.spladeExecuted()
        && decision instanceof SearchDecision.MultiLegDecision ml
        && ml.spladeSkip().isPresent()) {
      metrics.recordSpladeSkipped();
    }

    return response;
  }

  private LuceneRuntimeTypes.FacetsResult computeAndAttachFacets(
      SearchDecision decision,
      SearchOutcome outcome,
      SearchInputs inputs,
      SearchResponse.Builder responseBuilder) {
    LuceneRuntimeTypes.FacetsResult facetsResult = null;

    if (decision instanceof SearchDecision.SparseShortcut sparse
        && sparse.facets().isPresent()
        && outcome.queryForSpans() != null) {
      var f = sparse.facets().get();
      try {
        facetsResult =
            facetingEngine.computeFacets(outcome.queryForSpans(), f.fields(), f.maxDocsScanned());
      } catch (RuntimeException e) {
        log.debug("Facet computation failed (sparse path): {}", e.getMessage());
      }
    } else if (decision instanceof SearchDecision.MultiLegDecision ml
        && ml.facets().isPresent()) {
      var f = ml.facets().get();
      try {
        var facetQuery =
            textQueryOps.buildTextQuery(
                f.queryString(),
                f.filters(),
                LuceneRuntimeTypes.QuerySyntax.SIMPLE);
        if (facetQuery != null) {
          facetsResult = facetingEngine.computeFacets(facetQuery, f.fields(), f.maxDocsScanned());
        }
      } catch (org.apache.lucene.queryparser.classic.ParseException e) {
        log.debug("Facet-only query parse failed (multi-leg path): {}", e.getMessage());
      } catch (RuntimeException e) {
        log.debug("Facet computation failed (multi-leg path): {}", e.getMessage());
      }
    }

    if (facetsResult != null
        && facetsResult.facets() != null
        && !facetsResult.facets().isEmpty()) {
      Map<String, Map<String, Long>> facetData =
          mergeEntityFacets(facetsResult.facets(), responseBuilder, inputs.clusterSnapshot());
      for (var entry : facetData.entrySet()) {
        FacetCounts.Builder counts = FacetCounts.newBuilder();
        if (entry.getValue() != null && !entry.getValue().isEmpty()) {
          counts.putAllCounts(entry.getValue());
        }
        responseBuilder.putFacets(entry.getKey(), counts.build());
      }
      responseBuilder.setFacetsTruncated(facetsResult.truncated());
    }
    return facetsResult;
  }


  /**
   * Tempdoc 597: the true matched-document count, computed over the SAME chunk-excluded query the
   * facets scan so {@code matchCount >=} every facet value by construction. Sparse path reuses the
   * boosted {@code outcome.queryForSpans()} (the FromRetrievalQuery facet query); multi-leg rebuilds
   * the fresh SIMPLE BM25 query from the request + runtime filters, omitting boost filters (the
   * FromFreshBm25 facet query). Empty/blocked → 0. Independent of whether facets were requested, so
   * the facet-less quick pass still carries a true count. Failure is non-fatal (returns 0).
   */
  private int computeMatchCount(
      SearchDecision decision, SearchOutcome outcome, SearchInputs inputs) {
    try {
      return switch (decision) {
        case SearchDecision.SparseShortcut s ->
            outcome.queryForSpans() != null
                ? indexCountOps.countQuery(outcome.queryForSpans())
                : 0;
        case SearchDecision.MultiLegDecision m -> {
          org.apache.lucene.search.Query q =
              textQueryOps.buildTextQuery(
                  inputs.request().getQuery(),
                  inputs.runtimeFilters(),
                  LuceneRuntimeTypes.QuerySyntax.SIMPLE);
          yield q != null ? indexCountOps.countQuery(q) : 0;
        }
        case SearchDecision.EmptyQueryDecision e -> 0;
        case SearchDecision.BlockedDecision b -> 0;
      };
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.debug("matchCount query parse failed: {}", e.getMessage());
      return 0;
    } catch (RuntimeException e) {
      log.debug("matchCount computation failed: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Tempdoc 549 Phase D2 (principle 6): the optional numeric detail tier inside the per-hit
   * trace ({@code HitStage.detail}) — and its legacy {@code debug_scores} mirror — is emitted
   * only when the request opts in via {@code include_detail}. The deprecated {@code debug} flag
   * is honored as a transitional alias. The structural per-hit slice (stage id + rank + score)
   * is always-on regardless; this gates only the heavyweight numeric map.
   */
  private static boolean shouldIncludeDetail(SearchRequest request) {
    return request.getIncludeDetail() || request.getDebug();
  }


  /**
   * Tempdoc 549 (full thesis, Phase B per-hit): the per-doc slice of the unified stage
   * vocabulary. Rank/score per stage come from the typed {@code HitProvenanceSignals}; the
   * numeric detail tier is a LOSSLESS partition of {@code debugScores} (every key assigned to
   * exactly one stage), so the union of all {@code HitStage.detail} equals {@code debugScores}
   * — the invariant GPL feature collection depends on (Phase D). Package-private for the
   * losslessness guard test.
   */
  static List<io.justsearch.ipc.HitStage> buildHitStages(
      LuceneRuntimeTypes.HitProvenanceSignals sig,
      Map<String, Float> debugScores,
      Float sparseOnlyScore) {
    java.util.LinkedHashMap<String, io.justsearch.ipc.HitStage.Builder> byStage =
        new java.util.LinkedHashMap<>();

    // Seed retriever/fusion/chunk/branch legs (rank/score) from the typed signals, in canonical
    // order. ccScore/fusionScore are nullable → set the stage score only when present.
    if (sig != null) {
      if (sig.bm25() != null) {
        stageBuilder(byStage, "sparse-retrieval")
            .setRank(sig.bm25().rank())
            .setScore(sig.bm25().rawScore());
      }
      if (sig.dense() != null) {
        stageBuilder(byStage, "dense-retrieval")
            .setRank(sig.dense().rank())
            .setScore(sig.dense().rawScore());
      }
      if (sig.splade() != null) {
        stageBuilder(byStage, "splade-retrieval")
            .setRank(sig.splade().rank())
            .setScore(sig.splade().rawScore());
      }
      if (sig.fusion() != null) {
        stageBuilder(byStage, "fusion").setScore(sig.fusion().score());
      }
      if (sig.chunkMerge() != null && sig.chunkMerge().ccScore() != null) {
        stageBuilder(byStage, "chunk-merge").setScore(sig.chunkMerge().ccScore());
      } else if (sig.chunkMerge() != null) {
        stageBuilder(byStage, "chunk-merge");
      }
      if (sig.branchFusion() != null && sig.branchFusion().fusionScore() != null) {
        stageBuilder(byStage, "branch-fusion").setScore(sig.branchFusion().fusionScore());
      } else if (sig.branchFusion() != null) {
        stageBuilder(byStage, "branch-fusion");
      }
    }

    // Sparse-only shortcut: no typed provenance, so synthesize the structural sparse-retrieval
    // score (always-on, Phase D2) so the per-hit slice is non-empty even when the numeric detail
    // tier is not requested. No-op when a typed bm25 leg already seeded the stage.
    if ((sig == null || sig.bm25() == null) && sparseOnlyScore != null) {
      stageBuilder(byStage, "sparse-retrieval").setScore(sparseOnlyScore);
    }

    // Merge the debug_scores detail tier — lossless: each key → exactly one stage's detail map.
    // Also creates a stage node for any key whose stage had no typed leg (so nothing is dropped).
    if (debugScores != null) {
      for (Map.Entry<String, Float> e : debugScores.entrySet()) {
        stageBuilder(byStage, classifyDebugKey(e.getKey())).putDetail(e.getKey(), e.getValue());
      }
    }

    return byStage.values().stream().map(io.justsearch.ipc.HitStage.Builder::build).toList();
  }

  private static io.justsearch.ipc.HitStage.Builder stageBuilder(
      java.util.LinkedHashMap<String, io.justsearch.ipc.HitStage.Builder> byStage, String wireId) {
    return byStage.computeIfAbsent(
        wireId, id -> io.justsearch.ipc.HitStage.newBuilder().setId(id));
  }

  /** Maps a debug_scores key to its owning stage wireId. Total (catch-all → fusion) → lossless. */
  private static String classifyDebugKey(String k) {
    if (k.startsWith("branch_merge_") || k.startsWith("whole_branch") || k.startsWith("chunk_branch")) {
      return "branch-fusion";
    }
    if (k.startsWith("chunk_")) {
      return "chunk-merge";
    }
    if (k.startsWith("sparse")) {
      return "sparse-retrieval";
    }
    if (k.startsWith("vector")) {
      return "dense-retrieval";
    }
    if (k.startsWith("splade")) {
      return "splade-retrieval";
    }
    return "fusion";
  }

  // Tempdoc 549 Phase E5: effectiveModeFor retired with the flat fields — the trace's
  // effectiveMode is projected by SearchTraceProjector.

  // ============================================================
  // Helpers (moved verbatim from SearchOrchestrator)
  // ============================================================

  private SearchResponse.Builder toGrpcResponseBuilder(
      LuceneRuntimeTypes.SearchResult result,
      long tookMs,
      String queryString,
      io.justsearch.ipc.PipelineConfig pipeline,
      org.apache.lucene.search.Query queryForSpans,
      boolean includeExcerpts,
      boolean includeDetail) {
    SearchResponse.Builder responseBuilder =
        SearchResponse.newBuilder().setTotalHits(result.totalHits()).setTookMs(tookMs);

    Analyzer analyzer = indexAnalyzerSupplier.get();
    Set<String> queryTerms = TextAnalysisUtils.normalizedQueryTerms(queryString);
    Map<String, Double> termIdfWeights =
        includeExcerpts && analyzer != null
            ? computeTermIdfWeights(
                TextAnalysisUtils.analyzeTerms(analyzer, SchemaFields.CONTENT, queryString))
            : Map.of();

    int resultIndex = 0;
    for (LuceneRuntimeTypes.SearchHit hit : result.hits()) {
      SearchResult.Builder resultBuilder = SearchResult.newBuilder().setScore(hit.score());

      String parentDocId = hit.fields().get(SchemaFields.PARENT_DOC_ID);
      boolean isChunkHit = parentDocId != null && !parentDocId.isEmpty();

      if (isChunkHit) {
        resultBuilder.setId(parentDocId);
      } else if (hit.docId() != null) {
        resultBuilder.setId(hit.docId());
      }

      for (var entry : hit.fields().entrySet()) {
        if (SchemaFields.CONTENT.equals(entry.getKey())
            || SchemaFields.CHUNK_CONTENT.equals(entry.getKey())
            || SchemaFields.PARENT_TOKEN_COUNT.equals(entry.getKey())
            || (isChunkHit && CHUNK_INTERNAL_FIELDS.contains(entry.getKey()))) {
          continue;
        }
        resultBuilder.putFields(entry.getKey(), entry.getValue());
      }

      if (isChunkHit && resultIndex < 10) {
        resolveParentMetadata(parentDocId, resultBuilder);
      }

      // Compute the effective debug-score map ONCE: the worker's own per-hit scores, or — for
      // the sparse-only shortcut where the hit carries none — the synthesized sparse/vector pair
      // LambdaMART feature collection has always relied on. The SAME map feeds both the legacy
      // debug_scores field and the unified trace's per-hit detail tier (below), so the trace
      // detail is an exact superset of debug_scores for EVERY hit — the byte-equivalence
      // prerequisite for migrating GPL off debug_scores (Phase D) and retiring it (Phase E).
      Map<String, Float> effectiveDebug;
      if (hit.debugScores() != null && !hit.debugScores().isEmpty()) {
        effectiveDebug = hit.debugScores();
      } else if (pipeline.getSparseEnabled()
          && !pipeline.getDenseEnabled()
          && !pipeline.getSpladeEnabled()) {
        effectiveDebug = Map.of("sparse", hit.score(), "vector", 0.0f);
      } else {
        effectiveDebug = Map.of();
      }
      // Tempdoc 549 Phase D2 (principle 6): the numeric detail tier (the per-hit HitStage.detail
      // map) is emitted only when the request opts in via include_detail; the structural slice
      // below is always-on. The legacy debug_scores wire mirror is retired (Phase E1) — the
      // internal effectiveDebug map now flows solely into HitStage.detail.
      Map<String, Float> detailTier = includeDetail ? effectiveDebug : Map.of();

      // Tempdoc 549 (full thesis, Phase B per-hit): the per-doc slice of the unified stage
      // vocabulary — rank/score per stage from the typed provenance (always-on structural slice),
      // plus the numeric detail tier (gated on include_detail, Phase D2) which is a LOSSLESS
      // superset of `effectiveDebug` when requested, so GPL feature collection reads the trace
      // instead of debug_scores (Phase D). The sparse-only shortcut carries no typed provenance,
      // so its structural sparse score is synthesized here (always-on, independent of detail).
      Float sparseOnlyScore =
          pipeline.getSparseEnabled() && !pipeline.getDenseEnabled() && !pipeline.getSpladeEnabled()
              ? hit.score()
              : null;
      resultBuilder.addAllTrace(buildHitStages(hit.provenance(), detailTier, sparseOnlyScore));

      Map<String, String> spanFields = hit.fields();
      if (isChunkHit) {
        String chunkText = hit.fields().get(SchemaFields.CHUNK_CONTENT);
        if (chunkText != null && !chunkText.isEmpty()) {
          spanFields = new HashMap<>(hit.fields());
          spanFields.put(SchemaFields.CONTENT_PREVIEW, chunkText);
        }
      }

      boolean hasLexicalTerms = pipeline.getSparseEnabled() || pipeline.getSpladeEnabled();
      for (String f :
          TextAnalysisUtils.computeMatchedFields(hasLexicalTerms, queryTerms, spanFields)) {
        if (f != null && !f.isBlank()) {
          resultBuilder.addMatchedFields(f);
        }
      }

      if (analyzer != null && hasLexicalTerms) {
        List<MatchSpan> spans =
            queryForSpans != null
                ? HighlightingOps.computeMatchSpansFromQuery(analyzer, queryForSpans, spanFields)
                : HighlightingOps.computeMatchSpans(analyzer, queryString, spanFields);
        for (MatchSpan span : spans) {
          resultBuilder.addMatchSpans(span);
        }
      }

      if (includeExcerpts && resultIndex < 10 && analyzer != null && hasLexicalTerms) {
        org.apache.lucene.search.Query excerptQuery =
            queryForSpans != null
                ? queryForSpans
                : HighlightingOps.buildTermQuery(analyzer, queryString);
        if (excerptQuery != null) {
          String excerptContent;
          int lineOffset;
          if (isChunkHit) {
            excerptContent = hit.fields().get(SchemaFields.CHUNK_CONTENT);
            lineOffset = parseIntOrZero(hit.fields().get(SchemaFields.CHUNK_START_LINE));
          } else {
            excerptContent = documentFieldOps.getDocumentContent(hit.docId());
            lineOffset = 0;
          }
          if (excerptContent != null && !excerptContent.isEmpty()) {
            List<ExcerptRegion> regions =
                HighlightingOps.computeExcerptRegions(
                    analyzer, excerptQuery, excerptContent, 3, termIdfWeights);
            for (ExcerptRegion region : regions) {
              if (lineOffset > 0) {
                resultBuilder.addExcerptRegions(
                    region
                        .toBuilder()
                        .setApproxLine(region.getApproxLine() + lineOffset)
                        .build());
              } else {
                resultBuilder.addExcerptRegions(region);
              }
            }
          }
        }
      }
      resultIndex++;

      responseBuilder.addResults(resultBuilder.build());
    }

    return responseBuilder;
  }

  private void resolveParentMetadata(String parentDocId, SearchResult.Builder resultBuilder) {
    String title = documentFieldOps.getDocumentField(parentDocId, SchemaFields.TITLE);
    if (title != null && !title.isEmpty()) {
      resultBuilder.putFields(SchemaFields.TITLE, title);
    }
    String filename = documentFieldOps.getDocumentField(parentDocId, SchemaFields.FILENAME);
    if (filename != null && !filename.isEmpty()) {
      resultBuilder.putFields(SchemaFields.FILENAME, filename);
    }
  }

  private static int parseIntOrZero(String value) {
    if (value == null || value.isEmpty()) return 0;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private Map<String, Double> computeTermIdfWeights(Set<String> terms) {
    if (terms == null || terms.isEmpty()) return Map.of();
    long numDocs = indexCountOps.docCount();
    if (numDocs <= 0) return Map.of();
    Map<String, Integer> docFreqs = textQueryOps.getTermDocFreqs(SchemaFields.CONTENT, terms);
    Map<String, Double> weights = new HashMap<>();
    for (var entry : docFreqs.entrySet()) {
      int df = entry.getValue();
      weights.put(entry.getKey(), Math.log((numDocs - df + 0.5) / (df + 0.5) + 1.0));
    }
    return weights;
  }

  private Map<String, Map<String, Long>> mergeEntityFacets(
      Map<String, Map<String, Long>> rawFacets,
      SearchResponse.Builder responseBuilder,
      EntityClusterSnapshot snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return rawFacets;
    }

    Map<String, Map<String, Long>> merged = new HashMap<>();
    for (var entry : rawFacets.entrySet()) {
      String field = entry.getKey();
      String entityType = ENTITY_FACET_TYPE_MAP.get(field);
      if (entityType == null) {
        merged.put(field, entry.getValue());
        continue;
      }

      Map<String, Map<String, Long>> canonicalGroups = new HashMap<>();
      for (var countEntry : entry.getValue().entrySet()) {
        String rawForm = countEntry.getKey();
        long count = countEntry.getValue();
        String canonical = snapshot.getCanonical(entityType, rawForm);
        canonicalGroups
            .computeIfAbsent(canonical, k -> new HashMap<>())
            .put(rawForm, count);
      }

      Map<String, Long> mergedCounts = new HashMap<>();
      EntityFacetVariants.Builder variantsBuilder = EntityFacetVariants.newBuilder();
      for (var groupEntry : canonicalGroups.entrySet()) {
        String canonical = groupEntry.getKey();
        Map<String, Long> variants = groupEntry.getValue();
        long total = variants.values().stream().mapToLong(Long::longValue).sum();
        mergedCounts.put(canonical, total);

        if (variants.size() > 1) {
          variantsBuilder.addEntries(
              EntityVariantBreakdown.newBuilder()
                  .setCanonicalForm(canonical)
                  .setTotalCount(total)
                  .putAllVariants(variants)
                  .build());
        }
      }
      merged.put(field, mergedCounts);

      EntityFacetVariants variants = variantsBuilder.build();
      if (variants.getEntriesCount() > 0) {
        responseBuilder.putEntityFacetVariants(field, variants);
      }
    }
    return merged;
  }
}
