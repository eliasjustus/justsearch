#!/usr/bin/env node
/**
 * context-residency.mjs — tempdoc 886 §12 PR 2.
 *
 * WHY THIS EXISTS. Tempdoc 886 §2 found that the variable that actually sets
 * the bill is not cache-hit RATE (cache-efficiency.mjs already measures
 * that) but context tokens RE-PRESENTED PER CALL — a call's absolute size,
 * multiplied by how many calls carry it. No existing reader in this
 * directory measured that distribution, the cost of exceeding a context
 * cap, the compaction ledger, or the compounded cost of what stays resident
 * turn over turn. This reader productionises the three throwaway scripts
 * `tmp/tokeff/{deep,deep3,deep4}.mjs` (886 §1) that first measured them.
 *
 * SECTIONS (a-c) READ THE NEUTRAL LEDGER (`lib/ledger/index.mjs`), so they
 * are harness-neutral by construction — the same distribution/cap/compaction
 * logic runs over Claude Code and Codex CLI calls alike.
 *
 * SECTION (d) — compounded residency — is Claude-transcript-shaped and
 * DELIBERATELY reads raw transcript entries directly, the same precedent
 * `cache-efficiency.mjs`'s TTL/invalidation taxonomy set (see that module's
 * header): the neutral `Call`/`ToolEvent` record has no axis for a plain
 * user-text or assistant-text block's SIZE (only tool_use/tool_result are
 * captured as `ToolEvent`s), and neither harness's Codex-side log exposes an
 * equivalent breakdown (`agent_message` is deliberately not a `ToolEvent`,
 * codex-adapter.mjs's module doc). Section (d) is reported for `claude-code`
 * only; a `codex-cli`-only invocation prints an explicit note rather than a
 * partial, mis-proportioned number.
 *
 * `--since <ISO>` (default: trailing 30 days), `--until <ISO>`,
 * `--harness claude-code|codex-cli|all` (default all), `--cap <tokens>`
 * (default 200000), `--json`.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { listCalls } from './lib/ledger/index.mjs';
import { findPricing, round } from './lib/transcript-cost.mjs';
import { discoverProjectDirs, listSubagentPaths, DEFAULT_PROJECTS_ROOT } from './lib/transcript-store.mjs';

const DAY_MS = 24 * 60 * 60 * 1000;
const VALID_HARNESS_ARGS = ['claude-code', 'codex-cli', 'all'];

// --- generic helpers ---------------------------------------------------------

/** Same floor-index percentile method the tmp/tokeff prototypes used. */
export function percentile(sortedAsc, p) {
  if (!sortedAsc.length) return 0;
  const idx = Math.min(sortedAsc.length - 1, Math.floor(p * sortedAsc.length));
  return sortedAsc[idx];
}

function fmtM(n) { return `${(n / 1e6).toFixed(1)}M`; }
function usd(n) { return `$${n.toFixed(0)}`; }

export function lineageGroupOf(call) {
  return call.lineage.kind === 'main' ? 'main' : 'spawn/fork';
}

// --- (a) per-call context distribution ---------------------------------------

/** `calls` should already exclude synthetic calls (886 §12 PR 2 contract). */
export function buildDistribution(calls) {
  const groups = new Map();
  for (const c of calls) {
    const model = c.model ?? '(missing-model)';
    const key = `${c.harness}|${lineageGroupOf(c)}|${model}`;
    let g = groups.get(key);
    if (!g) {
      g = { harness: c.harness, lineageGroup: lineageGroupOf(c), model, contextTokens: [], totalOutput: 0 };
      groups.set(key, g);
    }
    g.contextTokens.push(c.contextTokens);
    g.totalOutput += c.tokens.output || 0;
  }
  const rows = [];
  for (const g of groups.values()) {
    const sorted = g.contextTokens.slice().sort((x, y) => x - y);
    const totalContextTokens = sorted.reduce((a, b) => a + b, 0);
    rows.push({
      harness: g.harness,
      lineageGroup: g.lineageGroup,
      model: g.model,
      calls: sorted.length,
      p50: percentile(sorted, 0.5),
      p75: percentile(sorted, 0.75),
      p90: percentile(sorted, 0.9),
      p99: percentile(sorted, 0.99),
      max: sorted[sorted.length - 1] ?? 0,
      totalContextTokens,
      totalOutputTokens: g.totalOutput,
      ratio: g.totalOutput ? Math.round(totalContextTokens / g.totalOutput) : null,
    });
  }
  rows.sort((a, b) => b.totalContextTokens - a.totalContextTokens);
  return rows;
}

