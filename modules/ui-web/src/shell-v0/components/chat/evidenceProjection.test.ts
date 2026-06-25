/**
 * Tempdoc 559 Authority IV — evidence-projection conformance.
 *
 * Proves the citation UI's view-model is a TOTAL projection of the retrieval-
 * evidence record (`RetrievalCitation` ← Java `ContextCitation`): every record
 * field is either represented in `EvidenceItem` or DELIBERATELY dropped — the
 * same lossy-downward discipline the Java `SearchTraceProjectionConformanceTest`
 * applies to the ranking sibling. Also pins the score-meaning fix: the score is
 * a labeled metric, not a bare scalar.
 */
import { describe, it, expect } from 'vitest';
import type { RetrievalCitation, CitationMatch } from './CitationsPanel.js';
import {
  toEvidenceItem,
  evidenceScore,
  evidenceTier,
  groundingClass,
  groundingLabel,
  groundingCoverage,
  countSentences,
  filenameOf,
  RELEVANCE_METRIC,
  declaredGroundingClass,
  answerFrame,
  answerFrameLabel,
  groundingDegraded,
  sourcesAreChunkPrecise,
  sourceGrounding,
  sourceGroundingLabel,
} from './evidenceProjection.js';

function match(overrides: Partial<CitationMatch> = {}): CitationMatch {
  return {
    sentenceIndex: 0,
    sentenceText: 's',
    chunkIndex: 0,
    similarity: 0.8,
    parentDocId: 'a.md',
    ...overrides,
  };
}

const FULL: RetrievalCitation = {
  parentDocId: 'C:/docs/handbook/onboarding.md',
  chunkIndex: 3,
  chunkTotal: 9,
  startChar: 120,
  endChar: 240,
  score: 0.83,
  excerpt: 'usually configured via the admin panel',
  startLine: 10,
  endLine: 14,
  headingText: 'Configuration',
  headingLevel: 2,
};

describe('evidenceProjection — total projection of the evidence record', () => {
  it('represents every field that the citation card needs', () => {
    const item = toEvidenceItem(FULL);
    // parentDocId → docId + location.parentDocId
    expect(item.docId).toBe(FULL.parentDocId);
    expect(item.location.parentDocId).toBe(FULL.parentDocId);
    // startChar/endChar/startLine/endLine → location (navigate-to-source)
    expect(item.location.startChar).toBe(FULL.startChar);
    expect(item.location.endChar).toBe(FULL.endChar);
    expect(item.location.startLine).toBe(FULL.startLine);
    expect(item.location.endLine).toBe(FULL.endLine);
    // score → labeled metric; excerpt + headingText carried verbatim
    expect(item.score.value).toBeCloseTo(0.83);
    expect(item.excerpt).toBe(FULL.excerpt);
    expect(item.headingText).toBe(FULL.headingText);
    // parentDocId tail → filename
    expect(item.filename).toBe('onboarding.md');
  });

  it('classifies every RetrievalCitation field as represented or deliberately dropped', () => {
    // The exhaustive field set — a new record field forces a decision here.
    const REPRESENTED = new Set([
      'parentDocId',
      'startChar',
      'endChar',
      'score',
      'excerpt',
      'startLine',
      'endLine',
      'headingText',
    ]);
    // Deliberately dropped — not needed by the citation card (chunk indexing +
    // heading depth are navigation-internal, not user-facing evidence).
    const DROPPED = new Set(['chunkIndex', 'chunkTotal', 'headingLevel']);
    const allFields = Object.keys(FULL);
    for (const f of allFields) {
      expect(REPRESENTED.has(f) || DROPPED.has(f)).toBe(true);
    }
    // No phantom classification (every classified field really exists).
    for (const f of [...REPRESENTED, ...DROPPED]) {
      expect(allFields).toContain(f);
    }
  });

  it('the score carries a declared metric label — not a bare scalar', () => {
    const item = toEvidenceItem(FULL);
    expect(item.score.label).toBe(RELEVANCE_METRIC);
    expect(item.score.label).toBe('Relevance');
  });

  it('score projects to clamped value / percent / tier', () => {
    expect(evidenceScore(1).pct).toBe(100);
    expect(evidenceScore(1).tier).toBe('high');
    expect(evidenceScore(0.5).pct).toBe(50);
    expect(evidenceScore(0.5).tier).toBe('medium');
    expect(evidenceScore(0.1).tier).toBe('low');
    // out-of-range clamps
    expect(evidenceScore(1.7).value).toBe(1);
    expect(evidenceScore(-0.2).value).toBe(0);
  });

  it('tier thresholds are the single authority, anchored to the matcher cutoff (0.6/0.5)', () => {
    expect(evidenceTier(0.6)).toBe('high');
    expect(evidenceTier(0.59)).toBe('medium');
    expect(evidenceTier(0.5)).toBe('medium'); // = the matcher cutoff: a cited sentence is ≥ this
    expect(evidenceTier(0.49)).toBe('low'); // below the cutoff → only UNcited prose lands here
  });

  // Tempdoc 565 §15.A — the grounding presentations (inline-mark class, hover label) derive from the
  // ONE tier authority. Before §15 these were forked (MarkdownBlock/StreamingTextBlock 0.5/0.2;
  // CitationHoverCard 0.5/0.2) so a single similarity classified DIFFERENTLY across surfaces; these
  // pin that one similarity now maps to one tier everywhere (and the boundaries track `evidenceTier`).
  it('groundingClass derives the inline-mark stem from the one tier authority', () => {
    expect(groundingClass(0.6)).toBe('grounded'); // high
    expect(groundingClass(0.59)).toBe('weak'); // medium
    expect(groundingClass(0.5)).toBe('weak'); // medium (a cited sentence at the cutoff reads weak, not ungrounded)
    expect(groundingClass(0.49)).toBe('ungrounded'); // low (below cutoff = uncited prose)
  });

  it('groundingLabel derives the hover word from the SAME tier authority', () => {
    expect(groundingLabel(0.6)).toBe('strong'); // high
    expect(groundingLabel(0.59)).toBe('moderate'); // medium
    expect(groundingLabel(0.49)).toBe('weak'); // low
  });

  it('one similarity → one tier across every grounding surface (the §15.A fix)', () => {
    // A mid-band value (above the 0.5 cutoff, below the 0.6 grounded bar) reads consistently 'weak'.
    const s = 0.55;
    const tier = evidenceTier(s); // 'medium'
    expect(tier).toBe('medium');
    // class + label + score.tier now all agree on that one tier — no surface disagrees.
    expect(groundingClass(s)).toBe('weak');
    expect(groundingLabel(s)).toBe('moderate');
    expect(evidenceScore(s).tier).toBe('medium');
  });

  it('filenameOf handles both path separators and bare ids', () => {
    expect(filenameOf('a/b/c.md')).toBe('c.md');
    expect(filenameOf('a\\b\\c.md')).toBe('c.md');
    expect(filenameOf('bare')).toBe('bare');
  });
});

