// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import './EffectLine.js';
import type { EffectLine } from './EffectLine.js';
import type { Effect } from '../substrates/effect.js';

async function mount(props: Partial<EffectLine>): Promise<EffectLine> {
  const el = document.createElement('jf-effect-line') as EffectLine;
  for (const [k, v] of Object.entries(props)) {
    (el as unknown as Record<string, unknown>)[k] = v;
  }
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('EffectLine (jf-effect-line)', () => {
  it('renders a humanized navigate label, not the raw kind or raw route (557 Q7)', async () => {
    const el = await mount({
      effect: { kind: 'navigate', to: 'justsearch://surface/core.search' } as Effect,
    });
    const label = el.shadowRoot?.querySelector('[data-testid="effect-label"]');
    // The target is humanized to the surface label (id-derived "Search" with no
    // catalog) — never the raw justsearch:// route (the Q7 leak).
    expect(label?.textContent).toContain('Navigate to');
    expect(label?.textContent).toContain('Search');
    expect(label?.textContent).not.toContain('justsearch://');
    el.remove();
  });

  it('hides the structured detail unless showDetail is set', async () => {
    const el = await mount({
      effect: { kind: 'invoke-operation', operationId: 'core_browse_folders' } as Effect,
    });
    expect(el.shadowRoot?.querySelector('details')).toBeNull();
    el.remove();
  });

  it('shows a structured key/value detail (no raw JSON blob) when showDetail', async () => {
    const el = await mount({
      effect: {
        kind: 'invoke-operation',
        operationId: 'core_browse_folders',
        args: { parent_path: 'slices' },
      } as Effect,
      showDetail: true,
    });
    const details = el.shadowRoot?.querySelector('details');
    expect(details).not.toBeNull();
    const dts = Array.from(el.shadowRoot?.querySelectorAll('dt') ?? []).map(
      (n) => n.textContent,
    );
    // Fields surface as labeled rows, not a JSON.stringify dump.
    expect(dts).toContain('operationId');
    expect(dts).toContain('args');
    el.remove();
  });

  it('renders no detail for a fieldless effect even with showDetail', async () => {
    const el = await mount({ effect: { kind: 'noop' } as Effect, showDetail: true });
    expect(el.shadowRoot?.querySelector('details')).toBeNull();
    el.remove();
  });

  it('renders a relative <time> with absolute title when timestamp is set', async () => {
    const iso = new Date(Date.now() - 5 * 60_000).toISOString();
    const el = await mount({
      effect: { kind: 'noop' } as Effect,
      timestamp: iso,
    });
    const time = el.shadowRoot?.querySelector('time');
    expect(time?.getAttribute('datetime')).toBe(iso);
    expect(time?.textContent).toMatch(/ago|just now/);
    expect(time?.getAttribute('title')).toBeTruthy();
    el.remove();
  });
});
