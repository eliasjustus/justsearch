/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tempdoc 834 §5.2 — the four honest presentations, pinned as a table. */
final class InterruptedRunPresentationTest {

  @ParameterizedTest(name = "{0} + resumable={1} + interrupted={2} -> {3}")
  @CsvSource({
    // terminal — finished before the process died; no marker, nothing to offer.
    "DONE,           false, false, FINISHED",
    "ERROR,          false, false, FINISHED",
    "DONE,           false, true,  FINISHED",
    // non-terminal but never interrupted — the ordinary live/idle row.
    "READY_FOR_LLM,  true,  false, NOT_INTERRUPTED",
    "WAITING_BUDGET, false, false, NOT_INTERRUPTED",
    // "Interrupted when the app closed. Resume."
    "READY_FOR_LLM,    true, true, RESUMABLE",
    "AFTER_TOOL_RESULT,true, true, RESUMABLE",
    // "Interrupted while waiting for your approval. Resume."
    "WAITING_APPROVAL, true, true, RESUMABLE_AT_APPROVAL",
    // Non-terminal, NOT resumable: the held decision lived only in memory, so there is no
    // checkpoint that can re-park. Fork from the transcript instead.
    "WAITING_BUDGET,  false, true, FORK_ONLY",
    "WAITING_CONTEXT, false, true, FORK_ONLY",
  })
  void theFourCases(String state, boolean resumable, boolean interrupted, String expected) {
    assertEquals(
        InterruptedRunPresentation.valueOf(expected),
        InterruptedRunPresentation.of(state, resumable, interrupted ? "2026-08-14T10:00:00Z" : null));
  }

  @Test
  @DisplayName("WAITING_BUDGET/WAITING_CONTEXT never offer resume, however resumable is flagged")
  void budgetAndContextGatesAreNeverResumable() {
    // The design's fourth row is a real product gap this classification SURFACES: those gates are
    // in-memory futures no checkpoint records. Even a stray resumable=true must not produce a
    // resume button that cannot work.
    for (String state : new String[] {"WAITING_BUDGET", "WAITING_CONTEXT"}) {
      var p = InterruptedRunPresentation.of(state, true, "2026-08-14T10:00:00Z");
      assertEquals(InterruptedRunPresentation.FORK_ONLY, p, state);
      assertFalse(p.resumable(), state);
      assertTrue(p.forkOnly(), state);
    }
  }

  @Test
  @DisplayName("classification reads the summary row exactly as the wire delivers it")
  void classifiesASummaryRow() {
    var row =
        new AgentSessionSummary(
            "s-1",
            "2026-08-14T09:00:00Z",
            "2026-08-14T09:05:00Z",
            "WAITING_APPROVAL",
            true,
            2,
            1,
            120,
            "planner",
            null,
            "2026-08-14T10:00:00Z",
            "find files");
    assertEquals(InterruptedRunPresentation.RESUMABLE_AT_APPROVAL, InterruptedRunPresentation.of(row));
    assertTrue(InterruptedRunPresentation.of(row).resumable());

    var live =
        new AgentSessionSummary(
            "s-2", null, null, "READY_FOR_LLM", true, 0, 0, 0, "planner", null, null, "");
    assertEquals(InterruptedRunPresentation.NOT_INTERRUPTED, InterruptedRunPresentation.of(live));
  }
}
