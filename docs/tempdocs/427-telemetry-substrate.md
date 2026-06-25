---
title: "Telemetry Substrate (catalog-driven worker→head metric replication)"
type: tempdocs
status: open
created: 2026-05-05
gated_on: "421 frontend framework kernel implementation completion"
---

# 427 — Telemetry Substrate

## Status

**PROPOSED — review after 421 ships.** Forward-looking design surfaced
by slice 3a.1.4b's live-validation pass — the most architecturally
significant of the three findings, because it exposed a methodology
gap in the slice's own investigation pass (declarations were checked;
runtime consumers were not).

Sibling tempdocs from the same root-cause analysis:

- **425** — Bootstrap Substrate (declare-not-side-effect boot phase).
- **426** — Transport Substrate (single canonical apiClient).

All three derive from the same anti-pattern: **declaration drift from
implementation**.

## Origin

Slice 3a.1.4b §B.I Finding 3 — the most concerning:

> The `worker.documents.indexed.rate_per_sec` metric is declared as
> `archivedTo(RrdArchive.STANDARD)` in `WorkerOpsMetricCatalog` but
> is **not** present in the head's `RrdMetricStore`. The
> `/api/debug/metrics/timeseries?metric=worker.documents.indexed.rate_per_sec`
> endpoint returns NOT_FOUND, while `worker.job_queue.depth` returns
> proper data. So the worker→head replication pipeline doesn't include
> this specific metric — even though the canonical
> `worker.job_queue.depth` replicates correctly via the same mechanism.

Logged to `docs/observations.md` Inbox by the slice; flagged as the
methodology gap that the slice's investigation pass overlooked: my
~6-minute investigation grepped for `archivedTo(RrdArchive.STANDARD)`
declarations and concluded "the data substrate exists" — raising
confidence to ~85%. The conclusion was technically true but
operationally false: declaration ≠ implementation. A grep for the
runtime consumer would have caught the gap in minutes.

## Problem (today's shape)

The metric catalog (`WorkerOpsMetricCatalog`, `HeadGpuMetricCatalog`)
declares which metrics are archived to the standard RRD via
`MetricDefinition.gauge(NAME).archivedTo(RrdArchive.STANDARD)`. Both
`worker.job_queue.depth` and `worker.documents.indexed.rate_per_sec`
are declared identically.

But only `worker.job_queue.depth` actually appears in the head's
RrdMetricStore. Something else — an allow-list, a scrape config, an
explicit registration site — gates the **actual** worker→head
replication. The catalog's `archivedTo` declaration is *intent*, not
implementation.

This is the highest-impact form of **declaration drift from
implementation**: the catalog says "this metric is archived" but
nothing in the codebase guarantees that the runtime honors that
declaration. Two lists describe the same thing — the metric catalog
and the (currently undocumented) replication wiring — and they have
already drifted on at least one metric.

For slice 3a.1.4b: 3 of 4 cohort TIMESERIES Resources fully serve
live data; the 4th (`docs-indexed-rate`) permanently 503s in dev
because of this gap. The substrate's graceful-degradation path works
correctly (canonical 503 envelope), but the operational outcome is
that the cohort closure is structurally complete and operationally
3-of-4.

## Correct design (theoretical)

The metric catalog is the **single source of truth** for what gets
archived. The runtime *derives* the replication wiring from the
catalog. There is no second list.

Specifically:

1. **Metric catalogs are wire contract.** They live in a shared
   package (`app-api` or whatever the canonical wire-contract home
   becomes per slice 3a.1.8). Both worker and head import the same
   catalog package. The worker registers suppliers against it; the
   head registers archivers against it.

2. **`archivedTo(...)` is transitive.** The declaration means "this
   metric's samples MUST land in the named archive, wherever that
   archive lives." The runtime is responsible for routing: if the
   catalog is on the worker and the standard RRD is on the head, the
   runtime ships samples worker→head; if both are co-located, no
   transport is needed.

