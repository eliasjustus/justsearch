// SPDX-License-Identifier: Apache-2.0
//
// Tempdoc 663 — unit coverage for `computeAiEngineVerdict` (the OBSERVED AI-engine lifecycle
// derivation, computed once in `aiStateStore.ts`) and `applyLocalIntent` (the small, surface-local
// optimistic-intent overlay `BrainSurface` applies on top). Design pass 2 (2026-07-01) split the
// original single ladder into these two functions; this file's structure mirrors that split.

import { describe, it, expect } from 'vitest';
import {
  computeAiEngineVerdict,
  applyLocalIntent,
  presentAiEngineVerdict,
  type AiEngineObservedInput,
  type AiEngineVerdict,
  type AiEngineKind,
} from './aiVerdict.js';
import { known, UNKNOWN, type Maybe } from './known.js';
import type { AiRuntime, InstallStatus, AiRuntimeStatus } from './aiStateStore.js';

function runtime(overrides: Partial<AiRuntime> = {}): AiRuntime {
  return {
    mode: 'offline',
    modelId: null,
    modelLabel: null,
    contextWindow: null,
    gpu: null,
    installed: UNKNOWN as Maybe<boolean>,
    installing: UNKNOWN as Maybe<boolean>,
    loadStartedAtMs: null,
    ...overrides,
  };
}

function input(overrides: Partial<AiEngineObservedInput> = {}): AiEngineObservedInput {
  return {
    installStatus: null,
    runtimeStatus: null,
    runtime: runtime(),
    reachable: true,
    ...overrides,
  };
}

describe('computeAiEngineVerdict (observed axes only — no local intent)', () => {
  it('no data yet, backend reachable → calm "connecting", cause "checking" (no-data ≠ not-installed)', () => {
    const v = computeAiEngineVerdict(input({ reachable: true }));
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'checking' });
  });

  it('no data yet, backend NOT reachable → still calm "connecting", cause "stale-poll" (never a confident negative)', () => {
    const v = computeAiEngineVerdict(input({ reachable: false }));
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'stale-poll' });
  });

  it('install status known, not installed → "not_installed", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false };
    const v = computeAiEngineVerdict(input({ installStatus }));
    expect(v.kind).toBe('not_installed');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('install running → "installing", provisional, regardless of other axes', () => {
    const installStatus: InstallStatus = { state: 'running', phase: 'downloading' };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'online' }) }),
    );
    expect(v.kind).toBe('installing');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'installing' });
  });

  it('install failed → "install_failed", settled, surfaces the error text (Investigation §E: previously unrepresented)', () => {
    const installStatus: InstallStatus = {
      state: 'failed',
      phase: 'idle',
      lastError: 'disk full',
    };
    const v = computeAiEngineVerdict(input({ installStatus }));
    expect(v.kind).toBe('install_failed');
    expect(v.stability).toEqual({ kind: 'settled' });
    expect(v.installFailure).toBe('disk full');
  });

  it('installed, runtime online → "online", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'online' }) }),
    );
    expect(v.kind).toBe('online');
  });

  it('runtime online is proof of an installed engine even if installStatus itself is stale/null', () => {
    const v = computeAiEngineVerdict(input({ installStatus: null, runtime: runtime({ mode: 'online' }) }));
    expect(v.kind).toBe('online');
  });

  it('installed, runtime indexing → "indexing", settled (Investigation §E: previously fell through to "offline")', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'indexing' }) }),
    );
    expect(v.kind).toBe('indexing');
  });

  it('runtime starting (explicit live-load signal) → "starting", provisional cause "starting"', () => {
    const v = computeAiEngineVerdict(input({ runtime: runtime({ mode: 'starting' }) }));
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'starting' });
  });

  it('installed via onnxFeatures.modelActive (runtimeStatus-derived), mode transitioning → "starting", cause "starting"', () => {
    const runtimeStatus: AiRuntimeStatus = {
      onnxFeatures: [{ feature: 'llm', modelActive: true }],
    };
    const v = computeAiEngineVerdict(
      input({ runtimeStatus, runtime: runtime({ mode: 'transitioning' }) }),
    );
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'starting' });
  });

  it('installed, runtime offline, reachable, no in-flight signal → "offline", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(input({ installStatus, reachable: true }));
    expect(v.kind).toBe('offline');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('Design pass 2 — installed, runtime offline, NOT reachable → "offline" stays but becomes provisional (stale-poll), not a confident negative', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(input({ installStatus, reachable: false }));
    expect(v.kind).toBe('offline');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'stale-poll' });
  });

  it('known-unknown install data via runtime.installed=known(true) counts as "have install data"', () => {
    const v = computeAiEngineVerdict(input({ runtime: runtime({ installed: known(true) }) }));
    // installed=known(true) alone does not satisfy the `installed` boolean (that requires
    // installStatus.installedFully or onnxFeatures) — but it DOES count as "have install data",
    // so the calm "connecting" state must resolve to a settled "not_installed" rather than stay
    // stuck, mirroring the original ladder's haveInstallData check.
    expect(v.kind).toBe('not_installed');
  });
});

