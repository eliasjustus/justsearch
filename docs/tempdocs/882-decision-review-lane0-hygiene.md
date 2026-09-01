---
status: IMPLEMENTED (branch ready; PR not yet opened)
created: 2026-09-01
updated: 2026-09-01
owner_session: 7471486e
follows:
  - 269-early-product-decisions-review.md (the only prior systematic ADR review, 2026-03)
  - 251-realistic-eval-framework.md §7 (Worker RPC deadline recommendation, 2026-03-02)
  - 347-gpu-env-var-propagation.md (config precedence diagnosis: Gradle configuration cache, not Windows)
  - 617 / 879 (store-recoverability register; the settings corruption policy)
  - 881-standard-profile-reasoning-exhaustion.md (context-window observations)
---

# 882 — Decision review, lane 0: hygiene sweep

**Thesis.** A 2026-09-01 review of every architectural decision that had not been re-examined
since it was made produced 26 items and split them into seven lanes. This is lane 0: the small,
mechanical, safe-to-do-now fixes that touch files every later lane branches from. It runs
**alone and first** so the later lanes (A config + context budget, B governance loop, C runtime
lifecycle, D index identity + migration, E search-quality re-derivation, F engine merge) do not
spend a week resolving merge conflicts against it.

Lane 0 makes no design decisions. Each item below is either a defect with a one-line fix, dead
residue whose reason has expired, or a doc that contradicts the code it describes. Anything that
needs a measurement, a reindex, a live LLM or a redesign belongs to a later lane and is out of
scope here even if it lives in the same file.

## Scope (contract)

From the review's numbering (kept so the lane tempdocs cross-reference cleanly):

| # | Item | Lane 0 does | Deferred to |
|---|---|---|---|
| 4 | `--enable-native-access` absent from both production JVM spawn sites | add the flag at both sites; delete the pre-Lucene-10 `--add-opens java.base/java.nio` line; drop `jdk.incubator.vector` from the jlink image | F (deletes one of the two sites) |
| 5 | gRPC size asymmetry + stale deadline | client `maxInboundMessageSize` = server's 32 MiB; raise the base deadline default as tempdoc 251 asked | F (per-RPC absolute deadlines, boundary rewrite) |
| 22 | config residue | delete dead yaml key groups + the `WorkerAi` record + its smoke-driver consumer; delete the "Windows env vars unreliable" pitfall row and its one non-tempdoc repeat | A (precedence javadoc, sysprop promotions, getenv funnel, key-has-reader gate) |
| 24 | ADR-0008 vs settings loader | back up the corrupt file, load defaults, raise a lifecycle condition, do not exit | — |
| 25 | ADR frontmatter | fix `status:` on the five ADRs whose frontmatter contradicts their body, and the README index | B (premise probes, risk register, amendments to 0015/0018/0039/0011) |
| 26 | small residue | retire the data-dir alias cluster; Head heap doc; reversed precedence javadoc; stale `rrf` default in a javadoc; MMF reserved-span constant + the Node reload literal | — |

Dropped after verification: the "telemetry NDJSON has no rotation" claim (it does; see item 26).

## Cross-lane rules (apply to every later lane; written here because lane 0 is the template)

- **File ownership is the contract.** A lane that needs a change in another lane's files sends a
  one-line request to the user, not a ride-along edit.
- **Lane 0 owns nothing after it merges.** Its edits are small enough that later lanes rebase over
  them; that is the whole point of running it first.
- **Each lane closes with an independent review**, since the `independent-review` gate was retired
  (tempdoc 563) and the implementing agent must not be the validating one.

## Evidence

Four read-only audits (sonnet, 2026-09-01) gathered `path:line` evidence per item; the
orchestrator re-read every load-bearing line before accepting it. Verdicts: TRUE = the review's
claim holds; PARTIAL = holds with a correction noted; FALSE = dropped from scope.

### Item 4 — native access

**TRUE.** Neither production spawn site passes `--enable-native-access`:
`modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerSpawner.java:449-588`
(`buildCommand()`) and `modules/shell/src-tauri/src/lib.rs:734-746`. Every site that does set it
is a Gradle test/run/dev task (`build-logic/.../JvmBaseConventionsPlugin.kt:115,149`,
`modules/ui/build.gradle.kts:1947` for the dev-only `runHeadless`, and the per-module test configs),
so production is the only tier that would break when the restriction becomes an error.

