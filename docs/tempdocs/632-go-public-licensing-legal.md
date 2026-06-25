---
title: "Go-public (Option C) — open-source licensing & legal completeness, designed as attribution-as-projection: single-source the license data (model-registry license field + native-binary manifests + the dep generators), generate the notices (NOTICE / THIRD_PARTY_NOTICES / canonical-doc table / gate input) as projections, and close the drift with a sync gate — plus SPDX headers, the license-allowlist gate, the two native-binary reviews (NVIDIA CUDA re-hosting, Tesseract LGPL/GPL DLLs), contributor terms (DCO, no CLA), trademark, and EU-CRA scoping. Workstream 2 of 4."
type: tempdoc
status: "implemented + merged to `main` (2026-06-24) — Stages A–F complete (attribution-as-projection: the registry license SSOT, the `NOTICE`/`THIRD_PARTY_NOTICES` projection + drift gate, the native-binary manifests incl. the Rust/Cargo tree, the license-allowlist gate). SPDX headers (Stage G) are a deferred follow-up PR. The flip-gated validation (hosted-lane `checkLicense`/DCO) + the founder legal calls (NVIDIA notice-retention, libjbig packaging, trademark/slug) are registered in tempdoc 634."
created: 2026-06-22
updated: 2026-06-24
related:
  - 631-go-public-publish-machinery
  - 633-go-public-launch-content
  - 634-go-public-cutover-transition
---

# Go-public (Option C): open-source licensing & legal completeness

## Context
Legal completeness for the Apache-2.0 release. Every shipped model is Apache-2.0 or AFL-3.0 (redistributable +
commercial-OK); deps are clean (no GPL/AGPL/SSPL in the Java/npm trees). What remains is **paperwork** + two
**native-binary** redistribution items + a few **decisions**.

## Scope
1. **License paperwork** `[agent][live]`
   - Generate `THIRD_PARTY_NOTICES`: `./gradlew.bat generateLicenseReport` (jk1 plugin —
     `build.gradle.kts:9,600-608`) → curate the JSON/HTML report into a committed file.
   - **Complete `NOTICE`** (today it lists only Lucene/Tika/llama.cpp/Tauri + references a nonexistent "licenses
     directory"): add ONNX Runtime (MIT), Javalin/DJL/Jetty, the 6 models — incl. the **AFL-3.0 NER** (attribute
     *as* AFL-3.0) + Apache Qwen/GTE/SPLADE/MiniLM — Tesseract, and the NVIDIA attribution string. **Reuse the
     `generateOnnxNotice` task** (`modules/ui/build.gradle.kts:397`) for the model attributions.
   - **SPDX headers** codemod over `modules/**/src/main/**/*.{java,ts}` (0 today; M2 deliverable).
   - **Enforce the license gate** — the jk1 plugin is report-only; add an `allowedLicenses` allowlist + wire
     `checkLicense` into the public CI so a future copyleft dep fails the build.
2. **Native-binary review** `[founder/legal]` — the two real risks:
   - **NVIDIA CUDA/cuDNN** re-hosted as standalone zips (`model-registry.v2.json` `cuda-runtime`): the EULA
     permits redistribution only "incorporated into / accessed only by your application" → review, or bundle the
     DLLs inside the app artifact only.
   - **Tesseract** bundled DLLs (cairo/pango **LGPL-2.1**; libgcc/libstdc++ **GPLv3 + Runtime-Library-Exception**)
     → ship the license texts + keep the libs dynamically replaceable, or rebuild Tesseract without cairo/pango.
3. **Contributor terms + trademark + CRA** `[agent draft / founder decide]`
   - **DCO** (chosen, no CLA) — add a DCO CI check; `CONTRIBUTING` reflects it. Note: this **forecloses a
     unilateral relicense** — fine, since the open-core plan is *additive* (paid = separate modules), not a relicense.
   - **Trademark:** the provisional `justsearch` name — collisions / protectability (→ sidecar `legal/`).
   - **EU CRA scoping note** (sharpened 2026-06-23 against new guidance — see "CRA guidance refresh" below):
     the FOSS carve-out applies to the genuinely-**non-monetised** core; the open-core **paid tier** most likely
     classifies the founder as a **manufacturer for that paid product** (full obligations: conformity assessment,
     technical docs, CE) — *not* merely a light-touch "steward". Reporting duties from **11 Sep 2026**, main
     obligations **11 Dec 2027**. Where the free/paid line sits is now a CRA-classification decision, not just a
     product one. **[founder/legal.]**

## Done
Every shipped dependency + model is attributed (`NOTICE` + `THIRD_PARTY_NOTICES`); the license gate passes; the
two native-binary items have a recorded decision; DCO is enforced in CI.

## Dependencies
Parallel with 631 + 633. **Feeds 634** — the snapshot needs the finished license files.

---

## Implementation status (2026-06-23 — worktree `632-licensing`, branch `worktree-632-licensing`)

**Stages A–F implemented + self-verified; Stage G (SPDX) deferred.** 3 commits on the branch.

- **A — registry license SSOT ✓** `license` (SPDX) field on every `model-registry.v2.json` package (both byte-identical
  copies); threaded through `ModelPackage`/`ModelRegistryLoader` (back-compat secondary ctors; loader already
  ignores unknown props); `everyPackageDeclaresALicense` test green (`:modules:configuration:test`).
- **B — projection generator + gate ✓** `scripts/codegen/gen-notices.mjs` projects root `NOTICE` +
  `THIRD_PARTY_NOTICES` (+ a registry-projected `packaging/runtime/NOTICE-MODELS.txt`) from the jk1 report + an
  npm `license-checker` dump + the registry + the native manifests; `scripts/ci/check-notices-regen.mjs` is the
  drift gate; `gen-notices.test.mjs` (3 tests) green; deterministic; `generateOnnxNotice` re-pointed to stage the
  projected file (now includes Qwen, which the old heredoc dropped).
- **C — native manifests ✓** `packaging/runtime/tesseract-bundled-libraries.v1.json` = the 56-DLL license table
  (exact-coverage verified; copyleft flagged: `libjbig` GPL-2.0, GCC GPL-w-exception, the LGPL family);
  llama.cpp + NVIDIA (incl. the separate-cuDNN-SLA note) in `license-overrides.json`. (The GPU-variant
  `NOTICE-NVIDIA-CUDA.txt` heredoc writers left as-is — stable + correct; re-pointing is low-value.)
- **D — license gate ✓** `config/allowed-licenses.json` (permissive by name; dual-licensed deps + junrar/jcip by
  `moduleName`); wired `allowedLicensesFile`; added the missing OTel/okhttp/guava POMs to
  `verification-metadata.xml`. **`checkLicense` is GREEN without `lenient`** (`--no-configuration-cache
  --no-parallel`). junrar/UnRar = accept-and-document (reversible) **[founder]**.
- **E — docs ✓** canonical `ai-runtime-and-model-redistribution.md` updated (v1/Qwen3-VL→v2/Qwen3.5-9B; separate
  cuDNN SLA + re-hosting caveat; Tesseract copyleft DLLs incl. `libjbig`; 374 G12 THIRD_PARTY_NOTICES marked
  CLOSED); `CONTRIBUTING.md` React→Lit + the dead `scripts/gate.ps1` refs fixed (founder-slug scrub left to 631).
- **F — CI authored ✓ (validation defers to 634)** `public-ci.yml` gains `checkLicense` (+ the required flags),
  the npm dump, `check-notices-regen`, and a real `tim-actions/dco` job; local `check-notices-regen` green. The
  green-on-hosted-lane + DCO-app install can't exist pre-flip (631/634).
- **G — SPDX headers: DEFERRED.** Codemod worked (1966 files, idempotent, TS typecheck green, trial module
  compiled + passed Spotless), **but** it entangles with a **pre-existing class-size gate failure**: 4 untouched
  files (`HeadlessApp` +10, `EnvRegistry` +14, `HeadAssembly` +4, `KnowledgeServer` +5) are **already over their
  pinned ceiling at HEAD** (stale `class-size-exceptions.txt` pins post-628/main merges; undetected because CI is
  manual-only). The header would also +1 sixteen at-ceiling files (legitimate baseline bumps). SPDX is the
  explicitly-deferrable M2 item, so it is unbundled into its own PR (which must also re-pin the stale baselines and
  fix a codemod path-match bug that stamped `scripts/governance/_fixtures/**`). Logged the pre-existing drift to
  the inbox.

**Post-review fixes (2026-06-23, commit `65b8b372e`)** — a critical review of A–F found 3 substantive gaps, all fixed:
- **[major] Rust/Cargo tree was unattributed.** The Tauri shell statically links **544 crates** (shipped, but
  absent from THIRD_PARTY_NOTICES + unchecked for copyleft). Added `dump-cargo-licenses.mjs` (`cargo metadata`,
  built-in/offline) + a Rust section + a **strong-copyleft (GPL/AGPL/SSPL) + UNKNOWN presence guard** over the
  npm+Cargo trees (the JVM-only jk1 gate misses these). Tree verified clean (no GPL/AGPL; MPL-2.0 + permissive
  duals only). So all **five** shipped trees now project.
- **[moderate] the closure gate was dormant** (only in the future `public-ci.yml`). Wired `checkLicense` + the
  cargo/npm dumps + `check-notices-regen` into the **internal `ci.yml` `full_build` job** — live on CI dispatch now.
- **[minor] jcip-annotations rendered `UNKNOWN`** → CC-BY-2.5 notice override + the UNKNOWN guard above.

**Verification:** `:modules:configuration:test` green; `checkLicense` exit 0 (no lenient); `gen-notices --check`
no drift; `gen-notices.test.mjs` green; `npm run typecheck` green; `build -x test` fails **only** on the
pre-existing `class-size` gate (4 untouched files) — A–E introduce **zero** new failures. No browser validation:
agent scope has no user-visible feature (the in-app notice viewer G-B is a founder design call).

