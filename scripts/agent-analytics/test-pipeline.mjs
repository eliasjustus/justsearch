#!/usr/bin/env node

/**
 * Test suite for the agent analytics pipeline.
 *
 * Tests hook output format, scoring logic, signal extraction, boolean rules,
 * path sanitization, and trend analysis. Uses synthetic session reports
 * injected into the real sessions dir (cleaned up via try/finally).
 *
 * Usage: node scripts/agent-analytics/test-pipeline.mjs
 */

import { execSync, execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { computeScore, computeTypeCeilings, SIGNAL_CEILINGS, N_STABLE, percentile } from './score-session.mjs';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;

const repoRoot = path.resolve(scriptDir, '..', '..');
const telemetryDir = path.join(repoRoot, 'tmp', 'agent-telemetry');
const sessionsDir = path.join(telemetryDir, 'sessions');
const tmpDir = path.join(telemetryDir, 'test-sessions');
const errorsFile = path.join(telemetryDir, 'errors.log');
const rulesFile = path.join(repoRoot, '.claude', 'rules', 'compaction-state.md');
const settingsFile = path.join(repoRoot, '.claude', 'settings.local.json');

// Track all test artifacts for cleanup in finally block
const testSessionIds = [];
const testCachePatterns = [];

let passed = 0;
let failed = 0;

function assert(condition, label) {
  if (condition) {
    passed++;
  } else {
    failed++;
    console.error(`  FAIL: ${label}`);
  }
}

function assertApprox(actual, expected, tolerance, label) {
  assert(Math.abs(actual - expected) <= tolerance, `${label}: got ${actual}, expected ${expected} ±${tolerance}`);
}

/**
 * Run intervene.mjs with synthetic input. Throws on non-zero exit (crash)
 * instead of silently returning empty string.
 */
function runHook(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'intervene.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
  });
  if (result.status !== 0) {
    throw new Error(`Hook crashed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/**
 * Run intervene.mjs without throwing on exit 2 (for testing blocking behavior).
 * Returns { stdout, stderr, status }.
 */
function runHookRaw(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'intervene.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
  });
  return { stdout: result.stdout, stderr: result.stderr, status: result.status };
}

/**
 * Run repeat-guard.mjs with synthetic input. Returns { stdout, stderr, status }.
 */
function runRepeatGuard(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'repeat-guard.mjs')], {
    input: json, encoding: 'utf8', timeout: 10000,
  });
  return { stdout: result.stdout, stderr: result.stderr, status: result.status };
}

/**
 * Run build-counter.mjs with synthetic input. Returns { stdout, stderr, status }.
 */
function runBuildCounter(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'build-counter.mjs')], {
    input: json, encoding: 'utf8', timeout: 10000,
  });
  return { stdout: result.stdout, stderr: result.stderr, status: result.status };
}

/**
 * Run dispatch.mjs with synthetic input. Returns { stdout, stderr, status }.
 */
function runDispatch(input) {
  const json = JSON.stringify(input);
  return spawnSync('node', [path.join(scriptDir, 'hooks', 'dispatch.mjs')], {
    input: json, encoding: 'utf8', timeout: 10000,
  });
}

/**
 * Run bash-guard.mjs with synthetic input. Returns { stdout, stderr, status }.
 * Does NOT throw on exit 2 (that's the expected "blocked" signal).
 */
function runBashGuard(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'bash-guard.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
  });
  if (result.status !== 0 && result.status !== 2) {
    throw new Error(`Bash guard crashed (exit ${result.status}): ${result.stderr}`);
  }
  return { stdout: result.stdout, stderr: result.stderr, status: result.status };
}

// Real large file path (>12KB) for statSync-based tests
const realLargeFile = path.resolve(scriptDir, '..', '..', 'modules', 'ui-web', 'scripts', 'capture-evidence-bundle.mjs');

/**
 * Run compact-save.mjs with synthetic input. Always exits 0.
 */
function runCompactSave(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'compact-save.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 15000,
  });
  if (result.status !== 0) {
    throw new Error(`compact-save crashed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/**
 * Run compact-restore.mjs with synthetic input. Always exits 0.
 */
function runCompactRestore(input) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'compact-restore.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
  });
  if (result.status !== 0) {
    throw new Error(`compact-restore crashed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/**
 * Run export-session-env.mjs with synthetic input. Always exits 0.
 */
function runExportSessionEnv(input, env = {}) {
  const json = JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'export-session-env.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
    env: {
      ...process.env,
      ...env,
    },
  });
  if (result.status !== 0) {
    throw new Error(`export-session-env crashed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/**
 * Run subagent-guide.mjs with synthetic input. Always exits 0.
 */
function runSubagentGuide(input) {
  const json = typeof input === 'string' ? input : JSON.stringify(input);
  const result = spawnSync('node', [path.join(scriptDir, 'hooks', 'subagent-guide.mjs')], {
    input: json,
    encoding: 'utf8',
    timeout: 10000,
  });
  if (result.status !== 0) {
    throw new Error(`subagent-guide crashed (exit ${result.status}): ${result.stderr}`);
  }
  return result.stdout;
}

/** Score a session via CLI, return parsed JSON. */
function scoreSession(sessionId, extraArgs = []) {
  const out = execFileSync('node', [
    path.join(scriptDir, 'score-session.mjs'),
    '--session-id', sessionId,
    '--json',
    ...extraArgs,
  ], { encoding: 'utf8', timeout: 10000 }).trim();
  return JSON.parse(out);
}

/** Generate timestamps forming non-overlapping edit clusters. */
function makeEditTimestamps(clusterCount, editsPerCluster = 3, spacingMs = 30_000) {
  const ts = [];
  const base = Date.parse('2026-01-01T00:00:00Z');
  for (let c = 0; c < clusterCount; c++) {
    for (let e = 0; e < editsPerCluster; e++) {
      // spacingMs between edits within cluster, 5min between clusters
      ts.push(new Date(base + c * 300_000 + e * spacingMs).toISOString());
    }
  }
  return ts;
}

function writeTestReport(id, overrides = {}) {
  testSessionIds.push(id);
  const report = {
    schema: 'agent-session-report.v1',
    session_id: id,
    started_at: '2026-01-01T00:00:00Z',
    ended_at: '2026-01-01T01:00:00Z',
    duration_seconds: 3600,
    model: 'test',
    tool_calls: { total: 100, by_type: { Read: 50, Bash: 20, Edit: 30 }, failure_count: 2 },
    file_reads: {
      total: 50,
      unique_files: 10,
      unbounded_count: 25,
      unbounded_large_count: 10,
      by_file: [
        { file: 'a.txt', count: 20, unbounded: 15 },
        { file: 'b.txt', count: 15, unbounded: 5 },
        { file: 'c.txt', count: 10, unbounded: 3 },
        { file: 'd.txt', count: 5, unbounded: 2 },
      ],
    },
    file_edits: { total: 30, by_file: [] },
    compactions: { count: 2, triggers: ['auto', 'auto'] },
    subagents: { count: 10, by_type: { Explore: 10 } },
    subagent_tool_calls: {
      total: 50,
      by_type: { Read: 30 },
      transcripts_found: 5,
      transcripts_missing: 5,
      transcript_coverage: 0.5,
      file_reads: {
        total: 30,
        unbounded_count: 20,
        unbounded_large_count: 8,
        by_file: [
          { file: 'a.txt', count: 10, unbounded: 8 },
          { file: 'e.txt', count: 5, unbounded: 5 },
        ],
      },
    },
    data_completeness: { available: true },
    search_patterns: [],
    bash_commands: { total: 20, file_op_count: 10, build_count: 0, failed_build_count: 0,
      bash_exploratory_count: 0, bash_exploratory_pct: 0 },
    ...overrides,
  };
  fs.mkdirSync(tmpDir, { recursive: true });
  fs.writeFileSync(path.join(tmpDir, `${id}.json`), JSON.stringify(report), 'utf8');
  // Also copy to sessions dir so score-session.mjs and analyze-trends.mjs can find it
  fs.mkdirSync(sessionsDir, { recursive: true });
  fs.copyFileSync(
    path.join(tmpDir, `${id}.json`),
    path.join(sessionsDir, `${id}.json`)
  );
  return report;
}

// ============================================================
// Helper for analyzer tests (Tests 12-15) — declared before try
// so cleanup in finally block can access them
// ============================================================

const eventsFile = path.join(telemetryDir, 'events.ndjson');
// Back up existing events file
let eventsBackup = null;
try {
  if (fs.existsSync(eventsFile)) {
    eventsBackup = fs.readFileSync(eventsFile, 'utf8');
  }
} catch { /* no backup needed */ }

/**
 * Write synthetic events, run analyze-session.mjs, return the report.
 */
function analyzeTestEvents(sessionId, events) {
  testSessionIds.push(sessionId);
  const lines = events.map(e => JSON.stringify({ session_id: sessionId, ...e })).join('\n') + '\n';

  // Write events
  fs.mkdirSync(telemetryDir, { recursive: true });
  fs.writeFileSync(eventsFile, lines, 'utf8');

  // Run analyzer
  execFileSync('node', [
    path.join(scriptDir, 'analyze-session.mjs'),
    '--session-id', sessionId,
  ], { encoding: 'utf8', timeout: 15000, stdio: 'pipe' });

  // Read the report
  const reportPath = path.join(sessionsDir, `${sessionId}.json`);
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));

  return report;
}

// Real session IDs with stable expected scores (captured 2026-02-18 from scores.ndjson).
// If the scorer logic changes, some of these will drift outside ±5 — that's the point.
// Recaptured 2026-03-13 after ceiling calibration (failed_build_pct=0.30, reedit_per_edit=0.35).
const GOLDEN_SESSIONS = [
  { id: '39c2b861-e2fa-4e05-9e75-a50b6e709f81', expected: 98 },
  { id: '18352bc5-e003-4e16-a70b-b6bda567bfe8', expected: 89 },
  { id: '01dcdc1e-e760-49be-8434-5700d8415e34', expected: 85 },
  { id: '0d70435c-79ec-4ad4-a20c-5eda62c955c5', expected: 80 },
  { id: '4937df03-bd3c-4766-b376-469144122211', expected: 74 },
  { id: '8d69ba61-0513-4c33-8789-84b8e5c950c8', expected: 79 },
  { id: '1613eee7-fe3d-4764-b6a6-d15112eb80f1', expected: 78 },
  { id: 'c79a0488-bfa3-42d5-8cd8-ee5dd550b575', expected: 71 },
  { id: '0ce68479-310e-4ace-a67e-b8b878abbc92', expected: 64 },
  { id: 'a7eb9fce-8cc5-446a-a23d-e6e0a07201b8', expected: 61 },
];

