/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import java.util.List;
import java.util.Map;

/**
 * Tempdoc 735 W6 — the ONE {@code justsearch_search} response content model: every fact the
 * response carries (header counts, per-hit rationale, response-level hints, facets, coverage,
 * truncation), computed exactly once by {@link McpToolSurface}'s builder and consumed by both the
 * text renderer (the human-readable {@code content} block) and the structured renderer ({@link
 * McpEvidenceProjection#searchEvidence(io.justsearch.app.api.knowledge.KnowledgeSearchResponse,
 * McpSearchResponseContent)}). This is the mechanism that guarantees tier equivalence by
 * construction (735 G3): before this increment, {@code hints}/{@code facets}/coverage facts
 * existed in the text tier only — a client that delivers {@code structuredContent} instead of
 * text (the CLI 2.1.209-observed default, tempdoc 735) never saw them.
 *
 * <p>Deliberately holds no reference to the canonical {@link
 * io.justsearch.app.api.knowledge.SearchTrace} record — the degradation note/summary is already
 * tier-equivalent (tempdoc 725 W1: {@code appendDegradationNote} in the text renderer and {@code
 * projectDegradationSummary} in the structured renderer independently derive the SAME reasons
 * from the SAME trace object, both already covered by {@code McpSearchTraceLegibilityTest}), so
 * this model does not re-carry it. Holding no SearchTrace reference also means this class needs
 * no separate {@code governance/execution-surfaces.v1.json} registration — it is a plain fact
 * carrier, not a new referencer of the canonical evidence record.
 */
record McpSearchResponseContent(
    long totalHits,
    long tookMs,
    int shownCount,
    boolean truncated,
    List<HitContent> hits,
    Map<String, Map<String, Long>> facets,
    List<String> hints,
    // Tempdoc 789 Phase 2 — the flag-gated delivery framings, carried as content-model facts for
    // the same reason every other fact here is: so the text renderer and the structured renderer
    // read ONE computation and cannot diverge (735 G3). Both are null when their framing flag is
    // off, which is the default — a null fact renders nothing on either tier.
    String evidenceHeader,
    String absenceNote) {

  /**
   * Per-hit facts computed once: the same {@code matchedTerms}/{@code matchedFields} the text
   * tier's {@code Matched:} line renders are read by the structured tier from this record instead
   * of being independently re-derived from {@code hit.matchSpans()} (the pre-735 duplication).
   *
   * <p>{@code continuation} (tempdoc 789 Phase 2, F1) is the per-hit framing line — null unless the
   * continuation framing is enabled AND this hit's delivered text names an indexed entity the query
   * did not.
   */
  record HitContent(
      int rank,
      String title,
      String path,
      double score,
      String preview,
      List<String> matchedTerms,
      List<String> matchedFields,
      String continuation) {

    /** True when no distinctive term overlap was found — renders the "Match basis:" line. */
    boolean semanticFallback() {
      return matchedTerms.isEmpty();
    }
  }
}
