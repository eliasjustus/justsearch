// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 859 §A §2.2 / R-4 — ONE synthetic run, driven down BOTH paths, must produce the same
 * timeline.
 *
 * (a) the LIVE path: journal records → `AgentSessionController.dispatchEvent` (the boundary
 *     chokepoint) → `projectSv3RunFeed`;
 * (b) the RECORD path: the thread events `AgentInteractionMapper.fromRunEvents` produces for that
 *     same journal → `projectSv3RecordTurns`.
 *
 * The record side's fixture is hand-written, and deliberately so: it is the OUTPUT the Java fold is
 * separately pinned to produce for this exact journal (`AgentInteractionMapperTest` M-1, M-6 and the
 * handoff case). Writing it out here is what makes the two halves independently checkable — a shared
 * helper that derived one from the other could not fail.
 *
 * What this catches that the per-side cases cannot: the live cut set DRIFTING from the record fold's.
 * That drift is exactly the defect this slice fixes (the live side cut on the first text chunk and
 * nowhere else), and it is invisible to any test that only looks at one path.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { AgentSessionController } from '../../controllers/AgentSessionController.js';
import { projectSv3RunFeed, type Sv3RunFeedItem } from './sv3-run.js';
import { projectSv3RecordTurns } from './sv3-record.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';

vi.mock('../../components/advisory/ephemeralToast.js', () => ({ emitEphemeralToast: vi.fn() }));

/** The ONE run. Journal names are `AgentEventPayloads.name`'s vocabulary, verbatim. */
const JOURNAL: ReadonlyArray<{ eventType: string; payload: Record<string, unknown> }> = [
  { eventType: 'reasoning_chunk', payload: { text: 'plan the search' } },
  // The highest-frequency real cut: emitted the instant the LLM stream ends, and invisible to the
  // record projection. It is the event the superseded rule had no carrier for.
  { eventType: 'budget_update', payload: { phase: 'llm_response' } },
  { eventType: 'tool_call_proposed', payload: { callId: 'c1', toolName: 'core_search', risk: 'low' } },
  { eventType: 'tool_call_pending', payload: { callId: 'c1', toolName: 'core_search', risk: 'low' } },
  { eventType: 'tool_exec_completed', payload: { callId: 'c1', success: true, output: '3 hits' } },
  { eventType: 'reasoning_chunk', payload: { text: 'hand this to the researcher' } },
  // Cuts on both paths and carries on neither: live appends the handoff on `proposed`, the record
  // projects `executed`, and the block lands on the producing agent's side either way.
  { eventType: 'handoff_proposed', payload: { fromAgentId: 'primary', toAgentId: 'researcher', reason: 'scope' } },
  { eventType: 'handoff_executed', payload: { fromAgentId: 'primary', toAgentId: 'researcher' } },
  { eventType: 'reasoning_chunk', payload: { text: 'now answer' } },
  { eventType: 'progress', payload: { phase: 'llm_call', message: 'Calling LLM' } },
  { eventType: 'done', payload: { finalResponse: 'the answer' } },
];

