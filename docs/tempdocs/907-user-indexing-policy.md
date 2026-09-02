---
title: "User indexing policy: pause, pause-on-battery, schedule window — as inputs to the pacing gauge, never a second pacing authority"
type: tempdocs
status: "CHARTERED (2026-09-02) — design settled; not started; gauge-wiring chunk gated on the L10 start condition (885 live arms recorded + 813 finding 3 settled)"
created: 2026-09-02
updated: 2026-09-02
lane: 887 L10 (register item 6.4)
model: fable (design) → opus (implementation)
parent: 887-improvement-landscape-register
coordination: "→ founder lane C (decision-review-lane-c-runtime-lifecycle-and-isolation) owns the pacing authority: ForegroundLoad, IndexingPacing, the duty cycle, the extraction pool. This lane adds ONE input to that authority and edits none of it before the start condition in §I. → background-citizenship (L7) adds OS-level levers UNDER the same authority; the two must not both re-time the loop. → failure-ux-coherent-surface (906, chartered in parallel) owns the WORDING table for every reason code named here; this lane owns the structure and the vocabulary, not the sentences."
related:
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # decision 3 (:227), baseline (:465), live arms (:2951), duty counter (:555)
  - 813-indexing-progress-queue-ux-design    # finding 3 cancel honesty (:98, :230), the phase model (:131)
  - 630-os-sleep-resume-robustness           # EnergyState / WindowsPowerStatus / ResumeDetector
  - 896-background-citizenship               # OS priority + low-disk, sibling input under the same authority
  - 898-inference-runtime-residuals          # names battery-aware indexing pacing as deferred to this lane
  - 600-degradation-cause-not-observable     # remedy honesty + closed reason-code vocabulary + gate pattern
  - 883-config-ordinal-chain                 # a user preference rides the chain as an input, never a promotion
---

> Design tempdoc. §C corrects the premise and records the start condition, §F is evidence,
> §D decisions, §A the design, §P reach, §O orphans, §I opus chunks, §K owner confirmations,
> §Z routed one-liners.

# 907 — User indexing policy

## §C. The premise, and the start condition

The register deferred this lane with a two-part trigger. Both parts were checked in this worktree:

- **"885 §Status live arms recorded" — SATISFIED.** The lane-C tempdoc's frontmatter status reads
  "IMPLEMENTED pending #602 merge — lane C closed. Items 14, 3, 6 and 21 done and live-verified",
  and the arms are in the file: the pre-change baseline table (`885:501-515`) and the after table
  (`885:2956-2966`), with the acceptance read-out at `885:2967-2979`. Under continuous search load
  the loop went from **699 of 5184 documents in 22 minutes** to **all 5184 indexed**, duty floor
  held at **20-27%**, `paced_intervals_total` **16 117** contended against **325** unloaded.
- **"813 finding 3 settled" — PARTIALLY.** The cancellation *seam* landed: `813:528-529` records
  "both Lane-1 fixes (#374 backfill yield/abandon — finding 3's cancel/budget)" as merged, and
  `core.cancel-indexing-job` ships (`CoreOperationCatalog.java:103,526-549`). What 813 §7
  (`:241-243`) asked for and **is still absent** is the UI contract: "a bounded, stated worst-case
  latency between 'user cancels/removes' and 'GPU work for that scope stops', and a queryable
  'stopping' state". There is no `stopping` arm anywhere in `modules/ui-web/src/shell-v0`
  (grepped; zero hits outside unrelated prose), and `IndexingPhase` is
  `'indexing' | 'enriching' | 'blocked' | 'ready' | 'unknown'`
  (`indexingProgress.ts:33`). **That missing contract is exactly what this design supplies for the
  pause case** — it is not a new deferral trigger, it is the deliverable.

The start condition is therefore carried **verbatim on chunk C3 only** (§I). C1, C2 and the
settings/store/vocabulary work start now. Nothing user-visible ships before C3
(`standalone-capability-stays-stuck`: a control wired to nothing is the defect this lane removes,
not a milestone).

## §F. What exists (verified in this worktree, `worktree-887-publish`)

- **The pacing authority is one type with one entry point.** `IndexingPacing.pace()`
  (`IndexingPacing.java:160`) is the throttle; `foregroundBusy()` (`:147`) is the gate;
  `ForegroundLoad` (`ForegroundLoad.java:26`) is the gauge. Call sites: `IndexingLoop.java:728`,
  `BackfillScheduler.java:246,437,622`, `JobBatchExtractor.java:136`, `SyncDirectoryOps.java:301`
  and the two abort-checker slots (`SyncDirectoryOps.java:226`, `GrpcIngestService.java:1093`).
  **Every one of those is already a drain point**, which is why this design needs no new call site.
