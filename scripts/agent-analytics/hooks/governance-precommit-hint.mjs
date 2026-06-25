#!/usr/bin/env node

/**
 * PreToolUse hook on Bash `git commit` — Layer 3 §3.7a / Polish §12 of
 * tempdoc 530.
 *
 * The project has no git pre-commit hook infrastructure (no Husky /
 * lefthook / native .git/hooks/), so this hook is the Claude-Code-side
 * equivalent: fires before `git commit` runs, scans for rebalanceable
 * baselines, and emits a hint with the exact --rebalance command(s).
 *
 * Does NOT auto-write. Architectural decision (tempdoc 530 Pass-7 §1):
 * explicit `--rebalance` invocation is the better long-term default;
 * auto-write creates git-history noise on every shrink.
 */

import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

const REPO_ROOT = resolve(new URL('.', import.meta.url).pathname, '..', '..', '..');

function isGitCommit(cmd) {
  if (!cmd) return false;
  // Match `git commit ...` but NOT `git commit-tree`, `git commit-graph`, etc.
  return /\bgit\s+commit\b(?!\s*[a-z-]*-tree|\s*-graph|\s*-msg)/i.test(cmd);
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    if (input.tool_name !== 'Bash') return;
    if (!isGitCommit(input.tool_input?.command)) return;

    // Scan for rebalanceable gates. Run the kernel in warn mode; look for
    // *-available rule patterns in its SARIF output.
    let sarifPath = resolve(REPO_ROOT, 'tmp/governance-report.sarif');
    if (!existsSync(sarifPath)) return;
    let sarif;
    try { sarif = JSON.parse(readFileSync(sarifPath, 'utf8')); } catch { return; }

    const rebalanceable = new Map(); // gate-id → count
    for (const run of sarif.runs ?? []) {
      const gateId = run.properties?.categoryId;
      for (const r of run.results ?? []) {
        if (r.ruleId?.endsWith('/rebalance-available') || r.ruleId?.endsWith('/row-removed')) {
          rebalanceable.set(gateId, (rebalanceable.get(gateId) ?? 0) + 1);
        }
      }
    }
    if (rebalanceable.size === 0) return;

    const lines = [`Discipline-gate kernel: ${rebalanceable.size} gate(s) have available auto-shrink rebalances:`];
    for (const [gateId, count] of rebalanceable) {
      lines.push(`  - ${gateId}: ${count} row(s) — \`node scripts/governance/run.mjs --gate ${gateId} --rebalance\``);
    }
    lines.push(`(Optional: stage the resulting baseline edits in this commit to tighten the ratchet.)`);

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: lines.join('\n') },
    }));
  } catch { /* silent */ }
}

main().catch(() => process.exit(0));
