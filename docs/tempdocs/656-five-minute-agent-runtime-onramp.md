---
title: "Five-minute agent/runtime onramp: make the first successful developer path deterministic by finishing the AI capability's runtime-manifest/reason-code wiring, not by adding a new diagnostics subsystem"
type: tempdocs
status: "open — investigation + design theorization pass 2026-07-01 (see §Investigation, §Design theorization). Triggered by a live incident: 2 agents independently reported missing models / missing llama-server after the go-public cutover. Root cause traced to the Inference capability being the one capability that doesn't route its failure reasons through the existing LifecycleReasonCode/RuntimeManifest substrate. Design conclusion: extend that existing substrate (§D3/§D4), do not build a parallel doctor subsystem. No implementation started."
created: 2026-06-28
updated: 2026-07-01
category: developer-experience / activation / mcp / diagnostics
related:
  - 654-local-runtime-contract-and-product-center
  - 655-mcp-conformance-and-capability-policy
  - 657-install-modes-and-model-pack-decomposition
  - 658-retrieval-inspectability-and-diagnostic-bundle
  - 650-go-public-capability-descriptor-truthfulness
  - 501-runtime-manifest-design
  - 634-go-public-cutover-transition
  - docs/explanation/23-runtime-manifest.md
  - docs/reference/contributing/agent-guide.md
  - docs/reference/mcp-production-server.md
  - docs/reference/model-inventory.md
  - scripts/verify-prerequisites.mjs
  - scripts/dev/dev-runner.cjs
  - modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java
  - modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 656 - Five-minute agent/runtime onramp

## Purpose

The close-project research suggests that lighter peers win trust quickly because a developer can
install, connect, inspect status, index something small, and get one useful result without learning
the whole architecture. JustSearch is more capable, but its first useful success path is heavier and
less obviously deterministic.

This tempdoc asks a next agent to design the smallest credible developer onramp for the agent/runtime
wedge: demo corpus, no-model or small-model path, doctor diagnostics, MCP attach instructions, first
query, expected status output, and failure explanations.

## Boundary

This is not retrieval-quality work and not a benchmark-release replacement. It should not tune
models, fusion weights, or rerankers. It should focus on activation economics: how a developer gets
to a trustworthy first result fast enough to keep evaluating the project.

This also should not become a marketing README rewrite. It may propose README/canonical-doc changes
later, but first it should design the actual runnable path and the evidence that proves it works.

## Prior owners to read first

- `local-runtime-contract-and-product-center` once available, because the onramp needs a stable object
  to introduce.
- `mcp-conformance-and-capability-policy` once available, because MCP setup should not drift from the
  supported-client matrix.
- `go-public-cutover-transition` for current public launch constraints.
- `docs/reference/mcp-production-server.md` for current client setup.
- `docs/reference/contributing/agent-guide.md` for contributor bootstrap expectations.

## First questions

- What is the fastest path that exercises real JustSearch value without a full 9 GB model path?
- Should the onramp use a bundled demo corpus, user-supplied folder, or both?
- What should `justsearch doctor` or an equivalent diagnostic report check?
- Which steps need screenshots/UI proof, and which need CLI/API proof?
- What exact "first success" should the onramp promise: status only, search result, cited answer, or
  MCP-driven answer?

## §Investigation (takeover pass, 2026-07-01)

Entered via a dedicated worktree (`worktree-656-onramp-investigation`), per branch-safety rules — no
work done on `main`. This pass is root-cause investigation triggered by a live symptom (2 agents
independently reported missing models / missing llama-server in this environment after the go-public
cutover), cross-referenced against recent tempdocs (through 666) and `docs/observations.md`. No
design or implementation performed — this section records what is verifiably true in the code/docs
today, and where the tempdoc's own framing needs adjusting before a next agent designs the onramp.

