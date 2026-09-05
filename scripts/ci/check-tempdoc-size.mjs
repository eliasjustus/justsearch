#!/usr/bin/env node
/**
 * Tempdoc size cap — tempdoc 930 §18.1 row 8 / §19.3 F4.
 *
 * `docs/tempdocs/` is append-only design history (CLAUDE.md `tempdocs-are-dated-history`): nothing
 * ever shrinks it back down on its own, so an individual tempdoc grows without bound as evidence,
 * findings, and closed-out sections accumulate — the exact failure this check is named for (930
 * itself, before this change, was well past its own cap). An unbounded tempdoc taxes every agent
 * who opens it, the same "always-loaded budget" argument `check-always-loaded-budget.mjs` makes for
 * CLAUDE.md/AGENTS.md, applied to working history instead of always-loaded rules.
 *
 * Measurement, PER TEMPDOC NUMBER (not per file — a split-out sidecar doesn't dodge the cap):
 *
 *     docs/tempdocs/NNN-*.md            the main file (at most one)
 *   + docs/tempdocs/NNN-<name>/ (recursively, every .md file)   every OTHER sidecar directory
 *   - docs/tempdocs/NNN-evidence/ (recursively)   EXEMPT — the one evidence sidecar per number
 *                                                 (bulk logs, transcripts, tables go here)
 *
 * No baseline file, no changeset: a hardcoded constant cap (`CAP_LINES`, overridable via `--cap`
 * for local experimentation — CI always uses the default). Only tempdocs TOUCHED in the diff
 * against a base ref are checked, so a pre-existing over-cap tempdoc nobody touched doesn't fail an
 * unrelated PR — the same touched-scope discipline `check-tempdoc-numbers.mjs` and the PR-scope
 * diff helpers in `scripts/governance/lib/git-utils.mjs` use elsewhere in this repo.
 *
 *   node scripts/ci/check-tempdoc-size.mjs                 # touched-only, default base, fails over cap
 *   node scripts/ci/check-tempdoc-size.mjs --base <ref>     # explicit base (CI passes the PR base)
 *   node scripts/ci/check-tempdoc-size.mjs --cap <n>        # override the cap (local experimentation)
 *   node scripts/ci/check-tempdoc-size.mjs --all            # repo-wide report; informational, never fails
 *   node scripts/ci/check-tempdoc-size.mjs --repo-root <p>  # test-support only; CI never passes this
 */

