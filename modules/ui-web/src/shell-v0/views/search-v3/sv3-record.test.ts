/**
 * The canonical thread record, projected into this window's turns (tempdoc 822 Phase F6; D1).
 *
 * No DOM: the projection is pure. The fixtures are real WIRE events, so every case runs through the
 * SHARED `projectUnifiedThread` (ordering, the tool-lifecycle merge by `callId`, segmentation) on its
 * way in — hand-authoring `UnifiedTurnItem`s instead would stop these cases noticing if this module
 * and the product's record ordering ever diverged, which is the whole point of not being a second
 * authority.
 */
import { describe, it, expect } from 'vitest';
import type { ThreadEvent } from '../unifiedThreadProjection.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { Sv3RunFeedTool } from './sv3-run.js';
import type { RetrievalCitation } from '../../components/chat/citationTypes.js';
import { claimsFromRecord } from '../../components/chat/recordEvidence.js';
import { claimsToCitations } from '../../components/chat/citationResolve.js';
import { sourceGrounding } from '../../components/chat/evidenceProjection.js';

let clock = 0;
const at = (): string => new Date(Date.parse('2026-08-13T10:00:00Z') + clock++ * 1000).toISOString();

const event = (
  id: string,
  kind: ThreadEvent['kind'],
  content: string,
  attributes: Record<string, unknown> = {},
): ThreadEvent => ({ id, occurredAt: at(), kind, originator: 'agent', content, attributes });

const project = (events: readonly ThreadEvent[]) => projectSv3RecordTurns(events);