**Caveat:** the "close-project research" cited in Purpose (competitor/peer analysis) lives in the
private strategy sidecar, not mounted in this public-repo worktree — I could not re-verify its
specific claims. Grounded the "lighter peers win trust quickly" premise instead against public
examples (e.g. `shinpr/mcp-local-rag`: "one npx command, zero setup... works fully offline after the
first model download") — the competitive pattern is real, not just an internal assumption.

### A. Confirmed root causes of the immediate incident

1. **`docs/reference/model-inventory.md` asserts something false about the public repo.** Its
   "Repo-Root Model Presence" section (lines 331-351) states "All search-runtime models are now
   tracked in git under `models/`" and tables every `.onnx` file as present with a size and status.
   This is **not true for `justsearch-public`**: `git ls-tree -r HEAD -- models/` contains zero
   `.onnx` entries (confirmed directly — `git cat-file -e HEAD:models/onnx/citation-scorer/model.onnx`
   fails with "does not exist in HEAD"). The doc has exactly one commit in this repo's history
   (`29579e5`, 2026-06-25, the squashed "initial public release"), i.e. it was carried over verbatim
   from the private repo (where the claim was true — LFS blobs were genuinely git-committed there,
   confirmed via `git lfs ls-files` in `F:\JustSearch`) and never reconciled against tempdoc 634 scope
   #2's decision to **exclude the 9 LFS model blobs from the public snapshot**. Any agent or
   contributor consulting this canonical reference — the single named source of truth for model
   identity — gets a false "the binaries are already here" signal. This is the highest-confidence
   single finding of this pass: it's dateable, git-verifiable, and directly explains a "why does the
   doc say X is present when it isn't" confusion.

2. **No path acquires models or `llama-server.exe` outside the Tauri installer build.** In
   `modules/ui/build.gradle.kts`, the tasks that actually fetch model/runtime binaries
   (`stageOnnxModels` line 412, `stageLlamaServer` line 860, which `dependsOn`
   `stageLlamaServerFromPrebuilt` → `downloadLlamaServerPrebuilt`) are wired as dependencies **only**
   of `bundleSidecarResources` (line 1386-1393) — the Tauri sidecar packaging step invoked by
   `npm run tauri build --bundles nsis`. But `README.md` and `CONTRIBUTING.md` both tell contributors
   the opposite: build/run needs "no GPU, no Rust toolchain, and no model download," and the
   documented run command is `./gradlew.bat :modules:ui:run`. That path never touches either staging
   task. The only model-acquisition UI ("Install AI") lives inside the Tauri webview, which isn't
   running under `:modules:ui:run`. A contributor or agent following the docs literally gets a Head
   process with no models and no `llama-server.exe`, and no doc names the gap or a remedy.

3. **`scripts/verify-prerequisites.mjs` — the closest thing to a "doctor" today — has a live,
   undiscoverable, partially-broken check.** It is not referenced from `README.md` or
   `CONTRIBUTING.md` at all — only from `docs/reference/contributing/agent-guide.md` (internal-agent
   doc, scoped to "before AI quality runs") and `docs/reference/model-inventory.md`. So it isn't
   somewhere a new contributor or an onramping agent would find it. Of its model checks: the
   Qwen3VL-naming drift that `model-inventory.md`'s own "Stale script references" table (lines
   317-329) once flagged has **already been fixed** (verified: lines 297-309 now read the filename
   live from `model-registry.v2.json`'s `chat` package instead of hardcoding a stale name) — that part
   of the doc's drift table is now itself stale and should be struck. But the **citation-scorer path
   check is still wrong**, exactly as that same table says: line 343 hardcodes
   `models/citation-scorer/ms-marco-MiniLM-L2-v2`, while the actual registry `targetDir` is
   `onnx/citation-scorer` (confirmed in `model-registry.v2.json` line 191) and the real model is a
   different cross-encoder (`ms-marco-MiniLM-L-6-v2`, not "L2-v2"). This check will warn "missing"
   even on a fully correct install. It's `required: false` (warn, not fail) so it doesn't block, but
   it's a standing false-positive in the one script whose entire job is "tell me what's missing."

4. **The default dev-stack clean mode silently deletes the installed GPU runtime.**
   `scripts/dev/dev-runner.cjs::cleanDataDir` (line 253) soft-clean keep-set (line 269-274:
   `config, index, watched_roots.json, ui, models, installed-packs.v1.json, policy.v1.json,
   gpl-training-triples.ndjson, gpl-eval-snapshot.json`) does **not** include `native-bin`. Soft is the
   documented default for `dev_start --clean`. So every default dev-stack restart deletes
   `{dev-data}/native-bin`, wiping an Install-AI'd cuda12 GPU llama-server variant (~3 GB), producing
   exactly a "missing llama-server" / "Variant not installed: cuda12" symptom on the next activation.
   This is **not a new finding** — it's already logged in `docs/observations.md` (line ~405,
   2026-06-20) with the fix identified (add `'native-bin'` to the keep set) but not applied as of this
   pass. Directly relevant to this tempdoc because it's a concrete, still-open instance of exactly the
   failure class 656 is about.

