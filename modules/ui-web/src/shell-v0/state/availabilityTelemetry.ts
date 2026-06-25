// SPDX-License-Identifier: Apache-2.0
/**
 * Availability telemetry (tempdoc 596 §16.5) — "what blocks users".
 *
 * Because availability is projected through ONE authority (`projectAvailability` / `unavailableBecause`)
 * and surfaced through ONE primitive (`jf-control`), there is a single choke point to observe: a user
 * ATTEMPTING a blocked affordance. We record THAT (not every render — that would be noise), so the data
 * answers "how often, and for what reason, is a user stopped?" — a free product-health signal.
 *
 * Sink mirrors `router/resolutionTelemetry.ts`: a localStorage ring, age-filtered, silent on failure. No
 * remote flush (loopback, no users) — the data is local, surfaced via the diagnostics export when wanted.
 * The reason string is the block identifier; no PII (these are generic system reasons), so it is safe to
 * persist. Pre-production value is near-zero by design; this is the substrate for when there ARE users.
 */

const STORAGE_KEY = 'jf.availability-telemetry';
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 500;

export interface AvailabilityBlockEntry {
  /** The reason the affordance was unavailable (the block identifier). */
  reason: string;
  /** Whether the block was transient (still loading) vs settled — distinguishes "wait" from "stuck". */
  transient: boolean;
  timestamp: number;
}

function loadEntries(): AvailabilityBlockEntry[] {
  try {
    if (typeof localStorage === 'undefined') return [];
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const entries = JSON.parse(raw) as AvailabilityBlockEntry[];
    const cutoff = Date.now() - MAX_AGE_MS;
    return entries.filter((e) => e.timestamp > cutoff);
  } catch {
    return [];
  }
}

function saveEntries(entries: AvailabilityBlockEntry[]): void {
  try {
    if (typeof localStorage === 'undefined') return;
    // Cap the ring so a long session can't grow the key unbounded (keep the most recent).
    const trimmed = entries.length > MAX_ENTRIES ? entries.slice(entries.length - MAX_ENTRIES) : entries;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // localStorage full or unavailable — silent degradation (telemetry must never break a click).
  }
}

/** Record that a user attempted an affordance that was unavailable. Called from the one block site. */
export function recordBlockedAttempt(reason: string, transient: boolean): void {
  const entries = loadEntries();
  entries.push({ reason, transient, timestamp: Date.now() });
  saveEntries(entries);
}

/** Read the recorded blocks (age-filtered). For diagnostics export / a future "what blocks users" view. */
export function readAvailabilityTelemetry(): AvailabilityBlockEntry[] {
  return loadEntries();
}

/** Aggregate blocks by reason → count (most-blocking first). The product-health summary. */
export function summarizeBlocks(): { reason: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const e of loadEntries()) counts.set(e.reason, (counts.get(e.reason) ?? 0) + 1);
  return [...counts.entries()]
    .map(([reason, count]) => ({ reason, count }))
    .sort((a, b) => b.count - a.count);
}
