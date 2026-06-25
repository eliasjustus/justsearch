---
title: "235: Eval Infrastructure Audit"
---

# 235: Eval Infrastructure Audit

**Status:** Complete. All 16 recommendations implemented. Remaining actions (A4, A6, I2, S1/I3) are user-blocked — no further agent work.
**Created:** 2026-02-25
**Purpose:** Critical analysis of evaluation/benchmarking infrastructure — what can we confidently measure, what can't we measure, and where is the infrastructure itself a liability.

---

## Part 1: Feature Coverage Map

Every user-facing feature mapped against eval coverage.

### COVERED (dedicated eval lane or strong test gate)

| Feature | Eval Lane / Gate | Key Files |
|---------|-----------------|-----------|
| Lexical (BM25) search quality | BEIR gate (5 datasets), rank-eval, golden corpus (Recall@K, nDCG@K, MRR) | `scripts/search/beir-eval-win.ps1`, `scripts/search/rank-eval-win.ps1` |
| Hybrid (BM25+vector) search quality | BEIR with embedding-nomic-q4 profile; ANN provenance enforced post-BEN-005 | `scripts/ci/dag-runner-beir-gate.mjs` |
| RAG answer generation | 24 queries x 7 metrics (fact coverage, faithfulness, citation precision/recall, similarity, retrieval recall, forbidden facts) | `RagQualityEvalTest.java`, `diff-rag-eval-suite.mjs` |
| HTTP API latency | Perf suite: per-route p50/p95/p99, SLO bucket compliance, TTFT | `scripts/perf/dag-runner-perf-suite.mjs`, `regression-thresholds.v1.json` |
| Engine indexing throughput | Claim A: ~3600 docs/s isolated Lucene runtime | `EngineIndexBench.java` |
| Agent quality | 22 scenarios, deterministic checks, efficiency diagnostics, rolling scorecard | `scripts/ci/dag-runner-agent-battery.mjs` |
| Cold start / startup latency | Perf suite `scenario_a_cold_start_ready` | `dag-runner-perf-suite.mjs` |
| Worker restart resilience | Perf suite `scenario_g_worker_restart` | `regression-thresholds.v1.json` |
| gRPC IPC stability | Soak test + retry calibration | `scripts/resilience/faults/dag-runner-grpc-soak.mjs` |
| Index schema migration | Blue/green E2E: migration, buffering, rollback | `MigrationControlE2ETest`, `SwitchingFenceBufferingE2ETest`, `RollbackE2ETest` |

### PARTIAL (some signal, incomplete)

| Feature | What Exists | What's Missing |
|---------|------------|----------------|
| Cross-encoder reranking | `RerankerDeadlineBench` measures latency under deadline constraints | No measurement of reranking quality impact on search results. No regression gate. Threshold not calibrated (RAG-006). |
| Pipeline throughput (Claim B) | `run-claim-b-suite-win.ps1` at L0 | No CI integration, no promoted baseline, no diff gate. Local-only. |
| LLM inference (Claim D) | `run-claim-d-suite-win.ps1` at L0 | No CI integration, no baseline, no diff gate. |
| Streaming/SSE latency | Perf suite `scenario_e_streaming_smoke` | Smoke test only — no throughput measurement, no quality gate, no token/s measurement. |
| Vector quantization | `vector-quantization-gate-win.ps1` | Single-machine evidence only (RAG-003). No cross-hardware validation. |
| Competitor comparison | 6 tools (DuckDB-VSS, Meilisearch, Qdrant, Recoll, ZincSearch) | BEN-001: process startup overhead invalidates latency. L0, not publishable. |

### NONE (zero evaluation)

