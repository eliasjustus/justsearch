// @vitest-environment happy-dom

/**
 * Tempdoc 602 R3 — the shared result-row presentation (path format + snippet
 * highlight), the one authority both the Search surface and the retrieve tier
 * project from. Pins: middle-ellipsis path truncation (filename preserved) and
 * the query-term `<mark>` highlight (incl. the empty-query no-mark edge).
 */
import { describe, it, expect } from 'vitest';
import { render, html } from 'lit';
import { formatDisplayPath, PATH_DISPLAY_MAX, highlightTerms } from './resultRowPresentation.js';

describe('formatDisplayPath (602 R3)', () => {
  it('passes short paths through unchanged', () => {
    expect(formatDisplayPath('/docs/q1.md')).toBe('/docs/q1.md');
    expect(formatDisplayPath('')).toBe('');
  });

  it('middle-ellipsis on long paths, preserving the filename', () => {
    const long = '/Users/alex/Documents/projects/justsearch/modules/ui-web/src/shell-v0/quarterly-report.md';
    expect(long.length).toBeGreaterThan(PATH_DISPLAY_MAX);
    const out = formatDisplayPath(long);
    expect(out).toContain('…');
    expect(out.endsWith('/quarterly-report.md')).toBe(true);
    expect(out.length).toBeLessThan(long.length);
  });

  it('handles a long no-separator path (head + ellipsis)', () => {
    const out = formatDisplayPath('x'.repeat(120));
    expect(out.endsWith('…')).toBe(true);
    expect(out.length).toBe(PATH_DISPLAY_MAX);
  });

  it('uses the backslash separator on Windows-style paths', () => {
    const long = 'C:\\Users\\elias\\Documents\\projects\\justsearch\\modules\\ui-web\\src\\shell-v0\\report.md';
    const out = formatDisplayPath(long);
    expect(out).toContain('…');
    expect(out.endsWith('\\report.md')).toBe(true);
  });
});

/**
 * Render a TemplateResult|string into a detached element and return it. We assert
 * on the live DOM (not innerHTML), because Lit interleaves comment markers in the
 * serialized HTML that would break naive substring matching.
 */
function renderToEl(node: ReturnType<typeof highlightTerms>): HTMLElement {
  const host = document.createElement('div');
  render(html`${node}`, host);
  return host;
}

describe('highlightTerms (602 R3)', () => {
  it('wraps a >=2-char query term in <mark class="hl">', () => {
    const mark = renderToEl(highlightTerms('the quarterly invoice total', 'invoice')).querySelector(
      'mark.hl',
    );
    expect(mark).not.toBeNull();
    expect(mark!.textContent).toBe('invoice');
  });

  it('is case-insensitive', () => {
    const mark = renderToEl(highlightTerms('Quarterly INVOICE due', 'invoice')).querySelector(
      'mark.hl',
    );
    expect(mark?.textContent).toBe('INVOICE');
  });

  it('returns the text unchanged for an empty / sub-2-char query (no mark)', () => {
    expect(highlightTerms('total due', '')).toBe('total due');
    expect(highlightTerms('total due', 'a')).toBe('total due');
    expect(renderToEl(highlightTerms('total due', '')).querySelector('mark')).toBeNull();
  });

  it('does not throw on regex-special characters in the query', () => {
    expect(() => renderToEl(highlightTerms('a (b) c', '(b)'))).not.toThrow();
  });
});
