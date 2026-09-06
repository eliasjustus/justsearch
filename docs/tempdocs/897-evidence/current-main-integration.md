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
