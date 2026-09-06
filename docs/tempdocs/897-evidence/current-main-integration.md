---
title: "897 current-main integration evidence"
type: tempdocs
status: complete-locally
created: 2026-09-06
updated: 2026-09-06
---

# Current-main integration

Base: `fdf50933eaa39bac3c7d4d6286141b9c4082c32c`. Imported task-only delta:
`189719e0..6c7177dfa`. Recovery implementation remains at `380eb7ad`; the source branch and
verified detached-runtime archive remain intact. This file records integration experiments, not shipped truth.

Independent Sol/high review found a revision gap: separate content/provenance reads could observe different
Lucene searchers, and raw-source hash equality cannot identify a same-source VDU text replacement. The fix
uses existing `getDocumentFieldsBatch` for one searcher, projects canonical `content_sha256` through the
existing metadata map, and makes Python reject page revision changes and joined UTF-8 hash mismatches.
Empty stored content remains found. Main's before/after commit and generation checks remain additional guards.

The calibrated-decision consumer binds the saved decision to its exact analyzer artifact and selects an
existing sweep row without calibration or holdout evaluation. Result redundancy remains content-exact;
Enron calibration does not validate other cohorts or archive-population near-duplicate prevalence.

Initial verification: focused jseval integration suite 281 passed, 4 skipped; decision/schema/command suite
47 passed; production snapshot suite including same-source revision changes 43 passed. Logs for broader
checks and new campaign artifacts remain under ignored `scripts/jseval/tmp/` in the integration worktree.
The first build exposed two imported one-argument FieldMapper test calls; both now use main's two-argument
API. Subsequent build found unnecessary qualifications in two imported Java files and a LambdaMART timing
benchmark at p50 7.8883 ms against 5 ms during concurrent build activity. Qualifications were corrected;
the benchmark requires an isolated rerun. No test threshold or baseline was changed.

Further verification: full jseval suite **3386 passed, 16 skipped** in 423.65 s (`897-pytest-full.log`).
Installed Worker format matrix and ten SyncDirectory tests passed; `pmdAll` passed (`897-installed-worker.log`,
52 s combined). Both catalog copies are byte-identical and the authoritative repro regeneration updated only
the fields catalog hash. Documentation index, skill sync and canonical link checks pass.

The full affected Java run exposed an obsolete imported test assertion claiming a retry write for a disabled
chunk-SPLADE stage. Main has since removed that retry; the auto-merge retained both false and true assertions
for the same outcome. Restored main's disabled-stage contract; its existing blank-content and null-embedding
tests retain applicable retry/progress coverage. This is an integration correction, not a weakened gate.

Frozen Enron decision application: canonical selected artifact `2807b0ddd1766611971b4c7ac5c78fff8065cf8230aa162500ebaf87dce83da6`,
file SHA `d0a86c1f179ba6c057ee13912beacb87c42102ad7c6c9fb05d2591d72041b667`.
At threshold 0.90, 273/5000 documents (5.46%) occur in 129 non-singleton components, with 154 confirmed edges.
Component-resampling 95% stability interval 4.506–6.391% is not population uncertainty. The exhaustive slice
retained all 104 threshold-positive pairs. These values describe the frozen Enron sample only and retain
the original model-assisted-label limitations; no holdout or calibration was rerun.

Serialized `build -x test --max-workers=1` passed in 50 s (`897-build-serial.log`), including the
previously failing LambdaMART benchmark. Full tests also identified the new stored source hash missing from
the schema test's internal-field classification. Classified it with the neighboring extraction provenance
fields because it is preview provenance, not a search fixture field; no schema assertion was removed.

Independent review of the new decision consumer identified incomplete decision validation and Python
boolean/integer equality in existing-output comparison. The fix requires an externally recorded decision
hash, full closed review evidence and consistent summary/confusion/selection/bootstrap accounting, and
validates existing output hashes before idempotent reuse. It never reads or re-evaluates review labels.
The recorded Enron decision still produces the identical selected artifact under these checks.

Full multi-module `gradlew test --max-workers=2` passed in 4m05s (`897-java-full-rerun.log`).
The current checkout now has independent copies of the 620-file realdocs, 199-file legal, and 33-file
format-breadth materializations. Each complete canonical raw manifest matches its original byte-for-byte,
and the raw-corpus resolver selects the current checkout. Original data and the runtime archive were not
modified. Current private specs declare exactly one realdocs terminal exclusion and zero legal exclusions.

The integration worktree was moved with Git into `.claude/worktrees/897-current-main`, which the shared
dev-stack ownership tools support. Branch/base are unchanged; private source specs now reference the
relocated copies. Preflight passes after installing the Head distribution.

