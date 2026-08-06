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

/**
 * 813 §4 — which PARENT enrichment stages are applicable to this deployment, index-wide.
 *
 * The per-root wire row (`IndexedRootView`) carries coverage COUNTS but no enabled flags, so a
 * per-root percent has no way of its own to learn that (say) SPLADE is switched off — and a
 * disabled stage's permanently-unsettled documents would pin every folder below 100% forever
 * (the index-wide `stage()` guard below, applied at folder granularity). This is the ONE place
 * applicability is decided, so the per-root derivation consumes it instead of re-reading the
 * `*Enabled` wire flags itself.
 *
 * UNKNOWN applicability is `null`, never an all-false object: all-false is a legible claim ("no
 * stage applies here"), and a consumer that reads it as such renders a coverage tier off whatever
 * counts survive — the {@link EMPTY} snapshot's own defect before this was corrected. The
 * synchronous first callback of `subscribeAiState` delivers exactly that pre-poll snapshot to every
 * consumer on mount, so this is the production path, not an edge case.
 */
export interface EnrichmentApplicability {
  readonly embedding: boolean;
  readonly splade: boolean;
  readonly ner: boolean;
}

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
   *  - a result beyond {@link ETA_MAX_SECONDS}, which a trailing-3-sample window cannot support;
   *  - a backlog that is still GROWING (a walk is enqueueing faster than the queue drains), where
   *    "remaining / rate" is extrapolating a denominator that has not stopped moving.
   * Doc-count-based by construction and therefore never promoted to a countdown: {@link
   * pendingBytes} is the weight of the same backlog, but sizes are recorded per job and the two are
   * deliberately not combined into a byte-rate, so on a mixed corpus this stays an
   * order-of-magnitude hint at best.
   */
  etaSeconds: number | null;
  /**
   * 813 Slice B — the byte weight of the remaining job backlog (`worker.core.pendingBytes`), or
   * `null` when it would not be faithful: nothing recorded, the worker could not compute the
   * aggregate, or more than half the remaining jobs carry no recorded size
   * (`pendingUnknownSizeJobs`), where the sum understates the backlog enough to mislead.
   */
  pendingBytes: number | null;
  /**
   * Is this snapshot still a LIVE observation? Threaded in from the ONE liveness authority
   * (`AiState.snapshotLive`, 807 A.3) — this module does NOT invent a staleness signal, and in
   * particular never reads `chunkCoverage.observedAtMs`, which is a Head serialization stamp.
   */
  live: boolean;
  /**
   * 813 §4 — the applicable parent enrichment stages (see {@link EnrichmentApplicability}). Read by
   * the per-root folder derivation (`folderStatus`), which has counts but no enabled flags.
   * `null` ⟹ applicability is UNKNOWN and NO consumer may claim a coverage tier from it.
   */
  stages: EnrichmentApplicability | null;
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
  pendingBytes: null,
  live: false,
  // Nothing was reported ⟹ applicability is UNKNOWN ⟹ no per-root percent may be asserted. `null`,
  // not all-false: all-false reads as "no stage applies", which is itself a claim.
  stages: null,
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
 * Is the job backlog still GROWING? Read off the last two samples of the SAME snapshot's
 * `worker.core.recentJobQueueDepth` trend, so it needs no cross-poll memory.
 *
 * While a walk is still enqueueing, "remaining / rate" divides a denominator that is still rising —
 * the estimate counts UP as the user watches it, which is worse than no estimate (813 §5b: coarse
 * or absent, never wrong). A refutation only: a trend too short to compare cannot establish growth,
 * so it leaves the other suppressions to decide.
 */
function backlogGrowing(depths: readonly number[] | null | undefined): boolean {
  if (!depths || depths.length < 2) return false;
  const last = depths[depths.length - 1];
  const prev = depths[depths.length - 2];
  if (typeof last !== 'number' || typeof prev !== 'number') return false;
  if (!Number.isFinite(last) || !Number.isFinite(prev)) return false;
  return last > prev;
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
  queueDepths: readonly number[] | null | undefined,
  jobsPending: number,
): number | null {
  if (jobsPending < ETA_MIN_JOBS) return null;
  if (backlogGrowing(queueDepths)) return null;
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
 * The remaining backlog's byte weight, or `null` when it is not faithful (see {@link
 * IndexingProgress.pendingBytes}).
 *
 * `knownBytes <= 0` covers both "nothing recorded" and the worker's UNAVAILABLE marker (which
 * reports zero bytes plus `unknownSizeJobs = -1`, normalized to 0 by {@link count}). The
 * half-the-backlog rule is the same discipline as the enrichment percent: a denominator that is
 * mostly guesses is not a denominator.
 */
