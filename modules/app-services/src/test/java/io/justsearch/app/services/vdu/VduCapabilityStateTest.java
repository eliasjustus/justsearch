/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Guard tests for {@link VduCapabilityState} (tempdoc 671, Post-implementation research idea 2).
 * No guard test existed for this class before — these characterize its current behavior,
 * including its documented single-slot-overwrite limitation. This is deliberately NOT a fix for
 * that limitation: no design was committed for changing it, so none is built here (tempdoc 671's
 * own framing: "closer to 'add assertions to an existing test' than 'extract a new pure seam'").
 */
class VduCapabilityStateTest {

  @Test
  void eachKnownReasonRoundTripsThroughBlockAndSnapshot() {
    for (String reason :
        new String[] {
          VduCapabilityState.REASON_AI_OFFLINE,
          VduCapabilityState.REASON_INSUFFICIENT_VRAM,
          VduCapabilityState.REASON_MISSING_MMPROJ,
          VduCapabilityState.REASON_CIRCUIT_OPEN
        }) {
      VduCapabilityState state = new VduCapabilityState();
      state.block(reason);
      assertEquals(reason, state.snapshot().blockedReason());
    }
  }

  @Test
  void theFourKnownReasonsArePairwiseDistinct() {
    assertNotEquals(VduCapabilityState.REASON_AI_OFFLINE, VduCapabilityState.REASON_INSUFFICIENT_VRAM);
    assertNotEquals(VduCapabilityState.REASON_AI_OFFLINE, VduCapabilityState.REASON_MISSING_MMPROJ);
    assertNotEquals(VduCapabilityState.REASON_AI_OFFLINE, VduCapabilityState.REASON_CIRCUIT_OPEN);
    assertNotEquals(VduCapabilityState.REASON_INSUFFICIENT_VRAM, VduCapabilityState.REASON_MISSING_MMPROJ);
    assertNotEquals(VduCapabilityState.REASON_INSUFFICIENT_VRAM, VduCapabilityState.REASON_CIRCUIT_OPEN);
    assertNotEquals(VduCapabilityState.REASON_MISSING_MMPROJ, VduCapabilityState.REASON_CIRCUIT_OPEN);
  }

  @Test
  void blockWithAnUnrecognizedReasonCodeIsANoOp() {
    // Characterizes current behavior: an unknown reason code is silently dropped, not recorded
    // and not rejected loudly. Guards against this going MORE silent (e.g. swallowing a null
    // snapshot read) rather than changing the drop-on-unknown behavior itself.
    VduCapabilityState state = new VduCapabilityState();
    state.block("vdu.some_future_reason_not_yet_known");
    assertNull(state.snapshot().blockedReason());
  }

  @Test
  void blockWithNullReasonCodeIsANoOp() {
    VduCapabilityState state = new VduCapabilityState();
    state.block(null);
    assertNull(state.snapshot().blockedReason());
  }

  @Test
  void aSecondBlockCallWhileAlreadyBlockedOverwritesTheFirst() {
    // Characterizes the documented single-slot limitation (tempdoc 671, Post-implementation
    // research idea 2): VduCapabilityState has one mutable slot, so two simultaneous blockers
    // are not both representable — the second block() call silently replaces the first rather
    // than accumulating. This test pins that behavior so a future change to it is deliberate,
    // not accidental.
    VduCapabilityState state = new VduCapabilityState();
    state.block(VduCapabilityState.REASON_AI_OFFLINE);
    state.block(VduCapabilityState.REASON_INSUFFICIENT_VRAM);
    assertEquals(VduCapabilityState.REASON_INSUFFICIENT_VRAM, state.snapshot().blockedReason());
  }

  @Test
  void clearWithMatchingReasonClearsTheSlot() {
    VduCapabilityState state = new VduCapabilityState();
    state.block(VduCapabilityState.REASON_CIRCUIT_OPEN);
    state.clear(VduCapabilityState.REASON_CIRCUIT_OPEN);
    assertNull(state.snapshot().blockedReason());
  }

  @Test
  void clearWithNonMatchingReasonDoesNotClearTheSlot() {
    VduCapabilityState state = new VduCapabilityState();
    state.block(VduCapabilityState.REASON_CIRCUIT_OPEN);
    state.clear(VduCapabilityState.REASON_AI_OFFLINE);
    assertEquals(VduCapabilityState.REASON_CIRCUIT_OPEN, state.snapshot().blockedReason());
  }

  @Test
  void clearAllAlwaysClearsRegardlessOfCurrentReason() {
    VduCapabilityState state = new VduCapabilityState();
    state.block(VduCapabilityState.REASON_MISSING_MMPROJ);
    state.clearAll();
    assertNull(state.snapshot().blockedReason());
  }
}