3. **Replication transport reads catalogs at startup.** At capability-
   handshake time (or equivalent), the head reads the worker's catalog
   advertisement and configures its RRD adapter to record exactly the
   metrics the catalog declares as `archivedTo(STANDARD)`. No
   per-metric wiring on the head side; the catalog drives everything.

4. **Sampling cadence and retention are catalog properties.** The
   catalog declares "30s sample interval, 30-min window, RRD STANDARD
   archive"; the runtime configures the sampling timer, the wire
   schedule, and the RRD policy from those declarations.

```java
// Single source of truth — shared package
public interface MetricCatalog {
  List<MetricDefinition> definitions(); // includes archivedTo, sampling, etc.
}

// Worker side
class WorkerTelemetryRuntime {
  void registerSuppliers(MetricCatalog catalog, SupplierMap suppliers) { ... }
  void start() { /* schedules sampling per catalog */ }
}

// Head side
class HeadTelemetryRuntime {
  void registerArchivers(MetricCatalog workerCatalog) {
    for (MetricDefinition def : workerCatalog.definitions()) {
      if (def.isArchived()) {
        rrdStore.register(def);  // auto-derived from catalog
      }
    }
  }
}
```

What this prevents:

- The exact trap that surfaced in 3a.1.4b: "I declared
  `archivedTo(STANDARD)`, but the head doesn't have it." Declaration
  *is* wiring.
- The "two sources of truth disagree" defect class — disappears by
  construction for this domain.
- Future agents investigating "is metric X recorded?" don't need to
  grep two separate lists; they grep the catalog and get a complete
  answer.
- Adding a new metric becomes a one-line catalog edit; the head
  picks it up automatically at next startup.

Patterns in the wild: OpenTelemetry's view + exporter model (the view
is the single source; the exporter handles transport, but the view
declares completely). Prometheus' service discovery → scrape config
(services register; the scraper auto-derives). Shape: declare *what*;
runtime handles *how*.

## Why this matters long-term

This is the most important of the three substrates because:

1. **It enables a methodology improvement.** The §A.0
   source-anchored-extraction lesson (and the §B.K self-grading
   blind-spot lesson) point at the same thing: agents trust
   declarations as a proxy for implementation. Making declarations
   *equal* implementation removes the gap that the methodology
   discipline tries to cover. Process improvements help; structural
   changes prevent.

2. **It composes with 3a.1.8 (wire contract architecture).** The
   parallel agent's wire-contract work establishes a shared-contract
   home. Telemetry catalogs belong in that home — they're wire
   contract by another name (the worker advertises capabilities; the
   head consumes them). This tempdoc and 3a.1.8 are mutually
   reinforcing: each makes the other simpler.

3. **It scales to other observability concerns.** The same shape
   ("catalog declares; runtime auto-derives") generalizes to log
   sources, trace sampling decisions, alerting rules. Each of those
   currently has the same risk class — declarations that may or may
   not have runtime correspondents.

## Concrete migration shape (theoretical)

1. Promote `MetricCatalog` to the shared wire-contract package.
2. Add a `RrdArchiveBootstrap` (or similar) that reads catalogs at
   head startup and registers archivers for every `isArchived()`
   metric.
3. Remove (or assert against) any standalone "metrics the head
   replicates" list that exists today — the catalog *is* the list.