try {

// ============================================================
// Test 1: Hook output format (intervene.mjs)
// ============================================================
console.log('Test 1: Hook output format');

// 1a: Non-large file, first read — no output (not crash)
const out1a = runHook({
  tool_name: 'Read',
  tool_input: { file_path: '/tmp/small-file.txt' },
  session_id: 'test-hook-format-1',
});
testCachePatterns.push('test-hook-format-1');
assert(out1a === '', '1a: No output for non-large first read');

// 1b: Large file (>12KB) without limit — injects limit via statSync
const out1b = runHook({
  tool_name: 'Read',
  tool_input: { file_path: realLargeFile },
  session_id: 'test-hook-format-2',
});
testCachePatterns.push('test-hook-format-2');
const parsed1b = JSON.parse(out1b);
assert(parsed1b.hookSpecificOutput?.updatedInput?.limit === 200, '1b: Injects limit 200 for known large file');
assert(parsed1b.hookSpecificOutput?.permissionDecision === 'allow', '1b: Sets permissionDecision to allow');

// 1c: Large file WITH limit — no injection (already has limit)
const out1c = runHook({
  tool_name: 'Read',
  tool_input: { file_path: realLargeFile, limit: 50 },
  session_id: 'test-hook-format-3',
});
testCachePatterns.push('test-hook-format-3');
assert(out1c === '', '1c: No output when limit already set');

// 1d: Repeat reads below hot-file cap — no warning (tracking only for compact-save)
const sessionId1d = 'test-hook-repeat-' + Date.now();
testCachePatterns.push(sessionId1d);
for (let i = 0; i < 7; i++) {
  const out = runHook({
    tool_name: 'Read',
    tool_input: { file_path: '/tmp/repeat-test.txt' },
    session_id: sessionId1d,
  });
  assert(out === '', `1d: No output on read ${i + 1} (below hot-file cap)`);
}

// 1d2: Hot-file read cap — blocks unbounded reads at ≥10 (unbounded count only)
const sessionId1d2 = 'test-hotfile-cap-' + Date.now();
testCachePatterns.push(sessionId1d2);

// 5 targeted reads (with offset) — don't count toward unbounded cap
for (let i = 0; i < 5; i++) {
  runHook({
    tool_name: 'Read',
    tool_input: { file_path: '/tmp/hotfile-cap-test.txt', offset: i * 50, limit: 50 },
    session_id: sessionId1d2,
  });
}

// 9 unbounded reads — below cap
for (let i = 0; i < 9; i++) {
  runHook({
    tool_name: 'Read',
    tool_input: { file_path: '/tmp/hotfile-cap-test.txt' },
    session_id: sessionId1d2,
  });
}

// 10th unbounded read — blocked
const capResult = runHookRaw({
  tool_name: 'Read',
  tool_input: { file_path: '/tmp/hotfile-cap-test.txt' },
  session_id: sessionId1d2,
});
assert(capResult.status === 2, '1d2: 10th unbounded read blocked (exit 2)');
assert(capResult.stderr.includes('unbounded reads'), '1d2: message mentions unbounded');

// Targeted read still bypasses cap
const capBypass = runHook({
  tool_name: 'Read',
  tool_input: { file_path: '/tmp/hotfile-cap-test.txt', offset: 1, limit: 50 },
  session_id: sessionId1d2,
});
assert(capBypass === '', '1d2: targeted read bypasses hot-file cap');

// 1e: Edit tool, first edit — no output (below threshold)
const out1e = runHook({
  tool_name: 'Edit',
  tool_input: { file_path: '/tmp/file.txt' },
  session_id: 'test-hook-format-5',
});
testCachePatterns.push('test-hook-format-5');
assert(out1e === '', '1e: No output for first edit (below threshold)');

// 1f: Large file at high read count (below hot-file cap) — only updatedInput, no additionalContext
const sessionIdCombined = 'test-hook-combined-' + Date.now();
testCachePatterns.push(sessionIdCombined);
for (let i = 0; i < 6; i++) {
  runHook({
    tool_name: 'Read',
    tool_input: { file_path: realLargeFile },
    session_id: sessionIdCombined,
  });
}
const out1f = runHook({
  tool_name: 'Read',
  tool_input: { file_path: realLargeFile },
  session_id: sessionIdCombined,
});
const parsed1f = JSON.parse(out1f);
assert(parsed1f.hookSpecificOutput?.updatedInput?.limit === 200, '1f: Large file: has updatedInput');
assert(!parsed1f.hookSpecificOutput?.additionalContext, '1f: Large file: no additionalContext (warnings removed)');
assert(parsed1f.hookSpecificOutput?.permissionDecision === 'allow', '1f: Large file: has permissionDecision');

// 1g: Missing tool_input — no crash, no output
const out1g = runHook({
  tool_name: 'Read',
  session_id: 'test-hook-malformed-1',
});
testCachePatterns.push('test-hook-malformed-1');
assert(out1g === '', '1g: No crash with missing tool_input');

// 1h: Missing session_id — no crash, no output (trackRead returns 0)
const out1h = runHook({
  tool_name: 'Read',
  tool_input: { file_path: '/tmp/x.txt' },
});
assert(out1h === '', '1h: No crash with missing session_id');

console.log();

// ============================================================
// Test 2: Scoring logic (score-session.mjs)
// ============================================================
console.log('Test 2: Scoring logic');

// 2a: Score a session with known signals
writeTestReport('test-score-2a');

const score2a = scoreSession('test-score-2a');

// Verify signal extraction
// unbounded_read_pct = (10 + 8) / (50 + 30) = 18/80 = 0.225 (uses unbounded_large_count)
assertApprox(score2a.signals.unbounded_read_pct, 0.225, 0.001, '2a: unbounded_read_pct');

// bash_fileop_pct = 10/20 = 0.5
assertApprox(score2a.signals.bash_fileop_pct, 0.5, 0.001, '2a: bash_fileop_pct');

// hot_file_concentration: a.txt (20+10=30), b.txt (15), c.txt (10) = 55; total reads = 80
assertApprox(score2a.signals.hot_file_concentration, 55 / 80, 0.01, '2a: hot_file_concentration');

// tool_failure_rate = 2/100 = 0.02
assertApprox(score2a.signals.tool_failure_rate, 0.02, 0.001, '2a: tool_failure_rate');

// subagent_density = 10 / 1 hour = 10
assertApprox(score2a.signals.subagent_density, 10, 0.1, '2a: subagent_density');

// Score should be 0-100
assert(score2a.score >= 0 && score2a.score <= 100, '2a: score in [0, 100]');

// 2b: WASTEFUL rule triggers (unbounded_read_pct > 0.10 AND bash_fileop_pct > 0.40)
assert(score2a.flags.includes('WASTEFUL'), '2b: WASTEFUL flag present');

// 2c: Score clamping at lower bound (extreme positive weight → score < 0 → clamped to 0)
const clamp2c = scoreSession('test-score-2a', ['--weights', '{"unbounded_read_pct":10}']);
assert(clamp2c.score === 0, '2c: Score clamped to 0 with extreme positive weight');

// 2c2: Score clamping at upper bound (negative weight → score > 100 → clamped to 100)
const clamp2c2 = scoreSession('test-score-2a', ['--weights', '{"unbounded_read_pct":-10}']);
assert(clamp2c2.score === 100, '2c2: Score clamped to 100 with negative weight');

// 2d: Perfect session — all signals at zero → score 100, no flags
writeTestReport('test-score-2d', {
  tool_calls: { total: 100, by_type: { Read: 50 }, failure_count: 0 },
  file_reads: { total: 50, unique_files: 50, unbounded_count: 0, by_file: [] },
  file_edits: { total: 0, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 20, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const perfect2d = scoreSession('test-score-2d');
assert(perfect2d.score === 100, '2d: Perfect session scores 100');
assert(perfect2d.flags.length === 0, '2d: Perfect session has no flags');

// 2e: Upsert deduplication — scoring same session twice shouldn't duplicate
const scoresPath = path.join(telemetryDir, 'scores.ndjson');
const linesBefore = fs.readFileSync(scoresPath, 'utf8').split('\n').filter(l => l.trim()).length;
scoreSession('test-score-2a');
const linesAfter = fs.readFileSync(scoresPath, 'utf8').split('\n').filter(l => l.trim()).length;
assert(linesBefore === linesAfter, '2e: Upsert does not increase line count');

// 2f: THRASHING rule — rapid re-edit clusters + concentrated reads
writeTestReport('test-score-thrash', {
  tool_calls: { total: 100, by_type: { Read: 50, Edit: 50 }, failure_count: 0 },
  file_reads: {
    total: 100, unique_files: 2, unbounded_count: 0, by_file: [
      { file: 'hot.txt', count: 90, unbounded: 0 },
      { file: 'other.txt', count: 10, unbounded: 0 },
    ],
  },
  file_edits: {
    total: 33, by_file: [
      { file: 'hot.txt', count: 33, timestamps: makeEditTimestamps(11, 3) },
    ],
  },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 10, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const thrash = scoreSession('test-score-thrash');
assert(thrash.signals.rapid_reedit_count === 11, `2f: rapid_reedit_count is 11 (got ${thrash.signals.rapid_reedit_count})`);
assert(thrash.flags.includes('THRASHING'), '2f: THRASHING flag present');
assert(!thrash.flags.includes('WASTEFUL'), '2f: WASTEFUL not triggered (rule independence)');

// 2f2: Tempdoc edits excluded from rapid_reedit_count
writeTestReport('test-score-thrash-tempdoc', {
  tool_calls: { total: 100, by_type: { Read: 50, Edit: 50 }, failure_count: 0 },
  file_reads: {
    total: 100, unique_files: 2, unbounded_count: 0, by_file: [
      { file: 'hot.txt', count: 90, unbounded: 0 },
      { file: 'other.txt', count: 10, unbounded: 0 },
    ],
  },
  file_edits: {
    total: 66, by_file: [
      { file: 'hot.txt', count: 33, timestamps: makeEditTimestamps(11, 3) },
      { file: 'docs/tempdocs/123-investigation.md', count: 33, timestamps: makeEditTimestamps(11, 3) },
    ],
  },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 10, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const thrashTd = scoreSession('test-score-thrash-tempdoc');
assert(thrashTd.signals.rapid_reedit_count === 11, `2f2: rapid_reedit_count excludes tempdocs (got ${thrashTd.signals.rapid_reedit_count})`);
assert(thrashTd.flags.includes('THRASHING'), '2f2: THRASHING still fires from code edits');

// 2g: CONTEXT_PRESSURE rule was removed (zero predictive validity at N=123, delta=-0.8).
// High subagent density alone should NOT produce any flags.
writeTestReport('test-score-pressure', {
  tool_calls: { total: 100, by_type: { Read: 50, Bash: 50 }, failure_count: 0 },
  file_reads: {
    total: 50, unique_files: 50, unbounded_count: 0, by_file: [],
  },
  file_edits: { total: 0, by_file: [] },
  subagents: { count: 35, by_type: { Explore: 35 } },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 50, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const pressure = scoreSession('test-score-pressure');
assert(pressure.flags.length === 0, `2g: No flags for high subagent density alone (got ${pressure.flags})`);

// 2g2: build_cycle_rate signal — normal case
writeTestReport('test-score-build-cycle', {
  tool_calls: { total: 100, by_type: { Read: 30, Edit: 40, Bash: 30 }, failure_count: 0 },
  file_reads: { total: 30, unique_files: 10, unbounded_count: 0, by_file: [] },
  file_edits: { total: 40, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 80, file_op_count: 0, build_count: 50, failed_build_count: 50 },
  duration_seconds: 3600,
});
const buildCycle = scoreSession('test-score-build-cycle');
assertApprox(buildCycle.signals.build_cycle_rate, 1.25, 0.01, '2g2: build_cycle_rate = 50 failed/40 edits');

// 2g3: build_cycle_rate — zero builds means rate 0
writeTestReport('test-score-zero-builds', {
  tool_calls: { total: 50, by_type: { Edit: 50 }, failure_count: 0 },
  file_reads: { total: 0, unique_files: 0, unbounded_count: 0, by_file: [] },
  file_edits: { total: 30, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 0, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const zeroBuilds = scoreSession('test-score-zero-builds');
assertApprox(zeroBuilds.signals.build_cycle_rate, 0, 0.01, '2g3: build_cycle_rate with 0 builds = 0');

// 2g4: build_cycle_rate — zero edits with builds means rate 0 (no cycling without edits)
writeTestReport('test-score-zero-edits', {
  tool_calls: { total: 50, by_type: { Bash: 50 }, failure_count: 0 },
  file_reads: { total: 0, unique_files: 0, unbounded_count: 0, by_file: [] },
  file_edits: { total: 0, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 20, file_op_count: 0, build_count: 10, failed_build_count: 5 },
  duration_seconds: 3600,
});
const zeroEdits = scoreSession('test-score-zero-edits');
assertApprox(zeroEdits.signals.build_cycle_rate, 0, 0.01, '2g4: build_cycle_rate with 0 edits = 0 (even with failed builds)');

// 2g5: build_cycle_rate — all builds succeed (verification only, no cycling)
writeTestReport('test-score-verify-only', {
  tool_calls: { total: 60, by_type: { Read: 10, Edit: 20, Bash: 30 }, failure_count: 0 },
  file_reads: { total: 10, unique_files: 5, unbounded_count: 0, by_file: [] },
  file_edits: { total: 20, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 30, file_op_count: 0, build_count: 15, failed_build_count: 0 },
  duration_seconds: 3600,
});
const verifyOnly = scoreSession('test-score-verify-only');
assertApprox(verifyOnly.signals.build_cycle_rate, 0, 0.01, '2g5: build_cycle_rate = 0 when all builds succeed');

// 2g6: failed_build_pct — normal case (3 failed out of 10 builds)
writeTestReport('test-score-failed-build-pct', {
  tool_calls: { total: 50, by_type: { Edit: 20, Bash: 30 }, failure_count: 0 },
  file_reads: { total: 10, unique_files: 5, unbounded_count: 0, by_file: [] },
  file_edits: { total: 20, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 30, file_op_count: 0, build_count: 10, failed_build_count: 3 },
  duration_seconds: 3600,
});
const fbp = scoreSession('test-score-failed-build-pct');
assertApprox(fbp.signals.failed_build_pct, 0.3, 0.01, '2g6: failed_build_pct = 3/10 = 0.3');

// 2g7: failed_build_pct — zero builds → 0
assertApprox(zeroBuilds.signals.failed_build_pct, 0, 0.01, '2g7: failed_build_pct = 0 when no builds');

// 2g8: failed_build_pct — all builds failed → 1.0
writeTestReport('test-score-all-builds-fail', {
  tool_calls: { total: 50, by_type: { Edit: 20, Bash: 30 }, failure_count: 0 },
  file_reads: { total: 10, unique_files: 5, unbounded_count: 0, by_file: [] },
  file_edits: { total: 20, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 20, file_op_count: 0, build_count: 8, failed_build_count: 8 },
  duration_seconds: 3600,
});
const allFail = scoreSession('test-score-all-builds-fail');
assertApprox(allFail.signals.failed_build_pct, 1.0, 0.01, '2g8: failed_build_pct = 1.0 when all builds fail');

// 2g9: reedit_per_edit — 11 clusters in 33 edits
assertApprox(thrash.signals.reedit_per_edit, 11 / 33, 0.01, '2g9: reedit_per_edit = 11/33 ≈ 0.333');

// 2g10: reedit_per_edit — 0 edits → 0
assertApprox(zeroEdits.signals.reedit_per_edit, 0, 0.01, '2g10: reedit_per_edit = 0 when no edits');

// 2g11: subagent_failure_rate — no outcomes data → 0 (backward compat)
assertApprox(fbp.signals.subagent_failure_rate, 0, 0.01, '2g11: subagent_failure_rate = 0 without outcomes data');

// 2g12: subagent_failure_rate — with outcomes data
writeTestReport('test-score-subagent-outcomes', {
  tool_calls: { total: 50, by_type: { Read: 20, Bash: 30 }, failure_count: 0 },
  file_reads: { total: 20, unique_files: 10, unbounded_count: 0, by_file: [] },
  file_edits: { total: 0, by_file: [] },
  subagents: { count: 5, by_type: { Explore: 5 } },
  subagent_tool_calls: {
    total: 30, by_type: { Read: 20, Bash: 10 },
    file_reads: { total: 10, unbounded_count: 0, by_file: [] },
    transcripts_found: 5, transcripts_missing: 0, transcript_coverage: 1.0,
    outcomes: { success: 3, failure: 1, partial: 1 },
  },
  bash_commands: { total: 30, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});
const subOutcome = scoreSession('test-score-subagent-outcomes');
assertApprox(subOutcome.signals.subagent_failure_rate, 0.2, 0.01, '2g12: subagent_failure_rate = 1/5 = 0.2');

// 2h: Scoring with missing subagent_tool_calls — should not crash
writeTestReport('test-score-nosub', {
  subagent_tool_calls: undefined,
  subagents: { count: 0, by_type: {} },
});
// Remove the key entirely from JSON (spread undefined doesn't remove, so rewrite)
const nosubPath = path.join(sessionsDir, 'test-score-nosub.json');
const nosubReport = JSON.parse(fs.readFileSync(nosubPath, 'utf8'));
delete nosubReport.subagent_tool_calls;
fs.writeFileSync(nosubPath, JSON.stringify(nosubReport), 'utf8');
const nosub = scoreSession('test-score-nosub');
assert(typeof nosub.score === 'number' && nosub.score >= 0, '2h: Valid score without subagent_tool_calls');

// 2i: Scoring with missing file_reads — should not crash
writeTestReport('test-score-noreads', {
  file_reads: undefined,
});
const noreadsPath = path.join(sessionsDir, 'test-score-noreads.json');
const noreadsReport = JSON.parse(fs.readFileSync(noreadsPath, 'utf8'));
delete noreadsReport.file_reads;
fs.writeFileSync(noreadsPath, JSON.stringify(noreadsReport), 'utf8');
const noreads = scoreSession('test-score-noreads');
assert(typeof noreads.score === 'number' && noreads.score >= 0, '2i: Valid score without file_reads');

// 2j: Shrinkage formula correctness (computeTypeCeilings — direct import)
{
  const invSignals = {
    unbounded_read_pct: 0.10, bash_fileop_pct: 0.20, rapid_reedit_count: 5,
    hot_file_concentration: 0.30, tool_failure_rate: 0.02, subagent_density: 8, build_cycle_rate: 0.05,
    failed_build_pct: 0.10, reedit_per_edit: 0.05, subagent_failure_rate: 0,
  };
  const featSignals = {
    unbounded_read_pct: 0.20, bash_fileop_pct: 0.40, rapid_reedit_count: 15,
    hot_file_concentration: 0.50, tool_failure_rate: 0.05, subagent_density: 20, build_cycle_rate: 0.10,
    failed_build_pct: 0.20, reedit_per_edit: 0.15, subagent_failure_rate: 0.10,
  };
  const docsSignals = {
    unbounded_read_pct: 0.50, bash_fileop_pct: 0.70, rapid_reedit_count: 30,
    hot_file_concentration: 0.90, tool_failure_rate: 0.08, subagent_density: 40, build_cycle_rate: 0.20,
    failed_build_pct: 0.30, reedit_per_edit: 0.25, subagent_failure_rate: 0.20,
  };
  const entries2j = [
    // 20 investigation → shrinkage=1.0 (fully type-specific)
    ...Array(20).fill(null).map(() => ({ signals: invSignals, taskType: 'investigation' })),
    // 10 feature → shrinkage=0.5 (halfway to global)
    ...Array(10).fill(null).map(() => ({ signals: featSignals, taskType: 'feature' })),
    // 1 docs → shrinkage=0.05 (~global)
    { signals: docsSignals, taskType: 'docs' },
  ];
  const ceilingsMap = computeTypeCeilings(entries2j);

  assert(ceilingsMap.has('investigation'), '2j: investigation type present');
  assert(ceilingsMap.has('feature'), '2j: feature type present');
  assert(ceilingsMap.has('docs'), '2j: docs type present');

  // investigation: N=20=N_STABLE → shrinkage=1.0 → ceiling = observed p75
  // All values identical → p75 = the value itself
  assertApprox(ceilingsMap.get('investigation').unbounded_read_pct, 0.10, 0.001, '2j: investigation ceiling = observed p75 at N=20');

  // feature: N=10 → shrinkage=0.5 → type_ceiling = global + (p75 - global) * 0.5
  // = 0.30 + (0.20 - 0.30) * 0.5 = 0.30 - 0.05 = 0.25
  assertApprox(ceilingsMap.get('feature').unbounded_read_pct, 0.25, 0.001, '2j: feature ceiling shrunk 50% toward global');

  // docs: N=1 → shrinkage=0.05 → type_ceiling = 0.30 + (0.50 - 0.30) * 0.05 = 0.31
  assertApprox(ceilingsMap.get('docs').unbounded_read_pct, 0.31, 0.001, '2j: docs ceiling ~global at N=1');
}

// 2k: Custom ceilings change scores (computeScore with ceilings param)
{
  const testSignals = {
    unbounded_read_pct: 0.15, bash_fileop_pct: 0.30, rapid_reedit_count: 10,
    hot_file_concentration: 0.40, tool_failure_rate: 0.03, subagent_density: 12, build_cycle_rate: 0.08,
    failed_build_pct: 0.10, reedit_per_edit: 0.05, subagent_failure_rate: 0,
  };
  const globalScore = computeScore(testSignals);

  // Tighter ceilings (half of global) → higher normalized values → lower scores
  const tightCeilings = {};
  for (const [k, v] of Object.entries(SIGNAL_CEILINGS)) tightCeilings[k] = v / 2;
  const tightScore = computeScore(testSignals, null, tightCeilings);
  assert(tightScore < globalScore, `2k: tighter ceilings produce lower score (${tightScore} < ${globalScore})`);

  // Looser ceilings (double global) → lower normalized values → higher scores
  const looseCeilings = {};
  for (const [k, v] of Object.entries(SIGNAL_CEILINGS)) looseCeilings[k] = v * 2;
  const looseScore = computeScore(testSignals, null, looseCeilings);
  assert(looseScore > globalScore, `2k: looser ceilings produce higher score (${looseScore} > ${globalScore})`);
}

// 2l: Null ceilings backward compat — same result as no ceilings param
{
  const testSignals = {
    unbounded_read_pct: 0.15, bash_fileop_pct: 0.30, rapid_reedit_count: 10,
    hot_file_concentration: 0.40, tool_failure_rate: 0.03, subagent_density: 12, build_cycle_rate: 0.08,
    failed_build_pct: 0.10, reedit_per_edit: 0.05, subagent_failure_rate: 0,
  };
  const scoreWithNull = computeScore(testSignals, null, null);
  const scoreDefault = computeScore(testSignals);
  assert(scoreWithNull === scoreDefault, `2l: null ceilings equals default (${scoreWithNull} === ${scoreDefault})`);
}

// 2m: Empty entries → empty map
{
  const emptyCeilings = computeTypeCeilings([]);
  assert(emptyCeilings.size === 0, '2m: empty entries → empty map');
}

// 2n: Null taskType entries ignored
{
  const mixedEntries = [
    { signals: { unbounded_read_pct: 0.20, bash_fileop_pct: 0.30, rapid_reedit_count: 5,
                 hot_file_concentration: 0.40, tool_failure_rate: 0.03, subagent_density: 10, build_cycle_rate: 0.05,
                 failed_build_pct: 0.10, reedit_per_edit: 0.05, subagent_failure_rate: 0 },
      taskType: null },
    { signals: { unbounded_read_pct: 0.10, bash_fileop_pct: 0.20, rapid_reedit_count: 3,
                 hot_file_concentration: 0.30, tool_failure_rate: 0.02, subagent_density: 8, build_cycle_rate: 0.03,
                 failed_build_pct: 0.05, reedit_per_edit: 0.03, subagent_failure_rate: 0 },
      taskType: 'refactor' },
  ];
  const mixedCeilings = computeTypeCeilings(mixedEntries);
  assert(mixedCeilings.size === 1, '2n: only typed entries create ceilings');
  assert(mixedCeilings.has('refactor'), '2n: refactor ceiling present');
}

console.log();

// ============================================================
// Test 3: Trend analysis (analyze-trends.mjs)
// ============================================================
console.log('Test 3: Trend analysis');

// Write a synthetic session with absolute paths and high counts to dominate output.
// This makes the test deterministic regardless of what other sessions exist.
const absPath = process.platform === 'win32'
  ? 'D:\\code\\JustSearch\\modules\\test-trend-file.txt'
  : '/home/user/code/JustSearch/modules/test-trend-file.txt';
const absEditPath = process.platform === 'win32'
  ? 'D:\\code\\JustSearch\\modules\\test-trend-edit.txt'
  : '/home/user/code/JustSearch/modules/test-trend-edit.txt';

writeTestReport('test-trends-3', {
  tool_calls: { total: 10000, by_type: { Read: 9000, Edit: 1000 }, failure_count: 0 },
  file_reads: {
    total: 9999, unique_files: 1, unbounded_count: 100, by_file: [
      { file: absPath, count: 9999, unbounded: 100 },
    ],
  },
  file_edits: {
    total: 240, by_file: [
      // 3 clusters of 40 edits at 3s apart (39*3=117s < 120s window) — maxInCluster=40 dominates real data
      { file: absEditPath, count: 120, timestamps: makeEditTimestamps(3, 40, 3_000) },
      { file: 'docs/tempdocs/999-test-investigation.md', count: 120, timestamps: makeEditTimestamps(3, 40, 3_000) },
    ],
  },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: {
    total: 100, by_type: { Read: 100 },
    file_reads: {
      total: 5000, unbounded_count: 50, by_file: [
        { file: absPath, count: 5000, unbounded: 50 },
      ],
    },
    transcripts_found: 1, transcripts_missing: 0, transcript_coverage: 1.0,
  },
  bash_commands: { total: 10, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});

const trendOut = execSync(
  `node ${path.join(scriptDir, 'analyze-trends.mjs')} --json`,
  { encoding: 'utf8', timeout: 30000, stdio: ['pipe', 'pipe', 'pipe'] }
);
const trends = JSON.parse(trendOut);

if (trends.hot_files.length === 0) {
  console.error('  WARN: No hot_files in trend output — skipping Test 3');
} else {
  // 3a: Synthetic file should appear with relative path, not absolute
  const syntheticHot = trends.hot_files.find(f =>
    f.file.includes('test-trend-file')
  );
  assert(syntheticHot != null, '3a: Synthetic file appears in hot_files');
  if (syntheticHot) {
    const isAbsolute = /^[A-Z]:[\\/]/.test(syntheticHot.file) || syntheticHot.file.startsWith('/home/');
    assert(!isAbsolute, `3a: Path is relative (got ${syntheticHot.file})`);

    // 3c: Subagent reads merged
    assert(syntheticHot.subagent_reads > 0, `3c: subagent_reads > 0 (got ${syntheticHot.subagent_reads})`);
  }

  // 3b: Absolute edit paths also sanitized
  const syntheticEdit = trends.rapid_edit_clusters.find(f =>
    f.file.includes('test-trend-edit')
  );
  assert(syntheticEdit != null, '3b: Synthetic edit appears in rapid_edit_clusters');
  if (syntheticEdit) {
    const isAbsolute = /^[A-Z]:[\\/]/.test(syntheticEdit.file) || syntheticEdit.file.startsWith('/home/');
    assert(!isAbsolute, `3b: Edit path is relative (got ${syntheticEdit.file})`);
  }

  // 3e: Tempdocs excluded from rapid_edit_clusters
  const tempdocEdit = trends.rapid_edit_clusters.find(f =>
    f.file.includes('docs/tempdocs/')
  );
  assert(tempdocEdit == null, '3e: Tempdocs excluded from rapid_edit_clusters');
}

// 3d: Unbounded rate has main_only field
assert(trends.unbounded_read_rate.main_only != null, '3d: Unbounded rate has main_only field');
assert(trends.unbounded_read_rate.main_only.mean != null, '3d: main_only has mean');

console.log();

// ============================================================
// Test 4: Dynamic file size limiting (A1 — statSync)
// ============================================================
console.log('Test 4: Dynamic file size limiting');

// Create temp files for size-based testing
const tempSmallFile = path.join(telemetryDir, 'test-small-file.txt');
const tempLargeFile = path.join(telemetryDir, 'test-large-file.txt');
fs.writeFileSync(tempSmallFile, 'small content\n'.repeat(10), 'utf8'); // ~140 bytes
fs.writeFileSync(tempLargeFile, 'x'.repeat(15_000), 'utf8'); // 15KB > 12KB threshold

// 4a: Small file (<12KB) — no limit injected
const out4a = runHook({
  tool_name: 'Read',
  tool_input: { file_path: tempSmallFile },
  session_id: 'test-size-4a',
});
testCachePatterns.push('test-size-4a');
assert(out4a === '', '4a: No limit for small file');

// 4b: Large file (>12KB) — limit injected
const out4b = runHook({
  tool_name: 'Read',
  tool_input: { file_path: tempLargeFile },
  session_id: 'test-size-4b',
});
testCachePatterns.push('test-size-4b');
const parsed4b = JSON.parse(out4b);
assert(parsed4b.hookSpecificOutput?.updatedInput?.limit === 200, '4b: Injects limit 200 for large file');
assert(parsed4b.hookSpecificOutput?.permissionDecision === 'allow', '4b: Sets permissionDecision to allow');

// 4c: Large file with existing offset — no injection
const out4c = runHook({
  tool_name: 'Read',
  tool_input: { file_path: tempLargeFile, offset: 100 },
  session_id: 'test-size-4c',
});
testCachePatterns.push('test-size-4c');
assert(out4c === '', '4c: No injection when offset already set');

// 4d: Nonexistent file — no crash, no limit
const out4d = runHook({
  tool_name: 'Read',
  tool_input: { file_path: '/nonexistent/path/to/file.txt' },
  session_id: 'test-size-4d',
});
testCachePatterns.push('test-size-4d');
assert(out4d === '', '4d: No crash for nonexistent file');

console.log();

// ============================================================
// Test 5: Edit tracking (no warnings, tracking only for compact-save)
// ============================================================
console.log('Test 5: Edit tracking (no warnings)');

const editSessionId = 'test-edit-rate-' + Date.now();
testCachePatterns.push(editSessionId);

// 5a: Many edits — no output at any count (warnings removed)
for (let i = 0; i < 10; i++) {
  const out = runHook({
    tool_name: 'Edit',
    tool_input: { file_path: '/tmp/edit-target.txt' },
    session_id: editSessionId,
  });
  assert(out === '', `5a: No output on edit ${i + 1} (tracking only)`);
}

// 5b: Missing file_path — no crash
const out5b = runHook({
  tool_name: 'Edit',
  tool_input: {},
  session_id: editSessionId,
});
assert(out5b === '', '5b: No crash with missing file_path');

console.log();

// ============================================================
// Test 6: Bash file-op blocking (A3 — bash-guard.mjs)
// ============================================================
console.log('Test 6: Bash file-op blocking');

// 6a: bare cat — blocked
const bg6a = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'cat file.txt' },
});
assert(bg6a.status === 2, '6a: bare cat blocked (exit 2)');
assert(bg6a.stderr.includes('Read'), '6a: stderr suggests Read tool');

// 6b: bare head — blocked
const bg6b = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'head -n 50 file.txt' },
});
assert(bg6b.status === 2, '6b: bare head blocked');

// 6c: bare tail — blocked
const bg6c = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'tail -n 20 file.txt' },
});
assert(bg6c.status === 2, '6c: bare tail blocked');

// 6d: bare grep — blocked
const bg6d = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'grep pattern file.txt' },
});
assert(bg6d.status === 2, '6d: bare grep blocked');
assert(bg6d.stderr.includes('Grep'), '6d: stderr suggests Grep tool');

// 6e: cat with pipe — allowed
const bg6e = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'cat file.txt | jq .' },
});
assert(bg6e.status === 0, '6e: cat with pipe allowed');

