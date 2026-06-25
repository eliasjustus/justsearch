// @vitest-environment happy-dom

/**
 * Tempdoc 596 §16.5 — availability telemetry. Pins: a blocked attempt is recorded, summarizeBlocks
 * aggregates by reason (most-blocking first), and a corrupt/empty store degrades to [] (never throws).
 */
import { beforeEach, describe, expect, it } from 'vitest';
import {
  recordBlockedAttempt,
  readAvailabilityTelemetry,
  summarizeBlocks,
} from './availabilityTelemetry.js';

const KEY = 'jf.availability-telemetry';

describe('availabilityTelemetry (tempdoc 596)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('records a blocked attempt with its reason + transient flag', () => {
    recordBlockedAttempt('The local AI model is offline', false);
    const entries = readAvailabilityTelemetry();
    expect(entries).toHaveLength(1);
    const [first] = entries;
    expect(first?.reason).toBe('The local AI model is offline');
    expect(first?.transient).toBe(false);
    expect(typeof first?.timestamp).toBe('number');
  });

  it('summarizes by reason, most-blocking first', () => {
    recordBlockedAttempt('AI offline', false);
    recordBlockedAttempt('AI offline', false);
    recordBlockedAttempt('No documents indexed yet', false);
    const summary = summarizeBlocks();
    expect(summary[0]).toEqual({ reason: 'AI offline', count: 2 });
    expect(summary[1]).toEqual({ reason: 'No documents indexed yet', count: 1 });
  });

  it('a corrupt store degrades to [] (never throws)', () => {
    localStorage.setItem(KEY, '{not json');
    expect(readAvailabilityTelemetry()).toEqual([]);
    // and a subsequent record still works (overwrites the corrupt value).
    recordBlockedAttempt('x', true);
    expect(readAvailabilityTelemetry()).toHaveLength(1);
  });
});