import { readFileSync, readdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { execFileSync } from 'node:child_process';

/** The one-directional constant this check enforces. Not a ratchet — a fixed cap. */
export const CAP_LINES = 800;

const TOPLEVEL_RE = /^(\d+)-/;

/** Value following a flag (`--base <v>`), or null if the flag is absent or dangling. */
function argValue(argv, flag) {
  const i = argv.indexOf(flag);
  const v = i >= 0 ? argv[i + 1] : null;
  return v && !v.startsWith('--') ? v : null;
}

function git(repoRoot, args) {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
}

/** @returns {boolean} whether `ref` resolves to a commit in `repoRoot`. */
export function gitRefExists(repoRoot, ref) {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], {
      cwd: repoRoot,
      stdio: ['ignore', 'ignore', 'ignore'],
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Resolve the diff base: an explicit ref wins outright; else `origin/main` if it exists; else the
 * genuine last resort `HEAD~1`. Deliberately simpler than `git-utils.mjs`'s `resolveGitBase` ladder
 * (merge-base, `GITHUB_BASE_REF`, multi-rung fallback) — this check only needs "what changed since
 * the base", not the discipline-kernel's baseline-strategy registry, and CI passes the PR base
 * explicitly when one is available (see `.github/workflows/ci.yml`).
 *
 * @param {string} repoRoot
 * @param {string|null} explicit
 * @returns {string}
 */
export function resolveBase(repoRoot, explicit) {
  if (explicit) return explicit;
  if (gitRefExists(repoRoot, 'origin/main')) return 'origin/main';
  return 'HEAD~1';
}

/**
 * Every tempdoc NUMBER touched (added/modified/renamed/deleted) under `docs/tempdocs/` between
 * `baseRef` and `HEAD`. A number is touched if ANY path under its main file or any of its sidecar
 * directories changed — including a file moved INTO or OUT OF the exempt `NNN-evidence/` sidecar,
 * since that move is exactly the remedy this check exists to encourage.
 *
 * @param {string} repoRoot
 * @param {string} baseRef
 * @returns {Set<string>}
 */
export function touchedTempdocNumbers(repoRoot, baseRef) {
  let out;
  try {
    out = git(repoRoot, ['diff', '--name-only', `${baseRef}...HEAD`, '--', 'docs/tempdocs']);
  } catch {
    return new Set();
  }
  const numbers = new Set();
  for (const line of out.split(/\r?\n/)) {
    const p = line.trim();
    if (!p.startsWith('docs/tempdocs/')) continue;
    const seg = p.slice('docs/tempdocs/'.length).split('/')[0];
    const m = TOPLEVEL_RE.exec(seg);
    if (m) numbers.add(m[1]);
  }
  return numbers;
}

/**
 * Every tempdoc number that exists on disk right now (a top-level `NNN-*.md` file or a
 * `NNN-<name>` directory under `docs/tempdocs/`). Used only by `--all` report mode.
 *
 * @param {string} repoRoot
 * @returns {Set<string>}
 */
export function allTempdocNumbers(repoRoot) {
  const dir = resolve(repoRoot, 'docs', 'tempdocs');
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return new Set();
  }
  const numbers = new Set();
  for (const e of entries) {
    const m = TOPLEVEL_RE.exec(e.name);
    if (m) numbers.add(m[1]);
  }
  return numbers;
}

/** Recursively collect every `*.md` file under `dir`. */
function walkMarkdown(dir) {
  const out = [];
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const full = join(dir, e.name);
    if (e.isDirectory()) out.push(...walkMarkdown(full));
    else if (e.isFile() && e.name.endsWith('.md')) out.push(full);
  }
  return out;
}

/**
 * Every file counted toward tempdoc NUMBER's size: its main file plus every OTHER sidecar
 * directory's markdown (recursively), excluding the one exempt `NNN-evidence/` sidecar.
 *
 * @param {string} repoRoot
 * @param {string} number
 * @returns {string[]} absolute paths
 */
export function contributingFiles(repoRoot, number) {
  const tempdocsDir = resolve(repoRoot, 'docs', 'tempdocs');
  let entries;
  try {
    entries = readdirSync(tempdocsDir, { withFileTypes: true });
  } catch {
    return [];
  }
  const mainRe = new RegExp(`^${number}-.*\\.md$`);
  const sidecarRe = new RegExp(`^${number}-`);
  const evidenceDirName = `${number}-evidence`;
  const files = [];
  for (const e of entries) {
    if (e.isFile() && mainRe.test(e.name)) {
      files.push(join(tempdocsDir, e.name));
    } else if (e.isDirectory() && sidecarRe.test(e.name) && e.name !== evidenceDirName) {
      files.push(...walkMarkdown(join(tempdocsDir, e.name)));
    }
  }
  return files;
}

/** Line count of a file's current on-disk content (a trailing newline doesn't count a phantom line). */
export function countLines(file) {
  const text = readFileSync(file, 'utf8');
  if (text === '') return 0;
  const parts = text.split(/\r?\n/);
  if (parts[parts.length - 1] === '') parts.pop();
  return parts.length;
}

/**
 * Total line count for tempdoc NUMBER, per the measurement rule above.
 *
 * @param {string} repoRoot
 * @param {string} number
 * @returns {{total: number, files: Array<{path: string, lines: number}>}}
 */
