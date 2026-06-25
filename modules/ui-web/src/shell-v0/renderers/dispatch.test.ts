/**
 * Tests for userConfig.rendererOverride threading through
 * dispatchRenderer (slice 3a.1.7).
 *
 * Resolution order:
 *  1. `userConfig.rendererOverride['renderer:<scope>']` — per-scope
 *  2. `userConfig.rendererOverride['renderer:']` — surface-wide
 *  3. Fallback to rank-based dispatch over the registry.
 */

import { describe, expect, it } from 'vitest';
import { dispatchRenderer } from './registry.js';
import type { RendererUserConfig } from './userConfig.js';
// Side-effect imports so the registry actually has matchable testers.
import './controls/TextControl.js';
import './controls/NumberControl.js';

describe('dispatchRenderer userConfig.rendererOverride (3a.1.7)', () => {
  it('returns null when no userConfig and no tester matches', () => {
    expect(
      dispatchRenderer(
        { type: 'UnknownThing' } as { type: 'UnknownThing' } as never,
        { type: 'string' },
      ),
    ).toBeNull();
  });

  it('falls through to rank-based dispatch when userConfig is undefined', () => {
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
    );
    expect(tag).toBe('jf-text-control');
  });

  it('honors per-scope rendererOverride in preference to rank-based dispatch', () => {
    const userConfig: RendererUserConfig = {
      version: 1,
      rendererOverride: {
        'renderer:#/properties/x': 'plugin-custom-text',
      },
    };
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
      userConfig,
    );
    expect(tag).toBe('plugin-custom-text');
  });

  it('does not honor a per-scope override for a different scope', () => {
    const userConfig: RendererUserConfig = {
      version: 1,
      rendererOverride: {
        'renderer:#/properties/y': 'plugin-custom-text',
      },
    };
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
      userConfig,
    );
    expect(tag).toBe('jf-text-control');
  });

  it('honors surface-wide rendererOverride when no per-scope match', () => {
    const userConfig: RendererUserConfig = {
      version: 1,
      rendererOverride: { 'renderer:': 'plugin-fallback' },
    };
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
      userConfig,
    );
    expect(tag).toBe('plugin-fallback');
  });

  it('per-scope override wins over surface-wide override', () => {
    const userConfig: RendererUserConfig = {
      version: 1,
      rendererOverride: {
        'renderer:#/properties/x': 'plugin-specific',
        'renderer:': 'plugin-fallback',
      },
    };
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
      userConfig,
    );
    expect(tag).toBe('plugin-specific');
  });

  it('does not validate that the override tag is registered', () => {
    // The dispatcher trusts the user config; the host is responsible
    // for surfacing "renderer not available" UX. Document via test.
    const userConfig: RendererUserConfig = {
      version: 1,
      rendererOverride: { 'renderer:': 'plugin-not-installed' },
    };
    const tag = dispatchRenderer(
      { type: 'Control', scope: '#/properties/x' } as never,
      { type: 'string' },
      userConfig,
    );
    expect(tag).toBe('plugin-not-installed');
  });
});
