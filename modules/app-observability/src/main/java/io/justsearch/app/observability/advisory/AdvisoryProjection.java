/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic projection result returned by {@link AdvisoryProjector#project}. Contains
 * source-derived fields only; the static fields ({@code classId}, {@code id},
 * {@code renderHint}) are stamped by the {@link AdvisoryChangeRegistry} from the
 * projector's metadata and dedup key.
 *
 * <p>Per slice 494 §6 shape (α): the projector does not compute the advisory id.
 * The central registry stamps {@code AdvisoryRecord.id = classId + ":" + dedupKey}.
 */
public record AdvisoryProjection(
    Instant occurredAt,
    Optional<String> diagnosticsLink,
    Optional<InvocationProvenance> provenance,
    Optional<OperationInvocation> primaryAction,
    Optional<String> primaryActionKind,
    Optional<String> bodyI18nKey,
    Map<String, Object> classExtras) {

  public AdvisoryProjection {
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(diagnosticsLink, "diagnosticsLink");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(primaryAction, "primaryAction");
    Objects.requireNonNull(primaryActionKind, "primaryActionKind");
    Objects.requireNonNull(bodyI18nKey, "bodyI18nKey");
    classExtras = classExtras == null ? Map.of() : Map.copyOf(classExtras);
  }

  public AdvisoryProjection(
      Instant occurredAt,
      Optional<String> diagnosticsLink,
      Optional<InvocationProvenance> provenance,
      Optional<OperationInvocation> primaryAction,
      Optional<String> bodyI18nKey,
      Map<String, Object> classExtras) {
    this(occurredAt, diagnosticsLink, provenance, primaryAction,
        Optional.empty(), bodyI18nKey, classExtras);
  }
}
