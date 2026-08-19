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