Added explicit production ingestion and bounded readiness waiting to `duplicate-prevalence`, reusing
jseval ingestion and poll authorities. Waiting alone does not ingest. The predicate retains ordinary
enrichment checks and adds VDU/writer quiescence, declared failure counts and strict numeric fields;
source/terminal identity reconciliation remains mandatory at capture. Focused production, CLI, timeline,
readiness, ingestion, decision and schema tests: 206 passed in 16.38 s. Live evidence is still pending.


Second full jseval pass: **3401 passed, 15 skipped**, 466.15 s (`897-pytest-final.log`).
The independent production-wait review then found incomplete chunk telemetry checks, output placement
inside the source root, missing production-token forwarding, and an ingestion timeout detached from the
requested wait budget. Added explicit chunk pending/failed gates, source-root output containment checks,
optional session-token support on the existing ingestion transport, a shared registration/wait budget,
and per-request/sleep deadline caps in the canonical poller. The expanded focused suite passes **156 tests**
in 12.78 s (`897-production-review-regressions.log`), including negative probes for each finding.

Fresh realdocs runtime: owned dev run `4852e415-5181-46b5-aecb-813d25d11263`, Worker/Head distribution
built from integration commit `eff095f14`, CUDA inference activation completed, canonical jseval preflight
passed with embedding/SPLADE/NER/reranker wired. Runtime data uses the dev-runner-returned path under
`.claude/worktrees/897-current-main/scripts/jseval/tmp/897-realdocs-runtime` relative to the integration
checkout (the launcher rebased the requested absolute path). Ingestion began 2026-09-06 08:55 UTC.
The initial Python wait predates the final review fixes; its result must be recaptured through the
strengthened helper before it can become accepted evidence. No aggregate is yet claimed.

Review recheck closed the auth, containment, dense-pending and deadline findings. A final 999/1000
chunk-SPLADE probe exposed the ordinary 99.9% readiness tolerance hiding a failed chunk; production
waiting now requires the published completed count to equal its full chunk denominator and exact 100%
coverage for a nonempty enabled stage. Final focused pass: **157 passed**, 10.79 s, same regression log.

Before observed VDU processing, the runtime profile was changed from the dev default compact 4B to
standard `Qwen_Qwen3.5-9B-Q4_K_M.gguf` through the owned activation tool. Activation completed and
`ai_runtime_status.active` confirmed `chatProfile=standard`, `mmprojActive=true`, CUDA and full GPU
layers. Pre-switch visual status still showed pending work and `vduProcessing=false`; final model
provenance must be checked again when the production capture completes.

Full jseval verification after all production-wait hardening: **3420 passed, 15 skipped**, 484.52 s
(`897-pytest-production-hardening.log`). All focused and full suites are green; only live corpus/gate
evidence remains open. A diagnostic of a temporary realdocs count plateau confirmed continued progress
(365 searchable documents, 65 pending VDU, healthy index at the later snapshot), not established deadlock.

The realdocs run was deliberately stopped with `clean=none` after more than 400 documents so the
retrieval regression gates could run before its long VDU phase. Its 848-row aggregate timeline remains
ignored; the early backend failure is not a completed capture or a two-hour elapsed timeout. The adapter
now distinguishes an early unreachable-backend failure from deadline exhaustion (64 production tests
passed in 1.82 s, `897-production-early-failure-test.log`). Resume the same preserved runtime after the
SciFact/legal campaigns and perform a new strict capture.

SciFact started from a fresh isolated eval backend with all four modes, CE, complete pipeline readiness
and index settling. Its observed 5184-document count is 5183 corpus documents plus the canonical
`materialize.py` sentinel; this was checked against the materialized file inventory and existing sentinel
tests before interpreting results. No corpus contamination was inferred from that expected extra file.


## SciFact regression gates — 2026-09-06

Run `897-scifact-current-main/20260906T093231_scifact` completed 300 queries in each of four modes,
with CE applied to all 300 eligible queries in every mode and no silent drops. Hybrid nDCG@10 is
0.7542743135; vector 0.7433, lexical 0.7253, SPLADE 0.5689. `index_state_at_query.settled=true`:
force-settle removed 308 deleted slots, leaving 6739/6739 live index documents (parent plus chunks),
100% parent SPLADE and chunk-vector coverage. Chunk-SPLADE remains at the current default OFF.
The summary records commit `d64e2c04a`; Worker/Head code is the integrated implementation.

| Gate | Measured | Authoritative bound | Result |
|---|---:|---:|---|
| Relevance | hybrid nDCG@10 0.754274 | floor 0.737191 | PASS |
| Performance | CE p50 143 ms; retrieval p50 3 ms; primary 108.2 docs/s; enrichment 18.6 docs/s | all nine pinned checks inside their relative bands | PASS |
| Recall leak | 0.0300 | limit 0.076667 | PASS |
| Union recall | 0.9300 | floor 0.883333 | PASS |

