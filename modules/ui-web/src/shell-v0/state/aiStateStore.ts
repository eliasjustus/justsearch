// SPDX-License-Identifier: Apache-2.0
/**
 * aiStateStore — unified AI state model (tempdoc 508).
 *
 * Composes inference poll + status poll + activity signals into a single
 * subscribable snapshot. Replaces per-component partial views of AI state
 * with one source of truth.
 *
 * Consumers subscribe via `subscribeAiState()`. Chat views report activity
 * via `setAiActivity()`. The store subscribes to existing pollers — it does
 * not replace them.
 *
 * Tempdoc 548 §1 / R1a — **state-as-signal (genuine, not a tick bolt-on).**
 * This is the first store converted to the thesis's signal-core pattern:
 *   - The inputs (inference snapshot, status snapshot, last-success stamps,
 *     activity, install state) are `signal`s — the source of truth.
 *   - `aiState` is a `computed` over them: the derived snapshot recomputes
 *     automatically and is memoized, replacing the hand-rolled
 *     `recompute()` + `emit()` orchestration (and its silent-notify
 *     boilerplate). No manual `notify()` call survives.
 *   - `subscribeAiState` is a thin **shim** over a `Signal.subtle.Watcher`:
 *     the signal drives fan-out; the callback contract (incl. the
 *     plugin-style sync call on subscribe) is preserved so consumers and
 *     the (internal-only) API are unchanged. Subsequent updates fan out on
 *     a microtask (signal-idiomatic batching) — the sync-on-subscribe value
 *     is delivered immediately.
 * Time is not a reactive value, so the staleness timer bumps a `clockTick`
 * signal when reachability flips, which the connection derivation reads.
 */

import { signal, computed, Signal } from '@lit-labs/signals';
import {
  subscribeInference,
  setInferenceApiBase,
  type InferenceSnapshot,
} from '../utils/inferencePoll.js';
import {
  subscribeStatus,
  setStatusApiBase,
  type StatusSnapshot,
} from '../utils/statusPoll.js';
import { type Maybe, known, UNKNOWN, mapKnown } from './known.js';
import { humanizeSeconds, elapsedSecondsSince } from './startupEstimate.js';
// Tempdoc 649 — the ONE reachability authority (positive contact across ANY channel), registered as
// the `connection` liveness domain. `aiStateStore` is its sole render site (imports `isOriginReachable`).
import {
  getLastOriginContactMs,
  isOriginReachable,
  bumpOriginContact,
  __resetOriginContactForTest,
} from './originContact.js';
import {
  computeStability,
  computeVerdict,
  verdictHeadline,
  verdictTone,
  type Stability,
  type SystemHealthVerdict,
} from './verdict.js';
import type { NoticeTone } from '../utils/statusTone.js';

// Re-export the raw snapshot types — the store is the single observed-state
// authority, so consumers type `aiState.status` / `aiState.inference` from here.
export type { StatusSnapshot, InferenceSnapshot };

// ── Types ──

export interface AiCapabilities {
  chat: boolean;
  rag: boolean;
  extract: boolean;
  embedding: boolean;
}

export interface AiConnection {
  reachable: boolean;
  /** Last *poll* success (the data-freshness stamp). */
  lastSuccessMs: number | null;
  /**
   * Tempdoc 649 — last POSITIVE CONTACT of any channel (poll success OR an SSE frame/heartbeat), the
   * reachability stamp. Distinct from `lastSuccessMs` (poll freshness): under load the poll can lag while
   * a stream keeps contact fresh. Surfaced so the CONNECTION panel can show both timestamps honestly.
   */
  lastContactMs: number | null;
  consecutiveFailures: number;
}

export interface AiRuntime {
  mode: 'offline' | 'online' | 'indexing' | 'starting' | 'unknown';
  modelId: string | null;
  modelLabel: string | null;
  contextWindow: number | null;
  gpu: { available: boolean; description: string } | null;
  installed: Maybe<boolean>;
  installing: Maybe<boolean>;
  // Tempdoc 601 §20 — wall-clock stamp captured when the model entered the `starting` window
  // (null otherwise). One source for the live "Starting… Ns" elapsed: the status pill
  // (computeStatusLabel) AND BrainSurface read it off the runtime object.
  loadStartedAtMs: number | null;
}

export type ActivityState = 'idle' | 'thinking' | 'streaming' | 'extracting';

export interface AiActivity {
  state: ActivityState;
  shapeId: string | null;
  startedAtMs: number | null;
  canCancel: boolean;
  cancel: (() => void) | null;
}

