// @vitest-environment happy-dom

/**
 * Render tests for DeclaredSurface (tempdoc 569 Move 3). Proves a real surface
 * region (Settings → Appearance) is a projection of a typed declaration rendered
 * through the EXISTING engine (createChildRenderer), with the invariant facets
 * (ARIA landmark, heading) CO-PROJECTED — not authored.
 */

import { describe, expect, it } from 'vitest';
import './DeclaredSurface.js';
// Side-effect imports for the renderers the declaration will dispatch:
import '../renderers/controls/TextControl.js';
import '../renderers/controls/EnumControl.js';
import '../renderers/controls/BooleanControl.js';
import '../renderers/layouts/VerticalLayout.js';
import type {
  DeclaredSurface,
  SurfaceBodyDeclaration,
  SurfaceChangeEventDetail,
} from './DeclaredSurface.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';

async function mount(props: Partial<DeclaredSurface>): Promise<DeclaredSurface> {
  const el = document.createElement('jf-declared-surface') as DeclaredSurface;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

/** The real Settings → Appearance region, expressed as a declaration. */
const APPEARANCE: SurfaceBodyDeclaration = {
  schema: {
    type: 'object',
    properties: {
      theme: { type: 'string', enum: ['system', 'dark', 'light'] },
      highContrast: { type: 'boolean' },
    },
  } as JsonSchema,
  uischema: {
    type: 'VerticalLayout',
    elements: [
      { type: 'Control', scope: '#/properties/theme' },
      { type: 'Control', scope: '#/properties/highContrast' },
    ],
  } as UISchemaElement,
  heading: 'Appearance',
  placement: 'STAGE',
};

describe('DeclaredSurface — body as a projection of a declaration', () => {
  it('renders a real surface region (Appearance) from a declaration via the engine', async () => {
    const el = await mount({
      declaration: APPEARANCE,
      data: { theme: 'dark', highContrast: false },
    });
    expect(el.shadowRoot?.querySelector('jf-vertical-layout')).not.toBeNull();
    el.remove();
  });

  it('co-projects the ARIA landmark role from the declared placement (not authored)', async () => {
    const el = await mount({ declaration: APPEARANCE, data: {} });
    // 578 Workstream B: the engine renders NESTED inside the shell STAGE (already role="main"), so it
    // uses nestedLandmarkRole — STAGE -> a named 'region', NEVER a second 'main' (axe duplicate-main).
    expect(el.shadowRoot?.querySelector('section[role="main"]')).toBeNull();
    const region = el.shadowRoot?.querySelector('section[role="region"]');
    expect(region).not.toBeNull();
    expect(region?.getAttribute('aria-label')).toBe('Appearance');
    el.remove();
  });

  it('renders the declared heading', async () => {
    const el = await mount({ declaration: APPEARANCE, data: {} });
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toMatch(/Appearance/);
    el.remove();
  });

  it('569 §14 — co-projects the liveness readout when the body names a signal (not authored state)', async () => {
    const el = await mount({
      declaration: { ...APPEARANCE, liveness: 'core.retrieval' },
      data: {},
    });
    // The engine mounts the readout; the author declared only the signal id, never the state.
    expect(el.shadowRoot?.querySelector('jf-liveness-readout')).not.toBeNull();
    // A body without the facet does NOT get a readout (opt-in).
    const plain = await mount({ declaration: APPEARANCE, data: {} });
    expect(plain.shadowRoot?.querySelector('jf-liveness-readout')).toBeNull();
    el.remove();
    plain.remove();
  });

  it('569 §14 — co-projects the overflow strip from declared priority items (engine owns the clip)', async () => {
    const el = await mount({
      declaration: {
        ...APPEARANCE,
        overflow: [
          { id: 'a', label: 'Indexed', priority: 100, pinned: true },
          { id: 'b', label: 'Queue', priority: 50 },
          { id: 'c', label: 'GPU', priority: 20 },
        ],
      },
      data: {},
    });
    const items = el.shadowRoot?.querySelectorAll('.adaptive-strip .item');
    // Items render in priority order (highest first); the engine owns hide/clip, not the author.
    expect(items?.length).toBe(3);
    expect(items?.[0]?.textContent?.trim()).toBe('Indexed');
    expect(items?.[0]?.getAttribute('data-pinned')).toBe('true');
    el.remove();
  });

  it('emits surface-change with updated data on child input', async () => {
    const events: SurfaceChangeEventDetail[] = [];
    const decl: SurfaceBodyDeclaration = {
      schema: {
        type: 'object',
        properties: { name: { type: 'string' } },
      } as JsonSchema,
      uischema: {
        type: 'VerticalLayout',
        elements: [{ type: 'Control', scope: '#/properties/name' }],
      } as UISchemaElement,
      heading: 'Test',
      placement: 'DRAWER',
    };
    const el = await mount({ declaration: decl, data: { name: 'Ada' } });
    el.addEventListener('surface-change', ((e: CustomEvent) => {
      events.push(e.detail as SurfaceChangeEventDetail);
    }) as EventListener);

    const layout = el.shadowRoot?.querySelector(
      'jf-vertical-layout',
    ) as HTMLElement;
    const input = layout?.shadowRoot
      ?.querySelector('jf-text-control')
      ?.shadowRoot?.querySelector('input') as HTMLInputElement | undefined;
    expect(input).toBeDefined();
    if (!input) {
      el.remove();
      return;
    }
    input.value = 'Grace';
    input.dispatchEvent(new Event('input'));

    expect(events).toHaveLength(1);
    expect(events[0]?.path).toBe('name');
    expect(events[0]?.data).toEqual({ name: 'Grace' });
    el.remove();
  });

  it('shows a fallback when no renderer matches the root uischema', async () => {
    const el = await mount({
      declaration: {
        schema: {},
        uischema: { type: 'UnknownRoot' } as UISchemaElement,
      },
      data: {},
    });
    expect(el.shadowRoot?.textContent).toMatch(/no renderer/i);
    el.remove();
  });
});
