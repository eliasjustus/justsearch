# Lane F — Merge Head and Worker into one Engine JVM (brief v2, refreshed 2026-09-06)

Supersedes `lane-F-engine-merge.md` (v1, 2026-09-01). Folds in tempdoc 917's audit corrections
1–8 and its four derisk results (`917 §Derisk results`). Read `00-program-overview.md` first.
**Start only after the owner explicitly confirms the merge decision.** Lanes A–E are merged on
`main` (lane D close-out: tempdoc 931; C1 quantization decided against, F-060). Worktree name:
`lane-F`. Load `/module-arch`, `/dev-stack`, `/installer`. Three PRs; stop for a go-ahead after
each.

## Why (unchanged in substance, corrected in detail)

ADR-0001 split the app into a Head JVM (UI host and API gateway) and a Worker JVM (Lucene, Tika,
ONNX) because "the UI process would hold Lucene locks on Windows". The UI process is the Tauri
shell, not the Head JVM; the premise never applied to the split it justified. The remaining
reasons were crash isolation from native parsers and GC isolation. Since then:
- Lucene 10 unmaps via Panama on `close()`, so a single JVM can close a Directory to migrate or
  delete it.
- Lane C moved native-parser isolation to a persistent extraction process pool (kept out of
  process by this lane; ADR-0048 probes survive unchanged).
- The Head is no longer thin: it hosts the agent loop, conversation engine, RAG assembly, MCP
  server and 13 SSE streams, yet runs with `-Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`
  (`modules/shell/src-tauri/src/lib.rs:740-744`). Measured 2026-09-06 (917 §Derisk results 1):
  the 512 MB heap is ≥ 85 % empty at every phase (live 13–68 MB), the working set is 377 MB idle
  and 436 MB under search + agent load, all of it non-heap, and every full GC in a 15-minute run
  was a metaspace or code-cache threshold, not heap pressure. `TieredStopAtLevel=1` is applied
  even when the AOT cache is present (`lib.rs:744,749-751`), which the dev runner deliberately
  avoids (`dev-runner.cjs:731`).
- The boundary is where the least-churned 2025-vintage code lives: a 64-byte MMF bus (nine
  declared fields, one of them undocumented until 917), a suicide pact with day-one constants, port
  handoff by polling, a config-snapshot precedence tier (`ORDINAL_WORKER_SNAPSHOT = 450`), one
  production argv builder plus three test-side builders in `WorkerProcessManager`, a deadline
  multiplier table, and 48 RPCs in `indexing.proto` (10 Search + 37 Ingest + 1 Health; the v1
  "50" and the "4 MiB / 32 MiB mismatch" are retired — the mismatch was fixed by tempdoc 882 via
  `GrpcMessageLimits`).
- Tauri already supervises the Head and restarts it (`lib.rs` `spawn_headless_backend`,
  `watch_manifest`); the webview survives a backend restart and every state is durable in Lucene
  and SQLite. An ORT/CUDA native fault in the merged process becomes a Tauri-supervised restart
  with the encoders reloading (~40 s), the accepted cost.

Target: one `Engine` JVM behind Tauri; `llama-server.exe` stays a separate native process; the
extraction pool stays separate.

## Consumer audit (done — tempdoc 917 §1–§9; this is PR 3's sweep list)

`RemoteKnowledgeClient` (concrete final class implementing `SearchPort` + `IndexingService`, one
construction site `KnowledgeServerBootstrap.java:261`, ~27 callers, the only production
`ManagedChannel` owner); `GrpcSearchService` / `GrpcIngestService` / `GrpcHealthService` in
`worker-services` wrapped by `Delegating*Service` for hot reload; two server streams
(`SubscribeIndexingJobs`, `ScanRoot`); `dev-runner.cjs` worker.log rotation + JDWP targeting;
`server.mjs:2840` writing MMF byte 29 by literal; `WorkerProcessManager` + 20 chaos/system tests;
jseval's `worker.log` knowledge; ADR probes `adr-0001-lucene-owners-pinned`,
`adr-0002-mmf-layout-pinned`, `adr-0002-mmf-constants-pinned`, `adr-0002-grpc-present`;
`LayeringEnforcementTest`, `BoundaryRulesTest`, `IndexWriterOwnershipTest`. Residue grep table:
917 §8.

## PR 1 — in-process transport behind a flag

- Client seam: extract the interface `RemoteKnowledgeClient` satisfies (today `SearchPort` +
  `IndexingService`; `IndexingController` is already typed on the interface and is the model).
  Add one constructor that takes a `ManagedChannel` supplier — port discovery through
  `MainSignalBus` is baked into the existing constructors and must become injectable.
