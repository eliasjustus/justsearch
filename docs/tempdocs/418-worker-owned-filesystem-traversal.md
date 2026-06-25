---
title: Worker-Owned Filesystem Traversal (Head Walker Replacement)
type: tempdocs
status: open
---

# 418 — Worker-Owned Filesystem Traversal (Head Walker Replacement)

## Status

**IN PROGRESS — Phase A + Phase B + Phase B Hardening landed (2026-04-25).**
Created 2026-04-25 as a follow-up to tempdoc 410 V1 + Phase 2.1. Tempdoc
410's Phase 2.3 user decision was to take the **full Worker-owned
traversal** path rather than the smaller "pragmatic skip-policy
unification." That decision crosses module boundaries and warrants its
own design tempdoc before code lands.

> **Renumbered post-merge (was 412):** the 412 number was claimed
> independently by `docs/tempdocs/412-observability-pattern-adoption.md`
> (tempdoc 417's coordinated 412–417 sequence for the 406 follow-up
> program) before this tempdoc merged to main. This tempdoc renumbered
> to 418 (next free after that sequence) since the 412–417 numbering
> is more deeply embedded in the 406 program's cross-references. Pre-
> merge commit messages on the 410 worktree (Slice A–E + G + Phase
> A/B/B-H + Phase C sub-commit A) still reference "tempdoc 412" by
> number; readers disambiguating those should match by commit date /
> hash to this file. The "412" semantics in those commit messages
> always meant Worker-Owned Filesystem Traversal (now 418), never the
> 406 program's InferenceLifecycleManager work.

### Phase A — Worker-side scaffolding (DONE, commit `0d546e86e`)

Additive only — no Head-side change, no behavior change for existing
call sites. Three new RPCs (`ScanRoot` server-streaming, `WatchRoot`,
`UnwatchRoot`) + 7 messages in `indexing.proto`. `WorkerScanOps`
implements the walk + admission + enqueue flow with caller-supplied
exclude globs and per-100-file `ScanRootProgress` emissions.
`RootWatcherRegistry` tracks active subscriptions (Methvin watcher dep
move deferred to Phase B). `GrpcIngestService` wires the RPCs;
`DelegatingIngestService` forwards. 8 new test cases across
`WorkerScanOpsTest` and `RootWatcherRegistryTest`.

### Phase B — Head-side migration (DONE, commits `9d46d2807` → `b1fe1aa40`)

Four sub-slices, each independently shipped:

- **B1** (`9d46d2807`): server-streaming `RemoteKnowledgeClient.scanRoot`
  + `KnowledgeHttpApiAdapter.scanRoot`. `KnowledgeSearchController.handleIngest`
  no longer expands directories Head-side. `ScanRootStreamingContractTest`
  pins the wire contract.
- **B2** (`ced03dc34`): `IngestTool` (agent) and `RootLifecycleOps.walkAndSubmit`
  migrated. `IngestTool` keeps a local-fallback `ScanRootCallback` for
  back-compat tests. `RootLifecycleOps` preserves backpressure between
  scan-progress events; terminal `ScanRootProgress` drives
  `markIndexed` / `markWalkFailed` state. `ExcludeMatcher.patterns()`
  surfaces the cleaned globs for forwarding to `ScanRoot.exclude_globs`.
- **B3** (`b1fe1aa40`): file watcher migrated to Worker via
  new `WorkerMethvinWatcher` + Methvin dep added to `worker-services`.
  `RootWatcherRegistry` gains real event delivery; both watchers run
  during the soak window. Queue's `INSERT OR REPLACE` coalesces
  duplicate enqueues — no Head-side dedup needed.
- **B4**: integration test sweep + Phase A cleanup (this commit).

Tempdoc 410 invariant 1 ("no raw path crosses into indexing") now holds
end-to-end, not just past the Worker boundary.

### Phase B Hardening (B-H, DONE, commits `c0b1e3ed0` → `e0d318622`)

Critical-analysis pass on the just-shipped Phase A/B surfaced 7 high-
severity defects + 4 medium polish items vs. the pre-tempdoc-412
behavior. The Worker-owned traversal was the right shape, but the
implementation diverged from `SyncDirectoryOps` in several silent ways
and lost two production-grade traits the Head-side walk had. B-H ships
the fixes as four independently reviewable sub-commits.

