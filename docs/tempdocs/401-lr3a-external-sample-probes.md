---
title: "401 — LR3-a external-world sample probes (Windows FFM surface)"
---

# 401 — LR3-a external-world sample probes (Windows FFM surface)

## 0. Status

**Investigation / problem statement only.** This tempdoc captures the
background and scope of the LR3-a deferral that tempdoc 400 carried
through Phases 1-6. No design, no fix plan, no commit sequence yet —
those come in a follow-up draft once the scope is accepted as worth
pursuing.

**Created:** 2026-04-22
**Owner:** Unclaimed
**Depends on:** tempdoc 400 (closed), ADR 0005 (manual FFM policy).

---

## 1. Context

Tempdoc 400 ("Pipeline observability: deltas on the existing telemetry
stack") landed six phases across Layers 1, 2, 4, 5, and 6 of its
delta architecture. Layer 3 is the "sample stream" layer — per-tick
numeric gauges aggregated by `RrdMetricStore` / `LocalTelemetry` and
exported via `NdjsonMetricExporter`.

Layer 3 had three deltas:

- **LR3-a** — external-world sample sources (CPU freq, GPU temp,
  disk I/O throughput, memory pressure).
- **LR3-b** — jseval consumes `metrics.ndjson` + `metrics-worker.ndjson`.
- **LR3-c** — sample-tag allowlist.

**LR3-b + LR3-c shipped** in tempdoc 400 Phase 1 (commit `8341dba9e`).
**LR3-a deferred** and remained deferred across every subsequent
phase. The deferral is documented in three places inside tempdoc 400:

- Design file §8.3 `LR3-a External-world sampler sources`
  (original spec).
- Design file §21.2 "Deferrals with rationale" (the Phase-1-landing
  rationale for why this bundle slipped).
- Design file §22.3 "Issue C — LR3-a scope granularity" (the
  post-implementation reassessment that identified why the original
  one-commit plan was unrealistic).

Tempdoc 400's §22.3 closes with: *"Each [sub-item] has its own
trigger and estimated scope. LR3-a as a unit is multi-week work, not
the single-commit §14 plan implied ... rethinking granularity
matters when the follow-up plan is written."* This tempdoc is that
follow-up's starting point.

---

## 2. What LR3-a was originally meant to deliver

From tempdoc 400 §8.3:

> Add CPU frequency (per-core via Windows PDH), GPU temperature (NVML
> — already present), disk I/O throughput (`Files.getFileStore()`
> patterns), memory pressure indicators to `LocalTelemetry` gauge
> registration. Gated behind env var (default off until Windows probe
> surface is validated).

The motivation: local-first desktop deployments can be bottlenecked
by external resources (thermal throttling, disk saturation, memory
pressure) that the existing JVM-internal gauges in `RrdMetricStore`
don't see. When a user complains "search is slow tonight," operators
today have no sample stream showing whether the GPU was at 87°C with
the disk queue backed up. LR3-a fills that gap.

The env-var gating (`JUSTSEARCH_EXTERNAL_SAMPLES`, design §8.7 H2)
kept the probes opt-in until the Windows bindings stabilized.

---

## 3. Why Phase 1 deferred it

Tempdoc 400 §21.2 records the deferral rationale:

> Design-time estimate underestimated the work. `NvmlService.probe()`
> is init+query+shutdown per call; a gauge needs long-lived lifecycle.
> `GpuCapabilities.Nvml` record extension. Register-with-poll-cadence
> infrastructure. CPU freq + memory pressure each require new Windows
> PDH / Win32 FFM binding surfaces per ADR 0005. Realistic effort:
> multi-day, not single-commit.

The pre-implementation confidence reassessment in §21.4 moved LR3-a
from 7/10 (per D3) down to 4/10 once the implementation shape was
clear. The single most important realization was that the probes
don't share a template — they share a **missing infrastructure**.

---

## 4. The five sub-items identified in §22.3

Tempdoc 400 §22.3 split LR3-a into a dependency-ordered set. For
reference — this is NOT a commit plan, just the scope inventory:

| Sub-item | Scope | Template / binding required |
|---|---|---|
| **LR3-a.0** | Periodic gauge registration infrastructure inside `LocalTelemetry` / `RrdMetricStore`. Precondition for LR3-a.1..5 + any future periodic-probe. | None existing. Covers: registration API, poll cadence wiring, shutdown semantics, error-isolation per gauge. |
| **LR3-a.1** | Extend `NvmlService` for long-lived lifecycle (init once, query N times, shutdown on process exit) rather than the current per-call init→query→shutdown. | `NvmlService` + `GpuCapabilities.Nvml` record. |
| **LR3-a.2** | GPU temperature gauge. | Uses LR3-a.0 + LR3-a.1. NVML already exposes the call (`nvmlDeviceGetTemperature`). |
| **LR3-a.3** | Disk I/O throughput. Either new FFM binding to Windows PDH disk counters OR a delta loop over `Files.getFileStore().getUsableSpace()` — decision deferred pending spike. | Depends on direction: FFM or Java NIO. |
| **LR3-a.4** | CPU frequency (per-core). New Windows PDH FFM binding. | New — no template in the codebase. |
| **LR3-a.5** | Memory pressure. New Win32 `GetPerformanceInfo` FFM binding. | New — no template in the codebase. |

Sub-items LR3-a.0 and LR3-a.1 are **infrastructure preconditions**.
LR3-a.2 through LR3-a.5 are the four operator-visible probes. Each
probe has its own effort estimate; no single one is
single-commit-sized once LR3-a.0 and its dependencies are paid.

---

## 5. Constraints inherited from tempdoc 400 / ADR 0005

Any future work on LR3-a must honour:

- **ADR 0005 "Manual FFM only".** No JNA, no JNI, no third-party
  binding libraries. All new bindings go through `java.lang.foreign`
  (`Arena`, `FunctionDescriptor`, `Linker`, `SymbolLookup`,
  `ValueLayout`) following the `NvmlService` + `WindowsJobObject` +
  `MmfWorkerSignalBus` templates.
- **Module placement.** Tempdoc 400 §13 D3 suggested new bindings
  stay in `modules/gpu-bridge` or a new `modules/os-probe` — not
  scattered across `app-util` / `indexer-worker` / `app-inference`.
  This preserves the bounded "FFM surface area" envelope that
  ADR 0005 justifies.
- **Env-var gating.** Per tempdoc 400 §8.7 H2, the bundle is gated
  behind `JUSTSEARCH_EXTERNAL_SAMPLES` (default off) until the
  Windows probes are validated end-to-end on the reference
  hardware. No probe lights up without operator opt-in.
- **Sample-stream allowlist (LR3-c).** Any new gauge must be added
  to the allowlist that LR3-c shipped in Phase 1 — otherwise
  `NdjsonMetricExporter` drops the samples. This is additive and
  cheap but must not be forgotten.
- **Platform portability.** Windows-first per JustSearch's local-
  first target (tempdoc 400 §16). Non-Windows builds must degrade
  gracefully — probe registration no-ops, gauges report absent
  rather than erroring. The existing `NvmlService` already models
  this shape.
- **`RrdMetricStore` limits.** `CURATED_METRICS` in
  `RrdMetricStore` has `STEP_SECONDS = 60`, `HEARTBEAT_SECONDS =
  180`, and datasource names must be < 20 characters (tempdoc 400
  §13 A4). Any new gauge inherits these limits.

---

## 6. Open questions (input for the eventual design draft)

Not resolved by this tempdoc. Listed so the follow-up plan knows
what to investigate:

1. **Disk I/O: PDH or `Files`?** Tempdoc 400 §13 D3 flagged disk I/O
   throughput as "6/10 confidence, spike needed." A PDH binding is
   heavier but gives raw counters (bytes/sec read/write); a `Files`
   delta loop is lighter but only approximates via `getUsableSpace()`.
   The call depends on the backing analytical question — is anyone
   going to alert on disk throughput, or just look at it in a
   post-mortem?
2. **Per-core vs aggregate CPU frequency.** Tempdoc 400 suggested
   per-core; that's N gauges for an N-core machine (laptop: 8-16;
   workstation: 24+). Aggregate (mean / min / max) would be three
   gauges and fits the sample-stream model better. Per-core data
   may belong in a trace-time event rather than a gauge.
