---
title: "Five-minute agent/runtime onramp: the runtime foundation is shipped (GPU-only shared dev runtime + truthful AI-readiness diagnosis); the remaining onramp is designed as an assembly problem — a tiered, proven first-success path (a doctor composing existing read-paths + a demo corpus + a runnable smoke), not new capability, with tier/MCP/product-identity framing handed to 657/655/654. Long-term design (14th pass): the onramp tier is a capability PROJECTION (conform to 587's probe→effective→policy substrate + the readiness code→wording register/gate), promoted to a declared tier-ladder + one canonical projection rendered by the UI/MCP at the FIRST second-renderer — recorded with a promotion trigger, not built (single consumer today)."
type: tempdocs
status: "FOUNDATION + ONRAMP IMPLEMENTED (live + browser verified 2026-07-01, §Implementation twelfth pass): the doctor (scripts/dev/doctor.mjs), demo corpus (examples/onramp-corpus), runnable proof (scripts/dev/test-onramp-first-success.mjs), the manifest-reason polish, and the tier-honest CONTRIBUTING onramp section all shipped and verified. Earlier: Two things are done + live/browser-verified: the AI-readiness diagnosability substrate (Tasks 0-5) and the GPU-only shared dev runtime (Move 1 + Move 2, ninth pass). §Onramp design (tenth pass, 2026-07-01) then returns to the tempdoc's ORIGINAL purpose (the five-minute onramp) and settles its long-term shape: it is an ASSEMBLY problem — the ingredients (ingest/search endpoints, MCP connect surface, the preflight endpoint, the runtime manifest, a tiny corpus candidate) already exist scattered; the remaining core is composing them into a tiered, evidence-producing first-success path (O1 tier ladder — Tier 0 zero-model search proven; O2 doctor extending AiPreflightService as a projection of manifest+status; O3 demo corpus; O4 a runnable proof; O5 minimal honest discoverability), plus fixing the deferred manifest-reason polish inside the doctor. NOT yet built. Explicitly NOT the whole 'five-minute onramp': the identity/MCP-matrix/tier-naming framing is coupled to the unstarted 654/655/657 and is handed to them. Detail on the implemented foundation follows. --- Move 1 + Move 2 shipped as a Node-only change to scripts/dev/dev-runner.cjs (+ prepare-worktree.cjs, the MCP readiness message, and a new regression test): dev inference is now GPU-only with a shared, acquire-once cuda12 runtime. dev-runner no longer stages a CPU llama-server baseline (removing the silent 9B-on-CPU fallback that DOSed concurrent worktrees, per the settled GPU-primary direction, tempdoc 381); it resolves JUSTSEARCH_SERVER_EXE to the shared main-checkout cuda12 (worktree-own first), and one-time-populates that shared location from the Gradle cuda stage — every worktree then references one copy, zero per-worktree download (the property models already have via JUSTSEARCH_MODELS_DIR). When no cuda12 is resolvable, inference fails CLOSED (truthful 'unavailable'; search still works) instead of silently degrading. Live-verified: the running llama-server was the SHARED main-checkout exe (-ngl 99) resolved by the new logic, a real API query + a browser Document Q&A both answered coherently on GPU ('Online — Qwen Qwen3.5-9B'). Production bundling (bundleSidecarResources) untouched. Deferred (optional, documented): the mode-transition manifest-reason polish + ORT-CUDA GPU-embedding sharing. Earlier: diagnosability substrate (Tasks 0-5) shipped; passes 6-8 traced the acquisition gap, reframed the goal, settled the design, and live-proved viability."
created: 2026-06-28
updated: 2026-07-01
category: developer-experience / activation / mcp / diagnostics
related:
  - 618-agent-developer-velocity-friction
  - 381-model-distribution-architecture
  - 376-cpu-gpu-inference-strategy
  - 374-app-packaging-and-distribution
  - 587-host-capability-sensing-substrate
  - 598-semantic-search-unreachable-single-gpu
  - 654-local-runtime-contract-and-product-center
  - 655-mcp-conformance-and-capability-policy
  - 657-install-modes-and-model-pack-decomposition
  - 658-retrieval-inspectability-and-diagnostic-bundle
  - 650-go-public-capability-descriptor-truthfulness
  - 501-runtime-manifest-design
  - 634-go-public-cutover-transition
  - docs/decisions/0024-app-packaging-nsis-per-user-download.md
  - docs/reference/inference-runtime-register.md
  - docs/explanation/23-runtime-manifest.md
  - docs/reference/contributing/agent-guide.md
  - docs/reference/mcp-production-server.md
  - docs/reference/model-inventory.md
  - scripts/verify-prerequisites.mjs
  - scripts/dev/dev-runner.cjs
  - modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java
  - modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java
  - modules/app-services/src/main/java/io/justsearch/app/services/ai/install/RuntimeRestoreUtil.java
  - modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java
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

## §Confidence pass (2026-07-01, third pass)

Per the approved investigation plan, this pass converts the design's unverified assumptions into
traced, read facts (or corrects them), plus one live experiment. No feature code changed.

### Resolved

**1. Confirmed — no existing wiring between `RuntimeActivationService.fail()` and
`InferenceCapability.transition()`.** Grepped `RuntimeActivationService.java` for
`InferenceCapability`/`transition(`: zero matches. The assumption holds as stated.

**2. Corrected — the "missing llama-server executable" failure does not collapse into
`StartupCode.PROCESS_EXITED`/`UNKNOWN` as originally guessed; it collapses into something even
coarser.** Traced the real call chain: `LlamaServerOps.startLlamaServer()` → `launchManagedLlamaServer()`
→ `startManagedProcessAndMonitor()` → `process = pb.start()` (`LlamaServerOps.java:563`), declared
`throws IOException` with **no catch** at any point in that chain. The caller,
`InferenceLifecycleManager` (e.g. around line 371), only has typed handling for
`ModeTransitionException` (line 403, which maps to `StartupCode` via `TransitionRunner`) — a raw
`IOException` from `pb.start()` failing (Windows error 2, file not found, is what a missing
`llama-server.exe` actually throws) falls through to the generic `catch (Exception e)` at line 412
and becomes `TransitionCode.ONLINE_START_FAILED` — a **different, more generic enum family**
(`TransitionCode`, not `StartupCode`) — with the real cause preserved only as a `safeMessage(e)`
free-text substring, not any stable code. Contrast with the precedent that *does* exist:
`MISSING_DLL` (`LlamaServerOps.java:391-405`) is detected by inspecting the **Windows exit code**
of an already-launched process (`0xC0000135`) — a fundamentally later failure point than "the exe
was never found to launch in the first place." **Correction for implementation**: the fix is not
"add a `StartupCode` entry and it'll get hit" — it requires an explicit pre-launch existence check
(or a dedicated catch around `pb.start()`) inside `LlamaServerOps`/`launchManagedLlamaServer` that
throws a proper `ModeTransitionException` with a new `Reason`, mirroring the `MISSING_DLL` pattern
structurally (detect condition → throw typed reason) even though the detection mechanism differs
(pre-launch file check vs. post-launch exit code).

**3. Confirmed and precisely bounded — adding new codes is mechanical, not free, and the mechanism
differs per enum family.** For `LifecycleReasonCode`: a real, live gate exists —
`scripts/ci/check-readiness-reason-codes.mjs` + register `governance/readiness-reason-codes.v1.json`
— enforcing FORWARD (every non-exempt enum code must have a row in `readinessNotice.ts`'s
`CAUSE_ROWS`, so no raw code ever renders to a user) and BACKWARD (no dead FE rows) correspondence.
A new inference-specific `LifecycleReasonCode` member therefore requires exactly one of: (a) a
`CAUSE_ROWS` wording entry, or (b) a justified `noWordingExempt` register entry — a bounded,
well-precedented, 2-3 file change. For `StartupCode` (and its siblings `HealthCode`/`ConfigCode`/
`TransitionCode`): a Java contract test, `InferenceFailureMessagesContractTest`, enforces that every
`wireValue()` has a matching key in `messages/inference-failures.en.properties` (missing-key and
orphan-key checks, plus a count-match check). A new `StartupCode` (or a new `ModeTransitionException.Reason`
feeding a new `TransitionCode`) requires exactly one new properties-file entry. Both mechanisms are
proven, low-risk, and already handle exactly this shape of change — this is good news for
implementation confidence, not a blocker.

**4. Corrected — the reusable registry→path resolution logic is `AiInstallService`, not
`PackStagingOps`/`AiPackValidator`.** `PackStagingOps` operates on an already-downloaded,
already-validated `AiPackManifestV1` ("pack") — a *second*, separate model-acquisition mechanism
(offline/pre-bundled pack import) distinct from the direct "Install AI" download flow. The actual
registry-driven path resolution the preflight design (D4) should reuse lives in
`AiInstallService.java` — confirmed via `modelsDir.resolve(pkg.targetDir())` (line 704) and
`modelsDir.resolve(chat.targetDir()).resolve(chatVariant.filename())` (line 648), both reading
directly from the loaded `ModelRegistry` (`getManifest()` → `ModelRegistryLoader.loadFromClasspath`).
This is the class a preflight implementation should call into or mirror — not `PackStagingOps`.

**6. Refined — the violation is at call-site discipline, not at the type level, and Worker is not
a clean counter-example.** `WorkerCapability.pendingReason()` (`WorkerCapability.java:40`) is
**also** a raw mutable string field (`WorkerCapability.java:25`, same shape as `InferenceCapability`)
— the `Capability` interface itself does not type-enforce `LifecycleReasonCode`. The real
distinction found: `StatusLifecycleHandler.java:1052` compares
`LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code().equals(workerCapability.pendingReason())`,
confirming Worker's call sites *discipline themselves* to always pass an enum's `.code()` string,
even though nothing enforces it. Inference's call sites do not follow this discipline at all: the
capability's default reasons are free English prose (`"Inference not yet activated"`,
`"Inference not configured"` — `InferenceCapability.java:20,29`), which don't match any
`LifecycleReasonCode.code()` value (e.g. `"inference.offline"`). **Revised framing for
implementation**: this is a call-site-discipline gap to close (make Inference's `transition()` callers
pass enum codes like Worker's already do), not a type-system gap requiring a new mechanism — the
existing `Capability` interface doesn't need to change.

**7. Spot-checked, hedge confirmed — `OcrSkipReason` and `ValidationReason` are proper closed
`enum`s** (matching the `LifecycleReasonCode` shape); `IngestionReasonCodes` is a `final class`
(constants holder, not an enum, but still a closed/discrete set by construction). No violation
found on a quick shape check. Not deep-audited further, per the plan's stated priority (this is
candidate-scope, not required for 656's own problem).

### Live experiment (5, 8)

Attempted per plan: `node scripts/dev/prepare-worktree.cjs --no-dist` succeeded quickly (FE deps
only). Starting the actual dev stack surfaced a real, useful precondition failure of its own:
`dev-runner.cjs start` requires `modules/ui/build/install/ui/bin/ui.bat` to exist first — i.e. a full
`:modules:ui:installDist :modules:indexer-worker:installDist` Gradle build, which `--no-dist` prep
deliberately skips. This is itself a small, consistent data point for the tempdoc's own subject
matter (getting from zero to a running instance has more preconditions than a single prepare
script covers) but is not itself a new finding to act on here.

The full install-dist build was run (`:modules:ui:installDist :modules:indexer-worker:installDist`,
succeeded), then the dev stack was started clean (`dev-runner.cjs start --clean hard`) in this
worktree's actual current state (4 of 9 model blobs + `native-bin` still absent, per the original
investigation pass). This is a **live, measured** result, not an inference from source.

**`GET /api/runtime/manifest` while genuinely broken:**
```json
"ai": {"phase":"PENDING","required":true,"pendingReason":"Inference not yet activated"}
```
Generic, exactly as the static read predicted — no hint of a specific cause.

**Triggered `POST /api/ai/runtime/activate {"variantId":"default"}`, then polled `GET
/api/ai/runtime/status` at the same moment:**
```json
"activation": {"errorCode":"RUNTIME_VARIANT_NOT_INSTALLED","message":"Variant not installed: default","state":"failed", ...}
```
The system **already knows** the precise, correct, actionable cause — sitting right there in the
activation-status endpoint. The manifest, polled in parallel at the exact same moment, still read
`"Inference not yet activated"`, completely unchanged. This is the D3 gap caught live, not
theorized: the precise answer and the generic answer coexist in the same running process, and the
one channel this tempdoc's onramp/doctor design would read (the manifest) is the one that doesn't
have it. `GET /api/status` shows the same generic ceiling from a third angle:
`"inference":{"state":"LIFECYCLE_STATE_DEGRADED","reason_code":"inference.offline"}` — coarser
still, confirming `INFERENCE_OFFLINE` really is the only code this path ever emits today.

**Search with the LLM/chat capability fully offline:** `POST /api/knowledge/search
{"query":"the","limit":5}` against the worker's already-indexed 5-document dev-data index returned
**4 real, ranked results** — not an error, not empty. `searchTrace` shows `effectiveMode: "HYBRID"`,
with `dense-retrieval: executed` and `embeddingCoverage: 1` — i.e. **the ONNX embedding/dense
retrieval stack ran successfully while the GGUF chat model / llama-server was completely offline.**
This directly, empirically confirms D5's speculative claim (previously flagged as untested): search
value does not require the LLM tier at all. It also sharpens D5's tier taxonomy further than the
design pass could from statics alone — the real dividing line observed is not "zero models vs. some
models," it's specifically **"LLM/chat (GGUF + llama-server) vs. everything else (ONNX embedding +
SPLADE + Lucene/BM25)"** — the latter cluster functions as one unit, live-confirmed, independent of
the former. One loose end not resolved in this pass: `onnxFeatures` in the activation-status payload
listed `reranker`/`citation_scorer` as `"reason":"not_found"` while the search response's
`crossEncoderAvailable` read `true` — plausibly two different things (an optional GPU-accelerated
variant install-status vs. the base CPU model actually loaded), but this pass did not chase it
further; noted honestly rather than papered over.

Cleanly stopped the dev stack afterward (`dev-runner.cjs stop --active`) — no state left running.

### Confidence rating and implementation-effort recommendation (2026-07-01)

**Confidence in the design, for the core wiring fix (D3): 8/10.** Every load-bearing claim for this
piece is now either directly read from the call chain or, for the central claim, live-measured
against a running instance in this exact worktree (the `RUNTIME_VARIANT_NOT_INSTALLED` vs.
`"Inference not yet activated"` split above is not a guess). The two points holding this back from a
higher score: (a) the exact shape of the fix for the "exe not found before launch" case (finding 2)
needs a small design decision next-agent-side (pre-launch `Files.exists()` check vs. a dedicated
catch around `pb.start()`) that this pass intentionally left as implementation-level judgment; (b)
the `onnxFeatures` vs. `crossEncoderAvailable` discrepancy noticed during the live experiment was not
chased to ground — low risk, but an honest unknown.

**Confidence in the preflight piece (D4): 6/10.** `AiInstallService`'s registry-driven path
resolution is confirmed to exist and be the right reuse target, but this pass did not verify it is
cleanly *extractable* as a read-only, side-effect-free query — it may be entangled with the download
mutation flow, which would make "reuse it for preflight" require a small refactor (splitting
resolution from action) rather than a pure call-out. Next agent should read the surrounding class
structure before assuming a one-line reuse.

**Confidence in the doc-drift fix (D6, `model-inventory.md`) and the "no-model tier" tier naming
(D5): 9/10.** Both are now either directly git-verified (the doc drift) or live-measured (the tier
split), the highest-confidence findings of the whole pass.

