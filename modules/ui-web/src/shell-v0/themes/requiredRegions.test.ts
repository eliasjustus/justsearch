/**
 * 569 Fix 3 — the operability floor projects from the REAL layout authority (no phantom ids).
 */
import { describe, it, expect } from 'vitest';
import {
  REQUIRED_REGION_IDS,
  missingRequiredRegions,
  hiddenRequiredRegions,
} from './requiredRegions.js';
import { DEFAULT_LAYOUT } from '../layout/LayoutManifest.js';

describe('requiredRegions — real-id projection', () => {
  it('references only REAL layout zones, never the old phantom ids', () => {
    expect(REQUIRED_REGION_IDS).toContain('stage');
    expect(REQUIRED_REGION_IDS).not.toContain('core.approval');
    expect(REQUIRED_REGION_IDS).not.toContain('core.results');
    // Every required id is a real zone in the layout authority (drift-proof).
    for (const id of REQUIRED_REGION_IDS) expect(id in DEFAULT_LAYOUT.zones).toBe(true);
  });

  it('flags a layout that omits the content zone, passes one that keeps it', () => {
    expect(missingRequiredRegions({ regions: [{ id: 'sidebar' }] })).toEqual(['stage']);
    expect(missingRequiredRegions({ regions: [{ id: 'stage' }, { id: 'sidebar' }] })).toEqual([]);
  });

  it('569 §14 — flags the present-but-hidden loophole: a required region carrying visibleWhen', () => {
    // Present but conditional → could be orphaned. Presence-only would MISS this; the visibility
    // check catches it.
    expect(missingRequiredRegions({ regions: [{ id: 'stage', visibleWhen: 'data.x == true' }] })).toEqual([]);
    expect(hiddenRequiredRegions({ regions: [{ id: 'stage', visibleWhen: 'data.x == true' }] })).toEqual(['stage']);
    // A required region with no visibleWhen is unconditional — no violation.
    expect(hiddenRequiredRegions({ regions: [{ id: 'stage' }, { id: 'sidebar', visibleWhen: 'false' }] })).toEqual([]);
  });
});
