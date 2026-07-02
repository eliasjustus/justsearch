#!/usr/bin/env node
/**
 * Tempdoc 656 O4 — the onramp's runnable proof.
 *
 * Asserts, re-runnably, that a developer/agent reaches a FIRST SUCCESS: start the stack, ingest the
 * bundled demo corpus (examples/onramp-corpus), and get a real search result back. This is the
 * onramp's "it works" evidence — not a doc claim (the Boundary's explicit ask). Tier 0 (keyword
 * search returning a hit) is the guaranteed floor and is asserted unconditionally; the reached tier
 * (per scripts/dev/doctor.mjs) is reported for context.
 *
 * Integration smoke (starts a real stack; needs installDist). Run on demand:
 *   node scripts/dev/test-onramp-first-success.mjs
 * Exits 0 on first-success, 1 on failure. Always tears the stack down.
 */
'use strict';
import { spawn, execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const devRunner = path.join(__dirname, 'dev-runner.cjs');
const corpus = path.join(repoRoot, 'examples', 'onramp-corpus');
const DEMO_QUERY = 'cinnamon heist'; // matches examples/onramp-corpus/cinnamon.md
// Indexing-settle poll budget: 90s by default (a cold windows-latest CI runner is slower than a warm
// local dev box); override with JUSTSEARCH_SETTLE_TIMEOUT_S for local runs.
const SETTLE_TIMEOUT_S = Number(process.env.JUSTSEARCH_SETTLE_TIMEOUT_S) || 90;

function log(m) { console.error(`[onramp-smoke] ${m}`); }
async function getJson(base, route, opts) {
  const res = await fetch(base + route, { signal: AbortSignal.timeout(15000), ...opts });
  if (!res.ok) throw new Error(`${route} → HTTP ${res.status}`);
  return res.json();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Spawn `dev-runner start`, resolve with {apiBaseUrl} once the ok:true JSON line is seen. */
function startStack() {
  return new Promise((resolve, reject) => {
    const child = spawn('node', [devRunner, 'start', '--clean', 'hard', '--json'],
      { cwd: repoRoot, stdio: ['ignore', 'pipe', 'inherit'] });
    let buf = '';
    const timer = setTimeout(() => { child.kill(); reject(new Error('stack start timed out')); }, 240000);
    child.stdout.on('data', (d) => {
      buf += d.toString();
      const line = buf.split('\n').find((l) => l.includes('"apiPort"'));
      if (line) {
        try {
          const r = JSON.parse(line.trim());
          if (r.ok && r.apiPort) { clearTimeout(timer); resolve({ apiBaseUrl: r.apiBaseUrl || `http://127.0.0.1:${r.apiPort}`, child }); }
        } catch { /* partial line */ }
      }
    });
    child.on('exit', (code) => { clearTimeout(timer); reject(new Error(`dev-runner exited early (${code})`)); });
  });
}
function stopStack() {
  try { execFileSync('node', [devRunner, 'stop', '--active', '--json'], { cwd: repoRoot, stdio: 'ignore' }); }
  catch { /* best-effort teardown */ }
}

async function main() {
  let started;
  try {
    log('starting dev stack (--clean hard)…');
    started = await startStack();
    const base = started.apiBaseUrl;
    log(`stack up at ${base}; ingesting demo corpus…`);

    const ing = await getJson(base, '/api/knowledge/ingest', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ paths: [corpus] }),
    });
    if (!(ing.accepted > 0)) throw new Error(`ingest accepted ${ing.accepted} docs (expected > 0)`);

    // Wait for indexing to settle (pending 0 + IDLE). Fail LOUDLY on timeout instead of falling
    // through silently — a silent fallthrough here previously surfaced as a misleading "query
    // returned 0 results" failure later, hiding the real cause (indexing never settled).
    let settled = false;
    for (let i = 0; i < SETTLE_TIMEOUT_S; i++) {
      const s = await getJson(base, '/api/status');
      const c = s.worker?.core;
      if (c && c.pendingJobs === 0 && c.indexState === 'IDLE' && c.indexedDocuments > 0) { settled = true; break; }
      await sleep(1000);
    }
    if (!settled) {
      throw new Error(`indexing did not settle within ${SETTLE_TIMEOUT_S}s — increase timeout or check ingest; NOT a search failure`);
    }

    log(`querying "${DEMO_QUERY}"…`);
    const r = await getJson(base, '/api/knowledge/search', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: DEMO_QUERY, limit: 5 }),
    });
    const results = r.results || [];
    const mode = r.searchTrace?.effectiveMode;

    // Tier 0 (the guaranteed floor): a keyword query returns a real hit. Asserted unconditionally.
    if (results.length < 1) throw new Error(`FIRST-SUCCESS FAILED: query returned 0 results (mode=${mode})`);
    if (!mode) throw new Error('FIRST-SUCCESS FAILED: no searchTrace.effectiveMode');

    // Ask the doctor which tier this environment is at (drives the conditional higher-tier check).
    let tier = '(unknown)';
    try { tier = JSON.parse(execFileSync('node', [path.join(__dirname, 'doctor.mjs'), '--json'], { cwd: repoRoot }).toString()).tier; } catch { /* best-effort */ }

    // Tier 1 (conditional): when the embedding model is present (tier ≥ 1), the semantic path must
    // actually engage — the query must NOT have fallen back to pure keyword (TEXT) mode. Deterministic.
    // (Tier 2's cited answer is intentionally NOT asserted here — it's LLM-dependent, slow and flaky.)
    if (typeof tier === 'number' && tier >= 1 && mode === 'TEXT') {
      throw new Error(`TIER-1 FAILED: embedding present (tier ${tier}) but query ran in TEXT mode — semantic retrieval did not engage`);
    }

    console.log(`\nOK  onramp first-success: ingested demo corpus → query "${DEMO_QUERY}" returned ${results.length} result(s) in ${mode} mode (tier ${tier}).\n`);
  } catch (err) {
    console.error(`\nFAIL  ${err.message}\n`);
    process.exitCode = 1;
  } finally {
    log('stopping dev stack…');
    stopStack();
  }
}

main();
