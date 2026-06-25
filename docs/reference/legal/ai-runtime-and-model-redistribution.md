---
title: AI Runtime Redistribution
type: reference
status: stable
description: "llama.cpp and model licensing considerations."
---

## AI runtime + model redistribution — legal considerations (living notes)

**Purpose**: capture potential licensing / redistribution considerations for JustSearch Desktop AI assets (runtime + models). This is not legal advice.

### Scope (what this doc covers)

- **AI runtime**: `llama-server.exe` (from `llama.cpp`), proposed to be shipped **inside the installer/app bundle**.
- **OCR runtime**: optional Windows Tesseract/Tessdata payload under `native-bin/tesseract`, used by Worker Tika OCR for scanned/image-text documents.
- **Model weights**: GGUF files downloaded into AI Home (e.g., chat model, embedding model, vision projector `mmproj`).

v3 note:

- JustSearch also supports **GPU Booster Packs (v3)**: offline packs that distribute a CUDA-capable `llama-server.exe` runtime variant plus required adjacent DLLs (and CUDA redistributables) under AI Home.

### `llama-server.exe` / `llama.cpp` license obligations

- **Source**: redistributed as the **prebuilt `llama-server` binary** — a pinned upstream build (`b8571`)
  downloaded at build time from `ggml-org/llama.cpp` releases. (The vendored `third_party/llama.cpp/` source tree
  was removed — tempdoc 632, go-public repo-size + provenance cleanup; clone upstream on demand for a source build.)
- **License**: MIT (upstream `https://github.com/ggml-org/llama.cpp/blob/master/LICENSE`)
- **Practical obligation when shipping a binary**:
  - Include the **MIT license text + copyright notice** with your distribution.

### Third‑party licenses bundled with `llama.cpp`

The prebuilt `llama-server` binary statically incorporates third‑party components with their own license notices
(upstream `llama.cpp/licenses/`).

Current notable examples in-repo:
- `LICENSE-httplib` (MIT)
- `LICENSE-jsonhpp` (MIT)
- `LICENSE-curl` (curl license)
- `LICENSE-linenoise` (BSD-style)

**Practical obligation**: if our `llama-server.exe` build contains these components, we should ship their license texts as part of our distribution (standard “Third‑Party Notices” practice).

### Recommended compliance actions (runtime)

- **Ship a Third‑Party Notices file** with the installer/app, e.g.:
  - `THIRD_PARTY_NOTICES` containing:
    - the upstream llama.cpp `LICENSE` (MIT)
    - its bundled `licenses/*` texts (httplib, nlohmann/json, curl, linenoise)
  - This file is generated — `scripts/codegen/gen-notices.mjs` projects it from the declared sources.
- **Expose notices in-app** (Settings → About → Third‑Party Notices) so end users can easily view them.
- **Pin runtime version** and document it (to keep behavior stable with `InferenceLifecycleManager`).

### Model weights are separate (and usually the bigger legal variable)

Important: shipping `llama.cpp` / `llama-server.exe` does **not** automatically grant redistribution rights for model weights.

- Each model file (chat, embedding, `mmproj`) has its **own license/terms** (often not OSI open-source).
- If we **bundle** models in the installer, or **host** them ourselves, we are redistributing.
- If we **download models from a third-party host**, we still need to ensure our use (and the user’s use) complies with the model’s terms. Some providers require specific notices or acceptance flows.

### Recommended compliance actions (models)

- Maintain a small “AI assets manifest” in-repo with, for each asset:
  - model name + version
  - upstream source
  - expected file hash (integrity)
  - **license/terms link** and a short internal summary (“redistribution allowed?” “commercial use allowed?” “attribution required?”)
- Before bundling/hosting any model weights:
  - verify redistribution permission
  - add any required notices/attribution
  - decide whether an **explicit user acceptance step** is required in-app

### Current pinned models — the registry is the license SSOT (tempdoc 632)

License is now a first-class field on every package in `modules/ui/src/main/resources/ai/model-registry.v2.json`
(the `license` field, added in tempdoc 632). The committed `NOTICE` / `THIRD_PARTY_NOTICES` PROJECT from it via
`scripts/codegen/gen-notices.mjs` (guarded by `check-notices-regen`), so this doc and the notices cannot drift from
the registry. Current chat model: `Qwen/Qwen3.5-9B` (Apache-2.0). The five ONNX/SPLADE packages are Apache-2.0
except the Davlan NER model (**AFL-3.0**); the `cuda-runtime` package is `LicenseRef-NVIDIA-CUDA-EULA`.

These licenses generally allow redistribution, but we should still:

- preserve attribution/notice requirements,
- keep a link to the upstream model card/terms in-app,
- and re-check license metadata when updating pins.

### Additional redistribution considerations (non-model)

Depending on how we build/package `llama-server.exe`, we may also need to consider:
- **MSVC runtime redistribution** (if we ship `vcruntime*.dll` / VC++ Redistributable)
- **GPU runtimes** (CUDA/cuBLAS/etc.) if we bundle vendor runtime components inside an offline GPU Booster Pack (verify redistribution rights + required notices/attribution)

