#!/usr/bin/env node
/**
 * Tempdoc 575 §17 Face A — single-authority mandate for the liveness-window constants.
 *
 * The window values are GENERATED from the register into the worker (Java) + FE (TS) constants
 * (gen-liveness-constants.mjs). This gate forbids a HAND-AUTHORED NUMERIC copy of any generated
 * constant name anywhere else — the value must flow from the generated source, never be re-typed
 * as a literal (which could silently drift). It is the constant-tier analogue of the 564
 * `check-wire-type-single-authority` mandate (which forbids hand copies of generated TYPES).
 *
 * What FAILS: `... HEARTBEAT_INTERVAL_MS = 30_000L` (a numeric literal/product RHS).
 * What PASSES: `... HEARTBEAT_INTERVAL_MS = LivenessWindows.HEARTBEAT_INTERVAL_MS` (alias — the
 * value flows from the generated class), and the generated files themselves.
 *
 * Invoked by: node scripts/ci/check-liveness-constants-single-authority.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = join(__dirname, '..', '..');

/**
 * The generated constant names — a hand-authored numeric copy of any of these is the violation.
 * The action-lifecycle window (575 §17 Face A) + the live-stream window (604 PART V/VI, the
 * sibling generator `gen-stream-liveness-constants.mjs`).
 */
const GENERATED_NAMES = [
  'HEARTBEAT_INTERVAL_MS',
  'DISPLAY_STALE_MS',
  'REAPER_STALE_MS',
  'STREAM_HEARTBEAT_INTERVAL_MS',
  'STREAM_HEARTBEAT_MS',
  'STREAM_WATCHDOG_STALE_MS',
];

/** The generated files (the legitimate single authority) — never flagged. */
const GENERATED_FILES = [
  'modules/worker-core/src/main/java/io/justsearch/indexerworker/liveness/LivenessWindows.java',
  'modules/ui-web/src/api/generated/liveness-constants.ts',
  'modules/ui/src/main/java/io/justsearch/ui/api/StreamLivenessWindows.java',
  'modules/ui-web/src/api/generated/stream-liveness-constants.ts',
].map((p) => p.split('/').join(sep));

/** Roots to scan: the worker Java modules + the head (ui) Java + the FE source. */
const SCAN_ROOTS = [
  { dir: join(REPO_ROOT, 'modules', 'worker-core', 'src', 'main', 'java'), ext: '.java' },
  { dir: join(REPO_ROOT, 'modules', 'worker-services', 'src', 'main', 'java'), ext: '.java' },
  { dir: join(REPO_ROOT, 'modules', 'indexer-worker', 'src', 'main', 'java'), ext: '.java' },
  { dir: join(REPO_ROOT, 'modules', 'ui', 'src', 'main', 'java'), ext: '.java' },
  { dir: join(REPO_ROOT, 'modules', 'ui-web', 'src'), ext: '.ts' },
];

// `<NAME> = <numeric>` where the RHS begins with a digit (literal or product, e.g. `30_000L`,
// `5 * 60_000L`). An alias RHS (`= LivenessWindows.X`, `= DISPLAY_STALE_MS`) starts with a letter
// and is NOT matched.
const FORBIDDEN = new RegExp(`\\b(${GENERATED_NAMES.join('|')})\\b\\s*=\\s*\\d`);

function walk(dir, ext, out) {
  let entries;
  try {
    entries = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of entries) {
    const p = join(dir, name);
    let st;
    try {
      st = statSync(p);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      if (name === 'build' || name === 'node_modules' || name === 'generated') continue;
      walk(p, ext, out);
    } else if (name.endsWith(ext)) {
      out.push(p);
    }
  }
}

const violations = [];
for (const { dir, ext } of SCAN_ROOTS) {
  const files = [];
  walk(dir, ext, files);
  for (const file of files) {
    const rel = relative(REPO_ROOT, file);
    if (GENERATED_FILES.includes(rel)) continue;
    const text = readFileSync(file, 'utf8');
    text.split(/\r?\n/).forEach((line, i) => {
      if (FORBIDDEN.test(line)) {
        violations.push(`${rel.split(sep).join('/')}:${i + 1}: ${line.trim()}`);
      }
    });
  }
}

if (violations.length > 0) {
  console.error(
    'liveness-constants single-authority gate (575 §17 Face A): FAILED — hand-authored numeric copy ' +
      'of a generated liveness constant:',
  );
  for (const v of violations) console.error('  - ' + v);
  console.error(
    'The window is generated from governance/observed-happening.v1.json. Import the generated value ' +
      '(LivenessWindows.* / liveness-constants.ts) instead of re-typing the number.',
  );
  process.exit(1);
}

console.log(
  'liveness-constants single-authority gate (575 §17 Face A): OK — no hand-authored numeric copy of ' +
    'a generated liveness constant.',
);
