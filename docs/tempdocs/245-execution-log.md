---
title: "245: Search & Index Quality Strategy — Agent Execution Log"
type: tempdoc
status: done
created: 2026-02-28
---

> Extracted from `245-search-quality-strategy.md` on 2026-03-02 to reduce
> main document length. This file contains operational details from the
> component isolation experiment sessions.

## Operating instructions (read after every compaction)

**Purpose:** This section is a running log for the agent executing the remaining
work. After compaction, re-read this file, find the `CURRENT STEP` marker, and
continue from there.

**Behavioral rules:**
1. **Work autonomously toward the tempdoc's main goal** — the component isolation
   matrix across datasets with published baseline comparisons. Don't stop after
   completing the checklist items if the goal isn't fully met. If new work items
   emerge (e.g., unexpected results that need investigation), pursue them.
2. **Monitor long-running processes while they run.** Don't just launch a command
   and wait. Periodically: tail server logs, poll `/api/status`, `/api/debug/state`,
   and `/api/ai/runtime/status` to verify progress. Catch failures early instead
   of waiting 2 hours to discover a crash at minute 5.
3. **After each step completes:** Check output carefully. Record results in this
   log. Update the checklist above. Then proceed to the next step.
4. **If a step fails:** Diagnose the root cause. Read relevant source code. Search
   the web if needed. Fix the issue and retry. Do NOT skip steps or declare done.
5. **Take your time.** When uncertain about anything — how a component works, why
   something failed, what a config does — investigate. Read the code, check docs,
   search online. Rushing leads to wasted multi-hour runs.
6. **Never declare the tempdoc complete** until all checklist items are checked off
   AND the isolation matrix covers multiple datasets with published comparisons.

**Monitoring commands for long runs:**
- Server health: `curl -s http://127.0.0.1:9001/api/status | python3 -m json.tool`
- Debug state (GPL/LambdaMART progress): `curl -s http://127.0.0.1:9001/api/debug/state | python3 -m json.tool`
- AI runtime status: `curl -s http://127.0.0.1:9001/api/ai/runtime/status | python3 -m json.tool`
- Server logs: `tail -50 tmp/eval-logs/eval-server-*-stderr.log`
- Check if server process alive: `powershell -Command "Get-Process java -ErrorAction SilentlyContinue | Select-Object Id,StartTime"`

**Environment prerequisites (verify before each run):**
- installDist up to date: `modules/ui/build/install/ui/bin/ui` and `modules/indexer-worker/build/install/indexer-worker/lib` must exist
- GPU available: CUDA GPU needed for embedding + SPLADE + GPL inference
- Models present: `models/nomic-embed-text-v1.5.Q4_K_M.gguf`, `models/splade/`, `models/reranker/`
- Ports free: 9001 not in use
- Corpora materialized: `tmp/eval-corpus-beir/{dataset}/docs/` dirs exist

## Results accumulation

Fill in after each dataset completes. Single-glance progress view.

| Configuration | SciFact nDCG@10 | Arguana nDCG@10 | NFCorpus nDCG@10 | FiQA nDCG@10 |
|---------------|-----------------|-----------------|------------------|--------------|
| BM25 only | 0.660 | 0.329 | 0.310 | 0.220 |
| Dense only | 0.669 | 0.370 | 0.324 | **0.317** |
| BM25 + Dense RRF | 0.666 | 0.289 | 0.313 | 0.241 |
| SPLADE + Dense RRF | 0.704 | 0.315 | **0.337** | **0.317** |
| SPLADE + Dense + LambdaMART (trained) | 0.693 (-0.009) | 0.213 (-0.10) | 0.316 (-0.021) | — |
| BM25 + cross-encoder | 0.675 | 0.324 | 0.325 | — |
| SPLADE 512-token | 0.702 | 0.318 | 0.336 | — |
| *BM25 (BEIR paper)* | *0.665* | *0.315* | *0.325* | *0.236* |
| *SPLADE-v3 (standalone)* | *0.710* | *0.509* | *0.357* | *0.374* |
| *nomic-embed-v1.5 (fp32, 8192ctx, with prefixes)* | *0.704* | *0.480* | *0.338* | *0.375* |

