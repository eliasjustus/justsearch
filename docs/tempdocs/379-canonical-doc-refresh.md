---
title: "379 — Canonical Documentation Refresh"
---

# 379 — Canonical Documentation Refresh

- **Status:** complete
- **Created:** 2026-04-08
- **Scope:** Systematic audit of 20 tempdocs against ~30 canonical docs, bulk update, 11 new ADRs, 9 new skills, 3 new hooks

---

## Motivation

Canonical documentation had drifted significantly from the implementation reality captured in tempdocs 252–377. Model names were wrong, deleted modules were still listed, new API endpoints and schema fields were undocumented, and load-bearing architectural decisions existed only in working notes. The skill system covered only 2 of 12+ domains where agents need pre-loaded context.

## Approach

### Phase 0: Tempdoc Analysis (20 parallel subagents)

Spawned one Explore subagent per tempdoc to cross-reference against the canonical doc index (`docs/llms.txt`). Each subagent:
1. Read its tempdoc fully
2. Identified which canonical docs were affected
3. Reported specific changes needed (with quotes)
4. Assessed whether new ADRs were warranted

**Tempdocs analyzed:** 252, 326, 343, 344, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372, 373, 374, 375, 376, 377

**Results:**
- 3 tempdocs had no canonical impact (344 business-only, 372 stub superseded by 370, 373 open/unimplemented)
- 17 tempdocs required canonical doc updates
- 11 new ADRs identified as warranted

### Phase 1: Canonical Doc Updates (7 parallel worktree agents)

Organized affected docs into 7 topic clusters to minimize cross-reference errors:

| Worker | Cluster | Files Updated | Key Changes |
|--------|---------|---------------|-------------|
| A | API Contract | api-contract-map, env-vars, pipeline-invariants | +91 lines to contract map; 5 env vars; 5 new invariants |
| B | Retrieval & Eval | pipeline-overview, quality-register, jseval-ref, throughput | Fixed all 3 model names; added 4 pipeline stages; 5 findings; 1 dataset |
| C | Model & AI Runtime | model-inventory, ai-architecture, ADR-0007, ADR-0008, legal | Updated all 5 active models; BASIC_OPT; entity facets live; 3 licenses |
| D | Architecture + ADRs | module-architecture, agent-system, ADRs 0015-0017 | Removed deleted modules; added MCP surface; created 3 ADRs |
| E | Storage & Installer | storage-engine, desktop-installer, installer issues, tech-debt | 12 schema fields; installer rewrite; 6 new issues |
| F | UI Behavioral | search-ui-behavior, secondary-views, ui-readiness, develop-ui | Metadata facets; GPU card; vimMode; MSW fixtures |
| G | Dev Process | agent-guide, checklists, class-size, mcp-dev-tools, process-coord, ADR-0018 | ui-shot guidance; API record workflow; 3 pitfalls; ADR-0018 |

**Merge strategy:** C → E → G (no ADR cross-refs), then A → B → F, then D (creates ADRs). One conflict in `docs/decisions/README.md` and `docs/llms.txt` (Worker D and G both added ADR rows) — resolved manually by combining all entries in order.

### Phase 2: Medium-Priority ADRs (Worker H)

Created ADRs 0019–0025 in a single batch:

| ADR | Title | Source Tempdoc |
|-----|-------|---------------|
| 0015 | MCP tool surface design | 366 |
| 0016 | Query understanding soft-boost over hard-filter | 363 |
| 0017 | ai-bridge module decomposition | 367 |
| 0018 | VLM PDF extraction via chat model | 252 |
| 0019 | CPU vs GPU model selection strategy | 376 |
| 0020 | Structured metadata fields as filterable facets | 362 |
| 0021 | Build-stamp content-hash design | 371 |
| 0022 | RecordBuilder annotation processor for API records | 370 |
| 0023 | API responses declare their runtime context | 368 |
| 0024 | App packaging: NSIS, per-user install, download-on-demand | 374 |
| 0025 | Core DTO dual-type layering (gRPC vs REST) | 377 |

### Phase 3: Skill System Expansion

