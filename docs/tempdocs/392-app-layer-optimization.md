---
title: "392 — App-Layer System Optimization"
---

# 392 — App-Layer System Optimization

**Status:** Done (closed 2026-04-22).
**Created:** 2026-04-19.
**Rewritten:** 2026-04-19 (r1: NVCP integration), 2026-04-19 (r2: critical cleanup — see Change log).
**Scope split:** 390 (system) + 391 (pipeline) + **392 (app-layer)** — all three are active and cross-reference each other.

**Note on numbering:** 390 line 91 references "items previously debunked in 391/392"; that earlier 392 was either consolidated or retired and no file exists. This document is a net-new 392 covering a scope neither 390 nor 391 owns. The carry-forward debunked list (below) preserves the history.

---

## Scope

**Owned here** — end-user application layer. Covers both **performance/resource** and **quality-of-experience** optimizations:
- Application install / uninstall / replacement
- Per-app launch flags (shortcut `.lnk` Arguments)
- Browser-level flags and extensions
- Terminal / shell integration (not terminal emulator *choice* if debunked)
- Per-application NVCP 3D profiles (distinct from debunked global Prefer-Max-Perf)
- App-level bloatware removal
- In-app configuration walkthroughs
- Video / display quality features (RTX VSR, RTX HDR, driver-level frame gen)

**Explicitly NOT owned** — defer to:
- **390** — Windows services, registry, BIOS, drivers, synthetic system benchmarks
- **391** — JustSearch pipeline, jseval baselines, Gradle / JVM / Node / Vite / ORT / llama-server tuning

**Validation principle**: app-layer changes are observed under real workload, not validated by synthetic benches (PassMark B1, jseval). See § Measurement approach for the specific per-item-class methods.

---

## Cross-references

- **Hardware Profile** → 390 § Hardware Profile (2026-04-18).
- **Measurement Methodology** (quiescence, N sizes, signal thresholds) → 390 § Measurement Methodology. Applies when an A-* item is A/B'd, which is rare.
- **Debunked items carry-forward** from 390 line 91 → reproduced in § Debunked below for app-layer-relevant subset only.

---

## Implemented (2026-04-19 session)

All reversible. Status: DONE.

| # | Item | Action | Verification | Reversibility path |
|---|------|--------|--------------|---------------------|
| A-1 | Thorium install (AVX2, M138.0.7204.303) | Downloaded `thorium_AVX2_mini_installer.exe` (184 MB) from `github.com/Alex313031/Thorium-Win/releases/tag/M138.0.7204.303`, ran silent per-user installer | `%LOCALAPPDATA%\Thorium\Application\thorium.exe` present; winget reports `ARP\User\X64\Thorium` v138.0.7204.303 | Apps & Features → Uninstall |
| A-2 | Thorium launch flags appended to shortcuts | Desktop + Start Menu `.lnk` via `WScript.Shell` COM. Flags: `--process-per-site --enable-zero-copy --ignore-gpu-blocklist --enable-gpu-rasterization --enable-features=ParallelDownloading --disable-features=CalculateNativeWinOcclusion` | Verified after-edit Arguments string matches expected | Backup file: `C:\Users\<user>\shortcut-backups-20260419-124328.txt` — contains original Arguments per `.lnk`; restore by setting Arguments empty via same COM script |
| A-3 | Steam minimal-mode launch flags appended | Per-user Start Menu `.lnk` edited with same COM script. Flags: `-nofriendsui -no-browser -silent`. (System-wide `C:\ProgramData\...\Steam.lnk` edit failed — needs admin; per-user shortcut wins in Start Menu search so no critical gap.) | `.lnk` Arguments string matches | Same backup file |
| A-4 | Warp install (v0.2026.04.15.08.45.stable_02) | `winget install Warp.Warp --exact --silent`. Installed VC++ 2015+.x64 redist dependency. Per-user install. | `%LOCALAPPDATA%\Programs\Warp\Warp.exe` present; winget reports `Warp.Warp` at specified version | `winget uninstall Warp.Warp` |
| A-5 | Intel Driver & Support Assistant uninstalled | `winget uninstall Intel.IntelDriverAndSupportAssistant`. Initial `--silent` flag caused hung winget waiting on suppressed UAC; killing the outer process let the underlying MSI uninstaller complete cleanly. | `winget list` → "No installed package found"; `tasklist` shows no `DSAService.exe` / `DSATray.exe` | Reinstall from https://www.intel.com/content/www/us/en/support/detect.html |
| A-6 | Steam in-client toggles applied | User-performed per cheatsheet delivered in chat: Low performance mode ON; GPU accelerated rendering in web views OFF; Animated avatars/frames OFF; Overlay OFF globally (with per-game re-enable path documented); Download cache cleared; Low bandwidth mode (as applicable). | User confirmed implemented | Re-toggle in Steam UI per path |
| A-7 | Warp first-launch configuration | User-performed: signed in; selected Git Bash as default shell (matches CLAUDE.md pin); enabled Local-only mode (Settings → Privacy) to disable telemetry and cloud AI; verified Claude Code runs inside Warp. | User confirmed implemented | Settings → Privacy → re-toggle Local-only mode; Settings → Shell → change default |

