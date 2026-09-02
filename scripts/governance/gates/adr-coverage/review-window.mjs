/**
 * ADR review-window arithmetic — the ONE place the `last_reviewed` cadence is computed
 * (tempdoc 884 PART E).
 *
 * Design decision 4 of tempdoc 884 says review staleness must surface where agents look,
 * which means two consumers: the `adr-coverage` kernel gate (CI/pre-merge) and
 * `scripts/agent-analytics/world-state.mjs` (session-start orientation). Two consumers of
 * one rule is exactly the representation-fork this repo's governance exists to prevent, so
 * the date normalization, the day arithmetic and the window default live here rather than
 * being restated per consumer.
 *
 * `governance/adr-probes.v1.json` declares `reviewStaleDays`; `REVIEW_STALE_DAYS_DEFAULT`
 * is the fallback for when the register is absent or unparseable, not a second authority.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Fallback window when the register cannot be read. The register's value wins. */
export const REVIEW_STALE_DAYS_DEFAULT = 183;

export const PROBE_REGISTER_PATH = 'governance/adr-probes.v1.json';

/** Files under `docs/decisions/` that are not decisions. The index is not an ADR. */
export const NON_ADR_FILES = new Set(['README.md']);

/**
 * gray-matter yields a `Date` for an unquoted YAML date and a string for a quoted one;
 * normalize either to `YYYY-MM-DD`. Returns `null` for absent/empty.
 *
 * @param {unknown} value
 * @returns {string|null}
 */
export function normalizeDate(value) {
  if (value === null || value === undefined || value === '') return null;
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return String(value).trim();
}

/**
 * Whole days elapsed from `isoDate` to `nowMs`. `null` when the date is unparseable —
 * "unknown", never 0, so a malformed date can't read as freshly reviewed.
 *
 * @param {string} isoDate
 * @param {number} nowMs
 * @returns {number|null}
 */
export function daysSince(isoDate, nowMs) {
  const t = Date.parse(isoDate);
  if (Number.isNaN(t)) return null;
  return Math.floor((nowMs - t) / 86_400_000);
}

/**
 * The declared review window in days, read from the probe register.
 * Falls back to `REVIEW_STALE_DAYS_DEFAULT` if the register is missing/unparseable or
 * declares a non-positive value.
 *
 * @param {string} repoRoot
 * @param {string} [registerPath] repo-relative
 * @returns {number}
 */
export function loadReviewStaleDays(repoRoot, registerPath = PROBE_REGISTER_PATH) {
  try {
    const raw = JSON.parse(readFileSync(resolve(repoRoot, registerPath), 'utf8'));
    const declared = Number(raw?.reviewStaleDays);
    if (Number.isFinite(declared) && declared > 0) return declared;
  } catch {
    /* fall through to the default */
  }
  return REVIEW_STALE_DAYS_DEFAULT;
}
