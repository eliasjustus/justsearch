// @vitest-environment happy-dom

/**
 * 569 §19 Seam 4 — the one adaptation/accessibility authority: persist per-profile + project to global
 * DOM state, omitted axes untouched.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { applyAdaptationProfile, getAdaptationProfile } from './adaptationProfile.js';
import { __resetUserStateForTest, getDocument } from './UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
  document.documentElement.className = '';
});

describe('applyAdaptationProfile', () => {
  it('persists the axes per-profile and projects contrast/motion to global classes', () => {
    applyAdaptationProfile({ density: 'compact', contrast: 'high', motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({
      density: 'compact',
      contrast: 'high',
      motion: 'reduced',
    });
    expect(getDocument().userConfig.density).toBe('compact'); // density threads via userConfig
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(true);
  });

  it('merges partial updates (omitted axes untouched) and toggles back off', () => {
    applyAdaptationProfile({ contrast: 'high' });
    applyAdaptationProfile({ motion: 'reduced' });
    expect(getAdaptationProfile()).toEqual({ contrast: 'high', motion: 'reduced' });
    applyAdaptationProfile({ contrast: 'normal' });
    expect(document.documentElement.classList.contains('high-contrast')).toBe(false);
    expect(document.documentElement.classList.contains('motion-reduced')).toBe(true); // untouched
  });

  it('a fresh profile projects nothing (no fight with the legacy appearance contrast)', () => {
    document.documentElement.classList.add('high-contrast'); // pretend appearance set it
    applyAdaptationProfile({ density: 'spacious' }); // contrast/motion unset → not projected
    expect(document.documentElement.classList.contains('high-contrast')).toBe(true);
  });
});
