---
title: "598 — Capability-derived retrieval: the default search pipeline must reflect what the engine can run. INVESTIGATION RE-FRAMED the 593 finding (semantic search never ran a dense query): the dominant root is NOT the single-GPU catch-22 but that the main search UI NEVER REQUESTS the dense leg — both passes resolve to a static TEXT (keyword) preset, so semantic search is unreachable from the UI regardless of hardware/fingerprint/chat-mode (proven live: an explicit mode:hybrid query returns conceptually-relevant results while chat is Online on the same 12 GB box). LONG-TERM DESIGN: the retrieval pipeline shape should be a PROJECTION of retrieval capability owned by the one authority that knows it (the Worker's embedding-compat boundary), with callers expressing intent (AUTO vs explicit override) — generalizing the capability-aware auto-hybrid pattern that ALREADY exists on the RAG path (rag.retrieveMode=\"auto\") to the general search wire. Two sub-roots: the rebuilt embedding generation's fingerprint must be co-durable with the vectors it certifies (provenance ≠ completeness), and query-embed must be unbundled from bulk-backfill in the single-tenant GPU arbitration (ADR-0004)."
type: tempdocs
status: "IMPLEMENTED + LIVE-VERIFIED (B-1..B-4, 2026-06-18, PART XVIII as-built) — the 598 reopen is SHIPPED: WI-1 dense-serviceability on the `retrieval` composite (new `LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE` + `StatusLifecycleHandler.denseUnavailableReason` mapping compat UNAVAILABLE / COMPATIBLE-but-`embeddingReady=false` + the `readinessNotice` CAUSE_ROWS row), WI-2 `core.rebuild-index → Audience.USER` + UI-only executor (Brain rebuild button now renders for a default USER viewer, was 0×0; AGENT executor dropped so it's not an agent tool), WI-3 durability regression tests (Lucene stamp→close→reopen→COMPATIBLE + IndexGenerationManager generation-pointer round-trip). Static green (build -x test, touched-module tests, 3179 FE tests, readiness-reason-codes/search-issuance/verdict-derivation/class-size gates) AND live-verified end-to-end on the worktree build (BLOCKED_LEGACY → truthful 'Reindex required … keyword-only' banner + USER rebuild button → rebuild → COMPATIBLE → default query 'Semantic + keyword' with `Dense (vector) executed` in Pipeline-details). Co-land note: the new code's final consumed-copy wording is shared with 600. ── PRIOR DESIGN: REOPENED + RE-INVESTIGATED (PART XIII, 2026-06-17 second agent) — the reopen's 4 findings were re-verified against `main`: B-2 (no reachable USER rebuild — `core.rebuild-index` is OPERATOR-gated to 0×0 in Brain, Library's affordance is the wrong incremental op, Search's banner remedy only shows when already-degraded) is REAL and the TRUE dominant blocker; B-3's stated mechanism is WRONG (the FE reads the backend `retrieval` composite, NOT `embeddingCoverage` — which has zero consumers — so the over-claim is a produce-side state-reporting gap: `compatBlockedReason` doesn't map `UNAVAILABLE`, inverse of the obs#480 under-claim); B-4 = B-3's produce-side hole seen from Brain's raw-compat read; B-1 durability is durable-by-construction on the blue/green path, so BLOCKED-after-restart most likely means NO rebuild completed (B-2 linkage), and its deliverable is the missing rebuild→restart regression test. Refined design (§62): B-2 reachability is priority; B-3/B-4 collapse to ONE Worker `dense-serviceable` predicate projected to `/api/status` (Design A, now data-motivated); B-1 is test-first. Decisive owned-stack experiment + open user decisions in §63/§64. LONG-TERM DESIGN (PART XIV, §65-71): all four findings reduce to ONE unrepresented fact — dense-serviceability (the binary 'can a doc-level dense query run now', = the issuance predicate `allowQueryEmbeddings() && embeddingProvider.isAvailable()`) is currently reconstructed by every consumer from the LEAKY graded `retrieval` composite (not-sufficient → B-3 over-claim; not-necessary → obs#480 under-claim; reconstructed-twice → B-4). The design adds exactly ONE edge: the Worker emits dense-serviceability faithfully through the EXISTING reason-code channel (one new `embedder_unavailable` code closing the hole + a single-snapshot consistency invariant), so capability/quality/actuality become three facts with one source each; B-2 reachability collapses to a coherence invariant (realign `core.rebuild-index` to USER audience — self-service recovery); B-1 durability is a regression test, not new structure. Rejects a parallel `denseServiceable` boolean (forks the fact the codes carry) and any new observability service (respects 600). Extends 595/596/600/598-R1, no replacement. USER-FACING DESIGN (PART XV, §72-78): the three facets get three on-screen slots — capability (banner headline + Brain card), quality (calm banner secondary line, never 'keyword results'), actuality (the existing per-result 'Semantic + keyword'/'Keyword' indicator); the banner's three existing branches are RE-KEYED onto the dense-serviceable signal (closing both the B-3 over-claim and the obs#480 under-claim) with NO new component; rebuild reachability = `core.rebuild-index → USER` so the Brain button paints + the banner remedy stays one control (honesty coupled to B-1 durability — never offer a fix that doesn't stick). LIVE PASS FOUND a TOTAL render-wedge ('Reconnecting…' blanks every surface incl. Brain 'Loading…'), making 598's truthful banner/remedy moot at point-of-use — a hard 595/596-owned precondition (§77), plus the design intent 'render last-known, not blank'. CONFIDENCE-BUILDING (PART XVI, §79-82): static de-risk closed all 5 Tier-A probes — A1 RULED OUT the snapshot-skew B-3 candidate (one workerView feeds both compat block + retrieval composite), so the consistency-invariant is dropped as already-true; A2 found a SURPRISE — flipping rebuild to USER would expose it to the AGENT tool list (ExecutorTag.AGENT), so the fix is 'USER + drop AGENT executor' (or OPERATOR + Brain override), not a plain flip; A3 confirmed startMigration re-embeds+stamps; A4 narrowed the produce-side fix to 'map compat UNAVAILABLE → a dense-blocking reason' (the embedder-on-COMPATIBLE over-claim is rare post-R4); A5 confirmed the banner remedy invokes for a USER (no authz). B-1 durability is now statically decisive (restart opens state.json active_generation, advanced to green ONLY when verifyGreenMetadata passes → BLOCKED-after-restart ⟺ green never stamped ⟺ no durable rebuild, not a selection bug); the regression test is feasible. Tier-B live deferred (owned/contended stack); residuals folded into implementation. Overall confidence 7/10. CROSS-TEMPDOC COORDINATION refreshed (PART XVII, §83-86, supersedes PART VI): NO active worktrees (no merge contention; 603's 'active 598 -impl worktrees' note is stale). No hard blockers. CORRECTION to §77 — the SSE/'Reconnecting…' render-wedge moved owner to the NEW doc 604 (602 promoted its R1/R2), which is UNDESIGNED — so 598's user-facing payoff is gated on 604, the largest external risk. Must-co-land: 600 (the twin, downstream) — 598's new 'map UNAVAILABLE' reason code needs a CAUSE_ROWS row or it trips 600's readiness-reason-codes gate. 597 shares SearchSurface.ts (favorable, merge-order). 603 investigated as 598-INDEPENDENT + merged (no conflict). The dense-serviceable fact's multi-consumer fork risk (PART VI risk D) is CONTAINED (600 consumes by design; 602/603 don't fork) — 598 just must project it once (its §67). ── PRIOR REOPEN NOTE: the #1 product blocker is NOT closed in-environment AND R1's capability-default introduced a truthfulness regression. Three live findings on the re-run: (Still-Present #6) BLOCKED_LEGACY RETURNS after every clean restart — R3's co-durable fingerprint did not hold in-env (rebuilt embeddings still don't survive / the fingerprint never re-stamps), even an explicit denseEnabled:true pipeline → Dense skipped; (NEW #3) NO usable rebuild affordance — the Search 'Force Rebuild' button is GONE (now 'Open Health'), Library 'Reindex' is incremental (doesn't clear BLOCKED_LEGACY), and the AI-Brain rebuild control `<jf-operation operation-id=\"core.rebuild-index\">` renders 0×0, so the user is told to rebuild with no working way; (NEW #1) the R1 capability-default now drives a 'fully semantic / results complete' OVER-CLAIM because the FE-read capability `indexCapabilities.embeddingCoverage=0.9755` does NOT reflect the Worker's BLOCKED_LEGACY gate — calm-but-inaccurate copy where the old copy was alarming-but-accurate; (NEW #2) cross-surface disagreement (AI Brain says 'disabled until you rebuild' while Search/Chat/Health say 'fully semantic'). Reopen scope in §REOPENED below. ── PRIOR: IMPLEMENTED & MERGED (R1+R3) to main — merge 9dd5d7042 (2026-06-17); live browser verification captured (§38.4: default query rendered 'Semantic + keyword' hybrid, Pipeline-details Dense (vector) executed) + post-merge build/gates/FE-3158-tests green. A post-implementation critical review fixed three issues in-tree before merge — Fix C (AUTO keyword-fallback keeps query expansion), Fix A (resolveAutoDense gates dense on embedder availability too), Fix E (deterministic green-cutover fingerprint stamp, closing the cross-thread race) — and confirmed Fix F a non-issue (§38.1-38.2). R4 still DEFERRED (§38.3). Progression: §1-6 problem statement → PART II investigation → PART III design → PART IV de-risking → PART V frontend design → PART VI cross-tempdoc coordination → PART VII AS-BUILT (§38). R1 (capability-derived AUTO default) DONE: proto dense_auto; Head emits AUTO when no mode+no pipeline; Worker GrpcSearchService.resolveAutoDense; FE retrieval-mode indicator + honest loading copy; backend + FE regression tests; full build + 3115 FE tests + search-issuance/verdict-derivation gates green. R3 (durable rebuilt embeddings) DONE structurally by reusing the EXISTING blue/green migration (the user Force Rebuild ALREADY routes through startMigration); added embedding-fingerprint verification to the green cutover (verifyGreenMetadata) so a rebuild cannot promote a still-BLOCKED generation, with a regression test. R4 (query-embed-while-Online unbundle) DEFERRED with rationale (§38.3): optional, safety-sensitive (VRAM/ADR-0004), moot for normal search after R1, and the safe mechanism needs live GPU verification — documented, not hacked. REMAINING: the final live browser verification (§38.4) — deferred while the dev stack is owned by another session."
created: 2026-06-17
author: agent
category: engine / inference-runtime / gpu / retrieval / index-lifecycle / operability / reachability / product-shape
related:
  - tempdoc 593 (the UX walkthrough that surfaced this — §C Force-Rebuild degraded state, §D worker wedged, §E BLOCKED_LEGACY root + the 384-d/768-d note, §F GPU-contention Online-vs-Indexing, §G the routing layer wantDense=false, §H the catch-22, §I the FE "hard walls"). THE SOURCE. CLOSED.
  - tempdoc 594 / 595 / 596 / 597 (the four FE-presentation depth rounds spawned from 593 — Display / Observed-state / Operability / search-count). THE SIBLINGS THIS IS NOT: they fix how the SYMPTOMS are DISPLAYED (e.g. 595 renders the rebuild transition honestly); this is why the END STATE is unreachable at all. A perfectly honest UI (595) still leaves semantic search non-functional. Distinct root.
  - tempdoc 587 (host-capability sensing — GPU/VRAM detection). ADJACENT, NOT THIS: 587 senses the GPU; this is about SCHEDULING the GPU between two mutually-exclusive model roles (chat LLM vs embedder) and the lifecycle around it. Phase 1 SHIPPED.
  - tempdoc 588 (worker-engine silent failures) / 589 (engine-correctness-debt). ADJACENT engine work; the worker "wedge" (593 §D) and the BLOCKED_LEGACY gate may intersect — a boundary to draw, not assumed.
  - CLAUDE.md `verify-dont-guess` / `audit-driven-fixes-need-test` (the 593 mechanisms are point-in-time observations; re-confirm against `main` before trusting — §6 Q1).
---

# 598 — Semantic search is operationally unreachable end-to-end on single-GPU

> **What this document is.** A *problem statement* — the one 593 finding that no follow-up owns, and
> the most user-impactful one: the product's headline capability (meaning-based search) did not work
> end-to-end for the walkthrough persona, and the reasons are operational/architectural, not
> presentational. **It deliberately proposes nothing yet** (per request). It frames the problem,
> assembles the evidence 593 already gathered, and lists what must be verified before any design.

## 1. The problem, in one sentence

> A user who indexes a folder and wants **meaning-based search** cannot get there: across a careful,
> fully-tooled walkthrough (593), **not a single query ever executed the `Dense (vector)` leg** — the
> three preconditions for a dense query (a fingerprinted embedding index · the chat LLM **Online** so
> the FE issues `wantDense=true` · the embedder able to encode) are **mutually unreachable at once** on
> a 12 GB single-GPU box, and the product neither guides the path nor makes the rebuilt index durable.

## 2. The observed reality (593)

- The engine **has** the capability: dense vectors, SPLADE, a cross-encoder reranker, hybrid fusion,
  and the real embedding model `gte-multilingual-base` (GPU, **768-d**, CLS pooling) (593 §E).
- Yet **every** query — literal, low-IDF, and exotic out-of-vocabulary alike — ran keyword-only:
  `decision: sparse_shortcut · mode: TEXT`, `Dense (vector) skipped` (593 §G). Indirect/paraphrased
  queries returned BM25 stopword noise, **not** the conceptually-relevant docs (593 §G / FINAL ANSWER).
- The keyword tier was fast, faceted, accurate throughout; the **vector tier never engaged** (593 §I).

## 3. The evidenced mechanism chain (observations, not diagnoses-for-fixing)

Each link below is something 593 *saw* (UI, worker logs, `debug_state`, `inference_status`, and a read
of `SearchPlanner.java`). They compose into a closed loop:

1. **Index-state gate.** The index sat in `BLOCKED_LEGACY` — *"index has no embedding fingerprint…
   embedding writes and vector/hybrid queries are blocked until a forced reindex"* (593 §E). Every
   backfill ran `embed=0` (SPLADE/NER wrote; embeddings refused).
2. **Single-GPU mode exclusivity.** On 12 GB, the chat LLM (~9.7 GB, **Online** mode) and the embedder
   (**Indexing** mode) cannot co-reside. Force Rebuild in **Online** mode starves the embedder and
   **stalls / "wedges"** (593 §D/§F); Force Rebuild in **Indexing** mode (chat LLM unloaded, VRAM
   9.7→3.2 GB) **completes** and builds a new generation that switches in (593 §F).
3. **Routing gate.** The FE only issues a dense query (`wantDense=true`) when it considers semantic
   **non-degraded**, which requires the **chat LLM Online** — but Online unloads the embedder. So
   `SparseShortcut` is returned because the *request* was sparse-only, not as a QPP optimization
   (593 §G, read from `SearchPlanner.java`).
4. **Durability gap.** A stack restart (needed to reset the runtime to Online) **reverted the index to
   `BLOCKED_LEGACY` again** — the rebuilt embedding generation did not survive (593 §H; honest caveat:
   the stop was a force-kill that may have interrupted a durable commit).
5. **No product guidance / misleading feedback.** The FE lets Force Rebuild run in *either* mode and
   never says "switch to Indexing first"; it renders a *successful* worker-generation rebuild as a
   scary **"Reconnecting…"** disconnect; and after a restart it just shows "Reindex required" again
   with no path forward (593 §F/§H/§I). The activation choreography is invisible to a FE-only user.

**Net (593 §H):** to run one dense query you need (a) a fingerprinted generation **and** (b) chat LLM
Online **and** (c) the embedder available — and on this hardware those three are mutually exclusive or
non-durable. The loop has no exit through the product.

## 4. Why this matters / why it is its own tempdoc

- **It is the user's actual primary-task failure.** 593's whole point was "index → search by meaning";
  that outcome was **not met** (593 FINAL ANSWER). Everything else 593 found is secondary to this.
- **The four spawned siblings don't touch it.** 594 (chip facts), 595 (observed-state verdict/
  transition), 596 (affordance reasons), 597 (result counts) all slice the **FE presentation** of the
  symptoms. Even if all four ship perfectly — e.g. 595 renders the rebuild transition honestly instead
  of as data-loss — **semantic search is still operationally unreachable.** Honest *display* of an
  unreachable state is not reachability. This is the missing operational root.
- **A careful, fully-tooled agent could not do it; a FE-only user has no chance** (593 §I "hard walls":
  `BLOCKED_LEGACY`, the mode requirement, the restart-revert are all log-only / impossible to learn
  from the UI). So the gap is severe and not merely cosmetic.

## 5. Scope boundary (what this is — and is NOT)

