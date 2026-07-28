/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/**
 * Per-document bound on the extraction dropout fallback chain (tempdoc 790 item 2).
 *
 * <p>The chain is structured → OCR → VDU/VLM (ADR-0018). Tier 0 (structured Tika) always runs and
 * is not charged to this budget; every <em>fallback</em> tier is. Two independent bounds:
 *
 * <ul>
 *   <li>{@link #maxFallbackTiers()} — how many fallback tiers a single document may consume. The
 *       VDU tier is GPU-time, so "unbounded retries per document" is a real cost, not a
 *       theoretical one.
 *   <li>{@link #perDocBudgetMs()} — wall-clock the in-extractor fallback tiers may start within.
 *       Nested inside {@code TimeboxedContentExtractor}'s whole-extraction timeout (60 s default),
 *       which bounds the attempt once started; this bound stops a document that already burned its
 *       budget in structured extraction from also starting an OCR pass.
 * </ul>
 *
 * <p>Deliberately not env-configurable: the defaults sit inside an existing enforced timeout, and
 * a new configuration surface would need its own registry entry, docs, and drift risk for no
 * measured benefit (tempdoc 790 §Deviations).
 */
public record ExtractionFallbackBudget(int maxFallbackTiers, long perDocBudgetMs) {

  /** OCR then VDU — the two fallback tiers ADR-0018's chain defines. */
  public static final int DEFAULT_MAX_FALLBACK_TIERS = 2;

  /** Half of {@code TimeboxedContentExtractor.DEFAULT_TIMEOUT}, so a fallback can still finish. */
  public static final long DEFAULT_PER_DOC_BUDGET_MS = 30_000L;

  private static final ExtractionFallbackBudget DEFAULTS =
      new ExtractionFallbackBudget(DEFAULT_MAX_FALLBACK_TIERS, DEFAULT_PER_DOC_BUDGET_MS);

  public ExtractionFallbackBudget {
    if (maxFallbackTiers < 0) {
      throw new IllegalArgumentException("maxFallbackTiers must be >= 0");
    }
    if (perDocBudgetMs < 0) {
      throw new IllegalArgumentException("perDocBudgetMs must be >= 0");
    }
  }

  public static ExtractionFallbackBudget defaults() {
    return DEFAULTS;
  }

  /** Tier index 1 = OCR, 2 = VDU. */
  public boolean permitsTier(int tierIndex) {
    return tierIndex >= 1 && tierIndex <= maxFallbackTiers;
  }

  /** True when {@code elapsedMs} has already exhausted the wall-clock half of the budget. */
  public boolean exhausted(long elapsedMs) {
    return elapsedMs >= perDocBudgetMs;
  }

  /** True when tier {@code tierIndex} may start after {@code elapsedMs} of work on this document. */
  public boolean permitsTierNow(int tierIndex, long elapsedMs) {
    return permitsTier(tierIndex) && !exhausted(elapsedMs);
  }
}