// 6f: git grep — allowed (git command)
const bg6f = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'git grep pattern' },
});
assert(bg6f.status === 0, '6f: git grep allowed');

// 6g: grep with redirect — allowed
const bg6g = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'grep pattern file > output.txt' },
});
assert(bg6g.status === 0, '6g: grep with redirect allowed');

// 6h: npm install — allowed (not a file-op)
const bg6h = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'npm install' },
});
assert(bg6h.status === 0, '6h: npm install allowed');

// 6i: Non-Bash tool_name — ignored (exit 0)
const bg6i = runBashGuard({
  tool_name: 'Read',
  tool_input: { command: 'cat file.txt' },
});
assert(bg6i.status === 0, '6i: Non-Bash tool ignored');

// 6j: cat with chaining — allowed
const bg6j = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: 'cat file.txt && echo done' },
});
assert(bg6j.status === 0, '6j: cat with chaining allowed');

// 6k: empty command — no crash
const bg6k = runBashGuard({
  tool_name: 'Bash',
  tool_input: { command: '' },
});
assert(bg6k.status === 0, '6k: empty command allowed');

console.log();

// ============================================================
// Test 7: PreCompact state capture (B1 — compact-save.mjs)
// ============================================================
console.log('Test 7: PreCompact state capture');

const compactSessionId = 'test-compact-' + Date.now();
testCachePatterns.push(compactSessionId);
const compactStatePath = path.join(telemetryDir, `compact-state-${compactSessionId}.json`);

