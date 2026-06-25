/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

/**
 * One row of {@code GET /api/chat/sessions} — a persisted agent session, projected to a list-row
 * summary (the heavy {@code messages}/{@code agentProfiles}/{@code handoffHistory} fields are
 * dropped by {@code AgentRunStore.toSessionSummary}).
 *
 * <p>Tempdoc 564 Phase 3: this record mirrors the {@code Map} the agent layer emits, so the FE can
 * validate the surface against a generated JSON-Schema → Zod projection (retiring the fail-open
 * {@code .loose()} hand-Zod) without changing the wire JSON. Boxed numeric/boolean components are
 * nullable-on-wire (old sessions may omit a field), faithful to what the FE receives.
 */
public record AgentSessionSummary(
    String sessionId,
    String startedAt,
    String updatedAt,
    String state,
    Boolean resumable,
    Integer iterationsUsed,
    Integer toolCallsExecuted,
    Integer totalTokensUsed,
    String activeAgentId,
    AgentTerminationReason terminationReason,
    String preview) {}