**Gap analysis** identified 9 domains where agents need pre-loaded context but no skill existed. Created 9 new skills (14 total project-defined):

| Skill | Type | Trigger Domain |
|-------|------|---------------|
| `/ci-triage` | Decision tree | Build/test failures, CI investigation |
| `/docs-maintenance` | Workflow | Canonical doc edits, regeneration sequence |
| `/lockfile` | Workflow | Dependency changes, lockfile regeneration |
| `/api-record` | Workflow | app-api record field additions, controller HashMap caveat |
| `/ssot-catalog` | Guard | SSOT catalog edits, dual-copy sync |
| `/dev-stack` | Operational (synced) | Dev stack start/stop, port issues, hot-reload |
| `/installer` | Context (synced) | Tauri/NSIS/packaging/sandbox |
| `/doc-audit` | Procedure | Periodic tempdoc-to-canonical drift analysis |
| `/module-arch` | Context (synced) | Module creation, dependency governance |

3 of the 9 are managed by `skills-sync.mjs` (auto-updated when their source canonical docs change). Updated the SKILLS manifest in `skills-sync.mjs` with 3 new entries (dev-stack → mcp-dev-tools.md, installer → desktop-installer.md, module-arch → module-architecture.md).

### Phase 4: Complementary Hooks

Created 3 PostToolUse hooks that fire automatically to remind agents about skill-backed workflows:

| Hook | Trigger | Reminder |
|------|---------|----------|
| `docs-regen-hint.mjs` | Edit/Write on `docs/**/*.md` (canonical dirs) | Run llmstxt-generate + skills-sync |
| `lockfile-hint.mjs` | Edit on `**/build.gradle.kts` | Regenerate lockfiles if deps changed |
| `ssot-hint.mjs` | Edit/Write on `SSOT/catalogs/**` | Sync dual copies |

Registered in `.claude/settings.local.json` PostToolUse section. Documented in `.claude/rules/hooks-reference.md`.

### Phase 5: CLAUDE.md & Cleanup

- Updated CLAUDE.md "Shared Registers" → "Skills" section with all 14 skills organized into Registers (2), Domain Context (5), and Workflows (6)
- Fixed 2 pre-existing broken links in `search-quality-register.md` (references to retired `search-quality.md` and `retrieval-quality.md` issue files)

## Verification

All three doc verification gates pass with zero errors:

```
verify-canonical-doc-links: OK (files=102)
llmstxt-generate --check: OK (99 docs indexed)
skills-sync --check: OK (6 skills, 10 sources)
```

## Commits

| Hash | Message |
|------|---------|
| `c3e40354d` | docs: update 5 canonical docs with model swaps, NER activation, settings mode, legal entries |
| `733a40409` | docs: update canonical docs with entity fields, installer issues, packaging, and tech debt |
| `23242cf51` | docs: update canonical docs and add ADR-0018 (VLM PDF extraction) |
| `dd8f823b4` | docs: update canonical docs for tempdocs 362-371 |
| `49b77b641` | docs: update canonical docs for 252/326/343/363/366/369 changes |
| `3a6b5af2d` | docs: update canonical UI docs with tempdoc 364 findings |
| `60273902e` | docs: update module-architecture + agent-system docs, add ADRs 0015-0017 |
| `3338eba50` | docs: add ADRs 0019-0025 (medium-priority architectural decisions) |
| `5169f8450` | feat: add 9 new skills |
| `b01324210` | docs: update CLAUDE.md with all 14 skills, fix broken links in quality register |
| `0e5b4afd1` | feat: add 3 complementary PostToolUse hooks (docs-regen, lockfile, ssot) |

Plus 6 merge commits for the worktree branch integrations.

## By the Numbers

- **20** tempdocs analyzed
- **~30** canonical docs updated
- **11** new ADRs (0015–0025)
- **9** new skills (14 total)
- **3** new PostToolUse hooks
- **2** pre-existing broken links fixed
- **0** verification errors remaining
- **8** parallel worktree agents used (7 doc workers + 1 ADR worker)

## Phase 6: Cross-References (completed)

