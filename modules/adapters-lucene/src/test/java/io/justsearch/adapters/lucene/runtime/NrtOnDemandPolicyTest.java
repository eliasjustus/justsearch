/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.runtime.NrtOnDemandPolicy.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 885 item 19 — the reopen-on-demand ladder, asserted branch by branch. */
final class NrtOnDemandPolicyTest {

  private static final long MAX_STALE = 1000L;

  @Test
  @DisplayName("continuous mode never refreshes on the foreground path, however stale")
  void continuousModeAlwaysSkips() {
    assertEquals(
        Action.SKIP, NrtOnDemandPolicy.decide(NrtMode.CONTINUOUS, true, 500L, 100L, 60_000L, MAX_STALE));
  }

  @Test
  @DisplayName("nothing written since the last reopen: the searcher is fresh, so no refresh")
  void freshSearcherSkips() {
    // Deliberately paired with a huge staleMs: age alone must NOT force a refresh, or an idle
    // Worker would reopen on every query forever.
    assertEquals(
        Action.SKIP, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 42L, 42L, 60_000L, MAX_STALE));
  }

  @Test
  @DisplayName("new writes within the staleness bound take the non-blocking path")
  void newWritesWithinBoundRefreshNonBlocking() {
    assertEquals(
        Action.REFRESH, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 43L, 42L, 999L, MAX_STALE));
    assertEquals(
        Action.REFRESH,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 43L, 42L, MAX_STALE, MAX_STALE),
        "the bound itself is still the non-blocking side");
  }

  @Test
  @DisplayName("new writes past the staleness bound escalate to a blocking refresh")
  void newWritesPastBoundRefreshBlocking() {
    assertEquals(
        Action.REFRESH_BLOCKING,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 43L, 42L, MAX_STALE + 1, MAX_STALE));
  }

  /**
   * Guards the {@code -1} sentinel only. It is UNREACHABLE in production: {@code
   * NrtReopenStats.install} seeds the watermark from the writer at open, so a read-write session
   * never presents -1 to the policy. Kept as a total-function guard, not as a behaviour claim.
   */
  @Test
  @DisplayName("a searcher that has never been reopened blocks, so the first query cannot miss")
  void neverReopenedBlocks() {
    assertEquals(
        Action.REFRESH_BLOCKING,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 7L, -1L, Long.MAX_VALUE, MAX_STALE));
  }

  @Test
  @DisplayName("a zero bound makes every write-visible query block")
  void zeroBoundAlwaysBlocks() {
    assertEquals(
        Action.REFRESH_BLOCKING, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 43L, 42L, 1L, 0L));
  }

  @Test
  @DisplayName("a background read never refreshes, however stale and however many new writes")
  void backgroundReadNeverRefreshes() {
    // The defect this gate exists for: enrichment backfill fetches every document it enriches
    // through the SAME SearcherBridge a search uses, so mode alone cannot tell them apart. In the
    // 885 live window that turned every backfill fetch into a reopen — 193 -> 568 reopens and a
    // 15% indexing-throughput loss. Deliberately paired with the strongest possible refresh case
    // (brand-new writes AND an ancient searcher): neither may override the foreground gate.
    assertEquals(
        Action.SKIP,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, false, 9_999L, 42L, Long.MAX_VALUE, MAX_STALE));
    assertEquals(
        Action.SKIP,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, false, 43L, 42L, 1L, MAX_STALE));
  }

  @Test
  @DisplayName("the foreground flag is a gate, not a trigger: fresh still skips when foreground")
  void foregroundDoesNotForceARefresh() {
    assertEquals(
        Action.SKIP, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, true, 42L, 42L, 60_000L, MAX_STALE));
  }
}
