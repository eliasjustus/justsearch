/**
 * Unit tests for Zod schema validation helpers.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { z } from 'zod';
import { validateWithFallback, validate } from './schemas';

// Simple test schema
const TestSchema = z.object({
  id: z.string(),
  count: z.number(),
  optional: z.string().optional(),
}).passthrough();

describe('validateWithFallback', () => {
  let consoleWarnSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleWarnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    consoleWarnSpy.mockRestore();
  });

  it('returns parsed data on valid input', () => {
    const input = { id: 'abc', count: 42 };
    const result = validateWithFallback(TestSchema, input, 'TEST');
    
    expect(result).toEqual({ id: 'abc', count: 42 });
  });

  it('preserves extra fields due to passthrough', () => {
    const input = { id: 'abc', count: 42, extra: 'field' };
    const result = validateWithFallback(TestSchema, input, 'TEST');
    
    expect(result).toEqual({ id: 'abc', count: 42, extra: 'field' });
  });

  it('returns original data on invalid input (fail-open)', () => {
    const input = { id: 123, count: 'not a number' }; // invalid types
    const result = validateWithFallback(TestSchema, input, 'TEST');
    
    // Should return original data even though invalid
    expect(result).toEqual(input);
  });

  it('handles null/undefined gracefully', () => {
    const result1 = validateWithFallback(TestSchema, null, 'TEST');
    const result2 = validateWithFallback(TestSchema, undefined, 'TEST');
    
    expect(result1).toBeNull();
    expect(result2).toBeUndefined();
  });

  it('handles completely wrong types gracefully', () => {
    const result = validateWithFallback(TestSchema, 'just a string', 'TEST');
    expect(result).toBe('just a string');
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

describe('Schema passthrough behavior', () => {
  it('allows unknown fields through', () => {
    const input = { 
      id: 'test', 
      count: 1, 
      unknownField: 'should pass through',
      nested: { also: 'passes' } 
    };
    const result = validate(TestSchema, input);
    
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.unknownField).toBe('should pass through');
      expect(result.data.nested).toEqual({ also: 'passes' });
    }
  });
});

