/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.run;

/**
 * Why a run is stopped and waiting, on the wire (tempdoc 834 §5.1).
 *
 * <p>Field-for-field the shape {@code AgentEvent.ParkSnapshot} already puts on the {@code
 * state_snapshot} payload, so the same JSON reaches the FE whether it arrives on a stream frame or
 * in this enumeration — a projection of the one park authority, not a second spelling of it.
 *
 * @param kind one of {@code approval} / {@code budget} / {@code context} / {@code unobserved}
 * @param sinceEpochMs when the park began; {@code 0} means "start unknown" (the zero-observer park
 *     is derived from an observer COUNT, not a transition, so it has no honest start stamp)
 * @param detail the actionable handle — the {@code callId} for an approval park, else free text
 */
public record ParkSummary(String kind, long sinceEpochMs, String detail) {}
