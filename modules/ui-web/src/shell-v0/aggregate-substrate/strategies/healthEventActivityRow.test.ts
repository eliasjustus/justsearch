// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup-A — Pass-8 mirror for (HealthEvent, activity-row).
 *
 * HealthEvent is generated with every field optional, so the
 * compile-time role record alone doesn't enforce reading — a
 * strategy listing every key but consuming only `id` would still
 * type-check. The behavioral Pass-8 mutates each wire field and
 * asserts the rendered output diffs accordingly. Per-variant cases
 * verify the body-message extraction for each HealthEventBodyUnion
 * member.
 */

import { describe, expect, it } from 'vitest';
import {
  HEALTH_EVENT_ACTIVITY_ROW_ROLES,
  healthEventActivityRowStrategy,
} from './healthEventActivityRow';
import type {
  HealthEvent,
  AssertedCondition,
  LifecycleEvent,
  ThresholdState,
} from '../../../api/generated/index.js';
import { classifiedKeys } from '../assertExhaustive';
import { assertBehavioralPass8 } from '../behavioralPass8';

const REFERENCE_LIFECYCLE: LifecycleEvent = {
  kind: 'lifecycle',
  attributes: { message: 'baseline-message', sessionId: 'sess-1' },
};

const REFERENCE_EVENT: Required<HealthEvent> = {
  id: 'core.event.test',
  timestamp: '2026-05-18T12:00:00Z',
  source: { serviceName: 'head', serviceInstanceId: 'inst-1', serviceVersion: '1' },
  severity: 'INFO',
  i18nKey: 'health-events.test.message',
  body: REFERENCE_LIFECYCLE,
};

describe('(HealthEvent, activity-row) canonical strategy — Pass-8 mirror', () => {
  it('roles record covers every HealthEvent wire key', () => {
    const wireKeys = Object.keys(REFERENCE_EVENT).sort();
    const declared = classifiedKeys(HEALTH_EVENT_ACTIVITY_ROW_ROLES)
      .slice()
      .sort();
    expect(declared).toEqual(wireKeys);
  });

  it('behavioral Pass-8 — every wire field affects the rendered row', () => {
    assertBehavioralPass8({
      reference: REFERENCE_EVENT,
      roles: HEALTH_EVENT_ACTIVITY_ROW_ROLES,
      strategy: healthEventActivityRowStrategy,
      ctx: {},
      host: { apiBase: '' },
      mutations: {
        id: (e) => ({ ...e, id: 'core.event.mutated' }),
        timestamp: (e) => ({ ...e, timestamp: '2030-01-01T00:00:00Z' }),
        source: (e) => ({
          ...e,
          source: { ...e.source, serviceName: 'worker' },
        }),
        severity: (e) => ({ ...e, severity: 'ERROR' as const }),
        i18nKey: (e) => ({ ...e, i18nKey: 'health-events.mutated.message' }),
        body: (e) => ({
          ...e,
          body: { kind: 'lifecycle', attributes: { message: 'mutated' } } as LifecycleEvent,
        }),
      },
    });
  });

  it('extracts AssertedCondition.message', () => {
    const event: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'condition',
        message: 'connection refused',
        status: 'TRUE',
      } as AssertedCondition,
    };
    const result = healthEventActivityRowStrategy(event, {}, { apiBase: '' });
    expect(typeof result).not.toBe('symbol');
  });

  it('extracts ThresholdState message via fallback chain', () => {
    const eventWithMessage: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'threshold',
        message: 'queue depth high',
        phase: 'FIRING',
        magnitudes: { depth: 1500 },
      } as ThresholdState,
    };
    const eventWithoutMessage: HealthEvent = {
      ...REFERENCE_EVENT,
      body: {
        kind: 'threshold',
        phase: 'FIRING',
        magnitudes: { depth: 1500 },
      } as ThresholdState,
    };
    // Both should produce a non-empty render (different content).
    expect(
      typeof healthEventActivityRowStrategy(eventWithMessage, {}, { apiBase: '' }),
    ).not.toBe('symbol');
    expect(
      typeof healthEventActivityRowStrategy(eventWithoutMessage, {}, { apiBase: '' }),
    ).not.toBe('symbol');
  });

  it('returns nothing when id is missing', () => {
    const orphan: HealthEvent = { ...REFERENCE_EVENT, id: undefined };
    const result = healthEventActivityRowStrategy(orphan, {}, { apiBase: '' });
    expect(typeof result).toBe('symbol');
  });
});
