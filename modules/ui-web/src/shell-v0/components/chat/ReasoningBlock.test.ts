/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 853 (F-07 / F-08) — the reasoning disclosure's a11y contract, pinned.
 *
 * The 2026-08-19 UX audit measured two SERIOUS findings on this component, both pre-existing markup
 * from v0.1.0 that #492's record-path rendering turned from a mid-stream flicker into a persistent
 * on-screen state:
 *
 *   F-08  axe `nested-interactive` (WCAG 4.1.2), n=1, every palette and both windows — the copy
 *         `<button>` sat INSIDE a `role="button" tabindex="0"` header, so the two roles conflicted
 *         and AT was free not to expose the copy control at all.
 *   F-07  axe `color-contrast` (WCAG 1.4.3) on the label AND the whole transcript — `--text-muted`
 *         measured 4.11:1 (light) and 4.40:1 (hc-light) at 13px, below the 4.5 floor.
 *
 * The component had no test file before this one, which is the reason both survived to an audit.
 * These assertions are the regression pin: the structural half of F-08 is checkable in the DOM, and
 * the token half of F-07 is checkable in the stylesheet (the RATIO itself is pinned separately, in
 * `themes/highContrastTextRoles.test.ts`, because it is a property of `tokens.css`, not of this
 * component).
 */
import { describe, expect, it } from 'vitest';
import { ReasoningBlock } from './ReasoningBlock.js';
import './ReasoningBlock.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

async function mount(props: Partial<{ text: string; durationMs: number }> = {}): Promise<ReasoningBlock> {
  const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
  el.text = props.text ?? 'Weighing the two candidate passages.';
  el.durationMs = props.durationMs ?? 7000;
  document.body.appendChild(el);
  await settle(el);
  return el;
}

/**
 * Tempdoc 870 item 4 — open the disclosure. The copy control renders only while the trace is
 * EXPANDED now (it acts on text a collapsed block does not show), so every assertion about that
 * control has to reach the state the control lives in first. Returns the settled element.
 */
async function expand(el: ReasoningBlock): Promise<ReasoningBlock> {
  (el.shadowRoot?.querySelector('.disclosure') as HTMLButtonElement).click();
  await settle(el);
  return el;
}

const styleText = (): string =>
  [ReasoningBlock.styles].flat(Infinity).map((s) => String((s as { cssText?: string }).cssText ?? s)).join('\n');

/** One rule's declaration body, so a size assertion cannot pass off a match from a NEIGHBOURING rule. */
const ruleBody = (selector: string): string => {
  const m = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styleText());
  if (!m) throw new Error(`ReasoningBlock.styles has no \`${selector}\` rule`);
  return m[1] as string;
};

describe('ReasoningBlock — F-08 nested-interactive', () => {
  it('the header row is inert layout, not a control', async () => {
    const el = await mount();
    const header = el.shadowRoot?.querySelector('.header');
    expect(header).toBeTruthy();
    // The exact markup axe flagged: `role="button" tabindex="0"` on a container that holds a button.
    expect(header?.hasAttribute('role')).toBe(false);
    expect(header?.hasAttribute('tabindex')).toBe(false);
    el.remove();
  });

  it('the disclosure is a native <button> carrying the name and the expanded state', async () => {
    const el = await mount();
    const disclosure = el.shadowRoot?.querySelector('.disclosure');
    expect(disclosure?.tagName).toBe('BUTTON');
    expect(disclosure?.getAttribute('aria-label')).toBe('Model reasoning trace');
    expect(disclosure?.getAttribute('aria-expanded')).toBe('false');
    // A native button is focusable WITHOUT an author tabindex — the property that makes the
    // role/tabindex pair above unnecessary rather than merely relocated.
    expect(disclosure?.hasAttribute('tabindex')).toBe(false);
    el.remove();
  });

  it('no interactive element is nested inside another (the axe rule, checked structurally)', async () => {
    const el = await expand(await mount());
    const root = el.shadowRoot as ShadowRoot;
    const FOCUSABLE = 'button, [role="button"], [tabindex], a[href], input, select, textarea';
    const interactive = [...root.querySelectorAll(FOCUSABLE)];
    // Both controls are present…
    expect(interactive.length).toBeGreaterThanOrEqual(2);
    // …and none of them contains another. This is exactly what `nested-interactive` asserts.
    for (const outer of interactive) {
      expect(outer.querySelector(FOCUSABLE)).toBeNull();
    }
    el.remove();
  });

  it('the copy control is a SIBLING of the disclosure, not a descendant', async () => {
    const el = await expand(await mount());
    const disclosure = el.shadowRoot?.querySelector('.disclosure');
    const copy = el.shadowRoot?.querySelector('.copy-btn');
    expect(copy?.tagName).toBe('BUTTON');
    expect(disclosure?.contains(copy as Node)).toBe(false);
    expect(copy?.parentElement).toBe(disclosure?.parentElement);
    el.remove();
  });
});

