---
title: "Install modes and model-pack decomposition: add an install-intent axis orthogonal to hardware, with runtime mode as a realized-capability projection (Full Desktop / Headless Runtime / MCP Lite as presets)"
type: tempdocs
status: "in-progress — substrate implemented & live-verified 2026-07-02 (feat(657)); founder decisions, 654/655-owned items, and the follow-ups in §Implementation status remain"
created: 2026-06-28
updated: 2026-07-02
category: packaging / installer / model-distribution / developer-experience
related:
  - 654-local-runtime-contract-and-product-center
  - 656-five-minute-agent-runtime-onramp
  - 655-mcp-conformance-and-capability-policy
  - 650-go-public-capability-descriptor-truthfulness
  - 381-model-distribution-architecture
  - 631-go-public-publish-machinery
  - 634-go-public-cutover-transition
  - docs/decisions/0024-app-packaging-nsis-per-user-download.md
  - docs/explanation/23-runtime-manifest.md
  - docs/reference/model-inventory.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 657 - Install modes and model-pack decomposition

## Implementation status (2026-07-02 — substrate shipped)

The design below (D1–D8) is **implemented and live-verified**, landed as `feat(657)` (install-intent
axis, model-pack tiers, runtime mode, honest weight breakdown, headless launcher). Everything after
this section is the design/investigation record it was built from; this section is the current truth.

### What shipped

- **Tiers (D2):** `CapabilityTier` {`retrieval-core`, `retrieval-enrichment`, `llm`, `runtime`} +
  a nullable `tier` field on `ModelPackage` (registry `model-registry.v2.json`, both copies, + loader).
  `vision`/`mmproj` rides with `llm` (it is a supporting file of the `chat` package — not a separate
  package), exactly as D2 specifies.
- **Intent (D1/D3):** `InstallIntent` {`FULL_DESKTOP` (default), `HEADLESS`, `MCP_LITE`} chosen at
  launch via `-Djustsearch.mode` / `JUSTSEARCH_MODE` (`EnvRegistry.MODE`). `InstallPlanner.plan(...)`
  gained an intent parameter with an intent-gated skip branch parallel to the VRAM gate; MCP Lite skips
  the `llm` + `runtime` tiers. Persisted in `InstallContract.installIntent`. Existing overloads default
  to `FULL_DESKTOP`, so pre-existing installs are unchanged.
- **Runtime mode (D4):** a projected `ModeInfo{intent, realized}` on `RuntimeManifest`
  (`publishMode` in `RuntimeManifestPublisher`, wired via `RuntimeManifestListenerWiring`). `realized`
  is coarse — `full` / `retrieval-only` / `degraded` — derived from `WorkerCapability` +
  `InferenceCapability`, per the closure rule (schema stays v1; nullable; closure-check unaffected).
- **Honest weight (D5):** new side-effect-free `GET /api/ai/install/plan-preview` (`InstallPlanPreview`,
  reuses the *pure* `InstallPlanner`) returning per-tier `{totalBytes, downloadBytes, includedByIntent}`;
  the Brain surface Models panel renders it, with intent-excluded tiers dimmed and labelled
  ("not in <mode> mode"). Also fixed a pre-existing FE bug (per-package name read `p.id` instead of
  `packageId`, so the column always showed a fallback).
- **Headless launcher (D6, user-requested):** `packaging/headless/justsearch-headless.{cmd,ps1}` (staged
  into the sidecar bundle), a `runHeadless -Pmode=<mode>` dev passthrough, and
  `docs/how-to/headless-runtime.md`.
- **Drift fix:** ADR-0024 reconciled (models are bundled today; only the GGUF LLM + CUDA runtime
  download on demand). `JUSTSEARCH_MODE` documented in `environment-variables.md`; plan-preview + the
  manifest `mode` field documented in `api-contract-map.md`.

### Verification evidence

- **Unit:** added tests — MCP-Lite tier-gating + untagged-package pass-through (`InstallPlannerTest`),
  `everyPackageDeclaresATier` (`ModelRegistryLoaderTest`), intent round-trip + back-compat ctor
  (`InstallContractIOTest`), manifest `mode` compat/round-trip (`RuntimeManifestSchemaCompatibilityTest`).
  All changed backend modules green (`configuration`, `app-api`, `app-services`, `ui`).
- **Live backend** (launched `-Djustsearch.mode=mcp-lite`): `GET /api/runtime/manifest` →
  `mode = {intent: "mcp-lite", realized: "retrieval-only"}`; `GET /api/ai/install/plan-preview` →
  `llm` and `runtime` tiers `includedByIntent:false, downloadBytes:0`; total download ≈ 2.1 GB
  (retrieval only) vs the full ≈ 10.7 GB.
