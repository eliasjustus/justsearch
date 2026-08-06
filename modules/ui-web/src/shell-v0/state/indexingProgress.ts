// SPDX-License-Identifier: Apache-2.0
/**
 * indexingProgress — tempdoc 813 §3b: the ONE derivation authority for index-wide indexing/enrichment
 * progress numbers.
 *
 * Every index-wide surface (status-bar queue chip, Health's Queue card, Health's "Now" strip, the
 * indexing overlay) renders from this selector instead of privately re-deriving "how much work is
 * left" from whichever field it happened to reach. Numbers may differ between surfaces by SCOPE,
 * never by DERIVATION (§3b) — the §1a defect class was one queue described by two transports.
 *
 * Transport is settled by 813 §13: a POLL-DERIVED SELECTOR over the single `/api/status` snapshot the
 * store already holds (`statusPoll` → `aiStateStore.status`). No new SSE resource, no second fetch —
 * internally consistent by construction, because every number here comes from one snapshot.
 *
 * The snapshot type is the generated wire authority `StatusResponse`; this module declares no local
 * response-shape interface (the `check-observed-state-collapse` rule).
 */

import type { StatusResponse } from '../../api/generated/index.js';

/**
 * The 813 §3a phase model, index-wide scope. `Scanning` is per-scan (the scan SSE) and is therefore
 * not derivable from this snapshot — it is not modelled here.
 */
export type IndexingPhase = 'indexing' | 'enriching' | 'ready' | 'unknown';

export interface IndexingProgress {
  /** §3a phase. `unknown` ⟹ the worker did not report; NO number below may be rendered. */
  phase: IndexingPhase;
  /** Non-terminal job rows (PENDING + PROCESSING) — `worker.core.pendingJobs` (the queueDepth projection). */
  jobsPending: number;
  /** The PROCESSING slice of {@link jobsPending}. */
  jobsRunning: number;
  /** The PENDING slice of {@link jobsPending}. */
  jobsQueued: number;
  /**
   * Settled-over-total across every APPLICABLE enrichment stage (parent embedding/SPLADE/NER + the
   * chunk tier), 0-100, rounded. `null` when there is no faithful denominator — the worker did not
   * report, or no stage has any documents to enrich. Never NaN, never a fabricated number
   * (`availability.ts:193-194`'s discipline, applied to progress).
   */
  enrichingPercent: number | null;
  /** Documents still PENDING across every applicable enrichment stage (0 when nothing is pending). */
  enrichingPending: number;
  /** Whole documents whose parent embedding is still PENDING (`worker.enrichment.embedding.pendingCount`). */
  embeddingPending: number;
  /** Documents still awaiting visual (VDU) extraction (`worker.core.pendingVduCount`). */
  vduPending: number;
  /**
   * 813 §5b — a COARSE, INDICATIVE seconds-remaining for the {@link IndexingPhase} `indexing` arm
   * only, extrapolated from observed recent throughput over the remaining backlog. `null` whenever
   * there is no honest basis, and the caller renders NOTHING rather than a placeholder:
   *  - any phase other than `indexing` — during `enriching` throughput is legitimately unstable
   *    (§1e: ingest preempts the backfill at batch boundaries), so an estimate there would be a
   *    fabricated number;
   *  - a stale snapshot (`live === false`) — extrapolating from a past measurement asserts a
   *    present rate nobody observed;
   *  - a backlog below {@link ETA_MIN_JOBS}, where the estimate is noise, not information;
   *  - an unstable/absent rate (see {@link deriveEtaSeconds});
   *  - a result beyond {@link ETA_MAX_SECONDS}, which a trailing-3-sample window cannot support.
   * Doc-count-based by construction and therefore never promoted to a countdown: the job queue has
   * no byte-size column (§1f), so on a mixed corpus this is an order-of-magnitude hint at best.
   */
  etaSeconds: number | null;
  /**
   * Is this snapshot still a LIVE observation? Threaded in from the ONE liveness authority
   * (`AiState.snapshotLive`, 807 A.3) — this module does NOT invent a staleness signal, and in
   * particular never reads `chunkCoverage.observedAtMs`, which is a Head serialization stamp.
   */
  live: boolean;
}