**Overall confidence for the remaining implementation work: 7/10.** Materially higher than before
this pass (would have rated 4-5/10 going in — the design rested on several unverified assumptions,
two of which (findings 2 and 4) turned out to need correction, not just confirmation). What raised it:
a live, reproduced, unambiguous demonstration of the exact bug this tempdoc exists to fix, plus a
bounded, precedented, low-risk blast radius for every enum/register touch point. What keeps it from
higher: the pre-launch-exception-handling shape (finding 2) and the preflight extractability question
(D4) are genuine open implementation judgment calls, not fully resolved facts — reasonable for an
investigation pass to leave to implementation, but real enough to name plainly rather than round up.

### Recommended model / effort tier for implementation

**Recommend Sonnet at high effort, not Opus, and not a low/default effort tier.** Reasoning:

- **Why not a low-effort tier**: the change touches four files across two languages in ways that must
  stay mutually consistent (`LifecycleReasonCode.java` new members ↔ `readinessNotice.ts` `CAUSE_ROWS`
  rows or `governance/readiness-reason-codes.v1.json` exemptions ↔ `messages/inference-failures.en.properties`
  keys ↔ the actual `RuntimeActivationService`/`InferenceCapability`/`LlamaServerOps` wiring) — a
  shallow pass would satisfy the compiler but miss one of the three gate/test mechanisms found in
  this pass (`check-readiness-reason-codes.mjs`, `InferenceFailureMessagesContractTest`, and whatever
  `RuntimeManifestSchemaCompatibilityTest` requires for a schema bump if one is judged necessary),
  producing a red CI gate discovered late rather than avoided.
- **Why not Opus/Fable**: this is not a novel-design problem — the design is already settled by this
  pass and the prior two, down to naming the exact classes and precedent patterns (`MISSING_DLL`,
  `VDU_MISSING_MMPROJ`, the `AiInstallService.targetDir()` resolution). What remains is careful,
  convention-following extension of an existing, well-precedented pattern into a handful of named
  files — exactly the shape of work Sonnet at high effort handles reliably, and where Opus's extra
  judgment capacity would be spent re-deriving conclusions this pass already reached, not adding new
  ones.
- **Why "high" and not default effort**: the two open judgment calls left in this pass (the
  pre-launch exception-handling shape for finding 2, and confirming/refactoring `AiInstallService`'s
  extractability for D4) need a careful read of surrounding code before committing to an approach —
  worth the extra effort budget over a quick mechanical pass, but not worth a full architecture-grade
  model.
- **One condition**: if the implementing agent's own read of `AiInstallService` (D4) reveals the
  path-resolution logic is *not* cleanly extractable and needs a real refactor (splitting resolution
  from the download side-effect) rather than a small reuse, that specific sub-slice is worth a second,
  focused Sonnet-high (or Opus, if the refactor turns out to have non-obvious blast radius into the
  pack-import path) pass rather than folding it into the same implementation session by default.

### D8. Public-repo caution

This tempdoc is design history in a public repository and may be read externally once merged. Nothing
in D1-D7 is a public-facing claim — it's internal reason-code/manifest wiring. Flagging explicitly so a
future README/CONTRIBUTING update drawing on this design doesn't get ahead of the work: do not describe
JustSearch as already telling developers "exactly why AI isn't ready" (or similar) until D3's wiring
has actually shipped and been live-verified: an unshipped design conclusion stated as present-tense
capability would be the same undercount-in-reverse class of falsehood 650 diagnosed on the public
descriptor, just overclaiming instead of undercounting.

## §Implementation (2026-07-01, fourth pass)

Implemented Tasks 0-5 from the approved implementation plan, in the same worktree
(`worktree-656-onramp-investigation`). All verification steps from the plan were run, including the
required browser validation. No scope beyond the plan's explicit non-goals was added.

### What shipped

- **Task 0** — `InferenceCapability.transition()` and `WorkerCapability.transition()` now fire
  listeners on a reason-only change (health unchanged), not just a health transition, without
  disturbing `WorkerCapability`'s generation-counter gating (still `prev != newHealth` only).
- **Task 1** — Six new `LifecycleReasonCode` members (`INFERENCE_MODEL_NOT_CONFIGURED`,
  `INFERENCE_MODEL_NOT_FOUND`, `INFERENCE_RUNTIME_NOT_INSTALLED`,
  `INFERENCE_POLICY_ONLINE_AI_DISABLED`, `INFERENCE_POLICY_GPU_DISABLED`,
  `INFERENCE_ACTIVATION_FAILED`), each with a `CAUSE_ROWS` wording row in `readinessNotice.ts`.
  `check-readiness-reason-codes.mjs` passes.
- **Task 2** — `RuntimeActivationService` now takes a nullable `InferenceCapability` and, in
  `fail()`, maps its existing error codes onto the six new reason codes and calls
  `inferenceCapability.transition(...)` — but only when the capability isn't already `READY` (an
  unrelated variant-switch failure must not regress a working capability). Wired at both
  construction sites (`ServicePhase.java` via the existing `in.inferenceCapability()`;
  `CoreApiAssembly.java`'s fallback path via the existing `resolveInferenceCapability` helper).
- **Task 3** — New `ModeTransitionException.Reason.EXECUTABLE_NOT_FOUND` /
  `StartupCode.EXECUTABLE_NOT_FOUND`, a pre-launch `Files.isRegularFile` check in
  `LlamaServerOps.launchManagedLlamaServer` (mirroring the existing `MISSING_DLL` precedent at an
  earlier failure point), an `inference-failures.executable_not_found` i18n entry, and the four
  exhaustive-switch call sites the Java compiler forced (`TransitionRunner` ×2,
  `ModeTransitionException.buildFailure`, `SwitchInferenceModeHandler` — one more mapping site than
  the plan anticipated, caught immediately by the compiler, not missed).
- **Task 4** — New `AiPreflightService` (app-services) + `AiModelsController` (ui) +
  `GET /api/ai/models/status` (registered in `AiRoutes.java`). Named "models/status", not
  "preflight", after discovering during implementation that `/api/ai/packs/preflight` already
  exists for a different purpose (validating a specific offline pack file) — avoided the name
  collision rather than reusing an already-claimed word. Reuses `AiInstallService.getManifest()` /
  a new `modelsDir()`/`aiHome()` getter pair (two one-line additions) and
  `RuntimeActivationService.getStatus()` — no new path-resolution logic, exactly per plan.
- **Task 5** — `docs/reference/model-inventory.md`'s "Repo-Root Model Presence" section rewritten
  to describe model *identity* only, pointing at `GET /api/ai/models/status` for live presence;
  the fixed Qwen3VL row struck from "Stale script references," the still-broken citation-scorer
  row kept.

### A finding the plan didn't anticipate, caught by the required browser check

Live-reproducing the original bug via the API alone (`GET /api/runtime/manifest`) showed the fix
working exactly as designed. But loading the actual web UI showed the degradation banner still
reading the old generic "The local AI model is offline." Investigating why: `StatusLifecycleHandler`
(which feeds `/api/status` and, transitively, the FE banner) had its **own**, separately-hardcoded
`LifecycleReasonCode.INFERENCE_OFFLINE.code()` for every non-READY/non-STARTING inference health
state — never reading `inferenceCapability.pendingReason()` at all, unlike the `WORKER` component's
switch three lines above it in the same file, which already does exactly that (comparing
`workerCapability.pendingReason()` against `WORKER_RESTART_EXHAUSTED` to pick between two codes).
Fixed by adding a small `resolveInferenceReasonCode` helper that prefers a known
`LifecycleReasonCode` from `pendingReason()` over the generic fallback — mirroring the `WORKER`
precedent that was sitting right there. This is exactly the kind of gap the user's instruction to
validate through the real browser (not just the API) was written to catch: the manifest-only
verification in the confidence pass would have reported success on a fix that was incomplete for
the actual user-facing surface.

### Live verification transcript (this worktree, real dev stack, real browser)

1. Started dev stack; `GET /api/runtime/manifest` → `{"phase":"PENDING","pendingReason":"Inference
   not yet activated"}` (baseline, pre-fix-equivalent state for a fresh poll).
2. `POST /api/ai/runtime/activate {"variantId":"default"}` against this checkout's genuinely
   incomplete model/native-bin state.
3. `GET /api/runtime/manifest` → `{"phase":"OFFLINE","pendingReason":"inference.runtime_not_installed"}` —
   fixed.
4. `GET /api/status` → `components.inference.reason_code: "inference.runtime_not_installed"` —
   fixed (after the `StatusLifecycleHandler` fix above; was still `"inference.offline"` before it).
5. `GET /api/ai/models/status` → correctly reports `citation-scorer` incomplete (no variant files),
   `chat` incomplete (GGUF present, `mmproj-F16.gguf` missing), `cuda-runtime` incomplete (all 4
   archives missing via its `installRoot` path), `runtimeInstalled: false`,
   `canActivateDefault: false` — cross-consistent with the other two endpoints.
6. Loaded `http://localhost:5173` in the real browser (claude-in-chrome): degradation banner
   rendered **"The local AI runtime (llama-server) is not installed"** with an "Open Health"
   fallback action (no remedy operation registered for this code, as designed) — confirmed
   end-to-end, backend to rendered pixels.
7. Dev stack stopped cleanly after verification.

### Test/build verification run

`./gradlew.bat build -x test` (full build, all modules) — green. Targeted unit tests
(`:modules:app-api:test`, `:modules:app-services:test`, `:modules:app-inference:test`,
`:modules:ui:test`, including `InferenceFailureMessagesContractTest` and the updated
`LegacyEndpointGuardTest`) — green. `node scripts/ci/check-readiness-reason-codes.mjs` — green.
`cd modules/ui-web && npm run typecheck && npm run test:unit:run` — green except one **pre-existing,
already-logged, unrelated** failure (`HealthLitView.test.ts`'s "604 Move B" SSE-reachability
assertion — confirmed in `docs/observations.md` as reproducing on an unmodified `main` checkout since
2026-06-30; not touched, not in scope). Docs regeneration (`llmstxt-generate.mjs`,
`skills-sync.mjs`) run after the `model-inventory.md` edit, per the consult-doc-hint.

### Explicitly not done (per the plan's non-goals, unchanged)

No new MCP tool, no CLI command, no product-mode decision, no fix to
`verify-prerequisites.mjs`'s citation-scorer path or `dev-runner.cjs`'s soft-clean keep-set gap
(both remain open, independently logged findings for a future pass).

## §Post-implementation critical review (2026-07-01, fifth pass)

Reviewed the shipped implementation against `RuntimeActivationService`'s actual behavior (re-read
line by line, not re-trusted from memory) and against the CAUSE_ROWS wording added in Task 1. Found
one substantive bug and one minor-but-real wording issue; both fixed in this pass. No
security/privacy issues.

**Bug (fixed): `canActivateDefault` false-negatived on a missing mmproj file.**
`AiPreflightService.getPreflight()` computed `chatModelPresent` from the "chat" `PackageStatus`'s
`complete()` flag, which requires every supportingFile present — including `mmproj-F16.gguf`. But
`RuntimeActivationService.runActivate()` never checks for mmproj at all; it only validates the chat
GGUF variant file and the runtime executable. mmproj is a separate, VDU-only concern (pre-existing
`LifecycleReasonCode.VDU_MISSING_MMPROJ` / `vdu.missing_mmproj` already model it as such). This
meant the endpoint could report "cannot activate" in a case where activation would actually
succeed — directly wrong for the one thing the endpoint exists to predict. Confirmed live in this
checkout (which genuinely lacks mmproj): `presentVariantFiles: ["Qwen_Qwen3.5-9B-Q4_K_M.gguf"]` (the
GGUF *is* present) alongside `complete: false` (because mmproj isn't). **Fix**: `canActivateDefault`
now checks `!presentVariantFiles().isEmpty()` for the chat package instead of `complete()`;
`PackageStatus.complete` is unchanged (still legitimate full-download-completeness information for
other consumers, just not the activation-readiness signal). Re-verified live: `canActivateDefault`
in this checkout is still `false`, but now because `runtimeInstalled: false` (the real, current
blocker) rather than being conflated with the unrelated mmproj gap.

**Minor issue (fixed): wrong-direction wording on the activation/deactivation catch-all.**
`LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED` is the shared catch-all for both activation
failures (self-test/apply) and deactivation failures (rollback) in
`RuntimeActivationService.mapToLifecycleReason`, but its `CAUSE_ROWS` wording said "The local AI
runtime failed to **activate**" — wrong when the user was actually deactivating. Reworded to "The
local AI runtime failed to switch modes" (direction-neutral) rather than adding a 7th reason code,
keeping the fix proportionate to a wording-accuracy issue.

**Re-verified after the fix**: `:modules:app-services:compileJava` clean (no warnings, including the
javadoc summary-line warning the first pass of this fix introduced and this pass corrected),
`:modules:app-services:test` green, `check-readiness-reason-codes.mjs` green, frontend typecheck
green. Live re-checked `GET /api/ai/models/status` against this checkout's real (unchanged) state —
structure and the decoupling behave as intended.

## §Conceptual alignment check against the original tempdoc (2026-07-01, requested by user)

Re-read the original, unedited Purpose/Boundary/First-questions (lines 31-68) against everything
shipped so far. **Honest finding: Tasks 0-5 satisfy only "failure explanations" and part of
"expected status output" from the original ask list.** Demo corpus, first query, a *doctor as an
actual discoverable tool* (not just its two constituent read-paths), MCP attach instructions, and
formalizing the no-model/small-model tier remain entirely unaddressed — my own §Design theorization
D1 narrowed scope to "truthful readiness diagnosis" with a stated justification (654/655/657 own
the product-identity/MCP-matrix/mode-decomposition pieces), but that justification does not
actually cover the demo-corpus or doctor-as-a-tool gaps, which were simply deferred, not blocked on
anything. **What shipped is necessary groundwork, not the onramp itself** — you cannot build a
trustworthy doctor on a system that lies about why it's broken, but a truthful lie-detector is not
yet a doctor. Recorded here so the tempdoc's status accurately reflects partial completion rather
than reading as "656 is done."

**Separately, and more importantly**: the user asked directly whether the *original incident*
(2 agents reporting missing models/llama-server) was actually fixed. It was not. Tasks 0-5 make the
system *report* the absence precisely; they do nothing to prevent the absence. The two agents would
almost certainly hit the same wall today, just with a clearer error message. The remainder of this
pass retargets investigation at that actual gap.

## §Root-cause investigation of the acquisition gap (sixth pass, 2026-07-01)

Per the user's redirect: stop treating diagnosability as the goal and investigate *why* agents end
up without the model/runtime files at all. The original investigation pass (finding A.2) said "no
path acquires models or `llama-server.exe` outside the Tauri installer build" — true as far as it
went, but this pass found a sharper, more precise, and partly more hopeful picture by actually
running the existing mechanisms rather than reading about them.

### Finding 1 — the headless model-download path already exists and is real, not hypothetical

`POST /api/ai/install/start` (`AiInstallService.startInstall`, routed via `AiRoutes.java`, already
present before this tempdoc) is a plain Head-process REST endpoint with **zero Tauri dependency** —
confirmed by reading the method body (spawns a virtual thread, does a registry-driven download plan
and file fetch) and by running it live: started the dev stack via `dev-runner.cjs` (no Tauri shell
anywhere in the process tree), called `GET /api/ai/install/manifest` (returned the real
model-registry.v2.json contents) and `POST /api/ai/install/start {"acceptTerms":true}`, and watched
it transition through real phases (`preflight` → `plan` → `restore_runtime`) with a real computed
download plan (`downloadProfile: "GPU_FULL"`, `totalBytes: 3032935158` ≈ 2.8 GB for this machine's
detected hardware profile). This is the single most encouraging finding of this pass: **the
"how does a dev/agent get models without the Tauri installer" mechanism is not missing — it already
exists, is headless, and is reachable from a plain `:modules:ui:run`/dev-runner Head process.**

### Finding 2 — but it hard-fails before downloading anything, on a precondition that only makes sense inside a packaged install

The live run above did not proceed past `restore_runtime`: it terminated
`{"state":"failed","errorCode":"RUNTIME_MISSING","message":"Bundled AI runtime is missing and could
not be restored."}` — **before any model bytes were fetched**, confirmed by `downloadedBytes: 0`
staying at 0 across every poll. Traced the cause:
`AiInstallService.runInstallInternal()` (`modules/app-services/.../ai/install/
AiInstallService.java:369-373`) calls `RuntimeRestoreUtil.ensureRuntimePresent(homeDir)`
unconditionally, immediately after computing the download plan and **before** constructing the
`DownloadExecutor` — a hard gate, not a soft warning. If it returns `false`, the entire install
aborts, including the ONNX/GGUF model downloads that have nothing to do with llama-server.

`RuntimeRestoreUtil.ensureRuntimePresent` (`modules/app-services/.../ai/install/
RuntimeRestoreUtil.java`) copies llama-server files from a "bundled" source directory into AI Home,
but only if that source exists — it does not *fetch* anything itself. Its source-location logic
(`resolveBundledRuntimeDir()`, lines 60-75) resolves to
`<ConfigStore's repoRoot, or cwd>/native-bin/llama-server` — i.e., in this worktree,
`F:\justsearch-public\.claude\worktrees\656-onramp-investigation\native-bin\llama-server`, a
directory that **structurally only exists in a packaged installer's app-root layout** (where the
installer's own build step stages llama-server directly at the app root). In a monorepo/dev
checkout, no such directory exists or is ever populated by anything — confirmed: `find . -maxdepth 1
-iname native-bin` at repo root returns nothing in this checkout, and nothing in the codebase writes
to that exact path outside of the installer bundling step. So `ensureRuntimePresent` was *never
going to succeed* in this environment, regardless of what models are or aren't already present on
disk — this is a structural mismatch, not a one-off missing file.

### Finding 3 — the actual llama-server-fetching mechanism exists too, standalone-invokable, just never run and never wired to anything dev/agent-facing

The Gradle task chain that actually *downloads* llama-server.exe —
`downloadLlamaServerPrebuilt` → `stageLlamaServerFromPrebuilt` (`modules/ui/build.gradle.kts`,
fetches the CPU prebuilt from `github.com/ggml-org/llama.cpp` releases, confirmed in the very first
investigation pass) — is invokable on its own: `./gradlew.bat :modules:ui:stageLlamaServerFromPrebuilt
--dry-run` resolved and would execute cleanly, entirely independent of `bundleSidecarResources` or
`npm run tauri build`. It's real, working infrastructure that nobody points a dev/agent workflow at,
because in the main task graph it's wired only as `bundleSidecarResources`'s dependency (the
installer-packaging path) — confirmed already in the original investigation pass (finding A.2).

### Finding 4 — there is no registry entry for the CPU llama-server binary at all; models and the runtime binary are acquired by two structurally different mechanisms

Grepped `model-registry.v2.json` for every package: `embedding`, `splade`, `reranker`, `ner`,
`citation-scorer`, `chat` (the GGUF weights) — all registry-driven, runtime-downloadable via
`AiInstallService`. `cuda-runtime` — also registry-driven (CUDA DLLs, same
`github.com/ggml-org/llama.cpp` release family). **The base CPU `llama-server.exe` itself has no
registry entry anywhere** — it is the *only* AI-stack artifact that is exclusively a build-time
(Gradle-task) acquisition, never a runtime (registry/REST) one. This asymmetry is not documented as
an intentional decision anywhere found (ADR-0024 describes the llama-server binary being packaged
into the app bundle by the Gradle build, but does not flag this as a barrier to any non-packaged use
of "Install AI").

### Finding 5 — three different, only-partly-agreeing ideas of "where does llama-server live in dev mode" already coexist in this codebase

- `RuntimeRestoreUtil.resolveBundledRuntimeDir()` (Finding 2): `<repoRoot>/native-bin/llama-server`.
- `scripts/dev/dev-runner.cjs`'s `ensureLlamaStagedInNativeBin()` (read in the very first
  investigation pass, tempdoc 618 §3): stages from `modules/ui/build/llama-server/stage` into
  `modules/ui/native-bin/llama-server`.
