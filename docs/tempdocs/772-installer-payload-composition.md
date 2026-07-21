---
title: "Installer payload composition: what belongs in the base installer versus the consent-gated download pack — the base installer ships an inference runtime it cannot run, and the pack mechanism that would carry it already exists"
type: tempdocs
status: "open — ANALYSIS ONLY (2026-07-21). Evidence measured against a real 815 MB CI artifact (run 29514086160, 743 files, 177 PE binaries, every PE signature-checked). Takeover investigation (2026-07-21, multiple passes) independently re-verified all citations (clean), resolved Q7 (§F: native-bin combined is ~10.4% of installed payload), found a much bigger payload outside the original native-bin-only scope (§G: lib/worker/onnxruntime_gpu-1.24.3.jar is 34.3%, of which ~18.65% is dead Linux native binaries on this Windows-only product and ~15.12% is a Windows CUDA provider DLL inert until GPU+pack), and found a second major lever (§H: the embedded WebView2 offline installer is 203.65 MB / 18.83% — the second-largest single component, ~60% bigger than Tauri's own documented estimate). Resolved Q3 (pack mechanism is NOT ready for a CPU-mandatory package — 4 concrete gaps found), Q4 (only 2 of 9 scripts are real gates, both already characterized), the should_sign upstream claim (confirmed against Tauri source), and Q8's licensing question (ONNX Runtime is MIT; no Windows-only upstream artifact exists). No decision reached, no split recommended, nothing removed or implemented; §Open questions (1-10, several now resolved inline) are for the owner. See §Takeover verdict."
created: 2026-07-21
updated: 2026-07-21 (takeover investigation)
author: agent (subagent investigation), founder-directed distribution work (2026-07-21)
category: distribution / installer
related:
  - 760-installer-distribution-readiness  # distribution TRUST mechanics — disjoint scope, see §Scope
  - 759-mcpb-standalone-feasibility       # sibling distribution lane
  - 761-linux-build-cost-estimate         # sibling distribution lane
  - 374-installer-alpha                   # the CPU-only alpha decision this payload inherits
---

> Charter. The base installer is 815 MB and ships `llama-server.exe` plus ~19 unsigned support
> DLLs, but not the models that runtime needs — those arrive later via a consent-gated ~9 GB
> download. So the installer carries an inference runtime that is inert until a second, larger
> transfer completes. Tesseract OCR (74 DLLs) is a parallel case. This tempdoc states the payload-
> composition problem, records what was measured, enumerates options and their tradeoffs, and
> poses the questions a decision needs. **It does not reach a decision.**

# 772 — installer payload composition

## §Scope — boundary against 760

Tempdoc 760 (`installer-distribution-readiness`, open, created 2026-07-21) covers distribution
**trust**: code signing, `SHA256SUMS`, winget manifest, silent install, SmartScreen documentation.
This tempdoc covers payload **composition**: what bytes are in the installer at all.

The two touch at exactly one point — per-PE signing cost scales with the number of unsigned PEs in
the payload (§Signing consequence). 760's gap table, its Phase 2 list, and its credential-mode
finding are not restated or re-litigated here. 760's out-of-scope observation about inconsistent
size figures is referenced in §Evidence C, not duplicated.

## §Evidence — measured 2026-07-21

### A. The artifact

