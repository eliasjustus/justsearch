---
title: "Residue-2 live validation: five backend/dev-tooling defects a real running stack found that the merged PRs' green suites could not"
type: tempdocs
status: "IMPLEMENTED (2026-09-02) — D1, D5, D6, H1, T1, T2 landed with tests; one item routed, not fixed (see Open items)"
created: 2026-09-02
updated: 2026-09-02
lane: resid3-backend
model: opus (implementation)
parent: 909-wave1-residue-durable-stores
related:
  - 911-wave1-residue-ui-contract-and-a11y   # #614 typed IndexingController onto IndexingJobView (D1); §F owns the scanId gap
  - 912-wave1-residue-worker-watcher-and-commit-floor
  - 910-wave1-residue-governance-kernel      # registered the fe-indexing-job-state-vocabulary row D1's row is modelled on
  - 606-dev-stack-ownership-and-provenance   # Piece 2 (provenance mismatch) and Piece 4 (distFrom) — T1 is their interaction
  - 656-five-minute-agent-runtime-onramp     # dev-time CPU baseline removal; the deactivate item's origin
  - 832-agent-tool-single-construction-authority  # AgentToolFactory.assemble as the one composition site (D5)
  - 868-agent-tool-capabilities              # :610 already recorded H1 with a 1.1 GB copy as the workaround
---

# 913 — Residue-2 live validation: backend and dev-tooling fixes