export interface AiIndex {
  documentCount: Maybe<number>;
  pendingJobs: Maybe<number>;
  embeddingPending: Maybe<number>;
  embeddingBlocked: Maybe<boolean>;
  embeddingQueueSize: Maybe<number>;
  vduQueueSize: Maybe<number>;
}

/**
 * tempdoc 644 — realized retrieval-engine state, one entry per engine, projected by
 * `computeRealized` from `status.worker.gpu`. The single FE authority for "which engine actually
 * loaded, and on which device" (the `accelerator`), so a surface (HealthSurface) reads
 * `aiState.realized.*` instead of re-deriving it from the raw snapshot — the fork-prevention the
 * realized-capability register guards.
 */
export type EngineAccelerator = 'gpu' | 'cpu' | null;
export interface EngineRealized {
  loaded: boolean;
  // null = not loaded, OR the ORT device is not yet probed (lazy init — e.g. the reranker only
  // runs at query time), distinct from a known CPU realization.
  accelerator: EngineAccelerator;
  failureReason: string | null;
}
export interface AiRealized {
  reranker: EngineRealized;
  embed: EngineRealized;
  splade: EngineRealized;
}

export type StatusTier = 'online' | 'degraded' | 'offline' | 'disconnected';

/**
 * Connectivity phase — the single "do we have data yet" authority (§2.B).
 *
 * Four states, distinguished by (have we ever succeeded?) × (is the last
 * success still fresh?):
 *  - `connecting`   — never succeeded yet, still within the grace window.
 *  - `connected`    — last success is fresh.
 *  - `stale`        — succeeded before, but the last success aged out; we hold
 *                     last-known values and show a reconnecting indicator
 *                     (don't wipe to "0 files", don't claim the data is fresh).
 *  - `disconnected` — never succeeded and the grace window elapsed (gave up).
 */
export type ConnectionPhase = 'connecting' | 'connected' | 'stale' | 'disconnected';

/** Projected from the backend's readiness composites — the one degradation authority. */
export interface ReadinessView {
  retrieval: 'ready' | 'degraded' | 'unknown';
  aiFeatures: 'ready' | 'degraded' | 'unknown';
  // Tempdoc 600 Design A: the reindex/compat cause is no longer a separate boolean here — it is
  // carried as a real reason code in `reasonCodes` (index.blocked_legacy / .schema_mismatch / …),
  // emitted on the `retrieval` composite by the worker. The verdict reads it via `reasonCodes`, and
  // `readinessNotice.isReindexCause` recognizes it. This collapses the prior fork (boolean + code).
  reasonCodes: string[];
}

export interface AiState {
  phase: ConnectionPhase;
  readiness: Maybe<ReadinessView>;
  capabilities: AiCapabilities;
  connection: AiConnection;
  runtime: AiRuntime;
  activity: AiActivity;
  index: AiIndex;
  /** tempdoc 644 — realized retrieval-engine state (loaded? GPU/CPU? failure), per engine. */
  realized: AiRealized;
  statusLabel: string;
  statusTier: StatusTier;
  /**
   * Tempdoc 649 — the ONE tone for the status-bar pill + liveness dot, the matched sibling of
   * `statusLabel`: both project from the one verdict (`verdictTone`). Replaces the `statusTier`→tone
   * fork that rendered the calm "Catching up…" (`busy`) state as amber `degraded`. Calm in-flux → `info`,
   * real degradation → `warning`/`error`, settled-online → `success`. (`statusTier` stays for any coarse
   * non-tone use; it no longer drives connection-status colour.)
   */
  statusTone: NoticeTone;
  /**
   * 595 §4.1 — the ONE "is what we're showing settled, or in flux?" axis. Every
   * transition-/freshness-sensitive renderer consults this instead of treating a
   * provisional value (a rebuild's 0 docs, a stale feed) as settled.
   */
  stability: Stability;
  /**
   * 595 §4.2 — the ONE system-health verdict. Header, footer, status bar all
   * consume this (none recompute); `computeStatusLabel`/`computeStatusTier` are
   * its status-bar projections. The `attention` overlay (open recovery
   * conditions) is layered locally by HealthSurface on top of this.
   */
  verdict: SystemHealthVerdict;
  /**
   * The last-known raw poll snapshots (B7). Consumers that need fields beyond
   * the projection above (GPU, memory ceiling, uptime, index state, inference
   * queues) read these instead of running a SECOND status/inference poll — the
   * store is the single observed-state authority. `null` until the first
   * successful poll; retained (not wiped) when the connection goes `stale`.
   */
  status: StatusSnapshot | null;
  inference: InferenceSnapshot | null;
  /**
   * 595 §15.3 (E2) — the last index counts observed while the system was SETTLED. During a
   * provisional window the worker fallback reports a real-but-transient `0` / `UNAVAILABLE`
   * (a *successful* poll, so `ConnectionPhase.stale` retention does not shadow it); renderers
   * show this dimmed "last known" value instead of collapsing to `…`, so a healthy rebuild
   * stops reading as data loss. `null` until the first settled poll. Stamped imperatively in
   * `onStatusUpdate` (the poll callback), never written by a `computed`, so the graph is acyclic.
   */
  lastSettledIndex: { documentCount: number; indexSizeBytes: number | null } | null;
}

