/**
 * Changeset parser — slice 3a-1-8f §A.14 + §A.18.
 *
 * Reads per-PR `.md` files under `<category>/.changesets/`, extracts the
 * `evolution-rule:` YAML frontmatter declaration, and aggregates declared
 * classifications under the highest-bump-wins rule.
 *
 * §A.18 PR-scope: only files added or modified in the PR's diff against
 * the baseline ref are eligible. Files merged from unrelated PRs (and
 * present at baseline) are ignored.
 *
 * Allowed `evolution-rule` values (post §A.10 dry-run, 2026-05-07):
 *   additive-optional, additive-required, enum-value-added,
 *   rename, remove, enum-value-removed, enum-value-renamed,
 *   type-change, package-rename
 */

import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { resolve } from 'node:path';

import { diffAddedModifiedFiles } from '../../lib/git-utils.mjs';
import { parseFrontmatter } from '../../lib/frontmatter.mjs';

const ALLOWED_RULES = new Set([
  'additive-optional',
  'additive-required',
  'enum-value-added',
  'rename',
  'remove',
  'enum-value-removed',
  'enum-value-renamed',
  'type-change',
  'package-rename',
]);

// Severity ordering for highest-bump-wins.
const SEVERITY_RANK = {
  'additive-optional': 1,
  'additive-required': 3,
  'enum-value-added': 2,
  rename: 5,
  remove: 5,
  'enum-value-removed': 5,
  'enum-value-renamed': 5,
  'type-change': 5,
  'package-rename': 5,
};

const REQUIRED_BUMP = {
  'additive-optional': 'patch',
  'additive-required': 'minor',
  'enum-value-added': 'minor',
  rename: 'major',
  remove: 'major',
  'enum-value-removed': 'major',
  'enum-value-renamed': 'major',
  'type-change': 'major',
  'package-rename': 'major',
};

/**
 * @param {{repoRoot: string, changesetsDir: string, baselineRef: string, fixtureMode?: boolean}} options
 * @returns {Array<{file: string, rule: string, body: string}>}
 */
export function parseChangesets(options) {
  const { repoRoot, changesetsDir, baselineRef, fixtureMode } = options;
  const fullDir = resolve(repoRoot, changesetsDir);
  if (!existsSync(fullDir)) return [];

  // §A.18 PR-scope discovery. In fixture mode, we use all *.md files under
  // the dir (no git diff).
  let candidateFiles;
  if (fixtureMode) {
    candidateFiles = readdirSync(fullDir)
      .filter(f => f.endsWith('.md'))
      .map(f => resolve(fullDir, f));
  } else {
    const diffPaths = diffAddedModifiedFiles(baselineRef, changesetsDir, repoRoot);
    candidateFiles = diffPaths
      .filter(p => p.endsWith('.md'))
      .map(p => resolve(repoRoot, p));
  }

  const declarations = [];
  for (const file of candidateFiles) {
    if (!existsSync(file) || statSync(file).isDirectory()) continue;
    if (file.endsWith('README.md')) continue; // documentation, not a declaration

    const content = readFileSync(file, 'utf8');
    const parsed = parseFrontmatter(content);
    if (!parsed) continue; // no frontmatter — informational tolerated
    const rule = parsed.frontmatter['evolution-rule'];
    if (!rule) continue;
    if (!ALLOWED_RULES.has(rule)) {
      throw new Error(
        `Invalid evolution-rule '${rule}' in ${file}. Allowed: ${[...ALLOWED_RULES].join(', ')}`,
      );
    }
    declarations.push({ file, rule, body: parsed.body.trim() });
  }
  return declarations;
}

/**
 * Highest-bump-wins aggregation per §A.14.
 *
 * @param {Array<{rule: string}>} declarations
 * @returns {{rule: string|null, declarations: number, requiredBump: string|null}}
 */
export function aggregateClassifications(declarations) {
  if (declarations.length === 0) {
    return { rule: null, declarations: 0, requiredBump: null };
  }
  let topRule = declarations[0].rule;
  for (const d of declarations.slice(1)) {
    if (SEVERITY_RANK[d.rule] > SEVERITY_RANK[topRule]) {
      topRule = d.rule;
    }
  }
  return {
    rule: topRule,
    declarations: declarations.length,
    requiredBump: REQUIRED_BUMP[topRule],
  };
}

// parseFrontmatter is imported from the substrate (tempdoc 530).
