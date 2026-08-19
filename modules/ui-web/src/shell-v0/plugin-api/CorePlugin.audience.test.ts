// @vitest-environment happy-dom
/**
 * Tempdoc 857 D3 (drafted as 854; renumbered — see docs/tempdocs/857-ratified-batch.md's status
 * header) — Health and Activity become USER-visible in the FE registration, matching the
 * Java catalog's existing `USER` declaration (the `check-surface-composition.mjs` parity gate is
 * the build-time enforcement; this pins the same fact as a fast, code-level test). Logs is
 * deliberately left OPERATOR on both sides — the wrong-line trap this batch names explicitly, so a
 * regression there is caught here too, not only by re-reading the source.
 */
import { describe, it, expect } from 'vitest';
import { createCorePluginManifest } from './CorePlugin.js';

const coreSurfaces = createCorePluginManifest().capabilities.surfaces ?? [];

function audienceOf(id: string): string | undefined {
  return coreSurfaces.find((s) => s.id === id)?.audience;
}

describe('CorePlugin surface audience (857 D3)', () => {
  it('declares core.health-surface USER-visible', () => {
    expect(audienceOf('core.health-surface')).toBe('USER');
  });

  it('declares core.activity-surface USER-visible', () => {
    expect(audienceOf('core.activity-surface')).toBe('USER');
  });

  it('leaves core.logs-surface OPERATOR — the wrong-line trap this batch must not touch', () => {
    expect(audienceOf('core.logs-surface')).toBe('OPERATOR');
  });
});
