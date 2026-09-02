/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the ladder arithmetic of {@link ContextWindowPolicy} (tempdoc 883 decision 1).
 *
 * <p>The window used to be a stored number, so there was nothing to test. It is now a derived one,
 * and the derivation is the behaviour: which rung is tried, which rungs remain, and the fact that
 * an explicit operator value has no rungs below it to fall to.
 */
@DisplayName("ContextWindowPolicy")
final class ContextWindowPolicyTest {

  @Test
  @DisplayName("GPU ladder starts at 32k and falls through every lower rung")
  void gpuLadder() {
    ContextWindowPolicy.Plan plan = ContextWindowPolicy.auto(true, null);

    assertEquals(List.of(32768, 16384, 8192, 4096), plan.ladder());
    assertEquals(32768, plan.topRung());
    assertEquals(ContextWindowPolicy.REASON_FIT, plan.reason());
  }

  @Test
  @DisplayName("CPU ladder skips every rung above the 8k top: 32k prefill is minutes per ask")
  void cpuLadderSkipsRungsAboveTop() {
    ContextWindowPolicy.Plan plan = ContextWindowPolicy.auto(false, null);

    assertEquals(List.of(8192, 4096), plan.ladder());
    assertEquals(8192, plan.topRung());
    assertFalse(plan.ladder().contains(32768), "CPU must never try the GPU top rung");
    assertFalse(plan.ladder().contains(16384), "CPU must never try a rung above its top");
  }

  @Test
  @DisplayName("step-down walks the GPU ladder rung by rung and then reports exhaustion")
  void stepDownSequence() {
    ContextWindowPolicy.Plan plan = ContextWindowPolicy.auto(true, null);

    assertEquals(OptionalInt.of(16384), plan.nextRungBelow(32768));
    assertEquals(OptionalInt.of(8192), plan.nextRungBelow(16384));
    assertEquals(OptionalInt.of(4096), plan.nextRungBelow(8192));
    assertTrue(plan.nextRungBelow(4096).isEmpty(), "the bottom rung has nothing below it");
  }

  @Test
  @DisplayName("an explicit override is a one-rung ladder: honoured or failed loud, never reduced")
  void overrideDoesNotStepDown() {
    ContextWindowPolicy.Plan plan = ContextWindowPolicy.override(65536, 1234L);

    assertEquals(List.of(65536), plan.ladder());
    assertEquals(ContextWindowPolicy.REASON_OVERRIDE, plan.reason());
    assertTrue(
        plan.nextRungBelow(65536).isEmpty(),
        "silently serving less context than an operator asked for is the precedence lie this lane"
            + " deletes; the launch must fail instead");
    assertEquals(Long.valueOf(1234L), plan.freeVramBytes());
  }

  @Test
  @DisplayName("an override below the floor is clamped to 512, not accepted as-is")
  void overrideIsFloored() {
    assertEquals(List.of(512), ContextWindowPolicy.override(1, null).ladder());
    assertEquals(List.of(512), ContextWindowPolicy.override(512, null).ladder());
    assertEquals(List.of(4096), ContextWindowPolicy.override(4096, null).ladder());
  }

  @Test
  @DisplayName("autoTopRung is the single default for the quantity, by backend")
  void autoTopRungByBackend() {
    assertEquals(32768, ContextWindowPolicy.autoTopRung(true));
    assertEquals(8192, ContextWindowPolicy.autoTopRung(false));
    assertEquals(
        ContextWindowPolicy.autoTopRung(true),
        ContextWindowPolicy.auto(true, null).topRung(),
        "the rung the Head contributes at ordinal 150 and the rung the launch tries must be the"
            + " same number, or effective-config explains a window nobody asked for");
    assertEquals(
        ContextWindowPolicy.autoTopRung(false), ContextWindowPolicy.auto(false, null).topRung());
  }

  @Test
  @DisplayName("the adopted-server floor IS the ladder's bottom rung, not a second number")
  void adoptedFloorIsTheBottomRung() {
    List<Integer> gpuLadder = ContextWindowPolicy.auto(true, null).ladder();

    assertEquals(
        ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS,
        gpuLadder.get(gpuLadder.size() - 1),
        "the smallest window we will run our own engine at is the honest floor for judging one we"
            + " adopt; two independent numbers would drift");
    assertEquals(
        ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS,
        ContextWindowPolicy.auto(false, null).ladder().get(1),
        "both ladders bottom out at the same rung");
  }

  @Test
  @DisplayName("free VRAM rides the plan for the record and never selects a rung")
  void freeVramIsRecordedNotUsed() {
    ContextWindowPolicy.Plan starved = ContextWindowPolicy.auto(true, 1L);
    ContextWindowPolicy.Plan roomy = ContextWindowPolicy.auto(true, 24L * 1024 * 1024 * 1024);

    assertEquals(starved.ladder(), roomy.ladder());
    assertEquals(Long.valueOf(1L), starved.freeVramBytes());
  }

  @Test
  @DisplayName("the stepped-from reason names the rung that was planned, not the one being tried")
  void steppedFromNamesThePlannedTopRung() {
    assertEquals("stepped-from:32768", ContextWindowPolicy.steppedFrom(32768));
    assertTrue(
        ContextWindowPolicy.steppedFrom(8192)
            .startsWith(ContextWindowPolicy.REASON_STEPPED_FROM_PREFIX));
  }
}