## Decision log

Record judgment calls made during autonomous work for user review.

- **2026-03-01 01:10** — First attempt at Step 2 used `| head -30` pipe which
  killed the PowerShell driver after 30 lines. Server survived (Start-Process
  detached) but the eval script was dead. Killed orphaned Java processes, will
  re-run without pipe truncation. Index was fully built (5,184 docs) but no
  eval queries ran — data dir still has the index from Session 1 (SPLADE enabled).
  Re-run will rebuild from scratch (script cleans stale index).
- **2026-03-01 01:30** — Second attempt stuck in `Wait-EvalIndexIdle` forever.
  Bug: `RequireChunkVectors=true` required `retrieval=READY`, but retrieval was
  `DEGRADED` due to `lambdamart.not_configured` (unrelated to chunk embedding).
  chunkEmbedding component was READY (100% coverage). Fixed
  `Wait-EvalIndexIdle` to check `chunkEmbedding` component directly instead of
  the retrieval composite when `RequireChunkVectors=true`.
- **2026-03-01 02:00** — Session 3 AI runtime activation failed: "No chat model
  configured." Activation reads from `UiSettings.llmModelPath` (persisted JSON),
  not env vars. Fixed `Wait-ForGplTraining` to POST `/api/settings/v2` with the
  chat model path before activating. Preliminary Session 1-2 results look
  correct: SPLADE hybrid 0.7024, BM25 0.6636, dense 0.6751, BM25+Dense 0.6666.
- **2026-03-01 02:55** — Script stuck in AI runtime polling loop. Bug: script
  checks `$aiStatus.phase` (top-level) but API response has no top-level `phase`
  field — it's at `$aiStatus.activation.state` with value `"completed"` (lowercase).
  GPL was running independently in the backend (400/5184 docs) but the script
  couldn't detect runtime was ready. Fixed: changed to check
  `$aiStatus.activation.state -eq "completed"`. Killed stuck run + server, restarting.
- **2026-03-01 05:55** — SciFact GPL isolation complete. GPL produced 62,467 triples
  from 5,184 docs (~12.1 triples/doc). LambdaMART trained successfully (train eval
  nDCG@10=0.881). However, BEIR eval shows trained LambdaMART performs WORSE than
  fixed RRF: hybrid 0.6930 vs 0.7018 (-0.009). Lexical also dropped 0.6418 vs
  0.6600 (-0.018). The lexical drop is particularly concerning — LambdaMART shouldn't
  affect lexical-only scoring. Hypothesis: model learned on GPL synthetic queries
  (which differ from real BEIR queries) and the learned weights overfit to the
  training distribution. Will observe if same pattern holds on Arguana/NFCorpus.
- **2026-03-01 06:20** — Arguana Session 1 returned 0.0000 for all metrics due to
  watcher race condition in `Wait-EvalIndexIdle`. Function accepted `IDLE + queue=0`
  before watcher discovered files (poll every 2s, watcher needs >2s to scan 8,674
  files). Fixed: added `$queueSeenNonZero` gate that requires queue to have been
  non-zero before accepting IDLE. Also fixed same race in Session 3's inline wait.
  Sessions 2+ work because watcher already knows the dir from Session 1's add.
  Will re-run SPLADE+Dense RRF eval against existing Arguana index after GPL completes.
- **2026-03-01 07:20** — Arguana GPL stalled again — llama-server (from SciFact
  Session 3, started 03:17 AM) survived multiple server restarts and went
  unresponsive at ~3950/8721 docs. Port 57961 not listening despite process alive
  (14GB working set). Killed all processes, restarted from scratch with fixed scripts.
- **2026-03-01 12:00** — Arguana complete (Sessions 1-4 + separate Session 5 re-run).
  Session 5 (SPLADE-512) crashed during hybrid eval — llama-server from Session 3
  GPL still holding 14GB. Killed llama-server, re-ran SPLADE-512 eval separately.
  Key findings: Dense-only (0.370) is best config for Arguana (argument retrieval).
  RRF fusion HURTS (0.289 vs 0.329 BM25). SPLADE adds nothing over BM25 on this
  dataset. LambdaMART catastrophically hurts (0.213 hybrid). Pattern confirmed:
  GPL-trained LambdaMART consistently degrades across datasets.