Commands: `jseval --json <gate> --dataset beir/scifact --run-dir
 tmp/897-scifact-current-main/20260906T093231_scifact --report-out tmp/897-scifact-<gate>.json`
for `relevance-gate`, `perf-gate`, `leak-gate`, and `union-recall-gate`. Each exited zero with actual
pinned checks, not an unpinned-dataset skip. No CLI override or ambient engine/chunk/CE override was
present, and no baseline file changed. Performance bands project the older canonical release
(`bef184e333`, June 24); this gate pass is not a paired causal throughput-improvement claim for 897.

Summary SHA-256: `bafcb268fe055daa1b8cfa769e24ccada7ba46b7960be09543f94bc91259cabe`.
Gate report SHA-256 values:
- `relevance-gate`: `38c6ffa8a8eb6e4f8adbbceff2f0f1c3791c52c8d9aa48891995d28cac14ada4`.
- `perf-gate`: `024c953f88de384de430d2c284976fe0621ffb0a0f0e779bb1a6f2965074efbf`.
- `leak-gate`: `032dbd5540923b069f08be4993d767d05367fc78da50b46f3071de978b745a52`.
- `union-recall-gate`: `b82084f9a7e44f22321598d607297afe1aaeda366ec6df347e163d49ee96d3e0`.

## Realdocs resumption — 2026-09-06

Owned run `048e9843-f54d-400b-86d5-83e33b09b3d8` resumed the same returned data directory with
`clean=none`, current installed distributions, standard chat profile and a 7200-second lease.
The raw corpus and index were not reset. A new strict `duplicate-prevalence` wait omits `--ingest`
because the root is already watched; its log is `897-realdocs-resumed.log` and its aggregate timeline
destination is `897-realdocs-resumed-timeline.tsv`, both under ignored `scripts/jseval/tmp/`.

The previous online inference posture deferred automatic VDU by design. Primary authorities are
`VduPacingPolicy.java:42-49` (online/energy/idle admission), `BrainRuntimeServiceImpl.java:112-126`
(indexing mode records chat-disabled intent), and `OfflineCoordinator.java:97-145` (VDU procedure
temporarily owns inference, then reconciles to recorded intent). Through the owned MCP, activation
selected standard and `POST /api/inference/mode` recorded `indexing`. At 09:49 UTC, status showed
`vduProcessing=true` with 120 visual documents pending; inference reported the standard 9B model,
vision capability and generation 4. Online mode during the VDU procedure is expected and does not
self-interrupt the batch. Realdocs ingestion had reached 555 searchable documents at that point.
The canonical jseval reference and both skill surfaces now describe this required operational step.
Independent final recheck found no remaining readiness or workflow blocker. It reran the exact
pending-zero/completed-999/coverage-99.9 chunk-SPLADE regression (1 passed, 63 deselected), and
verified the spec-off VDU procedure contract against `RuntimeReconcilerTest.java:149-162` and its
production implementation. Canonical/Codex/Claude workflow paragraphs are identical.

## Legal production and final-result redundancy — 2026-09-06

Run `897-legal-current-main/20260906T093958_mixed_legal-clerc-200` completed from a fresh index with
`--modes lexical,vector,splade,hybrid --pipeline --start-backend --clean --fresh-index --settle-index`
and the strict legal production input spec. All 199 source files reconciled to 199 indexed observations,
zero failures/partials/exclusions. Settling removed 380 deleted slots (4701 to 4321 total index docs);
the stable capture was revalidated after queries. Byte/content-exact membership is zero of 199;
all components are singletons. The 2000-draw component stability intervals are [0,0], not uncertainty
bounds on another population. Near duplicates remain `UNDECIDED`: the 198-document exhaustive slice
had no positive pairs, making candidate recall undefined rather than 100%.

Each mode ran 200 queries comparably, with zero errors. The sidecar accounts for all 8000 delivered
top-ten hits (2000 per mode). The existing staged-recall projection reports final-hybrid redundancy:
zero of 200 affected queries, zero of 2000 redundant hits, mean 10 unique clusters/query, and zero
delivered-hit reconciliation mismatches. It does not emit separate leg redundancy aggregates; no
per-leg redundancy rate is claimed. Hybrid CE applied to 199/200 queries; the remaining skip was
the intentional `NAVIGATIONAL_QUERY` policy, with zero silent drops. Individual leg modes use their
explicit CE-not-applicable pipelines. nDCG@10: lexical 0.6873, vector 0.6215, SPLADE 0.0592,
hybrid 0.5766. These contextual numbers are not new baselines or causal improvement claims.

