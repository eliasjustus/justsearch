#!/usr/bin/env node
/**
 * report-pit-strength — produces the `pit-strength-report.v1` artifact the test-efficacy gate reads
 * (tempdoc 555 Pillar C), mirroring scripts/ci/report-npm-audit.mjs (which runs the tool itself).
 *
 * For each seam in governance/logic-seams.v1.json it parses that seam's module PIT `mutations.xml`
 * (modules/<module>/build/reports/pitest/mutations.xml) and emits, per seam:
 *   - strength   = floor(killedEff / covered * 100), where killedEff = KILLED + TIMED_OUT and
 *                  covered = KILLED + TIMED_OUT + SURVIVED. TIMED_OUT counts as killed to match
 *                  PIT's own "test strength" definition (F4).
 *   - noCoverage = NO_COVERAGE count — ratcheted as a CEILING by the gate so a seam cannot rot by
 *                  adding untested branches without lowering killed/covered (F3).
 * NON_VIABLE / MEMORY_ERROR / RUN_ERROR are tooling artifacts and excluded from every count.
 *
 * With `--run`, this first executes the pitest task for each seam module (deriving the module set
 * from the register) so the report is always fresh + complete (F1) — the report-npm-audit pattern.
 *
 * Usage: node scripts/ci/report-pit-strength.mjs [--run] [--register <path>] [--out <path>]
 *   --register defaults to governance/logic-seams.v1.json; pass another logic-seam register to
 *   produce a strength report scoped to ITS seams (tempdoc 576 §3.2 — mutation-as-a-capability).
 */

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

function parseArgs(argv) {
  const args = { out: 'tmp/pit-strength-report.v1.json', run: false, register: 'governance/logic-seams.v1.json' };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--out' && argv[i + 1]) args.out = argv[(i += 1)];
    else if (argv[i] === '--register' && argv[i + 1]) args.register = argv[(i += 1)];
    else if (argv[i] === '--run') args.run = true;
  }
  return args;
}

/** Tally mutation statuses for a given mutatedClass in a PIT mutations.xml. */
function strengthFor(xml, targetClass) {
  let killed = 0;
  let survived = 0;
  let timedOut = 0;
  let noCoverage = 0;
  for (const block of xml.split(/<\/mutation>/)) {
    if (!block.includes('<mutation')) continue;
    const cls = (block.match(/<mutatedClass>([^<]+)<\/mutatedClass>/) || [])[1];
    if (cls !== targetClass) continue;
    if (/status='KILLED'/.test(block)) killed += 1;
    else if (/status='SURVIVED'/.test(block)) survived += 1;
    else if (/status='TIMED_OUT'/.test(block)) timedOut += 1; // PIT counts a timeout as killed
    else if (/status='NO_COVERAGE'/.test(block)) noCoverage += 1;
    // NON_VIABLE / MEMORY_ERROR / RUN_ERROR: tooling artifacts — ignored.
  }
  const killedEff = killed + timedOut;
  const covered = killedEff + survived;
  const strength = covered > 0 ? Math.floor((killedEff / covered) * 100) : 100;
  return { strength, killed: killedEff, covered, noCoverage };
}

function moduleNameFromGradlePath(gradlePath) {
  // ":modules:adapters-lucene" -> "adapters-lucene"
  return gradlePath.split(':').filter(Boolean).slice(-1)[0];
}

function gradlewCmd() {
  return process.platform === 'win32'
    ? resolve(REPO_ROOT, 'gradlew.bat')
    : resolve(REPO_ROOT, 'gradlew');
}

/** Run `:modules:<m>:pitest` for every distinct seam module so reports are fresh + complete. */
function runPitest(register) {
  const paths = [
    ...new Set((register.seams ?? []).map(s => `${s.gradlePath}:pitest`)),
  ];
  if (paths.length === 0) return;
  const args = [...paths, '--no-configuration-cache', '--console=plain'];
  console.log(`[report-pit-strength] running PIT: ${paths.join(' ')}`);
  // shell:true so a Windows .bat (gradlew.bat) is executed via the command interpreter.
  const child = spawnSync(gradlewCmd(), args, { cwd: REPO_ROOT, stdio: 'inherit', shell: true });
  if (child.status !== 0) {
    console.error('[report-pit-strength] pitest run failed — report may be stale/incomplete.');
    process.exit(child.status ?? 1);
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const register = JSON.parse(readFileSync(resolve(REPO_ROOT, args.register), 'utf8'));

  if (args.run) runPitest(register);

  const seams = {};
  const missing = [];
  for (const seam of register.seams ?? []) {
    const mod = moduleNameFromGradlePath(seam.gradlePath);
    const reportPath = resolve(REPO_ROOT, `modules/${mod}/build/reports/pitest/mutations.xml`);
    if (!existsSync(reportPath)) {
      missing.push(`${seam.id} (${reportPath})`);
      continue;
    }
    seams[seam.id] = strengthFor(readFileSync(reportPath, 'utf8'), seam.targetClass);
  }

  const out = { schema: 'pit-strength-report.v1', registerPath: args.register, seamCount: Object.keys(seams).length, seams };
  const outPath = resolve(REPO_ROOT, args.out);
  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, JSON.stringify(out, null, 2) + '\n');
  console.log(`Wrote ${args.out} (${Object.keys(seams).length} seam(s))`);
  for (const [id, s] of Object.entries(seams)) {
    console.log(`  ${id}: ${s.strength}% strength (${s.killed}/${s.covered} covered killed, ${s.noCoverage} no-coverage)`);
  }
  if (missing.length) {
    console.log(`  Seams with no PIT report (run their :pitest first, or use --run): ${missing.join(', ')}`);
  }
}

main();
