/**
 * Tests for the role-foreground co-projection (558 / 569 Move 3) — the pure core.
 */
import { describe, expect, it } from 'vitest';
import { deriveOnColorDecls, COLOR_ROLES } from './roleForegrounds.js';
import { ROLE_CATALOG } from './themeRoles.js';
import {
  parseColor,
  deriveForeground,
  relativeLuminance,
  contrastRatio,
  WCAG_AA,
} from './contrast.js';

describe('role foreground co-projection', () => {
  it('derives a readable, hue-bearing DARK on-colour for the bright accent (fixes white-on-bright)', () => {
    // Tempdoc 567 §8 (deferred → built): the on-colour is now a TINTED ink — a darkened version of the
    // fill's OWN hue — rather than flat black. It is still a DARK colour clearing the floor with room to
    // spare, so the white-on-bright defect (white fg ~1.9:1) can never render; it is just more designed.
    const bgRaw = 'rgb(0, 209, 178)';
    const decls = deriveOnColorDecls((bg) => (bg === 'accent-tint' ? bgRaw : ''));
    const decl = decls.find((d) => d.startsWith('--accent-on-tint:'));
    expect(decl).toBeTruthy();
    const fg = parseColor(decl!.replace('--accent-on-tint:', '').replace(';', '').trim());
    expect(fg).not.toBeNull();
    // DARK (low luminance) — the white-on-bright pair is structurally impossible.
    expect(relativeLuminance(fg!)).toBeLessThan(0.2);
    // Clears AA over the bright fill (correct-by-construction it is actually ≥ AAA where it tints).
    expect(contrastRatio(parseColor(bgRaw)!, fg!)).toBeGreaterThanOrEqual(WCAG_AA);
    // Hue-bearing (tinted), not pure black — the §8 designed look this test now locks in.
    expect(fg).not.toEqual([0, 0, 0]);
  });

  it('skips a role whose background does not resolve', () => {
    expect(deriveOnColorDecls(() => '')).toHaveLength(0);
  });

  it('every role declares a paired background + on-colour token', () => {
    for (const r of COLOR_ROLES) {
      expect(r.bg).toBeTruthy();
      expect(r.on).toMatch(/^accent-on-/);
    }
  });

  it('projects EVERY role from the single ROLE_CATALOG authority (574 §25 — no second list)', () => {
    // COLOR_ROLES is a projection of ROLE_CATALOG, so the deriver covers all six roles, not just one.
    expect(COLOR_ROLES.map((r) => r.on).sort()).toEqual(ROLE_CATALOG.map((r) => r.fgToken).sort());
    // 577 Phase 7 added the highlight + link roles (6 → 8).
    expect(COLOR_ROLES).toHaveLength(8);
    expect(COLOR_ROLES.map((r) => r.on)).toContain('accent-on-command');
    expect(COLOR_ROLES.map((r) => r.on)).toContain('accent-on-danger');
  });

  it('derives a floor-clearing on-colour for EVERY role over a representative bright accent', () => {
    // Representative bright accents (oklch ~70% L). The REAL oklch token values are resolved + checked
    // in the browser oracle (Phase 2) and the conformance gate; here we assert the derivation logic
    // clears each role's own floor given a bright fill (the maximal-contrast pole is black).
    const brightByRole: Record<string, string> = {
      command: 'rgb(170, 140, 240)',
      chat: 'rgb(40, 210, 190)',
      success: 'rgb(120, 210, 130)',
      warning: 'rgb(240, 195, 80)',
      danger: 'rgb(245, 140, 130)',
      tint: 'rgb(0, 209, 178)',
      // 577 Phase 7 — bright representatives for the new roles.
      highlight: 'rgb(240, 215, 140)',
      link: 'rgb(150, 185, 250)',
    };
    for (const role of COLOR_ROLES) {
      const bg = parseColor(brightByRole[role.name]!);
      expect(bg, role.name).toBeTruthy();
      const fg = deriveForeground(bg!, role.floor);
      expect(fg.meets, `${role.name}: ratio ${fg.ratio.toFixed(2)} < floor ${role.floor}`).toBe(true);
    }
  });
});
