/**
 * Tempdoc 865 §7.4 — the guard for the `agent-tool-search-card` register row
 * (`governance/execution-surfaces.v1.json`).
 *
 * `toolSearchCard.ts` is the delegate plane's SECOND evidence surface: it mints a
 * `SearchProvenance` — "every label here is a positive claim about how the search ran"
 * (`ResultsCard.ts:126-133`) — from the same `structuredData.searchResults` the Java mint reads
 * (`AgentSession.collectGroundingSources`). The register declares it a projection; this file is
 * what makes that declaration bite.
 *
 * **The divergence is DELIBERATE, and this pins it as such.** The two surfaces answer different
 * questions, so they are not merged:
 *
 *  - the tool card is a **receipt of one call** — every hit that call returned, in that call's own
 *    order, addressable identity or not;
 *  - the accumulator is the **run's evidence set** — deduped across calls, identity-bearing only,
 *    positionally aligned with the citation indices.
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
import { agentSearchCardData, findAgentSearchHit, hasAgentSearchEvidence } from './toolSearchCard.js';

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
};

/**
 * The shared two-call fixture. Call 1 returns A + B; call 2 returns A + B again, plus C. Three
 * distinct documents; five hits across the two calls.
 */
const CALL_1 = { query: 'chunk collapse', resultCount: 2, searchResults: [HIT_A, HIT_B] };
const CALL_2 = { query: 'chunk collapse depth', resultCount: 3, searchResults: [HIT_A, HIT_B, HIT_C] };

const AT = '2026-08-25T10:00:00Z';

describe('toolSearchCard — the projection law', () => {
  it('projects one card row per searchResults entry, with path as the row identity', () => {
    const data = agentSearchCardData(CALL_1, undefined, AT);
    expect(data).not.toBeNull();
    expect(data!.snapshot.results.map((h) => h.id)).toEqual(['f:/docs/a.md', 'f:/docs/b.md']);
    expect(data!.snapshot.results.map((h) => h.path)).toEqual(['f:/docs/a.md', 'f:/docs/b.md']);
    expect(data!.snapshot.results.map((h) => h.title)).toEqual(['Doc A', 'Doc B']);
  });

  it('falls back to the filename for an empty title, never a raw path or a blank row', () => {
    const { snapshot } = agentSearchCardData(
      { query: 'q', searchResults: [{ title: '', path: 'f:/docs/deep/notes.md', excerpt: 'e' }] },
      undefined,
      AT,
    )!;
    expect(snapshot.results[0]!.title).toBe('notes.md');
  });

  it('mints the provenance from the record: actor agent, the executed query, the carried counts', () => {
    const { provenance } = agentSearchCardData(CALL_2, undefined, AT)!;
    expect(provenance.actor).toBe('agent');
    expect(provenance.query).toBe('chunk collapse depth');
    expect(provenance.matchCount).toBe(3);
    expect(provenance.resultCount).toBe(3);
    expect(provenance.executedAt).toBe(AT);
    // No retrieval-mode signal rides the agent tool's structuredData, so the mode segment omits
    // rather than asserting a mode the record does not carry.
    expect(provenance.mode).toBe('');
  });

  it('derives resultCount from the hit list when an old record lacks the top-level key', () => {
    const { snapshot, provenance } = agentSearchCardData({ query: 'q', searchResults: [HIT_A] }, undefined, AT)!;
    expect(snapshot.matchCount).toBe(1);
    expect(snapshot.totalHits).toBe(1);
    expect(provenance.resultCount).toBe(1);
  });

  it('recovers a missing query from the tool call arguments, and returns null when it cannot', () => {
    const noQuery = { searchResults: [HIT_A] };
    expect(agentSearchCardData(noQuery, '{"query":"from arguments"}', AT)!.provenance.query).toBe(
      'from arguments',
    );
    // Nothing to derive an honest query from: no card at all, rather than a fabricated header.
    expect(agentSearchCardData(noQuery, undefined, AT)).toBeNull();
    expect(agentSearchCardData(noQuery, 'not json', AT)).toBeNull();
  });

  it('has no evidence card without searchResults', () => {
    expect(hasAgentSearchEvidence({ searchResults: [] })).toBe(false);
    expect(hasAgentSearchEvidence(CALL_1)).toBe(true);
    expect(agentSearchCardData({ query: 'q' }, undefined, AT)).toBeNull();
  });

  it('looks a hit up by its row identity for the reading-pane open path', () => {
    expect(findAgentSearchHit(CALL_2, 'f:/docs/c.md')?.title).toBe('Doc C');
    expect(findAgentSearchHit(CALL_2, 'f:/docs/c.md')?.snippet).toBe('passage C');
    expect(findAgentSearchHit(CALL_2, 'f:/docs/missing.md')).toBeUndefined();
  });
});

describe('toolSearchCard — the deliberate cross-call divergence from the run evidence mint', () => {
  it('shows a repeated document on EVERY call that returned it (a per-call receipt, not a run set)', () => {
    const call1 = agentSearchCardData(CALL_1, undefined, AT)!;
    const call2 = agentSearchCardData(CALL_2, undefined, AT)!;

    // A and B came back on both calls. The card does not know call 1 happened, and must not:
    // suppressing them on call 2 would make that card a false receipt of what call 2 returned.
    expect(call1.snapshot.results.map((h) => h.id)).toContain('f:/docs/a.md');
    expect(call2.snapshot.results.map((h) => h.id)).toContain('f:/docs/a.md');
    expect(call1.snapshot.results.map((h) => h.id)).toContain('f:/docs/b.md');
    expect(call2.snapshot.results.map((h) => h.id)).toContain('f:/docs/b.md');
  });

  it('totals FIVE card rows across the two calls for THREE distinct documents', () => {
    const rows = [
      ...agentSearchCardData(CALL_1, undefined, AT)!.snapshot.results,
      ...agentSearchCardData(CALL_2, undefined, AT)!.snapshot.results,
    ];

    expect(rows).toHaveLength(5);
    expect(new Set(rows.map((h) => h.id)).size).toBe(3);
    // The gap IS the divergence, and it is legible rather than silent: this call returned 3; the
    // run drew on 3 distinct documents out of 5 hits. The mint emits exactly those 3 — pinned on
    // the real `collectGroundingSources()` by the Java half named in this file's header.
    expect(rows.length).toBeGreaterThan(new Set(rows.map((h) => h.id)).size);
  });

  it('reports each call count on its own card, never a running total', () => {
    expect(agentSearchCardData(CALL_1, undefined, AT)!.provenance.resultCount).toBe(2);
    expect(agentSearchCardData(CALL_2, undefined, AT)!.provenance.resultCount).toBe(3);
  });
});
