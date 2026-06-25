import { describe, expect, it } from 'vitest';
import { presentationForSeverity } from './messageClasses.js';

/**
 * Tempdoc 613 §14 — the toast's rendered treatment (tone / announcement politeness / sticky) is ONE
 * projection of its declared severity, not a render-site literal. The load-bearing invariant: an ERROR
 * announces assertively (`alert`) and STICKS (no auto-dismiss); info/success stay polite + auto-dismissing.
 */
describe('presentationForSeverity (tempdoc 613 §14 — treatment = f(severity))', () => {
  it('error → assertive announce + sticky (an error must not silently auto-vanish)', () => {
    expect(presentationForSeverity('error')).toEqual({
      tone: 'error',
      live: 'alert',
      sticky: true,
    });
  });

  it('warning → assertive announce, not sticky', () => {
    expect(presentationForSeverity('warning')).toEqual({
      tone: 'warning',
      live: 'alert',
      sticky: false,
    });
  });

  it('success → polite announce, not sticky', () => {
    expect(presentationForSeverity('success')).toEqual({
      tone: 'success',
      live: 'status',
      sticky: false,
    });
  });

  it('info and unset both read as neutral + polite + auto-dismissing', () => {
    const neutral = { tone: 'neutral', live: 'status', sticky: false };
    expect(presentationForSeverity('info')).toEqual(neutral);
    expect(presentationForSeverity(undefined)).toEqual(neutral);
  });

  it('only error is sticky (the one severity that must persist until dismissed)', () => {
    const sticky = (['error', 'warning', 'success', 'info', undefined] as const).filter(
      (s) => presentationForSeverity(s).sticky,
    );
    expect(sticky).toEqual(['error']);
  });
});
