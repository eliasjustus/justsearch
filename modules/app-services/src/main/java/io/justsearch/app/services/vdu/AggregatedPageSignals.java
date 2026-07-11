/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Document-level Stage 1 input to {@link VduAbstentionGate#outputVerdict} — the per-page {@code
 * VisionCompletionResult}s from every page actually sent to the model (tempdoc 677 §Proposed
 * design), reduced to a single set of scalars. Aggregation (token-weighting, finish-reason
 * priority) is {@code VduProcessor}'s responsibility, since it is the caller with visibility into
 * which pages were sent and their individual results; this record only carries the outcome.
 *
 * @param meanLogprob token-weighted mean of per-page mean logprobs (weight = that page's token
 *     count), or {@code null} when no page reported logprobs (NO SIGNAL, not a low-confidence
 *     signal — see {@link VduAbstentionGate})
 * @param lowConfidenceFraction token-weighted mean of per-page low-confidence-token fractions
 *     (same weighting and null semantics as {@code meanLogprob})
 * @param tokenCount sum of per-page token counts across all pages sent to the model
 * @param finishReason the aggregated finish reason: an anomalous (non-{@code "stop"},
 *     non-{@code "length"}) value if any page reported one, else {@code "length"} if any page was
 *     truncated, else {@code "stop"} if every page that reported a reason completed cleanly, else
 *     {@code null} if no page reported a finish reason at all
 */
public record AggregatedPageSignals(
    Double meanLogprob, Double lowConfidenceFraction, int tokenCount, String finishReason) {}
