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
 * Unit tests for {@link VduAbstentionGate} (tempdoc 677 Stages 0+1). Boundary values use the
 * class's own PROVISIONAL constants rather than magic numbers, so these tests stay correct across
 * the eventual calibration pass without needing to be rewritten (only the constants change).
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
      assertEquals(VduAbstentionGate.STAGE_INPUT_LEGIBILITY, verdict.stage());
      assertEquals(measures.laplacianVariance(), verdict.laplacianVariance());
      assertEquals(measures.rmsContrast(), verdict.rmsContrast());
      assertNull(verdict.meanLogprob(), "Stage 0 verdict must not carry Stage 1 fields");
      assertNull(verdict.lowConfidenceFraction());
      assertNull(verdict.tokenCount());
      assertNull(verdict.finishReason());
    }

    @Test
    @DisplayName("only Laplacian below floor: not rejected (conjunctive floor)")
    void onlyLaplacianBelowFloorPasses() {
      LegibilityMeasures measures =
          new LegibilityMeasures(
              VduAbstentionGate.LAPLACIAN_FLOOR - 1.0, VduAbstentionGate.CONTRAST_FLOOR + 0.5);

      GateVerdict verdict = VduAbstentionGate.inputVerdict(measures);

      assertFalse(verdict.rejected());
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
    @DisplayName("null meanLogprob and null lowConfidenceFraction never reject on their own")
    void nullLogprobSignalsNeverReject() {
      AggregatedPageSignals signals = new AggregatedPageSignals(null, null, 0, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertFalse(verdict.rejected(), "NO SIGNAL must not be conflated with a low-confidence signal");
    }

    @Test
    @DisplayName("null logprob signals + anomalous finishReason still rejects (finish-reason arm survives)")
    void nullLogprobSignalsStillAllowFinishReasonRejection() {
      AggregatedPageSignals signals = new AggregatedPageSignals(null, null, 0, "content_filter");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);

      assertTrue(verdict.rejected());
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
  @DisplayName("Stage 1 — outputVerdict threshold boundaries")
  class ThresholdBoundaries {

    @Test
    @DisplayName("meanLogprob exactly at floor: not rejected (strict less-than)")
    void meanLogprobExactlyAtFloorPasses() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(VduAbstentionGate.MEAN_LOGPROB_FLOOR, 0.0, 10, "stop");

      assertFalse(VduAbstentionGate.outputVerdict(signals).rejected());
    }

    @Test
    @DisplayName("meanLogprob just below floor: rejected")
    void meanLogprobJustBelowFloorRejects() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(VduAbstentionGate.MEAN_LOGPROB_FLOOR - 0.001, 0.0, 10, "stop");

      GateVerdict verdict = VduAbstentionGate.outputVerdict(signals);
      assertTrue(verdict.rejected());
      assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, verdict.stage());
    }

    @Test
    @DisplayName("lowConfidenceFraction exactly at ceiling: not rejected (strict greater-than)")
    void lowConfidenceFractionExactlyAtCeilingPasses() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.LOW_CONFIDENCE_FRACTION_CEILING, 10, "stop");

      assertFalse(VduAbstentionGate.outputVerdict(signals).rejected());
    }

    @Test
    @DisplayName("lowConfidenceFraction just above ceiling: rejected")
    void lowConfidenceFractionJustAboveCeilingRejects() {
      AggregatedPageSignals signals =
          new AggregatedPageSignals(
              0.0, VduAbstentionGate.LOW_CONFIDENCE_FRACTION_CEILING + 0.001, 10, "stop");

      assertTrue(VduAbstentionGate.outputVerdict(signals).rejected());
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
      assertEquals(VduAbstentionGate.STAGE_OUTPUT_CONFIDENCE, verdict.stage());
    }

    @Test
    @DisplayName("all three signals healthy (tempdoc probe legible band): not rejected")
    void probeLegibleBandPasses() {
      // tempdoc 677 §Probe result: legible transcription measured mean -0.058, 0% low-confidence.
      AggregatedPageSignals signals = new AggregatedPageSignals(-0.058, 0.0, 200, "stop");

      assertFalse(VduAbstentionGate.outputVerdict(signals).rejected());
    }
  }
}