function derivePendingBytes(
  knownBytes: number,
  unknownSizeJobs: number,
  jobsPending: number,
): number | null {
  // A weight with no backlog is a contradiction (a residue of one poll's aggregate against
  // another's count); the backlog is the subject, so no backlog means no claim.
  if (jobsPending <= 0) return null;
  if (knownBytes <= 0) return null;
  if (unknownSizeJobs * 2 > jobsPending) return null;
  return knownBytes;
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

  // The ONE applicability decision (813 §4): consumed both by the index-wide stage math below and,
  // via `IndexingProgress.stages`, by the per-root folder derivation — so "is SPLADE on?" is
  // answered once for every progress surface.
  const applicable: EnrichmentApplicability = {
    embedding: enrichment?.embeddingEnabled !== false,
    splade: enrichment?.spladeEnabled !== false,
    ner: enrichment?.nerEnabled !== false,
  };

  const stages: Array<StageWork | null> = [
    stage(
      count(enrichment?.embeddingDocCount),
      count(enrichment?.embeddingPendingCount),
      applicable.embedding,
    ),
    stage(
      count(enrichment?.spladeDocCount),
      count(enrichment?.spladePendingCount),
      applicable.splade,
    ),
    // NER reports no doc-count, so its denominator is its own two-valued census.
    stage(nerCompleted + nerPending, nerPending, applicable.ner),
    // The chunk tier's denominator is CHUNKED documents (813 §13) — never "N of M files". Its
    // applicability is the EMBEDDING stage's: chunk vectors come from the same encoder, so a
    // deployment with embedding switched off (or no embedding service at all) never settles a
    // single chunk. Passing "unknown" here left those chunks pending forever ⟹ phase stuck at
    // `enriching` ⟹ "Up to date" / "System idle" / folder "fully searchable" unreachable.
    stage(
      count(chunk?.chunkDocCount),
      count(chunk?.chunkEmbeddingPendingCount),
      applicable.embedding,
    ),
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

  // Positive-evidence phase gate (merged from #375's enrichmentCoverage doctrine, 813 §17): pending
  // work on an APPLICABLE stage withholds the terminal phase even when that stage lacks a faithful
  // denominator — a denominator-less stage contributes no PERCENT, but its pending counter is still
  // evidence of unfinished work, and "Up to date" off a missing denominator would be the §1d false
  // terminal. Disabled stages stay excluded: their pending can never settle (813 review F1), so
  // counting them would pin the phase at `enriching` forever.
  const rawPending =
    (applicable.embedding ? count(enrichment?.embeddingPendingCount) : 0) +
    (applicable.splade ? count(enrichment?.spladePendingCount) : 0) +
    (applicable.ner ? nerPending : 0) +
    (applicable.embedding ? count(chunk?.chunkEmbeddingPendingCount) : 0);

  const phase: IndexingPhase =
    jobsPending > 0 ? 'indexing' : backfillActive || rawPending > 0 ? 'enriching' : 'ready';

  return {
    phase,
    jobsPending,
    jobsRunning,
    jobsQueued,
    enrichingPercent,
    // The displayed pending count matches the phase evidence (rawPending), not the percent's
    // stage-filtered sum — a surface saying "enriching" must be able to show the work it saw.
    enrichingPending: rawPending,
    embeddingPending: count(enrichment?.embeddingPendingCount),
    vduPending: count(core.pendingVduCount),
    etaSeconds:
      phase === 'indexing' && snapshotLive
        ? deriveEtaSeconds(core.recentDocsPerSec, core.recentJobQueueDepth, jobsPending)
        : null,
    pendingBytes: derivePendingBytes(
      count(core.pendingBytes),
      count(core.pendingUnknownSizeJobs),
      jobsPending,
    ),
    live: snapshotLive,
    stages: applicable,
  };
}