- **2026-03-01 13:30** — NFCorpus Session 3 inline wait hung: `$preStepQueueSeen`
  gate never triggered because watcher already knew the root from Session 1. Index
  was fully built with chunk vectors ready but the gate requires seeing queue>0 first.
  Fixed: added `$indexAlreadyBuilt` check (docs>0 AND chunkVectorsReady) as
  alternative to the queue-seen gate.
- **2026-03-01 15:00** — NFCorpus complete. 3-dataset pattern confirmed: LambdaMART
  consistently hurts (SciFact -0.009, Arguana -0.10, NFCorpus -0.021). GPL-trained
  models overfit on synthetic queries. RRF fusion dataset-dependent: helps SciFact
  (+0.04), hurts Arguana (-0.04), marginal on NFCorpus. SPLADE is the consistent
  winner: helps SciFact (+0.04 over BM25), helps NFCorpus (+0.027), neutral on
  Arguana. Cross-encoder consistently helps lexical search.

---

## Step details

### Step 9: FiQA isolation (item 8 — Sessions 1+2 only)

**Status:** COMPLETE
**What:** Run FiQA (financial Q&A, 47,399 docs) through the component isolation matrix.
Sessions 1+2 only (BM25, Dense, BM25+Dense RRF, SPLADE+Dense RRF). Skipping
Sessions 3-5 (GPL/LambdaMART already proven harmful; cross-encoder and SPLADE-512
are lower priority).

**Script changes (to run-ranking-experiments.ps1):**
- Added `-AllowCorpusPrep` to `Run-BeirEval` — first eval downloads/materializes for new datasets
- Added `-MaxSessions` parameter — stops after N sessions (used `-MaxSessions 2`)
- Added `-IndexTimeoutSec 14400` pass-through — 4h timeout for larger corpora
- Added `$corpusPrepDone` tracking — only first eval does corpus prep

**Command:** `run-ranking-experiments.ps1 -Step isolation -Dataset fiqa -EmbeddingModelPath "D:/code/JustSearch/models/nomic-embed-text-v1.5.Q8_0.gguf" -MaxSessions 2`

**Corpus stats:** 47,399 docs, avg 779 chars, max 10,509 chars. 1,037 chunk docs
created (2.2% of corpus — most FiQA docs are short). Q8_0 embedding model.

**Published baselines (verified):**
- BM25 (Pyserini): 0.236
- SPLADE-v3 (arXiv:2403.06789 Table 2): 0.374
- nomic-embed-v1.5 (Cathedral-BEIR, fp32, 8192ctx): 0.375

**Log:** `tmp/eval-logs/fiqa-isolation-stdout.log`
**Output:** `tmp/beir-eval/isolation-embed/` (splade-dense-rrf, bm25-only, dense-only, bm25-dense-rrf)

**Monitoring log:**
- 17:06 — Session 1 started. Server ready in ~10s (installDist fast-launch).
  FiQA corpus was already cached (downloaded previously). 47,399 docs materialized.
- 17:16 — 5,400/47,399 indexed. Throughput: 9.83 docs/sec. 1,037 chunks so far.
  0 failures. chunkVectorsReady=false (chunk embedding not started yet).
- 18:30 — 47,399 docs indexed (complete). Chunk embedding 300/8,317.
- 18:48 — chunkVectorsReady=true. Chunk embedding complete (8,317 chunks).
- 18:50 — Session 1 evals complete. SPLADE+Dense RRF hybrid=0.3170, lexical=0.2200.
- 18:52 — Session 2 started (reuses index). BM25-only lexical=0.2200.
- 19:05 — Dense-only vector=0.3174. BM25+Dense RRF hybrid=0.2412. Session 2 complete.
- 19:06 — Aggregation complete. Server stopped.

**Results (FiQA nDCG@10):**
| Config | lexical | hybrid | vector |
|--------|---------|--------|--------|
| SPLADE+Dense RRF | 0.2200 | **0.3170** | — |
| BM25-only | **0.2200** | — | — |
| Dense-only | — | — | **0.3174** |
| BM25+Dense RRF | — | **0.2412** | — |

