#!/usr/bin/env node
/**
 * Tempdoc 669 — demo-corpus staging: get examples/demo-corpus to a known-ready
 * indexed state so public demo assets (screenshots, a GIF/video of a cited
 * answer over a messy multilingual corpus) can be reproducibly recorded.
 *
 * Not CI-wired (unlike scripts/dev/test-onramp-first-success.mjs, which
 * onramp-smoke.yml calls) — this is the founder/dev-facing demo-staging
 * entry point, matching the settled design's "marketing/dev substrate only,
 * not in-product" scope decision. Shares the underlying ingest/poll/canary
 * mechanics with the onramp script via scripts/dev/lib/stage-reference-corpus.mjs
 * rather than duplicating them.
 *
 * Run on demand:
 *   node scripts/dev/stage-demo-corpus.mjs
 * Exits 0 on success, 1 on failure. Always tears the stack down.
 */
'use strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeLogger, startStack, stopStack, stageAndVerify, getJson, getTier } from './lib/stage-reference-corpus.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..', '..');
const devRunner = path.join(__dirname, 'dev-runner.cjs');
const doctorPath = path.join(__dirname, 'doctor.mjs');
const corpus = path.join(repoRoot, 'examples', 'demo-corpus');
const signaturePath = path.join(corpus, 'corpus-signature.json');
const CANARY_QUERY = 'obsidian ledger'; // matches examples/demo-corpus/verrenmoor-customs.md
// Distinct from the primary canary: proves the corpus's actual reason for existing over the
// onramp's (multilingual content), deterministically — a literal non-English phrase is findable
// via plain BM25/ICU matching regardless of tier (Hard Invariant #6, locale-invariant analysis).
const MULTILINGUAL_QUERY = 'Moulin de Brume';
const MULTILINGUAL_EXPECTED_DOC = 'moulin-de-brume.md';
// Informational OCR check (see below) — a phrase from the fabricated manifest text.
const OCR_QUERY = 'Halyard Wren';
const OCR_EXPECTED_DOC = 'weathered-manifest.jpg';
const RAG_QUESTION = 'What was unusual about the shipment logged at the Verrenmoor Customs House?';
const RAG_EXPECTED_FRAGMENT = /obsidian|aldric/i; // informational only, see below

const log = makeLogger('demo-corpus-stage');

/**
 * Recompute the corpus's content signature the same way `corpus_signature()`
 * (scripts/jseval/jseval/corpus_identity.py, `files=` mode) would — sha256 of
 * the concatenated file bytes, in the exact order recorded in the committed
 * `corpus-signature.json`'s own `files` array (so this check is driven by the
 * manifest, not a second hardcoded file list) — and compare. Warns, doesn't
 * hard-fail: a corpus identity check must never be the thing that blocks
 * staging (same philosophy as corpus_identity.py's own docstring).
 */
function verifyCorpusSignature() {
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(signaturePath, 'utf8'));
  } catch (err) {
    log(`WARNING: could not read corpus-signature.json (${err.message}) — skipping integrity check`);
    return;
  }
  const hash = createHash('sha256');
  for (const file of manifest.files || []) {
    hash.update(readFileSync(path.join(corpus, file)));
  }
  const actual = hash.digest('hex');
  if (actual !== manifest.signature) {
    log(`WARNING: corpus-signature.json mismatch — expected ${manifest.signature}, computed ${actual}. `
      + 'The bundled corpus does not match what was last verified; recompute corpus-signature.json if this is intentional.');
  } else {
    log('corpus-signature.json verified — bundled corpus matches the recorded signature.');
  }
}

/**
 * POST /api/chat/ask (SSE-only endpoint, tempdoc 669 confidence-building
 * pass confirmed there's no non-streaming mode) and collect events until the
 * terminal `done` (or `error`) event. Returns the `done` payload, which
 * carries the merged `citations[]` array (parentDocId/excerpt/etc.).
 */
