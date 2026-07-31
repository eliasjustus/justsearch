/**
 * Sandbox round 7 — the Data-protection card's wording is a claim about what the user can DO about
 * a state, and nothing checked it against the state it describes.
 *
 * Two authored assertions were wrong: the probe collapsed Windows PKEY 4 (NotApplicable — this
 * volume cannot be OS-encrypted at all) into UNKNOWN, and the card then told every unsensed case to
 * "get admin" — advice that cannot possibly help when there is nothing to elevate for. These tests
 * pin the derived rule: "needs admin" appears only where elevation could plausibly resolve the
 * question.
 */
import { describe, it, expect } from 'vitest';
import { atRestPresentation } from './atRestCard.js';

describe('atRestPresentation — "needs admin" only where elevation could resolve it', () => {
  it('offers elevation where encryption exists or might exist', () => {
    for (const state of ['ENCRYPTED', 'ENCRYPTING', 'UNKNOWN']) {
      expect(atRestPresentation(state, false).configuration, state).toBe('Unknown — needs admin');
    }
  });

  it('never offers elevation where there is no configuration to learn', () => {
    for (const state of ['NOT_ENCRYPTED', 'NOT_APPLICABLE']) {
      expect(atRestPresentation(state, false).configuration, state).toBe('Not applicable');
    }
  });

  it('a sensed configuration quality reads Known regardless of state', () => {
    for (const state of ['ENCRYPTED', 'NOT_ENCRYPTED', 'NOT_APPLICABLE', 'UNKNOWN']) {
      expect(atRestPresentation(state, true).configuration, state).toBe('Known');
    }
  });

  it('NOT_APPLICABLE is worded as its own state, not as Unknown', () => {
    const na = atRestPresentation('NOT_APPLICABLE', false);
    const unknown = atRestPresentation('UNKNOWN', false);
    expect(na.pillLabel).toBe('Not applicable');
    expect(na.pillLabel).not.toBe(unknown.pillLabel);
    expect(na.storeStatus).toBe('This volume cannot be encrypted by the OS');
    expect(na.storeStatus).not.toBe(unknown.storeStatus);
  });

  it('the pre-existing states keep their wording (the fix is additive, not a reword)', () => {
    expect(atRestPresentation('ENCRYPTED', false)).toMatchObject({
      pillLabel: 'Encrypted',
      storeStatus: 'Protected by OS disk encryption',
    });
    expect(atRestPresentation('NOT_ENCRYPTED', false)).toMatchObject({
      pillLabel: 'Not encrypted',
      storeStatus: 'Not encrypted',
    });
    expect(atRestPresentation('ENCRYPTING', false)).toMatchObject({ pillLabel: 'Encrypting…' });
    // An unrecognized value (a newer backend than this bundle) stays honestly Unknown, and keeps
    // the elevation offer that pairs with it.
    expect(atRestPresentation('SOME_FUTURE_STATE', false)).toMatchObject({
      pillLabel: 'Unknown',
      storeStatus: 'Unknown',
      configuration: 'Unknown — needs admin',
    });
  });
});
