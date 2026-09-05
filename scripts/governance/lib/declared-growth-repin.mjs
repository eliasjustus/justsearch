/**
 * declared-growth-repin — a growth-licensing changeset must advance the pin in the SAME diff
 * (tempdoc 918; the structural fix tempdoc 910 §E.9 asked for).
 *
 * THE DEFECT THIS CLOSES. Changeset discovery is PR-scoped
 * (`changeset-loader.mjs`, "PR-scope discovery"): only `.md` files added or modified in the diff
 * against the baseline ref are eligible. So a `declared-growth` changeset that licenses a row's
 * measured value WITHOUT advancing that row's pin buys exactly one PR of silence. The moment the PR
 * squash-merges, the changeset is no longer in any diff, the live count still exceeds the pin, and
 * the NEXT push to `main` fails `<gate>/silent-growth` on a row nobody touched — discovered by a
 * later lane rather than by the lane that caused it. Observed three times: #517→854 and #595→885
 * (both `config-surface`, tempdoc 883 Report-back "One process finding worth carrying past this
 * lane") and #614→#613/#615 (`dead-code`, tempdoc 910 §E.9).
 *
 * THE RULE. A growth-licensing classification licenses the PIN ADVANCE, not an unpinned overflow.
 * A row whose measured value exceeds its LIVE pin therefore fails even when a covering changeset is
 * present — with this module's rule id rather than `silent-growth`, because the author did declare
 * the growth and the remedy is different (advance the pin, keep the changeset). Once the pin is
 * advanced to at least the measured value the row is no longer an exceedance at all, so it passes
 * on this PR and on every push after the squash. That equivalence — "pin advanced to ≥ measured"
 * ⇔ "row is not an exceedance" — is why one rule covers every ratchet shape without a per-gate
 * notion of "same diff".
 *
 * WHY THIS IS A LIBRARY AND NOT A RUNNER-LEVEL PASS. The obvious shared implementation — have
 * `run.mjs` upgrade every `level:'note'` `<gate>/declared-growth` finding to an error — is wrong,
 * and measurably so: gates emit that SAME rule id from their baseline-shift block for a pin that
 * WAS raised under a changeset (`dead-code/enforcer.mjs` via `verdictForBaselineShift`), which is
 * the case this rule exists to permit. Only the gate knows which of its two notes is the
 * live-exceedance one. So the rule's id, description, wording and fail decision live here, once,
 * and each gate supplies its row/measured/pin at the one branch that means "over the pin". The
 * "every gate that can be covered has wired it" half is enforced by `repin-coverage.test.mjs`,
 * not by prose.
 */

/**
 * The classification words across the kernel that LICENSE a metric moving the wrong way. Any gate
 * whose vocabulary intersects this set owes the rule at its live-exceedance branch (asserted by
 * `repin-coverage.test.mjs`). Deliberately excludes words that license a BASELINE EDIT rather than
 * an exceedance — `tier-change`, `rule-retired`, `new-rule-registered`, `slot-retraction`,
 * `grace-extension`, `intentional-divergence`, `mirror-retirement`, `guard-downgrade`,
 * `unit-renormalization`, `unused-export-shrink`, `monotonic-shrink`, `dep-shrink`,
 * `severity-decrease`, `seam-retraction` — none of which leave a row measuring above its pin.
 */
export const GROWTH_LICENSING_CLASSIFICATIONS = Object.freeze([
  'declared-growth',
  'declared-regression',
  'merge-import',
  'emergency-override',
  'test-wired-infra',
  'lockfile-import',
  'strength-regression',
]);

export const REPIN_RULE_SUFFIX = 'declared-growth-without-repin';
export const REPIN_REGRESSION_RULE_SUFFIX = 'declared-regression-without-repin';

/**
 * @param {string} rulePrefix  the gate's finding-vocabulary prefix, e.g. `dead-code`
 * @param {string} [suffix]
 * @returns {string} e.g. `dead-code/declared-growth-without-repin`
 */