4. Migrate existing replication call sites to the catalog-driven path
   incrementally; the canonical `worker.job_queue.depth` is already
   compatible (it's declared in `WorkerOpsMetricCatalog`).
5. Add a contract test: every catalog metric with `archivedTo(STANDARD)`
   has a corresponding registered archiver after head startup. Drift
   becomes a test failure.

## Methodology meaning

The §A.0 source-anchored-extraction methodology lesson exists at the
spec layer: don't trust documentation as a proxy for code. This
finding shows the same lesson at the operational layer: don't trust
declarations as a proxy for runtime behavior. The substrate makes the
methodology unnecessary in this domain — declarations *are* runtime
behavior. The methodology's existence is itself a sign the substrate
was missing.

A stronger investigation discipline (codified in
`docs/reference/contributing/slice-execution.md`):

> For each load-bearing claim of "X exists" or "X works," produce
> *both* the declaration site *and* the runtime consumer site. If
> the runtime consumer can't be found via grep in ~5 minutes, the
> claim is **unverified** — confidence drops, not rises.

This is the operational analog of `§A.0 source-anchored-extraction`:
anchor on the wire / runtime path, not on the declared intent. The
slice 3a.1.4b investigation passed the bar for "decreased substrate
uncertainty" but failed the bar for "verified end-to-end runtime
path." The 30-point confidence jump (~55% → ~85%) was overcalibrated
for the declaration-only evidence. After this substrate ships, the
discipline becomes redundant for the metric domain.

## Out of scope (this tempdoc)

- The transport mechanism for shipping the worker's catalog
  advertisement to the head (gRPC handshake / capability handshake /
  static manifest sharing — choose one, the substrate's externally
  visible contract is the same regardless).
- Backwards-compatibility migration window for the replication-list
  → catalog-driven transition.
- Implementation of the kernel itself (deferred until 421 ships and
  3a.1.8 wire-contract-architecture lands).
- Generalization to logs / traces / alerting (separate substrate
  proposals).

## Alignment with 3a.1.8d (catalog-consolidation)

**Reconnaissance update (2026-05-06):** the parallel agent's wire-
contract cohort already includes
`slices/3a-1-8d-catalog-consolidation.md` — a substrate-extension
slice for "scattered identifier catalogs" (reason codes, operation
ids, severity, health-event ids, resource/prompt ids, i18n keys).
The metric catalog is **conspicuously absent** from 3a.1.8d's scope,
which the contract-substrate kernel doc
(`10-kernel/05-contract-substrate.md` axis 7) catalogs as the
authoritative list. So this tempdoc remains non-redundant.

But the *shape* fits. A metric catalog is structurally equivalent
to 3a.1.8d's other catalog Categories: a closed set of identifiers,
each carrying metadata (unit, sample interval, archive policy,
sampling supplier hint). The right framing is therefore:

- **Catalog-membership half** (declaration): metric catalogs become
  a **sixth catalog Category** in the 3a.1.8d substrate. The
  contract-substrate machinery handles spec→Java→TS→Zod projection
  uniformly. No new substrate primitive needed for the catalog axis.
- **Runtime-replication half** (wiring): the worker→head replication
  pipeline reads the catalog-Category at handshake time and
  auto-derives sampling + transport + recording. This is the genuine
  novel substrate (not covered by 3a.1.8d) — it's the runtime
  *consumer* of the catalog declaration.

Reframed concretely: tempdoc 427 is **3a.1.8d-extension + telemetry-
runtime-kernel**, not a parallel parallel substrate. The catalog
extension is mechanical follow-on once 3a.1.8d ships. The telemetry-
runtime-kernel is the genuinely new work.

This reframing collapses some of the proposed substrate into existing
421 scope — improving scope efficiency without weakening the
recommendation.

## Gating

**Review after 421 frontend-framework-kernel implementation completes**
AND **after `slices/3a-1-8d-catalog-consolidation.md` ships** — because
metric catalogs naturally land as 3a.1.8d's sixth catalog Category.
Reviewing this tempdoc before 3a.1.8d lands risks proposing a home
that 3a.1.8d supersedes; reviewing before 421 ships risks reshape from
later 421 work.

## See also

- Sibling tempdocs **425** (bootstrap) and **426** (transport).
- Origin: slice 3a.1.4b `slices/3a-1-4b-timeseries-cohort-followup.md`
  §B.I Finding 3 + observations.md Inbox entry.
- Wire-contract foundation:
  `docs/decisions/0039-contract-substrate.md`
  (parallel agent's slice).
- Methodology lesson:
  `docs/decisions/0036-fe-resource-category.md`
  §B.K (single-agent self-grading blind spot); §A.0
  source-anchored-extraction.
- Existing producer pattern that this substrate would generalize:
  `modules/app-services/.../JobQueueDepthMetricProducer.java`,
  `modules/app-observability/.../metrics/JobQueueDepthMetricResourceCatalog.java`.
