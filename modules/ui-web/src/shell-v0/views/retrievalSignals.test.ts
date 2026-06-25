import { describe, it, expect } from 'vitest';
import { formatRetrievalSignals } from './retrievalSignals.js';

describe('formatRetrievalSignals (tempdoc 561 P-A4 — honest, uncalibrated surfacing)', () => {
  it('returns empty when there is no signal (unknown != a value)', () => {
    expect(formatRetrievalSignals(null)).toBe('');
    expect(formatRetrievalSignals(undefined)).toBe('');
    expect(formatRetrievalSignals({})).toBe('');
    expect(formatRetrievalSignals({ best_chunk_score: 0 })).toBe('');
  });

  it('labels the signals as RELATIVE and UNCALIBRATED — never a fabricated %/confidence', () => {
    const s = formatRetrievalSignals({ best_chunk_score: 5.3, score_gap: 1.2 });
    expect(s.toLowerCase()).toContain('uncalibrated');
    expect(s.toLowerCase()).toContain('relative');
    // must NOT claim a confidence percentage of the answer.
    expect(s).not.toMatch(/\bconfidence\b/i);
    // it carries the raw top score and the margin to the next passage.
    expect(s).toContain('5');
    expect(s).toContain('margin to next');
  });

  it('omits the margin when score_gap is absent but still shows the top score', () => {
    const s = formatRetrievalSignals({ best_chunk_score: 0.87 });
    expect(s).toContain('0.87');
    expect(s).not.toContain('margin to next');
  });

  it('renders the margin as a percentage OF THE TOP score (a scale-invariant ratio, not a confidence)', () => {
    // gap 0.5 on a top of 1.0 -> margin is 50% of top.
    const s = formatRetrievalSignals({ best_chunk_score: 1.0, score_gap: 0.5 });
    expect(s).toContain('50% of top');
  });
});
