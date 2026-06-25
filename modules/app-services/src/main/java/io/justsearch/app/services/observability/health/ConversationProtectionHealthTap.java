/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Tempdoc 629 (#2) — the AUTHORED-store at-rest legibility tap, the LAYER sibling of {@link
 * AtRestHealthTap}. It conforms the LAYER's "authored data is unprotected" legibility to the SAME
 * ConditionStore→CAUSE_ROWS authority the FLOOR uses: when the AUTHORED stores (conversations, memories,
 * agent runs) have <b>no at-rest protection at all</b> — app-encryption is not configured AND the disk is
 * not OS-encrypted — it asserts a persistent {@code at-rest.authored} {@link AssertedCondition} that
 * surfaces in Health "Recent events" with honest, two-remedy guidance (set up chat encryption, or enable
 * device encryption). This is the FE altitude-3 "fires only on a real gap" (629 §UF.1).
 *
 * <p>Asserted iff the authored data is genuinely unprotected; cleared otherwise — and in particular it
 * <b>clears the moment app-encryption is configured, even with FDE off</b>, which makes the LAYER's whole
 * value legible (authored data protected without disk encryption). A merely-LOCKED store is the
 * <i>secure</i> state and raises nothing here (the "unlock to read" legibility is the chat surface's
 * locked affordance, 629 #3). "Unknown ≠ unhealthy" — an undeterminable FDE state never warns.
 *
 * <p>Deliberately verdict-independent (like the FLOOR tap): confidentiality legibility, not a retrieval
 * degradation, so it never drives the global "service degraded" verdict pill. Snapshot-triggered from
 * {@code StatusLifecycleHandler.buildStatusMap()} alongside the other taps — no new threading.
 */
public final class ConversationProtectionHealthTap {

  static final String CONDITION_ID = "at-rest.authored";
  static final String SUBJECT = "conversations";
  private static final String REASON = "AuthoredUnprotected";
  private static final Severity SEVERITY = Severity.WARNING;
  private static final String MESSAGE =
      "Your chat history, memories, and agent history have no at-rest protection. "
          + "Turn on chat encryption in Settings, or enable device encryption (BitLocker), to protect them.";

  private final ConditionStore conditions;
  private final HealthEventChangeRegistry changes;
  private final Source source;
  private final Clock clock;
  private final BooleanSupplier unprotected;

  private volatile boolean asserted;

  public ConversationProtectionHealthTap(
      ConditionStore conditions,
      HealthEventChangeRegistry changes,
      Source source,
      Clock clock,
      BooleanSupplier unprotected) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.unprotected = Objects.requireNonNull(unprotected, "unprotected");
  }

  /** Snapshot hook called from {@code StatusLifecycleHandler.buildStatusMap()}. */
  public void accept() {
    reconcile(unprotected.getAsBoolean());
  }

  /** Pure-ish core: assert iff the AUTHORED stores are genuinely unprotected; clear otherwise. */
  synchronized void reconcile(boolean isUnprotected) {
    if (isUnprotected) {
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
