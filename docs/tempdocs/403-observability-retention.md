---
title: "403 — Observability artifact retention policy"
---

# 403 — Observability artifact retention policy

## 0. Status

**Scoped for design** (not yet implemented). Tempdoc 400 shipped five
long-lived storage surfaces — cohort baselines, manifest index,
eval-history DB, per-run artifacts, detailed tracing NDJSON — none of
which have automated lifecycle management today. This tempdoc scopes
the retention model; implementation lands as a follow-up.

**Non-triggers — preserved as anti-pattern discipline:**

- "More retention knobs" — every TTL must name its trigger + its
  trim target, not "general hygiene".
- "Auto-GC everything nightly" — overly-eager cleanup has destroyed
  calibration work before (Phase 3 §30.1 entry 14 recorded the
  `--clean` mis-scope). Every purge point must be explicit and the
  protected surface must be enumerated.
- "Cross-artifact cascade on every op" — cohort cleanup should
  cascade, but not on every `jseval run`. Named trigger points only.

**Created:** 2026-04-22.
**Depends on:** tempdoc 400 §23.6 + Agent-3 resource-growth audit.

---

## 1. Context

Tempdoc 400's §16 "What this does NOT solve" called out "Storage
cost of per-query span trees" — but the issue generalizes to every
observability artifact the tempdoc introduced. A concrete inventory:

