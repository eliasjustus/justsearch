/**
 * Tests for the tempdoc size cap (tempdoc 930 §18.1 row 8 / §19.3 F4).
 *
 * Each case builds a throwaway git repo on disk rather than mocking git, because the thing under
 * test IS "what changed in the diff" — same reasoning as `scripts/governance/lib/git-utils.test.mjs`.
 *
 * Run: `node scripts/ci/check-tempdoc-size.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  CAP_LINES,
  resolveBase,
  touchedTempdocNumbers,
  allTempdocNumbers,
  contributingFiles,
  tempdocSize,
  countLines,
} from './check-tempdoc-size.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI = resolve(HERE, 'check-tempdoc-size.mjs');

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

const git = (cwd, ...argv) =>
  execFileSync('git', argv, { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();

/** Create a temp repo with no remote, `main` as the initial branch. */
function makeRepo() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'jstds-'));
  git(dir, 'init', '--initial-branch=main');
  git(dir, 'config', 'user.email', 'test@example.invalid');
  git(dir, 'config', 'user.name', 'check-tempdoc-size test');
  git(dir, 'config', 'commit.gpgsign', 'false');
  return dir;
}

function writeFile(dir, relPath, body) {
  const full = path.join(dir, relPath);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, body, 'utf8');
}

function commitAll(dir, message) {
  git(dir, 'add', '-A');
  git(dir, 'commit', '-m', message);
}

/** `n` lines of filler content, each numbered so a wrong count is easy to spot in a diff. */
function lines(n, label = 'line') {
  return Array.from({ length: n }, (_, i) => `${label} ${i + 1}`).join('\n') + '\n';
}

const created = [];
const repo = () => {
  const d = makeRepo();
  created.push(d);
  return d;
};

/** Run the CLI against a fixture repo; returns {status, stdout, stderr} without throwing. */
function runCli(repoDir, args) {
  try {
    const stdout = execFileSync('node', [CLI, '--repo-root', repoDir, ...args], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { status: 0, stdout, stderr: '' };
  } catch (e) {
    return { status: e.status, stdout: e.stdout?.toString() ?? '', stderr: e.stderr?.toString() ?? '' };
  }
}

// -------------------------------------------------------------------------------------------
// The cap constant itself.
// -------------------------------------------------------------------------------------------
ok('CAP_LINES is the agreed 800-line cap', CAP_LINES === 800);

// -------------------------------------------------------------------------------------------
// resolveBase
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'a.txt', 'base\n');
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');

  ok('an explicit base wins outright', resolveBase(dir, 'HEAD') === 'HEAD');

  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  ok('origin/main is used when it exists and no explicit base is given', resolveBase(dir, null) === 'origin/main');

  // A repo with no origin/main and this being the only commit: HEAD~1 is unreachable, but
  // resolveBase itself does not validate reachability — it names the fallback string, and the
  // caller's git diff invocation degrades to an empty touched-set (see below).
  const dir2 = repo();
  writeFile(dir2, 'a.txt', 'only\n');
  commitAll(dir2, 'only commit');
  ok('falls back to HEAD~1 when origin/main does not exist', resolveBase(dir2, null) === 'HEAD~1');
}

// -------------------------------------------------------------------------------------------
// touchedTempdocNumbers
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/100-existing.md', lines(5));
  writeFile(dir, 'docs/tempdocs/200-untouched.md', lines(5));
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);

  writeFile(dir, 'docs/tempdocs/100-existing.md', lines(6));
  writeFile(dir, 'docs/tempdocs/300-new.md', lines(3));
  commitAll(dir, 'touch 100, add 300');

  const touched = touchedTempdocNumbers(dir, resolveBase(dir, null));
  ok('a modified tempdoc is touched', touched.has('100'));
  ok('a newly added tempdoc is touched', touched.has('300'));
  ok('an untouched tempdoc is NOT touched', !touched.has('200'));

  // A sidecar-only edit still touches the owning number.
  writeFile(dir, 'docs/tempdocs/200-untouched/notes.md', lines(2));
  commitAll(dir, 'add a sidecar under 200');
  const touched2 = touchedTempdocNumbers(dir, baseSha);
  ok('editing only a sidecar file touches its owning number', touched2.has('200'));
}

// -------------------------------------------------------------------------------------------
// allTempdocNumbers
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/1-a.md', lines(1));
  writeFile(dir, 'docs/tempdocs/2-b/notes.md', lines(1));
  writeFile(dir, 'docs/tempdocs/2-evidence/log.md', lines(1));
  commitAll(dir, 'seed');
  const nums = allTempdocNumbers(dir);
  ok('a top-level file number is found', nums.has('1'));
  ok('a top-level directory number is found (and only counted once)', nums.has('2') && nums.size === 2);
}

// -------------------------------------------------------------------------------------------
// contributingFiles / tempdocSize — under cap
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/400-small.md', lines(40));
  commitAll(dir, 'seed');
  const size = tempdocSize(dir, '400');
  ok('under-cap tempdoc: total line count matches the single main file', size.total === 40);
  ok('under-cap tempdoc: is within the cap', size.total <= CAP_LINES);
  ok('countLines matches wc -l semantics for a trailing-newline file', countLines(path.join(dir, 'docs/tempdocs/400-small.md')) === 40);
}

