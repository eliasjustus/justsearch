/**
 * Tempdoc 565 §15.B — the ONE RAG claim→Citation resolver.
 *
 * Pins the bridge that lets the RAG path render through the same `MarkdownBlock` weave as the agent
 * path: a grounded `Claim` (sentence + score + the source index it grounds to) maps to a `Citation`
 * carrying the deep-link `detail` + `hover` (which the flat-text fork lacked); an ungrounded claim
 * (empty `sourceRefs`) produces no mark.
 */
import { describe, it, expect } from 'vitest';
import { claimsToCitations } from './citationResolve.js';
import { answerFrame, groundingCoverage } from './evidenceProjection.js';
import type { Claim, RetrievalCitation } from './citationTypes.js';

const SOURCES: RetrievalCitation[] = [
  {
    parentDocId: 'docs/a.md',
    chunkIndex: 0,
    chunkTotal: 2,
    startChar: 5,
    endChar: 40,
    score: 0.9,
    excerpt: 'the cited passage',
    startLine: 3,
    endLine: 5,
    headingText: 'Intro',
    headingLevel: 2,
  },
];

describe('claimsToCitations — the one RAG claim→Citation resolver (§15.B)', () => {
  it('maps a grounded claim to a Citation with the deep-link detail + hover the flat-text fork lacked', () => {
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'Grounded sentence.',
        verifiedScore: 0.8,
        lexicalScore: 0,
        verifiedRefs: [0],
        lexicalRefs: [],
      },
    ];
    const out = claimsToCitations(claims, SOURCES);
    expect(out).toHaveLength(1);
    const c = out[0]!;
    expect(c.sentenceText).toBe('Grounded sentence.');
    expect(c.similarity).toBe(0.8);
    expect(c.label).toBe(1); // 1-based source position
    // The deep-link detail RAG marks now carry (was sentence-index-only via cite-ref-click).
    expect(c.detail.parentDocId).toBe('docs/a.md');
    expect(c.detail.startLine).toBe(3);
    expect(c.detail.startChar).toBe(5);
    expect(c.hover.title).toBe('a.md'); // filenameOf the parentDocId
    expect(c.hover.excerpt).toBe('the cited passage');
    expect(c.sourceRefs).toEqual([0]);
  });

  it('drops an ungrounded claim (no sourceRefs) — neutral prose, no mark', () => {
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'Ungrounded.',
        verifiedScore: 0.1,
        lexicalScore: 0,
        verifiedRefs: [],
        lexicalRefs: [],
      },
    ];
    expect(claimsToCitations(claims, SOURCES)).toEqual([]);
  });

  it('returns [] when there are no sources to deep-link to', () => {
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'x',
        verifiedScore: 0.8,
        lexicalScore: 0,
        verifiedRefs: [0],
        lexicalRefs: [],
      },
    ];
    expect(claimsToCitations(claims, [])).toEqual([]);
  });
});

/* ───────────────────────────────────────────────────────────────────────────────────────────────
 * Tempdoc 822 §3d — THE PROVENANCE GATE. The streaming lexical matcher emits a word-overlap
 * coverage ratio (`hits / significantWords`, `StreamingCitationMatcher.matchSentenceLexical`); the
 * post-hoc matcher emits a cross-encoder relevance probability. The tier thresholds
 * (`evidenceProjection` TIER_HIGH/TIER_MEDIUM) are calibrated on the SECOND scale only. These tests
 * are the mutation probe: any route that lets a lexical score reach a tier fails them.
 * ─────────────────────────────────────────────────────────────────────────────────────────────── */
