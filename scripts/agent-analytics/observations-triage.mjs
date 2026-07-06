#!/usr/bin/env node

/**
 * Observations triage — the conditions store's READ MODEL and JANITOR
 * (tempdoc 680; the named-consumer feedstock).
 *
 *   node scripts/agent-analytics/observations-triage.mjs             # read-model (triage view)
 *   node scripts/agent-analytics/observations-triage.mjs --json     # same, machine-readable
 *   node scripts/agent-analytics/observations-triage.mjs --probe    # janitor: run condition probes
 *   node scripts/agent-analytics/observations-triage.mjs --probe --slow   # include `slow:` probes
 *
 * Probe semantics: a condition's `probe` is a command; **exit 0 means the
 * condition is GONE** — the janitor then writes `status: proposed-retire
 * (probe passed <date>)` into the store. It never deletes: deletion is a human
 * act at the triage pass (propose-then-accept, the repo's rebalance shape).
 * A nonzero exit re-affirms the condition (recorded as still-true).
 *
 * The janitor also checks `expected-state.v1.json` entries that carry an
 * `exitProbe`: exit 0 there means the pin's exit condition fired — reported as
 * a proposed removal (report-only; the baseline file is edited by a human).
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { repoRoot } from './lib/telemetry-io.mjs';
import { parseStore, serializeStore } from './lib/observations-store.mjs';

export const STORE_FILE = 'docs/observations.md';
export const EXPECTED_STATE_FILE = 'scripts/agent-analytics/expected-state.v1.json';

const isRetireProposed = (g) => /^proposed-retire/.test(g.fields.status || '');
const isParked = (g) => /^parked/.test(g.fields.status || '');
const isProposedKind = (g) => /\?$/.test(g.fields.kind || '');

/** Build the triage read-model from a parsed store. */
export function readModel(store) {
  const open = store.groups.filter((g) => !isRetireProposed(g) && !isParked(g));
  const kindCounts = {};
  for (const g of store.groups) {
    const k = (g.fields.kind || 'unknown').replace(/\?$/, '');
    kindCounts[k] = (kindCounts[k] || 0) + 1;
  }
  const bySeen = [...open].sort((a, b) => (parseInt(b.fields.seen, 10) || 0) - (parseInt(a.fields.seen, 10) || 0));
  const byLast = [...open].filter((g) => g.fields.last).sort((a, b) => b.fields.last.localeCompare(a.fields.last));
  return {
    depth: store.groups.length,
    open: open.length,
    proposedRetire: store.groups.filter(isRetireProposed).map((g) => ({ slug: g.slug, status: g.fields.status, title: g.title })),
    parked: store.groups.filter(isParked).map((g) => ({ slug: g.slug, status: g.fields.status, title: g.title })),
    needsKindConfirm: open.filter(isProposedKind).length,
    kinds: kindCounts,
    topBySeen: bySeen.slice(0, 15).map((g) => ({ slug: g.slug, seen: parseInt(g.fields.seen, 10) || 0, kind: g.fields.kind, title: g.title })),
    newest: byLast.slice(0, 10).map((g) => ({ slug: g.slug, last: g.fields.last, kind: g.fields.kind, title: g.title })),
    probeable: open.filter((g) => g.fields.probe).length,
  };
}

/**
 * Janitor: run probes for non-parked, non-already-proposed conditions.
 * exit 0 => write `status: proposed-retire (probe passed <date>)`.
 * Returns {ran, retireProposed:[], stillTrue:[], skippedSlow, errors:[]}.
 */
export function runProbes({ root = repoRoot, slow = false, timeoutMs = 180_000, today = new Date().toISOString().slice(0, 10) } = {}) {
  const storePath = path.join(root, STORE_FILE);
  const store = parseStore(fs.readFileSync(storePath, 'utf8'));
  if (!store) throw new Error(`observations-triage: ${STORE_FILE} has no '## Conditions' section`);
  const out = { ran: 0, retireProposed: [], stillTrue: [], skippedSlow: 0, errors: [] };
  for (const g of store.groups) {
    const probe = g.fields.probe;
    if (!probe || isParked(g) || isRetireProposed(g)) continue;
    const isSlow = /^slow:\s*/.test(probe);
    if (isSlow && !slow) { out.skippedSlow += 1; continue; }
    const cmd = probe.replace(/^slow:\s*/, '');
    let res;
    try {
      res = spawnSync(cmd, { shell: true, cwd: root, timeout: timeoutMs, stdio: 'ignore' });
    } catch (e) {
      out.errors.push({ slug: g.slug, error: e.message });
      continue;
    }
    out.ran += 1;
    if (res.error) { out.errors.push({ slug: g.slug, error: res.error.message }); continue; }
    if (res.status === 0) {
      g.fields.status = `proposed-retire (probe passed ${today})`;
      out.retireProposed.push(g.slug);
    } else {
      out.stillTrue.push(g.slug);
    }
  }
  if (out.retireProposed.length) fs.writeFileSync(storePath, serializeStore(store), 'utf8');
  return out;
}

