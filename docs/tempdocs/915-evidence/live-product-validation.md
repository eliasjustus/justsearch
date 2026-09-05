<!-- Sidecar of docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

## Live product validation (2026-09-03)

Independent live validation of PR #620 at head `12955fe9` (round 5), by a validator who is not the
implementer. Seven arms from the round-4 reviewer's written procedure, run against the **already
built** Head + Worker dists in this worktree — no rebuild, no production-code edit. Artefacts and
per-arm logs under `tmp/915-live/` (gitignored): `logs/<tag>-env.txt` (the exact env each arm
booted with), `logs/<tag>-worker.log`, `artefacts/<tag>-*.json`.

### Arm table

| Arm | Purpose | Verdict | Evidence pointer |
|---|---|---|---|
| 0 | Build Blue at a synthetic old fingerprint (`JUSTSEARCH_INDEX_VECTOR_HNSW_M=32`, prod) | **PASS** | `logs/arm0-worker.log`, `artefacts/arm0-api_status.json`; 205 docs, `indexSchemaFpStored = indexSchemaFpCurrent = 9814df37…`, `COMPATIBLE`, `state.json` IDLE with no `auto_rebuild_*` |
| 1 | Blue/green cutover as the prod default; the O7 headline | **PASS (6/8), 1 FAIL, 1 not verifiable** | `logs/arm1-worker-migration.log`; see D1 and D4 below |
| 2 | `FAIL_CLOSED` refuses without destroying anything | **PASS** | `logs/arm2-*`; `state.json` byte-identical (SHA-256 `E3BF2686…` before and after), 26 index files identical by name+size |
| 3 | Rebuild brake, read-only serving, `core.rebuild-index` recovery | **PASS (7/7)** | `logs/arm3-driver.log`, `logs/arm3-b4-recovery-worker.log`; two side findings D2, D3 |
| 4 | O7 deferred-open regression plus the negative re-boot | **PASS** | `logs/arm4b-worker.log` (PRE-OPEN on the 2nd boot), `logs/arm4c-worker.log` (0 PRE-OPEN, 0 PARITY_DIFF, 0 migrations) |
| 5 | Legacy (`39d38f73`) index upgrade: migrate once, clean 2nd boot | **PASS** | `logs/arm5-upgrade-worker.log` (`stored=<missing>`), `logs/arm5-second-worker.log` (0 PRE-OPEN) |
| 6 | B4 corrupt index (round-5 expectations) | **PASS** | `logs/arm6-corrupt-worker.log`; the Worker self-heals and starts — B4 is fixed live |

**Headline.** O7 is real on the boot path installs actually take. On an index **with segments** — the
`openDeferred()` path that pre-O7 swallowed the mismatch on — the pre-open detection fires and starts
the migration, in three independent shapes: a changed shape (arm 1, arm 4), a legacy index with no
recorded fingerprint (arm 5), and a refusal under `FAIL_CLOSED` (arm 2). The detector is not
trigger-happy: with the shape unchanged it emits nothing (arm 4c, arm 5 second boot).

Verbatim, arm 1 (`logs/arm1-worker-migration.log`), the O7 evidence line **before** the handler:

```
07:26:06.436 WARN PRE-OPEN PARITY_DIFF key=index_fingerprint stored=9814df37…3a3 expected=02353ae7…23b hint="The effective index shape changed (…). Reindex or run schema migration."
07:26:06.578 WARN Schema mismatch detected on active generation …\indices\g-20260903-052152. Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE, attempt 1 of 3)...
```

`FAIL_CLOSED` is refused from the **pre-open** site, not the open-time guard — the S14 distinction,
observed live (`logs/arm2-worker.log`): the stack is
`IndexMetadataParityGuard.schemaMismatch(IndexMetadataParityGuard.java:120)` under
`KnowledgeServer.start(KnowledgeServer.java:656)`, which is the pre-open throw, and the index is left
byte-identical.

**B4 and B5 are fixed, verified live.** Arm 6: an 8-junk-byte `segments_5` no longer kills the
Worker. `inspectCommittedParity` logs the non-fatal WARN and hands the question to the open, which
recovers:

```
07:48:07.282 WARN Could not read committed parity metadata at …\g-20260903-054724 (IndexFormatTooOldException: …)
07:48:07.344 WARN Corrupted index detected at …. Auto-recovery enabled, attempting backup-first rebuild...
07:48:07.453 WARN Index at … was recovered to empty (reason=corrupt_index). Rebuilding from source via blue/green...
```

`/api/health` reachable, no `worker-fatal-reason`, and the doc count returns to its pre-corruption
value (55) with search restored (`totalHits` 55 to 55). B5: the brake ladder (arm 3) produced
`auto_rebuild_count` 1, 2, 3 on three **live** Workers, none of which died; boot 4 hit the brake and
kept serving. No boot in the campaign wrote a `worker-fatal-reason` marker — not even the deliberate
`FAIL_CLOSED` arm.

