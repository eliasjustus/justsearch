package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConditionStore")
final class ConditionStoreTest {

  private static final Source SRC = Source.forProcess("worker", "instance-1", "1.0");

  private static HealthEvent condition(
      String id, String subject, ConditionStatus status, String reason, Instant transition) {
    return condition(id, subject, status, reason, transition, Optional.empty());
  }

  private static HealthEvent condition(
      String id,
      String subject,
      ConditionStatus status,
      String reason,
      Instant transition,
      Optional<String> message) {
    return condition(id, subject, status, reason, transition, message, Optional.empty());
  }

  private static HealthEvent condition(
      String id,
      String subject,
      ConditionStatus status,
      String reason,
      Instant transition,
      Optional<String> message,
      Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        id,
        Instant.parse("2026-04-30T12:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        new AssertedCondition(subject, status, reason, transition, message, recovery, List.of()));
  }

  @Test
  @DisplayName("first upsert is ADDED and bumps version")
  void firstUpsertIsAdded() {
    ConditionStore store = new ConditionStore();
    long before = store.currentVersion();
    ConditionStore.Transition t =
        store.upsert(
            condition(
                "index.unavailable",
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z")));
    assertEquals(ConditionStore.Transition.ADDED, t);
    assertTrue(store.currentVersion() > before);
    assertEquals(1, store.currentSnapshot().size());
  }

  @Test
  @DisplayName("re-emit with identical status/reason/message is UNCHANGED")
  void unchangedReemit() {
    ConditionStore store = new ConditionStore();
    HealthEvent first =
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            Instant.parse("2026-04-30T11:59:00Z"));
    store.upsert(first);
    long versionAfterAdd = store.currentVersion();
    ConditionStore.Transition t = store.upsert(first);
    assertEquals(ConditionStore.Transition.UNCHANGED, t);
    assertEquals(versionAfterAdd, store.currentVersion());
  }

  @Test
  @DisplayName("status flip stores the caller's lastTransitionTime (k8s status-change)")
  void statusFlipUpdatesLastTransitionTime() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = Instant.parse("2026-04-30T12:01:00Z");
    store.upsert(
        condition("index.unavailable", "worker", ConditionStatus.FALSE, "WorkerStarting", t0));
    long versionBefore = store.currentVersion();
    ConditionStore.Transition t =
        store.upsert(
            condition("index.unavailable", "worker", ConditionStatus.TRUE, "WorkerStarted", t1));
    assertEquals(ConditionStore.Transition.MODIFIED, t);
    assertNotEquals(versionBefore, store.currentVersion());
    HealthEvent stored = store.find("index.unavailable", "worker").orElseThrow();
    assertEquals(t1, ((AssertedCondition) stored.body()).lastTransitionTime());
  }

  @Test
  @DisplayName("reason-only change preserves prior lastTransitionTime (k8s SetStatusCondition)")
  void reasonChangePreservesLastTransitionTime() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = Instant.parse("2026-04-30T12:01:00Z");
    store.upsert(
        condition("index.unavailable", "worker", ConditionStatus.FALSE, "WorkerStarting", t0));

    // Caller naively passes "now" (t1) as lastTransitionTime; status is unchanged so the
    // store MUST preserve t0.
    ConditionStore.Transition t =
        store.upsert(
            condition("index.unavailable", "worker", ConditionStatus.FALSE, "WorkerCrashed", t1));

    assertEquals(ConditionStore.Transition.MODIFIED, t);
    AssertedCondition stored =
        (AssertedCondition) store.find("index.unavailable", "worker").orElseThrow().body();
    assertEquals(
        t0,
        stored.lastTransitionTime(),
        "Status-equal/reason-different MUST preserve prior lastTransitionTime per k8s");
    assertEquals("WorkerCrashed", stored.reason(), "New reason must still be stored");
  }