describe('ReasoningBlock — behaviour preserved across the restructure', () => {
  it('activating the disclosure toggles the trace and the expanded state', async () => {
    const el = await mount();
    const root = el.shadowRoot as ShadowRoot;
    const disclosure = root.querySelector('.disclosure') as HTMLButtonElement;

    expect(root.querySelector('.content')?.classList.contains('hidden')).toBe(true);

    disclosure.click();
    await settle(el);
    expect(root.querySelector('.disclosure')?.getAttribute('aria-expanded')).toBe('true');
    expect(root.querySelector('.content')?.classList.contains('hidden')).toBe(false);

    (root.querySelector('.disclosure') as HTMLButtonElement).click();
    await settle(el);
    expect(root.querySelector('.disclosure')?.getAttribute('aria-expanded')).toBe('false');
    expect(root.querySelector('.content')?.classList.contains('hidden')).toBe(true);
    el.remove();
  });

  it('activating copy does NOT also toggle the disclosure (no double-activation)', async () => {
    const el = await expand(await mount());
    const root = el.shadowRoot as ShadowRoot;
    const before = root.querySelector('.disclosure')?.getAttribute('aria-expanded');
    // The state the copy control is reachable from at all (870 item 4) — and the state a stray
    // re-toggle would visibly leave, which is what makes this assertion sharp.
    expect(before).toBe('true');

    (root.querySelector('.copy-btn') as HTMLButtonElement).click();
    await settle(el);

    // The old markup needed `stopPropagation` for this, because the copy click bubbled into the
    // header's own @click. Siblings make it structural — this asserts the replacement holds.
    expect(root.querySelector('.disclosure')?.getAttribute('aria-expanded')).toBe(before);
    el.remove();
  });

  it('offers no copy control while the trace is still streaming', async () => {
    const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
    el.controller = {
      isThinking: true,
      reasoningText: 'partial…',
      reasoningBlocks: [],
      elapsedSeconds: 3,
    } as unknown as ReasoningBlock['controller'];
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('.copy-btn')).toBeNull();
    expect(el.shadowRoot?.querySelector('.disclosure')).toBeTruthy();
    el.remove();
  });

  it('renders the duration label', async () => {
    const el = await mount({ durationMs: 7000 });
    expect(el.shadowRoot?.querySelector('.label')?.textContent).toContain('Thought for 7s');
    el.remove();
  });
});

describe('ReasoningBlock — F-09 copy-control name and target size', () => {
  it('names the copy control with aria-label, not with its glyph', async () => {
    const el = await expand(await mount());
    const copy = el.shadowRoot?.querySelector('.copy-btn');
    // The measured defect: accessible name "clipboard-glyph", computed from CONTENT, so `title` — the
    // only prose the markup carried — could never win. aria-label outranks content, so this is the
    // name AT announces.
    expect(copy?.getAttribute('aria-label')).toBe('Copy reasoning');
    // …and the glyph is out of the a11y tree entirely, so there is no content left to compute from.
    // Tempdoc 859 §A §1.7 (A4), glyph re-pointed to lucide `copy` by 870 item 5 — the glyph is a
    // product <svg> either way, not a raw emoji codepoint, so "there is a glyph" is asserted as the
    // element rather than as text content.
    const glyph = copy?.firstElementChild;
    expect(glyph?.getAttribute('aria-hidden')).toBe('true');
    expect(glyph?.querySelector('svg')).not.toBeNull();
    // The pointer affordance is unchanged — this is a name fix, not a tooltip removal.
    expect(copy?.getAttribute('title')).toBe('Copy reasoning');
    el.remove();
  });

  it('gives both controls a >=24px hit area (WCAG 2.2 2.5.8)', () => {
    // Measured before the fix: copy 23 x 19, disclosure row 541 x 20 — both under the floor.
    // happy-dom does not lay out shadow content, so there is no honest COMPUTED box to read here;
    // the declarations are asserted instead, each scoped to its own rule body so a stray `24px`
    // elsewhere in the sheet cannot satisfy them.
    const copy = ruleBody('\\.copy-btn');
    expect(copy).toMatch(/min-width:\s*24px/);
    expect(copy).toMatch(/min-height:\s*24px/);
    // The copy glyph is centred rather than top-left in the grown box.
    expect(copy).toMatch(/display:\s*inline-flex/);
    // The disclosure is wide by layout (flex: 1); only its block axis was short.
    expect(ruleBody('\\.disclosure')).toMatch(/min-height:\s*24px/);
  });
});

