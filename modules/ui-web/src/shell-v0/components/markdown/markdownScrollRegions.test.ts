/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 853 (F-05) — the scroll-region pass, pinned.
 *
 * The 2026-08-19 UX audit measured axe `scrollable-region-focusable` (serious, WCAG 2.1.1) n=2 on
 * the `DocumentPane` reading pane, in all four palettes: the ramp's `<table>` and `<pre>` scroll
 * horizontally and neither was focusable, so the clipped half of a wide table or fence was
 * unreachable by keyboard. These assertions are the regression pin for the remedy — `tabindex` plus
 * an accessible name on both container kinds — including the two properties a re-render could
 * silently break: idempotency, and that the pass leaves an author's own attributes alone.
 */
import { describe, expect, it } from 'vitest';
import { markScrollableRegions } from './markdownScrollRegions.js';

function root(html: string): HTMLElement {
  const el = document.createElement('div');
  el.innerHTML = html;
  return el;
}

describe('markScrollableRegions', () => {
  it('makes a rendered <pre> focusable and names it without minting a landmark', () => {
    const el = root('<pre><code class="language-java">int x = 1;</code></pre>');
    markScrollableRegions(el);
    const pre = el.querySelector('pre');
    expect(pre?.getAttribute('tabindex')).toBe('0');
    expect(pre?.getAttribute('aria-label')).toBe('Code block');
    // `group`, not `region`: `<pre>` maps to `generic`, where `aria-label` is PROHIBITED (the name
    // would be dropped), but a `region` with a name is a landmark — one per code fence on screen.
    expect(pre?.getAttribute('role')).toBe('group');
  });

  it('makes a rendered <table> focusable and named WITHOUT overriding its role', () => {
    const el = root('<table><tr><th>a</th><td>b</td></tr></table>');
    markScrollableRegions(el);
    const table = el.querySelector('table');
    expect(table?.getAttribute('tabindex')).toBe('0');
    expect(table?.getAttribute('aria-label')).toBe('Table');
    // The whole point of not reusing the `<pre>` treatment: a `role` here would cost the reader the
    // table semantics (rows, columns, headers) that the element already carries.
    expect(table?.hasAttribute('role')).toBe(false);
  });

  it('reaches every scroll container in a multi-block document, not just the first', () => {
    const el = root(
      '<div class="block"><table><tr><td>a</td></tr></table></div>' +
        '<div class="block"><pre><code>x</code></pre></div>' +
        '<div class="block"><pre><code>y</code></pre></div>',
    );
    markScrollableRegions(el);
    expect([...el.querySelectorAll('[tabindex="0"]')]).toHaveLength(3);
  });

  it('is idempotent — a re-render calls it again on the same tree', () => {
    const el = root('<pre><code>x</code></pre>');
    markScrollableRegions(el);
    markScrollableRegions(el);
    markScrollableRegions(el);
    const pre = el.querySelector('pre');
    expect(pre?.getAttribute('tabindex')).toBe('0');
    expect(pre?.getAttribute('role')).toBe('group');
  });

  it('leaves an element that already declares its own tabindex alone', () => {
    const el = root('<pre tabindex="-1" role="none">x</pre>');
    markScrollableRegions(el);
    const pre = el.querySelector('pre');
    expect(pre?.getAttribute('tabindex')).toBe('-1');
    expect(pre?.getAttribute('role')).toBe('none');
    expect(pre?.hasAttribute('aria-label')).toBe(false);
  });

  it('does not touch inline code or any other markdown element', () => {
    const el = root('<p>see <code>x</code> and <a href="#">y</a></p>');
    markScrollableRegions(el);
    expect(el.querySelector('[tabindex]')).toBeNull();
  });

  it('tolerates a null root (the pre-first-render call)', () => {
    expect(() => markScrollableRegions(null)).not.toThrow();
    expect(() => markScrollableRegions(undefined)).not.toThrow();
  });
});