/**
 * Index states the WORKER itself reports (`IndexStatusOps.buildCore`). Anything else — the
 * `WorkerOperationalView.fallback(...)` states ("UNAVAILABLE", "NOT_STARTED", or a `CapabilityHealth`
 * name) — means the whole worker block was HARD-ZEROED before serialization
 * (`WorkerStatusCache.status()` / `CoreIndexView.fallback`), so its zeros are absence, not "settled".
 * Reading them as `ready` is the "0 == done" lie this allowlist exists to prevent.
 */
const WORKER_REPORTED_INDEX_STATES: ReadonlySet<string> = new Set(['IDLE', 'INDEXING', 'ERROR']);

const EMPTY: IndexingProgress = {
  phase: 'unknown',
  jobsPending: 0,
  jobsRunning: 0,
  jobsQueued: 0,
  enrichingPercent: null,
  enrichingPending: 0,
  embeddingPending: 0,
  vduPending: 0,
  etaSeconds: null,
  live: false,
};

/** Trailing `recentDocsPerSec` samples that must ALL be non-zero before the rate is extrapolated. */
const ETA_STABLE_SAMPLES = 3;
/** Below this backlog the extrapolation is noise (at ~1 doc/s the answer is "a moment", not a number). */
const ETA_MIN_JOBS = 20;
/**
 * Above this the trailing-window extrapolation is not credible, and the shared humanizer
 * (`startupEstimate.humanizeSeconds`) has no hour form — so the estimate is withdrawn rather than
 * rendered as an implausible "180m".
 */
const ETA_MAX_SECONDS = 3600;