describe('ReasoningBlock — the run-timeline form (859 §A)', () => {
  it('B-1 (D-5): `streaming` drives the affordance with NO controller, and the label says the item’s own N', () => {
    // The run timeline renders each region as a VALUE, so the live one has no controller to ask for
    // an elapsed figure. Asserting the LABEL TEXT, not the presence of the dots, is the point: the
    // `?? 0` this replaces left a live, ticking thought reading "Thinking (0s)" for the whole run,
    // and every dots-presence assertion would have passed over it.
    return (async () => {
      const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
      el.text = 'still working on it';
      el.durationMs = 4000;
      el.streaming = true;
      document.body.appendChild(el);
      await settle(el);

      expect(el.shadowRoot?.querySelector('jf-pulse-dots')).not.toBeNull();
      expect(el.shadowRoot?.querySelector('.label')?.textContent).toBe('Thinking (4s)');
      // A live region has nothing final to copy yet.
      expect(el.shadowRoot?.querySelector('.copy-btn')).toBeNull();
      el.remove();
    })();
  });

  it('B-1b: a settled item is past-tense and offers the copy control once opened', async () => {
    const el = await mount({ text: 'a finished thought', durationMs: 3000 });
    expect(el.shadowRoot?.querySelector('jf-pulse-dots')).toBeNull();
    expect(el.shadowRoot?.querySelector('.label')?.textContent).toBe('Thought for 3s');
    await expand(el);
    expect(el.shadowRoot?.querySelector('.copy-btn')).not.toBeNull();
    el.remove();
  });

  it('B-2 (A4): the copy control is the product’s <svg> glyph, and no U+1F4CB survives anywhere', async () => {
    const el = await expand(await mount());
    expect(el.shadowRoot?.querySelector('.copy-btn svg')).not.toBeNull();
    expect(el.shadowRoot?.innerHTML.includes('\u{1F4CB}')).toBe(false);
    el.remove();
  });

  it('A2: the IN-FEED form drops the card, keeps the rule, and pins the 24px floor', async () => {
    // A tool card is an action; a thought is subordinate to it. The hairline left rule is what says
    // "aside" — the filled box and the radius were what made the row read as a competing card.
    // Tempdoc 870 item 3 retired the card from the OTHER form too, so the rule this form keeps is
    // declared here rather than inherited from a base that no longer draws a box — and the
    // `background: none` / `border-radius: 0` RESETS went with it. Pinned as absent, not merely
    // deleted: a reset that outlives the declaration it countered is residue reading as live intent.
    const inFeed = ruleBody(':host\\(\\[inline\\]\\) \\.container');
    expect(inFeed).toMatch(/border-left:\s*3px solid var\(--border-muted\)/);
    expect(inFeed).not.toMatch(/background/);
    expect(inFeed).not.toMatch(/border-radius/);
    // Vertical rhythm belongs to `.run-feed`'s gap — ONE spacing authority — so the block declares
    // padding only, and no margin. The slim form's host margin below is scoped away from this arm
    // for exactly that reason.
    expect(inFeed).not.toMatch(/margin/);
    expect(styleText()).not.toMatch(/:host\(\[inline\]\)\s*\{/);
    // The base rule keeps the grade the a11y work pinned (F-07) and NONE of the card chrome: a
    // background or radius reappearing there would put the box back under both forms at once.
    const base = ruleBody('\\.container');
    expect(base).toMatch(/color:\s*var\(--text-secondary\)/);
    expect(base).not.toMatch(/background|border-radius|border-left/);

    const el = await mount();
    el.inline = true;
    await settle(el);
    expect(el.hasAttribute('inline')).toBe(true);
    el.remove();
  });
});

/**
 * Tempdoc 870 — the owner's 2026-08-26 visual pass on this block: items 3 (slim disclosure), 4 (the
 * copy control is part of the expanded trace) and 5 (the lucide `copy` glyph).
 */
describe('ReasoningBlock — 870 the slim disclosure', () => {
  it('item 4: the copy control renders only while the trace is EXPANDED', async () => {
    const el = await mount({ text: 'a settled thought', durationMs: 2000 });
    // Collapsed and settled: text exists, but it is not on screen, so neither is the action on it.
    expect(el.shadowRoot?.querySelector('.disclosure')?.getAttribute('aria-expanded')).toBe('false');
    expect(el.shadowRoot?.querySelector('.copy-btn')).toBeNull();

    await expand(el);
    expect(el.shadowRoot?.querySelector('.content')?.classList.contains('hidden')).toBe(false);
    expect(el.shadowRoot?.querySelector('.copy-btn')).not.toBeNull();

    // …and it leaves again with the trace, rather than surviving as a stale control.
    await expand(el);
    expect(el.shadowRoot?.querySelector('.copy-btn')).toBeNull();
    el.remove();
  });

  it('item 4: an EXPANDED but still-streaming block offers no copy control', async () => {
    // The `!streaming` half of the condition is independent of the collapsed half: a trace being
    // written is visible and still has nothing final to copy.
    const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
    el.text = 'partial…';
    el.streaming = true;
    document.body.appendChild(el);
    await settle(el);
    await expand(el);
    expect(el.shadowRoot?.querySelector('.content')?.classList.contains('hidden')).toBe(false);
    expect(el.shadowRoot?.querySelector('.copy-btn')).toBeNull();
    el.remove();
  });

  it('item 3: the ask-arm form is a slim text disclosure, not a card', () => {
    const css = styleText();
    // Content-sized header at the tail's own 12px rung, so the collapsed row is a line of text and
    // not a full-width band.
    const header = ruleBody(':host\\(:not\\(\\[inline\\]\\)\\) \\.header');
    expect(header).toMatch(/display:\s*inline-flex/);
    expect(header).toMatch(/font-size:\s*var\(--font-size-xs\)/);
    // The chevron TRAILS the label — by flex order, so DOM order (and therefore reading order)
    // still puts the label first.
    expect(ruleBody(':host\\(:not\\(\\[inline\\]\\)\\) \\.chevron')).toMatch(/order:\s*1/);
    // The expanded trace carries the hairline the collapsed header gave up.
    expect(ruleBody(':host\\(:not\\(\\[inline\\]\\)\\) \\.content')).toMatch(
      /border-left:\s*3px solid var\(--border-muted\)/,
    );
    // No card chrome anywhere in the sheet's container rules — the box is gone, not overridden.
    expect(css).not.toMatch(/background:\s*var\(--surface-subtle\);[\s\S]{0,40}border-radius/);
  });

  it('item 3: the slim form brings its own seam, because not every container has a gap', () => {
    // The card carried 0.5rem of padding and a fill; a bare 12px line carries neither, and the
    // mount sites split on whether their container supplies the separation. NavigateView's and
    // SummarizeView's `.conversation` are flex columns with `gap: 0.75rem`; UnifiedChatView's
    // `.message` (views/unifiedChatStyles.ts) is a plain block and Search v3's `.turn` declares only
    // a `padding-bottom` — in those two the disclosure would abut the answer prose with nothing
    // between them. The margin rides the BLOCK, so a fifth mount site inherits the seam for free.
    expect(ruleBody(':host\\(:not\\(\\[inline\\]\\)\\)')).toMatch(/margin-block-end:\s*0\.5rem/);
    // …and never on the in-feed arm, whose ONE spacing authority is `.run-feed`'s gap (859 §A §1.6).
    expect(styleText()).not.toMatch(/:host\(\[inline\]\)[^{]*\{[^}]*margin/);
  });

  it('item 3: the streaming affordance and the [inline] form are untouched', async () => {
    const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
    el.text = 'still going';
    el.durationMs = 5000;
    el.streaming = true;
    el.inline = true;
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot?.querySelector('jf-pulse-dots')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.label')?.textContent).toBe('Thinking (5s)');
    el.remove();
  });

  it('item 5: the copy glyph is lucide `copy` (two rects), not the five-path clipboard', () => {
    // Told apart by shape rather than by name: the retired `clipboard-copy` opened with a 8x4 lid
    // rect and carried an inbound arrow; `copy` is two rounded rects and nothing else.
    const el = document.createElement('jf-reasoning-block') as ReasoningBlock;
    el.text = 'x';
    document.body.appendChild(el);
    return (async () => {
      await settle(el);
      await expand(el);
      const svg = el.shadowRoot?.querySelector('.copy-btn svg');
      expect(svg?.innerHTML).toContain('x="8" y="8"');
      expect(svg?.innerHTML).not.toContain('m15 10-4 4 4 4');
      el.remove();
    })();
  });
});

describe('ReasoningBlock — F-07 contrast', () => {
  it('no text in this component rides --text-muted any more', () => {
    // The failing grade, in both places the audit measured it: `.container { color }` (the label) and
    // the `--text-primary` re-point that dressed the whole transcript.
    expect(styleText()).not.toContain('var(--text-muted)');
  });

  it('the container and the transcript body both read --text-secondary', () => {
    const css = styleText();
    expect(css).toContain('color: var(--text-secondary)');
    expect(css).toContain('--text-primary: var(--text-secondary)');
  });
});