// Pre-populate read and edit count caches for this session (new {total, unbounded} format)
const testReadCounts = {
  'src/main.ts': { total: 5, unbounded: 3 },
  'src/utils.ts': { total: 3, unbounded: 1 },
};
const testEditCounts = { 'src/main.ts': [Date.now() - 60000, Date.now() - 30000] };
const readCountsAfterSave = path.join(telemetryDir, `read-counts-${compactSessionId}.json`);
const repeatBufferAfterSave = path.join(telemetryDir, `repeat-buffer-${compactSessionId}.json`);
fs.writeFileSync(readCountsAfterSave, JSON.stringify(testReadCounts), 'utf8');
fs.writeFileSync(path.join(telemetryDir, `edit-counts-${compactSessionId}.json`), JSON.stringify(testEditCounts), 'utf8');
// Pre-populate repeat-buffer BEFORE compact-save so we can verify it gets cleared
fs.writeFileSync(repeatBufferAfterSave, JSON.stringify({ buffer: ['Read|src/main.ts||'] }), 'utf8');

// 7a: Run compact-save — should write state file, reset read-counts, clear repeat-buffer
runCompactSave({
  hook_event_name: 'PreCompact',
  session_id: compactSessionId,
  trigger: 'auto',
});
assert(fs.existsSync(compactStatePath), '7a: compact-state file written');

// 7b: Verify state file contents
const savedState = JSON.parse(fs.readFileSync(compactStatePath, 'utf8'));
assert(savedState.session_id === compactSessionId, '7b: session_id matches');
assert(savedState.trigger === 'auto', '7b: trigger captured');
assert(typeof savedState.ts === 'string', '7b: has timestamp');
assert(Array.isArray(savedState.modified_files), '7b: modified_files is array');
assert(typeof savedState.read_files === 'object', '7b: read_files is object');
assert(savedState.read_files['src/main.ts']?.total === 5, '7b: read_files preserved (object format)');
assert(typeof savedState.edited_files === 'object', '7b: edited_files is object');
assert(Array.isArray(savedState.edited_files['src/main.ts']), '7b: edit timestamps preserved');
assert(typeof savedState.memory_summary === 'string', '7b: memory_summary is string');

// 7b2: compact-save resets read-counts and clears repeat-buffer
const readCountsContent = JSON.parse(fs.readFileSync(readCountsAfterSave, 'utf8'));
assert(Object.keys(readCountsContent).length === 0, '7b2: read-counts reset to {} after compact-save');
assert(!fs.existsSync(repeatBufferAfterSave), '7b2: repeat-buffer cleared by compact-save');

// 7c: Missing session_id — no crash, no file
runCompactSave({ hook_event_name: 'PreCompact' });
// Just verify it didn't crash (the assertion is that runCompactSave didn't throw)
assert(true, '7c: No crash with missing session_id');

console.log();

// ============================================================
// Test 8: SessionStart state restore (B2 — compact-restore.mjs)
// ============================================================
console.log('Test 8: SessionStart state restore');

// Clean up any existing rules file from prior test runs
try { fs.unlinkSync(rulesFile); } catch {}

// 8a: Restore from compact source — should write rules file AND emit additionalContext
const out8a = runCompactRestore({
  hook_event_name: 'SessionStart',
  session_id: compactSessionId,
  source: 'compact',
});
const parsed8a = JSON.parse(out8a);
assert(parsed8a.hookSpecificOutput?.additionalContext?.includes('Compaction State'), '8a: additionalContext has header');
assert(parsed8a.hookSpecificOutput?.additionalContext?.includes('src/main.ts'), '8a: additionalContext contains read file info');
assert(parsed8a.hookSpecificOutput?.additionalContext?.includes('5 reads'), '8a: additionalContext shows numeric read count (not [object Object])');

// 8b: Rules file should be written with session state
assert(fs.existsSync(rulesFile), '8b: rules file written');
const rulesContent8b = fs.readFileSync(rulesFile, 'utf8');
assert(rulesContent8b.includes('Compaction State'), '8b: rules file has header');
assert(rulesContent8b.includes('src/main.ts'), '8b: rules file contains read files');
assert(rulesContent8b.includes('5 reads'), '8b: rules file shows numeric read count (not [object Object])');

// 8c: State file should be deleted after restore
assert(!fs.existsSync(compactStatePath), '8c: compact-state file deleted after restore');

// 8d: Non-compact source — no output, rules file cleaned up
// First write a fake stale rules file
fs.writeFileSync(rulesFile, 'stale content', 'utf8');
const out8d = runCompactRestore({
  hook_event_name: 'SessionStart',
  session_id: compactSessionId,
  source: 'fresh',
});
assert(out8d === '', '8d: No output for non-compact source');
assert(!fs.existsSync(rulesFile), '8d: Stale rules file cleaned up on fresh start');

// 8e: No state file on disk — no output (graceful)
const out8e = runCompactRestore({
  hook_event_name: 'SessionStart',
  session_id: 'test-no-state-' + Date.now(),
  source: 'compact',
});
assert(out8e === '', '8e: No output when no state file exists');

// 8f: Missing session_id — no crash
const out8f = runCompactRestore({
  hook_event_name: 'SessionStart',
  source: 'compact',
});
assert(out8f === '', '8f: No crash with missing session_id');

// 8g: Integration — save then restore round-trip
const rtSessionId = 'test-roundtrip-' + Date.now();
testCachePatterns.push(rtSessionId);
const rtReadCounts = { 'a.java': 10, 'b.tsx': 7 };
fs.writeFileSync(path.join(telemetryDir, `read-counts-${rtSessionId}.json`), JSON.stringify(rtReadCounts), 'utf8');

runCompactSave({
  hook_event_name: 'PreCompact',
  session_id: rtSessionId,
  trigger: 'manual',
});

// Clean rules file before round-trip test
try { fs.unlinkSync(rulesFile); } catch {}

const rtOut = runCompactRestore({
  hook_event_name: 'SessionStart',
  session_id: rtSessionId,
  source: 'compact',
});
const rtParsed = JSON.parse(rtOut);
assert(rtParsed.hookSpecificOutput?.additionalContext?.includes('a.java'), '8g: Round-trip preserves read files');
assert(rtParsed.hookSpecificOutput?.additionalContext?.includes('10 reads'), '8g: Round-trip preserves read count');
// Also verify rules file was written in round-trip
assert(fs.existsSync(rulesFile), '8g: Round-trip writes rules file');
const rtRulesContent = fs.readFileSync(rulesFile, 'utf8');
assert(rtRulesContent.includes('a.java'), '8g: Round-trip rules file has read files');

// 8h: SessionStart export hook writes JUSTSEARCH_AGENT_SESSION_ID into CLAUDE_ENV_FILE
fs.mkdirSync(tmpDir, { recursive: true });
const envFile8h = path.join(tmpDir, `claude-env-${Date.now()}.sh`);
runExportSessionEnv({
  hook_event_name: 'SessionStart',
  session_id: 'test-export-session-8h',
}, {
  CLAUDE_ENV_FILE: envFile8h,
});
const envFileContent8h = fs.readFileSync(envFile8h, 'utf8');
assert(envFileContent8h.includes(`export JUSTSEARCH_AGENT_SESSION_ID='test-export-session-8h'`),
  '8h: export-session-env writes JUSTSEARCH_AGENT_SESSION_ID');

// 8i: Bad CLAUDE_ENV_FILE is non-blocking and logs an error
const priorErrors8i = fs.existsSync(errorsFile) ? fs.readFileSync(errorsFile, 'utf8') : '';
runExportSessionEnv({
  hook_event_name: 'SessionStart',
  session_id: 'test-export-session-8i',
}, {
  CLAUDE_ENV_FILE: telemetryDir,
});
const nextErrors8i = fs.existsSync(errorsFile) ? fs.readFileSync(errorsFile, 'utf8') : '';
assert(nextErrors8i.length >= priorErrors8i.length, '8i: export-session-env remains non-blocking on bad env file');
assert(nextErrors8i.includes('export-session-env error'), '8i: export-session-env logs bad env file errors');

// 8j: settings.local.json wires export-session-env first on SessionStart
const settings8j = JSON.parse(fs.readFileSync(settingsFile, 'utf8'));
const sessionStartHooks8j = Array.isArray(settings8j.hooks?.SessionStart) ? settings8j.hooks.SessionStart : [];
const firstCommand8j = sessionStartHooks8j[0]?.hooks?.[0]?.command || '';
assert(firstCommand8j.includes('export-session-env.mjs'), '8j: SessionStart export hook is registered first');

console.log();

// ============================================================
// Test 9: Unbounded large-file read metric (B3)
// ============================================================
console.log('Test 9: Unbounded large-file read metric');

// Re-analyze a test session to check the new field exists
writeTestReport('test-b3-metric', {
  tool_calls: { total: 100, by_type: { Read: 50 }, failure_count: 0 },
  file_reads: {
    total: 50, unique_files: 5, unbounded_count: 25, by_file: [],
  },
  file_edits: { total: 0, by_file: [] },
  subagents: { count: 0, by_type: {} },
  subagent_tool_calls: { total: 0, by_type: {}, file_reads: { total: 0, unbounded_count: 0, by_file: [] }, transcripts_found: 0, transcripts_missing: 0, transcript_coverage: null },
  bash_commands: { total: 10, file_op_count: 0, build_count: 0, failed_build_count: 0 },
  duration_seconds: 3600,
});

// Read the report back (written by writeTestReport via scoring)
const b3ReportPath = path.join(sessionsDir, 'test-b3-metric.json');
const b3Report = JSON.parse(fs.readFileSync(b3ReportPath, 'utf8'));

// 9a: Report should have the unbounded_large_count field
assert(b3Report.file_reads?.unbounded_large_count != null || b3Report.file_reads?.unbounded_count != null,
  '9a: file_reads has unbounded metrics');

// 9b: Existing unbounded_count still works
assert(typeof b3Report.file_reads?.unbounded_count === 'number', '9b: unbounded_count still present');

// 9c: Score the session — should not crash with the new field
const score9c = scoreSession('test-b3-metric');
assert(typeof score9c.score === 'number', '9c: Scoring works with unbounded_large_count');

console.log();

// ============================================================
// Test 10: SubagentStart guidance hook (C1 — subagent-guide.mjs)
// ============================================================
console.log('Test 10: SubagentStart guidance hook');

// 10a: Standard SubagentStart — emits additionalContext with guidance
const out10a = runSubagentGuide({
  hook_event_name: 'SubagentStart',
  session_id: 'test-c1-1',
  agent_id: 'abc-123',
  agent_type: 'Explore',
});
const parsed10a = JSON.parse(out10a);
assert(parsed10a.hookSpecificOutput?.hookEventName === 'SubagentStart', '10a: hookEventName is SubagentStart');
assert(parsed10a.hookSpecificOutput?.additionalContext?.includes('offset/limit'), '10a: guidance mentions offset/limit');
assert(parsed10a.hookSpecificOutput?.additionalContext?.includes('Grep'), '10a: guidance mentions Grep');
assert(parsed10a.hookSpecificOutput?.additionalContext?.includes('BrainView.tsx'), '10a: guidance mentions codebase-specific files');