| Artifact | Path | Growth driver | Bound today |
|---|---|---|---|
| Cohort baselines | `<data_dir>/cohort_baselines/<hash>/{envelope,span_distributions}.json` | one sub-directory per observed cohort | none |
| Manifest index | `<data_dir>/eval-results/_index/manifests.jsonl` | one line per `jseval run` | none |
| Runs table | `eval-history.db::runs` | one row per `jseval run` | none |
| Envelope metrics | `eval-history.db::envelope_metrics` | cascades from `runs` (FK ON DELETE CASCADE — Phase 6/6.11) | follows `runs` |
| Run dirs | `<data_dir>/eval-results/<ts>_<dataset>/` with summary.json + per_query.json + qrels.json + projections/*.json + mirrored telemetry | one dir per `jseval run` | none |
| traces.ndjson | `<data_dir>/telemetry/traces.ndjson` | per-span append | 10 MB / file + 7-day retention (existing) |
| metrics.ndjson | `<data_dir>/telemetry/metrics.ndjson` | per-tick append | 10 MB / file + 7-day retention (existing) |

Validation evidence from §23.8 live smoke: a 15-query scifact run
with `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` produced ~7,400 spans
and ~2.4 MB of traces.ndjson. Extrapolating naively to a 300-query
production eval: ~150K spans → ~48 MB per run. The 10 MB rotation
holds for individual files but does NOT bound total per-run artifact
cost.

---

## 2. Design principles

1. **Protect calibration state over transient state.** Envelope +
   drift baseline files survive operations that wipe index / queue /
   telemetry. This is the precedent set by Phase 3 / 30.1 entry 14.
2. **TTL-based pruning at named trigger points.** Never wipe on
   every op; run cleanup on well-defined operator actions or
   scheduled jobs.
3. **Cohort-centric cascade.** When a cohort is retired (either via
   explicit operator action or absence from recent N runs), its
   baseline + its run_dirs + its `envelope_metrics` rows all age
   together. Cross-artifact lifecycle, not per-artifact TTL.
4. **Detailed-tracing overflow mitigation.** The default 10 MB / 7-day
   rotation is fine for `sample` (1% ratio) but is projected to be
   overwhelmed under `detailed` — tighter rotation when tracing is
   `detailed`.
5. **Explicit operator override.** `jseval prune --dry-run` and
   `jseval prune --cohort <hash>` must exist. Cron-style background
   GC is a nice-to-have but the explicit command is load-bearing
   for operator trust.

---

## 3. Proposed retention model

### 3.1 Default TTLs

| Artifact | Default TTL | Rationale |
|---|---|---|
| Cohort baselines (`envelope.json` + `span_distributions.json`) | **90 days** | Matches §13 C4's "orphaned envelopes GC-eligible after 90 days." Long enough to survive a quarterly code-change cycle; short enough that stale baselines don't accumulate unboundedly. |
| `runs` table rows | **180 days** | Twice the baseline TTL so cross-cohort trend analysis has history to work with. Envelope metrics cascade per the existing FK. |
| Manifest index (`manifests.jsonl`) | **180 days** | Matches `runs` — the index is a mirror of the runs table. Trimming older than 180d requires rewriting the NDJSON file (not append-friendly); acceptable cost for a nightly job. |
| Run dirs | **30 days** | Matches common CI artifact retention (30-day GitHub Actions default). Operators wanting longer retention on a specific run should copy it out; the default is "recent runs are debuggable, old runs are summaries-only." |
| `traces.ndjson` (current `sample`) | unchanged (10 MB / 7 days) | existing policy works |
| `traces.ndjson` (when `detailed`) | **1 MB / 24 hours** | Tighter rotation under detailed tracing; keeps a single debug session's output inspectable without accumulating across days. |
| `metrics.ndjson` | unchanged (10 MB / 7 days) | metric volume is much lower than spans |

None of these are hardcoded — each should be overridable via
environment variable so CI and one-off operator workflows can tune
independently.

### 3.2 Cohort-centric cascade

When a cohort is retired (either TTL-triggered or explicit
`jseval prune --cohort <hash>`):

1. Delete `<data_dir>/cohort_baselines/<hash>/` (entire directory).
2. Delete every `runs` row where `manifest_hash = <hash>`
   (`envelope_metrics` cascades via FK ON DELETE CASCADE).
3. Delete every `<data_dir>/eval-results/<ts>_<dataset>/` where
   `manifest.manifest_hash == <hash>`.
4. Rewrite `manifests.jsonl` omitting lines matching the cohort.

No partial deletes. If any step fails (disk error, permission), the
whole prune aborts and nothing is deleted — the data dir is left in
its pre-prune state.

### 3.3 Detailed-tracing overflow mitigation

Read `JUSTSEARCH_INDEX_TRACING_LEVEL` at `NdjsonSpanExporter`
construction; when `detailed`:

- `rotateMaxBytes` = 1 MB (was 10 MB)
- `retentionDays` = 1 (was 7)

Operators running a targeted debug session inspect the single most
recent `.ndjson` file; old ones age out by the next day. This
prevents a long-running dev session from silently accumulating
gigabytes of traces.

Overrides: existing `JUSTSEARCH_TELEMETRY_TRACES_MAX_MB` +
`JUSTSEARCH_TELEMETRY_TRACES_RETENTION_DAYS` win if set. The
detailed-mode defaults kick in only when these env vars are unset.

### 3.4 Trigger points

| Trigger | Purpose | Scope |
|---|---|---|
| `jseval prune` subcommand (explicit) | Manual operator control | Full cohort cascade by `--cohort` or TTL-based sweep by `--older-than-days N` |
| Nightly observability workflow | Scheduled GC | TTL sweep over all cohorts + run dirs; dry-run-friendly output |
| `jseval run` end-of-run | **Never.** No per-op cleanup. | The `--clean` flag already has a well-defined scope (transient state only); do not expand |
| Worker startup | **Never.** No lifecycle coupling. | Backend never deletes cohort-identity artifacts |

The "never" rows are as important as the "every" rows: they
explicitly exclude the classes of coupling that have caused bugs
(Phase 3 / 30.1 entry 14's `--clean` mis-scope).

---

## 4. CLI + env var surface

### 4.1 `jseval prune`

New subcommand. Implementation in `scripts/jseval/jseval/prune.py`.

```
Usage: jseval prune [OPTIONS]

Options:
  --data-dir PATH            Required. Root data dir.
  --cohort TEXT              Prune this cohort (and its cascade) only.
  --older-than-days INTEGER  TTL-sweep cohorts not referenced in the
                             last N days of runs.
  --dry-run                  Print the plan without deleting.
  --force                    Skip the interactive confirmation.
```

`--cohort` + `--older-than-days` are mutually exclusive; one is
required.

### 4.2 Env vars

| New var | Purpose |
|---|---|
| `JUSTSEARCH_RETENTION_COHORT_DAYS` | Cohort TTL override (default `90`). |
| `JUSTSEARCH_RETENTION_RUNS_DAYS` | `runs` row TTL override (default `180`). |
| `JUSTSEARCH_RETENTION_RUN_DIRS_DAYS` | Run-dir TTL override (default `30`). |

These complement the existing `JUSTSEARCH_TELEMETRY_TRACES_MAX_MB`
and `JUSTSEARCH_TELEMETRY_TRACES_RETENTION_DAYS`.

### 4.3 Nightly workflow extension

`.github/workflows/phase-3-observability-nightly.yml` gains a
post-calibration step:

```yaml
- name: Prune stale cohorts (dry-run)
  run: python -m jseval prune --data-dir ... --older-than-days 90 --dry-run
```

Dry-run only; actual deletion requires an explicit operator
invocation. The nightly surfaces the candidate list so operators
see what would be pruned without risking accidental deletion.

---

## 5. Implementation sequencing

Sketch; full sequence lands when this tempdoc graduates to
implementation.

| Phase | Delta | Gate |
|---|---|---|
| P1 | `jseval prune` subcommand — dry-run-only, no deletes | CLI works; prints candidate cohorts |
| P2 | Actual deletion with full cohort cascade | unit tests on synthetic cohorts; 1 e2e smoke |
| P3 | Detailed-tracing overflow mitigation (exporter-side) | `:modules:telemetry:test` green; size stays bounded on 10-min detailed-tracing soak |
| P4 | Nightly workflow dry-run step | workflow YAML parses + first manual trigger |
| P5 | `JUSTSEARCH_RETENTION_*` env vars + doc updates | env-vars.md + 08-observability.md updated |

**Estimated scope:** ~400-600 LOC Python (`prune.py` + tests),
~50 LOC Java (`NdjsonSpanExporter` detailed-mode defaults + test),
~30 LOC workflow YAML, ~100 LOC canonical doc updates.

---

## 6. Verification gates

- **Dry-run fidelity:** `jseval prune --dry-run` must print the full
  deletion plan (cohort hashes, run dirs, row counts) without
  touching disk. A unit test seeds a synthetic data dir and
  verifies the plan matches the expected state.
- **Cascade atomicity:** injected mid-cascade failure (e.g. write-
  protected file) rolls back cleanly; nothing is deleted.
- **Never-delete axes verified:** explicit test that
  `jseval run --clean` does NOT invoke prune (uses §2.2 protected
  surface list from tempdoc 400).
- **Detailed-tracing rotation active:** 10-minute soak with
  `JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` produces < 5 MB of
  `traces.ndjson` after rotation sweeps (not 100 MB).
- **Env-var overrides:** unit test asserts
  `JUSTSEARCH_RETENTION_COHORT_DAYS=10` produces a narrower sweep
  than the default.

---

## 7. Non-goals (explicit)

- Not a general-purpose file-retention framework. Scope is strictly
  observability artifacts introduced by tempdoc 400.
- Not a replacement for `--clean` — `--clean` already targets
  transient index/queue/telemetry state. This tempdoc is about the
  long-lived calibration + run-history state.
- Not an ADR. Storage lifecycle is a feature-level concern; an
  ADR can capture it later if it proves cross-cutting.
- Not cross-machine replication / archival. Operators who need
  long-term retention copy artifacts out manually — the prune
  policy does not replace a backup strategy.

---

## 8. References

- **Tempdoc 400 §16** — the "storage cost of per-query span trees"
  non-goal that this tempdoc addresses.
- **Tempdoc 400 §23.6** — retrospective gap that flagged retention
  as a class of follow-up work.
- **Tempdoc 400 Phase 3 / 30.1 entry 14** — `--clean` preservation
  fix + the precedent for cohort-baseline protection.
- **Tempdoc 400 §13 C4** — original 90-day orphan-envelope GC
  rationale.
- **Tempdoc 400 §26.6 Decision 2** — cohort baselines facet
  registry layout that this tempdoc manages lifecycle for.
- **`modules/telemetry/src/main/java/io/justsearch/telemetry/
  NdjsonSpanExporter.java`** L111-121 — existing rotation defaults
  this tempdoc tightens under detailed tracing.
- **`scripts/jseval/jseval/backend.py::start_backend`** — the
  `--clean` implementation with its protected-directory enumeration;
  precedent for the "protect calibration over transient" principle.
- **`scripts/jseval/jseval/history.py`** — `envelope_metrics` FK
  cascade relied on by §3.2.
- **`docs/how-to/envelope-staleness-policy.md`** — complementary
  operator doc for rotation triggers (this tempdoc handles the
  bulk-cleanup side; the how-to handles the per-cohort rotation
  side).