Added bidirectional linking between the systems built in earlier phases:

**common-workflows.md skill cross-references (4 edits):**
- "Modify SSOT catalogs" → `/ssot-catalog`
- "Add a field to an API record" → `/api-record`
- "After modifying docs" → `/docs-maintenance`
- "Add a frontend component" → `/ui-check`

**ADR reverse cross-references in canonical docs (8 edits):**

| Canonical Doc | ADR Referenced |
|---------------|---------------|
| `04-storage-engine.md` | ADR-0020 (metadata facets) |
| `05-ai-architecture.md` | ADR-0019 (CPU/GPU model selection) |
| `02-process-coordination.md` | ADR-0021 (build-stamp) |
| `agent-guide.md` §3.3 | ADR-0022 (RecordBuilder) |
| `api-contract-map.md` | ADR-0023 (runtime context) |
| `12-desktop-installer.md` | ADR-0024 (packaging) |
| `19-module-architecture.md` | ADR-0025 (DTO layering) |
| `23-search-pipeline-overview.md` | ADR-0018 (VLM extraction) |

Commit: `19514292e`

## Phase 7: Cleanup (completed)

**Worktree cleanup:** No action needed — all subagent worktrees were auto-cleaned by `isolation: "worktree"`. Only `main` branch exists, no stale worktree branches.

**Gate run:** Compilation passes (`compileJava compileTestJava` BUILD SUCCESSFUL). One pre-existing `integrationTest` failure in `SchemaMismatchStatusContractTest` (schema mismatch contract test) — unrelated to doc changes. All 4 docs verification gates pass:
- `verify-canonical-doc-links`: OK (102 files)
- `llmstxt-generate --check`: OK (99 docs)
- `skills-sync --check`: OK (6 skills, 10 sources)
- `module-deps --check-canonical`: OK

**Tempdoc supersession audit:** Marked 3 tempdocs as `status: superseded`:

| Tempdoc | Superseded By |
|---------|---------------|
| 367-legacy-code-audit | ADR-0017, module-architecture.md, backend-tech-debt.md |
| 370-api-record-evolution | ADR-0022, agent-guide §3.3, common-workflows.md |
| 371-stale-jvm-detection | ADR-0021, process-coordination.md, mcp-dev-tools.md |

4 tempdocs assessed but NOT superseded (376: open/unvalidated, 377: active, 365: active, 372: already superseded by 370).

## Phase 8: Second Tempdoc Batch Analysis (tempdocs 334–361)

Analyzed 25 tempdocs across 3 parallel subagents (some tempdocs have multiple files under the same number, e.g., 347-* has 3 variants, 346-* has 2, 348-* has 2).

### No Canonical Impact (8 tempdocs)
- 346-search-eval-agent-loop — file doesn't exist
- 346-agent-retrieval-eval — design doc only, not implemented
- 347-gpu-env-var-propagation — superseded stub (sibling 347-gpu-env-propagation has the implementation)
- 348-runheadless-ram-explosion — likely superseded by 360 (reranker moved to Worker, Head has zero ORT sessions)
- 350-jseval-pipeline-timing — already documented in jseval-pipeline-reference.md
- 351-jseval-repo-root-invocation — already documented
- 352-ort-common-module — already documented (minor DLL fix note for inference-runtime-register)
- 355-index-reset-api — already documented in api-contract-map.md and jseval-pipeline-reference.md

### Minor Drift (7 tempdocs)

| Tempdoc | Canonical Doc | Change Needed |
|---------|---------------|---------------|
| 334 (single-pass enrichment) | `23-search-pipeline-overview.md` | Add `CombinedEnrichmentBackfillOps` to stage 8a + source code map |
| 335 (jseval observability) | `jseval-pipeline-reference.md` | `--max-queries 0` modes-optional behavior; `models` key in summary.json; GPU summary stats |
| 353 (agent friction log) | `jseval-pipeline-reference.md` | `--max-queries` qrel-filter scoring fix note |
| 354 (map-based metrics) | `api-contract-map.md` | `enrichmentCompleted` is a `map<string,int64>` not individual fields |
| 357 (encoder profiles) | `jseval-pipeline-reference.md` | `--profile` removed from `run`; encoder profiles always available with `--pipeline` |
| 349 (unified ORT CUDA) | `inference-runtime-register.md` | Note that `verify-model.py` is CPU-only |
| 359 (reranker audit) | `inference-runtime-register.md` | NaN-on-CPU-OOM finding (`reportCpuSessionFailure()`), BFC arena detection (`isBfcArenaFailure()`) |

