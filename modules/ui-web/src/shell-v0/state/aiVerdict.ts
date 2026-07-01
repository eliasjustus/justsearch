// SPDX-License-Identifier: Apache-2.0
/**
 * aiVerdict.ts — the ONE AI-engine lifecycle authority (tempdoc 663).
 *
 * `BrainSurface.deriveAiState()` used to reconcile ~5 overlapping state representations by a
 * hand-ordered precedence ladder: `installStatus`, local `busy[...]` flags,
 * `runtimeStatus.onnxFeatures[].modelActive`, the unified `aiStateStore` runtime, and a separate raw
 * inference snapshot. This module replaces that ladder with a single, pure, unit-testable derivation
 * — the AI-engine sibling of `verdict.ts`'s search-health authority (tempdoc 595).
 *
 * Deliberately NOT merged into `verdict.ts`'s `SystemHealthVerdict`/`ProvisionalCause`: 595 excludes
 * AI from the system-health alarm on purpose (AI is optional, offline-by-design on a fresh install is
 * not a system fault). This module keeps that exclusion while giving AI the SAME single-authority
 * treatment search already has — the original mistake 595 avoided repeating was conflating "don't
 * alarm" with "don't have a single authority." `AiStability` mirrors `verdict.ts`'s
 * `Stability`/`ProvisionalCause` SHAPE (settled | provisional·cause) but is its own closed union,
 * scoped to AI causes — `verdict.ts`'s `ProvisionalCause` lives inside the `verdict-derivation`
 * gate-guarded search-verdict seam, and its exhaustive switches are all search-scoped; adding AI-only
 * causes there would force them to handle cases that can never occur in their own output.
 *
 * Calm-by-default AND resilient to a starved poll (tempdoc 649/662): when no install/runtime data has
 * arrived yet, this never guesses a confident negative - it stays in the calm `connecting` state,
 * keyed off the shared `connection.reachable` signal `aiStateStore` already derives, not a bespoke
 * AI-specific staleness check.
 *
 * Design pass 2 (2026-07-01) - split into two functions so the OBSERVED half can be computed ONCE in
 * `aiStateStore.ts` (a store-level authority, like 594/595/596, not private to `BrainSurface`):
 *   - `computeAiEngineVerdict` - pure function of purely OBSERVED signals (install/runtime/reachable).
 *     Called from `aiStateStore.ts`'s `buildSnapshot()`, exposed as `AiState.aiEngine`.
 *   - `applyLocalIntent` - overlays surface-LOCAL optimistic UI intent (a just-clicked button, before
 *     the poll confirms) on top of the observed result. Deliberately kept OUT of the store: a different
 *     surface must not see BrainSurface's own click as if it were observed truth (the `setAiActivity`
 *     shared-signal precedent was considered and deliberately not reused here - see the design doc §R).
 */
import { isKnown } from './known.js';
import type { AiRuntime, InstallStatus, AiRuntimeStatus } from './aiStateStore.js';

/** Why the AI-engine state is provisional (real-now-but-in-flux). Closed union - every consumer
 * handles it totally. Sibling of `verdict.ts`'s `ProvisionalCause`, not a member of it. */
export type AiProvisionalCause =
  | 'checking' // no install/runtime data yet, but the backend is reachable
  | 'stale-poll' // the backend poll itself is currently stale (no first data yet, OR an
  // already-installed engine's runtime state can no longer be confirmed - Design pass 2)
  | 'installing'
  | 'starting'
  | 'switching-variant';

export type AiStability =
  | { readonly kind: 'settled' }
  | { readonly kind: 'provisional'; readonly cause: AiProvisionalCause };

export type AiEngineKind =
  | 'not_installed'
  | 'installing'
  | 'install_failed'
  | 'offline'
  | 'starting'
  | 'connecting'
  | 'online'
  | 'indexing';

export interface AiEngineVerdict {
  readonly kind: AiEngineKind;
  readonly stability: AiStability;
  /** Set only when `kind === 'install_failed'` - the install service's own error text. */
  readonly installFailure: string | null;
}

