/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.atrest.AtRestProtection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tempdoc 629 (FLOOR) — the at-rest-protection legibility tap. An instance of the same
 * ConditionStore→CAUSE_ROWS legibility seam {@link IndexDriftHealthTap} uses (tempdoc 626/628): when
 * the data-dir volume is NOT protected by OS disk encryption, the authored stores (conversations,
 * memories, agent runs) have no at-rest protection at all — so this surfaces a persistent {@code
 * at-rest.unprotected} {@link AssertedCondition} carrying honest guidance, instead of leaving the
 * gap silent.
 *
 * <p>Single-subject (unlike the per-root drift tap): the condition is asserted iff the sensed coarse
 * state is {@link AtRestProtection.State#NOT_ENCRYPTED}, and cleared for {@code ENCRYPTED} /
 * {@code ENCRYPTING}. On {@code UNKNOWN} the condition is also cleared — "treat unknown ≠ unhealthy":
 * a can't-determine state must not raise a scary warning (629 §UF / confidence-probe P3).
 *
 * <p>Deliberately verdict-independent: at-rest protection is a confidentiality concern, not a
 * retrieval-health degradation, so it is surfaced as a HealthSurface condition and never drives the
 * global "service degraded" verdict pill (629 confidence-probe P3). Snapshot-triggered from {@code
 * StatusLifecycleHandler.buildStatusMap()} alongside the other taps — no new threading.
 */
public final class AtRestHealthTap {

  static final String CONDITION_ID = "at-rest.unprotected";
  static final String SUBJECT = "data-dir";
  private static final String REASON = "VolumeNotEncrypted";
  private static final Severity SEVERITY = Severity.WARNING;
  private static final String MESSAGE =
      "Your data folder is on a drive that isn't encrypted by your operating system. "
          + "Turn on device encryption (BitLocker with a startup PIN on Windows) to protect it at rest.";

  private final ConditionStore conditions;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;
  private final Supplier<AtRestProtection> atRestSupplier;

  private volatile boolean asserted;

  public AtRestHealthTap(
      ConditionStore conditions,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock,
      Supplier<AtRestProtection> atRestSupplier) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.atRestSupplier = Objects.requireNonNull(atRestSupplier, "atRestSupplier");
  }

  /** Snapshot hook called from {@code StatusLifecycleHandler.buildStatusMap()}. */
  public void accept() {
    AtRestProtection p = atRestSupplier.get();
    reconcile(p == null ? AtRestProtection.State.UNKNOWN : p.state());
  }

  /** Pure-ish core: assert the condition iff the volume is known-unencrypted; clear otherwise. */
  synchronized void reconcile(AtRestProtection.State state) {
    if (state == AtRestProtection.State.NOT_ENCRYPTED) {
      upsert();
    } else {
      clear();
    }
  }

  private void upsert() {
    HealthEvent event =
        new HealthEvent(
            CONDITION_ID,
            clock.instant(),
            source,
            SEVERITY,
            Optional.of("health-events." + CONDITION_ID + ".message"),
            new AssertedCondition(
                SUBJECT,
                ConditionStatus.TRUE,
                REASON,
                clock.instant(),
                Optional.of(MESSAGE),
                Optional.empty(),
                List.of()));
    ConditionStore.Transition t = conditions.upsert(event);
    switch (t) {
      case ADDED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_ADDED, event);
      case MODIFIED -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_MODIFIED, event);
      case UNCHANGED -> {
        /* store preserved prior record — no broadcast */
      }
    }
    asserted = true;
  }

  private void clear() {
    if (!asserted) {
      return;
    }
    Optional<HealthEvent> removed = conditions.clear(CONDITION_ID, SUBJECT);
    removed.ifPresent(e -> changes.broadcast(HealthEventChangeRegistry.Kind.CONDITION_REMOVED, e));
    asserted = false;
  }
}
