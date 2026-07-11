/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VduAbstentionGate} (tempdoc 677 Stages 0/1/2). Boundary values use the
 * class's own constants rather than magic numbers, so these tests stay correct across a future
 * re-calibration pass without needing to be rewritten (only the constants change).
 */
@DisplayName("VduAbstentionGate")
final class VduAbstentionGateTest {

  @Nested
  @DisplayName("Stage 0 — inputVerdict")
  class InputVerdict {

    @Test
    @DisplayName("both signals below floor: rejected, stage=input_legibility")
    void bothBelowFloorRejects() {
      LegibilityMeasures measures =
          new LegibilityMeasures(
              VduAbstentionGate.LAPLACIAN_FLOOR - 1.0, VduAbstentionGate.CONTRAST_FLOOR - 0.01);

      GateVerdict verdict = VduAbstentionGate.inputVerdict(measures);

      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_INPUT_LEGIBILITY, verdict.stage());
      assertEquals(measures.laplacianVariance(), verdict.laplacianVariance());
      assertEquals(measures.rmsContrast(), verdict.rmsContrast());
      assertNull(verdict.meanLogprob(), "Stage 0 verdict must not carry Stage 1 fields");
      assertNull(verdict.lowConfidenceFraction());
      assertNull(verdict.tokenCount());
      assertNull(verdict.finishReason());
      assertNull(verdict.agreement(), "Stage 0 verdict must not carry Stage 2 fields");
      assertNull(verdict.probedPage());
    }

