/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Tempdoc 672 follow-up: the Head-side counterpart to {@code LoopPacingPolicy} (worker-services),
 * which already gates the Worker's own embedding/NER/SPLADE backfill on the same three-signal
 * shape (tempdoc 630). {@code LoopPacingPolicy} cannot be imported here directly — it lives in
 * the {@code worker-services} module (Lucene-adjacent; Hard Invariant: Head never touches
 * Lucene) — so this is a sibling with the same decision shape, not a shared class. Tempdoc 630
 * §A already named VDU as a workload this arbitration was meant to cover; it could not be built
 * until tempdoc 672's own fix made VDU triggerable at all.
 *
 * <p>Signals, each already existing elsewhere and reused here (no new sensing):
 *
 * <ul>
 *   <li><b>Recent activity</b> — {@code KnowledgeServerBootstrap.msSinceLastUserActivity}, a
 *       Head-local mirror of the same real search/suggest/folder-listing activity signal already
 *       fed to the Worker's own {@code isUserActive()} gate.
 *   <li><b>Energy intent</b> — {@code EnergyState.reduced()} (tempdoc 630), already Head-native.
 *   <li><b>LLM exclusivity</b> — {@code inferenceManager.isOnline()}, used only by {@link
 *       #shouldTrigger}, checked <i>before</i> starting a batch (not during VDU's own run, which
 *       legitimately switches Online itself and would make this signal meaninglessly always-true
 *       mid-batch — see {@link #shouldInterrupt}'s javadoc). A coarse, mode-level proxy — the
 *       same precision level the Worker's own {@code isMainGpuActive()} gate already uses in
 *       production, not a per-request counter. Correctly covers an active interactive chat
 *       session, since chat keeps the LLM in Online mode for its duration.
 * </ul>
 */
public final class VduPacingPolicy {

  /** Idle window before auto-triggering offline processing. Deliberately conservative. */
  public static final long DEFAULT_IDLE_THRESHOLD_MS = 5 * 60 * 1000L;

  private VduPacingPolicy() {}

  /**
   * Whether it's currently a good time to auto-trigger {@code
   * OfflineCoordinator.startOfflineProcessing()}. Mirrors {@code
   * LoopPacingPolicy.shouldRunBackfill}'s energy-first-then-exclusivity ordering.
   */
  public static boolean shouldTrigger(long msSinceLastActivity, boolean energyReduced, boolean llmOnline) {
    if (energyReduced) {
      return false; // power: defer regardless of idle state
    }
    if (llmOnline) {
      return false; // LLM already claimed (interactive chat, or otherwise online)
    }
    return msSinceLastActivity >= DEFAULT_IDLE_THRESHOLD_MS;
  }

  /**
   * Whether an in-progress batch should stop early because the user just became active.
   * Mirrors {@code LoopPacingPolicy.shouldInterruptBackfill}'s cooperative-checkpoint shape —
   * checked between units of work, not enforced via locking.
   *
   * <p><b>Deliberately does NOT take an {@code llmOnline} signal</b> — unlike {@link
   * #shouldTrigger}, where "LLM already online" means "someone else claimed it, don't start."
   * Once a VDU batch is running, the LLM is legitimately Online *because this batch itself put it
   * there* ({@code enterVduMode()} requires {@code Mode.ONLINE} as a precondition and doesn't
   * change {@code currentMode()}, only a {@code vduMode} flag within it) — so {@code isOnline()}
   * is unconditionally true for the batch's entire duration and would self-interrupt on the very
   * first checkpoint if included here. Caught live: a real batch logged "interrupted... leaving 3
   * docs PENDING" immediately after entering VDU mode, before processing a single document, when
   * this method's first version reused {@code shouldTrigger}'s three-signal composition verbatim.
   */
  public static boolean shouldInterrupt(long msSinceLastActivity, boolean energyReduced) {
    return msSinceLastActivity < DEFAULT_IDLE_THRESHOLD_MS || energyReduced;
  }
}
