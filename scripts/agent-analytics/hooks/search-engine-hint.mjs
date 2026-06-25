#!/usr/bin/env node

/**
 * PostToolUse hook for Edit/Write on retrieval-engine source (tempdoc 580 §4c / Q-010
 * relevance ratchet + tempdoc 640 performance ratchet + tempdoc 636 / D-005 recall-leak ratchet).
 *
 * The engine has no continuous CI quality gate the way the UI does (tempdoc 580 §3 —
 * the enforcement asymmetry that caused the relevance freeze). This hook is the
 * lightweight trigger half of the standing engine ratchets: when an agent edits a module that
 * can change ranking quality, performance, OR recall-survival, it nudges the relevance gate
 * (nDCG@10), the perf gate (latency/throughput/footprint), AND the leak gate (cascade-leak rate);
 * when an agent edits the INFERENCE / LLM path (a distinct subject), it nudges the llm-gen gate
 * (TTFT / e2e / tokens-sec — tempdoc 640 L) so a regression on any axis fails loudly instead of coasting.
 *
 * - Synchronous, path-check only (no process spawn), never blocks.
 */

function normalize(p) {
  return p.replace(/\\/g, '/');
}

// Modules whose source can change ranking quality (retrieval, fusion, reranking, encoders).
const ENGINE_PATTERNS = [
  /modules\/adapters-lucene\/src\//,
  /modules\/reranker\/src\//,
  /modules\/worker-services\/src\//,
  /modules\/app-services\/src\/main\/java\/io\/justsearch\/app\/services\/worker\//,
  /modules\/app-services\/src\/main\/java\/io\/justsearch\/app\/services\/gpl\//,
  /modules\/search\/src\//,
  /modules\/app-search\/src\//,
];

function isEngineSource(filePath) {
  const p = normalize(filePath);
  return ENGINE_PATTERNS.some((re) => re.test(p));
}

// Modules whose source can change LLM-GENERATION latency/throughput (the inference path — distinct
// subject from retrieval ranking; tempdoc 640 L). Edits here nudge the llm-gen ratchet, not the
// retrieval ratchets.
const INFERENCE_PATTERNS = [
  /modules\/app-inference\/src\//,
  /modules\/prompt-support\/src\//,
  /modules\/app-services\/src\/main\/java\/io\/justsearch\/app\/services\/conversation\//,
  /modules\/app-services\/src\/main\/java\/io\/justsearch\/app\/services\/inference\//,
];

function isInferenceSource(filePath) {
  const p = normalize(filePath);
  return INFERENCE_PATTERNS.some((re) => re.test(p));
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8');

  try {
    const input = JSON.parse(raw);
    const toolName = input.tool_name;
    if (toolName !== 'Edit' && toolName !== 'Write') return;

    const filePath = input.tool_input?.file_path;
    if (!filePath) return;
    const engine = isEngineSource(filePath);
    const inference = isInferenceSource(filePath);
    if (!engine && !inference) return;

    const blocks = [];
    if (engine) {
      blocks.push([
        `Retrieval-engine source edited — this can change ranking quality (tempdoc 580 Q-010),`,
        `engine performance (tempdoc 640), AND recall-survival (tempdoc 636 / D-005). Re-run a FULL`,
        `measurement eval with the LEG modes (the leak gate reads the staged_recall_accounting`,
        `projection, which needs vector,lexical,splade + the hybrid final in ONE run), then the THREE`,
        `ratchets so an nDCG@10 / latency / recall-leak regression fails loudly:`,
        `  jseval run --start-backend --clean --pipeline --ce --embedding --splade --dataset scifact --modes vector,lexical,splade,hybrid`,
        `  python -m jseval relevance-gate --data-dir <eval-data-dir> --dataset beir/scifact`,
        `  python -m jseval perf-gate      --data-dir <eval-data-dir> --dataset scifact`,
        `  python -m jseval leak-gate      --data-dir <eval-data-dir> --dataset beir/scifact`,
        `(relevance + leak key on beir/scifact; perf + the run use the raw slug scifact — all intended.)`,
        `After a deliberate change, re-pin: perf-gate --update-baseline ...; leak-gate-derive --datasets ...`,
        `Floors: scripts/jseval/{relevance,perf}-ratchet-baselines.v1.json + leak-gate-baselines.v1.json. Load /jseval.`,
      ].join('\n'));
    }
    if (inference) {
      blocks.push([
        `Inference / LLM-path source edited — this can change LLM-GENERATION latency/throughput (tempdoc`,
        `640 L), a different subject from retrieval ranking. Re-run the LLM bench + the llm-gen ratchet`,
        `(needs the AI runtime active) so a TTFT / e2e / tokens-sec regression fails loudly:`,
        `  jseval llm-bench --base-url <api-url> --output-dir <dir>   # with AI active (ai_activate)`,
        `  python -m jseval llm-gate --bench-file <dir>/llm-bench.json`,
        `After a deliberate change, re-pin: llm-gate --bench-file ... --update-baseline.`,
        `Floor: scripts/jseval/llm-gen-ratchet-baselines.v1.json. Load /jseval.`,
      ].join('\n'));
    }
    const hint = blocks.join('\n\n');

    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PostToolUse',
        additionalContext: hint,
      },
    }));
  } catch {
    // Parse failure — no output, don't block
  }
}

main().catch(() => process.exit(0));
