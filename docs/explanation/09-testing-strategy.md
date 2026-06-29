---
title: Testing Strategy
type: explanation
status: stable
description: "The 4-tier Test Pyramid, Chaos testing, and AI Judge."
---

# 09. Testing Strategy: The "Test Pyramid"

JustSearch employs a rigorous 4-tier testing strategy (`modules/system-tests`), crucial for a multi-process application where "Unit Tests" alone cannot catch deadlock or IPC bugs.

## Tier 1: Unit Tests (Fast)
*   **Scope:** Single class/method.
*   **Mocking:** Heavy usage of Mockito.
*   **Example:** `ResolvedConfigBuilderTest`, `RerankerConfigTest`.
*   **Rule:** Must complete in <10ms. No IO.
*   **Frontend unit tests (Vitest):**
    *   `modules/ui-web/src/api/sse.test.ts` (SSE framing correctness)
    *   `modules/ui-web/src/api/http.test.ts` (session token header + request helper behavior)

## Tier 2: Integration Tests (Medium)
*   **Scope:** Module level (wiring).
*   **Resource:** Uses temporary folders (`@TempDir`).
*   **Example:** `BatchUpdateIntegrationTest` (Does it actually write to disk?).
*   **Rule:** IO allowed. No networking/sockets if possible.
*   **Examples (current):**
    *   `KnowledgeServerIntegrationTest` (app-services ↔ worker wiring via real gRPC)
    *   `InferenceLifecycleManagerExternalServerTest` (adopting an already-running `llama-server` on the configured port)
    *   `LlamaServerPropsParsingTest` (robust `/props` parsing for `n_ctx` / `model_alias`)
    *   `LocalApiCorsPolicyTest` (regression for loopback-only CORS allowlist / local bind safety)
    *   `LuceneIndexRuntimeTest` (regressions for user-safe query parsing, facets, and cursor-based `searchAfter` paging)
    *   `GrpcSearchServiceSearchPayloadTest` (ensures interactive search hits do not ship `content`)
    *   `VduEligibilityPdfFixturesTest` (real PDF fixtures → correct `vdu_status` marking without llama-server)
    *   `TimeboxedContentExtractorTest` (deterministic extraction timeout guardrail)
    *   `ContextBudgeterTest` (strict character budgeting logic for RAG context assembly)
    *   `GrpcSearchServiceRetrieveContextTest` (retrieveContext respects `maxChars` via strict budgeting)
    *   `GrpcSearchServiceReasonCodeContractTest` (allowlisted reason codes for vector/hybrid fallback and RAG retrieval mode/reason)
    *   `RemoteDocumentServiceContextBudgetTest` (fallback full-doc context retrieval is budgeted)
    *   `SummaryControllerRagSectionTruncationTest` (head-side truncation filters citations/sections consistently)
    *   `ChunkDocumentWriterTest` (chunk regeneration consistency: offsets + metadata)
    *   `GrpcIngestServiceChunkRegenerationTest` (VDU update path regenerates chunks without drift)

## Tier 2.5: Guardrails & Contract Tests (Fast/Medium, drift prevention)

These tests exist to prevent architectural drift and contract breakage in a multi-module, multi-process system.

* **Architecture guardrails (ArchUnit)**:
  - Examples:
    - `modules/app-api/src/test/java/io/justsearch/app/api/ArchitectureRulesTest.java`
    - `modules/ui/src/test/java/io/justsearch/ui/api/UiApiGuardrailsTest.java`
    - `modules/app-launcher/src/test/java/io/justsearch/app/launcher/LayeringEnforcementTest.java`
    - `modules/app-services/src/test/java/io/justsearch/app/services/worker/AppServicesWorkerGuardrailsTest.java`
    - `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/IndexerWorkerGuardrailsTest.java`
    - `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/AdaptersLuceneGuardrailsTest.java`
  - What they enforce (examples): no runtime `test-support` dependencies, restricted `System.*` usage, and DTO-direction rules (UI REST controllers should not import proto DTOs by default).

* **Lifecycle contract tests**:
  - `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java` asserts the stable `/api/health` + `/api/status` lifecycle subset (schema v1) and the reason-code allowlist.

