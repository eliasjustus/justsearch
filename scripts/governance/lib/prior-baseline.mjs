/**
 * prior-baseline — the ONE place a ratchet gate reads its baseline as it stood before the PR
 * (tempdoc 910).
 *
 * Every baseline-shift check needs the same two-branch read: in fixtureMode the prior state is a
 * committed `_baseline/<path>` tree; in a real run it is the file at `baselineRef` in git. Six
 * gates hand-rolled it, and the copies had already drifted into a real defect —
 * `test-efficacy/enforcer.mjs` called `readFileAtRef` with an OPTIONS OBJECT against its POSITIONAL
 * signature (`git-utils.mjs:236`), so `git show [object Object]:undefined` threw, the catch
 * returned null, and its prior baseline was ALWAYS null outside fixture mode. The rule could not
 * fire in any real run, and the gate's own fixtures could not notice because the fixture branch
 * returns the LIVE baseline as the prior.
 *
 * That is the argument for centralizing THIS and not the covered→note/silent→error dispatch: the
 * dispatch is one `if` that each gate's `truth-table.mjs` already owns as a registered kernel
 * artifact (`detectBaselineTamper` exists for gates like register-guard-resolution that supply both
 * variants instead), whereas the read is a multi-line branch with a git call inside it — the shape
 * that actually rots when copied.
 *
 * Returns the raw text so each gate keeps its own parse (`<path> <count> <date>` vs JSON).
 *
 * @param {{
 *   fixtureMode?: boolean,
 *   fixtureRoot?: string,
 *   sourceRoot: string,
 *   baselineRef?: string|null,
 *   baselinePath: string,
 * }} options
 * @returns {string|null} null means "no prior state to compare against" — a new baseline, or a ref
 *   that does not carry the file. Callers must treat null as "skip", never as "everything grew".
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { readFileAtRef } from './git-utils.mjs';

export function readPriorBaselineText({ fixtureMode = false, fixtureRoot, sourceRoot, baselineRef, baselinePath }) {
  if (fixtureMode && fixtureRoot) {
    const p = resolve(fixtureRoot, '_baseline', baselinePath);
    return existsSync(p) ? readFileSync(p, 'utf8') : null;
  }
  if (!baselineRef) return null;
  return readFileAtRef(baselineRef, baselinePath, sourceRoot);
}
