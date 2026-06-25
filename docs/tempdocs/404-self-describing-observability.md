---
title: "404 — Self-describing observability architecture"
---

# 404 — Self-describing observability architecture

## 0. Status

**Design document.** Architectural direction for the observability
stack that resolves the silent-failure classes tempdoc 400 kept
patching. §3 describes the five pillars; §10 is a confidence
evaluation (epistemic state of the design, not an adoption gate).
Implementation sequencing is in §9 + 405 §5.

**Non-goals — preserved as anti-pattern discipline:**

- "Ship Pillar 1 first because it's the most elegant" — §9
  sequences for structural correctness (Pillar 3's validation
  depends on Pillar 2's schema; Pillar 5 reuses Pillar 3's
  projection base). Respect the order.
- "Migrate all existing artifacts to the new format" — the design
  is additive. Legacy data stays in legacy format; new data lands
  in new format. Dual-read during transition.
- "Unify Python + Java schema language as prerequisite" — the
  schema can be maintained manually in both languages for years
  before an IDL investment pays back. Don't gate the work on the
  generator.

**Revision history:**

- **Draft 1 (2026-04-22):** initial theoretical design with four
  pillars (run-as-manifest, schema-first, typed transformations,
  cost-as-signal). §10 rated confidence at 3-4/10 end-to-end /
  6/10 MVP based on unmeasured assumptions.
- **Draft 2 (2026-04-22):** pre-implementation P1+P2 + Activities
  A-D executed. Findings captured in tempdoc 405. Key outcomes:
  H2 (Pillar 1 producer-crash) identified as an unresolved design
  flaw at this stage. H6 (Pillars 3+4 MVP insufficient without
  Pillar 2) empirically confirmed by Activity A's spike, then
  resolved by wiring schema validation into the typed context.
  §13 "Post-P1+P2 addendum" summarizes the delta.
- **Draft 3 (2026-04-22):** web research on industry prior art
  surfaces that several Draft 2 residuals are solved problems:
  H2 by SQLite WAL + Git content-addressed object + atomic ref
  pattern; H5 by 1/√N statistical scaling + median-of-N +
  bootstrap CI (Sakai 2018 topic-set-size design); H7 by
  replacing cloud-scale references with desktop-scale ones
  (SQLite / Git / Cargo). Pillar 1's "structurally impossible"
  claim revised to "orphaned shards are recoverable garbage;
  manifest never asserts completeness it doesn't have." New §14
  "Web-research addendum." **Meta-lesson:** industry-prior-art
  check should be an EARLY pre-impl activity, not a late one.
- **Draft 4 (2026-04-22):** autonomous research on D-2 class
  (OS/process-boundary silent failures; not caught by Pillars
  1-4). Delivered in §15 as a new **Pillar 5 — Process-Boundary
  Contracts**: every parameter crossing a process boundary is
  (a) canonicalized at the caller, (b) emitted as
  `effective_env.{python,java}.json`, (c) validated by a
  differential `boundary_agreement` projection. Composes with
  existing pillars (reuses Pillar 2 schema + Pillar 3 projection
  infra); doesn't duplicate. Confidence 6/10 (single documented
  D-2 bug sample; whitelist breadth empirical). Framing
  cleanup in this draft: the tempdoc no longer gates implementation
  on "worth it" / "trigger" language. The design is what the design
  is; confidence numbers are evaluations of it.

**Created:** 2026-04-22.
**Depends on:** tempdoc 400 §23.9 (D-3 finding + 10 theoretical
improvements).
**Relates to:** tempdocs 401, 402, 403 (all three are scoped follow-ups
that operate in the CURRENT architecture; this tempdoc proposes a
different architecture they would eventually migrate into).

---

## 1. Context

Tempdoc 400 Phase 6 closed 15 silent-failure modes. §23.9 found one
more (D-3 traces-mirror rotation gap, 77.1% span loss). §23.9.4
catalogued 9 additional theoretical improvements that share a pattern:
each is a silent-failure mode where producer and consumer disagreed
about shape, coverage, or completeness, and no part of the system was
positioned to detect the disagreement.

This tempdoc argues those 10 items are not 10 independent problems.
They are symptoms of one missing architectural principle, and a
correct long-term design applies that principle uniformly.

---

## 2. Diagnosis — one missing principle

**The principle that is missing:** *every observability artifact must
be self-describing — it carries its schema, its lineage, its coverage,
and its execution provenance, so downstream consumers cannot silently
lose context.*

The current telemetry stack has excellent producer instrumentation
(tempdoc 400 Layer 2) and good consumer instrumentation (Layer 4
projections), but **no meta-layer that validates the producer-consumer
chain is intact**. A producer emits. A consumer reads what it finds
on disk. If the wire format changes (D-1), if the on-disk artifact is
partial (D-3), if the consumer's assumed schema drifts (fixture-vs-
emitter drift), or if the run's coverage is incomplete — the output
still looks healthy. Silent failure by construction.

Phase 6 addressed 15 specific instances by wiring up ad-hoc detection
for each. §23.9 proved the ad-hoc approach is insufficient: D-3 found
a new instance of the same pattern, on infrastructure Phase 6 already
touched.

A correct design replaces ad-hoc detection with **structural detection**:
the architecture itself prevents the chain from breaking silently.

---

## 3. Architecture — five pillars

### 3.1 Pillar 1 — Run as artifact manifest

**Current shape.** A run is a directory of loose files. `traces.ndjson`,
`metrics.ndjson`, `summary.json`, `manifest.json`, `projections/*.json`.
End-of-run snapshot; rotated siblings silently dropped.

**Proposed shape.** A run is an **append-only lifecycle event stream**
rolled up into a **content-addressed artifact manifest**. The manifest
is the root of trust; artifacts are content-addressed shards; every
state change is an event.

Sketch (schema v2):

```json
{
  "run_id": "...",
  "schema_version": 2,
  "cohort_hash": "...",
  "lifecycle": [
    {"ts": "...", "event": "backend.started"},
    {"ts": "...", "event": "ingest.completed", "docs": 5184},
    {"ts": "...", "event": "traces.rotated",
     "sealed": "artifacts/sha256:<h>.ndjson",
     "span_window": ["...", "..."]},
    {"ts": "...", "event": "projection.emitted",
     "name": "encoder_drift",
     "inputs": ["artifacts/sha256:<h1>", "artifacts/sha256:<h2>"],
     "output": "artifacts/sha256:<h3>"},
    {"ts": "...", "event": "envelope.embedded",
     "source": "cohort_baselines/<hash>/envelope.json"},
    {"ts": "...", "event": "run.closed"}
  ],
  "artifacts": {
    "traces": [
      {"path": "artifacts/sha256:<h>.ndjson",
       "span_count": 30169, "window": ["...", "..."]}
    ],
    "metrics": [...],
    "projections": {...}
  }
}
```

**What becomes structurally impossible:**