const STALE_THRESHOLD_MS = 15_000; // 3x the inference poll interval

const INITIAL_ACTIVITY: AiActivity = {
  state: 'idle',
  shapeId: null,
  startedAtMs: null,
  canCancel: false,
  cancel: null,
};

// ── Input signals (the source of truth) ──

const inferenceSig = signal<InferenceSnapshot | null>(null);
const statusSig = signal<StatusSnapshot | null>(null);
const lastInferenceSuccessSig = signal<number | null>(null);
const lastStatusSuccessSig = signal<number | null>(null);
const activitySig = signal<AiActivity>(INITIAL_ACTIVITY);
const installStateSig = signal<Maybe<{ installed: boolean; installing: boolean }>>(UNKNOWN);
// 595 §15.3 (E2) — retained last-settled index counts. Written ONLY by the poll
// callback (`onStatusUpdate`), read by `buildSnapshot`; no computed writes it.
const lastSettledIndexSig = signal<{ documentCount: number; indexSizeBytes: number | null } | null>(null);
// Time is not reactive; the staleness timer bumps this when reachability
// would flip, so the `connection` derivation re-evaluates.
const clockTickSig = signal(0);
// Tempdoc 601 §19 — wall-clock stamp captured when the model load ENTERS the `starting` window
// (cleared when it leaves), so the status pill can render a live measured "Starting… Ns" count-up,
// mirroring how `activity.startedAtMs` backs "Thinking… Ns". A static timestamp, not a ticking value:
// `computeStatusLabel` computes the elapsed at render, and the existing 5s `clockTick` drives refresh.
const loadStartedAtSig = signal<number | null>(null);

// ── Lifecycle (non-reactive) ──

const listeners = new Set<(s: AiState) => void>();
let unsubInference: (() => void) | null = null;
let unsubStatus: (() => void) | null = null;
let started = false;
let stalenessTimer: number | null = null;
let _startedAtMs = 0;

// ── Derived state computation (reads the input signals) ──

function friendlyModel(id: string | null | undefined): string | null {
  if (!id) return null;
  const stripped = id
    .replace(/[-.](?:(?:I?Q|[Ff])\d[_A-Z0-9]*)\.gguf$/i, '')
    .replace(/\.gguf$/i, '');
  // Q16: present a clean name, not a raw id — underscores → spaces, collapsed.
  return stripped.replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
}

function computeCapabilities(): AiCapabilities {
  const inference = inferenceSig.get();
  const status = statusSig.get();
  const chat = inference?.mode === 'online' && inference?.available === true;
  const docs = status?.worker?.core?.indexedDocuments ?? 0;
  return {
    chat,
    rag: chat && docs > 0,
    extract: chat,
    embedding: status?.embedding?.compatState === 'COMPATIBLE',
  };
}

/**
 * The one staleness derivation — the single source for "have we ever
 * succeeded, and is the last success still fresh". Both `computeConnection`
 * and `computePhase` read this (B3: removes the ~95% duplicated threshold
 * logic that previously lived in each).
 */
interface Staleness {
  lastSuccessMs: number | null;
  /** No success ever AND the start-up grace window has elapsed. */
  neverConnected: boolean;
  /** Succeeded before, but the last success aged past the threshold. */
  stale: boolean;
}

function computeStaleness(): Staleness {
  // Depend on the clock tick so staleness flips re-evaluate this derivation.
  void clockTickSig.get();
  const lastInf = lastInferenceSuccessSig.get();
  const lastStatus = lastStatusSuccessSig.get();
  const lastSuccess =
    lastInf !== null && lastStatus !== null
      ? Math.max(lastInf, lastStatus)
      : (lastInf ?? lastStatus);
  const now = Date.now();
  return {
    lastSuccessMs: lastSuccess,
    neverConnected: lastSuccess === null && started && now - _startedAtMs > STALE_THRESHOLD_MS,
    stale: lastSuccess !== null && now - lastSuccess > STALE_THRESHOLD_MS,
  };
}