// --- (b) cost/share above --cap -----------------------------------------------

/**
 * `shareAboveCapPct` is the share of a group's TOTAL context tokens sitting in
 * calls whose contextTokens exceeds `cap` (the tmp/tokeff/deep.mjs "bucket"
 * method — full ctx of the qualifying call, not just its excess).
 * `costAboveCapUsd` prices only the EXCESS (ctx - cap) at the call's model's
 * cache-read rate (the tmp/tokeff/deep3.mjs `excessCost` method, and the
 * method behind 886 §2.2's "$5.4k upper bound" figure). A call whose model
 * has no pricing row contributes its excess tokens to `unpricedTokensAboveCap`
 * instead of silently pricing it at $0 (transcript-cost.mjs's fail-closed rule).
 */
export function buildCapExcess(calls, cap) {
  const groups = new Map();
  for (const c of calls) {
    const key = `${c.harness}|${lineageGroupOf(c)}`;
    let g = groups.get(key);
    if (!g) {
      g = {
        harness: c.harness, lineageGroup: lineageGroupOf(c),
        totalTokens: 0, tokensAboveCap: 0, costAboveCapUsd: 0,
        unpricedTokensAboveCap: 0, unpricedModels: new Set(),
      };
      groups.set(key, g);
    }
    g.totalTokens += c.contextTokens;
    if (c.contextTokens > cap) {
      g.tokensAboveCap += c.contextTokens;
      const excess = c.contextTokens - cap;
      const tsMs = c.ts ? Date.parse(c.ts) : null;
      const pricing = findPricing(c.model, Number.isNaN(tsMs) ? null : tsMs, c.speed);
      if (pricing) {
        g.costAboveCapUsd += (excess * pricing.cache_read) / 1e6;
      } else {
        g.unpricedTokensAboveCap += excess;
        if (c.model) g.unpricedModels.add(c.model);
      }
    }
  }
  const rows = [...groups.values()].map((g) => ({
    harness: g.harness,
    lineageGroup: g.lineageGroup,
    sharePct: g.totalTokens ? round((100 * g.tokensAboveCap) / g.totalTokens, 1) : 0,
    tokensAboveCap: g.tokensAboveCap,
    totalTokens: g.totalTokens,
    costAboveCapUsd: round(g.costAboveCapUsd, 0),
    unpricedTokensAboveCap: g.unpricedTokensAboveCap,
    unpricedModels: [...g.unpricedModels],
  }));
  rows.sort((a, b) => b.costAboveCapUsd - a.costAboveCapUsd);
  return rows;
}

// --- (c) compaction ledger ----------------------------------------------------

function groupCallsBySession(calls) {
  const bySession = new Map();
  for (const c of calls) {
    if (!bySession.has(c.sessionId)) bySession.set(c.sessionId, []);
    bySession.get(c.sessionId).push(c);
  }
  for (const arr of bySession.values()) {
    arr.sort((x, y) => {
      const tx = x.ts ? Date.parse(x.ts) : 0;
      const ty = y.ts ? Date.parse(y.ts) : 0;
      return tx - ty;
    });
  }
  return bySession;
}

