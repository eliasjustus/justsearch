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
import { presentAiEngineVerdict } from './aiVerdict.js';
import { reasonFor } from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';

describe('isSnapshotLive — the predicate', () => {
  it('a verdict that never contacted the origin is NOT live (`unreachable`)', () => {
    const v: SystemHealthVerdict = { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] };
    expect(isSnapshotLive(v)).toBe(false);
  });

  it('a verdict whose contact aged out mid-session is NOT live (`transitioning`/`channel-stale`)', () => {
    // THE case round 13 reproduced: a poll DID land, then the backend died. `computeVerdict` mints
    // transitioning/channel-stale here, NOT `unreachable` — a predicate that only knew `unreachable`
    // would have left every photographed surface exactly as it was.
    const v: SystemHealthVerdict = { kind: 'transitioning', severity: 'warn', reasons: ['channel-stale'] };
    expect(isSnapshotLive(v)).toBe(false);
  });

  it('BOUNDARY — a transitioning verdict from any OTHER cause is still live (work in flight, backend answering)', () => {
    for (const cause of ['rebuilding', 'generation-switch', 'updating', 'worker-restart']) {
      const v: SystemHealthVerdict = { kind: 'transitioning', severity: 'busy', reasons: [cause] };
      expect(isSnapshotLive(v), cause).toBe(true);
    }
  });

  it('BOUNDARY — channel-stale alongside another reason is still NOT live (presence, not sole-reason)', () => {
    const v: SystemHealthVerdict = { kind: 'transitioning', severity: 'warn', reasons: ['rebuilding', 'channel-stale'] };
    expect(isSnapshotLive(v)).toBe(false);
  });

  it('every reachable kind is live — the anti-regression half (the predicate must not blank a healthy UI)', () => {
    const kinds: SystemHealthVerdict[] = [
      { kind: 'operational', severity: 'ok', reasons: [] },
      { kind: 'checking', severity: 'info', reasons: [] },
      { kind: 'connecting', severity: 'info', reasons: [] },
      { kind: 'degraded', severity: 'warn', reasons: ['lambdamart.not_configured'] },
    ];
    for (const v of kinds) expect(isSnapshotLive(v), v.kind).toBe(true);
  });
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

  it('a never-contacted origin past the grace window is not live', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      // No poll ever succeeded and the store was never started ⇒ phase 'connecting', which carries no
      // snapshot to misrepresent. The honest answer there is "live" (nothing stale is being shown).
      expect(getAiState().verdict.kind).toBe('connecting');
      expect(getAiState().snapshotLive).toBe(true);
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
        expect(av.transient).toBe(true); // the shell reconnects; this self-clears
      }
    } finally {
      vi.useRealTimers();
    }
  });
});
