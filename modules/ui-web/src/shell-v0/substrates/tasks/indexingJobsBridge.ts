// SPDX-License-Identifier: Apache-2.0
/**
 * Indexing-jobs → Task bridge — Tempdoc 543 §32 #1 (additive follow-up).
 *
 * Projects the backend `core.indexing-jobs` TABULAR Resource stream
 * (GET /api/indexing-jobs/stream, SSE) into the Task substrate (§32 R-E1) so
 * live backend indexing work shows in the ambient Task tray alongside agent
 * operations — a glanceable "what's running now" indicator across the app.
 *
 * READ-ONLY by design (the user's chosen scope): job-tasks carry NO cancel
 * affordance. Cancel/retry stay on the `core.indexing-jobs` Resource view
 * (which owns CANCEL_OP / RETRY_OP) — the tray must not duplicate that control
 * surface. The bridge only OBSERVES.
 *
 * Reconcile (STATELESS — the substrate is the source of truth; privacy-safe,
 * raw paths never on the wire, only pathHash):
 *   - job present, state != FAILED → ensure a running Task (id
 *     `idxjob:<pathHash>`); revives a stale terminal task if the same pathHash
 *     re-appears non-FAILED (e.g. an in-place retry).
 *   - job present, state == FAILED → ensure a failed Task.
 *   - job-task in the substrate but ABSENT from the live set → removeTask
 *     (the job finished / was cleared / the stream reset). It VANISHES rather
 *     than being marked 'succeeded' — correct for a "what's running now" tray
 *     (job history lives in the core.indexing-jobs Resource view), and removal
 *     (vs a guarded terminal transition) is what lets a re-appearing job re-add
 *     cleanly instead of being stranded in a terminal status after a reset.
 *
 * Reuses the canonical TABULAR reducer (`subscriptionStrategy.tabularStrategy`)
 * so frame parsing (snapshot / insert / update / delete / snapshot-replaced) is
 * not re-derived; the bridge owns only the items→Task projection.
 */

