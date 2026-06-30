import { describe, it, expect } from 'vitest';
import {
  computeStability,
  computeVerdict,
  verdictHeadline,
  verdictTone,
  presentVerdict,
  type StabilityInput,
} from './verdict.js';
import { severityForCodes } from './readinessNotice.js';
import { known, UNKNOWN } from './known.js';
import type { ReadinessView } from './aiStateStore.js';

const settledInput: StabilityInput = {
  phase: 'connected',
  indexState: 'IDLE',
  migrationState: 'IDLE',
  activeGenerationId: 'g1',
  buildingGenerationId: '',
  servingSearchGenerationId: 'g1',
  servingIngestGenerationId: 'g1',
};

const readyReadiness: ReadinessView = {
  retrieval: 'ready',
  aiFeatures: 'ready',
  reasonCodes: [],
};

describe('computeStability (595 §4.1)', () => {
  it('is settled on a healthy idle system', () => {
    expect(computeStability(settledInput)).toEqual({ kind: 'settled' });
  });

  it('worker-restart dominates when the poll returns the UNAVAILABLE fallback (worker down)', () => {
    // Even with migration IDLE and phase connected — this is the §9.1 down window.
    expect(computeStability({ ...settledInput, indexState: 'UNAVAILABLE' })).toEqual({
      kind: 'provisional',
      cause: 'worker-restart',
    });
  });

  it('rebuilding when the worker is MIGRATING (building a new generation)', () => {
    expect(computeStability({ ...settledInput, migrationState: 'MIGRATING' })).toEqual({
      kind: 'provisional',
      cause: 'rebuilding',
    });
  });

  it('generation-switch when SWITCHING, or serving search≠ingest gen', () => {
    expect(computeStability({ ...settledInput, migrationState: 'SWITCHING' })).toEqual({
      kind: 'provisional',
      cause: 'generation-switch',
    });
    expect(
      computeStability({ ...settledInput, servingSearchGenerationId: 'g1', servingIngestGenerationId: 'g2' }),
    ).toEqual({ kind: 'provisional', cause: 'generation-switch' });
  });

  it('rebuilding when a building generation differs from the active one', () => {
    expect(
      computeStability({ ...settledInput, buildingGenerationId: 'g2', activeGenerationId: 'g1' }),
    ).toEqual({ kind: 'provisional', cause: 'rebuilding' });
  });

  it('generalizes ConnectionPhase: connecting→initial-load, stale→channel-stale', () => {
    expect(computeStability({ ...settledInput, phase: 'connecting' })).toEqual({
      kind: 'provisional',
      cause: 'initial-load',
    });
    expect(computeStability({ ...settledInput, phase: 'stale' })).toEqual({
      kind: 'provisional',
      cause: 'channel-stale',
    });
  });

  it('630: catchingUp ⇒ catching-up, but a real rebuild/worker-down still dominates', () => {
    expect(computeStability({ ...settledInput, catchingUp: true })).toEqual({
      kind: 'provisional',
      cause: 'catching-up',
    });
    // Dominated by a higher-severity flux.
    expect(
      computeStability({ ...settledInput, catchingUp: true, migrationState: 'MIGRATING' }),
    ).toEqual({ kind: 'provisional', cause: 'rebuilding' });
    expect(
      computeStability({ ...settledInput, catchingUp: true, indexState: 'UNAVAILABLE' }),
    ).toEqual({ kind: 'provisional', cause: 'worker-restart' });
    // More informative than a plain stale connection.
    expect(computeStability({ ...settledInput, catchingUp: true, phase: 'stale' })).toEqual({
      kind: 'provisional',
      cause: 'catching-up',
    });
  });

  it('649: stale poll BUT reachable via another channel ⇒ updating (not channel-stale)', () => {
    expect(
      computeStability({ ...settledInput, phase: 'stale', reachableViaContact: true }),
    ).toEqual({ kind: 'provisional', cause: 'updating' });
    // No contact ⇒ the genuine lost-channel case stays channel-stale.
    expect(
      computeStability({ ...settledInput, phase: 'stale', reachableViaContact: false }),
    ).toEqual({ kind: 'provisional', cause: 'channel-stale' });
    // A real higher-severity flux still dominates even when reachable.
    expect(
      computeStability({
        ...settledInput,
        phase: 'stale',
        reachableViaContact: true,
        indexState: 'UNAVAILABLE',
      }),
    ).toEqual({ kind: 'provisional', cause: 'worker-restart' });
  });
});

