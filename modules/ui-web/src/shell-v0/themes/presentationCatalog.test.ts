// @vitest-environment happy-dom

/**
 * Tests for the custom Presentation Declaration catalog (569 Move 1 — persisted, multi-origin
 * artifact). Save certifies before persisting; round-trips through UserStateDocument.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import {
  saveCustomPresentation,
  listCustomPresentations,
  getCustomPresentation,
  deleteCustomPresentation,
} from './presentationCatalog.js';
import { __resetUserStateForTest } from '../state/UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
});

const DECL = {
  schemaVersion: 1,
  id: 'user.my-skin',
  displayName: 'My Skin',
  theme: { tokens: { 'accent-tint': 'oklch(70% 0.1 200)' } },
};

describe('presentation catalog', () => {
  it('saves a certified declaration and lists it', () => {
    const r = saveCustomPresentation(DECL);
    expect(r.ok).toBe(true);
    expect(listCustomPresentations().map((p) => p.id)).toEqual(['user.my-skin']);
    expect(getCustomPresentation('user.my-skin')?.displayName).toBe('My Skin');
  });

  it('rejects an uncertified declaration (does not persist)', () => {
    const r = saveCustomPresentation({ ...DECL, theme: { tokens: { 'not-a-token': '#000' } } });
    expect(r.ok).toBe(false);
    expect(r.errors.length).toBeGreaterThan(0);
    expect(listCustomPresentations()).toHaveLength(0);
  });

  it('replaces by id (no duplicates)', () => {
    saveCustomPresentation(DECL);
    saveCustomPresentation({ ...DECL, displayName: 'Renamed' });
    expect(listCustomPresentations()).toHaveLength(1);
    expect(getCustomPresentation('user.my-skin')?.displayName).toBe('Renamed');
  });

  it('deletes by id', () => {
    saveCustomPresentation(DECL);
    deleteCustomPresentation('user.my-skin');
    expect(listCustomPresentations()).toHaveLength(0);
  });
});