5. **Worktree dev-stacks need an undocumented manual copy step.** Also already in
   `docs/observations.md` (line ~167, 2026-05-19): `dev-runner.cjs` serving frontend from the main
   worktree when invoked via the MCP dev tools means an agent working in a *different* worktree must
   manually copy `native-bin/llama-server/variants/cuda12/` plus `.dev-data/{inference-model-id.txt,
   ui/settings.json}` from main into the worktree's own data dir — the only place this is written down
   is a one-line pointer to an old tempdoc's appendix, not `agent-guide.md` or any onramp-facing doc.
   If either reporting agent was working from a worktree rather than the main checkout, this is a
   direct, independent explanation for "missing llama-server" that has nothing to do with the public
   cutover at all — a pre-existing gap the cutover didn't create but that the onramp work should still
   close.

6. **No `justsearch doctor` (or equivalent) exists anywhere yet.** Confirmed via repo-wide search —
   this part of 656's Purpose is still fully open; nothing has been built toward it. The nearest analog
   is `verify-prerequisites.mjs`, which (per #3) is scoped for a different audience (pre-AI-quality-test
   checks, not "can I run the app") and isn't wired to be discoverable by the audience 656 is designing
   for.

### B. Where this pass thinks the tempdoc's own framing needs adjusting

- **A genuinely free "no-model path" plausibly already exists at the engine level and should be
  verified/formalized, not designed from scratch.** The architecture's documented graceful-degradation
  policy (no GPU → falls back to keyword search) implies BM25/lexical search does not require any
  ONNX/GGUF model to function — degraded-mode plumbing exists in the indexer/worker (e.g.
  `IndexRecoveryPolicy`, `InferenceCompositionRoot`/`InferenceSurface` in
  `modules/indexer-worker/.../server/`). If that's confirmed live (this pass didn't run it end-to-end —
  that's exactly the kind of runnable-evidence step 656's Boundary asks for, not something to assert
  from statics), the "smallest onramp tier" isn't a new small-model artifact to build — it's making the
  **existing zero-model BM25 tier** discoverable, named, and status-visible, which is a much cheaper
  design than inventing a new model tier. Recommend the next agent's first experiment be: cold dev-data
  dir, zero models present, index the demo corpus, run a query, see what actually happens today.
- **MCP-only usage (657's "MCP Lite") likely doesn't need the GGUF chat model at all**, only the ONNX
  retrieval stack (embedding + reranker + SPLADE, ~3.5 GB total per `model-registry.v2.json` sizes) —
  the MCP tool surface (`docs/reference/mcp-production-server.md` §Available Tools) reads as
  retrieval-only (search/fetch style tools), not chat/RAG. That's a meaningfully different, and
  meaningfully lighter, weight tier than "no model at all" (BM25) or "full desktop" (+5.9 GB GGUF +
  mmproj). Worth naming as a distinct tier explicitly rather than collapsing "no-model or small-model
  path" into one option, since the two have very different value curves (BM25-only forfeits semantic
  recall entirely; ONNX-only forfeits only chat/RAG).