3. **Periodic gauge cadence.** `RrdMetricStore` has `STEP_SECONDS =
   60`. Are thermal / memory-pressure changes visible at 60s
   resolution, or does this need a separate faster lane? A new lane
   adds scope to LR3-a.0.
4. **Multi-GPU handling.** `NvmlService.probe()` today only reports
   GPU 0. LR3-a.2's gauge needs a decision: one gauge-per-GPU with
   dynamic registration, or single-GPU-only until multi-GPU becomes
   relevant?
5. **Consumer fit.** Which tempdoc 400 Layer-4 / Layer-5 consumer
   actually benefits from these samples today? The original design
   assumed operator eyeballs; no projection in `scripts/jseval/
   jseval/projections/` consumes disk-throughput or CPU-frequency
   samples. If no named consumer exists, is this premature — or is
   the investment justified as "we'll wish we had them in six
   months when GPU thermals degrade"?

---

## 7. Non-triggers — preserved as anti-pattern discipline

Per tempdoc 400's own non-trigger list (§0), the following are
explicitly NOT motivations for this follow-up:

- "More observability would be nice." Every probe proposed here
  should tie back to a specific analytical question operators
  actually ask.
- "Dashboards would look cool." LR3-a samples feed the existing
  `metrics.ndjson` + jseval consumers; no new dashboard surface
  is implied.
