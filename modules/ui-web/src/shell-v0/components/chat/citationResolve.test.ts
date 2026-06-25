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
      { sentenceIndex: 0, sentenceText: 'Grounded sentence.', score: 0.8, sourceRefs: [0] },
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
      { sentenceIndex: 0, sentenceText: 'Ungrounded.', score: 0.1, sourceRefs: [] },
    ];
    expect(claimsToCitations(claims, SOURCES)).toEqual([]);
  });

  it('returns [] when there are no sources to deep-link to', () => {
    const claims: Claim[] = [
      { sentenceIndex: 0, sentenceText: 'x', score: 0.8, sourceRefs: [0] },
    ];
    expect(claimsToCitations(claims, [])).toEqual([]);
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
