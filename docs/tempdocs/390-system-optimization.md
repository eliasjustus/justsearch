---
title: "390 — Whole-PC System Optimization"
---

# 390 — Whole-PC System Optimization

**Status:** Active.
**Created:** 2026-04-18.
**Rewritten:** 2026-04-18 (evening) — split scope after SPLADE-counter-drift fix, 3-run baseline captured, and realisation that the existing `jseval` framework covers most of what this tempdoc previously planned to build.
**Split:** 2026-04-19 — all JustSearch-pipeline-specific content moved to `docs/tempdocs/391-pipeline-throughput-and-variance.md`. This doc retains whole-PC scope only.
**Supersedes:** v1 (morning) + v2 (off-the-shelf tools pivot) + v3 (3-run + bug fix).

---

## Scope

This tempdoc covers **whole-PC tuning only**: hardware inventory
verification, Windows / BIOS / driver tuning, system synthetic
benchmarks (CPU, memory, storage, GPU-agnostic), telemetry stack, and
system-level A/B experiments.

**JustSearch-pipeline-specific work lives in
[391-pipeline-throughput-and-variance.md](./391-pipeline-throughput-and-variance.md):**
jseval baselines, run records, root-cause investigations, JustSearch
opportunity register (E-J-*), pipeline experiments, the SPLADE
counter-drift fix, and resolution theorisation. 391 references this doc
for Hardware Profile and Measurement Methodology.

**Coordination across the two docs:**
- **Hardware Profile and Measurement Methodology** live here (below);
  391 cross-references.
- **Validation Workflow** lives here (below) and sequences batches
  across both scopes: A / C / H / I are PC-owned (this doc); B / E / F
  / G are JustSearch-owned (detailed experiments in 391).
- **Correctness fixes** landed during the 2026-04-18 session are
  recorded here as a session-level summary; the SPLADE-counter-drift
  fix's full detail (root cause, code citations, regression test) lives
  in 391.
- **Handoffs** between scopes go through named rows in the Handoffs
  section below; per-doc handoffs (H-PC-*, H-JS-*, H-INV-*) live in the
  owning doc.

---

## Hardware Profile (measured 2026-04-18, shared)

Canonical state observed directly via `smartctl`, `Get-CimInstance`, `nvidia-smi`, `powercfg`, `whoami.exe /priv`. Any action taken by either agent that would change this must update the row.

| Component | Measured |
|---|---|
| CPU | Intel i7-12700K, 12C/20T (8P + 4E). Air cooler (tower). |
| RAM | 32 GB DDR4-3600 CL18-22-22-42, 2×16 GB Corsair CMK32GX4M2D3600C18. XMP active, dual-channel, 1.2 V SPD. |
| Primary storage | **Samsung 990 PRO 2 TB** NVMe PCIe 4.0 x4. Firmware **8B2QJXD7** (Dec 2025, current latest). SMART=PASSED, PercentageUsed=0 %, PowerOnHours=4, temp 45 °C idle, Available Spare=100 %, NVMe 2.0. Warning 82 °C / Critical 85 °C. |
| Secondary storage | **Samsung 870 EVO 1 TB** SATA TLC — partitions D: 314 GB + E: 615 GB. |
| GPU | NVIDIA RTX 4070 12 GB. Driver 595.79 (CUDA runtime 13.2 reported). VBIOS 95.04.3e.40.5f. Power Limit 200 W stock. Idle 41 °C P8. |
| Motherboard | ASUS ROG STRIX B760-G GAMING WIFI D4. BIOS 1825 (released 2025-10-09). |
| Network | Intel Wi-Fi 6E AX211 at 229 Mbps (160 MHz) active; 2.5 GbE Ethernet onboard, reachable but deliberately unused. |
| OS | Windows 11 Pro 25H2, build 26200.**8037** (2026-03-06 hotfixes). User target ≥ .8117 pending. |
| Dev Drive | F: ReFS, 493 GB total, ~394 GB free, 4 KB allocation unit. Defender `PerformanceModeStatus=1`. |
| NVMe partition layout | EFI 200 MB + Reserved 16 MB + C: 931 GB + I: 471 GB + F: 459 GB + Recovery; **0 bytes unallocated**. |
| Privileges | `SeIncreaseWorkingSetPrivilege: Disabled`. `SeLockMemoryPrivilege`: **not granted** → `-XX:+UseLargePages` in gradle.properties is a silent no-op. |
| Power plan | **ChrisTitus Ultimate** (`ac2e159b-6bc0-4043-892d-6b24124013ed`). AC min = AC max = 100 %. |
| Git repo config | `fsmonitor`, `untrackedCache`, `preloadIndex`, `manyFiles` — all set at **repo** level (`.git/config`), not global. |
| Claude session env vars | `CLAUDE_CODE_NO_FLICKER` / `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` / `CLAUDE_ENABLE_STREAM_WATCHDOG` / `CLAUDE_CODE_SCROLL_SPEED` — none set in process env. |

---

## Correctness Fixes Applied (shared, 2026-04-18 → 2026-04-19)

| Fix | File | Why | Owner | Status |
|---|---|---|---|---|
| Removed stale `JUSTSEARCH_MODELS_DIR="D:/code/JustSearch/models"` env override | `.claude/settings.local.json` | Path did not exist; `InferenceConfig.discoverGgufModels` fell back to hardcoded constants, hiding the 3 GGUFs actually present at `F:\JustSearch\models`. | JustSearch | **committed** `47b4ce3c0` |
| Unset stale `core.hookspath = E:\code\JustSearch\.git\hooks` | `.git/config` (local) | Pointed to an old checkout on D:/E:. Coincidentally identical contents masked the breakage; would fail silently on any hook change. | JustSearch | **applied** (local-only config, not versioned) |
| **SPLADE counter drift fix** (see "SPLADE fix" section below) | `modules/adapters-lucene/.../WritePathOps.java` + regression test | `preserveSplade=true` RMWs were silently dropping non-stored `splade_status` doc-value, stalling pipeline coverage at ~92 %. | JustSearch | **committed** `57fa91775` |
| Removed `-XX:+UseLargePages` from gradle.properties *(pending — flagged, see E-J-N5)* | `gradle.properties` | Silent no-op without `SeLockMemoryPrivilege`. | JustSearch | pending Batch B null experiment |

---

## Debunked Items (shared — do not re-propose)

Evidence-backed rejections. Each row reflects measurement or cited research.