* **Security/token policy contract tests**:
  - `modules/ui/src/test/java/io/justsearch/ui/api/LocalApiUiTokenPolicyTest.java` asserts prod-mode session-token enforcement for non-GET requests (`X-JustSearch-Session`) and CORS allow-headers.

* **Schema mismatch status contract**:
  - `modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java` seeds a mismatched `index_schema_fp` and asserts `/api/status` surfaces `reindexRequired=true` with a stable reason.

## Tier 3: System Tests (Slow)
This is the most critical tier for verifying the "3-Process Architecture".
*   **Mechanism:** `WorkerSpawnTest` and `GrpcCommunicationTest` actually **spawn** the child Java process.
*   **Verification:** It communicates via real gRPC and MMF.
*   **Use Case:** Verifying the "Suicide Pact" (simulated parent death via stale heartbeat, worker self-termination).
*   **Chaos:** `ChaosSuiteTest` and torture/system flows intentionally stress corruption, locking, restart, and recovery logic.
*   **Performance benchmarks** — all benchmark + eval capabilities now in `scripts/jseval/` (slice 3a-1-8f §B.14, 2026-05-12 — the prior `scripts/bench/` infrastructure was deleted by commit `a9c484f59` 2026-03-16; jseval covers Claim A/B/C/D + Track G + agent + RAG via `engine-bench`, `ingest-bench`, `bench-concurrency`, `knn-bench`, `agent-eval`, `rag-eval`, `retrieval-eval`, etc.):
    *   **Claim A (engine-only):** `python -m jseval engine-bench` — isolated Lucene throughput
    *   **Claim B (pipeline):** `python -m jseval ingest-bench` — full Worker pipeline throughput
    *   **Claim C (UX):** `python -m jseval ui-perf` — UI responsiveness during indexing

System tests also cover schema migration + blue/green correctness (current):

* `MigrationControlE2ETest` (start/cutover/rollback control surface)
* `SwitchingFenceBufferingE2ETest` (durable buffering during `SWITCHING`)
* `RollbackE2ETest` (rollback safety after cutover)
* `PauseResumeMigrationE2ETest` (pause orchestration semantics)
* `IndexBasePathLockE2ETest` (index-root lock prevents concurrent Workers)

Additional high-signal system/contract tests (current):

* `MmfSignalBusCompatibilityTest` (prevents MMF layout drift between Head/Worker by exercising cross-read/write compatibility)
* `HttpPagingCursorE2ETest` (end-to-end cursor round-trip semantics over HTTP)
* `VduBatchProcessorE2ETest` (Tier-2 VDU OCR lane; requires llama-server for scanned-PDF OCR/searchability assertions)

## UI Contract Tests (Playwright E2E)

The frontend (`modules/ui-web`) includes Playwright tests that validate the **HTTP contract** and key UX behaviors:

* `e2e/search-filters-ui.spec.ts`: filters + projection + facets contract
* `e2e/search-pagination-ui.spec.ts`: cursor pagination (`nextCursor`/`cursor`) + sort parameter behavior
* `e2e/preview-highlighting.spec.ts`: preview term highlighting + Markdown/Raw toggle
* `e2e/auto-discovery.spec.ts`: backend auto-discovery (port scan + `/api/status` payload validation)
* `e2e/ai-meta-contract.spec.ts`: AI streaming meta contract fields are present and stable
* `e2e/chunk-vector-status.spec.ts`: chunk vector readiness card contract (`chunkVectorsReady` + coverage)
* `e2e/vdu-provenance.spec.ts`: preview surfaces `vduStatus` + `textProvenance` and UI renders them
* `e2e/search-cursor.spec.ts`: cursor vs selection model (Arrow keys browse without opening Inspector; `Enter` commits selection)
* `e2e/inspector-answer-first.spec.ts`: Inspector renders AI answer above-the-fold after summarize completes (Preview is not above Answer)
* `e2e/launchpad-actions.spec.ts`: Launchpad setup ladder/query chips and Action Panel entrypoint in demo mode
* `e2e/brain-flows.spec.ts`: AI install/repair lifecycle flows (stubbed)
* `e2e/brain-compat.spec.ts`: embedding compatibility card and reindex flow
* `e2e/citations.spec.ts`: citation click-to-verify + trust-loop nudge
* `e2e/api-integration.spec.ts`: connection handling, demo-mode bypass, network error recovery
* `e2e/feature-integration.spec.ts`: ranking, AI summarization, index auto-refresh (live backend only)

