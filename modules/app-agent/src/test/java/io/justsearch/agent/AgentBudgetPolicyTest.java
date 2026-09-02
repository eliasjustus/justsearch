/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 859 §D §3.3 T1 — {@link AgentBudgetPolicy}, asserted by CONSEQUENCE.
 *
 * <p><b>What this test is forbidden to do.</b> It must not assert {@code initialBudget(rung, n) ==
 * MULTIPLIER[rung] * n}. That re-derives the implementation from the same constant it is checking:
 * it would pass for 2x, for 5x, for 1x, for any value at all, so it would prove nothing about
 * whether the rungs are SIZED RIGHT — which is the entire question this slice exists to answer.
 *
 * <p><b>What it does instead.</b> It runs synthetic spend traces through a model of the loop's own
 * gate condition and asserts what each rung MEANS: how far a run gets before the budget stops it.
 * Change a multiplier and these fail, because the outcome changes — not because a literal moved.
 *
 * <p>Two traces are used.
 *
 * <ul>
 *   <li>The <b>fitted burn</b> — {@code prompt(i) = B + (i-1)R}, {@code B} 900, {@code R} 1100,
 *       {@code C} 150 — fitted in 859 §1 to the one live datapoint (859 §7: a 3840-token budget
 *       exhausted after two tool calls on a read-three-files task at {@code n_ctx} 4096). It stands
 *       in for a short realistic run; it is NOT measured fact beyond that single run.
 *   <li>The <b>measured burn</b> ({@link #MEASURED_BURN_2026_08_25}) — the per-iteration spend of a
 *       real 10-iteration delegate run, recorded by live leg L1 on the compact profile. This is the
 *       trace {@link AgentBudgetPolicy#STANDARD_MULTIPLIER} is now sized against, and the one that
 *       fails at the pre-L1 value of 5.
 *   <li>The <b>structural maximum</b> — every call charges the most it possibly can, {@code n_ctx +
 *       maxTokens}. Nothing can burn faster, whatever the burn shape, so a rung that survives this
 *       survives everything.
 * </ul>
 */
final class AgentBudgetPolicyTest {

  /** {@link #gateIteration} returns this when the run finished without the budget stopping it. */
  private static final int NO_GATE = -1;

  /** The loop's own per-call completion cap ({@link AgentContextBudgets} default). */
  private static final int MAX_COMPLETION_TOKENS = 1024;

  /** The loop's iteration ceiling as the FE dispatches it (AgentSessionController). */
  private static final int MAX_ITERATIONS = 10;

  private static final int COMPACT_N_CTX = 4096;
  private static final int LARGE_N_CTX = 32_768;

  /* ── The measured-burn trace (859 §1) ─────────────────────────────────────────────────────── */

  private static final int BURN_BASE_PROMPT = 900; // system prompt + tool schemas
  private static final int BURN_PER_TOOL_RESULT = 1100; // one tool result added to every later prompt
  private static final int BURN_COMPLETION = 150;

  private static final IntUnaryOperator MEASURED_BURN =
      i -> BURN_BASE_PROMPT + (i - 1) * BURN_PER_TOOL_RESULT;

  /* ── The measured 10-iteration burn (live leg L1, 2026-08-25) ─────────────────────────────── */

  /**
   * What a real delegate run actually spent, iteration by iteration, on the compact chat profile
   * ({@code n_ctx} 4096). Recorded live on 2026-08-25 — not fitted, not extrapolated. It totals
   * {@code 32,192} tokens, i.e. {@code 7.9x n_ctx}, which is the number Standard is sized against.
   */
  private static final int[] MEASURED_BURN_2026_08_25 = {
    1785, 2905, 3219, 3334, 3446, 3566, 3657, 3273, 3584, 3423
  };

  /**
   * The same gate model as {@link #gateIteration}, driven by the recorded per-iteration TOTAL spend
   * (prompt + completion, as the provider reported it). The loop gates when the next prompt meets or
   * exceeds what is left; the recorded figure is charged in full when the call goes ahead.
   */
  private static int gateOnMeasuredRun(int budget) {
    return gateIteration(
        budget, i -> MEASURED_BURN_2026_08_25[i - 1], 0, MEASURED_BURN_2026_08_25.length);
  }

  /**
   * A model of the loop's between-step budget check ({@code AgentStepRunner}): before each call the
   * loop projects the next prompt and gates when that projection meets or exceeds what is left;
   * otherwise the call happens and is charged its prompt PLUS its completion.
   *
   * @return the 1-based iteration the gate fired on, or {@link #NO_GATE} if the run completed
   */
  private static int gateIteration(
      int budget, IntUnaryOperator promptAt, int completionPerCall, int maxIterations) {
    int remaining = budget;
    for (int i = 1; i <= maxIterations; i++) {
      int prompt = promptAt.applyAsInt(i);
      if (prompt >= remaining) {
        return i;
      }
      remaining -= prompt + completionPerCall;
    }
    return NO_GATE;
  }

  /** The structural-maximum trace at a given window: every call charges {@code n_ctx + maxTokens}. */
  private static int gateOnStructuralMaximum(int budget, int nCtx) {
    // The prompt can never exceed n_ctx (that is what the context gate's auto-compaction
    // guarantees), and the completion can never exceed the per-call cap.
    return gateIteration(budget, i -> nCtx, MAX_COMPLETION_TOKENS, MAX_ITERATIONS);
  }

  private static int budgetFor(String effort, int nCtx) {
    return AgentBudgetPolicy.initialBudget(effort, nCtx, false);
  }

  /* ── (a) Thorough: tokens can never be what stops the run ──────────────────────────────────── */

  @Test
  @DisplayName("Thorough — a run spending the STRUCTURAL MAXIMUM every call never reaches the gate")
  void thoroughClearsTheStructuralBoundAtEveryWindow() {
    // This is Thorough's whole meaning: not "a lot of tokens" but "tokens are not the thing that
    // stops this run — the iteration cap is". The trace below burns the most any call physically
    // can, so no burn SHAPE can defeat it; the claim does not rest on the fitted constants above.
    //
    // RETIREMENT CONDITIONS — 15x stops sufficing if any of these change:
    //   * MAX_ITERATIONS is raised above 10 (the bound is maxIterations * (1 + maxTokens/n_ctx));
    //   * agent.maxCompletionTokens is raised above 1024 (raises the per-call ceiling);
    //   * a profile ships with n_ctx < 2048 (the ratio maxTokens/n_ctx blows up).
    // Any of those and this test goes red, which is the point: it is the tripwire, not a formality.
    assertEquals(
        NO_GATE,
        gateOnStructuralMaximum(budgetFor("thorough", COMPACT_N_CTX), COMPACT_N_CTX),
        "Thorough on the compact profile must outlast the worst possible burn for all 10 iterations");
    assertEquals(
        NO_GATE,
        gateOnStructuralMaximum(budgetFor("thorough", LARGE_N_CTX), LARGE_N_CTX),
        "and on a large-context model, where the bound is tighter still (10.3x asymptote)");
  }

  @Test
  @DisplayName("the structural bound is NOT free — Standard and Quick both gate under it")
  void loweRungsDoNotClearTheStructuralBound() {
    // Without this, the assertion above would pass for any absurdly large multiplier and would say
    // nothing about 15 in particular. Ordering by CONSEQUENCE: each rung buys strictly more room.
    int quick = gateOnStructuralMaximum(budgetFor("quick", COMPACT_N_CTX), COMPACT_N_CTX);
    int standard = gateOnStructuralMaximum(budgetFor("standard", COMPACT_N_CTX), COMPACT_N_CTX);
    assertTrue(quick != NO_GATE, "Quick cannot absorb the worst-case burn");
    assertTrue(standard != NO_GATE, "nor can Standard — only Thorough is bound-clearing");
    assertTrue(
        quick < standard,
        "Quick must gate strictly earlier than Standard (got " + quick + " vs " + standard + ")");
  }

  /* ── (b)/(c) Standard finishes a realistic run; Quick is a couple of steps ─────────────────── */

  @Test
  @DisplayName("Standard — the 5-iteration read-three-files burn completes with no gate")
  void standardFinishesTheMeasuredFiveIterationBurn() {
    // 859 §1's worked case: a read-three-files task is ~5 iterations. Standard exists to finish it.
    assertEquals(
        NO_GATE,
        gateIteration(budgetFor("standard", COMPACT_N_CTX), MEASURED_BURN, BURN_COMPLETION, 5),
        "Standard must not gate on the task the live audit watched it fail");
  }

  @Test
  @DisplayName("Standard — the MEASURED 10-iteration run (L1, 2026-08-25) completes with no gate")
  void standardFundsTheMeasuredTenIterationRun() {
    // THE L1 RESIZE, asserted by consequence. The pre-L1 value of 5 gave 20,480 tokens against a
    // recorded 32,192, and the live Standard run duly hit the gate at 102.6% after 8 tool calls
    // with no answer at all. Standard's promise is that a run of ordinary length FINISHES; a rung
    // whose default outcome is "cut short at the budget" is not a default, it is a trap.
    assertEquals(
        NO_GATE,
        gateOnMeasuredRun(budgetFor("standard", COMPACT_N_CTX)),
        "Standard must fund the whole measured run — total spend "
            + java.util.Arrays.stream(MEASURED_BURN_2026_08_25).sum()
            + " against a budget of "
            + budgetFor("standard", COMPACT_N_CTX));
  }

  @Test
  @DisplayName("the measured run is NOT free either — Quick still gates part-way through it")
  void quickDoesNotFundTheMeasuredTenIterationRun() {
    // Without this, the assertion above would pass for any large multiplier and would say nothing
    // about 8 in particular. The ladder must stay ordered by CONSEQUENCE on the same trace.
    int quick = gateOnMeasuredRun(budgetFor("quick", COMPACT_N_CTX));
    assertTrue(quick != NO_GATE, "Quick cannot fund a full 10-iteration run — it is a couple of steps");
    assertTrue(
        quick < MEASURED_BURN_2026_08_25.length,
        "and it must gate strictly before the run's last iteration (got " + quick + ")");
  }

  @Test
  @DisplayName("Quick — the same burn gates after a couple of steps, not zero and not a full run")
  void quickIsACoupleOfStepsOfRoom() {
    int gate = gateIteration(budgetFor("quick", COMPACT_N_CTX), MEASURED_BURN, BURN_COMPLETION, 10);
    assertTrue(gate != NO_GATE, "Quick is the smallest rung — the measured burn must reach its gate");
    assertTrue(
        gate >= 3 && gate <= 4,
        "Quick's copy promises 'a couple of steps when delegated' — it gated at iteration " + gate);
  }

  @Test
  @DisplayName("every rung raises today's allowance — the leash is only INTER-rung")
  void noRungReducesTheOldOneWindowAllowance() {
    // The pre-859 initial budget was `n_ctx - 256` — one cognitive window used as an economic one.
    // Quick is the SMALLEST raise, never a restriction, and that must hold at every window size.
    for (int nCtx : new int[] {2048, COMPACT_N_CTX, 8192, LARGE_N_CTX}) {
      int legacy = nCtx - 256;
      assertTrue(
          budgetFor("quick", nCtx) > legacy,
          "Quick must still be a raise at n_ctx " + nCtx + " (it is the floor of the ladder)");
    }
  }

  /* ── (d) Absent / unknown rung resolves to the Standard OUTCOME ────────────────────────────── */

  @Test
  @DisplayName("an absent, blank or unrecognized rung behaves exactly as Standard does")
  void unknownRungResolvesToTheStandardOutcome() {
    // Pinned from BOTH sides: the same runs Standard finishes must finish, and the same runs
    // Standard cannot absorb must still gate. Comparing the two budgets directly would be the
    // tautology this test is built to avoid.
    for (String rung : new String[] {null, "", "  ", "STANDARD", "Standard", "medium", "turbo"}) {
      assertEquals(
          NO_GATE,
          gateIteration(budgetFor(rung, COMPACT_N_CTX), MEASURED_BURN, BURN_COMPLETION, 5),
          "rung '" + rung + "' must finish the 5-iteration burn, as Standard does");
      assertEquals(
          gateOnStructuralMaximum(budgetFor("standard", COMPACT_N_CTX), COMPACT_N_CTX),
          gateOnStructuralMaximum(budgetFor(rung, COMPACT_N_CTX), COMPACT_N_CTX),
          "rung '" + rung + "' must gate where Standard gates under the worst-case burn");
    }
  }

  /* ── (e) Background runs are pinned to one window ──────────────────────────────────────────── */

  @Test
  @DisplayName("a BACKGROUND run keeps today's one-window behaviour whatever rung it names")
  void backgroundRunsArePinnedToOneWindow() {
    // 859 §D §2.9: a background run bypasses BOTH gates (AgentStepRunner), so it cannot park and
    // cannot be asked anything. A raised budget for it is raised UNSUPERVISED spend with no decision
    // point anywhere — which is why the rung is ignored rather than honoured.
    int background = AgentBudgetPolicy.initialBudget("thorough", COMPACT_N_CTX, true);
    int backgroundGate = gateIteration(background, MEASURED_BURN, BURN_COMPLETION, MAX_ITERATIONS);
    assertEquals(
        3,
        backgroundGate,
        "the measured burn must still stop a background run after two calls — 859 §7's live"
            + " observation, i.e. today's behaviour, deliberately unchanged");
    assertTrue(
        gateIteration(budgetFor("thorough", COMPACT_N_CTX), MEASURED_BURN, BURN_COMPLETION, MAX_ITERATIONS)
            == NO_GATE,
        "while the SAME rung in the foreground does not gate — so the pin is what made the"
            + " difference, not the rung being ignored everywhere");
  }

  @Test
  @DisplayName("a BACKGROUND run keeps the OLD safety margin too — 'unchanged' has to be literal")
  void backgroundRunsKeepThePre859SafetyMargin() {
    // The foreground rungs delete the 256-token margin (the between-step gate does that job
    // properly). A background run must not quietly gain those 256 tokens: §2.9's claim is that its
    // behaviour is UNCHANGED, and the one place the design refuses to move spend is the one place
    // nobody can supervise. This discriminates the margin — a 1x multiplier alone would pass a
    // `budget < foreground` check while being 256 tokens more than today.
    for (int nCtx : new int[] {2048, COMPACT_N_CTX, LARGE_N_CTX}) {
      assertEquals(
          nCtx - 256,
          AgentBudgetPolicy.initialBudget("thorough", nCtx, true),
          "background at n_ctx " + nCtx + " must be exactly the pre-859 `n_ctx - 256`");
    }
    // And it never goes negative on a pathologically small window.
    assertEquals(0, AgentBudgetPolicy.initialBudget(null, 100, true));
  }

  /**
   * Tempdoc 878 §D.8 — the Thorough rung's STRUCTURAL BOUND, computed instead of asserted in prose.
   *
   * <p>{@link AgentBudgetPolicy}'s javadoc derives 15x from {@code maxIterations * (n_ctx +
   * maxTokens)}, and states what the rung therefore MEANS: "tokens can never be what stops this run
   * — the iteration cap is." That derivation is stated in terms of the ITERATION CAP, so the cap and
   * the multiplier are one decision wearing two hats. Nothing enforced the link: raising the FE's
   * cap from 10 to 20 — a one-line edit in a file that knows nothing about budgets — would double
   * the bound to ~25x, silently void the derivation, and turn Thorough into a rung tokens CAN stop,
   * with every existing test still green.
   *
   * <p>878 designed an effort-scaled cap and deliberately did not ship the number, because it is a
   * spend decision. This is the half that does ship: the next agent to change the cap gets a red
   * build naming the multiplier that has to move with it, instead of a rung whose meaning quietly
   * stopped being true.
   *
   * <p>Asserted at the COMPACT window, which is the binding one — the bound as a multiple is
   * {@code maxIterations * (1 + maxTokens/n_ctx)}, largest at the smallest window.
   */
  @Test
  @DisplayName("878 §D.8: THOROUGH_MULTIPLIER still clears the per-run spend bound at the current iteration cap")
  void thoroughMultiplierStillClearsItsStructuralBound() {
    // Tempdoc 883 decision 3 — resolved per call now, not a class-init constant. With no
    // inference handle this is the FALLBACK window (4096), where the derived reserve is
    // min(1024, 4096/4) = 1024, i.e. unchanged — which is why the bound below is still
    // asserted at COMPACT_N_CTX, the same number by a different route.
    int maxCompletionTokens = AgentContextBudgets.forCall(null).completionReserve();
    // spend(run) <= maxIterations * (n_ctx + maxTokens), expressed as a multiple of n_ctx. Kept as a
    // double and compared with >=, so the integer multiplier must CLEAR the exact worst case rather
    // than tie a rounded one. At today's constants the bound is 12.5 against a multiplier of 15, so
    // the guard first bites at a cap of 13 — deliberate slack, not a tight fit.
    double boundAsMultiple =
        (double) DEFAULT_ITERATION_CAP
            * (COMPACT_N_CTX + maxCompletionTokens)
            / COMPACT_N_CTX;

    assertTrue(
        AgentBudgetPolicy.THOROUGH_MULTIPLIER >= boundAsMultiple,
        "THOROUGH_MULTIPLIER is "
            + AgentBudgetPolicy.THOROUGH_MULTIPLIER
            + "x but the structural bound at n_ctx "
            + COMPACT_N_CTX
            + ", maxTokens "
            + maxCompletionTokens
            + " and an iteration cap of "
            + DEFAULT_ITERATION_CAP
            + " is "
            + boundAsMultiple
            + "x. Whoever raised the cap (or the completion cap) has to re-derive the multiplier"
            + " with it — otherwise the Thorough rung stops meaning 'tokens can never be what stops"
            + " this run', which is the only thing that distinguishes it from Standard.");

    // The Standard rung is the deliberate opposite and must NOT clear the bound: it is the rung
    // where tokens genuinely can stop a run. Pinning both directions is what stops a future edit
    // from "fixing" the assertion above by raising every multiplier.
    assertTrue(
        AgentBudgetPolicy.STANDARD_MULTIPLIER < boundAsMultiple,
        "STANDARD_MULTIPLIER must stay UNDER the structural bound — a Standard run that tokens"
            + " cannot stop is a Thorough run with a different name");
  }

  /**
   * The delegate cap the frontend sends today ({@code AgentSessionController.DEFAULT_MAX_ITERATIONS
   * = 10}), and the number the bound above is derived at.
   *
   * <p>It is duplicated here rather than read from the FE because a Java test cannot see a
   * TypeScript constant. That makes this copy exactly the kind of unbound duplicate 878 §T.7 warns
   * about — so the failure message names the coupling explicitly, and a cap change that forgets this
   * file produces a bound that is too LOW, i.e. a test that passes when it should fail. That is the
   * honest limit of this guard: it catches the multiplier moving without the cap, and it catches a
   * cap change made by someone who updated this constant. It cannot catch a cap change made in
   * ignorance of it.
   */
  private static final int DEFAULT_ITERATION_CAP = 10;
}
