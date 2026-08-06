/**
 * Tempdoc 565 §19 / 559 Authority VI (the PLACEMENT facet) — the pure min-separation placement. (The
 * ResizeObserver/track-measurement integration is layout-driven and validated live; the decision it
 * feeds on is pure and pinned here, the same split as adaptiveBar/adaptiveDensity tests.)
 */
import { describe, it, expect } from 'vitest';
import { clusterAdjacent, computeSpacedPositions, requiredSeparation } from './adaptiveSpacing.js';

const EPS = 1e-6;

/** Assert no two adjacent nodes overlap: every centre-gap ≥ the required separation. */
function expectNoOverlap(out: number[], sizes: number[], gap = 2): void {
  for (let i = 0; i < out.length - 1; i++) {
    const need = requiredSeparation(sizes[i]!, sizes[i + 1]!, gap);
    expect(out[i + 1]! - out[i]!).toBeGreaterThanOrEqual(need - 1e-4);
  }
}

/** Assert positions are in non-decreasing order (timeline order preserved). */
function expectOrdered(out: number[]): void {
  for (let i = 0; i < out.length - 1; i++) {
    expect(out[i + 1]!).toBeGreaterThanOrEqual(out[i]! - EPS);
  }
}

describe('computeSpacedPositions — non-overlapping placement', () => {
  it('spreads a tight cluster so no nodes overlap, symmetric about the cluster centre', () => {
    const ideal = [100, 101, 102];
    const sizes = [10, 10, 10];
    const out = computeSpacedPositions(ideal, sizes, 500, 2);
    expectNoOverlap(out, sizes);
    expectOrdered(out);
    // required gap = 5 + 5 + 2 = 12; the cluster (centre ~101) becomes [89, 101, 113].
    expect(out[1]!).toBeCloseTo(101, 5); // centre node stays at the cluster centre
    expect(out[2]! - out[1]!).toBeCloseTo(12, 5);
    expect(out[1]! - out[0]!).toBeCloseTo(12, 5);
  });

  it('leaves an already-spaced input ~unchanged (minimal displacement)', () => {
    const ideal = [100, 120, 140];
    const sizes = [10, 10, 10];
    const out = computeSpacedPositions(ideal, sizes, 500, 2);
    // The input already satisfies the 12px separation with room → no displacement.
    expect(out).toEqual(ideal);
  });

  it('only nudges the violating pair, preserving well-separated neighbours', () => {
    // first two collide (gap 3 < 12), the third is far.
    const ideal = [100, 103, 300];
    const sizes = [10, 10, 10];
    const out = computeSpacedPositions(ideal, sizes, 500, 2);
    expectNoOverlap(out, sizes);
    expectOrdered(out);
    expect(out[2]!).toBeCloseTo(300, 5); // the far node is untouched
    expect(out[1]! - out[0]!).toBeCloseTo(12, 5); // the colliding pair is separated to exactly 12
  });

  it('respects per-node sizes in the required separation (mixed sizes)', () => {
    const ideal = [50, 51, 52];
    const sizes = [12.8, 5.8, 0.3]; // terminal / secondary / ambient
    const out = computeSpacedPositions(ideal, sizes, 500, 2);
    expectNoOverlap(out, sizes);
    expectOrdered(out);
  });

  it('clamps within the track and stays ordered when over capacity (extreme density)', () => {
    const ideal = [10, 20, 30, 40, 50];
    const sizes = [12.8, 12.8, 12.8, 12.8, 12.8];
    const trackPx = 50; // far too small for 5 × ~14.8px separations
    const out = computeSpacedPositions(ideal, sizes, trackPx, 2);
    expectOrdered(out);
    // compressed to fit the track [halfSize, trackPx - halfSize]
    expect(out[0]!).toBeGreaterThanOrEqual(12.8 / 2 - 1e-4);
    expect(out[out.length - 1]!).toBeLessThanOrEqual(trackPx - 12.8 / 2 + 1e-4);
  });

  it('shifts the chain into the track when the ideal would clip the top edge', () => {
    const ideal = [1, 2, 3];
    const sizes = [10, 10, 10];
    const out = computeSpacedPositions(ideal, sizes, 500, 2);
    expectNoOverlap(out, sizes);
    expectOrdered(out);
    expect(out[0]!).toBeGreaterThanOrEqual(10 / 2 - 1e-4); // first centre ≥ its radius
  });

  it('degrades gracefully: empty, single, and unmeasured track return the input', () => {
    expect(computeSpacedPositions([], [], 500)).toEqual([]);
    expect(computeSpacedPositions([42], [10], 500)).toEqual([42]);
    expect(computeSpacedPositions([100, 101], [10, 10], 0)).toEqual([100, 101]); // unmeasured track
    expect(computeSpacedPositions([100, 101], [10, 10], -1)).toEqual([100, 101]);
  });
});

/**
 * Tempdoc 814 §D4 — the AGGREGATION facet: the extension `computeSpacedPositions` deferred. Markers
 * still closer than the min gap after spacing collapse into ONE counted group, so spine density is
 * bounded by the track (and by the run's landmark structure), never by event count.
 */
describe('clusterAdjacent — bounded marker density', () => {
  const MERGEABLE = (n: number): boolean[] => new Array<boolean>(n).fill(true);

  it('merges a run of markers closer than the min gap into ONE counted group', () => {
    const groups = clusterAdjacent([100, 104, 108, 112], MERGEABLE(4), 14);
    expect(groups.length).toBe(1);
    expect(groups[0]!.indices).toEqual([0, 1, 2, 3]);
    expect(groups[0]!.positionPx).toBeCloseTo(106, 6); // the members' mean
  });

  it('does NOT merge markers at or beyond the min gap (each stays its own marker)', () => {
    const groups = clusterAdjacent([0, 14, 28], MERGEABLE(3), 14);
    expect(groups.map((g) => g.indices)).toEqual([[0], [1], [2]]);
    expect(groups.map((g) => g.positionPx)).toEqual([0, 14, 28]);
  });

  it('splits a run at the first gap ≥ the threshold (two clusters, not one)', () => {
    const groups = clusterAdjacent([0, 5, 10, 40, 45], MERGEABLE(5), 14);
    expect(groups.map((g) => g.indices)).toEqual([
      [0, 1, 2],
      [3, 4],
    ]);
  });

  it('LANDMARK immunity: a landmark is always its own marker AND breaks the run around it', () => {
    // Five markers within 4px of each other; index 2 is a landmark (a turn / node boundary / steer).
    const mergeable = [true, true, false, true, true];
    const groups = clusterAdjacent([100, 104, 108, 112, 116], mergeable, 14);
    expect(groups.map((g) => g.indices)).toEqual([[0, 1], [2], [3, 4]]);
    // The landmark keeps its exact measured position (never averaged into a cluster).
    expect(groups[1]!.positionPx).toBe(108);
  });

  it('keeps every input represented exactly once, in order (a count is never lost)', () => {
    const positions = [0, 2, 4, 6, 30, 31, 32, 90];
    const mergeable = [true, true, true, true, true, false, true, true];
    const groups = clusterAdjacent(positions, mergeable, 14);
    expect(groups.flatMap((g) => g.indices)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    // Density is bounded by structure: 8 events → 5 markers here, and the count rides on the badge.
    expect(groups.map((g) => g.indices)).toEqual([[0, 1, 2, 3], [4], [5], [6], [7]]);
    expect(groups[0]!.indices.length).toBe(4);
  });

  it('degrades gracefully on an empty input', () => {
    expect(clusterAdjacent([], [], 14)).toEqual([]);
  });
});
