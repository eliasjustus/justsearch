// SPDX-License-Identifier: Apache-2.0
/**
 * folderStatus.ts — the ONE per-folder indexing-status derivation (tempdoc 599 §9.1).
 *
 * The folder-granularity sibling of `computeVerdict` (595 §4.2): a single pure function that
 * decides a watched folder's truthful state, so every row consumer projects from it instead of
 * re-interpreting raw fields at the render site (the 594/597 single-authority discipline).
 *
 * THE TRUTHFULNESS FIX: `ready` derives from job DRAIN (`inFlight === 0 && failed === 0`), never
 * from the walk-completion timestamp. The wire `status` field is walk-derived and means "scanned",
 * NOT "searchable" — so a folder showing "✓ indexed" while its jobs are still processing (the
 * §8.1 false-positive) is unrepresentable here: a non-empty `inFlightCount` forces `indexing`.
 *
 * Pure + dependency-light: takes the already-formatted relative time and the system `provisional`
 * flag (the caller's projection of the `Stability` axis, mirroring `renderObserved`), so the seam
 * needs no host-utility or store import and is trivially unit-testable.
 *
 * `ready` means KEYWORD-searchable (the folder's index jobs drained). The vector/embedding tier is
 * a separate global backfill (tempdoc 599 §10 / 598) and is intentionally NOT reflected here.
 */

import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';

export type FolderState =
  | 'scanning' // walk in progress — files not yet fully enqueued
  | 'indexing' // in-flight jobs > 0
  | 'ready' // scanned, drained, no failures — keyword-searchable
  | 'unverified' // tempdoc 626 §Axis-C — indexed, but the reconcile couldn't verify deletions (cap-skipped)
  | 'failed' // walk error, or terminal failed jobs with nothing in flight
  | 'empty' // scanned, zero indexable files
  | 'unavailable' // tempdoc 599 §16/A1 — the folder's path is gone (deleted/unmounted), not an error
  | 'unknown'; // system in a global transition (rebuild) — don't assert a terminal fact

/** The glyph vocabulary (`renderStatusIcon` / `FolderCardRenderer.statusIcon`). */
export type FolderGlyph = 'indexed' | 'error' | 'pending' | 'unavailable' | 'unverified';

export interface FolderStatus {
  readonly state: FolderState;
  readonly glyph: FolderGlyph;
  readonly metaText: string;
  readonly inFlight: number;
  readonly failed: number;
}

export interface FolderStatusContext {
  /** Pre-formatted relative time of the last walk (host util), or '' if never. */
  readonly relativeTime: string;
  /**
   * Tempdoc 626 §Recency — pre-formatted relative time of the last reconcile that CONFIRMED index↔disk
   * correspondence (`lastVerifiedIsoTime`, host util), or '' if never verified. Distinct from
   * `relativeTime` (last write): this is the heartbeat that lets a calm "✓" prove it is fresh.
   */
  readonly verifiedRelativeTime: string;
  /** The system `Stability` axis projected to a boolean (`stability.kind === 'provisional'`). */
  readonly provisional: boolean;
}

/**
 * Derive a folder's truthful indexing status from its wire row + the system stability flag.
 * This is the sole site that decides folder searchability — the `check-folder-status-derivation`
 * gate forbids a second `ready`/`searchable` verdict elsewhere in shell-v0.
 */