A real CI installer artifact was downloaded and fully extracted: run `29514086160`, built
2026-07-16, **815 MB**, **743 files**, **177 PE binaries**. `Get-AuthenticodeSignature` was run on
every PE. That build took the `--no-sign` branch (CI calls `package-installer-win.ps1` without
`-Release`; see 760's signing-drop-in row), so **every valid signature found in it is a vendor's**,
not ours.

| Directory | PE files | Vendor-signed? |
|---|---|---|
| `native-bin/tesseract` | 74 | **No** — MSYS2 mingw-w64 builds, never Authenticode-signed |
| `native-bin/llama-server` | 21 | 19 unsigned; 2 Microsoft-signed |
| `runtime/bin` (+ `/server`) | 75 | **All vendor-signed** (Temurin JRE; jlink copies signed DLLs verbatim) |
| `$PLUGINSDIR` (NSIS) | 4 | Signed pre-`makensis` from Tauri's toolset |
| `resources/vc_redist.x64.exe` | 1 | Microsoft-signed |
| `$TEMP/MicrosoftEdgeWebView2RuntimeInstaller.exe` | 1 | Microsoft-signed |
| installer root `JustSearch.exe` | 1 | Main binary |

Totals reconcile: 74 + 21 + 75 + 4 + 1 + 1 + 1 = 177. Unsigned: 74 + 19 = **93**.

### B. Where the payload is assembled

- `modules/shell/src-tauri/tauri.conf.json:16-18` — `bundle.targets` is `["nsis"]` only.
- `modules/shell/src-tauri/tauri.conf.json:19-22` — `bundle.resources` is exactly
  `resources/headless/**/*` and `resources/vc_redist.x64.exe`. **There is no `externalBin` key**
  (verified by grep). Everything under `native-bin/` therefore enters the bundle as a *resource*,
  through the `headless/` tree, not as a declared sidecar.
- `modules/ui/build.gradle.kts:1396-1481` — the `bundleSidecarResources` Sync task that fills that
  tree. Relevant staging sites:
  - `:1439-1443` — llama-server (CPU-only), `into("native-bin/llama-server")`, Windows-host-gated.
  - `:1446-1468` — Tesseract runtime + its manifest + `NOTICE-TESSERACT.txt`,
    `NOTICE-JBIGKIT.txt`, `LICENSE-GPL-2.0.txt`, all `into("native-bin/tesseract")`.
    The GPL-2.0 files are a compliance obligation attached to the bundled `libjbig-0.dll`
    (tempdoc 632 "keep" decision) — they travel with the DLL wherever it goes.
  - `:1470-1474` — ORT CUDA variant, gated on `includeOrtCuda`.
  - `:1477-1480` — ONNX models, gated on `includeOnnxModels`.
- `.github/workflows/build-installer.yml:145-152` — the CI build sets
  `includeCuda=false` / `skipOnnxModels=true` and states in-comment: *"Models and GPU variants land
  on user machines via the 'Install AI' flow, not the base installer."* The stated reason is the
  NSIS 32-bit address-space limit (a 7+ GB sidecar crashes `makensis`), not a distribution
  preference.

**Reading:** the two payloads under discussion (`native-bin/tesseract`, `native-bin/llama-server`)
are staged by the same task, at the same altitude, and behind the same `isWindowsHost` gate as the
two payloads CI **already** excludes. The build already knows how to omit things from this tree.

### C. Size figures disagree across surfaces

| Surface | Stated size | Citation |
|---|---|---|
| `README.md` | 853 MB | `README.md:36`, `README.md:57` (at `HEAD`) |
| `.claude/skills/installer/SKILL.md` | ~748 MB | `:16`, restated `:111` |
| `.github/workflows/build-installer.yml` | 741 MB | `:202`, restated `:212` |
| Measured artifact (run `29514086160`) | **815 MB** | this tempdoc, §A |

All four disagree. 853 MB is the v0.1.0 release asset; 815 MB is a 2026-07-16 build; the other two
are undated in-comment figures. Tempdoc 760 already logged this drift to the observations inbox
(760 §"Out-of-scope observation") — **referenced, not duplicated.** It matters here only because a
payload decision needs a trustworthy baseline to measure against, and there currently isn't one.

> Note for a follow-up agent: `README.md` is dirty in the shared main checkout as of writing. The
> line numbers above are from `git show HEAD:README.md`, which is also what 760 cites (`:57`).

### D. The core observation

`README.md:40` states: *"On first launch the app asks consent to download its AI models (~9 GB, one
time, from GitHub Releases + Hugging Face) — after that it runs fully offline."* Corroborated at
`README.md:57` (disk budget: "853 MB installer + ~0.7 GB installed + ~9 GB one-time model
download") and by `.claude/skills/installer/SKILL.md:111` ("Models are NOT bundled in the alpha
installer").

**Verified as stated in the brief.** The consequence:

> The base installer ships an inference runtime — `llama-server.exe` plus ~19 support DLLs — and
> that runtime is **inert** until a separate, ~11× larger consent-gated download completes. The
> binary and the weights it exists to load travel by different routes, and the smaller one goes
> first.

Tesseract is a weaker parallel: it is *self-sufficient* on arrival (`tesseract.exe` +
`eng.traineddata` ship together, `packaging/runtime/tesseract-windows.v1.json:14-25`), so it does
not share llama-server's inert-on-arrival property. It is in scope for a different reason — 74 DLLs
for a feature that may not be first-run-critical (§Open question 6).

### E. The pack mechanism already carries native binaries

The brief asked whether models and GPU variants "already ship that way". They do, and the
mechanism is more capable than the workflow comment suggests.

`modules/ui/src/main/resources/ai/model-registry.v2.json:253-294` defines a package:

```json
{
  "id": "cuda-runtime",
  "label": "GPU runtime libraries",
  "targetDir": "cuda12",
  "installRoot": "native-bin/llama-server/variants",
  "tier": "runtime",
  "variants": [],
  "supportingFiles": [ /* 4 archives, extract: true */ ]
}
```

Load-bearing details:

- `installRoot` is **`native-bin/…`** — the pack mechanism already writes into the exact tree the
  payloads in question live in.
- `variants: []` with only `supportingFiles` — i.e. a package that is **purely native binaries and
  DLLs, no model weights at all**. `AiPreflightService.java:59` explicitly comments on this shape:
  *"A package with no variants at all (e.g. cuda-runtime, which ships only supportingFiles)"*.
- The four archives total ~1.82 GB (`sizeBytes` 232,957,511 + 391,443,627 + 563,449,486 +
  634,283,692), two hosted on `github.com/eliasjustus/justsearch-releases`, two upstream at
  `ggml-org/llama.cpp` — so both self-hosted and upstream sourcing are already exercised.
- Extraction and post-install activation are wired: `AiInstallService.java:533` (archive
  extraction), `:582` / `:840-864` (`applyCudaServerExe` — pointing the running config at a binary
  that did not exist at boot), `RuntimeActivationService.java:1148`.

Separately, `RuntimeRestoreUtil.java:34-74` shows the *bundled* path: `ensureRuntimePresent` copies
`native-bin/llama-server` out of the installed distribution into AI Home, version-gated on
`runtime-version.txt`. The build comment at `modules/ui/build.gradle.kts:1446-1448` describes
Tesseract identically — *"app-owned and restored into AI Home by the shell, just like
llama-server."*

**So there are two live delivery routes into the same destination directory**: ship-then-restore
(bundled) and download-then-extract (pack). A payload move is a route change between two mechanisms
that both already exist and both already terminate in `native-bin/`. That does **not** establish the
move is free — see §Open question 3 for what remains unverified.

## §Why this matters independent of signing

1. **Download size as adoption friction.** 815 MB is a large first ask. The public repo has had
   **zero issues filed by anyone** (verified 2026-07-21: `gh issue list --repo eliasjustus/justsearch
   --state all` returns empty). There is no user population whose tolerance for the current size has
   been observed — which cuts both ways: no evidence the size is hurting, and none that it isn't.
2. **The size baseline is not trustworthy** (§C). Four surfaces, four numbers.
3. **A future winget listing prefers smaller installers.** *Soft factor* — no hard winget size
   limit was found, and 760 owns the winget lane. Label it a preference, not a constraint.

## §Signing consequence — a consequence, not the justification

Tauri v2's bundler signs each bundled PE individually and skips those already carrying a valid
signature (the `should_sign` filter, bundler ≥ 2.6.0). **This was an upstream-behavior claim that
could not be verified from this checkout** — `tauri-bundler` is a build-time tool, absent from
`modules/shell/src-tauri/Cargo.lock`, and `modules/shell/package.json:10` pins only
`"@tauri-apps/cli": "^2"` (unpinned minor). The app crate is Tauri 2.11.3
(`Cargo.lock:3914-3915`).

**RESOLVED (takeover investigation, 2026-07-21) — confirmed against upstream source directly.**
`gh search code "should_sign" --repo tauri-apps/tauri` locates
`crates/tauri-bundler/src/bundle/windows/sign.rs: pub fn should_sign(file_path: &Path) -> crate::Result<bool>`.
Fetched the function body: it first checks the file has a `.exe`/`.dll` extension, then (Windows
only) runs `signtool verify /pa` via a `verify()` helper and returns `!already_signed` — i.e. it
returns `true` (meaning "go sign this") only when the file does **not** already carry a valid
Authenticode signature. The claim is confirmed verbatim: the bundler really does skip pre-signed
PEs, so the 101 → ~8 signings-per-release arithmetic in the table below is sound, not just
arithmetically consistent with an unverified premise.

Taking the claim as given, and applying it to §A's measured distribution:

| | Signing operations per release |
|---|---|
| Current wiring | **101** = 93 unsigned resource PEs + main binary + 5 NSIS plugins + uninstaller + final `setup.exe` |
| With `native-bin/tesseract` + `native-bin/llama-server` moved to the pack | **~8** |

At the owner's stated cadence of 1–2 releases/month (~24/yr): ~2,424 signings/year vs ~192/yr.

This materially changes code-signing cost, because metered signing tiers price per signature. It is
recorded here as **one input among several**, deliberately not as the argument. The payload question
stands on §"Why this matters" alone; if signing were free tomorrow, §D would still describe a
runtime shipping without its weights. **Vendor selection, pricing, and any purchasing
recommendation are explicitly out of scope for this repo** and live in the private business repo.

## §Options — enumerated, not chosen

None of these is recommended. They are the shape of the decision space.

| Option | What moves to the pack | Installer effect | Cost / risk |
|---|---|---|---|
| **O0. Status quo** | nothing | 815 MB, 101 signings | No first-run regression. Baseline. |
| **O1. llama-server only** | `native-bin/llama-server` (~19 unsigned PEs) | smaller by an unmeasured amount (§Q7); ~82 signings | Chat already requires the ~9 GB download, so arguably no *new* gating. Must confirm nothing non-chat depends on the CPU binary. Breaks `verify-installer-nsis-win.ps1` (§Q4). |
| **O2. Tesseract only** | `native-bin/tesseract` (74 unsigned PEs) | ~27 signings | OCR becomes a post-install download. GPL-2.0/NOTICE files must travel with the DLLs (§B). New first-run gate for a feature that currently just works. |
| **O3. Both** | both | ~8 signings | Union of O1+O2 risks. Largest size win, largest UX change. |
| **O4. Neither move; shrink elsewhere** | — | ? | The JRE (`runtime/bin`, 75 PEs) is already vendor-signed so contributes ~0 signings, but may contribute real bytes; jlink module trimming is a different lever entirely. Unexplored here. |
| **O5. Move payload, keep first-run parity** | either/both, but downloaded eagerly on first launch rather than on demand | smaller *download*, same *time-to-working* | Trades installer bytes for first-run wait. Turns a size question into a latency question; may satisfy neither goal. |

An axis cutting across all of these: **eager vs. lazy**. Whether a moved payload downloads during
first-run setup or only when the feature is first invoked is an independent choice with its own UX
consequences, and O5 exists only to name it.

## §Open questions — for the owner; deliberately unanswered

1. **What is the organizing principle?** One candidate: *"the base installer contains what first-run
   **search** needs; everything else is consent-gated."* Test it against the offline-first promise
   (`README.md:44-45`: "Only for the one-time model download. Nothing else, ever"). What does a user
   on an air-gapped machine get under that principle — keyword search over local files and nothing
   more? Is that an acceptable out-of-box product, or does it hollow out the pitch? Note the promise
   is already conditional today (search itself needs the ONNX embedding models, which are already in
   the ~9 GB pack), so the principle may be describing the *status quo* more than a change.

2. **First-run UX cost.** If OCR and chat both become post-install downloads, what is the first-run
   experience? This is a UX regression traded against a download-size win, and it needs an explicit
   owner call — not a side effect of a signing-cost optimization. Specifically: how many consent
   prompts does a new user face, and in what order?

3. **Does the pack mechanism actually support these payloads?** §E establishes that it *already
   carries pure-native-binary packages into `native-bin/`* (`cuda-runtime`), which is stronger
   evidence than the brief assumed. **RESOLVED — no, not without new work (takeover investigation,
   2026-07-21, subagent pass, all four sub-parts traced with file:line evidence):**
   - **(a) required-for-non-GPU:** No. `InstallPlanner.java:94-106`'s `RUNTIME`-tier skip is an
     *unconditional* `!profile.usesCuda()` check — it never consults `minVramBytes` at all, so
     `cuda-runtime`'s `minVramBytes: 0` is decorative, not load-bearing. `ModelPackage.java` has no
     `required`/`mandatory` field and `CapabilityTier` (`CapabilityTier.java:17-25`) has no tier
     meaning "always download regardless of hardware." A CPU-mandatory pack package needs either a
     new tier or the `RUNTIME` skip check to stop being tier-identity-based.
   - **(b) bundled-source-absent tolerance:** `RuntimeRestoreUtil.ensureRuntimePresent`
     (`RuntimeRestoreUtil.java:34-58`) itself degrades gracefully (`Files.isDirectory` guards
     throughout, silent no-op if the bundled dir is gone) — but its **caller**,
     `AiInstallService.java:453-456`, hard-fails the entire "Install AI" run with
     `RUNTIME_MISSING` if it returns `false`, and this check runs **before any pack downloads even
     start** (`:451-456`, ahead of the download loop at `:458+`). A pack-only llama-server would hit
     this precondition and fail before ever reaching the step that would fetch it. Load-bearing
     ordering assumption, not a passive tolerance gap.
   - **(c) GPL-2.0/NOTICE via download:** No precedent exists. The `cuda-runtime` package's four
     `supportingFiles` archives (`model-registry.v2.json:264-293`) are pure `{url, sha256, extract}`
     binary archives with zero license/notice entries — license metadata lives only at the
     *package* level (`"license"`, `"termsUrl"`) as UI-surfaced strings, never as a file dropped on
     disk. `AiInstallService.java:1226-1259`'s `extractZipInPlace` does nothing but unzip. Moving
     Tesseract to pack delivery would need either the hosted archive re-packaged to include the
     three notice/license files (only possible for the self-hosted, not upstream MSYS2, builds) or
     a new "always-copy these repo files after this package extracts" primitive — neither exists.
   - **(d) coherent never-downloaded-CPU-runtime state:** Partially. The *activation-time* signal
     already works — `LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED`
     (`LifecycleReasonCode.java:66`) is wired from `RuntimeActivationService`/`InferenceHandlers`
     and would plausibly fire correctly. But the *install-time/preflight* models do not distinguish
     severity: `AiPreflightResult`/`PackageStatus` (`AiPreflightService.java:36-97`) has no
     completeness-severity field, and the install-run state machine's `skipped` bucket
     (`AiInstallService.java:602-645`) is written entirely in "hardware doesn't support it, that's
     fine" prose — a CPU-mandatory package skipped for any reason would render with the same soft
     "installed with limitations" banner a user sees today for declined GPU acceleration.

   **Net:** the download/extract/consent machinery (§E) is genuinely reusable, but every place that
   decides *whether* a package downloads or *how its absence is reported* was built around an
   implicit "pack content is optional GPU/enrichment" assumption. None of (a)-(d) are fundamental
   blockers, but all four are real, specific gaps — this leans toward "real work needed" over
   "basically ready," which matters directly for scoping O1/O3/O5 effort.

4. **Which gates encode the current assumption?** Confirmed and must move in the same change (per
   `retire-with-a-sweep`):
   - `scripts/ci/verify-installer-nsis-win.ps1:385-412` — **verified.** Asserts
     `native-bin/llama-server/llama-server.exe` exists, then hard-fails unless
     `llama.dll`, `ggml.dll`, `ggml-base.dll`, `mtmd.dll`, `libomp140.x86_64.dll`,
     `msvcp140_codecvt_ids.dll`, `runtime-version.txt` are all present **and** at least one
     `ggml-cpu*.dll` backend exists. Comment: *"exe-only fails in Windows Sandbox"*. Note the brief
     cited `:385-410`; the assertion block actually runs to `:412`.
   - `scripts/ci/check-notices-regen.mjs` — references `native-bin`; the NOTICE/LICENSE files staged
     at `build.gradle.kts:1455-1467` are generated/checked here. Moving Tesseract moves a licensing
     obligation, not just bytes.
   - **RESOLVED — remaining 7 scripts checked (takeover investigation, 2026-07-21, subagent pass,
     file:line evidence for each): none are hard gates on the base-installer bundled payload.**
     `scripts/smoke-tests/verify-gpu-bundle.ps1` (`:34-58`) does hard-`throw`, but it asserts the
     already-pack-delivered cuda12 *variant*, not the bundled CPU payload, and it isn't wired into
     any CI workflow (grep across `.github/` — zero hits). `scripts/codegen/gen-notices.mjs`'s real
     hard gate (`nativeDispositionCheck`, `:244-273`) runs over committed manifests
     (`packaging/runtime/tesseract-bundled-libraries.v1.json`), explicitly designed to work "in CI
     without native-bin staged" (`:236`) — its presence-check at `:303-310` is skip-when-absent, not
     assert-must-exist. The remaining five (`doctor.mjs`, `run-headless-api.ps1`,
     `test-dev-runner-runtime-resolution.mjs`, `justsearch-dev-mcp/server.mjs`, `token-probe.ps1`)
     are all soft dev-tooling/path-resolution or explicitly report-only (`server.mjs:1672-1674`'s own
     comment: *"REPORT-ONLY... does NOT gate `ready`"*) — each would just need a resolved path
     updated, not a design change, if the payload moved.
   - Nothing in `governance/*.json` references `native-bin` or `tesseract` (grep, empty). No
     discipline-gate coupling found.
   - **Overall for Q4:** the gate surface for a payload move is much smaller than "8 scripts to
     check" implied — realistically just `verify-installer-nsis-win.ps1` (must change its assertion
     target) and `gen-notices.mjs`'s disposition manifests (must reflect the new delivery route),
     both already well-characterized. This is good news for whichever option the owner picks: gate
     churn is not a reason to avoid O1/O2/O3.