/**
 * One row per `compactionBoundary: true` call. Claude carries `compactMetadata`
 * (trigger/preTokens/postTokens/durationMs) verbatim from the transcript — used
 * directly when present. Codex carries none, so its trigger is the literal
 * `'codex'` (886 §12 PR 2 contract) and pre/post tokens are inferred from the
 * surrounding calls in the same session (previous call's contextTokens = pre;
 * the boundary call's own contextTokens = post). `calls` must already exclude
 * synthetic calls — a synthetic Codex "orphan boundary" call (no real usage
 * ever recorded after a `compacted` line) is filtered out at the same point
 * every other distribution in this module drops synthetic calls, so it never
 * masquerades as a real, $0 compaction event.
 */
export function buildCompactionLedger(calls) {
  const bySession = groupCallsBySession(calls);
  const rows = [];
  for (const arr of bySession.values()) {
    for (let i = 0; i < arr.length; i += 1) {
      const c = arr[i];
      if (!c.compactionBoundary) continue;
      let trigger;
      let preTokens = null;
      let postTokens = null;
      let durationMs = null;
      if (c.compactMetadata) {
        trigger = c.compactMetadata.trigger ?? 'unknown';
        preTokens = c.compactMetadata.preTokens ?? null;
        postTokens = c.compactMetadata.postTokens ?? null;
        durationMs = c.compactMetadata.durationMs ?? null;
      } else if (c.harness === 'codex-cli') {
        trigger = 'codex';
      } else {
        trigger = 'unknown';
      }
      if (preTokens == null) {
        const prev = arr[i - 1];
        preTokens = prev ? prev.contextTokens : null;
      }
      if (postTokens == null) postTokens = c.contextTokens;
      rows.push({ sessionId: c.sessionId, harness: c.harness, trigger, preTokens, postTokens, durationMs });
    }
  }
  const preSorted = rows.map((r) => r.preTokens).filter((x) => x != null).sort((a, b) => a - b);
  const durSorted = rows.map((r) => r.durationMs).filter((x) => x != null).sort((a, b) => a - b);
  const triggerBreakdown = {};
  for (const r of rows) triggerBreakdown[r.trigger] = (triggerBreakdown[r.trigger] || 0) + 1;
  return {
    count: rows.length,
    triggerBreakdown,
    preTokens: {
      p50: percentile(preSorted, 0.5),
      min: preSorted.length ? preSorted[0] : null,
      max: preSorted.length ? preSorted[preSorted.length - 1] : null,
    },
    durationMs: { p50: percentile(durSorted, 0.5), countWithData: durSorted.length },
    rows,
  };
}

// --- (d) compounded residency (claude-code only, raw transcript walk) --------

function tokEstimate(s) { return Math.ceil((s || '').length / 4); }

function categoryForToolUse(name) {
  if (name === 'Agent') return 'Agent-brief';
  if (name === 'Write' || name === 'Edit') return 'Write/Edit';
  return 'other';
}

function extractResultText(block) {
  if (typeof block.content === 'string') return block.content;
  if (Array.isArray(block.content)) return block.content.map((c) => (c && c.text) || '').join('');
  return '';
}

function isBoundaryEntry(e) {
  return Boolean(e.isCompactSummary || e.subtype === 'compact_boundary' || e.compactMetadata);
}

/**
 * The compounded-residency method (tmp/tokeff/deep4.mjs, productionised):
 * every context PIECE (prefix, a tool result, a tool_use input, a user-text
 * block, an assistant-text block, a thinking block) is added to a `resident`
 * list and STAYS there (never individually evicted) until a compaction
 * boundary resets the whole list. On EVERY subsequent call, every resident
 * piece is charged tokens x cache-read-rate again — that is the "compounded"
 * part: a piece that stays resident for 400 calls is charged 400 times, not
 * once. The chars/4 estimate is rescaled per call so the sum of estimated
 * pieces matches that call's ACTUAL `contextTokens` (`scale = ctx / est`).
 *
 * Exported so a test can feed it a crafted `entries` array directly, matching
 * the codex-adapter.mjs precedent for entries-in pure functions.
 * `categoryPrefix` is `claude-code:main` or `claude-code:sub` — the "group by
 * {harness}:{category}" contract (886 §12 PR 2).
 */
