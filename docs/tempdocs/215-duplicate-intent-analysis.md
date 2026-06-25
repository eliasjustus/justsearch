---
title: "215 — Duplicate Intent Analysis & Implementation"
---

# 215 — Duplicate Intent Analysis & Implementation

**Date**: 2026-02-18 (scaffold) / 2026-02-19 (investigation completed + code-verified) / 2026-02-19 (implementation) / 2026-02-20 (D-4 implemented)
**Purpose**: Analyze and implement fixes for duplicate intent found across the last ~2 months of commits (since ~2025-12-18). Includes recovery of deleted tempdocs from git history.

## Implementation Checklist

### D-7 (actionable now)
- [x] Add comment blocks in `SearchTool.java` above `DEFAULT_LIMIT` and in `AgentLoopService.java` above `MAX_TOOL_RESULT_CHARS` documenting the three-layer truncation and policy history (tempdocs 208, 213)
- [x] Implement per-result char budget in `SearchTool.formatResults()`: accumulate chars per hit across excerpt regions, cap at `MAX_TOOL_RESULT_CHARS / hits.size()`
- [x] Expose `MAX_TOOL_RESULT_CHARS` value to `SearchTool` — resolved: direct `EnvRegistry.AGENT_MAX_TOOL_RESULT_CHARS.getInt(4000)` read in `formatResults()`, consistent with `DEFAULT_LIMIT` pattern; no coupling to `AgentLoopService` internals
- [x] Empirical validation complete via existing RAG eval artifacts in `build/test-results/rag-eval/`. The two format-variant runs from tempdoc 213 Step 2 directly cover the D-7 parameter space:
  - `rag-eval-result.v1.agent-k5-exc200-bud900.json` (old): fact_coverage=0.424, faithfulness=0.215, citation_precision=0.333
  - `rag-eval-result.v1.agent-k3-exc800-bud4000.json` (Approach A = D-7 params): fact_coverage=0.649, faithfulness=0.417, citation_precision=0.500
  - +53% fact coverage, +94% faithfulness, +50% citation precision vs baseline
  - Live agent_chat validation (2026-02-20) confirmed all 3 results receive content (1333 chars/result) with per-result budget working correctly. Pre-fix A/B battery manifests in `tmp/agent-evidence/` have no corpus ingestion recorded — unreliable for search quality, consistent with tempdoc 213 INVALID finding.

### D-4 (picked up in this tempdoc — 2026-02-20)

#### Design decisions (resolved)

- **Worker wrapper**: New public class `WorkerModelDiscovery` in `modules/reranker`. Static `discoverAll()` calls `OnnxModelDiscovery.resolve(null, modelName, null)` for `"reranker"` and `"citation-scorer"`. `OnnxModelDiscovery` stays package-private.
- **Proto**: Extend `HealthCheckResponse` with `repeated OnnxDiscoveredModel onnx_models = 7`. New message `OnnxDiscoveredModel { model_name, found, path, auto_discovered }`. No new RPC — the last-known-good cache (see below) already handles Worker-down timing, and the added wiring of a dedicated `GetFeatureStatus` RPC is not worth it here.
- **Worker caching**: `KnowledgeServerGrpcWiring.createGrpcServer()` calls `WorkerModelDiscovery.discoverAll()` at startup and passes to `GrpcHealthService` via a new constructor overload. One FS scan at startup; no FS I/O on each health poll.
- **Head-side cache**: `RemoteKnowledgeClient.getHealthCheck()` is called frequently for status polling. After each successful RPC, update an `AtomicReference<List<OnnxModelStatus>>` field. New domain record `OnnxModelStatus(modelName, found, path, autoDiscovered)` — not a proto type. New functional interface `WorkerFeatureCache { List<OnnxModelStatus> getOnnxModels(); }`. Both in `io.justsearch.app.services.worker`.
- **Injection**: `AppFacadeBootstrap` exposes `public WorkerFeatureCache workerFeatureCache()` returning `knowledgeClient::getLastKnownOnnxModels`. `HeadlessApp` gets it from `bootstrap` and passes it via `LocalApiServer.Builder.workerFeatureCache()`. `LocalApiServer` passes it to `RuntimeActivationService`. `AppFacade` interface unchanged.
- **`RuntimeActivationService` simplification**: Keep steps 1 (disabled env var) and 2 (explicit path env var) — these are legitimately Head-side config. Replace steps 3–4 (FS walk) with a lookup against `workerFeatureCache.getOnnxModels()`. Delete `isCompleteModelDir()`, `resolveBaseDir()`, `MODEL_FILE`, `TOKENIZER_FILE`. New parameter is nullable — graceful degradation to `inactive/not_found` when null (preserves test backward-compat with 5-arg constructor).
- **Stale-discovery UX**: Accept cache-at-startup. Current behavior is actually *more* misleading — Head shows `active` immediately after model install, but the Worker won't use it until restart (since `RerankerConfig` is resolved at startup). The fix makes Head and Worker consistent. Document with a code comment.

#### Files

| File | Change |
|------|--------|
| `modules/reranker/…/WorkerModelDiscovery.java` | **new** — public wrapper, `discoverAll()` |
| `modules/app-services/…/OnnxModelStatus.java` | **new** — domain record |
| `modules/app-services/…/WorkerFeatureCache.java` | **new** — functional interface |
| `modules/ipc-common/src/main/proto/indexing.proto` | add `OnnxDiscoveredModel` message + field 7 on `HealthCheckResponse` |
| `modules/indexer-worker/…/GrpcHealthService.java` | new constructor overload + populate `onnx_models` in `check()` |
| `modules/indexer-worker/…/KnowledgeServerGrpcWiring.java` | call `discoverAll()`, pass to `GrpcHealthService` |
| `modules/app-services/…/RemoteKnowledgeClient.java` | `AtomicReference` cache + `getLastKnownOnnxModels()` + update in `getHealthCheck()` |
| `modules/app-services/…/AppFacadeBootstrap.java` | `workerFeatureCache()` method |
| `modules/ui/…/LocalApiServer.java` | `workerFeatureCache` on `Builder`, pass to `RuntimeActivationService` |
| `modules/ui/…/HeadlessApp.java` | wire `bootstrap.workerFeatureCache()` into builder |
| `modules/ui/…/RuntimeActivationService.java` | new nullable param, simplify `resolveOneOnnxFeature()`, delete FS walk code |