    @Test
    @DisplayName("only Laplacian below floor: not rejected (conjunctive floor)")
    void onlyLaplacianBelowFloorPasses() {
      LegibilityMeasures measures =
          new LegibilityMeasures(
              VduAbstentionGate.LAPLACIAN_FLOOR - 1.0, VduAbstentionGate.CONTRAST_FLOOR + 0.5);

      GateVerdict verdict = VduAbstentionGate.inputVerdict(measures);

      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.PASS, verdict.band());
      assertNull(verdict.stage(), "a passed verdict names no rejecting stage");
    }

    @Test
    @DisplayName("only contrast below floor: not rejected (conjunctive floor)")
    void onlyContrastBelowFloorPasses() {
      LegibilityMeasures measures =
          new LegibilityMeasures(
              VduAbstentionGate.LAPLACIAN_FLOOR + 1000.0, VduAbstentionGate.CONTRAST_FLOOR - 0.01);

      GateVerdict verdict = VduAbstentionGate.inputVerdict(measures);

      assertFalse(verdict.rejected());
    }

    @Test
    @DisplayName("neither below floor: not rejected")
    void neitherBelowFloorPasses() {
      LegibilityMeasures measures =
          new LegibilityMeasures(
              VduAbstentionGate.LAPLACIAN_FLOOR + 1000.0, VduAbstentionGate.CONTRAST_FLOOR + 0.5);

      assertFalse(VduAbstentionGate.inputVerdict(measures).rejected());
    }
  }

  @Nested
  @DisplayName("Stage 1 — outputVerdict null semantics (CRITICAL)")
  class NullSemantics {

    @Test
    @DisplayName("null meanLogprob and null lowConfidenceFraction: PASS band, not ambiguous")
    void nullLogprobSignalsPassNotAmbiguous() {
      AggregatedPageSignals signals = new AggregatedPageSignals(null, null, 0, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertFalse(verdict.rejected(), "NO SIGNAL must not be conflated with a low-confidence signal");
      assertEquals(
          GateVerdict.Band.PASS,
          verdict.band(),
          "NO SIGNAL must not be conflated with an ambiguous signal either");
    }

    @Test
    @DisplayName("null logprob signals + anomalous finishReason still rejects (finish-reason arm survives)")
    void nullLogprobSignalsStillAllowFinishReasonRejection() {
      AggregatedPageSignals signals = new AggregatedPageSignals(null, null, 0, "content_filter");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, verdict.stage());
    }

    @Test
    @DisplayName("null finishReason never rejects on its own")
    void nullFinishReasonNeverRejects() {
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 42, null);

      assertFalse(VduAbstentionGate.outputVerdict(signals).rejected());
    }
  }

  @Nested
  @DisplayName("Stage 1 — outputVerdict REJECT band boundaries")
  class RejectBandBoundaries {

    @Test
    @DisplayName("meanLogprob exactly at REJECT floor: not rejected (strict less-than); still AMBIGUOUS")
    void meanLogprobExactlyAtRejectFloorIsAmbiguousNotRejected() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(VduAbstentionGate.REJECT_MEAN_LOGPROB_FLOOR, 0.0, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      // -0.35 is not < -0.35 (strict), so REJECT doesn't fire — but -0.35 IS < the AMBIGUOUS
      // floor (-0.09), so this value is AMBIGUOUS, not a clean PASS.
      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.AMBIGUOUS, verdict.band());
    }

    @Test
    @DisplayName("meanLogprob just below REJECT floor: rejected")
    void meanLogprobJustBelowRejectFloorRejects() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              VduAbstentionGate.REJECT_MEAN_LOGPROB_FLOOR - 0.001, 0.0, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, verdict.stage());
    }

    @Test
    @DisplayName("lowConfidenceFraction exactly at REJECT ceiling: not rejected (strict greater-than)")
    void lowConfidenceFractionExactlyAtRejectCeilingPasses() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.REJECT_LOW_CONFIDENCE_FRACTION_CEILING, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      // At exactly the REJECT ceiling, the AMBIGUOUS ceiling is also breached (REJECT ceiling >
      // AMBIGUOUS ceiling), so this lands in AMBIGUOUS, not PASS.
      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.AMBIGUOUS, verdict.band());
    }

    @Test
    @DisplayName("lowConfidenceFraction just above REJECT ceiling: rejected")
    void lowConfidenceFractionJustAboveRejectCeilingRejects() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.REJECT_LOW_CONFIDENCE_FRACTION_CEILING + 0.001, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
    }

    @Test
    @DisplayName("finishReason=stop never rejects on its own")
    void finishReasonStopNeverRejects() {
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 100, "stop");

      assertFalse(VduAbstentionGate.outputVerdict(signals).rejected());
    }

    @Test
    @DisplayName("finishReason=length never rejects on its own, even with otherwise-healthy signals")
    void finishReasonLengthNeverRejectsAlone() {
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 100, "length");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertFalse(verdict.rejected(), "truncation alone must not abstain");
      assertEquals(GateVerdict.Band.PASS, verdict.band());
      // The raw finishReason still flows through for the evidence trail even though it didn't
      // trigger rejection — a human/log reader can still see truncation occurred.
      assertEquals("length", verdict.finishReason());
    }

    @Test
    @DisplayName("anomalous finishReason (not stop, not length) rejects on its own")
    void anomalousFinishReasonRejectsAlone() {
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 100, "content_filter");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, verdict.stage());
    }

    @Test
    @DisplayName("all three signals healthy (tempdoc calibration legible band): PASS")
    void calibratedLegibleBandPasses() {
      // tempdoc 677 §Calibration: legible transcription measured mean ∈ [-0.058, -0.006], 0%
      // low-confidence tokens (all 16 legible samples).
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 200, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.PASS, verdict.band());
    }
  }

  @Nested
  @DisplayName("Stage 1 — outputVerdict AMBIGUOUS band boundaries")
  class AmbiguousBandBoundaries {

    @Test
    @DisplayName("meanLogprob exactly at AMBIGUOUS floor: PASS (strict less-than)")
    void meanLogprobExactlyAtAmbiguousFloorPasses() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(VduAbstentionGate.AMBIGUOUS_MEAN_LOGPROB_FLOOR, 0.0, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertEquals(GateVerdict.Band.PASS, verdict.band());
    }

    @Test
    @DisplayName("meanLogprob just below AMBIGUOUS floor (but above REJECT floor): AMBIGUOUS")
    void meanLogprobJustBelowAmbiguousFloorIsAmbiguous() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              VduAbstentionGate.AMBIGUOUS_MEAN_LOGPROB_FLOOR - 0.001, 0.0, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.AMBIGUOUS, verdict.band());
      assertNull(verdict.stage(), "AMBIGUOUS is interim — nothing has rejected it yet");
    }

    @Test
    @DisplayName("lowConfidenceFraction exactly at AMBIGUOUS ceiling: PASS (strict greater-than)")
    void lowConfidenceFractionExactlyAtAmbiguousCeilingPasses() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertEquals(GateVerdict.Band.PASS, verdict.band());
    }

    @Test
    @DisplayName("lowConfidenceFraction just above AMBIGUOUS ceiling (but below REJECT ceiling): AMBIGUOUS")
    void lowConfidenceFractionJustAboveAmbiguousCeilingIsAmbiguous() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING + 0.001, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.AMBIGUOUS, verdict.band());
    }

    @Test
    @DisplayName("both REJECT and AMBIGUOUS floors breached: REJECT wins (not AMBIGUOUS)")
    void rejectTakesPriorityOverAmbiguous() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              VduAbstentionGate.REJECT_MEAN_LOGPROB_FLOOR - 0.1,
              VduAbstentionGate.REJECT_LOW_CONFIDENCE_FRACTION_CEILING + 0.1,
              10,
              "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
    }

    @Test
    @DisplayName("tempdoc calibration long-confab-mode band (evades REJECT): AMBIGUOUS")
    void calibratedLongConfabModeIsAmbiguous() {
      // tempdoc 677 §Calibration: long-confab-mode (8/30) mean ∈ [-0.25, -0.049], frac ∈
      // [0.013, 0.048] — deliberately evades the REJECT arm (that's the point of this band) but
      // must not evade the gate entirely.
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.049, 0.013, 150, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertFalse(verdict.rejected(), "long-confab-mode must not hit REJECT directly");
      assertEquals(GateVerdict.Band.AMBIGUOUS, verdict.band());
    }
  }

  @Nested
  @DisplayName("Stage 2 — agreementVerdict")
  class AgreementVerdictTests {

    @Test
    @DisplayName("agreement exactly at floor: not rejected (strict less-than)")
    void agreementExactlyAtFloorPasses() {
      GateVerdict verdict = VduAbstentionGate.agreementVerdict(VduAbstentionGate.AGREEMENT_FLOOR);

      assertFalse(verdict.rejected());
      assertEquals(GateVerdict.Band.PASS, verdict.band());
      assertEquals(VduAbstentionGate.AGREEMENT_FLOOR, verdict.agreement());
      assertNull(verdict.probedPage(), "agreementVerdict alone never knows the page number");
    }

    @Test
    @DisplayName("agreement just below floor: rejected, stage=agreement")
    void agreementJustBelowFloorRejects() {
      GateVerdict verdict =
          VduAbstentionGate.agreementVerdict(VduAbstentionGate.AGREEMENT_FLOOR - 0.001);

      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_AGREEMENT, verdict.stage());
    }

    @Test
    @DisplayName("tempdoc calibration legible re-sample agreement (1.000): passes with wide margin")
    void calibratedLegibleAgreementPasses() {
      assertFalse(VduAbstentionGate.agreementVerdict(1.0).rejected());
    }

    @Test
    @DisplayName("tempdoc calibration confabulated re-sample agreement (max observed 0.379): rejects")
    void calibratedConfabAgreementRejects() {
      GateVerdict verdict = VduAbstentionGate.agreementVerdict(0.379);
      assertTrue(verdict.rejected());
    }

    @Test
    @DisplayName("withProbedPage attaches the page number, preserving every other field")
    void withProbedPageAttachesPageNumber() {
      GateVerdict verdict = VduAbstentionGate.agreementVerdict(0.1).withProbedPage(3);

      assertEquals(3, verdict.probedPage());
      assertTrue(verdict.rejected());
      assertEquals(GateVerdict.Band.REJECT, verdict.band());
      assertEquals(VduAbstentionGate.STAGE_AGREEMENT, verdict.stage());
      assertEquals(0.1, verdict.agreement());
    }
  }

  @Nested
  @DisplayName("jaccardAgreement utility")
  class JaccardAgreementTests {

    @Test
    @DisplayName("identical text: agreement 1.0")
    void identicalTextAgreesFully() {
      String text = "The quick brown fox jumps over the lazy dog repeatedly";
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement(text, text));
    }

    @Test
    @DisplayName("completely disjoint token sets: agreement 0.0")
    void disjointTokenSetsNeverAgree() {
      String a = "alpha bravo charlie delta echo foxtrot";
      String b = "zulu yankee xray whiskey victor uniform";
      assertEquals(0.0, VduAbstentionGate.jaccardAgreement(a, b));
    }

    @Test
    @DisplayName("both texts blank/tokenless: agreement 1.0 (nothing to disagree about)")
    void bothBlankAgreesFully() {
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement("", ""));
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement("   ", null));
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement(null, null));
    }

    @Test
    @DisplayName("one blank, one with content: agreement 0.0, not 1.0")
    void oneBlankOneContentDisagrees() {
      assertEquals(0.0, VduAbstentionGate.jaccardAgreement("", "some real words here"));
    }

    @Test
    @DisplayName("partial overlap computes the exact Jaccard ratio")
    void partialOverlapComputesRatio() {
      // tokens(a) = {red, green, blue}; tokens(b) = {red, green, yellow}
      // intersection = {red, green} (2); union = {red, green, blue, yellow} (4) -> 0.5
      String a = "red green blue";
      String b = "red green yellow";
      assertEquals(0.5, VduAbstentionGate.jaccardAgreement(a, b));
    }

    @Test
    @DisplayName("case-insensitive: differently-cased identical words still agree fully")
    void caseInsensitiveAgreement() {
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement("Hello World", "hello world"));
    }

    @Test
    @DisplayName("tokens shorter than 3 chars are excluded from both sides")
    void shortTokensExcluded() {
      // "a" and "an" (both <3 chars) are dropped; "cat" (3 chars) is kept on both sides.
      assertEquals(1.0, VduAbstentionGate.jaccardAgreement("a cat", "an cat"));
    }
  }
}