- "Every external source a gauge." GPU temperature is cheap and
  locally useful; a full OS-level telemetry suite is scope creep.

---

## 8. Scope of this tempdoc

**In scope:**

- Problem statement (this document).
- Dependency chain from §22.3 Issue C (Layer-4 of this tempdoc).
- Inherited constraints from tempdoc 400 + ADR 0005.
- Open questions for the design draft.

**Out of scope (explicitly):**

- Design. Shape of the periodic gauge registration API, the
  `NvmlService` lifecycle extension, the FFM binding skeletons —
  all deferred to a later draft.
- Commit sequence. No §14-style plan yet.
- Effort estimates. "Multi-week" is the only bound accepted.
- Implementation.

A follow-up draft can graduate this tempdoc from "investigation" to
"scoped for implementation" by adding design + sequencing sections
once the open questions in §6 are resolved.

---

## 9. References

- Tempdoc 400 (`docs/tempdocs/400-pipeline-observability-gaps.md`)
  - §8.3 LR3-a original design
  - §13 D3 pre-implementation scoping
  - §13 A4 `RrdMetricStore` constraints
  - §21.2 Phase 1 deferral rationale
  - §22.3 Issue C scope-granularity rethink
- Tempdoc 400 implementation log
  (`docs/tempdocs/400-pipeline-observability-gaps.md`)
- ADR 0005 — Manual FFM only (`docs/decisions/0005-manual-ffm-
  only.md`, referenced from tempdoc 400).
- Existing FFM templates:
  - `modules/gpu-bridge/.../NvmlService.java`
  - `modules/app-util/.../WindowsJobObject.java`
  - `modules/ipc-common/.../MmfWorkerSignalBus.java`
- Existing Layer-3 infrastructure:
  - `modules/telemetry/.../RrdMetricStore.java`
  - `modules/telemetry/.../LocalTelemetry.java`
  - `modules/telemetry/.../NdjsonMetricExporter.java`