/**
 * Tempdoc 649 — REACHABILITY (is the backend alive at all?), derived from the most recent POSITIVE
 * CONTACT of ANY channel: a poll success (`lastInference/StatusSuccessSig`) OR any SSE frame/heartbeat
 * (`getLastOriginContactMs`). This is DELIBERATELY SEPARATE from poll-freshness (`computeStaleness`):
 * under load the cheap polls get starved behind the browser's 6-per-host connection limit, but the
 * always-on SSE streams keep heartbeating (15s) — so a poll-only signal wrongly reads "disconnected"
 * while the backend is provably alive. Reachability via the ONE `isOriginReachable` authority fixes
 * that. Before the first contact, the start-up grace window must elapse before we are willing to call
 * the origin unreachable (mirrors `neverConnected`), so startup shows "Connecting…", not a false alarm.
 */
function computeReachability(): { reachable: boolean; lastContactMs: number | null } {
  // Depend on the clock tick so reachability flips re-evaluate (the stream stamp is a plain global; the
  // 5s `checkStaleness` tick bumps `clockTickSig` when the value would change — see checkStaleness).
  void clockTickSig.get();
  const lastInf = lastInferenceSuccessSig.get();
  const lastStatus = lastStatusSuccessSig.get();
  const lastStream = getLastOriginContactMs();
  const lastContactMs = Math.max(lastInf ?? 0, lastStatus ?? 0, lastStream ?? 0) || null;
  const now = Date.now();
  if (lastContactMs === null) {
    // No contact of any kind yet: not reachable, but not "disconnected" until the grace window elapses.
    return { reachable: started && now - _startedAtMs <= STALE_THRESHOLD_MS, lastContactMs: null };
  }
  return { reachable: isOriginReachable(lastContactMs, now), lastContactMs };
}

function computeConnection(): AiConnection {
  // `lastSuccessMs` stays the last POLL success (the data-freshness stamp consumers read); `reachable`
  // is the contact-based truth (tempdoc 649). They are different facts and must not be conflated.
  const { lastSuccessMs } = computeStaleness();
  const { reachable, lastContactMs } = computeReachability();
  return {
    reachable,
    lastSuccessMs,
    lastContactMs,
    consecutiveFailures: reachable ? 0 : 1,
  };
}

function computeRuntime(): AiRuntime {
  const inference = inferenceSig.get();
  const installState = installStateSig.get();
  const mode = inference?.mode;
  let resolvedMode: AiRuntime['mode'] = 'unknown';
  if (mode === 'online') resolvedMode = 'online';
  else if (mode === 'indexing') resolvedMode = 'indexing';
  else if (mode === 'offline') resolvedMode = 'offline';
  else if (inference?.starting) resolvedMode = 'starting';

  return {
    mode: resolvedMode,
    modelId: inference?.activeModelId ?? null,
    modelLabel: friendlyModel(inference?.activeModelId),
    contextWindow: inference?.llmContextTokens ?? null,
    gpu: inference?.gpu
      ? {
          available: inference.gpu.cudaAvailable ?? false,
          description: inference.gpu.vramDescription ?? '',
        }
      : null,
    installed: mapKnown(installState, (s) => s.installed),
    installing: mapKnown(installState, (s) => s.installing),
    loadStartedAtMs: loadStartedAtSig.get(),
  };
}

