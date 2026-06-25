---
title: "justsearch-releases repo audit and rework"
status: active
created: 2026-04-24
updated: 2026-04-24
related: [374, 376, 381, 344]
---

# 409. justsearch-releases repo audit and rework

Audit and proposed rework of the public `eliasjustus/justsearch-releases`
distribution repo — release bodies, tag scheme, supporting repo content
(README / CHANGELOG / THIRD_PARTY_NOTICES / docs/), and license
compliance for redistributed model files.

**Parent / adjacent:**

- Tempdoc 374 (app packaging + distribution): owns the build pipeline
  that *produces* what ends up on a release. This tempdoc owns the
  *publication surface* that consumers see.
- Tempdoc 381 (model distribution architecture): owns the in-app
  manifest (`model-registry.v2.json`) and install flow. This tempdoc
  owns the public release tag those URLs resolve to.

## Scope

**In scope**

- The two existing releases (`v0.1.0-alpha`, `models-v1`) on
  `eliasjustus/justsearch-releases`: titles, bodies, asset naming,
  prerelease / latest flags, release-to-release naming convention.
- Supporting repo content: `README.md`, `CHANGELOG.md`,
  `THIRD_PARTY_NOTICES.txt`, `PRIVACY.md`, `docs/architecture.md`,
  `docs/overview.md`, `docs/roadmap.md`.
- License compliance for the five ONNX/SPLADE models redistributed in
  `models-v1`.
- A minimal `RELEASING.md` runbook.

**Out of scope (explicitly punted by user this session)**

- Rewriting the `v0.1.0-alpha` app release body, SmartScreen copy,
  installer size reconciliation (1.14 GiB shipped vs ADR-0024's 748 MB
  target).
- Codesigning (no certificate available).
- Version-line decision (`0.x` vs `2.x` is unresolved; release wording
  stays loose, no hard version-compat claims).
- Auto-update / Tauri updater manifest.
- SemVer prerelease naming convention (`-alpha.1` vs `-alpha`).
- Migration of model assets off GitHub Releases onto HuggingFace.

## Current state (2026-04-24)

### Releases

| Tag | Title | Assets | Downloads | Flags |
|---|---|---|---|---|
| `v0.1.0-alpha` | "v0.1.0-alpha — First Public Preview" (2026-02-03) | Windows installer 1.14 GiB + `SHA256SUMS.txt` 105 B | 3 / 0 | prerelease, not-latest |
| `models-v1` | "Search Models v1 — Validated Production Set (tempdoc 343)" (2026-03-30) | 23 files, ~2.7 GB ONNX / tokenizers / configs + `sha256sums-models.txt` (label `SHA256SUMS.txt`) | 2–3 per file | prerelease, not-latest |

**Neither release is marked "latest"** — `/releases/latest` is undefined.

### Repo content

```
justsearch-releases/
├── .gitignore
├── CHANGELOG.md        # one entry (0.1.0-alpha); no [Unreleased]
├── LICENSE             # Apache 2.0
├── PRIVACY.md          # clean, internally consistent
├── README.md           # marketing; has a drift defect (see below)
├── THIRD_PARTY_NOTICES.txt  # covers llama.cpp + GGUF models only
├── assets/             # screenshots
└── docs/
    ├── architecture.md  # Java 21+ (should be 25)
    ├── overview.md      # marketing copy, OK
    └── roadmap.md       # marketing copy, OK
```

### How the app consumes these releases

`modules/ui/src/main/resources/ai/model-registry.v2.json` (schema v2)
hard-codes 20 download URLs, all pointing at
`github.com/eliasjustus/justsearch-releases/releases/download/models-v1/...`.

**Practical consequence**: any rename of the `models-v1` tag, any move
off GitHub Releases, or any checksum change on a shipped asset requires
a coordinated app release that updates the manifest. Treat
`models-v1` assets as immutable.

## Defects

### D1 — NER license claim is wrong (compliance defect)

`models-v1` body claims:

> NER: MIT License

