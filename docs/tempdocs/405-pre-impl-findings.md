---
title: "405 — Pre-implementation findings for tempdoc 404"
---

# 405 — Pre-implementation findings for tempdoc 404

## 0. Status

**Pre-implementation findings — companion to tempdoc 404.**
Tempdoc 404 proposes a five-pillar observability architecture.
This tempdoc records the results of pre-implementation work
executed against 404's design: premise validation, measurement,
spike, adversarial review, reversibility analysis, web research,
autonomous D-2 research. Output: an updated confidence
evaluation per pillar + findings that refine the 404 design.

**Created:** 2026-04-22.
**Depends on:** tempdoc 404 (design).
**Companion:** `docs/observations.md` (D-3 fix shipped during
P1.C.3).

---

## 1. Context

Tempdoc 404 §10 rated the four-pillar design at 3-4/10 end-to-end
and 6/10 for the bounded Pillars 3+4 MVP, with the explicit
admission that most of the low-confidence signal came from
**unmeasured assumptions** rather than structural concerns about the
design itself. The user authorized P1+P2 work to convert those
assumptions to measurements + spikes.

Activities executed:

| Phase | Task | Outcome location |
|---|---|---|
| P1.A.1 | Retrospective defect audit | §2.1 |
| P1.B.1 | Consumer inventory | §2.2 |
| P1.B.2 | Content-hash benchmark | §2.3 |
| P1.B.3 | Span emitter inventory | §2.4 |
| P1.C.3 | D-3 one-commit fix | **Shipped** (commit `3ece672e1`) |
| P2.C.1 | Pillar 3 spike (encoder_drift typed) | §3.1 + `_spike_typed.py` |
| P2.C.2 | Schema sketch | §3.2 + `_spike_schema.py` |
| P2.A.2 | Adversarial review | §4 |
| P2.D.1 | Reversibility criteria per pillar | §5 |

---

## 2. P1 — Premise + measurement findings

### 2.1 Retrospective defect audit (P1.A.1)

Classified ~35 distinct defects from the tempdoc 400 post-
implementation critique (Phase 6 §5.1 severity summary),
Phase-6 ledger, and §23.9.4 theoretical improvements by which
pillar would have structurally prevented each.

| Pillar | Defects caught | Examples |
|---|---|---|
| **1 (run-as-manifest)** | ~6 | D-3, §23.9.4#4 envelope backfill, §23.9.4#6 Layer-5 indexing, C-1.2.1 projection quarantine |
| **2 (schema-first)** | ~7 | D-1, §23.9.4#2 cadence, §23.9.4#9 searcher_generation form, §23.9.4#10 commit.* regression, C-1.1.1 run_id type |
| **3 (typed transformations)** | ~10 | C-1.2.1 (also), C-1.4.1 50% threshold, C-1.8.1 first-run baseline, §23.9.4#3 window, §23.9.4#5 latency |
| **4 (cost as signal)** | ~3 | §23.9.4#8 detailed-tracing cost, C-1.10.1 baseline recalibration |
| **Not caught by any pillar** | ~6 | **D-2 path relativity** (OS/process boundary), C-2.1.1 wrong docstring, C-2.4.1 feature incompleteness |

Coverage:
- **Full 4-pillar: ~29/35 = 83%** structural prevention.
- **Bounded Pillars 3+4 only (MVP): ~13/35 = 37%** but catches ~63%
  of HIGH-severity items.
- **D-2 path relativity** NOT caught by any pillar → genuine blind
  spot. OS-environment-boundary interactions are outside the
  design's scope.

**Implication:** the premise holds. The 10 §23.9.4 improvements
are real symptoms of a pattern pillars 1-4 address. But the blind
spot means 404 should never be marketed as "prevents silent
failure" — only "prevents the silent-failure sub-class 404 names."

### 2.2 Consumer inventory (P1.B.1)

Grep of reference-sites for `manifest.json`, `summary.json`,
`traces.ndjson`, `metrics.ndjson`, `qrels.json`, `envelope.json`,
`span_distributions.json`:

- 17 Python source files (jseval package): 76 references.
- 16 Python test files: 72 references.
- **Total: 148 sites across 33 Python files.** Zero Java-side
  consumers (Java is producer-only for eval artifacts).

**Implication:** Pillar 1 migration scope is ~1500-2500 LOC of
consumer updates on top of new manifest code. Consistent with
§10.2 "~2× tempdoc 400" estimate. Concrete, sizeable, not
astronomical.

### 2.3 Content-hash benchmark (P1.B.2)

SHA-256 on realistic NDJSON from the §23.9 smoke artifacts (run
dir 20260422T134901_scifact):

| File | Size | Hash time |
|---|---|---|
| traces.ndjson | 2.79 MB | 1.32 ms |
| metrics.ndjson | 0.065 MB | 0.031 ms |
| manifest.json | 0.019 MB | 0.009 ms |
| full_per_query.json | 0.038 MB | 0.018 ms |
| 7× projection JSONs | <5 KB each | <0.01 ms each |
| **All artifacts together** | **2.99 MB** | **1.4 ms** |