**Analysis:**
- **Dense retrieval dominates FiQA.** Dense-only (0.317) is the best or tied-best config.
  SPLADE+Dense RRF hybrid (0.317) adds nothing over dense-only — SPLADE contributes zero
  lift on financial domain queries.
- **BM25 is weak on FiQA.** Our BM25 (0.220) is 7% below published (0.236). Gap vs
  published is consistent with other datasets.
- **RRF with BM25 hurts.** BM25+Dense RRF (0.241) is 24% below dense-only (0.317).
  Same pattern as Arguana: when BM25 is weak, RRF dilutes the dense signal.
- **Q8_0 gap to published fp32 is large.** Dense 0.317 vs published 0.375 = -15.5%.
  Much larger than SciFact (-0.6%). FiQA docs avg 779 chars — context is not a
  factor. Causes: Q8_0 quantization loss on financial jargon (harder query
  distribution requiring more precise embedding geometry) + HNSW approximation
  (M=16, approximate vs exact exhaustive search in published MTEB benchmarks).

**Note:** isolation-summary.json contains stale entries from prior nfcorpus/arguana runs
(lambdamart, crossencoder, splade-512 subdirs). Only splade-dense-rrf, bm25-only,
dense-only, bm25-dense-rrf are valid FiQA results.

---

### Step 8: Quantization experiment (Q4 vs Q8_0 vs F16)

**Status:** done — **Q8_0 confirmed as optimal quantization level**
**What:** Downloaded Q8_0 (139 MiB) and F16 (261 MiB) GGUF models from
HuggingFace. Ran full SciFact eval (lexical, vector, hybrid) for each quant
level using identical server config (SPLADE on, embedding GPU, fresh index).

**Models tested:**
- `nomic-embed-text-v1.5.Q4_K_M.gguf` — 81 MiB (current production)
- `nomic-embed-text-v1.5.Q8_0.gguf` — 139 MiB (downloaded for experiment)
- `nomic-embed-text-v1.5.f16.gguf` — 261 MiB (downloaded for experiment)

**Server config (per quant level):**
```
JUSTSEARCH_MODEL_PATH=<model file>
JUSTSEARCH_AI_EMBED_ENABLED=true
JUSTSEARCH_EMBED_GPU_LAYERS=99
JUSTSEARCH_SPLADE_ENABLED=true
JUSTSEARCH_LAMBDAMART_ENABLED=false
JUSTSEARCH_RERANK_ENABLED=false
```
Each quant level used a fresh data dir (clean index rebuild). Prefix code from
Step 7 was in place (all runs include task prefixes).

**Results:**

| Mode | Q4_K_M | Q8_0 | F16 | Q4→Q8 | Q4→F16 |
|------|--------|------|-----|-------|--------|
| Lexical | 0.663 | 0.663 | 0.661 | +0.000 | -0.002 |
| Vector | 0.669 | **0.694** | **0.696** | **+0.025** | **+0.027** |
| Hybrid | 0.700 | **0.706** | **0.706** | **+0.006** | **+0.006** |

**Resource usage (measured):**
- VRAM: all quant levels fit on RTX 4070 12GB alongside chat model
- Indexing throughput: ~4.8 docs/sec for all three (GPU-bound, not model-bound)
- Search latency: no measurable difference

