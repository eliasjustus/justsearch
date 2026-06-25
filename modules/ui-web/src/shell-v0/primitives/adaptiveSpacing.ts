// SPDX-License-Identifier: Apache-2.0
/**
 * adaptiveSpacing — tempdoc 565 §19 / tempdoc 559 Authority VI (Adaptivity): the PLACEMENT facet.
 *
 * The THIRD sibling of `adaptiveBar.ts` (OVERFLOW — which items fit a row) and `adaptiveDensity.ts`
 * (FILL/DENSITY — which representation of one element fits its box). 559 declares Adaptivity as
 * constrained-space layout; this is the third constraint: given markers that each WANT to sit at an
 * ideal position along an axis, and a finite track, **place them so none overlap** while staying as
 * close to their ideal positions as possible. The run-spine minimap is the first consumer: it maps each
 * step to its conversation scroll fraction, so a dense run piles many nodes onto nearly the same point
 * (measured: 2.4px centre-gaps between 5–13px nodes → heavy overlap, the active ring lost, buried nodes
 * un-clickable). This makes "a minimap that overlaps its own markers" unrepresentable.
 *
 * The core is a PURE function (testable, layout-free — the reusable piece, like `computeVisibleCount` /
 * `representationFor`). It solves the 1-D minimum-separation placement problem OPTIMALLY: minimise the
 * total squared displacement from the ideal positions subject to an order-preserving minimum centre
 * separation. Substituting `z_i = x_i − Σ_{k<i} d_k` turns the min-gap constraints into a simple
 * monotonicity constraint (`z` non-decreasing), so the optimum is the **isotonic regression** of the
 * shifted ideals — computed in O(n) by **pool-adjacent-violators (PAVA)**. The result is then shifted
 * back and clamped into the track. No ad-hoc nudging: every node sits at its closest legal position.
 */

/** The minimum CENTRE-to-centre distance between two adjacent nodes so their discs don't overlap. */
function minSeparation(sizeA: number, sizeB: number, gap: number): number {
  return sizeA / 2 + sizeB / 2 + gap;
}

/**
 * Place `idealPx` centres along a `trackPx` axis so adjacent nodes never overlap (each pair separated
 * by ≥ half-sizes + `gapPx`), order is preserved, and total displacement from the ideals is minimal.
 *
 * Returns adjusted centre positions in px. Degrades gracefully:
 *  - empty / single / `trackPx <= 0` (unmeasured: jsdom, first paint) → returns `idealPx` unchanged,
 *    mirroring the sibling primitives' "honour the input until measured" contract.
 *  - over-capacity (the required separations sum to more than the track — only a 50+-node run) → the
 *    result is compressed to fit the track (residual crowding only at that extreme; true aggregation is
 *    a future extension).
 */
export function computeSpacedPositions(
  idealPx: readonly number[],
  sizesPx: readonly number[],
  trackPx: number,
  gapPx = 2,
): number[] {
  const n = idealPx.length;
  if (n === 0) return [];
  if (n === 1 || !(trackPx > 0)) return idealPx.slice();

  // Required centre offsets between consecutive nodes (d_i for i = 0..n-2).
  const d: number[] = new Array(n - 1);
  for (let i = 0; i < n - 1; i++) {
    d[i] = minSeparation(sizesPx[i] ?? 0, sizesPx[i + 1] ?? 0, gapPx);
  }
  // Cumulative offset C_i = Σ_{k<i} d_k, so the constraint x_{i+1} - x_i ≥ d_i becomes z = x - C
  // monotonically non-decreasing.
  const cum: number[] = new Array(n);
  cum[0] = 0;
  for (let i = 1; i < n; i++) cum[i] = (cum[i - 1] as number) + (d[i - 1] as number);

  // Isotonic regression (PAVA) of b_i = ideal_i - C_i: the optimal non-decreasing z minimising
  // Σ(z_i - b_i)². Pool adjacent violators into weighted blocks of equal value (block mean).
  const b: number[] = new Array(n);
  for (let i = 0; i < n; i++) b[i] = (idealPx[i] as number) - (cum[i] as number);

  const blockVal: number[] = [];
  const blockLen: number[] = [];
  for (let i = 0; i < n; i++) {
    let val = b[i] as number;
    let len = 1;
    // Merge with previous blocks while they violate monotonicity (prev mean > this mean).
    while (blockVal.length > 0 && (blockVal[blockVal.length - 1] as number) > val) {
      const pv = blockVal.pop() as number;
      const pl = blockLen.pop() as number;
      val = (val * len + pv * pl) / (len + pl);
      len += pl;
    }
    blockVal.push(val);
    blockLen.push(len);
  }
  // Expand blocks back to per-node z, then x_i = z_i + C_i.
  const x: number[] = new Array(n);
  let idx = 0;
  for (let bI = 0; bI < blockVal.length; bI++) {
    const v = blockVal[bI] as number;
    const l = blockLen[bI] as number;
    for (let k = 0; k < l; k++) {
      x[idx] = v + (cum[idx] as number);
      idx++;
    }
  }

  // Clamp into the track: the first centre ≥ its half-size, the last ≤ trackPx - its half-size.
  const lo = (sizesPx[0] ?? 0) / 2;
  const hi = trackPx - (sizesPx[n - 1] ?? 0) / 2;
  // Total span the chain needs; if it exceeds the available [lo, hi], compress to fit (over-capacity).
  const span = (x[n - 1] as number) - (x[0] as number);
  const avail = hi - lo;
  if (span > avail && span > 0) {
    const scale = avail / span;
    const first = x[0] as number;
    for (let i = 0; i < n; i++) x[i] = lo + ((x[i] as number) - first) * scale;
    return x;
  }
  // Shift the whole chain minimally so it lies within [lo, hi] (PAVA already kept it tight + ordered).
  let shift = 0;
  if ((x[0] as number) < lo) shift = lo - (x[0] as number);
  else if ((x[n - 1] as number) > hi) shift = hi - (x[n - 1] as number);
  if (shift !== 0) for (let i = 0; i < n; i++) x[i] = (x[i] as number) + shift;
  return x;
}

/** The min centre separation for a pair — exported for the spine + tests to assert the invariant. */
export function requiredSeparation(sizeA: number, sizeB: number, gapPx = 2): number {
  return minSeparation(sizeA, sizeB, gapPx);
}
