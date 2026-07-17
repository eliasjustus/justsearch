# 720 — Memory read-injection: let the model see what it remembered

- **status:** open — DESIGN/ROUTING 2026-07-12. Two go-now slices SHIPPED (MMR field-adapter
  PR #170; per-sentence grounding-badge fix PR #171). Memory read-injector designed (GO 7/10)
  but HELD on a new Head↔Worker embedding RPC prerequisite — full plan: 752-memory-injector-plan.md;
  P1a held on a buried-signal corpus — full plan: 753-p1a-context-prepend-plan.md.
  Remaining parked levers trigger-checked — none fired (see §Parked levers).
- **created:** 2026-07-12
- **author-role:** orchestrator (Opus) — design/judgment; implementation delegatable to sonnet
- **relation:** outcome of a 2026-07 RAG generation-layer design review. Beyond its own
  memory-injection design, this tempdoc is the **reassessment hub** for the parked RAG levers the
  review surfaced (see §Parked levers), each connected to its owning doc: ADR-0006 Alt B/C/D,
  tempdoc 636 P1a/P1b, 270 U14, 603, 245. Supersedes the stale conversation-memory scope-out in
  tempdoc 345.

## Problem

Conversation **memory is write-only with respect to the model.** `MemoryExtractionConsumer`
runs unconditionally on both `core.rag-ask` and `core.free-chat` turns, extracts user
facts/preferences via a string-cue heuristic (no LLM call, `559 §15` cost-realism), and writes
them to the single-authority `MemoryStore`. But **no prompt contributor or context injector
reads that store** — verified by grepping every `memoryStore.` call site: the writers are
`MemoryExtractionConsumer` and `RememberFactHandler`; the *only* production reader is
`MemoryController` (the human-facing `GET /api/memory` panel). So the LLM never sees a fact the
user stated earlier. There is no cross-session personalization of answers — a user can tell the
assistant "I prefer metric units" and the next turn's answer is unaffected.

The extract-and-store half is built and tested; the read-and-inject half does not exist.

## Design (proposed — not yet implemented)

A **relevance-gated memory-read injector** that closes the loop with the infrastructure we
already run locally — no new dependency, no new network surface, privacy-preserving (everything
stays in the local, user-inspectable, user-forgettable `MemoryStore`).

1. **Read** `MemoryStore.whatItKnows()` during prompt construction (the
   `AgentPromptComposer` / `RAGAskShape` / `FreeChatShape` path — the sites confirmed *not* to
   read memory today).
2. **Score** stored records for relevance to the current turn using the **resident embedding
   model** (we already run one embed per query — marginal read-time cost), not lexical overlap
   (too weak for paraphrase). Cosine of the turn's query vector against each record's vector.
3. **Cap hard**: top-N under a small token budget, appended as one labeled block. Caps are
   **mandatory, not polish** — an always-inject-everything block blows the local context budget
   on a modest model. (Shipped local-app precedents all hard-cap; e.g. small global + per-scope
   limits.)
4. **Keep extraction as-is** (heuristic, no per-turn LLM). A richer LLM extractor is a separate,
   later, *eval-gated* question and, if pursued, must be idle/interval-gated (never per-turn) to
   preserve the `559 §15` cost-realism invariant — do **not** fold it into this tempdoc.

### Acceptance gate (eval, non-inferiority)
- **Primary:** a **PrefEval-style** multi-session preference-following probe — session 1 states a
  preference via the existing cues; session 2 asks a question whose correct answer depends on it.
  Metric: injected-memory answers apply the stated preference materially more often than the
  current no-injection baseline. (PrefEval, ACL 2025 Findings, is the right shape — single-user
  preference application — where multi-session social-dialogue recall benchmarks are not.)
- **Guardrail:** context-budget + latency non-inferiority (the read-time embed + block must not
  regress answer latency or crowd out retrieved evidence). Injection must degrade gracefully to
  "no block" when the store is empty or nothing scores above threshold.
- **Falsifier:** if injected memory does not improve session-2 preference application over
  baseline on the probe, the lever is falsified — do not ship.

## Parked levers — reassessment map (each connected to its owning doc)

A survivor cross-check (2026-07-12) confirmed that **every RAG lever except memory read-injection
is already-scoped-or-deferred internal work** — the same class of miss the review caught
for grounding/context. This map is the durable output: each lever routed back to its original
owner, with the trigger that would justify re-opening it. **None is implemented here; do NOT
re-open any of these as a "new" idea — pick up the owning doc.**

| Lever | Original owner | Status / why parked | Reassess when |
|---|---|---|---|
| Entailment citation scorer | **ADR-0006 Alt B** | Deferred: "ms-marco CE proved sufficient; NLI remains a future upgrade path" | ADR-0006's own triggers fire (LLM citation accuracy ≥95%, or CE latency a bottleneck) — check against our eval data first; prefer AlignScore-base (clean ONNX); training-data license is a policy call (shipped ms-marco scorer shares the lineage); measure CPU latency locally |
| Structural context-prepend | **tempdoc 636 P1a** | Rated "cheapest no-interface-change probe," never built (parked on an eval-isolation prereq now resolved) | now — run the measurement; needs a reindex |
| LLM contextual-retrieval | **tempdoc 636 P1b** | "real but not a uniform win" (636:396); LLM-per-chunk at index time | local-throughput eval shows it affordable AND P1a underperforms |
| MMR default | **tempdoc 270 U14** | ✅ **adapter+test MERGED (PR #170, 2026-07-12)** — was a clean bounded task (8/10). Corrections from the design pass: the `CHUNK_CONTENT`/`CONTENT` mismatch is **latent** (all current `diversifyByMmr` callers populate `CHUNK_CONTENT` — bug only bites future search-path reuse), and "~82% CE latency" is a **different path** (CE gated OFF in default hybrid RAG). The default **flip** is 4/10 — blocked on NEW diversity/distinct-fact eval infra that doesn't exist. | adapter+test now (defensive); flip only after a new diversity-metric eval clears |
| Per-sentence citation honesty | **tempdoc 603** (trust-calibration) | ✅ **Slice A (settled zero-cite badge fix) MERGED (PR #171, 2026-07-12)**, independently UX-audited (reuses `.grounding-badge-sourced`). Slice B (marking individual uncited sentences) deferred — needs UX-audit-closure + the sub-threshold wire data | Slice B taken up within 603 |
| HyDE | **tempdoc 245 item 23** | Deferred: "~1B+ model at search time… high cost for uncertain gain" (same single-lock latency issue) | 245's cost premise changes |
| Multi-query fan-out | **tempdoc 245** (query-expansion family) | 2–5× LLM calls before retrieval into the single request-lock (the same lock-contention concern as HyDE) | latency-budget design + a follow-up eval beating single-rewrite within budget |
| Disclose→prevent abstain rail | **ADR-0006 Alt C/D** (self-RAG / grammar decoding, both deferred) + 603 | needs entailment (Alt B) first + a mid-stream-abstain UX redesign | Alt B lands AND a streaming-abstain UX design exists |
| RAPTOR hierarchical summary | *(no owner — rejected)* | real incremental-indexing conflict + LLM-summarize-at-index cost | an incremental variant is shown affordable locally |

**Memory scope note:** `tempdoc 345` (2026-06-17) scoped conversation memory *out* ("no persistent
conversation memory"); that is now stale — the write-only `MemoryStore` was built afterward
(559/561 P-E), so this tempdoc's read-injection is the one genuinely-unproposed piece.

### Trigger-check results (2026-07-12 — all six parked levers checked, none fired)

Read-only trigger-checks (one per parked lever) verified at source whether any reassessment
trigger has fired. **None had** — the parking holds. Verdicts + corrections:

- **Entailment scorer (ADR-0006 Alt B) — NOT FIRED.** *Correction:* the "~82% of query latency"
  cross-encoder figure is the **search-result reranker** (`SearchTrace.crossEncoderMs`, ~200ms
  budget; tempdoc 640/648), NOT the citation scorer. The citation CE (`CitationScorerConfig`,
  2000ms budget) runs **after** the LLM finishes streaming (non-blocking by design, 603:463) and
  is **un-instrumented** — no latency-bottleneck evidence exists. Accuracy is stale (Feb-2026
  ~33–50% citation precision, far below the 95% trigger) and unguarded (no CI baseline). Next
  step = **measure** (re-run `RagQualityEvalTest`; instrument `CitationMatchOps`), not design.
- **HyDE (245) — PARTIAL / hold.** 2 of 3 cost premises shifted (a generative model is now
  resident + called pre-retrieval via `QueryRewriteInjector`; the GBNF prereq is resolved), but
  "cheap" is unestablished — a HyDE-length generation stresses the same single `onlineRequestLock`.
  Sharpened trigger: **measure `QueryRewriteInjector`'s live latency/lock-wait** before promoting.
- **P1b (636) — NOT FIRED / blocked on P1a** (P1a unmeasured; no local-throughput eval).
- **Multi-query fan-out (245) — NOT FIRED** (no budget design, no eval; `603:735` already lists it a deliberate non-action).
- **Abstain rail (ADR-0006 C/D) — NOT FIRED** (needs entailment first; `RAGAskShape` is ONE_SHOT).
- **RAPTOR — NOT FIRED / still rejected** (incremental-indexing conflict is live: `BackfillScheduler` + file-watcher E2E).

**Convergent finding:** the actionable next step for the parked levers is a small set of
**measurements**, not builds — a citation-accuracy re-baseline, a pre-retrieval LLM latency/lock-wait
probe, and the P1a structural-prepend measurement. Cheaper and more honest than promoting any to design.

## Non-goals / boundaries
- No implementation, no eval run, and no ADR-0006 trigger measurement without explicit go-ahead.
- This tempdoc asserts only mechanism-facts about our own code plus an eval plan.
