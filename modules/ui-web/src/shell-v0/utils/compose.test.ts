// @vitest-environment happy-dom

/**
 * Tempdoc 526 §12.4 — compose() dispatch tests.
 *
 * Mirrors the previous slice 514 askAi tests (deleted as part of askAi.ts
 * removal). Each ComposeArgs shape produces a navigate-with-context event
 * with the right query/affordance/docIds; when a typed selection rides on
 * the call, takePendingSelection() returns it on first call and null
 * afterwards (one-shot register per §12.4).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

let mockedCapabilities: { chat: boolean } = { chat: true };
vi.mock('../state/aiStateStore.js', () => ({
  getAiState: () => ({
    capabilities: { chat: mockedCapabilities.chat, rag: false, extract: false, embedding: false },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  }),
}));

import { compose, takePendingSelection, setPendingAutoRun, takePendingAutoRun } from './compose.js';
import type { SelectionPayload } from '../../api/types/selection.js';

interface CapturedDetail {
  target: string;
  state: { query: string; affordance: string; docIds: string[] };
}

function setupShell(): { capture: () => CapturedDetail | null } {
  document.body.innerHTML = '<jf-shell></jf-shell>';
  let captured: CapturedDetail | null = null;
  const shell = document.querySelector('jf-shell') as HTMLElement;
  shell.addEventListener('navigate-with-context', ((e: CustomEvent<CapturedDetail>) => {
    captured = e.detail;
  }) as EventListener);
  return { capture: () => captured };
}

describe('compose()', () => {
  beforeEach(() => {
    mockedCapabilities = { chat: true };
    // Drain any leftover pending selection from a previous test.
    takePendingSelection();
  });

  it('returns false when AI chat capability is offline', () => {
    mockedCapabilities = { chat: false };
    const ok = compose({ operation: 'core.ask', source: 'BUTTON', userPrompt: 'hi' });
    expect(ok).toBe(false);
  });

  it('core.ask with user prompt dispatches navigate-with-context', () => {
    const { capture } = setupShell();
    const ok = compose({ operation: 'core.ask', source: 'BUTTON', userPrompt: 'why splade?' });
    expect(ok).toBe(true);
    const d = capture();
    expect(d?.target).toBe('core.unified-chat-surface');
    expect(d?.state.query).toBe('why splade?');
    expect(d?.state.affordance).toBe('none');
    expect(d?.state.docIds).toEqual([]);
  });

  it('core.ask with docIds defaults affordance to documents', () => {
    const { capture } = setupShell();
    compose({
      operation: 'core.ask',
      source: 'BUTTON',
      userPrompt: 'compare them',
      docIds: ['a', 'b'],
    });
    const d = capture();
    expect(d?.state.affordance).toBe('documents');
    expect(d?.state.docIds).toEqual(['a', 'b']);
  });

  it('core.summarize with docName builds templated query', () => {
    const { capture } = setupShell();
    compose({
      operation: 'core.summarize',
      source: 'BUTTON',
      docIds: ['doc-123'],
      docName: 'Q3 report.pdf',
    });
    const d = capture();
    expect(d?.state.query).toBe('Summarize Q3 report.pdf');
    expect(d?.state.affordance).toBe('documents');
    expect(d?.state.docIds).toEqual(['doc-123']);
  });

  it('core.summarize without docName falls back to "Summarize this document"', () => {
    const { capture } = setupShell();
    compose({ operation: 'core.summarize', source: 'BUTTON', docIds: ['doc-only'] });
    const d = capture();
    expect(d?.state.query).toBe('Summarize this document');
  });

  it('typed selection is parked in the pending register and drained once', () => {
    setupShell();
    const sel: SelectionPayload = {
      kind: 'text-range',
      address: { coords: 'canonical', docId: 'doc-1', startChar: 0, endChar: 20 },
      selectionText: 'a slice of content',
      hostEntity: { kind: 'doc', id: 'doc-1' },
    };
    compose({ operation: 'core.summarize', source: 'BUTTON', selection: sel });
    expect(takePendingSelection()).toEqual(sel);
    // Second take returns null — register is one-shot.
    expect(takePendingSelection()).toBeNull();
  });

  it('selection with no docName uses "Summarize the selection" query template', () => {
    const { capture } = setupShell();
    const sel: SelectionPayload = {
      kind: 'text-range',
      address: {
        coords: 'display',
        docId: 'doc-1',
        viewId: 'preview-5k',
        displayStart: 10,
        displayEnd: 30,
      },
      selectionText: 'lorem ipsum',
      hostEntity: { kind: 'doc', id: 'doc-1' },
    };
    compose({ operation: 'core.summarize', source: 'BUTTON', selection: sel });
    const d = capture();
    expect(d?.state.query).toBe('Summarize the selection');
    // Pending register holds the typed selection until takePendingSelection().
    expect(takePendingSelection()).toEqual(sel);
  });
});

describe('pendingAutoRun one-shot (548 §4.5)', () => {
  beforeEach(() => {
    // Drain any leftover flag from a previous test.
    takePendingAutoRun();
  });

  it('defaults to false', () => {
    expect(takePendingAutoRun()).toBe(false);
  });

  it('returns true once after set, then resets to false (one-shot)', () => {
    setPendingAutoRun(true);
    expect(takePendingAutoRun()).toBe(true);
    expect(takePendingAutoRun()).toBe(false);
  });

  it('compose() does NOT set the auto-run flag (askAi prefills, waits for Send)', () => {
    setupShell();
    compose({ operation: 'core.ask', source: 'BUTTON', userPrompt: 'hi' });
    expect(takePendingAutoRun()).toBe(false);
  });
});