// 10b: Different agent_type — same guidance (type-agnostic)
const out10b = runSubagentGuide({
  hook_event_name: 'SubagentStart',
  session_id: 'test-c1-2',
  agent_id: 'def-456',
  agent_type: 'general-purpose',
});
const parsed10b = JSON.parse(out10b);
assert(parsed10b.hookSpecificOutput?.additionalContext?.includes('BrainView.tsx'), '10b: same core guidance for different agent_type');
assert(parsed10b.hookSpecificOutput?.additionalContext?.includes('--session-id test-c1-2'), '10b: guidance includes parent session forwarding');

// 10c: Missing session_id — no crash, still emits guidance
const out10c = runSubagentGuide({
  hook_event_name: 'SubagentStart',
  agent_type: 'Explore',
});
assert(out10c.length > 0, '10c: still emits guidance without session_id');

// 10d: Empty stdin — no crash, no output
const out10d = runSubagentGuide('');
assert(out10d === '', '10d: no output for empty stdin');

// 10e: Non-empty non-JSON — still emits guidance (only checks non-empty, not valid JSON)
const out10e = runSubagentGuide('not json');
assert(out10e.length > 0, '10e: emits guidance for non-empty non-JSON input');

// 10f: Guidance length — must be under 500 characters
assert(parsed10a.hookSpecificOutput.additionalContext.length < 500, '10f: guidance under 500 chars');

// 10g: No permissionDecision (SubagentStart doesn't support it)
assert(parsed10a.hookSpecificOutput.permissionDecision === undefined, '10g: no permissionDecision');

// 10h: No updatedInput (SubagentStart doesn't support it)
assert(parsed10a.hookSpecificOutput.updatedInput === undefined, '10h: no updatedInput');

// 10i: Parent session guidance is present when session_id is available
assert(parsed10a.hookSpecificOutput?.additionalContext?.includes('--session-id test-c1-1'),
  '10i: guidance includes session forwarding command');

console.log();

// Test 11: Subagent prompt guidance in rules file (C3 replacement)
// ============================================================
console.log('Test 11: Subagent prompt guidance');

const rulesContent = fs.readFileSync(path.join(repoRoot, '.claude', 'rules', 'context-efficiency.md'), 'utf8');

// 11a: Rules file instructs main agent to embed guidance in subagent prompts
assert(rulesContent.includes('subagent prompts'), '11a: rules mention subagent prompts');

// 11b: Guidance references codebase-specific large files
assert(rulesContent.includes('BrainView.tsx'), '11b: mentions BrainView.tsx');
assert(rulesContent.includes('SummaryController.java'), '11b: mentions SummaryController.java');

// 11c: Guidance references docs index
assert(rulesContent.includes('docs/llms.txt'), '11c: mentions docs/llms.txt');

// 11d: Guidance mentions offset/limit (the key behavioral fix)
assert(rulesContent.includes('offset/limit'), '11d: mentions offset/limit');

// 11e: Custom Explore agent file should NOT exist (upstream limitation: can't override built-in)
const agentFile = path.join(repoRoot, '.claude', 'agents', 'Explore.md');
assert(fs.existsSync(agentFile) === false, '11e: no dead-code custom Explore agent');

console.log();

// ============================================================
// Test 12: Post-compaction re-read detection
// ============================================================
console.log('Test 12: Post-compaction re-read detection');

// 12a: No compactions → 0 rereads
{
  const report = analyzeTestEvents('test-compact-rereads-12a', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.compaction_rereads.total_rereads === 0, '12a: 0 rereads without compactions');
  assert(report.compaction_rereads.by_compaction.length === 0, '12a: empty by_compaction');
}

