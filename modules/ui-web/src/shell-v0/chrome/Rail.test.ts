// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './Shell.js'; // side-effect: registers <jf-rail>
import type { Rail } from './Shell.js';
import type { Surface, Altitude } from '../../api/types/surface.js';

/** Minimal Surface fixture — only id + altitude drive the rail's band projection. */
function surface(id: string, altitude?: Altitude): Surface {
  return {
    id,
    presentation: { labelKey: 'x', descriptionKey: 'y' },
    audience: 'USER',
    placement: 'RAIL',
    consumes: { resources: [], operations: [], prompts: [], diagnosticChannels: [] },
    mountTag: `jf-${id}`,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    ...(altitude ? { altitude } : {}),
  } as unknown as Surface;
}

function ids(nodes: Element[]): (string | null)[] {
  return nodes.map((n) => n.getAttribute('data-surface-id'));
}

describe('Rail — altitude-projected homing (tempdoc 571)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('bands DIAGNOSTIC surfaces (Logs + Health) together, below the product surfaces', async () => {
    const el = document.createElement('jf-rail') as Rail;
    el.surfaces = [
      surface('core.unified-chat-surface'), // PRODUCT (default)
      surface('core.activity-surface', 'TRUST'),
      surface('core.health-surface', 'DIAGNOSTIC'),
      surface('core.logs-surface', 'DIAGNOSTIC'),
      surface('core.help-surface'), // bottom-pinned
    ];
    document.body.appendChild(el);
    await el.updateComplete;

    const band = el.shadowRoot!.querySelector('[data-testid="rail-band-diagnostics"]')!;
    expect(band).not.toBeNull();
    expect(ids([...band.querySelectorAll('button')])).toEqual([
      'core.health-surface',
      'core.logs-surface',
    ]);
  });

  it('keeps the TRUST Activity surface first-class in the product band (NOT in diagnostics)', async () => {
    const el = document.createElement('jf-rail') as Rail;
    el.surfaces = [
      surface('core.unified-chat-surface'),
      surface('core.activity-surface', 'TRUST'),
      surface('core.logs-surface', 'DIAGNOSTIC'),
    ];
    document.body.appendChild(el);
    await el.updateComplete;

    const band = el.shadowRoot!.querySelector('[data-testid="rail-band-diagnostics"]')!;
    const activityBtn = [...el.shadowRoot!.querySelectorAll('button')].find(
      (b) => b.getAttribute('data-surface-id') === 'core.activity-surface',
    )!;
    expect(activityBtn).toBeTruthy();
    expect(band.contains(activityBtn)).toBe(false);
    expect(activityBtn.getAttribute('data-altitude')).toBe('TRUST');
  });

  it('renders no diagnostics band when no surface is DIAGNOSTIC altitude', async () => {
    const el = document.createElement('jf-rail') as Rail;
    el.surfaces = [surface('core.unified-chat-surface'), surface('core.search-surface')];
    document.body.appendChild(el);
    await el.updateComplete;

    expect(el.shadowRoot!.querySelector('[data-testid="rail-band-diagnostics"]')).toBeNull();
  });

  it('§6 — DERIVES TRUST position adjacent to the interaction surface (not catalog placement)', async () => {
    const el = document.createElement('jf-rail') as Rail;
    // Input order puts Activity (TRUST) LAST — far from chat. The rail must re-home it adjacent to the
    // interaction surface by construction (derived from altitude), regardless of incoming order.
    el.surfaces = [
      surface('core.library-surface'), // PRODUCT
      surface('core.unified-chat-surface'), // the interaction surface
      surface('core.browse-surface'), // PRODUCT
      surface('core.search-surface'), // PRODUCT
      surface('core.activity-surface', 'TRUST'), // declared last
    ];
    document.body.appendChild(el);
    await el.updateComplete;

    const productIds = ids(
      [...el.shadowRoot!.querySelectorAll('button')].filter(
        (b) => !el.shadowRoot!.querySelector('[data-testid="rail-band-diagnostics"]')?.contains(b),
      ),
    );
    const chatIdx = productIds.indexOf('core.unified-chat-surface');
    // Activity is rendered IMMEDIATELY after the interaction surface, derived from TRUST altitude.
    expect(productIds[chatIdx + 1]).toBe('core.activity-surface');
  });
});
