/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.util.Set;

/** Head-owned global VDU capability state for status/readiness projection. */
public final class VduCapabilityState {
  public static final String REASON_AI_OFFLINE = "vdu.ai_offline";
  public static final String REASON_INSUFFICIENT_VRAM = "vdu.insufficient_vram";
  public static final String REASON_MISSING_MMPROJ = "vdu.missing_mmproj";
  public static final String REASON_CIRCUIT_OPEN = "vdu.circuit_open";

  private static final Set<String> KNOWN_REASONS =
      Set.of(REASON_AI_OFFLINE, REASON_INSUFFICIENT_VRAM, REASON_MISSING_MMPROJ, REASON_CIRCUIT_OPEN);

  private volatile Snapshot snapshot = Snapshot.empty();

  public Snapshot snapshot() {
    return snapshot;
  }

  public void block(String reasonCode) {
    if (reasonCode == null || !KNOWN_REASONS.contains(reasonCode)) {
      return;
    }
    snapshot = new Snapshot(reasonCode, System.currentTimeMillis());
  }

  public void clear(String reasonCode) {
    Snapshot current = snapshot;
    if (reasonCode != null && reasonCode.equals(current.blockedReason())) {
      snapshot = Snapshot.empty();
    }
  }

  public void clearAll() {
    snapshot = Snapshot.empty();
  }

  public record Snapshot(String blockedReason, long updatedAtMs) {
    public static Snapshot empty() {
      return new Snapshot(null, 0L);
    }
  }
}
