#!/usr/bin/env node
/**
 * spawn-economics.mjs — tempdoc 886 §12 PR 2.
 *
 * WHY THIS EXISTS. Tempdoc 886 §2.3 found that spawn LENGTH, not spawn
 * count, is the multiplier: spawns with >=120 calls are 20% of spawns and
 * 72% of subagent cost. No existing reader joined a spawn's lineage
 * (requested vs actual model, agentType, brief) to its cost — this
 * productionises `tmp/tokeff/deep3.mjs` (886 §1), which first measured it.
 *
 * Every Claude Code `spawn`/`fork` lineage `Call` already carries its own
 * `lineage.{parentSessionId, agentType, requestedModel, description}`
 * (`lib/ledger/claude-adapter.mjs`, sourced from `subagents/*.meta.json`) —
 * this reader groups those Calls by `sessionId` (one spawn transcript) and
 * adds two things the neutral ledger does not carry: per-call COST (the
 * ledger stores token axes, not dollars) and `firstUserMessageChars` (the
 * first user turn's character count — plain conversation text, which the
 * neutral `Call`/`ToolEvent` record has no axis for, same rationale as
 * `context-residency.mjs`'s compounded-residency section). Read directly off
 * each spawn's own raw transcript file, read-only, capped at the first 50
 * lines. NAMED `firstUserMessageChars`, not "brief length" (independent
 * review, 886 §12 PR 2 NIT 3): the first user turn of a SKILL-invoked
 * subagent is the skill body ("Base directory for this skill: ..."), not an
 * Agent-tool brief, so this axis is a mixed proxy across both call shapes —
 * a real measurement of the first turn, not a clean "brief" concept.
 *
 * CODEX HAS NO PER-SPAWN LINEAGE YET (codex-adapter.mjs: every Call is
 * `lineage.kind: 'main'` — `inter_agent_communication_metadata` is a
 * SESSION-level fact, not a per-call parent edge, 886 §12 PR 1 independent
 * review). So Codex sessions with `session.multiAgent === true` are
 * reported in a SEPARATE "multi-agent sessions" table, one row per whole
 * session rather than per spawn — the honest degrade, not a fabricated
 * per-spawn join.
 *
 * `--since <ISO>` (default trailing 30 days), `--until <ISO>`,
 * `--harness claude-code|codex-cli|all` (default all), `--json`,
 * `--top N` (default 20).
 */

import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { listCalls } from './lib/ledger/index.mjs';
import { findPricing, round } from './lib/transcript-cost.mjs';
import { discoverProjectDirs, listSubagentPaths, DEFAULT_PROJECTS_ROOT } from './lib/transcript-store.mjs';

const DAY_MS = 24 * 60 * 60 * 1000;
const VALID_HARNESS_ARGS = ['claude-code', 'codex-cli', 'all'];
const RUN_LENGTH_BUCKETS = [[0, 10], [10, 30], [30, 60], [60, 120], [120, 250], [250, 500], [500, Infinity]];

function usd(n) { return `$${n.toFixed(0)}`; }
function pctFmt(a, b) { return b ? `${((100 * a) / b).toFixed(1)}%` : 'n/a'; }

// --- per-call cost --------------------------------------------------------

/**
 * Price one `Call` using its OWN token axes (fresh/cacheRead/cacheWrite5m/
 * cacheWrite1h/output) at its own model+ts+speed — the same per-component
 * pricing `lib/transcript-cost.mjs`'s `accumulate()` does for a raw usage
 * snapshot, reimplemented here because that function reads a raw `usage`
 * object, not a neutral `Call`. Returns `{usd, priced}` — `priced: false`
 * (not a `$0` cost) when the model has no pricing row, so a caller can sum
 * `unpricedTokens` separately instead of a plausible-looking wrong total.
 */
export function costOfCall(call) {
  const tsMs = call.ts ? Date.parse(call.ts) : null;
  const pricing = findPricing(call.model, Number.isNaN(tsMs) ? null : tsMs, call.speed);
  if (!pricing) return { usd: 0, priced: false };
  const t = call.tokens;
  const costUsd = (
    (t.fresh || 0) * pricing.input
    + (t.cacheRead || 0) * pricing.cache_read
    + (t.cacheWrite5m || 0) * pricing.cache_write_5m
    + (t.cacheWrite1h || 0) * pricing.cache_write_1h
    + (t.output || 0) * pricing.output
  ) / 1e6;
  return { usd: costUsd, priced: true };
}

// --- firstUserMessageChars (raw transcript read) --------------------------

