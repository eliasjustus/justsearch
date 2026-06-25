// SPDX-License-Identifier: Apache-2.0
/**
 * Capability Consent substrate — Tempdoc 543 §14.3 β4 / §25.β4.
 *
 * Per-(contributor, capability, scope) allow/deny store. Today plugins
 * declare capabilities at install time and receive them all-or-nothing
 * (per PluginCapabilities manifest field). Real agent ecosystems grant
 * capabilities INCREMENTALLY — at first request, the user sees:
 *   - allow-once (this invocation only)
 *   - allow-always (persist consent for future invocations)
 *   - deny (this and future invocations)
 *
 * This substrate stores those decisions and exposes
 * `checkCapability(contributorId, capability)` for sandbox / handler
 * gating. When the answer is 'undecided', the gate caller pumps the
 * `<jf-consent-host>` chrome surface (via the requestCapability flow)
 * which surfaces a kernel-owned modal and returns a typed Promise.
 *
 * Persistence: decisions persist to localStorage so allow-always /
 * deny survive sessions. allow-once entries decay at process exit.
 *
 * Closure: §13.6 #5 partial — lifecycle-and-consent are separate
 * concerns but the consent store is the data structure that the V1.5
 * plugin sandbox (separate tempdoc) will read at runtime invocation
 * to enforce. Shipping the FE store + chrome means when the runtime
 * lands, the FE half is already in place.
 */

import { safeLocalStorage } from '../../primitives/storage.js';
import { notifyAll } from '../../primitives/notify.js';

export type ConsentDecision = 'allow-once' | 'allow-always' | 'deny';

export interface ConsentRecord {
  readonly contributorId: string;
  readonly capability: string;
  readonly decision: ConsentDecision;
  /** ISO-8601 timestamp of the decision. */
  readonly decidedAt: string;
}

const PERSIST_KEY = 'justsearch.consent.v1';

// In-memory store keyed by `${contributorId}\0${capability}`.
const _consents = new Map<string, ConsentRecord>();
const _listeners = new Set<() => void>();

// Pending requestCapability promises keyed by request id.
let _nextRequestId = 1;
const _pending = new Map<
  number,
  {
    readonly resolve: (decision: ConsentDecision) => void;
    readonly request: ConsentRequest;
  }
>();

export interface ConsentRequest {
  readonly id: number;
  readonly contributorId: string;
  readonly capability: string;
  /** Human-readable description of what the capability allows. */
  readonly description?: string;
  /** Human-readable rationale ("this plugin wants to ..."). */
  readonly rationale?: string;
}

function key(contributorId: string, capability: string): string {
  return `${contributorId}\x00${capability}`;
}

function notify(): void {
  notifyAll(_listeners);
}

interface PersistedConsents {
  readonly version: 1;
  readonly entries: ReadonlyArray<ConsentRecord>;
}

function writePersisted(): void {
  const storage = safeLocalStorage();
  if (!storage) return;
  try {
    const payload: PersistedConsents = {
      version: 1,
      entries: Array.from(_consents.values()).filter(
        (r) => r.decision !== 'allow-once',
      ),
    };
    storage.setItem(PERSIST_KEY, JSON.stringify(payload));
  } catch {
    /* swallow */
  }
}

let _restored = false;
export function restoreConsentFromStorage(): void {
  if (_restored) return;
  _restored = true;
  const storage = safeLocalStorage();
  if (!storage) return;
  const raw = storage.getItem(PERSIST_KEY);
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw) as PersistedConsents;
    if (parsed?.version !== 1) return;
    if (!Array.isArray(parsed.entries)) return;
    for (const r of parsed.entries) {
      _consents.set(key(r.contributorId, r.capability), r);
    }
  } catch {
    /* swallow corrupt storage */
  }
}

/**
 * Record a user decision for a (contributor, capability) pair.
 * allow-once does NOT persist; allow-always + deny persist to
 * localStorage.
 */
