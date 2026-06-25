// @vitest-environment happy-dom

/**
 * Tempdoc 521 §16.4 — persistence + projection round-trip tests for
 * the walkthroughState slice. Validates the slice survives a write +
 * reload (validateV2 round-trip) and that the projection helpers
 * return the expected views.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  getWalkthroughProgress,
  getAllWalkthroughProgress,
  markWalkthroughStepComplete,
  setWalkthroughActiveStep,
  dismissWalkthrough,
  subscribeWalkthroughProgress,
} from './UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
});

describe('walkthroughState projection (UserStateDocument §16.4)', () => {
  it('getWalkthroughProgress returns undefined when no progress is recorded', () => {
    expect(getWalkthroughProgress('core.welcome')).toBeUndefined();
    expect(getAllWalkthroughProgress()).toEqual({});
  });

  it('markWalkthroughStepComplete adds a step id once (idempotent)', () => {
    markWalkthroughStepComplete('core.welcome', 'step-1');
    markWalkthroughStepComplete('core.welcome', 'step-1');
    markWalkthroughStepComplete('core.welcome', 'step-2');
    const p = getWalkthroughProgress('core.welcome');
    expect(p?.completedStepIds).toEqual(['step-1', 'step-2']);
    expect(p?.dismissed).toBe(false);
  });

  it('setWalkthroughActiveStep updates the active index', () => {
    setWalkthroughActiveStep('core.welcome', 0);
    expect(getWalkthroughProgress('core.welcome')?.activeStepIndex).toBe(0);
    setWalkthroughActiveStep('core.welcome', 2);
    expect(getWalkthroughProgress('core.welcome')?.activeStepIndex).toBe(2);
  });

  it('setWalkthroughActiveStep ignores invalid indices', () => {
    setWalkthroughActiveStep('core.welcome', -1);
    setWalkthroughActiveStep('core.welcome', 1.5);
    expect(getWalkthroughProgress('core.welcome')).toBeUndefined();
  });

  it('dismissWalkthrough sets the dismissed flag', () => {
    dismissWalkthrough('core.welcome');
    expect(getWalkthroughProgress('core.welcome')?.dismissed).toBe(true);
  });

  it('persists across reload', () => {
    markWalkthroughStepComplete('core.welcome', 's1');
    setWalkthroughActiveStep('core.welcome', 1);
    dismissWalkthrough('plugin.tour');

    // simulate reload — discard the in-memory document, reload from localStorage
    __resetInMemoryStateForTest();

    const welcome = getWalkthroughProgress('core.welcome');
    expect(welcome).toEqual({
      activeStepIndex: 1,
      completedStepIds: ['s1'],
      dismissed: false,
    });
    const tour = getWalkthroughProgress('plugin.tour');
    expect(tour?.dismissed).toBe(true);
  });

  it('subscribeWalkthroughProgress fires on mutations', () => {
    const observed: Array<Readonly<Record<string, unknown>>> = [];
    const off = subscribeWalkthroughProgress((s) => observed.push(s));
    markWalkthroughStepComplete('core.welcome', 's1');
    expect(observed.length).toBeGreaterThanOrEqual(1);
    expect(Object.keys(observed[observed.length - 1]!)).toContain('core.welcome');
    off();
  });
});
