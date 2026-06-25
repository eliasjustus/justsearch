---
title: "Search Known Issues Audit"
type: tempdoc
status: done
created: 2026-02-19
description: "Audit all known search/retrieval/accessibility issues to determine if they are still current, resolved, or outdated."
---

# 220: Search Known Issues Audit

## Goal

Investigate every open issue in:
- `docs/reference/issues/search-quality.md` (SRQ-*)
- `docs/reference/issues/retrieval-quality.md` (RAG-*)
- `docs/reference/issues/search-accessibility.md` (ACC-*)

Determine whether each issue is **still open**, **resolved**, or **outdated** based on the current codebase.

## Issues to Audit

### Search Quality (`search-quality.md`)
- [x] SRQ-002: No passage-level retrieval for interactive search (status: mitigated)
- [x] SRQ-003: English stemming not evaluated (status: open)

### Retrieval Quality (`retrieval-quality.md`)
- [x] RAG-001: ORT CUDA runtime pack not built or validated (status: open)
- [x] RAG-002: Token estimation heuristics break on dense text (status: open)
- [x] RAG-003: Performance matrix lacks 1M+ vector and cross-machine data (status: open)
- [x] RAG-004: Quantization rollout lacks cross-machine evidence (status: open)
- [x] RAG-005: FULLTEXT_FALLBACK documents have no citation support (status: open)
- [x] RAG-006: Citation scorer threshold not empirically calibrated (status: open)
- [x] RAG-007: Reranker model upgrade blocked on quality evaluation harness (status: blocked)
- [x] RAG-008: Embedding model upgrade to BGE-M3 deferred (status: open)
- [x] RAG-009: Speculative decoding deferred to v2 (status: deferred)

### Search Accessibility (`search-accessibility.md`)
- [x] ACC-001: Result row quick actions unreachable by keyboard (status: mitigated)
- [x] ACC-003: Match pills use substring heuristic (status: open)

## Findings

### SRQ-002: No passage-level retrieval — STILL MITIGATED (accurate)

**Verdict: Issue description is fully accurate. No status change needed.**

- Excerpt regions are implemented and working (`SearchOrchestrator` computes query-focused regions via `HighlightingOps.computeExcerptRegions()` with IDF-weighted term importance)
- Frontend renders them in comfort/rich density modes
- Interactive search is **still document-level only** — chunk-level search (`ChunkSearchOps`) is only used for RAG retrieval, not interactive search
- No chunk-level grouping in the result list UI
- The mitigation (excerpt regions) substantially improves UX for long documents, but the residual architectural gap (chunk-level hits grouped by parent doc) remains

### SRQ-003: English stemming — STILL OPEN (accurate)

**Verdict: No change. No stemmer exists.**

- `SsotAnalyzerRegistry.createIcuAnalyzer()` chain: `ICUTokenizer` → `ICUNormalizer2Filter` → `LowerCaseFilter` → optional `SynonymGraphFilter`
- No `SnowballFilter`, `KStemFilter`, `EnglishMinimalStemFilter`, or `PorterStemFilter` anywhere in codebase
- No stemming configuration in `SSOT/catalogs/analyzers.v1.json`
- Deliberate design choice documented as P4 awaiting BEIR benchmark evaluation

### RAG-001: ORT CUDA runtime pack — STILL OPEN (accurate)

**Verdict: No change. Infrastructure exists but pack not built.**

- `tools/build-gpu-booster-pack.ps1` exists but focuses on llama-server (generation), not ORT (embeddings)
- ONNX Runtime wiring exists in `WorkerSpawner.java` (native path forwarding, variant ID support)
- The ~2.2 GiB ORT+cuDNN pack has not been assembled or validated
- cuDNN redistribution licensing still unresolved

### RAG-002: Token estimation heuristics — STILL OPEN (partially improved)

**Verdict: Heuristic upgraded but calibration harness still missing. Update description.**

- `TokenEstimation.estimateTokens()` now uses a **hybrid char+word approach** (not pure word-counting as the issue implies):
  - Word-based: `ceil(words * 1.3)`, Character-based: `len / 4.0` (default), `len / 3.0` (dense), `len` (CJK)
  - Takes the maximum to avoid under-estimation
  - Detects dense text via whitespace ratio (<2%)
- 3 tests exist in `ChunkSplitterTokenEstimateTest.java` (dense ASCII, whitespace-rich, CJK)
- **Gap remains:** No calibration harness comparing estimated vs. actual tokenizer output across content types
- Issue description should note the heuristic was improved but the calibration recommendation still applies

### RAG-003: Performance matrix 1M+ — STILL OPEN (accurate)

**Verdict: No change. Tooling ready, data not collected.**

- Benchmark suite in `scripts/bench/` is complete (diff, promote, baseline, ratchet)
- Results exist only for single-machine, smaller datasets
- No evidence of 1M+ vector runs or cross-machine execution

### RAG-004: Quantization cross-machine evidence — STILL OPEN (accurate)

**Verdict: No change. Feature implemented, default still OFF.**

