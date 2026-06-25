package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AutonomyLevel;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 thesis III — the ONE intent-gate computation. Both the enforcement chokepoint
 * (OperationExecutorImpl) and the Preview endpoint (OperationPreviewController) now READ this
 * single evaluator, so a preview can never disagree with what enforcement does. This test pins the
 * computation — source-tier derivation, the lattice, and the hard-stop override (the F1 scenario,
 * where a preview previously predicted a permissive gate while dispatch DENIED under the engaged
 * hard stop). Because both surfaces call the same method, pinning it here is the agreement guard.
 */
@DisplayName("IntentGateEvaluator — the one intent-gate computation (tempdoc 550 thesis III)")
class IntentGateEvaluatorTest {

  private IntentGateEvaluator evaluator() {
    return new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
  }

  @Test
  @DisplayName("derives source tier from transport; unregistered transport → strictest UNTRUSTED")
  void sourceTier() {
    var e = evaluator();
    assertEquals(SourceTier.TRUSTED, e.sourceTierFor(TransportTag.BUTTON));
    assertEquals(SourceTier.MEDIUM, e.sourceTierFor(TransportTag.URL_BAR));
    assertEquals(SourceTier.UNTRUSTED, e.sourceTierFor(TransportTag.LLM_EMISSION));
    // SCHEDULED is not registered in the core catalog → strictest fallback (Pass-9 commitment 4).
    assertEquals(SourceTier.UNTRUSTED, e.sourceTierFor(TransportTag.SCHEDULED));
  }

  @Test
  @DisplayName("composes the (SourceTier x RiskTier) lattice into the gate")
  void lattice() {
    var e = evaluator();
    // UNTRUSTED (LLM emission): LOW auto-fires; MEDIUM/HIGH escalate to typed confirm.
    assertEquals(
        GateBehavior.AUTO, e.evaluate(RiskTier.LOW, TransportTag.LLM_EMISSION).gateBehavior());
    assertEquals(
        GateBehavior.TYPED_CONFIRM,
        e.evaluate(RiskTier.MEDIUM, TransportTag.LLM_EMISSION).gateBehavior());
    // TRUSTED (user button): MEDIUM auto-fires (the gesture is the confirmation); HIGH typed-confirm.
    assertEquals(GateBehavior.AUTO, e.evaluate(RiskTier.MEDIUM, TransportTag.BUTTON).gateBehavior());
    assertEquals(
        GateBehavior.TYPED_CONFIRM, e.evaluate(RiskTier.HIGH, TransportTag.BUTTON).gateBehavior());
  }

  @Test
  @DisplayName("hard stop engaged DENIES every UNTRUSTED dispatch but leaves user-driven alone (F1)")
  void hardStopOverride() {
    var e = evaluator();
    boolean[] engaged = {false};
    e.setHardStopSignal(() -> engaged[0]);

    // Released: an UNTRUSTED LOW dispatch auto-fires.
    var released = e.evaluate(RiskTier.LOW, TransportTag.LLM_EMISSION);
    assertEquals(GateBehavior.AUTO, released.gateBehavior());
    assertFalse(released.hardStopEngaged());

    engaged[0] = true;
    // Engaged: the SAME UNTRUSTED LOW dispatch is now DENY. This is exactly what the Preview
    // endpoint must also report — it reads this one verdict — so it can't predict a stale AUTO.
    var untrusted = e.evaluate(RiskTier.LOW, TransportTag.LLM_EMISSION);
    assertEquals(GateBehavior.DENY, untrusted.gateBehavior());
    assertTrue(untrusted.hardStopEngaged());

    // User-driven (TRUSTED) dispatch is unaffected by the hard stop; the flag still reports engaged.
    var trusted = e.evaluate(RiskTier.LOW, TransportTag.BUTTON);
    assertEquals(GateBehavior.AUTO, trusted.gateBehavior());
    assertTrue(trusted.hardStopEngaged());
  }

  @Test
  @DisplayName("agentGate — the ONE issuance policy over (risk x autonomy dial); HIGH never auto (561 P-D)")
  void agentGateIssuancePolicy() {
    var e = evaluator();

    // WATCH — review everything: even a read-only call surfaces a lightweight acknowledgement.
    assertEquals(GateBehavior.INLINE_CONFIRM, e.agentGate(RiskTier.LOW, AutonomyLevel.WATCH));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.WATCH));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.HIGH, AutonomyLevel.WATCH));

    // ASSIST (default) — reads auto-run; writes require approval; destructive requires approval.
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.LOW, AutonomyLevel.ASSIST));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.ASSIST));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.HIGH, AutonomyLevel.ASSIST));

    // AUTO — trust the agent for reads and REVERSIBLE writes; destructive STILL requires approval,
    // and (561 §19/C-4) the 2-arg form defaults to irreversible, so a MEDIUM write confirms.
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.LOW, AutonomyLevel.AUTO));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.AUTO));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.HIGH, AutonomyLevel.AUTO));

    // null dial → the safe default (ASSIST).
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.LOW, null));
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, null));
  }

  @Test
  @DisplayName(
      "agentGate — AUTO confirms IRREVERSIBLE MEDIUM writes, auto-fires reversible ones (561 §19/C-4)")
  void agentGateReversibilityFloorUnderAuto() {
    var e = evaluator();

    // AUTO + MEDIUM: a REVERSIBLE write (declares undo / a backend inverse) still auto-fires...
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.AUTO, true));
    // ...but an IRREVERSIBLE write confirms even under AUTO (the hallucinated-write hazard, App. A).
    assertEquals(
        GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.AUTO, false));

    // Reads (LOW) are unaffected by reversibility — they always auto-fire under AUTO.
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.LOW, AutonomyLevel.AUTO, false));
    assertEquals(GateBehavior.AUTO, e.agentGate(RiskTier.LOW, AutonomyLevel.AUTO, true));

    // The HIGH floor dominates regardless of reversibility.
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.HIGH, AutonomyLevel.AUTO, true));

    // WATCH / ASSIST are unchanged — MEDIUM already confirms, so reversibility never relaxes them.
    assertEquals(
        GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.ASSIST, true));
    assertEquals(
        GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.WATCH, true));

    // The 2-arg overload defaults to the safe side (irreversible) → MEDIUM confirms under AUTO.
    assertEquals(GateBehavior.TYPED_CONFIRM, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.AUTO));
  }

  @Test
  @DisplayName("agentGate — an engaged hard stop DENIES regardless of the dial (561 P-D safety floor)")
  void agentGateHardStopDominates() {
    var e = evaluator();
    boolean[] engaged = {true};
    e.setHardStopSignal(() -> engaged[0]);
    // Even AUTO + LOW (the most permissive dial) is DENY while the hard stop is engaged.
    assertEquals(GateBehavior.DENY, e.agentGate(RiskTier.LOW, AutonomyLevel.AUTO));
    assertEquals(GateBehavior.DENY, e.agentGate(RiskTier.MEDIUM, AutonomyLevel.AUTO));
  }
}
