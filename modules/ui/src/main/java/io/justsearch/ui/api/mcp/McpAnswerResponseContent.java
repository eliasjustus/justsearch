/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import java.util.List;

/**
 * Tempdoc 735 W6 — the ONE {@code justsearch_answer} response content model, the answer-side twin
 * of {@link McpSearchResponseContent}. Computed exactly once by {@link McpToolSurface}'s builder
 * and consumed by both the text renderer and the structured renderer ({@link
 * McpEvidenceProjection#answerEvidence(io.justsearch.app.api.DocumentService.ContextResult,
 * McpAnswerResponseContent)}).
 *
 * <p>{@code comparativeHint}/{@code enrichmentHint}/{@code zeroResultHint} are kept as distinct
 * optional fields (rather than folded into an undifferentiated list) because the text renderer
 * formats each with its own pre-existing, distinct literal wording/placement (the comparative
 * hint is a bare sentence; the enrichment and zero-result hints are prefixed {@code "Hint: "}
 * lines) — collapsing them into one generic list would force a text-rendering change this
 * increment does not make. {@code hints} is the flattened, order-preserving view of the same
 * three facts for the structured tier, which has no such formatting constraint.
 *
 * <p>Tempdoc 770 §F.5 removed the {@code facets} component: it was sourced from a second full
 * hybrid search fired on every {@code justsearch_answer} call purely to build a 3-field sidecar.
 * The answer path has no other facet source, so the fact goes with the round-trip.
 */
record McpAnswerResponseContent(
    long passages,
    long distinctDocs,
    boolean contextTruncated,
    String comparativeHint,
    String enrichmentHint,
    String zeroResultHint,
    List<String> hints) {}