Actual license on the HuggingFace model card
[`Davlan/distilbert-base-multilingual-cased-ner-hrl`](https://huggingface.co/Davlan/distilbert-base-multilingual-cased-ner-hrl)
is **AFL-3.0** (Academic Free License 3.0). AFL-3.0 is OSI-approved
and permits commercial use, but has attribution (§9) and
patent-termination clauses absent from MIT. Users relying on the claim
would under-comply.

**Severity:** Reputational, not legal. Redistribution is permitted;
the claim just needs to say AFL-3.0.

### D2 — `THIRD_PARTY_NOTICES.txt` omits every ONNX model

The notices file covers llama.cpp and its direct deps
(cpp-httplib, nlohmann/json, curl, linenoise) and the default GGUF
models (Qwen3-VL-8B, nomic-embed-text-v1.5). It has zero attribution
for the five ONNX models redistributed in `models-v1`:

- `Alibaba-NLP/gte-multilingual-base` (embedding)
- `Alibaba-NLP/gte-multilingual-reranker-base` (reranker)
- `cross-encoder/ms-marco-MiniLM-L-6-v2` (citation)
- `Davlan/distilbert-base-multilingual-cased-ner-hrl` (NER)
- `opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1`
  (SPLADE, modified)

Apache 2.0 §4 and AFL-3.0 §9 both require attribution on
redistribution. The release body names licenses; the NOTICES file
doesn't back that up.

### D3 — SPLADE modification notice missing

The shipped SPLADE ONNX files are a custom PRESPARSE build
(ReLU + `log1p` + TopK sparse-activation ops baked into the graph).
Underlying trained weights are unchanged, but the modification needs
to be stated per Apache 2.0 §4(b). Current body sentence
("custom PRESPARSE build (baked ReLU + log1p + TopK sparse activation
ops)") is accurate but does not frame it as a §4(b) modification
notice.

### D4 — Internal reference leaked into public title

`models-v1` title: "Search Models v1 — Validated Production Set
(tempdoc 343)". `tempdoc 343` is agent-workflow vocabulary.
Unprofessional on a public release page.

### D5 — Embedding model inconsistency across surfaces

| Surface | Claims |
|---|---|
| `README.md` architecture table | `nomic-embed-text-v1.5` (GGUF via llama.cpp) |
| `THIRD_PARTY_NOTICES.txt` default-models section | `nomic-embed-text-v1.5` |
| `models-v1` release + `model-registry.v2.json` | `gte-multilingual-base` (ONNX via ORT) |

User-visible effect: README says the dense embedder is
`nomic-embed-text-v1.5`, but Install AI actually downloads
`gte-multilingual-base`. Either the app was mid-migration
(nomic GGUF → gte ONNX) and public-facing docs weren't updated, or
nomic is used elsewhere (chat-side embeddings?) and the README mis-
attributed it to the search path.

### D6 — Contact email inconsistency

- `v0.1.0-alpha` body: `<redacted>`
- `README.md`, `PRIVACY.md`: `<redacted>`
- Canonical (confirmed by user 2026-04-24): **`<redacted>`**

### D7 — SHA filename vs verification command mismatch in `models-v1`

Release body instructs `sha256sum -c SHA256SUMS.txt`. Actual asset
filename is `sha256sums-models.txt`; `SHA256SUMS.txt` is only the
asset *label*. A user copying the command gets "No such file or
directory".

### D8 — Doc drift: `docs/architecture.md` claims Java 21+

Actual runtime per `gradle.properties` + CI workflows is Java 25.

### D9 — README roadmap ambiguity ("open source release … Apache 2.0")

`LICENSE` is already Apache 2.0 and the repo already publishes binaries
under it. The roadmap bullet "Open source release under Apache 2.0
with reproducible quality benchmarks" reads as if the project is not
yet OSS. What's actually pending is publication of the private source
repo.

### D10 — Asset checksum naming inconsistent across releases

- `v0.1.0-alpha`: `SHA256SUMS.txt` (105 B, one entry)
- `models-v1`: `sha256sums-models.txt` (1860 B, label `SHA256SUMS.txt`)

### D11 — Unused per-model manifest files in `models-v1`

`embed-model_manifest.json` (135 B) and `splade-model_manifest.json`
(90 B) are in the release but are not referenced by
`model-registry.v2.json`. Either legacy, superseded by v2, or used by
an older code path that should be audited.

### D12 — nDCG@10 benchmarks in README have no provenance

`README.md` presents a nDCG@10 table with concrete numbers
(0.736 SciFact, 0.830 Enron, 0.925 CourtListener, 0.734 MIRACL-de,
0.706 MIRACL-fr, 0.691 MIRACL-zh). No evaluation date, no link to the
`jseval` run artifact, no dataset/judgment version. Public benchmark
claims without rerunnable provenance are disputable.

### D13 — No release marked "latest"

Both releases are prereleases with `isLatest=false`. `/releases/latest`
and `/releases/latest/download/<asset>` URLs do not resolve.
Third-party integrations (winget, scoop, auto-detect scripts) that
assume "latest" will fail silently.

### Observations (out-of-scope shipping bugs logged, not fixed here)

- **O1** — `model-registry.v2.json` has literal placeholders for the
  FP32 embedding variant: `"sha256": "REPLACE_WITH_FP32_SHA256"`,
  `"downloadUrl": "REPLACE_WITH_FP32_URL"`. The FP32 file is not in the
  `models-v1` release at all. CPU-only users (no CUDA) cannot download
  the embedding model. Flag in `docs/observations.md`.
- **O2** — Tag / schema version collision: app manifest schema is `v2`,
  public release tag is `models-v1`. A schema-v2 manifest pointing at
  "v1" assets is confusing. Future release tags should be date-based
  (`models-2026-04`) or schema-aligned to avoid this.

## Proposed fixes (drafts)

All drafts staged here for review. Nothing applied to the public repo
until the user approves.

### A — `models-v1` release body rewrite

```markdown
# Search Models v1 — Validated Production Set

Pre-trained ONNX and SPLADE models used by JustSearch for search
indexing and retrieval.

> **You typically don't download these manually.** JustSearch's
> "Install AI" flow fetches these automatically into
> `%APPDATA%\io.justsearch.shell\models\`. This page exists for
> transparency, manual install, and license attribution.

## Compatibility

Compatible with the current JustSearch desktop app. See
[`model-registry.v2.json`](https://github.com/eliasjustus/justsearch-releases/blob/main/docs/model-registry.v2.json)
for the pinned SHA-256 values the app verifies against.

## Included models (21 files, ~2.7 GB)

| Model | Purpose | Source | License |
|---|---|---|---|
| `gte-multilingual-base` (FP16) | Dense vector search, 70+ languages | [Alibaba-NLP/gte-multilingual-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-base) | Apache 2.0 |
| `gte-multilingual-reranker-base` (FP32) | Cross-encoder reranking, 70+ languages | [Alibaba-NLP/gte-multilingual-reranker-base](https://huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base) | Apache 2.0 |
| `ms-marco-MiniLM-L-6-v2` (INT8) | Citation scoring / source attribution | [cross-encoder/ms-marco-MiniLM-L-6-v2](https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2) | Apache 2.0 |
| `distilbert-base-multilingual-cased-ner-hrl` (INT8 + FP16) | Named-entity extraction (PER/ORG/LOC), 10 languages | [Davlan/distilbert-base-multilingual-cased-ner-hrl](https://huggingface.co/Davlan/distilbert-base-multilingual-cased-ner-hrl) | **AFL-3.0** |
| `opensearch-neural-sparse-encoding-multilingual-v1` (FP32 + FP16, **modified**) | Learned sparse retrieval (SPLADE), 15 languages | [opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1](https://huggingface.co/opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1) | Apache 2.0 |

### Modifications to the SPLADE model

The shipped SPLADE ONNX files are a **custom PRESPARSE build**:
ReLU + `log1p` + TopK sparse-activation ops baked into the graph for
runtime efficiency. The underlying weights are unchanged. Per
Apache-2.0 §4(b), this constitutes a modification; full attribution
and the modification notice are in
[`THIRD_PARTY_NOTICES.txt`](https://github.com/eliasjustus/justsearch-releases/blob/main/THIRD_PARTY_NOTICES.txt).

## Verification

```
sha256sum -c sha256sums-models.txt
```

Or on Windows:

```
certutil -hashfile <filename> SHA256
```

Compare against [`sha256sums-models.txt`](https://github.com/eliasjustus/justsearch-releases/releases/download/models-v1/sha256sums-models.txt).

## Attribution

See
[`THIRD_PARTY_NOTICES.txt`](https://github.com/eliasjustus/justsearch-releases/blob/main/THIRD_PARTY_NOTICES.txt)
for full license texts and required notices for each model.
```

Changes vs. current body:

- Title drops `(tempdoc 343)`.
- Top banner: users typically don't download manually.
- NER license corrected to AFL-3.0.
- Each model links to its HF source card.
- SPLADE modification framed explicitly as Apache 2.0 §4(b) notice.
- Verify command matches the actual filename.
- Cross-links `THIRD_PARTY_NOTICES.txt`.

### B — `THIRD_PARTY_NOTICES.txt` additions

Append after the existing "AI Models (downloaded separately via
'Install AI')" block:

```
================================================================================
Search Models (ONNX, distributed via the models-v1 release)
================================================================================

The following ONNX models are hosted at
https://github.com/eliasjustus/justsearch-releases/releases/tag/models-v1
and downloaded by JustSearch's search indexing pipeline.

--------------------------------------------------------------------------------
gte-multilingual-base (dense embedding)
--------------------------------------------------------------------------------
Source: https://huggingface.co/Alibaba-NLP/gte-multilingual-base
License: Apache License 2.0

--------------------------------------------------------------------------------
gte-multilingual-reranker-base (cross-encoder reranker)
--------------------------------------------------------------------------------
Source: https://huggingface.co/Alibaba-NLP/gte-multilingual-reranker-base
License: Apache License 2.0

--------------------------------------------------------------------------------
ms-marco-MiniLM-L-6-v2 (citation scorer)
--------------------------------------------------------------------------------
Source: https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2
License: Apache License 2.0

--------------------------------------------------------------------------------
distilbert-base-multilingual-cased-ner-hrl (named entity recognition)
--------------------------------------------------------------------------------
Source: https://huggingface.co/Davlan/distilbert-base-multilingual-cased-ner-hrl
License: Academic Free License v3.0 (AFL-3.0)
  https://opensource.org/license/afl-3-0-php
Base model: distilbert-base-multilingual-cased (Apache License 2.0)

--------------------------------------------------------------------------------
opensearch-neural-sparse-encoding-multilingual-v1 (SPLADE sparse retrieval)
--------------------------------------------------------------------------------
Source: https://huggingface.co/opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1
License: Apache License 2.0
Copyright OpenSearch Contributors
See https://github.com/opensearch-project/neural-search/blob/main/NOTICE

Modifications: The ONNX files distributed in the models-v1 release are
a custom PRESPARSE build (ReLU + log1p + TopK sparse-activation ops
baked into the model graph). The underlying trained weights are
unchanged. This modification is noted per Apache License 2.0 §4(b).

================================================================================
Apache License 2.0 full text
================================================================================

[optional: paste the full Apache 2.0 text here if going for strict
compliance; otherwise keep the external links above and document the
link-based convention]
```