Throughput: **~2.1 GB/s** on target hardware (Windows desktop).

Extrapolations:
- Per-run Pillar-1 hash overhead: **1.4 ms on a ~180-second run =
  0.0008% wall-clock.**
- Ingest+rotation scenario (~13.5 MB traces doubled by mirror
  concat): ~2.4 ms = 0.0013%.
- Detailed-tracing 10× span volume: ~4.3 ms = 0.0024%.

**Implication:** §10.3's "content-hashing cost is user-visible"
concern was an **unmeasured assumption**. B.2 disproves it. Hash
overhead at desktop scale is invisible against wall-clock. Pillar 1's
runtime cost is no longer a real constraint. **Pillar 1 confidence
bump: +0.5 on the cost dimension** (but H2 + H7 concerns remain).

### 2.4 Span emitter inventory (P1.B.3)

Grep of `spanBuilder(` + `.addEvent(` across `modules/*.java`:

- **Production emitter sites: ~20 calls across 8 files.**
  - SearchOrchestrator (7)
  - AgentLoopService (3)
  - KnowledgeHttpApiAdapter (3)
  - EncoderOrtRunSpans (3)
  - NativeSessionHandle (2)
  - IndexingLoop (1)
  - CrossEncoderReranker (1)
  - WorkflowTraceProbe (1)
- Test emitter sites: 13 calls across 7 files.

**Implication:** Pillar 2 emitter-retrofit scope is smaller than
§10.1 estimated. ~20 sites × ~3 LOC per site = ~60 LOC of emitter
changes. Plus schema file + reader infrastructure: ~700 LOC total
(§3.2). **Well below §10.1's 2000-4000 LOC worst case.**

**But H6 caveat:** Pillar 3 MVP value depends on Pillar 2 shipping
first/alongside. §9's sequencing (3 → 4 → 2 → 1) is wrong on this
axis.

---

## 3. P2 — Spike findings

Working code in `scripts/jseval/jseval/projections/_spike_typed.py`
(Pillar 3) + `_spike_schema.py` (Pillar 2). Tests in
`test_spike_typed_projection.py` (12 tests) +
`test_spike_schema.py` (12 tests). All passing.

### 3.1 Pillar 3 spike — encoder_drift as typed transformation

**Design validated:**
- `@projection(name=..., reads_kinds=..., writes_kind=...,
  health_checks=...)` decorator produces idiomatic Python.
- `ProjectionContext.read_kind(name)` replaces the hand-written
  span-name filter; raises if the projection reads a kind it didn't
  declare (consumer-side discipline).
- `execution.duration_ms` is a free side-effect of the wrapper —
  every projection output has it without projection-body changes.
- Coverage metadata (`spans_scanned`, `spans_matched`) is also
  free — pure side effect of iteration.
- Baseline lookup via `ctx.read_cohort_baseline()` cleaner than
  the existing `_resolve_data_dir` + `_load_baseline` chain.

**H6 empirically confirmed:**
`test_H6_undeclared_kind_enforcement_is_consumer_side_only` in
`test_spike_typed_projection.py` documents exactly what H6 predicted.
A producer renames `encoder.ort_run` to `encoder.session_run`;
Pillar 3 alone cannot catch this. The projection reads 0 spans,
reports `coverage.spans_scanned=1, spans_matched=0`, returns
`status="no-encoder-spans"`. No error. No alarm. Silent.

**Resolution:** Pillar 3 MUST ship either alongside Pillar 2 or with
a contract-test-per-projection pattern (Workstream C's
`test_projections_rate_timeline.py::TestMetricNdjsonContract` as the
template). Sequencing 3-first without either is a no-go.

**Scope: ~1050 LOC** (300 infra + 350 projection retrofits + 400
tests). Matches §10.1 estimate.

**Ergonomics verdict: pattern fits cleanly.** No friction in the
encoder_drift retrofit. Confidence on ergonomics: 8/10.
Confidence on structural prevention (without Pillar 2): 4/10.

### 3.2 Pillar 2 spike — canonical schema

**Design validated:**
- A single JSON dict (`CANONICAL_SCHEMA`) describes every span kind,
  attr, structural field, with constraints (enum, all_of, struct).
- Python reader: 4 helper functions (~60 LOC) cover
  `required_attrs_for_kind`, `identity_attrs_for_kind`,
  `structural_field_names`, `validate_span`.
- `validate_span(span)` returns a list of violations (empty = ok).
  Can be used producer-side for fail-loud emission OR consumer-side
  for fail-loud reads.
- Java export is a Python → Java static-constant generation (stubbed
  as `export_for_java()` returning Java code). Equivalent shape on
  Java side.

**H1 partially refuted:** H1 claimed Pillar 2 Python-first is "just
renamed current state." Spike shows if **both** Python + Java read
from the same canonical JSON + both validate, drift between them is
a git-diff-visible change to a shared file — not the silent drift
D-1 suffered. §5's symptom mapping for §23.9.4#9 (searcher_generation
struct) and #10 (commit.* all_of) works cleanly.

