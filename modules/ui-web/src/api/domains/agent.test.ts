/**
 * Unit tests for agent domain — tempdoc 415 follow-up (C20, C33, C44).
 *
 * Covers the Zod-validated parse paths for new endpoints and the transcript
 * URL helper, without spinning up a backend. Validates the contract surface
 * the UI relies on.
 */
import { describe, it, expect } from 'vitest';
import { AgentSessionSnapshotSchema } from '../schemas';
import { agentHistoryResponseSchema } from '../generated/schema-types/agent-history-response.js';
import { agentSessionsResponseSchema } from '../generated/schema-types/agent-sessions-response.js';
import { agentSessionTranscriptUrl } from './agent';

// Tempdoc 564 Phase 3: the sessions/history LIST surfaces are now validated against the GENERATED
// record→JSON-Schema→Zod projections (the single authority), not hand `.loose()` Zod.
//
// Note on the dropped C44 "rejects legacy failureCount (failedCount missing)" assertion: the
// generated schema makes `failedCount` nullable-optional (boxed `Long`), so it no longer *rejects*
// a payload missing that field at the FE tier — that FE-side runtime rejection is genuinely relaxed.
// The defect it guarded (a backend field rename `failedCount`→`failureCount`) is instead now
// structurally impossible: the field name is the Java record component `AgentBatchSummary.failedCount`,
// so a rename fails `WireRecordSchemaGenTest` (schema drift) at the source. Single source removes the
// FE↔backend drift class; the FE no longer needs (or has) a strict-rejection guard for it.
describe('agentHistoryResponseSchema (C44, generated)', () => {
  it('accepts a batch carrying the failedCount field', () => {
    const result = agentHistoryResponseSchema.safeParse({
      batches: [
        {
          batchId: 'b-1',
          timestamp: '2026-04-28T10:00:00Z',
          explanation: 'move files',
          operationCount: 3,
          successCount: 1,
          failedCount: 1,
          skippedCount: 1,
          finalized: true,
        },
      ],
    });
    expect(result.success).toBe(true);
    expect(result.data?.batches?.[0]?.failedCount).toBe(1);
  });

  it('accepts an empty batch list', () => {
    const result = agentHistoryResponseSchema.safeParse({ batches: [] });
    expect(result.success).toBe(true);
  });
});

describe('agentSessionsResponseSchema (C20, generated)', () => {
  it('accepts a minimal session row', () => {
    const result = agentSessionsResponseSchema.safeParse({
      sessions: [{ sessionId: 's-1', state: 'COMPLETED', resumable: false }],
    });
    expect(result.success).toBe(true);
  });

  it('accepts a complete row including terminationReason and preview', () => {
    const result = agentSessionsResponseSchema.safeParse({
      sessions: [
        {
          sessionId: 's-1',
          startedAt: '2026-04-28T10:00:00Z',
          updatedAt: '2026-04-28T10:01:00Z',
          state: 'WAITING_APPROVAL',
          resumable: true,
          iterationsUsed: 2,
          toolCallsExecuted: 1,
          totalTokensUsed: 120,
          activeAgentId: 'planner',
          terminationReason: {
            disposition: 'COMPLETED',
            errorCode: null,
            cancelTrigger: null,
          },
          preview: 'find files',
        },
      ],
    });
    expect(result.success).toBe(true);
  });
});

describe('AgentSessionSnapshotSchema (C20)', () => {
  it('AgentSessionSnapshotSchema accepts a snapshot with messages', () => {
    const result = AgentSessionSnapshotSchema.safeParse({
      sessionId: 's-1',
      state: 'READY_FOR_LLM',
      resumable: true,
      messages: [
        { role: 'user', content: 'hi' },
        { role: 'assistant', content: 'hello' },
      ],
    });
    expect(result.success).toBe(true);
  });
});

describe('agentSessionTranscriptUrl (C33)', () => {
  it('constructs a transcript URL with a non-trailing-slash base', () => {
    expect(agentSessionTranscriptUrl('http://localhost:33221', 'abc-123')).toBe(
      'http://localhost:33221/api/chat/sessions/abc-123/transcript',
    );
  });

  it('handles a trailing-slash base', () => {
    expect(agentSessionTranscriptUrl('http://localhost:33221/', 'abc-123')).toBe(
      'http://localhost:33221/api/chat/sessions/abc-123/transcript',
    );
  });

  it('encodes special characters in sessionId', () => {
    const url = agentSessionTranscriptUrl('http://x', 'a/b c');
    expect(url).toBe('http://x/api/chat/sessions/a%2Fb%20c/transcript');
  });
});
