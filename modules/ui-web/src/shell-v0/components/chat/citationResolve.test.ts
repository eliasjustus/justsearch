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
        sourceRefs: [0],
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
        sourceRefs: [],
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
        sourceRefs: [0],
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
    sourceRefs: [0],
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
        sourceRefs: [0],
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
