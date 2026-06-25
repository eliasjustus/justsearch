/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.app.observability.health.Severity;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * One operational-signal rule loaded from a classpath YAML file.
 *
 * <p>Per tempdoc 430 §A.3: a Prometheus-shaped rule with a CEL predicate and dwell-time
 * semantics. V1 ships only one rule (`memory-pressure`); V1.5 generalizes the rule
 * engine to absorb existing classification machines (see §B.J).
 *
 * <p>YAML schema (per §A.3):
 *
 * <pre>{@code
 * rule: memory-pressure
 * kind: threshold
 * emits:
 *   id: memory.pressure
 *   body_kind: threshold
 *   subject: head.memory
 *   reason: MemoryPressureHigh
 *   severity: WARNING
 * expr_cel: |
 *   signals['head.jvm.memory.heap.used_bytes'].latest()
 *     / signals['head.jvm.memory.heap.max_bytes'].latest() > 0.9
 * for: 60s
 * keep_firing_for: 30s
 * magnitudes_cel:
 *   used_bytes: signals['head.jvm.memory.heap.used_bytes'].latest()
 *   max_bytes: signals['head.jvm.memory.heap.max_bytes'].latest()
 *   ratio_pct: 100.0 * signals['head.jvm.memory.heap.used_bytes'].latest()
 *              / signals['head.jvm.memory.heap.max_bytes'].latest()
 * }</pre>
 *
 * @param name unique rule name within the catalog (e.g., {@code "memory-pressure"})
 * @param kind {@link Kind#CONDITION} or {@link Kind#THRESHOLD}; determines wire body type
 * @param emits the emit-target metadata (catalog ID, subject, reason, severity)
 * @param exprCel the CEL predicate expression (boolean-typed)
 * @param forDuration predicate must hold ≥{@code forDuration} before firing (Prometheus {@code for})
 * @param keepFiringFor flap-suppression grace after predicate goes false (Prometheus
 *     {@code keep_firing_for})
 * @param magnitudesCel optional per-magnitude CEL expressions (only meaningful for THRESHOLD
 *     rules); each entry's value is a CEL expression evaluated to a {@code Number}
 */
public record Rule(
    String name,
    Kind kind,
    Emits emits,
    String exprCel,
    Duration forDuration,
    Duration keepFiringFor,
    Map<String, String> magnitudesCel) {

  public Rule {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(emits, "emits");
    Objects.requireNonNull(exprCel, "exprCel");
    Objects.requireNonNull(forDuration, "forDuration");
    Objects.requireNonNull(keepFiringFor, "keepFiringFor");
    if (name.isBlank()) {
      throw new IllegalArgumentException("rule name must be non-blank");
    }
    if (exprCel.isBlank()) {
      throw new IllegalArgumentException("exprCel must be non-blank");
    }
    if (forDuration.isNegative()) {
      throw new IllegalArgumentException("forDuration must be >= 0");
    }
    if (keepFiringFor.isNegative()) {
      throw new IllegalArgumentException("keepFiringFor must be >= 0");
    }
    magnitudesCel = magnitudesCel == null ? Map.of() : Map.copyOf(magnitudesCel);
  }

  /**
   * Rule kind discriminator. Maps to the wire-format body kind on emit:
   *
   * <ul>
   *   <li>{@link #CONDITION} → {@code AssertedCondition} body
   *   <li>{@link #THRESHOLD} → {@code ThresholdState} body
   * </ul>
   */
  public enum Kind {
    CONDITION,
    THRESHOLD
  }

  /**
   * Emit-target metadata: the catalog ID + subject + reason + severity associated with the rule's
   * fire/resolve transitions.
   */
  public record Emits(String id, String subject, String reason, Severity severity) {

    public Emits {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(severity, "severity");
      if (id.isBlank()) {
        throw new IllegalArgumentException("emits.id must be non-blank");
      }
      if (subject.isBlank()) {
        throw new IllegalArgumentException("emits.subject must be non-blank");
      }
      if (reason.isBlank()) {
        throw new IllegalArgumentException("emits.reason must be non-blank");
      }
    }
  }
}
