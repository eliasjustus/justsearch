---
title: "Installer payload composition: what belongs in the base installer versus the consent-gated download pack — the base installer ships an inference runtime it cannot run, and the pack mechanism that would carry it already exists"
type: tempdocs
status: "open — ANALYSIS ONLY (2026-07-21). Evidence measured against a real 815 MB CI artifact (run 29514086160, 743 files, 177 PE binaries, every PE signature-checked). Takeover investigation (2026-07-21) independently re-verified all citations (spot-checks + subagent pass: no wrong citations, two minor line-range imprecisions) and resolved §Open question 7 (byte-level size win, §F) — combined payload is ~10.4% of the installed tree, not the dramatic reduction the PE-count framing suggested. No decision reached, no split recommended; §Open questions are for the owner. See §Takeover verdict. Nothing implemented."
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
signature (the `should_sign` filter, bundler ≥ 2.6.0). **This is an upstream-behavior claim that
could not be verified from this checkout** — `tauri-bundler` is a build-time tool, absent from
`modules/shell/src-tauri/Cargo.lock`, and `modules/shell/package.json:10` pins only
`"@tauri-apps/cli": "^2"` (unpinned minor). The app crate is Tauri 2.11.3
(`Cargo.lock:3914-3915`). A follow-up should confirm against the bundler source before this
arithmetic is relied on.

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
   evidence than the brief assumed. What is **not** established: (a) whether a package can be
   *required for a non-GPU code path* — `cuda-runtime` carries `minVramBytes: 0` and `tier:
   "runtime"`, and preflight/hardware-profile logic (`AiInstallService.java:428-429`, `:666-685`)
   gates on GPU detection; a CPU-tier native package may need new tier semantics; (b) whether
   `RuntimeRestoreUtil.ensureRuntimePresent` (`:34-74`) tolerates the bundled source being absent,
   or fails/degrades badly; (c) whether Tesseract's GPL-2.0/NOTICE obligation is satisfiable through
   a downloaded archive; (d) whether `AiPreflightService` would report a coherent state for a
   never-downloaded CPU runtime. **Investigate before assuming.**

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
   - `scripts/smoke-tests/verify-gpu-bundle.ps1`, `scripts/dev/doctor.mjs`,
     `scripts/dev/run-headless-api.ps1`, `scripts/dev/test-dev-runner-runtime-resolution.mjs`,
     `scripts/dev/justsearch-dev-mcp/server.mjs`, `scripts/ai/token-probe.ps1`,
     `scripts/codegen/gen-notices.mjs` (+ its `.test.mjs`) — all reference `native-bin`. Most are
     dev-path resolution rather than payload assertions, but each needs checking, not assuming.
   - Nothing in `governance/*.json` references `native-bin` or `tesseract` (grep, empty). No
     discipline-gate coupling found.

5. **Does `vc_redist` / WebView2 bootstrap change?** Current behavior: `vc_redist.x64.exe` is a
   declared bundle resource (`tauri.conf.json:21`) and WebView2 uses
   `webviewInstallMode: offlineInstaller, silent: true` (`:25-28`) — i.e. the *offline* WebView2
   bootstrapper is embedded rather than downloaded. Both are Microsoft-signed, so both are
   signing-cost-neutral. **Question:** are they byte-relevant, and is embedding the *offline*
   WebView2 installer still the right call if the product is accepting a network dependency for
   other payloads anyway? Switching to the online bootstrapper is a separate, smaller lever that
   trades bytes for an install-time network requirement.

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

**Reading — this narrows §"Why this matters" reason 1.** A ~10% download-size reduction is real but
modest; it is unlikely to be the deciding factor on its own for a product with zero recorded user
complaints about install size (§"Why this matters" #1). The signing-cost reduction (101→~8
signings/release, §Signing consequence) and the first-run UX/parity questions (Q1, Q2) are where
the actual decision weight sits, not size. This is evidence *against* over-weighting the size
argument in isolation — it does not by itself favor any option in §Options.

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
6. **The Tauri `should_sign` claim is not verifiable from this checkout** (§Signing consequence).
   `tauri-bundler` is not in `Cargo.lock` and the CLI is pinned only to `^2`. The 101 / ~8
   arithmetic is arithmetically sound given the claim and given §A's measured counts, but the claim
   itself is upstream-sourced and unverified here. Flagged rather than propagated silently.
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
product-shape decisions, not things a follow-up investigation can resolve. §Q3 (does the pack
mechanism actually support a CPU-tier *required* package, not just an optional GPU one) is
implementation-adjacent but still needs an owner-approved direction before it's worth spending
design effort on any one option.

**What was the cheapest validating evidence, and did it already exist?** No — it didn't exist
before this pass. §Open question 7 (the byte-level size win) was the one purely-empirical,
non-owner-gated question left on the table, and it was cheap: re-download + re-extract + `du -sb`,
no owner input needed. It's now answered (§F): moving both payloads to the pack saves **~10-11% of
the installer**, not the dramatic cut the "93 unsigned PEs" / "101→~8 signings" framing might
imply on its own. That reframes the decision — the size argument is real but minor, so whichever
way the owner leans on Q1/Q2/signing cost should be understood as the actual driver, not size.
**This evidence will not be re-obtainable from this exact artifact after 2026-07-23** (its GitHub
Actions retention expiry) — worth noting since re-measuring later means trusting a different build.

**What does it displace or duplicate?** Nothing currently shipped or planned — there is no
competing analysis of installer payload composition, and 760/759/761/374 are confirmed disjoint
(see §Method). It does not obsolete or conflict with any of them.

**LITE-CLASS: no.** This is evidence-gathering and decision-framing for a payload/UX/signing
tradeoff, not a pure teardown, rename, or config-delete — it does not qualify even provisionally.

**State:**
- **BLOCKED ON YOU:** Q1 (organizing principle for what belongs in the base installer vs. the
  consent-gated pack — does excluding chat/OCR from first-run "hollow out" the offline-first
  pitch, or just make explicit what's already true?) and Q2 (how much first-run UX friction, in
  the form of new consent prompts, is acceptable in exchange for the ~10-11% size win described
  above). Both are owner product-shape calls; no amount of further investigation resolves them.
- **PROCEEDING / DONE (this session):** Independent verification of all prior citations (clean);
  §Open question 7 answered with measured numbers (§F); tempdoc updated in place with both. No
  design or implementation work was started, per the takeover charter — that stays gated on your
  answer to Q1/Q2 above.