function computeStatusLabel(
  verdict: SystemHealthVerdict,
  runtime: AiRuntime,
  act: AiActivity,
): string {
  // 595 §10.5 — the status bar is a PROJECTION of the one verdict, with the
  // transient ACTIVITY overlay layered on top (active work implies a live
  // connection, so it takes precedence). The connection/runtime phrasing
  // ("Connecting…/Reconnecting…/Rebuilding…/Disconnected") now flows from the
  // verdict, not a parallel phase read — so the bar can no longer say "Online"
  // while Health says "Service degraded".
  if (act.state === 'thinking') {
    const elapsed = act.startedAtMs
      ? Math.round((Date.now() - act.startedAtMs) / 1000)
      : 0;
    return elapsed > 2 ? `Thinking… ${elapsed}s` : 'Thinking…';
  }
  if (act.state === 'streaming') return 'Streaming';
  if (act.state === 'extracting') return 'Extracting';
  // Provisional / unreachable phrasing comes from the ONE verdict-wording source
  // (595 §15.1 — `verdictHeadline`), not a parallel switch here, so the status bar
  // and the Health badge cannot drift apart (§2.B: no confident "offline" default
  // while connecting/reconnecting/transitioning).
  if (
    verdict.kind === 'connecting' ||
    verdict.kind === 'unreachable' ||
    verdict.kind === 'transitioning'
  ) {
    return verdictHeadline(verdict);
  }
  // Settled (operational / checking / degraded): keep the familiar runtime label
  // — the verdict's SEVERITY drives the bar's tone (computeStatusTier), so a
  // cosmetic degradation stays calm and an impairing one turns the dot amber.
  if (runtime.mode === 'online' && runtime.modelLabel) {
    return `Online — ${runtime.modelLabel}`;
  }
  if (runtime.mode === 'online') return 'Online';
  if (runtime.mode === 'indexing') return 'Indexing';
  if (runtime.mode === 'starting') {
    // Tempdoc 601 §19 — live MEASURED elapsed (a count-up, never a countdown), mirroring the
    // 'thinking' branch above: the `>2s` gate keeps trivially-fast loads on the bare label, and
    // `humanizeSeconds` gives minute-aware wording for cold loads that exceed 60s.
    const elapsed = elapsedSecondsSince(runtime.loadStartedAtMs);
    return elapsed > 2 ? `Starting… ${humanizeSeconds(elapsed)}` : 'Starting…';
  }
  if (runtime.mode === 'unknown') return 'offline';
  return 'Offline';
}

function computeIndex(): AiIndex {
  const status = statusSig.get();
  const inference = inferenceSig.get();
  const fromStatus = <T>(v: T | undefined | null): Maybe<T> =>
    status === null || v === undefined || v === null ? UNKNOWN : known(v);
  const fromInference = <T>(v: T | undefined | null): Maybe<T> =>
    inference === null || v === undefined || v === null ? UNKNOWN : known(v);
  return {
    documentCount: fromStatus(status?.worker?.core?.indexedDocuments),
    pendingJobs: fromStatus(status?.worker?.core?.pendingJobs),
    embeddingPending: fromStatus(status?.embedding?.pendingCount),
    embeddingBlocked:
      status === null ? UNKNOWN : known((status.embedding?.compatState ?? '').startsWith('BLOCKED')),
    embeddingQueueSize: fromInference(inference?.embeddingQueueSize),
    vduQueueSize: fromInference(inference?.vduQueueSize),
  };
}

/**
 * tempdoc 644 — the ONE realized retrieval-engine projection (reranker / embed / splade), read off
 * `status.worker.gpu`. Exported as the authority the realized-capability register binds to; surfaces
 * consume `aiState.realized.*` rather than re-reading `worker.gpu.*OrtCuda` ad-hoc (the fork-class).
 * `accelerator` distinguishes a known GPU/CPU realization from a not-yet-probed device (lazy ORT
 * init — `available` false but `attempted` false → `null`, not a false "CPU" claim). Per-query stage
 * execution is a different record (the search trace) and is intentionally not projected here.
 */
export function computeRealized(): AiRealized {
  const gpu = statusSig.get()?.worker?.gpu;
  const project = (
    loaded: boolean,
    cuda:
      | { available?: boolean | null; attempted?: boolean | null; failureReason?: string | null }
      | null
      | undefined,
  ): EngineRealized => {
    const accelerator: EngineAccelerator = !loaded
      ? null
      : cuda?.available
        ? 'gpu'
        : cuda?.attempted
          ? 'cpu'
          : null;
    return { loaded, accelerator, failureReason: cuda?.failureReason || null };
  };
  return {
    reranker: project(!!gpu?.rerankerModelPath, gpu?.rerankerOrtCuda),
    embed: project(!!gpu?.embedBackend, gpu?.embedOrtCuda),
    splade: project(!!gpu?.spladeModelPath, gpu?.spladeOrtCuda),
  };
}

function computeStatusTier(
  verdict: SystemHealthVerdict,
  runtime: AiRuntime,
): StatusTier {
  // 595 §10.5 — the status-bar tone is a PROJECTION of the verdict's severity,
  // so it agrees with Health by construction. A cosmetic degradation (severity
  // 'info', e.g. LambdaMART off) stays calm; an impairing one ('warn') turns the
  // dot amber on BOTH surfaces.
  switch (verdict.kind) {
    case 'unreachable':
      return 'disconnected';
    case 'transitioning':
    case 'connecting':
      return 'degraded'; // busy/connecting — a non-green, non-error "in flux" tone
    case 'degraded':
      return verdict.severity === 'info' ? 'online' : 'degraded';
    case 'checking':
    case 'operational':
    default:
      if (runtime.mode === 'online') return 'online';
      if (runtime.mode === 'indexing' || runtime.mode === 'starting') return 'degraded';
      return 'offline';
  }
}