5. **Does `vc_redist` / WebView2 bootstrap change?** Current behavior: `vc_redist.x64.exe` is a
   declared bundle resource (`tauri.conf.json:21`) and WebView2 uses
   `webviewInstallMode: offlineInstaller, silent: true` (`:25-28`) — i.e. the *offline* WebView2
   bootstrapper is embedded rather than downloaded. Both are Microsoft-signed, so both are
   signing-cost-neutral. **Question:** are they byte-relevant, and is embedding the *offline*
   WebView2 installer still the right call if the product is accepting a network dependency for
   other payloads anyway? Switching to the online bootstrapper is a separate, smaller lever that
   trades bytes for an install-time network requirement. **RESOLVED (byte-relevance) — see §H: the
   offline WebView2 installer is 203.65 MB, 18.83% of the entire installed payload** — the "is it
   byte-relevant" half of this question is answered emphatically yes; the "is it still the right
   call" half remains an owner call, now with a real number behind it.

6. **Is Tesseract genuinely optional?** Investigated far enough to pose it precisely:
   - Eligibility: OCR is attempted only for `application/pdf` and `image/*`
     (`PolicyDrivenTikaExtractor.java:447-451`).
   - It is further skipped when structured text quality is already sufficient
     (`:167-171`, `TEXTUAL` skip) — so **text-layer PDFs are unaffected**.
   - **What actually breaks with no engine:** scanned/image-only PDFs (pages with no readable text)
     and raster images lose text extraction entirely.
   - **How it breaks:** gracefully and legibly, not by crashing. `TikaOcrRuntime.java:19` defines
     `REASON_ENGINE_MISSING`; `PolicyDrivenTikaExtractor.java:176-183` converts a blocked reason into
     an `OcrSkipReason`, increments `ocrMetricCatalog.skippedTotal`, and records it in the
     extraction evidence. `TikaOcrRuntime.resolve()` (`:39-57`) already falls back through an env
     override, app-owned runtime roots, and finally `PATH` — so a user with system Tesseract keeps
     working.
   - **Open:** is "scanned PDFs and images silently index as empty until you download OCR" an
     acceptable default? What fraction of a realistic personal corpus is image-only PDF? That is an
     empirical question no one has measured.