#### Critical evaluation (2026-02-20)

**Pre-implementation reads completed** for `RemoteKnowledgeClient.executeHealthRpc`, `AppFacadeBootstrap`, `HeadlessApp`, `RuntimeActivationServiceTest`. Key findings:

- `executeHealthRpc` never returns null — throws on failure. Cache update is safe on any return.
- `AppFacadeBootstrap.knowledgeClient` can be null (no knowledge server). `workerFeatureCache()` must handle this.
- `HeadlessApp` wiring path is clean: `bootstrap` is in scope when `LocalApiServer.builder()` is called.
- `RuntimeActivationServiceTest` had **zero coverage** for the ONNX feature status path. Fixed: 5 tests added covering all branches of `resolveOneOnnxFeature()`.

**Cost/benefit analysis — the full D-4 is over-engineered:**

The duplication caused exactly one bug (`02fafeac`, sidecar path mismatch). That bug is fixed. The two implementations now check the same paths in the same order. Future divergence requires changing `OnnxModelDiscovery.resolve()` without updating `RuntimeActivationService` — unlikely given the tempdoc documents the coupling.

Full D-4 costs:
- 3 new files + 8 modified files = 11 file touches
- Proto change (permanent — field 7 lives in wire format forever)
- New caching layer in `RemoteKnowledgeClient` with last-known-good semantics
- New injection plumbing through 4 files (`AppFacadeBootstrap` → `HeadlessApp` → `LocalApiServer.Builder` → `RuntimeActivationService`)
- New failure mode: Worker down → feature status falls back to `inactive/not_found` (currently works standalone)
- Zero existing test coverage for the changed path

Full D-4 gains:
- Single source of truth for ONNX discovery (Worker only)
- ~60 lines deleted from `RuntimeActivationService`
- Consistent UI: Head no longer shows `active` when Worker won't use the model until restart

**Simpler alternative — shared code, no proto/cache/wiring:**

Make `OnnxModelDiscovery` accessible from the Head (public wrapper or make public directly) and call it from `RuntimeActivationService.resolveOneOnnxFeature()` instead of reimplementing the walk. This eliminates the duplication with 2 files touched:

1. `modules/reranker/WorkerModelDiscovery.java` — new, public static method wrapping `OnnxModelDiscovery.resolve()`
2. `modules/ui/RuntimeActivationService.java` — call `WorkerModelDiscovery.resolve()` instead of inline FS walk; delete `isCompleteModelDir()`, `resolveBaseDir()`, `MODEL_FILE`, `TOKENIZER_FILE`

No proto change. No caching. No injection plumbing. No new failure modes. Divergence is eliminated because the Head calls the exact same method as the Worker. The "stale discovery UX" concern doesn't apply because the Head walks fresh on each request (same as today).

Architectural impurity: the Head walks the Worker's model locations. But it already does today, and the practical cost is zero (fast FS stat checks, same machine).

**Recommendation**: Implement the simpler alternative. It solves the actual problem (duplicate code) without the disproportionate infrastructure cost. The full D-4 design should be reconsidered only if RAG-007 introduces a model installation flow that justifies the Worker-side caching and rescan trigger.

#### Implementation checklist (full D-4 — implemented 2026-02-20)
- [x] `WorkerModelDiscovery.java` — new public wrapper in `modules/reranker`
- [x] Proto: add `OnnxDiscoveredModel` message + `onnx_models` field 7 to `HealthCheckResponse`; run proto stub regen
- [x] `GrpcHealthService` — new constructor overload + populate `onnx_models` in `check()`
- [x] `KnowledgeServerGrpcWiring` — call `discoverAll()`, pass to `GrpcHealthService`
- [x] `OnnxModelStatus.java` + `WorkerFeatureCache.java` — new types in `app-services`
- [x] `RemoteKnowledgeClient` — cache + `getLastKnownOnnxModels()` + update in `getHealthCheck()`
- [x] `AppFacadeBootstrap` — expose `workerFeatureCache()`
- [x] `LocalApiServer.Builder` — add `workerFeatureCache` field + builder method
- [x] `HeadlessApp` — wire `bootstrap.workerFeatureCache()` into builder
- [x] `RuntimeActivationService` — simplify, add nullable cache param, delete FS walk
- [x] Verify: compile all affected modules, `spotlessApply`, full unit test suite
- [x] Unit tests: 5 new tests in `RuntimeActivationServiceTest` covering all `resolveOneOnnxFeature()` branches — cache found, cache not-found, null cache (5-arg ctor), disabled-env-var precedence, explicit-path precedence

---

## Method

1. Read all tempdocs, including those deleted — recovered from git via `git show <commit>~1:path`
2. Cross-reference commit history for overlapping problem statements and solutions
3. Document findings with specific file references

---

## Deleted Tempdocs Recovered

The following tempdocs were deleted and recovered for this analysis:

| Tempdoc | Deleted in | Reason for deletion | Status at deletion |
|---------|-----------|--------------------|--------------------|
| 137 — edge-case-resilience | `956e29bb` | Closed — all items resolved or deprioritized with rationale | closed |
| 138 — performance-real-workloads | `956e29bb` | Stale — self-hosted runner made "Cannot do" items feasible | closed |
| 146 — frontmatter-stripping | `e3a72b65` | Complete — 68 tests, merged to main 2026-02-17 | complete |
| 160 — ci-long-term-improvements | `956e29bb` | Closed — self-hosted runner resolved the runner-minutes constraint | closed |
| 181 — future-features-documentation | `e3a72b65` | (not recovered) | — |
| 189 — multi-agent-worktree-workflow | `e3a72b65` | (not recovered) | — |
| 196 — reranker-quality-tuning | `956e29bb` | Closed as redundant — near-duplicate of 135's reranker section | closed |
| 197 — agent-system-expansion | `956e29bb` | Stale — work absorbed into 186, 187, 208, 211, 213 | closed |
| 201 — error-ux-recovery-flows | `956e29bb` | Complete — investigation done, implementation landed in 217 | complete |
| 205 — model-spread-optimization | `956e29bb` | Done — all phases complete, M0 handoff deferred to 211 | done |
| 208 — ai-model-tuning-optimization | `956e29bb` | Closed — all 20 closure items implemented | closed |
| 212 — unified-error-type-system | `956e29bb` | Canonicalized into `docs/reference/contracts/` | done |
| 214 — neglected-gap-analysis | `956e29bb` | Deleted from that commit then re-created | in-progress |
| b6-empty-response-investigation | `956e29bb` | Short-lived diagnostic artifact — root cause found and fixed | complete |
| b6-research-topics | `956e29bb` | Short-lived research notes — consumed | complete |
| baseline-measurement-2026-02-16 | `956e29bb` | Point-in-time snapshot — no longer needed | complete |

---

## Notes Per Tempdoc

### 118 — agent-efficiency-research

Active behavioral tracking pipeline. Tracks process compliance (context efficiency, build cycles, tool usage) via `evaluate-session.mjs`. Core finding: the composite score measures process hygiene, not outcome quality. Interventions (auto-limiting reads, blocking bash file ops) are independently valuable. No duplication with other tempdocs — unique investigation domain.

### 135 — search-retrieval-quality

Active. Covers golden corpus, BEIR CI gate, hybrid fusion, reranker integration, excerpt infrastructure. Most researched open tempdoc. Post-2026-02-19: reranker sidecar path fixed, BEIR gate merged, golden corpus reranker test added. Open items: RAG-007 (reranker upgrade), SRQ-002 (passage-level), SRQ-003 (stemming), RRF parameter documentation.

**Overlap with 196**: 196 was a near-duplicate of 135's reranker section. Confirmed in 214. Correct deletion.

### 137 — edge-case-resilience (deleted `956e29bb`)

Closed properly. Implemented: OneDrive placeholder detection, `Files.walk()` → `Files.walkFileTree()` with `visitFileFailed` recovery, Office 30MB OOM limit, MMap unmap JVM flag. Deprioritized with rationale: exclude-patterns-during-walk (narrow gap, major dirs already hardcoded), junction deduplication (default junctions ACL-blocked), unindexable file feedback (low user impact per forum research).

**No duplication** with 219 (gRPC-level resilience). 137 addressed file I/O and process-level resilience; 219 addresses RPC-level retry semantics. Complementary, different fault domains.

### 138 — performance-real-workloads (deleted `956e29bb`)

Closed as stale. The "Cannot do" items (GPU benchmarks, model loading times, ONNX native memory, real file type tests at scale) became feasible when the `justsearch-perf` self-hosted runner was provisioned. Benchmark workflows (`claim-a-report-win`, `perf-calibration-win`) cover key latency paths. Remaining gaps (50K+ scale, warm/cold cache) should be addressed in a new tempdoc scoped to the self-hosted runner.

**No duplication** — the gaps documented here fed into 216 (eval harness consolidation) as input, not as redundant work.

### 146 — frontmatter-stripping (deleted `e3a72b65`)

Complete. Stripped YAML frontmatter from `content_preview` snippets at index time. 68 tests. Merged 2026-02-17.

**No overlap with 213**: 146 fixed the snippet shown to humans in UI density modes. 213 fixed the excerpt format delivered to the LLM agent in `SearchTool`. Same underlying `content` field, different consumers, independent code paths. Correct to implement separately.

### 160 — ci-long-term-improvements (deleted `956e29bb`)

Closed. The self-hosted runner eliminated the runner-minutes constraint that drove all major issues (push/PR triggers now feasible, system integration tests feasible via `agent-live-eval-nightly.yml`). Issue 4 (Spotless/PMD `--configuration-cache` exclusions) remains as an upstream Gradle bug. 5/6 issues resolved by runner provisioning.

### 191 — tauri-desktop-shell-gaps

Open. Audit complete (2026-02-17). Tauri upgraded to 2.10.2. Six plugins installed and in use (`single-instance`, `opener`, `dialog`, `window-state`, `autostart`, `notification`). Window-state persistence, autostart toggle, and desktop notifications all added.

**No duplication** — Tauri gap work is unique to the desktop shell module.

### 196 — reranker-quality-tuning (deleted `956e29bb`)

Closed as redundant. Explicitly a near-duplicate of 135's reranker section (same problem statement, no additional research). Key 2026-02-18 finding embedded in 214: the reranker was not disabled by config default — `RerankerConfig.fromEnv()` auto-enables on model discovery. Actual reason reranker was inactive: path mismatch between Tauri sidecar extraction location and `OnnxModelDiscovery` search paths. Fixed in commit `02fafeac`.

### 197 — agent-system-expansion (deleted `956e29bb`)

Closed as stale. Written when `app-agent` had 5 file touches. Subsequent work was tracked in dedicated tempdocs: 208 (Phase 1 reliability, R1 compression, trace identity, checkpoint versioning), 186 (LLM agentic file operations — active), 187 (production MCP server — complete), 213 (agent search context quality — in-progress). The broad "agent platform" audit it called for was effectively absorbed.

### 198 — rag-quality-eval-loop