export function repinRuleId(rulePrefix, suffix = REPIN_RULE_SUFFIX) {
  return `${rulePrefix}/${suffix}`;
}

/**
 * The SARIF rule description for a gate's repin rule. Merge into the gate's `ruleDescriptions` so
 * `--explain <ruleId>` and the SARIF `rules[]` catalog carry it like every other rule.
 *
 * @param {string} rulePrefix
 * @param {string} [suffix]
 * @returns {Record<string, string>}
 */
export function repinRuleDescription(rulePrefix, suffix = REPIN_RULE_SUFFIX) {
  return {
    [repinRuleId(rulePrefix, suffix)]:
      'A growth-licensing changeset covers this row, but its baseline pin was not advanced to the ' +
      'measured value in the same diff — so the licence disappears at squash-merge and the next ' +
      'push to main fails silent-growth on it (tempdoc 918).',
  };
}

/**
 * How the pin moved between the PR base and HEAD, as a message clause.
 *
 * `direction` names which way a pin has to move to accommodate a measured value: `growth` for a
 * ceiling (dead code, `any`-casts, TODOs, config keys, deps, advisories — the pin must rise),
 * `regression` for a floor (mutation test-strength — the pin must fall). Getting
 * this wrong would print "advance the pin" at an author who has to lower it.
 */
function pinMovementClause(livePin, priorPin, direction) {
  if (priorPin === undefined || priorPin === null) return '';
  if (priorPin === livePin) return ', unchanged in this diff';
  const towards = direction === 'regression' ? livePin < priorPin : livePin > priorPin;
  if (towards) {
    return `, moved ${priorPin} → ${livePin} in this diff but still short of the measured value`;
  }
  return `, moved ${priorPin} → ${livePin} in this diff — the wrong way`;
}

/**
 * Build the error finding for a row whose measured value exceeds its live pin while a
 * growth-licensing changeset is present. Always an error: the caller has already established the
 * exceedance, and an exceedance is exactly "the pin was not advanced to at least the measured
 * value".
 *
 * @param {{
 *   rulePrefix: string,
 *   classification: string,
 *   row: string,
 *   measured?: number|string|null,
 *   livePin?: number|string|null,
 *   priorPin?: number|null,
 *   baselineFile: string,
 *   unit?: string,
 *   suffix?: string,
 *   pinLine?: string,
 *   uri?: string,
 * }} input
 * @returns {{ruleId: string, level: 'error', message: string, uri: string}}
 */
export function repinFinding(input) {
  const {
    rulePrefix,
    classification,
    row,
    measured = null,
    livePin = null,
    priorPin,
    baselineFile,
    unit = '',
    suffix = REPIN_RULE_SUFFIX,
    direction = 'growth',
    pinLine,
    uri = row,
  } = input;

  const unitSuffix = unit ? ` ${unit}` : '';
  const state =
    measured === null || livePin === null
      ? `The baseline in ${baselineFile} does not carry this row`
      : `Measured ${measured}${unitSuffix}; the pin in ${baselineFile} is ${livePin}${pinMovementClause(livePin, priorPin, direction)}`;
  const remedy = pinLine ?? (measured === null ? row : `${row} ${measured}`);

  return {
    ruleId: repinRuleId(rulePrefix, suffix),
    level: 'error',
    uri,
    message:
      `${row}: '${classification}' licenses this change but the baseline pin was not moved with it. ` +
      `${state}. ` +
      'Changeset discovery is PR-scoped (scripts/governance/lib/changeset-loader.mjs), so once this PR ' +
      `squash-merges the changeset leaves the diff and the next push to main fails ${rulePrefix}/silent-growth ` +
      'on a row nobody touched (tempdoc 910 §E.9; observed #517→854, #595→885, #614→#613/#615). ' +
      `Remedy, in THIS commit: write \`${remedy}\` into ${baselineFile} and keep the changeset — for ` +
      `the gates that also ratchet their baseline file, the changeset is what licenses that edit. ` +
      `Explain the rule with \`node scripts/governance/run.mjs --explain ${repinRuleId(rulePrefix, suffix)}\`.`,
  };
}
