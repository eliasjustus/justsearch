/**
 * Tempdoc 865 §7.4 — the guard for the `agent-tool-search-card` register row
 * (`governance/execution-surfaces.v1.json`).
 *
 * `toolSearchCard.ts` is the delegate plane's SECOND evidence surface: it projects the same
 * `structuredData.searchResults` the Java mint reads (`AgentSession.collectGroundingSources`) into
 * the shape `ToolCallCard`'s own level-2 evidence body renders (tempdoc 867). The register declares
 * it a projection; this file is what makes that declaration bite.
 *
 * **The divergence from the run's evidence-set mint is DELIBERATE, and this pins it as such.** The
 * two surfaces answer different questions, so they are not merged:
 *
 *  - the tool card is a **receipt of one call** — every hit that call returned, in that call's own
 *    order, addressable identity or not;
 *  - the accumulator is the **run's evidence set** — deduped across calls, identity-bearing only,
 *    positionally aligned with the citation indices.
 *
 * Tempdoc 867 adds a JOIN, not a merge: `inEvidence` asks "is this receipt row ALSO in the run's
 * evidence set" against a `Set<string>` handed in from outside — it never re-derives the set itself,
 * so a call's own (possibly absent) grounding delta cannot change what the JOIN reports for a
 * document another call already established (the "duplicate-search" case below).
 *
 * **It is also cross-call ONLY.** The worker collapses chunk hits to one hit per parent document
 * before results ever reach the agent (`SearchExecutor.collapseChunkHitsToParents`,
 * `modules/worker-services/.../execute/SearchExecutor.java:1029-1051`), so within a single call
 * there are no duplicate parents to dedup and no identity-less hits to drop: a single-call fixture
 * would pass trivially and prove nothing. Every divergence case below therefore runs TWO calls that
 * share documents.
 *
 * The mint half of the same fixture lives in
 * `AgentSessionGroundingTest.cardsAreAPerCallReceipt_whileTheMintAccumulatesDistinctDocumentsAcrossCalls`
 * (`modules/app-agent`), which runs the real `collectGroundingSources()` — remove either dedup
 * branch there and the distinct-document count this file's story depends on goes red.
 */
import { describe, it, expect } from 'vitest';
import {
  agentSearchCardProjection,
  findAgentSearchHit,
  hasAgentSearchEvidence,
} from './toolSearchCard.js';

/** A chunk-precise hit as `SearchTool.buildSearchEvidence` emits it (parentDocId + chunkIndex). */
const HIT_A = {
  title: 'Doc A',
  path: 'f:/docs/a.md',
  excerpt: 'passage A',
  line: 3,
  parentDocId: 'docs/a.md',
  chunkIndex: 2,
  startLine: 3,
  endLine: 9,
};

/** A document-level hit — no `parentDocId`; the whole document is the source (603 D-3). */
const HIT_B = { title: 'Doc B', path: 'f:/docs/b.md', excerpt: 'passage B', line: 0 };

const HIT_C = {
  title: 'Doc C',
  path: 'f:/docs/c.md',
  excerpt: 'passage C',
  line: 11,
  parentDocId: 'docs/c.md',
  chunkIndex: 0,
  startLine: 11,
  endLine: 14,
  headingText: 'Chapter 2',
};

/**
 * The shared two-call fixture. Call 1 returns A + B; call 2 returns A + B again, plus C. Three
 * distinct documents; five hits across the two calls.
 */
const CALL_1 = { query: 'chunk collapse', resultCount: 2, searchResults: [HIT_A, HIT_B] };
const CALL_2 = { query: 'chunk collapse depth', resultCount: 3, searchResults: [HIT_A, HIT_B, HIT_C] };

const EMPTY = new Set<string>();

