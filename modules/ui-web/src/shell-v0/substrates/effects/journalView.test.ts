import { describe, expect, it } from 'vitest';
import { groupJournalForDisplay } from './journalView.js';
import type { JournalEntry } from './index.js';
import type { Effect } from '../effect.js';

let seq = 0;
function entry(
  effect: Effect,
  opts: { originator?: JournalEntry['originator']; causation?: number; id?: number } = {},
): JournalEntry {
  const id = opts.id ?? ++seq;
  return {
    id,
    effect,
    inverse: null,
    originator: opts.originator ?? 'user',
    invokedAt: new Date().toISOString(),
    ...(opts.causation !== undefined ? { causation: opts.causation } : {}),
  } as JournalEntry;
}

describe('groupJournalForDisplay (543-fwd #6)', () => {
  it('keeps standalone (uncaused) entries as separate singleton turns', () => {
    const a = entry({ kind: 'navigate', to: '#a' });
    const b = entry({ kind: 'open-modal', modalId: 'm' });
    const turns = groupJournalForDisplay([a, b]);
    expect(turns).toHaveLength(2);
    expect(turns.every((t) => !t.grouped)).toBe(true);
    expect(turns.map((t) => t.rootId)).toEqual([a.id, b.id]);
  });

  it('folds a causation-chained run into one grouped turn', () => {
    const root = entry({ kind: 'invoke-operation', operationId: 'core_browse_folders' }, {
      originator: 'agent',
    });
    const step2 = entry(
      { kind: 'invoke-operation', operationId: 'core_search_index' },
      { originator: 'agent', causation: root.id },
    );
    const turns = groupJournalForDisplay([root, step2]);
    expect(turns).toHaveLength(1);
    expect(turns[0]?.grouped).toBe(true);
    expect(turns[0]?.rootId).toBe(root.id);
    expect(turns[0]?.originator).toBe('agent');
    expect(turns[0]?.members).toHaveLength(2);
  });

  it('preserves every member of a turn — including consecutive identical effects (Fix C)', () => {
    // No row-collapsing: each entry keeps its own row (and thus its per-entry actions).
    const root = entry({ kind: 'toast', message: 'tick' }, { originator: 'agent' });
    const r2 = entry({ kind: 'toast', message: 'tick' }, { originator: 'agent', causation: root.id });
    const r3 = entry({ kind: 'toast', message: 'tick' }, { originator: 'agent', causation: root.id });
    const turns = groupJournalForDisplay([root, r2, r3]);
    expect(turns).toHaveLength(1);
    expect(turns[0]?.members).toHaveLength(3);
    expect(turns[0]?.members.map((m) => m.id)).toEqual([root.id, r2.id, r3.id]);
  });

  it('separates adjacent turns with different roots', () => {
    const userNav = entry({ kind: 'navigate', to: '#home' });
    const agentRoot = entry({ kind: 'invoke-operation', operationId: 'op' }, { originator: 'agent' });
    const agentStep = entry(
      { kind: 'invoke-operation', operationId: 'op2' },
      { originator: 'agent', causation: agentRoot.id },
    );
    const turns = groupJournalForDisplay([userNav, agentRoot, agentStep]);
    expect(turns).toHaveLength(2);
    expect(turns[0]?.grouped).toBe(false);
    expect(turns[1]?.grouped).toBe(true);
    expect(turns[1]?.members).toHaveLength(2);
  });
});
