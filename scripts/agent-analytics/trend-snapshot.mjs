#!/usr/bin/env node
/**
 * trend-snapshot.mjs — tempdoc 908 §5.2, THE HIGHER-PRIORITY HALF of this
 * tempdoc (908 §6.0 "Chunk order note").
 *
 * WHY THIS EXISTS. Claude Code rotates transcripts at ~30 days
 * (`cleanupPeriodDays` unset -> default 30, 908 §1/§4.3) — the ONLY store every
 * reader under this directory reads. Every week that passes without this writer
 * running destroys a week of the only history that can judge whether a lever
 * (model routing, spawn-length bounding, `delegate-by-default` itself, 908 §4.4)
 * worked. This appends the current window's bucket AGGREGATES — never raw
 * session content — to `tmp/agent-telemetry/efficiency-trend.ndjson`, one line
 * per `(bucket, harness)`, so `efficiency-trend.mjs` can still print a bucket
 * after its source transcripts are gone (908 §4.3's cheapest fix, "(a)").
 *
 * AGGREGATES ONLY (908 §5.2). A record carries call counts, token/cost
 * percentiles and sums, and a spawn-length histogram — never prompt text, file
 * paths, or session content. `findContentLeaks` enforces this by TEST, not just
 * by convention (a generic recursive string-shape checker, precedent:
 * `lib/ledger/boundary-check.mjs`'s pure checker + crafted-violation test).
 *
 * IDEMPOTENT (908 §5.2/§6.2): re-running for a bucket REPLACES that bucket's
 * line (keyed on `bucket|harness`), never appends a duplicate — `upsertRecords`
 * is the pure merge function this guarantees.
 *
 * Reuses `efficiency-trend.mjs`'s exported `build*` functions and bucketing —
 * this is a second CONSUMER of that module's aggregation, not a second
 * aggregation pipeline that could silently drift from the live reader's numbers.
 *
 * The snapshot path resolves to the MAIN checkout (`lib/hook-base.mjs`'s
 * `mainRepoRoot`, tempdoc 606) even when run from a worktree — the same
 * "one shared history regardless of which worktree touches it" contract
 * `baseline-economics.mjs`'s `resolveDefaultMergesPath` uses for
 * `session-merges.ndjson`, so a worktree teardown never takes the snapshot
 * history down with it.
 *
 * Flags: `--by week|day` (default `week`), `--since <ISO>` (default trailing 60
 * days), `--until <ISO>`, `--harness claude-code|codex-cli|all` (default `all` —
 * writes one record per selected harness per bucket), `--long-spawn-calls <n>`
 * (default 120), `--path <file>` (override, mainly for tests), `--json`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listCalls } from './lib/ledger/index.mjs';
import { atomicWriteFileSync, mainRepoRoot } from './lib/hook-base.mjs';
import {
  DAY_MS, VALID_HARNESS_ARGS, VALID_BY, DEFAULT_LONG_SPAWN_CALLS,
  buildLeadingIndicators, buildBucketedSpawnRows, buildSpawnTail,
} from './efficiency-trend.mjs';

const SNAPSHOT_RELATIVE = path.join('tmp', 'agent-telemetry', 'efficiency-trend.ndjson');
const RUN_LENGTH_BUCKETS = [[0, 10], [10, 30], [30, 60], [60, 120], [120, 250], [250, 500], [500, Infinity]];
const MAX_STRING_LEN = 64;

export function resolveDefaultSnapshotPath() {
  return path.join(mainRepoRoot, SNAPSHOT_RELATIVE);
}

// --- histogram -----------------------------------------------------------------

/** `{ "0-10": n, ..., "500+": n }` — a shape, never a per-spawn list (aggregates only). */
export function buildRunLengthHistogram(spawnRows) {
  const hist = {};
  for (const [lo, hi] of RUN_LENGTH_BUCKETS) {
    const label = hi === Infinity ? `${lo}+` : `${lo}-${hi}`;
    hist[label] = spawnRows.filter((r) => r.calls >= lo && r.calls < hi).length;
  }
  return hist;
}