// -------------------------------------------------------------------------------------------
// tempdocSize — over cap
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/500-big.md', lines(CAP_LINES + 50));
  commitAll(dir, 'seed');
  const size = tempdocSize(dir, '500');
  ok('over-cap tempdoc: exceeds the cap', size.total > CAP_LINES);
  ok('over-cap tempdoc: attributes the size to its own main file', size.files.length === 1 && size.files[0].path.includes('500-big.md'));
}

// -------------------------------------------------------------------------------------------
// tempdocSize — over cap BUT the evidence sidecar is exempt
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/600-main.md', lines(50));
  writeFile(dir, 'docs/tempdocs/600-evidence/dump.md', lines(CAP_LINES + 200));
  commitAll(dir, 'seed');
  const size = tempdocSize(dir, '600');
  ok('evidence sidecar is excluded from the total', size.total === 50);
  ok('evidence sidecar is within cap once excluded', size.total <= CAP_LINES);
  ok('contributingFiles does not include anything under the -evidence/ directory', !contributingFiles(dir, '600').some((f) => f.includes('600-evidence')));
}

// -------------------------------------------------------------------------------------------
// tempdocSize — split across (non-evidence) sidecars still counts toward the cap
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/700-main.md', lines(50));
  writeFile(dir, 'docs/tempdocs/700-part-a/section.md', lines(400));
  writeFile(dir, 'docs/tempdocs/700-part-b/nested/section.md', lines(400));
  commitAll(dir, 'seed');
  const size = tempdocSize(dir, '700');
  ok('split-across-sidecars: sums main + every non-evidence sidecar', size.total === 50 + 400 + 400);
  ok('split-across-sidecars: exceeds the cap even though no single file does', size.total > CAP_LINES && 400 < CAP_LINES);
}

// -------------------------------------------------------------------------------------------
// End-to-end CLI: under-cap passes
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/800-ok.md', lines(10));
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  writeFile(dir, 'docs/tempdocs/800-ok.md', lines(12));
  commitAll(dir, 'small edit');

  const res = runCli(dir, []);
  ok('under-cap CLI run exits 0', res.status === 0);
  ok('under-cap CLI run reports pass', /pass/.test(res.stdout));
}

// -------------------------------------------------------------------------------------------
// End-to-end CLI: over-cap fails with the three-remedy message, never says "summarise"
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/900-seed.md', lines(1));
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  writeFile(dir, 'docs/tempdocs/900-seed.md', lines(CAP_LINES + 10));
  commitAll(dir, 'grow past the cap');

  const res = runCli(dir, []);
  ok('over-cap CLI run exits 1', res.status === 1);
  const out = res.stdout + res.stderr;
  ok('failure message names remedy 1 (canonical doc / ADR)', /canonical doc/.test(out) && /ADR/.test(out));
  ok('failure message names remedy 2 (evidence sidecar)', /900-evidence\//.test(out));
  ok('failure message names remedy 3 (dated digest)', /dated one-paragraph digest/.test(out));
  ok('failure message never says "summarise"', !/summarise/i.test(out));
}

// -------------------------------------------------------------------------------------------
// End-to-end CLI: over-cap but entirely inside the evidence sidecar passes
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/910-seed.md', lines(1));
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  writeFile(dir, 'docs/tempdocs/910-seed.md', lines(50));
  writeFile(dir, 'docs/tempdocs/910-evidence/dump.md', lines(CAP_LINES + 500));
  commitAll(dir, 'grow evidence sidecar past the cap');

  const res = runCli(dir, []);
  ok('over-cap-but-evidence-exempt CLI run exits 0', res.status === 0);
}

// -------------------------------------------------------------------------------------------
// End-to-end CLI: --all never fails even when a number is over cap, and is informational
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/920-seed.md', lines(CAP_LINES + 10));
  commitAll(dir, 'seed already-over-cap tempdoc');

  const res = runCli(dir, ['--all']);
  ok('--all always exits 0', res.status === 0);
  ok('--all reports the over-cap number', /OVER #? *920/.test(res.stdout.replace(/\s+/g, ' ')));
}

// -------------------------------------------------------------------------------------------
// --cap override
// -------------------------------------------------------------------------------------------
{
  const dir = repo();
  writeFile(dir, 'docs/tempdocs/930-seed.md', lines(5));
  commitAll(dir, 'base');
  const baseSha = git(dir, 'rev-parse', 'HEAD');
  git(dir, 'update-ref', 'refs/remotes/origin/main', baseSha);
  writeFile(dir, 'docs/tempdocs/930-seed.md', lines(6));
  commitAll(dir, 'small edit');

  const res = runCli(dir, ['--cap', '5']);
  ok('a lowered --cap fails a tempdoc that passes the default cap', res.status === 1);
}

for (const dir of created) {
  try {
    fs.rmSync(dir, { recursive: true, force: true });
  } catch {
    /* Windows can hold a git handle briefly; a leftover temp dir is not a failure */
  }
}

if (failures.length > 0) {
  console.error(`check-tempdoc-size.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`check-tempdoc-size.test: all ${passed} checks passed`);