### Significant Drift (5 tempdocs)

**345 (RAG considerations):**
- `22-agent-system-architecture.md` says 4 MCP tools — there are now 6 (`justsearch_retrieve_context`, `justsearch_match_citations` added)
- `mcp-production-server.md` missing 2 tools + quality signals (`best_chunk_score`, `retrieval_coverage`, etc.)
- `23-search-pipeline-overview.md` Alternate Entry Points section missing retrieve-context REST endpoint

**347 (config resolution diagnostics):**
- `06-configuration-ssot.md` missing ordinal 150 (auto-detected hardware from `GpuAutoDetection`), stale EnvRegistry count (~180 → ~233), missing `ConfigKey` split (52 YAML-only keys), breaking sysprop rename undocumented
- `environment-variables.md` needs note that YAML-only keys (`ConfigKey`) are not env-overridable

**346-llama-server-architecture:**
- `05-ai-architecture.md` `SamplingParams.VDU` temperature stale (doc says 0.2, code is 0 since 346)
- `model-inventory.md` Qwen3VL entry missing OHR-Bench VDU quality result (76% vs Docling 66%)

**356 (inference observability):**
- `inference-runtime-register.md` missing roofline findings (RTX 4070 FP16 29.15 TF, 42-53% GPU efficiency for embed/SPLADE, NER 18% anomalous), NER per-call overhead (5.6ms fixed), Q-001 (GPU warmup) should be closed (warm-up implemented in 360)

**358 (pipeline model selection):**
- `model-inventory.md` still shows `EmbeddingGemma-300M` as "current default" — `gte-multilingual-base` is the production default per 358
- `inference-runtime-register.md` D-003 stale ("EmbeddingGemma as default — SHIPPED")
- `search-quality-register.md` missing SciFact baseline for gte-multilingual-base (full=0.7132)

**360 (reranker worker migration):**
- `inference-runtime-register.md` missing CPU CE latency (~42s, seq=2048), GPU CE latency (~2.2s, seq=512), VRAM budget table (embed ~2GB + SPLADE ~1GB + NER ~0.5GB + reranker ~2GB = ~5.5GB)

### New ADRs Warranted (2)
1. **gte-multilingual-base as production embedding model** (358) — selection criteria (H1-H9), multilingual gap finding, lazy CPU session design
2. **All ORT consumers in Worker process** (360) — reverses earlier Head-side reranker, settles "where does inference live"

### Verification Results

3 subagents verified key claims against code. One major claim was rejected:

| Claim | Verified? | Finding |
|-------|-----------|---------|
| 6 MCP tools (4+2 new) | **REJECTED** | Only 4 production tools. `retrieve_context` and `match_citations` are internal to `justsearch_answer`, not separate MCP tools. |
| gte-multilingual-base is default | **CONFIRMED** | `EmbeddingOnnxModelDiscovery.MODEL_NAME = "gte-multilingual-base"` |
| VDU temperature is 0 | **CONFIRMED** | `SamplingParams.VDU = new SamplingParams(0.0, ...)` |
| EnvRegistry ~233 entries | **CONFIRMED** (actual: 217 + 50 ConfigKey) |
| D-003 stale | **CONFIRMED** | Still says "EmbeddingGemma-300M as default — SHIPPED" |

### Implemented Updates (commit `347484db6`)

- `model-inventory.md`: gte-multilingual-base → "current default", EmbeddingGemma → "legacy backup"
- `inference-runtime-register.md`: D-003 updated; added F-008 (NER overhead), F-009 (NaN-on-CPU-OOM), F-010 (CE latency baselines); closed Q-001 (warmup implemented)
- `05-ai-architecture.md`: VDU temperature 0.2 → 0.0
- `06-configuration-ssot.md`: EnvRegistry count 180→217, ConfigKey split, ordinal 150
- `jseval-pipeline-reference.md`: --max-queries scoring semantics note

