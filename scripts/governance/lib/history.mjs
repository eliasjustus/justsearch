/**
 * Run-history substrate — Layer 3 §3.7a of tempdoc 530.
 *
 * After each kernel invocation, append one versioned line per gate plus one run-level repository-health
 * line to `tmp/governance-history.ndjson`. Readers retain the unversioned V1 gate-row shape.
 *
 * History is local-only (under tmp/, gitignored). CI may retain the per-run file as an artifact,
 * but no separately committed or cross-run rolling history exists. The local file drives the
 * dashboard and API projection while that checkout exists.
 */

import { existsSync, appendFileSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

import { collectRepositoryHealth } from './repository-health.mjs';

export const DEFAULT_HISTORY_PATH = 'tmp/governance-history.ndjson';
export const HISTORY_SCHEMA_VERSION = 2;

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
    const line = JSON.stringify({
      schemaVersion: HISTORY_SCHEMA_VERSION,
      kind: 'gate-run',
      ts,
      gate: v.gate,
      verdict: v.verdict,
      findings: counts,
    });
    appendFileSync(full, line + '\n');
  }
  const health = {
    schemaVersion: HISTORY_SCHEMA_VERSION,
    kind: 'repository-health',
    ts,
    metrics: collectRepositoryHealth(repoRoot),
  };
  appendFileSync(full, JSON.stringify(health) + '\n');
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

/** V1 rows had no kind/schemaVersion; a string gate keeps them readable during migration. */
export function readGateHistory(options) {
  return readHistory(options).filter(
    entry => typeof entry?.gate === 'string' && (entry.kind == null || entry.kind === 'gate-run'),
  );
}

export function readRepositoryHealthHistory(options) {
  return readHistory(options).filter(
    entry => entry?.kind === 'repository-health' && entry.schemaVersion === HISTORY_SCHEMA_VERSION,
  );
}