- **B-H.1** (`c0b1e3ed0`, refactor): extract `IngestionSkipPolicy`
  (worker-core) + `CloudPlaceholderRecorder` (worker-services). The
  skip rules used to live inside `WorkerIngestionAuthority` (package-
  private) and a duplicate `WALK_SKIP_DIRS` set inside
  `SyncDirectoryOps`; promoting them to worker-core gave Worker-owned
  scans the same admission filter. The cloud-placeholder ledger writer
  moved to a shared recorder. New `IngestionSkipPolicyTest` exercises
  the rules directly. Pre-existing quirk surfaced + logged: the
  `.gitignore` exemption is shadowed by the `name.contains(".git")`
  SKIP_PATTERN — preserved verbatim, recorded in `docs/observations.md`.
- **B-H.2** (`f6081e5e2`, defects A + D + E):
  - **A** — `WorkerScanOps` walks now apply
    `IngestionSkipPolicy.shouldSkip` at walk time, so `.pyc` /
    `~$Office.docx` / `.tmp` / `Thumbs.db` files are filed as
    `files_skipped` and never reach the queue.
  - **D** — `ScanRootProgress.current_directory` is the requested root
    verbatim, every nested directory is SHA-256 hex hashed. Privacy
    invariant of the ingestion ledger now applies to progress streams
    too. Proto comment rewritten to be unambiguous.
  - **E** — cloud-only placeholders route through
    `CloudPlaceholderRecorder.record(file)` so the file surfaces in the
    ingestion ledger as `DEFERRED_POLICY/CLOUD_PLACEHOLDER` instead of
    being silently skipped.
- **B-H.3** (`c09647233`, defects B + C):
  - **B** — backpressure moved Worker-side. `WorkerScanOps` polls
    `jobQueue.queueDepth` before each batch flush and pauses the scan
    thread until depth falls below LOW. Constants
    (HIGH=90k / LOW=70k / POLL=2s) are lifted verbatim from the prior
    Head-side `RootLifecycleOps` values.
  - **C** — cancellation moved Worker-side via
    `ServerCallStreamObserver.isCancelled()`. WorkerScanOps stops
    walking on the next file when cancellation fires and emits a
    terminal `ScanRootProgress` with `terminal_reason_code =
    CLIENT_CANCELLED`. First codebase use of
    `ServerCallStreamObserver` — establishes the pattern.
  - Head-side: `RootLifecycleOps.walkAndSubmit` callback collapsed to
    just observing `filesAdmitted`; the in-callback `awaitQueueBelowThreshold`
    + the `ScanCancelledException` machinery + the `queueDepthSupplier`
    constructor parameter are deleted. `CLIENT_CANCELLED` becomes another
    `markWalkFailed` reason.
- **B-H.4** (`e0d318622`, defects F + G + J + K):
  - **F** — `IndexingLoop.recordOutcomeSafely` catches `RuntimeException`
    instead of just `OutcomeWriteException`. The Phase 0.2 defer-policy
    guard in `SqliteJobQueue.markFailed` throws `IllegalArgumentException`
    on misroute; the prior catch let it crash the entire batch.
  - **G** — `WorkerMethvinWatcher.handleEvent`'s DELETE branch is no
    longer a no-op. Constructor takes `Consumer<String> deletePathSink`;
    `DefaultWorkerAppServices` wires it to
    `IndexingCoordinator.deleteByIdAndChunks`. Phase C can now safely
    delete the Head-side `WatcherEventOps` fallback without orphaning
    deletion events.
  - **J** — `ProcessExtractionSandbox.LimitedBytes` gains `length()`;
    success-path `error.bytes().length` no longer triggers a defensive
    clone of the underlying buffer.
  - **K** — `KnowledgeSearchController.handleIngest` wraps the
    accepted-count cast in `Math.toIntExact` so overflow surfaces as
    `ArithmeticException` (→ 500) instead of silent truncation in the
    JSON serializer.