7. **What is the actual size win?** **Now measured (2026-07-21 takeover, tempdoc 772 §F) — the win
   is modest, not dramatic.** See §F below. Combined `native-bin/tesseract` +
   `native-bin/llama-server` is ~10.4% of the installed payload, and this narrows the case for O1-O3
   to the signing-cost and UX axes — it does not stand on size alone.

## §F — Byte-level size measurement (takeover investigation, 2026-07-21)

Answers §Open question 7. **This is the same CI artifact §A measured** (run `29514086160`,
re-downloaded and re-extracted; `7z l` confirms 743 files, matching §A exactly — same evidence
base, no drift). **This artifact expires 2026-07-23** (`gh api .../artifacts` `expires_at`) — it
was two days from disappearing when this measurement was taken; there is no guarantee a future CI
artifact reproduces these exact figures.

Extraction method: `7z x` on the NSIS `.exe` (7-Zip reads NSIS containers directly), then `du -sb`
per directory under the extracted `resources/headless/` tree (the same tree `bundleSidecarResources`
stages, per §B).

| Directory | Bytes | MiB | % of installed payload* |
|---|---:|---:|---:|
| `native-bin/tesseract` | 79,659,851 | 76.0 | 7.36% |
| `native-bin/llama-server` | 33,100,809 | 31.6 | 3.06% |
| **Combined (O3)** | **112,760,660** | **107.6** | **10.4%** |
| `runtime` (JRE, vendor-signed, not in scope) | 53,925,635 | 51.4 | 4.99% |
| Whole extracted payload | 1,081,771,371 | 1031.6 | 100% |
| Installer download (`.exe`, compressed) | 854,988,427 | 815.4 | — |

