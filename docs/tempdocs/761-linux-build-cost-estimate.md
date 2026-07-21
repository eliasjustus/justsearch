---
title: "Linux build cost estimate (investigation-only): what a Linux (and/or credible WSL) story would take — inventory of Windows-coupled surfaces, effort class, risk register. No implementation."
type: tempdocs
status: "investigation COMPLETE (2026-07-21) — verdicts: full Linux build L (multi-week), WSL story M (1–2 wk); both share the same hardest blocker (no official Linux-CUDA llama-server prebuilt). Decision input ready; no implementation chartered. See §Findings."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed distribution-readiness work (2026-07-21)
category: distribution / platform
related:
  - 759-mcpb-standalone-feasibility     # sibling distribution lane
  - 760-installer-distribution-readiness # sibling distribution lane
---

> Charter. Motivation: JustSearch is Windows-only today. Several distribution/community surfaces
> weight cross-platform support heavily, and the decision "invest in a Linux build vs. accept a
> Windows-first ceiling for now" needs a real cost number, not a guess. This tempdoc produces
> the estimate only. A WSL path (documented, supported way to run the existing stack under
> WSL2 + GPU) counts as a candidate deliverable and must be costed separately — it may be the
> 20%-cost/80%-value option.

# 761 — Linux build cost estimate

## Investigation scope (delegable, read-only)

Inventory every Windows-coupled surface with `file:line` evidence, then classify each as
portable / needs-port / needs-replacement:

1. **Shell/packaging**: Tauri config + NSIS — what does a Linux target (AppImage/deb) need?
   Anything Windows-specific in `modules/shell` Rust code?
2. **Process & IPC layer**: the MMF-published gRPC port handshake (memory-mapped file
   signaling), process spawn/watchdog logic, any Win32-specific paths/APIs in Head/Worker
   lifecycle code. Java MMF is nominally portable — verify the actual usage is.
3. **Inference runtime**: cuda12 `llama-server` (llama.cpp ships Linux builds — confirm the
   pinned build/flags exist for Linux), ORT GPU sessions on Linux (ort-common assumptions),
   GPU detection in `gpu-bridge` (Windows-specific probing?).
4. **Model/data paths**: `<dataDir>` conventions, path handling, anything assuming Windows
   separators or registry.
5. **Build/CI**: what a Linux lane in CI would need (the public CI builds without model blobs —
   does that hold for a Linux target?).
6. **WSL2 alternative**: can the existing Windows stack — or a headless subset — run under
   WSL2 with GPU passthrough today? What breaks? What would a supported "WSL story" doc require?
   Cost it as its own line.

## Deliverable / acceptance

A written estimate section in this tempdoc:
- per-surface inventory table (surface → evidence → portable/port/replace → effort),
- two headline numbers: **full Linux build** effort class and **WSL story** effort class
  (S ≈ days, M ≈ 1–2 weeks, L ≈ multi-week, each with the top-3 risk drivers named),
- a risk register (unknowns that could blow the estimate),
- explicit non-goals (macOS is out of scope for this pass).

No implementation, no build attempts that mutate the tree; running read-only inspection
commands and existing Gradle metadata queries is fine.

## Findings (2026-07-21, opus investigator; file:line per claim)

**Headline: the Java/TS codebase is already Linux-clean at compile + unit-test level** — public
CI runs the no-model-blobs build and all three unit-test lanes on `ubuntu-latest`
(`ci.yml:189,246,261,275`); only 10 `@Tag("windows")` files run on the separate Windows lane
(`ci.yml:340-358`). Path/data-dir resolution is tri-platform by contract
(`PlatformPaths.java:132-145` ↔ `platform_paths.rs:67-89` ↔ `contracts/platform-paths/spec.v1.json`).
**The cost is concentrated in native-binary provisioning, shell packaging, process containment,
and end-to-end GPU verification — not application code.**

### Inventory (condensed; full table in the investigator report)

