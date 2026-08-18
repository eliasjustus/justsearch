/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.run;

import java.util.List;

/**
 * The act-on-the-run primer, as the enumeration serves it (tempdoc 834 §5.1, §6.1-6.2).
 *
 * <p><strong>The law this carries:</strong> every fact required to ACT on a run lives in the
 * snapshot; the ring carries narrative only. So an enumerated run that is parked at an approval gate
 * arrives with the {@code callId} needed to answer it, even though the {@code tool_call_pending}
 * frame that announced it may long since have been evicted from the replay ring.
 *
 * <p>Boxed types throughout, and this is load-bearing rather than incidental: a legacy or partial
 * snapshot simply LACKS a key, and absent must read as UNKNOWN — {@code null} says that, whereas a
 * primitive would silently say {@code 0}. {@code pendingApprovals} is the one exception in spirit:
 * an EMPTY list means "none pending" and an absent list means "unknown", so the two stay
 * distinguishable.
 *
 * @param park the same park the enclosing {@link LiveRunSummary} carries, as the snapshot recorded
 *     it at the moment it was taken
 */
public record RunStateSnapshotView(
    Integer iteration,
    Integer budgetRemaining,
    Integer toolCallsExecuted,
    Integer messageCount,
    String activeAgentId,
    List<PendingApprovalView> pendingApprovals,
    String autonomyLevel,
    ParkSummary park) {}
