/**
 * Tempdoc 663 Design pass 2 (critical-review fix, 2026-07-01) — `isGpuReadingProvisional` unit tests.
 *
 * Pins the narrowed trigger: only `installing`/`starting`/`switching-variant` dim the Runtime
 * section's GPU/VRAM/Tier grid — `checking`/`stale-poll` (about a DIFFERENT fact's freshness, not
 * these GPU values) must NOT.
 */

import { describe, expect, it } from 'vitest';
import { isGpuReadingProvisional } from './BrainSurface';
import type { AiStability } from '../state/aiVerdict.js';

describe('isGpuReadingProvisional', () => {
  it('undefined stability (store not yet populated) → not provisional', () => {
    expect(isGpuReadingProvisional(undefined)).toBe(false);
  });

  it('settled → not provisional', () => {
    expect(isGpuReadingProvisional({ kind: 'settled' })).toBe(false);
  });

  it.each(['installing', 'starting', 'switching-variant'] as const)(
    'provisional cause "%s" → dims (a genuinely in-flight, GPU-relevant window)',
    (cause) => {
      const stability: AiStability = { kind: 'provisional', cause };
      expect(isGpuReadingProvisional(stability)).toBe(true);
    },
  );

  it.each(['checking', 'stale-poll'] as const)(
    'provisional cause "%s" → does NOT dim (a different fact\'s freshness, not these GPU values)',
    (cause) => {
      const stability: AiStability = { kind: 'provisional', cause };
      expect(isGpuReadingProvisional(stability)).toBe(false);
    },
  );
});
