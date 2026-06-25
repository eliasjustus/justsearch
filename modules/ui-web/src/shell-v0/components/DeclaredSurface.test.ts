// @vitest-environment happy-dom

/**
 * Tempdoc 578 Workstream B — the 569 DeclaredSurface engine renders NESTED inside the shell STAGE
 * (which is itself role="main"), so it must never emit a second `main` landmark. A STAGE-placed
 * declaration becomes a named `region`; without a heading it carries no role at all. This pins the
 * fix the `check-a11y-closure` gate cannot see (the gate scans Shell.ts source, not the engine's
 * derived runtime role — 578 §14).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './DeclaredSurface.js';
import type { DeclaredSurface, SurfaceBodyDeclaration } from './DeclaredSurface.js';
import type { UISchemaElement } from '@jsonforms/core';
import {
  __feedForTest,
  __resetAiStateForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';

function declaration(over: Partial<SurfaceBodyDeclaration>): SurfaceBodyDeclaration {
  return {
    schema: {},
    uischema: { type: 'VerticalLayout', elements: [] } as UISchemaElement,
    ...over,
  };
}

async function mount(decl: SurfaceBodyDeclaration): Promise<DeclaredSurface> {
  const el = document.createElement('jf-declared-surface') as DeclaredSurface;
  el.declaration = decl;
  el.data = {};
  el.enabled = true;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function section(el: DeclaredSurface): HTMLElement | null {
  return el.shadowRoot!.querySelector('section');
}

describe('jf-declared-surface — nested landmark role (578 Workstream B)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('a STAGE placement does NOT emit role="main" (no duplicate-main under the stage)', async () => {
    const el = await mount(declaration({ placement: 'STAGE', heading: 'Settings' }));
    const sec = section(el);
    expect(sec).not.toBeNull();
    expect(sec!.getAttribute('role')).not.toBe('main');
    // It becomes a named region instead — a repeatable landmark valid beside the stage main.
    expect(sec!.getAttribute('role')).toBe('region');
    expect(sec!.getAttribute('aria-label')).toBe('Settings');
  });

  it('a STAGE placement with no heading carries no role at all (nameless region is dropped)', async () => {
    const el = await mount(declaration({ placement: 'STAGE' }));
    const sec = section(el);
    expect(sec!.getAttribute('role')).toBeNull();
  });

  it('a declaration with no placement carries no landmark role', async () => {
    const el = await mount(declaration({ heading: 'Body' }));
    const sec = section(el);
    expect(sec!.getAttribute('role')).toBeNull();
  });
});

// 594 Move 1b — the factual chip strip derives values from projectFact (the Display-value authority).
describe('jf-declared-surface — factual chip strip (594 Move 1b)', () => {
  const FACT_STRIP = declaration({
    overflow: [
      { id: 'cap-embed', fact: 'core.embed.dim', priority: 90, pinned: true },
      { id: 'cap-splade', fact: 'core.splade', priority: 70 },
      { id: 'cap-ner', fact: 'core.ner', priority: 50 },
      { id: 'cap-gpu', fact: 'core.gpu.accel', priority: 40 },
      { id: 'cap-vec', fact: 'core.vector.precision', priority: 30 },
    ],
  });
  const seed = (status: unknown): void =>
    __feedForTest({ status: status as StatusSnapshot | null });
  const items = (el: DeclaredSurface): string[] =>
    Array.from(el.shadowRoot!.querySelectorAll('.adaptive-strip .item')).map((e) =>
      (e.textContent ?? '').trim(),
    );

  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });

  it('omits absent capabilities, shows present ones, and never bakes a wrong dimension', async () => {
    seed({
      gpu: { available: false }, // CPU host → GPU chip omitted (never "GPU cuda12")
      worker: {
        enrichment: { spladeEnabled: true, nerEnabled: false }, // SPLADE present, NER absent
        vectorFormat: { vectorFormatActual: 'FLOAT32' },
      },
    });
    const el = await mount(FACT_STRIP);
    const txt = items(el);
    expect(txt).toContain('Embeddings 768-d'); // from the SSOT-generated constant, not a literal
    expect(txt).toContain('SPLADE');
    expect(txt).toContain('Vectors Float32');
    expect(txt.some((t) => t.startsWith('GPU'))).toBe(false); // absent → omitted
    expect(txt).not.toContain('NER'); // absent → omitted
  });

  it('renders the GPU accelerator with a provenance title when CUDA is functional', async () => {
    seed({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } });
    const el = await mount(FACT_STRIP);
    const gpu = Array.from(el.shadowRoot!.querySelectorAll<HTMLElement>('.adaptive-strip .item')).find(
      (e) => (e.textContent ?? '').includes('GPU'),
    );
    expect(gpu?.textContent?.trim()).toBe('GPU CUDA');
    expect(gpu?.getAttribute('title')).toBe('via NVML · high confidence');
  });

  it('§17.4 — a low-confidence chip carries an aria-label that states confidence/provenance in WORDS (not the bare "?")', async () => {
    seed({ gpu: { available: true, cudaFunctional: true, source: 'nvidia-smi', confidence: 'LOW' } });
    const el = await mount(FACT_STRIP);
    const gpu = Array.from(el.shadowRoot!.querySelectorAll<HTMLElement>('.adaptive-strip .item')).find(
      (e) => (e.textContent ?? '').includes('GPU'),
    );
    const aria = gpu?.getAttribute('aria-label') ?? '';
    expect(gpu?.textContent?.trim()).toBe('GPU CUDA?'); // visual cue for sighted users
    expect(aria).toContain('GPU CUDA'); // the value, without the ambiguous "?"
    expect(aria).not.toContain('?');
    expect(aria).toContain('low confidence'); // the "?" explained in words for AT
    expect(aria).toContain('nvidia-smi'); // provenance reachable by AT
  });

  it('a HIGH-confidence GPU renders flatly (no "?"); a LOW-confidence GPU marks uncertainty (§11.3 #2)', async () => {
    const gpuChip = (el: DeclaredSurface): HTMLElement | undefined =>
      Array.from(el.shadowRoot!.querySelectorAll<HTMLElement>('.adaptive-strip .item')).find((e) =>
        (e.textContent ?? '').includes('GPU'),
      );
    // HIGH (NVML): no trailing "?", data-confidence=high.
    seed({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } });
    const high = gpuChip(await mount(FACT_STRIP));
    expect(high?.textContent?.trim()).toBe('GPU CUDA');
    expect(high?.getAttribute('data-confidence')).toBe('high');
    // LOW (nvidia-smi): trailing "?", data-confidence=low → the uncertainty is visible.
    __resetAiStateForTest();
    seed({ gpu: { available: true, cudaFunctional: true, source: 'nvidia-smi', confidence: 'LOW' } });
    const low = gpuChip(await mount(FACT_STRIP));
    expect(low?.textContent?.trim()).toBe('GPU CUDA?');
    expect(low?.getAttribute('data-confidence')).toBe('low');
  });

  it('renders runtime facts MUTED (presence=unknown) when status has not been polled', async () => {
    // No seed → status is null → runtime facts are unknown (muted), embed constant still present.
    const el = await mount(FACT_STRIP);
    const unknownChips = Array.from(
      el.shadowRoot!.querySelectorAll('.adaptive-strip .item[data-presence="unknown"]'),
    );
    expect(unknownChips.length).toBeGreaterThan(0);
    expect(items(el)).toContain('Embeddings 768-d');
  });

  it('on a DIAGNOSTIC surface an absent capability shows "<name> off" instead of being omitted (§11.3 #4)', async () => {
    seed({ worker: { enrichment: { spladeEnabled: false, nerEnabled: true } } });
    const diag = declaration({
      altitude: 'DIAGNOSTIC',
      overflow: [
        { id: 'cap-splade', fact: 'core.splade', priority: 70 },
        { id: 'cap-ner', fact: 'core.ner', priority: 50 },
      ],
    });
    const el = await mount(diag);
    const txt = items(el);
    expect(txt).toContain('SPLADE off'); // absent, but diagnostic → shown as off
    expect(txt).toContain('NER'); // present
    // ambient (no altitude) would omit the absent SPLADE entirely:
    __resetAiStateForTest();
    seed({ worker: { enrichment: { spladeEnabled: false, nerEnabled: true } } });
    const ambient = declaration({
      overflow: [{ id: 'cap-splade', fact: 'core.splade', priority: 70 }],
    });
    const el2 = await mount(ambient);
    expect(items(el2)).not.toContain('SPLADE off');
    expect(items(el2).some((t) => t.startsWith('SPLADE'))).toBe(false);
  });
});