- `RuntimeConfig.VectorCfg.quantizationEnabled` defaults to `false`
- Configurable via YAML, env var, or system property
- Codec wiring complete in `JustSearchCodec`
- No cross-machine recall/latency evidence collected

### RAG-005: FULLTEXT_FALLBACK no citations — STILL OPEN (accurate)

**Verdict: No change. Confirmed in code.**

- `RagContextOps.executeRetrieval()`: when chunk search returns 0 hits, falls back to `searchFullDocsWithMeta()` with no structured chunk metadata
- `RemoteDocumentService.retrieveContext()`: `citations = response.getUsedChunks() ? ... : List.of()` — empty for fallback
- No virtual chunking or synthetic citation mechanism implemented

### RAG-006: Citation scorer threshold — STILL OPEN (accurate)

**Verdict: No change. Default 0.5, not calibrated.**

- `CitationScorerConfig.java` line 79: threshold hardcoded to `0.5`
- No calibration data from dogfooding sessions collected

### RAG-007: Reranker model upgrade — STILL BLOCKED (partially changed)

**Verdict: Blocker (tempdoc 216/BEN-004) is progressing but model not upgraded.**

- Current reranker: **MiniLM-L6-v2** (22M params) — unchanged
- `model-registry.v1.json` lists an alternate `gte-reranker-modernbert-base` but it's not wired as default
- Tempdoc 216 (eval harness consolidation) has acceptance criteria met per Phase 3g, but the model-specific upgrade gate hasn't been passed
- Latency issue persists (373ms for 10 candidates exceeds 200ms budget)

### RAG-008: BGE-M3 embedding upgrade — STILL DEFERRED (accurate)

**Verdict: No change. No progress.**

- Active model: `nomic-embed-text-v1.5.Q4_K_M.gguf` (768d)
- No BGE-M3 references found in codebase
- Dimension hardcoded as 768 in `EmbeddingService.java`

### RAG-009: Speculative decoding — STILL DEFERRED (accurate)

**Verdict: No change. No implementation.**

- References only in `third_party/llama.cpp/` (upstream) and future-features docs
- Eagle-3 not integrated into llama-server API
- VRAM budget conflict unchanged

### ACC-001: Keyboard actions — STILL MITIGATED (minor doc discrepancy found)

**Verdict: Mitigation is accurate, but docs claim Shift+F10 support that doesn't exist.**

- All 7 action buttons use `tabIndex={isCursor ? 0 : -1}` (confirmed in `ResultRow.tsx`)
- Buttons are keyboard-reachable via Tab on the cursor row only
- VirtualResultList has comprehensive keyboard navigation (arrows, j/k, Page Up/Down, Home/End, Enter, Space)
- **Discrepancy:** `search-accessibility.md` line 56 states "The context menu (`Shift+F10`) provides full keyboard access to all actions as a fallback" — but **no Shift+F10 handler exists** in `VirtualResultList.tsx` `handleKeyDown`. The context menu component exists but has no keyboard trigger binding from the result list.

### ACC-003: Match pills substring heuristic — STILL OPEN (accurate)

**Verdict: No change. Still using `containsAny()` substring matching.**

- `TextAnalysisUtils.computeMatchedFields()` still uses case-insensitive substring matching against `title` and `path` fields
- Lucene still only searches the `content` field (via `QueryParser` in `TextQueryOps.java`)
- No `MultiFieldQueryParser` or per-field `BooleanQuery` clauses
- Accepted as P4 — cosmetic pills, not functional impact

## Summary

| Issue | Documented Status | Actual Status | Action Needed |
|-------|-------------------|---------------|---------------|
| SRQ-002 | mitigated | **mitigated** | None |
| SRQ-003 | open | **open** | None |
| RAG-001 | open | **open** | None |
| RAG-002 | open | **open** | Update description (heuristic improved, calibration still missing) |
| RAG-003 | open | **open** | None |
| RAG-004 | open | **open** | None |
| RAG-005 | open | **open** | None |
| RAG-006 | open | **open** | None |
| RAG-007 | blocked | **blocked** (progressing) | Update blocker reference (tempdoc 216 acceptance criteria met) |
| RAG-008 | open | **open** | None |
| RAG-009 | deferred | **deferred** | None |
| ACC-001 | mitigated | **mitigated** | Fix doc: remove Shift+F10 claim (not implemented) |
| ACC-003 | open | **open** | None |

**Bottom line:** All 13 issues are still current. No issue has been resolved or made outdated.

## Doc corrections applied (2026-02-19)

1. **RAG-002** (`retrieval-quality.md`): Updated description, title, and component to reflect the improved hybrid char+word heuristic. Retained calibration recommendation.
2. **RAG-007** (`retrieval-quality.md`): Updated blocker status to note tempdoc 216 acceptance criteria met, and `gte-reranker-modernbert-base` available in registry.
3. **ACC-001** (`search-accessibility.md`): Replaced false Shift+F10 claim with a residual gap note documenting the missing keyboard trigger.

---

## Staleness review (2026-05-18)

Body contains explicit closure markers; marking `done` as part of the staleness audit. Classification: CLOSED. Stale for 88 days at audit time.