/**
 * Stream `file` far enough to find the FIRST `user` entry whose
 * `message.content` is a plain string (the opening turn, not a
 * `tool_result` array) and return its character length. Capped at 50 lines
 * — the opening turn is always first. Returns `null` when not found or
 * the file is unreadable.
 */
function firstUserMessageLength(file, { maxLines = 50 } = {}) {
  return new Promise((resolve) => {
    let settled = false;
    let lineCount = 0;
    let stream;
    try {
      stream = fs.createReadStream(file, { encoding: 'utf8' });
    } catch {
      resolve(null);
      return;
    }
    const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
      rl.close();
      stream.destroy();
    };
    rl.on('line', (line) => {
      lineCount += 1;
      const t = line.trim();
      if (t) {
        try {
          const e = JSON.parse(t);
          if (e.type === 'user' && typeof e.message?.content === 'string') {
            finish(e.message.content.length);
            return;
          }
        } catch { /* skip malformed line */ }
      }
      if (lineCount >= maxLines) finish(null);
    });
    rl.on('close', () => finish(null));
    stream.on('error', () => finish(null));
  });
}

/**
 * Map every `${parentSessionId}:${subBase}` spawn sessionId (the exact
 * construction `lib/ledger/claude-adapter.mjs`'s `listClaudeCalls` uses) to
 * its transcript file path, by independently walking the same
 * `discoverProjectDirs` + `listSubagentPaths` layout — read-only discovery,
 * no ledger internals reused, so this module never needs an adapter export
 * change to get a file path back out.
 */