/**
 * Tempdoc 649 — the ONE tone for the status pill + liveness dot, the matched sibling of
 * `computeStatusLabel`. Both the pill and the dot consume this single verdict-derived tone, so `statusTier`
 * is no longer a second tone authority: the calm "Catching up…" (`transitioning`, severity `busy`) projects
 * `verdictTone('busy')='info'` (calm tint) instead of the old `statusTier='degraded'` amber.
 *
 * Scope: this change is intentionally CONNECTION-ONLY. The verdict-driven kinds
 * (`connecting`/`transitioning`/`unreachable`/`degraded`) take their tone from the one `verdictTone`; every
 * NON-connection state keeps its PRE-649 tone:
 *   - AI **activity** ("Thinking…") is NOT special-cased here — its tone falls through to the verdict/runtime
 *     logic, so a "Thinking…" overlay still shows the UNDERLYING health (green healthy / amber degraded), as
 *     before. The label is the only thing the activity overlays (`computeStatusLabel`), not the tone.
 *   - settled `indexing`/`starting` keep their prior **amber** (`warning`) "in-flux" tone (595); only the
 *     connection states were the 649 over-alarm. `online → success`, `offline`/unknown → `neutral`.
 */
function computeStatusTone(verdict: SystemHealthVerdict, runtime: AiRuntime): NoticeTone {
  // Verdict-driven kinds (mirror computeStatusLabel's verdictHeadline branch): tone from the ONE
  // verdict-tone authority — calm `busy` → info, `warn` → warning, `unreachable` (error) → error.
  if (
    verdict.kind === 'connecting' ||
    verdict.kind === 'unreachable' ||
    verdict.kind === 'transitioning' ||
    verdict.kind === 'degraded'
  ) {
    return verdictTone(verdict.severity);
  }
  // Settled (operational / checking): the label is the runtime mode (AI is excluded from the verdict), so
  // tone by the mode — online green, indexing/starting keep the prior amber "in-flux" tone, offline neutral.
  if (runtime.mode === 'online') return 'success';
  if (runtime.mode === 'indexing' || runtime.mode === 'starting') return 'warning';
  return 'neutral';
}

function computePhase(): ConnectionPhase {
  const { lastSuccessMs, neverConnected, stale } = computeStaleness();
  if (lastSuccessMs === null) return neverConnected ? 'disconnected' : 'connecting';
  return stale ? 'stale' : 'connected';
}

function computeReadiness(): Maybe<ReadinessView> {
  const status = statusSig.get();
  if (status === null) return UNKNOWN;
  const composites = status.readiness?.composites ?? {};
  const toState = (k: string): 'ready' | 'degraded' | 'unknown' => {
    const s = composites[k]?.state;
    if (s === 'READY') return 'ready';
    if (s === 'DEGRADED') return 'degraded';
    return 'unknown';
  };
  const reasonCodes = [
    ...(composites['retrieval']?.reasonCodes ?? []),
    ...(composites['aiFeatures']?.reasonCodes ?? []),
  ];
  return known({
    retrieval: toState('retrieval'),
    aiFeatures: toState('aiFeatures'),
    reasonCodes,
  });
}

function buildSnapshot(): AiState {
  const phase = computePhase();
  const readiness = computeReadiness();
  const capabilities = computeCapabilities();
  const connection = computeConnection();
  const runtime = computeRuntime();
  const index = computeIndex();
  const realized = computeRealized();
  const activity = activitySig.get();
  const status = statusSig.get();
  // 595 §4.1/§4.2 — derive the ONE stability axis + verdict, then project the
  // status-bar label/tier from the verdict (no parallel phase/readiness reads).
  const migration = status?.worker?.migration;
  const stability = computeStability({
    phase,
    indexState: status?.worker?.core?.indexState,
    migrationState: migration?.migrationState,
    activeGenerationId: migration?.activeGenerationId,
    buildingGenerationId: migration?.buildingGenerationId,
    servingSearchGenerationId: migration?.servingSearchGenerationId,
    servingIngestGenerationId: migration?.servingIngestGenerationId,
    catchingUp: status?.catchingUp,
    // Tempdoc 649 — poll-stale BUT reachable via another channel ⟹ calm "Catching up…", not "Reconnecting…".
    reachableViaContact: connection.reachable,
  });
  const verdict = computeVerdict({
    phase,
    stability,
    readiness,
    // Tempdoc 649 — distinguishes "no poll yet but origin alive" (Connecting…) from a true unreachable.
    reachableViaContact: connection.reachable,
    // 595 §15.2 (E4) — project the backend's own stuck-rebuild signals so a wedged
    // generation cutover escalates from calm "Rebuilding…" to a warning.
    migrationPaused: migration?.migrationPaused,
    migrationSwitchingAgeMs: migration?.migrationSwitchingAgeMs,
    migrationSwitchingMaxDurationMs: migration?.migrationSwitchingMaxDurationMs,
  });
  const statusLabel = computeStatusLabel(verdict, runtime, activity);
  const statusTier = computeStatusTier(verdict, runtime);
  const statusTone = computeStatusTone(verdict, runtime);
  return {
    phase,
    readiness,
    capabilities,
    connection,
    runtime,
    activity,
    index,
    realized,
    statusLabel,
    statusTier,
    statusTone,
    stability,
    verdict,
    status,
    inference: inferenceSig.get(),
    lastSettledIndex: lastSettledIndexSig.get(),
  };
}

