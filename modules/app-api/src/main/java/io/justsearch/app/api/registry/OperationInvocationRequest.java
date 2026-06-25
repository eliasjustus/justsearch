/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Wire-format request for {@code POST /api/operations/{id}/invoke} (slice 3a-1-2).
 *
 * <p>The operation id is in the path; the body carries the runtime arguments plus
 * optional client-supplied keys for idempotency and HIGH-risk confirmation. {@code args}
 * is a free-shape JSON object whose schema is operation-specific and declared on the
 * Operation registry entry.
 *
 * <p>{@code idempotencyKey}: opt-in client-supplied UUID for backend deduplication. V1
 * accepts the field but does not yet enforce dedup (tracked as deferred follow-up; per
 * slice 3a-1-2 §A.5, OperationExecutorImpl has no dedup map).
 *
 * <p>{@code confirmationToken}: opt-in for HIGH-risk operations. The
 * {@code OperationPolicy.confirm} axis (per the registry entry) determines whether the
 * controller enforces presence; V1 trusts the FE ActionButton to gate UX and forwards
 * the typed-confirmation string here. Future slices add backend enforcement.
 *
 * <p>Stability: stable (API contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationInvocationRequest(
    Map<String, Object> args, String idempotencyKey, String confirmationToken) {

  public OperationInvocationRequest {
    args = args == null ? Map.of() : Map.copyOf(args);
  }
}
