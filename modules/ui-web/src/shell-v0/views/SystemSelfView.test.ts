// @vitest-environment happy-dom

/**
 * Tempdoc 578 Workstream A — SystemSelfView ("Now") is now embedded as Health's compact live-strip
 * (`variant="strip"`), the standalone RAIL surface having been retired. The strip must NOT emit its
 * own `<h2>Now</h2>` heading (Health owns the page heading), while the historical `'full'` variant
 * keeps it. Both variants still render the live body (idle state here, since no tasks are seeded).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SystemSelfView.js';
import { visibleIndexQueueCount, type SystemSelfView } from './SystemSelfView.js';
import { UNKNOWN, known } from '../state/known.js';

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
