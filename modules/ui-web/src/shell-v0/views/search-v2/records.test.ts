/**
 * The records laws (tempdoc 818 slices 1-3) — L4, L6, and the L8 corollary as executable tests.
 *
 * These pin the from-scratch payoff: a committed search is a SNAPSHOT (so the shipped window's
 * "search results as a live transcript" staleness class cannot occur), every count is computed
 * from the set it describes (so the count-truthfulness recurrence cannot occur), the session
 * name is a projection of the first committed record (so "New chat is state-gated" cannot occur),
 * and the answer slot has a lifecycle of its own that cannot reach any other record (slice 2).
 */
import { describe, it, expect } from 'vitest';
import {
  NO_RECORDS,
  UNNAMED_SESSION,
  appendAgentRun,
  appendUserTurn,
  commitSearch,
  finalizeAnswer,
  freezeSearch,
  groundedSentencesLabel,
  pendingAnswerIdFor,
  projectIndex,
  projectSessionName,
  projectTranscript,
  refuseAnswer,
  runSummaryLabel,
  type AnswerCapture,
  type SearchCapture,
  type SessionRecord,
  type TranscriptAnswerItem,
  type TranscriptFrozenItem,
} from './records.js';

/** Narrowing helper — the transcript is a union, and these assertions are about frozen blocks. */
function frozenAt(records: readonly SessionRecord[], i = 0): TranscriptFrozenItem {
  const item = projectTranscript(records)[i];
  if (!item || item.kind !== 'frozen-search') throw new Error(`no frozen block at index ${i}`);
  return item;
}

function answerAt(records: readonly SessionRecord[], i: number): TranscriptAnswerItem {
  const item = projectTranscript(records)[i];
  if (!item || item.kind !== 'answer') throw new Error(`no answer at index ${i}`);
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
    retrievalMode: 'HYBRID',
    executedAt: '2026-08-08T10:00:00.000Z',
    ...over,
  };
}