import { subscribePooled } from '../../streaming/EnvelopeStreamPool.js';
import type { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../../streaming/shellEventStreamIds.js';
import {
  tabularStrategy,
  type TabularData,
  type StrategyState,
} from '../../strategies/subscriptionStrategy.js';
import {
  upsertMirroredTask,
  listTasks,
  removeTask,
  type TaskStatus,
} from './index.js';
import { isInFlightLive } from './inFlightLiveness.js';
import { resolvePathLazy } from '../../hooks/resolvePathLazy.js';

/** The Resource id whose `privacy.resolver` (core.resolve-path-hash) resolves a job pathHash. */
const INDEXING_JOBS_RESOURCE = 'core.indexing-jobs';

/** Minimal view of the wire record (app-api `IndexingJobView`) the bridge reads. */
interface IndexingJobRow extends Record<string, unknown> {
  readonly pathHash: string;
  readonly state: string;
  readonly collection: string;
  /**
   * Epoch millis of the row's last heartbeat / state transition (the worker's
   * `last_updated` column, refreshed every ~30s by `JobQueue.heartbeatProcessing`
   * while a batch owns the row). Already on the wire (`IndexingJobView.lastUpdatedMs`)
   * and in the SSE payload; surfaced here so the projection can derive liveness
   * (tempdoc 575 §14 — "in-flight derives from a LIVE owner, not stream membership").
   */
  readonly lastUpdatedMs: number;
}

const PRIMARY_KEY = 'pathHash';
const STREAM_PATH = '/api/indexing-jobs/stream';

const TASK_ID_PREFIX = 'idxjob:';

function taskIdFor(pathHash: string): string {
  return `${TASK_ID_PREFIX}${pathHash}`;
}

/**
 * Tempdoc 602 R5 — resolved file/folder labels for the Tasks tray.
 *
 * The job row carries only the file's `pathHash` (SHA-256; raw paths never cross
 * the wire, ADR-0028), so the tray historically showed a 6-char hash —
 * "Indexing · default (3c575b)" — which tells a user nothing about WHICH file is
 * indexing (593 §4). The hash IS resolvable: the worker registers per-file path
 * hashes in its PathResolutionStore as jobs flow, so the existing
 * `core.resolve-path-hash` resolver (the `core.indexing-jobs` Resource's privacy
 * resolver) turns a job pathHash into its path. We resolve lazily + memoized and
 * relabel in place; until a hash resolves (or if it can't), the short-hash label
 * is the graceful fallback. FE-only — no new wire field.
 */
const resolvedLabel = new Map<string, string>(); // pathHash → "parentFolder/fileName"
const resolvingHashes = new Set<string>();
let reproject: (() => void) | null = null;

/** Injectable resolver (default = the real lazy hash resolver), for focused tests. */
let resolveHashFn: (resourceId: string, pathHash: string) => Promise<string | null> = (
  resourceId,
  pathHash,
) => resolvePathLazy(resourceId, pathHash);

/** Test-only: override the hash→path resolver. */
export function __setResolveHashForTest(
  fn: ((resourceId: string, pathHash: string) => Promise<string | null>) | null,
): void {
  resolveHashFn = fn ?? ((r, p) => resolvePathLazy(r, p));
}

/** Test-only: clear the resolved-label cache + in-flight set. */
export function __resetResolvedLabelsForTest(): void {
  resolvedLabel.clear();
  resolvingHashes.clear();
}

/** "C:\a\b\report.pdf" → "b/report.pdf" — the file plus its parent folder for context. */
function friendlyName(path: string): string {
  const parts = path
    .replace(/\\/g, '/')
    .replace(/\/+$/, '')
    .split('/')
    .filter(Boolean);
  if (parts.length === 0) return path;
  const file = parts[parts.length - 1]!;
  const parent = parts.length >= 2 ? parts[parts.length - 2] : '';
  return parent ? `${parent}/${file}` : file;
}

function labelFor(job: IndexingJobRow): string {
  const friendly = resolvedLabel.get(job.pathHash);
  if (friendly) return `Indexing · ${friendly}`;
  // Fallback until the hash resolves (or if the worker has no path on record):
  // collection + a short hex prefix to disambiguate, never the raw path.
  return `Indexing · ${job.collection} (${job.pathHash.slice(0, 6)})`;
}

/**
 * Resolve a job's pathHash → friendly label once (memoized), then ask the bridge
 * to re-project so the task relabels in place with the CURRENT job status. A
 * not-found / unreachable resolve leaves the short-hash fallback untouched.
 */
function ensureResolvedLabel(pathHash: string): void {
  if (resolvedLabel.has(pathHash) || resolvingHashes.has(pathHash)) return;
  resolvingHashes.add(pathHash);
  void resolveHashFn(INDEXING_JOBS_RESOURCE, pathHash)
    .then((path) => {
      if (path) {
        resolvedLabel.set(pathHash, friendlyName(path));
        reproject?.();
      }
    })
    .catch(() => {
      /* keep the short-hash fallback */
    })
    .finally(() => {
      resolvingHashes.delete(pathHash);
    });
}

/**
 * How often the bridge re-derives task status from the last known rows, so a job
 * that STOPS receiving heartbeat frames still crosses the freshness window and
 * demotes (tempdoc 575 §14). Shorter than the liveness window (`IN_FLIGHT_STALE_MS`,
 * owned by `inFlightLiveness.ts`) so the demotion is observed promptly.
 */
const RE_EVAL_INTERVAL_MS = 15_000;

/**
 * Tempdoc 595 §4.4 (closes 1.3) — feed-liveness for the Tasks panel. The panel
 * reads the SSE substrate while the status-bar counter reads the `/api/status`
 * poll; if the SSE stream STALLS while the poll keeps moving, the panel froze
 * silently (same counts/hashes while the bar advanced). This makes that stall an
 * OBSERVED state: when in-flight work exists but no frame has arrived within the
 * window, the panel renders "live updates paused" instead of stale counts. The
 * window is > the re-eval tick + the per-job stale window so it fires only on a
 * real stall, not a normal between-frame gap.
 */
const STALE_FEED_MS = 45_000;

let lastFrameAtMs = 0;
let _feedStalled = false;
const _feedListeners = new Set<(stalled: boolean) => void>();

/**
 * Pure: the feed is stalled iff in-flight work exists AND a frame has arrived
 * before (lastFrameMs>0) AND none has arrived within the window. A feed that has
 * never delivered a frame is "not started", NOT "stalled".
 */
export function isFeedStalled(
  lastFrameMs: number,
  now: number,
  items: ReadonlyMap<string, IndexingJobRow>,
): boolean {
  if (lastFrameMs === 0) return false;
  let hasInflight = false;
  for (const job of items.values()) {
    const st = job.state.toUpperCase();
    if (st === 'PENDING' || st === 'PROCESSING') {
      hasInflight = true;
      break;
    }
  }
  return hasInflight && now - lastFrameMs > STALE_FEED_MS;
}

function setFeedStalled(v: boolean): void {
  if (v === _feedStalled) return;
  _feedStalled = v;
  for (const l of _feedListeners) {
    try {
      l(v);
    } catch {
      /* swallow listener errors */
    }
  }
}

/** Subscribe to the indexing-jobs feed-stall state (sync current value on subscribe). */
export function subscribeFeedStalled(cb: (stalled: boolean) => void): () => void {
  _feedListeners.add(cb);
  cb(_feedStalled);
  return () => {
    _feedListeners.delete(cb);
  };
}

/** Test-only reset of the feed-liveness state. */
export function __resetFeedHealthForTest(): void {
  lastFrameAtMs = 0;
  _feedStalled = false;
  _feedListeners.clear();
}

/**
 * Map a backend job state to its TRUE Task lifecycle status (tempdoc 550 Thesis
 * II + 575 §14). The running-signal is a job in PROCESSING **whose owner is still
 * live** — derived from heartbeat freshness (`lastUpdatedMs`), NOT mere membership
 * in the PROCESSING set. A job whose owner stopped beating (a wedged loop in a
 * live worker) goes stale and is demoted to `queued` ("in-flight derives from a
 * live owner, not stream membership") rather than asserting a phantom RUNNING pill
 * until the worker-side reaper acts. PENDING is `queued`; DONE is terminal-success
 * → history (returns null so the rail vanishes it). The F-1 defect was collapsing
 * every non-FAILED state into `running`.
 */
function statusFor(job: IndexingJobRow): TaskStatus | null {
  switch (job.state.toUpperCase()) {
    case 'PROCESSING':
      // Tempdoc 575 §15: derive RUNNING from the ONE liveness authority, never inline.
      return isInFlightLive(job.lastUpdatedMs) ? 'running' : 'queued';
    case 'FAILED':
      return 'failed';
    case 'DONE':
      return null; // terminal success = history; vanish from the live rail
    case 'PENDING':
      return 'queued';
    default:
      // Tempdoc 550 Thesis II truthfulness: an UNRECOGNIZED backend state must not be silently
      // asserted as a known status. The worker enum is PENDING/PROCESSING/DONE/FAILED; anything
      // else means the wire contract drifted — surface it once rather than mislabel. We render it
      // as `queued` (non-running, least-wrong) AND warn so the drift is visible, not hidden.
      warnUnknownState(job.state);
      return 'queued';
  }
}

const _warnedStates = new Set<string>();
function warnUnknownState(state: string): void {
  if (_warnedStates.has(state)) return;
  _warnedStates.add(state);
  // eslint-disable-next-line no-console
  console.warn(
    `[indexingJobsBridge] unrecognized job state ${JSON.stringify(state)} — ` +
      `rendering as 'queued'. The worker enum (PENDING/PROCESSING/DONE/FAILED) may have drifted.`,
  );
}

/**
 * Reconcile the Task substrate to the current live job set. STATELESS: the
 * source of truth for "which job-tasks exist" is the substrate itself (tasks
 * whose id carries the `idxjob:` prefix), NOT a closure set. Each job renders
 * its TRUE lifecycle state (queued / running / failed); a DONE job vanishes
 * (success is history, not live work); a job ABSENT from the live set vanishes
 * too. `running` is asserted only for jobs actually in PROCESSING — so a backlog
 * of PENDING jobs renders as `queued` (bounded + grouped by <jf-task-list>),
 * never as a wall of perpetual RUNNING pills (the F-1 cure).
 *
 * Deriving the existing set from `listTasks()` each call means a fresh bridge
 * instance (after a remount) reconciles against whatever tasks already exist —
 * no orphaning. Exported for focused unit testing of the projection lifecycle.
 */
export function projectJobsToTasks(
  items: ReadonlyMap<string, IndexingJobRow>,
): void {
  const present = new Set<string>();
  for (const [pathHash, job] of items) {
    const id = taskIdFor(pathHash);
    const status = statusFor(job);
    if (status === null) {
      // Terminal-success (DONE): drop from the live rail (history, not work).
      removeTask(id);
      continue;
    }
    present.add(id);
    // Tempdoc 609 §R (T1.3) — indexing is managed from Library, so the task row returns there.
    upsertMirroredTask({ id, label: labelFor(job), status, originSurfaceId: 'core.library-surface' });
    // 602 R5 — lazily resolve the file's pathHash → a friendly name; the resolve
    // callback re-projects so this task relabels in place with its current status.
    ensureResolvedLabel(pathHash);
  }
  // Job-tasks no longer in the live set → remove (vanish), regardless of their
  // current status. Derived from the substrate so this is correct after a
  // remount (no reliance on per-instance closure state).
  for (const t of listTasks()) {
    if (t.id.startsWith(TASK_ID_PREFIX) && !present.has(t.id)) {
      removeTask(t.id);
    }
  }
}

/**
 * Start the bridge. Subscribes to the indexing-jobs SSE stream and projects
 * each snapshot into the Task substrate; returns a stop function.
 *
 * Pooled (tempdoc 543 residue #3): the subscription goes through
 * `EnvelopeStreamPool` keyed by the stream URL, so the always-on tray bridge
 * and an open `core.indexing-jobs` Resource view (same endpoint
 * `/api/indexing-jobs/stream`, same `pathHash` primaryKey → same tabular
 * reducer shape) share ONE EventSource instead of opening two. The returned
 * stop is the pool's idempotent unsubscribe (last release closes the stream).
 * Tempdoc 662: when `opts.multiplex` is supplied, the bridge instead subscribes
 * the `surface:indexing-jobs` streamId on the shared `MultiplexedStream` — one
 * of the 5 always-on streams collapsed onto `/api/shell-events/stream` (the
 * fix for the browser connection-pool exhaustion that starved the cheap status
 * polls under load, tempdoc 649). Note: an open `core.indexing-jobs` Resource
 * view (via `ResourceView.ts`'s OWN `subscribePooled` call, unmigrated — out
 * of this tempdoc's scope) no longer shares a socket with this bridge in that
 * case; it opens its own, same as before 662 for any non-bridge consumer.
 *
 * SSR/headless-safe: when no `eventSourceFactory`/`multiplex` is supplied and
 * the global `EventSource` is unavailable, the bridge is a no-op (returns a
 * stop fn that does nothing) — it never tries to connect.
 */
export function startIndexingJobsBridge(
  apiBase: string,
  opts: {
    eventSourceFactory?: (url: string) => EventSource;
    multiplex?: MultiplexedStream;
  } = {},
): () => void {
  if (!opts.multiplex && !opts.eventSourceFactory && typeof EventSource === 'undefined') {
    return () => {};
  }
  const url = `${(apiBase || '').replace(/\/$/, '')}${STREAM_PATH}`;
  // The last known job set — captured so the staleness re-tick can re-project it
  // even when no new SSE frame arrives (tempdoc 575 §14).
  let latestItems: ReadonlyMap<string, IndexingJobRow> = new Map();
  // 602 R5 — a late pathHash resolve relabels in place by re-projecting the
  // latest rows (so the task keeps its CURRENT status, not a stale captured one).
  reproject = () => projectJobsToTasks(latestItems);
  const onSnapshot = (snap: { payload: StrategyState<TabularData<IndexingJobRow>> }): void => {
    latestItems = snap.payload.data.items;
    // 595 §4.4: a fresh frame stamps liveness and clears any stall.
    lastFrameAtMs = Date.now();
    setFeedStalled(false);
    projectJobsToTasks(latestItems);
  };
  const stopSub = opts.multiplex
    ? opts.multiplex.subscribe<StrategyState<TabularData<IndexingJobRow>>>(
        SHELL_EVENT_STREAM_IDS.INDEXING_JOBS,
        () => {
          const strat = tabularStrategy<IndexingJobRow>(PRIMARY_KEY);
          return { initialState: strat.initialState, reducer: strat.reducer };
        },
        onSnapshot,
      )
    : subscribePooled<StrategyState<TabularData<IndexingJobRow>>>(
        url,
        onSnapshot,
        () => {
          const strat = tabularStrategy<IndexingJobRow>(PRIMARY_KEY);
          return {
            url,
            initialState: strat.initialState,
            reducer: strat.reducer,
            ...(opts.eventSourceFactory
              ? { eventSourceFactory: opts.eventSourceFactory }
              : {}),
          };
        },
      );
  // Tempdoc 575 §14: re-derive staleness on a tick. A job that STOPS getting
  // heartbeat frames (a wedged loop → no new Delta.Update) would otherwise keep
  // `running` forever; re-projecting the last known rows lets it cross the
  // freshness window and demote to `queued` without a new frame. Cheap when idle
  // (empty item set → no-op).
  const tick = setInterval(() => {
    projectJobsToTasks(latestItems);
    // 595 §4.4: the same tick re-evaluates feed-staleness — if in-flight work
    // exists but no frame has arrived within the window, mark the feed stalled.
    setFeedStalled(isFeedStalled(lastFrameAtMs, Date.now(), latestItems));
  }, RE_EVAL_INTERVAL_MS);
  return () => {
    clearInterval(tick);
    reproject = null;
    stopSub();
  };
}
