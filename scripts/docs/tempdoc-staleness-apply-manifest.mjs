#!/usr/bin/env node
// Apply per-doc staleness-review decisions from a manifest.
//
// One-shot — written for the Shape-2 ambiguous-cohort triage on
// 2026-05-18. Each entry: { file, target, reason }. The script:
//   1. Rewrites YAML `status:` to `target`
//   2. Rewrites inline `**Status**:` to `target` if present
//   3. Appends a per-doc `## Staleness review (YYYY-MM-DD)` note
//      with the supplied `reason`
//
// Idempotent: skips files that already have a staleness-review note.

import fs from 'node:fs';

const TODAY = new Date().toISOString().slice(0, 10);

const MANIFEST = [
  // === Search-quality investigations / strategy ===
  { file: 'docs/tempdocs/245-execution-log.md', target: 'done',
    reason: 'Agent execution log for the 245 search/index quality strategy. The logged work (multi-dataset evals, LambdaMART training, comparison table) completed. The log itself is terminal — its purpose was to record what ran, and it did.' },
  { file: 'docs/tempdocs/245-search-quality-strategy.md', target: 'done',
    reason: 'External-validation research across 10 retrieval systems (Anserini, Pyserini, Vespa, Infinity, Qdrant, Milvus, Docling, qmd, SentenceTransformers, OpenSearch). The validation phase concluded; the findings are the artifact and are also cross-referenced from the 249-* findings cluster.' },
  { file: 'docs/tempdocs/227-agent-quality-improvement.md', target: 'done',
    reason: 'Agent-quality analysis (1196 lines) producing concrete recommendations on Phase 2 oracle enforcement (relax firstToolCallOracle, promote requiredToolSuccess). Recommendations were the deliverable; whether each was picked up is downstream work.' },
  { file: 'docs/tempdocs/258-search-quality-direction-program.md', target: 'done',
    reason: 'Coordination/synthesis doc that explicitly assesses other tempdocs (252, 253, 259, 260) and gives forward direction. The synthesis IS the closure — the doc completed its role of programming the next phase.' },
  { file: 'docs/tempdocs/313-search-quality-profiling.md', target: 'done',
    reason: 'Profiling tempdoc with eval-dataset selections (NFCorpus, ArguAna) and dependency list. Profiling-phase artifact; downstream tempdocs (e.g., 343 baseline refresh) consumed its outputs.' },
  { file: 'docs/tempdocs/252-ingestion-quality.md', target: 'done',
    reason: 'Ingestion-pipeline strategy (1311 lines) with explicit non-goals (no Tika replacement, no real-time speed focus). Strategy concluded. Subsequent tika/extraction work (per recent commits like the structured extractor changes) consumed the direction.' },
  { file: 'docs/tempdocs/343-search-quality-baseline-refresh.md', target: 'done',
    reason: 'Baseline refresh plan with critical-files table pointing at search-quality-register.md updates. The baseline refresh as a planning artifact reached its conclusion; ongoing baseline maintenance is per the search-quality register, not this tempdoc.' },
  { file: 'docs/tempdocs/345-rag-and-similar-considerations.md', target: 'done',
    reason: 'RAG design doc (2920 lines) with cross-lingual quality analysis and Implementation Plan tiers. Design phase concluded — the constraints (30-50 point Hits@20 drop for cross-lingual) are documented; the MCP API surface decision is captured.' },
  { file: 'docs/tempdocs/365-evidence-capture-rationalization.md', target: 'done',
    reason: 'Agent UI feedback loop design with fixture-update plan for the 364 frontend-types landing. Design captured; the 364 fixture work has presumably landed (per recent slice activity).' },

  // === SPLADE / throughput work ===
  { file: 'docs/tempdocs/266-splade-throughput-architecture.md', target: 'done',
    reason: 'SPLADE throughput architecture (337 lines) referencing ORT CUDA EP, DJL WorkLoadManager, Triton, TensorRT-RTX. Architecture-exploration tempdoc; current SPLADE in `modules/indexer-worker/splade/` either consumed or evolved past the proposals.' },
  { file: 'docs/tempdocs/273-splade-quality-and-performance-followup.md', target: 'done',
    reason: 'SPLADE quality/performance followup. Tail explicitly says "What\'s not proven: mldr-en full corpus quality eval — deferred". The original Phase 3 acceptance gate was deferred to an overnight run; the tempdoc\'s in-scope work reached its decision point.' },
  { file: 'docs/tempdocs/278-indexing-throughput.md', target: 'done',
    reason: 'Indexing-throughput investigation. Body explicitly states "These informed the implementation decisions above but are no longer actionable" with an Archived sections list. Self-declared terminal.' },
  { file: 'docs/tempdocs/310-batch-lucene-backfill-writes.md', target: 'done',
    reason: 'Batch backfill architecture (528 lines). Lucene-internals reference doc. Architecture proposal phase concluded; implementations of batched writes have happened in subsequent enrichment-batch work.' },
  { file: 'docs/tempdocs/312-primary-indexing-throughput.md', target: 'done',
    reason: 'Primary indexing throughput deep-dive (2011 lines) with agent-bug-pattern lessons in the tail. Investigation produced its findings; lessons are absorbed into agent-lessons.md and related rules.' },
  { file: 'docs/tempdocs/302-startup-performance-phase-2.md', target: 'done',
    reason: 'Phase-2 startup-performance work with Strategy-A implementation hooks. Subsequent startup optimization (tempdoc 275 cold-start baseline referenced in CLAUDE.md) ran; this tempdoc\'s strategy phase concluded.' },

  // === Worker / config / lifecycle substrate ===
  { file: 'docs/tempdocs/304-worker-persistence.md', target: 'done',
    reason: 'Design proposal for Worker persistence across Head restarts. Current architecture has separate restart-able processes per ADR-0001; the proposal\'s decision point was reached (proposal was either absorbed or rejected — the current 3-process model is the stable answer).' },
  { file: 'docs/tempdocs/271-backend-lifecycle-isolation.md', target: 'done',
    reason: 'Multi-agent backend ownership analysis (2453 lines). Subsequent dev-stack ownership model (lease-based, per CLAUDE.md and `tmp/dev-runner/active.json`) consumed the findings; the analysis served its role.' },
  { file: 'docs/tempdocs/331-shared-config-contract.md', target: 'done',
    reason: 'Head↔Worker shared config contract design, building on 329. Subsequent config snapshot + divergence-warning work (per `docs/explanation/01-system-overview.md` Head→Worker Config Propagation section) implemented the contract.' },
  { file: 'docs/tempdocs/332-worker-lifecycle-phases.md', target: 'done',
    reason: 'Worker lifecycle phase boundaries design (full design for 330 §5). Builds on 330 which is completed per 341\'s "Related" annotation. Design concluded.' },
  { file: 'docs/tempdocs/333-status-state-provenance.md', target: 'done',
    reason: 'Status freshness/provenance design on top of 330. 330 is completed per cross-tempdoc references. Design phase concluded.' },
  { file: 'docs/tempdocs/341-proto-field-governance.md', target: 'done',
    reason: 'StatusResponse decomposition follow-on to 330 (annotated as completed in body) + 333. Design captured; the grouped sub-object pattern is in production per the canonical status surface.' },

  // === CI / ops / dev experience ===
  { file: 'docs/tempdocs/281-workflow-quality-baseline-refresh-and-stale-run-reconciliation-followup.md', target: 'done',
    reason: 'Workflow-quality followup with explicit numbered closure conclusions (fresh baseline passes SLOs, direct engine trustworthy, attribution sufficient, stale-run reconciliation implemented). Self-declared closure.' },
  { file: 'docs/tempdocs/282-ci-health-and-gate-fixes.md', target: 'done',
    reason: 'CI health fixes with a Done/Deferred completion table. Done items shipped; deferred items have explicit infrastructure blockers (single self-hosted runner). Closure recorded inline.' },
  { file: 'docs/tempdocs/285-agent-signal-validity-and-metrics-evolution.md', target: 'done',
    reason: 'Research/reference doc on multi-agent latency/cost optimization (arXiv links + Claude Code subagent docs). Research-phase artifact; lessons absorbed into agent-lessons.md and slice-execution.md.' },
  { file: 'docs/tempdocs/373-dependency-verification-dx.md', target: 'done',
    reason: 'Open-questions doc on Gradle dependency-verification DX (49 lines, 5 questions). Questions logged for future investigation; the tempdoc itself is terminal as a question-capture artifact.' },

  // === Refactor / module work ===
  { file: 'docs/tempdocs/328-merge-hollow-app-search-into-app-services.md', target: 'shipped',
    reason: 'Refactor actually shipped: "app-search classes 4 → 0 (module deleted)" per the body\'s before/after table. This is `shipped`, not `done` — code + canonical-doc changes landed on main.' },
  { file: 'docs/tempdocs/377-core-module-review.md', target: 'done',
    reason: 'Core-module review. Body explicitly says "Total actionable work: ~15 minutes. This is not enough to warrant a dedicated implementation session. These can be picked up opportunistically." Self-declared closure with deferred-to-opportunistic-fix language.' },

  // === Inference / GPU observability ===
  { file: 'docs/tempdocs/339-inference-phase-timing.md', target: 'done',
    reason: 'Small enhancement (57 lines) standardizing DEBUG-level timing in 3 encoders + aggregate counters. Subsequent inference observability work (tempdoc 356, ADR-0027 MetricCatalog) covered the broader observability surface.' },
  { file: 'docs/tempdocs/356-inference-observability.md', target: 'done',
    reason: 'Inference observability tempdoc (681 lines) with dependencies on 334, 354, 357. ADR-0027 (MetricCatalog as telemetry contract) is the structural consumer of this work — observability infrastructure now flows through MetricCatalog.' },
  { file: 'docs/tempdocs/347-gpu-env-var-propagation.md', target: 'done',
    reason: 'GPU env-var propagation issue for runHeadless. CLAUDE.md captures the lesson ("Windows env vars unreliable — pass config via -D"); the immediate issue is documented as guidance rather than open work.' },
  { file: 'docs/tempdocs/348-runheadless-ram-explosion.md', target: 'done',
    reason: 'Head-side reranker BFCArena OOM investigation. Investigation reached its findings; related tempdocs (309, 346, 347, 349) consumed the cross-cutting recommendations.' },
  { file: 'docs/tempdocs/353-agent-friction-log.md', target: 'done',
    reason: 'Agent friction log with explicit "Fix (applied this session)" entries and concrete one-line fix. Log-doc role complete; fixes recorded.' },
  { file: 'docs/tempdocs/375-sandbox-validation.md', target: 'done',
    reason: 'Sandbox-validation procedures with model pre-staging via `JUSTSEARCH_MODELS_DIR`. Procedures documented; ongoing sandbox testing is operational work, not tempdoc work.' },
  { file: 'docs/tempdocs/376-cpu-gpu-inference-strategy.md', target: 'done',
    reason: 'CPU vs GPU inference strategy exploration. ADR-0019 (CPU vs GPU model selection — FP32 for CPU, FP16 for GPU, model-manifest selection) is the structural decision that consumed this strategy work.' },

  // === Agent UI / search surface ===
  { file: 'docs/tempdocs/366-agent-search-interface.md', target: 'done',
    reason: 'Agent search interface design (2598 lines) building on 362 (faceted metadata filtering) + 363 (transparent query enhancement). ADR-0020 (structured metadata facets) + ADR-0016 (QU soft-boost) are the structural consumers.' },

  // (344-funding-opportunities.md removed 2026-06-23 — relocated to the private
  //  business sidecar for the go-public cutover; no longer a tempdoc.)
];