function answer(over: Partial<AnswerCapture> = {}): AnswerCapture {
  return {
    text: 'The renewal moved payment terms to net-45.',
    claims: [],
    citations: [],
    sources: [],
    retrievalMode: 'HYBRID',
    chunksUsed: 4,
    grounding: { sentencesMatched: 3, sentencesTotal: 5 },
    promptTokens: 1200,
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

  it('L4 — the capture keeps HOW the pass retrieved and WHEN, so the frozen header cannot re-derive it', () => {
    const frozen = freezeSearch('r0', capture({ retrievalMode: 'VECTOR' }));
    expect(frozen.retrievalMode).toBe('VECTOR');
    expect(frozen.executedAt).toBe('2026-08-08T10:00:00.000Z');
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

describe('818 records — the answer slot lifecycle (L4, slice 2)', () => {
  it('L4 — finalizing an answer leaves every prior record IDENTICAL BY REFERENCE', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const slotId = pendingAnswerIdFor(NO_RECORDS);
    const after = finalizeAnswer(committed, slotId, answer());

    expect(after).toHaveLength(committed.length);
    // The frozen search and the user turn are the SAME objects — an answer landing cannot reach them.
    expect(after[0]).toBe(committed[0]);
    expect(after[1]).toBe(committed[1]);
    // Only the slot changed, and it kept its id.
    expect(after[2]).not.toBe(committed[2]);
    expect(after[2]?.kind).toBe('answer');
    expect(after[2]?.id).toBe(slotId);
    expect(committed[2]?.kind).toBe('pending-answer');
  });

  it('L4 — a second terminal cannot rewrite a landed answer (the array is returned by identity)', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const slotId = pendingAnswerIdFor(NO_RECORDS);
    const landed = finalizeAnswer(committed, slotId, answer());

    expect(finalizeAnswer(landed, slotId, answer({ text: 'a different answer' }))).toBe(landed);
    expect(refuseAnswer(landed, slotId, 'locked', 'locked')).toBe(landed);
    expect(answerAt(landed, 2).text).toBe('The renewal moved payment terms to net-45.');
  });

  it('L4/L9 — a refusal fills the SAME slot, so a refused send never leaves a pending answer', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const slotId = pendingAnswerIdFor(NO_RECORDS);
    const refused = refuseAnswer(committed, slotId, 'locked', 'Your chat history is encrypted and locked');

    expect(refused[0]).toBe(committed[0]);
    expect(refused[1]).toBe(committed[1]);
    const item = projectTranscript(refused)[2];
    expect(item?.kind).toBe('refused-answer');
    expect(item && item.kind === 'refused-answer' ? item.label : '').toBe(
      'Not sent — the session is locked',
    );
  });

  it('L4 — a terminal for an unknown slot is a no-op, not an append', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    expect(finalizeAnswer(committed, 'r99', answer())).toBe(committed);
    expect(refuseAnswer(committed, 'r99', 'error', 'boom')).toBe(committed);
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
    // The captured counts ARE the frozen header's inputs: `<jf-results-card variant="snapshot">`
    // renders them through the shared `matchCountLabel`, so this window authors no count label of
    // its own (the rendered form is asserted in SearchV2View.test.ts).
    const block = frozenAt(commitSearch(NO_RECORDS, capture({ hits: liveHits(3), total: 12 }), 'ask'));
    expect(block.capturedCount).toBe(3);
    expect(block.matchedTotal).toBe(12);
    expect(block.capturedCount).toBe(block.hits.length);
  });

  it('L6 — a captured set that IS the whole match population describes itself as such', () => {
    const block = frozenAt(commitSearch(NO_RECORDS, capture({ hits: liveHits(2), total: 2 }), 'ask'));
    expect(block.capturedCount).toBe(2);
    expect(block.matchedTotal).toBe(2);
  });

  it('L6 — an empty capture still describes itself honestly', () => {
    const block = frozenAt(commitSearch(NO_RECORDS, capture({ hits: [], total: 0 }), 'ask anyway'));
    expect(block.capturedCount).toBe(0);
    expect(block.matchedTotal).toBe(0);
  });

  it('L6 — the grounding line is derived from the two counts it describes', () => {
    expect(groundedSentencesLabel({ sentencesMatched: 3, sentencesTotal: 5 })).toBe(
      '3 of 5 sentences grounded in your files',
    );
    expect(groundedSentencesLabel({ sentencesMatched: 1, sentencesTotal: 1 })).toBe(
      '1 of 1 sentence grounded in your files',
    );
  });

  it('L6 — an unmeasured answer renders NO grounding line (never a fabricated 0 of 0)', () => {
    expect(groundedSentencesLabel(null)).toBeNull();
    expect(groundedSentencesLabel({ sentencesMatched: 0, sentencesTotal: 0 })).toBeNull();

    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const landed = finalizeAnswer(committed, pendingAnswerIdFor(NO_RECORDS), answer({ grounding: null }));
    expect(answerAt(landed, 2).groundedSentencesLabel).toBeNull();
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

  it('L8 corollary — a landed answer never renames the session', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const landed = finalizeAnswer(committed, pendingAnswerIdFor(NO_RECORDS), answer());
    expect(projectSessionName(landed)).toBe('northfield renewal');
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

  it('L11 — an answer projects its own evidence; the index labels its cluster "Answer"', () => {
    const committed = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const landed = finalizeAnswer(
      committed,
      pendingAnswerIdFor(NO_RECORDS),
      answer({
        sources: [
          {
            parentDocId: 'Contracts/0.pdf',
            chunkIndex: 0,
            chunkTotal: 3,
            startChar: 0,
            endChar: 40,
            score: 0.8,
            excerpt: '…net-45…',
            startLine: 1,
            endLine: 2,
            headingText: 'Payment',
            headingLevel: 2,
          },
        ],
      }),
    );
    const item = answerAt(landed, 2);
    expect(item.sources).toHaveLength(1);
    expect(item.retrievalMode).toBe('HYBRID');
    expect(item.groundedSentencesLabel).toBe('3 of 5 sentences grounded in your files');

    // A slot that opens a cluster with no preceding commit is labelled by what it is.
    const bare = finalizeAnswer(
      Object.freeze([{ kind: 'pending-answer' as const, id: 'r0' }]),
      'r0',
      answer(),
    );
    expect(projectIndex(bare).nodes[0]?.label).toBe('Answer');
  });
});

describe('818 records — the delegated run (L4, L6, L8)', () => {
  it('L8 — a delegate appends exactly ONE user-turn, and the run appends exactly ONE receipt', () => {
    const delegated = appendUserTurn(NO_RECORDS, 'File the 2025 supplier agreements');
    expect(delegated).toHaveLength(1);
    expect(delegated[0]).toEqual({
      kind: 'user-turn',
      id: 'r0',
      text: 'File the 2025 supplier agreements',
    });

    const concluded = appendAgentRun(delegated, {
      outcome: 'completed',
      toolCallCount: 3,
      tokensUsed: 41_200,
    });
    expect(concluded).toHaveLength(2);
    expect(concluded[1]).toEqual({
      kind: 'agent-run',
      id: 'r1',
      outcome: 'completed',
      toolCallCount: 3,
      tokensUsed: 41_200,
    });
  });

  it('L4 — appending a receipt carries every earlier record through BY IDENTITY', () => {
    const before = commitSearch(NO_RECORDS, capture(), 'what changed?');
    const after = appendAgentRun(appendUserTurn(before, 'now file them'), {
      outcome: 'completed',
      toolCallCount: 1,
      tokensUsed: null,
    });

    for (let i = 0; i < before.length; i += 1) {
      expect(after[i]).toBe(before[i]); // the same object, not an equal copy
    }
    expect(after).toHaveLength(before.length + 2);
  });

  it('L6 — the receipt line is computed from the counts the record carries', () => {
    expect(runSummaryLabel('completed', 3, 41_200)).toBe(
      `Run finished · 3 tool calls · ${(41_200).toLocaleString()} tokens`,
    );
    expect(runSummaryLabel('completed', 1, 10)).toBe(
      `Run finished · 1 tool call · ${(10).toLocaleString()} tokens`,
    );
    expect(runSummaryLabel('halted', 2, null)).toBe('Run halted by you · 2 tool calls');
    expect(runSummaryLabel('error', 0, null)).toBe('Run ended in an error · 0 tool calls');
  });

  it('L6 — an unreported token total is OMITTED, never rendered as a measured zero', () => {
    const records = appendAgentRun(NO_RECORDS, {
      outcome: 'halted',
      toolCallCount: 2,
      tokensUsed: null,
    });
    const item = projectTranscript(records)[0];
    if (!item || item.kind !== 'agent-run') throw new Error('no run receipt');
    expect(item.label).not.toContain('tokens');
    expect(item.label).toContain('2 tool calls');
    expect(item.outcome).toBe('halted');
  });

  it('L8 corollary — a receipt joins the cluster its turn opened; it never names the session', () => {
    const records = appendAgentRun(appendUserTurn(NO_RECORDS, 'file the agreements'), {
      outcome: 'completed',
      toolCallCount: 2,
      tokensUsed: 900,
    });
    const index = projectIndex(records);

    expect(index.nodes).toHaveLength(1);
    expect(index.nodes[0]?.label).toBe('file the agreements');
    expect(index.nodes[0]?.size).toBe(2);
    expect(index.headerCount).toBe(2);
    expect(projectSessionName(records)).toBe('file the agreements');

    // A receipt with no turn before it still labels its own cluster rather than going unnamed.
    const orphan = appendAgentRun(NO_RECORDS, {
      outcome: 'error',
      toolCallCount: 0,
      tokensUsed: null,
    });
    expect(projectIndex(orphan).nodes[0]?.label).toBe('Delegated run');
    expect(projectSessionName(orphan)).toBe(UNNAMED_SESSION);
  });
});
