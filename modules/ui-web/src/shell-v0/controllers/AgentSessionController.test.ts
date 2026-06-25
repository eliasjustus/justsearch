/**
 * @vitest-environment happy-dom
 *
 * Slice 495 — AgentSessionController tests.
 *
 * Migrated from AgentSurface.test.ts G1 harness + slice 495 Phase 1 extended
 * harness. Tests the controller directly — no DOM mounting needed.
 *
 * The controller implements CoreAgentRunHandlers; the handler methods
 * (onSessionStarted, onChunk, etc.) are called directly in these tests,
 * matching how dispatchShapeEventToHandlers routes SSE events to them.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentSessionController, sessionLabel } from './AgentSessionController.js';
import { setActiveRun, clearActiveRun } from './activeRunPointer.js';
// §32 unify — auto-approval is now driven by the autonomy dial level.
import {
  setAutonomyLevel,
  __resetAutonomyForTest,
} from '../substrates/autonomy/index.js';
// Tempdoc 550 C3 — non-auto-approved tool calls route through the unified ceremony host
// via the broker; tests register a presenter to control the human decision deterministically.
import {
  setAuthorizationPresenter,
  setAuthorizationCanceller,
} from '../operations/authorizationBroker.js';
// Tempdoc 605 — the run-conclusion notice routes through the 559 single system-message channel;
// mock it to assert it fires exactly once on a denial and never when nothing was drained.
vi.mock('../components/advisory/ephemeralToast.js', () => ({ emitEphemeralToast: vi.fn() }));
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
// 543-fwd idea #0 — the controller now bridges successful tool-calls into the
// (module-global) Effect Journal; reset it per-test for determinism.
import {
  listJournalByOriginator,
  getUndoableOperation,
  __resetJournalForTest,
} from '../substrates/effects/index.js';

// ---------- SSE mock helpers ----------

function sseChunk(event: string, data: object): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

function mockFetchSse(body: string): typeof fetch {
  return vi.fn(() => {
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(body));
        controller.close();
      },
    });
    return Promise.resolve(
      new Response(stream, {
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
      }),
    );
  }) as unknown as typeof fetch;
}

function mockFetchJson(data: unknown, status = 200): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } })),
  ) as unknown as typeof fetch;
}

function mockFetchError(status: number, body = ''): typeof fetch {
  return vi.fn(() =>
    Promise.resolve(new Response(body, { status })),
  ) as unknown as typeof fetch;
}

let notifyCount: number;
let ctrl: AgentSessionController;

beforeEach(() => {
  notifyCount = 0;
  __resetAutonomyForTest(); // autonomy level is module-global; reset for determinism
  __resetJournalForTest(); // journal is module-global; the bridge writes to it
  ctrl = new AgentSessionController('http://test', () => { notifyCount++; });
  if (typeof globalThis.fetch !== 'function') {
    globalThis.fetch = (() =>
      Promise.reject(new Error('fetch unmocked'))) as unknown as typeof fetch;
  }
});

afterEach(() => {
  ctrl.destroy();
  vi.restoreAllMocks();
  __resetAutonomyForTest();
  setAuthorizationPresenter(null);
});

// ==================== G1 migration: SSE event handling ====================

describe('AgentSessionController SSE handlers (G1 migration)', () => {
  // ===== 1. session_started =====
  it('onSessionStarted sets sessionId', () => {
    ctrl.onSessionStarted({ sessionId: 'sess-123' });
    expect(ctrl.sessionId).toBe('sess-123');
  });

  // ===== 2. chunk =====
  it('onChunk appends text to streamingText (multiple chunks concatenate)', () => {
    ctrl.onChunk({ text: 'Hello ' });
    ctrl.onChunk({ text: 'world' });
    expect(ctrl.streamingText).toBe('Hello world');
  });

  // ===== 3. tool_call_proposed =====
  it('onToolCallProposed adds a tool call with status="proposed"', () => {
    ctrl.onToolCallProposed({
      callId: 'c1', toolName: 'core_search_index', arguments: '{"query":"x"}', risk: 'LOW',
    });
    expect(ctrl.toolCalls.c1?.status).toBe('proposed');
    expect(ctrl.toolCalls.c1?.toolName).toBe('core_search_index');
    expect(ctrl.toolCalls.c1?.risk).toBe('LOW');
  });

  // ===== 3b. tool_batch_proposed (tempdoc 550 N1) =====
  it('onToolBatchProposed records the turn\'s proposed tool-call batch', () => {
    ctrl.onToolBatchProposed({
      calls: [
        { callId: 'c1', toolName: 'core_search_index' },
        { callId: 'c2', toolName: 'core_file_operations' },
      ],
    });
    expect(ctrl.currentToolBatch.map(c => c.toolName)).toEqual([
      'core_search_index',
      'core_file_operations',
    ]);
    expect(ctrl.currentToolBatch.map(c => c.callId)).toEqual(['c1', 'c2']);
  });

  it('clears currentToolBatch on done and on a new session (F2 — no stale plan)', () => {
    ctrl.onToolBatchProposed({ calls: [{ callId: 'c1', toolName: 'core_search_index' }] });
    expect(ctrl.currentToolBatch).toHaveLength(1);
    ctrl.onDone({ finalResponse: 'ok', iterationsUsed: 1, toolCallsExecuted: 1, totalTokensUsed: 0 });
    expect(ctrl.currentToolBatch).toHaveLength(0);
    // And a fresh run also starts clean.
    ctrl.onToolBatchProposed({ calls: [{ callId: 'c2', toolName: 'core_browse_folders' }] });
    expect(ctrl.currentToolBatch).toHaveLength(1);
    ctrl.onSessionStarted({ sessionId: 'sess-new' });
    expect(ctrl.currentToolBatch).toHaveLength(0);
  });

  // ===== 4. tool_call_pending =====
  it('onToolCallPending adds a tool call with status="pending" AND commits streamingText into a tool-call-group', () => {
    ctrl.onChunk({ text: 'Pre-tool text. ' });
    ctrl.onToolCallPending({
      callId: 'c2', toolName: 'core_browse_folders', arguments: '{"path":"/"}', risk: 'MEDIUM',
    });
    expect(ctrl.toolCalls.c2?.status).toBe('pending');
    const assistantText = ctrl.conversation.find(e => e.type === 'assistant-text');
    expect(assistantText?.content).toBe('Pre-tool text. ');
    const group = ctrl.conversation.find(e => e.type === 'tool-call-group');
    expect(group?.callIds).toContain('c2');
    expect(ctrl.streamingText).toBe('');
  });

  it('onToolCallProposed does NOT commit streamingText (only pending does)', () => {
    ctrl.onChunk({ text: 'Pre. ' });
    ctrl.onToolCallProposed({
      callId: 'cp', toolName: 'core_search_index', arguments: '{}', risk: 'LOW',
    });
    expect(ctrl.streamingText).toBe('Pre. ');
    expect(ctrl.conversation.find(e => e.type === 'tool-call-group')).toBeUndefined();
  });

  // ===== 5. tool_call_approved =====
  it('onToolCallApproved updates status to "approved" when toolCall exists', () => {
    ctrl.onToolCallPending({ callId: 'c3', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolCallApproved({ callId: 'c3' });
    expect(ctrl.toolCalls.c3?.status).toBe('approved');
  });

  it('onToolCallApproved is a no-op for unknown callIds', () => {
    ctrl.onToolCallApproved({ callId: 'never-seen' });
    expect(ctrl.toolCalls['never-seen']).toBeUndefined();
  });

  // ===== 6. tool_exec_started (dedup-checks grouping) =====
  it('onToolExecStarted commits streamingText when the call is NOT already grouped', () => {
    ctrl.onToolCallProposed({ callId: 'c4', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onChunk({ text: 'streaming midway ' });
    ctrl.onToolExecStarted({ callId: 'c4', toolName: 't' });
    expect(ctrl.toolCalls.c4?.status).toBe('executing');
    const group = ctrl.conversation.find(
      e => e.type === 'tool-call-group' && e.callIds?.includes('c4'),
    );
    expect(group).toBeDefined();
  });

  it('onToolExecStarted skips re-committing when the call is already grouped (pending → started)', () => {
    ctrl.onChunk({ text: 'pre. ' });
    ctrl.onToolCallPending({ callId: 'c5', toolName: 't', arguments: '{}', risk: 'LOW' });
    const groupsAfterPending = ctrl.conversation.filter(e => e.type === 'tool-call-group').length;
    ctrl.onToolExecStarted({ callId: 'c5', toolName: 't' });
    const groupsAfterStarted = ctrl.conversation.filter(e => e.type === 'tool-call-group').length;
    expect(groupsAfterStarted).toBe(groupsAfterPending);
    expect(ctrl.toolCalls.c5?.status).toBe('executing');
  });

  it('onToolExecStarted is a no-op for unknown callIds', () => {
    ctrl.onToolExecStarted({ callId: 'unknown', toolName: 't' });
    expect(ctrl.toolCalls.unknown).toBeUndefined();
  });

  // ===== 7. tool_exec_completed =====
  it('onToolExecCompleted sets status, success, output, executionId from result wrapper', () => {
    ctrl.onToolCallPending({ callId: 'c6', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({
      callId: 'c6', result: { success: true, output: 'result text', executionId: 'ex-1' },
    });
    expect(ctrl.toolCalls.c6?.status).toBe('completed');
    expect(ctrl.toolCalls.c6?.success).toBe(true);
    expect(ctrl.toolCalls.c6?.output).toBe('result text');
    expect(ctrl.toolCalls.c6?.executionId).toBe('ex-1');
  });

  it('onToolExecCompleted falls back to flat fields when result wrapper is absent', () => {
    ctrl.onToolCallPending({ callId: 'c6b', toolName: 't', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'c6b', success: false, output: 'failure text', executionId: 'ex-2' });
    expect(ctrl.toolCalls['c6b']?.success).toBe(false);
    expect(ctrl.toolCalls['c6b']?.output).toBe('failure text');
    expect(ctrl.toolCalls['c6b']?.executionId).toBe('ex-2');
  });

  // ===== 543-fwd idea #0: agent→journal bridge =====
  it('journals a successful tool-call as an originator:agent invoke-operation entry + wires undo via executionId', () => {
    ctrl.onToolCallPending({ callId: 'b1', toolName: 'core_search_index', arguments: '{"query":"x"}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b1', result: { success: true, output: 'ok', executionId: 'exec-9' } });
    const entries = listJournalByOriginator('agent');
    expect(entries).toHaveLength(1);
    expect(entries[0]!.effect).toEqual({ kind: 'invoke-operation', operationId: 'core_search_index', args: { query: 'x' } });
    expect(getUndoableOperation(entries[0]!.id)).toEqual({ operationId: 'core_search_index', executionId: 'exec-9' });
  });

  it('does NOT journal a failed tool-call (no state change to undo/digest)', () => {
    ctrl.onToolCallPending({ callId: 'b2', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b2', result: { success: false, output: 'err' } });
    expect(listJournalByOriginator('agent')).toHaveLength(0);
  });

  it('skips vop_ tools (they self-journal via the virtual path)', () => {
    ctrl.onToolCallPending({ callId: 'b3', toolName: 'vop_open_pane', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b3', result: { success: true, output: 'ok', executionId: 'exec-x' } });
    expect(listJournalByOriginator('agent')).toHaveLength(0);
  });

  it('journals without an executionId → entry exists but carries no undoable mapping', () => {
    ctrl.onToolCallPending({ callId: 'b4', toolName: 'core_browse_folders', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 'b4', result: { success: true, output: 'ok' } });
    const entries = listJournalByOriginator('agent');
    expect(entries).toHaveLength(1);
    expect(getUndoableOperation(entries[0]!.id)).toBeUndefined();
  });

  // ===== 543-fwd #6: causation enrichment — chain a turn's tool-calls =====
  it('chains tool-calls within a turn via causation; a new session starts a fresh chain', () => {
    ctrl.onSessionStarted({ sessionId: 'turn-1' });
    ctrl.onToolCallPending({ callId: 't1a', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't1a', result: { success: true, output: 'ok' } });
    ctrl.onToolCallPending({ callId: 't1b', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't1b', result: { success: true, output: 'ok' } });
    const turn1 = listJournalByOriginator('agent');
    expect(turn1).toHaveLength(2);
    expect(turn1[0]!.causation).toBeUndefined(); // first call = chain root
    expect(turn1[1]!.causation).toBe(turn1[0]!.id); // second chained to first
    // A new session resets the chain — its first call has no parent.
    ctrl.onSessionStarted({ sessionId: 'turn-2' });
    ctrl.onToolCallPending({ callId: 't2a', toolName: 'core_search_index', arguments: '{}', risk: 'LOW' });
    ctrl.onToolExecCompleted({ callId: 't2a', result: { success: true, output: 'ok' } });
    const all = listJournalByOriginator('agent');
    expect(all).toHaveLength(3);
    expect(all[2]!.causation).toBeUndefined(); // new turn = fresh root
  });

  // ===== 8. tool_call_rejected =====
  it('onToolCallRejected sets status="rejected" with rejectReason', () => {
    ctrl.onToolCallPending({ callId: 'c7', toolName: 't', arguments: '{}', risk: 'HIGH' });
    ctrl.onToolCallRejected({ callId: 'c7', reason: 'user denied' });
    expect(ctrl.toolCalls.c7?.status).toBe('rejected');
    expect(ctrl.toolCalls.c7?.rejectReason).toBe('user denied');
  });

  // ===== 9. done (dedup against streamingText) =====
  it('onDone commits streamingText, then appends assistant-text when finalResp differs', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'partial stream' });
    ctrl.onDone({ finalResponse: 'final canonical response', iterationsUsed: 3, toolCallsExecuted: 1, totalTokensUsed: 1234 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['partial stream', 'final canonical response']);
    expect(ctrl.iterationsUsed).toBe(3);
    expect(ctrl.toolCallsExecuted).toBe(1);
    expect(ctrl.totalTokensUsed).toBe(1234);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('onDone does NOT double-append when finalResp === streamingText.trim (dedup invariant)', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'identical text' });
    ctrl.onDone({ finalResponse: 'identical text', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['identical text']);
  });

  it('onDone with no finalResponse still commits streamingText + clears isStreaming', () => {
    ctrl.isStreaming = true;
    ctrl.onChunk({ text: 'only stream' });
    ctrl.onDone({ finalResponse: '', iterationsUsed: 0, toolCallsExecuted: 0, totalTokensUsed: 0 });
    const assistantTexts = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantTexts.map(e => e.content)).toEqual(['only stream']);
    expect(ctrl.isStreaming).toBe(false);
  });

  // Tempdoc 565 §27.2 — the PRODUCTION-side guard for the zero-padded entry id. The projection-side
  // tie test (unifiedThreadProjection.test.ts) proves the sort contract with a local pad() helper;
  // this one pins that `nextEntryId()` itself emits the padded format, so a revert of the
  // `padStart(6,'0')` in AgentSessionController would fail here, not slip through (the reviewer's
  // §28 MINOR: the emitter format must be guarded, not just the consumer's assumption about it).
  it('nextEntryId emits zero-padded ids so lexical order == temporal order past the 9->10 boundary', () => {
    // onDone appends one assistant-text via nextEntryId() per distinct finalResponse (no stream to dedup).
    for (let i = 1; i <= 12; i++) {
      ctrl.onDone({ finalResponse: `response ${i}`, iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    }
    const ids = ctrl.conversation.filter(e => e.type === 'assistant-text').map(e => e.id);
    expect(ids.length).toBeGreaterThan(9); // crossed the 9->10 boundary where padding matters
    // Every production id is zero-padded to 6 digits — an unpadded `e-10` would fail this regex.
    for (const id of ids) expect(id).toMatch(/^e-\d{6}$/);
    // Insertion order is temporal; assert it is ALSO lexical order. The pre-fix `e-${n}` format
    // would invert here (`e-10` < `e-9` lexically), so this distinguishes the right reason.
    expect([...ids].sort()).toEqual(ids);
  });

  // Tempdoc 565 §3.A / §13.8 — the typed onDone reads the now-truthful done-event grounding fields
  // (was a loose Record<unknown> cast). These pin the source/citation population the de-risk + the
  // live browser check confirmed but no unit test covered.
  it('onDone populates answerSources + answerCitations from the typed done payload', () => {
    ctrl.onDone({
      finalResponse: 'a [1]',
      iterationsUsed: 2,
      toolCallsExecuted: 1,
      totalTokensUsed: 10,
      sources: [
        { parentDocId: 'd1', chunkIndex: 0, path: '/a.md', title: 'A', excerpt: 'x', startLine: 5, endLine: 9, headingText: '' },
        { parentDocId: 'd2', chunkIndex: 1, path: '/b.md', title: 'B', excerpt: 'y', startLine: 1, endLine: 4, headingText: '' },
      ],
      citations: [{ sentenceText: 'a', sourceIndex: 0, similarity: 0.9 }],
    });
    expect(ctrl.answerSources.map(s => s.parentDocId)).toEqual(['d1', 'd2']);
    expect(ctrl.answerSources[0]!.startLine).toBe(5);
    expect(ctrl.answerCitations).toHaveLength(1);
  });

  it('onDone with absent grounding fields → empty answerSources/answerCitations (ungrounded run)', () => {
    ctrl.onDone({ finalResponse: 'a', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    expect(ctrl.answerSources).toEqual([]);
    expect(ctrl.answerCitations).toEqual([]);
  });

  // ===== 10. budget_update =====
  it('onBudgetUpdate appends to budgetUpdates with default-zero filling', () => {
    ctrl.onBudgetUpdate({ phase: 'p1', tokensConsumed: 100, tokensRemaining: 900 });
    ctrl.onBudgetUpdate({ phase: 'p2', tokensConsumed: 50 });
    expect(ctrl.budgetUpdates).toEqual([
      { phase: 'p1', tokensConsumed: 100, tokensRemaining: 900 },
      { phase: 'p2', tokensConsumed: 50, tokensRemaining: 0 },
    ]);
  });

  // Tempdoc 577 Move 2 — the held budget gate.
  it('onBudgetGate parks the run; a budget_update or new run clears it', () => {
    ctrl.onBudgetGate({ tokensNeeded: 1200, tokensRemaining: -700, totalTokensConsumed: 6700 });
    expect(ctrl.budgetGate).toEqual({
      tokensNeeded: 1200,
      tokensRemaining: -700,
      totalTokensConsumed: 6700,
    });
    // Any budget movement means the gate resolved (CONTINUE emits the next iteration_start).
    ctrl.onBudgetUpdate({ phase: 'iteration_start', tokensConsumed: 10, tokensRemaining: 3000 });
    expect(ctrl.budgetGate).toBeNull();
  });

  // ===== 11. error =====
  it('onError appends an error entry + clears isStreaming', () => {
    ctrl.isStreaming = true;
    ctrl.onError({ error: 'AI_OFFLINE', errorCode: 'AI_OFFLINE' });
    const errorEntry = ctrl.conversation.find(e => e.type === 'error');
    expect(errorEntry?.content).toBe('AI_OFFLINE');
    expect(errorEntry?.errorCode).toBe('AI_OFFLINE');
    expect(ctrl.isStreaming).toBe(false);
  });

  it('onError with no error field falls back to "Unknown error"', () => {
    ctrl.onError({});
    const errorEntry = ctrl.conversation.find(e => e.type === 'error');
    expect(errorEntry?.content).toBe('Unknown error');
  });

  // ===== 12. progress =====
  it('onProgress appends a progress entry with message or falls back to phase', () => {
    ctrl.onProgress({ message: 'doing things' });
    ctrl.onProgress({ phase: 'phase-2' });
    const progressEntries = ctrl.conversation.filter(e => e.type === 'progress');
    expect(progressEntries.map(e => e.content)).toEqual(['doing things', 'phase-2']);
  });

  // Tempdoc 561 #5: severity rides the wire so the renderer decorates by intent (no ⚠ on routine).
  it('onProgress carries the backend severity (undefined when absent)', () => {
    ctrl.onProgress({ message: 'routine', severity: 'info' });
    ctrl.onProgress({ message: 'a failure', severity: 'error' });
    ctrl.onProgress({ message: 'no severity field' });
    const progress = ctrl.conversation.filter(e => e.type === 'progress');
    expect(progress.map(e => e.severity)).toEqual(['info', 'error', undefined]);
  });

  // ===== 13. handoff_proposed =====
  it('onHandoffProposed appends a handoff entry with from/to/reason in content', () => {
    ctrl.onHandoffProposed({ fromAgentId: 'manager', toAgentId: 'specialist', reason: 'needs domain expertise' });
    const handoff = ctrl.conversation.find(e => e.type === 'handoff');
    expect(handoff?.content).toBe('Handoff: manager → specialist: needs domain expertise');
    expect(handoff?.fromAgentId).toBe('manager');
    expect(handoff?.toAgentId).toBe('specialist');
  });

  // ===== 14. handoff_executed =====
  it('onHandoffExecuted sets activeAgentId to toAgentId', () => {
    ctrl.onHandoffExecuted({ fromAgentId: 'manager', toAgentId: 'specialist' });
    expect(ctrl.activeAgentId).toBe('specialist');
  });

  // ===== Cross-cutting: tool_call_pending side-effect sets sessionId =====
  it('onToolCallPending populates sessionId when it arrives in the event payload', () => {
    expect(ctrl.sessionId).toBeNull();
    ctrl.onToolCallPending({
      callId: 'c8', toolName: 't', arguments: '{}', risk: 'LOW', sessionId: 'sess-from-tool-pending',
    });
    expect(ctrl.sessionId).toBe('sess-from-tool-pending');
  });
});

// ==================== Extended harness: interaction methods ====================

describe('sessionLabel (561 #4 — human label, never the raw UUID)', () => {
  it('uses the backend first-user-message preview when present', () => {
    expect(sessionLabel({ sessionId: 'abc-123', preview: 'find my tax docs' })).toBe(
      'find my tax docs',
    );
  });
  it('falls back to initialMessage, then a neutral label — never the UUID', () => {
    expect(sessionLabel({ sessionId: 'abc-123', initialMessage: 'older field' })).toBe(
      'older field',
    );
    const label = sessionLabel({ sessionId: '475d1d1f-aaaa-bbbb-cccc-ddddeeeeffff' });
    expect(label).toBe('Untitled session');
    expect(label).not.toContain('475d1d1f');
  });
  it('treats a blank preview as absent', () => {
    expect(sessionLabel({ sessionId: 'abc-123', preview: '   ' })).toBe('Untitled session');
  });
});

describe('AgentSessionController interaction methods', () => {
  // ===== checkAvailability =====
  it('checkAvailability sets available=true and populates tools on 200', async () => {
    globalThis.fetch = mockFetchJson({ available: true, tools: [{ name: 'search' }, { name: 'browse' }] });
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(true);
    expect(ctrl.tools).toEqual([{ name: 'search' }, { name: 'browse' }]);
  });

  it('checkAvailability sets available=false on non-200', async () => {
    globalThis.fetch = mockFetchError(503);
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(false);
    expect(ctrl.tools).toEqual([]);
  });

  it('checkAvailability sets available=false on fetch error', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('network'))) as unknown as typeof fetch;
    await ctrl.checkAvailability();
    expect(ctrl.available).toBe(false);
    expect(ctrl.tools).toEqual([]);
  });

  // ===== send (full SSE round-trip via consumeShapeStream) =====
  it('send posts message and processes SSE events into state', async () => {
    const body =
      sseChunk('session_started', { sessionId: 'sess-1' }) +
      sseChunk('chunk', { text: 'Response text' }) +
      sseChunk('done', { finalResponse: 'Response text', iterationsUsed: 1, toolCallsExecuted: 0 });
    const fetchSpy = mockFetchSse(body);
    globalThis.fetch = fetchSpy;

    await ctrl.send('hello agent');

    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/agent');
    expect(ctrl.sessionId).toBe('sess-1');
    expect(ctrl.isStreaming).toBe(false);
    expect(ctrl.iterationsUsed).toBe(1);
    const userEntries = ctrl.conversation.filter(e => e.type === 'user');
    expect(userEntries.length).toBe(1);
    expect(userEntries[0]?.content).toBe('hello agent');
    const assistantEntries = ctrl.conversation.filter(e => e.type === 'assistant-text');
    expect(assistantEntries.length).toBe(1);
  });

  it('send appends error entry on non-200 HTTP (via consumeShapeStream throw)', async () => {
    globalThis.fetch = mockFetchError(500, 'Internal Server Error');
    await ctrl.send('test');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('send does not double-append error when SSE error event fires + consumeShapeStream throws (D1)', async () => {
    const body = sseChunk('error', { error: 'AI_OFFLINE', errorCode: 'AI_OFFLINE' });
    globalThis.fetch = mockFetchSse(body);
    await ctrl.send('test');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toBe('AI_OFFLINE');
  });

  // ===== Tempdoc 577 Root I (#13/#1d): reattach on a mid-run stream drop =====
  it('reattaches to the live run when the stream DROPS mid-run (after session_started)', async () => {
    const encoder = new TextEncoder();
    let call = 0;
    const fetchSpy = vi.fn((_url: string) => {
      call += 1;
      if (call === 1) {
        // The send stream establishes the run (session_started) then DROPS: it ends WITHOUT a
        // terminal (done/error) event — exactly a mid-run socket drop. consumeShapeStream processes
        // session_started, then throws STREAM_INCOMPLETE (not an AbortError) → the reattach path.
        const stream = new ReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(encoder.encode(sseChunk('session_started', { sessionId: 'sess-1' })));
            c.close();
          },
        });
        return Promise.resolve(
          new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
        );
      }
      // The reattach stream: the run already ended, so the backend signals not-live (graceful).
      const stream2 = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(encoder.encode(sseChunk('attach_not_live', { sessionId: 'sess-1', reason: 'not-live' })));
          c.close();
        },
      });
      return Promise.resolve(
        new Response(stream2, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
      );
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    await ctrl.send('do the work');

    // A second fetch fired — the reattach to /attach, NOT a false error on the FE.
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect((fetchSpy.mock.calls[1]![0] as string)).toContain('/api/chat/agent/sess-1/attach');
    // The drop was NOT surfaced as a conversation error (the run was reattached, then fell back cleanly).
    expect(ctrl.conversation.filter((e) => e.type === 'error').length).toBe(0);
    expect(ctrl.isStreaming).toBe(false);
  });

  it('does NOT reattach when the initial send POST fails (no run established)', async () => {
    // A 500 on the initial POST means no run exists; with no session_started this stream, the
    // reattach guard (runStartedThisStream) is false, so it surfaces an error instead of reattaching.
    ctrl.sessionId = 'stale-prior'; // a stale id from a prior run must NOT trigger a wrong reattach
    const fetchSpy = mockFetchError(500, 'Internal Server Error');
    globalThis.fetch = fetchSpy;
    await ctrl.send('new message');
    expect(fetchSpy).toHaveBeenCalledTimes(1); // no reattach attempt
    expect(ctrl.conversation.filter((e) => e.type === 'error').length).toBe(1);
  });

  // ===== Tempdoc 577 Root I (#1d): cross-tab reattach on load =====
  it('reattaches on load to a live run ANOTHER tab started (via the activeRunPointer)', async () => {
    localStorage.clear();
    setActiveRun('sess-x'); // a different tab published its live run to the shared pointer
    const encoder = new TextEncoder();
    const fetchSpy = vi.fn((_url: string) => {
      const stream = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(encoder.encode(sseChunk('attach_not_live', { sessionId: 'sess-x', reason: 'not-live' })));
          c.close();
        },
      });
      return Promise.resolve(
        new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
      );
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;

    await ctrl.reattachActiveRunOnLoad();

    // This tab (no run of its own) attached to the OTHER tab's run via the /attach endpoint.
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0]![0] as string).toContain('/api/chat/agent/sess-x/attach');
  });

  it('does NOT reattach on load when this tab already owns/observes a run', async () => {
    localStorage.clear();
    setActiveRun('sess-y');
    ctrl.isStreaming = true; // this tab is already streaming its own run — must not steal/double-attach
    const fetchSpy = vi.fn();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('does nothing on load when there is no active-run pointer', async () => {
    localStorage.clear();
    clearActiveRun();
    const fetchSpy = vi.fn();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('reattaches when the pointer conversation matches this tab', async () => {
    localStorage.clear();
    setActiveRun('sess-m', 'conv-1');
    ctrl.conversationId = 'conv-1';
    const encoder = new TextEncoder();
    const fetchSpy = vi.fn((_url: string) => {
      const stream = new ReadableStream<Uint8Array>({
        start(c) {
          c.enqueue(encoder.encode(sseChunk('attach_not_live', { sessionId: 'sess-m', reason: 'not-live' })));
          c.close();
        },
      });
      return Promise.resolve(
        new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
      );
    });
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0]![0] as string).toContain('/api/chat/agent/sess-m/attach');
  });

  it('does NOT reattach when the pointer belongs to a DIFFERENT conversation', async () => {
    localStorage.clear();
    setActiveRun('sess-a', 'conv-A');
    ctrl.conversationId = 'conv-B'; // this tab is pinned to a different conversation
    const fetchSpy = vi.fn();
    globalThis.fetch = fetchSpy as unknown as typeof fetch;
    await ctrl.reattachActiveRunOnLoad();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  // ===== approveCall / rejectCall =====
  it('approveCall sends POST with sessionId and callId', async () => {
    ctrl.sessionId = 'sess-a';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.approveCall('call-1');
    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/approve');
    const body = JSON.parse(callArgs[1].body);
    expect(body.sessionId).toBe('sess-a');
    expect(body.callId).toBe('call-1');
  });

  it('approveCall is a no-op when sessionId is null', async () => {
    ctrl.sessionId = null;
    const fetchSpy = vi.fn() as unknown as typeof fetch;
    globalThis.fetch = fetchSpy;
    await ctrl.approveCall('call-1');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('rejectCall sends POST with sessionId, callId, and reason', async () => {
    ctrl.sessionId = 'sess-b';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.rejectCall('call-2', 'not now');
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/reject');
    const body = JSON.parse(callArgs[1].body);
    expect(body.reason).toBe('not now');
  });

  // Tempdoc 565 §30 — the DIRECTION authority's interject.
  it('steer sends POST to /api/chat/agent/steer with sessionId and text', async () => {
    ctrl.sessionId = 'sess-steer';
    const fetchSpy = mockFetchJson({ status: 'injected' });
    globalThis.fetch = fetchSpy;
    const ok = await ctrl.steer('  Focus only on Q3.  ');
    expect(ok).toBe(true);
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toBe('http://test/api/chat/agent/steer');
    const body = JSON.parse(callArgs[1].body);
    expect(body.sessionId).toBe('sess-steer');
    expect(body.text).toBe('Focus only on Q3.'); // trimmed
    // §33 — a SUCCESSFUL steer must NOT append a failure note (the §33 note is for the 404 path only;
    // this pins "passes for the right reason" so a future regression can't make every steer noisy).
    expect(
      ctrl.conversation.filter(
        (e) => e.type === 'progress' && /could not steer/i.test(String(e.content ?? '')),
      ),
    ).toHaveLength(0);
  });

  it('steer is a no-op (returns false, no fetch) when there is no live session', async () => {
    ctrl.sessionId = null;
    const fetchSpy = vi.fn() as unknown as typeof fetch;
    globalThis.fetch = fetchSpy;
    expect(await ctrl.steer('go')).toBe(false);
    expect(await ctrl.steer('   ')).toBe(false); // blank
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('onDirectiveAcknowledged appends a human-origin steer-directive entry', () => {
    ctrl.onDirectiveAcknowledged({ directiveText: 'Focus only on Q3.' });
    const steer = ctrl.conversation.filter((e) => e.type === 'steer-directive');
    expect(steer.length).toBe(1);
    expect(steer[0]?.content).toBe('Focus only on Q3.');
    // a blank/absent text is ignored (no phantom entry)
    ctrl.onDirectiveAcknowledged({});
    expect(ctrl.conversation.filter((e) => e.type === 'steer-directive').length).toBe(1);
  });

  // Tempdoc 565 §33 — a failed steer (404: the run finished) must surface a note, not be silent.
  it('steer surfaces a system note when the POST fails (404)', async () => {
    ctrl.sessionId = 'gone';
    globalThis.fetch = mockFetchJson({ error: 'session not found' }, 404);
    const ok = await ctrl.steer('focus on Q3');
    expect(ok).toBe(false);
    const notes = ctrl.conversation.filter(
      (e) => e.type === 'progress' && /could not steer/i.test(e.content),
    );
    expect(notes.length).toBe(1);
    expect(notes[0]?.severity).toBe('warn');
  });

  // Tempdoc 565 §33 — runKind: only an `agent` run is steerable; the view gates the steer input on it.
  it('runKind: defaults null, set on start, cleared on terminal', () => {
    expect(ctrl.runKind).toBeNull();
    globalThis.fetch = mockFetchSse('event: done\ndata: {}\n\n');
    void ctrl.send('hi'); // set synchronously before the stream await
    expect(ctrl.runKind).toBe('agent');
    ctrl.onDone({ finalResponse: 'x', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    expect(ctrl.runKind).toBeNull();
  });

  it('runKind: runWorkflow marks the run NOT steerable (workflow)', () => {
    globalThis.fetch = mockFetchSse('event: done\ndata: {}\n\n');
    void ctrl.runWorkflow('core.demo-compose');
    expect(ctrl.runKind).toBe('workflow');
    ctrl.onError({ error: 'boom' });
    expect(ctrl.runKind).toBeNull();
  });

  // Fix B (issue 2 robustness) — a non-ok approve/reject POST must SURFACE an error, not be swallowed
  // (a stale backend without the §15.J unified route 404s, otherwise leaving the card stuck PENDING).
  it('approveCall surfaces an error when the POST is not ok (no silent stuck-PENDING)', async () => {
    ctrl.sessionId = 'sess-x';
    globalThis.fetch = mockFetchJson({}, 404);
    await ctrl.approveCall('call-1');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toMatch(/approval/i);
  });

  it('rejectCall surfaces an error when the POST is not ok', async () => {
    ctrl.sessionId = 'sess-x';
    globalThis.fetch = mockFetchJson({}, 500);
    await ctrl.rejectCall('call-1', 'no');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(errors[0]?.content).toMatch(/rejection/i);
  });

  // Fix A (issue 1) — the single-window chat agent must issue a SINGLE-agent run request: an empty
  // agentProfiles + no initialAgentId, so the backend cannot treat it as a handed-off sub-agent and
  // force `core_ingest_files` via the E0a multi-agent policy. A lone non-primary profile was the bug.
  it('send issues a single-agent run request (empty agentProfiles, no initialAgentId)', async () => {
    const fetchSpy = mockFetchSse('');
    globalThis.fetch = fetchSpy;
    await ctrl.send('hello');
    const call = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.find((c) =>
      String(c[0]).includes('/api/chat/agent'),
    );
    expect(call).toBeTruthy();
    const body = JSON.parse((call as unknown as [string, { body: string }])[1].body);
    expect(body.agentProfiles).toEqual([]);
    expect(body.initialAgentId).toBeUndefined();
  });

  // ===== cancelSession =====
  it('cancelSession commits streaming text and sends DELETE for session', async () => {
    ctrl.sessionId = 'sess-c';
    ctrl.isStreaming = true;
    ctrl.streamingText = 'partial';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    await ctrl.cancelSession();
    expect(ctrl.isStreaming).toBe(false);
    const committed = ctrl.conversation.find(e => e.type === 'assistant-text');
    expect(committed?.content).toBe('partial');
    expect(fetchSpy).toHaveBeenCalled();
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toContain('/api/chat/sessions/sess-c');
    expect(callArgs[1].method).toBe('DELETE');
  });

  // ===== loadSessions / loadHistory =====
  it('loadSessions populates sessions array', async () => {
    globalThis.fetch = mockFetchJson({ sessions: [{ sessionId: 's1' }, { sessionId: 's2' }] });
    await ctrl.loadSessions();
    expect(ctrl.sessions.length).toBe(2);
    expect(ctrl.sessions[0]?.sessionId).toBe('s1');
  });

  // Tempdoc 561 P-B1: History is a projection of the ONE action ledger, filtered to this session
  // via the cross-domain correlationId join key — not the old FileOperationLog-backed
  // /api/chat/agent/history (which is exactly why a completed search left History empty).
  it('loadHistory projects agent operation rows from the one action ledger, filtered to the session', async () => {
    ctrl.sessionId = 'sess-xyz';
    let requestedUrl = '';
    globalThis.fetch = (async (url: string) => {
      requestedUrl = String(url);
      return {
        ok: true,
        json: async () => ({
          entries: [
            {
              id: 'operation:t1:core.search-index:SUCCESS',
              kind: 'operation',
              occurredAt: '2026-05-30T00:00:01Z',
              originator: 'agent',
              operationId: 'core.search-index',
              outcome: 'SUCCESS',
              correlationId: 'sess-xyz',
            },
            // a non-operation row in the same session must be excluded from the tool-call History
            {
              id: 'gate:t2:core.ingest:GATED',
              kind: 'gate',
              occurredAt: '2026-05-30T00:00:02Z',
              originator: 'agent',
              correlationId: 'sess-xyz',
            },
          ],
        }),
      } as Response;
    }) as typeof fetch;

    await ctrl.loadHistory();

    // requests the session-scoped, agent-only projection of the one ledger
    expect(requestedUrl).toContain('/api/action-ledger');
    expect(requestedUrl).toContain('originator=agent');
    expect(requestedUrl).toContain('correlationId=sess-xyz');
    // only the operation row maps; the gate row is excluded
    expect(ctrl.history.length).toBe(1);
    // tempdoc 558 §S1 — History now projects through the ONE shared projection (UnifiedActionEntry):
    // the outcome is a structured field and the operation id is humanized into the label (no raw id).
    expect(ctrl.history[0]?.id).toBe('operation:t1:core.search-index:SUCCESS');
    expect(ctrl.history[0]?.outcome).toBe('SUCCESS');
    expect(ctrl.history[0]?.label).toContain('Search Index');
    expect(ctrl.history[0]?.label).not.toContain('core.search-index');
  });

  // Tempdoc 561 P-B2: Timeline is a DISTINCT projection of the one ledger (the workspace activity
  // stream), not a copy of the Sessions roster.
  it('loadTimeline projects the workspace activity stream from the one ledger', async () => {
    let requestedUrl = '';
    globalThis.fetch = (async (url: string) => {
      requestedUrl = String(url);
      return {
        ok: true,
        json: async () => ({
          entries: [
            {
              id: 'operation:t1:core.search-index:SUCCESS',
              kind: 'operation',
              occurredAt: '2026-05-30T00:00:01Z',
              originator: 'agent',
              operationId: 'core.search-index',
              outcome: 'SUCCESS',
            },
            {
              id: 'index:t2:default:DONE',
              kind: 'index',
              occurredAt: '2026-05-30T00:00:02Z',
              originator: 'system',
              collection: 'default',
              state: 'DONE',
            },
          ],
        }),
      } as Response;
    }) as typeof fetch;

    await ctrl.loadTimeline();

    expect(requestedUrl).toContain('/api/action-ledger');
    // both ledger kinds appear in the activity stream (distinct from the Sessions roster)
    expect(ctrl.timeline.length).toBe(2);
    expect(ctrl.timeline.map(e => e.kind)).toEqual(expect.arrayContaining(['operation', 'index']));
    expect(ctrl.timeline.every(e => typeof e.label === 'string' && e.label.length > 0)).toBe(true);
  });

  it('loadHistory is empty (no fetch) when there is no active session', async () => {
    ctrl.sessionId = null;
    let fetched = false;
    globalThis.fetch = (async () => {
      fetched = true;
      return { ok: true, json: async () => ({ entries: [] }) } as Response;
    }) as typeof fetch;
    await ctrl.loadHistory();
    expect(fetched).toBe(false);
    expect(ctrl.history.length).toBe(0);
  });

  // ===== resumeSession =====
  it('resumeSession consumes SSE and sets session state', async () => {
    const body =
      sseChunk('session_started', { sessionId: 'sess-resumed' }) +
      sseChunk('chunk', { text: 'Resumed text' }) +
      sseChunk('done', { finalResponse: 'Resumed text', iterationsUsed: 2, toolCallsExecuted: 0 });
    globalThis.fetch = mockFetchSse(body);
    await ctrl.resumeSession('sess-old');
    expect(ctrl.sessionId).toBe('sess-resumed');
    expect(ctrl.isStreaming).toBe(false);
  });

  it('resumeSession appends error on non-200 HTTP', async () => {
    globalThis.fetch = mockFetchError(404);
    await ctrl.resumeSession('sess-gone');
    const errors = ctrl.conversation.filter(e => e.type === 'error');
    expect(errors.length).toBe(1);
    expect(ctrl.isStreaming).toBe(false);
  });

  // ===== notify dedup =====
  it('notify batches multiple synchronous mutations into one onUpdate call', async () => {
    ctrl.onChunk({ text: 'a' });
    ctrl.onChunk({ text: 'b' });
    ctrl.onChunk({ text: 'c' });
    expect(notifyCount).toBe(0);
    await new Promise(r => setTimeout(r, 0));
    expect(notifyCount).toBe(1);
  });

  // ===== lifecycle =====
  it('destroy stops polling and aborts active stream', () => {
    ctrl.startPolling();
    ctrl.destroy();
    ctrl.destroy();
  });

  // ===== fork =====
  it('fork returns a shallow copy of conversation up to fromIndex', () => {
    ctrl.onChunk({ text: 'hello' });
    ctrl.onDone({ finalResponse: 'hello', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 });
    ctrl.conversation = [
      ...ctrl.conversation,
      { id: 'u1', type: 'user' as const, content: 'msg1', timestamp: 1 },
      { id: 'a1', type: 'assistant-text' as const, content: 'resp1', timestamp: 2 },
      { id: 'u2', type: 'user' as const, content: 'msg2', timestamp: 3 },
      { id: 'a2', type: 'assistant-text' as const, content: 'resp2', timestamp: 4 },
    ];
    const forked = ctrl.fork(1);
    expect(forked.length).toBe(2);
    expect(forked[0]?.content).toBe(ctrl.conversation[0]?.content);
    expect(forked[1]?.content).toBe(ctrl.conversation[1]?.content);
  });

  it('fork returns empty array when fromIndex is -1', () => {
    ctrl.conversation = [{ id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1 }];
    const forked = ctrl.fork(-1);
    expect(forked.length).toBe(0);
  });

  it('fork returns full conversation when fromIndex exceeds length', () => {
    ctrl.conversation = [{ id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1 }];
    const forked = ctrl.fork(100);
    expect(forked.length).toBe(1);
  });

  // ===== loadForkedConversation =====
  it('loadForkedConversation resets state and retains referenced toolCalls', () => {
    ctrl.toolCalls = {
      'c1': { callId: 'c1', toolName: 'search', arguments: '{}', risk: 'LOW', status: 'completed' },
      'c2': { callId: 'c2', toolName: 'browse', arguments: '{}', risk: 'LOW', status: 'completed' },
    };
    const entries = [
      { id: 'u1', type: 'user' as const, content: 'msg', timestamp: 1, callIds: undefined },
      { id: 'g1', type: 'tool-call-group' as const, content: '', callIds: ['c1'], timestamp: 2 },
    ];
    ctrl.loadForkedConversation(entries);
    expect(ctrl.conversation.length).toBe(2);
    expect(ctrl.toolCalls['c1']).toBeDefined();
    expect(ctrl.toolCalls['c2']).toBeUndefined();
    expect(ctrl.sessionId).toBeNull();
    expect(ctrl.isStreaming).toBe(false);
  });

  // ===== auto-approval driven by the autonomy dial (§32 unify) =====
  it('backend AUTO verdict: queues callId when sessionId is null, flushes (approves) on session_started', async () => {
    // Tempdoc 561 P-D collapse: the FE OBEYS the backend gateBehavior. The backend decided AUTO
    // (e.g. a read-only call under assist) — the FE auto-approves; it no longer re-derives from risk.
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({
      callId: 'auto1', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'auto',
    });
    // sessionId is null → should NOT have called fetch (approveCall returns early; callId queued)
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0);

    // Now session starts → should flush the queue
    ctrl.onSessionStarted({ sessionId: 'sess-x' });
    await new Promise(r => setTimeout(r, 10));
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1);
    const callArgs = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(callArgs[0]).toContain('/approve');
  });

  it('the backend verdict is the SOLE auto-approval authority: AUTO approves, non-AUTO does not', async () => {
    ctrl.sessionId = 'sess-z';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    // A typed_confirm call routes to the ceremony; deny it deterministically.
    setAuthorizationPresenter(async () => ({ approved: false, allowAlways: false }));
    const approveCount = () =>
      (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
        String(c[0]).includes('/approve'),
      ).length;

    // Backend AUTO (e.g. the auto dial trusting the agent on a MEDIUM write) → the FE obeys + approves.
    ctrl.onToolCallPending({
      callId: 'ok-m', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'auto',
    });
    await new Promise(r => setTimeout(r, 10));
    expect(approveCount()).toBe(1);

    // Backend typed_confirm → NOT auto-clicked (routes to the ceremony) — the FE never auto-approves
    // without an explicit backend AUTO (the collapsed second authority is gone).
    ctrl.onToolCallPending({
      callId: 'blk-m', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'typed_confirm',
    });
    await new Promise(r => setTimeout(r, 10));
    expect(approveCount()).toBe(1); // still 1 — the typed_confirm call did not auto-approve
    expect(ctrl.toolCalls['blk-m']?.gateBehavior).toBe('typed_confirm');
  });

  it('assist + LOW: calls approveCall immediately when sessionId is set', async () => {
    setAutonomyLevel('assist');
    ctrl.sessionId = 'sess-y';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({
      callId: 'auto2', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm',
    });
    await new Promise(r => setTimeout(r, 10));
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1);
  });

  it('assist: does NOT auto-approve MEDIUM or HIGH', () => {
    setAutonomyLevel('assist');
    ctrl.sessionId = 'sess-z';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;

    ctrl.onToolCallPending({ callId: 'med', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'inline_confirm' });
    ctrl.onToolCallPending({ callId: 'high', toolName: 't', arguments: '{}', risk: 'HIGH', gateBehavior: 'typed_confirm' });
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(0);
  });

  it('backend AUTO: auto-approves MEDIUM directly; backend typed_confirm HIGH routes to the ceremony host', async () => {
    ctrl.sessionId = 'sess-a';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    const prompts: Array<{ operationId: string; gateBehavior: string }> = [];
    // Presenter denies in-test (so a routed HIGH call resolves deterministically to reject).
    setAuthorizationPresenter(async (p) => {
      prompts.push(p);
      return { approved: false, allowAlways: false };
    });

    // Tempdoc 561 P-D: the backend issued AUTO (the auto dial trusting the agent) → the FE obeys.
    ctrl.onToolCallPending({ callId: 'med2', toolName: 't', arguments: '{}', risk: 'MEDIUM', gateBehavior: 'auto' });
    await new Promise(r => setTimeout(r, 10));
    // MEDIUM auto-approved directly — no ceremony, one approve fetch.
    expect((fetchSpy as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1);
    expect(prompts.length).toBe(0);

    // HIGH is never AUTO (the backend safety floor) → routes through the unified ceremony host.
    ctrl.onToolCallPending({ callId: 'high2', toolName: 't', arguments: '{}', risk: 'HIGH', gateBehavior: 'typed_confirm' });
    await new Promise(r => setTimeout(r, 10));
    expect(prompts.length).toBe(1);
    expect(prompts[0]!.operationId).toBe('t');
    expect(prompts[0]!.gateBehavior).toBe('TYPED_CONFIRM');
  });

  it('watch: auto-approves NOTHING — routes even LOW through the ceremony host', async () => {
    setAutonomyLevel('watch'); // watch → manual approval for everything
    ctrl.sessionId = 'sess-b';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    const prompts: Array<{ gateBehavior: string }> = [];
    setAuthorizationPresenter(async (p) => {
      prompts.push(p);
      return { approved: false, allowAlways: false };
    });

    ctrl.onToolCallPending({ callId: 'low3', toolName: 't', arguments: '{}', risk: 'LOW', gateBehavior: 'inline_confirm' });
    await new Promise(r => setTimeout(r, 10));
    // Not auto-approved → routed to the ceremony (INLINE for LOW), not silently fired.
    expect(prompts.length).toBe(1);
    expect(prompts[0]!.gateBehavior).toBe('INLINE_CONFIRM');
  });

  it('ceremony APPROVAL drives the unified approve endpoint (tempdoc 550 C3 / 565 §15.C)', async () => {
    setAutonomyLevel('watch'); // everything manual → routes to the ceremony
    ctrl.sessionId = 'sess-c';
    const fetchSpy = mockFetchJson({});
    globalThis.fetch = fetchSpy;
    setAuthorizationPresenter(async () => ({ approved: true, allowAlways: false })); // user approves

    ctrl.onToolCallPending({ callId: 'c-ok', toolName: 't', arguments: '{}', risk: 'MEDIUM' });
    await new Promise(r => setTimeout(r, 10));

    const approveCalls = (fetchSpy as ReturnType<typeof vi.fn>).mock.calls.filter(c =>
      String(c[0]).endsWith('/api/chat/approve'),
    );
    expect(approveCalls.length).toBe(1);
    expect(JSON.parse(approveCalls[0]![1]!.body as string)).toMatchObject({ callId: 'c-ok' });
  });
});

// ==================== Tempdoc 585 §D Phase 2 (D3): shareable replay ====================

describe('loadReplayFromExport (585 §D Phase 2 — D3 shareable replay)', () => {
  it('replays an exported transcript through the same handlers and enters replay mode', () => {
    const ok = ctrl.loadReplayFromExport({
      meta: { sessionId: 'shared-1' },
      events: [
        { eventType: 'session_started', payload: { sessionId: 'shared-1' } },
        { eventType: 'chunk', payload: { text: 'Hello from a shared run' } },
        { eventType: 'done', payload: { finalResponse: 'Hello from a shared run', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 } },
      ],
    });
    expect(ok).toBe(true);
    expect(ctrl.replayMode).toBe(true);
    expect(ctrl.sessionId).toBe('shared-1');
    // The exported events drove the SAME projection the live/C1 paths use.
    const text = ctrl.conversation.map((e) => e.content).join(' ');
    expect(text).toContain('Hello from a shared run');
  });

  it('returns false (and does not enter replay) for a transcript with no usable events', () => {
    expect(ctrl.loadReplayFromExport({ meta: {}, events: [] })).toBe(false);
    expect(ctrl.loadReplayFromExport({ junk: true })).toBe(false);
    expect(ctrl.replayMode).toBe(false);
  });
});

// ==================== Tempdoc 605 — single-live-run ceremony invariant ====================

describe('605 — run identity + conclude-drain', () => {
  it('approveCall routes to the explicit owner run, not a later/stale this.sessionId (M2)', async () => {
    globalThis.fetch = mockFetchJson({ ok: true });
    ctrl.sessionId = 'run-2-current'; // a LATER run is now current
    await ctrl.approveCall('call-x', 'run-1-owner'); // approve a ceremony OWNED by the earlier run
    const body = JSON.parse((globalThis.fetch as unknown as { mock: { calls: [string, { body: string }][] } }).mock.calls[0]![1].body);
    expect(body.sessionId).toBe('run-1-owner');
    expect(body.callId).toBe('call-x');
  });

  it('rejectCall routes to the explicit owner run too', async () => {
    globalThis.fetch = mockFetchJson({ ok: true });
    ctrl.sessionId = 'run-2-current';
    await ctrl.rejectCall('call-y', 'denied', 'run-1-owner');
    const body = JSON.parse((globalThis.fetch as unknown as { mock: { calls: [string, { body: string }][] } }).mock.calls[0]![1].body);
    expect(body.sessionId).toBe('run-1-owner');
  });

  it('onError (the abnormal terminal — the M1 trigger) drains the run and surfaces ONE notice when ≥1 was denied', () => {
    vi.mocked(emitEphemeralToast).mockClear();
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 2; });
    ctrl.sessionId = 'run-err';
    ctrl.onError({ error: 'stream dropped' });
    expect(drained).toEqual(['run-err']);
    expect(emitEphemeralToast).toHaveBeenCalledTimes(1);
    setAuthorizationCanceller(null);
  });

  it('cancelSession drains the halted run', async () => {
    vi.mocked(emitEphemeralToast).mockClear();
    globalThis.fetch = mockFetchJson({ ok: true }); // the DELETE
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 1; });
    ctrl.sessionId = 'run-halt';
    await ctrl.cancelSession();
    expect(drained).toEqual(['run-halt']);
    expect(emitEphemeralToast).toHaveBeenCalledTimes(1);
    setAuthorizationCanceller(null);
  });

  it('a terminal with no open ceremony (0 denied) surfaces NO notice', () => {
    vi.mocked(emitEphemeralToast).mockClear();
    setAuthorizationCanceller(() => 0);
    ctrl.sessionId = 'run-clean';
    ctrl.onError({ error: 'x' });
    expect(emitEphemeralToast).not.toHaveBeenCalled();
    setAuthorizationCanceller(null);
  });

  it('a REPLAY terminal does NOT drain (replay is not a live run conclusion)', () => {
    const drained: string[] = [];
    setAuthorizationCanceller((runId) => { drained.push(runId); return 1; });
    // loadReplayFromExport runs session_started + chunk + done IN REPLAY MODE.
    ctrl.loadReplayFromExport({
      meta: { sessionId: 'replay-1' },
      events: [
        { eventType: 'session_started', payload: { sessionId: 'replay-1' } },
        { eventType: 'done', payload: { finalResponse: 'r', iterationsUsed: 1, toolCallsExecuted: 0, totalTokensUsed: 0 } },
      ],
    });
    expect(ctrl.replayMode).toBe(true);
    expect(drained).toEqual([]); // the replay done() never drained a real ceremony
    setAuthorizationCanceller(null);
  });
});
