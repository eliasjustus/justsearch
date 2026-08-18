/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.run;

import java.util.List;

/**
 * The body of {@code GET /api/chat/runs/live} — every run executing right now (tempdoc 834 §5.1).
 *
 * <p><strong>A list, never collapsed by conversation</strong> (§3.5). Nothing serializes two
 * dispatches on one {@code conversationId}, so this may legitimately carry N &gt; 1 rows for the same
 * conversation. Turn-taking is a product decision that belongs in the FE composer where the user's
 * intent lives, not silently in the run substrate; the FE's "is this conversation answering?" is
 * {@code runs.length > 0}, and presenting N as one is a rendering choice it owns.
 *
 * <p><strong>No {@code interrupted} row.</strong> An interrupted run is a PERSISTED run and never
 * appears in a live enumeration — the registry only holds what is still executing. Interruption
 * surfaces on the persisted-sessions record instead ({@code AgentSessionSummary.interruptedAt},
 * §5.3), which is why the two views compose rather than overlap.
 *
 * @param runs ordered by {@code startedAtEpochMs} descending — newest first
 */
public record LiveRunsResponse(List<LiveRunSummary> runs) {

  public LiveRunsResponse {
    runs = runs == null ? List.of() : List.copyOf(runs);
  }
}
