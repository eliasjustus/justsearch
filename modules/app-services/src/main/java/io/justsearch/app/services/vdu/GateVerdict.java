/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Result of a {@link VduAbstentionGate} check — whether the cascade rejects at this stage, which
 * stage produced the rejection, and the evidence backing the decision (tempdoc 677 §Proposed
 * design). A single shape covers {@link VduAbstentionGate#inputVerdict} (Stage 0, per page),
 * {@link VduAbstentionGate#outputVerdict} (Stage 1, per document), and {@link
 * VduAbstentionGate#agreementVerdict} (Stage 2, re-sample agreement probe) — the evidence fields
 * not relevant to a given stage are simply {@code null} rather than needing three record shapes.
 *
 * @param rejected true if this stage judged the input/output untrustworthy — equivalent to
 *     {@code band() == Band.REJECT}, kept as its own field because it is the field every
 *     downstream consumer ({@code VduBatchProcessor}) actually branches on
 * @param band the tri-state cascade decision: {@link Band#PASS} (trust the output), {@link
 *     Band#AMBIGUOUS} (Stage 1 only — neither healthy nor bad enough to reject outright; the
 *     caller must run Stage 2 to resolve it), or {@link Band#REJECT} (abstain). A verdict handed
 *     to {@code VduBatchProcessor} is never {@code AMBIGUOUS} — that band only ever appears as
 *     {@code VduProcessor}'s interim Stage-1 result, resolved to PASS or REJECT before the
 *     document-level result is returned
 * @param stage which cascade stage produced the REJECT ({@link
 *     VduAbstentionGate#STAGE_INPUT_LEGIBILITY}, {@link VduAbstentionGate#STAGE_OUTPUT_CONFIDENCE},
 *     or {@link VduAbstentionGate#STAGE_AGREEMENT}) when {@code rejected} is true; {@code null}
 *     otherwise — including for an interim {@code AMBIGUOUS} verdict, since nothing has rejected
 *     it yet
 * @param meanLogprob Stage 1 only: token-weighted mean logprob across the pages sent to the
 *     model, or {@code null} when Stage 1 did not run (Stage 0 rejection) or the server returned
 *     no logprobs (NO SIGNAL — never itself a rejection reason, see {@link VduAbstentionGate})
 * @param lowConfidenceFraction Stage 1 only: token-weighted fraction of low-confidence tokens, or
 *     {@code null} under the same conditions as {@code meanLogprob}
 * @param tokenCount Stage 1 only: total per-token logprob entries observed across the pages sent
 *     to the model, or {@code null} when Stage 1 did not run
 * @param finishReason Stage 1 only: the aggregated OpenAI-compatible finish reason across the
 *     pages sent to the model ({@code "stop"}, {@code "length"}, an anomalous value, or {@code
 *     null} when no page reported one), or {@code null} when Stage 1 did not run
 * @param laplacianVariance Stage 0 only: the legibility measurement(s) behind this verdict, or
 *     {@code null} for a Stage 1/2 verdict
 * @param rmsContrast Stage 0 only: paired with {@code laplacianVariance}, or {@code null} under
 *     the same conditions
 * @param agreement Stage 2 only: the Jaccard word-set agreement between the probed page's Pass 1
 *     text and its re-sampled probe text ({@link VduAbstentionGate#jaccardAgreement}), or {@code
 *     null} when Stage 2 did not run
 * @param probedPage Stage 2 only: the 1-based page number Stage 2 re-sampled (the page with the
 *     worst per-page Stage 1 signals — see {@code VduProcessor}'s selection), or {@code null}
 *     when Stage 2 did not run
 */
public record GateVerdict(
    boolean rejected,
    Band band,
    String stage,
    Double meanLogprob,
    Double lowConfidenceFraction,
    Integer tokenCount,
    String finishReason,
    Double laplacianVariance,
    Double rmsContrast,
    Double agreement,
    Integer probedPage) {

  /** Tri-state cascade decision — see the {@link GateVerdict} class javadoc's {@code band} param. */
  public enum Band {
    PASS,
    AMBIGUOUS,
    REJECT
  }

  /** A verdict that never rejects — the default for documents the cascade did not flag. */
  public static GateVerdict passed() {
    return new GateVerdict(
        false, Band.PASS, null, null, null, null, null, null, null, null, null);
  }

  /**
   * Returns a copy with {@code probedPage} set, preserving all other fields. {@link
   * VduAbstentionGate#agreementVerdict} cannot populate this field itself — the gate is pure and
   * has no visibility into page numbers, which is {@code VduProcessor}'s (the caller's)
   * responsibility to attach once it knows which page it probed.
   */
  public GateVerdict withProbedPage(int probedPage) {
    return new GateVerdict(
        rejected,
        band,
        stage,
        meanLogprob,
        lowConfidenceFraction,
        tokenCount,
        finishReason,
        laplacianVariance,
        rmsContrast,
        agreement,
        probedPage);
  }
}
