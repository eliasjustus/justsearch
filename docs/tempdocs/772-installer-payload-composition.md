---
title: "Installer payload composition: what belongs in the base installer versus the consent-gated download pack — the base installer ships an inference runtime it cannot run, and the pack mechanism that would carry it already exists"
type: tempdocs
status: "open — ANALYSIS ONLY (2026-07-21). Evidence measured against a real 815 MB CI artifact (run 29514086160, 743 files, 177 PE binaries, every PE signature-checked). Takeover investigation (2026-07-21, multiple passes) independently re-verified all citations (clean), resolved Q7 (§F: native-bin combined is ~10.4% of installed payload), found a much bigger payload outside the original native-bin-only scope (§G: lib/worker/onnxruntime_gpu-1.24.3.jar is 34.3%, of which ~18.65% is dead Linux native binaries on this Windows-only product and ~15.12% is a Windows CUDA provider DLL inert until GPU+pack), and found a second major lever (§H: the embedded WebView2 offline installer is 203.65 MB / 18.83% — the second-largest single component, ~60% bigger than Tauri's own documented estimate). Resolved Q3 (pack mechanism is NOT ready for a CPU-mandatory package — 4 concrete gaps found), Q4 (only 2 of 9 scripts are real gates, both already characterized), the should_sign upstream claim (confirmed against Tauri source), and Q8's licensing question (ONNX Runtime is MIT; no Windows-only upstream artifact exists). No decision reached, no split recommended, nothing removed or implemented; §Open questions (1-10, several now resolved inline) are for the owner. A /derisk pass, /theorize pass (broader framings + a spun-off idea sketch, tempdoc 773), /research pass (competitive precedent: Ollama's Windows installer is 1.36 GB, ~1.75x this one; the onnxruntime_gpu fat-jar problem is a known unresolved upstream issue, not a local one-off; Tauri's WebviewInstallMode default is downloadBootstrapper, so JustSearch's offlineInstaller choice was a deliberate historical override), a /design pass (§Design: closes Q3 by extending 657's existing tier/intent/hardware model with a package-level `requiresCuda` field rather than building new structure; recommends Gradle Artifact Transforms over the derisk pass's hand-rolled jar task for §G; recommends reverting §H to Tauri's own default), and a round-2 /derisk pass (softens the Artifact Transform recommendation — no Transform/buildSrc precedent exists in this repo, so the hand-rolled task is the lower-risk near-term choice — and surfaces a must-not-miss InstallPlannerTest update for Q3a, an unresolved attachment point for Q3c's notice-baking, and confirms Q3d needs matching ui-web work) followed. See §Takeover verdict."
created: 2026-07-21
updated: 2026-07-22 (implementation addendum, confidence check, second takeover verdict — recommends closing Q1/Q2 with O0; §I completes the needs audit against the owner's stated main intention — full inventory of the post-fix 452.7 MB artifact, Q9 resolved as load-bearing/keep, 96.9 MB head/worker duplicate-jar finding)
author: agent (subagent investigation), founder-directed distribution work (2026-07-21)
category: distribution / installer
related:
  - 760-installer-distribution-readiness  # distribution TRUST mechanics — disjoint scope, see §Scope
  - 759-mcpb-standalone-feasibility       # sibling distribution lane
  - 761-linux-build-cost-estimate         # sibling distribution lane
  - 374-app-packaging-and-distribution    # the CPU-only alpha decision this payload inherits (filename corrected)
  - 657-install-modes-and-model-pack-decomposition  # shipped the CapabilityTier/InstallPlanner substrate §Design extends
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
(`v2.tauri.app/reference/config/`, fetched this pass) describes five modes total:

| Mode | Installer size impact | Requires network at install time? |
|---|---|---|
| `downloadBootstrapper` (**Tauri's own default**) | ~1.8 MB | Yes |
| `embedBootstrapper` | ~1.8 MB | Yes |
| `offlineInstaller` (**current, JustSearch's choice**) | Tauri's own docs say "~127 MB" | No |
| `fixedRuntime` | ~180 MB (pins an exact WebView2 version rather than the auto-updating "evergreen" one `offlineInstaller` uses) | No |
| `skip` | none | No (assumes WebView2 already present — installer doesn't check) |

**Confirmed this pass: `offlineInstaller` is not Tauri's default — it's a deliberate override someone
made at some point.** `windows.webviewInstallMode` defaults to `{ silent: true, type:
"downloadBootstrapper" }` when unset (confirmed against Tauri's config reference). This reframes
§H: this isn't an accidental default nobody looked at (unlike §G's Linux binaries) — it's a real past
decision to trade bytes for offline-installability, made before anyone had measured what it actually
costs. That's a materially different starting point for the owner's call than "nobody chose this."

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

## §Derisk — implementation-confidence passes (round 1: the two fast-track candidates; round 2: the /design pass's three designs) (2026-07-21)

Run via `/derisk` against the two candidates the takeover verdict flagged as possibly worth
fast-tracking ahead of the big Q1/Q2 decision: stripping §G's Linux native binaries and switching
§H's WebView2 install mode. Read-only investigation only — nothing implemented, no design chosen.

**Linux-native jar stripping (§G) — every identified risk resolved favorably:**
- **CI blast radius, confirmed real and correctly scoped around.** `.github/workflows/ci.yml`'s
  `unit-tests` matrix runs `:modules:indexer-worker:test` on `ubuntu-latest` (the "search-worker"
  lane, `:246-277`) — Linux CI genuinely does resolve and exercise the `onnxruntime_gpu` dependency.
  This means stripping must happen **only** in the Windows-only installer-staging copy, never at the
  Gradle dependency-declaration/resolution level — confirming the design constraint already assumed
  in the takeover verdict was load-bearing, not just cautious.
- **Native-loader mechanism, confirmed from upstream source.** Fetched `OnnxRuntime.java` directly:
  `initOsArch()` detects `os.name`/`os.arch` once at class-init into a single `OS_ARCH_STR` (e.g.
  `"win-x64"`), and `extractFromResources()` builds exactly one resource path,
  `"/ai/onnxruntime/native/" + OS_ARCH_STR + '/' + libraryFileName` — it never inspects any other
  platform's folder. Deleting `linux-x64/**` entries is provably side-effect-free on Windows.
- **Mechanical pattern, already precedented in this exact codebase.** `modules/ui/build.gradle.kts`
  already does `from(zipTree(cudaZipFile)) { include(...); exclude("**/ggml-rpc.dll") }` (staging the
  CUDA llama-server variant, excluding one unwanted DLL for an analogous reason — "trips Windows
  Defender"). The same `zipTree` + `exclude` idiom applies directly; three more `zipTree` sites exist
  in this build (`modules/ui/build.gradle.kts:815`, `modules/api-contract-projection-java/build.gradle.kts:58`).
  Producing a valid re-packaged `.jar` (not just a flattened directory, since the loader needs a
  classpath-resolvable jar) would need a dedicated `Jar`/`Zip`-type task rather than the `Sync`-style
  flatten these examples use — a standard, well-documented Gradle pattern, just not yet proven in
  *this* repo verbatim.
- **Dependency-verification risk, avoided by construction.** `gradle/verification-metadata.xml`
  (referenced `settings.gradle.kts:67`) verifies resolved artifacts at resolution time; a downstream
  task consuming the already-resolved jar as a plain file input (not a new dependency coordinate)
  never touches that file.
- **Single site of entry, confirmed.** `runtimeOnly(libs.onnxruntime.gpu)` is declared in
  `indexer-worker`, `worker-core`, and `benchmarks` (`gradle/libs.versions.toml:117` for the
  coordinate) — but only `indexer-worker`'s `installDist` output feeds `bundleSidecarResources`
  (`build.gradle.kts:1417`), so the installer-staging fix has exactly one place to change.
- **No test coupling.** Grep for `linux-x64`/`libonnxruntime` across worker module test sources:
  zero hits.
- **Residual, not fully retired:** actually building a trimmed jar and running a live smoke test
  (embeddings/SPLADE/reranker/NER all depend on this jar loading correctly) hasn't been done — this
  pass confirms the *design* is sound, not that a built artifact behaves correctly. A silently
  malformed jar would degrade core retrieval quality, not just fail loudly, so this step should not
  be skipped once implemented.

**WebView2 install-mode switch (§H) — every identified risk resolved favorably, lower residual risk overall:**
- **No gate references it.** Swept `verify-installer-nsis-win.ps1`, `package-installer-win.ps1`,
  `build-installer.yml` — the only hit is a caching comment (`build-installer.yml:80`, "Cache Tauri
  bundler downloads... WebView2 bootstrapper"), not an assertion on presence, size, or mode.
- **Version-pinned behavior confirmed stable.** `webviewInstallMode` predates Tauri 2.0's stable
  release (carried forward from v1, documented at `docs.rs/tauri-utils/latest`) — no risk that
  2.11.3 (`Cargo.lock:3914-3915`) lacks any of the four modes.
- **Silent-install interaction is a pre-existing gap, not a new one.** Tempdoc 760 already found CI
  never empirically verifies silent install (`-SkipVerify`, `build-installer.yml:173`). Switching to
  a mode requiring network at install time interacts with that same untested surface, but doesn't
  introduce a new one — worth knowing, not a reason to avoid the change.
- **Residual, not fully retired:** this is a one-line config change, but "installer completes
  correctly end-to-end with the new mode, including under `/S` silent install" has not been built and
  tested — mechanically trivial, verification is not.

### Round 2 (2026-07-21) — derisking the /design pass's three designs

Run after §Design settled on specific mechanisms. This round found one design recommendation should
be **softened** (Design 2) and surfaced concrete, previously-unstated work items for Design 1 that
change its effort estimate, without invalidating either design's core direction.

**Design 2 (Gradle Artifact Transforms for §G) — the scoping mechanism is confirmed safe, but the
recommendation over the hand-rolled task is weaker than §Design implied.**
- Confirmed (Gradle's own docs, fetched this pass): a transform only fires for a configuration that
  explicitly requests its "to" attribute value — two configurations resolving the *same* dependency
  coordinate can safely get different variants (one transformed, one not) by declaring different
  attributes. The Linux-CI blast-radius concern this round set out to check is **not a blocker** — it
  can be scoped correctly.
- **But:** this repo has **zero existing precedent** for Artifact Transforms — no
  `registerTransform`/`TransformAction` anywhere, and **no `buildSrc` or convention-plugin directory
  at all** (confirmed by direct search), which is where a Transform would normally be registered once,
  centrally. Adopting Transforms here means introducing an entire new category of build infrastructure
  this codebase has never used, whereas the previously-derisked hand-rolled task reuses the
  `zipTree`+`exclude` idiom *already precedented in this exact build* (the CUDA variant staging code).
  The "generalizes to future consumers" benefit is real but currently theoretical — no second consumer
  needs a trimmed variant today.
- **Revised read:** Design 2's recommendation stands as the more idiomatic long-term answer, but is a
  bigger, less-precedented lift than the write-up conveyed. For a first implementation, the
  previously-derisked hand-rolled task (scoped inside `bundleSidecarResources`, already Windows-host-
  gated and structurally isolated from `:test`) is the lower-risk near-term choice; Artifact Transforms
  are worth adopting once/if a second consumer of a trimmed dependency actually materializes.

**Design 1 (Q3 fixes) — three concrete findings that change scope, none that block it.**
- **Q3(a) (`requiresCuda` field) is lower-risk on the schema-gate front than assumed, but has a real,
  specific test obligation.** Confirmed: `model-registry.v2.json`'s two copies
  (`modules/configuration/src/test/resources/...` and `modules/ui/src/main/resources/...`) are
  byte-identical today with no automated sync-check — the SSOT-catalog-sync gate does **not** cover
  this (it's scoped to `SSOT/catalogs/**`, a different catalog family entirely), and `ModelPackage`
  is not one of the five schema classes `:modules:app-api:updateSchemas` regenerates (confirmed by
  reading the task's `includeTestsMatching` filter) — so this field addition needs neither gate.
  It **does** need the same backward-compat constructor-overload chain `ModelPackage` already uses
  for `installRoot`/`license`/`tier` (precedented, low-risk, mechanical) — **and, concretely,
  `InstallPlannerTest`'s two `cuda-runtime` package-construction call sites (its explicit
  hardware-gating regression tests) must be updated to pass `requiresCuda: true`, or the change
  silently inverts the exact GPU_LITE regression those tests protect** (the old backward-compat
  overload would default `requiresCuda` to `false`, making `InstallPlanner`'s new check never skip
  `cuda-runtime` on non-CUDA hardware). This is a real, necessary, well-scoped fix — not a blocker,
  but a specific item that was not called out in §Design and must not be missed.
- **Q3(b) (precondition reorder) confirmed safe by reading the full method**, not just cited line
  ranges. `AiInstallService.runInstallInternal()`'s actual flow: create dirs → load registry → policy
  check → hardware profile → compute `InstallPlan` → populate status → **`ensureRuntimePresent` check**
  → download loop. The runtime-restore check is structurally independent of everything after it — no
  step in the download loop reads state the reorder would change. Confidence on this sub-fix raised,
  not lowered.
- **Q3(c) (bake notices into the release archive) — RESOLVED (2026-07-22, owner-directed follow-up).**
  No script in *this* repo builds/publishes the `justsearch-releases` archives because that process
  doesn't live here — it's a manual runbook (`RELEASING.md`) owned by the separate `justsearch-releases`
  distribution repo, covering both app-installer releases and model/runtime-asset releases (tag
  scheme, SHA-256 capture, per-release notes). It is a human-run checklist, not CI automation, and it
  already has a repo-level `THIRD_PARTY_NOTICES.txt` convention for tracking redistributed-binary
  license obligations. **The fix for Q3(c) is therefore a runbook step addition** ("include
  `NOTICE-*.txt`/`LICENSE-*.txt` in the archive before uploading it," mirroring how the existing
  `THIRD_PARTY_NOTICES.txt` already covers other redistributed content), not new tooling — no code or
  CI in this repo needs to change to make this feasible. Still real, still owner-executed work (a
  manual step someone has to remember to do at release time), but the "does an attachment point even
  exist" uncertainty is closed.
- **Q3(d) (derived severity field) is not schema-gated, but is confirmed two-sided, not backend-only.**
  `AiPreflightResult`/`PackageStatus` (nested records in `AiPreflightService.java`, not `app-api`
  types) aren't covered by `updateSchemas` either. But `modules/ui-web/src/shell-v0/utils/
  aiInstallPoll.ts` is a confirmed real consumer of this exact shape — adding the field is additive
  and non-breaking on its own, but for it to actually *do* anything (let a user see "mandatory and
  missing" vs. "optional and skipped"), `modules/ui-web` needs matching work too, which pulls in the
  `ui-web-gates` consult recipe. §Design's framing ("a derived field, not new persisted state") was
  correct about the backend but understated that realizing the point of the fix is a two-module
  change, not a one-file one.

**Net effect on confidence:** Design 1's four sub-fixes remain sound in direction; this round found
one concrete must-not-miss test update (Q3a), one open question with no visible answer in this repo
(Q3c), and one scope correction (Q3d is backend+frontend). Design 2's mechanism is confirmed
technically safe but its edge over the already-derisked hand-rolled task is smaller than presented —
recommend the hand-rolled task first, Transforms later if a second consumer appears. Design 3 is
unchanged from round 1 (re-swept, nothing new found).

## §Theorize — broader framings, alternative directions, and hidden assumptions (2026-07-21)

Run via `/theorize`, before any design is settled. Nothing below is a recommendation or a decision —
it's the space of ideas worth having in view when the owner (or a future implementer) does settle one.

### A governing distinction the tempdoc has been circling without naming: waste vs. tradeoff

§A-§F's original options (O0-O5) are all genuine tradeoffs — moving `tesseract`/`llama-server` trades
installer bytes against first-run UX and signing cost. There's a real decision to make, and reasonable
people could land on either side. §G's Linux native binaries are a **different kind of thing**: nobody
chose to ship them, no UX or functionality depends on them, and no counter-argument for keeping them
was found. §H's WebView2 sizing is somewhere in between — a real default someone picked (for good
reasons: no install-time network dependency), just never revisited as the runtime grew.

Naming this distinction explicitly might be more useful than the O0-O5 framing alone: **"is this bytes
someone decided to ship for a reason, or bytes that accumulated because no one had cause to look?"**
The former deserves the full options-and-tradeoffs treatment this tempdoc already gives O0-O5. The
latter deserves a much lower-friction path — closer to a bug fix than a product decision. Reading
§G and §H through this lens is *why* they were flagged as fast-track candidates rather than folded
into the O0-O5 option set — but the tempdoc never said so explicitly until now.

### Alternative solution shapes not yet considered

The tempdoc's option space (§Options) is built entirely around one axis: *bundled now vs. downloaded
later, after first launch*. A few structurally different shapes exist that weren't on that axis:

1. **Install-time component selection (NSIS-native).** NSIS supports a classic component-picker page
   — the installer itself could ask "Chat (requires ~9 GB)? OCR?" as checkboxes *during* setup,
   using the same consent moment as today's post-launch prompt, just moved earlier. This sidesteps
   Q3's pack-mechanism engineering gaps entirely (no new tier semantics, no preflight-severity model
   needed) at the cost of a real limitation: a component picker doesn't survive silent (`/S`) install,
   so winget/scripted installs would need a hardcoded default selection, which reintroduces a version
   of Q1 (what does the silent/default path ship?) in a different guise. Worth having in view as a
   materially different mechanism from "pack download," not just a smaller version of it.
2. **Bootstrapper-first setup** — the opposite extreme from the status quo. Instead of one large
   installer with some content deferred, ship a genuinely tiny launcher that downloads everything
   (search core, chat runtime, OCR) through a guided first-run wizard with visible progress — closer
   to how some modern desktop apps handle large runtimes. This turns the network dependency from a
   surprise after install into the explicit main event of setup, which might resolve the Q1/Q2 tension
   by changing the frame rather than answering it within the current one. It's a much bigger
   architectural lift (changes what "the installer" *is*, interacts with 760's signing/trust
   questions in new ways) — named here as the north star of one direction, not a proposal.
3. **Gradle Artifact Transforms, as an alternative mechanism for the §G jar fix.** The derisk pass
   sketched a hand-rolled `Jar`/`Zip` task consuming the resolved `onnxruntime_gpu` jar. Gradle's
   built-in Artifact Transform API exists specifically for "make a resolved dependency smaller/
   different without changing its coordinates," is cacheable, and would apply uniformly wherever the
   dependency resolves rather than needing separate wiring at each staging site. Worth a real look
   before committing to the hand-rolled task if/when this is implemented — not evaluated in depth
   here, but a strictly more idiomatic-Gradle alternative to the mechanism the derisk pass exercised.
4. **Upstream engagement, as a slower parallel track.** The Linux-binary bloat in `onnxruntime_gpu`
   is a known shape of complaint for other JVM consumers of this artifact (a multi-platform fat jar
   is how Microsoft publishes it, confirmed from source during the derisk pass). Filing or finding an
   upstream issue asking for a per-OS classifier is a legitimate parallel path to a local repackaging
   fix — slower, not guaranteed, but the actual fix rather than a local workaround, and useful even if
   JustSearch repackages locally in the meantime.

### Hidden assumptions and risks not yet named

- **"Smaller installer is strictly better" is itself an assumption.** Every post-install download this
  tempdoc's options add is a new failure point (partial download, network hiccup, a user confused
  about why a feature "doesn't work yet"). There is a real tension between *download size* and
  *number of moving parts / predictability of the first-run experience* that the options table doesn't
  price in — reducing MB is not free of cost even when it looks free of tradeoff.
- **Repackaging a vendored third-party binary (§G) has an auditability cost the licensing check
  didn't cover.** MIT permits the modification (§Corrections item 8), but a security-conscious user
  can no longer verify the shipped `onnxruntime_gpu` bytes against Microsoft's published artifact
  hash once it's been repackaged — the installer would contain a custom-built artifact instead of a
  verifiable upstream one. Minor in absolute terms (it's already inside a larger unsigned-by-us
  installer today), but worth naming since "MIT license, so no legal issue" isn't the same claim as
  "no auditability cost."
- **Removing the offline WebView2 installer (§H) may disproportionately affect exactly the audience
  this product is built for.** JustSearch's own pitch is offline-first, privacy-first, local search.
  A user who deliberately runs an air-gapped or bandwidth-constrained machine — plausibly overrepresented
  among people drawn to that pitch specifically — is exactly who loses the most from trading the
  offline installer for a smaller download. This is a real tension between the size-optimization goal
  and the product's own stated values, not just an edge case to note in passing.

### A broader principle this tempdoc's evidence points toward, without settling it

Every capability this investigation touched — Tesseract, the CPU llama-server runtime, the CUDA
runtime pack, the ONNX Runtime GPU providers, and now WebView2 — is an **optional capability with its
own bespoke delivery decision**: build-time bundling behind a Windows-host gate, a consent-gated
download with hardcoded GPU-tier semantics, an embedded jar, or an embedded installer, each invented
independently as the need arose. Q3's investigation already surfaced one symptom of this (the pack
mechanism's tier model assumes "optional and GPU-gated," so it can't yet represent "required and
hardware-independent" without new work).

A more general shape may be worth having in view for later: a single **capability-delivery lifecycle**
— declare a capability's tier (always-required / hardware-optional / feature-optional), its size, and
its activation trigger, and have every one of these payloads (Tesseract, llama-server-cpu,
cuda-runtime, onnxruntime GPU providers, and even WebView2) expressed as an instance of the same model
rather than a one-off mechanism. This is **not a design proposed here** — it's a pattern the accumulated
evidence makes visible, worth recognizing if a future design session reaches for it independently
rather than re-deriving it from scratch.

### A meta-observation: this investigation was archaeology, not monitoring

Two of this tempdoc's three largest findings (§G, §H) were only found because a takeover investigation
happened to re-extract and manually inspect a real installer artifact, byte by byte, by hand, three
times over. Nothing about installer composition is visible on an ongoing basis today — no CI step
reports "here is what's inside this release's installer and how it changed since the last one." That
gap is a separate, smaller idea worth sketching on its own rather than folding into this tempdoc's
already-large scope — see tempdoc 773 (`773-installer-composition-observability.md`), sketched
alongside this theorization pass, not chartered or decided.

## §Research — external context pass (2026-07-21)

Run via `/research`, prompted by asking whether anything in this space is actively evolving or
already solved elsewhere rather than needing to be re-derived from first principles. Four questions
turned out to have real external answers; two are recorded above (§H's corrected mode table, the
`downloadBootstrapper`-is-the-default fact). The other two are recorded here.

### Competitive precedent: comparable local-AI desktop apps ship installers this size or bigger

[Ollama's Windows installer](https://github.com/ollama/ollama/releases) (`OllamaSetup.exe`, latest
release, confirmed via `gh api repos/ollama/ollama/releases/latest`) is **1,426,451,968 bytes — 1.36
GB, about 1.75x this installer's measured 815 MB** — and that's before counting the separate
variant archives Ollama also publishes for the same release (`ollama-windows-amd64-rocm.zip`, 247
MB; `ollama-windows-amd64-mlx.zip`, 724 MB). A general web search additionally indicates LM Studio
bundles its CUDA runtime directly into its installer rather than deferring it.

**Reading:** §"Why this matters" reason 1 (815 MB as "a large first ask") is worth weighing against
this. JustSearch's installer is not large by the standards of comparable, well-adopted local-AI
desktop tools — if anything it's smaller than at least one popular reference point. This doesn't
settle Q1/Q2 (JustSearch's zero-issues-filed baseline and Ollama's are different products with
different audiences and adoption stages), but it's real calibration data that didn't exist in this
tempdoc before, and it argues against treating "815 MB feels big" as self-evidently true without a
comparison point.

### The onnxruntime_gpu fat-jar problem (§G) is a known, unresolved upstream complaint — not a local one-off

Three open GitHub issues on `microsoft/onnxruntime` describe the same structural shape §G found,
independently, some at a considerably larger scale:
[#22996](https://github.com/microsoft/onnxruntime/issues/22996) states plainly that "the jar file
from Maven contains native files for multiple operating systems," contributing to jar bloat;
[#18859](https://github.com/microsoft/onnxruntime/issues/18859) reports a much more extreme instance
— a 788 MB jar where `libonnxruntime.so` alone accounts for 751 MB (a newer/larger CUDA build than
JustSearch's 1.24.3, but the identical root cause); and
[#12084](https://github.com/microsoft/onnxruntime/issues/12084) flagged embedded `.pdb` debug-symbol
bloat in an older version specifically (confirmed not present in JustSearch's current 1.24.3 jar —
this particular sub-issue looks fixed upstream since then). **None of these issues have a maintainer
response, fix, or workaround recorded — #22996 is open with no engagement since filing.**

**Reading:** this confirms §Theorize's "upstream engagement" idea is a real, already-open door (worth
adding a 👍/comment to an existing issue rather than filing a fresh one) but also confirms it is not
a fast path — these issues show no sign of upstream movement. It also reinforces that local
repackaging (the derisk pass's approach) isn't a hacky workaround for a JustSearch-specific problem;
it's the same fix other consumers of this exact artifact have independently had to consider.

## §Design (2026-07-21)

Run via `/design`, after reading tempdoc 657 (`install-modes-and-model-pack-decomposition`, 2026-07-02
— the design that shipped the `CapabilityTier`/`InstallIntent`/`InstallPlanner` substrate `772`'s
own Q3 investigation examined) and re-checking the live source of `CapabilityTier.java`,
`InstallPlanner.java`, and `ModelPackage.java` in this checkout, rather than relying on the earlier
subagent's characterization alone. This produced a correction to the theorize pass's framing, and
three concrete, scoped designs.

### Correction to §Theorize: the general mechanism already exists — extend it, don't replace it

§Theorize proposed a "broader principle... every optional capability... invents its own delivery
mechanism" and floated an unbuilt "capability-delivery lifecycle" as a future direction. Having now
read 657 directly: **that system already exists, and it already documents itself as solving exactly
this.** `CapabilityTier.java`'s own docstring states it is *"Orthogonal to `DownloadProfile` (the
hardware axis, which picks the precision variant within a wanted package)"* — tier (capability
grouping) × intent (product mode) × hardware (device capability) were already designed as three
independent axes in 657, not something this tempdoc needs to invent.

The real gap is much narrower than §Theorize suggested: **one hardcoded conditional in
`InstallPlanner.java` fuses tier-identity with hardware-gating for exactly one tier**, because until
now `RUNTIME` has only ever contained a single package (`cuda-runtime`), so "is RUNTIME-tier" and
"requires CUDA" happened to mean the same thing in practice. This is the correct place to extend the
existing model, not a case for parallel new structure — matching the instruction to prefer extending
a usable existing design over replacing it.

### Design 1 — closing Q3: a package-level hardware-gate field, not a new tier or mechanism

**The fix, precisely scoped:**

- Add a package-level field to `ModelPackage` (alongside the existing `minVramBytes`) — e.g.
  `requiresCuda: boolean`, defaulting to `false`. Change `InstallPlanner`'s skip condition from
  `pkg.tier() == CapabilityTier.RUNTIME && !profile.usesCuda()` to `pkg.requiresCuda() &&
  !profile.usesCuda()`, independent of tier identity. `cuda-runtime` sets `requiresCuda: true`
  (unchanged behavior — this preserves the exact GPU_LITE-vs-full-CUDA-VRAM-threshold fix
  `InstallPlanner`'s own comment documents; conflating this with `minVramBytes` would reintroduce
  that bug). A future CPU-mandatory RUNTIME-tier package (a pack-delivered `llama-server-cpu`, if
  O1/O3/O5 is ever chosen) sets `requiresCuda: false` and is never skipped for hardware reasons —
  Q3(a) closed.
- This is not a new idiom — it mirrors `ModelPackage`'s own documented `minVramBytes: 0 = always
  include` convention, extended to the CUDA-capability axis specifically (kept as a separate field
  from VRAM-amount because they're different checks, per the reasoning above).
- **Q3(b)** (the bundled-runtime precondition can hard-fail before any pack download starts):
  reorder, don't restructure — `AiInstallService`'s presence check should consult the current run's
  `InstallPlan` (does this run include a package that would satisfy the runtime need?) before
  treating absence as fatal, rather than checking presence-at-entry unconditionally ahead of the
  download loop.
- **Q3(c)** (no precedent for shipping license/notice files inside a downloaded archive): needs no
  new mechanism in the install/extraction code path at all. Bake the notice/license files into the
  release archive itself when it's built and uploaded — only self-hosted archives (the ones
  JustSearch controls the packaging of) are candidates for carrying a GPL-2.0-obligated payload like
  Tesseract, and for those, the files arrive as ordinary zip entries through the existing
  `extractZipInPlace` step, no special-casing required.
- **Q3(d)** (preflight can't distinguish "optionally skipped" from "mandatory and missing"): a
  *derived* field, not new persisted state. `AiPreflightService`/`PackageStatus` can compute "wanted
  by the current intent and permitted by current hardware, yet still absent" using the same two
  checks `InstallPlanner` already makes, reused at read time — no new state to keep in sync.

None of this is implemented here — same standing as everything else in this tempdoc. It is scoped
narrowly enough (one new field, one reordering, one build-time packaging change, one derived read)
that it does not itself decide Q1/Q2, and does not require the owner to have chosen an option first —
it is infrastructure that O1, O3, and O5 all need regardless of which gets picked, if any does.

### Design 2 — §G: Gradle Artifact Transforms, superseding the derisk pass's hand-rolled task

The derisk pass sketched a bespoke `Jar`/`Zip`-type task consuming the resolved `onnxruntime_gpu` jar
via the `zipTree(...) { exclude(...) }` idiom already precedented in this build (CUDA variant
staging). **That sketch is superseded by this design**, in favor of Gradle's built-in Artifact
Transform mechanism: a Transform is registered once against the dependency's coordinates and produces
a trimmed variant that *any* consumer requesting that variant receives automatically — `indexer-worker`
today, but also `worker-core` or any future module that resolves the same dependency, without each
site needing its own copy-filtering logic. This is the more idiomatic, generalizing mechanism for
"make a resolved dependency smaller for our one deployment target" — the hand-rolled task would only
have fixed the one export site (`bundleSidecarResources`) this investigation happened to look at.

- **What it orphans:** the derisk pass's `Jar`-task sketch is not needed. `bundleSidecarResources`'s
  `from(workerInstallDist...)` block (`build.gradle.kts:1417`) would need a variant-selecting
  attribute on the consuming configuration, not new copy/repackaging logic of its own.

### Design 3 — §H: revert to Tauri's own default, don't invent a third custom mode

Given §Research's findings — `downloadBootstrapper` is Tauri's own considered default;
`embedBootstrapper` differs from it only by ~1.8 MB and Windows 7 support (irrelevant, not a target
platform); WebView2 ships built into Windows 11 and is already present on "most active" Windows 10
machines; and the product already carries a hard network dependency for the ~9 GB model download
shortly after install regardless — the best-evidenced design is not a bespoke third option, but to
**stop overriding Tauri's own default**: remove the `webviewInstallMode` override entirely and let it
fall back to `downloadBootstrapper`.

- **What it orphans:** the current explicit `{ type: "offlineInstaller", silent: true }` block at
  `tauri.conf.json:25-27` — named exactly, since removing an override is superseding a prior
  deliberate choice (confirmed as deliberate, not accidental, by §Research), not a silent default.

### §Reach — does this point to a broader principle?

**Principle:** *package mandatoriness and hardware-applicability are orthogonal axes; a
capability-delivery model should encode them as independent fields on the package, not fuse them into
the tier (or any other single identity) — which is exactly what `RUNTIME` currently does, for the one
package that has ever existed in that tier.*

This is not a new invention. `CapabilityTier`'s own docstring already asserts the orthogonality as
design intent. The gap is that the *implementation* doesn't yet honor that promise for the one case
where it was tested (RUNTIME), because until this tempdoc's investigation only one RUNTIME-tier
package had ever existed, so tier-identity and hardware-gate happened to coincide by accident of
history, not by design.

- **Where else it would apply:** the two live candidates already surfaced *in this same tempdoc* — a
  pack-delivered CPU-only `llama-server` runtime (§Options O1/O3/O5) and Tesseract, if ever moved to
  the pack (§Options O2/O3). Both would need `requiresCuda: false` and Design 1's baked-in-notices
  idiom. No third candidate is known today.
- **Does existing code already violate it?** Yes — `InstallPlanner.java`'s RUNTIME-tier skip
  condition, precisely as described in Design 1.
- **Evidence this would be earning its keep:** a second RUNTIME-tier (or otherwise hardware-gated)
  package is added later and is representable via `requiresCuda` without a new special-cased
  conditional — the pattern generalizing past the one instance that motivated it.
- **Retirement condition:** if neither live candidate (CPU llama-server, Tesseract) is ever actually
  moved to the pack, `requiresCuda` ends up a field with exactly one value (`true`, on
  `cuda-runtime`) that nothing else uses. At that point it isn't wrong, just unearned generality —
  and the honest reading would be that the original hardcoded tier check was fine for the one case
  that ever existed, and this design overbuilt slightly for a future that didn't arrive.

**Not everything here has reach, and forcing it would be dishonest.** Design 3 (the WebView2 mode
choice) does not carry a broader principle — it's a bounded, one-time configuration decision with no
recurring shape behind it. Named explicitly so this section doesn't read as finding reach everywhere
by default.

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

## §Addendum (2026-07-22) — implementation landed; Q3(c) resolved; Q1/Q2 reopened

Since the verdict above, a `/plan` pass implemented and merged into this branch (not yet a PR) the
three items that didn't need Q1/Q2 to be answered first: §H's WebView2 revert (commit `7e96d61c`,
CI-measured −203.6 MB), §G's Linux-native jar trim (commit `c1ee039f`, live-functional-verified —
real embeddings/SPLADE/NER inference against the trimmed jar, zero failures), and the Q3 pack-
mechanism infrastructure (commit `5f4d4dec`, full test suite green). Combined estimated installer
size: ~452 MB, down from 815 MB. Full `./gradlew.bat build -x test` and `./gradlew.bat test` pass
across all three together. Nothing has been merged to `main` or opened as a PR.

**Q3(c) is now resolved**, not just blocked-pending-owner-input (see the round-2 derisk entry above,
updated in place): the self-hosted release archives are built via a manual runbook in the separate
`justsearch-releases` distribution repo, which already has a `THIRD_PARTY_NOTICES.txt` convention for
tracking redistributed-binary license obligations. The fix is a runbook step addition, not new CI
tooling — real work, but no longer an open question about whether an attachment point exists.

**Q1/Q2 is reopened, not settled, but the calculus changed.** With §G/§H banked, the pure *size*
argument for moving `native-bin/tesseract`/`llama-server` to the pack is now weaker than at the start
of this investigation (815 MB → ~452 MB already, competitive with or smaller than comparable tools
per §Research) — on size alone, the remaining ~109 MB (tesseract + llama-server combined) is a much
smaller marginal win than it looked at the outset. **But the owner has since stated that signing-
pipeline design (tempdoc 760's domain) is a *live blocker* on the next release**, not merely a
future cost to amortize. That changes which argument in §"Why this matters" / §"Signing consequence"
actually matters: the 101→~8 signings-per-release reduction was recorded as "one input, not the
justification" when this tempdoc's main concern was download size — but reducing signing *surface*
may also reduce the *design complexity* of 760's still-unresolved problems (CI never engaging
`-Release`; no credential mode for cloud-HSM/USB-token signing) by shrinking the pile of heterogeneous
third-party binaries (74 MSYS2 Tesseract files + 19 llama.cpp files) that any interim or complete
signing solution has to correctly handle down to the ~8 Tauri/NSIS-produced artifacts its own
`should_sign` logic already covers uniformly. This is a genuinely different argument from "815 MB is
too big" — it's "a smaller, more homogeneous signing surface may unblock work that is blocking a
release right now" — and it wasn't weighed this way earlier in this tempdoc because the signing
blocker's *current, active* status wasn't known until the owner said so directly. Whether it actually
resolves 760's specific blockers (rather than just reducing volume) is a question for whoever is
doing that design work, not re-litigated here. Q1/Q2 remain unanswered, but this reframes them as
worth resolving now rather than deferring — see 760 for whether reduced signing surface actually
unblocks its open items.

## §Confidence check (2026-07-22) — a critical, not celebratory, read of where things stand

Requested directly by the owner after the implementation above landed: an honest evaluation of
confidence in the three shipped items, plus a self-correction on part of the §Addendum's Q1/Q2
reasoning. Two things were checked freshly for this pass (not assumed):

- **The combined CI installer build (all three commits together) was previously left unconfirmed**
  — a monitor watching it was stopped mid-run for being an inefficient use of CI (the two changes'
  effects are independently well-measured and additive; a third full build added little). Checked
  directly afterward: run `29874382035` **completed successfully**. This closes a real gap rather
  than leaving it as an assumption.
- **Whether the additive `PackageStatus.blockingIncomplete` field (§Design 1/Q3(d)) could break
  anything on the `ui-web` side was unverified until now.** Checked: `npm run typecheck` in
  `modules/ui-web` exits 0 (clean — the known repo-wide TS5101 pre-existing issue, per this
  worktree's expected-state baseline, did not reproduce here), and `aiInstallPoll.ts` (the confirmed
  consumer of this response shape) does plain untyped `fetch(...).then(r => r.json())` with no
  `zod`/strict schema validation — an additive backend field is genuinely inert there, not just
  probably-fine.

**Per-item confidence, named gaps included (not just "it passed"):**

- **§H (WebView2 revert) — solid on the measurement, unverified on real behavior.** The size delta
  is real and confirmed twice (CI artifact size directly, and independently by the implementing
  agent downloading and measuring the actual `.exe`). But nobody has installed and *run* the
  resulting installer on a machine — a successful CI build proves the Tauri config is well-formed,
  not that `downloadBootstrapper` mode's install-time behavior is correct end-to-end, particularly
  under silent (`/S`) install. This is the exact gap tempdoc 760 already named as pre-existing
  ("silent install never empirically verified") — this change doesn't worsen it, but doesn't retire
  it either, and the earlier report should not have implied otherwise.
- **§G (jar stripping) — strong on CPU path, untested (not just unverified) on the GPU/CUDA path.**
  The live functional check is genuinely good evidence: real embeddings/SPLADE/NER inference against
  the trimmed jar, zero failures, on an actual dev-stack run. But that run almost certainly exercised
  the CPU execution provider only — the retained `win-x64` `onnxruntime_providers_cuda.dll` was
  never actually loaded or exercised by any check in this pass. Its bytes are untouched by the
  change (only `linux-x64/**` entries were removed), so the risk is low, but "we didn't touch it" is
  a weaker claim than "we tested it," and the two were presented as more equivalent than they are.
  The config-cache incompatibility the implementing agent flagged (`bundleSidecarResources` fails
  config-cache *store*, pre-existing and not caused by this change) is now confirmed not to break the
  combined CI build in practice (see above) — that part of the concern is resolved by evidence, not
  assumption.
- **Q3 infrastructure — solid unit coverage, no live "Install AI" click-through.** The implementing
  agent's handling of a genuine ambiguity in its own brief (the `planSuppliesRuntime` question) was a
  real positive signal — it reasoned toward the stated invariant rather than the more literal (and
  wrong) reading, and locked the choice in with a dedicated test. But every check across this item
  was a unit test; nobody exercised the real `AiInstallService`/`AiPreflightService` code paths via
  an actual running dev stack. Low risk since `requiresCuda:false` has no real producer in production
  today (the new code paths are provably dormant), but "provably dormant" is carrying real weight in
  that confidence claim, not a substitute for having run it live.

**A walked-back claim, recorded rather than quietly dropped.** The §Addendum above argued that
reducing `native-bin`'s unsigned-PE surface might reduce the *design complexity* of tempdoc 760's
signing blockers, not just their per-signature cost. On reconsideration, that's weaker than it was
made to sound: 760's two specific blockers — CI never passing `-Release`, and `sign-windows.ps1`
having no credential mode for cloud-HSM/USB-token signing (only PFX) — are both about the *signing
mechanism itself*, not about how many files pass through it. Building a cloud-HSM code path is
exactly as much engineering work whether it then runs against 8 files or 101; reducing surface does
not make that specific design problem easier to solve. What plausibly *does* hold, and is a
different, narrower claim: (a) lower ongoing *cost* once some paid signing solution is adopted
(already captured correctly in §"Signing consequence" as "one input, not the justification"), and
(b) a **manual/interim signing stopgap becomes far more operationally feasible at ~8 files than at
~101** — someone running `signtool` by hand against a small, homogeneous set is realistic; against a
large, heterogeneous pile of third-party binaries it likely isn't. Whether (b) is actually what's
blocking the next release, versus the mechanism problems being the real blocker regardless of
surface, has **not been checked against tempdoc 760's specifics** — this remains an open, unverified
question, not a settled part of the case for Q1/Q2, and should be checked directly against 760
before being relied on.

**Still genuinely open, distinct from "deferred":** Q1/Q2 itself; whether reduced native-bin surface
actually unblocks any of 760's named blockers (unchecked); actually authoring a real pack-delivered
package instance if an option is ever chosen (Q3 built the *mechanism* only, no real producer exists
yet); Q3(c)'s runbook step (a manual, easy-to-forget addition, not automated); Q3(d) (deliberately
parked, owner-confirmed low priority until a real `requiresCuda:false` package exists). Nothing here
has a PR open or has been merged to `main`.

## §Second takeover verdict (2026-07-22, Fable) — verification of the landed work + a recommendation that closes Q1/Q2

**Method.** Independent re-verification, not re-derivation: read the full tempdoc and all three
implementation diffs; confirmed the combined CI installer build the §Confidence check cites
(`gh run view 29874382035` → `completed` / `success`, branch `worktree-772-installer-payload`);
read tempdoc 760's live Findings table directly (its two signing blockers verbatim); confirmed in
`modules/ui/build.gradle.kts` that the trimmed-jar swap is wired exactly as described
(`exclude("onnxruntime_gpu-*.jar")` + trimmed replacement into `lib/worker/`) and that
`stageLlamaServerFromPrebuilt` sources the llama-server payload from a **pinned upstream prebuilt
zip** (`llamaPrebuiltVersion`), i.e. byte-stable across releases until the pin is bumped — a fact
that turns out to be load-bearing below. No discrepancies found between the tempdoc's claims and
the code/CI reality.

**Quality read on the prior sessions' work: sound, and unusually honest.** The three landed changes
are correctly scoped (§H is a pure override-removal; §G touches only the Windows staging path,
leaving the Linux CI lane's resolution untouched; Q3 is behavior-preserving by construction with
tests pinning exactly that). The §Confidence check's walked-back claim was walked back correctly —
760's two named blockers (CI never passes `-Release`; `sign-windows.ps1` is PFX-only with no
cloud-HSM/token mode) are *mechanism* work whose cost is independent of how many files flow through
it. Nothing in the landed work needs re-doing.

**Two observations that further weaken the remaining case for O1/O2/O3 (moving tesseract/llama-server
to the pack), beyond the walk-back already recorded:**

1. **Moving unsigned PEs to the pack does not dodge the trust problem — Smart App Control gates
   execution, not delivery.** A pack-delivered `llama-server.exe` arrives just as unsigned as a
   bundled one and is blocked at *launch* on a SAC-enforcing machine regardless of route (this
   repo's own `package-installer-win.ps1` SAC self-diagnosis is a live instance of SAC blocking
   unsigned executables at run time). So if the goal of signing is "the product works on
   locked-down machines," the 93 third-party PEs need signatures *wherever they live* — the move
   changes who signs them and when, not whether. The 101→~8 framing quietly equates "fewer
   bundler-signed files" with "less signing needed," which holds only if pack-delivered binaries
   are allowed to stay unsigned forever.
2. **The 101-signings-per-release arithmetic assumes re-signing identical bytes every release —
   but the payload is pin-stable, and `should_sign` skips already-signed PEs.** The 93 unsigned
   third-party binaries come from pinned, versioned upstream prebuilts that do not change between
   releases (only when the pin is bumped). Since the bundler verifiably skips PEs that already
   carry a valid signature (§Signing consequence, confirmed from Tauri source), **signing the
   vendored binaries once per upstream bump — e.g. hosting signed mirrors on `justsearch-releases`,
   where two of the four cuda-runtime archives are already self-hosted — collapses per-release
   signings to ~8 with zero payload movement, zero UX change, and zero pack-mechanism work.**
   ~93 signatures per upstream bump (a few times a year) vs. per release is a far better cost
   curve than O1-O3's, and it also *solves* observation 1 (the binaries end up signed) instead of
   relocating it. This belongs in 760's signing-pipeline design space, not here — recorded so 760's
   designer sees it.

**Verdict: the tempdoc's remaining open work should be closed, not extended.**

- **Should it have been done at all? Yes — and it's done.** The two banked wins (§G −190 MB, §H
  −204 MB, combined 815 → ~452 MB) were near-zero-downside fixes of the "accumulated, not chosen"
  kind §Theorize named, and the evidence work here (three re-extractions of a now-expired CI
  artifact) is not reproducible later. The Q3 infrastructure is behavior-preserving and honestly
  carries its own retirement condition.
- **Should O1/O2/O3 be done now? No — recommend closing Q1/Q2 with O0 (status quo).** After §G/§H,
  the size argument is marginal (~109 MB on a ~452 MB installer already ~3x smaller than Ollama's,
  per §Research); the UX argument is net-negative (new consent gates, new failure points); and the
  signing argument — the only one that recently gained urgency — is better served by
  sign-once-per-bump (observation 2), which costs less than O1-O3, keeps first-run UX intact, and
  actually results in signed binaries on user machines. "This should not be done" is the honest
  option-table outcome here.
- **Cheapest validating/invalidating evidence for that recommendation, and does it exist?** It now
  does: the pin-stability of the payload + the confirmed `should_sign` skip are both verified in
  this tempdoc; together they falsify the per-release signing-cost premise that made O1-O3 look
  necessary. The one check that would *re-open* O1-O3: if 760's design work concludes signed
  mirrors are infeasible (e.g. no signing setup can be run against vendored archives at all), the
  per-release framing returns — that check belongs to 760.
- **What does it displace/duplicate? Nothing** — confirmed unchanged from the first takeover
  verdict; 760/759/761/374 remain disjoint, and observation 2 above is an *input to* 760, not a
  duplication of it.

**LITE-CLASS: no** (unchanged — this was evidence-gathering plus three behavior-affecting changes,
not pure teardown).

**Residual verification gaps, inherited not new:** no end-to-end install of the new
`downloadBootstrapper` installer on a real machine (this folds naturally into 760's Phase 2 item 1
— the same clean-sandbox silent-install run verifies both); CUDA provider path untested against the
trimmed jar (low risk — win-x64 bytes untouched — but unexercised).

## §I — The completed needs audit (2026-07-22, second takeover, owner-directed)

The owner clarified this tempdoc's **main intention**: not "find big removable things," but *"what
do we actually need in the installer, versus what's currently in it."* Measured against that
intention, the prior passes were suspect-driven, not needs-driven: §H's top-level accounting was
complete, but inside `resources/headless/` only ~530 of 844 MB had ever been itemized — **~314 MB
was never listed, let alone justified** — and the largest flagged-but-unresolved item (Q9, the
win-x64 CUDA EP DLL) was characterized but never traced. Both gaps are now closed, via two parallel
subagent investigations against the **post-fix CI artifact** (run `29874382035` — which also
independently re-verifies both landed fixes in the real shipped artifact: `onnxruntime_gpu-trimmed.jar`
present at 172,119,741 bytes; `MicrosoftEdgeWebView2RuntimeInstaller.exe` absent).

### Complete inventory (run `29874382035`: installer 452,720,845 B compressed / 679,110,948 B extracted; every sub-sum reconciled exactly)

| Component | Bytes (extracted) | % | Needs verdict |
|---|---:|---:|---|
| `lib/worker/onnxruntime_gpu-trimmed.jar` | 172,119,741 | 25.3% | **Needed for GPU users** (see Q9 below); its ~163.6 MB CUDA EP DLL is inert on CPU-only machines — a genuine tradeoff item, not waste |
| `lib/worker/` other jars (182) | 149,231,182 | 22.0% | Needed (worker classpath) — but see duplication row |
| `lib/` head jars (134) | 118,849,445 | 17.5% | Needed (head classpath) — but see duplication row |
| — of which **byte-identical duplicates across the two classpaths** | 96,908,586 (redundant side) | 14.3% | **The one significant remaining "nobody chose this" finding** — see below |
| `native-bin/tesseract` | 79,659,851 | 11.7% | Chosen tradeoff (out-of-box OCR; Q6/O2 — recommendation: keep, per §Second takeover verdict) |
| `runtime/` (jlink'd JRE) | 53,925,635 | 7.9% | Needed; already trimmed (27.1 MB `modules`, 15.4 MB `jvm.dll`); O4 checked → low further yield |
| `worker.aot` + `head.aot` | 37,224,448 | 5.5% | Chosen tradeoff (JEP 514 startup speed vs bytes) — deliberate, keep |
| `native-bin/llama-server` | 33,100,809 | 4.9% | Chosen tradeoff (chat runtime pre-staged; O1 — recommendation: keep, per §Second takeover verdict) |
| `vc_redist.x64.exe` | 25,635,768 | 3.8% | Chosen tradeoff (offline CRT install; Q5's other half — small, keep) |
| `JustSearch.exe` + `$PLUGINSDIR` | 8,555,390 | 1.3% | Needed (shell + NSIS) |
| `ui-headless.jar`, `SSOT/`, launchers, config | 808,679 | 0.1% | Needed |

Waste-vs-tradeoff classification (§Theorize's own lens), post-fix: **the only remaining
accumulated-not-chosen bytes are the 96.9 MB of duplicated jars** (94 byte-identical jars shipped
in both `lib/` and `lib/worker/`; largest: tokenizers 18.7 MB ×2, icu4j 14.7 MB ×2,
grpc-netty-shaded 10.7 MB ×2, bcprov 9.0 MB ×2, lucene-core 4.6 MB ×2). Everything else in the
installer is either needed or a documented, deliberate tradeoff. Caveats on the duplication lever:
the *installed-footprint* win (~97 MB) is certain, but the *download-size* win is uncertain (NSIS
compression may or may not deduplicate across the solid archive — unmeasured); and whether the two
process classpaths *can* share a jar directory is a real design question (Head/Worker isolation,
`lib/worker/` deliberately staged so Head's `-cp lib/*` doesn't pick it up, per the staging
comment) — this is a lever to *charter deliberately*, not a fast-track fix. Four further jar pairs
ship at *different* versions per process (jackson-core/databind 3.1.0 vs 2.20.0, kotlin-stdlib,
commons-text) — a consistency observation (logged to the inbox), not a size lever (~4 MB).

### Q9 — RESOLVED: the win-x64 CUDA EP DLL is load-bearing, not removable

Full code trace (opus subagent, file:line evidence): ONNX Runtime's Java loader extracts
`onnxruntime_providers_cuda.dll` **from the classpath jar itself** to `%TEMP%/onnxruntime-java*/`
when `SessionOptions.addCUDA()` fires (`OrtCudaHelper.java:24-28,99-106`;
`NativeSessionHandle.java:692-698`). The pack-delivered `cuda12` directory carries only the CUDA
*dependency* DLLs (cudart/cublas/cudnn…, `OrtCudaHelper.java:46-70`) — never the EP DLL — and no
code wires ORT's per-library native-path override that would let it load an EP DLL from outside the
jar (repo-wide grep: zero hits). `stageOrtCudaVariant` (`build.gradle.kts:747-796`) is a dev-only,
CI-disabled staging of EP DLLs that nothing points ORT at — a chartered-but-unimplemented route,
not an existing one. If the DLL were trimmed like the Linux natives, GPU users would **silently**
fall back to CPU inference (`NativeSessionHandle.java:629-634` degrades rather than fails). On
CPU-only machines the DLL is never extracted (provider extraction is lazy, `addCUDA`-triggered).
**Verdict: keep in the jar. Moving it to the pack is possible in principle but is O1-class work
(new ORT path-override wiring or post-install jar replacement) with O1-class objections — not a
fast-track candidate.** §G's original "inert until GPU+pack, but needed" characterization was
correct; the second takeover's suspicion that it might be fully redundant is refuted by the trace.

### Where this leaves the intention

The needs audit is now **complete**: every byte of the shipped installer is inventoried, classified
needed / chosen-tradeoff / waste, and the classification is evidence-backed. Post-fix state:
452.7 MB download, of which the honest "floor" (if every tradeoff were traded the other way —
dedupe the jars, move every optional capability to the pack) is roughly 240-280 MB extracted —
but per §Second takeover verdict, most of those trades are not recommended. The one remaining
lever with real upside and no UX cost is the classpath duplication (~97 MB installed, download
effect unmeasured), which needs its own deliberate charter if pursued.