/** The single derived AiState. Recomputes automatically; memoized. */
const aiState = computed<AiState>(buildSnapshot);

// ── subscribe shim: a Signal.subtle.Watcher drives fan-out ──
//
// Replaces the hand-rolled `emit()` (build snapshot + loop listeners called
// at every mutation site). The watcher fires when `aiState` changes; we
// fan out on a microtask (signal-idiomatic batching). The sync value at
// subscribe time is still delivered synchronously (preserves the contract).

let watcherScheduled = false;
let watching = false;
const watcher = new Signal.subtle.Watcher(() => {
  if (watcherScheduled) return;
  watcherScheduled = true;
  queueMicrotask(() => {
    watcherScheduled = false;
    // Canonical re-arm: drain + recompute the pending computeds, then
    // re-watch so the watcher notifies again on the next change.
    for (const s of watcher.getPending()) s.get();
    watcher.watch();
    const snapshot = aiState.get();
    for (const l of listeners) {
      try {
        l(snapshot);
      } catch {
        /* swallow listener errors */
      }
    }
  });
});

function ensureWatching(): void {
  if (watching) return;
  watching = true;
  watcher.watch(aiState);
  aiState.get(); // establish initial dependency tracking
}

// ── Poller integration ──
//
// The existing pollers only notify on success (errors are silently swallowed).
// To detect disconnection, we run a staleness check: if no successful poll has
// arrived in STALE_THRESHOLD_MS, we consider the connection unreachable.

/**
 * Apply a fresh inference snapshot to the input signals. Tempdoc 601 §19 — captures the
 * model-load start (`loadStartedAtSig`) on the `starting` edge so the live "Starting… Ns" count-up
 * has a stamp; shared by the poll callback AND `__feedForTest` so tests exercise the same capture.
 */
function ingestInferenceSnapshot(snap: InferenceSnapshot | null): void {
  const wasStarting = inferenceSig.get()?.starting === true;
  const nowStarting = snap?.starting === true;
  if (nowStarting && !wasStarting) {
    loadStartedAtSig.set(Date.now());
  } else if (!nowStarting && wasStarting) {
    loadStartedAtSig.set(null);
  }
  inferenceSig.set(snap);
  if (snap) lastInferenceSuccessSig.set(Date.now());
}

