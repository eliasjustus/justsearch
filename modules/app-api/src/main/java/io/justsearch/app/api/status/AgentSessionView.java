/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Currently-active agent session count for the {@code /api/status} endpoint.
 *
 * <p>Tempdoc 415: surfaced via the {@code agent.session.active_count} gauge declared with
 * {@code surfacedAt(StatusEndpoint.AGENT_SESSION_VIEW, "activeCount")}. The
 * {@code MetricSurfaceContractTest} (ArchUnit-style reflective rule) requires the field name
 * here to match the {@code surfacedAt} second argument byte-for-byte.
 *
 * <p>Stability: stable (API contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSessionView(int activeCount) {}
