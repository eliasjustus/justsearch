---
title: "Retrieval adequacy as the missing verdict axis: one Worker-stamped STRONG/WEAK/NONE/UNKNOWN verdict on the canonical search trace, projected onto search results, chat evidence, agent tool results and MCP — beside (never folded into) the existing faithfulness, capability, completion and extraction-trust verdicts"
type: tempdocs
status: "DESIGN SETTLED (2026-09-02, fable fork) — evidence verified on main @ 4c4adbd6; the register's 'unify three vocabularies' hypothesis is corrected in §C (they are four distinct axes, correctly separate); the design adds the one axis that is missing. Owner confirmations K1-K3 DECIDED 2026-09-02 (all as recommended; §K). Not implemented — ready for opus takeover."
created: 2026-09-02
updated: 2026-09-02
lane: 887 L9 (register item 5.5)
model: fable (design) → opus (implementation, §I)
parent: 887-improvement-landscape-register
coordination: "⇢ founder lane E (search-quality re-derivation) owns ranking and threshold VALUES; this lane pins initial floors from existing constants and defines the calibration protocol, never a ranking change. 779 (answer-surface assertiveness) becomes a consumer. 597 (cardinality) and 600/602 (degradation) stay separate axes."
related:
  - 603-rag-trust-calibration        # faithfulness authority; §13 rejects QualitySignals as user trust — this design honours that
  - 677-vdu-extraction-abstention-gate / 797-vlm-confabulation-gate   # extraction-trust axis, unchanged
  - 779-answer-surface-assertiveness # consumer: STRONG adequacy + ungrounded answer ⇒ show the passage
  - 597-search-result-count-truthfulness # cardinality is not adequacy
  - 600-degradation-cause-not-observable / 602 R6  # capability axis, closed vocabularies + gate pattern reused
  - 878-agent-run-honesty-and-paging # completion axis (TerminalDisposition), unchanged
  - 549 / 553 (canonical SearchTrace, execution-surface register)
  - 702-dense-fusion-score-calibration-euclidean-cosine  # the one calibrated threshold that exists
---

> Design tempdoc. §C corrects the premise, §F is evidence, §D decisions, §A the design, §P reach,
> §O orphans, §I opus chunks, §K owner confirmations.

# 902 — Retrieval adequacy: the missing verdict axis

## §C. Correcting the premise

The 887 register (item 5.5) framed this as "three unrelated vocabularies for one thing". The
evidence says otherwise. The existing verdicts answer **different questions** and are correctly
separate authorities:

| Axis | Question | Authority | Vocabulary | Status |
|---|---|---|---|---|
| **Faithfulness** | what did the *answer* stand on? | `evidenceProjection.ts` (`answerFrame`, gate-enforced via `governance/run-renderers.v1.json` `groundingSemantics`) | grounded · partially-grounded · sourced · ungrounded · transform | shipped, calibrated by 603 |
| **Capability** | which pipeline legs *could* run? | `SearchTrace.Degradation` + `search-degradation-reason-codes.v1.json` gate | vectorBlocked / hybridFallback / spladeSkip + reason codes | shipped (600/602) |
| **Completion** | how did the agent *run* end? | `TerminalDisposition` | COMPLETED · MAX_ITERATIONS · BUDGET_EDGE_FINALIZE · ERRORED · CANCELLED | shipped (878) |
| **Extraction trust** | is this VLM text *real*? | `VduAbstentionGate` bands | PASS · AMBIGUOUS · REJECT (+ stage) | shipped (677), blind spot chartered (797) |
| **Retrieval adequacy** | did retrieval find anything *good enough to stand on*? | **none** | — | signals exist, verdict absent |

