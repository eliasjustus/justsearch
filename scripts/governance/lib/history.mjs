/**
 * Run-history substrate — Layer 3 §3.7a of tempdoc 530.
 *
 * After each gate run, append a one-line summary to
 * `tmp/governance-history.ndjson`. Each line:
 *   {"ts":"<iso>", "gate":"<id>", "verdict":"pass|fail", "findings":{"error":N,"warning":N,"note":N}}
 *
 * History is local-only (under tmp/, gitignored). CI artifact retention or
 * a separately-committed history file are out-of-scope for V1 — the local
 * file is enough to drive a dashboard that runs locally or in CI.
 */

import { existsSync, appendFileSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const DEFAULT_HISTORY_PATH = 'tmp/governance-history.ndjson';

export function appendRunRecord({ repoRoot, path = DEFAULT_HISTORY_PATH, runs, verdicts }) {
  const ts = new Date().toISOString();
  const full = resolve(repoRoot, path);
  mkdirSync(dirname(full), { recursive: true });
  for (const v of verdicts) {
    const matching = runs.find(r => r.categoryId === v.gate);
    const counts = { error: 0, warning: 0, note: 0 };
    for (const f of matching?.findings ?? []) {
      counts[f.level] = (counts[f.level] ?? 0) + 1;
    }
    const line = JSON.stringify({ ts, gate: v.gate, verdict: v.verdict, findings: counts });
    appendFileSync(full, line + '\n');
  }
}

export function readHistory({ repoRoot, path = DEFAULT_HISTORY_PATH }) {
  const full = resolve(repoRoot, path);
  if (!existsSync(full)) return [];
  const content = readFileSync(full, 'utf8');
  const out = [];
  for (const raw of content.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    try { out.push(JSON.parse(line)); } catch { /* skip malformed */ }
  }
  return out;
}