- `RuntimeActivationService.resolveVariantsRoot()`'s dev-mode fallback (read in the confidence
  pass): also `modules/ui/native-bin/llama-server/variants` — **agrees with dev-runner**.

So two of the three already agree on the correct dev-mode convention
(`modules/ui/native-bin/llama-server`); `RuntimeRestoreUtil` is the outlier, carrying a
packaged-app-only path assumption inside the one mechanism (`AiInstallService`) that's supposed to
also serve as a runtime/headless flow. This is not a three-way ambiguity to resolve from scratch —
it's one outlier to reconcile against an already-established, already-correct majority convention.

### External grounding — is hand-derived dev-vs-packaged path resolution a known anti-pattern?

Checked whether this specific class of bug (a component resolving "where are my bundled resources"
by guessing a path per call site, rather than one canonical resolver) is a recognized anti-pattern
outside this codebase, since JustSearch happens to sit on Tauri. Confirmed: Tauri's own
documentation prescribes exactly one API (`PathResolver` / `resolveResource`) specifically so
resource-path resolution is consistent across dev and packaged modes, rather than each call site
hand-deriving it — "this approach works consistently in both development and packaged modes by
automatically resolving to the correct location based on how the application is being run"
([Tauri: Embedding Additional Files](https://v2.tauri.app/develop/resources/)). This validates the
diagnosis, not just as an internal convention violation but as a recognized general anti-pattern:
`RuntimeRestoreUtil` is exactly the kind of ungoverned, hand-derived path guess this pattern exists
to prevent — and the fix direction should be "converge on the one already-correct convention this
codebase already has" (the dev-runner/RuntimeActivationService agreement), not invent a new
resolver mechanism from scratch.

### Critical analysis — candidate designs, not yet chosen

**Option A — wire the existing Gradle task into the dev/worktree bootstrap.**
`stageLlamaServerFromPrebuilt` already populates exactly the directory
(`modules/ui/build/llama-server/stage`) that `dev-runner.cjs`'s auto-stage logic already knows how
to consume. If `scripts/dev/prepare-worktree.cjs` (or `dev-runner.cjs`'s own startup sequence) ran
this task once, the *existing* dev-runner auto-stage mechanism would then work end-to-end with zero
new code — this closes the loop using 100% pre-existing plumbing. Narrowest, lowest-risk option.
Downside: only fixes the dev-runner-driven path; the REST `/api/ai/install/start` flow (Finding 1-2)
would still hard-fail for anyone calling it directly (e.g. an agent driving the app over HTTP/MCP
without going through dev-runner at all).

**Option B — make `RuntimeRestoreUtil` dev-mode-aware, matching the majority convention.**
Extend `resolveBundledRuntimeDir()` (or add a fallback ahead of it) to also check
`modules/ui/native-bin/llama-server` and `modules/ui/build/llama-server/stage` — the two locations
`dev-runner.cjs` and `RuntimeActivationService` already agree on — before concluding the runtime is
missing. This would make the REST `/api/ai/install/start` flow itself dev-mode-functional: an agent
could trigger the *entire* bootstrap (runtime + models) with one HTTP call, matching how a packaged
end user's "Install AI" button already works. Directly fixes Finding 2 at its source rather than
only working around it via dev-runner. Larger question this raises: should `AiInstallService` also
gain the ability to *download* llama-server (not just restore a pre-staged copy) when neither a
bundled nor a dev-staged copy exists — i.e., should Option B extend to also invoking (or
functionally mirroring) `downloadLlamaServerPrebuilt`'s fetch logic from Java, so the REST flow
never needs the Gradle task at all? That would be a bigger, structurally different change (Option
C, below) — worth naming as a fork in the design, not deciding here.

**Option C — give the CPU llama-server binary a real registry entry, closing Finding 4 entirely.**
Add a `llama-server-runtime` (or similar) package to `model-registry.v2.json` pointing at the same
`github.com/ggml-org/llama.cpp` release asset the Gradle task already downloads, so
`AiInstallService`'s existing registry-driven download loop acquires it the same way as every other
artifact — eliminating the Gradle-task/REST-flow split entirely (Finding 4) and, as a side effect,
resolving Finding 2 for free (no separate "restore" step needed if the runtime is just another
downloaded package). Most unifying, but the largest change: touches the packaging/licensing lineage
tempdoc 632 established for llama.cpp binary provenance, and would need someone to decide whether
`RuntimeRestoreUtil`'s "restore from bundled" step still has a reason to exist afterward (probably
only for the packaged-installer fast-path, where the file is already sitting in the app bundle and
a network fetch would be wasteful) — a genuine design question, not just a mechanical addition.

**A question this pass does not resolve: is "Install AI via REST in dev mode" even meant to work at
all**, or is the Gradle-task-then-dev-runner path (Option A) the *intended* dev/agent bootstrap by
design, with the REST flow deliberately scoped to the packaged end-user app only? This pass found no
document stating either position — ADR-0024 describes the packaged-app flow only and is silent on
dev/headless use entirely. Whoever picks between Option A/B/C should settle this framing question
first, since it changes whether Finding 2 is "a bug to fix" or "a boundary correctly enforced with a
bad error message."

### What this pass deliberately did not do

No code changed. No option chosen. No new tempdoc opened (this is squarely 656's own subject —
"why agents end up without the files" is the literal live incident 656 exists to explain). The
Task-0-5 diagnosability work already shipped is left as-is, not reverted — it remains correct and
useful regardless of which acquisition-gap option is eventually chosen; a future fix to the
acquisition gap will make the now-truthful error messages fire less often, not become wrong.

## §Prior art correction — tempdoc 618 already owns half of this (2026-07-01)

The user asked directly whether a dedicated tempdoc already exists for this. Yes: **tempdoc 618**
("agent-developer-velocity-friction," `status: active`, updated 2026-07-01 — the same day as this
pass) already source-traced the identical root cause: `modules/ui/build.gradle.kts`'s `stage →
native-bin` copy happens **only** inside `bundleSidecarResources` (the packaged-installer step),
never for a plain dev checkout, so a fresh worktree's runtime directory starts empty. 618 recorded
this as recurring across four independent sessions (549, 583, 610, and its own seed) — not a one-off.

**But 618's fix only covers half the problem.** It patched the *activation* consumers —
`RuntimeActivationService.resolveVariantsRoot()`'s dev-mode fallback and `dev-runner.cjs`'s
file-probing/auto-copy (`ensureLlamaStagedInNativeBin()`) — so that *if* a runtime is already staged
somewhere findable, activation succeeds. It never touched `AiInstallService`/`RuntimeRestoreUtil` —
the *install/download* consumer — because its fix was a workaround that copies files directly,
sidestepping that flow entirely. That's exactly the gap this pass (Finding 2) found: `POST
/api/ai/install/start` hard-fails at `restore_runtime` via a code path 618 never inspected. This
matters specifically because the two agents that opened this tempdoc had **nothing** staged at all —
they needed the install/download path, not activation of an already-present runtime.

**Cross-reference recorded in both directions**: this tempdoc's frontmatter now cites 618; a
corresponding note has been added to 618 itself (see its own `§656 cross-reference` addition) so a
reader of either doc finds the other, rather than 656 silently re-deriving what 618 already
established and 618 silently missing where its own fix's scope ended.

## §Reframing the actual goal — per-worktree redownload is not acceptable (2026-07-01, user directive)

The user's explicit correction: **this tempdoc's goal is not "make `/api/ai/install/start` succeed."**
For development work, redownloading multi-gigabyte models (and the runtime binary) once per worktree
is not viable — JustSearch runs multiple parallel agent worktrees routinely (`branch-safety.md`: "up
to 3-4 agent sessions run concurrently, each in its own git worktree"), and each is a full separate
checkout. A fix that makes the install flow "work" by having each worktree independently download its
own copy would solve the reported symptom while creating a much worse, silent cost (bandwidth, disk,
time) multiplied by every worktree ever created. Any correct design must preserve or extend the
existing **download-once, share-across-worktrees** property — not routinely re-trigger downloads.

**Checked whether this property already exists, and for which artifacts:**

- **Models: already correctly shared, confirmed by re-reading the code.** `EnvRegistry.MODELS_DIR`
  (`modules/configuration/.../EnvRegistry.java:378`, wire name `JUSTSEARCH_MODELS_DIR`) is a real,
  registered environment variable. `dev-runner.cjs` already sets it to the **main checkout's**
  `models/` directory (not a copy — a direct path reference) whenever a worktree's dev-runner starts
  (per `branch-safety.md`'s own documentation of this: "the dev-runner now resolves
  `JUSTSEARCH_MODELS_DIR` from the main checkout automatically"). `AiInstallService`'s constructor
  explicitly honors this same env var when resolving `modelsDir`, with its own comment stating the
  reason: "so Install AI checks the operator-supplied dir for already-present models... When all
  required models are present, InstallPlanner produces zero downloads." **This means: if the main
  checkout has ever completed a real model install, every worktree already gets zero-download,
  zero-copy access to it for free, today, with no fix needed.** The redownload-per-worktree risk for
  *models* is already mitigated — conditional on the main checkout having models at all, which is
  the actual remaining question (see below).
- **The runtime binary: no equivalent mechanism exists at all.** Grepped `EnvRegistry.java`,
  `RuntimeRestoreUtil.java`, and `RuntimeActivationService.java` for any `NATIVE_BIN`/native-bin-scoped
  environment variable: **zero matches.** The only cross-worktree sharing for the runtime binary is
  `dev-runner.cjs`'s own JS-side fallback (`ensureLlamaStagedInNativeBin()`, from 618): if the
  worktree's own `modules/ui/build/llama-server/stage` is absent, it falls back to the **main
  checkout's** `modules/ui/build/llama-server/stage` — avoiding a re-*download* (bandwidth), but
  still performing a full local file *copy* into the worktree's own `modules/ui/native-bin/` (disk
  duplication, smaller in absolute terms than models — the CPU llama-server prebuilt is on the order
  of 100-300MB, not multi-GB — but the same class of avoidable waste, and this sharing exists only in
  JS/dev-runner, invisible to the Java-side `AiInstallService`/`RuntimeRestoreUtil` REST flow, which
  has no shared-location awareness whatsoever — it only ever checks one hardcoded,
  packaged-installer-shaped path (Finding 2).

**Revised framing for the acquisition-gap fix, superseding the unqualified Options A/B/C above:**
whichever option is chosen must explicitly satisfy the same "populate once, share via existing
main-checkout-first resolution" property models already have — not just stop hard-failing.
Concretely, this reframes each option:

- **Option A (wire the Gradle task into dev bootstrap)** is fine *only* if it's understood to run
  once, ideally against the main checkout, relying on `dev-runner.cjs`'s existing
  main-checkout-fallback copy (which already exists, per 618) to propagate to every other worktree —
  not re-run per worktree as a matter of course. This should be stated explicitly wherever this
  option is documented, since "add it to `prepare-worktree.cjs`" (the phrasing used earlier in this
  pass) reads as "run per worktree" unless qualified.
- **Option B (make `RuntimeRestoreUtil` dev-mode-aware)** needs a real design decision now visible
  that wasn't visible before this reframe: should it merely check the existing
  `modules/ui/native-bin/llama-server` / `modules/ui/build/llama-server/stage` locations (which, per
  the finding above, are **worktree-local**, not shared) — or should it be extended to introduce a
  proper shared-location convention analogous to `JUSTSEARCH_MODELS_DIR` (e.g. a new
  `JUSTSEARCH_NATIVE_BIN_DIR`/`EnvRegistry` entry, honored by `RuntimeRestoreUtil`,
  `RuntimeActivationService`, and `dev-runner.cjs` alike, all resolving to the main checkout by
  default)? The former is a smaller, faster fix that inherits models' existing weaker (copy-based, not
  zero-copy) sharing tier; the latter closes the gap properly, bringing the runtime binary up to the
  same zero-copy, single-source-of-truth tier models already have, and would also let
  `RuntimeRestoreUtil` and `RuntimeActivationService`/`dev-runner.cjs` finally agree through one
  registered mechanism instead of three independently-hardcoded path conventions (Finding 5). Given
  this pass's own external grounding (Tauri's `PathResolver` principle — one canonical resolver, not
  per-call-site guessing), the latter is the more defensible long-term shape, but it is a strictly
  bigger change than "check two more directories."
- **Option C (registry entry for the runtime binary)** would need the *same* consideration — if
  `AiInstallService` downloads llama-server.exe as just another registry package, it must resolve its
  target directory through whatever the shared-location convention ends up being (existing
  `modelsDir`-style resolution, or the new native-bin-equivalent above), not silently reintroduce a
  worktree-local-only target and regress the property this section exists to protect.

**Resolved (checked directly):** the main checkout (`F:\justsearch-public`, non-worktree) has 7 of 9
model files already present, including the chat GGUF — models are partially populated there
(probably by the same manual process that partially populated this worktree's copy). But its
`modules/ui/native-bin/` does not exist and `modules/ui/build/llama-server/` does not exist either —
**the main checkout has never built or staged the runtime binary, so there is currently nothing for
any worktree's sharing fallback to find even if it worked correctly.** This confirms the runtime
binary has no working acquisition path *anywhere* right now — not per-worktree, not even at the one
shared location every mechanism (618's dev-runner fallback, and whichever acquisition-gap option gets
chosen here) ultimately depends on. **"Populate the main checkout's runtime binary once" is therefore
the actual first concrete milestone** — after that, 618's existing copy-fallback already propagates it
to every worktree today with no further fix needed; only the *first* population (at the main
checkout, once) requires one of Options A/B/C above.

## §Long-term design (seventh pass, 2026-07-01)

This pass supersedes the unqualified "Options A/B/C" of the sixth pass. The user's directive
reframed the goal a second time, decisively: **the CPU llama-server should not be available in the
development environment at all.** Agents that fail to get the GPU runtime running silently fall back
to the CPU llama-server, which is ~10x slower *and* saturates every CPU core running a 9B model —
which, on one machine shared by 3-4 concurrent agent worktrees, halts all of them. The CPU
llama-server exists in dev only because of an early convenience decision (tempdoc 618 §3) that this
design concludes was a mistake. Investigated via two source-tracing subagents + the product-direction
tempdocs; the design below is grounded in verbatim code and settled prior decisions, not preference.

### The reframe: this is conforming dev to the *already-settled* product direction, not changing it

The strongest single finding of this pass: **the codebase's own settled product-direction doc already
says the CPU llama-server chat path should not exist.** Tempdoc 381 ("Model Distribution
Architecture," `status: done`) §"The GGUF/LLM Dimension — Settled" states verbatim: *"GPU-primary. …
CPU users do not download GGUF — chat/RAG requires GPU for acceptable performance. … On CPU,
inference takes minutes per response for the 8B parameter model. **This isn't degraded — it's
unusable.** Downloading 6.2 GB for a feature that doesn't work wastes bandwidth and disk space."* The
three download profiles (381 §"GPU-primary simplification") confirm it: only the **GPU-full** profile
downloads the GGUF, and it runs on the `cuda12` variant; the **GPU-lite** and **CPU** profiles never
download the GGUF at all. So *no production user is ever supposed to run the chat GGUF on a CPU
llama-server.*

Yet dev-runner's `ensureLlamaStagedInNativeBin()` (tempdoc 618 §3, `scripts/dev/dev-runner.cjs`
~383-407) auto-stages a CPU baseline into a fresh worktree specifically *"so `ai_activate
{variantId:"default"}` can verify the LLM tier without the ~3 GB 'Install AI' GPU download"* — and
`ai_activate {default}` points that CPU llama-server at the configured 9B GGUF. That is exactly the
"unusable" path 381 says must never run, re-introduced into dev as a verification shortcut. **The
design is therefore not "change the product direction" — it is "make the dev environment obey the
product direction it already has."** That framing matters: it converts a debatable preference into a
consistency fix against a settled decision, and it means Move 1 below removes an *inconsistency*, not
a feature.

### Move 1 — remove the CPU llama-server from the dev environment; inference fails *closed*

Stop the dev-time provisioning of the CPU baseline. The CPU baseline reaches dev through exactly two
dev-only seams (both confirmed by source-trace, both removable without touching production):
1. `dev-runner.cjs`'s `ensureLlamaStagedInNativeBin()` copying the flat CPU baseline into
   `modules/ui/native-bin/llama-server/` (618 §3), **and** the `JUSTSEARCH_SERVER_EXE` candidate in
   `resolveAiDevEnv()` that points at that flat baseline as a second choice after `variants/cuda12/`.
2. The dev *consumption* of the Gradle CPU stage (`build/llama-server/stage/llama-server.exe`) as the
   copy source for #1. (The Gradle `stageLlamaServer*` chain itself must stay — it also feeds
   production bundling, a separate seam — but nothing in dev should *consume* its flat CPU output.)

The elegant consequence: **removing the CPU baseline removes the trap by construction, with no new
guard.** Traced silent-fallback path: with the CPU baseline absent, `dev-runner`'s
`JUSTSEARCH_SERVER_EXE` resolution finds only `variants/cuda12/…` (or nothing);
`HeadlessApp.resolveDefaultServerExecutable()` returns null; `maybeAutoSelectCuda12Variant` skips
("default server not found"); `InferenceConfig.findServerExecutable`'s silent
"GPU-requested-but-no-cuda12 → return the flat CPU baseline" branch
(`InferenceConfig.java` ~345-358) has no CPU baseline to return. So when the GPU runtime is
genuinely unavailable, LLM inference is *unavailable* — and now truthfully reported as
`inference.runtime_not_installed` via the Tasks 0-5 substrate already shipped — instead of silently
degrading onto the shared CPU. **Fail closed, not fail open.** This is the whole point: the trap is
not a missing check, it is the *presence of a fallback target that shouldn't exist in this
environment*; the fix is to remove the target, and the "unavailable" outcome falls out for free.

**Preserve the explicit opt-in CPU lane, kill only the silent default.** 381 §"Prevention Through
Structure" deliberately keeps a `jseval --cpu` path for CPU regression testing. The design removes
the *silent default* fallback onto CPU, not the *ability to deliberately exercise* a CPU path when a
developer explicitly asks. The distinction is silent-default vs explicit-opt-in — the former is the
trap, the latter is a legitimate test lane. (This pass did not fully pin whether `jseval --cpu`
touches the llama-server LLM path or only the ONNX encoders; that boundary should be confirmed at
implementation so the opt-in lane, whatever its exact scope, is preserved.)

### Move 2 — the GPU (cuda12) runtime becomes a shared, acquire-once artifact

Move 1 alone would leave dev with *no* inference, because acquiring the GPU runtime is currently a
per-worktree ~3 GB "Install AI" download (the cuda-runtime registry package extracts into the
worktree's own `{aiHome}/native-bin/llama-server/variants/cuda12/`). So Move 1 is only viable coupled
with Move 2: **the GPU runtime must gain the same acquire-once/share-across-worktrees property models
already have.** Confirmed asymmetry (source-traced): models have `EnvRegistry.MODELS_DIR`
(`JUSTSEARCH_MODELS_DIR`), which dev-runner sets to the *main checkout's* `models/` so every worktree
references (zero-copy) one shared download; the runtime binary has **no `NATIVE_BIN`-equivalent env
var anywhere in the Java code** — its only cross-worktree sharing is dev-runner's JS-side copy from
the main checkout's stage dir, and that copy path deliberately skips `variants/` (never propagates
cuda12).

The design: resolve the dev runtime binary from a single canonical shared location, referenced not
copied, exactly as models are. Recommended shape (the more correct long-term one, and the present
problem *is* about the sharing mechanism being absent): rather than minting a second parallel env var
next to `JUSTSEARCH_MODELS_DIR`, treat **the AI home as the shared unit** — `models/` and
`native-bin/` are both subdirectories of the AI home (`PlatformPaths.resolveAiHome()`), and the dev
environment already points models at the main checkout's copy. Extend that same "resolve from the
canonical AI-home location" to `native-bin/`, so *all* large machine-global AI artifacts share one
authority by construction. The runtime binary then resolves, read-only, from wherever the machine
downloaded it once. (A smaller alternative — a dedicated `JUSTSEARCH_NATIVE_BIN_DIR` mirroring
`JUSTSEARCH_MODELS_DIR` — is noted but is the weaker shape: it perpetuates the "one env var per
artifact" pattern the AI-home framing subsumes, and the recognized Tauri `PathResolver` guidance
(sixth pass) favors one canonical resolver over per-artifact ad-hoc resolution.)

**Milestone ordering (from the sixth pass, still holds):** the main checkout itself has *never*
staged or downloaded the GPU runtime, so "populate the canonical location once" is the first concrete
milestone; propagation to worktrees is then pure reference-resolution. The canonical location could be
the main checkout's `native-bin/`, or — a nice property worth evaluating at implementation — the
*production* AI home (`%APPDATA%\io.justsearch.shell\native-bin\`), which is machine-global and would
let a dev worktree reuse the runtime a locally-installed packaged app already downloaded, with zero
dev-specific duplication. Choosing among these is implementation-level; the design requirement is only
"one canonical location, referenced not copied."

### Why not just fix the install flow (superseding sixth-pass Options A/B/C)

The sixth pass proposed making `POST /api/ai/install/start` succeed in dev (via `RuntimeRestoreUtil`
dev-awareness, or a registry entry for the CPU binary). Under this pass's reframe, **those options
partly aim at the wrong target**: options that would restore/download a *CPU* llama-server in dev are
now anti-goals (they provision the exact trap Move 1 removes). What survives from the sixth pass is
narrower and re-pointed: the install/acquisition machinery should be able to place the *GPU* runtime
at the shared canonical location once (Move 2's "populate once"), and the `restore_runtime`
hard-gate's packaged-installer-only path assumption (`RuntimeRestoreUtil.resolveBundledRuntimeDir()`)
should either be made shared-location-aware or bypassed in dev — but the objective is a shared GPU
runtime, never a per-worktree or CPU one.

### Scope guard — what this design must NOT touch

- **Production bundling is untouched.** `bundleSidecarResources` (`modules/ui/build.gradle.kts`
  ~1424-1429, `into("native-bin/llama-server")`) bundles the CPU baseline into the packaged installer
  for genuine no-GPU end users — that is legitimate single-user graceful degradation and the
  `runDeactivate` target, and Move 1 is strictly dev-scoped. The two dev seams are cleanly separable
  from this production seam (confirmed by source-trace).
- **The ONNX CPU paths stay.** The embedder/reranker/citation-scorer CPU execution providers are
  intentional and fast enough (the citation scorer is CPU-only *by design*, precisely to avoid GPU
  contention — 05-ai-architecture). Move 1 targets the *llama-server LLM* CPU path only, not ONNX
  CPU execution. Do not conflate the two.

### An honest open question this design surfaces (candidate scope, not decided here)

381's own logic implies the CPU llama-server has a *questionable role even in production*: since no
download profile ever runs the GGUF on CPU, a GPU-full production user whose `cuda12` variant is not
yet installed (or whose auto-select fails) *also* silently drops onto the flat CPU baseline running
the GGUF — the same "unusable" path, just triggered by a different precondition
(`InferenceConfig.java` ~345-358 and `HeadlessApp` ~986-1001 both silently return/keep the CPU
baseline). Whether the CPU llama-server should exist in *production* at all, or whether production
should also fail closed rather than silently drop a GPU user onto unusable CPU chat, is a larger
product/packaging decision owned by tempdoc 374 (still `open`). **This tempdoc names it and does not
decide it** — the present problem (the dev DOS) is fully addressed by Moves 1+2 without touching the
production question, and per scope discipline the production change is not required by the present
problem. Flagged so 374's owner sees the coupling.

## §Reach — principle recognition (recognizing ≠ building)

Stepping back from the immediate fix, this design instances two principles — one already established
elsewhere in the system (conform to it), one genuinely new (name it, note candidate scope, don't
build the general enforcement now).

### Principle A (new): *A fallback is "graceful degradation" only if its cost is borne solely by the tenant that triggered it. When the fallback consumes a resource shared across tenants, its cost is externalized onto co-tenants, and "graceful degradation" becomes denial-of-service — so in a multi-tenant context, fail closed rather than degrade onto the shared resource.*

The CPU llama-server fallback is genuinely graceful on a single-user desktop (the CPU it saturates is
private to that one user, who chose to wait). It is a denial-of-service across 3-4 concurrent agent
worktrees on one dev machine (the CPU it saturates is shared; one worktree's "graceful degradation"
freezes the other three). **The gracefulness of a fallback is a property of the deployment context,
not of the fallback itself** — specifically, of whether the resource it degrades onto is tenant-local
or tenant-shared. This is why the *same* code path is a correct product feature in production and a
bug in dev, and why the fix is environment-scoped rather than a code-path deletion.

- **Where else it plausibly applies (candidate scope, not proposed work):**
  - The ONNX embedder/reranker silent CPU fallback (register F-002, F-011: "Without [the CUDA path],
    all ONNX GPU sessions fall back to CPU silently"). Same shape, milder magnitude (300M-param models
    at ~160ms on CPU, not a 9B model at minutes) — a judgment call whether it clears the
    "denial-of-service" bar; likely tolerable, hence *note, don't fix*. The register already carries
    the adjacent open item FW-002 ("CPU fallback latency budget — decide if CPU CE should be disabled
    under latency pressure"), which is this principle seen from the latency side.
  - The single-GPU arbitration across worktrees (tempdoc 598's catch-22) and concurrent `jseval` eval
    runs sharing one machine — any dev-mode "if the fast resource is busy, use the shared slow one"
    path.
- **Existing violations:** the llama-server CPU fallback (this tempdoc fixes it, dev-scoped); the ONNX
  CPU fallbacks (noted, not fixed — magnitude may not warrant fail-closed).
- **Deliberately NOT built now:** a generalized "no fallback onto a contended shared resource"
  guard/gate. The present problem requires fixing only the llama-server case in dev. Building general
  enforcement over every CPU/GPU fallback would be premature abstraction over paths whose harm varies
  by magnitude and context — exactly the judgment the principle itself says is context-dependent.

### Principle B (conform, don't re-invent): large machine-global AI artifacts resolve from one canonical shared location and are *referenced, never copied/re-acquired per checkout*.

This is the `canonical-authority-and-projection` principle already named in this tempdoc's earlier
passes (from tempdoc 650) and established across the system for other domains (587 host-capability:
one resolver, projection-not-fork; 553/564 canonical-record-vs-fork; 598 pipeline-as-projection).
Models already conform via `JUSTSEARCH_MODELS_DIR` (one canonical copy at the main checkout,
worktrees reference it). Move 2 conforms the runtime binary to the *same* authority rather than
minting a parallel mechanism — which is precisely why the recommended shape is "extend the AI-home
resolution models already use" over "add a second independent env var." No new principle is invented
here; the runtime binary is brought under an existing one.

There is also a second, tighter conformance worth naming: **inference-runtime *selection* should
project truthfully from the GPU-capability authority, not silently degrade.** 587/598 established that
capability-derived surfaces must project from the one authority that knows the capability, expressing
loss faithfully rather than papering over it. The silent CPU fallback is inference-runtime selection
*failing* to project GPU-capability loss — it hides "GPU unavailable" behind a working-but-unusable
CPU server. Move 1 (fail closed) realigns runtime selection with that established projection
principle; combined with the Tasks 0-5 substrate already shipped, "GPU unavailable" now surfaces as a
truthful reason code instead of a silent 10x degradation. So Move 1 is simultaneously an instance of
the new Principle A (multi-tenant externalized cost) *and* of the existing capability-projection
principle — the two framings agree on "fail closed, report truthfully."

### External-research judgment for this design (2026-07-01)

Considered whether any load-bearing part of the design sits on actively-shifting external ground
warranting a web-research pass. Conclusion: **no research pass** — reasoning recorded for continuity.

- **Fail-closed-on-capability-loss (Move 1), shared acquire-once resolution (Move 2), and the
  multi-tenant-externalized-cost principle (Reach A)** are settled engineering practice (fail-fast /
  bulkhead / load-shedding; shared machine-global caches, the same shape as `HF_HOME`/`HF_HUB_CACHE`
  and the codebase's own `JUSTSEARCH_MODELS_DIR`). Not fast-moving; nothing to check.
- **The one genuinely fast-moving input is local-LLM *CPU* inference performance** (llama.cpp CPU
  kernels / quantization / AMX are actively improving). It is nonetheless **not decision-relevant**
  here, for three reasons: (1) the design rests on a *first-hand, current* operator observation on the
  actual dev hardware+model ("CPU fallback is ~10x slower and completely halts all other work") — a
  stronger primary source than any general web claim about CPU inference speed; (2) the load-bearing
  harm is *structural*, not throughput-dependent — a 9B model on CPU saturates all cores and DOSes
  co-tenant worktrees whether it runs at 3 or 8 tok/s, so a faster-CPU finding cannot flip the
  contention argument; (3) the one alternative external trends might favour (keep CPU but *bound* it
  via resource limits instead of removing it) is foreclosed by the operator directive that the CPU
  llama-server should not exist in dev at all. So there is no open design fork a search would resolve.
- **Public-doc currency caveat:** the "CPU 9B is unusable" claim is attributed to the settled internal
  direction (381) + the operator observation, not asserted as a universal fact, and the tempdoc
  carries the standard dated-history caveat — so the public-claims lane is not at risk from this claim
  aging. No external code/text/assets were copied or adapted; nothing to attribute under the
  license/notices lane.

## §Confidence pass (eighth pass, 2026-07-01)

Confidence-building before implementing Move 1 + Move 2. Live experiments (dev stack) + my-own
re-verification of the subagent traces. **No feature code changed.** Enabling discovery: a *complete*
cuda12 GPU runtime (25 files, all 4 required DLLs + exe) already existed on the dev machine — staged in
another dev checkout under `native-bin/llama-server/variants/cuda12/` — so GPU viability and
cross-location referencing were testable with zero download.

### Resolved (per the ranked uncertainties)

**#1 GPU viability [make-or-break] — CONFIRMED, clean live pass.** Started a dev stack in this
worktree with `JUSTSEARCH_SERVER_EXE` pointed at that *foreign* (other-checkout) cuda12 exe, configured the
Qwen 9B GGUF + `gpuLayers:99`, triggered `POST /api/inference/mode {online}` → transition succeeded.
`/api/inference/status`: `mode:online, available:true, activeModelId:Qwen_Qwen3.5-9B-Q4_K_M.gguf,
lastStartupDurationMs:13467, tier:gpu_12gb_plus, cudaAvailable:true`. The OS process was verifiably
the foreign exe: `...\native-bin\llama-server\variants\cuda12\llama-server.exe
-m ...Qwen_Qwen3.5-9B-Q4_K_M.gguf ... -ngl 99 -fa on`. A real query (`/api/chat/free`, prompt "capital
of France") streamed a coherent grounded answer: *"The capital of France is Paris."* (22 prompt / 32
total tokens). **GPU-only dev inference is viable here — the design's foundational premise holds.**

**#2 Does Move 2 need the Install-AI/`restore_runtime` flow? — CONFIRMED NO (big simplification).**
The entire GPU run above bypassed Install AI, `RuntimeActivationService`, and `RuntimeRestoreUtil`
completely — the runtime was made available purely by pointing `JUSTSEARCH_SERVER_EXE` at a foreign
path. So the sixth-pass "`restore_runtime` hard-gate blocks populating cuda12" concern is **irrelevant
to the design**: Move 2 is a reference/env-var concern, not a download-flow concern. The install-flow
fix contemplated in the sixth pass (Options A/B/C) is not needed for this design at all.

**#3 Is "fail closed" clean? — CONFIRMED graceful (with one minor polish gap).** Restarted with no
runtime (this worktree has no `native-bin` and, notably, `installDist` produces no CPU stage dir — so
dev-runner had nothing to auto-stage: it was *already* naturally fail-closed). Stack came up cleanly:
Head + Worker `READY`, 5 docs indexed, and `POST /api/knowledge/search` returned 3 HYBRID results —
**search fully works with inference down.** The online-transition attempt returned a graceful
structured error (`MODE_SWITCH_FAILED / INVALID_CONFIG: "llama-server executable not found"`,
non-retryable); a direct `/api/chat/free` returned a graceful `AI_OFFLINE` SSE error. **No crash, no
hang, Head stayed up.** *Minor gap:* the manifest's `ai.pendingReason` on this path shows the generic
`"Inference offline"`, not a specific reason code — because the `switchToOnlineMode`
(`InferenceLifecycleManager`) failure path is distinct from the `RuntimeActivationService` path that
Tasks 0-5 wired to specific manifest reasons. Fully delivering "fail closed AND specifically diagnosed
on the manifest" would extend Tasks-0-5-style wiring to the mode-transition path — optional polish,
not a blocker (the outcome is already graceful + truthful to the caller).

**#4 Move 2 mechanism feasibility + read-only safety — CONFIRMED (mechanism already exists).** The
runtime-sharing primitive Move 2 needs **already exists as `JUSTSEARCH_SERVER_EXE`** (registered in
`EnvRegistry`, and `dev-runner.cjs` already sets it). Pointing it at a foreign cuda12 exe worked
end-to-end (the DLLs load adjacent to the exe from that foreign dir). Read-only check: after the full
GPU run, the foreign cuda12 dir was **untouched** — still 25 files, all mtimes at the pre-run baseline,
nothing created/modified. So **cross-worktree read-only sharing of the runtime is safe** (llama-server
reads exe+DLLs, writes nothing there). Also mapped the resolution model: `aiHome = JUSTSEARCH_HOME
override | dataDir`; `native-bin = {aiHome}/native-bin` (no independent native-bin env var); but the
two things inside native-bin that matter each have a dedicated env hook — the LLM exe
(`JUSTSEARCH_SERVER_EXE`) and the ORT-CUDA DLLs (`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`). So Move 2 for
the LLM needs **no new structure** — it's "point the existing env var at a shared location," exactly
as `JUSTSEARCH_MODELS_DIR` already does for models.

**#5 Blast radius — CONFIRMED small and understood.** Re-verified `InferenceConfig.findServerExecutable`
myself: `JUSTSEARCH_SERVER_EXE` is resolution candidate #1 and short-circuits everything, so in dev
**dev-runner is the sole gatekeeper** (the other fallbacks — `{dataDir}/native-bin`,
`{repoRoot}/native-bin`, the shell-headless dev-layout path — are all empty in a clean worktree).
`BootstrapInferenceFactory` (eval-autostart) uses the *resolved* server executable
(`Files.exists(config.serverExecutable())`), no CPU-baseline dependency — cuda12 satisfies it.
`jseval` LLM eval (`backend.py`, `qu_spike.py`) uses the `JUSTSEARCH_SERVER_EXE`-resolved runtime, not
a hardcoded CPU path; jseval's `cpu_only` refers to the ONNX *reranker*, not the LLM — so the explicit
CPU test lane is ONNX and is unaffected. The MCP dev-tools runtime check
(`justsearch-dev-mcp/server.mjs` ~1650) is explicitly **REPORT-ONLY ("does NOT gate ready")** — removing
CPU staging only changes a cosmetic readiness message (which the implementation should update to stop
advertising the CPU baseline).

### What this pass changed about the design (net simplification)

Move 1 + Move 2 collapse to a **change concentrated almost entirely in `scripts/dev/dev-runner.cjs`**:
(a) stop `ensureLlamaStagedInNativeBin()` from staging a CPU baseline; (b) change `resolveAiDevEnv()`
to resolve `JUSTSEARCH_SERVER_EXE` from a *shared canonical cuda12 location* and drop the CPU-baseline
candidate. Plus: pick the canonical shared location + populate it once (trivially satisfiable — an
existing cuda12 can simply be referenced/copied there; two already exist on disk), and update the
cosmetic MCP readiness message. Optional follow-ons: extend the truthful-reason wiring to the
mode-transition path (#3 gap), and share the ORT-CUDA path (`JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`) for
GPU *embedding* too (secondary — CPU ONNX embedding is tolerable). Production bundling
(`bundleSidecarResources`) is untouched, confirmed cleanly separate.

### Residuals (not fully de-risked)

- **Fresh-acquisition-at-the-canonical-location** was not tested — I *referenced* an existing cuda12,
  I did not *acquire a new one* at the chosen shared location. But "populate once" is a one-time
  bootstrap with multiple trivially-working options (copy an on-disk runtime; run Install AI once at a
  production-like AI home), not the recurring mechanism — a choice, not a risk.
- **Canonical-location choice** (main checkout vs dedicated shared dir vs production `%APPDATA%` AI
  home) is an open decision, not a risk.
- The actual `dev-runner.cjs` edits (vs my manual env-var simulation of the identical target behavior)
  are not yet written — but the target behavior itself is now proven end-to-end.

### Confidence rating: **8 / 10**

Up from what would have been ~4/10 before this pass. The single make-or-break risk (is GPU-only dev
even viable here?) is resolved with a clean live pass; the sharing mechanism turned out to **already
exist** (`JUSTSEARCH_SERVER_EXE`) and I ran the *exact* target behavior end-to-end; the scary Move-2
coupling to the `restore_runtime` install bug is **eliminated** (reference, not install); fail-closed
is proven graceful; the blast radius is small, concentrated in one Node file, with the rest cosmetic;
production is cleanly separate. Held below 9 by: the canonical-location decision + one-time-populate
bootstrap (choices, low-risk but unmade), the optional manifest-reason polish, and the secondary
ORT-GPU-embedding sharing question.

### Difficulty + recommended model/effort

**Difficulty: LOW-to-MODERATE.** The core is a small, concentrated edit to one Node file
(`dev-runner.cjs`), plus a one-time populate and a cosmetic message update — mostly config-plumbing,
not deep Java architecture. The judgment calls (canonical location; how much of the optional
manifest-reason + ORT-GPU polish to fold in; careful production-non-regression verification) are
bounded and already framed by the tempdoc.

**Recommended: Sonnet at medium effort for the core** (dev-runner CPU-removal + shared-cuda12
resolution + populate + MCP message + live re-verify), bumping to **medium-high** only if the same pass
also takes on the optional cross-language polish (the mode-transition manifest-reason wiring, which
touches Java + the reason-code substrate + its gate, and the ORT-CUDA sharing). **Not Opus** — the
hard design and the viability/mechanism unknowns are resolved; what remains is well-understood,
mostly-mechanical change with a couple of bounded decisions, which is squarely Sonnet's strength.
Effort should be medium (not low) because production-non-regression and the "don't reintroduce a
silent CPU path via some other seam" check reward care, and the implementer must live-verify GPU-only
dev end-to-end (exactly the run this pass proved is achievable).

## §Implementation (ninth pass, 2026-07-01)

Move 1 + Move 2 implemented. **Node-only change** (zero Java / gradle / production touched), confirmed
by `git status`. Files:

- **`scripts/dev/dev-runner.cjs`** — the heart of both moves.
  - `ensureLlamaStagedInNativeBin()` (618 §3, staged a CPU baseline into the worktree) →
    **`ensureSharedCuda12Staged()`**: never stages a CPU baseline; instead one-time-populates the
    **shared main-checkout** cuda12 (`mainRepoRoot/modules/ui/native-bin/llama-server/variants/cuda12`)
    from the Gradle cuda stage, idempotent, `hasAnyLlamaRuntime` don't-clobber guard preserved.
  - New pure helper **`resolveCuda12ServerExe(worktreeRoot, sharedRoot, exeName)`** (exported in
    `__test`): resolves the worktree's own cuda12 first, else the shared main-checkout cuda12; returns
    `null` (→ `JUSTSEARCH_SERVER_EXE` unset → fail closed) otherwise. **Never resolves a CPU baseline.**
    `resolveAiDevEnv()`'s server-exe block now calls it (dropped the flat-CPU-baseline + legacy-root
    candidates).
- **`scripts/dev/prepare-worktree.cjs`** — corrected the now-false "auto-stages a CPU baseline" docs;
  documents the one-time `./gradlew :modules:ui:stageLlamaCudaVariant` at the main checkout (GPU-only).
- **`scripts/dev/justsearch-dev-mcp/server.mjs`** — the REPORT-ONLY runtime-readiness check now probes
  cuda12 only (worktree + shared main, via `resolveMainRepoRoot`) and its message points at the
  `stageLlamaCudaVariant` remedy instead of advertising a CPU baseline.
- **`scripts/dev/test-dev-runner-runtime-resolution.mjs`** (new) — regression test pinning the
  load-bearing guarantee: cuda12-only resolution, shared-vs-worktree precedence, and — the core
  anti-regression — a CPU baseline is **never** resolved even when present (returns `null`).

### Deferred (documented in §Long-term design; not in this pass, by scope)
The mode-transition manifest-reason polish and the ORT-CUDA (GPU embedding) sharing — both explicitly
optional/orthogonal; the reported problem (the 9B-LLM CPU DOS) is fully addressed without them.

### Validation (all passed)
1. **Static/tests:** `node -c` on all edited files; the new resolution test green; existing
   `test-dev-runner-{pruning,admission,gate-integration}.mjs` green (regression clean). No Java changed.
2. **Auto-populate (my `ensureSharedCuda12Staged`), live:** seeded the main checkout's Gradle build
   stage with a complete cuda12 (from the already-present 643 runtime, cuda prebuilt zips confirmed
   cached — the acquisition path is real, just not re-downloaded), started a dev stack from this
   worktree with **no env override** → console logged `[dev] 656: staged shared cuda12 GPU runtime
   into F:\justsearch-public\...\variants\cuda12`, and the previously-absent shared native-bin cuda12
   was **populated**. No CPU baseline was staged anywhere (Move 1 confirmed).
3. **End-to-end GPU-only dev with the ACTUAL dev-runner logic:** the running llama-server was verifiably
   the **shared main-checkout** exe (`F:\justsearch-public\modules\ui\native-bin\llama-server\variants\
   cuda12\llama-server.exe ... -ngl 99`), resolved by `resolveCuda12ServerExe` from the shared location
   (not the worktree). A real API query (`/api/chat/free`) answered coherently on GPU ("Red, green,
   blue").
4. **Browser (required — user-visible surface):** loaded the real UI (`localhost:5173`); status bar
   showed **"Online — Qwen Qwen3.5-9B"** (green) — the only banner a mild "LambdaMART reranker not
   configured", not "AI offline"; a Document Q&A ("cross-encoder reranking arbitration") returned a
   coherent, grounded, **cited** answer rendered in the chat surface. GPU inference works end-to-end
   in the real UI via the shared runtime.
5. **Fail-closed** (re-confirmed this session, confidence pass): with no cuda12 resolvable, the stack
   starts, search works (HYBRID), and chat fails gracefully (`AI_OFFLINE`) — no CPU fallback, no crash.
6. Dev stack stopped cleanly; no llama-server left running. The shared cuda12 populated at the main
   checkout is **left in place** — that is the intended one-time bootstrap, and GPU dev now works for
   every worktree with zero per-worktree download (Move 2 delivered).

**Outcome:** dev inference is now GPU-only with a shared, acquire-once runtime. An agent that can't
reach the GPU no longer silently drops onto a 9B-on-CPU path that DOSes concurrent worktrees — it gets
fast GPU inference (shared) or a clean, truthful "unavailable" (fail closed). The reported incident's
root cause is fixed. Production bundling untouched.

### Post-implementation review fix (2026-07-01)

A critical review of the above found one substantive bug: `ensureSharedCuda12Staged`'s populate guard
used `hasAnyLlamaRuntime` (flat CPU baseline OR any variant), so a **stray flat CPU baseline** in the
shared native-bin (e.g. left by the old dev-runner if ever run from the main checkout) would trip the
guard and silently block cuda12 auto-provisioning — GPU dev would fail closed with no clear cause even
after the documented `stageLlamaCudaVariant` step. Fixed by guarding **specifically on the cuda12
exe's presence** (still protects an Install-AI'd/staged cuda12; a stray CPU baseline no longer blocks),
extracting the populate into a pure, unit-tested `stageSharedCuda12(sharedNativeBin, cudaStageCandidates,
exeName)` helper (mirroring `resolveCuda12ServerExe`), and removing the now-unused `hasAnyLlamaRuntime`.
The regression is pinned by a new test case (stray CPU baseline present + no cuda12 → still provisions).
Re-verified: extended + existing dev-runner tests green; live smoke — the wrapper correctly skips when
cuda12 is already present, resolves the shared main-checkout cuda12 (`-ngl 99`), and a real query
answers on GPU. Other review items were ruled out (clean rename; nothing depends on `ai_activate
{default}`; a harmless `mainRepoRoot` shadow in the MCP readiness message). No security/privacy issues.

## §Onramp design — the remaining core (tenth pass, 2026-07-01)

Returns to the tempdoc's *original* Purpose (the five-minute onramp) now that the foundation is
shipped. This pass designs the remaining core; nothing is implemented. Kept general (shape, seams,
first-success definitions), not implementation-level.

### The reframe: what remains is an *assembly* problem, not new capability

The runtime is now deterministic (GPU-only, shared, fail-closed) and diagnosable (reason codes +
`/api/ai/models/status` preflight). A fresh codebase-wide investigation (verified against `main`)
confirms the onramp's ingredients **already exist, scattered**: the ingest + search endpoints
(`POST /api/knowledge/{ingest,search}`), a documented MCP connect surface with 5 tools
(`docs/reference/mcp-production-server.md`), the preflight endpoint, the runtime manifest, and even a
tiny contamination-free corpus candidate (`scripts/jseval/util-smoke/corpus/`). What is **absent** is
any *assembly* of them: no onboarding demo corpus, no composed "doctor," and no scripted "index →
query → verifiable result" path for a developer/agent running from source. **The correct long-term
design is therefore composition + a runnable proof of first-success — not new subsystems.** This is the
central judgement: the scope is "compose what exists, prove it works, make it discoverable," and the
design deliberately does not add capability the problem does not require.

### O1 — a tiered first-success ladder (conforms to 657's modes)

The onramp must not be all-or-nothing (index nothing useful until a ~9 GB download). It is a ladder of
**complete, self-sufficient first-successes**, each producing verifiable value:
- **Tier 0 — zero model, instant:** index the demo corpus → keyword/BM25 query → a real ranked result
  list. **Live-proven** (the confidence pass: `effectiveMode: HYBRID`, real hits, with the LLM
  offline). This is the answer to 657's open question *"can a no-model mode provide enough value for
  developer evaluation?"* — **yes**.
- **Tier 1 — ONNX (~3.5 GB):** + semantic/hybrid retrieval + reranking. First-success = a
  semantically-relevant result (a hit keyword search misses).
- **Tier 2 — GPU + GGUF:** + a cited RAG answer. First-success = a grounded, cited answer (**proven
  this session** in the real browser — a Document Q&A with a source citation, GPU-served).

Each tier is a *complete* success, not a degraded fraction of the full product. **Ownership seam:**
656 owns *"what is the first success at each tier, and how do you know you reached it"* (activation
economics — the Purpose's wedge); **657** owns *which models/packaging define each mode* (Full Desktop
/ Headless Runtime / MCP Lite) — the ladder's tier *names/weights* conform to 657 when it lands; 656
does not design packaging.

### O2 — the doctor is the ladder's compass (extend `AiPreflightService`, don't add a prober)

One discoverable thing that answers, at any moment: *what tier am I at, what's missing to reach the
next, and what is the single next remedy.* This is the Purpose's "doctor diagnostics / expected status
output / failure explanations," assembled. **Extend the existing spine, don't build a new prober:**
`AiPreflightService` (Task 4) is already the declared-vs-present reconciler; the doctor composes it
with the two live authorities — `/api/status` readiness + `/api/runtime/manifest` reason codes — into
one report. It is a **projection** of those authorities (conforms to tempdoc 501's
canonical-authority-and-projection: "how does a consumer find runtime fact F? → it is a field on the
manifest" — the doctor renders, it does not fork a second source of truth; the same principle Tasks
0-5 and Move 2 already conform to). One logic, two audience surfaces:
- **Agent:** the MCP readiness/status surface (the dev-MCP readiness check was already re-pointed this
  pass; the production `justsearch_status` tool is the natural home).
- **Human dev:** a thin CLI entry — fix + fold `scripts/verify-prerequisites.mjs` (correct its
  citation-scorer path defect; make it live-probe, not just source-tree check) or a small `doctor`
  wrapper over the endpoints.

The doctor's precision **absorbs the deferred manifest-reason polish**: the "why AI isn't ready" reason
must be *specific* on every path (the mode-transition/`switchToOnlineMode` path still surfaces a
generic `"Inference offline"` on the manifest — Tasks 0-5 wired only the activation path), so the
doctor's remedy is exact rather than "it's offline, somehow."

### O3 — a demo corpus (tiny, license-clean, bundled)

A small, permissively-licensed, fabricated/public-domain document set so the first index+query needs
**no user data**. Repurpose `scripts/jseval/util-smoke/corpus/` (already contamination-free fabricated
facts) into an onboarding-scoped corpus with a one-command ingest. **Public-repo constraint:** the
content must be license-clean (fabricated or public-domain) — the license/notices CI lane and the
public blast radius apply.

### O4 — evidence is the deliverable (a runnable proof, not prose)

The onramp ships a **runnable smoke** that indexes the demo corpus and *asserts* each reachable tier's
first-success (Tier 0 always; Tier 1/2 when their models are present). "The onramp works" becomes a
re-runnable proof, not a doc claim — directly satisfying the Boundary's explicit ask ("design the
actual runnable path and the evidence that proves it works") and mirroring the project's
verify-your-work discipline and every confidence pass in this tempdoc. This is the element that keeps
the onramp from silently rotting (the way `verify-prerequisites.mjs`'s citation-scorer check rotted
undetected).

### O5 — minimal, honest discoverability

Link the doctor + the first-query path from **CONTRIBUTING** (the front door): the investigation found
`verify-prerequisites.mjs` is wired nowhere and unreferenced from README/CONTRIBUTING, and that
README/CONTRIBUTING actively mislead ("build/run needs no model download" is true for *build*,
misleading for *run* — a dev following it literally gets a Head with no models and no named remedy).
Correct that wording and add a minimal pointer. **Not a marketing README rewrite** (Boundary), and
**tier-honest** (Tier 0 = a keyword result with zero download; a *cited answer* needs the GPU model —
do not imply a universal "five-minute cited answer"; no compliance/certification framing; the
public-claims lane checks this).

### Seams — designed as interfaces, handed to their (currently unstarted) owners

All four adjacencies are open skeletons; 656 designs the *identity-invariant, unblocked* core above and
hands the rest off rather than designing other tempdocs' work:
- **657 (install modes):** the tier taxonomy + packaging + first-run-weight framing.
- **655 (MCP conformance):** the supported-client matrix for the "attach your agent + get your first
  cited answer" guide. The *connect surface exists* (`mcp-production-server.md`), so 656 can compose a
  first-query narrative over it; the client matrix is 655's.
- **654 (runtime contract / product center):** the *stable object* the onramp introduces ("what are you
  onboarding *to*" — desktop app vs runtime vs MCP backend). The onramp's headline framing is 654's;
  the mechanics (index→query→result) are 656's and identity-invariant.
- **658 (retrieval inspectability):** the "why *this* result/citation" explainer — a clean split from
  the doctor: **doctor = is the engine *up* (environment/runtime readiness, a precondition); 658 = why
  this query behaved this way (retrieval-behavior, after the engine is up).**

**Honest coupling note:** the *full* onramp UX is coupled to 654/655/657 (all unstarted). So the
buildable-now core is the identity-invariant spine (doctor composition, demo corpus, tiered runnable
proof, the API/CLI first-query path, the discoverability fix); the identity / MCP-matrix / tier-naming
framing is genuinely blocked on those tempdocs and is handed to them, not designed here.

### Remaining non-onramp follow-on
- **ORT-CUDA (GPU embedding) sharing** — orthogonal to the onramp's first-success (Tier 0 needs no
  embedding; Tier 1 works on CPU ONNX, which is tolerable — register F-002/FW-002). It would let Tier 1
  GPU embedding share the cuda12 ORT DLLs via the existing `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`, the
  ONNX analogue of Move 2's LLM sharing. Noted; not required by the onramp.

## §Reach — principle recognition (recognizing ≠ building)

### Principle (new): *onramp-as-composition* — assembly, not capability; proven, not claimed.

*A system that accretes powerful subsystems without an assembled, discoverable, evidence-producing
"first success" path leaves newcomers unable to reach value. The remedy is composition of existing
capabilities plus a runnable proof of first-success — not new capability.* This whole remaining design
is that principle: every ingredient existed; the gap was assembly + proof + discoverability.

- **Where else it applies (candidate scope, not proposed work):**
  - **660 (plugin SDK community onramp)** — the *same shape one layer out*: the plugin substrate exists
    but no assembled external-contributor path; 660 even gates itself on runtime+MCP contracts
    stabilizing (i.e., on *this* onramp being solved first). The strongest reuse candidate.
  - **655 (MCP onramp)** and the general **contributor path** (CONTRIBUTING) — same "capabilities exist,
    assembled path doesn't."
- **Existing violation:** the current scattered state itself — four disjoint doctor surfaces that don't
  reference each other, `verify-prerequisites.mjs` unwired + carrying a stale defect, no demo corpus,
  README/CONTRIBUTING that mislead about first-run. That *is* the violation the design corrects.
- **Deliberately NOT built now:** a generalized "onramp framework." The present problem requires one
  onramp (656's), composed from existing parts. Building a reusable onramp abstraction over 655/660
  before those tempdocs are even designed would be premature. The principle is recorded so 660
  recognizes the shared shape quickly.

### Conforms to (do not re-invent)
- **501 `canonical-authority-and-projection`** — the doctor is a *projection* of the manifest + registry
  + status, not a new source of truth. Same principle Tasks 0-5 (reason codes) and Move 2 (shared
  runtime resolution) already conform to; the doctor is a third instance in this tempdoc's own lineage.
- **657 / 381 tiered modes** — "each capability tier is a complete success, not a degraded fraction"
  already partly exists as 381's GPU-full / GPU-lite / CPU download profiles; the ladder conforms to
  that rather than inventing a parallel tiering.
- **The project's verify-your-work / benchmark-evidence discipline** — O4's runnable proof is the same
  shape as the benchmark release gating its published nDCG numbers from `release.v1.json`: a claim
  ships with a re-runnable proof, not prose.

### Public-repo caution
This design's discoverability element (O5) touches README/CONTRIBUTING — public surface under the
capability-descriptor-truthfulness lane (650). Keep every onramp promise true and tier-honest, avoid
"five-minute cited answer" as a universal claim (the GPU tier has a real download), and keep the demo
corpus license-clean. No claim in this design section is itself a public-facing assertion yet — it is
design history; the public wording is authored only when O5 is implemented.

### External-research judgment for the onramp design (2026-07-01)

Considered whether the onramp design rests on actively-shifting external ground. **No research pass** —
reasoning recorded for continuity.

- The core (O1-O5: tiered progressive onboarding, a doctor diagnostic, a demo dataset, test-as-proof,
  docs discoverability) is settled UX/engineering practice — `flutter doctor`/`brew doctor`,
  time-to-first-value, sample corpora, claim-with-a-runnable-proof. Nothing fast-moving to check. The
  doctor (O2) is additionally a *projection* of internal endpoints (501), so its shape is dictated
  internally, not by an external convention.
- The one genuinely fast-moving area — the MCP spec transition + competitive agent-onramp UX + product
  positioning — maps exactly onto the seams this design **hands off** (655 MCP matrix, 657 tiers, 654
  product identity). Researching them now would be doing those (unstarted) tempdocs' work; the earlier
  Move-1/2 research pass already recorded that 655 should track the July-2026 MCP spec transition, so
  the research is *owed to 655*, not skipped here.
- The competitive premise the core leans on ("a fast, zero-download first success wins developer
  trust") is the tempdoc's given Purpose (private close-project research), already grounded against
  public examples (`shinpr/mcp-local-rag`, prior pass), and stable; the Tier-0-works claim rests on a
  live measurement, not a web claim.
- No external code/text/assets copied; O3's demo corpus is to be fabricated/public-domain content
  authored at implementation — nothing to attribute under the license/notices lane.

## §Confidence pass (eleventh pass, 2026-07-01) — onramp design (O1–O5)

Confidence-building before implementing the onramp. Live experiment (a genuinely empty-models dev
stack) + read-only tracing. **No feature code changed.**

### Resolved

**#1 [MAKE-OR-BREAK] Tier 0 = zero-model keyword search — CONFIRMED (and my earlier "proof" WAS
contaminated, as suspected).** Started a dev stack with `JUSTSEARCH_MODELS_DIR` pointed at an empty
temp dir + `--clean hard`. `GET /api/inference/encoders` returned `[]` (no ONNX encoders at all —
override confirmed). Indexing the demo corpus **succeeded with no embedding model** (worker READY,
docs indexed, IDLE). The decisive queries: `"cinnamon heist"` → **1 hit**, `"telescope"` → **1 hit**
(each the correct demo doc), both `effectiveMode: TEXT` with `sparse-retrieval: executed` and
`dense-retrieval / splade / cross-encoder: skipped`. So a genuinely zero-model checkout **indexes and
returns a real keyword result** — the onramp's headline promise (index → keyword result, zero
download) is valid. (The Move-1/2 confidence pass had returned `HYBRID` because dev-runner had pointed
`JUSTSEARCH_MODELS_DIR` at the main checkout's ONNX models; that contamination is now corrected with a
true zero-model run.)

**#2 Tier degradation is clean — CONFIRMED.** The zero-model result was `effectiveMode: TEXT` with
`degradation.hybridFallback: false` (genuinely TEXT, not a crashed/degraded hybrid) and every
model-dependent stage gracefully `skipped` — no error, no crash. This supports the design's "each tier
is a *complete* success, not a degraded fraction": Tier 0 is a clean floor.

**#3 The manifest-reason polish footprint — SCOPED (bounded, moderate).** `handleSetInferenceMode`
(`InferenceHandlers.java:329-402`) already extracts the typed `ModeTransitionException mte` and has
`mte.reason()` in hand (line 373/394). So making the manifest reason specific = inject an
`InferenceCapability` (the handler does **not** currently hold one → a small constructor-wiring add,
exactly like Task 2's injection into `RuntimeActivationService`) + reuse the **existing** Tasks-0-5
codes (`INFERENCE_RUNTIME_NOT_INSTALLED`, etc.) via the same `Reason→LifecycleReasonCode` mapping
pattern Task 2 already established. **No new `LifecycleReasonCode` → no `readiness-reason-codes` gate
change → no `readinessNotice.ts` row needed.** One ordering caveat: the rollback's mode-change listener
fires a generic `OFFLINE→"Inference offline"` first, so the handler's post-catch `transition(OFFLINE,
specificCode)` must be the last write (it is — it runs after `switchToOnlineMode` returns), or do it at
the rollback source for robustness. ~1–2 Java files + one construction site.

**#4 Doctor composition — CONFIRMED feasible (thin read, no new plumbing).** The three surfaces the
doctor composes all exist and expose the needed data: `/api/ai/models/status` (correctly reported
**all packages MISSING** under empty models — the "what tier / what's missing" answer),
`/api/status` (a rich readiness surface: `lifecycle`, `components`, `readiness`, `aiReady`,
`embeddingReady`, `modelDistribution`, `gpu`), and the runtime manifest. The **filesystem manifest**
(`.dev-data/runtime/manifest.json`, 2 KB, current — `lifecycle`/`ai.phase`/`worker.state`) exists and
is fresh, so a **CLI doctor can read runtime state without a live API**. `AiPreflightService` is the
natural spine to extend; the composition is a read, not new machinery.

**#5 Runnable proof + demo corpus — FEASIBLE.** The full `index → query → result` path works
end-to-end (just exercised via `POST /api/knowledge/{ingest,search}`); the `util-smoke/corpus` docs
(2, fabricated → license-clean) each return a correct single hit. A tier-conditional first-success
smoke composes from existing tooling (jseval `ingest_and_wait`, the search call, dev-MCP `dev_ingest`)
plus the doctor's tier detection for the conditional assertions. 2 docs suffice for a smoke; a few more
would make a richer demo.

### Confidence rating: **7 / 10**

The make-or-break (Tier 0 zero-model search) is confirmed with a clean live run; the doctor is a thin
read of three confirmed surfaces; the one Java change is bounded and reuses existing infrastructure;
the end-to-end first-query path works. Held below 8 not by any unresolved *risk* but by *breadth*: the
onramp is ~5 loosely-coupled pieces (doctor API composition + doctor CLI + demo corpus + runnable smoke
+ docs + the Java polish) across three languages, so there is more assembly + validation surface than a
single-file change, plus two genuine *design-judgment* calls not yet nailed — the doctor's exact
surface (CLI / MCP / both) and its tier-derivation semantics (how preflight+status map to a tier
label), and O5's **tier-honest public wording** (the public-claims lane checks README/CONTRIBUTING;
"zero-download keyword result" is true, "five-minute cited answer" is not universal).

### Difficulty + recommended model/effort

**Difficulty: MODERATE, and broader than Move 1/2.** Not hard — the technical unknowns are resolved and
most of it is composition of confirmed-working parts — but it spans Java (the bounded manifest-reason
polish + possibly the doctor endpoint), Node/CLI (the doctor CLI + runnable smoke), content (the demo
corpus), and public docs, with real (if small) design judgment and public-honesty stakes.

**Recommended: Sonnet at high effort for the bulk** (doctor composition, demo corpus, runnable smoke,
the manifest-reason polish, the CLI) — all now well-scoped, mechanical-to-moderate, and squarely
Sonnet's strength. **Reserve extra care (a focused review pass, or Opus for just these slices) for two
things:** (a) O5's public-facing wording, where the over-claiming risk + the public-claims CI lane are
real, and (b) the doctor's surface + tier-derivation semantics, a small but genuine UX/design judgment.
**Not Opus for the whole pass** — the hard unknowns are de-risked and most work is well-specified
composition. **Not Fable / low-effort** — the breadth, the cross-language assembly, the browser
validation of the user-visible first-query, and the public-wording honesty all reward care.

## §Implementation (twelfth pass, 2026-07-01) — the onramp (O1–O5 + manifest-reason polish)

Implemented the onramp as designed. All new structure is dev/onboarding-scoped; the only production
change is the bounded manifest-reason polish. Live + browser verified.

### What shipped
- **The doctor — `scripts/dev/doctor.mjs` (new, O1+O2).** One command → current tier / what's missing
  / the single next remedy. A **projection** (501), not a new authority: offline it reads
  `model-registry.v2.json` + checks disk + the shared cuda12 runtime; live it reads `/api/status` +
  `/api/runtime/manifest`. Tier derivation keys on the **primary artifact** (variant file), avoiding
  the mmproj trap (same lesson as the `canActivateDefault` fix). Stack detection goes
  `active.json → runId → runs/<id>/run.json` (the port lives in run.json, not active.json — caught in
  live verification).
- **Manifest-reason polish (Java, O2 completeness).** `InferenceHandlers` takes a nullable
  `InferenceCapability`; on an online-mode transition failure it projects the specific cause onto the
  manifest via `mapModeReason(mte) → LifecycleReasonCode`, reusing the Tasks-0-5 codes (no new code →
  no gate change). Wired at `CoreApiAssembly:137` via the existing `resolveInferenceCapability(...)`.
  Guarded: only for `online` mode, only when not already READY (won't regress a working runtime).
- **`verify-prerequisites.mjs` citation-scorer path fixed (O2 hygiene)** — `models/onnx/citation-scorer`
  (was the stale `models/citation-scorer/ms-marco-MiniLM-L2-v2`), resolving the standing false-positive.
- **Demo corpus — `examples/onramp-corpus/` (new, O3).** Four fabricated (license-clean) markdown docs
  + a README. Ingested via the existing endpoint.
- **Runnable proof — `scripts/dev/test-onramp-first-success.mjs` (new, O4).** Starts the stack, ingests
  the demo corpus, queries, asserts a first result, tears down.
- **Discoverability (O5).** `CONTRIBUTING.md`: corrected the misleading "models download on first run"
  wording and added a tier-honest "First run from source (the onramp)" section pointing at the doctor +
  the demo-corpus first-query. Tier-honest (Tier 0 = zero-download keyword; cited answers need the GPU
  model) — no over-claim, no compliance framing.

### Verification (all green)
- **Static/tests:** `./gradlew.bat build -x test` SUCCESSFUL; `:modules:ui:test` pass;
  `check-readiness-reason-codes` green (no new code); all three new/edited scripts `node --check` clean.
- **Onramp smoke (O4, the primary proof):** PASS — ingested the demo corpus → query "cinnamon heist" →
  5 results in HYBRID mode (tier 2), clean teardown.
- **Doctor across tiers (live):** empty models → **Tier 0** + "reach Tier 1" remedy; full → **Tier 2**,
  all reachable; live detection reports worker=READY, indexed, aiReady, and the specific ai reason.
- **Manifest-reason polish (live):** forced an online-mode failure with an unresolvable runtime →
  `/api/runtime/manifest` `ai.pendingReason` became the specific **`inference.runtime_not_installed`**
  (was generic "Inference offline"). Confirmed.
- **Browser (REQUIRED — user-visible first-query):** with the demo corpus indexed and AI online
  (Online — Qwen Qwen3.5-9B), the **Search tab** for "Brasswick Lighthouse keeper" rendered the demo
  docs (lighthouse.md top with highlighted matches + snippet "…by the keeper Odette Prynne", plus
  cinnamon/clockwork/telescope) with "Why this result?" affordances; **Document Q&A** rendered a
  grounded, cited answer (2 sources, citation `[1]`). The onramp's first-success renders in the real UI.

### Noted (out of scope, logged to the inbox)
On a tiny index, RAG top-k for a Document Q&A content query can surface the corpus README + leftover
`.dev-data` docs over the fabricated-fact docs (raw Search is clean). Consider excluding the README
from the ingested set or seeding a clean index for the demo — logged, not fixed here.

### Still handed off (unchanged)
Tier taxonomy/packaging → 657; MCP client matrix + attach guide → 655; product-identity framing → 654;
retrieval "why this result" → 658. ORT-CUDA GPU-embedding sharing remains a noted follow-on.

### Post-implementation review fixes (2026-07-01)
A critical review of the committed onramp caught two substantive issues (the rest verified correct —
the Java manifest-reason polish mirrors `RuntimeActivationService.transition(OFFLINE, reason.code())`
exactly, `pendingReason`-as-code is the system convention, and the citation-scorer path matches the
registry):
1. **Doctor tier derivation was not cumulative** — `if (chat && runtime) tier = 2` ran independent of
   embedding, so {embedding absent, chat + GPU runtime present} reported "Tier 2" while listing
   embedding as missing and setting `nextRemedy = null` (self-contradictory). Fixed: Tier 2 now
   requires embedding ∧ chat ∧ runtime (cumulative); `nextRemedy` names the first unmet rung
   (embedding → runtime → chat). Verified with a bug-repro state (chat GGUF only, cuda12 resolvable →
   now correctly Tier 0 pointing at embedding).
2. **Smoke omitted the plan's conditional higher-tier assertion** — added a deterministic Tier-1 check
   (when tier ≥ 1, the query must not fall back to pure `TEXT` mode); Tier-2's cited answer is
   intentionally not asserted (LLM-flaky). Smoke re-run PASS (tier 2, HYBRID).

## §Post-implementation research & ideas (thirteenth pass, 2026-07-01)

Docs-only exploration ("what could we do with this?") — no code. Grounded the ideas against external
onboarding/diagnostic practice (sources at the end). Nothing here is committed work; it is a recorded
opportunity backlog. The app is public-alpha with **no real users yet**, so the urgency ordering below
weighs *current* audiences (contributors, evaluating agents) over the not-yet-present desktop user.

### The meta-finding: I built the plumbing; activation value is unlocked only where the audience is

Everything shipped (doctor, demo corpus, smoke, CONTRIBUTING section) is **CLI + docs + dev-runner** —
it serves only the *from-source developer*, and even for them it is gated behind "know the script path
exists." The two audiences with the highest activation leverage are untouched:
- the **desktop user's first-run/empty state** — external UX research calls the blank "no data yet"
  screen *"one of the most common silent drop-off points in early activation"*; JustSearch's is
  currently un-onboarded;
- the **agent (MCP)** — the very "wedge" this tempdoc is named for; agents don't run CLIs.

TTFV research quantifies the prize: a **sample-data sandbox** (exactly what the demo corpus is) is a
top time-to-first-value lever (~40-60% reduction) *when it's one click*, and dev tools where the first
success lands in <5-10 min convert 3-4× better. My demo corpus has the right shape but the wrong
surface (reachable only via CONTRIBUTING + a manual `curl`). **The theme of every idea below: surface
the plumbing where the audience already is.**

### Practicality verdict on the shipped pieces (honest)
- **doctor** — genuinely useful for "why isn't AI working," but `node scripts/dev/doctor.mjs` is not a
  discoverable command (a tool nobody finds has ~zero activation value).
- **demo corpus** — a textbook sample-data sandbox, but not one-click.
- **smoke** — hands-on evidence (87% of devs prefer hands-on over docs), but not wired into CI/npm.
- **manifest-reason polish** — real value, invisible until a failure, surfaced only via the doctor/manifest.
- **net**: correct and complete for its settled scope; narrow in practical reach until surfaced.

### Idea backlog (grouped; each with a practicality/urgency read)

**A — Polish / simplify (cheap, serves the current from-source dev):**
1. Fix the dangling `docs/how-to/onramp.md` reference in `examples/onramp-corpus/README.md` (create the
   page or drop the link). Trivial; it's a broken pointer in shipped public content.
2. Doctor UX borrowed from `flutter doctor`: a "rerun until all ✓ / clean bill of health" framing, a
   `-v` verbose mode, and fix the cosmetic `present['cuda-runtime']` display (wrong `installRoot` base).
3. **Make the doctor discoverable** — an `npm run doctor` script and/or a `dev-runner doctor` subcommand
   (not a bare path). Highest cheap-win: discoverability *is* activation (TTFV).
4. Reconcile the two overlapping "is my env OK" tools — `verify-prerequisites.mjs` (build-check) vs the
   doctor (onramp-check): cross-link or fold, so contributors aren't confused by two answers.

**B — Extend (medium; higher leverage):**
5. Doctor `--fix` / guided remediation: offer to run the next-step command (confirm-gated — staging
   cuda12 is a ~600 MB download). Turns diagnosis into a one-keystroke fix.
6. Wire the **smoke into CI** as a fact-lane (ADR-0044): the runnable proof becomes a *continuous*
   guarantee that the onramp still works, not a manual check.
7. **Agent auto-discovery** — expose a `/.well-known/mcp/server-card.json` (SEP-1649: `serverInfo` /
   `transport` / `capabilities`) on the loopback server so Claude/Cursor auto-detect JustSearch's MCP
   instead of the manual `mcpServers` config block. A 2026 discovery standard; concrete and low-cost,
   but **coupled to 655** (the MCP client matrix) and to the dynamic-port problem — record, coordinate.
8. **Agent-facing doctor** — fold the tier/remedy answer into the existing `justsearch_status` MCP tool
   (or a new one) so an attached agent can self-diagnose "why can't I get cited answers." Reuses the
   doctor logic; the agent-side of O2.

**C — New UX (higher effort; highest *user* leverage, lowest *current* urgency — no users yet):**
9. **In-UI first-run / empty-state onboarding** (the flagship): replace the blank "no documents"
   screen with a guided step — `[Load the demo corpus] · [Add a folder]` → first search → a result —
   plus a tiered status line ("Search ready; add AI models for cited answers → [Install AI]"). This is
   the LM-Studio guided-first-run + empty-state-as-onboarding + sample-data-sandbox patterns composed,
   surfaced in the GUI. It reuses the demo corpus + the doctor's tier logic + Install-AI. **Coupled to
   654** (product identity — "what are you onboarding *to*"), so it's a recognize-and-record item, not
   build-now.
10. **In-UI readiness/tier panel** — extend the existing "Open Health" banner into "you're at Tier N,
    here's the one next step," reusing the now-specific manifest reason codes. The desktop-user analogue
    of the CLI doctor.
11. **One-click "Load demo corpus"** in the UI — the single highest-ROI atom of #9 (sample-data sandbox
    = the top TTFV lever), extractable on its own.

### Reach / discipline note
Several of the best ideas (#7, #8, #9, #10) are **coupled to the unstarted 654/655** — consistent with
this tempdoc's own reach discipline (recognize the shared shape, record the candidate, don't build the
cross-tempdoc structure now). The *onramp-as-composition* principle (§Reach) predicts these: each is
"the plumbing exists; compose+surface it for a new audience." The cheap, un-coupled, current-audience
wins are **A1-A4 + B6** (docs/dev-tool polish + CI proof-lane); the rest wait on their owners.

### Sources (external practice, 2026)
- Doctor-command design (actionable output, rerun-until-✓, verbose): Flutter `flutter doctor` guides.
- User first-run (guided, hardware-sized, drop-into-chat): LM Studio vs Ollama onboarding write-ups.
- TTFV levers (sample-data sandbox ~40-60%, guided first task ~30-50%, <5-min first value → 3-4×
  conversion, 87% prefer hands-on): SaaS/dev-tool time-to-value guides (rework.com, getmonetizely,
  daily.dev).
- Empty-state-as-onboarding ("silent drop-off point"; one clear action; Notion/Todoist checklists):
  useronboard.com, eleken.co, setproduct.com.
- MCP auto-discovery (`.well-known/mcp/server-card.json`, SEP-1649 fields): ekamoira.com 2026 guide;
  MCP 2026 roadmap (getknit.dev, tedt.org).

## §Long-term design theorization (fourteenth pass, 2026-07-01) — activation readiness as one capability projection

Design-theory (general, not implementation-level). Turns the thirteenth-pass idea backlog into a
single coherent architecture. **No code; records the target shape + a promotion trigger.**

### The backlog is one architecture, not eleven features

The eleven ideas collapse to one structural question. The onramp's core derived fact — *"which
capability tier is this environment at, what's missing, and the single next remedy"* — lives today
**only** as hardcoded logic in the Node CLI (`doctor.mjs::deriveTier`). The research showed the *same*
fact must reach the desktop UI (empty-state/first-run) and the agent (MCP). Three surfaces each
re-deriving the tier is a **3-way fork of a derived fact** — the representation-drift class this
codebase guards with registers + gates. So the only real design question is: where does the ONE
canonical tier answer live, and how do audiences *render* (not re-derive) it?

### The design: conform to the capability-projection substrate that already exists (don't invent a tier authority)

The tier is not a new concept — it is an instance of patterns already running in the system. Conform:

- **It is a capability projection (587).** *"A capability answer is a PROJECTION of its probes, never
  a second authority"* — probe → effective view → policy, with consumers reading the merged view and
  **foreclosed** from re-deriving it (587's raw-probe foreclosure gate). 587 ships the GPU **host**-capability
  as the reference instance; the onramp tier is an **asset**-capability instance (model/runtime presence
  → tier) of the *same substrate*. The tier = the "effective view"; the next-remedy = the
  "requirements/policy projection" (the analogue of `VramRequirements` over the effective value).
- **Its inputs already exist as two probe-halves.** Static/declared-vs-present → `AiPreflightService`
  (registry vs disk + runtime-exe; zero new deps, but **no live readiness by design**). Live → the
  capability layer (`InferenceCapability`/`WorkerCapability`) + the manifest reason codes +
  `/api/status`'s derived `aiReady`/`embeddingReady`. The CLI merges the two halves client-side today;
  a canonical projection merges them once.
- **The render pattern is already CI-enforced.** One Java producer emits a CODE; the FE/MCP render
  code→wording *purely*; a `governance/*.v1.json` register + a `scripts/ci/check-*-reason-codes.mjs`
  gate enforce producer↔renderer bijection. The onramp's per-cause remedies **already live here**
  (`readinessNotice.ts` CAUSE_ROWS + `LifecycleReasonCode`, added under this tempdoc). A tier
  projection conforms — a small tier fact rendered as pure code→wording, register + gate enforced.
- **The FE already has a remedy-carrying capability projection to EXTEND.** `state/availability.ts::
  projectAvailability` is literally *"two tiers, one authority"*: it projects
  `available`/`blocked`/`unavailable{reason,remedy}`/`degraded{caveat}` from ONE authority
  (`aiStateStore`) and already models `no_documents`. The FE tier renderer is an *extension of this* +
  the existing `EmptyStateRegistry` (`search-no-results`/`library-empty`) + `WalkthroughRegistry` — not
  new scaffolding, a new projection field on scaffolding that exists.
- **The ladder becomes DECLARED data.** The tier→package mapping (embedding→Tier 1; chat+cuda→Tier 2)
  is hardcoded in the CLI and declared nowhere (the registry has no `tier`/`enables`/`role` field, no
  schema). Correct home: an `enables`/`role` field on registry packages **or** a
  `capability-tiers.v1.json` SSOT catalog conforming to `fields.v1.json` (`$schema` + versioned +
  role-array shape + dual-copy sync + a `check-capability-tiers` gate enforcing tier↔package-id
  bijection). Data-driven ⇒ the JVM projection, the FE, and the offline CLI read ONE declaration ⇒ no
  per-runtime logic to drift.

### Scope judgment — record the shape + the promotion trigger; build nothing structural now

- Exactly ONE consumer of the tier exists today (the CLI), which correctly derives+renders inline. The
  second renderer (FE first-run per 654; agent doctor per 655) is unstarted; 587's own generalized
  capability catalog is likewise still design-theory (only the GPU instance shipped).
- Therefore, per the codebase's consistent *"a second consumer justifies the projection"* discipline +
  the YAGNI rule: **building the canonical Head projection + the declared-tier catalog now is
  premature.** One renderer does not justify promoting logic out of the CLI, and declaring a ladder as
  data for a single reader is abstraction-for-nobody.
- **Warranted now (non-structural, un-coupled, current-audience):** make the CLI renderer discoverable
  (an `npm run doctor` / `dev-runner doctor` subcommand); wire the smoke into CI as a continuous
  proof-lane; fix the dangling `docs/how-to/onramp.md` reference. None touch the tier authority.
- **The promotion trigger (recorded, precise).** At the FIRST second-renderer, in order: (1) declare
  the ladder as data (registry field or `capability-tiers` catalog + schema + gate); (2) render it by
  **extending `availability.ts`** (FE) or adding a `tier`/`nextRemedy` field to `justsearch_status`
  (MCP), conforming to the code→wording + register + gate pattern; (3) repoint the CLI's *live* path at
  the canonical projection, keeping ONLY its offline registry-read fallback (the one legitimate second
  implementation — a dev needs "you're at Tier 0, start here" before any JVM is up; the declared ladder
  keeps it drift-free). **Do not let the second surface re-implement `deriveTier`.**

### Public-repo caution
Tier names become public surface once rendered (UI/README) — keep them tier-honest (Tier 0 keyword =
zero-download; Tier 2 cited answers needs the GPU model); no "five-minute cited answer" universal
claim, no compliance/certification framing (the capability-descriptor lane, 650). This section is
design history, not a public claim.

## §Reach extension (fourteenth pass) — the tier is an instance, not an invention

### Conforms to (do not re-invent)
- **587 host-capability-sensing substrate** — the onramp tier is another instance of *"capability =
  projection of probes, never a second authority; consumers read the merged view (foreclosure)."*
  587's GPU is the host-axis reference; the onramp tier is the asset-axis instance. When 587's
  generalized capability catalog lands, the tier is a candidate axis for it — record it there.
- **The code→wording + register + gate pattern** (`check-readiness-reason-codes`) — the tier/remedy
  render conforms to that enforced bijection, not a parallel scheme.
- **`availability.ts` "two tiers, one authority"** — the FE tier renderer extends this existing
  remedy-carrying projection. (Already recorded: 501 canonical-authority-and-projection; 657/381
  tiered modes.)

### New sub-principle: *readiness is a projection, rendered per-audience — never re-derived per surface*
*A user-facing DERIVED readiness fact (capability tier, degradation cause, availability, "why this
result") must be ONE canonical projection that each audience surface RENDERS as pure code→wording; it
must never be re-derived per surface.* The projection principle (501/553/564) specialized to the
cross-audience activation/readiness domain.
- **Already conforms (the evidence it's right):** the degradation reason codes (one producer →
  CAUSE_ROWS → gate); `availability.ts` (one authority → per-affordance projection); 587's GPU
  Effective view (one merge → foreclosed consumers).
- **Candidate scope (record, don't build):** 658 retrieval "why this result" (one trace projection,
  many renderers); 587's remaining host axes; the onramp tier (this doc — the one currently
  NOT-yet-canonical instance, acceptable while single-consumer).
- **The near-violation this design forecloses:** the doctor already had to compose FOUR disjoint status
  surfaces (preflight, `/api/status`, manifest, `verify-prerequisites`) because "is the environment
  ready" was never projected once. The tier projection is the merge that stops a fifth renderer forking
  a fifth answer.
- **Recognize, don't build:** the principle + the promotion trigger are recorded; the general
  capability catalog / Head projection are not built until the second renderer arrives (587's own
  phasing; the YAGNI rule).

## §Confidence pass (fifteenth, 2026-07-01) — the remaining *implementable* work

Confidence-building for the three warranted-now items (discoverable doctor / smoke-in-CI / dangling-ref
fix). The big tier projection stays deferred by design. Read-only investigation + one live experiment.
**No feature code changed.**

### Resolved

**#1 [was biggest] Smoke-in-CI is viable at Tier 0 — CONFIRMED, with a clear home.** Ran
`test-onramp-first-success.mjs` with an **empty** `JUSTSEARCH_MODELS_DIR` (simulating a public CI
runner: no models, no GPU). The stack started, indexed the demo corpus **with no embedding model**, and
the query returned **1 result in TEXT mode (tier 0)** — the Tier-0 assertion passed and the conditional
Tier-1 assertion correctly *skipped*. Wall-clock **18 s** (the earlier ~60 s was a one-off `installDist`
rebuild, not the steady state). So the smoke does **not** need models/GPU and is fast enough for CI.
Characterization of the home: `ci.yml` is fast **script-only fact-lanes** (no stack) — the smoke does
not belong there; the only stack-starting job (`phase-3-observability-nightly.yml`) is a **self-hosted
perf runner** and adding the smoke there would be Tier-2 + **redundant with jseval's** `--start-backend`
run. **Honest recommendation: a dedicated public-hosted Tier-0 smoke lane** (or a `workflow_dispatch` +
scheduled lane) — it proves the exact promise the onramp makes (zero-download first success) on the same
public runners a user would use. Two known costs, neither a blocker: (a) the lane pays a **full
dev-stack bootstrap** (Java `assemble` + `npm ci` + a Vite frontend spawn the smoke doesn't actually use
— `dev-runner.cjs:1233` always spawns it; a future `--no-frontend` flag would trim it); (b) a new
workflow needs an entry in `workflow-signal-policy.v1.json` or `check-workflow-triggers.mjs` fails.

**#2 [low] Doctor discoverability home — CONFIRMED.** A root `package.json` with a `scripts` block
exists (home for `npm run doctor`); dev-runner's dispatch (`cmd === 'start'|'status'|'stop'|'cleanup'`)
extends cleanly to a `doctor` subcommand; and the CJS→ESM invocation is already a proven pattern (the
smoke spawns `doctor.mjs` via `execFileSync`). No surprises.

**#3 [trivial] Dangling-ref — DECIDED.** Drop the `docs/how-to/onramp.md` link from
`examples/onramp-corpus/README.md`. A standalone how-to is O5/UI work (rides 654); the only defect is
the broken link in shipped public content.

**#4 [separate] Deferred design soundness — HIGH.** The tier-as-capability-projection design conforms
to shipped patterns (587's probe→effective→policy, the CI-enforced code→wording register/gate,
`availability.ts`'s "two tiers, one authority"), and the renderer homes (`justsearch_status`,
`EmptyStateRegistry`, `WalkthroughRegistry`) were confirmed to exist. The only residual is that 654/655
might frame the tier differently — which the *promotion trigger* (declare-then-render at the second
consumer) absorbs by construction.

### Confidence rating (remaining *implementable* work): **8 / 10**
The biggest unknown (does the smoke survive a model-less CI-like env) resolved positively and fast; the
discoverability home and the dangling-ref are trivial and confirmed. Held below 9 only by minor
open *judgment* (not risk): which lane hosts the smoke and whether to add a `--no-frontend` flag to
avoid the wasted Vite spawn. Nothing here is a genuine surprise-in-waiting.
Deferred-design soundness (not built now): **~8/10** — sound, conforms, renderer homes exist.

### Difficulty + recommended model/effort
**Difficulty: LOW — the easiest remaining work in this tempdoc.** An `npm run doctor` script + a
`dev-runner doctor` subcommand (a few lines, proven pattern), a CI workflow yaml + a
`workflow-signal-policy` entry (small, mechanical, light care on triggers), and a one-line doc edit. No
Java, no new logic, no browser validation (the smoke *is* the validation).

**Recommended: Sonnet at low-medium effort** for the whole batch — well-scoped, mechanical, low-judgment.
**Fable is acceptable** for the purely mechanical subset (the npm script, the dangling-ref edit, the
dev-runner subcommand). **Not Opus** — the deep reasoning was the design pass, which is done and
deliberately deferred; nothing remaining needs it. The one spot rewarding a little care is the CI lane
(trigger policy + lane choice), comfortably within Sonnet.

## Launch-context note (2026-07-02, founder direction — reconciliation of a parallel re-scope)

While this tempdoc's implementation was merging (PR #44), a founder-directed re-scope was filed
against the pre-merge stub in the main checkout, elevating "the five-minute first-success path" to
**launch-blocker** status: the public launch sequence will not fire until a first success exists
that does not require the full desktop install + ~9 GB first run. Reconciled against the merged
state:

- The **developer/from-source half is now shipped by this tempdoc** (Tier-0 zero-model search, the
  doctor, `examples/onramp-corpus`, the runnable proof) — that half of the launch-blocker is
  satisfied.
- The **end-user / agent-developer trial half** (install modes, model-pack decomposition, a light
  MCP-first shape) is exactly the framing this tempdoc already handed to **657/654/655**; the
  launch-blocker elevation therefore lives on **657** (see its 2026-07-01 re-scope note), not here.
- The demo-grade corpus question (messy, multilingual, OCR — beyond the four-file Tier-0 smoke
  corpus) is carried by **669**.

## §Implementation (sixteenth pass, 2026-07-01) — the three warranted-now wins

Implemented the remaining non-structural, un-coupled work (the big tier projection stays deferred by
design). No new logic — entry points + a CI lane + a doc fix over the already-shipped doctor/smoke.

### What shipped
- **Discoverable doctor (D1).** `package.json` now has `"doctor": "node scripts/dev/doctor.mjs"`
  (`npm run doctor`), and `dev-runner.cjs` gained a `doctor` subcommand (`cmdDoctor` — a thin
  `spawnSync` passthrough to `doctor.mjs`, inheriting stdio + propagating the exit code, so `--json`
  and the informational/exit-2 contract pass through; the tier logic keeps exactly one home). Usage
  line added.
- **Onramp Tier-0 CI proof-lane (D2).** New `.github/workflows/onramp-smoke.yml` —
  `workflow_dispatch`, `windows-latest`, advisory/non-blocking — checks out, sets up Java/Node,
  installs `modules/ui-web` deps (the stack spawns the Vite frontend; readiness is backend-only but the
  deps keep the spawn clean), forces the model-less **Tier 0** path via an empty
  `JUSTSEARCH_MODELS_DIR`, and runs `test-onramp-first-success.mjs` (plus a `doctor` diagnostic step).
  Declared in `workflow-signal-policy.v1.json` as class `scheduled-quality-signal` (mirrors the Phase-3
  nightly). Conforms to CLAUDE.md's "specialty workflows remain manual; local-first primary."
- **Dangling-ref fix (D3).** `examples/onramp-corpus/README.md` no longer points at the nonexistent
  `docs/how-to/onramp.md` (the only site).

### Verification
- D1: `npm run doctor` and `dev-runner doctor` both print the tiered report; `dev-runner doctor --json`
  emits the JSON object and exit 0; `node --check` clean.
- D2 (locally checkable): `check-workflow-triggers` **passes** (the new entry ↔ the `workflow_dispatch`
  block correspond); policy JSON + workflow YAML well-formed; the Tier-0 smoke re-run **green**
  end-to-end with empty models (1 result, TEXT mode, tier 0) — the exact path the CI lane runs.
- D3: grep confirms zero remaining `how-to/onramp.md` references in shipped content.
- Sanity: `./gradlew.bat build -x test` SUCCESSFUL (no Java touched); no stray llama-server; no stack.

### Honest validation boundary (not a gap)
A GitHub Actions workflow's *live* green only runs on a push + dispatch — an outward action not taken
autonomously. Everything locally checkable is green; the first live Actions run is a **user dispatch
after merge** (`gh workflow run "Onramp Smoke"`), inherent to CI work.

### Deliberately not built (recorded elsewhere)
The deferred tier projection / declared ladder / UI-MCP renderers (wait for the 2nd consumer); a
`dev-runner --no-frontend` flag (future trim of the CI frontend spawn); an `ubuntu-latest` lane
(dev-runner has Linux paths but the stack is only *proven* on Windows).

