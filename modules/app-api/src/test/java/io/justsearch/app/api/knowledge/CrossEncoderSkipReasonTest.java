/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Register F-052 (production side): the cross-encoder skip vocabulary is closed.
 *
 * <p>Before this enum the Head wrote raw strings into the {@code cross-encoder} trace stage and
 * passed the Worker's {@code RerankResponse.skip_reason} through verbatim — so an unrecognised
 * Worker string reached the FE as an unworded code, and a deadline drop (the empty-reason case)
 * was the only one normalised at all.
 */
@DisplayName("CrossEncoderSkipReason: closed vocabulary for the cross-encoder trace stage")
class CrossEncoderSkipReasonTest {

  @Test
  @DisplayName("an empty Worker skip reason is the deadline drop (the Worker's only skip path)")
  void blankWorkerReasonIsDeadline() {
    assertEquals(
        CrossEncoderSkipReason.DEADLINE_EXCEEDED,
        CrossEncoderSkipReason.fromWorkerSkipReason(""));
    assertEquals(
        CrossEncoderSkipReason.DEADLINE_EXCEEDED,
        CrossEncoderSkipReason.fromWorkerSkipReason(null));
  }

  @Test
  @DisplayName("the Worker's declared skip reasons map to their members")
  void knownWorkerReasonsMap() {
    assertEquals(
        CrossEncoderSkipReason.DEADLINE_EXCEEDED,
        CrossEncoderSkipReason.fromWorkerSkipReason("DEADLINE_EXCEEDED"));
    assertEquals(
        CrossEncoderSkipReason.MODEL_NOT_LOADED,
        CrossEncoderSkipReason.fromWorkerSkipReason("MODEL_NOT_LOADED"));
  }

  @Test
  @DisplayName("an unrecognised Worker string resolves to UNKNOWN, never a raw pass-through")
  void unrecognisedWorkerReasonIsUnknown() {
    assertEquals(
        CrossEncoderSkipReason.UNKNOWN,
        CrossEncoderSkipReason.fromWorkerSkipReason("SOME_FUTURE_WORKER_CODE"));
  }

  @Test
  @DisplayName("the drop class is exactly the reasons the user tier words")
  void dropClassIsDeclared() {
    for (CrossEncoderSkipReason r : CrossEncoderSkipReason.values()) {
      boolean expectedDrop =
          switch (r) {
            case DEADLINE_EXCEEDED, RPC_FAILED, MODEL_NOT_LOADED, UNKNOWN -> true;
            case NAVIGATIONAL_QUERY,
                DISABLED,
                BELOW_MIN_THRESHOLD,
                DOCS_TOO_LONG,
                PIPELINE_NOT_ELIGIBLE,
                MODEL_NOT_CONFIGURED,
                FUSION_CONFIDENT -> false;
          };
      assertEquals(expectedDrop, r.isDrop(), r.name());
    }
  }

  @Test
  @DisplayName("wire() is the enum name — the string the trace stage carries")
  void wireIsName() {
    for (CrossEncoderSkipReason r : CrossEncoderSkipReason.values()) {
      assertEquals(r.name(), r.wire());
      assertTrue(r.wire().matches("[A-Z][A-Z0-9_]*"), r.wire());
    }
  }
}
