/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Set;

/**
 * Post-execution-axis sub-record on {@link Operation}: which Resources this Operation
 * may touch, and which prior Operations it supersedes.
 *
 * <p>Per slice 447 §X.3.1 + §X.11.5 follow-up Phase 3: third of the three sub-records
 * produced by partitioning OperationPolicy along consumer-model lines. Future history
 * surface + lineage-discovery consumers read these axes; the executor (which reads
 * {@link OperationPolicy}) does not.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code affects}: Resources this Operation may mutate. Replaces the
 *       formerly-phantom {@code OperationHistoryEntry.affectedResources} per-history-
 *       entry runtime-observed metadata with per-Operation-declaration intrinsic.
 *       Empty when the Operation doesn't mutate Resource state (e.g., read-only
 *       operations).
 *   <li>{@code supersedes}: prior Operations this one replaces (e.g., a v2 Operation
 *       superseding a v1). Empty when there's no superseded predecessor. Discovery
 *       layer + agent retrospection use this to favor the newer Operation.
 * </ul>
 *
 * <p>Empty default per §X.11.2: every existing Operation construction site passes
 * {@link #empty()} during the initial partition rollout. Non-empty values land per
 * declaration when consumer slices ship.
 */
public record OperationLineage(Set<ResourceRef> affects, Set<OperationRef> supersedes) {

  public OperationLineage {
    Objects.requireNonNull(affects, "affects");
    Objects.requireNonNull(supersedes, "supersedes");
    affects = Set.copyOf(affects);
    supersedes = Set.copyOf(supersedes);
  }

  /** Empty lineage axis: no affected Resources, no superseded Operations. */
  public static OperationLineage empty() {
    return new OperationLineage(Set.of(), Set.of());
  }
}