H1's weaker form still holds: if only Python is enforcing (the
4-6-week MVP state), enforcement is contract-test grade (same class
as today, just more regular). But the target state (both sides
validating against shared schema) is **structurally** different from
current.

**Scope: ~700 LOC** (150 schema JSON + 100 Python reader + 200 Java
reader + 60 emitter retrofits + 150 contract tests). Well below
§10.1's 2000-4000 LOC estimate.

**IDL investment NOT required for MVP.** One JSON file checked into
`SSOT/observability/` + two thin readers + one contract test per
language is sufficient. §8 Q1+Q2 (schema language choice, Python-
Java bridge) can be resolved with "shared JSON, no generator" for v1;
Protobuf/Avro can wait for scale we don't have.

---

## 4. P2.A.2 — Adversarial review findings

Plan agent with "find holes" brief produced 9 findings, ordered
high → low severity.

| Tag | Severity | Claim | Resolution |
|---|---|---|---|
| **H1** | HIGH | Pillar 2 Python-first = current state renamed | **Partially refuted by §3.2 spike.** Weaker form (MVP is contract-test-grade until Java catches up) holds. |
| **H2** | HIGH | Pillar 1 manifest can't survive producer crashes — orphaned shards with manifest asserting completeness | **Valid and unresolved.** Filesystem-scan reconciliation at run.closed re-introduces the ad-hoc detection Pillar 1 claims to replace. **This is an actual design flaw**, not a nit. |
| **H3** | HIGH | Pillar 3 health checks are projections validated against baselines that may themselves be biased — recursion of trust | **Valid and partially accepted.** Spike's `input_span_count_nonzero` is trivial; `within_10pct_of_baseline` recurses. Pillar 3 cannot structurally prevent recursion without Pillar 1 lineage metadata, but the recursion depth is bounded (1 level) so damage is finite. |
| **H4** | MEDIUM | Invariant 2 "observability-about-observability" is recursive and doesn't terminate | **Accepted.** Tempdoc 404 should explicitly name the root-of-trust layer (§4 Invariant 2) rather than claim infinite self-description. |
| **H5** | MEDIUM | Pillar 4 cost gate amplifies hardware noise; either conservative (false alarms) or loose (misses real regressions like +22%) | **Valid.** §23.9.2's actual +22% regression lands inside a 3σ band on the measured CV=8.9%. Pillar 4 catches only ≥25% regressions reliably. Weaker than §3.4 prose suggests. |
| **H6** | MEDIUM | Pillars 3+4 MVP has hidden dependency on Pillar 2 schema | **Valid; EMPIRICALLY CONFIRMED by §3.1 spike.** Rename-producer-kind silent failure reproduced. MVP confidence 6/10 → 4-5/10 unless Pillar 2 ships first/alongside. |
| **H7** | MEDIUM | OpenLineage / Dagster / Beam references cargo-culted for desktop scale | **Valid.** Reference set should be SQLite manifest + Maven local repo pattern, not data-lake. Does not kill the design but §10.3 under-addresses. |
| **M8** | MEDIUM | §5 symptom mapping oversells (rows 2, 10 aren't pure Pillar 2 resolutions) | **Valid.** §5 row 2 "smoke harness auto-validates" is a separate deliverable; row 10's all_of only fires on actual emission. |
| **L9** | LOW | §10.4 partial-implementation cost not quantified | **Valid; partially addressed by §5 reversibility criteria.** |

**Net effect on confidence:** findings are substantive. H2 is a real
design flaw; H3 is an accepted limit; H6 is a sequencing flaw; H5
weakens Pillar 4's claim. Confidence should **drop** from these,
not rise.

---

## 5. P2.D.1 — Reversibility criteria per pillar

| Pillar | Max stable mid-point | Abandonment exit | Pause blast radius |
|---|---|---|---|
| **3 (typed projections)** | **All 7 OR zero.** Mixed-mode projections mean the nightly gate must handle two output shapes. Acceptable mid-point: all 7 converted to typed, but without Pillar-2 schema validation (MVP state). | Delete `_spike_typed.py` + revert the 7 projection files to their current shape. ~1 day of undo work. Zero Java changes to revert. | LOW. Projections are internal; consumer surface is the nightly gate + `jseval` CLI which hold the old shape invariably through MVP. |
| **4 (cost signal)** | **Calibrated cost envelope exists OR doesn't.** Acceptable mid-point: envelope calibrated for one tracing level; others added incrementally. No hybrid state where the gate knows about some features and not others. | Delete cost_envelope.json; revert gate.py to current assertions. ~0.5 day of undo. | LOW. Pure additive to calibrate+gate. |
| **2 (schema-first)** | **ALL production emitters validate OR none.** Partial emitter validation = silent drift for the unvalidated ones (D-1 class, the bug Pillar 2 is fixing). Acceptable mid-point: schema published, Python validator shipped, Java emitter retrofit pending with a single "SCHEMA_ENFORCED=false" flag that blocks merge when flipped. | Delete `SSOT/observability/spans.v1.schema.json`; remove Python readers; revert emitter instrumentation. ~2-3 days of undo. | MEDIUM. Emitter changes are Java; reverting requires rebuild + redistribution if the Worker was shipped with schema-validation in production. |
| **1 (run-as-manifest)** | **Consumers read v2 manifest OR v1 loose files.** Mixed-mode = every consumer needs dual-read. Acceptable mid-point exists only via a total v1 → v2 switch at one commit. | ~1-2 week revert — every consumer + test fixture rewritten; artifact layout reverted; rotated-sibling concat (the D-3 fix that was Pillar 1's MVP slice) preserved but the manifest/lineage meta stripped. | HIGH. Downstream consumers (CI nightly gate, `jseval compare`, history queries, debug UIs) all break on revert. |

**Sequencing implication:**

404 §9 ordered Pillars 3 → 4 → 2 → 1 ("least-invasive first"). The
reversibility matrix confirms that ordering is correct for
**abandonment risk** (Pillar 1 is most-reversible-first only with
respect to code invasiveness, not consumer impact). But it **mis-
sequences for MVP value**: Pillar 3's MVP enforcement value
requires Pillar 2 (H6), so 3-first-without-2 is a 4-6 week
investment that lands contract-test-grade enforcement — strictly
below what Workstream C already demonstrated for one projection.

**Revised sequencing (see also 404 §9):**

1. **Pillar 4 first** — extends existing calibrate infrastructure;
   smallest code footprint; independent of other pillars.
2. **Pillar 2 next** — unlocks Pillar 3's structural prevention
   (H6 resolution). Java emitter retrofit lands with a
   `schema_enforced` bool that starts OFF and flips ON once
   coverage is complete.
3. **Pillar 3 after** — every projection lands with schema-backed
   structural prevention instead of string-literal drift hazards.
4. **Pillar 5 after Pillar 3** — reuses typed-projection base +
   schema infrastructure (see 404 §15).
5. **Pillar 1 last** — invasive, consumer-migration-heavy.
   Sequenced last so it benefits from stable earlier pillars.

This reorders 404 Draft 1's "least-invasive first" to a
dependency-respecting chain. Each pillar's prerequisites land
before it does.

---

## 6. Updated confidence ratings

| Pillar | §10 pre-P1/P2 | Post-P1/P2 | Delta | Driver of change |
|---|---|---|---|---|
| **1 (run-as-manifest)** | 3/10 | **2/10** | -1 | H2 (producer crash gap) is a real design flaw; hash cost concern dissolved (+), H2 concern emerged (−) |
| **2 (schema-first)** | 4/10 | **5/10** | +1 | §3.2 spike shows IDL investment not needed for MVP; H1 weakened |
| **3 (typed transformations)** | 6/10 | **6/10** | — | §3.1 spike: ergonomics 8/10; H6 structural dependency confirmed → net wash |
| **4 (cost as signal)** | 6/10 | **4/10** | -2 | H5 (noise-floor amplification) means gate only catches ≥25% regressions reliably |
| **Bounded MVP (3+4)** | 6/10 | **4/10** | -2 | H6 confirmed empirically; MVP needs Pillar 2 to deliver |
| **End-to-end full 4-pillar** | 3-4/10 | **3/10** | -1 | Hash cost disproven (+), but H2/H3/H6 net negative |

**Scope estimates (revised with measurements):**

| Pillar | §10 LOC estimate | Revised LOC (measured) |
|---|---|---|
| 1 | 3000-5000 | 2500-4000 (B.1 consumer inventory) |
| 2 | 2000-4000 | **~700** (B.3 + §3.2 spike) |
| 3 | 1500-2500 | ~1050 (§3.1 spike) |
| 4 | 1000-1500 | 800-1200 |
| **Total** | 8000-13000 | **5000-7000** |

Scope is ~40% smaller than the §10 worst-case. Still substantial,
still ~2× tempdoc 400 at the upper bound, but less than estimated.

---

## 7. Refinements surfaced during P1+P2

P1+P2 produced two structural refinements to the 404 design:

1. **Bounded-MVP definition refined from "Pillars 3+4" to
   "Pillars 4 → 2 → 3".** §5 reversibility + §3.1 H6 confirm
   Pillars 3+4 alone is insufficient: Pillar 3's structural
   prevention depends on Pillar 2's schema (empirically
   demonstrated in Activity A). The correct dependency order
   is 4 → 2 → 3. ~2500 LOC total.
2. **D-3 fix shipped** (commit `3ece672e1`) — rotation-aware
   telemetry mirror. Resolves §23.9.3's 77.1% span-loss defect
   as a scoped patch in `artifacts._mirror_telemetry`.

Additional scope/design findings:

- **Pillar 1's Draft 1 claim revised.** "Structurally impossible
  to lose artifacts" was wrong (H2). Corrected claim: "orphaned
  shards are recoverable garbage; manifest never asserts
  completeness it doesn't have." Matches SQLite WAL + Git
  content-addressed pattern (§12).
- **Scoped-fix engineering discipline documented** at
  `docs/reference/observability-scoped-fix-playbook.md`. Pattern:
  classify defect, write contract test, commit, log. Activity C.
- **Activity D** opened a silent-failure engineering log in
  `docs/observations.md` as a running engineering record.

---

## 8. What shipped during P1+P2 as production code

Spikes (`_spike_typed.py` + `_spike_schema.py`) are reference
material, not production. 24 tests pin the findings into the
suite but mark them `SPIKE` in docstrings.

Single production commit: **`3ece672e1` — D-3 fix** (rotation-aware
`artifacts._mirror_telemetry` + 6 regression tests). Addresses
§23.9.3's 77.1% span-loss defect directly. The only code change
with operational impact.

---

## 9. Follow-up tempdocs (inventory)

- **Tempdoc 401** (scoped, not started): LR3-a Windows probes.
  Minimal interaction with 4-pillar architecture — additive
  gauges need Pillar 2 schema entries only.
- **Tempdoc 402** (scoped, not started): Layer 6 tier completion.
  Substantial overlap with Pillar 2 (`@BootContract` is
  proto-Pillar-2 fail-loud-at-startup; `contract_violations.py`
  is a Pillar-3 projection candidate). 402 can be restructured
  to build on Pillars 2+3 rather than reinventing the
  enforcement surface.
- **Tempdoc 403** (scoped, not started): observability retention.
  Substantial overlap with Pillar 1 — cohort-hash cascade
  becomes manifest-reachability GC (git-gc pattern) under
  Pillar 1. Same principles, different mechanism.
- **Tempdoc 404** (design; current canonical direction).
- **Tempdoc 405** (this): pre-implementation findings.

---

## 10. References

- **Tempdoc 404 §10-§11** — the recommendation this pre-impl
  executed against.
- **Tempdoc 400 §23.9.3** — the D-3 bug fixed during P1.C.3.
- **`docs/observations.md`** — D-3 marked resolved with commit SHA.
- **`scripts/jseval/jseval/projections/_spike_typed.py`** — Pillar 3
  spike artifact (keep as reference; delete once Pillar 3 either
  lands or is definitively shelved).
- **`scripts/jseval/jseval/projections/_spike_schema.py`** — Pillar 2
  spike artifact (same disposition).
- **`scripts/jseval/tests/test_spike_typed_projection.py`** +
  **`test_spike_schema.py`** — tests pinning the spike findings
  into the suite.

---

## 11. Next-step activities + results (Draft 2 addendum)

After the initial P1+P2 pre-implementation work, additional
confidence-raising activities executed. Results captured here.

### 11.1 Activity A — Combined Pillar 2+3 spike (COMPLETE, positive)

Commit `4fc25509f`. Wired `_spike_schema.validate_span` into
`_spike_typed.ProjectionContext`. Registration-time
`SchemaViolation` when projection declares unknown kind; per-span
`coverage.<kind>.schema_violations` when producer emits non-
conformant attrs. 6 new tests in `TestCombinedPillar2And3Spike`.

**H6 resolution empirically demonstrated:**
- `test_registration_time_kind_check_catches_unknown_kind` —
  projection declaring `reads_kinds=("encoder.session_run",)`
  (hypothetical renamed form) fails loudly at registration.
- `test_read_kind_surfaces_schema_violations_on_real_spans` —
  a producer emitting `encoder.ort_run` spans without
  `encoder.gpu` has the violation surfaced in coverage.
- `test_H6_scenario_with_combined_spike_resolves_loudly` — the
  original silent-failure scenario is now operator-observable.

**Confidence impact:** Pillars 4 → 2 → 3 MVP: 4/10 → **6/10**.
H6 reduced from "structural silent failure" to "surfaces in
coverage output + operator can add kind-level validator."

**One documented gap:** `validate_span` doesn't reject unknown
kinds yet (silently passes). A separate validator pass over ALL
kinds (not just declared reads) would close this. Listed as
future refinement; not load-bearing for the MVP claim.

### 11.2 Activity B — 300q noise envelope (INCOMPLETE after 2 attempts)

Attempted twice. Both runs failed for different reasons. The
**fact of both failures is itself evidence** about Pillar 4's
operational viability.

**Attempt 1 (`runs=5, max-queries=300`, ~25 min expected):**
Aborted at run 2 with `cohort_hash unstable across identical
reruns`. Phase 2.0 guard fired correctly. Root cause was
operator error: I committed a Draft-2 doc update to the worktree
mid-calibration, changing `git_sha` (a cohort-identity field)
between run 1 and run 2. The guard did its job; not a regression
of Phase 2.0. The issue was the same one documented in tempdoc
400 Phase 3 §27.3 "don't commit mid-calibration."

**Attempt 2 (`runs=2, max-queries=300`, ~8 min expected):**
Aborted at run 1 query phase with `WinError 10061 connection
refused`. The Worker JVM became unreachable mid-ingest (last
`worker.log` entry at 19:15:33; jseval first connection failure
at 19:15:37 — 4s window). No exception stack in worker.log;
consistent with external process death or JVM OOM. Not
reproduced a third time (time budget exhausted).

**Implications for Pillar 4:**

These two failures are operational evidence, not code defects:

1. **Pillar 4 requires reliable calibration.** Cost envelopes
   must be producible on demand to be useful. If a 25-min 5-run
   calibrate aborts ~50% of the time due to operator-environment
   issues (commits, Worker stability), Pillar 4's "continuously
   calibrated" promise is aspirational rather than real.
2. **The operator-pitfall of committing mid-calibrate is real
   and under-documented.** The Phase 2.0 error message says "Phase
   2.0 has regressed, invoke diagnostic" — which misleads (like
   it misled me). A clearer error that notes `git_sha` drift
   would save future operators the same investigation.
