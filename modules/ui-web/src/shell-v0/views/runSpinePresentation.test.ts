import { describe, it, expect } from 'vitest';
import { computeSpinePositions, spineNodeLabel } from './runSpinePresentation.js';
import type { UnifiedTurnItem } from './unifiedThreadProjection.js';

function turn(id: string, kind: UnifiedTurnItem['kind']): UnifiedTurnItem {
  return { id, ts: 0, kind, prominence: 'primary', originator: 'user', content: '', attributes: {} };
}

describe('computeSpinePositions (tempdoc 621 Phase 5 — pure run-spine positions)', () => {
  it('anchors turns at their measured fraction', () => {
    const items = [turn('u1', 'user'), turn('a1', 'assistant')];
    const fractions = new Map([['u1', 0.2], ['a1', 0.8]]);
    expect(computeSpinePositions(items, fractions)).toEqual([0.2, 0.8]);
  });

  it('interpolates intra-run steps EVENLY between the turn landmarks (the §13 texture rule)', () => {
    // user(0.0) — step — step — assistant(0.9): the two steps spread evenly across the span.
    const items = [turn('u1', 'user'), turn('t1', 'tool-activity'), turn('t2', 'tool-activity'), turn('a1', 'assistant')];
    const fractions = new Map([['u1', 0.0], ['a1', 0.9]]);
    const pos = computeSpinePositions(items, fractions);
    expect(pos[0]).toBe(0.0);
    expect(pos[3]).toBe(0.9);
    // span 0.9, count 2 → 0.3, 0.6
    expect(pos[1]).toBeCloseTo(0.3, 6);
    expect(pos[2]).toBeCloseTo(0.6, 6);
  });

  it('leading/trailing steps extrapolate to the 0 / 1 boundaries', () => {
    const items = [turn('t0', 'tool-activity'), turn('u1', 'user'), turn('t1', 'tool-activity')];
    const fractions = new Map([['u1', 0.5]]);
    const pos = computeSpinePositions(items, fractions);
    expect(pos[0]).toBeCloseTo(0.25, 6); // between 0 and 0.5
    expect(pos[1]).toBe(0.5);
    expect(pos[2]).toBeCloseTo(0.75, 6); // between 0.5 and 1
  });
});

describe('spineNodeLabel — accessible jump-control names', () => {
  it('names each node kind', () => {
    expect(spineNodeLabel(turn('x', 'user'))).toBe('Jump to your message');
    expect(spineNodeLabel(turn('x', 'assistant'))).toBe('Jump to the answer');
    expect(spineNodeLabel(turn('x', 'tool-activity'))).toBe('Jump to a tool step');
    expect(spineNodeLabel(turn('x', 'error'))).toBe('Jump to an error');
    expect(spineNodeLabel(turn('x', 'progress'))).toBe('Jump to a step');
  });
});