- **Boundary overlap with 658 (retrieval-inspectability-and-diagnostic-bundle) needs an explicit split,
  not just a mutual "prior owner" citation.** 658 owns *why did this query/result/citation happen*
  (retrieval-behavior explainability). 656's "doctor diagnostics" is really about *is the environment
  even ready to answer a query at all* (models present? correct paths? llama-server reachable? MCP
  endpoint up?) — environment/runtime readiness, not retrieval behavior. Recommend 656 scope its
  doctor check explicitly as a **precondition gate that runs before** 658's inspector is ever relevant,
  and 658 should not re-scope model/runtime presence checks itself.
- **The tempdoc's assumption that the gap is primarily a *design* gap is only half right.** Findings
  A.1, A.3, and A.4 are not missing designs — they are concrete, git-datable **defects** (a false
  canonical-doc claim, a wrong hardcoded path, a missing keep-set entry) that a fix-root-causes pass
  could resolve directly, independent of whatever the eventual onramp design looks like. The onramp
  design (bundled demo corpus, `justsearch doctor` shape, etc.) is real follow-on work, but it
  shouldn't be blocked on or conflated with fixing these three, which are correctness bugs in existing
  surfaces today.

### C. Related tempdocs, characterized (not deeply re-derived)

- **657 (install-modes-and-model-pack-decomposition)** — the structural counterpart: decides which
  models/runtime pieces belong to which install mode. 656's "no-model or small-model path" question is
  downstream of 657's mode decomposition; the two should share one tier taxonomy (see BM25-only vs.
  ONNX-only vs. full-desktop above) rather than each inventing its own.
- **654 (local-runtime-contract-and-product-center)** — 656 needs a stable "what is JustSearch Runtime"
  object to onboard developers *to*; still open, no decision made yet, so 656's design work is
  currently blocked on a product-identity decision it doesn't own.
- **655 (mcp-conformance-and-capability-policy)** — governs the supported-MCP-client matrix; 656's
  "MCP attach instructions" step should not invent its own client list ahead of 655.
- **658 (retrieval-inspectability-and-diagnostic-bundle)** — see boundary note above.
- **660 (plugin-sdk-community-onramp)** — downstream/later-stage (explicitly gates on runtime+MCP
  contracts stabilizing first); not immediately relevant to this pass, noted for completeness only.
