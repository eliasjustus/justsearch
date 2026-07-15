/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.Mode;
import java.util.OptionalLong;

/**
 * Head-side GPU lease state (tempdoc 737 §12a). Models the chat engine and embedding work as
 * <i>holders</i> of a single GPU grant: {@code ONLINE ≡ CHAT holds it}, {@code INDEXING ≡ WORKER
 * may use it}, {@code OFFLINE ≡ NONE}. The cross-process MMF boolean stays exactly as-is — it is
 * the lease's projection ({@code gpuActive == holder==CHAT}); this class does not write it.
 *
 * <p><b>Phase-1 role: PASSIVE MIRROR.</b> The lease is driven by {@link #mirrorFromMode(Mode)}
 * from the reconciler's mode-change listener; it does not yet drive the MMF write (that stays in
 * {@code InferenceWiring} this phase) nor arbitrate admission.
 *
 * <p>{@link #requestGrant(Holder, OptionalLong)} is the size-admitting interface (§12a / P4:
 * "resource exclusivity is policy, not ontology") but implements ONLY binary logic in this phase
 * — {@code sizeBytes} is accepted and <b>ignored</b>; sized admission is future work.
 */
public final class RuntimeGpuLease {

  /** Who currently holds the GPU grant. */
  public enum Holder {
    CHAT,
    WORKER,
    NONE
  }

  /** Outcome of a grant request. {@code sizeBytes} is echoed for the future sized path. */
  public record Grant(boolean granted, Holder holder, OptionalLong sizeBytes, String reason) {}

  private volatile Holder holder = Holder.NONE;

  public Holder holder() {
    return holder;
  }

  /**
   * Binary grant logic (Phase 1): granted iff the lease is free ({@code NONE}) or already held by
   * the requester. {@code sizeBytes} is accepted for forward-compatibility and IGNORED — sized
   * co-residency admission is future work (§12a / P4).
   */
  public Grant requestGrant(Holder requester, OptionalLong sizeBytes) {
    if (requester == null || requester == Holder.NONE) {
      return new Grant(false, holder, sizeBytes, "invalid-requester");
    }
    Holder current = holder;
    if (current == Holder.NONE || current == requester) {
      return new Grant(true, requester, sizeBytes, "granted");
    }
    return new Grant(false, current, sizeBytes, "held-by-" + current);
  }

  /**
   * Passive mirror: derive the holder from the observed engine mode. {@code TRANSITIONING} leaves
   * the holder unchanged (mid-swap — do not flip the lease until the transition commits).
   */
  public void mirrorFromMode(Mode mode) {
    if (mode == null) {
      return;
    }
    switch (mode) {
      case ONLINE -> holder = Holder.CHAT;
      case INDEXING -> holder = Holder.WORKER;
      case OFFLINE -> holder = Holder.NONE;
      case TRANSITIONING -> {
        /* keep last holder mid-swap */
      }
    }
  }
}
