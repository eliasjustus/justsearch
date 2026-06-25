/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.agent.AgentSessionTerminationObserver;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Head-side direct emitter for agent-session terminal-disposition lifecycle events.
 *
 * <p>Per tempdoc 430 §A.10 Phase 7 (rev 3.9 §B.V): the 5 {@code agent.session.*} events are
 * emitted as {@link LifecycleEvent}-bodied {@code HealthEvent} Occurrences (not Conditions —
 * they're instantaneous transitions, not persistent assertions). The emitter implements
 * {@link AgentSessionTerminationObserver} and is wired into {@code AgentLoopService}'s
 * {@code finally{}} block alongside the existing {@code AgentTelemetry.recordSessionEnd}
 * call (single chokepoint at {@code AgentLoopService.java:1217-1228}).
 *
 * <p>{@code TerminalDisposition} → catalog ID + severity mapping (per §A.2 lines 881–885):
 *
 * <table>
 *   <caption>Disposition mapping</caption>
 *   <tr><th>disposition (String)</th><th>catalog ID</th><th>severity</th></tr>
 *   <tr><td>{@code "COMPLETED"}</td><td>{@code agent.session.completed}</td><td>INFO</td></tr>
 *   <tr><td>{@code "CANCELLED"}</td><td>{@code agent.session.cancelled}</td><td>WARNING</td></tr>
 *   <tr><td>{@code "BUDGET_EDGE_FINALIZE"}</td><td>{@code agent.session.budget-edge-finalize}</td><td>WARNING</td></tr>
 *   <tr><td>{@code "MAX_ITERATIONS"}</td><td>{@code agent.session.max-iterations}</td><td>WARNING</td></tr>
 *   <tr><td>{@code "ERRORED"}</td><td>{@code agent.session.errored}</td><td>ERROR</td></tr>
 * </table>
 *
 * <p>Subject parametrization: {@code agent.session/{sessionId}} — carried as a body attribute
 * on {@link LifecycleEvent} (Occurrences don't index into {@code ConditionStore}; the subject
 * is metadata, not a store key).
 *
 * <p>Null-attribute discipline (rev 3.9 §B.V.5): when {@code errorCode} or {@code cancelTrigger}
 * is null, the corresponding key is <strong>absent</strong> from {@code body.attributes} (not
 * present with literal value {@code "null"}). Same defect class as rev-3.6 §B.S #1 — silent
 * wire-format wrong-ness that the FE would render literally.
 *
 * <p>WARN-once dedup (rev 3.9 §B.V.6): unmapped {@code disposition} strings (a future
 * {@code TerminalDisposition} value not yet in the dispatch table) WARN-log once per startup
 * via {@link #warnedDispositions}, then skip emission. Inherited from rev-3.6 §B.S #4.
 *
 * <p>Thread safety: {@link #onSessionTerminated} is {@code synchronized}. Sessions terminate
 * at low frequency relative to lock-contention concerns; the lock guards the WARN-dedup set
 * and the broadcast-after-append ordering.
 */
public final class HeadHealthEventsEmitter implements AgentSessionTerminationObserver {

  private static final Logger log = LoggerFactory.getLogger(HeadHealthEventsEmitter.class);

  /** Resolved catalog ID + severity for a TerminalDisposition. */
  private record IdAndSeverity(String id, Severity severity) {}

  private static final Map<String, IdAndSeverity> AGENT_DISPOSITIONS;

  static {
    Map<String, IdAndSeverity> m = new LinkedHashMap<>();
    m.put("COMPLETED", new IdAndSeverity("agent.session.completed", Severity.INFO));
    m.put("CANCELLED", new IdAndSeverity("agent.session.cancelled", Severity.WARNING));
    m.put(
        "BUDGET_EDGE_FINALIZE",
        new IdAndSeverity("agent.session.budget-edge-finalize", Severity.WARNING));
    m.put(
        "MAX_ITERATIONS", new IdAndSeverity("agent.session.max-iterations", Severity.WARNING));
    m.put("ERRORED", new IdAndSeverity("agent.session.errored", Severity.ERROR));
    AGENT_DISPOSITIONS = Map.copyOf(m);
  }

  /**
   * Returns the catalog IDs this emitter can produce, derived from {@link
   * #AGENT_DISPOSITIONS}.
   *
   * <p>Per tempdoc 430 §A.10 Phase 10 + rev 3.15 §B.AB.1: exposed for {@code
   * HealthEventEmitCoverageTest} to assert every catalog ID in §A.2 has an emit site.
   */
  public static Set<String> emittableIds() {
    return AGENT_DISPOSITIONS.values().stream()
        .map(IdAndSeverity::id)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private final OccurrenceLog occurrences;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;

  /** WARN-once dedup for unmapped TerminalDisposition values (per §B.V.6). */
  private final Set<String> warnedDispositions = ConcurrentHashMap.newKeySet();

  public HeadHealthEventsEmitter(
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock) {
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized void onSessionTerminated(
      String sessionId,
      String disposition,
      String errorCode,
      String cancelTrigger,
      long durationMs,
      int iterationsUsed,
      int toolCallsExecuted,
      int contextSizeBytes) {
    if (sessionId == null || disposition == null) {
      return; // Defensive — both are non-null at the AgentLoopService chokepoint.
    }
    IdAndSeverity target = AGENT_DISPOSITIONS.get(disposition);
    if (target == null) {
      if (warnedDispositions.add(disposition)) {
        log.warn(
            "HeadHealthEventsEmitter: unmapped TerminalDisposition '{}'; emission skipped",
            disposition);
      }
      return;
    }
    // Build attributes with null-attribute discipline (§B.V.5): omit null fields entirely.
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("sessionId", sessionId);
    attributes.put("subject", "agent.session/" + sessionId);
    attributes.put("disposition", disposition);
    if (errorCode != null) {
      attributes.put("errorCode", errorCode);
    }
    if (cancelTrigger != null) {
      attributes.put("cancelTrigger", cancelTrigger);
    }
    attributes.put("durationMs", durationMs);
    attributes.put("iterationsUsed", iterationsUsed);
    attributes.put("toolCallsExecuted", toolCallsExecuted);
    attributes.put("contextSizeBytes", contextSizeBytes);

    HealthEvent event =
        new HealthEvent(
            target.id(),
            clock.instant(),
            source,
            target.severity(),
            Optional.of("health-events." + target.id() + ".message"),
            new LifecycleEvent(attributes, Optional.empty()));
    occurrences.append(event);
    changes.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
  }
}
