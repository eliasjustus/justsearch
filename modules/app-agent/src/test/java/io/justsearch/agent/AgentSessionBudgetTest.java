package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 577 Ext III — the budget accounting facets the accountability record projects: the
 * run-cumulative {@link AgentSession#totalTokens()} carried on {@code AgentBudgetUpdate} (the §2.9
 * V4 ceiling fix) and the {@link AgentSession#addBudget(int)} raise-budget remedy.
 *
 * <p>Tempdoc 577 §2.12 Move 2 — also the held budget GATE (createBudgetGate / resolveBudgetGate /
 * budgetGateHeld): the budget analogue of an approval gate, parked and resolved as one decision.
 */
final class AgentSessionBudgetTest {

  private static AgentSession session(int budget) {
    return new AgentSession(List.of(Map.of("role", "user", "content", "q")), budget);
  }

  @Test
  @DisplayName("totalTokens is run-cumulative while budgetRemaining decrements — ceiling = total + remaining")
  void cumulativeInvariantHoldsAcrossIterations() {
    var s = session(6000);
    s.recordUsage(1000, 500); // iteration 1
    s.recordUsage(2000, 800); // iteration 2
    assertEquals(4300, s.totalTokens());
    assertEquals(1700, s.budgetRemaining());
    // The Ext III invariant the FE ceiling derivation relies on:
    assertEquals(6000, s.totalTokens() + s.budgetRemaining());
  }

  @Test
  @DisplayName("the invariant survives an overrun (remaining goes negative, total keeps counting)")
  void invariantSurvivesOverrun() {
    var s = session(1000);
    s.recordUsage(900, 807);
    assertEquals(1707, s.totalTokens());
    assertEquals(-707, s.budgetRemaining());
    assertEquals(1000, s.totalTokens() + s.budgetRemaining());
  }

  @Test
  @DisplayName("addBudget raises remaining (the raise-budget remedy); non-positive grants are ignored")
  void addBudgetRaisesRemaining() {
    var s = session(1000);
    s.recordUsage(900, 807); // over budget by 707
    s.addBudget(4096);
    assertEquals(3389, s.budgetRemaining());
    s.addBudget(0);
    s.addBudget(-50);
    assertEquals(3389, s.budgetRemaining(), "non-positive grants are no-ops");
    // The ceiling honestly grows with the grant: total + remaining = 1000 + 4096.
    assertEquals(5096, s.totalTokens() + s.budgetRemaining());
  }

  // --- The held budget gate (tempdoc 577 §2.12 Move 2) ---

  @Test
  @DisplayName("a fresh session holds no budget gate; createBudgetGate parks it")
  void createBudgetGateParksTheRun() {
    var s = session(1000);
    assertFalse(s.budgetGateHeld(), "no gate before the boundary");
    var gate = s.createBudgetGate();
    assertTrue(s.budgetGateHeld(), "the run is parked once the gate is created");
    assertFalse(gate.isDone(), "the future is unresolved while parked");
  }

  @Test
  @DisplayName("resolveBudgetGate completes the held future with the human's decision")
  void resolveBudgetGateCompletesTheFuture()
      throws InterruptedException, ExecutionException, TimeoutException {
    var s = session(1000);
    var gate = s.createBudgetGate();
    assertTrue(
        s.resolveBudgetGate(AgentSession.BudgetGateDecision.CONTINUE),
        "resolving a held gate returns true");
    assertEquals(AgentSession.BudgetGateDecision.CONTINUE, gate.get(1, TimeUnit.SECONDS));
    assertFalse(s.budgetGateHeld(), "the gate is no longer held after resolution");
  }

  @Test
  @DisplayName("resolveBudgetGate on an unparked run returns false (the endpoint 404 case)")
  void resolveBudgetGateWithoutAGateReturnsFalse() {
    var s = session(1000);
    assertFalse(
        s.resolveBudgetGate(AgentSession.BudgetGateDecision.STOP),
        "no held gate ⇒ false ⇒ the decision endpoint surfaces 404");
  }

  @Test
  @DisplayName("clearBudgetGate releases the gate (the loop's timeout path)")
  void clearBudgetGateReleasesIt() {
    var s = session(1000);
    s.createBudgetGate();
    assertTrue(s.budgetGateHeld());
    s.clearBudgetGate();
    assertFalse(s.budgetGateHeld(), "cleared ⇒ a later resolve is a no-op 404");
    assertFalse(s.resolveBudgetGate(AgentSession.BudgetGateDecision.FINALIZE));
  }

  // --- The held context gate (tempdoc 577 §2.14 Root II #14) ---

  private static AgentSession sessionWith(List<Map<String, Object>> messages) {
    return new AgentSession(messages, 8192);
  }

  @Test
  @DisplayName("createContextGate parks the run and marks it fired (park at most once)")
  void createContextGateParksAndMarksFired() {
    var s = session(8192);
    assertFalse(s.contextGateHeld());
    assertFalse(s.contextGateFired());
    var gate = s.createContextGate();
    assertTrue(s.contextGateHeld(), "parked once created");
    assertTrue(s.contextGateFired(), "fired-once flag set so the loop never re-parks");
    assertFalse(gate.isDone());
  }

  @Test
  @DisplayName("resolveContextGate completes the held future; unparked ⇒ false (404)")
  void resolveContextGateCompletesTheFuture()
      throws InterruptedException, ExecutionException, TimeoutException {
    var s = session(8192);
    var gate = s.createContextGate();
    assertTrue(s.resolveContextGate(AgentSession.ContextGateDecision.SUMMARIZE));
    assertEquals(AgentSession.ContextGateDecision.SUMMARIZE, gate.get(1, TimeUnit.SECONDS));
    assertFalse(s.contextGateHeld());
    assertFalse(
        s.resolveContextGate(AgentSession.ContextGateDecision.STOP),
        "a second resolve on an unparked run is the 404 case");
  }

  @Test
  @DisplayName("compactOlderTurns drops the oldest non-system turns, preserving the system anchor + recent window")
  void compactOlderTurnsKeepsSystemAndRecent() {
    var msgs = new java.util.ArrayList<Map<String, Object>>();
    msgs.add(Map.of("role", "system", "content", "you are an agent"));
    for (int i = 0; i < 10; i++) {
      msgs.add(Map.of("role", "user", "content", "turn " + i));
    }
    var s = sessionWith(msgs);
    int dropped = s.compactOlderTurns(3); // keep the 3 most-recent
    assertEquals(7, dropped, "11 messages, keep system + 3 recent ⇒ drop 7");
    assertEquals(4, s.messages().size());
    assertEquals("system", s.messages().get(0).get("role"), "the system anchor is preserved");
    assertEquals("turn 9", s.messages().get(3).get("content"), "the newest turn is kept");
  }

  // --- The zero-observer policy (tempdoc 577 §2.14 Root I #13) ---

  @Test
  @DisplayName("a Watch run with no observer PARKS; with an observer it PROCEEDs")
  void zeroObserverPolicyParksWatchWithoutAWatcher() {
    var s = session(8192);
    s.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.WATCH);
    // No observer subscribed to the hub yet ⇒ a Watch run must PARK (don't run unsupervised).
    assertEquals(AgentSession.ZeroObserverPolicy.PARK, s.zeroObserverPolicy());
    // An attached observer ⇒ PROCEED.
    s.eventHub().subscribe(e -> {});
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, s.zeroObserverPolicy());
  }

  @Test
  @DisplayName("Assist / Auto runs PROCEED with no observer (their gates self-arbitrate by posture)")
  void zeroObserverPolicyProceedsForAssistAndAuto() {
    var assist = session(8192);
    assist.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.ASSIST);
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, assist.zeroObserverPolicy());
    var auto = session(8192);
    auto.setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel.AUTO);
    assertEquals(AgentSession.ZeroObserverPolicy.PROCEED, auto.zeroObserverPolicy());
  }

  @Test
  @DisplayName("compactOlderTurns is a no-op when the working set already fits the keep window")
  void compactOlderTurnsNoOpWhenSmall() {
    var s = sessionWith(
        new java.util.ArrayList<>(
            List.of(
                Map.of("role", "system", "content", "s"),
                Map.of("role", "user", "content", "a"))));
    assertEquals(0, s.compactOlderTurns(6), "nothing compactable ⇒ 0 dropped");
    assertEquals(2, s.messages().size(), "messages untouched");
  }
}
