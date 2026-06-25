// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 494 §10.1 + §10.2: class-chrome lookup table for advisory classes.
 * Maps each registered classId to its FE presentation. New advisory classes
 * add one entry; no protocol changes needed.
 *
 * Unknown classIds return the default chrome descriptor (§10.2) — the strict
 * BE-side registry is paired with graceful FE fallback so deployment-skew
 * and future plugin-contributed classes render safely.
 */

export interface AdvisoryClassChromeEntry {
  readonly label: string;
  readonly icon: string;
  readonly toneClass: string;
}

const CHROME_MAP: Record<string, AdvisoryClassChromeEntry> = {
  'operation.completed': {
    label: 'Operation completed',
    icon: '⚡',
    toneClass: 'tone-operation',
  },
  'health.recoverable': {
    label: 'Recoverable condition',
    icon: '🔧',
    toneClass: 'tone-health',
  },
};

const DEFAULT_CHROME: AdvisoryClassChromeEntry = {
  label: 'Advisory',
  icon: 'ℹ',
  toneClass: 'tone-default',
};

const loggedUnknown = new Set<string>();

export function advisoryClassChrome(classId: string): AdvisoryClassChromeEntry {
  const entry = CHROME_MAP[classId];
  if (entry) return entry;
  if (!loggedUnknown.has(classId)) {
    loggedUnknown.add(classId);
    console.warn(`[advisory] Unknown advisory classId: ${classId} — using default chrome`);
  }
  return DEFAULT_CHROME;
}