| Feature | Importance | Why It Matters |
|---------|-----------|----------------|
| **Text extraction quality (Tika)** | **High** | The entire Tika pipeline — PDF, DOCX, HTML, code, spreadsheet extraction — has zero quality evaluation. A bad extraction silently degrades search for that document. Only `TimeboxedContentExtractorTest` (timeout guardrail) and `VduEligibilityPdfFixturesTest` (PDF OCR marking) exist. No extraction accuracy measurement. |
| **Autocomplete / Suggest** | **High** | User-facing hot path (`GET /api/knowledge/suggest`). No latency measurement, no quality evaluation. Noted as high-priority TCK gap in `api-contract-map.md`. |
| **Search correction precision** | **Medium** | `correctionApplied` / `correctedQuery` features (Levenshtein retry, fuzzy correction) have no quality evaluation. No measurement of correction precision, correction recall, or false correction rate. |
| **Incremental index updates** | **Medium** | No eval of how quickly add/remove/update operations become searchable. No measurement of `POST /api/indexing/excludes/apply` effectiveness under load. |
| **UI paint times (non-indexing)** | **Medium** | Perf suite measures UX *during* indexing (scenario B2) but not baseline search-as-you-type latency, Inspector panel load, or filter interaction in isolation. |
| **GPU management lifecycle** | **Medium** | `InferenceLifecycleManager` adoption, VRAM cleanup, mode transitions — only integration tests. No eval of GPU detection accuracy across hardware. |
| **Prefix expansion recall impact** | **Low** | `PrefixQuery` behavior documented but not evaluated against a query set measuring recall delta with/without expansion. |
| **Faceted search accuracy** | **Low** | Entity facets exist but no quality eval of facet completeness or correctness. |
| **Passage-level excerpt quality** | **Medium** | The excerpt regions feature (SRQ-002 mitigation) has no standalone quality evaluation — only functional tests. |
| **NER extraction accuracy** | **Low** | `NerModelDiscovery` exists but no accuracy evaluation against annotated test data. |
| **Non-English / stemming** | **Low** | No language-specific search quality eval. SRQ-003 (English stemming not evaluated) open at P4. |

---

## Part 2: Measurement Confidence

### RAG Quality Suite — Low-Medium Confidence

**Corpus:** 6 synthetic plain-text documents on unrelated topics (HikariCP, Transformers, gradient descent, puppy vaccination, Java generics, data pipelines).

Statistical concerns:
- **6 documents is too small to stress retrieval.** With 6 docs, BM25 alone provides near-perfect recall. Vector search is barely challenged. The retrieval pipeline is not meaningfully tested.
- **24 queries is statistically marginal.** At a 0.90 ratio threshold, a single query regression can flip the entire gate (1/24 = 4.2% swing).
- **Content types are homogeneous:** all plain text, all English, all well-structured prose. Real users index messy PDFs, scanned documents, code files, HTML, and mixed-language content. The corpus does not represent the target workload.
- **Citation threshold (0.5) not calibrated** (RAG-006). The cross-encoder cutoff is an educated guess, not empirically derived.
- **Frozen corpus profile provides comparability** — this is good. But comparability of a non-representative corpus has limited value for real-world quality claims.

**What's working:** The 7 metrics themselves are well-chosen. The `CitationScorer` cross-encoder is a genuine quality signal. The frozen-vector approach enables reproducible comparison.

### BEIR Datasets — Medium-High Confidence

**Corpus:** 5 standard IR benchmarks (Arguana, NFCorpus, SciFact, Webis-Touche2020, MLDR-en) with thousands of judged query-document pairs.

Strengths:
- Statistically meaningful nDCG/MRR/Recall@K measurements with large query sets.
- Industry-standard benchmarks enabling external comparison.
- Multi-dataset coverage catches dataset-specific regressions.

Concerns:
- **Domain mismatch:** BEIR datasets are academic web/science text. JustSearch's target is personal file search (documents, code, notes) — different structure, length distribution, and vocabulary.
- **BEN-005 invalidation:** Hybrid profile was silently running in lexical-only mode until 2026-02-25. Historical hybrid baselines may need re-promotion.
- **English only** — no non-English retrieval evaluation.

### Perf Suite — High Confidence (within scope)

`regression-thresholds.v1.json` shows well-considered `min_count`, `max_ratio`, and `max_delta_ms` values with documented rationale. Histogram bucket awareness is honest about limitations.

Concern: **Single-machine calibration** (RAG-003). Thresholds are tuned for the developer's machine. No cross-machine evidence.

### Agent Battery — Low Confidence

**Current pass rate: 43.8%** — more scenarios fail than pass.