Folding these into one vocabulary would repeat the exact conflation 603 §13 killed ("surfacing
retrieval-quality signals as user confidence repeats the C1 conflation"). The correct design adds
the fifth axis and keeps the other four where they are.

## §F. What exists (evidence, `main` @ 4c4adbd6)

- **Search results have no quality state.** `Sv3ResultsStatus = idle|loading|ready|empty|unreachable` (`sv3-results.ts:26`); `empty` is `rows.length === 0` (`:85`). `MAIN_EMPTY` "Nothing matched" vs `MAIN_UNREACHABLE` are deliberately distinct (`fixtures.ts:63-78`); a top-N of weak hits renders as `ready`, indistinguishable from a strong one. The standalone renderer has only "No results." (`SearchResultsRenderer.ts:72`).
- **The signals a verdict needs are already computed per query, in the Worker:**
  - `RagContextOps.computeQualitySignals` (`:695-716`) → `RagQualitySignals(bestChunkScore, scoreGap, retrievalCoverage, chunksConsidered, chunksIncluded)`; best/gap come from **cross-encoder scores when reranked, else fusion/BM25 scores** (`:702-711`), `EMPTY` when nothing was scored (the `FULLTEXT_FALLBACK` path, `DocumentService.java:156`). Carried as `DocumentService.QualitySignals` (`:221-229`), written to `ragMeta.score_gap` (`RAGContext.java:358`), the RAG done event (`RAGDoneEnricher.java:87`), and the MCP `justsearch_answer` `quality` block (`McpEvidenceProjection.java:371-375`, "so an agent can judge retrieval confidence").
  - `HybridSearchOps.computeLowSignalGating` (`:161-176`): the one **calibrated** weak-signal detector — vector top score below cosine-score 0.40 (`DEFAULT_VECTOR_LOW_SIGNAL_THRESHOLD = 0.294` in euclidean form, derivation pinned by `CalibrationConstantsTest`, tempdoc 702) caps vector-only results at 3. It is a ranking lever; it never surfaces "weak" to anyone.
  - Per-hit `Hit.score` + `HitStage` trace (`knowledge.proto:160-182,284-300`); leg scores via `SearchTrace.legScores` (`McpEvidenceProjection.java:320`).
- **RAG generates regardless.** `RAGContext` has no adequacy branch: `sections.isEmpty()` only switches to whole-document fallback (`:374-416,526`); the prompt is built and the model called; the faithfulness frame reports `ungrounded` **after** generation (`answerFrameLabel`: "Searched your documents but found nothing to cite — treat this as the model's own answer", `evidenceProjection.ts:189-193`). Abstention is post-hoc and phrased as the model's failure.
- **The agent sees zero, not weak.** `SearchTool.java:442` "No results found (took N ms)"; any non-empty list is formatted `[n] title (score: x)` with raw scores the model must interpret.
- **MCP exposes numbers, not a verdict.** `McpToolSurface.java:631,1061` two ad-hoc "No results…" strings; `quality` block with five raw fields (`McpEvidenceProjection.java:371-375`); degradation summary (`:66-68,159-161`) — capability, not adequacy.
- **603 already decided the boundary.** §13: `QualitySignals` are a *retrieval-quality* axis, not faithfulness; user-facing *trust* = faithfulness only; quality signals belong in "why this result" diagnostics. §12: `EvidenceScore` is retrieval-evidence-only (559 §5). Nothing in 603 forbids a retrieval-adequacy **verdict** — it forbids presenting retrieval numbers **as answer confidence**.
- **779 asks for exactly this input** without naming it: "when is showing the raw passage more honest than a fluent synthesis?" (§B) — answerable only with an adequacy verdict beside the faithfulness frame; and it notes Q-009 (validated calibration) is open.
- **797 §4(c)** notes `extraction_method` / `vdu_status` are indexed, filterable fields read on the search path (`GrpcSearchService.java:688`) — a per-hit provenance signal an adequacy *reason* can cite.

## §D. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Add one axis, `RetrievalAdequacy`, with a closed vocabulary `STRONG · WEAK · NONE · UNKNOWN`.** UNKNOWN is a real value (signals absent: `QualitySignals.EMPTY`, fulltext fallback, unreachable), never collapsed into WEAK (tri-state discipline, `slice-execution.md`). | The missing question is "is there anything to stand on", and it has no owner. |
| D2 | **Stamped once, in the Worker, on the canonical `SearchTrace`.** The Worker already computes every input (§F); the trace is the one execution record (553) every consumer projects from. Registered in `governance/execution-surfaces.v1.json`. | One source; Head never touches Lucene; no second derivation in the Head or FE. |
| D3 | **Derived from existing signals only; no new model, no classifier.** Inputs: best final score (CE when reranked, else fusion), score gap, candidate cardinality, coverage, degradation, and the per-hit provenance tier. Floors are register-owned constants with a pinned derivation (the `CalibrationConstantsTest` pattern); initial values reuse the 702 cosine-0.40 low-signal floor for the dense-only case and a CE floor **to be measured** (§I-1 protocol). | 702 is the only calibrated threshold; lane E owns values, this lane owns the seam. |
| D4 | **Adequacy is rendered beside the faithfulness frame, never folded into it.** Chat evidence header: line 1 = frame (unchanged authority), line 2 = adequacy when WEAK/NONE/UNKNOWN. | 603 §13; two questions, two lines. |
| D5 | **Search results: notice, never filter.** WEAK renders a one-line notice above the rows ("Weak matches — nothing scored well; try other words or widen scope"); NONE/empty keep `MAIN_EMPTY`; rows are never hidden by a floor. | Hiding rows is a ranking change (lane E) and breaks "N results" truthfulness (597). |
| D6 | **RAG abstains before generation on NONE** with a structured no-evidence turn (no LLM call), carrying the search summary and an "answer from the model anyway" affordance that runs the existing ungrounded shape. WEAK proceeds and the header says so. | The post-hoc "found nothing to cite" is the model's failure phrased as the user's; NONE is knowable before spending a generation. **Owner K1.** |
| D7 | **The agent receives the verdict in the tool result** (`SearchTool` prefixes `adequacy: WEAK — best match scored low`), so re-query or abstain is the model's informed choice; `TerminalDisposition` untouched. | 878's completion honesty stays its own axis. |
| D8 | **MCP carries the verdict as data** (`adequacy: {verdict, reason}`) next to the raw `quality` block; the two "No results" strings become the NONE projection; `justsearch_answer` on NONE returns the structured no-evidence result without calling the model. | An external agent should not have to re-derive a verdict from five floats. **Owner K3.** |
| D9 | **Wording is a closed FE table gated like degradation codes** (`check-search-degradation-reason-codes` pattern: Java enum ↔ FE wording, forward + backward), and the FE projection is one registered authority module in `run-renderers.v1.json` (`groundingSemantics` pattern) so no surface re-derives a threshold. | The repo's existing prevention ladder for verdict vocabularies. |
| D10 | **Extraction trust and capability stay separate** but may appear as the adequacy **reason** ("top match is VLM-recovered text", "dense leg blocked"). | Projection of existing facts, not a merge of authorities. |

## §A. Design

1. **Verdict record** on `SearchTrace`: `Adequacy(verdict, reason, bestScore, scoreMetric, gap, candidates)`. `scoreMetric` names what `bestScore` is (`cross-encoder` | `fusion` | `bm25`), because the floors differ per metric and a bare scalar is exactly what 559 §5 forbids. Rules (initial, register-pinned):
   NONE ← zero candidates, or best score below the metric's NONE floor;
   WEAK ← best score below the metric's STRONG floor, or gap ≈ 0 across the whole top-N with low coverage;
   STRONG ← otherwise;
   UNKNOWN ← no scored signal (`EMPTY`, fulltext fallback) or the trace is absent.
   The dense-only NONE/WEAK boundary reuses the 702 cosine-0.40 floor; the CE floors are placeholders until §I-1's measurement fills them, and the register row says so.
2. **Wire**: the record rides the existing `SearchTrace` message and `ContextResult` (RAG) — additive proto fields, `--gate wire`. The MCP evidence projection maps it 1:1.
3. **FE authority** `components/search/retrievalAdequacy.ts`: `adequacyOf(trace)` (identity projection, no re-derivation), `adequacyLabel(verdict, reason)` wording table, `adequacyNotice` renderer. Consumers: `sv3-results.ts` (view gains `adequacy`), `Sv3Empty`/`SearchResultsRenderer` (notice slot), chat evidence header (`UnifiedChatView`, sv3 window) as line 2, `SourcesPane` (agent tier). Registered in `run-renderers.v1.json` with consumer sites; wording gate added to `governance/search-degradation-reason-codes.v1.json` as a second vocabulary (`retrieval-adequacy`).
4. **RAG pre-generation branch** in `RAGContext`: after retrieval, if `adequacy == NONE` and the shape is index-grounded, emit a `rag.no_evidence` event with the adequacy record and the retrieval summary, skip the completion, and finish the turn with the structured no-evidence answer; the FE renders it through the same frame authority (`ungrounded`, degraded=true) plus the adequacy line and the "answer anyway" action that re-runs as the ungrounded shape. WEAK/UNKNOWN proceed unchanged.
5. **Agent**: `SearchTool` result identity block gains the adequacy line; the final answer's evidence header shows it via the same FE authority. No prompt-policy change beyond the line.
6. **MCP**: `justsearch_search` and `justsearch_answer` results carry `adequacy`; the NONE projection replaces the two ad-hoc strings; `justsearch_answer` on NONE returns the no-evidence result (K3). Documented in `mcp-production-server.md`.
7. **779 becomes a consumer**: STRONG adequacy + `ungrounded`/`partially-grounded` frame is the measured "synthesis failed although the pack had the answer" case — 779's surface (show the passage) keys on exactly that pair. Recorded in 779 §B as the answer to its first question.
8. **Calibration protocol** (§I-1): jseval run over the standard strata (legal/email 1k/10k) recording best score per metric for queries with and without gold in the pack; choose floors at the point that separates the populations, report precision/recall of NONE against "no gold in corpus" queries, pin the constants with the derivation test. Values are lane E's to re-derive later; the protocol and seam are this lane's.

## §P. Reach

**Principle — one verdict per question, each a projection of the canonical execution record.**
A verdict vocabulary answers exactly one question, is derived once from the trace, has a closed
wording table with a forward/backward gate, and is rendered beside (never merged with) the
verdicts for other questions.

**Already instantiated by:** faithfulness (`answerFrame`), capability (degradation codes),
completion (`TerminalDisposition`), extraction trust (VDU bands), readiness (`readinessNotice`
+ `check-readiness-reason-codes`). This design adds adequacy in the same shape.

**Where else it applies:** the evidence-reader's per-passage relevance (849) should read adequacy's
per-hit inputs rather than the raw `Hit.score`; 897's duplicate-collapse work, if it ships, needs
a "results are redundant" verdict of the same shape; 901's origin discipline is a sibling axis
("where did this text come from") on the same trace.

**Existing violations:** the agent interprets raw `(score: x)` numbers (`SearchTool.java:383`);
MCP hands five floats to the client; `Sv3ResultsStatus` conflates transport with cardinality
and has no quality axis; two ad-hoc "No results" strings in `McpToolSurface`.

**Evidence it earns its keep:** (1) on the calibration run, NONE separates "no gold in corpus"
queries from the rest with stated precision/recall; (2) the pre-generation abstention fires on
real corpora and saves generations (count in the run summary); (3) the wording gate catches a
re-derived threshold in review at least once, or the register row stays the only threshold site
over two releases.

**Retirement condition:** if calibration cannot find floors that separate the populations
(NONE precision below what §I-1 sets), retire the NONE branch and keep only WEAK-as-notice;
if lane E's re-derivation makes the CE score itself calibrated and surfaced, fold this record's
inputs into that and keep only the vocabulary.

## §O. Orphaned by this design

- `McpToolSurface.java:631,1061` ad-hoc "No results…" strings → replaced by the NONE projection.
- `SearchTool.java:442` "No results found (took N ms)" → replaced by the adequacy line.
- `RAGQAStyle`'s reliance on the model to say "not in the documents" for the NONE case → the
  structured branch owns it; the prompt sentence stays for WEAK.
- Nothing in `evidenceProjection.ts`, the VDU gate, degradation codes, or `TerminalDisposition`
  is touched.

## §I. Implementation chunks (opus takeover)

**Briefing.** Fresh start; read this tempdoc, then §F pointers. Load `/search-quality` (the
register gets an adequacy row; no baseline changes) and `/jseval` for I-1. Work in a worktree.
Adequacy is a projection of existing signals — if you find yourself computing a new score, stop.
Do not change ranking, fusion weights, or the low-signal gating behaviour (lane E).

| chunk | scope | acceptance |
|---|---|---|
| **I-1 Worker verdict + calibration** | `RetrievalAdequacy` enum + record in `app-api` `SearchTrace`; computation in `RagContextOps`/`HybridSearchOps` from the existing signals with register-pinned floors (`governance/retrieval-adequacy-floors.v1.json` + derivation test in the `CalibrationConstantsTest` pattern); proto fields; `execution-surfaces.v1.json` row; jseval `adequacy-calibration` command running §A.8 and writing the floors' evidence | `--gate wire`, `--gate execution-surface` green; unit tests for every verdict arm incl. UNKNOWN on `EMPTY`; §Status holds the calibration table (n, strata, run ids) and the chosen floors with precision/recall of NONE |
| **I-2 FE authority + gates** | `retrievalAdequacy.ts` registered in `run-renderers.v1.json`; `retrieval-adequacy` vocabulary in the degradation-codes register + gate; `sv3-results` `adequacy` field + notice; chat evidence header line 2; `SourcesPane` agent tier; `SearchResultsRenderer` notice | `node scripts/ci/run-ui-web-gates.mjs`, typecheck, vitest; `check-search-degradation-reason-codes` green with the new vocabulary; ui-shot steps for WEAK notice and NONE turn (measured) |
| **I-3 Pre-generation abstention + agent + MCP** (after K1/K3) | `RAGContext` NONE branch + `rag.no_evidence` event + "answer anyway" action; `SearchTool` line; MCP `adequacy` field + NONE projection + `justsearch_answer` no-evidence result | live: a query with no answer in the corpus produces the no-evidence turn with zero completion tokens (record llama-server `/metrics`); a weak query proceeds with the notice; `check-dev-mcp-doc-sync`, `check-intent-tier-coverage`, `:modules:app-services:test :modules:app-agent:test :modules:ui:test` |
| **I-4 Docs + consumers** | `23-search-pipeline-overview.md` (adequacy stage row), `search-ui-behavior.md`, `mcp-production-server.md`, `/search-quality` register row; 779 §B answered; 603 cross-reference (§13 boundary honoured) | `verify-canonical-doc-links`, docs-lint; `/docs-maintenance` regen sequence |

Order: I-1 → I-2 → I-3 (needs K1/K3) → I-4.

## §K. Owner confirmations — DECIDED (2026-09-02, founder delegated the call to the orchestrating session)

- **K1 (D6)** Abstain before generation on NONE, with an "answer anyway" affordance — or always generate and rely on the post-hoc `ungrounded` frame? **Decided: abstain.** Honest, saves a generation, and the affordance keeps the free-chat path one click away. Reversible per D6 if the calibration in §I-1 shows NONE fires on queries that had gold in the corpus.
- **K2 (D5)** Search results: notice only, or additionally collapse WEAK rows below a fold? **Decided: notice only.** Collapsing is a ranking/cardinality change (lane E, 597); revisit only with a measured UX audit after the notice ships.
- **K3 (D8)** MCP `justsearch_answer` on NONE: structured no-evidence result without a model call, or generate and mark? **Decided: no model call.** An external agent gets a typed verdict it can act on; generating anyway would spend a local generation to produce text the client should not trust.

## §Status

Design settled 2026-09-02; K1-K3 decided the same day (all three as recommended). Nothing implemented. Chunks I-1..I-4 may start in any order that respects §I's dependencies.