**Fingerprint determinism.** The same config produced the same fingerprint across five independent
index builds and three data dirs: default → `02353ae765fd…`, `HNSW_M=32` → `9814df378e0e…`. The two
swap symmetrically depending on which side of the lever the index was built on.

### Defects

1. **D1 — mid-migration, `/api/status` reports `COMPATIBLE` / `reindex_required=false` where the
   procedure asserts `BLOCKED_MISMATCH` / `schema_mismatch` / `true`** (arm 1 assertion 5; the one
   FAIL). Observed twice, independently:
   - arm 1, 07:26:26, `migration.state=MIGRATING`, `buildingGenerationId=g-20260903-052606`:
     `indexSchemaFpStored=02353ae7…` (Green's, equal to current), `indexSchemaCompatState=COMPATIBLE`,
     `reindexRequired=false`, `embeddingCompatReason=NEW_INDEX_NO_FINGERPRINT`.
   - arm 4b, 07:40:54, mid-migration: `fpStored=9814df378e0e…` (again the current runtime's own
     fingerprint), `compat=COMPATIBLE`, `reindexRequired=False`.

   Cause, read at source: `IndexStatusOps.safeSchemaFingerprintStored()` (`IndexStatusOps.java:1120-1137`)
   reads the **ingest** runtime's commit user data. During a blue/green migration the ingest runtime
   is GREEN, freshly created and stamped with the CURRENT fingerprint, so `current.equals(stored)` at
   `:1162` yields `COMPATIBLE` and `isReindexRequired()` (`:1186-1194`) is false. Consequence: while
   the user's searches are answered from the stale-shape Blue, the compat surface says the index is
   compatible and needs no reindex; only `migration.state` / `migrationSource=schema_mismatch` carries
   the story. §C does not pin the mid-migration value, so this may be intended — but §C.13's argument
   for *not* propagating the deferred-upgrade mismatch rests on "the status surface already reports
   `BLOCKED_MISMATCH` with `reindex_required` from the same fingerprint comparison", and that sentence
   holds only while no migration is in flight. Either the assertion or that sentence needs correcting.

2. **D2 — in the exhausted-brake state, `/api/status` reports `worker.core.indexedDocuments = 5`
   while `searchableDocuments = 205` and `migration.activeIndexedDocuments = 205`** (arm 3). Stable,
   not a warm-up transient: 3 reads over 21 s and 8 further reads over 90 s, all `indexed=5
   searchable=205`. In the brake branch `ingestLifecycle == searchLifecycle == blue`, and
   `KnowledgeStatusView.java:87` fills `indexedDocuments` from `ks.docCount()`, i.e.
   `IndexCountOps.java:81` `searcher.getIndexReader().numDocs()`, which for Blue is 205. After the
   `core.rebuild-index` recovery the two agree again (206 == 206), so the disagreement is specific to
   the brake state. A surface that headlines "documents indexed" tells a braked user 5 when 205 are
   searchable.

3. **D3 — in the exhausted-brake state, `indexSchemaFpStored` is reported as the empty string**
   (arm 3), although the same boot's own PRE-OPEN line proves Blue's last commit carries
   `9814df37…`. It is on the wire in two places: `compatibility.indexSchemaFpStored` and
   `schema.fpStored`. The user-visible verdict is still right for the right reason —
   `safeSchemaCompatState()` (`IndexStatusOps.java:1139-1170`) tests the brake at `:1147` before it
   reaches the stored-fingerprint branches — but the empty value would drive the `stored.isEmpty()`
   to `BLOCKED_LEGACY` branch at `:1153-1160` if the brake check were ever reordered, or if the
   budget were cleared by hand, which the brake's own ERROR message tells users to do. A read-only
   open of Blue appears not to populate `openTimeCommitUserData`.

4. **D4 — arm 1 assertion 7 is not verifiable by the instrument it names.** `commit_by_reason` never
   contains `migration/cutover` for a fast migration. The worker metric snapshot cadence is 60 s
   (observed 05:22:52 / 05:23:52 / 05:24:52 in `telemetry/metrics-worker.ndjson`) and the cutover
   restarts the Worker about 20 s after the migration starts (`Migration cutover complete. Restarting
   worker to open new active generation...`), so the migration session's counters are discarded
   before any snapshot is written. `migration/switch-buffer-replay` is additionally not expected in
   this arm — `switchBufferDepth` was 0 throughout. Both emit sites are live production code
   (`KnowledgeServerMigrationOps.java:229` and `:793`); what is missing is a flush on the cutover
   restart, or a different instrument for the assertion.

### Observations (not defects, routed here for the owner)

- **The `FAIL_CLOSED` refusal does not reach the user as a schema mismatch.** Arm 2's Head reports
  `knowledgeServerStartError: "Worker process crashed (exit code 1) before writing port to signal
  file"` and readiness `workerControlPlane: worker.spawn_recovery_exhausted` /
  `indexServing: index.not_healthy`; `/api/health` is unreachable entirely. The `index_fingerprint`
  mismatch exists only in `worker.log`. `FAIL_CLOSED` is not the prod default, so this is a
  non-default path — but it is the path an operator who sets the policy lands on.
- **The braked ingest queue is unbounded and silent.** Arm 3: with ingestion stopped, the watcher
  re-enqueued the whole corpus (`pendingJobs = 200`), and one newly created file took it to 201,
  where it stayed for 90 s with `searchableDocuments` pinned at 205. No cap and no backpressure on
  the status surface. After recovery all 201 drained and the new file became searchable.
- **The cutover's own Worker restart is recorded as an unclean shutdown.** The next boot logs
  `Unclean previous shutdown detected at …\<green> — running FULL integrity verification`, so every
  blue/green cutover buys a full integrity verification on the following boot.
- **Duplicate WARN on the corrupt path.** `Could not read committed parity metadata …` is logged
  twice per boot (07:48:07.282 pre-open, 07:48:07.343 open-time guard) — one condition, two lines.
- **Pre-existing, NOT lane D:** every deferred-open boot over an existing index logs 6-8 of
  `Lucene health check failed: SearcherManager not available (runtime closed?)` (arm 4c, arm 5
  second boot); fresh-index boots log none (arm 0, arm 5 base). `SearcherBridge.java` is identical
  between `39d38f73` and `12955fe9`, so this predates the PR.

### Deviations from the written procedure

1. **Backend launcher.** The procedure specified jseval. `scripts/jseval/jseval/backend.py:260-262`
   boots `:modules:ui:runHeadlessEval` through Gradle, which would rebuild (contradicting the
   no-rebuild constraint) and, worse, pins the **eval** contract —
   `justsearch.ui.settings.mode=IN_MEMORY`, `justsearch.eval.mode=true`, and
   `JUSTSEARCH_CONFIG=modules/ui/src/main/resources/headless-config/application.yaml` — i.e. not the
   production config that carries `index.auto_recovery: true` and the prod schema-mismatch default
   this validation is about. Every arm therefore launched the already-built dist directly:
   `modules/ui/build/install/ui/bin/ui.bat` with `JAVA_OPTS=-Djustsearch.prod=true`,
   `JUSTSEARCH_PROD=true`, `JUSTSEARCH_CONFIG` **unset** (so `config/application.yaml` applies),
   models from the main checkout, ORT CUDA from the shared `tmp/ort-variant-test/cuda-12.4-v1.24.3`.
   Harness: `tmp/915-live/{boot,boot-base,stop,probe,api,arm3}.ps1`.
2. **Config reached the Worker JVM, proved per arm** (the [R1] discipline). Head side,
   `GET /api/debug/effective-config`: `justsearch.prod = true (source jvm_arg)`,
   `index.vector.hnsw.m = 32 (source env_var, detail JUSTSEARCH_INDEX_VECTOR_HNSW_M)`,
   `index.schema_mismatch.policy` unset so the prod default `BLUE_GREEN_MIGRATE` applies
   (`ResolvedConfigBuilder.java:1016`). Worker side, `worker-config-snapshot.json` plus
   `worker.log`: `Config: index.vector.hnsw.m=32 (worker_snapshot:…, ordinal=450)` and
   `Config: justsearch.prod=true (worker_snapshot…)`.
3. **The `justsearch_dev_*` MCP tools were not used** — they drive the dev-runner stack, not a dist
   boot. Hygiene was checked directly (`netstat` for 33221, `Win32_Process` for Head/Worker JVMs,
   `nvidia-smi`, game processes) before the campaign and after every arm.
4. **Arms 2 and 3 restored a byte copy of the arm-0 data dir** (`tmp/915-live/data-arm0-snapshot`)
   instead of re-running the arm-0 ingest. Deterministic and faster; the restored generation was
   verified identical by file name plus size.
5. **Arm 1 assertion 4 (search served from Blue mid-migration) was captured in arm 4b, not arm 1.**
   Arm 1's migration cut over in about 20 s and my query landed in the Worker-restart window
   (`Worker capability unavailable / worker.lost`). Arm 4b is the same code path under the same
   policy and gave the assertion cleanly: `servingSearchGenerationId = g-20260903-053945` (the
   pre-mismatch generation), `activeIndexedDocuments = 206`, `searchTotalHits = 147` — identical to
   the pre-migration baseline.
6. **Arm 3 assertion 6: the UI render was NOT exercised.** The Head dist was built with
   `-PskipWebBuild`. What was asserted is the API reason code that selects the string —
   `readiness.components.indexServing = { state: DEGRADED, reasonCode: index.rebuild_brake_exhausted }`
   — and that `readinessNotice.ts:399-404` carries the procedure's quoted wording verbatim, severity
   `warn`, remedy `operationId: 'core.rebuild-index'`.
7. **Arm 6 assertions were rewritten for round 5**, as instructed: the expectation is no longer "the
   Worker fails to start and writes `worker-fatal-reason`" but "the pre-open inspection warns
   non-fatally and the open's recovery runs". That is what happened.
8. **`core.rebuild-index` needs a two-phase confirm the procedure does not mention.**
   `POST /api/operations/core.rebuild-index/invoke` returns `CONFIRMATION_REQUIRED` (gate
   `TYPED_CONFIRM`, risk `HIGH`) with a `pendingId`; the recovery arm therefore ran
   `POST /api/authorizations/approve {pendingId}` and re-invoked with `confirmationToken`. Under prod
   every mutating call also needs `X-JustSearch-Session`, obtained from `GET /api/mcp/token`.
9. **The arm-5 base is legacy for the key under test, not "pre-fingerprint" in general.** `39d38f73`
   stamps `index_schema_fp` (`SsotCommitMetadataSource.java:93` at that commit) and its own
   `/api/status` reported `indexSchemaFpStored = 79566bb5…`. It carries no `index_fingerprint`, which
   is exactly what the PR's key needs, and the head's PRE-OPEN line confirmed it: `stored=<missing>`.

### Machine signature

`nvidia-smi`: NVIDIA GeForce RTX 4070, 12282 MiB total. **Before:** 819 MiB used, 0 % util, port
33221 free, 0 Head/Worker JVMs (2 Gradle daemons plus 20 compiler daemons from sibling worktrees),
0 game processes. **After:** 805 MiB used, 1 % util, port 33221 free, 0 Head/Worker JVMs, 0 game
processes. Every arm ran alone; the port was confirmed free between arms.

Timings (local, 2026-09-03): arm 0 ingest 07:21:52 to 07:23:03 (205 docs) · arm 1 boot to PRE-OPEN
07:26:06.436, cutover complete 07:26:25.924, new Worker up 07:26:59 · arm 2 boot to refusal
07:30:39.750 (sub-second) · arm 3 four-boot ladder 07:32:44 to 07:32:58, brake at 07:32:57.976,
recovery invoked 07:38:15 and cutover 07:39:13 · arm 4 seed 07:39:45 to 07:40:00, mismatch boot
07:40:30, cutover 07:40:58 · arm 5 base ingest 07:44:24 to 07:44:50, upgrade PRE-OPEN 07:45:17 ·
arm 6 corrupt boot 07:48:06, recovery 07:48:07.45, rebuilt 07:49:10.

### Re-validation on `403f4b30`

Second independent live pass, same validator, after lane D fixed D1-D4, the FAIL_CLOSED marker, the
cutover clean-shutdown marker, the duplicate WARN and O14. Dists were confirmed to carry the
round-6/7 code before any arm ran: `WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH =
"index_schema_mismatch"` and the `CleanShutdownMarker.wasClean` / `consume` split are both present
in the shipped `indexer-worker` jars (`javap` on the installed dist, not on the source tree).
Fingerprint determinism held across heads: the same two values as round 1 — defaults
`02353ae765fd…`, `HNSW_M=32` `9814df378e0e…`.

| Arm | What this head had to prove | Verdict | Evidence |
|---|---|---|---|
| 1 | D1 (stored fp = **Blue's**, `BLOCKED_MISMATCH`), D4 (`commit_by_reason`), and no unclean/full-scan on the cutover restart | **PASS** | `logs/r2arm1b-driver.log`, `artefacts/r2arm1-midmigration.json` |
| 2 | FAIL_CLOSED surfaces as `worker.index_schema_mismatch` + a `worker-fatal-reason` marker | **FAIL (Head half)** — marker half PASS | `logs/r2arm2-driver.log`, `logs/r2arm2b-driver.log` (arm 2c), defect **R1** |
| 3 | D2 (`indexedDocuments == searchableDocuments`), D3 (`fpStored` = Blue's), budget-cleared-by-hand, O14 five read-only boots | **PASS** | `logs/r2arm3-driver.log`, `logs/r2arm3b-driver.log`, `artefacts/r2arm3b-braked.json` |
| 4 | O7 on the deferred-open path, the negative re-boot, and search served from Blue mid-migration | **PASS** | `logs/r2arm4-driver.log` |
| 5 | Legacy `39d38f73` index: `stored=<missing>`, migrate once, silent second boot | **PASS** | `logs/r2arm56-driver.log`, `logs/r2arm5b-driver.log` |
| 6 | The unreadable-commit WARN is latched to ONE line (D.48) | **PASS** | `logs/r2arm56-driver.log`, `data-arm6/logs/worker.log.1` |

**D1 — fixed, confirmed twice in opposite directions.** Mid-migration, the compat surface now reports
the generation the user's searches actually reach. Arm 1 (09:08:39, Blue built at `HNSW_M=32`):

```
migrationState=MIGRATING  servingSearchGenerationId=g-20260903-070331  servingIngestGenerationId=g-20260903-070817
indexSchemaFpStored=9814df378e0e…   <- BLUE's, not Green's
indexSchemaFpCurrent=02353ae765fd…
indexSchemaCompatState=BLOCKED_MISMATCH  reindexRequired=True  reindexRequiredReason=schema_mismatch
readiness.indexServing = DEGRADED / index.schema_mismatch
indexedDocuments=768  buildingIndexedDocuments=768  searchableDocuments=1005
```

Arm 4b (09:21:49) is the same surface with the fingerprints swapped (Blue built at defaults), and it
also carries the assertion round 1 kept losing to the cutover-restart window — **search is served
from Blue throughout**: `servingSearch = g-20260903-072031` (= the pre-mismatch generation),
`activeIndexedDocuments = 1005` = N0, `searchHits = 100` = the pre-migration baseline. The
`indexedDocuments` / `searchableDocuments` split (768 vs 1005) is the intended one — "documents
indexed" counts the generation being written, "searchable" counts what a query can reach — which is
what §F G43 pins in the other direction.

**D4 — fixed.** `commit_by_reason` read from `<dataDir>/telemetry/` after arm 1 now contains
`"migration/cutover": 1`. `migration/switch-buffer-replay` is absent and correctly so:
`switchBufferDepth` was 0 for the whole cutover, so that reason never fired.

**Cutover restart — fixed.** The worker the cutover restarts logs `unclean=0 fullscan=0` in arm 1 and
again in arm 4b. Round 1's `Unclean previous shutdown detected at …\<green> — running FULL integrity
verification` is gone.

**D2 / D3 — fixed.** Braked state (arm 3b, worker connected, stable at t+20 s):

```
indexedDocuments=1005  searchableDocuments=1005  activeIndexedDocuments=1005   (round 1: 5 vs 205)
indexSchemaFpStored=9814df378e0e…                                             (round 1: "")
indexSchemaFpCurrent=02353ae765fd…  indexSchemaCompatState=BLOCKED_REBUILD_BRAKE
reindexRequired=True  reindexRequiredReason=rebuild_brake_exhausted
readiness.indexServing = DEGRADED / index.rebuild_brake_exhausted
servingSearchGenerationId=g-20260903-070331  migrationState=IDLE  buildingGenerationId=
worker.log: brakeLine=1  ingestStopped=1
```

The four-boot ladder itself was run for real first and produced `auto_rebuild_count` 1 → 2 → 3 on
three live Workers, then the brake on boot 4 with `count=4`, `migration_state IDLE`, no building
generation.

**Budget cleared by hand — PASS.** With `auto_rebuild_*` removed from `state.json`, the next boot
migrates as a MISMATCH, not as a legacy index — the PRE-OPEN line carries `stored=9814df378e0e…` and
the *changed-shape* hint (`"The effective index shape changed (field catalog, analyzers, vector
format/dimension, HNSW build params, chunking, or an embedding/SPLADE model)…"`), not
`index-without-fingerprint`, and it restarts at `attempt 1 of 3` with `auto_rebuild_count: 1`.

**O14 — PASS.** Five consecutive read-only (braked) boots of the same Blue: `unclean=0 fullscan=0` on
every one, and `g-20260903-070331.clean-shutdown` still present on disk after the fifth. This is only
testable because the harness now stops the product the way a user does (see deviation 1).

**Arm 5 — PASS.** `PRE-OPEN PARITY_DIFF key=index_fingerprint stored=<missing> expected=02353ae765fd…
hint="index-without-fingerprint: this index carries no recorded index_fingerprint, so its physical
shape cannot be verified. Rebuilding once records it."` then `attempt 1 of 3`; one cutover
(`active g-20260903-072854 → g-20260903-072938`); second boot `preOpen=0 parityDiff=0 blueGreen=0
unclean=0 fullscan=0`, `COMPATIBLE`, `reindexRequired=False`, 205 docs.

**Arm 6 — PASS, single WARN.** `unreadableCommitWarns=1` (round 1: 2), then
`Corrupted index detected … attempting backup-first rebuild` → `recovered to empty (reason=corrupt_index).
Rebuilding from source via blue/green`. Worker starts, no `worker-fatal-reason`, doc count back to 55
and search back to 55 hits.

#### Defect

**R1 — `worker.index_schema_mismatch` never reaches Head readiness under FAIL_CLOSED. The Worker
half of D.46 works; the Head half does not.**

*Worker half PASS.* `<dataDir>/worker-fatal-reason` is written and contains exactly
`index_schema_mismatch` — read off disk at 09:10:02.007 (arm 2) and again at 09:14:13.301 (arm 2c).

*Head half FAIL.* The complete `readiness.components.workerControlPlane.reasonCode` sequence across
the whole supervision arc (boot 09:13:39, watched to a terminal state that then held unchanged for
85 s):

```
09:13:41.361  NOT_READY  worker.spawn.failed
09:13:52.132  DEGRADED   worker.recovering
09:16:06.475  NOT_READY  worker.spawn_recovery_exhausted     <- terminal, stable through 09:17:31
```

`worker.index_schema_mismatch` never appears, in any component, at any point. `/api/health` returns
503 throughout and `knowledgeServerStartError` stays
`"Worker process crashed (exit code 1) before writing port to signal file"` — the exact string
D.46's own test comment quotes as the thing it set out to replace. The first narrated verdict already
carried the generic code: `HeadlessApp.java:548` logged
`Knowledge Server failed to start: … (worker reason: worker.spawn.failed) — boot recovery armed` at
09:13:41.256, while the marker was on disk.

*Mechanism, from source — the implementer's to pin with a test, not asserted here.*
`KnowledgeServerBootstrap.transitionWorkerDown` (`:787-809`) calls `workerDownCode` at `:788`, which
calls `WorkerFatalReasonMarker.readAndClear` at `:759` — consuming the one-shot marker — and then
returns early **without applying the verdict** at `:798` (`narrationSuppressed()`, true while "a retry
or recovery arc owns it", i.e. for the whole boot-recovery ladder) or at `:805`
(`supervisionVerdictHeld()`, whose carve-out at `:800` reads
`down.code() != LifecycleReasonCode.WORKER_INDEX_CORRUPT` — `WORKER_INDEX_SCHEMA_MISMATCH` is not
carved out). Once the marker is consumed no later call can recover the cause, which is precisely the
hazard the corruption axis has a latch for.

*Why the round-6 test is green.* `KnowledgeServerWorkerDownCodeTest` has
`corruptCauseSurvivesTheSupervisionSequence` — an end-to-end latch case over restart-then-give-up —
for the corruption axis, but `schemaMismatchMarkerOverridesTheGenericCode` calls
`transitionWorkerDown` exactly once on a fresh bootstrap, so it meets neither guard. This is the same
`substrate-without-consumer` shape §F G49 was added to catch, one level further down: G49 pinned the
*mapping*, nothing pins the *arc*. `HeadlessApp.java:540-542`'s own comment corroborates the missed
sweep — it still enumerates the reachable codes as "worker.spawn.failed, worker.index_corrupt, or
supervision's terminal worker.restart_exhausted", with the round-6 addition absent.

*What arm 2 did pass:* `state.json` byte-identical across the arm (SHA-256 `F84DDB07…` before and
after), only the Blue generation on disk, `preOpen=1`, `failedStart=1` — FAIL_CLOSED still refuses
without touching anything, exactly as in round 1. The `readinessNotice.ts:282-285` wording row for
`worker.index_schema_mismatch` exists; it is simply unreachable.

#### Deviations from the round-1 harness and the written procedure

1. **`stop.ps1` now uses the product's ordered shutdown** (`POST /api/lifecycle/shutdown`,
   `LifecycleApiModule`) with `taskkill` only as `-Hard`. Round 1 used `taskkill` throughout, which is
   a crash by definition: it left every generation dirty, which made round 1's "the post-cutover
   reboot logs unclean" observation un-interpretable and would have made O14 untestable — the clean
   marker never exists after a kill. Arms that want an unclean stop (arm 2, and the sweeps between
   arms) pass `-Hard` explicitly.
2. **Arms 1 and 4 use a new deterministic 1000-file corpus** (`tmp/915-live/corpus1000`). At 200 files
   the migration completes in 20-40 s while `/api/status` first answers at ~55 s, so the
   mid-migration surface — the entire object of the D1 assertion — is not observable. Arms 2 and 3
   reuse that 1000-doc Blue through a byte snapshot; arms 5 and 6 keep the 200- and 50-file corpora.
3. **Arm 1's mid-migration capture was taken by hand from the session** at 09:08:39, after the
   driver's own readiness check proved too loose (it accepted an `/api/status` that answers before the
   worker payload exists). Arm 4b then captured the same surface unattended at 09:21:49, so D1 rests
   on two independent observations rather than one hand-taken sample.
4. **Arm 3's braked compat capture was re-taken** (`r2arm3b`) by seeding `auto_rebuild_count: 4`
   directly into `state.json` — the same fixture `BrakeExhaustedWorkerServesReadOnlyTest` uses —
   because the first capture landed before the worker was connected and read all zeros. The
   four-boot ladder itself was still run for real, and its counts are the ones reported.
5. **Arm 5's `stored=<missing>` line was re-captured** (`r2arm5b`) by tailing `worker.log` while the
   migration ran. The first pass lost it: the cutover-restarted Worker truncates `worker.log` before
   the driver copies it.
6. **Arm 2 needed the full supervision arc.** The first two passes sampled readiness at t+2 s and
   t+60 s and would have reported a transient (`worker.spawn.failed`, `worker.recovering`) as the
   verdict. Only the third pass, watching to a terminal code that then held for 85 s, is the evidence
   quoted above.
7. **The UI render is still not exercised** (`-PskipWebBuild`). Reason codes are asserted on the API;
   wording is read from `readinessNotice.ts`.
8. **Machine contention, disclosed rather than hidden.** A Riot Client / League / TFT / Overwolf stack
   started at 09:26:39, overlapping arm 6 (09:26:49-09:28:44) and the arm-5 re-capture
   (09:28:52-09:30:55). Every assertion in those two arms is a log string, a document count or a
   `state.json` field — none is a throughput measurement — so the contention does not bear on their
   verdicts.
9. **The Head's own log is not in the arm's data dir.** `logback.xml:29` resolves the log path from
   the **sysprop** `justsearch.data.dir`, which this harness does not set (it sets the env var), so
   Head logs land in `build/headless-data/logs/headless-backend.log` at the worktree root. That file
   is where the `HeadlessApp.java:548` line quoted in R1 was read.

#### Machine signature

`nvidia-smi`: NVIDIA GeForce RTX 4070, 12282 MiB total. **Before (09:03):** 902 MiB used, port 33221
free, 0 Head/Worker JVMs, 0 game processes. **After (09:31):** 2180 MiB used, 23 % util, port 33221
free, 0 Head/Worker JVMs, 3 game-client processes (deviation 8). Arms ran one at a time; the port was
confirmed free between arms.

Timings: arm 0'' ingest 09:03:29 → 09:08:08 (1005 docs) · arm 1 PRE-OPEN 09:08:17.651, mid-migration
capture 09:08:39, cutover 09:08:33 → settled 09:09:32 · arm 2 boot 09:13:39 → terminal readiness
09:16:06 · arm 3 ladder 09:18:15 → brake 09:18:27, O14 boots 09:18:59 → 09:20:10, cleared-budget boot
09:20:14 · arm 3b 09:23:36 → 09:24:20 · arm 4 09:20:29 → 09:23:16 · arm 5 09:24:31 → 09:26:42, arm 5b
09:28:52 → 09:30:55 · arm 6 09:26:49 → 09:28:44.

### Arm 2 re-run on `c06d8b25`

R1 is fixed. Dist verified first: `BootRecoveryDecision$Veto` in the shipped Head jar carries
`NONE, SUPERVISION_ENGAGED, RESTART_EXHAUSTED, INDEX_FATAL` (`javap` on
`modules/ui/build/install/ui/lib/app-services-0.2.0.jar`, not on the source tree). Same 1000-doc
Blue at `HNSW_M=32` as the round-2 pass, booted with `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY=FAIL_CLOSED`.

| # | Assertion | Verdict | Evidence |
|---|---|---|---|
| A1 | terminal readiness is `NOT_READY worker.index_schema_mismatch`, not `spawn_recovery_exhausted` | **PASS** | reached 10:15:22.072 (t+2.6 s), unchanged for the whole 200 s watch to 10:18:39 |
| A2 | the policy remedy is carried | **PASS** | verbatim in `knowledgeServerStartError` and in the Head log; see the note below on where it does *not* appear |
| A3 | `/api/health` payload names the mismatch | **PASS** | `HTTP 503` + 419-byte body: `lifecycle.reason_code = worker.index_schema_mismatch` **and** `components.worker.reason_code = worker.index_schema_mismatch` |
| A4 | `knowledgeServerStartError` names the mismatch | **PASS** | the full remedy sentence, quoted below |
| A5 | the fatal-reason marker is on disk | **PASS** | `worker-fatal-reason` = `index_schema_mismatch`, observed 10:20:07.192 at a 50 ms poll |
| A6 | the ladder gives up early instead of burning the budget | **PASS** | `spawnAttempts=1`, `bootRecoveryReattempts=0` |
| A7 | the index is still untouched | **PASS** | `state.json` SHA-256 identical; only `g-20260903-070331` + its `.clean-shutdown` on disk |
| A8/A9 | the operator path re-opens the `INDEX_FATAL` give-up | **PASS** | `POST /api/worker/restart` → `HTTP 202 {"recovery":"ACCEPTED"}`, followed by a new spawn |
| A10 | with the policy flipped, the migration starts | **PASS** | `PRE-OPEN PARITY_DIFF` → `Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE, attempt 1 of 3)`, `auto_rebuild_count: 1` |

**A1/A4 — the refusal now reaches the user as itself.** Round 2 produced
`worker.spawn.failed → worker.recovering → worker.spawn_recovery_exhausted` over ~150 s, with
`knowledgeServerStartError = "Worker process crashed (exit code 1) before writing port to signal
file"`. At this head, 2.6 s after boot:

```
readiness.components.workerControlPlane = NOT_READY / worker.index_schema_mismatch
readiness.composites.retrieval          = NOT_READY / [worker.index_schema_mismatch, index.not_healthy, lambdamart.not_configured]
knowledgeServerStartError = "The search index was built with a different index shape than this
  version writes, and index.schema_mismatch.policy=FAIL_CLOSED refuses to rebuild it. Set the policy
  to BLUE_GREEN_MIGRATE to rebuild alongside the existing index, or rebuild the index yourself."
```

**A6 — the ladder short-circuits.** One `Spawning worker:` for the whole boot, zero boot-recovery
re-attempts, and the veto is narrated rather than silent (Head log, UTF-8):

```
10:15:20.313 INFO  Spawning worker: …                                   <- the only one
10:15:21.537 WARN  Knowledge Server failed to start: The search index was built with a different index shape … (worker …
10:15:31.538 ERROR Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch — the condition is on disk, so re-spawning would read the same bytes and refuse the same way
```

Round 2 burned `Boot recovery: re-attempting … (1/4)`, `(2/4)`, `(3/4)` with 10/20/40 s backoff before
landing on `worker.spawn_recovery_exhausted` ~150 s in.

**A8/A9 — the operator exemption is reachable over HTTP and works.** The path is
`POST /api/worker/restart` (`InferenceRoutes.java:26` → `InferenceHandlers.handleRestartWorker` →
`routeToRecoveryAuthority` → `KnowledgeServerHealthMonitor.requestRecoveryNow()` →
`currentRecoveryInput(true)`, which withholds the `INDEX_FATAL` veto and clears the latched give-up
for that veto only). Observed:

```
10:18:40.379  POST /api/worker/restart -> HTTP 202 {"recovery":"ACCEPTED","success":true}
10:18:40.367  Boot recovery: re-attempting Knowledge Server start (1/4)     <- the give-up re-opened
10:18:40.370  Spawning worker: …
10:18:41.488  Boot recovery attempt 1 failed: … crashed (exit code 1) …
10:18:41.558  Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch …
```

The re-spawn refuses again and re-latches, which is correct: the policy had not changed yet.

**A10 — the remedy.** `index.schema_mismatch.policy` is forwarded to the Worker as a `-D` at spawn
from the Head's boot-time resolved config (`WorkerSpawner.WORKER_FORWARDED_PROPS`), so flipping it
needs a new Head process — the operator exemption re-opens the *give-up*, it cannot change the
*policy*. Rebooted with `BLUE_GREEN_MIGRATE`: `PRE-OPEN PARITY_DIFF key=index_fingerprint
stored=9814df378e0e… expected=02353ae765fd…` then `Starting Blue/Green migration (policy=BLUE_GREEN_MIGRATE,
attempt 1 of 3)`, `building_generation g-20260903-081908`, `auto_rebuild_count: 1`.

#### Residual finding

**R2 — after an operator-requested retry that re-refuses, readiness is left on the transient
`worker.recovering` instead of re-latching the terminal code.** Reproduced twice: after
`POST /api/worker/restart` the component goes `DEGRADED / worker.recovering` and stays there —
observed continuously for 120 s (10:21:05 → 10:23:05) and again 25 s after the first request — even
though the Head has already narrated
`Boot recovery declining to re-attempt: the worker refused with worker.index_schema_mismatch` at
ERROR. `knowledgeServerStartError` keeps the remedy sentence throughout, so the user is not left
without the cause, but the readiness component — the surface `readinessNotice.ts` renders — reports
"recovering" indefinitely for a condition that by the Head's own reasoning will never recover on its
own. The latch works for the automatic ladder (A1); it is the operator-requested arm that leaves the
transient in place. Scoped as a follow-up, not a re-opening of R1.

#### Corrections to my own round-2 measurements

- **`/api/health` was never empty.** Round 2 recorded "unreachable (503)" and this round's first pass
  recorded an empty body; both were my probe, not the product — `Invoke-RestMethod` throws on 503 and
  my error-stream read returned nothing. `StatusLifecycleHandler.java:1137-1141` does
  `ctx.status(503).json(snapshot)`, and `curl -i` shows the 419-byte body quoted in A3. The round-1/2
  claim that the FAIL_CLOSED refusal "reaches the user only in worker.log" was therefore wrong about
  `/api/health` specifically; it was right about the *content*, which named a spawn crash rather than
  the refusal until this head.
- **A2, where the remedy is not.** `ReadinessComponentView` (`:12-14`) has fields
  `state, reasonCode, source, observedAt, stale, …` and no detail field at all, so the remedy sentence
  is not on the readiness component by construction. It is carried by `knowledgeServerStartError`
  (D.55's `startErrorFor`) and by `readinessNotice.ts`'s own wording row. Not a defect; my first probe
  looked for a field that does not exist.

#### Out of scope, pre-existing

`LifecycleSnapshotTap: no mapping for dim=WORKER_CONTROL_PLANE state=NOT_READY
reasonCode=worker.index_schema_mismatch; preserving any prior assertion (unknown reason ≠ healthy)`
fires on every boot of this arm. The same WARN fired in round 2 for `worker.starting` and
`worker.recovering`, so the tap's mapping table is generically incomplete rather than newly broken by
the round-8 code — but the new code does inherit the gap.

#### Machine signature

RTX 4070, 12282 MiB. Before: 976 MiB used, 2 % util, port 33221 free, 0 Head/Worker JVMs. After: port
33221 free, 0 Head/Worker JVMs. Riot/League client processes were resident but idle throughout
(GPU at 2 %); every assertion in this arm is a log string, an HTTP status/body or a `state.json`
field, none is a throughput measurement. Boots: 10:15:19 (main arc), 10:20:05 (marker + health +
operator follow-up), 10:23:35 (`/api/health` re-measurement).