**Open for the founder/user:** (1) the pre-existing class-size drift blocks a clean pre-merge `build -x test` —
re-pin the 4 stale baselines (overdue ratchet maintenance per the file's own comments) vs. a separate decomposition
PR; (2) the merge of A–F given that pre-existing red gate; (3) SPDX (G) as a follow-up PR; (4) the standing
[founder] calls — NVIDIA hosting posture, junrar/UnRar, trademark, in-app notice viewer (G-B), SBOM (G-C).

## Native-binary cleanup — confidence pass (2026-06-23; throwaway trial + binary analysis + web)

- **junrar — 9/10. PROVEN by a throwaway trial (in-main, fully reverted).** Added the 2 `exclude`s, ran
  `resolveAndLockAll` (clean — 6-file/3+4 diff, succeeds), `checkLicense` GREEN, and the freshly-generated jk1
  report has **0 junrar** → junrar is off every production `runtimeClasspath`. One refinement the investigation
  missed: junrar lingers only on `indexer-worker:testFixturesRuntimeClasspath` (test-only, not shipped, not in the
  license report — harmless for licensing; a module-level `configurations.all { exclude(group="com.github.junrar") }`
  gives 100% removal if wanted). Remaining real work: the excludes + regen the 5 lockfiles + the `verification-metadata`
  junrar block (becomes unused — remove for cleanliness) + drop junrar from `allowed-licenses.json` + `license-overrides.json`
  (the J check needs both) + regen the notices (junrar leaves THIRD_PARTY_NOTICES). ~30–45 min, low-risk.
- **libjbig — now FULLY CLARIFIED (2026-06-23 PE-analysis + jbigkit license check). The decision is a clean 3-way
  founder/legal call; everything agent-resolvable is resolved.**
  - **Linkage type — SETTLED by the PE import table** (`pefile` parse of `libtiff-6.dll`): `libjbig-0.dll` is a **HARD
    load-time import** (regular Import Directory; **zero** delay-load entries). So the Windows loader resolves it when
    libtiff loads → **deleting the DLL breaks libtiff entirely → Leptonica/Tesseract OCR fails for ALL images** at
    OCR-path startup, not just JBIG-encoded TIFFs. This **kills the "delete-the-DLL, lose only JBIG-TIFF" quick-win** I
    floated; removal genuinely requires a libtiff `--disable-jbig` rebuild that drops the import.
  - **libjbig is the LONE copyleft import.** The same dump shows libtiff's other imports — libdeflate, libjpeg-8,
    liblerc, liblzma-5, libwebp-7, zlib1, libzstd — are all permissive. So this is a single, isolated GPL codec.
  - **jbigkit is DUAL-LICENSED** (web-verified): GPL-2.0 **or** a paid commercial license via Cambridge Enterprise
    (`software@enterprise.cam.ac.uk`). And the **author's stated intent is that dynamic linking into a combined work
    makes that work fall under GPL** — Kuhn explicitly rejects the "dynamic link = mere aggregation" escape. That makes
    keep-and-document the *legally riskiest* path for an Apache-2.0 product, not merely the cheapest.
  - **Current state is a compliance GAP regardless of the decision:** we ship `libjbig-0.dll` (GPL-2.0) but ship **no
    GPL-2.0 license text and no corresponding-source / written offer** (only a registry + legal-doc *flag*). If the
    binary ships at all, GPL-2.0 §3 obligates shipping the license text + the jbigkit source (or a written offer).
  - **The decision space is exactly THREE, each characterized:**
    1. **Rebuild** libtiff `--disable-jbig` (+ rebuild leptonica/tesseract or re-pin a jbig-free libtiff) → host + re-pin
       `tesseract-windows.v1.json` + re-validate the DLL set. **Eliminates the GPL.** LARGE native-build, mostly
       founder-infra. (Hard-import proof confirms there's no cheaper mechanical route.)
    2. **Commercial license** from Cambridge Enterprise → keep the binary, **removes the copyleft obligation.** No code;
       a cost + paperwork the founder owns (price unknown until contacted).
    3. **Keep + full GPL compliance** (ship GPL-2.0 text + jbigkit corresponding source/written offer + keep it
       dynamically replaceable). Cheapest in effort, but leaves GPL code in an Apache-2.0 product AND carries the
       author's dynamic-linking-propagates-GPL intent → the legally-riskiest for a permissive go-public.
  - **What REMAINS — founder/legal only, not agent-resolvable:** the choice among the three; the commercial-license
    price (founder must contact Cambridge Enterprise); and counsel's read on whether Kuhn's dynamic-linking intent
    actually reaches JustSearch's bundle (I can characterize the risk, not adjudicate it).
- **NVIDIA — 8/10 (was 7), Gap A now closed at the source.** The offline runtime-pack import (`AiPackImportService` +
  `AiPackValidator` + `AiPackController` + routes + `RuntimeActivationService`) is fully wired and TESTED (not the
  draft-vapor the doc's status implied); the llama side consumes a pack identically to the download. The pack format
  already accepts any `*.dll` (cuDNN/ORT DLLs are valid payload). Repackaging the two zips is feasible (manifest +
  per-file SHA + allowlist entry).
  - **Gap A — RESOLVED (source-verified 2026-06-23).** Pack routing is unambiguous: `PackInstallOps.resolveRuntimeDestRoot`
    (`:363-370`) sends **only** `runtime.onnxruntime`/`runtime.onnxruntimeFile` roles to the unread `onnxruntime/variants/<id>/`
    tree; **every other role** lands in `native-bin/llama-server/variants/<id>/`. That llama variant dir is read by BOTH
    consumers: (1) the llama launch — `RuntimeActivationService:368` runs `variants/<id>/llama-server.exe`; (2) **ORT's CUDA
    *runtime* DLLs** — `WorkerSpawner:506-510` derives `justsearch.onnxruntime.native_path` *from the llama-server variant ID*,
    and `GpuAutoDetection.searchConventionalPaths` (`:84-91`) detects that same dir by sentinel + CUDA-DLL presence. ORT's own
    EP DLLs (`onnxruntime_providers_cuda.dll`) **auto-extract from the `onnxruntime_gpu` JAR** (`GpuAutoDetection:103-105`,
    `OrtCudaHelperTest:49-50`) and need no packing at all. **Conclusion:** pack the CUDA runtime DLLs (cudart64_12 / cublas64_12
    / cublasLt64_12 / cudnn*64_9 family) with a **generic (non-onnx) role** so they install to `variants/cuda12/`; do **not**
    pack the EP DLLs, and do **not** use `runtime.onnxruntime*` (that's the trap that lands them in the dead tree). With that
    role assignment a pack-installed cuda12 variant is **provably equivalent** to the legacy bundled install for both consumers.
    (My pass-#1 note said "pack as `runtime.runtimeFile`" — directionally right; the source-precise rule is "any non-onnx role,"
    and the EP DLLs are explicitly *excluded* from the payload.)
  - Residual (minor): `variantId` must be `cuda12`; confirm the pack size-budget tolerates ~1.2 GB; `NOTICE-NVIDIA-CUDA.txt`
    must add cuDNN's *separate SLA* (today CUDA-only). UX shift: auto-download → import-an-allowlisted-pack (the designed
    security posture). Founder-infra: produce/distribute the pack + retire the public release assets.

**Per-item confidence for the remaining work:** junrar **9/10** (proven, quick), NVIDIA **8/10** (medium effort, real
path, Gap A source-verified-equivalent to the bundled install; founder produces the pack), libjbig **clarity now 9/10
(was 4/10)** — every agent-resolvable question is answered (hard-import → no delete-shortcut; lone copyleft import;
dual-licensed; current compliance gap; 3-way option space). The remaining uncertainty is *not investigative* — it's a
founder/legal choice among rebuild / commercial-license / full-GPL-keep, which no amount of digging can decide for them.

## Native-binary / dependency cleanup — investigation (2026-06-23, read-only)
Investigated the three changes to confidence; effort/feasibility per item:

- **junrar removal — EASY, low-risk, fully scoped.** Transitive via `tika-parsers-standard-package →
  tika-parser-pkg-module:3.2.3 → junrar:7.5.5`, declared `runtimeOnly(libs.tika.parsers.standard)` in
  `worker-services/build.gradle.kts:43` + `indexer-worker/build.gradle.kts:59`. Fix = a module-level
  `exclude(group="com.github.junrar", module="junrar")` on both (surgical — keeps zip/7z/tar parsers). Then
  regenerate the 5 lockfiles + `verification-metadata.xml`, and delete the now-dead junrar entries from
  `config/allowed-licenses.json` + `license-overrides.json` (the J consistency-check requires they move together).
  **Zero code/test impact** (only an incidental `x-rar`→"archive" MIME string at `IndexingDocumentOps.java:444`,
  junrar-independent; no RAR test fixtures). RAR not in any supported-formats list.
- **libjbig removal — HARD (rebuild, not delete) — EMPIRICALLY CONFIRMED.** The Tesseract build extracts the
  whole UB-Mannheim installer (`7z x`, no DLL curation), so libjbig ships because upstream includes it.
  **Verified against the real binary: `libtiff-6.dll` imports `libjbig-0.dll` at load time** (its bytes reference
  `libjbig`; method validated — libtiff also references its known `libjpeg` dep). So **deleting the DLL would break
  libtiff → Leptonica → OCR**, not just drop JBIG. The clean fix is a **Tesseract/libtiff rebuild WITHOUT JBIG**
  (`--disable-jbig`) + re-pinning that artifact in `tesseract-windows.v1.json` (new source URL + SHA). No in-repo
  custom-Tesseract-build infra exists; the artifact would also need hosting (founder infra). Functional loss:
  negligible (JBIG = rare bilevel-fax codec; the OCR path has no JBIG dependency). **Effort: real; part founder-infra.**
- **NVIDIA — MEDIUM, and it ALIGNS WITH AN EXISTING DESIGN.** The compliant path already exists on paper +
  partly in code: the **offline GPU Booster Pack** (`docs/explanation/16-gpu-booster-pack.md` — "no arbitrary
  executable downloads"; GPU enabled "only by importing an allowlisted offline pack"; runtime-pack import +
  `POST /api/ai/runtime/activate` + manifest-SHA allowlisting are SHIPPED, §0). The *shipped* `model-registry.v2.json`
  `cuda-runtime` path instead uses **runtime HTTP download of public release assets** — the looser posture the pack
  design was meant to avoid, and the source of the "standalone public asset" EULA strain. So "adopt the packaging
  change" = **move cuDNN/ORT-CUDA off the auto-download path onto the offline-pack-import path** (incorporated into
  the app's pack → "accessed only by your application" by construction). Agent-editable: the registry `downloadUrl`s
  + install flow + `NOTICE-NVIDIA-CUDA.txt` (which today lists ONLY the CUDA-EULA DLLs cudart/cublas/cublasLt —
  **missing cuDNN + its separate SLA**). Founder-infra: producing/hosting the pack; retiring the public release
  assets on `eliasjustus/justsearch-releases`. The two llama.cpp-sourced zips are llama.cpp's own public
  redistribution (lesser concern).

**Sequencing read:** junrar is a quick standalone win. libjbig + NVIDIA both need a founder-infra component
(a rehosted Tesseract artifact; the booster-pack production + asset retirement) and are larger — natural to plan
together as the "native-binary distribution" follow-up.

## Founder decisions (2026-06-23, recorded)
- **NVIDIA → ✅ ACCEPT-AND-DOCUMENT the current app-driven download (2026-06-23, CORRECTED).** Earlier this
  workstream recorded "adopt the offline-pack import path" as the remediation — that is now **superseded**: the
  founder clarified the **GPU Booster Pack is legacy** and the live mechanism is the **initial AI-brain install**
  (`AiInstallService` loads `model-registry.v2.json`, selects a hardware/download profile, and downloads the
  `cuda-runtime` package's three files — `cudart-llama-bin-…` from llama.cpp's own release, and
  `ort-cuda-runtime-12.4.zip` + `cudnn-9-runtime-12.4.zip` from `eliasjustus/justsearch-releases`). **Founder
  decision: the current app-driven download from the project's release assets is an ACCEPTABLE redistribution
  posture** — the application initiates and consumes the runtime ("accessed only by your application"), and the
  standalone-public-asset character is accepted as the operating posture. So this is **accept-and-document**, not a
  packaging migration. The disposition gate records NVIDIA CUDA + cuDNN as `restricted` with a dated acceptance
  citing *this* basis (the acceptance rationale was corrected off the stale "offline pack" wording). The cuDNN
  *separate SLA* attribution still attaches to the artifact that actually ships cuDNN (`cudnn-9-runtime-12.4.zip`).
  No agent packaging work remains; the posture is the founder's accepted decision.
  - **License-grounded basis (web-verified 2026-06-23 against the *current* terms — CUDA EULA v13.3 dated
    2026-01-26 + the cuDNN SLA):** the earlier "standalone-public-asset *strains* the EULA" framing was an
    **over-reading**; the posture is **affirmatively allowed**, not merely a defensible grey call. The EULA grants
    redistribution of the Attachment-A components (cudart/cuBLAS/cuBLAS-Lt) when they are (a) embedded in an
    application with **material additional functionality** beyond the libraries, (b) **accessed only by your
    application**, with (c) NVIDIA's **notices retained** — and the current v13.3 **no longer mandates** the old
    "private (non-shared) directory" clause (it focuses on *access*, not storage; JustSearch installs to an
    app-private `variants/cuda12/` anyway). The cuDNN SLA imposes the parallel "incorporated into a software
    application … accessed only by your application … notices retained" conditions. **JustSearch meets all of
    them.** The *"may not distribute the SDK as a stand-alone product"* clause prohibits **offering the libraries
    *as an independent product / dev-kit*** — not hosting component zips that the app fetches; that is the
    universal ML-ecosystem pattern (PyPI/conda CUDA+cuDNN wheels are equally public, separately-named, app-fetched
    artifacts, which NVIDIA expects). So the residual "grey" (publicly-named downloadable zips) is **thin and
    industry-normal**, and the founder's *accept* is well-supported by the terms. **Two caveats:** this is a
    reasonable license reading, **not formal legal advice** (a lawyer's sign-off remains the go-public authority —
    `[founder/legal]`); and it hinges on **retaining NVIDIA's notices inside the shipped runtime** (don't strip the
    license files from the extracted zips) — worth a one-time check of the extracted `cuda12/` dir.
- **Trademark → keep `justsearch` for now** (provisional; revisit if a collision/protectability problem surfaces).
- **junrar (UnRar, non-OSI) → ✅ REMOVED + MERGED (2026-06-23, commit `5f9408604`).** Excluded
  `com.github.junrar:junrar` from `tika-parsers-standard` at both declaration sites (worker-services,
  indexer-worker) + a module-wide exclude in indexer-worker (clears it off testFixtures too); removed it from
  `allowed-licenses.json` + `license-overrides.json` (the gen-notices J consistency-check requires they move
  together), the dead `verification-metadata.xml` block, and `THIRD_PARTY_NOTICES` (regenerated). The product now
  has **zero non-OSI dependencies**. Verified: 0 junrar in all 5 lockfiles + production runtimeClasspath;
  `checkLicense` GREEN (no lenient); `gen-notices --check` no drift; `gen-notices.test` 7 passed; `build -x test`
  GREEN pre- and post-merge. RAR support dropped (not a supported format; the `x-rar` MIME classifier at
  `IndexingDocumentOps.java:444` still works — Tika core detects the MIME from magic bytes, only extraction is gone).
  Two failing module tests (`IndexerWorkerGuardrailsTest`/TikaOcrRuntime, `VduEligibilityPdfFixturesTest`) are
  **pre-existing OCR-workstream breakage** — they fail identically on `main` without this change (logged to inbox).
- **`libjbig` (GPL-2.0) → still a founder decision** (NOT bundled with junrar — it's a native-build/legal call,
  not a dep exclude). Fully clarified above (hard import → no delete-shortcut; lone copyleft import; dual-licensed;
  3-way option space: rebuild / commercial license / full-GPL-keep). The "keep" path is now **compliant + gated**
  (GPL-2.0 text + written offer ship; disposition gate enforces the dated acceptance). **Awaiting the founder's
  choice.** NOT swapping the dual-licensed deps (logback/JNA/jhighlight — claimed under their permissive side) or
  juniversalchardet (MPL-1.1, OSI weak-copyleft, acceptable).
  - **Commercial-license COST — cannot be evaluated by the agent (web-verified 2026-06-23).** jbigkit's commercial
    license has **no public price, no tiers, no fee structure** — Cambridge Enterprise lists only a "Make an
    enquiry" contact form (`enterprise.cam.ac.uk/reagents/jbig-kit-…`). Pricing is a bespoke, negotiated, per-use
    quote obtained only by the founder contacting them. Decision input: the "keep" path costs **$0** and is already
    compliant; "rebuild" costs engineering time (no fee); the commercial license is the only option whose cost is
    *both* unknown *and* requires outbound contact to discover — so it is the least-favoured unless the founder
    specifically wants to retain JBIG support *and* eliminate the GPL without a rebuild.
- Still open: DCO app activation (post-flip), CRA tracking.

## Post-merge verification close-out (2026-06-23)
A review found the full unit suite (`./gradlew test`) had never been run on the merged result (only `build -x
test`). Ran it: **632 introduces ZERO test regressions** — the only failures are **pre-existing on main**
(verified by running the suite at `b66d408fe`, the pre-632 HEAD: same 6 modules fail identically, app-services
even with *more* failures there). The breakage is **tempdoc 629/630's** — they merged to `main` without running
the suite (e.g. `StatusResponse.power`/`catchingUp` added to the record but never to `status.proto` →
`StatusWireContractConformanceTest`; the 629 encryption field-count → `LocalApiServerThinComposerTest`; etc.).
Logged to the inbox; **not fixed** (out of 632 scope; 629/630's wire-contract/field-count domain). Also hardened
the SPDX `--check` predicate to be position-aware (commit `211ab243`). 632's own behavioral module
(`configuration`) is test-green.

## Final status (2026-06-23) — ✅ MERGED to `main` (commit `343a84a0`; teardown `06844f5a`)

All agent-actionable 632 work is implemented, verified, and **merged to `main`**. Two catch-up merges of `main`
(it advanced 3× during the session with concurrent 629/630 work) were resolved as documented post-merge class-size
realigns; main's 23 new sources were SPDX-stamped; the fast-forward landed cleanly with neighbour WIP untouched;
`record-merge` + `fold-observations` teardown ran. **Post-merge `./gradlew build -x test` from `main`: GREEN.**

The earlier worktree implementation:
Stages A–F + the 3 review fixes + **H** (class-size unblock) + **I** (canonical-doc table is now a generated
projection) + **J** (allowlist consistency-check) + **K** (SPDX headers on 1932 sources + a `--check` CI guard),
plus a clean merge of `main@05ae8031a`. **All green:** `./gradlew build -x test` (compile + Spotless + class-size
+ all gates), `checkLicense` (no lenient), `gen-notices.test` (7), `check-notices-regen`, `npm run typecheck`,
`add-spdx-headers --check`. The design's "everything is a projection, nothing is a hand-fork" thesis is fully
realized (all 4 surfaces: NOTICE, THIRD_PARTY_NOTICES, the canonical-doc table, the gate input).

**Merge to `main` is blocked by concurrent-agent churn:** `main` advanced TWICE during the session
(`220317444` → `05ae8031a` → `b66d408fe`; the last +5 commits are another agent's tempdoc-630 FE work), so the
fast-forward fails and would need yet another catch-up merge into a moving target (each round re-conflicts on the
`class-size-exceptions.txt` hotspot). Needs a quiescent moment / human coordination to land. The branch is ready;
`main` was left intact (ff aborted cleanly; the 2 neighbour WIP files untouched).

## Confidence pass #3 — the REMAINING work (2026-06-23; read-only + a throwaway trial)

Probed the four uncertainties in what's left (merge blocker + the two design-deviation fixes + deferred SPDX). All
resolved; the remaining agent work is small, mechanism-known, and pattern-conforming.

- **P1 — class-size merge blocker: RESOLVED + PROVEN.** The sanctioned unblock is **raise 3 pins** (EnvRegistry
  1154→1168, KnowledgeServer 2114→2119, HeadlessApp 1214→1224) **+ one `merge-import` changeset** (the verbatim
  template is `gates/class-size/.changesets/549-followup-pin-realign-*.md`; classification `merge-import` =
  "formalizes already-merged growth, no new code here"). The 3 files grew via **already-merged** 627/628/630 work
  whose pins were never raised — `git log` confirms; the baseline's own "Tempdoc 626 post-merge realign" section
  did the *same* re-pin for the *same* "a different gate failure masked class-size" reason. (HeadAssembly is a 4th
  *finding* but it's UNDER pin — a rebalance-*down* candidate, not a failure.) **Throwaway trial: with the 3 bumps
  + the changeset the gate went GREEN (0 fail); reverted → red again (confirms pre-existing).** Tiny, sanctioned,
  unblocks the merge. *(This is `merge-import` maintenance the repo does routinely — not new scope.)*
- **P2 — canonical-doc table as a projection: RESOLVED (small + conforming).** The repo already has the
  marker-region-injection pattern — `<!-- GENERATED:MODULE_DEPS:BEGIN/END -->` in `docs/reference/architecture/
  module-deps.md`, with a generate+verify pair (`scripts/docs/generate-runtime-config-matrix.mjs` +
  `verify-runtime-config-matrix.mjs`). Fix: add `<!-- GENERATED:MODEL_LICENSES:BEGIN/END -->` markers to the legal
  doc, have `gen-notices` inject the registry-projected model/license table between them, guard with
  `check-notices-regen`. `docs/reference/legal/**` is **not** a blocking governed region (maintain-doc-hint is
  shell-v0 only), so no doc-governance fight.
- **P3 — gate allowlist as a projection: RESOLVED via decision (consistency-check, not generation).** Verified the
  per-`moduleName` lists in `allowed-licenses.json` and `license-overrides.json` are **byte-identical** (same 7
  modules) — two copies that can silently diverge, sharing one change-reason. Per AHA, the proportionate closure is
  a **consistency-check** (the two moduleName sets must agree), NOT full generation — generation would need a
  second "permissive-license set" source and conflate that *different* change-reason. The check achieves the
  design's "no silent fork" goal in ~5 lines, keeping `allowed-licenses.json` the single file jk1 reads.
- **P4 — SPDX governance cost: KNOWN (deferred-G).** The 16 legitimate `+1` bumps take the same path as P1 but
  classify as **`declared-growth`** (new header growth, tempdoc-632 ref) rather than `merge-import`; the codemod
  bug is a one-line exclude of `scripts/governance/_fixtures/**`; and Spotless `licenseHeader(header, delimiter)`
  is available on the `format("javaSources")` block, so forward-enforcement is feasible (optional).

### Confidence rating for the remaining work: **9 / 10** (was 8/10)
The scariest unknown — the class-size merge blocker — is now **proven green** with a verbatim template and is a
~4-line sanctioned change. The two design-deviation fixes (P2 doc-table projection, P3 allowlist consistency-check)
are small, pattern-conforming, and proportionate. SPDX (P4) is de-risked. The only residual is the **founder/legal
decisions** (NVIDIA hosting, junrar, trademark, DCO activation, CRA) — out of agent control by design, and
decisions rather than implementation risks. That's what holds it at 9, not 10.

## Long-term design — attribution as projection, not fork (the spine; 2026-06-23)

### The real problem under the paperwork
632 reads as a task list, but the durable problem is structural: **"what we ship and under what license" is
currently forked across ~8 hand-edited representations, and every one examined has already drifted.** The root
`NOTICE` is stale + references a non-existent dir; `generateOnnxNotice` is a hardcoded heredoc that forks
`model-registry.v2.json`, is missing Qwen, and cannot track a license change; `NOTICE-TESSERACT.txt` omits all 56
bundled DLLs; `NOTICE-NVIDIA-CUDA.txt` is hardcoded in `build.gradle.kts`; the canonical legal doc
(`docs/reference/legal/ai-runtime-and-model-redistribution.md`) has drifted to `v1`/`Qwen3-VL`; `THIRD_PARTY_NOTICES`
is absent. A one-time hand-fix re-drifts on the next model add, dep bump, or Tesseract version roll. The purpose of
this workstream is not "author a NOTICE once" — it is **be, and stay, legally complete.** That is a drift problem,
and this codebase already has a settled answer to drift problems.

### Conform to the existing seam: the closure rule (do not invent a parallel one)
The repo's canonical answer to "many hand-authored copies of one derived fact" is the **closure rule** (tempdoc
501; `docs/explanation/23-runtime-manifest.md`): *one declaration; every consumer reads a field on it; a proposed
new sibling artifact must extend the declaration, mechanically enforced by a closure gate*
(`scripts/ci/check-runtime-manifest-closure.mjs`). The same shape recurs as the **execution-surfaces register**
(`governance/execution-surfaces.v1.json` + the `execution-surface` gate: an unregistered referencer of the
canonical record fails the build) and the **`ssot-catalog-sync`** gate (a generated/mirrored copy must equal its
source). Attribution is simply the next representation that belongs under this discipline — **CLAUDE.md's own
"projection vs fork" rule** (the `explore-before-implementing` register step) names it. So the design is *not* a new
framework; it is **bringing license/attribution under the closure rule the system already runs.**

### The design (general level)
**Principle: a NOTICE / THIRD_PARTY_NOTICES / canonical-doc table / gate-allowlist / in-app viewer is a
*rendering* of declared license facts, never an independently-authored copy.** Three sources, one projection layer,
one closure gate — matched to the three genuinely-distinct authorities that already exist (do *not* unify them into
one schema; they change for different reasons — AHA):

1. **Dependency artifacts (JVM + npm + Cargo)** — the authority is the package managers' own metadata; the
   *generators* are the projection. JVM already has one (`generateLicenseReport`); npm and Cargo need theirs wired
   (`license-checker`; `cargo-license`/`cargo-about`). This is **wiring, not new structure** — the source already
   exists upstream.
2. **Model artifacts** — the authority already exists: `model-registry.v2.json` (it *already* single-sources each
   model's `downloadUrl`, `sha256`, `termsUrl`, and projects them into `InstallContract` at install). License is the
   **one model attribute not yet single-sourced.** Add a `license`(+optional `attribution`) field to the registry
   record and **re-point `generateOnnxNotice` to project from it** — killing the heredoc fork and covering Qwen by
   construction. This *completes a row in a table the codebase already maintains*; it is the minimal structure, not
   new structure.
3. **Native-binary artifacts** (llama.cpp, Tesseract + its 56 DLLs, NVIDIA CUDA/cuDNN) — the genuinely-unstructured
   part. Authority = each component's packaging manifest. `packaging/runtime/tesseract-windows.v1.json` already has
   the right *shape* (`license` + `notices[]`) but does not enumerate the DLLs; extend it (and give llama.cpp +
   NVIDIA equivalent declared file+license lists — the NVIDIA one can fold into the registry's existing
   `cuda-runtime` package). The native NOTICEs then **project** from these manifests instead of being hardcoded.

**Projection layer:** root `NOTICE`, `THIRD_PARTY_NOTICES`, the canonical-doc table, the license-gate allowlist
input, and (later) an in-app viewer are all *rendered* by concatenating the three sources — generated build
outputs, not committed hand-edits (or committed-but-generated, guarded by a sync check).

**Closure gate (the light variant — conform to `ssot-catalog-sync`, not the heavier referencer-scan):** assert
(a) **presence** — every `model-registry` package carries a `license`; every redistributed `native-bin/` file is
enumerated-with-license in a manifest; every dep is covered by a wired generator — and (b) **equality** — each
committed NOTICE equals a fresh projection. A model added without a license, a Tesseract bump that adds a DLL, or a
registry license change not reflected downstream then **fails the build**. This is what converts "legally complete"
from a perishable snapshot into a standing invariant — and matches the kernel's own "gate ~100% vs prose ~70%"
posture for a correctness invariant.

### Scope boundaries — structure deliberately NOT built
- **No universal cross-language BOM schema.** The three sources have three different existing authorities and
  change for different reasons; unify only at the *render* layer, never the *source* layer (AHA — unify what shares
  a reason to change). A mega-schema is structure the problem does not include.
- **No SBOM/CycloneDX framework now** — different consumer (CRA/M2). The same sources can *feed* an SBOM later;
  building it now is premature (G-C stays a boundary note).
- **No in-app viewer as part of the source design** — it is just another projection consumer; build it when goal
  G-B is taken.
- **No new governance *register*** if presence+equality suffices — the `ssot-catalog-sync` build-task+sync-check
  shape is lighter and fits; reach for the `execution-surfaces` referencer-scan only if forks-from-scratch (not
  just stale copies) become the failure mode.

### Reach — the principle and where else it applies
This design is an **instance**, not a new idea: **"Derived facts about a shipped artifact are projections of one
declaration; the notice/doc/gate/UI are downstream renderings; a closure gate keeps every artifact's fact sourced
and every rendering equal to a fresh projection."** It is the same closure-rule/projection-not-fork invariant as
tempdoc 501, execution-surfaces, ssot-catalog-sync, and tempdoc 553's representation-drift class — restated for
the *attribution* representation. Candidate scope beyond 632, with **existing violations** named (recognized, not
to-be-built-now):
- `generateOnnxNotice` vs `model-registry.v2.json` — **violation** (forks the registry; the instance this design
  fixes).
- `docs/reference/legal/ai-runtime-and-model-redistribution.md` vs the registry — **violation** (drifted to
  v1/Qwen3-VL).
- the `NOTICE-*` files vs the actually-shipped binaries — **violation** (hardcoded; Tesseract omits 56 DLLs).
- A future **SBOM**, and any future "fact about a shipped artifact," must project from these same sources, not
  fork them.
- **Conforming instances already present** (proof the structure is minimal, not novel): the model `sha256` /
  `downloadUrl` / `termsUrl` are *already* single-sourced in the registry and projected into `InstallContract` —
  licensing is the lone un-projected sibling attribute. The design finishes an established pattern rather than
  starting one.

Per the recognize-vs-build separation: **build now** only the registry `license` field + `generateOnnxNotice`
projection + native-binary manifest enumeration + the notice projections + the presence/equality gate that *this*
problem requires; **record** the broader principle + candidate scope above without generalizing the projection
layer into a framework.

## Long-term design — native-binary redistribution policy as a gated disposition (the *policy* axis; 2026-06-23)

### The problem the remaining work actually has
The attribution spine above made *attribution* a standing invariant (every shipped thing is **named**). The
remaining 632 work (libjbig, NVIDIA) is a **different axis the spine deliberately left ungated: whether a shipped
artifact's redistribution constraint is acceptable, and what action it forces.** That axis has a structural
asymmetry, verified in the live tree:

- **Dependency trees (JVM / npm / Cargo) are policy-gated.** A strong-copyleft or field-of-use constraint **fails
  the build** — `checkLicense` over the JVM classpath (junrar was caught + accepted here by an explicit
  `moduleName` allowlist row, then removed) plus the `gen-notices` presence-check #4 strong-copyleft/UNKNOWN guard
  over npm+Cargo (`scripts/codegen/gen-notices.mjs:263-270`). Acceptable exceptions are **explicit, dated allowlist
  entries**.
- **Native binaries are NOT policy-gated.** `gen-notices` check #3 asserts only **presence** (every
  `native-bin/tesseract/*.dll` is enumerated in `tesseract-bundled-libraries.v1.json`); check #4 scans
  `[...loadJvm(), ...loadNpm(), ...loadCargo()]` and **excludes `loadNative()`**. So a strong-copyleft DLL
  (`libjbig`, GPL-2.0-or-later, carrying only a descriptive `"copyleft": true` flag + a prose `copyleftFlags`
  entry) or an EULA/SLA-restricted runtime (NVIDIA `LicenseRef-NVIDIA-CUDA-EULA` / `-cuDNN-SLA`, "accessed only by
  your application") **ships green.** The constraint lives in *descriptive metadata that nothing enforces.*

**libjbig is the proof-by-example** (one documented silent ship ⇒ the bug-class is proven, per
`structural-defects-no-repeat`): a GPL-2.0 DLL rode the Tesseract bundle undetected and was found only by manual
PE-import archaeology — exactly the failure the gate discipline exists to prevent. **NVIDIA's EULA/SLA-restricted
runtime is the second live instance** of the same class. (No `631`/`634` design covers this — grep-verified; the
licensing-policy axis is `632`'s.)

### The design (general level) — extend the existing gate to the native axis; do not parallel it
**One policy across every redistributed component — dependency OR native: an artifact carrying a strong-copyleft
(GPL/AGPL/SSPL) or EULA/field-of-use constraint must either have a permissive alternative, be removed, or carry an
explicit *dated acceptance*; otherwise the build fails.** This is the `checkLicense` allowlist shape, extended to
close the native blind spot. Three minimal moves, all extensions of structure that already exists:

1. **Type the disposition that is already half-declared.** The native manifests
   (`tesseract-bundled-libraries.v1.json` rows; the `nativeRuntimes` block in `license-overrides.json`) gain a
   per-artifact `disposition` ∈ {`permissive`, `weak-copyleft-dynamic` (LGPL/MPL as separate replaceable DLLs —
   already the stated posture, fine), `strong-copyleft`, `restricted` (EULA/SLA/field-of-use)}. The existing
   `"copyleft": true` flag, the prose `copyleftFlags`, and the `LicenseRef-NVIDIA-*` strings are the **seed** — this
   makes the disposition a declared, typed field instead of prose.
2. **A native acceptance ledger mirroring the JVM `moduleName` allowlist:** `{ artifact, disposition, decision,
   date, ref }`. The founder's recorded native-binary decision (the tempdoc's `[founder/legal]` items) becomes a
   *machine-checked, dated waiver* rather than a prose note.
3. **Extend `gen-notices` presence-check #4 to scan `loadNative()`:** any native artifact whose disposition ∈
   {`strong-copyleft`, `restricted`} **must** have a matching acceptance-ledger entry, else FAIL — identical in
   shape to `checkLicense` and the npm/Cargo guard. A future Tesseract bump that adds a new GPL codec, or a new
   EULA-restricted runtime, then fails the build until *explicitly accepted-and-dated.*

This converts the native-binary review from a **perishable manual review** (precisely what let libjbig slip) into a
**standing invariant** — what the attribution spine did for notices, applied to the policy axis. The specific
remediations (**NVIDIA → pack-import** because its disposition is `restricted`→"app-private only"; **libjbig →
rebuild/commercial/keep** because its disposition is `strong-copyleft`) are **execution + founder-infra consequences
of the disposition decision, not structure to build** — the gate governs the *policy*; the posture change follows
from each artifact's accepted disposition.

### Scope boundaries — structure deliberately NOT built
- **No new gate or register.** Reuse `gen-notices` presence-checks + the existing manifests; the acceptance ledger
  is the `checkLicense`-allowlist shape, not a new framework.
- **No unified cross-tree license schema.** The dependency allowlist (jk1 display-name vocabulary) and the native
  disposition stay **separate authorities** — they change for different reasons (AHA). They unify only at the
  *policy* level (the strong-copyleft/restricted rule), never at the *schema* level.
- **No model-disposition gate now** — recognized below as candidate scope, not built (no non-redistributable model
  case exists yet; only presence is currently required).

### Reach — the principle and where else it applies
This is an **instance** of an existing seam, not a new idea: the **`checkLicense` allowlist-of-dated-exceptions**
pattern and the closure-rule lineage (501 runtime-manifest, `ssot-catalog-sync`, 553 representation-drift). Conform
to it; do not parallel it. The broader principle it makes explicit — **record, do not build now**:

> **A constraint that lives only in descriptive metadata (a prose flag, a `LicenseRef` string) is not a control. A
> redistribution constraint must be a *typed, gated disposition* declared at the point the artifact is pinned, with
> an explicit dated-exception ledger — otherwise it ships silently.**

Candidate scope beyond 632, with **existing violations** named (recognized, not to-be-built-now):
- **`libjbig` (strong-copyleft)** — ships with no enforced acceptance (prose flag only). *Violation.*
- **NVIDIA CUDA/cuDNN (`restricted`)** — ship with no enforced acceptance (LicenseRef string only). *Violation.*
- **`model-registry.v2.json` model licenses** — gated for *presence* (`everyPackageDeclaresALicense`) but **not
  disposition**: a future model added under a non-redistributable/`restricted` license passes the presence check.
  *Latent violation* — the same blind spot, on the model axis. The unified end-state ("redistribution-disposition
  closure" across deps + native + models) is the general structure; **building it waits for a model/other-artifact
  case to actually arise.**
- Any future bundled native tool (ffmpeg, poppler, …) hits the same gate by construction once it exists.

Per the recognize-vs-build separation: **build now** (when the founder's libjbig/NVIDIA decisions land) only the
native `disposition` field + acceptance ledger + the `loadNative()` extension to presence-check #4 that *this*
problem's two live cases require; **record** the cross-tree "redistribution-disposition closure" principle without
generalizing it into a model/SBOM framework yet.

### Confidence pass — native-binary disposition gate (2026-06-23; read-only + throwaway scratchpad prototype)
De-risked the gate (the main remaining agent-implementable piece) against the **real** manifests. Six checks; the
mechanism is proven and one design refinement was forced.

- **C1 — uniform scan is clean.** `gen-notices.mjs` loaders return `{name,version,license}` (jvm/npm/cargo) and
  `{id,label,license,…}` (models); `loadNative()` returns `{runtimes:[{name,license,ref}],
  tessDlls:{libraries:[{dll,library,license,copyleft?}]}, tessCore}`. A uniform `{name,license,disposition}` scan
  over BOTH native sources is a ~5-line read-side flatMap — **no loader change.**
- **C2 — regex auto-derivation is FRAGILE (the key finding).** Running the existing `isStrongCopyleft()` over the
  actual native strings: it correctly FIRES on libjbig (`GPL-2.0-or-later`) and correctly EXCLUDES the GCC
  `…WITH …exception` and the LGPL family — **but FALSE-POSITIVES on FreeType** (`FTL OR GPL-2.0-or-later`; FTL
  isn't in the JVM-tuned permissive list), and does **not** fire on NVIDIA `LicenseRef-*-EULA/SLA` (restricted ≠
  copyleft). The scratchpad prototype then surfaced **two more** auto-derivation traps: GCC libgcc/libstdc++ carry
  `copyleft:true` but are *acceptable-with-linking-exception* (not action-required), and `GNU libidn2`
  (`LGPL-3.0 OR GPL-2.0`) is choosable under its LGPL side (weak-copyleft-dynamic, not strong). **Three distinct
  dual-license/exception edge cases break regex derivation.**
- **DESIGN REFINEMENT (load-bearing):** the gate must key on an **explicitly DECLARED `disposition` per artifact**
  (a one-time legal judgment authored into the manifest — ~20 distinct libraries, most `permissive`), **not** a
  regex over license strings. Taxonomy: `permissive` · `weak-copyleft-dynamic` (LGPL/MPL separate DLL) ·
  `copyleft-with-linking-exception` (GCC runtime — ship the exception text, non-firing) · `strong-copyleft`
  (firing) · `restricted` (EULA/SLA/field-of-use — firing). This is both more robust (kills the FreeType/GCC/idn2
  traps) and more honest (disposition *is* a legal call, not a string match).
- **C3 — gate fires AND suppresses: PROVEN both directions** (scratchpad, against real manifests, reverted). With
  declared dispositions, exactly the three genuine cases fire — libjbig (`strong-copyleft`), NVIDIA CUDA + cuDNN
  (`restricted`); FreeType correctly `permissive`. No acceptance ledger → all three FAIL; dated ledger → PASS;
  adverse drop of one entry → that one alone FAILS (isolation confirmed — not green-for-the-wrong-reason).
- **C4 — render-safe.** `renderThirdParty()` reads only named fields (`r.name/license/ref`, `e.library/license`);
  an added `disposition` key is inert. ✓
- **C5 — CI-safe.** `loadNative()` reads the **committed** manifests, not the gitignored `native-bin/` filesystem
  (only presence-check #3 does `readdirSync`), so the disposition gate runs unconditionally — it is *not*
  skip-when-absent. ✓
- **C6 — remediation residuals.** (a) **NVIDIA NOTICE — corrects an assumption:** the staged `…/variants/cuda12/`
  ships `cudart`/`cublas`/`cublasLt` (CUDA EULA) but **no cuDNN** DLLs — so "add cuDNN to the notice" is wrong for
  the variant heredocs; cuDNN's SLA attribution attaches only to whatever artifact actually *ships* cuDNN (the
  cuda-runtime download / future pack), confirming this work belongs **with** the pack-move and must be verified
  per-artifact. (b) **libjbig "keep" location:** a GPL-2.0 text + jbigkit written-offer would live at
  `packaging/runtime/NOTICE-JBIGKIT.txt` next to `NOTICE-TESSERACT.txt` — a known ~1–2-file add.

**Residual unknowns (what holds the rating below 10):** authoring the ~20 explicit dispositions is a careful
per-library legal-labelling task (the three edge cases prove mistakes are possible); the acceptance-ledger's exact
file home + the `gen-notices.test` additions are designed-but-unbuilt; and the founder-gated remediations (NVIDIA
pack, libjbig rebuild) stay decision/infra-blocked regardless of gate readiness.

### Confidence rating (remaining work): **8 / 10**
The gate — the main agent-implementable structural piece — is de-risked end-to-end: data shapes confirmed, scan is
render-safe + CI-safe, and the fire/suppress mechanism is proven against real data. The one surprise (regex
derivation breaks on three dual-license/exception cases) was found *and* resolved by switching to a declared
`disposition` field. The −2 is the per-library disposition-authoring judgment, the unbuilt ledger/test wiring, and
the founder-gated remediations that no amount of agent work unblocks.

### ✅ IMPLEMENTED + MERGED — native-binary disposition gate (2026-06-23, commit `51c2e80f4`, ff `f8d4e088b`)
The gate is built and on `main`. The declared-disposition design (not regex) was carried through exactly as the
confidence pass concluded:
- **Manifests** — `disposition` declared on all 56 `tesseract-bundled-libraries.v1.json` rows (the only firing one
  is JBIG-KIT=`strong-copyleft`; GCC×2=`copyleft-with-linking-exception`; LGPL family=`weak-copyleft-dynamic`;
  FreeType/Zstandard/libidn2 classified by hand, defeating the regex traps) + the 3 `nativeRuntimes`
  (llama.cpp=permissive, NVIDIA CUDA + cuDNN=`restricted`).
- **Ledger** — `config/native-license-acceptances.json` (the jk1-allowlist analogue): JBIG-KIT + NVIDIA CUDA +
  cuDNN, each a dated explicit decision.
- **Gate** — `gen-notices.mjs` `nativeDispositionCheck` (pure/injectable), called from `presenceChecks` → enforced
  in regen AND `--check` (CI), on the committed manifests (unconditional). Three assertions: no-silent-default,
  firing-needs-acceptance, no-stale-acceptance.
- **libjbig interim GPL compliance** — `packaging/runtime/LICENSE-GPL-2.0.txt` (canonical text, fetched verbatim
  from gnu.org) + `NOTICE-JBIGKIT.txt` (written source offer). Removed-with-libjbig on rebuild.
- **Verified**: `gen-notices.test` 13 passed (6 new, both directions + real-manifest firing-set assertion);
  `gen-notices --check` green (NOTICE unchanged — disposition is manifest-only); **adverse test** (drop an
  acceptance → gate FAILS → restore → PASS); `build -x test` green pre- and post-merge. A pre-existing 629-era
  class-size realign for HeadAssembly (1180→1189, `merge-import`) rode along; the catch-up merge of `main`
  reconciled cleanly with 629's own 1189→1200 changeset. The proven libjbig blind spot is now build-enforced;
  libjbig + NVIDIA are explicit dated-gated facts. **Remaining = Part 2 founder-gated only** (NVIDIA pack
  production, libjbig final 3-way decision, DCO/CRA/trademark).

---

## Investigation / pre-implementation findings (2026-06-23, takeover pass — verified against `main`)

Read-only audit of the live tree + web-verification of the external legal claims. Each finding cites
primary-source evidence; corrections to the scope above are noted so implementation works from real state, not
planning estimates. **Three findings materially change the plan** — flagged ★ (F4, F6, the gate-override in C).

### Verified-as-stated (the tempdoc's load-bearing claims hold)
- **Root `NOTICE` is minimal + broken.** Lists only Lucene/Tika/llama.cpp/Tauri and ends with a dangling
  "see … the licenses directory of individual components" (`NOTICE:30-31`) — no such directory exists. ✓
- **jk1 plugin is report-only.** `licenseReport {}` at `build.gradle.kts:600-609` configures JSON+HTML
  renderers + a `LicenseBundleNormalizer`, no `allowedLicensesFile` / `checkLicense` wiring. ✓
- **`generateOnnxNotice` exists** (`modules/ui/build.gradle.kts:397-448`). ✓ — but see F3.
- **`THIRD_PARTY_NOTICES` absent.** Independently confirmed as a known gap in the canonical doc
  (`docs/reference/legal/ai-runtime-and-model-redistribution.md:156-158`, "Pending compliance action (374 G12)"). ✓
- **Model licenses (web-verified 2026-06-23):** Qwen3.5-9B chat is **Apache-2.0**
  (`huggingface.co/Qwen/Qwen3.5-9B`); the 5 ONNX/SPLADE models are Apache-2.0 except **NER = AFL-3.0**
  (`Davlan/distilbert-base-multilingual-cased-ner-hrl`). The tempdoc's "attribute NER *as* AFL-3.0, not Apache"
  is correct; AFL-3.0 is OSI-approved + commercial-OK (and GPL-incompatible — a non-issue for a permissive
  bundle; the canonical doc already names NER "the one to swap" if uniformity matters). ✓
- **NVIDIA CUDA EULA** (web-verified): redistributables must have "**material additional functionality** beyond
  the included SDK portions", be "**accessed only by your application**", and "may **not** be distributed as a
  **stand-alone product**" (also documented at canonical `ai-runtime-and-model-redistribution.md:111-115`). ✓
- **Tesseract** bundles LGPL (cairo/pango) + GPLv3-with-Runtime-Library-Exception (libgcc/libstdc++) DLLs. ✓ (F5 widens this.)
- **EU CRA timeline** (web-verified): in force 10 Dec 2024; vuln/incident **reporting obligations from 11 Sep
  2026**; **full compliance 11 Dec 2027**. "Commercial activity = monetized" → a paid tier pulls you in; the
  open-source-steward lighter-touch regime applies to the FOSS side. The tempdoc's CRA note is accurate. ✓

### F1 — Item #3's CONTRIBUTING/DCO half is already DONE; only the CI check remains
`CONTRIBUTING.md:83-109` already carries a full DCO section (the certify-3-points text + `git commit -s` / amend
instructions, "DCO instead of a CLA"). So "CONTRIBUTING reflects it" is a **no-op** — don't re-author it. The
only DCO work left is the **CI enforcement**, and even that is half-drafted: `public-ci.yml:57-62` has a `dco:`
job stub (commented: "use the GitHub DCO app, or `tim-actions/dco@v1`"). Net DCO scope = wire one action/app, not
prose. **Also fold in (same file, while you're there):** `CONTRIBUTING.md` carries stale content a public
contributor would trip on — `:117` "modules/ui-web | **React** frontend" (Hard Invariant #5 / ADR-0032 → Lit);
`:31,:67` reference `.\scripts\gate.ps1` which **does not exist** (no `**/gate.ps1` in the tree); and the
`eliasjustus/JustSearch` slug (631 G1 scrub). `CODE_OF_CONDUCT.md` + `SECURITY.md` it links **do** exist.

### F2 — The component NOTICE files already exist; the gap is consolidation + the non-model deps
The tempdoc frames NOTICE completion as net-new attribution authoring, but three per-component notices already
ship: `NOTICE-MODELS.txt` (5 ONNX models, generated by `generateOnnxNotice`),
`packaging/runtime/NOTICE-TESSERACT.txt`, and `…/variants/cuda12/NOTICE-NVIDIA-CUDA.txt`. These ship **with the
installer** (where they legally bind — `native-bin/` is gitignored, so the binaries aren't in git). The actual
gaps are: (a) the **root** `NOTICE` references none of them; (b) **no notice covers the Java runtime deps** the
license report surfaces (ONNX Runtime MIT, Javalin/DJL/Jetty); (c) **the Qwen chat GGUF is not in
`NOTICE-MODELS.txt`** (it lists the 5 ONNX models only — the LLM is a separate package, `model-registry.v2.json`
`id:"chat"`). So "reuse `generateOnnxNotice`" gets you 5 of the 6 models; **Qwen must be added by hand.**
→ surfaces design-question **A** below (consolidate vs. reference).

### F3 — `generateOnnxNotice` is a hardcoded heredoc, not data-driven
`modules/ui/build.gradle.kts:402-446` `writeText`s a literal string; it does **not** read
`model-registry.v2.json`. "Reuse the task" = copy its text, not call a generator over the registry. If a
drift-proof NOTICE is wanted (registry ↔ notice can't diverge), the registry must first gain a `license` field —
today entries declare only `termsUrl` (readiness doc §should-fix). Low-priority, but the "reuse" verb in the
scope oversells what the task gives you.

### F0 — ★ The runbook's headline command (`generateLicenseReport`) FAILS three ways as written (verified 2026-06-23 by running it)
The runbook + readiness doc both print a bare `./gradlew.bat generateLicenseReport`. **It does not run on this
repo as written** — I hit three *distinct* failures, each needing a flag:
1. **Configuration cache** (on by default here): the jk1 `ReportTask` serializes Gradle `Project` objects →
   `cannot serialize object of type '…DefaultProject'`. Needs **`--no-configuration-cache`**.
2. **Cross-project resolution without an exclusive lock**: `Resolution of ':modules:adapters-lucene:
   runtimeClasspath' was attempted without an exclusive lock` (the root task resolving every subproject's
   classpath clashes with parallel execution). Needs **`--no-parallel`**.
3. **Dependency verification**: the plugin fetches extra `.pom` metadata not covered by
   `gradle/verification-metadata.xml` (`<verify-metadata>true</verify-metadata>`) → fails on
   `io.opentelemetry:*`, `okhttp`, `guava`, etc. Needs the POMs added to verification-metadata **or**
   `-Dorg.gradle.dependency.verification=lenient`.

The **only invocation that produced a report** (BUILD SUCCESSFUL):
`./gradlew.bat generateLicenseReport --no-configuration-cache --no-parallel -Dorg.gradle.dependency.verification=lenient`.
**Implication:** "reuse existing infra, build no new tooling" is true but the command is non-obvious; bake the
exact flags into `public-ci.yml` + the runbook, and prefer **adding the missing POMs to verification-metadata**
over shipping `lenient` in CI (lenient defeats the supply-chain guard the repo deliberately runs). *(Process
aside: my first run was piped through `tail` and reported **exit 0 while the build had FAILED** — the live
`pipefail` footgun from `agent-lessons.md`. Re-ran bare to see the truth.)*

### F9 — ★ The dependency-license reality (ran the report; corrects the readiness doc's "no copyleft" verdict)
The readiness doc asserts deps are "**no GPL/AGPL/SSPL; only EPL-2.0 (dual w/ Apache) + MPL-2.0**." Running the
report (`build/reports/licenses/third-party-licenses.json`, ~200 deps) confirms the **strong-copyleft half** (no
GPL/AGPL/SSPL — true) but the rest is **wrong/incomplete**. Verified non-trivial licenses, by dep:
- **LGPL-2.1, three dependencies — all dual-licensed, report picked the copyleft side:** `ch.qos.logback:
  logback-classic/-core:1.5.32` (actually **EPL-1.0 OR LGPL-2.1**), `net.java.dev.jna:jna(+-platform):5.18.1`
  (**Apache-2.0 OR LGPL-2.1+**), `org.codelibs:jhighlight:1.1.0` (**CDDL-1.0 OR LGPL-2.1+**). The jk1
  `LicenseBundleNormalizer` collapsed each to a single license and chose LGPL — so a verbatim NOTICE/gate would
  (a) mis-declare your posture as copyleft and (b) trip the allowlist. **Claim each under its permissive
  alternative** via an override (logback→EPL-1.0, JNA→Apache-2.0, jhighlight→CDDL-1.0).
- **MPL-1.1 (not 2.0):** `com.github.albfernandez:juniversalchardet:2.5.0` (a Tika transitive). The runbook's
  allowlist lists **MPL-2.0** — a *different* license string; MPL-1.1 would false-positive **and** is a genuine
  "is 1.1 acceptable?" call (it is — OSI-approved, file-level weak copyleft, redistribution-OK).
- **UnRar License — the one genuine policy item:** `com.github.junrar:junrar:7.5.5` (Tika's RAR support). The
  UnRar license carries a **field-of-use restriction** (may not be used to recreate the RAR compression
  algorithm) → it is **not OSI-approved**; a strict OSI-only allowlist excludes it. **[founder/legal decision:]**
  accept-and-document, or drop RAR support. This is a real review item the tempdoc's scope doesn't name.
- **Plus harmless-but-allowlist-tripping strings:** `Go License`, `Unicode-3.0`, `PUBLIC DOMAIN`, `0BSD`,
  `BSD Zero Clause License`, `"Similar to Apache License but with the acknowledgment clause removed"`, and the
  fact jk1 emits **display names** (`Eclipse Public License - v 2.0`, `The 3-Clause BSD License`) not SPDX ids.

**This converts design-question B from hypothesis to fact:** a bare SPDX allowlist (Apache-2.0/MIT/BSD-2/3/
MPL-2.0/EPL-2.0/AFL-3.0) **would be red on day one** on ~8+ deps — partly real (junrar, MPL-1.1), mostly
string-format/dual-license artifacts. The real work in "enforce the gate" is the **per-dependency
override/normalizer map authored in jk1's vocabulary**, validated by running `checkLicense` against the current
tree *before* making it blocking — not the allowlist itself.

### F4 — ★ The CUDA re-hosting risk is more specific (and bigger) than "cudart/cublas"
The `cuda-runtime` package (`model-registry.v2.json:241-280`) re-hosts **four** zips, and the EULA exposure splits:
- `llama-…-cuda-12.4.zip` + `cudart-llama-…-cuda-12.4.zip` come from **ggml-org/llama.cpp's own releases** —
  that's llama.cpp's redistribution posture, not the founder's.
- `ort-cuda-runtime-12.4.zip` and `cudnn-9-runtime-12.4.zip` are **re-hosted on the founder's own
  `github.com/eliasjustus/justsearch-releases`** (`:266-278`) — *this* is the direct exposure: anyone can `curl`
  a standalone NVIDIA-runtime zip off a public release page, which is precisely the "stand-alone product" /
  "accessed only by your application" violation.
- **cuDNN is governed by a SEPARATE agreement** (the *cuDNN SLA*, not the CUDA Toolkit EULA — web-verified) with
  the same "accessed only by your application" restriction **plus** a clause that you "may not use the SDK in any
  manner that would cause it to become subject to an open-source license." The tempdoc + canonical doc both treat
  "CUDA/cuDNN" as one EULA; the review must consult **two** agreements, and the canonical
  `ai-runtime-and-model-redistribution.md` analyzes **only** cudart/cublas under Attachment A — it never covers
  cuDNN or the ORT-CUDA EP zips. **That omission is the real day-one legal gap, and it lives in the founder's own
  re-hosting, not in the llama.cpp-sourced files.** See design-question **D** for a fix that may dodge the lawyer.

### F5 — The Tesseract copyleft set is ~56 DLLs, broader than cairo/pango/libgcc/libstdc++
`native-bin/tesseract/*.dll` = 56 DLLs. Beyond the four the tempdoc names, the copyleft/conditional set also
includes the **GLib family** (`libglib/gio/gobject/gmodule-2.0` — LGPL-2.1), **libgraphite2** (LGPL-2.1/MPL),
**libfreetype-6** (FTL **or** GPLv2 dual), **libharfbuzz** (Old-MIT), plus many MIT/BSD/zlib libs. **None are
attributed** — `NOTICE-TESSERACT.txt` covers only Tesseract core + `eng.traineddata` (both Apache-2.0); the
canonical doc is also silent on the bundled DLLs. The review needs a **per-DLL license inventory** (all 56), not
a four-library spot-check, and the notice must enumerate them. Mitigant: all are *dynamically-linked separate
DLLs*, so the LGPL "user can replace the library" condition is satisfied **by construction** — the outstanding
duty is shipping the license **texts**, not relinking.

### F6 — ★ The work has a CANONICAL home the tempdoc never names
`docs/reference/legal/ai-runtime-and-model-redistribution.md` (status: stable) is the governed home of the
runtime/model redistribution analysis. Per the `consult-doc-hint`/`maintain-doc-hint` discipline + `docs-regen`,
632's native-binary findings + the completed NOTICE/THIRD_PARTY_NOTICES should **update this doc in the same
pass**, not just spawn new files — otherwise the canonical analysis drifts further from reality. It already has
drift to note (it cites `model-registry.v1.json` + `Qwen3-VL-8B`/`nomic-embed`; live is **v2** + `Qwen3.5-9B`,
no nomic) — fixing that is arguably in-scope for this workstream since 632 touches the same subject; at minimum
log it. **This is the single biggest omission in the current scope: it's framed as "create files" when it's also
"maintain the canonical legal doc."**

### F7 — SPDX headers: 5 files already carry them, not "0 today"
`SPDX-License-Identifier: Apache-2.0` is already present on `WitnessController.java`,
`PluginVerificationController.java`, `LiveWitness.java`, and the two corresponding `*Test.java` (5 files). The
codemod must be **idempotent** (skip files with an existing header) or it double-stamps. **Scope decision (flagged
to us by 631's boundary check):** #1 scopes the codemod to `modules/**/src/main/**`, but the now-published
`scripts/` tree also wants headers — decide modules-only vs. modules+scripts before the codemod runs.

### F8 — Minor doc-discrepancies in the private `license-runbook.md` (don't follow it blindly)
The companion runbook (`docs/business/go-to-market/cutover-package/license-runbook.md`) has two stale specifics:
(a) it says the report lands in `build/reports/dependency-license/…` — the actual override is
`build/reports/licenses/third-party-licenses.{json,html}` (`build.gradle.kts:601-606`); (b) it says "the README
already has a `<<THIRD_PARTY_NOTICES>>` placeholder" — the **shipped** `README.md` has no such placeholder (only
`- License: [LICENSE](LICENSE)` at `:16`); the placeholder lives in the *draft* `positioning/readme-draft.md`.
Add the THIRD_PARTY_NOTICES link to the real `README.md` as part of #1.

## Critical analysis — design questions for the founder (evidence-backed; not yet decided)

- **A — NOTICE architecture: consolidate into root, or reference the component notices?** The legally-binding
  notices ship *with the installer* (the binaries aren't in git). Stuffing the root `NOTICE`/`THIRD_PARTY_NOTICES`
  with Tesseract-DLL + NVIDIA + model attributions describes things the **git repo + a default `build` do not
  redistribute** (models are `-PincludeOnnxModels`-gated; `native-bin/` is gitignored). Cleaner split: root
  `NOTICE`/`THIRD_PARTY_NOTICES` = what the *repo + standard build* ship (Java deps + llama.cpp source + ONNX
  models *when bundled*); an **installer-time aggregated NOTICE** = the native binaries (Tesseract DLLs, NVIDIA,
  GGUF). The tempdoc treats "complete the NOTICE" as one blob; it's really two audiences (repo cloner vs.
  installer user). **[founder decision.]**
- **B — The license-gate's hard part is the override map, not the allowlist (now PROVEN — see F9).** The
  allowlist (Apache-2.0, MIT, BSD-2/3, MPL-2.0, EPL-2.0, AFL-3.0) is directionally right, but I ran the report:
  ~8+ deps would false-positive (dual-licensed logback/JNA/jhighlight collapsed to LGPL; MPL-**1.1**; UnRar;
  Go License; Unicode-3.0; 0BSD; Public Domain), and jk1 matches **display-name strings**, not SPDX ids. Budget
  this as "author + maintain a per-dependency override/normalizer map in jk1's vocabulary," not "add an allowlist."
- **B′ — junrar's UnRar license is a real [founder/legal] decision, not a string artifact (F9).** Field-of-use
  restricted, non-OSI; accept-and-document or drop RAR support. Don't let it hide in the allowlist noise.
- **C — ★ Sequence the gate-enforcement against a clean baseline.** Wire `checkLicense` as **blocking** only after
  a run over today's `runtimeClasspath` is green (override map applied); otherwise the first public push is red.
  Prefer adding the missing `.pom`s to `verification-metadata.xml` over `-D…verification=lenient` in CI (F0).
- **D — The NVIDIA re-hosting may be a packaging fix, not a legal-review spend.** Bundling the cuDNN/ORT-CUDA
  runtimes so they're downloaded *by the app* into the app's own dir and "accessed only by JustSearch" satisfies
  the EULA/SLA **by construction** — and moots the lawyer for the founder-re-hosted zips (F4). The standalone
  public-release-page `downloadUrl` is what manufactures the "stand-alone product" exposure. Framing to surface:
  **re-architect the distribution channel (app-gated fetch) > pay to review a posture you can simply avoid.**
  (Caveat: the cudart/cublas zips are llama.cpp's public releases already — that posture isn't the founder's to fix.)

## Recommended scope adjustments (for founder confirmation)
1. **Add F6 as an explicit deliverable:** "update `docs/reference/legal/ai-runtime-and-model-redistribution.md`"
   alongside creating NOTICE/THIRD_PARTY_NOTICES (and fix its v1→v2 / Qwen drift).
2. **Re-scope #3's DCO** to "wire the CI check + fix CONTRIBUTING's React/`gate.ps1`/slug staleness" — the DCO
   prose is already written (F1).
3. **Widen the two native-binary reviews** with the real evidence: review the **cuDNN SLA separately** + scope to
   the **founder-re-hosted** ORT-CUDA/cuDNN zips (F4); inventory **all 56 Tesseract DLLs** (F5).
4. **Add Qwen to the model attributions by hand** (F2/F3 — `generateOnnxNotice` won't supply it).
5. **Decide the SPDX codemod scope** (modules-only vs. +scripts) and make it idempotent (F7).
6. **Record the working `generateLicenseReport` invocation** (3 flags — F0) in `public-ci.yml` + the runbook, and
   add the missing OpenTelemetry/okhttp/guava `.pom`s to `verification-metadata.xml` so CI needn't run `lenient`.
7. **Scope "enforce the license gate" as an override-map task** (F9/B), and surface **junrar/UnRar** as an
   explicit founder decision (B′).

## Candidate *new goals* (theory check — are 632's three goals the complete set?)
The three goals cover repo-side paperwork + the two reviews + contributor terms. Read the **Done** criterion at
full strength ("every shipped dependency + model is attributed") and two genuine **goal-level** gaps appear that
aren't refinements of the existing items — flagged for founder fold-in:
- **G-A — Maintain the canonical legal doc** (`docs/reference/legal/ai-runtime-and-model-redistribution.md`).
  Already in the recommended-adjustments list (#1) but it is a *deliverable in its own right*, not a sub-step of
  "complete NOTICE": the governed doc owns this analysis, the `consult/maintain-doc-hint` discipline requires it,
  and it currently has stale v1/Qwen3-VL content. (F6.)
- **G-B — Surface notices to the *end user* (in-app or installer).** **Verified absent (2026-06-23):** no
  `modules/ui-web/src` or Java surface renders third-party notices — no "About → Open Source Licenses" view; the
  only matches are system/readiness *notices*. The canonical doc explicitly recommends "expose notices in-app
  (Settings → About → Third-Party Notices)." This is **load-bearing for completeness, not cosmetic**: the LGPL-2.1
  deps (logback/JNA/jhighlight, F9) and the Tesseract LGPL DLLs (F5) require the user be *able to access* the
  license/attribution. The component NOTICE files ship into `native-bin/`, but a user has no in-product path to
  them. "Every shipped dependency attributed" isn't satisfied if attribution is unreachable. **[founder: in-app
  viewer vs. a discoverable bundled NOTICES file is the design call.]**
- **G-C — SBOM (CycloneDX)** — *boundary note, not a hard add.* The readiness doc lists SBOM under **M2** (grant
  work) and the CRA goal already references SBOM duties (~2026–2027). It's licensing/supply-chain-adjacent and
  CRA-linked, so it *could* live here — but it's deliberately scoped to M2 elsewhere. Name the boundary explicitly
  so it doesn't fall between 632 and M2. (Threat model + the PII/history scrub stay out — 631/634 + the security
  workstream own those.)

## Pre-implementation confidence pass (2026-06-23 — read-only + throwaway probes; no feature work)

Ran the six weakest-confidence areas of the *remaining implementation* to ground. All probes were throwaway
(scratchpad/cache reads, a real `checkLicense`-mechanism doc pull, a real `license-checker` run); no NOTICE,
gate config, SPDX header, or doc was authored.

- **C1 — license gate is configurable to green (HIGH confidence).** jk1's `allowedLicensesFile` is JSON with
  `moduleLicense` / `moduleName` / `moduleVersion`, each **exact-or-regex** matched, and a dep passes if **≥1
  declared license matches** (per the plugin docs). Verified the decisive case in the cache: **JNA's POM declares
  BOTH `Apache-2.0` and `LGPL-2.1-or-later`** (`F:\caches\gradle\…\jna\…pom`) — so JNA (and the other dual deps
  that declare their permissive side: logback EPL/LGPL, jhighlight CDDL/LGPL) **pass automatically** once
  Apache/EPL/CDDL are allowlisted; the jk1 report's single-LGPL display is just the renderer collapsing. Net: the
  gate needs an allowlist in **jk1's display-string vocabulary** (incl. `Eclipse Public License - v 2.0`,
  `The 3-Clause BSD License`, etc.) + a **per-`moduleName` allow for junrar** + a decision to allow
  `Mozilla Public License Version 1.1` (juniversalchardet). Implementation ≈ author one JSON + run `checkLicense`.
- **C2 — npm needs a SECOND generator; the tree is clean (HIGH).** `license-checker --production` over `ui-web`:
  all permissive — MIT 83, BSD-3 22, Apache-2.0 12, ISC 5, dual MIT/Apache 5, **0** GPL/LGPL/AGPL. The only
  non-trivial entries: `dompurify` (MPL-2.0 **OR** Apache-2.0 → take Apache) and `caniuse-lite` (**CC-BY-4.0**, but
  it's a **build-time** browserslist datum, not in the shipped bundle → attribution is courtesy, not duty). **But
  jk1 covers only the Gradle classpath, and `ui-web` has no license tooling** → THIRD_PARTY_NOTICES must add an
  **npm generator** (and check the Tauri/Rust `modules/shell` Cargo tree — not yet inventoried). This is a small
  real sub-goal #1 doesn't name.
- **C3 — NVIDIA download is app-driven; exposure is the public *hosting* (MEDIUM-HIGH).**
  `AiInstallService.startInstall(acceptTerms)` → `runInstallInternal` → `DownloadExecutor` fetches the registry
  `downloadUrl`s into the app's AI Home at runtime, **terms-gated**. So "accessed only by your application" is
  satisfied for the app's own use; the residual EULA/SLA exposure is purely that the cuDNN/ORT zips are **publicly,
  independently downloadable** release assets on `justsearch-releases`. **Fix without a lawyer:** stop hosting them
  as separately-fetchable public assets (gated download or inside the installer) → removes the "stand-alone
  product" strain by construction. 2a is therefore largely a **distribution-hosting decision** (founder: where/how
  to host) + the cuDNN-SLA note (F4), not necessarily a paid legal review.
- **C4 — SPDX codemod is mechanical but large + uncoordinated (HIGH, with a caveat).** ~**1,934 files** (1,471
  Java `src/main` + 463 `ui-web/src` non-test/non-generated TS); **3 already stamped** (idempotency target);
  **no Spotless `licenseHeader` config exists anywhere** → no formatting conflict, but also no enforcement, so
  new files would drift. Recommend pairing the codemod with a **Spotless `licenseHeader` rule** (auto-applies +
  enforces forward). Caveat: a ~2k-file diff is a big merge-conflict surface against the 3–4 parallel agents on
  `main` — land it as an isolated, late commit.
- **C5 — verifiable-now vs. only-at-cutover (sets the confidence ceiling):**

  | Item | Locally verifiable NOW | Author-blind until public repo (→ 634) |
  |---|---|---|
  | NOTICE / THIRD_PARTY_NOTICES content (Gradle **+ npm**) | ✅ generate + eyeball | — |
  | SPDX codemod + build-green | ✅ | — |
  | License gate config + `checkLicense` pass | ✅ run locally | wiring into `public-ci.yml` green on hosted lane |
  | CONTRIBUTING fixes / canonical-doc update / model-registry `license` field | ✅ | — |
  | DCO CI check | yaml authorable | the **DCO GitHub App** install is a repo setting (founder, post-flip) |
  | NVIDIA hosting · junrar/UnRar · trademark · CRA · in-app-notice | — | **founder/legal decisions** (not agent-verifiable) |

- **C6 — Tesseract attribution has NO upstream source (raises 2b effort).** The bundle ships only Tesseract's own
  Apache `LICENSE` + `AUTHORS` (`native-bin/tesseract/doc/`) — **no per-DLL manifest** for the 56 DLLs. So the
  cairo/pango/GLib/libgcc/… attribution must be **hand-assembled from MSYS2/mingw package metadata** (tractable,
  but manual, with residual mis-attribution risk) — 2b is "research 56 libs + assemble texts," not "ship texts that
  already exist." Minor: Qwen `mmproj-F16.gguf` inherits the base model's **Apache-2.0**; `model-registry.v2.json`
  confirmed to carry **no `license` field** (only `termsUrl`) — add it if a data-driven NOTICE is wanted (F3).

### Critical confidence rating for the remaining work: **7 / 10**
The agent-actionable core — NOTICE, the **two** THIRD_PARTY_NOTICES generators, the SPDX codemod, the license
gate, the CONTRIBUTING/canonical-doc fixes, the DCO yaml — is now de-risked to ~8–9: the mechanisms are proven
and locally verifiable. Four things hold it at 7, and three of them are **structural, not resolvable by more
investigation**: (a) the Tesseract 56-DLL attribution is manual with no authoritative source (mis-attribution
risk); (b) the CI-wiring/DCO-app/gate-on-public-lane cannot be validated until the public repo exists (inherent,
by 631/634's design); (c) genuine founder/legal decisions gate parts of goals #2–#3 (NVIDIA hosting, junrar,
trademark, CRA); (d) the SPDX mega-diff needs cross-agent coordination. None are surprises now — they're known and
scoped, which is the point of this pass.

## Confidence pass #2 — the attribution-as-projection DESIGN (2026-06-23; read-only + throwaway probes)

Pass #1 (C1–C6) covered the paperwork; this pass tests the *design's* implementation surface. Result: the design
is **cheaper and more convention-aligned than feared** — it conforms to an existing lightweight pattern, so it
de-risks rather than adds risk. No feature work; findings only.

- **D1 — the closure gate is a ~25-line CI script, NOT governance ceremony (HIGH).** Adding a full kernel gate
  (`ssot-catalog-sync`) is heavyweight — 6 files (`enforcer/classifications/rule-descriptions/truth-table` + tests)
  + a `governance/registry.v1.json` entry + `.changesets/` + self-test fixtures + a `tier-register.md` row. **But
  that is the wrong tool.** The repo's dominant convention is the **`check-*-regen` pattern**:
  `scripts/ci/check-field-constants-regen.mjs` is **25 lines** — it just `spawnSync`s `gen-field-constants.mjs
  --check` and exits non-zero on drift. ~10 such `gen-*.mjs` ↔ `check-*-regen.mjs` pairs exist. So the closure
  gate's **equality** half = a thin `check-notices-regen.mjs`; the **presence** half (every model has a `license`,
  every native DLL is mapped) = an assertion inside the generator's `--check`. **No registry entry, changeset, or
  tier-register row.** This is the single biggest de-risk of the pass.
- **D2 — projection shape settled: a node `gen-notices.mjs`, not a Gradle heredoc (HIGH).** Canonical template:
  `scripts/codegen/gen-field-constants.mjs` (+ its `--check` mode) guarded by `scripts/ci/check-field-constants-regen.mjs`.
  The notice generator reads `model-registry.v2.json` + the jk1 `third-party-licenses.json` + a `license-checker`
  npm dump + the native manifests, and renders NOTICE/THIRD_PARTY_NOTICES; `--check` re-renders and diffs. This
  **replaces the hardcoded Gradle `generateOnnxNotice`** with the house pattern (the build task can shell out to it
  or be retired). The equality gate is therefore *not new machinery* — it's the existing regen-check shape.
- **D3 — ★ Tesseract DLL licenses are a stable hand-table, gated — not auto-derived (MEDIUM-HIGH; the honest
  caveat).** No upstream per-DLL SPDX manifest exists: UB-Mannheim ships the exes as Apache-2.0; the ~14 bundled
  library families (cairo, pango, glib2, harfbuzz, freetype2, fontconfig, ICU, pixman, libffi, expat, …) are
  standard MSYS2/mingw packages whose licenses are documented **per package** on packages.msys2.org and are
  **stable across versions**. You cannot derive a license from a `.dll` binary. So the manifest is a **one-time
  hand-authored DLL→library→SPDX table**; what *closes the drift* is the gate's **presence check** — enumerate the
  actual `native-bin/tesseract/*.dll` (56 today) and **fail the build if any DLL isn't in the table**. This is the
  **exact pattern of `check-runtime-manifest-closure`'s `ALLOWED_RUNTIME_ARTIFACTS` allowlist** (hand-maintained +
  closure-gated): a Tesseract version bump that adds a DLL becomes a deliberate build failure, not silent
  under-attribution. **Honest framing for the design: it *gates* the drift, it does not *eliminate* the manual
  data.** Still a strict improvement over today (zero attribution, zero guard), and seam-consistent.
- **D4 — determinism is free (HIGH).** The jk1 `third-party-licenses.json` is **alphabetically sorted by
  `moduleName`** (verified: `ai.djl… → build.buf… → ch.qos… → com.adobe…`). `license-checker --json` is key-sorted.
  The generator should still impose a canonical sort defensively (cheap), but the inputs are ordered, so the
  equality gate won't flap.
- **D5 — the registry `license` field-add is low-risk + backward-compatible (HIGH).** The two registry copies
  (`modules/ui/src/main/resources/ai/` prod + `modules/configuration/src/test/resources/ai/` test) are
  **byte-identical**, and **no model-registry sync/closure gate exists** (grep-clean) — the dual-copy is convention,
  not gated. Critically, `ModelRegistryLoader` builds its mapper with **`FAIL_ON_UNKNOWN_PROPERTIES` disabled**
  (`ModelRegistryLoader.java:25`) → adding `license` to the JSON **cannot break deserialization even mid-change**
  (old code ignores it). No JSON schema exists (POJO-parsed). Lockstep for the field: both JSON copies + the
  private `RawPackage` record + `ModelPackage` (add a back-compat secondary ctor, matching the existing pattern at
  `ModelPackage.java:46`) + the `convertPackage` mapping + any value-asserting loader test. **Bonus:** the new
  license generator's presence-check could also assert the prod/test copies match — closing the *currently-ungated*
  dual-copy gap as a free side-effect.

### Updated confidence rating: **8 / 10** for the remaining work (was 7/10 in pass #1)
The design *raised* confidence rather than lowering it: pass #2 found its implementation conforms to a cheap,
established pattern (the 25-line regen-check, not a kernel gate), the registry field-add is backward-compatible, and
determinism is free. The remaining drags are unchanged and known: (a) the Tesseract DLL table is hand-authored
(now *gated*, so bounded — D3); (b) CI-wiring/DCO-app/gate-on-public-lane can't be validated until the public repo
exists (631/634, structural); (c) founder/legal decisions gate parts of goals #2–#3; (d) the SPDX mega-diff
coordination. Net: the *risk per piece* is now low and convention-backed; the design is **more total work** than the
bare paperwork but **durable** (stays complete via the regen-check) — which was the whole point of designing it.

## CRA guidance refresh (2026-06-23 — the one externally-fast-moving area; web pass)

CRA is the only part of 632 on actively-moving external ground (it is mid-rollout *right now* — today sits between
the 11 Jun 2026 conformity-assessment milestone and the 11 Sep 2026 reporting milestone), so it got a targeted web
pass; everything else is either internal design or already-fresh license facts. **Material new development past the
Jan-2026 knowledge cutoff:** the European Commission **published draft CRA guidance on 3 March 2026** (open for
stakeholder feedback) that, for the first time, addresses FOSS, the open-source-steward category, and *monetisation*
concretely. What it sharpens for JustSearch's open-core plan:
- **Manufacturer vs steward is now guidance-defined.** *Directly monetising* the FOSS (paid tier, premium
  features, paid support) → **manufacturer** for that product = **full** obligations (conformity assessment, CE,
  technical documentation, vuln handling). Sustainably supporting FOSS dev *for* commercial activity *without
  directly monetising it* → **steward** = light-touch (cybersecurity policy, vuln/incident reporting, info-sharing;
  **no** conformity assessment/CE/tech-docs; **no** administrative fines). JustSearch's paid tier points at the
  **manufacturer** classification for the paid product — heavier than the original note's "can pull you in."
- **Monetisation criteria (3 Mar 2026 draft):** free-but-monetising-services-around-it = commercial; access
  conditioned on personal-data processing beyond security/compat/interop = commercial; **donations** can count as
  placing-on-market *if* essential functionality/updates are conditional on donating or donors get contractual
  advantages beyond community perks. Relevant if JustSearch ever takes sponsorship/donations.
- **Responsibility attaches to who publishes & controls governance, not who holds commit rights** — matters for an
  Option-C develop-in-public repo accepting outside contributors.
- **Status:** draft guidance, in consultation → details may shift before final; treat as direction, not settled law.
  Founder/legal should track the final guidance. (Primary: EU `digital-strategy.ec.europa.eu/en/policies/
  cra-open-source` + the 3 Mar 2026 draft-guidance announcement; corroborated by OpenSSF + the ORC working group.)

This refresh touches **only** the CRA scoping note (goal #3) — it does **not** change the licensing/attribution
design above, which is internal architecture on stable ground and needed no external pass.

### Addendum — second web pass (2026-06-23): corroboration + 4 new specifics
A fresh targeted pass **independently confirmed** everything above (the 3 Mar 2026 draft guidance, the
manufacturer/steward split, the monetisation criteria) and adds four decision-useful specifics not yet stated:
- **The 11 Sep 2026 reporting is concrete + mechanised.** Manufacturers report actively-exploited vulnerabilities
  and severe incidents through ENISA's **Single Reporting Platform (SRP)** on a fixed cadence: **24 h** early
  warning → **72 h** full notification → **14-day** final report after a fix (vulns) / **1-month** final (severe
  incidents). It binds **every product on the EU market, including legacy**. Stewards get a *subset* (severe
  incidents affecting systems they provide) and **no fines**.
- **SBOM is a CRA *manufacturer obligation*, not just an M2 nicety** — machine-readable, covering at least
  top-level dependencies, kept current, provided to authorities on request. This **validates the design's G-C
  boundary note**: the attribution-as-projection sources (the same JVM/npm/Cargo/native/model declarations) are
  exactly what a CRA SBOM projects from — so when the paid product needs one, it is another *projection consumer*,
  not new authorship. Building it still waits for the paid tier (recognise-not-build holds).
- **Launch-timing corollary (de-risks the go-public deadline).** Because a **non-monetised** FOSS release "should
  not be considered a commercial activity", shipping JustSearch's free core publicly does **not** make the founder
  a manufacturer and does **not** trip the 11 Sep 2026 reporting clock. The deadline bites only when/if the **paid
  tier is placed on the EU market**. So 631/633/634's public launch is **not** gated by the CRA milestone; only the
  later paid product is.
- **Stakes quantified:** manufacturer non-compliance fines run up to **€15 M or 2.5 % of global turnover**;
  stewards are exempt from administrative fines entirely — which is why the free/paid line is a CRA-classification
  decision, not just a product one.

Net: no change to the design or the founder-decision direction — this pass *raises confidence* the existing CRA
note is current and correct, pins the reporting mechanics, and ties the SBOM obligation back into the
attribution-as-projection sources. (Primary: EU `digital-strategy.ec.europa.eu/en/policies/{cra-open-source,
cra-reporting}` + ENISA SRP page; corroborated across multiple 2026 compliance trackers.)

---

## Founder decisions update (2026-06-23, takeover session) — libjbig → REBUILD; NVIDIA framing clarified; source-vs-binary split

Reviewed the two open dispositions with the founder. Two decisions recorded + one framing correction; remaining
work re-scoped at the foot.

### libjbig → ✅ DECIDED: rebuild (drop keep + commercial)
The earlier "3-way founder/legal call" collapses to **one** path. The founder rejects the other two:
- **Keep-GPL** — rejected. Not worth carrying GPL-2.0 in an Apache-2.0 product for a rare bilevel-fax codec; and it
  is the *riskiest* keep (Kuhn's stated dynamic-linking-propagates-GPL intent), not merely the least appealing.
- **Commercial license** — rejected. Bespoke negotiated Cambridge-Enterprise quote, no public price, not worth it
  for a codec of negligible value.
- **Rebuild** — the decision. libtiff `--disable-jbig` rebuild + re-pin `tesseract-windows.v1.json` (new source URL
  + SHA) + re-validate the DLL set. Removes the lone copyleft native binary; the OCR path has no JBIG dependency
  (functional loss negligible).

**Why "keep" was never a real choice (corrects the earlier "interim keep state, no pressure" framing):** removal is
*only* achievable via the rebuild — `libjbig-0.dll` is a **hard load-time import** of `libtiff-6.dll`
(PE-import-verified), so "delete the DLL" breaks libtiff → Leptonica → ALL OCR. "Stop shipping GPL" and "rebuild"
are therefore the *same action*; there is no remove-now-rebuild-later. The GPL-compliance scaffolding already in the
tree (`packaging/runtime/NOTICE-JBIGKIT.txt` + `LICENSE-GPL-2.0.txt` + the `native-license-acceptances.json` ledger
entry) is a **safety net for the pre-rebuild window only**, not a recommendation to ship GPL — it is **deleted
together with the DLL** on rebuild (the ledger `_comment` already states this teardown).

### The two surfaces — libjbig does NOT block the *source* go-public
A crux fact that re-sequences this work: **`native-bin/` is gitignored (`.gitignore:8`); `libjbig-0.dll` is not in
the git repo** — only attribution/manifest/gate *metadata* tracks the name (`git grep -li libjbig` = 7 metadata
files, zero binaries). So "full OSI" splits into two surfaces:
- **Public source repo (Apache-2.0):** already clean — zero GPL contamination. The OSS *source* launch (631/633/
  634) is **not gated** on the rebuild.
- **Shipped product binary (the installer's Tesseract OCR bundle):** the GPL DLL lives here (the build extracts the
  stock UB-Mannheim installer wholesale, no DLL curation). The rebuild gates a fully-permissive *binary*
  distribution.

After junrar's removal (only non-OSI *dependency* — done) + this rebuild (only copyleft *native binary*), **both
surfaces are fully permissive/OSI-clean** — nothing else remains on the licensing axis.

**Status of the rebuild:** founder-infra native-build follow-up (no in-repo Tesseract build infra exists; the
rebuilt artifact also needs hosting). The disposition gate's JBIG-KIT acceptance stays valid + compliant until the
rebuild lands, then the manifest row + acceptance + `NOTICE-JBIGKIT.txt` + `LICENSE-GPL-2.0.txt` drop together.

### NVIDIA → framing clarified: "accept" = REVIEWED-COMPLIANT, not accepted-risk
The ledger word "accept" reads as "tolerated a defect" — it is not. `restricted` flags a constraint a machine
cannot auto-judge (an EULA/SLA is prose, not an SPDX string), routing it to a **human waiver/sign-off**. NVIDIA's
review came out **affirmatively compliant** (app fetches the runtime into an app-private dir → "accessed only by
your application"; material additional functionality beyond the libraries; the "no stand-alone product" clause bars
offering the libs *as a dev-kit*, not app-fetched zips — the universal PyPI/conda CUDA+cuDNN pattern). The
disposition *type* is restricted; the *outcome* is compliant. Two residuals unchanged: (1) reasonable license
reading, **not** formal legal advice — a lawyer's sign-off remains the go-public authority `[founder/legal]`;
(2) it hinges on **not stripping NVIDIA's license files** from the shipped runtime — a one-time check of the
extracted `…/variants/cuda12/` dir.

### Where 632 stands after these decisions — remaining work pre-going-public
**All agent-actionable licensing/attribution work is implemented + merged** (attribution-as-projection spine, jk1
license gate, junrar removal, native-binary disposition gate, SPDX headers, canonical-doc + CONTRIBUTING fixes).
Remaining = a short, well-typed list; **none of it blocks the *source* go-public**:

- **[founder-infra] Tesseract `--disable-jbig` rebuild** — gates a fully-clean *binary*, not the source repo. The
  only OSI-cleanup item with engineering work (native build + host + re-pin). Not urgent: interim state is
  gated-compliant.
- **[founder, post-flip] DCO GitHub App install** — a repo setting; the CI yaml + CONTRIBUTING prose are done.
- **[founder/legal] one-time NVIDIA notice-retention check** in the shipped `cuda12/` dir + a lawyer's final
  sign-off on the redistribution posture.
- **[founder/legal] CRA tracking** — only the *future paid tier* trips obligations; the free launch does not.
  Track the final EU guidance.
- **Decided/closed:** junrar (removed), trademark (keep `justsearch` provisionally), NVIDIA posture
  (reviewed-compliant), libjbig (rebuild).
- **Deferred by design (not blockers):** G-B in-app notice viewer (founder design call); G-C SBOM (M2 / paid-tier).

## libjbig rebuild — route investigation + theorized change-set (2026-06-23, takeover session; read-only + web)

The "rebuild with `--disable-jbig`" line above was under-scoped. Investigated the real bundle mechanism + the two
alternatives I floated. Result: the **destination is confirmed**, the tempting shortcut is **closed**, and one route
is now **concrete + licensing-guaranteed-clean**.

### Real mechanism (verified — `packaging/runtime/tesseract-windows.v1.json`)
JustSearch **does not build Tesseract.** It downloads the **official prebuilt Tesseract 5.5.0 NSIS installer**
(`tesseract-ocr-w64-setup-5.5.0.20241111.exe`, the UB-Mannheim/MSYS2 build) and 7z-extracts it wholesale
(`#/dl.7z`). Only `tesseract.exe` + `eng.traineddata` are SHA-pinned; the ~56 DLLs (incl. `libjbig-0.dll`) ride
along from the extracted installer. The bundle is **MinGW-built** (libgcc/libstdc++ present in
`tesseract-bundled-libraries.v1.json` as `copyleft-with-linking-exception`). So `--disable-jbig` is a *libtiff
configure flag* with **no JustSearch build to flip it in** — "rebuild" means producing a jbig-free artifact
ourselves, not re-pinning a SHA.

### Alternative B (drop libtiff entirely) — ❌ RULED OUT
TIFF is a **first-class supported OCR format**, not droppable:
- `PolicyDrivenTikaExtractor.java:633-634,662-663` — `.tif`/`.tiff` are OCR-eligible + raster-image-eligible.
- `VisualRoutingDecision.java:19` — `.tiff` is in the supported visual-extraction set.
- OCR runs via Tika `TesseractOCRParser` (`StructuredContentExtractor.java:290`) → bundled `tesseract.exe` →
  leptonica → **libtiff**; no ImageMagick is bundled, so image files go to Tesseract directly.

So libtiff is load-bearing — dropping it breaks OCR of *all* TIFFs, not just JBIG-encoded ones. A related shortcut
also dies: converting TIFF→PNG in-JVM before OCR does **not** help, because `libjbig-0.dll` still **ships** (hard
import of libtiff) regardless of code path — the only way to stop *shipping* it is a jbig-free libtiff.

### Alternative A (jbig-free libtiff via a managed build) — ✅ VIABLE, the route
**vcpkg's `tiff` port disables jbig by default, explicitly for its GPL licensing** (`-Djbig=OFF` in the portfile;
web-verified). So a vcpkg/managed libtiff is jbig-free out of the box while keeping every common TIFF compression
(LZW/Deflate/JPEG/PackBits/LZMA/WebP/ZSTD/LERC). Only loss = JBIG-encoded TIFFs (rare bilevel fax) — matches the
negligible-loss claim. Two build paths:
1. **Single-DLL swap** — build only a jbig-free `libtiff-6.dll` (vcpkg `x64-mingw-dynamic` to match the bundle's
   MinGW toolchain), overlay it, delete `libjbig-0.dll`. libtiff is **C** (stable ABI in the soname-6/v4.x series;
   x64 Windows has one calling convention) so cross-compiler risk is far lower than a C++ swap. Residual risk:
   exported-symbol/struct-layout skew vs. the bundle's libtiff version + libtiff's transitive deps (zlib/libjpeg).
   Note the dependency: a jbig-free libtiff has **no `libjbig` import**, so deleting `libjbig-0.dll` is only safe
   *after* the swap (the stock libtiff hard-imports it).
2. **Whole-stack vcpkg rebuild** — build libtiff + leptonica + tesseract together via vcpkg; internally consistent +
   jbig-free by policy, but replaces the entire trusted UB-Mannheim bundle (bigger trust shift + build surface).

**Route confidence: ~7/10** (was ~3/10). A managed source guarantees the licensing outcome by its own policy; the
dead-end is closed; C-ABI makes the swap tractable. Held below high by: it's still a founder-infra native build
(not a re-pin), needs ABI/dep validation, and carries a standing maintenance tax + a supply-chain-trust shift (every
Tesseract bump re-does the custom step; the founder vouches for the binary — a mild downgrade vs. canonical upstream).
Path 1 minimizes the trust shift (54/56 DLLs stay canonical upstream); Path 2 minimizes ABI risk.

### Theorized change-set (what becomes necessary once the founder produces the jbig-free artifact)
The 632 disposition gate makes this a **gate-guided teardown**, not a manual hunt — the structure already built pays
off here. Ordered:

1. **[founder-infra] Produce the jbig-free libtiff** (Path 1 or 2 above) and host it. Path 1 hosts ONE small DLL;
   Path 2 hosts a full curated bundle.
2. **Re-pin `packaging/runtime/tesseract-windows.v1.json`** + the build's extract logic
   (`modules/ui/build.gradle.kts` consumes the manifest). Two manifest shapes:
   - *Path 1:* keep pinning the upstream installer, add a **post-extract curation step**: overlay the hosted
     jbig-free `libtiff-6.dll` (pin its SHA in `files[]`) and remove `libjbig-0.dll`.
   - *Path 2:* point `sourceUrl`/`sourceSha256` at the curated bundle; extract wholesale as today.
3. **Gate-forced attribution teardown (agent-actionable once the bundle exists):**
   - `tesseract-bundled-libraries.v1.json` — **remove the JBIG-KIT row** (and update libtiff's row if its version
     changed). The gen-notices **presence-check #3** (`readdirSync` of `native-bin/tesseract/*.dll` ⊆ table) then
     passes only if the table matches the *actual* post-swap DLL set — so any other DLLs the new libtiff drags in/out
     are forced into the table too.
   - `config/native-license-acceptances.json` — **remove the JBIG-KIT acceptance** entry. The gate's *symmetric*
     check (a stale acceptance with no matching firing artifact FAILS) **forces** this removal — you cannot leave a
     dangling waiver. After removal the only firing native artifacts are NVIDIA CUDA + cuDNN (`restricted`, accepted).
   - **Delete** `packaging/runtime/NOTICE-JBIGKIT.txt` + `packaging/runtime/LICENSE-GPL-2.0.txt` (no GPL ships).
   - **Regenerate** `NOTICE` / `THIRD_PARTY_NOTICES` (`gen-notices.mjs`); `check-notices-regen` then asserts no drift.
   - Update `docs/reference/legal/ai-runtime-and-model-redistribution.md` (drop the libjbig/GPL-2.0 analysis).
4. **Validation:** OCR eval over common-compression TIFFs (LZW/Deflate/JPEG) confirms TIFF OCR still works; confirm a
   JBIG-encoded TIFF degrades gracefully (unsupported, not a crash); re-run the bundled-libraries exact-coverage
   assertion + `gen-notices.test` (the firing-set assertion now excludes JBIG-KIT); `build -x test` green.

**Net:** step 1–2 are founder-infra (build + host + manifest re-pin); step 3–4 are agent-actionable and **gate-guided
to completeness** — the disposition gate's two symmetric checks (presence ⊆ table; no-stale-acceptance) mechanically
enforce that every JBIG trace is removed together, which is exactly the "perishable manual review → standing
invariant" payoff the gate was built for. Until step 1 lands, the interim keep-state stays gated-compliant.

## libjbig — ✅ DECIDED: KEEP (compliant GPL bundling); rebuild demoted to optional. + a real compliance gap found & fixed (2026-06-23, takeover session)

The route investigation above showed the rebuild is materially more effort than the planning estimate (founder-infra
native build + host + ongoing maintenance + a supply-chain-trust shift). Weighed against that, the founder **decided
to KEEP** libjbig under compliant GPL bundling. This **supersedes the earlier "rebuild" line** — rebuild-without-JBIG
remains available as an *optional future purity step*, not a requirement.

### Why keep is sound (the licensing reality)
- **Public SOURCE repo: zero libjbig impact.** `native-bin/` is gitignored — `libjbig-0.dll` is never in git
  (`git grep -li libjbig` = metadata only). The Apache-2.0 source release is clean regardless; libjbig is purely a
  **binary-distribution** question, and does **not** block the source go-public.
- **Binary: GPL-2.0 lets you redistribute** if you ship the license text (§1) + a corresponding-source / written
  offer (§3), keep notices intact, and keep the DLL dynamically replaceable. All satisfiable; the codec is
  dynamically linked + separately replaceable by construction.
- **Infection risk is low.** JustSearch invokes Tesseract as a **separate process** (Tika `TesseractOCRParser` shells
  out to `tesseract.exe`); the app never links the GPL code. The process boundary is the strongest separation. Kuhn's
  dynamic-linking-propagates-GPL stance concerns the third-party `libtiff↔libjbig` link, not JustSearch's invocation.
  A lawyer's sign-off remains the final authority `[founder/legal]`.
- **Cost of keep:** a standing §3 source-offer obligation (jbigkit source is public → trivial to honor) + the binary
  cannot be marketed as "100 % permissive / no copyleft" (honest framing: "Apache-2.0 + one bundled GPL-2.0 OCR
  codec"). Fully reversible.

### ★ Compliance GAP found and FIXED — the §1/§3 files were not actually shipping
The merged disposition-gate work added `LICENSE-GPL-2.0.txt` + `NOTICE-JBIGKIT.txt` to the repo and the ledger, and
the ledger *claimed* they "ship at packaging/runtime/…" — **but they were never wired into the installer bundle.**
`modules/ui/build.gradle.kts:1496-1507` staged the Tesseract payload (incl. `libjbig-0.dll`) + manifest + **only
`NOTICE-TESSERACT.txt`** into `native-bin/tesseract/`; `git grep` confirmed `NOTICE-JBIGKIT`/`LICENSE-GPL-2.0` were
referenced in **no** bundle copy step. So the shipped binary carried the GPL DLL **without** its required license
text + source offer → the "keep" posture was **non-compliant in fact, despite the gate asserting otherwise.**

**Fixed (2026-06-23):** added two `from(...)` staging lines to `bundleSidecarResources` so `NOTICE-JBIGKIT.txt` +
`LICENSE-GPL-2.0.txt` land in `native-bin/tesseract/` next to `libjbig-0.dll`. Updated the ledger decision text
(`config/native-license-acceptances.json`: keep-decided + accurate "staged by build.gradle.kts" wording) and the
canonical legal doc (`ai-runtime-and-model-redistribution.md`: records the KEEP decision, not an open 3-way). The
disposition gate is unchanged: libjbig stays `strong-copyleft` with its (now-accurate) dated acceptance → gate green.

### Where this leaves the remaining work (replaces the rebuild line in the earlier "remaining work" list)
- ~~`[founder-infra]` Tesseract `--disable-jbig` rebuild~~ → **demoted to optional** (purity only; not a go-public
  blocker). The keep path is now genuinely compliant.
- The **gate-forced teardown change-set above** stays recorded for *if/when* the optional rebuild is ever taken.
- All other remaining items unchanged: `[founder, post-flip]` DCO app install; `[founder/legal]` NVIDIA
  notice-retention check + lawyer sign-off; `[founder/legal]` CRA tracking.

**Verification:** Gradle staging is a resource-copy addition (no dependency change → no lockfile regen);
`native-bin/tesseract/` is populated locally and both notice files exist in `packaging/runtime/`; the change is
inert to `gen-notices` (which reads manifests, not the bundle) so `gen-notices.test` + `check-notices-regen` are
unaffected. Canonical-doc regen (`llmstxt-generate` + `skills-sync`) run as part of this pass.

## Vendored `third_party/llama.cpp/` removed — repo-size + provenance cleanup (2026-06-23, takeover session)

The OSI-provenance-confidence pass (above) flagged `third_party/llama.cpp/` as the single largest vendored-code
surface in the repo (1893 tracked files) — and investigation showed it was **vestigial**: the dead-code audits
deleted the *in-process FFM llama.cpp Java bindings* (512/556) but **explicitly excluded** this vendored C++ *source
tree* (556: "third_party/llama.cpp is excluded from all sub-audits (vendored upstream)"). It survived only as the
`-PllamaRuntime=source` developer-override build path + reference material; production ships the **prebuilt download**
(`b8571`), and the vendored tree was **stale** (~b7502-era — 236 §8), so the source override would build a *different,
older* binary than ships. **Founder decision: delete it.**

**Removed (coordinated, build-verified):**
- `third_party/llama.cpp/` (1893 files) + the now-unused `build-logic/.../conventions/LlamaServerTasks.kt`.
- `modules/ui/build.gradle.kts` — dropped the two `conventions.LlamaServer*Task` imports, the `llamaCppDir`/
  `llamaBuildDir`/`llamaRuntimeMode` vars, the `source`-mode override (`usePrebuiltLlamaRuntime` now just
  `isWindowsHost`), and the three source-build tasks (`configureLlamaServer`/`buildLlamaServer`/
  `stageLlamaServerFromSource`); `stageLlamaServer` now depends only on `stageLlamaServerFromPrebuilt`.
- `InferenceConfig.java` — removed `findSourceBuildExecutable` + its call site (the `third_party/llama.cpp/build/`
  runtime probe); removed its test in `InferenceConfigServerExeTest`.
- **Attribution preserved (NOT removed):** we still ship the prebuilt llama-server binary, so llama.cpp stays
  attributed — `license-overrides.json` `ref` re-pointed from the deleted `third_party/llama.cpp/LICENSE` to the
  upstream URL; `THIRD_PARTY_NOTICES` regenerated (1-line, scoped diff).

**Verification:** `spotlessApply build -x test` compiles clean (Kotlin DSL + all Java) — the **only** gate failure is
the **pre-existing** `class-size` drift on `HeadAssembly.java` (1189→1200, a 629/630-era stale pin, NOT in this diff;
documented repeatedly in this tempdoc's history); this change grows no class. `:modules:app-inference:test
--tests "*InferenceConfigServerExeTest*"` green; `check-notices-regen` green (regenerated with fresh jk1 + npm +
cargo dumps). Net effect on go-public: −1893 vendored files and the largest unaudited license/provenance surface is
gone; the OSI-confidence residual shrinks to first-party-provenance + bundled assets.
