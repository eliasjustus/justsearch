// SPDX-License-Identifier: Apache-2.0
/**
 * Wire-contract drift telemetry (tempdoc 683) — "what drifted in prod".
 *
 * In dev a wire-contract mismatch THROWS at the parse boundary (`parseWireContract`), so drift is
 * impossible to miss. In prod the boundary degrades (returns the raw data) — this ring records THAT,
 * so a silent prod degradation still leaves a local, inspectable trace of which endpoint drifted,
 * how often, and when.
 *
 * Sink mirrors `shell-v0/state/availabilityTelemetry.ts` (which mirrors `router/resolutionTelemetry.ts`):
 * a localStorage ring, age-filtered, capped, silent on failure. No remote flush (loopback, no users) —
 * surfaced via the diagnostics export when wanted. The context string is the endpoint identifier
 * (e.g. "POST /api/knowledge/search"); no PII.
 */

const STORAGE_KEY = 'jf.wire-drift-telemetry';
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_ENTRIES = 500;

export interface WireDriftEntry {
  /** The parse-boundary context (endpoint identifier, e.g. "POST /api/knowledge/search"). */
  context: string;
  /** How many Zod issues the mismatch produced (a rough drift-size signal). */
  issueCount: number;
  timestamp: number;
}

function loadEntries(): WireDriftEntry[] {
  try {
    if (typeof localStorage === 'undefined') return [];
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const entries = JSON.parse(raw) as WireDriftEntry[];
    const cutoff = Date.now() - MAX_AGE_MS;
    return entries.filter((e) => e.timestamp > cutoff);
  } catch {
    return [];
  }
}

function saveEntries(entries: WireDriftEntry[]): void {
  try {
    if (typeof localStorage === 'undefined') return;
    // Cap the ring so persistent drift can't grow the key unbounded (keep the most recent).
    const trimmed = entries.length > MAX_ENTRIES ? entries.slice(entries.length - MAX_ENTRIES) : entries;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // localStorage full or unavailable — silent degradation (telemetry must never break a request).
  }
}

/** Record a prod-posture wire-contract mismatch. Called from the parse-boundary degrade path. */
export function recordWireDrift(context: string, issueCount: number): void {
  const entries = loadEntries();
  entries.push({ context, issueCount, timestamp: Date.now() });
  saveEntries(entries);
}

/** Read the recorded drift events (age-filtered). For diagnostics export. */
export function readWireDrift(): WireDriftEntry[] {
  return loadEntries();
}

/** Aggregate drift by context → count (most-drifting first) + the last drift timestamp. */
export function summarizeWireDrift(): {
  byContext: { context: string; count: number }[];
  lastTimestamp: number | null;
} {
  const entries = loadEntries();
  const counts = new Map<string, number>();
  let lastTimestamp: number | null = null;
  for (const e of entries) {
    counts.set(e.context, (counts.get(e.context) ?? 0) + 1);
    if (lastTimestamp === null || e.timestamp > lastTimestamp) lastTimestamp = e.timestamp;
  }
  const byContext = [...counts.entries()]
    .map(([context, count]) => ({ context, count }))
    .sort((a, b) => b.count - a.count);
  return { byContext, lastTimestamp };
}