describe('toolSearchCard — the projection law', () => {
  it('projects one card row per searchResults entry, with path as the row identity', () => {
    const data = agentSearchCardProjection(CALL_1, undefined, EMPTY);
    expect(data).not.toBeNull();
    expect(data!.hits.map((h) => h.id)).toEqual(['f:/docs/a.md', 'f:/docs/b.md']);
    expect(data!.hits.map((h) => h.path)).toEqual(['f:/docs/a.md', 'f:/docs/b.md']);
    expect(data!.hits.map((h) => h.title)).toEqual(['Doc A', 'Doc B']);
  });

  it('falls back to the filename for an empty title, never a raw path or a blank row', () => {
    const { hits } = agentSearchCardProjection(
      { query: 'q', searchResults: [{ title: '', path: 'f:/docs/deep/notes.md', excerpt: 'e' }] },
      undefined,
      EMPTY,
    )!;
    expect(hits[0]!.title).toBe('notes.md');
  });

  it('mints query/scope/mode/resultCount from the record', () => {
    const data = agentSearchCardProjection(CALL_2, undefined, EMPTY)!;
    expect(data.query).toBe('chunk collapse depth');
    expect(data.resultCount).toBe(3);
    expect(data.scope).toBe('');
    // 867 §2a's named gap: CALL_2's fixture predates the `searchMode` stamp, so mode is honestly ''.
    expect(data.mode).toBe('');
  });

  it('reads the RESOLVED mode from `structuredData.searchMode`, verbatim, never re-derived', () => {
    const data = agentSearchCardProjection(
      { ...CALL_1, searchMode: 'hybrid' },
      undefined,
      EMPTY,
    )!;
    expect(data.mode).toBe('hybrid');
  });

  it('reads the scope from the tool call\'s own `path_prefix` argument', () => {
    const data = agentSearchCardProjection(
      CALL_1,
      '{"query":"chunk collapse","path_prefix":"f:/docs"}',
      EMPTY,
    )!;
    expect(data.scope).toBe('f:/docs');
  });

  it('derives resultCount from the hit list when an old record lacks the top-level key', () => {
    const { resultCount } = agentSearchCardProjection(
      { query: 'q', searchResults: [HIT_A] },
      undefined,
      EMPTY,
    )!;
    expect(resultCount).toBe(1);
  });

  it('recovers a missing query from the tool call arguments, and returns null when it cannot', () => {
    const noQuery = { searchResults: [HIT_A] };
    expect(
      agentSearchCardProjection(noQuery, '{"query":"from arguments"}', EMPTY)!.query,
    ).toBe('from arguments');
    // Nothing to derive an honest query from: no card at all, rather than a fabricated header.
    expect(agentSearchCardProjection(noQuery, undefined, EMPTY)).toBeNull();
    expect(agentSearchCardProjection(noQuery, 'not json', EMPTY)).toBeNull();
  });

  it('has no evidence card without searchResults', () => {
    expect(hasAgentSearchEvidence({ searchResults: [] })).toBe(false);
    expect(hasAgentSearchEvidence(CALL_1)).toBe(true);
    expect(agentSearchCardProjection({ query: 'q' }, undefined, EMPTY)).toBeNull();
  });

  it('looks a hit up by its row identity for the reading-pane open path', () => {
    expect(findAgentSearchHit(CALL_2, 'f:/docs/c.md')?.title).toBe('Doc C');
    expect(findAgentSearchHit(CALL_2, 'f:/docs/c.md')?.snippet).toBe('passage C');
    expect(findAgentSearchHit(CALL_2, 'f:/docs/missing.md')).toBeUndefined();
  });
});

describe('toolSearchCard — the locator (867)', () => {
  it('prefers headingText when the backend reported one', () => {
    const { hits } = agentSearchCardProjection(CALL_2, undefined, EMPTY)!;
    expect(hits.find((h) => h.path === 'f:/docs/c.md')?.locator).toBe('Chapter 2');
  });

  it('falls back to `Line N` when there is no heading but a positive line', () => {
    const { hits } = agentSearchCardProjection(CALL_1, undefined, EMPTY)!;
    expect(hits.find((h) => h.path === 'f:/docs/a.md')?.locator).toBe('Line 3');
  });

  it('is null when neither headingText nor a positive line is available', () => {
    const { hits } = agentSearchCardProjection(CALL_1, undefined, EMPTY)!;
    expect(hits.find((h) => h.path === 'f:/docs/b.md')?.locator).toBeNull();
  });
});

