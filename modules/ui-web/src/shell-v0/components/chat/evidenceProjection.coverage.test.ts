// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 836 S2S3-A.6 — the consumer half of coverage honesty.
 *
 * The load-bearing claim under test is the `Not scored` / `Not grounded` split. "Not grounded" is a
 * verdict about EVIDENCE; rendering it over a pass the budget cut short states a conclusion nobody
 * reached. These pin that the projection says which of the two happened, and that a source the
 * budget never examined is not filed as "retrieved but never grounded".
 */
import { describe, it, expect } from 'vitest';
import {
  coverageHonesty,
  groundingCoverage,
  sourceGrounding,
  sourceGroundingLabel,
  isVerifiedProducer,
  coverageNote,
} from './evidenceProjection.js';
import type { CitationMatch } from './citationTypes.js';

const match = (over: Partial<CitationMatch> = {}): CitationMatch => ({
  sentenceIndex: 0,
  sentenceText: 'A sentence.',
  sourceIndex: 0,
  similarity: 0.9,
  parentDocId: 'a.md',
  ...over,
});

/** Four sentences of prose, so the regex fallback and the backend count can disagree visibly. */
const ANSWER = 'One thing. Two things. Three things. Four things.';

describe('836 S2S3-A.6a — a complete-looking pass over partially-examined text', () => {
  it('reports the text axis even when every sentence was scored', () => {
    // §10.7 gap 1: 15 of 15 sentences scored, against 5 of 134 windows. The sentence axis is
    // complete and says nothing about the text axis; only the per-source facts can.
    const honesty = coverageHonesty({
      sentencesScored: 15,
      sentencesTotal: 15,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 134, windowsScored: 5 }],
    })!;
    expect(honesty.sentencesIncomplete).toBe(false);
    expect(honesty.textIncomplete).toBe(true);
    expect(honesty.textExaminedRatio).toBeCloseTo(5 / 134);
  });

  it('says so in the coverage line rather than reporting a plain "Grounded · N of M"', () => {
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 134, windowsScored: 5 }],
    });
    const cov = groundingCoverage([{ similarity: 0.9 }], ANSWER, honesty);
    expect(cov.label).toBe('Grounded · 1 of 4 sentences · part of the text examined');
  });

  it('names the unexamined SOURCES rather than a percentage of text', () => {
    // A count is actionable; "4% of your text" invites reading coverage as a quality score. The
    // ratio stays available on the projection, off the primary line.
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [
        { sourceIndex: 0, windowsConsidered: 10, windowsScored: 10 },
        { sourceIndex: 1, windowsConsidered: 9, windowsScored: 0 },
        { sourceIndex: 2, windowsConsidered: 7, windowsScored: 0 },
      ],
    });
    const cov = groundingCoverage([{ similarity: 0.9 }], ANSWER, honesty);
    expect(cov.label).toBe('Grounded · 1 of 4 sentences · 2 sources not examined');
    expect(cov.label).not.toContain('%');
  });

  it('reports the SENTENCE shortfall as scored-of-total, never as a smaller grounded ratio', () => {
    const honesty = coverageHonesty({
      sentencesScored: 12,
      sentencesTotal: 15,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 3, windowsScored: 3 }],
    });
    const cov = groundingCoverage([{ similarity: 0.9 }], ANSWER, honesty);
    expect(cov.label).toBe('Grounded · 1 of 15 sentences · 12 of 15 sentences scored');
  });
});

describe('836 §3.6 — the backend sentence count is the denominator authority', () => {
  it('uses the reported sentencesTotal, not the frontend regex estimate', () => {
    // The regex counts 4 here; the backend's BreakIterator counted 6. Two counters disagreeing,
    // one of them the denominator of a user-facing honesty claim, is the fork this closes.
    const honesty = coverageHonesty({
      sentencesScored: 6,
      sentencesTotal: 6,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 2, windowsScored: 2 }],
    });
    expect(groundingCoverage([{ similarity: 0.9 }], ANSWER, honesty).total).toBe(6);
    expect(groundingCoverage([{ similarity: 0.9 }], ANSWER, null).total).toBe(4);
  });
});

describe('836 S2S3-A.6c — no false incompleteness', () => {
  it('a request fully inside the budget renders the unchanged line', () => {
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [
        { sourceIndex: 0, windowsConsidered: 2, windowsScored: 2 },
        { sourceIndex: 1, windowsConsidered: 1, windowsScored: 1 },
      ],
    })!;
    expect(honesty.sentencesIncomplete).toBe(false);
    expect(honesty.textIncomplete).toBe(false);
    expect(honesty.unexaminedSources).toBe(0);
    expect(groundingCoverage([{ similarity: 0.9 }], ANSWER, honesty).label).toBe(
      'Grounded · 1 of 4 sentences',
    );
  });

  it('a producer that reports NO coverage facts keeps its line exactly as before', () => {
    expect(coverageHonesty(null)).toBeNull();
    expect(coverageHonesty({})).toBeNull();
    expect(groundingCoverage([{ similarity: 0.9 }], ANSWER).label).toBe(
      'Grounded · 1 of 4 sentences',
    );
  });
});

