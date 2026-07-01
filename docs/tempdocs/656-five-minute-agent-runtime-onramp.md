---
title: "Five-minute agent/runtime onramp: make the first successful developer path deterministic before adding more retrieval ambition"
type: tempdocs
status: "open — takeover investigation pass 2026-07-01 (see §Investigation). Triggered by a live incident: 2 agents independently reported missing models / missing llama-server after the go-public cutover. No design/implementation started yet; this pass is root-cause investigation only."
created: 2026-06-28
updated: 2026-07-01
category: developer-experience / activation / mcp / diagnostics
related:
  - 654-local-runtime-contract-and-product-center
  - 655-mcp-conformance-and-capability-policy
  - 657-install-modes-and-model-pack-decomposition
  - 658-retrieval-inspectability-and-diagnostic-bundle
  - 634-go-public-cutover-transition
  - docs/reference/contributing/agent-guide.md
  - docs/reference/mcp-production-server.md
  - docs/reference/model-inventory.md
  - scripts/verify-prerequisites.mjs
  - scripts/dev/dev-runner.cjs
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