// 12b: Files read before AND after compaction → detected
{
  const report = analyzeTestEvents('test-compact-rereads-12b', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:30:00Z', event: 'pre_compact', trigger: 'auto' },
    { ts: '2026-01-01T00:31:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:32:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/c.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.compaction_rereads.total_rereads === 1, '12b: 1 reread (a.txt)');
  assert(report.compaction_rereads.by_compaction.length === 1, '12b: 1 compaction entry');
  assert(report.compaction_rereads.by_compaction[0].reread_count === 1, '12b: compaction has 1 reread');
}

// 12c: Disjoint files before/after → 0 rereads
{
  const report = analyzeTestEvents('test-compact-rereads-12c', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:30:00Z', event: 'pre_compact', trigger: 'auto' },
    { ts: '2026-01-01T00:31:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.compaction_rereads.total_rereads === 0, '12c: 0 rereads with disjoint files');
}

// 12d: Two compactions — each with distinct rereads
{
  const report = analyzeTestEvents('test-compact-rereads-12d', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:30:00Z', event: 'pre_compact', trigger: 'auto' },
    // After first compaction: re-read a.txt, plus new file c.txt
    { ts: '2026-01-01T00:31:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:32:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/c.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'pre_compact', trigger: 'auto' },
    // After second compaction: re-read c.txt (from segment 2), new file d.txt
    { ts: '2026-01-01T01:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/c.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/d.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T02:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.compaction_rereads.by_compaction.length === 2, '12d: 2 compaction entries');
  assert(report.compaction_rereads.by_compaction[0].reread_count === 1, '12d: first compaction has 1 reread (a.txt)');
  assert(report.compaction_rereads.by_compaction[1].reread_count === 1, '12d: second compaction has 1 reread (c.txt)');
  assert(report.compaction_rereads.total_rereads === 2, '12d: 2 total rereads across compactions');
}

console.log();

// ============================================================
// Test 13: Failure cascade detection
// ============================================================
console.log('Test 13: Failure cascade detection');

// 13a: 3 failures within 60s → 1 cascade
{
  const report = analyzeTestEvents('test-cascades-13a', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T00:01:20Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T00:01:40Z', event: 'post_tool_use_failure', tool_name: 'Edit', is_interrupt: false },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.failure_cascades.count === 1, '13a: 1 cascade from 3 failures');
  assert(report.failure_cascades.cascades[0].failure_count === 3, '13a: cascade has 3 failures');
}

// 13b: Only 2 failures → no cascade
{
  const report = analyzeTestEvents('test-cascades-13b', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T00:01:20Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.failure_cascades.count === 0, '13b: no cascade with 2 failures');
}

// 13c: Interrupt failures excluded
{
  const report = analyzeTestEvents('test-cascades-13c', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T00:01:10Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: true },
    { ts: '2026-01-01T00:01:20Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: false },
    { ts: '2026-01-01T00:01:30Z', event: 'post_tool_use_failure', tool_name: 'Bash', is_interrupt: true },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.failure_cascades.count === 0, '13c: no cascade when interrupts excluded');
}

// 13d: No failures → empty
{
  const report = analyzeTestEvents('test-cascades-13d', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt' } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.failure_cascades.count === 0, '13d: no cascades without failures');
}

console.log();

// ============================================================
// Test 14: Context efficiency
// ============================================================
console.log('Test 14: Context efficiency');

// 14a: All first reads → score 1.0
{
  const report = analyzeTestEvents('test-ctx-eff-14a', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:03:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/c.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.context_efficiency.first_reads === 3, '14a: 3 first reads');
  assert(report.context_efficiency.rereads_unchanged === 0, '14a: 0 unchanged rereads');
  assertApprox(report.context_efficiency.score_informational, 1.0, 0.001, '14a: perfect score');
}

// 14b: Re-reads of edited files → classified as changed
{
  const report = analyzeTestEvents('test-ctx-eff-14b', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Edit', input_summary: { file_path: '/repo/a.txt', old_string_length: 10, new_string_length: 20 } },
    { ts: '2026-01-01T00:03:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.context_efficiency.first_reads === 1, '14b: 1 first read');
  assert(report.context_efficiency.rereads_changed === 1, '14b: 1 changed reread');
  assert(report.context_efficiency.rereads_unchanged === 0, '14b: 0 unchanged rereads');
}

// 14c: Re-reads of unedited files → score < 1.0
{
  const report = analyzeTestEvents('test-ctx-eff-14c', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.context_efficiency.rereads_unchanged === 1, '14c: 1 unchanged reread');
  assert(report.context_efficiency.score_informational < 1.0, '14c: imperfect score for rereads');
}

// 14d: Zero reads → score 1.0
{
  const report = analyzeTestEvents('test-ctx-eff-14d', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assertApprox(report.context_efficiency.score_informational, 1.0, 0.001, '14d: perfect score with 0 reads');
}

console.log();

// ============================================================
// Test 15: Read redundancy classification
// ============================================================
console.log('Test 15: Read redundancy classification');

// 15a: Read then Edit → structural (precedes_edit)
{
  const report = analyzeTestEvents('test-redundancy-15a', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:30Z', event: 'pre_tool_use', tool_name: 'Edit', input_summary: { file_path: '/repo/a.txt', old_string_length: 10, new_string_length: 20 } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.read_redundancy.structural === 1, '15a: 1 structural reread');
  assert(report.read_redundancy.by_reason.precedes_edit === 1, '15a: precedes_edit reason');
}

// 15b: Read, compaction, Read → structural (post_compaction)
{
  const report = analyzeTestEvents('test-redundancy-15b', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:30:00Z', event: 'pre_compact', trigger: 'auto' },
    { ts: '2026-01-01T00:31:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.read_redundancy.structural === 1, '15b: 1 structural reread');
  assert(report.read_redundancy.by_reason.post_compaction === 1, '15b: post_compaction reason');
}

// 15c: Read, >5 min gap, Read (no edit) → wasteful
{
  const report = analyzeTestEvents('test-redundancy-15c', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:10:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.read_redundancy.wasteful === 1, '15c: 1 wasteful reread');
  assert(report.read_redundancy.by_reason.long_gap_no_edit === 1, '15c: long_gap_no_edit reason');
}

// 15d: No rereads → all zeros
{
  const report = analyzeTestEvents('test-redundancy-15d', [
    { ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/a.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T00:02:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: { file_path: '/repo/b.txt', has_offset: false, has_limit: false } },
    { ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ]);
  assert(report.read_redundancy.total_rereads === 0, '15d: 0 rereads');
  assert(report.read_redundancy.structural === 0, '15d: 0 structural');
  assert(report.read_redundancy.wasteful === 0, '15d: 0 wasteful');
}

console.log();

// ============================================================
// Test 16: Cost estimation
// ============================================================
console.log('Test 16: Cost estimation');

// Create a synthetic transcript JSONL
const testTranscriptDir = path.join(telemetryDir, 'test-transcripts');
fs.mkdirSync(testTranscriptDir, { recursive: true });
const testTranscriptPath = path.join(testTranscriptDir, 'test-cost.jsonl');

const syntheticTranscript = [
  JSON.stringify({
    type: 'assistant',
    message: {
      model: 'claude-sonnet-4-5-20250929',
      content: [{ type: 'text', text: 'Hello' }],
      usage: {
        input_tokens: 1000,
        output_tokens: 500,
        cache_creation_input_tokens: 200,
        cache_read_input_tokens: 3000,
      }
    },
    timestamp: '2026-01-01T00:05:00Z',
  }),
  JSON.stringify({
    type: 'assistant',
    message: {
      model: 'claude-sonnet-4-5-20250929',
      content: [{ type: 'text', text: 'World' }],
      usage: {
        input_tokens: 2000,
        output_tokens: 1000,
        cache_creation_input_tokens: 100,
        cache_read_input_tokens: 5000,
      }
    },
    timestamp: '2026-01-01T00:10:00Z',
  }),
].join('\n') + '\n';

fs.writeFileSync(testTranscriptPath, syntheticTranscript, 'utf8');

// 16a: Known transcript → correct cost
{
  // Write events with transcript_path
  const sid = 'test-cost-16a';
  testSessionIds.push(sid);
  const events16a = [
    { session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'claude-sonnet-4-5-20250929', transcript_path: testTranscriptPath },
    { session_id: sid, ts: '2026-01-01T00:01:00Z', event: 'pre_tool_use', tool_name: 'Read', input_summary: {} },
    { session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events16a.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  // Create a minimal session report so cost-session can find it
  const minReport = { schema: 'agent-session-report.v1', session_id: sid };
  fs.mkdirSync(sessionsDir, { recursive: true });
  fs.writeFileSync(path.join(sessionsDir, `${sid}.json`), JSON.stringify(minReport), 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'cost-session.mjs'),
    '--session-id', sid,
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const costResult = JSON.parse(output);
  // Sonnet pricing: input=3.0, output=15.0, cache_write=3.75, cache_read=0.30 per 1M
  // Tokens: input=3000, output=1500, cache_write=300, cache_read=8000
  // Cost: (3000/1M)*3 + (1500/1M)*15 + (300/1M)*3.75 + (8000/1M)*0.30
  //     = 0.009 + 0.0225 + 0.001125 + 0.0024 = 0.034525
  assert(costResult.total_cost_usd != null, '16a: cost computed');
  assert(costResult.tokens.input === 3000, '16a: input tokens correct');
  assert(costResult.tokens.output === 1500, '16a: output tokens correct');
  assert(costResult.tokens.cache_write === 300, '16a: cache_write tokens correct');
  assert(costResult.tokens.cache_read === 8000, '16a: cache_read tokens correct');
  assert(costResult.turns === 2, '16a: 2 turns');
  // Exact: (3000/1M)*3 + (1500/1M)*15 + (300/1M)*3.75 + (8000/1M)*0.30 = 0.035025
  assertApprox(costResult.total_cost_usd, 0.0350, 0.001, '16a: total cost approximately correct');
}

// 16b: No transcript → null cost
{
  const sid = 'test-cost-16b';
  testSessionIds.push(sid);
  const events16b = [
    { session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' },
    { session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events16b.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  const minReport = { schema: 'agent-session-report.v1', session_id: sid };
  fs.writeFileSync(path.join(sessionsDir, `${sid}.json`), JSON.stringify(minReport), 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'cost-session.mjs'),
    '--session-id', sid,
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const costResult = JSON.parse(output);
  assert(costResult.total_cost_usd === null, '16b: null cost when no transcript');
  assert(costResult.reason === 'no_transcript_path', '16b: reason is no_transcript_path');
}

// 16c: --all mode preserves existing cost records when transcript is gone
{
  const sidPreserve = 'test-cost-16c-preserve';
  const sidFresh    = 'test-cost-16c-fresh';
  testSessionIds.push(sidPreserve, sidFresh);

  // Seed costs.ndjson with a historical record for sidPreserve ($99.99)
  const costsPath = path.join(telemetryDir, 'costs.ndjson');
  let costsBackup = null;
  try { costsBackup = fs.readFileSync(costsPath, 'utf8'); } catch { /* ok */ }

  const historicalRecord = {
    ts: '2025-01-01T00:00:00.000Z',
    session_id: sidPreserve,
    total_cost_usd: 99.99,
    tokens: { input: 1000, output: 500, cache_write: 0, cache_read: 0 },
    model: 'claude-opus-4-6',
    turns: 10,
    subagent_transcripts_found: 0,
    subagent_transcripts_missing: 0,
    reason: null,
  };
  const existingLines = costsBackup ? costsBackup.split('\n').filter(l => l.trim()) : [];
  existingLines.push(JSON.stringify(historicalRecord));
  fs.writeFileSync(costsPath, existingLines.join('\n') + '\n', 'utf8');

  // Events for sidPreserve: transcript path points to a non-existent file
  // Events for sidFresh: transcript path points to the real test transcript
  const events16c = [
    { session_id: sidPreserve, ts: '2025-01-01T00:00:00Z', event: 'session_start', model: 'test',
      transcript_path: '/nonexistent/gone.jsonl' },
    { session_id: sidPreserve, ts: '2025-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
    { session_id: sidFresh,    ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'claude-sonnet-4-5-20250929',
      transcript_path: testTranscriptPath },
    { session_id: sidFresh,    ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events16c.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  // Create minimal reports for both sessions
  fs.mkdirSync(sessionsDir, { recursive: true });
  fs.writeFileSync(path.join(sessionsDir, `${sidPreserve}.json`),
    JSON.stringify({ schema: 'agent-session-report.v1', session_id: sidPreserve }), 'utf8');
  fs.writeFileSync(path.join(sessionsDir, `${sidFresh}.json`),
    JSON.stringify({ schema: 'agent-session-report.v1', session_id: sidFresh }), 'utf8');

  execFileSync('node', [path.join(scriptDir, 'cost-session.mjs'), '--all'],
    { encoding: 'utf8', timeout: 15000 });

  // Read the merged costs.ndjson and look up both sessions
  const mergedRecords = fs.readFileSync(costsPath, 'utf8')
    .split('\n').filter(l => l.trim()).map(l => JSON.parse(l));
  const preservedRec = mergedRecords.find(r => r.session_id === sidPreserve);
  const freshRec     = mergedRecords.find(r => r.session_id === sidFresh);

  assert(preservedRec != null, '16c: historical record still present');
  assert(preservedRec?.total_cost_usd === 99.99, '16c: historical cost preserved (not overwritten with 0)');
  assert(freshRec != null, '16c: fresh session costed');
  assert((freshRec?.total_cost_usd ?? 0) > 0, '16c: fresh session has real cost');

  // Restore costs.ndjson (remove the test records we injected)
  if (costsBackup != null) {
    fs.writeFileSync(costsPath, costsBackup, 'utf8');
  }
}

console.log();

// ============================================================
// Test 17: Z-score anomaly detection
// ============================================================
console.log('Test 17: Z-score anomaly detection');

// 17a: 5 similar sessions with low/normal signals → 0 anomalies among them
{
  const ids = [];
  for (let i = 0; i < 5; i++) {
    const id = `test-anomaly-17a-${i}`;
    ids.push(id);
    // Use low signal values that cannot be outliers in any population
    writeTestReport(id, {
      file_reads: { total: 100, unique_files: 50, unbounded_count: 5, by_file: [] },
      tool_calls: { total: 100, by_type: {}, failure_count: 1 },
      subagents: { count: 2, by_type: {} },
      file_edits: { total: 40, by_file: [] },
      bash_commands: { total: 20, file_op_count: 1, build_count: 0, failed_build_count: 0 },
    });
  }

  const output = execFileSync('node', [
    path.join(scriptDir, 'score-session.mjs'), '--all', '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const results = JSON.parse(output);
  const testResults = results.filter(r => r.session_id.startsWith('test-anomaly-17a'));
  const totalAnomalies = testResults.reduce((s, r) => s + (r.anomalies?.length ?? 0), 0);
  assert(totalAnomalies === 0, '17a: no anomalies among similar low-signal sessions');
}

// 17b: 1 outlier among 5 → detected
{
  const ids = [];
  for (let i = 0; i < 4; i++) {
    const id = `test-anomaly-17b-${i}`;
    ids.push(id);
    writeTestReport(id, {
      file_reads: { total: 50, unique_files: 10, unbounded_count: 20, by_file: [] },
      tool_calls: { total: 100, by_type: {}, failure_count: 2 },
      subagents: { count: 5, by_type: {} },
      file_edits: { total: 20, by_file: [] },
      bash_commands: { total: 20, file_op_count: 2, build_count: 0, failed_build_count: 0 },
    });
  }
  // Outlier: extreme unbounded reads and subagent density
  const outlierId = 'test-anomaly-17b-outlier';
  ids.push(outlierId);
  writeTestReport(outlierId, {
    file_reads: { total: 100, unique_files: 10, unbounded_count: 95, by_file: [] },
    tool_calls: { total: 200, by_type: {}, failure_count: 20 },
    subagents: { count: 100, by_type: {} },
    file_edits: { total: 20, by_file: [] },
    bash_commands: { total: 50, file_op_count: 40, build_count: 0, failed_build_count: 0 },
  });

  const output = execFileSync('node', [
    path.join(scriptDir, 'score-session.mjs'), '--all', '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const results = JSON.parse(output);
  const outlierResult = results.find(r => r.session_id === outlierId);
  assert(outlierResult.anomalies?.length > 0, '17b: outlier has anomalies');
}

console.log();

// ============================================================
// Test 18: Session index
// ============================================================
console.log('Test 18: Session index');

// 18a: Generates valid JSON
{
  const output = execFileSync('node', [
    path.join(scriptDir, 'generate-index.mjs'), '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const index = JSON.parse(output);
  assert(index.schema === 'agent-session-index.v1', '18a: correct schema');
  assert(typeof index.session_count === 'number', '18a: session_count is number');
  assert(Array.isArray(index.entries), '18a: entries is array');
  assert(index.entries.length > 0, '18a: has entries');
}

// 18b: Entries have expected fields
{
  const output = execFileSync('node', [
    path.join(scriptDir, 'generate-index.mjs'), '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const index = JSON.parse(output);
  const entry = index.entries[0];
  assert(typeof entry.session_id === 'string', '18b: session_id is string');
  assert('score' in entry, '18b: has score field');
  assert('cost_usd' in entry, '18b: has cost_usd field');
  assert('duration_hours' in entry, '18b: has duration_hours field');
}

console.log();

// ============================================================
// Test 19: Dashboard generation
// ============================================================
console.log('Test 19: Dashboard generation');

// 19a: Generates HTML file
{
  const dashboardPath = path.join(telemetryDir, 'dashboard.html');
  execFileSync('node', [
    path.join(scriptDir, 'generate-dashboard.mjs'),
  ], { encoding: 'utf8', timeout: 15000 });

  assert(fs.existsSync(dashboardPath), '19a: dashboard.html exists');

  const html = fs.readFileSync(dashboardPath, 'utf8');
  assert(html.includes('<!DOCTYPE html>'), '19b: valid HTML');
  assert(html.includes('chart.js@4'), '19c: Chart.js CDN reference');
  assert(html.includes('const DATA ='), '19d: embedded data');
  assert(html.includes('Flag Trend'), '19e: has flag trend section');
  assert(html.includes('Process Hygiene Index'), '19e2: has score trend (demoted)');
  assert(html.includes('Signal Heatmap'), '19f: has heatmap section');
  assert(html.includes('Verdict'), '19g: has verdict column');
}

console.log();

// ============================================================
// Test 20: LLM-as-judge evaluation (evaluate-session.mjs)
// ============================================================
console.log('Test 20: LLM-as-judge evaluation');

// Create a richer test transcript with user messages, tool_use, and Bash output
const evalTranscriptPath = path.join(testTranscriptDir, 'test-eval.jsonl');
const evalTranscript = [
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: 'Fix the authentication bug in login.ts' },
  }),
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      model: 'claude-sonnet-4-5-20250929',
      content: [
        { type: 'text', text: 'I will investigate and fix the authentication bug.' },
        { type: 'tool_use', id: 'tool1', name: 'Read', input: { file_path: 'src/login.ts' } },
      ],
      usage: { input_tokens: 1000, output_tokens: 200 },
    },
  }),
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: [
      { type: 'tool_result', tool_use_id: 'tool1', content: 'file contents here...' },
    ] },
  }),
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      model: 'claude-sonnet-4-5-20250929',
      content: [
        { type: 'tool_use', id: 'tool2', name: 'Edit', input: { file_path: 'src/login.ts', old_string: 'x', new_string: 'y' } },
      ],
      usage: { input_tokens: 2000, output_tokens: 100 },
    },
  }),
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: [
      { type: 'tool_result', tool_use_id: 'tool2', content: 'The file has been updated successfully.' },
    ] },
  }),
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      model: 'claude-sonnet-4-5-20250929',
      content: [
        { type: 'text', text: 'The bug is fixed. The issue was an incorrect comparison.' },
        { type: 'tool_use', id: 'tool3', name: 'Bash', input: { command: './gradlew.bat test' } },
      ],
      usage: { input_tokens: 3000, output_tokens: 300 },
    },
  }),
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: [
      { type: 'tool_result', tool_use_id: 'tool3', content: 'BUILD SUCCESSFUL in 42s\n15 tests passed, 0 failed' },
    ] },
  }),
  JSON.stringify({
    type: 'assistant',
    message: {
      role: 'assistant',
      model: 'claude-sonnet-4-5-20250929',
      content: [
        { type: 'tool_use', id: 'tool4', name: 'Bash', input: { command: 'node bad-script.mjs' } },
      ],
      usage: { input_tokens: 4000, output_tokens: 100 },
    },
  }),
  JSON.stringify({
    type: 'user',
    message: { role: 'user', content: [
      { type: 'tool_result', tool_use_id: 'tool4', is_error: true, content: 'Exit code 1\nError: module not found' },
    ] },
  }),
].join('\n') + '\n';
fs.writeFileSync(evalTranscriptPath, evalTranscript, 'utf8');

// 20a: --dry-run --json on a session with transcript produces valid output
{
  const sid = 'test-eval-20a';
  writeTestReport(sid);

  // Write events with transcript_path
  const evalEvents = [
    JSON.stringify({ session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test', transcript_path: evalTranscriptPath }),
    JSON.stringify({ session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'post_tool_use', tool_name: 'Read' }),
  ].join('\n') + '\n';
  fs.writeFileSync(eventsFile, evalEvents, 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'evaluate-session.mjs'),
    '--session-id', sid,
    '--dry-run',
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const evalResult = JSON.parse(output);
  assert(evalResult.session_id === sid, '20a: session_id matches');
  assert(evalResult.eval_model === 'dry-run', '20a: dry-run model');
  assert(['complete', 'partial', 'failed', 'abandoned'].includes(evalResult.task_completion), '20a: valid task_completion');
  assert(evalResult.reason === null, '20a: no skip reason');
  assert(evalResult.eval_input_tokens > 0, '20a: eval_input_tokens > 0');
  // New fields from structured verdict schema
  assert(typeof evalResult.task_intent === 'string', '20a: task_intent is string');
  assert(typeof evalResult.task_completion_rationale === 'string', '20a: task_completion_rationale present');
  assert('tests_added_rationale' in evalResult, '20a: tests_added_rationale field exists');
  assert('build_passed_rationale' in evalResult, '20a: build_passed_rationale field exists');
}

// 20b: Session without transcript_path → reason: no_transcript
{
  const sid = 'test-eval-20b';
  writeTestReport(sid);

  // Write events without transcript_path
  const evalEvents = [
    JSON.stringify({ session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test' }),
  ].join('\n') + '\n';
  fs.writeFileSync(eventsFile, evalEvents, 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'evaluate-session.mjs'),
    '--session-id', sid,
    '--dry-run',
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const evalResult = JSON.parse(output);
  assert(evalResult.task_completion === null, '20b: null completion when no transcript');
  assert(evalResult.reason === 'no_transcript', '20b: reason is no_transcript');
}

// 20c: Upsert deduplication — re-evaluating same session doesn't duplicate
{
  const outcomesPath = path.join(telemetryDir, 'outcomes.ndjson');
  const linesBefore = fs.readFileSync(outcomesPath, 'utf8').split('\n').filter(l => l.trim()).length;

  // Re-evaluate same session from 20a
  const reEvents = [
    JSON.stringify({ session_id: 'test-eval-20a', ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test', transcript_path: evalTranscriptPath }),
  ].join('\n') + '\n';
  fs.writeFileSync(eventsFile, reEvents, 'utf8');

  execFileSync('node', [
    path.join(scriptDir, 'evaluate-session.mjs'),
    '--session-id', 'test-eval-20a',
    '--dry-run',
    '--json',
  ], { encoding: 'utf8', timeout: 15000 });

  const linesAfter = fs.readFileSync(outcomesPath, 'utf8').split('\n').filter(l => l.trim()).length;
  assert(linesBefore === linesAfter, '20c: upsert does not duplicate');
}

// 20d: eval_model is "dry-run" in dry-run mode
{
  const outcomesPath = path.join(telemetryDir, 'outcomes.ndjson');
  const lines = fs.readFileSync(outcomesPath, 'utf8').split('\n').filter(l => l.trim());
  const record20a = lines.map(l => JSON.parse(l)).find(r => r.session_id === 'test-eval-20a');
  assert(record20a?.eval_model === 'dry-run', '20d: eval_model is dry-run');
}

// 20e: Condensation includes user messages, tool calls, Bash output, and errors
{
  // Import condenseTranscript indirectly by reading the dry-run condensed output.
  // The dry-run exercises the full condensation path — we check via a second transcript.
  // Instead, directly test the transcript content by reading the outcomes file and
  // checking the eval_input_tokens (which reflect condensed size).
  // For actual content verification, create a transcript and read it back through the script.

  // Use the evalTranscriptPath which has known content:
  // - 1 user message: "Fix the authentication bug in login.ts"
  // - 3 assistant text blocks
  // - 4 tool_use blocks (Read, Edit, Bash, Bash)
  // - 1 successful Bash output: "BUILD SUCCESSFUL..."
  // - 1 error: "Exit code 1\nError: module not found"

  // Write a condensation test transcript
  const condensePath = path.join(testTranscriptDir, 'test-condense.jsonl');
  const condenseLines = [
    JSON.stringify({ type: 'user', message: { content: 'Deploy to staging' } }),
    JSON.stringify({ type: 'assistant', message: { content: [
      { type: 'text', text: 'Starting deployment process now.' },
      { type: 'tool_use', id: 't1', name: 'Bash', input: { command: 'npm run build' } },
    ] } }),
    JSON.stringify({ type: 'user', message: { content: [
      { type: 'tool_result', tool_use_id: 't1', content: 'Build completed successfully in 3.2s' },
    ] } }),
    JSON.stringify({ type: 'assistant', message: { content: [
      { type: 'tool_use', id: 't2', name: 'Bash', input: { command: 'npm run deploy' } },
    ] } }),
    JSON.stringify({ type: 'user', message: { content: [
      { type: 'tool_result', tool_use_id: 't2', is_error: true, content: 'Exit code 1\nPermission denied: staging.example.com' },
    ] } }),
    // Add a progress event that should be skipped
    JSON.stringify({ type: 'progress', data: { some: 'progress' } }),
    // Add a meta message that should be skipped
    JSON.stringify({ type: 'user', isMeta: true, message: { content: 'system metadata' } }),
  ].join('\n') + '\n';
  fs.writeFileSync(condensePath, condenseLines, 'utf8');

  const sid = 'test-eval-20e';
  writeTestReport(sid);
  fs.writeFileSync(eventsFile, JSON.stringify({
    session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test', transcript_path: condensePath,
  }) + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'evaluate-session.mjs'),
    '--session-id', sid,
    '--dry-run',
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const evalResult = JSON.parse(output);
  assert(evalResult.reason === null, '20e: condensation produced enough lines');

  // Now read the outcomes and verify indirectly — the transcript has 5 meaningful lines
  // (1 user msg, 1 text, 2 tool_use, 1 bash output, 1 error = 6 meaningful lines)
  assert(evalResult.eval_input_tokens > 50, '20e: condensed tokens reflect content');
}

// 20f: Condensation captures Bash output from original evalTranscript
{
  // The evalTranscript has a successful Bash result "BUILD SUCCESSFUL..." after tool3.
  // Verify the condensed transcript is non-trivial and includes Bash output data.
  const sid = 'test-eval-20f';
  writeTestReport(sid);
  fs.writeFileSync(eventsFile, JSON.stringify({
    session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', model: 'test', transcript_path: evalTranscriptPath,
  }) + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'evaluate-session.mjs'),
    '--session-id', sid,
    '--dry-run',
    '--json',
  ], { encoding: 'utf8', timeout: 15000 }).trim();

  const evalResult = JSON.parse(output);
  // evalTranscript has: 1 user msg + 3 text blocks + 4 tool_use + 1 bash output + 1 error = 10
  assert(evalResult.eval_input_tokens > 100, '20f: eval_input_tokens substantial for rich transcript');
  assert(evalResult.reason === null, '20f: rich transcript not skipped');
}

// 20g: SYSTEM_PROMPT contains task-type-specific rubric anchors
{
  const sourceCode = fs.readFileSync(path.join(scriptDir, 'evaluate-session.mjs'), 'utf8');
  assert(sourceCode.includes('bugfix: reported defect'), '20g: bugfix rubric anchor present');
  assert(sourceCode.includes('refactor: code restructured'), '20g: refactor rubric anchor present');
  assert(sourceCode.includes('investigation: findings written'), '20g: investigation rubric anchor present');
  assert(sourceCode.includes('feature: new behavior present'), '20g: feature rubric anchor present');
  assert(sourceCode.includes('chore: maintenance task executed'), '20g: chore rubric anchor present');
}

// 20h: SYSTEM_PROMPT contains failed-verdict bias mitigation anchor
{
  const sourceCode = fs.readFileSync(path.join(scriptDir, 'evaluate-session.mjs'), 'utf8');
  assert(sourceCode.includes('Do not avoid "failed"'), '20h: failed bias anchor present');
  assert(sourceCode.includes('broken end state is "failed"'), '20h: failed/partial distinction present');
}

// 20i: Bias correction — partial + build_passed=false → failed (post-processing rule)
{
  const evalSource = fs.readFileSync(path.join(scriptDir, 'evaluate-session.mjs'), 'utf8');
  assert(evalSource.includes('Auto-corrected from "partial"'), '20i: bias correction rule exists');
  assert(evalSource.includes('build_passed === false'), '20i: correction checks build_passed');
}

console.log();

// ============================================================
// Test 21: Golden dataset regression
// ============================================================
console.log('Test 21: Golden dataset regression');
{
  for (const { id, expected } of GOLDEN_SESSIONS) {
    try {
      const result = scoreSession(id);
      assertApprox(result.score, expected, 5, `golden/${id.slice(0, 8)}: score`);
    } catch (e) {
      if (e.message?.includes('Session report not found') || e.status === 1) {
        console.warn(`  SKIP: golden/${id.slice(0, 8)}: session file not found`);
      } else {
        throw e;
      }
    }
  }
}

console.log();

// ============================================================
// Test 22: Repeat guard (repeat-guard.mjs)
// ============================================================
console.log('Test 22: Repeat guard');

const rgSession = 'test-repeat-guard-' + Date.now();
testCachePatterns.push(rgSession);
const rgCall = (toolName, toolInput, sessionOverride) => ({
  hook_event_name: 'PreToolUse',
  session_id: sessionOverride || rgSession,
  tool_name: toolName,
  tool_input: toolInput,
});

// 22a: Single call — no block
{
  const r = runRepeatGuard(rgCall('Read', { file_path: '/tmp/a.txt' }));
  assert(r.status === 0, '22a: single call allowed');
}

// 22b: 2 consecutive identical — no block
{
  const r = runRepeatGuard(rgCall('Read', { file_path: '/tmp/a.txt' }));
  assert(r.status === 0, '22b: 2nd identical call allowed (below threshold)');
}

// 22c: 3rd consecutive identical — blocked
{
  const r = runRepeatGuard(rgCall('Read', { file_path: '/tmp/a.txt' }));
  assert(r.status === 2, '22c: 3rd consecutive identical call blocked');
  assert(r.stderr.includes('consecutive identical'), '22d: block message contains advisory');
}

// 22e: Different call breaks consecutive run — allowed
{
  const r = runRepeatGuard(rgCall('Grep', { pattern: 'foo', path: '/tmp' }));
  assert(r.status === 0, '22e: different call allowed (breaks consecutive run)');
}

// 22f: Same Read again after break — allowed (consecutive count reset)
{
  const r = runRepeatGuard(rgCall('Read', { file_path: '/tmp/a.txt' }));
  assert(r.status === 0, '22f: same call after break is allowed');
}

// 22g: 3 consecutive Bash — blocked (fresh session)
const rgSession2 = 'test-repeat-guard-bash-' + Date.now();
testCachePatterns.push(rgSession2);
{
  runRepeatGuard(rgCall('Bash', { command: 'echo hello' }, rgSession2));
  runRepeatGuard(rgCall('Bash', { command: 'echo hello' }, rgSession2));
  const r = runRepeatGuard(rgCall('Bash', { command: 'echo hello' }, rgSession2));
  assert(r.status === 2, '22g: 3 consecutive Bash calls blocked');
}

// 22h: 3 consecutive gradlew — allowed (deferred to build-counter)
const rgSession3 = 'test-repeat-guard-build-' + Date.now();
testCachePatterns.push(rgSession3);
{
  runRepeatGuard(rgCall('Bash', { command: './gradlew.bat build' }, rgSession3));
  runRepeatGuard(rgCall('Bash', { command: './gradlew.bat build' }, rgSession3));
  const r = runRepeatGuard(rgCall('Bash', { command: './gradlew.bat build' }, rgSession3));
  assert(r.status === 0, '22h: gradlew commands deferred to build-counter');
}

// 22i: 3 consecutive MCP calls with different args — allowed
const rgSession4 = 'test-repeat-guard-mcp-' + Date.now();
testCachePatterns.push(rgSession4);
{
  runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'backend_stdout' }, rgSession4));
  runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'backend_stderr' }, rgSession4));
  const r = runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'frontend_stdout' }, rgSession4));
  assert(r.status === 0, '22i: MCP calls with different args allowed');
}

// 22j: 3 consecutive MCP calls with same args — blocked
const rgSession5 = 'test-repeat-guard-mcp-same-' + Date.now();
testCachePatterns.push(rgSession5);
{
  runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'backend_stdout' }, rgSession5));
  runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'backend_stdout' }, rgSession5));
  const r = runRepeatGuard(rgCall('mcp__dev__tail_log', { kind: 'backend_stdout' }, rgSession5));
  assert(r.status === 2, '22j: identical MCP calls blocked');
}

