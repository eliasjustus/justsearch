#!/usr/bin/env node
/**
 * Tempdoc 727: aggregates per-session output from mine-friction.mjs
 * (tmp/agent-telemetry/friction-results/<sessionId>.json) into a category-ranked
 * report — severity-weighted frequency across the batch, plus a few example
 * incidents per category for citation.
 *
 * By default, excludes sessions listed in friction-excluded-sessions.json (benchmark-
 * harness-subject sessions, personal/unrelated use, empty sessions, non-coding project
 * work) — this analysis is scoped to organic developer-agent sessions only. Pass
 * --include-excluded to disable the filter.
 *
 * Usage: node aggregate-friction.mjs [--include-excluded]
 */

import fs from 'node:fs';
import path from 'node:path';
import { loadExclusionKeys, makeExclusionMatcher, fmtScopeExclusion } from './lib/telemetry-io.mjs';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const repoRoot = path.resolve(SCRIPT_DIR, '..', '..');
const DIR = path.join(repoRoot, 'tmp', 'agent-telemetry', 'friction-results');
const OUT_FILE = path.join(repoRoot, 'tmp', 'agent-telemetry', 'friction-aggregate.json');
const EXCLUSIONS_FILE = path.join(SCRIPT_DIR, 'friction-excluded-sessions.json');

const costWeight = { low: 1, medium: 2, high: 3 };

function main() {
  const includeExcluded = process.argv.includes('--include-excluded');
  // Keys are loaded even when the filter is off, so the report can state the
  // denominator rather than an unexplained zero (tempdoc 858 §7).
  const exclusionKeys = loadExclusionKeys(EXCLUSIONS_FILE);
  const isExcluded = includeExcluded ? () => false : makeExclusionMatcher(exclusionKeys);
  const files = fs.readdirSync(DIR).filter(f => f.endsWith('.json'));
  const byCategory = new Map();
  let totalSessions = 0, sessionsWithFriction = 0, tooSmall = 0, errors = 0, excluded = 0;
  const biggestPerSession = [];

  for (const f of files) {
    const j = JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
    if (isExcluded(j.sessionId)) { excluded++; continue; }
    if (j.tooSmall) { tooSmall++; continue; }
    if (j.error) { errors++; continue; }
    totalSessions++;
    const ev = j.evaluation;
    if (!ev) { errors++; continue; }
    if (!ev.no_friction_detected && (ev.friction_incidents?.length ?? 0) > 0) sessionsWithFriction++;
    if (ev.biggest_single_timewaste) {
      biggestPerSession.push({ sessionId: j.sessionId, text: ev.biggest_single_timewaste, summary: ev.session_summary });
    }
    for (const inc of ev.friction_incidents ?? []) {
      const cat = (inc.category || 'other').toLowerCase().trim();
      if (!byCategory.has(cat)) byCategory.set(cat, { count: 0, weight: 0, examples: [] });
      const rec = byCategory.get(cat);
      rec.count++;
      rec.weight += costWeight[inc.estimated_cost] ?? 1;
      if (rec.examples.length < 6) {
        rec.examples.push({ sessionId: j.sessionId, description: inc.description, cost: inc.estimated_cost, evidence: inc.evidence });
      }
    }
  }

  const ranked = [...byCategory.entries()].sort((a, b) => b[1].weight - a[1].weight);

  console.log(`Sessions analyzed: ${totalSessions} (too small: ${tooSmall}, errors: ${errors}, ${fmtScopeExclusion({ excluded, listed: exclusionKeys.length, disabled: includeExcluded })})`);
  console.log(`Sessions with friction detected: ${sessionsWithFriction} (${Math.round(100 * sessionsWithFriction / totalSessions)}%)`);
  console.log(`Distinct friction categories: ${ranked.length}`);
  for (const [cat, rec] of ranked) {
    console.log(`  ${cat}: count=${rec.count} weight=${rec.weight}`);
  }

  fs.writeFileSync(OUT_FILE, JSON.stringify({
    generated_from: DIR,
    excludedFilterApplied: !includeExcluded,
    scopeFilterIdsListed: exclusionKeys.length,
    totalSessions, sessionsWithFriction, tooSmall, errors, excluded,
    categories: ranked.map(([cat, rec]) => ({ category: cat, count: rec.count, weight: rec.weight, examples: rec.examples })),
    biggestPerSession,
  }, null, 2));
  console.log(`Wrote ${OUT_FILE}`);
}

main();
