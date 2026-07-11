/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Stages 0 and 1 of the VDU abstention cascade (tempdoc 677 §Proposed design): pure, stateless
 * verdict logic deciding whether a page/document's vision-extraction output should be trusted.
 * Stage 2 (seed-varied agreement probe) is a later slice — not implemented here.
 *
 * <p><b>Stage 0 — input legibility</b> ({@link #inputVerdict}): a per-page check on raw pixel
 * signals, before any model call. <b>CAUTION (quoted from the tempdoc, verbatim):</b> "the gate
 * must key on 'no textual signal present for anything' (blur/contrast floor), not 'OCR confidence
 * low', or it would defeat VDU's purpose." {@link #LAPLACIAN_FLOOR} / {@link #CONTRAST_FLOOR} are
 * therefore deliberately conservative — Stage 0 must only catch pages carrying no textual signal
 * at all (blank/blown-out/uniformly-flat), not merely hard-to-read ones; a stray ambiguous case
 * must fall through to Stage 1, not get silently abstained here.
 *
 * <p><b>Stage 1 — same-call output confidence</b> ({@link #outputVerdict}): a document-level
 * check on the signals returned alongside the model's own Pass 1 output (mean logprob,
 * low-confidence-token fraction, finish reason). Thresholds are taken from the tempdoc's live
 * probe result (§Probe result, 2026-07-11): a legible transcription measured mean logprob -0.058
 * / 0% low-confidence tokens, while refusal-shaped output on pure noise measured mean logprob
 * -0.442 / 14% low-confidence tokens. {@link #MEAN_LOGPROB_FLOOR} and {@link
 * #LOW_CONFIDENCE_FRACTION_CEILING} sit deliberately far above/below the legible band so a
 * healthy extraction is nowhere near either threshold.
 *
 * <p><b>PROVISIONAL:</b> every threshold in this class is a first cut, not a calibrated value.
 * The tempdoc's own verification plan calibrates them against {@code golden/synth-scan-v1}
 * (~100% known-confabulated) and legible fixtures/{@code mixed/realdocs-v1} (known-legible) —
 * that calibration pass has not run yet. Treat these constants as a conservative starting point,
 * not a tuned decision boundary.
 */
public final class VduAbstentionGate {

  /** {@link GateVerdict#stage()} value for a Stage 0 (input legibility) rejection. */
  public static final String STAGE_INPUT_LEGIBILITY = "input_legibility";

  /** {@link GateVerdict#stage()} value for a Stage 1 (output confidence) rejection. */
  public static final String STAGE_OUTPUT_CONFIDENCE = "output_confidence";

  /**
   * PROVISIONAL Stage 0 Laplacian-variance floor. Paired conjunctively with {@link
   * #CONTRAST_FLOOR} via {@link LegibilityMeasures#belowFloor} — both must be breached to reject.
   * Chosen far below the magnitude any real text content produces: {@code
   * ImageLegibilityTest.sharpTextHasHighVarianceAndContrast}'s sparse 3-line synthetic text
   * fixture demonstrates real text edges dominate the variance (large, well-distributed
   * responses), landing orders of magnitude above this floor; a genuinely blank/flat page
   * measures exactly {@code 0.0} ({@code uniformImageHasNearZeroSignals}). 15.0 leaves a wide
   * margin between "flat page" and "any real content" for calibration to tighten later.
   */
  public static final double LAPLACIAN_FLOOR = 15.0;

  /**
   * PROVISIONAL Stage 0 RMS-contrast floor, paired conjunctively with {@link #LAPLACIAN_FLOOR}
   * (see {@link LegibilityMeasures#belowFloor}). Because the two floors are ANDed, a page cannot
   * be rejected on contrast alone — {@code ImageLegibilityTest}'s sparse-text fixture (contrast
   * just above 0.05) would still pass Stage 0 even at this floor, since its Laplacian variance is
   * nowhere near {@link #LAPLACIAN_FLOOR}. A genuinely flat page measures {@code 0.0} contrast.
   */
  public static final double CONTRAST_FLOOR = 0.06;

  /**
   * PROVISIONAL Stage 1 mean-logprob floor (reject when the aggregated mean logprob is strictly
   * below this). tempdoc 677 probe: legible transcription -0.058, refusal-on-noise -0.442 — this
   * floor sits between the two, closer to the refusal band, so a healthy extraction has
   * substantial headroom above it.
   */
  public static final double MEAN_LOGPROB_FLOOR = -1.0;

  /**
   * PROVISIONAL Stage 1 low-confidence-token-fraction ceiling (reject when the aggregated
   * fraction is strictly above this). tempdoc 677 probe: legible 0.00, refusal-on-noise 0.14 —
   * 0.35 sits well above both observed points, deliberately conservative pending calibration.
   */
  public static final double LOW_CONFIDENCE_FRACTION_CEILING = 0.35;

  private VduAbstentionGate() {}

  /**
   * Stage 0 per-page verdict: below-floor pixel signals (see {@link #LAPLACIAN_FLOOR} / {@link
   * #CONTRAST_FLOOR}) reject; anything else passes. Aggregating this per-page verdict into a
   * document-level "every page illegible" decision is the caller's responsibility ({@code
   * VduProcessor}), since only the caller knows the full page set.
   *
   * @param measures the page's legibility measurement ({@link ImageLegibility#measure})
   * @return a verdict carrying the page's {@code laplacianVariance}/{@code rmsContrast} as
   *     evidence; {@link GateVerdict#stage()} is {@link #STAGE_INPUT_LEGIBILITY} iff rejected
   */
  public static GateVerdict inputVerdict(LegibilityMeasures measures) {
    boolean rejected = measures.belowFloor(LAPLACIAN_FLOOR, CONTRAST_FLOOR);
    return new GateVerdict(
        rejected,
        rejected ? STAGE_INPUT_LEGIBILITY : null,
        null,
        null,
        null,
        null,
        measures.laplacianVariance(),
        measures.rmsContrast());
  }

  /**
   * Stage 1 document-level verdict over the pages actually sent to the model. Rejects when ANY
   * of:
   *
   * <ul>
   *   <li>{@code meanLogprob != null && meanLogprob < MEAN_LOGPROB_FLOOR}
   *   <li>{@code lowConfidenceFraction != null && lowConfidenceFraction > LOW_CONFIDENCE_FRACTION_CEILING}
   *   <li>{@code finishReason != null && !"stop".equals(finishReason) && !"length".equals(finishReason)}
   * </ul>
   *
   * <p><b>Null semantics (CRITICAL):</b> a {@code null} {@code meanLogprob} or {@code
   * lowConfidenceFraction} means the server returned NO SIGNAL (logprobs not requested or
   * unsupported) — it must NEVER count toward rejection. Only the finish-reason arm can still
   * reject when the logprob signals are absent.
   *
   * <p><b>The {@code "length"} exception:</b> a truncated-but-otherwise-clean completion
   * ({@code finish_reason == "length"}) is excluded from the finish-reason trigger — hitting the
   * token budget is not, by itself, evidence of confabulation (tempdoc 677 task: "note truncation
   * but do NOT reject on it alone"). The raw {@code "length"} value still flows through into the
   * returned verdict's {@link GateVerdict#finishReason()} for the evidence trail, it just does
   * not independently flip {@code rejected}.
   *
   * @param signals the token-weighted aggregate across every page sent to the model
   * @return a verdict carrying {@code signals}' fields as evidence; {@link GateVerdict#stage()}
   *     is {@link #STAGE_OUTPUT_CONFIDENCE} iff rejected
   */
  public static GateVerdict outputVerdict(AggregatedPageSignals signals) {
    boolean logprobBelowFloor =
        signals.meanLogprob() != null && signals.meanLogprob() < MEAN_LOGPROB_FLOOR;
    boolean lowConfidenceAboveCeiling =
        signals.lowConfidenceFraction() != null
            && signals.lowConfidenceFraction() > LOW_CONFIDENCE_FRACTION_CEILING;
    boolean finishReasonSuspect =
        signals.finishReason() != null
            && !"stop".equals(signals.finishReason())
            && !"length".equals(signals.finishReason());
    boolean rejected = logprobBelowFloor || lowConfidenceAboveCeiling || finishReasonSuspect;
    return new GateVerdict(
        rejected,
        rejected ? STAGE_OUTPUT_CONFIDENCE : null,
        signals.meanLogprob(),
        signals.lowConfidenceFraction(),
        signals.tokenCount(),
        signals.finishReason(),
        null,
        null);
  }
}