Independent read-only artifact review verified aggregate and sidecar self-hashes, summary anchors,
corpus/extraction identity, denominator and hit reconciliation, and privacy contracts. The aggregate
contains no source identities or text; the opaque sidecar remains local. Ordinary summary/manifest
runtime path metadata makes the whole run unsuitable for an aggregate-only publication claim.
All campaign outputs remain ignored and uncommitted.

Canonical aggregate hash: `eb378f9117eaae5598a06aecbe7e3d7e0e96c920c2bdd780707cfbe269d44bfb`.
File SHA-256 values:
- `duplicate_prevalence.v1.json`: `ba8267a708d89550339818b0f009ebe57e86b69263a89936b157462145938a20`.
- `result_identity.v1.json`: `3b762b05bda6fd06a73348871e6b1aec91566313b731bc8aafe2fcea4c16d336`.
- `summary.json`: `eb1c1bc5e8f6584538f8f8276fbe00e610547c106e75e53154b838eb9c1fc70a`.
- `projections/staged_recall_accounting.json`: `41e3c00b9a54afa587e8940c3c5e7ed1802acabd5fd3ea1db04b779866cf4c71`.

## Full-inference corpus isolation — 2026-09-06

The first full-app realdocs run reached 624 global parents: root substrate independently confirmed
619 realdocs parents and one terminal parser failure, while five additional parents existed outside
that watched root. `KnowledgeServerBootstrap.java:991-1038` explains those extras: normal application
startup ingests the five bundled help files into reserved `justsearch-help`. The production adapter's
exact whole-index reconciliation would reject this state. No aggregate was produced and no helper
document was filtered out, counted as a terminal failure, or deleted to manufacture a match.

Two independent source reviews established an existing isolation route. The normal dev runner
preserves ambient `JAVA_OPTS` (`dev-runner.cjs:727-739,1733-1739`) and writable settings; adding
`-Djustsearch.eval.mode=true` skips bundled help. The incompatible behavior of `runHeadlessEval`
comes from its separate `justsearch.ui.settings.mode=IN_MEMORY` property, not from the eval flag.
Relevant direct regressions cover help skipping, JVM-option preservation and default READ_WRITE
settings. The canonical reference and both jseval/dev-stack skill surfaces now distinguish these routes;
stale claims that retrying `--start-backend --llm` would activate inference were corrected.

Stopped owned run `048e9843-f54d-400b-86d5-83e33b09b3d8` with `clean=none`, preserving all its runtime
and source data. The old strict wait exited after five unreachable-backend probes and persisted a
792-row timeline; its accurate early-failure message does not claim a two-hour elapsed timeout.
Its runtime remains useful recovery/diagnostic evidence but is not the isolated measurement index.

New owned run `8724186f-d376-4deb-b3b8-c00bcdfaaedc` started through the normal dev-runner CLI from
commit `cd68bc31b`, with the eval JVM property, a fresh verified-absent data directory, standard profile,
7200-second lease, explicit session id and `clean=none`. It uses the direct worktree-relative runtime
`scripts/jseval/tmp/897-realdocs-isolated-runtime` (no doubled worktree prefix). Startup log:
`scripts/jseval/tmp/897-realdocs-isolated-start.log`. MCP subsequently confirmed the same run and
session ownership with FRESH distributions and ready Worker. Before ingestion, jseval preflight proved
IDLE and exactly zero indexed documents, no help marker existed, and standard CUDA activation
completed successfully in 13.843 seconds. The owned mode endpoint then recorded indexing intent.

At 10:15 UTC the unchanged strict input spec began a new `duplicate-prevalence --ingest
--wait-timeout-seconds 7200` campaign. Its ignored paths are `897-realdocs-isolated.log`,
`897-realdocs-isolated-timeline.tsv`, and `897-realdocs-isolated-prevalence.json` under
`scripts/jseval/tmp/`. The new aggregate requires the original 619 indexed plus one exact terminal
exclusion, with no extra documents. No auxiliary manifest or extraction-schema extension was added.

An independent acceptance check also confirmed the instrument cannot pass only by emitting zero:
`test_write_run_decorates_private_snapshot_without_leaking_source_material` exercises a nonzero
production content-exact redundancy result; `test_delivered_order_top_10_is_independent_of_trec_rank`
proves delivered-order top-ten accounting while recall retains TREC ordering. Added two direct assertions
to the existing declared-terminal-exclusion test: all three fixture sources remain byte-exact eligible,
while only two successful extractions are content-exact eligible. Production suite: **64 passed**,
3.55 s (`897-production-denominator-regression.log`).

## Reject incompatible indexes before ingestion — 2026-09-06

