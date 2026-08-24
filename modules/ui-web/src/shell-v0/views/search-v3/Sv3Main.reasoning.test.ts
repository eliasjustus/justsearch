// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * The transcript region's reasoning binding (tempdoc 848 §2.7).
 *
 * Two decisions live here and neither is observable from the ask path alone:
 *
 *  1. **An AGENT turn shows its thinking too.** Leaving one turn kind reasoning-less would rebuild,
 *     inside this window, the same live/record asymmetry the persistence work exists to remove.
 *  2. **The live controller is bound by TURN ID, not by "whichever turn is streaming".** `streaming`
 *     is derived per turn from `turn.status`, and two turns in that status are reachable (an
 *     externally-dispatched run is adopted without coordinating with the ask path). A turn-KIND
 *     check would not close that gap — both turns could be the same kind — so the case below puts
 *     TWO streaming turns on screen and asserts only the owner renders the live block.
 *
 * The region is mounted directly: what is under test is its binding, not the window's plumbing.
 */
import { describe, it, expect } from 'vitest';
import './Sv3Main.js';
import type { Sv3Main } from './Sv3Main.js';
import type { Sv3Turn } from './sv3-sessions.js';
import { ReasoningController } from '../../controllers/ReasoningController.js';

type Mounted = Sv3Main & { updateComplete: Promise<unknown> };

const turn = (over: Partial<Sv3Turn> & { id: string }): Sv3Turn => ({
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind: 'ask',
  question: 'why did the renewal fail?',
  answer: 'It expired.',
  status: 'complete',
  evidence: null,
  detail: '',
  toolCalls: 0,
  activity: [],
  askedAt: 1,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: null,
  modelLabel: null,
  disposition: null,
  ...over,
});