describe('836 S2S3-A.6e — "Not scored" is not "Not grounded"', () => {
  it('zero cites over an INCOMPLETE pass reads "Not scored"', () => {
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 134, windowsScored: 5 }],
    });
    const cov = groundingCoverage([], ANSWER, honesty);
    expect(cov.label).toBe('Not scored');
    expect(cov.notScored).toBe(true);
  });

  it('zero cites over a COMPLETE pass reads "Not grounded" — a real verdict', () => {
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 3, windowsScored: 3 }],
    });
    const cov = groundingCoverage([], ANSWER, honesty);
    expect(cov.label).toBe('Not grounded');
    expect(cov.notScored).toBe(false);
  });

  it('zero cites with NO coverage facts keeps the established "Not grounded"', () => {
    expect(groundingCoverage([], ANSWER).label).toBe('Not grounded');
  });

  it('a starved source alone is enough to make it "Not scored"', () => {
    const honesty = coverageHonesty({
      sentencesScored: 4,
      sentencesTotal: 4,
      sourceCoverage: [
        { sourceIndex: 0, windowsConsidered: 2, windowsScored: 2 },
        { sourceIndex: 1, windowsConsidered: 9, windowsScored: 0 },
      ],
    });
    expect(groundingCoverage([], ANSWER, honesty).label).toBe('Not scored');
  });
});

describe('836 S2S3-A.6b — the sources panel is three-valued', () => {
  const matches = [match({ sourceIndex: 0, parentDocId: 'a.md' })];

  it('a scored-but-uncited source keeps its established meaning', () => {
    const g = sourceGrounding(1, matches, 'b.md', {
      sourceIndex: 1,
      windowsConsidered: 4,
      windowsScored: 4,
    });
    expect(g.state).toBe('examined-uncited');
    expect(sourceGroundingLabel(g)).toBe('Retrieved · not cited');
  });

  it('a STARVED source is unexamined, not uncited', () => {
    const g = sourceGrounding(1, matches, 'b.md', {
      sourceIndex: 1,
      windowsConsidered: 9,
      windowsScored: 0,
    });
    expect(g.state).toBe('unexamined');
    expect(sourceGroundingLabel(g)).toBe('Retrieved · not examined');
    // A budget fact never becomes a grounding input: the tier is the same non-input it was.
    expect(g.tier).toBe('low');
    expect(g.groundedSentences).toBe(0);
  });

  it('a source with NO text is unexamined too — nothing read it either way', () => {
    const g = sourceGrounding(1, matches, 'b.md', {
      sourceIndex: 1,
      windowsConsidered: 0,
      windowsScored: 0,
    });
    expect(g.state).toBe('unexamined');
  });

  it('a cited source stays cited even when its coverage was partial', () => {
    const g = sourceGrounding(0, matches, 'a.md', {
      sourceIndex: 0,
      windowsConsidered: 40,
      windowsScored: 2,
    });
    expect(g.state).toBe('cited');
    expect(sourceGroundingLabel(g)).toBe('Grounds 1 sentence');
  });

  it('without coverage facts the state stays the established binary', () => {
    expect(sourceGrounding(1, matches, 'b.md').state).toBe('examined-uncited');
    expect(sourceGrounding(0, matches, 'a.md').state).toBe('cited');
  });
});

describe('836 §4 — the producer gate', () => {
  it('admits the cross-encoder and refuses the cosine fallback', () => {
    expect(isVerifiedProducer('CROSS_ENCODER')).toBe(true);
    expect(isVerifiedProducer('EMBEDDING_COSINE')).toBe(false);
    expect(isVerifiedProducer('NONE')).toBe(false);
  });

  it('admits an ABSENT producer — a record older than the field, not an unknown one', () => {
    expect(isVerifiedProducer(undefined)).toBe(true);
    expect(isVerifiedProducer(null)).toBe(true);
    expect(isVerifiedProducer('')).toBe(true);
  });
});

describe('836 S2S3-A.2 — the coverage note renders only when there is something to say', () => {
  const complete = coverageHonesty({
    sentencesScored: 4,
    sentencesTotal: 4,
    sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 3, windowsScored: 3 }],
  });
  const partial = coverageHonesty({
    sentencesScored: 4,
    sentencesTotal: 4,
    sourceCoverage: [{ sourceIndex: 0, windowsConsidered: 134, windowsScored: 5 }],
  });

  it('is silent when the pass was complete — an always-on line is noise, and noise gets skipped', () => {
    expect(coverageNote(groundingCoverage([{ similarity: 0.9 }], ANSWER, complete))).toBeNull();
    expect(coverageNote(groundingCoverage([], ANSWER, complete))).toBeNull();
    expect(coverageNote(groundingCoverage([{ similarity: 0.9 }], ANSWER, null))).toBeNull();
  });

  it('speaks when the run said verification did not fully happen — and says the SAME string', () => {
    const cov = groundingCoverage([{ similarity: 0.9 }], ANSWER, partial);
    expect(coverageNote(cov)).toBe(cov.label);
    expect(coverageNote(cov)).toBe('Grounded · 1 of 4 sentences · part of the text examined');
    expect(coverageNote(groundingCoverage([], ANSWER, partial))).toBe('Not scored');
  });
});
