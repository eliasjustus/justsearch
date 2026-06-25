---
title: The relevance freeze — retrieval-engine quality-lever triage (live vs dead)
type: tempdocs
status: active
created: 2026-06-13
updated: 2026-06-15
---

# 580 — The relevance freeze: retrieval-engine quality-lever triage (live vs dead)

> *Title updated 2026-06-15: the original "…and the FW-001 thaw" named the opening plan; FW-001 was
> superseded (§10) and the doc's durable payload is the live-vs-dead lever map, not that one item. The
> filename slug (`…-and-fw001-thaw.md`) is kept stable — 581 and the search-quality register link to it.*

> **Nature & purpose of this doc (what 580 is FOR — updated 2026-06-15).** 580 is the
> **retrieval-engine-relevance triage hub**. It started (2026-06-13) as a narrow
> *organizational finding* — "what work was done unusually little?" → the verdict that the
> **engine was frozen ~2 months while ~46k lines went to presentation + governance** (§1),
> *neglect not maturity* (§2) — but it has become the single authoritative answer to the
> question that finding raised: **given the engine is frozen, which retrieval-quality levers
> are LIVE, which are DEAD, and with what evidence** — so neither the user nor a future agent
> re-walks the dead ends. It is **NOT a build contract**: nothing here is committed-to-ship;
> it is a *decision map*. The acid test for whether a thread belongs in 580: *does it help
> decide what (if anything) to do about the frozen engine?*
>
> **How to read it.** The *finding* (§0–§4: frozen but healthy — no silent regression). The
> *map of dead ends, so they aren't re-walked* (FW-001 binary switch → superseded §10; GPL→
> LambdaMART learned fusion → discredited §12, structurally *and* by data §13.7, register
> **F-021**). The *one live internal lever* (§13.3 + design §13.8: result-set-adaptive fusion
> weighting — label-free, designed, **unbuilt**). The *one live external lever* (F-009
> extraction — §14.4, now with a verified drop-in candidate). The *post-cutoff landscape*
> (§14, Feb–Jun 2026). The *whole-engine work map* (§15: which general areas were worked vs the
> untouched ranking surface) and the **area-12 deep-dive** (§16 feedback signal already on disk;
> §17 the *correct long-term design* — feedback as the outcome tier of the projection spine).
> **Durable artifacts:** Q-010 (relevance ratchet), register F-021, the *parked* bootstrap fix
> (§13.7), the §13.3/§13.8 fusion design, the §15 area-investment map, and the **§17 outcome-tier
> design** (the correct end-state for the learn-from-use loop). **The open decision is §13.6 (A)–(D).**
>
> **State of work (2026-06-15).** The *theoretical* work this doc exists to do — diagnose the
> freeze, triage **every** lever to live/dead (§13–14), map the work across all engine areas (§15),
> and theorize the correct long-term structure for the highest-leverage gap (§17, area 12) — is
> **complete.** Everything that remains is **execution**, all of it the §13.6 direction call (run the
> §13.3 experiment · pilot F-009/PaddleOCR-VL-1.6 · build the §17 outcome tier · wire Q-010) —
> dev-stack-bound or new-feature work in its **own** implementation tempdoc, not analysis. **One
> analytical thread is deliberately left un-deepened:** §11 (global-comparison capability) is sketched
> at the strategy tier only — a *different* question, the fork candidate. The §17 outcome-tier design
> is the correct *end-state*; its build seeds area 12's own tempdoc.
>
> **Execution status (2026-06-15 — the §13.6 direction call was taken: build all live levers).**
> All four levers are now **implemented** on `worktree-580-engine-levers` (the structural §17 design,
> not a shim). Static proof for each: `./gradlew.bat build -x test` compiles; affected module + FE
> unit suites green; `--preflight main` shows no NEW gate findings (class-size + clone covered by
> declared-growth changesets; ts-any/operation-surface/exception-count findings are pre-existing on
> `main`, not introduced here).
> - **Track A — §13.3 adaptive fusion weighting:** `AdaptiveWeightSelector` (per-query CC weight pick
>   from result-set length signal), flag-gated `index.hybrid.adaptive_weights_enabled` (default OFF =
>   today's behavior), wired at the one `SearchExecutor` fusion site. 7 unit tests.
> - **Track B — Q-010 relevance ratchet:** `jseval relevance-gate` (nDCG@10-mean regression floor) +
>   per-corpus baselines + the `search-engine-hint` PostToolUse hook. 7 unit tests.
> - **Track C — §17 outcome tier (the feedback loop):** the canonical `ResultDisposition` stream + the
>   `FeatureSnapshot` capture (keyed by `interactionId`, the fix for the de-risking surprise that
>   `SearchTrace` is ephemeral), one generic `NdjsonAppendStore`, both declared as a 575
>   observed-happening `feedback-outcome` concept + Category.HISTORY Resource catalogs; the FE
>   disposition emit (opened/refined-without-opening carrying `interactionId`) from the LIVE search
>   surface; the agent-citation contributor (CITED/SHOWN harvest from `AgentDone`); the
>   disposition⋈snapshot label projection feeding a 3-leg (BM25/dense/SPLADE) `LambdaMartFeatureSchema`
>   with GPL demoted to a cold-start prior.
> - **Track D — F-009 extraction:** the atomic `VlmExtractionProfile` (PaddleOCR-VL-1.6 as a first-class
>   (model+mmproj) profile, default = today's Qwen-VL pair) + the **retrieval-aware** `jseval
>   extraction-gate` that gates a profile swap on downstream nDCG, NOT the OCR leaderboard number (the
>   §14.4 / InduOCRBench lesson, with an explicit OCR-vs-nDCG-disagreement flag). 6 + 8 unit tests.
>
> **Live verification (one final dev-stack batch; what passed vs what the dev env blocked).** The
> **core §17 loop is live-verified end-to-end** against a worktree dev stack: a real browser search →
> open a result → the disposition persists to `feedback/result-dispositions.ndjson` and **joins its
> `FeatureSnapshot` by `interactionId`** (the §17.4 join — the opened doc links to its ranking
> features: rank 1, sparse 5.63, fused 1.0). This caught + fixed a real wiring gap (the FE emit was
> only in `SearchSurface`, not the live `UnifiedChatView` retrieve surface). **Two LLM/dense-dependent
> proofs were blocked by the dev environment, not by code:** the agent-citation *live* harvest needs
> the chat LLM (the dev `.dev-data` has no imported models pack — "No chat model configured", true of
> both the worktree and main dev stacks) and a meaningful §13.3 *eval* needs dense+SPLADE coverage (the
> dev index is sparse-only; the ONNX encoders report `not_found`). Both mechanisms are unit-verified
> (`AgentDispositionWiringTest`, `AdaptiveWeightSelectorTest`). **§13.3 flag-default decision: OFF** —
> the capability + the Q-010 ratchet ship, but flipping the default stays gated on a future
> dense-capable mixed-corpus A/B (the "ship only on net-positive nDCG" rule, §13.8). Likewise Track D's
> PaddleOCR-VL pilot ships the profile + the gate; the actual model A/B awaits a model download.
>
> **Track D runtime de-risked (2026-06-15 — §14.4's "llama.cpp-runnable" caveat now VERIFIED, not just
> claimed).** Downloaded `PaddlePaddle/PaddleOCR-VL-1.6-GGUF` (936 MB model + 882 MB mmproj) and load-tested
> it on the project's **bundled, pinned `llama-server` (build 8185, 2026-03-03)** — it loads FULLY:
> `general.architecture = paddleocr`, the model tensors, AND the vision projector (`clip_model_loader: has
> vision encoder`, `projector: paddleocr`), reaching `main: model loaded` → `server is listening`. So
> PaddleOCR-VL-1.6 is a true drop-in on the EXISTING binary — no `llama-server` upgrade needed (the
> `paddleocr` arch landed via llama.cpp PR #18825 before the pinned build; 1.6 reuses 1.5's architecture).
> The remaining Track D work is now purely the eval: index an OCR-for-RAG corpus (`ohr-bench-*`, on disk
> with qrels) under the `paddle-ocr-vl` profile vs the Qwen-VL default and run the retrieval-aware
> `extraction-gate`. NOTE: the real GGUF filenames (`PaddleOCR-VL-1.6-GGUF.gguf` / `…-GGUF-mmproj.gguf`,
> BF16) differ from the `VlmExtractionProfile` placeholders — the profile's filenames are finalized by the
> model-pack manifest at distribution time.
>
> **Post-review correctness fixes (2026-06-15 — a critical-analysis pass over the learned loop).** A
> review of the §17 learned layer end-to-end found it *captured* signals but could not yet *learn* from
> them — and one contributor fed nothing. Four defects; three fixed + verified, one deferred:
> - **Fix A (landed) — contrast.** Only `OPENED` was emitted per query, so the projection wrote ~one
>   positive label per query-group; LambdaMART needs intra-group contrast, and the `nDCG>0` adoption
>   guard does NOT catch single-positive groups (each scores a trivially-perfect nDCG@10=1.0) — so a
>   degenerate reranker would be adopted (the F-021 recurrence). `LabelProjection` now derives a `SHOWN`
>   negative for every shown-but-not-disposed snapshot hit of a query with a positive (the snapshot
>   already holds every shown hit, so it is a pure projection). v0 = all-shown; a click model
>   (skip-above) is the future position-bias refinement.
> - **Fix C (landed) — adoption gate.** Real disposition-derived labels displace the GPL cold-start
>   prior only once `contrastGroups >= 20` (queries with both a positive and a negative), not on the
>   first label.
> - **Fix D (landed, browser-verified) — `DWELLED`.** The named P3 signal had no producer; the inspector
>   lifecycle now arms a 3s dwell timer (cancel on close/switch/new-query). Verified live: open a result,
>   hold >3s → `DWELLED` persists and joins its snapshot.
> - **Fix B (BUILT 2026-06-15 — was deferred, now landed).** The agent-citation contributor (P4)
>   minted `agent-<UUID>` ids with no snapshot, so every agent CITED/SHOWN disposition was dropped by the
>   join → P4 produced zero training labels. **Resolved:** (1) `SearchTool.buildSearchEvidence` now emits a
>   SEPARATE `feedbackFeatures` list (per-leg scores keyed by `parent_doc_id`) — a feedback channel the tool
>   card does NOT render, so no uncalibrated score reaches the UI (the 559 §5 line is about *display*); (2)
>   `AgentRunStore.appendEvent` stamps the run's `sessionId` onto the persisted/fanned-out event payload only
>   (the SSE wire payload is built separately, so the event-schema conformance gate is unaffected); (3)
>   `AgentDispositionWiring` captures a `FeatureSnapshot` keyed by `sessionId` from each
>   `tool_exec_completed`'s `feedbackFeatures`, and keys the done-event dispositions by the SAME `sessionId`;
>   (4) `LabelProjection` unions per-`interactionId` snapshots (multiple agent searches per run). The
>   docId-space is the SAME (`hit.id()` == `parent_doc_id` == normalized path — verified at source). Multiple
>   searches per run union into the run's snapshot (coarse but honest). Regression test
>   `AgentDispositionWiringTest.agentRun_capturesSnapshot_andDispositionsJoinIt_bySessionId` proves a CITED
>   disposition now joins → real labels (was 0). Full app-agent + app-services suites green; conformance gate
>   unaffected. (Live agent-run proof on the LLM stack is the remaining tier.)
>
> **Scope honesty (one fork candidate):** the **global-comparison-capability** thread (§11,
> benchmarks/marketing/credibility) is a genuinely *distinct strategic concern* — it answers
> "how do we compare JustSearch to competitors," not "is the engine frozen." The doc itself
> flagged it as forkable; if it grows past §11, it should become its own tempdoc. Everything
> else here shares the one reason-to-change ("the frozen engine") and stays unified per AHA.
>
> Per `rule:tempdocs-are-dated-history`: §0–§12 reflect 2026-06-13 `main`; §13–§14 reflect
> 2026-06-15. Older headline conclusions are superseded where the bottom-line note below says so.

> **⮑ Work split with tempdoc 581 (read this if you're picking up either doc).**
> The **language / multilingual** thread was spun OUT of this doc into
> **[`581-native-multilingual-no-per-language-levers.md`](581-native-multilingual-no-per-language-levers.md)**.
> Keep the boundary clean:
> - **580 (this doc) owns:** the *relevance-freeze* organizational finding, the live
>   regression verification, FW-001 supersession, the GPL→LambdaMART investigation
>   (F-021), the extraction-quality and global-comparison threads.
> - **581 owns:** the *native-multilingual invariant* (no per-language levers) and the
>   reclassification of the per-language backlog items — **FW-006 stemming** and **Q-004
>   locale-aware BM25** are now **won't-do**, decided in 581, *not* here. Do not re-open
>   them from this doc's older "open backlog" prose (§2.4); that prose is superseded by 581.
> - **Rule of thumb:** anything about *language coverage / per-language work* → 581.
>   Anything about *the engine being frozen / which non-language levers are live* → 580.

## ⚑ Current bottom line (updated 2026-06-15) — READ THIS before the body

This doc is append-only history, and **several headline conclusions in §0–§12.8 were superseded by
later findings.** Where each thread actually landed:

1. **The core finding stands.** The retrieval engine was frozen ~2 months while presentation/governance
   absorbed ~46k lines (§1); a live regression check (§4a) confirmed the production search path is
   **healthy** (hybrid nDCG on-baseline) → the freeze is *stagnation, not decay*.
2. **FW-001 (corpus-adaptive fusion weights): SUPERSEDED** (§10, user decision). Premise is real — the
   optimal recipe flips by corpus (§9.3) — but the binary switch is too crude.
3. **The "general recipe system" (GPL→LambdaMART learned fusion): EMPIRICALLY DISCREDITED** (§12.9 +
   register **F-021**). It already EXISTS and is wired (§12.1), not greenfield — but it was **already
   measured (tempdoc 245) to *hurt* real-query nDCG (−0.01 to −0.10)** because GPL *synthetic* training
   queries don't transfer; my live re-run was a degenerate no-op (§12.8's "neutral" was a mis-framing).
   **Not a quality lever without real user-feedback labels** — a ship-then-learn dependency, not a code change.
4. **The one shipped code deliverable:** the LambdaMART **bootstrap first-model bug is FIXED** (§12.7,
   branch `worktree-lambdamart-bootstrap-fix`, unit-tested + build-green, ready to merge). It makes the
   learned layer *trainable* — orthogonal to whether the learned layer is worth using.
5. **External landscape (§7):** corrected against live config (we're already on Lucene 10.4). The genuinely
   promising *external* lever is **document-extraction quality** (F-009 / OCR-VLM via OmniDocBench), eval-gated.
6. **Global comparison capability (§11):** a separate thread — Tier 0 (BEIR reproducibility) is cheap/ready;
   OmniDocBench is the marketing-grade axis.

**Net engine verdict:** the frozen engine is *healthy*; the *learning-to-rank* internal lever
(GPL→LambdaMART) is a dead end for cold-start; real *learned* retrieval lift needs **real user-feedback
capture** (ship-then-learn) **or better extraction** (F-009). This doc's lasting value = the *map of dead
ends* (so they aren't re-walked), the bug fix, and register F-021.

7. **Correction (2026-06-15 takeover, §13).** The bottom line above slightly over-reached: §12 declared the
   *whole* "general recipe system" discredited, but that **conflated two designs**. GPL→LambdaMART
   (learning-to-rank, needs real clicks) is genuinely dead — but the §10.3-named **fit-a-fusion-weight-function
   from features** is a *separate, label-free* lever (the register's per-corpus optima ARE its training signal)
   that was **never built or measured**. §13.3 recovers it as **result-set-adaptive fusion weighting** — the
   one engine lever that is both label-free and unmeasured — with a ~1-day decisive experiment. So FW-001 is
   not fully dead: its binary-switch *granularity* was wrong (whole-index on a mixed personal corpus), not its
   premise. (All §13.1 load-bearing claims re-verified against source; verdicts otherwise hold. Open direction
   call: §13.6.)

**Process note:** three `audit-without-test` misses this session — subagent tempdoc-readings that omitted
counter-evidence (esp. §10.1 leaning on 234's *prediction* while missing 245's *measurement*). Verify
load-bearing tempdoc claims against source before building a narrative on them. **§13 adds a fourth shape:**
a *reading*-driven equivalence ("general recipe system = the wired LambdaMART") that buried a distinct
label-free lever; a source check separates them back apart.

## 0. Thesis in one paragraph

JustSearch is, by its own one-line identity, a *neural search engine*. Yet across
the last ~120 non-merge commits the relevance levers — fusion weights, reranking,
SPLADE/BM25, dense — did not move a single line. All recent energy went to **how
the product presents itself** (the `ui-web` wave: 565/569/574/577) and **how it
governs itself** (the discipline-gate kernel: 554/555/575/576). The charitable
reading — "the engine is mature, nothing left to gain" — is **falsified** by the
search-quality register's own non-empty Measurement-Gaps / Open-Questions /
Future-Work sections and by a designed-but-unwired seam (`CorpusProfile.isLongCorpus()`,
zero production consumers). The engine is not done; it is ignored. This doc proceeds
to (a) confirm no *silent* regression crept in under the churn, and (b) thaw
**FW-001** (corpus-adaptive fusion-weight + CE selection), whose substrate already
exists.

## 1. The finding — quantified imbalance

Fixed-window line churn over the same 119 non-merge commits (HEAD `d2c5129fa`-era),
module code only:

| Area | ~lines | Kind |
|---|---|---|
| `modules/ui-web` | 11,444 | presentation |
| `scripts/governance` + `scripts/ci` + `governance/*.json` + `gates/` | ~22,000 | governance meta-infra |
| `docs/tempdocs` | ~12,500 | design theory |
| `app-services` + `app-agent` | ~1,300 | orchestration |
| **retrieval engine** (`adapters-lucene`, `app-search`, `search`, `reranker`, `indexing`, `app-indexing`, `ai-backend`, `ort-common`) | **0** | — |
| Worker (`indexer-worker` 6, `worker-services` 4, `worker-core` 36) | ~46 | governance/liveness |
| Brain (`app-inference` 104) | ~104 | wiring |

The product's core competency (retrieval + inference quality) absorbed **~0.1%** of
module churn; presentation + governance absorbed the rest.

## 2. Verification — *ignored*, not *done*

Four independent evidence legs, each runnable:

**2.1 Baselines are stale.** Every "Best known" row in
`docs/reference/search-quality-register.md` traces to tempdocs 252/309/326/343/363/366/391,
authored **2026-03-23 → 2026-04-19** (frontmatter `created`). Every dataset's
**Last Validated** is 2026-03-18 → 2026-04-07. Nothing revalidated since.

**2.2 Every engine commit since 2026-05-01 is non-relevance.** Classified
(`git log --since=2026-05-01 -- modules/<engine>/src/main`):
- *Representation/tracing* — 549 (unified `SearchTrace`), 553 (OpenInference spans). Dozens of commits about how execution is *recorded*.
- *Substrate/governance/tests* — 554 (value-laws + jqwik), 555 (pure-seam extraction), 560/561 (cross-process substrate), 575 (liveness).
- *Robustness bug-fixes* — title-length cap, `ChunkSplitter` boundary crash, fusion null-rank symmetry. (Notably the 554 property tests *found real latent engine bugs* — evidence the engine was under-verified, not over-polished.)

None touched a fusion weight, reranker, SPLADE, BM25, or dense parameter.

**2.3 The register's headline win was never built.** D-002 says "Revisit when
corpus-adaptive weight selection is implemented"; **FW-001** (gate CE + select CC
weights by corpus regime) is the most-cited available improvement, justified by
F-002 / F-004 / F-008. Code check:
- `CorpusProfile` (adapters-lucene) computes `isShortCorpus()` / `isLongCorpus()`.
- `isShortCorpus()` is consumed **only** by `CorpusCapabilities.isChunkMergeViable()` (the F-014 chunk-merge gate).
- `isLongCorpus()` has **zero production consumers** — referenced only in its definition and `CorpusProfileTest`. A designed seam, computed every search, read by nothing.

The substrate D-002 names ("`CorpusProfile.isLongCorpus()` → bm25-dom, else → balanced")
exists and dangles. The consumer was never wired.

**2.4 The register itself lists open relevance work.** Non-empty Measurement Gaps
(cord19 dense re-run, desktop-mixed qrel fix), Open Questions (Q-002 short-query,
Q-004 locale-aware BM25 ~~"a cheap win"~~ — **reclassified won't-do (tempdoc 581):** a
per-language lever that violates the native-multilingual invariant, Q-007/Q-009
calibration), and Future Work
(FW-001…FW-010). A *done* engine has an empty backlog.

## 3. Why this happened (the deep read)

The governance kernel (tempdoc 530 lineage) gives **presentation** a ratchet: every
`ui-web` edit trips a gate, so presentation debt is continuously *surfaced and
serviced*. The **engine has no equivalent ratchet** — relevance quality is measured
only by an opt-in `jseval` run a human must remember to launch. So under attention
scarcity, the gated surface (presentation) crowds out the ungated one (relevance),
and the engine silently coasts. This is not a moral failing of any agent; it is the
predictable output of asymmetric enforcement. The risk it creates: a search product
whose relevance is frozen is *decaying relative to its own documented potential*,
invisibly, because nothing fails when it does.

## 4. Proceeding — the plan

**4a. Confirm no silent regression (DONE — clean).** SciFact at HEAD, `full` +
`hybrid` vs the 343/391 baselines. **Hybrid (production preset, CE-on) = 0.758 vs
0.754 baseline — on-baseline, no regression.** Full = 0.708 but CE-off/default-weights,
config-attributable not a regression. Full detail + interrogation in §5. **Verdict:
the freeze is stagnation, not silent decay — the production path is healthy.**

**4b. Thaw FW-001 (corpus-adaptive selection).** Highest value, lowest cost: the
profiler already exists; this wires the dangling `isLongCorpus()` consumer.
- Long corpus (`isLongCorpus()`) → BM25-dominant CC weights (0.60/0.20/0.20); else balanced (0.34/0.33/0.33). Evidence: D-002, the CC-weights comparison table (CourtListener +13.4% bm25-dom; MIRACL/de +14.9% balanced).
- Corpus-regime CE gating: email-regime → CE off (F-002/F-008: CE hurts email 3–5%, helps academic/multilingual). Needs a regime signal beyond length; scope carefully — may land as a second step.
- **Eval-gated**: no FW-001 change ships without a jseval A/B showing net-positive (or neutral) nDCG across ≥3 register corpora, inside the envelope. Per `rule:interrogate-results`, confirm the delta is the wiring, not a reindex artifact.

**4c. Structural residue (name, do not over-build).** The asymmetry in §3 is the
root cause. A *relevance ratchet* (a cheap nightly/again-on-engine-edit `jseval gate`
that fails when nDCG drops >tolerance vs the pinned baseline) would give the engine
the same continuous-servicing pressure the presentation gates give the UI. Recorded
as Open Question Q-010 in the register; **not** implemented here. **§4a resolved its
trigger negatively** — the production path is healthy, so there is no silent-regression
proof-by-example, and the ratchet stays YAGNI/named-only. The case for Q-010 now rests
solely on the stagnation+asymmetry argument (§3) — real, but weaker than a measured
regression would have been; it waits for the user's call rather than self-endorsing.

## 5. Evidence ledger

- **§1 churn**: `git log --no-merges --pretty=%H -120` → per-commit `git show --numstat` summed by module.
- **§2.3 dangling seam**: `grep -rn 'isLongCorpus' modules/**/*.java` → only def + `CorpusProfileTest`.
- **§4a regression eval (DONE 2026-06-13, HEAD `f91e269bc`)**: SciFact, clean ingest, 300 queries, `tmp/eval-results/20260613T045727_scifact/`.
  - **hybrid: nDCG@10 = 0.758** (P@1 0.627, R@10 0.896), legs `cross_encoder, dense, splade, query_classification`, `comparable=true`. Baseline (391, 6-run median) = **0.754**, envelope range 0.7527–0.7571 (CV 0.1–0.3%). Delta **+0.5%**, at the top of the envelope → **on-baseline, NO regression.** This is the clean, config-matched comparison: the production interactive preset, CE-on.
  - **full: nDCG@10 = 0.708** (P@1 0.577, R@10 0.833), legs `dense, splade, query_classification` (**no `cross_encoder`**). Best-known 0.736 was multilingual-stack / bm25-dom / **CE-on**; this run is **CE-off** (no `--ce`; `full` defaults CE-off post-F-015) with default weights → **not a clean comparison.** 0.708 ≈ the register's bm25-dom CE-off full (0.709, 309 §35) and sits inside the full-mode family (0.709–0.736). The −3.8% is **config-attributable, not a confirmed regression** (`rule:interrogate-results`).
  - **Config-drift note:** `query_classification` is an active leg in both modes at HEAD — it was not in the 2026-03 baselines. Hybrid still matches baseline, so it is not hurting; logged as a comparability caveat, not a finding.
  - **Verdict: the freeze is STAGNATION, not silent decay.** The production path is healthy after the 549/553 representation refactors + ~46k lines of churn. To make the full-mode comparison clean, a config-matched re-run (`--ce`, pinned weights/stack) is the follow-up — but it does not change the headline.

## 6. Register updates made by this doc

- Add Open Question **Q-010** (relevance ratchet / enforcement asymmetry) to the search-quality register.
- On §4a completion: add a 2026-06-13 SciFact baseline row (HEAD SHA) and refresh "Last Validated".

## 7. External-landscape scan (2026-06-13) — what the field shipped vs what we run

A deep web-research pass (deep-research workflow, 105 agents) + targeted re-verification.
**Method caveat:** the workflow's adversarial-verification tier was API-rate-limited — 18/25
claims returned `0-0 (abstain)`, i.e. *unverified*, NOT *refuted*. So absence below = "this
pass didn't confirm," not "no advance exists." All version claims re-checked against our own
source (`gradle/libs.versions.toml`), which **corrected the headline finding** (see 7.1).

**Cross-checked against our live config — the key correction (`rule:verify-dont-guess`):**

| Component | We run (verified in-repo) | Field, last 3 mo | Verdict |
|---|---|---|---|
| Lucene | **10.4.0** (`libs.versions.toml`) | Lucene 10.4.0 (Feb 25 2026) | **We are already on it.** Speedups already ours. |
| ONNX Runtime | **1.24.3** (`libs.versions.toml`) | ORT 1.26 (~May 2026), CUDA EP graph+profiling | 1.26 is a **real forward upgrade**; F-005 fix status unknown. |
| Dense embedding | gte-multilingual-base 768d | EmbeddingGemma / Qwen3-Embedding | **EmbeddingGemma already tried+abandoned** (F-012, FP16 NaN). |
| Extraction VLM | Qwen-VL path (ADR-0018 / 252/346) | PaddleOCR-VL-1.5 (0.9B, Jan 2026), olmOCR 2 | Genuine new options; **eval-gated**. |

**7.1 Lucene (#9/#1) — NOT an upgrade, NOT a format adoption; the residue is the bit width.**
Three layers of grounded correction (each verified in-repo, each killing a coarser "finding"):
1. We are already on **Lucene 10.4.0** (`libs.versions.toml`) → the 10–15%/35% query speedups are already ours.
2. `JustSearchCodec` already uses **`Lucene104HnswScalarQuantizedVectorsFormat`** (`ComponentsFactory` → `JustSearchCodec.quantizedFormat`) → we are already on the new 10.4 quant format family.
3. It is configured at **7-bit** scalar quantization (Int8, ~75% vs Float32, 0.99 CI). 10.4's headline improvement is that **2-bit now beats the *old* 4-bit recall**.
   → The only real Lucene lever for us: **eval a 7-bit→2-bit drop.** Potential ~another 73% vector-memory cut (768→~192 B/doc) IF 10.4's improved low-bit recall holds on our corpora. **Eval-gated** (re-encode vectors at 2-bit, measure nDCG vs 7-bit on the register corpora); low-risk, no model swap, no schema change. (The workflow marked the 10.4 quant claim "refuted" — a rate-limit abstention; it is true, and we already exploit it at 7-bit.)

**7.2 Extraction VLMs (#7) — highest *expected* leverage, because the bottleneck is documented.**
Maps to F-009 (extraction = single largest quality bottleneck, −15% to −33% nDCG). New, in/near-window,
consumer-GPU-feasible, OPEN: **PaddleOCR-VL-1.5** (0.9B, ~1.9 GB, Jan 29 2026 — far cheaper than full
Qwen-VL) and **olmOCR 2** (7B/FP8, same Qwen2.5-VL family → low integration risk). ⚠️ **Both models'
public benchmark numbers (94.5% OmniDocBench, 82.4 olmOCR-Bench) were NOT verified** — validate on our
own OCR'd-PDF corpus (the F-009 set) before believing the win.

**7.3 Embeddings (#1) — low priority for us.** EmbeddingGemma/Qwen3-Embedding are strong but: (a)
predate the window; (b) **EmbeddingGemma is known scar tissue** — we ran it as default and abandoned it
on a model-specific FP16 NaN at head_dim=256 (F-012 / 343 / 358); (c) any swap = full reindex; (d) our
own F-006 found embedding/CE swaps largely neutral once retrieval is strong. Re-piloting requires first
solving the FP16 NaN. **Not recommended over 7.1/7.2.**

**7.4 ONNX Runtime (#6).** We run 1.24.3; ORT 1.26 (~May 2026) is a real forward bump (CUDA EP graph
support + profiling). **Our F-005 XLM-RoBERTa CUDA 5.7× slowdown fix status is unconfirmed** — answerable
only by a direct gte-reranker benchmark on a newer ORT, not by web search.

**7.5 Unconfirmed leads (rate-limited, need a clean second pass), NOT dismissed:**
SPLADE successors (#2), new rerankers (#3 — BGE/Jina/Qwen3-Reranker/listwise), ColBERT late-interaction
(#4 — and whether Lucene exposes it), and a real BFCL/RAG-grounding comparison of ≤8B LLMs (#5 — Qwen3-4B/8B,
Phi-4-mini as Llama-3.1-8B-class candidates). Zero confirmed findings this pass.

**7.6 Priority vs the internal thaw (FW-001).** Ranked by (evidence × leverage ÷ risk):
1. ~~**FW-001** (internal, §4b) — designed, justified by 4 findings, substrate exists, no reindex. Still #1.~~ **SUPERSEDED (§10):** the binary switch is demoted to interim baseline; the new #1 is a *general* recipe-weight system (shape TBD by the user).
2. **Lucene 7-bit→2-bit quant eval (7.1)** — already on the 10.4 format at 7-bit; test a 2-bit drop for ~another 73% vector-memory cut. Low risk, eval-gated vector re-encode.
3. **OCR-VLM extraction pilot (7.2)** — highest ceiling (F-009) but eval-gated on our corpus, heavier.
4. Embeddings (7.3) / ORT (7.4) — low priority / test-first.

## 8. Honest limits of the §7 research (self-audit)

This scan was **competent at the concept level but config-blind at the start** — it asked
"is there a newer Lucene?" without first checking we run 10.4.0, and recommended EmbeddingGemma
without knowing we'd abandoned it. Both were caught only by *post-hoc* in-repo verification, not
by design. The correct order was grounding-first (pin live versions + our own "already-tried /
found-neutral / found-bottleneck" list from the register), THEN a window-constrained,
exclusion-aware research brief weighted by our own findings (front-load #7, exclude EmbeddingGemma).
The heavy multi-agent harness was also reached for before a cheap scoping search established it was
warranted; its verification tier then collapsed under rate-limits, and a handful of targeted manual
searches recovered more in-window signal per token. Recorded so the next external scan starts from
source, not from the doc.

## 9. De-risking pass (2026-06-13) — confidence before implementation

Plan-mode-approved confidence-building before touching any feature code: read-only source recon
(3 Explore agents) + one measurement experiment (the CC-weight A/B). **No feature code written.**
This section supersedes earlier coarser framings (esp. §7.1's "2-bit" lever).

### 9.1 Mechanics — settled by source recon (high confidence)

| Question | Verdict | Evidence |
|---|---|---|
| FW-001 CC-weight injection site | **Clean, ONE site** — `SearchExecutor.execute()` builds `double[] weights` from `ResolvedConfig.hybridSearch()` (`SearchExecutor.java:~435`) → `HybridFusionUtils.fuseWithCC3`; `SearchInputs.corpus()` (`CorpusCapabilities.medianTokenCount()`/`isShortCorpus()`) is **already in scope** at that method | worker-services `SearchExecutor`, `SearchInputCapture.java:133-139` |
| Weights query-time? | **Yes** — applied at fusion; A/B needs no reindex, only a backend restart with different weights | `fuseWithCC3` |
| Eval override channel | **Works** — env `JUSTSEARCH_HYBRID_CC_WEIGHT_{SPARSE,DENSE,SPLADE}`, inherited by the backend subprocess (`backend.py:91` `os.environ.copy()`). ⚠️ jseval's `summary.json.search_config.cc_weight_*` and `env_overrides` **do not echo** the applied weights (a reporting gap) — verify via the nDCG delta, NOT the echo | `EnvRegistry.java:901-906`, `backend.py:91-95` |
| CC mode | CC weights apply to **`full`** (`fuseWithCC3`); `hybrid` preset uses RRF → A/B must use `full` | pipeline overview |

### 9.2 FW-001 must be SPLIT (scope finding)

- **CC-weight half — READY + premise-validated.** Clean 1-site injection; `isLongCorpus()`/`medianTokenCount()` already threaded in. **Implementable in ~one site.**
- **CE-gating half ("email→CE off") — DESCOPE.** No content-type signal exists anywhere; `isRerankerEligible` keys only on queryType/length/hit-count (`KnowledgeSearchEngine.java:163-175`). "Email vs academic" needs a NEW indexed `source_type` field + corpus-level aggregation — a separate, larger effort. **Do not bundle it into the cheap FW-001.**

### 9.3 The premise experiment — CC-weight A/B at HEAD (`full`, CE-off)

The risk FW-001 had to clear: is corpus-adaptive weighting still worth it on the *current* stack,
or did it flatten (as F-006 flattened CE-model choice)? Measured at HEAD `f91e269bc`:

| Corpus (regime) | bm25-dom 0.60/0.20/0.20 | balanced 0.34/0.33/0.33 | Winner | Register (old stack) |
|---|---|---|---|---|
| **CourtListener-200 (long legal)** | **0.9770** | 0.7772 | **bm25-dom +25.7%** | bm25-dom +13.4% |
| **SciFact (short academic)** | 0.7097 | **0.7224** | **balanced +1.8%** | balanced +1.9% |

**Verdict: premise FULLY VALIDATED — the optimal recipe FLIPS by corpus.** Long docs → bm25-dom
by a country mile (+25.7%); short docs → balanced (+1.8%). The SciFact numbers reproduce the
register's old-stack figures almost exactly (0.7097/0.7224 vs 0.709/0.723), so corpus-adaptivity
did **not** go stale on the current stack — unlike CE-model choice (F-006). A single
`isLongCorpus()`-driven switch captures both wins. The empty echo fields nearly produced a false
"override broken" conclusion (`rule:interrogate-results`): the nDCG deltas — not the unpopulated
`search_config` echo — are the proof the override worked.

**Residual efficacy unknown (the one real risk left):** the win is proven on *benchmark* corpora.
Whether a real personal-files index ever trips `isLongCorpus()` (median > 2048 tokens AND chunkRate
> 0.5) is untested — if real corpora are mostly short, the big long-corpus win is rarely realized in
production (the small short-corpus win still applies). Measure `CorpusProfile` on a realistic corpus
before tuning the thresholds.

### 9.4 Other items — corrections that lower their confidence

- **7→2-bit quant (#2): downgraded.** Lucene 10.4's `Lucene104ScalarQuantizedVectorsFormat` takes a **`ScalarEncoding` enum, NOT a numeric `bits` param**; docs confirm a 1-bit level + 4-bit query precision but **no explicit "2-bit."** So §7.1's "2-bit drop" is not a given — the realistic lever is "try a lower `ScalarEncoding` vs current 7-bit," via a constructor switch in `JustSearchCodec` + a config field; **reindex required**. Feasible but smaller/less certain than the external scan implied.
- **OCR-VLM (#7): real integration cost.** Extraction is hardcoded to **Qwen3.5-9B + mmproj via llama-server** with **no pluggable-extractor abstraction** (`VduProcessor.java`, `InferenceConfig.java:127-168`). Swapping PaddleOCR-VL/olmOCR = a new inference backend or subprocess wrapper, not a config swap. The leverage (F-009) is still highest, but the cost is real.

### 9.5 Confidence (post-de-risk) — rating 0–10

- **FW-001 CC-weight half (binary switch): 8/10 *as a tactical fix* — but SUPERSEDED as the long-term design (see §10).** Mechanics proven (1-site, clean injection, profile in scope, query-time, no reindex); premise fully validated at HEAD (the flip is real on BOTH ends and reproduces the register). Eval harness for A/B-validating the implementation works. The −2: (a) unproven whether real personal corpora ever trip `isLongCorpus()` (production efficacy unknown), and (b) the switch thresholds (2048 / 0.5) + exact weight vectors need an eval tuning pass. **Per the 2026-06-13 user decision (§10), the binary two-bucket switch is no longer the target — it is at most an interim baseline of a more general recipe system.**
- **FW-001 CE-gating half: 3/10** — needs new indexed `source_type` substrate; descoped.
- **7→2-bit quant: 4/10** — `ScalarEncoding` enum (no real 2-bit), reindex required, win uncertain.
- **OCR-VLM extraction: 5/10** — highest leverage (F-009) but real integration cost (no pluggable-extractor abstraction); benchmark numbers unverified.

## 10. Direction change (2026-06-13, user decision) — FW-001's binary switch is superseded

**Decision (user):** The FW-001 idea — pick weights by a binary `isLongCorpus()` test, choosing
between two hand-set recipes (bm25-dom vs balanced) — is **validated as directionally correct but
NOT good enough long term.** The fusion-recipe (CC-weight) values need a **more generally applicable
system**, not a two-point lookup. FW-001-as-scoped is therefore **demoted**: at most an interim
baseline, not the destination. This supersedes the §4b plan and the §7.6 priority that put the
binary switch at #1.

**Why the binary switch is too crude (the limits that motivate generalization):**
- **2-point step function.** Two buckets, two hand-picked weight vectors, two hand-picked thresholds
  (median > 2048, chunkRate > 0.5). It cannot interpolate the middle, and the four magic numbers
  don't generalize across domains/languages.
- **Whole-index granularity.** One global recipe for the entire corpus — but a real index *mixes*
  long and short docs, and the optimal blend may depend on the **query / retrieved result-set**, not
  the corpus-wide average. (`CorpusProfile` is a whole-index statistic.)
- **Values are hand-tuned, not fit.** The register already shows the optimum varies *continuously*
  across corpora (SciFact balanced, CourtListener bm25-dom, MIRACL balanced, EnronQA bm25_splade…) —
  that's a curve to fit, not two points to hard-code.
- **Length-only.** It keys on one signal; query type, language, and retrieval-score dispersion
  plausibly matter too.

**The generalization design space (axes to resolve — NOT yet a committed design):**
1. **Granularity:** per-index (global) → per-query / per-result-set adaptive.
2. **Shape:** discrete buckets → a continuous function of corpus/query features.
3. **Source of values:** hand-tuned → **fit/learned from eval data** (the register's per-corpus
   optima are training signal; a small model/curve from features → weights).
4. **Feature set:** length-only → richer profile (length *distribution*, query type, language,
   score dispersion / QPP signals). NB (tempdoc 581): language is admissible here only as a
   *signal* to this one uniform weight function — **not** as a license for per-language
   *components* (stemmers, locale fields), which the native-multilingual invariant rejects.
5. **Objective & honesty:** how weights are chosen and how the system is held to a measurable
   quality target (eval-gated per corpus; ties to Q-010's relevance-ratchet idea).

**Reframe:** the binary switch is a *degenerate special case* of this general function (a 2-point
lookup on one feature). It remains useful only as (a) proof that adaptivity matters — already
demonstrated (§9.3) — and (b) a cheap fallback baseline to beat. It is no longer the thing to ship.

**This is NOT a greenfield problem — see §10.1 for the documented prior art.** The user's steer
("some ideas, none good enough yet") points at an existing design line, not a blank page. The next
design must build on that history and confront its specific blockers, not re-propose a shape already
explored.

**Status:** FW-001 (binary) → INTERIM/SUPERSEDED. New headline direction: a general recipe-weight
system whose prior art and blockers are catalogued in §10.1. §7.6 priority reordered accordingly.

### 10.1 Prior art — the general recipe-weight problem has a documented history (don't re-derive)

> ⚠️ **CORRECTED by §12 (de-risk #2, 2026-06-13).** This subsection was built from a subagent's
> *reading* of tempdocs, not code (`audit-without-test`), and its status claims are WRONG: **GPL and
> LambdaMART already exist and are production-wired.** Read §12 before trusting the table below.

The "general system for fusion-recipe weights" was explored across a tempdoc line. FW-001's binary
`isLongCorpus → {bm25-dom, balanced}` switch is a **2-cell fragment** of designs that already went
further. Catalogue (so the next design starts here):

| Tempdoc | What it proposed for choosing weights | Status / why it didn't become the answer |
|---|---|---|
| **270 optimal-search-routing** | The fullest design: **Layer 0** corpus-regime classify (SHORT/MIXED/LONG) · **Layer 1** query-type classify · **Layer 2** `(regime × type) → weight recipe` policy table · **Layer 4** Thompson-Sampling bandit adapts weights post-launch via QPP reward | Investigation done; **policy table never validated, bandit never built — cold-start** (single-user desktop can't supply pre-launch feedback). FW-001 ≈ a hand-picked 2 cells of Layer 0×2. |
| **309 search-routing-considerations** | Refined 270; dropped per-doc dense modulation (no basis); kept SPLADE parent-length interpolation (shipped) | The **killer fact**: QPP routing signals buy only **~4% nDCG**, and **collection identity dominates (F≈137.6)** — signal-driven adaptation is a weak lever. Calibration never run. |
| **234 retrieval-architecture-alternatives** | The **learning path**: **GPL** (synthetic query-doc triples to beat cold-start) + **LambdaMART** (learns the full rank ordering, *subsuming* weight selection) | Endorsed but **not operationalized** — waiting on GPL activation + stable eval. A 2-feature LambdaMART prototype exists (273) but isn't default. This is the strongest "general system" candidate. |
| **274 / 306 / 322** | What actually shipped instead: static CC `[0.35/0.35/0.30]` (274); rule-based query classification gating CE/expansion + field boosts, NOT leg weights (306); long-doc SPLADE fixed by **model swap to BGE-M3**, not weight tuning (322) | Shipped & adequate; none is corpus/query-adaptive on the leg weights. |
| **258 search-quality-direction-program** | Meta-verdict: sequence **eval control plane → adaptive routing → ingestion → model bets** | Declares adaptive fusion-weight selection **LOW-LEVERAGE** until eval+GPL are stable; the real bottlenecks are eval-mismatch and ingestion, not the fusion recipe. |

**The four walls every adaptive attempt hit** (this is the real long-term obstacle, not the weight
shape): (1) **cold-start** — no per-user feedback to learn from; (2) **cross-corpus calibration** —
hand-tuned tables don't generalize, corpus identity dominates; (3) **weak signals** — best routing
signals (QPP/WIG/NQC) cap ~4% nDCG, near the cost/benefit line; (4) **architectural debt** — a model
swap (SPLADE→BGE-M3) sometimes beats any amount of weight tuning.

**Implication for the next design:** the durable "general recipe system" is most likely **234's
GPL→LambdaMART learned fusion gated behind 258's eval control plane** — i.e., *activate GPL + learned
ranking + a trustworthy eval lane*, NOT "design better weight buckets / policy tables / per-doc
modulation" (270/309 already explored those and they stalled on the four walls). Any new proposal
should state explicitly which of the four walls it overcomes. FW-001 remains only a cheap baseline
for such a system to beat. **Next step: a dedicated tempdoc scoping the GPL+eval path against the
four walls — not implementation of the binary switch.**

## 11. Global comparison capability — benchmarks, credibility, and the two bars (2026-06-13)

Captured here per user direction (a distinct strategic thread, may later fork to its own tempdoc).
Goal: reach the capability to compare JustSearch **globally**, for both **marketing** and **general
information**. Internet research (recognized benchmarks + credibility + legal) synthesized below.

**This is NOT greenfield — it extends the existing eval line:** 258 §D1 already made an "eval control
plane" the first priority and **explicitly retained the BEIR lane "for external comparability"** with
a north-star objective (known-item MRR@10/P@1 50% · nDCG@10 25% · context-quality 15% · robustness
10%); the control plane was delivered by **259**; **251** is the realistic-eval-framework seed; **308**
rewrote eval onto **`ir-measures`** (the standard scorer). What's missing for *global* comparison is
(a) reproducibility alignment, (b) the extraction axis, (c) the marketing/legal bar, (d) a real
head-to-head vs named competitors.

### 11.1 Two bars (do not conflate)

- **General information (internal truth):** bar = *reproducible & honest* ("different team, same setup, same number").
- **Marketing (public comparative claim):** bar = *legally substantiated*. FTC requires **independent, head-to-head testing under equivalent conditions, on current versions, substantiated BEFORE the claim runs** — you cannot test-after-challenge. Material differences must be disclosed; the competitor named.

### 11.2 Recognized benchmark landscape (by axis)

| Axis | Recognized benchmark(s) | Our status | Marketing value |
|---|---|---|---|
| Retrieval quality | **BEIR** (nDCG@10, now in **MTEB**), **MIRACL** (multilingual) | already emit via jseval+ir-measures (308) | **WEAK** — saturated, contamination-prone, model-driven, undifferentiated |
| Reproducibility/credibility | **Pyserini "two-click reproduction" + Brewing-BEIR official leaderboard** (validates to 1e-4) | not yet aligned to it | the key to making any quality number *believable* |
| Document extraction | **OmniDocBench** (CVPR 2025, v1.7 Apr 2026; text+tables+formulas+layout) | not yet run | **STRONG** — recognized public benchmark on our actual differentiator (F-009) |
| RAG/answer | **RAGAS** (faithfulness/context precision-recall), DeepEval | partial (Q-007/Q-009 open) | medium — LLM-judge eval is itself unreliable |
| Performance | ann-benchmarks (criticized: low-dim, ignores hybrid/filter/mutation) | have ingest-bench/knn-bench | only same-hardware, realistic-workload counts |

### 11.3 Credibility traps (what sinks marketing claims)

1. **Contamination & saturation** — BEIR is "no longer truly zero-shot" (models train on it); MTEB has 400+ models within noise ("Leaderboard Illusion"). A high cell can be contamination, not skill. **Do not lead marketing with a BEIR number.**
2. **Self-serving-benchmark accusation** — if *we* design the benchmark, competitors call it rigged (exactly the vector-DB-vendor pattern). Mitigate: use public benchmarks we don't own (BEIR-via-Pyserini, OmniDocBench) and/or an independent run.
3. **Category error** — comparing our packaged *local product* to a *cloud API* or *infra library* is apples-to-oranges and easily debunked.

### 11.4 Where the marketing value actually is (NOT raw BEIR)

Our three real differentiators — only one is a leaderboard:
1. **Extraction quality → OmniDocBench.** Recognized public number on our actual bottleneck; best marketing-grade single-number candidate.
2. **Local-first / privacy → a factual claim, not a metric.** Substantiable by architecture; competitors are visibly weaker (e.g. Docora sends excerpts to cloud; LaSearch is fully local). Needs no benchmark.
3. **Whole-pipeline quality on a real personal corpus → no public benchmark exists.** None of LaSearch/Docora/Fenn/Spotlight publish IR metrics — they compete on features. Gap *and* opportunity.

### 11.5 Roadmap to the capability (tiered)

- **Tier 0 — internal scorecard (cheap):** align our BEIR/MIRACL runs to the **Pyserini/Brewing-BEIR reproduction matrix** so our nDCG sits on the recognized axis next to published BM25/SPLADE++/ColBERTv2/BGE-M3. Reproducible ⇒ credible. (Operationalizes 258 §D1's "external comparability" lane.)
- **Tier 1 — public number on our differentiator:** run our extraction pipeline through **OmniDocBench**. Globally legible, on a benchmark we don't control.
- **Tier 2 — the only FTC-defensible "we're better" (expensive):** build a representative **personal-files benchmark** (mixed email/PDF/docs/code + qrels = 251's realistic-eval seed) and run **named competitors + JustSearch on the same corpus + same hardware**, quality *and* performance.
- **Tier 3 — credibility hardening:** publish harness+corpus openly and/or commission a third-party run; pre-substantiate before any public claim.

### 11.6 Bottom line

Globally-comparable *quality numbers* are achievable and we're closest on the algorithm axis — but
that axis is the **weakest for marketing**. The marketing-useful comparisons are **OmniDocBench
(extraction)**, **a built realistic head-to-head** (the only lawful "we beat X"), and the
**privacy/local-first factual claim** — never a cherry-picked BEIR cell. The prerequisite is the same
trustworthy-own-measurement that 258/251/259 already called for. **Sources:** BEIR/MTEB leaderboard;
Pyserini + "Brewing BEIR" (arXiv 2306.07471); OmniDocBench (arXiv 2412.07626); RAGAS; FTC comparative-
ad substantiation; "Leaderboard Illusion" (arXiv 2504.20879); vector-DB-benchmark critique (Actian).

## 12. De-risking pass #2 (2026-06-13) — verification corrections + re-rating

Plan-mode-approved confidence pass over the *current* remaining work (general recipe system §10,
comparison §11, OCR §7.2). Read-only verification (3 Explore agents against **code**, + eval-artifact
reads). **No feature code.** It overturned a material error in this very doc.

### 12.1 🔴 CORRECTION — the "general recipe system" already EXISTS and is WIRED (not greenfield)

§10/§10.1 framed the general recipe-weight system as something to *build* (GPL→LambdaMART). **Verified
against source — it is already built and production-wired:**
- **LambdaMART** — `modules/app-services/.../gpl/LambdaMartReranker.java`; runs FIRST in the rerank
  cascade (`KnowledgeSearchEngine.java:526-574`); **enabled by default in TEXT/HYBRID presets**
  (`PipelineConfig.java:31,39`); 2-feature (sparse+vector), LightGBM, hot-swap. It **no-ops only when
  no trained model file is loaded.**
- **GPL** — `modules/app-services/.../gpl/GplJobCoordinator.java`; full pipeline: corpus → LLM
  synthetic queries → cross-encoder scoring → triple store (`gpl-training-triples.ndjson`) → triggers
  LambdaMART training. Wired; callable from agent tools/UI.
- **Source fixes to §10.1:** the "2-feature LambdaMART" fact is in **234** (the deployed impl), NOT
  273 (273 doesn't discuss it); the **F≈137.6** stat is in **234**, not 309 (309 does confirm the
  ~4% QPP ceiling). 234 (`status: done`) is the real design source and already chose this path over
  tuned-CC.

**⟹ The remaining work is ENRICH + ACTIVATE + VALIDATE the existing learned layer, NOT build one.**
234 already contemplates a richer V2 feature schema (QPP etc.). This raises confidence on §10's
direction sharply — and means the next tempdoc scopes *feature enrichment + a training/eval loop*,
not a from-scratch learning-to-rank system. (Cold-start is still real: GPL trains on **synthetic
LLM/CE labels**, not user feedback — no implicit-feedback capture exists, confirmed absent.)

### 12.2 🟡 The learned layer is DORMANT in our evals
`lambdamart` is **absent from the observed legs** of every recent eval (`courtlistener`, `scifact`×2)
— it skipped for lack of a trained model. So **every baseline in this doc (incl. the 0.758 hybrid)
ran WITHOUT the learned-ranking layer.** There is latent, untested headroom; the immediate experiment
is "**train GPL→LambdaMART and measure with/without**," not "build."

### 12.3 🟢 Tier 0 (BEIR comparability) is GREEN
`jseval/corpora.py` loads **official BEIR via `ir_datasets`** (`beir/scifact/test`, binary qrels, test
split); `scoring.py` uses **`ir-measures` nDCG@10**. **Our numbers are already on the same axis as the
Pyserini/BEIR leaderboard — no reconciliation needed.** §11.5 Tier 0 confirmed cheap/near-mechanical.

### 12.4 🟡 Tier 1 (OmniDocBench) — Tika standalone, VLM coupled
`StructuredContentExtractor.extract(Path)` is standalone-invocable (no gRPC/Lucene/queue) → the *Tika*
path is a small harness. The differentiating **VLM path (`VduProcessor`) is coupled to the AI runtime**
→ heavier. The F-009 win lives on the VLM path, so the valuable measurement is the harder one.

### 12.5 Confidence rating — remaining work, 0–10 (post de-risk #2)

- **General recipe system: 7/10** (was ~3 when mis-framed as a build). Substrate exists+wired; remaining = activate + enrich features + validate. −3: synthetic-GPL-label training quality on small/personal corpora is **unmeasured**, and cold-start (no user feedback) caps the learning signal.
- **Tier 0 comparison: 9/10** — same-axis confirmed.
- **Tier 1 OmniDocBench: 7/10 (Tika) · 5/10 (VLM)** — the valuable path is AI-runtime-coupled.
- **OCR-VLM pilot: 5/10** — integration cost known, gain unmeasured.
- **Tier 2 realistic head-to-head: 4/10** — still large/unscoped.
- **Overall remaining work: ~6.5/10** — up materially, because the two highest-priority threads de-risked upward. Residual risk concentrates in *whether learned/synthetic training actually moves real quality* (one approved experiment away) and in the heavy Tier 2.

### 12.6 Experiment RESULT (2026-06-13) — headroom number NOT obtained; the activation path is mapped instead

Ran on the full dev stack (CourtListener-200, LLM active). Outcome is more valuable than the number
would have been: **the entire learned-layer activation path is now mapped, with an apparent bug.**

- **Baseline (no model): CourtListener full nDCG@10 = 0.9758**; `lambdamart` absent from observed legs (confirms the learned layer was OFF — as in every baseline this session).
- **GPL works — empirically proven.** It **auto-fired** on the full stack once the index stabilized + LLM was up, and generated **5,448 triples from 200 docs in ~6 min** (`gpl-training-triples.ndjson`). §12.1's "GPL is wired" is confirmed at runtime, not just in code.
- **But no LambdaMART model trained** — three concrete activation blockers, each verified from code + head log:
  1. **Chicken-and-egg flag.** `lambdamartEnabled` defaults **false until a model file exists** → training is gated off on a fresh index. Must set `JUSTSEARCH_LAMBDAMART_ENABLED=true` (confirmed: `Config: justsearch.lambdamart.enabled=true (env_var, ordinal=400)` once forced; the flag propagates fine through jseval).
  2. **Apparent bootstrap-ordering bug.** With the flag ON, `LambdaMartTraining.loadOrTrain` logs `"no model file; retraining from existing triples"` and calls `trainAsync` — but `startAsync` then **returns silently because the GPL coordinator is null at that bootstrap point** (`if (coordinator == null) return;`). No `"starting training from …"`, no model. So the "retrain from existing triples on startup" path is effectively dead.
  3. **The only working path** is the **GPL-completion snapshot callback** (fires `startLambdaMartTrainingAsync` with a live coordinator, *when the flag is on*) — which needs a **fresh GPL run**. On the stale-triples backend the auto-trigger stayed IDLE and did not re-fire on demand in-session.
- **Net:** all session baselines (incl. the 0.758 hybrid and 0.9758 CL) ran **without** the learned layer; getting the *first* model trained is non-trivial (flag + LLM-up + a fresh GPL run so the completion callback fires with a live coordinator). The **precise activation recipe** is now known for the implementation phase.
- **Headroom (does a trained model improve real-query nDCG) remains UNMEASURED** — and on CL's 0.9758 ceiling it would have been low-information anyway. The proper headroom test = a fresh GPL run on a **mid-range corpus** (SciFact) with the flag on + the §12.6.2 bug fixed/worked-around.

**Rating impact:** general recipe system **7 → 6/10.** Existence confirmed at runtime (big +), but
**activation has real friction + an apparent ordering bug**, and **efficacy is still unmeasured** (−).
The residual risk sharpened from "is it built?" (answered: yes) to "what does it take to activate it,
and does its synthetic-label training actually help?" (the activation path + a mid-range headroom run).

### 12.7 Bootstrap bug FIXED (2026-06-13) — branch `worktree-lambdamart-bootstrap-fix`

The §12.6.2 ordering bug is fixed (commit `8d2817d13`, ready to merge): `LambdaMartTraining.loadOrTrain`
now trains the first model **directly from the deterministic triple path** via a new
`startAsyncFromTriples()`, instead of routing through the bootstrap callback whose GPL-coordinator
field is null at that point. `startAsync` delegates to the same helper; the unused `trainAsync` param
is dropped (only `OrchestrationPhase` called it). **Verified:** new `LambdaMartTrainingTest` (2 tests,
green) pins that `loadOrTrain` trains+adopts a model from existing triples when enabled and is a no-op
when disabled; full `./gradlew build -x test` green (PMD/spotless/integrationTest). So the *first-model
chicken-and-egg* now only requires the flag (`JUSTSEARCH_LAMBDAMART_ENABLED=true`) — no coordinator-timing trap.

**SciFact headroom number — still not run** (the long part of the task). With the fix in place the clean
recipe is: fresh stack from this branch + flag + LLM + ingest SciFact → GPL runs → model trains via the
fixed path → `--lambdamart` A/B vs the ~0.72 baseline. Deferred as a dedicated ~30–60 min GPL run rather
than rabbit-holed inline; the bug fix (the bounded, verifiable half) is the shipped deliverable. CourtListener
live-validation was abandoned mid-session because `.dev-data`'s triples drifted to 96 lines (partial run);
the unit test is the authoritative verification.

### 12.8 Headroom RESULT (2026-06-13) — the activated learned layer is NEUTRAL on the proxy corpus

Ran the full loop end-to-end on a provisioned main backend (flag on, LLM up). **Corpus: `cord19-qddf`
as a tractable SciFact proxy** — SciFact-full GPL is ~2+ hrs because **GPL has no doc-sampling cap**
(it iterates the whole corpus; itself a finding — see observations). cord19 (1000 docs, baseline ~0.40)
has real headroom unlike CourtListener's 0.976 ceiling, but only **48 queries (low statistical power)**.

**The full activation loop WORKS** (the headline positive): GPL auto-fired on the stable index →
**12,234 triples / 1,015 docs in ~8 min** → completion callback trained LambdaMART (flag on) → model
loaded (internal synthetic NDCG@10=0.819, train=1609/eval=402 groups) → fires in hybrid mode. So with
the flag set, the GPL→train→activate path runs clean (no fix even needed — the *callback* path, not the
fixed restart path, trains here).

**But the activated model is NEUTRAL on real queries** (the headline finding), clean A/B, same index:

| cord19 `hybrid` | nDCG@10 | P@1 | R@10 | LambdaMART |
|---|---|---|---|---|
| **with model** (trained, `lambdamart=True` in legs) | 0.4429 | 0.6042 | 0.2875 | fires |
| **no model** (restart, flag off, "disabled via config") | 0.4429 | 0.6042 | 0.2875 | off |

**Identical to 4 decimals** across all three metrics. The 2-feature (sparse+vector) GPL-synthetic-trained
reranker activates and fires but **does not change the ranking** on cord19. (`rule:interrogate-results`
notes en route: `full` mode never invokes rerankers — only `hybrid` does; `--no-lambdamart` doesn't
disable a loaded model in server-resolved hybrid, so the no-model arm required a flag-off restart.)

**What this means for §10's direction.** The residual risk "does the synthetic-label training actually
help?" resolves, on this proxy, to **no — it's neutral.** This is concrete evidence for the §10.1 "four
walls": the *2-feature, GPL-synthetic* learned layer as currently built does not move real-query quality.
⟹ The value (if any) is in **234's V2 richer feature schema + real feedback**, NOT in merely activating
the existing 2-feature model. **Re-rated: general recipe system 6 → 5/10** — the loop works and the
bootstrap bug is fixed, but the *current* learned layer is empirically neutral; the open lever is feature
enrichment, unmeasured. Caveats: single proxy corpus, 48 queries; a full SciFact / multi-corpus run with
the V2 schema is the proper follow-up.

### 12.9 CORRECTION (2026-06-15) — §12.8 was wrong; GPL→LambdaMART was already measured to HURT

User skepticism ("quality not changing with/without reranking makes no sense") was right, and chasing it
overturned §12.8 on two counts. Both claims below are verified against source (not a subagent reading).

**(a) It wasn't "neutral" — the reranker had ZERO effect (a degenerate no-op).** The with-model and
no-model `hybrid_run.trec` files are **byte-identical** (`diff` = 0 lines). Code-verified: LambdaMART
*does* execute in hybrid (`KnowledgeSearchEngine.java:531-574` — it checks `pipelineConfig.lambdamartEnabled()`,
reranks at :556, reassigns at :560), so it ran. Byte-identical output ⟹ the cord19 model produced an
**order-preserving (degenerate) result** — the LightGBM training warning *"no meaningful features which
satisfy the provided configuration"* is the tell. So §12.8's "the activated layer is neutral" mis-stated a
*degenerate no-op model* as a *quality verdict*. I never actually measured "does reranking help."

**(b) The real finding is already documented — GPL→LambdaMART HURTS and was abandoned.** Tempdoc **245**
(verified verbatim) measured GPL-trained LambdaMART across three BEIR datasets and found it **consistently
degrades** nDCG: **SciFact −0.009, Arguana −0.10, NFCorpus −0.021** (`245-execution-log.md:61`); root cause
*"GPL synthetic queries don't transfer to real BEIR queries"* (`245:332`); verdict *"tested, hurts performance"*
(`245:242`), *"not viable without real user query data,"* *"GPL-LambdaMART may be fundamentally unrecoverable"*
(`245:1263`). My cord19 run is the same failure mode in its no-op form (degenerate model → 0 change) rather
than 245's reorder-and-hurt form — both confirm non-viability.

**What this does to §10's direction.** §10.1 leaned on **234**, which *predicted* LambdaMART would beat fixed
fusion *"when ≥500 labelled queries are available."* It MISSED **245**, which *measured* the GPL-synthetic
substitute and found it fails — because the data doesn't transfer (a **data** problem, not the *feature-count*
problem §12.8 hypothesized; 234's V2 richer schema does NOT fix synthetic-query distribution mismatch). So:
- The "general recipe system via GPL→LambdaMART" is **not** "under-activated, needs enrichment" — it is
  **empirically discredited for the cold-start (no-real-feedback) case** we are in. Re-rated **5 → 2/10.**
- Reconciling 234⊕245: LambdaMART *can* work with **≥500 real labelled queries**; the **GPL synthetic
  substitute is the part that fails**. That ties straight back to §10.1's cold-start wall — the missing piece
  is **real user feedback capture** (confirmed absent in §12.1), not features and not "activate the model."
- The §10.1 catalogue is corrected: GPL→LambdaMART is a *measured-failed* path (245), not a *promising-unbuilt*
  one. This is the **third `audit-without-test` miss this session** — the narrative was built on a partial
  234-only reading that omitted 245's counter-measurement. Lesson re-logged.

**Net:** the engine-relevance thread's headline is now: the learned-ranking system exists and the bootstrap
bug is fixed, but the *training-data* path (GPL synthetic) is a documented dead end for our cold-start
situation; any real lift requires **real user-feedback labels first** — a ship-then-learn dependency, not a
local code change.

## 13. Takeover critical-analysis pass (2026-06-15) — verification + the conflation that foreclosed a live lever

A fresh agent took the doc over with the brief: *verify the load-bearing claims, then think critically —
question assumptions, propose alternatives.* The doc self-flagged three `audit-without-test` misses, so
the pass started from source, not from the narrative. **No feature code written this pass** (the levers
are eval-gated and dev-stack-bound; the section ends with the user-owned direction call).

### 13.1 Load-bearing claims — re-verified against source (all hold)

| Claim (where) | Verdict | How verified |
|---|---|---|
| GPL→LambdaMART measured to HURT (§12.9 / F-021) | **SOUND** | `245-execution-log.md:61` table: `SPLADE+Dense+LambdaMART(trained)` = SciFact 0.693 (−0.009), Arguana 0.213 (−0.10), NFCorpus 0.316 (−0.021). Root cause + verdict verbatim in `245-search-quality-strategy.md:131-132,332-333,883-884` ("GPL synthetic queries don't transfer… not viable without real user query data… not recoverable"). The §12.9 line cites were to the *strategy* file, not the *execution-log*; substance exact. |
| No implicit-feedback capture exists (§12.1) | **SOUND** | `git grep` for click/open/dwell/relevance-label/qrel over `modules/**/*.java` → zero search-result-feedback sites. The one `DwellTime*` hit is a **Prometheus alert-rule** dwell state machine (`observability/rules/DwellTimeScheduler.java:11`), not result dwell. |
| Bootstrap fix is clean + ready (§12.7) | **SOUND, but branch is STALE** | Commit `8d2817d13` touches exactly the 3 LambdaMart files; well-scoped, documented, `LambdaMartTrainingTest` green. **But `worktree-lambdamart-bootstrap-fix` was cut before recent `main`** — `git diff main <branch>` shows it *removing* 223 lines of 580 / 125 of 581 / the 577–579 work. **Do not merge the branch**; cherry-pick `8d2817d13` (3 files) onto a fresh branch off HEAD. Disposition in §13.4. |
| FW-001 injection is one clean site (§9.1) | **SOUND + extended** | `SearchExecutor.runThreeWay()` builds `double[] weights` from `ResolvedConfig.HybridSearch` (`SearchExecutor.java:435-439`) → `HybridFusionUtils.fuseWithCC3` (`:449-458`). **New finding (§13.3):** the per-leg candidate lists *with raw scores* (`bm25Result/denseResult/spladeResult.hits()`, `:432-434`) are in scope *before* fusion. Corpus shape (`isLongCorpus()`) is **not** threaded to the executor — only `queryString` and the config weights are. |

The doc's headline verdicts survive verification. The freeze is real and the engine is healthy; GPL→LambdaMART
is a genuine dead end for cold-start. **The pass's one substantive disagreement is narrower and is below.**

### 13.2 🔶 The central critical finding — §12 foreclosed a live lever by CONFLATING two different designs

§10 opened a real question: *generalize* FW-001's binary switch into "a general recipe-weight system." §10.3
listed the axes, including pt 3 **"Source of values: hand-tuned → fit/learned from eval data (the register's
per-corpus optima are training signal; a small model/curve from features → weights)."** Then §12.1 "corrected"
the framing to *"the general recipe system already EXISTS and is WIRED = GPL→LambdaMART,"* and §12.8–12.9
discredited GPL→LambdaMART. The doc's net reads as **"the general recipe system is empirically discredited."**

**That inference does not hold — it equates two designs with opposite data requirements:**

| | GPL→LambdaMART (what §12 discredited) | Fit-a-fusion-weight-function (what §10.3 pt 3 named, never built) |
|---|---|---|
| **Output** | A per-**document** rerank score (learning-to-rank) | The 3 fusion **leg weights** (sparse/dense/splade) |
| **Training signal** | (query, doc, relevance) triples → **needs real clicks** (cold-start wall, F-021) | (features → optimal weights) pairs → **the register's per-corpus optima ARE the labels** |
| **Cold-start status** | Blocked — synthetic GPL labels don't transfer (245) | **Not blocked** — no user feedback needed; we already have the supervision |
| **Built?** | Yes, wired, measured-to-hurt | **No — named in §10.3, never evaluated** |

LambdaMART *subsumes* weight selection only in the sense that a 100-dimensional learned ranker could in
principle reproduce a 3-number fusion blend — but it pays the cold-start cost to do so. The 3-number blend
**does not need that cost.** So the discrediting of the learning-to-rank path says **nothing** about whether
a directly-fit fusion-weight function works. §12's "it already exists" collapsed the cheap label-free lever
into the expensive label-blocked one and buried it. **This is the same `audit-without-test` shape the doc
warns about, one level up:** a *reading*-driven equivalence ("general recipe system = the wired LambdaMART")
that a source check separates back into two things.

### 13.3 The distinct lever this exposes — result-set-feature-adaptive fusion weighting

The proven fact (§9.3, re-confirmed): **the optimal fusion recipe flips by corpus** — CourtListener (long
legal) bm25-dom **+25.7%**, SciFact (short academic) balanced +1.8%. That flip is large where it's large.
The binary `isLongCorpus()` switch (§4b) was demoted (§10) for three correct reasons, the load-bearing one
being **whole-index granularity**: JustSearch's actual product is a *single mixed personal corpus* (email +
PDF + code + docs, multilingual), so a corpus-**average** statistic is structurally near-useless — §9.3's own
residual flagged this ("whether a real personal index ever trips `isLongCorpus()` is untested"). A whole-index
switch on a mixed index always reads "mixed."

**The fix the conflation hid:** select weights **per query / per retrieved result-set**, from cheap features
of the candidates already in hand — *not* a whole-index average, *not* a learned per-doc reranker, *not* an
LLM call. Concretely, at `SearchExecutor.runThreeWay()` the three per-leg hit lists (with scores + each
candidate's `PARENT_TOKEN_COUNT`) are in scope *before* `fuseWithCC3` (§13.1 row 4), so a selector can read:
- the **length distribution of the union of top-k candidates** for *this* query (does this query pull long
  docs or short ones?) — the per-query analogue of the corpus regime, the thing a whole-index switch can't see;
- **per-leg score dispersion / gap** (a leg whose top scores are bunched/flat is contributing little signal);
- **cross-leg rank overlap** (legs that agree vs. disagree on the candidate set).

A *low-dimensional function* from those features → `(w_sparse, w_dense, w_splade)`, **fit** on the register's
per-corpus optima (and a fresh per-query sweep), is the §10.3-pt-3 design realized. It captures the proven
cross-regime win **per query on a mixed index**, with **zero user-feedback dependency** and **no reindex**.

**Honest prior-art reconciliation (this is NOT novel, and the doc's record needs correcting both ways):**
- Per-query fusion-weight adaptation is *established* in the literature — **DAT** (arXiv 2503.23013),
  **Dynamic Weighted RRF**, **DIME** (SIGIR'24, +11.5% nDCG via per-query denoising). My proposal is a *cheap-
  feature* member of that family, not an invention. Claiming novelty would be wrong.
- **309 §31 already rejected "per-query alpha tuning (DAT-style)"** (`309:554`) — but explicitly because *DAT
  uses an LLM call per query* ("impractical for interactive search"). That rejection is of a **mechanism**
  (LLM-per-query), not of the **idea**. A statistical feature selector at <0.1 ms is the mechanism 309 did not
  evaluate. **The doc should not treat 309 §31 as having foreclosed this** — it foreclosed a costlier cousin.
- **Counter-evidence I must carry (lowers confidence, does not foreclose):** Bruch et al. (TOIS 2024, `309:506`)
  found CC has a **flat optimum within ±10% alpha** for *static* queries → small per-query nudges buy little.
  309 §6's broader skepticism: **collection identity dominates (F≈137.6)** and QPP routing signals cap **~4%
  nDCG**. These are real cautions — but they describe *local* sensitivity and *QPP-style* signals, whereas
  §9.3's flip is a *global, ±70%-magnitude* weight change driven by *length*, not QPP. Locally-flat and
  globally-very-different are compatible; the lever's value is routing between the two global regimes per query
  on a mixed index, which the flat-optimum result doesn't speak to.

**The decisive, cheap experiment (kills or ships it in ~1 day, no new substrate):**
1. Build a **mixed eval corpus** that actually contains both regimes (e.g. CourtListener-long ⊕ SciFact-short
   interleaved, or `desktop-mixed-v1`), so per-query routing has something to route.
2. Implement a *throwaway* per-query length-gated weight pick at the §13.1-row-4 site (top-k median token
   count → {bm25-dom, balanced}) — the binary switch moved from corpus-level to **query-level**.
3. jseval A/B vs (a) static balanced and (b) the §4b *corpus-level* binary switch, on the mixed corpus + ≥2
   homogeneous corpora (regression guard, per `rule:interrogate-results` — confirm the delta is the routing,
   not a reindex/config artifact; the `search_config` echo is unreliable per §9.1, read the nDCG).
- **Decision rule:** if query-level routing beats *both* baselines on the mixed corpus without hurting the
  homogeneous ones → promote to a fit continuous function (§10.3 pt 3) as the FW-001 successor. If it can't beat
  static balanced → the lever is dead for our corpora and the doc closes it *with a measurement* (not by
  conflation), finally settling §10 honestly.

**Confidence: 5/10** — deliberately mid. Mechanically clean and label-free (the two things that sank
LambdaMART don't apply); premise (the flip) is measured and large; but the flat-optimum + collection-dominance
literature is a genuine headwind, and whether *cheap features* pick weights as well as an *oracle corpus label*
is exactly the unmeasured thing. That uncertainty is *why it's worth the one cheap run* — it is the only engine
lever on the board that is both label-free and unmeasured. Everything else is either measured-dead (LambdaMART)
or measured-real-but-heavy (extraction).

### 13.4 Bootstrap-fix disposition — recommend PARK, not merge (subject to user call)

The fix (`8d2817d13`) is correct, tested, and isolated. But F-021 establishes the layer it makes trainable is
**non-viable for our cold-start situation**, and it is disabled-by-default (no model file → no-op). So merging
it onto `main` buys **near-zero current value** and adds maintained surface for a *parked* subsystem; its real
value is latent (it unblocks first-model training *if* real-feedback capture ever lands and the layer is
re-enabled). The honest dispositions, in preference order:
1. **PARK (recommended):** keep `8d2817d13` on its branch; record here that the bootstrap path is fixed-but-parked,
   so no one re-discovers the §12.6 dead path. Re-attach when/if §13.5's feedback-capture thread is funded.
2. **Hygiene cherry-pick:** if we'd rather not carry known-dead code on `main` at all, cherry-pick the 3 files
   onto a fresh branch off HEAD (the stale branch must NOT be merged — §13.1 row 3) and merge as a pure
   correctness fix, explicitly *not* a quality change. Defensible (a silent no-op bootstrap path is a latent trap).
- Either way: **never merge `worktree-lambdamart-bootstrap-fix` as-is** — it would revert recent `main` work.

### 13.5 Re-ranked live levers (after this pass) + the one structural deliverable worth its own scope

Ranked by (label-free? × measured-headroom × ÷ cost). Only the first two are *engine-relevance*; the rest are
the doc's existing threads, re-stated with current confidence.

| # | Lever | Label-free? | Headroom | Cost | Status |
|---|---|---|---|---|---|
| 1 | **Result-set-adaptive fusion weighting (§13.3)** | ✅ | proven flip (±25%), per-query realization **unmeasured** | ~1 day eval | the one cheap unmeasured engine lever |
| 2 | **F-009 extraction (OCR-VLM)** (§7.2/§12.4) | ✅ | highest ceiling (−15…−33% nDCG tax) | heavy — no pluggable-extractor abstraction (`VduProcessor`), VLM-coupled | eval-gated, real |
| 3 | **Tier 0 BEIR reproducibility** (§11.5/§12.3) | ✅ | n/a (credibility, not nDCG) | near-zero — already on-axis (`ir_datasets`+`ir-measures`) | green, mechanical |
| 4 | **FW-002 spell correction** | ✅ (index-derived; survives 581's invariant) | unmeasured; UX-real for typo'd personal queries | ~100 lines | unbuilt (register-verified 2026-06-15) |
| 5 | **GPL→LambdaMART** | ❌ needs real clicks | measured-NEGATIVE (F-021) | — | **dead until feedback capture** |

**The structural deliverable the doc keeps circling but never names as buildable now: implicit-feedback
capture.** Every learned path (real LambdaMART, any future learned weight fit beyond eval corpora) is gated on
(query → which result the user opened/dwelled-on). That capture **has no quality risk and no model dependency**
— it is pure instrumentation (log `(queryId, shownDocIds, openedDocId, rank, dwell)` at the result surface +
Worker). The doc treats "real user feedback" as an external *dependency*; it is in fact a **local code
deliverable** we could ship now so labels *accumulate* against the day a learned layer becomes viable. It does
not belong in *this* doc's scope (it's a new feature, not a 580 finding), but 580's honest closing should say:
*the single highest-leverage engine investment is not a model — it is the feedback pipe that every model needs,
and it is buildable today.* (Recorded as a candidate; see §13.6 for the user decision.)

### 13.6 Where this leaves 580 — the open direction call

The doc's map of dead ends is now verified and one *non*-dead lever was recovered from a conflation. What
remains is genuinely the user's to steer (each is a separate next move, not all-of-the-above):
- **(A)** Run the §13.3 result-set-adaptive fusion experiment — the cheap, label-free, unmeasured engine lever.
- **(B)** Scope the F-009 OCR-VLM extraction pilot — highest ceiling, heaviest, eval-gated.
- **(C)** Stand up implicit-feedback capture (§13.5) — the prerequisite that unblocks every learned path; new feature, new tempdoc.
- **(D)** Treat 580 as landed (verified finding + map of dead ends + F-021 + the parked bootstrap fix) and only do the register/disposition bookkeeping.

**User steer (2026-06-15):** keep 580 **open** and *document the direction further*; on the bootstrap fix, *investigate further* before disposing. §13.7 records that deeper investigation.

### 13.7 Bootstrap-fix deep dive — the learned layer is *structurally* capped, not just label-starved

Per the steer, I read the LambdaMART V1 substrate end-to-end (feature schema, trainer, reranker, the
`KnowledgeSearchEngine` call site, the GPL triple store) rather than trusting F-021's one-line framing. The
result **sharpens F-021** and **settles the fix disposition** — and one tempting "I found a bug" was checked
and **withdrawn** (the discipline the doc keeps demanding of itself).

**Finding 1 — the V1 reranker is informationally *poorer* than the fusion it post-processes (code-proven).**
The whole feature vector is `[sparse, vector]` — 2 numbers (`LambdaMartFeatureSchema.java:9-11,24`). At the
call site (`KnowledgeSearchEngine.java:544-560`) the reranker runs on the **already-fused** result list and
reads each hit's `sparse-retrieval` and `dense-retrieval` stage scores (`:553-554`) — i.e. *the very leg
scores CC fusion already consumed*. Worse, the "sparse" slot is "SPLADE when available, BM25 otherwise"
(`LambdaMartFeatureSchema.java:9`), so in a 3-way (BM25+SPLADE+dense) search the reranker sees **two** of the
three legs, collapsing BM25 and SPLADE into one. Consequences:
- The model **cannot add a signal fusion lacked.** It can at most learn a *non-linear* re-blend of a
  *subset* of fusion's own inputs. CC fusion is already a (tuned) linear blend of all three; a tree model over
  two-of-three normalized scores has almost nothing to contribute.
- With min-max normalization wiping absolute scale and a near-monotone score→relevance relationship, the
  learned function is near **order-preserving** → the degenerate/byte-identical output §12.9 observed is the
  *expected* outcome, not an anomaly.
- **This is a ceiling independent of label quality.** Even with 500+ real-click labels, a reranker whose only
  features are fusion's own leg scores has near-zero headroom *over fusion*. Learning-to-rank earns its keep
  from features the first-stage ranker can't see (query–doc interaction, BM25F per-field scores, doc
  length/freshness/metadata, click priors) — V1 has **none** of those.

**Finding 2 — F-021's cure is necessary but NOT sufficient (the refinement).** F-021 frames the failure as
"GPL synthetic queries don't transfer" and the cure as "real user-feedback labels first." True but incomplete:
real labels on the *same 2 fusion-redundant features* would **still** near-certainly fail to beat fusion
(Finding 1). The honest prerequisites for any learned-rank lift here are **both** (a) a feature set richer than
fusion's own scores (the 234 "V2 schema" direction) **and** (b) real labels. The substrate has neither. So
"capture feedback → re-activate the existing model" is a **trap** — it would satisfy (b) and still lose to (a).
*Register F-021 updated with this caveat so the next agent doesn't walk it.*

**Finding 3 — a tempting normalization-skew "bug" was investigated and WITHDRAWN.** The trainer stores *raw*
scores in the triple (`GplTrainingTripleStore.java:32` — `"sparse":12.4,"vector":0.73`) and the reranker
min-max-normalizes at inference (`LambdaMartReranker.java:294-295`), which *looked* like a train/serve scale
skew that would neutralize the sparse feature. **It is not a bug:** the trainer normalizes too —
`buildDataset` calls `normalizeGroupedFeatures` (`LambdaMartTrainer.java:217`), which applies the *same*
`LambdaMartFeatureSchema.normalize` per query-group (`:261`); offline eval normalizes identically
(`:292-293`). Training (per-group) and serving (per-result-set) use the same min-max formula → consistent.
Logged as a checked-and-cleared hypothesis so it isn't re-chased (the byte-identical cord19 result is most
simply the LightGBM zero-split *constant* model from tiny synthetic data — the "no meaningful features"
warning — not a scale skew).

**Disposition — PARK, confirmed and strengthened (was the §13.4 recommendation; now load-bearing).** The
bootstrap fix (`8d2817d13`) correctly unblocks first-model *training*, but Finding 1 shows the model it makes
trainable is **structurally capped below fusion** regardless of labels. So:
- Merging it as a **quality** change would be wrong (it improves nothing — doubly so: redundant features *and*
  no real labels).
- Merging it as pure **hygiene** (kill a silent no-op bootstrap path) is the only defensible merge, and even
  that is low-value while the whole layer is parked. **Recommended: PARK** — keep `8d2817d13` on its branch,
  recorded here, re-attach only as part of a *rebuild* (rich V2 features + a real-label/feedback pipe), not as
  a standalone "activate LambdaMART" step. **Never merge the stale `worktree-lambdamart-bootstrap-fix` branch
  as-is** (§13.1 row 3).

**What this does to §13.3 (it gets *stronger*).** Findings 1–2 are the clearest argument yet *for* the §13.3
lever. Both V1-LambdaMART and §13.3 chase the same goal — *combine the retrieval legs better* — but:

| | V1 LambdaMART (parked) | §13.3 result-set-adaptive fusion weights |
|---|---|---|
| New information over fusion? | **No** — features ARE fusion's own scores | **Yes** — per-query length distribution / score dispersion fusion never sees |
| Needs real labels? | **Yes** (and still capped without rich features) | **No** — fit on the register's per-corpus optima |
| Where it acts | per-doc rerank *after* fusion (redundant) | sets the fusion *weights* (additive, upstream) |
| Cost | LLM/GPL pipeline + training + serving model | a low-dim function at one existing site |

V1 tried to out-rank fusion using *less* than fusion had; §13.3 improves fusion by feeding it *more* than it
currently has, for free. **The learned-layer dead end and the §13.3 opportunity are the same insight read two
ways** — which is exactly why §12's conflation (treating "the wired LambdaMART" as the whole "general recipe
system") was so costly: it let the *failure* of the redundant-feature approach masquerade as a verdict on the
*additive-feature* approach that was never built.

**Net for 580 (open):** the engine-relevance map is now complete and verified — every *internal learned* path
is capped by missing-information (Finding 1) or missing-labels (F-021) or both; the one *un-walked* internal
lever is §13.3 (additive features, label-free); the one *external* high-ceiling lever is F-009 extraction. The
doc stays open pending the user's pick among §13.6 (A)–(D); nothing further is built until then.

### 13.8 Executable design sketch — result-set-adaptive fusion weighting (the §13.3 lever, buildable)

Per the steer to *document the direction further*, this turns §13.3 into a spec an implementer can pick up. It
is a **design, not a build** (no code shipped this pass). Kept deliberately small — the whole point is a
low-dimensional, auditable function, not another learned subsystem.

**One-line statement.** Replace the single config-constant weight vector with `w = f(φ(resultsets))`, where
`φ` is a handful of cheap features of *this query's* retrieved candidates and `f` is a low-dimensional function
**fit offline** on the register's per-corpus optima. Inject at the one site (`SearchExecutor.runThreeWay`,
`:435-439`), before `fuseWithCC3`.

**The features `φ` (all computable from the in-scope per-leg hit lists `bm25Result/denseResult/spladeResult.hits()`, no new IO):**
| Feature | Definition | Why |
|---|---|---|
| `lenMedian` | median `PARENT_TOKEN_COUNT` over the union of the top-k candidates of all legs | the **per-query** analogue of the corpus length-regime — the signal a whole-index `isLongCorpus()` can't see on a mixed index; carries the proven §9.3 win |
| `lenSpread` | IQR (or p90/p10) of that same token-count set | distinguishes a query that pulled a *uniformly* long set from a *mixed* one (routing matters more when mixed) |
| `dispΔ(leg)` | per-leg top-k score dispersion, e.g. `(s₁ − s_k)/s₁` for each leg | a **flat** leg (low dispersion) is contributing little ranking signal → down-weight it; a **peaked** leg is confident → up-weight |
| `overlap` | Jaccard of the top-k doc-id sets across legs | high overlap ⇒ legs agree ⇒ weights barely matter (skip adaptation); low overlap ⇒ weights are decisive |

(`queryLen` is also free in scope but Q-002 is unmeasured, so leave it out of v1 to avoid an unvalidated axis.)

**The function `f` (start interpretable, escalate only if needed):**
- **v0 — per-query binary switch (the throwaway probe, ~1 day).** `lenMedian > θ → bm25-dom (0.60/0.20/0.20)
  else balanced (0.34/0.33/0.33)`. This is *literally the §4b switch moved from corpus granularity to query
  granularity* — the cheapest possible test of "does per-query routing beat a whole-index average on a mixed
  corpus." Costs one `if` at the injection site.
- **v1 — fitted low-dim map (only if v0 wins).** A 2–3-parameter monotone map `lenMedian → bm25Weight`
  (logistic or piecewise-linear), with `dispΔ`/`overlap` as multiplicative nudges, fit by grid search on
  (φ → best-weights) pairs. 3 outputs, ≤4 inputs ⇒ a *tiny* fit, auditable, no model artifact to serve.

**Fit data (label-free — this is the crux vs F-021):** the supervision already exists. (a) The register's
per-corpus optima (SciFact balanced, CourtListener bm25-dom, MIRACL balanced, EnronQA bm25_splade…) give
(corpus-φ → best-w) anchors. (b) A fresh **per-query weight sweep** on 2–3 corpora yields (query-φ → best-w)
pairs at query granularity. No clicks, no GPL, no synthetic queries — the eval harness is the oracle.

**Eval protocol (the kill/ship gate, per `rule:interrogate-results`):**
1. Build a genuinely **mixed-regime** eval corpus (CourtListener-long ⊕ SciFact-short interleaved, or use
   `desktop-mixed-v1`) so per-query routing has both regimes to route between.
2. A/B v0 vs **two** baselines: static-balanced (current default) **and** the §4b corpus-level binary switch,
   on the mixed corpus + ≥2 homogeneous corpora as regression guards.
3. Read the **nDCG delta**, not the `search_config` echo (unreliable, §9.1). Confirm the delta is the routing,
   not a reindex/config artifact (re-run the baseline arm on the same index).

**Decision rule / kill-criteria (write the verdict either way — close §10 *with a measurement*):**
- v0 **beats both** baselines on mixed *without* hurting the homogeneous guards → promote to v1 (fitted map) as
  the FW-001 successor; record a new register Decision superseding D-002's binary path.
- v0 **can't beat static-balanced** → the lever is dead for our corpora; close it in the register with the
  number (not by conflation, as §12 did). ~1 day spent, §10 finally settled honestly.
- v0 beats the *corpus* switch but not static-balanced → per-query granularity is the wrong axis; fall back and
  reconsider F-009/feedback-capture as the only live levers.

**Honest risk register (carry these, they cap confidence at 5/10):** (a) Bruch flat-optimum — small per-query
nudges may buy little; mitigated by routing between *global* regimes, not nudging locally. (b) Overfit to the
handful of register corpora — mitigated by the homogeneous regression guards + holding `queryLen` out. (c)
`lenMedian` noisy on small/short result sets — guard with `overlap` (skip adaptation when legs already agree).
(d) latency — negligible (top-k arithmetic, <0.1 ms). **None of these is the GPL cold-start wall or the
Finding-1 redundancy ceiling — which is the whole reason this lever is worth the one cheap run the learned
paths didn't earn.**

## 14. Post-cutoff external scan (2026-06-15) — the Feb–Jun 2026 window

A targeted external scan for developments **after the assistant knowledge cutoff (Jan 2026)** — window
**Feb–Jun 2026** — over exactly the techniques 580 already analyzed. Method per the §8 lesson: five grounded,
**exclusion-aware** research agents (each briefed with our live config + our dead-ends so they didn't re-pitch
EmbeddingGemma / GPL-LambdaMART / per-language levers), **then** my own primary-source verification of the
decision-relevant claims (agent leaderboard numbers are leads, not facts; abstention ≠ refutation). This
*replaces* §7's coarser scan for the in-window slice and corrects three of its forward-looking claims.

### 14.1 The four decision-relevant deltas — I verified each against a primary source

| Delta | Date | What it is | Verified (primary source) | What it changes |
|---|---|---|---|---|
| **Lucene 2-bit / asymmetric scalar quant** | Lucene 10.4.0, Feb 2026 | `Lucene104(Hnsw)ScalarQuantizedVectorsFormat` supports **1/2/4/7/8-bit** + **2-bit-store / 4-bit-query asymmetric**; "2-bit achieves better recall than older formats at the 4-bit level" | ✅ Lucene 10.4.0 ANNOUNCE / Changes.html | **Corrects §9.4** ("no explicit 2-bit"). We run this format at **7-bit** — dropping to 2-bit-store/4-bit-query is a **config-only** change (no new dep), ~3.5× further vector-memory cut, reindex required, **eval-gated** (recall is corpus-dependent). An *efficiency* lever, not a quality one — frame honestly (no nDCG upside; possible downside). |
| **PaddleOCR-VL-1.6** | 2026-05-28 | ~1.0B, **Apache-2.0**, ships **GGUF/llama.cpp/Ollama** quants, multilingual; claims **96.33 OmniDocBench v1.6 SOTA** | ✅ HF model card (date/size/license/llama.cpp/score confirmed; 96.33 is **self-reported**) | **F-009 gets a concrete, verified, low-integration-risk drop-in** over our Qwen-VL extraction path (same local-runner family). Pilot candidate. |
| **InduOCRBench — "When Good OCR Is Not Enough"** | 2026-04-29 (arXiv 2605.00911) | OHR-Bench-successor: benchmarks OCR **robustness for RAG** over 11 industrial doc types | ✅ arXiv abstract — verbatim: *"high OCR accuracy does not necessarily translate into strong downstream RAG performance; structural and semantic errors can cause substantial retrieval failures even when WER/CER remains low"* | **Validates F-009** (extraction is the bottleneck) **and sharpens it**: OmniDocBench char-accuracy is a **poor proxy** for our nDCG. Adopt a retrieval-aware eval lens, not the raw OCR leaderboard, when piloting PaddleOCR-VL-1.6. |
| **ONNX Runtime 1.26.0** | ~May 2026 | CUDA plugin-EP **graph capture/replay + profiling**; "CUDA 12 will be removed in 1.27.0" | ✅ GitHub release tag v1.26.0 (content confirmed; WebFetch mis-stamped the *year*, a known quirk) | **Confirms §7.4** ("1.26 is a real forward bump"). We're on 1.24.3. Caveat: 1.25 dropped **CUDA 11**; 1.27 will drop **CUDA 12** — gate any upgrade on toolkit compatibility. XLM-R **encoder** speedup **unverified** (in-window attention work is generative/GQA-skewed; F-005 status still answerable only by a direct benchmark). |

### 14.2 Corrections to earlier sections of this doc (the scan overturned three forward-looking claims)

1. **§9.4 / §7.1 "no explicit 2-bit" — WRONG.** Lucene 10.4 *does* expose 1/2/4/7/8-bit + asymmetric (14.1).
   The §9.4 "ScalarEncoding enum, no numeric bits" framing mis-read the format. The 7-bit→2-bit lever is real,
   config-only, reindex-gated. (Primary-source verified — not a rate-limit abstention this time.)
2. **§7.5 "ColBERT late-interaction — and whether Lucene exposes it" — RESOLVED.** Lucene **`LateInteractionField`**
   (native multi-vector + maxSim rescoring, ColBERT/ColPali-style) shipped in **10.3.0 (Sept 2025, *pre*-window)**;
   we inherit it on 10.4.0. The integration *blocker* the doc flagged is **lifted**. (But adoption is still a
   heavy lift — a ColBERT model + multi-vector indexing + the storage cost; not a config flip. In-window
   activity is academic: Col-Bandit query-pruning (Jun 2026), the ECIR-2026 late-interaction workshop.)
3. **§7.4 ORT 1.26 — CONFIRMED shipped** (14.1), with the CUDA-version-deprecation caveat the doc didn't have.

### 14.3 The §13.3 lever — the scan STRENGTHENS it (the null is the finding)

The most important in-window result for our live direction is a **clean null**: **no new label-free,
per-query, cheap-feature hybrid-fusion-weight method shipped Feb–Jun 2026.** The field still cites DAT (Mar
2025) and Dynamic-Weighted-RRF (Feb 2025) — both *pre-window*. So §13.3 is **not behind a 2026 SOTA**; the
niche is genuinely open. Two cheap, **label-free, no-LLM** leads to fold into the §13.8 design (both flagged
*lead, not fact* — single-team arXiv, unreproduced):
- **PIT / percentile-rank score normalization** (PhaseGraph, arXiv 2603.28886, Mar 2026) — a probability-
  integral-transform alternative to our min-max normalization for combining heterogeneous BM25/dense/SPLADE
  scores. A concrete, cheap normalization swap to A/B alongside §13.8's weight selection.
- **Self-consistency / cross-retriever agreement as a per-query effectiveness signal** (arXiv 2606.03535, Jun
  2026) — a training-free per-query confidence estimate; portable to "do the legs agree on this query?" as a
  §13.8 feature (it's our `overlap` feature, with literature support).
- **QPP-as-fusion-priors >4.5%** (ECIR 2026, arXiv 2601.17339) — a *lead with a caveat*: the gain is over
  *unweighted* CombSUM/RRF, **not** a tuned CC, and the arXiv v1 is Jan-2026 (pre-window). Do **not** read it
  as "the ~4% QPP ceiling is broken." Watch, don't bank.
- Recurring in-window theme (multiple papers): **fusion gains are corpus-dominated** — reinforces §13.8's
  insistence on validating on *our* mixed corpus, not a public benchmark.

### 14.4 F-009 extraction — the highest-ceiling lever got a verified drop-in + a sharper eval lens

The scan's strongest *actionable* result outside §13.3. PaddleOCR-VL-1.6 (14.1) is a verified, Apache-licensed,
~1B, llama.cpp-runnable drop-in over our existing VLM extraction — the lowest-integration-cost extraction
upgrade available, and extraction is our single largest measured quality bottleneck (F-009: −15…−33% nDCG).
**MinerU2.5-Pro** (Apr 2026, 1.2B, Apache, claims 95.69) is the table-heavy A/B challenger. **GLM-OCR** (~0.9B,
MIT) is the only one with a *third-party-verified* OmniDocBench #1 — but on the older v1.5 and local-packaging
unconfirmed. **Critical pairing:** pilot any of these **gated on an InduOCRBench-style retrieval-aware eval**,
not the self-reported 96.33 — the in-window evidence (2605.00911) is that OCR score ≠ downstream nDCG.
*Caveat from §9.4 stands:* extraction is hardcoded to Qwen-VL via llama-server with **no pluggable-extractor
abstraction** — a swap is a subprocess/model change, feasible but real work.

### 14.5 Scanned and does NOT move us (honest nulls + exclusions confirmed)

- **Dense embeddings:** the only in-window candidate is **Harrier-OSS-v1-0.6b** (Mar 2026, MIT, Qwen3-based →
  dodges the Gemma FP16 lineage, 1024-dim, 94 lang) — but its "#1 multilingual MTEB-v2" is **vendor-only**,
  **ONNX export unconfirmed**, and a swap = **full reindex**. **Jina-v5** (Feb 2026) is technically strong +
  ships ONNX but is **CC-BY-NC (non-commercial)** → license blocker. **EmbeddingGemma stays excluded:** the
  root-cause **ORT fp16→NaN bug (#26732) is still open, no 2026 fix** — primary-source-verified *negative*.
  None clears the reindex bar on verified evidence. (581 invariant: all are single multilingual models = bucket
  C, no per-language conflict — but feasibility, not the invariant, is what gates them out.)
- **Rerankers:** no in-window open multilingual ONNX cross-encoder beats `gte-multilingual-reranker-base`.
  Bonus: **"Scaling Laws for Cross-Encoder Reranking"** (arXiv 2603.04816, Apr 2026) independently corroborates
  our F-002/F-008 (CE is neutral-to-helpful but *hurts some domains* incl. email) — our local measurement is
  now a published finding. Keep gte.
- **Learned-sparse (SPLADE):** no new *production* opensearch-neural-sparse in-window. **Milco** (arXiv
  2510.00671 v2, Mar 2026; 560M multilingual learned-sparse, **weights released**) is a research-grade
  successor worth *tracking* for a future multilingual-SPLADE upgrade. **SPLARE** (Feb 2026) is SAE-sparse —
  **not** Lucene-inverted-index compatible; track only.
- **Lucene / ANN:** **10.4.0 is still the latest** (no 10.5/11 in-window — verified by absence of any RC vote).
  ACORN-1 filtered-HNSW is already inherited (pre-window). RaBitQ/BBQ remain Elastic/external, not Lucene-core.
  No ANN action.

### 14.6 Re-ranked levers after the scan (supersedes §13.5's table for the external slice)

| # | Lever | Quality or efficiency? | New in-window evidence | Status |
|---|---|---|---|---|
| 1 | **§13.3/§13.8 result-set-adaptive fusion weighting** | quality | niche confirmed **open** (no 2026 competitor); +PIT-normalization & self-consistency leads | the cheap label-free internal lever; **most actionable** |
| 2 | **F-009 PaddleOCR-VL-1.6 extraction pilot** | quality (highest ceiling) | verified drop-in (Apache, llama.cpp); InduOCRBench retrieval-aware eval lens | now **concretely actionable** (a named model + an eval) |
| 3 | **Lucene 7-bit→2-bit asymmetric quant** | **efficiency only** | verified: 2-bit-store/4-bit-query, recall-competitive w/ old 4-bit | config-only, reindex+eval-gated; **no nDCG upside** — memory lever |
| 4 | **ORT 1.24.3→1.26 upgrade** | infra | verified shipped (CUDA-EP graph) | gate on CUDA-12 toolkit; encoder speedup **unverified** |
| — | embeddings / rerankers / SPLADE swaps | quality | **no verified in-window winner** | hold; track Harrier-0.6b, Milco |

### 14.7 Verification honesty (the §8 discipline, applied)

- **Primary-source verified (4/4 decision-relevant):** Lucene 2-bit/asymmetric quant, PaddleOCR-VL-1.6,
  InduOCRBench core claim, ORT 1.26.0 + CUDA-deprecation. These I'll stand behind.
- **Carried as agent-claim, NOT independently re-verified (flagged *lead*):** every leaderboard number (Harrier
  #1, Jina 71.7, Milco/SPLARE/PaddleOCR 96.33, the fusion-paper deltas) and the Scaling-Laws tables. Skeptic's
  rule: an unreproduced benchmark number is a lead.
- **The nulls are "didn't find in-window," not "doesn't exist"** (abstention ≠ refutation) — but the
  fusion-weighting null is corroborated across multiple survey/topic pages, so it's a *well-supported* null.
- **Register touch:** F-009 + FW-008 get terse pointers to this section (PaddleOCR-VL-1.6 / InduOCRBench;
  Lucene 2-bit). Findings themselves unchanged — these are *candidates/leads*, not new measurements.

## 15. Engine work-history map (2026-06-15) — what's been worked, and the untouched ranking surface

A git-history + tempdoc-title sweep (337 tempdocs; relevance-core commit log for 2026) to answer two
questions precisely: *what has engine work actually gone into*, and *which ranking levers have never been
touched*. This is the historical dimension the triage was missing — it explains **why** §13–§14's live levers
are the ones they are (they're the parts the 222→395 relevance era never reached or left dangling).

### 15.1 The reframe: "the engine" IS being worked on — but only its plumbing, never its ranking

The relevance-core modules (`adapters-lucene/runtime`, `app-services/worker`, `app-services/gpl`,
`worker-services/execute`, `reranker`) have **recent, heavy commit activity** (through 2026-06-11). But every
commit since ~late-April is **representation / structure / governance**, not ranking:

| Recent engine-core commit cluster | What it changed | Ranking touched? |
|---|---|---|
| **549** (May 25–27) — unified `SearchTrace`, retire flat fields/`debug_scores`/`HitProvenance` | how a ranking decision is **recorded** | ✗ |
| **553** (May 27) — OpenInference span projection | how execution is **traced** | ✗ |
| **556** (May 28) — split `KnowledgeHttpApiAdapter` → `SearchExecutor`/`SearchTraceMapper`/presets/mappers | how search code is **structured** | ✗ |
| **517** (May 18) — "search execution as a values-driven decision tree" | control-flow **shape** | ✗ |
| **554** (Jun 3) — value-law fixes incl. "fusion null-rank symmetry" | a fusion **correctness bug** (not a weight) | ✗ |
| **550** (May 28) — operation-surface ledger/teeth | **governance** | ✗ |

So "I haven't worked on the engine in months" is exactly right **for ranking** — and simultaneously the engine
*code* has been churned hard for *plumbing*. The freeze is specifically a **relevance** freeze (580's whole
thesis), now confirmed at the commit level: 0 of the recent engine commits move a fusion weight, reranker
model, SPLADE/BM25/dense parameter, or quantization setting.

### 15.2 What relevance work DID happen — the 222→395 era (~Feb–Apr 2026), now frozen

All actual ranking-quality work clusters in one ~2-month burst, then stops. By lever (tempdoc citations):

| Ranking lever | Worked in | Outcome |
|---|---|---|
| **Hybrid fusion** (CC vs RRF, 3-way) | 274, 280 (QDDF/chunk), 309 | CC default shipped (F-022); static weights `[0.35/0.35/0.30]` / balanced (D-002) |
| **Learned-sparse / SPLADE** | 266, 273, 319 (multiling. suppression), 322 (long-doc) | model swap to multilingual SPLADE; long-doc gap fixed by *model*, not weights |
| **Cross-encoder / reranker** | 317 (GTE-ModernBERT), 358, 359, 360 (→worker) | CE-model choice found quality-**neutral** (F-006); CE **hurts email** (F-002/F-008) |
| **Dense embeddings** | 268 (ONNX), 327, 343/358 (gte-multilingual stack) | gte-multilingual-base default; EmbeddingGemma tried+abandoned (F-012) |
| **Query understanding / classification** | 306 (classify+route), 363 (QU), 362 (facets), 385 (structured) | QU soft-boost safe+neutral (F-018); disabled by default (LLM contention) |
| **Entity / NER retrieval** | 326 | entity-boost **neutral-to-negative** (F-010); default off |
| **Chunk handling** | 280, 343 (branch diagnosis) | chunk-merge net-positive on long docs (F-014) |
| **Extraction / ingestion quality** | 252 (F-009), 346 (llama-server/VLM) | extraction = **largest bottleneck** (−15…−33%); VLM path chosen, not fully shipped |
| **Scoring calibration** | 277 | PHI reframing |
| **Eval infrastructure** | 216,224,225,230,235,246,251,255,258,259,267,308,315,346,369 | **mature** — BEIR/ir-measures, realistic-eval seed, shadow-eval |
| **LTR / GPL / LambdaMART** | 234, 245, 544 | built + wired, **measured to hurt** (F-021); cold-start dead end |
| **Adaptive routing (theory)** | 270, 309, 395 | **designed, never built** — policy tables/bandit stalled on the "four walls" (§10.1) |

**Read:** the relevance era was *thorough on models and eval* (every encoder swapped, eval lane built) but
left the **combination logic static** (fixed fusion weights, no adaptivity) and the **biggest bottleneck
half-addressed** (extraction VLM chosen but not shipped). That's the shape of the frozen surface.

### 15.3 The untouched ranking surface — levers with NO build (the actual gaps)

Cross-referenced against the register; each verified unbuilt this session:

| Untouched lever | Status | Where it stands |
|---|---|---|
| **Per-query / corpus-adaptive fusion weighting** | designed (270/309/395), **never built**; `isLongCorpus()` dangles (zero consumers) | the §13.3 successor — *the* cheap label-free gap |
| **Vector-quant quality** (Int8 / Lucene 2-bit) | wiring exists, **default Float32**; nDCG cost **never measured** (FW-008) | §14.1 efficiency lever, eval-gated |
| **Spell / typo correction** | **never built** — no `DirectSpellChecker`; only zero-hit fuzzy retry (FW-002) | survives 581's invariant; ~100 lines |
| **Implicit-feedback capture** (click/open/dwell) | **does not exist** (verified — `DwellTime*` is an alert-rule, not result dwell) | blocks *every* learned-ranking path; pure instrumentation |
| **Reranker domain-gating** (CE-off for email) | measured-needed (F-002/F-008), **never built** — no content-type signal (§9.2) | the descoped FW-001 CE-half |
| **Relevance ratchet** (engine quality gate) | **never built** (Q-010) — engine has *no* CI quality gate, unlike presentation | the §3 enforcement-asymmetry root cause |
| **Short-query eval** / **confidence calibration** | **never measured** (Q-002/FW-004; Q-007/Q-009) | measurement gaps |

**Net:** the relevance era exhausted *model swaps* and *eval infrastructure* but never touched **(a) adaptive
combination, (b) quantization quality, (c) typo handling, (d) feedback capture, (e) an engine quality gate.**
Those five are the untouched surface — and §13/§14 already independently converged on (a) [§13.3] and the
feedback/ratchet structural items [§13.5, Q-010] as the live moves, which this history corroborates: they're
live *because* they were the parts the 222→395 burst never reached.

### 15.4 The general-area investment map (the headline view; §15.1–15.3 are the detail)

Stepping up from levers to the **general functional areas every local neural search engine has**, and how much
each was worked in this repo. "Worked" = a judgment from dedicated-tempdoc density + module commit activity
(a proxy, not a precise metric). The decision-relevant column is the last one — **investment *vs* importance
to a local search engine's core job (ranking the right docs first)**:

| # | General area (any local search engine) | Worked in this repo | vs. importance | Evidence (tempdoc clusters / modules) |
|---|---|---|---|---|
| **INGEST PATH** | | | | |
| 1 | Acquisition & file watching | 🟧 Moderate | balanced | 334, 410, 418, 445 (job lifecycle), scan-progress |
| 2 | **Extraction & parsing (OCR/VLM)** | 🟨 **Light** | **UNDER** ⬅ | 252, 346 — *the largest measured quality bottleneck, half-addressed (VLM chosen, not shipped)* |
| 3 | Chunking & enrichment | 🟧 Moderate | balanced | 222, 280, 334, 343 |
| 4 | Index build & lifecycle | 🟥 Heavy | balanced | 278/303/310/312/320/324/334/402/406/516 |
| **QUERY PATH** | | | | |
| 5 | Query understanding (classify/expand/spell) | 🟧 Moderate | balanced (spell unbuilt) | 306, 362, 363, 385 |
| 6 | Retrieval / candidate generation (BM25·ANN·sparse) | 🟧 Moderate | balanced | within SPLADE/fusion work; ANN/HNSW just *inherited* from Lucene |
| 7 | **Fusion & ranking (combine · rerank · LTR · score)** | 🟧 Moderate, then **FROZEN** | **UNDER (adaptivity)** ⬅ | 274/280/309/317/358/359/360/277/234/245 — models swapped, **combination left static** |
| 8 | RAG / answer generation (context·cite·sufficiency) | 🟨 Light | UNDER | 345, 363; sufficiency Q-007/Q-009 & citation FW-009 **unvalidated** |
| **SUBSTRATE** | | | | |
| 9 | **Inference runtime (GPU·ORT·llama-server·memory)** | 🟥 **Saturated** | **OVER** ⬆ | the 311 cluster + 337/338/339/340/346/348/349/352/356/357/376/397/398/412 |
| 10 | Storage & vector quantization | 🟨 Light | UNDER | FW-008 — codec wired, **quality cost never measured**, Float32 default |
| 11 | Multilinguality | 🟨 Light (**by design**) | intentional | 319, 581 (the no-per-language invariant), multilingual model stack — "by construction" |
| 12 | **Personalization / feedback / online learning** | ⬜ **Minimal/None** | **UNDER (biggest gap)** ⬅ | no click/dwell capture; LTR via *synthetic* labels failed (234/245, F-021) |
| **QUALITY & OPS** | | | | |
| 13 | **Evaluation & quality measurement** | 🟥 **Saturated** | **OVER** ⬆ | 216/224/225/230/235/246/251/255/258/259/267/308/313/315/335/369/391 |
| 14 | **Observability & introspection** | 🟥 **Saturated** | **OVER** ⬆ | 294/335/350/356/400/403/404/412/417/427/529/549/553/575 |
| 15 | Performance / concurrency / resources | 🟥 Heavy | balanced | 275/284/286/287/302/348/390/391/392/394/398 |
| 16 | Serving API & agent interface | 🟥 Heavy | balanced | 291/295/362/366/525 + the agent-tool surface |
| 17 | Security / privacy / sandboxing | 🟧 Moderate | balanced | 297, 375, 428, loopback/local-first invariants |

**The shape in one read.** Investment concentrated on the **substrate and instrumentation *around* search** —
inference runtime, observability, evaluation, indexing throughput, serving — all 🟥 Heavy/Saturated. The
**actual ranking quality** (fusion/combination, extraction, answer-grounding) is 🟧/🟨 and **frozen**, and the
**learn-from-use loop** (personalization/feedback) is ⬜ essentially absent. So the repo built a superbly
*observed, evaluated, fast, well-served* engine whose **ranking brain stopped improving** and **never learned
from its users**. The three "OVER" areas (9, 13, 14) are not waste — they're the substrate that *makes* a
ranking improvement measurable and safe — but the imbalance is the §3 enforcement-asymmetry made visible at the
area level: everything with a gate/runtime got serviced; ranking, which has neither, coasted. The highest-ROI
corrections are the **UNDER** rows — extraction (2), adaptive fusion (7), and feedback capture (12) — which is
exactly where §13/§14's live levers sit.

## 16. Deep-dive on the biggest gap — area 12 (feedback / learn-from-use): a latent signal already on disk

Picked area 12 (⬜ Minimal/None — the only near-zero area, the F-021 linchpin: "any real learned lift needs
real feedback labels first") for a **code-level** investigation, framed not as "design a feedback system" but
**"what feedback-shaped signal already flows through this code but isn't captured as a relevance label?"**
(Explore map + four primary-source verifications.) The answer changes F-021's framing.

### 16.1 The system is "observer-only" — but the two user paths differ sharply

It records what the pipeline *produces* (SearchTrace, results, answers) and **never** what's *done* with the
output. But plain-search and agentic paths are **not** symmetric:

| Path | The feedback-shaped signal | Captured today? | Verified |
|---|---|---|---|
| **Plain search** | user clicks/opens/previews a result (doc id + rank) | **NO — 100% uncaptured.** `SearchSurface` click is pure FE state (`selectedHitIds`, inspector); it makes **no** backend call | ✅ grep of `SearchSurface.ts` for `host_.api`/`fetch`/`POST` → **empty** |
| **Explicit rating** | thumbs / helpful / accept on an answer | **NO — affordance does not exist** | ✅ no answer-rating UI (keyword hits are UI-"feedback" collisions) |
| **Agentic** | the agent retrieves candidates, attaches **grounding sources**, and **cites** specific docs per sentence | **YES — already persisted** (retrieved → grounding → cited, all with `parentDocId`/`chunkIndex`) | ✅ `AgentCitationResolver.resolve()` emits `AgentSentenceCite(sentence, sourceIndex, similarity)` over sources carrying `parentDocId`+`chunkIndex` (`:46-72`); `AgentInteractionMapper.java:64-65` persists `citations` on the assistant message |

### 16.2 The finding — agentic citations are a graded, real-query relevance signal ALREADY on disk

The agent path already persists a **3-level implicit grade** per agentic query, keyed by doc identity:

> `retrieved` (in SearchTool evidence) ⊃ `grounding source` (attached to the answer) ⊃ `cited` (an
> `AgentSentenceCite` links an answer sentence to it, above the shared similarity floor).

This is **richer than a binary click** (three relevance tiers, plus a similarity score on the strongest tier)
and it is **not GPL**. The distinction is load-bearing against F-021/245:

| | GPL synthetic triples (F-021 dead end) | Agent-citation signal (this finding) |
|---|---|---|
| Query | **synthetic** (LLM-invented per doc) | **the user's REAL query** |
| Candidate set | constructed | the **REAL retrieved top-k** |
| Judge | CE over synthetic pairs | the LLM **choosing which retrieved docs to ground/cite** |
| 245's failure mode (synthetic-query distribution mismatch) | **hits it — why GPL fails** | **sidesteps it — real query distribution** |
| Stored today | `gpl-training-triples.ndjson` | already in the interaction/conversation store, **un-assembled** |

So F-021's "the only labels are synthetic and they don't transfer" is **too strong**: a *real-query,
real-candidate, LLM-judged* signal is **already being persisted** — it just was never assembled into a
`(query, doc, relevance)` set or joined to the ranking it grades.

### 16.3 The honest caveats — why this is a *partial, noisy, reorder-only* label, not a silver bullet

Per `rule:interrogate-results`, the reasons it is NOT user-click gold:
- **LLM-judged, not user behavior.** The agent cites what *it* used — subject to position bias and to simply
  following whatever retrieval surfaced. It is a *proxy* for relevance, not ground truth.
- **Recall-blind / circularity (the critical one).** The agent only sees the retrieved top-k, so it can only
  grade *within* what retrieval already surfaced — it **cannot signal a relevant doc that retrieval missed.**
  Training a ranker on it can **reorder within top-k** but **cannot fix recall**, and risks **reinforcing the
  current ranking** (a filter-bubble loop). ⟹ It is a **fusion/rerank-weight** signal, *not* a recall signal.
  This dovetails precisely with §13.3 (which is also a within-candidate reorder lever) — and is a real limit
  to state, not bury.
- **Negatives are noisy.** "retrieved-but-not-cited" ≠ "irrelevant" (the agent may ignore a relevant doc).
- **Agentic-only + single-user-sparse.** Plain searches contribute nothing; volume accrues slowly.

### 16.4 What this changes — "capture" is partly a HARVEST, not a build

§13.5 framed feedback capture as a buildable-now deliverable. The investigation splits it in two:
1. **Agentic signal = a HARVEST, not new instrumentation.** The data is already persisted; the work is a
   *read-side* assembly (ETL over the interaction store → `(query, retrieved-ids, grounding-ids, cited-ids,
   similarity)` tuples) + a `queryId` anchor on `SearchTrace` (one field) to join the grade to the ranking it
   grades. **No FE plumbing, no new user burden.** Hook 2/3 in the Explore map.
2. **Plain-search click signal = a genuine BUILD.** Needs the FE→backend bridge (`SearchSurface` click →
   an `InteractionEvent`); the telemetry substrate (`RunEventStore`/`InteractionEvent` extensible attributes)
   is ready to receive it, but the emit site does not exist. Hook 1.

**The decisive, cheap experiment (kills or promotes it):** harvest the existing agent-citation tuples; train a
reranker (or fit §13.3's fusion-weight selector) on them; A/B on a **held-out real-query** set vs static
fusion. Because the labels are real-query (unlike GPL), this is the *first* test that could legitimately beat
fusion — but read it through the recall-blind caveat (measure nDCG *within recalled*, and watch for the
circularity loop). If it can't beat fusion even on real-query labels, learned reranking is dead for us on
*any* currently-available signal and only true user-click capture (the build) remains.

### 16.5 Verdict for the triage

Area 12 is mis-scored as flatly ⬜ "None": **explicit and click feedback are absent (a build away), but a
graded real-query agentic-citation signal is already persisted (a harvest away).** That harvest is the
cheapest path to the *first real-label* experiment the engine has ever had — the one thing F-021 said was
missing — with the honest ceiling that it can only teach *reordering within retrieved*, not recall. It belongs
to area 12's own implementation tempdoc if pursued; recorded here as the analysis that **re-opens F-021's
"no real labels" wall as "no *assembled* real labels, and the cheapest ones are already on disk."**

## 17. Correct long-term design for area 12 — feedback as the *outcome tier* of the projection spine

Design-theory (genre per 557/559/575: end-states at the bar the category sets; feasibility/phasing
deliberately disregarded; major refactors in scope; **general, not implementation-level**). Current-behavior
claims verified against `main` 2026-06-15 (§16 + the §17 substrate investigation). This is the correct
structure for the whole learn-from-use loop, not the §16 harvest tactic — §16 is its degenerate first step.

### 17.1 The frame — the one tier the projection spine never built

This codebase runs a single architectural spine at every altitude (575 §1): *one canonical source per concept
→ a typed declaration → governed projections → a coverage gate.* It records, canonically: **what the ranking
pipeline produced** (549/553 `SearchTrace`), **what operations did** (550 action-lifecycle), and **the whole
"what happened / what's true" family** (575 observed-happening register, 16 concepts). Every one of those
streams is *the engine observing itself.*

**The missing tier is the engine observing its *effect*.** Nothing records **what came of a ranked result** —
`SearchTrace` says "I put doc X at rank 2 for these reasons"; no canonical record says "the user opened X / the
agent cited X / the user refined and opened nothing." That **outcome/disposition** record is the one stream
that closes the loop from output back to the engine, and it is exactly the substrate three separate threads
already need: the real labels F-021 found missing, the realistic eval 251/258 wanted, and the per-query signal
§13.3's selector and Q-010's ratchet consume. The correct design is not a "feedback feature" — it is **adding
the outcome tier to the spine the codebase already runs.**

### 17.2 What exists to extend (so this is generalization, not invention)

| Substrate | State on `main` | Verdict |
|---|---|---|
| 575 observed-happening register + gate | live; 16 concepts; kinds {event-stream, state, tabular, **HISTORY**, timeseries, diagnostic-channel}; each declares `canonicalSource` + **`contributors[]`** + `projections` | **EXTEND** — add the outcome concept |
| Resource taxonomy (`Category.HISTORY`: durable, append-only, query-shaped; `Audience.USER`, `Role.PRODUCT`, `HistoryPolicy.DURABLE`) | live | **REUSE** — the disposition stream IS a `HISTORY` Resource; no new primitive |
| `SearchTrace` (549/553) — per-hit features already on the wire | live; **no stable per-query id** (verified) | **EXTEND** by exactly one field — the join key |
| `LambdaMartTrainer` + `GplTrainingTripleStore` (NDJSON) + `FeaturePayload` | live; trainer reads generic `(query_id, doc_id, label, features)`, **indifferent to label source** | **REUSE ENTIRELY** — only the *source* changes |
| `GplJobCoordinator` (synthetic-query generation) | live; the part F-021 discredited | **DEMOTE** — from "the source" to a cold-start *prior contributor* |
| agentic citation signal (§16: retrieved ⊃ grounding ⊃ cited, persisted) | live | **PROJECT IN** as one contributor, not a second store |

The only genuinely new pieces are the **outcome concept's declaration** (17.3) and its **join key** (17.4).
Everything else is reuse.

### 17.3 The keystone — ONE canonical Disposition record, multi-contributor (NOT a fork)

Declare a single observed-happening concept — *the disposition of a ranked result* — as a `HISTORY`-kind
register entry with **one canonical source and several contributors**, exactly the pattern action-lifecycle
already uses (one source fed by ledger + operation-history + indexing-jobs). The contributors are the two §16
signals plus the absent one, unified by construction:

- **agent-citation contributor** — projects "the agent grounded/cited doc X for this query" (already
  persisted; §16). Dense, immediate, LLM-judged, *reorder-only*.
- **search-interaction contributor** — emits "the user opened / dwelled-on / copied / **refined-without-
  opening** doc X" from the search surface (the one genuinely-new emit site; §16 Hook 1). Sparse, accrues,
  human.
- **explicit-rating contributor** (optional, later) — a thumbs/save signal.

This is the load-bearing correction to the naïve "add a `SearchDispositionStore`" instinct: a *separate
parallel store* would **fork** the very concept (agentic-cited would live in two places) — precisely the
fragmentation 575/553/561 exist to forbid. The register's `contributors[]` is the anti-fork mechanism: **one
canonical disposition stream, many feeders.** The disposition is **graded, not binary** — `shown < opened <
dwelled < cited < acted-on`, with `refined-without-opening` / `abandoned` as the **negative** tier. That
negative tier is the design's sharpest point: it is the *one* signal that escapes §16's recall-blind ceiling —
"the user looked at the whole result set and opened nothing" is a **recall-failure** label that within-retrieved
citation signal structurally cannot produce.

### 17.4 The join — one key turns dispositions into fully-featured labels for free

Add a stable **`interactionId`** to the search response and onto `SearchTrace` (the single missing field). It
makes **Disposition ⋈ SearchTrace** a clean join: "what came of result X" binds to "the full ranked set and
the per-hit features that produced X." Because `SearchTrace` *carries the per-stage per-hit features*
(549), the join yields a **fully-featured labeled example** — `(query, ranked-set,
features, graded-disposition)`. This single key is what elevates §16 from "a pile of citation tuples" to "the
canonical training/eval example, joinable to its cause." It is also the spine-completing move in its own right:
`SearchTrace` (what we ranked) and the disposition record (what came of it) become the two halves of one
loop, joinable by construction.

> ⚠️ **Corrected by §17.10 (de-risking, 2026-06-15).** The "zero re-computation / for free" framing was
> **wrong**: `SearchTrace` is **ephemeral** (response-only, never persisted), so the features are *not*
> recoverable later. The join needs an added **trace/feature-capture step** — the trace must itself become a
> *persisted* observed-happening keyed by `interactionId`, not just gain one field. The thesis stands; the
> cost is one subsystem larger. See §17.10.

### 17.5 Labels and eval as *governed projections* (not new authorities)

Two projections off the Disposition⋈Trace join, both governed (derive-from-the-one-source, can't drift):

1. **The label projection** → the existing `GplTrainingTripleStore` NDJSON shape, consumed by the unchanged
   `LambdaMartTrainer`. The synthetic GPL store **demotes to a cold-start prior contributor** (real labels
   dominate as they accrue). This is the AHA-correct reframe of F-021: the learned layer was never the problem
   — its *label source* was; swap synthetic→disposition-derived and the existing machinery is sound.
2. **The eval projection** → held-out **real-query** eval sets — the "realistic eval" 251/258 called for, now
   from actual usage, and the baseline **Q-010's relevance ratchet** needs. Critically, for a single-user
   engine with **no A/B population**, this projection enables the only honest evaluation: **counterfactual /
   replay** — "would candidate ranker R have surfaced what the user actually opened, higher than the shipped
   ranker did?" The Trace⋈Disposition join is exactly the data a counterfactual estimator needs. This is the
   local-first answer to "you can't A/B a single user."

### 17.6 The learning consumer — the two prior findings bind here

The consumer (LambdaMart, or §13.3's fusion-weight selector — they converge on this same label source) inherits
two non-negotiable constraints already established in this doc:
- **§13.7 (features beyond fusion's own scores).** A consumer fed only the two leg scores is redundant with
  fusion regardless of labels. The disposition design *supplies the fix for free*: `SearchTrace` already
  carries rich per-stage features (the "234 V2 schema" 580 kept invoking) — the join delivers them. So real
  labels (this design) **plus** rich features (the join) together clear *both* walls F-021 and §13.7 named.
- **§16 (recall-blindness).** Citation-only labels teach reorder, not recall. The `refined-without-opening`
  negative tier (17.3) is the deliberate design answer — capture it and the loop can, in principle, learn that
  retrieval *missed*, not just mis-ordered.

### 17.7 The local-first shape — what makes this design distinctive (and a moat, not a limit)

Three single-user/local constraints invert into the design's defining properties:
- **Privacy is the *enabling condition*, not a constraint.** The canonical disposition record **never leaves
  the machine** — so the engine may ethically capture behavioral signal (full opens, dwell, edits-after-open,
  refinements) a cloud engine legally/ethically cannot. 580 §11's "local-first/privacy" *marketing* claim
  becomes an *engineering capability*: richer feedback than any cloud competitor can lawfully hold.
- **Sparse single-user signal → a *hybrid* label source + conservative learning.** No cross-user pooling, so:
  the **LLM-judge dispositions** (agentic citations — dense, day-one, reorder-only) seed and densify the
  **human dispositions** (sparse, accruing, carrying the recall signal); learning is a **prior + slow personal
  adaptation** (static fusion / cold-start GPL as the prior), never a from-scratch per-user model.
- **No A/B population → counterfactual eval** (17.5) is the native evaluation mode, enabled by the join.

### 17.8 Extend-not-replace ledger (the explicit verdict the brief asked for)

**Replace nothing wholesale.** Extend the 575 register (one new concept), the Resource set (one `HISTORY`
Resource), `SearchTrace` (one join field). Reuse the entire learning stack (trainer, triple format, feature
schema). The *only* thing demoted is GPL's **synthetic-query source** — and even it survives as the cold-start
prior. The agentic signal is **projected into** the canonical stream, never re-stored. The design is the
codebase's own spine plus one tier, plus a rewire of the learner's input from synthetic to real.

### 17.9 Why this is the correct structure (vs. the §16 short-term harvest), and what it unblocks

The §16 harvest ("dump cited tuples to NDJSON, train") works *once* and **forks**: a second label store, no join
to the ranking, the UI signal still orphaned, no eval, no register home. The correct structure makes "what came
of a result" a **first-class canonical record in the spine**, so by construction: every disposition signal
(agentic, UI, explicit) lands in **one** place; it is **joinable** to the ranking that caused it; labels and
eval are **governed projections** that cannot drift; and **one** record simultaneously feeds the learned ranker
(F-021), the §13.3 selector, the realistic-eval lane (251/258), the relevance ratchet (Q-010), and any future
personalization (the whole of area 12). It closes the loop the projection spine was always missing — the spine
records what the engine *does*; the outcome tier records **what that doing achieved**, which is the only thing
that can teach the engine to rank better. **This is the design that should seed area 12's own implementation
tempdoc**; 580 records the correct end-state, not the build.

### 17.10 De-risking pass (2026-06-15) — the one surprise, the corrected design, and a confidence read

A read-only confidence-building pass stress-tested §16/§17's load-bearing assumptions against `main` *before*
any implementation (no feature code). It resolved five uncertainties; one is a material correction.

**The surprise (uncertainty 1, verified): `SearchTrace` is EPHEMERAL.** Built in `SearchTraceMapper`, put on
the HTTP response in `KnowledgeSearchController`, then **discarded** — persisted nowhere; no search-history /
query-log store exists. So §17.4's "the join is free" is **false**: the per-hit features exist only transiently.
**Corrected design:** the outcome tier needs a **trace/feature-capture subsystem** — persist each query's
ranked-set + per-hit features under a stable `interactionId`, i.e. **the trace must itself become a persisted
575 observed-happening** (today it is response-only). This does *not* change the thesis (feedback = the outcome
tier; the disposition record, the `contributors[]` unification, the projections all stand) — it adds a *second*
spine-completing record and makes the build **one subsystem larger** than §17 stated. The same capture gap
blocks the label projection *and* the counterfactual-eval projection, so it is the **first** dependency.

**The other four:**
- **(2) The graded agentic tuple IS recoverable from disk** (retrieved ⊃ grounding ⊃ cited, by `parentDocId`:
  `ToolExecutionCompleted.structuredData.searchResults` ⊃ `AgentDone.sources` ⊃ `AgentDone.citations`) — **but
  feature-less** (evidence cards carry path/excerpt, not scores). So even the agentic labels need the §17.10
  trace-capture to become *featured* examples. (Whole-doc hits w/o `parentDocId` are filtered → minor loss.)
- **(3) §13.7 ↔ §17 feature tension reconciled.** "Reuse the learning stack" = reuse `LambdaMartTrainer`
  (generic NDJSON in) + the triple format + `FeaturePayload`; **extend** `LambdaMartFeatureSchema` past 2
  features (the §13.7 redundancy fix); **add** feature capture (the surprise). The payoff: trace-capture +
  disposition-join clears **both** documented walls — F-021 (no real labels) and §13.7 (redundant features) —
  with one move.
- **(4) The loop is COLD.** No real-usage data on disk (`tmp/conversations`/`runs` absent; only dev fixtures +
  GPL **synthetic** triples 498/12,234). Signal accrues only with use → value is **back-loaded** (ship-the-pipe-
  now / harvest-later; no shortcut to labels today). The agentic-citation contributor densifies it day-one once
  the agent is used, but a single user reaching ~500 real labels (234's threshold) is slow.
- **(5) Counterfactual eval depends on the same trace-capture and is research-grade.** Descope from full IPS to
  a tractable proxy: "for docs the user opened/cited, what rank did the engine give them, and would candidate
  ranker R rank them higher?" — needs only the disposition + the persisted features, no propensity model.

**Confidence (0–10, critical):**
- **§17 *structure* correct: ~8** — the surprise *strengthened* it (the trace joins the spine as a persisted
  concept); it fits the architecture and reuses the learning stack.
- **§17 *implementation + near-term value*: ~4** — one subsystem larger than written, and the payoff waits on
  real use that does not exist yet.
- For the sibling levers: **§13.3 fusion weighting ~7** (the §13.8 experiment *is* the de-risk — best-ready);
  **F-009 PaddleOCR-VL pilot ~6** (verified candidate, real integration cost, self-reported number); **Q-010
  ~6** (its eval baseline is §17's real-query projection — partly gated on §17).
- **Overall remaining work ~5/10**, split sharply: *the designs are right* (high) vs *implement-and-get-quality-
  soon* (low). The execution-ready lever is **§13.3**; **§17** is architecturally sound but a build-the-pipe-now
  bet. Full de-risking record: the plan artifact for this pass.
