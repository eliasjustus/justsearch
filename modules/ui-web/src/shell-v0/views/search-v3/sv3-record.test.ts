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
