// @vitest-environment happy-dom

/**
 * Tempdoc 578 Workstream A — SystemSelfView ("Now") is now embedded as Health's compact live-strip
 * (`variant="strip"`), the standalone RAIL surface having been retired. The strip must NOT emit its
 * own `<h2>Now</h2>` heading (Health owns the page heading), while the historical `'full'` variant
 * keeps it. Both variants still render the live body (idle state here, since no tasks are seeded).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SystemSelfView.js';
import { visibleIndexQueueCount, enrichingLabel, type SystemSelfView } from './SystemSelfView.js';
import { UNKNOWN, known } from '../state/known.js';
import type { IndexingProgress } from '../state/indexingProgress.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  __tickClockForTest,
} from '../state/aiStateStore.js';

async function mount(variant?: 'full' | 'strip'): Promise<SystemSelfView> {
  const el = document.createElement('jf-system-self-view') as SystemSelfView;
  if (variant) el.variant = variant;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-system-self-view — strip variant (578 Workstream A)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ state: 'idle', updatedAtEpochMs: Date.now() }),
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.unstubAllGlobals();
  });

  it("full variant (default) emits its own <h2>Now</h2> heading", async () => {
    const el = await mount();
    expect(el.shadowRoot!.querySelector('h2')?.textContent).toBe('Now');
  });

  it('strip variant emits NO heading (Health owns the page heading)', async () => {
    const el = await mount('strip');
    expect(el.shadowRoot!.querySelector('h2')).toBeNull();
  });

  it('strip variant still renders the live body (idle state)', async () => {
    const el = await mount('strip');
    // The body region is always present; with nothing running it shows the compact idle marker.
    expect(el.shadowRoot!.querySelector('.body')).not.toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).not.toBeNull();
  });
});

describe('visibleIndexQueueCount', () => {
  const baseIndex = {
    documentCount: UNKNOWN,
    pendingJobs: UNKNOWN,
    embeddingPending: UNKNOWN,
    embeddingBlocked: UNKNOWN,
    embeddingQueueSize: UNKNOWN,
    vduQueueSize: UNKNOWN,
  };

  it('uses backend pending jobs as live indexing activity', () => {
    expect(
      visibleIndexQueueCount({
        index: {
          ...baseIndex,
          pendingJobs: known(3792),
        },
      }),
    ).toBe(3792);
  });

  it('falls back to known embedding and VDU queues when pending jobs are empty', () => {
    expect(
      visibleIndexQueueCount({
        index: {
          ...baseIndex,
          pendingJobs: known(0),
          embeddingPending: known(2),
          embeddingQueueSize: known(3),
          vduQueueSize: known(5),
        },
      }),
    ).toBe(10);
  });

  it('does not invent activity from unknown or zero queues', () => {
    expect(visibleIndexQueueCount({ index: baseIndex })).toBeNull();
    expect(
      visibleIndexQueueCount({
        index: {
          ...baseIndex,
          pendingJobs: known(0),
          embeddingPending: known(0),
          embeddingQueueSize: known(0),
          vduQueueSize: known(0),
        },
      }),
    ).toBeNull();
  });

  // Tempdoc 727 F-2: sandbox-round repro — worker.core.indexState=IDLE, pending/queue_depth/
  // processing_jobs_count all 0, yet the "Now" strip's INDEXING row showed "Processing 10 items /
  // running" (10 = embeddingPending(2) + embeddingQueueSize(3) + vduQueueSize(5)). On pre-fix code
  // this test FAILS (returns 10, not null): the embedding/VDU counters were trusted as "busy" even
  // though the worker had already authoritatively settled to IDLE — the same truth the Queue card
  // (`pendingJobs`) and the Index-state row already derive from.
  //
  // Tempdoc 813 §4 NARROWS, but does not repeal, this suppression: it stays exactly as pinned here
  // for genuinely-idle residue, and the "backfill is actually running" case is re-expressed as the
  // `enrichingLabel` pair below (F-2 was right that stale counters must not fake activity; it
  // overcorrected by also hiding real enrichment activity, which shares this same IDLE indexState).
  it('does not show stale embedding/VDU residue as busy once the worker has settled to IDLE', () => {
    expect(
      visibleIndexQueueCount({
        index: {
          ...baseIndex,
          pendingJobs: known(0),
          embeddingPending: known(2),
          embeddingQueueSize: known(3),
          vduQueueSize: known(5),
        },
        status: {
          worker: { core: { indexState: 'IDLE' } },
        } as unknown as import('../state/aiStateStore.js').StatusSnapshot,
      }),
    ).toBeNull();
  });

  it('still surfaces the embedding/VDU fallback when the worker is genuinely INDEXING', () => {
    expect(
      visibleIndexQueueCount({
        index: {
          ...baseIndex,
          pendingJobs: known(0),
          embeddingPending: known(2),
          embeddingQueueSize: known(3),
          vduQueueSize: known(5),
        },
        status: {
          worker: { core: { indexState: 'INDEXING' } },
        } as unknown as import('../state/aiStateStore.js').StatusSnapshot,
      }),
    ).toBe(10);
  });
});

/**
 * Tempdoc 813 §4 — the other half of the narrowed 727 F-2 suppression. `indexState` is IDLE across
 * BOTH the stale-residue case pinned above and the real enrichment window (the backfill runs on idle
 * cycles and never flips the index state), so suppression alone made the strip claim "System idle"
 * while the GPU was busy for minutes — finding 1's exact mechanism. These cases pin the split.
 */