export function accumulateResidency(entries, acc, categoryPrefix) {
  const seenIds = new Set();
  const toolUseById = {};
  let resident = [];
  let prefixTok = null;

  for (const e of entries) {
    if (isBoundaryEntry(e)) resident = [];

    if (e.type === 'user') {
      const content = e.message?.content;
      if (typeof content === 'string') {
        resident.push({ cat: `${categoryPrefix}:user-text`, t: tokEstimate(content) });
      } else if (Array.isArray(content)) {
        for (const b of content) {
          if (!b) continue;
          if (b.type === 'tool_result') {
            const txt = extractResultText(b);
            const name = toolUseById[b.tool_use_id] || '?';
            const cat = /<task-notification>/.test(txt)
              ? `${categoryPrefix}:tool:Agent(notification)`
              : `${categoryPrefix}:tool:${name}`;
            resident.push({ cat, t: tokEstimate(txt) });
          } else if (b.type === 'text') {
            resident.push({ cat: `${categoryPrefix}:user-text`, t: tokEstimate(b.text) });
          }
        }
      }
      continue;
    }

    if (e.type !== 'assistant' || !e.message?.usage) continue;

    for (const b of (e.message.content || [])) {
      if (!b) continue;
      if (b.type === 'tool_use') {
        toolUseById[b.id] = b.name;
        resident.push({
          cat: `${categoryPrefix}:assistant-tool_use(${categoryForToolUse(b.name)})`,
          t: tokEstimate(JSON.stringify(b.input ?? {})),
        });
      } else if (b.type === 'text') {
        resident.push({ cat: `${categoryPrefix}:assistant-text`, t: tokEstimate(b.text) });
      } else if (b.type === 'thinking') {
        resident.push({ cat: `${categoryPrefix}:thinking`, t: tokEstimate(b.thinking) });
      }
    }

    const id = e.message.id;
    if (id) {
      if (seenIds.has(id)) continue;
      seenIds.add(id);
    }

    const u = e.message.usage;
    const pricing = findPricing(e.message.model || '');
    if (!pricing) continue; // fails closed, same as every other reader here
    const rate = pricing.cache_read / 1e6;
    const ctx = (u.input_tokens || 0) + (u.cache_read_input_tokens || 0) + (u.cache_creation_input_tokens || 0);
    if (prefixTok === null) prefixTok = ctx;

    const est = resident.reduce((a, r) => a + r.t, 0) + prefixTok;
    const scale = est > 0 ? ctx / est : 1;

    const prefixCat = `${categoryPrefix}:prefix(system+CLAUDE.md+tools)`;
    acc.byCategory[prefixCat] = (acc.byCategory[prefixCat] || 0) + prefixTok * scale * rate;
    for (const r of resident) acc.byCategory[r.cat] = (acc.byCategory[r.cat] || 0) + r.t * scale * rate;

    acc.totalCost += ctx * rate;
    acc.totalCalls += 1;
  }
}

export function emptyResidencyAcc() {
  return { byCategory: {}, totalCost: 0, totalCalls: 0 };
}

/** Collapse a detailed `${prefix}:tool:<Name>` key down to `${prefix}:tool` etc, deep4-style. */
export function collapseCategory(key) {
  return key.replace(/:tool:.*$/, ':tool').replace(/\(.*\)$/, '');
}

function parseJsonl(file) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  const out = [];
  for (const raw of content.split('\n')) {
    if (!raw.trim()) continue;
    try { out.push(JSON.parse(raw)); } catch { /* skip malformed line */ }
  }
  return out;
}

/**
 * Walk every Claude Code main + subagent transcript in the mtime window and
 * build the compounded-residency accumulator. Read-only; discovery reuses
 * `lib/transcript-store.mjs` exactly like `cache-efficiency.mjs` does.
 */