// --- record shape ----------------------------------------------------------------

export function recordKey(record) {
  return `${record.bucket}|${record.harness}`;
}

/**
 * Build ONE snapshot record for one `(bucket, harness)`. `leadingRow`/
 * `spawnTailRow` may be `undefined` (a bucket with, say, leading-indicator data
 * but zero spawns that week) — the corresponding sub-object is `null`, never a
 * fabricated zero-row.
 */
export function buildSnapshotRecord({
  bucket, harness, by, generatedAtMs, leadingRow, spawnTailRow, runLengthHistogram,
}) {
  return {
    bucket,
    harness,
    by,
    generatedAtMs,
    leading: leadingRow ? {
      calls: leadingRow.calls,
      costUsd: leadingRow.costUsd,
      unpricedCalls: leadingRow.unpricedCalls,
      ctxOut: leadingRow.ctxOut,
      costPerMOut: leadingRow.costPerMOut,
      mainP50Ctx: leadingRow.mainP50Ctx,
      subP50Ctx: leadingRow.subP50Ctx,
      subCostSharePct: leadingRow.subCostSharePct,
    } : null,
    spawnTail: spawnTailRow ? {
      spawns: spawnTailRow.spawns,
      medCalls: spawnTailRow.medCalls,
      medPeakCtx: spawnTailRow.medPeakCtx,
      costPerSpawn: spawnTailRow.costPerSpawn,
      longSpawns: spawnTailRow.longSpawns,
      longCostSharePct: spawnTailRow.longCostSharePct,
      unpricedCalls: spawnTailRow.unpricedCalls,
      runLengthHistogram: runLengthHistogram ?? null,
    } : null,
  };
}

/**
 * Recursively walk `value` and report any string longer than `maxLen` or
 * containing a `/` (a path-like value) — the aggregates-only rule (908 §5.2),
 * enforced by a checker a test can run against BOTH the real builder output
 * and a crafted violation shape (same precedent as `boundary-check.mjs`).
 */
export function findContentLeaks(value, maxLen = MAX_STRING_LEN, pathSoFar = '$') {
  const leaks = [];
  if (typeof value === 'string') {
    if (value.length > maxLen) leaks.push({ path: pathSoFar, reason: `string longer than ${maxLen} chars` });
    else if (value.includes('/')) leaks.push({ path: pathSoFar, reason: 'contains "/" (path-like)' });
    return leaks;
  }
  if (Array.isArray(value)) {
    value.forEach((v, i) => leaks.push(...findContentLeaks(v, maxLen, `${pathSoFar}[${i}]`)));
    return leaks;
  }
  if (value && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) leaks.push(...findContentLeaks(v, maxLen, `${pathSoFar}.${k}`));
  }
  return leaks;
}

// --- file I/O --------------------------------------------------------------------

export function readSnapshotFile(filePath) {
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return [];
  }
  const rows = [];
  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    try { rows.push(JSON.parse(line)); } catch { /* skip malformed line */ }
  }
  return rows;
}

/** Replace any existing record sharing a `newRecords` entry's key; never append a duplicate. */
export function upsertRecords(existingRecords, newRecords) {
  const newKeys = new Set(newRecords.map(recordKey));
  const kept = existingRecords.filter((r) => !newKeys.has(recordKey(r)));
  const merged = [...kept, ...newRecords];
  merged.sort((a, b) => recordKey(a).localeCompare(recordKey(b)));
  return merged;
}

export function writeSnapshotFile(filePath, records) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  const body = records.map((r) => JSON.stringify(r)).join('\n') + (records.length ? '\n' : '');
  atomicWriteFileSync(filePath, body);
}

// --- compute (ledger I/O) ---------------------------------------------------------

