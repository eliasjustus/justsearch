/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * Why a model package was not installed — the typed classification beside the prose
 * {@code skipReason} (tempdoc 840 Phase 2).
 *
 * <p>The prose stays for display; this is what logic reads. Classifying by parsing a human-readable
 * reason string is the prose-as-classification defect Phase 0 removed elsewhere, and it is exactly
 * the kind that survives a reworded message.
 *
 * <p>Produced once, by {@link InstallPlanner}, on the {@link InstallPlan.SkippedPackage} it emits;
 * carried unchanged into {@link InstallContract.InstalledModel}. The planner is the only authority
 * for the decision, so the contract writer never re-derives it.
 *
 * <p>There is deliberately no {@code POLICY} value. Administrator policy does not skip a package —
 * {@code policyBlocksDownloads()} fails the whole install with {@code DOWNLOADS_DISABLED}, so no
 * producer could ever emit it. A declared-but-unproduced value reads as wired when it is not; the
 * repo gates that phantom-value class elsewhere (tempdoc 837). Add it when something skips for it.
 */
public enum SkipCause {
  /** The machine cannot run it: no CUDA, or not enough VRAM for the GGUF floor. */
  HARDWARE,
  /** The active {@link InstallIntent} does not want this package's capability tier. */
  INTENT,
  /** The user declined this component (only possible for a {@link Necessity#userDeclinable} one). */
  USER_DECLINED,
  /**
   * The package exists for development stacks only ({@link ModelPackage#devOnly}, tempdoc 842) and
   * is never part of a user install plan — independent of intent, hardware and user choice.
   */
  DEV_ONLY
}
