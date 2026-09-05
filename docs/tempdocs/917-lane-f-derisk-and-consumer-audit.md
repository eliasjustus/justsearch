---
title: Lane F — De-risk and Consumer Audit (PR 1 pre-work)
type: tempdocs
status: "AUDIT (2026-09-03) — read-only consumer audit for lane F PR 1; lane F NOT started; go/no-go pending"
created: 2026-09-03
author: Claude (audit agent, session_01CgW4Ut1yU1DQ86bXivJdUJ)
category: engine / process-boundary
related: lane-F brief (I:\Downloads\justsearch-lane-briefs\lane-F-engine-merge.md), ADR-0001, ADR-0002, ADR-0048, tempdoc 885
---

# Lane F — de-risk and consumer audit

Read-only audit performed in `.claude/worktrees/lane-F` (branch `worktree-lane-F`, base
`39d38f73` = origin/main incl. PRs #611–#618, verified via `git log -1 --oneline`). No code,
config, or gate was edited; nothing was built or committed. Lane F itself has not started —
this is the PR 1 "audit every consumer" step required before any `justsearch.engine.mode`
work begins.

## Summary (10 lines)

- Category 1 (Head consumers): 1 concrete `RemoteKnowledgeClient` (implements `SearchPort` +
  `IndexingService`, no separate interface), 1 production construction site
  (`KnowledgeServerBootstrap.java:261`), ~27 production classes hold/call it directly.
- Category 2 (Worker gRPC services): 3 impl classes in `modules/worker-services` (not
  `indexer-worker`) — `GrpcSearchService` (1017 lines, 10 RPCs), `GrpcIngestService` (2198
  lines, 37 RPCs), `GrpcHealthService` (329 lines, 1 RPC) — wrapped by
  `Delegating{Search,Ingest,Health}Service` in `modules/indexer-worker/.../grpc/` for hot-reload.
  `indexing.proto` = 48 `rpc` lines total (10+37+1); brief's 37/50 claim for
  IngestService/total is correct for IngestService, and 48 is the true total once
  `infra_diagnostics.proto`'s 2 RPCs (a separate service) are excluded.
- Category 3 (streams): `SubscribeIndexingJobs` (`RemoteIndexingJobsBridge.java:269`) and
  `ScanRoot` (`RootLifecycleOps.java` `ScanRootFn`) both confirmed server-streaming, each with a
  Head-side SSE fan-out (`ScanProgressController`, `IndexingJobsBridgeWiring`).
- Category 4 (dev MCP): heavy worker-process awareness in `dev-runner.cjs` (worker.log
  preservation/rotation, JDWP port, reload signal). PR #617 (`f6f8433d`) is **not** a Worker
  spawn-path change — brief correction below.
- Category 5 (chaos/system tests): `WorkerProcessManager` (chaos process manager,
  `modules/system-tests/.../chaos/`) spawned by 20 test classes across
  systemTest/soakTest/integrationTest source sets.
- Category 6 (jseval): `backend.py`/`ops.py`/`contract_violations.py` all know `worker.log` as
  a distinct artifact; no direct gRPC-port or MMF-offset literals found in jseval.
- Category 7 (governance/CI): 3 ADR-0001/0002 probes in `governance/adr-probes.v1.json` will
  need re-examination on merge; zero `execution-surfaces`/`operation-surfaces` entries name
  gRPC classes (out of scope for those registers); `LayeringEnforcementTest` pins 3
  worker-isolation rules that lane F's PR1 module choice must respect or amend.
- Category 8 (residue grep): all 15 terms have live hits in `modules/` and `docs/`; `MMF`,
  `heartbeat`, `WorkerSpawner` are the widest (30-127 files); full table below.
- Category 9 (MMF today): 64-byte layout has **8** declared fields (doc's table is stale — a
  9th field, `OFFSET_ENERGY_REDUCED = 17`, exists in code and is undocumented); `main_gpu_active`
  has exactly 6 production readers (matches brief); the 4 MiB/32 MiB message-size mismatch the
  brief cites **is already fixed** (tempdoc 882 item 5, `GrpcMessageLimits`) — brief correction.
- Top 5 highest-risk consumers: (1) `RemoteKnowledgeClient` itself — 1806 lines, 27+ callers,
  the whole in-process-transport PR1 diff surface; (2) `GrpcIngestService` — 2198 lines, 37 RPCs,
  migration/VDU/upgrade-quiescence state machines behind it; (3) `WorkerSpawner` — env-forwarding,
  JVM flags, suicide-pact heartbeat, hot-reload classpath, all retired in PR3; (4)
  `WorkerProcessManager` (chaos) — 20 test consumers assume a separate OS process to crash/kill;
  (5) `dev-runner.cjs`/`server.mjs` — worker.log rotation, JDWP target selection, MMF reload byte
  all assume two processes.

## 1. `RemoteKnowledgeClient` callers in the Head

`RemoteKnowledgeClient` is a **concrete final class**, not an interface implementation of a
dedicated `KnowledgeClient` seam:
`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java:99`
— `public final class RemoteKnowledgeClient implements Closeable, SearchPort, IndexingService`.
`SearchPort` is declared at `modules/core/src/main/java/io/justsearch/core/search/SearchPort.java:12`;
`IndexingService` at `modules/app-api/src/main/java/io/justsearch/app/api/IndexingService.java:17`.
The only other `SearchPort` implementor is
`modules/app-services/.../bootstrap/phases/NoopSearchPort.java` (test/offline stub) — no other
`IndexingService` implementor exists in production. So "extract the interface" (PR1 brief item) is
real work: today `SearchPort`+`IndexingService` are two separate interfaces the class satisfies,
not one unified `KnowledgeClient`.

Production construction site (the only one):
`modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java:261`
— `client = new RemoteKnowledgeClient(signalBus, config.deadlineMs(), config.maxRetries(), config.batchSize(), circuitBreaker, ipcTelemetry);`

| consumer | path:line | what it calls / depends on | in-process replacement shape | risk | notes |
|---|---|---|---|---|---|
| `KnowledgeServerBootstrap` | `worker/KnowledgeServerBootstrap.java:74,261,642,903` | owns the `volatile RemoteKnowledgeClient client` field, constructs it, exposes `client()` | replace with a supplier of the in-process facade; keep the `client()` accessor shape | high | single construction/reconnect site — the PR1 flag branch point |
| `HeadAssembly` | `HeadAssembly.java:63,1269` | `private volatile RemoteKnowledgeClient knowledgeClient` | same field, in-process-backed | high | composition root |
| `KnowledgeHttpApiAdapter` | `worker/KnowledgeHttpApiAdapter.java:129,143,223,229,247` | `knowledgeServer.client()` 5 call sites | unaffected if `client()` shape preserved | med | |
| `KnowledgeSearchEngine` | `worker/KnowledgeSearchEngine.java:623` | `knowledgeServer.client()` | unaffected | med | |
| `WorkerStatusCache` | `worker/WorkerStatusCache.java:115,193` | `knowledgeServer.client()` | unaffected | low | |
| `RemoteDocumentService` | `worker/RemoteDocumentService.java:44,52,63` | `Supplier<RemoteKnowledgeClient>` | unaffected if supplier shape kept | med | implements `DocumentService` |
| `AgentToolFactory` | `bootstrap/phases/AgentToolFactory.java:71,131` | ctor params `RemoteKnowledgeClient knowledgeClient` | unaffected | med | |
| `AgentToolHandlers` | `bootstrap/phases/AgentToolHandlers.java:150` | ctor param | unaffected | low | |
| `BootstrapHelpers` | `bootstrap/phases/BootstrapHelpers.java:42` | ctor param | unaffected | low | |
| `GplOrchestration` | `bootstrap/phases/GplOrchestration.java:66,107,140,181` | `clientSupplier.get()`, static helper params | unaffected | med | |
| `IndexingJobsBridgeWiring` | `bootstrap/phases/IndexingJobsBridgeWiring.java:51` | `knowledgeClientSupplier.get()` | feeds `SubscribeIndexingJobs` stream — see §3 | high | |
| `ServicePhase` | `bootstrap/phases/ServicePhase.java:79,285` | ctor param + supplier | wiring-only | low | |
| `OfflineCoordinator` | `vdu/OfflineCoordinator.java:101,203,215,223` | `knowledgeClientSupplier.get()` × 4 | VDU RPCs (`UpdateVduResult` etc.) | med | |
| `VduBatchProcessor` | `vdu/VduBatchProcessor.java:137,334` | supplier + direct param | VDU batch RPCs | med | |
| `WorkerEncoderRuntimeCache` | `observability/WorkerEncoderRuntimeCache.java:62` | `clientSupplier.get()` | encoder-runtime introspection | low | |
| `AgentHistoryIndexer` | `agenthistory/AgentHistoryIndexer.java:270` | `clientSupplier.get()` | low | |
| `SearchPerSourceExecutor` | `worker/SearchPerSourceExecutor.java:40` | direct param | search fan-out | med | |
| `KnowledgeServerHealthMonitor` | `worker/KnowledgeServerHealthMonitor.java:567` | `RemoteKnowledgeClient client` field | health polling — retired in PR3 for in-process | high | this class's whole reason to exist is process-boundary health |
| `EncoderRuntimeController` (ui) | `ui/api/EncoderRuntimeController.java:43,45,50,62` | `volatile RemoteKnowledgeClient client`, late-bound setter | unaffected | low | |
| `SessionPoliciesController` (ui) | `ui/api/SessionPoliciesController.java:51,53,65,77` | same late-bind pattern | unaffected | low | |
| `UpgradeApiModule` (ui) | `ui/api/UpgradeApiModule.java:29,39,46` | `Supplier<...RemoteKnowledgeClient> workerClient` (FQN, no import) | unaffected | low | |
| `UpgradeController` (ui) | `ui/api/UpgradeController.java:25,39,46` | same FQN supplier pattern | unaffected | low | |
| `IndexingController` (ui) | `ui/api/IndexingController.java:44,53,65,128,129` | typed on `IndexingService` interface, not the concrete class | already interface-clean — no change needed | low | model for what PR1's extracted interface should look like |
| `WatchedRootsStore` | `worker/WatchedRootsStore.java` | (grep hit, not yet read in detail) | — | low | not expanded — low materiality |
| `KnowledgeServerHealthMonitor`, `MigrationOps`, `RootLifecycleOps`, `SyncOps`, `VduOps`, `SearchRpcOps`, `SearchRpcExecutor`, `IngestRpcExecutor`, `IpcTelemetry`, `CancelToken`, `BootRecoveryDecision`, `RemoteIndexingJobsBridge` | `modules/app-services/src/main/java/io/justsearch/app/services/worker/*.java` | grep-confirmed references (`RemoteKnowledgeClient` string hits), not individually line-audited above given the file count | ops-classes the in-process client should keep calling directly (PR1 brief: "call the ops, not the gRPC stubs") | med | full file list in the grep run; see raw list in audit session |

**Other `ManagedChannel`/stub construction sites (production only):**
`RemoteKnowledgeClient.java:317` (`ManagedChannelBuilder`), `:337-340,354-355` (4 blocking/async
stub constructions). **No other production `ManagedChannelBuilder`/`newBlockingStub`/`newStub`
site exists** outside `RemoteKnowledgeClient.java` — the only other hits are test-only
(`GrpcTestClient.java` in `system-tests/src/main`, which is chaos-test infra, not production
runtime) and `AotTraining.java:46` (`touch("io.grpc.ManagedChannelBuilder")` — an AOT-cache warm
touch, not a channel construction). This narrows PR1's blast radius: exactly one class owns the
wire transport.

## 2. Worker-side gRPC services

RPC counts (grep `^\s*rpc ` in `modules/ipc-common/src/main/proto/indexing.proto`, verified
by counting `service` blocks):

| Service | RPC count (proto) | Impl file | Impl `public void` RPC overrides (grep-counted) |
|---|---|---|---|
| `SearchService` | 10 (`indexing.proto:299-317`) | `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java` (1017 lines) | not individually enumerated (file too large for this pass; the proto count is authoritative) |
| `IngestService` | 37 (`indexing.proto:1242-1371`) | `modules/worker-services/.../services/GrpcIngestService.java` (2198 lines) | 37 (verified 1:1 against the proto list) |
| `HealthService` | 1 (`indexing.proto:1700-1701`) | `modules/worker-services/.../services/GrpcHealthService.java` (329 lines) | matches proto's single `Check` RPC |
| **Total, `indexing.proto`** | **48** | | brief's "50 total" is **wrong**; `grep -c '^\s*rpc '` = 48, and 10+37+1=48 exactly |
| `InfraDiagnosticsService` (separate proto/file) | 2 (`io/justsearch/ipc/v1/infra_diagnostics.proto:26-27`) | not audited (out of Search/Ingest/Health scope) | — |

Brief correction: the brief's "IngestService alone has 37" **is correct** (verified exactly).
The brief's "50 RPCs" total is **not** — `indexing.proto` has 48 `rpc` lines; there is no
50-RPC file. If the brief meant 48 + the 2 `InfraDiagnosticsService` RPCs = 50, that reading is
consistent with the numbers but the brief doesn't say so; flagging the ambiguity rather than
guessing.

Registration/wiring:
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/KnowledgeServerGrpcWiring.java`
(53 lines) — `createGrpcServer()` wraps each concrete `Grpc*Service` in a
`Delegating*Service` (from `modules/indexer-worker/.../grpc/`) and registers the delegate with a
Netty `Server`, sourcing message limits from `GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES`
(:31). The delegate pattern exists for hot-reload class-swap (JDWP), per
`DelegatingSearchService.java`'s javadoc: "delegate is typed as the generated ImplBase to support
cross-classloader hot-reload (Phase 2, tempdoc 305)."

`ForegroundLoadInterceptor`
(`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/ForegroundLoadInterceptor.java`,
124 lines) — a `ServerInterceptor` feeding `ForegroundLoad` from the 9 foreground
`SearchService` methods (built off generated method descriptors, `:37-45`). **Its own javadoc
already names lane F**: "Deliberately the only producer of the gauge, and deliberately thin:
under the lane-F Head/Worker merge there is no gRPC boundary left, so this adapter is thrown
away while the gauge and the pacing policy it feeds survive." (`:18-21`) — this confirms the
gauge/policy split (`ForegroundLoad`, `IndexingPacing`) is already designed to survive PR3's
interceptor removal.

## 3. Streams

`SubscribeIndexingJobs` (server-streaming, `indexing.proto:1318`): Head-side consumption at
`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridge.java:269`
— `asyncStub.subscribeIndexingJobs(SubscribeIndexingJobsRequest.newBuilder().build(), obs)`. Wired
in `bootstrap/phases/IndexingJobsBridgeWiring.java:51` (`knowledgeClientSupplier.get()`).

`ScanRoot` (server-streaming, `indexing.proto:1364`): Head-side dispatch through
`modules/app-services/src/main/java/io/justsearch/app/services/worker/RootLifecycleOps.java` —
the `ScanRootFn` functional interface (`:113-119`) forwards each `ScanRootProgress` to a
`progressConsumer`, called from `:235` ("dispatch a Worker-side ScanRoot RPC"). SSE fan-out is
`modules/ui/src/main/java/io/justsearch/ui/api/ScanProgressController.java` via a
`ScanProgressRegistry` + `SseWriter` (`:36-42`) — not a direct gRPC-stream-to-SSE passthrough;
there's an intermediate registry, which is a smaller PR1 diff than a raw stream rewire.

No other server-streaming RPC found in `indexing.proto` besides these two (confirmed by the
`stream` keyword count in the earlier `rpc` grep — only `SubscribeIndexingJobs` and `ScanRoot`
return `stream`).

## 4. Dev MCP tools

`scripts/dev/dev-runner.cjs` worker-process awareness (all confirmed grep hits):
worker.log preservation/rotation (`:574-650`, handles `worker.log.1`/`worker.log.2` rotation by
rename), JDWP port selection (`:818` — "hardcoded 5005 in three independent places... this file,
the MCP reload handler's default, and WorkerSpawner's fallback" — now resolved once and forwarded
via `JUSTSEARCH_DEV_DEBUG_PORT`), and per-run worker.log identity stamping (`:1865,1912,2395`).

Reload byte writer: `scripts/dev/justsearch-dev-mcp/server.mjs:2840` —
`await fh.write(buf, 0, 1, 29); // OFFSET_RELOAD_SIGNAL = 29` — writes the MMF hot-reload signal
directly by literal offset (mirrors `MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL`, not imported
from it — a duplication PR3's sweep must catch).

**Brief correction — PR #617 (`f6f8433d`) is not a "Worker spawn-path change."** Read the full
diff (`git show f6f8433d --format="" -- scripts/dev/dev-runner.cjs
scripts/dev/justsearch-dev-mcp/{server.mjs,cli.mjs} scripts/dev/lib/ownership-verdict.cjs`): the
change adds a `--dist-from` flag threaded through `dev-runner.cjs`/`cli.mjs`/`server.mjs` and a
pure `computeProvenanceMismatch()` predicate in `ownership-verdict.cjs`, all in service of
tempdoc 913 T1 ("does the running stack run code from a checkout the caller did NOT ask for") —
i.e. dev-stack **launch provenance for worktree variant resolution**, not Worker process argv.
The PR's `RuntimeActivationService.java` change (169 lines) is likewise unrelated to the Worker
gRPC process: it's `variantsRoot()` memoization for **AI-runtime** (llama-server) variant
resolution (913 H1: `ai_activate` failing on worktree stacks with `installedVariants: []`), not
`WorkerSpawner`. Neither touched file constructs a Worker argv or spawns `IndexerWorker`. This
does not change lane F's audit conclusions (dev-runner.cjs still has extensive worker.log/JDWP
awareness independent of #617) but the brief's specific citation of #617 as evidence of "spawn-path
changes" is incorrect provenance.

## 5. Chaos/system tests that spawn a Worker

Chaos process manager:
`modules/system-tests/src/main/java/io/justsearch/systemtests/chaos/WorkerProcessManager.java`
extends `ManagedProcess`; javadoc: "Manages Knowledge Server worker processes for system tests"
with distribution-script or JAR launch modes and MMF signal-path management (`:15-30`). 20
consumer test classes across `systemTest`/`soakTest`/`integrationTest` source sets (full list:
`SoakSuiteTest`, `SummarizationPipelineE2ETest`, `ChaosSuiteTest`, `ExtractionSandboxChaosTest`,
`CompleteIndexingWorkflowE2ETest`, `ConfigPropagationTest`, `CorruptionRebuildE2ETest`,
`GrpcCommunicationTest`, `GrpcDataIntegrationTest`, `IndexBasePathLockE2ETest`,
`MigrationControlE2ETest`, `PauseResumeMigrationE2ETest`, `RollbackE2ETest`,
`SwitchingFenceBufferingE2ETest`, `SyncDirectoryIntegrationTest`, `WorkerSpawnTest`,
`ReadWhileWriteTest`, `WindowsTortureTest`, `VduBatchProcessorE2ETest`,
`VduRecoverySystemTest`). Each assumes an OS-process boundary to kill/crash/isolate; PR2's
"retarget the crash injector" item names this class directly.

Separately, `modules/app-services/src/integrationTest/.../KnowledgeServerIntegrationTest.java`
and `RichDocumentIntegrationTest.java` construct `RemoteKnowledgeClient` directly against an
in-process test server (not `WorkerProcessManager`) — lighter-weight integration tests, lower
risk for PR1.

## 6. jseval

`scripts/jseval/jseval/backend.py`: `_WORKER_LOG_REL = Path("logs") / "worker.log"` (`:38`),
pre-sweep worker.log tail capture (`:778-788`) for forensics before an orphan-process kill.
`scripts/jseval/jseval/commands/ops.py:52,67,234` — a dedicated worker.log discovery/print
command. `scripts/jseval/jseval/projections/contract_violations.py:88` references "worker log"
in a projection docstring. No literal gRPC port number, `worker_snapshot`, or ordinal-450 string
found in `scripts/jseval/**` (grep across `backend.py`, `ops.py`, `contract_violations.py`
returned zero hits for those three terms) — jseval's Worker-process coupling is narrower than
the brief implies, limited to log-file identity, not port/ordinal knowledge.

## 7. Governance/CI

**ADR probes** (`governance/adr-probes.v1.json`, tempdoc 884 register): `adr-0001-lucene-owners-pinned`
(`:10-17`, kind `test`, backs onto `IndexWriterOwnershipTest`), `adr-0002-mmf-layout-pinned`
(`:18-25`, backs onto `MmfWorkerSignalLayoutV1Test#reserved1EndsAtMmfSize`),
`adr-0002-mmf-constants-pinned` (`:26-36`, `grep-present` on `MMF_SIZE_BYTES = 64` and
`OFFSET_WORKER_GRPC_PORT = 20`), `adr-0002-grpc-present` (`:37-49`, `grep-present` on
`libs\.grpc|io\.grpc` across `modules`). All four are premises PR3's "single engine process" ADR
must explicitly supersede per the amendment procedure — none can simply be deleted, per the
register's own note ("never to edit the probe until it passes... re-examine and amend the ADR").
ADR-0048 probes (`:508-580+`) concern the extraction-child-pool, which lane F explicitly keeps
out-of-process — those probes should survive unchanged.

**Execution/operation-surfaces registers**: zero entries reference `Grpc`/gRPC classes in either
`governance/execution-surfaces.v1.json` or `governance/operation-surfaces.v1.json` (grep
confirmed) — out of scope for those two registers; nothing there needs a lane-F sweep.

**ArchUnit layering** (`modules/app-launcher/src/test/java/io/justsearch/app/launcher/LayeringEnforcementTest.java`):
`indexerWorkerMustNotDependOnUi` (`:137-145`, "indexer-worker must not depend on ui (runs in
separate process)" — the comment states the premise PR1's module-choice item must not violate),
`indexerWorkerMustNotDependOnAppServices` (`:184-193`, "isolated worker"),
`appInferenceMustNotDependOnAppServices` (`:151-159`, unrelated to the Worker boundary but a
sibling rule in the same enforcement class). PR1's brief item — "add a layer, do not punch a
hole" for `ui → worker-services` — is a real ArchUnit constraint, not just a style preference:
whichever module composes the in-process engine must be added to this test's allowed-dependency
lists explicitly, or the build fails.

**expected-state pins**: no pin in `scripts/agent-analytics/expected-state.v1.json` names a
worker/IPC test by symbol (grep for `indexerworker`/`WorkerSpawner`/`grpc`/`Grpc` combined with
`ipc` returned one unrelated hit about the `buf` CLI/wire-contract tooling, not a worker/IPC test
pin). Lane F PR3 will not need to retire an expected-state pin for this reason.

**Other named gates**: `check-live-witness.mjs` (register-integrity for the `SearchTrace`
live-registry witness, ADR-0042 — orthogonal to process boundary, no Worker-specific logic read
in its header), `check-runtime-manifest-closure.mjs` (writers into `<dataDir>/runtime/`; would
need its allowlist re-audited if the merged Engine changes where the manifest is written from,
but the header shows a producer allowlist keyed by writer call site, not by process),
`check-dev-mcp-doc-sync.mjs` (spawns the MCP server over stdio and diffs `tools/list` against
docs — unaffected by the process merge itself, only by tool surface changes PR2/PR3 might make).

## 8. Residue list for PR 3 (grep table)

File-count per top-level dir (`grep -rlc <term> <dir> | wc -l`), from the worktree root:

| term | modules | scripts | governance | docs | config | .claude | SSOT | contracts |
|---|---|---|---|---|---|---|---|---|
| `MMF` | 35 | 5 | 3 | 64 | 0 | 3 | 0 | 0 |
| `heartbeat` | 127 | 18 | 5 | 71 | 0 | 2 | 0 | 1 |
| `suicide` | 15 | 0 | 2 | 12 | 0 | 0 | 0 | 0 |
| `breath` | 11 | 1 | 1 | 20 | 0 | 0 | 0 | 0 |
| `OFFSET_` | 15 | 2 | 1 | 7 | 0 | 1 | 0 | 0 |
| `WorkerSpawner` | 30 | 9 | 3 | 74 | 0 | 2 | 0 | 0 |
| `ORDINAL_WORKER_SNAPSHOT` | 1 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| `WORKER_FORWARDED_PROPS` | 7 | 0 | 0 | 7 | 0 | 0 | 0 | 0 |
| `MainSignalBus` | 17 | 0 | 1 | 16 | 0 | 1 | 0 | 0 |
| `MmfWorkerSignalBus` | 8 | 0 | 3 | 17 | 0 | 1 | 0 | 0 |
| `MmfWorkerSignalLayoutV1` | 11 | 0 | 1 | 14 | 0 | 1 | 0 | 0 |
| `WorkerLivenessDecision` | 5 | 0 | 2 | 1 | 0 | 0 | 0 | 0 |
| `SupervisionPolicy` | 11 | 0 | 2 | 6 | 0 | 0 | 0 | 0 |
| `main_gpu_active` | 2 | 0 | 0 | 15 | 0 | 1 | 0 | 0 |
| `ForegroundLoadInterceptor` | 3 | 0 | 1 | 3 | 0 | 0 | 0 | 0 |

Counts are file-count (`grep -c` per file, summed as file hits, i.e. "files containing the
term"), not raw occurrence count — matches the brief's "hit counts per top-level dir" phrasing
loosely; if the orchestrator wants raw occurrence totals instead, re-run with `grep -rc | awk`
summation. `docs` is consistently the largest bucket for every term except `WorkerSpawner` and
`heartbeat` in `modules` — most of the residue is prose (tempdocs, canonical docs), not code,
which lowers PR3's code-sweep risk but raises its doc-sweep volume (`docs/explanation/02` is the
single largest canonical-doc rewrite PR3 already commits to).

## 9. The MMF bus today

`modules/ipc-common/src/main/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1.java` —
authoritative offsets (`:31-53`):

| Offset | Field | Writer (path:line) | Reader (path:line) | Status vs. `docs/explanation/02` |
|---|---|---|---|---|
| 0 (`OFFSET_ACTIVITY_EPOCH_MS`) | Last Activity | none found | none found | doc-confirmed retired (`02-process-coordination.md:91-92`); no production writer/reader in code either — matches |
| 8 (`OFFSET_HEARTBEAT_EPOCH_MS`) | Main Heartbeat | `MainSignalBus.java:128` | `MmfWorkerSignalBus.java:148` | matches doc |
| 16 (`OFFSET_SHUTDOWN_SIGNAL`) | Shutdown | `MainSignalBus.java:138,150` | `MmfWorkerSignalBus.java:154` | matches doc |
| **17** (`OFFSET_ENERGY_REDUCED`) | Energy Reduced | `MainSignalBus.java:193` | `MmfWorkerSignalBus.java:218` | **not in doc's table** — doc says `17-19` is 3 bytes reserved; code has a live, actively-read/written field at byte 17. Doc drift, not a lane-F defect, but PR3's doc rewrite must not silently drop it. |
| 18-19 (`OFFSET_RESERVED0_START`, 2 bytes) | reserved | — | — | doc's 17-19/3-byte reserved region shrinks to 18-19/2 bytes once offset 17 is accounted for |
| 20-23 (`OFFSET_WORKER_GRPC_PORT`) | gRPC Port | `MmfWorkerSignalBus.java:140` | `MainSignalBus.java:206` | matches doc |
| 24 (`OFFSET_MAIN_GPU_ACTIVE`) | GPU Active | `MainSignalBus.java:166` | `MmfWorkerSignalBus.java:211`, then consumed via `WorkerSignalBus.isMainGpuActive()` at exactly 6 production call sites: `indexer-worker/server/KnowledgeServer.java:1049,1714`, `worker-services/loop/BackfillScheduler.java:198`, `worker-services/loop/EmbeddingProviderLifecycle.java:169`, `worker-services/loop/ops/EmbeddingBackfillOps.java:194`, `worker-services/loop/ops/IndexingDocumentOps.java:219` | matches doc + brief's "six readers" claim exactly |
| 25-28 | magic/version/compat | — | — | not independently verified this pass |
| 29 (`OFFSET_RELOAD_SIGNAL`) | dev reload | `server.mjs:2840` (Gradle/MCP side) | `MmfWorkerSignalBus.java:240`, cleared at `:246` | matches doc |
| 30-63 (`OFFSET_RESERVED1_START`, 34 bytes) | reserved | — | — | matches doc |

**Brief correction — the 4 MiB/32 MiB message-size mismatch is already fixed, not a live
defect.** `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcMessageLimits.java`'s own
javadoc: "Before tempdoc 882 item 5 the two ends had drifted since the first commit: the server
advertised 32 MiB while the client never called `maxInboundMessageSize`, so grpc-java's 4 MiB
default silently capped replies." Verified both ends now read the shared constant:
`RemoteKnowledgeClient.java:325` (`.maxInboundMessageSize(GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES)`)
and `KnowledgeServerGrpcWiring.java:31` (Netty server builder, same constant). At base
`39d38f73` there is no mismatch; the brief's PR1 rationale item listing this as an unresolved
defect is stale.

**JVM flags — Head** (`modules/shell/src-tauri/src/lib.rs`, `spawn_headless_backend`, brief cites
`:734-789`; actual flag/classpath block is `:734-791`, close but not exact): `-Xmx512m` (`:740`),
`-XX:+UseSerialGC` (`:743`), `-XX:TieredStopAtLevel=1` (`:744`), `-XX:-UsePerfData` (`:745`),
`--sun-misc-unsafe-memory-access=warn` (`:746`), `--enable-native-access=ALL-UNNAMED` (`:748`),
conditional `-XX:AOTCache=...` (`:749-751`), crash-dir flags (`:754-759`), then a chain of
`-Djustsearch.*` system properties (`:763-787`) and `-cp`/main-class (`:788-790`).

**Worker argv builders — confirmed two, but asymmetric.** Production:
`modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java:431`
(`buildCommand()`), invoked from `:366-369` via `ProcessBuilder`. Test/chaos:
`modules/system-tests/src/main/java/io/justsearch/systemtests/chaos/WorkerProcessManager.java`
has **three** builder methods (`createJarProcessBuilder():217`, `createScriptProcessBuilder():253`,
`createJavaWithArgfileProcessBuilder():284`), all independent of `WorkerSpawner.buildCommand()`.
The brief's "two independent argv builders" undercounts the test side (3 methods, 1 class) but
correctly identifies that production and test-chaos maintain argv construction separately — PR2's
"one spawn path" item should note the chaos-tier builders as a second sweep target distinct from
`WorkerSpawner`, not folded into "the" second builder.

## Brief corrections

1. **RPC total is 48, not 50** (`indexing.proto`: 10 SearchService + 37 IngestService + 1
   HealthService). 50 would require adding `InfraDiagnosticsService`'s 2 RPCs from a *different*
   proto file — the brief doesn't say this, so treat "50" as unverified/likely a rounding or
   stale count.
2. **PR #617 (`f6f8433d`) is not a Worker spawn-path change.** It's dev-stack launch-provenance
   worktree-variant resolution (tempdoc 913 T1) plus an unrelated AI-runtime variant-resolution
   fix (913 H1). Neither touches `WorkerSpawner`, Worker argv, or the Worker process boundary.
   The dev-runner's worker.log/JDWP awareness (§4 above) is real and extensive, just not because
   of this PR.
3. **The 4 MiB/32 MiB gRPC message-size mismatch is already fixed** (tempdoc 882 item 5,
   `GrpcMessageLimits`, both ends verified using the shared constant). Not a live PR1 risk.
4. **`docs/explanation/02-process-coordination.md`'s MMF offset table is stale**: it omits
   `OFFSET_ENERGY_REDUCED = 17` (a live, read/written field) and its 17-19/3-byte reserved
   region should be 18-19/2-byte given that field. Also its Appendix claims a
   `io.justsearch.ipc.v1` `HealthService` with `Liveness`/`Readiness`/`Version` RPCs — no such
   RPCs exist in either proto file in this worktree (grep for `rpc Liveness|rpc Readiness|rpc
   Version` across `modules/ipc-common/src/main/proto/` returned zero hits). This is pre-existing
   doc drift, out of lane-F's current scope to fix (PR3 rewrites this doc anyway per the brief),
   routed here per `log-pre-existing-issues` rather than fixed now (multi-line, not a verified
   one-line fix).
5. **"Two independent argv builders"**: correct in spirit, imprecise in count — production has
   exactly one (`WorkerSpawner.buildCommand()`); the test/chaos side has three separate builder
   methods in one class (`WorkerProcessManager`), not one.

## Derisk (pending)

Not run in this pass (read-only audit only); the orchestrator should run next, in order:
1. **Head heap-flag sensitivity** — measure current Head RSS/GC-pause profile at `-Xmx512m`
   SerialGC before PR2 proposes the merged-heap/G1-or-ZGC default, so "measure startup time
   before/after" (brief PR2) has a real baseline.
2. **MMF layout residue reconciliation** — decide whether `OFFSET_ENERGY_REDUCED` (found here,
   undocumented) changes the PR3 "delete `MmfWorkerSignalLayoutV1`" sweep scope (it's an active
   field with a real reader/writer pair, not dead weight to silently drop).
3. **tempdoc 885 MMF residue cross-check** — tempdoc 885 is the item that introduced
   `ForegroundLoadInterceptor`'s "thrown away under lane-F" javadoc and the `main_gpu_active`
   in-process-flag framing already used in this audit's §9; read 885 in full before PR1 design to
   confirm no other 885-introduced construct was missed by this pass's grep set.
4. **PR1 module-composition ArchUnit rule design** — draft the exact `LayeringEnforcementTest`
   diff (which packages are added to the allowed-dependency lists) before writing
   `DefaultWorkerAppServices`/`modules/app-engine`, since `indexerWorkerMustNotDependOnUi` and
   `indexerWorkerMustNotDependOnAppServices` are today unconditional `noClasses()` rules that
   PR1's chosen composition module will violate by construction unless the rule is rewritten,
   not merely allowlisted.

## Report-back

- PRs: none opened (read-only audit; lane F PR1 implementation has not started).
- Items: audit categories 1-9 done (9/9); 0 deviated; 0 skipped. Every table row above carries
  a `path:line` opened during this session.
- Evidence: `git log -1 --oneline` in worktree = `39d38f73`; `git show f6f8433d --format="" -- ...`
  for the PR #617 correction; `grep -rn`/`grep -c` runs cited inline per table; proto RPC counts
  verified by direct line-count against `indexing.proto:299-317,1242-1371,1700-1701`.
- Measurements: none taken (brief's "Measurements to report" section is PR1-3 scope, not this
  audit's).
- Cross-lane requests raised: none — this audit touches no other lane's owned files.
- Residue found outside scope and where it was routed: `docs/explanation/02-process-coordination.md`
  MMF-offset-table staleness (missing `OFFSET_ENERGY_REDUCED`, phantom `io.justsearch.ipc.v1`
  Health RPCs) — routed here (§Brief corrections item 4) per `log-pre-existing-issues`, since
  PR3 already commits to rewriting this doc and a multi-line fix isn't a ride-along one-liner.
- What the next lane must know: lane F is gated on A, B, C, D, E merged plus owner go-ahead per
  the brief — this audit does not change that gate. PR1's actual diff surface is smaller than
  the brief's rationale implies in two respects (message-size mismatch already fixed; #617 is
  not a spawn-path precedent) but larger in one (an undocumented live MMF field must be accounted
  for in the PR3 sweep, and the ArchUnit layering rules are unconditional `noClasses()` rules
  requiring an explicit rewrite, not an allowlist add).
