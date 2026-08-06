// @vitest-environment happy-dom

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — the ONE snapshot-liveness predicate.
 *
 * Round 13 killed both java processes and photographed the shell still rendering an ANIMATING
 * "Building semantic search 2.0% · 5,084 pending", "Search Quality Features 4/4 active", a populated
 * Runtime card and a GREEN CONN dot. The values were right; their TENSE was not. `isSnapshotLive`
 * is the single question every snapshot-rendering surface now asks, projected from the same verdict
 * authority `verdictOwnsStatus` projects from — never a second, surface-local staleness heuristic.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  isSnapshotLive,
  getAiState,
  __resetAiStateForTest,
  __feedForTest,
  __feedContactForTest,
  __tickClockForTest,
} from './aiStateStore.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';
import { projectAvailability } from './availability.js';
import '../components/Control.js';
import type { Control } from '../components/Control.js';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';
import { presentAiEngineVerdict } from './aiVerdict.js';
import { reasonFor } from './readinessNotice.js';

describe('isSnapshotLive — the predicate', () => {
  it('an unreachable origin is NOT live; a reachable one is', () => {
    // Round-13 review: liveness is a CONTACT fact, not a verdict-kind classification. The first cut
    // read `verdict.kind` and was bypassed by every higher-precedence `computeStability` branch (see
    // the precedence suite below); contact is the one thing a retained snapshot cannot fake.
    expect(isSnapshotLive({ reachable: false })).toBe(false);
    expect(isSnapshotLive({ reachable: true })).toBe(true);
  });
});

/**
 * Round-13 review of this bundle — THE regression home for the hole the first cut left.
 *
 * `computeStability` (verdict.ts:100-131) returns from six branches BEFORE the `phase === 'stale'`
 * one that mints `channel-stale`, and every one reads a field off the RETAINED snapshot. Since
 * `statusSig` is kept on a failed poll (`onStatusUpdate`), a backend that dies while any of them
 * holds — the ordinary case being the Worker dying first, which writes `indexState: UNAVAILABLE` —
 * pinned the verdict on that branch forever. A kind-based predicate then reported LIVE indefinitely
 * and silently disabled the entire fix. These six states are live IFF contact is fresh.
 */
