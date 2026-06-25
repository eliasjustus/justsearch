// SPDX-License-Identifier: Apache-2.0
/**
 * Agent domain API - Tool execution and agent session management
 */

import { request } from '../http';
import { AgentSessionSnapshotSchema, parseWireContract, validateWithFallback } from '../schemas';
import { agentHistoryResponseSchema } from '../generated/schema-types/agent-history-response.js';
import { agentSessionsResponseSchema } from '../generated/schema-types/agent-sessions-response.js';

// ============================================
// Types
// ============================================

export type AgentRiskLevel = 'low' | 'medium' | 'high';

export interface AgentToolDef {
  name: string;
  description: string;
  risk: AgentRiskLevel;
  supportsUndo: boolean;
  parameterSchema: Record<string, unknown>;
}

interface AgentToolsResponse {
  tools: AgentToolDef[];
  available: boolean;
}

export interface AgentBatchSummary {
  batchId: string;
  timestamp: string;
  explanation: string;
  operationCount: number;
  successCount: number;
  failedCount: number;
  skippedCount: number;
}

// Tempdoc 564 Phase 3: the wire envelope `AgentHistoryResponse` is the generated single authority
// (`generated/schema-types/agent-history-response.ts`); this module no longer hand-declares it
// (mandate gate). The ergonomic `AgentBatchSummary` item type is kept as the FE view.

export interface AgentUndoResult {
  success: boolean;
  output: string;
  executionId?: string;
}

// Tempdoc 415 follow-up (C20): persisted-session enumeration + resume-by-id.

export interface AgentTerminationReason {
  disposition: string;
  errorCode?: string | null;
  cancelTrigger?: string | null;
}

export interface AgentSessionSummary {
  sessionId: string;
  startedAt?: string;
  updatedAt?: string;
  state: string;
  resumable: boolean;
  iterationsUsed?: number;
  toolCallsExecuted?: number;
  totalTokensUsed?: number;
  activeAgentId?: string | null;
  terminationReason?: AgentTerminationReason | null;
  preview?: string;
}

export interface AgentSessionSnapshot extends AgentSessionSummary {
  messages?: Array<Record<string, unknown>>;
  agentProfiles?: Array<Record<string, unknown>>;
  selectedToolNames?: string[];
  maxIterations?: number;
  initialBudget?: number;
  handoffHistory?: Array<Record<string, unknown>>;
}

// Tempdoc 564 Phase 3: the wire envelope `AgentSessionsResponse` is the generated single authority
// (`generated/schema-types/agent-sessions-response.ts`); not hand-declared here (mandate gate).

// ============================================
// API Functions
// ============================================

// Tempdoc 491 C4 (2026-05-12): all agent endpoints moved from /api/agent/* to the unified
// /api/chat/* namespace. Wire shape unchanged; URL prefix only.

export async function getAgentTools(
  baseUrl: string,
  signal?: AbortSignal,
): Promise<AgentToolsResponse> {
  return request<AgentToolsResponse>(baseUrl, '/api/chat/agent/tools', { signal });
}

export async function approveToolCall(
  baseUrl: string,
  sessionId: string,
  callId: string,
): Promise<void> {
  await request(baseUrl, '/api/chat/approve', {
    method: 'POST',
    body: { sessionId, callId },
  });
}

export async function rejectToolCall(
  baseUrl: string,
  sessionId: string,
  callId: string,
  reason?: string,
): Promise<void> {
  await request(baseUrl, '/api/chat/reject', {
    method: 'POST',
    body: { sessionId, callId, reason: reason ?? 'User rejected' },
  });
}

export async function cancelAgentSession(
  baseUrl: string,
  sessionId: string,
): Promise<void> {
  await request(baseUrl, `/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
}

export async function undoToolExecution(
  baseUrl: string,
  toolName: string,
  executionId: string,
): Promise<AgentUndoResult> {
  return request<AgentUndoResult>(baseUrl, '/api/chat/agent/undo', {
    method: 'POST',
    body: { toolName, executionId },
  });
}

export async function getAgentHistory(
  baseUrl: string,
  limit = 20,
  signal?: AbortSignal,
): Promise<{ batches: AgentBatchSummary[] }> {
  const raw = await request<unknown>(
    baseUrl,
    `/api/chat/agent/history?limit=${limit}`,
    { signal },
  );
  return parseWireContract(
    agentHistoryResponseSchema,
    raw,
    `GET /api/chat/agent/history?limit=${limit}`,
  ) as unknown as { batches: AgentBatchSummary[] };
}

export async function getAgentBatchDetail(
  baseUrl: string,
  batchId: string,
  signal?: AbortSignal,
): Promise<Record<string, unknown>> {
  return request<Record<string, unknown>>(
    baseUrl,
    `/api/chat/agent/history/${encodeURIComponent(batchId)}`,
    { signal },
  );
}

// Tempdoc 415 follow-up (C20): list / detail wrappers.

export async function listAgentSessions(
  baseUrl: string,
  limit = 20,
  signal?: AbortSignal,
): Promise<{ sessions: AgentSessionSummary[] }> {
  const raw = await request<unknown>(
    baseUrl,
    `/api/chat/sessions?limit=${limit}`,
    { signal },
  );
  return parseWireContract(
    agentSessionsResponseSchema,
    raw,
    `GET /api/chat/sessions?limit=${limit}`,
  ) as unknown as { sessions: AgentSessionSummary[] };
}

export async function getAgentSessionSnapshot(
  baseUrl: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<AgentSessionSnapshot> {
  const raw = await request<unknown>(
    baseUrl,
    `/api/chat/sessions/${encodeURIComponent(sessionId)}`,
    { signal },
  );
  return validateWithFallback(
    AgentSessionSnapshotSchema,
    raw,
    `GET /api/chat/sessions/${sessionId}`,
  ) as AgentSessionSnapshot;
}

/**
 * Build a download URL for the bundled meta+events transcript (C33 V1).
 * The browser handles the download via {@code <a download>}; no JS state needed.
 */
export function agentSessionTranscriptUrl(
  baseUrl: string,
  sessionId: string,
): string {
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  return `${base}/api/chat/sessions/${encodeURIComponent(sessionId)}/transcript`;
}
