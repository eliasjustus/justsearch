/**
 * Provenance primitive unit tests — Tempdoc 543 §3.A + §13.2.3.1.
 */

import { describe, it, expect } from 'vitest';
import {
  CORE_PROVENANCE,
  makeCoreProvenance,
  makePluginProvenance,
  stampInstalledAt,
  isNonCore,
  displayTier,
} from './provenance.js';

describe('Provenance primitive (§3.A)', () => {
  it('CORE_PROVENANCE has tier=CORE, contributorId=core, version=0', () => {
    expect(CORE_PROVENANCE.tier).toBe('CORE');
    expect(CORE_PROVENANCE.contributorId).toBe('core');
    expect(CORE_PROVENANCE.version).toBe('0');
  });

  it('CORE_PROVENANCE is frozen (kernel primitive cannot mutate)', () => {
    expect(Object.isFrozen(CORE_PROVENANCE)).toBe(true);
  });

  it('makeCoreProvenance() returns the canonical CORE provenance', () => {
    const p = makeCoreProvenance();
    expect(p.tier).toBe('CORE');
    expect(p.contributorId).toBe('core');
  });

  it('makePluginProvenance defaults tier to TRUSTED_PLUGIN', () => {
    const p = makePluginProvenance('example-plugin', '1.2.3');
    expect(p.tier).toBe('TRUSTED_PLUGIN');
    expect(p.contributorId).toBe('example-plugin');
    expect(p.version).toBe('1.2.3');
  });

  it('makePluginProvenance accepts explicit UNTRUSTED_PLUGIN', () => {
    const p = makePluginProvenance('untrusted-plugin', '0.1.0', 'UNTRUSTED_PLUGIN');
    expect(p.tier).toBe('UNTRUSTED_PLUGIN');
  });

  it('makePluginProvenance result is frozen', () => {
    const p = makePluginProvenance('example-plugin', '1.0.0');
    expect(Object.isFrozen(p)).toBe(true);
  });

  it('isNonCore returns false for CORE, true for plugin tiers', () => {
    expect(isNonCore(CORE_PROVENANCE)).toBe(false);
    expect(isNonCore(makePluginProvenance('p', '1'))).toBe(true);
    expect(isNonCore(makePluginProvenance('p', '1', 'UNTRUSTED_PLUGIN'))).toBe(true);
  });
});

describe('Multi-axis Provenance (§13.2.3.1)', () => {
  it('CORE_PROVENANCE carries identity.verified=true', () => {
    expect(CORE_PROVENANCE.identity?.verified).toBe(true);
  });

  it('makePluginProvenance does NOT populate installedAt — that is the install site\'s job (§25.α7)', () => {
    const p = makePluginProvenance('p', '1.0.0');
    expect(p.installedAt).toBeUndefined();
  });

  it('stampInstalledAt adds an ISO timestamp without losing other fields (§25.α7)', () => {
    const base = makePluginProvenance('p', '1.0.0', 'TRUSTED_PLUGIN', {
      identity: { verified: true, signature: 'sig-abc' },
    });
    const stamped = stampInstalledAt(base);
    expect(stamped.installedAt).toBeDefined();
    expect(stamped.installedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(stamped.contributorId).toBe('p');
    expect(stamped.version).toBe('1.0.0');
    expect(stamped.tier).toBe('TRUSTED_PLUGIN');
    expect(stamped.identity?.verified).toBe(true);
    expect(stamped.identity?.signature).toBe('sig-abc');
  });

  it('makePluginProvenance accepts extras (identity / review / capability)', () => {
    const p = makePluginProvenance('p', '1.0.0', 'TRUSTED_PLUGIN', {
      identity: { verified: true, signature: 'sig-abc' },
      review: { lastReviewedAt: '2026-05-01T00:00:00Z', reviewer: 'team' },
      capability: ['filesystem.read', 'network.fetch'],
    });
    expect(p.identity?.verified).toBe(true);
    expect(p.identity?.signature).toBe('sig-abc');
    expect(p.review?.lastReviewedAt).toBe('2026-05-01T00:00:00Z');
    expect(p.capability).toEqual(['filesystem.read', 'network.fetch']);
  });

  it('displayTier — CORE → CORE', () => {
    expect(displayTier(CORE_PROVENANCE)).toBe('CORE');
  });

  it('displayTier — UNTRUSTED_PLUGIN → UNTRUSTED', () => {
    expect(
      displayTier(makePluginProvenance('p', '1', 'UNTRUSTED_PLUGIN')),
    ).toBe('UNTRUSTED');
  });

  it('displayTier — TRUSTED_PLUGIN without identity → TRUSTED', () => {
    expect(displayTier(makePluginProvenance('p', '1'))).toBe('TRUSTED');
  });

  it('displayTier — TRUSTED_PLUGIN with identity.verified → VERIFIED', () => {
    const p = makePluginProvenance('p', '1', 'TRUSTED_PLUGIN', {
      identity: { verified: true },
    });
    expect(displayTier(p)).toBe('VERIFIED');
  });
});

// §21.A2 (D5) — `resolveProvenance` retired; with `provenance` required
// on every contribution registry interface, callers read `.provenance`
// directly. Its 9 legacy-helper tests retire with it. The retirement is
// what enabled this whole describe block to go away.