/**
 * Purely OBSERVED inputs - install/runtime signals + reachability. No surface-local UI intent (see
 * `AiEngineLocalIntent` below) - this is what `aiStateStore.ts` computes once, from signals it already
 * owns, alongside `stability`/`verdict`/`runtime`.
 */
export interface AiEngineObservedInput {
  readonly installStatus: InstallStatus | null;
  readonly runtimeStatus: AiRuntimeStatus | null;
  readonly runtime: AiRuntime;
  /** `aiStateStore.connection.reachable` (tempdoc 649) - the ONE reachability signal, reused rather
   * than re-derived, so the calm "connecting"/stale-poll states degrade honestly under a starved poll. */
  readonly reachable: boolean;
}

/**
 * The ONE AI-engine lifecycle derivation, computed from purely OBSERVED signals. Pure function (no
 * DOM/IO) - unit-testable, mirroring `computeVerdict`'s style. Called ONCE, in `aiStateStore.ts`'s
 * `buildSnapshot()` (Design pass 2) - not privately by `BrainSurface` or any other consumer, which
 * instead read the result off `AiState.aiEngine` (optionally overlaid via `applyLocalIntent`).
 */
export function computeAiEngineVerdict(input: AiEngineObservedInput): AiEngineVerdict {
  const { installStatus, runtimeStatus, runtime, reachable } = input;

  if (installStatus?.state === 'running') {
    return {
      kind: 'installing',
      stability: { kind: 'provisional', cause: 'installing' },
      installFailure: null,
    };
  }

  if (installStatus?.state === 'failed') {
    return {
      kind: 'install_failed',
      stability: { kind: 'settled' },
      installFailure: installStatus.lastError || installStatus.message || null,
    };
  }

  // The runtime may be active even if install status is stale - a running/indexing/online mode is
  // itself proof of an installed, working engine, independent of whether the install poll has
  // reported back yet (mirrors the original ladder's own reasoning for this ordering).
  if (runtime.mode === 'online') {
    return { kind: 'online', stability: { kind: 'settled' }, installFailure: null };
  }
  if (runtime.mode === 'indexing') {
    return { kind: 'indexing', stability: { kind: 'settled' }, installFailure: null };
  }
  if (runtime.mode === 'starting') {
    return {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'starting' },
      installFailure: null,
    };
  }

  const installed =
    installStatus?.installedFully === true ||
    (runtimeStatus?.onnxFeatures?.some((f) => f.modelActive) ?? false);

  if (!installed) {
    const haveInstallData = installStatus !== null || runtimeStatus !== null || isKnown(runtime.installed);
    if (!haveInstallData) {
      // No-data != not-installed (§2.B). Resilient to a starved poll: while the backend is reachable
      // via any channel, this never flips to a confident "Not Installed" or "Offline" - only the
      // internal cause distinguishes "waiting for the first poll" from "the poll itself is stale."
      return {
        kind: 'connecting',
        stability: { kind: 'provisional', cause: reachable ? 'checking' : 'stale-poll' },
        installFailure: null,
      };
    }
    return { kind: 'not_installed', stability: { kind: 'settled' }, installFailure: null };
  }

  if (runtime.mode === 'transitioning') {
    return {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'starting' },
      installFailure: null,
    };
  }

  // Design pass 2 - resilient to a starved poll (649/§K), extended to the already-installed axis: a
  // genuinely-installed engine whose runtime state we can no longer confirm (the poll has gone stale)
  // must not settle to a confident negative - only this ONE fallback branch needs the check (the
  // online/indexing/starting/transitioning branches above are each themselves direct, specific evidence
  // and correctly stay unconditional, matching 595's own `computeStability` precedent of a low-precedence
  // catch-all, not a check repeated on every branch).
  if (!reachable) {
    return {
      kind: 'offline',
      stability: { kind: 'provisional', cause: 'stale-poll' },
      installFailure: null,
    };
  }
  return { kind: 'offline', stability: { kind: 'settled' }, installFailure: null };
}