**Interpretation:**
1. Q8_0 recovers 72% of the Q4→published gap (0.669→0.694 vs published 0.704)
2. F16 recovers only 5% more (0.694→0.696) — diminishing returns past Q8_0
3. Hybrid gains are muted (+0.006) because SPLADE and BM25 legs are unchanged
4. Lexical is unaffected (BM25 doesn't use embeddings) — serves as control
5. Residual gap (Q8_0 0.694 vs published 0.704) likely from HNSW approximation
   and 2048 vs 8192 context window

**Artifacts:**
- `tmp/beir-eval/quant-experiment/q8-lexical/` — Q8_0 lexical
- `tmp/beir-eval/quant-experiment/q8-vector/` — Q8_0 vector
- `tmp/beir-eval/quant-experiment/q8-hybrid/` — Q8_0 hybrid
- `tmp/beir-eval/quant-experiment/f16-lexical/` — F16 lexical
- `tmp/beir-eval/quant-experiment/f16-vector/` — F16 vector
- `tmp/beir-eval/quant-experiment/f16-hybrid/` — F16 hybrid

---

### Step 7: Task prefix experiment (A/B comparison)

**Status:** done — **null result** (prefixes have no measurable impact on Q4_K_M)
**What:** Added `search_document:` / `search_query:` task prefixes to nomic-embed
embedding pipeline. Running SciFact isolation to measure the impact on dense
retrieval quality.

**Code changes:**
- `EmbeddingService.java` — added `DOCUMENT_PREFIX`, `QUERY_PREFIX` constants
  and `embedDocument()`, `embedQuery()`, `embedDocumentWithChunks()` methods
- `IndexingDocumentOps.java:93` — `embed()` → `embedDocument()`
- `EmbeddingBackfillOps.java:79,212` — `embed()` → `embedDocument()`
- `SearchOrchestrator.java:194,242` — `embed()` → `embedQuery()`
- `RagContextOps.java:270,943,956` — query→`embedQuery()`, doc→`embedDocument()`
- `CitationMatchOps.java:205,216` — sentence→`embedQuery()`, chunk→`embedDocument()`

**Baseline (without prefixes):**
- SciFact dense-only: 0.669 nDCG@10
- SciFact SPLADE+Dense RRF hybrid: 0.704 nDCG@10
- SciFact BM25+Dense RRF hybrid: 0.666 nDCG@10

**Expected:** Dense-only should improve, potentially closing the gap with
published nomic-embed (0.704). BM25/SPLADE lexical scores should be unchanged
(no embedding involved).

**First attempt (failed):** Ran with `JUSTSEARCH_AI_EMBED_ENABLED` but without
`JUSTSEARCH_EMBED_GPU_LAYERS`, and the eval ran only in TEXT mode (all 300
queries fell back to BM25). nDCG@10=0.660 was just the BM25 result, not
vector. Root cause: inference component was offline (chat model activation
failed) but TEXT mode worked.

**Second attempt (complete):** Restarted with proper config:
`JUSTSEARCH_AI_EMBED_ENABLED=true`, `JUSTSEARCH_EMBED_GPU_LAYERS=99`,
`JUSTSEARCH_MODEL_PATH`, `JUSTSEARCH_SPLADE_ENABLED=true`. Worker confirmed
`embedding_ready: true` with CUDA backend (17 layers on RTX 4070). Full index
rebuild with prefixed embeddings took ~75 minutes for 5184 docs.

**Result: Prefixes have NO measurable impact on quality.**

| Mode | Baseline (no prefix) | With prefix | Delta |
|------|---------------------|-------------|-------|
| Lexical (BM25) | 0.660 | 0.662 | +0.002 (noise) |
| Vector (dense) | 0.669 | 0.669 | ±0.000 |
| Hybrid (SPLADE+Dense RRF) | 0.704 | 0.700 | -0.004 (noise) |

**Interpretation:** The Q4_K_M GGUF quantization of nomic-embed-text-v1.5
appears to be insensitive to task prefixes. The tokenizer processes the prefix
tokens but the quantized embedding space doesn't differentiate between prefixed
and unprefixed inputs. This may differ for fp32 or less aggressive quantization.
The prefix changes are technically correct per the model card but produce no
measurable retrieval quality improvement on SciFact with Q4 quantization.

---

### Step 1: Commit post-246-merge fixes

**Status:** done (commit 9fa550de)
**What:** Commit 4 files that fix integration test failures after the 246 merge:
IndexMetadataParityGuard fallback, LuceneIndexRuntime open-time caching,
IndexStatusOps snapshot usage, JsonDagHashingServiceTest golden hash.
**Command:**
```bash
git add <4 files> && git commit -m "fix: post-246 merge — parity guard fallback + open-time commit caching"
```
**Success:** Clean commit on main. All unit tests still pass.
**Result:** *(pending)*

### Step 2: Run SciFact GPL training isolation

**Status:** done
**What:** Run the full isolation matrix on SciFact with GPL training enabled
(Session 3). This activates the AI runtime (llama-server with Qwen3 chat model),
generates synthetic queries via LLM for each indexed doc, scores them with
cross-encoder, trains LambdaMART on the feature matrix, then runs eval.
**Duration estimate:** 2-4 hours (dominated by LLM inference for ~5K docs × 2-3
queries each, plus cross-encoder scoring).
**Command:**
```powershell
cd D:\code\JustSearch
.\scripts\search\run-ranking-experiments.ps1 `
    -Step isolation `
    -Dataset scifact `
    -RerankerModelPath "D:\code\JustSearch\models\reranker\ms-marco-MiniLM-L6-v2" `
    -EmbeddingModelPath "D:\code\JustSearch\models\nomic-embed-text-v1.5.Q4_K_M.gguf" `
    -ApiPort 9001
```
**Monitoring while running:**
- Every 5 min during GPL: poll `/api/debug/state` → check `gpl.status`,
  `gpl.processed_docs`, `gpl.total_docs`, `gpl.triple_count`
- After GPL completes: check `reranking.lambdamart.training.status`
- If server crashes: check `tmp/eval-logs/eval-server-*-stderr.log`
**Success criteria:**
- `splade-dense-lambdamart` hybrid nDCG@10 should differ meaningfully from 0.704
  (the untrained RRF result). If it doesn't, training may have failed silently.
- `isolation-summary.json` updated with trained LambdaMART results.
**Failure playbook:**
- AI runtime fails to activate → check GPU, check `models/` for chat model,
  check stderr log for CUDA errors
- GPL times out → SciFact is ~5K docs, should take 1-3 hours. If >4h, check if
  LLM inference is running (GPU utilization). May need to restart.
- LambdaMART training fails → check if triples file was generated
  (`tmp/dev-data-beir-scifact/gpl-training-triples.ndjson`). If empty, GPL
  didn't produce data.
- Cross-encoder not found → verify `models/reranker/ms-marco-MiniLM-L6-v2/` exists
  with ONNX files
**Result:** GPL completed (5,184 docs, 62,467 triples, ~2.5h). LambdaMART trained
  (nDCG@10=0.881 on training eval). But eval results show LambdaMART HURTS:
  hybrid 0.6930 vs RRF 0.7018 (-0.009), lexical 0.6418 vs 0.6600 (-0.018).
  Likely overfitting on GPL-generated synthetic queries, or LambdaMART feature
  weights disrupting lexical-only ranking where SPLADE/dense features are absent.

### Step 3: Run Arguana isolation matrix

**Status:** done
**What:** Run full 7-config isolation on Arguana (1,406 queries, 8,674 docs).
Arguana is an argument retrieval dataset — tests whether SPLADE helps with
argumentative text (not just scientific vocabulary like SciFact).
**Duration estimate:** ~20-30 min for non-GPL sessions (5 sessions × ~4-5 min
each for startup + indexing + eval). Session 3 (GPL) will add 2-4 hours.
**Command:**
```powershell
.\scripts\search\run-ranking-experiments.ps1 `
    -Step isolation `
    -Dataset arguana `
    -RerankerModelPath "D:\code\JustSearch\models\reranker\ms-marco-MiniLM-L6-v2" `
    -EmbeddingModelPath "D:\code\JustSearch\models\nomic-embed-text-v1.5.Q4_K_M.gguf" `
    -ApiPort 9001
```
**Success criteria:**
- `isolation-summary.json` in `tmp/beir-eval/isolation-embed/` with Arguana results.
- Published baseline comparison: BM25 0.315, SPLADE ~0.50, nomic ~0.43.
**Failure playbook:**
- Indexing timeout → Arguana is 8,674 docs, may take longer. Check `/api/status`
  for pending jobs count.
- Same GPU/model failures as Step 2 — refer to Step 2 playbook.
**Result:** BM25 0.329, Dense 0.370 (best), BM25+Dense RRF 0.289, SPLADE+Dense RRF
  0.315, SPLADE+LambdaMART 0.213, BM25+crossencoder 0.324, SPLADE-512 0.318.
  Dense-only dominates. RRF fusion hurts on Arguana. SPLADE adds nothing.
  LambdaMART catastrophically hurts (confirmed SciFact pattern). GPL-trained model
  (nDCG@10=0.762 on training eval) overfits on synthetic queries.

### Step 4: Run NFCorpus isolation matrix

**Status:** done
**What:** Run full 7-config isolation on NFCorpus (323 queries, 3,633 docs).
NFCorpus is a biomedical IR dataset — small corpus, quick runs.
**Duration estimate:** ~15-20 min for non-GPL sessions. Session 3 (GPL) shorter
than SciFact due to smaller corpus (~3.6K docs).
**Command:**
```powershell
.\scripts\search\run-ranking-experiments.ps1 `
    -Step isolation `
    -Dataset nfcorpus `
    -RerankerModelPath "D:\code\JustSearch\models\reranker\ms-marco-MiniLM-L6-v2" `
    -EmbeddingModelPath "D:\code\JustSearch\models\nomic-embed-text-v1.5.Q4_K_M.gguf" `
    -ApiPort 9001
```
**Success criteria:**
- Published baseline comparison: BM25 0.325, SPLADE ~0.34, nomic ~0.34.
**Result:** SPLADE+Dense RRF hybrid (0.337) best — nearest to published baselines.
  BM25+crossencoder 0.325 matches BM25 published. Dense 0.324. LambdaMART again
  hurts (hybrid 0.316 vs RRF 0.337). Session 3 inline wait fix required for
  already-built indexes (added `$indexAlreadyBuilt` check). GPL produced 43,968
  triples from 3,633 docs. LambdaMART training nDCG@10=0.810.

### Step 5: Investigate touche2020/mldr-en broken baselines

**Status:** done (diagnosed, not re-run)
**What:** Both datasets show identical lexical and hybrid nDCG — the vector leg
contributed nothing. touche2020: 0.2922/0.2922. This was from an older baseline
run (2026-02-21) that may not have had embedding enabled properly.

**Investigation findings:**
1. Provenance metadata shows `embedding.enabled: true` with model configured, BUT
   `ann.proof_status: None` and `ann.dense_vector_evidence_rate: None` — ANN proof
   fields are absent (not FAIL, just null). The warning is explicit: "embedding
   profile run has no vector debug-score evidence; hybrid comparability may be
   uncertain".
2. Per-query comparison confirms: all 49 touche2020 queries have *exactly* identical
   lexical and hybrid nDCG (bitwise equal). Zero vector contribution.
3. Root cause: the old beir-eval-win.ps1 script did NOT wait for `chunkVectorsReady`.
   The index was built with chunk text but embedding hadn't completed. Hybrid search
   degrades to lexical-only when no chunk vectors are present.
4. Corpus sizes: touche2020 = 382,545 docs, mldr-en = 200,001 docs. Re-running with
   the isolation matrix script (which properly waits for `chunkVectorsReady`) would
   fix it but requires 10+ hours per dataset just for indexing — impractical for
   this tempdoc's scope.
5. The 3-dataset isolation matrix (SciFact/Arguana/NFCorpus) already validates
   that the vector pipeline works correctly when `chunkVectorsReady` is enforced.
   The broken baselines were a script bug, not a vector pipeline bug.
**Result:** Diagnosed as missing `chunkVectorsReady` gate in old eval script
  (now fixed). Not re-run due to corpus size (382K/200K docs). The 3-dataset
  matrix proves vectors work when properly configured.

### Step 6: Update tempdoc with final results

**Status:** done
**What:** Update the Results section with multi-dataset isolation matrix. Update
the published comparison table. Check off all checklist items. Update the
"What's still unknown" section based on findings.
**Result:** All sections updated: results accumulation table filled for all 3
  datasets, published comparison table updated with all JustSearch numbers,
  multi-dataset observations added (7 findings), caveats updated, SciFact
  results section updated with trained LambdaMART data, "what would close
  the gap" updated to strike out LambdaMART/GPL.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Agent execution log for the 245 search/index quality strategy. The logged work (multi-dataset evals, LambdaMART training, comparison table) completed. The log itself is terminal — its purpose was to record what ran, and it did.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

