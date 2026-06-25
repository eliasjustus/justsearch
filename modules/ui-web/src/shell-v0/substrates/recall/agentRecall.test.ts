import { describe, it, expect } from 'vitest';
import { summarizeAgentRecall } from './agentRecall.js';
import type { UnifiedActionEntry } from '../../operations/ActionLedgerClient.js';

function row(p: Partial<UnifiedActionEntry> & { occurredAt: string }): UnifiedActionEntry {
  return {
    id: p.id ?? `id:${p.occurredAt}`,
    source: p.source ?? 'backend',
    kind: p.kind ?? 'operation',
    occurredAt: p.occurredAt,
    originator: p.originator ?? 'agent',
    label: p.label ?? 'x',
    ...(p.groupKey ? { groupKey: p.groupKey } : {}),
  };
}

describe('summarizeAgentRecall', () => {
  it('counts only agent-originated action rows, grouped by ledger kind', () => {
    const d = summarizeAgentRecall(
      [
        row({ occurredAt: '2026-01-01T00:00:01Z', kind: 'operation' }),
        row({ occurredAt: '2026-01-01T00:00:02Z', kind: 'operation' }),
        row({ occurredAt: '2026-01-01T00:00:03Z', kind: 'navigate', source: 'fe-effect' }),
        row({ occurredAt: '2026-01-01T00:00:04Z', kind: 'operation', originator: 'user' }),
        row({ occurredAt: '2026-01-01T00:00:05Z', kind: 'operation', originator: 'system' }),
      ],
      '',
    );
    expect(d.total).toBe(3);
    expect(d.byKind).toEqual({ operation: 2, navigate: 1 });
    expect(d.latestIso).toBe('2026-01-01T00:00:03Z');
  });

  it('excludes gate / grant / index rows (decision + system, not "the assistant did X")', () => {
    const d = summarizeAgentRecall(
      [
        row({ occurredAt: '2026-01-01T00:00:01Z', kind: 'gate' }),
        row({ occurredAt: '2026-01-01T00:00:02Z', kind: 'grant' }),
        row({ occurredAt: '2026-01-01T00:00:03Z', kind: 'index' }),
        row({ occurredAt: '2026-01-01T00:00:04Z', kind: 'operation' }),
      ],
      '',
    );
    expect(d.total).toBe(1);
    expect(d.byKind).toEqual({ operation: 1 });
  });

  it('counts only rows strictly newer than the seen-cursor', () => {
    const rows = [
      row({ occurredAt: '2026-01-01T00:00:01Z' }),
      row({ occurredAt: '2026-01-01T00:00:02Z' }),
      row({ occurredAt: '2026-01-01T00:00:03Z' }),
    ];
    const d = summarizeAgentRecall(rows, '2026-01-01T00:00:02Z');
    expect(d.total).toBe(1); // only the :03 row
    expect(d.latestIso).toBe('2026-01-01T00:00:03Z');
  });

  it('empty when the cursor is at or past the newest row', () => {
    const rows = [row({ occurredAt: '2026-01-01T00:00:01Z' })];
    const d = summarizeAgentRecall(rows, '2026-01-01T00:00:09Z');
    expect(d.total).toBe(0);
    expect(d.byKind).toEqual({});
    expect(d.latestIso).toBe('2026-01-01T00:00:09Z');
  });
});
