import { describe, expect, it } from 'vitest';
import { agencyPosture, postureChrome } from './agencyPosture.js';

describe('agencyPosture (561 C-2 — the graded continuum signal)', () => {
  it('answer-plane affordances are posture 0 (oracle), regardless of the dial', () => {
    for (const aff of ['none', 'documents', 'extract'] as const) {
      for (const level of ['watch', 'assist', 'auto'] as const) {
        expect(agencyPosture(aff, level)).toBe(0);
      }
    }
  });

  it('agent mode grades 1/2/3 with the autonomy dial', () => {
    expect(agencyPosture('agent', 'watch')).toBe(1);
    expect(agencyPosture('agent', 'assist')).toBe(2);
    expect(agencyPosture('agent', 'auto')).toBe(3);
  });
});

describe('postureChrome (561 C-2 — graded chrome copy)', () => {
  it('posture 0 keeps the neutral composer copy and no rail posture', () => {
    const c = postureChrome(0);
    expect(c.placeholder).toBe('');
    expect(c.sendLabel).toBe('Send');
    expect(c.approvalPosture).toBe('');
  });

  it('rising posture grades the send label and the approval posture', () => {
    expect(postureChrome(1).sendLabel).toBe('Send for review');
    expect(postureChrome(2).sendLabel).toBe('Send');
    expect(postureChrome(3).sendLabel).toBe('Send & auto-run');
    expect(postureChrome(1).approvalPosture).toBe('Reviewing every step');
    expect(postureChrome(2).approvalPosture).toContain('confirming writes');
    // Honesty: the AUTO posture reflects the C-4 floor (irreversible writes still confirm).
    expect(postureChrome(3).approvalPosture).toContain('confirming irreversible writes');
  });
});
