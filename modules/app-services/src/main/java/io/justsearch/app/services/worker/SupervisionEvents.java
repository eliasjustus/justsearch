/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Supervision lifecycle callback for the Worker process (tempdoc 627). The {@link WorkerSpawner}
 * fires these as it drives the recovery loop; {@code KnowledgeServerBootstrap} installs an
 * implementation that bridges them to {@code WorkerCapability} transitions, closing the
 * observation→actuation loop. This mirrors the Brain's {@code goOfflineFromMaxCrashes} /
 * {@code goOfflineFromExternalFailure} callbacks (installed by {@code InferenceLifecycleManager} and
 * bridged to {@code InferenceCapability} by {@code InferenceCapabilityWiring}).
 *
 * <p>All methods default to no-op so the spawner can run standalone (tests) without a bridge.
 */
public interface SupervisionEvents {

  /** A no-op bridge for standalone / test use. */
  SupervisionEvents NOOP = new SupervisionEvents() {};

  /**
   * A supervised restart attempt has begun (capability → RECOVERING). {@code ctx} (tempdoc 627 N2)
   * carries the attempt number, fault kind (hang vs death) and backoff the supervisor computed, so the
   * bridge can attach them as forensic attributes on the {@code worker.restart-attempted} occurrence.
   */
  default void onRecovering(String reason, RecoveryContext ctx) {}

  /** A supervised restart succeeded and the worker is serving again (capability → READY path). */
  default void onRecovered() {}

  /**
   * The restart budget is exhausted; recovery has given up (terminal state). The bridge transitions
   * the capability to a terminal, reason-coded state ({@code DEGRADED} + {@code WORKER_RESTART_EXHAUSTED}).
   */
  default void onGaveUp(String reason) {}
}
