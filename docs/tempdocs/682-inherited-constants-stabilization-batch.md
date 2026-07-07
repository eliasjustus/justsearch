---
title: "Inherited-constants stabilization batch: (1) measure the worker heap before trusting its 512m default, (2) pin the external llama-server build and surface its version in the runtime manifest, (3) collapse three literal-duplication constant clusters onto single authorities. Three small, independent, measurement-first items batched because they share one root: load-bearing operational constants whose provenance is invisible at the use site. Scoped from a read-only constants-provenance sweep (2026-07-06); companion defect/observation lines are in the observations inbox (session shard 0bdd87e8, commits 2875cd2 / 2ef7396)."
type: tempdocs
status: "implemented — all three items done on branch worktree-682-constants (commits adfb1d1, 267b094, 09b1f19); see §As-built. Two live-run sub-items deferred and named there: the real cuda12 stage producing the version marker (~600 MB download) + the mismatch drill; and a future Tika-PDF-pressure re-measurement once a real PDF corpus exists locally. Not yet merged; PR pending owner direction."
created: 2026-07-06
author: agent analysis session 2026-07-06 (read-only constants-provenance sweep over modules/*/src/main + configuration defaults; ~560 operational constants classified; this batch is the act-now subset)
category: stabilization / operational-constants / provenance
related:
  - 640-performance-ratchet   # the perf ratchet measures the surfaces these constants govern; item 1's measurement should reuse its harness conventions where possible
---

> **Scope note.** This batches the three smallest-footprint, highest-certainty actions from a
> constants-provenance sweep of `main` (2026-07-06). The sweep's broader finding — most
> operational constants carry no visible provenance, with a clear temporal gradient (recent
> constants cite measurements; founding-era constants don't) — does NOT warrant a retroactive
> annotation campaign and none is proposed. These three items qualify because each is either
> measurably load-bearing (1), a structural one-time flip (2), or a live drift hazard (3).

# 682 — Inherited-constants stabilization batch

## Item 1 — Measure the worker heap; then resize or annotate `DEFAULT_WORKER_HEAP`

**Current state.** `KnowledgeServerConfig.java:43` sets `DEFAULT_WORKER_HEAP = "512m"`
(env-overridable via `JUSTSEARCH_WORKER_HEAP`); `WorkerSpawner` now also passes `-Xms=-Xmx`,
so the full amount is resident from boot. No measurement, tempdoc, or comment derives the
value, and the worker's heap-resident work has grown far past what the value was originally
sized for (Tika extraction, NER decoding, chunking, SPLADE term maps, gRPC assembly — the
ONNX/Lucene-mmap footprint is off-heap and not the concern).

**Work.** One instrumented indexing run over a heavy mixed corpus (large PDFs + office docs;
an existing eval corpus is fine) recording heap watermarks (`-Xlog:gc` or the existing RRD
metric store), plus one search-under-indexing pass. Then exactly one of:
- the measurement shows real headroom pressure → raise (or make adaptive) the default, citing
  the run; or
- the measurement shows 512m comfortably correct → keep the value and write the derivation
  next to it (the `DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS` comment style: defect/measurement/margin).

**Acceptance.** The constant's site cites a dated measurement either way. A 4× error in
either direction is no longer possible to ship silently: OOM-under-merge (too low) and ~2 GB
stolen from co-resident inference (too high) both become claims the citation answers.

## Item 2 — Pin the external llama-server build; surface its version in the runtime manifest

**Current state.** The Brain process is an external `llama-server.exe` staged by the Gradle
cuda-variant task; nothing pins its build, no startup check asserts the version, and the
runtime manifest does not carry it. Version drift therefore surfaces as behavioral bugs
(flag semantics, `/props` shape, sampling defaults) with no declared expectation to diff
against. The process boundary already contains the blast radius (HTTP/JSON, not ABI) — this
item is about making drift *visible and intentional*, not about hard-failing.

**Work.**
- Pin the staged llama-server to an explicit build/version in the staging path (the download
  already targets a release; make the version an asserted input, not an incidental one).
- Record the expected + actually-running version (from `GET /props` at adoption/startup) in
  the runtime manifest alongside the existing reason codes.
- `InferenceLifecycleManager` logs loudly (and the manifest reflects) on mismatch;
  fail-closed is NOT proposed — adoption of an externally-started server is a supported flow.

**Acceptance.** A deliberate version swap of the staged binary produces a visible
expected-vs-actual mismatch in the runtime manifest and log; the normal path records the
pinned version. One-time structural flip; ~zero standing cost.

## Item 3 — Collapse three literal-duplication constant clusters onto single authorities

**Current state (all verified on `main`, also logged to the observations inbox):**
1. The ~11.5 GB VRAM threshold literal exists three times: `VramRequirements.java:30`,
   `VramDetector.java:36`, `VramFlagsUtil.java:87` — the last carries a comment warning the
   copies are parallel.
2. A GPU-saturation window trio is duplicated across processes: `OperationalMetrics.java:433-436`
   (worker) vs `GpuSaturationMonitor.java:23-29` (head).
3. An unexplained `9000` is hand-coupled FE/BE: `FrameHistoryRingBuffer.java:30` (SSE replay
   capacity) vs `bootIntentStreamBridge.ts:44` (FE dedup LRU) — divergence on one side only
   yields replay gaps or duplicate-event storms after reconnect.

**Work.** One authority per cluster: a shared constants holder for (1) and (2) (same-language,
cross-module — ordinary shared class placement, no new mechanism), and for (3) the repo's
existing codegen-from-authority pattern (`LivenessWindows` → `liveness-constants.ts` is the
in-house template) or, minimally, a checked comment-pair the wire gate already knows how to
verify. Do not redesign the values themselves — this item changes *authority count*, not
behavior; value changes, if any, belong to their own measured work.

**Acceptance.** Each literal exists exactly once per language boundary; the FE/BE pair either
codegen'd or drift-checked. Grep for the literals finds one authority + references.

## Explicitly out of scope

- Retroactive provenance annotation of the remaining provenance-less constants (ceremony;
  the observed norm — new/touched constants citing their derivation — is already trending
  correctly without enforcement).
- Any behavior/value tuning beyond what Item 1's measurement itself justifies.
- The fusion-fallback drift defect (`HybridSearchOps` 10/0.3 vs builder defaults 3/0.25) —
  already an observations-inbox item; it is a one-line bugfix a triage pass should take
  (whoever fixes it must decide which value is the *intended* default before aligning).

## As-built (2026-07-06, implementing session, branch `worktree-682-constants`)

### Item 3 — DONE (commit `adfb1d1`)

- **Cluster 1 (VRAM threshold):** authority is now `VramRequirements.COMFORTABLE_VRAM_BYTES`
  (`VramRequirements.java:32`); `VramDetector` and `VramFlagsUtil` reference it. Pre-unification
  check confirmed all three literals were identical (no hidden drift). A stale comment in
  `VramDetector` referencing a constant name that never existed (`TWELVE_GB_THRESHOLD`) was
  replaced by the authority pointer. Acceptance grep: one production literal + one deliberate
  boundary-value pin in `VramFlagsUtilTest` (`@CsvSource` input, kept as a regression pin).
- **Cluster 2 (monitor window trio):** authority is new `RollingMonitorWindow`
  (`modules/telemetry/.../RollingMonitorWindow.java` — `WINDOW_MS`/`MAX_GAP_MS`/`MAX_SAMPLES`);
  both `OperationalMetrics` (worker; note: lives in `worker-core`, not `worker-services` as this
  doc originally said) and `GpuSaturationMonitor` (head) alias it. `telemetry` chosen because it
  is already a direct dependency of both consumers and a layering-leaf — **no new module edge**;
  `LayeringEnforcementTest` green. Values unchanged.
- **Cluster 3 (FE/BE 9000):** codegen absorption was evaluated and **rejected as
  disproportionate** (the liveness generator family is shape-specialized and its generated Java
  lives in `modules/ui`, which `app-observability` cannot import; the "wire gate comment-pair"
  option this doc hypothesized does not exist — the wire gate is contracts-only). Implemented
  the cheapest existing-mechanism check instead: a cross-language drift test in
  `bootIntentStreamBridge.test.ts` (regex-reads both files, asserts equality), following the
  existing cross-tree-read test pattern. **Mutation-verified**: setting the FE value to 9001
  fails the test with `expected 9001 to be 9000`. Comment pairs at all four sites.
- Verification: `spotlessApply` + `build -x test` green; module tests
  `:gpu-bridge :worker-core :ui :app-observability :telemetry` green; FE `test:unit:run` 3510
  tests green (includes the new drift test). Known pre-existing red: `npm run typecheck` fails
  at the branch base on TS5101/`baseUrl` (tracked in observations; not caused here).

### Item 2 — DONE, one acceptance sub-item deferred to a live run (commit `267b094`)

- **Pin:** the authority already existed (`llamaPrebuiltVersion = "b8571"`,
  `modules/ui/build.gradle.kts`); what was missing was assertion. `stageLlamaCudaVariant` now
  writes a machine-readable `runtime-version.txt` into `variants/cuda12/` (same convention as
  the existing CPU-stage stamp; consumed by `RuntimeRestoreUtil` and copied by dev-runner's
  flat-file copy — dev-runner untouched).
- **Compare:** new pure `LlamaServerBuildCheck` (parses `bNNNN` from marker and from `/props
  build_info`; exact-match; unknown-tolerant) — 11 unit tests green.
- **Surface:** `ServerPropsOps.applyBuildInsightsFromProps` records the actual build on BOTH
  the managed-start and adoption paths (set-sites verified, not assumed); WARNs once per
  (expected,actual) pair on mismatch; missing marker ⇒ expected=unknown, actual recorded, no
  warning (externally-started servers are a supported state). `RuntimeManifest.AiInfo` gains
  nullable `serverBuildExpected`/`serverBuildActual`, published live via
  `RuntimeManifestListenerWiring`; the redaction test asserts the pair survives the public
  projection.
- Verification: full `gradlew test` green; `check-runtime-manifest-closure` 0 violations (no
  new runtime files); `:modules:app-api:updateSchemas` run post-hoc by the orchestrator —
  **zero content changes** (EOL-only phantom diffs restored), i.e. the manifest fields touch no
  generated schema surface. **Deferred to a live run:** executing the real ~600 MB stage (the
  marker write is in `doLast`, verified by dry-run/config inspection only) and the "mismatch
  drill" against a live server.
- Out-of-scope finding logged to observations: the cuda12 zip download is URL-pinned but not
  SHA-256-pinned, unlike the CPU zip.

### Item 1 — DONE: measured, verdict RAISE, default now `1g`

**Corpus reality check first:** the machine holds no real PDF corpus (`ohr-bench-tika-pdf` is
*pre-extracted text*, not PDFs; only 3 fixture PDFs exist anywhere). The measurement therefore
covers the **enrichment-pipeline watermark**, with live Tika-PDF/office parse pressure
explicitly unexercised — stated in the constant-site annotation as a scope limit (and it makes
the measured pressure a lower bound, since parse buffers only add).

**Measurement (2026-07-06):** `jseval run --dataset mixed/desktop-mixed-v1 --max-queries 0
--pipeline --start-backend --clean` (2286 mixed desktop docs; primary indexing + chunking +
embedding + SPLADE + NER on GPU), worker verified on its live command line to run the shipping
default `-Xms512m -Xmx512m` plus `-Xlog:gc:file=tmp/worker-gc-682.log`. The monitoring process
was externally stopped twice; the second run's backend kept indexing to ~74% enrichment
(embed 74% / SPLADE 73% / NER 1700/2287 / chunks 16%) before the stack was cleaned up, giving
**543s of GC log under real load (153 GC events)** — partial, but decisive:

- **After-GC live-set peak: 348M of 512M (68% occupancy).**
- **Heap repeatedly full before collections** (499–512M before-GC values).
- **5 G1 evacuation failures** — two `G1 Humongous Allocation`-triggered — e.g.
  `GC(142) Pause Young (Mixed) (Evacuation Failure: Allocation) 512M->310M(512M)` at 482s and
  `GC(7) ... (G1 Humongous Allocation) (Evacuation Failure: Allocation) 499M->211M(512M)` at
  5.4s. No `Pause Full` and no OOM — the worker survives, but with zero comfort margin, at
  only ~74% enrichment, on a modest 2286-doc corpus, without PDF parsing.

**Verdict: the Nov-2025 `512m` is not comfortably correct — raised to `1g`** (live-set peak
becomes ~34% occupancy; measured 2× raise, not the speculative 4×). The derivation is written
at the constant site (`KnowledgeServerConfig.java`, `DEFAULT_WORKER_HEAP` javadoc) per the
item's acceptance: dataset, date, numbers, scope caveat, and the `JUSTSEARCH_WORKER_HEAP`
override for constrained devices. Full GC log preserved at the worktree's
`tmp/worker-gc-682.log` (gitignored) for re-inspection.

**Verification:** `spotlessApply` + `build -x test` green; `:modules:app-services:test` green
(task executed, not up-to-date); no test asserts the old default (the only test heap reference
is an explicit `"256m"` parameter, unaffected).

**Follow-up worth one future measurement (not this tempdoc):** re-run the same instrumented
recipe once a real PDF/office corpus exists locally, to close the Tika-pressure scope gap.

## Session close-out: handoff state (2026-07-07)

Written so a continuing agent needs nothing beyond this document and the branch.

**Branch state:** `worktree-682-constants`, clean tree, commits `adfb1d1` (item 3), `267b094`
(item 2), `09b1f19` (item 1 + as-built), `4d108cc` (status), plus the close-out commit carrying
this section and the `environment-variables.md` default correction. (Commit hashes are
pre-squash branch references — they identify work until merge; the durable evidence pointers
are the test names, commands, and numbers recorded per item above.)

**Verified claims — every claim above carries its pointer**; the pointer classes used:
named unit tests (`LlamaServerBuildCheckTest` 11/11, `InferenceLifecycleManagerPropsInsightsTest`
4/4, `RuntimeManifestSchemaCompatibilityTest` 6/6, redaction 4/4, the FE drift test inside
`bootIntentStreamBridge.test.ts` incl. its 9001-mutation check), named commands with outcomes
(`spotlessApply`, `build -x test`, module `:test` tasks, `check-runtime-manifest-closure` = 0
violations, `updateSchemas` = content no-op, FE `test:unit:run` 3510 green), and the GC-log
numbers quoted verbatim in §Item 1. Set-site verification for item 2 (wrong-gate check),
re-done independently of the implementing pass: `applyBuildInsightsFromProps` is called from
`updateFromPropsBestEffort` (`ServerPropsOps.java:71`), which fires from both probe paths
(`LlamaServerOps.java:493` and `:677`).

**Unverified assumptions (explicitly NOT verified in this session):**
1. That the real `stageLlamaCudaVariant` execution produces the `runtime-version.txt` marker —
   the write lives in `doLast` and was verified by dry-run/config inspection only (~600 MB
   download not executed).
2. That llama-server b8571's live `/props` actually carries `build_info` in the parsed shape —
   code is defensive (absence ⇒ expected/actual = unknown, never an error), but the positive
   path has not been observed against a live server; the "mismatch drill" acceptance is
   likewise pending.
3. That `1g` is *sufficient* — the measurement proves 512m lacked margin (evacuation failures
   at ~74% enrichment, no PDF pressure); it does not prove 1g absorbs a full run plus Tika-PDF
   load. Lower-bound reasoning says it is a strict improvement; the §Item 1 follow-up
   measurement closes this.
4. The full `gradlew test` suite was last run green at item-2 completion; item 1 afterwards
   changed one constant + javadoc and re-ran `build -x test` + `:modules:app-services:test`
   only. Pre-merge must re-run the full suite (see below).

**Pre-merge checklist for whoever publishes this branch:**
- Full `./gradlew.bat test` on the final branch state (subset-isnt-the-suite).
- The ui-web pre-merge gate set for the two touched FE files
  (`bootIntentStreamBridge.ts/.test.ts` — `modules/ui-web/src/**` row of the CLAUDE.md table).
- Known pre-existing red, NOT from this branch: `npm run typecheck` fails at the branch base
  (TS5101 deprecated `baseUrl`, `modules/ui-web/tsconfig.json`) — already in the observations
  inbox; do not "fix" it by weakening this branch.
- `docs/reference/configuration/environment-variables.md` was corrected here (default `1g`);
  regen sequence (`llmstxt-generate`, `skills-sync`) already run — no output drift.

**Environmental note for future measurement runs on this machine:** both long-running
background measurement runs in this session were stopped by an external signal (not a crash —
`jseval`'s monitor died while the backend survived). If this recurs, prefer letting the backend
run detached and polling `/api/status` from short-lived commands; also note the second kill
left a half-dead stack (Head alive with dead HTTP, Worker alive but idle) that needed manual
`taskkill` — check for orphaned `HeadlessApp`/`indexer-worker` processes after any aborted run.
Related tooling observation (in the inbox): `prepare-worktree.cjs` fails at its gradlew spawn
step on this environment; run the two `installDist` tasks directly instead.

**Not forgotten (routed, no action here):** the fusion-fallback drift defect (10/0.3 vs 3/0.25)
stays an inbox item for triage; the cuda12 zip lacking a SHA-256 pin (unlike the CPU zip) is in
the inbox; the "reverse doc-audit" observation that enforcement sometimes outlives
documentation is tracked outside this tempdoc.

## Verification map

Item 1: the measurement run itself + the citation landing at the constant site.
Item 2: manifest field present in a live run; mismatch drill logged.
Item 3: compile + affected module tests; grep-count assertion per literal; FE/BE pair covered
by codegen check or drift check. Standard pre-merge: `./gradlew.bat build -x test` + affected
module tests; no new gates proposed.
