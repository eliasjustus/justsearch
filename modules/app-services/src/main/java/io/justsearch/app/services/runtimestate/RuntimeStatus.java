/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.Mode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Observed runtime state (tempdoc 737 §12a), Condition-shaped per axis (§11a — the KEP-1623 /
 * {@code CapabilityHealth} vocabulary this layer reuses). Immutable snapshot; the reconciler owns
 * the single mutable reference and swaps a fresh {@code RuntimeStatus} in on each observation.
 *
 * <p>Phase-1 axes: {@code ENGINE} (Down / Starting / Healthy / Recovering), {@code ADOPTION}
 * (own / external), {@code LEASE} (CHAT / WORKER / NONE). In-flight-procedure overlay (install /
 * activation / VDU-batch) is deferred to Phase 2.
 *
 * <p><b>Reason codes are internal strings in this phase.</b> They are NOT wired into
 * {@code LifecycleReasonCode} / {@code check-readiness-reason-codes} yet — that join (which
 * requires paired FE rows) is Phase 2. Where a {@code TransitionReason} name fits, it is reused.
 */
public record RuntimeStatus(List<Condition> conditions) {

  /** The axes a Condition can describe in Phase 1. */
  public enum Axis {
    ENGINE,
    ADOPTION,
    LEASE
  }

  /**
   * One per-axis condition. {@code status} is the axis's vocabulary value (e.g. {@code "Healthy"},
   * {@code "external"}, {@code "CHAT"}); {@code reason} is an internal string code; {@code message}
   * is human-facing; {@code observedSpecVersion} is the {@link RuntimeSpec} version this condition
   * was derived against; {@code lastTransition} is when this status was observed.
   */
  public record Condition(
      Axis axis,
      String status,
      String reason,
      String message,
      long observedSpecVersion,
      Instant lastTransition) {}

  public RuntimeStatus {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public Optional<Condition> condition(Axis axis) {
    return conditions.stream().filter(c -> c.axis() == axis).findFirst();
  }

  // ==================== Engine axis vocabulary ====================

  public static final String ENGINE_DOWN = "Down";
  public static final String ENGINE_STARTING = "Starting";
  public static final String ENGINE_HEALTHY = "Healthy";
  public static final String ENGINE_RECOVERING = "Recovering";

  // Internal reason codes (Phase-1 strings; not LifecycleReasonCode members yet).
  public static final String REASON_ENGINE_DOWN = "engine-down";
  public static final String REASON_GPU_YIELDED_TO_INDEXING = "gpu-yielded-to-indexing";
  public static final String REASON_ENGINE_STARTING = "engine-starting";
  public static final String REASON_ENGINE_HEALTHY = "engine-healthy";

  /**
   * Derives the ENGINE-axis condition from the observed {@link Mode}. Pure and deterministic —
   * the derivation table under test.
   *
   * <ul>
   *   <li>{@code OFFLINE} → Down (engine-down)
   *   <li>{@code INDEXING} → Down (gpu-yielded-to-indexing) — the GPU is the Worker's, chat is
   *       intentionally not running
   *   <li>{@code TRANSITIONING} → Starting (engine-starting). {@code Recovering} is a Phase-2
   *       refinement gated on a {@code TransitionReason == CRASH_RECOVERY} signal that the
   *       bare {@code ModeChangeListener} does not carry.
   *   <li>{@code ONLINE} → Healthy (engine-healthy)
   * </ul>
   */
  public static Condition deriveEngine(Mode mode, long specVersion, Instant now) {
    Mode m = mode == null ? Mode.OFFLINE : mode;
    return switch (m) {
      case OFFLINE ->
          new Condition(Axis.ENGINE, ENGINE_DOWN, REASON_ENGINE_DOWN, "Inference engine is down", specVersion, now);
      case INDEXING ->
          new Condition(
              Axis.ENGINE,
              ENGINE_DOWN,
              REASON_GPU_YIELDED_TO_INDEXING,
              "GPU yielded to indexing; chat engine not running",
              specVersion,
              now);
      case TRANSITIONING ->
          new Condition(
              Axis.ENGINE, ENGINE_STARTING, REASON_ENGINE_STARTING, "Inference engine transitioning", specVersion, now);
      case ONLINE ->
          new Condition(Axis.ENGINE, ENGINE_HEALTHY, REASON_ENGINE_HEALTHY, "Inference engine healthy", specVersion, now);
    };
  }

  /** Derives the ADOPTION-axis condition from the observed external-server flag. */
  public static Condition deriveAdoption(boolean external, long specVersion, Instant now) {
    return external
        ? new Condition(
            Axis.ADOPTION, "external", "external-server-adopted", "Using an external llama-server", specVersion, now)
        : new Condition(Axis.ADOPTION, "own", "own-process", "Using the managed llama-server process", specVersion, now);
  }

  /** Derives the LEASE-axis condition from the current lease holder. */
  public static Condition deriveLease(RuntimeGpuLease.Holder holder, long specVersion, Instant now) {
    RuntimeGpuLease.Holder h = holder == null ? RuntimeGpuLease.Holder.NONE : holder;
    return new Condition(Axis.LEASE, h.name(), "lease-holder", "GPU lease holder: " + h.name(), specVersion, now);
  }

  /** Full three-axis derivation for the given observation. */
  public static RuntimeStatus derive(
      Mode mode, boolean external, RuntimeGpuLease.Holder leaseHolder, long specVersion, Instant now) {
    Instant t = now == null ? Instant.now() : now;
    return new RuntimeStatus(
        List.of(
            deriveEngine(mode, specVersion, t),
            deriveAdoption(external, specVersion, t),
            deriveLease(leaseHolder, specVersion, t)));
  }

  /** Initial status before the first observation: engine down, own process, no lease. */
  public static RuntimeStatus initial() {
    return derive(Mode.OFFLINE, false, RuntimeGpuLease.Holder.NONE, 0L, Instant.now());
  }
}