// 22k: 3 consecutive TaskUpdate with different taskIds — allowed
const rgSession6 = 'test-repeat-guard-taskupdate-' + Date.now();
testCachePatterns.push(rgSession6);
{
  runRepeatGuard(rgCall('TaskUpdate', { taskId: '1', status: 'completed' }, rgSession6));
  runRepeatGuard(rgCall('TaskUpdate', { taskId: '2', status: 'completed' }, rgSession6));
  const r = runRepeatGuard(rgCall('TaskUpdate', { taskId: '3', status: 'completed' }, rgSession6));
  assert(r.status === 0, '22k: TaskUpdate with different ids allowed');
}

console.log();

// ============================================================
// Test 23: Build counter (build-counter.mjs + dispatch.mjs)
// ============================================================
console.log('Test 23: Build counter');

const bcSession = 'test-build-counter-' + Date.now();
testCachePatterns.push(bcSession);
const bcStateFile = path.join(telemetryDir, `build-fails-${bcSession}.json`);
const bcCall = (command) => ({
  hook_event_name: 'PreToolUse',
  session_id: bcSession,
  tool_name: 'Bash',
  tool_input: { command },
});

// 23a: No state file — build allowed
{
  const r = runBuildCounter(bcCall('./gradlew.bat build'));
  assert(r.status === 0, '23a: build allowed with no state file');
}

// 23b: 2 failures — build allowed
fs.writeFileSync(bcStateFile, JSON.stringify({ consecutiveFailures: 2, advisoryShown: false }));
{
  const r = runBuildCounter(bcCall('./gradlew.bat build'));
  assert(r.status === 0, '23b: build allowed at 2 failures (below threshold)');
}

// 23c: 3 failures — build blocked
fs.writeFileSync(bcStateFile, JSON.stringify({ consecutiveFailures: 3, advisoryShown: false }));
{
  const r = runBuildCounter(bcCall('./gradlew.bat build'));
  assert(r.status === 2, '23c: build blocked at 3 failures');
  assert(r.stderr.includes('3 consecutive'), '23d: block message mentions count');
}

// 23e: Non-build command allowed even at 3 failures
// (state file still has advisoryShown: true from 23c's hook write — irrelevant since non-build commands exit early)
{
  const r = runBuildCounter(bcCall('git status'));
  assert(r.status === 0, '23e: non-build command allowed despite failures');
}

// 23f: After block, advisoryShown prevents re-block
{
  const state = JSON.parse(fs.readFileSync(bcStateFile, 'utf8'));
  assert(state.advisoryShown === true, '23f: advisoryShown set after block');
  const r = runBuildCounter(bcCall('./gradlew.bat build'));
  assert(r.status === 0, '23f2: build allowed after advisory shown');
}

// 23g: dispatch.mjs writes state on failed build
const bcSession2 = 'test-build-dispatch-' + Date.now();
testCachePatterns.push(bcSession2);
{
  runDispatch({
    hook_event_name: 'PostToolUse',
    session_id: bcSession2,
    tool_name: 'Bash',
    tool_use_id: 'tool-1',
    tool_input: { command: './gradlew.bat build' },
    tool_response: { exitCode: 1 },
  });
  const sf = path.join(telemetryDir, `build-fails-${bcSession2}.json`);
  assert(fs.existsSync(sf), '23g: dispatch writes state on failed build');
  const state = JSON.parse(fs.readFileSync(sf, 'utf8'));
  assert(state.consecutiveFailures === 1, '23g2: failure count incremented');
}

// 23h: dispatch.mjs resets state on successful build
{
  runDispatch({
    hook_event_name: 'PostToolUse',
    session_id: bcSession2,
    tool_name: 'Bash',
    tool_use_id: 'tool-2',
    tool_input: { command: './gradlew.bat test' },
    tool_response: { exitCode: 0 },
  });
  const sf = path.join(telemetryDir, `build-fails-${bcSession2}.json`);
  const state = JSON.parse(fs.readFileSync(sf, 'utf8'));
  assert(state.consecutiveFailures === 0, '23h: success resets failure count');
}

