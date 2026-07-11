/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Result of a {@link VduAbstentionGate} check — whether the cascade rejects at this stage, which
 * stage produced the rejection, and the evidence backing the decision (tempdoc 677 §Proposed
 * design). A single shape covers both {@link VduAbstentionGate#inputVerdict} (Stage 0, per page)
 * and {@link VduAbstentionGate#outputVerdict} (Stage 1, per document) — the evidence fields not
 * relevant to a given stage are simply {@code null} rather than needing two record shapes.
 *
 * @param rejected true if this stage judged the input/output untrustworthy
 * @param stage which cascade stage produced this verdict ({@link VduAbstentionGate#STAGE_INPUT_LEGIBILITY}
 *     or {@link VduAbstentionGate#STAGE_OUTPUT_CONFIDENCE}) when {@code rejected} is true; {@code
 *     null} when the verdict passed (nothing rejected it, so there is no rejecting stage to name)
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
 *     {@code null} when Stage 0 passed and Stage 1 rejected instead
 * @param rmsContrast Stage 0 only: paired with {@code laplacianVariance}, or {@code null} under
 *     the same conditions
 */
public record GateVerdict(
    boolean rejected,
    String stage,
    Double meanLogprob,
    Double lowConfidenceFraction,
    Integer tokenCount,
    String finishReason,
    Double laplacianVariance,
    Double rmsContrast) {

  /** A verdict that never rejects — the default for documents the cascade did not flag. */
  public static GateVerdict passed() {
    return new GateVerdict(false, null, null, null, null, null, null, null);
  }
}
