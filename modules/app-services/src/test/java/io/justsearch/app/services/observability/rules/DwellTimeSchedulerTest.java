package io.justsearch.app.services.observability.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DwellTimeScheduler")
final class DwellTimeSchedulerTest {

  private static final Instant T0 = Instant.parse("2026-05-02T10:00:00Z");
  private MutableClock clock;
  private DwellTimeScheduler scheduler;

  /** Standard test rule: for=60s, keep_firing_for=30s. */
  private static final Rule STANDARD_RULE =
      new Rule(
          "test-rule",
          Rule.Kind.THRESHOLD,
          new Rule.Emits("test.id", "test.subject", "TestReason", Severity.WARNING),
          "true",
          Duration.ofSeconds(60),
          Duration.ofSeconds(30),
          Map.of());

  /** Fire-immediately rule: for=0. */
  private static final Rule IMMEDIATE_RULE =
      new Rule(
          "immediate-rule",
          Rule.Kind.CONDITION,
          new Rule.Emits("test.imm", "test.s", "Imm", Severity.INFO),
          "true",
          Duration.ZERO,
          Duration.ZERO,
          Map.of());

  /** Evaluated-true / -false predicate outcomes (tempdoc 600 Design B tri-state). */
  private static final PredicateOutcome EV_TRUE = PredicateOutcome.evaluated(true);
  private static final PredicateOutcome EV_FALSE = PredicateOutcome.evaluated(false);

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    scheduler = new DwellTimeScheduler(clock);
  }

  @Test
  @DisplayName("INACTIVE + false → INACTIVE (no transition)")
  void inactiveStaysInactiveWhenFalse() {
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_FALSE);
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("INACTIVE + true → PENDING (no emission yet)")
  void inactiveTrueGoesPending() {
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("PENDING + false → INACTIVE (predicate flipped before `for` elapsed)")
  void pendingFalseResets() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING
    clock.advance(Duration.ofSeconds(30)); // < for=60
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_FALSE);
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("PENDING + true after `for` elapses → FIRING (emit STARTED_FIRING)")
  void pendingTrueAfterForFires() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING at T0
    clock.advance(Duration.ofSeconds(60)); // exactly at `for`
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    assertTrue(t.isPresent());
    assertEquals(DwellTimeScheduler.Transition.STARTED_FIRING, t.get());
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("PENDING + true before `for` elapses → still PENDING")
  void pendingTrueBeforeForStays() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING at T0
    clock.advance(Duration.ofSeconds(30)); // < for=60
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("FIRING + false → KEEP_FIRING (no immediate resolution)")
  void firingFalseGoesKeepFiring() {
    fireImmediate();
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_FALSE);
    assertTrue(t.isEmpty());
    assertEquals(
        DwellTimeScheduler.State.KEEP_FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("KEEP_FIRING + true before grace expires → FIRING (no new emission)")
  void keepFiringTrueGoesFiringNoEmission() {
    fireImmediate();
    scheduler.tick(STANDARD_RULE, EV_FALSE); // → KEEP_FIRING
    clock.advance(Duration.ofSeconds(15)); // < keep_firing_for=30
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    assertTrue(t.isEmpty(), "should NOT re-emit STARTED_FIRING when predicate flaps back true");
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("KEEP_FIRING + false after grace → INACTIVE (emit RESOLVED)")
  void keepFiringFalseAfterGraceResolves() {
    fireImmediate();
    scheduler.tick(STANDARD_RULE, EV_FALSE); // → KEEP_FIRING at T0+60
    clock.advance(Duration.ofSeconds(30)); // == keep_firing_for
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_FALSE);
    assertTrue(t.isPresent());
    assertEquals(DwellTimeScheduler.Transition.RESOLVED, t.get());
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("KEEP_FIRING + false before grace → still KEEP_FIRING")
  void keepFiringFalseBeforeGraceStays() {
    fireImmediate();
    scheduler.tick(STANDARD_RULE, EV_FALSE); // → KEEP_FIRING
    clock.advance(Duration.ofSeconds(15)); // < keep_firing_for
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_FALSE);
    assertTrue(t.isEmpty());
    assertEquals(
        DwellTimeScheduler.State.KEEP_FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("Full lifecycle INACTIVE → PENDING → FIRING → KEEP_FIRING → INACTIVE")
  void fullLifecycle() {
    // INACTIVE → PENDING
    assertTrue(scheduler.tick(STANDARD_RULE, EV_TRUE).isEmpty());
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));

    // PENDING → FIRING (after for=60s)
    clock.advance(Duration.ofSeconds(60));
    assertEquals(
        DwellTimeScheduler.Transition.STARTED_FIRING,
        scheduler.tick(STANDARD_RULE, EV_TRUE).orElseThrow());
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));

    // FIRING + true → still FIRING (no new emit)
    clock.advance(Duration.ofSeconds(10));
    assertTrue(scheduler.tick(STANDARD_RULE, EV_TRUE).isEmpty());

    // FIRING → KEEP_FIRING
    assertTrue(scheduler.tick(STANDARD_RULE, EV_FALSE).isEmpty());

    // KEEP_FIRING → INACTIVE (after keep_firing_for=30s)
    clock.advance(Duration.ofSeconds(30));
    assertEquals(
        DwellTimeScheduler.Transition.RESOLVED,
        scheduler.tick(STANDARD_RULE, EV_FALSE).orElseThrow());
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState(STANDARD_RULE.name()));
  }

  // ============================================================
  // §A.9 edge cases
  // ============================================================

  @Test
  @DisplayName("§A.9: for=0 fires immediately on first true")
  void forZeroFiresImmediately() {
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(IMMEDIATE_RULE, EV_TRUE);
    assertTrue(t.isPresent());
    assertEquals(DwellTimeScheduler.Transition.STARTED_FIRING, t.get());
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(IMMEDIATE_RULE.name()));
  }

  @Test
  @DisplayName("§A.9: clock skew backward clamps elapsed to zero (no fire)")
  void clockSkewBackwardClamped() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING at T0
    clock.setTo(T0.minusSeconds(3600)); // 1 hour backward
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    // Elapsed clamps to 0; for=60s not satisfied → still PENDING.
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("§A.9: cyclic flapping within keep_firing_for stays FIRING (no double-emit)")
  void cyclicFlappingNoDoubleEmit() {
    fireImmediate();
    // false → KEEP_FIRING
    scheduler.tick(STANDARD_RULE, EV_FALSE);
    clock.advance(Duration.ofSeconds(5));
    // true → FIRING (no emit)
    assertTrue(scheduler.tick(STANDARD_RULE, EV_TRUE).isEmpty());
    clock.advance(Duration.ofSeconds(5));
    // false → KEEP_FIRING
    scheduler.tick(STANDARD_RULE, EV_FALSE);
    clock.advance(Duration.ofSeconds(5));
    // true again → FIRING (no emit — second flap)
    assertTrue(scheduler.tick(STANDARD_RULE, EV_TRUE).isEmpty());
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("§A.9: predicate-false during KEEP_FIRING then true again preserves FIRING")
  void keepFiringFlapsBackPreservesFiring() {
    fireImmediate();
    scheduler.tick(STANDARD_RULE, EV_FALSE); // → KEEP_FIRING
    clock.advance(Duration.ofSeconds(10));
    Optional<DwellTimeScheduler.Transition> t = scheduler.tick(STANDARD_RULE, EV_TRUE);
    assertTrue(t.isEmpty()); // No new STARTED_FIRING.
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  // ============================================================
  // Multi-rule isolation
  // ============================================================

  @Test
  @DisplayName("two rules' states are independent")
  void multipleRulesIndependent() {
    scheduler.tick(STANDARD_RULE, EV_TRUE);
    scheduler.tick(IMMEDIATE_RULE, EV_TRUE); // fires immediately

    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(IMMEDIATE_RULE.name()));
  }

  @Test
  @DisplayName("currentState returns INACTIVE for never-ticked rule")
  void currentStateForUnknownRuleIsInactive() {
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState("never-seen"));
    // No side effect from the query.
    assertFalse(scheduler.tick(STANDARD_RULE, EV_FALSE).isPresent());
  }

  // ============================================================
  // Tempdoc 600 Design B — indeterminate "freeze, don't advance"
  // ============================================================

  @Test
  @DisplayName("indeterminate is a no-op (no transition, state untouched) from INACTIVE")
  void indeterminateNoOpFromInactive() {
    Optional<DwellTimeScheduler.Transition> t =
        scheduler.tick(STANDARD_RULE, PredicateOutcome.indeterminate("no samples"));
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.INACTIVE, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("indeterminate does NOT reset PENDING (unlike false)")
  void indeterminateHoldsPending() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
    // A `false` here would reset to INACTIVE; indeterminate must hold PENDING.
    Optional<DwellTimeScheduler.Transition> t =
        scheduler.tick(STANDARD_RULE, PredicateOutcome.indeterminate("no samples"));
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.PENDING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("indeterminate does NOT start the grace period from FIRING")
  void indeterminateHoldsFiring() {
    fireImmediate(); // → FIRING
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
    // A `false` here would move to KEEP_FIRING; indeterminate must hold FIRING.
    Optional<DwellTimeScheduler.Transition> t =
        scheduler.tick(STANDARD_RULE, PredicateOutcome.indeterminate("no samples"));
    assertTrue(t.isEmpty());
    assertEquals(DwellTimeScheduler.State.FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  @Test
  @DisplayName("indeterminate never RESOLVES a firing rule, even past the grace window")
  void indeterminateNeverResolves() {
    fireImmediate(); // → FIRING
    scheduler.tick(STANDARD_RULE, EV_FALSE); // → KEEP_FIRING (grace = +30s)
    assertEquals(
        DwellTimeScheduler.State.KEEP_FIRING, scheduler.currentState(STANDARD_RULE.name()));
    // Advance well past grace, but only blind ticks arrive: must NOT resolve (no false-healthy).
    clock.advance(Duration.ofSeconds(120));
    Optional<DwellTimeScheduler.Transition> t =
        scheduler.tick(STANDARD_RULE, PredicateOutcome.indeterminate("no samples"));
    assertTrue(t.isEmpty());
    assertEquals(
        DwellTimeScheduler.State.KEEP_FIRING, scheduler.currentState(STANDARD_RULE.name()));
  }

  // ============================================================
  // Helpers
  // ============================================================

  /** Drives the standard rule from INACTIVE to FIRING. */
  private void fireImmediate() {
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → PENDING
    clock.advance(Duration.ofSeconds(60));
    scheduler.tick(STANDARD_RULE, EV_TRUE); // → FIRING
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      this.now = now.plus(d);
    }

    void setTo(Instant t) {
      this.now = t;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