export function recordConsent(
  contributorId: string,
  capability: string,
  decision: ConsentDecision,
): void {
  const record: ConsentRecord = {
    contributorId,
    capability,
    decision,
    decidedAt: new Date().toISOString(),
  };
  _consents.set(key(contributorId, capability), record);
  writePersisted();
  notify();
}

/**
 * Look up the recorded decision for a (contributor, capability) pair.
 * Returns undefined when no decision has been recorded ("undecided").
 */
export function checkCapability(
  contributorId: string,
  capability: string,
): ConsentDecision | undefined {
  return _consents.get(key(contributorId, capability))?.decision;
}

/**
 * Returns true when the current decision allows the capability:
 *  - allow-once → true (and consumes the once-grant — see below)
 *  - allow-always → true (persists)
 *  - deny → false
 *  - undecided → false (caller should requestCapability to prompt)
 *
 * NOTE: this read does NOT auto-consume allow-once. The handler must
 * call `consumeOnce(contributorId, capability)` after acting. Splitting
 * the read from the consume lets handlers decide whether the action
 * succeeded before clearing the grant.
 */
export function isAllowed(
  contributorId: string,
  capability: string,
): boolean {
  const d = checkCapability(contributorId, capability);
  return d === 'allow-once' || d === 'allow-always';
}

/**
 * Consume an allow-once grant. No-op for allow-always / deny /
 * undecided. After consume, the grant is cleared and the next call
 * returns undefined (requires re-prompt).
 */
export function consumeOnce(
  contributorId: string,
  capability: string,
): boolean {
  const k = key(contributorId, capability);
  const r = _consents.get(k);
  if (r?.decision === 'allow-once') {
    _consents.delete(k);
    notify();
    return true;
  }
  return false;
}

/**
 * Open a consent prompt and return a Promise that resolves with the
 * user's decision. Dispatches jf-consent-request CustomEvent to the
 * chrome host. Resolves with 'deny' if no chrome is mounted (safe
 * default in headless / SSR).
 */
export function requestCapability(opts: {
  contributorId: string;
  capability: string;
  description?: string;
  rationale?: string;
}): Promise<ConsentDecision> {
  const id = _nextRequestId++;
  const request: ConsentRequest = {
    id,
    contributorId: opts.contributorId,
    capability: opts.capability,
    ...(opts.description !== undefined ? { description: opts.description } : {}),
    ...(opts.rationale !== undefined ? { rationale: opts.rationale } : {}),
  };
  return new Promise<ConsentDecision>((resolve) => {
    _pending.set(id, { resolve, request });
    if (typeof document !== 'undefined') {
      document.dispatchEvent(
        new CustomEvent('jf-consent-request', {
          detail: request,
          bubbles: true,
        }),
      );
    } else {
      _pending.delete(id);
      resolve('deny');
    }
  });
}

/** Chrome calls this when the user picks a decision. */
export function resolveConsentRequest(
  id: number,
  decision: ConsentDecision,
): boolean {
  const entry = _pending.get(id);
  if (!entry) return false;
  _pending.delete(id);
  recordConsent(entry.request.contributorId, entry.request.capability, decision);
  entry.resolve(decision);
  return true;
}

export function listPendingConsentRequests(): readonly ConsentRequest[] {
  return Array.from(_pending.values()).map((p) => p.request);
}

export function listAllConsents(): readonly ConsentRecord[] {
  return Array.from(_consents.values());
}

export function revokeConsent(
  contributorId: string,
  capability: string,
): boolean {
  const removed = _consents.delete(key(contributorId, capability));
  if (removed) {
    writePersisted();
    notify();
  }
  return removed;
}

export function subscribeConsent(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** Test-only reset. */
export function __resetConsentForTest(): void {
  for (const entry of _pending.values()) entry.resolve('deny');
  _pending.clear();
  _consents.clear();
  _listeners.clear();
  _nextRequestId = 1;
  _restored = false;
  const storage = safeLocalStorage();
  storage?.removeItem(PERSIST_KEY);
}