describe('AiState.snapshotLive — the higher-precedence stability states (round-13 review)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  const worker = (migration: Record<string, unknown>): StatusSnapshot =>
    ({ worker: { core: { indexedDocuments: 42 }, migration } }) as unknown as StatusSnapshot;

  const CASES: ReadonlyArray<{ name: string; status: StatusSnapshot; cause: string }> = [
    {
      name: 'worker down/restarting (indexState UNAVAILABLE) — the Worker-dies-first case',
      status: { worker: { core: { indexedDocuments: 42, indexState: 'UNAVAILABLE' } } } as unknown as StatusSnapshot,
      cause: 'worker-restart',
    },
    { name: 'migrationState SWITCHING', status: worker({ migrationState: 'SWITCHING' }), cause: 'generation-switch' },
    { name: 'migrationState MIGRATING', status: worker({ migrationState: 'MIGRATING' }), cause: 'rebuilding' },
    {
      name: 'a building generation differs from the active one',
      status: worker({ buildingGenerationId: 'g2', activeGenerationId: 'g1' }),
      cause: 'rebuilding',
    },
    {
      name: 'serving-search differs from serving-ingest',
      status: worker({ servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g2' }),
      cause: 'generation-switch',
    },
    {
      name: 'catchingUp (post-resume reconcile)',
      status: { worker: { core: { indexedDocuments: 42 } }, catchingUp: true } as unknown as StatusSnapshot,
      cause: 'catching-up',
    },
  ];

  for (const c of CASES) {
    it(`NOT live once contact dies: ${c.name}`, () => {
      vi.useFakeTimers();
      try {
        const t0 = new Date('2026-01-01T00:00:00Z').getTime();
        vi.setSystemTime(t0);
        __feedForTest({ status: c.status });
        __tickClockForTest();

        vi.setSystemTime(t0 + 41_000); // past the 40s reachability window: no contact of any channel
        __tickClockForTest();
        const dead = getAiState();
        // Precision: this state must still be winning its higher-precedence branch — otherwise the
        // assertion below would pass for the WRONG reason (the `channel-stale` path the old
        // kind-based predicate already handled).
        expect(dead.stability, c.name).toEqual({ kind: 'provisional', cause: c.cause });
        expect(dead.verdict.reasons.includes('channel-stale'), c.name).toBe(false);
        expect(dead.connection.reachable, c.name).toBe(false);
        expect(dead.snapshotLive, c.name).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it(`ANTI-REGRESSION — still live while contact is fresh: ${c.name}`, () => {
      vi.useFakeTimers();
      try {
        const t0 = new Date('2026-01-01T00:00:00Z').getTime();
        vi.setSystemTime(t0);
        __feedForTest({ status: c.status });
        __tickClockForTest();
        // Work in flight with the backend answering: still a LIVE observation. That is what the
        // retired "work in flight, backend answering" pin actually meant — live IFF contact is fresh.
        expect(getAiState().stability, c.name).toEqual({ kind: 'provisional', cause: c.cause });
        expect(getAiState().snapshotLive, c.name).toBe(true);

        // ...and it stays live across a poll gap while an SSE frame keeps contact fresh (649).
        vi.setSystemTime(t0 + 60_000);
        __feedContactForTest(t0 + 58_000);
        expect(getAiState().snapshotLive, c.name).toBe(true);
      } finally {
        vi.useRealTimers();
      }
    });
  }
});

describe('AiState.snapshotLive — projected once, in the store', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('BOUNDARY — live across the reachability window, dead past it (no new threshold: the 40s stream-watchdog window)', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
      });
      __tickClockForTest();
      expect(getAiState().snapshotLive).toBe(true);

      // 16s: past the 15s POLL-freshness threshold but well inside the 40s reachability window —
      // the data is merely behind ("Catching up…"), the backend is provably answering. STILL LIVE.
      vi.setSystemTime(t0 + 16_000);
      __tickClockForTest();
      expect(getAiState().phase).toBe('stale');
      expect(getAiState().snapshotLive).toBe(true);

      // 39.999s: last moment inside the window (isOriginReachable is exclusive at the boundary).
      vi.setSystemTime(t0 + 39_999);
      __tickClockForTest();
      expect(getAiState().snapshotLive).toBe(true);

      // 40s: no contact of any kind within the window ⇒ the snapshot is history.
      vi.setSystemTime(t0 + 40_000);
      __tickClockForTest();
      const dead = getAiState();
      expect(dead.connection.reachable).toBe(false);
      expect(dead.snapshotLive).toBe(false);
      // The retained values are NOT wiped — this bundle changes their TENSE, not their existence.
      expect(dead.status?.worker?.core?.indexedDocuments).toBe(42);
    } finally {
      vi.useRealTimers();
    }
  });

  it('an SSE heartbeat keeps the snapshot live even while the poll starves (649 — no poll-only fork)', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      __feedForTest({ status: { worker: { core: { indexedDocuments: 7 } } } as unknown as StatusSnapshot });
      __tickClockForTest();
      vi.setSystemTime(t0 + 60_000);
      __feedContactForTest(t0 + 58_000); // a stream frame 2s ago
      expect(getAiState().snapshotLive).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a never-contacted origin is not live — and the boot window is still worded as "starting", not "disconnected"', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      // No poll ever succeeded ⇒ no contact ⇒ not a live observation (round-13 review: liveness is a
      // contact fact). There is no snapshot to misrepresent either, and the boot window keeps its own
      // wording: `projectAvailability` answers `phase === 'connecting'` BEFORE it consults liveness,
      // so "still starting" — not "Backend disconnected." — is what a control says while booting.
      expect(getAiState().verdict.kind).toBe('connecting');
      expect(getAiState().phase).toBe('connecting');
      expect(getAiState().snapshotLive).toBe(false);
      const a = projectAvailability('extract', getAiState());
      expect(a.kind).toBe('unavailable');
      if (a.kind !== 'unavailable') throw new Error('unreachable');
      expect(a.reason).toBe(reasonFor('inference.starting').wording);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('AiState.aiEngine — the retained engine claim expires with the snapshot (807 Part A)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('a Healthy engine goes from settled "online" to unconfirmed the moment the snapshot stops being live', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      __feedForTest({
        status: {
          worker: { core: { indexedDocuments: 42 } },
          inference: { engineState: 'Healthy', chatEnabledSpec: true },
        } as unknown as StatusSnapshot,
      });
      __tickClockForTest();
      const alive = getAiState();
      expect(alive.aiEngine).toEqual({
        kind: 'online',
        stability: { kind: 'settled' },
        installFailure: null,
      });
      expect(presentAiEngineVerdict(alive.aiEngine).body).toBe('Chat and summaries ready.');

      // Round 13's reproduction: both java processes killed. `engineState` is still 'Healthy' on the
      // retained snapshot — which is exactly why the sentence survived before this bundle.
      vi.setSystemTime(t0 + 41_000);
      __tickClockForTest();
      const dead = getAiState();
      expect(dead.snapshotLive).toBe(false);
      expect(dead.status?.inference?.engineState).toBe('Healthy'); // the retained value is unchanged...
      expect(dead.aiEngine.kind).not.toBe('online'); // ...but it is no longer a present-tense claim.
      expect(dead.aiEngine.stability).toEqual({ kind: 'provisional', cause: 'stale-poll' });
      const p = presentAiEngineVerdict(dead.aiEngine);
      expect(p.body).not.toBe('Chat and summaries ready.');
      expect(p.tone).not.toBe('success');
      // The status bar keeps its own, louder wording (`verdictOwnsStatus`) — the two must not disagree.
      expect(dead.statusLabel).not.toContain('Online');
      expect(dead.statusTone).not.toBe('success');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('projectAvailability — a live backend is a precondition (807 A.3)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('capability affordances go unavailable-with-a-reason when the snapshot is not live', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      // A backend that WAS fully healthy: chat online, docs indexed. `capabilities.chat` is computed
      // off this retained snapshot, so without the liveness gate every affordance stays "available".
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 42 } }, inference: { engineState: 'Healthy' } } as unknown as StatusSnapshot,
        inference: { mode: 'online', available: true } as never,
      });
      __tickClockForTest();
      expect(getAiState().capabilities.chat).toBe(true);
      expect(projectAvailability('extract', getAiState()).kind).toBe('available');

      vi.setSystemTime(t0 + 41_000);
      __tickClockForTest();
      const s = getAiState();
      expect(s.capabilities.chat).toBe(true); // the retained value is unchanged...
      for (const affordance of ['documents', 'extract', 'agent'] as const) {
        const av = projectAvailability(affordance, s);
        expect(av.kind, affordance).toBe('unavailable');
        if (av.kind !== 'unavailable') throw new Error('unreachable');
        // ...but the control now says WHY, in the same words the verdict + banner use.
        expect(av.reason).toBe(reasonFor('binding.unreachable').wording);
        // NOT transient (round-13 review) — see the click test below for what that flag actually does.
        expect(av.transient, affordance).toBe(false);
      }
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * Round-13 review, P2 — the campaign converted these controls from `?disabled` (inert) to a
   * transient soft-block, and `transient` is exactly what makes `jf-control` QUEUE the intent
   * (`Control.activate`) and replay it on the next operable render (`resolveQueued`). "Disabled while
   * the backend is down" and "queued and fired the moment it comes back" are opposite behaviours; a
   * live-backend precondition must be the former.
   */
  it('a click while not live does not enqueue an intent that later fires', async () => {
    const t0 = Date.now();
    vi.useFakeTimers();
    try {
      vi.setSystemTime(t0);
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
        inference: { mode: 'online', available: true } as never,
      });
      __tickClockForTest();
      vi.setSystemTime(t0 + 41_000);
      __tickClockForTest();

      const onActivate = vi.fn();
      const toasts: string[] = [];
      const listener = (e: Event) => toasts.push((e as CustomEvent).detail?.message ?? '');
      document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);

      const el = document.createElement('jf-control') as Control;
      el.label = 'Ask';
      el.availability = projectAvailability('documents', getAiState());
      el.onActivate = onActivate;
      document.body.appendChild(el);
      await el.updateComplete;

      el.shadowRoot!.querySelector('button')!.click();
      expect(onActivate).not.toHaveBeenCalled();
      expect(toasts.some((m) => /queued/i.test(m))).toBe(false);
      expect(toasts.some((m) => m === reasonFor('binding.unreachable').wording)).toBe(true);

      // The backend comes back: nothing may fire by itself. The user re-clicks if they still want it.
      __feedContactForTest(Date.now());
      el.availability = projectAvailability('documents', getAiState());
      await el.updateComplete;
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener);
      expect(el.availability.kind).toBe('available'); // precision: the control DID become operable
      expect(onActivate).not.toHaveBeenCalled();
      el.remove();
    } finally {
      vi.useRealTimers();
    }
  });
});
