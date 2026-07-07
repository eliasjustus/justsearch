import { describe, expect, it } from 'vitest';
import { agencyPosture, postureChrome, deriveAffordance } from './agencyPosture.js';
import type { AffordanceDerivationInput } from './agencyPosture.js';

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

describe('deriveAffordance (Search Thread S5a — the tier derivation authority)', () => {
  const base: AffordanceDerivationInput = {
    explicit: null,
    route: null,
    hasSchemaAttachment: false,
  };

  it('defaults to the retrieve floor when nothing is held', () => {
    expect(deriveAffordance(base)).toBe('retrieve');
  });

  it('explicit choice wins over everything (sticky tier)', () => {
    // Every explicit value beats every combination of the derived inputs.
    for (const explicit of ['none', 'retrieve', 'documents', 'extract', 'agent'] as const) {
      for (const route of ['search', 'ask', null] as const) {
        for (const hasSchemaAttachment of [true, false]) {
          expect(deriveAffordance({ explicit, route, hasSchemaAttachment })).toBe(explicit);
        }
      }
    }
  });

  it('a held schema attachment derives extract (Structured is an attachment, decision 6)', () => {
    expect(deriveAffordance({ ...base, hasSchemaAttachment: true })).toBe('extract');
    // ...and outranks the committed route: the attachment is the stronger held artifact.
    expect(deriveAffordance({ ...base, hasSchemaAttachment: true, route: 'ask' })).toBe('extract');
  });

  it("committed route 'ask' derives documents (submit-time input)", () => {
    expect(deriveAffordance({ ...base, route: 'ask' })).toBe('documents');
  });

  it("committed route 'search' and the standing view (route null) both stay on the floor", () => {
    expect(deriveAffordance({ ...base, route: 'search' })).toBe('retrieve');
    expect(deriveAffordance({ ...base, route: null })).toBe('retrieve');
  });

  it("never derives 'agent' or 'none' — delegation is always an explicit act (decision B14)", () => {
    for (const route of ['search', 'ask', null] as const) {
      for (const hasSchemaAttachment of [true, false]) {
        const derived = deriveAffordance({ explicit: null, route, hasSchemaAttachment });
        expect(derived).not.toBe('agent');
        expect(derived).not.toBe('none');
      }
    }
  });
});
