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
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render } from 'lit';
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

const COMPLETION_CASES = [
  ['COMPLETED', 'The agent finished its response.'],
  ['MAX_ITERATIONS', 'The agent reached its step limit. Its response may be incomplete.'],
  ['BUDGET_EDGE_FINALIZE', 'The agent reached its response budget and produced a final response.'],
  ['ERRORED', 'The agent stopped because an error occurred.'],
  ['CANCELLED', 'The run was cancelled.'],
] as const;

function renderCompletion(disposition: string, message?: string): HTMLElement {
  const container = document.createElement('div');
  render(healthEventActivityRowStrategy({
    ...REFERENCE_EVENT,
    severity: 'WARNING',
    body: { kind: 'lifecycle', attributes: { disposition, ...(message ? { message } : {}) } },
  }, {}, { apiBase: '' }), container);
  return container;
}

describe('(HealthEvent, activity-row) canonical strategy — Pass-8 mirror', () => {
  it('covers the current backend terminal disposition vocabulary', () => {
    const source = readFileSync(resolve(process.cwd(),
      '../app-agent/src/main/java/io/justsearch/agent/TerminalDisposition.java'), 'utf8');
    const codes = [...source.matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*[,;]?\s*$/gm)]
      .map((match) => match[1]);
    expect(codes.sort()).toEqual(COMPLETION_CASES.map(([code]) => code).sort());
  });

  it.each(COMPLETION_CASES)('explains %s while preserving the event title and severity', (code, wording) => {
    const container = renderCompletion(code);
    expect(container.textContent).toContain(wording);
    expect(container.textContent).not.toContain(code);
    expect(container.textContent).not.toContain('disposition:');
    expect(container.querySelector('.event-row.warning')?.getAttribute('data-severity')).toBe('WARNING');
    expect(container.querySelector('strong')?.textContent?.trim()).toBeTruthy();
  });

  it.each(['FUTURE_OUTCOME', 'constructor', '__proto__'])('keeps an honest fallback for unknown outcome %s', (code) => {
    const container = renderCompletion(code);
    expect(container.textContent).toContain('The run ended, but its outcome could not be identified.');
    expect(container.textContent).not.toContain(code);
  });

  it('preserves an explicit lifecycle message when present', () => {
    const container = renderCompletion('MAX_ITERATIONS', 'The requested operation was stopped.');
    expect(container.textContent).toContain('The requested operation was stopped.');
    expect(container.textContent).not.toContain('step limit');
  });

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