// Tempdoc 565 §14 ④/⑤ — the grounding-honesty read (readiness + coverage) over the one §15.A verdict.
describe('groundingCoverage — the ④ readiness + ⑤ coverage read', () => {
  it('countSentences splits prose best-effort and is ≥1 for non-empty text', () => {
    expect(countSentences('')).toBe(0);
    expect(countSentences('One sentence.')).toBe(1);
    expect(countSentences('First. Second! Third?')).toBe(3);
    expect(countSentences('No terminator here')).toBe(1); // text but no punctuation ⇒ one sentence
  });

  it('④ ready=false + "Not grounded" when no sentence is cited', () => {
    const c = groundingCoverage([], 'An answer with three sentences. Two. Three.');
    expect(c.ready).toBe(false);
    expect(c.cited).toBe(0);
    expect(c.label).toBe('Not grounded');
  });

  it('④ ready + ⑤ "N of M" counts grounded/weak cites via the §15.A verdict', () => {
    // similarities: 0.8 grounded, 0.55 weak (≥0.5 cutoff, <0.6 high), 0.49 below-cutoff (not cited in practice).
    const cites = [{ similarity: 0.8 }, { similarity: 0.55 }];
    const c = groundingCoverage(cites, 'Alpha statement. Beta statement. Gamma. Delta. Epsilon.');
    expect(c.ready).toBe(true);
    expect(c.grounded).toBe(1); // 0.8 ⇒ grounded
    expect(c.weak).toBe(1); // 0.55 ⇒ weak
    expect(c.cited).toBe(2); // N
    expect(c.total).toBe(5); // M
    expect(c.label).toBe('Grounded · 2 of 5 sentences');
  });

  it('total (M) is never less than cited (N)', () => {
    // more cites than the heuristic finds sentences ⇒ total clamps up to cited.
    const c = groundingCoverage([{ similarity: 0.9 }, { similarity: 0.9 }, { similarity: 0.9 }], 'Short');
    expect(c.cited).toBe(3);
    expect(c.total).toBeGreaterThanOrEqual(3);
  });
});