function onInferenceUpdate(snap: InferenceSnapshot | null): void {
  if (snap) {
    ingestInferenceSnapshot(snap);
  } else {
    // Failed poll: no data change, but a re-evaluation lets the staleness
    // window be reflected (mirrors the old emit-on-null behavior).
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

function onStatusUpdate(snap: StatusSnapshot | null): void {
  if (snap) {
    statusSig.set(snap);
    lastStatusSuccessSig.set(Date.now());
    stampSettledIndex(snap);
  } else {
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

/**
 * 595 §15.3 (E2) — on a SETTLED successful poll, retain the index counts so a later
 * provisional window can show them dimmed. Settled-ness reuses the ONE pure oracle
 * `computeStability` (phase='connected' — we just got a successful poll); a non-settled
 * poll leaves the retained value untouched (do not overwrite good data with the
 * rebuild's transient 0). Imperative-on-input → no signal-graph loop (§17.1).
 */
function stampSettledIndex(snap: StatusSnapshot): void {
  const migration = snap.worker?.migration;
  const stability = computeStability({
    phase: 'connected',
    indexState: snap.worker?.core?.indexState,
    migrationState: migration?.migrationState,
    activeGenerationId: migration?.activeGenerationId,
    buildingGenerationId: migration?.buildingGenerationId,
    servingSearchGenerationId: migration?.servingSearchGenerationId,
    servingIngestGenerationId: migration?.servingIngestGenerationId,
    catchingUp: snap.catchingUp,
  });
  if (stability.kind !== 'settled') return;
  const documentCount = snap.worker?.core?.indexedDocuments;
  if (documentCount == null) return;
  lastSettledIndexSig.set({
    documentCount,
    // Honesty: a size that was never observed stays `null` (renderers show "…" for Size, not
    // a confident "0 B"). Files retention is gated on documentCount above, the primary path.
    indexSizeBytes: snap.worker?.core?.indexSizeBytes ?? null,
  });
}

function checkStaleness(): void {
  // Tempdoc 649 — two time-derived axes can change between input-signal updates: the poll-freshness
  // PHASE (data current vs aged out) and contact-based REACHABILITY (the stream stamp is a plain
  // global). Bump the clock tick when EITHER would flip vs the currently-displayed snapshot, so the
  // memoized `computed` re-evaluates — covering both the calm "Catching up…" transition (poll goes
  // stale while still reachable) and the unreachable transition (all contact ages out).
  const current = aiState.get();
  const phaseNow = computePhase();
  const { reachable: reachableNow } = computeReachability();
  if (phaseNow !== current.phase || reachableNow !== current.connection.reachable) {
    clockTickSig.set(clockTickSig.get() + 1);
  }
}

// ── Public API (unchanged surface) ──

export function startAiStateStore(apiBase: string): void {
  if (started) return;
  started = true;
  _startedAtMs = Date.now();
  setInferenceApiBase(apiBase);
  setStatusApiBase(apiBase);
  unsubInference = subscribeInference(onInferenceUpdate);
  unsubStatus = subscribeStatus(onStatusUpdate);
  stalenessTimer = window.setInterval(checkStaleness, 5000);
}

export function stopAiStateStore(): void {
  unsubInference?.();
  unsubStatus?.();
  unsubInference = null;
  unsubStatus = null;
  if (stalenessTimer !== null) {
    window.clearInterval(stalenessTimer);
    stalenessTimer = null;
  }
  started = false;
}

export function subscribeAiState(listener: (s: AiState) => void): () => void {
  listeners.add(listener);
  ensureWatching();
  try {
    listener(aiState.get());
  } catch {
    /* swallow listener errors */
  }
  return () => {
    listeners.delete(listener);
  };
}

export function getAiState(): AiState {
  return aiState.get();
}

export function setAiActivity(patch: Partial<AiActivity>): void {
  activitySig.set({ ...activitySig.get(), ...patch });
}

export function setInstallState(installed: boolean, installing: boolean): void {
  // Brain surface calls this to feed install state into the unified model.
  installStateSig.set(known({ installed, installing }));
}

/** Test-only: inject a poll snapshot + stamp last-success at the current clock. */
export function __feedForTest(opts: {
  status?: StatusSnapshot | null;
  inference?: InferenceSnapshot | null;
}): void {
  if (opts.status !== undefined) {
    statusSig.set(opts.status);
    if (opts.status) {
      lastStatusSuccessSig.set(Date.now());
      stampSettledIndex(opts.status); // E2 — mirror the production poll-callback stamp.
    }
  }
  if (opts.inference !== undefined) {
    // Tempdoc 601 §19 — route through the shared ingest so the `starting`-edge capture
    // (`loadStartedAtSig`) runs in tests, not just the production poll path.
    ingestInferenceSnapshot(opts.inference);
  }
}

/**
 * Test-only (tempdoc 649): record positive origin contact at `ms` (default now), mirroring an SSE
 * frame/heartbeat, so tests can exercise reachable-but-poll-stale without a live stream. Bump the clock
 * tick so the reachability derivation re-evaluates (the stream stamp is a plain global).
 */
export function __feedContactForTest(ms: number = Date.now()): void {
  bumpOriginContact(ms);
  clockTickSig.set(clockTickSig.get() + 1);
}

/** Test-only: force the staleness derivation to re-evaluate (mirrors the interval). */
export function __tickClockForTest(): void {
  clockTickSig.set(clockTickSig.get() + 1);
}

export function __resetAiStateForTest(): void {
  stopAiStateStore();
  listeners.clear();
  inferenceSig.set(null);
  statusSig.set(null);
  lastInferenceSuccessSig.set(null);
  lastStatusSuccessSig.set(null);
  activitySig.set(INITIAL_ACTIVITY);
  installStateSig.set(UNKNOWN);
  lastSettledIndexSig.set(null);
  clockTickSig.set(0);
  loadStartedAtSig.set(null);
  __resetOriginContactForTest();
  _startedAtMs = 0;
}
