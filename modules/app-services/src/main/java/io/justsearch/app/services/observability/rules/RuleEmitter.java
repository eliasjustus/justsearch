/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.health.ThresholdPhase;
import io.justsearch.app.observability.health.ThresholdState;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates rule-engine transitions into {@link HealthEvent} records and pushes them through
 * {@link ConditionStore} + {@link HealthEventChangeRegistry}.
 *
 * <p>Per tempdoc 430 §A.3 + rev 3.11 §B.X.2/§B.X.3: ThresholdState transitions reuse the same
 * Kind discriminators as AssertedCondition transitions on the wire ({@code CONDITION_ADDED} on
 * STARTED_FIRING, {@code CONDITION_REMOVED} on RESOLVED). The wire payload's {@code body.kind:
 * "threshold"} discriminates for the FE renderer.
 */
public final class RuleEmitter {

  private static final Logger log = LoggerFactory.getLogger(RuleEmitter.class);

  private final ConditionStore conditions;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;

  public RuleEmitter(
      ConditionStore conditions,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Emits a {@link DwellTimeScheduler.Transition#STARTED_FIRING} event for the given rule with
   * the supplied magnitudes. The wire payload uses {@link ThresholdPhase#FIRING}.
   */
  public void emitStartedFiring(Rule rule, Map<String, Number> magnitudes) {
    HealthEvent event = buildEvent(rule, ThresholdPhase.FIRING, magnitudes);
    ConditionStore.Transition transition = conditions.upsert(event);
    switch (transition) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        /* no-op: re-emit of identical phase + magnitudes */
      }
    }
  }

  /**
   * Emits a {@link DwellTimeScheduler.Transition#RESOLVED} event for the given rule. The
   * persistent record is cleared from the {@link ConditionStore}; subscribers see a
   * {@code CONDITION_REMOVED} broadcast carrying the prior payload.
   */
  public void emitResolved(Rule rule) {
    Optional<HealthEvent> removed = conditions.clear(rule.emits().id(), rule.emits().subject());
    removed.ifPresent(
        event -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, event));
  }

  /**
   * The stable id for the "this monitor cannot evaluate" observable (tempdoc 600 Design B). It is
   * deliberately DISTINCT from any rule's own emit id, and keyed by {@code (UNOBSERVABLE_ID,
   * rule.emits().subject())} in the {@link ConditionStore} so each blind monitor gets one row that
   * cannot collide with its threshold/condition firing.
   */
  static final String UNOBSERVABLE_ID = "monitor.unobservable";

  /** PascalCase k8s reason for the blind-monitor condition (matches {@code AssertedCondition} regex). */
  private static final String UNOBSERVABLE_REASON = "MetricUnavailable";

  /**
   * Asserts that {@code rule} cannot be evaluated this tick (tempdoc 600 Design B). Emits an
   * {@link AssertedCondition} with {@link ConditionStatus#UNKNOWN} — the wire's existing vocabulary
   * for "observation-failed-or-not-yet-available" — so a blind monitor is a first-class observable
   * rather than a silent collapse to "healthy". INFO severity (diagnostic, not an alarm): a monitor
   * that can't see is not, itself, a system fault.
   *
   * <p>Tempdoc 600 C-2 (Design B): the condition message is a PLAIN, STABLE, check-named string — it
   * names the rule, never the internal metric or a moving window. Stability makes a re-emit dedup to
   * {@link ConditionStore.Transition#UNCHANGED} (no {@code CONDITION_MODIFIED} churn every blind
   * tick); plainness keeps the rendered Health row free of raw identifiers (Nielsen #9). The engineer-
   * facing detail ({@code reason}, carrying the metric name) is logged at DEBUG, not shown to the user.
   */
  public void emitUnobservable(Rule rule, String reason) {
    Objects.requireNonNull(rule, "rule");
    log.debug("Rule '{}' cannot evaluate: {}", rule.name(), reason);
    Instant now = clock.instant();
    HealthEvent event =
        new HealthEvent(
            UNOBSERVABLE_ID,
            now,
            source,
            Severity.INFO,
            Optional.of("health-events." + UNOBSERVABLE_ID + ".message"),
            new AssertedCondition(
                rule.emits().subject(),
                ConditionStatus.UNKNOWN,
                UNOBSERVABLE_REASON,
                now,
                Optional.of("The '" + rule.name() + "' check can't read its data yet."),
                Optional.empty(),
                List.of()));
    ConditionStore.Transition transition = conditions.upsert(event);
    switch (transition) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        /* no-op: rule is still blind for the same reason */
      }
    }
  }

  /**
   * Clears the blind-monitor condition for {@code rule} once it can evaluate again (tempdoc 600
   * Design B). Idempotent: a no-op when no blind condition is asserted, so {@link RuleRunner} can
   * call it on every evaluated tick.
   */
  public void clearUnobservable(Rule rule) {
    Objects.requireNonNull(rule, "rule");
    Optional<HealthEvent> removed = conditions.clear(UNOBSERVABLE_ID, rule.emits().subject());
    removed.ifPresent(
        event -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, event));
  }

  /**
   * Emits a magnitude-update for an already-FIRING rule (used by {@code RuleRunner} when the
   * predicate stays true across ticks but magnitudes have moved). ConditionStore preserves the
   * prior {@code lastTransitionTime} when phase is unchanged (rev 3.11 §B.X.2).
   */
  public void emitFiringMagnitudes(Rule rule, Map<String, Number> magnitudes) {
    HealthEvent event = buildEvent(rule, ThresholdPhase.FIRING, magnitudes);
    ConditionStore.Transition transition = conditions.upsert(event);
    if (transition == ConditionStore.Transition.MODIFIED) {
      changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
    }
    // ADDED would mean the store didn't have a prior FIRING record — possible across process
    // restarts mid-firing or under direct state-machine manipulation in tests. Broadcast anyway.
    if (transition == ConditionStore.Transition.ADDED) {
      changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
    }
  }

  private HealthEvent buildEvent(Rule rule, ThresholdPhase phase, Map<String, Number> magnitudes) {
    Instant now = clock.instant();
    LinkedHashMap<String, Number> mag = new LinkedHashMap<>(magnitudes);
    return new HealthEvent(
        rule.emits().id(),
        now,
        source,
        rule.emits().severity(),
        Optional.of("health-events." + rule.emits().id() + ".message"),
        new ThresholdState(
            rule.emits().subject(),
            phase,
            Map.copyOf(mag),
            now,
            Optional.empty(),
            Optional.empty(), // recovery — populated by RuleSet declarations once they declare default recoveries (447-impl-B widens the type)
            List.of())); // relatedMetrics — slice 3a.1.4 Phase 6 populates for trend-correlated Conditions
  }
}
