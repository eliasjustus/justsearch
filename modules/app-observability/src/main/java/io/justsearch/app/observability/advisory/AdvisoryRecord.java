/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Uniform wire shape for all advisory classes. Per slice 494 §5: every advisory
 * projector emits this record; FE switches on {@code classId} for class-specific
 * chrome.
 *
 * <p>Industry-validated shape: uniform record + discriminator + escape-hatch payload.
 * Apple {@code UNNotificationContent.userInfo}, Android {@code Notification.extras},
 * W3C {@code NotificationOptions.data}, GitHub {@code subject.type} all converge here.
 *
 * <p>Constructed by {@link AdvisoryChangeRegistry#project} from a projector's
 * {@link AdvisoryProjection} + metadata (classId, id, renderHint). The projector
 * never constructs this record directly.
 */
public record AdvisoryRecord(
    String classId,
    String id,
    Instant occurredAt,
    String renderHint,
    Optional<String> diagnosticsLink,
    Optional<InvocationProvenance> provenance,
    Optional<OperationInvocation> primaryAction,
    Optional<String> primaryActionKind,
    Optional<String> bodyI18nKey,
    Map<String, Object> classExtras) {

  public AdvisoryRecord {
    Objects.requireNonNull(classId, "classId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(renderHint, "renderHint");
    Objects.requireNonNull(diagnosticsLink, "diagnosticsLink");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(primaryAction, "primaryAction");
    Objects.requireNonNull(primaryActionKind, "primaryActionKind");
    Objects.requireNonNull(bodyI18nKey, "bodyI18nKey");
    classExtras = classExtras == null ? Map.of() : Map.copyOf(classExtras);
  }

  static AdvisoryRecord fromProjection(
      AdvisoryClassId classId,
      String id,
      String renderHint,
      AdvisoryProjection projection) {
    return new AdvisoryRecord(
        classId.value(),
        id,
        projection.occurredAt(),
        renderHint,
        projection.diagnosticsLink(),
        projection.provenance(),
        projection.primaryAction(),
        projection.primaryActionKind(),
        projection.bodyI18nKey(),
        projection.classExtras());
  }
}
