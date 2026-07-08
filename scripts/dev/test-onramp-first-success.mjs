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
 * The ingest→poll→query→assert mechanics live in scripts/dev/lib/stage-reference-corpus.mjs
 * (tempdoc 669), shared with scripts/dev/stage-demo-corpus.mjs so this smoke test and the demo-corpus
 * staging script don't carry two drifting copies of the same logic.
 *
 * Integration smoke (starts a real stack; needs installDist). Run on demand:
 *   node scripts/dev/test-onramp-first-success.mjs
 * Exits 0 on first-success, 1 on failure. Always tears the stack down.
 */
'use strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeLogger, startStack, stopStack, stageAndVerify, getTier } from './lib/stage-reference-corpus.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const devRunner = path.join(__dirname, 'dev-runner.cjs');
const doctorPath = path.join(__dirname, 'doctor.mjs');
const corpus = path.join(repoRoot, 'examples', 'onramp-corpus');
const DEMO_QUERY = 'cinnamon heist'; // matches examples/onramp-corpus/cinnamon.md
// Indexing-settle poll budget: 90s by default (a cold windows-latest CI runner is slower than a warm
// local dev box); override with JUSTSEARCH_SETTLE_TIMEOUT_S for local runs.
const SETTLE_TIMEOUT_S = Number(process.env.JUSTSEARCH_SETTLE_TIMEOUT_S) || 90;
// Stack-up latency guard (tempdoc 656 §I/K4): the port-wait bug (§I) hid for a long time because nothing
// asserted startup *speed* — it read as "eventually up" under any generous budget. Measured post-fix
// stack-up is ~5s on CI; 90s is ~18x margin yet well below the ≥120s "burning a full timeout" regression
// class this guards. Override with JUSTSEARCH_ONRAMP_MAX_STACKUP_MS (raise it if a genuinely cold runner
// ever approaches the default — do not tighten).
const MAX_STACKUP_MS = Number(process.env.JUSTSEARCH_ONRAMP_MAX_STACKUP_MS) || 90000;

const log = makeLogger('onramp-smoke');

async function main() {
  try {
    log('starting dev stack (--clean hard)…');
    const startedAtMs = Date.now();
    const started = await startStack({ repoRoot, devRunner });
    const stackUpMs = Date.now() - startedAtMs;
    const base = started.apiBaseUrl;
    log(`stack up at ${base} in ${(stackUpMs / 1000).toFixed(1)}s; ingesting demo corpus…`);
    // K4 latency guard: fail loudly on a startup-speed regression the generous startStack budget would miss
    // (e.g. a return of the §I full-timeout burn). Excludes the assemble (a separate CI step / pre-built).
    if (stackUpMs > MAX_STACKUP_MS) {
      throw new Error(`STACK-UP TOO SLOW: ${(stackUpMs / 1000).toFixed(1)}s > ${(MAX_STACKUP_MS / 1000).toFixed(0)}s budget — likely a startup regression (see tempdoc 656 §I). Raise JUSTSEARCH_ONRAMP_MAX_STACKUP_MS only if a cold runner is legitimately this slow.`);
    }
    log(`querying "${DEMO_QUERY}"…`);

    const { results, mode } = await stageAndVerify({
      base, corpusPath: corpus, query: DEMO_QUERY,
      pollAttempts: SETTLE_TIMEOUT_S,
      failLabel: 'FIRST-SUCCESS FAILED',
    });

    // Ask the doctor which tier this environment is at (drives the conditional higher-tier check).
    const tier = getTier({ repoRoot, doctorPath });

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
    stopStack({ repoRoot, devRunner });
  }
}

main();
