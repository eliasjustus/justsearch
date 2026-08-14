/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

/**
 * Tempdoc 834 §5.2/§5.3 — the four honest ways to present a persisted run, as DATA rather than as a
 * table in a design doc that each surface re-derives for itself.
 *
 * <p>{@link #of} takes exactly the triple {@link AgentSessionSummary} carries — {@code state},
 * {@code resumable}, {@code interruptedAt} — because those three facts determine the answer with no
 * inference. That is the whole point of stamping {@code interruptedAt} additively instead of
 * inventing an {@code INTERRUPTED} lifecycle state: "this run is not running" and "this is where it
 * would resume from" are two facts, and collapsing them into one field would destroy the resume
 * seed to record the interruption.
 *
 * <p>The fourth case is a real product gap this reconciliation SURFACES rather than creates:
 * {@code WAITING_BUDGET} and {@code WAITING_CONTEXT} are non-terminal but sit outside
 * {@code AgentRunStore.isResumableState}, because those gates are in-memory futures that no
 * checkpoint records. Such a run can only be FORKED ({@code POST /api/chat/sessions/{id}/fork}), not
 * resumed. Extending resumability to cover them needs a checkpoint that can re-park — a separate,
 * larger change.
 */
public enum InterruptedRunPresentation {

  /** The run finished before the process died. No marker; nothing to offer. */
  FINISHED,

  /** Not running, and nothing says it was interrupted — the ordinary live/idle case. */
  NOT_INTERRUPTED,

  /**
   * Interrupted mid-step ({@code READY_FOR_LLM} / {@code AFTER_TOOL_RESULT}), and the checkpoint
   * names where to pick up. "Interrupted when the app closed. Resume."
   */
  RESUMABLE,

  /**
   * Interrupted while parked at an approval gate. Also resumable — the checkpoint records the state
   * — but the copy differs: "Interrupted while waiting for your approval. Resume."
   */
  RESUMABLE_AT_APPROVAL,

  /**
   * Interrupted while parked at a budget or context gate: non-terminal, but NOT resumable, because
   * the held decision lived only in memory. Fork, do not offer resume.
   */
  FORK_ONLY;

  /** Whether this presentation should offer a resume affordance. */
  public boolean resumable() {
    return this == RESUMABLE || this == RESUMABLE_AT_APPROVAL;
  }

  /** Whether this presentation should offer forking a new run from the transcript instead. */
  public boolean forkOnly() {
    return this == FORK_ONLY;
  }

  /** Classify one row of {@code GET /api/chat/sessions}. */
  public static InterruptedRunPresentation of(AgentSessionSummary summary) {
    return summary == null
        ? NOT_INTERRUPTED
        : of(summary.state(), summary.resumable(), summary.interruptedAt());
  }

  /**
   * Classify from the persisted triple. {@code state} is the raw persisted string (an unrecognised
   * value is treated as a mid-step state, matching {@code LifecycleState.parse}'s own default).
   */
  public static InterruptedRunPresentation of(String state, Boolean resumable, String interruptedAt) {
    if ("DONE".equals(state) || "ERROR".equals(state)) {
      return FINISHED;
    }
    if (interruptedAt == null || interruptedAt.isBlank()) {
      return NOT_INTERRUPTED;
    }
    if ("WAITING_BUDGET".equals(state) || "WAITING_CONTEXT".equals(state)) {
      return FORK_ONLY;
    }
    if (!Boolean.TRUE.equals(resumable)) {
      // Defensive: a non-terminal state the store did not mark resumable cannot be resumed either,
      // whatever its name. Offer the fork rather than a resume button that will fail.
      return FORK_ONLY;
    }
    return "WAITING_APPROVAL".equals(state) ? RESUMABLE_AT_APPROVAL : RESUMABLE;
  }
}