Live FFM downcalls (`Linker.nativeLinker()`): `modules/gpu-bridge/.../NvmlService.java:65,96`
(Head), `modules/ort-common/.../GpuDriverApiProbe.java:53-65` (both processes),
`modules/app-util/.../WindowsJobObject.java:125-126` and `WindowsPowerStatus.java:90-91` (Head, called
from WorkerSpawner). Correction to the review: the MMF signal bus uses `FileChannel.map(..., Arena)`,
not a downcall; it needs the FFM API but not native access. Immaterial to the fix.

**TRUE.** `WorkerSpawner.java:568-569` still passes `--add-opens=java.base/java.nio=ALL-UNNAMED` with
the comment "Enable MMapDirectory unmap hack". Lucene is pinned at 10.4.0
(`gradle/libs.versions.toml:9`), whose `MMapDirectory` uses the FFM `MemorySegment` provider and never
touches `java.nio` internals; the repo's own `305-hot-reload` tempdoc (line 322) says so, while
`docs/explanation/03-knowledge-server.md:249` still documents the flag as load-bearing. The Head
launch passes no `--add-opens` at all, which is the asymmetry that proves it is inert.

**PARTIAL.** `modules/ui/build.gradle.kts:964,977` puts `jdk.incubator.vector` into the jlink image,
and `WorkerSpawner.java:561-565` documents that no runtime enables it (JEP 514 AOT linking, tempdoc
269 §D4a). The only `--add-modules jdk.incubator.vector` is the benchmarks Gradle task, which does not
run from the jlink image. Dropping it from the image is a pure size saving with no behavior change.

No test pins the Worker argv flag list (six `WorkerSpawner*Test` classes cover other surfaces), and
the chaos harness `modules/system-tests/.../WorkerProcessManager.java:200-348` builds a second Worker
argv without either flag.

### Item 5 — gRPC sizes and deadlines

**TRUE.** Server: `KnowledgeServerGrpcWiring.java:30` `maxInboundMessageSize(32 * 1024 * 1024)`.
Client: `RemoteKnowledgeClient.java:317-325` builds the only production channel and never calls
`maxInboundMessageSize`, so grpc-java's 4 MiB default applies. No test asserts either value.

**PARTIAL.** `FetchDocuments` (`modules/ipc-common/src/main/proto/indexing.proto:303,327-337`) is
unary and unpaged, but the Worker already caps each document at 200 000 characters
(`GrpcSearchService.java:77,603`) and a paged sibling `FetchDocumentSlice` exists (`:305,343-349`).
A 4 MiB reply is therefore reachable at roughly 21 full-size documents in one call, which is the
defect the client limit fixes; paging the RPC itself is not needed in lane 0.

**TRUE.** `KnowledgeServerConfig.java:60,62,112-114`: `DEFAULT_DEADLINE_MS = 5000`,
`DEFAULT_MAX_RETRIES = 3`, escape hatch `JUSTSEARCH_WORKER_DEADLINE_MS` /
`justsearch.worker.deadline_ms`. `docs/tempdocs/251-realistic-eval-framework.md:866-868` recorded the
5 s deadline as the cause of circuit-breaker cascades on long documents and recommended 15 000 ms;
the default never moved. The circuit breaker trips after 3 failures with a 10 s cooldown
(`ipc-common/.../GrpcCircuitBreaker.java:39-40`). Correction: the multiplier table
(`RemoteKnowledgeClient.java:120-132`, `RpcDeadlineCategory`) has six categories, not five; replacing
it with per-RPC absolutes stays with lane F. `RemoteKnowledgeClientHealthDeadlineTest.java:43`
hardcodes `BASE_DEADLINE_MS = 5_000` and must move with the default.

The proto is not under `contracts/`; a shape change would trip `ipc-grpc-buf-breaking`
(`contracts/registry.v1.json:23-32`), not `--gate wire`. Lane 0 changes no proto.

### Item 22 — config residue and the pitfall row

**TRUE.** `search.rerank.enabled/k/reduced_k` are put into the resolver
(`ResolvedConfigBuilder.java:529-531`) and never resolved; the live reranker switch is
`justsearch.rerank.enabled` (`:1270`, `RerankerConfig.java:116`). The repo's own dead-config gate already
baselines them (`gates/config-surface/dead-config-baseline.txt`, with `search.facets.enabled` and
`search.hybrid.{bm25_k,ann_k,auto_embed}`), and the baseline's header says shrinking it is the point.

