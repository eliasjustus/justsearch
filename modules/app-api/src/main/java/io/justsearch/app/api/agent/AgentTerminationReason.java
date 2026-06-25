/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

/**
 * Why an agent session reached a terminal state (tempdoc 415 schema v3).
 *
 * <p>Wire shape of the {@code terminationReason} field on {@link AgentSessionSummary} — {@code null}
 * until the session terminates, then carries the disposition plus optional error/cancel context.
 *
 * <p>Tempdoc 564 Phase 3: the agent sessions/history surface becomes record-backed so the FE
 * validates it against a generated JSON-Schema → Zod projection instead of fail-open {@code .loose()}
 * hand-Zod. The wire JSON is unchanged — this record mirrors the {@code Map} the agent layer emits.
 */
public record AgentTerminationReason(String disposition, String errorCode, String cancelTrigger) {}
