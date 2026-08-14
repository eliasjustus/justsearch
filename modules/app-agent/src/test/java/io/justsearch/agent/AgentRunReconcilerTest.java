/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 834 §5.2 — reconciliation of runs the previous process left mid-flight.
 *
 * <p>The adverse precondition is the point of this file. With at-rest encryption on and the store
 * LOCKED, {@code readMeta} returns null and {@code listSessions} yields nothing — so a boot-only
 * reconciliation is a silent no-op on exactly the encrypted installs, and a green test that only
 * ever ran against a plaintext store would prove nothing about them (834 R5). Both branches are
 * exercised here.
 */
final class AgentRunReconcilerTest {

  @TempDir Path tempDir;

  /** A DataKeyState whose lock can be flipped, standing in for {@code DataKeyManager}. */
  private static final class TestKey implements DataKeyState {
    private final byte[] dek = new byte[32];
    private volatile boolean locked;

    TestKey() {
      new SecureRandom().nextBytes(dek);
    }

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public boolean locked() {
      return locked;
    }

    @Override
    public byte[] dek() {
      if (locked) {
        throw new KeyLockedException();
      }
      return dek.clone();
    }
  }

  private AgentRunStore plainStore() {
    return new AgentRunStore(tempDir.resolve("agent-runs"));
  }

  private static void startRun(AgentRunStore store, String sessionId, String state) {
    var request = new AgentRequest(List.of(Map.of("role", "user", "content", "hi")), List.of(), 5);
    store.startRun(sessionId, request, request.messages(), 8000);
    store.updateCheckpoint(sessionId, state, request.messages(), 1, 0, 30, "");
  }

  private static Map<String, Object> rowFor(AgentRunStore store, String sessionId) {
    return store.listSessions(100).stream()
        .filter(s -> sessionId.equals(s.get("sessionId")))
        .findFirst()
        .orElse(null);
  }

  @Test
  @DisplayName("a non-terminal run is stamped; a terminal one is not")
  void stampsOnlyUnfinishedRuns() {
    AgentRunStore store = plainStore();
    startRun(store, "live-1", "WAITING_APPROVAL");
    startRun(store, "done-1", "DONE");

    assertEquals(1, new AgentRunReconciler(store).reconcile(Instant.parse("2026-08-14T10:00:00Z")));

    assertEquals("2026-08-14T10:00:00Z", rowFor(store, "live-1").get("interruptedAt"));
    assertNull(rowFor(store, "done-1").get("interruptedAt"), "a finished run gets no marker");
  }

  @Test
  @DisplayName("the marker is ADDITIVE — state and resumable are untouched")
  void doesNotTouchTheResumeSeed() {
    AgentRunStore store = plainStore();
    startRun(store, "live-1", "WAITING_APPROVAL");
    new AgentRunReconciler(store).reconcile();

    Map<String, Object> row = rowFor(store, "live-1");
    // 834 §5.2: `state` IS the resume seed (handleResumeSessionStream replays from the checkpoint it
    // names), so overwriting it to record "not running" would destroy what makes the run resumable.
    assertEquals("WAITING_APPROVAL", row.get("state"));
    assertEquals(true, row.get("resumable"));
    assertNotNull(row.get("interruptedAt"));
  }

  @Test
  @DisplayName("idempotent: a second pass does not re-stamp or move the timestamp")
  void idempotentAcrossPasses() {
    AgentRunStore store = plainStore();
    startRun(store, "live-1", "READY_FOR_LLM");
    var reconciler = new AgentRunReconciler(store);

    assertEquals(1, reconciler.reconcile(Instant.parse("2026-08-14T10:00:00Z")));
    assertEquals(
        0,
        reconciler.reconcile(Instant.parse("2026-08-14T11:00:00Z")),
        "boot + unlock + re-unlock all run this pass, so a repeat must be a no-op");
    assertEquals(
        "2026-08-14T10:00:00Z",
        rowFor(store, "live-1").get("interruptedAt"),
        "the ORIGINAL interruption time survives; a later pass must not overwrite it");
  }

  @Test
  @DisplayName("ADVERSE PRECONDITION: on a locked store the pass is a no-op that completes on unlock")
  void lockedStoreIsANoOpUntilUnlock() {
    var key = new TestKey();
    Path runs = tempDir.resolve("agent-runs");
    var store = new AgentRunStore(runs, new StoreCipher(key));
    startRun(store, "live-1", "READY_FOR_LLM");

    // Lock, as a fresh boot on an encrypted install would be.
    key.locked = true;
    var reconciler = new AgentRunReconciler(store);
    assertEquals(0, reconciler.reconcile(), "a locked store reads empty — nothing to stamp");
    assertTrue(store.listSessions(100).isEmpty(), "and the ledger really is unreadable while locked");

    // Unlock: this is the seam the design requires, because without it the boot-only pass above
    // would be the ONLY pass and encrypted installs would never get a marker at all.
    key.locked = false;
    assertEquals(
        1,
        reconciler.reconcile(Instant.parse("2026-08-14T10:00:00Z")),
        "the unlock pass must complete the work the boot pass could not do");
    assertEquals("2026-08-14T10:00:00Z", rowFor(store, "live-1").get("interruptedAt"));
  }

  @Test
  @DisplayName("the pass never throws — a DataKeyManager listener's fault would be invisible")
  void neverThrows() {
    // DataKeyManager.fire SWALLOWS listener throws, so a throw on this path is not loud, it is
    // GONE. The reconciler must therefore absorb its own failures rather than rely on a caller.
    var key = new TestKey();
    key.locked = true;
    var store = new AgentRunStore(tempDir.resolve("agent-runs"), new StoreCipher(key));
    assertEquals(0, new AgentRunReconciler(store).reconcile());

    // A disabled (no root dir) store is the other degenerate case.
    assertEquals(0, new AgentRunReconciler(AgentRunStore.noop()).reconcile());
  }

  @Test
  @DisplayName("markInterrupted on a locked store neither stamps nor corrupts")
  void markInterruptedRefusesWhileLocked() {
    var key = new TestKey();
    var store = new AgentRunStore(tempDir.resolve("agent-runs"), new StoreCipher(key));
    startRun(store, "live-1", "READY_FOR_LLM");

    key.locked = true;
    assertFalse(store.markInterrupted("live-1", Instant.now()), "locked reads yield no meta to patch");

    key.locked = false;
    assertNull(
        rowFor(store, "live-1").get("interruptedAt"),
        "the refused write must not have left a partial stamp behind");
  }
}