/** What the Java fold produces for {@link JOURNAL}. Pinned independently by the mapper's own suite. */
const iso = (n: number): string => new Date(Date.parse('2026-08-13T10:00:00Z') + n * 1000).toISOString();
const RECORD: readonly ThreadEvent[] = [
  { id: 'e0', occurredAt: iso(0), kind: 'USER_MESSAGE', originator: 'user', content: 'find the renewals', attributes: {} },
  {
    id: 'c1:proposed', occurredAt: iso(1), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '',
    attributes: {
      callId: 'c1', toolName: 'core_search', status: 'proposed', risk: 'low',
      reasoning: [{ text: 'plan the search', durationMs: 1000 }],
    },
  },
  { id: 'c1:pending', occurredAt: iso(2), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', status: 'pending' } },
  { id: 'c1:completed', occurredAt: iso(3), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', status: 'completed', success: true, output: '3 hits' } },
  {
    id: 'conv:handoff:4', occurredAt: iso(4), kind: 'HANDOFF', originator: 'agent', content: '',
    attributes: {
      fromAgentId: 'primary', toAgentId: 'researcher',
      reasoning: [{ text: 'hand this to the researcher', durationMs: 1000 }],
    },
  },
  {
    id: 'conv:assistant:5', occurredAt: iso(5), kind: 'ASSISTANT_MESSAGE', originator: 'agent', content: 'the answer',
    attributes: { reasoning: [{ text: 'now answer', durationMs: 1000 }] },
  },
];

type Shape = ReadonlyArray<readonly [string, string]>;

const shapeOf = (items: readonly Sv3RunFeedItem[]): Shape =>
  items.map((item) => {
    switch (item.kind) {
      case 'reasoning':
        return [item.kind, item.text] as const;
      case 'tool':
        return [item.kind, item.call.callId] as const;
      case 'text':
        return [item.kind, item.text] as const;
      default:
        return [item.kind, item.label] as const;
    }
  });

describe('one run, two paths, one timeline (859 §A §2.2)', () => {
  let ctrl: AgentSessionController;

  beforeEach(() => {
    ctrl = new AgentSessionController('http://test', () => {});
  });
  afterEach(() => {
    ctrl.destroy();
  });

  it('R-4: the live feed and the record produce the SAME ordered (kind, text) sequence', () => {
    ctrl.loadReplayFromExport({ meta: { sessionId: 'run-parity' }, events: [...JOURNAL] });
    const liveShape = shapeOf(projectSv3RunFeed(ctrl, 0).items);
    const [recordTurn] = projectSv3RecordTurns(RECORD);
    const recordShape = shapeOf(recordTurn!.activity);

    // The ONE known divergence, and it predates this slice: an agent `progress` event appends a live
    // note and projects NOTHING on the record (`AgentInteractionMapper.fromRunEvent` has no case for
    // it). Excluded explicitly and asserted on both sides, so it is a stated limit rather than a
    // silently-passing filter — and so a future change that made progress durable fails here.
    expect(liveShape.filter(([kind, label]) => kind === 'note' && label === 'Progress')).toEqual([
      ['note', 'Progress'],
    ]);
    expect(recordShape.filter(([kind, label]) => kind === 'note' && label === 'Progress')).toEqual([]);

    const comparable = (shape: Shape): Shape =>
      shape.filter(([kind, label]) => !(kind === 'note' && label === 'Progress'));

    expect(comparable(liveShape)).toEqual(comparable(recordShape));
    // …and the shape is the run's real chronology, not an empty agreement between two empty lists.
    expect(comparable(liveShape)).toEqual([
      ['reasoning', 'plan the search'],
      ['tool', 'c1'],
      ['reasoning', 'hand this to the researcher'],
      ['note', 'Handoff'],
      ['reasoning', 'now answer'],
      ['text', 'the answer'],
    ]);
  });

  /**
   * F4 — the ONE shape where the parity invariant does not hold, named and pinned rather than left
   * to be rediscovered.
   *
   * It is reachable, but only through the TRAILING rule (848 §2.4 D-7), which this slice preserved
   * deliberately: a run cut off mid-thought has a region with no following event to flush onto, so
   * the fold attaches it to the run's LAST event. When that event is a lifecycle event of a call
   * whose earlier lifecycle event already carries a block, the merge collapses both onto one tool
   * ROW — and `sv3-record` draws reasoning ABOVE its carrier, which is right for a flushed block and
   * wrong for a trailing one.
   *
   * It is NOT reachable by interleaving: `ReasoningChunk` is emitted inside the LLM stream
   * (`AgentLlmCaller.java:212`) and the tool calls parsed out of it are dispatched only after it
   * closes (`AgentStepRunner`), so reasoning never falls between two lifecycle events of one call
   * on a healthy run. The Java half is pinned by
   * `AgentInteractionMapperTest.twoLifecycleEventsOfOneCallEachCarryABlock`.
   *
   * The LIVE ordering is the chronologically correct one. Making the record match would need the
   * block to carry a "this one trails" marker — a wire-visible change to every persisted reasoning
   * element and to both windows' readers, for a shape that only an interrupted run produces. That is
   * a bigger change than slice A, so the limit is recorded here instead of papered over.
   */
  it('F4: a TRUNCATED run is the one shape where the two orderings differ — by design, pinned', () => {
    ctrl.loadReplayFromExport({
      meta: { sessionId: 'run-truncated' },
      events: [
        { eventType: 'reasoning_chunk', payload: { text: 'search first' } },
        { eventType: 'budget_update', payload: { phase: 'llm_response' } },
        { eventType: 'tool_call_proposed', payload: { callId: 'c1', toolName: 'core_search', risk: 'low' } },
        { eventType: 'tool_exec_started', payload: { callId: 'c1', toolName: 'core_search' } },
        { eventType: 'tool_exec_completed', payload: { callId: 'c1', success: true } },
        // The process died here, mid-thought.
        { eventType: 'reasoning_chunk', payload: { text: 'the results are thin' } },
      ],
    });
    const liveShape = shapeOf(projectSv3RunFeed(ctrl, 0).items);

    // What the Java fold produces for that journal: block 1 flushed onto `proposed` on the way past,
    // block 2 attached to the run's last event by the trailing rule — the same call, so the FE merge
    // unions them onto one row.
    const truncatedRecord: readonly ThreadEvent[] = [
      { id: 'e0', occurredAt: iso(0), kind: 'USER_MESSAGE', originator: 'user', content: 'find the renewals', attributes: {} },
      {
        id: 'c1:proposed', occurredAt: iso(1), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '',
        attributes: {
          callId: 'c1', toolName: 'core_search', status: 'proposed', risk: 'low',
          reasoning: [{ text: 'search first', durationMs: 1000 }],
        },
      },
      { id: 'c1:started', occurredAt: iso(2), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '', attributes: { callId: 'c1', status: 'executing' } },
      {
        id: 'c1:completed', occurredAt: iso(3), kind: 'TOOL_ACTIVITY', originator: 'agent', content: '',
        attributes: {
          callId: 'c1', status: 'completed', success: true,
          reasoning: [{ text: 'the results are thin', durationMs: 1000 }],
        },
      },
    ];
    const [recordTurn] = projectSv3RecordTurns(truncatedRecord);
    const recordShape = shapeOf(recordTurn!.activity);

    // Live: the trailing thought is where it happened — after the call, still open.
    expect(liveShape).toEqual([
      ['reasoning', 'search first'],
      ['tool', 'c1'],
      ['reasoning', 'the results are thin'],
    ]);
    // Record: both blocks ride the one merged tool row, so both draw above it.
    expect(recordShape).toEqual([
      ['reasoning', 'search first'],
      ['reasoning', 'the results are thin'],
      ['tool', 'c1'],
    ]);
    // The invariant that DOES survive, and the one the union fix exists for: no thought is lost.
    expect(new Set(liveShape.map(([, v]) => v))).toEqual(new Set(recordShape.map(([, v]) => v)));
  });
});
