/**
 * Tempdoc 577 Goal 1 Phase 7 (570 Move B / §18 D3 interim) — the typed result view.
 *
 * Pins: kind derivation (file_kind first, mime_base disambiguates), the
 * excerpt-over-preview snippet preference, and word-boundary truncation (the
 * mid-word cut is unrepresentable).
 */
import { describe, it, expect } from 'vitest';
import { projectResultView, truncateAtWord } from './searchResultViewModel.js';

describe('searchResultViewModel (577 Phase 7)', () => {
  it('derives kind from file_kind, with mime_base as the fallback', () => {
    expect(projectResultView({ title: 't', path: 'p', kind: 'markdown' }).kind).toBe('markdown');
    expect(projectResultView({ title: 't', path: 'p', kind: 'code' }).kind).toBe('code');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'image/png' }).kind).toBe('image');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'application/pdf' }).kind).toBe('pdf');
    expect(projectResultView({ title: 't', path: 'p', mimeBase: 'text/plain' }).kind).toBe('document');
    expect(projectResultView({ title: 't', path: 'p' }).kind).toBe('other');
  });

  it('maps each kind to an icon glyph', () => {
    expect(projectResultView({ title: 't', path: 'p', kind: 'code' }).icon).toBe('code');
    expect(projectResultView({ title: 't', path: 'p', kind: 'image' }).icon).toBe('image');
    expect(projectResultView({ title: 't', path: 'p', kind: 'markdown' }).icon).toBe('file-text');
  });

  it('prefers the worker excerpt over content_preview and carries its line anchor', () => {
    const v = projectResultView({
      title: 't',
      path: 'p',
      snippet: 'raw preview text',
      excerptRegions: [{ text: 'the best passage', approxLine: 42 }],
    });
    expect(v.snippet).toBe('the best passage');
    expect(v.snippetSource).toBe('excerpt');
    expect(v.approxLine).toBe(42);
  });

  it('falls back to content_preview when no excerpt has text', () => {
    const v = projectResultView({
      title: 't',
      path: 'p',
      snippet: 'preview',
      excerptRegions: [{ text: '  ' }],
    });
    expect(v.snippet).toBe('preview');
    expect(v.snippetSource).toBe('preview');
  });

  it('truncates at a word boundary with an ellipsis — never mid-word', () => {
    const long = `${'word '.repeat(60)}ingestion-time`; // > 240 chars
    const out = truncateAtWord(long, 240);
    expect(out.length).toBeLessThanOrEqual(241); // incl. ellipsis
    expect(out.endsWith('…')).toBe(true);
    // The cut never leaves a partial word before the ellipsis.
    const beforeEllipsis = out.slice(0, -1).trimEnd();
    expect(long.includes(beforeEllipsis)).toBe(true);
    expect(long[beforeEllipsis.length]).toBe(' ');
  });

  it('short text passes through untouched', () => {
    expect(truncateAtWord('short', 240)).toBe('short');
  });
});