> **⚠ CORRECTED IN §14.** Draft 1 overstated the guarantee. A
> producer crash between shard-write and manifest-append leaves
> the shard orphaned on disk with the manifest not referencing it
> (H2). Revised claim: **"orphaned shards are recoverable garbage;
> the manifest never asserts completeness it doesn't have."**
> See §14.1 for the SQLite-WAL + Git-atomic-ref pattern that
> makes this weaker-but-honest guarantee achievable.

- Losing a rotated trace file. Every rotation is an event; every
  sealed shard is an artifact reference. Consumers traverse the
  artifact list; they cannot miss data.
- Post-hoc backfill as a file rewrite. Envelope embed after
  calibrate becomes an event append, preserving provenance.
- Cross-runner inconsistency. Counterfactual, shadow-eval,
  bench-concurrency, bisect all emit the same manifest shape;
  Layer-4 projections work uniformly across them.

**Architectural reference** *(corrected in §14.3):* git objects
+ SQLite WAL + Cargo / pnpm content-addressed cache. Desktop-scale
proven patterns. (Draft 1 also cited OpenLineage + DVC; those are
cloud-scale references and have been removed — see §14.3.)

### 3.2 Pillar 2 — Schema-first telemetry

**Current shape.** Span kinds, attrs, and structural fields are
implicit contracts. `NdjsonSpanExporter.ALLOWED_ATTRS` is a hand-
maintained allowlist. Consumers read fields like `duration_ms`
without a canonical registry asserting "this field exists."

**Proposed shape.** A **canonical observability schema** (checked
into the repo, versioned, content-hashed) defines every span kind,
every attr, every metric shape, every projection output. Producers
emit against the schema; consumers declare reads against the schema;
build-time + runtime checks enforce agreement.

Sketch (partial):

```json
{
  "schema_version": 1,
  "kinds": {
    "search/retrieval": {
      "emitted_by": "io.justsearch.indexerworker.services.SearchOrchestrator",
      "cadence": "one per POST /api/knowledge/search",
      "required_attrs": ["search.mode"],
      "identity_attrs": {"all_of": ["commit.schema_fp", ...]},
      "optional_attrs": ["search.searcher_generation", "search.took_ms"]
    }
  },
  "attrs": {
    "search.searcher_generation": {
      "type": "struct",
      "fields": [
        {"name": "id", "type": "string"},
        {"name": "epoch_ms", "type": "long"}
      ]
    }
  },
  "structural_fields": [
    {"name": "duration_ms", "type": "double", "source": "nanos/1e6"}
  ]
}
```

**What becomes structurally impossible:**

- Producer-consumer shape drift (D-1 class). Schema-declared field
  missing from emitter is a compile-time error; consumer reading a
  field not in schema is a registration-time error.