describe('649: connection truthfulness under load', () => {
  it('updating verdict is a calm transition headed "Catching up…"', () => {
    const v = computeVerdict({
      phase: 'stale',
      stability: { kind: 'provisional', cause: 'updating' },
      readiness: known(readyReadiness),
      reachableViaContact: true,
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy');
    expect(verdictTone(v.severity)).toBe('info'); // calm, NOT the "Reconnecting…" alarm
    expect(verdictHeadline(v)).toBe('Catching up…');
  });

  it('disconnected phase but reachable via contact ⇒ Connecting…, not a false unreachable', () => {
    const v = computeVerdict({
      phase: 'disconnected',
      stability: { kind: 'settled' },
      readiness: UNKNOWN,
      reachableViaContact: true,
    });
    expect(v.kind).toBe('connecting');
    expect(verdictHeadline(v)).toBe('Connecting…');
  });

  it('disconnected with NO contact still escalates to unreachable (no false calm)', () => {
    const v = computeVerdict({
      phase: 'disconnected',
      stability: { kind: 'settled' },
      readiness: UNKNOWN,
      reachableViaContact: false,
    });
    expect(v.kind).toBe('unreachable');
    expect(verdictHeadline(v)).toBe('Backend disconnected');
  });
});

describe('630: catching-up verdict is a calm transition', () => {
  it('renders as a busy/info transition headed "Catching up…"', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'catching-up' },
      readiness: known(readyReadiness),
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy');
    expect(verdictTone(v.severity)).toBe('info'); // calm, not an alarm
    expect(verdictHeadline(v)).toBe('Catching up…');
  });
});

describe('computeVerdict (595 §4.2) — the ONE rollup', () => {
  const settled = { kind: 'settled' } as const;

  it('THE 1.1 BOUNDARY: settled + retrieval unknown ⇒ ONE verdict (checking), never split', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'unknown' }),
    });
    // Not 'operational' (the footer's old fall-through) and not 'degraded'
    // (the header's old over-claim) — a single non-green, non-alarming verdict.
    expect(v.kind).toBe('checking');
    expect(v.severity).toBe('info');
  });

  it('disconnected ⇒ unreachable/error with the binding.unreachable reason (637 #1)', () => {
    const v = computeVerdict({ phase: 'disconnected', stability: settled, readiness: UNKNOWN });
    expect(v.kind).toBe('unreachable');
    expect(v.severity).toBe('error');
    // 637 #1: the reason code makes the unreachable state word itself loudly (CAUSE_ROWS),
    // never a silent empty result one layer up.
    expect(v.reasons).toEqual(['binding.unreachable']);
  });

  it('provisional dominates: initial-load⇒connecting, others⇒transitioning(busy)', () => {
    expect(
      computeVerdict({ phase: 'connecting', stability: { kind: 'provisional', cause: 'initial-load' }, readiness: UNKNOWN }).kind,
    ).toBe('connecting');
    const t = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'rebuilding' },
      readiness: known(readyReadiness), // healthy readiness must NOT override the transition
    });
    expect(t.kind).toBe('transitioning');
    expect(t.severity).toBe('busy');
    expect(t.reasons).toEqual(['rebuilding']);
  });

  it('settled + ready ⇒ operational/ok', () => {
    const v = computeVerdict({ phase: 'connected', stability: settled, readiness: known(readyReadiness) });
    expect(v.kind).toBe('operational');
    expect(v.severity).toBe('ok');
  });

  it('§10.3: a cosmetic degradation (LambdaMART) is degraded/INFO, not an alarm', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['lambdamart.not_configured'] }),
    });
    expect(v.kind).toBe('degraded');
    expect(v.severity).toBe('info'); // calm — search still works
  });

  it('an impairing degradation (embedding not ready) is degraded/WARN', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['worker.health.embedding_not_ready'] }),
    });
    expect(v.severity).toBe('warn');
  });

  it('627: a worker restart in flight ⇒ calm transitioning (Restarting…), NOT degraded/error', () => {
    // The supervised restart surfaces worker.recovering alongside downstream consequences
    // (index.not_healthy); its presence promotes the verdict to a calm transitioning state so a
    // routine self-heal does not read as "Service degraded".
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({
        ...readyReadiness,
        retrieval: 'degraded',
        reasonCodes: ['worker.recovering', 'index.not_healthy'],
      }),
    });
    expect(v.kind).toBe('transitioning');
    expect(v.severity).toBe('busy'); // calm tone (busy→info), reuses the worker-restart wording
    expect(v.reasons).toContain('worker-restart');
  });

  it('627: a real spawn failure (no worker.recovering) stays degraded/error', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({ ...readyReadiness, retrieval: 'degraded', reasonCodes: ['worker.spawn.failed'] }),
    });
    expect(v.kind).toBe('degraded');
    expect(v.severity).toBe('error');
  });

  it('compat-blocked index ⇒ degraded carrying the specific reindex cause code (600 Design A)', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: settled,
      readiness: known({
        ...readyReadiness,
        retrieval: 'degraded',
        reasonCodes: ['index.blocked_legacy'],
      }),
    });
    expect(v.kind).toBe('degraded');
    // The actionable cause is a real reason code (not a synthetic boolean-derived token).
    expect(v.reasons).toContain('index.blocked_legacy');
    expect(verdictHeadline(v)).toBe('Reindex required');
  });
});

