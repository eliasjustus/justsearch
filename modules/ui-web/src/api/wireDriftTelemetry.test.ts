// @vitest-environment happy-dom

/**
 * Tempdoc 683 — wire-drift telemetry. Pins: a prod-posture drift is recorded, summarizeWireDrift
 * aggregates by context (most-drifting first) + carries the last timestamp, and a corrupt/empty
 * store degrades to [] (never throws). Mirrors availabilityTelemetry.test.ts.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { recordWireDrift, readWireDrift, summarizeWireDrift } from './wireDriftTelemetry.js';

const KEY = 'jf.wire-drift-telemetry';

describe('wireDriftTelemetry (tempdoc 683)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('records a drift event with its context + issue count', () => {
    recordWireDrift('POST /api/knowledge/search', 3);
    const entries = readWireDrift();
    expect(entries).toHaveLength(1);
    const [first] = entries;
    expect(first?.context).toBe('POST /api/knowledge/search');
    expect(first?.issueCount).toBe(3);
    expect(typeof first?.timestamp).toBe('number');
  });

  it('summarizes by context, most-drifting first, with the last timestamp', () => {
    recordWireDrift('GET /api/status', 1);
    recordWireDrift('GET /api/status', 2);
    recordWireDrift('POST /api/knowledge/search', 1);
    const summary = summarizeWireDrift();
    expect(summary.byContext[0]).toEqual({ context: 'GET /api/status', count: 2 });
    expect(summary.byContext[1]).toEqual({ context: 'POST /api/knowledge/search', count: 1 });
    expect(typeof summary.lastTimestamp).toBe('number');
    expect(summary.lastTimestamp).toBe(readWireDrift().at(-1)?.timestamp);
  });

  it('an empty store summarizes to no contexts and a null lastTimestamp', () => {
    const summary = summarizeWireDrift();
    expect(summary.byContext).toEqual([]);
    expect(summary.lastTimestamp).toBeNull();
  });

  it('a corrupt store degrades to [] (never throws)', () => {
    localStorage.setItem(KEY, '{not json');
    expect(readWireDrift()).toEqual([]);
    // and a subsequent record still works (overwrites the corrupt value).
    recordWireDrift('GET /api/status', 1);
    expect(readWireDrift()).toHaveLength(1);
  });
});
