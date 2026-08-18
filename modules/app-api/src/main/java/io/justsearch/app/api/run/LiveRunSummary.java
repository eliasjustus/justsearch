/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.run;

/**
 * One run that is executing right now (tempdoc 834 §5.1).
 *
 * <p>This is the FE's <strong>discovery authority</strong>. It replaces the {@code localStorage}
 * pointer the shell used to reattach from (§15.3): a backend enumeration cannot go stale the way a
 * pointer can, and it sees runs this browser never started.
 *
 * <p><strong>{@code state} is a closed two-value vocabulary</strong>, and it is closed because of
 * where the rows come from. The registry's {@code live()} returns only runs that have not been
 * retired, so "finished" is not representable here by construction — what remains is whether the run
 * is advancing or stopped:
 *
 * <ul>
 *   <li>{@code "parked"} — the run is stopped and waiting; {@link #park()} says what for.
 *   <li>{@code "running"} — the run is advancing.
 * </ul>
 *
 * <p>A one-shot run is always {@code "running"}: it has no control point to park at, which §3.4
 * makes structural rather than documentary.
 *
 * @param runId an agent {@code sessionId} or a minted {@code run-<uuid>} — one namespace (§3.2)
 * @param conversationId blank when the run answers into no thread (an OPERATOR-audience dispatch, a
 *     workflow); blank says so honestly rather than inventing an id
 * @param park null when the run is not parked
 * @param updatedAtEpochMs when the run last published a NARRATIVE frame. Heartbeats deliberately do
 *     not bump it — a parked run must look stopped, and a liveness write that made it look busy
 *     would be the one lie this field can tell.
 * @param observerCount live observers right now. Eventually consistent with a bound of one heartbeat
 *     interval on the closing side: a managed stream learns its client left on its next write, so a
 *     departure shows up here within one heartbeat rather than instantly (§15.0 D1.1).
 * @param snapshot the act-on-the-run primer; null for a one-shot run, which has no stepped state
 */
public record LiveRunSummary(
    String runId,
    String shapeId,
    String conversationId,
    String state,
    ParkSummary park,
    long startedAtEpochMs,
    long updatedAtEpochMs,
    int observerCount,
    RunStateSnapshotView snapshot) {

  /** The run is stopped and waiting; {@code park} says what for. */
  public static final String STATE_PARKED = "parked";

  /** The run is advancing. */
  public static final String STATE_RUNNING = "running";
}
