// @vitest-environment happy-dom

import { describe, it, expect } from 'vitest';
import { evaluateSafeMath, formatSafeMathResult, SafeMathError } from './safeMath.js';

describe('safeMath — happy path', () => {
  it('evaluates integer arithmetic', () => {
    expect(evaluateSafeMath('1 + 2')).toBe(3);
    expect(evaluateSafeMath('7 - 4')).toBe(3);
    expect(evaluateSafeMath('6 * 7')).toBe(42);
    expect(evaluateSafeMath('20 / 4')).toBe(5);
  });

  it('honors operator precedence', () => {
    expect(evaluateSafeMath('2 + 3 * 4')).toBe(14);
    expect(evaluateSafeMath('20 - 4 * 2')).toBe(12);
    expect(evaluateSafeMath('10 / 2 + 3')).toBe(8);
  });

  it('honors parentheses', () => {
    expect(evaluateSafeMath('(2 + 3) * 4')).toBe(20);
    expect(evaluateSafeMath('2 * (3 + 4)')).toBe(14);
    expect(evaluateSafeMath('((1 + 2)) * 3')).toBe(9);
  });

  it('handles decimals', () => {
    expect(evaluateSafeMath('1.5 + 2.5')).toBe(4);
    expect(evaluateSafeMath('0.1 * 10')).toBeCloseTo(1, 10);
  });

  it('handles unary minus / plus', () => {
    expect(evaluateSafeMath('-3 + 5')).toBe(2);
    expect(evaluateSafeMath('+5 - 3')).toBe(2);
    expect(evaluateSafeMath('-(2 + 3)')).toBe(-5);
    expect(evaluateSafeMath('--5')).toBe(5);
  });
});

describe('safeMath — error paths', () => {
  it('rejects empty input', () => {
    expect(() => evaluateSafeMath('')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('   ')).toThrow(SafeMathError);
  });

  it('rejects unknown characters', () => {
    expect(() => evaluateSafeMath('1 + a')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('eval(1)')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('1 ^ 2')).toThrow(SafeMathError);
  });

  it('rejects malformed expressions', () => {
    expect(() => evaluateSafeMath('1 +')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('* 2')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('(1 + 2')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('1 + 2)')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('1 2')).toThrow(SafeMathError);
  });

  it('rejects division by zero', () => {
    expect(() => evaluateSafeMath('1 / 0')).toThrow(/Division by zero/);
    expect(() => evaluateSafeMath('5 / (2 - 2)')).toThrow(/Division by zero/);
  });

  it('does NOT invoke eval / Function', () => {
    // Calling new Function('x') with our input shape would error or
    // succeed depending on the input, but evaluateSafeMath must never
    // forward to it. Sanity: a malicious-looking string is rejected as
    // unknown-character at tokenize, not silently executed.
    expect(() => evaluateSafeMath('alert(1)')).toThrow(SafeMathError);
    expect(() => evaluateSafeMath('globalThis')).toThrow(SafeMathError);
  });
});

describe('formatSafeMathResult', () => {
  it('drops trailing zeros for non-integer results', () => {
    expect(formatSafeMathResult(1.5)).toBe('1.5');
    expect(formatSafeMathResult(2)).toBe('2');
    expect(formatSafeMathResult(0.1 + 0.2)).toBe('0.3');
  });
});