describe('claimsToCitations — the 822 §3d provenance gate', () => {
  const lexicalOnly = (score: number): Claim => ({
    sentenceIndex: 0,
    sentenceText: 'A sentence the lexical matcher liked.',
    verifiedScore: null,
    lexicalScore: score,
    // Deliberately a RESOLVABLE verified ref: only the §3d SCORE gate can drop this claim, so the
    // probe stays precise (the §3b ref gate below is tested on its own fixtures).
    verifiedRefs: [0],
    lexicalRefs: [],
  });

  it('mints NO citation for a lexical-only claim, however high its word overlap runs', () => {
    // 1.0 is reachable: every significant word of a short passage appearing in the sentence. Under
    // the old single-`score` model this was 'grounded' — the strongest tier, from word overlap.
    for (const overlap of [0.1, 0.33, 0.5, 0.6, 0.75, 1]) {
      expect(claimsToCitations([lexicalOnly(overlap)], SOURCES)).toEqual([]);
    }
  });

  it('carries the VERIFIED score, never the lexical one, when both producers scored the sentence', () => {
    const both: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'Grounded sentence.',
        verifiedScore: 0.52,
        // Higher than the verified score, and above TIER_HIGH — a `Math.max` across the two scales
        // (the defect) would surface 0.95 here and read 'grounded' instead of 'weak'.
        lexicalScore: 0.95,
        verifiedRefs: [0],
        lexicalRefs: [],
      },
    ];
    const out = claimsToCitations(both, SOURCES);
    expect(out).toHaveLength(1);
    expect(out[0]!.similarity).toBe(0.52);
  });

  it('fails CLOSED for a claim carrying no verified score field at all (legacy/untyped object)', () => {
    const legacy = [
      { sentenceIndex: 0, sentenceText: 'Legacy.', lexicalScore: 0.9, sourceRefs: [0] },
    ] as unknown as Claim[];
    expect(claimsToCitations(legacy, SOURCES)).toEqual([]);
  });
});

/* ───────────────────────────────────────────────────────────────────────────────────────────────
 * Tempdoc 822 §3b — THE NUMBERING CONTRACT's resolver half. Two rules, both mutation-probed:
 *   1. a claim resolves ONLY through a ref the authoritative matcher supplied (`verifiedRefs`);
 *   2. a ref that addresses no source mints NO citation — the `sources[refIdx] ?? sources[0]`
 *      fallback is gone, so a wrong-target deep link is unconstructible rather than merely rare.
 * The reproduction fixture is the gap report's: a streamed index of 59 against 5 sources, which
 * used to render a mark labelled 60 that deep-linked to source 1.
 * ─────────────────────────────────────────────────────────────────────────────────────────────── */
describe('claimsToCitations — the 822 §3b numbering contract', () => {
  const fiveSources: RetrievalCitation[] = Array.from({ length: 5 }, (_, i) => ({
    ...SOURCES[0]!,
    parentDocId: `docs/${i}.md`,
    excerpt: `passage ${i}`,
  }));

  it('mints NO citation for an out-of-range ref — and the old sources[0] result is GONE', () => {
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'A doubly-matched sentence.',
        verifiedScore: 0.8,
        lexicalScore: 0,
        verifiedRefs: [59],
        lexicalRefs: [],
      },
    ];
    const out = claimsToCitations(claims, fiveSources);
    expect(out).toEqual([]);
    // The precise assertion: not merely "no mark for source 59", but no mark pointing at source 0
    // either — which is exactly what the removed fallback produced.
    expect(out.some((c) => c.detail.parentDocId === 'docs/0.md')).toBe(false);
  });

  it('resolves through the VERIFIED ref even when a lexical ref arrived first', () => {
    // The live ordering: deltas stream before the post-hoc matches, so a merged ref list put the
    // lexical guess at index 0 and the resolver took it.
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'A doubly-matched sentence.',
        verifiedScore: 0.8,
        lexicalScore: 0.9,
        verifiedRefs: [3],
        lexicalRefs: [1],
      },
    ];
    const out = claimsToCitations(claims, fiveSources);
    expect(out).toHaveLength(1);
    expect(out[0]!.label).toBe(4);
    expect(out[0]!.detail.parentDocId).toBe('docs/3.md');
    expect(out[0]!.sourceRefs).toEqual([3]);
  });

  it('mints NO citation for a claim with lexical refs only, even with a verified score', () => {
    const claims: Claim[] = [
      {
        sentenceIndex: 0,
        sentenceText: 'The matcher scored it but tied it to no passage.',
        verifiedScore: 0.9,
        lexicalScore: 0.4,
        verifiedRefs: [],
        lexicalRefs: [2],
      },
    ];
    expect(claimsToCitations(claims, fiveSources)).toEqual([]);
  });
});

