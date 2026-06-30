// @vitest-environment happy-dom
//
// Tempdoc 548 §1 / R1a — proves the genuine state-as-signal conversion of
// aiStateStore: inputs are signals, AiState is a `computed`, and
// `subscribeAiState` is a Signal.subtle.Watcher shim. These assertions pin
// the two properties that distinguish a real conversion from a tick-bolt:
//   1. the derived snapshot recomputes from inputs with NO manual notify
//      (read synchronously via getAiState);
//   2. subscribers still get a synchronous value on subscribe, and
//      subsequent changes fan out (batched) via the signal watcher.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  subscribeAiState,
  getAiState,
  setAiActivity,
  setInstallState,
  __resetAiStateForTest,
  __feedForTest,
  __feedContactForTest,
  __tickClockForTest,
  type AiState,
} from './aiStateStore.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';
import type { InferenceSnapshot } from '../utils/inferencePoll.js';
import { known } from './known.js';

const microtask = () => new Promise<void>((r) => queueMicrotask(() => r()));

describe('aiStateStore — R1a signal-core conversion', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  it('getAiState() reflects mutations synchronously (computed, no manual notify)', () => {
    expect(getAiState().activity.state).toBe('idle');
    setAiActivity({ state: 'streaming' });
    expect(getAiState().activity.state).toBe('streaming');
    setInstallState(true, true);
    // §2.B: install state is tri-state (Maybe<boolean>) — a fed value is Known.
    expect(getAiState().runtime.installed).toEqual(known(true));
    expect(getAiState().runtime.installing).toEqual(known(true));
  });

  it('delivers the current value synchronously on subscribe', () => {
    setAiActivity({ state: 'thinking' });
    let captured: AiState | null = null;
    const unsub = subscribeAiState((s) => {
      captured = s;
    });
    expect(captured).not.toBeNull();
    expect(captured!.activity.state).toBe('thinking');
    unsub();
  });

  it('fans out subsequent changes via the watcher (async, batched to latest)', async () => {
    const seen: string[] = [];
    const unsub = subscribeAiState((s) => seen.push(s.activity.state));
    expect(seen).toEqual(['idle']); // sync-on-subscribe only

    setAiActivity({ state: 'thinking' });
    setAiActivity({ state: 'streaming' });
    expect(seen).toEqual(['idle']); // not yet — fan-out is on a microtask

    await microtask();
    // Two synchronous mutations batch into ONE fan-out carrying the latest.
    expect(seen).toEqual(['idle', 'streaming']);
    unsub();
  });

  it('derived statusLabel recomputes from activity (computed dependency)', () => {
    setAiActivity({ state: 'extracting' });
    expect(getAiState().statusLabel).toBe('Extracting');
    setAiActivity({ state: 'idle' });
    // §2.B: with no successful poll the honest state is "Connecting…", not a
    // confident "offline" default (the no-data≠offline correction).
    expect(getAiState().statusLabel).toBe('Connecting…');
  });

  it('§2.B: with no stream data, state is Unknown/connecting — never a concrete default', () => {
    // No poll has succeeded (beforeEach reset; no start). The store must NOT
    // seed concrete values — that is the "0 files / Not Installed" defect class.
    const s = getAiState();
    expect(s.phase).toBe('connecting');
    expect(s.runtime.installed.known).toBe(false); // Unknown, not known(false)
    expect(s.runtime.installing.known).toBe(false);
    expect(s.index.documentCount.known).toBe(false);
    expect(s.index.embeddingBlocked.known).toBe(false);
    expect(s.readiness.known).toBe(false);
    expect(s.statusLabel).toBe('Connecting…');
  });

  it('§2.B / B4 + 649: poll stale then truly unreachable — staged "Catching up…" → "Reconnecting…", last-known retained', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      // One successful status poll: 42 docs (a poll success is positive contact, tempdoc 649).
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
      });
      __tickClockForTest();
      expect(getAiState().phase).toBe('connected');
      expect(getAiState().connection.reachable).toBe(true);
      expect(getAiState().index.documentCount).toEqual(known(42));

      // 16s later: past the 15s poll-freshness threshold, but the last contact (the t0 poll) is only
      // 16s old — within the 40s reachability window. 649: data is behind but the backend is provably
      // reachable, so the calm "Catching up…", NOT the "Reconnecting…" alarm.
      vi.setSystemTime(t0 + 16_000);
      __tickClockForTest();
      let s = getAiState();
      expect(s.phase).toBe('stale');
      expect(s.connection.reachable).toBe(true);
      expect(s.statusLabel).toBe('Catching up…');
      expect(s.index.documentCount).toEqual(known(42)); // last-known retained, not wiped
      expect(s.statusTier).toBe('degraded'); // statusTier still 'degraded' (it no longer drives tone)
      // 649 tone fix: the calm "Catching up…" state is `info` (calm tint), NOT amber — so every surface
      // (status pill, liveness dot) renders it calm, matching the Health badge.
      expect(s.statusTone).toBe('info');
      expect(s.connection.lastContactMs).toBe(t0); // contact stamp surfaced (the t0 poll success)

      // 41s later: NO contact of any kind within the 40s window ⇒ genuinely unreachable ⇒ the alarm.
      vi.setSystemTime(t0 + 41_000);
      __tickClockForTest();
      s = getAiState();
      expect(s.connection.reachable).toBe(false);
      expect(s.statusLabel).toBe('Reconnecting…');
      // 649 ramp: lost contact ("Reconnecting…") is a WARNING (amber), distinct from the calm catch-up.
      expect(s.statusTone).toBe('warning');
      expect(s.index.documentCount).toEqual(known(42)); // still retained
    } finally {
      vi.useRealTimers();
    }
  });

  it('649: a stale poll stays calm "Catching up…" while an SSE frame keeps contact fresh', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      vi.setSystemTime(t0);
      __feedForTest({
        status: { worker: { core: { indexedDocuments: 7 } } } as unknown as StatusSnapshot,
      });
      __tickClockForTest();

      // 50s later: the poll is long stale (>40s) — WITHOUT a stream this would be "Reconnecting…".
      // But an SSE frame arrived at t0+45s (positive contact), so the origin is reachable.
      vi.setSystemTime(t0 + 45_000);
      __feedContactForTest(t0 + 45_000); // mirrors EnvelopeStream.handleFrame bumping the stamp
      vi.setSystemTime(t0 + 50_000);
      __tickClockForTest();
      const s = getAiState();
      expect(s.phase).toBe('stale'); // poll data is behind
      expect(s.connection.reachable).toBe(true); // but a recent SSE frame proves the backend is alive
      expect(s.statusLabel).toBe('Catching up…'); // calm, not the false "Reconnecting…"
      expect(s.statusTone).toBe('info'); // 649: calm tone, not amber
      expect(s.connection.lastContactMs).toBe(t0 + 45_000); // contact stamp = the SSE frame
    } finally {
      vi.useRealTimers();
    }
  });

  it('601 §19: model-load `starting` shows a live measured elapsed (>2s gate, minute-aware, clears on leave)', () => {
    vi.useFakeTimers();
    try {
      const t0 = new Date('2026-01-01T00:00:00Z').getTime();
      // A fresh poll (status keeps the connection live so the verdict stays settled, not
      // 'reconnecting'; inference carries the model 'starting' flag). Re-feeding while already
      // starting does NOT re-stamp loadStartedAt (the capture is edge-triggered), so elapsed
      // measures from t0 — exactly the real 5s-poll behavior during a load.
      const pollStarting = () =>
        __feedForTest({
          status: { worker: { core: { indexedDocuments: 5, pendingJobs: 0 } } } as unknown as StatusSnapshot,
          inference: { mode: 'transitioning', starting: true, available: false } as unknown as InferenceSnapshot,
        });

      vi.setSystemTime(t0);
      pollStarting();
      expect(getAiState().runtime.mode).toBe('starting');
      // <2s elapsed → bare label (no fabricated tiny number).
      expect(getAiState().statusLabel).toBe('Starting…');

      // 12s in → measured count-up (a count-UP, never a countdown).
      vi.setSystemTime(t0 + 12_000);
      pollStarting();
      expect(getAiState().statusLabel).toBe('Starting… 12s');

      // 90s in (a cold load) → minute-aware wording via humanizeSeconds.
      vi.setSystemTime(t0 + 90_000);
      pollStarting();
      expect(getAiState().statusLabel).toBe('Starting… 1m 30s');

      // Leaving 'starting' (→ online) clears the stamp: the count-up does not persist.
      __feedForTest({
        inference: { mode: 'online', starting: false, available: true } as unknown as InferenceSnapshot,
      });
      expect(getAiState().runtime.mode).toBe('online');
      expect(getAiState().statusLabel.startsWith('Starting')).toBe(false);
      expect(getAiState().statusTone).toBe('success'); // 649: settled-online is green
    } finally {
      vi.useRealTimers();
    }
  });

  it('unsubscribe stops delivery', async () => {
    const seen: string[] = [];
    const unsub = subscribeAiState((s) => seen.push(s.activity.state));
    unsub();
    setAiActivity({ state: 'thinking' });
    await microtask();
    expect(seen).toEqual(['idle']); // only the initial sync call survived
  });
});

