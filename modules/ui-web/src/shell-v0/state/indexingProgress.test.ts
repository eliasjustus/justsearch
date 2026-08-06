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

/**
 * 813 §5b — the estimate is INDICATIVE and suppressed whenever there is no honest basis. Each arm
 * below is a way the estimate could have been fabricated; the projection must return `null`, so the
 * Tasks panel renders no line at all rather than a placeholder.
 */
describe('selectIndexingProgress — indicative estimate (813 §5b)', () => {
  const indexing = (pendingJobs: number, recentDocsPerSec: number[]) =>
    snapshot({ core: { indexState: 'INDEXING', pendingJobs, recentDocsPerSec } });

  it('extrapolates the backlog over the MEDIAN of the trailing rate window', () => {
    // median(10, 4, 4) = 4 ⇒ 400 / 4 = 100s. The mean (6) would have said 67s — one burst of tiny
    // files must not halve the estimate.
    const p = selectIndexingProgress(indexing(400, [1, 2, 10, 4, 4]), true);
    expect(p.etaSeconds).toBe(100);
  });

  it('suppresses the estimate when a trailing sample is zero (the producer’s "unknown window")', () => {
    // `recentDocsPerSec` reports 0.0 for windows it could not measure (WorkerOpsMetricCatalog's
    // INDEX_DOCS_PER_SEC contract) — a zero in the window means the rate is not established.
    expect(selectIndexingProgress(indexing(400, [4, 0, 4]), true).etaSeconds).toBeNull();
  });

  it('suppresses the estimate when the trend is too short to be a window', () => {
    expect(selectIndexingProgress(indexing(400, [4, 4]), true).etaSeconds).toBeNull();
    expect(selectIndexingProgress(indexing(400, []), true).etaSeconds).toBeNull();
  });

  it('suppresses the estimate on a backlog too small to be worth extrapolating', () => {
    expect(selectIndexingProgress(indexing(5, [4, 4, 4]), true).etaSeconds).toBeNull();
  });

  it('suppresses an implausibly distant estimate rather than rendering it', () => {
    // 0.01 docs/s over 400 jobs = ~11 hours; a three-sample window cannot support that claim.
    expect(selectIndexingProgress(indexing(400, [0.01, 0.01, 0.01]), true).etaSeconds).toBeNull();
  });

  it('never estimates during enrichment (§1e: ingest preemption makes throughput unstable)', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0, recentDocsPerSec: [4, 4, 4] },
        enrichment: { embeddingDocCount: 1000, embeddingPendingCount: 400, embeddingEnabled: true },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.etaSeconds).toBeNull();
  });

  it('never estimates from a stale snapshot (a past rate is not a present one)', () => {
    expect(selectIndexingProgress(indexing(400, [4, 4, 4]), false).etaSeconds).toBeNull();
  });
});
