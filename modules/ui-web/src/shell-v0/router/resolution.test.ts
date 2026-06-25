import { describe, expect, it } from 'vitest';
import {
  damerauLevenshtein,
  resolveAgainstCatalog,
  type CatalogEntry,
  type AliasMap,
} from './resolution.js';

const SURFACES: CatalogEntry[] = [
  { id: 'core.library-surface', label: 'Library' },
  { id: 'core.health-surface', label: 'Health' },
  { id: 'core.search-surface', label: 'Search' },
  { id: 'core.brain-surface', label: 'Brain' },
  { id: 'core.settings-surface', label: 'Settings' },
  { id: 'core.browse-surface', label: 'Browse' },
  { id: 'core.help-surface', label: 'Help' },
  { id: 'core.logs-surface', label: 'Logs' },
  { id: 'core.ask-surface', label: 'Ask' },
  { id: 'core.unified-chat-surface', label: 'Unified Chat' },
  { id: 'core.free-chat-surface', label: 'Free Chat' },
  { id: 'core.extract-surface', label: 'Extract' },
  { id: 'core.activity-surface', label: 'Activity' },
];

describe('damerauLevenshtein', () => {
  it('identical → 0', () => expect(damerauLevenshtein('abc', 'abc')).toBe(0));
  it('insertion → 1', () => expect(damerauLevenshtein('abc', 'abcd')).toBe(1));
  it('deletion → 1', () => expect(damerauLevenshtein('abcd', 'abc')).toBe(1));
  it('substitution → 1', () => expect(damerauLevenshtein('abc', 'axc')).toBe(1));
  it('transposition → 1', () => expect(damerauLevenshtein('ab', 'ba')).toBe(1));
  it('empty vs non-empty', () => {
    expect(damerauLevenshtein('', 'abc')).toBe(3);
    expect(damerauLevenshtein('abc', '')).toBe(3);
  });
});

describe('resolveAgainstCatalog', () => {
  describe('exact match', () => {
    it('returns resolved for an exact ID', () => {
      const result = resolveAgainstCatalog('core.library-surface', SURFACES);
      expect(result).toEqual({ status: 'resolved', id: 'core.library-surface' });
    });
  });

  describe('alias redirect', () => {
    const aliases: AliasMap = {
      'core.library': { target: 'core.library-surface', reason: 'renamed' },
    };

    it('returns redirected for an aliased ID', () => {
      const result = resolveAgainstCatalog('core.library', SURFACES, aliases);
      expect(result).toEqual({
        status: 'redirected',
        id: 'core.library-surface',
        originalId: 'core.library',
        reason: 'renamed',
      });
    });

    it('falls through to fuzzy when alias target is not in catalog', () => {
      const badAlias: AliasMap = {
        'core.old': { target: 'core.nonexistent', reason: 'renamed' },
      };
      const result = resolveAgainstCatalog('core.old', SURFACES, badAlias);
      expect(result.status).toBe('unresolved');
    });
  });

  describe('fuzzy matching (typo)', () => {
    it('core.libary-surface → core.library-surface as top suggestion', () => {
      const result = resolveAgainstCatalog('core.libary-surface', SURFACES);
      expect(result.status).toBe('unresolved');
      if (result.status === 'unresolved') {
        expect(result.alternatives.length).toBeGreaterThan(0);
        expect(result.alternatives[0]!.id).toBe('core.library-surface');
        expect(result.diagnosis.mode).toBe('typo');
      }
    });

    it('core.serach-surface → core.search-surface', () => {
      const result = resolveAgainstCatalog('core.serach-surface', SURFACES);
      expect(result.status).toBe('unresolved');
      if (result.status === 'unresolved') {
        expect(result.alternatives[0]!.id).toBe('core.search-surface');
      }
    });
  });

  describe('prefix match (renamed/truncated)', () => {
    it('core.library → core.library-surface (prefix match)', () => {
      const result = resolveAgainstCatalog('core.library', SURFACES);
      expect(result.status).toBe('unresolved');
      if (result.status === 'unresolved') {
        expect(result.alternatives[0]!.id).toBe('core.library-surface');
      }
    });
  });

  describe('no match', () => {
    it('completely unrelated ID returns no alternatives', () => {
      const result = resolveAgainstCatalog('vendor.totally-different.thing', SURFACES);
      expect(result.status).toBe('unresolved');
      if (result.status === 'unresolved') {
        expect(result.alternatives).toHaveLength(0);
        expect(result.diagnosis.mode).toBe('unknown');
      }
    });
  });

  describe('edge cases', () => {
    it('empty catalog returns unresolved', () => {
      const result = resolveAgainstCatalog('core.x', []);
      expect(result.status).toBe('unresolved');
    });

    it('max 3 suggestions', () => {
      const result = resolveAgainstCatalog('core.surface', SURFACES);
      if (result.status === 'unresolved') {
        expect(result.alternatives.length).toBeLessThanOrEqual(3);
      }
    });
  });
});
