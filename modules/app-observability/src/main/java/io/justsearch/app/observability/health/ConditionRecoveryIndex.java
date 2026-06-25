/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.util.List;
import java.util.Objects;

/**
 * Wire payload for the {@code core.condition-recovery-index} Resource: the derived inverse
 * view of recovery references currently held in {@link ConditionStore}.
 *
 * <p>Per slice 447 §X.3.4 + 447-impl-D: STATE × ONE_SHOT shape (current snapshot,
 * subscribers re-fetch). {@link #catalogVersion} is taken from
 * {@link ConditionStore#currentVersion()} at build time so consumers can detect drift.
 */
public record ConditionRecoveryIndex(List<ConditionRecoveryEntry> entries, long catalogVersion) {

  public ConditionRecoveryIndex {
    Objects.requireNonNull(entries, "entries");
    entries = List.copyOf(entries);
    if (catalogVersion < 0) {
      throw new IllegalArgumentException("catalogVersion must be >= 0, got " + catalogVersion);
    }
  }

  public static ConditionRecoveryIndex empty() {
    return new ConditionRecoveryIndex(List.of(), 0L);
  }
}
