---
title: "587 — Host-Capability Sensing: a confidence-carrying substrate (probe → effective view → policy). The GPU's `Effective`/`Confidence` merge generalized into a uniform capability axis; every other host fact (RAM, disk, CPU, OS) is today a degenerate single-source-naive instance, and even GPU is forked across 4 probes / 2 modules with confidence destroyed at the policy boundary. Proof-by-example: the gpu-bridge hardening (the nvidia-smi hang + the merge-testability refactor)."
type: tempdocs
status: active
note: "Phase 1 SHIPPED (2026-06-15) — the GPU capability completed as the reference instance (one resolver, CUDA folded into the merged Effective view, provenance to the surface, raw-probe foreclosure gate); browser-validated. Confidence-building (§11) corrected 4 claims before implementation; see §11–§12. Remaining axis-generalization phases stay design-theory."
created: 2026-06-15
category: hardware / runtime / capability-detection / gpu / inference / single-authority / projection / provenance / confidence / process-lifecycle / design-theory
related:
  - tempdoc 571 (surface altitude/composition — the genre template + the central move this doc mirrors: "generalize ONE derivation the substrate already performs." There it is audience-from-authority lifted to an altitude axis; here it is effective-from-probes lifted to a capability axis)
  - tempdoc 561 (the register/gate, one-authority-per-concept pattern; the `interaction-surface` gate is the enforcement template the capability-resolver foreclosure mirrors)
  - tempdoc 553 / 564 (canonical record + projection-vs-fork discipline — a capability answer is a PROJECTION of its probes, never a second authority)
  - tempdoc 575 (the observed-happening register — single-source + classify a set of streams; a capability catalog is the same register pattern one substrate over)
  - tempdoc 554 / 555 (value-law verification + logic-seams — the merge/precedence/threshold logic is a pure law-bearing seam, testable without the host; this doc's testability invariant is that pattern)
  - ADR-0026 (manual-CI) — relevant to where any future capability gate runs
related-skills:
  - /inference-runtime (GPU detection, ORT sessions, the gpu-bridge + ort-common probes this doc unifies)
verified-against-main:
  - modules/gpu-bridge/src/main/java/io/justsearch/gpu/GpuCapabilities.java (Confidence enum HIGH/MEDIUM/LOW/UNKNOWN + the Effective record carrying value+source+confidence+per-probe diagnostics — THE reference shape)
  - modules/gpu-bridge/src/main/java/io/justsearch/gpu/GpuCapabilitiesService.java (mergeEffective — the pure source-precedence law: NVML-device⟹nvml/HIGH; else nvidia-smi-VRAM⟹nvidia-smi/LOW; else none/UNKNOWN-with-diagnostics)
  - modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java (the probe shape: best-effort, never throws, returns a structured error-carrying snapshot)
  - modules/gpu-bridge/src/main/java/io/justsearch/gpu/VramDetector.java (the subprocess probe: 60s cache + sticky `nvidiaSmiUnavailable` liveness short-circuit; the nvidia-smi hang the hardening fixed)
  - modules/gpu-bridge/src/main/java/io/justsearch/gpu/VramRequirements.java (the pure requirements/threshold projection over the effective value)
  - modules/app-launcher/src/test/java/io/justsearch/app/launcher/VramDetectorAccessTest.java (the ArchUnit raw-probe foreclosure — consumers read the merged view, never the raw probe)
  - modules/configuration/src/main/java/io/justsearch/configuration/model/HardwareProfile.java (GPU-only; gpuDetected/cudaFunctional/vramBytes; MINIMUM_VRAM_FOR_GGUF SSOT; CONFIDENCE-STRIPPING boundary)
  - modules/configuration/src/main/java/io/justsearch/configuration/PlatformPaths.java (isWindows/isMac — the canonical OS resolver, bypassed by ~39 re-rolled os.name sites incl. NvmlService.isWindows + GpuAutoDetection.isWindows)
  - modules/ort-common/.../GpuAutoDetection.java + GpuDriverApiProbe (the FOURTH GPU probe — nvcuda.dll CUDA-functional — outside the gpu-bridge merge)
  - modules/app-services/.../ai/install/AiInstallService.java buildHardwareProfile (folds Effective.totalVramBytes but calls GpuDriverApiProbe directly for cudaFunctional — the bypass)
  - disk-threshold forks: SqliteJobQueue (50 MB) + NdjsonSpanExporter (200 MB/1 GB) + NdjsonMetricExporter (200 MB/1 GB) — three sites, three thresholds, no authority
  - subprocess spawn sites (11, all hand-rolled, no shared primitive): VramDetector, LlamaServerOps (launch+taskkill), WorkerSpawner, RuntimeActivationService (self-test+taskkill), StdioMcpTransport, ProcessExtractionSandbox, DownloadExecutor (curl + powershell/BITS)
---

# 587 — Host-Capability Sensing as a Confidence-Carrying Substrate

> **Genre: design-theory** (per 557/559/567/571). Feasibility, phasing, sequencing,
> and migration cost are **deliberately disregarded**; major rewrites/refactors are in
> scope; end-states are stated at the bar the category sets. Current-behavior claims were
> verified against `main` on 2026-06-15 (citations in frontmatter + inline); re-verify
> before relying. This is a **theory/charter**, not an implementation slice — no code
> changes are proposed here, only the correct conceptual structure.

## 1. The motivating case (proof-by-example)

The `gpu-bridge` hardening (a behavior-preserving refactor + a latent-bug fix, merged to
`main` 2026-06-15) was nominally a small test-coverage job. It surfaced two structural
facts that are **not GPU-specific**:

1. **A latent process-invocation hazard.** Three `nvidia-smi` methods read stdout *before*
   `waitFor(timeout)`, so a hung child could block the read forever and the timeout was
   unreachable. The fix was to reorder one call. But the same hazard-class is **re-defended
   by copy-pasted reasoning at every one of 11 hand-rolled spawn sites** in the codebase,
   with three different drain strategies and the safety of the cheap ones resting on
   *undocumented* "output fits the pipe buffer" assumptions. There is **no shared
   process-execution primitive** — the discipline is re-derived per site.

2. **A capability-sensing asymmetry.** The reason the GPU decision logic was untestable was
   that it was buried behind a subprocess/native call — so the fix was to extract a *pure
   merge* (`mergeEffective`) over probe snapshots. In doing so it became clear that GPU is
   **the only host capability in the system with a real sensing architecture**: a
   multi-source merge into one `Effective` view carrying a `value`, a **provenance** `source`
   string, a **`Confidence`** level, and per-probe diagnostics. Every *other* host fact —
   total RAM, free disk, CPU cores, OS/platform, driver versions — is sensed by a single
   naive call with **no fallback, no provenance, no confidence**, often **forked across many
   sites** (free disk is checked at 3 sites with 3 different thresholds; `os.name` is
   re-rolled in ~39 files despite a canonical `PlatformPaths`). And even GPU is **itself
   forked** across four probes in two modules, with the merge covering only two of them.

These are two distinct substrates that intersect. This doc theorizes both, with the
capability substrate as the spine (Pillar I) and the process substrate as the orthogonal
foundation it partly stands on (Pillar II).

## 2. Thesis

> The GPU's `GpuCapabilities.Effective` / `Confidence` / `source` design is a **proof-by-
> existence of the correct shape for a substrate the system is otherwise missing: host-
> capability sensing.** The correct long-term structure is **one generalization of a
> derivation the substrate already performs** — lift `effective-from-probes` from a
> gpu-bridge-local invention into a **uniform capability-resolution axis**, where every host
> fact is answered by *one resolver* that consults *authority-ordered probes* and yields a
> *confidence-tagged, provenance-carrying* value that **survives to the policy decision**.
> Then capability **forks** (disk×3, os.name×39, GPU's 4-probes/2-modules), **confidence
> destruction at policy boundaries** (the `HardwareProfile` cliff), and **host-coupled,
> untestable decision logic** all become **unrepresentable**.

This is **not a greenfield substrate.** It *extends* four prototypes that already exist and
are correct — it lifts them out of the GPU silo (§3). This mirrors 571 exactly: there, the
surface substrate already derived a surface's *audience* from its projected authority, and
the move was to generalize that one derivation into a full altitude axis. Here the GPU
module already derives an *effective capability* from its probes, and the move is to
generalize that one derivation into a full capability axis.

## 3. What already exists — extend, do not replace

Four correct prototypes live inside `gpu-bridge`. The substrate is the act of **lifting each
from "GPU's private version" to "the capability axis's general version."** None should be
reinvented.

| Prototype (GPU-local today) | What it gets right | Generalized form |
|---|---|---|
| `GpuCapabilities.Effective` + `Confidence` | A capability answer is `(value, source, confidence, diagnostics)` — **not a bare value** | The universal **answer shape** every capability returns |
| `GpuCapabilitiesService.mergeEffective` | A **pure** source-precedence law over snapshots — testable without the host | The **resolver**: authority-ordered probe consultation; single-probe capabilities = a one-element precedence list |
| `NvmlService.probe()` | A probe is **best-effort, never throws, returns a structured error-carrying snapshot** | The universal **probe contract** |
| `VramDetector` cache + sticky `nvidiaSmiUnavailable` | **Liveness**: a missing tool short-circuits future spawns | The universal **probe-liveness** rule |
| `VramRequirements` | Pure **threshold/requirement projections** over the effective value, against one SSOT (`MINIMUM_VRAM_FOR_GGUF`) | One **requirements home + one threshold SSOT** per capability |
| `VramDetectorAccessTest` (ArchUnit) | **Raw-probe foreclosure** — consumers read the merged view, never the raw probe | The **single-authority gate** per capability |
| `PlatformPaths.isWindows/isMac` | The OS capability **already has** a canonical resolver | Enforce it (kill the ~39 re-rolls) — the substrate's cheapest, already-built instance |

The substrate is therefore **~70% already designed**; it has simply never been named as an
axis or applied beyond GPU. That is the strongest possible position for a design-theory: the
end-state is "make the rest of the system look like the part that is already right."

## 4. Pillar I — the capability-sensing substrate

### 4.1 Concepts

- **Capability** — a typed host fact that *policy* consumes: VRAM bytes, CUDA-functional,
  total/available RAM, free disk *per data-dir*, CPU core count, OS/platform, NVIDIA driver
  version. Each is a value type, not a `boolean`/`long` smeared across call sites.
- **Probe** — a source of evidence for one or more capabilities. A probe declares: the
  capabilities it answers; its **platform applicability**; its **cost tier** (free in-process
  / cheap syscall / expensive subprocess); its **authority** (native API > tool subprocess >
  config-flag fallback); and its **liveness** (sticky-unavailable short-circuit). A probe is
  best-effort and never throws.
- **Resolver** — per capability, consults its probes in **authority-precedence order**, takes
  the first available answer, and produces the **Effective view**. The precedence law is
  **pure over probe snapshots**. Single-probe capabilities (RAM, disk, CPU) have a trivial
  one-element list and no merge ceremony — *the substrate must cover the single-source case
  without cost*, or it over-engineers (see §7).
- **Effective view** — `(value, source, confidence, perProbeDiagnostics)`. The `source`
  is provenance ("which probe won"); `confidence` is the authority of the winning probe
  degraded by precondition gaps (e.g. an incomplete probe).
- **Requirements projection** — pure functions reading the effective value against **one
  threshold SSOT**. (VRAM has this; disk does not.)
- **Policy consumer** — reads the resolver's effective view, never a raw probe, and is
  *handed the confidence* so it can be conservative under uncertainty.

### 4.2 The invariants — what becomes unrepresentable

1. **No capability is sensed two ways that can drift.** One resolver per capability;
   a second probe of the same capability outside its resolver is a build failure
   (the `VramDetectorAccessTest` gate, generalized). Eliminates disk×3, os.name×39,
   GPU's 4-probes/2-modules.
2. **Confidence is first-class and survives to the decision.** A capability answer is always
   `(value, source, confidence)`. **The defect this kills:** `HardwareProfile` collapses the
   GPU merge's confidence to `boolean gpuDetected`/`long vramBytes`, so the install planner
   *cannot* distinguish "12 GB, NVML, HIGH" from "12 GB, nvidia-smi, LOW" from "unknown" —
   it cannot degrade safely on uncertainty. The correct shape carries confidence to every
   policy boundary, making "decide on a value while discarding how sure we are" impossible.
3. **Resolution is host-independent and testable.** The merge/precedence/threshold logic is
   pure over snapshots (a law-bearing seam, 554/555). The probe I/O is the thin untestable
   shell; the decision is pure and unit-pinned. (This is precisely what the gpu-bridge
   refactor demonstrated — and why GPU decisions went from 0% to fully covered.)
4. **A missing tool is probed once, not every call.** Probe liveness is part of the contract
   (VramDetector's sticky flag, generalized), so a host without `nvidia-smi` doesn't pay a
   process spawn on every read.
5. **Thresholds live in one place per capability.** No bespoke hardcoded limit at a consumer
   (the disk 50 MB / 200 MB / 1 GB drift is the anti-pattern).

## 5. Even GPU is incomplete — proof the substrate is genuinely missing

GPU is the *best-developed* capability and it is still a **partial** instance, which is the
clearest evidence that the axis itself is absent rather than merely unapplied:

- **Four probes, two modules.** `gpu-bridge` merges NVML + nvidia-smi. But the
  **CUDA-functional** axis is sensed by a *separate fourth probe* — `GpuDriverApiProbe`
  (nvcuda.dll) via `GpuAutoDetection` in `ort-common` — which is **not in the merge**, and
  `AiInstallService.buildHardwareProfile` calls it **directly**, bypassing `Effective`. So
  even the canonical hardware-profile builder reaches around the one resolver for one axis.
- **Confidence is destroyed at the `HardwareProfile` boundary** (invariant 2 above).
- **`isWindows()` is re-rolled** inside both `NvmlService` and `GpuAutoDetection` rather than
  read from `PlatformPaths`.

The substrate would *complete* GPU: fold CUDA-functional into the resolver as a third probe
with its own authority; carry confidence through `HardwareProfile`; read OS from the one
resolver. GPU stops being a special case and becomes the **reference instance** of a general
axis.

## 6. Pillar II — the process-invocation substrate (orthogonal foundation)

The hang bug generalizes to a second, broader-scoped substrate. Today **11 production sites**
hand-roll `ProcessBuilder` with **no shared primitive**, three different drain strategies,
and duplicated `taskkill /F /PID` teardown + bundled-runtime PATH-prepend logic. The
hazard-class is *latent* (each risky site is individually defended) but the discipline is
**re-derived, not owned**.

The correct structure is **one hardened subprocess primitive** in which the discipline lives
exactly once:

- **Correct timeout** — `waitFor` bounds the call; the read can never block past it.
- **Guaranteed concurrent draining** — no pipe-buffer deadlock *regardless of output size*,
  eliminating the undocumented "output is small" assumption the cheap sites rely on.
- **Teardown ladder** — `destroy` → `waitFor(N)` → `destroyForcibly`, with the Windows
  hard-kill fallback, owned once.
- **Bundled-runtime PATH-prepend**, owned once.
- **Two modes** that the 11 sites cleanly partition into:
  - `runToCompletion(timeout) → bounded captured stdout/stderr` (probes, curl, powershell/BITS,
    taskkill, the Tika sandbox);
  - `launchManaged(logFile) → handle` (long-lived: llama-server, the worker, the MCP stdio
    child).

This is **the home the hang-fix should have generalized to**: rather than reordering
`waitFor` at one site, the rule becomes structurally unbreakable. It is a *distinct*
substrate (process lifecycle) whose scope is **broader** than capability-sensing — it also
serves work-launches — so it is its own concern, not subordinate to Pillar I.

## 7. The intersection, and the scope discipline (YAGNI / AHA guard)

**Intersection.** A subprocess-backed probe = a Pillar-I `Probe` *implemented over* the
Pillar-II primitive (the nvidia-smi VRAM probe is the canonical example). The two substrates
**compose**; neither subsumes the other. Native probes (NVML, MXBeans) and syscall probes
(`getUsableSpace`) sit only in Pillar I; work-launches (llama-server, worker) sit only in
Pillar II.

**Scope discipline.** The substrate is emphatically **not** "force a multi-source merge onto
everything." Most capabilities have exactly one good probe; forcing a probe-registry + merge
on them would be scaffolding no one reads (the over-DRY failure AHA warns against). The
substrate is precisely four things, each independently justified by a *recurring, observed*
fork/loss — not a speculative abstraction:

1. the **answer shape** `(value, source, confidence)` — justified by the confidence-loss at
   the `HardwareProfile` boundary;
2. the **single-authority discipline** (one resolver, raw probes foreclosed) — justified by
   disk×3, os.name×39, GPU's split;
3. the **threshold SSOT** per capability — justified by the 50 MB/200 MB/1 GB disk drift;
4. (for subprocess probes) the **shared exec primitive** — justified by 11 hand-rolled sites.

Single-source capabilities flow through #1–#3 with a one-element precedence list and zero
merge ceremony. The justification that these capabilities *share a reason to change* is
empirical, not aesthetic: the **same** fork failure recurs across them and the **same**
information loss recurs at policy boundaries. One documented instance proves a bug-class
(structural-defects-no-repeat); here several are documented.

## 8. Relationship to the codebase's standing patterns

This design is the codebase's existing disciplines applied to a new axis, not a new idiom:

- **Projection-vs-fork** (CLAUDE.md, 553/564): a capability answer is a *projection* of its
  probes — one canonical source — never a second authority that drifts.
- **Register + gate / one-authority-per-concept** (561/575/571): a capability catalog +
  a raw-probe foreclosure gate is the same shape as the `interaction-surface` /
  observed-happening / execution-surface registers, one substrate over.
- **Law-bearing seam** (554/555): the resolver's precedence/threshold logic is a pure law
  with a silent-wrong-value failure mode — exactly the seam class those docs pin.
- **"Generalize one derivation the substrate already performs"** (571): the central move,
  borrowed verbatim in spirit.

## 9. Confidence ledger

- **CONFIRMED (high):** GPU's `Effective`/`Confidence`/`source` is real and reused by no
  other capability (agent-verified, citations in frontmatter). Disk free-space is forked
  across three sites with three thresholds. `os.name` is re-rolled in ~39 files despite
  `PlatformPaths`. Confidence is destroyed at the `HardwareProfile` boundary. GPU is itself
  split across four probes / two modules. There is no shared process-exec primitive.
- **DELIBERATELY OPEN (the right altitude is uncertain, by design genre):** whether RAM/CPU
  sensing should be *resolvers* at all or simply *one canonical reader each* with provenance.
  The substrate must make the single-probe case free; if expressing RAM as a "resolver"
  costs ceremony, the design is wrong, not RAM. This is the boundary to get right before any
  implementation.
- **WEAKER DRIVER (acknowledged):** Pillar II's hazard is *latent* (defended per-site today),
  so its case is "eliminate the undocumented-assumption + duplication class," not "fix an
  active bug." It is the broader-scope, lower-urgency half. Pillar I carries an *active*
  information-loss defect (the confidence cliff) and is the stronger of the two.

## 10. Summary

The correct long-term structure is a **host-capability axis** in which every host fact is
answered by one **resolver** over **authority-ordered probes**, yielding a **confidence- and
provenance-carrying** value that **reaches the policy decision intact** — with
subprocess-backed probes standing on **one hardened process primitive**. The GPU module
already contains every prototype this needs (`Effective`, `Confidence`, `mergeEffective`, the
probe contract, the requirements projection, the raw-probe foreclosure gate); the design is
to **lift those out of the GPU silo and make the rest of the system — including the
unmerged-CUDA, confidence-stripping parts of GPU itself — degenerate instances of the same
axis.** Forks, confidence loss, and host-coupled untestable decisions become unrepresentable
by construction.

## 11. Confidence-building corrections (2026-06-15, pre-implementation)

A targeted feasibility pass (module-boundary, concept-conflation, live-vs-cached, blast-radius)
**corrected four claims above** before any code was written — recorded here so the design isn't
read as fully validated where it wasn't:

1. **Disk-threshold "fork" was a mirage (§1/§7 corrected).** The 50 MB `SqliteJobQueue` floor is a
   hard DB-corruption-safety gate; the 200 MB/1 GB telemetry bands are best-effort retention
   pressure. They **do not share a reason to change** — "unify into one SSOT" would be an over-DRY
   error. Only the free-space *reading* unifies into a resolver; the thresholds stay separate.
2. **Pillar II `launchManaged` is a forced abstraction (§6 corrected).** llama-server (HTTP-health +
   crash-counter), the worker (MMF heartbeat + backoff + stability window), and MCP stdio (JSON-RPC
   reader threads, no restart) have mutually incompatible lifecycles. Only the one-shot
   `runToCompletion(timeout)` primitive is sound; the long-lived launches stay separate.
3. **Confidence wraps `HardwareProfile`, it does not mutate it (§4.2 #2 refined).** Mutating the
   3-scalar record is a ~15–20 accessor + ~30–40 test-factory + Jackson ripple; the confidence-
   tagged answer must be a *separate view* the profile derives from.
4. **The unifications are dependency-feasible but not free (§3/§5 refined).** gpu-bridge and
   ort-common depend on neither each other (no cycle), so the GPU composition lives at the
   app-services layer that already depends on both — gpu-bridge takes the CUDA reading as an *input*
   and never depends on ort-common. The `os.name → PlatformPaths` sweep is ~95% feasible with one
   layering-blocked exception (`telemetry/CrashReporter`, a leaf that can't depend on configuration).

**Confirmed favorably:** the "one resolver, foreclose raw probes" model does NOT break the live
utilization sampler — `snapshot()` re-probes NVML every call (only total-VRAM is cached, with an
invalidate seam), so one resolver serves both one-shot gating and live sampling.

## 12. Phase 1 implementation outcome — SHIPPED (2026-06-15)

The validated core — **complete the GPU capability as the reference instance** — landed on `main`
(merge of `worktree-587-gpu-capability-resolver`):

- `GpuCapabilities.Effective` carries a `Cuda` axis (functional/source/confidence); `mergeEffective`
  threads it (single-probe, orthogonal to the VRAM precedence). gpu-bridge stays free of ort-common —
  the CUDA reading is supplied via `snapshot(Cuda)`.
- **`GpuCapabilityResolver`** (app-services) is the one composition seam: probes CUDA via
  `GpuDriverApiProbe` + folds it into the NVML/nvidia-smi merge. `AiInstallService` (bypass removed),
  `StatusLifecycleHandler`, and `HeadlessApp`'s boot VRAM gate all route through the resolver/service.
- Wire (user-visible): `GpuStatusView` + `status.proto` + regenerated schema/TS gain
  `cudaFunctional`/`source`/`confidence`; the Health-surface GPU card renders a "GPU acceleration"
  row + a provenance hint.
- **`GpuProbeAccessTest`** generalizes `VramDetectorAccessTest` — direct `GpuDriverApiProbe` /
  `NvmlService` use outside the resolver is now a build failure (the bypass is unrepresentable).

**Validated:** gpu-bridge tests (+3 CUDA cases), app-api schema tests, both ArchUnit gates, FE
typecheck + 2996 unit tests, class-size gate, all green. **Browser-validated** on an RTX-4070 host:
the Health-surface GPU card shows "GPU acceleration: Active" + "Source: nvml · HIGH" sourced from the
resolver via `/api/status`.

**De-scoped (per §11):** disk-threshold unification, Pillar II `launchManaged`, and a generic
RAM/CPU resolver framework (YAGNI — GPU is the only genuinely multi-probe capability).

**Known follow-ups (not blocking):** the `/api/gpu/capabilities` diagnostic endpoint reads the raw
gpu-bridge `snapshot()` (CUDA axis = unknown sentinel) rather than the resolver — a secondary
inconsistency vs `/api/status`; route it through the resolver for full single-authority. The
deferred `os.name → PlatformPaths` sweep (Phase 2) and the `runToCompletion` process primitive remain
as separately-scoped slices.