async function askRagCited({ base, question, docIds, timeoutMs = 240000 }) {
  const res = await fetch(base + '/api/chat/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, docIds, topK: 5 }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`/api/chat/ask → HTTP ${res.status}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let doneEvent = null;
  let errorEvent = null;
  while (!doneEvent && !errorEvent) {
    const { value, done: streamDone } = await reader.read();
    if (streamDone) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) !== -1) {
      const block = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const lines = block.split('\n');
      const eventLine = lines.find((l) => l.startsWith('event:'));
      const dataLine = lines.find((l) => l.startsWith('data:'));
      if (!eventLine || !dataLine) continue;
      const eventName = eventLine.slice('event:'.length).trim();
      let data;
      try { data = JSON.parse(dataLine.slice('data:'.length).trim()); } catch { continue; }
      if (eventName === 'done') doneEvent = data;
      if (eventName === 'error') errorEvent = data;
    }
  }
  if (errorEvent) {
    const err = new Error(`RAG ask failed: ${errorEvent.error || errorEvent.errorCode || 'unknown'}`);
    err.errorCode = errorEvent.errorCode;
    throw err;
  }
  if (!doneEvent) throw new Error('RAG ask stream ended without a done event');
  return doneEvent;
}

async function main() {
  try {
    verifyCorpusSignature();

    log('starting dev stack (--clean hard)…');
    const started = await startStack({ repoRoot, devRunner });
    const base = started.apiBaseUrl;
    log(`stack up at ${base}; ingesting demo corpus…`);
    log(`querying "${CANARY_QUERY}"…`);

    // Larger poll budget than the onramp's default 30×1000ms: OCR's own
    // per-file timeout (OcrRoutingConfig.perFileTimeoutMs, default 30000ms)
    // alone can approach the onramp's entire window, and this corpus has
    // more files across more formats. 180×1000ms = 3 minutes.
    const { results, mode } = await stageAndVerify({
      base, corpusPath: corpus, query: CANARY_QUERY,
      pollAttempts: 180, pollIntervalMs: 1000,
      failLabel: 'DEMO-STAGING FAILED',
    });

    // Multilingual canary (hard assertion): distinct from the primary canary,
    // this is what actually proves the corpus's reason for existing over the
    // onramp's (tempdoc 669 Purpose: "multilingual retrieval, OCR, citations
    // over heterogeneous files"). Deterministic regardless of tier — a
    // literal non-English phrase is findable via plain keyword/ICU matching
    // (Hard Invariant #6), no embedding model required.
    log(`querying "${MULTILINGUAL_QUERY}" (multilingual check)…`);
    const mlResult = await getJson(base, '/api/knowledge/search', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: MULTILINGUAL_QUERY, limit: 5 }),
    });
    const mlResults = mlResult.results || [];
    if (!mlResults.some((r) => (r.path || r.id || '').toLowerCase().endsWith(MULTILINGUAL_EXPECTED_DOC))) {
      throw new Error(`MULTILINGUAL-CHECK FAILED: query "${MULTILINGUAL_QUERY}" did not surface ${MULTILINGUAL_EXPECTED_DOC}`);
    }
    log(`multilingual check OK — "${MULTILINGUAL_QUERY}" surfaced ${MULTILINGUAL_EXPECTED_DOC}.`);

    // OCR check (informational only — OCR engine availability is genuinely
    // environment-dependent; this sandbox has none staged anywhere, per
    // tempdoc 669's implementation notes). Mirrors the AI_OFFLINE pattern
    // below: report honestly, never a hard failure for a missing local
    // engine.
    const status = await getJson(base, '/api/status');
    const visualExtraction = status.worker?.visualExtraction;
    let ocrNote = '(OCR status unknown — /api/status did not report visualExtraction)';
    if (visualExtraction) {
      if (!visualExtraction.ocrEngineAvailable) {
        ocrNote = `OCR unavailable in this environment (${visualExtraction.ocrBlockedReason || 'engine not available'}) — OCR extraction not exercised`;
      } else {
        const ocrResult = await getJson(base, '/api/knowledge/search', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query: OCR_QUERY, limit: 5 }),
        });
        const ocrFound = (ocrResult.results || []).some((r) => (r.path || r.id || '').toLowerCase().endsWith(OCR_EXPECTED_DOC));
        ocrNote = ocrFound
          ? `OCR extraction confirmed — "${OCR_QUERY}" surfaced ${OCR_EXPECTED_DOC}`
          : `OCR engine available but "${OCR_QUERY}" did not surface ${OCR_EXPECTED_DOC} (informational only)`;
      }
    }
    log(ocrNote);

    const tier = getTier({ repoRoot, doctorPath });

    // Tier 1 (conditional, mirrors the onramp's own check): when the
    // embedding model is present, the canary query must not have fallen back
    // to pure keyword (TEXT) mode.
    if (typeof tier === 'number' && tier >= 1 && mode === 'TEXT') {
      throw new Error(`TIER-1 FAILED: embedding present (tier ${tier}) but query ran in TEXT mode — semantic retrieval did not engage`);
    }

    let ragNote = '(tier < 2, cited-answer path not exercised)';
    if (typeof tier === 'number' && tier >= 2) {
      log('tier 2: asking a cited question over the demo corpus…');
      let doneEvent;
      try {
        // Scoped to the demo corpus's own files (from corpus-signature.json's
        // manifest) — without this, the app's built-in help docs can
        // out-rank the demo content in retrieval and the citation check
        // would pass while citing the wrong document (observed live).
        const manifest = JSON.parse(readFileSync(signaturePath, 'utf8'));
        const docIds = (manifest.files || []).map((f) => path.join(corpus, f));
        doneEvent = await askRagCited({ base, question: RAG_QUESTION, docIds });
      } catch (err) {
        // doctor's tier is a static "packages installed" signal, not "the
        // inference runtime is currently warm" — AI_OFFLINE means the
        // latter hasn't happened yet (e.g. ai_activate wasn't run before
        // staging). That's a precondition gap, not a demo-corpus regression,
        // so it's reported, not a hard failure (mirrors the onramp script's
        // own "Tier 2 is LLM-dependent, slow and flaky" rationale for not
        // asserting it unconditionally).
        if (err.errorCode === 'AI_OFFLINE') {
          ragNote = '(tier 2 installed but AI runtime not active — run ai_activate before staging to exercise the cited-answer path)';
          log(ragNote);
          doneEvent = null;
        } else {
          throw err;
        }
      }
      if (doneEvent) {
        const citations = doneEvent.citations || [];
        // Hard requirement once the runtime is actually warm: RAG engaged
        // and produced at least one citation. Structural, deterministic
        // across model versions.
        if (citations.length < 1) {
          throw new Error('TIER-2 FAILED: cited-answer query returned zero citations');
        }
        // Soft/informational: does a citation actually match the expected
        // fact? LLM phrasing/excerpt selection isn't guaranteed stable
        // across model versions (see tempdoc 669's determinism-scope
        // discussion), so this is reported, not asserted.
        const matched = citations.some((c) => RAG_EXPECTED_FRAGMENT.test(c.excerpt || ''));
        ragNote = matched
          ? `cited answer: ${citations.length} citation(s), expected fact confirmed in an excerpt`
          : `cited answer: ${citations.length} citation(s), but expected fact not found verbatim in any excerpt (informational only)`;
        log(ragNote);
      }
    }

    console.log(`\nOK  demo-corpus staged: query "${CANARY_QUERY}" returned ${results.length} result(s) in ${mode} mode (tier ${tier}); multilingual check passed. ${ocrNote}. ${ragNote}\n`);
  } catch (err) {
    console.error(`\nFAIL  ${err.message}\n`);
    process.exitCode = 1;
  } finally {
    log('stopping dev stack…');
    stopStack({ repoRoot, devRunner });
  }
}

main();
