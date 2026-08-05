// @vitest-environment happy-dom

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — with the backend dead, no snapshot-derived Brain surface may
 * render a PRESENT-TENSE progress or capability claim.
 *
 * The round photographed, with both java processes killed: an animating "Building semantic search
 * 2.0% · 5,084 pending", "Search Quality Features 4/4 active", and a populated Runtime card. Each
 * value was a true past measurement rendered as a present fact. These tests assert on the RENDERED
 * TEXT, and each has an anti-regression twin proving a healthy verdict still renders normally.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';
import '../components/Control.js';
import type { Control } from '../components/Control.js';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';
import type { Availability } from '../state/availability.js';
import type { AiRuntime, AiState, InstallStatus } from '../state/aiStateStore.js';
import { computeAiEngineVerdict } from '../state/aiVerdict.js';
import { UNKNOWN, type Maybe } from '../state/known.js';
import { reasonFor } from '../state/readinessNotice.js';

/** The disconnection wording is IMPORTED from the one cause vocabulary, never a string literal here. */
const DISCONNECTED = reasonFor('binding.unreachable').wording;

/** The surface formats counts with the ambient locale's grouping — assert against the same. */
const num = (n: number) => new Intl.NumberFormat().format(n);

const DEAD_VERDICT = { kind: 'transitioning', severity: 'warn', reasons: ['channel-stale'] } as const;
const LIVE_VERDICT = { kind: 'operational', severity: 'ok', reasons: [] } as const;

interface Harness {
  apiBase: string;
  systemStatus: unknown;
  runtimeStatus: unknown;
  inference: unknown;
  policy: unknown;
  busy: Record<string, boolean>;
  _unifiedAiState: unknown;
  renderEmbeddingProgress(): TemplateResult | symbol;
  renderSearchQualityFeatures(): TemplateResult;
  renderRuntimeSection(): TemplateResult;
}

/** A detached BrainSurface (no connectedCallback ⇒ no poll/subscribe) primed with the round-13 data. */
function harness(live: boolean): Harness {
  const el = document.createElement('jf-brain-surface') as unknown as Harness;
  el.apiBase = '';
  el.busy = {};
  el.policy = {};
  el.systemStatus = {
    embedding: { pendingCount: 5084, completedCount: 104, docCount: 5188, coveragePercent: 2.0 },
  };
  el.runtimeStatus = {
    onnxFeatures: [
      { id: 'reranker', label: 'Reranker', modelActive: true, executionProvider: 'cuda' },
      { id: 'splade', label: 'SPLADE', modelActive: true, executionProvider: 'cuda' },
      { id: 'embed', label: 'Embeddings', modelActive: true, executionProvider: 'cuda' },
      { id: 'citation_scorer', label: 'Citations', modelActive: true, executionProvider: 'cpu' },
    ],
  };
  el.inference = {
    mode: 'online',
    gpu: { cudaAvailable: true, vramDescription: '12.0 GB' },
    embeddingQueueSize: 4789,
  };
  el._unifiedAiState = {
    verdict: live ? LIVE_VERDICT : DEAD_VERDICT,
    snapshotLive: live,
    aiEngine: { kind: 'online', stability: { kind: 'settled' }, installFailure: null },
  } as unknown as AiState;
  return el;
}

function textOf(tpl: TemplateResult | symbol): string {
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container.textContent ?? '';
}

function domOf(tpl: TemplateResult | symbol): HTMLElement {
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container;
}

describe('BrainSurface — embedding progress card (807)', () => {
  it('stops presenting as live progress when the snapshot is not live', () => {
    const text = textOf(harness(false).renderEmbeddingProgress());
    expect(text).not.toContain('Building semantic search');
    expect(text).toContain('last known');
    expect(text).toContain(DISCONNECTED);
    // The figures survive — this bundle changes their tense, not their existence.
    expect(text).toContain('2.0%');
    expect(text).toContain('pending when last observed');
  });

  it('stops ANIMATING when the snapshot is not live (the spinner is the present-tense claim)', () => {
    const dead = domOf(harness(false).renderEmbeddingProgress());
    expect(dead.querySelector('.spin, [class*="spin"]')).toBeNull();
    const live = domOf(harness(true).renderEmbeddingProgress());
    expect(live.querySelector('.spin, [class*="spin"]')).not.toBeNull();
  });

  it('ANTI-REGRESSION: a healthy verdict still renders the live progress card unchanged', () => {
    const text = textOf(harness(true).renderEmbeddingProgress());
    expect(text).toContain('Building semantic search');
    expect(text).toContain('2.0%');
    expect(text).toContain(`${num(5084)} pending`);
    expect(text).not.toContain('last known');
  });
});

