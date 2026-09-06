---
title: "897 current-main integration evidence"
type: tempdocs
status: active
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
