/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Sibling axis to {@link OperationPolicy}, governing <em>unprompted system → user
 * emission</em>: what may an advisory-shaped Resource push, when, with what renderer
 * shape?
 *
 * <p>Per slice 490 §4.C: {@code OperationPolicy.confirm} ({@code None / Inline / Typed})
 * gates <em>execution</em> — what must the user do before an Operation fires?
 * {@code EmissionPolicy} is the home for <em>discovery</em> + <em>presentation</em>
 * concerns on the proactive-emission side. The two axes are orthogonal and compose:
 * discovery / presentation gate on the advisory-class Resource; execution gate on each
 * linked Operation (via the operation's own {@link OperationPolicy}).
 *
 * <p>Declared on the advisory-class {@link Resource} via {@code Resource.emissionPolicy},
 * not on each event instance. The policy is a property of the event class, not the
 * event. Adding a new advisory class forces the author to name an emission posture.
 *
 * <p><strong>v1 fields:</strong>
 *
 * <ul>
 *   <li>{@link #renderHint} — renderer-selection driving toast / inbox / ack-dialog
 *       choice. Per-Resource declaration; toast/drawer dispatch on the event's
 *       source-Resource renderHint.
 *   <li>{@link #dedupeWindow} (substrate-completion follow-up) — when present, the
 *       advisory broadcast registry suppresses repeats of the same (operationId,
 *       outcome) key within the declared window. Activates the slice 488 §5
 *       "stale-action / over-notification" prevention discipline at the substrate
 *       boundary rather than letting consumers ad-hoc throttle. Absent = no
 *       suppression (every broadcast publishes).
 * </ul>
 *
 * <p>Future discovery-gate fields land additively when their user-config substrate
 * exists:
 *
 * <ul>
 *   <li>{@code quietHours: TimeRange | none} — suppress emission during user-declared
 *       quiet hours (slice 488 §5 "over-notification").
 *   <li>{@code importanceFloor: ImportanceFloor} — gate on a per-event importance
 *       score (slice 488 §5 "false importance"); pairs with adding
 *       {@code importanceScore} to event payloads.
 * </ul>
 *
 * These are NOT synonyms for the existing fields; they are separate axes that compose
 * with them.
 *
 * <p>Audience visibility is <strong>not</strong> a field on this record — the existing
 * {@code Audience} enum and {@code audienceFloorForTier} mapping (slice 449) govern
 * trust-tier visibility at the consumer side.
 */
public record EmissionPolicy(RenderHint renderHint, Optional<Duration> dedupeWindow)
    implements PreciseWire {

  public EmissionPolicy {
    Objects.requireNonNull(renderHint, "renderHint");
    Objects.requireNonNull(dedupeWindow, "dedupeWindow");
    dedupeWindow.ifPresent(
        d -> {
          if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(
                "dedupeWindow, when present, must be a strictly positive duration");
          }
        });
  }

  /**
   * Slice 490 v1 back-compat constructor — pre-Group-B1-dedupe shape. Defaults
   * {@link #dedupeWindow} to {@link Optional#empty()} so Resource declarations from
   * pre-substrate-completion code paths compile unchanged.
   */
  public EmissionPolicy(RenderHint renderHint) {
    this(renderHint, Optional.empty());
  }

  /**
   * Builder-style copy with a declared dedupe window. Sugar for
   * {@code new EmissionPolicy(this.renderHint(), Optional.of(window))}.
   */
  public EmissionPolicy withDedupeWindow(Duration window) {
    Objects.requireNonNull(window, "window");
    return new EmissionPolicy(this.renderHint, Optional.of(window));
  }

  /**
   * Convenience: ephemeral / transient emission (toast renderer). Pass-8 follow-up
   * note: no production callsite in this branch — the v1 advisory class
   * ({@code core.advisory-operation-completed}) uses {@link #persisted}. Future
   * EPHEMERAL classes (e.g. G44 tutorial-nudge from slice 486 §32 Cluster D) will
   * consume this factory.
   */
  public static EmissionPolicy ephemeral() {
    return new EmissionPolicy(RenderHint.EPHEMERAL);
  }

  /** Convenience: durable emission with read/unread state (inbox renderer). */
  public static EmissionPolicy persisted() {
    return new EmissionPolicy(RenderHint.PERSISTED);
  }

  /**
   * Convenience: durable emission requiring explicit acknowledgment. Pass-8 follow-
   * up note: no production callsite in this branch — future REQUIRES_ACK classes
   * (G75 PII detection from slice 486 §32 Cluster D) will consume this factory.
   */
  public static EmissionPolicy requiresAck() {
    return new EmissionPolicy(RenderHint.REQUIRES_ACK);
  }
}
