// @vitest-environment happy-dom

/**
 * Tempdoc 578 Workstream A — the standalone System Self-View ("Now") surface is retired; its
 * live-strip folded into Health. A deep-link to the retired id must redirect to the System hub (whose
 * first member, Health, opens by default — where the strip now lives), not dead-end. This pins that
 * `RETIRED_SURFACE_ALIASES` row, alongside the existing agent→unified-chat retirement.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { resolveSurface } from './catalogResolver.js';
import {
  __seedForTest,
  __resetForTest,
} from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

function surface(id: string): Surface {
  return {
    id,
    presentation: {
      labelKey: `registry-surface.${id.replace(/^core\./, '')}.label`,
      descriptionKey: `registry-surface.${id.replace(/^core\./, '')}.description`,
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    placement: 'RAIL',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: `jf-${id.replace(/^core\./, '').replace(/\./g, '-')}`,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
  };
}

function catalogOf(...entries: Surface[]): SurfaceCatalog {
  return { schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries };
}

describe('resolveSurface — retired System Self-View alias (578 Workstream A)', () => {
  beforeEach(() => __resetForTest());
  afterEach(() => __resetForTest());

  it('redirects core.system-self-view to the System hub', () => {
    __seedForTest(catalogOf(surface('core.system-surface'), surface('core.health-surface')));
    const r = resolveSurface('core.system-self-view');
    expect(r.status).toBe('redirected');
    if (r.status === 'redirected') {
      expect(r.id).toBe('core.system-surface');
      expect(r.originalId).toBe('core.system-self-view');
    }
  });

  it('still resolves a live surface to itself (no spurious redirect)', () => {
    __seedForTest(catalogOf(surface('core.system-surface')));
    const r = resolveSurface('core.system-surface');
    expect(r.status).toBe('resolved');
    if (r.status === 'resolved') expect(r.id).toBe('core.system-surface');
  });
});