describe('toolSearchCard — the evidence JOIN (867), never a re-derivation', () => {
  it('marks a hit in-evidence when its path is in the handed-in set, and counts it', () => {
    const inEvidence = new Set(['f:/docs/a.md']);
    const data = agentSearchCardProjection(CALL_1, undefined, inEvidence)!;
    expect(data.hits.find((h) => h.path === 'f:/docs/a.md')?.inEvidence).toBe(true);
    expect(data.hits.find((h) => h.path === 'f:/docs/b.md')?.inEvidence).toBe(false);
    expect(data.evidenceCount).toBe(1);
  });

  it('an empty evidence set marks every hit not-in-evidence, honestly — never a guess', () => {
    const data = agentSearchCardProjection(CALL_1, undefined, EMPTY)!;
    expect(data.hits.every((h) => h.inEvidence === false)).toBe(true);
    expect(data.evidenceCount).toBe(0);
  });

  // The duplicate-search case: call 2 returns A again. Whether call 2's OWN structuredData carried a
  // fresh grounding delta for A is irrelevant to this JOIN — the evidence set is handed in from
  // OUTSIDE (the run's accumulator), so a document another call already established stays in-evidence
  // here even when nothing about the delta is visible to this projection call.
  it('a hit is in-evidence by SET MEMBERSHIP alone, regardless of which call established it', () => {
    const runEvidence = new Set(['f:/docs/a.md']); // established by call 1, not re-stated by call 2
    const call2 = agentSearchCardProjection(CALL_2, undefined, runEvidence)!;
    expect(call2.hits.find((h) => h.path === 'f:/docs/a.md')?.inEvidence).toBe(true);
    expect(call2.hits.find((h) => h.path === 'f:/docs/c.md')?.inEvidence).toBe(false);
    expect(call2.evidenceCount).toBe(1);
  });
});

describe('toolSearchCard — the deliberate cross-call divergence from the run evidence mint', () => {
  it('shows a repeated document on EVERY call that returned it (a per-call receipt, not a run set)', () => {
    const call1 = agentSearchCardProjection(CALL_1, undefined, EMPTY)!;
    const call2 = agentSearchCardProjection(CALL_2, undefined, EMPTY)!;

    // A and B came back on both calls. The card does not know call 1 happened, and must not:
    // suppressing them on call 2 would make that card a false receipt of what call 2 returned.
    expect(call1.hits.map((h) => h.id)).toContain('f:/docs/a.md');
    expect(call2.hits.map((h) => h.id)).toContain('f:/docs/a.md');
    expect(call1.hits.map((h) => h.id)).toContain('f:/docs/b.md');
    expect(call2.hits.map((h) => h.id)).toContain('f:/docs/b.md');
  });

  it('totals FIVE card rows across the two calls for THREE distinct documents', () => {
    const rows = [
      ...agentSearchCardProjection(CALL_1, undefined, EMPTY)!.hits,
      ...agentSearchCardProjection(CALL_2, undefined, EMPTY)!.hits,
    ];

    expect(rows).toHaveLength(5);
    expect(new Set(rows.map((h) => h.id)).size).toBe(3);
    // The gap IS the divergence, and it is legible rather than silent: this call returned 3; the
    // run drew on 3 distinct documents out of 5 hits. The mint emits exactly those 3 — pinned on
    // the real `collectGroundingSources()` by the Java half named in this file's header.
    expect(rows.length).toBeGreaterThan(new Set(rows.map((h) => h.id)).size);
  });

  it('reports each call count on its own card, never a running total', () => {
    expect(agentSearchCardProjection(CALL_1, undefined, EMPTY)!.resultCount).toBe(2);
    expect(agentSearchCardProjection(CALL_2, undefined, EMPTY)!.resultCount).toBe(3);
  });
});