- Composition (917 §Derisk results 4): new module **`modules/app-engine`**, package
  `io.justsearch.app.engine`, depending on `app-services`, `worker-services`, `worker-core`,
  `core`, `app-api`, `configuration`, `telemetry`, `ipc-common`, `libs.grpc.inprocess`,
  `libs.grpc.stub`. It builds `DefaultWorkerAppServices` from an `InfraContext` (as
  `KnowledgeServer.newAppServices()` does, `KnowledgeServer.java:1104-1110`), registers the three
  `Grpc*Service` impls on a `grpc-inprocess` server, and hands the Head an in-process channel.
  `ui` gains `implementation(project(":modules:app-engine"))`; `app-launcher` gains
  `runtimeOnly(project(":modules:app-engine"))` (otherwise the ArchUnit rules never scan it);
  `dead-code-audit` lists it. Do **not** reuse `modules/ui` (every Worker service would sit on the
  request-handler module and the new composition rule becomes unwritable). Keep the proto DTOs as
  the internal parameter/result types for now.
- Flag `justsearch.engine.mode = split | single`, default `split` in PR 1, read in `configuration`
  or `app-engine` — never in `app-services` (a `worker-services → app-services` read would create
  the only cycle in the graph). `HeadlessApp` picks `KnowledgeServerBootstrap` (split) or the
  in-process engine (single). Both modes must pass the full suite.
- ArchUnit: keep `indexerWorkerMustNotDependOnUi` / `indexerWorkerMustNotDependOnAppServices`
  predicates, reword their premise (no longer "separate process"); add rule 6b — no class outside
  `io.justsearch.app.engine..`, `io.justsearch.indexerworker..`, `io.justsearch.adapters..` may
  depend on `io.justsearch.indexerworker.{server,services,loop}..` (green today; it is the
  retargeted invariant #1 pin). `IndexWriterOwnershipTest` stays at two owner packages; only its
  comment changes. Acceptance: inject an `app.engine → ui` edge locally, watch
  `onlyAppLauncherMayDependOnUi` go red, revert.
- Streams: `SubscribeIndexingJobs` / `ScanRoot` stay server-streaming over the in-process channel
  in PR 1 (the SSE fan-out sits behind `RemoteIndexingJobsBridge` and `ScanProgressRegistry`, so
  no rewire); reactive in-process streams are PR 3 cleanup.
- Pacing and power (917 §Derisk results 2–3): `ForegroundLoad` / `IndexingPacing` survive as they
  are; `ForegroundLoadInterceptor` is replaced by direct increments at the search entry points in
  `single` mode. **Re-home `main_gpu_active` and `energy_reduced` together** as one in-process
  power/GPU gauge: the Head's `AtomicReference<EnergyState>` in `WorkerSpawner` (polled every
  15 s from `GetSystemPowerStatus`) already feeds `ServicePhase` and `VduOfflineTriggerSampler`
  in-process; the Worker-side `shouldYieldGpuBackfill()` readers (`EmbeddingBackfillOps`,
  `BgeM3BackfillOps`, `BackfillScheduler`, `GpuArbiter`) read that gauge directly. Keep the
  `justsearch.power.force_energy_state` override. Note `NrtOnDemandPolicy` is a second
  `ForegroundLoad` consumer across the `adapters-lucene` seam.
- Decide (record in the tempdoc): does the `FetchDocuments` byte-budget pager
  (`BoundedDocumentFetch`, `GplJobCoordinator`, `RemoteDocumentService`) survive once there is
  no wire ceiling, or is it retired with the channel? 885 deferred the proto-side fix to this lane.
- `single` mode is `hotReload:false` in PR 1: `DevReloadManager` is instantiated only by
  `KnowledgeServer` and triggered by MMF byte 29, and `dev-runner.cjs` targets JDWP at the Worker
  JVM. Say so in the run record rather than let `reload` no-op.
- Gates: `app-engine` must not import `SearchTrace` or `IndexingJobView` (the execution- and
  operation-surface registers auto-scan `modules/`).

## PR 2 — flip the default, one spawn path

- `lib.rs`: spawn the engine with one argv. Merge the Worker's flags (AOT cache,
  `-XX:+UseCompactObjectHeaders`, crash-dir flags, `--enable-native-access`). Heap: configurable,
  default sized from the **Worker's** footprint (≈ 4 GB working set measured idle), not "Head +
  Worker heaps" — the Head's heap is empty. Drop `-XX:TieredStopAtLevel=1` (also fixes the
  unconditional C1-only + AOT combination, correction 6) and add `-XX:MetaspaceSize=128m`: the
  measured full GCs were `Metadata GC Threshold` (startup) and `CodeCache GC Threshold`
  (48 MiB C1-only cache). G1 or generational ZGC instead of SerialGC. Measure startup before and
  after — baseline (dev path, warm, `--skip-build`): HTTP + head READY 3.1 s, worker READY 8.0 s;
  measure the Tauri + AOT path in this PR since the dev machine has no dev AOT cache.
- `scripts/dev/dev-runner.cjs` and the dev MCP `start/stop/reload` follow: one JDWP target,
  `reload` re-enabled for `single` mode, `worker.log` rotation retired or relabelled.
