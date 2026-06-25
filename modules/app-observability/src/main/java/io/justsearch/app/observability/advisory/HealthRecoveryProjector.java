/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.Severity;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Projector for recoverable-health advisories (G158). Consumes
 * {@link HealthEventChangeRegistry.HealthChangeEvent} via {@code subscribeTyped}
 * and projects recoverable HealthEvents into the uniform {@link AdvisoryRecord} shape.
 *
 * <p>Per slice 494 §9: a HealthEvent is "recoverable" iff its body is
 * {@link AssertedCondition} or {@link LifecycleEvent} with {@code recovery.isPresent()}
 * AND {@code severity >= WARNING}. ThresholdState (no recovery field) and
 * UnknownEventBody (forward-compat) return empty.
 *
 * <p>Stream binding (§6.1): bootstrap subscribes this projector to
 * {@code HealthEventChangeRegistry.subscribeTyped()}.
 *
 * <p>Dedup key is per-body-shape (§9): AssertedCondition uses
 * {@code lastTransitionTime}; LifecycleEvent uses the HealthEvent's top-level
 * {@code timestamp} (LifecycleEvent carries no lastTransitionTime).
 */
public final class HealthRecoveryProjector
    implements AdvisoryProjector<HealthEventChangeRegistry.HealthChangeEvent> {

  public static final AdvisoryClassId CLASS_ID = AdvisoryClassId.of("health.recoverable");

  private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(5);

  @Override
  public AdvisoryClassId classId() {
    return CLASS_ID;
  }

  @Override
  public EmissionPolicy emissionPolicy() {
    return EmissionPolicy.persisted().withDedupeWindow(DEDUPE_WINDOW);
  }

  @Override
  public Optional<AdvisoryProjection> project(
      HealthEventChangeRegistry.HealthChangeEvent change) {
    HealthEvent event = change.event();

    if (event.severity() == Severity.INFO) {
      return Optional.empty();
    }

    if (event.body() instanceof AssertedCondition condition) {
      return projectCondition(change, event, condition);
    }

    if (event.body() instanceof LifecycleEvent lifecycle) {
      return projectLifecycle(event, lifecycle);
    }

    return Optional.empty();
  }

  @Override
  public String dedupKey(HealthEventChangeRegistry.HealthChangeEvent change) {
    HealthEvent event = change.event();
    String prefix = event.source().serviceInstanceId() + "#" + event.id();
    if (event.body() instanceof AssertedCondition condition) {
      return prefix + "#" + condition.lastTransitionTime().toString();
    }
    return prefix + "#" + event.timestamp().toString();
  }

  private Optional<AdvisoryProjection> projectCondition(
      HealthEventChangeRegistry.HealthChangeEvent change,
      HealthEvent event,
      AssertedCondition condition) {
    if (condition.recovery().isEmpty()) {
      return Optional.empty();
    }
    HealthEventChangeRegistry.Kind kind = change.kind();
    if (kind != HealthEventChangeRegistry.Kind.CONDITION_ADDED
        && kind != HealthEventChangeRegistry.Kind.CONDITION_MODIFIED) {
      return Optional.empty();
    }
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("conditionId", event.id());
    extras.put("severity", event.severity().name());
    extras.put("subject", condition.subject());
    extras.put("reason", condition.reason());
    return Optional.of(
        new AdvisoryProjection(
            event.timestamp(),
            Optional.empty(),
            Optional.empty(),
            condition.recovery(),
            event.i18nKey(),
            extras));
  }

  private Optional<AdvisoryProjection> projectLifecycle(
      HealthEvent event, LifecycleEvent lifecycle) {
    if (lifecycle.recovery().isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> extras = new LinkedHashMap<>(lifecycle.attributes());
    extras.put("severity", event.severity().name());
    return Optional.of(
        new AdvisoryProjection(
            event.timestamp(),
            Optional.empty(),
            Optional.empty(),
            lifecycle.recovery(),
            event.i18nKey(),
            extras));
  }
}