// Tempdoc 577 §2.12 Move 3 — the epistemic answer-frame authority.
describe('answerFrame — the epistemic frame (declared class × actual outcome)', () => {
  it('declares each shape a grounding class (exhaustive over the interaction shapes)', () => {
    expect(declaredGroundingClass('core.agent-run')).toBe('grounded-index');
    expect(declaredGroundingClass('core.rag-ask')).toBe('grounded-index');
    expect(declaredGroundingClass('core.workflow-run')).toBe('grounded-index');
    expect(declaredGroundingClass('core.free-chat')).toBe('ungrounded-llm');
    expect(declaredGroundingClass('core.extract')).toBe('transform');
  });

  it('an ungrounded-LLM shape is always framed ungrounded — regardless of (absent) evidence', () => {
    expect(answerFrame('core.free-chat', 0, groundingCoverage([], 'Some answer.'))).toBe('ungrounded');
    // even if the model fabricated markers and we had a stray cite, the shape declares ungrounded:
    expect(answerFrame('core.free-chat', 3, groundingCoverage([{ similarity: 0.9 }], 'A. B.'))).toBe(
      'ungrounded',
    );
  });

  it('a transform shape (extract) is framed transform', () => {
    expect(answerFrame('core.extract', 0, groundingCoverage([], '{json}'))).toBe('transform');
  });

  it('an index-grounded shape with ZERO sources is honestly ungrounded (the §2.9 V1 case)', () => {
    expect(answerFrame('core.agent-run', 0, groundingCoverage([], 'Based on [1] and [2].'))).toBe(
      'ungrounded',
    );
  });

  it('index-grounded with sources but NO per-sentence cites is grounded (marks pending, not partial)', () => {
    expect(answerFrame('core.agent-run', 4, groundingCoverage([], 'A. B. C.'))).toBe('grounded');
  });

  it('603 D-4: index-grounded, sources but no cites AND document-level (not chunk-precise) is SOURCED', () => {
    // Marks can NEVER arrive for document-level sources (no chunk identity → the matcher cannot run), so
    // cited===0 is provenance, not "marks pending" — the `sourced` frame, never "grounded".
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), false)).toBe('sourced');
    // The SAME zero-cite coverage with chunk-precise sources (default) stays grounded (marks pending).
    expect(answerFrame('core.agent-run', 2, groundingCoverage([], 'A. B.'), true)).toBe('grounded');
    // Partial coverage outranks the doc-level check (some sentences DID cite) — still partially-grounded.
    const cov = groundingCoverage([{ similarity: 0.9 }], 'Alpha. Beta. Gamma.');
    expect(answerFrame('core.agent-run', 2, cov, false)).toBe('partially-grounded');
  });

  it('index-grounded where some sentences cite and others do not is partially-grounded', () => {
    // 1 cited of 3 sentences ⇒ partial.
    const cov = groundingCoverage([{ similarity: 0.9 }], 'Alpha. Beta. Gamma.');
    expect(cov.cited).toBe(1);
    expect(cov.total).toBe(3);
    expect(answerFrame('core.agent-run', 2, cov)).toBe('partially-grounded');
  });

  it('frame labels: grounded is silent; transform (603 C3) + partial + ungrounded carry an honest header', () => {
    expect(answerFrameLabel('grounded')).toBeNull();
    // 603 C3 — an extraction (transform) is no longer silent: it is the model's own structuring, not
    // retrieved data, so it carries an unmissable "not retrieved from your documents" marker.
    expect(answerFrameLabel('transform')).toMatch(/not retrieved from your documents/i);
    expect(answerFrameLabel('partially-grounded')).toMatch(/not backed/i);
    // Tempdoc 577 §2.16 — the default (non-degraded) ungrounded label names the no-search mode.
    expect(answerFrameLabel('ungrounded')).toMatch(/does not search/i);
    // The degraded variant is honest that the run SEARCHED but found nothing to cite.
    expect(answerFrameLabel('ungrounded', true)).toMatch(/found nothing to cite/i);
    // 603 D-4 — the SOURCED label states provenance without claiming per-sentence grounding.
    expect(answerFrameLabel('sourced')).toMatch(/per-sentence grounding not verified/i);
  });

  describe('sourcesAreChunkPrecise — 603 D-4 doc-level vs chunk-precise discriminator', () => {
    it('all document-level (chunkIndex === -1 sentinel) ⇒ not chunk-precise', () => {
      expect(sourcesAreChunkPrecise([{ chunkIndex: -1 }, { chunkIndex: -1 }])).toBe(false);
    });
    it('any real chunk ordinal (including 0) ⇒ chunk-precise', () => {
      expect(sourcesAreChunkPrecise([{ chunkIndex: -1 }, { chunkIndex: 0 }])).toBe(true);
      expect(sourcesAreChunkPrecise([{ chunkIndex: 3 }])).toBe(true);
    });
    it('a source type without chunkIndex (RAG RetrievalCitation) ⇒ chunk-precise (no sentinel)', () => {
      expect(sourcesAreChunkPrecise([{}])).toBe(true);
    });
    it('empty list ⇒ not chunk-precise (vacuous)', () => {
      expect(sourcesAreChunkPrecise([])).toBe(false);
    });
  });

  it('577 §2.16 — groundingDegraded distinguishes searched-but-uncitable from no-search', () => {
    // A grounded-index shape (agent/RAG) with ZERO sources searched but couldn't cite ⇒ degraded.
    expect(groundingDegraded('core.agent-run', 0)).toBe(true);
    expect(groundingDegraded('core.rag-ask', 0)).toBe(true);
    // With sources, it is not degraded (it cited something).
    expect(groundingDegraded('core.agent-run', 3)).toBe(false);
    // A free-chat shape never searches ⇒ NOT degraded (it is ungrounded by design, not by failure).
    expect(groundingDegraded('core.free-chat', 0)).toBe(false);
    // A transform shape is not a grounding case at all.
    expect(groundingDegraded('core.extract', 0)).toBe(false);
  });
});