**Audit artefacts** (enumerated before proposing cleanup; read-only observation):
- `C:\Users\<user>\software-audit-20260419-131028\installed-apps.txt` — `winget list --source winget` snapshot (91 lines)
- `C:\Users\<user>\software-audit-20260419-131028\running-processes.txt` — `Get-Process` top-40 by `WorkingSet64`
- `C:\Users\<user>\software-audit-20260419-131028\startup-entries.txt` — Startup folders + `HKCU/HKLM\...\Run` registry keys

Top pre-cleanup RAM draw (snapshot): `claude` × 3 = 2.1 GB (expected — 3 live sessions); `thorium` aggregate ~1.4 GB (just launched, settles); `steamwebhelper` × 3 = 664 MB (reduced by A-6); `MsMpEng` (Defender) 417 MB (out of scope — 390); `DSAService + DSATray` 217 MB (freed by A-5).

**Verification rigor note**: verification strength varies across rows. A-1 (Thorium install) + A-2/A-3 (shortcut flags) are verified by **file/artefact check** — flag-take-effect-at-runtime was not re-verified (would require launching + checking `chrome://version` command line). A-4 (Warp) + A-5 (Intel DSA) are verified by **runtime observation** (winget + tasklist). A-6 (Steam toggles) + A-7 (Warp config) are verified by **user assertion only** — no measurement artefact. If any DONE row later shows unexpected behavior, re-verify at the appropriate layer.

---

## Deferred (user-flagged for later)

Approved changes with timing held because user actively uses the affected component during this session.

| # | Item | Current → Target | Command |
|---|------|------------------|---------|
| A-D1 | Windows Terminal | 1.18.10301.0 → 1.24.10921.0 | `winget upgrade Microsoft.WindowsTerminal` |
| A-D2 | PawnIO (kernel-IO driver for HWiNFO sensor reads) | 2.1.0.0 → 2.2.0 | `winget upgrade namazso.PawnIO` |
| A-D3 | .NET Runtime 8 + ASP.NET Core 8 + Desktop Runtime 8 | 8.0.14 → 8.0.26 | `winget upgrade Microsoft.DotNet.Runtime.8 Microsoft.DotNet.AspNetCore.8 Microsoft.DotNet.DesktopRuntime.8` |

**Re-entry trigger**: next time the user is not actively using these components (reboot / session break / explicit signal).

---

## Opportunity Register — PENDING

Theorized app-layer items, awaiting explicit user approval. Ordered by expected real-workload impact. Each row is defensible as *not* already debunked in 390/391.