- **Real UI (browser):** Brain surface → Models accordion renders the four tier rows with the two LLM
  tiers dimmed and marked "not in mcp-lite mode"; no console errors.

### Deferred by design (NOT bugs — owned elsewhere / need a decision)

- **MCP-Lite's exposed MCP tool subset** → tempdoc 655 (this note only sets the footprint).
- **Blessing Headless/MCP-Lite as official product shapes** (marketing/README) → tempdoc 654.
- **Populating the low-download rung** with a small multilingual dense model (D7) → founder decision
  (§Open decisions #2); substrate already supports it as a `retrieval-core` size/quality variant.
- **Bundle-vs-download policy** for the ~3.5 GB ONNX (§Open decisions #1) → founder decision;
  `stageOnnxModels` is the seam, untouched here.
- **Splitting `mmproj` into its own package** so `vision` becomes independently selectable → only if
  a vision-less-but-chat mode is ever wanted.

### Follow-ups / unverified assumptions (do not forget)

1. **Runtime enforcement gap (design nuance).** Intent gates *download* + *mode metadata*, not *runtime
   subsystem activation*. So MCP-Lite yields "no LLM" by never downloading it (fresh install) + honest
   `realized` reporting — but a *full* install relaunched with `mode=mcp-lite` would still start a
   present LLM (and honestly report `realized:full`). This matches the design's explicit mechanism
   (D4 embraces intent≠realized divergence), but if the stricter product-shape reading is wanted,
   wire intent → the LLM autostart flag. **Open question for the owner.**
2. **`realized:full` path unobserved live.** Only `retrieval-only` was exercised (LLM offline at
   startup); the `full` transition (LLM ready) is simple projection logic but was not observed end-to-end.
3. **Bundle-staged launcher unexercised.** The `bundleSidecar` Copy of the launcher scripts and their
   in-bundle path resolution (`runtime\bin\java.exe`, `ui-headless.jar`, `lib\*`) were not run from an
   actual built NSIS installer — only the mode *plumbing* was validated via `runHeadless -Pmode`.
   Build the installer and confirm the launcher lands and runs before relying on it.
4. **`InstallIntent="custom"` label (D4) not implemented.** Intent is a first-class chosen value (three
   presets + default); there is no "custom" state derived from arbitrary per-subsystem toggle combos.
   Minor; revisit only if free-form toggle installs need a distinct label.
5. **Pre-existing issues found this pass (logged to the observations inbox; NOT caused here):**
   FE `npm run typecheck` fails repo-wide on `TS5101` (tsconfig `baseUrl` deprecation, no
   `ignoreDeprecations`) — so my FE was verified type-clean by the (empty) error list for my files,
   not a green typecheck; and two ui-web gates are red on base (`check-theme-token-closure` ghost tokens
   in `RecentsMenu.ts`; `check-accent-as-text` in `ActionLedgerView.ts`) — both files untouched here.
6. **`model-inventory.md` Open Decision #1 is stale** (ONNX embedding+SPLADE are already registry
   packages) — logged, not fixed here.

## Purpose

JustSearch's full local capability has a real runtime cost. The reports consistently argue that this
cost is not fatal, but it needs clearer product shapes: Full Desktop for the complete private assistant
experience, Headless Runtime for local service use, and MCP Lite for agent developers who need fast
retrieval before they commit to the full stack.

This tempdoc designs install/runtime modes and model-pack decomposition without weakening the
local-first promise. The goal is to make first-run weight explicit, optional where possible, and
aligned with actual capability tiers.

## Boundary

This is not a request to rip out models or weaken the retrieval stack. It is a packaging and product
mode design. It maps existing model/runtime dependencies, decides which capabilities are required for
each mode, and identifies where the current installer or setup flow makes optional weight look
mandatory.

Coordinate with installer ownership before implementation. Packaging changes touch Windows installer,
model distribution, runtime config, and public docs.

## Prior owners to read first

- **381-model-distribution-architecture** (done) — the shipped registry-v2 / `InstallPlanner` /
  `DownloadProfile` / `HardwareProfile` / `InstallContract` architecture this design extends.
- ADR-0024 for the installer and download-on-demand architecture (**note: stale — see §Drift**).
- `docs/reference/model-inventory.md` for model purposes, sizes, and licensing.
- `go-public-publish-machinery` / `go-public-cutover-transition` for release/public-repo constraints.
- `local-runtime-contract-and-product-center` (654) — owns whether Headless/MCP Lite are *official
  product shapes*; this note supplies their packaging substrate and defers the product-blessing there.
- `mcp-conformance-and-capability-policy` (655) — owns which MCP tools a lite surface exposes.
- `five-minute-agent-runtime-onramp` (656) — owns the runnable onramp/demo that introduces a mode.

---

## Current state — verified ground truth (2026-07-02)

An investigation pass established what already exists. The short version: **the retrieval-vs-LLM
split the tempdoc wants is already physically realized at the bundle boundary; it is just not exposed
as a product concept, and it can only be reached by accident of hardware, never by choice.**

1. **The installer already bundles retrieval; only the LLM is deferred.**
   `includeOnnxModels` **defaults `true`** (`modules/ui/build.gradle.kts:384`); `stageOnnxModels`
   bakes the full ONNX retrieval stack (embedding FP16, SPLADE FP32+FP16, reranker FP32+FP16, NER
   INT8+FP16, citation INT8 ≈ **3.5 GB**) plus the CPU llama-server into the NSIS bundle. Only the
   GGUF chat pack (`Qwen_Qwen3.5-9B-Q4_K_M.gguf` 5.89 GB + `mmproj-F16.gguf` 918 MB ≈ **6.4 GB**) and
   the `cuda-runtime` DLL zips (≈ **1.8 GB**) download on demand via "Install AI", VRAM-gated to a GPU
   with ≥ 7.5 GB. So a fresh install does full hybrid neural retrieval offline with zero downloads;
   RAG/chat/agent/VDU require the heavy download. **ADR-0024's "~748 MB / no models bundled / all
   8.5 GB post-install" is false on `main`.**

2. **The only tiering that exists is hardware-driven and auto-selected.**
   `DownloadProfile` {`GPU_FULL`, `GPU_LITE`, `CPU`} (`DownloadProfile.java`) is derived from
   `HardwareProfile.downloadProfile()`. Notably `GPU_LITE` **already means "GPU-accelerated search,
   no chat"** — a retrieval-only shape — but it is *forced* by insufficient VRAM, never chosen. A
   user with a large GPU who only wants an MCP retrieval backend has **no way to say "skip the
   6.4 GB LLM."**

3. **381's architecture is a pure, well-factored pipeline.** `InstallPlanner.plan(registry, hardware,
   modelsDir)` is a pure function; `ModelPackage` models a package with per-EP variants + VRAM gate;
   `InstallContract` (`install-contract.v2.json`) is the bill-of-materials recording hardware profile,
   download profile, and per-model selected variant / `skipped` state; the plan already carries
   `totalBytes` and is presented to the user before download. Per-subsystem enable flags
   (`JUSTSEARCH_AI_EMBED_ENABLED`, `JUSTSEARCH_SPLADE_ENABLED`, NER/reranker `*_ENABLED`, LLM
   `justsearch.ai.autostart.enabled`) compose freely; `SearchPipelinePresets` is prior art for
   "a named preset that sets a bundle of toggles."

4. **Runtime already reports realized capability, grouped.** The runtime manifest
   (`docs/explanation/23-runtime-manifest.md`) exposes `lifecycle`, `ai.phase`/`ai.required`,
   `worker.state`, `reachability` (incl. `mcp`) across five transports, under a **closure rule**
   (new runtime facts must be manifest fields, CI-enforced). The readiness envelope
   (`StatusLifecycleHandler`) rolls per-dimension readiness into **composites** via `dim.composite()`
   grouping with *no hardcoded lists* — today `retrieval` and `aiFeatures`. Degradation is
   reason-coded (`INFERENCE_OFFLINE`, `INDEX_DENSE_UNAVAILABLE`, `NO_EMBEDDING_MODEL`).

## The real problem

`DownloadProfile` collapses two orthogonal concerns into one enum:

| Axis | Question | Today |
|------|----------|-------|
| **Hardware capability** | *Can this box run GGUF / FP16?* | `DownloadProfile` (auto-detected) |
| **Install intent** | *Do I **want** chat, or just retrieval?* | **inferred from hardware** — no VRAM ⇒ no chat |

The three product shapes the tempdoc names (Full Desktop / Headless Runtime / MCP Lite) are points on
the **intent** axis, which does not exist as a first-class concept. Everything else the tempdoc asks
for — model-pack decomposition, active-mode metadata, honest first-run weight — follows once intent is
separated from hardware and the decomposition is *labelled* rather than merely physical.

## Design

The design is an **extension of 381's pipeline, not a replacement.** 381 made inclusion a pure
function of one axis (hardware); this adds the orthogonal intent axis it never anticipated, persists
the choice in the existing bill-of-materials, and projects the *realized* mode onto the existing
runtime manifest. Nothing is rewritten.

### D1 — Two axes; intent is a separate input, never a fourth `DownloadProfile`

Keep `DownloadProfile` as the pure hardware axis (it selects the *variant* within a wanted pack). Add
**install intent** as a second, independent input. Adding intent as a fourth enum value would re-fuse
the two axes — the exact conflation being removed — so intent stays orthogonal, and the planner
becomes a pure function of *both*: `plan(registry, hardware, intent, installedState)`. Hardware answers
"which precision," intent answers "which capability tiers at all."

### D2 — Capability tiers on model packages

Give each `ModelPackage` a coarse **capability tier** tag. The problem has exactly these groupings, so
this is the minimal structure it requires — not a speculative taxonomy:

- `retrieval-core` — embedding (required for any neural/hybrid search)
- `retrieval-enrichment` — SPLADE, reranker, NER, citation (quality boosters; retrieval degrades
  gracefully without them)
- `llm` — chat GGUF (RAG/chat/agent/extract/summarize)
- `vision` — the VDU projector (`mmproj`). **Not independently selectable today:** `mmproj` is a
  *supporting file* of the `chat` package, not its own package (§C/U3), so it rides with the `llm`
  tier unless the registry splits it into a package. Treat vision as part of `llm` until/unless that
  split is wanted.
- `runtime` — `cuda-runtime` DLLs (hardware-orthogonal support, not a capability)

Intent selects a **set of wanted tiers**; the planner includes a package iff its tier is wanted *and*
hardware permits its variant. This mirrors the runtime side's existing `retrieval` / `aiFeatures`
readiness composites — the install-time tier tag and the runtime-time composite are two ends of the
same capability grouping and should use aligned names.

### D3 — Intent is a chosen input, persisted in the install contract

Intent is the **one new producer-owned primitive**. Persist it in the existing `InstallContract`
bill-of-materials (which already records hardware + download profile + per-model `skipped` state). This
keeps a single authority for "what this install is": the contract already answers "what was installed";
it gains "what it was installed *for*." No new sibling file, no new env mechanism.

### D4 — Runtime **mode** is a realized-capability projection, not a stored label

The mode a caller reads at runtime must be **derived from what is actually loaded**, with intent as the
only stored input. A "Full Desktop" install whose LLM failed to load must not advertise chat. So expose
mode as a **projected field on `RuntimeManifest`** (per the closure rule — *not* on `/infra/capabilities`,
which is a plugin contract-version handshake, a different surface). The field carries:

- `intent` — the chosen named preset (producer-owned primitive, set at launch as a `-Djustsearch.mode=`
  system property / env, exactly like the other launch config the shell already passes — §C/U2;
  `"custom"` when the toggles don't match a named preset).
- a **realized rollup** — projected from the sources the manifest publisher **already listens to**:
  `WorkerCapability` (retrieval serving) + `InferenceCapability` / `AiInfo.phase` (LLM). This is what
  callers branch on.

Normally `realized` equals `intent`; when they diverge (degradation), the manifest tells the honest
truth. This is the same discipline the reason codes already enforce (`INDEX_DENSE_UNAVAILABLE` stops the
search banner over-claiming "fully semantic").

**Scoping correction (from §C/U1):** the publisher listens to `WorkerCapability` + `InferenceCapability`,
**not** to the `StatusLifecycleHandler` `retrieval`/`aiFeatures` readiness composites. So the `mode`
rollup can report LLM-availability and worker-ready *for free*, but **fine-grained "retrieval dense vs
BM25-only" is not manifest-reachable without wiring a new embedding-readiness listener to the publisher.**
Keep the manifest `mode` coarse (llm on/off, worker ready) and leave dense-depth in the readiness
envelope where it already lives — unless a caller genuinely needs dense-depth in the manifest, in which
case the new listener is the (small, known) cost.

### D5 — Honest first-run weight: break the plan down by tier

`InstallPlan` already computes `totalBytes` and is shown before download. Extend the plan *presentation*
(not a new mechanism) to **group downloads by capability tier**, so the ~6.4 GB `llm` tier renders as a
distinct, visibly-optional line rather than being folded into one "several GB" number. This directly
answers "explain the first-run download without hiding its size": the size isn't hidden, its
*decomposition* is made legible, and the heavy tier is shown as the optional one it already is.

### D6 — The three shapes are named presets over the axes, not rigid modes

Model the compositional axes (wanted tiers × shell-attached), then name three useful presets. This
matches the "mode as named preset" prior art (`SearchPipelinePresets`) and avoids a rigid enum that
needs special-casing everywhere:

| Preset | Wanted tiers | Shell | RAG/chat | Realized envelope |
|--------|--------------|-------|----------|-------------------|
| **Full Desktop** | retrieval-core + enrichment + llm (+ vision) | attached | yes | full assistant |
| **Headless Runtime** | retrieval-core + enrichment (+ llm, optional) | none | optional | local service |
| **MCP Lite** | retrieval-core (+ enrichment) | none | no | retrieval for agents |

657 defines this substrate and the three presets; **whether these are blessed as official product
shapes is 654's call**, and **which MCP tools MCP Lite exposes is 655's call** (retrieval-only tools —
`justsearch_search` / `_browse` / `_ingest` / `_status` — vs `justsearch_answer`, which needs the
`llm` tier). This note owns only the packaging/footprint and the mode metadata.

### D7 — The low-footprint tier is two rungs: BM25 (zero-download) and, optionally, a small multilingual *dense* model (low-download)

There are two distinct low-footprint rungs, and the honest answer differs by rung. (This section was
revised after a mid-2026 research pass; the size/quality landscape moved since the first draft — see
the dated note below.)

- **Zero / near-zero download → BM25.** Already the keyword fallback in the Lucene-owning Worker,
  **uniquely multilingual-by-construction** (no per-language artifact), delivers evaluable value neural
  retrieval is *worse* at (exact-match, code/identifier search), and honestly "lights up" to hybrid when
  an embedding model loads. At < a few MB it is the only honest *near-zero* tier. **Static embeddings do
  not beat it:** model2vec/potion multilingual retain ~82%-of-MiniLM retrieval quality *at best* and the
  cliff widens cross-lingual — they cost 30–128 MB to be *worse* than BM25 at retrieval. So static is out.

- **Low download (~100–200 MB) → a small multilingual *dense* model is now viable.** As of mid-2026,
  genuinely multilingual (~100-language) dense embedders exist well under 200 MB quantized with real
  multilingual *retrieval* quality — e.g. **multilingual-e5-small** (~118 MB int8 ONNX, **MIT**, MIRACL
  nDCG@10 ≈ 59.3) and **snowflake-arctic-embed-m-v2.0** (~110 MB, **Apache-2.0**, MIRACL ≈ 55.2), both
  3.5–5× smaller than the 628 MB incumbent `gte-multilingual-base` for only a few nDCG points. Google's
  **EmbeddingGemma-300m** (~179 MB uint8) is the quality leader but ships under the **Gemma Terms of
  Use** (not Apache) and does **not** support fp16 — redistribution/legal review + a non-fp16 pipeline
  are prerequisites, so it is not the clean-license default.

**Invariant check (correcting the first draft):** Hard Invariant #6 forbids *per-language levers /
artifacts*, **not small models**. A single small *multilingual* model is one locale-invariant artifact —
it upholds #6 exactly as the current 628 MB model does. The reason to prefer BM25 for the *zero* rung is
download size, not the invariant; a ~118 MB multilingual dense model is invariant-compatible.

**Design consequence:** the low-download rung is a **size/quality variant of the `retrieval-core`
embedding package**, selectable by intent — not a new tier and not a per-language artifact. Whether to
*populate* it with a small multilingual dense model (recommend the MIT/Apache options; EmbeddingGemma
only after license clearance) or to leave the low-download rung as BM25-only is now a **genuinely open
founder decision** (it was wrongly foreclosed as "BM25-only" in the first draft). The packaging
substrate (D1–D6) supports either without change; this is a populate-the-registry choice, not an
architecture choice. Shipping any such model goes through the existing registry `license` field +
`check-notices-regen` gate — no ad-hoc vendoring.

### D8 — Reused vs genuinely new

| Reused (extend) | Genuinely new (small) |
|---|---|
| `InstallPlanner.plan(...)` pure function | one added input: `intent` |
| `ModelPackage` / registry v2 | one added field: capability `tier` |
| `DownloadProfile` (hardware axis) | *unchanged* |
| `InstallContract` bill-of-materials | one persisted field: chosen `intent` |
| `InstallPlan.totalBytes` + plan UI | group presentation by tier |
| `RuntimeManifest` + closure rule + `publicProjection()` | one projected `mode` field |
| `ReadinessEnvelopeView` composites (`retrieval`/`aiFeatures`) | *read from*, not changed |
| per-subsystem enable flags + `SearchPipelinePresets` | three named presets |

## Scope boundaries (deliberately excluded)

- **No per-model checkboxes.** Tier presets are the right granularity; per-artifact selection is
  structure the problem doesn't have.
- **No separate lean-installer build committed here.** Whether the ~3.5 GB ONNX stays bundled or moves
  to download-on-demand is a *build-time bundling policy* (`stageOnnxModels` is the seam) independent of
  the runtime intent model — the design is robust to either choice. See Open Decisions.
- **No new *static* embedding model** (model2vec/potion — D7: retrieval cliff). A small *multilingual
  dense* model for the low-download rung is *not* excluded but is deferred to a founder decision (D7 /
  Open Decisions #2) — this design supplies the substrate, not the model choice.
- **No MCP tool-subset policy** (655's) and **no product-shape blessing** (654's).
- **No generalized "intent-vs-realized" framework** beyond the one manifest field the problem needs
  (see §Principle).

## Answers to the founder's original first questions

- *Which capabilities define each mode?* → D6 (tier sets × shell-attached).
- *Which models required/optional/deferrable per mode?* → D2 tiers: `retrieval-core` required for
  neural search, `enrichment` optional quality, `llm`/`vision` optional and the deferrable heavy tier.
- *How to explain first-run download without hiding size?* → D5 (per-tier breakdown; the optional heavy
  tier shown as optional).
- *Can a no-model/small-model mode provide value?* → D7 (yes — BM25 for the zero-download rung; and,
  as of mid-2026, a small multilingual *dense* model ~118 MB is a viable low-download rung. Static
  embedders remain out).
- *What metadata must expose the active mode?* → D4 (a projected `mode` field on the runtime manifest,
  via the closure rule; not `/infra/capabilities`).

## Principle & reach

Two principles this design conforms to, and one recurring shape it reveals. **Recorded, not built out
— the present problem needs only the single manifest field and the single planner input above.**

1. **Compute, don't store; separate an axis rather than overload one signal.** 381 already made
   inclusion a pure projection of *(declared registry × detected hardware × installed state)*. The
   generalization: when a new use case needs a decision axis the function currently *infers* from
   another signal, separate the axis instead of overloading the signal. **Current instance that
   violates it:** `DownloadProfile` folds hardware-capability and user-intent together (`GPU_LITE`
   doubles as "small GPU" and "retrieval-only"). This design separates them. **Candidate reach:** any
   other place a single detected signal is silently standing in for a user choice (e.g. the
   `switchInference('online'|'indexing')` runtime toggle is worth checking against this lens). Do not
   build a generic axis framework; just don't add a fourth `DownloadProfile` value.

2. **The advertised shape must never outrun the realized shape** (projection-not-fork — 650 + the
   manifest closure rule). A mode *label* is a projection of loaded capability, intent the only stored
   primitive. **Already honored** by the readiness composites and reason codes (`INDEX_DENSE_UNAVAILABLE`,
   `ai.required`/`phase`); the `mode` field is one more instance, not a new pattern. **Reach:** the
   public capability descriptor (650), README/docs claims, and any future "certified"/"conformant"
   language (655) must all derive from realized state, never assert an aspirational shape. This is the
   principle most likely to be violated by public-facing copy, so name it plainly.

3. **Naming hazard: "profile" is already overloaded** — `DownloadProfile` (hardware) and the FE
   "Workspace Profile" (tempdoc 543, UI manifests + scope). The intent concept must use a distinct term
   (*install intent* / *runtime mode*), never "profile," to avoid a third meaning.

## Open decisions for the founder (before an implementation pass)

1. **Offline-retrieval-immediately vs small-initial-download.** Keep bundling ~3.5 GB ONNX (retrieval
   works air-gapped on first launch) *or* move it to download-on-demand for a lean installer matching
   the Ollama/LM-Studio norm. These pull opposite directions; the MCP-Lite/dev-eval wedge should decide
   which it optimizes. The design supports either — this is a bundling-policy choice, not an
   architecture choice.
2. **Low-footprint tier: BM25-only, or BM25 + a small multilingual dense preview?** BM25 is the honest
   *zero-download* rung either way. The open question (reopened by the mid-2026 research pass — D7) is
   whether to *also* populate a ~118 MB *low-download* rung with a small multilingual dense model
   (multilingual-e5-small, MIT, or arctic-embed-m-v2.0, Apache-2.0 — both redistribution-clean;
   EmbeddingGemma only after Gemma-license clearance). Cost: a second embedding model to maintain and
   eval. Benefit: a genuinely-semantic preview at ~5× less download than the full stack.
3. **Does "Headless Runtime" include the `llm` tier by default, or is it retrieval-first with LLM
   opt-in?** (Affects whether Headless and MCP Lite differ only by advertised MCP surface.)
4. **Adoption thesis is unproven.** The "lighter peers win trust" motivation is design-convergence
   evidence, not measured data (no adoption/conversion study found). If it is decision-critical,
   instrument JustSearch's own first-run funnel (time-to-first-result, abandonment) rather than cite
   external proof. This does not weaken the packaging-hygiene case, which stands on its own.

## Drift to reconcile (prerequisite — both logged to the observations inbox)

- **ADR-0024** (`docs/decisions/0024-...:37-52`) — "no models bundled / ~748 MB / all 8.5 GB
  post-install" is contradicted by `stageOnnxModels` (default-on). **Must be corrected before the
  "make first-run weight explicit" goal can be designed against a real baseline.**
- **model-inventory.md Open Decision #1** (`:355`) — "should ONNX embedding+SPLADE enter the registry?"
  is already settled (both are packages); the "FP32 not yet in registry" notes are stale.
- Minor: `DownloadProfile` Javadoc size estimates predate the `cuda-runtime` package and the FP32
  embedding variant — illustrative, not authoritative.

---

## §C — Pre-implementation confidence probe (2026-07-02)

Firsthand trace of the load-bearing "it just projects off existing state / minimal net-new plumbing"
claims, before implementation. Each uncertainty marked **resolved / partly-resolved / open** with
`file:line` evidence.

- **U1 — manifest `mode` field mechanics → RESOLVED (with one scoping correction).** `RuntimeManifest`
  is a `@RecordBuilder` record with `@JsonInclude(NON_NULL)`; new optional sub-records stay at
  `CURRENT_SCHEMA_VERSION = 1` and each declares its own `publicProjection()`
  (`RuntimeManifest.java:37-72,193`) — so a new optional `ModeInfo` field is schema-compatible and
  follows the exact pattern `reachability` used. **Correction:** the publisher listens only to
  `WorkerCapability` + `InferenceCapability` (`RuntimeManifestListenerWiring.java:77-155`), **not** the
  `StatusLifecycleHandler` `retrieval`/`aiFeatures` composites. So LLM-availability + worker-ready
  project for free, but fine-grained dense-vs-BM25 retrieval depth needs a *new* embedding-readiness
  listener wired to the publisher — a known small cost, not free. Design updated (D4) to keep the
  manifest `mode` coarse.

- **U2 — "shell attached" signal + headless entry point → RESOLVED, favorably.** There is **no
  shell-attached signal anywhere** (grep hit only this tempdoc) — and none is needed. The Tauri shell
  already spawns `ui-headless.jar` (the same `HeadlessApp`) as a child, passing a batch of
  `-Djustsearch.*` system properties + env (`modules/shell/src-tauri/src/lib.rs:647-720`). So **intent
  is one more launch-config input** (`-Djustsearch.mode=…`): the shell declares `full-desktop`, a
  service launcher declares `headless`/`mcp-lite`; the backend reads it like any other config and hands
  it to the publisher as a producer-owned primitive. **Genuinely new (but small):** a *supported*
  headless/service launch path — today only the shell-spawn and the dev `runHeadless` Gradle task exist
  (`modules/ui/build.gradle.kts`), so "Headless Runtime as a product" needs a small launcher + doc
  (and 654's product-blessing), not deep plumbing.

- **U3 — tier ↔ package boundary → RESOLVED.** Each registry package maps to exactly one tier
  (embedding→`retrieval-core`; splade/reranker/ner/citation→`retrieval-enrichment`; chat→`llm`;
  cuda-runtime→`runtime`), so the `tier` tag lives cleanly on `ModelPackage`. **Exception:** the VDU
  projector `mmproj` is a *supporting file* of the `chat` package, not its own package — so `vision`
  is not independently selectable without a registry package split (D2 corrected).

- **U4 — intent plumbing + contract consumers → RESOLVED, additive.** `InstallPlanner.plan(...)` is a
  pure per-package loop; the existing VRAM gate (`InstallPlanner.java:65`) is a package-level predicate,
  so an intent/tier gate is a *parallel* predicate, not a tangle. `InstallContract` already carries
  backward-compat constructors (`InstallContract.java:46-53`), so adding a persisted `intent` field is
  additive; its runtime consumers (`KnowledgeServer`, `InferenceCompositionRoot`,
  `StatusLifecycleHandler`) read specific `resolveModelPath`/model state, not "downloadProfile decides
  everything," so they are unaffected. Intent is chosen at install time (UI) or launch config (headless)
  — both existing mechanisms.

- **U5 — sequencing → confirmed OPEN dependency.** 654 / 655 / 656 are all `status: open`. MCP Lite's
  exposed tool subset is 655's decision and "official product shape" is 654's; an MCP-Lite/Headless
  *product* implementation should not land ahead of those. The **substrate** (tier tag + intent input +
  manifest `mode` + plan-by-tier) is independent and can proceed; only the *named-product* surfacing
  waits on the siblings.

**Live probe:** skipped deliberately — the static read of `RuntimeManifest.java` gives the definitive
field set, and contending the shared dev stack for a JSON-shape confirmation wasn't worth it.

### U6/U7 — implementation-surface trace (FE presentation + per-change compat) → RESOLVED, additive

Two follow-up probes traced every change point to `file:line`, confirming each is additive with a known
compat template and guarding test:

- **`tier` on `ModelPackage`** — loader is lenient (`ModelRegistryLoader` disables
  `FAIL_ON_UNKNOWN_PROPERTIES`); **no registry JSON schema exists**; no `schemaVersion` bump. Add via
  the trailing-arg backward-compat-ctor pattern `installRoot`/`license` already established
  (`ModelPackage.java:52-78`). `ModelRegistryLoaderTest.everyPackageDeclaresALicense` is the template
  for an `everyPackageDeclaresATier` invariant.
- **Per-tier size breakdown (D5)** — the install **wire carries per-*package* size** (`packageId` +
  `bytesTotal`), not per-file or per-tier; the breakdown is **new aggregation** in
  `AiInstallService.populateStatusPackages` (`:980-1012`), not a passthrough. `AiInstallStatus` has
  **no JSON schema and no generated TS type** (FE reads untyped JSON), so no codegen/strict-parse gate.
  FE render site is `BrainSurface.renderModels()` (`:1586-1618`); the **pre-install "several GB" string
  is a hardcoded literal with no size data behind it** (`:755`) — a tiered pre-install estimate must
  consume the currently-unused `GET /api/ai/install/manifest`. shell-v0 uses plain string literals (no
  localize keys), so a new label is friction-free on i18n; the **ui-web gate battery**
  (presentation-purity, token-closure, a11y) is the real FE friction. **Pre-existing bug to fix
  alongside:** `renderModels` reads `p.id` but the wire emits `packageId`, so the name column always
  shows the fallback `'package'` (logged to observations).
- **Manifest `mode`** — `publishAi` + the shared `commit(...)` is the exact template
  (`RuntimeManifestPublisher.java:492-522`); a nullable `@RecordBuilder @JsonInclude(NON_NULL)`
  `ModeInfo` passes all three `RuntimeManifestSchemaCompatibilityTest` guards, is **invisible to
  `check-runtime-manifest-closure.mjs`** (it inspects sibling files/stdout, not record fields), needs
  no version bump and no `RuntimeTransportRegistry` change.
- **Install `intent`** — thread through controller → `AiInstallService.startInstall` → status, using
  the existing `parseAcceptTerms` body-parse pattern (`AiInstallController.java:127-137`).
- **`-Djustsearch.mode` / `JUSTSEARCH_MODE`** — one `EnvRegistry` enum constant (precedent
  `LLM_MODE` at `EnvRegistry.java:95`), read via `.get()` like every existing key (satisfies the
  no-direct-sysprop build gate).
- **Compat template for every added record field:** `InstallContractIOTest` (old-JSON-without-field →
  null + N-arg backward-compat ctor test) is the pattern to copy.

### Updated risk list (post-probe)

- **Lowered:** manifest field (established `publishAi` template, closure-check-invisible,
  schema-compat-safe), intent plumbing (additive to a pure planner + back-compat contract + tolerant
  readers), tier tag (lenient loader, no schema, compat-ctor precedent), FE per-tier breakdown (data
  mostly on the wire, render site known, no codegen gate), "shell attached" (dissolved — launch config,
  not a detected signal).
- **Remaining, bounded (known work, not surprises):** (a) new *aggregation* logic for per-tier
  subtotals + FE grouping under the ui-web gate battery; (b) a new embedding-readiness listener *only
  if* dense-depth must appear in the manifest `mode` (avoided by keeping mode coarse); (c) a supported
  headless/service launcher + doc for the Headless/MCP-Lite product shapes; (d) `mmproj`/vision needs a
  registry package split to be independently selectable; (e) FE must start consuming
  `/api/ai/install/manifest` for a *pre-install* tiered estimate.
- **Remaining, external:** (f) sequencing — the named-product surfacing depends on 654/655; (g)
  ADR-0024 reconciliation is a prerequisite doc fix (`stable` decision record — may want founder
  sign-off); (h) whether to populate the low-download rung with a small multilingual dense model is an
  open founder decision (D7 / coordinated with 656's onramp-tier choice).