/* Tempdoc 822 §3b — the frame consequence, asserted rather than assumed: a dropped claim is not
 * counted as grounded, so the coverage read over the RESOLVED MARKS degrades and the frame moves. */
describe('the dropped claim degrades the frame (822 §3b)', () => {
  const sources: RetrievalCitation[] = Array.from({ length: 5 }, (_, i) => ({
    ...SOURCES[0]!,
    parentDocId: `docs/${i}.md`,
  }));
  const answer = 'One. Two. Three. Four. Five. Six.';
  const claim = (i: number, ref: number): Claim => ({
    sentenceIndex: i,
    sentenceText: `S${i}`,
    verifiedScore: 0.8,
    lexicalScore: 0,
    verifiedRefs: [ref],
    lexicalRefs: [],
  });

  it('reads "4 of 6" (partially-grounded), not "5 of 6", when one ref addresses no source', () => {
    const claims = [claim(0, 0), claim(1, 1), claim(2, 2), claim(3, 3), claim(4, 59)];
    const marks = claimsToCitations(claims, sources);
    expect(marks).toHaveLength(4);

    const cov = groundingCoverage(marks, answer);
    expect(cov.cited).toBe(4);
    expect(cov.total).toBe(6);
    expect(cov.label).toBe('Grounded · 4 of 6 sentences');
    expect(answerFrame('core.rag-ask', sources.length, cov, true, true)).toBe('partially-grounded');
  });
});

// Tempdoc 577 Phase 1 (Move F) — the ONE agent-answer resolver, extracted from UnifiedChatView so
// the Inspector's Answer tab grounds through the same authority. Pins the mapping + the skip rule.
import { resolveAnswerCitations } from './citationResolve.js';
import type { AgentSource, AgentSentenceCite } from '../../../api/generated/shape-handlers/shared.js';

const AGENT_SOURCES: AgentSource[] = [
  {
    parentDocId: 'docs/a.md',
    chunkIndex: 0,
    path: 'f:\docs\a.md',
    title: 'Doc A',
    excerpt: 'passage A',
    startLine: 3,
    endLine: 9,
    headingText: 'Intro',
  },
  {
    parentDocId: 'docs/b.md',
    chunkIndex: 1,
    path: 'f:\docs\b.md',
    title: 'Doc B',
    excerpt: 'passage B',
    startLine: 12,
    endLine: 20,
    headingText: '',
  },
];

describe('resolveAnswerCitations — the one agent answer→Citation resolver (577 Phase 1)', () => {
  it('maps a sentence cite to a Citation with the 1-based label + the citation-select deep-link detail', () => {
    const cites: AgentSentenceCite[] = [
      { sentenceText: 'Cited sentence.', sourceIndex: 1, similarity: 0.72 },
    ];
    const out = resolveAnswerCitations(AGENT_SOURCES, cites);
    expect(out).toHaveLength(1);
    const c = out[0]!;
    expect(c.label).toBe(2); // 1-based source position, cross-references the sources list
    expect(c.similarity).toBe(0.72);
    expect(c.detail.parentDocId).toBe('docs/b.md');
    expect(c.detail.startLine).toBe(12);
    expect(c.detail.endLine).toBe(20);
    expect(c.hover.title).toBe('Doc B');
    expect(c.hover.excerpt).toBe('passage B');
  });

  it('skips a cite whose sourceIndex has no source (defensive against a truncated sources list)', () => {
    const cites: AgentSentenceCite[] = [
      { sentenceText: 'Dangling.', sourceIndex: 9, similarity: 0.5 },
      { sentenceText: 'Valid.', sourceIndex: 0, similarity: 0.6 },
    ];
    const out = resolveAnswerCitations(AGENT_SOURCES, cites);
    expect(out).toHaveLength(1);
    expect(out[0]!.detail.parentDocId).toBe('docs/a.md');
  });

  it('returns [] when either side is empty', () => {
    expect(resolveAnswerCitations([], [{ sentenceText: 'x', sourceIndex: 0, similarity: 1 }])).toEqual([]);
    expect(resolveAnswerCitations(AGENT_SOURCES, [])).toEqual([]);
  });
});
