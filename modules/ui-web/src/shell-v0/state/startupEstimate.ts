// SPDX-License-Identifier: Apache-2.0
/**
 * startupEstimate.ts — tempdoc 601: the ONE place the model-load time-estimate is formatted.
 *
 * Maps the inference runtime's `lastStartupDurationMs` (the last successful startup duration in
 * this process lifetime; `-1`/`undefined` when none has completed yet) to a short, approximate
 * estimate string, or `null` when there is no honest basis — the `unknown` arm, which the UI
 * renders as "still starting" with NO number (never a fabricated value).
 *
 * Estimate-only by contract (601 §9): a historical, approximate "usually ready in ~Ns" — never a
 * decrementing countdown. Both consumers read this single derivation so the number is not forked:
 *   - `projectAvailability` (availability.ts) — suffixes the transient `inference.starting` reason
 *     shown on the chat affordance-bar tooltip.
 *   - `formatRestartEtaSub` (BrainSurface.ts) — the Brain surface "Starting…" subtitle.
 *
 * The rounding mirrors the original BrainSurface formatter (tempdoc 518 App. F W3.1), now centralized.
 */
export function formatStartupEstimate(
  lastStartupDurationMs: number | undefined | null,
): string | null {
  if (
    lastStartupDurationMs === undefined ||
    lastStartupDurationMs === null ||
    lastStartupDurationMs < 0
  ) {
    return null;
  }
  // The retrospective estimate floors at 1s and carries the approximate "~" prefix (601 §9).
  return `~${humanizeSeconds(lastStartupDurationMs / 1000, { floor: 1 })}`;
}

/**
 * Tempdoc 601 §19 — the shared "duration → short string" core: `"Ns"` for sub-minute, `"Nm"` /
 * `"Nm Ns"` above. NO prefix and NO suppression — callers add the `~` (estimate) or the `>2s` gate
 * (live count-up) so each surface keeps its own wording. Shared by `formatStartupEstimate` (the
 * retrospective "usually ~Ns") and the live "Starting… Ns" count-up in `computeStatusLabel`, so the
 * minute-aware rounding is not forked (cold model loads can exceed 60s).
 */
export function humanizeSeconds(seconds: number, opts?: { floor?: number }): string {
  const s = Math.max(opts?.floor ?? 0, Math.round(seconds));
  if (s < 60) {
    return `${s}s`;
  }
  const minutes = Math.floor(s / 60);
  const remSec = s % 60;
  return remSec === 0 ? `${minutes}m` : `${minutes}m ${remSec}s`;
}

/**
 * Tempdoc 601 §20 — the single source for "seconds elapsed since a captured load-start stamp" (a
 * measured count-up, never a countdown). Returns 0 when there is no stamp. Shared by the status pill's
 * "Starting… Ns" (`computeStatusLabel`) and the BrainSurface "Starting…" sub, so the elapsed math is
 * not forked across the two surfaces.
 */
export function elapsedSecondsSince(loadStartedAtMs: number | null): number {
  return loadStartedAtMs ? Math.round((Date.now() - loadStartedAtMs) / 1000) : 0;
}
