/**
 * Sandbox round 7 — the Data-protection card's wording is a claim about what the user can DO about
 * a state, and nothing checked it against the state it describes.
 *
 * Two authored assertions were wrong: the probe collapsed Windows PKEY 4 (NotApplicable — this
 * volume cannot be OS-encrypted at all) into UNKNOWN, and the card then told every unsensed case to
 * "get admin" — advice that cannot possibly help when there is nothing to elevate for. These tests
 * pin the derived rule: "needs admin" appears only where elevation could plausibly resolve the
 * question.
 *
 * Tempdoc 798: a THIRD case was still wrong after round 7 — UNKNOWN itself unconditionally offered
 * elevation, on the theory that an unsensed state "might" be encrypted. On a machine with nothing
 * encryptable, probed in a session that already held admin rights, this told an administrator to
 * get administrator rights to resolve something admin rights cannot resolve. Nothing in this
 * codebase ever checks whether the current process IS elevated, so "needs admin" is never a
 * verified claim for UNKNOWN — it now requires a positive signal (source `shell-property` +
 * confidence `LOW`: the probe ran and returned a value it couldn't classify) rather than being
 * assumed from the state string alone. `source === 'none'` (no answer at all — the "nothing
 * encryptable" shape) and an unrecognised future state both make no claim about elevation.
 */
import { describe, it, expect } from 'vitest';
import { atRestPresentation } from './atRestCard.js';

describe('atRestPresentation — "needs admin" only where elevation could resolve it', () => {
  it('offers elevation where an encrypted (or encrypting) volume is known to exist', () => {
    for (const state of ['ENCRYPTED', 'ENCRYPTING']) {
      expect(atRestPresentation(state, false).configuration, state).toBe('Unknown — needs admin');
    }
  });

  it('UNKNOWN offers elevation only when the probe genuinely ran and returned an indeterminate value', () => {
    expect(
      atRestPresentation('UNKNOWN', false, 'shell-property', 'LOW').configuration,
    ).toBe('Unknown — needs admin');
  });

  it('UNKNOWN never offers elevation when the probe gave no signal at all (798 regression)', () => {
    // source 'none' — no probe answer at all: non-Windows, execution failure, or (798 Part 2) the
    // property was absent, e.g. no BitLocker feature present at all. The exact shape of the bug: a
    // machine with nothing encryptable must not be told that admin rights would help.
    expect(atRestPresentation('UNKNOWN', false, 'none', 'UNKNOWN').configuration).toBe(
      'Not applicable',
    );
    // Called with no source/confidence at all (e.g. an older wire payload) makes the same
    // conservative non-claim rather than assuming elevation would help.
    expect(atRestPresentation('UNKNOWN', false).configuration).toBe('Not applicable');
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
    // An unrecognized value (a newer backend than this bundle) stays honestly Unknown, but (798) no
    // longer defaults to an elevation offer it cannot back up — no source/confidence accompanies it,
    // so it gets the conservative, no-claim wording instead.
    expect(atRestPresentation('SOME_FUTURE_STATE', false)).toMatchObject({
      pillLabel: 'Unknown',
      storeStatus: 'Unknown',
      configuration: 'Not applicable',
    });
  });
});