export function folderStatus(row: IndexedRootView, ctx: FolderStatusContext): FolderStatus {
  const collection = row.collection ?? 'default';
  const fileCount = row.fileCount ?? -1;
  const inFlight = row.inFlightCount ?? 0;
  const failed = row.failedCount ?? 0;
  const walkError = row.walkError ?? '';
  // The folder had files indexed (its walk admitted ≥1 file and a lastIndexed timestamp was set).
  // The walk-derived `status` flips to 'indexed' exactly when lastIndexed is present.
  const indexed = row.status === 'indexed' || !!row.lastIndexedIsoTime;
  // Tempdoc 599 Fix 1 — whether the filesystem walk has TERMINATED at least once. This is the
  // load-bearing distinction: an empty / all-excluded folder is `walkCompleted` with no lastIndexed
  // (→ "empty"), whereas a walk still in progress (or never run) is not (→ "scanning"). Both
  // otherwise look identical on the wire (no lastIndexed, no walkError).
  const walkCompleted = row.walkCompleted === true;
  // Tempdoc 626 §Axis-C — the last reconcile could NOT verify index-vs-disk delete correspondence
  // (the delete-detection scan was cap-skipped for a very large root). Surfaced as a caveat on an
  // otherwise-ready folder: it stays searchable, but we cannot promise stale entries were pruned.
  const deleteDetectionUnverified = row.deleteDetectionUnverified === true;

  const fileCountText =
    fileCount >= 0
      ? `${fileCount.toLocaleString()} ${fileCount === 1 ? 'file' : 'files'}`
      : 'count pending';

  // During a global rebuild, every row is provisional: render busy + last-known, never a terminal
  // fact (595 honesty vocabulary). Highest precedence so a stale "✓ indexed" can't show mid-flux.
  if (ctx.provisional) {
    return {
      state: 'unknown',
      glyph: 'pending',
      metaText:
        fileCount >= 0
          ? `${collection} · Rebuilding… · last known ${fileCountText}`
          : `${collection} · Rebuilding…`,
      inFlight,
      failed,
    };
  }

  // Tempdoc 599 §16/A1 — the folder's path is gone (deleted/unmounted). The controller classifies the
  // path-missing walk failure to status:"unavailable"; render it calmly (a remedy, not an alarm) and
  // BEFORE the generic walkError→failed branch. Re-derived every poll, so a remount silently recovers.
  if (row.status === 'unavailable') {
    // §17.3 — keep the last-known file count visible (last-known, not "0"): the folder isn't empty,
    // it's disconnected. The FE retains the count across live ticks (LibrarySurface), so show it when
    // known rather than erasing the folder's identity.
    return {
      state: 'unavailable',
      glyph: 'unavailable',
      metaText:
        fileCount >= 0
          ? `${collection} · Folder not found — reconnect the drive, or remove it · last known ${fileCountText}`
          : `${collection} · Folder not found — reconnect the drive, or remove it`,
      inFlight,
      failed,
    };
  }

  if (walkError) {
    return { state: 'failed', glyph: 'error', metaText: `${collection} · ${walkError}`, inFlight, failed };
  }

  // In-flight jobs → indexing, even while the walk is still discovering more (a count-down is more
  // useful than "Scanning"). Outranks the scanning branch for this reason.
  if (inFlight > 0) {
    // The `failed` count rides the structured field (rendered as a clickable chip by the row,
    // tempdoc 599 §16/B1) — not baked into the prose meta — so it can open the drill-down.
    return {
      state: 'indexing',
      glyph: 'pending',
      metaText: `${collection} · Indexing · ${inFlight.toLocaleString()} remaining`,
      inFlight,
      failed,
    };
  }

  // Walk not yet terminated and nothing in flight → genuinely scanning (or never walked).
  if (!walkCompleted && !indexed) {
    return { state: 'scanning', glyph: 'pending', metaText: `${collection} · Scanning folder…`, inFlight, failed };
  }

  // Walk done, nothing in flight: surface terminal failures, then ready, then walked-empty.
  if (failed > 0) {
    // `failed` rides the structured field → a clickable chip on the row (tempdoc 599 §16/B1).
    return {
      state: 'failed',
      glyph: 'error',
      metaText: `${collection} · ${fileCountText}`,
      inFlight,
      failed,
    };
  }

  // Drained, indexed, no failures → keyword-searchable. The ONLY path that yields the ✓ glyph.
  if (indexed) {
    // Tempdoc 626 §Axis-C — an indexed folder whose deletions couldn't be verified must NOT show the
    // green ✓ (the 599 false-"✓" class, generalized to reconciliation completeness). It is still
    // searchable, so render a calm "couldn't verify — reindex to be sure", never an alarm.
    if (deleteDetectionUnverified) {
      const indexedSuffix = ctx.relativeTime ? ` · indexed ${ctx.relativeTime}` : '';
      return {
        state: 'unverified',
        glyph: 'unverified',
        metaText: `${collection} · ${fileCountText}${indexedSuffix} · couldn't verify deletions — reindex to be sure`,
        inFlight,
        failed,
      };
    }
    const indexedSuffix = ctx.relativeTime ? ` · indexed ${ctx.relativeTime}` : '';
    // Tempdoc 626 §Recency — the freshness heartbeat. Showing WHEN the index↔disk correspondence was
    // last confirmed turns a bare "✓" into a checkable fact ("Verified 2m ago") and makes a folder the
    // round-robin reconcile hasn't reached lately read as mildly stale ("Verified 8m ago") rather than
    // falsely-fresh. Display-only — the `index.drift-unknown` Condition owns the "needs attention" alarm.
    const verifiedSuffix = ctx.verifiedRelativeTime ? ` · Verified ${ctx.verifiedRelativeTime}` : '';
    return {
      state: 'ready',
      glyph: 'indexed',
      metaText: `${collection} · ${fileCountText}${indexedSuffix}${verifiedSuffix}`,
      inFlight,
      failed,
    };
  }

  // Walk completed, no files admitted, no failures → empty / nothing indexable (tempdoc 599 Fix 1).
  return { state: 'empty', glyph: 'pending', metaText: `${collection} · No indexable files`, inFlight, failed };
}