describe('applyLocalIntent (surface-local optimistic overlay — BrainSurface only)', () => {
  const settledOffline: AiEngineVerdict = {
    kind: 'offline',
    stability: { kind: 'settled' },
    installFailure: null,
  };

  it('installStarting (local click intent) alone → "installing" immediately, before the poll confirms', () => {
    // Mirrors the original ladder's `busy['install-start']` OR-condition: a click must not wait for
    // the next poll tick to show feedback.
    const v = applyLocalIntent(
      { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null },
      { installStarting: true, switching: false },
    );
    expect(v.kind).toBe('installing');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'installing' });
  });

  it('installStarting takes priority over a stale "failed" observed verdict (retry-click shows "Installing…" at once)', () => {
    const observed: AiEngineVerdict = {
      kind: 'install_failed',
      stability: { kind: 'settled' },
      installFailure: 'disk full',
    };
    const v = applyLocalIntent(observed, { installStarting: true, switching: false });
    expect(v.kind).toBe('installing');
    expect(v.installFailure).toBeNull();
  });

  it('switching intent flag, observed "offline" → "starting", cause "switching-variant"', () => {
    const v = applyLocalIntent(settledOffline, { installStarting: false, switching: true });
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'switching-variant' });
  });

  it('switching intent flag, observed "starting"/cause "starting" (a bare transitioning mode) → cause becomes "switching-variant"', () => {
    const observed: AiEngineVerdict = {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'starting' },
      installFailure: null,
    };
    const v = applyLocalIntent(observed, { installStarting: false, switching: true });
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'switching-variant' });
  });

  it('switching intent flag never overrides a confident non-offline observed kind (e.g. "online")', () => {
    const observed: AiEngineVerdict = { kind: 'online', stability: { kind: 'settled' }, installFailure: null };
    const v = applyLocalIntent(observed, { installStarting: false, switching: true });
    expect(v).toEqual(observed);
  });

  it('no local intent → returns the observed value unchanged', () => {
    const v = applyLocalIntent(settledOffline, { installStarting: false, switching: false });
    expect(v).toEqual(settledOffline);
  });
});

describe('presentAiEngineVerdict (Design pass 3 — the presentation projection)', () => {
  const verdictFor = (kind: AiEngineKind, installFailure: string | null = null): AiEngineVerdict => ({
    kind,
    stability: { kind: 'settled' },
    installFailure,
  });

  // Reuses the exact wording already established in BrainSurface.ts's `statusConfig` for the four
  // kinds that had no prior footer wording, and the footer's OWN pre-existing terse wording (preserved
  // deliberately, not `statusConfig`'s "AI Online"/"AI Offline") for the four kinds it already covered.
  it.each([
    ['not_installed', 'Not Installed', 'neutral'],
    ['installing', 'Installing…', 'info'],
    // Critical-review fix (2026-07-01) — 'error', not 'neutral': must agree with the
    // `core.ai-engine.failed` toast's `defaultSeverity: 'error'` (messageClasses.ts).
    ['install_failed', 'Install Failed', 'error'],
    ['offline', 'Offline', 'neutral'],
    ['starting', 'Starting…', 'warning'],
    ['connecting', 'Connecting…', 'warning'],
    ['online', 'Online', 'success'],
    ['indexing', 'Indexing', 'warning'],
  ] as const)('kind %s → headline %s, tone %s', (kind, headline, tone) => {
    const p = presentAiEngineVerdict(verdictFor(kind));
    expect(p.headline).toBe(headline);
    expect(p.tone).toBe(tone);
  });

  it('install_failed surfaces the install service error text in the body', () => {
    const p = presentAiEngineVerdict(verdictFor('install_failed', 'disk full'));
    expect(p.body).toBe('disk full');
  });

  it('install_failed announces with assertive politeness (the one error-toned kind)', () => {
    const p = presentAiEngineVerdict(verdictFor('install_failed'));
    expect(p.announce).toEqual({ text: 'Install Failed', politeness: 'alert' });
  });

  it('every other kind announces with polite status politeness', () => {
    const p = presentAiEngineVerdict(verdictFor('online'));
    expect(p.announce).toEqual({ text: 'Online', politeness: 'status' });
  });
});