Portable, zero work: MMF signal bus (pure Java NIO file-backed mmap, `MainSignalBus.java:47-74` —
not a Win32 named MMF), Worker spawn (`WorkerSpawner.java:872-884` already OS-branched), CUDA
driver probe (`GpuDriverApiProbe.java:49-51` loads `libcuda.so.1` on Linux), VRAM via
`nvidia-smi` (`VramDetector.java:68-71`), data paths, most Tauri plugin deps
(`Cargo.toml:21-38`, no cfg(windows) deps).

Needs port: shell process mgmt (`lib.rs:157-173` — taskkill/javaw/explorer.exe are
cfg(windows)-gated with weaker fallbacks; needs tree-kill + xdg-open), llama-server resolver
(hardcoded `llama-server.exe`, `InferenceConfig.java:346`), ORT Linux staging (Windows DLL
sideload helpers have no `.so`/`LD_LIBRARY_PATH` analog, `OrtCudaHelper.java:111-169`).

Needs replacement: bundle targets (`tauri.conf.json:16-34` NSIS-only → deb/rpm/appimage),
llama-server provisioning (Gradle downloads **Windows-only** SHA-pinned assets,
`modules/ui/build.gradle.kts:357-376,658-736`), ORT CUDA runtime staging
(`build.gradle.kts:752-798`), bundled Tesseract (`build.gradle.kts:1450-1466`),
`WindowsJobObject` kill-on-parent-death (returns null off-Windows,
`WindowsJobObject.java:110-113` — orphaned Worker + leaked VRAM on Head crash without a
PDEATHSIG/process-group replacement).

### Verdicts

**Full Linux build: L (multi-week).** Top-3 risk drivers: **(R1) no official Linux-CUDA
llama-server prebuilt** — ggml-org `b8571` ships ubuntu-CPU/Vulkan/ROCm but zero ubuntu+CUDA
(release assets verified), forcing build-from-source-in-CI vs third-party binary
(breaks SHA-pinning) vs Vulkan pivot — a decision with supply-chain/ADR implications;
**(R3) WebKitGTK vs the custom transparent/undecorated titlebar** (`tauri.conf.json:59-60`),
live-visual-verify only; **(R4) job-object replacement** (above).

**WSL story: M (1–2 weeks).** The headless backend (Head+Worker+Brain) is the Linux build
*minus the Tauri shell* — skips the most expensive front, keeps the cheapest. WSL2 CUDA
passthrough is mature (~95-100% native for llama.cpp, 2025-26 sources). **Honesty point: WSL
does NOT dodge R1** — it's cheaper because it skips the shell, not inference provisioning.
Additional risks: loopback/ephemeral-port discovery for a shell-less client across the
WSL2→Windows `localhost` relay (the shell normally reads the MMF-published port); and
"supported ≠ works-once" — a reproducible install script + tested CUDA path is what makes it M
rather than S.

### Risk register

R1 llama-server Linux-CUDA supply (blocks GPU-only invariant; the estimate-maker); R2 ORT
`onnxruntime_gpu` CUDA-12 `.so` resolution on Linux (onnxruntime#19960; silent CPU fallback
risk); R3 WebKitGTK chrome; R4 orphaned-Worker containment; R5 advisory-lock semantics for
single-instance on Linux (`AppInstanceLock.java:96`, tested `@Tag("windows")` only); R6
Tesseract provisioning + GPL codec re-check (`build.gradle.kts:1459`); R7 no Linux+NVIDIA
hardware in CI — all of the above is static-green until a real GPU box verifies it
(`static-green ≠ live-working`).

### Non-goals & unverified

macOS out of scope (no CUDA path at all — separate investigation if ever). No ARM Linux.
Unverified (flagged by investigator): ORT GPU actually initializing on Linux/CUDA-12; WebKitGTK
titlebar behavior; MMF-port discovery across the WSL boundary; non-`WorkerSpawner` consumers of
the job object; contents of the 7 unopened `@Tag("windows")` files.
