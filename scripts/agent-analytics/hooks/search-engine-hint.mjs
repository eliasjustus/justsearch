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
 * (TTFT / e2e / tokens-sec — tempdoc 640 L); when an agent edits the MCP TOOL SURFACE (a third
 * distinct subject — whether an LLM agent can still successfully DRIVE the retrieval tool, not
 * raw retrieval quality), it nudges the utility gate (tempdoc 673) — so a regression on any axis
 * fails loudly instead of coasting.
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
  // (`modules/search/src/` and `modules/app-search/src/` were listed here until
  // 2026-08-18 — neither module has ever existed in this repo's history, so both
  // patterns were dead weight that could never match. Removed with their settings
  // `if`-clause counterparts.)
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

// The MCP tool surface itself (tempdoc 673) — a THIRD distinct subject from retrieval ranking and
// LLM-generation latency: whether an LLM agent can still successfully DRIVE the JustSearch retrieval
// tool at all (tool description/schema/protocol changes), not raw retrieval quality. Deliberately
// narrower than ENGINE_PATTERNS/INFERENCE_PATTERNS — this is the one surface the utility-gate's
// detection floor is designed to react to, and unlike the other two gates this one costs a real paid
// agent-call run, so the trigger set stays tight (tempdoc 673 §D6/§F5).
const MCP_SURFACE_PATTERNS = [
  /modules\/ui\/src\/main\/java\/io\/justsearch\/ui\/api\/mcp\//,
];

function isMcpSurfaceSource(filePath) {
  const p = normalize(filePath);
  return MCP_SURFACE_PATTERNS.some((re) => re.test(p));
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
    const mcpSurface = isMcpSurfaceSource(filePath);
    if (!engine && !inference && !mcpSurface) return;

    const blocks = [];
    if (engine) {
      blocks.push([
        `Retrieval-engine source edited — this can change ranking quality (tempdoc 580 Q-010),`,
        `engine performance (tempdoc 640), AND recall-survival (tempdoc 636 / D-005). Re-run a FULL`,
        `measurement eval with the LEG modes (the leak gate reads the staged_recall_accounting`,
        `projection, which needs vector,lexical,splade + the hybrid final in ONE run), then the FOUR`,
        `ratchets so an nDCG@10 / latency / recall-leak / recall-completeness regression fails loudly:`,
        `  jseval run --start-backend --clean --pipeline --ce --embedding --splade --dataset scifact --modes vector,lexical,splade,hybrid`,
        `  python -m jseval relevance-gate     --data-dir <eval-data-dir> --dataset beir/scifact`,
        `  python -m jseval perf-gate          --data-dir <eval-data-dir> --dataset scifact`,
        `  python -m jseval leak-gate          --data-dir <eval-data-dir> --dataset beir/scifact`,
        `  python -m jseval union-recall-gate  --data-dir <eval-data-dir> --dataset beir/scifact`,
        `(relevance + leak + union-recall key on beir/scifact; perf + the run use the raw slug scifact — all intended.)`,
        `After a deliberate change, re-pin: perf-gate --update-baseline ...; leak-gate-derive --datasets ...; union-recall-gate-derive --datasets ...`,
        `Floors: scripts/jseval/{relevance,perf}-ratchet-baselines.v1.json + {leak,union-recall}-gate-baselines.v1.json. Load /jseval.`,
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
    if (mcpSurface) {
      blocks.push([
        `MCP tool surface edited — this can change whether an LLM agent can still successfully DRIVE`,
        `the JustSearch retrieval tool (tempdoc 673), a different subject from retrieval ranking or`,
        `LLM-generation latency. Unlike the other two ratchets this one costs a REAL paid agent-call`,
        `run — deliberate/periodic, not auto-run on every edit. If this change could plausibly affect`,
        `tool usability (description/schema/protocol), run the detection gate before merging:`,
        `  python -m jseval utility-gate --record <utility-comparison.v1.json> --corpus golden/util-smoke`,
        `(records come from \`jseval agent-eval\` + \`utility-compose\` against util-smoke/, condition C only`,
        `— see scripts/jseval/util-smoke/README.md; ~$0.20-0.60, needs a live dev stack + claude CLI. Only a`,
        `fabricated/engineered corpus can be gated here — tempdoc 673 D8 refuses a realistic one by default.)`,
        `After a deliberate change, re-pin: utility-gate --record ... --update-baseline.`,
        `Floor: scripts/jseval/utility-ratchet-baselines.v1.json. Load /jseval.`,
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
