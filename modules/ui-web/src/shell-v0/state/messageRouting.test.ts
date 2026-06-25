import { describe, expect, it } from 'vitest';
import {
  isRoutineActivity,
  isRoutineOperation,
  causePushSuppressedByBanner,
  routePushSurface,
} from './messageRouting.js';
import type { SystemHealthVerdict } from './verdict.js';

describe('routePushSurface (tempdoc 613 §3/§6 — surface = f(meaning))', () => {
  it('routes an at-control ack to a RECEIPT and a window ack to a TOAST', () => {
    expect(routePushSurface('at-control')).toBe('receipt');
    expect(routePushSurface('window')).toBe('toast');
  });

  it('the altitude invariant holds: at-control can never resolve to a window toast', () => {
    expect(routePushSurface('at-control')).not.toBe('toast');
  });
});

/**
 * Tempdoc 613 §6/§10 — the Activity-inclusion routing predicate: only DIRECT-USER navigation is a
 * routine, already-witnessed action that the default Activity feed excludes.
 */
describe('isRoutineActivity', () => {
  it('treats direct-user navigation as routine (all three nav shapes)', () => {
    expect(isRoutineActivity('navigation', 'user')).toBe(true); // backend nav row
    expect(isRoutineActivity('effect', 'user', 'navigate')).toBe(true); // ingested FE navigate effect
    expect(isRoutineActivity('navigate', 'user')).toBe(true); // raw FE journal navigate row
  });

  it('does NOT treat agent/system navigation as routine (it explains a background effect)', () => {
    expect(isRoutineActivity('navigation', 'agent')).toBe(false);
    expect(isRoutineActivity('effect', 'system', 'navigate')).toBe(false);
  });

  it('does NOT treat operations/gates/index as routine (those are graded elsewhere or always stay)', () => {
    expect(isRoutineActivity('operation', 'user')).toBe(false); // graded by isRoutineOperation, not here
    expect(isRoutineActivity('gate', 'user')).toBe(false);
    expect(isRoutineActivity('index', 'system')).toBe(false);
  });

  // tempdoc 612 §3/§L — the routine set widened from navigation-only to the witnessed local-ack /
  // preference effect class (the live residual flood: set-appearance / set-ui-mode / save-settings).
  it('treats witnessed local-ack / preference EFFECT kinds as routine (ingested + raw journal shapes)', () => {
    for (const k of ['set-appearance', 'set-ui-mode', 'save-settings', 'toast', 'noop', 'open-pane']) {
      expect(isRoutineActivity('effect', 'user', k)).toBe(true); // ingested FE effect row
      expect(isRoutineActivity(k, 'user')).toBe(true); // raw FE journal row (kind IS the effect kind)
    }
  });

  it('keeps weight-bearing effect kinds foreground (durable change / undo / failure / invoke)', () => {
    for (const k of ['invoke-operation', 'undo-operation', 'apply-presentation', 'data-error']) {
      expect(isRoutineActivity('effect', 'user', k)).toBe(false);
      expect(isRoutineActivity(k, 'user')).toBe(false);
    }
  });

  it('never treats an agent/system local-ack effect as routine (originator guard)', () => {
    expect(isRoutineActivity('effect', 'agent', 'set-appearance')).toBe(false);
    expect(isRoutineActivity('effect', 'system', 'toast')).toBe(false);
  });
});

/**
 * Tempdoc 612 §3 — an OPERATION row is routine only when its DECLARED facets say it is insignificant:
 * LOW risk, no confirmation, not fully audited, mutating no Resource. Anything else is causal/audit-relevant.
 */
describe('isRoutineOperation', () => {
  const insignificant = { risk: 'LOW', confirmKind: 'NONE', audit: 'METADATA_ONLY', affectsCount: 0 };

  it('is routine for a read-only-ish op (LOW · no-confirm · not-fully-audited · no-affects)', () => {
    expect(isRoutineOperation(insignificant)).toBe(true);
    expect(isRoutineOperation({ ...insignificant, audit: 'NONE' })).toBe(true);
  });

  it('is NOT routine when any significance facet trips', () => {
    expect(isRoutineOperation({ ...insignificant, risk: 'HIGH' })).toBe(false); // destructive
    expect(isRoutineOperation({ ...insignificant, risk: 'MEDIUM' })).toBe(false); // write
    expect(isRoutineOperation({ ...insignificant, confirmKind: 'TYPED' })).toBe(false); // confirmed
    expect(isRoutineOperation({ ...insignificant, confirmKind: 'INLINE' })).toBe(false);
    expect(isRoutineOperation({ ...insignificant, audit: 'FULL_PAYLOAD' })).toBe(false); // audited
    expect(isRoutineOperation({ ...insignificant, affectsCount: 1 })).toBe(false); // mutates a Resource
  });
});

describe('causePushSuppressedByBanner (tempdoc 613 §6 R-3)', () => {
  it('suppresses a cause-push when the verdict is degraded (the banner already states the cause)', () => {
    const degraded: SystemHealthVerdict = {
      kind: 'degraded',
      severity: 'warn',
      reasons: ['inference.offline'],
    };
    expect(causePushSuppressedByBanner(degraded)).toBe(true);
  });

  it('allows the push when no banner is up (operational / connecting verdict)', () => {
    const operational: SystemHealthVerdict = { kind: 'operational', severity: 'info', reasons: [] };
    const connecting: SystemHealthVerdict = { kind: 'connecting', severity: 'info', reasons: [] };
    expect(causePushSuppressedByBanner(operational)).toBe(false);
    expect(causePushSuppressedByBanner(connecting)).toBe(false);
  });
});
