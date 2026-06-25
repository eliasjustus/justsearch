// SPDX-License-Identifier: Apache-2.0
/**
 * draftKeptHint — tempdoc 609 §R (T1.4): the ambient "Draft kept" reassurance.
 *
 * Instance-retention silently preserves an unsaved draft when you navigate away from a surface. This makes
 * that invisible guarantee LEGIBLE (Google-Docs' "All changes saved" ambient signal): a one-shot toast on
 * the single ephemeral-message channel when you leave a surface whose draft is non-empty.
 *
 * Teach, don't nag: shown at most once per surface per session (a module-level Set), so a user who switches
 * away from the chat composer fifty times sees the hint once, then trusts it.
 */
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';

const notified = new Set<string>();

/**
 * Emit "Draft kept" for `surfaceKey` if a draft exists there and the hint hasn't fired for it this session.
 * @param hasDraft whether the surface currently holds a non-empty recoverable draft.
 */
export function notifyDraftKeptOnce(surfaceKey: string, hasDraft: boolean): void {
  if (!hasDraft || notified.has(surfaceKey)) return;
  notified.add(surfaceKey);
  emitEphemeralToast({ classId: 'core.draft-kept', message: 'Draft kept' });
}

/** Test-only reset of the once-per-session memory. */
export function __resetDraftKeptForTest(): void {
  notified.clear();
}