describe('BrainSurface — capability counts (807)', () => {
  it('"4/4 active" stops claiming active when the snapshot is not live', () => {
    const text = textOf(harness(false).renderSearchQualityFeatures());
    expect(text).not.toContain('4/4 active');
    expect(text).toContain('4/4 when last observed');
  });

  it('ANTI-REGRESSION: a healthy verdict still says "4/4 active"', () => {
    expect(textOf(harness(true).renderSearchQualityFeatures())).toContain('4/4 active');
  });
});

/**
 * Tempdoc 807 Part A (W4) — the SENTENCE itself, asserted where the round photographed it: the Brain
 * status card. W1 fixed the surfaces around this card; the card's own words come from the AI-engine
 * verdict, so this harness derives `aiEngine` through the real `computeAiEngineVerdict` (never a
 * hand-written verdict literal) — the test fails if EITHER the derivation or this render regresses.
 */
describe('BrainSurface — the status card sentence (807 Part A)', () => {
  interface PanelHarness {
    apiBase: string;
    busy: Record<string, boolean>;
    policy: unknown;
    installStatus: InstallStatus | null;
    inference: unknown;
    _unifiedAiState: unknown;
    renderSimplePanel(): TemplateResult;
  }

  const RUNTIME: AiRuntime = {
    mode: 'online',
    modelId: 'qwen3-4b',
    modelLabel: 'Qwen3 4B',
    contextWindow: 8192,
    gpu: null,
    installed: UNKNOWN as Maybe<boolean>,
    installing: UNKNOWN as Maybe<boolean>,
    loadStartedAtMs: null,
  };

  /** A healthy engine observed at some past moment; `live` says whether that observation still holds. */
  function panel(live: boolean): PanelHarness {
    const el = document.createElement('jf-brain-surface') as unknown as PanelHarness;
    el.apiBase = '';
    el.busy = {};
    el.policy = {};
    el.installStatus = { state: 'idle', phase: 'idle', installedFully: true };
    el.inference = { mode: 'online' };
    const aiEngine = computeAiEngineVerdict({
      installStatus: el.installStatus,
      runtimeStatus: null,
      runtime: RUNTIME,
      reachable: live,
      snapshotLive: live,
      engineState: 'Healthy',
      chatEnabledSpec: true,
    });
    el._unifiedAiState = {
      verdict: live ? LIVE_VERDICT : DEAD_VERDICT,
      snapshotLive: live,
      aiEngine,
      runtime: RUNTIME,
      inference: el.inference,
      status: {},
    } as unknown as AiState;
    return el;
  }

  it('THE round-13 photograph: with the backend gone, the card no longer reads "Online / Chat and summaries ready."', () => {
    const dom = domOf(panel(false).renderSimplePanel());
    expect(dom.textContent).not.toContain('Chat and summaries ready.');
    expect(dom.textContent).not.toContain('Online');
    // ...and the dot beside it is not the settled green one.
    // `tone` is bound as an ATTRIBUTE (`tone=${...}`), and the atom is not upgraded in this detached
    // render — read the attribute, not a property that would be `undefined` either way.
    const dot = dom.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).not.toBe('success');
  });

  it('the card offers no action whose precondition is a live backend (807 A.3)', () => {
    const dom = domOf(panel(false).renderSimplePanel());
    const action = dom.querySelector('[data-testid="brain-simple-action"]') as unknown as {
      label: string;
      availability?: { kind: string };
    } | null;
    expect(action?.label).not.toBe('Shut Down AI');
    expect(action?.availability?.kind).not.toBe('available');
  });

  it('ANTI-REGRESSION: a live observation still renders the green "Online / Chat and summaries ready."', () => {
    const dom = domOf(panel(true).renderSimplePanel());
    expect(dom.textContent).toContain('Online');
    expect(dom.textContent).toContain('Chat and summaries ready.');
    // `tone` is bound as an ATTRIBUTE (`tone=${...}`), and the atom is not upgraded in this detached
    // render — read the attribute, not a property that would be `undefined` either way.
    const dot = dom.querySelector('jf-status-dot');
    expect(dot?.getAttribute('tone')).toBe('success');
  });
});

