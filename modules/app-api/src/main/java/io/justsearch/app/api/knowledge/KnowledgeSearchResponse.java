/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Search response for the Knowledge Server-backed HTTP API (e.g., POST /api/knowledge/search).
 *
 * <p>Stability: stable (API contract)
 */
@RecordBuilder
public record KnowledgeSearchResponse(
    long totalHits,
    // Tempdoc 597: the TRUE matched-document count (searcher.count over the chunk-excluded query),
    // distinct from totalHits (the bounded fused-candidate-union / retrieval window). The FE
    // headline binds to this ("Top N of M matches"); totalHits stays for telemetry / the trace.
    long matchCount,
    long tookMs,
    List<Hit> results,
    String nextCursor,
    Map<String, Map<String, Long>> facets,
    Boolean facetsTruncated,
    // Tempdoc 549 U4 (Slice 6): the 15 flat query-trace fields (effectiveMode, vectorBlocked,
    // hybridFallback, chunkMerge*, correction*, expansionApplied, splade*, lambdaMartApplied,
    // crossEncoder*) were removed — the canonical `introspection` trace (+ its headStages) is now
    // the single source. The worker proto still carries the raw signals; the head folds them into
    // `introspection` (KnowledgeIntrospectionMapper + buildHeadStages), and all consumers (FE
    // explain panel, jseval) read the trace.
    Map<String, List<EntityVariantBreakdown>> entityFacetVariants,
    IndexCapabilities indexCapabilities,
    // Tempdoc 549 Phase E3: pipelineExecution retired — per-stage timing + component statuses
    // are on the unified trace (TraceStage.ms / status).
    QueryUnderstanding queryUnderstanding,
    FilterNormalization filterNormalization,
    // Tempdoc 549 Phase E4: introspection retired — the unified trace is the single source.
    SearchTrace searchTrace) {

  public KnowledgeSearchResponse {
    results = results == null ? List.of() : List.copyOf(results);
    facets = facets == null ? Map.of() : Map.copyOf(facets);
    entityFacetVariants =
        entityFacetVariants == null
            ? Map.of()
            : entityFacetVariants.entrySet().stream()
                .collect(
                    Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, e -> List.copyOf(e.getValue())));
  }

  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static record Hit(
      String id,
      double score,
      Map<String, String> fields,
      List<String> matchedFields,
      List<MatchSpan> matchSpans,
      List<ExcerptRegion> excerptRegions,
      // Tempdoc 549: per-hit slice of the unified stage vocabulary — the single per-hit
      // ranking-decision record. Subsumes the retired debugScores map (E1) + the leg-keyed
      // HitProvenance (E2); the numeric detail tier now lives in HitStage.detail.
      List<SearchTrace.HitStage> trace) {
    public Hit {
      fields = fields == null ? Map.of() : Map.copyOf(fields);
      matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
      matchSpans = matchSpans == null ? List.of() : List.copyOf(matchSpans);
      excerptRegions = excerptRegions == null ? List.of() : List.copyOf(excerptRegions);
      trace = (trace == null || trace.isEmpty()) ? null : List.copyOf(trace);
    }
  }

  /**
   * Index capability snapshot included in search responses (250: Phase 3). Coverage values are
   * fractions (0.0–1.0). Null means the data is not yet available (Worker status never fetched).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record IndexCapabilities(
      Double embeddingCoverage,
      Double spladeCoverage,
      Double chunkEmbeddingCoverage,
      Boolean crossEncoderAvailable) {}

  // Tempdoc 549 Phase E3: PipelineExecution + ComponentStatus retired — per-stage timing
  // (TraceStage.ms) and component statuses (TraceStage.status/reason) live on the unified trace.

  /** Best-effort precise match span for a stored field value. */
  public static record MatchSpan(String field, int startChar, int endChar, String term) {
    public MatchSpan {
      field = field == null ? "" : field;
      term = term == null ? "" : term;
      startChar = Math.max(0, startChar);
      endChar = Math.max(startChar, endChar);
    }
  }

  /** A query-focused excerpt region extracted from the full document content. */
  public static record ExcerptRegion(
      String text, int startChar, int endChar, int approxLine, List<MatchSpan> matchSpans) {
    public ExcerptRegion {
      text = text == null ? "" : text;
      matchSpans = matchSpans == null ? List.of() : List.copyOf(matchSpans);
      startChar = Math.max(0, startChar);
      endChar = Math.max(startChar, endChar);
      approxLine = Math.max(1, approxLine);
    }
  }

  /** Variant breakdown for a disambiguated entity canonical form. */
  public static record EntityVariantBreakdown(
      String canonicalForm, long totalCount, Map<String, Long> variants) {
    public EntityVariantBreakdown {
      canonicalForm = canonicalForm == null ? "" : canonicalForm;
      variants = variants == null ? Map.of() : Map.copyOf(variants);
    }
  }

  /**
   * Query understanding metadata (366 §3a). Surfaces what the QU layer (363 Track B) extracted
   * from the query and applied as soft boosts. Null when QU was bypassed (explicit filters,
   * cursor, navigational query, Brain offline).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QueryUnderstanding(
      Map<String, List<String>> appliedBoosts,
      long latencyMs,
      String expectedAnswerType) {
    public QueryUnderstanding {
      appliedBoosts = appliedBoosts == null ? Map.of() : Map.copyOf(appliedBoosts);
    }
  }

  /**
   * Filter normalization metadata (366). Surfaces what filter values were rewritten to match the
   * index vocabulary. Null when normalization was not attempted (no explicit filters, Brain offline,
   * feature disabled).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FilterNormalization(
      Map<String, List<String>> original,
      Map<String, List<String>> normalized,
      long latencyMs,
      String source) { // "llm", "case_only", "exact_match", "timeout"
  }
}
