/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.lifecycle;

import io.justsearch.contract.wire.LifecycleState;
import java.time.Instant;
import java.util.Objects;

/**
 * Stable, contract-tested lifecycle snapshot for /api/status and /api/health (schema v1).
 *
 * <p>Stability: stable (API contract)
 */
public record LifecycleSnapshotV1(
    int schema_version,
    String observed_at,
    Lifecycle lifecycle,
    Components components) {

  public static final int SCHEMA_VERSION = 1;

  public LifecycleSnapshotV1 {
    if (schema_version != SCHEMA_VERSION) {
      throw new IllegalArgumentException("schema_version must be " + SCHEMA_VERSION);
    }
    Objects.requireNonNull(observed_at, "observed_at");
    Objects.requireNonNull(lifecycle, "lifecycle");
    Objects.requireNonNull(components, "components");
  }

  public static LifecycleSnapshotV1 now(Lifecycle lifecycle, Components components) {
    return new LifecycleSnapshotV1(SCHEMA_VERSION, Instant.now().toString(), lifecycle, components);
  }

  public static record Lifecycle(LifecycleState state, String reason_code, String message) {
    public Lifecycle {
      requireRealState(state);
    }

    public Lifecycle(LifecycleState state) {
      this(state, null, null);
    }
  }

  /**
   * 548 §4.1: the lifecycle vocabulary's single authority is the proto enum. Reject null and the
   * non-domain sentinels so a snapshot only ever carries one of the six real states.
   */
  private static void requireRealState(LifecycleState state) {
    Objects.requireNonNull(state, "state");
    if (state == LifecycleState.LIFECYCLE_STATE_UNSPECIFIED
        || state == LifecycleState.UNRECOGNIZED) {
      throw new IllegalArgumentException("lifecycle state must be a real state, got: " + state);
    }
  }

  public static record Components(Component head, Component worker, Component inference) {
    public Components {
      Objects.requireNonNull(head, "head");
      Objects.requireNonNull(worker, "worker");
      Objects.requireNonNull(inference, "inference");
    }
  }

  public static record Component(LifecycleState state, String reason_code) {
    public Component {
      requireRealState(state);
    }

    public Component(LifecycleState state) {
      this(state, null);
    }
  }
}
