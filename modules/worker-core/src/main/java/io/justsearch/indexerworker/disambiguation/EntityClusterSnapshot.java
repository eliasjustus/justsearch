/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of entity cluster mappings for lock-free query-time reads.
 *
 * <p>Published via volatile swap from {@link DisambiguationService}. Each search request
 * captures a single snapshot reference for consistent facet merging and filter expansion.
 */
public final class EntityClusterSnapshot {

  /** Empty snapshot used before any disambiguation has run. */
  public static final EntityClusterSnapshot EMPTY =
      new EntityClusterSnapshot(Map.of(), Map.of());

  // entity_type -> (raw_form -> canonical_form)
  private final Map<String, Map<String, String>> rawToCanonical;

  // entity_type -> (canonical_form -> set of raw_forms)
  private final Map<String, Map<String, Set<String>>> canonicalToRaw;

  EntityClusterSnapshot(
      Map<String, Map<String, String>> rawToCanonical,
      Map<String, Map<String, Set<String>>> canonicalToRaw) {
    this.rawToCanonical = rawToCanonical;
    this.canonicalToRaw = canonicalToRaw;
  }

  /** Returns the canonical form for a raw entity mention, or the raw form itself if unmapped. */
  public String getCanonical(String entityType, String rawForm) {
    Map<String, String> typeMap = rawToCanonical.get(entityType);
    if (typeMap == null) {
      return rawForm;
    }
    return typeMap.getOrDefault(rawForm, rawForm);
  }

  /** Returns all raw forms that map to a given canonical form, or a singleton set if unmapped. */
  public Set<String> expandCanonical(String entityType, String canonicalForm) {
    Map<String, Set<String>> typeMap = canonicalToRaw.get(entityType);
    if (typeMap == null) {
      return Set.of(canonicalForm);
    }
    return typeMap.getOrDefault(canonicalForm, Set.of(canonicalForm));
  }

  /** Whether this snapshot has any cluster mappings. */
  public boolean isEmpty() {
    return rawToCanonical.isEmpty();
  }

  /** Builds a snapshot from a list of cluster entries. */
  public static EntityClusterSnapshot fromEntries(
      Iterable<ClusterEntry> entries) {
    Map<String, Map<String, String>> r2c = new HashMap<>();
    Map<String, Map<String, Set<String>>> c2r = new HashMap<>();

    for (ClusterEntry entry : entries) {
      r2c.computeIfAbsent(entry.entityType(), k -> new HashMap<>())
          .put(entry.rawForm(), entry.canonicalForm());

      c2r.computeIfAbsent(entry.entityType(), k -> new HashMap<>())
          .computeIfAbsent(entry.canonicalForm(), k -> new HashSet<>())
          .add(entry.rawForm());
    }

    // Make immutable
    Map<String, Map<String, String>> immR2c = new HashMap<>();
    for (var e : r2c.entrySet()) {
      immR2c.put(e.getKey(), Collections.unmodifiableMap(e.getValue()));
    }
    Map<String, Map<String, Set<String>>> immC2r = new HashMap<>();
    for (var e : c2r.entrySet()) {
      Map<String, Set<String>> innerMap = new HashMap<>();
      for (var inner : e.getValue().entrySet()) {
        innerMap.put(inner.getKey(), Collections.unmodifiableSet(inner.getValue()));
      }
      immC2r.put(e.getKey(), Collections.unmodifiableMap(innerMap));
    }

    return new EntityClusterSnapshot(
        Collections.unmodifiableMap(immR2c), Collections.unmodifiableMap(immC2r));
  }
}