/** Surface-LOCAL, transient UI intent - kept OUT of the store (Design pass 2 §R). */
export interface AiEngineLocalIntent {
  /** e.g. `busy['inference-switch']` - a just-clicked variant switch, before the poll confirms it. */
  readonly switching: boolean;
  /** e.g. `busy['install-start']` - a just-clicked install/retry, before the poll confirms it. */
  readonly installStarting: boolean;
}

/**
 * Overlays local, surface-specific optimistic intent on top of the store-computed observed verdict.
 * `BrainSurface` (the one surface with the buttons these flags come from) calls this on its own render
 * path; no other consumer of `AiState.aiEngine` should call it, since these flags are not observed
 * truth. Mirrors the original ladder's exact precedence for both flags (see `aiVerdict.test.ts`).
 */
export function applyLocalIntent(observed: AiEngineVerdict, intent: AiEngineLocalIntent): AiEngineVerdict {
  // Checked first, unconditionally - matches the original ladder's priority for this exact
  // OR-condition. A retry-click after a failure must show "Installing…" immediately, even though the
  // last-known poll still says `failed`; that is what the local intent flag is for.
  if (intent.installStarting) {
    return {
      kind: 'installing',
      stability: { kind: 'provisional', cause: 'installing' },
      installFailure: null,
    };
  }
  // `switching` only matters where the observed axis would otherwise land on a confident "offline" or a
  // bare "starting" (a mode-level `transitioning`, not yet attributed to a variant switch) - it never
  // overrides installing/failed/not_installed/connecting/online/indexing, mirroring the original
  // ladder's placement of this check last, after every other branch.
  if (
    intent.switching &&
    (observed.kind === 'offline' ||
      (observed.kind === 'starting' &&
        observed.stability.kind === 'provisional' &&
        observed.stability.cause === 'starting'))
  ) {
    return {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'switching-variant' },
      installFailure: null,
    };
  }
  return observed;
}

// ---------------------------------------------------------------------------------------------
// Presentation projection (Design pass 3) - mirrors `verdict.ts`'s `verdictHeadline`/`verdictTone`/
// `presentVerdict` EXACTLY, extending `aiEngine` from a single-sourced VALUE (Design pass 2) to a
// single-sourced PRESENTATION (headline/tone/body/announce) - the same 557 Display-to-Presentation
// lineage `verdict.ts` already documents for the search axis, now given to the AI axis. Every consumer
// (the global footer pill via `computeStatusLabel`/`computeStatusTone`, the a11y announcer, the
// completion/failure toast) reads THIS, never `AiEngineKind` directly with its own switch - the same
// discipline that keeps 594/595/596 from re-forking.
//
// Wording/tone reused VERBATIM from `BrainSurface.ts`'s `statusConfig` label/sub table and
// `brainDotTone` (the one place this copy already existed) - not reinvented here.
import type { NoticeTone } from '../components/SystemNotice.js';

/**
 * The ONE human headline for the AI-engine verdict (the footer pill + a11y announcer read this).
 *
 * Wording note: `BrainSurface.ts`'s OWN `statusConfig` table (its status card, a DIFFERENT consumer)
 * independently says "AI Online" for both `online`/`indexing` and carries the online-vs-indexing
 * distinction in its separate SUB-text ("Indexing embeddings…"), not its label. This function serves a
 * DIFFERENT consumer (the footer pill, which has no sub-text slot) that has its own, pre-existing,
 * terser convention ("Online"/"Indexing" as distinct bare words) predating this change — preserved here
 * deliberately, not an oversight or a drift from `statusConfig`. `not_installed`/`installing`/
 * `install_failed`/`connecting` had NO prior footer wording (the old code collapsed all of them to a
 * bare, inconsistently-cased "offline"/"Offline") — those four reuse `statusConfig`'s established text
 * verbatim, since there is no prior footer convention to preserve for them.
 */