function count(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * One enrichment stage's contribution. `settled = total - pending` rather than `completed + failed`
 * on purpose: the wire exposes only the COMPLETED / PENDING / FAILED buckets, while a stage's
 * terminal vocabulary also includes COMPLETED_EMPTY (ran fine, produced nothing —
 * `IndexStatusOps.buildEnrichment`'s own note for NER). Counting "not pending" is therefore the
 * faithful TERMINAL count per 813 §13; counting `completed + failed` would under-count and leave
 * enrichment looking permanently unfinished.
 */
interface StageWork {
  total: number;
  pending: number;
}

function stage(total: number, pending: number, enabled: boolean | undefined): StageWork | null {
  // A disabled stage is not-applicable — it contributes to neither numerator nor denominator, so a
  // deployment with SPLADE off cannot be stuck at "67% enriched" forever.
  if (enabled === false) return null;
  if (total <= 0) return null;
  return { total, pending: Math.min(pending, total) };
}

/**
 * The coarse seconds-remaining, or `null` when there is no honest basis (see
 * {@link IndexingProgress.etaSeconds} for the full suppression list).
 *
 * `worker.core.recentDocsPerSec` is a 30-minute RRD trend of the worker's own 180 s rolling
 * throughput gauge (`WorkerOpsMetricCatalog.INDEX_DOCS_PER_SEC`), which reports **0.0** for windows
 * it could not measure (insufficient samples, or nothing processing). So "every sample in the
 * trailing window is non-zero" is the stability test the producer's own vocabulary supports; the
 * MEDIAN of that window is used rather than the mean so one spike (a burst of tiny files) cannot
 * halve the estimate.
 */
function deriveEtaSeconds(
  samples: readonly number[] | null | undefined,
  jobsPending: number,
): number | null {
  if (jobsPending < ETA_MIN_JOBS) return null;
  if (!samples || samples.length < ETA_STABLE_SAMPLES) return null;
  const tail = samples.slice(-ETA_STABLE_SAMPLES);
  if (!tail.every((v) => Number.isFinite(v) && v > 0)) return null;
  const sorted = [...tail].sort((a, b) => a - b);
  const rate = sorted[Math.floor(sorted.length / 2)]!;
  const seconds = Math.round(jobsPending / rate);
  if (!Number.isFinite(seconds) || seconds <= 0 || seconds > ETA_MAX_SECONDS) return null;
  return seconds;
}

/**
 * Derive the index-wide progress projection from one `/api/status` snapshot.
 *
 * @param status the retained poll snapshot (`AiState.status`); `null` before the first poll.
 * @param snapshotLive the ONE liveness answer (`AiState.snapshotLive`).
 */
export function selectIndexingProgress(
  status: StatusResponse | null | undefined,
  snapshotLive: boolean,
): IndexingProgress {
  const core = status?.worker?.core;
  const indexState = core?.indexState ?? '';
  if (!status || !core || !WORKER_REPORTED_INDEX_STATES.has(indexState)) {
    return { ...EMPTY, live: snapshotLive };
  }

  const migration = status.worker?.migration;
  const jobsPending = count(core.pendingJobs);
  // The running/queued split comes from the SAME jobs table this count projects (`jobStateCounts()`),
  // not from counting rendered task rows — that row list is a different transport (the SSE mirror)
  // and is exactly what drifted in §1a.
  const jobsRunning = Math.min(count(migration?.processingJobsCount), jobsPending);
  // Derived, not read from `migration.pendingJobsCount`: `queueDepth()` and `jobStateCounts()` are two
  // separate reads of the jobs table, so trusting both independently could render a split that does not
  // add up to the total the same surface shows. running + queued == jobsPending, by construction.
  const jobsQueued = jobsPending - jobsRunning;

  const enrichment = status.worker?.enrichment;
  const chunk = enrichment?.chunk ?? null;
  const nerCompleted = count(enrichment?.completedNerCount);
  const nerPending = count(enrichment?.pendingNerCount);

  const stages: Array<StageWork | null> = [
    stage(
      count(enrichment?.embeddingDocCount),
      count(enrichment?.embeddingPendingCount),
      enrichment?.embeddingEnabled,
    ),
    stage(
      count(enrichment?.spladeDocCount),
      count(enrichment?.spladePendingCount),
      enrichment?.spladeEnabled,
    ),
    // NER reports no doc-count, so its denominator is its own two-valued census.
    stage(nerCompleted + nerPending, nerPending, enrichment?.nerEnabled),
    // The chunk tier's denominator is CHUNKED documents (813 §13) — never "N of M files".
    stage(count(chunk?.chunkDocCount), count(chunk?.chunkEmbeddingPendingCount), undefined),
  ];

  let total = 0;
  let pending = 0;
  for (const s of stages) {
    if (s === null) continue;
    total += s.total;
    pending += s.pending;
  }
  // No applicable stage ⟹ no faithful denominator ⟹ no percent (never 0/0 → NaN, never a fake 0%).
  const enrichingPercent = total > 0 ? Math.round(((total - pending) / total) * 100) : null;

  const backfillMode = enrichment?.backfillMode ?? 'idle';
  const backfillActive = backfillMode !== 'idle' && backfillMode !== '';

  const phase: IndexingPhase =
    jobsPending > 0 ? 'indexing' : backfillActive || pending > 0 ? 'enriching' : 'ready';

  return {
    phase,
    jobsPending,
    jobsRunning,
    jobsQueued,
    enrichingPercent,
    enrichingPending: pending,
    embeddingPending: count(enrichment?.embeddingPendingCount),
    vduPending: count(core.pendingVduCount),
    etaSeconds:
      phase === 'indexing' && snapshotLive
        ? deriveEtaSeconds(core.recentDocsPerSec, jobsPending)
        : null,
    live: snapshotLive,
  };
}
