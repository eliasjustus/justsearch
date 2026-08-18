/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.Objects;

/**
 * Why a STEPPED run is stopped and waiting (tempdoc 834 §1.5).
 *
 * <p>Only a {@link SteppedRunChannel} can carry one — a one-shot pipeline has no control point to
 * park at, which §3.4 makes structural rather than documentary.
 *
 * @param kind what the run is waiting for
 * @param sinceEpochMs when the park began; {@code 0} means "start unknown" (the zero-observer park
 *     is derived from an observer COUNT, not a transition, so it has no honest start stamp)
 * @param detail the actionable handle — the {@code callId} for an approval park, else free text
 */
public record ParkState(Kind kind, long sinceEpochMs, String detail) {

  public ParkState {
    Objects.requireNonNull(kind, "kind");
    detail = detail == null ? "" : detail;
  }

  /** The four things a stepped run stops for. */
  public enum Kind {
    APPROVAL,
    BUDGET,
    CONTEXT,
    UNOBSERVED;

    /** The wire spelling — lowercase, matching {@code AgentEvent.ParkSnapshot.kind()}. */
    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}
