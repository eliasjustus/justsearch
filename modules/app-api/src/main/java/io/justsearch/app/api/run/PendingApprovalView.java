/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.run;

/**
 * One tool call held at an approval gate, carried on the enumerated run's primer (tempdoc 834
 * §5.1/§6.2).
 *
 * <p>{@code arguments} rides as a JSON <strong>string</strong>, not a {@code Map<String,Object>} —
 * the 564 chain retired the fail-open untyped hole ({@code WireRecordSchemaGenTest}: "record-backed
 * so the FE retires its fail-open {@code .loose()} hand-Zod"), and a free-form map on a typed record
 * would reopen it. That is also the shape the agent layer already emits.
 *
 * <p><strong>The component is named {@code arguments}, not {@code argumentsJson} as §5.1's sketch
 * spelled it</strong>: the controller projects with {@code MAPPER.convertValue} by component name
 * from the canonical {@code AgentEventPayloads.approvalMap} payload, whose key is {@code arguments}.
 * Renaming here would fork the vocabulary from the one the {@code tool_call_pending} frame already
 * puts on the wire — and would silently project to {@code null} rather than fail loudly.
 *
 * @param gateBehavior the backend gate verdict; {@code null} when no evaluator was available
 */
public record PendingApprovalView(
    String callId, String toolName, String arguments, String risk, String gateBehavior) {}