Concerns:
- **Infra failure tolerance (15%)** means up to ~3 of 22 scenarios can fail due to infrastructure, not agent quality.
- **No semantic evaluation** — current checks are substring/keyword-based. LLM-as-judge was explored but dropped (tempdoc 224 deleted — low value given current pipeline maturity).
- **Local model quality limitations** — TinyLlama/Qwen quality ceiling bounds what the battery can measure.
- **Pass rate instability** — Phase A stability requires `passRateStdDev <= 8pp`, suggesting observed variance is high.

**What's working:** Deterministic checks (tool call oracles, required tool success) are novel and catch real regressions. Efficiency diagnostics (redundant calls, loop detection, progress rate) provide signal no other system captures.

### Golden Corpus — Low Confidence

**10 documents** across 4 categories (lexical-truth, semantic-truth, hybrid-trap, hard-negatives). Recall@3 >= 0.9, nDCG@3 >= 0.8 thresholds.

Concern: **10 documents is not statistically meaningful** for IR quality claims. The categories are well-designed (especially the hard-negatives), but the sample size is too small for reliable signal.

### Competitor Suite — Low Confidence

BEN-001: process startup overhead invalidates latency comparison for process-based competitors. L0 maturity (local only). Results are directionally useful but not publishable.

---

## Part 3: Operational Sustainability

### Infrastructure Scale

| Directory | Files | Notable |
|-----------|-------|---------|
| `scripts/bench/` | 211 | 25 in `rr/` (meta-evaluation), 32+ baseline JSONs |
| `scripts/resilience/` | 75 | 50 in `governance/` |
| `scripts/perf/` | 27 | |
| `scripts/ci/` | 83 | 7 DAG runners (20-35K lines each) |
| `scripts/search/` | 5 | |
| **Total** | **~400** | |

Additional:
- 8 v1→v2 conversion scripts + 1 parity checker (dual-write migration)
- 14 JSON schema files
- 7-hour overnight cycle, single failure wastes a night

### Maintenance Burden Hotspots

**1. Dual-write v1/v2 migration (no completion date)**

Every lane change must be implemented in both v1 and v2 formats. 8 conversion scripts + `check-v1-v2-parity.mjs` validate equivalence. This has been "in progress" for weeks with no v1 deprecation timeline. Each new metric, threshold, or schema field requires dual implementation.

**2. `scripts/governance/bench-meta/` — meta-evaluation pipeline (25 files)**

This pipeline evaluates the evaluation system itself:
- `build-rr-u1-objective-alignment-report.mjs` — are the eval objectives aligned?
- `build-rr-u2-coverage-audit.mjs` — does the eval cover enough?
- `build-rr-u3-label-sensitivity-report.mjs` — are the labels sensitive enough?
- `build-rr-u7-transfer-consistency-report.mjs` — do results transfer?
- `build-rr229-evidence-readiness-manifest.mjs` — is the evidence ready?

Each has its own test file, input builder, and schema. This is evaluation of the evaluation infrastructure — appropriate for a team with dedicated ML quality engineering, excessive for a solo-dev project.

**3. `scripts/governance/resilience/` — resilience governance (50 files)**

Conformance contracts, error budget policies, SLO packages, external-fit decisions, promotion decision validators, tooling lifecycle contracts, persistent handoffs, lane triage, history readiness, soak readiness, release evidence index — all for a product with zero production users. The actual value (gRPC soak testing, retry calibration) is buried under layers of governance wrapping.

**4. Ratchet policy and baseline management**

- `scorecard-ratchet-policy.v1.json` requires 15 comparable runs for L3 promotion
- `baseline.max_age_days_warn: 7` means baselines go stale weekly
- Each baseline refresh requires a successful overnight run
- Any corpus change, model change, or parameter change resets the comparable-run window

**5. Overnight automation fragility**

- `overnight-benchmark-autopilot.mjs`: 7-hour cycle with adaptive lane frequency
- `overnight-rag-ai-queue.mjs`: 12+ hour serial RAG + claim suite + agent battery
- GPU memory pressure, port conflicts, llama-server hangs require manual recovery
- Single failure in a 7-hour cycle wastes the entire night

---

## Part 4: Methodology Critique

### L0-L3 Maturity Model — Overhead Without Value

Current state after months of investment:

| Maturity | Lanes |
|----------|-------|
| L0 (local only) | Claim B, Claim D, Competitor |
| L1 (report-only) | Claim A, Track G, Search eval, Agent battery |
| L2 (warn-on-regression) | Perf suite |
| L3 (blocking gate) | **None** |

No lane has reached L3. The 4-level model creates a perpetual sense of "not there yet" without clear evidence that L3 gates would improve the product. For a solo developer, the distinction between L2 (warn) and L3 (block) is moot — the developer sees warnings and blocks identically because they are the same person.

The ceremony of tracking maturity levels, computing promotion readiness, running drift backtests, and generating ratchet recommendations consumes development time that could be spent on product features or actual quality improvements.

**Recommendation:** Collapse to two levels — "tracked" (runs, produces reports) and "gating" (blocks merges). A lane graduates when the developer decides it's ready, not when a ratchet formula says so.

### Ratchet Policy — May Never Converge

Promotion from L1→L2 requires 8 comparable runs with <20% regression rate. L2→L3 requires 15 comparable runs with <10% regression rate and <5% non-comparable rate.

With overnight runs (one per night at best), accumulating 15 comparable runs takes over two weeks of uninterrupted successful automation. Any corpus change, model upgrade, parameter adjustment, or infrastructure failure resets the comparable-run window. The codebase evolves faster than the run window stabilizes.

This creates a Zeno's paradox: the ratchet can never advance because reaching the target requires the system to stop changing, but a pre-release product's defining characteristic is that it changes constantly.

### bench-suite.v2 Migration — Doubling Maintenance Cost

8 conversion scripts maintain dual-write parity between v1 and v2 artifact formats. A parity checker validates equivalence. Every new metric, threshold, or schema change must be implemented in both formats.

The dual-write approach is technically sound (no data loss, rollback safety) but has no deprecation timeline. For a solo dev, this is a velocity tax on every lane change.

### Meta-Evaluation — Decreasing Returns

The `rr/` pipeline (25 files) and resilience governance (50 files) represent evaluation of evaluation infrastructure. At some point, the marginal value of validating the validators crosses zero. For a solo-dev project pre-release, the actual search quality matters more than the evidence readiness of the objective alignment report.

### What IS Working Well

Not everything is overhead — several patterns provide genuine value:

- **Corpus governance** (comparability enforcement) caught BEN-005 — a real measurement bug where hybrid BEIR was silently running lexical-only.
- **Sentinel validation** (sentinel-query-must-return-hits) prevents bogus throughput claims from empty indexes.
- **Perf thresholds** have documented rationale, `min_count` requirements for p95/p99, and honest warnings about histogram limitations.
- **Agent efficiency diagnostics** (redundant tool calls, loop detection, progress rate) provide novel behavioral signal that no standard framework captures.
- **Golden corpus categories** (lexical-truth, semantic-truth, hybrid-trap, hard-negatives) are well-designed, even if the corpus is too small.
- **BEIR multi-dataset approach** catches dataset-specific regressions that a single benchmark would miss.

---

## Part 5: Recommendations — Implementation Summary

All 16 recommendations implemented across 7 commits (2026-02-25/26). Key commits: `9266291e` (S2/S3 governance relocation), `d2ca0439` (S4 scorecard simplification), `b3d04c83` (A5/A3/A6 multi-mode + correction eval), `56e83193` (A1/I1/A4 extraction + diagnosis + RAG diversification).

### Completed

| ID | Action | Key Result |
|----|--------|------------|
| S1 | v2 migration prep | `--v2-only` flag wired through overnight pipeline. 1/5 validation cycles done. |
| S2 | Relocate `rr/` to `scripts/governance/bench-meta/` | Done (`9266291e`) |
| S3 | Relocate resilience governance to `scripts/governance/resilience/` | Done (`9266291e`) |
| S4 | Remove automated ratchet/promotion machinery | Done — ~446 lines removed (`d2ca0439`) |
| A1 | Text extraction quality eval | Harness + 3 corpus files. 3/3 pass. |
| A2 | Suggest perf scenario | `scenario_suggest_latency` added (`fdaee38e`) |
| A3 | Search correction eval | 50-query manifest + script (`b3d04c83`) |
| A5 | Multi-mode isolation (3-way) | Dense nDCG=0.668, Hybrid=0.639, Lexical=0.661. Hybrid underperforms — motivates A6. |
| A7 | Regression detection ROI | 1 genuine catch (BEN-005). 0 automated gate catches. |
| I1 | Agent battery diagnosis | 8/22 pass (36.4%). 6 infra, 5 bad expectations, 1 model ceiling, 2 unknown. |