**PARTIAL.** `WorkerAi` (`ResolvedConfig.java:711`, built at `ResolvedConfigBuilder.java:1502-1507`)
has one reader: `modules/app-launcher/.../SmokeDriver.java:47` emits a
`LAUNCHER/WORKER_MISSING kind=ai` diagnostic when `workers.ai.enabled` is false. No AI worker exists
to be missing (the module was deleted 2026-03-29), so the reader is a diagnostic stub, and
`host/port/deadlineMs` are read by nothing. Delete the record, the builder, the yaml group and the
stub together.

**TRUE.** `app.timezone` has no reader. The sweep also found `app.package_base`, the whole
`telemetry.*` group, `plugins.*`, `index.language_detection.enabled`, and `index.export.otlp.*`
(self-described as dormant) unread, plus `search.pipeline.profile` / `index.pipeline.profile` yaml
values silently ignored because only the env/sysprop spelling is resolved
(`EnvRegistry.java:257`). The scanner cannot see keys that are never `put`. Lane 0 deletes the
never-put dead groups; the "every key has a reader" gate extension is lane A's.

**TRUE.** `CLAUDE.md:181` carries the pitfall row. `docs/tempdocs/347-gpu-env-var-propagation.md:20-38`
diagnoses Gradle configuration-cache capture plus per-call fresh shells, not Windows. Repeats: only
`scripts/docs/tempdoc-staleness-apply-manifest.mjs:88` (a reason string) outside dated tempdocs, which
stay as written. Removing the row shrinks CLAUDE.md, which the always-loaded-budget ratchet allows.

### Item 24 — settings file corruption

**TRUE, with a conflicting later decision.** `docs/decisions/0008-settings-ephemeral-defaults-safe.md:29`:
an incompatible `settings.json` "is silently replaced with defaults". `UiSettingsStore.load()`
(`modules/app-services/.../settings/UiSettingsStore.java:54-83`) throws `CorruptDurableStoreException`
on any parse or shape failure; `HeadlessApp.resolveConfig()` (`HeadlessApp.java:531`) does not catch
it, and `main()` (`:878-880`) logs and calls `System.exit(1)`. The test
`UiSettingsStorePersistenceModeTest.readWrite_malformedStateIsRetained` (`:338-348`) pins that
behavior by name.

That behavior is not an accident: `governance/store-recoverability.v1.json:19-36` (tempdoc 617,
extended by 879) registers `ui-settings` with `corruptionPolicy: FAIL_LOUD_AND_PRESERVE`, and the
gate forbids `SILENT_EMPTY` for an authored store (`check-store-recoverability.mjs:222-225`). So the
ADR and the register disagree, and lane 0 must pick one. The review's recommendation satisfies
both intents: rename the bad file to a timestamped sibling (the `SafeIndexPathOps.backupDirectory()`
pattern at `adapters-lucene/.../SafeIndexPathOps.java:34-49`), load defaults, and raise a lifecycle
condition so the user learns their preferences were reset. A desktop app that refuses to start over
a preferences file is the wrong trade; preserving the bytes and telling the user is the right one.
The register row gets a new policy value, `PRESERVE_AND_RECOVER_DEFAULTS`, and ADR-0008 is amended
to say "backed up and replaced, with a visible condition" instead of "silently replaced".

There is no `LifecycleReasonCode` for it yet (`modules/app-api/.../LifecycleReasonCode.java:17`;
nearest analog `WORKER_INDEX_CORRUPT` at `:55`, emitted via `KnowledgeServerBootstrap.java:751` into
`ConditionStore.upsert`). Adding one trips the `check-readiness-reason-codes` pre-merge row.

### Item 25 — ADR frontmatter

**TRUE, wider than stated.** All 45 ADRs have a frontmatter `status`. Contradictions between
frontmatter and body:

| ADR | frontmatter | body |
|---|---|---|
| 0010 | `stable` | line 11 "Status: Superseded (tempdoc 638)"; line 15 "Accepted" |
| 0013 | `stable` | line 13 "Partially superseded by ADR-0043 (2026-06-15)" |
| 0038 | `accepted` | line 21 "Accepted in principle ... superseded by tempdoc 564" |
| 0039 | `accepted` | line 21 same |
| 0041 | `accepted` | line 21 "superseded in part by tempdoc 564" |

