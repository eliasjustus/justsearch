// SPDX-License-Identifier: Apache-2.0
/**
 * Resolution telemetry subscriber (tempdoc 499 E6).
 *
 * Counts resolution outcomes by (status, transport) dimensions in localStorage.
 * Subscribers are registered via {@link IntentRouter.subscribe()}.
 */

import type { DispatchOutcome, IntentListener } from './intentRouter.js';
import type { Intent } from './types.js';

const STORAGE_KEY = 'jf.resolution-telemetry';
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

interface TelemetryEntry {
  status: string;
  transport: string;
  attemptedId?: string;
  timestamp: number;
}

function loadEntries(): TelemetryEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const entries = JSON.parse(raw) as TelemetryEntry[];
    const cutoff = Date.now() - MAX_AGE_MS;
    return entries.filter(e => e.timestamp > cutoff);
  } catch {
    return [];
  }
}

function saveEntries(entries: TelemetryEntry[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // localStorage full or unavailable — silent degradation
  }
}

export const resolutionTelemetryListener: IntentListener = (
  intent: Intent,
  outcome: DispatchOutcome,
) => {
  const entry: TelemetryEntry = {
    status: outcome.status,
    transport: intent.transport,
    timestamp: Date.now(),
  };
  if (outcome.status === 'unresolved') {
    entry.attemptedId = outcome.attemptedId;
  }
  const entries = loadEntries();
  entries.push(entry);
  saveEntries(entries);
};
