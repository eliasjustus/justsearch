// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 813 §3b — the indexing-progress projection is the ONE derivation authority for index-wide
 * progress numbers. These cases pin the properties every consuming surface depends on: the phase
 * arms, the hard-zeroed-snapshot `unknown` arm (a worker that never reported must NOT read as
 * "ready"), disabled-stage handling, and denominator honesty (no NaN, no percent without a real
 * denominator).
 */
import { describe, it, expect } from 'vitest';
import { selectIndexingProgress } from './indexingProgress.js';
import type { StatusResponse } from '../../api/generated/index.js';

type Core = NonNullable<NonNullable<StatusResponse['worker']>['core']>;
type Enrichment = NonNullable<NonNullable<StatusResponse['worker']>['enrichment']>;
type Migration = NonNullable<NonNullable<StatusResponse['worker']>['migration']>;

function snapshot(parts: {
  core?: Partial<Core>;
  enrichment?: Partial<Enrichment>;
  migration?: Partial<Migration>;
}): StatusResponse {
  return {
    worker: {
      core: { indexState: 'IDLE', pendingJobs: 0, ...parts.core },
      enrichment: { backfillMode: 'idle', ...parts.enrichment },
      migration: { ...parts.migration },
    },
  } as StatusResponse;
}

describe('selectIndexingProgress — phase arms (813 §3a)', () => {
  it('pending jobs ⇒ "indexing", with a running/queued split that adds up to the total', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 1218 },
        migration: { processingJobsCount: 4 },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
    expect(p.jobsPending).toBe(1218);
    expect(p.jobsRunning).toBe(4);
    expect(p.jobsQueued).toBe(1214);
    expect(p.jobsRunning + p.jobsQueued).toBe(p.jobsPending);
  });

  it('jobs drained but enrichment counters still pending ⇒ "enriching"', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 100,
          embeddingPendingCount: 40,
          embeddingEnabled: true,
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPercent).toBe(60);
    expect(p.enrichingPending).toBe(40);
  });

  it('jobs drained and no pending counters, but the backfill is running ⇒ "enriching"', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: { backfillMode: 'combined' },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
  });

  it('everything settled ⇒ "ready"', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 0 },
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.enrichingPercent).toBe(100);
    expect(p.enrichingPending).toBe(0);
  });

  it('the indexing arm wins over enrichment even while the backfill mode is non-idle', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 7 },
        enrichment: { backfillMode: 'individual', embeddingDocCount: 10, embeddingPendingCount: 10 },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
  });
});

describe('selectIndexingProgress — the hard-zeroed / unreachable snapshot must not read as "ready"', () => {
  it('no snapshot at all ⇒ "unknown", no numbers', () => {
    const p = selectIndexingProgress(null, false);
    expect(p.phase).toBe('unknown');
    expect(p.jobsPending).toBe(0);
    expect(p.enrichingPercent).toBeNull();
  });

  it.each(['UNAVAILABLE', 'NOT_STARTED', 'PENDING', 'OFFLINE', 'RECOVERING', 'DEGRADED', ''])(
    'the WorkerOperationalView.fallback("%s") zeroed block ⇒ "unknown", NOT "ready"',
    (state) => {
      // `CoreIndexView.fallback` zeroes every count; only `indexState` distinguishes it from a real
      // settled worker. Reading those zeros as "ready" is the "0 == done" lie.
      const p = selectIndexingProgress(
        snapshot({ core: { indexState: state, pendingJobs: 0, indexHealthy: false } }),
        true,
      );
      expect(p.phase).toBe('unknown');
      expect(p.enrichingPercent).toBeNull();
    },
  );

  it('a worker block missing entirely ⇒ "unknown"', () => {
    expect(selectIndexingProgress({} as StatusResponse, true).phase).toBe('unknown');
  });

  it('liveness is threaded through on every arm (807 A.3), never re-derived here', () => {
    expect(selectIndexingProgress(null, true).live).toBe(true);
    expect(selectIndexingProgress(snapshot({}), false).live).toBe(false);
    expect(selectIndexingProgress(snapshot({}), true).live).toBe(true);
  });
});

describe('selectIndexingProgress — denominator honesty', () => {
  it('no enrichable documents ⇒ no percent (null), never NaN and never a fabricated 0%', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingDocCount: 0,
          embeddingPendingCount: 0,
          spladeDocCount: 0,
          chunk: { chunkDocCount: 0, chunkEmbeddingPendingCount: 0 },
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBeNull();
    expect(p.phase).toBe('ready');
  });

  it('a disabled stage is not-applicable — it leaves the denominator alone', () => {
    const withSplade = selectIndexingProgress(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 100,
          spladePendingCount: 100,
          spladeEnabled: true,
        },
      }),
      true,
    );
    expect(withSplade.enrichingPercent).toBe(50);

    const spladeOff = selectIndexingProgress(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 0,
          embeddingEnabled: true,
          spladeDocCount: 100,
          spladePendingCount: 100,
          spladeEnabled: false,
        },
      }),
      true,
    );
    // With SPLADE off the deployment is fully enriched, not stuck at 50% forever.
    expect(spladeOff.enrichingPercent).toBe(100);
    expect(spladeOff.phase).toBe('ready');
  });

  it('the percent is settled-over-total across parent stages AND the chunk tier', () => {
    const p = selectIndexingProgress(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 50,
          embeddingEnabled: true,
          chunk: { chunkDocCount: 300, chunkEmbeddingPendingCount: 150 },
        },
      }),
      true,
    );
    // 200 settled of 400 total — NOT the average of the two stage percentages.
    expect(p.enrichingPercent).toBe(50);
    expect(p.enrichingPending).toBe(200);
  });

  it('the percent is derived from counts, not from the pre-baked coverage percents', () => {
    const p = selectIndexingProgress(
      snapshot({
        enrichment: {
          embeddingDocCount: 100,
          embeddingPendingCount: 25,
          embeddingEnabled: true,
          embeddingCoveragePercent: 3,
          chunk: {
            chunkDocCount: 100,
            chunkEmbeddingPendingCount: 25,
            chunkVectorCoveragePercent: 3,
          },
        },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(75);
  });

  it('NER counts as a stage on its own two-valued census (it reports no doc count)', () => {
    const p = selectIndexingProgress(
      snapshot({
        enrichment: { completedNerCount: 30, pendingNerCount: 10, nerEnabled: true },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(75);
    expect(p.phase).toBe('enriching');
  });

  it('a pending count larger than its own total cannot push the percent negative', () => {
    const p = selectIndexingProgress(
      snapshot({
        enrichment: { embeddingDocCount: 10, embeddingPendingCount: 999, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.enrichingPercent).toBe(0);
  });

  it('exposes the per-subject pending counts the overlay renders, from the same snapshot', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0, pendingVduCount: 5 },
        enrichment: { embeddingDocCount: 100, embeddingPendingCount: 3, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.embeddingPending).toBe(3);
    expect(p.vduPending).toBe(5);
  });
});