export function buildCompoundedResidency({ projectsRoot = DEFAULT_PROJECTS_ROOT, sinceMs = null, untilMs = null, projectFilter } = {}) {
  const acc = emptyResidencyAcc();
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
      const filePath = path.join(dir.path, f.name);
      let stat;
      try {
        stat = fs.statSync(filePath);
      } catch {
        continue;
      }
      if (sinceMs != null && stat.mtimeMs < sinceMs) continue;
      if (untilMs != null && stat.mtimeMs > untilMs) continue;

      const sessionId = f.name.slice(0, -'.jsonl'.length);
      const mainEntries = parseJsonl(filePath);
      if (!mainEntries.some((e) => e.type === 'assistant' && e.message?.usage)) continue;
      accumulateResidency(mainEntries, acc, 'claude-code:main');

      for (const subPath of listSubagentPaths(dir.path, sessionId)) {
        accumulateResidency(parseJsonl(subPath), acc, 'claude-code:sub');
      }
    }
  }
  return acc;
}

export function residencyReport(acc) {
  const rows = Object.entries(acc.byCategory)
    .map(([category, costUsd]) => ({ category, costUsd, sharePct: acc.totalCost ? round((100 * costUsd) / acc.totalCost, 1) : 0 }))
    .sort((a, b) => b.costUsd - a.costUsd);

  const grouped = new Map();
  for (const r of rows) {
    const g = collapseCategory(r.category);
    grouped.set(g, (grouped.get(g) || 0) + r.costUsd);
  }
  const byCategory = [...grouped.entries()]
    .map(([category, costUsd]) => ({ category, costUsd, sharePct: acc.totalCost ? round((100 * costUsd) / acc.totalCost, 1) : 0 }))
    .sort((a, b) => b.costUsd - a.costUsd);

  return {
    totalCostUsd: round(acc.totalCost, 0),
    totalCalls: acc.totalCalls,
    byCategory,
    top20: rows.slice(0, 20),
  };
}

// --- CLI -----------------------------------------------------------------

function parseArgs(argv) {
  const opts = { since: null, until: null, harness: 'all', cap: 200000, json: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--harness') opts.harness = argv[++i];
    else if (a === '--cap') opts.cap = Number(argv[++i]);
    else if (a === '--json') opts.json = true;
  }
  return opts;
}

function printDistribution(rows) {
  console.log('\n=== (a) per-call context distribution: harness x lineage x model ===');
  console.log('harness      lineage     model                          calls     p50     p75     p90     p99     max   totCtx   totOut  ctx/out');
  for (const r of rows) {
    console.log(
      `${r.harness.padEnd(12)} ${r.lineageGroup.padEnd(11)} ${r.model.padEnd(28)} `
      + `${String(r.calls).padStart(7)} ${String(r.p50).padStart(7)} ${String(r.p75).padStart(7)} `
      + `${String(r.p90).padStart(7)} ${String(r.p99).padStart(7)} ${String(r.max).padStart(7)} `
      + `${fmtM(r.totalContextTokens).padStart(8)} ${fmtM(r.totalOutputTokens).padStart(8)} ${String(r.ratio ?? 'n/a').padStart(8)}`,
    );
  }
}

function printCapExcess(rows, cap) {
  console.log(`\n=== (b) share/cost above --cap=${cap} ===`);
  let totalCost = 0;
  for (const r of rows) {
    totalCost += r.costAboveCapUsd;
    const unpriced = r.unpricedTokensAboveCap ? ` + ${fmtM(r.unpricedTokensAboveCap)} n/a (${r.unpricedModels.join(',')})` : '';
    console.log(`  ${r.harness.padEnd(12)} ${r.lineageGroup.padEnd(11)} share=${String(r.sharePct).padStart(5)}%  `
      + `tokensAboveCap=${fmtM(r.tokensAboveCap).padStart(8)}  cost=${usd(r.costAboveCapUsd).padStart(7)}${unpriced}`);
  }
  console.log(`  total priceable cost above cap: ${usd(totalCost)}`);
}

