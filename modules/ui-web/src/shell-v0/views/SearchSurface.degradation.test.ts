// @vitest-environment happy-dom

/**
 * Tempdoc 557 phase-2 B3 — the Search degradation banner.
 *
 * Q5: semantic search silently falls back to keyword-only when retrieval is
 * DEGRADED. The banner surfaces that, projected from the ONE observed-state
 * authority (aiStateStore.readiness) — so it is honest by construction and
 * present/absent strictly tracks the backend composite.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import './SearchSurface.ts';
import { SearchSurface } from './SearchSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';

function mount(): SearchSurface {
  const el = document.createElement('jf-search-surface') as SearchSurface;
  el.host_ = createMockHostApi({});
  document.body.appendChild(el);
  return el;
}

const banner = (el: SearchSurface) =>
  el.shadowRoot?.querySelector('[data-testid="search-degradation"]') ?? null;

describe('SearchSurface — B3 retrieval-degraded banner', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('shows the banner when the retrieval composite is DEGRADED', async () => {
    __feedForTest({
      status: {
        readiness: { composites: { retrieval: { state: 'DEGRADED' } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    expect(banner(el)).not.toBeNull();
    expect(banner(el)?.textContent).toContain('Semantic search degraded');
    // 559 notice-presentation: the banner renders through the shared notice
    // primitive (same tone + a11y authority as the toast), warning tone.
    expect(banner(el)?.tagName.toLowerCase()).toBe('jf-system-notice');
    expect(banner(el)?.getAttribute('tone')).toBe('warning');
    expect(banner(el)?.getAttribute('role')).toBe('status');
  });

  it('637 #1: a dead FE→backend binding renders a LOUD disconnected banner + disables the search input', async () => {
    // The store's disconnected→unreachable derivation is unit-tested in verdict.test.ts; here we
    // pin the SearchSurface consequence: given the ONE verdict it consumes is `unreachable`, the
    // loud banner renders (error tone) and the search input is write-disabled.
    const el = mount();
    await el.updateComplete;
    el.verdict = { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] };
    el.requestUpdate();
    await el.updateComplete;
    const b = banner(el);
    expect(b).not.toBeNull();
    expect(b?.textContent).toContain('Backend disconnected');
    expect(b?.getAttribute('tone')).toBe('error'); // loud, not the calm info/warning tones
    const input = el.shadowRoot?.querySelector(
      '[data-testid="search-input"]',
    ) as HTMLInputElement | null;
    expect(input?.disabled).toBe(true); // writes disabled while unreachable
  });

  it('595 §10.3: a cosmetic degradation (LambdaMART) renders the banner CALMLY — no "keyword results", tone info', async () => {
    __feedForTest({
      status: {
        readiness: {
          composites: {
            retrieval: { state: 'DEGRADED', reasonCodes: ['lambdamart.not_configured'] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    const b = banner(el);
    expect(b).not.toBeNull();
    expect(b?.textContent).toContain('Reduced search capability');
    expect(b?.textContent).not.toContain('keyword results');
    expect(b?.getAttribute('tone')).toBe('info'); // calm, consistent with the Health header
  });

  it('shows a reindex-required banner naming the cause when retrieval is compat-blocked (600 Design A)', async () => {
    __feedForTest({
      status: {
        readiness: {
          composites: {
            retrieval: { state: 'DEGRADED', reasonCodes: ['index.blocked_legacy'] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    const text = banner(el)?.textContent ?? '';
    expect(text).toContain('Reindex required');
    // Tempdoc 600's core fix: the banner now names the SPECIFIC actionable cause.
    expect(text).toContain('built before semantic search was available');
  });

  it('hides the banner when retrieval is READY', async () => {
    __feedForTest({
      status: {
        readiness: { composites: { retrieval: { state: 'READY' } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    expect(banner(el)).toBeNull();
  });

  it('hides the banner when readiness is unknown (no data yet)', async () => {
    const el = mount();
    await el.updateComplete;
    expect(banner(el)).toBeNull();
  });

  // Tempdoc 577 Phase 2 (Ext III) — the banner explains itself: worded cause +
  // a dispatchable remedy, projected from the readinessNotice authority.
  it('words known reason codes as causes and renders the mapped operation remedy', async () => {
    __feedForTest({
      status: {
        readiness: {
          composites: {
            retrieval: {
              state: 'DEGRADED',
              reasonCodes: ['worker.health.embedding_not_ready'],
            },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    const causes = el.shadowRoot?.querySelector('[data-testid="degradation-causes"]');
    expect(causes?.textContent).toContain('The semantic embedding index is not ready');
    const op = el.shadowRoot?.querySelector('[data-testid="degradation-remedy-op"]');
    expect(op).not.toBeNull();
    expect(op?.getAttribute('operation-id')).toBe('core.trigger-offline-processing');
  });

  it('falls back to the Open Health navigate remedy for unknown/empty causes', async () => {
    __feedForTest({
      status: {
        readiness: { composites: { retrieval: { state: 'DEGRADED' } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    const nav = el.shadowRoot?.querySelector('[data-testid="degradation-remedy-nav"]');
    expect(nav).not.toBeNull();
    expect(nav?.textContent).toContain('Open Health');
    let target: string | null = null;
    el.addEventListener('navigate-with-context', (e) => {
      target = (e as CustomEvent<{ target: string }>).detail.target;
    });
    (nav as unknown as { onActivate: () => void }).onActivate();
    expect(target).toBe('core.health-surface');
  });

  it('reindex-required banner carries the rebuild-index operation remedy', async () => {
    __feedForTest({
      status: {
        readiness: {
          composites: {
            retrieval: { state: 'DEGRADED', reasonCodes: ['index.blocked_legacy'] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = mount();
    await el.updateComplete;
    const op = el.shadowRoot?.querySelector('[data-testid="degradation-remedy-op"]');
    expect(op?.getAttribute('operation-id')).toBe('core.rebuild-index');
  });
});
