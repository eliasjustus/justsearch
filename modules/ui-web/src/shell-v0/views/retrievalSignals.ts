// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A4 — honest surfacing of the retrieval quality signals the pipeline already emits
 * but the UI did not use (`best_chunk_score`, `score_gap` from `QualitySignals`).
 *
 * HONESTY CONSTRAINT (the §6 producer-owned ceiling + the search-quality register). These are NOT
 * calibrated, user-presentable confidences: per `computeQualitySignals` they are either raw
 * cross-encoder scores OR raw BM25/fusion scores (scheme-dependent, unbounded), and the register
 * marks user-facing calibration as unvalidated open work (FW-009 citation-scorer threshold; Q-007
 * sufficiency classifier; F-019 QPP closed). So this projection must NOT fabricate a percentage or
 * a confidence verdict (that would repeat the live-audit's "unlabeled 100%" anti-pattern and
 * violate 557's "unknown ≠ a value"). It surfaces them only as an explicitly RELATIVE, UNCALIBRATED
 * transparency string — and degrades to empty when the signals are absent. A validated, producer-
 * owned calibration (the real claim-level confidence field, 561 P-A) is tracked as register work.
 */

/** The retrieval-quality subset of `RagMetaPayload` this projection reads. */
export interface RetrievalSignalsInput {
  readonly best_chunk_score?: number;
  readonly score_gap?: number;
}

/**
 * An explicitly-relative, uncalibrated transparency string for the retrieval signals, or '' when no
 * signal is present (unknown ≠ a value). Suitable for a tooltip — never an inline confidence claim.
 */
export function formatRetrievalSignals(m: RetrievalSignalsInput | null | undefined): string {
  if (!m) return '';
  const best = m.best_chunk_score;
  const gap = m.score_gap;
  const hasBest = typeof best === 'number' && Number.isFinite(best) && best > 0;
  if (!hasBest) return '';

  // Relative distinctness only (scale-invariant ratio): how far the top passage stands out from the
  // next. This is a retrieval *shape* cue, not a grounding verdict — labelled as such.
  const parts: string[] = [`top-passage retrieval score ${round(best)}`];
  if (typeof gap === 'number' && Number.isFinite(gap) && gap > 0) {
    const ratio = gap / best;
    parts.push(`margin to next ${round(gap)} (${Math.round(ratio * 100)}% of top)`);
  }
  return `Relative retrieval signals (uncalibrated): ${parts.join(' · ')}`;
}

function round(n: number): string {
  // Keep two significant-ish digits without implying precision the raw score does not have.
  return Math.abs(n) >= 10 ? n.toFixed(0) : n.toFixed(2);
}