async function mount(turns: readonly Sv3Turn[]): Promise<Mounted> {
  document.body.innerHTML = '';
  const el = document.createElement('jf-sv3-main') as Mounted;
  el.turns = turns;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const blocks = (el: Mounted): HTMLElement[] => [
  ...(el.shadowRoot?.querySelectorAll('[data-testid="sv3-turn-reasoning"]') ?? []),
] as HTMLElement[];

const feedBlocks = (el: Mounted): HTMLElement[] => [
  ...(el.shadowRoot?.querySelectorAll('[data-testid="sv3-run-reasoning"]') ?? []),
] as HTMLElement[];

/**
 * Tempdoc 859 §A §1.9 — S-1, superseding 848's "shows an AGENT turn's recorded reasoning" case.
 *
 * That case asserted the STACK above the agent turn, which is the thing A1 removes: on a run with
 * seven steps it drew seven bars in a row, none of them next to the step that produced it. The
 * decision it stood for — an agent turn shows its thinking too — is unchanged and is what these
 * cases assert; only its POSITION moved, into the feed, in stream order.
 */
describe('an agent turn’s thinking is INSIDE the run feed, in order (859 §A A1)', () => {
  const agentTurn = (activity: Sv3Turn['activity']): Sv3Turn =>
    turn({ id: 't1', kind: 'agent', activity: [...activity] });

  it('interleaves reasoning with the steps it produced, and stacks nothing above the feed', async () => {
    const el = await mount([
      agentTurn([
        { kind: 'reasoning', id: 'c1:think:0', text: 'read the renewal log first', durationMs: 900, streaming: false },
        { kind: 'tool', id: 'c1', call: { callId: 'c1', toolName: 'core_search', arguments: '{}', risk: 'LOW', status: 'completed' } },
        { kind: 'reasoning', id: 'c2:think:0', text: 'now check the expiry', durationMs: 400, streaming: false },
        { kind: 'text', id: 'a1', text: 'It expired.' },
      ]),
    ]);
    const feed = el.shadowRoot?.querySelector('[data-testid="sv3-record-activity"]');
    expect(feed).not.toBeNull();
    // Every reasoning row is a CHILD of the feed — the interleave, not a lane beside it.
    const inFeed = [...(feed?.querySelectorAll('[data-testid="sv3-run-reasoning"]') ?? [])];
    expect(inFeed).toHaveLength(2);
    expect(inFeed.map((n) => (n as unknown as { text: string }).text)).toEqual([
      'read the renewal log first',
      'now check the expiry',
    ]);
    // …and the stack the agent arm used to render is gone. Scoped to the agent testid so this
    // cannot pass merely because the query was renamed.
    expect(blocks(el)).toHaveLength(0);
  });

  it('gives every feed row a landmark id, so J/K walks the run in true order (857)', async () => {
    const el = await mount([
      agentTurn([
        { kind: 'reasoning', id: 'c1:think:0', text: 'first', durationMs: 1, streaming: false },
        { kind: 'tool', id: 'c1', call: { callId: 'c1', toolName: 'core_search', arguments: '{}', risk: 'LOW', status: 'completed' } },
      ]),
    ]);
    const ids = [...(el.shadowRoot?.querySelectorAll('[data-testid="sv3-record-activity"] [data-item-id]') ?? [])]
      .map((n) => n.getAttribute('data-item-id'));
    expect(ids).toEqual(['c1:think:0', 'c1']);
  });

  it('S-5: only the NEWEST region wears the live affordance (A3)', async () => {
    const el = await mount([
      agentTurn([
        { kind: 'reasoning', id: 'r1', text: 'finished thought', durationMs: 3000, streaming: false },
        { kind: 'tool', id: 'c1', call: { callId: 'c1', toolName: 'core_search', arguments: '{}', risk: 'LOW', status: 'completed' } },
        { kind: 'reasoning', id: 'run-reasoning-live', text: 'still thinking', durationMs: 2000, streaming: true },
      ]),
    ]);
    const rows = feedBlocks(el);
    expect(rows).toHaveLength(2);
    const pulsing = rows.filter((r) => r.shadowRoot?.querySelector('jf-pulse-dots') !== null);
    expect(pulsing).toHaveLength(1);
    expect(pulsing[0]?.getAttribute('data-item-id')).toBe('run-reasoning-live');
    // The finished one says what it was, in the past tense — the leak's visible symptom was the
    // opposite: a settled thought reading "Thinking (Ns)" with dots, for the rest of the run.
    expect(rows[0]?.shadowRoot?.querySelector('.label')?.textContent).toBe('Thought for 3s');
  });
});

describe('the transcript renders an ASK turn’s thinking, whatever tier produced it', () => {
  it('S-3: the ask arm still renders its reasoning above the answer', async () => {
    const el = await mount([
      turn({ id: 't1', reasoning: [{ text: 'read the renewal log first', durationMs: 900 }] }),
    ]);
    const rendered = blocks(el);
    expect(rendered).toHaveLength(1);
    expect((rendered[0] as unknown as { text: string }).text).toBe('read the renewal log first');
  });

  it('binds the LIVE controller to the turn that owns it, even with two turns streaming', async () => {
    const live = new ReasoningController(() => {});
    live.handleReasoningChunk({ text: 'live thinking' });
    const el = await mount([
      turn({ id: 'owner', status: 'streaming', answer: '' }),
      turn({
        id: 'other',
        status: 'streaming',
        answer: '',
        reasoning: [{ text: 'the other turn’s own record', durationMs: 5 }],
      }),
    ]);
    el.reasoning = live;
    el.reasoningTurnId = 'owner';
    await el.updateComplete;

    const rendered = blocks(el);
    expect(rendered).toHaveLength(2);
    // The owner renders the live controller (no static text/duration of its own)…
    expect((rendered[0] as unknown as { controller: unknown }).controller).toBe(live);
    // …and the OTHER streaming turn falls back to its own record — never the live stream's thinking.
    expect((rendered[1] as unknown as { controller: unknown }).controller ?? null).toBeNull();
    expect((rendered[1] as unknown as { text: string }).text).toBe('the other turn’s own record');
  });

  it('renders nothing for a turn with neither live nor recorded thinking', async () => {
    const el = await mount([turn({ id: 't1' })]);
    expect(blocks(el)).toHaveLength(0);
  });
});
