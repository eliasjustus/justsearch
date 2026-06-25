// SPDX-License-Identifier: Apache-2.0
/**
 * Resolution recovery policies (tempdoc 499 §3).
 *
 * Each policy transforms a {@link ResolutionResult} into a {@link RecoveryAction}
 * based on the consumer context.
 */

import { damerauLevenshtein, type ResolutionResult, type Suggestion } from './resolution.js';

export type RecoveryAction =
  | { readonly kind: 'proceed'; readonly id: string }
  | { readonly kind: 'auto-correct'; readonly id: string; readonly originalId: string }
  | { readonly kind: 'suggest'; readonly attemptedId: string; readonly alternatives: Suggestion[] }
  | { readonly kind: 'abort'; readonly attemptedId: string; readonly reason: string };

export type RecoveryPolicy = (result: ResolutionResult) => RecoveryAction;

export const strictPolicy: RecoveryPolicy = (result) => {
  if (result.status === 'resolved') return { kind: 'proceed', id: result.id };
  if (result.status === 'redirected') return { kind: 'abort', attemptedId: result.originalId, reason: 'Strict: redirects not allowed' };
  return { kind: 'abort', attemptedId: result.attemptedId, reason: result.diagnosis.detail };
};

/**
 * Interactive policy with two-tier auto-correction gate (tempdoc 499 §6.4).
 *
 * Typo gate: DL ≤ 2 AND confidence ≥ 0.65 AND exactly 1 candidate within DL ≤ (DL+2).
 * Truncation gate: tokenJaccard ≥ 0.60 AND prefixScore ≥ 0.55 AND confidence ≥ 0.55.
 */
export const interactivePolicy: RecoveryPolicy = (result) => {
  if (result.status === 'resolved') return { kind: 'proceed', id: result.id };
  if (result.status === 'redirected') return { kind: 'auto-correct', id: result.id, originalId: result.originalId };
  if (result.alternatives.length === 0) {
    return { kind: 'abort', attemptedId: result.attemptedId, reason: result.diagnosis.detail };
  }

  const top = result.alternatives[0]!;
  const second = result.alternatives[1];
  const dl = damerauLevenshtein(result.attemptedId, top.id);
  const gap = second ? top.confidence - second.confidence : 1.0;

  // Typo gate: DL ≤ 2, confidence ≥ 0.65, gap ≥ 0.15
  if (dl <= 2 && top.confidence >= 0.65 && gap >= 0.15) {
    return { kind: 'auto-correct', id: top.id, originalId: result.attemptedId };
  }
  // Truncation gate: candidate is longer, confidence ≥ 0.55, gap ≥ 0.15
  if (top.confidence >= 0.55 && gap >= 0.15) {
    const queryLen = result.attemptedId.length;
    const candLen = top.id.length;
    if (candLen > queryLen && dl <= candLen - queryLen + 2) {
      return { kind: 'auto-correct', id: top.id, originalId: result.attemptedId };
    }
  }

  return { kind: 'suggest', attemptedId: result.attemptedId, alternatives: result.alternatives };
};