#### Tesseract OCR runtime

The Windows OCR baseline packages Tesseract 5.5.0.20241111 plus English tessdata by restoring the
pinned runtime declared in `packaging/runtime/tesseract-windows.v1.json`. The notice file bundled
adjacent to the runtime is `NOTICE-TESSERACT.txt`.

- **License**: Apache-2.0 for Tesseract and `tessdata_fast/eng.traineddata`.
- **Validation**: release-side packaging runs `tesseract.exe --list-langs` with `TESSDATA_PREFIX` and
  requires `eng`.
- **Practical obligation**: preserve Apache-2.0 notice text and upstream attribution with the runtime
  payload; do not ship an unverified floating Windows installer artifact.
- **Bundled Windows DLLs are NOT all Apache-2.0** (tempdoc 632). The UB-Mannheim build bundles ~56 MSYS2/mingw-w64
  library DLLs whose licenses span permissive (MIT/BSD/zlib) AND copyleft: **LGPL-2.1+** (cairo, pango, GLib,
  graphite2, fribidi, libiconv, libintl, libdatrie, libthai), **LGPL-3.0+** (libunistring, libidn2), **GPL-3.0+
  WITH GCC-Runtime-Library-Exception** (libgcc/libstdc++), and one strong-copyleft **GPL-2.0+** codec
  (`libjbig` / jbigkit, reached via libtiff's optional JBIG support). The full per-DLL table is the SSOT
  `packaging/runtime/tesseract-bundled-libraries.v1.json` (projected into `THIRD_PARTY_NOTICES`). Obligation: ship
  the LGPL/GPL license + exception texts and keep the DLLs dynamically replaceable. **Founder decision (2026-06-23):
KEEP** the `libjbig` GPL-2.0 codec under compliant GPL bundling — the GPL-2.0 license text + a jbigkit written
source offer (`LICENSE-GPL-2.0.txt` + `NOTICE-JBIGKIT.txt`) are staged into `native-bin/tesseract/` with the binary,
satisfying GPL-2.0 §1/§3. Commercial relicensing was rejected (cost) and a rebuild-without-JBIG is deferred as an
optional future purity step. The GPL codec ships only in the **binary** distribution; the **source repo** never
carries it (`native-bin/` is gitignored), so the Apache-2.0 source release is unaffected. The disposition gate
(`config/native-license-acceptances.json`) records this as a dated acceptance.

#### CUDA redistributables (GPU Booster Pack; NVIDIA-only)

NVIDIA’s CUDA Toolkit EULA defines which components are redistributable and under what conditions.

- **Redistributable file families we expect to ship** (Windows; typical for `llama-server` CUDA builds):
  - CUDA Runtime: `cudart.dll`
  - CUDA BLAS: `cublas.dll`, `cublasLt.dll`
  - These are explicitly listed as redistributable in CUDA Toolkit EULA **Attachment A**.
    - Reference: `https://docs.nvidia.com/cuda/eula/index.html#attachment-a`

- **Distribution requirements** (high-signal excerpts):
  - Your application must have **material additional functionality** beyond the included SDK portions.
  - The distributable portions of the SDK shall only be **accessed by your application**.
  - The SDK may not be distributed as a **stand-alone product**.
  - Reference: CUDA Toolkit EULA “Distribution Requirements”: `https://docs.nvidia.com/cuda/eula/index.html#distribution-requirements`

- **cuDNN is governed by a SEPARATE agreement** (tempdoc 632). The `cuda-runtime` registry package also bundles
  **NVIDIA cuDNN** (inside `ort-cuda-runtime-*.zip` + `cudnn-9-runtime-*.zip`), which is licensed under the
  **NVIDIA cuDNN SLA — not the CUDA Toolkit EULA** — with the same "accessed only by your application" restriction
  **plus** a clause forbidding use that would subject it to an open-source license. Treat CUDA and cuDNN as two
  agreements when reviewing.
- **Re-hosting caveat** (tempdoc 632 [founder/legal]): the cuDNN + ORT-CUDA zips are currently re-hosted as
  **standalone, publicly downloadable** release assets. The app fetches them at runtime (terms-gated → "accessed
  only by your application" is satisfied for the app's own use), but the public standalone hosting strains the
  "not a stand-alone product" clause. Cleanest fix: gate the download / bundle inside the installer rather than as
  separately-fetchable public assets — removes the exposure by construction without a legal opinion.

- **Sample source code notice**:
  - If we distribute modifications/derivative works of CUDA **sample source code**, the EULA requires including the notice:
    - `This software contains source code provided by NVIDIA Corporation.`
  - In our current plan, the GPU Booster Pack ships redistributable **DLLs**, not CUDA sample source code, but we keep this requirement documented to avoid accidental violations later.

##### Required notice files for GPU Booster Packs (policy for JustSearch distributions)

To make audits and enterprise compliance easier, any JustSearch GPU Booster Pack that includes CUDA redistributables MUST also include a small notice file adjacent to the installed DLLs.

- **File name (recommended)**: `NOTICE-NVIDIA-CUDA.txt`
- **Content must include**:
  - The CUDA Toolkit EULA URL and the Attachment A URL (above)
  - The list of redistributed CUDA DLLs in the pack (e.g., `cudart.dll`, `cublas.dll`, `cublasLt.dll`)
  - The CUDA “variant id” (e.g., `cuda-12.4`) and the minimum driver requirement used by JustSearch for gating
  - A statement that these DLLs are redistributed as “distributable portions” under the CUDA Toolkit EULA and are intended to be used only by JustSearch (not as a stand-alone SDK)

### Windows runtime prerequisites (practical)

Some `llama-server.exe` builds (including CUDA-capable variants) may depend on the Microsoft Visual C++ runtime.

- If a user sees Windows error `0xC0000135` when starting `llama-server.exe`, it commonly indicates a missing DLL dependency (often the VC++ runtime).
- Distributions (installer and GPU Booster Packs) should document this prerequisite clearly, and enterprise runbooks should include installing the appropriate `vc_redist.x64.exe` where needed.

### ONNX models

JustSearch bundles models for embedding, SPLADE, reranking, citation scoring, NER, and chat. The table below is
**projected from `ai/model-registry.v2.json`** (the license SSOT) by `scripts/codegen/gen-notices.mjs` and guarded
by `check-notices-regen` — it cannot drift from the registry, and editing it by hand is overwritten on regen.

<!-- GENERATED:MODEL_LICENSES:BEGIN — do not edit; run: node scripts/codegen/gen-notices.mjs -->
| Model | License | Source |
|-------|---------|--------|
| Chat model (`chat`) | Apache-2.0 | [huggingface.co/Qwen/Qwen3.5-9B](https://huggingface.co/Qwen/Qwen3.5-9B) |
| Citation scorer (`citation-scorer`) | Apache-2.0 | [huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2) |
| Embedding model (`embedding`) | Apache-2.0 | [huggingface.co/Alibaba-NLP/gte-multilingual-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-base) |
| Named entity recognition (`ner`) | AFL-3.0 | [huggingface.co/Davlan/distilbert-base-multilingual-cased-ner-hrl](https://huggingface.co/Davlan/distilbert-base-multilingual-cased-ner-hrl) |
| Search reranker (`reranker`) | Apache-2.0 | [huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base) |
| Sparse retrieval (SPLADE) (`splade`) | Apache-2.0 | [huggingface.co/opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1](https://huggingface.co/opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1) |
<!-- GENERATED:MODEL_LICENSES:END -->

Most models use Apache 2.0; the NER model uses the **Academic Free License 3.0 (AFL-3.0)**. Both permit redistribution, commercial use, and modification with attribution. Required: include license notice (see `NOTICE-MODELS.txt` shipped alongside model files). AFL-3.0 is OSI-approved and compatible with commercial distribution but is not identical to Apache 2.0 — if license-uniformity matters for a specific distribution channel, NER would be the one to swap.

ONNX weights for cross-encoder models are obtained from the [Xenova](https://huggingface.co/Xenova) HuggingFace namespace which provides pre-converted ONNX exports. Other models are built locally via scripts in `scripts/models/`.

### Compliance status

- **`THIRD_PARTY_NOTICES` — DONE (tempdoc 632).** The repo-root `NOTICE` + `THIRD_PARTY_NOTICES` are now generated
  by `scripts/codegen/gen-notices.mjs` as a projection of **all five shipped trees**: the JVM dependency report
  (jk1), the frontend npm license dump, the **Rust/Cargo dependency dump** (the Tauri desktop shell's statically
  linked crates — `cargo metadata`), the model registry's `license` field, and the native-binary manifests
  (llama.cpp, Tesseract + its 56 bundled DLLs, NVIDIA CUDA/cuDNN). `check-notices-regen` fails the build on drift,
  and its presence check also guards against a strong-copyleft (GPL/AGPL/SSPL) or UNKNOWN license in the npm/Cargo
  trees (which the JVM-only jk1 `checkLicense` gate does not cover). This closes the former 374 G12 gap. (The
  per-component installer notices — `NOTICE-MODELS.txt`, `NOTICE-TESSERACT.txt`, `NOTICE-NVIDIA-CUDA.txt` — continue
  to ship in the bundle.)

### Self-hosting redistribution posture

The plan to host ONNX/SPLADE models on GitHub Releases (`models-v1` release, tempdoc 374 G3-A) changes the redistribution posture from linking to HuggingFace to self-hosting model weights. All current ONNX models use Apache 2.0, which permits redistribution and self-hosting. However, self-hosting means JustSearch takes on direct responsibility for:

- Verifying that redistributed files match the upstream license terms at the time of upload
- Including required attribution/notices alongside hosted assets
- Re-verifying license compliance when models are updated or swapped
- Ensuring download URLs in the model registry always serve the correct, license-compliant artifact

This is a stricter posture than linking to HuggingFace (where the upstream host handles distribution compliance).

### Open questions / follow-ups

- For each default LLM model (`Qwen3.5-9B`, `nomic-embed…`, `mmproj…`), what are the license terms and do they permit:
  - bundling in installer?
  - hosting by JustSearch?
  - commercial use?
  - required attribution/notice?
