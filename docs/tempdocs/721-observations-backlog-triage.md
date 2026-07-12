---
title: Observations backlog triage — 2026-07-12
status: active — store drained 457→208 (this PR); fix/route/brief backlog tracked below
created: 2026-07-12
updated: 2026-07-12
---

# Observations backlog triage (2026-07-12)

A full triage pass over `docs/observations.md`. Every one of the **457** conditions
was dispositioned with primary-source evidence via kind-routed verification
(doc-line / code-pattern / build-lane / routing oracles). This PR (**A**) applies the
safe subset — retirements, dedup-merges, parks — and fixes the fold dedup-leak that
inflated the backlog. The remaining actionable work (fixes, routes, briefs) is the
durable record below and drains via follow-on PRs.

## What this PR did

- **Retired 249** conditions verified stale/fixed/pinned (build-lane green or pinned,
  doc/code drift already resolved, self-declared-fixed confirmed against current `main`).
- **Merged 8** duplicate fragments into their canonicals (the `unanchored-*` sprawl).
- **Parked 41** genuine deferred decisions under `## Parked` (each carries its revisit
  trigger in its occurrence note).
- **Fixed the fold-leak** (`observations-store.mjs` `matchGroup`): anchorless
  re-observations now merge by title-similarity + symptom class instead of minting a
  fresh `unanchored-N` slug each time — the mechanism that regrew the backlog. Regression
  tests added to `fold-observations.test.mjs`.
- Store went **457 → 208** (167 open + 41 parked).

## Method (for reproducibility)

Kind-routed oracles, each returning a verdict with `file:line` evidence:
`run-gate`/`run-test` (run once, fan the verdict back out), `doc-line` (read anchor vs
current source), `code-pattern` (confirm buggy pattern + covering test), routing lanes
(retire / route-to-home / park / live-brief). Build-lane fact: only `ts-any`, `clone`
(jscpd-missing), `liveness-constants-regen` remain red locally; all 43 flagged tests pass
on `main` or are CI-excluded/pinned.

## Remaining backlog (drains via PR B/C/D and the brief worklist)

### PR B — doc/register fixes (17)

