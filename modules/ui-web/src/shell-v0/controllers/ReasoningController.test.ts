// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * The ONE parser for a persisted reasoning array (tempdoc 848 §2.5). Both windows read the record
 * through this function, so what it accepts and what it drops is a product decision, not a per-view
 * detail — a second `typeof x.text === 'string'` walk elsewhere is the drift these cases exist to
 * make visible.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { ReasoningController, reasoningBlocksFromRecord } from './ReasoningController.js';

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

/**
 * Tempdoc 859 §A §1.2 — the region's two boundaries, separated. `endThinking()` used to be both at
 * once, which is why the live side could only ever cut on the first prose token: the one call site
 * that had to freeze the duration also had to close the region, so it could not be placed anywhere
 * a region should keep accumulating.
 */
describe('ReasoningController — the region boundary (859 §A)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  const at = (ms: number): void => {
    vi.setSystemTime(new Date(ms));
  };

  const controller = (): ReasoningController => {
    vi.useFakeTimers();
    at(0);
    return new ReasoningController(() => {});
  };

  it('C-1: markOutput freezes the duration and drops the affordance WITHOUT closing the region', () => {
    const c = controller();
    c.handleReasoningChunk({ text: 'part one ' });
    expect(c.isThinking).toBe(true);

    at(2000);
    c.markOutput();
    expect(c.isThinking).toBe(false);
    expect(c.reasoningBlocks).toHaveLength(0);
    expect(c.reasoningText).toBe('part one ');

    // The corruption case, and it is invisible without asserting the START time: the region is still
    // open, so a following chunk joins it — and must NOT restart the clock. (Asserted through the
    // block's duration, which is the only reading of `thinkingStartedAt` that leaves the class.)
    at(5000);
    c.handleReasoningChunk({ text: 'part two' });
    at(9000);
    const block = c.closeRegion();
    expect(block).toEqual({ text: 'part one part two', durationMs: 2000 });
  });

  it('C-2: closeRegion PUSHES to reasoningBlocks and returns the same block', () => {
    const c = controller();
    c.handleReasoningChunk({ text: 'a thought' });
    at(1500);
    const block = c.closeRegion();
    expect(block).not.toBeNull();
    // Both, not either/or: five live readers depend on the array, the run timeline on the value.
    expect(c.reasoningBlocks).toEqual([block]);
    expect(c.reasoningText).toBe('');
    expect(c.isThinking).toBe(false);
  });

  it('C-2b: a blank region returns null and pushes nothing', () => {
    const c = controller();
    expect(c.closeRegion()).toBeNull();
    c.handleReasoningChunk({ text: '' });
    expect(c.closeRegion()).toBeNull();
    expect(c.reasoningBlocks).toEqual([]);
  });

  it('C-3: after endThinking there is exactly ONE block — the contract four surfaces depend on', () => {
    // NavigateView, SummarizeView, UnifiedChatView and SearchV3View all read `reasoningBlocks`, and
    // all four reach it through `endThinking()`. Splitting the method must not change what they see.
    const c = controller();
    c.handleReasoningChunk({ text: 'the whole thought' });
    at(1000);
    c.endThinking();
    expect(c.reasoningBlocks).toHaveLength(1);
    expect(c.reasoningBlocks[0]?.text).toBe('the whole thought');
    // Idempotent: a second terminal (chunk then done) must not push an empty second block.
    c.endThinking();
    expect(c.reasoningBlocks).toHaveLength(1);
  });

  it('C-3b: isThinking is DERIVED — a closed region can never claim to be in progress', () => {
    const c = controller();
    expect(c.isThinking).toBe(false);
    c.handleReasoningChunk({ text: 'x' });
    expect(c.isThinking).toBe(true);
    c.closeRegion();
    expect(c.isThinking).toBe(false);
    // The A3 leak in one line: the old flag was cleared by exactly one site, so this stayed true.
    c.markOutput();
    expect(c.isThinking).toBe(false);
  });
});
