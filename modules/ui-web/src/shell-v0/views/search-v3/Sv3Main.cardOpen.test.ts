// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * Tempdoc 871 fix (live finding, 2026-08-26) — the search tool card's evidence-row `card-open` was
 * dead in SV3: `ToolCallCard` fires it (bubbles+composed, `{id}` = the hit's path) but nothing in
 * `Sv3Main`/`SearchV3View` listened, so a reader's click on an evidence row did nothing
 * (`UnifiedChatView` wires the same event via `handleRetrieveCardOpen`/`handleCommittedCardOpen`/
 * `handleToolEvidenceOpen` — this is the SV3-side gap those never covered).
 *
 * The fix routes `card-open` through the SAME document-open seam a followed citation already uses:
 * `Sv3Main` dispatches its own `SV3_CITATION_OPEN`, which `SearchV3View.onCitationOpen`
 * (`SearchV3View.pane.test.ts`) is the one landing site for — proven there in full, with a live
 * `<jf-sv3-pane>`. This file is scoped to `Sv3Main`'s OWN half: that the mount actually listens, that
 * it resolves the clicked id against the call's OWN `structuredData` (mirrors
 * `UnifiedChatView.handleToolEvidenceOpen`), and that it raises the window's event with the right
 * shape — mounted directly, as `Sv3Main.reasoning.test.ts` does, because what is under test is the
 * binding, not the window's plumbing.
 */
import { describe, it, expect } from 'vitest';
import './Sv3Main.js';
import { SV3_CITATION_OPEN, type Sv3Main, type Sv3CitationOpen } from './Sv3Main.js';
import { SV3_SOURCE_INDEX_ABSENT } from './sv3-citation-anchor.js';
import type { Sv3Turn } from './sv3-sessions.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';

type Mounted = Sv3Main & { updateComplete: Promise<unknown> };

const turn = (over: Partial<Sv3Turn> & { id: string }): Sv3Turn => ({
  recordId: null,
  assistantRecordId: null,
  recordOpenedByUser: false,
  kind: 'agent',
  question: 'find last year’s tax notes',
  answer: '',
  status: 'complete',
  evidence: null,
  detail: '',
  toolCalls: 1,
  activity: [],
  askedAt: 1,
  standaloneQuestion: '',
  reasoning: [],
  durationMs: null,
  modelLabel: null,
  disposition: null,
  ...over,
});

const searchToolCall = (structuredData: Record<string, unknown>): ToolCall => ({
  callId: 'c1',
  toolName: 'core_search_index',
  arguments: '{"query":"taxes"}',
  risk: 'LOW',
  status: 'completed',
  structuredData,
});

async function mount(turns: readonly Sv3Turn[]): Promise<Mounted> {
  document.body.innerHTML = '';
  const el = document.createElement('jf-sv3-main') as Mounted;
  el.turns = turns;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function toolCard(el: Mounted): (HTMLElement & { updateComplete: Promise<unknown> }) | null {
  return el.shadowRoot?.querySelector('jf-tool-call-card') as
    | (HTMLElement & { updateComplete: Promise<unknown> })
    | null;
}

describe('a search tool card evidence-row click opens the SV3 document pane seam (871 fix)', () => {
  it('resolves the clicked row against the CALL\'S OWN structuredData and raises sv3-citation-open', async () => {
    const t = turn({
      id: 't1',
      evidencePaths: ['/docs/taxes.md'],
      activity: [
        {
          kind: 'tool',
          id: 'c1',
          call: searchToolCall({
            query: 'taxes',
            resultCount: 1,
            searchResults: [
              { title: 'Taxes 2025', path: '/docs/taxes.md', excerpt: 'deductible limits', line: 5 },
            ],
          }),
        },
      ],
    });
    const el = await mount([t]);
    const card = toolCard(el);
    expect(card).not.toBeNull();
    await card!.updateComplete;
    const row = card!.shadowRoot?.querySelector(
      '[data-testid="tool-search-row"]',
    ) as HTMLButtonElement | null;
    expect(row, 'the evidence row did not render — evidencePaths wiring is broken').not.toBeNull();

    const raised: Sv3CitationOpen[] = [];
    el.addEventListener(SV3_CITATION_OPEN, (e) =>
      raised.push((e as CustomEvent<Sv3CitationOpen>).detail),
    );
    row!.click();

    // sourceIndex is ABSENT: an evidence row is a raw search-tool result, not a member of the turn's
    // citation set, so the pane opens on the document with no citation anchor/header — the same
    // degraded-but-correct path an unresolvable followed citation already takes.
    expect(raised).toEqual([
      { docPath: '/docs/taxes.md', anchor: null, turnId: 't1', sourceIndex: SV3_SOURCE_INDEX_ABSENT },
    ]);
  });

  it('is a no-op when the clicked id does not resolve in the call\'s OWN evidence (never fabricates an open)', async () => {
    const t = turn({
      id: 't1',
      evidencePaths: ['/docs/taxes.md'],
      activity: [
        {
          kind: 'tool',
          id: 'c1',
          call: searchToolCall({
            query: 'taxes',
            resultCount: 1,
            searchResults: [
              { title: 'Taxes 2025', path: '/docs/taxes.md', excerpt: 'deductible limits', line: 5 },
            ],
          }),
        },
      ],
    });
    const el = await mount([t]);
    const raised: Sv3CitationOpen[] = [];
    el.addEventListener(SV3_CITATION_OPEN, (e) =>
      raised.push((e as CustomEvent<Sv3CitationOpen>).detail),
    );

    // Fired directly (not via a real row click) with an id this call's structuredData never carried —
    // the defensive branch `findAgentSearchHit` exists to catch.
    const card = toolCard(el)!;
    card.dispatchEvent(
      new CustomEvent('card-open', { detail: { id: '/nowhere/else.md' }, bubbles: true, composed: true }),
    );

    expect(raised).toEqual([]);
  });

  it('routes BOTH the live feed and the record through the same seam (one renderer, 859/867)', async () => {
    // `runItem` draws the live controller feed and the settled record through the SAME function — a
    // second, unwired mount for one of the two would silently reintroduce the live/record asymmetry
    // 859 §A removed. Exercised here via the record path (`turn.activity`); the live path
    // (`run.feed.items`) calls the identical `runItem`, so a regression in the shared wiring fails
    // this case too, not only a live-only one.
    const t = turn({
      id: 't2',
      evidencePaths: ['/docs/budget.md'],
      activity: [
        {
          kind: 'tool',
          id: 'c2',
          call: searchToolCall({
            query: 'budget',
            resultCount: 1,
            searchResults: [{ title: 'Budget', path: '/docs/budget.md', excerpt: 'monthly', line: 1 }],
          }),
        },
      ],
    });
    const el = await mount([t]);
    const card = toolCard(el)!;
    await card.updateComplete;
    const row = card.shadowRoot?.querySelector(
      '[data-testid="tool-search-row"]',
    ) as HTMLButtonElement | null;
    expect(row).not.toBeNull();

    const raised: Sv3CitationOpen[] = [];
    el.addEventListener(SV3_CITATION_OPEN, (e) =>
      raised.push((e as CustomEvent<Sv3CitationOpen>).detail),
    );
    row!.click();
    expect(raised.map((d) => d.docPath)).toEqual(['/docs/budget.md']);
  });
});