Phase 6.1 stabilized. Full RAG eval harness implemented: 24-query manifest, `CitationScorer` faithfulness scoring, BEIR hybrid baseline, regression gating. Now feeds into 216 (eval harness) as the third harness family.

### 199 — internationalization-i18n

Paused. Found that a complete backend i18n system exists (`Messages.java`, 161 keys in `ui.properties`) but is entirely dead code — the React frontend has its own `i18n.ts` with German translations that makes no use of the backend system. These are two parallel implementations with no connection. The backend system (`modules/ui/src/main/java/io/justsearch/ui/i18n/`) was never wired to the frontend.

**Genuine code duplication**: 161 backend message keys for search/settings/status/errors that the frontend already covers independently. Not a harmful duplication (the backend layer is never called), but the dead code should be cleaned up or wired up if multilingual backend responses are ever needed.

### 200 — agent-tool-architecture-unification

Provisional. Research complete: 4 unification options evaluated, all rejected. Verdict: keep dual system (built-in Java agent + MCP server). Experiment 1 (richer SearchTool description) deployed, blocked on agent observability to evaluate. BrowseTool root sentinel fix applied.

### 201 — error-ux-recovery-flows (deleted `956e29bb`)

Complete investigation. Found the error UX much more developed than assumed: React `ErrorBoundary`, 40+ localized error codes, `isRetryableError()`, circuit breakers, retry with backoff. Wave 1-2 fixes applied (`ApiErrorHandler` refactor to inspect `IndexRuntimeIOException.Reason`, gRPC status code mapping, `requestId` in responses). The structural problem (string literals, parallel resolvers) remained and was handed to 212/217.

**Clean progression** to 212 (design) → 217 (implementation). Not redundant.

### 202 — installer-distribution-maturity

(Not read in detail — tempdoc still exists and is open/active.)

### 204 — configuration-ux

(Not read in detail — tempdoc still exists.)

### 205 — model-spread-optimization (deleted `956e29bb`)

Done. Research established: the initial assumption that the VL-Instruct model "cannot think" was wrong — Qwen3-VL-8B-Thinking exists with both vision and chain-of-thought. Decision: use unified VL-Thinking model rather than splitting. Critical reassessment (2026-02-17) noted Qwen3.5 release but concluded it's not a practical GGUF runtime swap yet. NER model agnosticism extracted to 207 (unrelated to chat/VDU/embedding workloads).

**Overlap with 208**: 205 made the model selection decision; 208 tuned performance after the regression the new model caused. Sequential, not redundant.

### 207 — ner-model-agnosticism

Idea. Extracted from 205. `BioTagDecoder` hardcodes 9 label constants and 4 switch statements for `dslim/bert-base-NER`. ~60-80 lines to make it model-agnostic via config record. Low priority — no NER model swap is planned.

### 208 — ai-model-tuning-optimization (deleted `956e29bb`)

Closed. All 20 closure items implemented: Phase 1 reliability (loop detection, budget-edge finalize, R3 path enforcement), R1 compression v2 (limit 10→5, truncation 1500→900), trace identity parity (SSE/MCP/persistence), checkpoint schema versioning with upcaster chain, scorecard v2 process metrics, OBS-005 OTel agent spans, non-RKC gRPC resilience baseline (shared `GrpcRetryServiceConfig`, `GrpcCircuitBreaker`), health/readiness contract refinement. M0 handoff deferred to 211.

**Overlap with 213**: 208 reduced SearchTool results limit (10→5) and added tool result truncation (1500→900 chars) as part of token-budget optimization. 213 independently investigated agent search context quality and increased results limit back to k=3 with 800-char excerpts and a 4K total budget. These decisions moved in opposite directions. **The k=3 setting in 213 supersedes the limit-5 from 208 compression work**. The divergence is explained by different goals: 208 was cutting cost of LLM tokens, 213 prioritized answer quality over cost.

### 209 — jdk26-early-switch-evaluation

Open (pending checkpoint). Decision: do not switch yet. JDK 26 pre-GA on 2026-02-17. Start canary lane, re-evaluate at checkpoint 2026-03-24 (one week after JDK 26 GA target date).

### 210 — agent-infra-external-fit-research

Active. Reframed as 12-month whole-product strategy. Two workstreams split out as dedicated tempdocs: 216 (eval harness) and 219 (runtime resilience). Strategy backlog (S-001 through S-011+) is canonical for this cycle.

### 211 — multi-agent-handoff-m0

Resolved. Sequential handoff M0 implemented: `agentProfiles`/`initialAgentId` in `AgentRequest`, `HandoffProposed`/`HandoffExecuted` events, `activeAgentId`/`handoffHistory` in `AgentRunStore`, approval boundary reset on handoff. Gate criteria met.

### 212 — unified-error-type-system (deleted `956e29bb`, canonicalized)

Done. `ApiErrorCode` enum replacing ~30 hardcoded string literals. Every code carries `errorClass` and `retryAction`. Frontend receives structured retry semantics instead of inferring from string matching. Canonicalized into reference contracts.

**Clean progression from 201**: 201 investigated the problem → 212 designed the solution → 217 implemented it. Not redundant.

### 213 — agent-search-context-quality

In-progress. Approach A shipped: `k=3`, 800-char excerpts, 4K budget in `SearchTool`. Battery regression check invalid (needs ingestion fix). Approach B not started.

### 214 — neglected-gap-analysis

In-progress. Cross-references stalled tempdocs against their own rationale. Key finding: reranker not disabled by config — path mismatch was the real blocker. Fixed in `02fafeac`.

### b6-empty-response-investigation (deleted `956e29bb`)

Complete. Root cause: thinking model generates 8000-9300+ reasoning tokens, exhausting `max_tokens=2048` before producing output. `--reasoning-budget 0` and `max_tokens 8192` fixes applied in 208. Short-lived diagnostic artifact — correct to delete.

### baseline-measurement-2026-02-16 (deleted `956e29bb`)

