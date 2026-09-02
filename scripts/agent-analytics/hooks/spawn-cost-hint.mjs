#!/usr/bin/env node

/**
 * PostToolUse hook (matcher: "Agent") — spawn-cost-hint (tempdoc 886 §12 PR 4 / §2.3).
 *
 * 886 §2.3 found the money in subagent spend concentrated in a small share of LONG
 * spawns (the top 20 spawns, all "Implement <tempdoc> slice" opus workers, cost
 * $86-355 each) — a cost class the orchestrator has no visibility into until the
 * spawn is long over, because nothing surfaces per-spawn cost at the moment it
 * returns. This hook closes that: when an `Agent` tool call completes, it resolves
 * the spawn's OWN transcript (via the ledger's Claude adapter) and prints a
 * one-line cost/context summary, so the orchestrator sees "was that spawn cheap or
 * the $300 kind" before deciding whether to delegate the next chunk the same way.
 *
 * RESOLUTION (886 §12 discovery, no documented platform contract): Claude Code
 * stores a spawn's transcript at
 *   `<dirname(transcript_path)>/<session_id>/subagents/agent-<agentId>.jsonl`
 * with a sibling `.meta.json` carrying `{agentType, description, model, toolUseId,
 * spawnDepth}` (real corpus, `toolUseId` present on every SYNCHRONOUS spawn's
 * meta.json). PRIMARY path: scan that dir's `*.meta.json` files for the one whose
 * `toolUseId` equals this call's `tool_use_id` (PostToolUse carries that field —
 * dispatch.mjs:112). FALLBACK (async/background spawns, `run_in_background:true`):
 * their tool_response text carries an internal `agentId: <hex>` line and no
 * `toolUseId` in meta.json at all (verified corpus-wide) — extract the id from that
 * text and address `agent-<id>.jsonl` directly. Either miss (dir absent, no
 * matching meta, file unreadable, zero calls parsed) is a SILENT no-op: this is an
 * advisory delivering data that may not always be resolvable, not a guard.
 *
 * Advisory: never blocks, fail-open on any error, honors JUSTSEARCH_DISABLE_HOOKS.
 */

import fs from 'node:fs';
import path from 'node:path';
import { readJsonStdin, hooksDisabled, isDirectRun } from '../lib/hook-base.mjs';
import { callsFromClaudeTranscript } from '../lib/ledger/claude-adapter.mjs';
import { findPricing, PER_M } from '../lib/transcript-cost.mjs';

/** Read + parse every `*.meta.json` in a subagents dir; tolerant of a missing dir. */
function readMetaFiles(subagentsDir) {
  let names = [];
  try {
    names = fs.readdirSync(subagentsDir).filter((n) => n.endsWith('.meta.json'));
  } catch {
    return [];
  }
  const out = [];
  for (const name of names) {
    try {
      const meta = JSON.parse(fs.readFileSync(path.join(subagentsDir, name), 'utf8'));
      out.push({ name, meta });
    } catch {
      // unreadable/corrupt meta.json — skip, not fatal to the scan
    }
  }
  return out;
}

/** Best-effort text extraction from a PostToolUse `tool_response` of any shape. */
function responseText(toolResponse) {
  if (typeof toolResponse === 'string') return toolResponse;
  if (Array.isArray(toolResponse)) {
    return toolResponse.map((b) => (b && typeof b.text === 'string' ? b.text : '')).join('\n');
  }
  if (toolResponse && typeof toolResponse === 'object') {
    if (typeof toolResponse.text === 'string') return toolResponse.text;
    if (Array.isArray(toolResponse.content)) return responseText(toolResponse.content);
  }
  return '';
}

/**
 * Resolve the spawn's `.jsonl` file path, pure given the already-read meta-file
 * list and the raw hook input. Exported for unit testing without real files.
 */
export function resolveAgentFile(subagentsDir, metaFiles, input) {
  const toolUseId = input?.tool_use_id;
  if (toolUseId) {
    const hit = metaFiles.find((m) => m.meta?.toolUseId === toolUseId);
    if (hit) return { file: path.join(subagentsDir, hit.name.replace(/\.meta\.json$/, '.jsonl')), meta: hit.meta };
  }
  const text = responseText(input?.tool_response);
  const m = /agentId:\s*([0-9a-f]+)/i.exec(text);
  if (m) {
    const base = `agent-${m[1]}`;
    const metaHit = metaFiles.find((f) => f.name === `${base}.meta.json`);
    return { file: path.join(subagentsDir, `${base}.jsonl`), meta: metaHit?.meta ?? {} };
  }
  return null;
}