## Phase 9: Deferred Work Inventory

Compiled all deferred/future work items from the 40 analyzed tempdocs, grouped by domain.

### Desktop Packaging & Distribution (374, 375)
- G1: Auto-updater — no `tauri-plugin-updater`, no update endpoint, no signing keypair
- G2: Release versioning — still `2.0.0-SNAPSHOT`, no tagging workflow
- G3: Distribution channel — GitHub Releases not set up (G3-A in progress)
- G4: Code signing — unsigned dev builds blocked by Windows SAC
- G5: Uninstall data cleanup — 11.2 GB in AppData with no cleanup UI
- G6: First-run onboarding — no guided experience, empty search on launch
- G7: Splash/loading screen — white flash on startup
- G11: DevTools enabled unconditionally in release builds
- G19: NSIS stale temp files — needs automated cleanup
- G20: Gradle stage dirs leak stale CUDA artifacts
- Download pipeline abort — `AiInstallService` aborts all assets on first failure
- `\\?\` extended-length paths from Tauri — needs normalization in `lib.rs`
- `prod=false` security audit — CORS/session tokens disabled in distributed installer

### CPU/GPU Inference (376, 375, 348)
- FP32 embedding model not uploaded to registry or GitHub Releases
- `BASIC_OPT` for FP16 on CPU not validated in sandbox
- GPU-aware download profiles at Install AI time (Option C) — not designed
- CPU embedding latency with FP32: unmeasured
- `OnnxSessionCache` behavior with both model.onnx and model_fp16.onnx: unresolved

### RAG & Answer Quality (345, 363, 366)
- Prompt injection defense — XML context wrapping, framing instructions, MCP audience annotations
- "I don't know" gating — `context_sufficient` exists but not acted on in `/api/ask/stream`
- Sufficiency calibration — need labeled (query, context) → answerable? dataset
- QU implementation — designed in 363, gated behind `JUSTSEARCH_QU_ENABLED`, not yet implemented
- LLM resource scheduling — QU and expansion compete for single llama-server slot
- ContextFormat XML/PLAIN — Worker always returns LABELED, others not implemented
- Temporal query handling, conversation memory, NLI faithfulness eval — deferred

### Status/Proto Cleanup (341)
- 4d: Remove flat-field remnants from frontend `SystemStatus` type + schema
- 4e: Drop `@JsonUnwrapped` from `WorkerOperationalView` (breaking — needs production verification)

### Pipeline Profiling (334, 356)
- 7 unrun throughput experiments (pending-ID caching, deferred commits, batch=200, NRT refresh, pre-tokenize SPLADE, chunk slots, compound files)
- ORT built-in profiling blocked by jseval force-kill (needs graceful shutdown)
- Nsight Compute per-kernel roofline: not done

### Agentic Workflow (361, 353)
- I1: Skill-trigger hook (PreToolUse on Edit) — ~40 lines JS
- I3: "Error log first" diagnostic rule → agent-lessons.md
- I7: gRPC deadline guidance → common-workflows.md
- I8: Backend verification discipline → agent-lessons.md
- Port conflict detection in jseval `start_backend`
- `jseval smoke` subcommand (ingest 10-20 docs, assert, exit <60s)

### Configuration (347)
- Phase 5: Remove `detectOrtCudaPath()` from Gradle, merge env var lists

### Dependency DX (373)
- All 5 questions open (CI auto-detection, actionable errors, metadata regeneration, frequency analysis, survey)

### Notes
- Tempdoc 358 lists model swaps as "open" but many were completed by tempdoc 343 (verified in Phase 1). The 358 "open" items are stale — they were done.
- Tempdoc 363 QU is a design doc — the feature gating, prompts, and architecture are defined but code is not yet written.
- The packaging gaps (374 G1-G12) are the largest single cluster of deferred work.