  @Test
  @DisplayName("message-only change preserves prior lastTransitionTime")
  void messageChangePreservesLastTransitionTime() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = Instant.parse("2026-04-30T12:01:00Z");
    store.upsert(
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            t0,
            Optional.of("starting")));

    ConditionStore.Transition t =
        store.upsert(
            condition(
                "index.unavailable",
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                t1,
                Optional.of("still starting")));

    assertEquals(ConditionStore.Transition.MODIFIED, t);
    AssertedCondition stored =
        (AssertedCondition) store.find("index.unavailable", "worker").orElseThrow().body();
    assertEquals(t0, stored.lastTransitionTime());
    assertEquals(Optional.of("still starting"), stored.message());
  }

  // Slice 438 §B.B (post-impl tightening): the rewriteAssertedTransitionTime path also
  // propagates recoveryOperationId. A regression that dropped the field from the rewrite
  // (e.g., constructed AssertedCondition without it) would silently lose the recovery
  // pointer on every reason/message-only update. The next two tests exercise both
  // preservation branches with a populated OperationRef.

  @Test
  @DisplayName("reason-only change preserves recoveryOperationId (slice 438 §B.B)")
  void reasonChangePreservesRecoveryOperationId() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = Instant.parse("2026-04-30T12:01:00Z");
    OperationInvocation recovery = OperationInvocation.of(new OperationRef("core.rebuild-index"));
    store.upsert(
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            t0,
            Optional.empty(),
            Optional.of(recovery)));

    store.upsert(
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "IndexCorrupted",
            t1,
            Optional.empty(),
            Optional.of(recovery)));

    AssertedCondition stored =
        (AssertedCondition) store.find("index.unavailable", "worker").orElseThrow().body();
    assertEquals(t0, stored.lastTransitionTime(), "lastTransitionTime preserved");
    assertEquals(
        Optional.of(recovery),
        stored.recovery(),
        "recoveryOperationId must survive the reason-only rewrite (per ConditionStore"
            + ".rewriteAssertedTransitionTime).");
  }

  @Test
  @DisplayName("message-only change preserves recoveryOperationId (slice 438 §B.B)")
  void messageChangePreservesRecoveryOperationId() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = Instant.parse("2026-04-30T12:01:00Z");
    OperationInvocation recovery = OperationInvocation.of(new OperationRef("core.rebuild-index"));
    store.upsert(
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            t0,
            Optional.of("starting"),
            Optional.of(recovery)));

    store.upsert(
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            t1,
            Optional.of("still starting"),
            Optional.of(recovery)));

    AssertedCondition stored =
        (AssertedCondition) store.find("index.unavailable", "worker").orElseThrow().body();
    assertEquals(t0, stored.lastTransitionTime());
    assertEquals(Optional.of(recovery), stored.recovery());
  }

  @Test
  @DisplayName("clear of present condition returns the removed event + bumps version")
  void clearReturnsRemovedEvent() {
    ConditionStore store = new ConditionStore();
    HealthEvent original =
        condition(
            "index.unavailable",
            "worker",
            ConditionStatus.FALSE,
            "WorkerStarting",
            Instant.parse("2026-04-30T11:59:00Z"));
    store.upsert(original);
    long before = store.currentVersion();
    Optional<HealthEvent> removed = store.clear("index.unavailable", "worker");
    assertTrue(removed.isPresent(), "clear must return the removed event for broadcast");
    assertEquals(original, removed.get());
    assertTrue(store.currentVersion() > before);
    assertEquals(0, store.currentSnapshot().size());
  }

  @Test
  @DisplayName("clear of absent condition returns empty + version stable")
  void clearAbsentReturnsEmpty() {
    ConditionStore store = new ConditionStore();
    long before = store.currentVersion();
    Optional<HealthEvent> removed = store.clear("missing.id", "missing.subject");
    assertTrue(removed.isEmpty());
    assertEquals(before, store.currentVersion());
  }

  @Test
  @DisplayName("upserting non-AssertedCondition body is rejected")
  void rejectsLifecycleUpsert() {
    ConditionStore store = new ConditionStore();
    HealthEvent lifecycle =
        new HealthEvent(
            "agent.session.completed",
            Instant.parse("2026-04-30T12:00:00Z"),
            SRC,
            Severity.INFO,
            Optional.empty(),
            LifecycleEvent.empty());
    assertThrows(IllegalArgumentException.class, () -> store.upsert(lifecycle));
  }

  @Test
  @DisplayName("currentSnapshot returns conditions in stable order by (id, subject)")
  void currentSnapshotIsOrderedById() {
    ConditionStore store = new ConditionStore();
    Instant t = Instant.parse("2026-04-30T11:59:00Z");
    // Insert in reverse order; snapshot must come back lexicographically sorted.
    store.upsert(condition("zeta.unavailable", "worker", ConditionStatus.FALSE, "ZetaDown", t));
    store.upsert(condition("alpha.unavailable", "worker", ConditionStatus.FALSE, "AlphaDown", t));
    store.upsert(condition("alpha.unavailable", "head", ConditionStatus.FALSE, "AlphaHeadDown", t));

    List<HealthEvent> snapshot = store.currentSnapshot();
    assertEquals(3, snapshot.size());
    assertEquals("alpha.unavailable", snapshot.get(0).id());
    assertEquals("head", ((AssertedCondition) snapshot.get(0).body()).subject());
    assertEquals("alpha.unavailable", snapshot.get(1).id());
    assertEquals("worker", ((AssertedCondition) snapshot.get(1).body()).subject());
    assertEquals("zeta.unavailable", snapshot.get(2).id());
  }

  @Test
  @DisplayName("concurrent first-emit on the same key produces exactly one ADDED")
  void concurrentUpsertDoesNotDoubleAdd() throws Exception {
    ConditionStore store = new ConditionStore();
    int threads = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      AtomicInteger added = new AtomicInteger();
      AtomicInteger unchanged = new AtomicInteger();
      AtomicInteger modified = new AtomicInteger();
      Instant t = Instant.parse("2026-04-30T11:59:00Z");
      HealthEvent racer =
          condition("index.unavailable", "worker", ConditionStatus.FALSE, "WorkerStarting", t);
      CountDownLatch done = new CountDownLatch(threads);
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                start.await();
                ConditionStore.Transition transition = store.upsert(racer);
                switch (transition) {
                  case ADDED -> added.incrementAndGet();
                  case UNCHANGED -> unchanged.incrementAndGet();
                  case MODIFIED -> modified.incrementAndGet();
                  default -> throw new AssertionError("unexpected: " + transition);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertTrue(done.await(10, TimeUnit.SECONDS), "Threads should finish within timeout");
      assertEquals(1, added.get(), "Exactly one ADDED across concurrent racers");
      assertEquals(threads - 1, unchanged.get(), "Remaining racers see UNCHANGED");
      assertEquals(0, modified.get());
      assertEquals(1L, store.currentVersion(), "Version increments exactly once");
      assertEquals(1, store.currentSnapshot().size());
    } finally {
      pool.shutdownNow();
    }
  }

  // ============================================================
  // ThresholdState support (rev 3.11 §B.X.2 generalization)
  // ============================================================

  private static HealthEvent threshold(
      String id,
      String subject,
      ThresholdPhase phase,
      java.util.Map<String, Number> magnitudes,
      Instant transition) {
    return threshold(id, subject, phase, magnitudes, transition, Optional.empty());
  }

  private static HealthEvent threshold(
      String id,
      String subject,
      ThresholdPhase phase,
      java.util.Map<String, Number> magnitudes,
      Instant transition,
      Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        id,
        Instant.parse("2026-04-30T12:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        new ThresholdState(
            subject, phase, magnitudes, transition, Optional.empty(), recovery, List.of()));
  }

  @Test
  @DisplayName("ThresholdState first upsert is ADDED")
  void thresholdFirstUpsertIsAdded() {
    ConditionStore store = new ConditionStore();
    Instant t = Instant.parse("2026-04-30T11:59:00Z");
    ConditionStore.Transition tr =
        store.upsert(
            threshold(
                "memory.pressure",
                "head.memory",
                ThresholdPhase.FIRING,
                java.util.Map.of("ratio_pct", 92),
                t));
    assertEquals(ConditionStore.Transition.ADDED, tr);
    assertEquals(1L, store.currentVersion());
  }

  @Test
  @DisplayName("ThresholdState re-emit identical phase+magnitudes is UNCHANGED")
  void thresholdReemitUnchanged() {
    ConditionStore store = new ConditionStore();
    Instant t = Instant.parse("2026-04-30T11:59:00Z");
    HealthEvent e1 =
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 92),
            t);
    store.upsert(e1);
    long versionAfterAdded = store.currentVersion();
    ConditionStore.Transition tr = store.upsert(e1);
    assertEquals(ConditionStore.Transition.UNCHANGED, tr);
    assertEquals(versionAfterAdded, store.currentVersion());
  }

  @Test
  @DisplayName("ThresholdState magnitude-only change preserves prior lastTransitionTime")
  void thresholdMagnitudeOnlyPreservesTransitionTime() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = t0.plusSeconds(60);
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 92),
            t0));
    ConditionStore.Transition tr =
        store.upsert(
            threshold(
                "memory.pressure",
                "head.memory",
                ThresholdPhase.FIRING,
                java.util.Map.of("ratio_pct", 95),
                t1));
    assertEquals(ConditionStore.Transition.MODIFIED, tr);
    HealthEvent stored = store.find("memory.pressure", "head.memory").orElseThrow();
    ThresholdState body = (ThresholdState) stored.body();
    // Magnitude updated, but transitionTime preserved.
    assertEquals(95, body.magnitudes().get("ratio_pct"));
    assertEquals(t0, body.lastTransitionTime());
  }

  @Test
  @DisplayName(
      "ThresholdState magnitude-only change preserves recoveryOperationId (slice 438 §B.B)")
  void thresholdMagnitudeOnlyPreservesRecoveryOperationId() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = t0.plusSeconds(60);
    OperationInvocation recovery = OperationInvocation.of(new OperationRef("core.gc-cache"));
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 92),
            t0,
            Optional.of(recovery)));
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 95),
            t1,
            Optional.of(recovery)));

    ThresholdState body =
        (ThresholdState) store.find("memory.pressure", "head.memory").orElseThrow().body();
    assertEquals(t0, body.lastTransitionTime(), "lastTransitionTime preserved");
    assertEquals(
        Optional.of(recovery),
        body.recovery(),
        "recoveryOperationId must survive the magnitude-only rewrite (per ConditionStore"
            + ".rewriteThresholdTransitionTime).");
  }

  @Test
  @DisplayName("ThresholdState phase change moves lastTransitionTime")
  void thresholdPhaseChangeUpdatesTransitionTime() {
    ConditionStore store = new ConditionStore();
    Instant t0 = Instant.parse("2026-04-30T11:59:00Z");
    Instant t1 = t0.plusSeconds(60);
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.PENDING,
            java.util.Map.of("ratio_pct", 92),
            t0));
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 95),
            t1));
    HealthEvent stored = store.find("memory.pressure", "head.memory").orElseThrow();
    ThresholdState body = (ThresholdState) stored.body();
    assertEquals(ThresholdPhase.FIRING, body.phase());
    assertEquals(t1, body.lastTransitionTime());
  }

  @Test
  @DisplayName("currentSnapshot orders mixed body types uniformly by (id, subject)")
  void snapshotMixedBodyTypes() {
    ConditionStore store = new ConditionStore();
    Instant t = Instant.parse("2026-04-30T11:59:00Z");
    store.upsert(condition("zeta.unavailable", "worker", ConditionStatus.TRUE, "ZetaDown", t));
    store.upsert(
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 95),
            t));
    store.upsert(condition("alpha.unavailable", "worker", ConditionStatus.TRUE, "AlphaDown", t));

    List<HealthEvent> snapshot = store.currentSnapshot();
    assertEquals(3, snapshot.size());
    assertEquals("alpha.unavailable", snapshot.get(0).id());
    assertEquals("memory.pressure", snapshot.get(1).id());
    assertEquals("zeta.unavailable", snapshot.get(2).id());
  }

  @Test
  @DisplayName("clear of ThresholdState returns the removed event")
  void clearThresholdReturnsRemoved() {
    ConditionStore store = new ConditionStore();
    Instant t = Instant.parse("2026-04-30T11:59:00Z");
    HealthEvent fired =
        threshold(
            "memory.pressure",
            "head.memory",
            ThresholdPhase.FIRING,
            java.util.Map.of("ratio_pct", 95),
            t);
    store.upsert(fired);
    Optional<HealthEvent> removed = store.clear("memory.pressure", "head.memory");
    assertTrue(removed.isPresent());
    assertEquals("memory.pressure", removed.get().id());
    assertTrue(store.find("memory.pressure", "head.memory").isEmpty());
  }
}
