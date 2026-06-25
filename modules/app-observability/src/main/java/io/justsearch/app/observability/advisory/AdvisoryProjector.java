/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.EmissionPolicy;
import java.util.Optional;

/**
 * SPI for projecting source-domain events into the uniform {@link AdvisoryRecord}
 * wire shape. Per slice 494 §6: four members, no source-stream binding (binding is
 * a per-source bootstrap concern, not an SPI contract).
 *
 * <p>Identity ownership — shape (α): {@link #dedupKey} is the single source of truth
 * for advisory identity. The central {@link AdvisoryChangeRegistry} stamps
 * {@code AdvisoryRecord.id = classId + ":" + dedupKey(source)} before broadcasting.
 *
 * <p>Idempotency contract (§6.2): {@link #dedupKey} must be a strictly pure function
 * of the source event — no clock reads, no mutable-state reads. {@link #project} is
 * relaxed: it may read {@code Instant.now()} for {@code occurredAt} if the source
 * event lacks one, but the dedup key must be stable across replays.
 *
 * @param <E> the source-domain event type this projector consumes
 */
public interface AdvisoryProjector<E> {

  /** Stable class identity for the advisory class this projector emits. */
  AdvisoryClassId classId();

  /** Emission policy for this advisory class (renderHint + dedupeWindow). */
  EmissionPolicy emissionPolicy();

  /**
   * Projects a source event into an advisory projection. Returns empty if the
   * source event should not produce an advisory (filtered out). The returned
   * projection carries only dynamic fields; static fields (classId, id,
   * renderHint) are stamped by the registry.
   */
  Optional<AdvisoryProjection> project(E source);

  /**
   * Computes the dedup key from the source event. Must be a pure function of
   * the source event with no side effects, clock reads, or mutable-state reads.
   * The registry stamps {@code AdvisoryRecord.id = classId + ":" + dedupKey}.
   */
  String dedupKey(E source);
}