/**
 * Sum a Call's token axes into a dollar cost via findPricing, per call — an
 * unpriced call (unknown model, e.g. Claude's own literal `<synthetic>`
 * model-name turns) is COUNTED, not treated as voiding the whole spawn's
 * total (independent review NIT: one unpriced call among 50 priced ones
 * previously collapsed the entire line to `n/a`, hiding a real, mostly-known
 * cost behind a single bad axis). Returns `{total, priced, unpriced}`;
 * `priced === 0` is the only case a caller should render as `n/a`.
 */
export function costOfCalls(calls) {
  let total = 0;
  let priced = 0;
  let unpriced = 0;
  for (const c of calls) {
    const ts = c.ts ? Date.parse(c.ts) : null;
    const entry = findPricing(c.model, Number.isNaN(ts) ? null : ts, c.speed);
    if (!entry) {
      unpriced += 1;
      continue;
    }
    priced += 1;
    const t = c.tokens ?? {};
    total +=
      ((t.fresh ?? 0) * entry.input +
        (t.output ?? 0) * entry.output +
        (t.cacheRead ?? 0) * entry.cache_read +
        (t.cacheWrite5m ?? 0) * entry.cache_write_5m +
        (t.cacheWrite1h ?? 0) * entry.cache_write_1h) /
      PER_M;
  }
  return { total, priced, unpriced };
}

/** Most-frequent non-null `model` across calls (the ACTUAL model resolved, per call). */
function dominantModel(calls) {
  const counts = new Map();
  for (const c of calls) {
    if (!c.model) continue;
    counts.set(c.model, (counts.get(c.model) ?? 0) + 1);
  }
  let best = null;
  let bestCount = 0;
  for (const [model, n] of counts) {
    if (n > bestCount) {
      best = model;
      bestCount = n;
    }
  }
  return best;
}

/** Build the one-line advisory text from a resolved spawn's calls + meta. Pure. */
export function renderSpawnCostLine(calls, meta) {
  if (!calls || calls.length === 0) return null;
  const peakCtx = Math.max(...calls.map((c) => c.contextTokens ?? 0));
  const outTotal = calls.reduce((sum, c) => sum + (c.tokens?.output ?? 0), 0);
  const { total: cost, priced, unpriced } = costOfCalls(calls);
  const costStr = priced === 0 ? 'n/a' : `~$${cost.toFixed(2)}${unpriced > 0 ? ` (+${unpriced} unpriced)` : ''}`;
  const actual = dominantModel(calls) ?? '(unknown)';
  const requested = meta?.model ?? 'unset';
  const description = meta?.description ?? '(no description)';
  return (
    `spawn-cost: ${calls.length} calls, peak ctx ${Math.round(peakCtx / 1000)}k, ` +
    `out ${Math.round(outTotal / 1000)}k, model ${actual} (requested ${requested}), ` +
    `${costStr} — ${description}`
  );
}

async function main() {
  if (hooksDisabled()) return;
  const input = await readJsonStdin();
  if (!input || input.tool_name !== 'Agent') return;
  const transcriptPath = input.transcript_path;
  const sessionId = input.session_id;
  if (!transcriptPath || !sessionId) return;

  const subagentsDir = path.join(path.dirname(transcriptPath), sessionId, 'subagents');
  const metaFiles = readMetaFiles(subagentsDir);
  if (metaFiles.length === 0) return;

  const resolved = resolveAgentFile(subagentsDir, metaFiles, input);
  if (!resolved || !fs.existsSync(resolved.file)) return;

  let calls;
  try {
    const kind = resolved.meta?.agentType === 'fork' ? 'fork' : 'spawn';
    ({ calls } = callsFromClaudeTranscript(resolved.file, {
      sessionId: `${sessionId}:${path.basename(resolved.file, '.jsonl')}`,
      lineage: {
        parentSessionId: sessionId,
        kind,
        agentType: resolved.meta?.agentType ?? null,
        requestedModel: resolved.meta?.model ?? null,
        description: resolved.meta?.description ?? null,
      },
    }));
  } catch {
    return;
  }

  const line = renderSpawnCostLine(calls, resolved.meta);
  if (!line) return;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: line },
    }),
  );
}

if (isDirectRun(import.meta.url)) {
  main().catch(() => process.exit(0));
}