describe('sourceGrounding — faithfulness join by ARRAY POSITION (603 C1 / PART X.B)', () => {
  it('joins a source to its grounded sentences by its array position (match.chunkIndex === sourceIndex)', () => {
    // The match `chunkIndex` is the source's POSITION in the rag.citations list (the established convention
    // the inline marks use), NOT a document ordinal. Source at index 2 is grounded by matches at position 2.
    const g = sourceGrounding(2, [
      match({ chunkIndex: 2, parentDocId: 'a.md', similarity: 0.7 }),
      match({ chunkIndex: 2, parentDocId: 'a.md', similarity: 0.9 }),
      match({ chunkIndex: 5, parentDocId: 'a.md', similarity: 0.99 }), // different position — excluded
    ], 'a.md');
    expect(g.cited).toBe(true);
    expect(g.groundedSentences).toBe(2);
    expect(g.similarity).toBe(0.9); // strongest match
    expect(g.tier).toBe('high'); // 0.9 ≥ 0.6
  });

  it('DECISIVE — position-join, not doc-ordinal: a match at position 1 grounds index 1 even when ordinals differ', () => {
    // This is the §1 "everything uncited" bug guard. Sources have NON-sequential doc-ordinals; the join must
    // key on the array POSITION (what the matcher emits), so an ordinal compare would mis-assign here.
    const matches = [match({ chunkIndex: 1, parentDocId: 'sys.md', similarity: 0.95 })];
    const grounded = sourceGrounding(1, matches, 'sys.md'); // the source at array-index 1
    expect(grounded.cited).toBe(true);
    expect(grounded.groundedSentences).toBe(1);
    const other = sourceGrounding(0, matches, 'other.md'); // a different source at index 0
    expect(other.cited).toBe(false);
  });

  it('parentDocId guards a position whose match is from a different document', () => {
    const g = sourceGrounding(0, [match({ chunkIndex: 0, parentDocId: 'b.md' })], 'a.md');
    expect(g.cited).toBe(false);
  });

  it('a retrieved-but-unmatched source is uncited (low tier, similarity 0)', () => {
    const g = sourceGrounding(0, [match({ chunkIndex: 3, parentDocId: 'a.md' })], 'a.md');
    expect(g.cited).toBe(false);
    expect(g.groundedSentences).toBe(0);
    expect(g.similarity).toBe(0);
    expect(g.tier).toBe('low'); // never "high confidence"
  });

  it('sourceGroundingLabel: count when cited, honest "not cited" otherwise', () => {
    expect(sourceGroundingLabel({ cited: true, groundedSentences: 1, similarity: 0.8, tier: 'high' as never })).toBe('Grounds 1 sentence');
    expect(sourceGroundingLabel({ cited: true, groundedSentences: 3, similarity: 0.8, tier: 'high' as never })).toBe('Grounds 3 sentences');
    expect(sourceGroundingLabel({ cited: false, groundedSentences: 0, similarity: 0, tier: 'low' as never })).toBe('Retrieved · not cited');
  });
});