Point-in-time performance baseline snapshot. Not a tempdoc — consumed and superseded by the ongoing benchmark infrastructure. Correct to delete.

---

## Candidate Duplication Areas (confirmed)

### D-1: 196 vs 135 — Reranker scope overlap [CONFIRMED / RESOLVED]

**Nature**: Duplicate problem statement. 196 had no additional research beyond what 135 already covered.
**Resolution**: 196 deleted as redundant. Documented in 214. ✓

### D-2: gRPC per-client retry/circuit-breaker divergence [CONFIRMED CODE-LEVEL / RESOLVED]

**Nature**: Before resilience work (208/219), each gRPC client (`GrpcAnnSearchClient`, `GrpcEmbeddingClient`, `GrpcAiTranslatorService`) had independent retry and backoff patterns. Same problem solved three times.
**Resolution**: Unified into `GrpcRetryServiceConfig` and `GrpcCircuitBreaker` in `modules/ipc-common`. All three clients now share a single config object. ✓

### D-3: Error message sniffing across controllers [CONFIRMED CODE-LEVEL / RESOLVED]

**Nature**: Multiple controllers classified exceptions by checking message substrings ("unavailable", "not found"). This logic was independently duplicated in `ApiErrorHandler.resolveErrorCode()` and `SummaryErrorUtils.resolveErrorCode()` — same exception could produce different codes on different endpoints.
**Resolution**: Replaced by `ApiErrorCode` enum (tempdoc 217, commit `8b1679ab`) and telemetry wiring cleanup (`e3a72b65`). ✓

### D-4: OnnxModelDiscovery sidecar path — parallel fix [IMPLEMENTED]

**Nature**: When the sidecar path bug was fixed, the same path check was independently added to both `OnnxModelDiscovery.resolve()` and `RuntimeActivationService.resolveOneOnnxFeature()`. Near-copy fix rather than shared abstraction.
**Resolution**: Full D-4 implemented (2026-02-20). Worker is now the single source of truth for ONNX model discovery. `RuntimeActivationService` reads from `WorkerFeatureCache` (populated via gRPC health check) instead of independently walking the filesystem. 3 new files, 8 modified files, 5 unit tests. See D-4 section above for full details.

### D-5: Pipeline schema types spread [CONFIRMED CODE-LEVEL / RESOLVED]

**Nature**: `StageRetryPolicy`, `StageType`, `SearchStageType`, `StageRole`, `SearchStageRole`, `pipelineBuiltinStageTypes` were defined in `pipeline-engine`/`pipeline-executor` and referenced across multiple modules. The engine was never wired into live paths, so these types were engine-only infrastructure carrying no live behavior.
**Resolution**: All deleted in tempdoc 221 (commits `ea3137e3`, `e3d36557`, `c225b37d`). ✓

### D-6: Backend i18n vs frontend i18n — parallel implementations [RESOLVED]

**Nature**: `modules/ui/src/main/java/io/justsearch/ui/i18n/Messages.java` with 161 message keys in `ui.properties` was dead code — the React frontend has its own `i18n.ts` with German translations.
**Resolution**: Backend i18n files were already deleted before this investigation. See code verification section below for correction.

### D-7: SearchTool result limit divergence — 208 vs 213 reversed each other [RESOLVED]

**Nature**: 208 reduced SearchTool default limit from 10→5 and truncation from 1500→900 chars (cost-optimization goal). 213 then set k=3 results with 800-char excerpts and 4K total budget (quality goal). The two tempdocs moved in opposite directions without explicit cross-reference.
**Current state**: 213 changes are the effective state as of 2026-02-18. The compression from 208 was partially superseded.
**Resolution**: Per-result char budget implemented in `SearchTool.formatResults()` (D-7 checklist above). Comment blocks added to `SearchTool.java` and `AgentLoopService.java` documenting the three-layer truncation and cross-referencing tempdocs 208/213. The implicit reversal is now documented in code.

---

## Code Verification Results (2026-02-19)

All duplications were verified against the live codebase. Several claims needed correction.

### D-1: 196 vs 135 — Reranker scope overlap [CONFIRMED / RESOLVED]

No code to verify — tempdoc-level overlap, 196 deleted. ✓

### D-2: gRPC circuit breaker — TWO CLASSES EXIST, INTENTIONAL [CORRECTED]

