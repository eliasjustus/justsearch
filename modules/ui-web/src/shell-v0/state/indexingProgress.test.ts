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

  it('the CHUNK tier is gated on the embedding stage — same encoder, same applicability', () => {
    // Embedding off (no embedding service, or switched off): chunk vectors come from that same
    // encoder, so their pending count NEVER settles. Counting them left the deployment permanently
    // `enriching` — "Up to date" / "System idle" / "fully searchable" unreachable forever.
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          embeddingEnabled: false,
          embeddingDocCount: 100,
          embeddingPendingCount: 100,
          chunk: { chunkDocCount: 400, chunkEmbeddingPendingCount: 400 },
        },
      }),
      true,
    );
    expect(p.phase).toBe('ready');
    expect(p.enrichingPending).toBe(0);
    expect(p.enrichingPercent).toBeNull();
  });

  it('pending work with no denominator still withholds the terminal phase (positive evidence)', () => {
    // #375's enrichmentCoverage doctrine, kept through the 813 merge (§17): a pending counter on an
    // APPLICABLE stage is evidence of unfinished work even when its denominator is absent — the
    // PERCENT is suppressed (no faithful denominator), but the phase must not read 'ready' and let
    // the queue card claim "Up to date" over work it can see.
    const p = selectIndexingProgress(
      snapshot({
        core: { indexState: 'IDLE', pendingJobs: 0 },
        enrichment: {
          backfillMode: 'idle',
          chunk: { chunkEmbeddingPendingCount: 1554 }, // no chunkDocCount
        },
      }),
      true,
    );
    expect(p.phase).toBe('enriching');
    expect(p.enrichingPending).toBe(1554);
    expect(p.enrichingPercent).toBeNull();
  });

  it('applicability is NULL when the worker did not report — never an all-false claim', () => {
    // All-false says "no stage applies here", which a per-root consumer reads as a licence to
    // score coverage on whatever counts survive. Nothing observed must stay nothing claimed.
    expect(selectIndexingProgress(null, false).stages).toBeNull();
    expect(selectIndexingProgress({} as StatusResponse, true).stages).toBeNull();
    expect(
      selectIndexingProgress(snapshot({ core: { indexState: 'UNAVAILABLE' } }), true).stages,
    ).toBeNull();
    // A REPORTING worker does answer the question.
    expect(selectIndexingProgress(snapshot({}), true).stages).toEqual({
      embedding: true,
      splade: true,
      ner: true,
    });
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

  // The estimate counted UP while a walk was still enqueueing: "remaining / rate" divides by a
  // denominator that is still rising. The last two queue-depth samples of the SAME snapshot say
  // whether it is.
  it('suppresses the estimate while the backlog is still GROWING (a walk is enqueueing)', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: {
          indexState: 'INDEXING',
          pendingJobs: 400,
          recentDocsPerSec: [4, 4, 4],
          recentJobQueueDepth: [100, 250, 400],
        },
      }),
      true,
    );
    expect(p.phase).toBe('indexing');
    expect(p.etaSeconds).toBeNull();
  });

  it('estimates once the backlog is draining (the same snapshot shape, depth falling)', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: {
          indexState: 'INDEXING',
          pendingJobs: 400,
          recentDocsPerSec: [4, 4, 4],
          recentJobQueueDepth: [900, 650, 400],
        },
      }),
      true,
    );
    expect(p.etaSeconds).toBe(100);
  });
});

/**
 * 813 Slice B — the byte weight of the remaining backlog. Same denominator discipline as the
 * percent: shown only when it is faithful, withdrawn (never 0) when it is not.
 */
describe('selectIndexingProgress — remaining byte weight', () => {
  const withBytes = (over: Record<string, unknown>) =>
    selectIndexingProgress(
      snapshot({
        core: { indexState: 'INDEXING', pendingJobs: 100, ...over },
      }),
      true,
    );

  it('reports the recorded weight of the remaining jobs', () => {
    expect(withBytes({ pendingBytes: 5_000_000, pendingUnknownSizeJobs: 4 }).pendingBytes).toBe(
      5_000_000,
    );
  });

  it('withdraws it when most of the backlog has no recorded size', () => {
    // 60 of 100 remaining jobs carry no size: the sum is not a weight of the backlog any more.
    expect(withBytes({ pendingBytes: 5_000_000, pendingUnknownSizeJobs: 60 }).pendingBytes).toBeNull();
  });

  it('withdraws it when there is no backlog to weigh', () => {
    const p = selectIndexingProgress(
      snapshot({
        core: {
          indexState: 'IDLE',
          pendingJobs: 0,
          pendingBytes: 5_000_000,
          pendingUnknownSizeJobs: 0,
        },
      }),
      true,
    );
    expect(p.pendingBytes).toBeNull();
  });

  it('withdraws it when nothing is recorded, and on the worker UNAVAILABLE marker', () => {
    expect(withBytes({ pendingBytes: 0, pendingUnknownSizeJobs: 0 }).pendingBytes).toBeNull();
    // JobQueue.PendingBytes.UNAVAILABLE: zero bytes plus the -1 marker, never "0 B remaining".
    expect(withBytes({ pendingBytes: 0, pendingUnknownSizeJobs: -1 }).pendingBytes).toBeNull();
  });
});