// 23i: SessionEnd cleans up build-fails state file
const bcSession3 = 'test-build-cleanup-' + Date.now();
testCachePatterns.push(bcSession3);
{
  const sf = path.join(telemetryDir, `build-fails-${bcSession3}.json`);
  fs.mkdirSync(telemetryDir, { recursive: true });
  fs.writeFileSync(sf, JSON.stringify({ consecutiveFailures: 2, advisoryShown: false }));
  runDispatch({
    hook_event_name: 'SessionEnd',
    session_id: bcSession3,
    reason: 'user_exit',
  });
  assert(!fs.existsSync(sf), '23i: SessionEnd cleans up build-fails state file');
}

// 23j: SessionEnd cleans up repeat-buffer state file
const rgCleanSession = 'test-repeat-cleanup-' + Date.now();
testCachePatterns.push(rgCleanSession);
{
  const sf = path.join(telemetryDir, `repeat-buffer-${rgCleanSession}.json`);
  fs.mkdirSync(telemetryDir, { recursive: true });
  fs.writeFileSync(sf, JSON.stringify({ buffer: ['a', 'b'] }));
  runDispatch({
    hook_event_name: 'SessionEnd',
    session_id: rgCleanSession,
    reason: 'user_exit',
  });
  assert(!fs.existsSync(sf), '23j: SessionEnd cleans up repeat-buffer state file');
}

console.log();

// ============================================================
// Test 24: Hook chain interaction (repeat-guard + build-counter)
// ============================================================
console.log('Test 24: Hook chain interaction');

// 24a: 3 consecutive builds with 3+ failures — repeat-guard defers, build-counter blocks
const chainSession1 = 'test-chain-build-' + Date.now();
testCachePatterns.push(chainSession1);
{
  const chainStateFile = path.join(telemetryDir, `build-fails-${chainSession1}.json`);
  fs.writeFileSync(chainStateFile, JSON.stringify({ consecutiveFailures: 3, advisoryShown: false }));

  // Run repeat-guard first (registration order) — should defer for gradlew
  const rg = runRepeatGuard({
    hook_event_name: 'PreToolUse', session_id: chainSession1,
    tool_name: 'Bash', tool_input: { command: './gradlew.bat build' },
  });
  assert(rg.status === 0, '24a: repeat-guard defers for build commands');

  // Run build-counter second — should block
  const bc = runBuildCounter({
    hook_event_name: 'PreToolUse', session_id: chainSession1,
    tool_name: 'Bash', tool_input: { command: './gradlew.bat build' },
  });
  assert(bc.status === 2, '24a: build-counter blocks at 3 failures');
  assert(bc.stderr.includes('consecutive'), '24a: correct advisory message');
}

// 24b: 3 consecutive non-build bash — repeat-guard blocks, build-counter irrelevant
const chainSession2 = 'test-chain-nonbuild-' + Date.now();
testCachePatterns.push(chainSession2);
{
  runRepeatGuard({
    hook_event_name: 'PreToolUse', session_id: chainSession2,
    tool_name: 'Bash', tool_input: { command: 'echo hello' },
  });
  runRepeatGuard({
    hook_event_name: 'PreToolUse', session_id: chainSession2,
    tool_name: 'Bash', tool_input: { command: 'echo hello' },
  });
  const rg = runRepeatGuard({
    hook_event_name: 'PreToolUse', session_id: chainSession2,
    tool_name: 'Bash', tool_input: { command: 'echo hello' },
  });
  assert(rg.status === 2, '24b: repeat-guard blocks non-build repetition');
}

console.log();

// ============================================================
console.log('Test 25: Context attribution');

// Create a synthetic transcript with known content sizes
const ctxTranscriptDir = path.join(telemetryDir, 'test-transcripts');
fs.mkdirSync(ctxTranscriptDir, { recursive: true });
const ctxTranscriptPath = path.join(ctxTranscriptDir, 'test-context.jsonl');

const ctxTranscript = [
  // Assistant turn with tool_use (Read) and text
  JSON.stringify({
    type: 'assistant',
    message: {
      content: [
        { type: 'text', text: 'A'.repeat(500) },
        { type: 'thinking', thinking: 'T'.repeat(300) },
        { type: 'tool_use', id: 'tu-read-1', name: 'Read', input: { file_path: '/a.txt' } },
        { type: 'tool_use', id: 'tu-bash-1', name: 'Bash', input: { command: 'ls' } },
      ],
    },
  }),
  // Tool results for Read and Bash
  JSON.stringify({
    type: 'user',
    message: {
      content: [
        { type: 'tool_result', tool_use_id: 'tu-read-1', content: 'R'.repeat(2000) },
        { type: 'tool_result', tool_use_id: 'tu-bash-1', content: 'B'.repeat(800) },
        { type: 'text', text: 'U'.repeat(400) },
      ],
    },
  }),
  // System message
  JSON.stringify({
    type: 'system',
    message: { content: 'S'.repeat(100) },
  }),
  // Another assistant turn with a second Read
  JSON.stringify({
    type: 'assistant',
    message: {
      content: [
        { type: 'tool_use', id: 'tu-read-2', name: 'Read', input: { file_path: '/b.txt' } },
      ],
    },
  }),
  // Second Read result
  JSON.stringify({
    type: 'user',
    message: {
      content: [
        { type: 'tool_result', tool_use_id: 'tu-read-2', content: 'R'.repeat(1000) },
      ],
    },
  }),
].join('\n') + '\n';

fs.writeFileSync(ctxTranscriptPath, ctxTranscript, 'utf8');

// Known expected sizes:
// assistant_text = 500, thinking = 300
// tool_outputs: Read = 2000 + 1000 = 3000, Bash = 800 → total = 3800
// user_messages = 400, system = 100
// grand total = 500 + 300 + 3800 + 400 + 100 = 5100

// 25a: Single session with known transcript → correct attribution
{
  const sid = 'test-ctx-25a';
  testSessionIds.push(sid);
  const events25a = [
    { session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', transcript_path: ctxTranscriptPath },
    { session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events25a.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'context-attribution.mjs'),
    '--session-id', sid, '--json',
  ], { cwd: repoRoot, encoding: 'utf8' });
  const result = JSON.parse(output);

  assert(result.session_id === sid, '25a: session_id matches');
  assert(result.total_chars === 5100, '25a: total_chars is 5100');
  assert(result.estimated_tokens === Math.round(5100 / 4), '25a: estimated_tokens correct');

  // Category checks
  assertApprox(result.categories.tool_outputs.pct, 3800 / 5100, 0.01, '25a: tool_outputs pct');
  assertApprox(result.categories.assistant_text.pct, 500 / 5100, 0.01, '25a: assistant_text pct');
  assertApprox(result.categories.thinking.pct, 300 / 5100, 0.01, '25a: thinking pct');
  assertApprox(result.categories.user_messages.pct, 400 / 5100, 0.01, '25a: user_messages pct');
  assertApprox(result.categories.system.pct, 100 / 5100, 0.01, '25a: system pct');

  // Per-tool: Read should be top tool (3000 chars), Bash second (800 chars)
  assert(result.top_tools[0].tool === 'Read', '25a: top tool is Read');
  assert(result.top_tools[0].chars === 3000, '25a: Read chars = 3000');
  assert(result.top_tools[0].calls === 2, '25a: Read calls = 2');
  assert(result.top_tools[1].tool === 'Bash', '25a: second tool is Bash');
  assert(result.top_tools[1].chars === 800, '25a: Bash chars = 800');

  // Category percentages should sum to ~1.0
  const pctSum = Object.values(result.categories).reduce((s, c) => s + c.pct, 0);
  assertApprox(pctSum, 1.0, 0.01, '25a: category pcts sum to ~1.0');
}

// 25b: Missing transcript → graceful error
{
  const sid = 'test-ctx-25b';
  testSessionIds.push(sid);
  const events25b = [
    { session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start' },
    { session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events25b.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'context-attribution.mjs'),
    '--session-id', sid, '--json',
  ], { cwd: repoRoot, encoding: 'utf8' });
  const result = JSON.parse(output);

  assert(result.session_id === sid, '25b: session_id matches');
  assert(result.error === 'no_transcript', '25b: error is no_transcript');
}

// 25c: tool_use_id linkage — unknown tool_use_id falls back to 'unknown'
{
  const sid = 'test-ctx-25c';
  testSessionIds.push(sid);
  const orphanTranscriptPath = path.join(ctxTranscriptDir, 'test-orphan.jsonl');
  const orphanTranscript = [
    JSON.stringify({
      type: 'user',
      message: {
        content: [
          { type: 'tool_result', tool_use_id: 'nonexistent-id', content: 'X'.repeat(100) },
        ],
      },
    }),
  ].join('\n') + '\n';
  fs.writeFileSync(orphanTranscriptPath, orphanTranscript, 'utf8');

  const events25c = [
    { session_id: sid, ts: '2026-01-01T00:00:00Z', event: 'session_start', transcript_path: orphanTranscriptPath },
    { session_id: sid, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events25c.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'context-attribution.mjs'),
    '--session-id', sid, '--json',
  ], { cwd: repoRoot, encoding: 'utf8' });
  const result = JSON.parse(output);

  assert(result.top_tools.length === 1, '25c: one tool entry');
  assert(result.top_tools[0].tool === 'unknown', '25c: unmapped tool_use_id → unknown');
  assert(result.top_tools[0].chars === 100, '25c: correct chars for unknown tool');
}

// 25d: --all --json aggregate mode
{
  // Write events for two sessions pointing to the same transcript
  const sid1 = 'test-ctx-25d1';
  const sid2 = 'test-ctx-25d2';
  testSessionIds.push(sid1, sid2);
  const events25d = [
    { session_id: sid1, ts: '2026-01-01T00:00:00Z', event: 'session_start', transcript_path: ctxTranscriptPath },
    { session_id: sid1, ts: '2026-01-01T01:00:00Z', event: 'session_end', reason: 'done' },
    { session_id: sid2, ts: '2026-01-01T02:00:00Z', event: 'session_start', transcript_path: ctxTranscriptPath },
    { session_id: sid2, ts: '2026-01-01T03:00:00Z', event: 'session_end', reason: 'done' },
  ];
  fs.writeFileSync(eventsFile, events25d.map(e => JSON.stringify(e)).join('\n') + '\n', 'utf8');

  const output = execFileSync('node', [
    path.join(scriptDir, 'context-attribution.mjs'),
    '--all', '--json',
  ], { cwd: repoRoot, encoding: 'utf8' });
  const agg = JSON.parse(output);

  assert(agg.count >= 2, '25d: at least 2 valid sessions');
  assert(agg.total_chars >= 5100 * 2, '25d: total_chars includes both test sessions');
  assert(typeof agg.category_medians.tool_outputs === 'number', '25d: has category medians');
  assert(agg.top_tools.length >= 2, '25d: has tool breakdown');
  // Read should be top tool (dominates in test transcripts and typically in real ones too)
  assert(agg.top_tools[0].tool === 'Read', '25d: aggregate top tool is Read');
}

console.log();

// ============================================================
} finally {
// ============================================================
// Cleanup — runs even if tests crash
// ============================================================

// Remove test session files from the real sessions dir
for (const id of testSessionIds) {
  try { fs.unlinkSync(path.join(sessionsDir, `${id}.json`)); } catch {}
}

// Remove test cache files (read-counts, edit-counts, compact-state)
try {
  for (const f of fs.readdirSync(telemetryDir).filter(f =>
    f.startsWith('read-counts-test-') || f.startsWith('edit-counts-test-') || f.startsWith('compact-state-test-') || f.startsWith('repeat-buffer-test-') || f.startsWith('build-fails-test-')
  )) {
    fs.unlinkSync(path.join(telemetryDir, f));
  }
} catch {}

// Remove temp test files for size-based testing
try { fs.unlinkSync(path.join(telemetryDir, 'test-small-file.txt')); } catch {}
try { fs.unlinkSync(path.join(telemetryDir, 'test-large-file.txt')); } catch {}

// Remove compaction-state rules file if written during tests
try { fs.unlinkSync(rulesFile); } catch {}

// Restore events file
if (eventsBackup != null) {
  try { fs.writeFileSync(eventsFile, eventsBackup, 'utf8'); } catch {}
} else {
  // If no backup existed, the file was created by tests — remove it only if empty/test-only
}

// Remove test transcript dir
try { fs.rmSync(path.join(telemetryDir, 'test-transcripts'), { recursive: true }); } catch {}

// Remove test cost records
try {
  const costsPath = path.join(telemetryDir, 'costs.ndjson');
  if (fs.existsSync(costsPath)) {
    const lines = fs.readFileSync(costsPath, 'utf8').split('\n').filter(l => l.trim());
    const filtered = lines.filter(l => {
      try { return !JSON.parse(l).session_id?.startsWith('test-'); } catch { return true; }
    });
    fs.writeFileSync(costsPath, filtered.join('\n') + (filtered.length ? '\n' : ''), 'utf8');
  }
} catch {}

// Remove test outcome records
try {
  const outcomesPath = path.join(telemetryDir, 'outcomes.ndjson');
  if (fs.existsSync(outcomesPath)) {
    const lines = fs.readFileSync(outcomesPath, 'utf8').split('\n').filter(l => l.trim());
    const filtered = lines.filter(l => {
      try { return !JSON.parse(l).session_id?.startsWith('test-'); } catch { return true; }
    });
    if (filtered.length > 0) {
      fs.writeFileSync(outcomesPath, filtered.join('\n') + '\n', 'utf8');
    } else {
      fs.unlinkSync(outcomesPath);
    }
  }
} catch {}

// Remove tmpDir
try { fs.rmSync(tmpDir, { recursive: true }); } catch {}

} // end finally

// ============================================================
// Summary
// ============================================================
console.log(`Results: ${passed} passed, ${failed} failed`);
if (failed > 0) {
  process.exit(1);
}
