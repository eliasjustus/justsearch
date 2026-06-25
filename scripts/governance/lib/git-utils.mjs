/**
 * Git utilities for the discipline-gate kernel (tempdoc 530).
 *
 * Originally factored out of `scripts/contract-governance/lib/git-utils.mjs`
 * (slice 3a-1-8f §A.16 / §A.7) so multiple gate classes can share the same
 * baseline-resolution / shallow-detection / PR-scope-diff machinery.
 *
 * Pure infrastructure — no gate-class semantics live here.
 */

import { execFileSync } from 'node:child_process';

/**
 * @param {string} cwd
 * @returns {boolean}
 */
export function isShallowRepository(cwd) {
  try {
    const out = execFileSync('git', ['rev-parse', '--is-shallow-repository'], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out === 'true';
  } catch {
    return false;
  }
}

/**
 * Resolve a baseline ref per the registry's baseline strategy.
 *
 * Strategies:
 *  - `tag`        — fixed tag (config-pinned via `ref`)
 *  - `tag-prefix` — most-recent tag matching `prefix`
 *  - `git-base`   — PR base ref with `HEAD~1` fallback (default)
 *  - `none`       — gates that don't need a git-historical baseline (e.g.,
 *                   ratchet gates that compare against a checked-in baseline
 *                   file). Returns `{ ref: null, strategy: 'none' }`.
 *
 * @param {{strategy: string, ref?: string, prefix?: string, fallback?: string}} baseline
 * @param {string} cwd
 * @returns {{ref: string|null, strategy: string, fallback?: boolean}}
 */
export function resolveBaselineRef(baseline, cwd) {
  const { strategy } = baseline;

  if (strategy === 'none') {
    return { ref: null, strategy };
  }

  if (strategy === 'tag') {
    if (!baseline.ref) {
      throw new Error("baseline.strategy='tag' requires baseline.ref");
    }
    if (!gitRefExists(baseline.ref, cwd)) {
      throw new Error(`baseline ref '${baseline.ref}' does not exist`);
    }
    return { ref: baseline.ref, strategy };
  }

  if (strategy === 'tag-prefix') {
    if (!baseline.prefix) {
      throw new Error("baseline.strategy='tag-prefix' requires baseline.prefix");
    }
    const tags = execFileSync('git', ['tag', '-l', `${baseline.prefix}*`, '--sort=-v:refname'], {
      cwd,
      encoding: 'utf8',
    })
      .split(/\r?\n/)
      .filter(Boolean);
    if (tags.length === 0) {
      if (baseline.fallback) {
        return { ref: baseline.fallback, strategy: 'tag-prefix-fallback', fallback: true };
      }
      throw new Error(
        `baseline.strategy='tag-prefix' but no tags match '${baseline.prefix}*' and no fallback configured`,
      );
    }
    return { ref: tags[0], strategy };
  }

  if (strategy === 'git-base') {
    const candidate = baseline.fallback ?? 'HEAD~1';
    if (!gitRefExists(candidate, cwd)) {
      throw new Error(
        `baseline ref '${candidate}' is unreachable. ` +
          `If running in CI, ensure 'actions/checkout' uses 'fetch-depth: 0'.`,
      );
    }
    return { ref: candidate, strategy };
  }

  throw new Error(`Unknown baseline.strategy: ${strategy}`);
}

/**
 * @param {string} ref
 * @param {string} cwd
 * @returns {boolean}
 */
export function gitRefExists(ref, cwd) {
  try {
    execFileSync('git', ['rev-parse', '--verify', `${ref}^{commit}`], {
      cwd,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Read a file at a given git ref.
 *
 * @param {string} ref
 * @param {string} filePath — repo-relative path
 * @param {string} cwd
 * @returns {string|null}   — null if file did not exist at ref
 */
export function readFileAtRef(ref, filePath, cwd) {
  try {
    const out = execFileSync('git', ['show', `${ref}:${filePath}`], {
      cwd,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
    return out;
  } catch {
    return null;
  }
}

/** Back-compat alias for the original contract-governance API. */
export const readVersionAtRef = readFileAtRef;

/**
 * Files added or modified between baseline ref and HEAD under a path filter.
 *
 * PR-scope semantics (slice 3a-1-8f §A.18): only files added/modified count.
 * Files merged from unrelated PRs (present at baseline) are ignored.
 *
 * @param {string} baselineRef
 * @param {string} pathFilter   — e.g., "contracts/wire/.changesets"
 * @param {string} cwd
 * @returns {string[]}          — repo-relative paths
 */
export function diffAddedModifiedFiles(baselineRef, pathFilter, cwd) {
  try {
    const out = execFileSync(
      'git',
      ['diff', '--diff-filter=AM', '--name-only', `${baselineRef}...HEAD`, '--', pathFilter],
      { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
    );
    return out
      .split(/\r?\n/)
      .map(s => s.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
}
