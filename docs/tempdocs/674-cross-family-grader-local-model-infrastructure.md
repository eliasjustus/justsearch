---
title: "Local cross-family grader calibration via a column-level rater seam in the eval calibration layer -- lift the calibration orchestrator's rater boundary from per-item to per-column so a local, GPU-serial second-lineage grader is just another rater (reusing the existing agreement machinery and the existing single-tenant-GPU swap primitive), letting §M.8 item 3 run on local models with no paid-API credentials and no new inference-lifecycle infrastructure"
type: tempdocs
status: "open -- long-term design SETTLED (2026-07-02); implementation not started, deferred pending go-ahead. Spun out of tempdoc 624's remaining-work cost evaluation. The grounding pass (§Investigation grounding) dissolved most of the assumed cost -- the model-swap primitive, the GGUF registry, and the single-tenant-GPU serialization all already exist -- and relocated the real missing structure to the eval calibration layer: the two near-duplicate calibration orchestrators cut their rater boundary per-item, which silently assumes concurrently-live raters and is the sole reason a local GPU-serial grader doesn't fit. Design (§Long-term design): lift the rater seam from per-item to per-column so heuristic / concurrent-endpoint / serial-GPU-swap graders are all just column-producers over the untouched agreement machinery; the serial rater drives the EXISTING applyRuntimeOverrides swap primitive and conforms to the EXISTING exclusive-GPU mode invariant, so no new inference-lifecycle infrastructure is built. See §Design reach for the principle and its candidate scope."
created: 2026-07-02
updated: 2026-07-02  # investigation-grounding + theorization + settled design + design-reach + bounded external-research pass (see §Investigation grounding, §Theorization, §Long-term design, §Design reach, §External research pass)
author: agent investigation (spun out of a direct cost-accounting question against tempdoc 624's remaining-work plan)
category: app-inference / model-lifecycle / agent-eval / judge-calibration
related:
  - 624-agentic-retrieval-eval-rebuild   # the consumer -- §M.8 item 3 (cross-family judge calibration), and external_grader.py, the provider-agnostic client this tempdoc points at a local endpoint instead of an external one
  - 673-agent-utility-standing-regression-ratchet   # sibling tempdoc, NOT the same concern -- 673 catches regressions cheaply and often using an already-trusted judge; this tempdoc validates a judge's trustworthiness, infrequently, at credibility-grade cost
  - 518-inference-lifecycle-design   # adjacent context -- the inference runtime lifecycle this work must respect, not bypass (dated 2026-05-18; verify current relevance against HEAD before trusting its specifics)
principle: "external_grader.py's client is provider-agnostic by construction (endpoint URL + model name + headers as config, nothing vendor-hardcoded) -- so 'a cross-family grader' does not require a new paid vendor relationship. A second locally-hosted model of a different training lineage than the local Qwen judge satisfies the founder decision's actual stated reasoning ('different training lineages fail less identically') as well as an external frontier model would, at zero dollar cost. What it is NOT free of is engineering time: no local dual-model-swap infrastructure exists in this codebase yet, and this tempdoc is where that gets designed, not 624's own text."
---

# 674 -- Cross-family grader local-model infrastructure

> NOTE: Opened 2026-07-02, spun out of a direct cost-accounting question against tempdoc 624's remaining
> work: the local-model path for §M.8 item 3 was being described as "free" because its dollar cost is $0,
> without ever pricing the real engineering cost of building infrastructure that doesn't exist yet. No
> design or implementation has started.

## Why this tempdoc exists

Tempdoc 624's founder decisions (§U-Founder-4 revised) accept a cross-family LLM grader panel in place of
human judge-calibration. The mechanism to *call* such a panel is already built and tested
(`external_grader.py`, mocked-HTTP tests only, zero real network calls) -- but it was designed against the
assumption of an external paid API, and this environment has no API credentials for one (confirmed: no
OpenAI/Gemini/Google/Anthropic keys anywhere). A later refinement proposed pointing the same
provider-agnostic client at a second *local* model instead, removing the credential dependency entirely.
That refinement was correct as far as it went, but it was recorded as a footnote in 624's own text without
being priced -- and it turns out to be the single largest unscoped cost in the whole remaining plan, because
none of the infrastructure it assumes (staging a second model, running it alongside the existing judge
without blowing the GPU's VRAM budget, verifying its output is usable by the existing kappa/agreement
pipeline) exists today. That's real, unscoped `modules/app-inference` lifecycle work, not a config change --
it deserves its own design pass rather than continuing to live as an unpriced aside in 624.

## Overarching goal

Make §M.8 item 3 (cross-family judge calibration) executable using this project's own existing local-
inference infrastructure, with no dependency on an external vendor relationship or credentials -- while
being honest that "no dollar cost" and "no cost" are not the same thing; this tempdoc is where the real
engineering cost gets designed and priced, not hand-waved.

## This tempdoc's main goal (scope)

1. **Choose a second local model of a different training lineage than the local Qwen judge** (e.g.
   Llama-class, Mistral-class, or Gemma-class GGUF) -- a real selection decision with real download/staging
   cost, not a given.
2. **Design sequential load/unload orchestration.** This machine's GPU (12GB) most likely cannot hold two
   ~8-9B local models simultaneously alongside the rest of the dev-stack's resident models -- verify this
   constraint concretely (current VRAM budget, current resident footprint) rather than assuming it, then
   design how the grader panel loads model A, grades, unloads, loads model B, grades, without violating
   `modules/app-inference`'s existing lifecycle contracts (the module CLAUDE.md names as owning "Online
   llama-server lifecycle" -- this work must respect that ownership, not route around it).
3. **Point `external_grader.py`'s existing `GraderConfig` at the local endpoint(s)** instead of an external
   one -- the client itself needs no changes if this is done correctly (that was the point of building it
   provider-agnostically); this tempdoc's job is the model-lifecycle side, not the HTTP-client side.
4. **Verify a second model's raw output is actually usable** by the existing
   `sample_for_calibration`/`collect_calibration_texts`/`cohens_kappa` pipeline -- a different model family
   may format responses differently than the local Qwen judge or a frontier API; confirm parsing holds
   before assuming it does.

## Explicit non-goals

- **Not** a replacement for the external-API path -- if credentials become available later, both paths can
  coexist (`GraderConfig` already supports either); this tempdoc doesn't need to argue local-only is
  strictly better, only that it's a viable, currently-executable alternative.
- **Not** the routine regression ratchet (673) -- this tempdoc's output is used rarely, at credibility-grade
  moments, not on every commit.
- **Not** a general-purpose "run any local model for any purpose" platform feature -- scope this to what the
  grader panel actually needs; resist building a speculative multi-model abstraction beyond that (AHA:
  only unify what shares a reason to change).

## Recommended model/effort for the design pass

Sonnet-5, **high** effort. Unlike 673 (which applies an already-proven four-times-over pattern), this
touches real lifecycle code in a Hard-Invariant-adjacent module with no existing local precedent for
sequential dual-model swapping in this codebase -- genuine novel design, not pattern application. The
VRAM-budget verification step in particular should be measured against the real current resident footprint,
not assumed, given this session's own history of GPU/CPU misattributions being caught only by direct
`nvidia-smi`-level verification rather than inference from documentation.

## Status

Open. Not yet started. **2026-07-02: an investigation + theorization pass (below, §Investigation
grounding and §Theorization) was added — no design or implementation is settled; the pass verifies the
tempdoc's own assumptions against `main` and opens the solution space before a design is chosen.**

---

## Investigation grounding (2026-07-02) — what is actually true today

This pass verified the tempdoc's premises against `main` rather than trusting them, per the tempdoc's own
§Recommended directive ("VRAM-budget verification should be measured against the real current resident
footprint, not assumed"). Findings, with primary-source citations:

**Hardware / artifacts (measured, not assumed):**
- GPU is an RTX 4070, **12282 MiB total, ~10.8 GB free at idle** (`nvidia-smi`). The tempdoc's "12GB, most
  likely cannot hold two ~8-9B models" hypothesis is **confirmed**: the one local chat GGUF,
  `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, is **5.5 GB on disk** (`models/`). Two such models' weights alone (~11 GB)
  plus KV cache + context would exceed the budget. Sequential (one-at-a-time) serving is forced, not a
  preference.
- **Only one local chat GGUF exists.** There is no second-family model on disk — a real download/staging
  cost, as the tempdoc says.

**The judge/grader serving path (this is the load-bearing part):**
- The served model is bound to `-m <modelPath>` **at process spawn** and is fixed for the process lifetime —
  llama-server here has no live "reload model" API (`LlamaServerOps.java:220-221`, argv built 218-275).
- **A model-swap primitive already exists.** `OnlineAiServiceImpl.applyRuntimeOverrides(llmModelPath,
  contextLength, gpuLayers, RestartPolicy)` → `InferenceLifecycleManager.applyConfig(newConfig,
  RESTART_ALWAYS)` does a full **stop (with `taskkill /F` to force VRAM release) → set new config → start on
  the *same* `serverPort`** (`OnlineAiServiceImpl.java:79-97, 249-256`; `InferenceLifecycleManager.java:683,
  688, 718-719`). "Swap the served model" is therefore an *already-supported* operation, not greenfield —
  the served model changes, the port does not.
- The GPU is a **single-tenant** resource by construction: the MMF `main_gpu_active` mutual-exclusion
  protocol (the still-live remnant of superseded ADR-0004) makes llama-server the sole GPU owner during
  Online mode, with the Worker's ONNX encoders yielding. A *second concurrent* GPU llama-server would
  violate this; serial swap rides the existing protocol cleanly.
- The Head's OpenAI-compat proxy (`OpenAiCompatController.java`) that serves the judge at
  `127.0.0.1:33221/v1/...` assumes **exactly one upstream** llama-server, resolved from the *static
  configured port* (`LocalApiServer.java:629-637`), and **forwards the request's `model` field without using
  it to route** (`OpenAiCompatController.java:115-186`). Consequence for this feature: after a swap, the same
  URL serves whatever model is now loaded — two graders behind this proxy are indistinguishable by URL *or*
  by `model` field; only *when* you call (before vs after the swap) selects the grader.
- A GGUF model **registry already exists** (`modules/ui/src/main/resources/ai/model-registry.v2.json`,
  `targetEP: "LLAMA_SERVER"`) and governs download/install of the chat GGUF — so staging a second model has
  precedent (a registry entry + fetch), it is not net-new plumbing.
- **`ai_activate` already spawns a throwaway bounded llama-server** on an ephemeral port for its GPU
  self-test, measuring the VRAM delta (`RuntimeActivationService.java:420, 603`). This is direct precedent
  for an *eval-only, temporary* server spawn — relevant to the "who owns the swap" question below.
- There is **no per-model VRAM budgeting** anywhere — only a coarse total-VRAM threshold
  (`HardwareProfile.MINIMUM_VRAM_FOR_GGUF`; `VramRequirements.COMFORTABLE_VRAM_BYTES = 11.5 GB`,
  `gpu-bridge/.../VramRequirements.java:30`). Nothing today reasons about "will model A + model B fit."

**The consumer (already built, tempdoc 624 §M.9):**
- `external_grader.py` is genuinely provider-agnostic (endpoint/model/headers all caller-supplied) — the
  tempdoc's central premise holds.
- **But the orchestration around it is not topology-agnostic.** `run_cross_family_calibration`
  (`utility_judge.py:555-569`) and the CLI (`commands/utility.py:434-520`) loop **per item, calling every
  grader on each item** (`verdicts = [call_grader_dual_order(g, ...) for g in graders]`). This assumes all
  graders are **concurrently reachable live endpoints** — precisely what a single-GPU serial swap cannot
  provide.
- No API credentials exist in the environment (verified empty) — the credential blocker the tempdoc cites is
  real.

---

## Theorization (2026-07-02) — directions, framings, tradeoffs, risks

> Exploratory, not a chosen design. The goal is to open the space and name what a later design pass should
> weigh, per the "theorize before settling" brief.

### A. The real constraint is an orchestration mismatch, not a lifecycle gap

The tempdoc frames the cost as "`modules/app-inference` lifecycle work." The grounding above reframes it:
the *swap primitive already exists* (`applyRuntimeOverrides(..., RESTART_ALWAYS)`), and the *single-tenant
GPU protocol already serializes* GPU access. The genuinely missing piece is **on the consumer side**: the
calibration loop interleaves graders per item, but a serial single-GPU panel can only offer one grader at a
time. So scope item 3's claim — "the client itself needs no changes… this tempdoc's job is the
model-lifecycle side, not the HTTP-client side" — is only half right. `external_grader.py` needs no change;
**`run_cross_family_calibration` must be restructured** from *interleave-by-item* to *batch-by-grader* (grade
all N items with model A, swap, grade all N with model B). That restructuring — not the Java lifecycle — is
the load-bearing engineering cost, and it lives in neither of the two buckets the tempdoc named.

Why interleave-plus-swap is not merely suboptimal but pathological: a full swap is stop + `taskkill /F` +
reload 5.5 GB from disk + health-wait — order of seconds. Batch-by-grader pays this **twice** total (≈2
swaps). The unchanged interleaved loop would pay it **once per grader per item** — for n=40, 2 graders, dual
order that is up to ~160 reloads, i.e. minutes-to-tens-of-minutes of pure disk-to-VRAM thrash. The
orchestration shape is the difference between a ~1-swap-each run and a degenerate one.

### B. Solution directions (a menu to weigh, not a decision)

1. **Drive the existing swap primitive from the eval harness.** jseval grades batch A against the live
   backend, issues one `applyRuntimeOverrides(modelB)` API call, grades batch B. Reuses all existing
   lifecycle; respects app-inference ownership; needs the batch-by-grader restructure (A) and a *served-model
   assertion* (F below). Cost is concentrated in Python + one already-exposed API.
2. **Eval-owned throwaway server(s).** jseval spawns its own bounded llama-server for grader A on an
   ephemeral port, runs the batch, kills it, spawns grader B, runs, kills it — pointing `GraderConfig
   .endpoint_url` at the raw port and bypassing the Head proxy. Precedent exists (`runSelfTest` already does
   exactly this spawn-measure-kill shape). Keeps *all* swap logic out of the Hard-Invariant-adjacent
   production module and out of the shared dev-stack; the tradeoff is duplicating a slice of spawn/health
   logic in Python and owning the single-tenant-GPU courtesy (don't run it while the dev-stack holds the
   GPU). Because calibration runs **offline against a pre-computed `judge-overlay.json`**, no live production
   inference need be running during grading at all — which makes this isolation natural rather than forced.
3. **Native multi-model (llama.cpp router mode / llama-swap proxy).** Upstream now supports dynamic model
   switching (still one model resident in VRAM; a full unload/reload on switch). Heavier dependency and
   arguably over-built for a rarely-run eval, but it is the path if multi-model serving is ever wanted in
   *production* (it would replace this codebase's bespoke single-upstream assumption). Noted for the future,
   not recommended for this narrow need (non-goal #3 warns against a speculative multi-model platform).
4. **CPU concurrency (minimal-change path).** Run one grader on GPU and the other on CPU (or both on CPU).
   This restores true concurrency, so the *existing interleaved loop runs unchanged*. For 30-50 samples the
   CPU grader's slowness may be tolerable. Trades wall-clock for zero orchestration change and zero swap
   machinery — the cheapest thing that could possibly work, worth pricing before building swap infra.
5. **Smaller co-resident models.** Two 3-4B graders (or heavier-quantized ~8B) *can* co-reside on 12 GB,
   also restoring concurrency and the unchanged loop — at the cost of grader capability (see risks). A
   capability-vs-concurrency dial.
6. **Hybrid panel.** One local grader + one external (whenever a single credential appears), or local-now /
   external-spot-check-later. The client already supports mixing; this hedges the capability-floor risk and
   matches the founder's own "optional 15-minute spot-check" escape hatch.

### C. Hidden assumptions worth challenging

- **"Provider-agnostic ⇒ ready for a local model."** The client is; the *panel orchestration* silently
  assumes concurrently-addressable raters. Provider-agnostic ≠ topology-agnostic — a leaked assumption.
- **"A second local model" (singular).** The ≥2-rater floor in `rater_agreement_report` means a *fully-local*
  panel needs **≥2 different non-Qwen families** (e.g. Llama-class + Mistral-class) — two downloads, two
  swaps — not one. Scope item 1 undercounts. (A mixed panel, B6, is the way to keep it to one local model.)
- **"As well as an external frontier model would" (frontmatter `principle`).** A local ~8-9B grader satisfies
  the *lineage-diversity* half of the founder's rationale but **not** the *frontier-capability* half (the
  founder said "frontier models"). The local path is a legitimate, currently-executable alternative that
  yields **weaker** calibration evidence — especially on the hard/ambiguous cases the founder already flagged
  as calibration's weak spot. Honest framing: cheaper *and* weaker, not equivalent-at-zero-cost.
- **"This is app-inference's problem."** It need not be (directions B2/B4). Whether an eval-only grader server
  is "Online llama-server lifecycle" (app-inference's charter) or an offline test fixture (jseval's) is a
  real ownership judgment for the founder, not a settled fact.

### D. Risks a design pass must weigh

- **Correlated-weakness inflates agreement (the most important validity risk).** Cross-family calibration is
  worth doing *because* independent lineages make *decorrelated* errors. Two *small* models decorrelate less
  than two *frontier* models — they share more training-corpus overlap and fail on the same hard cases in the
  same direction. High rater-vs-rater agreement among two weak local graders can therefore be **spuriously
  reassuring** — it may reflect shared blind spots, not a well-calibrated judge. This is a threat to the
  *meaning* of the number, not just its strength, and it is the sharpest argument for a hybrid panel (B6) or
  at least for reporting the local result with this caveat attached.
- **Output-format drift (scope item 4 is right to flag this).** The verdict parse is `text.startswith("YES")`
  after upper-casing. The Qwen judge suppresses chain-of-thought (`enable_thinking: False`); a different
  family may emit reasoning first, or "Yes." / "The candidate is correct" — silently misparsed as NO. Each
  new family needs its own thinking-suppression / format check before its labels are trusted.
- **Silent same-model grading (a concrete correctness trap).** Because the proxy routes neither by URL nor by
  `model` field, if a swap *fails* (or is forgotten) both "graders" are the *same* loaded model — producing
  near-perfect, entirely meaningless rater-vs-rater agreement. A design must **assert the served model
  identity** (`/v1/models` or `/props`) matches the intended grader before grading each batch. This is
  "verify, don't guess" applied to model identity.
- **Single-tenant GPU courtesy.** Any approach must not run a second GPU llama-server concurrently with the
  dev-stack's — it would violate the `main_gpu_active` protocol. Serial swap and the offline-only run window
  both avoid this; naive two-instance concurrency does not.

### E. An under-counted upside of the local path (not just a weaker substitute)

Local, temperature-0 grading is **deterministic and re-runnable**: the exact calibration can be reproduced
and audited later from the same inputs, with no vendor, no rate limit, and no silently-changed model version
behind an API. External frontier APIs cannot offer this — their models drift under a fixed name. For a
*methodology* artifact whose whole purpose is credibility, reproducibility is a first-class virtue, not a
consolation. This reframes the local path as having its *own* distinct strength (auditability) that partly
offsets its capability deficit — worth stating so the choice reads as a genuine trade, not a pure downgrade.

### F. Possible broader shape (named, not built)

- **"Serialize scarce single-tenant resources at the orchestration layer, and make the panel say whether its
  raters are concurrent or serial."** The recurring lesson: an abstraction that hides *who* a provider is
  should also be explicit about *whether* providers can be addressed at once. The panel abstraction here hid
  a concurrency assumption; the fix is to make serial-vs-concurrent an explicit property of the panel, so the
  same calibration code can run against a GPU-serial local panel, a CPU-concurrent local panel, or a
  concurrent external panel without silently assuming one topology.
- **An eval-time "bounded ephemeral model server" fixture.** `runSelfTest`'s spawn-measure-kill and a
  hypothetical eval grader-spawn (B2) share a shape: *stand up a throwaway llama-server, use it briefly, tear
  it down, reclaim VRAM.* There may be one small reusable fixture here — but **AHA caution**: unify only if
  they truly share a reason to change; a self-test that measures VRAM and a grader that answers prompts may
  not. Flag for a later look, don't force it now.
- **The credibility-vs-cost frontier (the invariant 624 keeps circling).** Calibration credibility ≈ grader
  *decorrelation* × grader *capability*. The choices above are points on that frontier, not a binary
  local-vs-external switch: local-only (max availability + reproducibility, lower on both credibility axes),
  hybrid (one anchor of capability), external-only (max capability, zero reproducibility, blocked on
  credentials + spend). Naming the frontier explicitly lets the founder pick a point deliberately rather than
  defaulting to whichever path is unblocked this week.

### G. What a later design pass still has to decide (open questions, not answered here)

1. Ownership: extend/drive app-inference's swap (B1) vs eval-owned throwaway server (B2) vs CPU-concurrency
   escape from swapping entirely (B4).
2. Panel composition: two local families, or one-local-plus-hybrid (B6), given the correlated-weakness risk.
3. Whether the batch-by-grader restructure of `run_cross_family_calibration` is done as a general
   serial/concurrent panel property (F) or as a narrow eval-only branch.
4. The served-model-identity assertion (D) — where it lives and how it fails loud.
5. Model selection: which specific non-Qwen GGUF(s), at which quant, meeting a *stated minimum grader
   capability* (not just "a different family").

---

## Long-term design (2026-07-02) — SETTLED

> General shape, not implementation. Selected from the §Theorization menu after grounding against `main` and
> the adjacent tempdocs. The scope is deliberately small because the problem is small once framed correctly —
> see the AHA justification below for why it is *this* small and not smaller.

### The decisive reframing: the missing structure is a rater seam, not swap infrastructure

The tempdoc opened by pricing "local dual-model-swap infrastructure." The grounding pass dissolved most of
that cost: the swap primitive already exists (`applyRuntimeOverrides(..., RESTART_ALWAYS)`), the GPU is
already serialized by the inference module's Online/Indexing **mode** invariant (`InferenceLifecycleManager`
is literally "Singleton managing exclusive GPU access between Online and Indexing modes"), and a GGUF
download registry already exists. What is genuinely missing sits in the **eval calibration layer**, and it is
structural, not lifecycle:

`rater_agreement_report(judge_verdicts, raters, ...)` — the agreement/κ/CI machinery — is **already
rater-source-agnostic**: it consumes `raters: list[list[bool]]` (N per-item label columns) and does not care
where the labels came from. Above it sit two near-duplicate orchestrators —
`run_calibration_dry_run` (heuristic-function raters) and `run_cross_family_calibration` (live-HTTP raters) —
that share one skeleton (sample → collect texts → assemble columns → report → overlay) and differ in exactly
one respect: **how each rater turns a sampled item into a label.** Their own docstrings admit the coupling
("mirrors `run_calibration_dry_run`'s exact shape").

`run_cross_family_calibration` bakes in a *per-item* rater call (`for item: [grade(g, item) for g in
graders]`). That per-item shape is the *sole* reason a local GPU-serial model doesn't fit — it presumes every
rater is a concurrently-live endpoint.

### The design: lift the rater boundary from per-item to per-column

Introduce one small seam in the calibration layer: **a rater is a thing that produces a full label *column*
over the calibration sample** (one boolean per sampled item), and *owns internally* how it does so. The
orchestrator becomes a single function that collects each rater's column **sequentially** and hands the
columns to the untouched `rater_agreement_report`.

Under this seam the three rater kinds are just three column-producers:

- **heuristic rater** — computes the column in-process (today's dry-run substitutes);
- **concurrent-endpoint rater** — loops the sample against a live HTTP endpoint (today's external/frontier
  path, and equally a *CPU-served* local model, which can be live alongside others);
- **serial GPU-swap rater** — sets its model via the existing `applyRuntimeOverrides` swap primitive, waits
  for readiness, **asserts the served-model identity matches the one it intends** (via `/v1/models` — the
  guard that stops a failed swap from silently grading two columns with the same model), labels the whole
  sample, and yields its column; the next rater then swaps in the next model.

Because columns are produced **one rater at a time**, the interleave-vs-serial tension named in
§Theorization-A simply *disappears* — a GPU-serial rater loading, labelling all items, and being torn down is
the natural unit of a column-producer. No execution-topology tag is needed for correctness. The single-GPU
constraint is honoured by construction, and it is honoured by **conforming to the inference module's existing
exclusive-GPU mode invariant** (the eval layer serializes GPU tenants the same way the runtime already does),
not by inventing a parallel VRAM budgeter.

### What is reused vs. new (scope is an outcome, not a target)

- **Reused verbatim:** `sample_for_calibration`, `collect_calibration_texts`, `rater_agreement_report`
  (+ κ/CI/`degenerate_pe`), `write_overlay`, the `rater_kind`/`n_abstained` reporting discipline,
  `external_grader.py` (unchanged — the provider-agnostic client was correct), the `applyRuntimeOverrides`
  swap primitive, the GGUF registry, and the single-tenant-GPU mode invariant.
- **New (small):** the column-producer rater boundary; one orchestrator that supersedes the two
  near-duplicates; the serial GPU-swap rater implementation (a thin driver over the *existing* swap
  primitive + a served-model-identity assertion); and ≥2 non-Qwen GGUF registry entries.
- **The two existing orchestrators collapse into the one seam** rather than a third being forked beside them.

### Why this scope and not smaller (the AHA test, applied)

Unify only what shares a reason to change. `run_calibration_dry_run` and `run_cross_family_calibration`
*already* must change together whenever the sampler, the agreement shape, or the overlay contract changes —
they are kept in lockstep by hand today (the docstrings prove it). A local-serial path would be a **third**
copy of the same skeleton. Three hand-synchronised copies of one orchestration, differing only in
label-production, is precisely the drift-prone duplication the AHA principle says to unify. So the seam is
*required* by the present problem (a third rater kind that cannot be expressed per-item), not added for a
speculative future. Equally, the seam stops at "rater = column-producer": no plugin registry, no config DSL,
no general multi-model serving platform (non-goal #3). That larger structure is not required by any rater
kind on the table.

### Explicitly rejected (with reasons, so a later pass doesn't re-litigate)

- **Eval-owned throwaway llama-server spawned by jseval** (§Theorization-B2): rejected as the *primary* path
  because it re-implements, in Python, model-swap serving that `app-inference` already owns — a parallel
  authority for "which model is served," exactly the anti-pattern adjacent tempdoc 672 settled against
  ("conform to the existing seam rather than value-capture / duplicate it"). Kept only as a fallback if
  driving the swap through the live backend proves impractical for an offline run.
- **llama.cpp router mode / llama-swap proxy** (§Theorization-B3): rejected for this need as speculative
  multi-model *production* infrastructure (non-goal #3); revisit only if production multi-model serving is
  ever wanted for its own sake.
- **Two co-resident small models** (§Theorization-B5): rejected as a *default* because it trades away grader
  capability (see risk §Theorization-D) to buy a concurrency the column seam makes unnecessary; remains
  available as a per-rater choice, since a CPU- or small-GPU-served model is simply a concurrent-mode rater
  under the same seam.

### Honesty constraints carried into the design (public-history note)

The design does not change the founder-accepted honesty limits, and must not let local execution soften them
in any public-facing claim: the emitted statistic stays stamped `rater_kind: "cross-family-llm, NOT human"`;
a local ~8-9B panel is **cheaper and weaker** than a frontier panel, not equivalent (the frontmatter
`principle`'s "as well as an external frontier model would" is an over-reach on the *capability* axis, true
only on the *lineage-diversity* axis); and two small models risk **correlated** blind spots that can inflate
rater-vs-rater agreement, so the local number must be reported with that caveat. The one genuine local-only
*upside* — deterministic, re-runnable, auditable calibration (temperature 0, no vendor drift) — may be stated
plainly. None of this is compliance/certification framing and must not become such in README/docs/business.

---

## Design reach (2026-07-02) — principle, scope, and the deliberate stop

### The principle this is an instance of

**"A source-agnostic aggregator needs a source seam at the granularity the scarcest shared resource
dictates."** The aggregation half was already right (agreement stats don't care who labels). The failure was
that the *source seam* was cut at **per-item** granularity, which silently encodes "every source is
concurrently addressable" — an assumption a single-tenant resource (the one GPU) breaks. Lifting the seam to
**per-column** (the whole unit of work a source can own end-to-end) makes serial and concurrent sources
interchangeable without the aggregator or the orchestrator knowing which it has. Stated generally:
*choose the producer-seam boundary so that a producer owning a scarce exclusive resource can complete its
whole contribution within one invocation, rather than being re-entered per element.*

### This conforms to an invariant the system already has

The serial-rater mode is not a new idea — it is the **eval layer conforming to the inference module's
existing exclusive-GPU mode invariant** (Online vs Indexing mutual exclusion, the surviving core of superseded
ADR-0004, still enforced via the `main_gpu_active` MMF flag). The runtime already serializes GPU tenants;
the calibration layer simply stops assuming it can ignore that. So this is *conformance to an existing seam*,
not a parallel invention — the outcome the "prefer extending over replacing" discipline asks for.

### Where else the principle applies in this system (named, not built)

- **Any future model-ensemble/panel over the single GPU** — multi-model voting judges, A/B model
  comparisons, self-consistency across local models. All would hit the same per-item-vs-per-column choice.
  The column seam is the reusable answer, but only the calibration path needs it *today*.
- **Candidate existing tension to check (not asserted):** the eval "leg modes" (`vector,lexical,splade,hybrid`)
  and the staged-recall accounting aggregate per-leg results; if any leg is GPU-resident (SPLADE/rerank/embed
  encoders share the GPU under the same mutual-exclusion protocol), an orchestrator that assumed legs were
  concurrently live would be the *same* latent violation. Worth a look when that code is next touched — flagged,
  not fixed here (out of this tempdoc's scope; log to the inbox if confirmed).

### Does existing code violate the principle?

Yes, in exactly one place today: `run_cross_family_calibration`'s per-item grader loop encodes the
concurrent-source assumption and is the reason a local GPU grader cannot be expressed. That is the specific
violation this tempdoc's design removes. No other confirmed violation is in scope; the leg-mode question above
is a *candidate*, deliberately left as an observation rather than expanded into this work.

### The deliberate stop (recognize ≠ build)

The general "topology-explicit producer seam / execution-mode tag" — parallelising concurrent raters for
speed, a registry of rater kinds, a reusable ensemble framework — is **recognised and named here but not
built**. The present problem needs only the column-level seam and one serial rater; calibration runs are rare
and small (30-50 items), so the parallel-execution optimisation earns nothing today. Recording the principle
and its candidate scope without erecting the generalized structure is the point: a real insight captured
without premature abstraction.

## External research pass (2026-07-02) — bounded, calibration-validity only

> Scope decision: the *architecture* (the column-level rater seam, conformance to existing swap/GPU-mode
> primitives) is an internal software judgment settled from in-repo evidence and needs no external input. But
> the design leans on two *calibration-validity* claims that fed the §G founder decisions and the honesty
> constraints, and judge-evaluation methodology is a fast-moving external field — so a narrow pass was run on
> exactly those two claims. 624 owns the calibration *methodology* and did its own research pass (arXiv
> 2510.09738); this note stays scoped to what 674's design rests on and does not re-own that. No external code
> or assets were copied — findings are summarised in our own words with source attribution (arXiv IDs/URLs);
> the license-and-notices lane is not implicated. Numbers below are as reported by the cited sources and are
> stated directionally, not adopted as our own measurements.

**Finding 1 — the grader-capability floor is a real, quantified threshold, and a local ~8-9B grader sits
below it.** Recent judge-reliability work places dependable LLM-judging roughly in the **14-32B** range and
reports that sub-~14B models have structural limits (they fail to benefit from richer judging instructions
that larger models exploit), while cautioning that *even frontier* judges are unreliable on hard bias tests
(arXiv 2606.19544 "Reliability without Validity"; arXiv 2506.13639 "How Design Choices Impact Evaluation
Reliability"). **Design consequence:** this *strengthens*, not weakens, the honesty framing — the frontmatter
`principle`'s "as well as an external frontier model would" is contradicted on the capability axis by current
evidence, not merely by caution. It also sharpens §G item 5: model selection should prefer the *highest-
capability* local model that fits the 12 GB single-tenant budget (favouring a ~12-14B at aggressive quant
over a comfortable ~7-8B), or else *explicitly* record the panel as a below-floor, mechanism-demonstrating
calibration rather than a credibility-grade one. This is a founder selection call, not a design gap; the
column seam is indifferent to which model each rater loads.

**Finding 2 — "correlated errors" is a measured, named phenomenon, upgrading §Theorization-D from hypothesis
to grounded risk.** "Nine Judges, Two Effective Votes: Correlated Errors Undermine LLM Evaluation Panels"
(arXiv 2605.29800) measures item-level *effective independence* far below nominal panel size, reports models
agreeing on *wrong* answers a large fraction of the time on some benchmarks, and names the "artificial
hivemind" — homogeneous outputs *within and across* families — with the pointed implication that unanimous
panel agreement is far less diagnostic than it looks and the marginal value of an extra similar judge is near
zero. The prescribed remedy is diversification of *reasoning*, not of *size*. **Design consequence:** two
*small* cross-family local graders are a *weaker* decorrelation guarantee than two frontier cross-family
graders, because hivemind persists across families most strongly among smaller models — so a high
rater-vs-rater κ from a local-only panel is exactly the "spuriously reassuring" outcome the risk warned of.
This is the sharpest evidence-backed argument for the **hybrid panel** (§Theorization-B6: one local grader +
one frontier anchor whenever a single credential exists) as the honest sweet spot, and for always reporting
the local-only number with the correlation caveat attached.

**Finding 3 — the codebase already embodies the right instinct, and the research frontier beyond it is
out of scope here.** The existing machinery already (a) reports **rater-vs-rater agreement as the baseline the
judge's agreement must be read against** (`rater_agreement_report`) — i.e. it measures inter-rater correlation
rather than assuming independence — and (b) uses **dual-order abstain-on-disagreement**, a simple instance of
the "trust or escalate" confidence-gating discipline (ICLR 2025, "Trust or Escalate"). The frontier beyond
this — confounder-aware aggregation that *models* the inter-judge correlation (CARE, Zhao et al. 2025,
reported to cut aggregation error by up to ~25%) — would live in **624's agreement machinery, not 674's
orchestration seam**. Named here as a candidate future refinement for 624; deliberately *not* adopted (out of
this tempdoc's scope, and premature for a rarely-run local calibration).

**Net effect on the design: none structural.** All three findings bear on *which model to pick* and *how
honestly to report the number* — decisions this tempdoc already deferred to §G and to the honesty constraints.
The column-level rater seam, and its conformance to the existing swap primitive and single-tenant-GPU mode
invariant, are unchanged. The research confirms the earlier judgment that the architecture did not need an
external pass, while materially strengthening the honesty caveats the public-facing write-up must carry.

## Pre-implementation confidence probes (2026-07-02) — de-risking before build

> Read-only verification of the design's load-bearing assumptions, run *before* any implementation, to
> convert assumed facts into checked ones. Each probe: result → effect on the design → any newly-surfaced
> scope. All findings are static/code-evidence (cited); the one live behavioural check (Probe E) is
> deliberately deferred (see below). No feature code was written.

**Probe A — can the eval harness drive an arbitrary-model swap with no JVM restart? → YES (primary path B1
viable, zero new Java).** An external loopback caller swaps the served chat model to *any* local GGUF via two
existing REST calls: `POST /api/settings/v2` with `{"llm":{"modelPath":"<abs .gguf>"}}` (persists the path;
no allow-list, no registry restriction — `SettingsController.handleUpdateSettingsV2`), then `POST
/api/ai/runtime/activate` (`AiRuntimeController.handleActivate` → `RuntimeActivationService.runActivate` reads
`getLlmModelPath()` → `applyRuntimeOverrides(..., RESTART_ALWAYS)`) or `POST /api/inference/reload`
(`RESTART_IF_ONLINE`, skips the GPU self-test). Both reachable via the MCP `api_call` passthrough. **Effect:**
the serial-swap rater is two HTTP calls over existing routes — no new endpoint, no Java change. **New scope
surfaced:** (1) the swap *persists* the model path to `UiSettings`, so the orchestration must **save the
original Qwen path and restore it** (incl. on failure/crash) or it leaves the dev-stack pointed at a grader;
(2) `activate` runs a GPU self-test on every call (per-swap latency) — `inference/reload` avoids it but only
restarts if already ONLINE. Both are implementation details, not blockers.

**Probe B — can the stack serve a text-only (no-mmproj) foreign GGUF? → YES, zero code change.** Setting
`llmModelPath` via settings sets `usingLlmModelOverride`, under which `InferenceConfig.fromEnvironment`
*deliberately* nulls mmproj and logs "Starting in text-only mode" (`InferenceConfig.java:160-168`); `--mmproj`
and the VDU slot flags are conditional (`LlamaServerOps.java:223-236`, chat startup is `vduMode=false`); the
always-on flags (`--jinja`, `--metrics`, reasoning toggles) are model-agnostic. The *only* vision assumption
lives in the registry (the sole `LLAMA_SERVER` entry bundles Qwen's mmproj), and the settings-override path
bypasses the registry. **Effect:** a plain Llama/Mistral/Gemma-Instruct grader serves today via the exact same
mechanism as Probe A — A and B are satisfied by one existing path. **New scope:** a text-only grader is not
representable as a *registry-installed* model without adding a component; the design should stage grader GGUFs
via the settings-override path (or add registry entries) rather than assume registry install.

**Probe C — do the two orchestrators collapse into one column seam, incl. abstain? → YES, minimal.**
`run_calibration_dry_run` and `run_cross_family_calibration` share the exact skeleton
(`sample_for_calibration` → `collect_calibration_texts` → per-rater labels → `rater_agreement_report` →
overlay); they diverge only in per-rater label production and cross-family's "drop the whole item if any
grader abstains." A column-producer yielding `bool | None` per sampled key, with the orchestrator dropping
keys where any rater is `None`, reproduces both exactly (heuristics never return `None` → nothing dropped).
**One alignment detail:** columns must be keyed by `sample_key` (all raters already iterate the same
`sample_keys` in order) so the post-hoc abstain-intersection lines up. The 2-rater floor
(`rater_agreement_report` raises `<2`, `NotImplementedError` for `3+`) holds. **Effect:** the refactor is as
small as claimed; no hidden divergence.

**Probe D — does verdict parsing survive a foreign family? → SAFE for a non-reasoning instruct family, zero
client change.** `external_grader.call_grader_once` sends only `max_tokens` (default 4) + `temperature` (0.0),
no thinking-suppression; the Head proxy forwards the body verbatim, so suppression *could* be added if ever
needed. A **non-reasoning instruct** model (Llama-3.x / Mistral / Gemma -Instruct) emits `YES`/`NO` directly
→ `startswith("YES")` holds. A *reasoning* family would be truncated at 4 tokens into garbage. **Effect:**
constrain grader selection to non-reasoning instruct families (§G-5); parsing then needs no change. Full proof
still wants one live call on the real model (folded into the deferred Probe E / first impl checkpoint).

**Probe F — do ≥2 suitable non-Qwen GGUFs exist and fit? → YES.** Registry entries carry `license`/`termsUrl`
per component (download attribution structurally handled — the license-and-notices concern is covered for
model artifacts). Candidates that fit 12 GB as sole GPU tenant and are widely available as GGUF: Mistral-Nemo-
12B-Instruct Q4 (~7 GB, closest to the research ≥12-14B floor), Gemma-2-9B-it Q5 (~6.5 GB), Llama-3.1-8B-
Instruct Q5 (~5.7 GB) — ≥2 distinct non-Qwen lineages. **Effect:** feeds the §G-5 selection decision with
concrete, fitting options; per the research floor, prefer the 12-14B where it fits.

**Probe E — live swap smoke test → DEFERRED to the first implementation checkpoint (deliberate).** The
dev-stack was verified free (`quick_health: running=false`), so E was *eligible*. It is deferred because a
swap of Qwen→Qwen (all that's possible pre-download) would only exercise restart plumbing the static evidence
(Probe A) already establishes; the *valuable* live test — a foreign **text-only** model actually loading and
parsing (Probes B+D end-to-end) — requires a staged second model, which is implementation-phase work. Running
it then tests the real thing rather than a proxy. This honours "static-green ≠ live-working": the live tier is
scheduled, not skipped, and gated at the point it can test the actual behaviour.

### Confidence outcome

All five static assumptions resolved **favorably**, several eliminating feared cost (no Java lifecycle work;
swap = two existing REST calls; text-only serving = no code change). Residual unknowns are all
implementation-detail level, not architecture-level: (1) the swap has not yet been *observed* executing live
(static-strong, unproven); (2) the settings-persist/restore round-trip adds a small robustness surface
(save/restore-on-failure); (3) real foreign-model parsing is unproven until a model is downloaded; (4)
per-swap self-test latency is unquantified. None threaten the column-seam design.

## Readiness assessment (2026-07-02) — confidence + effort sizing

Post-probe readiness for the remaining implementation work:

- **Confidence: 8/10.** All five static load-bearing assumptions (§Pre-implementation confidence probes)
  resolved favorably; the feared Java-lifecycle cost evaporated (swap = two existing REST calls; text-only
  serving = no code change). The −2 is residual *implementation-detail* risk only: the swap is unproven live
  (Probe E deferred to the first impl checkpoint), the settings save/restore-on-failure is an untested
  robustness surface, real foreign-model parsing is unproven until a model is staged, and per-swap self-test
  latency is unquantified. None is architecture-threatening.
- **Difficulty: medium** — a contained, single-module (`scripts/jseval`) Python change with **no Java edits**:
  (1) collapse `run_calibration_dry_run` + `run_cross_family_calibration` into the column seam; (2) a
  serial-swap rater = `POST /api/settings/v2` + `POST /api/ai/runtime/activate` + save/restore original path
  + a `/v1/models` served-model-identity assertion; (3) ≥2 registry entries + model downloads (mechanical);
  (4) a foreign-family parse check. The only real judgment is the abstain/alignment + restore-on-failure
  semantics and the honest reporting of the weaker local-panel number.
- **Recommended model/effort: Sonnet-5, high** (the tempdoc's original call still holds, but the *reason*
  has shifted from "novel Hard-Invariant-adjacent lifecycle design" — the probes removed that — to a
  well-scoped refactor + eval wiring with clear reuse). Opus not warranted unless the first live checkpoint
  surfaces unexpected lifecycle behaviour; the mechanical staging sub-tasks (registry entries, downloads)
  could go to Fable for speed, keeping the seam refactor + honesty logic on Sonnet.

## Files the remaining work will touch (for cross-worktree conflict awareness)

- `scripts/jseval/jseval/utility_judge.py` — the column-seam refactor (primary).
- `scripts/jseval/jseval/commands/utility.py` — CLI wiring for the unified orchestrator.
- `scripts/jseval/jseval/external_grader.py` — likely unchanged (client is already correct); possibly a
  larger-`max_tokens`/suppression option if a non-instruct family is ever chosen.
- `scripts/jseval/tests/test_utility_judge.py`, `test_external_grader.py` — new seam/abstain tests.
- `modules/ui/src/main/resources/ai/model-registry.v2.json` (+ its `modules/configuration` test-resource
  duplicate) — *if* grader GGUFs are staged as registry components rather than via the settings-override path.

## Cross-worktree coordination (2026-07-02) — interference scan

Scanned active worktrees (`git worktree list`), open PRs, and adjacent tempdocs (654-694, modified <5h) for
overlap with the files this work needs. Two items to manage; the rest are clear.

- **656-onramp-investigation (active worktree, launch-blocker, no PR yet) — MEDIUM, re-verify dependency.**
  Edits `RuntimeActivationService.java` + `LlamaServerOps.java` — the exact activation/serving files this
  design *drives via REST but does not edit*. The current hunks are constructor wiring (`workerFeatureCache`)
  + a `MODEL_NOT_FOUND` reason-code mapping + a process-launch change near `LlamaServerOps:502` — none touch
  the arbitrary-path resolution (Probe A) or the conditional-mmproj argv logic at `LlamaServerOps:218-275`
  (Probe B), so the two behaviours this design relies on should survive. Because 656 is launch-priority and
  this work is deferred, 656 will likely merge first: **before implementing, rebase and re-verify Probe A
  (arbitrary-path settings-override swap) and Probe B (text-only serving) against the merged code**; cited
  line numbers may also drift.
- **657-install-modes / model-pack decomposition (launch-blocker) — LOW, avoidable.** Adds a nullable `tier`
  field to `ModelPackage` + `model-registry.v2.json` (both copies) + loader. Overlaps this work *only if*
  grader GGUFs are staged as registry components. **Mitigation already in the design:** stage graders via the
  settings-override path (Probe A/B — no registry edit needed), which decouples from 657 entirely. If registry
  entries are used instead, conform to 657's `tier` tag (`llm`) and expect 657 to land first.
- **673 (sibling) — LOW, explicitly disjoint.** 673 states it is "**not** a redesign of ... `utility_judge.py`
  machinery" and lives in `ratchet_kernel.py` + a gate trigger, reusing `judge_logs` read-only. This work
  refactors only the *calibration orchestrators* (`run_calibration_dry_run` / `run_cross_family_calibration`),
  leaving `judge_logs`' signature intact — so the sibling's reuse is unaffected. No file collision.
- **643-judge-arbitration/-rung, 644-capability/-eval (active worktrees) — LOW, adjacent not overlapping.**
  Heavy churn across `scripts/jseval` (`gates.py`, `artifacts.py`, `ratchet_kernel.py`, `backend.py`,
  `cli.py`, `preflight.py`, `readiness.py`) but **none touch `utility_judge.py`, `external_grader.py`, or
  `commands/utility.py`** — my exact files. One watch item: 644-eval edits `cli.py`; the cross-family command
  is already registered there, so no `cli.py` edit is expected, but confirm at implementation time.
- **Open PRs (#12 CI-attribution, #21/#24 docs, #41-43 dependabot) — NONE.** No overlap with this work's files.
- **ci-latency-667 — NONE.** Touches only a `LlamaServerOps` *test* file; no behavioural overlap.

**Net:** no blocking collision. Sequencing implication — let the two launch-blockers (656, 657) land first,
then rebase and re-run Probes A/B before building; keep grader staging on the settings-override path to stay
off 657's registry changes.

## As-built (2026-07-02) — the code + mechanism-validation slice shipped

Implemented the column-level rater seam and the serial-swap rater exactly as designed above. **Scope
boundary honoured**: this is the code + mechanism the design called for, not the founder-gated credibility-
grade calibration run (§G) — no run was executed, no model was chosen for real use, no registry entry added.

**Shipped** (`scripts/jseval/`, no Java touched, no UI — this is a CLI/eval-methodology tool):
- `utility_judge.py`: `_HeuristicRater` / `_EndpointRater` / `LocalSerialRater` (column-producers) +
  `run_calibration(...)` (the unified sequential-column orchestrator, abstain-drop, `<2`-rater guard,
  unconditional `rater_kind` stamp). `run_calibration_dry_run` and `run_cross_family_calibration` are now
  thin wrappers over it — public signatures and return shapes preserved exactly (verified by the full
  pre-existing test suite passing unchanged, plus new shape-lock tests).
- `LocalSerialRater`: drives the two existing loopback Head-API routes found by Probe A/B (`POST
  /api/settings/v2` to set `llm.modelPath`, `POST /api/ai/runtime/activate` to reload) — save the current
  model → swap → **assert `/v1/models` reports the intended model before grading** (the guard against the
  silent-same-model trap named in §Theorization-D) → label → **restore the original model in a `finally`**,
  warning (not masking) if the restore call itself fails. No new Java, no parallel swap authority — exactly
  the rejected-alternative boundary in §Long-term design.
- `commands/utility.py`: `--graders-config` gained a per-grader `kind` field (`"endpoint"` default,
  `"local-serial"`); the command name/surface is unchanged (`python -m jseval.commands.inventory --check`
  stays clean — no CLI-surface drift).
- 30 new unit tests (seam abstain/ordering/rater_kind, wrapper shape-preservation, `LocalSerialRater`
  swap/restore/failure paths including a not-masking-the-original-exception restore-failure path, a mixed
  local+endpoint panel integration test, and CLI-level coverage for the `kind` field).

**Post-implementation critical-analysis pass** (bidirectional-pass discipline) found and fixed 3 real gaps,
all in the CLI's new `kind`-field wiring (the core rater seam / `LocalSerialRater` design was sound as
implemented):
1. The `local-serial` code path through the actual CLI command was untested end-to-end — added.
2. A misconfigured nonzero `price_per_call_usd` on a `local-serial` grader would have printed a misleading
   nonzero cost estimate for something that can never actually cost money — now rejected loudly
   (`click.ClickException`) rather than silently misreported, consistent with this file's existing
   fail-loud-on-bad-config philosophy.
3. A `local-serial` config missing `model_path` raised a raw `KeyError` instead of a clean CLI error — fixed
   to match every other validation in the same function.

No security/privacy issue was found (every new HTTP call stays loopback-only, per the hard invariant).

**Verification**: 87/87 targeted tests pass (`test_utility_judge.py` + `test_external_grader.py`);
full-suite run is 1412/1412 green once two pre-existing/unrelated sources of failure are excluded — a
missing data file on `main` itself (`correction-eval-queries.v1.json`, logged to the observations inbox,
not this tempdoc's concern) and another session's concurrent, uncommitted, in-progress work on
`test_utility_gate.py`/`commands/gates.py` in this shared worktree (also logged; confirmed unrelated by
deselecting those files and re-running clean). `python -m jseval.commands.inventory --check` is clean.

**Update (2026-07-02, later pass): the live mechanism check is now DONE** for the swap primitive itself —
see §Pre-implementation confidence probes (remaining work) below. A same-model round-trip (Qwen→Qwen,
executed twice) against the real running dev-stack proved the full `settings/v2` → `activate` → poll →
`/v1/models`-assert cycle end-to-end, including a real graded HTTP call through the Head's OpenAI-compat
proxy. **What remains genuinely open** is narrower than before: proving a *foreign, different-lineage*
GGUF serves correctly (text-only mode, no mmproj) — that still needs a second model staged on disk, out of
this tempdoc's own scope (a founder model-selection call, §G item 5). The swap *mechanism* is no longer an
open question; only the *foreign-model-specific* behavior is.

## Post-implementation polish, extension, and practicality ideas (2026-07-02)

> Pure research + ideation — no code changed by this pass. This audience is developers/agents who
> occasionally run a cross-family calibration, not JustSearch end-users — `LocalSerialRater` has no
> product-UI surface, so "UX" below means CLI/developer ergonomics. None of this is committed work;
> the goal was open-ended ("all improvements are viable, no rush"), so these are ranked by value, not
> scheduled. A short, targeted external-research pass (GGUF file format, prior art in local-model eval
> tooling) grounds two of the ideas below in verified fact rather than guesswork; sources cited inline.

### Highest-value idea: a standalone swap smoke-test command

The single practical gap that has come up twice in this tempdoc's own history: the live mechanism check
(does the swap → text-only-serve → parse → restore cycle actually work end-to-end) remains undone,
blocked on needing a full calibration run's worth of setup (`judge-overlay.json`, a staged sample, ≥2
graders) to exercise. A small new command — e.g. `jseval utility-judge-local-swap-smoketest --model-path
<gguf>` — that does *just* `LocalSerialRater`'s swap/assert/restore cycle against one synthetic
question/reference/candidate triple, with no overlay and no full sample, would let a developer (or a
future agent) validate the mechanism in seconds instead of needing an entire eval run staged first. This
directly unblocks the deferred live-validation tier at near-zero cost, and doubles as an ongoing
diagnostic ("is my dev-stack's active variant swappable at all") independent of any calibration.

### DX friction points found by walking the actual developer workflow

- **No shipped example config.** `--graders-config`'s shape (including the new `kind` field) is only
  documented in `--help` text; a developer has to reverse-engineer the JSON from prose. A single example
  file (e.g. `scripts/jseval/examples/local-cross-family-graders.example.json`, one endpoint + one
  local-serial entry) would remove that friction cheaply.
- **No time-cost estimate.** `estimate_cross_family_cost` reports a dollar figure ($0 for local graders)
  but nothing about wall-clock cost — a developer has no upfront signal that a local-serial panel could
  take many minutes (each rater's turn pays a full stop→reload→self-test→restore cycle). A parallel, pure
  `estimate_local_serial_time_cost(n_local_raters, ...)` function — same shape and spirit as the existing
  cost estimator — would close this gap using the same "print and confirm before any real call" pattern
  already established.
- **Silent during a long-running turn.** Nothing prints progress while a `LocalSerialRater` is mid-turn
  (swapping, labelling up to ~40 items). `click.progressbar` is already available (Click is an existing
  dependency) but unused anywhere in this codebase — a genuinely free addition, not a new dependency,
  though it would be a new UI *pattern* for this CLI, worth a quick look at how other `jseval` long-running
  commands (if any) currently report progress before introducing a new convention.
- **The double-swap-per-rater cost, found in the prior critical-analysis pass, could become an explicit
  choice rather than a fixed default.** A `keep_loaded_between_raters: bool` (default `False`, the current
  safe behavior) would let a caller who understands the tradeoff opt into the tempdoc's originally-assumed
  "≈2 swaps total" shape once the time-cost estimate above makes that tradeoff visible instead of implicit.

### Capability-floor extension — grounded in a verified, dependency-free feasibility check

The prior critical-analysis pass flagged that no capability floor is enforced or even surfaced anywhere —
`LocalSerialRater` accepts any `model_path` with no signal to the caller about whether the chosen model is
likely to be a competent judge (the external-research pass earlier in this tempdoc found published
guidance suggesting a ~14-32B floor for reliable LLM-judging, with local ~8-9B graders below it). A
worthwhile extension: **a small, dependency-free GGUF metadata prober.**

- **Feasibility, verified by reading the actual GGUF spec** ([ggml-org/ggml gguf.md](https://github.com/ggml-org/ggml/blob/master/docs/gguf.md)):
  the file's header (24 bytes: magic, version, tensor count, metadata KV count) and metadata key-value
  store are read *before* any tensor data — a pure-`struct`/`io` Python function (no new dependency; the
  `gguf-py` package exists but would be a heavier addition than needed here) can extract
  `general.architecture` and `general.size_label` in an estimated 80-120 lines, without loading gigabytes
  of tensor data. Caveat, also verified: **no standard `parameter_count` key exists in the GGUF spec**, and
  `general.size_label` (e.g. `"8B"`, `"8x7B"`) is optional/not guaranteed present — so this is a
  best-effort signal, not a guarantee, and must fail open (print "unknown" and proceed) rather than block.
- **A VRAM pre-flight check comes almost for free alongside it**, using only the file's on-disk size (no
  GGUF parsing needed at all) plus a formula found during the same research pass: quantized file size +
  ~15-20% overhead for KV cache/runtime approximates the real VRAM footprint. Combined with the already-
  known `VramRequirements.COMFORTABLE_VRAM_BYTES` threshold this codebase already has (Java-side, not
  currently reachable from Python), this could catch "this won't fit" before a live swap attempt fails deep
  into a run, rather than after.
- Both together would slot into the *existing* "print and confirm before any real call" pattern
  (`estimate_cross_family_cost`'s own dry-run discipline) — a natural, additive extension of a pattern
  that's already right, not a new one.

### Prior-art check — validates the design rather than suggesting a different one

A search for how other local/eval tooling handles sequential local-model swapping found
[**llama-swap**](https://github.com/mostlygeek/llama-swap) — a maintained, dedicated open-source VRAM
orchestration tool for swapping models across llama.cpp/vLLM/Ollama backends. Its existence confirms two
things: (1) "swap-based local judge/model panels under one GPU" is a recognized, common problem in the
wider ecosystem, not a fringe need this project invented — reassurance that the underlying premise here is
sound; and (2) it reinforces, rather than overturns, this tempdoc's own earlier decision (§Long-term
design, "Explicitly rejected") to keep using the existing in-repo swap primitive instead of adopting a new
external dependency — llama-swap's own docs describe it as suited to *concurrent, request-routed* serving
workloads, not this project's narrow, rarely-run, offline-calibration case. No other framework surveyed
(DeepEval, lm-evaluation-harness, Langfuse) implements a "column-producer rater" abstraction equivalent to
what this tempdoc designed — the seam appears to be a genuine, project-specific synthesis rather than a
known pattern that could simply be adopted off the shelf.

## Status (updated)

Open — **long-term design settled, de-risked via pre-implementation probes, and the code + mechanism-
validation slice implemented + critically reviewed (2026-07-02)**; see §As-built above. The one remaining
tier is the live dev-stack check (blocked on stack ownership, not on any known code issue). The
founder-gated credibility-grade calibration run itself (§G: model selection, whether to spend the run) is
explicitly out of this slice's scope and remains open. **A post-implementation polish/extension/practicality
research pass (above) found one high-value idea (a standalone swap smoke-test command, which would also
unblock the deferred live-validation tier), several DX friction points, and a verified-feasible
capability-floor/VRAM-preflight extension — all recorded as ideas, none implemented, no rush per the
open-ended brief.** A subsequent pass (below, §Long-term design for the remaining work) turned that idea
list into a settled design and found it requires no new architecture — every item is an extension of a
seam this codebase already uses, confirmed against adjacent tempdocs (645, 640) and direct code reading.

---

## Long-term design for the remaining work (2026-07-02)

> General shape, not implementation — per the theorization brief. Investigated first: does an existing
> design already cover this, so the remaining work extends it instead of forking a parallel one. Read
> tempdoc 645 (the `cli.py` split — establishes the `commands/*.py`-thin-wrapper / logic-module boundary
> this tempdoc's own `commands/utility.py` already follows) and tempdoc 640 (the perf-budget design —
> independently names the same "extend the canonical seam as a governed projection, never fork a parallel
> subsystem" culture this tempdoc's own §Long-term design already applied). 670-673 were already read in
> depth earlier in this tempdoc's own history (§Cross-worktree coordination); nothing newer exists (674 is
> still the highest-numbered tempdoc as of this pass).

### The reframing: the remaining-work list is one need, not five features

§Polish and extension ideas (above) reads as five separate items — a smoke-test command, a time estimate,
a capability/VRAM check, progress reporting, a swap-count knob. Read together, they are one need stated
five ways: **give the caller enough information to decide whether and how to run a `LocalSerialRater`
panel, before and during the run** — using the same decision-support seams this codebase already has for
exactly this kind of call, not new ones. Four existing seams already cover every item:

1. **The dry-run, print-and-confirm-before-commit seam.** `estimate_cross_family_cost` is not merely "the
   cost function" — its own docstring states the general shape: "meant to be printed and confirmed BEFORE
   any real grader call is made." Time-cost, VRAM-fit, and capability-floor signals are the *same kind* of
   fact (decision-support before an irreversible-adjacent, expensive-adjacent action) as dollar cost, just
   on axes dollars don't cover for a local grader. They belong inside the *same dry-run report*, gated by
   the *same* `--yes` split — not as three new, separately-gated mechanisms.
2. **One pure projection function, many consumers — never re-derived ad hoc.** `preflight.py`'s
   `project_realized_capability` already states this codebase's rule explicitly: "the one... projection...
   instead of each re-deriving its own predicate" (citing AHA and the representation-drift class, tempdoc
   644/553). The subject differs (static GGUF file metadata vs. live backend status), but the *shape* is
   identical: a GGUF-metadata prober should be ONE pure, standalone function, consumed by the dry-run report
   now and by anything else that later needs the same fact — never duplicated.
3. **Library code stays click-free; progress reporting is `logging` + an optional callback, decided at the
   library layer.** Confirmed twice: (a) tempdoc 645 establishes that `commands/*.py` are thin Click
   wrappers and the modules they call (`utility_judge.py`, `external_grader.py` — verified, neither imports
   `click` today) are pure logic; (b) `readiness.py` already has the exact shape needed for a long operation
   that wants to report progress without knowing about Click — stdlib `logging` at a throttled interval, plus
   an optional structured callback parameter for a caller that wants more. Progress during a
   `LocalSerialRater` turn should follow this **existing** shape, not `click.progressbar` (a UI primitive
   this codebase has never used) and not `click.echo` calls added inside the logic layer (a boundary
   violation, not a style nit — it would make `utility_judge.py` depend on the CLI framework it has
   deliberately never depended on).
4. **A CLI command that is a thin wrapper over already-correct logic, not a new mechanism.** The smoke-test
   idea does not need new verification machinery — `LocalSerialRater.label_sample` already *is* the
   swap→assert→label→restore cycle; a smoke-test command is a new *door* into that existing room (call it on
   one synthetic item, report success/failure), matching exactly the thin-wrapper shape 645 already
   established for every other `commands/*.py` entry.

The `keep_loaded_between_raters` idea needs no seam at all beyond what already exists: it is a single
additional optional parameter on the already-built `LocalSerialRater`, the same shape as its existing
`backend_base_url`/`timeout_sec` parameters — not a new subsystem.

### What is genuinely new, and how big

Given the above, the honest scope is: **no new architecture, one new small pure function, and several
small extension points on already-built code.** Concretely (still general, not a spec): a GGUF-metadata
prober is new *content* (nothing in this codebase parses GGUF today) but not new *shape* — it is exactly
"one pure projection function" per the seam above. Everything else — the extended dry-run report, the
progress callback, the smoke-test command, the swap-count knob — is a bounded extension of code that
already exists and already has the right shape. This matches the AHA test the original design already
applied: none of these five items shares a reason to change with a *different* subject closely enough to
justify inventing a shared abstraction beyond what's named above; equally, none of them requires a new
architectural layer, because the layers they need (dry-run report, one-projection-many-consumers,
click-free logic + logging/callback, thin CLI wrapper) are already standing.

### Why NOT one bigger "local-serial preflight" mega-function

A tempting shortcut is to fold dollar-cost, time-cost, VRAM-fit, and capability-floor into one combined
report function. Rejected: dollar-cost applies uniformly to *every* grader kind (a fixed formula over
sample size and price), while time/VRAM/capability are specific to `LocalSerialRater` graders only (they
need the model path, and don't apply to a live endpoint at all) — different subjects, different reasons to
change, matching `estimate_cross_family_cost`'s own existing separation of concerns. The right shape is a
**second**, local-serial-specific pre-flight report, printed *alongside* the existing dollar-cost report in
the same dry-run block — not a merge of the two, and not three separate reports either (time/VRAM/capability
share one subject — "is this specific local model workable" — and belong together).

### Public-repo honesty note

None of this is compliance/certification framing, and the capability-floor signal must stay advisory, not
a hard gate: the GGUF `general.size_label` field is optional and not guaranteed present (verified against
the GGUF spec, §Post-implementation polish above), so the check must fail open ("unknown, proceeding") not
block a caller who supplies a model the prober can't classify. Any future public write-up of this feature
should describe the local-serial path as "cheaper and weaker, with an advisory capability check," not as a
verified-adequate judge — consistent with the honesty constraints already carried into the original design.

## Design reach for the remaining-work design (2026-07-02)

### The principle this sharpens (not a new one — the original §Design reach principle, seen from the
### operational-maturity angle)

The original design's principle was "a source-agnostic aggregator needs a source seam at the granularity
the scarcest shared resource dictates." This pass surfaces its natural companion, visible only once
day-two usability is considered: **"decision-support information about an expensive-adjacent operation
belongs in a pre-flight, print-and-confirm-before-commit report; observability during that operation
belongs in a logging-and-callback seam at the library layer, never inline in CLI-coupled code."** Both
halves are already-adopted conventions in this exact codebase (`estimate_cross_family_cost`'s own stated
philosophy; `readiness.py`'s `on_snapshot`/logging shape) — this pass did not invent them, it recognized
that the remaining-work list is an instance of both, not a reason to invent new ones.

### Where else this would apply

- **Pre-flight decision support.** Any future jseval operation with a hidden cost axis beyond dollars —
  time, disk, VRAM, or model-capability risk — should extend the existing dry-run-report seam rather than
  print an ad hoc warning inline. The credibility-vs-cost frontier named in the original §Theorization-F
  is exactly the kind of multi-axis decision this seam exists to surface.
- **Library-layer observability.** Any future long-running jseval operation (a slow local recompute, a
  large download, another swap-based mechanism) should use the `logging` + optional-callback shape
  `readiness.py` already established, rather than each long operation inventing its own progress UI.

### Does existing code already violate either half?

Partially checked, not fully verified — flagging rather than asserting. `run_cross_family_calibration`'s
own predecessor (before this tempdoc's fix) was itself an instance of the *first* half being ignored (no
pre-flight signal beyond dollars) — already fixed by the settled design's dry-run report existing at all;
the remaining gap is only that it doesn't yet cover the local-serial-specific axes, which is exactly what
this pass scopes to add. Whether any *other* long-running jseval command (e.g. corpus downloads in
`corpus_fetch.py`) currently reports progress silently or ad hoc was not checked in this pass — worth a
look next time that code is touched, not fixed here (out of this tempdoc's scope; log to the inbox if
confirmed).

### The deliberate stop

A general "pre-flight report registry" or "progress-callback framework" spanning all of jseval is
recognized here as a candidate generalization, and deliberately **not** built — this tempdoc's own
remaining work needs exactly one new local-serial-specific report and one use of the existing
logging/callback shape, not a framework serving hypothetical future callers. Recording the principle's
reach without erecting the general structure is the same discipline the original design already applied
(§Design reach, "the deliberate stop").

### Title check

The frontmatter title ("lift the calibration orchestrator's rater boundary from per-item to per-column...")
still accurately describes this tempdoc's core contribution; the remaining-work design above extends usage
of that seam, it does not change what the tempdoc is fundamentally about. No title change.

---

## Pre-implementation confidence probes (remaining work) (2026-07-02)

> De-risking pass for §Long-term design for the remaining work, before any of it is built. Five
> uncertainties were identified; all five resolved, three of them materially refining the design (not
> merely confirming it), and the biggest one — the swap mechanism has never been proven live — is now
> closed. No feature code was written; this is verification only.

**Probe A — GGUF byte-layout, verified against the real local file, not just web research. CONFIRMED,
plus a stronger result than expected.** A throwaway stdlib-only parser (24-byte header + typed KV walk)
read all 45 metadata pairs of `models/Qwen_Qwen3.5-9B-Q4_K_M.gguf` cleanly, zero errors. `general.
architecture='qwen35'`, `general.size_label='9B'` — **present**, not absent, in this real, current model
(the spec only guarantees it's optional; this real example has it). **Effect:** the researched byte layout
is fact, not assumption; the prober is real-file-proven, not merely spec-plausible.

**Probe B — does `LocalSerialRater.label_sample`'s current shape actually fit a progress hook and a
smoke-test entry cleanly? PARTIALLY — one real refinement.** The smoke-test claim is fully confirmed: `
label_sample(self, texts: dict)` needs nothing else, so a one-synthetic-item call is a genuine thin
wrapper, no new mechanism. The progress-callback claim needs a small correction: the grading loop is
currently a **dict comprehension** (`{k: eg.call_grader_dual_order(...) for k, t in texts.items()}`) with
no per-iteration hook point — adding progress reporting means converting this to an explicit loop first, a
small but real code-shape change, not a purely additive parameter as originally implied.

**Probe C — does `readiness.py`'s callback/logging shape transplant onto `LocalSerialRater` as-is? NO —
needs adaptation, not verbatim reuse.** `_SnapshotCallback = Callable[[float, dict], None]` confirmed as a
simple typed alias — but `_PROGRESS_LOG_INTERVAL = 15` polls (~30s at a fixed 2s poll interval) is tuned
for a *multi-minute polling* loop, a fundamentally different cadence than grading a handful of items after
one swap. **Effect:** the *shape* (optional typed callback + throttled default logging) is the right seam
to extend; the specific poll-count throttle is not — a simpler per-item or per-swap-boundary log variant
is the correct adaptation, not a literal copy.

**Probe D — how would VRAM actually be read from Python? BETTER THAN DESIGNED: an existing function
already does this.** `jseval/env_fingerprint.py`'s `_probe_gpu()` already shells out to `nvidia-smi` and
returns `mem_used_mb`/`mem_total_mb`, fail-open (`{"available": False}`) on failure — a working, precedented
utility this design hadn't identified. **Effect:** the VRAM-fit half of the local-serial pre-flight report
should reuse this existing function (currently private/internal to `env_fingerprint.py`) rather than invent
a fresh `nvidia-smi` shell-out, an even better "extend, don't fork" outcome than originally designed.

**Probe E — has the swap mechanism ever actually worked, live? YES — now proven, not just plausible.**
Dev-stack was free; ran the full cycle against the real backend: `preflight` → `start` → `ai_activate`
(8.7s) → confirmed `/v1/models` reports Qwen → drove the exact swap sequence `LocalSerialRater` uses
(`POST /api/settings/v2` → `POST /api/ai/runtime/activate` → poll `/api/ai/runtime/status`) twice in a row,
both completing cleanly (`state: completed, result: passed`, ~21s each) → confirmed `/v1/models` correctly
reported the served model after each cycle → ran one real dual-order-shaped grading call through the
Head's OpenAI-compat proxy, which returned a correctly-parsed `"YES"` for a genuinely correct answer →
`stop`. Two additional findings of note, both strengthening confidence: (1) `/v1/models` reports the
model's **exact filename** (`Qwen_Qwen3.5-9B-Q4_K_M.gguf`) as its `id`, confirming `_assert_serving`'s
substring-match design is correct against the real server, not just plausible (previously an open risk —
"the model id shape isn't pinned anywhere"); (2) `/v1/models`'s `data[].meta` unexpectedly exposes
`n_params` directly (8,953,803,264 for this model) — a free, exact, POST-swap capability cross-check,
complementing (not replacing) the GGUF-file PRE-swap prober from Probe A, since it's only available once a
model is already loaded.

### Confidence outcome

All five probes resolved. Two (A, E) simply confirmed the design with hard evidence, closing the two
biggest open questions (byte-layout correctness, mechanism-actually-works). Three (B, C, D) refined the
design in small, concrete ways: the progress hook needs a minor loop restructure first (B); the callback
shape should adapt its throttle logic, not copy it verbatim (C); and the VRAM check gets simpler by reusing
an already-existing function neither this design nor its author had found before this probe (D). None of
the refinements are architecture-level — they sharpen the same four-seam design from §Long-term design for
the remaining work, they don't replace it.

## Readiness assessment (remaining work) (2026-07-02)

- **Confidence: 9/10.** Every load-bearing assumption is now either directly measured against real
  artifacts (the GGUF file, the live backend) or corrected in place where it was slightly off. The swap
  mechanism — the single biggest standing unknown across this entire tempdoc's history — is now proven,
  not inferred. The remaining point of uncertainty is genuinely small: whether a *foreign, differently-
  shaped* GGUF (different architecture/tokenizer/chat-template conventions than Qwen) serves and parses as
  cleanly as this same-model round-trip did — unknowable without a second model staged, which is outside
  this tempdoc's own scope to decide (a founder call).
- **Difficulty: low-to-medium**, lower than the original implementation slice. Every remaining piece is a
  small, additive extension of code that already exists and already has the right shape: one new pure
  function (the GGUF prober, ~80-120 lines, stdlib-only, already validated against a real file), one small
  loop-shape change plus a typed callback parameter (progress reporting), one function reused from
  `env_fingerprint.py` (VRAM), one thin new CLI command (the smoke-test, reusing `LocalSerialRater`
  verbatim), and one optional constructor parameter (`keep_loaded_between_raters`). No Java, no new
  dependency, no architectural change.
- **Recommended model/effort: Sonnet-5, medium effort.** Lower than the original implementation slice's
  "Sonnet-5, high" — that slice carried genuine novel-design risk (the rater seam itself, the swap
  mechanism's live behavior, both now resolved); this slice is mechanical extension of proven, already-
  understood seams. Medium effort is enough headroom for the one place judgment still matters (adapting
  `readiness.py`'s throttle logic to a much-lower-iteration-count case, per Probe C) without needing high
  effort or Opus-level novel design. Fable-tier is not recommended — the GGUF byte-parsing and the
  settings-merge/restore-on-failure paths are exactly the kind of "small mistake compounds silently" code
  this repo's own postmortems (`audit-without-test`, `wrong-gate`) warn against skimping verification on.

---

## As-built (2026-07-02, second slice) — the remaining-work design shipped

Implemented all five items from §Long-term design for the remaining work, following the sharpened design
from §Pre-implementation confidence probes (remaining work) exactly. **Scope boundary honoured**: no Java,
no registry entry, no model download/staging, no founder-gated run — same boundary as the first slice.

**Shipped** (`scripts/jseval/`):
- `jseval/gguf_probe.py` (new) — `probe_gguf(path) -> GgufInfo`, the pure, stdlib-only GGUF header/metadata
  reader from Probe A, transplanted verbatim from the validated experiment. Fails open on any unparseable
  field; raises only on a genuinely wrong file (bad magic bytes).
- `env_fingerprint.py` — added `probe_gpu_vram()`, a thin public alias over the existing private
  `_probe_gpu()` (Probe D's finding: reuse, don't reinvent). No rename of the existing function, no
  behavior change to `capture_env_fingerprint`'s own use of it.
- `utility_judge.py` — `LocalSerialRater`: the grading loop is now an explicit `for` loop (was a dict
  comprehension, per Probe B) with `logging` calls + an optional `on_progress` callback at swap-start/
  swap-complete/each-item/restore-start/restore-complete/restore-skipped boundaries — a `_ProgressCallback`
  shape adapted from `readiness.py`'s `_SnapshotCallback` per Probe C's finding (no poll-count throttle;
  fires every event, since the iteration count here is orders of magnitude smaller than a multi-minute
  poll loop). Added `keep_loaded_between_raters` (default `False`, preserves the existing safe restore-
  every-time behavior). Added `estimate_local_serial_preflight(...)`, the local-serial-specific sibling of
  `external_grader.estimate_cross_family_cost` — file size, architecture/size_label (via `gguf_probe`),
  a VRAM-fit verdict (via the new `probe_gpu_vram`, +20% overhead rule-of-thumb from the earlier research
  pass), and a swap-count/time estimate seeded by Probe E's real measured swap latency (~21s/swap).
- `commands/utility.py` — the pre-flight report prints in `cmd_utility_judge_cross_family`'s existing
  dry-run block, alongside the dollar-cost estimate, gated by the same `--yes` flag (not a new commit
  gate). A new `_echo_local_serial_progress` function is the CLI-layer half of the progress callback —
  `utility_judge.py` still imports no `click` (the tempdoc-645 boundary held throughout). A new command,
  `utility-judge-local-swap-smoketest`, is a thin wrapper (~40 lines) over `LocalSerialRater` on one
  hardcoded synthetic item — no overlay, no calibration sample.
- 24 new unit tests: `test_gguf_probe.py` (real-file + synthetic-byte edge cases) and additions to
  `test_utility_judge.py` (`keep_loaded_between_raters`, `on_progress` event ordering, the pre-flight
  function against both the real GGUF and failure/edge cases, the CLI pre-flight print, the smoke-test
  command's pass/fail paths).

**Verification — every tier, not just static:**
- Unit tests: 101/101 across `test_utility_judge.py` + `test_external_grader.py` + `test_gguf_probe.py`.
- Full suite: 1426/1426 green (deselecting only the other session's known-unrelated `test_utility_gate.py`
  WIP), with the same two pre-existing/unrelated failures already logged (missing data file on `main`).
- Command inventory: regenerated (`--write`, 72→73 commands) — confirmed additive-only via `git diff`
  (the new smoke-test command plus two already-present commands from another session's concurrent WIP in
  this shared worktree; nothing of theirs was touched or reverted).
- **Live, against the real running dev-stack** — the closest thing this CLI-only feature has to UI
  validation: started the backend, activated the AI runtime, then ran the actual new
  `utility-judge-local-swap-smoketest` command (through the real `jseval` CLI entrypoint, not hand-driven
  API calls) against `Qwen_Qwen3.5-9B-Q4_K_M.gguf`. Result: `SMOKETEST PASSED in 31.2s` — swap (16.1s),
  grade (verdict=True, correctly graded a genuinely correct answer), and restore (13s) all succeeded; both
  the `logging` output and the `click.echo` progress lines fired in the expected order; `/v1/models`
  confirmed the model was correctly restored afterward. Stopped cleanly.

No PR opened (per instruction — not until told).
