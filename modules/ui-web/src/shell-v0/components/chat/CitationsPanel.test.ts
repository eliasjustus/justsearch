/**
 * @vitest-environment happy-dom
 *
 * Slice 493 — CitationsPanel tests (trust-tier rendering).
 */

import { beforeEach, describe, expect, it } from 'vitest';
import {
  CitationsPanel,
  type CitationMatch,
  type RetrievalCitation,
} from './CitationsPanel.js';
import './CitationsPanel.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../../state/selectedSource.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  // 559 C-1: sources are collapsed by default; open the disclosure so the
  // body-inspecting assertions below see the cards. (The default-collapsed
  // behavior itself is covered by the dedicated test at the end.)
  (el as unknown as { sourcesExpanded: boolean }).sourcesExpanded = true;
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function fakeSource(
  overrides: Partial<RetrievalCitation> = {},
): RetrievalCitation {
  return {
    parentDocId: 'doc.fake',
    chunkIndex: 0,
    chunkTotal: 1,
    startChar: 0,
    endChar: 100,
    score: 0.85,
    excerpt: 'Default excerpt text.',
    startLine: 0,
    endLine: 5,
    headingText: '',
    headingLevel: 0,
    ...overrides,
  };
}

function fakeCitation(overrides: Partial<CitationMatch> = {}): CitationMatch {
  return {
    sentenceIndex: 0,
    sentenceText: 'Default sentence text.',
    sourceIndex: 0,
    similarity: 0.85,
    parentDocId: 'doc.fake',
    ...overrides,
  };
}

/**
 * Tempdoc 849 slice 3 §5 — the RETRIEVED-vs-RECEIVED badge on the source card.
 *
 * Its own describe block because the discipline being pinned is not "the badge renders" but "the
 * badge renders EXACTLY when the producer resolved the state": the absence case is the one that
 * keeps a pre-849 conversation from being retroactively described.
 */
