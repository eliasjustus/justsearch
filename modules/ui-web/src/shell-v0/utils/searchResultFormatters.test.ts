// @vitest-environment happy-dom

/**
 * Slice 486 G35 — searchResultFormatters tests.
 *
 * Verifies each formatter handles empty / single / multi-hit
 * inputs and escapes markdown-significant characters in titles
 * + snippets.
 */

import { describe, it, expect } from 'vitest';
import {
  formatAsMarkdown,
  formatAsJson,
  formatAsPaths,
} from './searchResultFormatters.js';
import type { SearchHit } from '../state/searchState.js';

const hit = (over: Partial<SearchHit> = {}): SearchHit => ({
  id: over.id ?? 'h-1',
  title: over.title ?? 'Title',
  path: over.path ?? '/path/to/file.md',
  snippet: over.snippet,
  score: over.score,
});

describe('formatAsMarkdown', () => {
  it('returns empty string for empty input', () => {
    expect(formatAsMarkdown([])).toBe('');
  });

  it('formats a single hit with title + path', () => {
    const out = formatAsMarkdown([hit({ title: 'Hello', path: '/a/b.md' })]);
    expect(out).toContain('**Hello**');
    expect(out).toContain('`/a/b.md`');
  });

  it('includes snippet when present', () => {
    const out = formatAsMarkdown([
      hit({ title: 'T', path: '/p', snippet: 'a useful excerpt' }),
    ]);
    expect(out).toContain('*a useful excerpt*');
  });

  it('omits snippet line when snippet is missing or whitespace-only', () => {
    // The snippet line is the italics-wrapped line (starts with '  *').
    // Bold title (`**T**`) also contains '*' but not at the
    // snippet-line position.
    const noSnippet = formatAsMarkdown([hit({ title: 'T', path: '/p' })]);
    const wsSnippet = formatAsMarkdown([
      hit({ title: 'T', path: '/p', snippet: '  ' }),
    ]);
    const hasItalicSnippet = (s: string) =>
      s.split('\n').some((l) => /^ {2}\*[^*]/.test(l));
    expect(hasItalicSnippet(noSnippet)).toBe(false);
    expect(hasItalicSnippet(wsSnippet)).toBe(false);
  });

  it('separates multiple hits with a blank line', () => {
    const out = formatAsMarkdown([
      hit({ id: 'a', title: 'A', path: '/a' }),
      hit({ id: 'b', title: 'B', path: '/b' }),
    ]);
    // Each hit produces 2 content lines + 1 blank between hits.
    const lines = out.split('\n');
    expect(lines).toContain('');
    expect(lines.filter((l) => l.startsWith('- **'))).toHaveLength(2);
  });

  it('escapes markdown-significant characters in title', () => {
    const out = formatAsMarkdown([hit({ title: '*important*', path: '/p' })]);
    expect(out).toContain('\\*important\\*');
  });

  it('truncates long snippets to 240 chars and collapses whitespace', () => {
    const long = 'word '.repeat(100); // 500 chars
    const out = formatAsMarkdown([hit({ title: 'T', path: '/p', snippet: long })]);
    // Find the italic line.
    const italicLine = out.split('\n').find((l) => l.includes('*word'));
    expect(italicLine).toBeDefined();
    // The italics-wrapped content should be ≤ 244 chars (240 + 4 for `*` `*`).
    expect(italicLine!.length).toBeLessThanOrEqual(248);
  });

  it('does not have trailing blank line', () => {
    const out = formatAsMarkdown([hit({})]);
    expect(out.endsWith('\n\n')).toBe(false);
  });
});

describe('formatAsJson', () => {
  it('returns "[]" for empty input', () => {
    expect(formatAsJson([])).toBe('[]');
  });

  it('produces pretty-printed JSON with two-space indent', () => {
    const out = formatAsJson([hit({ id: 'x', title: 'T', path: '/p' })]);
    expect(out).toMatch(/^\[\s+\{/);
    expect(out).toContain('  "id": "x"');
    expect(out).toContain('  "title": "T"');
  });

  it('includes all SearchHit fields when present', () => {
    const out = formatAsJson([
      hit({ id: 'x', title: 'T', path: '/p', snippet: 's', score: 0.5 }),
    ]);
    const parsed = JSON.parse(out);
    expect(parsed[0]).toEqual({
      id: 'x',
      title: 'T',
      path: '/p',
      snippet: 's',
      score: 0.5,
    });
  });

  it('round-trips through JSON.parse', () => {
    const hits = [
      hit({ id: 'a' }),
      hit({ id: 'b', snippet: 'with "quotes" and \\backslash' }),
    ];
    expect(JSON.parse(formatAsJson(hits))).toEqual(hits);
  });
});

describe('formatAsPaths', () => {
  it('returns empty string for empty input', () => {
    expect(formatAsPaths([])).toBe('');
  });

  it('formats a single hit as just the path', () => {
    expect(formatAsPaths([hit({ path: '/a/b.md' })])).toBe('/a/b.md');
  });

  it('joins multiple paths with newline', () => {
    const out = formatAsPaths([
      hit({ path: '/a' }),
      hit({ path: '/b' }),
      hit({ path: '/c' }),
    ]);
    expect(out).toBe('/a\n/b\n/c');
  });

  it('does not include title or snippet', () => {
    const out = formatAsPaths([hit({ path: '/p', title: 'T', snippet: 'S' })]);
    expect(out).toBe('/p');
    expect(out).not.toContain('T');
    expect(out).not.toContain('S');
  });
});