Out-of-scope deferred (per B-H plan §"Out of scope"):
defect I (`BurstDetector` double-counting during the soak window — moot
after Phase C deletes the Head-side watcher), defect L (`ScanMode`
plumbed but not used — own slice), defect M (`TestDocumentBuilder` null
sourcePathHash — cosmetic), defect N (gRPC handler thread held by long
walks — measure first), defect O (circuit-breaker semantics for
streaming — unlikely at desktop scale).

### Phase C — cleanup

Deletes the now-unused Head-side walker code (`MethvinWatcherStrategy`,
`WatcherBootstrap`, `RootLifecycleOps.removeWatchedPath`'s
`watcherBootstrap.stopWatcher` call, `WatcherEventOps`,
`ExcludeGlobs` Head-side enforcement, the `app-indexing` Methvin
dependency declaration). Lands after Phase B has run in production for
at least one release cycle.

## Why this is a separate tempdoc

Tempdoc 410 invariant 1 says **"no raw path crosses into indexing."**
The V1 slice satisfies this past the Worker boundary
(`WorkerIngestionAuthority` admits, `IndexingLoop` consumes typed
`AdmittedFile`s). End-to-end satisfaction requires Head-side walkers
to stop being authoritative admission logic. Today, four Head-side
entry points still expand directories and submit raw `Path` strings:

```
UI POST /api/knowledge/ingest      → KnowledgeSearchController.handleIngest    (Files.walk)
Agent ingest_files tool            → IngestTool.execute                        (Files.walk)
Persisted-root startup / add root  → RootLifecycleOps.walkAndSubmit            (Files.walk)
File watcher CREATE/MODIFY         → WatcherEventOps.handleCreateOrModify      (single-path)
```

The change is multi-module (`ui` / `app-agent` / `app-services` /
`worker-services` / `app-indexing` / `ipc-common`), introduces a new
gRPC contract, moves the Methvin file-watcher dependency between
processes, and changes who owns watched-root persistence. This is not
a hardening pass — it is an architectural shift.

## Chosen direction

**Worker becomes the sole authority for filesystem traversal.** Head
sends "scan this root" intents to Worker over gRPC; Worker owns the
walk, the watcher, and the typed admission. Head's role for indexing
intent reduces to:

- **Persisted root configuration** — what roots the user has chosen.
- **Forwarding** ad-hoc ingest requests (`/api/knowledge/ingest`,
  agent `ingest_files`) as scoped scan intents.
- **Surfacing** the typed status / health back to UI.

The non-goals: Head does not stop reading files for non-indexing
purposes (settings YAML, model files, etc.). The migration is scoped
to **untrusted user-content traversal**.

## Current state map

| Entry point | Current Head behavior | Worker call today |
| --- | --- | --- |
| `POST /api/knowledge/ingest` | `Files.walk` + `ExcludeGlobs` filter, then `submitBatch(List<Path>)` | `IngestService.SubmitBatch` (already exists) |
| Agent `ingest_files` | `Files.walk` over indexed-root-relative paths, then `submitBatch` | same |
| `RootLifecycleOps.walkAndSubmit` | `Files.walk` after add-root or force-reindex, batched submits | same |
| `WatcherEventOps.handleCreateOrModify` | Single-path `submitBatch` from Methvin event | same |

All four ultimately call `IngestService.SubmitBatch(BatchRequest)`
which carries `repeated string paths`. The proto contract has no
notion of "scan this root" — only "ingest these specific paths."

## Design

### 1. New gRPC contract: `ScanRoot` + `WatchRoot`

Replace the four Head-side walkers with two new RPCs:

```proto
service IngestService {
  // Existing — preserved for ad-hoc per-file ingest (e.g., file-drop UX
  // that pre-resolves a small list).
  rpc SubmitBatch(BatchRequest) returns (BatchResponse);

  // NEW — Worker performs filesystem traversal under the given root
  // and admits each discovered path through WorkerIngestionAuthority.
  // Server-streaming so Head sees scan progress (file count, bytes
  // walked, ETA) without polling. Cancellation is gRPC-native.
  rpc ScanRoot(ScanRootRequest) returns (stream ScanRootProgress);

  // NEW — Worker subscribes to filesystem-change events under the
  // root and feeds them through the same admission path. Idempotent:
  // calling twice for the same root replaces the prior subscription.
  rpc WatchRoot(WatchRootRequest) returns (WatchRootResponse);
  rpc UnwatchRoot(UnwatchRootRequest) returns (UnwatchRootResponse);
}

message ScanRootRequest {
  string root_path = 1;          // absolute, normalized at admission
  string collection = 2;         // optional; null = default
  ScanMode mode = 3;             // INITIAL | RESCAN | FORCE_REINDEX
  repeated string exclude_globs = 4;  // operator overrides; default
                                      // is the WorkerIngestionAuthority skip policy
}

enum ScanMode {
  SCAN_MODE_INITIAL = 0;
  SCAN_MODE_RESCAN = 1;          // matches force=false today
  SCAN_MODE_FORCE_REINDEX = 2;   // matches force=true today
}

message ScanRootProgress {
  int64 files_walked = 1;
  int64 files_admitted = 2;
  int64 files_skipped = 3;       // SKIPPED_POLICY total
  int64 bytes_walked = 4;
  string current_directory = 5;  // hashed if outside the requested root
  bool complete = 6;
  string terminal_reason_code = 7;  // populated when complete=true and
                                    // not a clean finish
}

message WatchRootRequest {
  string root_path = 1;
  string collection = 2;
}

message WatchRootResponse {
  bool watching = 1;
  string error_message = 2;
}

message UnwatchRootRequest {
  string root_path = 1;
}

message UnwatchRootResponse {
  bool unwatched = 1;
}
```

`SubmitBatch` is **kept**, not deprecated. Use cases like "user dragged
3 files onto the app" still pre-resolve to a small list. The deprecated
behavior is "Head walks a directory then submits the expanded list."

### 2. Watcher placement: Worker spawns Methvin

Move the Methvin watcher dependency from `modules/app-indexing` to
`modules/worker-services`. Worker holds the file-watch threadpool and
the `WatchedDirectory` registry. Watcher events feed directly into
`JobQueue.enqueue` after `WorkerIngestionAuthority.admit`.

Implications:
- `modules/app-indexing` shrinks to "watcher event types" (now consumed
  by Worker not Head).
- Head loses its `WatcherBootstrap` and `MethvinWatcherStrategy`.
- Worker gains a `RootWatcherRegistry` paired with the `JobQueue`.

Why Worker (not Head):
- File events fire close to the typed admission boundary — no IPC hop
  per event.
- Head's `WatcherEventOps.handleCreateOrModify` is currently the only
  Head→Worker path that submits one path per event; moving it removes
  N gRPC calls per minute under heavy filesystem activity.
- Watcher restart on `RuntimeSession` swap (tempdoc 406 lifecycle)
  becomes natural — the watcher is a per-session resource.

### 3. Root persistence: Head still owns the user choice; Worker holds the runtime registry

Roots remain stored Head-side (UI / settings / persistence). Worker has
a runtime-only `WatchedRootRegistry` populated from Head at startup
via `WatchRoot` RPCs. On Worker restart, Head re-syncs by replaying
its persisted set.

Why split:
- The user's choice of roots is a settings concern — Head owns
  settings. Worker should not have a parallel persistence store.
- Worker restart should not lose the user's roots; replay from
  Head-side persistence is the recovery model.
- Mirrors the pre-410 split: Head decides "what to watch," Worker
  decides "how to admit."

### 4. Migration phases

**Phase A — proto + Worker scaffolding** (no Head behavior change)
- Add the three new RPCs + messages to `indexing.proto`.
- Implement `ScanRoot` and `WatchRoot` server-side in
  `GrpcIngestService` using the existing `SyncDirectoryOps` walk
  logic + Methvin watcher (move dep). New code, parallel to existing.
- Add `DelegatingIngestService` forwards.
- Tests: contract test for the new RPCs.

**Phase B — Head-side enumerator removal**
- Delete `Files.walk` from `KnowledgeSearchController.handleIngest`;
  call `ScanRoot` instead with the input root + extracted ExcludeGlobs.
- Same for agent `IngestTool`.
- Delete `RootLifecycleOps.walkAndSubmit`; replace with `ScanRoot` on
  add-root + reindex.
