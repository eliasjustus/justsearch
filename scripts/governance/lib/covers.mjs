/**
 * covers — the kernel-level bounded-exception protocol (tempdoc 576 §4).
 *
 * A `covers: target=value` changeset frontmatter field declares a VALUE-BOUNDED, persistent
 * exception: it keeps a metric covered across later commits of a continuously-integrated trunk
 * (closing the PR-scoped-changeset treadmill) but ONLY while the metric stays within the declared
 * bound — so growth/regression past it still re-fails. Lifted out of the ui-bundle gate so any
 * ratchet gate can opt in; the per-gate value DIRECTION (lower-is-better bytes vs higher-is-better
 * strength) is supplied via `withinBound`, honoring the de-risked finding that gate value-models
 * diverge (§9): this is a protocol with a gate-specific value interpreter, not a uniform metric.
 */

/**
 * Parse a changeset's optional flat `covers: t1=v1,t2=v2` frontmatter into a `{target: number}`
 * map of the ceilings/floors it acknowledges. Returns `null` when absent. Flat `target=value`
 * form because the frontmatter parser is flat key:value only (no nested YAML).
 *
 * @param {Record<string, string> | undefined} frontmatter
 * @returns {Record<string, number> | null}
 */
export function parseCovers(frontmatter) {
  const raw = frontmatter?.covers;
  if (typeof raw !== 'string' || raw.trim() === '') return null;
  const out = {};
  for (const pair of raw.split(',')) {
    const [target, value] = pair.split('=').map((s) => s.trim());
    const n = Number(value);
    if (target && Number.isFinite(n)) out[target] = n;
  }
  return Object.keys(out).length > 0 ? out : null;
}

/**
 * Whether a committed `covers`-bearing changeset PERSISTENTLY covers an exceedance of `target` at
 * `current`. A changeset persists iff it (a) is one of `coveringClassifications`, (b) carries an
 * explicit `covers:` bound for this target, and (c) `current` is still WITHIN that bound. The
 * explicit bound keeps it SAFE — past the bound it re-fails.
 *
 * @param {Array<{classification: string, frontmatter?: Record<string, string>}>} declarations
 * @param {string} target
 * @param {number} current
 * @param {{ coveringClassifications?: string[], withinBound?: (current: number, bound: number) => boolean }} [options]
 *   withinBound defaults to `current <= bound` (lower-is-better budgets, e.g. bytes/LOC). For
 *   higher-is-better metrics (e.g. mutation strength) pass `(cur, floor) => cur >= floor`.
 */
export function persistentlyCovers(declarations, target, current, options = {}) {
  const covering = options.coveringClassifications ?? ['declared-growth', 'emergency-override'];
  const withinBound = options.withinBound ?? ((cur, bound) => cur <= bound);
  return declarations.some((d) => {
    if (!covering.includes(d.classification)) return false;
    const covers = parseCovers(d.frontmatter);
    return covers !== null && target in covers && withinBound(current, covers[target]);
  });
}

/**
 * The declared `covers:` bound for `target` among the covering changesets, or null. Lets a gate
 * emit a LOUD, self-explaining revocation message (tempdoc 576 §4.3) — "current X exceeds the
 * declared covers bound Y; raise the bound or reduce the metric" — instead of an opaque failure.
 *
 * @returns {number | null}
 */
export function coversBoundFor(declarations, target, options = {}) {
  const covering = options.coveringClassifications ?? ['declared-growth', 'emergency-override'];
  let bound = null;
  for (const d of declarations) {
    if (!covering.includes(d.classification)) continue;
    const covers = parseCovers(d.frontmatter);
    if (covers !== null && target in covers) {
      bound = bound === null ? covers[target] : Math.max(bound, covers[target]);
    }
  }
  return bound;
}