### Selector guard

All E2E specs use `E2E_TEST_IDS` from `e2e/selectors.ts` instead of hardcoded test-id strings. Enforced by `scripts/ci/check-playwright-hardcoded-testids.mjs --mode gate`.

### Skip classification

Skips fall into two accepted categories:

* **Integration-only**: gated by `describeRealBackend`, require `VITE_JUSTSEARCH_API_PORT` and a live backend with indexed data.
* **Runtime-conditional**: defensive `test.skip(...)` for mode/state-dependent UI elements; each has parallel stub-backed coverage in dedicated specs.

## Test Evidence Lanes

Public CI uses named evidence lanes rather than one anonymous test bucket. The hosted `Unit tests`
shards prove ordinary JVM regression evidence on the public hosted runner. The separate
`Build (no model blobs)` lane owns the UI web-bundle build, so the unit-test shards run Gradle with
`-PskipWebBuild=true`. The unit lanes do not claim to run every parser fixture, worker-process
integration test, system test, stress test, model-dependent AI test, or web asset build.

Evidence tiers:

| Tier | Default evidence | Ownership rule |
|---|---|---|
| Hosted-required unit evidence | `Unit tests (app-ui)`, `Unit tests (search-worker)`, and `Unit tests (platform-contracts)` with `-PskipWebBuild=true` | Must stay deterministic on standard hosted runners and produce attribution from JUnit XML. |
| Local parser/fixture evidence | PDF/OCR/Office fixture tests disabled under `CI=true` | Must be declared in `scripts/ci/test-evidence-policy.v1.json` with replacement evidence and cadence. |
| Local worker-process integration evidence | `src/integrationTest` cases that spawn worker/server processes | Must be declared when skipped under `CI=true`; run locally before changing the owned integration surface. |
| Opt-in system/AI evidence | `modules/system-tests` tags and opt-in Gradle flags | Owned by the system-tests source sets and documented tags. |
| Stress evidence | `@Tag("stress")` tests | Owned by `scripts/ci/stress-suite-policy.v1.json` and verified by `verify-stress-suite-policy.mjs`. |
| Experiment/evidence tags | `@Tag("experiment")` / `@Tag("evidence")` | Must be declared in `test-evidence-policy.v1.json`; they are development evidence, not branch-protection candidates. |

The hosted public checks that are branch-protection candidates are declared in
`scripts/ci/workflow-signal-policy.v1.json`. Keep the three unit-test shard names stable unless
the branch-protection required checks are updated in the same change.

The guards for this ownership model are:

```bash
node scripts/ci/verify-unit-test-shard-policy.mjs
node scripts/ci/verify-test-evidence-policy.mjs
```

`scripts/ci/unit-test-shard-policy.v1.json` declares each hosted unit shard's check name, artifact,
runner label, Gradle tasks, local reproduction command, owner, platform classification, and
warn-only budget settings. The verifier fails when that policy drifts from the `ci.yml` unit-test
matrix or the workflow-signal required checks.

The public `Unit tests` lanes also publish unit-test attribution and advisory budget reports from
existing Gradle/JUnit XML. Attribution shows module totals, slow suites, skips, failures, errors,
lane identity, and hosted runner image identity. Budget reports are diagnostic and warn-only; Gradle
remains the source of pass/fail truth.

## Deterministic UI evidence (EvidenceBundle v1)

> **Removed (tempdoc 638):** the EBv1 capture harness (`modules/ui-web/scripts/capture-evidence-bundle.mjs`) was deleted. For screenshot-driven UI/UX verification, use the `jseval ui-shot` harness (load the `/ui-check` skill). This section is retained as historical design context only.