- Cadence drift (§23.9.4 #2). Schema declares "one per
  `/api/knowledge/search`"; a smoke harness validates cadence
  automatically.
- Identity-attr thinning (§23.9.4 #10). `all_of` in the identity-attr
  spec means any missing attr fails emission.
- Mixed-format attrs (§23.9.4 #9). `search.searcher_generation`
  declares both string and numeric forms; consumers pick the shape
  they need; schema guarantees both are emitted.

**Architectural reference:** OpenTelemetry Semantic Conventions;
Avro / Protobuf schema registries; JSON-Schema `$id` versioning.
The principle is: schema is a code artifact both sides import, not
an informal convention.

### 3.3 Pillar 3 — Projections as typed transformations

**Current shape.** A projection is a Python function that reads paths
and writes a JSON file. Duration, coverage, input lineage, and health
are invisible. A projection on biased input looks identical to a
projection on complete input.

**Proposed shape.** A projection is a **typed transformation** with
declared inputs (by kind), declared outputs (by kind), recorded
execution provenance, and self-health assertions.

Sketch:

```python
@projection(
    name="encoder_drift",
    schema_version=2,
    reads_kinds=["encoder.ort_run"],
    reads_fields=["duration_ms", "attrs.encoder.name"],
    reads_cohort_baseline="span_distributions.json",
    writes_kind="projection.encoder_drift",
    cadence="one per run",
    health_checks=[
        "input_span_count_within_10pct_of_cohort_baseline",
        "baseline_exists_or_status_is_no_baseline",
    ],
    expected_cost_ms=200,
)
def produce(ctx: ProjectionContext) -> ProjectionOutput:
    ...  # ctx.read_kind("encoder.ort_run") iterates every shard
```

Output carries provenance:

```json
{
  "projection_name": "encoder_drift",
  "schema_version": 2,
  "execution": {"duration_ms": 142, "input_artifacts": [...]},
  "coverage": {"input_span_count": 28133, "expected_minimum": 25000},
  "health": [{"level": "ok", "check": "..."}],
  "status": "ok",
  "result": {...}
}
```

**What becomes structurally impossible:**

- Per-projection latency blindness (§23.9.4 #5). `duration_ms` is
  first-class on every output.
- Silent biased-input outputs (D-3). Coverage declarations fail the
  `input_span_count_within_10pct` health check automatically.
- Degenerate short-run windows (§23.9.4 #3). Short runs fail the
  window-size health check; consumers see `degraded` status.
- Hidden cost regressions (§23.9.4 #8, Pillar 4 interaction). Every
  projection declares `expected_cost_ms`; deviations visible in the
  run rollup.

**Architectural reference** *(corrected in §14.3):*
pytest-Hypothesis contract-test patterns + the Workstream C
contract-test pattern already in the codebase. (Draft 1 also cited
Apache Beam / Flink / Dagster as references; those are cloud-scale
multi-producer / multi-consumer systems where typed-transformation
graphs are the only tractable coordination pattern. Our single-
producer / single-consumer scale doesn't need that machinery —
see §14.3.)

Transformations declare types; coverage is first-class; the runtime
validates.

### 3.4 Pillar 4 — Cost as continuously-measured signal

**Current shape.** Performance cost of opt-in features is measured
once (if at all) and assumed stable. Tempdoc 400's B3 deferred six
times; §23.9 caught a +22% regression on its first real measurement.

**Proposed shape.** Every opt-in observability feature has a
**continuously-calibrated cost envelope**, stored in the same
`cohort_baselines/` registry as correctness envelopes. The gate
compares both correctness AND cost.

Sketch:

```json
// cohort_baselines/<hash>/cost_envelope.json
{
  "schema_version": 1,
  "feature_costs": {
    "tracing_level:none":     {"mean_ms": 36.8, "stdev_ms": 2.8},
    "tracing_level:detailed": {"mean_ms": 45.1, "stdev_ms": 3.8},
    "projection_set:default": {"total_duration_ms": 180}
  }
}
```

**What becomes structurally impossible:**

- Unmeasured feature overhead (§23.9.4 #8). Opting in without a cost
  baseline is an error; the baseline is a required output of any
  calibrate-cost invocation.
- Silent cost drift. Nightly gate fails when `detailed`-tracing
  overhead doubles.
- B3-style perpetual deferral. You cannot merge an observability
  feature without characterizing its cost.

**Architectural reference:** continuous-performance-budget tooling
(SpeedCurve, Calibre); FinOps unit-cost tracking; cost-based gates
in CI/CD.

### 3.5 Pillar 5 — Process-boundary contracts

**Current shape.** Parameters crossing between processes (Python
jseval driver ↔ Gradle-launched JVM Worker; jseval ↔ llama-server;
Head ↔ Worker gRPC) are interpreted independently by each side. A
relative path resolves against each process's `cwd`; an env var's
meaning depends on each shell's quoting; a locale-dependent sort
produces different manifest hashes on different processes.
Pillars 1-4 reason about the artifact graph *inside* each process
but are silent about the edge between them.

**Proposed shape.** Every parameter crossing a process boundary is
(a) **canonicalized by the caller** to an absolute, OS-native,
resolved form before it crosses; (b) **each process emits** one
`effective_env.{python,java}.json` at startup containing its
resolved view (cwd, data_dir, locale, timezone, path separator,
OS, whitelisted env vars, argv hash); (c) **a differential
projection** `boundary_agreement` reads both blobs and fails when
declared-shared fields disagree.

Sketch:

```json
// schema/boundary.v1.json — declares which fields must agree
// across which process pairs
{
  "schema_version": 1,
  "process_pairs": [
    {
      "pair": ["jseval.python", "worker.java"],
      "must_agree": ["data_dir", "models_dir", "timezone", "locale"],
      "may_differ": ["pid", "cwd", "os"]
    }
  ]
}
```

```json
// effective_env.python.json emitted at jseval startup
{
  "process": "jseval.python",
  "cwd": "F:\\JustSearch\\scripts\\jseval",
  "data_dir": "F:\\JustSearch\\scripts\\jseval\\tmp\\eval-results",
  "locale": "en_US.UTF-8",
  "timezone": "Europe/Berlin",
  "os": "Windows_NT"
}
```

**What becomes structurally impossible:**

- D-2 class (process-boundary parameter interpretation drift).
  The §23.8 D-2 bug would fail deterministically:
  `effective_env.python.data_dir != effective_env.java.data_dir`
  → `boundary_agreement` reports MISMATCH → exit 1.
- Silent locale-dependent manifest drift. If either process
  hashes with locale-dependent sort, the schema requires locale
  to agree across pair; drift fails.
- Timezone-naive parsing across processes. Pair declares timezone
  must agree; drift fails at `boundary_agreement` time.
- Env var semantics drift. Whitelisted env vars must round-trip
  identically; shell-quoting inconsistency fails.

**Composition with other pillars:**

- Reuses **Pillar 2 schema** (`boundary.v1.json` lives in the
  canonical schema registry).
- Reuses **Pillar 3 projection infrastructure** (`boundary_agreement`
  is a typed projection with `reads_kinds=["effective_env"]`).
- `effective_env.*.json` files become artifacts in the **Pillar 1
  manifest** (content-addressed + referenced from the run's
  lifecycle log).

**Architectural reference:** Docker Compose's `docker compose config`
(renders the effective canonical config for auditing); `.gitattributes`
over per-machine `core.autocrlf` (contract is a committed artifact,
not ambient config); Kubernetes Downward API (caller resolves,
callee consumes a fixed string); Grafana Alloy `discovery.process`
+ Linux `auditd` path reconstruction (observability plane records
effective resolved values for forensics).

Full design + scope estimate + prior art survey in §15.

---

## 4. Cross-pillar invariants

The five pillars together produce emergent properties.

### Invariant 1 — Self-describing at every layer

Every artifact — span, metric record, projection output, manifest,
envelope, cost baseline — carries its schema version + content hash
+ lineage reference. No consumer ever reads data it doesn't know
the shape of. Schema drift = type error at registration; missing
ancestor = lineage hole at manifest-load.

### Invariant 2 — Observability-about-observability

Every producer emits a **structural health signal** alongside its
primary data. An exporter that rotated emits `telemetry.rotation`;
a backend that saw no search traffic in 5 min emits
`telemetry.staleness`; a projection with biased input emits a
health warning alongside its output. This is the meta-layer that
prevents silent failure by construction.

### Invariant 3 — Schema-first wire formats

Every wire format derives from the canonical schema. There are no
hand-written parsers; all parsers are generated or check the schema.
Adding `duration_ms` is a schema addition that propagates by
construction, not a one-off code change at one call site.

### Invariant 4 — Cost is a dimension of correctness

A system producing correct outputs at 10× cost is broken in the same
way as a system producing wrong outputs. Gate checks, envelope
calibration, and nightly workflows treat both dimensions uniformly.

---

## 5. Symptom mapping

| # | §23.9.4 symptom | Pillar(s) | Resolution |
|---|---|---|---|
| 1 | D-3 rotation-aware mirror | 1 | Rotation is a lifecycle event; every sealed shard referenced by manifest. No mirror needed. |
| 2 | `search/retrieval` count short | 2 | Cadence declared in schema; smoke harness auto-validates. |
| 3 | Rate-timeline window too large | 3 | Projection declares window health check; short runs surface `degraded`. |
| 4 | Envelope auto-embed backfill | 1 | Backfill appends `envelope.embedded` event; manifest references envelope. |
| 5 | Per-projection latency | 3 | `execution.duration_ms` first-class on every output. |
| 6 | Layer-5 runner outputs not indexed | 1 | Every runner produces same manifest shape; uniform cross-index. |
| 7 | Traces rotation invisible | 1, 2 | Rotation events in lifecycle log + `telemetry.rotation` metric. |
| 8 | Detailed-tracing cost unmeasured | 4 | Cost envelope per feature, continuously calibrated. |
| 9 | `searcher_generation` string-only | 2 | Schema declares both `id` (string) and `epoch_ms` (numeric). |
| 10 | `commit.*` regression guard | 2 | Identity attrs are `all_of` in schema; missing = fail. |
| 11 | D-2 class — process-boundary parameter interpretation drift (path relativity, env var semantics, locale, timezone) | **5** (new) | Caller canonicalizes; each process emits `effective_env.*.json`; `boundary_agreement` projection fails on field disagreement. See §15. |

---

## 6. What this prevents long-term

The current design's failure mode is **hidden coupling across
producer-consumer boundaries**. Each fix is necessary but doesn't
prevent the next; every new projection, every new attr, every new
file format introduces another opportunity for silent drift.

The proposed structure rules out the whole class:

- **No silent producer-consumer drift** (Pillar 2): schema is
  canonical and enforced.
- **No silent artifact loss** (Pillar 1): manifest references every
  artifact by hash.
- **No silent projection degradation** (Pillar 3): coverage + health
  first-class.
- **No silent performance regression** (Pillar 4): cost
  continuously calibrated.
- **No silent process-boundary drift** (Pillar 5): every parameter
  crossing between processes is canonicalized at the caller +
  audited by a differential projection.
- **No accidental measurement deferral** (Pillar 4): cost baselines
  are part of opt-in, not a deferrable task.

---

## 7. Non-goals (explicit)

- **Not SaaS-grade multi-tenant.** Single-user desktop + nightly CI
  context. Manifests don't need concurrent writers or distributed
  consensus.
- **Not retroactive.** Applies to new data; old run artifacts stay
  in current format. Shim reads v1; shim retires eventually.
- **Not a replacement for canonical docs.** Schema is machine-
  readable source of truth; `08-observability.md` is the human-
  readable narrative. Both exist.
- **Not an ADR-worthy cross-cutting concern.** Each pillar is
  implementable as a focused tempdoc. No single monolithic
  redesign.
- **Not speculative infrastructure for hypothetical consumers.**
  Every element resolves a specific observed failure mode.

---

## 8. Open questions

Resolved before any implementation begins.

1. **Schema language choice.** JSON Schema (verbose, mature
   ecosystem), Protobuf (binds wire format, code-gen), or custom
   DSL (maintenance burden). Recommendation deferred; each has
   nontrivial tradeoffs.
2. **Python-Java cross-language schema.** One IDL + two generators,
   or two hand-maintained schema files kept in sync? The latter is
   lower-effort but drift-prone.
3. **Content-addressing granularity.** Per-file or per-shard
   chunks? Per-shard gives finer lineage but explodes file count.
4. **Migration path for existing `tmp/eval-results/` artifacts.**
   Keep as v1 forever? Migrate on first-read? Recalculate hashes
   retroactively? All three have drawbacks.
5. **Runtime cost of self-description.** Content-hashing every
   write adds overhead; manifest parse-on-read adds latency. Are
   these measured against Pillar 4's own cost budget?
6. **Who owns the canonical schema?** ssot-tools? A new
   `modules/observability-schema`? The schema authority has to be
   reachable from Java (telemetry) and Python (projections);
   current SSOT layout is file-based, which works.

---

## 9. Implementation sequencing

Sequenced for structural correctness — each pillar is positioned so
its prerequisites have landed. The order below reflects the updated
understanding from tempdoc 405 §5 (reversibility analysis) + Activity
A's combined 2+3 spike (405 §3.1) + §15's Pillar 5 addition.

| Order | Pillar | Scope | Reversibility |
|---|---|---|---|
| 1 | **Pillar 4** (cost as signal) | Python + nightly CI; extends existing `calibrate` with cost envelopes + gate | Yes; extends existing infrastructure |
| 2 | **Pillar 2** (schema-first) | Python + Java; canonical schema file + Java emitter validation + Python consumer registration. Unlocks Pillar 3's MVP value (H6 resolution). | Harder; emitter retrofit across ~20 sites |
| 3 | **Pillar 3** (projections as typed transformations) | Python-only; 7 projections + `base.py` refactor + ProjectionContext with schema validation. Builds on Pillar 2's schema. | Yes; easy to undo before + after |
| 4 | **Pillar 5** (process-boundary contracts) | Python + Java; `effective_env.{python,java}.json` emitters + `boundary_agreement` projection. Reuses Pillar 2 schema + Pillar 3 projection base. | Yes; ~400-500 LOC Python + ~150 LOC Java |
| 5 | **Pillar 1** (run-as-manifest) | Python + Java; content-addressed artifacts + lifecycle log (SQLite-WAL + Git atomic-ref pattern, §14.1) + consumer rewrites | Hardest; consumer migration ~1500-2500 LOC |

Each pillar lands as its own tempdoc (404-4, 404-2, 404-3, 404-5,
404-1). Sequencing rationale:

- **Pillar 4 first**: extends existing `calibrate`; smallest code
  footprint; independent of others.
- **Pillar 2 next**: unlocks Pillar 3's MVP structural prevention
  (H6). Java emitter retrofit is the biggest Java touch; do it
  while the codebase is in a known state.
- **Pillar 3 after 2**: every projection lands with schema-backed
  validation instead of string-literal drift hazards (demonstrated
  by 405 Activity A spike).
- **Pillar 5 after 3**: reuses the typed-projection base +
  schema infrastructure; adding boundary contracts is ~550 LOC on
  top of established patterns.
- **Pillar 1 last**: most invasive (consumer migration); benefits
  from the other pillars being stable before artifact layout
  changes ripple through them.

Ordering is not a go/no-go cascade — it's a dependency chain.
Later pillars build on earlier ones.

---

## 10. Confidence evaluation

Confidence is an epistemic state of the design — how well-understood
each pillar is, where residual design uncertainties live, and what
scope each piece would take to implement. Ratings are 1-10 based on:
prior-art strength, spike/measurement evidence, known limitations,
and scope measurements.

### 10.1 Per-pillar confidence

| Pillar | Confidence | Basis |
|---|---|---|
| 4 (Cost as signal) | **5/10** | Theoretical noise floor: `1/√N` scaling (Sakai 2018 formal analysis) + median-of-N + bootstrap CI standard in robust-benchmarking. Caveat: timing measurements are non-iid on real hardware (thermal, scheduling); actual tightening is less than theoretical. Operational fragility in the calibration path (405 §11.2 observations) is a separate binding concern — statistics don't fix infrastructure. |
| 2 (Schema-first) | **7/10** | OpenTelemetry Semantic Conventions validates schema-first as industry-standard. Our scale (single producer, single consumer, both controlled) allows a stronger invariant than OTel's (fail-loud emission vs OTel's transform-on-mismatch). §3.2 spike proved single-JSON-file + thin readers is sufficient; ~700 LOC scope. |
| 3 (Typed transformations) | **7/10** | Activity A spike validated ergonomics + resolved H6 (combined 2+3 spike showed `SchemaViolation` at registration + `coverage.<kind>.schema_violations` at read time catch producer-consumer kind drift loudly). ~1050 LOC scope measured. |
| 5 (Process-boundary contracts) | **6/10** | §15 D-2 research delivered a concrete three-layer mechanism (canonicalization + `effective_env` emission + `boundary_agreement` projection) with identified prior art (Docker Compose config, `.gitattributes`, Kubernetes Downward API, Grafana Alloy process discovery). Single documented D-2 bug sample; whitelist-breadth is empirical. ~550 LOC scope. |
| 1 (Run-as-manifest) | **4/10** | SQLite WAL + Git content-addressed object + atomic ref pattern (§14.1) resolves H2 producer-crash at billions-of-installs scale. Pillar 1's guarantee revised to "orphaned shards are recoverable garbage; manifest never asserts completeness it doesn't have" — weaker than Draft 1's claim but honest + achievable. Most invasive: ~1500-2500 LOC of consumer migration. |
| **End-to-end (all five)** | **5/10** | Architecture is well-understood + prior-art-validated; residual unknowns are primarily operational (Pillar 4 calibration fragility, Pillar 1 consumer migration surface). |
| **Sequencing-MVP (Pillars 4 → 2 → 3)** | **7/10** | H6 resolution validated. ~2500 LOC. Python-heavy with bounded Java emitter retrofit. |

### 10.2 Scope measurements

Revised from Draft 1's estimates via P1+P2 + web-research
measurements:

| Pillar | LOC estimate |
|---|---|
| 4 | ~800-1200 |
| 2 | ~700 |
| 3 | ~1050 |
| 5 | ~550 |
| 1 | ~1500-2500 |
| **Total** | ~4600-6000 |

### 10.3 Residual design uncertainties

Technical questions the design doesn't fully resolve. Each is a
known limit, not an adoption gate.

- **H3 recursion of trust.** Pillar 3's health checks are themselves
  projections validated against cohort baselines that could be
  biased. Acceptable bounded-depth limit (one level); no industry
  prior art fully resolves this.
- **Pillar 4 non-iid noise.** `1/√N` scaling is an upper bound on
  what 300q gets us; real hardware produces correlated timing
  noise. Median-of-N + bootstrap CI mitigates.
- **Pillar 5 whitelist breadth.** "Fields that must agree across
  processes" is empirically derived. Too narrow = misses bugs;
  too wide = false positives. Same empirical-discipline
  requirement as Pillar 2's allowlist.
- **Partial-implementation state.** A mid-sequence pause leaves
  some pillars landed and others not. Activity A's combined 2+3
  spike shows the pillars are designed to be landed in order,
  but each pillar should be fully landed before the next begins
  — no N-of-5 projections typed while the rest remain untyped.
- **D-2 sample size.** §15 designs Pillar 5 from one documented
  D-2 defect (tempdoc 400 §23.8 D-2). Confidence in the design
  would rise after a second D-2-class bug surfaces that isn't a
  path-resolution variant.

### 10.4 Interaction with tempdocs 401, 402, 403

- **401 (LR3-a external probes):** minimal interaction. Additive
  gauges flowing to `metrics.ndjson`. Under Pillar 2, each new
  gauge adds a schema entry (~5 LOC per gauge). No structural
  migration needed.
- **402 (Layer 6 tiers):** substantial overlap. `@BootContract` +
  `BootContractRunner.validateAll()` is proto-Pillar-2
  fail-loud-at-startup. `@SampleContract` emits `contract.violation`
  span events that Pillar 2's schema governs. `contract_violations.py`
  is a Pillar-3 candidate. 402's work can be restructured to build
  on Pillar 2 rather than reinventing the invariant-enforcement
  surface.
- **403 (observability retention):** interacts with Pillar 1's
  manifest model. Current 403 uses cohort-hash cascade; Pillar 1
  makes this simpler — delete manifest → sweep orphan shards by
  reachability (git-gc pattern). Same principles, different
  mechanism. 403 would be rewritten under Pillar 1 if Pillar 1
  ships.

---

## 12. References

- **Tempdoc 400 §23.9** — the post-followup validation pass that
  surfaced D-3 + the 10 theoretical improvements.
- **Tempdoc 400 §15** — prevention rules this design would
  strengthen (claim-vs-code drift, silent architectural
  duplication, metric-shape drift).
- **Tempdoc 400 §30** — Phase 6's 15 silent-failure-mode fixes;
  the empirical base case this design generalizes.
- **Tempdoc 401 / 402 / 403** — scoped follow-ups that operate in
  the current architecture; see §10.6 for interaction.
- **`docs/observations.md`** — D-3 logged as single-commit fix;
  this tempdoc references it as the alternative-to-adoption.
- **Architectural references** — git objects (Pillar 1);
  OpenTelemetry Semantic Conventions (Pillar 2); Dagster typed-
  asset graph (Pillar 3); CI cost-budget tooling (Pillar 4).
- **Tempdoc 405** — post-P1+P2 pre-implementation findings;
  supersedes §9-§11. Canonical current state. §12 has full
  web-research detail supporting Draft 3.
- **`docs/reference/observability-scoped-fix-playbook.md`** —
  the alternative-to-adoption path, promoted to first-class
  engineering discipline.
- **Draft 3 (web research) sources:** see 405 §12.10 — SQLite
  WAL, Git Internals, pnpm content-addressable store, Cargo
  lockfile, OpenTelemetry Semantic Conventions + Telemetry
  Schemas, Sakai 2018 topic-set-size-design, TREC Robust
  Track, robust benchmarking literature.

---

## 13. Post-P1+P2 addendum (Draft 2, 2026-04-22)

The original Draft 1 §11 recommended the following pre-
implementation work: retrospective defect audit, consumer
inventory, hash benchmark, emitter inventory, D-3 fix, Pillar 3
spike, schema sketch, adversarial review, reversibility analysis.
User authorized + executed. Full results in tempdoc 405; summary
of the deltas to Draft 1 below.

### 13.1 What changed

- **Hash-cost concern (§10.3) dissolved.** B.2 measured 2.1 GB/s
  SHA-256 throughput; hashing all run artifacts costs 1.4 ms =
  0.0008% wall-clock on a 3-min run. Pillar 1's runtime-cost
  concern is no longer a real constraint.
- **Scope estimates revised 40% smaller.** §10.1 estimated
  8-13k LOC end-to-end; B.1/B.3/§3.1/§3.2 measurements revise
  to 5-7k. Pillar 2 dropped from 2-4k to ~700 LOC.
- **H2 (Pillar 1 producer-crash) unresolved.** Adversarial review
  confirmed that rotation lifecycle events are written by the
  same process that rotates; a mid-rotation crash leaves shards
  orphaned with the manifest asserting completeness it doesn't
  have. Pillar 1's "structurally impossible to lose artifacts"
  claim is false. Pillar 1 confidence: 3/10 → 2/10.
- **H6 (Pillars 3+4 MVP insufficient) confirmed, then resolved.**
  The §3.1 Pillar 3 spike reproduced the silent-failure mode
  empirically (test `test_H6_undeclared_kind_enforcement_is_
  consumer_side_only`). Activity A (post-405) wired the Pillar 2
  schema into the Pillar 3 context; registration-time
  SchemaViolation + per-span coverage.schema_violations surface
  producer-consumer drift loudly. MVP sequencing revised from
  "Pillars 3+4 only" to "Pillars 4 → 2 → 3."
- **H5 (Pillar 4 noise floor) accepted.** Cost gate reliably
  catches only ≥25% regressions at 50q scale. Activity B (300q
  calibration) is in flight to measure whether production-scale
  query counts tighten the detection threshold.
- **Pillar 3 ergonomics validated.** §3.1 spike showed the
  decorator + typed context + runner pattern fits cleanly; all
  7 projections could take this shape.
- **Pillar 2 IDL investment not required for MVP.** §3.2 spike
  showed a single JSON file + thin Python + Java readers covers
  the full shape.

### 13.2 Revised per-pillar confidence (see 405 §6 for full detail)

| Pillar | Draft 1 | Draft 2 post-work | Net driver |
|---|---|---|---|
| 1 (run-as-manifest) | 3/10 | **2/10** | H2 unresolved; hash cost dissolved; net negative |
| 2 (schema-first) | 4/10 | **5/10** | §3.2 spike; IDL not required |
| 3 (typed transformations) | 6/10 | **6/10** | Ergonomics +1, H6 −1, Activity A +1; net +1 from start but rounding to 6 |
| 4 (cost as signal) | 6/10 | **4/10** (pending B) | H5 noise amplification |
| MVP (3+4 alone → 4→2→3) | 6/10 | **6/10** | Originally 6, dropped to 4 by H6, back to 6 post-Activity A |
| Full 4-pillar | 3-4/10 | **3/10** | H2 not fixable cheaply |

### 13.3 Revised sequencing

See 405 §5 for reversibility analysis + §9 above for the current
sequencing. Summary: **Pillars 4 → 2 → 3 → 5 → 1**. Rationale:

1. **Pillar 4 first** — extends existing calibrate; smallest
   code footprint; independent of others.
2. **Pillar 2 next** — unlocks Pillar 3's structural prevention
   (H6 resolution).
3. **Pillar 3 after** — every projection lands with schema-
   backed validation instead of string-literal drift hazards.
4. **Pillar 5 after Pillar 3** — reuses typed-projection base
   + schema infrastructure.
5. **Pillar 1 last** — most invasive (consumer migration);
   benefits from other pillars being stable before artifact
   layout changes ripple through them. The D-3 fix (the
   Pillar-1 MVP slice) already shipped as a scoped fix
   (commit `3ece672e1`).

### 13.4 What shipped as operational production code during P1+P2

Single commit: **`3ece672e1` — D-3 rotation-aware mirror fix**.
Closes §23.9.3's 77.1% span-loss defect via scoped patch in
`artifacts._mirror_telemetry`. 6 regression tests.

Plus **`4fc25509f`** — Activity A + C + D: combined Pillar 2+3
spike integration, scoped-fix engineering discipline playbook,
silent-failure engineering log.

### 13.5 Current-state summary

- Tempdoc 404 design: canonical architectural direction; §9
  sequencing + §10 confidence evaluation reflect current state.
- Tempdoc 405: pre-impl findings + web research; supersedes
  earlier confidence numbers.
- Tempdoc 404 §9 / §10 / §11: §11 removed in Draft 4; §9 + §10
  refreshed in Draft 4. Earlier Draft 2/3 banners pointing at
  405 are retained below for decision provenance.
- Scoped-fix playbook: engineering discipline at
  `docs/reference/observability-scoped-fix-playbook.md`.
- Silent-failure engineering log: running record in
  `docs/observations.md`.
- 405 Activity A resolves H6 (Pillars 2+3 combined spike).
- 405 Activity B (300q noise envelope): 4 attempts in session,
  none producing a clean σ measurement — attempts failed on
  operator error (commit mid-run), Worker JVM instability
  (`O-2`), and sandbox mount restrictions. Pillar 4's
  production-scale noise characterization remains pending on
  O-2 investigation + environmental stability.
- Activities E (counterfactual audit), F (H2 mitigation
  prototype), G (full schema write): not pursued in this
  session; technical work items, not blocking items.

---

## 14. Web-research addendum (Draft 3, 2026-04-22)

After §13's Draft 2 update, a deliberate web-research pass on the
unresolved adversarial findings showed that most of them are
already-solved problems in the industry. Summary below; full
detail in tempdoc 405 §12.

### 14.1 H2 (Pillar 1 producer-crash) — resolvable

**§3.1 claim "structurally impossible to lose artifacts" was
wrong.** A producer crash between shard-write and
manifest-journal-append leaves orphaned shards on disk. SQLite WAL
and Git's object model solve this exact problem at billions-of-
installs scale via the write order:

1. Write content-addressed shard → `fsync`.
2. Append manifest journal entry referencing the shard's hash.
   Journal is append-only with WAL semantics (fsync-on-commit;
   partial entries discarded by replay).
3. Orphaned shards are detected + reclaimed by a `git gc`-style
   maintenance pass.

**Revised Pillar 1 guarantee:** "orphaned shards are recoverable
garbage; the manifest never asserts completeness it doesn't
have." Weaker than the Draft 1 claim, but achievable + honest.

**Confidence effect:** Pillar 1: 2/10 → 4/10.

### 14.2 H5 (Pillar 4 noise floor) — theoretically resolvable

50q measured CV of `mean_ms` = 8.9%. Analytical `1/√N` scaling
(conservative under non-iid) predicts 300q CV ≈ **3.6%**; a 3σ
gate then catches `≥11%` regressions (vs 25% at 50q). Sakai 2018
"Topic Set Size Design for Paired and Unpaired Data" provides
formal N-queries-for-power formulas.

**Caveat:** timing measurements on real hardware are NOT iid
(thermal, scheduling, cache state → correlated noise). `1/√N` is
an upper bound. Pillar 4 should use **median-of-N + bootstrap CI**
rather than `mean ± k·σ` — standard mitigation in robust-
benchmarking literature.

**Operational caveat:** Activity B's double-failure (405 §11.2)
remains. The calibration pipeline is fragile independent of
statistics. O-2 Worker-instability investigation is a
prerequisite for real Pillar-4 data at production scale.

**Confidence effect:** Pillar 4: 3/10 → 5/10.

### 14.3 H7 (cargo-culting) — architectural references corrected

Desktop-scale references that ARE appropriate:

- **SQLite WAL** — single-user desktop database; Firefox, Chrome,
  mobile OSes; billions of installs at exactly our scale.
- **Git `.git/objects/` + refs** — content-addressed object store
  + atomic ref updates in single-user local systems.
- **Cargo / npm / pnpm lockfiles + content-addressed caches** —
  pnpm's `~/.pnpm-store/` hardlinks into `node_modules`. Exactly
  Pillar 1's pattern at desktop scale.
- **OpenTelemetry Semantic Conventions** (§14.4) — schema-first
  but at a different scope.

Superseded references (removed from §3.1 / §3.3): OpenLineage,
DVC artifact manifests, Apache Beam / Flink typed pipelines,
Dagster typed-asset graph. Those are multi-producer / multi-
consumer systems where lineage graphs and typed-transformation
graphs are the only tractable coordination mechanism. Our scale
has one producer (the JVM) and one consumer pipeline (jseval).

**Confidence effect:** no per-pillar number moves, but the
cargo-cult stigma from H7 is removed.

### 14.4 Pillar 2 — OTel SemConv refines the approach

OTel Telemetry Schemas use `schema_url` + consumer-side
transformation (rename attrs, drop deprecated fields, etc.) rather
than fail-loud emission validation. **OTel explicitly does not
validate required attrs at emission time.**

OTel's broader-scope-but-weaker-invariant approach is what you
need when producers and consumers are independent entities
(vendor A emits, vendor B consumes). Our scale (single producer,
single consumer, both controlled) allows a **stronger invariant**:

- Fail-loud at emission when required attrs missing.
- Fail-loud at registration when consumer declares unknown kind
  (Activity A proved this works).
- Single schema file both sides read; no URL indirection.

This is unprecedented-at-OTel-scale only because OTel's scope
forces weaker guarantees. At desktop scale, stronger invariants
are standard.

**Confidence effect:** Pillar 2: 6/10 → 7/10.

### 14.5 Revised confidence summary

| Pillar | §10 (start) | §13 (post A-D) | §14 (post research) |
|---|---|---|---|
| 1 (run-as-manifest) | 3/10 | 2/10 | **4/10** |
| 2 (schema-first) | 4/10 | 6/10 | **7/10** |
| 3 (typed transformations) | 6/10 | 7/10 | 7/10 |
| 4 (cost as signal) | 6/10 | 3/10 | **5/10** |
| MVP (Pillars 4 → 2 → 3) | 6/10 | 6/10 | **7/10** |
| Full 4-pillar | 3-4/10 | 3/10 | **5/10** |

### 14.6 Meta-lesson

"The confidence profile moved more in a few hours of web
research than in the earlier two passes (P1+P2 and Activities
A-D). Not because those were wasteful — they surfaced the
specific unknowns. But the unknowns remaining were precisely
the ones the industry had already solved."

**Checklist item for future confidence-raising work:**
"Have I checked industry prior art on the remaining unresolved
items?" This should be one of the FIRST pre-impl activities,
not one of the last.

### 14.7 What didn't change

- **H3** (recursion of trust in health checks) — no prior art
  solves this. Acceptable bounded-depth limit.
- **Partial-implementation technical concern** — mid-sequence
  pause leaves hybrid state. Each pillar should be fully landed
  before the next begins; no N-of-M partial pillar state.
- **O-2 Worker instability at 300q** — infrastructure question
  independent of design.

See 405 §12.7 for the full list.

---

## 15. D-2 class + Pillar 5 (Draft 4, 2026-04-22)

Research + design pass on the OS/process-boundary silent-failure
class (D-2). Delivered by autonomous subagent. Output of this
section is the canonical specification of Pillar 5 — the rest of
404 summarizes; this section is the detail.

### 15.1 Scope

Pillars 1-4 reason about the artifact graph (manifest, schema,
projections, cost). The D-2 defect class lives **outside that
graph**: inconsistencies in how two cooperating processes
*interpret* the same input.

**Canonical case** (tempdoc 400 §23.8 D-2): `jseval calibrate
--data-dir some/rel/path` passed a relative path. The Python
driver (cwd = `scripts/jseval`) and the Gradle-launched JVM Worker
(cwd = repo root) both "succeed," but resolve the relative path to
different absolute locations. `--clean` deletes path A; the JVM
writes to path B; run 2 inherits run 1's index and stalls. No
error, no mismatch visible to any of the four pillars because
both processes produced internally coherent manifests.

**Generalized D-2 failure modes:** path-separator handling,
shell-specific variable quoting, advisory-vs-mandatory file
locks, locale-dependent sort order in manifest hashing,
timezone-naive datetime parsing, Windows MAX_PATH (`\\?\` prefix
divergence), UNC paths with `subprocess.Popen(cwd=)` (Python
[bpo-15533](https://bugs.python.org/issue15533),
[bpo-20927](https://bugs.python.org/issue20927)).

### 15.2 Prior art survey

**Contract testing (Pact, Dredd, PactFlow).** Mature for
HTTP/message payloads; explicitly *not* for OS-level parameter
interpretation. [Pact](https://docs.pact.io/) contracts a response
shape; it does not contract "both sides resolve this relative
path the same way." Useful as a *pattern* to borrow, not a tool
to reuse.

**Hermetic builds (Bazel, Nix).** Solve D-2 by eliminating
ambient environment entirely. [Bazel hermeticity](https://bazel.build/basics/hermeticity)
sandboxes every action with symlinked inputs;
[Nix](https://nixos.org/) content-addresses dependencies. Both
require the entire build graph to live inside the sandbox;
retrofitting a cross-language dev-tool stack (Python subprocess
→ Gradle JVM → llama-server) is infeasible for our scope. *The
underlying idea — canonicalize early, forbid ambient
interpretation — is transferable.*

**Docker Compose `docker compose config`.** Renders the
**effective canonical configuration** after env-var substitution
and file merges, usable as an audit step before `up`.
[docker compose config docs](https://docs.docker.com/reference/cli/docker/compose/config/).
Cleanest small-scope precedent: one command, one canonical string,
agreement-or-divergence is trivially diffable.

**`.gitattributes` over `core.autocrlf`.**
[GitHub's CRLF guide](https://docs.github.com/en/get-started/git-basics/configuring-git-to-handle-line-endings)
explicitly recommends repo-level `.gitattributes` because
`core.autocrlf` is a per-machine setting and differing
interpretations silently corrupt shell scripts. *Pattern: make
the contract a committed artifact, not an ambient config.*

**Kubernetes Downward API.**
[Downward API](https://kubernetes.io/docs/concepts/workloads/pods/downward-api/)
injects pod metadata into containers so they see **the same
authoritative view** from the outside — the pod isn't asked to
introspect; it's told. *Pattern: caller resolves, callee
consumes a fixed string.*

**Process-level fingerprinting (Grafana Alloy `discovery.process`,
kernel audit).** [Alloy docs](https://grafana.com/docs/alloy/latest/reference/components/discovery/discovery.process/)
expose `__meta_process_cwd` from `/proc/<pid>/cwd`, plus exe,
commandline, uid. Linux auditd reconstructs absolute paths from
relative ones in
[RHEL audit records](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/8/html/security_hardening/auditing-the-system_security-hardening).
*Pattern: observability plane records the effective resolved
values for forensics.*

**Spring Boot / application frameworks** log the effective
resolved configuration at startup (profiles, property sources,
resolved paths). Low-ceremony precedent for "echo what you
resolved."

### 15.3 Applying findings to jseval + Worker JVM

Three layered mechanisms, cheapest first:

**(a) Boundary-canonicalization discipline (code pattern, not a
pillar).** Every parameter crossing a process boundary is
canonicalized to an absolute, OS-native, resolved form *before*
it crosses. For paths: `Path(p).resolve(strict=False)` on the
Python side, `java.nio.file.Path.toAbsolutePath().normalize()`
on the Java side, but **never both** — caller resolves, callee
consumes. Env vars crossing a boundary are explicitly
whitelisted in the subprocess spawn; ambient inheritance is the
leak. For the D-2 bug specifically: `calibrate.py:164` must
write `str(Path(data_dir).resolve())`, not `str(data_dir)`.

**(b) Effective-config emission (per-process, one JSON).** Each
process, at startup, emits one `effective_env.json` blob into
the run directory containing: resolved cwd, resolved data_dir,
OS, path separator, default encoding, locale, timezone, PID,
parent PID, the subset of env vars matching a declared
whitelist, and the hash of the argv it was launched with. Python
writes `effective_env.python.json`; the Worker JVM writes
`effective_env.java.json`. Modeled on `docker compose config` +
Spring Boot startup banner + auditd reconstruction.

**(c) Boundary-agreement check (differential test over the two
blobs).** A new projection (`boundary_agreement`, fits Pillar 3's
contract) reads both blobs and fails if declared-shared fields
disagree: `data_dir`, `models_dir`, `config_file`, timezone (if
either process parses timestamps), locale (if either sorts for
hashing). A schema (`boundary.v1.json`) declares which fields
must agree across which process pairs. The contract lives next
to the Pillar 2 schema registry.

This would have caught the §23.8 D-2 bug instantly:
`effective_env.python.json.data_dir = F:\JustSearch\scripts\jseval\some\rel\path`,
`effective_env.java.json.data_dir = F:\JustSearch\some\rel\path`
— `boundary_agreement` reports MISMATCH on field `data_dir`,
exit 1.

### 15.4 Pillar 5 or hygiene pattern?

**Adopted framing: Pillar 5 — Process-Boundary Contracts.**
Justified because:

1. It resolves a distinct defect class orthogonal to Pillars
   1-4. §5's symptom mapping cannot absorb D-2 without a
   category error.
2. It has the same shape as the other pillars: a declared
   schema (Pillar 2-style), emitted artifacts (Pillar 1-style),
   and a validating projection (Pillar 3-style). It **composes
   with** rather than duplicates the existing pillars.
3. The §23.8 pairing of D-1 (producer-consumer schema drift)
   and D-2 (process-boundary drift) is suggestive: the
   retrospective treated them as sibling bugs at "module
   boundaries," but pillars only covered one sibling.

**Counter-argument (honest):** Pillar 5 could instead be framed
as "hygiene pattern (a) + new projection under Pillar 3 +
schema under Pillar 2" — a *composite* of existing pillars, not
a fifth. Same implementation either way; the explicit Pillar 5
framing preserves the dimensional distinction between "inside
the artifact graph" and "at the edge of processes."

### 15.5 Confidence evaluation (6/10)

Drivers for the rating:

- **Strong:** prior art is consistent and the §23.8 D-2 example
  is a perfect test case for the design. Mechanism (b)+(c) would
  have caught it deterministically.
- **Moderate:** the whitelist of "fields that must agree" is
  the hard part. Too narrow = misses bugs (e.g., nobody
  declared `tmpdir` must agree, then a tempfile race emerges).
  Too wide = false positives on legitimate per-process values
  (`PID`, `cwd` itself). No principled way to derive the
  whitelist — must grow empirically, same failure mode as
  Pillar 2's attr allowlist.
- **Weak:** this is a **one-sample design**. I have one
  documented D-2 bug (plus the sibling §27.1 #13 `start_backend
  JUSTSEARCH_DATA_DIR` bug). The other examples in §15.1 (locale
  sort, MAX_PATH, CRLF) are hypothetical for JustSearch.
- **Unknown:** whether the Worker JVM can cleanly emit
  `effective_env.java.json` without coupling to
  app-observability's event plane. If it can reuse
  `NdjsonSpanExporter`, cheap. If it needs a new emitter, scope
  doubles.

Specific uncertainties that only implementation resolves:

- Does `JUSTSEARCH_MODELS_DIR` (worktree override) need to
  agree? Probably yes for eval runs, no for isolated subagent
  worktrees.
- Hash-comparison vs field-comparison: hashing the whole blob
  catches silent drift but obscures *which field* drifted.
  Field-comparison per schema is more surgical but more code.
- Does a Head-Worker gRPC boundary also need this, or only the
  jseval-backend launch boundary? The gRPC layer is typed and
  already has `commit.*` identity attrs (§23.1 LR2-d.2); likely
  redundant there.

### 15.6 Scope estimate

- Schema `boundary.v1.json` (~60 LOC declared fields + agreement
  rules).
- Python emitter `_effective_env.py` (~80 LOC): gather
  os/cwd/tz/locale/resolved-paths; write JSON.
- Java emitter in `app-observability` (~120 LOC): equivalent,
  plus integration into Worker startup.
- Projection `boundary_agreement.py` under `projections/`
  (~100 LOC + ~80 LOC tests).
- Wiring in `calibrate.py`, `run.py`, and Gradle `runHeadlessEval`
  task (~40 LOC).

**Total: ~400-500 LOC Python + ~150 LOC Java + tests.** Smaller
than any of Pillars 1-4. Sequencing-wise, lands cleanly **after
Pillar 2** (reuses schema registry) and **after Pillar 3**
(reuses projection base) — slot 4 in the §9 sequencing.

### 15.7 Positioning

Mechanism (a) is code-review discipline — a one-line fix at
`calibrate.py:164` plus a `.claude/rules/` note describing the
"caller resolves, callee consumes" principle. It can ship
independently of Pillar 5 proper.

Mechanisms (b)+(c) are the Pillar 5 surface — scoped as 404-5
in §9's sequencing. They compose on top of Pillar 2 (schema) +
Pillar 3 (projection base).

### 15.8 Sources (Pillar 5 research)

- [Docker Compose config](https://docs.docker.com/reference/cli/docker/compose/config/)
- [Docker Compose env_file consistency issue #8515](https://github.com/docker/compose/issues/8515)
- [GitHub line endings guide](https://docs.github.com/en/get-started/git-basics/configuring-git-to-handle-line-endings)
- [Bazel hermeticity](https://bazel.build/basics/hermeticity)
- [NixOS reproducible builds](https://reproducible.nixos.org/)
- [Kubernetes Downward API](https://kubernetes.io/docs/concepts/workloads/pods/downward-api/)
- [Grafana Alloy discovery.process](https://grafana.com/docs/alloy/latest/reference/components/discovery/discovery.process/)
- [RHEL auditd path reconstruction](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/8/html/security_hardening/auditing-the-system_security-hardening)
- [Python bpo-15533](https://bugs.python.org/issue15533)
- [Python bpo-20927](https://bugs.python.org/issue20927)
- [Pact](https://docs.pact.io/) +
  [CDC playbook](https://microsoft.github.io/code-with-engineering-playbook/automated-testing/cdc-testing/)
- [Pytest-cov subprocess coverage silent-ignore #282](https://github.com/pytest-dev/pytest-cov/issues/282)
