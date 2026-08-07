/**
 * The records laws (tempdoc 818 slice 1) — L4, L6, and the L8 corollary as executable tests.
 *
 * These pin the from-scratch payoff: a committed search is a SNAPSHOT (so the shipped window's
 * "search results as a live transcript" staleness class cannot occur), every count is computed
 * from the set it describes (so the count-truthfulness recurrence cannot occur), and the session
 * name is a projection of the first committed record (so "New chat is state-gated" cannot occur).
 */
import { describe, it, expect } from 'vitest';
import {
  NO_RECORDS,
  UNNAMED_SESSION,
  commitSearch,
  freezeSearch,
  projectIndex,
  projectSessionName,
  projectTranscript,
  type SearchCapture,
  type SessionRecord,
  type TranscriptFrozenItem,
} from './records.js';

/** Narrowing helper — the transcript is a union, and these assertions are about frozen blocks. */
function frozenAt(records: readonly SessionRecord[], i = 0): TranscriptFrozenItem {
  const item = projectTranscript(records)[i];
  if (!item || item.kind !== 'frozen-search') throw new Error(`no frozen block at index ${i}`);
  return item;
}

interface MutableHit {
  id: string;
  title: string;
  path: string;
  snippet?: string;
}

function liveHits(n: number): MutableHit[] {
  return Array.from({ length: n }, (_, i) => ({
    id: `d${i}`,
    title: `Document ${i}`,
    path: `Contracts/${i}.pdf`,
    snippet: `…passage ${i}…`,
  }));
}

function capture(over: Partial<SearchCapture> = {}): SearchCapture {
  return {
    query: 'northfield renewal',
    hits: liveHits(3),
    total: 12,
    mode: 'refined',
    tookMs: 42,
    ...over,
  };
}

describe('818 records — freezing (L4)', () => {
  it('L4 — a frozen record is a snapshot: mutating the SOURCE array afterwards changes nothing', () => {
    const source = liveHits(3);
    const frozen = freezeSearch('r0', capture({ hits: source }));

    source.push({ id: 'd99', title: 'Later arrival', path: 'Later.pdf' });
    const head = source[0];
    if (head) head.title = 'Renamed after the fact';
    source.length = 1;

    expect(frozen.hits).toHaveLength(3);
    expect(frozen.hits[0]?.title).toBe('Document 0');
    expect(frozen.hits.map((h) => h.id)).toEqual(['d0', 'd1', 'd2']);
  });

  it('L4 — the record itself is frozen: a later write cannot mutate a committed block', () => {
    const frozen = freezeSearch('r0', capture());
    expect(Object.isFrozen(frozen)).toBe(true);
    expect(Object.isFrozen(frozen.hits)).toBe(true);
    expect(() => {
      (frozen as unknown as { query: string }).query = 'rewritten';
    }).toThrow();
    expect(frozen.query).toBe('northfield renewal');
  });

  it('L4 — commit is append-only: earlier records are carried through by identity', () => {
    const first = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const second = commitSearch(first, capture({ query: 'second search' }), 'and after that?');
    expect(second.slice(0, 3)).toEqual(first);
    expect(second[0]).toBe(first[0]);
    expect(second).toHaveLength(6);
    expect(second.map((r) => r.kind)).toEqual([
      'frozen-search',
      'user-turn',
      'pending-answer',
      'frozen-search',
      'user-turn',
      'pending-answer',
    ]);
  });
});

describe('818 records — derived counts (L6)', () => {
  it('L6 — the projected index header count equals Σ of its own cluster sizes', () => {
    let records = commitSearch(NO_RECORDS, capture(), 'what changed?');
    records = commitSearch(records, capture({ query: 'second search' }), 'and after that?');
    records = commitSearch(records, capture({ query: 'third search' }), 'anything else?');

    const index = projectIndex(records);
    expect(index.nodes).toHaveLength(3);
    expect(index.nodes.map((n) => n.size)).toEqual([3, 3, 3]);
    expect(index.headerCount).toBe(index.nodes.reduce((sum, n) => sum + n.size, 0));
    expect(index.headerCount).toBe(records.length);
    expect(index.nodes.map((n) => n.label)).toEqual([
      'northfield renewal',
      'second search',
      'third search',
    ]);
  });

  it('L6 — the frozen block header derives from the CAPTURED set, not the live one', () => {
    const block = frozenAt(commitSearch(NO_RECORDS, capture({ hits: liveHits(3), total: 12 }), 'ask'));
    expect(block.capturedCount).toBe(3);
    expect(block.matchedTotal).toBe(12);
    expect(block.headerLabel).toBe('3 of 12 matches');
  });

  it('L6 — a captured set that IS the whole match population reads as a plain result count', () => {
    expect(
      frozenAt(commitSearch(NO_RECORDS, capture({ hits: liveHits(2), total: 2 }), 'ask')).headerLabel,
    ).toBe('2 results');
    expect(
      frozenAt(commitSearch(NO_RECORDS, capture({ hits: liveHits(1), total: 1 }), 'ask')).headerLabel,
    ).toBe('1 result');
  });

  it('L6 — an empty capture still describes itself honestly', () => {
    const block = frozenAt(commitSearch(NO_RECORDS, capture({ hits: [], total: 0 }), 'ask anyway'));
    expect(block.capturedCount).toBe(0);
    expect(block.headerLabel).toBe('0 results');
  });
});

describe('818 records — projections (L8 corollary, L11)', () => {
  it("L8 corollary — the session is named by its first committed record's query", () => {
    const records = commitSearch(NO_RECORDS, capture({ query: 'northfield renewal' }), 'what changed?');
    expect(projectSessionName(records)).toBe('northfield renewal');

    const later = commitSearch(records, capture({ query: 'a much later search' }), 'and then?');
    expect(projectSessionName(later)).toBe('northfield renewal');
  });

  it('L8 corollary — an empty records array is a New session', () => {
    expect(projectSessionName(NO_RECORDS)).toBe('New session');
    expect(projectSessionName([])).toBe(UNNAMED_SESSION);
  });

  it('L8 corollary — a commit with no query falls through to the committed turn text', () => {
    const records = commitSearch(NO_RECORDS, capture({ query: '   ' }), 'ask anyway about everything');
    expect(projectSessionName(records)).toBe('ask anyway about everything');
  });

  it('L11 — transcript and index are projections of ONE array (no second authority to diverge)', () => {
    const records = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const transcript = projectTranscript(records);
    const index = projectIndex(records);
    expect(transcript.map((t) => t.id)).toEqual(records.map((r) => r.id));
    expect(index.nodes[0]?.recordIds).toEqual(records.map((r) => r.id));
    expect(projectTranscript(NO_RECORDS)).toEqual([]);
    expect(projectIndex(NO_RECORDS)).toEqual({ headerCount: 0, nodes: [] });
  });
});
