/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.agent;

/**
 * One row of {@code GET /api/chat/agent/history} — a summary of a persisted file-operation batch
 * (mirrors {@code AgentRunQueryService.toBatchSummary}).
 *
 * <p>Tempdoc 564 Phase 3: record-backed so the FE validates the surface against a generated
 * JSON-Schema → Zod projection instead of fail-open {@code .loose()} hand-Zod; the wire JSON is
 * unchanged.
 */
public record AgentBatchSummary(
    String batchId,
    String timestamp,
    String explanation,
    Integer operationCount,
    Long successCount,
    Long failedCount,
    Long skippedCount,
    Boolean finalized) {}