export function mapSpawnSessionIdsToFiles({ projectsRoot = DEFAULT_PROJECTS_ROOT, projectFilter } = {}) {
  const map = new Map();
  const dirs = projectFilter ? discoverProjectDirs(projectsRoot, projectFilter) : discoverProjectDirs(projectsRoot);
  for (const dir of dirs) {
    let files;
    try {
      files = fs.readdirSync(dir.path, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      const sessionId = f.name.slice(0, -'.jsonl'.length);
      for (const subPath of listSubagentPaths(dir.path, sessionId)) {
        const subBase = path.basename(subPath, '.jsonl');
        map.set(`${sessionId}:${subBase}`, subPath);
      }
    }
  }
  return map;
}

// --- spawn rows -------------------------------------------------------------

/**
 * Group `calls` (already filtered to non-synthetic, non-main-lineage Claude
 * Calls) by `sessionId` into one row per spawn. `firstUserMessageCharsMap` is
 * `sessionId -> chars|null`, resolved asynchronously up front (see
 * `main()`) since it needs file IO this pure function does not perform.
 */
export function buildSpawnRows(calls, firstUserMessageCharsMap = new Map()) {
  const bySession = new Map();
  for (const c of calls) {
    if (!bySession.has(c.sessionId)) bySession.set(c.sessionId, []);
    bySession.get(c.sessionId).push(c);
  }
  const rows = [];
  for (const [sessionId, arr] of bySession) {
    arr.sort((a, b) => {
      const ta = a.ts ? Date.parse(a.ts) : 0;
      const tb = b.ts ? Date.parse(b.ts) : 0;
      return ta - tb;
    });
    const first = arr[0];
    let costUsd = 0;
    let unpricedTokens = 0;
    let peakContextTokens = 0;
    let actualModel = null;
    for (const c of arr) {
      if (c.contextTokens > peakContextTokens) peakContextTokens = c.contextTokens;
      if (c.model) actualModel = c.model; // last-seen model wins (tmp/tokeff/deep3.mjs method)
      const { usd: callUsd, priced } = costOfCall(c);
      if (priced) costUsd += callUsd;
      else unpricedTokens += c.contextTokens;
    }
    rows.push({
      sessionId,
      harness: first.harness,
      parentSessionId: first.lineage.parentSessionId,
      agentType: first.lineage.agentType ?? '(unset)',
      requestedModel: first.lineage.requestedModel ?? '(unset)',
      actualModel: actualModel ?? '(missing-model)',
      calls: arr.length,
      peakContextTokens,
      costUsd: round(costUsd, 2),
      unpricedTokens,
      firstUserMessageChars: firstUserMessageCharsMap.get(sessionId) ?? null,
      description: first.lineage.description ?? null,
    });
  }
  rows.sort((a, b) => b.costUsd - a.costUsd);
  return rows;
}

/** Codex "multi-agent sessions" — one row per whole session, no per-spawn split. */
export function buildMultiAgentSessionRows(calls, sessions) {
  const multiAgentIds = new Set(sessions.filter((s) => s.harness === 'codex-cli' && s.multiAgent).map((s) => s.sessionId));
  if (multiAgentIds.size === 0) return [];
  const bySession = new Map();
  for (const c of calls) {
    if (!multiAgentIds.has(c.sessionId)) continue;
    if (!bySession.has(c.sessionId)) bySession.set(c.sessionId, []);
    bySession.get(c.sessionId).push(c);
  }
  const rows = [];
  for (const [sessionId, arr] of bySession) {
    let costUsd = 0;
    let unpricedTokens = 0;
    let peakContextTokens = 0;
    let actualModel = null;
    for (const c of arr) {
      if (c.contextTokens > peakContextTokens) peakContextTokens = c.contextTokens;
      if (c.model) actualModel = c.model;
      const { usd: callUsd, priced } = costOfCall(c);
      if (priced) costUsd += callUsd;
      else unpricedTokens += c.contextTokens;
    }
    rows.push({
      sessionId, harness: 'codex-cli', calls: arr.length, peakContextTokens,
      costUsd: round(costUsd, 2), unpricedTokens, actualModel: actualModel ?? '(missing-model)',
    });
  }
  rows.sort((a, b) => b.costUsd - a.costUsd);
  return rows;
}

// --- tables -----------------------------------------------------------------

export function groupRequestedToActual(rows) {
  const groups = new Map();
  for (const r of rows) {
    const key = `${r.requestedModel} -> ${r.actualModel}`;
    let g = groups.get(key);
    if (!g) { g = { key, spawns: 0, costUsd: 0, calls: 0 }; groups.set(key, g); }
    g.spawns += 1;
    g.costUsd += r.costUsd;
    g.calls += r.calls;
  }
  return [...groups.values()].map((g) => ({ ...g, costUsd: round(g.costUsd, 2) })).sort((a, b) => b.costUsd - a.costUsd);
}

export function groupByAgentType(rows) {
  const groups = new Map();
  for (const r of rows) {
    let g = groups.get(r.agentType);
    if (!g) { g = { agentType: r.agentType, spawns: 0, costUsd: 0, calls: 0 }; groups.set(r.agentType, g); }
    g.spawns += 1;
    g.costUsd += r.costUsd;
    g.calls += r.calls;
  }
  return [...groups.values()].map((g) => ({ ...g, costUsd: round(g.costUsd, 2) })).sort((a, b) => b.costUsd - a.costUsd);
}

export function runLengthBuckets(rows) {
  const totalCost = rows.reduce((a, r) => a + r.costUsd, 0);
  return RUN_LENGTH_BUCKETS.map(([lo, hi]) => {
    const g = rows.filter((r) => r.calls >= lo && r.calls < hi);
    const costUsd = g.reduce((a, r) => a + r.costUsd, 0);
    return {
      bucket: hi === Infinity ? `${lo}+` : `${lo}-${hi}`,
      spawns: g.length,
      costUsd: round(costUsd, 2),
      costSharePct: totalCost ? round((100 * costUsd) / totalCost, 1) : 0,
    };
  });
}

export function topByCost(rows, n) {
  return rows.slice(0, n);
}

export function firstUserMessageCharsPercentile(rows, p) {
  const sorted = rows.map((r) => r.firstUserMessageChars).filter((x) => x != null).sort((a, b) => a - b);
  if (!sorted.length) return null;
  return sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
}

// --- CLI ---------------------------------------------------------------------

function parseArgs(argv) {
  const opts = { since: null, until: null, harness: 'all', json: false, top: 20 };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--harness') opts.harness = argv[++i];
    else if (a === '--json') opts.json = true;
    else if (a === '--top') opts.top = Number(argv[++i]);
  }
  return opts;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!VALID_HARNESS_ARGS.includes(opts.harness)) {
    console.error(`spawn-economics: unknown --harness "${opts.harness}" (expected one of: ${VALID_HARNESS_ARGS.join(', ')})`);
    process.exit(2);
  }
  const harnesses = opts.harness === 'all' ? ['claude-code', 'codex-cli'] : [opts.harness];
  const sinceMs = opts.since ? Date.parse(opts.since) : Date.now() - 30 * DAY_MS;
  const untilMs = opts.until ? Date.parse(opts.until) : null;

  const { calls, sessions } = listCalls({ harnesses, sinceMs, untilMs });
  const nonSynthetic = calls.filter((c) => !c.synthetic);

  const spawnCalls = nonSynthetic.filter((c) => c.harness === 'claude-code' && c.lineage.kind !== 'main');

  let firstUserMessageCharsMap = new Map();
  if (spawnCalls.length && harnesses.includes('claude-code')) {
    const fileMap = mapSpawnSessionIdsToFiles();
    const sessionIds = [...new Set(spawnCalls.map((c) => c.sessionId))];
    const results = await Promise.all(sessionIds.map(async (sid) => {
      const file = fileMap.get(sid);
      if (!file) return [sid, null];
      const len = await firstUserMessageLength(file);
      return [sid, len];
    }));
    firstUserMessageCharsMap = new Map(results);
  }

  const spawnRows = buildSpawnRows(spawnCalls, firstUserMessageCharsMap);
  const multiAgentRows = buildMultiAgentSessionRows(nonSynthetic, sessions);

  const requestedToActual = groupRequestedToActual(spawnRows);
  const byAgentType = groupByAgentType(spawnRows);
  const buckets = runLengthBuckets(spawnRows);
  const top = topByCost(spawnRows, opts.top);
  const firstUserMessageCharsP50 = firstUserMessageCharsPercentile(spawnRows, 0.5);
  const firstUserMessageCharsP90 = firstUserMessageCharsPercentile(spawnRows, 0.9);

  if (opts.json) {
    console.log(JSON.stringify({
      window: { sinceMs, untilMs }, harnesses, top: opts.top,
      spawnCount: spawnRows.length,
      requestedToActual, byAgentType, buckets, top20: top,
      firstUserMessageChars: { p50: firstUserMessageCharsP50, p90: firstUserMessageCharsP90 },
      multiAgentSessions: multiAgentRows,
    }, null, 2));
    return;
  }

  console.log(`spawn-economics [${harnesses.join(',')}] — since ${new Date(sinceMs).toISOString()}${untilMs ? ` until ${new Date(untilMs).toISOString()}` : ''}`);
  console.log(`${spawnRows.length} spawns (Claude spawn/fork lineage)`);

  console.log('\n=== requested -> actual model ===');
  for (const g of requestedToActual) {
    console.log(`  ${g.key.padEnd(40)} spawns=${String(g.spawns).padStart(5)}  cost=${usd(g.costUsd).padStart(7)}  calls=${String(g.calls).padStart(7)}`);
  }

  console.log('\n=== by agentType ===');
  for (const g of byAgentType) {
    console.log(`  ${g.agentType.padEnd(20)} spawns=${String(g.spawns).padStart(5)}  cost=${usd(g.costUsd).padStart(7)}  calls=${String(g.calls).padStart(7)}`);
  }

  console.log('\n=== run-length buckets ===');
  for (const b of buckets) {
    console.log(`  ${b.bucket.padEnd(10)} spawns=${String(b.spawns).padStart(5)}  cost=${usd(b.costUsd).padStart(7)}  share=${pctFmt(b.costUsd, buckets.reduce((a, x) => a + x.costUsd, 0))}`);
  }

  console.log(`\n=== top ${opts.top} spawns by cost ===`);
  for (const r of top) {
    console.log(`  ${r.sessionId.slice(0, 8)} ${r.agentType.padEnd(16)} ${r.requestedModel.padEnd(8)} `
      + `calls=${String(r.calls).padStart(5)} peakCtx=${String(r.peakContextTokens).padStart(7)} `
      + `cost=${usd(r.costUsd).padStart(6)} firstUserMessageChars=${String(r.firstUserMessageChars ?? 'n/a').padStart(6)} ${r.description ?? ''}`);
  }

  // NOTE (independent review, 886 §12 PR 2 NIT 3): this is a mixed proxy, not
  // a clean "brief length" -- a skill-invoked subagent's first user turn is
  // the skill body ("Base directory for this skill: ..."), not an Agent-tool
  // brief. Both shapes are opening-turn character counts, folded together.
  console.log(`\nfirstUserMessageChars p50/p90 (mixed proxy: Agent briefs + skill bodies): `
    + `${firstUserMessageCharsP50 ?? 'n/a'} / ${firstUserMessageCharsP90 ?? 'n/a'}`);

  if (multiAgentRows.length) {
    console.log(`\n=== Codex multi-agent sessions (${multiAgentRows.length}) ===`);
    for (const r of multiAgentRows) {
      console.log(`  ${r.sessionId.slice(0, 8)} calls=${String(r.calls).padStart(5)} peakCtx=${String(r.peakContextTokens).padStart(7)} cost=${usd(r.costUsd).padStart(6)} model=${r.actualModel}`);
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main().catch((err) => { console.error(err); process.exit(1); });
}
