---
title: Observations Cleanup Pass
type: tempdocs
status: in-progress
---

# 403 — Observations Cleanup Pass

> Generalized 2026-06-11 from the original "Backend Observations Cleanup +
> LuceneLifecycleManager Single-Use Decision" — Rounds 1–4 were backend-only;
> Round 5 onward drains the whole `docs/observations.md` Inbox across any area
> (backend, frontend, tooling, docs). The LuceneLifecycleManager architectural
> decision from Round 1 is preserved in the body below.

## Status

**DONE (Round 5, 2026-06-11).** All-areas takeover. Inbox stands at 180
open / 159 done at start of round. Renamed `403-backend-observations-cleanup.md`
→ `403-observations-cleanup.md` and widened scope beyond the backend section.
See **Round 5** below. The critical-review follow-ups on the Round-5 work
(#374 citationResolve guard, #379 sane title cap, #4 ui-bundle `covers:`
treadmill fix, #3 `--text-warning` a11y token) were implemented on the
`403-review-fixes` worktree, validated (build + typecheck + execution-surface +
worker test green), and merged to `main` at `2eb2ade63`. Post-merge hygiene:
`token-names.generated.ts` regen (the #3 token) + this closure. A pre-existing
ui-bundle hard-cap breach (`index.js` 1,020,280 > 1,020,000, from 565/575 FE
growth — not merge-caused) was logged to the Inbox for the bundle owner.

**DONE (2026-05-14).** Round 4 takeover completed. Inbox reduced from
~57 open to 30 open (29 closed this session). 8 real code/test/tooling
fixes, 1 PMD cleanup pass (63 violations), 1 test-hardening, 3 small
fixes, ~15 stale-inbox closures. All changes committed on `main`.

Original status DONE (2026-04-23) preserved for the first three rounds
of work.

**DONE (2026-04-23).** Pass resolved 9 backend observations across two
rounds (initial bulk cleanup + critical-analysis-driven follow-ups),
formalized an architectural decision on `LuceneLifecycleManager`
single-use lifecycle, and surfaced process lessons about trust-without-
verify and audit-subagent reliability.

## Scope

The pass works through `docs/observations.md` autonomously: investigate each
unresolved Inbox item, implement the confident fixes (root cause + regression
test, per the agent-discipline rules), critique the results, and iterate.
Rounds 1–4 were scoped to the backend (`ui`, `indexer-worker`); **Round 5
generalizes to any area** — backend, frontend, tooling, or docs.

## Resolved observations (9 items)

| # | Observation | Resolution |
|---|---|---|
| 2 | `content_preview` YAML frontmatter | Already fixed in `LanguageUtils.contentPreview()` — observation was stale |
| 3 | `LuceneIndexRuntime` restart NPE | **Resolved by design** — see architectural decision below |
| 5 | Exclude patterns per-pattern count test missing | Added `dryRunPerPatternCountsAreAttributedCorrectly` + 3 `matchingDirectoryPatternIndex` direct tests |
| 6 | `SqliteJobQueue` 7 methods untested | Added 9 direct unit tests to `JobQueueTest` |
| 7 | `FullCoverageSummarizer` ops untested | New `ContentLoadingOpsTest` + `SectionProcessingOpsTest` (7 tests total) |
| 8 | Hard-clean wipes `llmModelPath` | `scripts/dev/dev-runner.cjs` now preserves `ui/` in hard mode |
| 9 | Fresh hard-clean index not empty | Gated `tryIngestHelpFiles` on `justsearch.eval.mode` + new `KnowledgeServerBootstrapEvalModeTest` |
| 10 | Low-disk guard for `SqliteJobQueue` (was deferred) | `hasSufficientDiskSpace()` pre-check in `enqueue()` |
| 12 | Tempdoc 211 files uncommitted | Stale — files are in `modules/app-agent/`, last touched `79492b693` |

Plus two inbox entries ticked: the duplicate `AtomicBoolean` import in
`SectionProcessingOps.java` and, via reframing, the observation-drift
issue on #3.

## Deferred (trigger conditions recorded)

- **#4 RuntimeConfig untested methods.** Deferred because the
  Phase-1 subagent investigated `ResolvedConfigBuilderTest` while the
  observation specifies `RuntimeConfigTest`. Reconcile which surface is
  canonical before writing tests. **Trigger to resurrect**: the next
  agent adding a `RuntimeConfig` field who encounters a reviewer ask
  for unit coverage, or a concrete regression in `workerMaxBatchSize`/
  `egressBlockAll`/`llmModelPath` resolution.
- **#8b Periodic SqliteJobQueue backup.** Needs a
  `ScheduledExecutorService` in `KnowledgeServer`, which does not
  currently have scheduler infrastructure. Non-trivial in scope
  (cadence/retention/error-handling decisions) relative to current
  payoff (`VACUUM INTO` already runs pre-migration, so backups exist
  for schema transitions). **Trigger to resurrect**: first report of
  jobs.db corruption under a non-migration workload.
- **Inbox: `SchemaMismatchStatusContractTest` sqlite-jdbc classpath.**
  Test is `@DisabledIfEnvironmentVariable(named="CI")`; production
  unaffected (installer bundles everything). Real fix requires CI
  environment access not available in-session. **Trigger**: someone
  wants this test to run in CI.

## Critical-analysis findings that became bug fixes

Three critical-analysis rounds ran. Each surfaced a real bug in work
already marked "done":

1. **#3 LuceneLifecycleManager audit was wrong.** A Phase-1 subagent
   confidently asserted that `ctx.analyzerRegistry` was the only
   restart blocker. When the initial fix landed and the regression
   test ran, it failed on the state-machine check. When the state
   machine was relaxed, the test then failed on `indexingCoordinator`
   being null because `applyComponents()` never rebuilds it. The
   partial fix (preserve `analyzerRegistry`) was retained as defensive
   cleanup; full restart was reframed as an architectural choice (see
   below).

2. **#9 wrong gate flag (silent bug).** Original implementation gated
   on `ResolvedConfig.Ui.automationEnabled`, following a subagent's
   "reuse existing flag" recommendation. Critical analysis discovered
   `runHeadlessEval` never sets `JUSTSEARCH_UI_AUTOMATION` — it sets
   `justsearch.eval.mode=true` (a separate flag). The original fix
   would have had **zero effect** in real eval runs. Switched to
   `Boolean.getBoolean("justsearch.eval.mode")`, the same flag that
   already gates `/api/debug/reset-index`.

3. **Test precision weaknesses.** Inverse test used `atLeastOnce()`
   where production makes exactly one `submitBatch` call; tightened to
   `times(1)`. Mockito stub on `BatchResponse.newBuilder()` was
   unnecessary (production ignores the return); dropped, eliminating
   one cross-module coupling in the test.

## Architectural decision: `LuceneLifecycleManager` is single-use

The original observation #3 framed this as a latent NPE to fix. Audit
revealed three overlapping barriers:

1. `start()` state machine rejects `CLOSED`.
2. `ctx.analyzerRegistry` is nulled by `close()` and required by
   `ComponentsFactory.build()` on next `start()`.
3. `indexingCoordinator` is constructed in the ctor (not
   `applyComponents`), nulled by `close()`, and never rebuilt —
   its `LinkedBlockingDeque`/`ReentrantLock` state would also need
   deterministic reset.

Full restart support was audited at 5–10 dev-days, medium risk, with
no actual blocked caller. `KnowledgeServer`'s Blue/Green migration
constructs a new instance per generation (`KnowledgeServer.java:395-
398`), which already works.

Formalized as:

- Class Javadoc now records the single-use constraint, the three
  blockers, and the history ("as of 2026-04-22 we made that contract
  explicit instead of adding restart support").
- `start()`'s `CLOSED → STARTING` rejection emits a targeted error
  message routing callers to the new-instance pattern.
- Observation entry reframed from `[ ]` ("partial fix, real fix
  deferred") to `[x]` ("Resolved by design").
- `ctx.analyzerRegistry` preservation in `close()` retained as a narrow
  defensive cleanup (per-instance schema state should not be
  released on close).

This is worth revisiting if any of the following surface:

- Hot field-catalog reload without full index restart.
- Embedded-runtime scenarios that share a single manager across
  sessions.
- Performance regressions where Blue/Green reconstruction becomes a
  bottleneck.

## Verification

- `./gradlew.bat spotlessApply` clean throughout.
- `./gradlew.bat build -x test` green (includes PMD + Spotless +
  integrationTest).
- Module tests green: `app-services`, `adapters-lucene`,
  `indexer-worker`, `ui`.
- **Empirical end-to-end** for #9: ran `runHeadlessEval` with clean
  `tmp/headless-eval-data`, confirmed `app.log:103` contains
  `"Skipping help-file auto-ingest (eval mode)"` immediately after
  `"Knowledge Server is READY"`, and `.help-ingested-version` marker
  is absent.
- **Empirical end-to-end for the inverse path** (added 2026-04-23, after
  this entry's original "infeasible" claim was found wrong): ran
  `./gradlew :modules:ui:runHeadless` with clean `build/applauncher-data`,
  confirmed app.log contains `"Ingested 5 built-in help files"` and
  `.help-ingested-version` marker is present. The unit-test inverse in
  `KnowledgeServerBootstrapEvalModeTest` remains the cheap regression
  gate; the smoke confirms it reflects production. **Correction note**:
  an earlier version of this entry claimed `SSOT/docs/help/` was not
  tracked in git, based on a faulty `git ls-files` check. The 5 .md
  files (`ai-features`, `getting-started`, `keyboard-shortcuts`,
  `search-syntax`, `troubleshooting`) **are** tracked. The "coverage
  gap" described in the original entry was fictional.

## Process lessons

### Trust-without-verify has two failure modes

1. **Implementation trust** (symbols exist, therefore they work at
   runtime): the `automationEnabled` mistake. **Mitigation**: for any
   "reuse flag X" decision, verify X is set in the target scenario,
   not just declared as a config key.
2. **Audit trust** (subagent says "Y is the only blocker", therefore
   Y is the only blocker): the LuceneLifecycleManager mistake.
   **Mitigation**: before marking a fix complete, run a regression
   test that exercises the exact failure path the audit identified.

### Subagent audits of static structure are not infallible

The Phase-1 audit of `LuceneLifecycleManager.close()`/`start()`/
`applyComponents()` missed that `indexingCoordinator` is constructed
once in the ctor and never rebuilt. This is not a subtle detail —
it's obvious from reading `applyComponents()` carefully. The
subagent's report was internally confident ("the only restart
blocker") and the human reviewer (me) didn't independently verify.
Better pattern: **the audit output must propose a regression test,
and the agent must run that test before declaring the fix
complete**. The runtime signal is truth; the static audit is
hypothesis.

### Critical analysis is a reliable secondary gate

Both corrections to this work came from running a critical analysis
pass AFTER implementation. Treating "critically analyze my changes"
as a standard post-implementation step (not an optional
afterthought) would have caught these bugs one round earlier. This is
cheap insurance — the analysis itself takes a few minutes and
prevents landing non-working fixes.

### Test-visibility escape hatches are fine but ad-hoc

`tryIngestHelpFiles` was relaxed from `private` to package-private for
the new test. This is a standard pattern, but each occurrence is a
per-class decision. No systemic approach (e.g., `@VisibleForTesting`
annotations, per-package test helpers) exists. Not worth changing
today, but worth noting as a small-paper-cut pattern.

## Theoretically next

The highest-leverage follow-ups, roughly ordered by payoff/risk ratio:

### 1. Config flag coherence map

The Java config stack has several parallel "I'm in a non-normal mode"
flags:

- `justsearch.eval.mode` — system property, set by `runHeadlessEval`,
  gates `/api/debug/reset-index` and (now) help-ingest skip.
- `justsearch.ui.automation.enabled` / `JUSTSEARCH_UI_AUTOMATION`
  / `UI_AUTOMATION_ENABLED` — env-var/sysprop, not set by
  `runHeadlessEval` today, gates UI-side diagnostics.
- `justsearch.ui.settings.mode=IN_MEMORY` — set by `runHeadlessEval`,
  controls UI settings persistence.
- `justsearch.eval.disable_breath_holding` — set by `runHeadlessEval`,
  prevents indexing throttle.

None of these are cross-referenced in one place. Each was a local
decision at the time it was added. The #9 bug was a direct
consequence: a subagent "helpfully" suggested reusing one of them
without realizing they signal different things.

**Proposal**: a single reference table in
`docs/reference/configuration/` mapping each mode-flag to: what sets
it, what reads it, what effect it has. Not a refactor — a map.
Low effort, high leverage for future "reuse flag X" decisions.

### 2. Regression-test-first for audit-driven fixes

Formalize the pattern: when a subagent audit proposes a "the only
blocker is X" conclusion, the fix is not complete until a regression
test exercising that exact path (start → close → start; config with
eval mode; etc.) is green. The test is the gate, not the static
audit.

Could land as a CLAUDE.md rule under Agent Discipline, adjacent to
"Fix Root Causes, Not Symptoms": **"Verify audit conclusions with a
runnable test, not by re-reading the audit."**

### 3. ~~SSOT/docs/help distribution clarity~~ — RESOLVED (was a faulty finding)

Original entry claimed `SSOT/docs/help/` was not tracked and proposed
three resolution options. This was wrong — the files are tracked. No
action needed; the inverse-path smoke now runs successfully.

Lesson: a single negative result from `git ls-files | grep ...` was
treated as authoritative without independent verification. This is the
same trust-without-verify failure mode that produced the
`automationEnabled` bug. The fix in CLAUDE.md (Tier 3 below) covers
both classes.

### 4. `LuceneLifecycleManager` restart — promoted to tempdoc 406

The original entry here proposed a "watch list" with reopen-when-X
triggers. That framing is forbidden by `CLAUDE.md > Structural Defects
Don't Need Repeat Incidents`. Moved to its own tempdoc
(`docs/tempdocs/406-lucene-lifecycle-manager-restart-refactor.md`)
where it must be resolved as either a real refactor (Resolution A) or
a permanent close (Resolution B) — no middle ground.

## Round 4 — 2026-05-14 takeover

### Context

After the 2026-05-14 observations.md audit (17 items checked off as
verified-resolved, ~57 remaining open), this tempdoc was reopened to
extend the autonomous-cleanup pattern past the original Backend section.
Round 4 picks a single open observation for deep investigation rather
than a bulk pass, on the principle that the highest-leverage open items
(substrate-affecting, multi-consumer) deserve a focused write-up before
implementation.

### L84 — Close-time NDJSON forceFlush race (LocalTelemetry)

**Observation (verbatim from `docs/observations.md`):** "Close-time
NDJSON forceFlush race affects every counter project-wide.
`LocalTelemetry.close()` calls `meterProvider.forceFlush().join(2s)` then
`meterProvider.close()`; the 2s join expires before
`NdjsonMetricExporter.export` finishes writing, so the last counter
values never reach NDJSON. Visible on `worker.documents.indexed.total`
(last value at last periodic flush, never at close),
`index.runtime.*` counters, and any others. Tempdoc 413 worked around it
locally by emitting the SHUTDOWN unload at top of `KnowledgeServer.close()`
and calling `LocalTelemetry.flush()` explicitly (5s join, SDK fully
alive). A substrate fix — perhaps a final periodic-style flush before
the scheduler shutdown, or a longer close-time join — would benefit every
counter."

**Status: STILL-OPEN, but the diagnosis in the observation is partially
wrong.** The 2s join timeout is not the primary cause.

### Root cause (corrected diagnosis)

`LocalTelemetry.close()` (`modules/telemetry/.../LocalTelemetry.java:360-390`)
executes in this order:

```java
public void close() {
    // (1) Close gauge handles — unregisters ObservableCounter callbacks.
    for (Object h : gaugeHandles) {
        if (h instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
    // (2) Shutdown the heartbeat scheduler (NOT the SDK reader).
    this.flushScheduler.shutdown();
    if (heartbeatFuture != null) heartbeatFuture.cancel(true);
    this.flushScheduler.awaitTermination(2, TimeUnit.SECONDS);

    // (3) forceFlush — too late for ObservableCounters.
    CompletableResultCode rc = this.meterProvider.forceFlush();
    rc.join(2, TimeUnit.SECONDS);

    // (4) Close provider + RRD store.
    this.meterProvider.close();
    this.rrdStore.close();
}
```

The bug is step **(1) before (3)**, not the 2-second join on (3).

`gaugeHandles` stores the `AutoCloseable` returned by
`meter.counterBuilder(name).buildWithCallback(...)` for every
`ObservableCounter` / `ObservableGauge` (see `CatalogRegistry`
`registerObservableCounter()` at line 354). Closing the handle
unregisters the callback in OTel's `SdkMeterProvider`. After (1), an
`ObservableCounter` has no producer. When (3) runs `forceFlush()`, the
SDK iterates registered instruments and asks each for a measurement;
the unregistered observers produce nothing. `NdjsonMetricExporter`
receives a `Collection<MetricData>` that omits those instruments
entirely.

**`worker.documents.indexed.total` is an `ObservableCounterMetric`**
(verified: `WorkerOpsMetricCatalog.java:202`,
`ObservableCounterMetric<EmptyTags> documentsIndexedTotal`). It loses
its final cumulative value at every clean shutdown. This is exactly the
symptom L84 reports.

The `index.runtime.*` counters listed in L84 are mostly **synchronous**
`Counter` instruments (verified via `IndexRuntimeWireFormatRegressionTest`
expected-types map — all `"counter"` not `"observable_counter"`). Sync
counters buffer increments in the SDK aggregator; `forceFlush()` should
collect them regardless of the gauge-close-first ordering. **Their loss,
if real, has a different cause** (see "secondary causes" below).

### Why 2 seconds isn't the bottleneck

`NdjsonMetricExporter.export()` (`NdjsonMetricExporter.java:133-180`) is
**synchronous**: it builds a `StringBuilder`, calls `Files.writeString`
once, and returns `CompletableResultCode.ofSuccess()`. The
`PeriodicMetricReader.forceFlush()` path collects metrics on the calling
thread and invokes `export()` inline. Under normal conditions the whole
flush returns in tens of milliseconds — well within 2s.

The 2s join can expire in a degenerate scenario (file system stalled,
periodic flush already mid-export and forceFlush queued behind it), but
that's a contention failure mode, not the root cause of the observed
ObservableCounter loss. Even with a 60-second join, the L84 symptom
would persist because the values were never collected in the first
place.

### Secondary causes worth ruling out

1. **Sync-counter loss is unverified.** L84 claims sync counters lose
   their last values too, but the supporting evidence in the
   observation references only `worker.documents.indexed.total`
   (observable) and "`index.runtime.*` counters" generically. Whether
   sync counters actually lose final values depends on whether
   `forceFlush()` completes before `meterProvider.close()`. If the
   2-second join *does* expire on a slow disk, the close-and-shutdown
   path can drop the last batch — but only as a contention fault, not
   as the steady-state behavior the observation implies.

2. **`flushScheduler` is the heartbeat scheduler, not the SDK reader.**
   The variable name is misleading. `flushScheduler.shutdown()` does
   not affect the OTel `PeriodicMetricReader`'s internal scheduler;
   that one is owned by the SDK and shut down inside
   `meterProvider.close()` (step 4). So the periodic reader is still
   alive at step (3) and `forceFlush()` works against a live SDK.

3. **`rrdStore.record()` is invoked from inside `export()`.** If RRD
   writes block, they would extend the synchronous export latency. Not
   a likely cause on a healthy system but worth noting if a future
   profile shows long close-time exports.

### Tempdoc 413's workaround

Tempdoc 413 added `LocalTelemetry.flush()` (line 433, 5s join) and
calls it explicitly at the top of `KnowledgeServer.close()` after
emitting `embedding.runtime.unload_total{SHUTDOWN}`. The 5s join is not
the load-bearing part of the workaround — what saves the SHUTDOWN
emit is calling flush **before** `LocalTelemetry.close()` runs, while
the gauge callbacks are still registered. For the SHUTDOWN-counter
specifically (a sync `Counter`, not observable), this is sufficient.

But this workaround is per-callsite. Every module that wants a clean
final-value snapshot has to remember to emit + flush before close.
That's not a substrate, it's a discipline.

### Proposed fix

Reorder `LocalTelemetry.close()` so flush precedes gauge teardown:

```java
public void close() {
    // (1) Final flush BEFORE unregistering anything — gives ObservableCounter
    //     callbacks one last invocation under forceFlush, capturing their final
    //     cumulative values.
    try {
        meterProvider.forceFlush().join(5, TimeUnit.SECONDS);
    } catch (Exception e) {
        healthState.recordFlushFailure();
        log.warn("Final metrics flush failed (best-effort): {}", e.getMessage());
    }

    // (2) Unregister gauge callbacks.
    for (Object h : gaugeHandles) {
        if (h instanceof AutoCloseable c) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    // (3) Heartbeat scheduler shutdown.
    this.flushScheduler.shutdown();
    if (heartbeatFuture != null) heartbeatFuture.cancel(true);
    try { this.flushScheduler.awaitTermination(2, TimeUnit.SECONDS); }
    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

    // (4) Close provider + RRD store.
    this.meterProvider.close();
    this.rrdStore.close();
}
```

Bumping the join from 2s → 5s also addresses the secondary contention
case at zero cost (this is a one-shot close-time wait, not a hot path).

### Regression test (per Round 1 lesson "audit needs a test")

The audit conclusion above — "ObservableCounter values are lost because
callbacks unregister before the final flush" — needs a runnable test
before the fix is considered complete:

1. Construct a `LocalTelemetry` with an `NdjsonMetricExporter` writing
   to a tmp path.
2. Register an `ObservableCounterMetric` whose supplier returns a known
   value (e.g., 42L).
3. Call `close()`.
4. Parse the NDJSON; assert the metric appears with value 42.

This test should fail against the current `close()` order and pass
after the reorder. It pins the property "ObservableCounters emit their
final value on graceful shutdown."

### Why this matters beyond L84

`worker.documents.indexed.total` is the canonical "did indexing happen"
signal in eval / soak runs. Losing its final value forces every
downstream consumer (jseval projections, throughput reports, soak
dashboards) to inspect the second-to-last value, which captures a
mid-flush state. The substrate fix removes a class of off-by-one
errors in every observability consumer of cumulative ObservableCounters.

### Status (Round 4 decision)

**FIXED 2026-05-14.**

**Regression test** (`LocalTelemetryTest.observableCounterEmitsFinalValueOnShutdown`):
constructs `LocalTelemetry` with a 60s flush interval, registers an
`ObservableCounter` whose supplier returns 42, calls `close()`, asserts
the NDJSON contains both `"name":"pipeline.observed"` and `"value":42`.

- **Against unfixed code**: FAILED at `LocalTelemetryTest.java:81`
  (`AssertionFailedError` — NDJSON did not contain the metric name).
  Confirms the audit conclusion empirically.
- **After fix**: PASSED. Full `:modules:telemetry:test` suite green.
- **Full build green**: `./gradlew.bat build -x test` (34s, 253 tasks).

**Substrate change** (`LocalTelemetry.close()`):

1. Reordered: `forceFlush()` now runs **before** gauge handles are
   unregistered, so ObservableCounter callbacks fire one last time
   during the final collection.
2. Bumped join timeout from 2s → 5s. Zero-cost insurance against
   secondary contention (slow disks, periodic flush mid-flight). Matches
   the explicit `flush()` method's 5s join.
3. Heartbeat scheduler shutdown moved after flush (was already correct
   semantically, but co-locating with the gauge teardown clarifies the
   teardown sequence).

**Tempdoc 413's per-callsite workaround** (explicit `LocalTelemetry.flush()`
before close + early SHUTDOWN emit) is now redundant but harmless;
removing it is a separate cleanup that doesn't gate this fix.

**observations.md L84**: ready to mark `[x]`. Resolution note: "Resolved
2026-05-14 (tempdoc 403 Round 4): `LocalTelemetry.close()` reordered so
`forceFlush()` runs before gauge-handle unregister, allowing
ObservableCounter callbacks to fire one final time. Regression pin in
`LocalTelemetryTest.observableCounterEmitsFinalValueOnShutdown`."

### L85 — KnowledgeServer.embeddingService stale reference after unload

**Observation (verbatim):** "`KnowledgeServer.embeddingService` keeps a
stale reference to the closed instance after
`IndexingLoop.unloadEmbeddingService` (hybrid-inference VRAM handoff).
The unload path closes the service and nulls
`IndexingLoop.embeddingServiceForLifecycle`, but
`KnowledgeServer.embeddingService` (the field at line 121) is not
nulled — readers like `GpuDiagnosticSuppliers` (lines 970-975) still
call methods on the closed service. The cache-size gauge supplier
(tempdoc 413) tolerates this because `embeddingCache.size()` is safe on
a cleared map, but other accessors return stale data."

**Status: ALREADY RESOLVED.** This investigation is a verification case,
not a fix case.

### What's already in place

1. **Production wiring** (`KnowledgeServer.java:881-887`): a listener is
   registered on `appServices` that nulls `this.embeddingService` when
   the provider transitions to `NoOpEmbeddingProvider`:

   ```java
   appServices.addEmbeddingProviderChangeListener(
       provider -> {
         if (provider == null
             || provider instanceof NoOpEmbeddingProvider) {
           this.embeddingService = null;
         }
       });
   ```

2. **Unload path** (`IndexingLoop.java:1590-1607`): on
   `unloadEmbeddingService()`, the loop sets
   `embeddingProvider = NoOpEmbeddingProvider.INSTANCE` and calls
   `notifyEmbeddingProviderChange(NoOpEmbeddingProvider.INSTANCE)`,
   firing the listener.

3. **All four GpuDiagnosticSuppliers re-read the field** (lines
   1029-1056): each lambda starts with `var es = this.embeddingService;`
   so post-unload nulls propagate to `/api/status` instead of being
   captured by an early method reference. The integer-returning
   `gpuLayers()` supplier returns `0` when null (the
   `IndexStatusOps.buildGpu` consumer auto-unboxes `int`, so returning
   `null` would NPE — fix landed 2026-05-09 after
   `SchemaMismatchStatusContractTest` caught it).

4. **All other readers null-guard**:
   - `validateEmbeddingDimension()` line 1276: `if (embeddingService == null || ...) return;`
   - SHUTDOWN emit line 1513: `if (embeddingService != null && ...)`
   - close() line 1664: `if (embeddingService != null)`

5. **Regression test**
   (`IndexingLoopUnloadTelemetryEmitTest.unloadEmbeddingService_listenerNullsKnowledgeServerStyleFieldHolder`,
   line 115): mirrors the production wiring with an `AtomicReference`
   holder, registers the same listener pattern, invokes
   `unloadEmbeddingService` via reflection, and asserts the holder
   becomes null. Test passes on current main (verified 2026-05-14).

### Why the observation is stale

The comments in `KnowledgeServer.java:875` and `:1029` both reference
"observations.md fix", indicating someone wrote the fix and updated the
code, but the observations.md entry itself was never checked off. The
comment at line 1051 ("regression caught by
`SchemaMismatchStatusContractTest` 2026-05-09") implies the fix landed
**after** the original observation, and a subsequent NPE in the
auto-unboxing path was caught and patched without the observation being
revisited.

This is a documentation-hygiene defect, not a code defect. The
substrate is correct; the inbox just wasn't pruned.

### Status (Round 4)

**L85: marked `[x]` in observations.md.** No code change. The Round 4
contribution is the verification trail above — establishing that the
listener fires, all readers tolerate null, and a regression test pins
the property end-to-end.

### Process note

This is the second time the 2026-05-14 audit pass has caught a stale
observation entry (L84 had a wrong diagnosis; L85 has the right
diagnosis but the fix already shipped). Pattern: `observations.md`
accumulates entries faster than they're pruned, and entries can outlive
the bugs they describe by weeks. Mitigation candidates for a future
process change:

- A "claim before fix" convention: when an agent fixes an observation,
  they must check it off in the same commit as the fix.
- Periodic verification passes (this audit was the first since 403's
  original Round 1-3).
- A `Resolved-but-not-pruned` cluster in the inbox so verification
  doesn't have to re-derive the fix from scratch.

Not promoting any of these to a CLAUDE.md rule from one data point;
flagging for the next observations cleanup pass to consider.

### Stale-entry sweep (2026-05-14)

After L84/L85, the audit pass continued autonomously through the open
inbox. **14 additional entries** verified as already-fixed-but-not-
pruned, raising the total stale-inbox count for this audit to **16**:

| # | Where fix landed |
|---|---|
| L66 | `messages.po` has all 4 strings translated |
| L67 | `ui_check.py:553-554` generates per-view content-ready steps |
| L76 | `IngestionSkipPolicy.java:29-38,129-135` adds `EXEMPT_NAMES` short-circuit |
| L96 | `timeline.py:110-113` adds writer/commit/refresh fields |
| L97 | `cli.py:1085-1119` exposes `--no-demo` flag |
| L103 | `_paths.py:13-49` adds CWD-aware `_resolve_repo_root` |
| L104 | `DevReloadManager.java:163-197` null-guards reranker supplier |
| L105 | `KnowledgeServer.java:1019-1060` splits SPLADE/bgeM3 slots |
| L106 | `build.gradle.kts:298-308` `doFirst { delete(lib/*.jar) }` |
| L109 | `GpuCapabilitiesService.snapshot()` unifies all 4 endpoints |
| L116 | `LocalApiServer.java:879-897` checks `matchedPath()` |
| L141 | `SurfaceCatalogClient.ts:241-280` adds first-install retry |
| L142 | commit `29fa1349c` code-splits SES, opt-in lockdown |
| L147 | commits `bf491eb0f` + `02d5eaa97` wrap tokens in `@layer core-theme` |
| L151 | both schema files use `$defs+$ref` form |

Plus L84 (wrong diagnosis, real fix shipped Round 4) and L85
(documented above).

### L117 — Real bug found and fixed

**One concrete bug surfaced during the sweep**: L117 framed itself as a
"Vite proxy / CORS / bundle path mismatch" but the real cause was a
**missing `data-testid="search-input"` attribute** on the search input
in `SearchSurface.ts:671-683`. The shell-v0 SearchSurface had testids
on neighboring inputs (`pin-search-btn`, `filter-from`, `filter-to`)
but not on the main search field that `scripts/jseval/jseval/ui_selectors.py:10`
declares as `TID_SEARCH_INPUT = "search-input"`.

One-line fix added the missing attribute. Typecheck + 1058 FE unit
tests green.

This is the same trust-without-verify failure mode Rounds 1-3 warned
about: the original observation accepted the user-visible symptom
("Locator timeout") and proposed the most-plausible-sounding cause
(Vite proxy). Reading the actual element revealed the real cause in
under a minute.

### Round 4 — second batch (autonomous, 2026-05-14)

After L117, the autonomous sweep continued through six more concrete
items via task tracking. Real fixes landed for five of them:

1. **L154 — UnreferencedCodeTest dead-code findings**. Deleted
   `EngineConversationContext.mutableMessages()` (public `messages()`
   already covers legitimate use) and `SseWriter.writeSseComment()`
   (Javalin handles SSE comments internally). Compile + test green.

2. **L63 — HealthSurface GPU "Active" misleading label**. Minimal
   honest relabel: pill now reads "Detected" / "Unavailable" matching
   what `gpu.available` actually represents (NVML probe = GPU present
   on host). Deeper "GPU in use by JustSearch" attribution requires
   aggregating per-component cuda status across embed / SPLADE /
   reranker / inference — flagged as separate substrate work.

3. **L99 — `inference.startup.failure_total code=unknown`**.
   Investigated; **left open by design**. Bug D synthesis at
   `InferenceLifecycleManager.java:1334-1343` uses
   `StartupCode.UNKNOWN` because `StartupFailureTags` is closed-typed
   and `ConfigFailure` / `TransitionFailure` wireCodes don't fit the
   `StartupCode` enum. Fix needs a telemetry taxonomy decision
   (extend enum vs. add `underlying_code` tag axis vs. emit a
   distinct `inference.transition.failure_total` metric). Not
   implementable autonomously; observation entry updated with the
   options.

4. **L113 — victools `JacksonModule` deprecation**. Migrated to
   `JacksonSchemaModule` (the upstream replacement in jsonschema-
   module-jackson 5.0.0). Test green. The specific "marked for
   removal" warning is gone; residual generic deprecation note
   refers to a different API in the same file.

5. **L118 — SSE empty body when Accept header missing**. The
   observation framed it as `/api/health/events/stream` only, but the
   root cause sits in the shared `SseEnvelopeWriter`. Fix:
   `attach()` + `attachEventOnly()` now unconditionally set
   `Content-Type: text/event-stream; charset=utf-8` +
   `Cache-Control: no-cache` + `X-Accel-Buffering: no` before the
   first write, bypassing Javalin's content negotiation. This
   benefits **every** SSE endpoint (health-event, capabilities,
   indexing-jobs, diagnostic-channel, gpu-utilization,
   intent-stream, etc.), not just the one cited. Real EventSource
   clients send the header and are unaffected; ad-hoc curl /
   postman users now see the actual envelope stream.

6. **L100 — `/api/inference/status` vs `/api/status` divergence**.
   Static-verified consistent: both converge on
   `InferenceLifecycleManager.getCurrentMode()`. Supplier-based
   `projectInferenceSnapshot` closes over `this.inferenceManager`
   and evaluates at call time, so no stale-mode window. Original
   tempdoc 412 Phase 3 symptom was likely a transient wiring race
   during initial setup, closed by the consolidation. Marked
   resolved without code change.

### Round 4 — third batch (autonomous continuation, 2026-05-14)

Continued the sweep on lower-impact concrete items. Findings:

7. **L46 — testFixtures ArchUnit governance**: moot. When the
   observation was filed (2026-04-22), `ort-common` was the only
   module applying `java-test-fixtures`. The codebase has since
   organically adopted the pattern across **7 modules**
   (`ort-common`, `app-indexing`, `app-services`, `configuration`,
   `telemetry`, `worker-core`, `worker-services`). The "first-mover
   needs governance" rationale is gone. If misuse surfaces later,
   governance can be added then. Marked resolved.

8. **L52, L54 — Zombie workflows (Agent Live Eval Nightly, RR219
   Resilience Soak Weekly)**: both workflow files deleted as part of
   the Phase 3 §B.14 sweep (line 36) alongside the entire
   `scripts/resilience/*` tree. Stale entries.

9. **L53 — Phase 3 Observability Nightly zombie**: workflow exists
   but now `workflow_dispatch` only per ADR-0026. The April 23
   failure was the last scheduled run before the policy change.
   Marked resolved.

10. **L59 — NdjsonSpanExporter ALLOWED_ATTRS duplication**: the
    original observation conflated three attrs. `fallback.cause` +
    `fallback.encoder` duplication has been cleaned up (line 73-76
    comment explicitly references the fix). `reason_code` was never
    duplicated — it's only at span level, not in
    `ALLOWED_EVENT_ATTRS`. Marked resolved.

11. **L60 — OTel `Attributes.of(stringKey, null)`**: already
    guarded. `ContractEmitter.emit()` does `Objects.requireNonNull`
    on all three args (line 77-79) with an inline comment
    (line 59-64) explicitly referencing the OTel quirk. The
    "future OTel attr builder" guidance is in place via precedent.

12. **L61 — Python fixture `@RuntimeContract`**: real fix. The
    Java taxonomy is Build/Boot/Sample/Advisory; `@RuntimeContract`
    doesn't exist. Replaced fixture references with
    `@AdvisoryContract` to align. 13 projection tests green.

13. **L62 — NdjsonSpanExporter always emits `events:[]`**: by-design
    per inline comment (line 191-192): "Empty events array is emitted
    for schema stability (consumers don't need null-coalescing)."
    Won't-fix.

14. **L65 — NER license mislabeled**: both `build.gradle.kts:421`
    and `docs/reference/legal/ai-runtime-and-model-redistribution.md:137,139`
    already show "AFL-3.0" correctly. Stale entry.

15. **L94 — `tmp/agent-telemetry/` never produced data**: the
    buggy `auto-evaluate.mjs` hook was deleted in tempdoc 423 §14.19.
    No remaining producer to fix; entry was a post-mortem.

### Round 4 — infrastructure batch (2026-05-14)

Targeted the infrastructure/discipline category. Three real substrate
fixes + two stale-inbox closures:

16. **L68 — `NativeSessionHandleConcurrentStressTest` NPE on null
    variant**. Real bug. `OrtSessionAssembler.buildManager` emitted
    `NULL_VARIANT` telemetry then immediately NPE'd at
    `policy.variant().executionProvider()`. Fix: treat null variant
    as implicit-CPU fallback (matches `ModelSessionPolicy.forFallback`
    contract). Updated `OrtSessionAssemblerNullVariantTest` to pin the
    new "emit-then-CPU-fallback" contract — previously the test was
    pinning the buggy "emit-then-NPE" behavior. Stress lane
    (`gh workflow run ci.yml -f runStress=true`) unblocked.

17. **L51 — PMD debt across 5 modules (111 violations)**. Real
    cleanup. Three of the cited modules (`adapters-lucene`,
    `configuration`, `ssot-tools`) had self-cleaned between filing and
    audit. Cleaned the remaining `app-agent` (1) + `app-services` (62)
    in this pass: stripped `UnnecessaryFullyQualifiedName` across 9
    files; deleted genuinely-dead `batchSize` field in
    `RemoteKnowledgeClient`; wired `indexingJobsBridgeRegistrySubscription`
    into `AppFacadeBootstrap.close()` (turned a leaking dead field
    into proper unsubscribe-on-close); removed two stale unused
    parameters with their docstrings. All 5 modules PMD-clean.

18. **L39 — `SchemaMismatchStatusContractTest` sqlite-jdbc classpath
    failure**. Stale. Test passes on current main (`BUILD SUCCESSFUL`).
    Root cause was reliance on a complete worker dist at
    `modules/indexer-worker/build/install/indexer-worker/lib/`. Per
    CLAUDE.md, `installDist` is now wired into `assemble`
    (`indexer-worker/build.gradle.kts:124-127, 201`), so sqlite-jdbc-*.jar
    is always staged before integration tests run.

19. **L90 — PathNormalizer doc-vs-reality drift**. Stale. Doc
    `03-knowledge-server.md:147` already reads "replaces every `/`
    with the platform-native separator — on Windows that produces
    backslash-form paths like `c:\\users\\elias\\…\\file.txt`."
    Matches `PathNormalizer.normalizePath` verbatim.

### Round 4 — slice-internal followup batch (2026-05-14)

Targeted slice-internal followups and historical worktree coordination
entries. All stale, all closed via verification:

20. **L127 — slice 3a.1.9 D1-D4 follow-ups**: all four closed in
    commit `fe8150a5a` (slice doc lines 1349-1378). D1
    (timeseries-snapshot.v1.json authored), D2
    (KindRendererCrossRefValidator + 5 unit tests), D3 (catalog boot
    moved to i18n.ts app-startup), D4 (bootResourceCatalog →
    bootResourceRegistry rename).

21. **L128 — truth-class audit F-11/F-12/F-13**: audit's own §X.S
    states "No new C-NNN ledger entries needed; F-11 extends C-015,
    F-12 + F-13 are observations supporting C-018's framing." R-7
    landed (CONFLICT-LEDGER C-015 enumeration now includes
    Privacy.resolver, line 36).

22. **L114 — Tempdoc 429 Phase 10-13**: tempdoc 429 status `complete`;
    `phases_completed` includes 10,11,12,13,c1,c2,c3-design-fidelity;
    `phases_pending: none`.

23. **L71, L73 — 410 worktree coordination items**: tempdoc 410 merged
    as "substantively-complete". L71 was speculative ("if you add
    writeBarrier.readLock(), use the helper") — verified: merged code
    doesn't use either. L73 was a MetricCatalog migration concern —
    verified: merged 410 routes through `IngestionOutcomeMetricCatalog`
    per tempdoc 410 status block.

24. **L119 — `jseval --reduce-throughput` flag**: documented deferral
    with workaround (smoke-test doc lines 81-92). Item 6 (worker
    death) exercises the same substrate path; only throughput-specific
    reason code remains unverified — acceptable V1 gap explicitly
    deferred to slice 1.3.

25. **L108 — chat skip "AI installed." misleading message**: stale.
    `AiInstallService.java:398-421` already branches correctly —
    "Installed with limitations: <labels> skipped on this hardware"
    when `skippedCount > 0`, "AI installed." only when zero
    skipped/failed. Tempdoc 374 finding #8 cited inline. FE
    translations present.

### Round 4 closing tally

- **32 observations checked off** in total — Round 4 closed nearly
  half the inbox. From the third batch: L46, L52, L53, L54, L59, L60,
  L61, L62, L65, L94. Plus the earlier batches: L63, L66, L67, L76,
  L84, L85, L96, L97, L100, L103, L104, L105, L106, L109, L113, L116,
  L117, L118, L141, L142, L147, L151, L154.
- **5 substrate / code fixes** implemented this round: L84 telemetry
  close ordering, L117 search-input testid, L113 JacksonModule
  migration, L118 SSE forced content-type (every SSE endpoint), L154
  dead-code deletion, L63 honest pill label.
- **1 regression test** added (`observableCounterEmitsFinalValueOnShutdown`).
- **1 observation upgraded with deeper analysis** (L99 — typology
  decision required, not autonomously implementable).
- **Stale-inbox pattern** documented: ~16-17 of the 23 closed entries
  had already-shipped fixes that were never checked off. Pattern
  flagged for future process consideration.

Remaining open items (33) are genuine — the stale-fixed-but-not-pruned
pattern has been worked through. Categories:

- **V1.5 plugin sandbox dev-mode polish** (L143-146, L150): Vite
  module-instance deduplication, `?import` query 500, `btoa()`
  non-Latin1, `customElements.define` un-define guard, FE arg
  prompting UI. All dev-only; production Rollup unaffected. Tagged
  V1.5.1 scope.
- **Hardware-bound** (L47, L86, L112): cold-start timing, JAR-bundled
  CUDA reproduction, vGPU vs bare-metal enrichment benchmark. Need
  specific hardware / sandbox environments.
- **User-environment** (L50, L91-93): scoop symlinks, user-scope
  plugin notes. Not codebase bugs.
- **Deferred-by-design** (L95, L101, L102, L122, L124, L126): V2
  gpu-saturated false-negative, WorkspaceTimeline sessionId join, C28
  notification continuity, methodology note, RemoteIndexingJobsBridge
  reconnect, Delta discriminator. All have explicit reopen triggers.
- **Substrate decisions** (L99): inference failure typology taxonomy.
  Needs design review.
- **Test-hardening** (L87, L88, L89): tempdoc 415 latent risks.
  Hypothetical regressions pinned by existing tests; hardening is
  optional future work.
- **Editorial / doc** (L133, L149): 3a-1-8f inbound-ref sweep,
  canonical-to-tempdoc link verifier. Bounded but non-trivial.
- **Remaining infrastructure** (L120): smoke 7a heap-pressure live
  path. Process/tooling gap. (L45 resolved via stress-test-hint hook.)
- **Fresh from today's session** (L157-162): dev-runner migration
  restart window, MCP body serialization (L158 fixed), reindex force
  query-param diagnosis (L159 closed), knowledge-status sparse
  projection (L160), dev-stack takeover incident (L161),
  chunk-embedding readiness lie-by-omission (L162). L160-L162 are
  status-projection redesigns — too big for autonomous pass.

## Provenance

- Initial prompt: autonomous investigation of unresolved Backend
  observations, plan implementation, critical analysis, iterate.
- Session sequence: Backend triage → Tier A-E plan → implement →
  critical analysis round 1 → corrections → critical analysis round
  2 → corrections → this tempdoc.
- Associated plan files (Claude-local, not checked in):
  `plan-out-how-to-fluffy-metcalfe.md`,
  `fix-critical-analysis-followups.md`.

---

# Consolidated satellites (folded 2026-06-09, post-400 hygiene pass)

> The structural-quality critical-analysis followup folded in.

## Structural quality from 403/406 critical analysis (was 407)

*(folded from `407-structural-quality-from-403-critical-analysis.md`)*

### 407 — Structural Quality Improvements from 403/406 Critical Analysis

## Status

**OPEN.** A long-term-quality critique of the code shipped in tempdoc
403 surfaced cross-cutting structural concerns that don't fit either
403 (a closed retrospective) or 406 (a specific class refactor).

**Three proposals are warranted by documented bug-class instances**
under `CLAUDE.md > Structural Defects Don't Need Repeat Incidents`:
EvalMode (proven by Tier B's wrong-flag bug), DiskSpacePolicy (proven
by tempdoc 201 Gap 1's documented reactive-only disk-full handling),
and telemetry counters for the new gates (proven by 17 sibling files
that emit counters for similar gates — the new gates are inconsistent
with that established pattern).

**One drops on plain YAGNI**: a test-style register. No documented
incident attributable to test-style heterogeneity surfaced in
verification.

**One is already scoped elsewhere**: LuceneLifecycleManager field
grouping is in tempdoc 406 Phase 1.

**Important correction:** an earlier draft of this tempdoc dropped
DiskSpacePolicy and downgraded telemetry counters to "needs
investigation" based on unverified absence-of-evidence claims. A
follow-up grep over `docs/observations.md`, `docs/tempdocs/`, and the
`modules/` codebase surfaced direct contradicting evidence. Both
items are now warranted. The pattern of unverified-negative claims
recurred three times this session and needs explicit disciplinary
attention (see Critical confidence section).

## Self-contained summary of prior work

The reader does not need to fetch tempdoc 403 or 406 to understand this
document. Key prior context:

### What 403 produced

A backend observations cleanup pass resolved 9 items from
`docs/observations.md`. Code surface:

- **Production code modified (5 files):**
  - `KnowledgeServerBootstrap.java` — added an eval-mode gate at the
    top of `tryIngestHelpFiles()`: skips help-doc auto-ingest when
    `Boolean.getBoolean("justsearch.eval.mode")` is true. Visibility
    relaxed `private → package-private` for testing.
  - `LuceneLifecycleManager.java` — removed `ctx.analyzerRegistry =
    null` from `close()` (per-instance schema state should not be
    released on close); tightened the `CLOSED → start()` rejection
    message to route callers to the instance-per-generation pattern;
    class Javadoc updated to document the single-use constraint.
  - `SqliteJobQueue.java` — added a `hasSufficientDiskSpace()`
    pre-check in `enqueue()` (50 MB threshold, fail-open on probe
    error) to pre-empt SQLITE_IOERR_FULL corruption.
  - `SectionProcessingOps.java` — removed a duplicate
    `AtomicBoolean` import (pre-existing; noticed during ops-test
    work).
  - `scripts/dev/dev-runner.cjs` — hard-clean mode now preserves
    `ui/` (mirrors soft-clean's existing whitelist), preventing
    `MODEL_PATH_REQUIRED` AI-activation footgun on dev resets.

- **Test code added (3 new files, ~17 tests):**
  - `KnowledgeServerBootstrapEvalModeTest` (skip + inverse for
    eval-mode gate)
  - `ContentLoadingOpsTest` (paged loading, fallback chain,
    cancellation)
  - `SectionProcessingOpsTest` (CONTEXT_TOO_LARGE retry,
    non-retryable degradation, cancellation)

- **Test code extended (3 files, ~13 tests):**
  - `JobQueueTest` (9 tests for SqliteJobQueue's previously
    untested methods: jobStateCounts, failureSummary, deletes,
    cleanup, integrity, health snapshot)
  - `IndexingControllerExcludesApplyTest` (per-pattern count
    correctness)
  - `ExcludeGlobsBarePatternTest` (3 matchingDirectoryPatternIndex
    tests)

- **Documentation/process additions:**
  - New `## Special Mode Flags` section in
    `runtime-config-ownership-matrix.md` cross-referencing 8 flags
    with set/read sites.
  - Two new bullets in CLAUDE.md `Verify Your Work`: audit
    conclusions need a runnable regression test; critical-analysis
    pass required for non-trivial changes.

### Bugs caught by critical analysis

Three silent bugs were caught by post-implementation critical
analysis (each had passed compilation + unit tests):

1. **Wrong gate flag.** Original Tier B #8 fix gated on
   `ResolvedConfig.Ui.automationEnabled` after a subagent suggested
   "reuse the existing flag." But `runHeadlessEval` doesn't set
   `JUSTSEARCH_UI_AUTOMATION` — it sets `justsearch.eval.mode`. The
   original fix would have had **zero effect** in real eval runs.
2. **Wrong audit conclusion on `LuceneLifecycleManager`.** A subagent
   confidently asserted `analyzerRegistry` was the only restart
   blocker. The fix shipped, then a regression test exposed the state
   machine and `indexingCoordinator` as additional blockers. Reverted
   to a defensive-only partial fix; full restart promoted to tempdoc
   406.
3. **Faulty negative-result trust.** A `git ls-files | grep ...`
   returned empty for `SSOT/docs/help/`, leading me to claim those
   files weren't tracked. They are — the grep had failed silently.
   The wrong claim propagated into both tempdoc 403 and a test
   Javadoc before being corrected.

### What 406 covers

Tempdoc 406 is an operational implementation plan for making
`LuceneLifecycleManager.start() → close() → start()` work on the same
instance. Six phases (lifecycle audit → taxonomy → state machine →
collaborator restart-safety → regression test → stress test → docs).
Estimated 5-10 dev-days. Not yet started. **Not duplicated here.**

### The diagnosis (where this tempdoc starts)

A long-term-quality critique of the shipped code observed: every
change is *responsive* (fixes a specific symptom) and none is
*generative* (introduces an abstraction that prevents the next
instance of the bug-class). Five candidate structural improvements
were proposed:

1. `EvalMode` type — single source of truth for the eval-mode flag.
2. `LuceneLifecycleManager` field grouping by lifecycle category.
3. `DiskSpacePolicy` interface for the 50 MB threshold.
4. Test-style register documenting when to use Mockito vs subclass-fakes
   vs recording-stubs.
5. Telemetry counters for the new gates (eval-mode skipped, low-disk
   refused).

The remainder of this tempdoc evaluates each. Proposals backed by a
documented bug-class instance are warranted under
`Structural Defects Don't Need Repeat Incidents`. Proposals without
that warrant fall to plain YAGNI — the rule has no opinion on them
beyond clarifying that it doesn't override YAGNI in their favor.

## Proposals

### 1. EvalMode type — single source of truth for `justsearch.eval.mode`

**Status: WARRANTED.** Bug class proven by Tier B #8.

**Diagnosis.** The wrong-gate bug was directly caused by no canonical
place naming the eval-mode concept. Three current read-sites:

- `LocalApiServer.java:638` — `Boolean.getBoolean("justsearch.eval.mode")`
  gates `/api/debug/reset-index`.
- `KnowledgeServerBootstrap.java:401` — same call, gates help-doc
  auto-ingest.
- `runtime-config-ownership-matrix.md` — documents the flag.

A subagent suggested reusing `ResolvedConfig.Ui.automationEnabled`
because it was a discoverable boolean accessor with semantically
similar-sounding name. Had `EvalMode.isActive()` existed as the
canonical surface, the suggestion would have been "reuse
`EvalMode.isActive()`" instead — and would have been correct.

**Proposal.** Introduce in `modules/configuration`:

```java
public final class EvalMode {
  private static final String SYSPROP = "justsearch.eval.mode";
  private EvalMode() {}
  public static boolean isActive() { return Boolean.getBoolean(SYSPROP); }
}
```

Refactor the two production read-sites to use `EvalMode.isActive()`.
Update the matrix doc's selection-rule paragraph to point at
`EvalMode` as the canonical reference (the magic string moves from
"the thing you read" to "an implementation detail of the type").

**Scope.** ~half-day. Surface area:
- 1 new class (~15 LOC including Javadoc).
- 2 production-code edits (one-line each).
- Matrix doc update (~3 lines).
- New `EvalModeTest` (~30 LOC).

**Risk.** Very low. The refactor is literal. The only judgment call
is whether to use a static class (proposed above) or a non-static
type with DI — static is consistent with the codebase's existing
sysprop-read patterns.

### 2. `LuceneLifecycleManager` field grouping → covered by tempdoc 406

**Status: ALREADY SCOPED ELSEWHERE.** Not duplicated here.

The lifecycle taxonomy (Phase 1 in tempdoc 406) categorizes each
field as schema-state / per-session / pre-start / lazy-session, with
the categorization made structurally evident in code rather than
Javadoc-only. This addresses the structural concern raised in the
critique. See tempdoc 406 Phase 1 for the operational plan.

### 3. `DiskSpacePolicy` — proactive disk monitoring

**Status: WARRANTED.** Bug class proven by tempdoc 201 Gap 1
(`docs/tempdocs/201-error-ux-recovery-flows.md:147`).

**Diagnosis (proven).** Tempdoc 201 documents the gap directly,
severity High:

> **Current**: Disk full detected reactively when Lucene write fails
> (`LuceneRuntimeUtils.java:157-160`). IOException message parsed for
> "no space" / "disk full".
> **Missing**: No proactive disk space check. No warning at 90% or
> 95% capacity. User discovers the problem only when operations fail.
> **Recommended fix**: Periodic disk space check in Worker (e.g.
> during health monitoring). Surface in `/api/status` as a warning
> field. Add `DISK_SPACE_LOW` to `deriveHealthEvents`. Add specific
> `DISK_FULL` user message to `errorMessages.ts`.

`docs/observations.md:98` corroborates the gap from a different
angle: "Error handling only tested for gRPC — no corrupt-index,
disk-full, OOM..." (from tempdoc 235 D4).

**Important context: the SqliteJobQueue low-disk guard from this
session (tempdoc 403 Tier E) is a partial precursor**, not the full
fix. It checks disk before `enqueue()` — useful, but only at one
location, with no status surface and no proactive warning. The full
fix per tempdoc 201 is Worker-level periodic monitoring with
`/api/status` integration. The 403 guard would either become the
queue-level integration of the broader policy, or be subsumed by it.
Reconciliation is part of the work.

**Proposal.** Either:
- **(A)** Execute tempdoc 201 Gap 1's recommended fix directly;
  add a one-line note to 201 marking it in progress.
- **(B)** Promote to its own tempdoc (408) with full operational
  plan modeled on tempdoc 406's phase structure.

A is cheaper if 201's recommended fix is enough specification. B is
right if the work needs more elaboration (audit current disk-related
error paths; design the periodic-check infrastructure; integrate
with `/api/status`; UI message + `errorMessages.ts` updates). I lean
toward B because the work touches multiple modules and tempdoc 201
is broader than this single gap — extracting the gap into a
dedicated tempdoc keeps both focused.

**Scope.** ~1-2 dev-days if pursued via path B, depending on how
existing `LuceneRuntimeUtils` disk detection is reconciled with the
new policy.

**Cross-reference.** The 403 SqliteJobQueue low-disk guard
(`SqliteJobQueue.hasSufficientDiskSpace()`) is the only existing
disk-space code in the codebase outside `LuceneRuntimeUtils`'s
post-hoc detection. Both should be unified under the policy.

### 4. ~~Test-style register~~

**Status: SPECULATIVE — DROPPED.**

The critique observed that this session introduced three different
mocking styles (Mockito for final classes, subclass-based fakes for
package-private collaborators, hand-written recording stubs for
service interfaces) and that future maintainers face cognitive
overhead choosing between them.

**Verified: no documented bug or productivity loss attributable to
test-style heterogeneity.** Greps over `docs/observations.md`,
`docs/tempdocs/`, and commit messages for "test style", "mocking
style", "fake vs mock", etc. surfaced nothing specific. The existing
`docs/reference/contributing/testing-quality.md` documents test
patterns generically; no incident is recorded. (This drop was
re-verified during the same pass that overturned DiskSpacePolicy and
telemetry counters; this one survived.)

No documented bug → no warrant from
`Structural Defects Don't Need Repeat Incidents`. Plain YAGNI then
applies. Drop. If a future incident shows test-style choice caused a
real problem, that becomes the warrant.

**Anti-counter-argument check.** Could the register be cheap
documentation that costs little to maintain? Yes, but per `Tempdoc Is
Your Contract`, a tempdoc commits to work that is necessary, not work
that is plausibly nice-to-have. This is the latter.

### 5. Telemetry counters for new gates

**Status: WARRANTED.** Bug class proven by codebase pattern
inconsistency: 17 sibling files emit counters for gates analogous to
the ones added in this session.

**Diagnosis (proven by pattern inventory).** A grep for
`Telemetry.Counter`, `counter(`, `counter.increment` across
`modules/` returned 17 production files using the pattern. Sample:

- `KnowledgeServer.java`, `SqliteJobQueue.java`, `SqliteQueueSwitchBufferOps.java` (worker-internal gates)
- `RemoteDocumentService.java`, `VduBatchProcessor.java`, `VduProcessor.java` (service-layer gates)
- `ApiErrorHandler.java`, `PagingCursorManager.java` (UI-layer gates)
- `MethvinWatcherStrategy.java`, `OcrProcessor.java`, `OcrGuards.java`, `TimeboxedContentExtractor.java` (indexing-layer gates)
- `IpcTelemetry.java`, `AgentTelemetry.java` (telemetry infrastructure)

The two new gates from this session (`KnowledgeServerBootstrap.tryIngestHelpFiles`
eval-mode skip; `SqliteJobQueue.hasSufficientDiskSpace` refusal) emit
log lines only and no counters. **They're inconsistent with the
established pattern**, which is the bug-class instance per
`Structural Defects Don't Need Repeat Incidents`.

**Proposal.** Add two counters following the existing pattern:

- `bootstrap.help_ingest.skipped` (with reason tag, e.g. `eval_mode`,
  `marker_present`) in `KnowledgeServerBootstrap`.
- `sqlite_queue.enqueue.refused_low_disk` in `SqliteJobQueue` (and
  consider whether other refusal reasons deserve sibling counters).

Both should use whichever Telemetry abstraction the surrounding code
uses (the 17-file inventory will show which is canonical for each
location).

**Scope.** ~1-2 hours. Surface area:
- 2 counter declarations (existing patterns to copy from sibling
  files in the same modules).
- 2 increment call-sites.
- Optional: 2 unit-test assertions verifying the counter increments
  when the gate fires.

**Risk.** Very low. Strictly additive; existing telemetry
infrastructure used.

**Cross-reference.** This item is essentially "make the new gates
look like the rest of the gates." If a future reader asks "why
does this gate emit a counter and that one doesn't?", the answer
should be a real reason, not "the agent who added it didn't follow
the pattern."

## Net warranted scope (post-verification)

| Proposal | Status | Effort |
|---|---|---|
| 1. EvalMode type | Warranted (Tier B wrong-flag bug) | ~half-day |
| 2. LuceneLifecycleManager grouping | Already in tempdoc 406 | (in 406's 5-10 days) |
| 3. DiskSpacePolicy | **Warranted** (tempdoc 201 Gap 1) | ~1-2 days; recommend promoting to tempdoc 408 |
| 4. Test-style register | Dropped on YAGNI (verified — no documented warrant) | — |
| 5. Telemetry counters | **Warranted** (17-file pattern inconsistency) | ~1-2 hours |

Three warranted items, one cross-reference, one verified drop. The
shape of the tempdoc has changed materially from the first draft —
two items moved from "speculative/needs-investigation" to "warranted"
because the verification produced direct contradicting evidence.

**Recommended scoping if all three warranted items are pursued:**

- Tier 5 (telemetry counters) → ~1-2 hours; do inline as a small
  follow-up to 403's gate work.
- Tier 1 (EvalMode) → ~half-day; do inline.
- Tier 3 (DiskSpacePolicy) → 1-2 days; promote to tempdoc 408 with
  full operational plan modeled on 406. Cross-reference tempdoc 201
  Gap 1 as the original diagnosis.

Tempdoc 407 then closes once tiers 1 and 5 land and 408 is opened.

## Critical confidence evaluation

This is the section where I need to apply the same skepticism to my
own proposals that I applied to the shipped code. Honest assessment:

### Confidence that the warranted item (EvalMode) is correctly
diagnosed and would prevent the next instance of the bug-class

**Medium-high.** The diagnosis chain is:
- Bug observed: subagent suggested wrong flag because no canonical
  type named the eval-mode concept.
- Proposed fix: introduce the canonical type.
- Mechanism: a future "reuse flag X" suggestion would either pick
  `EvalMode.isActive()` (correct) or pick something else — but the
  "something else" is now obviously a different *type*, not a
  similarly-named accessor.

The mechanism plausibly addresses the cause. But: a determined
subagent could still suggest reusing some other boolean accessor.
The type-level distinction reduces the surface for the mistake; it
doesn't eliminate it. The new CLAUDE.md rule (verify the flag is
*set* in the target scenario, not just declared) is the actual
last-mile defense. EvalMode reduces the search space; the rule
catches what slips through.

So the EvalMode proposal is real structural work, but it's
defense-in-depth, not the load-bearing fix. The load-bearing fix is
already done (the CLAUDE.md rule).

### Confidence that the drops are correctly dropped (post-verification)

**Wrong on one of two.** The first draft of this tempdoc dropped both
DiskSpacePolicy and downgraded telemetry counters based on
"no documented bug" claims that I never verified. A subsequent grep
pass produced direct contradicting evidence in both cases:

- DiskSpacePolicy: tempdoc 201 Gap 1 documents the exact gap with
  severity High and a recommended fix.
- Telemetry counters: 17 production files use the pattern; the new
  gates are clearly inconsistent.

Both moved to warranted. **The drops were not "high confidence"** as
the previous draft claimed; they were unverified-negative claims
masquerading as confident analysis. The same pattern produced the
SSOT/docs/help mistake earlier in the session.

**Test-style register drop survived re-verification.** Greps for
"test style", "mocking style", "fake vs", etc. surfaced no specific
incident. Registered as a real drop, not an unverified one.

### Confidence that I'm not making the same mistake I criticized

**Lower than the first draft claimed.** This session has now produced
three documented instances of unverified-negative claims:

1. `git ls-files | grep SSOT/docs/help` returned empty → claimed
   files weren't tracked. They were.
2. "No documented bug for DiskSpacePolicy" based on memory →
   tempdoc 201 documents exactly this gap.
3. "No clear telemetry pattern" without grepping → 17 files use it.

Each was caught by a single follow-up grep. The cost of the
verification is one Grep call per claim. The cost of skipping is
shipping wrong claims that propagate into tempdocs and require a
revision pass to correct.

**This is now a documented pattern, not a one-off slip.** My critical
analysis turns are biased toward (a) proposing abstractions as fixes,
and (b) asserting absence-of-evidence without verification. The
second is more dangerous because it produces *confident wrong
claims* that look like rigor but aren't. The mitigation: every
"no documented X" claim in a tempdoc should be backed by the actual
grep query that proved the absence, with results pasted inline. If
the query isn't there, the claim isn't verified.

### Confidence that the EvalMode work would actually get done

**Medium.** Tempdocs sit in the queue. Looking at this session's
output: tempdoc 403 closed cleanly because the user actively drove
the work through three rounds. Tempdoc 406 has not been started.
Tempdoc 407 (this one) would compete with 406 for attention. If the
user prioritizes 406's lifecycle refactor, 407 sits.

The honest expected-value calculation: half-day of EvalMode work
prevents perhaps one wrong-flag bug per quarter (extrapolating from
n=1 in this session). The CLAUDE.md rule prevents more, faster, at
zero ongoing cost. So EvalMode's marginal value over the rule is
real but small.

This is not an argument against doing the work; it's an honest read
on its priority relative to other open items. I would not be
surprised if 407 sits for months.

### Honest meta-point (post-verification)

This tempdoc went through three drafts. The first naively shipped all
five critique items as warranted work. The second strictly applied
YAGNI and (over-)dropped three. The third (this one) verified the
drops with greps and discovered two were wrong.

**Final shape:** three warranted items, one verified drop, one cross-
reference. About a day and a half of warranted work plus one
sub-tempdoc to open (408 for DiskSpacePolicy).

The most important takeaway is not the work items themselves but the
process artifact: **a tempdoc draft cannot be trusted just because it
applies the rules; the rules' inputs (claims about what the codebase
contains or doesn't) need to be verified independently.** The new
CLAUDE.md "Audit-driven fixes need a runnable test" rule applies by
analogy to tempdoc claims: an "absence claim" needs the actual
verification command attached, not just an assertion.

## Critical files

### For EvalMode (Tier 1)
- `modules/configuration/src/main/java/io/justsearch/configuration/EvalMode.java`
  — new class, ~15 LOC.
- `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java`
  line 638 — replace `Boolean.getBoolean("justsearch.eval.mode")` with
  `EvalMode.isActive()`.
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java`
  line 403 — same replacement.
- `docs/reference/configuration/runtime-config-ownership-matrix.md`
  — update the `## Special Mode Flags` selection-rule paragraph to
  reference `EvalMode` as the canonical access point.
- `modules/configuration/src/test/java/io/justsearch/configuration/EvalModeTest.java`
  — new, two tests (active when set, inactive when not).

### For Telemetry counters (Tier 5)
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java`
  — add counter for help-ingest skip with reason tag (eval_mode,
  marker_present). Use the Telemetry pattern already present in
  sibling files (see `IpcTelemetry.java` for KnowledgeServerBootstrap-
  adjacent example).
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java`
  — add counter for `enqueue` refusals with reason tag (low_disk,
  closed, error). The class already uses `Telemetry.Counter` for
  `switchBufferWriteFailuresCounter`; add a sibling.
- Test additions: extend `KnowledgeServerBootstrapEvalModeTest` and
  `JobQueueTest` to assert counter increments when the gates fire.

### For DiskSpacePolicy (Tier 3 — recommend tempdoc 408)
- See tempdoc 201 Gap 1 for the original spec.
- Files implicated: `LuceneRuntimeUtils.java:157-160` (existing
  reactive detection), `SqliteJobQueue.hasSufficientDiskSpace()`
  (precursor from 403 Tier E), `/api/status` builder, `errorMessages.ts`.
- Defer detailed file list to the dedicated tempdoc.

## Verification

- `./gradlew.bat :modules:configuration:test` covers the new class.
- `./gradlew.bat build -x test` clean (PMD + Spotless +
  integrationTest).
- Module tests for the two refactored sites still green.
- Optional: `KnowledgeServerBootstrapEvalModeTest` continues to pass
  unchanged — the test sets the sysprop directly, so the refactor
  to `EvalMode.isActive()` is transparent. This is also a small
  signal that the refactor preserves behavior.

## Provenance

Created 2026-04-24 from this session's critical-analysis-of-implemented-
changes turn. The original critique proposed five structural
improvements.

**Three drafts:**

1. **First draft** naively committed all five as warranted work.
2. **Second draft** strictly applied YAGNI and dropped three
   (DiskSpacePolicy, test-style register, telemetry counters as
   "needs investigation"). Status said exactly one (EvalMode) was
   warranted.
3. **Third draft (this one)** verified the drops with greps and
   discovered:
   - DiskSpacePolicy is documented in tempdoc 201 Gap 1 with
     severity High and a recommended fix → moved to warranted.
   - Telemetry counters are an established 17-file codebase pattern
     → moved from "needs investigation" to warranted.
   - Test-style register survived re-verification → drop holds.

Final shape: 3 warranted, 1 cross-reference (LuceneLifecycleManager
in 406), 1 verified drop (test-style register).

The verification process matters as much as the result: each
unverified-negative claim from the second draft was caught by a
single follow-up grep. The session has now produced three documented
instances of the same pattern (SSOT/docs/help, DiskSpacePolicy
absence, telemetry pattern absence). Treating "absence claims"
without attached verification commands as untrusted-by-default is
the discipline shift this tempdoc most directly demonstrates.

---

## Round 5 (2026-06-11) — all-areas takeover

First round under the generalized (non-backend-only) scope. Inbox at start:
**180 open / 159 done**.

### #379 — Indexer permanently fails a document on an over-long title (worker-health loop)

**Symptom (as reported).** A document whose extracted title exceeds the
title-metadata max length fails extraction (`… title metadata exceeds max
length`) → `Job permanently failed after 1 attempts` → the doc stays "missing",
so every `syncDirectory` re-enqueues + re-fails it. A constant error loop that
flips the worker to `status:ERROR` / `is_healthy:false` while the backend is
otherwise serving. Found live on a `docs/tempdocs` sync; the trigger was the 565
tempdoc's 5846-char `title:` frontmatter (observation #380).

**Root cause (corrected the observation's file pointer).** The observation
pointed at `JobBatchExtractor`, but the throw is in
`ExtractionArtifact.validateScalar("title", …)` — a defensive backstop that
rejects the *entire* artifact when `title.length() > MAX_SCALAR_METADATA_CHARS`
(4096). The title is derived from user content (Tika `TITLE` → YAML frontmatter
`title:` fallback) and was never bounded before reaching that backstop, so an
arbitrarily long frontmatter title makes the whole document permanently
unindexable. (`audit-without-test` / verify-the-pointer: the symptom site ≠ the
throw site.)

**Fix (root cause, single construction point).** Bound the title in the
`ContentExtractor.ExtractionResult` record's **compact constructor** — every
extraction path (both `ContentExtractor` and `StructuredContentExtractor`, plus
any future producer or test) flows through it, so no site can bypass the bound.
Truncates to `MAX_SCALAR_METADATA_CHARS` with a trailing ellipsis;
`validateScalar` stays the strict backstop for `mimeType` / `author` (those
being arbitrarily long is genuinely anomalous, so they keep rejecting). Made
`ExtractionArtifact.MAX_SCALAR_METADATA_CHARS` package-private so the bound and
the validation share one source of truth.

**No weakened test.** A test scan confirmed nothing asserted the over-long-title
rejection — it was an unintended side-effect, not a tested contract — so the
fix weakens no existing coverage. Added `ContentExtractorTest.TitleBounding`
(2 tests: the 5846-char repro is bounded + ends with the ellipsis; an
at-limit/normal/null title passes through unchanged). `:modules:worker-services:test
--tests "*ContentExtractorTest*"` green.

**Files.** `ContentExtractor.java` (bound + helper), `ExtractionArtifact.java`
(constant visibility), `ContentExtractorTest.java` (regression).

**#380 cross-reference.** The fix resolves the breaking *loop* (the 565 doc now
indexes), leaving #380 as pure doc-hygiene (a 5846-char title still truncates to
a garbage indexed title — it still deserves a real one-line title). Left open.

### #376 — Raw NUL byte as a dedup-key separator in `UnifiedChatView` (binary-file hazard)

**Symptom.** `renderLiveOverlay` built its record-vs-live dedup keys as
`kind` + separator + `content`, where the separator was a literal **0x00 byte**
embedded in the template literals (actual lines 2254/2260, not the 2252/2258 the
observation estimated). A raw 0x00 makes the whole file read as *binary*: `grep`
skips it, and — as the observation notes — it had already let a merge conflict
hide inside the file undetected.

**Investigation (verify-don't-guess).** The lines render as an ordinary space in
a text view, so the issue is invisible to inspection. A byte-level scan confirmed
exactly 2 raw 0x00 bytes on those lines; the surrounding code uses them as a
collision-proof join separator (message `content` can contain spaces, but never a
NUL — so a NUL separator can't be forged by content).

**Fix (behaviour-preserving source hygiene).** Replaced each raw 0x00 byte with
its **source escape** (the Unicode NUL escape, six ASCII characters) in both keys
via a byte-level rewrite — the Edit tool cannot type a control byte. The compiled
output is byte-identical (the escape *is* a NUL at runtime), so the NUL separator
and its collision-proofness are kept; only the on-disk encoding changes from
binary to plain text. No logic changed → no behaviour test owed; verification is
`npm run typecheck` (green) + a re-scan showing **0 raw NUL bytes remain**.

**Self-inflicted aside (logged here as method discipline).** Writing the
resolution note, the Edit tool converted the escape sequence I typed into 4 *new*
raw 0x00 bytes inside `docs/observations.md` — the exact bug, reintroduced into
the doc. Caught it with the same byte-scan and rewrote those to literal escape
text. Lesson: never author a control-char escape through the Edit tool; write it
with a byte-level script and re-scan the target.

**Files.** `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` (the fix);
`docs/observations.md` (the tick + the self-inflicted re-fix).

### #374 — Undeclared `execution-surface` referencers (citation files)

**Symptom.** The `execution-surface` gate was red: two chat files reference the
canonical-record scan types (`tsRefPattern = (SearchTrace|RetrievalCitation)`)
but weren't in `governance/execution-surfaces.v1.json`. (Not CI-enforced — the
gate isn't wired into `ci.yml` — but a register-integrity debt regardless.)

**Investigation (projection-vs-fork, same shape as the operation-surface item).**
Read both: `citationResolve.ts` genuinely `import type { … RetrievalCitation }`
and uses it as a parameter type — it is the ONE RAG claim→Citation resolver
(565 §15.B), a real **consumer** in the 559 Authority IV evidence lineage.
`MarkdownBlock.ts` only *mentions* `RetrievalCitation` in a JSDoc line that
literally ends "so the block stays a pure renderer" — a comment-only false
positive of the content-regex scan (the same scanner-precision limit that bit
the operation-surface item's `eventStreamProjection.ts`).

**Fix.** Registered `citationResolve` as `evidence-fe-claim-resolver`
(`kind:consumer, guard:self`) next to the `fe-citation-types` carrier it
consumes; de-flagged `MarkdownBlock` by dropping the literal type token from its
JSDoc (it is not a surface — registering it would misrepresent a pure renderer).
JSON valid; `MarkdownBlock` no longer matches the scan; **`execution-surface`
gate passes (0 findings)**. The `MarkdownBlock` edit is comment-only, so the gate
is the verification (no typecheck impact).

**Files.** `governance/execution-surfaces.v1.json` (the consumer registration);
`modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts` (JSDoc de-flag).

### #375 — BrowseSurface bypasses the SurfaceLayout `.body` region

**Symptom.** `BrowseSurface` composes `surfaceLayoutStyles` (so the layout-purity
gate passes) but renders its tree in a hand-rolled `<div class="scroll">` instead
of the shared `.body` region every other surface uses — a contract bypass, not a
gate failure.

**Investigation.** Diffed the two: `.scroll` was `flex:1; overflow-y:auto;
padding:0.5rem 0` — `flex`+`overflow` are *exactly* what `surfaceLayoutStyles
.body` already provides; the only bespoke part is the padding (edge-to-edge tree
rows, which own their own horizontal inset). This is precisely the "two
authorities for one concept" / rhythm-drift defect the `surfaceLayout.ts` doc
comment describes (a per-surface `rem` literal diverging from the `--density-*`
region rhythm).

**Fix (behaviour-preserving).** Switched the container to `class="body"` and
deleted the duplicated `flex`/`overflow` (now inherited from
`surfaceLayoutStyles`), keeping only a bespoke `.body { padding: 0.5rem 0 }`
override — which the primitive explicitly allows ("surfaces compose it and keep
only bespoke rules"). The override preserves the exact edge-to-edge look, so the
change is zero-visual — **no ui-shot owed** (a true region-rename, not a restyle).
typecheck green; layout-purity gate green; no `.scroll` remnant.

**Files.** `modules/ui-web/src/shell-v0/views/BrowseSurface.ts`.

### #380 — 565 tempdoc 5846-char title (closed: already fixed)

A **stale closure** — and a `verify-don't-guess` save. The item was logged as the
trigger for #379, so I went to shorten the title — but measured first: the
current `title:` is **203 chars**, not 5846. `git log` showed commit `bb332be3b`
("fix(565): shorten the 5846-char frontmatter title that broke indexing")
already did it (the giant design-summary was preserved as a body summary). Had I
trusted the observation's number, I'd have re-shortened an already-short title.
Closed citing the fix; the indexer-loop half stays hardened by #379. No code
change.

### #377 — ResourceAreaValidator §4.2 not run against prod catalogs (deferred w/ analysis)

The first item this round that is **not a quick fix** — and a place where shipping
a fast test would *violate* the very discipline the item is about
(`audit-without-test` → don't manufacture false confidence).

**Investigation.** `ResourceAreaValidator.validate(catalog) → List<Finding>`
(empty = valid) is the entry point; §4.2 = the Category×SubscriptionMode matrix,
HistoryPolicy required-when, and TABULAR primaryKey rules. Today only
`ResourceAreaValidatorTest` (synthetic fixtures) exercises it.

**Why a quick unit test is wrong.** The faithful conformance target is the
*complete authoritative* production catalog set — and that is **15** catalog
classes spanning three substrate clusters (`resources` + `advisory` +
`operation-history`), assembled only from a fully-wired `HeadAssembly`
(`LocalApiServer.java:308`). The one test-safe entry point,
`ResourceSubstrateInit.run()`, yields only the **8** resource-cluster catalogs
*and* performs a global `diagnosticChannelAppenderInstaller.attach()` side-effect.
A unit test over those 8 would validate an INCOMPLETE set — false confidence,
the exact failure mode #377 flags.

**Correct fix (one of), recorded for the next agent.**
- (a) A bootstrap-backed conformance test (integration-tier, in `modules/ui`)
  that enumerates the same list `LocalApiServer` wires and asserts
  `validate()` returns no findings for every prod catalog; or
- (b) Fold the §4.2 shape rules into the `observed-happening` gate's existing
  prod-catalog scan (it already enforces the channel-vs-Resource axis against
  prod — the natural home for the finer shape axis).

**Trigger to resurrect.** The next agent adding/altering a Resource shape, or
wiring a new `ResourceCatalog`. Left open (`[ ]`) with this analysis attached so
the investigation isn't repeated.

### #32 — bash-guard force-push regex has no quote-awareness (false positive)

A security-guard *false positive* (over-block, errs safe): the
`DESTRUCTIVE_EVERYWHERE` force-push regex matched `git push --force` even when it
was only quoted DATA — `echo "git push --force"`, a commit message — so harmless
commands that merely *contain* the literal were blocked.

**Fix.** Added `stripQuotedLiterals(cmd)` (replaces the contents of single/double
quoted strings with empty) and ran the `DESTRUCTIVE_EVERYWHERE` checks against the
stripped command. A real force-push is never wholly inside quotes (the shell would
treat it as a string), so unquoted force-pushes — `--force`, `-f`, the `+refspec`
form — still block; only quoted-DATA literals stop matching.

**Security care (both directions tested).** The risk in "fixing" an over-block is
introducing an under-block. Verified with 7 new + 44 existing `bash-guard.test.mjs`
cases (**51/51 green**): `echo "git push --force"` / `printf '… git push --force …'`
/ `git commit -m "… git push --force"` now pass; `git push --force`,
`git push --force && echo "done"` (real push beside a quoted arg), and the
`+refspec` form still block.

**Dogfooding caught the escaped-quote gap.** The first regex (`"[^"]*"`) stopped at
an escaped `\"`, so committing THIS fix — whose commit message contains
`\"git push --force\"` — was itself blocked by the guard. Hardened the
double-quoted branch to consume escapes (`"(?:\\.|[^"\\])*"`) and added a regression
case. A neat instance of the tool failing on its own change before it shipped.

**Honest limit (commented in source).** `bash -c "git push --force"` executes a
quoted command and is NOT caught — intentional-evasion territory, out of scope for
a guard against ACCIDENTAL actions. Scoped to the reported `DESTRUCTIVE_EVERYWHERE`
case; `DESTRUCTIVE_IN_MAIN` / sleep share the false-positive class and could adopt
the same helper as a follow-up.

**Files.** `scripts/agent-analytics/hooks/bash-guard.mjs` (helper + applied);
`scripts/agent-analytics/hooks/bash-guard.test.mjs` (7 cases).

### #210 — JfHealthEvent skip-on-button (investigated, NOT fixed — riskier than logged)

Started this (change `closest('button')` → a recovery-specific selector) but
stopped before a blind edit. The recovery buttons are **not** in `JfHealthEvent`
nor its `healthEventActivityRow` strategy — the host listener catches clicks that
bubble from elsewhere, and it's unclear what `closest('button')` currently matches
given the shell uses custom-element controls (`jf-button`/`jf-control`, which
`closest('button')` would NOT match). Changing the selector without first
enumerating what actually bubbles here risks a regression (a recovery click that
no longer suppresses selection → double-fires). Left **open** with this note; the
correct fix needs the click-target inventory first. (The current broad skip is
safe; the defect is latent — no non-recovery button exists yet.)

### #180 — AiInstallServiceLateBindTest "2 tests failing" (closed: stale)

A verified stale-closure. Ran the two named tests
(`:modules:app-services:test --tests "*AiInstallServiceLateBindTest"`) → **2/0/0
green**, consistent with the green full-suite CI run. Fixed sometime since the
2026-05-16 report. Bonus correction: the recorded path was wrong — the test lives
in `modules/app-services/...ai/install/`, not `modules/ui`. No code change.

### #200 — jf-chat-shape-mount doesn't forward host_ (closed: resolved)

Another verified stale-closure, resolved by the slice 491 §9.D refactor. The
recorded file (`views/ChatShapeMount.ts`) no longer exists — it moved to
`components/chat/ChatShapeMount.ts`, and the current version DOES forward `host_`:
a reactive property that re-mounts on change and passes through
`mountView(factory, { apiBase, ...(host_ !== undefined ? { host_ } : {}) })`, so
the inner view gets the host (no direct-import fallback). The ε3-era bug is gone.
No code change.

---

## Round 5 running ledger (as of 2026-06-11)

| # | obs | outcome |
|---|---|---|
| 1 | #379 | fix — indexer over-long-title truncate (worker-health loop) |
| 2 | #376 | fix — escape raw NUL dedup separator |
| 3 | #374 | fix — register citationResolve / de-flag MarkdownBlock |
| 4 | #375 | fix — BrowseSurface uses the SurfaceLayout `.body` region |
| 5 | #380 | stale-closure — 565 title already shortened |
| 6 | #377 | deferred w/ analysis — prod-catalog conformance needs full bootstrap |
| 7 | #32 | fix — quote-aware force-push guard (+ dogfooding catch) |
| 8 | #180 | stale-closure — LateBind tests green |
| 9 | #210 | investigated, open — selector swap needs click-target inventory |
| 10 | #200 | stale-closure — host_ forwarding fixed in slice 491 |

**5 fixes + 3 stale-closures (8 resolved) + 1 deferral + 1 open-with-note.** Inbox 180 → 172 open.
