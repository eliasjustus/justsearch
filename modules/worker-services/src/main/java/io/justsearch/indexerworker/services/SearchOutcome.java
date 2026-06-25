/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.ipc.FacetCounts;
import java.util.Map;

/**
 * Runtime-derived state produced by {@code SearchExecutor.execute(decision)} (tempdoc 517).
 *
 * <p>Captures what only the executor can know — retrieval result + per-leg
 * fusion details + correction outcome + chunk-merge runtime application +
 * timing. The response builder reads {@code (SearchOutcome, SearchDecision)}
 * to project the wire response. Most fields are derived from the executed
 * leg shape; some (e.g. {@link #correctionApplied}) are runtime-only.
 *
 * <p>Comprehensive enumeration per §A.11 of the tempdoc.
 *
 * @param result the raw Lucene search result (hits, totalHits, nextCursor, tookMs)
 * @param facets nullable; computed facets if the decision requested them
 * @param queryForSpans the Lucene query to use for match-span computation
 *     (post-correction if correction fired); may be null
 * @param chunkQueryText the (possibly post-correction) chunk-search query text
 * @param retrievalMs orchestrator-measured retrieval phase elapsed milliseconds
 * @param correctionApplied true iff fuzzy or per-term correction fired
 * @param correctedQuery non-null iff correctionApplied; the post-correction query text
 * @param chunkMergeApplied true iff chunk merge ran and produced a merged result
 * @param chunkMergeReason wire reason code (APPLIED or one of the 11 SKIPPED_* codes)
 * @param branchFusionStrategy "rrf" or "cc" — only set when branch fusion ran
 * @param branchFusionContributed which branches contributed (e.g. "whole+chunk")
 * @param spladeExecuted true iff a SPLADE leg actually executed
 */
public record SearchOutcome(
    LuceneRuntimeTypes.SearchResult result,
    Map<String, Map<String, Long>> facets,
    org.apache.lucene.search.Query queryForSpans,
    String chunkQueryText,
    long retrievalMs,
    boolean correctionApplied,
    String correctedQuery,
    boolean chunkMergeApplied,
    SearchReasonCode chunkMergeReason,
    String branchFusionStrategy,
    boolean branchFusionContributed,
    boolean spladeExecuted,
    // Tempdoc 517 follow-up (pass-F finding): the legacy emit-path set the
    // chunk timing fields on ComponentTiming (SearchOrchestrator.java:886-895).
    // The first pass of the refactor left these unthreaded which would have
    // produced a silent wire regression. wire-emitter-elision per the
    // agent-lessons substrate-discipline handle.
    long chunkMergeMs,
    long chunkBm25Ns,
    long chunkKnnNs,
    long chunkSpladeNs,
    boolean chunkRetry,
    long branchFusionNs) {

  /** Convenience factory for empty / blocked decisions where most fields are absent. */
  public static SearchOutcome empty(LuceneRuntimeTypes.SearchResult result) {
    return new SearchOutcome(
        result, null, null, "", 0L, false, null, false, null, null, false, false, 0L, 0L, 0L, 0L,
        false, 0L);
  }
}