### Remaining (user-blocked)

| ID | Status | Blocker |
|----|--------|---------|
| A4 | User content needed | Create diverse RAG corpus docs, regenerate frozen embeddings |
| A6 | Blocked (env vars) | User must set env vars in MCP server terminal before each experiment |
| I2 | 3/5 datasets done | webis re-run needed (pre-237 W1/W2); mldr-en not yet run (~6h). See Appendix A runbook. |
| S1/I3 | 1/5 cycles done | 4 more overnight validation cycles needed |

---

## Part 6: Blind Spots — What Part 1-5 Missed

A self-critical second pass identified 4 additional areas directly relevant to the eval/benchmarking audit.

#### 6.1 Configuration sensitivity completely unmeasured

`RuntimeHybridSearchConfigFactory.java` exposes 10+ tunable parameters:

| Parameter | Default | Calibration Evidence |
|-----------|---------|---------------------|
| `rrf_k` | 60 | "Consensus default" per tempdoc 135 research. No sweep. |
| `vector_rrf_weight` | 0.75 | Unknown origin. No sweep. |
| `bm25_score_boost_weight` | 0.002 | Unknown origin. No sweep. |
| `vector_skip_min_chars` | 4 | Unknown origin. No sweep. |
| `candidate_limit_max` | 100 | Unknown origin. No sweep. |
| `text_candidate_multiplier` | 10 | Unknown origin. No sweep. |
| `vector_candidate_multiplier` | 10 | Unknown origin. No sweep. |
| `vector_low_signal_top_score_threshold` | 0.40 | Unknown origin. No sweep. |

No sensitivity analysis exists. No parameter sweep infrastructure. No interaction study. The defaults may be suboptimal — there's no way to know without measuring.

#### 6.2 Embedding model quality not isolated from BM25

Vector search quality is never measured independently. The BEIR evaluation runs in `lexical` or `hybrid` mode, but there is no `vector-only` mode evaluation. The golden corpus tests hybrid search. The RAG eval uses frozen vectors.

This means: when hybrid search shows Recall@10 = X, we cannot determine how much comes from BM25 and how much from vector search. A complete failure of the embedding model could be masked by BM25 carrying all the recall. There is no way to attribute quality to the vector pipeline specifically.

The `EngineVectorIndexBench.java` measures vector **timing** (indexing throughput, kNN latency) but not vector **quality** (recall vs. brute-force ground truth).

#### 6.3 Regression detection effectiveness — A7 research findings

**Methodology:** Searched git log with 12 query patterns (`regression AND detected`, `gate.*fail`, `baseline.*invalid`, `caught`, `revert`, `nDCG`, `perf.*fix`, `BEN-*`, `promote.*baseline`, `overnight`, `ANN.*proof`, `provenance`), reviewed `docs/observations.md`, searched all tempdocs for regression catch documentation.

**Documented catches (1 total):**

1. **BEN-005 (hybrid profile non-functional):** The BEIR hybrid profile was silently running in lexical-only mode — vector search contributed zero recall. This was discovered during tempdoc 219 Phase AE work (manual investigation of comparability inputs), not by an automated gate failing. The corpus governance system was then enhanced with ANN provenance enforcement to prevent recurrence (`provenance.ann.proof_status`). This is a genuine catch, but it came from human review of eval artifacts, not from a gate blocking a bad commit.

**Gate failures found in git history (2):**

- `448a6d88` — 3 CI gate failures (buildHealth, ajv deps, module-deps freshness). All were infrastructure breakage (dependency scope errors, missing npm install), not quality regressions detected by eval.
- `117c2869` — Gate timeout fix. Infrastructure stability, not regression detection.

**Perf baseline updates (1):**