function printCompaction(ledger) {
  console.log('\n=== (c) compaction ledger ===');
  console.log(`  count=${ledger.count}  triggers=${JSON.stringify(ledger.triggerBreakdown)}`);
  console.log(`  pre-compaction context p50/min/max: ${ledger.preTokens.p50} / ${ledger.preTokens.min} / ${ledger.preTokens.max}`);
  console.log(`  durationMs p50 (n=${ledger.durationMs.countWithData}): ${ledger.durationMs.p50}`);
}

function printResidency(report) {
  console.log('\n=== (d) compounded residency (claude-code only) ===');
  console.log(`  total context re-presentation cost (cache-read priced): ${usd(report.totalCostUsd)} over ${report.totalCalls} calls`);
  console.log('\n  by category:');
  for (const r of report.byCategory) console.log(`    ${r.category.padEnd(40)} ${usd(r.costUsd).padStart(7)}  ${r.sharePct}%`);
  console.log('\n  top 20 detailed:');
  for (const r of report.top20) console.log(`    ${r.category.padEnd(60)} ${usd(r.costUsd).padStart(7)}  ${r.sharePct}%`);
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!VALID_HARNESS_ARGS.includes(opts.harness)) {
    console.error(`context-residency: unknown --harness "${opts.harness}" (expected one of: ${VALID_HARNESS_ARGS.join(', ')})`);
    process.exit(2);
  }
  const harnesses = opts.harness === 'all' ? ['claude-code', 'codex-cli'] : [opts.harness];
  const sinceMs = opts.since ? Date.parse(opts.since) : Date.now() - 30 * DAY_MS;
  const untilMs = opts.until ? Date.parse(opts.until) : null;

  const { calls, skipped } = listCalls({ harnesses, sinceMs, untilMs });
  const nonSynthetic = calls.filter((c) => !c.synthetic);

  const distribution = buildDistribution(nonSynthetic);
  const capExcess = buildCapExcess(nonSynthetic, opts.cap);
  const compaction = buildCompactionLedger(nonSynthetic);

  let residency = null;
  if (harnesses.includes('claude-code')) {
    const acc = buildCompoundedResidency({ sinceMs, untilMs });
    residency = residencyReport(acc);
  }

  if (opts.json) {
    console.log(JSON.stringify({
      window: { sinceMs, untilMs }, cap: opts.cap, harnesses,
      distribution, capExcess, compaction, residency, skipped: skipped?.length ?? 0,
    }, null, 2));
    return;
  }

  console.log(`context-residency [${harnesses.join(',')}] — since ${new Date(sinceMs).toISOString()}${untilMs ? ` until ${new Date(untilMs).toISOString()}` : ''}`);
  // Two DIFFERENT things named "synthetic" here (independent review, 886 §12
  // PR 2 NIT 2) -- do not conflate them. `Call.synthetic` is the ledger's own
  // boolean (codex-adapter.mjs's fabricated zero-token orphan-boundary Call);
  // a Claude turn whose `message.model` is the LITERAL string `<synthetic>`
  // is an unrelated Claude-harness internal placeholder (compaction-summary
  // turns etc) that the adapter never flags `synthetic:true` for, so it
  // stays IN `nonSynthetic` and gets its own model-bucket rows (contextTokens
  // 0) in section (a) -- see the `<synthetic>` rows there.
  const syntheticFlagCount = calls.length - nonSynthetic.length;
  const syntheticModelCount = nonSynthetic.filter((c) => c.model === '<synthetic>').length;
  console.log(`${nonSynthetic.length} calls (${syntheticFlagCount} Codex synthetic-boundary calls excluded; `
    + `${syntheticModelCount} calls with model '<synthetic>' kept in their own rows, contextTokens 0)`);
  printDistribution(distribution);
  printCapExcess(capExcess, opts.cap);
  printCompaction(compaction);
  if (residency) {
    printResidency(residency);
  } else {
    console.log('\n=== (d) compounded residency ===\n  skipped: requires claude-code in --harness (Codex ledger has no text/thinking-block size axis).');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
