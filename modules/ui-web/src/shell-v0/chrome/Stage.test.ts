// @vitest-environment happy-dom

/**
 * Tests for slice 471's Stage surface-override dispatch.
 *
 * Covers the resolveSurface() decision logic via render output:
 *  - no override → mounts the rail-selected surface's tag
 *  - override pointing to a surface in the catalog → mounts the
 *    override target's tag
 *  - override pointing to a surface NOT in the catalog → graceful
 *    fallback to the original surface's tag
 *
 * The chrome `<jf-shell>` integration (subscribing userConfig +
 * threading to <jf-stage>) is covered indirectly: this test
 * exercises Stage's properties API directly.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LitElement } from 'lit';
import '../chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
  getSurface,
} from '../../api/registry/SurfaceCatalogClient.js';
import type { Surface, SurfaceCatalog } from '../../api/types/surface.js';

// Stand-in custom elements registered just for the test, so the
// Stage's `customElements.get(tag)` check passes for both core
// and "plugin" tags. Each renders a unique sentinel string we can
// assert on.
class StubLibrarySurface extends LitElement {
  override createRenderRoot(): HTMLElement {
    return this;
  }
  override render(): string {
    return 'STUB:CORE-LIBRARY';
  }
}
class StubAltLibrarySurface extends LitElement {
  override createRenderRoot(): HTMLElement {
    return this;
  }
  override render(): string {
    return 'STUB:ALT-LIBRARY';
  }
}

if (!customElements.get('test-stub-library-surface')) {
  customElements.define('test-stub-library-surface', StubLibrarySurface);
}
if (!customElements.get('test-stub-alt-library-surface')) {
  customElements.define(
    'test-stub-alt-library-surface',
    StubAltLibrarySurface,
  );
}

const CORE_SURFACE: Surface = {
  id: 'core.library-surface',
  presentation: {
    labelKey: 'registry-surface.library-surface.label',
    descriptionKey: 'registry-surface.library-surface.description',
  },
  audience: 'USER',
  placement: 'RAIL',
  consumes: {
    operations: [],
    resources: [],
    prompts: [],
    diagnosticChannels: [],
  },
  mountTag: 'test-stub-library-surface',
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
};
const ALT_SURFACE: Surface = {
  id: 'acme.alt-library-surface',
  presentation: {
    labelKey: 'acme.alt-library-surface.label',
    descriptionKey: 'acme.alt-library-surface.description',
  },
  audience: 'USER',
  placement: 'RAIL',
  consumes: {
    operations: [],
    resources: [],
    prompts: [],
    diagnosticChannels: [],
  },
  mountTag: 'test-stub-alt-library-surface',
  provenance: { tier: 'TRUSTED_PLUGIN', contributorId: 'acme', version: '0.1.0' },
};

function makeCatalog(entries: Surface[]): SurfaceCatalog {
  return {
    schemaVersion: '1.0.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Surface',
    entries,
  };
}

function seedBoth(): void {
  seedSurfaceCatalog(makeCatalog([CORE_SURFACE, ALT_SURFACE]));
}

function seedCoreOnly(): void {
  seedSurfaceCatalog(makeCatalog([CORE_SURFACE]));
}

interface StageElement extends HTMLElement {
  surface: Surface | null;
  apiBase: string;
  hostApi_: unknown;
  userConfig?: { surfaceOverride?: Record<string, string> };
  updateComplete: Promise<void>;
}

async function renderStage(
  surface: Surface,
  userConfig?: { surfaceOverride?: Record<string, string> },
): Promise<StageElement> {
  const stage = document.createElement('jf-stage') as StageElement;
  stage.surface = surface;
  stage.apiBase = '';
  if (userConfig) stage.userConfig = userConfig;
  document.body.appendChild(stage);
  await stage.updateComplete;
  return stage;
}

describe('Stage — surface override dispatch (slice 471)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
  });

  afterEach(() => {
    document.querySelectorAll('jf-stage').forEach((el) => el.remove());
    resetSurfaceCatalog();
  });

  it('mounts the rail-selected surface when no override is set', async () => {
    seedBoth();
    const stage = await renderStage(CORE_SURFACE);
    const mounted = stage.shadowRoot?.querySelector(
      'test-stub-library-surface',
    );
    expect(mounted).not.toBeNull();
  });

  it('mounts the override target when surfaceOverride resolves in the catalog', async () => {
    seedBoth();
    const stage = await renderStage(CORE_SURFACE, {
      surfaceOverride: { 'core.library-surface': 'acme.alt-library-surface' },
    });
    const mounted = stage.shadowRoot?.querySelector(
      'test-stub-alt-library-surface',
    );
    expect(mounted).not.toBeNull();
    // The original core surface element must NOT be mounted.
    const original = stage.shadowRoot?.querySelector(
      'test-stub-library-surface',
    );
    expect(original).toBeNull();
  });

  it('falls back to the original surface when the override target is missing from the catalog', async () => {
    // Only the core surface is in the catalog; the override target
    // is not present (e.g., the plugin was uninstalled).
    seedCoreOnly();
    const stage = await renderStage(CORE_SURFACE, {
      surfaceOverride: { 'core.library-surface': 'acme.alt-library-surface' },
    });
    // Graceful fallback per slice 471: original core surface mounts.
    const mounted = stage.shadowRoot?.querySelector(
      'test-stub-library-surface',
    );
    expect(mounted).not.toBeNull();
  });

  it('renders empty state when no surface is provided', async () => {
    const stage = document.createElement('jf-stage') as StageElement;
    stage.surface = null;
    stage.apiBase = '';
    document.body.appendChild(stage);
    await stage.updateComplete;
    const empty = stage.shadowRoot?.querySelector('.empty');
    expect(empty?.textContent).toContain('No surface selected');
  });

  it('renders the not-registered hint when mountTag is unknown', async () => {
    seedBoth();
    const ghost: Surface = {
      ...CORE_SURFACE,
      mountTag: 'test-stub-ghost-not-registered',
    };
    const stage = await renderStage(ghost);
    const empty = stage.shadowRoot?.querySelector('.empty');
    expect(empty?.textContent).toContain('not registered');
  });
});

describe('Stage — instance retention (tempdoc 609)', () => {
  beforeEach(() => {
    resetSurfaceCatalog();
  });

  afterEach(() => {
    document.querySelectorAll('jf-stage').forEach((el) => el.remove());
    resetSurfaceCatalog();
  });

  // The retention cache only engages on the preferred `mountSurface` (factory) dispatch path, which is
  // what production surfaces use. The raw CORE_SURFACE/ALT_SURFACE literals carry no factory, so we
  // resolve the catalog-STAMPED entries (which `getSurface` returns after seeding) to exercise it.
  const stamped = (id: string): Surface => {
    const s = getSurface(id);
    if (!s) throw new Error(`stamped surface not found: ${id}`);
    return s;
  };

  it('reuses the same surface element across a non-navigation re-render', async () => {
    seedBoth();
    const stage = await renderStage(stamped(CORE_SURFACE.id));
    const first = stage.shadowRoot!.querySelector('test-stub-library-surface');
    expect(first).not.toBeNull();

    // Simulate the AI warming up: a reactive-property change re-renders the
    // stage without any navigation. Pre-609 this minted a fresh element and Lit
    // swapped it in — remounting the active surface for no reason.
    (stage as unknown as { aiAvailable: boolean }).aiAvailable = false;
    await stage.updateComplete;
    const second = stage.shadowRoot!.querySelector('test-stub-library-surface');

    expect(second).toBe(first); // same instance — the surface was NOT remounted
  });

  it('refreshes host/API dependencies on a retained surface element', async () => {
    seedBoth();
    const stage = await renderStage(stamped(CORE_SURFACE.id));
    const mounted = stage.shadowRoot!.querySelector('test-stub-library-surface') as
      | (HTMLElement & { host_?: unknown })
      | null;
    expect(mounted).not.toBeNull();
    expect(mounted!.host_).toBeUndefined();

    const host = { marker: 'core-host' };
    stage.hostApi_ = host;
    stage.apiBase = 'http://127.0.0.1:12345';
    await stage.updateComplete;

    const retained = stage.shadowRoot!.querySelector('test-stub-library-surface') as
      | (HTMLElement & { host_?: unknown })
      | null;
    expect(retained).toBe(mounted);
    expect(retained!.host_).toBe(host);
    expect(retained!.getAttribute('api-base')).toBe('http://127.0.0.1:12345');
  });

  it('mounts the target on navigation and removes the previous surface from the DOM', async () => {
    seedBoth();
    const stage = await renderStage(stamped(CORE_SURFACE.id));
    expect(stage.shadowRoot!.querySelector('test-stub-library-surface')).not.toBeNull();

    stage.surface = stamped(ALT_SURFACE.id);
    await stage.updateComplete;

    // The previous surface leaves the DOM (→ disconnectedCallback, streams torn down) and the target
    // mounts. The previous instance is RETAINED off-DOM (see the round-trip test below), not destroyed.
    expect(stage.shadowRoot!.querySelector('test-stub-library-surface')).toBeNull();
    expect(stage.shadowRoot!.querySelector('test-stub-alt-library-surface')).not.toBeNull();
  });

  it('returns the SAME retained instance when navigating away and back (A→B→A)', async () => {
    seedBoth();
    const stage = await renderStage(stamped(CORE_SURFACE.id));
    const aFirst = stage.shadowRoot!.querySelector('test-stub-library-surface');
    expect(aFirst).not.toBeNull();
    // Tag the instance so we can prove identity across the round-trip (a fresh mint would lose this).
    (aFirst as unknown as { __retentionProbe?: string }).__retentionProbe = 'kept';

    stage.surface = stamped(ALT_SURFACE.id);
    await stage.updateComplete;
    expect(stage.shadowRoot!.querySelector('test-stub-library-surface')).toBeNull();

    stage.surface = stamped(CORE_SURFACE.id);
    await stage.updateComplete;
    const aAgain = stage.shadowRoot!.querySelector('test-stub-library-surface');

    // Instance-retention: the SAME element instance comes back (its @state would survive with it),
    // not a freshly-minted one — the class-wide 609 retention guarantee.
    expect(aAgain).toBe(aFirst);
    expect((aAgain as unknown as { __retentionProbe?: string }).__retentionProbe).toBe('kept');
  });

  it('does not prune the cache on navigation (retained instance count grows with surfaces visited)', async () => {
    seedBoth();
    const stage = await renderStage(stamped(CORE_SURFACE.id));
    const cache = (stage as unknown as { _surfaceElCache: Map<string, HTMLElement> })._surfaceElCache;
    expect(cache.size).toBe(1);

    stage.surface = stamped(ALT_SURFACE.id);
    await stage.updateComplete;
    // Both visited surfaces are retained — the off-DOM one is NOT evicted.
    expect(cache.size).toBe(2);
    expect(cache.has(CORE_SURFACE.id)).toBe(true);
    expect(cache.has(ALT_SURFACE.id)).toBe(true);
  });

  it('LRU-evicts the least-recently-used DORMANT surface beyond MAX_RETAINED (§R P1 backstop)', async () => {
    const THIRD: Surface = { ...CORE_SURFACE, id: 'core.third-surface' };
    seedSurfaceCatalog(makeCatalog([CORE_SURFACE, ALT_SURFACE, THIRD]));
    const StageClass = customElements.get('jf-stage') as unknown as { MAX_RETAINED: number };
    const prevCap = StageClass.MAX_RETAINED;
    StageClass.MAX_RETAINED = 2; // lower the cap so 3 surfaces trip the backstop
    try {
      const stage = await renderStage(stamped(CORE_SURFACE.id)); // visit A (active)
      const cache = (stage as unknown as { _surfaceElCache: Map<string, HTMLElement> })._surfaceElCache;

      stage.surface = stamped(ALT_SURFACE.id); // visit B → A now dormant (detached)
      await stage.updateComplete;
      expect(cache.size).toBe(2); // still under the cap

      stage.surface = stamped('core.third-surface'); // visit C → over cap → evict LRU dormant
      await stage.updateComplete;

      expect(cache.size).toBe(2); // bounded at the cap, not 3
      expect(cache.has(CORE_SURFACE.id)).toBe(false); // A = least-recently-used dormant → evicted
      expect(cache.has('core.third-surface')).toBe(true); // C = active, never evicted
    } finally {
      StageClass.MAX_RETAINED = prevCap; // don't leak the lowered cap into other tests
    }
  });
});

describe('Stage — split mode (tempdoc 521 §22 Phase A.2)', () => {
  beforeEach(() => {
    seedBoth();
  });
  afterEach(() => {
    resetSurfaceCatalog();
    document.body.innerHTML = '';
  });

  it('renders two panes with picker when secondarySurface + splitAxis are set', async () => {
    const stage = document.createElement('jf-stage') as StageElement & {
      secondarySurface?: Surface | null;
      splitAxis?: 'horizontal' | 'vertical' | null;
    };
    stage.surface = CORE_SURFACE;
    stage.secondarySurface = ALT_SURFACE;
    stage.splitAxis = 'horizontal';
    stage.apiBase = '';
    document.body.appendChild(stage);
    await stage.updateComplete;
    const split = stage.shadowRoot?.querySelector('.split');
    expect(split).not.toBeNull();
    expect(split?.classList.contains('horizontal')).toBe(true);
    const panes = stage.shadowRoot?.querySelectorAll('.pane');
    expect(panes?.length).toBe(2);
    const picker = stage.shadowRoot?.querySelector('jf-pane-picker');
    expect(picker).not.toBeNull();
  });

  it('honors the vertical splitAxis', async () => {
    const stage = document.createElement('jf-stage') as StageElement & {
      secondarySurface?: Surface | null;
      splitAxis?: 'horizontal' | 'vertical' | null;
    };
    stage.surface = CORE_SURFACE;
    stage.secondarySurface = ALT_SURFACE;
    stage.splitAxis = 'vertical';
    stage.apiBase = '';
    document.body.appendChild(stage);
    await stage.updateComplete;
    const split = stage.shadowRoot?.querySelector('.split');
    expect(split?.classList.contains('vertical')).toBe(true);
  });

  it('falls back to single-surface render when secondarySurface is missing', async () => {
    const stage = document.createElement('jf-stage') as StageElement & {
      secondarySurface?: Surface | null;
      splitAxis?: 'horizontal' | 'vertical' | null;
    };
    stage.surface = CORE_SURFACE;
    stage.secondarySurface = null;
    stage.splitAxis = 'horizontal';
    stage.apiBase = '';
    document.body.appendChild(stage);
    await stage.updateComplete;
    const split = stage.shadowRoot?.querySelector('.split');
    expect(split).toBeNull();
  });

  it('falls back to single-surface render when splitAxis is null', async () => {
    const stage = document.createElement('jf-stage') as StageElement & {
      secondarySurface?: Surface | null;
      splitAxis?: 'horizontal' | 'vertical' | null;
    };
    stage.surface = CORE_SURFACE;
    stage.secondarySurface = ALT_SURFACE;
    stage.splitAxis = null;
    stage.apiBase = '';
    document.body.appendChild(stage);
    await stage.updateComplete;
    const split = stage.shadowRoot?.querySelector('.split');
    expect(split).toBeNull();
  });
});