- **`isUserActive` is gone.** `MmfWorkerSignalBus` no longer declares it (grepped: the only
  remaining signal read is `isEnergyReduced()` at `MmfWorkerSignalBus.java:216`).
- **A battery input already reaches indexing — but only enrichment, and only the energy-saver
  flag.** `LoopPacingPolicy.shouldRunBackfill(mainGpuActive, energyReduced, provider)`
  (`LoopPacingPolicy.java:53-59`) defers GPU *and* CPU backfill while the OS energy-saver flag is
  set; primary indexing (extraction + Lucene write) is not gated by energy at all. The 887 row and
  898's coordination line ("only the VDU consumer exists today") are imprecise: `EnergyState` the
  Head type has one consumer (`VduPacingPolicy.java:42`), but the *signal* reaches the Worker
  backfill through the MMF byte.
- **Battery-vs-AC is available and distinct from energy-saver.**
  `WindowsPowerStatus.toEnergyState` (`:63-79`) derives `Intent.REDUCED` from the energy-saver flag
  and `Source.BATTERY` from `ACLineStatus == 0`. Only `Intent` crosses to the Worker today;
  `Source` does not. `EnergyState.Intent.UNKNOWN` is documented as "never throttle" (`:25-26`).
- **No user pause/resume for ingest exists.** `IndexingRoutes.java:23-24` is *migration*
  pause/resume (`PauseMigration`/`ResumeMigration`, `indexing.proto:1284-1285`, surfaced as
  `migration_paused` + `pause_reason`, `status.proto:462-463`) — a different subject, and the wire
  precedent this design copies. `CoreOperationCatalog` has no `core.indexing-pause`.
- **`LoopState.PAUSED` exists and already means something else.** `IndexingLoop.java:175,1051`
  and its comment at `:721-724`: PAUSED is reported "only for the duration of the yield". Metrics:
  `worker.indexing.paused`, `worker.indexing.duty_pct`, `worker.indexing.paced_intervals_total`,
  `worker.indexing.foreground_in_flight` (`WorkerOpsMetricCatalog.java:110,123,126,129`).
- **`pauseIndexingDuringAi` is a lever wired to nothing.** Declared in the schema
  (`SSOT/schemas/settings-v2.v1.json:67`, dual copy under `modules/ui/src/main/resources/`),
  the record (`UiSettingsV2.java:16`), the POJO (`UiSettings.java:33,141-147`), the generated TS
  (`settings-v2.ts:33,58`), a live fixture and `SettingsSurface.ts:140`. The **only** reader is
  `SettingsController.java:138`, echoing it back on GET. No indexing code reads it, and no control
  renders it. 15 files carry a promise the product does not keep.
- **Settings are a registered AUTHORED durable store.** `governance/store-recoverability.v1.json`
  row `ui-settings`: `ui/settings.json`, HEAD-owned, `AUTHORED`, `READ_IN_PLACE`, `currentVersion`
  1, `PRESERVE_AND_RECOVER_DEFAULTS`, authority `UiSettingsStore.java`. Settings contribute to the
  config chain at ordinal 300 via `ConfigStoreRebuilder.contributeUiSettings` (`:94`), which is
  re-run on runtime settings changes.
- **Config keys are ratcheted.** The `config-surface` gate (`governance/registry.v1.json`) is a
  knob-regrowth ratchet with a changeset cost. The two pacing keys reach the Worker as a
  *start-time* config snapshot at ordinal 450 (`ForegroundPacingConfigForwardingTest:24-27`) —
  there is **no live config push RPC** (full RPC list read from `indexing.proto:300-1701`).
- **Observability paths are already open.** `/api/debug/state` copies the whole worker status
  snapshot into its `worker` node (`DebugStateController.java:108-113`), so anything added to the
  `IndexStatus` wire appears there for free. Readiness reason codes are a closed enum
  (`LifecycleReasonCode.java`) gated forward/backward/producer against the FE `CAUSE_ROWS` table
  (`governance/readiness-reason-codes.v1.json`, `readinessNotice.ts`). MCP `justsearch_status`
  prints state/ready/documents/queueDepth/coverage (`McpToolSurface.java:1341-1375`) — it cannot
  say "paused", so an agent that calls `justsearch_ingest` against a paused index waits forever.