For screenshot-driven UI/UX verification without committing pixel baselines, `modules/ui-web` could capture a deterministic EvidenceBundle v1 scenario (`ui_screenshots`) whose output landed under `tmp/agent-evidence/ui_screenshots/<timestamp>/`. The bundle was validated (structure + determinism budget) and avoided flaky timing via explicit UI state waits and demo-mode knobs.

## Desktop bundle smoke test (no GUI)

For desktop packaging drift prevention, `modules/ui` provides a non-GUI smoke test:
* **Gradle task:** `:modules:ui:smokeSidecarBundle`
* **What it asserts:** the staged sidecar bundle boots and responds to `GET /api/status` and `GET /api/health` (backend up; `/api/health` uses the lifecycle gate semantics and `/api/status` is well-formed and reports Worker/index state).

## CI/Preflight runners (drift catchers)

Some “tests” are also executed via scripts to generate artifacts and fail fast in automation:

### Pre-merge checks (cross-platform)
There is no single "canonical local gate" wrapper. The retired three-layer
chain (`scripts/gate.ps1` → `scripts/ci/local-agent-gate-win.ps1` →
`scripts/ci/dag-runner-local-agent-gate.mjs`) was deleted in slice
3a-1-8f §B.12 (2026-05-12) — the inner DAG runner had been broken since
2026-03-16 commit `a9c484f59` (which deleted `scripts/lib/` without
updating the runner). Reviewing whether a replacement wrapper was
useful concluded that bundling unrelated checks behind a label was a
coping mechanism, not a workflow value; agents and developers invoke
the individual tools when their subjects change.

Per-subject pre-merge checks (invoke individually; CLAUDE.md
"Verification Workflow" step 5 is the canonical list):

- `node scripts/ci/check-workflow-triggers.mjs` — workflow trigger policy compliance after editing `.github/workflows/*.yml`
- `node scripts/ci/check-root-readme.mjs` — README freshness after editing the root README
- `node scripts/governance/run.mjs --gate wire --mode gate` — wire-evolution gate under the unified discipline-gate kernel (tempdoc 530 Phase F; supersedes the prior standalone `scripts/contract-governance/` runner); load-bearing for any PR touching `contracts/**`

Heavy verification (gradle, frontend, cargo, playwright) is the
individual commands in CLAUDE.md "Verification Workflow" steps 1-4.

* **Architecture preflight**: runs ArchUnit tasks and dependency analysis and captures outputs for review (see `scripts/architecture/*`).
* **Perf regression lane**: superseded by jseval. Use `python -m jseval bench-concurrency` / `engine-bench` / `ingest-bench` + `compare` + `diff` + `trend` + `bisect`. The prior `scripts/perf/dag-runner-perf-suite.mjs` and `scripts/ci/dag-runner-perf-regression.mjs` runners were deleted by commit `a9c484f59` (2026-03-16); the residual wrappers and workflows were retired by slice 3a-1-8f §B.14 (2026-05-12).

### Gradle check gate

As of 2026-01-18, `./gradlew check --no-configuration-cache` is a **green gate** and enforces:

- Compilation success
- Unit + integration tests
- PMD static analysis (see `config/pmd/ruleset.xml` for the full rule set) + Error Prone compile-time checks
- Spotless code formatting
- Architecture guardrails

Before submitting PRs, run locally:

```bash
./gradlew check --no-configuration-cache
# Plus the per-subject pre-merge checks (CLAUDE.md "Verification Workflow" step 5)
# when their subjects changed — e.g., for contract changes:
node scripts/governance/run.mjs --gate wire --mode gate
```

### Test-efficacy (mutation adequacy on law-bearing seams)

Beyond *volume* (the `test-to-code` ratchet) and *execution* (JaCoCo coverage), the **`test-efficacy`**
discipline gate (tempdoc 555) measures whether tests on designated **law-bearing seams** actually
*constrain* them — via PIT mutation **test-strength** (killed/covered) plus a no-coverage ceiling, over
the pure seams declared in `governance/logic-seams.v1.json`. Because PIT re-runs the suite per mutant,
it is **not** in `check` or the public hosted `CI` fact lanes. Produce the opt-in evidence manually with
`node scripts/ci/report-pit-strength.mjs --run`, then gate it with
`node scripts/governance/run.mjs --gate test-efficacy --mode gate`. The register's integrity is guarded
cheaply on every run by `scripts/ci/check-logic-seams.mjs`. See
`docs/reference/contributing/discipline-gate-kernel.md`.

