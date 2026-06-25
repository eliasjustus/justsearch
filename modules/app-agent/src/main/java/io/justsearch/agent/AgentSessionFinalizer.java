/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-of-run session finalization (tempdoc 240 W5-epilogue — extracted from
 * {@code AgentLoopService}'s {@code runAgent} finally-block). Given a terminated
 * {@link AgentSession}, fans the end-of-run signals out to the three sinks: the
 * {@link AgentTelemetry} metric family (tempdoc 415), the
 * {@link AgentSessionTerminationObserver} health-event Occurrence (tempdoc 430
 * Phase 7), and the {@link AgentRunStore} typed termination reason. Defaults a
 * missing {@code markTerminated} to ERRORED/INTERNAL_ERROR with a loud ERROR log
 * (tempdoc 415 F6) — a mis-tagged metric is a bug, not an expected condition.
 */
final class AgentSessionFinalizer {

  private static final Logger LOG = LoggerFactory.getLogger(AgentSessionFinalizer.class);

  private final AgentTelemetry agentTelemetry;
  private final AgentSessionTerminationObserver terminationObserver;
  private final AgentRunStore runStore;

  AgentSessionFinalizer(
      AgentTelemetry agentTelemetry,
      AgentSessionTerminationObserver terminationObserver,
      AgentRunStore runStore) {
    this.agentTelemetry = agentTelemetry;
    this.terminationObserver = terminationObserver;
    this.runStore = runStore;
  }

  /**
   * Emits the centralized session-end signals. Call sites mark the session via
   * {@code session.markTerminated(...)} before returning; this reads the typed reason and fans it
   * out. Does NOT remove the session from the loop's active map — that stays with the owner.
   */
  void emitSessionEnd(String sessionId, AgentSession session) {
    if (!session.isTerminated()) {
      // Tempdoc 415 F6: ERROR not WARN — missing markTerminated produces a mis-tagged
      // metric, which is a bug, not an expected runtime condition. Surface loudly so
      // dashboards / log-tail tooling see it.
      LOG.error(
          "Session {} reached finally{{}} without markTerminated; defaulting to ERRORED/INTERNAL_ERROR",
          sessionId);
      session.markTerminated(TerminalDisposition.ERRORED, AgentErrorCode.INTERNAL_ERROR, null);
    }
    agentTelemetry.recordSessionEnd(
        session.disposition(),
        session.terminationCode(),
        session.cancelTrigger(),
        session.durationMs(),
        session.contextSizeBytes(),
        session.iterationsUsed(),
        session.toolCallsExecuted());
    // Tempdoc 430 Phase 7: emit agent.session.* lifecycle Occurrence to the health-event
    // substrate, alongside the existing AgentTelemetry counter emit. Defaults to NOOP when
    // the back-compat constructor is used (no observer wired).
    AgentErrorCode termCode = session.terminationCode();
    CancelTrigger trigger = session.cancelTrigger();
    terminationObserver.onSessionTerminated(
        sessionId,
        session.disposition().name(),
        termCode != null ? termCode.name() : null,
        trigger != null ? trigger.name() : null,
        session.durationMs(),
        session.iterationsUsed(),
        session.toolCallsExecuted(),
        session.contextSizeBytes());
    runStore.setTerminationReason(
        sessionId,
        new TerminationReason(
            session.disposition(), session.terminationCode(), session.cancelTrigger()));
  }
}
