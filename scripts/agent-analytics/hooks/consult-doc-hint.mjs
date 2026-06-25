#!/usr/bin/env node

/**
 * PreToolUse "Consult" hook for Edit/Write on governed code regions.
 *
 * Behavioral-protocol pilot (tempdoc 579, "Consult" step). The drift class this
 * tempdoc fixed (React→Lit across the docs) persisted because agents never read
 * the governing decision — canonical docs are 0.1% of agent reads. Generators
 * can't carry out-of-code knowledge (the "why"); the lever is to *push* the one
 * governing decision-doc into context at the moment of the edit, instead of
 * hoping the agent navigates to it.
 *
 * Surgical by design: one terse pointer per governed region (NOT the doc body) —
 * bloated context is net-negative (ETH "Evaluating AGENTS.md", arXiv 2602.11988).
 * Start with a tiny high-value map; promote to governance/consult-register.v1.json
 * only if it grows.
 *
 * - Synchronous, <50ms, no process spawning — just a path check.
 * - Honors the JUSTSEARCH_DISABLE_HOOKS=1 kill switch.
 * - Outputs hookSpecificOutput.additionalContext only when a region matches.
 * - De-duped ONCE per region per session (mirrors maintain-doc-hint): without this
 *   the same governing doc re-injects on every edit in a region (e.g. ADR-0032 on
 *   each shell-v0 edit) — pure context waste. State lives in a per-session marker.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { regionFor } from '../lib/governed-regions.mjs';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..', '..');

async function main() {
  if (process.env.JUSTSEARCH_DISABLE_HOOKS === '1') return;

  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    if (input.tool_name !== 'Edit' && input.tool_name !== 'Write') return;

    const entry = regionFor(input.tool_input?.file_path);
    if (!entry) return;

    // De-dupe per session: deliver each region's pointer ONCE per session. The
    // governing doc doesn't change between the 1st and the Nth edit in a region, so
    // re-pushing it is waste (tempdoc 579 — the Consult-step gap; maintain-doc-hint
    // already dedupes this way). Record BEFORE emitting; on persist failure, deliver
    // anyway (delivery-first: a missed write should not silence the hook).
    const sessionId = input.session_id || 'unknown';
    const markerFile = path.join(
      REPO_ROOT,
      'tmp',
      'agent-telemetry',
      `consult-nudged-${sessionId}.json`,
    );
    let consulted = [];
    try {
      consulted = JSON.parse(fs.readFileSync(markerFile, 'utf8'));
    } catch {
      /* none recorded yet */
    }
    if (consulted.includes(entry.region)) return; // already delivered this region this session
    try {
      fs.mkdirSync(path.dirname(markerFile), { recursive: true });
      fs.writeFileSync(markerFile, JSON.stringify([...new Set([...consulted, entry.region])]));
    } catch {
      /* couldn't persist — fall through and deliver once anyway */
    }

    const lines = [`You are editing ${entry.region}.`];
    if (entry.docs.length) {
      lines.push('Before changing behavior, consult the governing decision(s):');
      for (const d of entry.docs) lines.push(`  - \`${d.path}\` — ${d.why}`);
    }
    const recipe = Array.isArray(entry.recipe) ? entry.recipe : [];
    if (recipe.length) {
      lines.push('Recipe (just-in-time procedure for this region):');
      recipe.forEach((step, i) => lines.push(`  ${i + 1}. ${step}`));
    }
    lines.push(
      '(Consult hint — tempdoc 579/620 behavioral pilot. Records intent/procedure code cannot reveal, delivered when you touch the region; trust them, or update them if your change supersedes them.)',
    );
    const hint = lines.join('\n');

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        additionalContext: hint,
      },
    }));
  } catch {
    // Parse failure — no output, never block.
  }
}

main().catch(() => process.exit(0));
