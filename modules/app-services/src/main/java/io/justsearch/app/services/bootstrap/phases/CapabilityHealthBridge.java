/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.RecoveryContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: capability-health → condition-store bridge. Listens to
 * WorkerCapability / InferenceCapability transitions and pushes assertions/clears into
 * ConditionStore so SSE subscribers get immediate updates rather than waiting for
 * {@code /api/status} polls. Extracted from {@code HeadAssembly} (~70 LOC).
 */
public final class CapabilityHealthBridge {

  private static final Logger log = LoggerFactory.getLogger(CapabilityHealthBridge.class);

  private CapabilityHealthBridge() {}

  /**
   * Occurrence IDs this bridge emits (tempdoc 627) — the producer-set declaration the
   * {@code HealthEventEmitCoverageTest} reconciles against the canonical catalog.
   */
  public static java.util.Set<String> emittableIds() {
    return java.util.Set.of("worker.restart-attempted", "worker.recovered");
  }

  /** Wire WorkerCapability + InferenceCapability listeners to the condition store. */
  public static void wireListeners(
      WorkerCapability worker,
      InferenceCapability inference,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      OccurrenceLog occurrenceLog) {
    Clock clock = Clock.systemUTC();
    worker.addListener((prev, next) -> {
      pushCondition(
          "worker.capability", "worker", next, worker.pendingReason(), Severity.WARNING,
          conditionStore, changeRegistry, headSource, clock);
      // Tempdoc 627: narrate the supervised-recovery loop as one-shot occurrences in the RECENT EVENTS
      // stream (the timeline) — distinct from the persistent condition above. The terminal give-up
      // (→ DEGRADED + restart_exhausted) is left to the condition; only the transient milestones emit.
      // Tempdoc 627 (N2): the supervision bridge parked the forensic context on the capability just
      // before this (synchronous) transition fired, so read it for the occurrence attributes.
      emitRecoveryOccurrence(
          prev, next, worker.lastRecoveryContext(), occurrenceLog, changeRegistry, headSource, clock);
    });
    inference.addListener((prev, next) -> {
      if (next == CapabilityHealth.PENDING) {
        return;
      }
      pushCondition(
          "inference.capability", "inference", next, inference.pendingReason(), Severity.INFO,
          conditionStore, changeRegistry, headSource, clock);
    });

    // Tempdoc 627 Deliverable 10: replay current state on wire (race-safe self-seed). With one
    // shared WorkerCapability the supervisor (async worker-start) may have already driven it past
    // PENDING before this listener was added — the deleted HeadAssembly mirror's synchronous
    // initial-copy used to cover that; the bridge now seeds itself. addListener-THEN-replay is
    // safe: transition() sets the volatile health BEFORE firing listeners, so a fired listener
    // implies health() already reflects it (no stale regress); a same-state re-push is an
    // idempotent UNCHANGED no-op. Mirrors the inference PENDING skip above.
    if (worker.health() != CapabilityHealth.PENDING) {
      pushCondition(
          "worker.capability", "worker", worker.health(), worker.pendingReason(), Severity.WARNING,
          conditionStore, changeRegistry, headSource, clock);
    }
    if (inference.health() != CapabilityHealth.PENDING) {
      pushCondition(
          "inference.capability", "inference", inference.health(), inference.pendingReason(),
          Severity.INFO, conditionStore, changeRegistry, headSource, clock);
    }
  }

  private static void pushCondition(
      String condId,
      String subject,
      CapabilityHealth health,
      String reason,
      Severity severity,
      ConditionStore conditionStore,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Clock clock) {
    try {
      if (health == CapabilityHealth.READY) {
        conditionStore
            .clear(condId, subject)
            .ifPresent(removed ->
                changeRegistry.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, removed));
      } else {
        AssertedCondition condition = new AssertedCondition(
            subject,
            ConditionStatus.TRUE,
            toPascalCase(health.name()),
            Instant.now(clock),
            Optional.ofNullable(reason),
            Optional.empty(),
            List.of());
        HealthEvent event = new HealthEvent(
            condId, Instant.now(clock), headSource, severity, Optional.of(condId), condition);
        ConditionStore.Transition transition = conditionStore.upsert(event);
        if (transition != ConditionStore.Transition.UNCHANGED) {
          changeRegistry.broadcast(
              transition == ConditionStore.Transition.ADDED
                  ? HealthEventChangeRegistry.Kind.CONDITION_ADDED
                  : HealthEventChangeRegistry.Kind.CONDITION_MODIFIED,
              event);
        }
      }
    } catch (Exception e) {
      log.debug("Failed to push capability condition for {}: {}", condId, e.getMessage());
    }
  }

  /**
   * Tempdoc 627: emit supervised-recovery milestones as one-shot occurrences (the RECENT EVENTS
   * timeline). Keyed off the worker capability transitions this bridge already observes:
   * <ul>
   *   <li>→ RECOVERING (a restart is in flight) ⇒ {@code worker.restart-attempted} (fires once per
   *       recovery episode — repeated attempts re-enter RECOVERING as a same-state no-op);</li>
   *   <li>RECOVERING → READY (it came back) ⇒ {@code worker.recovered} (the positive event).</li>
   * </ul>
   * Best-effort; never throws into the listener.
   */
  private static void emitRecoveryOccurrence(
      CapabilityHealth prev,
      CapabilityHealth next,
      RecoveryContext ctx,
      OccurrenceLog occurrenceLog,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Clock clock) {
    try {
      String id = null;
      if (next == CapabilityHealth.RECOVERING && prev != CapabilityHealth.RECOVERING) {
        id = "worker.restart-attempted";
      } else if (next == CapabilityHealth.READY && prev == CapabilityHealth.RECOVERING) {
        id = "worker.recovered";
      }
      if (id == null) {
        return;
      }
      // Tempdoc 627 (N2): attach the forensic context the supervisor computed (which attempt, why).
      // restart-attempted carries the full context; recovered carries which attempt it came back on.
      Map<String, Object> attributes = new LinkedHashMap<>();
      if (ctx != null) {
        if ("worker.restart-attempted".equals(id)) {
          attributes.put("attempt", ctx.attempt());
          attributes.put("faultKind", ctx.faultKind());
          attributes.put("backoffMs", ctx.backoffMs());
        } else {
          attributes.put("recoveredAfterAttempts", ctx.attempt());
        }
      }
      HealthEvent event = new HealthEvent(
          id,
          clock.instant(),
          headSource,
          Severity.INFO,
          Optional.of("health-events." + id + ".message"),
          new LifecycleEvent(attributes, Optional.empty()));
      occurrenceLog.append(event);
      changeRegistry.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    } catch (Exception e) {
      log.debug("Failed to emit recovery occurrence ({}→{}): {}", prev, next, e.getMessage());
    }
  }

  private static String toPascalCase(String upper) {
    if (upper == null || upper.isEmpty()) {
      return upper;
    }
    return upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
  }
}