The bundled-help incident also exposed a late diagnostic: strict capture rejected extras only after
the full ingest/enrichment campaign. `ingest_and_wait_for_snapshot` now reads one immutable complete
parent-id export before root registration and rejects identities outside the declared successful
corpus. It accepts empty indexes and valid partial resumes. Malformed/incomplete exports, normalized
identity collisions and indexed terminal exclusions fail before mutation. The inspection client closes
on every exit; inspection, registration and readiness consume one deadline and use the same session
token. Final capture retains the complete identity, source-hash and revision checks, including changes
that race with the early inspection.

The foreign-document regression failed against the preceding implementation (`DID NOT RAISE`,
`897-pre-ingest-negative-before.log`) and passes with the guard. The focused production, schema and
wait-command suites passed **170 tests** in 10.61 s (`897-pre-ingest-focused.log`). The active isolated
realdocs run had already independently proved an empty index before ingestion; its final capture
contract is unchanged by this early diagnostic.

Independent refute-first review of the actual diff found no blockers across mutation ordering,
partial resumes, terminal exclusions, normalized collisions, transport failure, client closure and
deadline/authentication reuse. A read-only invocation of the new guard against the active partial
realdocs index passed; it performed no registration or other backend mutation. Canonical index,
skill synchronization, canonical links and prompt-surface inventory checks passed.

The full jseval suite passed **3433 tests**, with 15 skipped and 83 warnings, in 522.75 s
(`scripts/jseval/tmp/897-pre-ingest-full-pytest.log`). No Java/runtime code changed after the
recorded full build, module tests, installed-Worker matrix and four SciFact gates at that checkpoint.
The later enrichment repair supersedes that freshness claim; its current-tree checks are recorded below.

## Effort retrospective — 2026-09-06

The work combines three independently understandable deliverables: deterministic format capability,
a provenance-bound duplicate measurement instrument (including calibration and result identity), and
production corpus campaigns. Seven implementation slices under one tempdoc obscured those completion
boundaries. A future lane should close reusable instrumentation separately from long-running evidence,
while keeping product repairs explicitly owned and preserving the campaign's acceptance criteria.

The largest identifiable avoidable costs were late scale validation (the first Enron attempt reached
about 23 GB RSS before the bounded census/sample redesign), reworking all-human adjudication after
model-assisted triage was accepted, late integration across 34 task paths changed upstream, and late
runtime-isolation checks. The five-help incident and online-inference VDU deferral should have been
prevented by a small full-inference preflight before the 620-file campaign. Both are now documented;
the foreign-index guard also prevents the observed late cohort-rejection failure class.

Review found real snapshot, authentication, deadline and readiness defects after broad test runs.
Each subsequent full rerun was warranted by changed code, but an earlier independent production-surface
review and runtime smoke would likely have reduced the number of cycles. Preserve the negative
regressions and independent review; move them earlier rather than relaxing acceptance.

Comparison boundaries matter: 916 explicitly parked its Part 3 experiment with 4,122 chunk embeddings
pending; 931's initial wave skipped campaigns estimated above two hours; 930 explicitly excluded VDU.
Those decisions are recorded in their respective tempdocs. 915/931 also split substantial work across
multiple PRs. Neither completion labels nor commit/line counts establish relative active agent-hours.
897's elapsed duration includes pauses, production compute, repeated runs and integration work; no
complete cross-tempdoc effort ledger was collected here. The source record supports these causal
findings, not a numerical allocation of total effort. The takeover request correctly warned about
stale assumptions; no user-prompt correction was needed.

## Live enrichment failure invalidates the runtime ETA — 2026-09-06