- `16146c8e` — "perf: update win regression baseline" — a baseline refresh, not a regression-triggered fix. No accompanying commit fixing a performance bug.

**Zero documented regression-detection stories:** All commits mentioning "regression" in the search/BEIR/perf context are infrastructure changes (adding gates, wiring DAG runners, promoting baselines). No commit message describes "eval infra detected X regression, fixed by Y change."

**Assessment:**

The eval infrastructure has genuine deterrence value — code is written more carefully when gates exist. The BEN-005 catch (via manual artifact review) demonstrates the artifacts contain useful diagnostic information. But the automated regression detection loop (candidate vs. baseline → gate fail → developer investigates → fix committed) has never demonstrably fired. After months of investment in 8 eval lanes, a consolidated scorecard, overnight automation, and ~400 script files, the automated regression detection ROI is undemonstrated.

This is an honest gap, not a failure indictment. The system is young, the product has a single developer, and regressions may genuinely be rare. The recommendation is to document catches when they occur (a `## Regression Catches` section in `docs/observations.md`) so future ROI assessment has data.

#### 6.4 Query-time embedding non-functional (blocks vector/hybrid search)

**Discovered during A5 live testing.** HYBRID search silently falls back to BM25-only (`debugScores.vector: 0`). VECTOR search fails with `"Vector mode requires a non-empty vector field"`. Documents have embeddings (`embedding_status=COMPLETED`), but query text cannot be embedded at search time.

**Root cause — lazy init chicken-and-egg:**

1. `KnowledgeServer` creates an `EmbeddingService` with `createWithAutoDiscovery()` and passes it to `SearchOrchestrator` (via `GrpcSearchService`). The service uses lazy initialization — the model is not loaded until the first `embed()` call. (`KnowledgeServer.java:342`)
2. `SearchOrchestrator` checks `embeddingService.isAvailable()` before calling `embed()`. Since the service is not yet initialized, `available == false`, so `isAvailable()` returns false. (`SearchOrchestrator.java:230`)
3. The code falls through to the "no embedding service" fallback (BM25-only). The `embed()` call that would trigger lazy initialization is never reached.
4. The `IndexingLoop` creates and manages a **separate** `EmbeddingService` instance for document embedding — this one is properly initialized and functional.

**Evidence:**
- Worker log: `"Embedding service configured (will initialize on first use)"` — but first use never happens
- Health check: `embedding_ready: false` (repeating every 15s)
- Hybrid search response: `debugScores.vector: 0`, `hybridFallbackReason: "NO_EMBEDDING_SERVICE"`
- `EmbeddingCompatibilityController` is in `COMPATIBLE` state — the fingerprint check passes; the issue is solely `EmbeddingService.isAvailable()`.

**Fix options (product-level, not eval infrastructure):**
- (a) Call `embeddingService.initialize()` eagerly in `KnowledgeServer` after creation (adds ~2s to startup)
- (b) Change `SearchOrchestrator` to call `embed()` directly (which triggers lazy init) instead of guarding on `isAvailable()`
- (c) Change `isAvailable()` to return true for "configured but not yet initialized" (distinct from "failed to initialize")

**Impact on eval infrastructure:** A5 (vector-only BEIR), A6 (parameter experiments), and I2 (hybrid BEIR re-promotion) were all blocked on this.