export function tempdocSize(repoRoot, number) {
  const files = contributingFiles(repoRoot, number).map((path) => ({ path, lines: countLines(path) }));
  const total = files.reduce((sum, f) => sum + f.lines, 0);
  return { total, files };
}

/**
 * The three-remedy failure message body (deliberately never says "summarise" — see 930 §19.3
 * F4). Exported so the write-time advisory hint (`scripts/agent-analytics/hooks/intervene.mjs`)
 * renders the identical wording instead of forking a second copy of it.
 */
export function remedyMessage(repoRoot, number, size, cap) {
  const rel = (p) => p.replace(resolve(repoRoot) + '\\', '').replace(resolve(repoRoot) + '/', '').replace(/\\/g, '/');
  const lines = size.files
    .slice()
    .sort((a, b) => b.lines - a.lines)
    .map((f) => `      ${String(f.lines).padStart(5)}  ${rel(f.path)}`);
  return (
    `  #${number}: ${size.total} lines > cap ${cap}\n` +
    lines.join('\n') +
    '\n' +
    '    Remedies (pick whichever fits the content — not a rewording pass, a move):\n' +
    '      1. Settled truth moves to a canonical doc (docs/{explanation,reference,how-to,decisions}) or an ADR.\n' +
    `      2. Bulk evidence, tables, logs, transcripts move to docs/tempdocs/${number}-evidence/.\n` +
    '      3. Closed sections collapse to a dated one-paragraph digest.'
  );
}

function main() {
  const argv = process.argv.slice(2);
  const repoRootArg = argValue(argv, '--repo-root');
  const repoRoot = repoRootArg ? resolve(repoRootArg) : resolve(process.cwd());
  const cap = Number(argValue(argv, '--cap')) || CAP_LINES;
  const allMode = argv.includes('--all');

  if (allMode) {
    const numbers = [...allTempdocNumbers(repoRoot)].sort((a, b) => Number(a) - Number(b));
    let overCount = 0;
    for (const number of numbers) {
      const size = tempdocSize(repoRoot, number);
      const flag = size.total > cap ? 'OVER' : 'ok  ';
      if (size.total > cap) overCount++;
      console.log(`  ${flag} #${number.padStart(4)}  ${String(size.total).padStart(6)} / ${cap} lines`);
    }
    console.log(
      `\n[tempdoc-size] report — ${numbers.length} tempdoc number(s), ${overCount} over the ${cap}-line cap ` +
        '(informational; --all never fails).',
    );
    process.exit(0);
  }

  const explicitBase = argValue(argv, '--base');
  const baseRef = resolveBase(repoRoot, explicitBase);
  const touched = [...touchedTempdocNumbers(repoRoot, baseRef)].sort((a, b) => Number(a) - Number(b));

  if (touched.length === 0) {
    console.log(`[tempdoc-size] no docs/tempdocs/ changes between ${baseRef} and HEAD — nothing to check.`);
    process.exit(0);
  }

  const violations = [];
  for (const number of touched) {
    const size = tempdocSize(repoRoot, number);
    console.log(`  #${number.padStart(4)}  ${String(size.total).padStart(6)} / ${cap} lines`);
    if (size.total > cap) violations.push({ number, size });
  }

  if (violations.length > 0) {
    console.error(`\n[tempdoc-size] FAIL — ${violations.length} tempdoc(s) touched in this diff exceed the ${cap}-line cap:`);
    console.error(violations.map((v) => remedyMessage(repoRoot, v.number, v.size, cap)).join('\n\n'));
    console.error(
      `\nMeasured against base ${baseRef}: each number's main file plus every sidecar EXCEPT its own ` +
        '-evidence/ directory, which is exempt by design.',
    );
    process.exit(1);
  }

  console.log(`\n[tempdoc-size] pass — ${touched.length} touched tempdoc(s), all within the ${cap}-line cap (base ${baseRef}).`);
}

if (process.argv[1] && process.argv[1].replace(/\\/g, '/').endsWith('check-tempdoc-size.mjs')) {
  main();
}
