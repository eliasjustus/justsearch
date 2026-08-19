// SPDX-License-Identifier: Apache-2.0
/**
 * The ONE parser for a persisted reasoning array (tempdoc 848 §2.5). Both windows read the record
 * through this function, so what it accepts and what it drops is a product decision, not a per-view
 * detail — a second `typeof x.text === 'string'` walk elsewhere is the drift these cases exist to
 * make visible.
 */
import { describe, it, expect } from 'vitest';
import { reasoningBlocksFromRecord } from './ReasoningController.js';

describe('reasoningBlocksFromRecord', () => {
  it('accepts a well-formed record array', () => {
    expect(
      reasoningBlocksFromRecord([
        { text: 'first', durationMs: 1840 },
        { text: 'second', durationMs: 20 },
      ]),
    ).toEqual([
      { text: 'first', durationMs: 1840 },
      { text: 'second', durationMs: 20 },
    ]);
  });

  it('drops malformed elements rather than rendering half a block', () => {
    expect(
      reasoningBlocksFromRecord([
        { durationMs: 5 },
        { text: '', durationMs: 5 },
        'a bare string',
        null,
        { text: 'kept', durationMs: 7 },
      ]),
    ).toEqual([{ text: 'kept', durationMs: 7 }]);
  });

  it('defaults a missing or non-finite duration to 0 rather than dropping real thinking', () => {
    expect(reasoningBlocksFromRecord([{ text: 'thought' }])).toEqual([
      { text: 'thought', durationMs: 0 },
    ]);
    expect(reasoningBlocksFromRecord([{ text: 'thought', durationMs: Number.NaN }])).toEqual([
      { text: 'thought', durationMs: 0 },
    ]);
  });

  it('returns [] for anything that is not an array — absence is never an error state', () => {
    expect(reasoningBlocksFromRecord(undefined)).toEqual([]);
    expect(reasoningBlocksFromRecord(null)).toEqual([]);
    expect(reasoningBlocksFromRecord('reasoning')).toEqual([]);
    expect(reasoningBlocksFromRecord({ text: 'not an array' })).toEqual([]);
  });
});