describe('CitationsPanel — 849 inclusion badge', () => {
  async function panelText(source: RetrievalCitation): Promise<string> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [source];
    el.citations = [fakeCitation({ parentDocId: source.parentDocId, sourceIndex: 0 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    el.remove();
    return text;
  }

  it('names a DROPPED passage as retrieved but never sent to the model', async () => {
    const text = await panelText(fakeSource({ contextInclusion: 'dropped' }));
    expect(text).toContain('Retrieved · never sent to the model');
    // The two budget facts sit side by side and stay distinguishable: one is about the MATCHER
    // (did it ground a sentence), the other about the PROMPT (did the model see it) — 849 §5.5.
    expect(text).toContain('Grounds 1 sentence');
  });

  it('names the partial and included states in the same vocabulary', async () => {
    expect(await panelText(fakeSource({ contextInclusion: 'partial' }))).toContain(
      'Partly sent to the model',
    );
    expect(await panelText(fakeSource({ contextInclusion: 'included' }))).toContain(
      'Sent to the model',
    );
  });

  it('renders NOTHING for a citation that said nothing about inclusion', async () => {
    // Every conversation persisted before 849 lands here. The card must be silent — not "included",
    // and not a placeholder caveat, which would put a hedge on the entire history.
    const text = await panelText(fakeSource());
    expect(text).not.toContain('sent to the model');
    expect(text).not.toContain('Sent to the model');
    // Non-vacuity: the card DID render (so "no badge" is the badge's absence, not an empty panel).
    expect(text).toContain('Grounds 1 sentence');
  });

  it('an unrecognised state is absence, not a guess', async () => {
    const text = await panelText(
      fakeSource({ contextInclusion: 'mostly' as never }),
    );
    expect(text).not.toContain('sent to the model');
    expect(text).not.toContain('mostly');
  });
});

describe('CitationsPanel', () => {
  it('renders nothing when both arrays are empty', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [];
    el.sources = [];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').trim();
    expect(text).toBe('');
    el.remove();
  });

  // Tempdoc 603 C1 — sources are graded by GROUNDING (faithfulness from the citation-matches), NOT the
  // BM25 retrieval score. A cited source ranks under "Grounds the answer"; a retrieved-but-uncited source
  // is demoted to the collapsed "retrieved · not cited" group (never "high confidence").
  it('grades a CITED source by grounding (Grounds the answer), joining by ARRAY POSITION not the doc-ordinal', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.architecture',
        chunkIndex: 2, // the source's DOC-ORDINAL (display only) — deliberately != its array position (0)
        score: 0.92, // BM25 — must NOT surface as a trust %
        excerpt: 'The system uses a three-process model.',
      }),
    ];
    // 603 PART X.B — the match's sourceIndex is the source's POSITION in this list (0), not the doc-ordinal (2).
    // Joining by doc-ordinal (the §1 bug) would find nothing here and wrongly read "not cited".
    el.citations = [
      fakeCitation({ parentDocId: 'doc.architecture', sourceIndex: 0, similarity: 0.8 }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 source');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('Grounds 1 sentence');
    expect(text).not.toContain('92%'); // the BM25 retrieval number is gone (559 §5)
    expect(text).toContain('three-process model');
    el.remove();
  });

  it('demotes a retrieved-but-UNCITED source to "retrieved · not cited", never high confidence', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      // cited: grounds the answer
      fakeSource({ parentDocId: 'a.md', chunkIndex: 0, excerpt: 'Grounded passage' }),
      // retrieved (high BM25) but never cited → demoted
      fakeSource({ parentDocId: 'b.md', chunkIndex: 1, score: 0.99, excerpt: 'Unused passage' }),
    ];
    el.citations = [fakeCitation({ parentDocId: 'a.md', sourceIndex: 0, similarity: 0.85 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('a.md');
    // the uncited high-BM25 source is in the collapsed "retrieved (not cited)" group, NOT shown by default
    expect(text).toContain('1 retrieved (not cited)');
    expect(text).not.toContain('Unused passage');
    el.remove();
  });

  it('no citation-matches → neutral flat list (no trust grade, no BM25 %)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource({ parentDocId: 'a.md', score: 0.92, excerpt: 'Retrieved text' })];
    el.citations = []; // matcher didn't run
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 source retrieved');
    expect(text).toContain('Retrieved text');
    expect(text).not.toContain('Grounds');
    expect(text).not.toContain('Grounds the answer');
    expect(text).not.toContain('92%');
    el.remove();
  });

  it('shows heading breadcrumb when present', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.md',
        score: 0.8,
        headingText: 'Getting Started',
      }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Getting Started');
    el.remove();
  });

  it('renders citation-match fallback with confidence bars', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [
      fakeCitation({ similarity: 0.75, sentenceText: 'Matched claim.' }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 citation');
    expect(text).toContain('75%');
    expect(text).toContain('Matched claim');
    el.remove();
  });

  // The score clamp lives in the citation-match fallback (the one place a % is still shown — the
  // per-sentence faithfulness similarity). Source cards no longer show a % (603 C1).
  it('clamps citation-match scores above 1.0 to 100%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: 2.09 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('100%');
    expect(text).not.toContain('209%');
    const fill = el.shadowRoot?.querySelector('.fill') as HTMLElement | null;
    expect(fill?.style.width).toBe('100%');
    el.remove();
  });

  it('clamps negative citation-match scores to 0%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: -0.5 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('0%');
    el.remove();
  });

  it('fires citation-select exactly once per click', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource()];
    document.body.appendChild(el);
    await settle(el);
    let count = 0;
    el.addEventListener('citation-select', () => count++);
    const btn = el.shadowRoot?.querySelector('button.source') as HTMLElement;
    btn?.click();
    expect(count).toBe(1);
    el.remove();
  });

  it('collapses sources by default and discloses on toggle (559 C-1)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource(), fakeSource()];
    // 603 C1 / PART X.B — the collapsible disclosure is the GROUNDED (tiered) path; supply a match per
    // source POSITION (0 and 1) so both are cited and render through renderTieredSources (the flat
    // no-matches path has no disclosure). Grounding joins by array position, so one match per index.
    el.citations = [fakeCitation({ sourceIndex: 0 }), fakeCitation({ sourceIndex: 1 })];
    document.body.appendChild(el);
    // Raw settle (no auto-expand) — assert the default-collapsed state.
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const header = el.shadowRoot?.querySelector('button.panel-header');
    expect(header?.getAttribute('aria-expanded')).toBe('false');
    expect(el.shadowRoot?.querySelector('button.source')).toBeNull();
    expect((header?.textContent ?? '').replace(/\s+/g, ' ')).toContain('2 sources');
    // Toggling discloses the cards.
    (header as HTMLElement).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(header?.getAttribute('aria-expanded')).toBe('true');
    expect(el.shadowRoot?.querySelectorAll('button.source').length).toBe(2);
    el.remove();
  });

  /* ── Tempdoc 822 Phase F11: the additive `externalDisclosure`, and the shipped default it must
        not move. The property lets ONE consumer (the Search v3 window) own the disclosure itself;
        the containment rule is that every other consumer renders byte-identically to before, so the
        default path is asserted here on ALL THREE header paths rather than on the one that changed. */

  /** The three shapes the panel's `render` dispatches over — one per header path. */
  const paths = [
    {
      what: 'tiered sources (the disclosure path)',
      apply: (el: CitationsPanel) => {
        el.sources = [fakeSource(), fakeSource()];
        el.citations = [fakeCitation({ sourceIndex: 0 }), fakeCitation({ sourceIndex: 1 })];
      },
      header: 'button.panel-header',
      body: 'button.source',
    },
    {
      what: 'flat sources (no matches, so no trust grade)',
      apply: (el: CitationsPanel) => {
        el.sources = [fakeSource(), fakeSource()];
        el.citations = [];
      },
      header: 'div.panel-header',
      body: 'button.source',
    },
    {
      what: 'citation-matches only (no retrieval sources)',
      apply: (el: CitationsPanel) => {
        el.sources = [];
        el.citations = [fakeCitation()];
      },
      header: 'div.panel-header',
      body: '.citation',
    },
  ] as const;

  const mount = async (
    apply: (el: CitationsPanel) => void,
    external: boolean,
  ): Promise<CitationsPanel> => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    apply(el);
    if (external) el.externalDisclosure = true;
    document.body.appendChild(el);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    return el;
  };

  for (const path of paths) {
    it(`leaves the SHIPPED default untouched — ${path.what}`, async () => {
      const el = await mount(path.apply, false);
      // The header is still the panel's own, and the two always-open bodies are still open: a
      // default that quietly started gating itself would close UnifiedChatView and SummarizeView.
      expect(el.externalDisclosure).toBe(false);
      expect(el.shadowRoot?.querySelector(path.header)).not.toBeNull();
      const openByDefault = path.header === 'div.panel-header';
      expect(el.shadowRoot?.querySelector(path.body) !== null).toBe(openByDefault);
      el.remove();
    });

    it(`renders BODY ONLY when the host owns the disclosure — ${path.what}`, async () => {
      const el = await mount(path.apply, true);
      // No header at all, on any of the three paths...
      expect(el.shadowRoot?.querySelector('.panel-header')).toBeNull();
      // ...and no body either, until the host says the disclosure is open — a suppressed toggle
      // must not leave a body permanently expanded.
      expect(el.shadowRoot?.querySelector(path.body)).toBeNull();
      el.sourcesExpanded = true;
      await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
      expect(el.shadowRoot?.querySelector('.panel-header')).toBeNull();
      expect(el.shadowRoot?.querySelector(path.body)).not.toBeNull();
      el.remove();
    });
  }
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 citation-mark presentation §5.4 (F1) — THE FAR SIDE OF THE SELECTION.
 *
 * `MarkdownBlock.ts` promises the selected mark is "highlighted in sync with the rail card". In the
 * Search v3 window the counterpart surface is THIS panel, and it carried zero references to
 * `selectedSource`: selecting a citation lit the inline mark and left its source card identical to
 * every other card. The store's whole justification is relating two surfaces; it rendered on one.
 *
 * The key is computed through the ONE `sourceKey` authority over the SAME two fields
 * `MarkdownBlock.makeMarker` uses — a second key function here would silently never agree.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('CitationsPanel 822 §5.4 — the source card is the selection’s far side', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  const A = { parentDocId: 'doc.alpha', startLine: 11 };
  const B = { parentDocId: 'doc.beta', startLine: 42 };

  /** Two cards from two DIFFERENT docs, disclosed, through the flat (no-grade) path. */
  async function mountTwo(): Promise<CitationsPanel> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({ ...A, excerpt: 'Alpha excerpt.' }),
      fakeSource({ ...B, excerpt: 'Beta excerpt.' }),
    ];
    el.citations = [];
    document.body.appendChild(el);
    await settle(el);
    return el;
  }

  const cards = (el: CitationsPanel): HTMLElement[] =>
    Array.from(el.shadowRoot?.querySelectorAll('button.source') ?? []) as HTMLElement[];

  /** The nth card, or a failure — an absent card must never read as a passing assertion. */
  const cardAt = (el: CitationsPanel, i: number): HTMLElement => {
    const c = cards(el)[i];
    if (!c) throw new Error(`no source card at index ${i}`);
    return c;
  };

  /* ── Reading the cascade out of the sheet itself ────────────────────────────────────────────
     happy-dom ABANDONS any declaration whose value carries a var() FALLBACK, and every rule this
     section governs is written that way — so a computed-style comparison of a selected card against
     an unselected one is passed by ANY fallback, right or wrong (probed: pointing the selected
     card's fill at the destructive role instead of the base surface stayed green). It cannot see
     `:hover` either. The
     invariants therefore have to be read off the stylesheet as STRUCTURE, related to each other,
     rather than pinned as literal strings on one side only — a literal pins the sheet against a
     copy of itself in the test, whereas relating the two rules breaks if EITHER side drifts. */

  const CSS = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
  /** Comments stripped, so a WHY-comment between two declarations cannot hide one from the parse. */
  const CSS_BARE = CSS.replace(/\/\*[\s\S]*?\*\//g, '');

  /** The declaration block of one rule, by its selector (regex source), or a failure. */
  const ruleBody = (selector: string): string => {
    const found = new RegExp(`(?:^|[\\s}])${selector}\\s*\\{([^}]*)\\}`).exec(CSS_BARE);
    if (!found?.[1]) throw new Error(`no rule for ${selector}`);
    return found[1];
  };

  /** Every `var(--…)` name a property's value mentions, outermost first. */
  const tokensOf = (body: string, property: string): string[] => {
    const decl = new RegExp(`(?:^|;)\\s*${property}\\s*:([^;]+);`).exec(body)?.[1];
    if (decl === undefined) throw new Error(`no '${property}' declaration in: ${body.trim()}`);
    const names = [...decl.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1] as string);
    if (names.length === 0) throw new Error(`'${property}' names no token: ${decl}`);
    return names;
  };

  /** The opt-in knob (outermost var) and the value it falls back to (innermost var). */
  const knobOf = (body: string, property: string): string => tokensOf(body, property)[0] as string;
  const fallbackOf = (body: string, property: string): string => {
    const names = tokensOf(body, property);
    return names[names.length - 1] as string;
  };

  it('marks ONLY the card whose source key is selected', async () => {
    const el = await mountTwo();
    expect(cards(el)).toHaveLength(2);
    expect(cards(el).filter((c) => c.hasAttribute('data-selected'))).toHaveLength(0);
    // Each card publishes the identity its inline mark carries in `dataset.citeKey`, from the ONE
    // `sourceKey` authority. Positional correspondence between the two surfaces is not one — the
    // panel renders every retrieved source, marks exist only for cited ones — so anything relating
    // a card to a mark (the `sv3-citation-selected` harness step) has to match on this.
    expect(cards(el).map((c) => c.dataset.citeKey)).toEqual([
      sourceKey(A.parentDocId, A.startLine),
      sourceKey(B.parentDocId, B.startLine),
    ]);

    setSelectedSource(sourceKey(B.parentDocId, B.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    const marked = cards(el).map((c) => c.hasAttribute('data-selected'));
    expect(marked).toEqual([false, true]);

    // The key is the (parentDocId, startLine) PAIR: the same doc at another line is a different
    // source, so a card must not light for its neighbour's passage.
    setSelectedSource(sourceKey(B.parentDocId, B.startLine + 1));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cards(el).filter((c) => c.hasAttribute('data-selected'))).toHaveLength(0);
    el.remove();
  });

  it('clicking a card publishes that source as the cross-surface selection', async () => {
    const el = await mountTwo();
    expect(getSelectedSource()).toBeNull();

    let dispatched = 0;
    el.addEventListener('citation-select', () => {
      dispatched += 1;
    });
    cardAt(el, 0).click();

    // The store now holds the clicked card's key...
    expect(getSelectedSource()).toBe(sourceKey(A.parentDocId, A.startLine));
    // ...and the existing deep-link dispatch still fires: the selection is an ADDED line, not a
    // replacement for what the card already did.
    expect(dispatched).toBe(1);

    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);
    el.remove();
  });

  it('unsubscribes from the store on disconnect (no leaked listener)', async () => {
    const el = await mountTwo();
    setSelectedSource(sourceKey(A.parentDocId, A.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);

    el.remove();
    setSelectedSource(null);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    // A live subscription would have re-rendered the detached panel and dropped the attribute.
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);

    // NON-VACUITY: a detached Lit element still renders when asked, so the stale attribute above
    // means "no notification arrived", not "updates stopped working". Ask directly and it clears.
    el.requestUpdate();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(false);
  });

  it('announces the selected card to assistive tech, and only that card', async () => {
    // `data-selected` is a styling hook; it is invisible to a screen reader. Marking the card with
    // it alone reproduced, on the far side, the exact "state was visual-only" defect (F6) the slice
    // had just fixed on the inline mark. The idiom is `MarkdownBlock.ts`'s: present-and-true when
    // selected, REMOVED otherwise — never "false", which some readers announce as a present-but-off
    // property (noise on every other card in the list).
    const el = await mountTwo();
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(0);

    setSelectedSource(sourceKey(B.parentDocId, B.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    expect(cardAt(el, 1).getAttribute('aria-current')).toBe('true');
    expect(cardAt(el, 0).hasAttribute('aria-current')).toBe(false);
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(1);

    setSelectedSource(null);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(0);
    el.remove();
  });

  it('renders a selected card IDENTICALLY to an unselected one with no --cp-* tokens set (the shipped-containment proof)', async () => {
    const el = await mountTwo();
    // Sentinel values for the two tokens the BASE `.citation, .source` rule declares, so the
    // equality below is a comparison of real colours rather than of two empty strings.
    el.style.setProperty('--surface-2', 'rgb(1, 2, 3)');
    el.style.setProperty('--border-subtle', 'rgb(4, 5, 6)');
    setSelectedSource(sourceKey(A.parentDocId, A.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const selected = cardAt(el, 0);
    const unselected = cardAt(el, 1);
    expect(selected.hasAttribute('data-selected')).toBe(true);
    expect(unselected.hasAttribute('data-selected')).toBe(false);

    // `.source[data-selected]` is (0,2,0) and OUTRANKS the base `.citation, .source` at (0,1,0), so
    // its fallbacks are not decoration — they are what a selected card resolves to in every consumer
    // that does not opt in (UnifiedChatView, SummarizeView, where `MarkdownBlock` marks and `SourcesPane`
    // both write the store, so `data-selected` IS reachable). Defaulting them to the base rule's own
    // `--surface-2` / `--border-subtle` is what makes "shipped is unaffected" true: the two cards
    // must be indistinguishable. A `transparent` default would blank the selected card instead.
    //
    // THE DISCRIMINATING HALF. The computed comparison below cannot see this: happy-dom abandons the
    // nested-fallback declarations outright, so the selected card falls through to the base rule no
    // matter WHAT the fallback names, and a wrong one stayed green. Relating the two rules' token
    // names is what actually proves the claim in the title — and it breaks from either direction,
    // whether the selected rule's fallback drifts or the base rule is repainted out from under it.
    const base = ruleBody('\\.citation,\\s*\\.source');
    const selectedRule = ruleBody('\\.source\\[data-selected\\]');
    expect(fallbackOf(selectedRule, 'background')).toBe(fallbackOf(base, 'background'));
    expect(fallbackOf(selectedRule, 'border-color')).toBe(fallbackOf(base, 'border'));
    // …and each one is opt-in through a `--cp-*` knob, which is what lets v3 wash the card at all.
    expect(knobOf(selectedRule, 'background')).toMatch(/^--cp-/);
    expect(knobOf(selectedRule, 'border-color')).toMatch(/^--cp-/);

    const sel = getComputedStyle(selected);
    const unsel = getComputedStyle(unselected);
    expect(unsel.backgroundColor).toBe('rgb(1, 2, 3)');
    expect(unsel.borderTopColor).toBe('rgb(4, 5, 6)');
    expect(sel.backgroundColor).toBe(unsel.backgroundColor);
    expect(sel.borderTopColor).toBe(unsel.borderTopColor);
    expect(sel.borderLeftColor).toBe(unsel.borderLeftColor);
    el.remove();
  });

  it('freezes the selected card’s un-overridden fill and edge at the base rule’s own tokens', () => {
    // Source-level, deliberately, and for the reason `MarkdownBlock.test.ts:791` records: happy-dom
    // ABANDONS a declaration whose value contains a nested var() fallback, which both of these are —
    // so the sentinel half of this proof (set `--cp-selected-region`, watch only the selected card
    // take it) is not expressible here and belongs to the live browser check. What IS checkable, and
    // is the actual invariant, is that the two fallbacks name the SAME tokens the base rule paints
    // with: that is why the computed equality above holds, and it would break the moment either
    // default drifted back toward `transparent`.
    const cssText = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
    expect(cssText).toContain('background: var(--cp-selected-region, var(--surface-2));');
    expect(cssText).toContain('border-color: var(--cp-selected-edge, var(--border-subtle));');
    // …and those ARE the base rule's values, read off the base rule itself rather than assumed.
    const base = /\.citation,\s*\.source\s*\{([^}]*)\}/.exec(cssText)![1]!;
    expect(base).toContain('background: var(--surface-2);');
    expect(base).toContain('border: 1px solid var(--border-subtle);');
  });

  it('leaves the hover edge on its shipped default (--cp-hover-edge is opt-in only)', () => {
    const cssText = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
    // The accent is still the FALLBACK, so the shipped hover border is byte-identical; only a
    // window that declares `--cp-hover-edge` takes it off the accent (822 §5.4).
    expect(cssText).toContain('border-color: var(--cp-hover-edge, var(--accent-tint))');
    // The spec’s precedence rule, and it is about the WASH: `.source[data-selected]` sits after `.source:hover`
    // at the same (0,2,0), so a card that is both takes the selected fill; and the (0,3,0)
    // `[data-selected]:hover` after it raises that to the higher rung rather than layering a second
    // one under it. Flip this order and a hovered selected card would paint the plain hover fill.
    expect(cssText.indexOf('.source:hover')).toBeLessThan(cssText.indexOf('.source[data-selected]'));
    expect(cssText.indexOf('.source[data-selected] ')).toBeLessThan(
      cssText.indexOf('.source[data-selected]:hover'),
    );
  });

  it('keeps hover feedback on a card that is ALREADY selected', () => {
    // A shipped regression the containment claim denied. `.source:hover` and `.source[data-selected]`
    // are both (0,2,0) and the selected rule is later, so it took the border of any card the pointer
    // was over. While `data-selected` was unreachable that never showed; the moment this slice wired
    // the store up, the card the reader had just CLICKED became the one card in the panel with no
    // pointer feedback — in SummarizeView and UnifiedChatView alike. So the more specific
    // (0,3,0) rule has to restate the hover edge, not the background alone.
    const hovered = ruleBody('\\.source\\[data-selected\\]:hover');
    const resting = ruleBody('\\.source\\[data-selected\\]');
    // A selected+hovered card's border differs from a selected+unhovered one's: different knob…
    expect(knobOf(hovered, 'border-color')).not.toBe(knobOf(resting, 'border-color'));
    // …and, UNBRIDGED, the same ultimate colour `.source:hover` paints: a shipped consumer that
    // sets no --cp-* name sees hover mean one thing on every card, selected or not.
    expect(fallbackOf(hovered, 'border-color')).toBe(
      fallbackOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    // The plain hover knob stays IN the chain, so an unbridged consumer still gets exactly the edge
    // '.source:hover' paints — that is what shipped parity rests on.
    expect(tokensOf(hovered, 'border-color')).toContain(
      knobOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    // But its OWN knob comes first, deliberately: a consumer whose selected edge is STRONGER than
    // its hover edge (v3 spends 34% on selection and 14% on hover) would otherwise WEAKEN the edge
    // the moment the pointer arrived — reading as "less selected" exactly when the reader is acting
    // on it.
    expect(knobOf(hovered, 'border-color')).not.toBe(
      knobOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    expect(fallbackOf(hovered, 'border-color')).not.toBe(fallbackOf(resting, 'border-color'));
  });
});
