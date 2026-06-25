// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest';
import { setActiveRun, clearActiveRun, readActiveRun } from './activeRunPointer.js';

describe('activeRunPointer — Tempdoc 577 Root I (#1d) cross-tab live-run pointer', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('round-trips a live agent run for a second tab to discover', () => {
    expect(readActiveRun()).toBeNull();
    setActiveRun('sess-123');
    expect(readActiveRun()).toEqual({ sessionId: 'sess-123', runKind: 'agent' });
  });

  it('clears on terminal so a fresh tab finds nothing to reattach to', () => {
    setActiveRun('sess-abc');
    clearActiveRun();
    expect(readActiveRun()).toBeNull();
  });

  it('returns null for a malformed/foreign pointer (never a partial reattach target)', () => {
    localStorage.setItem('justsearch.activeAgentRun.v1', '{not json');
    expect(readActiveRun()).toBeNull();
    localStorage.setItem('justsearch.activeAgentRun.v1', JSON.stringify({ sessionId: '', runKind: 'agent' }));
    expect(readActiveRun()).toBeNull();
    localStorage.setItem('justsearch.activeAgentRun.v1', JSON.stringify({ sessionId: 'x', runKind: 'workflow' }));
    expect(readActiveRun()).toBeNull();
  });

  it('the latest writer wins (the most recent run is the reattach target)', () => {
    setActiveRun('old');
    setActiveRun('new');
    expect(readActiveRun()?.sessionId).toBe('new');
  });

  it('round-trips the conversationId so a reattach can be conversation-scoped', () => {
    setActiveRun('sess-c', 'conv-42');
    expect(readActiveRun()).toEqual({ sessionId: 'sess-c', runKind: 'agent', conversationId: 'conv-42' });
  });

  it('omits conversationId when absent/blank (back-compat — a run with no conversation)', () => {
    setActiveRun('sess-d');
    expect(readActiveRun()).toEqual({ sessionId: 'sess-d', runKind: 'agent' });
    setActiveRun('sess-e', null);
    expect(readActiveRun()).toEqual({ sessionId: 'sess-e', runKind: 'agent' });
    setActiveRun('sess-f', '');
    expect(readActiveRun()).toEqual({ sessionId: 'sess-f', runKind: 'agent' });
  });
});