| Item | Why rejected |
|---|---|
| **990 PRO firmware update to ≥ 4B2QJXE7** | Drive is 2 TB (`xD7` line), not 1 TB (`xE7` line). Current `8B2QJXD7` (Dec 2025) is already six revisions past the wear-leveling fix. SMART=PASSED, 0 % used, 4 power-on hours. Sources: Samsung consumer-storage support, Puget Systems 2023-03-14, Tom's Hardware. |
| **Grant `SeLockMemoryPrivilege` + keep `-XX:+UseLargePages`** | Windows = 2 MB pages only. JVM 4 g Gradle daemon: <1 % wall-clock win measured in the wild. Privilege widens security surface. JDK 21+ falls back with warning; currently silent no-op. Sources: Shipilëv "JVM Anatomy Quarks #2", JDK-8266489, JDK-8267042. |
| **Pagefile fixed 4096/4096** | QLC 80 TBW wear argument evaporated when the drive turned out to be TLC 600 TBW. 32 GB RAM + auto pagefile is correct. |
| **NVMe explicit over-provisioning** | PercentageUsed=0 %, 87 % free on the ReFS Dev Drive. Modern consumer TLC self-OPs; no evidence of sustained-write degradation. |
| **BIOS PL1/PL2 raise** | Air cooler caps sustained power. Raising PL1/PL2 would thermally throttle sooner, not later. **Empirically confirmed 2026-04-19 B1:** CPU hit TjMax (100 °C) under CPU_ALL with all three active fans at 100 % control and CPU fan at its physical RPM ceiling (1 856 / 1 867 RPM). p95 Package Power was 123 W — already at PL1 stock = 125 W. Reconsider only with AIO or better air cooler. |
| **Enable Kotlin K2 compiler** | Codebase is **1,251 Java files, 0 Kotlin in `modules/`**. Only `build-logic/` (16 Kotlin files) compiles Kotlin; it already uses K2 by default in Kotlin 2.0+. Zero impact. |
| **Custom measurement harness** (old "sysperf" Phase 1+ plan) | Existing `scripts/jseval/` framework already provides: `suite_stats` (median + stddev + 95 % CI via t-distribution), SQLite `history.db` (with Student's t-test regression for N≥8), `diff_gate` (ratio-based pass/fail), `compare` / `diff` / `trend` CLI, `nsys` integration (231 lines of Nsight Systems wrapping). Extending `jseval` is correct; a parallel harness is not. |

(Items previously debunked in 391/392 — `DisablePagingExecutive`, `NtfsMemoryUsage=2`, `EnablePrefetcher`, GPU VRAM as system RAM, legacy `bcdedit`, CR 2T, tRFC lowering, AVX-512 microcode mod, CPU undervolt, HAGS toggle, NV Prefer-Max-Perf, Steam tier-2C, Process Lasso, small-mode, Thread-Director XP toggle, Gear 1 on B760, visual-effects-disable — carried forward unchanged.)

---

# Whole-PC Agent Scope

## System-level opportunity register (PC-agent owned)

Status: `PENDING` = not yet applied or measured by the PC agent. Priorities follow the air-cooled 12700K + general-purpose threat model scoped in prior session.

| # | Item | Status | Notes |
|---|---|---|---|
| S-1 | Windows Update UBR .8037 → ≥.8117 | PENDING | Free. Tempdoc v1 cited KB5086672 GPU fix. Forced change; just record the delta. |
| S-2 | SysMain disable | PENDING | Save RAM; harmless on NVMe. |
| S-3 | WSearch disable + install `voidtools.Everything` | PENDING | Free, big daily-life win for dev file lookup. |
| S-4 | Defender scheduled scan window | PENDING | Currently 02:00 daily, 50 % CPU cap. Relocate if collides with active sessions. |
| S-5 | DNS → Cloudflare + DoH | PENDING | Low risk, small win on name resolution. |
| S-6 | Power plan A/B — ChrisTitus Ultimate vs MS Ultimate Performance | PENDING | Counter-hypothesis: bursty compile may prefer Balanced + Thread Director (cold P-cores boost higher). Measure with system synthetic B1 before keeping either. |
| S-7 | HVCI benchmark (keep or disable) | PARKED | User threat model = general-purpose → keep HVCI on. Benchmark only if a clear JustSearch regression traces to it. |
| S-8 | Claude session env vars (`CLAUDE_CODE_NO_FLICKER`, etc.) | PENDING | Session ergonomics; free. Set via `setx` or a startup script. |
| S-9 | Install CrystalDiskInfo + LibreHardwareMonitor (or HWiNFO64) | PENDING | Unblocks thermal observability under sustained load. |
| S-10 | 990 PRO M.2 heatsink verification | PENDING | Visual BIOS/POST check next reboot. Throttle threshold ≈ 80 °C. |
| ~~S-11~~ | ~~Backup / recovery strategy~~ | OUT OF SCOPE (2026-04-19) | Dropped per user direction. Not part of the bench / perf-optimisation workflow. |

**Deprioritized** by prior user scoping: 2.5 GbE Ethernet migration (cable reachable but user prefers Wi-Fi), Riot Vanguard removal (general-purpose threat model), `SeLockMemoryPrivilege` grant (debunked).

## System synthetic benchmark tool map (PC-agent owned)

**Primary tool**: **PassMark PerformanceTest 11.1.1009**, installed at `C:\Program Files\PerformanceTest\PerformanceTest64.exe` (reinstalled 2026-04-18 evening to the default path; prior E:\ copy uninstalled by user). One tool, one scripted invocation, covers **CPU** (compression, encryption, physics, prime, integer + float math, sort, extended instructions), **memory** (cached + uncached read, write, latency), **disk** (sequential + random, IOPS, latency), and optionally **2D graphics**. 3D is available but skipped in automation (opens fullscreen window). Standard tests are free forever; advanced tests ran on a 30-day trial but the user has purchased a license. Canonical one-line call:

```
"C:\Program Files\PerformanceTest\PerformanceTest64.exe" scripts\bench\passmark-b1.ptscript
```

PassMark's own scripting language handles looping + CSV aggregation via `LOOP N { ... } REPORTSUMMARYCSV "..."`, so we do **not** wrap it in an external loop. A canonical ptscript is pasted in the E-S-B1 experiment row.

| Domain | Tool | Install | Role |
|---|---|---|---|
| **Unified system bench** (CPU + mem + disk + 2D/3D) | **PassMark PerformanceTest 11.1.1009** | installed at `C:\Program Files\PerformanceTest\` | **Primary.** Scripted CSV output; baseline-DB comparison against > 1 M systems. |
| LLM inference | `llama-bench` from llama.cpp CUDA build | [ggml-org/llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases) | **Supplementary** — PassMark has no LLM test. `-m <gguf> -ngl 99 -p 512 -n 128 -r 5 -o csv` reports pp / tg + stddev. |
| ONNX inference | `onnxruntime_perf_test` | ships with `onnxruntime-win-x64-gpu` zip | **Supplementary** — only when specifically measuring ORT execution-provider changes (JustSearch-agent E-J-N6). |
| NVMe SMART / thermal | smartctl 7.5 | `winget install smartmontools.smartmontools` (installed 2026-04-18) | **Supplementary** — PassMark's disk subtests are too short to trigger sustained-write thermal throttle. `smartctl -A -j -d nvme pd1` for point samples; 1 Hz poll during DiskSpd sustained-write for thermal curves. |
| Sustained-write + thermal stress | DiskSpd 2.2 | [microsoft/diskspd releases](https://github.com/microsoft/diskspd/releases) | **Optional** — used only for E-S-10 (heatsink + sustained-write thermal verification). Pre-fill target with `-Zr` on ReFS. |
| GPU profiling (targeted) | NVIDIA Nsight Systems 2026.2 | [developer.nvidia.com/nsight-systems](https://developer.nvidia.com/nsight-systems) | Free; `nsys profile -t cuda,nvtx,osrt …`. Not part of B1; reserved for JustSearch agent if a CUDA-side investigation lands. |
| Telemetry — primary | HWiNFO64 free GUI CSV sensor log | `winget install REALiX.HWiNFO` | Free personal; three-click manual start before each run, 1 s poll, no time cap on CSV log itself. |
| Telemetry — GPU cross-check | `nvidia-smi dmon -s pucvmet -d 1 -c N -f gpu.csv` | driver (already present) | Runs in a parallel pane; NVML cross-check against HWiNFO's NVAPI read. |

**Fallbacks** (retained for reference, not the primary path): 7-Zip `b`, y-cruncher, Cinebench 2024, Intel MLC, fio, Geekbench 6, Unigine Superposition. Each is a legitimate free or near-free tool in its domain, but PassMark consolidates them into one scripted run, so they're only invoked if a specific PassMark subtest is suspect (e.g., cross-check a PassMark CPU score against Cinebench once to detect a systematic bias, then stop).

**Disqualified**: AIDA64 Engineer ($230/yr), Geekbench Pro ($99 > $50 cap for `--no-upload`), 3DMark CLI ($35 paid), AS SSD + Anvil (unmaintained), Open Hardware Monitor (abandoned, CVE-2020-14979), STREAM (requires compilation — violates off-the-shelf rule), CrystalDiskMark (GUI-only; wraps DiskSpd anyway), Novabench Free (no CLI / CSV; confirmed useless for scripted runs; Pro $18 /month).

## Reference scores (for system-sanity gate)

System agent should compare measured to these before declaring the box "at class". Two tables: the **primary** one is PassMark-native since PassMark is now the canonical tool; the **fallback** one is kept for cross-check against individual free tools if a specific PassMark subtest looks suspect.

### Primary: PassMark-native scores — captured 2026-04-19 (B1 N=1)

Canonical data: `docs/tempdocs/390-results/2026-04-19-B1/passmark-b1.csv` row 2, Result Date `19/04/2026 16:54`, git SHA `cf38da21b`. System: i7-12700K + 32 GB DDR4-3600 CL18 + RTX 4070 12 GB + Samsung 990 PRO 2 TB on F: (ReFS) + ASUS B760-G BIOS 1825, Windows 11 Pro 25H2 build 26200.8037, ChrisTitus Ultimate power plan, HVCI on.

| PassMark metric | Measured | Class band (12700K + DDR4-3600 / 4070 / 990 PRO) | Delta vs class | Gate |
|---|---|---|---|---|
| **CPU Mark** | **34,589.5** | ~34,000–35,000 | at class | ✓ |
| **Memory Mark** | **3,801.0** | ~3,500–4,000 | at class | ✓ |
| **Disk Mark** (F: ReFS) | **56,374.3** | ~45,000–55,000 (varies; ReFS typically above NTFS) | +5 % above band | ✓ |
| **2D Graphics Mark** | **1,509.4** | ~1,000–1,700 | at class | ✓ |
| 3D Graphics Mark | skipped | — | — | — |
| **PassMark Rating** (overall) | **17,493.2** | ~14,000–16,000 | +9 % above band | ✓ |

Per-subtest data (for drift tracking across future batches):

| Subtest | Measured | Unit |
|---|---|---|
| CPU Integer Math | 116,910.0 | MOps/s |
| CPU Floating Point Math | 89,162.2 | MOps/s |
| CPU Prime Numbers | 95.7 | M primes/s |
| CPU Extended Instructions (SSE / AVX2) | 29,167.0 | Mill. Matrices/s |
| CPU Compression | 453,424.3 | KB/s |
| CPU Encryption | 24,203.8 | MB/s |
| CPU Physics | 1,658.0 | FPS |
| CPU Sorting | 46,462.3 | thousand strings/s |
| CPU Single Threaded | 3,980.5 | MOps/s |
| Cross-Platform Mark | 69,474.3 | composite |
| 2D Simple Vectors | 20.8 | thousand vectors/s |
| 2D Fonts and Text | 382.9 | Ops/s |
| 2D Windows Interface | 102.6 | Ops/s |
| 2D Image Filters | 3,757.9 | Filters/s |
| 2D Image Rendering | 410.3 | thousand images/s |
| 2D Direct 2D | 83.3 | FPS |
| 2D PDF Rendering | 103.3 | Ops/s |
| 2D SVG | 116.0 | FPS |
| Memory Database Operations | 8,670.4 | KOps/s |
| Memory Read Cached | 36,555.5 | MB/s |
| Memory Read Uncached | 26,111.1 | MB/s |
| Memory Write | 17,850.4 | MB/s |
| Memory Latency | 36.2 | ns (lower better) |
| Memory Threaded | 47,385.4 | MB/s |
| Disk Sequential Read | 7,114.7 | MB/s |
| Disk Sequential Write | 6,691.0 | MB/s |
| Disk IOPS 32 KB Q20 | 4,848.2 | MB/s (≈ 155 K IOPS) |
| Disk IOPS 4 KB Q1 | 137.5 | MB/s (≈ 35 K IOPS) |

**llama-bench (8B Q4_K_M on RTX 4070, -ngl 99, r=5, measured 2026-04-19 16:56, `docs/tempdocs/390-results/2026-04-19-B1/llama-bench.csv`):**

| Metric | Measured | Expected band | Gate |
|---|---|---|---|
| Prompt processing @ 512 | **4,221.6 tok/s** ±70.8 stddev | 3,500–4,500 | ✓ |
| Token generation @ 128 | **87.2 tok/s** ±0.10 stddev | 70–85 | ✓ (slightly above) |

Build `23b8cc499` / b8838, CUDA backend, layer split, f16 KV cache. GPU PCIe init + model load completed before first rep; reported throughputs are steady-state.

**Sanity gate verdict (2026-04-19, revised after telemetry interrogation):** No subsystem is pathologically underperforming. PassMark Marks sit within the rough expected envelope for this CPU + memory + disk + cooling combination; llama-bench pp/tg land inside the expected 4070 band with very small r=5 stddev (1.7 % pp, 0.12 % tg). **H-PC-1 cleared** — JustSearch agent (391) unblocked for project-level experiments. But the numbers are more limited than "at class" implies; do not cite them without the five caveats below:

1. **"Class bands" cited in the table are my recollection** of typical pcbenchmarks.net scores, **not verified** against that database. The "+9 % PassMark Rating" delta is indicative, not authoritative. To make a rigorous at-class claim, a future agent should query pcbenchmarks.net for each subsystem baseline (stock 12700K + DDR4-3600 + 990 PRO 2 TB + 4070) and update the bands.
2. **CPU Mark is measured at the cooler's thermal ceiling.** Telemetry shows 100 °C TjMax peaks (2 samples, 0.13 % of run) and p95 Package Power 123 W (PL1 stock = 125 W). It's a real steady-state score for this cooling config, **not the CPU's ceiling potential**. See "Cooler is the binding constraint" below.
3. **Memory "Latency" 36.2 ns is cache-inclusive, NOT DRAM round-trip.** DDR4-3600 CL18 true DRAM RTT is ~75–85 ns (what MLC / AIDA64 measure). PassMark's pointer-chase benchmark includes L1/L2/L3 hits. Useful as a regression signal; do not compare across memory subsystems.
4. **Disk 4K QD1 33.6 K IOPS is ~50 % above the 990 PRO spec** of 22 K IOPS random read Q1T1 — almost certainly filesystem (ReFS) / OS cache contribution, not pure direct-random IOPS. Do not cite as a "4K QD1 baseline" without qualification.
5. **N=1 — run-to-run CV from P1 (CPU_ALL × 3 iters) was ~2.4 % on CPU Mark.** A second B1 could land ±800 PassMark Rating points from this one. For cross-run comparisons (Batch H power-plan A/B, etc.), at minimum N=2 pre + N=2 post is required to distinguish signal from run-to-run noise.

### Telemetry interrogation (`docs/tempdocs/390-results/2026-04-19-B1/lhm.csv`, `gpu.csv`, `nvme.jsonl`)

Aggregated via `scripts/bench/_analyze-B1.ps1`. 1 537 samples at 1 Hz over 1 880 s. Where PassMark and telemetry agree, the PassMark number is trustworthy; where they disagree or telemetry adds context, caveats apply.

| Sensor | Min | p50 | p95 | Max | Notes |
|---|---|---|---|---|---|
| CPU Package Temp (°C) | 28 | 41 | 74 | **100** | TjMax peaks (2 samples ≈ 0.1 % of run) |
| Core Max Temp (°C) | 27 | 42 | 75 | 100 | tracks Package Temp |
| CPU Package Power (W) | 15 | 36 | **123** | 179 | PL1 stock 125 W; PL2 stock ~190 W. Sustained right at PL1 limit. |
| CPU Cores Power (W) | 5 | 25 | 111 | 168 | "Cores" excludes Ring/Uncore |
| CPU Total Load (%) | 1 | 6 | 100 | 100 | Only ~5 % of run at full multi-thread load; most subtests 1–3 thread or I/O-bound |
| P-Core #1 Clock (MHz) | 802 | 4 913 | 4 913 | 4 913 | All-core boost sustained at 4.9 GHz; no significant clock drop despite thermal peaks |
| GPU Core Temp (°C) | 35 | 39 | 49 | 57 | Cool throughout |
| GPU Core Load (%) | 1 | 2 | 55 | 83 | Engaged for Direct 2D / DirectX — 2D Mark is a real 4070 measurement, not CPU software rendering |
| GPU Package Power (W) | 11 | 12 | 31 | 84 | Modest power draw, 4070 not stressed |
| GPU Memory Used (MB) | 816 | 953 | 1 008 | 1 103 | 2D barely uses VRAM |
| NVMe Composite Temp (°C) | 41 | 48 | 65 | **66** | Well below 82 °C warn / 85 °C critical. **No thermal throttling** on disk tests. |
| NVMe Read Rate peak (GB/s) | — | — | — | 7.13 | Cross-validates PassMark's 7 115 MB/s (0.2 % agreement); 95 % of 990 PRO spec 7 450 |
| NVMe Write Rate peak (GB/s) | — | — | — | 6.84 | Cross-validates PassMark's 6 691 MB/s (2 %); 97 % of spec 6 900 |
| Total RAM Load (%) | 34 | 35 | 37 | 44 | Capacity never stressed (13.9 GB peak of 32) |

### Cooler is the binding constraint on CPU (fan-correlation analysis)

`scripts/bench/_fan-correlation.ps1` bins fan state by CPU Package Temp:

| CPU Temp (°C) | Samples | Fan #2 ctrl | Fan #2 RPM | Case fans #1/#4 ctrl |
|---|---|---|---|---|
| <50 | 1 234 (80 %) | 53 % | 1 140 | 66 % |
| 50–69 | 202 (13 %) | 70 % | 1 380 | 72 % |
| 70–79 | 42 (2.7 %) | **100 %** | 1 809 | 88 % |
| 80–89 | 31 (2 %) | 98 % | 1 817 | 97 % |
| 90–94 | 11 (0.7 %) | 100 % | 1 798 | 100 % |
| 95–99 | 15 (1 %) | 100 % | 1 826 | 100 % |
| **≥100** | **2** (0.13 %) | **100 %** | **1 856** (~physical ceiling 1 867) | **100 %** |

Fan #2 (CPU fan) saturates at 70–79 °C, well before throttle point. At TjMax, all three active fans are at 100 % control and Fan #2 is at its physical RPM ceiling. **The CPU throttled despite the cooler being fully saturated** — the binding constraint is cooler hardware, not fan curve.

Three options to reduce TjMax peaks (none applied here):
- **Better cooler** (NH-D15-class tower or 280 mm AIO) — expected 5–10 °C drop under sustained load. Hardware cost.
- **CPU undervolt** — reduces heat at same perf; Silicon-lottery dependent but often 5–15 °C. Requires BIOS tuning.
- **Lower PL1/PL2** — reduces heat **and** reduces CPU Mark; not a win for benchmark scores.

**Corollary:** do not re-propose raising PL1/PL2 (already in Debunked Items; this run is the empirical confirmation). Fans #3, #5, #7 report control but zero RPM — empty motherboard headers. Fan #6 constant 100 % with no RPM — same (unused header).

**Actual B1 wall time: 1880 s = 31.3 min.** Earlier estimate of 14–16 min (linear extrapolation from P1 CPU_ALL) was off by ~2×. Root cause: ME / DI / G2D subtests each have multiple internal phases (e.g., IOPS tests sweep several queue depths sequentially, each for SETDURATION seconds), so the effective subtest cost is ~75 s at SETDURATION 30, not 30 s. Adjusted estimates below.

**Revised wall-time calibration (from measured B1):**

| Variant | SETDURATION | N | Estimated wall |
|---|---|---|---|
| `passmark-b1.ptscript` (full) | 30 | 1 | **~31 min** (measured) |
| `passmark-b1-quick.ptscript` | 15 | 1 | **~16 min** (2.5× scale factor × 25 subtests × 15 s + overhead) |
| CPU-only (if re-added) | 30 | 1 | **~10 min** (8 subtests only; CPU phases simpler) |

**Sanity rule (unchanged)**: if any future B1 re-run produces a summary Mark ≥ 10 % below the measured values above (or any per-subtest row ≥ 20 % below), open a subsystem investigation before proceeding with JustSearch experiments.

### Post-B1 research priorities (ranked, 2026-04-19)

Five areas the B1 + P1 data points toward. For each: what we already know from existing data (desk analysis), concrete prediction, what requires a new run, and the decision rule. All five feed into future batches; none should be implemented blindly.

#### 1. Why is the jseval pipeline GPU not saturated? (highest leverage — may unlock 20–40 % on ingest wall)

**Desk analysis** from 391 baseline (3× scifact, git `58221d5fa` + SPLADE fix):
- Total per-encoder inference wall: embed 75.4 s + SPLADE 87.5 s + NER 43.5 s = **206.4 s**.
- Pipeline wall: **253.1 s**.
- Theoretical max GPU utilisation if encoders were 100 % GPU-busy during their inference windows: **206.4 / 253.1 = 81.5 %**.
- Observed GPU average: **59.4 %**. Gap = 22 percentage points → ~55 s of GPU idle during "inference-active" windows.
- Distribute across 18 603 calls (954 embed + 5 320 SPLADE + 12 329 NER): **~3 ms per-call GPU-idle overhead**. Consistent with small-batch ORT on CUDA: H2D copy + kernel launch + D2H copy dispatch overhead.
- NER is the worst offender: median call 2.6 ms total, of which maybe ~1 ms is actual GPU kernel time → ~60 % of NER wall is overhead, not kernel work.

**Prediction.** If the bundle `ort-perf-probe.py` (Batch E follow-ups) runs with (1) `*_fp16.onnx` variants, (2) batch sweep 1/4/16/64, (3) IO binding (pre-allocated GPU tensors), the GPU utilisation will lift from ~59 % to 80–90 % and ingest wall will drop 15–30 % purely from batching + IO binding. FP16 alone (no batching) is marginal on non-reranker encoders.

**What requires a new run.**
- Option A (minimal): Re-run `python -m jseval run --dataset scifact --pipeline --start-backend --clean --nsys tmp/nsys/e-j-1.nsys-rep` (`scripts/jseval/jseval/nsys.py` wrapper, 231 lines, already integrated). Query the resulting SQLite for per-kernel gap times.
- Option B (full): the existing Batch E probe enhancements (FP16 + batch sweep + IO binding + TensorRT EP cross-check in `scripts/bench/ort-perf-probe.py`) run cold on each encoder individually. 45 min wall once enhancements are in place.

**Install + capture attempt (2026-04-19)**: Nsight Systems 2026.2.1 installed manually (direct URL `https://developer.nvidia.com/downloads/assets/tools/secure/nsight-systems/2026_2/NsightSystems-2026.2.1.210-3763964.msi`, 515 MB public without developer-login auth). Added `C:\Program Files\NVIDIA Corporation\Nsight Systems 2026.2.1\target-windows-x64` to machine PATH. nsys version 2026.2.1.210 confirmed.

**Empirical capture FAILED** due to resource pressure. Sequence:
1. Started nsys with `profile --trace=cuda --cuda-trace-scope=system-wide --cuda-memory-usage=true` wrapping a dummy `ping` target.
2. Ran `python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean` (4 min 17 s).
3. Called `nsys stop --session=jseval-r1`. Session entered "Generation" state.
4. `QdstrmImporter` process spawned and accumulated to **23.6 GB working set** while processing the raw event buffers. System free RAM dropped to **1.3 GB of 32 GB** (96 % used).
5. At T+11 min of Generation, `jseval-r1.nsys-rep` still 0 bytes. Killed `QdstrmImporter` + `nsys` to recover system.

**Root cause**: `--cuda-memory-usage=true` on a system-wide 4-min CUDA trace with an ML workload emits event volume that exceeds the importer's practical memory budget on 32 GB RAM.

**Retry strategy (deferred)**: drop `--cuda-memory-usage=true`; narrow to `--cuda-trace-scope=process-tree --attach=<backend-pid>` instead of system-wide; or shorten the capture window with `--max-queries 10` to reduce event volume. Any of these should keep importer RAM under ~5 GB.

**Decision**: Research 1 remains **desk-analysis-only resolved**. The 22 pp GPU-idle gap = ~3 ms per-call overhead math is strong enough to justify Batch E's probe enhancements (FP16 + batch sweep added 2026-04-19) without nsys confirmation. Empirical nsys trace would be a nice cross-check but is not blocking Batch E execution.

**Decision rule.** If nsys shows median inter-kernel gap > 2 ms OR per-call H2D+D2H > 1.5 ms → prioritise IO binding + dynamic batching in the ORT encoder call path (311-family follow-up). If gaps are < 10 % of wall → GPU isn't the ingest bottleneck; look elsewhere (chunking, file-discovery order, index-writer back-pressure).

#### 2. Gradle thermal profile under sustained compile — **RESOLVED 2026-04-19**

**Measured** via `lhm-log.ps1` capture during 3 back-to-back Gradle builds (cold daemon + warm cache 53 s / cold daemon + cold cache 76 s / warm daemon + warm cache 9 s). 199 samples at 1 Hz. Analysis: `scripts/bench/_analyze-gradle-thermal.ps1`.

| Sensor | min | p50 | p95 | max | samples ≥80 °C | samples ≥90 °C |
|---|---|---|---|---|---|---|
| CPU Package Temp (°C) | 39 | 52 | 83 | **86** | 18 (9 %) | **0 (0 %)** |
| Core Max Temp (°C) | 41 | 54 | 83 | **87** | 25 | 0 |
| CPU Package Power (W) | 32 | 62 | 148 | 161 | — | — |
| CPU Cores Power (W) | 22 | 50 | 133 | 146 | — | — |
| CPU Total Load (%) | 6 | 22 | 98 | 100 | — | — |
| P-Core #1–8 Clock avg (MHz) | 4 612 | 4 713 | 4 813 | **4 913** | — | — |

**Findings**:

1. **CPU does NOT thermally throttle during Gradle builds.** Max 86 °C; zero samples ≥90 °C. The 100 °C TjMax peaks seen in B1 do NOT recur under Gradle-shaped workloads.
2. **P-Core clocks sit at all-core-boost steady state (~4 700 MHz).** Stock all-core boost on 12700K is 4.7 GHz; the 4.9 GHz is single-core-only. We're at the expected MT ceiling, no clock drop. (Contrast with B1: single-core PassMark bursts hit 4.9 GHz because only 1 core is active.)
3. **CPU Package Power p95 = 148 W** vs PL1 stock = 125 W. Sustained slightly above PL1; PL2 (190 W) never reached.
4. **Prediction was wrong** — I predicted 10–25 % of wall above 90 °C. Actual: 0 %.

**Implication for cooling upgrade ROI on Gradle workloads specifically: ZERO.** We are not thermally limited during builds. The cooler-at-ceiling observation from B1 (Debunked Items → "BIOS PL1/PL2 raise" empirical confirmation) applies only to single-core-intense bursty workloads (PassMark subtests). For sustained MT compile + test, the cooler has plenty of thermal headroom.

**Corollary for Batch J**: test parallelism 2 → 3 is **thermally safe** — current p95 Package Power 148 W leaves room for an additional worker JVM; max temp 86 °C leaves 14 °C of headroom. No throttling risk from bumping to 3.

**Bonus — E-J-0a baseline captured**:
- True cold (no-daemon, no-cache): **76 s**
- Cold daemon + warm cache: 53 s
- Warm daemon + warm cache: 9 s

Build-cache hit-rate is ~30 % of wall (23 s delta cold vs cached). Config-cache + warm daemon is another ~44 s saving. This is a healthy, well-cached Gradle project — not the 5–10 min cold builds I initially predicted.

#### 3. Defender impact on Gradle wall — PARTIALLY RESOLVED 2026-04-19 (desk analysis; ETL capture blocked)

**Desk analysis** from `Get-MpPreference` + `fsutil devdrv query F:` (2026-04-19):
- **ExclusionPath / ExclusionExtension / ExclusionProcess: all empty.** No custom exclusions.
- `F:` is a Dev Drive — "trusted developer volume" with `WdFilter` attached. Per Dev Drive docs, Defender runs in **async (deferred) scan mode** rather than synchronous-blocking real-time scan. File operations complete immediately; scans happen after.
- `GRADLE_USER_HOME` = `F:\caches\gradle` (per daemon cmdline). Wrapper, modules-2, build outputs: all on F: → already async.
- `C:\Users\<user>\.gradle` exists but likely vestigial (legacy default location before GRADLE_USER_HOME was redirected). Probably minimal traffic during builds.
- `C:\Users\<user>\.m2` status unknown; Gradle with `mavenLocal()` would hit it, but this project mostly uses Gradle caches → probably cold.
- **Revised prediction**: Defender cost on Gradle builds on THIS machine is probably already mostly mitigated by Dev Drive async mode. Adding exclusions to F: paths is redundant (they're already async). Adding exclusions to C:\Users\<user>\.gradle might buy a tiny win if the path is actively used; likely < 2 % of build wall.

**ETL capture attempt (2026-04-19, failed)**: `New-MpPerformanceRecording` with a 20-min window ran during 3 Gradle builds but the ETL file didn't flush when I stopped the background PowerShell task early (force-stop didn't give wpr a chance to finalize). Subsequent retry attempts returned `0xc5583001 "profiles are already running"` from a stuck WPR session that `wpr -cancel` and `wpr -status` both reported as not running. Symptom was a Defender-specific session state that survived general-WPR cancellation — required reboot to clear.

**ETL capture landed (2026-04-20, post-reboot)**: `New-MpPerformanceRecording -RecordTo defender.etl -Seconds 90` around one cold `./gradlew.bat --no-daemon --no-build-cache --no-configuration-cache clean build -x test` (1 min 10 s wall). 6.4 MB ETL flushed cleanly. Parsed with `Get-MpPerformanceReport -TopProcesses 10 -TopExtensions 15 -TopScans 10`. Artefact: `docs/tempdocs/390-results/2026-04-20-defender-etw/defender.etl`.

**Empirical finding overturns desk prediction** — Dev Drive PerformanceMode does **NOT** skip Java source / class / jar files:

| Extension | Scan count | Total CPU duration | Median per scan |
|---|---|---|---|
| `.java` | 1 248 | **210.5 s** | 86 ms |
| `.class` | 3 602 | **162.7 s** | 36 ms |
| `.jar` | 803 | **133.6 s** | 47 ms |
| `.bin` | 1 477 | 34.0 s | 18 ms |
| `.proto` | 158 | 11.3 s | 17 ms |
| Plus `.json / .txt / .html / .js / .properties / .xml / .jinja / .lockfile / .dll` — another ~30 s combined | | | |

**Top process: `F:\scoop\apps\temurin25-jdk\25.0.2-10.0\bin\java.exe` — 8 016 scans, 582.6 s total cumulative CPU scan time across all threads during a 70 s wall-clock build.** Second-highest is `protoc-4.33.5-windows-x86_64.exe` (process-scan, 3.4 s — expected for an untrusted executable).

**Interpretation**: the ~582 s cumulative > 70 s wall means Defender was using roughly 8 concurrent CPU threads' worth of time scanning files during the build. On a 20-logical-thread CPU at p95 98 % load during compile, this is significant CPU contention: scan threads were competing with javac + Kotlin + protobuf compile workers.

**Projected impact of `Add-MpPreference -ExclusionPath "F:\JustSearch"`** (desk, pre-measurement): 15–30 % wall win from freeing ~8 threads of CPU contention.

**Empirical validation — MEASURED 2026-04-20**: applied the exclusion, ran the same cold `gradlew --no-daemon --no-build-cache --no-configuration-cache clean build -x test` command: **71 s with exclusion vs 70 s without = +1 s (within N=1 noise)**. **No measurable wall impact.**

**Why the scan-duration-to-wall-time intuition fails**: the raw 582 s cumulative scan CPU on 12700K's 20 logical threads ≈ 8 threads' worth. The build uses ~12 compile threads (thread-pool ceiling, not thread-count ceiling). With 20 total threads, 12 compile + 8 scan sum to 20 — fully consumed, but compile isn't waiting on scan. Dev Drive PerformanceMode's async-scan behaviour truly keeps scans off the critical path.

**Desk analysis was right for the wrong reason**: prediction of <2 % wall win was correct because Defender is off-critical-path, not because it wasn't scanning. The intermediate "scans are happening + cost CPU → should save X%" logic was wrong.

**Decision for Batch J (FINAL)**: **keep Defender exclusion dropped.** No measurable benefit. Exclusion reverted post-measurement. Batch J's only real lever remains E-J-N4 (test parallelism 2 → 3 = +20 % on test wall).

#### 4. JVM heap / GC inside the Gradle daemon — **RESOLVED 2026-04-19 (surprise: null signal)**

**Measured** by injecting `-Xlog:gc*:file=F:/JustSearch/tmp/gradle-diagnostic/gc.log:time,uptime:filecount=3,filesize=10m` into `org.gradle.jvmargs` and running 3 builds (cold-daemon+warm-cache 53 s / cold-daemon+cold-cache 76 s / warm-daemon+warm-cache 9 s). Analysis: `scripts/bench/_gc-summary.ps1`.

| Metric | Value |
|---|---|
| Total GC pauses (3 JVMs) | 218 |
| Total pause wall | **1.19 s** |
| Total JVM runtime (3 daemons) | ~167 s |
| **GC overhead at Xmx 4g** | **0.71 %** |
| Pause min / p50 / p95 / max | 0.0 / 4.8 / 14.3 / 37.0 ms |
| Mean pause | 5.5 ms |

**Findings**:

1. **GC is NOT a bottleneck.** 0.71 % overhead is ~4× lower than my 2–5 % prediction.
2. **G1GC is well-tuned for this workload.** Pauses stay under 15 ms p95. The one outlier at 37 ms is a Mixed Pause in the warm-daemon run — still short.
3. **UseCompactObjectHeaders (JEP 450) is active** and likely contributing to the low pause times — smaller heap footprint = less to mark + less to copy.

**Implication for Xmx bump 4g → 6g**: predicted saving was 1–3 % of wall based on reducing 2–5 % GC overhead. Actual GC overhead is 0.71 %; bumping Xmx could halve it (to ~0.35 %) for a saving of **≤ 0.4 %** on wall time — **below the 2 % minimum signal threshold**. Cannot be distinguished from noise even at N=5.

**Decision for Batch J**: **DROP the Xmx bump**. Null signal predicted empirically. Leaving at 4g. Revisit only if a future codebase change (e.g., 3× more modules) pushes GC overhead above 3 %.

#### 5. Thread-Director × power-plan interaction on 12700K P/E split

**Desk analysis**:
- 12700K: 8 P-cores (hyperthreaded, 16 threads) + 4 E-cores (4 threads) = 20 logical.
- ChrisTitus Ultimate power plan: `Minimum processor state = Maximum processor state = 100 %`. Pins all cores to maximum clock (P: 4.9 GHz all-core boost; E: 3.6 GHz).
- **Key interaction**: Windows Thread Director uses per-core performance/efficiency hints to the scheduler. When clock scaling is disabled (CPU states pinned), E-cores still report as "low-performance" cores via hardware hints, so Thread Director still prefers P-cores for latency-sensitive threads. However, the *core parking* mechanism (park idle E-cores) is inhibited under clock-pinned plans, which can raise idle power and also reduce Thread Director's dynamic steering information.
- Gradle workers at parallelism=2: 2 worker JVMs × ~4 compile threads each = ~8 active threads → all fit on P-cores (16 threads available). E-core usage should be OS + background only.
- Gradle workers at parallelism=3: 3 × ~4 = ~12 active threads, still P-core-fit. Jump to parallelism=4+ would start spilling to E-cores.
- **Prediction**: for parallelism=2 or 3, ChrisTitus Ultimate vs MS Ultimate Performance are within ~1–2 % of each other (both clock-pinned, both inhibit parking). MS Balanced allows normal parking + Thread Director dynamic steering, which *may* be 1–5 % faster on bursty compile loads specifically because E-cores can idle and P-cores boost a bit higher. On sustained full-MT load, ChrisTitus Ultimate should be ~equal or slightly better (no wasted cycles to clock ramp-ups).

**What requires a new run.**
- Batch H is already scoped for this (power plan 3-way A/B, N≥2 per plan). Additional finer-grain diagnostic: during each plan's Gradle build, capture `Get-Counter '\Processor Information(*)\% Processor Time'` with 1 s interval to get per-logical-core utilisation, aggregate P-core vs E-core load.

**Decision rule.** If MS Balanced ≥ 3 % better on Gradle wall than ChrisTitus Ultimate → switch default (user-facing win + power savings). If within ±2 % → keep ChrisTitus (user preference, no perf reason to change). If MS Balanced ≥ 3 % worse → confirms clock-pinning is a win for this workload; document and move on.

### Post-B1 pre-work probes (2026-04-20) — confidence-raisers before committing to implementation

Applied a "measure-before-predict" discipline before touching any remaining batch code. Four probes (total ~30 min):

| Probe | Finding | Effect on confidence | Raw artefacts |
|---|---|---|---|
| Batch G — raw `llama-bench -fa 0` vs `-fa 1` on 8B Q4_K_M (r=5 each) | **pp 4199 → 4684 tok/s (+11.5 %); tg 86.5 → 89.2 tok/s (+3.1 %)**. pp gain is solid; tg is marginal but positive. | 70 % → 92 % for `-fa`. Drop `-t 8` (tg +3 % marginal) + `--mlock` (needs `SeLockMemoryPrivilege`, not granted for this user). | `docs/tempdocs/390-results/2026-04-20-prework/llama-fa{0,1}.csv` |
| Batch F / Vite SWC — Babel-plugin audit | `modules/ui-web/vite.config.js` loads `@lingui/babel-plugin-lingui-macro` for i18n. SWC doesn't host Babel plugins. SWC swap requires **simultaneous** migration to `@lingui/swc-plugin` — two swaps at once, elevated risk for a minor-stakes change. | 65 % → **35 %, DROP.** | — |
| Batch F / sccache — toolchain audit | `modules/shell/src-tauri/.cargo/config.toml` is clean (5 lines, no existing `rustc-wrapper`). sccache 0.14.0 installed via winget at `C:\Users\<user>\AppData\Local\Microsoft\WinGet\Packages\Mozilla.sccache_Microsoft.Winget.Source_8wekyb3d8bbwe\sccache-v0.14.0-x86_64-pc-windows-msvc\sccache.exe`. Setup-ready for 391 to adopt. | 55 % → 70 % setup-ready; actual hit-rate measurement deferred to 391 (installer-rebuild scope). |  |
| E-J-N4 permanent — change-site scout | `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:36-37` has two `.orElse(2)` lines (CI-detect branch + outer fallback). Straightforward 2-line edit. | 60 % → **95 %**. |  |

### Post-B1 Gradle RAM+IO Bundle (Batch J) — **MEASURED 2026-04-19/20: E-J-N4 +20 %; Defender exclusion = null (measured post-reboot)**

**Batch J test-wall measurement (2026-04-19 20:24–20:31, warm daemon, N=3 each side):**

| Parallelism | Samples (s) | Median (s) | Range |
|---|---|---|---|
| 2 (current default) | 99.9 / 95.9 / 96.0 | **96.0** | ±2 % |
| 3 (`-PtestParallelism=3`) | 91.2 (cold-config, discarded) / 76.7 / 75.1 | **75.9** | ±1 % |

**Delta: −20.1 % on test wall, non-overlapping IQRs, well above 5 % signal threshold.** Method: `./gradlew.bat test --rerun-tasks --continue` with 165 tasks fully re-executed (not cached). Pre-existing test failures in `:modules:app-launcher:test`, `:modules:app-services:test`, `:modules:ui:test` fail at both parallelism levels — wall comparison is fair because both sides have the same failing tests.

**E-J-N4 permanent — LANDED 2026-04-20.** `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt` default changed from 2 → 3 (CI stays at 1). Verified with warm-daemon `./gradlew.bat test --rerun-tasks --continue` at 75.5 s — matches yesterday's `-PtestParallelism=3` measurement (75.1–76.7 s). Comment includes the empirical justification (86 °C max vs 100 °C TjMax; PL1/PL2 headroom from Research 2).

**Thermal check** (from Research 2 vs E-J-N4 at p=3): p=3 adds a third test JVM, but the Gradle build thermal ceiling was 86 °C with plenty of headroom (PL2 190 W vs measured p95 148 W). E-J-N4 is thermally safe.

### Batch E — full ORT encoder sweep (2026-04-20, CUDA FP16)

Measured via `scripts/bench/ort-perf-probe.py --provider cuda --runs 30 --warmup 5 --batch-sweep 1,4,16,64 --seq-len 128 --graph-opt {all|extended}`. SPLADE used `all`; embed / NER / reranker required `extended` to work around the `SimplifiedLayerNormFusion` graph-optimizer bug.

| Encoder | Model MB | batch=1 p50 | batch=16 p50 | batch=64 p50 | Best eff throughput | Batching multiplier (best / batch=1) | Knee |
|---|---|---|---|---|---|---|---|
| **SPLADE** (naver-splade-v3) | 475 | 2.13 ms | 1.69 ms | 2.21 ms | **26 938 in/s** (at batch=64) | **53.8×** | 64+ |
| **embed** (gte-multilingual-base) | 628 | 2.93 ms | 14.44 ms | 62.35 ms | **1 098 in/s** (at batch=16) | 3.4× | 16 |
| **NER** | 135 (est.) | 1.13 ms | 4.76 ms | 18.59 ms | **3 392 in/s** (at batch=64) | 3.9× | 16–64 |
| **reranker** | 130 (est.) | 2.91 ms | 13.90 ms | 60.22 ms | **1 134 in/s** (at batch=16) | 3.6× | 16 |

**Comparison to jseval production baseline** (391, scifact, 5 184 docs):

| Encoder | Java-side p50 (jseval) | Raw CUDA batch=1 p50 (this probe) | Java overhead per call | If batch=16 | If batch=64 |
|---|---|---|---|---|---|
| SPLADE | 16.1 ms | 2.13 ms | ~14 ms (87 % of call) | ~2.2 ms kernel | ~2.2 ms kernel |
| embed | 65.7 ms | 2.93 ms | ~63 ms (96 % of call) | ~14.4 ms kernel | ~62.3 ms kernel |
| NER | 2.6 ms | 1.13 ms | ~1.5 ms (58 % of call) | ~4.8 ms kernel | ~18.6 ms kernel |

**Java-side overhead (∆ between per-call jseval p50 and raw CUDA p50) dominates call cost for every encoder.** Small-batch overhead (H2D copy + kernel launch + D2H copy + Java/JNI roundtrip) is the bottleneck, not the GPU kernel itself.

**Projected pipeline speedup if Java call path were batched** (Research 1's implicit ask):
- Current scifact ingest inference: 75.4 s embed + 87.5 s SPLADE + 43.5 s NER = **206.4 s**.
- Batched-at-16 projection: (954/16 × 14.4 ms) + (5 320/16 × 1.7 ms) + (12 329/16 × 4.8 ms) = 0.86 + 0.56 + 3.70 = **~5.1 s of kernel time** (plus Java overhead per batched-call, probably ~50 ms × 1 074 = 54 s) = **~60 s total**.
- Pipeline wall 253 s → ~60 s inference + ~47 s overhead = **~107 s — ~2.4× pipeline speedup potential**.
- **BIG caveat**: batching requires Java call-path redesign (accumulator + dispatcher per encoder). Non-trivial 391 work — document at 311-family (ORT session architecture) tempdoc.

**Why SPLADE is the outlier** (17.6× at batch=16, 53.8× at batch=64): sparse-encoder architecture with wider output projection; kernel better-parallelized on small-batch. embed / reranker are standard BERT encoders and saturate GPU bandwidth at batch=16.

**Decision rule satisfied**: per-call overhead confirmed >2 ms on 3 of 4 encoders. **Research 1 hypothesis empirically validated** (batch-sweep is a clean substitute for nsys trace — we directly measured the per-call cost instead of inferring it from GPU-idle gaps).

---

### Post-B1 proposed single batch — Gradle RAM+IO Bundle (Batch J) — **SHRUNK 2026-04-19 after Phase-2 diagnostic findings**

Original design had 4 items. Phase-2 diagnostic runs (Research 2 + 3 + 4) empirically **invalidated 2 of 4**:
- **~~Xmx 4g → 6g~~ DROPPED** — Research 4 measured GC overhead at 0.71 % (not 2–5 % as predicted). Xmx bump would halve it to ~0.35 % = ~0.4 % wall win, **below the 2 % signal threshold**.
- **~~Defender exclusions~~ DROPPED** — Research 3 desk analysis confirmed F: Dev Drive async mode absorbs most of the cost already; ETL empirical capture blocked by stuck WPR state. Decision rule threshold not reached.

**Remaining contents** (2 items):
1. **E-J-N5** — remove `-XX:+UseLargePages` from `gradle.properties` (null cleanup; silent no-op without `SeLockMemoryPrivilege` as established in Debunked Items).
2. **E-J-N4** — test parallelism `maxParallelUsages` 2 → 3 (expected 10–20 % on `./gradlew.bat test` wall; RAM + thermal headroom verified empirically).

**Revised predicted impact**: **~10–20 % on `./gradlew.bat test` wall**; near-zero on `clean build -x test` wall. Batch is now genuinely an *E-J-N4-focused batch* with E-J-N5 as a no-op bystander.

**Alternative framing**: at this point Batch J is small enough that it could be merged back into Batch F (originally: Vite SWC + sccache + Test parallelism). With GC + Defender exclusions out, Batch J's separate blast radius justification evaporates. Future agent may prefer to fold E-J-N4 back into Batch F and retire Batch J as a distinct entry.

**Baseline captured (bonus)**:
- E-J-0a `./gradlew.bat --no-daemon --no-build-cache --no-configuration-cache clean build -x test`: **76 s** (one-shot 2026-04-19).
- `./gradlew.bat clean build -x test` (cold daemon, warm cache): **53 s**.
- Same command warm daemon + warm cache: **9 s**.

Future E-J-N4 pre/post should use `./gradlew.bat test` (N=5, warm daemon) — the N=5 is specifically because this E-J-N4 delta is on test wall, not build wall, and test runs have higher run-to-run variance than compile.

**Items explicitly EXCLUDED from this batch** (unchanged):
- `-XX:+UseCompactObjectHeaders` (already active per daemon cmdline).
- Raising PL1/PL2, granting `SeLockMemoryPrivilege`, enabling K2, pagefile tuning — all in Debunked Items.
- Power plan A/B (Batch H; its own measurement infrastructure).
- sccache (Batch F, Rust-scope, doesn't touch Gradle).
- Vite SWC (Batch F, ui-web-scope, doesn't touch Gradle).
- System-level hygiene (Batch C; low-perf-impact by construction).

**Thermal risk analysis (Research 2 result)**: test parallelism=3 is **thermally safe**. Gradle load measured 86 °C max (vs TjMax 100 °C), p95 83 °C, CPU Package Power p95 148 W (of PL2 190 W). Plenty of power + thermal headroom for a 3rd worker JVM.

### Fallback: individual free-tool scores (for cross-check if PassMark is suspect)

Kept so we don't have to re-research them later. Use a fallback tool only when a specific PassMark subtest shows a surprising result and we need a second opinion.

| Metric | Hardware | Expected |
|---|---|---|
| Cinebench 2024 MT | i7-12700K stock | ~1050 |
| 7-Zip MIPS MT | 12700K, DDR4-3600 | ~110 000 |
| y-cruncher 1 b π | 20 threads | ~60 s |
| MLC peak bandwidth | DDR4-3600 dual-channel | ~50 GB/s |
| MLC idle latency | DDR4-3600 CL18 | ~75–85 ns |
| DiskSpd seq-read 1 MiB Q8T1 | 990 PRO 2 TB | ~7.4 GB/s |
| DiskSpd seq-write 1 MiB Q8T1 | 990 PRO 2 TB | ~6.8 GB/s |
| DiskSpd rand-read 4K Q32T1 | 990 PRO 2 TB | ~1.2 M IOPS |
| llama-bench 8B Q4_K_M `-ngl 99` | RTX 4070 12 GB | pp 3 500–4 500 tok/s, tg 70–85 tok/s |

## System-level experiments (PC-agent owned)

| # | Experiment | Inputs | Decision rule |
|---|---|---|---|
| E-S-B1 | System sanity baseline (must run first — **LANDED 2026-04-19**) | Run the elevated orchestrator `scripts\bench\run-B1.ps1` (one UAC prompt at start). Emits `tmp/B1/passmark-b1.csv`, `passmark-b1.html`, `llama-bench.csv`, `lhm.csv`, `gpu.csv`, `nvme.jsonl`. **Measured wall: 31 min PassMark + ~1 min llama-bench ≈ 32 min.** | If any PassMark subsystem or llama-bench row >10 % below the reference-scores table → investigate that subsystem before any E-experiment. Report the CSV + telemetry peaks (package power, core / package temp, GPU temp, VRAM peak, NVMe temp) in `docs/tempdocs/390-results/<date>-B1/`. Upload PassMark baseline to pcbenchmarks.net if sharing is desired; skip if private. **First B1 verdict 2026-04-19: within envelope; no pathological subsystem. H-PC-1 cleared. Five caveats attached — see "Sanity gate verdict" subsection below (class bands unverified, CPU thermally capped, Memory Latency is cache-biased, Disk 4K QD1 is cache-inclusive, N=1 variance unknown).** |
| E-S-6 | Power plan A/B (ChrisTitus Ultimate vs MS Ultimate Performance) | Flip `powercfg /setactive`, reboot to settle, run B1 + Gradle E-J-0a (handoff, see below) | Keep the plan whose Gradle wall + B1 CPU score is ≥5 % better AND no CPU regression >3 %. |
| E-S-1 | Windows UBR .8037 → .8117 | Windows Update + reboot | Forced change; record delta on B1 + E-J-0a + E-J-1. >5 % on any row is attributable to the KB bundle. |
| E-S-9 | Install telemetry stack | HWiNFO64 + nvidia-smi dmon + smartctl poll | Unblocks per-run thermal/power capture for every subsequent experiment. |
| E-S-10 | 990 PRO heatsink + sustained-write thermal | `diskspd -w100 -d120` + 1 Hz smartctl poll | Record temp curve. If peak ≥80 °C, add / reseat heatsink. |

### `scripts/bench/passmark-b1.ptscript` (inline, for reference)

Invoke from the repo root (`F:\JustSearch`) with the `/s` flag so PassMark runs the script to completion (without `/s`, the script loads into the editor and idles — see P1 finding below). Paths are relative to PassMark's working directory.

```
# B1 — Whole-PC system sanity baseline via PassMark PerformanceTest 11.
# Covers CPU, memory, disk; 2D graphics included; 3D skipped for automation safety.
# Invoke (elevated) from F:\JustSearch:
#   "C:\Program Files\PerformanceTest\PerformanceTest64.exe" /s scripts\bench\passmark-b1.ptscript

SETDISK "F:"
SETDURATION 30
SETCPUTESTPROCESSES 20
SET3DMAX

CLEARRESULTS
RUN CPU_ALL
RUN ME_ALL
RUN DI_ALL
RUN G2D_ALL
# RUN 3D_ALL   # skipped: fullscreen 3D tests do not compose with unattended batches
EXPORTCSV "tmp\B1\passmark-b1.csv" SINGLELINE

EXPORTHTML "tmp\B1\passmark-b1.html"

EXIT
```

**Runtime — measured 2026-04-19 (first successful B1):**

**Total wall 1,880 s = 31.3 min** at SETDURATION 30, N=1. Effective cost per top-level `*_ALL` subtest ≈ **75 s** (not 30 s), because ME / DI / G2D subtests run multiple internal phases back-to-back (e.g., IOPS sweeps several queue depths in sequence, each for SETDURATION seconds). Linear extrapolation from CPU_ALL alone (as I did before the first B1) under-estimates by ~2× — CPU subtests are simpler, single-phase.

| Group | Top-level subtests | Wall (measured) |
|---|---|---|
| CPU_ALL | 8 | ~4 min |
| ME_ALL | 6 | ~9 min |
| DI_ALL | 4 | ~6 min |
| G2D_ALL | 7 | ~12 min |
| **Total** | **25** | **~31 min** (plus ~30 s startup absorbed) |

**Why N=1 for a sanity baseline.** PassMark subtests are already time-averaged internally (SETDURATION seconds of operations → one reported average). One LOOP iteration is already an averaged sample, not a single point. For comparison against pcbenchmarks.net class averages (built from millions of systems), one good run places us within the 10–20 % sanity threshold. Repeated runs are only justified when A/B-ing a specific system change where tight IQR matters. At that point, re-invoke this ptscript N times from a wrapper — each invocation is its own fully isolated sample, which is actually cleaner than an inner `LOOP` block (no `CLEARRESULTS` state concerns).

**Faster variant:** `scripts\bench\passmark-b1-quick.ptscript` — `SETDURATION 15` × N=1 → ~7 min. Use for pre/post gate on fast OS tweaks where cycle time matters more than per-subtest precision.

**Key PassMark CLI findings (P1 probe, 2026-04-19):**

- **`/s` flag is mandatory.** Without `/s`, `PerformanceTest64.exe <script>` loads the script into the editor and idles — no tests run, no output produced. This cost us two broken attempts before we spotted it. Sources: [PassMark forum: CommandLine : Script + Output result](https://forums.passmark.com/performancetest/5401-commandline-script-output-result), [Command Line Parameters to launch specific tests](https://forums.passmark.com/performancetest/48-command-line-parameters-to-launch-specific-tests).
- **Only the `*_ALL` RUN tokens are recognized** (`CPU_ALL`, `ME_ALL`, `DI_ALL`, `G2D_ALL`, `G3D_ALL`, `CD_ALL`). Individual sub-test tokens like `CPU_INTEGER`, `CPU_PRIME`, `CPU_FLOAT` produce a "Line Number: X Line: RUN …" script error and halt execution. Individual sub-test tokens are only documented inside the in-app Help, which we can't script against.
- **`EXPORTCSV "…" SINGLELINE` inside a LOOP appends per iteration** (verified in P1: iter.csv grew from 2 → 3 → 4 lines as each LOOP iteration completed). Partial data from a killed run IS salvageable when looping; not applicable to the current N=1 design.
- **`EXIT` command cleanly closes PassMark** after the script — required for an unattended orchestrator that waits on process exit.
- **PassMark spawns `PT-CPUTest64.exe` per worker process** (20 children at `SETCPUTESTPROCESSES 20`). CPU accounting against the parent `PerformanceTest64.exe` process is therefore misleading; real utilization must be measured against the whole process tree or via telemetry.
- **`SETDURATION` IS honored.** The earlier "SETDURATION ignored, sub-tests hit hard-coded minimums" observation in this tempdoc was an artefact of running PassMark without `/s` — the app was idling, not benchmarking. P1 at SETDURATION 5 measured ~5 s per CPU subtest as expected.

Flags in use:
- `SETDISK "F:"` — run disk subtests against the ReFS Dev Drive. Switch to `"C:"` if a comparison against NTFS on the same NVMe is desired (same physical drive; expect <1 % difference attributable to filesystem overhead only).
- `SETDURATION 30` — seconds per subtest. 30 s × (CPU_ALL + ME_ALL + DI_ALL + G2D_ALL) × 5 loops ≈ 15–20 min. Raise to 60 for more stable medians; lower to 10 for a quick-gate.
- `SETCPUTESTPROCESSES 20` — match the 12700K's logical thread count.
- `SET3DMAX` — 3D preset = max (unused here since `RUN 3D_ALL` is commented out; kept to retain the default if re-enabled).
- `CLEARRESULTS` inside the loop means each iteration is independent; `REPORTSUMMARYCSV` at the end emits aggregate median + each-iteration row.

---

# JustSearch Agent Scope

> **Moved to [`391-pipeline-throughput-and-variance.md`](./391-pipeline-throughput-and-variance.md) on 2026-04-19.**
>
> All JustSearch-pipeline-specific content has been relocated:
> - jseval observability map
> - Current 3-run baseline (2026-04-18) + 3-run re-measurement (2026-04-19)
> - Confirmed root causes for run-1 cold-start, SPLADE churn drops, and cross-session variance
> - Theorised resolutions (per issue, three tiers; multi-purpose-PC constraint enforced)
> - JustSearch opportunity register (E-J-N1…N12) and experiments (E-J-0a/0b/0c, E-J-1, E-J-N1…N10)
> - SPLADE counter-drift fix detail (root cause, code citations, regression test)
> - JustSearch-side handoffs (H-SQ-1, H-JS-1/2, H-INV-1/2/3) and next actions
>
> The Validation Workflow batches B / E / F / G (below in this doc) own
> their experiments in 391; A / C / H / I remain in this doc. Hardware
> Profile and Measurement Methodology are referenced from 391 — they
> stay here.

---

# Shared

## Measurement Methodology

**Scope**: applies to every experiment on both sides. Violations invalidate the run.

### Quiescence checklist (before every batch)
- Close: IDE, browser, Steam, Riot Client, Claude Code, Discord, any resident apps.
- Stop: Gradle daemon (`./gradlew.bat --stop`), Node dev server, llama-server, `justsearch-dev` stack.
- Do not run during a Defender scheduled scan window. **Windows Update auto-reboot is permanently mitigated 2026-04-20** — no need to `Stop-Service wuauserv` around benches; the WU service may be Running but UsoSvc + the WaaSMedic PerformRemediation task remain Disabled, which prevents the reboot-trigger chain. WU can still download in background and steal CPU cycles during a bench — if that matters for the measurement, `Stop-Service wuauserv` as a one-time action before the bench is cheap.
- Thermal soak: CPU package < 45 °C, GPU < 40 °C at start of first repeat. If a prior run just finished, idle 5 min.
- Ambient note: record room temperature approximately in the fingerprint.

### Environment fingerprint (one per results folder)
Write `fingerprint.txt` at the start of each results folder capturing: UBR / BIOS / NVIDIA driver / JDK / Gradle / Node / Python / Rust versions, git HEAD SHA, working-tree modification count, active power plan. Template PowerShell snippet is retained in this session's earlier drafts; the PC agent may prefer its own canonical script.

### Sample sizes + warmup
- **N = 5** for Gradle / compile / network experiments.
- **N = 3** for jseval pipeline / LLM / ORT experiments (long per-run; `jseval run` has internal repeats).
- First run after `./gradlew.bat --stop` or a reboot is **warm-up** and discarded.
- Always report **median + IQR** (25th–75th). Never accept a single point estimate.

### Signal threshold + decision rules
- Δmedian > **5 %** AND non-overlapping IQRs → real signal; apply per-experiment decision rule.
- Δmedian ∈ [2 %, 5 %] → extend by +5 repeats. If post-extension Δ > 2 % with non-overlapping IQRs, accept.
- Δmedian < 2 % OR overlapping IQRs → noise, discard.
- **Baseline noise floor (JustSearch side)**: 1.2 % CV measured on 3 scifact runs — so 2 % threshold requires N ≥ 5 for reliable detection.

### Telemetry (every experiment)
- **Primary**: HWiNFO64 free GUI CSV sensor log, poll 1 s, `Settings → Logging → Start logging` → `tmp/<label>/hwinfo.csv`. Three clicks per run.
- **GPU cross-check (optional)**: `nvidia-smi dmon -s pucvmet -d 1 -c <N> -f tmp/<label>/gpu.csv` in a parallel pane.
- **NVMe thermal (optional)**: 1 Hz `smartctl -A -j -d nvme pd1` loop for transient-spike capture on sub-minute runs.

## Validation Workflow — batched rerun strategy

Designed 2026-04-18 after a full-B1 attempt was interrupted mid-run (originally attributed to a WU-forced reboot; user confirmed 2026-04-20 that auto-reboot-after-update has been permanently mitigated and will not recur, so this class of interruption can be dropped from the risk model going forward). **Purpose**: validate all theorized improvements with the minimum number of baseline reruns, grouped by blast radius.

### Grouping rules

1. **Changes with the same blast radius share one post-baseline.** Cheap OS hygiene items cannot interfere with each other; one re-baseline after the whole set proves them all.
2. **Forced / irreversible changes go solo.** Each gets a dedicated pre/post — except Windows UBR update, which the user applies manually out-of-band (no baseline planned; rely on Batch C's post-baseline as the new reference going forward).
3. **Isolated-scope changes skip the system baseline entirely.** Vite SWC, sccache, test parallelism need only their own micro-benchmark; no B1.
4. **Strictness level**: **grouped + quick-B1 as gates** (confirmed 2026-04-18). `passmark-b1-quick.ptscript` (LOOP 3 + SETDURATION 15, ~15 min) is the default checkpoint for system-level batches. Full `passmark-b1.ptscript` only when specifically publishing class-relative numbers.

### Batches (execution order)

| # | Batch | Contents | Pre-baseline | Post-baseline | Wall |
|---|---|---|---|---|---|
| **A** | Correctness — landed 2026-04-18 | hookspath unset • `JUSTSEARCH_MODELS_DIR` removed • SPLADE counter-drift fix (other agent) | — | unit tests only | done |
| **B** | JVM flag cleanup — null experiment | E-J-N5: remove `-XX:+UseLargePages` from `gradle.properties` | **E-J-0a × 5** | E-J-0a × 5 | ~20 min |
| **C** | Free OS hygiene — bundled | S-2 SysMain off • S-3 WSearch off + `voidtools.Everything` install • S-4 Defender scan window relocate • S-5 DNS → Cloudflare + DoH • S-8 Claude session env vars (`CLAUDE_CODE_NO_FLICKER`, etc.) | **quick-B1 + E-J-0a + E-J-1** | quick-B1 + E-J-0a + E-J-1 | quick-B1 portion now ~7 min each side (N=1 SETDURATION 15). Gradle E-J-0a × 5 + jseval × 3 dominate the per-side wall; see 391 for their estimates. |
| ~~D~~ | ~~Windows UBR update~~ | **SKIPPED from formal workflow.** User installs the update manually out-of-band. No pre/post baseline scheduled; Batch C's post becomes the effective new reference once the update lands. | — | — | — |
| **E** | ORT inference diagnostic curve — **FULLY MEASURED 2026-04-20** (4 encoders × 4 batch sizes on CUDA FP16) | E-J-N6: Python probe (`scripts/bench/ort-perf-probe.py`) with `--batch-sweep` + `--graph-opt` flags (added 2026-04-19/20). CUDA 12 DLLs at `C:\tools\cuda12-ort\` on machine PATH — CUDA EP now loads cleanly in fresh shells. `--graph-opt extended` unblocks embed + NER + reranker past the `SimplifiedLayerNormFusion` bug they hit at `all`. Results table below. **Raw outputs**: `docs/tempdocs/390-results/2026-04-20-batch-E-sweep/` (4 JSONs). | — | (capture only, no A/B) | Completed. |
| **F** | Project-scoped build pipeline — independent changes | ~~E-J-N1 Vite SWC~~ (DROPPED 2026-04-20: Lingui `@lingui/babel-plugin-lingui-macro` incompat with SWC; would need simultaneous `@lingui/swc-plugin` migration) • **E-J-N3 sccache** setup-ready (installed 2026-04-20; `.cargo/config.toml` clean; adoption deferred to 391) • ~~E-J-N4 Test parallelism 2 → 3~~ (LANDED 2026-04-20 in `build-logic/.../JvmBaseConventionsPlugin.kt`; default changed 2 → 3) | — | — | Batch F effectively closed: N1 dropped, N3 handed to 391, N4 landed. |
| **G** | LLM inference flags — **`-fa` LANDED 2026-04-20** | E-J-N2 (shrunk): `-fa` flag added to `VramDetector.getRecommendedLlamaServerFlags()` for both 12GB+ and 8GB paths. Raw `llama-bench` probe confirmed +11.5 % pp / +3.1 % tg on RTX 4070 + 8B Q4_K_M. ~~`-t 8`~~ DROPPED (marginal), ~~`--mlock`~~ DROPPED (needs SeLockMemoryPrivilege, not granted). | raw `llama-bench -fa 0` (measured 2026-04-20: pp 4199, tg 86.5) | raw `llama-bench -fa 1` (measured 2026-04-20: pp 4684, tg 89.2) | Completed. Tests green (`:modules:gpu-bridge:test` + `:modules:app-inference:test`). |
| **H** | Power plan A/B — 3-way | S-6: ChrisTitus Ultimate vs MS Ultimate Performance vs MS Balanced | **quick-B1 × 2 + E-J-0a per plan** (N≥2 required — see Tier 2 "B1 N=1 variance unknown") | same | **~80–150 min, no reboots** (3 × (2 × 16 min quick-B1 + Gradle E-J-0a × 5). Correction 2026-04-18: `powercfg /setactive <GUID>` applies immediately; CPU min/max + boost-preference take effect within the next scheduler interval. Thermal soak between plans + methodology's warmup-discard rule covers the "settle" concern. **N=1 quick-B1 per plan is insufficient** — run-to-run CV ~2.4 % on CPU Mark from P1, and the expected power-plan delta on a well-tuned system is <3 %, so N=1 can't distinguish.) |
| **I** | Deferred / stretch | S-10 NVMe heatsink sustained-write thermal • S-11 backup strategy design • HVCI benchmark (PARKED per threat model) | per-item | per-item | on demand |
| **J** | **Gradle RAM+IO Bundle (B1-informed, SHRUNK 2026-04-19)** — E-J-N5 UseLargePages removal • ~~Xmx 4g → 6g~~ (dropped: GC 0.71 %) • E-J-N4 Test parallelism 2 → 3 • ~~Defender exclusions~~ (dropped: Dev Drive async) | **`./gradlew.bat test` × 5** (N=5 on test wall; parallelism=3 is the only non-null lever) | same | **~15–30 min wall total** (5 × test wall pre + 5 × test wall post). Research items 2 + 4 resolved empirically (thermal safe at p95 83 °C; GC overhead 0.71 %). Predicted win ~10–20 % on test wall; near-zero on build wall. **Subsumes Batch B**. **Candidate for folding into Batch F** now that only one real lever remains. |

### Why this order

- **A first** — already landed.
- **B** is cheapest-risk (one JVM flag removal), proves the methodology + fingerprint flow on a trivial null target. **Subsumed into J** after the 2026-04-19 B1-informed redesign — don't run standalone.
- **C** applies many hygiene tweaks in a single shot; one post-baseline validates all of them.
- **E** is a pure diagnostic that doesn't invalidate anything; can run at any safe moment.
- **F** changes are mutually independent and each touches only its own module; one sitting knocks out two (after E-J-N4 moved to J).
- **G** is isolated to the LLM path.
- **H** is last because power plan testing is the most expensive (3 reboots) and only makes sense against the final locked configuration.
- **I** on demand only.
- **J** now trivial after Research items 2 + 3 + 4 killed 2 of 4 components (Xmx bump = null; Defender = null + capture blocked). What's left is just E-J-N4 (test parallelism 2→3) with E-J-N5 as a no-op bystander. Thermal is confirmed safe (Research 2). Could reasonably be folded into Batch F.

### Baseline reuse rules

- **One quick-B1 + E-J-0a + E-J-1 snapshot per session** is enough to anchor everything in that session as long as no system-level change has landed since. Batch C's post-baseline is the anchor for all subsequent batches in the same or next adjacent session.
- **Batch B's E-J-0a post-baseline** can double as the E-J-0a pre-baseline for Batch C, if done back-to-back.
- **Batches E/F/G do not invalidate the system baseline.** They can run in any order between system-level batches without re-capturing B1.
- **Any planned reboot, Windows Update install, BIOS change, or driver update invalidates all prior baselines.** Re-anchor with fresh quick-B1 + E-J-0a + E-J-1 before continuing. (Unplanned WU-forced reboots were a concern pre-2026-04-20; now mitigated.)

### Decision gates between batches

After each batch's post-baseline, apply the methodology's signal rule (Shared → Measurement Methodology → Signal threshold):
- Δmedian > **5 %** AND non-overlapping IQRs → **real signal**; land the change, proceed.
- Δmedian ∈ **[2 %, 5 %]** → extend by +5 repeats; if post-extension Δ > 2 % with non-overlapping IQRs, accept.
- Δmedian < **2 %** OR overlapping IQRs → **noise**, discard the attribution (but the change may still be retained on other grounds — e.g., Batch C is kept regardless because its components are either hygiene fixes or free OS tweaks that don't need a perf justification).

### Time budgets (revised after first B1)

| Scenario | Total wall |
|---|---|
| First full B1 (system sanity, one-shot) | **~32 min** (measured 2026-04-19: 31 min PassMark + 1 min llama-bench) |
| quick-B1 (SETDURATION 15, N=1) | **~16 min** (recalibrated from 2.5× wall / SETDURATION factor) |
| Research items 2 + 4 | **DONE 2026-04-19** (~8 min wall, 3 builds + LHM + GC log). |
| Research item 3 (Defender ETW) | Desk-analysis done; ETL empirical capture blocked (WPR state); reattempt after reboot if needed. |
| Batch J (Gradle RAM+IO Bundle, pre/post) | **~15–30 min** — shrunk to E-J-N4 + E-J-N5 after Research 4 killed Xmx bump and Research 3 killed Defender exclusions. |
| Batch C (OS hygiene) | ~1 h (low perf impact; primarily quality-of-life) |
| Batches C→E→F→G→J (excl. power plan) | ~4 h (J replaces B) |
| Full workflow excl. H | ~4 h |
| Full workflow incl. H | ~6–7 h (Batch H bumped to N=2 quick-B1 per plan — see variance caveat) |

## Open issues — remaining uncertainties (pre-work cycle, 2026-04-19)

Tier labels reflect blast radius. Not all need resolving before any batch starts — some just inform how we interpret results.

### Tier 1 — load-bearing (resolve or acknowledge before relying on the workflow)

- **P1 — RESOLVED 2026-04-19.** `EXPORTCSV "…" SINGLELINE` inside a PassMark LOOP **does** append per iteration. Verified with `LOOP 3 { RUN CPU_ALL × SETDURATION 5 EXPORTCSV … SINGLELINE }`: iter.csv gained a row at the end of each of the three iterations (2 → 3 → 4 lines) during the run, not just at LOOP end. Wall 262.8 s, exit 0. Partial data from a killed LOOP IS salvageable (not relevant to the current N=1 B1 design, but confirms the mechanism for any future multi-iteration variant). Results: `tmp/P1/passmark-p1-iter.csv`, `tmp/P1/passmark-p1-summary.csv`, `tmp/P1/P1-watch.log`.
  - Prior reproduction attempts failed because PassMark was launched without the `/s` flag (app loaded the script into the editor and idled — see next bullet). Root cause documented in the inline ptscript "Key PassMark CLI findings" section.

- **PassMark's `SETDURATION` IS honoured — RESOLVED 2026-04-19.** P1 at SETDURATION 5 measured ~5 s per CPU subtest as designed (3 iters × 8 subtests × 5 s = 120 s pure, 262.8 s total wall, remainder = ~47 s / iter overhead). The earlier "SETDURATION ignored" finding was an artefact of running PassMark **without `/s`** — the app sits idle when given only a script path. With `/s scripts\bench\<file>.ptscript`, the script executes as written. Implications:
  - Wall-time budgets now reliable: B1 full (SETDURATION 30 × 25 subtests, N=1) ≈ **14–16 min**; quick-B1 (SETDURATION 15, N=1) ≈ **7 min**.
  - Batch H 3-way power-plan A/B: 3 × (quick-B1 + E-J-0a) ≈ **35–40 min** (no reboots).
  - Mandatory: add `/s` to every PassMark invocation; add `EXIT` as the last line of every ptscript so PassMark closes cleanly and the orchestrator unblocks on `Start-Process -Wait`.

- **Windows Update auto-reboot — DEMOTED TO NON-CONCERN 2026-04-20.** Previously Tier 1: this session had disabled `UsoSvc` (Disabled+Stopped) and the `\Microsoft\Windows\WaaSMedic\PerformRemediation` scheduled task; `wuauserv` was Stopped manually but has a triggered-start that reactivates it on boot. Post-reboot 2026-04-20, `wuauserv` is Running (expected), `UsoSvc` + WaaSMedic task are still Disabled. **User confirmed 2026-04-20 that auto-reboot-after-update has been permanently mitigated via a mechanism unrelated to the services disabled here — this risk can be dropped from the bench-safety model going forward.** The service-state tweaks can stay as-is or be reverted without affecting the reboot-safety guarantee. No rollback script required.

### Tier 2 — significant, affects result quality

- **Noise floor — partially answered (jseval pipeline only).** The 391 3-run re-measurement (2026-04-19) gave: same-SHA day-to-day CV ≈ **4.4 %** at N=3 with one cold run, ≈ **3 %** without the cold run. **Methodology implication:** the 5 % signal threshold is at the edge of cross-session noise; raise to ~9 % for single-cold-run comparisons or N ≥ 5 with cold-run discard. **Still unknown for Gradle wall (E-J-0a)** — needs the same N≥10-of-unchanged-build characterisation that the original Tier 2 row called for. PassMark CV also unknown until first successful B1 lands.

- **PassMark reference scores captured + interrogated — RESOLVED 2026-04-19.** First successful B1 after the P1 fix (`/s` flag + `EXIT` + `CPU_ALL`-only RUN tokens). Scores populate the reference-scores table above. Cross-validated against telemetry: Disk Seq R/W within 0.2–2 % of LHM-observed peaks. Caveats captured inline with the scores + in the "Telemetry interrogation" subsection: CPU thermally capped, Memory "Latency" is cache-biased, Disk 4K QD1 is cache-inclusive, class bands are unverified. H-PC-1 closed.

- **B1 N=1 variance unknown — NEW 2026-04-19.** P1 measured ~2.4 % CV on CPU Mark at N=3. The first B1 is N=1 by design (PassMark subtests are internally time-averaged; one sample is already an average, not a point). This is sufficient for a one-shot sanity gate but **insufficient for any numeric-delta claim**. For future A/B comparisons (Batch H power-plan, post-Windows-Update re-capture, etc.), at minimum N=2 pre + N=2 post is needed. The PassMark Rating 17 493 from this run has an unknown noise halo — plausibly ±2–5 % from run-to-run CV, plus unknown day-to-day drift from ambient temp / background process state. Do not cite this as the "the" baseline without pairing it with at least one same-config re-run.

- **FP32 at batch=1 seq=128 is NOT a representative GPU baseline** (P2 finding). Reranker on CUDA FP32 was **slower than CPU** (27.6 ms vs 21.6 ms). Reasons:
  - CUDA kernel-launch overhead (~10 µs × 100+ ORT ops per BERT forward = ~1 ms just in launches) dominates at seq=128.
  - H2D + D2H memcopy per call has fixed cost unrelated to model size.
  - Ada (RTX 4070) tensor cores are optimised for FP16 / BF16 / INT8; FP32 runs on normal SM lanes without the 5-8× tensor-core speed-up.
  - batch=1 gives no batch dimension to parallelise across.
  - JustSearch production uses FP16 ONNX variants (`model_fp16.onnx`) + batched inputs + a warm Java ORT session + possibly IO binding; none of those conditions are reproduced by my current probe.
  - **Consequence**: the CPU numbers already captured (`tmp/bench-tool-validation/ort/*.json`) are useful only as probe-internal reference points, **not as "the ORT baseline" for Batch E**.
  - **Follow-up to address during Batch E**: enhance `scripts/bench/ort-perf-probe.py` to (1) prefer `*_fp16.onnx` variants where available, (2) accept a `--batch` flag and sweep 1 / 4 / 16 / 64, (3) optionally use IO binding (pre-allocated GPU tensors) to isolate memcopy overhead, (4) test TensorRT EP (confirmed available) as a second data point.

- **Python ORT CUDA EP path — RESOLVED 2026-04-19.** Durable DLL copy landed at `C:\tools\cuda12-ort\` (1.99 GB, 19 files: cublas64_12, cublasLt64_12, cudart64_12, cudnn*_9, cufft64_11, onnxruntime*, `onnxruntime_providers_tensorrt.dll` for future TRT EP use). Added to machine PATH permanently. The Tauri build artefact at `modules/shell/src-tauri/target/…/cuda12/` remains the canonical source (1.99 GB copy each build); the durable path survives `cargo clean`. Future agents: if the Tauri build ships a newer ORT/CUDA version, refresh `C:\tools\cuda12-ort\` by re-running the one-line copy. 

- **Nothing is committed.** Tonight's working tree has 10+ uncommitted files from two agents (correctness fixes, tempdoc rewrites, bench scripts, orchestrator, LHM poll, ORT probe). None committed. Single crash or catastrophic session failure loses all of it. The JustSearch-side correctness patches (`WritePathOps.java` SPLADE fix and friends) are detailed in 391 and listed there as Next-Action #1.

### Tier 3 — operational hygiene

- **Recurring scripting bug classes without golden patterns.** Caused silent failures multiple times tonight + 2026-04-19:
  - `Start-Process -ArgumentList` with embedded `$env:`-style quoted strings parses wrong (UAC test crashed at "PositionalParameterNotFound").
  - MSYS path handling in bash (`/c/Program Files/...`) gives "permission denied" when calling Windows `.exe` paths through MSYS (first B1 orchestrator failure). Same class: MSYS also mangles Unix-style switches that look like paths (`taskkill /F` → "Invalid argument `F:/`") — use `powershell Stop-Process` instead.
  - `Split-Path -Parent` depth miscounts: run-P1.ps1 used 2 levels where the caller sat 3 levels deep, producing `F:\JustSearch\scripts\scripts\bench\...`. run-B1.ps1 had the same bug, fixed earlier. **Golden rule:** a script at `repo\scripts\bench\foo.ps1` needs **three** `Split-Path -Parent` calls to reach the repo root.
  - Non-ASCII characters (em-dash, arrow, middle-dot) inside PowerShell **string literals** break parsing when the file is read as ANSI by PS 5.1 (no UTF-8 BOM). Non-ASCII inside **comments** is tolerated. Both run-P1.ps1 and run-B1.ps1 hit this — cost a diagnostic cycle each. Always stick to ASCII inside `"..."` or ensure the file has a BOM.
  - PassMark CLI: `PerformanceTest64.exe <script>` opens the script in the editor and idles. To actually run a ptscript you need `/s`, and to close PassMark afterwards the ptscript needs `EXIT` as its final line. Without `/s`, CPU stays at ~5 % while you wait indefinitely — easy to misread as "test running slowly".
  - PassMark error dialogs (e.g., "Line 18: RUN CPU_INTEGER") block the script but the parent process keeps running at 0 CPU; the orchestrator's `Start-Process -Wait` won't return until a human clicks OK. If orchestrating unattended runs, consider parsing PassMark's `/s` exit code — non-zero means script error.
  - Silent exit codes: `tee` returns 0 even if the piped command failed; `Start-Process -Verb RunAs` returns 0 when UAC was dismissed; Monitor `grep` detected binary output and emitted only "Binary file matches".
  - Each of these cost a diagnostic cycle. No reusable helper module exists; every new script re-introduces these risks.
  - **Action**: when we next write a new elevated / cross-shell launcher, write it once as a reusable `scripts/bench/_lib/launch-elevated.ps1` (or similar) and reuse from batch scripts.

- **No session-start / session-end protocol documented.** Resuming this work on a new day forces me to re-probe: what services are Stopped? which power plan is active? what telemetry is still running? what tmp files are stale? Existing `/start` skill covers codebase orientation but knows nothing about bench state. Proposed: add a `scripts/bench/check-bench-state.ps1` that snapshots the 10 relevant state slots in one call.

- **PassMark-native reference scores — POPULATED 2026-04-19.** Scores + per-subtest table populated from `docs/tempdocs/390-results/2026-04-19-B1/passmark-b1.csv`. pcbenchmarks.net cross-reference still pending (noted as caveat #1 in "Sanity gate verdict"); not blocking any batch. Future agent interested in rigour: submit the `.pmb` baseline file PassMark generated (under `C:\ProgramData\PassMark\PerformanceTest11\Reference Baselines` or as browser-uploaded from the HTML report) for class-comparison URL.

- **Batch sequencing assumes best-case environment.** Workflow assumes: no unplanned reboots, no WU-forced downtime, no thermal throttling, no Defender scheduled scan overlap, no edit conflicts with the JustSearch agent. No fallback playbook exists for any of these. Practical mitigation: any batch that fails >30 min into a run, document the failure mode, restart from the batch boundary, not from scratch.

## Handoffs between agents

Explicit contracts. The owning agent creates the row; the receiving agent updates when settled. **JustSearch-side handoffs (H-SQ-1, H-JS-1, H-JS-2, H-INV-1/2/3) live in 391** — only PC-owned and shared rows below.

| Handoff | From → To | Payload | Closure criterion |
|---|---|---|---|
| **H-PC-1** | PC → JustSearch | B1 system-sanity result table + telemetry peaks. Flag if any row >10 % below reference. | **CLOSED 2026-04-19.** First B1 landed (`tmp/B1/passmark-b1.csv`). All subsystem Marks within envelope; telemetry cross-validated Disk Seq R/W; no subsystem pathologically low. CPU thermally capped (100 °C peaks, cooler at its hardware ceiling — see Debunked "BIOS PL1/PL2 raise"). Five caveats attached to the scores (see "Sanity gate verdict" bullets). JustSearch agent (391) unblocked. |
| **H-PC-2** | PC → JustSearch | Power plan decision from E-S-6 (ChrisTitus vs MS Ultimate Performance). | JustSearch agent (391) pins the plan for subsequent E-J-0a runs to avoid mixing plans across JustSearch experiments. |
| **H-PC-3** | PC → JustSearch | Windows UBR delta from E-S-1. | JustSearch agent (391) re-captures E-J-0a + E-J-1 baseline after update and logs the delta against the pre-update baseline in `history.db`. |
| **H-PC-4** | PC → shared | Telemetry tool choice | **CLOSED 2026-04-18.** Canonical path is HWiNFO64 free GUI CSV sensor log at 1 s poll (three-click manual start, no time cap on CSV log), with `nvidia-smi dmon -s pucvmet -d 1 -c N -f gpu.csv` as optional NVML cross-check, and 1 Hz `smartctl -A -j -d nvme pd1` loop as optional NVMe thermal capture during sustained-write runs. See Methodology → Telemetry. Novabench free tier rejected (no CLI, no CSV export). HWiNFO Pro not required — personal GUI-start is acceptable for ≤ 10 min runs. |
| **H-SHARED-1** | shared → observability | `ner_total` non-determinism across back-to-back scifact runs (baseline 5 300 / 6 100 / 7 300; 2026-04-19 follow-up 5 / 7 296 / 7 298). | Agent investigating chunking / file-discovery order either explains the variance or pins it (E-J-N12 candidate in 391). |

## Next actions (PC-agent only — JustSearch actions in 391)

1. [PC] **Install benchmark tools — DONE 2026-04-18 evening**: PassMark PT 11.1.1009 (direct download, winget manifest was stale), HWiNFO64 8.46, DiskSpd 2.2.0, llama.cpp b8838 CUDA-13.1 + cudart, smartmontools 7.5 (earlier). Nsight Systems + CrystalDiskInfo not needed for B1. Smoke-tested: DiskSpd 4K Q1T1 read latency 14 µs p50 (NVMe healthy); llama-bench 8B Q4_K_M `-ngl 99`: pp 1256 tok/s (short), tg 81 tok/s (matches reference band 70–85); HWiNFO binary signed + self-elevates. PassMark ptscript smoke: see `tmp/bench-tool-validation/passmark/smoketest.csv` once the first CPU_ALL short loop completes.
2. **Windows Update** to ≥ .8117 — user applies out-of-band without a scheduled pre/post baseline (see "Validation Workflow" → Batch D explicitly SKIPPED). Any baseline captured before the update is considered invalidated once it lands.
3. **Execute the batched Validation Workflow** (see section above). Post-B1 recommended sequence:
   - **Research items 2 + 3 first** (Gradle thermal profile + Defender ETW) — ~30 min shared wall, provides inputs for Batch J's gating decisions.
   - **Batch J** — Gradle RAM+IO Bundle (E-J-N5 + Xmx bump + E-J-N4 + conditional Defender exclusions). Highest expected Gradle-wall win; subsumes Batch B.
   - **Batch C** — Free OS hygiene bundle (S-2 SysMain, S-3 WSearch + Everything, S-4 Defender scan window, S-5 DNS DoH, S-8 Claude env vars). Single quick-B1 + E-J-0a + E-J-1 pre/post. Low-perf-impact by construction — quality-of-life bundle, not a perf lever.
   - **Batch H** — Power plan 3-way A/B (S-6 ChrisTitus Ultimate vs MS Ultimate Performance vs MS Balanced). **N≥2 per plan** per the post-B1 variance caveat. Do last.
   - **Research items 1, 4, 5** — schedule against existing batches (1 → alongside Batch E; 4 → alongside Batch J's pre-baseline with GC logs on; 5 → alongside Batch H per-core counters).
   - **Batch I (on demand)** — E-S-10 NVMe heatsink sustained-write thermal • HVCI benchmark (PARKED per general-purpose threat model). S-11 (backup) dropped as out-of-scope.
   - **JustSearch-owned batches E / F / G** — see 391 for ownership and detailed experiments; Batch B subsumed into J.
4. **JustSearch next actions** (commit the SPLADE fix + correctness patches; H-SQ-1 register promotion; Issue B/D targeted fixes; methodology refinement) — **see 391 § Next Actions**.

## Session Artifacts

- `C:\Program Files\smartmontools\bin\smartctl.exe` — installed 2026-04-18 afternoon via winget.
- **`C:\Program Files\PerformanceTest\PerformanceTest64.exe`** — PassMark PerformanceTest **11.1.1009.0** (copyright 1999-2025). User uninstalled the prior E:\ copy; reinstalled via direct download from passmark.com (winget manifest was stale for 11.1.1008) with `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`. Authenticode signature valid (CN=PassMark Software Pty Ltd). Sub-test binaries present: `PT-CPUTest64`, `PT-D3D11Test`, `PT-D3D12Test64`, `PT-DatabaseTest64`, `PT-DBBenchmark64`, `PT-OpenCV64`, `PT-BulletPhysics64`, `PT-NBodyGravity`, `PT-PDFTest`, `PT-InternetSpeedTest`, plus `clpeak64` (OpenCL peak) and `QJulia4D` / `Fluid3D` / `Mandel` visual tests.
- **`C:\Program Files\HWiNFO64\HWiNFO64.EXE`** — HWiNFO64 **8.46-5960**, installed via `winget install REALiX.HWiNFO` (requires UAC at install time). Authenticode signature valid (CN=REALiX, s.r.o.). First-launch dialog was auto-handled; HWiNFO self-elevates on run for sensor access (SMBus / MSR / PCI config space).
- **`C:\Users\<user>\AppData\Local\Microsoft\WinGet\Packages\Microsoft.DiskSpd_Microsoft.Winget.Source_8wekyb3d8bbwe\amd64\diskspd.exe`** — DiskSpd **2.2.0** (Microsoft), installed via `winget install Microsoft.DiskSpd`. Path alias `diskspd` registered in the winget links directory. For scripts, invoke by full path to avoid PATH drift.
- **`C:\Program Files\llama.cpp\llama-bench.exe`** — llama.cpp **b8838** Windows CUDA-13.1 build, extracted from GitHub release zip + matching `cudart-llama-bin-win-cuda-13.1-x64.zip`. Auto-detects RTX 4070 12 281 MiB, loads `ggml-cuda.dll` backend + `ggml-cpu-alderlake.dll` for the 12700K's µarch.
- **`C:\Program Files\NVIDIA Corporation\Nsight Systems 2026.2.1\`** — NVIDIA Nsight Systems **2026.2.1.210**. Installed 2026-04-19 via `msiexec /i NsightSystems-2026.2.1.210-3763964.msi /quiet /norestart`. `target-windows-x64\nsys.exe` on machine PATH. Empirical trace capture for Research 1 failed on memory pressure (see Research 1 details); binary is ready for a future attempt with lighter flags.
- **`C:\tools\cuda12-ort\`** — durable copy of CUDA 12 runtime DLLs (19 files, 1.99 GB) needed by pip-installed `onnxruntime-gpu`. Source: `modules/shell/src-tauri/target/x86_64-pc-windows-msvc/release/resources/headless/native-bin/onnxruntime/cuda12/`. Added to machine PATH 2026-04-19. Unblocks Batch E (ORT probe).
- `scripts/bench/passmark-b1.ptscript` — N=1 × SETDURATION 30 × (CPU_ALL + ME_ALL + DI_ALL + G2D_ALL), 3D intentionally skipped. Updated 2026-04-19 (dropped LOOP wrapper after N=1 decision; added `EXIT`).
- `scripts/bench/passmark-b1-quick.ptscript` — N=1 × SETDURATION 15, same subtest set, for ~7 min pre/post A/B gating.
- `scripts/bench/passmark-p1-reprobe.ptscript` + `scripts/bench/run-P1.ps1` — P1 probe that resolved the SINGLELINE and SETDURATION questions (see Tier 1). Retained for audit; rerun costs ~4 min.
- `scripts/bench/_analyze-B1.ps1` — B1 telemetry aggregator. Manual CSV parse (Import-Csv chokes on LHM duplicate columns like `D3D Copy|Load`). Reports min/p50/p95/max for CPU Temp/Power/Load, P-Core clocks, GPU Temp/Load/Power, NVMe Temp, RAM load, and motherboard fan control + RPM. Source of the "Telemetry interrogation" table above.
- `scripts/bench/_fan-correlation.ps1` — bins fan control % by CPU Package Temp. Used to confirm whether the CPU fan was already at max when the CPU hit TjMax (it was → cooler is the binding constraint).
- `scripts/bench/_check-leftovers.ps1` — diagnostic: lists any nvidia-smi / lhm-log powershell / smartctl processes that survived an aborted B1 run. Used once to confirm the pre-fix run-B1.ps1 leaked telemetry on the llama-bench failure path.
- `scripts/bench/_analyze-gradle-thermal.ps1` — thermal + clock + power summary during Gradle builds (same column schema as `_analyze-B1.ps1`, specialised for `tmp/gradle-diagnostic/lhm.csv`). Source of the Research 2 resolved-table.
- `scripts/bench/_gc-summary.ps1` — parses JDK `-Xlog:gc*` output (rotation-aware for `filecount=3`), sums total pause time, reports pause percentile distribution. Source of the Research 4 "0.71 % GC overhead" finding.
- `scripts/bench/check-bench-state.ps1` — session-start state snapshot (Tier 3 infra item). Prints elevation, power plan, WU/Defender service state, bench-tool processes, Defender exclusion list, Dev Drive status, dev-stack listening port, disk free, git branch, recent tmp/ dirs, 390 results folders. Run at the top of any new bench session.
- `scripts/bench/_lib/launch-elevated.ps1` — dot-sourceable helpers (`Invoke-NativeExe`, `Test-ElevationOrExit`) that extract the bug-prone patterns from run-B1.ps1 / run-P1.ps1 (stderr split, elevation check). Clear-Tier-3 action item.
- `scripts/bench/_add-nsys-path.ps1`, `scripts/bench/_add-cuda12-path.ps1` — one-shot PATH-maintenance scripts used 2026-04-19 to add Nsight Systems + `C:\tools\cuda12-ort\` to the machine PATH. Kept for audit; re-running is idempotent.
- `scripts/bench/_nsys-check.ps1` — diagnostic: lists nsys + QdstrmImporter processes, their RAM usage, and current nsys-rep file size. Used during the Research 1 debugging to confirm Generation was mem-bound.
- `scripts/bench/_probe-nsight.ps1` — one-shot URL probe confirming the NVIDIA Nsight Systems MSI is publicly accessible (HEAD request, returns 200). Retained for audit.
- `scripts/bench/ort-perf-probe.py` — ENHANCED 2026-04-19 with `--batch N` + `--batch-sweep 1,4,16,64` + `--seq-len N`. Output now emits `batch_results[]` array per invocation instead of a single latency block. FP16 support was already present (user just passes `model_fp16.onnx`). IO-binding + TensorRT EP sweep still pending.
- `tmp/gradle-diagnostic/` — Phase 2 outputs, 2026-04-19 (`gc.log` + rotations, `lhm.csv` 199 rows / 3.3 min). Source for Research 2 thermal table + Research 4 GC overhead. Retain until committed to a results folder or deleted.
- `scripts/bench/passmark-smoketest.ptscript` — 5 s × 1 loop × CPU_ALL smoke-test variant, used to validate the ptscript-parse + CSV-emit path without running the full ~14 min B1.
- `tmp/bench-tool-validation/{passmark,hwinfo,diskspd,llama}/` — per-tool smoke outputs.
- `docs/tempdocs/390-results/2026-04-19-B1/` — first successful B1 run, 2026-04-19 (`passmark-b1.csv` + `.html`, `llama-bench.csv` + `.stderr`, `lhm.csv` 1.7 MB / 1537 rows, `gpu.csv` nvidia-smi dmon, `nvme.jsonl` smartctl 1 Hz, `orchestrator.log`). Moved from `tmp/B1/` 2026-04-19 to canonical location. Source of every number in the reference-scores + telemetry-interrogation sections. Future B1 re-runs should land in `tmp/B1/` (per `run-B1.ps1`) and be moved into their own `docs/tempdocs/390-results/<date>-B1/` subfolder on completion.
- `tmp/P1/` — P1 probe output that resolved the `/s` + SINGLELINE + SETDURATION questions (`passmark-p1-iter.csv`, `passmark-p1-summary.csv`, `P1-watch.log`).
- `docs/tempdocs/390-results/` — per-run result folders; `2026-04-18-baseline/` seeded by the JustSearch agent for the 3× scifact baseline (kept here for cross-agent visibility; the JustSearch baseline data itself is documented in 391).
- `docs/tempdocs/390-system-optimization.md` — this file (whole-PC scope after the 2026-04-19 split).
- **JustSearch artefacts** (jseval baselines, working-tree correctness fixes, SPLADE regression test) — **see 391 § Session Artifacts**.

## Sources

- Firmware / hardware: Samsung 990 PRO 2 TB `8B2QJXD7` cited via Puget Systems 2023-03-14, Tom's Hardware, Samsung consumer-storage support (Dec 2025 firmware).
- JVM UseLargePages: Shipilëv "JVM Anatomy Quarks #2", JDK-8266489, JDK-8267042.
- JDK 25 + Gradle 9.4 + Kotlin 2.3 compat: adoptium.net/support, docs.gradle.org/9.4/userguide/compatibility, kotlinlang.org/docs/whatsnew23.
- Develocity Build Scans: `settings.gradle.kts:87-109`, scans.gradle.com free tier.
- **JustSearch-specific sources** (SPLADE code trail, pre-existing pipeline baselines from 311 / 322 / 334 / 343, jseval framework, ORT optimised-graph cache mechanism, worker.log evidence) — **see 391 § Sources**.
