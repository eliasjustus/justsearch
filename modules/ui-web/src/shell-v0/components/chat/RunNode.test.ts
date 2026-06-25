/**
 * @vitest-environment happy-dom
 *
 * Tempdoc 565 §19 — RunNode density-adaptive render. In happy-dom there is no ResizeObserver, so the
 * DensityController never recomputes and sits at its construction default (`full`). That is precisely
 * the FIRST-PAINT condition §19.I-R1 hardens: the render must cap its effective density by the DECLARED
 * ceiling (`minDensity(this.density, controller.density)`) so a `minimal`-declared gutter node renders a
 * dot from frame 0 — never a one-frame flash of a decisive glyph char that will collapse. These tests
 * pin that cap + the decisive-glyph→dot collapse mapping.
 */
import { describe, expect, it } from 'vitest';
import { RunNode } from './RunNode.js';
import './RunNode.js';
import type { StepPresentation } from '../../views/runStepPresentation.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

// A plain descriptor — the brand is compile-time only; the render reads glyph/tone/live.
const present = (glyph: string): StepPresentation =>
  ({ tone: 'positive', glyph, label: 'x', prominence: 'primary', live: false }) as unknown as StepPresentation;

async function mount(density: string | null, glyph: string): Promise<RunNode> {
  const el = document.createElement('jf-run-node') as RunNode;
  if (density !== null) el.setAttribute('density', density);
  el.presentation = present(glyph);
  document.body.appendChild(el);
  await settle(el);
  return el;
}

const span = (el: RunNode) => el.shadowRoot!.querySelector('.g') as HTMLElement;

describe('RunNode density-adaptive render (§19 / §19.I-R1)', () => {
  it('R1 — a `minimal`-declared node collapses a decisive glyph to a tone dot WITHOUT measurement', async () => {
    // No ResizeObserver in happy-dom → controller stays at its `full` default; the render must STILL
    // cap to the declared `minimal` ceiling (R1) and draw a dot, not the ✓ char.
    const el = await mount('minimal', 'done');
    const g = span(el);
    expect(g.className.trim()).toBe('g g-dot');
    expect(g.textContent!.trim()).toBe('');
  });

  it('a `full`-declared node renders the decisive glyph char (the ✓)', async () => {
    const el = await mount('full', 'done');
    const g = span(el);
    expect(g.className.trim()).toBe('g g-done');
    expect(g.textContent!.trim()).toBe('✓');
  });

  it('a `minimal`-declared node keeps the %-sized SHAPE glyphs (pending ring) — they scale fine', async () => {
    const el = await mount('minimal', 'pending');
    const g = span(el);
    expect(g.className.trim()).toContain('g-pending');
    expect(g.textContent!.trim()).toBe(''); // pending is a CSS ring, never a char
  });

  it('renders nothing without a presentation', async () => {
    const el = document.createElement('jf-run-node') as RunNode;
    document.body.appendChild(el);
    await settle(el);
    expect(el.shadowRoot!.querySelector('.g')).toBeNull();
  });
});