3. **Worker instability at 300q scale is a new signal.** Prior
   §23.9 smokes all used 50q and ran to completion. Scaling to
   300q surfaces intermittent failures whose root cause isn't
   documented. Pillar 4's cost-envelope-at-production-scale
   requires this to be solved.

**Confidence impact:**
- Pillar 4: 4/10 → **3/10** (infra-stability concerns added to
  H5 noise-floor concerns).
- The Pillars 4 → 2 → 3 MVP: **6/10 still**, because Activity A's
  H6 resolution dominates and Pillar 4 is the last of the three
  in sequence — by the time it lands, infra issues may be
  resolved independently. Net stays.

**Not scheduled for retry in this session.** Infra-stability
investigation is its own tempdoc or observation; B's question is
"does 300q tighten the noise envelope enough to catch ≥10%
regressions" and that needs multiple successful calibrations to
answer. Open observation below (§11.6).

### 11.3 Activity C — Scoped-fix playbook (COMPLETE)

Commit `4fc25509f`. New canonical doc
`docs/reference/observability-scoped-fix-playbook.md` (~280
lines). 5-step procedure, contract-test template, concrete
examples (D-1 / D-3 fixes + Workstream C proactive test),
relationship to tempdocs 404/405.

**Confidence impact:** the alternative-to-adoption path is now
first-class engineering discipline. The §11 "don't start full
adoption" recommendation rests on "scoped fixes keep up" which
rests on "the scoped-fix pattern is documented + reproducible."
Activity C closes that loop.

