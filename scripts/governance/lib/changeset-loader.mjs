/**
 * Generic changeset loader for the discipline-gate kernel (tempdoc 530).
 *
 * Factored from `scripts/contract-governance/lib/changeset-parser.mjs`. The
 * contract-governance copy is protobuf-shaped (hardcoded `ALLOWED_RULES`,
 * `SEVERITY_RANK`, `REQUIRED_BUMP` tables); this version takes the allowed
 * classification set as a parameter so each gate class declares its own.
 *
 * PR-scope discovery (slice 3a-1-8f §A.18 precedent): only files added or
 * modified in the PR's diff against the baseline ref are eligible. Files
 * merged from unrelated PRs are ignored. In `fixtureMode`, all *.md files
 * under the directory are loaded.
 */

import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

import { diffAddedModifiedFiles } from './git-utils.mjs';
import { parseFrontmatter } from './frontmatter.mjs';

/**
 * @param {{
 *   repoRoot: string,
 *   changesetsDir: string,
 *   baselineRef: string | null,
 *   allowedClassifications: Set<string>,
 *   classificationField?: string,
 *   requireJustificationFor?: Set<string>,
 *   fixtureMode?: boolean,
 * }} options
 * @returns {Array<{file: string, classification: string, body: string, frontmatter: Record<string, string>}>}
 */
export function loadChangesets(options) {
  const {
    repoRoot,
    changesetsDir,
    baselineRef,
    allowedClassifications,
    classificationField = 'classification',
    requireJustificationFor = new Set(),
    requireAccountabilityFor = new Set(),
    fixtureMode = false,
    validate = true,
  } = options;

  const fullDir = resolve(repoRoot, changesetsDir);
  if (!existsSync(fullDir)) return [];

  let candidateFiles;
  if (fixtureMode || !baselineRef) {
    candidateFiles = readdirSync(fullDir)
      .filter(f => f.endsWith('.md'))
      .map(f => resolve(fullDir, f));
  } else {
    // PR-scope discovery (slice 3a-1-8f §A.18): committed adds/mods vs baseline.
    const diffPaths = diffAddedModifiedFiles(baselineRef, changesetsDir, repoRoot);
    // Also include locally added (untracked + staged-but-uncommitted) .md files
    // under the changesets dir. Without this, a developer authoring a changeset
    // locally would see the gate still fail even after writing the file —
    // because the PR-scope diff only sees committed state. This matches the
    // spirit of PR-scope semantics (the changeset is being authored AS PART OF
    // the PR) while remaining strictly more permissive than the committed-only
    // path. CI runs after the commit lands, so the strict diff path catches.
    const localPaths = locallyAddedFiles(changesetsDir, repoRoot);
    const allPaths = [...new Set([...diffPaths, ...localPaths])];
    candidateFiles = allPaths.filter(p => p.endsWith('.md')).map(p => resolve(repoRoot, p));
  }

  const declarations = [];
  for (const file of candidateFiles) {
    if (!existsSync(file) || statSync(file).isDirectory()) continue;
    if (file.endsWith('README.md')) continue; // documentation, not a declaration

    const content = readFileSync(file, 'utf8');
    const parsed = parseFrontmatter(content);
    if (!parsed) continue;
    const classification = parsed.frontmatter[classificationField];
    if (!classification) continue;
    // `validate:false` (cross-gate enumeration) loads every classified changeset without imposing
    // a single gate's classification vocabulary — used by enumerateAllGateChangesets / the meta-ratchet.
    if (validate && !allowedClassifications.has(classification)) {
      throw new Error(
        `Invalid ${classificationField} '${classification}' in ${file}. ` +
          `Allowed: ${[...allowedClassifications].sort().join(', ')}`,
      );
    }
    if (validate && requireJustificationFor.has(classification)) {
      const tempdoc = parsed.frontmatter.tempdoc;
      const adr = parsed.frontmatter.adr;
      const hasJustification =
        (typeof tempdoc === 'string' && tempdoc.trim().length > 0) ||
        (typeof adr === 'string' && adr.trim().length > 0);
      if (!hasJustification) {
        throw new Error(
          `Changeset ${file} declares classification '${classification}' which requires a ` +
            `'tempdoc:' or 'adr:' frontmatter field with a non-empty value. ` +
            `See gates/<gate-id>/.changesets/README.md.`,
        );
      }
    }
    // Accountability (tempdoc 576 §4.2): a bounded-exception growth changeset must say WHO and WHY,
    // so a still-valid-but-stale exception is auditable.
    if (validate && requireAccountabilityFor.has(classification)) {
      const owner = parsed.frontmatter.owner;
      const reason = parsed.frontmatter.reason;
      const hasOwner = typeof owner === 'string' && owner.trim().length > 0;
      const hasReason = typeof reason === 'string' && reason.trim().length > 0;
      if (!hasOwner || !hasReason) {
        throw new Error(
          `Changeset ${file} declares '${classification}' which requires non-empty 'owner:' and ` +
            `'reason:' frontmatter fields (tempdoc 576 §4.2 — bounded exceptions must be accountable).`,
        );
      }
    }
    declarations.push({
      file,
      classification,
      frontmatter: parsed.frontmatter,
      body: parsed.body.trim(),
    });
  }
  return declarations;
}

/**
 * Enumerate live changesets across ALL registry gates (tempdoc 576 §4.4 — the exception ledger as a
 * ratcheted metric). Loads each gate's changesets WITHOUT imposing that gate's classification
 * vocabulary (`validate:false`), so a single pass can count/inspect every declared exception in the
 * tree. Each result carries its originating `gateId`.
 *
 * @param {{ repoRoot: string, gates: Array<{id: string, changesetsDir?: string}>,
 *   baselineRef?: string | null, fixtureMode?: boolean }} options
 * @returns {Array<{gateId: string, file: string, classification: string, frontmatter: Record<string, string>, body: string}>}
 */
export function enumerateAllGateChangesets(options) {
  const { repoRoot, gates, baselineRef = null, fixtureMode = false } = options;
  const all = [];
  for (const gate of gates ?? []) {
    if (!gate?.changesetsDir) continue;
    const decls = loadChangesets({
      repoRoot,
      changesetsDir: gate.changesetsDir,
      baselineRef,
      allowedClassifications: new Set(),
      fixtureMode,
      validate: false,
    });
    for (const d of decls) all.push({ gateId: gate.id, ...d });
  }
  return all;
}

/**
 * Discover locally-added (untracked + staged-not-committed) files under a path.
 * Combines `git ls-files --others --exclude-standard` (untracked) with `git
 * diff --name-only --cached --diff-filter=A` (staged-but-uncommitted adds).
 *
 * Returns repo-relative paths.
 */
function locallyAddedFiles(pathFilter, cwd) {
  const out = new Set();
  for (const cmd of [
    ['ls-files', '--others', '--exclude-standard', '--', pathFilter],
    ['diff', '--name-only', '--cached', '--diff-filter=AM', '--', pathFilter],
  ]) {
    try {
      const stdout = execFileSync('git', cmd, {
        cwd,
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      for (const line of stdout.split(/\r?\n/)) {
        const trimmed = line.trim();
        if (trimmed) out.add(trimmed);
      }
    } catch {
      /* ignore; absent paths or no-git contexts return empty */
    }
  }
  return [...out];
}
