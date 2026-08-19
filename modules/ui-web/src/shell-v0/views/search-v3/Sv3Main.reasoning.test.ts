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

describe('the transcript renders a turn’s thinking, whatever tier produced it', () => {
  it('shows an AGENT turn’s recorded reasoning, not only an ask turn’s', async () => {
    const el = await mount([
      turn({
        id: 't1',
        kind: 'agent',
        activity: [{ kind: 'text', id: 'a1', text: 'It expired.' }],
        reasoning: [{ text: 'read the renewal log first', durationMs: 900 }],
      }),
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
