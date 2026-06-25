/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registry of known {@link AdvisoryClassId} values. Per slice 494 §6.3: the ArchUnit
 * invariant enforces 1:1 between registered class IDs and {@link AdvisoryProjector}
 * implementations. Per §6.5: exactly one projector per class ID (multi-projector-per-
 * class defeats the dedup contract).
 *
 * <p>Registration happens once at bootstrap; the registry is immutable after startup.
 * Future plugin-contributed advisory classes (Phase-D scope) register at startup via
 * ServiceLoader or explicit bootstrap calls.
 */
public final class AdvisoryClassRegistry {

  private final Map<AdvisoryClassId, AdvisoryProjector<?>> projectors;

  private AdvisoryClassRegistry(Map<AdvisoryClassId, AdvisoryProjector<?>> projectors) {
    this.projectors = Collections.unmodifiableMap(new LinkedHashMap<>(projectors));
  }

  public Set<AdvisoryClassId> classIds() {
    return projectors.keySet();
  }

  @SuppressWarnings("unchecked")
  public <E> AdvisoryProjector<E> projector(AdvisoryClassId classId) {
    AdvisoryProjector<?> p = projectors.get(classId);
    if (p == null) {
      throw new IllegalArgumentException("No projector registered for advisory class: " + classId);
    }
    return (AdvisoryProjector<E>) p;
  }

  public boolean contains(AdvisoryClassId classId) {
    return projectors.containsKey(classId);
  }

  public int size() {
    return projectors.size();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final Map<AdvisoryClassId, AdvisoryProjector<?>> map = new LinkedHashMap<>();

    private Builder() {}

    public <E> Builder register(AdvisoryProjector<E> projector) {
      Objects.requireNonNull(projector, "projector");
      AdvisoryClassId classId = projector.classId();
      Objects.requireNonNull(classId, "projector.classId()");
      if (map.containsKey(classId)) {
        throw new IllegalStateException(
            "Duplicate projector registration for advisory class "
                + classId.value()
                + ": "
                + map.get(classId).getClass().getSimpleName()
                + " and "
                + projector.getClass().getSimpleName());
      }
      map.put(classId, projector);
      return this;
    }

    public AdvisoryClassRegistry build() {
      if (map.isEmpty()) {
        throw new IllegalStateException("AdvisoryClassRegistry must have at least one projector");
      }
      return new AdvisoryClassRegistry(map);
    }
  }
}
