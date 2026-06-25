/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.Optional;

/**
 * Tempdoc 541 §4.2: a single phase invocation captured at composition-root boot time.
 *
 * <p>Immutable; one record per phase per process boot. Multiple records form a {@link BootTrace}.
 * Records are written once (post-phase-completion) and never mutated — the once-per-boot
 * lifecycle replaces 518's many-time {@code TransitionRecord} ring-buffer pattern (§9.1 A3
 * retraction).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code name} — stable phase identifier (e.g., {@code "infra"}, {@code "capability"},
 *       {@code "service"}, {@code "substrate"}, {@code "orchestration"}). Doubles as the OTel
 *       child-span name ({@code composition.phase.<name>}) and the ring-buffer key.
 *   <li>{@code eagerness} — {@code "EAGER"} or {@code "LAZY"}. EAGER phases run synchronously at
 *       composition-root construction. LAZY phases declare a {@code Supplier<O>} output that
 *       triggers the body on first read.
 *   <li>{@code startedAtMs} — phase body entry timestamp ({@code System.currentTimeMillis()}).
 *   <li>{@code completedAtMs} — phase body exit timestamp. For LAZY phases not yet triggered
 *       at trace publication time, this is null and {@code outcome} is {@code "PENDING"}.
 *   <li>{@code durationMs} — convenience field (completedAtMs - startedAtMs). Null when phase
 *       is still PENDING.
 *   <li>{@code outcome} — {@code "READY"} / {@code "DEGRADED"} / {@code "FAILED"} / {@code
 *       "PENDING"}. Mirrors §5.3's {@code PhaseOutcome} sealed-sum once phases are migrated.
 *   <li>{@code reasonCode} — non-null only when outcome is DEGRADED or FAILED; cross-references
 *       529's wireCode taxonomy where applicable.
 *   <li>{@code spanId} — non-null when OTel tracing was active for this boot; the OTel
 *       child-span ID for {@code composition.phase.<name>}.
 * </ul>
 *
 * <p>Construction helpers favor the common READY case ({@link #ready}); other outcomes use the
 * canonical constructor.
 */
public record PhaseRecord(
    String name,
    Eagerness eagerness,
    long startedAtMs,
    Long completedAtMs,
    Long durationMs,
    String outcome,
    String reasonCode,
    String spanId) {

  /** Outcome wire values. Mirror the {@code PhaseOutcome} sealed-sum once §5.3 fully ships. */
  public static final String READY = "READY";

  public static final String DEGRADED = "DEGRADED";
  public static final String FAILED = "FAILED";
  public static final String PENDING = "PENDING";

  /** Compact constructor — name + eagerness + outcome are required. */
  public PhaseRecord {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("phase name required");
    }
    if (eagerness == null) {
      throw new IllegalArgumentException("eagerness required");
    }
    if (outcome == null) {
      throw new IllegalArgumentException("outcome required");
    }
  }

  /** Factory for the common case: eager phase completed READY. */
  public static PhaseRecord ready(
      String name, long startedAtMs, long completedAtMs, String spanId) {
    return new PhaseRecord(
        name,
        Eagerness.EAGER,
        startedAtMs,
        completedAtMs,
        completedAtMs - startedAtMs,
        READY,
        null,
        spanId);
  }

  /** Factory for a degraded outcome with a reason code (e.g., {@code "inference.not_configured"}). */
  public static PhaseRecord degraded(
      String name, long startedAtMs, long completedAtMs, String reasonCode, String spanId) {
    return new PhaseRecord(
        name,
        Eagerness.EAGER,
        startedAtMs,
        completedAtMs,
        completedAtMs - startedAtMs,
        DEGRADED,
        reasonCode,
        spanId);
  }

  /** Factory for a failed outcome with a reason code. */
  public static PhaseRecord failed(
      String name, long startedAtMs, long completedAtMs, String reasonCode, String spanId) {
    return new PhaseRecord(
        name,
        Eagerness.EAGER,
        startedAtMs,
        completedAtMs,
        completedAtMs - startedAtMs,
        FAILED,
        reasonCode,
        spanId);
  }

  /** Factory for a LAZY phase that has not yet been triggered. */
  public static PhaseRecord lazyPending(String name, String trigger) {
    return new PhaseRecord(name, Eagerness.LAZY, 0L, null, null, PENDING, trigger, null);
  }

  /** Convenience: optional reasonCode for type-checked consumers. */
  public Optional<String> reasonCodeOpt() {
    return Optional.ofNullable(reasonCode);
  }
}