describe('a record becomes turns, bracketed by the user messages', () => {
  it('opens one turn per user message and keeps the answers under the question that asked', () => {
    clock = 0;
    const turns = project([
      event('e1', 'USER_MESSAGE', 'why did the renewal fail?'),
      event('e2', 'ASSISTANT_MESSAGE', 'Because the lock held.'),
      event('e3', 'USER_MESSAGE', 'and the break clause?'),
      event('e4', 'ASSISTANT_MESSAGE', 'It lapsed in March.'),
    ]);
    expect(turns.map((t) => t.question)).toEqual([
      'why did the renewal fail?',
      'and the break clause?',
    ]);
    expect(turns.map((t) => t.answer)).toEqual(['Because the lock held.', 'It lapsed in March.']);
    // The RECORD's ids, which is what makes a turn a stable handle across a reload (A4).
    expect(turns.map((t) => t.id)).toEqual(['e1', 'e3']);
    // Prose alone is an ASK turn, and it carries no activity list: its whole response is the answer,
    // rendered by the one markdown block. A one-item list here would be a second way to draw it.
    expect(turns.map((t) => t.kind)).toEqual(['ask', 'ask']);
    expect(turns.flatMap((t) => t.activity)).toEqual([]);
    expect(turns.map((t) => t.status)).toEqual(['complete', 'complete']);
  });

  it('INTERLEAVES agent activity with the prose, in the record’s own order (561 P-A)', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'index the vendor folder'),
      event('e2', 'ASSISTANT_MESSAGE', 'Looking for it first.'),
      event('e3', 'TOOL_ACTIVITY', 'core_search', {
        callId: 'c1',
        toolName: 'core_search',
        arguments: '{"q":"vendor"}',
        risk: 'low',
        status: 'completed',
        output: '12 hits',
      }),
      event('e4', 'PROGRESS', 'Reading 12 files'),
      event('e5', 'ASSISTANT_MESSAGE', 'Indexed.'),
    ]);
    // ONE flat sequence, not "prose here, activity there": re-sorting into two lists is exactly what
    // the record's single ordering exists to prevent.
    expect(turn?.activity.map((item) => item.kind)).toEqual(['text', 'tool', 'note', 'text']);
    expect(turn?.kind).toBe('agent');
    expect(turn?.toolCalls).toBe(1);
    // The answer text is still the prose alone — the receipt and the transcript read one record.
    expect(turn?.answer).toBe('Looking for it first.\n\nIndexed.');
  });

  it('maps a tool row onto the SHARED ToolCall the one card renders', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'tidy up'),
      event('e2', 'TOOL_ACTIVITY', 'core_ingest_files', {
        callId: 'c9',
        toolName: 'core_ingest_files',
        arguments: '{"path":"F:/docs"}',
        risk: 'medium',
        status: 'completed',
        output: 'ok',
        success: true,
      }),
    ]);
    const tool = turn?.activity[0] as Sv3RunFeedTool;
    expect(tool.kind).toBe('tool');
    expect(tool.id).toBe('c9');
    expect(tool.call.toolName).toBe('core_ingest_files');
    // Risk persists lowercase and the live card expects the uppercase ToolRisk — the shipped
    // window's own record mapping, mined rather than re-guessed.
    expect(tool.call.risk).toBe('MEDIUM');
    expect(tool.call.status).toBe('completed');
    expect(tool.call.output).toBe('ok');
  });

  it('says FAILED when the record recorded an error, and never re-words it as complete', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'do the thing'),
      event('e2', 'ERROR', 'the model went away'),
    ]);
    expect(turn?.status).toBe('failed');
    expect(turn?.activity.map((i) => i.kind)).toEqual(['note']);
  });

  it('keeps activity that arrived before any user message rather than dropping it', () => {
    // A run whose prompt was never recorded still really made its calls. The turn carries no
    // question, and the surface renders no ask bubble for it — losing the call would be worse.
    clock = 0;
    const turns = project([
      event('e1', 'TOOL_ACTIVITY', 'core_search', { callId: 'c1', toolName: 'core_search' }),
      event('e2', 'USER_MESSAGE', 'now this'),
      event('e3', 'ASSISTANT_MESSAGE', 'done'),
    ]);
    expect(turns).toHaveLength(2);
    expect(turns[0]?.question).toBe('');
    expect(turns[0]?.kind).toBe('agent');
    expect(turns[0]?.toolCalls).toBe(1);
    expect(turns[1]?.question).toBe('now this');
  });

  it('hydrates the turn’s THINKING from the record (tempdoc 848 §2.7)', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'why did the renewal fail?'),
      event('e2', 'ASSISTANT_MESSAGE', 'Because the lock held.', {
        reasoning: [{ text: 'check the lock table first', durationMs: 1840 }],
      }),
    ]);
    expect(turn?.reasoning).toEqual([{ text: 'check the lock table first', durationMs: 1840 }]);
  });

  it('accumulates blocks from EVERY assistant item of a turn, in record order', () => {
    // A turn can record several assistant items (an iterating shape, a multi-step run); taking only
    // the last one would silently drop the earlier steps' thinking.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'do the multi-step thing'),
      event('e2', 'ASSISTANT_MESSAGE', 'step one', {
        reasoning: [{ text: 'first', durationMs: 10 }],
      }),
      event('e3', 'ASSISTANT_MESSAGE', 'step two', {
        reasoning: [{ text: 'second', durationMs: 20 }],
      }),
    ]);
    expect(turn?.reasoning.map((block) => block.text)).toEqual(['first', 'second']);
  });

  it('keeps the thinking a FAILED run recorded on its terminal error event (848 D-7)', () => {
    // The agent fold attaches a halted/errored run's trailing blocks to its ERROR event. Reading
    // reasoning only off assistant items would drop exactly the case where the thinking matters most.
    //
    // Tempdoc 859 §A §1.9 — the turn is an AGENT turn (an error is activity), so its thinking is
    // routed into `activity` as an ordered item and `reasoning` is empty: one authority per turn
    // kind, never both. The block still survives, which is what D-7 was about — it survives IN
    // POSITION now, immediately above the error it preceded.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'do the thing'),
      event('e2', 'ERROR', 'the model went away', {
        reasoning: [{ text: 'got as far as the lock table', durationMs: 700 }],
      }),
    ]);
    expect(turn?.status).toBe('failed');
    expect(turn?.kind).toBe('agent');
    expect(turn?.reasoning).toEqual([]);
    expect(turn?.activity.map((item) => item.kind)).toEqual(['reasoning', 'note']);
    const [think] = turn!.activity;
    expect(think).toMatchObject({
      kind: 'reasoning',
      text: 'got as far as the lock table',
      durationMs: 700,
      streaming: false,
    });
  });

  it('R-1: an agent turn’s thinking becomes ORDERED ITEMS, and the stack is emptied', () => {
    // The A1 fix, at the record end: the block rides the carrier the fold attached it to, so it
    // renders immediately above the step it produced instead of in a wall above the whole run.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'index the vendor folder'),
      event('e2', 'TOOL_ACTIVITY', '', {
        callId: 'c1',
        toolName: 'core_search',
        status: 'completed',
        reasoning: [{ text: 'search first', durationMs: 900 }],
      }),
      event('e3', 'ASSISTANT_MESSAGE', 'Indexed.', {
        reasoning: [{ text: 'that is enough to answer', durationMs: 400 }],
      }),
    ]);
    expect(turn?.kind).toBe('agent');
    expect(turn?.activity.map((i) => i.kind)).toEqual(['reasoning', 'tool', 'reasoning', 'text']);
    // Routed, never both: the stack would draw every block a second time above the feed.
    expect(turn?.reasoning).toEqual([]);
  });

  it('R-2: a reasoning item does NOT flip an ASK turn into an agent turn', () => {
    // The highest-value right-reason guard in the set. The kind derivation used to read "activity
    // has something that is not text", and an ordinary grounded ask turn records plenty of
    // thinking — so a thinking ask turn would have swapped render arms and hidden its sources. Same
    // class 847 §2.4.4 fixed once already for `panelSpeaks`.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'why did the renewal fail?'),
      event('e2', 'ASSISTANT_MESSAGE', 'It expired.', {
        reasoning: [{ text: 'check the renewal log', durationMs: 700 }],
      }),
    ]);
    expect(turn?.kind).toBe('ask');
    expect(turn?.reasoning).toEqual([{ text: 'check the renewal log', durationMs: 700 }]);
    expect(turn?.activity).toEqual([]);
  });

  it('R-3: a record reasoning id is STABLE across a refresh (D-2a)', () => {
    // `concludeRun` refreshes the record at every run terminal, and the merged tool item's `id` is
    // the LATEST lifecycle event's — so an id derived from it would move the active J/K landmark
    // out from under the reader exactly when a run ends. `callId` does not move.
    const proposedOnly: ThreadEvent[] = [
      event('e1', 'USER_MESSAGE', 'q'),
      event('c1:proposed', 'TOOL_ACTIVITY', '', {
        callId: 'c1',
        toolName: 'core_search',
        status: 'proposed',
        reasoning: [{ text: 'search first', durationMs: 900 }],
      }),
    ];
    clock = 0;
    const before = project(proposedOnly);
    clock = 0;
    const after = project([
      ...proposedOnly.map((e) => ({ ...e })),
      event('c1:started', 'TOOL_ACTIVITY', '', { callId: 'c1', status: 'executing' }),
      event('c1:completed', 'TOOL_ACTIVITY', '', { callId: 'c1', status: 'completed', success: true }),
    ]);
    const idOf = (turns: readonly { activity: readonly { kind: string; id: string }[] }[]): string =>
      turns[0]!.activity.find((i) => i.kind === 'reasoning')!.id;
    expect(idOf(before)).toBe('c1:think:0');
    expect(idOf(after)).toBe(idOf(before));
    // …and the block itself survived the merge, which is the other half of the same refresh.
    expect(after[0]?.activity.filter((i) => i.kind === 'reasoning')).toHaveLength(1);
  });

  it('R-3b: two blocks on two lifecycle events of ONE call both survive the merge (M-7)', () => {
    // The FE half of the collision: the tool merge is a later-wins attribute union, so without an
    // explicit array-union for `reasoning` the earlier block is silently dropped.
    //
    // 859 §A F4 — the shape is REACHABLE, through exactly one route: a run cut off mid-thought,
    // where the fold's trailing rule attaches the still-open region to the run's last event and that
    // event turns out to be a later lifecycle event of a call an earlier block already rode. (It is
    // NOT reachable by interleaving — reasoning is emitted inside the LLM stream, and the tool calls
    // parsed out of it are dispatched only after it closes.) So this guards a real dropped block,
    // not a hypothetical one; the ordering cost that comes with it is pinned in
    // `sv3-timeline-parity.test.ts`.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'q'),
      event('c1:proposed', 'TOOL_ACTIVITY', '', {
        callId: 'c1',
        toolName: 'core_search',
        status: 'proposed',
        reasoning: [{ text: 'I should search', durationMs: 100 }],
      }),
      event('c1:completed', 'TOOL_ACTIVITY', '', {
        callId: 'c1',
        status: 'completed',
        success: true,
        reasoning: [{ text: 'and I should widen it', durationMs: 200 }],
      }),
    ]);
    const texts = turn!.activity
      .filter((i) => i.kind === 'reasoning')
      .map((i) => (i as { text: string }).text);
    expect(texts).toEqual(['I should search', 'and I should widen it']);
  });

  it('drops a malformed reasoning payload rather than rendering half a block', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'q'),
      event('e2', 'ASSISTANT_MESSAGE', 'a', { reasoning: [{ durationMs: 5 }, 'nope', null] }),
    ]);
    expect(turn?.reasoning).toEqual([]);
  });

  it('never claims evidence the record cannot resolve — never told is not zero', () => {
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'q'),
      event('e2', 'ASSISTANT_MESSAGE', 'a'),
    ]);
    expect(turn?.evidence).toBeNull();
  });

  it('is empty for an empty record, so nothing can read "no record" as "nothing happened"', () => {
    expect(project([])).toEqual([]);
  });
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The record's EVIDENCE, rehydrated (tempdoc 847 §1.3 / §2.4).
 *
 * `GET /api/thread/{id}` copies `citations` and `claimMatches` onto the assistant event's
 * attributes; this window discarded both and rendered every restored answer sourceless. The cases
 * below run the real wire attributes through the projection, and each gate case is paired with the
 * producer twin that must NOT be gated — a "no marks" expectation over a fixture that could never
 * mint one would pass for the wrong reason.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('a restored turn stands on the evidence the record carries (847 §2.4)', () => {
  const DOC = 'docs/lease.md';

  const persistedSource = (): RetrievalCitation => ({
    parentDocId: DOC,
    chunkIndex: 0,
    chunkTotal: 1,
    startChar: 0,
    endChar: 40,
    score: 0.9,
    excerpt: 'The lock held past the renewal date.',
    startLine: 1,
    endLine: 2,
    headingText: 'Renewal',
    headingLevel: 2,
  });

  const persistedMatches = (scorer?: string): Record<string, unknown> => ({
    ...(scorer === undefined ? {} : { scorer }),
    sentencesTotal: 1,
    sentencesScored: 1,
    matches: [
      {
        sentenceIndex: 0,
        sentenceText: 'The lock held.',
        sourceIndex: 0,
        similarity: 0.94,
        parentDocId: DOC,
      },
    ],
  });

  const answered = (scorer?: string, extra: Record<string, unknown> = {}): readonly ThreadEvent[] => [
    event('e1', 'USER_MESSAGE', 'why did the renewal fail?'),
    event('e2', 'ASSISTANT_MESSAGE', 'The lock held.', {
      citations: [persistedSource()],
      claimMatches: persistedMatches(scorer),
      ...extra,
    }),
  ];

  it('projects the persisted sources and marks, through the SHARED resolver (847 §1.3)', () => {
    clock = 0;
    const [turn] = project(answered('CROSS_ENCODER'));
    const evidence = turn?.evidence;
    expect(evidence).not.toBeNull();
    expect(evidence?.sources).toHaveLength(1);
    expect(evidence?.matches).toHaveLength(1);
    // The marks are exactly what the ONE resolver makes of the ONE envelope reader's claims — not a
    // second derivation this module authored (561 P-A: live and restored may not disagree).
    expect([...(evidence?.marks ?? [])]).toEqual(
      claimsToCitations(claimsFromRecord(persistedMatches('CROSS_ENCODER')), [persistedSource()]),
    );
    expect(evidence?.marks[0]?.similarity).toBe(0.94);
  });

  it('withholds the mark AND the panel tier when the producer is not admitted (847 §2.3)', () => {
    clock = 0;
    const [gatedTurn] = project(answered('EMBEDDING_COSINE'));
    clock = 0;
    const [admittedTurn] = project(answered('CROSS_ENCODER'));
    // Paired with the twin so the empty below is the producer gate, not an inert fixture.
    expect(admittedTurn?.evidence?.marks).toHaveLength(1);
    expect(sourceGrounding(0, [...(admittedTurn?.evidence?.matches ?? [])], DOC).cited).toBe(true);
    expect(gatedTurn?.evidence?.marks).toEqual([]);
    expect(gatedTurn?.evidence?.matches).toEqual([]);
    // The sources panel reads its per-source tier off those matches, so the answer's markless text
    // and the panel beside it now say the same thing.
    expect(sourceGrounding(0, [...(gatedTurn?.evidence?.matches ?? [])], DOC).cited).toBe(false);
    // The retrieval itself is not withheld — it happened, and the record says so.
    expect(gatedTurn?.evidence?.sources).toHaveLength(1);
  });

  it('carries the evidence of an AGENT-kind turn too — a note does not un-source an answer', () => {
    // §1.7: the kind flips to `agent` on any non-text activity, and the turns most likely to carry a
    // progress note are the long retrieval-heavy ones most likely to have evidence.
    clock = 0;
    const [turn] = project([
      event('e1', 'USER_MESSAGE', 'why did the renewal fail?'),
      event('e2', 'PROGRESS', 'Searching the lease folder'),
      event('e3', 'ASSISTANT_MESSAGE', 'The lock held.', {
        citations: [persistedSource()],
        claimMatches: persistedMatches('CROSS_ENCODER'),
      }),
    ]);
    expect(turn?.kind).toBe('agent');
    expect(turn?.evidence?.sources).toHaveLength(1);
  });

  it('stamps the RECORD id as `recordId`, which is what the merge reconciles on (847 §2.4.3)', () => {
    clock = 0;
    const [turn] = project(answered('CROSS_ENCODER'));
    expect(turn?.recordId).toBe('e1');
    expect(turn?.id).toBe('e1');
  });
});