- **634 (go-public-cutover-transition)** — the direct cause of finding A.1 (model blobs deliberately
  excluded from the public snapshot per its scope #2, doc never reconciled).

### D. Explicitly not done in this pass

No fixes applied, no `justsearch doctor` designed, no README/CONTRIBUTING changes made, per the
instruction to investigate/analyze only. Findings A.1, A.3, and A.4 in particular read as small,
independent, low-risk fixes a future pass could take on immediately regardless of how the larger
onramp design resolves — flagged here rather than actioned.

## §Design theorization (second pass, 2026-07-01)

This section answers "what should the long-term design actually be," grounded in what the codebase
already does for adjacent problems. No implementation performed. Design kept at the
architecture/contract level, not file-by-file — concrete code shape (exact enum names, exact wiring
call sites) is next-agent implementation work.

### D1. Restating the problem this tempdoc actually has

656 is not "JustSearch needs better onboarding docs" in general. The live incident and the confirmed
findings above are all one specific shape: **a running or about-to-run instance has an AI-capability
state that is not ready, and nothing in the system can say, precisely and in one authoritative place,
why.** The "doctor," "expected status output," and "failure explanations" asks in the original Purpose
are all requests for *that one capability* — truthful, specific readiness explanation — not a request
to invent a new UX subsystem. Scope stays there; it does not need to grow into a general onboarding
redesign, a demo-corpus product decision, or an MCP-client-matrix design (655 already owns that).

### D2. Existing seam #1 — the runtime manifest is already the system's single-authority answer for this class of question

`docs/explanation/23-runtime-manifest.md` (tempdoc 501) already establishes, in force, exactly the
principle this problem needs: *"Future questions of the form 'how does a non-JVM consumer find
runtime fact F?' have exactly one acceptable answer: F is a field on the runtime manifest."* This is
not aspirational — it is mechanically enforced by `scripts/ci/check-runtime-manifest-closure.mjs`,
which fails a PR that invents a tenth mechanism (new env var, new sibling file, new stdout line).

The manifest already carries an `ai` block (`phase`, `required`, `pendingReason`, `readyAt`) and is
already exposed on **five** transports simultaneously: HTTP (`GET /api/runtime/manifest`), SSE
(`GET /api/runtime/manifest/stream`), filesystem (`<dataDir>/runtime/manifest.json`), a
`.well-known` mirror, and an MCP tool (`justsearch_runtime_manifest`). Any design for "how does a
developer or agent learn why AI isn't ready" that does not route through this surface would be
exactly the "tenth mechanism" the closure rule exists to prevent — a parallel, driftable authority
next to the one that already exists and is already wired everywhere an onramping consumer would look
(including MCP, which 656's own Purpose calls out).

**Conclusion: whatever "doctor" ends up being, it is a *renderer* of the runtime manifest, not a new
probe.** This part of the design is not new structure — it's confirming the existing structure already
covers the surface-area question and should not be duplicated.

### D3. Existing seam #2 — a closed, stable reason-code taxonomy already exists for exactly this failure shape, for every other capability except this one

`LifecycleReasonCode` (`modules/app-api/.../lifecycle/LifecycleReasonCode.java`) is the manifest's
existing "why" vocabulary: a closed enum, explicitly documented as "low-cardinality, stable, and
suitable for automation... must not include dynamic details like file paths, exception messages, or
IDs." It already has the exact precedent this problem needs, for *other* capabilities:
`VDU_MISSING_MMPROJ` (a required model file is absent), `ORT_CUDA_MISSING_DLLS` (a required native
library is absent), `WORKER_NOT_CONFIGURED`, `INDEX_BLOCKED_LEGACY`, etc. — one code per
distinguishable "not ready, and here is specifically why" state, per capability.

For the Inference/AI capability — the one this tempdoc is about — the taxonomy is coarse to the point
of being nearly useless for diagnosis: only `INFERENCE_STARTING` and `INFERENCE_OFFLINE` exist.
Verified why: `InferenceCapability.pendingReason()` (`modules/app-services/.../lifecycle/
InferenceCapability.java:39`) returns a **raw mutable string field** (`reason`, set via
`transition(health, newReason)`), never validated against `LifecycleReasonCode.isKnown(...)`. This is
a live, confirmed violation of the pattern every sibling capability follows.

Meanwhile, the actually-useful, already-correctly-detected granular causes exist — just in the wrong
place. `RuntimeActivationService.fail(...)` (`modules/app-services/.../ai/runtime/
RuntimeActivationService.java`) already distinguishes exactly the cases this incident hit:
`MODEL_PATH_REQUIRED` ("No chat model configured"), `MODEL_NOT_FOUND` ("Configured model does not
exist"), `RUNTIME_BASELINE_NOT_FOUND` ("CPU baseline llama-server.exe not found" — the literal
"missing llama-server" case), `RUNTIME_VARIANT_NOT_INSTALLED` ("Variant not installed: cuda12" — the
literal case observations.md already logged from the dev-runner soft-clean bug). But these are ad hoc
string literals scoped only to the immediate `ai_activate` RPC response — never passed into
`InferenceCapability.transition(...)`, so they **never reach `ai.pendingReason` on the manifest, and
therefore never reach any of its five transports.** A developer polling `/api/runtime/manifest` (or
the MCP tool) mid-failure sees only "Inference not yet activated" or similar generic prose, while the
system, one layer inward, already knows the precise cause.

A second, smaller instance of the same gap: `StartupCode` (`modules/app-api/.../StartupCode.java`),
the llama-server *process-launch* failure taxonomy, has a precedent for "a required artifact is
missing" (`MISSING_DLL`) but no equivalent for "the llama-server executable itself was not found" —
that case most likely collapses into the generic `PROCESS_EXITED` or `UNKNOWN` today, losing the
causal signal at the point closest to the OS-level failure.

**Conclusion: the long-term design is not a new subsystem. It is finishing a wiring job the rest of
the system already did for every other capability** — extend `LifecycleReasonCode` with the missing
inference-specific entries (following the `VDU_MISSING_MMPROJ`/`ORT_CUDA_MISSING_DLLS` precedent
exactly), extend `StartupCode` with an executable-not-found entry (following the `MISSING_DLL`
precedent), and route `RuntimeActivationService`'s already-correct detection through
`InferenceCapability.transition(health, code)` using those codes instead of local strings. Once wired,
the manifest — already multi-transport, already MCP-exposed — carries the true, specific reason
everywhere, automatically, with no new transport or new doc needed.

### D4. The one genuinely new piece — preflight (before any process exists to ask)

The manifest cannot answer "will activation succeed if I try," because that question has no running
instance to publish an answer from — this is the one real gap D2/D3 don't close. This is where
`verify-prerequisites.mjs`-shaped logic legitimately belongs; the tool is the right *kind* of thing,
just the wrong *shape* today (per finding A.3, it hand-maintains its own copy of "where should each
model file be," which is exactly how it drifted — the citation-scorer path check has been wrong since
2026-06-13, already known and undocumented-as-fixed).

The design principle for preflight should mirror what already fixed the *other* half of that same
script: the chat-model check (line 297-309) now reads the expected filename live from
`model-registry.v2.json`'s `chat` package instead of hardcoding it, specifically "so this check never
drifts when the packaged model changes" (comment cites tempdoc 579). Preflight, generally, should be a
thin, read-only reconciliation between the two catalogs the system already maintains as SSOT —
`model-registry.v2.json` (what should exist, and where, keyed by package id / `targetDir`) and
`installed-packs.v1.json` + on-disk presence (what actually exists) — never a hand-authored parallel
path list. This is not new structure to invent; it is applying the fix already proven correct for the
chat-model check to the rest of the same file, and treating "read the registry, don't hardcode paths"
as the standing rule for any future preflight/doctor code, not a one-off patch.

### D5. What "doctor" / onramp UX becomes once D3+D4 exist (kept general — not implementation-level)

With D3 (truthful, specific runtime reason codes) and D4 (registry-driven preflight) in place, a
"doctor" is the union of exactly two read paths, both already-existing-shaped:

- **Before a process runs**: preflight reconciliation (D4) — "given the registry, what's missing on
  disk right now."
- **While a process runs**: render the runtime manifest (D2), specifically `ai.phase` +
  `ai.pendingReason` (now carrying a real `LifecycleReasonCode`, per D3) — "given the running
  instance's own self-report, what's not ready and why."

Whether this union is exposed as a CLI subcommand, a REST diagnostic endpoint, an MCP tool, or some
combination is a next-agent implementation decision, not something to fix here — but whichever shape
is chosen, it should be a thin presentation layer over these two existing/extended read paths, not a
third independent prober. The same applies to "expected status output" and "failure explanations" in
the original Purpose: both become renderings of `ai.phase`/`ai.pendingReason` values that are already
true and already transported, not new content to author by hand (which is exactly how
`model-inventory.md`'s stale presence table happened — see D6).

The "no-model or small-model path" question is a separate, narrower design question this pass leaves
open: whether a BM25-only (zero-model) search tier is already functionally viable today is an
empirical question this pass did not run an experiment for (recommended as the next agent's first
verification step, per the original investigation pass), and whichever tiers 657's mode decomposition
settles on, doctor/preflight should describe *those same tiers* — not invent its own.

### D6. The doc-drift finding (A.1) is an instance of an already-named, already-diagnosed class — not a new problem

`docs/reference/model-inventory.md`'s stale "all models are tracked in git" claim (finding A.1) is not
a one-off documentation slip. Tempdoc 650 already diagnosed the identical shape for a different
surface (the public "what JustSearch is" narrative): *"the capability narrative has no declared
source, so every public doc hand-authors it and they drift in one direction."* 650 names the general
fix as **`canonical-authority-and-projection`** — establish one canonical source, make every other
surface a projection of it, and guard the projection mechanically (as 633's stack-claim gate already
does for a different narrative). Tempdoc 579 (`canonical-doc-drift-remediation`, cited by 650) is the
earlier lineage of the same principle applied to code/doc drift generally.

`model-inventory.md`'s presence table is exactly this: a hand-authored projection of git-tree state
with no guard keeping it honest, and it silently went stale the moment tempdoc 634's public snapshot
excluded the LFS blobs it describes. The long-term fix is therefore not "edit the doc" (a short-term
fix, out of scope here) — it is recognizing the table needs to become either (a) generated from
`model-registry.v2.json` + a live git/LFS presence check, the same SSOT preflight (D4) already needs to
read, or (b) demoted to prose that does not assert per-file presence/status at all. Either resolves it
as an instance of 650/579's already-adopted remediation shape, not a new one.

### D7. Principle recognition and reach (recognizing ≠ building)

**Named principle (reusing the existing name, not inventing a new one): `canonical-authority-and-projection`**
(tempdoc 650). Restated for this domain: *every "is X present / is X ready / why isn't X ready"
surface in JustSearch should be a projection of exactly one authoritative state machine, never a
hand-authored parallel copy.* For *runtime* facts, that authority is Capability + LifecycleReasonCode
+ RuntimeManifest (D2/D3). For *installable-artifact identity* facts, that authority is
`model-registry.v2.json` (D4/D6).

This pass found the principle already named twice, independently, in different parts of the system —
579 for code/doc drift, 650 for the public capability narrative — and now finds a **third, independent
instance** in a third part of the system entirely (runtime lifecycle reason-code plumbing: D3) plus a
recurrence of the original doc-drift instance (D6) and a script-level instance (`verify-prerequisites.mjs`'s
citation-scorer path, A.3). Three independent lineages converging on the same shape across docs, scripts,
and runtime-lifecycle code is reasonably strong evidence this is a real, recurring invariant of the
system rather than a coincidence — worth naming plainly so a future agent recognizes the fourth instance
faster.

**Where else this principle plausibly applies (candidate scope, not proposed work):**
- Any other `Capability` implementation's `pendingReason()` that returns a raw string rather than a
  `LifecycleReasonCode`-validated one — `WorkerCapability.pendingReason()` was not audited in this pass
  and should be checked for the same violation before assuming Inference is the only offender.
- The other reason-code-shaped enums this pass noticed in passing but did not audit
  (`OcrSkipReason`, `IngestionReasonCodes`, `ValidationReason` in adapters-lucene) — likely already
  conform (they read as properly-closed enums from their names/locations), but this pass did not verify
  them; noted as a candidate check, not a finding.
- Any future canonical reference doc (like `model-inventory.md`) that asserts a fact about shipped/
  present artifacts without a generating mechanism is a candidate for the same drift.

**What this pass deliberately does not do:** build a generalized "projection-drift linter" gate, audit
every `Capability` implementation, or retrofit `OcrSkipReason`/`IngestionReasonCodes`/`ValidationReason`.
656's actual, present problem is fully addressed by D3+D4+D6 (the Inference capability's wiring, the
registry-driven preflight, and the one stale doc) — generalizing further now would be structure the
current problem does not require. The principle is recorded here so it's recognized quickly if a
future tempdoc hits the fourth instance; building the generalized enforcement is that future tempdoc's
call, not this one's.

### D7b. External research pass — is any of this design sitting on ground that's actively shifting? (2026-07-01)

Checked whether a research pass was warranted before treating D1-D7 as settled. Judgment, by piece:

- **The core mechanism (single authoritative state machine + closed reason-code enum + multi-transport
  projection, k8s-style readiness/liveness)** is settled software-engineering practice (the manifest doc
  itself already cites the k8s readiness/liveness pattern by name). No active research churn here; no
  search performed for this part.
- **"Doctor"-style local preflight tooling and zero-setup onboarding** (`flutter doctor`, `brew doctor`,
  the "one command, zero setup, offline after first model download" framing already validated in the
  prior investigation pass against `shinpr/mcp-local-rag`) is an established, stable pattern, not a
  fast-moving one. No further search needed.
- **MCP itself is not settled** — this one is worth a targeted check, since D2/D5 lean on "the MCP tool
  is one of the manifest's already-existing transports" as a given. Searched and fetched the current
  spec state directly (`blog.modelcontextprotocol.io`, official source) rather than relying on
  secondary summaries.

**Finding:** JustSearch pins MCP protocol version `2025-11-25` (`docs/reference/mcp-production-server.md:62`).
A **2026-07-28 release candidate** — described by the MCP maintainers as "the largest revision of the
protocol since launch" — finalizes in about four weeks from this pass (RC locked 2026-05-21, final spec
2026-07-28; source: [MCP blog, "The 2026-07-28 MCP Specification Release Candidate"](https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/)).
Relevant specifics: it **removes the `initialize`/`initialized` handshake and session model entirely**
(any request can land on any server instance, no sticky session store), replaces connection-time
capability negotiation with an on-demand `server/discover` method, and — most relevant here — gives
`tools/list` and resource reads `ttlMs`/`cacheScope` fields "modeled on HTTP `Cache-Control`,"
explicitly **"eliminating reliance on persistent SSE streams for change notifications."**

**Implication for this design, kept general (no implementation decided here):** D2/D5's core claim —
"project the manifest's `ai.phase`/`ai.pendingReason` through MCP, don't build a parallel prober" —
still holds and is actually reinforced by this shift, not undermined: a snapshot field read through a
ttl-cached MCP resource/tool call is a *better* fit for the incoming stateless model than the
manifest's own SSE stream would be if naively carried over. What should **not** be assumed is that the
current session/SSE-shaped MCP tool wiring survives unchanged — that's exactly the kind of transport
detail 655 (`mcp-conformance-and-capability-policy`) owns, not this tempdoc. Flagging so 655's own pass
tracks the July 2026 spec transition when it designs MCP conformance, rather than 656 quietly baking in
an assumption about MCP transport mechanics that may not survive the month.

**No external code, text, or assets were copied or adapted into this tempdoc or the codebase** —
this was a factual/currency check, cited by URL above; nothing to attribute under the license/notices
CI lane beyond the citation itself.

### D8. Public-repo caution

This tempdoc is design history in a public repository and may be read externally once merged. Nothing
in D1-D7 is a public-facing claim — it's internal reason-code/manifest wiring. Flagging explicitly so a
future README/CONTRIBUTING update drawing on this design doesn't get ahead of the work: do not describe
JustSearch as already telling developers "exactly why AI isn't ready" (or similar) until D3's wiring
has actually shipped and been live-verified: an unshipped design conclusion stated as present-tense
capability would be the same undercount-in-reverse class of falsehood 650 diagnosed on the public
descriptor, just overclaiming instead of undercounting.

