#!/usr/bin/env node
/**
 * Tempdoc 727: timeline view over mine-friction.mjs output — how friction category
 * counts/weights have moved across session dates, not just the aggregate total.
 *
 * Session date is read from the first timestamped line of the raw transcript (session
 * start), not the friction-results file's mtime (which is when this script last ran).
 *
 * By default, excludes sessions listed in friction-excluded-sessions.json (benchmark-harness
 * bursts, personal/unrelated use, empty sessions, non-coding project work) — scoped to
 * organic developer-agent sessions only. Pass --include-excluded to disable the filter.
 *
 * Usage: node friction-timeline.mjs [--project-dir <path>] [--bucket day|3day|week] [--include-excluded]
 */

import fs from 'node:fs';
import path from 'node:path';
import { loadExclusionKeys, makeExclusionMatcher, fmtScopeExclusion } from './lib/telemetry-io.mjs';

const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const repoRoot = path.resolve(SCRIPT_DIR, '..', '..');
const RESULTS_DIR = path.join(repoRoot, 'tmp', 'agent-telemetry', 'friction-results');
const OUT_FILE = path.join(repoRoot, 'tmp', 'agent-telemetry', 'friction-timeline.json');

function parseArgs() {
  const args = process.argv.slice(2);
  const get = (name, def) => {
    const i = args.indexOf(name);
    return i !== -1 && args[i + 1] ? args[i + 1] : def;
  };
  return {
    projectDir: get('--project-dir', defaultProjectDir()),
    bucket: get('--bucket', 'day'),
  };
}

function defaultProjectDir() {
  const home = process.env.HOME || process.env.USERPROFILE;
  const slug = repoRoot.replace(/[:\\/]/g, '-');
  return path.join(home, '.claude', 'projects', slug);
}

function sessionStartDate(transcriptPath) {
  let content;
  try {
    content = fs.readFileSync(transcriptPath, 'utf8');
  } catch {
    return null;
  }
  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }
    if (entry.timestamp) return entry.timestamp; // first timestamped line = session start
  }
  return null;
}

function bucketKey(isoDate, bucket) {
  const d = new Date(isoDate);
  if (bucket === 'day') return d.toISOString().slice(0, 10);
  if (bucket === 'week') {
    // ISO week start (Monday)
    const day = (d.getUTCDay() + 6) % 7;
    const monday = new Date(d);
    monday.setUTCDate(d.getUTCDate() - day);
    return monday.toISOString().slice(0, 10);
  }
  if (bucket === '3day') {
    const epochDay = Math.floor(d.getTime() / (86400 * 1000));
    const bucketStart = Math.floor(epochDay / 3) * 3;
    return new Date(bucketStart * 86400 * 1000).toISOString().slice(0, 10);
  }
  return d.toISOString().slice(0, 10);
}

const costWeight = { low: 1, medium: 2, high: 3 };
const EXCLUSIONS_FILE = path.join(SCRIPT_DIR, 'friction-excluded-sessions.json');

function main() {
  const opts = parseArgs();
  const includeExcluded = process.argv.includes('--include-excluded');
  // Keys are loaded even when the filter is off, so the report can state the
  // denominator rather than an unexplained zero (tempdoc 858 §7).
  const exclusionKeys = loadExclusionKeys(EXCLUSIONS_FILE);
  const isExcluded = includeExcluded ? () => false : makeExclusionMatcher(exclusionKeys);
  const files = fs.readdirSync(RESULTS_DIR).filter(f => f.endsWith('.json'));

  const byBucket = new Map(); // bucketKey -> { sessions, sessionsWithFriction, categories: Map }
  let missingTimestamp = 0, excluded = 0;

  for (const f of files) {
    const j = JSON.parse(fs.readFileSync(path.join(RESULTS_DIR, f), 'utf8'));
    if (isExcluded(j.sessionId)) { excluded++; continue; }
    if (j.tooSmall || j.error || !j.evaluation) continue;

    const transcriptPath = path.join(opts.projectDir, `${j.sessionId}.jsonl`);
    const startIso = sessionStartDate(transcriptPath);
    if (!startIso) { missingTimestamp++; continue; }
    const key = bucketKey(startIso, opts.bucket);

    if (!byBucket.has(key)) byBucket.set(key, { sessions: 0, sessionsWithFriction: 0, categories: new Map() });
    const rec = byBucket.get(key);
    rec.sessions++;
    const ev = j.evaluation;
    if (!ev.no_friction_detected && (ev.friction_incidents?.length ?? 0) > 0) rec.sessionsWithFriction++;
    for (const inc of ev.friction_incidents ?? []) {
      const cat = (inc.category || 'other').toLowerCase().trim();
      if (!rec.categories.has(cat)) rec.categories.set(cat, { count: 0, weight: 0 });
      const c = rec.categories.get(cat);
      c.count++;
      c.weight += costWeight[inc.estimated_cost] ?? 1;
    }
  }

  const buckets = [...byBucket.keys()].sort();
  console.log(`Buckets (${opts.bucket}): ${buckets.length}, sessions with a resolved timestamp: ${[...byBucket.values()].reduce((a, r) => a + r.sessions, 0)}, missing timestamp: ${missingTimestamp}, ${fmtScopeExclusion({ excluded, listed: exclusionKeys.length, disabled: includeExcluded })}`);
  console.log('');
  console.log('date       | sessions | %fric | top categories (weight)');
  console.log('-----------|----------|-------|-------------------------');
  for (const key of buckets) {
    const rec = byBucket.get(key);
    const pct = Math.round(100 * rec.sessionsWithFriction / rec.sessions);
    const top = [...rec.categories.entries()]
      .sort((a, b) => b[1].weight - a[1].weight)
      .slice(0, 4)
      .map(([cat, c]) => `${cat}(${c.weight})`)
      .join(', ');
    console.log(`${key} | ${String(rec.sessions).padStart(8)} | ${String(pct).padStart(4)}% | ${top}`);
  }

  // Per-category time series (for trend inspection / charting)
  const allCategories = new Set();
  for (const rec of byBucket.values()) for (const cat of rec.categories.keys()) allCategories.add(cat);
  const series = {};
  for (const cat of allCategories) {
    series[cat] = buckets.map(key => byBucket.get(key).categories.get(cat)?.weight ?? 0);
  }

  fs.writeFileSync(OUT_FILE, JSON.stringify({
    bucket: opts.bucket,
    buckets,
    perBucket: buckets.map(key => ({
      date: key,
      sessions: byBucket.get(key).sessions,
      sessionsWithFriction: byBucket.get(key).sessionsWithFriction,
      categories: Object.fromEntries(byBucket.get(key).categories),
    })),
    series,
    missingTimestamp,
  }, null, 2));
  console.log(`\nWrote ${OUT_FILE}`);
}

main();