### 11.4 Activity D — Silent-failure log (COMPLETE)

Commit `4fc25509f`. New "Silent-failure log" section in
`docs/observations.md` with template + review cadence. Next
review 2026-07-22.

**Confidence impact:** the §10.5 trigger becomes data-driven.
At 3-month review the decision is a count, not a judgment.

### 11.5 Revised confidence (post-A/C/D; pending B)

| Pillar | 405 §6 | Post A/B/C/D | Net driver |
|---|---|---|---|
| 1 (run-as-manifest) | 2/10 | **2/10** | Unchanged; H2 unresolved |
| 2 (schema-first) | 5/10 | **6/10** | Activity A demonstrated the combined pattern works |
| 3 (typed transformations) | 6/10 | **7/10** | Ergonomics + Activity A H6 resolution |
| 4 (cost as signal) | 4/10 | **3/10** | Activity B both-attempts failure adds infra-stability concern on top of H5 |
| MVP (Pillars 4 → 2 → 3) | 4/10 | **6/10** | H6 resolution dominates |
| Full 4-pillar | 3/10 | **3/10** | H2 still unresolved; MVP stays available |

The MVP confidence of 6/10 is now the same as Draft 1's
original 6/10 for the OLD (Pillars 3+4 alone) MVP — except now
the MVP is structurally correct (includes Pillar 2) rather than
structurally flawed. Same number, better meaning.