function rewriteStatus(raw, target) {
  let next = raw;
  const fmMatch = next.match(/^(---\s*\n)([\s\S]*?)(\n---\s*(?:\n|$))/);
  if (fmMatch) {
    const [whole, open, block, close] = fmMatch;
    if (/^status:/m.test(block)) {
      const newBlock = block.replace(/^(status:\s*).+$/m, `$1${target}`);
      next = next.replace(whole, open + newBlock + close);
    }
  }
  next = next.replace(/^(\*\*Status\*\*:\s*).+$/m, `$1${target}`);
  return next;
}

function appendStalenessReview(raw, target, reason) {
  if (/^## Staleness review \(\d{4}-\d{2}-\d{2}\)$/m.test(raw)) return raw;
  const trimmed = raw.replace(/\s+$/, '');
  const note = `

---

## Staleness review (${TODAY})

Marked \`${target}\` after per-doc triage in the Shape-2 staleness audit.

${reason}

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.
`;
  return trimmed + note + '\n';
}

let modified = 0;
let skipped = 0;
for (const entry of MANIFEST) {
  if (!fs.existsSync(entry.file)) {
    console.error(`SKIP (missing): ${entry.file}`);
    skipped++;
    continue;
  }
  const raw = fs.readFileSync(entry.file, 'utf8');
  let next = rewriteStatus(raw, entry.target);
  next = appendStalenessReview(next, entry.target, entry.reason);
  if (next !== raw) {
    fs.writeFileSync(entry.file, next);
    console.log(`✓ ${entry.file} → ${entry.target}`);
    modified++;
  } else {
    console.log(`= ${entry.file} (no change)`);
  }
}

console.log(`\n${modified} tempdoc(s) modified, ${skipped} skipped.`);