**What I said**: "Unified into `GrpcRetryServiceConfig` and `GrpcCircuitBreaker` in `modules/ipc-common`."
**What is actually true**: Two `GrpcCircuitBreaker` classes still exist:
- `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker` — canonical implementation
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/GrpcCircuitBreaker` — **compatibility adapter** that wraps the canonical one

The adapter's Javadoc explicitly states: *"Compatibility adapter over ipc-common's canonical gRPC circuit breaker. App-services keeps this wrapper to preserve existing public API and telemetry/log semantics while delegating state transitions and probe/rejection behavior to the shared primitive."*

`app-search` and `app-ai` use `ipc-common.GrpcCircuitBreaker` directly. `app-services` uses its own wrapper (which internally delegates). **This is correct design** — the wrapper adds `IpcTelemetry` hooks and logging that the worker client (`RemoteKnowledgeClient`) needs. Not a real duplication. ✓

### D-3: Error message sniffing — ONE DEFENSIVE LINE REMAINS [CONFIRMED, SUBSTANTIALLY RESOLVED]

Only one `message.contains()` check survives in the entire codebase:

```java
// SummaryErrorUtils.java:31
if (message.contains("status 503") || message.contains("Service Unavailable")) {
```

The comment reads: *"Defensive: non-LlmServerException with HTTP status hints (should not occur after OnlineModeOps migration, but preserves UX if it does)."* This is an acknowledged defensive fallback, not active business logic. D-3 is resolved in practice; this line is a safety net for an edge case that the author believes no longer triggers.

### D-4: OnnxModelDiscovery sidecar path — RESOLVED [IMPLEMENTED 2026-02-20]

Full D-4 implemented. `RuntimeActivationService` no longer walks the filesystem for ONNX models. Instead, the Worker runs `OnnxModelDiscovery` at startup, reports results via `HealthCheckResponse.onnx_models` (proto field 7), and the Head reads from a `WorkerFeatureCache` populated by `RemoteKnowledgeClient.getHealthCheck()`. The duplicate helpers (`isCompleteModelDir`, `resolveBaseDir`, `MODEL_FILE`, `TOKENIZER_FILE`) are deleted from `RuntimeActivationService`. 5 unit tests added covering all branches of the new code path.

### D-5: Pipeline schema types spread [CONFIRMED / RESOLVED]

Types deleted in 221. Verified by grep — no `StageRetryPolicy`, `SearchStageType`, `SearchStageRole`, or `StageRole` in codebase. ✓

### D-6: Backend i18n dead code — RESOLVED (FILES DELETED) [CORRECTION]

**What I said**: "161 backend message keys that the frontend never reads. Open."
**What is actually true**: `modules/ui/src/main/java/io/justsearch/ui/i18n/` and `modules/ui/src/main/resources/i18n/` do **not exist** in the current codebase. The files were deleted at some point before this investigation. D-6 was already resolved — there is no dead i18n code. My original analysis was incorrect.

### D-7: SearchTool result limits — CONFIRMED [OPEN, IMPLICIT]

Verified in `modules/app-agent/src/main/java/io/justsearch/agent/tools/SearchTool.java`:
```java
private static final int DEFAULT_LIMIT =
    Math.max(1, Math.min(20, EnvRegistry.AGENT_SEARCH_DEFAULT_LIMIT.getInt(3)));
```
And per-excerpt cap: `if (excerpt.length() > 800)` (line 193).

The 213 state (k=3, 800-char excerpts) is confirmed live. This supersedes the 208 compression work (10→5, 1500→900). Tempdoc 213 does not call this out as a reversal of 208's limits. The implicit reversal is real but harmless — just undocumented.

---

## Confirmed Duplications — Summary (Code-Verified)

| ID | Type | Domain | Code Verification | Status |
|----|------|--------|------------------|--------|
| D-1 | Tempdoc overlap | Reranker quality | No code — tempdoc-level | Resolved |
| D-2 | Intentional adapter | gRPC circuit-breaker | Two classes: canonical + wrapper | Resolved (by design) |
| D-3 | Code duplication | Error message sniffing | 1 defensive line remains | Resolved in practice |
| D-4 | Code duplication | OnnxModel path resolution | Worker authoritative via gRPC, FS walk deleted from Head | Resolved |
| D-5 | Code duplication | Pipeline schema types | All deleted, verified by grep | Resolved |
| D-6 | Dead code | i18n backend | Files don't exist | Resolved (already deleted) |
| D-7 | Policy reversal | SearchTool result limits | Per-result char budget + documentation | Resolved |

---

## Non-Duplications (correct architectural separations)

| Apparent overlap | Why it's not a duplicate |
|-----------------|--------------------------|
| 137 (worker file resilience) vs 219 (gRPC resilience) | Different fault domains: file I/O vs RPC semantics |
| 146 (content_preview frontmatter) vs 213 (agent search excerpts) | Different consumers of same field: frontend snippet display vs LLM agent context |
| 201 (investigation) → 212 (design) → 217 (implementation) | Sequential progression, not duplication |
| 205 (model selection) → 208 (model tuning) | Phase 1 decision then Phase 2 execution |
| 197 absorbed by 186/187/208/211/213 | Intentional decomposition into dedicated packets |

---

## The "Finding 7" Reference

Tempdoc 216 states it was "Promoted from tempdoc 215 Finding 7." This is an error in 216's origin attribution — tempdoc 215 was never filled in (all sections were `[pending read]` in every committed version). The eval harness consolidation work originated from tempdoc 210's strategy backlog (item BEN-004 / S-001), not from 215.

---

## Long-Term Fix Analysis

### D-4: OnnxModelDiscovery path resolution duplication

#### Originally proposed fix

- **Phase 1**: Extract `resolveBaseDir()` and `isCompleteModelDir()` to `PlatformPaths` as `resolveInstallBaseDir()`. Both callers already import `PlatformPaths`; zero module boundary changes.
- **Phase 2**: Add a new `GetOnnxModelStatus` gRPC RPC so the Worker can authoritatively report which ONNX models it found, letting `RuntimeActivationService` stop doing local discovery.

#### Critical evaluation

**Phase 1 is wrong for three reasons:**

1. `PlatformPaths` already has `resolveAiHome()`, which looks similar but has different semantics — it falls through to `resolveDataDir()`, whereas the duplicated `resolveBaseDir()` falls directly to CWD. A new `resolveInstallBaseDir()` encoding this subtle difference adds a footgun to an already overloaded utility class.
2. It expands `PlatformPaths` API to serve code that should not exist. The API surface for `resolveInstallBaseDir()` outlives the code that prompted it — it would still be there after `RuntimeActivationService.resolveOneOnnxFeature()` is eventually deleted.
3. It fixes the symptom, not the cause. The root cause is that the Head reimplements Worker file-system behavior rather than observing it. Extracting a shared helper preserves that anti-pattern while adding API surface.

**Phase 2 is also wrong:**

1. Timing problem. `/api/ai/runtime/status` must return a useful response before the Worker starts. A new `GetOnnxModelStatus` gRPC call fails or blocks when the Worker is down, creating a dependency the current approach avoids.
2. The right extension point already exists. `HealthCheckResponse` (in `indexing.proto`) already carries `ai_ready` and `embedding_ready` — exactly the same category of Worker subsystem state. ONNX feature status belongs there, not in a new RPC.

#### Is this legacy code?

No. Actively rendered. `BrainRuntimeSection.tsx` has a "Search Quality Features" collapsible panel that maps directly to `onnxFeatures`: green/amber dot per feature, active/inactive label, resolved model path when active, and a help panel (showing install location and env var) when `reason === 'not_found'`. The `label` field ("Search reranking", "Citation scoring") and `reason` field drive the UI copy. This is live user-facing functionality, not dead code.

#### Correct fix — implementation requirements (fully investigated)

The architectural direction remains: extend `HealthCheckResponse` so the Worker reports discovery results, eliminating the Head's local file-system walk. Full picture after code investigation:

**Proto change:**
```proto
// New message — only carries auto-discovery results from the Worker
message OnnxDiscoveryResult {
  string id = 1;      // "reranker", "citation_scorer"
  bool found = 2;
  string path = 3;    // discovered path (if found)
}

message HealthCheckResponse {
  // existing: serving, version, pid, worker_state, ai_ready, embedding_ready
  repeated OnnxDiscoveryResult onnx_discovery = 7;
}
```

Note: the proto field carries only discovery results, not `disabled`/`explicit_path` — those remain Head-side (explained below).

**Worker-side (4 things):**

1. `OnnxModelDiscovery` is **package-private** (`final class OnnxModelDiscovery`). `modules/indexer-worker` already depends on `modules/reranker` (line 27 of `build.gradle.kts`), so no new Gradle dependency is needed — but `OnnxModelDiscovery` must be made `public`, or a public wrapper added to `modules/reranker`.
2. Call `OnnxModelDiscovery.resolve()` at Worker startup for each model (`"reranker"`, `"citation-scorer"`). Cache the results in a field.
3. `GrpcHealthService` needs a new constructor parameter — either a pre-computed `List<OnnxDiscoveryResult>` or a `Supplier` — to receive the cached discovery results.
4. Populate `onnx_discovery` in the `HealthCheckResponse.newBuilder()` call (line 153 of `GrpcHealthService.java`).

**Head-side (3 things):**

1. **`AppFacade` has no health method.** `AppFacade` exposes only `search()`, `indexingAi()`, `indexing()`, `documents()`, `onlineAi()`, `agent()`. A new `workerHealth()` method would need to be added, OR `RemoteKnowledgeClient` injected directly into `RuntimeActivationService`.
2. **`RemoteKnowledgeClient.getHealthCheck()` is a live RPC, not cached.** Every call fires a gRPC request to the Worker. Reading it per status request would be a performance regression. A health cache needs to be introduced — either in `RemoteKnowledgeClient` itself or in `AppFacadeBootstrap` — before this data path is usable from `RuntimeActivationService`.
3. **`resolveOneOnnxFeature()` is simplified, not deleted.** The `disabled` and `explicit_path` reasons reflect Head-process env vars (`JUSTSEARCH_RERANK_ENABLED`, `JUSTSEARCH_RERANK_MODEL_PATH`). The Worker has no visibility into these. The simplified Head-side logic becomes:
   - Explicitly disabled? → return `inactive/disabled`
   - Explicit model path set? → return `active/explicit_path`
   - Otherwise → read from Worker health response → return `active/auto_discovered` or `inactive/not_found`
   - Worker not up? → return `inactive/not_found` (same as today when discovery fails)

   `isCompleteModelDir()` and `resolveBaseDir()` in `RuntimeActivationService` are deleted. The file-system walk moves to the Worker.

**Remaining uncertainties:** None. The design is fully characterized. The implementation choices are:
- Public `OnnxModelDiscovery` vs. new public wrapper in `modules/reranker`
- `AppFacade.workerHealth()` vs. direct injection of a `WorkerFeatureCache` into `RuntimeActivationService`
- Health cache location: in `RemoteKnowledgeClient` vs. in `AppFacadeBootstrap`

These are design decisions, not unknowns.

#### Long-term improvement evaluation

The fix solves the duplication but has three concrete weaknesses:

**1. Stale-discovery UX regression (significant)**

Currently `resolveOnnxFeatures()` scans the file system on every `/api/ai/runtime/status` request. If a user installs a model while the Worker is running, the status page immediately shows "active."

After the fix: the Worker scans at startup and caches. The status page shows "inactive" until the Worker restarts — directly contradicting the install instructions ("place files here"). This is a real regression.

Resolution options:
- Accept + document: add UI copy ("status reflects last startup scan — restart Worker to refresh")
- Make the Worker re-run discovery on each health check (defeats caching, adds FS I/O to health polling path)
- Add a rescan trigger: if RAG-007 introduces a Head-managed model installation flow, the Head can signal the Worker to re-run discovery at that point — recovering dynamic accuracy

**2. `HealthCheckResponse` scope creep (moderate)**

The message currently mixes runtime liveness (`serving`, `pid`, `worker_state`) and service availability (`ai_ready`, `embedding_ready`). Adding startup-time configuration (`onnx_discovery`) introduces a third semantic category with a different change rate. This sets a precedent: every future Worker subsystem with a discoverable resource gets added here. The message becomes a general-purpose status bag.

The timing problem (Worker down → no response) was the original reason a dedicated RPC was rejected. But the health cache being introduced for the fix also solves the timing problem for a dedicated RPC — a `GetFeatureStatus` RPC with last-known-good fallback is architecturally cleaner. This alternative should be reconsidered at implementation time.

**3. API surface expansion (minor)**

Making `OnnxModelDiscovery` public gives external consumers access to an implementation detail of `modules/reranker`. A narrow public wrapper (e.g., `RerankerStatus.discoverModels()`) exports only the data the Worker needs to report without opening the full discovery class. Prefer this over making the class itself public.

Similarly, adding `workerHealth()` to `AppFacade` (marked "stable") leaks gRPC infrastructure concerns into the application facade. Prefer direct injection of a `WorkerFeatureCache` abstraction into `RuntimeActivationService`.

**Bottom line**

The fix is correct and worth doing with RAG-007, conditional on resolving the stale-discovery concern — either with UI copy or a rescan trigger from the RAG-007 installation flow. The `HealthCheckResponse` scope concern warrants reconsidering a dedicated `GetFeatureStatus` RPC at implementation time. Do not make `OnnxModelDiscovery` public directly; use a narrow wrapper.

Do this with RAG-007. Until then, leave D-4 as-is.

---

### D-7: SearchTool result limit policy

#### Originally proposed fix

- **Phase 1**: Add comment blocks above `DEFAULT_LIMIT` and `MAX_TOOL_RESULT_CHARS` documenting the policy.
- **Phase 2**: Run the agent battery at k=2, 3, 5 to establish a valid baseline.
- **Phase 3**: Thread a context budget from `AgentLoopService` to `SearchTool` — pass `remainingContextChars / k` so each result gets a proportional budget.

#### Critical evaluation

**Phase 3 is wrong.** To understand why, the truncation chain has three layers, not two:

```
Layer 1 — SearchTool formats output
    k=3 results × up to 3 excerpt regions × 800 chars/region ≈ 8,100 chars worst case

Layer 2 — AgentLoopService.truncateForContext()
    Hard substring cut at MAX_TOOL_RESULT_CHARS = 4,000 chars
    Result 3 is typically cut here; later excerpts of result 2 often cut too

Layer 3 — AgentLoopService.compressToolMessagesForContext()
    After each iteration: strips Excerpt: lines from older search results
    Earlier searches lose excerpts; the most recent search keeps them
```

Layer 3 already handles the session-level context problem correctly. The actual problem is Layer 2 cutting the *current* result blindly from the end, discarding content without the agent knowing which result was truncated.

Threading `remainingContextChars / k` from the loop fails because:
1. Equal distribution is wrong — results are ranked by relevance, result 1 deserves more chars than result 3.
2. It changes the `ToolDefinition.execute()` contract for all tools (BrowseTool, IngestTool, etc.) to solve a problem specific to SearchTool's interaction with Layer 2.
3. It addresses the wrong layer — the fix belongs in Layer 1, preventing overflow before it reaches Layer 2.

**Phase 2 methodology is insufficient.** A single battery pass per k value cannot detect signal — the 208 A/B test at N=14 runs was already marked INVALID because LLM behavioral variance exceeded the effect size. Valid measurement: pick 4-5 multi-document synthesis scenarios, run each 8-10 times at k=1, 2, 3, compute pass rates with confidence intervals.

#### Correct fix

The approach is right but two implementation details were wrong after reading `formatResults()` in full.

**Wrong detail 1 — "~3 lines"**: The 800-char cap is applied per excerpt *region* inside a nested loop, not per result. There is currently no per-result total. Adding one requires tracking accumulated chars across regions for each hit and stopping region inclusion when the budget is exhausted. That's closer to 10-15 lines:

```java
int maxCharsPerResult = maxToolResultChars / hits.size();
// ...inside per-hit loop:
int hitCharsUsed = 0;
for (ExcerptRegion region : hit.excerptRegions()) {
    String excerpt = region.text().strip()...;
    int allowed = maxCharsPerResult - hitCharsUsed;
    if (allowed <= 0) break;
    if (excerpt.length() > allowed) excerpt = excerpt.substring(0, allowed) + "...";
    sb.append(...);
    hitCharsUsed += excerpt.length();
}
```

**Wrong detail 2 — `MAX_TOOL_RESULT_CHARS` not accessible**: That constant is private to `AgentLoopService`. `SearchTool` already imports `EnvRegistry`, so it can read `EnvRegistry.AGENT_MAX_TOOL_RESULT_CHARS.getInt(4000)` directly — but this duplicates the env lookup. The cleaner option is to expose the constant at package level or pass it via constructor.

The approach itself remains correct: per-result budget scaling with k, no interface changes, `truncateForContext()` becomes a safety net.

Do this with tempdoc 213 Approach B (ingestion fix), then run the targeted repeated battery at k=1, 2, 3.

---

## Conclusions

1. **Most deletions were correct**. Of the 16 deleted tempdocs reviewed, all were either complete, stale, or redundant. No open work was silently dropped.

2. **The big code-level duplications (D-3, D-5) are resolved**. D-2 was intentional design (adapter), not duplication.

3. **D-4: fix is correct and fully scoped; three long-term concerns identified.** The ONNX status UI is active (not legacy). Fix scope: (a) public wrapper in `modules/reranker` (not `OnnxModelDiscovery` itself), (b) Worker startup discovery cached and passed to `GrpcHealthService`, (c) proto field for discovery results only — or reconsidered as a dedicated `GetFeatureStatus` RPC with cached fallback, (d) `WorkerFeatureCache` injected into `RuntimeActivationService` rather than polluting `AppFacade`, (e) `resolveOneOnnxFeature()` simplified to disabled/explicit-path only. Long-term concerns: (1) stale-discovery UX regression — user installs model at runtime, status stays "inactive" until Worker restart; resolve with UI copy or RAG-007 rescan trigger. (2) `HealthCheckResponse` scope creep — mixing liveness with startup config. (3) `AppFacade` stability — prefer injection over new facade method. Do with RAG-007.

4. **D-7: the per-result budget approach is right; two implementation details were wrong.** The 800-char cap in `formatResults()` is per excerpt region (nested loop), so a per-result total cap requires a char accumulator — ~10-15 lines, not ~3. `MAX_TOOL_RESULT_CHARS` is private to `AgentLoopService` and needs either direct `EnvRegistry` access in `SearchTool` or a package-level exposure. The three-layer structure finding stands; threading context budget from the loop is still the wrong approach.

5. **D-6 was a false alarm** — the backend i18n files were already deleted before this investigation.