describe('enrichingLabel (813 §4 — the narrowed F-2 suppression)', () => {
  const progress = (over: Partial<IndexingProgress>): IndexingProgress => ({
    phase: 'ready',
    jobsPending: 0,
    jobsRunning: 0,
    jobsQueued: 0,
    enrichingPercent: null,
    enrichingPending: 0,
    embeddingPending: 0,
    vduPending: 0,
    live: true,
    ...over,
  });

  it('backfill genuinely active ⇒ an "Enriching — N%" line, NOT the idle close', () => {
    expect(enrichingLabel(progress({ phase: 'enriching', enrichingPercent: 64 }))).toBe(
      'Enriching — 64% · semantic search catching up',
    );
  });

  it('enriching without a faithful denominator ⇒ the phase, no fabricated number', () => {
    const label = enrichingLabel(progress({ phase: 'enriching', enrichingPercent: null }));
    expect(label).toBe('Enriching — semantic search catching up');
    expect(label).not.toMatch(/\d/);
  });

  it('idle residue (phase "ready") ⇒ null — the suppression F-2 installed is retained', () => {
    expect(enrichingLabel(progress({ phase: 'ready', enrichingPercent: 100 }))).toBeNull();
  });

  it('job-queue work ⇒ null (the task rows already say it) and unknown ⇒ null', () => {
    expect(enrichingLabel(progress({ phase: 'indexing', jobsPending: 12 }))).toBeNull();
    expect(enrichingLabel(progress({ phase: 'unknown' }))).toBeNull();
  });

  it('a dead snapshot never asserts live enrichment (807 A.3)', () => {
    expect(
      enrichingLabel(progress({ phase: 'enriching', enrichingPercent: 64, live: false })),
    ).toBeNull();
  });
});

/**
 * The same split, end-to-end at the render site — the half `visibleIndexQueueCount`'s unit cases
 * cannot see. Both scenarios below have `indexState: 'IDLE'` and zero pending jobs; only the
 * enrichment counters differ, and that is exactly what must decide "System idle" vs "Enriching".
 */
describe('jf-system-self-view — the narrowed 727 F-2 suppression, rendered (813 §4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ state: 'idle', updatedAtEpochMs: Date.now() }),
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
    vi.unstubAllGlobals();
  });

  function feed(enrichment: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', pendingJobs: 0, indexHealthy: true }, enrichment },
      } as unknown as import('../state/aiStateStore.js').StatusSnapshot,
    });
    __tickClockForTest();
  }

  it('backfill genuinely active ⇒ the strip shows Enriching, NOT "System idle"', async () => {
    feed({
      backfillMode: 'combined',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 36,
    });
    const el = await mount('strip');
    const row = el.shadowRoot!.querySelector('[data-testid="self-view-enriching"]');
    expect(row?.textContent).toContain('Enriching — 64%');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).toBeNull();
  });

  it('idle residue only ⇒ "System idle" is retained (F-2 not repealed)', async () => {
    feed({
      backfillMode: 'idle',
      embeddingEnabled: true,
      embeddingDocCount: 100,
      embeddingPendingCount: 0,
    });
    const el = await mount('strip');
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-enriching"]')).toBeNull();
    expect(el.shadowRoot!.querySelector('[data-testid="self-view-idle"]')).not.toBeNull();
  });
});