**IN (one root — operational reachability of the dense/semantic path):** the index-state gate (#1),
the single-GPU mode exclusivity (#2), the routing gate's coupling to chat-LLM-Online (#3), the
generation-durability-across-restart gap (#4), and the absence of an activation path/guidance (#5) —
i.e. the closed loop that makes one dense query unreachable.

**OUT (other roots — do NOT bundle):**
- **How the rebuild *transition* is displayed** (0 docs / "No watched folders" / "Service degraded" vs
  "operational" / "Reconnecting…") — that is the **Observed-state** presentation root, owned by **595**.
  598 is "the rebuild end-state is unreachable," not "the rebuild looks scary."
- **The "Embeddings 384-d" chip** and the count/facet truthfulness — **594 / 597**.
- **GPU/VRAM *sensing*** — **587** (598 is GPU *scheduling between roles*, not detection).
- **The generic worker-wedge / silent-failure surface** — may intersect **588**; the boundary
  (is the §D "wedge" the same failure class, or a GPU-starvation symptom of #2?) is an open question, not
  an assumption.

## 6. Open questions — what must be verified BEFORE any design (no proposals here)

1. **Is it still true on `main`?** 593 is a dated, point-in-time observation against a dev stack owned
   by another session. Re-confirm each mechanism against current code/runtime (`verify-dont-guess`):
   does the FE still gate `wantDense` on chat-LLM-Online? does a forced reindex still only complete in
   Indexing mode? does a clean (non-force-kill) restart still revert to `BLOCKED_LEGACY`?
2. **Is single-GPU the binding constraint, or also a smaller/larger-VRAM and multi-GPU story?** The
   12 GB mutual-exclusivity is hardware-specific; characterize where the catch-22 binds.
3. **Is the durability loss real or a force-kill artifact?** (593 §H flagged the caveat.) Does a
   graceful stop preserve the rebuilt generation + fingerprint?
4. **What is the *intended* activation path** (per the engineers/ADRs) from "indexed folder" to "a
   dense query runs"? Is there a designed sequence the product fails to guide, or is none defined?
5. **Boundary with 588/589** — is the §D worker-wedge the same failure as a tracked engine defect, or a
   distinct GPU-starvation symptom of mechanism #2?

> **No fixes proposed (by request).** The next step, when the user chooses, is the investigation in §6;
> design comes only after the problem is confirmed and scoped.

---

# PART II — §6 INVESTIGATION (2026-06-17, agent takeover)

> **What changed.** The §1-6 problem statement above is 593-dated and is preserved verbatim. This
> Part II is the investigation §6 asked for: re-verify every mechanism against `main`, characterize
> the constraint, and scope the boundaries — *before* design. It substantially **re-frames** the
> problem. The catch-22 as written is partly a **misdiagnosis**; the true dominant root is simpler,
> hardware-independent, and worse. Method, evidence (with file:line + live probes), and the answer to
> each open question follow. The user has now explicitly authorized suggesting alternative designs, so
> §12 sketches the solution space (clearly delineated from the verified facts).

## 7. Method

Three tiers, every claim cited:

1. **Static source reads** — ADR-0004, ADR-0019; the FE search store; the planner; the embedding
   compatibility controller.
2. **Four parallel source-cited subagent audits** (subagents do not inherit CLAUDE.md — each was
   briefed inline): (A) FE `wantDense` gating, (B) `BLOCKED_LEGACY` + fingerprint durability,
   (C) GPU mode exclusivity, (D) `SearchPlanner` sparse-shortcut, plus a fifth on (E) the Head/backend
   default pipeline and (F) the embedder GPU/CPU default. Findings below carry their file:line.
3. **Live read-only probes** of the running dev stack (owned by another session — *no* takeover, *no*
   reindex, *no* mode change; only `GET`-shaped `/api/knowledge/search` + `debug_state` /
   `inference_status`). This is the empirical `verify-don't-guess` tier for open-Q1.

Recency check: `searchState.ts` was last touched 2026-06-17 (597 match-count work) — the dense-gating
logic is unchanged; all other cited files predate 593. So "is it still true on `main`" is answered
against code as of today.

## 8. The headline correction — semantic search is unreachable because the UI never asks for it

593 §G read `SearchPlanner` correctly (sparse-only request ⇒ `sparse_shortcut`), but **mis-attributed
the cause** to "the FE only issues dense when the chat LLM is Online." The real cause is upstream and
unconditional:

- **The FE never sets dense.** `buildSearchIntent` (`modules/ui-web/src/shell-v0/state/searchState.ts:314-331`)
  makes the dense decision a pure function of *pass stage*: the **quick** pass pins
  `QUICK_PIPELINE` (`denseEnabled:false`, `:269-279`); the **refined** pass sends **no `pipeline` and
  no `mode`** at all. There is **zero** reference to AI/online/readiness/`verdict`/`degraded` in
  request construction (audit A). The `degraded` verdict only drives a *banner* (`readinessNotice.ts`),
  never the request body — and even that verdict explicitly *excludes* the AI/inference axis
  (`verdict.ts:133-135`: "this is retrieval/search health… AI is intentionally EXCLUDED"). So the
  claim "dense requires chat-LLM-Online" is **FALSE on `main`.**
- **The Head defaults to sparse.** When the body carries no pipeline/mode, the Head always sets a
  `PipelineConfig` derived from `SearchPipelinePresets.expandPreset(SEARCH_MODE_TEXT, …)` →
  `(sparse=true, dense=false, splade=false)` (`modules/app-services/.../worker/KnowledgeSearchEngine.java:296-300,376-386`;
  `SearchPipelinePresets.java:24-26,49`). Dense is **never** derived from embedding-readiness,
  fingerprint presence, or a "default hybrid" policy (audit E).
- **The planner then takes the shortcut.** `sparseOnlyRequest = wantSparse && !wantDense && !wantSplade`
  (`modules/worker-services/.../plan/SearchPlanner.java:82`) ⇒ `SparseShortcut` (`:110`). It is request
  shape, **not** a QPP/IDF heuristic (audit D — there is no `maxIdf` branch in the planner; the QPP
  numbers 593 saw in the trace are computed but do not gate this).
- **The only producers of dense are non-UI:** the agent `SearchTool` (defaults `PipelineConfig.HYBRID`,
  `modules/app-agent/.../tools/SearchTool.java:79`) and the jseval harness (explicit `mode:hybrid`).
  **Hybrid/dense is not reachable from the main search box at all** (audit E).

**Consequence that demolishes the §1-6 framing:** even with embeddings fully built, the index
`COMPATIBLE`, the GPU happy, and chat-LLM Online — *a normal search from the box still runs
sparse-only*, because nothing on the FE→Head path ever requests dense. The three "mutually-unreachable
preconditions" (§1, §3) are **moot**: the system never gets far enough to need them. This is
hardware-independent, fingerprint-independent, and mode-independent.

### 8.1 Live proof (the decisive evidence)

Against the running stack today (`doc_count 608`, `embedding_ready:true`, every doc
`embedding_status:COMPLETED`, serving generation `g-20260616-125203` — i.e. **no longer**
`BLOCKED_LEGACY`; the index is fully embedded and compatible):

- **Default query** `"knowledge server body"` (no mode) → per-hit `trace: [sparse-retrieval,
  cross-encoder]`. **No dense leg.** Doc-level BM25 hits. Confirms §8 live.
- **Explicit `mode:hybrid`** query `"do less work when the user is active"` (a paraphrase with none of
  the literal doc terms — exactly 593's failing indirect-query class) → `trace: [chunk-merge,
  branch-fusion, cross-encoder]`, chunk-level results, and it surfaced the **conceptually-relevant**
  docs (`237-pipeline-readiness`, `512-codebase-investigation`, the "Check Breath" throttle material)
  that 593 reported BM25 *missed*. **The dense capability works end-to-end** — it is simply never
  requested by the UI.
- **Simultaneity:** that hybrid query embedded + ranked while `inference_status` reported
  `mode:"online"`, Qwen 9B loaded, on the 12 GB card (`tier:gpu_12gb_plus`), with backfill still
  progressing (`embeddingQueueSize:15`). So **Online + dense coexisted** — directly contradicting §1/§3's
  "mutually exclusive at once" in this live config.

## 9. Per-mechanism verdicts vs §3

| §3 link | 593/598 claim | Verdict on `main` | Evidence |
|---|---|---|---|
| #1 Index-state gate | `BLOCKED_LEGACY` blocks vector/hybrid until forced reindex | **TRUE** (real gate) but **self-resolved** here — index is now `COMPATIBLE` | `EmbeddingCompatibilityController.java:44-55,85-136`; live `embedding_ready:true` |
| #2 Single-GPU mode exclusivity | chat-LLM + embedder cannot co-reside on 12 GB | **PARTLY — config-dependent & over-broad** (see §10) | ADR-0004; `InferenceWiring.java:39`; `LoopPacingPolicy.java:60-68`; live coexistence |
| #3 Routing gate couples dense to chat-LLM-Online | FE issues dense only when semantic non-degraded ⇒ needs Online | **FALSE** — FE never requests dense; default is sparse regardless of mode | audit A + E; `searchState.ts:314-331`; `KnowledgeSearchEngine.java:376-386` |
| #4 Durability gap | rebuilt generation reverts to `BLOCKED_LEGACY` after restart | **TRUE — real ordering bug, broader than force-kill** (see §11) | `EmbeddingCompatibilityController.java:198-209,257-268`; `EmbeddingProviderLifecycle.java:214-247`; `IndexingLoop.java:642-654` |
| #5 No guidance / misleading feedback | FE doesn't guide the path; renders rebuild as "Reconnecting…" | **TRUE** but largely owned by 595/596 (presentation); the *guidance-to-dense* gap is moot given #3 (there is no dense path to guide *to* from the UI) | 593 §F/§H/§I |

## 10. Open-Q2 — where the GPU constraint actually binds (and where it doesn't)

The mutual exclusion is **a blanket advisory flag, not VRAM math** (audit C): `main_gpu_active = (mode
== ONLINE)` (`InferenceWiring.java:38-41`), and every worker reaction gates on `mainGpuActive &&
embeddingProvider.isUsingGpu()` where `isUsingGpu()` is just the static config flag
(`EmbeddingService.java:509-511`). There is **no** free-VRAM check, **no** VRAM-tier branch, **no**
multi-GPU branch anywhere in the decision path (ADR-0004 §Consequences explicitly: "No concurrent GPU
use even when VRAM would allow it (e.g. 24 GB)"). So:

- **The embedder is GPU by default** on any CUDA box: the unset `JUSTSEARCH_EMBED_GPU_ENABLED`
  inherits the master `justsearch.gpu.enabled`, which `GpuAutoDetection.probe()` auto-sets `true` when
  a driver/device is visible (`ResolvedConfigBuilder.java:969-979`; `GpuAutoDetection` ordinal 150) —
  audit F. So a normal 12 GB user *does* get a GPU-resident embedder.
- **With a GPU-resident embedder, going Online fully *unloads* it** (`EmbeddingProviderLifecycle.handleGpuStateTransition` → `unloadEmbeddingService()` → `NoOpEmbeddingProvider`,
  `:144-185`), so a *query* embed returns empty ⇒ dense fails *while Online* (audit C). This is the
  real kernel of 593's experience (its stack was GPU-embedder: VRAM 9.7→3.2 on switch + the "Batch
  Processing Active… paused to prevent GPU memory conflicts" modal).
- **BUT chat-LLM-Online is not required for dense search.** Dense *search* runs fine in **Indexing**
  (or Offline) mode where the embedder is loaded. The catch-22 only bites when you need **chat
  generation AND dense retrieval at the same time** — i.e. the **RAG / grounded-Q&A** path (Documents
  tab), not standalone semantic search. 593 conflated the two.
- **And it is config-dependent.** The *live* stack today shows Online + dense coexisting + backfill
  running while Online. Per `LoopPacingPolicy.shouldRunBackfill = !mainGpuActive || !isUsingGpu()`,
  backfill running under `mode:online` ⇒ `isUsingGpu()==false` ⇒ **this stack's embedder is effectively
  CPU-resident**, so it is never unloaded and query-embed works alongside Online. A CPU embedder
  removes the catch-22 entirely — at a latency cost (ADR-0019: ~2-5 s/query, backfill of thousands of
  docs is hours-class on the FP16-on-CPU model).
- **Binding summary:** the catch-22 binds **iff** (embedder is GPU-resident) **AND** (the user wants
  chat-Online and dense-retrieval simultaneously). It is *independent of VRAM size* (the blanket flag
  evicts even on a 24 GB card) and *dissolves* with a CPU embedder. 12 GB is not special; the policy is.
  Critically, ~628 MB (FP16 embedder, ADR-0019) + ~9.7 GB (chat) ≈ 10.3 GB **fits** in 12 GB — the
  eviction is policy-conservatism, not a VRAM necessity, for the *query*-embed case.

> **Unresolved sub-point (honest):** I could not, from config alone, fully reconcile *why* the live
> stack behaves CPU-embedder-like (backfill under Online) while audit F says GPU is the auto-detected
> default. Candidates: (a) this dev stack has embed-GPU effectively off; (b) query embedding routes via
> the `workers.ai` sidecar (port 50061, `features.embed:true`) which may sit outside the in-process
> `main_gpu_active` arbitration. Resolving (a) vs (b) is a precise, cheap follow-up (a one-line
> `effective_config` read for `justsearch.gpu.enabled`/`justsearch.embed.gpu.enabled` on a *fresh*
> default install, plus tracing whether `SearchInputCapture.embedQuery` hits the in-process provider or
> the sidecar). It does not change §8 (the dominant root) or the §10 binding summary.

## 11. Open-Q3 — the durability bug is real (and broader than a force-kill)

The fingerprint (`embedding_model_sha256`) is persisted into the **Lucene commit user-data**, and the
stamp commit itself *is* atomic and durable (`CommitOps.java:96-108`). The bug is **temporal ordering**
(audit B):

- During `REBUILDING`, `fingerprintToStamp()` returns **empty** unless `rebuildCompleted`
  (`EmbeddingCompatibilityController.java:257-268`). Ordinary commits *during* the rebuild write the new
  vectors but leave the commit user-data **without** the fingerprint.
- `rebuildCompleted` flips true only when **`queueDepth==0 && pendingEmbeddingCount==0`**
  (`:198-209`), and the dedicated stamp commit (`INDEXING_LOOP_REBUILD_STAMP`) is issued *only* from
  inside the running loop via `tryFinalizeRebuild()` (`EmbeddingProviderLifecycle.java:214-247`;
  `IndexingLoop.java:556,611`). The **shutdown path never finalizes** — it uses
  `INDEXING_LOOP_SHUTDOWN` (`IndexingLoop.java:642-654`), whose overlay returns empty while
  `REBUILDING`.
- **Therefore any stop — graceful OR force-kill — between "started writing rebuilt embeddings" and "the
  terminal drain-gated stamp commit lands" loses the fingerprint** and re-derives `BLOCKED_LEGACY` on
  restart (the vectors persist; only the *compat fingerprint* is lost, which is what re-blocks dense).
  593 §H's "may be a force-kill artifact" caveat is only half right: on a large corpus the backfill
  window is long, so a *graceful* quit mid-backfill loses it too. (Consistent with 593: its generation
  "switched in" — a separate blue/green migration step in `IndexGenerationManager`, distinct from the
  fingerprint stamp — while `embed queue:592` was still draining; stopping then = no stamp = revert.)
- **Why the live stack is fine now:** generation `g-…125203`'s backfill evidently *did* fully drain and
  stamp before any restart, so it is durably `COMPATIBLE`. The bug is a *window*, not a permanent loss —
  but it is a real correctness gap (a structural defect per `structural-defects-no-repeat`: one
  documented silent revert proves the class).

## 12. Open-Q4 / Q5 — intended path, and the 588 boundary

- **Q4 (intended activation path).** There is no product-level choreography that takes "indexed folder
  → a dense query runs." The *documented promise* is unconditional — help text states "With AI models
  installed, JustSearch uses hybrid retrieval combining keywords with semantic understanding"
  (`SSOT/docs/help/getting-started.md:26`, `search-syntax.md:34`) and `auto_embed:true` /
  `ann_k:200` sit in the live `search.hybrid` config — i.e. the *design intent is hybrid-by-default*,
  but the FE→Head request shaping never honors it (§8). So #5's "no guidance" is real, but the deeper
  issue is there is no dense path to guide *to* from the UI; guidance is necessary-but-insufficient.
  (Both drift findings logged to `docs/observations.md` Inbox.)
- **Q5 (588 boundary).** 588 *shipped* (2026-06-15, commit `95130b014`) fixing the **silent-liveness**
  class — an uncaught `Error` killing the loop while the wire still reports `RUNNING`. 593 §D's "wedge"
  (GPU util 2%, frozen counters, unresponsive worker after Force-Rebuild *in Online mode*) is a
  **distinct** mechanism: GPU-starvation of the embedder (mechanism #2), not an uncaught `Error`. So
  §D ≠ the 588 class; 588 makes such a stall *more honestly observable* but does not cause/fix it. The
  §D wedge is a symptom of the §10 blanket-eviction policy (Force-Rebuild needs the embedder, Online
  holds the GPU, embedder can't load → stall).

## 13. Re-framed problem (supersedes §1, for design purposes)

Ordered by impact, the real roots are:

1. **R1 — Dense is never requested by the UI (dominant, hardware-independent).** The FE→Head search path
   always resolves to the TEXT preset; hybrid is reachable only via the agent tool / eval. Semantic
   search is unreachable from the search box *by construction*, regardless of #1-#5. Proven live.
2. **R2 — `BLOCKED_LEGACY` requires a forced reindex (real, but resolves once embeddings are built).**
3. **R3 — Fingerprint-stamp durability window (real ordering bug).** Stamp is deferred to a terminal,
   full-drain-gated commit never issued on shutdown ⇒ interruption mid-backfill reverts to legacy.
4. **R4 — Blanket GPU eviction policy (real, narrow).** Unloads the GPU embedder whenever Online,
   independent of VRAM, blocking the *simultaneous chat+dense (RAG)* case; dissolves with a CPU embedder
   or VRAM-aware co-residence.
5. **R5 — No activation guidance + scary/contradictory rebuild transition** (mostly owned by 595/596).

---

# PART III — LONG-TERM DESIGN (2026-06-17)

> Genre: design-theory (per 557/559/567/570/577 — correct long-term structure, general not
> implementation-level; feasibility/phasing deliberately set aside). Supersedes the preliminary §14
> sketch. The design is **scoped to the three roots the problem actually has** (R1, R3, R4) and
> deliberately adds no structure for cases 598 does not contain (§22). Where a usable mechanism exists,
> the design **extends** it rather than replacing it (§17).

## 16. Design thesis (one paragraph)

The retrieval pipeline shape is currently authored as a **caller assertion** — the request declares a
fixed `(sparse, dense, splade)` triple — but the *truth* of whether the dense leg can run is **Worker
state** (is the index `COMPATIBLE`? is the query embedder serviceable?). That authority inversion is
the disease: the place that decides (FE/Head, via a static TEXT preset) does not know the facts, and the
place that knows (the Worker's embedding-compat boundary) is only allowed to *subtract* legs that were
requested, never to *add* the dense leg when it is available. The correct long-term shape restores the
authority to where the facts are: **the default retrieval pipeline is a projection of retrieval
capability, resolved at the single Worker authority that already owns that capability, and callers
express *intent* (AUTO = "retrieve as well as the engine currently can" vs an explicit override) rather
than asserting capability.** R3 and R4 are corollaries of the same "represent the fact where it is
true" principle applied to the embedding lifecycle: the embedding *provenance* fingerprint must be
co-durable with the vectors it certifies (not a deferred separate fact), and the GPU arbitration must
distinguish the two embedding *workloads* it currently conflates (bulk backfill vs a single query
embed) because they have different VRAM/latency truths.

## 17. What already exists that the design extends (inventory)

The expensive substrate is already built; the design is mostly *wiring an authority that is missing one
edge*, not new machinery.

- **The capability-aware auto-hybrid pattern already exists — on the RAG path only.**
  `RagContextOps.searchChunksWithMeta` honors `rag.retrieveMode="auto"`: it attempts dense iff
  `embeddingProvider.isAvailable() && allowQueryEmbeddings`, else silently falls back to BM25
  (`RagContextOps.java:418,429`, audit §4). **This is precisely the R1 decision, already implemented and
  in production for grounded Q&A.** R1 is "lift this pattern from the RAG path to the general search
  wire," not invent it.
- **The single dense-serviceability authority already exists.** The Worker's `EmbeddingCompatBoundary`
  (`allowQueryEmbeddings()` + the mapped reason code) is the crisp "can dense run right now" fact, read
  in both the planner (`SearchPlanner.java:84-93`) and input capture (`SearchInputCapture.java:151-175`).
  The design routes the AUTO default through this same boundary — no new readiness signal.
- **The planner already does half the symmetry.** `SearchPlanner.selectLegSet` already *degrades*
  hybrid→sparse when vector encoding fails (`hybridFallback`). The design adds the missing *promote*
  edge; the safety-net degrade stays as-is and covers the capability-check-vs-encode race.
- **The single search-issuance seam exists and is gated.** `buildSearchIntent` + the
  `check-search-issuance` gate (570 Move H / 577 §1.10) already force one query-construction site. The
  design works *inside* that seam (the refined pass already sends "no pipeline"), so it does not create a
  second issuer.
- **A reserved-but-dead config name already exists.** `search.hybrid.auto_embed:true` is registered and
  never consumed (audit §4) — an earlier, abandoned attempt at exactly this decision. The design either
  completes its intent or removes it; it does not add a new flag.
- **Completeness is already tracked separately from compatibility.** The embed queue / `CHUNK_EMBEDDING`
  readiness dimension already expresses "how much of the index is embedded" independent of the
  fingerprint. R3 leans on this existing split rather than inventing a progress signal.

## 18. R1 — capability-derived default pipeline ("AUTO")

**The cut.** Three caller intents, not one fixed triple:

1. **AUTO (the default; what "no explicit pipeline" means).** "Retrieve as well as the engine currently
   can." Resolved at the Worker against the embedding-compat boundary: if dense is serviceable, run
   hybrid (sparse + dense, existing fusion); else run sparse — silently, no error, and **without paying
   the query-embed cost** when dense isn't serviceable.
2. **Explicit override** (`mode:hybrid` / `mode:text` / a concrete `PipelineConfig`). Unchanged. Eval
   (jseval), the agent `SearchTool`, and any debug/force path keep asserting an exact pipeline. AUTO
   never overrides an explicit choice.
3. **Quick/keystroke pass** stays explicit-sparse (the FE's `QUICK_PIPELINE`) — instant-feedback latency
   forbids a per-keystroke encode. AUTO belongs to the *refined* pass (settle/Enter), which already
   sends "no opinion."

**Where the authority lives.** The resolution AUTO→concrete-pipeline happens **once, at the Worker**,
because (a) that is the only place that authoritatively knows `allowQueryEmbeddings` at query time
(avoiding the Head's stale-status TOCTOU), and (b) the query-embed trigger is already Worker-side
(`SearchInputCapture` decides `wantDense` *before* encoding — audit §1 — so promotion must happen there,
not post-hoc in the planner). Concretely the design generalizes the RAG `auto` rule into the
`wantDense` derivation: for an AUTO request, `wantDense := compatBoundary.allowQueryEmbeddings() &&
embeddingProvider.isAvailable()`; everything downstream (encode → planner `selectLegSet` → fusion) is
unchanged. This makes capability→pipeline a **single symmetric authority** (promote *and* demote in one
place), which is the anti-drift property the codebase already enforces elsewhere (553 execution-surfaces
register, 595 single-derivation).

**Why not the alternatives** (each rejected for a concrete reason, not taste):
- *FE-gates-on-readiness* (the refined pass sets `mode:hybrid` when `aiStateStore` says ready): puts the
  capability→pipeline decision in the FE, where readiness is a **stale display projection** that
  *deliberately excludes embedder availability* (audit §3 — `composites['retrieval']` caps
  `CHUNK_EMBEDDING` so dense-unavailability cannot fail it). The FE would assert a capability it can't
  see, and the decision would then have to be re-checked at the Worker anyway → two authorities. Also
  forks per-surface (search box, future retrieve tier, RAG) — exactly the drift 577's convergence fights.
- *Head-defaults-to-HYBRID-when-COMPATIBLE* (`expandPreset` consults worker status): better (single
  default site) but splits the symmetry across two processes — the Head *promotes* from a cached status
  poll while the Worker *demotes* — and reintroduces a TOCTOU window the Worker already has the facts to
  avoid. Acceptable as a pragmatic stopgap, but not the correct long-term home.

**Contract change (minimal, warranted).** The wire must be able to say "AUTO / unspecified" distinctly
from "explicitly sparse-only." Today "no pipeline" collapses to TEXT at the Head (audit §2). The design
stops that collapse: "no explicit pipeline and no explicit mode" is preserved to the Worker as AUTO and
resolved there; the Worker's existing `modeToDefaultPipeline` fallback becomes the AUTO resolver instead
of a TEXT constant. This is the one genuinely new representational element, and it is required — the
present contract *cannot express* "let the engine decide," which is the whole problem.

**What this fixes and aligns.** Semantic search becomes reachable from the UI by default, honoring the
help-doc promise (§12). It also aligns user-facing quality with what the 580 relevance ratchet already
measures (eval runs `mode:hybrid`; today users get sparse — the ratchet guards a pipeline real users
never hit). And it lands inside 577's "retrieve base tier" cleanly: the base tier simply executes AUTO.

## 19. R3 — fingerprint provenance must be co-durable with the vectors (not deferred)

> **⚠ SUPERSEDED IN PART IV.** The de-risking pass (PART IV §27) found the "stamp `COMPATIBLE`
> early" mechanism below is **unsafe for the in-place forced-reindex path** (it would serve dense
> over a mixed old/new-model index). The *problem framing* (provenance ≠ completeness; the rebuilt
> work must survive a restart) stands; the *fix* is redirected to reusing the existing blue/green
> generation mechanism. Read §27 before acting on this section.

**Root design error.** `EmbeddingCompatibilityController.fingerprintToStamp()` returns empty until
`rebuildCompleted` (queue *and* pending both drained), so the model fingerprint is written only by a
single terminal commit that the shutdown path never issues (§11). This **conflates two distinct facts**:
- **Provenance** — "these embeddings were produced by model X." True from the *first* rebuilt-and-written
  doc. This is what the compat fingerprint actually certifies.
- **Completeness** — "every doc in the index has an up-to-date embedding." A monotonic progress fact,
  already tracked independently by the embed queue / `CHUNK_EMBEDDING` readiness (§17).

**The design.** Stamp the model fingerprint into the **same commit that first persists rebuilt
embeddings** (provenance is known at write time), so vectors and their provenance are always
co-durable — there is no window in which embeddings exist on disk without their fingerprint. Mark the
index `COMPATIBLE` on that provenance fact; surface "still embedding N docs" via the existing
completeness signal, not by withholding compatibility. Dense then runs over a possibly-partial index —
which is **strictly better than blocking dense for the whole backfill**, because hybrid already mixes
sparse (unembedded docs still match via BM25), i.e. graceful degradation by construction. This removes
the durability window for *any* interruption (graceful or forced), not just force-kill.

**The one thing to verify before committing** (`audit-driven-fixes-need-test`): that no consumer treats
`COMPATIBLE ⟺ fully-embedded` (the conflation may be load-bearing somewhere). If one does, the fix is to
point it at the completeness signal, not to keep the deferred stamp. Closure needs a regression test that
interrupts mid-backfill and asserts `COMPATIBLE` survives a restart.

## 20. R4 — unbundle query-embed from bulk-backfill in the GPU arbitration (scoped extension)

**Root design error.** `main_gpu_active` is a single blanket boolean that the Worker translates to
"unload the GPU embedder entirely" (§10). It conflates two embedding *workloads* with opposite
truths:
- **Bulk backfill** (embed thousands of docs): sustained, VRAM-heavy, genuinely *can* be deferred while
  the user chats. Pausing it under Online is ADR-0004's real and correct justification.
- **Query embed** (one short query → one 628 MB-model forward pass): tiny, bounded, latency-critical,
  and *needed during chat* for RAG retrieval. Evicting the embedder for this is the catch-22.

**The design.** Keep ADR-0004's mutual exclusion for **bulk backfill**; keep **query embed serviceable
while Online**. Two admissible mechanisms (both narrow, neither is the "arbitrary VRAM budgeting"
ADR-0004 rightly rejected): (a) a **bounded query-embedder reservation** that co-resides — 628 MB + 9.7 GB
≈ 10.3 GB fits in 12 GB, and because it is *one fixed-size model*, not arbitrary budgeting, ADR-0004's
rejection reasoning does not apply; or (b) a **CPU fallback for the query embedder only** while Online
(2-5 s for a single query is acceptable for a grounded answer; bulk backfill on CPU is not). This
**narrows ADR-0004 rather than overturning it**, so it requires an ADR amendment ("mutual exclusion
governs bulk GPU work; a bounded query embed is exempt"), and ADR-0004 is already marked partly
superseded.

**Scope boundary — why R4 is a separable, lower-priority extension.** R4 affects *only* the simultaneous
chat-Online + dense-retrieval (RAG) case. Standalone semantic search does not need it (it runs in
Indexing/Offline mode, and the live stack shows it even coexists with Online today). So R1 alone makes
598's headline problem — semantic search unreachable — go away; R4 is the quality fix for RAG retrieval
*during* chat. It is in the design because it is the same conflation disease, but it is explicitly
optional for v1, gated on the §10 unresolved sub-point (CPU-vs-sidecar embedder on a default install),
which should be resolved with a cheap probe before any R4 work.

## 21. R2 / R5 (not core design; pointers)

R2 (`BLOCKED_LEGACY` ⇒ forced reindex) is the *correct* behavior given a legacy index — the design need
is only to make the reason and the remedy legible, which is presentation (596) plus the §19 durability
fix so the remedy *sticks*. R5 (scary/contradictory rebuild transition) is owned by 595/596. Neither is
new structure for 598; they are consumers of R1/R3 landing.

## 22. Scope discipline — what the design deliberately does NOT add

- **No new readiness/capability service.** AUTO reuses the existing `EmbeddingCompatBoundary`; no second
  source of "is dense serviceable."
- **No generalized pipeline-policy engine** (per-query-class routing, learned pipeline selection, QPP-gated
  dense, etc.). 598's problem is "dense is never on"; the design turns it on by capability. Adaptive
  *weighting* of legs already lives in 580 (adaptive fusion); the design does not duplicate or pre-empt it.
- **No multi-GPU / VRAM-tier branching for R4.** The problem is single-GPU conflation of two workloads;
  the design unbundles those two workloads, nothing more. Concurrent-loading budgeting stays rejected.
- **No revival of the deprecated `SearchMode` enum as the AUTO carrier** if "absence of explicit pipeline"
  already expresses it — the design prefers the existing absence-semantics over resurrecting a deprecated
  field.
- **No re-modelling of the RAG `retrieveMode`** — R1 generalizes its *rule*, it does not merge the two
  call sites into a premature shared abstraction (AHA: unify only when they share a reason to change; for
  now they share a rule, which the compat boundary already centralizes).

## 23. How this composes with adjacent work

- **577 (window convergence):** R1 is what the "retrieve base tier" should *execute*; it strengthens
  577's thesis (one entry point, escalating intent) by making the base tier actually semantic.
- **580 (relevance ratchet):** R1 makes the hybrid pipeline the eval already guards the *default* user
  experience — closing the gap between measured and delivered quality.
- **595 (verdict authority):** the design respects 595's boundary — it does **not** route issuance through
  the display verdict (which excludes embedder availability by design); issuance authority stays at the
  Worker compat boundary.
- **553 (execution-surfaces / representation register):** AUTO makes capability→pipeline a single
  symmetric authority, consistent with the register's anti-fork discipline.

## 24. Open decisions for the user

1. **Tempdoc scope:** adopt 598 as the owner of **R1 (capability-derived default pipeline)** as primary
   deliverable, with **R3** as a tracked correctness follow-up and **R4** as an optional, separately-gated
   extension? (Recommended — R1+R3 = "semantic search becomes reachable *and stays* reachable.")
2. **AUTO carrier:** confirm preference for "absence-of-explicit-pipeline ⇒ AUTO" (no new enum) vs a
   first-class `SEARCH_MODE_AUTO`. (Recommend the former — minimal contract surface, uses existing
   absence-semantics.) — *PART IV note: this choice also sets the test-assertion blast radius; see §28.*
3. ~~**R3 pre-check:** authorize the "does any consumer assume `COMPATIBLE ⟺ fully-embedded`" audit~~ —
   **DONE in PART IV §27** (no consumer makes that assumption; the real hazard is mixed-model serving on
   the in-place path). The corrected R3 question is now §29.
4. **R4 appetite + the §10 probe:** — *PART IV note: the §10 probe is **DONE** (§27.5: in-process embedder,
   GPU by default, the `workers.ai` sidecar is inert). R4's catch-22 is confirmed real-but-narrow.*

---

# PART IV — DE-RISKING RESULTS (2026-06-17)

> The PART III design was stress-tested by a confidence-building pass (plan: de-risk before
> implementing): four parallel source-cited audits (A1 result-shape/FE, A2/A3 callers+AUTO pipeline,
> A4 generation↔fingerprint, A5 embedder placement) + read-only live probes. One finding is
> **design-invalidating for R3 as written** (§27.4); the rest **raise** confidence in R1 and scope R4.
> No feature code was written. Per-root confidence ratings + the one remaining de-risk are in §30.

## 25. R1 — FE result-shape risk is LOW (the biggest worry, retired)

The fear was that turning on hybrid would feed the search surface **chunk-level** hits it can't render.
Resolved against `main` + live:
- **The engine already collapses chunks→docs for both paths.** `chunk-merge` runs for the
  `SparseShortcut` *and* the hybrid `MultiLegDecision` (`SearchPlanner.java:141-144,186-190`);
  `SearchExecutor.collapseChunkHitsToParents` (`:866-902`) rebuilds each hit with the **parent doc id**
  and carries the winning chunk's `chunk_heading_text`/lines forward as passage metadata in `fields`.
- **Live proof:** a `mode:hybrid` query (`"suicide pact heartbeat worker self terminate"`) returned
  **doc-level ids** (`…\0002-grpc-mmf-hybrid-ipc.md`) with `fields.path` populated and
  trace `[sparse-retrieval, fusion, chunk-merge, branch-fusion, cross-encoder]` — the same shape the FE
  already renders. Latency 680–698 ms ≈ the sparse query (cross-encoder-dominated; the query-embed add is
  negligible here).
- **The agent `SearchTool` already runs HYBRID by default** and renders results fine
  (`SearchTool.java:79`, `buildSearchEvidence` reads `fields.path` + chunk metadata) — an existing
  proof that the doc-level-with-chunk-metadata shape works.
- **Edge case (narrow):** a *pure-dense* result set with no sparse overlap can surface raw `chunk:` ids
  (my first probe did). The FE `SearchHit` mapping falls back `path = fields.path ?? r.id`
  (`searchState.ts:492`), so a `chunk:`-id hit lacking `fields.path` would render a broken filename. **AUTO
  avoids this by construction** — it targets BM25+dense (so `chunk-merge` always has doc hits to collapse
  into), never dense-only. (A defensive FE fallback for raw `chunk:` ids is a small hardening, logged as an
  observation, not an R1 blocker.)

**Verdict:** R1 is engine-/Head-side; no FE rendering work is required for the common path.

## 26. R1 — blast radius is clean; AUTO pipeline is precisely defined

- **Only the FE refined pass relies on the TEXT default** (`searchState.ts:328-331`). The quick pass pins
  `QUICK_PIPELINE` (explicit sparse — keystroke latency preserved); the agent `SearchTool` and MCP search
  send explicit HYBRID; RAG uses its own `retrieveMode="auto"`; eval sends explicit modes. So flipping the
  default is a no-op for every non-UI caller (audit A2).
- **AUTO target = BM25 + dense, RRF fusion, cross-encoder when the reranker is ready, NO SPLADE.** SPLADE
  is fragile (extra query-time model on the same constrained GPU; degrades to `Bm25Dense` when it can't
  encode), and 580's adaptive-fusion selector only runs on the three-leg CC path (default-off), not on
  `Bm25Dense`/RRF — so AUTO gets stable, well-trodden behavior (audit A3).
- **The planner already degrades** a wanted-dense query to sparse when the vector can't encode
  (`hybridFallback`), so AUTO is safe when embeddings aren't serviceable — it just needs to *not request*
  the encode when the compat boundary is closed (to avoid cost), which is the RAG `auto` rule generalized.

## 27. R3/R4 — the generation/fingerprint truth (the decisive audit)

- **Two rebuild mechanisms exist.** (A) **Blue/green migration** — a separate `g-<timestamp>` generation
  built while the old one serves read-only, switched in atomically only when complete + verified, then a
  worker restart (`IndexGenerationManager.startMigration:197-216`; `KnowledgeServerMigrationOps.java:208-234`).
  (B) **In-place forced reindex** — `searchLifecycle == ingestLifecycle` (`KnowledgeServer.java:499-500`);
  re-embeds in the *served* generation.
- **27.4 — the design-invalidating finding.** Forced reindex uses path **(B)**, so during the rebuild the
  *served* index transiently **mixes old- and new-model vectors**. Garbage similarity is prevented **only**
  by the ECC holding `allowQueryEmbeddings()==false` until `queue==0 && pending==0`
  (`EmbeddingCompatibilityController.java:198-209,244-246`). **Therefore PART III's "stamp `COMPATIBLE`
  early" is unsafe** — it would let dense queries run over the mixed index. The deferred stamp is doing real
  safety work, not just provenance bookkeeping.
- **27.5 — no consumer assumes `COMPATIBLE ⟺ fully-embedded`** (audit A4 enumerated them: `allowQueryEmbeddings`,
  `allowEmbeddingWrites`, the boundary record, status reporters — all treat it as the provenance fact
  "present vectors are current-model"). Completeness is tracked independently (embed-PENDING count +
  `queueDepth`). So the provenance/completeness *distinction* is sound; only the proposed *mechanism* was wrong.
- **27.6 — R4 confirmed real but narrow.** Query embedding is 100% **in-process** in the Worker; the
  `workers.ai`/port-50061 "sidecar" has **no embedding client** — it's inert config (audit A5). The embedder
  is **GPU by default** on a CUDA box (`GpuAutoDetection` → master switch → `resolveEmbedGpuEnabled`), so the
  chat-Online→embedder-unload catch-22 is mechanically real — but **moot for UI search** (which never
  requests dense) and binds only on RAG + agent/eval. The §10 sub-point is settled (in-process, not sidecar).

## 28. R1 — known test blast radius

Flipping the default touches assertions that pin the current mapping: `PipelineConfigPresetExpansionTest`,
`KnowledgeHttpApiAdapterHarmfulCombinationsTest`, and `SearchToolTest` (asserts `req.mode()==null`). Whether
they break depends on the §24.2 carrier choice (changed-default vs new `SEARCH_MODE_AUTO`). These are
*expected* updates, not surprises — but they scope the change.

## 29. R3 — corrected design direction (reuse blue/green, don't stamp-early)

The right long-term fix keeps the PART III *framing* (the rebuilt embedding work must survive a restart;
provenance ≠ completeness) but changes the *mechanism* to **reuse the blue/green generation path that
already exists** (mechanism A) for the forced embedding reindex:
- Build the re-embedded index as a **separate generation** while the old one keeps serving (sparse-only /
  its existing compat state — honest degraded keyword search during the rebuild). No mixed-model vectors are
  ever *served*.
- Stamp the new generation's fingerprint into **its own** commits as it builds (co-durable there — safe,
  because that generation isn't served yet), and switch it in atomically only when complete.
- A restart mid-rebuild then **resumes/retains the building generation** (as schema migration already does)
  instead of reverting the *served* index to `BLOCKED_LEGACY` and discarding progress.

This reuses `IndexGenerationManager` + the migration cutover rather than inventing a durability protocol —
matching the "extend what exists" discipline. **It needs one more feasibility investigation before it's
plannable** (§30): can the forced *embedding* reindex be expressed as a generation migration (disk 2×,
resume-on-restart of a building generation, and the trigger path), or is in-place rebuild load-bearing for
a reason not yet seen? Until that is answered, R3 is *not* ready to implement.

## 30. Confidence ratings & the one remaining de-risk

| Root | Confidence (0–10) | Rationale |
|---|---|---|
| **R1** (capability-derived AUTO default) | **8** | FE risk retired (§25), blast radius clean (§26), AUTO precisely defined, planner already degrades. Residual: the §24.2 carrier choice + test updates (§28) and a live "AUTO degrades gracefully when not COMPATIBLE" smoke. |
| **R3** (durable rebuilt embeddings) | **3 as written / 5 redirected** | PART III mechanism is unsafe (§27.4). The §29 blue/green-reuse direction is sound but needs the feasibility investigation below before planning. |
| **R4** (unbundle query-embed) | **5** | Problem confirmed real + narrow (§27.6); optional. The unbundle direction is sound but co-residence VRAM safety is unproven and it amends ADR-0004 — defer until R1 lands. |

**Remaining de-risk before R3 implementation** (the only Tier-C-ish item left): confirm whether forced
embedding reindex can route through the blue/green generation mechanism (static feasibility read of
`IndexGenerationManager` trigger paths + a live interrupt-mid-rebuild→restart repro on an **owned** stack to
witness the BLOCKED_LEGACY revert and validate the fix target). This is best folded into R3 implementation as
the regression test (`audit-driven-fixes-need-test`), and needs an owned dev stack (coordinate, don't take
over).

**Net:** R1 (the dominant root) is ready to implement with high confidence. R3 needs a short follow-up
design pass (blue/green feasibility) before it's safe to build. R4 is a sound, optional, deferrable extension.

---

# PART V — USER-FACING / FRONTEND DESIGN (2026-06-17)

> Method: re-read PART III/IV for the user-visible surface, then inspected the **live** search UI in the
> browser (read-only) against the running stack, and read the FE wording/seam code
> (`readinessNotice.ts`, `SearchSurface.ts`). The design below is grounded in what the UI *actually does
> today*, not the tempdoc alone. General/design-tier, no implementation.

## 31. What is user-visible in this design

- **R1 (capability-derived AUTO default) — directly user-facing.** Changes what the search box returns
  (keyword → semantic/hybrid), and — critically — whether the UI's *claims* about retrieval match what it
  *delivers*.
- **R3 (durable rebuilt embeddings via blue/green) — indirectly user-facing.** Changes observable
  behavior during a rebuild (search stays up vs the 593 §C "503 / 0 docs / No watched folders" scare) and
  stops the "Reindex required" loop from recurring after a restart. *Presentation* of the transition is
  owned by 595/596; R3 changes the underlying state they present.
- **R4 (unbundle query-embed) — indirectly user-facing.** Affects grounded-Q&A (Documents tab) answer
  quality: semantic retrieval *while chatting* instead of the keyword ceiling 593 ADDENDUM 2 hit.

## 32. Live UI findings (browser, read-only, against `main`)

1. **The UI now CLAIMS semantic while delivering keyword — the inverse of the 593 complaint.** With the
   index `COMPATIBLE` and inference Online, the search surface shows a calm banner: **"Reduced search
   capability. An optional capability is unavailable; results are still fully semantic."** (source:
   `readinessNotice.ts:200-204`, the `severity:'info'` branch — here because LambdaMART is unconfigured).
   But per PART II/IV the box issues **keyword-only**. So the FE asserts "fully semantic" for results that
   never ran the dense leg. 595/596 fixed the *over-claim in the other direction* (it no longer cries
   "degraded" for a cosmetic gap) — but in doing so it now *under-warns*: it reassures "fully semantic"
   without semantic issuance.
2. **The loading state over-claims AI involvement:** a plain keyword query renders **"Searching — the AI
   is working on your query…"** — implying semantic/AI work for a sparse-only request.
3. **The search surface wedges on "Reconnecting…" and will not render results** even though the same
   backend answers in <1 s via the API (reproduced 593 §S1 live; the shared stack's SSE/status connection
   flaps and the surface gates result render on it). This is a reliability/observability issue **owned by
   595/596**, but it *compounds* R1: semantic results a user finally gets can still be hidden behind a
   stuck connection state.
4. **The result-anatomy substrate already exists and is mode-aware.** Per-hit "Why this result?"
   disclosure renders the contributing legs from the trace (`renderWhyDisclosure`, the one 577 stage-label
   vocabulary), and `matchCountLabel` already switches "matches" vs "ranked" on the response's *effective
   mode* (`rankedOnly`, 597 §8.3). So the FE already models "which retrieval ran" — it is simply always
   lexical today because the box never requests dense.

## 33. The core UX defect R1 must fix: three layers that disagree

There are **three independent statements about retrieval mode** in the search UI, and they currently
contradict each other:

| Layer | Authority today | Says today |
|---|---|---|
| **Issuance** (what the box asks for) | `searchState.buildSearchIntent` — pass-stage only | keyword (TEXT preset) |
| **Banner / verdict** (what the UI claims) | `readiness.retrieval` → `computeVerdict` → `readinessNotice` | "results are still fully semantic" |
| **Per-result trace** (what each hit shows) | the response's leg trace | `sparse-retrieval` only |

The disease (PART II §16) at the *presentation* layer: the banner reads retrieval **capability**, issuance
ignores it, and the per-result trace reflects issuance — so the user is told "semantic," gets keyword, and
(if they expand the trace) sees keyword. **R1's user-facing essence is to collapse these three onto one
authority:** the same retrieval-readiness that the banner already consumes must also drive *issuance*
(AUTO), and the response must report its *effective mode* so the banner and the per-result trace both
describe what actually ran. After R1 the banner's "fully semantic" becomes **true**; when AUTO degrades
to keyword (embeddings building / not ready), the existing impairing-degradation banner ("Showing keyword
results", `readinessNotice.ts:207-212`) fires **for the same reason issuance fell back** — honest by
construction, no new banner invented.

## 34. The correct frontend design (general)

1. **One retrieval-mode truth, surfaced glanceably.** The response already carries an effective mode (the
   `rankedOnly`/effective-mode the count label reads). Promote it to a small, always-present **retrieval
   indicator** above the results — "Semantic + keyword" vs "Keyword only" — so a non-power-user knows which
   tier ran without expanding the power-user trace. It must be projected from the **response's effective
   mode**, never a second client-side guess (single authority — same discipline as 595's verdict gate).
   This is the one genuinely-new affordance; everything else is reuse.
2. **Reconcile the banner with issuance (no new banner).** Once issuance is AUTO (capability-derived), the
   *existing* `readinessNotice` wording becomes accurate automatically: "fully semantic" when AUTO runs
   hybrid; "showing keyword results" when AUTO degrades — because both the banner and the fallback now read
   the same `readiness.retrieval`. The fix is connecting issuance to that authority, not rewording the
   banner. (If anything, the `severity:'info'` "still fully semantic" copy should be re-derived from the
   issued effective mode, not from capability alone — so it cannot claim semantic when AUTO chose keyword.)
3. **Per-result trace & passage context become meaningful for free.** The "Why this result?" chips and the
   pipeline-details trace already render whichever legs ran; after R1 they show dense/fusion instead of
   `sparse-shortcut` — no structural change. Optionally surface the matched **passage/heading**
   (`chunk_heading_text`, present on hybrid hits per PART IV §25) as the result's snippet, richer than the
   doc-level preview. Enhancement, not required.
4. **Honest loading language.** Word the in-flight state by the *intended* tier ("Searching…" / "Searching
   semantically…") rather than a blanket "the AI is working on your query," so it does not over-claim when
   AUTO will run keyword. Small wording fix (logged to observations).
5. **The quick→refined shape stays coherent.** Quick (keystroke) remains keyword for latency; refined runs
   AUTO. The retrieval indicator (#1) makes the transition legible ("Keyword" instant → "Semantic +
   keyword" on settle) instead of a silent, unexplained result-set change.

## 35. R3 / R4 user-facing notes

- **R3 (blue/green forced reindex)** turns the rebuild from 593's *"search goes down, index looks wiped,
  then the warning returns after restart"* into *"search stays up on keyword while semantic rebuilds in
  the background, then upgrades — and stays upgraded across restarts."* The honest in-progress state
  (`chunk_embedding.in_progress` → "Passage embeddings are still being computed", `readinessNotice.ts:80-85`,
  severity `info`) already exists; R3 makes it the *truthful* description of a non-destructive background
  rebuild. 595/596 own how that transition is drawn; R3 is what makes the drawn state calm and true.
- **R4 (query-embed during chat)** improves the Documents-tab answer: semantic retrieval while the chat
  model is loaded means on-topic passages and better grounding labels, instead of 593 ADDENDUM 2's
  keyword-retrieved, sometimes-off-topic context. The grounding/citation UI is unchanged; its inputs improve.

## 36. Scope discipline (frontend)

- **One new affordance only** (the glanceable retrieval-mode indicator, §34.1); everything else **reuses**
  existing seams (the `readiness.retrieval` authority, `readinessNotice`, the 577 trace vocabulary, the
  597 mode-aware count). No new banner, no new health surface, no per-result redesign.
- **Out of scope (owned elsewhere):** the "Reconnecting…" render-wedge (§32.3 — 595/596 reliability); the
  rebuild-transition presentation (595/596); the full search/agent window convergence (577 Goal 3).
- **The single-authority rule is the load-bearing constraint:** issuance, banner, and per-result tier must
  all derive from the response's effective mode + `readiness.retrieval` — never independent guesses — or
  the three-way contradiction (§33) reappears under a new coat of paint.

---

# PART VI — CROSS-TEMPDOC COORDINATION (2026-06-17)

> Scan of adjacent tempdocs (within 20 of 598) modified in the last 5 h, plus active worktrees in range.
> In range + freshly-worked: the whole **593→594/595/596/597/598/599/600 cluster** (all spawned from the
> 593 walkthrough). One active worktree: **`596-remaining`** (worktree-596-remaining). Question answered:
> does any of this long-term interfere with 598's remaining work (R1 issuance/AUTO + the PART V FE design;
> R3 blue/green; R4)?

**Verdict: no hard blockers — 598 is the upstream root the others defer to — but there are three
same-authority coordination points and one genuine long-term risk.**

## 37. The interference map

- **No conflict, favorable (598 is upstream):** **599** (folder "searchable?" — explicitly *pending 598*)
  and **600** (its root causes (c) restart-revert / (d) "UI never requests dense" are *owned by 598 R3/R1
  and disappear when 598 lands*) both **defer to 598**. **597**'s `matchCountLabel` is already
  *mode-aware* (`rankedOnly`/effective-mode) — it is exactly the consumer R1's hybrid default needs;
  favorable substrate. **595** (verdict single-authority) is the *foundation* PART V builds on — a
  land-595-first dependency, not a conflict.

- **Coordination point A — the shared readiness/reason projection** (`aiStateStore.ts` +
  `readinessNotice.ts` `CAUSE_ROWS`/`reasonFor`). Touched by **598** (R1: reconcile the banner with
  issuance + an effective-mode signal), **600** (un-drop the actionable `embeddingCompatReason` that
  `aiStateStore.ts:398` currently discards), and **596** (the `reasonFor`/availability reason seam). Goals
  are complementary, but three docs reshaping one projection = merge contention **and** a risk of
  re-forking the very authority 595 unified. Must land coherently: one wire→FE readiness authority that
  issuance, the banner, the cause-display, and per-affordance reasons all read.

- **Coordination point B — `SearchSurface.ts` / `searchState.ts`.** Touched by **597** (count funnel),
  **598** (issuance in `buildSearchIntent` + the new retrieval-mode indicator, §34.1), and adjacently
  **596** (`UnifiedChatView` retrieve tier). Same-file merge-order coordination; no goal conflict.

- **Coordination point C — `596-remaining` is an ACTIVE worktree** editing the FE control/availability
  layer (`availability.ts` +93, new `CapabilityMap.ts`/`availabilityTelemetry.ts`, `Control.ts`,
  `Shell.ts`, `UnifiedChatView.ts`, Brain/Browse/Health surfaces). It does **not** touch `searchState.ts`,
  `SearchSurface.ts`, `readinessNotice.ts`, or `verdict.ts` directly, so direct file conflict is LOW — but
  it extends the **same reason vocabulary** (`reasonFor`/`CAUSE_ROWS`) 598's banner reconciliation reads.
  Compose with it; do not fork.

- **THE long-term risk (D) — a single definition of "retrieval is semantic-ready."** R1 introduces an
  *effective-mode / dense-serviceable* notion; 599 needs "is a folder searchable (keyword vs vector)?";
  600 needs "the actionable cause when it's not ready"; 594 a chip claiming embedding readiness; 595 the
  `readiness.retrieval` verdict. If each defines "retrieval ready" independently, the cluster re-fragments
  the exact authority 595 was built to consolidate — at a *worse* altitude because it now spans six docs.
  **Mitigation owned by 598:** R1 must define the retrieval-serviceable/effective-mode authority (at the
  Worker compat boundary, projected once to `/api/status`) as the *one* source the whole cluster consumes —
  making this 598's contribution, not just its dependency.

- **Flag to raise (E):** 599 §13 asserts "**598 [is] actively implemented by the stack-holding session**."
  598 is design-only here (no feature code); the dev stack is held by another session (`b9e86b32`). If a
  second session believes it is *implementing* 598, that is a direct double-work hazard — confirm ownership
  with the user before any 598 build begins.

---

# PART VII — AS-BUILT (2026-06-17, worktree-598-capability-retrieval)

> Implemented per the approved plan. Branch: `worktree-598-capability-retrieval` (off latest `main`).
> No merge performed. All verification below was run in the worktree; the final live browser
> verification is deferred (§38.4 — shared dev stack owned by another session).

## 38. What was built

### 38.1 R1 — capability-derived AUTO default (DONE)
The default search pipeline is now a projection of retrieval capability, not a static keyword preset.
- **Wire** (`indexing.proto`): new `PipelineConfig.dense_auto = 13` — "the caller defers the dense leg to
  the engine." Wire-compatible (proto3 bool, defaults false); regen via the protobuf plugin.
- **Head** (`KnowledgeSearchEngine` + `SearchPipelinePresets`): when a request carries neither an explicit
  `mode` nor an explicit `pipeline`, it emits the new `autoPreset` (sparse on, dense deferred, RRF fusion,
  CE per config) with `dense_auto=true` on the wire (`toProtoPipelineConfig(cfg, denseAuto)`), instead of
  collapsing to the TEXT preset. An explicit `mode` (incl. `"text"`) or `pipeline` remains an override.
  **Review Fix C:** `autoPreset` sets `expansion=true` (mirroring TEXT) so AUTO's keyword fallback keeps
  query expansion — without it, AUTO's degraded path regressed vs the prior TEXT default exactly in the
  degraded state where keyword is the only option (expansion is gated Head-side, before the Worker resolves
  dense, so it must be decided here).
- **Worker** (`GrpcSearchService.resolveAutoDense`): the single resolution point — before the orchestrator
  runs, an AUTO request's `dense_enabled` is set to a `denseServiceable` gate, so both `SearchInputCapture`
  and `SearchPlanner` (which independently read `dense_enabled`) see the resolved value, and the existing
  hybrid-fallback covers the residual race. **Review Fix A:** `denseServiceable = compat.allowed()
  (index COMPATIBLE) && embeddingProvider.isAvailable()` — gating on embedder availability too (matching the
  RAG `retrieveMode="auto"` rule, `RagContextOps:431`), so AUTO does not request a query-embed that would
  just fail when the embedder is unloaded (e.g. Online mode).
- **FE** (`SearchSurface.ts`): a glanceable **retrieval-mode indicator** (`renderRetrievalMode`) projected
  from the response's `searchTrace.effectiveMode` (HYBRID → "Semantic + keyword", VECTOR → "Semantic",
  TEXT → "Keyword") — the one honest signal of which retrieval actually ran (no new wire field; reuses the
  effective-mode already wired for the 597 count). The "AI is working on your query…" loading copy was
  softened to not over-claim AI for a keyword pass. The banner authority (595/600) was deliberately not
  forked (§34.2, §33).
- **Tests:** `PipelineConfigPresetExpansionTest` (AUTO preset + `dense_auto` marker), `GrpcSearchServiceAutoDenseTest`
  (resolution: permitted→dense, blocked→keyword, explicit unchanged), `SearchSurface.retrievalMode.test.ts`.

### 38.2 R3 — durable rebuilt embeddings via the existing blue/green migration (DONE structurally)
Investigation found the user **Force Rebuild (`core.rebuild-index`) already routes through
`startMigration`** (durable blue/green: the old generation serves until the green is fully built + verified
+ atomically promoted, and a mid-rebuild restart RESUMES the green). So the PART III "stamp early" idea was
both unsafe (it would have served a mixed-model in-place index — PART IV §27.4) and unnecessary. 593's
"revert after restart" was the *observability* of a resuming migration (blue stays legacy until green
promotes), not lost work.
- **The genuine gap closed:** the green cutover verification did not check the embedding fingerprint, so a
  green could promote without one and still serve `BLOCKED_LEGACY`. `verifyGreenMetadata` (the new IO-free
  core of `verifyGreenCommitMetadataBestEffort`) now requires the green's `embedding_model_sha256` to be
  present AND match the current model — but only when an embedding model is resolvable (a keyword-only
  rebuild still promotes). `KnowledgeServer` threads `embeddingCompatController.currentFingerprint()` in.
- **The in-place `force_reindex` path** (the restart-fragile one) is used only by targeted re-indexers
  (`AgentHistoryIndexer`, bundled help files), NOT the user embedding rebuild. **Review Fix F (confirmed
  non-issue):** `EmbeddingCompatibilityController.onForcedReindexRequested()` only transitions to
  `REBUILDING` from `BLOCKED_LEGACY`/`BLOCKED_MISMATCH` (`EmbeddingCompatibilityController.java:144-151`) —
  it is a **no-op on a `COMPATIBLE` index**. So a targeted `force_reindex` cannot drop a `COMPATIBLE`
  index's fingerprint or its durability; the gap need not be "closed" because it cannot regress the
  durability surface. Verified, not merely deferred.
- **Review Fix E (deterministic stamp — race closed):** the original code relied on the indexing-loop
  thread having already flipped the ECC `rebuildCompleted` before the cutover's `COMPLETE` commit; if it
  hadn't, the green committed without the fingerprint (the cause of the very BLOCKED-after-promote symptom,
  and — with the Fix-above verification added — a cutover failure). The cutover now calls
  `finalizeEmbeddingRebuildBeforeCutover()` immediately before the `COMPLETE` commit:
  `embeddingCompatController.checkRebuildCompletion(queueDepth, pendingEmbeddings)` on the drained green
  deterministically flips `COMPATIBLE` iff fully embedded, so the `COMPLETE` commit stamps the fingerprint
  on the cutover thread itself — no cross-thread race. A not-fully-embedded green is correctly left
  unstamped → verification blocks its promotion.
- **Tests:** `GreenCutoverEmbeddingFpVerifyTest` (match→promote, missing/mismatch→reject, no-model→skip,
  incomplete→reject); `EmbeddingCompatibilityControllerTest` (finalize on a drained green → COMPATIBLE +
  `fingerprintToStamp()` returns the fp; still-pending green → no flip, nothing to stamp — Fix E contract).

### 38.3 R4 — query-embed-while-Online unbundle (DEFERRED, documented — not hacked)
The structurally-correct fix is to split the single embedder into a bulk-backfill role (keep ADR-0004
mutual-exclusion: unload on Online) and a lightweight query-embed role kept serviceable on Online (CPU
fallback, or a bounded resident session). This is **deliberately not implemented now** because: (a) it is
optional and **moot for normal search** — R1 makes semantic search reachable without chat-Online (dense
search runs in Indexing/Offline mode, and the live stack already coexists); it only improves RAG retrieval
*during* chat; (b) it is **safety-sensitive** — it touches the VRAM mutual-exclusion that prevents OOM
(ADR-0004), and a wrong split risks crashing inference; (c) proving it safe **requires live GPU
verification** (co-residence VRAM headroom / CPU-session viability) that cannot be run while the dev stack
is owned by another session. Shipping it unverified would violate "long-term structural, never a hack."
**ADR-0004 amendment direction** (for when R4 is taken up): narrow the mutual-exclusion to *bulk* GPU work;
exempt a bounded query-embed (one fixed-size forward pass) — the "arbitrary VRAM budgeting" ADR-0004
rejected does not apply to a single bounded reservation. Touchpoints: `EmbeddingProviderLifecycle.handleGpuStateTransition`
(don't fully NoOp the query path), `LoopPacingPolicy` (pause only backfill).

### 38.4 Verification status
- **Green (worktree):** `./gradlew.bat build -x test` (incl. `verifyGovernanceGates`); affected module
  tests (`app-services`, `worker-services`, `indexer-worker`, `app-api`, `ipc-common`, `app-agent`)
  BUILD SUCCESSFUL; FE `npm run typecheck` clean + `test:unit:run` 3115 passed; `check-search-issuance` +
  `check-verdict-derivation` gates pass; class-size growth declared (`gates/class-size/.changesets/598-…`).
- **Live verification (2026-06-17, worktree backend on `:33333`, GPU free):**
  - **R1 AUTO proven end-to-end at the API** (the exact `/api/knowledge/search` body the FE POSTs, no
    `mode`): on a `COMPATIBLE` index the default query returns `effectiveMode=HYBRID, decisionKind=multi_leg`
    — **identical to explicit `mode:hybrid`** — while `mode:text` returns `sparse_shortcut`. Re-confirmed
    multiple times.
  - **R1 FE proven live in the browser:** the retrieval-mode indicator renders from `searchTrace.effectiveMode`
    (observed reading **"Keyword"** for `effectiveMode=TEXT`), and the softened loading copy
    ("Searching your documents — almost there…") is live. By construction it renders **"Semantic + keyword"**
    when a `HYBRID` response is shown.
  - **R3 blue/green durable rebuild proven live:** a forced reindex took the index from `BLOCKED_LEGACY`
    (`docCount=16486, no embedding fingerprint`) through a blue/green migration with **inline embedding**
    (`embed=50, ok=50, fail=0` per batch), stamped the fingerprint → **`COMPATIBLE`**, which then flipped the
    default query to `HYBRID`. The exact R1↔R3 dependency (dense reachable only after a durable fingerprinted
    rebuild) was exercised live.
  - **Honest-degradation proven live:** while embeddings were unavailable (offline / pre-rebuild), the default
    query degraded to keyword and the indicator read **"Keyword"** — never silently presenting keyword as
    semantic.
  - **CAPTURED (condition 6 satisfied):** after clearing competing dev stacks and re-establishing the FE's
    SSE connection via a fresh page load (Library → search-query URL — the documented workaround for the
    593-§S1 render-wedge), the **default search box query "shut down the helper when the main app closes"
    rendered a hybrid result** in the real browser UI:
    - the retrieval-mode indicator read **"Semantic + keyword"** (header: "Top 50 of 576 matches · 584ms ·
      Semantic + keyword"; the surface labelled the run **"Hybrid search"**);
    - **Pipeline details showed the dense/fusion legs**: `decision: multi_leg · mode: HYBRID`,
      `Sparse (BM25) executed · 576 docs`, **`Dense (vector) executed`**, `Fusion executed (hybrid) · 152 docs · 22ms`,
      `Cross-encoder executed`;
    - results were conceptually relevant to the paraphrase (architecture / lifecycle docs), not BM25 stopword noise;
    - the **embeddings-unavailable degradation** was also observed live earlier in the session (indicator
      **"Keyword"** + "Semantic search degraded" banner while the embedder was offline/pre-rebuild) — honest, never
      presenting keyword as semantic.
  - Note: the 593-§S1 / 595–596 SSE render-wedge (the surface intermittently stuck on "Searching…"/"Reconnecting…"
    in Indexing mode) is real and made this capture fiddly on a shared, multi-stack machine, but it is a frontend
    reliability bug owned by 595/596, **not** R1 — once the connection was fresh the hybrid result rendered correctly.

---

# PART VIII — FUTURES & RESEARCH (2026-06-17, post-merge)

> Research-genre, idea-only (no commitment, no schedule). What R1+R3 *unlock* now that semantic search is
> reachable by default. Every idea is **grounded in something the engine already has** (often
> built-but-underused) and respects the hard constraints: local-first, single small GPU, and **no
> per-language levers** (ADR-0043/581 — so routing/fusion/feedback must key on language-invariant signals).
> External state-of-the-art was surveyed (sources at the end); the value here is the mapping to JustSearch's
> actual code, not the literature.

## 39. The key unlock
R1 turned "the engine *can* do semantic, but the UI never asks" into "the UI asks for the best retrieval
the engine can run." That makes a whole class of *retrieval-intelligence* and *trust-UX* work suddenly
worth doing — because users will now actually receive (and see the labelling of) dense/hybrid results.

## 40. Top picks (highest leverage, most grounded)
1. **Query-adaptive routing (B1)** — the natural next step after capability-derived: from "CAN dense run"
   to "SHOULD dense lead *for this query*."
2. **Local relevance-feedback loop (C1)** — clicks already become better ranking, on-device, per user.
3. **Matryoshka two-stage dense (B3)** — a tiny truncated query vector that also makes R4 nearly free.

## 41. (I) Polish / finish what shipped
- **Single "retrieval-ready" authority (finish 598 §33/§34 risk D).** Today issuance (R1), the degradation
  banner (595/600), and the per-result indicator each read retrieval-readiness slightly differently. Collapse
  them onto one signal so "is semantic ready?" has exactly one answer across the box, the banner, and the
  label. *Grounded:* the pieces exist (the Worker compat boundary + `readiness.retrieval` + the indicator);
  this is wiring, not new structure. Coordinate with 595/600. Low–medium.
- **Indicator plain-language tooltip.** "Semantic + keyword" → a one-line "matched by meaning *and* by words"
  on hover. Trivial; pure clarity.

## 42. (II) Extend retrieval quality (engine)
- **B1 — Query-adaptive routing/weighting.** The engine already computes QPP signals (`maxIdf`, `avgIctf`,
  `queryScope`) and ships them in `SearchTrace`, but the AUTO pipeline is a *fixed* BM25+dense RRF. Use those
  signals to set the dense/sparse balance *per query*: a distinctive high-`maxIdf` query leans BM25; a vague,
  low-IDF, conceptual query leans dense. Research (Dynamic-Alpha-Tuning / query-specificity weighting) reports
  **+2–7.5 pp Precision@1 / MRR** over static hybrids, and the signals it needs are exactly the ones we already
  derive. *Grounded:* QPP in the trace; the 580 adaptive-fusion-weight selector already exists for the 3-leg CC
  path — extend it to the default 2-leg path. Language-agnostic (IDF/scope are corpus stats). Medium · high value.
- **B2 — Calibrated convex-combination fusion for the default hybrid.** Research consensus: a calibrated
  weighted score-combination beats RRF in-domain, is parameter-light and sample-efficient. AUTO currently uses
  RRF; CC + weights already exist for the 3-leg path. Fold B1's per-query weight into a CC default. *Verify via
  the 580 nDCG ratchet before adopting* — RRF stays the safe zero-config fallback. Medium.
- **B3 — Matryoshka two-stage dense (also the cheapest path to R4).** `gte-multilingual-base` (already shipped)
  natively supports **elastic dimension truncation [128–768] without retraining**. Use a 256-dim query+ANN
  shortlist, then rescore the top-k on full 768-dim. Wins: faster ANN, smaller on-disk vector index, and a
  *tiny* query-embed footprint — which makes the R4 "query-embed-while-Online" co-residence nearly free (the
  thing R4 was deferred on). *Grounded:* model capability confirmed on the HF card. Medium · enables R4.
- **B4 — QPP-gated / lighter cross-encoder.** The cross-encoder dominates search latency (593 trace:
  ~432 ms of ~511 ms). Skip it when QPP says the ranking is already confident/well-separated, and/or use a
  distilled student (literature: distilled MiniLM-L6 ≈ 70–90 % of the gain at 30–50 % cost, <50 ms GPU).
  *Grounded:* LambdaMART already gates the CE; the trace already measures its latency. Medium · latency polish.

## 43. (III) New UX on now-working semantics + the trace
- **C1 — Local relevance-feedback loop.** The app already emits search-interaction dispositions (580
  DWELLED/clicked) and already has a learned ranker (LambdaMART via GPL). Wire clicks/dwell (and optional
  explicit 👍/👎) back into that ranker so **the more you search, the better *your* results get — entirely
  on-device.** Must be presentation-bias-aware (literature: implicit feedback is biased toward top
  positions). *Grounded:* both halves exist (disposition emission + learned ranker); this closes the loop.
  Strongly on-brand for a private, local-first tool. Medium–high · high value.
- **C2 — "Found by meaning" badge.** When a result was surfaced by the dense leg that BM25 alone would have
  missed (visible in the per-hit `SearchTrace` legs), badge it — a concrete, per-result demonstration of what
  semantic search bought the user. *Grounded:* "Why this result?" already renders the per-leg contributions.
  Low–medium · showcases R1's payoff.
- **C3 — "More like this" / semantic clustering.** Vectors now exist per chunk; offer "find similar to this
  result" (nearest-neighbour from a hit's vector) and optionally group results by topic. New capability, fully
  local. Keep clustering label-free (language-agnostic). Medium.
- **C4 — Weak-query reformulation.** When QPP/scores say a query retrieved poorly, suggest a
  semantically-related reformulation instead of returning stopword noise. *Grounded:* expansion + QPP exist.
  Medium.

## 44. (IV) R4, reconsidered
R4 (query-embed serviceable while the chat LLM is Online) was deferred as VRAM-risky. ~~B3 (Matryoshka)
largely dissolves the risk~~ — **CORRECTED in PART IX §50:** Matryoshka shrinks the output *vector*
(768→256 dims), not the embedder *model* (~628 MB FP16 — the actual VRAM cost is the weights, which are
unchanged by output dim). So Matryoshka does **not** make R4 free; R4 still rests on co-residing or
CPU-falling-back the ~628 MB model. See §51.

## 45. What to avoid (scope discipline)
No per-language levers (any clustering/reformulation must stay label-free, ADR-0043/581). No speculative
pipeline-policy engine beyond B1's signal-driven weighting. RRF stays the zero-config fallback; B2/B1 must
prove out on the 580 relevance ratchet before becoming default. None of this is committed — it's the
opportunity map R1+R3 created.

## 46. Sources
- Query-adaptive hybrid / Dynamic-Alpha-Tuning & query-specificity weighting:
  `emergentmind.com/topics/dense-sparse-hybrid-retrieval`, `doi.org/10.3390/make8040091`,
  `arxiv.org/html/2604.14222v1`.
- Fusion (RRF vs convex combination): `arxiv.org/abs/2210.11934` (Analysis of Fusion Functions),
  `elastic.co/search-labs` hybrid-retrieval, `opensearch.org` RRF.
- Matryoshka / elastic embeddings: `sbert.net` Matryoshka docs, NeurIPS 2022 MRL paper,
  `huggingface.co/Alibaba-NLP/gte-multilingual-base` (confirms elastic [128–768]).
- Reranker latency / distillation: `arxiv.org/pdf/2305.11744` (ReFIT), FlashRank / MiniLM-L6 surveys.
- Implicit / unbiased learning-to-rank from clicks: Lehigh "Learning from Implicit Feedback for Unbiased LTR",
  `arxiv.org/pdf/2401.04053` (LTR with nested feedback).

---

# PART IX — LONG-TERM DESIGN (scope-matched end-state, 2026-06-17)

> Design-theory pass over PART VIII, with the **scope discipline the work actually needs**: 598's stated
> problem (semantic search unreachable) is **solved and shipped** (R1+R3). So the correct long-term design
> does NOT turn every PART VIII opportunity into committed 598 structure — that would be the speculative
> completeness this kind of pass must avoid. The disciplined result is **narrow**: 598 genuinely owns only
> two remaining structural items (§48, §49); most of PART VIII is **already 580's design, more mature than
> mine — adopt/extend it, don't duplicate** (§50); and one PART VIII claim was wrong on investigation (§50
> Matryoshka). General, not implementation-level.

## 47. The boundary that drove this pass (investigated, not assumed)
- **580 already owns the retrieval-quality decision.** `AdaptiveWeightSelector`
  (`adapters-lucene/.../runtime/AdaptiveWeightSelector.java`, on `main`) is explicitly a **v0** that picks
  per-query CC fusion weights from a *result-length* signal, with a **"richer v1" named as future**; QPP is
  not yet consumed at the fusion site. 580 Track C §17 is the **already-designed-and-built feedback "outcome
  tier"** (`ResultDisposition` + `FeatureSnapshot` → `LambdaMartFeatureSchema`, GPL demoted to cold-start
  prior). 580 §13.7/F-021 **discredited learned *fusion*** (a dead end — do not propose it). So PART VIII's
  B1 (QPP routing), B2 (CC fusion), and C1 (feedback loop) are **580's levers**, not new 598 design.
- **598's genuine remaining debt is the read/serviceability side** — the §33/§34 "risk D" the doc itself
  flagged, confirmed live: the search banner's readiness reads the `"retrieval"` composite, which
  **excludes embedder availability** (`ReadinessDimension`: `EMBEDDING → "aiFeatures"`, not `"retrieval"`),
  while issuance (R1 `resolveAutoDense`) and the per-result indicator key on the actual compat+embedder
  state. That divergence is 598's to close (it overlaps 600's T2 in the retrieval direction).

## 48. (598-owned) Design A — one retrieval-serviceability authority
**Problem.** "Is semantic search usable right now?" has three readers — issuance, the degradation banner,
the per-result indicator — and they don't read the same fact, so they can disagree (banner says capable,
the query actually ran keyword because the embedder was momentarily down).
**Correct long-term shape (extend, don't replace).** Define **one capability predicate at the Worker** —
*dense-serviceable now = index COMPATIBLE (`allowQueryEmbeddings`) AND the query embedder available* — and
**project it once** onto `/api/status` (the compat fields are already on the wire per 600 T2; this adds the
embedder-availability conjunct). Then:
- **Issuance** already consumes exactly this (R1 `resolveAutoDense` = `compat.allowed() && embedder.isAvailable()`) — it becomes the *reference* definition, not a fourth opinion.
- **The banner/verdict** consumes the same predicate instead of the embedder-excluding `"retrieval"` composite (a 595/600 coordination — 598 supplies the predicate).
- **The per-result indicator** keeps reading the response's `effectiveMode` — which is *actuality* (what
  ran), a distinct-but-consistent fact from the same source.
So there are exactly **two facts from one source**: *capability* ("could run", the banner) and *actuality*
("did run", the indicator) — never contradictory. This is wiring + a predicate, **no new subsystem**;
it closes risk D and the retrieval half of 600 T2. Small.

## 49. (598-owned) Design B — R4 as embedder-role unbundling
**Problem.** The single embedder serves two roles the single-GPU mutual-exclusion (ADR-0004) treats
identically: **bulk backfill** (sustained, heavy — correctly paused under Online) and **query embedding**
(one bounded forward pass, latency-critical, needed for dense *search and RAG* during chat).
**Correct long-term shape.** Keep ADR-0004's exclusion for *bulk* GPU work; make the *query* embedder
**always serviceable** via either (a) a **bounded co-residence reservation** — the ~628 MB FP16 model
beside the ~9.7 GB chat model ≈ 10.3 GB fits a 12 GB card; because it is *one fixed-size model*, this is a
single bounded reservation, not the arbitrary VRAM budgeting ADR-0004 rightly rejected — or (b) a **CPU
query-embed fallback while Online**. Either narrows ADR-0004 rather than overturning it (it is already
partly superseded). This is the one piece that, once built, also lets the RAG path retrieve semantically
*during* chat. Feeds Design A (then "dense-serviceable" is ~always true). Medium; the only safety-sensitive
remaining item — gate on live GPU verification.

## 50. (NOT 598 — route to 580 / corrections)
- **Query-adaptive weighting (PART VIII B1/B2) → 580.** It is the **"richer v1"** of 580's
  `AdaptiveWeightSelector`: feed the QPP signals (`maxIdf`/`avgIctf`/`queryScope`, already emitted in
  `SearchTrace`) into the *existing* selector as a better/added weight input than the v0 result-length
  signal. Extends 580's structure; belongs in 580's lever map, not a new 598 mechanism. RRF stays the
  zero-config fallback; any change proves out on the **Q-010 nDCG ratchet** (580 Track B) first. Do **not**
  build a generic "pipeline-policy engine" — the decision stays a function of a few concrete signals.
- **Feedback loop (PART VIII C1) → 580 Track C §17.** Already designed and built (the outcome tier). 598
  does not re-design it; it is the same "clicks → the existing learned ranker" loop. Learned *fusion* stays
  dead (F-021).
- **Matryoshka (PART VIII B3) — reclassified + corrected.** It shrinks the stored **vector** (768→256, via
  `gte-multilingual-base`'s elastic dims), giving smaller on-disk vectors + faster ANN (a real *index-
  efficiency* lever for 580's map / its own item) — but it does **not** reduce the embedder **model** VRAM
  (the ~628 MB weights, unchanged by output dim), so it is **not** an R4 enabler (corrects §44). Low
  priority, independent of R4.

## 51. Net (the scope-matched end-state)
598's correct long-term end-state is just: **(A) one Worker-sourced "dense-serviceable" predicate read
consistently by issuance, banner, and indicator; and (B) the embedder-role unbundling that makes that
predicate ~always true even while chatting (R4).** Everything else PART VIII surfaced is either **580's
already-more-mature design (adopt it)** or a **separate index-efficiency lever** — deliberately *not*
pulled into 598. The size of 598's remaining design is small because that is what the remaining problem
actually is; the broad opportunity map (PART VIII) stays as research, not as committed structure.

---

# PART X — USER-FACING / FRONTEND DESIGN for the remaining work (2026-06-17)

> What the PART IX remaining design (Design A readiness-authority · Design B/R4 embedder-unbundling) means
> on screen. **Inspection method:** the FE code as-merged on `main` (post-600) + this session's live
> screenshots of every relevant state (the three readiness-banner variants, the per-result indicator
> "Keyword"/"Semantic + keyword", the `IndexingOverlay` "Batch Processing Active" modal, the Health verdict,
> the Pipeline-details trace). A fresh browser pass found only empty/offline demo stacks (0 docs) + a flaky
> tab, so the degraded/hybrid states were read from code + the prior live captures rather than re-shot —
> noted honestly.

## 52. What 600 already shipped (so 598 does NOT re-build it)
Inspecting `readinessNotice.ts` on `main`: 600 already unified the **capability** story. "Reindex required"
is now derived from **real worker compat codes** (`REINDEX_CAUSE_CODES`: `index.blocked_legacy`,
`index.embedding_legacy`, `index.schema_mismatch`, `index.embedding_mismatch`) carried on the `retrieval`
composite, the banner **consumes the one 595 verdict** (no re-read), and `causes` names the specific cause
with a one-click `core.rebuild-index` remedy. So Design A's "single authority" is **already realized for the
index-compat dimension** — banner ↔ verdict ↔ compat-codes cannot disagree there. 598 must not duplicate it.

## 53. The genuine user-facing shape (capability vs actuality — two facets, one source)
The correct on-screen model is two deliberately-distinct facts that share one source, so they read as
complementary, never contradictory:
- **Banner = capability** — "*can* semantic search run?" (compat-driven, 600-shipped, with the rebuild
  remedy). Answered once, at the system level.
- **Per-result indicator = actuality** — "*did this query* run semantic?" ("Semantic + keyword" vs
  "Keyword", my R1 work, from the response's `effectiveMode`). Answered per query.
A user seeing a calm banner **and** a "Semantic + keyword" indicator (COMPATIBLE), or "Reindex required"
**and** "Keyword" (BLOCKED), gets one coherent story. The **only** state where they can still visibly clash
is the narrow **COMPATIBLE-but-embedder-momentarily-unloaded** transient (Online-mode GPU contention): the
banner stays calm (the embedder-availability dimension lives on the `aiFeatures` composite, not `retrieval`)
while a query runs keyword → indicator "Keyword". **The correct fix is structural, not a banner patch:
Design B/R4 removes that transient** (the query embedder is always serviceable), after which the only
readiness variable is index-compat — which 600 already surfaces consistently. So 598's user-facing Design A
adds **no new surface**: it is "keep the indicator as the per-query truth + let R4 close the one transient,"
explicitly *not* a stopgap reason-string on the indicator.

## 54. R4's user-facing payoff (Design B on screen)
R4 is "indirect," but it has two concrete visible consequences:
1. **Narrow the `IndexingOverlay` modal.** Today (`IndexingOverlay.ts`) it announces **"Batch Processing
   Active — AI features temporarily unavailable… Chat, Q&A, and summarization are paused to prevent GPU
   memory conflicts."** That copy over-claims: it implies *semantic search* is down during indexing too.
   R4's whole point is that the **query embedder stays serviceable**, so after R4 the modal must narrow to
   *"Chat & Q&A paused while indexing — search still works"* and the search surface must stay fully semantic
   during indexing/Online. This is R4's most visible win: the user can keep meaning-searching while the
   model is busy, instead of being told "AI features unavailable."
2. **Grounded-Q&A (Documents tab) during chat.** With query-embed alive while the chat model is loaded, RAG
   retrieves by meaning instead of falling back to keyword — better-grounded answers and citations through
   the *existing* grounding/citation UI (better inputs, no new surface).

## 55. Frontend scope discipline
No new surfaces. Reuse: 600's capability banner, my per-result actuality indicator, the existing
`IndexingOverlay` (copy narrowed by R4), and the existing RAG citation/grounding UI (better inputs from R4).
The load-bearing UX rule is the **capability-vs-actuality split** (§53) — one source, two facets — and the
recognition that the last visible contradiction is closed by the R4 *mechanism*, not by more banner text.

# PART XI — R4 DE-RISKING (read-only, 2026-06-17, pre-implementation)

Confidence-building pass before any R4 code. **No feature code written** — static source audit (Tier A),
read-only GPU probe (Tier B); the binding live co-residence test (Tier C / C1) is deliberately folded into
R4 implementation as its acceptance test. Findings correct two earlier optimistic notes and pick the safer
first mechanism.

## A1 — Embedder split feasibility (uncertainty #2): single-session, unloaded at *provider* granularity
There is **one** `EmbeddingService` → one `OnnxEmbeddingBackend` → one `SessionHandle`; `embedQuery`,
`embed`, and `embedDocumentBatch` all route through the same backend (`EmbeddingService.java:60,184,205,349`).
On `main_gpu_active` rising edge, `EmbeddingProviderLifecycle.handleGpuStateTransition` (`:144-165`) calls
`unloadEmbeddingService()` (`:168-185`) → `EmbeddingService.close()` → `backend.close()` + swap to
`NoOpEmbeddingProvider` (`:181`). This is a **wholesale teardown to NoOp, not a pause** — and it tears down
at the *provider* layer, so even the internal CPU session is destroyed. A subsequent `embedQuery` returns
`null`. **Splitting query-embed from bulk-embed is therefore the unbuilt §38.3 work**: a new query-role
provider the lifecycle does *not* NoOp on Online, + `LoopPacingPolicy` to pause only backfill. **Moderately
invasive — it touches the exact GPU-handoff that enforces ADR-0004 VRAM safety.** (Confirms §38.3.)

## A1 — VRAM budget (uncertainty #1): correcting the "628 MB" note
- Arena caps are **ceilings, not up-front reservations** (`gpu_mem_limit` + lazy BFC arena,
  `SessionOptionsApplier.java:88-91`). Per-role caps: embedder **3072 MB** (`ResolvedConfigBuilder.java:960`
  — *not* the 628 MB figure; 628 MB is the FP16 *weights*, the cap is weights+activations), SPLADE 4096,
  reranker 2048, NER 512, citation-scorer CPU-only.
- The summed caps (~9.7 GB) do **not** co-fit with ~9.7 GB chat in 12 GB — **but they are not all resident
  under Online**: only the **reranker** is *actively* released (`GrpcSearchService.onMainClaimedGpu →
  searchReranker.releaseGpuSession`, `:323-329`); the embedder is fully NoOp'd; SPLADE/NER/BGE-M3 are
  **denied *new* GPU leases** by the shared arbiter `() -> !signalBus.isMainGpuActive()`
  (`NativeSessionHandle.selectSession:206-208`) and degrade to CPU, though already-open GPU sessions linger
  until next close (transient overlap possible). **There is no global VRAM budget reconciling sessions**
  (§10). So the binding steady-state contention is **chat + the query-embedder weights** (~628 MB) ≈ 10.3 GB,
  which fits the 12 GB arithmetic — but co-residing the embedder under its **full 3072 MB arena cap**
  alongside chat is much tighter and **remains UNVERIFIED on live GPU** (the observed NER OOM is the warning).

## A2 — CPU query-embed fallback (uncertainty #3): not currently wired for the embedder
`NativeSessionHandle` keeps a CPU session and `selectSession` falls back to it (reranker/SPLADE use this) —
**but for the embedder it is moot**, because the unload happens one layer up (`EmbeddingService.close()` +
NoOp swap), destroying the CPU session too. So **today there is NO CPU embedding fallback under Online.** The
FP32 CPU variant is *decided* (ADR-0019) and the `ModelArtifacts(cpu,gpu)` mechanism exists, but the two
paths **may collapse to FP16-only** in GPU-lite installs (`ModelArtifacts.java:11-12`) and FP32 asset
presence in the shipped bundle is **unconfirmed**. Single-query CPU latency is seconds-class (~2–5 s, §10) —
fine for a query, not for backfill.

## B1 — Live GPU probe (uncertainty #1, empirical decider): not measurable now
`nvidia-smi`: **9917 MiB free / 2094 used, no `llama-server` running** — no chat model loaded, so the
binding number (free VRAM *with chat Online*) cannot be measured without loading the 9.7 GB model. On a
shared box I will not load it. **Deferred to C1 as R4's acceptance test** (`audit-driven-fixes-need-test`):
bring chat Online on an *owned* quiet stack, attempt a co-resident query-embed, watch for OOM/latency.

## A3 — IndexingOverlay over-claim (uncertainty #4): real, and R4-INDEPENDENT
`SearchSurface.ts` has **no `mode==='indexing'` gating** — search is never disabled by indexing mode. The
`IndexingOverlay` is a full `backdrop`/`aria-modal` card (`IndexingOverlay.ts:200-201`) that **visually
blocks the still-working search surface** while claiming *"AI features temporarily unavailable… Chat, Q&A,
and summarization are paused"* (`:206-218`). During **Indexing** mode the chat model is *not* loaded, so the
embedder is GPU-resident and **AUTO search serves full HYBRID** (R1, proven live in PART VII). The copy
conflates chat with search. **Narrowing the modal copy + not full-blocking the search surface is safe to
ship WITHOUT R4** — it is a pure presentation correction of an already-true capability. (This is the cheapest
real user-facing win in the whole 598 line and does not depend on the risky split.)

## A4 — Design A residual (uncertainty #5): confirmed, mooted by R4
`ReadinessDimension`: `EMBEDDING → aiFeatures`, `CHUNK_EMBEDDING → retrieval` — the `retrieval` composite
**excludes live embedder availability**. So under Online (embedder NoOp'd) the index can be COMPATIBLE and
the `retrieval`-derived verdict/banner stay calm while a dense query silently falls to keyword (Fix A's
`resolveAutoDense` → TEXT). That **Online-mode COMPATIBLE-but-embedder-unloaded transient is the only
remaining banner↔indicator divergence** (§53), and R4 (query embedder always serviceable) removes it at the
source. Confirmed: Design A needs **no new surface** — it is "R4 closes the transient."

## Go / no-go + confidence per R4 mechanism
| Mechanism | Verdict | Confidence | Why |
|---|---|---|---|
| **IndexingOverlay narrowing** (R4-independent) | **GO now** | **8/10** | Search already serves during Indexing (R1, live-proven); pure copy/gating correction; only risk is wording. Could ship ahead of the split. |
| **CPU query-embed fallback** | **Conditional GO** — safer first mechanism | **6/10** | No VRAM contention with chat; latency viable (~2–5 s). Blockers: (a) confirm FP32 CPU asset is bundled (else FP16-on-CPU is slow), (b) lifecycle change so the query path isn't NoOp'd on Online. Both bounded, no ADR-0004 amendment. |
| **GPU co-residence** (resident query embedder beside chat) | **NO-GO until C1** | **4/10** | Weight arithmetic fits (10.3 GB/12 GB) but the embedder runs under a 3072 MB arena cap with no global budget; NER OOM observed; unverified live. Needs the C1 owned-stack OOM test and likely an arena-cap clamp for the query role. |

**Overall R4 confidence: 5/10** (unchanged from §30, now with the cause located). Recommended sequencing
when R4 is greenlit: **(1)** ship the IndexingOverlay narrowing first (independent, 8/10); **(2)** implement
the query/bulk role split with a **CPU query-embed** path (6/10) as the default-safe mechanism; **(3)** treat
GPU co-residence as an *optimization* gated behind the C1 live OOM acceptance test, with an arena-cap clamp,
not the baseline. This keeps ADR-0004 VRAM safety intact (no co-resident GPU claim is made until measured)
and still delivers the user-visible win (semantic search/RAG alive while chat is busy). **No R4 code written
in this pass.**

# PART XII — AS-BUILT: R4 implemented (2026-06-17, worktree-598-r4)

Status: **R4 IMPLEMENTED + LIVE-VERIFIED + MERGED** (merged to `main` via `00eeeaaa2`, 2026-06-17).
Phase 0 (IndexingOverlay copy) + Phase 1 (R4 core) done; Phase 2 (GPU co-residence) deferred as planned;
Phase 3 (Design-A residual) verified. ADR-0004 amended with the R4 query-embed-exempt note. Remaining
598-line debt (not blocking): Design A read-side banner consistency — see §56 below.

## 56. Remaining work after R4 (for a future pass)
Two items the R4 merge intentionally did **not** close (surfaced honestly, logged to `observations.md`):
1. **Design A read-side consistency is only partially realized.** R4 makes the *embedder-availability*
   conjunct of the "dense-serviceable" predicate (§48) ~always true, so the specific
   COMPATIBLE-but-embedder-unloaded divergence (§53) is closed. BUT a live browser pass found the readiness
   banner still reads *"Semantic search degraded — Showing keyword results"* while document semantic search
   actually serves HYBRID — driven by signals *outside* Design A's predicate (passage/chunk embeddings +
   LambdaMART). So the broader "banner never contradicts actuality" goal is not fully met. Fix options:
   wire the one Worker `dense-serviceable` predicate into the banner (Design A proper), **or** scope the
   banner copy so "keyword results" no longer over-claims document search vs passage-grounded Q&A.
2. **RAG-during-chat payoff (§54.2) is structurally enabled but unverified** — R4 keeps the query embedder
   alive, but passage-grounded Q&A also needs chunk embeddings to exist; a corpus with 0 chunk embeddings
   still falls to keyword for passages regardless of R4. Verify with a chunk-embedded corpus.

## Design pivot during implementation: release-only, not a second CPU service
The approved plan (PART XI) proposed a **second, CPU-only `EmbeddingService`** for query embedding. While
wiring it I found the codebase already supports the goal far more cheaply — adopting it honored the
"prefer extending existing designs" directive:
- `OnnxEmbeddingEncoder.releaseGpuSession()` already exists (`:596` → `SessionHandle.releaseGpu()`), the
  same primitive the reranker uses on the GPU handoff; the embedder just never called it (the lifecycle did
  a full `EmbeddingService.close()` + `NoOpEmbeddingProvider` swap instead).
- `NativeSessionHandle` keeps a **deferred CPU session** (`:114`); `selectSession()` falls back to it when
  the GPU session is released/arbitration says CPU. For a CUDA embedding variant `deferCpuSession=true`
  (`ModelSessionPolicyResolver:108`); the CPU session is fp16 at `BASIC_OPT` (`:84-88`, tempdoc-376 path),
  consistent with the indexed document vectors (same fp16 model).
- `LoopPacingPolicy.shouldRunBackfill` keys off `mainGpuActive && isUsingGpu()` (the static config flag,
  still true after a live-session release), so bulk backfill stays paused while a CPU query path is alive —
  no rewiring of the pacing gate needed.

**The chosen implementation:** on the GPU handoff, *release the embedder's GPU session* instead of closing
the service to NoOp. The provider stays alive and `isAvailable()` stays true, so the four search-path
callers and `GrpcSearchService.resolveAutoDense` (Fix A) keep working unchanged — `embedQuery` simply falls
to the CPU session. **Net: zero search-path rewiring, zero second model load, no ADR-0004 amendment** (CPU
path uses no VRAM), and vector-consistent (same fp16 weights as documents). This is strictly less new
structure than the approved second-service design while achieving the identical outcome.

## Changes (4 files)
- `worker-core/.../embed/EmbeddingService.java` — new `releaseGpuSession()`: delegates to the ONNX encoder's
  `releaseGpuSession()`, keeps the service open + `available` true. No-op when closed / non-ONNX backend.
- `worker-services/.../loop/EmbeddingProviderLifecycle.java` — the GPU-handoff rising edge now calls a new
  `releaseEmbeddingGpuSession()` (release GPU, keep provider, **no** NoOp swap, **no** listener notify)
  instead of `unloadEmbeddingService()`. The old full-unload method is retained for shutdown/tests.
- `worker-services/.../loop/IndexingLoopUnloadTelemetryEmitTest.java` — 3 new tests: release keeps the
  provider available + doesn't notify listeners + doesn't close the backend (still emits GPU_HANDOFF
  telemetry); the handoff rising-edge routes to release (provider stays); release is a no-op with no service.
- `ui-web/src/shell-v0/components/IndexingOverlay.ts` (Phase 0) — narrowed the over-claim: sub
  "AI features temporarily unavailable" → "Chat & Q&A paused — search still works"; explain now ends
  "…GPU memory conflicts — search still works."

## Verification
- **Unit:** `:worker-core:test` + `:worker-services:test` green (incl. the 3 new R4 tests + the retained
  unload tests). FE: `npm run typecheck` + `test:unit:run` (3158) green; `IndexingOverlay.test.ts` (7) green.
  Touched shell-v0 gates pass (presentation-purity, modal-arbitration, modality-contract, verdict-derivation).
  (`verifyGovernanceGates` reports unrelated pre-existing failures vs the worktree's stale `origin/main`
  base — 211-file divergence; none reference the 4 changed files; resolves on merge into local `main`.)
- **Live (real stack from this worktree + real Qwen-9B chat model on GPU):**
  - Built embeddings (5 + later 290 docs, 100% coverage, GPU).
  - **Offline/indexing baseline:** AUTO search "how do plants make energy from sunlight" → `Mode=hybrid`,
    5 hits, `photosynthesis` ranked #1 (1.000) — pure semantic (no keyword overlap).
  - **ONLINE (chat resident on GPU — 11.7 GB used / 271 MB free, embedder physically cannot be GPU-resident):**
    the SAME AUTO query returns **identical** results — `Mode=hybrid`, `photosynthesis` #1 (1.000); a second
    semantic query "what beverage contains caffeine" → `coffee` #1. Dense/semantic retrieval **survives
    Online**, served by the CPU embedder. Under the prior code the embedder was NoOp'd → keyword-only.
    Query latency ~0.9–1.7 s online (real CPU forward pass; first query pays the deferred-session warm-up).
  - **Browser (real shell-v0 FE proxied to the live backend, chat Online):** searching the semantic query
    returns `photosynthesis.txt` ranked #1 in the UI — the user-visible R4 win confirmed end-to-end.
- **Phase 3 (Design-A residual):** online AUTO = HYBRID confirms the COMPATIBLE-but-embedder-unloaded
  divergence is closed for document search — no code needed.
- **Notes / honest limits:** (1) the live browser also surfaced the §53 capability-vs-actuality split — the
  readiness banner reads "Semantic search degraded — keyword results" (keyed off passage/chunk embeddings +
  LambdaMART) while doc-level dense actually serves; logged to observations.md, not in R4 scope. (2) The
  `IndexingOverlay` live capture was impractical: its gating field `embeddingQueueSize` does not track the
  embedding *backfill* queue, so the overlay does not surface during backfill regardless of the copy change;
  the copy is unit-verified. Logged to observations.md.
- **Deferred (Phase 2):** GPU co-residence remains out — the CPU-release mechanism makes it unnecessary for
  correctness; revisit only if the ~1 s CPU query-embed latency proves materially harmful (it did not here).

---

## REOPENED — second pass (2026-06-17, 593 regression sweep)

> The §38 as-built was verified on an index that **had** a fingerprinted generation (post a
> manual rebuild). The 593 re-run against `main` (HEAD `cc293577b`) — clean restarts, no
> manual pre-rebuild — found the **core blocker still open in this environment** and that
> **R1's capability-default introduced a new truthfulness regression**. This is the #1
> product blocker per 593's own severity ranking, so 598 is reopened.

### The four live findings (oracle-paired against worker log + `/api/status` + pipeline trace)

**B-1 — BLOCKED_LEGACY durability did not hold in-env (Still-Present #6, contradicts §38.2).**
- Worker log on every clean restart: *"Embedding compatibility: BLOCKED_LEGACY … vector/hybrid
  queries are blocked until a forced reindex."* The rebuilt embedding generation **does not
  survive a restart** / the fingerprint never re-stamps. Even an explicit `denseEnabled:true`
  pipeline returned `Dense (vector) skipped`.
- §38.2 claimed R3 "DONE structurally" by adding `verifyGreenMetadata` to the green cutover so
  a rebuild "cannot promote a still-BLOCKED generation." The re-run shows the **promoted
  generation reverts to BLOCKED_LEGACY after restart** — so either the fingerprint isn't
  persisted with the vectors, or the green cutover's stamp isn't read on the next startup.
  This is the *original* §11/§H durability bug, **still reproducible**.
- **`audit-driven-fixes-need-test` flag:** §38.2's structural claim rested on reusing the
  blue/green migration + a unit test on `verifyGreenMetadata`; the surviving-across-restart
  property was **not exercised by a test**. The reopen needs a regression test that does a
  rebuild → restart → asserts the index is NOT BLOCKED_LEGACY (a runnable test, not a passing
  audit).

**B-2 — no usable rebuild affordance (NEW #3).** The user is *told* to "rebuild the index" with
no working control: the Search-panel **Force Rebuild button is gone** (replaced by "Open
Health"); **Library Reindex is incremental** (oracle: no `buildingGenerationId`, doc_count
steady — does not clear BLOCKED_LEGACY); and the **AI-Brain rebuild control**
(`<jf-operation operation-id="core.rebuild-index">`) **renders 0×0** (no clickable button).
B-1's durability fix is moot if there is no reachable way to trigger the rebuild it depends on.

**B-3 — capability-source over-claim (NEW #1).** R1 makes the default pipeline a projection of
`indexCapabilities.embeddingCoverage` (0.9755), but that capability **does not reflect the
Worker's BLOCKED_LEGACY gate** — so the reframed banner asserts *"results are still fully
semantic"* / *"results are complete"* while the **default query runs pure BM25** (trace: Dense
AND SPLADE skipped). The R1 thesis — "the pipeline shape is a projection of retrieval
capability owned by the one authority that knows it (the Worker's embedding-compat boundary)"
— is **violated in practice**: the FE reads `embeddingCoverage`, not the embedding-compat
boundary, so the capability the UI trusts is the one that lies. This is the regression's root.

**B-4 — cross-surface semantic-status disagreement (NEW #2).** **AI Brain** still says "Vector
and hybrid search are disabled until you rebuild the index" while **Search / Chat / System
Health** say "fully semantic / results complete." The surfaces disagree about whether semantic
search works — a single-authority gap on the *semantic-status* signal that 600 (the legibility
consumer) and 598 (the capability source) both touch.

### Reopen scope (design only — not yet implemented)

1. **B-3/B-4 (capability-source truthfulness) — 598's own:** the capability the FE reads for the
   AUTO default and for the "fully semantic" copy must reflect the **Worker embedding-compat
   boundary** (BLOCKED_LEGACY), not `embeddingCoverage`. When BLOCKED_LEGACY, the default is
   honestly keyword and the copy says so. This closes B-3 and removes the surface-disagreement
   source for B-4 (600 owns the consumed wording — see the 598/600 boundary note).
2. **B-1 (durability) — 598's own:** the rebuilt fingerprint must survive a restart; add the
   rebuild→restart→not-BLOCKED regression test (audit-driven-fixes-need-test).
3. **B-2 (reachable rebuild) — 598's own:** a working, single rebuild affordance that actually
   clears BLOCKED_LEGACY (fix the 0×0 `core.rebuild-index` render and/or restore a Force
   Rebuild path). Honesty of the remedy depends on B-1 (a one-click rebuild that silently
   reverts on restart is worse than none — exactly 600 PART IV's cross-tempdoc constraint).

### 598/600 boundary (so the regression isn't double-owned)

- **598 owns the SOURCE:** the capability signal (`embeddingCoverage` vs embedding-compat
  boundary) and the rebuild path. B-1/B-2/B-3 are source-side.
- **600 owns the CONSUMED COPY:** the "What you can do right now" / degraded-banner wording
  that *renders* the (now-truthful) capability. The over-claim shows up in 600's panel because
  it consumed 598's lying capability — 600 is reopened narrowly for that (and for the
  CelEvaluator self-monitoring item). Fixing 598's source is the prerequisite; 600's copy then
  reads true.

### Disposition

- **Reopened for B-1/B-2/B-3/B-4 (source side).** R1's general capability-derived-AUTO design
  and R3's blue/green reuse stand as merged history; the reopen is a **correctness completion**
  (the capability must reflect the real gate, the durability must hold, the rebuild must be
  reachable), not a redesign. R4 stays deferred.

---

# PART XIII — REOPEN INVESTIGATION (2026-06-17, second agent takeover)

> **What changed.** The REOPENED section above is a live-observation note (4 findings) with a
> *hypothesized* root for each. This Part re-verifies every B-finding against `main` (HEAD
> `cc293577b`, the exact reopen HEAD) with `file:line` evidence — `verify-don't-guess` applied to
> the reopen's own claims, exactly as PART II did to 593's. **The headline result: one finding
> (B-2) is real, structural, and the true dominant blocker; one finding's *stated mechanism* (B-3
> "the FE reads `embeddingCoverage`") is demonstrably wrong on `main`; and B-1/B-4 are corollaries
> of two precisely-locatable gaps, not new diseases.** Static source reads + the live read-only
> probe used here (dev stack owned by `b9e86b32` — no takeover). The one decisive disambiguation
> that needs an *owned* stack is named in §63.

## 57. Method

- Static reads of the whole BLOCKED_LEGACY → `/api/status` → verdict → banner → rebuild-remedy
  chain, both processes (Worker compat controller; Head readiness handler; FE store/verdict/notice;
  the operation catalog + audience gate).
- The single live probe available without takeover: `quick_health` (confirmed a foreign-owned
  running stack). No `/api/status` capture at the BLOCKED_LEGACY moment was possible (that is the
  one owned-stack item, §63).
- Cross-checked against `docs/observations.md` (items #383 pre-existing BLOCKED_LEGACY, #480 the
  §53 banner split, #380 Health passes `viewer-audience=OPERATOR`, #201 the viewerAudience cache
  quirk).

## 58. B-2 is REAL, structural, and the dominant blocker — root-caused (highest confidence)

**The whole rebuild-affordance surface, evidenced end-to-end:**

| Surface | Affordance on `main` | Op | Audience reality | Clears BLOCKED_LEGACY? |
|---|---|---|---|---|
| **Brain** (`BrainSurface.ts:1470-1476`) | `<jf-operation operation-id="core.rebuild-index">` — **no `viewer-audience` override** | `core.rebuild-index` → `startMigration` (blue/green) ✓ | op is `Audience.OPERATOR` (`CoreOperationCatalog.java:413`); viewer defaults `'USER'` (`viewerAudienceState.ts:73`) ⇒ `operationVisibleTo('USER')==false` (`queryPrimitives.ts:34-41`) ⇒ strategy returns `nothing` (`operationButton.ts:109-111`) ⇒ JfOperation renders `nothing` (`JfOperation.ts:124-145`) | **Right op, but 0×0 for a USER viewer** |
| **Library** (`LibrarySurface.ts:738`) | `<jf-operation operation-id="core.reindex">` (renders — `core.reindex` is `Audience.USER`) | `core.reindex` (**incremental**) | visible ✓ | **No** — incremental reindex doesn't re-embed a legacy generation or stamp a fingerprint |
| **Search** (`SearchSurface.ts:1366-1372`) | degradation-banner *remedy* → `<jf-op-button operation-id="core.rebuild-index">` — **ungated** (OpButton applies no audience gate) | `core.rebuild-index` ✓ | renders for any viewer ✓ | **Yes — but only appears when `verdict.kind==='degraded'` with a reindex cause** (`readinessNotice.ts:247-254`); masked when B-3 mis-reports "fully semantic" |

**Net (the product gap, fully evidenced):** there is **no reliably-reachable USER affordance** for the
full rebuild that clears BLOCKED_LEGACY. The one that *works for a USER* (the Search banner remedy)
is shown **only when the verdict is already degraded with a reindex cause** — i.e. precisely when the
capability signal is *correct*; the moment B-3's over-claim fires, that affordance disappears. The
always-present one (Brain) is OPERATOR-gated to 0×0. The always-visible USER one (Library) is the
**wrong op** (incremental). This is the real catch: **the remedy `readinessNotice` points at
(`core.rebuild-index`) is the OPERATOR op the always-on Brain control hides, and the only place it
renders for a USER is a banner that B-3 can suppress.**

**This re-frames the reopen's R-ordering.** The REOPENED note lists B-1 (durability) first; the
evidence says **B-2 (reachability) is the dominant blocker**, and B-1's symptom is most consistent
with *no rebuild ever completing because there was no reachable affordance* (§61), not a durability
regression. Same shape as PART II's headline correction of 593.

**Fix space (a real product decision for the user — §64 Q1).** Three coherent options, not taste:
1. **Make `core.rebuild-index` `Audience.USER`.** 593's entire thesis is that the *user* must rebuild
   to get semantic search; "rebuild my index" is a self-service recovery, not an admin action. This is
   the smallest change and the most honest to the product model (a local single-user app where "the
   user IS the operator" — obs #380). Risk: it's a heavy, destructive-ish op (full corpus re-embed);
   its `MUST_COMPLETE` lease + confirm ceremony already guard it.
2. **Keep it OPERATOR; have Brain pass `viewer-audience="OPERATOR"`** like HealthSurface already does
   (`HealthSurface.ts:1266`, obs #380). Restores the Brain button without changing the op's tier.
   Risk: re-introduces the per-surface hardcoded override that 511-followup called "gate theater" —
   and still leaves a default-USER viewer with no *self-service* path outside an operator surface.
3. **Add a first-class USER rebuild affordance on Search/Library** wired to `core.rebuild-index`
   (ungated, like the banner remedy), independent of the degraded-banner appearing. Most work; closes
   the gap regardless of B-3.

**Recommendation:** Option 1 (USER-audience) + Option 3's always-available entry on the Brain/Health
"semantic blocked" card, because it makes reachability independent of the (separately-buggy) capability
signal. But this is the user's product call.

## 59. B-3's stated mechanism is WRONG on `main` — the over-claim is a state-reporting gap, not a capability substitution

The REOPENED note's root for B-3: *"R1 makes the default pipeline a projection of
`indexCapabilities.embeddingCoverage` (0.9755)… the FE reads `embeddingCoverage`, not the
embedding-compat boundary, so the capability the UI trusts is the one that lies."* **Three independent
reads refute this:**

1. **The pipeline is resolved at the Worker from the compat boundary, never from coverage.**
   `GrpcSearchService.resolveAutoDense` gates AUTO dense on
   `denseServiceable = compat.allowed() && embeddingProvider.isAvailable()`
   (`GrpcSearchService.java:412-413`), where `compat.allowed() == controller.allowQueryEmbeddings()
   == (state == COMPATIBLE)` (`EmbeddingCompatibilityController.java:244-246`). Under BLOCKED_LEGACY,
   AUTO **correctly** resolves to keyword. So the *query behaviour* the reopen saw (Dense skipped) is
   the boundary working — not a coverage misread.
2. **The FE has ZERO `embeddingCoverage` consumers in `shell-v0`.** `indexCapabilities.embeddingCoverage`
   is parsed into the search domain model (`api/domains/search.ts:295`) and then **read by nothing** —
   no banner, no verdict, no pipeline decision references it (whole-`ui-web/src` grep: only the schema,
   the generated types, fixtures, and the unused domain field). The number `0.9755` the reopen cited is
   a *symptom the author saw on the wire*, not an input the UI acts on.
3. **The banner reads the backend `retrieval` composite, which DOES carry BLOCKED_LEGACY.**
   `computeReadiness()` projects `status.readiness.composites['retrieval'].state` + `.reasonCodes`
   directly (`aiStateStore.ts:404-418`); `computeVerdict` turns `retrieval==='degraded'` into
   `{kind:'degraded', reasons: reasonCodes}` (`verdict.ts:167-168`); and the Head **maps BLOCKED_LEGACY
   → `index.embedding_legacy` on the `INDEX_SERVING` component**, which is in the **`retrieval`**
   composite (`StatusLifecycleHandler.compatBlockedReason` :903-920 + the INDEX_SERVING arm :952-959;
   `ReadinessDimension.INDEX_SERVING → "retrieval"` :15). `readinessNotice` then renders
   **"Reindex required."** for any `index.*` reason (`readinessNotice.ts:198-208,247-254`).

**Therefore: if `/api/status` reports `embeddingCompatState==BLOCKED_LEGACY`, the banner says "Reindex
required" — NOT "fully semantic."** The same single `EmbeddingCompatibilityController` instance feeds
both `/api/status` (`IndexStatusOps.safeEmbeddingCompatState → controller.state().name()`) and the
query gate (`KnowledgeServer.java:940-947` wires one `ecc` to both via `wireEmbeddingCompatController`),
so there is no wiring fork either.

**So the B-3 over-claim necessarily means `/api/status`'s `retrieval` composite was NOT degraded at the
FE poll moment, while the live query gate WAS blocking.** That is a **state-reporting / temporal gap**,
and there are exactly three structural candidates (one is provably real from source; the decisive
disambiguation is §63):

- **(a) UNAVAILABLE-not-mapped (provably real gap).** `compatBlockedReason` maps **only** `BLOCKED_LEGACY`
  / `BLOCKED_MISMATCH` (`:908-919`). It does **not** map `UNAVAILABLE` (no embedding model resolvable —
  e.g. the embedder unloaded under Online, or not yet probed). In `UNAVAILABLE`,
  `allowQueryEmbeddings()==false` (dense blocked) **but** `compatBlockedReason→null` ⇒ INDEX_SERVING
  READY ⇒ `retrieval` not degraded ⇒ calm "fully semantic" banner. This *exactly* reproduces B-3 in the
  UNAVAILABLE state. (It does **not** reproduce it for a controller genuinely in BLOCKED_LEGACY, which
  the reopen log asserts — see (c).)
- **(b) Status staleness / dual-snapshot skew.** The readiness composites are built Head-side from the
  cached `WorkerOperationalView`; a startup window (worker `STARTING`, `refresh()` not yet run → state
  `UNAVAILABLE`/`INITIALIZING`) or a stale `WorkerStatusCache` view can serve a non-BLOCKED compat while
  a live query already hits the refreshed BLOCKED gate. This is a timing window, hard to confirm
  statically.
- **(c) The reopen log said BLOCKED_LEGACY, not UNAVAILABLE.** If the controller was genuinely
  BLOCKED_LEGACY *at the FE poll moment*, the banner should have said "Reindex required" — so an observed
  "fully semantic" under a true-BLOCKED_LEGACY state would be a **status-snapshot bug** (the same
  `/api/status` response carrying a BLOCKED `compatibility` block but a non-degraded `retrieval`
  composite, derived from a different/older workerView). Distinguishing (b)/(c) from (a) is the §63
  owned-stack capture.

**Critical correction recorded:** the reopen's "the capability the UI trusts is the one that lies"
points at the *wrong authority*. The FE already trusts the right one (`retrieval` composite ←
compat boundary). The defect is that **the `retrieval` composite can fail to reflect a live dense-block
(`UNAVAILABLE`, and possibly a snapshot skew) — the read side is sound; the produce side has a
coverage hole for non-`BLOCKED_*` no-dense states.** This is the inverse of obs #480 (the §53
*under*-claim, where `retrieval` is degraded by CHUNK_EMBEDDING/LAMBDAMART while doc-level dense
actually serves). Both are the **same disease: the `retrieval` composite conflates several distinct
facts and is neither necessary nor sufficient for "did/can a doc-level dense query run."**

## 60. B-4 (cross-surface disagreement) — same root as B-3, plus the B-2 asymmetry

Brain shows "Vector and hybrid search are disabled until you rebuild" by reading the **raw**
`emb.compatState === 'BLOCKED_LEGACY'` straight off `systemStatus` (`BrainSurface.ts:1433-1452`) — an
*actuality-faithful* read. Search/Health read the **derived verdict** (`retrieval` composite, gated by
the §59 produce-side hole and by the `verdict.ts` Stability precedence, where a `provisional` stability
masks the readiness rollup entirely, `verdict.ts:141-160`). So the two surfaces disagree **exactly when
the verdict's `retrieval` composite fails to carry the compat truth that Brain reads directly.** B-4 is
not a separate authority problem; it is B-3's produce-side hole observed from a second surface that
(correctly) bypasses the composite. The fix is the same single predicate (§62), consumed by both.

## 61. B-1 (durability) — the blue/green path is durable by construction; the symptom most likely means "no rebuild completed"

- **The promoted generation provably carries the fingerprint.** The green cutover runs
  `finalizeEmbeddingRebuildBeforeCutover()` (deterministic ECC flip on the drained green) →
  `commitWithBuildState(COMPLETE)` (stamps `embedding_model_sha256` via the overlay) →
  `verifyGreenCommitMetadataBestEffort` which **refuses to promote** a green whose committed
  `embedding_model_sha256` is missing or ≠ current (`KnowledgeServerMigrationOps.java:215-235,304-341`;
  `KnowledgeServer.java:1828-1854`). On restart, `refresh()` reads the **served** generation's commit
  user-data (`EmbeddingCompatibilityController.java:85-127` ← `latestCommitUserDataBestEffort`), so a
  durably-promoted green ⇒ COMPATIBLE. **There is no static path by which a *promoted* green reverts to
  BLOCKED_LEGACY on a clean restart.**
- **So "BLOCKED_LEGACY after every clean restart" is most consistent with: the served generation was
  never replaced by a fingerprinted green** — i.e. **no blue/green rebuild ever completed** in the
  reopen environment. That is precisely what B-2 predicts (no reachable affordance) and what
  `maybeAutoStartEmbeddingRebuildAllPendingBestEffort` *cannot* auto-fix here: it only fires when
  `completed==0 && pending==docCount` (`EmbeddingCompatibilityController.java:172-189`), but the reopen
  index had `embeddingCoverage≈0.9755` (mostly **completed**) ⇒ auto-start is skipped ⇒ stuck
  BLOCKED_LEGACY with **only** a manual `core.rebuild-index` to escape — the one B-2 hides.
- **The honest residual (why B-1 still needs a test, not a dismissal).** Static reading cannot *prove*
  the absence of a durability window — only that the *designed* path is safe. The reopen is right that
  §38.2 shipped **without a rebuild→restart→assert-not-BLOCKED regression test**
  (`audit-driven-fixes-need-test`): the `GreenCutoverEmbeddingFpVerifyTest` pins the *verify predicate*,
  not the *survives-restart* property. **B-1's deliverable is that test** — and it doubles as the
  experiment that distinguishes "legacy never rebuilt" (B-2) from "rebuilt then reverted" (a real
  durability bug). Until it runs on an owned stack, B-1's *mechanism* is **not** confirmed as a
  durability regression; the evidence leans toward B-2.

## 62. Refined reopen design (supersedes the REOPENED §1-3 sketch where it conflicts)

The reopen framed the fix as "the capability the FE reads must be the compat boundary, not
`embeddingCoverage`." §59 shows the FE *already* reads the boundary; the real shape is:

- **B-2 (reachability) — the priority.** Make the full rebuild **reachable by a USER independent of the
  degraded banner** (§58 Option 1 ± 3). This is the dominant blocker and the cheapest real win.
- **B-3/B-4 (produce-side truthfulness) — one predicate, two facts (this is Design A, §48, finally
  motivated by data).** The `retrieval` composite is the wrong carrier for "can/did a doc-level dense
  query run": it (i) **omits** the live `UNAVAILABLE`/embedder-down block (over-claim, B-3) and (ii)
  **includes** CHUNK_EMBEDDING/LAMBDAMART that don't gate doc-level dense (under-claim, obs #480). The
  correct shape is the **one Worker `dense-serviceable` predicate** — `allowQueryEmbeddings() &&
  embeddingProvider.isAvailable()`, the *same* `resolveAutoDense` already uses — **projected once onto
  `/api/status`**, and the response's **`effectiveMode`** for *actuality*. Capability (could run) and
  actuality (did run) become two facts from one source; the banner consumes the predicate (not the
  coverage-polluted composite), the per-result indicator consumes `effectiveMode`, and Brain's raw
  `compatState` read agrees with both because they share the boundary. (Map `UNAVAILABLE`/no-dense
  states through the predicate so the over-claim hole closes; this is a strictly smaller change than the
  reopen's "stop reading embeddingCoverage," since nothing reads it.)
- **B-1 (durability) — prove it with the regression test** (§61), then either close as "no-bug, it was
  B-2" or fix the real window the test exposes. Do not ship a durability "fix" without the failing test
  first (`audit-driven-fixes-need-test`, the reopen's own flag).

## 63. The one owned-stack experiment (decisive, names the disambiguation)

On a **quiet owned** stack (coordinate, don't take over — current holder `b9e86b32`):
1. Open the existing legacy/partially-embedded index → confirm `/api/status` shows
   `compatibility.embeddingCompatState` and, **in the same response**, `readiness.composites.retrieval`
   — **does retrieval carry `index.embedding_legacy`?** If YES (banner = "Reindex required"), B-3's
   over-claim was the §59(b)/(c) snapshot/timing skew or an UNAVAILABLE moment; if the compat block says
   BLOCKED but retrieval is READY in the *same* payload, that is the §59(c) snapshot bug — the precise
   defect to fix.
2. Trigger `core.rebuild-index` (via API to bypass the B-2 UI gate) → let the blue/green green promote →
   **clean** graceful restart → assert `/api/status` is **not** BLOCKED_LEGACY and a default (AUTO) query
   returns `effectiveMode=HYBRID`. This is the B-1 regression test as a live smoke (then codify it).
3. Flip viewer audience to USER in the UI → confirm the Brain `core.rebuild-index` renders 0×0 (B-2),
   and that the chosen §58 fix restores it.

## 64. Open decisions for the user

1. **B-2 rebuild reachability (the product call):** make `core.rebuild-index` `Audience.USER`
   (recommended — self-service recovery in a single-user app), have Brain pass `viewer-audience="OPERATOR"`
   (matches Health, but re-hardcodes the gate), or add a dedicated always-available USER rebuild entry?
2. **Scope confirmation:** adopt this Part's re-ordering (B-2 dominant; B-3/B-4 = one produce-side
   predicate = Design A; B-1 = test-first), i.e. 598's remaining work is **reachability + one
   `dense-serviceable` predicate projected to `/api/status` + the durability regression test** — not
   "stop the FE reading embeddingCoverage" (which it doesn't)?
3. **Owned-stack coordination (§63):** the decisive B-1/B-3 disambiguation needs a quiet owned stack.
   Coordinate a window, or proceed static-only and land the regression test as the live gate?

---

# PART XIV — LONG-TERM DESIGN for the reopen (2026-06-17, second agent)

> Genre: design-theory (general, not implementation-level), scope-matched to the reopen's *actual*
> problem as PART XIII verified it — not to the reopen note's hypothesized roots. It builds on the
> existing authorities (595 verdict, 596 reason+remedy+availability, 600 reason-code channel, 598 R1
> predicate) and **adds exactly one structural element**, because the problem is exactly one
> mis-represented fact. The inventory (§66) shows most of the machinery already exists; the design is
> "wire the one missing edge + separate two facts that were fused," not new subsystems.

## 65. The one disease behind all four findings (restating §16 at the reopen's altitude)

598's thesis was *"represent the fact where it is true."* The reopen's B-1…B-4 are four symptoms of a
single unrepresented fact: **"can the user get meaning-based (doc-level dense) search right now, and if
not, what fixes it?"** This binary *capability* is computed once, at the Worker, by the exact predicate
issuance already uses — `allowQueryEmbeddings() && embeddingProvider.isAvailable()`
(`GrpcSearchService.java:412`) — but it is **not projected as its own observable.** Instead every other
consumer *reconstructs an approximation* of it from the **graded `retrieval` composite**, which is
neither necessary nor sufficient for it:

- **Not sufficient → over-claim (B-3).** A live dense-block that is not a `BLOCKED_*` compat state
  (the `UNAVAILABLE`/embedder-down arm, or a same-response snapshot skew) emits **no** retrieval reason
  code (`compatBlockedReason` maps only `BLOCKED_*`, `StatusLifecycleHandler.java:908-919`), so the
  composite reads healthy while dense is blocked → "fully semantic" over a keyword query.
- **Not necessary → under-claim (obs #480).** `CHUNK_EMBEDDING` + `LAMBDAMART_MODEL` also feed the
  **same** `retrieval` composite (`ReadinessDimension:18-19`), so passage-embedding/ranking gaps degrade
  it and the banner says "Showing keyword results" while doc-level dense actually serves HYBRID.
- **Reconstructed twice → disagreement (B-4).** Brain reads the *raw* compat state directly
  (`BrainSurface.ts:1433`); Search/Health read the *derived verdict* over the composite — two
  reconstructions of one fact, so they disagree exactly in the gaps above.
- **Remedy gated on the mis-derived signal → unreachability (B-2).** The only USER-reachable rebuild
  (the banner remedy, rendered *ungated*) appears **only when the verdict is degraded with a reindex
  cause** — i.e. only when the leaky reconstruction happens to be right; and the always-on Brain
  affordance is OPERATOR-gated to 0×0.

So B-2/B-3/B-4 are **one** problem (a fused, leaky representation of dense-serviceability), and B-1 is
orthogonal (durability — already structurally sound, §61).

## 66. What already exists that the design extends (inventory — most of it is built)

- **The predicate itself** — `resolveAutoDense`'s `compat.allowed() && embeddingProvider.isAvailable()`
  is the canonical "can dense run now" fact, already the issuance authority. The design makes it the
  *reference definition*; it does not invent a second one.
- **The reason→wording+remedy vocabulary** — `readinessNotice.CAUSE_ROWS` / `reasonFor` is the ONE
  closed channel (600), with `severity` already per-code (`warn` for reindex causes, `info` for
  optional/quality gaps) and `isReindexCause` already partitioning the "a rebuild fixes this" subset.
- **The verdict authority** — `computeVerdict` (595) is the single derivation seam the
  `verdict-derivation` gate protects; the banner consumes it, never re-reads `readiness.retrieval`.
- **The availability model** — `availability.ts` (596) already types "capability unavailable + reachable
  reason + remedy" (`unavailable{reason, remedy}` / `degraded{caveat}`) and deliberately does **not**
  gate on the leaky `retrieval==='degraded'` — the exact discipline this design generalizes to semantic
  search.
- **Actuality** — the response `effectiveMode` + the per-result retrieval indicator (598 R1) already
  carry "what *did* run."
- **The durable rebuild** — `core.rebuild-index → startMigration` blue/green, with verified-promote
  (`verifyGreenCommitMetadataBestEffort`) and served-generation metadata read on restart (§61).

The only thing missing is **one edge**: the binary capability is never *projected as its own fact*, so
consumers fall back to the graded composite.

## 67. The design — promote "dense-serviceability" to a first-class capability fact, separated from the graded composite

Three facts, each with **one** source, replacing today's fused two:

1. **Capability (binary, system-level): "can a doc-level dense query run right now?"** Sourced at the
   Worker as `!(allowQueryEmbeddings() && embeddingProvider.isAvailable())` → emitted as a
   **dense-blocking reason code** on the wire. This *extends* the existing `compatBlockedReason`
   produce-site to close its one hole: when the index is compatible but the embedder is unavailable
   (the `UNAVAILABLE`/down arm, B-3's over-claim), it must emit a dense-blocking reason
   (`embedding.embedder_unavailable`, a new `CAUSE_ROWS` row) — so the emitted reason codes become a
   **faithful projection of the issuance predicate**, not a partial one. Capability is read by the
   verdict/banner and by Brain — from the **same** codes — so over-claim (B-3) and cross-surface
   disagreement (B-4) become structurally impossible.

2. **Quality (graded, system-level): "is ranking / passage coverage full?"** The existing `retrieval`
   composite's *non*-dense-blocking reasons (`chunk_embedding.*`, `lambdamart.*`, throughput) keep
   their honest job — a **calm caveat**, never the binary "keyword results" claim. The banner words its
   *headline availability* strictly from the **dense-blocking subset** (the `isReindexCause` set + the
   new embedder code) and any quality reasons as a separate, calm secondary line. This closes the
   obs #480 under-claim *without* a new field: the codes already distinguish the two; the fix is that
   the banner's binary headline must derive only from the dense-blocking subset.

3. **Actuality (per-query): "did *this* query run dense?"** Unchanged — `effectiveMode` → the per-result
   indicator. Capability is a snapshot ("could run"); actuality is the per-query truth ("did run"); a
   brief, honest divergence during a transition is expected and is *not* a contradiction.

**Why the reason-code channel and not a parallel `denseServiceable` boolean** (rejecting the
over-structured alternative the reopen's §1 and PART IX §48 reach for): a separate boolean would be a
**second representation** of a fact the reason codes already carry — the exact forking 553/595 forbid —
and it would still need the cause for the remedy, which only the codes carry. Emitting the *negation of
the issuance predicate as a reason code* keeps **one** authority (the Worker predicate), projected once,
read by issuance (directly) and the banner/Brain (via codes). This is strictly less new structure than a
parallel boolean and is the faithful completion of Design A.

**The produce-side consistency invariant (closes the §59(c) candidate).** The `compatibility` block and
the `readiness.composites.retrieval` reasons within **one** `/api/status` response must be derived from
the **same** worker snapshot — so a response can never carry "compat BLOCKED" beside "retrieval ready"
(the snapshot skew that would let Brain and the banner disagree from the *same* payload). This is a
single-snapshot discipline on the Head's status assembly, not new machinery.

## 68. B-2 reachability — a coherence invariant, not new machinery

Once capability is truthful (§67), the truthful "Reindex required" banner appears exactly when dense is
unserviceable, and it already renders the rebuild remedy through the **ungated** `<jf-op-button>` path
(`SearchSurface.ts:1366-1372`; OpButton has no audience gate) — so the remedy is USER-reachable *when
shown*. Two residual coherence gaps remain, both small and both about *declaration coherence*, not
mechanism:

- **The invariant:** *a remedy named by a user-facing degradation projection must be invocable by the
  audience that observes the degradation.* Today `readinessNotice` hands a USER the remedy
  `core.rebuild-index`, declared `Audience.OPERATOR` — incoherent. The long-term resolution is to align
  the **declaration** with the product fact (593's thesis): **self-service recovery from a
  capability-blocking state is a USER action.** `core.rebuild-index → Audience.USER`; its safety is
  carried by its existing `MUST_COMPLETE` lease + confirm ceremony (risk/ceremony, not audience tier).
  This also makes the always-on Brain affordance render for a default-USER viewer (removing the 0×0)
  **without** re-hardcoding a per-surface `viewer-audience` override — the "gate theater" 511-followup
  removed.
- **Enforcement is a separate, optional choice** (per the tier-register philosophy): the coherence could
  be *gate*-enforced (extend the existing `check-readiness-reason-codes` gate so every `CAUSE_ROWS`
  remedy `operationId` resolves to a USER-audience op), or left prose. One documented instance
  (`structural-defects-no-repeat`) justifies the invariant; whether to spend a gate on it is the user's
  call, not required structure.

This makes rebuild reachability **independent of the capability signal being right** (the always-on USER
affordance), *and* the banner remedy correct *when* it is right — belt and suspenders, but each is a
one-line declaration/wording change over existing structure.

## 69. B-1 durability — no new structure; the design is a test

The blue/green path is durable by construction (§61): a green cannot promote without a current-model
fingerprint, and restart reads the served generation's metadata. The correct long-term "design" is
therefore **not** a durability protocol but the **regression test §38.2 omitted**
(`audit-driven-fixes-need-test`): *rebuild → graceful restart → assert not-BLOCKED + AUTO query returns
HYBRID.* It doubles as the experiment distinguishing "no rebuild ever completed" (the B-2 prediction)
from a real durability window. Only if the test goes red does new structure become warranted — and then
it is scoped by what the test exposes, not pre-designed.

## 70. Scope discipline — what this design deliberately does NOT add

- **No new observability service / capability signal subsystem** (respects 600's scope). Capability is
  projected through the *existing* reason-code channel; quality through the *existing* composite.
- **No parallel `denseServiceable` wire boolean** — it would fork the fact the codes already carry (§67).
- **No `'semantic'` entry bolted into `availability.ts`'s `documents/extract/agent` affordances** unless
  a *control* genuinely needs to gate on it — the banner/Brain consume the verdict/codes directly; adding
  an affordance kind for a fact only the system-banner reads would be structure for no consumer (AHA).
- **No per-query routing / pipeline-policy engine** — that is 580's lever map (PART IX §50); the binary
  capability is not query-adaptive.
- **No R4 reopening** — R4 (merged) keeps the embedder serviceable via CPU release; this design's
  embedder-unavailable arm is for the genuine no-model/failure cases, not the Online transient R4 closed.
- **No multi-user audience authorization** — the B-2 audience change is a single-user self-service tier
  realignment, not an authz layer (511's "Option A2" stays out of scope).

## 71. Net long-term end-state (scope-matched)

598's reopen end-state is small because the remaining problem is small and precise: **(A) the Worker
emits dense-serviceability faithfully (one new reason code closing the embedder-unavailable hole +
single-snapshot consistency), so the binary "is semantic search available?" is one fact read identically
by issuance, the banner, and Brain — separated from the graded retrieval-quality composite and from
per-query actuality; (B) `core.rebuild-index` is realigned to USER audience so the remedy named for a
user-facing block is invocable by that user (reachability independent of the signal); and (C) the
durability invariant is a regression test, not new structure.** Everything else stays as the existing
authorities (595/596/600/598 R1) already built it. This is the faithful completion of the doc's own
"represent the fact where it is true" thesis — applied to the one fact (dense-serviceability) that the
shipped R1+R3+R4 left reconstructed-from-a-proxy rather than represented-at-its-source.

---

# PART XV — USER-FACING / FRONTEND DESIGN for the reopen (2026-06-17, second agent)

> Method: re-read PART XIII/XIV for the user-visible surface, then inspected the **live** UI in the
> browser (read-only) against the running stack, and re-read the FE render code
> (`SearchSurface.renderRetrievalMode`/`renderDegradationNotice`, `readinessNotice`, `availability.ts`,
> `BrainSurface`). Grounded in what the UI *does today*, not the tempdoc alone. General/design-tier.

## 72. What is user-visible in this design (every element maps to an existing surface)

| Design element (PART XIV) | Surface today | User-facing? |
|---|---|---|
| **Capability truthfulness** (dense-serviceable faithfully reflected, B-3) | the Search **degradation banner** (`readinessNotice` → `SystemNotice`) + the Brain **"disabled until rebuild" card** (`BrainSurface.ts:1432-1454`) | **Directly** — it changes what the banner *claims* ("Reindex required" vs "fully semantic") |
| **Capability/quality split** (obs #480 under-claim) | the same banner's body copy | **Directly** — "keyword results" vs a calm "ranking simpler" |
| **Actuality** (per-query) | the **retrieval-mode indicator** (`renderRetrievalMode`, an inline `· Semantic + keyword` / `· Keyword` on the results meta-header) | **Directly** — the per-query tiebreaker the user reads |
| **Rebuild reachability** (B-2 audience) | the Brain `<jf-operation core.rebuild-index>` (0×0 today) + the banner remedy `<jf-op-button>` | **Directly** — whether a USER can *see/click* rebuild at all |
| **Cross-surface agreement** (B-4) | Brain vs Search/Health | **Directly** — whether two screens tell the user the same thing |
| **Durability** (B-1) | none directly; it makes the rebuild remedy's promise *true* | **Indirectly** — a remedy that doesn't stick is a broken promise |

So this is **substantial** user-facing work. Proceeding with the inspection.

## 73. Live inspection — what the UI actually does today (don't judge from the tempdoc)

Fresh browser pass against the running stack (`main` HEAD `cc293577b`, a **COMPATIBLE** 620-doc index;
the BLOCKED_LEGACY states are not reproducible read-only on this index — those rely on the prior live
captures in §38.4 / PART X §52-55, which screenshotted the three banner variants, the indicator, the
IndexingOverlay modal, and the Health verdict):

1. **The R1 softened loading copy is live and good.** The Search surface in-flight state reads
   **"Searching your documents — almost there…"** — no over-claimed "the AI is working on your query."
   (Confirms §38.4; the §32.2 over-claim is closed.)
2. **THE dominant live finding — a total render-wedge.** Every surface was **stuck**: Search on
   "Searching… almost there", **Brain on "Loading AI Brain…"** — none rendered its content — while the
   status bar read **"Reconnecting…"**. This is the §32.3 / 593-§S1 SSE/status render-wedge, and on this
   shared multi-stack machine it was **total**, not intermittent. **Decisive UX consequence (§77):** a
   truthful capability banner, an honest indicator, and a working rebuild button are *all inside surfaces
   the wedge prevents from rendering* — so 598's entire user-facing payoff is **gated on the surface
   actually painting**. Owned by 595/596, but a hard precondition for 598, not an aside.
3. **Brain shows an advisory overlay** ("Since you last looked, the assistant: 5 operations · Undo all AI
   actions · Save as macro · Mark as seen") floating over the still-loading body — an overlay paints while
   the capability content underneath does not, exactly the wrong priority when the user needs the
   capability state.

## 74. The load-bearing UX cut — three facets, three distinct on-screen slots

The reopen's B-2/B-3/B-4 are, on screen, one defect: **the UI conflates three different questions into
one banner, so it can be simultaneously wrong in two directions.** The correct frontend model gives each
its own honest slot (PART XIV's capability/quality/actuality, made concrete for the screen):

| Facet | The question | On-screen home | Source (one each) |
|---|---|---|---|
| **Capability** | "*Can* semantic search run right now?" (binary, system) | banner **headline** + Brain card | the dense-serviceable reason subset (PART XIV §67) |
| **Quality** | "Is ranking / passage coverage full?" (graded) | banner **secondary line**, calm | the non-dense-blocking composite reasons |
| **Actuality** | "Did *this* query run semantic?" (per-query) | the inline **retrieval-mode indicator** | response `effectiveMode` |

The user reads them as one coherent story: a *capability* headline says whether meaning-search is
available at all; a *quality* line (only if applicable) says ranking is simpler without alarming; and the
per-result *indicator* is the per-query ground truth that lets the user verify the headline against what
*this* search actually did. Today facets 1 and 2 are fused (one banner), which is why "fully semantic"
(over-claim) and "keyword results" (under-claim) both ship from the same control.

## 75. The banner redesign — re-key the three branches, add no new component

`readinessNotice` already produces three branches; they are keyed on the wrong axis. **Re-key them onto
the dense-serviceable signal**, fixing both error directions, reusing `SystemNotice` + the `CAUSE_ROWS`
wording (no new component, no new vocabulary):

- **Capability OFF** (dense-serviceable false — compat `BLOCKED_*` *or* the new embedder-unavailable
  code): headline **"Semantic search is off"**, body **"Rebuild the index to search by meaning — keyword
  search still works,"** remedy = the rebuild op. Fires whenever dense genuinely can't run — so the
  `UNAVAILABLE` hole can no longer render the calm "still fully semantic" copy (closes **B-3**). The
  "keyword search still works" half keeps it non-alarming (search isn't broken, just lexical).
- **Capability ON, quality reduced** (dense-serviceable true, but a `chunk_embedding.*` / `lambdamart.*`
  reason present): a **calm** line — **"Semantic search is on; ranking is simpler right now"** or
  **"Passage answers are still being prepared"** — and crucially **never "Showing keyword results"** (the
  doc-level dense leg *did* run). Closes the obs #480 **under-claim**.
- **Capability ON, full**: **no banner** (the positive signal is the per-result indicator, §76 — no
  persistent "all good" banner is added; that would be noise).

The severity mapping already exists (`reindex` causes = `warn`, optional/quality = `info`), so the tone
machinery is reused; the change is *which fact each branch reads* and the wording that stops the two
over/under-claims.

## 76. Rebuild reachability on screen (B-2) — one remedy, USER-reachable, honestly durable

- **The indicator is the positive/actuality signal** — keep `renderRetrievalMode` exactly: `· Semantic +
  keyword` when dense ran, `· Keyword` when it fell back. It is the per-query truth a user can trust over
  any system banner, and it already exists. (Only visible once results render — see §77.)
- **The rebuild affordance must render for a USER and be ONE control.** Today the Brain
  `<jf-operation core.rebuild-index>` is 0×0 (OPERATOR-gated) and the Search banner remedy is a *separate*
  ungated `<jf-op-button>` for the *same* op, while Library offers a *different* op (`core.reindex`,
  incremental) that looks like a rebuild but doesn't fix it. The user-facing fix: **`core.rebuild-index`
  becomes `Audience.USER`** (self-service recovery), so the Brain card's button paints for a default
  viewer *and* the banner remedy stays reachable — the **same** op, label, and confirm-ceremony in both
  places (the `CAUSE_ROWS` remedy already centralizes label/ceremony, so they cannot drift). The
  incremental `core.reindex` on Library must be visually distinct from "rebuild to enable semantic" so the
  user isn't offered two near-identical buttons that do different things.
- **The honesty dependency on B-1.** A one-click "Rebuild to enable semantic search" that silently
  reverts on restart is a **broken promise — worse than no button** (the 600 PART IV constraint). So the
  remedy's *presence* is only honest once durability holds (the §69 regression test). UX rule, stated:
  **never offer a one-click fix that doesn't stick.** This couples the visible B-2 remedy to the
  invisible B-1 guarantee.

## 77. The hard precondition this design cannot ignore — the render-wedge (§73.2)

Every element above lives **inside** a surface that, live, was stuck on "Reconnecting…/Loading…". A
perfectly truthful capability banner the user never sees is worth nothing; a reachable rebuild button on a
surface that won't paint is unreachable in practice. So the **frontend design must treat surface-render
reliability as a precondition, not an externality.** Two consequences for 598's user-facing scope:

1. **Coordinate with 595/596 (who own the wedge) — do not ship 598's UX as "done" while the wedge can
   blank the surface.** 598 should not *re-fix* the wedge (wrong owner), but it must not pretend its
   truthful banner reaches the user until the surface reliably renders. A named cross-tempdoc dependency,
   surfaced by the live pass.
2. **Degraded-but-rendering beats blank.** Where 598 controls it, the capability/indicator/remedy should
   render from the **last known** observed-state during a reconnect rather than being withheld behind the
   connection gate — i.e. the capability facets should follow the 595 *Stability* "provisional but show
   last-known" discipline, not vanish into a "Loading…" placeholder. (Design intent only; the gating
   mechanism is 595/596's.)

## 78. Scope discipline (frontend)

- **No new components or surfaces.** Reuse the existing degradation banner (`SystemNotice` +
  `readinessNotice`), the existing retrieval-mode indicator (`renderRetrievalMode`), the existing Brain
  capability card + `<jf-operation>`, and the existing reason+remedy vocabulary (`CAUSE_ROWS`) and
  availability model (596 `availability.ts`).
- **The only changes are wording, keying, and one audience declaration:** re-key the banner's three
  branches onto the dense-serviceable signal (§75), keep the indicator as-is (§76), and flip
  `core.rebuild-index` to USER so the rebuild paints for the user it is offered to.
- **No persistent "all systems semantic" banner** (the per-result indicator is the positive signal —
  adding a standing positive banner is noise the problem does not call for).
- **No new "semantic" entry in `availability.ts`'s `documents/extract/agent` affordances** — the banner
  and Brain card consume the capability signal directly; an affordance kind for a fact only the
  system-banner reads would be structure for no consumer (AHA).
- **Out of scope (owned elsewhere):** the render-wedge itself (595/596 — §77 is a *dependency*, not 598
  work), the IndexingOverlay copy (already narrowed by R4), and the broader search/agent window
  convergence (577).

---

# PART XVI — CONFIDENCE-BUILDING RESULTS (2026-06-17, pre-implementation de-risk)

> A read-only de-risk pass against the PART XIV/XV design *before* any implementation — to turn the
> theorized roots into known ones. Tier A (static, 5 probes) ran in full; Tier B (live BLOCKED_LEGACY
> repro) was **deliberately not run** (rationale in §80). One probe surfaced a genuine design-affecting
> surprise (§79.A2). Net: the design is sound, with two refinements and two residual live items folded
> into implementation. No code changed.

## 79. Tier A findings (static — all five closed)

- **A1 — snapshot skew RULED OUT.** `/api/status` is assembled from a **single** `workerView` fetched
  once (`StatusLifecycleHandler.java:268`); both the `readiness` envelope
  (`buildReadinessEnvelope(workerView,…)`, :289) and the serialized `compatibility` block (:349) derive
  from that same view. So within one payload the compat block and the `retrieval` composite **cannot**
  disagree — the §59(c) "compat BLOCKED beside retrieval READY in one response" candidate is eliminated.
  *Consequence:* the §67 "single-snapshot consistency invariant" is **already satisfied** — drop it from
  the design as already-true (one less thing to build).
- **A2 — B-2 audience flip: contained blast radius BUT a real policy surprise.** Flipping
  `core.rebuild-index` OPERATOR→USER touches ~5 sites (`CoreOperationCatalog.java:413`, the
  `ui-operation-wire.golden.json` audience, and `AgentOperationEmitterCoreCatalogIntegrationTest`
  audience/filter assertions). **Surprise:** the op carries `ExecutorTag.AGENT`, and the agent-tool
  filter offers a USER+AGENT op to the model — so a naïve flip would **hand the agent a destructive
  full-corpus rebuild** (today it is filtered *because* it is OPERATOR). Surface audiences are static
  (not computed from op audiences), so Brain/Health audiences are unaffected; recovery invocation is
  audience-agnostic. *Consequence — refine §76/§68:* the B-2 fix is **"USER audience + drop
  `ExecutorTag.AGENT`"** (UI/self-service only, not an agent tool), OR keep OPERATOR and have Brain pass
  `viewer-audience="OPERATOR"` like Health. The "plain flip to USER" is **not** safe as-is. This is a
  user decision, now with the agent-exposure consequence made explicit.
- **A3 — rebuild re-embeds + stamps: confirmed.** `MigrationOps.startMigration` issues the worker
  `startMigration` RPC; the worker builds an **embedded** green and `verifyGreenCommitMetadata` refuses
  promotion without a matching `embedding_model_sha256` (§61). The B-2 remedy's promise ("rebuild → dense
  works") is mechanically real.
- **A4 — the embedder-unavailable arm is RARE post-R4; the real hole is `UNAVAILABLE`-not-mapped.**
  `isAvailable() == available && !closed` (`EmbeddingService.java:500`); R4's `releaseGpuSession()` leaves
  `available` true. So post-R4 the embedder is unavailable on a *COMPATIBLE* index only in genuine
  no-model/failure cases — rare. The structurally-real B-3 produce-side hole is that compat state
  **`UNAVAILABLE`** (no embedding model) sets `allowQueryEmbeddings()==false` (dense blocked) yet
  `compatBlockedReason` maps only `BLOCKED_*` → returns null → "fully semantic" over-claim.
  *Consequence — sharpen §67:* the "one new reason code" is precisely **map compat `UNAVAILABLE` → a
  dense-blocking reason**, not a separate embedder probe; the conjunct is mostly redundant with compat
  post-R4.
- **A5 — the banner remedy invokes for a USER: confirmed.** `OperationClient.invoke` is a plain
  `POST /api/operations/{id}/invoke` with **no** audience/authz gate (`OperationClient.ts:166-193`); the
  banner's `<jf-op-button>` path has no `operationVisibleTo`. So a truthful "Reindex required" banner
  yields a working USER remedy — B-2's banner half is sound; only the always-on Brain affordance needs
  the §79.A2 decision.

## 80. Tier B (live) — deliberately deferred, with rationale and the cheaper substitute

The live BLOCKED_LEGACY repro (B-3 (a)-vs-(b) disambiguation; B-1 end-to-end witness) was **not** run:
the shared dev stack is **owned by an active session** (`b9e86b32`) and is COMPATIBLE + render-wedged,
and standing up a *competing* isolated backend would contend for the LFS models + memory (Windows
memory-pressure caveat) and risk disrupting that session — a poor trade for a confirmation the static
work already largely pinned. Instead:

- **B-1 durability is now statically decisive (§ above + the generation-pointer finding):** restart opens
  whatever `IndexGenerationManager` `state.json.active_generation` points to (atomic tmp+prev write), and
  the cutover **only advances that pointer to the green when `verifyGreenCommitMetadata` passes**. So
  "BLOCKED after restart" ⟺ the green never stamped/verified ⟺ the pointer stayed on blue ⟺ **no durable
  rebuild completed** (the Fix-E surface / B-2 linkage) — *not* a generation-selection bug. The B-1
  regression test is **feasible**: the controller already drives BLOCKED_LEGACY deterministically
  (`EmbeddingFingerprint.setForTesting` + `refresh()`, `EmbeddingCompatibilityControllerTest`), so the
  test = drive a real migration → stamp → reopen-on-the-served-pointer → assert COMPATIBLE. It belongs in
  the implementation phase as the deliverable, not a standalone live run.
- **B-3 (a)-vs-(b) is the only residual live unknown,** and the design covers **both** branches anyway
  (map `UNAVAILABLE` for (a); the FE consumes last-known `/api/status`, so a stale snapshot (b) is a
  freshness/poll-cadence concern, partly the §77 "render last-known" intent). A coordinated single
  `/api/status` capture under BLOCKED_LEGACY (when a stack is free) would confirm which branch the reopen
  hit, but it does not change *what gets built*.

## 81. Net design refinements from this pass

1. **Drop** the "single-snapshot consistency invariant" from §67 — A1 proved it already holds.
2. **The B-3 produce-side fix is specifically "map compat `UNAVAILABLE` → a dense-blocking reason code"**
   (A4) — narrower than a general embedder probe.
3. **The B-2 audience fix is "USER + drop `ExecutorTag.AGENT`" (or OPERATOR + Brain surface-override)**,
   not a plain USER flip — because USER+AGENT would expose a destructive rebuild as an agent tool (A2).
4. **B-1 stays test-first** and is feasible at integration tier; the durability mechanism is understood,
   so the test is a guard, not a discovery.

## 82. Critical confidence rating (remaining work, 0–10)

| Item | Confidence | Why |
|---|---|---|
| **B-2** (reachable rebuild) | **8** | Blast radius mapped (A2), remedy invocation confirmed (A5); the only open part is the *decision* USER+drop-AGENT vs OPERATOR+override — a choice, not an unknown. |
| **B-3 / B-4** (capability truthfulness) | **7** | Produce-side fix now precise (map `UNAVAILABLE`, A4); skew ruled out (A1); FE read path confirmed (PART XIII). Residual: the (a)-vs-(b) live branch — covered by the design either way, hence not blocking. |
| **B-1** (durability) | **7** | Mechanism statically decisive (state.json gated by green-verify); test feasible. Residual: a real end-to-end migration+restart has not been *witnessed* green — the test will be the proof. |
| **Render-wedge dependency (§77)** | **5** | Real, live-confirmed, blocks user-visible value, but **owned by 595/596** — 598 cannot close it alone; coordination risk, not a 598 unknown. |
| **Overall remaining 598 work** | **7/10** | Up from ~5 pre-pass. The design's mechanics are de-risked and two surprises (agent-exposure; UNAVAILABLE-not-BLOCKED) are now designed-for. The two residuals (B-1 live witness, B-3 live branch) are folded into implementation, and the external wedge dependency is the main thing outside 598's control. |

---

# PART XVII — CROSS-TEMPDOC COORDINATION, refreshed (2026-06-17, supersedes PART VI)

> Re-scan of the 593-cluster siblings (within 20 of 598) modified in the last 5 h, plus active
> worktrees. PART VI was dated (it referenced a `596-remaining` worktree that no longer exists and
> a wedge owner that has since moved). **Verdict: no hard blockers and no worktree contention; one
> ownership correction to this doc (§77 wedge), one must-co-land coordination (600), and one
> contained long-term risk (the dense-serviceable fact's multiple consumers) that 598's own §67
> design already mitigates.**

## 83. Scan inputs (verified, not assumed)

- **Active worktrees: NONE** (`git worktree list` → only `F:/JustSearch [main]`). So there is **no
  worktree-based merge contention** for 598's remaining work. (603's note about "active 598 `-impl`
  worktrees" is **stale** — none exist now; treat as history.)
- **In-window siblings (modified < 5 h, # within 20):** 593, 595, 597, 600, 602, 603, 604, 605.
  (599 @ 15:08 and 601 @ 13:12 just miss the window; 599 still defers to 598 — favorable.)

## 84. The interference map (per sibling, against 598's remaining files: `readinessNotice.ts`/CAUSE_ROWS, `StatusLifecycleHandler`, `verdict.ts`, `SearchSurface.ts`, `BrainSurface.ts`, `CoreOperationCatalog`)

- **600 (degradation cause) — THE must-co-land coordination (complementary, not conflicting).** 600
  is **downstream** of 598: its PART XIV is a *negative guarantee* — "given a truthful verdict, the
  three render sites already word BLOCKED_LEGACY honestly." 598 supplies that truthful signal. The
  shared edit surface is real: 598's **new "map compat `UNAVAILABLE` → dense-blocking reason" code
  (§81.2)** MUST get a `CAUSE_ROWS` wording row + a `governance/readiness-reason-codes.v1.json` entry,
  or it trips 600's `check-readiness-reason-codes` gate. **Action: land 598's source + 600's wording
  coherently (598-source → 600-copy, or one change).** The 598/600 boundary (598=SOURCE, 600=COPY)
  holds; no goal conflict.
- **602 (residual FE reliability) — CLEAN boundary, plus an ownership correction for this doc.** 602
  explicitly **promoted the SSE/"Reconnecting…" render-wedge (its R1+R2) to a NEW doc 604**, and keeps
  the **trace** reason vocabulary (`SearchReasonCode`/`reason-codes.v1.json`, its R6 "sparse-shortcut
  illegible") **deliberately separate** from the readiness `CAUSE_ROWS` ("without merging the two
  vocabularies", AHA). So 602 does **not** overlap 598's readiness reason-code work. **Correction to
  §77 below.**
- **595 (verdict authority) — foundation; consume, don't fork.** 595's split-verdict (#1.1) and
  data-loss (#1.2) shipped; its remaining work is the `Stability`-axis substrate. 598 operates at the
  `readinessNotice`/banner layer *consuming* `computeVerdict`, so it stays inside the
  `verdict-derivation` gate's seam — different layer, low conflict. If 595's Stability work reshapes
  the verdict's reason shape, 598's banner re-key tracks it (consumer, not fork).
- **597 (search count) — same-file, favorable.** 597 (reopened for the Chat-surface count) edits
  `matchCountLabel`/`effectiveMode` in `SearchSurface.ts` — the **same file** as 598's banner re-key +
  retrieval indicator, and `effectiveMode` is *shared favorable substrate* (598's indicator already
  reads it). Merge-order coordination only; no goal conflict.
- **603 (RAG trust) — investigated as 598-INDEPENDENT; already merged.** Its reopen status says
  "entangled with 598's BLOCKED_LEGACY," but its own D-2/D-3 investigation **corrected that**: the
  agent grounded-source gap is an agent-tier grounding-*granularity* gate (`parent_doc_id` on the
  doc-level pipeline), "a faithful projection of what was retrieved in ANY retrieval mode —
  598-independent, no new type/authority/proto change," and D-5/U6 confirmed "598 composes (no file
  conflict)." Its earlier C2 (decontextualization) overlapped 598's `GrpcSearchService` but was
  explicitly de-conflicted (proto-free, sequenced after 598) and is **merged**. **Not a 598
  interference risk.**
- **604 (diagnostic surface liveness recovery) — the new wedge owner; UNDESIGNED.** Open, "problem
  statement + investigation framing only." It now owns the §77 render-wedge (promoted from 602 R1/R2).
- **605 (agent-window stacked-run reliability) — different domain (agent run singletons), no overlap.**

## 85. Correction to §77 — the render-wedge dependency owner moved to 604

§77 names the wedge "owned by 595/596." Per 602's second-pass dispatch that is now stale: **the
SSE/"Reconnecting…" request-lifecycle wedge is owned by 604** (602 R1+R2 promoted), with **595**
owning only the settled-vs-provisional *rendering* axis. So 598's hard user-facing precondition
(§73.2/§77 — a truthful banner/remedy is worthless on a blanked surface) is **gated on 604**, which is
**still undesigned** (problem-statement only). This makes the wedge the single largest external risk to
598's user-visible payoff, and it points at a doc that has not yet started design — worth flagging to
the user for sequencing.

## 86. The one genuine long-term risk (PART VI risk D), now measured and contained

The "is dense/semantic retrieval available?" fact has multiple cluster consumers. Measured against
`main`, the fork risk is **contained**, not realized: **600 consumes it by design (598 is the SOURCE);
603 was found 598-independent (it doesn't read dense-availability); 602 keeps the trace vocabulary
separate.** So the only thing 598 must do to keep it contained is exactly its §67 design — **project the
ONE dense-serviceable signal once, and let 600 consume it** — rather than letting a second derivation
appear. No sibling currently forks it; the risk is "don't introduce a second source," which 598 owns.

**Net:** 598's remaining work has **no hard blocker and no worktree contention**; it needs (1) a
co-landing with 600 on the reason-code wording, (2) merge-order care with 597 on `SearchSurface.ts`,
(3) to consume 595's verdict without forking, and (4) awareness that its user-facing value is gated on
**604** resolving the render-wedge.

---

# PART XVIII — AS-BUILT: reopen B-1/B-2/B-3/B-4 implemented (2026-06-17, worktree-598-reopen-impl)

> Implemented in worktree `worktree-598-reopen-impl` (off `main` @ `cc293577b`). MERGED to `main` (merge
> `98cb80cef`, 2026-06-18); post-merge `build -x test` + touched tests + 3195 FE tests + gates green.
> Structural approach (faithful capability projection through existing channels — no new wire field,
> no new subsystem). The design rationale (PARTS XIII–XVII: the dense-serviceability-as-one-fact
> thesis, the confidence pass, the cross-tempdoc coordination) lives on the `main` working copy; this
> section records the as-built against the four reopen findings.

## Recap of the reopen findings this closes
- **B-3 (over-claim):** the search banner could read "fully semantic" while a query actually ran
  keyword — because the `retrieval` readiness composite only carried a reason for the `BLOCKED_*`
  compat states, leaving the other "dense can't run" states (no embedding model / embedder down)
  un-degraded.
- **B-4 (cross-surface disagreement):** AI-Brain (raw compat read) and Search/Health (the derived
  verdict) could disagree about whether semantic search works — same root as B-3 (the verdict's
  composite didn't reflect the full dense-block).
- **B-2 (unreachable rebuild):** `core.rebuild-index` was `Audience.OPERATOR`, so the Brain
  `<jf-operation>` rendered 0×0 for the default USER viewer — the user was told to rebuild with no
  visible control.
- **B-1 (durability):** the §38.2 blue/green-reuse claim shipped without a rebuild→restart regression
  test (the surviving-across-restart property was never exercised).

## What was built

### B-3 / B-4 — the `retrieval` composite now reflects dense-serviceability (the SOURCE fix)
- **New reason code** `LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE("index.dense_unavailable")`
  (`modules/app-api/.../lifecycle/LifecycleReasonCode.java`) — distinct from the `index.*_legacy/_mismatch`
  reindex causes (those have a rebuild remedy; this one does not — a reindex won't add a missing model).
- **`StatusLifecycleHandler.denseUnavailableReason(workerView)`** (new static, `modules/ui/.../StatusLifecycleHandler.java`)
  emits `index.dense_unavailable` for the POSITIVELY-known not-serviceable states only: compat
  `UNAVAILABLE` (no embedding model), or compat `COMPATIBLE` with `embeddingReady == false` (embedder
  down on an otherwise-good index). It is a faithful Head-side reconstruction of the Worker's own
  issuance predicate `denseServiceable = allowQueryEmbeddings()(==COMPATIBLE) && embeddingProvider.isAvailable()`
  — using `embeddingReady` (which equals `isAvailable()` on the Worker, `GrpcHealthService`) — and reuses
  existing wire fields (no proto change). It deliberately does NOT fire for `BLOCKED_*` (handled by
  `compatBlockedReason` with the rebuild remedy), `REBUILDING` (owned by the 595 Stability axis), or an
  unknown/null `embeddingReady` (we never alarm on "don't know" — avoids startup false-positives).
- The `INDEX_SERVING` arm now emits this reason (DEGRADED), ranked just below `compatBlockedReason`, so a
  serving-but-dense-blocked index degrades the `retrieval` composite the 595 verdict consumes → the banner
  can no longer over-claim "fully semantic" while AUTO degraded to keyword.
- **FE wording** (`readinessNotice.ts` `CAUSE_ROWS`): the new code words *"Semantic search is unavailable
  right now — showing keyword results"* (`severity: warn`, Open-Health fallback remedy — not a reindex
  cause). The final consumed-copy is co-owned with 600 (the consumed-copy authority); this is the
  source-side vocabulary entry the gate requires.
- **B-4** falls out: the verdict and Brain's raw-compat read now reflect the same dense-block.
- **Tests:** `StatusLifecycleHandlerTest` gains 6 cases — `denseUnavailableReason` for `UNAVAILABLE`,
  for COMPATIBLE-but-`embeddingReady=false`, null for COMPATIBLE+ready and for unknown (null), null for
  REBUILDING, plus two end-to-end rollups asserting the `retrieval` composite goes DEGRADED carrying
  `index.dense_unavailable`.

### B-2 — `core.rebuild-index` is USER self-service + UI-only
- `CoreOperationCatalog.rebuildIndex()`: `Audience.OPERATOR → Audience.USER` and
  `Set.of(ExecutorTag.UI, ExecutorTag.AGENT) → Set.of(ExecutorTag.UI)`. USER makes the Brain
  `<jf-operation>` render for the default viewer (no 0×0) and keeps the degradation-banner remedy
  reachable; **dropping `ExecutorTag.AGENT` is required** so a USER op is not offered to the agent tool
  list (a USER+AGENT op would hand the model a destructive full rebuild — the emitter gates on
  `targetExecutor()==AGENT`, so UI-only keeps it out via the executor, not the audience). Safety stays on
  the op (HIGH risk + Inline confirm + WorkerOnline).
- Updated the pinned wire output (`ui-operation-wire.golden.json`: audience USER, executors UI-only) and
  `AgentOperationEmitterCoreCatalogIntegrationTest` (the precondition now pins the new USER+UI-only
  contract; the "filtered from agent list" assertion holds via the missing AGENT executor). Recovery use
  (`LifecycleSnapshotTap`) is audience-agnostic; HealthSurface still shows it (USER ops are visible to an
  OPERATOR viewer).

### B-1 — durability regression tests
- `EmbeddingFingerprintDurabilityTest` (`modules/indexer-worker/.../embed/`, the primary, real-persistence
  proof): a commit that stamps `embedding_model_sha256` via `EmbeddingMetadataOverlay` SURVIVES a
  close→reopen of the real Lucene index → a fresh `EmbeddingCompatibilityController` reading the reopened
  commit's user-data resolves `COMPATIBLE`. Negative control: an unstamped commit reverts to
  `BLOCKED_LEGACY` on reopen (proves the test bites).
- `IndexGenerationManagerRestartTest` (`modules/worker-core/.../index/`): after a migration promotes a
  green via the on-disk `state.json active_generation` pointer, a fresh manager on the same dir (a
  restart) opens THAT generation, not the old blue. Together the two prove a completed rebuild durably
  escapes BLOCKED_LEGACY — so the reopen B-1 symptom maps to "no green was promoted," not a
  selection/persistence bug.

## Verification

**Static (worktree, all green):**
- `./gradlew.bat spotlessApply` + `./gradlew.bat build -x test` (incl. `verifyGovernanceGates`) — BUILD SUCCESSFUL.
- Module tests: `app-api`, `ui` (StatusLifecycleHandlerTest 21/0 — all 6 new dense cases green), `app-services`
  (AgentOperationEmitterCoreCatalogIntegrationTest 5/0, RegistrySnapshotExporterTest 7/0, CoreOperationCatalogTest
  29/0, UIOperationEmitterTest 4/0), `worker-core` (IndexGenerationManagerRestartTest), `indexer-worker`
  (EmbeddingFingerprintDurabilityTest) all green. (The only failure across the run was the pre-existing
  env-prerequisite `AiInstallServiceLateBindTest` — "Worker lib directory not found"; it passes once
  `:indexer-worker:installDist` runs, unrelated to this change.)
- FE: `npm run typecheck` clean; `npm run test:unit:run` — 3179 passed.
- Gates: `check-readiness-reason-codes` ✓ (31 emittable codes, 22 worded rows), `check-search-issuance` ✓,
  `check-verdict-derivation` ✓, `class-size` ✓ (declared-growth changeset for the StatusLifecycleHandler +
  CoreOperationCatalog growth).

**Live (final batch) — DONE.** Verified end-to-end in the browser against a backend running THIS
worktree's build (user authorized a dev-stack takeover; the worktree dev-runner was launched with
`JUSTSEARCH_MODELS_DIR` → main's models, `--skip-build` over the worktree's installed dists, and a
worktree data dir seeded from main's BLOCKED_LEGACY index). The running wire confirmed the new code was
live: `GET /api/registry/operations` → `core.rebuild-index` `audience=USER, executors=['UI']` (the main
checkout served `OPERATOR, ['AGENT','UI']` — confirming the pre-fix baseline). Then, on the same
BLOCKED_LEGACY index:

1. **B-3 truthful banner (NOT "fully semantic").** The Search surface rendered *"⚠ Reindex required.
   Semantic search is degraded until the index is rebuilt — results may be keyword-only · Semantic search
   isn't available on this index yet — rebuild it to enable meaning-based results"*, and the AI-Brain card
   rendered *"Embedding model fingerprint missing — … Vector and hybrid search are disabled until you
   rebuild the index"* (B-4: the two surfaces agree). `/api/status` → `retrieval` composite `DEGRADED`
   carrying the real reason code. No "fully semantic" over-claim. (The new `index.dense_unavailable`
   code's specific UNAVAILABLE/embedder-down wording is unit-verified — `StatusLifecycleHandlerTest`'s 6
   cases — over the identical composite→banner machinery proven live here.)
2. **B-2 USER rebuild button renders.** On THIS build the AI-Brain "fingerprint missing" card renders the
   **"Force Rebuild"** button (audience `USER`); on the main build (audience `OPERATOR`) the same card
   renders the button **0×0** — the exact 0×0→visible fix, screenshotted both ways.
3. **B-1 + R1 end-to-end.** Triggered the rebuild → the blue/green migration re-embedded the 600-doc green
   (`MIGRATING`/`REBUILDING`, `embedPending` 600→0) and promoted it through the cutover restart →
   `embeddingCompatState=COMPATIBLE`. The SAME default (no-mode/AUTO) query then returned
   `effectiveMode=HYBRID, decision=multi_leg`; the Search header read **"Top 50 of 607 matches · 531ms ·
   Semantic + keyword"** and Pipeline-details showed **`Sparse (BM25) executed · 607 docs`**,
   **`Dense (vector) executed`**, **`Fusion executed (hybrid) · 152 docs`**, `Cross-encoder executed` —
   i.e. the dense leg ran end-to-end after a durable rebuild (BLOCKED_LEGACY → keyword-only → rebuild →
   COMPATIBLE → hybrid).

All four reopen findings (B-1/B-2/B-3/B-4) are now verified at the unit/integration tier AND live in the
real UI on this worktree's build, and **MERGED to `main`** (merge `98cb80cef`, 2026-06-18) — post-merge
`build -x test`, the touched/new module tests, FE typecheck + 3195 unit tests, and the
readiness-reason-codes / search-degradation-reason / search-issuance / verdict-derivation / class-size
gates all green. Remaining (not blocking, owned elsewhere): co-land the new code's final consumed-copy
wording with 600; the obs#480 under-claim (600); the render-wedge (604).

## Post-merge follow-up (2026-06-18)
- **600 co-land — RESOLVED (coherent by construction, no 600 edit needed).** Verified the consumed-copy
  surfaces all read the SHARED vocabulary my row is in, so `index.dense_unavailable` is handled
  coherently with no 600 change: the search degradation banner reads it via `readinessNotice`
  (`CAUSE_ROWS`/the 595 verdict); `CapabilityMap` (600's "What you can do right now") does NOT consume
  reason codes directly — it reads `projectAvailability` (`availability.ts`), whose `documents`/RAG
  `degraded` caveat consumes the same 595 verdict, so a dense-block surfaces as "Showing keyword-ranked
  results — semantic ranking is degraded" there. One vocabulary, every surface consistent.
- **FE behavioral test added** (`readinessNotice.test.ts`): pins that
  `severityForCodes(['index.dense_unavailable'])` is `warn` (so the banner can NEVER take the `info`
  "still fully semantic" branch — the B-3 over-claim guard) and that the banner words it as capability-OFF
  ("Semantic search degraded / keyword results") with the Open-Health remedy and NOT a reindex headline.
  Closes the FE consume-side coverage gap that complements the backend produce-side
  `StatusLifecycleHandlerTest` cases.
- **`index.dense_unavailable` specific-state LIVE demo — deemed adequately covered by tests.** The exact
  `UNAVAILABLE`/embedder-down state is impractical to induce on a live stack without disrupting another
  agent's owned stack; it is covered end-to-end by the backend composite-rollup tests + this FE wording
  test, and the integration path (BLOCKED_LEGACY → truthful banner → rebuild → HYBRID) was live-verified.
  A live capture of this specific state remains an optional future check if a stack is freed.
