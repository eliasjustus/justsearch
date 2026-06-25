/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Declared supervision policy for the Worker process — the recovery contract's tunable parameters
 * as named data (tempdoc 627). This is the Body's analogue of the Brain's
 * {@code MAX_CRASHES}/{@code CONSECUTIVE_FAILURES_BEFORE_RESTART} constants in
 * {@code LlamaServerOps}, lifted into one value so the recovery policy is legible and so
 * {@link SupervisionDecision} can be a pure, substrate-free function tested in isolation.
 *
 * <p>In Erlang/OTP terms this is the supervisor's restart intensity: at most
 * {@code maxRestartAttempts} restarts before the supervisor gives up, with the counter reset once
 * the child has run stably for {@code stabilityWindowMs} (the rolling-window {@code (MaxR, MaxT)}).
 *
 * @param maxRestartAttempts hard cap on restarts before terminal give-up (OTP {@code intensity})
 * @param baseCooldownMs base backoff before a restart attempt (doubles each attempt)
 * @param maxCooldownMs ceiling on the exponential backoff
 * @param stabilityWindowMs uptime after which a healthy worker resets the restart counter (OTP
 *     {@code period})
 * @param hangUnhealthyThreshold consecutive gRPC-unhealthy polls (process still alive) that count as
 *     a hang requiring a restart — the Kubernetes "liveness probe" threshold for the Worker; mirrors
 *     the Brain's {@code CONSECUTIVE_FAILURES_BEFORE_RESTART}
 */
public record SupervisionPolicy(
    int maxRestartAttempts,
    long baseCooldownMs,
    long maxCooldownMs,
    long stabilityWindowMs,
    int hangUnhealthyThreshold) {

  /** Default restart cap — mirrors {@code WorkerSpawner.MAX_RESTART_ATTEMPTS} (historical value). */
  public static final int DEFAULT_MAX_RESTART_ATTEMPTS = 3;
  /** Default base cooldown — mirrors {@code WorkerSpawner.RESTART_BASE_COOLDOWN_MS}. */
  public static final long DEFAULT_BASE_COOLDOWN_MS = 1000;
  /** Default cooldown ceiling — mirrors {@code WorkerSpawner.RESTART_MAX_COOLDOWN_MS}. */
  public static final long DEFAULT_MAX_COOLDOWN_MS = 30_000;
  /** Default hang threshold — mirrors the Brain's {@code CONSECUTIVE_FAILURES_BEFORE_RESTART=3}. */
  public static final int DEFAULT_HANG_UNHEALTHY_THRESHOLD = 3;

  public SupervisionPolicy {
    if (maxRestartAttempts < 0) {
      throw new IllegalArgumentException("maxRestartAttempts must be >= 0");
    }
    if (hangUnhealthyThreshold < 1) {
      throw new IllegalArgumentException("hangUnhealthyThreshold must be >= 1");
    }
  }

  /** Fallback stability window when no config is supplied (tests/standalone) — mirrors the config default. */
  private static final long DEFAULT_STABILITY_WINDOW_MS = 300_000;

  /**
   * Builds a policy from the worker config's stability window (the one policy value already exposed as
   * runtime config) and the historical {@code WorkerSpawner} restart constants as defaults. The cap,
   * backoff, and hang threshold are fixed defaults — like the cap/backoff before this tempdoc, they
   * are not per-instance tunables (env/sysprop config reads belong to {@code KnowledgeServerConfig}, the
   * allowlisted config surface; keeping them out of here keeps this class pure for the guardrail).
   */
  public static SupervisionPolicy from(KnowledgeServerConfig config) {
    long stabilityWindowMs = config != null ? config.stabilityWindowMs() : DEFAULT_STABILITY_WINDOW_MS;
    return new SupervisionPolicy(
        DEFAULT_MAX_RESTART_ATTEMPTS,
        DEFAULT_BASE_COOLDOWN_MS,
        DEFAULT_MAX_COOLDOWN_MS,
        stabilityWindowMs,
        DEFAULT_HANG_UNHEALTHY_THRESHOLD);
  }
}