| # | Item | Expected impact | Reversibility |
|---|------|-----------------|----------------|
| A-8 | NVCP — CUDA Sysmem Fallback Policy = "Prefer No Sysmem Fallback" | Distinct from debunked global Prefer-Max-Perf. Default ("Prefer Sysmem Fallback") silently spills VRAM overcommit to system RAM with ~100× latency penalty (~10× slowdown reported in community Stable Diffusion tests). Explicit "No Fallback" fails fast, exposing regressions under ORT / llama workloads. Diagnostic + preventive. Setting introduced in driver **546.01** (Dec 2023); user on 595.79 ✓. | NVCP → Manage 3D settings → restore default |
| A-9 | NVCP — per-app 3D profiles (LoL, CK3, `java.exe`) | Per-application differentiation is distinct from the debunked global Prefer-Max-Perf (see Decision principle #9). See **§ A-9 recipes** below for each `.exe`'s spec. `thorium.exe` was originally included but removed after research review (see Debunked: `thorium.exe Threaded Optimization = Off`). | Remove app profile in NVCP |
| A-10 | Thorium `chrome://flags` layer | Layers over `.lnk` launch flags in A-2. Candidates: `#smooth-scrolling` Enabled; `#enable-gpu-rasterization` Enabled (forced at flag level); `#enable-parallel-downloading` Enabled; `#enable-experimental-web-platform-features` **Disabled** (shrinks security surface); `#memory-saver-multi-state-mode` Aggressive (if many tabs). | Reset each flag to Default in the same UI |
| A-11 | uBlock Origin in Thorium (single extension) | "Load less" is the largest browser perf lever. uBO blocks per-page tracker JavaScript; substantially reduces RAM / CPU per tab. Manifest V3 version works on Chromium 138. Complementary to **390's S-5** (NextDNS/Cloudflare) — uBO blocks at the page, DNS blocks at resolution. | Extensions → Remove |
| A-12 | Warp — Git Bash shell integration (OSC 133) | Enables per-command blocks in Warp: jump-to-prompt with `Ctrl+↑/↓`, collapsible per-command output, exit-code badges. Meaningful ergonomic gain for long Claude Code / Gradle output streams. One extra file sourced from `~/.bashrc`; near-zero perf cost. | Remove `source` line from `~/.bashrc` |
| A-13 | RTSS (RivaTuner Statistics Server) frame cap for LoL | Not a resource optimization — a frame-pacing optimization. Capping at monitor-refresh − 3 yields more consistent frame times than uncapped, reducing input-latency variance. Standalone RTSS works without MSI Afterburner. Scoped to LoL here because LoL is the user's latency-sensitive title (Vanguard retention in Debunked section implies active use). RTSS itself is game-agnostic — if future use cases emerge (CK3 pacing, other titles), widen the scope. | Uninstall / remove global or per-profile cap |
| A-14 | PWA-install heavy web apps in Thorium | Standalone-window PWA for sites held open long-term (GitHub, webmail, chat). Separate renderer-process lineage from the tab ecosystem → lighter than keeping the same sites as pinned tabs; discourages tab-accumulation pattern; easy to kill. | Uninstall PWA from Thorium's `chrome://apps` or Settings |
| A-15 | NVCP — RTX Video Super Resolution + RTX Video HDR (for Thorium) | **Quality** lever, not perf — AI upscaling + SDR→HDR10 mapping on video playback in any Chromium-based browser (Thorium is Chromium 138 ✓). RTX VSR: AI upscaling removes compression artifacts, scales SD→1080p or 1080p→4K via Tensor cores. RTX HDR: remaps SDR video to HDR10 using a deep-learning network trained on a wide content variety. Requires RTX 30/40 series (4070 ✓) and driver ≥530 (user on 595.79 ✓). Negligible GPU load on a 4070 12 GB. Path: NVCP → Video → "Adjust video image settings" → enable Super Resolution (Quality 1–4) + RTX HDR toggle. | NVCP → Video → restore defaults |
| A-16 | NVIDIA Smooth Motion for CK3 (driver-level frame generation) | RTX 40-series driver-level frame generation, officially enabled for 40-series in driver 590.26+ (now mainstream). Doubles perceived FPS in any DX11/DX12 game without game-level SDK integration — similar mechanism to AMD's Fluid Motion Frames. **Restricted to CK3**: defensible because CK3 is turn-based — input latency from frame gen doesn't matter, and late-game map-pan bogs down due to character-count sim processing (frame gen gives perceived smoothness when the sim can't push real frames). **Excluded from LoL**: LoL already runs hundreds of FPS; frame gen adds latency, hurts competitive play. Enable via NVIDIA App → Settings → Graphics → Smooth Motion toggle + per-game profile. | NVIDIA App → toggle off per-game |
| A-17 | NVIDIA App — Automatic Tuning (GPU OC scanner) | **App-layer feature with hardware-tweak adjacent behavior; gated on compute-stability validation.** One-click scanner in NVIDIA App → System → Performance tab. Runs 10–30 min scan at idle, incrementally raises GPU core-clock (and optionally memory-clock) offset, applies as V/F curve profile loaded by NVIDIA App at boot. Typical result on RTX 40-series: +50–150 MHz core, +2–7 % in-game FPS. **Warranty-guaranteed by NVIDIA** (explicit) — unique among OC tools. Persistence: profile held by the NVIDIA App service; uninstalling the app reverts card to stock. **Risk specific to this user**: scanner's internal stability tests are *graphics workloads* (shader stress, rasterization). "Graphics-stable" ≠ "compute-stable" — a frequency offset that passes scanner can still produce silently-wrong CUDA FP32 results under ORT / SPLADE / llama inference. Compute errors manifest as wrong numbers, not visible artifacts. Feature is "medium-risk" for mixed gaming + inference workload until compute correctness is re-verified. **Prerequisite before enabling**: capture pre-OC compute reference outputs (see H-A-1 for sequencing). | Disable via NVIDIA App toggle = **instant** revert. Re-enable later = **full 20–30 min rescan** (not instantaneous). Uninstalling NVIDIA App also reverts. |

**Visibility gap acknowledged**: full debunked tables of 391 and the historical 392 have not been read end-to-end. Only the one-line summary on 390 line 91 was consulted. Residual risk applies to **every** PENDING row (not only A-11/A-13) — A-15, A-16, A-17 are also net-new and uncross-checked. Before promoting any PENDING row to DONE, grep 391's debunked tables for the item by name.

---

## A-9 recipes — per-app NVCP profiles

Each entry below is the concrete `.exe` configuration for NVCP → Manage 3D settings → Program Settings. Apply only the rows listed; leave all other settings at "Use global setting" / default.

### A-9a — League of Legends (`League of Legends.exe`)

Scope: competitive latency-sensitive gaming. LoL doesn't support NVIDIA Reflex natively, so driver-side NCP settings are the only latency lever.

| Setting | Value | Rationale / source |
|---|---|---|
| Low Latency Mode | **Ultra** | Driver shortens render queue aggressively; best available without game Reflex support. (1v9.gg, switchbladegaming 2026) |
| V-Sync | **Off** | Use G-SYNC if available; plain V-Sync adds latency. |
| MFAA (Multi-Frame Sampled AA) | **Off** | Thrashes on low-poly art style; visible stutter risk. |
| Texture Filtering — Quality | **High Performance** | Marginal visual loss at LoL art direction; FPS uplift per community consensus. |
| Texture Filtering — Trilinear Optimization | **On** | Free perf, no visible artifact on LoL. |
| Max Frame Rate | **Off** | Let LoL's in-game cap handle it. Driver cap here fights the game's logic. |
| Threaded Optimization | **Auto** | **Not "Off"** — the "browsers don't benefit from TO" heuristic that once implied "also off for games" is dated; 12700K benefits from TO. |
| Power Management Mode | **Prefer Max Performance** *(per-app; derived, not cited)* | Global Prefer-Max-Perf is debunked (390 line 91). Per-app use is defended by Decision principle #9 — the mechanism is scoped to the `.exe`, not desktop-wide. **However**: cited sources (1v9.gg, switchbladegaming) recommend it *globally*; per-app scoping is my derivation, not independently cited research. Treat as "defensible extrapolation" pending direct citation. |

### A-9b — `java.exe` (Gradle + ORT CUDA)

Scope: Java processes running Gradle builds or ORT CUDA kernels.

| Setting | Value | Rationale |
|---|---|---|
| Shader Cache Size | **Unlimited** (or at least 100 GB) | Default 10 GB thrashes across repeated CUDA kernel compilations. Gradle rebuilds + ORT model loads both benefit from persistent kernel cache. |

All other settings: use global / default.

**Caveat**: applies to `java.exe` generically — **any** Java process. If the user runs multiple JVMs on the same host with different workload profiles, per-exe targeting won't separate them. Acceptable trade-off here given the user's only Java workload is JustSearch.

### A-9c — Crusader Kings III (`ck3.exe`)

Scope: turn-based grand strategy. Latency-insensitive; frame smoothness matters more than peak FPS.

| Setting | Value | Rationale |
|---|---|---|
| (most settings) | Use global / default | CK3 doesn't benefit from competitive-gaming tuning. |
| Smooth Motion (via A-16 in NVIDIA App, not NVCP) | **On** (when A-16 lands) | Driver-level frame gen for late-game map-pan smoothness. Handled by A-16, not this recipe. |

Effectively A-9c is a no-op row in NVCP Program Settings; listed here for completeness so future agents don't re-propose per-app CK3 tuning unnecessarily.

---

## Debunked — do not re-propose

### From this session's user decisions (2026-04-19)

| Item | Why rejected |
|---|---|
| Uninstall Google Chrome (now that Thorium is installed) | User keeps Chrome for specific sites (DRM / Chrome-only extensions). Thorium is additive, not replacement. |
| Uninstall Riot Vanguard | User is an active LoL player; keeps kernel anti-cheat loaded full-time. Also matches 391/392 debunked: "Riot Vanguard removal" on general-purpose threat model. |
| Uninstall LibreHardwareMonitor | User retains alongside HWiNFO; redundancy accepted. |
| IDE research (VSCodium / Cursor / JetBrains Toolbox) | User workflow is editor-less: Claude Code CLI + browser only. No IDE to optimize. |
| Terminal emulator alternatives (WezTerm / Alacritty / Wave / Ghostty) | User chose Warp (A-4) as daily driver. Windows Terminal kept as fallback. |
| Shell alternatives (PowerShell 7 / Nushell / fish / zsh-on-Windows) | User retains Git Bash per CLAUDE.md pin. Claude Code compat is load-bearing. |
| Paradox Launcher skip for Crusader Kings III (`-skiplauncher`) | Not selected in cleanup round. |
| NVCP — `thorium.exe` Threaded Optimization = Off | **Reversed spec** — original A-9 proposed this, research contradicts on modern 8+ core CPUs. The "browsers don't benefit from TO" heuristic is dated (pre-2020 CPU era). 12700K qualifies for TO benefit; leave at Auto globally. Do not add TO override to `thorium.exe` profile. |
| NVCP — global Max Frame Rate cap (e.g., 500) | **Nuanced rejection.** Global cap at a high ceiling (e.g., 500 FPS) is defensible — prevents idle/menu runaway FPS burning power and heat for no visible gain — but **strictly worse than per-app caps via A-9 or A-13**. Community consensus is "per-app if you can, otherwise nothing." Residual gotcha: interferes with synthetic-benchmark ceiling measurement; edge-case game engine frame-rate-dependent logic. Verdict: defer to per-app (A-9 / A-13). |
| NVCP — Background Application Max Frame Rate | Known bugs with Electron apps (Discord, VSCode, Warp — all Chromium renderers), YouTube frame drops when alt-tabbed in Vivaldi/Chromium browsers, input lag in some games when alt-tabbed. GitHub: `electron/electron #40208`. Not worth the edge-case breakage. |
| NVIDIA Studio Driver swap (from Game Ready 595.79) | Research confirms **no performance difference for CUDA inference** between Studio and Game Ready. Studio is more conservatively QA'd for creative apps but that doesn't translate to ORT / llama gains. Game Ready 595.79 stays. |
| Lossless Scaling (third-party frame gen) | Redundant with A-16 (NVIDIA Smooth Motion) now that Smooth Motion is officially enabled on RTX 40-series. Keep A-16 if frame gen is wanted for CK3; don't add a duplicate. |
| MSI Afterburner undervolt (for heat/noise reduction) | Out of scope per "hardware tweaks already optimized" constraint. App-executed but effectively hardware tuning of V/F curve. If heat/noise becomes an issue, revisit — for now deferred. |
| NVIDIA Profile Inspector | Legitimate tool for exposing hidden driver settings, but nothing in current A-* register requires it. Revisit only if A-8 or A-9 need tuning beyond what NVCP exposes. |
| Special K injection for LoL | Riot Vanguard is kernel-level anti-cheat; DLL injection into Vanguard-protected process gets flagged as tampering → ban. Not a "maybe later" risk, a "immediately on injection" risk. Historical ban-wave documentation on Vanguard-protected titles (Valorant). Never enable on LoL. |
| DXVK (DirectX→Vulkan translation) for LoL | Same Vanguard problem — swapping out core rendering DLLs (`d3d9.dll` / `d3d11.dll` / `dxgi.dll`) triggers anti-cheat. DXVK is safe for single-player / non-AC titles only. |
| NVIDIA Reflex for LoL | LoL does not support Reflex natively (no SDK integration in the engine). NVCP Low Latency Mode = Ultra (covered by A-9) is the correct fallback. Reflex is moot for LoL regardless of any config change. |

### Carried from 390 line 91 — app-layer-relevant subset

Items rejected at system/hardware scope that would have surfaced here too.

| Item | Original scope | App-layer delta |
|---|---|---|
| NV Prefer-Max-Perf (global NVCP) | 391/392 | Global setting debunked. A-9 is **per-app** profile differentiation — different mechanism, kept live. |
| Process Lasso | 391/392 | Rejected outright. |
| Steam tier-2C (download tier exploit) | 391/392 | Rejected outright. |
| Steam small-mode | 391/392 | Rejected outright. |
| HAGS (Hardware-Accelerated GPU Scheduling) toggle | 391/392 | Windows-scope; not re-proposed here. |
| visual-effects-disable | 391/392 | Windows-scope; not re-proposed here. |

### Full 390-line-91 carry-forward (for reference, includes non-app-layer items)

`DisablePagingExecutive`, `NtfsMemoryUsage=2`, `EnablePrefetcher`, GPU VRAM as system RAM, legacy `bcdedit`, CR 2T, tRFC lowering, AVX-512 microcode mod, CPU undervolt, HAGS toggle, NV Prefer-Max-Perf, Steam tier-2C, Process Lasso, small-mode, Thread-Director XP toggle, Gear 1 on B760, visual-effects-disable.

---

## Decision log

Design choices worth preserving for future-me:

1. **Additive over replacement for browsers and terminals (specifically).** Thorium added alongside Chrome; Warp added alongside Windows Terminal. Both are reversible by the user choosing the old shortcut. This keeps fallback paths intact and avoids compatibility surprises. **Scope limit**: this principle does not apply to bloatware removal (A-5 Intel DSA was a full uninstall, correctly), only to user-facing tools with compatibility surface.
2. **Shortcut edits have a durable backup.** `C:\Users\<user>\shortcut-backups-20260419-124328.txt` contains original Arguments for Thorium × 2 shortcuts + Steam × 1. Restore path: re-run the same PowerShell COM loop with `$lnk.Arguments = ""`.
3. **System-wide Steam shortcut (`C:\ProgramData\...`) was not edited** — admin elevation required, and the per-user copy at `%APPDATA%\...\Start Menu\Programs\Steam\Steam.lnk` takes precedence in Start Menu search. Accepted as non-critical gap.
4. **Audit-before-propose became the new default mid-session** after the user pointed out that generic recommendations (Process Lasso, NVCP Prefer-Max-Perf) duplicated 390's debunked list. The audit under `software-audit-20260419-131028/` is the evidence base for targeting A-5 and sizing A-11 / A-14.
5. **Vanguard retention and A-13 scope are linked, not identical.** A-13 is currently *scoped to* LoL (per register wording), so if LoL usage drops, A-13's current target becomes moot. But RTSS itself is game-agnostic — it would remain potentially useful for CK3 pacing or other titles. The coupling is on scope, not on the tool. If LoL drops, revisit A-13's scope and Vanguard uninstall together; don't assume RTSS as a whole is moot.
6. **Warp telemetry posture is user-explicit.** A-7 includes Local-only mode ON. Any future Warp feature that requires cloud (e.g., Warp AI) is opt-in per this decision.
7. **Claude Code host shell is not negotiable.** Git Bash is pinned by CLAUDE.md and by Claude Code's generated bash-style commands. Any future terminal / shell experiment must preserve Git Bash as the host shell.
8. **Compute-stability ≠ graphics-stability.** Any GPU OC / UV tool in the A-* register that was validated only with graphics workloads (shader stress, rasterization) must be independently re-validated against compute paths (ORT / SPLADE / llama inference) before being trusted under JustSearch workload. Silent FP32 corruption under overclock won't show as visible artifact — it shows as wrong embedding / SPLADE / llama output. Specific application: **A-17 NVIDIA App Auto Tuning** requires a JustSearch sanity suite before landing as DONE.
9. **Per-app profile differentiation survives global debunking.** 390 debunked *global* Prefer-Max-Perf; that doesn't preclude per-app profiles with the same setting in NVCP's Program Settings tab. The mechanism is different — global affects idle / browser / desktop, per-app scopes to the `.exe`. Verify any NVCP setting against this when deciding reject-or-accept.
10. **Vanguard anti-cheat as permanent governor for third-party injection tools.** As long as Riot Vanguard is installed (A-D retention), any tool relying on DLL injection / core-API swap is banned-by-anti-cheat regardless of upside: Special K, DXVK, ReShade on LoL, and anything similar. This applies permanently — don't re-propose injection-based optimizations for LoL.
11. **Prefer warranty-guaranteed over warranty-safe when outcomes are comparable.** NVIDIA explicitly warranties their own Automatic Tuning (A-17). Third-party OC via MSI Afterburner / GPU Tweak / Profile Inspector is warranty-safe in practice (NVIDIA tolerates it) but not warranty-guaranteed. **Decision rule**: when a first-party tool achieves the same outcome as a third-party tool within acceptable margin, pick the first-party one. A-17 over Afterburner holds because their OC gains are comparable (~+80 MHz vs ~+120 MHz). If a third-party tool delivers materially more (e.g., 2× the gain, or undervolt that the first-party doesn't offer), re-evaluate on expected value.

---

## Measurement approach for A-* items

App-layer wins rarely register in synthetic benchmarks. Per-item evaluation:

| Item class | Evaluation method | Signal threshold |
|---|---|---|
| Resource reduction (A-5, A-11) | `Get-Process` before/after snapshot; 30-min HWiNFO CSV under representative workload | Δ RAM ≥ 100 MB sustained, or elimination of a background process |
| Ergonomic (A-12, A-14) | Subjective — single-workflow diary for 3 days | User reports improvement / regression explicitly |
| Per-app NVCP (A-8, A-9) | Requires a workload that exercises the setting. A-8: deliberate ORT model overcommit (A-8 is a diagnostic — "can we surface silent fallback?" not "does it make anything faster"). A-9: LoL session with in-game FPS overlay + NVCP per-app profile toggle. | A-8: observable error instead of slowdown. A-9: Δ frame-time P99 ≥ 5 % |
| Browser (A-10, A-11, A-14) | RAM per tab via `Shift+Esc` task manager in Thorium; page-load subjective feel | Δ per-tab RAM ≥ 20 %, or explicit user observation |
| Frame pacing (A-13) | RTSS frametime graph overlay in LoL for 1 match pre/post | Frametime stddev reduction ≥ 10 % at same average FPS |
| Video quality (A-15) | Subjective side-by-side of same YouTube clip pre/post RTX VSR + RTX HDR toggle, at common resolutions (480p → 1080p, 1080p → 2160p); HWiNFO GPU-utilization sample during playback | User explicitly reports quality improvement; GPU utilization during playback increases by ≤ 10 percentage points (above that, the quality feature is competing with other workloads); no stutter / dropped frames in browser playback stats |
| Frame gen for turn-based (A-16) | In-game FPS overlay + subjective smoothness rating in CK3 late-game map pan; side-by-side of same save file pre/post toggle | Perceived smoothness improvement at visibly higher output FPS (≥ 1.5× reported by overlay); no input-latency complaint from the user (CK3 latency threshold is lax, qualitative) |
| GPU auto-OC + compute correctness (A-17) | **Two-phase, sequenced.** Phase 0 (prerequisite): capture pre-OC compute reference outputs — see H-A-1. Phase 1: gaming FPS uplift via in-game counter, **N ≥ 5 runs per title** (matches 390's signal-detection threshold for reliable ≥ 2 % Δ), across LoL + CK3 + one CPU-light GPU-heavy title if available. Phase 2 (gating): JustSearch compute-correctness re-validation — re-run jseval with embedding determinism check + SPLADE counter check + llama-bench output diff against Phase-0 reference outputs. | Phase 1: Δ FPS ≥ 3 % median across titles with non-overlapping IQRs (per 390 § Measurement Methodology). Phase 2 (gating): **zero** diff in embeddings / SPLADE scores / llama outputs vs Phase-0 reference. Any deviation → revert auto-tune immediately and block A-17 from landing. |

**Explicitly not applicable**: 390's PassMark B1 workflow. A-* items target paths PassMark doesn't exercise. Do not attempt B1 as validation for this register.

---

## Future items — noticed, not yet claimed

Placeholder for observations during this session that didn't rise to PENDING yet.

- **Free Download Manager 6.33.2.6656** — installed, user retention unknown. FDM has had bundled-adware CVE history (2023); alternatives `aria2c` (CLI, scriptable) or JDownloader2 exist. Flag if user mentions using FDM.
- **Samsung Magician** for 990 PRO — hardware-side debunked (drive already on latest `8B2QJXD7` firmware, PercentageUsed=0 %). App-layer value (SMART health trend, TBW monitoring) may still exist alongside HWiNFO. Low priority.
- **NVIDIA ICAT** (image comparison tool) — enthusiast tool for side-by-side A/B of upscaling quality. Potentially relevant if A-15 RTX VSR quality tuning becomes a topic. Low priority.

*(NVIDIA Profile Inspector is tracked in Debunked with a concrete re-entry trigger — "Revisit only if A-8 or A-9 need tuning beyond what NVCP exposes." Not duplicated here.)*

---

## Open questions — known unknowns

Items where the answer is load-bearing for a decision but isn't currently on record. Tracked so future-me doesn't forget they're unknowns.

| # | Question | Blocks | How to resolve |
|---|----------|--------|----------------|
| Q-1 | Does 391's full debunked table include any of A-11 (uBO), A-13 (RTSS), A-15 (RTX VSR), A-16 (Smooth Motion), A-17 (Auto Tuning)? | Any of those moving PENDING → DONE | `grep -i <item> docs/tempdocs/391-*.md` before landing |
| Q-2 | Is "NVCP Power Management Mode = Prefer Max Performance per-app for LoL" community-cited, or only my derivation from Principle #9? | A-9a's final row (currently marked "derived, not cited") | Targeted search for "LoL per-app power management" in community tuning guides, or test empirically: enable per-app vs leave default, measure Δ P99 frame time |
| Q-3 | Does A-15 RTX VSR impose measurable GPU-utilization overhead when a compute workload (ORT / llama) is concurrent with browser video playback? | A-15 landing cleanly — if yes, may need conditional enable (toggle VSR off during inference sessions) | HWiNFO GPU-util log during (a) browser video only, (b) browser video + concurrent `jseval run`. Δ > 15 % = concerning |
| Q-4 | Do NVCP per-app profiles (A-9) and NVIDIA App Smooth Motion per-game (A-16) and NVIDIA App Auto Tuning (A-17) override each other, layer additively, or conflict silently? | A-17 landing meaningfully — if A-9's Power Management per-app = Max Perf conflicts with Auto Tune's V/F curve, outcome is undefined | Test empirically: enable one at a time, then combinations; observe reported clocks via `nvidia-smi dmon` |
| Q-5 | Does 392 close when its PENDING register empties (all DONE/DEBUNKED), or does it stay open as a permanent app-layer reference? | Lifecycle / when to mark Status = Closed | Decide with user; document in next rewrite |

---

## Lifecycle

Unlike 390 (system-bench workflow with concrete completion) and 391 (JustSearch pipeline experiments with baseline targets), this doc's nature is closer to a **permanent register of app-layer decisions**. Candidate completion criteria:

- **Soft close**: all PENDING items are either DONE or DEBUNKED, no new items expected in the current session arc. Status flips to "Dormant." Reopens on next app-layer decision.
- **Hard close**: never — app-layer decisions recur over the life of the machine. Better to keep 392 perpetually Active and append.

**Working assumption**: soft close. When PENDING is empty and no item has been added for a calendar month, flip Status to Dormant. Any new app-layer decision reopens it with an appended rewrite note.

(Q-5 in Open questions tracks confirmation of this.)

---

## Handoffs

### H-A-1 — A-17 compute-correctness validation (392 → 391)

**Trigger**: user approves A-17 (NVIDIA App Automatic Tuning) as PENDING → in-progress.

**Status**: open

**Sequencing** (load-bearing — do not reorder):

| Phase | Owner | Step | Artefact |
|---|---|---|---|
| 0 (prerequisite) | **392 agent** (pre-OC) | Capture compute reference at stock clocks. Run `jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean --json` + `llama-bench -fa 1 -t 8 -r 5`. Save outputs: embedding vectors (sampled N=10 docs), SPLADE term-weight vectors (sampled N=10 docs), llama-bench pp/tg scores with stddev. | Results folder `docs/tempdocs/392-results/<date>-A17-phase0/` |
| 1 | User | Enable A-17 in NVIDIA App. Scanner runs 20–30 min at idle. Profile applied. | NVIDIA App reports "Auto-tuned" state |
| 2a (gaming perf) | 392 agent | FPS uplift measurement per A-17 Measurement approach (N≥5 runs × 3 titles). | `docs/tempdocs/392-results/<date>-A17-phase2a/fps.csv` |
| 2b (compute correctness, gating) | **391 agent** | Re-run Phase 0 commands post-OC. Diff outputs vs Phase 0 reference. | `docs/tempdocs/392-results/<date>-A17-phase2b/diff.txt` |

**Decision rule**: Phase 2b diff must be **zero** on all three measures (embedding vectors bit-identical, SPLADE term weights bit-identical, llama-bench outputs within measurement noise). Any non-zero diff → **392 agent immediately disables A-17 via NVIDIA App toggle** (instant revert). A-17 stays PENDING; record failure in Debunked with specific diff observed.

**Communication mechanism**: results folder (shared path above) + inline session chat. 391 agent is notified via user message; if 391 not active when user enables A-17, **392 agent blocks landing** until 391 can run Phase 2b. A-17 cannot move to DONE without Phase 2b artefact.

**Revert non-triviality note**: toggling A-17 off is instant, but re-enabling (e.g., after a future driver update) triggers a **full 20–30 min rescan**. Plan Phase 2b on the same session as Phase 1 enable, not days later.

### A-8 (candidate handoff, not yet active)

**Trigger**: A-8 surfaces a VRAM-overcommit bug in JustSearch's ORT path.
**Contents**: finding + diagnosis flows to 391 for pipeline-level fix. Until triggered, A-8 is pure-diagnostic and self-contained in 392.

---

## Change log

- **2026-04-19 (r0 / created)**: Initial tempdoc. Implemented A-1..A-7, Deferred A-D1..A-D3, PENDING A-8..A-14, session Debunked + carry-forward from 390 line 91, Decision log principles #1–#7, Measurement approach, Future items, empty Handoffs.
- **2026-04-19 (r1 / NVCP integration)**: Integrated NVCP research findings. A-9 LoL profile expanded (Texture Filtering, Max Frame Rate, Trilinear Optimization rows added). `thorium.exe Threaded Optimization = Off` removed from A-9 and added to Debunked (research contradicted the "browsers don't benefit" heuristic on modern 8+ core CPUs). Added A-15 (RTX VSR + RTX HDR), A-16 (Smooth Motion for CK3), A-17 (NVIDIA App Auto Tuning, with compute-stability caveat). Expanded Debunked session-decisions with Background App Max Frame Rate, Studio Driver swap, Lossless Scaling, MSI Afterburner UV, Profile Inspector, global Max Frame Rate, Special K / DXVK / Reflex for LoL. Added Decision log principles #8–#11. Promoted first handoff row (H-A-1 placeholder) for A-17 validation to 391.
- **2026-04-19 (r2 / critical cleanup)**: Removed Profile Inspector from Future items (was duplicated with Debunked — contradiction resolved in favor of Debunked with re-entry trigger). Decomposed A-9 overloaded row into A-9 reference row + new **§ A-9 recipes** subsection with A-9a (LoL), A-9b (java.exe), A-9c (CK3) as dedicated tables. Fixed A-13 cross-reference (previously pointed to "A-D decision" — Vanguard retention is actually in Debunked, not Deferred). Added "Open questions" section (Q-1..Q-5) capturing known unknowns. Added "Lifecycle" section with soft-close model. Fully specified H-A-1 with phased sequencing, ownership, artefacts, communication mechanism, revert non-triviality. Trimmed header "Rewritten" prose (moved detail to this Change log). Expanded scope statement to explicitly include quality-of-experience items (A-15/A-16 now scope-consistent). Strengthened Principle #1 (scope limit), #5 (Vanguard↔A-13 coupling clarified), #11 (decision rule for warranty-safe vs guaranteed). Marked A-9a Power Management per-app as "derived, not cited" with Q-2 tracking resolution. Updated A-15 measurement row with concrete thresholds. Updated A-17 measurement row with N≥5 requirement (matches 390's reliability threshold), Phase 0 prerequisite, sequencing link to H-A-1. Added verification rigor note to Implemented section. Expanded A-17 reversibility (disable=instant, re-enable=rescan). Removed non-actionable A-9 `java.exe` sub-handoff. Clarified 390's-S-5 vs this-doc-A-5 distinction in A-11. Expanded visibility-gap warning to cover all PENDING rows, not only A-11/A-13.