- **Resume from OS sleep has an owner.** `ResumeDetector` + the health monitor's eager
  re-validation (630 "Latency-hardening slice"); the tempdoc's whole subject is wall-clock logic
  going wrong across a suspend.

## §D. Decisions

| # | Decision | Grounds |
|---|---|---|
| D1 | **One user policy record, three arms:** `indexing.userPolicy = { paused: bool, pausedUntilMs: long-or-null, pauseOnBattery: bool, schedule: { days[], startLocal, endLocal }[] }`. An empty `schedule` means "always allowed"; a window declares when indexing MAY run and may cross midnight. | The register item names exactly these three levers. One record, one write path, one precedence table. |
| D2 | **All defaults OFF** (`paused:false`, `pausedUntilMs:null`, `pauseOnBattery:false`, `schedule:[]`). | 885 measured and shipped the automatic duty cycle as the behaviour; a default-on user lever would change measured behaviour without a measurement, and would double-throttle against the existing energy-saver defer at `LoopPacingPolicy.java:55`. |
| D3 | **AUTHORED user state in the existing `ui-settings` store**, as a new optional object in `settings-v2.v1.json` — no new store row, no version bump (an optional nested object is backward-compatible under `READ_IN_PLACE`), and **no new `EnvRegistry` key**. | It is a user preference, not an operator knob; adding three knobs would spend `config-surface` ratchet budget on the wrong classification (883 decision 4's lesson: user values ride the chain at 300). |
| D4 | **The Worker owns the arithmetic; the Head owns the inputs.** A new `SetIndexingPolicy` RPC on `IndexingService` carries the policy record plus the two Head-owned facts the Worker cannot see (`on_battery` from `EnergyState.Source`, and the resolved wall-clock instant is the Worker's own). Pushed on settings change and idempotently on the existing `KnowledgeServerHealthMonitor` tick, so battery-transition latency is bounded by the sampler period (10 s default) and stated. | Mirrors `PauseMigration`/`ResumeMigration` exactly. Mirrors item 3's split: `ForegroundLoad` is the durable worker-services type, the gRPC adapter is the throwaway half lane F deletes. No new MMF field. |
| D5 | **The policy is ONE MORE INPUT to `IndexingPacing`, expressed as an admission veto plus a duty of 0 — never a parallel scheduler, timer, or second loop.** `IndexingPacing` gains an injected `Supplier<UserIndexingPolicyState>` and two members: `admit()` (may the loop take new work now) and `policyState()`. Nothing else in the pacing type changes shape. | The rule the register wrote for this lane. A second scheduler would re-open lane C's decision; an input cannot. |
| D6 | **Precedence, evaluated per loop iteration from the wall clock: manual pause > schedule > battery > automatic duty cycle.** A pure total function `UserIndexingPolicy.evaluate(policy, nowLocal, onBattery)` → `(admit, reason)`, registered as a `logic-seams.v1.json` seam with a truth table, in the `LoopPacingPolicy` style. | Per-iteration evaluation from the clock means a schedule boundary crossed during OS sleep is self-correcting — the 630 bug class is unrepresentable because no timer exists to be wrong. Cross-midnight and DST are the seam's truth-table cases. |
| D7 | **Admission is vetoed at the poll, not by sleeping inside `pace()`.** While not admitted, `IndexingLoop.java:619` takes no jobs and `BackfillScheduler.runIdleCycle()` (`IndexingLoop.java:651`) is skipped, so the loop falls into the existing idle branch that **commits pending documents** (`:635-646`) and sleeps. `pace()` keeps its bounded 2 s debt cap and never parks indefinitely. | A pause that leaves the index uncommitted would be a durability regression. Reusing the idle branch means pause is committed-and-consistent by construction, with no new code path. |
| D8 | **Discovery is never paused.** The watcher and the walk keep enqueuing (`WorkerMethvinWatcher` → `JobQueue.enqueue`); `paceAndContinue()` keeps returning false. Pause stops *consumption*, not *knowledge*. | Same shape as 896's low-disk decision ("queue accepts, loop pauses"); nothing is lost, and resume needs no rescan. **Owner K3.** |
| D9 | **"Paused" is a distinct state on the wire from the duty-cycle yield.** `LoopState.PAUSED` keeps its 885 meaning. New wire fields `user_paused` (bool) + `user_pause_reason` (closed vocabulary: `manual`, `schedule`, `battery`) on the status message, plus a `worker.indexing.user_paused` gauge. | Two meanings on one enum value is the representation-drift class the registers exist to prevent. |
| D10 | **Paused is not idle for the duty gauge.** While user-paused, `observedDutyPct()` accounts the paused wall time as yielded (so it reads toward 0) and `paced_intervals_total` does **not** increment (nothing was yielded to *foreground*). | Otherwise `duty_pct` reports the stale previous window (`IndexingPacing.java:237-243` returns `lastWindowDutyPct` when the current window has no samples) — a gauge reading 100 while nothing runs. |
| D11 | **Honesty surface:** three readiness reason codes (`indexing.paused_manual`, `indexing.paused_schedule`, `indexing.paused_battery`, severity info, remedy = resume), a fourth `IndexingPhase` arm `'paused'` in `indexingProgress.ts`, the `user_paused` fields on `/api/debug/state` (free, per §F), and `paused` + reason in the MCP `justsearch_status` text. Wording table is 906's. | The 600 remedy-honesty pattern with its existing forward/backward/producer gate; the `'blocked'` arm (`indexingProgress.ts:26-31`) is the precedent for "not running, not ready, no percent moves". |
| D12 | **Operation pair `core.indexing-pause` / `core.indexing-resume`**, `RiskTier.LOW`, `ConfirmStrategy.None`, `AuditPolicy.METADATA_ONLY`, `RequiredCapability.WorkerOnline`, `ExecutorTag.UI + AGENT`; pause takes optional `{durationMs}`. Both write the same settings field the Settings control writes, then push (D4). | Same tier as `core.cancel-indexing-job` (`:537-543`): reversible, no data loss. AGENT tag means an MCP client can resume what it paused — no new MCP tool needed. |
| D13 | **Delete `pauseIndexingDuringAi` in the same PR that adds the real lever**, sweeping all 15 sites (`retire-with-a-sweep`). | Two pause settings, one of which does nothing, is worse than none. **Owner K5.** |

**What "paused" guarantees, exactly.** No new job is dequeued; no new backfill cycle starts; the
index is committed before the loop idles. The unit already in flight finishes — worst-case drain is
**one document** for primary indexing (`JobBatchExtractor.java:136` paces per file, batch size 16,
`EnvRegistry:644-645`) and **one encoder sub-batch** for enrichment (the `pace()` sites in
`BackfillScheduler`). 813 §1e's ~63 s figure predates the persistent extraction pool and must be
**re-measured**, not quoted (`interrogate-results`). What it does **not** guarantee: that discovery
stops (D8), that search stops (search is served from the committed index and is unaffected), that
model download stops (Head-owned, a different subject), or that the process uses no CPU.

## §A. Design in one pass

`UserIndexingPolicy` (record, `worker-services`, `loop/pacing/`) + `UserIndexingPolicyState`
(`admit`, `reason`). `UserIndexingPolicy.evaluate` is pure and seam-registered (D6).
`IndexingPacing` takes the supplier, exposes `admit()` / `policyState()`, and folds the paused
window into its duty accounting (D10). `IndexingLoop` consults `admit()` immediately before
`pollPending` and before `runIdleCycle`. `BackfillScheduler` consults it at cycle entry. Nothing
else in the pacing package changes.

Head side: `UiSettingsV2` gains the record; `SettingsController` writes it through the existing
path; a small `IndexingPolicyPublisher` in `app-services` pushes on change and on the health tick,
reading `EnergyState.source()` for `on_battery`. The Worker's last-received policy defaults to
"no policy" so a Worker that has never heard from the Head behaves exactly as today (fail-open,
mirroring `EnergyState.Intent.UNKNOWN`).

FE: one `settings-indexing-policy` section in `SettingsSurface.ts` (the existing `data-testid`
section pattern, `operation-id` binding as at `:2560`), the `'paused'` phase arm, and the status
area rendering reason + a resume affordance from 906's wording table.

## §P. Reach

**Principle — a user preference is an INPUT to the authority that already decides, never a second
decider.** It is persisted as AUTHORED state, resolved by the one existing policy function, and
observable through the same vocabulary as every other reason that authority has.

**Already instantiated by:** `justsearch.gpu.layers` / `justsearch.server.exe` /
`justsearch.ui.exclude_patterns` riding `settings.json` at ordinal 300 instead of a sysprop
promotion (883 decision 4, `06-configuration-ssot.md:133`); the energy signal entering
`LoopPacingPolicy.shouldRunBackfill` as an argument rather than a second scheduler; `ForegroundLoad`
as one gauge with a disposable adapter.

**Where else it applies:** 896's low-disk `DiskPressure` is the same shape (an input that vetoes
admission and names its reason) and must land in the same veto, not a second one; 898's
"battery-aware pacing beyond VDU" is subsumed here.

**Existing violation:** `pauseIndexingDuringAi` — persisted, round-tripped, typed in three
languages, consumed by nobody (§F).

**Evidence it earns its keep:** (1) the live check in C3 shows ingest stopping within the stated
drain bound and `/api/debug/state` saying so, with the bound recorded as a number; (2)
`worker.indexing.user_paused` and the duty gauge separate paused from throttled from idle in the
same run; (3) the `pauseIndexingDuringAi` grep count reaches zero.

**Retirement condition:** if the automatic duty cycle plus 896's OS priority classes make the
manual lever unused (no issue traffic, no support signal over two releases), retire the **schedule**
arm first — it carries the most machinery per unit of benefit — and keep manual pause. If lane F's
Head/Worker merge lands, `SetIndexingPolicy` collapses to a direct call and only the record and the
pure evaluator survive; that is by construction, not a rewrite.

## §O. Orphaned by this design

- `pauseIndexingDuringAi` and its 15 sites (D13, §F) — deleted, not migrated: the new record is not
  a rename of it (it never meant pause-on-battery or a schedule, and it never did anything).
- Nothing in `ForegroundLoad`, `IndexingPacing`'s arithmetic, `LoopPacingPolicy`, the extraction
  pool, the MMF layout, or the migration pause/resume pair is touched or replaced.

## §I. Implementation chunks (opus takeover)

**Briefing.** Fresh start; read this file, then §F's pointers. Load `/dev-stack` and `/jseval`
before C3. Work in a worktree. If you find yourself writing a timer, a scheduled executor, or a
second place that decides when indexing runs — stop; re-read D5.

| chunk | scope | acceptance (runnable) |
|---|---|---|
| **C1 — record + store + vocabulary** (startable now) | `indexing.userPolicy` in both copies of `settings-v2.v1.json` + regenerated TS types; `UiSettingsV2` / `UiSettings` / `SettingsController` round-trip; `UserIndexingPolicy` + `evaluate` + `logic-seams.v1.json` seam row; the three `LifecycleReasonCode` members. No pacing code, no UI. | `node scripts/ci/check-store-recoverability.mjs`; `node scripts/ci/check-readiness-reason-codes.mjs`; `node scripts/governance/run.mjs --gate logic-seams --mode gate` (and `check-logic-seams --mode gate`); `--gate config-surface` shows **no new key**; `./gradlew.bat spotlessApply && ./gradlew.bat build -x test`; `:modules:app-api:test :modules:ui:test :modules:worker-services:test`; the seam truth table covers cross-midnight, DST, empty schedule, UNKNOWN battery |
| **C2 — wire + operations, dark** (startable now) | `SetIndexingPolicy` RPC + messages; `IndexingPolicyPublisher` (settings change + health tick); `core.indexing-pause` / `core.indexing-resume` in `CoreOperationCatalog` per D12. The Worker stores the policy and **does not act on it yet**. No user-visible control. | `npm install` in `scripts/wire-contract/` **first** (otherwise the `wire` gate reports `buf-cli-missing` and never inspects the proto), then `node scripts/governance/run.mjs --gate wire --mode gate`; `--gate operation-surface --mode gate`; `--gate host-owns-truth --gate consumer-presence --gate runtime-witness --mode gate`; `./gradlew.bat build -x test`; `:modules:app-services:test :modules:indexer-worker:test` |
| **C3 — wire the input into the gauge** — **START CONDITION, verbatim from 887 §L L10: `885 §Status "live arms recorded" + 813 finding 3 settled`** (§C records both readings; re-verify before starting) | `IndexingPacing` gains the supplier, `admit()`, `policyState()` and the D10 duty accounting; `IndexingLoop` + `BackfillScheduler` consult `admit()`; `user_paused` / `user_pause_reason` on the status wire; `worker.indexing.user_paused`. **Re-measure with 885's exact arms** — `python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean --json`, arms (a) alone, (b) `--search-load-qpm 10`, (c) `--search-load continuous` — plus a **fourth arm (d): user-paused**, on the same corpus, one arm at a time, `/api/debug/effective-config` confirmed per arm. | Full kernel `node scripts/governance/run.mjs --produce-inputs --mode gate`; `./gradlew.bat build -x test` then `./gradlew.bat test`; live via the dev-stack tools: `justsearch_dev_start` → ingest a corpus → pause → **ingested count stops advancing within the measured drain bound and `/api/debug/state`.worker reports `user_paused:true` with the reason** → resume → count advances again; the §Status table carries arms (a)-(d) with run ids and the measured drain bound as a number |
| **C4 — honesty surface** (after C3) | `'paused'` arm in `indexingProgress.ts` + consumers; `CAUSE_ROWS` rows (wording authored by 906, structure here); `settings-indexing-policy` section in `SettingsSurface.ts` with the `operation-id` bindings; MCP `justsearch_status` paused line; `03-knowledge-server.md` duty-cycle section + `search-ui-behavior.md`; `/docs-maintenance` regen | `node scripts/ci/run-ui-web-gates.mjs`; `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; `node scripts/ci/check-readiness-reason-codes.mjs`; `node scripts/ci/check-ui-step-coverage.mjs` with new ui-shot steps for the paused status area and the settings section (measured, not eyeballed); `node scripts/ci/check-dev-mcp-doc-sync.mjs` |

Order: C1 → C2 → C3 → C4. C1 and C2 are internal by construction; **no user-visible affordance
ships before C3 is green**.

## §Constraints

- **Never edit `ForegroundLoad`, `IndexingPacing`, the extraction pool, or the MMF activity byte
  before the C3 start condition.** C1 and C2 touch none of them.
- Never a second pacing authority: no new timer, scheduled executor, or independent loop gate.
- No ranking, fusion, or retrieval change. No new MMF field. No new `EnvRegistry` key.
- No `CLAUDE.md` or `.claude/rules/` edits.
- Presentation-kernel rules apply to C4 (atom authority, style-literal ratchet, modal/transient
  arbitration, measured a11y closure).
- Wording for every code and label named here is `failure-ux-coherent-surface`'s table, not this
  lane's — coordinate before authoring a sentence a user will read.

## §K. Owner confirmations — PENDING

- **K1 (D2)** `pauseOnBattery` default. **Recommend OFF.** The energy-saver defer already ships and
  is measured; a default-on battery pause changes shipped behaviour without a measurement.
- **K2 (D1)** Manual pause persists across restart, with an optional `pausedUntilMs`.
  **Recommend YES, persisted, `pausedUntilMs` default null (indefinite).** A pause that silently
  lifts on restart is the dishonest arm; the always-visible paused state plus a one-click resume is
  the mitigation for "I forgot".
- **K3 (D8)** Does pause stop discovery as well as consumption? **Recommend NO — consumption only.**
  Files stay discovered and queued, so resume needs no rescan and no user-visible work is lost.
- **K4 (D1)** Schedule granularity. **Recommend one index-wide window set (days + local start/end,
  cross-midnight allowed); no per-root schedules.** Per-root is a second precedence table for a
  demand nobody has evidenced.
- **K5 (D13)** Delete `pauseIndexingDuringAi` with a full sweep in the same PR.
  **Recommend YES.** It is a persisted promise with no consumer; two pause settings, one inert, is
  worse than one.

## §Status

Chartered 2026-09-02 (fable design pass). Nothing implemented. C1 and C2 may start immediately;
C3 carries the L10 start condition verbatim and must re-measure with 885's arms; C4 follows C3.
K1-K5 pending.

## §Z. Routed one-liners

- `pauseIndexingDuringAi` is persisted, schema-declared and typed in three languages with no
  consumer (`UiSettings.java:141`, `SettingsController.java:138` echo only) — owned by D13/K5 here;
  listed so it is not "found" again by another lane.
- `813-indexing-progress-queue-ux-design` frontmatter still reads "Lane-1 fixes (809 findings 1, 3)
  still unstarted" while its own §17 (`:528-529`, dated 2026-08-07) records both as merged. Stale
  status on a tempdoc; belongs to 813's owner, not to this lane.
- `898-inference-runtime-residuals` coordination line says "only the VDU consumer exists today" for
  battery-aware pacing; `LoopPacingPolicy.java:55` is an indexing-side consumer of the same energy
  signal. One-line correction for 898's owner.
- `IndexingPacing.observedDutyPct()` returns the previous window's value when the current window has
  no samples (`:237-243`), so a fully idle Worker reports the last contended duty rather than a
  no-data sentinel. Correct for 885's field question; noted because D10 depends on knowing it.