`docs/decisions/README.md:73,76,101-104` is the index that must agree. Vocabulary in use: `stable`,
`accepted`, `superseded`, one `Superseded` (0012), one free-text `accepted - narrowed by ADR-0044`
(0026). No gate validates ADR status (`adr-coverage` checks code coverage only). Twenty `stable`
ADRs carry a bare `## Status` / `Accepted` body section from before the frontmatter migration; that
is vocabulary drift, not contradiction, and lane B's probe mechanism is the place to settle it.

### Item 26 — small residue

**TRUE.** Data-dir aliases `justsearch.data_dir` and `app.data_dir` are accepted in
`PlatformPaths.java:87-118`, set in `HeadlessApp.java:1339-1345`, `LauncherBootstrap.java:30-49`
and `Launcher.java:314-328`, declared deprecated in `contracts/platform-paths/spec.v1.json:10-11`,
exercised by `PlatformPathsContractTest.java:86-97` and `LauncherInternalsTest.java:31-78`, and
described in three docs (`02-process-coordination.md:266`, `06-configuration-ssot.md:100-101`,
`environment-variables.md:262`). The stated reason, "older logback templates", expired: both
`logback.xml` files read `justsearch.data.dir` (`modules/ui/src/main/resources/logback.xml:29,31`,
`modules/app-launcher/src/main/resources/logback.xml:3,5`). Nothing else in the repo sets either alias.

**TRUE.** `docs/explanation/01-system-overview.md:44` says the Head heap is "128MB-256MB";
`lib.rs:740` passes `-Xmx512m`; the dev-runner passes no `-Xmx` unless `JUSTSEARCH_HEAD_HEAP` is set
(`scripts/dev/dev-runner.cjs:713-727`).

**TRUE.** `EnvRegistry.java:22-31` says YAML outranks sysprop and env;
`ResolvedConfigBuilder.java:226-231` assigns sysprop 500, env 400, YAML 200, default 100, higher wins.

**TRUE.** `ResolvedConfig.java:777` documents `"rrf" (default)`; the default is `"cc"`
(`ResolvedConfigBuilder.java:506,1629`).

**FALSE, dropped.** Both telemetry NDJSON exporters rotate at 10 MB and prune at 7 days
(`NdjsonMetricExporter.java:53-54,416,438`, `NdjsonSpanExporter.java:162-163,317,355`; tempdoc 403).

**TRUE.** `scripts/dev/justsearch-dev-mcp/server.mjs:2825` writes byte 29 as a bare literal;
`MmfWorkerSignalLayoutV1.java:47-50` defines `OFFSET_RELOAD_SIGNAL = 29` inside a `RESERVED1` span that
starts at 25 and so also covers the header bytes 25-28. Lane 0 fixes the constant span and its
comment and makes the Node literal read a shared value; the MMF layer itself is lane F's to delete.

## Plan (approved 2026-09-01) and what shipped

Four workers on disjoint file sets plus the orchestrator; the alias sweep ran last because it
touches files the other workers owned.

