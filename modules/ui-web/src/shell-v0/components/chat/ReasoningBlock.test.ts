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
    const el = await mount();
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
    const el = await mount();
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
    const el = await mount();
    const root = el.shadowRoot as ShadowRoot;
    const before = root.querySelector('.disclosure')?.getAttribute('aria-expanded');

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
    const el = await mount();
    const copy = el.shadowRoot?.querySelector('.copy-btn');
    // The measured defect: accessible name "clipboard-glyph", computed from CONTENT, so `title` — the
    // only prose the markup carried — could never win. aria-label outranks content, so this is the
    // name AT announces.
    expect(copy?.getAttribute('aria-label')).toBe('Copy reasoning');
    // …and the glyph is out of the a11y tree entirely, so there is no content left to compute from.
    // Tempdoc 859 §A §1.7 (A4) — the glyph is the product's `clipboard-copy` <svg> now, not a raw
    // emoji codepoint, so "there is a glyph" is asserted as the element rather than as text content.
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

  it('B-1b: a settled item is past-tense and offers the copy control', async () => {
    const el = await mount({ text: 'a finished thought', durationMs: 3000 });
    expect(el.shadowRoot?.querySelector('jf-pulse-dots')).toBeNull();
    expect(el.shadowRoot?.querySelector('.label')?.textContent).toBe('Thought for 3s');
    expect(el.shadowRoot?.querySelector('.copy-btn')).not.toBeNull();
    el.remove();
  });

  it('B-2 (A4): the copy control is the product’s <svg> glyph, and no U+1F4CB survives anywhere', async () => {
    const el = await mount();
    expect(el.shadowRoot?.querySelector('.copy-btn svg')).not.toBeNull();
    expect(el.shadowRoot?.innerHTML.includes('\u{1F4CB}')).toBe(false);
    el.remove();
  });

  it('A2: the IN-FEED form drops the card, keeps the rule, and pins the 24px floor', async () => {
    // A tool card is an action; a thought is subordinate to it. The hairline left rule is what says
    // "aside" — the filled box and the radius were what made the row read as a competing card.
    const inFeed = ruleBody(':host\\(\\[inline\\]\\) \\.container');
    expect(inFeed).toMatch(/background:\s*none/);
    expect(inFeed).toMatch(/border-radius:\s*0/);
    // Vertical rhythm belongs to `.run-feed`'s gap — ONE spacing authority — so the block declares
    // padding only, and no margin.
    expect(inFeed).not.toMatch(/margin/);
    // The base rule still carries the rule and the grade the a11y work pinned (F-07).
    const base = ruleBody('\\.container');
    expect(base).toMatch(/border-left:\s*3px solid var\(--border-muted\)/);
    expect(base).toMatch(/color:\s*var\(--text-secondary\)/);

    const el = await mount();
    el.inline = true;
    await settle(el);
    expect(el.hasAttribute('inline')).toBe(true);
    el.remove();
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