export function aiEngineHeadline(v: AiEngineVerdict): string {
  switch (v.kind) {
    case 'not_installed':
      return 'Not Installed';
    case 'installing':
      return 'Installing…';
    case 'install_failed':
      return 'Install Failed';
    case 'offline':
      return 'Offline';
    case 'starting':
      // Callers needing the live "Starting… Ns" count-up (the footer pill) special-case this kind
      // themselves, reading `runtime.loadStartedAtMs` directly - a static headline cannot carry a
      // ticking value. This plain label is still correct for non-live consumers (e.g. the a11y
      // announcer, which announces the moment of transition, not a live count).
      return 'Starting…';
    case 'connecting':
      return 'Connecting…';
    case 'online':
      return 'Online';
    case 'indexing':
      return 'Indexing';
  }
}

/**
 * The ONE tone projection. Mirrors the footer's OWN pre-existing tone convention (`indexing`/`starting`
 * kept deliberately amber/"in-flux", distinct from settled `online`'s green — 595's own comment on the
 * code this replaces: "settled indexing/starting keep their prior amber in-flux tone") — NOT
 * `BrainSurface.ts`'s `brainDotTone`, which collapses `online`/`indexing` to the same dot color because
 * its sub-text already carries the distinction. Same reasoning as `aiEngineHeadline` above: this
 * function serves the footer, which has no sub-text slot, so the tone itself must carry the distinction.
 */
export function aiEngineTone(kind: AiEngineKind): NoticeTone {
  switch (kind) {
    case 'online':
      return 'success';
    case 'indexing':
    case 'starting':
    case 'connecting':
      return 'warning';
    case 'installing':
      return 'info';
    case 'install_failed':
      // Critical-review fix (2026-07-01) — this MUST agree with `core.ai-engine.failed`'s
      // `defaultSeverity: 'error'` (`messageClasses.ts`), the toast fired on reaching this state.
      // Grouping this with `offline`/`not_installed`'s neutral tone (as an earlier draft of this
      // function did) meant a failed install fired one urgent red toast, then settled into a calm,
      // neutral-toned pill for anyone who missed the toast — the SAME state disagreeing with itself
      // across two things this pass built together.
      return 'error';
    case 'offline':
    case 'not_installed':
    default:
      return 'neutral';
  }
}

/** The ONE consequence sentence for the AI-engine verdict (mirrors `statusConfig`'s `sub` text; the
 * `starting` kind's live ETA/elapsed sub-text is caller-specific and not reproduced here). */
export function aiEngineBody(v: AiEngineVerdict): string {
  switch (v.kind) {
    case 'not_installed':
      return 'Install AI models to get started.';
    case 'installing':
      return 'Downloading models.';
    case 'install_failed':
      return v.installFailure || 'Installation failed — try again.';
    case 'offline':
      return 'Start AI to enable chat and summaries.';
    case 'starting':
      return 'AI is initializing.';
    case 'connecting':
      return 'Checking AI status…';
    case 'online':
      return 'Chat and summaries ready.';
    case 'indexing':
      return 'Indexing embeddings…';
  }
}

export interface AiEngineVerdictPresentation {
  readonly tone: NoticeTone;
  readonly headline: string;
  readonly body: string;
  /** The screen-reader announcement of an AI-ENGINE VERDICT CHANGE - mirrors `presentVerdict`'s
   * `announce` exactly (595 §15.1 / E1's pattern, a second instance for the AI axis). `alert`
   * (assertive) only for the one error-toned kind (`install_failed`), else `status` (polite). */
  readonly announce: { readonly text: string; readonly politeness: 'status' | 'alert' };
}

/**
 * The ONE AI-engine verdict-presentation projection (Design pass 3, mirroring `presentVerdict`
 * structurally). Every surface consumes this - the footer pill (headline + tone, via
 * `computeStatusLabel`/`computeStatusTone`), the a11y announcer (`announce`), and (indirectly, via the
 * `AiEngineKind` transition it drives) the completion/failure toast tracker.
 */
export function presentAiEngineVerdict(v: AiEngineVerdict): AiEngineVerdictPresentation {
  const headline = aiEngineHeadline(v);
  const tone = aiEngineTone(v.kind);
  return {
    tone,
    headline,
    body: aiEngineBody(v),
    announce: { text: headline, politeness: v.kind === 'install_failed' ? 'alert' : 'status' },
  };
}