| # | Shipped |
|---|---|
| 4 | `--enable-native-access=ALL-UNNAMED` at `WorkerSpawner.buildCommand()` and the Tauri `lib.rs` Head launch, and in both chaos-harness builders; the `--add-opens java.nio` line deleted; `jdk.incubator.vector` dropped from the jlink module list; `WorkerSpawnerJvmFlagsTest` pins the argv; `03-knowledge-server.md` bullet rewritten. |
| 5 | `GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES` (ipc-common) is now read by both `KnowledgeServerGrpcWiring` and `RemoteKnowledgeClient`; `DEFAULT_DEADLINE_MS` 5000 -> 15 000 with a `defaultDeadlineMs()` accessor the health-deadline test reads; `JUSTSEARCH_WORKER_DEADLINE_MS` documented. |
| 22 | Both `application.yaml` copies lose `app.timezone`, `app.package_base`, `workers.ai.*`, `search.rerank.*`, `telemetry.*`, `plugins.*`, `index.language_detection`, `index.export.otlp`; the `WorkerAi` record, its builder, its four `EnvRegistry` constants and the smoke-driver stub are deleted; the three `search.rerank.*` baseline pins retired with a dated history line; the CLAUDE.md pitfall row and the staleness-manifest reason string that cited it are gone. |
| 24 | `UiSettingsStore.load()` quarantines an unparseable file to `settings.json.corrupt-<stamp>`, loads defaults and records `lastRecovery()`; `save()` clears it and fires a callback. `HeadlessApp` publishes `SettingsRecoveryNotice` after the condition store exists (Phase 2) with `LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT` (STICKY, `Severity.WARNING`; the dotted code is the condition id, the reason is PascalCase because `AssertedCondition` validates it). The user sees it on the Health surface, which renders condition-store entries with the Java-side message; it is registered as `noWordingExempt` because it never rides the readiness envelope, so a `CAUSE_ROWS` row would be dead UI (the review caught a first draft that claimed otherwise). The quarantine record is snapshotted before `buildApi()` because the AI autostart seed can save settings before the substrate exists. Register row: `corruptionPolicy: PRESERVE_AND_RECOVER_DEFAULTS`. ADR-0008 amended. A future `schemaVersion` still fails loud. |
| 25 | 0010 `superseded`; 0013 / 0038 / 0039 / 0041 carry the ADR-0026-style free-text qualifier; the README index agrees. |
| 26 | Sysprop aliases `justsearch.data_dir` / `app.data_dir` retired everywhere (the YAML key `app.data_dir` is a different thing, a yaml-tier source that `ConfigWiringTest` exercises, and stays; the full suite caught a first draft that removed it too, and the launcher test now asserts the configured directory is created rather than relying on the env var outranking a dead alias) (resolver, three launch sites, Worker forwarding, effective-config reporting, chaos harness, contract spec, tests, docs); Head heap doc; `EnvRegistry` precedence javadoc; `rrf` javadoc; `OFFSET_RESERVED1_START` 25 -> 30 with `MmfWorkerSignalLayoutV1Test` pinning field disjointness and the Node script's write offset; `MmfWorkerSignalHeaderV1` javadoc no longer calls byte 29 reserved. |

Ride-alongs: the `store-recoverability-acquisitionstage-unclassified` expected-state pin was retired
because the gate is green on this base; `agent-postmortems.md` #28 (`shared-worktree-checkout`)
records a worker that ran `git checkout --` on sibling files in this worktree; a trailing-space fix
on a `config/application.yaml` comment line rode along with the key deletions.

Known consequence of item 25: `llmstxt-generate.mjs` indexes only `stable|in-progress|advisory`
statuses, so ADR-0013 (now `accepted - partially superseded by ADR-0043`) drops out of
`docs/llms.txt` exactly as ADR-0026 did under the same convention. Lane B's status-vocabulary work
is where that convention gets decided.

Live verification (dev stack launched from this worktree's dist, 2026-09-01): Head and Worker
command lines both carry `--enable-native-access=ALL-UNNAMED`, neither carries `--add-opens`, the
Worker receives only `justsearch.data.dir`, and backend stderr shows no native-access warning
(only the pre-existing protobuf `sun.misc.Unsafe` notice). `/api/health` reported Head and Worker
READY. With `ui/settings.json` written as `{not-json`, the Head booted, moved the file to
`settings.json.corrupt-20260901-204101` with the original bytes, logged the WARN, and the
`/api/health/events/stream` snapshot carried `settings.reset_from_corrupt` at WARNING with the
backup filename; the Health surface rendered that message in the browser; a `POST /api/settings/v2`
round-trip rewrote the file and the condition disappeared from the next snapshot.

Independent review (opus, reviewer != implementer) ran on the full diff before the branch was
declared ready; its four should-fix findings (boot-order snapshot, a tautological parity test, the
unreachable readiness row, three stale ownership-matrix rows) are folded in above.

## Open items

- `LauncherEnvironment.java:94` builds its own `UiSettingsStore`; it now gets defaults instead of a
  crash on a corrupt file but has no condition store to notify. The launcher is a smoke harness, not
  the Head, so this is acceptable; lane A's config work owns that file if it ever matters.
- The dead-config scanner still cannot see yaml keys that are never `put` (item 22 evidence); the
  "every yaml key has a reader" gate extension is lane A's.
