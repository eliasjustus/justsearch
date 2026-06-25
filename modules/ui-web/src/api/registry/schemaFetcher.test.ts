/**
 * Slice 3a.1.9 — schemaFetcher tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest,
  __seedForTest,
  fetchSchema,
  rewriteSchemaUrl,
} from './schemaFetcher';

describe('schemaFetcher', () => {
  beforeEach(() => {
    __resetForTest();
  });
  afterEach(() => {
    __resetForTest();
  });

  describe('rewriteSchemaUrl', () => {
    it('rewrites canonical URL to same-origin path', () => {
      expect(
        rewriteSchemaUrl('https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json'),
      ).toBe('/api/schemas/indexing-job-view.v1.json');
    });

    it('passes through same-origin paths unchanged', () => {
      expect(rewriteSchemaUrl('/api/schemas/foo.v1.json')).toBe('/api/schemas/foo.v1.json');
    });

    it('passes through non-canonical URLs unchanged', () => {
      const u = 'https://example.com/some-other-schema.json';
      expect(rewriteSchemaUrl(u)).toBe(u);
    });
  });

  describe('fetchSchema', () => {
    it('fetches from rewritten URL on first call', async () => {
      const schema = { type: 'object', properties: { id: { type: 'string' } } };
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(schema),
      });
      const result = await fetchSchema(
        'https://ssot.justsearch/v1/schemas/indexing-job-view.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      expect(result).toEqual(schema);
      expect(fetchImpl).toHaveBeenCalledWith('/api/schemas/indexing-job-view.v1.json');
    });

    it('memoizes on subsequent calls (same URL → one fetch)', async () => {
      const schema = { type: 'object' };
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: () => Promise.resolve(schema),
      });
      await fetchSchema(
        'https://ssot.justsearch/v1/schemas/x.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      await fetchSchema(
        'https://ssot.justsearch/v1/schemas/x.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    });

    it('returns null on 404', async () => {
      const fetchImpl = vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: () => Promise.resolve({}),
      });
      const result = await fetchSchema(
        '/api/schemas/nonexistent.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      expect(result).toBeNull();
    });

    it('returns null on network error', async () => {
      const fetchImpl = vi.fn().mockRejectedValue(new Error('boom'));
      const result = await fetchSchema(
        '/api/schemas/x.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      expect(result).toBeNull();
    });

    it('seeded schema returns without HTTP', async () => {
      const schema = { type: 'object', properties: {} };
      __seedForTest('/api/schemas/seeded.v1.json', schema);
      const fetchImpl = vi.fn();
      const result = await fetchSchema(
        '/api/schemas/seeded.v1.json',
        fetchImpl as unknown as typeof fetch,
      );
      expect(result).toEqual(schema);
      expect(fetchImpl).not.toHaveBeenCalled();
    });
  });
});
