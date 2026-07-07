// @vitest-environment happy-dom

/**
 * Unit tests for the wire-contract parse boundary (tempdoc 683 posture split).
 *
 * parseWireContract THROWS in dev (DEV=true, the vitest default) and degrades in prod
 * (console.error + wireDriftTelemetry ring + returns the raw data). Both postures are
 * pinned explicitly here.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { z } from 'zod';
import { parseWireContract, validate } from './schemas';
import { readWireDrift, summarizeWireDrift } from './wireDriftTelemetry';
import { isDevMode } from './devMode';

// The posture seam: spied so each describe below pins one posture explicitly.
vi.mock('./devMode', { spy: true });

// Simple test schema (loose: extra fields pass through, like the hand snapshot schema).
const TestSchema = z
  .object({
    id: z.string(),
    count: z.number(),
    optional: z.string().optional(),
  })
  .loose();

describe('parseWireContract — dev posture (DEV=true: drift throws)', () => {
  beforeEach(() => {
    vi.mocked(isDevMode).mockReturnValue(true);
  });

  afterEach(() => {
    vi.mocked(isDevMode).mockRestore();
  });

  it('returns parsed data on valid input', () => {
    const input = { id: 'abc', count: 42 };
    const result = parseWireContract(TestSchema, input, 'TEST');

    expect(result).toEqual({ id: 'abc', count: 42 });
  });

  it('preserves extra fields due to loose schema', () => {
    const input = { id: 'abc', count: 42, extra: 'field' };
    const result = parseWireContract(TestSchema, input, 'TEST');

    expect(result).toEqual({ id: 'abc', count: 42, extra: 'field' });
  });

  it('throws on invalid input, carrying the context and the Zod issues', () => {
    const input = { id: 123, count: 'not a number' }; // invalid types
    expect(() => parseWireContract(TestSchema, input, 'TEST')).toThrowError(
      /\[WireContract\] TEST .*contract drift/,
    );
    // The thrown message carries the issue paths so the drift is diagnosable from the error alone.
    expect(() => parseWireContract(TestSchema, input, 'TEST')).toThrowError(/"id"/);
  });

  it('throws on null/undefined/completely wrong types', () => {
    expect(() => parseWireContract(TestSchema, null, 'TEST')).toThrow();
    expect(() => parseWireContract(TestSchema, undefined, 'TEST')).toThrow();
    expect(() => parseWireContract(TestSchema, 'just a string', 'TEST')).toThrow();
  });
});

describe('parseWireContract — prod posture (DEV=false: drift degrades + is recorded)', () => {
  let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.mocked(isDevMode).mockReturnValue(false);
    consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    localStorage.clear();
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
    vi.mocked(isDevMode).mockRestore();
  });

  it('returns parsed data on valid input (no drift recorded)', () => {
    const result = parseWireContract(TestSchema, { id: 'abc', count: 42 }, 'TEST');
    expect(result).toEqual({ id: 'abc', count: 42 });
    expect(consoleErrorSpy).not.toHaveBeenCalled();
    expect(readWireDrift()).toEqual([]);
  });

  it('returns original data on invalid input (fail-open degrade)', () => {
    const input = { id: 123, count: 'not a number' }; // invalid types
    const result = parseWireContract(TestSchema, input, 'TEST');

    // Should return original data even though invalid
    expect(result).toEqual(input);
  });

  it('handles null/undefined gracefully', () => {
    const result1 = parseWireContract(TestSchema, null, 'TEST');
    const result2 = parseWireContract(TestSchema, undefined, 'TEST');

    expect(result1).toBeNull();
    expect(result2).toBeUndefined();
  });

  it('handles completely wrong types gracefully', () => {
    const result = parseWireContract(TestSchema, 'just a string', 'TEST');
    expect(result).toBe('just a string');
  });

  it('logs the [WireContract] drift loudly', () => {
    parseWireContract(TestSchema, { id: 123, count: 'nope' }, 'GET /api/test');
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining('[WireContract] GET /api/test'),
      expect.any(Array),
    );
  });

  it('records the drift in the wireDriftTelemetry ring', () => {
    parseWireContract(TestSchema, { id: 123, count: 'nope' }, 'GET /api/test');
    parseWireContract(TestSchema, null, 'GET /api/test');
    parseWireContract(TestSchema, null, 'GET /api/other');

    const entries = readWireDrift();
    expect(entries).toHaveLength(3);
    expect(entries[0]?.context).toBe('GET /api/test');
    expect(entries[0]?.issueCount).toBeGreaterThan(0);

    const summary = summarizeWireDrift();
    expect(summary.byContext[0]).toEqual({ context: 'GET /api/test', count: 2 });
    expect(summary.byContext[1]).toEqual({ context: 'GET /api/other', count: 1 });
    expect(typeof summary.lastTimestamp).toBe('number');
  });
});

describe('validate', () => {
  it('returns success=true with parsed data on valid input', () => {
    const input = { id: 'abc', count: 42 };
    const result = validate(TestSchema, input);

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data).toEqual({ id: 'abc', count: 42 });
    }
  });

  it('returns success=false with error on invalid input', () => {
    const input = { id: 123, count: 'not a number' };
    const result = validate(TestSchema, input);

    expect(result.success).toBe(false);
    // TypeScript guard for discriminated union
    expect('error' in result).toBe(true);
    if ('error' in result) {
      expect(result.error).toBeDefined();
      expect(result.data).toEqual(input);
    }
  });

  it('provides detailed error information', () => {
    const input = { id: 123, count: 'wrong' };
    const result = validate(TestSchema, input);

    expect(result.success).toBe(false);
    // TypeScript guard for discriminated union
    expect('error' in result).toBe(true);
    if ('error' in result) {
      // Zod error should have issues array
      expect(result.error.issues.length).toBeGreaterThan(0);
    }
  });
});

describe('Schema loose behavior', () => {
  it('allows unknown fields through', () => {
    const input = {
      id: 'test',
      count: 1,
      unknownField: 'should pass through',
      nested: { also: 'passes' },
    };
    const result = validate(TestSchema, input);

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.unknownField).toBe('should pass through');
      expect(result.data.nested).toEqual({ also: 'passes' });
    }
  });
});