A live product validation of the residue-2 work (tempdocs 909-912, PRs #611-#615) ran the real
stack with the real UI and found five defects; the UI lane added a sixth (D6) mid-lane. Every one
of them had a green unit suite over it. That is the through-line of this tempdoc: five of the six
are *wiring* defects — a value captured before the thing it names exists, a register row nobody
added, a gate nothing runs, an allowlist that lost an entry, an authority nobody on that path
calls — and unit tests that construct their collaborators directly cannot see any of them. In two
cases (D5, D6) the correct component existed, was correct, and was fully unit-tested; the defect
was that the code path in question never reached it.

## §A — The validation findings, verbatim

The following is the validator's report as received, unedited. §B corrects it where the source
disagreed.

> **D1 (S2, main is red):** `node scripts/governance/run.mjs --gate operation-surface --mode gate`
> fails: `IndexingController.java` references the canonical `IndexingJobView` (real import at
> `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:11`, uses at :675, :742,
> :931-934, introduced by #614) but `governance/operation-surfaces.v1.json` has no row for it. Add
> the row as a PROJECTION, run the gate and `--gate register-guard-resolution`, both must pass.
> Also explain why #614's PR CI and merge-group did not catch it (which CI job runs this gate, and
> on what inputs) and, if the gate is simply not in CI, add it to the CI kernel step that runs the
> other input-free gates.
>
> **D5 (S3 but it kills the product's undo affordance):** `GET /api/chat/agent/history` returns
> `{"batches":[]}` while `<dataDir>/file-operations/*.json` journal batches exist (v2 rows with
> digests), before and after a restart. `AgentRunQueryService.operationHistory`
> (`AgentRunQueryService.java:103-111`) returns `List.of()` when `fileOperationLog == null`. Find
> why the log is null in the running Head (HeadAssembly wiring order? a different FileOperationLog
> instance than the one the tools write to? a data-dir path mismatch?) and fix the root cause so
> the endpoint lists the batches the UI's undo drill-down needs
> (`modules/ui-web/src/api/domains/agent.ts:129-138` calls `/api/chat/agent/undo`; find the history
> consumer too). Add a test at the wiring level.
>
> **H1 (dev tooling, S2 for agents):** `ai_activate` fails on every worktree dev stack with
> `Variant not installed: cuda12`, `installedVariants: []`, although the dev-runner sets
> `JUSTSEARCH_SERVER_EXE=F:\justsearch-public\modules\ui\native-bin\llama-server\variants\cuda12\llama-server.exe`
> (`scripts/dev/dev-runner.cjs:493-511`, visible in `/api/debug/effective-config`).
> `RuntimeActivationService.resolveVariantsRoot()` (`:1692-1720`) looks only at
> `aiHome/native-bin/llama-server/variants` then the worktree's `repoRoot/modules/ui/native-bin/...`,
> never the resolved exe, and the value is cached at construction (:383). Fix: derive the variants
> root from the resolved server exe when it lies under a `.../variants/<id>/` directory, with a
> unit test for the resolution order. The validator's workaround was two junctions
> (`<worktree>/modules/ui/native-bin` → main's, and `<dataDir>/native-bin/llama-server/variants` →
> main's); the fix must make both unnecessary.
>
> **T1 (dev tooling):** `quick_health` reports `rebuildFirst: true` / "runs different code than
> your worktree" while `provenance.gitHead` (a83de156) matches the worktree's HEAD — a false
> positive on the `headDistStamp` comparison. Find the comparison in the dev-runner, determine what
> it compares (dist stamp of the calling checkout vs the running one? the caller was the MAIN
> checkout's MCP server while the stack ran from a worktree via `distFrom`), and make the verdict
> correct when the running stack's provenance repoRoot == the distFrom worktree even though the
> caller is the main checkout (the caller's own dist is irrelevant then).
>
> **T2 (dev tooling, small):** the MCP `api_call` allowlist lacks `/api/schemas/*` and
> `/api/indexing-jobs/failed` (only `/failed/by-prefix`). Add both, GET-only.
>
> **D6 (added mid-lane by the coordinator, found by the UI lane):** `core.add-watched-root`'s
> Operation handler bypasses `IngestCollectionPolicy`. The REST route validates the collection
> (`IndexingController.java:176-186`) but `AddWatchedRootHandler.java:50-54` — the path every
> UI/agent invocation takes — does not, so an agent (or the new UI field) can create a watched root
> tagged `agent-history` and inherit that corpus's default-EXCLUDED posture. Fix: route the handler
> through the same validation the REST route uses (one authority, not a copy; extract if needed),
> reject reserved/invalid collections with the same error shape, and add a handler-level test
> (reserved name rejected; normal name accepted) falsified by removing the check.
>
> **Route, do not fix:** `POST /api/ai/runtime/deactivate` returns 200 with
> `RUNTIME_BASELINE_NOT_FOUND` on GPU-only dev installs (`RuntimeActivationService.runDeactivate`
> :940-946 switches to a CPU baseline that dev never provisions, tempdoc 656) — say whether
> production bundles the CPU baseline.

## §B — Pre-implementation verification

Every cited line was re-read against source before any code was written. Corrections are marked
**CORRECTION**; everything else confirmed as stated.

### §B.1 — D1

- Confirmed `import io.justsearch.app.api.indexing.IndexingJobView;` at
  `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:11`.
- Confirmed uses at `:675` and `:742` (`List<IndexingJobView> payload` in
  `handleListFailedJobsSubstrate` and the by-prefix handler) and `:931-934`
  (`private static IndexingJobView toJobView(IndexingService.FailedJobInfo j)`). Also `:647`,
  `:911`, `:920` in javadoc — javadoc references do not matter to the scanner, which keys on
  `import <pattern>;` (`scripts/governance/gates/operation-surface/enforcer.mjs:152-169`), but the
  real import makes the file a detected surface either way.
- Confirmed the gate's own finding text names exactly this file
  (`operation-surface/undeclared-surface`, one finding).
- **Row shape** read from the register and the enforcer, not guessed. A row needs `id`, `kind`,
  `lang`, `path`, `guard`, `consumesProjection`, `note`. Three checks bite on it:
  dangling-guard resolution (`enforcer.mjs:86-99`), projection lineage — `consumesProjection` must
  be `canonical-record`, `self`, or another row's `id` (`:102-118`) — and, from the sibling
  `register-guard-resolution` gate, rung-1 invalid-guard-form: a bare `self`/`none-yet`/absent
  guard is **not representable** (`scripts/governance/gates/register-guard-resolution/enforcer.mjs:87-90`).
  So the row must name a real `gate:`/`test:` guard even though `operation-surfaces.v1.json` is
  registered with no `requireGuardedKinds` (`governance/registry.v1.json`).
- **Guard chosen:** `test:IndexingControllerFailedJobsWireContractTest`
  (`modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerFailedJobsWireContractTest.java`).
  It is the right guard rather than a nearby one: it drives BOTH routes over a live Javalin
  instance and validates the returned JSON against `SSOT/schemas/failed-indexing-jobs-response.v1.json`,
  which is precisely the "this projection still matches the canonical record" property.
- **`consumesProjection: canonical-record`** — verified by tracing the lineage, not assumed:
  `SqliteJobQueue.listFailedJobs` → `DelegatingIngestService`/`GrpcIngestService` →
  `RemoteKnowledgeClient` → `IndexingService.FailedJobInfo` (`IndexingService.java:307`, `:315`) →
  `toJobView`. It never touches `IndexingJobsChangeStream` or `RemoteIndexingJobsBridge`, so it is
  a second direct read of the one jobs table — the same lineage the existing `count-projection` row
  declares for `WorkerStatusMapper`, not a derivation of `head-bridge`.

- **Why CI missed it — answered, and the answer is worse than "the wrong job ran it".**
  `operation-surface` runs **nowhere**. Not in `ci.yml`, not in any other workflow, not in
  `build.gradle.kts`. The only `scripts/governance/run.mjs` invocations in CI are:
  `--gate config-surface --gate dead-code --gate npm-audit --gate module-deps` (the
  "Kernel gates with built inputs" step), `--gate adr-coverage`, and `--self-test`. The self-test
  is the trap worth naming: it DOES exercise `operation-surface/positive` and
  `operation-surface/negative` — but against the gate's fixture tree, never against the repo. So
  CI proves the enforcer works and proves nothing about `main`. #614 typed `IndexingController`
  onto the canonical record, the register gained no row, PR CI and the merge group were both green,
  and `main` was red on this gate from that merge onward.
  A repo-wide count (`tmp/gate-ci-map.mjs`, transient): **29 of 35 registered gates run in no
  workflow.** That is a class-level defect, out of this lane's scope; see Open items.

### §B.2 — D5

The validator's hypothesis was correct, and the mechanism is one layer further out than
"HeadAssembly wiring order".

- `AgentRunQueryService.operationHistory` confirmed at
  `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunQueryService.java:104-111`
  (**CORRECTION**: `:104-111`, not `:103-111` — trivial).
- The null's origin: `AgentToolFactory.build`
  (`modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/AgentToolFactory.java:65-67`)
  returns `new Output(null, null, null, null, null, null, null)` when
  `knowledgeClient == null || indexingService == null`. The second component is `fileOperationLog`
  (record declaration `:43-51`).
- That arm is the one the REAL boot takes:
  `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:437-439` constructs `HeadAssembly`
  with `knowledgeServer = null` and logs *"HeadAssembly started (degraded — Worker connecting in
  background)."*; `HeadAssembly.java:325-330` then sets both `knowledgeClient` and
  `indexingService` to null.
- Propagation confirmed end to end: `HeadAssembly.java:423` → `:666` → `:689` →
  `OrchestrationPhase.java:62,150` → `AgentLoopWiring.java:53,83` →
  `AgentLoopService.java:143` (`private final FileOperationLog fileOperationLog; // nullable`),
  assigned once at `:325`, handed to `AgentRunQueryService` at `:363-369`. **No setter exists** —
  `AgentLoopService` has late-bind setters for seven other collaborators (`:380, :390, :401, :406,
  :414, :970, :981`) and none for the journal.
- Why it never self-heals: `connectKnowledgeServer` (`HeadAssembly.java:1256-1263`) resolves
  `agentToolsRegistration` → `AgentToolHandlers.registerLateBound` → `AgentToolFactory.java:115`,
  which builds a **brand-new** `FileOperationLog` wired only into the write-side
  `FileOperationsTool` (`:132-137`). Two instances, one authority for writes, `null` for reads.
- **Data-dir mismatch ruled out.** `AgentToolFactory.java:115` was the single path-resolution site
  for either side, and `dataDir` reaches both `ServicePhase` (`HeadAssembly.java:402`) and
  `registerLateBound` (`:822`) from the same field. This is purely a null-instance bug.
- Endpoint confirmed: `modules/ui/src/main/java/io/justsearch/ui/api/AgentRoutes.java:83-85`
  (`POST /api/chat/agent/undo`, `GET /api/chat/agent/history`, `.../history/{batchId}`) →
  `AgentSessionController.java:279-296` → `AgentHistoryResponse` (`batches` key,
  `modules/app-api/src/main/java/io/justsearch/app/api/agent/AgentHistoryResponse.java:11`). The
  controller reads through a **live supplier** (`AgentSessionController.java:71-73`), so the
  controller is not the fault — the resolved `AgentLoopService` is.
- **CORRECTION, and it changes D5's headline.** The brief calls this "S3 but it kills the
  product's undo affordance". It does not, because there is no undo affordance on this endpoint to
  kill. `getAgentHistory` (`modules/ui-web/src/api/domains/agent.ts:141`), `undoToolExecution`
  (`:130`) and `getAgentBatchDetail` (`:158`) have **zero production callers** in
  `modules/ui-web/src/` — verified by grep; only `agent.test.ts` touches them.
  `AgentSessionController.ts:2349` states outright that it "no longer reads the separate
  `/api/chat/agent/history`", and the Undo control in `Shell.ts:1188-1204` routes through
  `OperationClient.undo → POST /api/undo/{operationId}`. So the backend fix is correct and worth
  making (the endpoint is published, schema'd, and lies), but it restores a surface no UI currently
  consumes. The FE decision — wire it or retire the three helpers and the routes per
  `retire-with-a-sweep` — belongs to the UI lane; see Open items.
- **`FileOperationLog` is stateless over the filesystem** (`modules/app-agent/src/main/java/io/justsearch/agent/tools/FileOperationLog.java:44`
  — one `final Path logDir`, no other instance field), which is why the two-instance situation
  produced correct *files* and an empty *read*. It also means the cheap fix (return a second
  instance from the guard arm) would have worked functionally. It was rejected: see §C.

### §B.3 — H1

Every cited line confirmed.

- `resolveVariantsRoot()` at
  `modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java:1692-1720`
  (pre-change numbering), `private final Path variantsRoot;` at `:161`, assigned at `:383` inside
  the canonical 8-arg constructor that all five public overloads delegate to.
- Six reads of the field, in three methods: `:766` and `:771` (`runActivate`, including the
  `RUNTIME_VARIANT_NOT_INSTALLED` failure at `:778`), `:1514`, `:1515`, `:1522`, `:1523`
  (`listInstalledVariants`, whose result becomes `installedVariants` at `:392`).
- **The mechanism, confirmed:** `aiHome` = `PlatformPaths.resolveAiHome()` ←
  `EnvRegistry.HOME` (`justsearch.home` / `JUSTSEARCH_HOME`), which the dev-runner sets to
  `<worktree>/modules/ui-web/.dev-data` (`scripts/dev/dev-runner.cjs:1684-1685`, `:285`) — no
  `native-bin` under it. `RepoRootLocator.findRepoRoot` walks up for an `SSOT/` directory, and a
  worktree **has its own** `SSOT/`, so it resolves the worktree, which has no
  `modules/ui/native-bin` (verified absent on disk). Both candidates miss by construction on every
  worktree.
- The exe the dev-runner resolves is right there: `resolveCuda12ServerExe`
  (`dev-runner.cjs:493-499`) prefers the worktree's own cuda12 and falls back to the **shared main
  checkout's**, and `:507-511` publishes it as `JUSTSEARCH_SERVER_EXE`.
- **Accessor to use, verified:** `ConfigStore.globalOrNull().get().ai().serverExe()` —
  `ResolvedConfig.Ai`'s first component (`modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java:207-208`),
  resolved from `EnvRegistry.SERVER_EXE` (`EnvRegistry.java:291`) at
  `ResolvedConfigBuilder.java:1061`. Not `System.getenv` — a direct env read is what
  `EnvRegistryDirectReadTest` forbids, and the store is also where a JVM-arg override outranks the
  env. `ConfigStore.globalOrNull()` is already the idiom in this very class (`:412`, `:1030`,
  `:1677`, `:1724`).
- **Existing helper found before writing a new one** (`explore-before-implementing`):
  `resolveVariantIdFromExePath` (`:1604-1619`) already walks path segments for a case-insensitive
  `variants` segment and returns the child name. The new resolver answers the sibling question over
  the same shape, and the code comment says so rather than the two silently diverging.
- On-disk layout confirmed: `F:/justsearch-public/modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe`
  exists; `cuda12` is the only variant id; there is **no** flat baseline exe on this machine.
- **CORRECTION to the brief's proposed fix — and this one matters.** "derive the variants root
  from the resolved server exe" is necessary but not sufficient. The field is `final` and computed
  in the constructor, so a correct resolver behind an eager cache still answers with whatever was
  on disk at boot. Two ways that bites: a fresh production profile where nothing exists yet and
  `AiInstallService` installs afterwards, and `CoreApiAssembly.java:392-400`, which can construct a
  second instance. The memo had to become lazy. §C records the test that distinguishes the two.
- **Order correction.** The brief is ambiguous about where the exe branch goes. It must go
  **second**, after `aiHome`: this class itself writes `justsearch.server.exe` on activate (`:986`,
  `:997`, `:1131`) and `AiInstallService` writes it at `AiInstallService.java:1946`, so an
  exe-first order would let one activation re-root a real install at whatever it last launched.

### §B.4 — T1

- **CORRECTION — the brief's stated mechanism is wrong.** There is no `headDistStamp` comparison
  behind `rebuildFirst`. `headDistStamp` drives a *different*, independent check —
  `ownership.backendStale` (`scripts/dev/justsearch-dev-mcp/server.mjs:235-256`) — which did not
  fire here; had it fired, the payload would have carried `backendStale: true` and a
  `STALE BACKEND:` prefix on `recommendedAction`.
  `rebuildFirst` comes from `provenance.mismatch` (`scripts/dev/lib/ownership-verdict.cjs:149`,
  surfaced at `:156-159`), which was computed at `server.mjs:190-193` as a plain string comparison
  of `leaseProv.repoRoot` against `callerRepoRoot`. The brief's *parenthetical* — "the caller was
  the MAIN checkout's MCP server while the stack ran from a worktree via `distFrom`" — is the
  actual cause.
- `leaseProv.repoRoot` is stamped by `resolveProvenance()` (`dev-runner.cjs:789-795`) as the tree
  the dev-runner ran in, which under `start { distFrom }` is the worktree (`server.mjs:1289-1297`
  spawns that checkout's dev-runner). `callerRepoRoot` is the MCP server's own `repoRoot`
  (`server.mjs:1259`, `:2142`, and the other call sites).
- **A finding the brief did not ask for, and it sharpens the fix.** `rebuildFirst` is returned
  ONLY on the `callerIsOwner` branch (`ownership-verdict.cjs:151-161`); the other branches never
  carry it. And the only way an owner's stack runs from a root other than the caller's is an
  explicit `distFrom`. So the check, as written, could fire on *nothing but* its own false
  positive. That argues for correcting rather than deleting it: the intended case (a run record
  whose root is neither the caller's nor the one the launch asked for) is real, it just had no way
  to be distinguished, because **nothing recorded what was asked for**.
- `distFrom` was **not** on the run record — verified: `resolveProvenance()` had three fields
  (`repoRoot`, `gitHead`, `headDistStamp`) and the MCP layer resolved `distFrom` without passing it
  down (`server.mjs:1289-1304`, `cli.mjs:174-197`). The fix therefore had to add the fact before it
  could use it.
- `ownership.provenance` is schema'd as `z.record(z.string(), z.unknown())`
  (`scripts/dev/justsearch-dev-mcp/schemas.mjs:71`), so the new field is additive with no schema
  change.

### §B.5 — T2

- Confirmed: `API_CALL_ALLOWLIST` (`server.mjs:696`) had `/api/indexing-jobs/failed/by-prefix` and
  not `/api/indexing-jobs/failed`; no `/api/schemas` entry at all.
- Routes confirmed: `modules/ui/src/main/java/io/justsearch/ui/api/routes/IndexingRoutes.java:32`
  (`/api/indexing-jobs/failed`) and `LocalApiServer.java:664`
  (`app.get("/api/schemas/{name}", schemaController::handle)`).
- The schemas route carries a **path parameter**, so it needs a `pattern` entry like the existing
  `/api/ai/install/packages/{packageId}/decline` row — matching is `e.path === input.path` or
  `e.pattern.test(input.path)` (`server.mjs:1563-1565`).

### §B.6 — D6

- Confirmed the asymmetry. `IndexingController.java:180-187` routes a supplied, non-blank
  collection through `IngestCollectionPolicy.normalizeRequested` and returns 400
  `INVALID_REQUEST` on rejection. `AddWatchedRootHandler.execute` read the `collection` arg
  (defaulting to `"default"`) and passed it straight to `IndexingService.addWatchedRoot` with no
  validation at all — only the `Files.isDirectory` check, which the handler *did* copy from the
  REST route (slice 450 §2.3).
- Confirmed the consequence is real and not theoretical: `IngestCollectionPolicy.RESERVED` is
  `{justsearch-help, agent-history}`, and the class's own javadoc states `agent-history` is the
  corpus `QueryFilterBuilder.addCollectionScope` default-excludes. A watched root tags every
  document its scan admits, so the folder would index and then be invisible to search — a silent
  failure, which is the worst shape for this.
- **Blast-radius check:** the two are the only production callers of
  `IndexingService.addWatchedRoot` (`IndexingController.java:189` and
  `AddWatchedRootHandler.java:81`); the rest of the grep hits are the interface default, the
  Worker-side implementations (`RemoteKnowledgeClient.java:1036`, `RootLifecycleOps.java:285`) and
  one system test. So closing this handler closes the gap — there is no third path.
- **"Extract if needed" was not needed.** `IngestCollectionPolicy.normalizeRequested` already IS
  the one authority (tempdoc 811 decision C-2a); the REST route calls it directly. The handler now
  calls the same method. Extracting a shared wrapper would have created the second copy the
  instruction warns against.
- **CORRECTION to the cited lines:** the handler's collection handling is at `:50-54` in the
  pre-change file as stated, but the missing validation is better described as an *absence* across
  `:37-83` (the whole `execute` body) than as a defect at those four lines.
- **Pre-existing divergence noted, not changed:** the REST route leaves an absent collection as
  `null` (index default) while the handler substitutes the literal `"default"`. Both paths now
  validate identically, but they still hand the service different values for "no collection
  supplied". Out of scope; see Open items.

## §C — Implementation and post-implementation critical pass

Per test: what wrong implementation would still pass, and what was done about it.

### D1 — register row + the CI hole

`governance/operation-surfaces.v1.json` gains `head-failed-jobs-controller`
(`kind: projection`, `lang: java`, `guard: test:IndexingControllerFailedJobsWireContractTest`,
`consumesProjection: canonical-record`). The note records the projection-vs-fork judgment, the
lineage that justifies `canonical-record`, and the known `scanId` gap that tempdoc 911 §F owns —
declared here so it is reviewable against the canonical record rather than invisible.

`.github/workflows/ci.yml` gains a **Register-family gates** step running
`operation-surface`, `execution-surface` and `register-guard-resolution`. Scoping is deliberate and
stated in the step's comment: all three are input-free and green, whereas a blanket full-kernel
step would land CI red immediately — `ts-any` is red on `main` behind an expected-state pin, and
`wire` is environment-sensitive (needs the buf CLI installed).

*What would still pass:* nothing much — the gate IS the test here, and it is verified in both
directions (it failed before the row, passes after, and the falsification is the register itself:
removing the row reproduces `operation-surface/undeclared-surface`). The honest limit is that the
CI step's correctness cannot be proved from this worktree; it will be observable on the PR run.

### D5 — remove the false dependency, and keep one instance

Root-cause fix, not a null-tolerance fix. Three alternatives were considered and rejected because
each treats the null as a fact to work around: a `setFileOperationLog` setter on `AgentLoopService`
(a second late-binding path only one of the two registration routes would call); a default inside
`AgentRunQueryService.operationHistory` (a third construction authority for the same directory);
and returning a fresh instance from the guard arm (functionally sufficient — the type is stateless
over the filesystem — but it leaves two instances and two retention prunes, which
`AgentToolHandlers` already calls out as a cost).

What landed:

- `AgentToolFactory.build` returns the journal from the guard arm
  (`AgentToolFactory.java`, guard arm + new `fileOperationLog(Path)` helper as the single
  path-resolution rule).
- `AgentToolFactory.assemble` gains `existingFileOperationLog`, immediately after
  `existingAdapter` — the same reuse contract that parameter already documents, so the pattern is
  the file's own, not a new one.
- `AgentToolHandlers.registerLateBound` threads it; `HeadAssembly` holds the journal as a field
  (next to `agentSearchAdapter`, for the same reason) and passes it at the `registerLateBound` call
  site. Net effect: **one** instance, and the reader is the writer.

*Tests and what a wrong implementation would still pass:*

- `HeadAssemblyTest#agentOperationHistoryReadsTheJournalAtBootstrapBeforeAnyWorkerConnects` drives
  the production `knowledgeServer=null` constructor, seeds a v2 journal batch under a temp
  `justsearch.data.dir`, and reads back through `core().agent()`.
  - The assertion is deliberately **pre-connect**. A post-connect assertion would pass with the
    defect fully present, because the late-bound path builds its own journal — and pre-connect is
    the window the product actually lives in.
  - It carries an explicit **right-reason guard**: `AgentService.unavailable()` also answers
    `operationHistory` with an empty list (the `AgentRunQueries` default at
    `AgentRunQueries.java:63-65`), so the test asserts the resolved implementation is the real
    `AgentLoopService` before trusting the history assertion. Without that, a green would say
    nothing.
  - It cannot be satisfied by a direct `AgentRunQueryService` construction — the shape that stayed
    green throughout the defect (`AgentLoopServiceTest` builds one with a real journal by hand:
    `unreachable-seed-green`).
- `AgentToolFactoryScanWiringTest#eagerGuardNullsTheWorkerBackedToolsButNotTheJournal` is the
  pre-existing `eagerGuardStillYieldsAllNull` **restated at its real scope**, not weakened. Its
  intent — no Worker, no Worker-backed tool — is preserved verbatim for all six tools; only the
  journal moved out, with the reason in the javadoc. It additionally asserts the journal's
  directory is `dataDir/file-operations`, so "non-null" cannot be satisfied by a journal pointed
  somewhere else.
- `AgentToolFactoryScanWiringTest#suppliedJournalIsReused` pins the one-instance property
  (`assertSame` on reuse, `assertNotSame` on the fresh path) — the part the "return a second
  instance" alternative would have failed.

*Falsification (run, observed, restored):* reverting the guard arm to
`new Output(null, null, null, null, null, null, null)` →
`HeadAssemblyTest > agentOperationHistoryReadsTheJournalAtBootstrapBeforeAnyWorkerConnects FAILED`
and `AgentToolFactory — scan wiring … the eager guard covers the Worker-backed tools — and only
those (913 D5) FAILED`, `31 tests completed, 2 failed`.

### H1 — resolve from the exe, and stop freezing the answer

`resolveVariantsRoot` is split into a pure package-private
`static Path resolveVariantsRoot(Path aiHome, Path repoRoot, Path resolvedServerExe)` plus a
`variantsRoot()` accessor over a `volatile` memo that is re-derived whenever it no longer names a
directory. The six field reads become `variantsRoot()` calls (two of them hoisted to a local so
`root` and `root.getParent()` cannot disagree within one method). Resolution order:
aiHome → **exe-derived** → repoRoot dev layout → standard path for the error message.
`variantsRootOfExe` walks segments right-to-left for a case-insensitive `variants` and requires the
resulting prefix to be a real directory, so a BYO exe outside a variants tree contributes nothing.

*Tests and what a wrong implementation would still pass:* this is the case where one tier alone is
misleading in both directions, so there are two.

- Pure-resolver cases (`RuntimeActivationServiceVariantsRootTest`): aiHome-wins,
  the defect case, repoRoot-still-wins-with-no-exe, exe-outside-variants, exe-under-a-nonexistent-
  variants-dir, and null-aiHome. These pin the ORDER — but every one of them would pass with the
  constructor cache left `final`, because they call the static directly.
- `statusListsTheSharedVariantWithoutJunctions` closes that: it builds the service through the
  4-arg public constructor with `justsearch.home` pointing at an empty worktree-shaped
  `.dev-data`, publishes the shared exe through `ConfigStore` exactly as `JUSTSEARCH_SERVER_EXE`
  reaches it, and asserts `getStatus().installedVariants()` contains `cuda12` — the exact payload
  the live `ai_activate` failure reported as `[]`.

*Falsification (run, observed, restored):*
(a) neutralising the exe branch (`Path fromExe = null;`) →
`THE DEFECT: worktree aiHome + worktree repoRoot both miss → the exe names the root FAILED` **and**
`getStatus() lists the shared cuda12 variant on a worktree-shaped install FAILED`,
`13 tests completed, 2 failed`.
(b) restoring the eager-final behaviour (constructor-time
`this.variantsRoot = resolveVariantsRoot(aiHome, repoRootOrNull(), null)` plus a memo that never
refreshes) → only `getStatus() lists the shared cuda12 variant on a worktree-shaped install
FAILED`, `13 tests completed, 1 failed`. That the *pure* tests stayed green under (b) is the
evidence that the second tier was necessary.

**Live verification recipe (for the orchestrator).** The junctions must be removed first, or the
green is the workaround's, not the fix's (`green-masked-destructive`):

1. Remove both workaround junctions if present:
   `rmdir "<worktree>\modules\ui\native-bin"` and
   `rmdir "<worktree>\modules\ui-web\.dev-data\native-bin\llama-server\variants"`
   (`rmdir` on a junction unlinks it; do not use `rm -rf`, which deletes through into the main
   checkout's real directory).
2. Confirm they are gone: `ls <worktree>/modules/ui/native-bin` must fail.
3. Build the worktree (`./gradlew.bat :modules:ui:installDist`) and start the stack from it:
   `justsearch_dev_start { distFrom: "<worktree-name>", hotReload: true }`.
4. `api_call { path: "/api/debug/effective-config" }` — confirm `JUSTSEARCH_SERVER_EXE` still
   points at `F:\justsearch-public\modules\ui\native-bin\llama-server\variants\cuda12\llama-server.exe`
   (the main checkout).
5. `api_call { path: "/api/ai/runtime/status" }` — `installedVariants` must now contain `cuda12`.
   This is the assertion that was `[]`.
6. `ai_activate {}` — must reach a loaded runtime rather than
   `RUNTIME_VARIANT_NOT_INSTALLED: Variant not installed: cuda12`.
7. Then send one real chat query (`use-every-verification-tier`) to confirm the activated runtime
   actually serves.

### T1 — record what was asked for, then judge against it

The predicate moved out of `server.mjs` into a pure, exported
`computeProvenanceMismatch(leaseProvenance, callerRepoRoot)` in
`scripts/dev/lib/ownership-verdict.cjs` — the same "one verdict authority" the file already exists
to be. It exonerates a launch when the recorded `distFromRoot` equals the tree the dev-runner
actually ran in ("launched where asked", proved rather than assumed), and still fires when the
running root is neither the caller's nor the requested one.

The fact it needs did not exist, so the wiring was added: `buildDevRunnerArgsStart` forwards
`--dist-from=<resolved root>` (only when a `distFrom` was actually supplied — recording a requested
root that was never requested would exonerate the drift the check exists to catch);
`dev-runner.cjs parseArgs` reads it into `opts.distFromRoot`; `resolveProvenance(distFromRoot)`
stamps it onto the lease. The now-unused `_normPath` helper in `server.mjs` was deleted rather than
left behind (`retire-with-a-sweep`).

*Tests and what a wrong implementation would still pass:* the pure predicate is correct in
isolation even if nothing ever passes `distFromRoot` to it — that is exactly the
`audit-without-test` shape — so the suite covers both halves.

- `test-ownership-verdict.mjs` (6 new cases): the false positive; the same case with Windows
  backslashes and a trailing slash (normalization must not resurrect it); running-root-is-neither
  → still a mismatch; no-distFrom-recorded → still a mismatch; same-root; and unknowns → not a
  mismatch (an absent fact is not a negative finding).
- `test-dev-mcp-surface-honesty.mjs` (5 new cases) pins the wiring: the flag is forwarded, the flag
  is omitted when there is nothing to forward, the handler's condition is `distRoot.distFrom ?
  effRepoRoot : null`, `parseArgs` yields `null` (not `""`) when absent, and the spawn-time call is
  `resolveProvenance(opts.distFromRoot)` — an unparameterised call would stamp `null` forever while
  every other test stayed green.

*Falsification (run, observed, restored):* removing the `distFromRoot` exoneration from the
predicate → `2 FAIL` in `test-ownership-verdict.mjs` (`38 passed, 2 failed`), naming the false
positive and the separator case. Dropping the `--dist-from` push from `cli.mjs` →
`FAIL start forwards the resolved distFrom root to the dev-runner` (`77 passed, 1 failed`).
Replacing `resolveProvenance(opts.distFromRoot)` with `resolveProvenance()` →
`FAIL …and the provenance block stamped on the lease carries it` (`77 passed, 1 failed`).

### T2 — two allowlist entries

`/api/indexing-jobs/failed` (exact, GET) and `/api/schemas/{name}` (pattern
`^\/api\/schemas\/[A-Za-z0-9._-]+$`, GET), plus the two rows
`docs/reference/contributing/mcp-dev-tools.md` requires. Three tests in the allowlist block:
both failed-jobs routes admitted; the schemas route admitted, sent verbatim, and GET-only; and a
two-segment path **rejected** — the route serves one segment, and admitting more would make the
allowlist describe a surface that does not exist.

*Falsification (run, observed, restored):* renaming the schemas entry →
`FAIL 913 T2: /api/schemas/<name> is admitted, and is GET-only` (`77 passed, 1 failed`);
deleting the index-wide failed entry →
`FAIL 913 T2: the index-wide failed-jobs route is reachable, like its by-prefix sibling`
(`77 passed, 1 failed`).

### D6 — call the authority that already exists

`AddWatchedRootHandler` now routes the collection through
`IngestCollectionPolicy.normalizeRequested` and returns
`OperationResult.failure(message, ApiErrorCode.INVALID_REQUEST.name(), Map.of(), false)` — the
Operation-layer spelling of the REST route's 400, so a consumer branching on the code gets one
answer from both surfaces. The call sits deliberately *outside* the JSON-parsing `try`: a rejected
collection is a caller error with its own message, not a malformed-arguments failure, and folding it
into `HandlerJson.invalidArgs` would have replaced the policy's specific message with a generic one.

*Tests and what a wrong implementation would still pass:*

- `rejectsReservedCollection` iterates `IngestCollectionPolicy.reservedCollections()` rather than
  hardcoding `agent-history`, so a future reserved name is covered the day it is added — a test
  naming one literal would go quietly stale.
- It asserts three things, not one: the failure, the message naming the offending collection and
  the reason, **and** `addCalled == 0`. That last one is the assertion that matters: a handler that
  returns a failure and still registers the root is the same defect wearing an error message.
- `reservedMatchIsNotLiteral` (`"  Agent-History "`) pins that the guard is the policy's
  case/whitespace-insensitive match, not a `.equals` a caller walks past with different casing.
- `ordinaryCollectionStillPasses` asserts the service receives the **normalized** `"my-notes"` from
  `"  my-notes "` — so "added a validation call" cannot be satisfied by a check whose result is
  discarded.
- All four drive `execute`, not the policy. The policy was already correct and already tested; the
  defect was entirely that nothing on this path called it, so a policy-level test would have been
  green throughout.

*Falsification (run, observed, restored):* replacing the `normalizeRequested` call with a bare
`collection.trim()` →
`913 D6: a reserved collection is rejected and never reaches the service FAILED` and
`913 D6: a reserved name is matched case- and whitespace-insensitively FAILED`,
`12 tests completed, 2 failed`. (`ordinaryCollectionStillPasses` stays green under that
falsification, which is correct — it guards the non-regression half.)

### Cross-cutting critical pass

- **Wrong-gate check.** For D5 the gate is `AgentToolFactory.java:65-67`, and the set-site
  (`HeadlessApp.java:437-439` passing `null`) was read directly, not inferred — the arm taken in
  production is the one changed. For H1 the failing site `:778` and the status producer `:1509+`
  were both re-read; both now read through `variantsRoot()`.
- **Tri-state.** `computeProvenanceMismatch` returns `false` for unknowns (missing repoRoot or
  caller) rather than treating absence as evidence; `variantsRootOfExe` returns `null` rather than
  a speculative path when the derived root is not a directory.
- **Asymmetric lifecycle / stale-flag short-circuit.** The H1 memo is the stale-flag class caught
  in review: `volatile` + a liveness re-check, and the falsification proves the re-check is
  load-bearing.
- **Subagent findings re-verified.** Three subagents produced the D5, H1 and CPU-baseline
  evidence. Every load-bearing line each cited was re-read in the main loop before it was acted on;
  §B.2's FE-consumer correction and §B.3's caching correction both came out of that pass, and
  §B.4's mechanism correction came from reading the code rather than the brief.
- **Process note, recorded because it nearly cost work.** A falsification was reverted with
  `git checkout -- <path>`, which restores to `HEAD` — and the file's change was uncommitted, so
  the whole addition was wiped and had to be re-written. Falsification of uncommitted code must
  save and restore the file's *content* (the scripted save/restore used for every subsequent
  falsification), never `git checkout`.

## Report-back — what changed, with file:line

| Item | Status | Where |
|---|---|---|
| D1 register row | done | `governance/operation-surfaces.v1.json` — `head-failed-jobs-controller`, after `head-sse-controller` |
| D1 CI wiring | done | `.github/workflows/ci.yml` — "Register-family gates (operation-surface, execution-surface, guard resolution)" |
| D5 root cause | done | `AgentToolFactory.java` (guard arm + `fileOperationLog(Path)`; `existingFileOperationLog` on `assemble`) |
| D5 thread-through | done | `AgentToolHandlers.java` `registerLateBound` param; `HeadAssembly.java` field + `:423` + `:666` + the `registerLateBound` call |
| D5 test | done | `HeadAssemblyTest#agentOperationHistoryReadsTheJournalAtBootstrapBeforeAnyWorkerConnects` |
| D5 guard restated | done | `AgentToolFactoryScanWiringTest#eagerGuardNullsTheWorkerBackedToolsButNotTheJournal`, `#suppliedJournalIsReused` |
| H1 resolver | done | `RuntimeActivationService.java` — `variantsRoot()`, `resolveVariantsRoot(Path,Path,Path)`, `variantsRootOfExe`, `resolvedServerExeOrNull`, `repoRootOrNull` |
| H1 tests | done | `RuntimeActivationServiceVariantsRootTest` (6 cases) |
| T1 predicate | done | `scripts/dev/lib/ownership-verdict.cjs` `computeProvenanceMismatch`; consumed at `server.mjs` ~`:190` |
| T1 wiring | done | `cli.mjs buildDevRunnerArgsStart`, `server.mjs` start handler, `dev-runner.cjs parseArgs` + `resolveProvenance` |
| T1 tests | done | `test-ownership-verdict.mjs` (6), `test-dev-mcp-surface-honesty.mjs` (5) |
| T2 allowlist | done | `server.mjs API_CALL_ALLOWLIST`; `docs/reference/contributing/mcp-dev-tools.md`; 3 tests |
| Dev-MCP CI wiring | done | `.github/workflows/ci.yml` — "Dev-MCP surface (doc sync + pure unit suites)" |
| D6 handler validation | done | `AddWatchedRootHandler.java` — `normalizeRequested` + typed `INVALID_REQUEST` failure |
| D6 tests | done | `AddWatchedRootHandlerTest#rejectsReservedCollection`, `#reservedMatchIsNotLiteral`, `#ordinaryCollectionStillPasses` |

## Open items

1. **29 of 35 registered governance gates run in no workflow.** D1 is one instance of a class.
   Verified by mapping `governance/registry.v1.json` against `.github/workflows/*.yml`: only
   `config-surface`, `dead-code`, `dead-code-jvm`, `npm-audit`, `module-deps` and `adr-coverage`
   were invoked before this PR; this PR adds `operation-surface`, `execution-surface` and
   `register-guard-resolution`. The remaining 26 include `wire`, `hook-integrity`,
   `ssot-catalog-sync`, `surface-altitude`, `interaction-surface`, `runtime-state`,
   `contract-projection`, `observed-happening` and `stage-completeness` — each of which can be red
   on `main` indefinitely with nothing to notice. Wiring the rest needs a per-gate decision (some
   are environment-sensitive, `ts-any` is pinned red), so it is a governance-lane item, not a
   ride-along. Owner: the governance-kernel lane (910).
2. **`scripts/dev/test-*.mjs` — 16 suites, one of which runs in CI.** Only
   `test-onramp-first-success.mjs` is invoked (`onramp-smoke.yml:86`). This PR wires the two pure
   suites it touched plus `check-dev-mcp-doc-sync`; the rest (`test-dev-runner-admission`,
   `test-dev-runner-lease-duration`, `test-dev-runner-pruning`, `test-dev-runner-runtime-resolution`,
   `test-dev-mcp-projection-live`, …) still run only when an agent remembers to. Same class as
   item 1.
3. **`api_call` cannot pass a query string.** The allowlist match is
   `e.path === input.path || e.pattern.test(input.path)` (`server.mjs:1563-1565`) against the raw
   path, and no `query` input exists on the tool. So `/api/indexing-jobs/failed/by-prefix?pathHash=…`
   — an endpoint whose *only* useful form carries a query param — is unreachable, and the entry
   added by T2 for `/api/indexing-jobs/failed` is usable only at its default limit. Pre-existing,
   found while implementing T2, out of scope. Owner: the dev-tooling lane.
4. **`GET /api/chat/agent/history` has no FE consumer.** See §B.2's correction: the three
   `agent.ts` helpers (`:130`, `:141`, `:158`) have zero production callers, and Undo routes
   through `POST /api/undo/{operationId}` instead. The backend now answers correctly; the decision
   is whether the UI drill-down gets wired or the three helpers plus the two history routes get
   retired per `retire-with-a-sweep`. **Not** to be resolved by adding backend code. Owner: the UI
   lane (911).
5. **`POST /api/ai/runtime/deactivate` → `RUNTIME_BASELINE_NOT_FOUND` is dev-only, not a
   production defect.** Routed as instructed, with the answer the brief asked for.
   `runDeactivate` (`RuntimeActivationService.java:940-946`) fails when
   `resolveCpuBaselineExe(aiHome)` finds nothing, and it looks for a flat
   `aiHome/native-bin/llama-server/llama-server.exe` (then non-`variants` subdirectories).
   Production **does** ship that file: `modules/ui/build.gradle.kts:840-884`
   (`stageLlamaServerFromPrebuilt`) stages the CPU prebuilt at the *root* of the stage dir
   (`:871`), CUDA is staged separately under `variants/cuda12` (`:700-739`),
   `bundleSidecarResources` copies the whole tree `into("native-bin/llama-server")` (`:1589-1590`),
   and a build-time assertion (`:1662-1687`) throws `GradleException("Missing bundled
   llama-server.exe: …")` if the flat exe is absent — the installer cannot be built without it. At
   runtime `modules/shell/src-tauri/src/lib.rs:593-627` copies that directory into
   `app_data_dir/native-bin/llama-server`, i.e. the exact path `resolveCpuBaselineExe` checks.
   `branch-safety.md`'s "GPU-only by design — no CPU fallback" is worktree/dev-scoped, confirmed by
   `docs/tempdocs/656-…:1197-1199` ("Production bundling is untouched … Move 1 is strictly
   dev-scoped"). So: dev-only. The residual question 656 (`:1206-1214`) raises but does not
   decide — whether a CPU llama-server should exist in production at all — is owned by tempdoc 374
   (`status: open`). Settling evidence would be a run of the packaged NSIS installer confirming the
   copy-on-launch step succeeds.
6. **The two `addWatchedRoot` callers still disagree on "no collection supplied".**
   `IndexingController.java` leaves it `null` (the index default: `IndexingDocumentOps` writes the
   `collection` field only when non-blank, and the roots API *reports* such documents as
   `"default"`); `AddWatchedRootHandler` substitutes the literal string `"default"`, which may write
   a real `collection=default` term. Both now validate identically — D6 is closed — but the default
   itself is a second divergence, pre-existing and untouched here because changing it alters what
   gets written to the index. Owner: whoever holds the collection-vocabulary question (811's lane).
7. **`docs/tempdocs/374-app-packaging-and-distribution.md:233` cites stale line numbers** for
   `resolveVariantsRoot` (`:911-938`; it was `:1692-1720` before this PR and has moved again).
   Left alone deliberately: 374 is dated working history, and this PR moves the target once more,
   so a line-number edit would be stale on landing. Recorded here so the next reader of 374 knows.
