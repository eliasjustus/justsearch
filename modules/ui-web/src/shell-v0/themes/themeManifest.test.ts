// @vitest-environment happy-dom

/**
 * Slice 477 H3.3 — Theme manifest validator tests.
 */

import { describe, it, expect, vi } from 'vitest';
import {
  validateThemeManifest,
  fetchThemeManifest,
} from './themeManifest.js';

describe('validateThemeManifest', () => {
  it('accepts a minimal valid manifest', () => {
    const result = validateThemeManifest({
      schemaVersion: 1,
      themes: [
        { id: 'core.foo', displayName: 'Foo', cssPath: '/themes/foo.css' },
      ],
    });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.manifest.themes.length).toBe(1);
      expect(result.manifest.themes[0]!.id).toBe('core.foo');
    }
  });

  it('rejects non-object input', () => {
    const r = validateThemeManifest('hello');
    expect(r.ok).toBe(false);
  });

  it('rejects when schemaVersion is not literal 1', () => {
    const r = validateThemeManifest({ schemaVersion: 2, themes: [] });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.some((e) => e.includes('schemaVersion'))).toBe(true);
  });

  it('rejects when themes is not an array', () => {
    const r = validateThemeManifest({ schemaVersion: 1, themes: 'oops' });
    expect(r.ok).toBe(false);
  });

  it('rejects entries with malformed id', () => {
    const r = validateThemeManifest({
      schemaVersion: 1,
      themes: [{ id: 'BadId!', displayName: 'X', cssPath: '/themes/x.css' }],
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.some((e) => e.includes('id'))).toBe(true);
  });

  it('rejects duplicate ids', () => {
    const r = validateThemeManifest({
      schemaVersion: 1,
      themes: [
        { id: 'core.foo', displayName: 'A', cssPath: '/themes/a.css' },
        { id: 'core.foo', displayName: 'B', cssPath: '/themes/b.css' },
      ],
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.some((e) => e.includes('duplicated'))).toBe(true);
  });

  it('rejects cssPath without leading /', () => {
    const r = validateThemeManifest({
      schemaVersion: 1,
      themes: [{ id: 'core.x', displayName: 'X', cssPath: 'themes/x.css' }],
    });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.errors.some((e) => e.includes('cssPath'))).toBe(true);
  });

  it('accepts optional fields when present', () => {
    const r = validateThemeManifest({
      schemaVersion: 1,
      themes: [
        {
          id: 'core.x',
          displayName: 'X',
          cssPath: '/themes/x.css',
          description: 'a description',
          author: 'me',
          version: '1.0.0',
        },
      ],
    });
    expect(r.ok).toBe(true);
  });
});

describe('fetchThemeManifest', () => {
  it('returns the validated manifest when fetch succeeds', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          schemaVersion: 1,
          themes: [
            { id: 'core.test', displayName: 'Test', cssPath: '/themes/t.css' },
          ],
        }),
        { status: 200 },
      ),
    );
    const m = await fetchThemeManifest(fetchMock);
    expect(m).not.toBeNull();
    expect(m!.themes[0]!.id).toBe('core.test');
  });

  it('returns null on network failure', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('boom'));
    const m = await fetchThemeManifest(fetchMock);
    expect(m).toBeNull();
  });

  it('returns null on non-2xx response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('not found', { status: 404 }),
    );
    const m = await fetchThemeManifest(fetchMock);
    expect(m).toBeNull();
  });

  it('returns null on validation failure', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ schemaVersion: 99, themes: [] }),
        { status: 200 },
      ),
    );
    const m = await fetchThemeManifest(fetchMock);
    expect(m).toBeNull();
  });
});