**Open question (Q2 below)**: paste full Apache 2.0 text here
(~10 KB), ship `LICENSE-APACHE-2.0` as a separate file, or keep the
current link-only style.

### C — `README.md` surgical corrections

1. Architecture model table — swap embedder row:

   ```diff
   - | nomic-embed-text-v1.5 | Dense embeddings (768-dim) | GGUF via llama.cpp |
   + | gte-multilingual-base | Dense embeddings, 70+ languages | ONNX Runtime |
   ```

2. nDCG@10 table footnote:

   ```
   _Evaluated on [DATE] using the jseval pipeline
   (ingest → enrich → search → score) with standard BEIR/MIRACL
   relevance judgments. Raw run artifacts available on request._
   ```

   Needs eval date from user (Q4 below).

3. Roadmap wording:

   ```diff
   - Open source release under Apache 2.0 with reproducible quality benchmarks
   + Publication of the source repository (this repo hosts binaries and documentation; core sources are currently private)
   ```

4. Verify the architecture table also matches the current runtime —
   the releases-repo `README.md` says "Java 25" nowhere (it currently
   says nothing); `docs/architecture.md` says "Java 21+". See patch E.

### D — `CHANGELOG.md` expansion

Add an `[Unreleased]` section and a convention note:

```markdown
# Changelog

All notable changes to JustSearch will be documented in this file.

The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This changelog tracks **app releases** only. Model asset releases
(tagged `models-*`) are documented in the release notes on their tag
pages.

## [Unreleased]

_Work in progress._

## [0.1.0-alpha] - 2026-02-03
[... existing content unchanged ...]
```

### E — `docs/architecture.md` Java version fix

Single-line diff:

```diff
- | Backend | Java 21+ | Strong typing, mature ecosystem, Panama FFM for native interop |
+ | Backend | Java 25 | Strong typing, mature ecosystem, Panama FFM for native interop |
```

### F — `models-v1` SHA filename convention

Two options:

- **F1**: Keep filename `sha256sums-models.txt`; drop the
  `SHA256SUMS.txt` label. Fixes the internal mismatch without breaking
  the existing download URL.
- **F2**: Re-upload the file as `SHA256SUMS.txt` to match the
  convention used by `v0.1.0-alpha`. Breaks any existing link to the
  lowercase filename.

**Default recommendation**: F1.

### G — "Latest" release decision

Both existing releases are prereleases. Options:

- **G1** (~~default~~ **invalid**): ~~Mark `v0.1.0-alpha` as latest via
  API (`make_latest=true` on a prerelease)~~. **GitHub API refuses
  this**: HTTP 422 "Latest release cannot be draft or prerelease."
  Confirmed 2026-04-24 against both `gh release edit --latest` and
  raw `PATCH /releases/{id} -f make_latest=true`. Documented in
  https://docs.github.com/rest/releases/releases — "Drafts and
  prereleases cannot be set as latest."
- **G2** (**applied**): Leave both as not-latest. `/releases/latest`
  remains undefined until a non-prerelease app release is published.
  Third-party consumers that assume a "latest" exist (winget, scoop,
  auto-detect scripts) will not resolve.
- **G3**: Mark `models-v1` as latest. Also invalid for the same
  reason (also prerelease), and user-facing nonsensical even if it
  were possible.
- **G4** (not taken): Flip `v0.1.0-alpha` off prerelease to make it
  eligible for "latest". Rejected — the release is an alpha and
  misrepresenting that to the release ecosystem is worse than
  `/releases/latest` being undefined until a real stable ships.

**Resolution**: G2. `/releases/latest` stays undefined until a
non-prerelease app release exists. When the version line leaves alpha
(see punted decisions in "Scope"), marking latest becomes a
one-command operation.

### H — `RELEASING.md` runbook (new file in `justsearch-releases`)

New file, bullet runbook:

1. Bump `version=` in main repo `gradle.properties`.
2. Run `scripts/ci/sync-version.ps1` (propagates to `tauri.conf.json`,
   shell `package.json`, `Cargo.toml`).
3. Local gate: `./gradlew.bat build` then `.\scripts\gate.ps1`.
4. Build installer: `./scripts/ci/package-installer-win.ps1` (no
   `-Release` flag until codesigning is available — the `-Release`
   flag requires `JUSTSEARCH_CODESIGN_*` env vars).
5. Capture SHA-256 of the produced `*-setup.exe`.
6. In `justsearch-releases` repo: add entry to `CHANGELOG.md` under a
   new version heading (move content from `[Unreleased]`).
7. Create a GitHub release against the new tag, attach installer +
   combined `SHA256SUMS.txt` (one sums file covering every asset in
   the release).
8. Mark as prerelease until the version leaves alpha.
9. Confirm `make_latest` state is correct.
10. Cross-check that `THIRD_PARTY_NOTICES.txt` still covers everything
    bundled.

## Investigation results (resolves Q1 and Q6)

### Q1 — NER license claim audit (main repo)

The wrong-license claim appears in **three places**:

1. **`modules/ui/build.gradle.kts:360`** — the `generateOnnxNotice`
   Gradle task writes `NOTICE-MODELS.txt` into the build output with
   **"License: MIT License"** for NER. This file is bundled into
   every installer at `resources/headless/models/NOTICE-MODELS.txt`
   and user-visible (read by anyone inspecting the installed app's
   model directory). **Highest-impact surface.**
2. **`docs/reference/legal/ai-runtime-and-model-redistribution.md:137`**:
   lists NER as **"Apache 2.0"** in the ONNX models table. Also
   line 139 makes a blanket claim: "All models use Apache 2.0
   licenses, which permit redistribution...". Both statements are
   false — the actual license is AFL-3.0.
3. **`models-v1` release body**: "NER: MIT License".

Note that (1) and (3) make the same MIT claim; (2) makes a different
(also wrong) Apache 2.0 claim. The two distinct wrong values suggest
independent guesses rather than one propagated mistake.

**Resolution — out of scope for this tempdoc**: (1) and (2) live in
the main private repo and are outside the "update releases github"
task scope. They're logged in `docs/observations.md` for follow-up
and documented here so a future maintainer has the full picture. (3)
is the one this tempdoc fixes via Patch A.

