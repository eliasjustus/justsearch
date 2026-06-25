/**
 * Tests for the tempdoc 577 §2.13 #17 / §2.14 Root III authority-space projection.
 *
 * The disposition lattice mirrors the backend gate (risk × autonomy × reversibility). These pin the
 * policy the panel shows the user — the calibration approximation, NOT a per-call verdict.
 */
import { describe, it, expect } from 'vitest';
import {
  toolDisposition,
  dispositionLabel,
  DISPOSITION_ORDER,
  type ToolDisposition,
} from './authoritySpace.js';

describe('authoritySpace.toolDisposition', () => {
  it('HIGH risk always confirms, regardless of posture', () => {
    for (const level of ['watch', 'assist', 'auto'] as const) {
      expect(toolDisposition('high', true, level)).toBe('always-confirms');
      expect(toolDisposition('high', false, level)).toBe('always-confirms');
    }
  });

  it('Watch posture asks first for everything below HIGH', () => {
    expect(toolDisposition('low', true, 'watch')).toBe('asks-first');
    expect(toolDisposition('medium', true, 'watch')).toBe('asks-first');
    expect(toolDisposition('medium', false, 'watch')).toBe('asks-first');
  });

  it('Auto auto-runs LOW and reversible MEDIUM, but confirms irreversible MEDIUM (the C-4 floor)', () => {
    expect(toolDisposition('low', false, 'auto')).toBe('auto-runs');
    expect(toolDisposition('medium', true, 'auto')).toBe('auto-runs');
    expect(toolDisposition('medium', undefined, 'auto')).toBe('auto-runs'); // unknown undo → not strictly false
    expect(toolDisposition('medium', false, 'auto')).toBe('asks-first');
  });

  it('Assist (default) auto-runs LOW read-only, confirms MEDIUM write', () => {
    expect(toolDisposition('low', true, 'assist')).toBe('auto-runs');
    expect(toolDisposition('medium', true, 'assist')).toBe('asks-first');
    expect(toolDisposition('medium', false, 'assist')).toBe('asks-first');
  });

  it('missing risk is treated as LOW', () => {
    expect(toolDisposition(undefined, true, 'assist')).toBe('auto-runs');
    expect(toolDisposition(undefined, true, 'watch')).toBe('asks-first');
  });
});

describe('authoritySpace.dispositionLabel', () => {
  it('maps every disposition to a human label', () => {
    const all: ToolDisposition[] = ['auto-runs', 'asks-first', 'always-confirms', 'blocked'];
    for (const d of all) {
      expect(dispositionLabel(d)).toBeTruthy();
    }
    expect(dispositionLabel('asks-first')).toBe('Asks you first');
    expect(dispositionLabel('always-confirms')).toBe('Always confirms');
  });
});

describe('authoritySpace.DISPOSITION_ORDER', () => {
  it('surfaces trust-relevant groups (always-confirms / asks-first) ABOVE auto-runs', () => {
    expect(DISPOSITION_ORDER.indexOf('always-confirms')).toBeLessThan(
      DISPOSITION_ORDER.indexOf('auto-runs'),
    );
    expect(DISPOSITION_ORDER.indexOf('asks-first')).toBeLessThan(
      DISPOSITION_ORDER.indexOf('auto-runs'),
    );
  });
});