- Delete `WatcherEventOps`, `MethvinWatcherStrategy`, `WatcherBootstrap`
  Head-side; replace with `WatchRoot` calls at startup + on add-root.
- Delete `ExcludeGlobs` Head-side enforcement (Worker does it now).
  Keep the Head-side parsing for transmission to Worker.
- Tests: integration tests that the four entry points still work.

**Phase C — cleanup**
- Delete `modules/app-indexing/src/main/java/io/justsearch/app/indexing/watch/`.
- Reduce `modules/app-services/.../RootLifecycleOps` to a thin
  Head→Worker root-replay client.
- Deprecate (don't delete) `SubmitBatch`'s "ingest a directory list
  pre-expanded by Head" use case — keep the RPC for true per-file lists.

### 5. Backward compatibility

`SubmitBatch` stays. New deployments use `ScanRoot` for directories;
old call sites that pre-expand stay functional during the migration.
Cleanup phase removes the Head-side expanders, not the RPC.

The `jobs.db` schema is unchanged — every entry still uses
`enqueue(path, collection)` server-side.

The ledger schema (V6) is unchanged — Worker-side admission produces
the same typed outcomes.

## Open questions

1. **Cancellation of in-flight scans** — RESOLVED in B-H.3. Worker
   observes `ServerCallStreamObserver.isCancelled()` after each batch
   flush and emits a terminal `ScanRootProgress` with
   `terminal_reason_code = CLIENT_CANCELLED`; Head treats it as another
   `markWalkFailed` reason.

2. **Scan progress granularity** — current default is per-100-files.
   Adequate for the 5K–10K-file desktop workloads observed in eval.
   Revisit when a user-facing workload exceeds ~50K files per scan.

3. **Root validation timing** — current behavior validates
   synchronously: a non-directory root returns a terminal
   `ScanRootProgress` with `terminal_reason_code = ROOT_NOT_DIRECTORY`
   on the first `onNext`, and `WatchRoot` returns `watching=false` with
   an error message. Matches the prior Head-side behavior.

4. **Permissions** — both processes run as the same user on Windows;
   not exercised on Linux/macOS yet. Tracked for the eventual
   cross-platform soak.

5. **Per-root scan throttling** — out of scope for B-H; tracked under
   the larger SourceRoot capability work.

6. **Eval-mode short-circuit** — `ScanRoot` is now used for the
   `/api/knowledge/ingest` directory entry-point and works fine in eval;
   `SubmitBatch` still exists for true per-file lists. No special-case
   needed for eval.

7. **Agent ingest_files semantics** — kept Head-side resolution of
   relative paths against indexed roots (B2 design). The agent tool
   resolves against the user's mental model and forwards absolute roots
   to `ScanRoot`.

## Confidence

- **Direction**: high. User explicitly chose this scope.
- **Proto contract shape**: medium-high. Server-streaming
  `ScanRoot` is straightforward; `WatchRoot` is bidirectional in
  spirit but unidirectional in proto.
- **Watcher migration mechanics**: medium. Methvin's threadpool model
  is portable; the friction is module dependency rearrangement.
- **Migration safety**: medium. Phase A is additive (no Head-side
  change); Phase B is the cutover. Some integration tests will need
  updating.
- **Production rollout**: medium-low. The Worker watcher and event
  pipeline need real-environment validation before Phase C cleanup.

## Relationship to other tempdocs

- **Tempdoc 410** (Adversarial Ingestion Resilience): this tempdoc
  finishes invariant 1 ("no raw path crosses into indexing") that V1
  satisfied past the Worker boundary.
- **Tempdoc 406** (Lifecycle Refactor): the Worker watcher should
  follow the service-identity-with-single-shot-session pattern. New
  watcher per `RunningRuntime`; restart = swap.
- **Tempdoc 411** (Workflow Signal Governance): unrelated; just
  number-adjacent.
- **Future tempdoc for §1 SourceRoot**: this tempdoc defers the
  `SourceRoot` capability model. Roots remain identified by path
  string; the capability work is its own design pass.

## Non-goals

- This tempdoc does not introduce the SourceRoot capability model
  (tempdoc 410 §1) — that needs its own design.
- This tempdoc does not change the queue schema, ledger schema, or
  outcome taxonomy.
- This tempdoc does not redesign the agent `ingest_files` semantics
  beyond moving the walk to Worker (or keeping it Head-side per
  Open Question 7).
- This tempdoc does not address Head-side reads of non-content files
  (settings, models, packs).

## Implementation log

### Phase A (commit `0d546e86e`, 2026-04-25)

Landed:
1. Three new RPCs + 7 messages in `indexing.proto`.
2. `WorkerScanOps` walks a single root, applies `SyncDirectoryOps`'
   skip-dir + cloud-placeholder rules + caller-supplied glob excludes,
   enqueues admitted regular files via `JobQueue`, and emits
   `ScanRootProgress` every 100 files plus once at completion.
3. `RootWatcherRegistry` holds active subscriptions with idempotent
   `watch`/`unwatch` and typed validation results (Methvin dep move
   deferred to Phase B per the staging note in §2 above).
4. `GrpcIngestService.scanRoot/watchRoot/unwatchRoot` wire the RPCs.
   `INVALID_ARGUMENT` for blank roots, `IO_ERROR` terminal-reason-code
   on walk failure, `WatchResult` carries typed `not a directory` /
   `invalid path` errors.
5. `DelegatingIngestService` forwards.
6. 8 new test cases: `WorkerScanOpsTest` (basic walk + enqueue,
   skip-dir pruning, caller exclude globs, ROOT_NOT_DIRECTORY
   terminal, in-flight progress emissions) and
   `RootWatcherRegistryTest` (idempotent watch, non-directory
   rejection, unwatch lifecycle).

Phase A was completely additive — Head walkers continued to work
unchanged. The contract is now stable and Phase B can run any time.

### Phase B (DONE — 4 sub-slices)

Plan file: `C:\Users\<user>\.claude\plans\412-phase-b.md`.

- **B1** `feat(412-B1)` (`9d46d2807`): Worker-side `ScanRoot` powers
  `POST /api/knowledge/ingest`. Server-streaming
  `RemoteKnowledgeClient.scanRoot` + `KnowledgeHttpApiAdapter.scanRoot`
  + `ScanRootStreamingContractTest` (3 cases).
- **B2** `feat(412-B2)` (`ced03dc34`): agent `IngestTool` and
  persisted-root `RootLifecycleOps.walkAndSubmit` dispatch via
  `ScanRoot`. Backpressure stays Head-side between progress events;
  terminal progress drives state persistence. `ExcludeMatcher` exposes
  cleaned globs.
- **B3** `feat(412-B3)` (`b1fe1aa40`): file watcher migrates to
  Worker. `WorkerMethvinWatcher` (real Methvin against per-root
  `DirectoryWatcher`s) + `RootWatcherRegistry` real event delivery
  + `DefaultWorkerAppServices` constructs the watcher + Head's
  `RootLifecycleOps` registers/unregisters Worker watchers via new
  `RemoteKnowledgeClient.watchRoot/unwatchRoot`. Both watchers run
  during the soak window; SQLite `INSERT OR REPLACE` coalesces
  duplicate enqueues. Lockfiles regenerated.
- **B4**: this commit — tempdoc status update + Javadoc cleanup +
  integration coverage check.

### Phase B Hardening (DONE — 4 sub-slices, 2026-04-25)

Plan file: `C:\Users\<user>\.claude\plans\412-phase-b-hardening.md`.

- **B-H.1** `refactor(412-BH1)` (`c0b1e3ed0`): pure refactor — extract
  `IngestionSkipPolicy` (worker-core) + `CloudPlaceholderRecorder`
  (worker-services). `WorkerIngestionAuthority` and `SyncDirectoryOps`
  delegate. New `IngestionSkipPolicyTest`. The pre-existing `.gitignore`
  vs. `.git`-contains shadowing quirk is preserved verbatim and logged
  in `docs/observations.md`.
- **B-H.2** `fix(412-BH2)` (`f6081e5e2`): defects A + D + E in
  `WorkerScanOps` — walk-time skip rules, SHA-256-hashed
  `current_directory` for nested paths, cloud placeholders route
  through the recorder. Three new `WorkerScanOpsTest` cases.
  `ScanRootProgress.current_directory` proto comment rewritten.
- **B-H.3** `fix(412-BH3)` (`c09647233`): defects B + C — backpressure
  + cancellation moved to Worker via
  `ServerCallStreamObserver.isCancelled()`. `RootLifecycleOps` drops
  `awaitQueueBelowThreshold`, `ScanCancelledException`, and the
  `queueDepthSupplier` constructor parameter. Two new
  `WorkerScanOpsTest` cases (backpressure waiter invocation +
  CLIENT_CANCELLED terminal).
- **B-H.4** `fix(412-BH4)` (`e0d318622`): defects F + G + J + K —
  `recordOutcomeSafely` catches `RuntimeException` (defer-policy
  misroute survives); `WorkerMethvinWatcher` DELETE forwards to
  `IndexingCoordinator.deleteByIdAndChunks`;
  `ProcessExtractionSandbox.LimitedBytes#length()` skips defensive
  clone; `KnowledgeSearchController` uses `Math.toIntExact`. Two new
  test cases (`WorkerMethvinWatcherTest.deliversDeleteEventToSink` +
  `IndexingLoopTest.misroutedOutcomeWriteDoesNotCrashBatch`).

After B-H, Phase C is unblocked: the Worker now owns DELETE handling
end to end, so deleting the Head-side `WatcherEventOps` no longer
orphans deletion events.

### Phase C sub-commit A — back-compat shim deletion (DONE, commit `41f626906` succeeded by Slice D)

Deleted the internal-only back-compat shims that have zero operator-
visible surface and were safe to remove pre-soak. Splits Phase C in
two so the load-bearing wiring deletions (sub-commit B) wait for one
release cycle of soak per the original tempdoc constraint.

- Deleted the 2-arg `WorkerMethvinWatcher(jobQueue, telemetry)`
  constructor (the no-op-DELETE shim from B-H.4). Production callers
  cannot accidentally drop DELETE events anymore.
- Deleted `IngestTool.defaultLocalScanCallback` and the 1- and 2-arg
  `IngestTool` constructors that defaulted to it. The local-walk
  fallback moved to a private helper inside `IngestToolTest` so the
  test semantics are preserved without leaking the back-compat path
  into production.
- Migrated `WorkerMethvinWatcherTest` and `IngestToolTest` to the
  3-arg form via small test-helper indirection (`NOOP_DELETE_SINK` /
  `toolWithLocalScan`).

### Phase C sub-commit B — production wiring deletion (deferred — post-soak)

Cleanup of the load-bearing Head-side walker code lands after Phase B
has run in production for at least one release cycle. Concrete
deliverables when sub-commit B starts:

- Delete `modules/app-indexing/src/main/java/io/justsearch/app/indexing/watch/MethvinWatcherStrategy.java`.
- Delete `modules/app-indexing/src/main/java/io/justsearch/app/indexing/WatcherBootstrap.java`.
- Delete `modules/app-services/src/main/java/io/justsearch/app/services/worker/WatcherEventOps.java`
  (now safely deletable post-B-H.4 — Worker owns DELETE).
- Delete `modules/app-services/src/main/java/io/justsearch/app/services/worker/WatcherEventCallbacks.java`
  (interface used only by `WatcherEventOps`).
- Remove `setWatcherBootstrap` and the `watcherBootstrap.stopWatcher` /
  `startWatcher` calls from `RootLifecycleOps`.
- Remove the `WatcherEventOps` constructor parameter + field from
  `RootLifecycleOps` and the `new WatcherEventOps(...)` construction
  + `setWatcherBootstrap` public method from `RemoteKnowledgeClient`.
- Remove the `watcherBootstrap` field + the `new WatcherBootstrap(...)`
  / `client.setWatcherBootstrap(...)` / `watcherBootstrap.close()`
  block from `KnowledgeServerBootstrap`.
- Remove `implementation(libs.directory.watcher)` from
  `modules/app-indexing/build.gradle.kts`.
- Remove the Phase B "soak window" comment in
  `RootLifecycleOps.startWatcherIfAvailable`.