describe('severityForCodes (595 §10.5)', () => {
  it('maps the cosmetic codes to info and hard failures to error', () => {
    expect(severityForCodes(['lambdamart.not_configured'])).toBe('info');
    expect(severityForCodes(['worker.spawn.failed'])).toBe('error');
  });
  it('defaults an unknown or empty code set to warn (never silently info)', () => {
    expect(severityForCodes(['some.future.code'])).toBe('warn');
    expect(severityForCodes([])).toBe('warn');
  });
  it('takes the worst-of across mixed codes', () => {
    expect(severityForCodes(['lambdamart.not_configured', 'worker.health.embedding_not_ready'])).toBe('warn');
  });
});

describe('verdictTone (595 §10.5)', () => {
  it('projects severity to a calm-vs-alarm tone', () => {
    expect(verdictTone('ok')).toBe('success');
    expect(verdictTone('info')).toBe('info');
    expect(verdictTone('busy')).toBe('info');
    expect(verdictTone('warn')).toBe('warning');
    expect(verdictTone('error')).toBe('error');
  });
});

describe('verdictHeadline', () => {
  it('words each kind, distinguishing cosmetic degraded from a hard one', () => {
    expect(verdictHeadline({ kind: 'operational', severity: 'ok', reasons: [] })).toContain('operational');
    expect(verdictHeadline({ kind: 'checking', severity: 'info', reasons: [] })).toBe('Checking…');
    expect(verdictHeadline({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] })).toBe('Rebuilding…');
    expect(verdictHeadline({ kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] })).toBe('Reduced capability');
    expect(verdictHeadline({ kind: 'degraded', severity: 'warn', reasons: ['x'] })).toBe('Service degraded');
  });
});

describe('computeVerdict — stuck-transition escalation (595 §15.2 / E4)', () => {
  const provisional = (cause: 'rebuilding' | 'generation-switch') =>
    ({ phase: 'connected', stability: { kind: 'provisional', cause }, readiness: UNKNOWN } as const);

  it('within the switch budget, a rebuild stays calm (busy)', () => {
    const v = computeVerdict({ ...provisional('rebuilding'), migrationSwitchingAgeMs: 1000, migrationSwitchingMaxDurationMs: 60000 });
    expect(v.severity).toBe('busy');
    expect(v.reasons).toEqual(['rebuilding']);
  });

  it('a PAUSED migration escalates to warn + "paused"', () => {
    const v = computeVerdict({ ...provisional('rebuilding'), migrationPaused: true });
    expect(v.severity).toBe('warn');
    expect(v.reasons).toEqual(['rebuilding', 'paused']);
    expect(verdictHeadline(v)).toBe('Rebuild paused');
  });

  it('an OVERDUE migration (age > max) escalates to warn + "overdue"', () => {
    const v = computeVerdict({ ...provisional('generation-switch'), migrationSwitchingAgeMs: 90000, migrationSwitchingMaxDurationMs: 60000 });
    expect(v.severity).toBe('warn');
    expect(v.reasons).toEqual(['generation-switch', 'overdue']);
    expect(verdictHeadline(v)).toBe('Rebuilding… (taking longer than expected)');
  });

  it('escalation applies only to rebuild/switch, not e.g. channel-stale', () => {
    const v = computeVerdict({
      phase: 'connected',
      stability: { kind: 'provisional', cause: 'channel-stale' },
      readiness: UNKNOWN,
      migrationPaused: true, // irrelevant to a connection-stale transition
    });
    expect(v.severity).toBe('busy');
    expect(v.reasons).toEqual(['channel-stale']);
  });
});

describe('presentVerdict (595 §15.1) — the ONE verdict-presentation projection', () => {
  it('bundles tone + headline + body for a cosmetic degradation (calm)', () => {
    const p = presentVerdict({ kind: 'degraded', severity: 'info', reasons: ['lambdamart.not_configured'] });
    expect(p.tone).toBe('info');
    expect(p.headline).toBe('Reduced capability');
    expect(p.body).toContain('search still works');
  });

  it('announces politely for non-error verdicts, assertively for an error verdict', () => {
    expect(presentVerdict({ kind: 'operational', severity: 'ok', reasons: [] }).announce).toEqual({
      text: 'All systems operational',
      politeness: 'status',
    });
    expect(presentVerdict({ kind: 'unreachable', severity: 'error', reasons: [] }).announce.politeness).toBe('alert');
  });

  it('the announce text is the concise headline (WCAG 4.1.3 brevity)', () => {
    const p = presentVerdict({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] });
    expect(p.announce.text).toBe('Rebuilding…');
    expect(p.announce.text).toBe(p.headline);
  });
});