- `0004-single-tenant-gpu-policy` — ADR-0004 line 52 stale: claims embedder "defaults to CPU-only (JUSTSEARCH_EMBED_GPU_LAYERS opt-
- `0024-app-packaging-nsis-per-user-download` — ADR-0024 stale: claims '~748 MB installer, no models bundled, all ~8.5 GB downloaded post-insta
- `05-ai-architecture` — Stale chat-model name in canonical docs: `docs/explanation/05-ai-architecture.md` (e.g. lines 1
- `624-agentic-retrieval-eval-rebuild` — Tempdoc frontmatter status fields can be multi-thousand-token essays (e.g. tempdoc 624's), whic
- `branch-safety` — branch-safety.md claims '.claude/settings.local.json is tracked' but it is gitignored (seeded f
- `buf` — Stale comment: contracts/catalog/severity/buf.yaml:8 still claims the root :wireGenerate task d
- `gitleaks` — gitleaks.toml allowlists `third_party/.*` as 'vendored upstream (llama.cpp etc.)' — that tree w
- `go-public-readiness` — go-public-readiness.md:202 publish include-list still lists `third_party/llama.cpp/` (MIT, vend
- `libraryview` — ui-ux.md 'Key Files' + UIX-013/UIX-014 reference the retired React stack (`components/views/Lib
- `model-inventory` — model-inventory.md Open Decision #1 ('should ONNX embedding+SPLADE enter model-registry.v2.json
- `runtime-config-ownership-matrix` — runtime-config-ownership-matrix stale: verify-runtime-config-matrix fails on 6 env/sysprop pair
- `search-quality-register` — Doc/code drift: search-quality register D-004 still says leg-arbitration 'SHIPPED (default off)
- `unanchored-drift-15` — package.json self-presentation bug: version says 1.0.0 (app is 0.1.0-alpha), description is sta
- `unanchored-general-50` — README badge line still ships the empty placeholder comment (build status / release / nDCG badg
- `unanchored-general-68` — Pre-existing markdownlint MD031 violations (10x, fenced code blocks not surrounded by blank lin
- `unanchored-missing-4` — docs/reference/configuration/environment-variables.md 'Search reranker' section is missing a ro
- `url-probe-system-prompt` — url-probe-system-prompt.md was already stale before my edit: core.rebuild-index shows audience=
### PR D — tooling fixes (fold-leak shipped in this PR) (19)

- `agent-utility-inspect` — jseval utility-run (Inspect eval_set): re-invoking a FULLY-COMPLETED set errors 'log file not a
- `agent-utility-run` — agent_utility_run._per_query_from_result (classic run_agent_eval path) does not filter results 
- `bench` — jseval `bench-concurrency --output-dir` doubles as the corpus base_dir (passed to corpora.load)
- `cli` — `jseval run --start-backend` cannot be port/data-dir isolated: `_run_iteration` calls `backend.
- `corpus-build` — corpus-build (build_golden) does not clean the target golden corpus-dir before materializing — 
- `corpus-fetch` — jseval corpus-fetch-clerc streams the full CLERC `collection.doc.tsv.gz` (GB-scale) line-by-lin
- `corpus-generate` — battlefield-en-v1's materialized corpus-dir contained 858 stale .txt files from an earlier, lar
- `corpus-generate-general` — 635 suite: generated corpus sources (4x ~450 long docs) committed under scripts/jseval/635-corp
- `cost-session` — cost-session analytics tool defect (develocity audit 2026-07-05): per-turn cost attribution fal
- `dev-runner-drift` — justsearch_dev_start defaults to launching the backend from the MAIN checkout's installed dist 
- `llm-bench` — llm-bench discover_doc_ids uses `*:*` which returns 0 in semantic-search dev stacks (real queri
- `run-judge-with-backend` — scripts/jseval/_run_judge_with_backend.py (untracked, tempdoc-624 judge-scoring-gap scratch) ha
- `server` — Dev-stack ownership gates only `start` (spawn); a non-owner agent can POST `/api/knowledge/inge
- `staged-recall-accounting` — staged_recall_accounting.py module docstring 'Output shape v1' example (lines ~40-56) is stale 
- `test-compare` — compare_runs.compare_pipeline_timing has no unit test (test_compare.py covers compare() + per_q
- `test-pipeline` — test-pipeline.mjs fails at line 361 (JSON.parse of empty intervene output for realLargeFile lar
- `test-report-ci-walltime-attribution` — scripts/ci/test-*.mjs unit tests (test-report-ci-walltime-attribution, test-report-unit-test-at
- `ui-check` — ui-shot color_scheme="light" steps render the dark app theme (persisted theme wins over prefers
- `ui-shot-cleanup` — ui-shot-cleanup.mjs exists on disk but is not wired in .claude/settings.local.json (hooks-refer
### PR C — product-code fixes (34)

- `agent-hooks-v1-drift` — governance/agent-hooks.v1.json changes have no regen-reminder hook (unlike lockfile-hint for bu
- `agentsessioncontroller` — Agent Sessions list: FE `SessionListItem` reads `startedAtEpochMs`/`status` but backend `toSess
- `browser` — MSW browser-mock activation may be unwired: `src/mocks/browser.ts` exports a `setupWorker` but 
- `citationspanel` — 526 §17 review note 3 — T1A citation anchor publish has no regression test; if `event.currentTa
- `documentpane` — Stale comments in DocumentPane.ts reference retired InspectorPane.ts (e.g. line 10, line 60 'Mi
- `hybridsearchops` — Stale code comments say recall-complete pool is 'default off' but resolved default is true — `H
- `jfhealthevent` — 526 §17 review note 1 — JfHealthEvent.handleConditionClick skip-on-button is overly broad; if a
- `knowledgeapi` — Pre-existing: modules/app-api/.../KnowledgeApi.java is a 1-byte empty stub (no package/class) —
- `knowledgesearchcontroller-general` — Search wire matchSpans entries carry empty `term` strings — `modules/ui/.../KnowledgeSearchCont
- `logger-general` — logger.ts uses CSS `var(--text-*)` inside console `%c` style strings, which don't resolve in de
- `modelcapabilityresolver` — Pre-existing: Jackson tools.jackson.databind JsonNode.isTextual()/asText() are deprecated in th
- `remotedocumentservice` — RemoteDocumentService.mapRetrieveContextResponse hardcodes docsUsed=0 on the rich-params retrie
- `resourceapimodule` — ResourceApiModule.shutdown() never calls intentStreamController::shutdown — its heartbeat sched
- `resourceregistry-test` — resourceRegistry.test.ts "produces the four expected registrations" fails in the FULL vitest su
- `retrospectivepanel` — RetrospectivePanel Inbox per-run cards show the raw underscored LifecycleState text (e.g. `READ
- `searchstate` — Search surface meta-line displays "2ms" while the actual /api/knowledge/search round trip is ~1
- `selectioncontextinjector` — SelectionContextInjector.java uses a raw `"\n\n---\n\n"` separator literal instead of a canonic
- `settings-v2-live` — Build-hygiene: `./gradlew build` (re)normalizes line endings (CRLF→LF) on `SSOT/catalogs/synony
- `shell` — 3 unused eslint-disable(no-console) directive warnings — `modules/ui-web/src/shell-v0/chrome/Sh
- `shell-drift` — Reachability-fossil (same class as the deleted CapabilityPills, found by the 613 follow-up hunt
- `tokens` — `gen-token-names --check` is stale on main: `--surface-content-max-width` (added to styles/toke
- `unanchored-drift-20` — ui-shot step skeleton-library fails in every harness mode (live --no-demo, --fixtures, demo): r
- `unanchored-error-6` — python -m jseval --help (bare, no subcommand) crashes with UnicodeEncodeError ('charmap' codec 
- `unanchored-general-15` — `.dev-data-548/` (worktree dev-stack data dir) is not gitignored — `git add -A` stages it; .git
- `unanchored-general-31` — The build's classpath-SSOT auto-sync (393 §3.6) rewrites `synonyms.{de,en}.v1.txt` to LF on eve
- `unanchored-general-35` — `@types/dompurify@^3.2.0` in ui-web devDeps is a redundant STUB — dompurify (now 3.4.10) ships 
- `unanchored-general-36` — `deleteByPathPrefix` (SqliteJobQueue) uses `path LIKE ? || '%'` — `_`/`%` in a path act as LIKE
- `unanchored-general-37` — a11y: critical `aria-valid-attr-value` on the search input (`jf-search-surface .q`) — an ARIA a
- `unanchored-general-39` — └ 615 §41 live-inspection pinned the 2 nameless Settings controls: the **"Load"** button (PLUGI
- `unanchored-general-51` — downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for 
- `unanchored-general-52` — downloadLlamaCudaPrebuilt skips the SHA-256 pin the CPU prebuilt has ('hash check disabled for 
- `unanchored-general-63` — worker.log floods DEBUG 'Loaded analyzers catalog from repo path' (i.j.a.lucene.analyzers.SsotA
- `unanchored-general-72` — jseval readiness off-by-one hangs large MIRACL fetches: `corpus-fetch-miracl --n-docs 40000` ma
- `unanchored-missing-7` — STALE observation correction: obs:vdu-pdf-fixtures-local-env claims eng.traineddata missing — o
### Design briefs — need a scoped decision before implementation (36)

- `16-gpu-booster-pack` — Canonical drift: `docs/explanation/16-gpu-booster-pack.md` presents the GPU Booster Pack as the
- `actionledgerview` — 550 thesis II NIT (independent review 2026-05-27): `ActionLedgerView` went stream-only and drop
- `agent-tool-arg-coercion` — Agent tool schema rejects string-typed numbers ("limit":"10") — burns an iteration every sessio
- `agent-utility-inspect-error` — New agent-eval leak class found + cleaned (624 DE cycle): an earlier run with direct write acce
- `agenthistoryindexer` — Restored agent runs are viewable but not searchable: AgentHistoryIndexer is purely live-listene
- `coreplugin` — Surface audience drift: `core.health-surface` + `core.activity-surface` are USER in Java CoreSu
- `coreplugin-missing` — FE-only surfaces: `core.memory-surface` + `core.command-palette` exist in FE CorePlugin.ts but 
- `extractionsandboxfactory` — Reported (sidecar audit, unverified by me): in_process extraction sandbox reportedly uses one l
- `gpljobcoordinator` — GPL has no doc-sampling cap — `GplJobCoordinator` iterates the entire corpus (`ListAllDocumentI
- `healtheventstreamcontroller` — Health 'Recent events' SSE (/api/health/events/stream) delivered NOTHING across two fresh dev s
- `index` — undoAllByOriginator/undoLastEffectByOriginator do not skip pendingOutcome:'rejected' entries; a
- `index-general` — Packaged Tauri CSP likely blocks the `index.html` Google Fonts `<link href="https://fonts.googl
- `knowledgesearchengine` — Search result count is nondeterministic across runs of the same query: LLM query expansion succ
- `onnxembeddingencoder` — Parent-doc embedding recomputes chunk vectors that are immediately discarded: EmbeddingService.
- `pendingauthorizationbridge` — PR#55 (655 MCP policy) post-merge review F1 (medium): duplicate approval ceremony for browser-o
- `presentation-demo` — presentation-demo §7 chip strip drifts from the real HEALTH_STATS_BODY strip — demo shows Index
- `record-merge` — Dev-tooling test-coverage gap (surfaced by 684): record-merge.mjs has NO dedicated test, and pr
- `release-v1` — mixed/enron-qa has no committed fetch/materialization mechanism (datasets/ is gitignored, no co
- `resourceview` — Tempdoc 662: after migrating startIndexingJobsBridge onto the shared MultiplexedStream, an open
- `runcontrolintent` — §30 "stop a run" wiring gap — VERIFY whether stopping an agent run actually halts the BACKEND l
- `searchresultsrenderer` — **(602 R3 spillover)** `SearchResultsRenderer` (the `x-ui-renderer='search-results'` declared-s
- `searchsurface` — Pre-existing a11y: the SearchSurface degraded-readiness banner reports an axe serious violation
- `settingscontroller` — **Latent governance gap (tempdoc 612 R1):** a TRUST/SECURITY-relevant settings change leaves NO
- `supervisiondecision` — SupervisionDecision has 3 pre-existing surviving ConditionalsBoundaryMutator mutants (backoffMs
- `unanchored-drift-6` — §B.2 job-queue count/list divergence: /api/status worker.core.pendingJobs=0 while /api/indexing
- `unanchored-error-7` — capture_evidence crashes on Windows with a libuv fail-fast (`Assertion failed: !(handle->flags 
- `unanchored-error-9` — Search results can surface a raw internal file with a GUID-shaped name and no title/summary/con
- `unanchored-general-46` — inspector-open ui-shot step reports 1 serious axe violation (pre-existing, unrelated to tempdoc
- `unanchored-general-62` — worker.log: native ORT stderr (ANSI-colored CUDA/BFCArena OOM traces) is captured with NUL-byte
- `unanchored-general-64` — Benchmarks module's default relative model-dir args (e.g. models/onnx/gte-multilingual-base) re
- `unanchored-general-69` — License-and-notices CI job failed 2x this session with different transient causes (Gradle-distr
- `unanchored-general-75` — Worker log shows ~40 'Loaded analyzers catalog from repo path' loads PER DOCUMENT during 686 re
- `unanchored-general-77` — main checkout shows models/*.onnx (gte, ner, reranker, splade) as untracked (??) despite .gitat
- `userconfigstate` — V1.5 dev-mode: Vite serves `.ts` and `.js`-extension URL imports as separate ES module instance
- `utility-comparison` — Pre-existing (unrelated to tempdoc 624 utility_comparison.py work): tests/test_agent_retrieval_
- `vieweraudiencestate` — viewerAudience: localStorage edits don't propagate to the in-memory store cache. A direct `loca
### Route — migrate the fact/lesson to its home, then retire from the store (47)

- `activate` — V1.5 dev-mode: Vite middleware adds `?import` query to dynamic-import URLs targeting `public/` 
- `aistatestore` — HealthSurface this.status frozen post-mount — observed_at stuck for minutes while /api/status a
- `auth` — 624 §M.9 cross-family calibration: user has ~/.codex/auth.json (Codex CLI) but its OPENAI_API_K
- `bertnerinference` — NER tokenizer construction lacked explicit truncation=false/padding=false (unlike SPLADE/embed'
- `corpus` — jseval corpus-fidelity's default --modes bm25_splade structurally cannot pass semantic=True sel
- `dev-runner` — Dev-runner is bound to main repo path (`F:/JustSearch`) and cannot live-verify Java backend cha
- `dev-server` — **First-plugin onboarding broken: the scaffold `dev-server.js` won't run.** `modules/ui-web/dev
- `fixtures` — `modules/ui-web/src/mocks/fixtures.mjs` (+ fixtures.d.mts/.test.ts) is orphaned React-era demo 
- `hook-base` — Agent-harness pitfall: PowerShell 5.1 pipes prepend a UTF-8 BOM to native stdin, so piping craf
- `hooks` — **Audit lesson — when probing for hooks, all four scopes must be checked**: `.claude/settings.j
- `inference-status-response` — contract-surfaces: registered InferenceStatusResponse with EMPTY consumers — its generated FE Z
- `inferencehandlers` — VDU offline processing (post-672) drains in ~100-doc batches and then stops with vduProcessing=
- `installed-plugins` — `frontend-design@claude-plugins-official` plugin active at user scope surfaces a `frontend-desi
- `isolatedbackendfixture` — LESSON (static-green != live-working, 2026-05-27): merging 138 commits of `main` into a long-li
- `knowledgesearchcontroller-error` — /api/knowledge/search sharp edges found while harvesting index stats: facets return silently EM
- `remove-worktree` — Second orphaned worktree dir `.claude/worktrees/597-chat-count` is on disk but unregistered (no
- `unanchored` — scoop python/java `current` symlinks are Windows-unfriendly (mid-session regression, likely nee
- `unanchored-drift-13` — bash-tool grep/wc/sha256sum via the /f/... posix-mount path returned stale (pre-edit) content f
- `unanchored-drift-14` — Hard Invariant #1 names only Lucene, but the worker-exclusive SQLite job queue is equally owner
- `unanchored-drift-8` — **Live-verify dev gotcha — `JAVA_TOOL_OPTIONS` is unreliable for passing a `-D` to the head.** 
- `unanchored-error` — Local Rust/cargo builds blocked by Windows Application Control policy (os error 4551) on freshl
- `unanchored-error-10` — eval-run logs commit machine context by default — the PR-117 scrub found 3,900+ 'C:\Users\<name
- `unanchored-error-4` — HealthSurface's error banner ('Failed to fetch') latches and does not self-clear on subsequent 
- `unanchored-error-5` — Running ./gradlew.bat :modules:<x>:compileJava/test/spotlessApply in the SAME worktree while a 
- `unanchored-general-10` — 526 §17 verification finding — Lit class-field shadowing pattern: `static properties = { foo: {
- `unanchored-general-11` — Tempdoc 501 §12.6 trust-envelope is gated on three pre-conditions: sigstore-java dependency lan
- `unanchored-general-13` — Tempdoc 501 §13 F3 (`@SensitiveField` ArchUnit enforcement) + F5 (per-component `LifecycleSnaps
- `unanchored-general-16` — Agent pitfall: piping source files through PowerShell 5.1 `Get-Content`/`Set-Content -Encoding 
- `unanchored-general-17` — HealthSurface `static styles` mixes hardcoded rgba() literals with tokens (e.g. :247,:325,:990)
- `unanchored-general-18` — Backend message catalog `registry-surface.en.properties` lacks entries for `token-editor-surfac
- `unanchored-general-22` — The MCP `justsearch-dev` dev stack launches the backend from the **main checkout** (`dataDir F:
- `unanchored-general-24` — **Live-verify dev gotcha — logback logs are NOT in the captured gradle stdout.** App SLF4J/logb
- `unanchored-general-26` — 565 independent UX-audit residual (moderate/minor — agent window): #6 streaming answer needs an
- `unanchored-general-27` — /dev-stack: chat model is runtime-configurable via `POST /api/settings/v2` `{"llm":{"modelPath"
- `unanchored-general-30` — Index is in `BLOCKED_LEGACY` embedding state: `Embedding compatibility: BLOCKED_LEGACY (index h
- `unanchored-general-34` — 585 split relocated several `AgentController` symbols referenced by open items above: `writeAge
- `unanchored-general-38` — DX/§4 (tempdoc 618): running repo-wide regen (`skills-sync`/`llmstxt-generate`) on a multi-agen
- `unanchored-general-41` — tempdoc 623 U7 follow-up: capture ORT library version string worker-side (Head cannot init OrtE
- `unanchored-general-42` — Quantified follow-up to the staged_recall_accounting trec-blindness bug (see earlier entry this
- `unanchored-general-49` — 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band
- `unanchored-general-5` — 635 suite-profile: --records-root agent-record lookup expects tmp/635-<dataset-name>/ but the p
- `unanchored-general-55` — 624 §T.2 scan-battlefield premise empirically resolved NEGATIVE at the shipped degradation band
- `unanchored-general-78` — Recurring orchestration failure mode (2x this session): a stopped/idle subagent is NOT re-woken
- `unanchored-missing` — **Live-verify dev gotcha — zombie `HeadlessApp` JVMs.** Manual `gradlew :modules:ui:runHeadless
- `unanchored-missing-3` — `modules/ui-web/node_modules` in the main checkout is incomplete (.bin empty, ~82 pkgs; vite pr
- `unanchored-missing-6` — npm version skew trap: a locally-resynced modules/ui-web/package-lock.json (npm 11.6/node 24.12
- `writepathops` — STRUCTURAL TRAP confirmed live: KnnFloatVectorField (VECTOR) is non-stored and silently DESTROY
### Needs live verification / route-to-pin (14)

- `baseline` — ts-any baseline needs seeding before the gate can be wired to CI in gate-mode. The gate current
- `default-index` — Dev-stack: orphaned dev-runner/Worker processes accumulate across sessions and hold `index/defa
- `healthsurface-flake` — HealthSurface 'Recent events' renders NO ConditionStore conditions: `hs.events` is empty for AL
- `indexingoverlay` — IndexingOverlay gating field `ai.index.embeddingQueueSize` does not track the embedding *backfi
- `logger` — Governance `ts-any` gate: silent-growth across ~16 files untouched by 549 (logger.ts, platform.
- `multiplexedstream` — governance kernel: ts-any gate fails on modules/ui-web/src/shell-v0/streaming/MultiplexedStream
- `searchplanner` — Live worker returns chunk-merge skipped(SKIPPED_QUERY_SYNTAX) even for simple/absent querySynta
- `unanchored-drift` — Methodology improvement — tempdocs that propose to mirror an existing component should source-a
- `unanchored-drift-12` — Killing the jseval-launched Head (runHeadlessEval) java process via Stop-Process/taskkill does 
- `unanchored-drift-2` — Inbound references to 3a-1-8f assume narrow Axis-6 framing ("mechanical structural-diff"); kern
- `unanchored-drift-4` — workerRpcStale env bug — Head→Worker status RPC reports stale on first stack-start of a session
- `unanchored-drift-5` — Dev-stack agent-tool execution fails: `OperationExecutorImpl` throws "No handler registered for
- `unanchored-drift-9` — Verify the search surface's "Semantic search degraded — showing keyword results" banner is firi
- `unanchored-gate-red-2` — 553 Phase 2b (clone tripwire gate) deferred: jscpd/CPD not installed + no CPD gradle task; a cl

## Owner decisions (parked, surfaced for a call)

- **README badge placeholder** (`unanchored-general-50`) — wire real build/release/nDCG
  badges, or remove the placeholder comment.
- **`skeleton-library` ui-shot step** (`unanchored-drift-20`) — implement the missing FE
  `data-testid`, or drop the harness step.
- **`coreplugin` audience drift** — `core.health-surface`/`core.activity-surface` are
  `OPERATOR` in FE `CorePlugin.ts` but `USER` in Java `CoreSurfaceCatalog`; align one way.

## Independent-review follow-ups (PR A fold-leak)

An independent reviewer (reviewer ≠ implementer) audited PR A. Findings actioned in this PR:

- **Over-merge risk (fixed):** `sigTokens` stripped all backtick-quoted spans, discarding
  the discriminating file/symbol identifier so two different-artifact notes sharing a
  template could collide. Now keeps identifier tokens **and** refuses a merge when two
  anchorless notes name disjoint backtick identifiers (`identTokens`/`disjoint`). Test added.
- **Parked absorption (fixed for the anchorless path):** `matchGroup` runs over
  `store.groups`, which includes `## Parked`. A recurrence could silently bump a dismissed
  condition. The new anchorless path now skips parked groups so recurrences resurface. Test added.

Deliberately **not** changed here (kept scoped to the anchorless leak): the **pre-existing
exact-anchor path** has the same parked-absorption interaction (low-risk — exact string
equality, not probabilistic). Follow-up: give the anchor path the same `isParked` skip in a
future fold pass, with a fold-vs-parked test for both paths.
- 16/55 judgment-based retirements were independently re-verified as genuinely stale; the
  other ~233 retirements rest on the fan-out evidence, not a full re-audit.
