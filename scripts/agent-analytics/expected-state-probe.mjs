#!/usr/bin/env node
/**
 * expected-state-probe — the exit lane for `expected-state.v1.json` pins (tempdoc 872;
 * ported from the retired observations-triage `--probe`).
 *
 * A pin is a DATED EXCEPTION, not a steady state: it says "this verification command
 * is red on main for a known reason that is not yours". Every pin must carry an exit —
 * an `exitProbe` (exit 0 ⇒ the pinned state is gone) and/or a `reviewBy` date — so the
 * hint that delivers it (hooks/known-state-hint.mjs) can never quietly outlive the red
 * it describes. This script is the check:
 *
 *   node scripts/agent-analytics/expected-state-probe.mjs            # schema + reviewBy; fast probes
 *   node scripts/agent-analytics/expected-state-probe.mjs --slow     # also run `slow:` probes
 *   node scripts/agent-analytics/expected-state-probe.mjs --gate     # non-zero on a stale pin
 *
 * `--gate` fails on: a pin with neither exit; a pin past `reviewBy`; a pin whose
 * exitProbe fired (the red is gone — delete the pin, it is now a lie). Deletion is a
 * human act; this only reports.
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
export const EXPECTED_STATE_FILE = 'scripts/agent-analytics/expected-state.v1.json';

/** Pure structural check. `today` is YYYY-MM-DD. Returns [{id, problem}]. */
export function checkPinShape(entries, today) {
  const problems = [];
  for (const e of entries ?? []) {
    if (!e.id) { problems.push({ id: '(no id)', problem: 'pin has no id' }); continue; }
    if (!Array.isArray(e.match) || e.match.length === 0) problems.push({ id: e.id, problem: 'no `match` patterns' });
    if (!e.claim) problems.push({ id: e.id, problem: 'no `claim`' });
    if (!e.exitProbe && !e.reviewBy) problems.push({ id: e.id, problem: 'no exit — needs `exitProbe` and/or `reviewBy`' });
    if (e.reviewBy && today && e.reviewBy < today) problems.push({ id: e.id, problem: `past reviewBy ${e.reviewBy} — re-affirm (bump reviewBy with evidence) or delete` });
  }
  return problems;
}

/** Run exitProbes; exit 0 means the pinned state is GONE. */
export function runExitProbes(entries, { root = ROOT, slow = false, timeoutMs = 180_000 } = {}) {
  const out = { checked: 0, skippedSlow: 0, exitFired: [], errors: [] };
  for (const e of entries ?? []) {
    if (!e.exitProbe) continue;
    const isSlow = /^slow:\s*/.test(e.exitProbe);
    if (isSlow && !slow) { out.skippedSlow += 1; continue; }
    const res = spawnSync(e.exitProbe.replace(/^slow:\s*/, ''), { shell: true, cwd: root, timeout: timeoutMs, stdio: 'ignore' });
    out.checked += 1;
    if (res.error) out.errors.push({ id: e.id, error: res.error.message });
    else if (res.status === 0) out.exitFired.push(e.id);
  }
  return out;
}

function main() {
  const args = process.argv.slice(2);
  const data = JSON.parse(fs.readFileSync(path.join(ROOT, EXPECTED_STATE_FILE), 'utf8'));
  const today = new Date().toISOString().slice(0, 10);
  const shape = checkPinShape(data.entries, today);
  const probes = runExitProbes(data.entries, { slow: args.includes('--slow') });
  console.log(`expected-state-probe: ${data.entries.length} pin(s); ${shape.length} shape/review problem(s); ` +
    `${probes.checked} exit-probe(s) run, ${probes.exitFired.length} fired` +
    `${probes.skippedSlow ? `, ${probes.skippedSlow} slow skipped (pass --slow)` : ''}`);
  for (const p of shape) console.log(`  STALE  [${p.id}] ${p.problem}`);
  for (const id of probes.exitFired) console.log(`  GONE   [${id}] exitProbe passed — the pinned red no longer exists; delete the pin`);
  for (const e of probes.errors) console.log(`  ERROR  [${e.id}] ${e.error}`);
  if (args.includes('--gate') && (shape.length || probes.exitFired.length)) process.exit(1);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