- One AOT cache build instead of two (`modules/ui/build.gradle.kts` AOT tasks).
- Config: delete `ORDINAL_WORKER_SNAPSHOT` and the snapshot file; `WORKER_FORWARDED_PROPS` and
  the `JUSTSEARCH_*` env blanket-forwarding disappear with `WorkerSpawner`;
  `justsearch.indexing.foreground_duty_pct` becomes a single read
  (`ForegroundPacingConfigForwardingTest` retires with the forwarding path).
- Logging: one log stream; keep `worker.log` as a symlink or a labelled logger for tooling that
  tails it (jseval `backend.py`, `ops.py`), then sweep the tooling.
- Crash policy: verify Tauri's restart-with-backoff on an injected native crash — retarget the
  chaos crash injector (`WorkerProcessManager`, 20 consumers) — and that the readiness UI shows the
  restart state.

## PR 3 — delete the split mode and sweep

Retire with a sweep (grep every name across code, config, gates, baselines, docs; delete or
label every hit in the same PR): `WorkerSpawner` (production argv builder) and the three
`WorkerProcessManager` builders, `MainSignalBus`, `MmfWorkerSignalBus`, `MmfWorkerSignalLayoutV1`
(**precondition: `main_gpu_active` and `energy_reduced` re-homed in PR 1**; the dead activity byte
goes with its unpopulated wire twin `CoreStatus.signal_bus_activity_ts`, `indexing.proto:677`, in
this lane's one wire change), `WorkerLivenessDecision`, `SupervisionPolicy`, the suicide-pact and
heartbeat constants, port discovery, `KnowledgeServerGrpcWiring` and the gRPC server (unless a
consumer outside the JVM still needs it; the runtime contract is HTTP/MCP), `ForegroundLoadInterceptor`,
the retry service config, the deadline categories, `IndexerWorker` main, the chaos process manager,
`docs/explanation/02-process-coordination.md` (rewrite as the engine lifecycle doc),
`01-system-overview.md`, `19-module-architecture.md` (layer tables, "Worker isolation" wording),
`docs/reference/architecture/module-deps.md` (regenerate), the dev-reload byte writer in
`server.mjs`, and every grep hit for `MMF`, `heartbeat`, `suicide`, `breath`, `OFFSET_`,
`WorkerSpawner`, `worker snapshot`, `ORDINAL_WORKER_SNAPSHOT`, `WORKER_FORWARDED_PROPS`,
`main_gpu_active`, `energy_reduced`. Keep `WindowsJobObject` for the extraction pool children.
- Invariants: root `CLAUDE.md` #1 becomes "Only the index runtime (`adapters-lucene`,
  `worker-services`) touches Lucene; the API layer goes through the engine client interface", with
  ArchUnit rule 6b as its pin (Lucene is on the engine classpath by construction; the process half
  of the old invariant is gone). ADR-0001 and ADR-0002 are superseded by a new ADR "Single engine
  process" with probes (lane B's format): exactly one JVM spawn site in `lib.rs`, no
  `ManagedChannel` in production code, extraction runs out of process. Amend the four ADR probes
  through the register's procedure; never delete a failing probe.
- Installer: `check-update-preserves-models`, the NSIS hooks and the bundled runtime image change
  (`/installer` skill); build via `gh workflow run build-installer.yml` and smoke the installed app.
- Gates: `--gate execution-surface`, `--gate operation-surface`, `check-live-witness`,
  `check-runtime-manifest-closure` (the manifest's process list changes), `check-dev-mcp-doc-sync`,
  `check-ui-step-coverage` if readiness states changed, `check-language-agnostic-analysis`
  untouched.

## Measurements to report

Startup to ready (cold and warm; baseline above), RSS at idle and during indexing (two JVMs vs
one; baseline: Head 377 / 423 MB, Worker ≈ 4.07 GB), search p95 during bulk indexing (baseline
485 ms in TEXT mode during enrichment; the hybrid figure needs a paired run with the agent idle),
API p95 for the agent loop (C1 vs C2), crash-to-recovered time on an injected native fault,
installer size. Baseline artifacts: `917 §Derisk results 1`.

## Files this lane owns

Everything in the Head/Worker boundary listed above, `modules/shell/src-tauri/src/lib.rs` spawn
function, `scripts/dev/dev-runner.cjs`, the dev MCP server lifecycle tools, module wiring in
`settings.gradle.kts` and `build.gradle.kts` files, the new `modules/app-engine`,
`docs/explanation/01`, `02`, `19`, root `CLAUDE.md` invariant #1 and the Architecture table.
Coordinate any catalog or search change through the owner.

## Acceptance

Each PR: full suite in both modes (PR 1), full suite plus chaos tier plus installer smoke (PR 2,
PR 3), the gates above, live jseval pipeline run, `ai_activate` and one full RAG answer end-to-end
in single mode, independent review with live verification, the ArchUnit falsification step (PR 1).
Stop after each PR for a merge go-ahead. Report-back includes the measurement table and the final
residue grep (must be empty).
