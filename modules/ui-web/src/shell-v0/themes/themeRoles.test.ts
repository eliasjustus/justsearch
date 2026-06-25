/**
 * themeRoles — Tempdoc 567 §8 / A2 — role catalog integrity.
 */
import { describe, it, expect } from 'vitest';
import { ROLE_CATALOG, ROLE_FG_TOKEN_NAMES } from './themeRoles.js';
import { KNOWN_TOKEN_NAMES } from './designTokenTree.js';

describe('ROLE_CATALOG', () => {
  it('is non-empty; every bg + fg token is a known token; fg == accent-on-<id>', () => {
    expect(ROLE_CATALOG.length).toBeGreaterThan(0);
    for (const role of ROLE_CATALOG) {
      expect(KNOWN_TOKEN_NAMES.has(role.bgToken)).toBe(true);
      expect(KNOWN_TOKEN_NAMES.has(role.fgToken)).toBe(true);
      expect(role.fgToken).toBe(`accent-on-${role.id}`);
      expect(role.floor).toBeGreaterThan(1);
    }
  });

  it('ROLE_FG_TOKEN_NAMES is exactly the catalog fg tokens', () => {
    expect(ROLE_FG_TOKEN_NAMES.size).toBe(ROLE_CATALOG.length);
    for (const role of ROLE_CATALOG) expect(ROLE_FG_TOKEN_NAMES.has(role.fgToken)).toBe(true);
  });
});