### 11.6 Open observations from Activities A-D

- **O-1 calibrate.py error message on cohort-hash instability.**
  The current error cites Phase 2.0 regression as the default
  cause. For operator-error causes (committing mid-run), the
  error should mention `git_sha` drift first. Small scoped-fix:
  calibrate snapshots `git_sha` at run 1 + explicitly checks for
  drift at runs 2+ with a "did you commit during calibration?"
  hint. ~30 LOC.
- **O-2 Worker instability at 300q ingest.** No crash stack in
  worker.log at attempt 2 failure point; needs investigation as
  its own observation. Blocks B from producing real data.
  Logged in `docs/observations.md` silent-failure-log section
  (or the main inbox depending on classification).
- **O-3 schema's validate_span doesn't reject unknown kinds.**
  Activity A gap. Schema-side refinement; small.

### 11.7 Remaining technical work items

- **Activity E (retrospective counterfactual audit):** would
  empirically validate the scoped-fix engineering discipline
  against the ~16 historical silent-failure defects.
- **Activity F (H2 mitigation prototype):** design concrete
  fsync + atomic-rename order for Pillar 1's manifest journal.
  §14.1 / §12.1 describe the pattern; F would produce a
  working prototype.
- **Activity G (full schema write):** validates Pillar 2's
  ~150 LOC scope estimate by writing the full canonical schema
  covering every current span kind.

These are technical refinement work items. Each raises confidence
on the corresponding pillar by turning analytical claims into
working code.

---

## 12. Web-research addendum (Draft 2 — 2026-04-22)