At 13:20:33 CEST the active isolated Worker's indexing loop died with `OutOfMemoryError: Java heap
space` in `OnnxEmbeddingEncoder.createChunks:952`, through `embedBatchWithChunking` and individual
`EmbeddingBackfillOps`. The current `worker.log` records both the fatal error and uncaught-thread
termination. Process configuration confirms `-Xms1g -Xmx1g`; the readiness log's 7.7 GB heap ceiling
does not describe this Worker. API/Worker health remains reachable, so health alone does not prove
that indexing can progress. No speculative heap increase or restart was performed during diagnosis.

The 13:46–13:49 CEST checkpoint reports 619 indexed parents, 101 pending VDU (21 visual-text and
80 visual-enrichment), and one ready job. Parent completion is 277/619 dense, 340/619 SPLADE and
25/619 NER; 109,225 chunks remain pending dense embedding with zero complete. VDU RPC updates
continue independently, but enrichment has plateaued. No final terminal-exclusion reconciliation or
aggregate exists, and waiting alone cannot finish this run in its current state.

The retained Worker log rotations record 19 VDU result dispositions from 12:19:58 through 13:45:50,
including failures/rejection; these are resolved attempts, not 19 successful text extractions. Recent
rates suggest roughly 5–10 further hours for 101 visual items if that heterogeneous workload retains
similar cost. This is only a rough VDU projection; no total completion ETA is defensible until the
embedding failure is repaired and chunk throughput is observed. The original two-hour jseval wait
deadline is approximately 14:15 CEST and must not be reported as a completion forecast. Preserve the
runtime and resume the existing index after resolving the failure; do not repeat source ingestion.

## Enrichment repair and bounded campaign decision — 2026-09-06

The user directed stopping the run, investigating enrichment first, and skipping the full campaign if
the repaired end-to-end run would exceed roughly 2–3 hours. Three hours is the hard maximum, not a new
unbounded wait. Owned run `8724186f-d376-4deb-b3b8-c00bcdfaaedc` stopped with `clean=none` at about
14:04 CEST; the dev runner confirmed ports closed. Source files and runtime/index remain preserved.
The strict wait ended without an aggregate. This supersedes mandatory completion of the expensive
realdocs campaign; skipped measurements must remain visibly unmeasured, with no readiness bypass.

Source investigation identifies retained allocation across stages: individual backfill fetches up to
100 full texts, the encoder retains copied token windows across all tokenization groups, and the generic
backend boxes all chunk vectors even though parent backfill consumes only the pooled document vector.
Tokenizing in 512k-character groups alone does not bound those retained inputs or outputs. Increasing
heap would only move the limit. The existing window and pooling geometry remain the semantic authority.

Repair plan: (1) generate token windows on demand and release each tokenization group's inputs before
the next group; preserve exact overlap/tail handling, ordering and pooling. (2) Use bounded inference
and pooled-only results for parent-vector consumers, and bound individual backfill content collection;
retain full chunk-vector results for callers that request them. (3) Trace fatal-loop health propagation
and repair missing reporting through the existing lifecycle authority if confirmed. No new runtime knob,
alternate embedding algorithm, truncation policy, or heap-default change is warranted.

Verification: synthetic negative regressions for retained-window memory and large-parent batching;
small exact geometry/pooling/ordering comparisons; affected Worker suites and build, independent review,
then a bounded preserved-index live probe with unchanged corpus/model settings. Use jseval telemetry
to assess end-to-end cost, including VDU; an embedding speedup alone does not justify a campaign whose
remaining visual work exceeds the cap. Record accepted or explicitly skipped corpus evidence and finish
the remaining documentation/review closeout. The broader lesson is to bound retained work across a stage
boundary, not only individual native calls; this is demonstrated by the constrained-memory regression,
and no additional abstraction is needed once existing owners enforce that bound.

The implementation now uses lazy `TokenWindows`, a bounded eight-window inference accumulator,
and an ONNX pooled-only parent route. Content collection stops before a second document would exceed
512,000 characters; an oversized first document remains whole. Independent review caught and corrected
that mixed-size boundary and preserved the service's empty-result, first-dimension and telemetry rules.
`EmbeddingServicePooledBatchTest` exercises the actual service route, so reverting to the generic boxed
chunk response cannot pass by leaving only helper tests green.

`LoopState.FAILED` is published before logging a fatal VM/uncaught event. The same loop instance feeds
core status (`FAILED`, unhealthy), while search serving stays separately probed. Fresh FAILED snapshots
terminate every jseval readiness wait with `indexing_loop_failed`; stale snapshots and declared ordinary
document ERROR retain their existing contracts. The Health UI names the failure and suppresses false
progress/ETA. Independent source review found no remaining blockers in this propagation chain.

Focused evidence: `897-enrichment-focused.log` passed in 7m15s, including lazy-window geometry,
bounded pooling and parent collection. Its 128 MiB child-process regression uses identical synthetic
three-million-token inputs: lazy iteration completes (7,813 windows), the former eager algorithm raises
heap OOM. `897-enrichment-model-health.log` passed in 8m: the real-model pooled/full comparison took
9.078s; the existing cross-group ordering comparison took 419.480s (two model tests, zero skipped).
The same command passed fatal-loop, core-status, serving-health and parent-budget regressions.
Broader Java/UI/Python verification and fresh four-mode SciFact gates are recorded below.

CPU contention was observed independently of the evaluation stack: a first sample identified Windows
Defender as the largest consumer; later total CPU was 31%, of which the serial model test used about
20 percentage points. The stopped corpus run cannot establish a post-repair speedup. Builds now use
one Gradle worker, and the live timing probe must have no concurrent build or test from this session.

Current repair verification: `897-enrichment-final-java.log` passed the full Java suite (9,260 cases,
26 skipped, zero failures/errors). Earlier reruns exposed only two test-wiring corrections: the new
Mockito service test belongs in Worker services, and the scheduler fixture must supply its same seeded
text through incremental content reads. Behavioral assertions were retained. `897-enrichment-build-pmd-install.log`
passed `build -x test`, `pmdAll`, integration checks and both installed distributions in 1m28s.
`897-enrichment-installed-worker-live.log` explicitly enabled and reran the installed-Worker format matrix
and ten directory tests in 45s; the first invocation without the opt-in was skipped and is not evidence.
UI type checking and all 6,335 unit tests passed (`897-enrichment-ui-typecheck.log`, `897-enrichment-ui-unit.log`).
Current Python scopes: readiness/production capture 131 passed; UI capture forwarding/index 12 passed.
The previously recorded full Python suite predates these narrowly tested readiness/capture-tool changes.

Affected UI capture exposed an ignored `--fixtures` flag: the CLI's affected-step branch dropped the
existing per-shot options. The existing owner now forwards fixture, measurement, trace and recording
options, with CLI-to-owner-to-shot tests. Corrected captures are under `897-enrichment-health-ui-fixed/`:
three Health variants render with no document overflow and no network errors. Health completion has
zero axe findings; ordinary dark/light Health retains the registered `color-contrast` debt on the
unchanged unknown-presence label. The harness also records a DOM view-transition timeout; captures are
visual smoke evidence, not a claim of zero UI diagnostics. Fatal-state rendering is covered by the new
`HealthSurface.render.test.ts` and `indexingProgress.test.ts` cases.

At 15:09 CEST an owned, preserved-index probe started as `27b6b8f4-fe2c-4117-85dc-deb3affd816e`.
Preflight passed every check; MCP reports FRESH artifacts (`headDistStamp=b0be3df6fa335916`) and the
correct worktree/data directory. Standard CUDA activation completed in 45.801s, then indexing intent was
recorded. No ingestion was repeated. `897-enrichment-probe.log` and its timeline use a 1,200-second
strict wait, including the visual scheduler's idle window. Worker PID 20416 still uses `-Xms1g -Xmx1g`;
the readiness log's 7.7 GB maximum describes Head heap, not Worker heap. This probe's sources are the
verified working-tree repair recorded above; its runtime provenance still names checkpoint `efe744cca`.

### Bounded decision: skip the full realdocs campaign

The probe ended early at about 15:27 CEST once the runtime decision was clear. MCP shutdown confirmed
ports closed and retained the runtime/index (`clean=none`). The strict wait then failed on the deliberately
stopped backend and persisted 476 timeline rows; this is an intentional stop, not another pipeline crash
or a completed aggregate. No production aggregate exists at `897-enrichment-probe-prevalence.json`.

Fresh observations span elapsed 0.5–954.6 seconds. Indexed parents remain 619. Parent embedding rises
45.1%→57.8%, SPLADE 57.5%→66.6%, and NER 27→82 (537 still pending). Chunk coverage remains 0.0%.
`vdu_pending` falls only 99→95; `vdu_processing` is one at both endpoints. These are backlog dispositions,
not a count of successful document conversions. The same standard model was verified as
`Qwen_Qwen3.5-9B-Q4_K_M.gguf`; no model, parser, readiness or heap setting was relaxed.

At this observed net drain rate, 95 pending visual tasks project about **6.3 further hours**. Even
discounting a full five minutes from the observation as startup/idle overhead gives about **4.3 hours**,
before unfinished dense/NER/chunk work. These are alternative drain-rate projections, not a confidence
interval or a claim that heterogeneous future documents all have equal cost. They are sufficient to
reject continuation under the user's three-hour maximum. The repair restores real enrichment progress
and removes the reproduced retained-allocation failure, but it does not make the end-to-end campaign
short enough. Realdocs byte/content/near-duplicate production cells remain explicitly unmeasured.
Completed Enron, legal and deterministic format evidence remains usable within its recorded scope.

Independent final review found no blockers in the capture-option fix or the smoke-only UI evidence
wording. Fresh post-repair four-mode SciFact verification used a separate disposable eval index;
it did not resume the skipped realdocs campaign.

### Post-repair SciFact regression evidence

Run `897-scifact-enrichment-repair/20260906T133913_scifact` completed at about 15:39 CEST, exit zero
(`897-scifact-enrichment-repair.log`). Run identity: `8f9766b3-1c7c-4375-a218-a93f3a740415`;
summary SHA-256: `c8e03e3d00015c2ea69bcac4e350143e81ff5b1b504aca5096539cc40bb335b7`.
The installed repair was verified above; the manifest names its pre-commit checkpoint `efe744cca`.
All four modes are comparable, with 300 queries each, zero query errors and cross-encoder coverage
300/300 in each mode, zero silent drops. nDCG@10: vector 0.7495996, lexical 0.7252984,
SPLADE 0.5700250, hybrid 0.7543339. These are regression observations, not a causal quality gain.

All four commands used `--run-dir tmp/897-scifact-enrichment-repair/20260906T133913_scifact`
and pinned dataset `--dataset beir/scifact`. Saved logs are `897-enrichment-relevance-gate.log`,
`897-enrichment-perf-gate.log`, `897-enrichment-leak-gate.log` and `897-enrichment-union-recall-gate.log`.
Relevance passes 0.7543339 ≥ 0.7371906; leak rate passes 0.0300 ≤ 0.0766667; union recall passes
0.9300 ≥ 0.8833333. Performance passes all nine pinned checks: hybrid CE p50 150 ms, retrieval
p50 3 ms, primary throughput 91.0 documents/s and enrichment 14.9 documents/s, plus five footprint
checks. No baseline was changed. Initial gate invocations used `--data-dir` incorrectly, then the
short `scifact` name skipped the performance baseline; neither invocation counts as gate evidence.

Readiness accepted 5,184 parents (5,183 corpus documents plus the canonical sentinel); settled index
contains 6,739 live documents in one segment. The recorded settle operation changed maxDoc 7,633→6,739
without changing numDocs. Chunk vector coverage reached 100% at 237.9 seconds. Total ingest/readiness
was 348.62 seconds. Throughput and latency vary with host load; this run does not establish a repair
speedup over the earlier run or a counterfactual duration without CPU contention.

Independent final artifact review confirms that the offline chunk-expectation guard does not validate
this ir_datasets BEIR route: `run.py:940-962` reads a local `corpus.jsonl` that this route does not create,
so the existing oracle returns expected=0. Its `chunk-free` label is not a corpus fact. Live status
independently records 1,555 completed chunk documents, zero failures and 100% vector coverage
(`summary.json:746-750`). The four pinned gates passed, but no offline chunk-expectation validation is
claimed. A nonblocking diagnostic follow-up is to distinguish an unavailable offline oracle from a
genuinely chunk-free corpus in `chunk_completeness.py:216-225`; no threshold, verdict or saved artifact
was changed to conceal this existing scope limitation.

MCP `quick_health` after shutdown reports `ABSENT`, no foreign runs and no inference orphan. The final
repository helper sweep leaves the UI capture Vite (PID 42192, this worktree) because its original
registration has an unattributed owner; lease renewal does not transfer ownership. It also reports the
shared ownerless OTLP sink (PID 14468), which is deliberately never reaped. Neither process was killed
outside the ownership protocol. No evaluation or corpus backend remains running.

Documentation regeneration and all seven checks passed: llmstxt, generated skill sync, canonical links,
module dependency projection, runtime configuration matrix, prompt-surface inventory and UI step coverage
(`897-enrichment-doc-check-0.log` through `897-enrichment-doc-check-6.log`, in that order).
The corresponding manual Codex skill changes were reviewed directly. `git diff --check` passed.
The branch is intentionally local and unpublished; opening/pushing/merging a PR is not authorized.
At final world-state inspection, origin/main had advanced by two release/documentation commits
(`e1ed33e8a`, `b684b7860`), whose changed paths do not overlap this repair. Publication must refresh
integration against the then-current main rather than treating this local verification as merge CI.

Closeout lessons: bound a live probe before committing to a campaign, and use its slowest unfinished
stage to enforce the user's wall-clock budget. Shared-host timing cannot prove an isolated speedup.
Keep regression fixtures aligned with existing module dependencies and the production read seam to
avoid wasted build reruns. Inspect gate CLI options and the pinned dataset key before invoking them;
an exit-zero skip is not a tested floor. Set the available session-identity environment variable before
starting helpers; renewing an unattributed lease cannot repair its original ownership record. Bounded
independent reviews found useful semantic and fixture issues without competing for the single build
or dev stack. No user prompt caused the delays; obsolete runtime assumptions and discovered defects did.

### Close state

Authorized implementation and local verification are complete, including the user's explicit decision
to skip the full realdocs campaign when the repaired probe cannot fit the three-hour limit. Realdocs
production duplicate prevalence remains unmeasured; neither its preserved index nor the older archive
is accepted aggregate evidence. Completed format, Enron and legal observations retain the exact scope
and uncertainty recorded above. Independent repair, fatal-state, UI forwarding and final artifact
reviews found no remaining 897 blocker. There are no pending corpus runs or required local tests.
The next publication step requires separate authorization and a fresh main integration check. The UI
capture ownership refusal and existing BEIR diagnostic-label limitation above remain visible follow-ups.