/** One record per `(bucket, harness)` for every harness in `harnesses`, over the window. */
export function computeSnapshotRecords({ harnesses, sinceMs, untilMs, by, longSpawnCalls, generatedAtMs = Date.now() }) {
  const records = [];
  for (const harness of harnesses) {
    const { calls } = listCalls({ harnesses: [harness], sinceMs, untilMs });
    const nonSynthetic = calls.filter((c) => !c.synthetic);
    const { rows: leadingRows } = buildLeadingIndicators(nonSynthetic, by);

    const spawnCalls = harness === 'claude-code' ? nonSynthetic.filter((c) => c.lineage.kind !== 'main') : [];
    const spawnRows = buildBucketedSpawnRows(spawnCalls, by);
    const spawnTailRows = buildSpawnTail(spawnRows, longSpawnCalls);

    const leadingByBucket = new Map(leadingRows.map((r) => [r.bucket, r]));
    const spawnTailByBucket = new Map(spawnTailRows.map((r) => [r.bucket, r]));
    const spawnsByBucket = new Map();
    for (const r of spawnRows) {
      if (!spawnsByBucket.has(r.bucket)) spawnsByBucket.set(r.bucket, []);
      spawnsByBucket.get(r.bucket).push(r);
    }

    const buckets = new Set([...leadingByBucket.keys(), ...spawnTailByBucket.keys()]);
    for (const bucket of buckets) {
      const spawnTailRow = spawnTailByBucket.get(bucket);
      records.push(buildSnapshotRecord({
        bucket, harness, by, generatedAtMs,
        leadingRow: leadingByBucket.get(bucket),
        spawnTailRow,
        runLengthHistogram: spawnTailRow ? buildRunLengthHistogram(spawnsByBucket.get(bucket) ?? []) : null,
      }));
    }
  }
  return records;
}

// --- CLI ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = {
    by: 'week', since: null, until: null, harness: 'all',
    longSpawnCalls: DEFAULT_LONG_SPAWN_CALLS, path: null, json: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--by') opts.by = argv[++i];
    else if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--harness') opts.harness = argv[++i];
    else if (a === '--long-spawn-calls') opts.longSpawnCalls = Number(argv[++i]);
    else if (a === '--path') opts.path = argv[++i];
    else if (a === '--json') opts.json = true;
  }
  return opts;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!VALID_HARNESS_ARGS.includes(opts.harness)) {
    console.error(`trend-snapshot: unknown --harness "${opts.harness}" (expected one of: ${VALID_HARNESS_ARGS.join(', ')})`);
    process.exit(2);
  }
  if (!VALID_BY.includes(opts.by)) {
    console.error(`trend-snapshot: unknown --by "${opts.by}" (expected one of: ${VALID_BY.join(', ')})`);
    process.exit(2);
  }
  const harnesses = opts.harness === 'all' ? ['claude-code', 'codex-cli'] : [opts.harness];
  const sinceMs = opts.since ? Date.parse(opts.since) : Date.now() - 60 * DAY_MS;
  const untilMs = opts.until ? Date.parse(opts.until) : null;
  const filePath = opts.path ?? resolveDefaultSnapshotPath();

  const newRecords = computeSnapshotRecords({ harnesses, sinceMs, untilMs, by: opts.by, longSpawnCalls: opts.longSpawnCalls });

  for (const r of newRecords) {
    const leaks = findContentLeaks(r);
    if (leaks.length) {
      console.error(`trend-snapshot: refusing to write a record with a content leak: ${JSON.stringify(leaks)}`);
      process.exit(1);
    }
  }

  const existing = readSnapshotFile(filePath);
  const merged = upsertRecords(existing, newRecords);
  writeSnapshotFile(filePath, merged);

  if (opts.json) {
    console.log(JSON.stringify({ path: filePath, wroteRecords: newRecords.length, totalRecords: merged.length }, null, 2));
    return;
  }
  console.log(`trend-snapshot: upserted ${newRecords.length} record(s) for ${harnesses.join(',')} `
    + `(--by ${opts.by}) into ${filePath} — ${merged.length} total record(s).`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
