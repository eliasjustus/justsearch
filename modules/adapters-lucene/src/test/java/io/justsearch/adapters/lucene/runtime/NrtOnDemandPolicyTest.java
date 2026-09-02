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
        Action.SKIP, NrtOnDemandPolicy.decide(NrtMode.CONTINUOUS, 500L, 100L, 60_000L, MAX_STALE));
  }

  @Test
  @DisplayName("nothing written since the last reopen: the searcher is fresh, so no refresh")
  void freshSearcherSkips() {
    // Deliberately paired with a huge staleMs: age alone must NOT force a refresh, or an idle
    // Worker would reopen on every query forever.
    assertEquals(
        Action.SKIP, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 42L, 42L, 60_000L, MAX_STALE));
  }

  @Test
  @DisplayName("new writes within the staleness bound take the non-blocking path")
  void newWritesWithinBoundRefreshNonBlocking() {
    assertEquals(
        Action.REFRESH, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 43L, 42L, 999L, MAX_STALE));
    assertEquals(
        Action.REFRESH,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 43L, 42L, MAX_STALE, MAX_STALE),
        "the bound itself is still the non-blocking side");
  }

  @Test
  @DisplayName("new writes past the staleness bound escalate to a blocking refresh")
  void newWritesPastBoundRefreshBlocking() {
    assertEquals(
        Action.REFRESH_BLOCKING,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 43L, 42L, MAX_STALE + 1, MAX_STALE));
  }

  @Test
  @DisplayName("a searcher that has never been reopened blocks, so the first query cannot miss")
  void neverReopenedBlocks() {
    assertEquals(
        Action.REFRESH_BLOCKING,
        NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 7L, -1L, Long.MAX_VALUE, MAX_STALE));
  }

  @Test
  @DisplayName("a zero bound makes every write-visible query block")
  void zeroBoundAlwaysBlocks() {
    assertEquals(
        Action.REFRESH_BLOCKING, NrtOnDemandPolicy.decide(NrtMode.ON_DEMAND, 43L, 42L, 1L, 0L));
  }
}