// Tempdoc 595 — the derived verdict + stability axis + their status-bar projection.
describe('aiStateStore — system-health verdict (595)', () => {
  beforeEach(() => __resetAiStateForTest());
  afterEach(() => __resetAiStateForTest());

  function statusWith(
    retrieval: 'READY' | 'DEGRADED' | 'UNKNOWN',
    reasonCodes: string[] = [],
    over: {
      indexState?: string;
      migrationState?: string;
      building?: string;
      active?: string;
      docs?: number;
      sizeBytes?: number;
    } = {},
  ): StatusSnapshot {
    return {
      worker: {
        core: {
          indexedDocuments: over.docs ?? 5,
          indexSizeBytes: over.sizeBytes ?? 1024,
          pendingJobs: 0,
          indexState: over.indexState ?? 'IDLE',
          indexHealthy: true,
        },
        migration: {
          migrationState: over.migrationState ?? 'IDLE',
          activeGenerationId: over.active ?? 'g1',
          buildingGenerationId: over.building ?? '',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
      readiness: {
        composites: {
          retrieval: { state: retrieval, reasonCodes },
          aiFeatures: { state: 'READY', reasonCodes: [] },
        },
      },
      schema: { reindexRequired: false },
    } as unknown as StatusSnapshot;
  }

  function feed(s: StatusSnapshot): void {
    __feedForTest({ status: s });
    __tickClockForTest();
  }

  it('§10.1 fix: the status-bar tier reflects readiness — impairing degraded ⇒ degraded tier', () => {
    feed(statusWith('DEGRADED', ['worker.health.embedding_not_ready']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('warn');
    expect(s.statusTier).toBe('degraded'); // was 'online' before 595 — the live split
    expect(s.statusTone).toBe('warning'); // 649: an impairing degrade is amber on every surface
  });

  it('600 Design A: a compat reindex code carries through to a degraded verdict (the specific cause)', () => {
    feed(statusWith('DEGRADED', ['index.blocked_legacy']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('warn');
    // The new compat reason code reaches the verdict's reasons (so readinessNotice can name it).
    expect(s.verdict.reasons).toContain('index.blocked_legacy');
    expect(s.statusTier).toBe('degraded');
  });

  it('§10.3 fix: a COSMETIC degraded (LambdaMART) stays calm — verdict info, tier NOT degraded', () => {
    feed(statusWith('DEGRADED', ['lambdamart.not_configured']));
    const s = getAiState();
    expect(s.verdict.kind).toBe('degraded');
    expect(s.verdict.severity).toBe('info');
    expect(s.statusTier).not.toBe('degraded'); // no false alarm for an optional re-ranker
    expect(s.statusTone).toBe('info'); // 649: a cosmetic degrade stays calm (info), never amber
  });

  it('a worker-down fallback (indexState UNAVAILABLE) ⇒ transitioning, "Restarting…"', () => {
    feed(statusWith('UNKNOWN', [], { indexState: 'UNAVAILABLE' }));
    const s = getAiState();
    expect(s.stability).toEqual({ kind: 'provisional', cause: 'worker-restart' });
    expect(s.verdict.kind).toBe('transitioning');
    expect(s.statusLabel).toBe('Restarting…');
  });

  it('a rebuild (migration MIGRATING) ⇒ transitioning, "Rebuilding…"', () => {
    feed(statusWith('READY', [], { migrationState: 'MIGRATING' }));
    const s = getAiState();
    expect(s.verdict.kind).toBe('transitioning');
    expect(s.verdict.reasons).toEqual(['rebuilding']);
    expect(s.statusLabel).toBe('Rebuilding…');
  });

  it('a healthy settled system ⇒ operational/ok', () => {
    feed(statusWith('READY'));
    const s = getAiState();
    expect(s.verdict.kind).toBe('operational');
    expect(s.verdict.severity).toBe('ok');
    expect(s.stability).toEqual({ kind: 'settled' });
  });

  // 595 §15.3 (E2) — last-settled index retention across a provisional window.
  it('E2: lastSettledIndex is null before any settled poll', () => {
    expect(getAiState().lastSettledIndex).toBeNull();
  });

  it('E2: a settled poll stamps lastSettledIndex; a provisional poll keeps it', () => {
    feed(statusWith('READY', [], { docs: 1234, sizeBytes: 4096 }));
    expect(getAiState().lastSettledIndex).toEqual({ documentCount: 1234, indexSizeBytes: 4096 });
    // Worker restarts: a *successful* poll returns the fallback (0 docs / UNAVAILABLE). The
    // retained settled value must NOT be overwritten by that transient zero.
    feed(statusWith('UNKNOWN', [], { indexState: 'UNAVAILABLE', docs: 0, sizeBytes: 0 }));
    const s = getAiState();
    expect(s.stability.kind).toBe('provisional');
    expect(s.lastSettledIndex).toEqual({ documentCount: 1234, indexSizeBytes: 4096 });
  });

  it('E2: a later settled poll refreshes lastSettledIndex', () => {
    feed(statusWith('READY', [], { docs: 10, sizeBytes: 100 }));
    feed(statusWith('READY', [], { docs: 20, sizeBytes: 200 }));
    expect(getAiState().lastSettledIndex).toEqual({ documentCount: 20, indexSizeBytes: 200 });
  });

  it('E2: a settled poll with a doc count but NO size stamps indexSizeBytes=null (honesty)', () => {
    // A present doc count with an absent size retains null for size (renderers show "…", not "0 B").
    feed({
      worker: {
        core: { indexedDocuments: 77, indexState: 'IDLE', indexHealthy: true },
        migration: { migrationState: 'IDLE', activeGenerationId: 'g1', buildingGenerationId: '', servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g1' },
      },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
    } as unknown as StatusSnapshot);
    expect(getAiState().lastSettledIndex).toEqual({ documentCount: 77, indexSizeBytes: null });
  });
});