**NOTICE-MODELS.txt existence clarification**: the file *does* exist
at build time (generated by `generateOnnxNotice`, staged into
`resources/headless/models/NOTICE-MODELS.txt` by `stageOnnxModels`,
and the installer smoke test at `build.gradle.kts:1351-1353` fails
if it's missing from the bundle). It's not a source file — it's
build-generated per-release. That means any wrong claim in the build
script ships immediately; there's no review gate between source and
installer.

### Q6 — Embedding model resolution

Per `docs/reference/model-inventory.md`:

- **`gte-multilingual-base`** (FP16 GPU / FP32 CPU): **current default**
  (tempdoc 358). `EmbeddingOnnxModelDiscovery` tries this first.
  nDCG@10 0.7132 on SciFact, 70+ languages.
- **`EmbeddingGemma-300M`** (Q4 GPU / INT8 CPU): legacy backup,
  discovered only when `gte-multilingual-base/` is absent.
- **`nomic-embed-text-v1.5`** (INT8 ONNX): *fallback*
  (tempdoc 268, superseded by tempdoc 312).
- **`nomic-embed-text-v1.5.Q4_K_M.gguf`**: still pinned as a
  *packaged GGUF fallback* in `model-registry.v1.json`.

**Implication for the patch set**:

- Draft C item 1 (README architecture-table swap to
  `gte-multilingual-base`) is correct and should proceed.
- `THIRD_PARTY_NOTICES.txt`'s existing nomic GGUF section is **not
  wrong** (the GGUF variant is still pinned as a fallback), but the
  "AI Models (downloaded separately via 'Install AI')" block needs to
  clarify that `gte-multilingual-base` is the packaged default and
  nomic is a fallback. Add a patch (B2 below).

## Open questions (blocking apply)

1. **Q1**: Does any other surface (installer-internal NOTICE files,
   main repo docs, marketing copy, in-app license panel) repeat the
   "NER: MIT" claim? If so, those need the same correction. My drafts
   only cover the public releases repo.
2. **Q2**: For `THIRD_PARTY_NOTICES.txt`, paste the full Apache 2.0
   text verbatim (+10 KB), ship `LICENSE-APACHE-2.0` as a separate
   file, or keep the current link-only style?
3. **Q3**: F1 (keep `sha256sums-models.txt` filename, drop label) or
   F2 (rename asset to `SHA256SUMS.txt`, breaks existing URL)?
4. **Q4**: nDCG@10 footnote — give an eval date + artifact link, or
   leave as "available on request"?
5. **Q5**: Application sequence — direct commit of D/E/H (CHANGELOG,
   architecture.md, RELEASING.md) and separate release-body edits via
   API, or stage everything in one PR against `main` of
   `justsearch-releases`?
6. **Q6**: Do I resolve D5 (embedding inconsistency) by treating
   `gte-multilingual-base` as the single truth everywhere, or is
   `nomic-embed-text-v1.5` used in a code path I haven't seen? If
   nomic is genuinely gone, `THIRD_PARTY_NOTICES.txt`'s "AI Models
   (downloaded separately)" section also needs updating.
7. **Q7**: D13 — mark `v0.1.0-alpha` as `make_latest=true` (G1)?

## Application order (once unblocked)

Staged by risk. Lowest-risk first.

1. **Patch E** — single-line Java version fix in
   `docs/architecture.md`. Trivial, no dependencies.
2. **Patch D** — `CHANGELOG.md` adds `[Unreleased]` + convention note.
3. **Patch C** — `README.md` three surgical edits.
4. **Patch B** — `THIRD_PARTY_NOTICES.txt` ONNX models section.
   Depends on Q2 answer.
5. **Patch H** — new `RELEASING.md`.
6. **Patch A** — rewrite `models-v1` release body via API. Depends on
   B being merged (body cross-links NOTICES).
7. **Patch F** — asset filename choice (F1 or F2).
8. **Patch G** — `make_latest` flag on `v0.1.0-alpha`.

## Notes for future tempdocs

When the installer release rework is unblocked (codesigning available,
version line settled, installer size investigated), a follow-up
tempdoc should cover:

- `v0.1.0-alpha` body rewrite (SmartScreen copy, Install AI step,
  log paths, uninstall, offline-caveat).
- SemVer prerelease naming convention decision.
- Whether to retag `v0.1.0-alpha` or start the next release at
  `v0.1.0-alpha.1` / `v0.1.0-alpha.2`.
- Installer size reconciliation with ADR-0024.
- Tauri auto-updater wiring.

## References

- Tempdoc 374 — app packaging and distribution (installer build side)
- Tempdoc 376 — CPU vs GPU inference strategy (references
  `reranker-model_fp16.onnx` URL)
- Tempdoc 381 — model distribution architecture (manifest,
  hardware-aware downloads)
- Tempdoc 344 — funding opportunities (references
  `github.com/eliasjustus/justsearch-releases` as project surface)
- ADR-0024 — App Packaging: NSIS, Per-User Install, Download-on-Demand
- `modules/ui/src/main/resources/ai/model-registry.v2.json` — pinned
  download URLs consumed by Install AI