/** Check expected-state pins whose exitProbe fired (report-only). */
export function checkExpectedStateExits({ root = repoRoot, slow = false, timeoutMs = 180_000 } = {}) {
  const file = path.join(root, EXPECTED_STATE_FILE);
  if (!fs.existsSync(file)) return { checked: 0, exitFired: [] };
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  const out = { checked: 0, exitFired: [] };
  for (const e of data.entries ?? []) {
    if (!e.exitProbe) continue;
    const isSlow = /^slow:\s*/.test(e.exitProbe);
    if (isSlow && !slow) continue;
    const res = spawnSync(e.exitProbe.replace(/^slow:\s*/, ''), { shell: true, cwd: root, timeout: timeoutMs, stdio: 'ignore' });
    out.checked += 1;
    if (res.status === 0) out.exitFired.push(e.id);
  }
  return out;
}

function printReadModel(m) {
  const line = (g) => `  ${String(g.seen ?? '').padStart(3)}${g.seen !== undefined ? '×' : ''} ${g.slug}${g.kind ? `  [${g.kind}]` : ''}${g.last ? `  (last ${g.last})` : ''}\n      ${g.title.slice(0, 110)}`;
  console.log(`observations-triage — store depth ${m.depth} (${m.open} open, ${m.proposedRetire.length} proposed-retire, ${m.parked.length} parked)`);
  console.log(`kinds: ${Object.entries(m.kinds).map(([k, v]) => `${k} ${v}`).join(', ')} | kind-confirmations pending: ${m.needsKindConfirm} | probeable: ${m.probeable}`);
  console.log('\n== TOP OPEN BY SEEN (the fleet-tax ranking) ==');
  for (const g of m.topBySeen) console.log(line(g));
  console.log('\n== NEWEST (by last-seen) ==');
  for (const g of m.newest) console.log(line(g));
  if (m.proposedRetire.length) {
    console.log('\n== PROPOSED RETIREMENTS (accept by deleting the condition; evidence inline) ==');
    for (const g of m.proposedRetire) console.log(`  ${g.slug} — ${g.status}`);
  }
  if (m.parked.length) {
    console.log('\n== PARKED (revisit when the stated trigger fires) ==');
    for (const g of m.parked) console.log(`  ${g.slug} — ${g.status}`);
  }
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--probe')) {
    const r = runProbes({ slow: args.includes('--slow') });
    console.log(`observations-triage --probe: ran ${r.ran} probe(s); ${r.retireProposed.length} retirement(s) proposed` +
      `${r.skippedSlow ? `; ${r.skippedSlow} slow probe(s) skipped (pass --slow)` : ''}${r.errors.length ? `; ${r.errors.length} probe error(s)` : ''}`);
    for (const s of r.retireProposed) console.log(`  proposed-retire: ${s}`);
    if (r.stillTrue.length) console.log(`  still true: ${r.stillTrue.join(', ')}`);
    for (const e of r.errors) console.log(`  probe error: ${e.slug} — ${e.error}`);
    const es = checkExpectedStateExits({ slow: args.includes('--slow') });
    if (es.checked) {
      console.log(`expected-state: ${es.checked} exit-probe(s) checked; ${es.exitFired.length} pin(s) whose exit condition FIRED` +
        (es.exitFired.length ? ` — propose removing: ${es.exitFired.join(', ')}` : ''));
    }
    return;
  }
  const store = parseStore(fs.readFileSync(path.join(repoRoot, STORE_FILE), 'utf8'));
  if (!store) throw new Error(`observations-triage: ${STORE_FILE} has no '## Conditions' section`);
  const m = readModel(store);
  if (args.includes('--json')) console.log(JSON.stringify(m, null, 2));
  else printReadModel(m);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'))) {
  main();
}