\* % of the *extracted, uncompressed* payload (1,081,771,371 bytes), which is what
`bundleSidecarResources` stages and the more defensible denominator for "how much of what we ship
is this." The compressed download (815 MB, matching §A/§C's cited figure) is smaller than the
extracted total because NSIS/Deflate compresses it; applying the archive's overall compression
ratio (854,988,427 / 1,081,771,371 ≈ 0.79) as a rough proxy (solid-archive compression means 7z
cannot attribute compressed bytes per-file), moving O3 to the pack would save **on the rough order
of 85-95 MB off the 815 MB download — roughly 10-11%, not the dramatic reduction "93 unsigned PEs"
might suggest.**

**Reading — this narrows §"Why this matters" reason 1, *within this tempdoc's original scope*.**
A ~10% download-size reduction from the two native-binary payloads examined in §A-§F is real but
modest. **However, §G below finds a single dependency, outside this tempdoc's original
native-bin-only framing, that is more than 3x bigger than tesseract + llama-server combined** —
so "is size a weak argument" does not generalize past the two payloads this tempdoc set out to
measure. Read §G before concluding size is a minor factor overall.

## §G — A bigger, previously unexamined payload: `onnxruntime_gpu-1.24.3.jar` (takeover investigation, 2026-07-21)

This tempdoc's charter (and §A's inventory) scoped "payload composition" to native-bin PE binaries
only. Prompted by a direct question during the takeover investigation ("did you also look at other
files unnecessary to be in the installer?"), the JAR dependency tree was checked too. It was not in
scope for §A-§F, and the finding is large enough to change the overall picture.

**What it is.** `lib/worker/onnxruntime_gpu-1.24.3.jar` — staged unconditionally by
`bundleSidecarResources` (`modules/ui/build.gradle.kts:1417`: `from(workerInstallDist...) {
into("lib/worker") }`, no exclusion) from the Worker module's runtime classpath
(`modules/indexer-worker/build.gradle.kts:24`: `runtimeOnly(libs.onnxruntime.gpu)`). Confirmed
present in the same re-extracted, re-verified CI artifact as §F (run `29514086160`).

| Component | Bytes (as shipped in the jar) | % of installed payload |
|---|---:|---:|
| **Whole jar, on disk** | 370,985,729 | **34.3%** |
| `ai/onnxruntime/native/linux-x64/*` (5 files, incl. `libonnxruntime_providers_cuda.so`) | 201,758,934 | **18.65%** |
| `ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll` alone | 163,557,127 | **15.12%** |
| `ai/onnxruntime/native/win-x64/*` (remaining: `onnxruntime.dll`, jni, shared/tensorrt providers) | ~5.5 MB | ~0.5% |
| Java classes + `pom.xml`/notices | ~140 KB | ~0.01% |

(Byte figures for the sub-components are the in-jar *compressed* sizes reported by `7z l` on the
jar itself, which sum to the jar's actual on-disk size — 201,758,934 + 163,557,127 + ~5.5M + ~140K
≈ 370,985,729 — so these percentages are additive against the same 1,081,771,371-byte installed-
payload denominator used throughout §F.)

**Two distinct problems, confirmed separately:**

1. **The Linux natives (18.65% of the installer) serve no purpose on this product.** JustSearch is
   Windows-only (`CLAUDE.md` architecture table; `README.md` Status section). Tempdoc 761
   (`linux-build-cost-estimate`, sibling lane, status "investigation COMPLETE... no implementation
   chartered") confirms no Linux build exists or is chartered — so this isn't latent prep for a
   planned port, it is very likely simply how the upstream Maven artifact
   `com.microsoft.onnxruntime:onnxruntime_gpu` is published (a single fat jar bundling `win-x64` +
   `linux-x64` natives; no per-OS classifier was found in use). **Every Windows installer built
   today ships a full Linux CUDA provider `.so` (316 MB uncompressed, 192 MB compressed) that can
   never execute.**
2. **The Windows CUDA provider DLL (15.12%) is inert until a GPU is present *and* the separate
   ~1.82 GB `cuda-runtime` pack (§E) is downloaded** — the exact same "runtime shipped ahead of its
   weights" pattern §D describes for `native-bin/llama-server`, except here the inert component
   (163.6 MB compressed) is ~5x bigger than the *entire* `native-bin/llama-server` directory (33.1
   MB) that §A-§F already flagged for the same reason.

**Why CI's existing exclusion pattern didn't catch this.** §B already establishes that CI
deliberately excludes GPU/model bulk via `includeOrtCuda=false` / `skipOnnxModels=true`
(`build-installer.yml:145-152`) — but those flags gate the *separate* `native-bin/onnxruntime/cuda12`
staging step (`build.gradle.kts:1470-1474`) and the ONNX model files, not the Worker's own classpath
jar. The jar is pulled in unconditionally as part of `workerInstallDist`, with no equivalent gate.
The project's own build already reasons about this jar's size elsewhere — a comment at
`modules/indexer-worker/build.gradle.kts:163` explicitly avoids content-hashing it on every build
("avoids reading 371MB onnxruntime_gpu on every stamp") — so its size was known, just not connected
to the installer-payload question this tempdoc asks.

**Open questions this raises (additive to §Open questions, not replacing them):**

8. **Can the Linux natives be stripped without touching functionality?** The Worker needs the
   `win-x64` entries only. Options include: (a) a build step that repackages the resolved jar,
   deleting `native/linux-x64/**`, before staging into `lib/worker`; (b) checking whether
   `com.microsoft.onnxruntime` publishes a Windows-only or per-classifier artifact upstream (not
   confirmed in this pass — needs a Maven Central check); (c) accepting the waste as a known,
   bounded cost. Repackaging a vendored third-party binary artifact touches the dependency-lock /
   verification surface (`lockfile-hint`) and raises a redistribution-terms question (is
   reconstituting a modified Microsoft-published artifact inside our installer still within its
   license?) — **investigate before assuming (b) or (a) is free**, same discipline §Q3 already
   applies to the pack-mechanism question.
9. **Does the CUDA provider DLL belong in the base install at all, or should it move the same way
   `native-bin/llama-server`'s CUDA variant already does** — i.e., is `onnxruntime_gpu` a candidate
   for the exact same bundled-vs-packed route change §E describes, just one level down (inside a
   jar instead of a `native-bin/` directory)? This is a larger, more consequential version of
   Option O1/O3 with a bigger size payoff (up to ~33.8% combined vs. §F's 10.4%) and the same open
   dependency on Q3's "does the pack mechanism actually support a CPU-tier required payload"
   finding.
10. **Was this a case of a much bigger fish being missed by scope, or is it out of scope for a
    reason?** This tempdoc's own charter framed the problem entirely in terms of `native-bin/` PE
    binaries (§A: "177 PE binaries", "every PE signature-checked") — a JAR containing embedded
    native libraries is invisible to that framing (a `.jar` is not itself a PE, and
    `Get-AuthenticodeSignature` was run only on already-identified PE files, per §A). **Any signing-
    cost arithmetic in §"Signing consequence" is unaffected** (the embedded `.dll`/`.so` inside a
    `.jar` are not independently Authenticode-signed as bundle resources — they are opaque zip
    entries, not scanned by the installer's PE enumeration), but the *size* argument in
    §"Why this matters" #1 should be re-read with this component included, not excluded.

**This is not a recommendation to remove anything** — consistent with this tempdoc's own
deliberate no-decision stance (§"What this tempdoc deliberately does not do"). It is a correction to
this tempdoc's own scope: the payload-composition question it asks is bigger than the two `native-
bin/` directories it originally measured, and the biggest single lever found so far (the Linux
natives, 18.65%, arguably pure waste with no counter-argument found) sits entirely outside the O0-O5
option set as currently framed.

## §H — Complete top-level accounting + the WebView2 offline installer (takeover investigation, 2026-07-21)

Prompted by the same "what else is unnecessarily in the installer" question that produced §G, and
directly answering this tempdoc's own pre-existing Open Question 5 (byte-relevance of `vc_redist`/
WebView2). Same re-extracted CI artifact as §F/§G (run `29514086160`, third independent
re-download+re-extraction in this pass, still 743 files — no drift across any of the three pulls).

**Full top-level breakdown of the 1,081,771,371-byte installed payload** (this is the complete
accounting — every byte of the extracted installer is in exactly one row, verified by sum):

| Component | Bytes | % of installed payload |
|---|---:|---:|
| `resources/headless/` (everything §A-§G already examined) | 843,940,709 | 78.02% |
| **`$TEMP/MicrosoftEdgeWebView2RuntimeInstaller.exe`** | **203,654,864** | **18.83%** |
| `resources/vc_redist.x64.exe` | 25,635,768 | 2.37% |
| `JustSearch.exe` (root NSIS launcher stub) | 8,449,536 | 0.78% |
| `$PLUGINSDIR` (NSIS UI plugins) | 90,494 | 0.008% |
| **Total** | **1,081,771,371** | **100.00%** |

**The finding: the embedded WebView2 offline installer is the second-largest single component in
the entire installer** — bigger than `native-bin/tesseract` + `native-bin/llama-server` +
`runtime` (JRE) combined (166.7 MB, §F), and closing in on onnxruntime_gpu's Linux-native waste
(§G, 201.8 MB). This resolves Q5's byte-relevance half unambiguously.

**Why it's this big, and whether it needs to be.** `tauri.conf.json:25-27` sets
`webviewInstallMode: { type: "offlineInstaller", silent: true }`. Tauri v2's own documentation
(`v2.tauri.app/reference/config/`, fetched this pass) describes four alternative modes:

| Mode | Installer size impact | Requires network at install time? |
|---|---|---|
| `skip` | none | No (assumes WebView2 already present) |
| `downloadBootstrapper` | ~1.8 MB | Yes |
| `embedBootstrapper` | ~1.8 MB | Yes |
| `offlineInstaller` (current) | Tauri's own docs say "~127 MB" | No |

**The measured 203.65 MB is ~60% larger than Tauri's own documented estimate for this mode** — most
likely because the WebView2 Runtime itself (a full Chromium build) has grown since that doc figure
was written, not because of anything JustSearch-specific. Flagging the discrepancy rather than
resolving it; a follow-up could confirm against Tauri's changelog, but it doesn't change the
conclusion that this mode is now far heavier than documented.

**Context that bears on the tradeoff, not a recommendation:** WebView2 Runtime ships pre-installed
on Windows 11 by default and is pushed to most Windows 10 machines via Windows Update, so the
`downloadBootstrapper`/`embedBootstrapper` modes' "requires network at install time" cost would, in
practice, rarely trigger an actual download for most users — it only matters on a machine that
genuinely lacks WebView2 (older/locked-down Windows 10, air-gapped machines). Weighed against that:
the product already has a hard network dependency for the ~9 GB model download shortly after
install (§D), so "the installer completes fully offline, but the app can't do much until you're
online anyway" is arguably close to the status quo's real behavior already — same tension Q1 poses
for the base-installer-vs-pack question, applied one level down to WebView2 specifically.

**Not evaluated here (owner/design territory, consistent with this tempdoc's stance):** whether
`skip`/`downloadBootstrapper`/`embedBootstrapper` is the right replacement, what fraction of the
target audience lacks WebView2 today (no telemetry exists, same empirical gap as Q6's OCR-corpus
question), and whether trading ~202 MB of installer bytes for a rare install-time network
dependency is worth it. This is a single Tauri config field — mechanically the cheapest of every
lever this investigation found (§G's jar-repackaging carries license/lockfile questions; this one
does not) — but "cheap to flip" is not the same as "should be flipped," and that judgment belongs
to the owner.

## §What this tempdoc deliberately does not do

- Does not recommend a payload split, or state which of O0–O5 is right.
- Does not name code-signing vendors, quote prices, or recommend a purchase.
- Does not modify tempdoc 760 or any other file — this document is the whole change.

## §Corrections to the brief that produced this tempdoc

Recorded per `verify, don't guess`; a follow-up agent should carry these forward.

1. **`verify-installer-nsis-win.ps1:385-410` → `:385-412`.** The llama-DLL assertion block extends
   two lines past the cited range (the `throw` at `:411` and its closing brace). Claim otherwise
   **confirmed** — the gate does assert llama DLLs are present.
2. **`tauri.conf.json:16` → `:16-18`.** Line 16 is `"targets": [`; the `"nsis"` value is `:17`.
   Claim confirmed: NSIS is the only target.
3. **`build-installer.yml:148-152` → `:145-152`.** The "Install AI flow, not the base installer"
   comment is at `:151`; the enclosing rationale (the NSIS 32-bit address-space limit) starts at
   `:145`. Claim confirmed, and the stated *reason* is a technical ceiling, not a distribution
   preference — a distinction the brief did not make.
4. **The pack-mechanism claim was understated.** The brief asked whether native binaries fit the
   models/CUDA flow. They already do: `cuda-runtime` is a `variants: []` package of pure native
   binaries writing into `native-bin/llama-server/variants`
   (`model-registry.v2.json:253-294`). Open question 3 was narrowed accordingly rather than
   answered — the *shape* is proven, the *fit for a CPU-tier required payload* is not.
5. **The 815 MB figure is a fifth number, not a confirmation of any existing one.** The brief framed
   §C as three inconsistent surfaces; the measurement makes it four (853 / 815 / ~748 / 741).
6. **The Tauri `should_sign` claim, flagged as unverifiable from this checkout, is now confirmed**
   (§Signing consequence, takeover investigation 2026-07-21) — fetched directly from
   `tauri-apps/tauri`'s `sign.rs` source. The 101 / ~8 arithmetic is sound, not just consistent with
   an unverified premise.
8. **The Q8 (§G) licensing question is resolved in the permissive direction.** ONNX Runtime is
   MIT-licensed (confirmed: `github.com/microsoft/onnxruntime/blob/main/LICENSE`), and the upstream
   `java/build.gradle`'s `allJar` task (confirmed by fetching it) bundles every built platform's
   native libraries into one jar with no OS-conditional filtering — there is no Windows-only
   artifact to switch to instead; repackaging (if ever chartered) would be a JustSearch-side
   post-resolution step, and MIT's terms permit that modification and redistribution (copyright
   notice must travel with it — already present as `ThirdPartyNotices.txt`/`pom.xml` inside the
   jar). This de-risks Q8(a)/(b) but doesn't resolve whether stripping is worth doing — that's still
   an owner call, now with the licensing uncertainty removed from the decision.
7. **`README.md` line numbers.** The shared main checkout has `README.md` modified by another
   agent's in-flight work; working-tree lines differ from `HEAD` (e.g. the consent sentence is
   `:44-45` in the working tree, `:40` at `HEAD`). All `README.md` citations above are **`HEAD`**
   line numbers, consistent with 760's `:57` citation.

## §Takeover verdict (2026-07-21)

**Method.** Independently re-verified this tempdoc's evidence rather than taking it on faith:
direct spot-checks of `tauri.conf.json`, `build.gradle.kts:1430-1481`,
`verify-installer-nsis-win.ps1:380-415`, and `model-registry.v2.json:253-294` (all confirmed
verbatim); a subagent pass re-verified every §E/Q6 citation (`AiPreflightService.java:59`,
`AiInstallService.java` ×4 sites, `RuntimeActivationService.java:1148`,
`RuntimeRestoreUtil.java:34-74`, `PolicyDrivenTikaExtractor.java` ×3 sites, `TikaOcrRuntime.java`
×2 sites — zero wrong citations, two minor line-range-vs-Javadoc imprecisions, nothing that
changes any claim); confirmed the "zero GitHub issues" claim (`gh issue list --repo
eliasjustus/justsearch --state all` → empty); confirmed tempdoc 760 is genuinely disjoint in scope
(read its live Findings table — Smart App Control, signing credential modes — no payload-
composition overlap); and re-downloaded + re-extracted the cited CI artifact (`29514086160`,
`gh run download`, 7z on the NSIS `.exe`) to answer §Open question 7 with real numbers (§F).

**Is this tempdoc sound?** Yes. Every checked citation held up. This is unusually rigorous analysis
work — it states a real, verified problem (§D: an inert runtime shipping ahead of its weights), is
honest about what it doesn't know (flagged the unverified `tauri-bundler` `should_sign` behavior
rather than silently relying on it), and explicitly declines to reach a decision rather than padding
toward one. It does not duplicate 760 (trust/signing-mechanics, disjoint), 759, 761 (other
distribution lanes), or 374 (the ancestor decision this inherits, not a redo of it).

**Should this proceed to design/implementation now? No — and it isn't supposed to.** This tempdoc
is explicitly analysis-only, and that is the correct place for it to stop. What it surfaces is
genuinely owner-gated: Q1 (organizing principle: does "installer ships what first-run *search*
needs" hollow out the offline-first pitch?) and Q2 (first-run UX cost of new consent prompts) are
product-shape decisions, not things a follow-up investigation can resolve. Q3 (pack-mechanism
readiness) is now **resolved, not just posed** — a dedicated subagent pass traced all four
sub-questions with file:line evidence and found the mechanism is genuinely *not* ready for a
CPU-mandatory package (§Open questions, item 3): the `RUNTIME` tier's skip check is unconditionally
CUDA-gated with no escape hatch, the bundled-runtime restore precondition runs and can hard-fail
*before* any pack download starts, there's no existing precedent for shipping license/notice files
inside a downloaded archive, and the install-time state model has no severity distinction between
"skipped, that's fine" and "skipped, and now chat is broken." None of these are blockers, but
together they mean O1/O3/O5 carry more implementation weight than §E's "the mechanism already
exists" framing suggested — worth knowing before scoping effort on any option. Q4 (gate sweep) is
also now fully resolved: only 2 of 9 checked scripts are real gates, both already well-characterized,
so gate churn is not a reason to prefer one option over another.

**What was the cheapest validating evidence, and did it already exist?** No — it didn't exist
before this pass. §Open question 7 (the byte-level size win) was the one purely-empirical,
non-owner-gated question left on the table, and it was cheap: re-download + re-extract + `du -sb`,
no owner input needed. It's now answered (§F): moving both `native-bin` payloads to the pack saves
**~10-11% of the installer** — modest on its own. **But asked to check further whether other files
were unnecessarily in the installer, the same re-extracted artifact turned up two things bigger than
everything §A-§F examined:**

- **§G: `lib/worker/onnxruntime_gpu-1.24.3.jar` is 34.3% of the installer**, and ~18.65 percentage
  points of that (bundled Linux native libraries) has no live justification on a Windows-only
  product with no chartered Linux build. Unlike the O0-O5 tradeoffs, this doesn't obviously trade
  against any UX or signing benefit — no counter-argument for keeping it was found, though a
  licensing/lockfile question was (now resolved permissively: ONNX Runtime is MIT, no Windows-only
  upstream artifact exists to switch to instead — see the updated Corrections list).
- **§H: the embedded WebView2 offline installer is 203.65 MB, 18.83% of the installer** — the
  second-largest single component overall, and ~60% bigger than Tauri's own documented estimate for
  this config mode. A single config field (`webviewInstallMode`), the cheapest-to-flip lever found,
  though whether to flip it is still a real tradeoff (install-time network dependency on the
  minority of machines lacking WebView2 — no telemetry on how large that minority is).

Both were missed by the original charter because it scoped "payload" to `native-bin/` PE binaries
and `resources/`-declared bundle entries only — a `.jar`'s embedded natives and NSIS's `$TEMP`
staging area both fall outside that framing. Together, §G's Linux natives + §H's WebView2 installer
sum to more than a third of the entire installer, roughly 3.5x what §A-§F examined. **This evidence
will not be re-obtainable from this exact artifact after 2026-07-23** (its GitHub Actions retention
expiry) — §F, §G, and §H's numbers all came from the same artifact, re-downloaded three times across
this pass with identical file counts (743) each time; re-measuring later means trusting a different
build.

**What does it displace or duplicate?** Nothing currently shipped or planned — there is no
competing analysis of installer payload composition, and 760/759/761/374 are confirmed disjoint
(see §Method). §G's Linux-binary finding doesn't displace anything either — it's not something
another tempdoc already tracks; 761 (Linux build cost) only confirms there's no active Linux-build
work that would justify the bytes being there. It does not obsolete or conflict with any of them.

**LITE-CLASS: no.** This is evidence-gathering and decision-framing for a payload/UX/signing
tradeoff, not a pure teardown, rename, or config-delete — it does not qualify even provisionally.

**State:**
- **BLOCKED ON YOU:**
  - Q1/Q2 (organizing principle + first-run UX cost) — product-shape calls no investigation
    resolves; now sharpened by Q3's finding that O1/O3/O5 (moving `native-bin` payloads) carry real
    implementation weight beyond "the mechanism already exists."
  - **Whether §G's Linux-natives removal is worth chartering as a small, separable,
    near-zero-downside fix** — it doesn't obviously trade against UX, doesn't need to wait for the
    bigger O0-O5 decision, and its licensing question is now resolved permissively (MIT). Still
    needs your go-ahead before anyone spends effort repackaging a third-party dependency.
  - **Whether §H's WebView2 install-mode change is worth chartering** — mechanically the cheapest
    lever of everything found (a single Tauri config field), but trades ~202 MB for an install-time
    network dependency on an unmeasured minority of machines. A real tradeoff, not a free win, even
    though it's cheap to implement.
- **PROCEEDING / DONE (this session):** Independent verification of all prior citations (clean); Q7
  answered with measured numbers (§F); two follow-up investigations beyond the tempdoc's original
  charter (§G Linux/CUDA jar bloat, §H WebView2) found and quantified payloads the original charter
  couldn't have caught by construction, together larger than everything §A-§F examined; Q3 (pack
  mechanism readiness) and Q4 (gate sweep) both resolved with file:line evidence via subagent
  investigation; the `should_sign` upstream claim confirmed against Tauri source; Q8's licensing
  question resolved (MIT, no Windows-only artifact exists). Tempdoc updated in place with all of it.
  No design or implementation work was started, per the takeover charter — that stays gated on your
  answers above, including whether the two newly-found levers (Linux natives, WebView2 mode) should
  be fast-tracked ahead of the bigger Q1/Q2 decision.
