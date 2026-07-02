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

const log = makeLogger('onramp-smoke');

async function main() {
  try {
    log('starting dev stack (--clean hard)…');
    const started = await startStack({ repoRoot, devRunner });
    const base = started.apiBaseUrl;
    log(`stack up at ${base}; ingesting demo corpus…`);
    log(`querying "${DEMO_QUERY}"…`);

    const { results, mode } = await stageAndVerify({
      base, corpusPath: corpus, query: DEMO_QUERY,
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
