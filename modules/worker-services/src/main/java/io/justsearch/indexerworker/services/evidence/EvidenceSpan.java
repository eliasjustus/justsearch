/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.evidence;

import java.util.List;
import java.util.Objects;

/**
 * The canonical answer-bearing span of a document selected at retrieval time (tempdoc 775 §E).
 *
 * <p>One selection authority, N consumer envelopes: {@code EvidenceSpanSelector} mints this record
 * worker-side, once, at the {@code HighlightingOps.computeExcerptRegions} locus (the full-content
 * MemoryIndex pass). Delivery projects it onto {@code ExcerptRegion} (adding {@code matchSpans});
 * later steps (775 step 2/3) project it onto {@code ContextCitation}/CE input. Registered as the
 * third sibling evidence record in {@code governance/execution-surfaces.v1.json} — a projection
 * substrate under {@code ExcerptRegion} and {@code ContextCitation.excerpt}, not a SearchTrace
 * projection (no shared field; same reasoning that keeps SearchTrace/ContextCitation siblings).
 *
 * <p>Selection is answer-bearing (query-term coverage AND distinguishing-entity coverage), reacting
 * only to the query + the document's own content — never corpus identity (D-005). This record is
 * the pure selection value: char/line window, heading, text, and the selection provenance
 * ({@code selectingLegs}, {@code entityCoverage}). Delivery-envelope-specific detail (the per-window
 * {@code matchSpans}) is added by the projector, not carried here.
 *
 * @param parentDocId parent document id (chunk-branch winners carry parent-relative offsets); empty
 *     for a chunkless/full-document span
 * @param charStart inclusive start offset of the span in the (parent-relative) document content
 * @param charEnd exclusive end offset of the span
 * @param lineStart 1-based line of the span start
 * @param lineEnd 1-based line of the span end
 * @param headingText nearest preceding heading (empty when N/A); carried for the RAG/citation
 *     envelope (step 2), populated best-effort at delivery
 * @param text the selected span text
 * @param selectingLegs the query terms/legs that surfaced this window (selection provenance)
 * @param entityCoverage the distinguishing entities this window carries (the answer-bearing signal —
 *     rare/df-1 tokens or NER-member entities present in the window)
 */
public record EvidenceSpan(
    String parentDocId,
    int charStart,
    int charEnd,
    int lineStart,
    int lineEnd,
    String headingText,
    String text,
    List<String> selectingLegs,
    List<String> entityCoverage) {

  public EvidenceSpan {
    parentDocId = parentDocId == null ? "" : parentDocId;
    headingText = headingText == null ? "" : headingText;
    text = text == null ? "" : text;
    selectingLegs = selectingLegs == null ? List.of() : List.copyOf(selectingLegs);
    entityCoverage = entityCoverage == null ? List.of() : List.copyOf(entityCoverage);
    Objects.requireNonNull(text, "text");
  }

  /** True when this span was selected because it carries at least one distinguishing entity. */
  public boolean isAnswerBearing() {
    return !entityCoverage.isEmpty();
  }
}