Previous pre-impl passes (P1 + P2 + Activities A-D) were entirely
internal analysis. Four of the supposedly-unresolved adversarial
findings turn out to be **solved problems in the industry** that a
deliberate web-research pass surfaces. Recording here so the
confidence ratings reflect known prior art instead of leaving the
pillars anchored on internal reasoning alone.

### 12.1 H2 (Pillar 1 producer-crash) — resolvable via WAL + Git patterns

Original H2 claim: Pillar 1's manifest cannot survive a producer
crash mid-rotation because lifecycle events are written by the same
process. I accepted this as unresolved design flaw.

**Industry prior art:** SQLite WAL and Git's object model both solve
exactly this problem.

Pattern:
1. Write the artifact shard (content-addressed by SHA-256) to disk,
   then `fsync`. Crash here leaves an orphaned shard — no manifest
   entry yet references it. Garbage, not inconsistency.
2. Append an entry to the manifest journal referencing the shard's
   hash. Journal uses WAL semantics: append-only, fsync-on-commit,
   partial entries discarded by replay on next open.
3. Orphaned shards are detected by "has any manifest entry referenced
   me?" content-hash lookup and reclaimed by a `git gc`-equivalent
   maintenance pass.

This is exactly Git's write order — `git hash-object` writes the
object first (atomic rename), **then** `git update-ref` updates the
ref. Crash between them leaves orphaned objects that `git fsck` + `git
gc` reclaim. The invariant Git preserves is not "no data loss" but
"no inconsistency between refs and referenced objects."

**Corrected Pillar 1 claim:** not "structurally impossible to lose
artifacts" but "**orphaned shards are recoverable garbage; the
manifest never asserts completeness it doesn't have**." This is a
weaker but honest guarantee that exactly matches SQLite WAL + Git.

**Confidence impact:** Pillar 1: 2/10 → **4/10**. Claim revised but
achievable; prior art validates the pattern at exactly the scale we
target.

### 12.2 H5 (Pillar 4 noise floor) — theoretically resolvable

H5 claimed Pillar 4 catches only ≥25% regressions at 50q scale.
Activity B tried to measure 300q empirically; failed twice.

**Industry prior art:** basic statistics + Sakai 2018 "Topic Set Size
Design for Paired and Unpaired Data" give concrete N-queries-for-power
formulas.

Analytical scaling from §23.9.2 measurements:
- 50q CV for `mean_ms` = 8.9%.
- CV of sample mean scales as `1/√N` under iid noise.
- 50q → 300q tightens by `√6 ≈ 2.45×`.
- Predicted 300q CV ≈ **3.6%**.
- 3σ gate at 3.6% catches ≥10.9% regressions (vs 25% at 50q).
- 2σ gate catches ≥7.3%.

**Caveat from benchmarking literature:** timing measurements on real
hardware are NOT iid (thermal throttling, scheduling, cache state →
correlated noise). CLT-based `1/√N` is an upper bound; real-world
tightening is usually worse. Pillar 4 should use **median-of-N +
bootstrap CI** rather than `mean ± k·σ` — standard practice in robust
benchmarking.

**Operational concern remains.** 405 §11.2 O-2 (Worker instability at
300q) is unaffected by analytical work.

**Confidence impact:** Pillar 4: 3/10 → **5/10**. Theoretical floor
tightens with N; median-of-N refinement is well-established.
Operational fragility is the binding constraint now.

### 12.3 H7 (cargo-culting) — architectural references revised

H7 claimed OpenLineage / Dagster / Beam are over-scaled for a
single-operator desktop project.

**Desktop-scale references that ARE appropriate:**

- **SQLite WAL mode** — single-user desktop database with crash-safe
  atomic commits. Used by Firefox, Chrome, iOS, Android, macOS Mail.
  Billions of installs at exactly our scale.
- **Git's `.git/objects/` + refs** — content-addressed object store
  + atomic ref updates in a single-user local system.
- **Cargo / npm / pnpm lockfiles + content-addressed caches** —
  pnpm's `~/.pnpm-store/` is content-addressed, hardlinked into
  per-project `node_modules`. Precisely Pillar 1's pattern at
  desktop scale.
- **OpenTelemetry Semantic Conventions** (§12.4) — validates
  schema-first approach but at a different scope (see §12.4).

Tempdoc 404 §3.1's reference list (OpenLineage + DVC + Git objects +
Beam/Flink + Dagster + SpeedCurve) is a mix of appropriate +
over-scaled. Corrected list above.

**Confidence impact:** no per-pillar number moves (H7 was framing,
not feasibility). But the **cargo-cult stigma is removed** from the
design. Pillar 1/2/4 prose should cite SQLite + Git + Cargo, not
OpenLineage + Dagster.

### 12.4 Pillar 2 — OTel SemConv refines the approach

§3.2 spike proposed a single JSON schema file both Python + Java
read. Before web research, I worried this was under-engineered vs
OpenTelemetry Semantic Conventions.

**Industry prior art:** OpenTelemetry's Telemetry Schemas take a
**different** approach:

