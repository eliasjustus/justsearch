/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.preview;

import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RequiredCapability;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Derives an {@link OperationAvailability} expression from an Operation's declared
 * {@link RequiredCapability} set — tempdoc 550 E3.
 *
 * <p>Capability-gating (dispatch-time, via the executor's {@code capabilityResolver}) and
 * Preview availability (discovery-time, "should this be offered now?") are the <b>same axis</b>:
 * an op that needs the Worker shouldn't be offered to the agent while the Worker is down. The
 * {@code CapabilityHealthBridge} publishes <b>absence=healthy</b> conditions — {@code
 * worker.capability} / {@code inference.capability} fire only when that capability is NOT READY
 * and are reliably cleared on recovery. So {@code RequiredCapability.WorkerOnline} maps to
 * {@code Not(ConditionMatches("worker.capability"))} ("available unless the worker is not ready"),
 * and likewise for inference. This lets availability be derived <b>mechanically from the
 * capabilities ops already declare</b>, rather than hand-authored per op.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@code WorkerOnline}, {@code IndexedRoot} → {@code worker.capability} (the executor's
 *       resolver also backs both with worker readiness).
 *   <li>{@code InferenceOnline} → {@code inference.capability}.
 *   <li>{@code GpuAvailable} → no condition is published for GPU, so it contributes nothing to the
 *       availability hint (it remains enforced at dispatch by the capability resolver).
 * </ul>
 *
 * <p>An op's own hand-authored availability expression always wins — derivation only fills ops
 * that declared capabilities but no explicit availability (e.g. {@code core.search-index} keeps
 * its finer {@code index.unavailable} gate). Availability is a discovery hint; the authoritative
 * enforcement stays the dispatch-time capability resolver + the trust lattice.
 */
public final class CapabilityAvailability {

  private CapabilityAvailability() {}

  /** The condition id a capability maps to, or null when no condition represents it. */
  private static String conditionFor(RequiredCapability cap) {
    return switch (cap) {
      case RequiredCapability.WorkerOnline ignored -> "worker.capability";
      case RequiredCapability.IndexedRoot ignored -> "worker.capability";
      case RequiredCapability.InferenceOnline ignored -> "inference.capability";
      case RequiredCapability.GpuAvailable ignored -> null;
    };
  }

  /**
   * Derive an availability expression from a capability set: {@code Not(ConditionMatches(c))}
   * for each distinct mapped condition (an {@code AllOf} when more than one). Empty when no
   * capability maps to a condition.
   */
  public static Optional<AvailabilityExpression> derive(Set<RequiredCapability> capabilities) {
    if (capabilities == null || capabilities.isEmpty()) {
      return Optional.empty();
    }
    // Distinct condition ids, insertion-ordered for a stable expression shape.
    Set<String> conditions = new LinkedHashSet<>();
    for (RequiredCapability cap : capabilities) {
      String cond = conditionFor(cap);
      if (cond != null) {
        conditions.add(cond);
      }
    }
    if (conditions.isEmpty()) {
      return Optional.empty();
    }
    List<AvailabilityExpression> terms = new ArrayList<>();
    for (String cond : conditions) {
      terms.add(new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches(cond)));
    }
    return Optional.of(terms.size() == 1 ? terms.get(0) : new AvailabilityExpression.AllOf(terms));
  }

  /**
   * Return a copy of {@code catalog} in which every op that declared capabilities but no explicit
   * availability expression gets a capability-derived one. Ops with an explicit expression are
   * left untouched (explicit wins). Idempotent.
   */
  public static OperationCatalog withCapabilityDerivedAvailability(OperationCatalog catalog) {
    List<Operation> mapped = new ArrayList<>(catalog.definitions().size());
    for (Operation op : catalog.definitions()) {
      if (op.availability().expression().isPresent()) {
        mapped.add(op); // explicit availability wins — do not override.
        continue;
      }
      Optional<AvailabilityExpression> derived = derive(op.policy().requiredCapabilities());
      if (derived.isEmpty()) {
        mapped.add(op);
        continue;
      }
      mapped.add(
          op.withAvailability(
              new OperationAvailability(derived, op.availability().argumentDefaultsJson())));
    }
    return OperationCatalog.of(catalog.namespace(), mapped);
  }
}