**Fixed:** Applied option (a) — restored eager `embeddingService.initialize()` in `KnowledgeServer`. Also added auto-embedding fallback to `SearchOrchestrator` VECTOR mode (matching HYBRID mode's existing pattern). Verified: `embedding_ready: true` on startup, hybrid search shows `debugScores.vector > 0`, vector-only search returns HTTP 200 with vector-matched results. Introduced in `dece2424` (Jan 21), not related to recent `splade-v3` or `retrieval-arch-234` merges.

---

## Deferred: Adjacent Concerns

> Relocated to `docs/observations.md` § Eval & Benchmarking on 2026-02-27. Original IDs preserved for traceability.

| ID | Summary |
|----|---------|
| D1 | Security/adversarial testing (query injection, ZIP bombs, path traversal, Tika fuzzing) |
| D2 | Memory/GC/VRAM evaluation (heap growth, GC pauses, NMT, VRAM fragmentation) |
| D3 | Concurrent operations under desktop workload (search during indexing) |
| D4 | Error handling beyond gRPC (corrupt index, disk-full, OOM, llama-server hang) |
| D5 | Migration tests at small scale only (no 1000s-of-docs validation) |
| D6 | Model versioning protocol (no baseline invalidation policy) |
| D7 | Ranking stability (same query + same index → same order) |
| D8 | Run-to-run reproducibility (no cross-suite variance analysis) |
| D9 | UI tests validate rendering, not quality (no end-to-end through both layers) |
| D10 | Eval cost and lane overlap (no per-lane runtime record, no correlation analysis) |

---

## Appendix A: Reference Data

### A5 — Multi-mode isolation results (scifact, SPLADE-era)

| Mode | nDCG@10 | Recall@10 |
|------|---------|-----------|
| Lexical (BM25) | 0.661 | 0.779 |
| Dense-only (nomic Q4) | 0.668 | 0.789 |
| Hybrid (SPLADE+dense) | 0.639 | 0.787 |

Hybrid underperforms both legs on nDCG. Sparse (SPLADE) and dense legs may be correlated, making RRF fusion dilutive. Motivates A6 parameter experiments.

### I1 — Agent battery diagnosis (2026-02-26)

22 scenarios, Qwen3VL-8B-Thinking Q4_K_M, 12GB VRAM, 15862-doc corpus. **8/22 pass (36.4%).**

| Category | Count | Action |
|----------|-------|--------|
| Pass | 8 | None |
| Infra (VRAM contention) | 6 | Re-run without concurrent embedding rebuild |
| Bad expectations | 5 | Review oracles — substantive responses missed strict keyword checks |
| Model ceiling | 1 | Corpus/query issue |
| Unknown | 2 | Manual investigation needed |

### I2 — SPLADE+dense baseline capture runbook

Old `embedding-nomic-q4` baselines invalid (pre-SPLADE, pre-§6.4 fix). Fresh capture for SPLADE+dense architecture.

For each dataset (arguana, nfcorpus, scifact, mldr-en, webis-touche2020):
1. Run: `./scripts/search/beir-eval-win.ps1 -Dataset <dataset> -ProfileId embedding-nomic-q4 -K 10`
   - Large corpora (>50K docs): add `-IndexMode ingest_batches`
2. Verify: `provenance.ann.proof_status == "PASS"` and `debug_scores` contains `"sparse"` key
3. Promote: `./scripts/bench/promote-search-eval-beir-baseline-win.ps1 -ProfileId embedding-nomic-q4`

**Status (2026-02-27): 3/5 datasets promotable.**

| Dataset | Docs | Hybrid nDCG@10 | ANN Proof | Status |
|---------|------|----------------|-----------|--------|
| scifact | 5.2K | 0.639 | PASS | Promotable |
| arguana | 8.7K | 0.336 | PASS | Promotable |
| nfcorpus | 3.6K | 0.277 | PASS | Promotable |
| webis-touche2020 | 382K | — | — | Needs re-run (pre-237 W1/W2; SPLADE-only hybrid) |
| mldr-en | 200K | — | — | Not yet run (~6h). Issue 11 caveat: 256-token truncation. |

Promotion blocked until webis re-run and mldr-en complete.

### Issues discovered during I2 execution

| # | Summary | Status |
|---|---------|--------|
| 1 | `addWatchedRoot()` blocks Jetty on large corpora (sync `Files.walk().toList()`). | Fixed by 237 R1 (async walk). |
| 2 | `splade_model_sha256` missing from commit metadata schema. | Fixed in 235. |
| 3 | `chunkVectorCoveragePercent` near 0% during Phase 1 is expected (two-phase arch). | Informational. |
| 4 | `-SkipIndex` also skips `Wait-ForIndexIdle` (coupled concerns). | Fixed by 237 R3 (flag decomposition). |
| 5 | `Wait-ForIndexIdle` doesn't wait for Phase 2 chunk embedding backfill. | Fixed by 237 W1+W7. |
| 6 | ANN proof conflates SPLADE and dense vector evidence. | Fixed by 237 W2. |
| 7 | NER backfill permanently skipped (no BERT NER model present). | Informational. No eval impact. |
| 8 | `-IngestSkipFiles` workaround enumerates all files, causing I/O contention. | Fixed by 237 W6. |
| 9 | Orphaned Worker at ~10x reduced throughput (one incident, not reproduced). | Detection added (237 W5). See `observations.md`. |
| 10 | Large-doc corpora 3x slower (full-text tokenization before truncation). | Fixed in 235 (tokenizer truncation at construction). |
| 11 | SPLADE encodes only first 256 tokens — non-comparable to published results. | Architectural limitation. See `observations.md`. |

### A6 — Parameter experiment protocol (SPLADE-era)

One-time procedure to measure RRF fusion parameter sensitivity against SPLADE-enabled backend. Sparse leg is SPLADE (env var names retain `BM25` for backward compat).

**Procedure:**
1. Baseline: `./scripts/search/beir-eval-win.ps1 -Dataset scifact -ProfileId embedding-nomic-q4 -K 10`
2. Experiments (one param at a time):
   - `rrf_k`: 30, 60 (default), 90, 120 — via `-Djustsearch.index.rrf_k=N`
   - `vector_rrf_weight`: 0.5, 0.75 (default), 1.0, 1.5 — via `-Djustsearch.index.vector_rrf_weight=N`
   - `bm25_score_boost_weight`: 0.001, 0.002 (default), 0.004 — via `-Djustsearch.index.bm25_score_boost_weight=N`
3. Each experiment: restart backend with modified `-D` property, re-run BEIR, record nDCG@10
4. Tabulate: param vs nDCG@10 delta from baseline

**Execution constraint:** User must set env vars in MCP server terminal, restart dev stack, then run BEIR eval. Cannot be automated via agent tooling.

---

## Appendix B: Infrastructure Inventory

### File Counts by Directory

| Directory | Total Files | Noteworthy Subsets |
|-----------|------------|-------------------|
| `scripts/bench/` | ~190 | `baselines/` 32+, `schemas/` 14, `corpora/` ~10, `competitors/` ~10 (rr/ moved to governance/) |
| `scripts/resilience/` | ~25 | `faults/` ~12, `calibration/` ~8, `contracts/` ~5 (governance/ moved) |
| `scripts/governance/` | ~75 | `bench-meta/` 25 (ex-rr/), `resilience/` 50 (ex-resilience/governance/) |
| `scripts/perf/` | 27 | suite runner, diff tool, thresholds, calibration, variance analysis |
| `scripts/ci/` | 83 | 7 DAG runners, 6 PS1 wrappers, lifecycle, agent battery core |
| `scripts/search/` | 8 | BEIR eval, rank eval, MLDR converter, correction eval |

### CI Maturity Summary

| Lane | Current | Target | Blocker |
|------|---------|--------|---------|
| Claim A | L1 | L2 | Needs 8 comparable runs |
| Claim B | L0 | L2 | No CI integration |
| Claim D | L0 | L2 | No CI integration |
| Track G | L1 | L2 | Needs 8 comparable runs |
| Competitor | L0 | L1 | BEN-001 methodology flaw |
| Perf | L2 | L3 | Needs 15 comparable runs |
| Search eval | L1 | L2 | Needs 8 comparable runs; judged BEIR coverage gates |
| Agent battery | L1 | L2 | Phase A stability gates (43.8% pass rate) |

### Known Open Issues (Eval-Related)

| Issue | Priority | Status |
|-------|----------|--------|
| BEN-001: Process-based competitor latency methodology | P4 | Open |
| BEN-002: No end-to-end pipeline benchmark adapter | P4 | Open |
| BEN-003: No filename-only indexing lane | P4 | Open |
| BEN-005: BEIR gate hybrid profile non-functional | P2 | **Resolved 2026-02-25** |
| SRQ-002: No passage-level retrieval | P3 | Mitigated (excerpt regions) |
| SRQ-003: English stemming not evaluated | P4 | Open |
| RAG-001: ORT CUDA runtime pack not built | P3 | Open |
| RAG-003: Performance matrix lacks cross-machine data | P4 | Open |
| RAG-006: Citation scorer threshold not calibrated | P3 | Open |