- Producers embed a `schema_url` in OTLP messages.
- Consumers on schema-version mismatch apply documented
  **transformations** (rename, drop deprecated, etc.) rather than
  rejecting data.
- OTel **explicitly does not validate required attributes at
  emission time**.

This is broader-scope-but-weaker-invariant than what I proposed. OTel
must work across untrusted producers (vendor A → vendor B); schema
URL + transforms let both sides evolve independently. Our scale
doesn't have that constraint — we control both sides — so we can
afford a **stronger** invariant:

- **Fail-loud at emission** when required attrs missing.
- **Fail-loud at registration** when consumer declares unknown kind
  (Activity A proved this works).
- Single schema file both sides read; no URL indirection.

OTel's approach only applies if we ship a user-installable consumer
against unknown producers (not planned). Until then, our
stronger-invariant approach is correct and well-precedented.

**Confidence impact:** Pillar 2: 6/10 → **7/10**. Schema-first IS
the industry standard; our scale allows stronger enforcement than
OTel's.

### 12.5 Revised confidence after web research

| Pillar | Post A-D (§11.5) | Post web research | Net |
|---|---|---|---|
| 1 (run-as-manifest) | 2/10 | **4/10** | +2; SQLite-WAL + Git patterns resolve H2 |
| 2 (schema-first) | 6/10 | **7/10** | +1; OTel SemConv validates; our scale → stronger invariant |
| 3 (typed transformations) | 7/10 | 7/10 | No change; pattern already validated |
| 4 (cost as signal) | 3/10 | **5/10** | +2; 1/√N theoretical lift + median-of-N; operational concerns remain |
| MVP (Pillars 4 → 2 → 3) | 6/10 | **7/10** | +1; Pillar 2 + Pillar 4 lifts |
| Full 4-pillar | 3/10 | **5/10** | +2; H2 resolution + architectural framing corrected |

### 12.6 What web research specifically changed

- **H2 moved from unresolved design flaw to solved problem.**
  Pillar 1's claim is revised but achievable via SQLite-WAL + Git
  content-addressed pattern.
- **H5 partially resolved analytically.** 300q → ~3× tighter noise
  floor (theoretical upper bound; real-world lower due to non-iid);
  median-of-N refinement standard in literature.
- **H7 architectural framing corrected.** Desktop-scale references
  (SQLite, Git, Cargo) replace cloud-scale references (OpenLineage,
  Dagster, Beam).
- **Pillar 2 approach confirmed + refined.** OTel SemConv validates
  schema-first; our scale allows stronger invariant than OTel's.

### 12.7 What web research didn't change

- **H3 recursion of trust** — no prior art found that fundamentally
  solves health-check-on-potentially-biased-baseline. Acceptable
  limit; bounded recursion depth.
- **H6** — already resolved by Activity A; web research confirmed
  schema-first fail-loud is unprecedented-at-OTel-scale but standard
  at our scale.
- **Partial-implementation technical concern** — mid-sequence
  pause leaves hybrid state. Each pillar should be fully landed
  before the next begins.
- **Activity B operational fragility (O-2)** — analytical work
  doesn't fix it. Needs its own investigation.

### 12.9 Meta-observation on the research gap

The confidence profile moved more in a few hours of web research
than it did across two earlier passes (P1+P2 and Activities A-D).
Not because those were wasteful — internal analysis surfaced the
specific unknowns. But the unknowns that remained were precisely
the ones the industry has already solved. A checklist item for
future confidence-raising work: **"have I checked the industry
prior art on the remaining unresolved items?"** This should be one
of the first pre-impl activities, not one of the last.

### 12.10 Sources consulted

- [SQLite WAL](https://sqlite.org/wal.html)
- [Write-ahead logging (Wikipedia)](https://en.wikipedia.org/wiki/Write-ahead_logging)
- [Git Maintenance and Data Recovery](https://git-scm.com/book/en/v2/Git-Internals-Maintenance-and-Data-Recovery)
- [Git refs](https://git-scm.com/book/en/v2/Git-Internals-Git-References)
- [pnpm content-addressable store (LogRocket)](https://blog.logrocket.com/javascript-package-managers-compared/)
- [Cargo.lock FAQ](https://web.mit.edu/rust-lang_v1.25/arch/amd64_ubuntu1404/share/doc/rust/html/cargo/faq.html)
- [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/)
- [OpenTelemetry Telemetry Schemas](https://opentelemetry.io/docs/specs/otel/schemas/)
- [Sakai 2018 — Topic Set Size Design](https://dl.acm.org/doi/pdf/10.1145/3234944.3234971)
- [TREC 2004 Robust Retrieval Track Overview](https://trec.nist.gov/pubs/trec13/papers/ROBUST.OVERVIEW.pdf)
- [Statistical Methods for Reliable Benchmarks](https://modulovalue.com/blog/statistical-methods-for-reliable-benchmarks/)
- [Robust benchmarking in noisy environments (Edelman et al.)](https://math.mit.edu/~edelman/publications/robust_benchmarking.pdf)
- [Evaluating Evaluation Measure Stability (Buckley & Voorhees)](https://dl.acm.org/doi/10.1145/345508.345543)