## Tier 4: AI Judge (Semantic Eval)
Testing search relevance is hard. "Is this result good?" is subjective.
The **AI Judge** is the coding agent (e.g., Claude Code) performing semantic evaluation during development sessions. The agent can:
1.  **Evaluate search quality:** Index a known corpus, run queries, judge whether results are relevant.
2.  **Evaluate RAG quality:** Ask questions, check if answers are grounded in retrieved context.
3.  **Identify gaps:** Audit the codebase for missing coverage, untested edge cases, or quality regressions.
4.  **Run existing tools:** Execute BEIR evaluation, golden corpus tests, and benchmark suites, then interpret results.

This is not an automated pipeline — it is agent-driven evaluation performed on demand during development.

## Tier 4.5: Agent Evaluation System

The agent system has its own two-lane evaluation framework that validates both deterministic correctness and live-model quality.

### Lane 1: Scripted Deterministic Battery

`modules/system-tests` includes `AgentBatteryTest`, which runs the full agent loop against `ScriptedAiService` (a deterministic mock that replays canned LLM responses). This validates:

- Tool dispatch correctness (right tool called with right arguments)
- Conversation flow (system prompt → tool call → tool result → synthesis)
- Safety gate behavior (WRITE tools pause, READ_ONLY tools auto-approve)
- Error taxonomy (transient errors retry, permanent errors abort)
- Budget tracking mechanics (events emitted, early termination triggers)

Gated by `-PincludeAgentTests=true` to avoid adding live-LLM latency to the default test suite.

### Lane 2: Live-Model Nightly Battery — RETIRED 2026-05-12

The legacy nightly battery (`scripts/ci/dag-runner-agent-battery.mjs` + harness) was retired in slice 3a-1-8f §B.14. The DAG-runner-based harness had been broken since 2026-03-16 (commit `a9c484f59` deleted its `scripts/lib/` dependencies); slice 487/491 already built a focused replacement (`scripts/ci/agent-battery-url-probe.mjs`) for the URL-emission probe specifically. Broader agent-quality evaluation now flows through jseval's `agent-eval` subcommand.

**Surviving artifacts:** `scripts/ci/build-agent-live-scorecard.mjs` + `scripts/ci/evaluate-agent-live-gate.mjs` + `scripts/ci/agent-battery-url-probe.mjs` + `scripts/ci/agent-battery-url-scorer.mjs` + the scenario JSON files. The DAG harness, its core, and the workflow yaml that drove it are gone.

### Evaluation Analytics

See [Agent Analytics Pipeline](21-agent-analytics-pipeline.md) for the behavioral tracking layer (event capture, session analysis, scoring rubric, LLM-as-judge evaluation, bias mitigation).

## The `TCK` (Technology Compatibility Kit)
*   **Module:** `modules/app-api-tck`
*   **Purpose:** Since we have strict interfaces (`SearchPort`, `IngestPort`, etc.), the TCK ensures that both the "Real" implementation (Worker) and the "Mock" implementations used in tests behave identically.

## Stress & Soak Testing
*   **Module:** `modules/system-tests` (soak package)
*   **Behavior:** Runs for hours. Indexing 100k tiny files, renaming folders rapidly, etc.
*   **Goal:** Find memory leaks and file handle exhaustion (Windows `AccessDenied`).

## Node.js DAG Runner Tests — RETIRED 2026-05-12

The DAG runner framework (`scripts/lib/orchestration/`) and the associated `dag-runner-*.test.mjs` suites were deleted by commit `a9c484f59` (2026-03-16) and the residual stranded runners were retired by slice 3a-1-8f §B.14 (2026-05-12). All benchmark / eval test surfaces now run through `scripts/jseval/` (Python, Click-based subcommand CLI). ADR-0009 records the original architecture for historical context.