describe('BrainSurface — Runtime card (807)', () => {
  it('labels the readout as last-known and blocks its controls with a reason when not live', () => {
    const dom = domOf(harness(false).renderRuntimeSection());
    expect(dom.textContent).toContain(DISCONNECTED);
    expect(dom.textContent).toContain('last observed');
    // Every control whose precondition is a live backend is unavailable-WITH-A-REASON (the 596 soft
    // block), not silently clickable-and-failing.
    const buttons = Array.from(dom.querySelectorAll('jf-button')) as unknown as {
      label: string;
      availability?: { kind: string; reason?: string; transient?: boolean };
    }[];
    const gated = buttons.filter((b) => ['Online', 'Indexing', 'Reload'].includes(b.label));
    expect(gated.length).toBe(3);
    for (const b of gated) {
      expect(b.availability?.kind, b.label).toBe('unavailable');
      expect(b.availability?.reason, b.label).toBe(DISCONNECTED);
      // Round-13 review, P2: NOT transient. `transient` is what makes `jf-control` queue the intent
      // and replay it on reconnect (Control.activate/resolveQueued) — for RUNTIME MUTATIONS that
      // turns an offline click into a burst of conflicting POSTs the moment the backend returns,
      // the opposite of the "disabled while disconnected" these controls are supposed to be.
      expect(b.availability?.transient, b.label).toBeFalsy();
    }
  });

  it('P2: an offline click on a runtime control neither queues nor fires when the backend returns', async () => {
    // The REAL gate value the surface produces, driven through a REAL jf-control.
    const dom = domOf(harness(false).renderRuntimeSection());
    const online = Array.from(dom.querySelectorAll('jf-button')).find(
      (b) => (b as unknown as { label: string }).label === 'Online',
    ) as unknown as { availability?: Availability };
    expect(online?.availability?.kind).toBe('unavailable');

    const onActivate = vi.fn();
    const toasts: string[] = [];
    const listener = (e: Event) => toasts.push((e as CustomEvent).detail?.message ?? '');
    document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
    const el = document.createElement('jf-control') as Control;
    el.label = 'Online';
    el.availability = online.availability as Availability;
    el.onActivate = onActivate;
    document.body.appendChild(el);
    await el.updateComplete;

    el.shadowRoot!.querySelector('button')!.click();
    expect(onActivate).not.toHaveBeenCalled();
    expect(toasts.some((m) => /queued/i.test(m))).toBe(false);

    el.availability = { kind: 'available' }; // the backend comes back
    await el.updateComplete;
    document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
    expect(onActivate).not.toHaveBeenCalled(); // no unattended runtime mutation
    el.remove();
  });

  it('ANTI-REGRESSION: a healthy verdict leaves the Runtime readout and its controls alone', () => {
    const dom = domOf(harness(true).renderRuntimeSection());
    expect(dom.textContent).not.toContain(DISCONNECTED);
    expect(dom.textContent).toContain('available'); // CUDA: available
    expect(dom.textContent).toContain(num(4789)); // embed queue, un-caveated
    expect(dom.textContent).not.toContain('last observed');
    const buttons = Array.from(dom.querySelectorAll('jf-button')) as unknown as {
      label: string;
      availability?: { kind: string };
    }[];
    for (const b of buttons.filter((x) => ['Online', 'Indexing', 'Reload'].includes(x.label))) {
      expect(b.availability?.kind, b.label).toBe('available');
    }
  });
});
