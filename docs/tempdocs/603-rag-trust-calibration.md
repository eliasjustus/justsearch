---
title: "603 — RAG trust-calibration (593 Area C): the grounded surfaces' trust signals must project from what was actually GROUNDED, not from the retrieval score — and a follow-up's retrieval must be decontextualized. The long-term design EXTENDS two authorities that already exist (the evidenceProjection grounding-semantics/answer-frame authority; the query-transformation pipeline) — no new structure. Owns three 593 findings (relevance/confidence forked onto the BM25 score · multi-turn self-contradiction from per-turn raw re-retrieval · the Structured tier carries no grounding signal). Distinct from 598 (retrieval ROOT) and built on 498 (shipped LLM-context threading)."
status: "REOPENED (2026-06-17, 593 second-pass regression sweep, NEW #4) — the calibration OVER-CORRECTED: C1 fixed the over-confident '100% RELEVANCE / HIGH CONFIDENCE on off-topic sources' (sweep ✅#9), but answers with CORRECT inline [1][2] citations now show 'No grounded sources for the latest answer'. The grounded-source panel appears gated on vector retrieval, which BLOCKED_LEGACY disables — so the inline citations and the sources-panel verdict DISAGREE, and the calibration swung from over-confident to over-conservative. Reopen scope in §REOPENED below (entangled with 598's BLOCKED_LEGACY). ── D-1 INVESTIGATION DONE (PART D-2, agent takeover): the reopen note's mechanism+surface are both corrected — D-1 is the AGENT-tier `SourcesPane` (`collectGroundingSources`), NOT the Documents `evidenceProjection`/CitationMatch authority C1 changed; 603's merged C1 never touched this path so it is a PRE-EXISTING gap, not a C1 over-correction; the `[1][2]` are MODEL-AUTHORED text (FE marks require non-empty sources, so they can't disagree); root cause = the agent grounds on the main DOC-level pipeline (no `parent_doc_id` under BM25-only) while Documents RAG grounds on the chunk path (survives BLOCKED_LEGACY). LONG-TERM DESIGN DONE (PART D-3): the root cause is a granularity GATE — `collectGroundingSources` gates source EXISTENCE on chunk identity (`parent_doc_id`), a filter the answer's `[1][2]` never applied. Design principle: separate grounding PROVENANCE (the source identity is the DOCUMENT, always present on a real hit) from grounding PRECISION (chunk identity is OPTIONAL enrichment → line-precise deep-link + inline marks). The collector keys by document identity and emits doc-level sources for chunk-absent hits, so the pane is a faithful projection of what was retrieved in ANY retrieval mode (598-independent, no new type/authority/proto change — extends `AgentSource` 565 §3.A; the 565 §10 mark-less degrade already covers doc-level). Empty state regains its true meaning (only when nothing was retrieved). NOT in scope (over-design): chunk-native agent retrieval, a doc-level entailment verifier, unifying the two pipelines. FRONTEND DESIGN DONE (PART D-4): the backend fix ALONE introduces a NEW lie — the grounding badge (`renderGroundingBadge`), hidden today when sources are empty, would flip to "Grounded · 0 of N sentences" for doc-level sources (no matcher cites), and the rail card would read "line 0" — trading over-conservative for over-confident (the C1 disease in a new place). Root: the agent trust surfaces model a BINARY (chunk-precise GROUNDED vs nothing) and have no vocabulary for the real third state D-3 introduces: SOURCED (the answer drew on retrieved documents, per-sentence grounding NOT verified — keyword-only). FE design = three honest states across the EXISTING badge/pane/chips/marks surfaces: GROUNDED (unchanged), SOURCED (list doc sources, OMIT the line locator + deep-link to file top, badge states provenance without claiming coverage, model's [n] stay plain — NOT synthesized into clickable marks, NOT neutralized), UNGROUNDED (unchanged, now truly empty). No new authority/view/numeric-confidence; the SOURCED verdict classifies the same inputs the badge reads today into a third bucket. Live screenshots attempted but blocked (HSTS self-upgrade on fresh tabs + shared-stack contention) — UX mapped from authoritative component code. CONFIDENCE PASS DONE (PART D-5, 7/10): U1 (BM25 hits lack parent_doc_id) confirmed structurally + the empty-pane symptom already observed (live hit-field probe the one residual); U2 → doc-level sentinel `chunkIndex=-1,startLine=-1` (no new wire field); U3 → deep-link opens file but the `-1` sentinel is REQUIRED to suppress a false line-0 highlight + omit the card locator; U4 (the big one) → the SOURCED state must extend the GATED `answerFrame` authority + BOTH render paths (the "marks-pending ⇒ grounded / Grounded·0 of N" semantics PRE-EXIST and permanently misframe doc-level; the live path passes no frame while persisted does — close that), not just `renderGroundingBadge`; `[n]` neutralization is NOT triggered for doc-level (retires a worry); U5 agent-tier confirmed by code; U6 → only `main` is a live worktree, 598 composes (no file conflict). **D-1 IMPLEMENTED + MERGED (2026-06-17, PART D-6/D-6.1): backend D-3 (`collectGroundingSources` emits document-level provenance sources, `-1` sentinel) + FE D-4 (the `sourced` AnswerFrame; badge "Based on N documents" never "Grounded · 0 of N"; card omits the locator) merged to main (feat `9098f2878` → merge `68b56140b`). Static all-green (FE typecheck + 3186 unit tests · `:app-agent:test`+`:app-api:test` · `build -x test` · run-renderers/color-tokens/presentation-purity/theme-token-closure gates). FE behavior LIVE-VALIDATED in the real UI (worktree Vite + the exact D-3 shape injected): SOURCED badge + frame line render, no "Grounded · 0", no "line -1", deep-link opens the file with no false highlight (`highlightStartLine=-1`), GROUNDED regression intact. The ONE residual is a full backend agent-run E2E of D-3 — gated behind merge because the dev-runner is main-bound; now that D-3 IS on main, it is a post-merge smoke (run a BLOCKED_LEGACY agent query, confirm the SOURCED state end-to-end), NOT a design risk; D-3's output is unit-pinned by `AgentSessionGroundingTest`.** ── PRIOR: open — agent takeover (2026-06-17). PART I problem + hypothesis · PART II investigation DONE (3 source-cited traces + RAG-eval/conversational-RAG research + the 498 reconciliation) · PART III LONG-TERM DESIGN DONE. The design is two scope-matched EXTENSIONS of existing structure, no new authority: (I, C1+C3) extend the ONE grounding-semantics/answer-frame authority `evidenceProjection.ts` so the per-source trust tier + the extraction frame project from the FAITHFULNESS evidence (CitationMatch / actual grounding outcome) the authority already owns — NOT the BM25 retrieval score (consistent with the authority's own 559 §5 'a retrieval score shown as relevance misrepresents' decision); (II, C2) extend the existing query-transformation pipeline (QUERY_UNDERSTANDING/EXPANSION/CORRECTION) with a conversation-aware DECONTEXTUALIZATION step that rewrites a follow-up into a standalone question before retrieval, consuming the conversation `context` 498 already plumbs. Self-correction over PART II: the backend QualitySignals are retrieval-axis, NOT trust — wiring them into 'confidence' would repeat C1. PART IV USER-FACING DESIGN DONE: the inline citations + grounding banner already tell the faithfulness story right; the fix makes the SOURCES panel + confidence band AGREE with them (group/badge sources by grounding USE, demote retrieved-but-uncited, drop the bare '100% relevance'), marks an ungrounded extraction unmissably ON its JSON artifact, and (C2) retrieves a follow-up on a decontextualized question (optional 'interpreted as' display). All within existing trust surfaces (CitationsPanel / answer-frame / extraction render / pipeline trace) — no new view. Inspected via existing demo screenshots + the 593 browser walkthrough + component code. PART V CONFIDENCE PASS DONE (C1 8/10, C2 7/10, C3 5/10-pending-decision); PART VI cross-work interference scan (598 the one overlap, kept clean); PART VII IMPLEMENTED — all three slices code-complete + static-green (typecheck · 3138 FE tests · gradle build · app-services/ui Java tests · run-renderers/color/presentation gates), C3 took the FE-only honest-frame option. **COMPLETE + MERGED: all three slices (C1/C2/C3) IMPLEMENTED + LIVE-VALIDATED. PART VIII = the C1/C2/C3 live pass + the C2 'Interpreted as' persistence fix; PART IX = post-ship research backlog (IX.2 #3–#8 OPTIONAL, deferred); PART X/X.A/X.B/X.C = the C1 grounding-attribution root-cause fix — the SOURCES panel joins grounding by the source's ARRAY POSITION, not a doc-ordinal (the corrected design after PART X's worker-remap idea, which would have broken the inline marks) — live-validated against a running Qwen3.5-9B ('Grounds 4 sentences' + 'Grounds 1 sentence' + 3 'Retrieved · not cited', no %; identical on reload via matchesFromRecord). Static at merge: typecheck · 3172 FE tests · gradle BUILD SUCCESSFUL · gates green. Merged to main 2026-06-17.**"
created: 2026-06-17
relates-to: [593, 598, 595, 596, 600, 498, 559, 565, 577]
author: agent
---

# 603 — RAG trust-calibration (593 Area C)

> **Takeover note.** This tempdoc is the dedicated home for the 593 walkthrough's **Area C — RAG /
> retrieval quality & trust-calibration**, which the 602 residual-sweep explicitly excluded "for separate
> tracking." It was un-homed until now. PART I (below) states the problem + an INITIAL proposed solution.
> PART II records the autonomous investigation that tests and critiques that proposal against the codebase,
> external best practice, and live experiments. **No implementation yet.** All Area-C work lives here.

## PART I — problem statement + initial proposed solution

### 1. Motivation (from 593)

The recurring verdict across every tier of the 593 walkthrough (Documents / Structured / Agent): **LLM
generation + grounding *honesty* are strong** (it cites, hedges, resists hallucination, self-labels
grounding) — **but the *trust signals* the UI paints around that generation do not faithfully track the
actual retrieval+grounding quality.** A user is therefore led to over-trust confidently-wrong output.

This is **distinct from 598**. 598 fixes the retrieval *root* (the FE never requests the dense leg → make
capability-derived dense the default). Area C is what remains **even with perfect retrieval**: how
*relevant* each source really is, how *confident* the user should be, whether context is *coherent across
turns*, and whether an *ungrounded* answer is *unmissable*. Better retrieval reduces the frequency of the
failure but does not fix the calibration/presentation of the trust signals themselves.

### 2. The three findings this doc owns (593 ADDENDUM 2)

- **C1 — Relevance/confidence MIS-CALIBRATION.** In Probe A, off-topic BM25 sources (passages that merely
  *mention* "Head/Worker/Brain") were each labelled **"100% RELEVANCE"** under a **"HIGH CONFIDENCE"**
  header — while the answer itself was self-flagged *"Partly grounded."* So the per-source relevance number
  and the overall confidence band were **confidently wrong**: they did not reflect that the retrieved
  context was off-topic. A user reads "100% / HIGH CONFIDENCE" and trusts a wrong answer.
- **C2 — Multi-turn SELF-CONTRADICTION from per-turn independent re-retrieval.** Topical context is kept
  across turns, but **each turn retrieves independently** (keyword re-retrieval, phrasing-unstable) with
  **no carry-forward of already-retrieved context**. A follow-up ("why was it designed that way?") pulled a
  *different, off-topic* passage set and concluded *"the documents do not contain… the default TCP port"* —
  **directly contradicting the immediately-prior turn** that had answered "ephemeral port (port 0)." Honest
  ("not found", no hallucination) but a conversational-trust break.
- **C3 — Clean JSON HIDES the ungrounded caveat (Structured tier).** Structured extraction emitted a wrong
  value (`timeout_seconds: 120`; the source says 5000 ms), **honestly flagged** *"Searched your documents
  but found nothing to cite — treat this as the model's own answer."* But the caveat is a header *above*
  clean, valid, schema-shaped JSON — a user skimming the JSON **misses the caveat** and trusts `120`.

### 3. Why these are ONE cluster (the unifying thesis, to be tested)

All three are the **same class**: a **trust signal that is not a faithful projection of the underlying
grounding evidence.** C1 = a per-source/aggregate *confidence* signal decoupled from actual relevance;
C2 = the *retrieval scope* not carried as conversational state, so the grounding basis silently changes
turn-to-turn; C3 = a *grounding-state* signal that is structurally easy to miss. The 593 line itself:
*"generation + honesty robust; the weak link is retrieval AND its trust-calibration."*

### 4. INITIAL proposed solution (a hypothesis to attack in PART II — NOT a committed design)

Mirroring the 558/594/595/596/600 single-authority depth-round pattern: there is likely **already** a
grounding/confidence model in the code, *projected inconsistently* — so the fix is "one trust-calibration
authority, faithfully projected," not new ML. Concretely, the working hypothesis:

- **C1 →** the displayed per-source "relevance %" and the "confidence" band should be **derived from the
  same grounding evidence the answer's own "Partly grounded / fully grounded" label uses** — not from a raw
  fusion/normalized retrieval score that always tops out at ~100%. If the answer is only "partly grounded,"
  the source confidence/relevance MUST NOT read "100% / HIGH." (Calibrate the badge to the grounding, or to
  whether the source was actually *cited/used*, not merely retrieved.)
- **C2 →** make retrieval **conversation-aware**: either carry forward the prior turn's retrieved+used
  context as state, or rewrite the follow-up query with conversation context so a follow-up cannot silently
  re-retrieve a contradictory basis. (At minimum, detect contradiction with the prior grounded answer.)
- **C3 →** make the ungrounded / "model's-own-answer" state **structurally unmissable** in the rendered
  artifact itself (not just a header) — e.g., the JSON values carry/are-wrapped-by a grounding marker, or
  the surface refuses to present ungrounded output as clean sourced data.

**The deeper unifying hypothesis:** one **grounding/trust authority** (analogous to 595's verdict or
596's availability) that every grounded surface (Documents / Structured / Agent) *projects* — so a
source badge, a confidence band, and an ungrounded marker cannot disagree with the answer's own grounding
self-assessment. **PART II must verify this authority exists / is forkable, and whether C1–C3 are really
one authority or genuinely separate concerns (AHA: don't over-unify).**

### 5. Open questions for PART II (recorded, to be answered by investigation)

1. **Where do the "relevance %" and "confidence band" come from?** Is the relevance a real per-source
   calibrated number or a normalized retrieval/fusion score? Is "confidence" computed from grounding
   coverage, retrieval score, or something else — and where is it rendered?
2. **What IS the answer's "Partly grounded / fully grounded" self-label derived from**, and does the same
   evidence feed (or fail to feed) the source badges? Is there already one grounding model that's forked?
3. **How does multi-turn retrieval actually work** — is each turn truly independent, is there any query
   rewriting / context carry-forward / source pinning, and where would conversational-retrieval state live?
4. **How is the Structured "nothing to cite / model's own" state rendered**, and is the grounding marker
   separable from the JSON payload (C3)?
5. **Is C1 a calibration problem (the number is wrong) or a presentation problem (the number is right but
   mislabelled)?** Distinguishing these changes the fix entirely.
6. **External best practice:** how do mature RAG systems calibrate + display retrieval confidence, source
   relevance, grounding/attribution, and conversational-retrieval coherence? (Faithfulness/groundedness
   metrics, calibrated confidence, citation/attribution UX, conversational query rewriting.)
7. **Does 598 (dense default) change any of C1–C3**, or are they fully orthogonal to which retrieval leg ran?

## PART II — autonomous investigation (IN PROGRESS)

> Codebase reads + external research. Each claim cited to source. Live reproduction is deferred to the
> implementation phase (the mechanisms below are statically conclusive — the forks/gaps are in the code —
> and the user-visible symptoms are already live-documented in 593 ADDENDUM 2).

### 6. Method

Three parallel source-cited codebase traces (one per finding) + external best-practice research
(RAG evaluation taxonomy; conversational-retrieval query rewriting) + a prior-art sweep that found
**tempdoc 498 owns part of C2**. No code changed. The §4 hypothesis is tested against each.

### 7. C1 — relevance/confidence is forked off the RETRIEVAL score, not grounding (CONFIRMED, sharpened)

**Provenance trace (FE `components/chat/evidenceProjection.ts` + `CitationsPanel.ts`; backend `RAGContext.java`):**
- The per-source **"relevance %"** = `ContextCitation.score` — the **raw BM25 retrieval score** — clamped to
  [0,1] then `Math.round(v*100)` (`evidenceProjection.evidenceScore`). It tops out near 100% by construction.
- The **confidence band** ("High confidence / Supporting / Weak") = `evidenceTier(score)` thresholded at
  0.6 / 0.5 on **the same BM25 score** — a purely FE-derived re-bucketing of the retrieval score.
- The answer's **"Partly grounded · N of M sentences"** label = `groundingCoverage()` over
  **`CitationMatch.similarity`** — a **different** signal (post-hoc answer-sentence ↔ source matching via
  `StreamingCitationMatcher`, the `rag.citation_matches` event), classified by `groundingClass`.
- The backend ALSO computes **`QualitySignals`** (`bestChunkScore`, `scoreGap`, `retrievalCoverage`) and ships
  them in the done payload's `calibration` block — **but nothing renders them.** A real calibration signal
  is computed and dropped.

**So the fork is exactly the §4 hypothesis, now precise:** the user-facing *confidence/relevance* badges
project the **retrieval** score (did this chunk keyword-match the query), while the answer's *grounded* label
projects the **faithfulness** signal (did the answer's sentences actually come from this source). An
off-topic-but-keyword-matching chunk reads **"100% · HIGH confidence"** while the answer is **"Partly
grounded"** — two signals, two sources, no reconciliation. (593 ADDENDUM 2 Probe A, live-confirmed.)

**External grounding (RAG-eval taxonomy):** the literature cleanly separates **context-relevance**
(query↔retrieved) vs **faithfulness/groundedness** (retrieved↔output) vs **answer-relevance**
(output↔question) — three distinct axes ([Braintrust], [Deepchecks], [Ragas]). JS conflates axis-1 (and not
even a relevance *judgement* — a raw BM25 score) with user-facing *trust*. The trustworthy signal is
**faithfulness** (axis-2), which JS already computes (`CitationMatch`) but does **not** route to the badge.

**Refined C1 design (extends §4):** make the per-source badge + the confidence band **project from the
grounding/faithfulness authority** (`CitationMatch` / `groundingClass` — "was this source actually used to
ground the answer, and how strongly"), NOT the raw BM25 retrieval score. A source that was retrieved but
**not cited** must not read "HIGH confidence." Two honest options, to decide at design time:
(a) **single trust axis** — show only the faithfulness-derived badge (simplest; the BM25 score becomes an
internal ranking detail, not a user trust signal); or (b) **two clearly-labelled axes** — "match" (retrieval)
vs "used to answer" (faithfulness), per the literature's split (more information, more UI). Either way, wire
the dropped `QualitySignals` (coverage / scoreGap) into the **overall** confidence so a low-coverage /
ambiguous retrieval can't surface as "HIGH". **This is the same single-authority depth-round as 594/595/600:
the trust badges must be a projection of the ONE grounding-evidence model, not a fork onto the retrieval score.**

### 8. C2 — retrieval is per-turn-stateless; 498 shipped the CONTEXT layer, NOT the RETRIEVAL layer (REFRAMED)

**Prior-art reconciliation (the decisive AHA finding).** **Tempdoc 498 "Cross-shape semantic context
threading" is `status: done` — SHIPPED** as `ExternalContextInjector` (`core.external-context`, request-body
`context` array), wired onto the **RAGAsk (Documents) and Extract shapes**, and the FE **does** populate it
(`UnifiedChatView.ts:4083`). So follow-up context threading is NOT un-owned.

**But 498 fixes a DIFFERENT layer than C2 needs.** `ExternalContextInjector.inject()` returns
`InjectorResult.messagesOnly(kept)` — it forwards prior messages into the **LLM context** (so the model can
resolve "it"/"that"). It does **not** rewrite the retrieval query, and **retrieval still runs on the raw
follow-up text alone** (`KnowledgeSearchRequest` has no history field; `RAGContext` retrieves on the current
question; the agent tier's `SearchTool` is even more stateless — `contextInjectorIds` empty). So:

> **C2's residual, owned by 603 (distinct from the done 498): retrieval is not history-aware.** Even with
> 498's context-threading to the LLM, a follow-up re-retrieves a *fresh, possibly contradictory* passage
> set, because the **retrieval query** is never decontextualized into a standalone question and prior
> used-passages are never carried forward. This is exactly the 593 contradiction (turn 2 re-retrieved an
> off-topic set and contradicted turn 1).

**External grounding (conversational RAG):** the standard fix is **query rewriting / standalone-question
generation / question decontextualization** — in-line the prior turn's entities into the follow-up query
*before* retrieval (coreference + ellipsis resolution), which "directly improves retriever performance"
([ChatQA], [decontextualizing user questions], [MaFeRw query-rewriting]). A complementary lever is
**historical-passage carry-forward / source pinning** (reuse the prior turn's retrieved set as a prior).

**Refined C2 design:** add a **history-aware retrieval** step for the grounded shapes — a standalone-question
rewrite (LLM-light or rule-based coreference) feeding the existing retrieval, optionally with prior-used-
passage carry-forward — building ON 498's shipped context channel, not duplicating it. **Scope honesty:**
this is a *backend retrieval-architecture* change (a different mechanism from C1/C3's presentation), and it
partly overlaps 598 (the agent/search default pipeline) — see §10.

### 9. C3 — the Structured tier carries NO grounding signal at all (CONFIRMED, possibly WORSE than 593)

**Trace (`UnifiedChatView.ts` + `MarkdownBlock.ts` + `evidenceProjection.ts`):** an extraction result is a
`ThreadMessage` with `isExtract:true` and the raw JSON string as `content`; it sets **no** `sources` /
`claims` / `citations` / `ragMeta`. Its frame is hardcoded `'transform'` (`answerFrame('core.extract',…)`),
and `answerFrameLabel('transform')` returns **`null`** — so the extraction renders the JSON **with no
epistemic header at all**, and the wire carries **no grounding state** (no per-field, no whole-result).

**This is the §4-C3 problem, and arguably worse than 593 described:** 593 saw an honest "found nothing to
cite — model's own answer" caveat above clean JSON (the *hideable-header* problem). The current code path
shows extraction as `'transform'` with **no caveat element**, so a hallucinated value and a grounded one are
**visually identical**. (Open item: confirm whether the zero-cite extraction path actually resolves to
`'ungrounded'` — which shows the caveat — or `'transform'` — which shows nothing; the trace says `core.extract`
is always `'transform'`. Either way the grounding state is not carried on the result.)

**Refined C3 design:** the extraction **result must carry a grounding state** (at minimum whole-result
grounded vs model's-own; ideally per-field), and render it **structurally unmissable** — attached to / wrapping
the values, not a separable header (e.g. ungrounded values visibly marked, or the surface refusing to present
ungrounded output as clean sourced data). This shares the C1 authority: extraction grounding is the same
"faithfulness of values to cited sources" signal, in JSON form.

### 10. Critical analysis — is Area C ONE authority? (AHA: NO — two mechanisms, one theme)

My §4 hypothesis ("one grounding/trust authority that every grounded surface projects") is **half right** and
must be split to avoid over-unifying:

- **C1 + C3 ARE one authority** — both are *presentation*: a trust signal (a source badge / a confidence band
  / an extraction grounding marker) that must be a faithful **projection of the ONE grounding-faithfulness
  evidence** (`CitationMatch` / coverage), instead of a fork onto the retrieval score (C1) or nothing (C3).
  This is a clean 558-style single-authority depth round on the *grounding-display* projector.
- **C2 is a DIFFERENT mechanism** — *retrieval architecture* (history-aware retrieval / query rewriting),
  backend, building on 498's shipped context channel and adjacent to 598's pipeline default. It is **not** a
  presentation projection; folding it into the C1/C3 authority would be the over-DRY the AHA cut warns against.

So 603's honest shape: **(I) a grounding-faithfulness display authority** (C1+C3 — the badges/bands/extraction
markers project the actual grounding evidence, the dropped `QualitySignals` get wired in) **+ (II) history-
aware retrieval** (C2 — standalone-question rewrite + optional passage carry-forward, on top of 498). They
share the *theme* (trust signals faithful to what was actually grounded) but are two pieces of work.

### 11. Scope decision + open questions for the user (no implementation yet)

**Recommended scope of 603:** own **(I) C1+C3 — the grounding-faithfulness display authority** as the primary,
self-contained, high-value piece (it is pure FE projection over signals the backend ALREADY computes —
`CitationMatch` + the dropped `QualitySignals`; no new ML, no retrieval change). Own **(II) C2 — history-aware
retrieval** as a second, backend slice explicitly built on 498 and de-conflicted with 598.

**Decisions to confirm before designing further:**
1. **C1 single-axis vs two-axis** (§7): show only a faithfulness-derived trust badge, or show "match"
   (retrieval) and "used-to-answer" (faithfulness) as two labelled axes?
2. **C2 depth:** standalone-question *query rewrite* only (cheaper, LLM-light), or also prior-passage
   *carry-forward / pinning* (more coherent, more state)? And does C2 belong in 603 or as a 598-adjacent
   retrieval doc? (I recommend keeping it in 603 per your "Area C = all three" direction, scoped as slice II.)
3. **C3 granularity:** whole-result grounded marker (simple) vs per-field grounding (faithful but needs the
   extractor to attribute each value to a source — a backend change).
4. **The §9 open item:** confirm live whether zero-cite extraction renders `'ungrounded'` (caveat) or
   `'transform'` (nothing) — this sets C3's baseline severity. (Deferred to the implementation/verify phase.)

**Confidence:** the diagnosis (C1 fork, C2 layer-split vs 498, C3 missing signal) is **source-confirmed and
high**. The designs are directions, not committed structure — slice I (C1+C3) is the clear, low-risk,
high-value core; slice II (C2) is real but larger and overlaps 498/598, so it needs the §11.2 depth decision.

## PART III — long-term design (general; extends existing structure)

> Method directive: extend usable existing designs; new structure only where the present problem requires
> it; no speculative abstraction; scope matched to the actual problem. The investigation below found the
> two authorities the fix needs **already exist**, so the design is two scoped EXTENSIONS, not a rewrite.

### 12. What already exists (the structure to extend, NOT replace)

- **The grounding-semantics + answer-frame authority — `components/chat/evidenceProjection.ts`** (tempdoc
  559 Authority IV; deepened by 565 §15.A/§15.D.1 and 577 §2.12). It is THE single, **gate-enforced**
  (`governance/run-renderers.v1.json` -> `groundingSemantics`; the `check-run-renderers` gate forbids any
  out-of-authority threshold re-derivation) owner of: the per-source `EvidenceScore`/`evidenceTier` (a
  branded tier only it can mint), the `groundingClass`/`groundingLabel`/`tierGroup`, and the epistemic
  `answerFrame`/`declaredGroundingClass` ("declared grounding class **combined with the run's ACTUAL
  grounding outcome**"). **Two facts make this the home for C1+C3, not a new authority:**
  - Its own header records a **559 §5 decision**: a *retrieval* score shown as a relevance/trust metric
    "**misrepresents** stage signals as a relevance score (over-DRY)" — the existing design **already
    argues against C1's bug**. C1 is a regression *against* this authority's stated principle, not a gap
    in it.
  - `answerFrame()` **already** refines a shape's declared class by the *actual* grounding outcome — the
    exact mechanism C3 needs; extraction merely **short-circuits** it (`declaredGroundingClass('core.extract')
    -> 'transform'`, never refined by whether anything was actually cited).
- **The faithfulness signal already exists on the wire** — `CitationMatch.similarity` (the
  `rag.citation_matches` event, answer-sentence <-> source matching, via `StreamingCitationMatcher`). The
  authority already consumes it for the answer's grounding label; C1's fix is to make the per-source
  **trust** tier consume it too, instead of the BM25 `ContextCitation.score`.
- **A query-transformation pipeline already exists** — the `SearchTrace` stage vocabulary
  (`QUERY_UNDERSTANDING`, `EXPANSION`, `CORRECTION`, ...) with **`EXPANSION` already an async, budget-gated,
  Head-side LLM query-rewrite** (`KnowledgeSearchEngine.startExpansionAsync`). C2 extends this established
  stage pattern; it does not invent a retrieval subsystem.
- **The conversation context is already plumbed to the Head** — tempdoc 498's shipped
  `ExternalContextInjector` carries the prior-turn `context` array to the RAGAsk/Extract shapes. C2 reuses
  that exact channel for the *retrieval* rewrite; it does not add a second history pathway.

### 13. Correction to PART II (the investigation refined the design)

PART II §7 floated "wire the dropped backend `QualitySignals` (coverage/scoreGap) into the overall
confidence." **Reading 559 §5 shows that is wrong:** `QualitySignals` are a *retrieval-quality* axis
(was the top chunk clearly better than the rest), not a *faithfulness* axis (did the answer come from the
sources). Surfacing them as user "confidence" would **repeat exactly the C1 conflation** the design exists
to kill. So the corrected design: user-facing trust = **faithfulness only**; `QualitySignals` stay where
they belong — as *retrieval diagnostics* in the pipeline trace / "why this result," never as answer trust.

### 14. Slice I — C1 + C3: extend the grounding-faithfulness authority (the depth round)

One single-authority depth round on `evidenceProjection.ts` (the 558/595/600 pattern), entirely within the
already-gated authority:

- **C1 — the per-source trust tier projects from FAITHFULNESS, not retrieval.** The source badge + its tier
  derive from the citation-MATCH evidence (how many answer sentences this source actually grounds —
  `CitationMatch`/`groundingClass`), not from `ContextCitation.score` (BM25). A source that was retrieved
  but **not used to ground any sentence** must not read "HIGH confidence." Per the authority's own 559 §5
  principle, the raw retrieval score stops being a user *trust* signal — it is removed from the trust
  display or demoted to an explicitly-secondary, honestly-labelled "retrieval match" detail (not
  "Relevance/Confidence"). **Single faithfulness axis** (this resolves §11.1: two-axis is rejected — it
  re-presents the retrieval score the authority already decided isn't trust).
- **C3 — extraction's frame is refined by ACTUAL grounding outcome.** Stop short-circuiting extract to a
  bare `'transform'`. Use the *existing* `answerFrame()` "declared × actual" mechanism: an extraction whose
  values came from cited sources is a *grounded* transform; an extraction with **zero citations** (the
  model's own values — the 593 `120`-vs-`5s` case) is framed **ungrounded** and rendered **unmissably**
  (the authority already owns an 'ungrounded' frame + label; extraction must USE it instead of the silent
  'transform'). Scope: the **whole-result** grounded/ungrounded state is what the present problem requires
  and is achievable from the existing per-answer grounding signal; **per-field** grounding is NOT designed
  in (it needs the backend extractor to attribute each value to a source — structure the present problem
  does not require; revisit only if a per-field gap is actually observed).

Why this is extend-not-replace: the authority, its branded-tier seam, its declared×actual frame mechanism,
the faithfulness wire signal, and the enforcing gate **all already exist**. Slice I re-routes inputs and
un-short-circuits one frame case **inside** that authority — no new authority, no new wire field, no
backend change. It is the smallest change that makes the trust signals faithful.

### 15. Slice II — C2: extend the query-transformation pipeline with conversation-aware decontextualization

The present problem: a follow-up re-retrieves an incoherent passage set because retrieval runs on the raw
follow-up text. The required structure (and no more):

- **A conversation-aware DECONTEXTUALIZATION step** in the existing Head-side query-transformation pipeline
  — rewrite the follow-up into a **standalone question** (resolve coreference/ellipsis from the conversation
  `context` 498 already plumbs) **before** retrieval. This is the literature's standard, retriever-improving
  fix ([ChatQA], [decontextualizing user questions], [MaFeRw]). It reuses the established async-LLM-stage
  pattern (`EXPANSION`'s shape: Head-side, budget-gated, trace-emitting) and the 498 context channel.
- **It is a distinct transformation** from morphological `EXPANSION` (different input = conversation
  history; different trigger = is-a-follow-up; different output = a rewritten *question*, not appended
  variants), so modelling it as its own pipeline step (its own `SearchTrace` stage) is warranted by the
  problem — not over-structure. (Whether it is literally a new `StageId` or a sub-mode of `EXPANSION` is an
  implementation choice; the *design-level* structure is "one conversation-aware query-rewrite step feeding
  retrieval, trace-visible.")
- **De-confliction:** builds ON 498 (LLM-context threading — shipped) and is orthogonal to 598 (which makes
  *dense* the default leg). C2 fixes *which query* is retrieved on; 598 fixes *which legs* run. Both
  compose.

**Deliberately NOT in scope (no speculative structure):** prior-turn **passage carry-forward / source
pinning** — the rewrite alone restores follow-up coherence; pinning is an additional state-carrying
mechanism the present coherence problem does not require, so it is left out until a residual is observed.
A first-turn query is unchanged (no history -> no rewrite).

### 16. Scope boundary — what this design deliberately does NOT add

- No new FE grounding authority (Slice I extends the one that exists).
- No retrieval *quality* signal surfaced as user trust (§13 — that is the C1 mistake).
- No per-field extraction grounding (§14 — whole-result is what the problem needs).
- No passage-pinning / conversational-cache for C2 (§15 — the rewrite suffices).
- No two-axis relevance UI (§14 — rejected per 559 §5).
- C2 stays in 603 (per the "Area C = all three" direction) but is honestly a *separate mechanism* from
  Slice I — they share the theme (trust faithful to what was grounded), not the implementation.

### 17. Resolved decisions (the §11 open questions, now answered by the design)

1. **C1 axis ->** single **faithfulness** axis (§14), per the authority's own 559 §5 principle. Resolved.
2. **C2 depth ->** standalone-question **rewrite only** (§15); passage-pinning is out of scope. C2 stays in
   603 as Slice II. Resolved.
3. **C3 granularity ->** **whole-result** grounded/ungrounded frame (§14); per-field deferred unless observed.
   Resolved.
4. **§9 open item (does zero-cite extraction render the caveat or nothing) ->** the design makes it moot:
   extract is reframed by *actual* outcome, so a zero-cite extraction is *always* framed ungrounded
   regardless of the current `'transform'` short-circuit. A one-line live check remains a *verification*
   step for the implementation phase, not a design dependency. Resolved.

**Net design:** two scoped extensions of existing, gate-backed structure — (I) the grounding-faithfulness
display authority projects trust from what was actually grounded; (II) the query-transformation pipeline
decontextualizes a follow-up before retrieval. No new authority, no speculative generality. Implementation
is a later phase.

## PART IV — user-facing / frontend design

> This design is almost entirely user-facing (Slice I is trust *display*). Inspected via: existing demo
> screenshots (`tmp/ui-check-demo/…/qa-response.png`, `citation-highlight.png` — confirm the per-source
> "100%" badge + the click-source→preview-highlight, pre-577 inspector layout); the 593 walkthrough's real
> browser observations of the CURRENT unified-window trust UI; and the live component code
> (`CitationsPanel.ts`, `evidenceProjection.ts`, the answer-frame render). Fresh current-layout screenshots
> were blocked this turn — the live stack would not start (3× 15s JVM-bind timeout under other-agent machine
> contention) and `ui-shot`'s `search-input` selector is stale post-577. The above primary sources are
> sufficient to design faithfully; a fresh screenshot pass is a cheap follow-up when the stack is free.

### 18. The existing user-facing trust surfaces (what a user sees today)

A grounded answer (Documents tier) shows FOUR trust signals — and **two of them already do faithfulness
right, while two project the retrieval score**, which is the visible contradiction:

- **Inline citations** — superscript `[n]` marks + underlined sourced spans in the answer body. **Correct:**
  these reflect *which sentences are actually grounded* (`CitationMatch`).
- **The grounding banner** — "Partly grounded — some statements are not backed by your documents" / silent
  when fully grounded / "Searched your documents but found nothing to cite — treat this as the model's own
  answer." **Correct:** faithfulness-derived (the grounding coverage).
- **The SOURCES panel** — a "N sources" disclosure that, when expanded, groups sources under **"High
  confidence" / "Supporting" / "Show N weak matches"** (`CitationsPanel.renderTieredSources`, grouped by
  `tierGroup(evidenceTier(s.score))`). **WRONG:** the tier is the **BM25 retrieval score** — so a retrieved-
  but-unused source sits under "High confidence."
- **The per-source relevance %** — the right-aligned "100%" on each source card (confirmed in the demo
  screenshot). **WRONG:** the raw retrieval score shown as a trust number.

So on one screen a user can read **"High confidence · 100%"** sources beneath a **"Partly grounded"** banner —
the SOURCES panel disagrees with the inline citations and the banner *about the same answer*. The Structured
tier shows **no trust signal at all** (clean JSON, no grounding marker).

### 19. User-visible changes the PART III design requires

- The SOURCES panel's grouping + per-source badge change MEANING (retrieval-score → faithfulness): a source
  is grouped by *how much it actually grounds the answer*, consistent with the inline citations.
- The bare "100% relevance" trust number is removed or demoted to an honestly-labelled secondary detail.
- A new visible state for the Structured tier: an *ungrounded* extraction is unmistakably marked.
- Follow-up answers stop self-contradicting (indirect); the pipeline trace gains a decontextualization stage,
  and optionally the rewritten standalone question becomes visible.

### 20. The correct user-facing design

**C1 — make the SOURCES panel and confidence band AGREE with the inline citations (one faithfulness story).**
The inline `[n]` citations are already the truth (which sentences each source grounds). The design makes the
SOURCES panel project the *same* signal:
- **Group/badge sources by grounding USE, not BM25.** A source that grounds answer sentences ranks high; a
  source that was *retrieved but never cited* is demoted into a clearly-labelled "retrieved, not cited"
  group (the existing "weak matches" collapsed-by-default slot is the natural home) — **never "High
  confidence."** The tier vocabulary shifts from a retrieval grade ("High confidence / Supporting / Weak") to
  a **use** grade (e.g. "Cited / Supporting / Retrieved — not cited"); exact words at design time, but the
  axis is faithfulness.
- **Drop the bare "100% relevance" as a trust number** (per the authority's own 559 §5 rule). If a per-source
  number is kept, it states what it means — "grounds N sentences" / a faithfulness %, honestly labelled —
  not an unlabelled "100%."
- **The overall confidence = the answer's grounding coverage** (the banner's "N of M sentences" faithfulness),
  shown consistently — so a user never sees a "High confidence" cluster contradicting a "Partly grounded"
  banner. One grounding story across banner + inline + sources.

**C3 — make an ungrounded extraction unmissable, on the artifact.** A grounded extraction (values from cited
sources) shows its sources/citations like the prose tier. An *ungrounded* extraction (the model's own values
— the `120`-vs-`5s` case) carries a marker that **travels with the JSON, not a header above it**: e.g. the
result block is visibly framed/tinted as "model's own values — not from your documents," so a user skimming
the values cannot read them as sourced data. (Whole-result marker; per-value is out of scope per §14.)

**C2 — coherent follow-ups + optional transparency.** The primary user win is that a follow-up no longer
silently retrieves a contradictory basis. Two visible affordances to consider (not required for correctness):
(a) surface the **decontextualized standalone question** ("Searching for: …") so the user can see — and
correct — how their follow-up was interpreted (a legibility/trust win and an escape hatch for a bad rewrite);
(b) the **pipeline trace** gains the decontextualization stage (power-user transparency, free from the
trace's existing stage vocabulary). (a) is the genuinely user-facing one; weigh it against added chrome.

### 21. Scope boundary (frontend)

- **No new view/route/surface.** Every change lives in the EXISTING trust surfaces — the SOURCES panel
  (`CitationsPanel`), the answer-frame banner + inline citations, the Structured render, and the pipeline
  trace — all already owned by the `evidenceProjection` authority + its consumers. This is a depth round on
  the trust *display*, not a new screen.
- The C2 "interpreted as" rewrite display is an **optional transparency affordance** to weigh, not required
  for the coherence fix; coherent retrieval is the necessary part.
- No new trust *number* invented; faithfulness is what already exists (`CitationMatch` / grounding coverage)
  — the design re-points the badges at it, it does not add a new metric for users to learn.

**Frontend net:** the trust display tells ONE faithfulness story — the SOURCES panel and the confidence band
stop projecting the retrieval score and instead agree with the inline citations and the grounding banner; an
ungrounded extraction is unmissably marked on its own artifact; and a follow-up is retrieved on a
decontextualized question (optionally shown). All within the existing surfaces and the one grounding
authority. Implementation remains a later phase.

## PART V — pre-implementation confidence findings (investigation, no code)

> A confidence-building pass tested PART III/IV's load-bearing assumptions against the code. Result: the
> design largely holds, with ONE real correction (C3). Per-slice ratings + the biggest remaining risk each.

### 22. Findings per uncertainty

- **U1 (C1 data join) — CLEAN, FE-only.** Both arrays are held in `UnifiedChatView` state and committed to
  the `ThreadMessage` (`sources` = `rag.citations`/RetrievalCitation; `citations` = `rag.citation_matches`/
  CitationMatch); the join key `{parentDocId, chunkIndex}` aligns (same producer), so each source card maps
  to "grounds N sentences." Timing: `rag.citations` lands BEFORE the stream, `rag.citation_matches` AFTER
  the `done` event — so the faithfulness badge appears on the panel's automatic post-stream re-render (no
  badge during streaming, acceptable). `CitationsPanel` currently *ignores* `citations` when `sources`
  exist — the fix reads both. **No new state field, no backend change.**
- **U2 (matcher quality) — DECENT but THRESHOLD-SPARSE.** `StreamingCitationMatcher`/`CitationMatchOps`:
  a two-phase matcher (lexical draft during stream → authoritative **cross-encoder** (CPU/ONNX) or embedding
  fallback at stream-end), one gated 0.5 cutoff (565 §15.A). Cross-encoder captures paraphrase (better than
  lexical), but only sentences ABOVE 0.5 are emitted — below-threshold contributions are omitted, not marked
  low. **Design nuance:** an uncited source must read "retrieved — not cited" (neutral), NOT "irrelevant"; and
  when the matcher is unavailable (embedding fallback + embeddings offline → no matches at all), the badge
  must degrade gracefully (don't render ALL sources as "uncited"). A bounded calibration risk, not a blocker.
- **U3 (C3) — CORRECTION: extraction emits NO grounding signal and never retrieves.** `ExtractShape` wires
  **no RAGContext** (no retrieval) and **no StreamingCitationMatcher**; its event schema is
  `[chunk, reasoning_chunk, done, error]` — zero `rag.*`/grounding events. So PART III §14's "reframe from
  the existing per-answer grounding signal" is **wrong** — there is no signal to reframe. Extraction is a
  pure model transform today. **This needs a design decision, two honest options:**
  (a) **FE-only honest frame** — since extraction never grounds, frame it ALWAYS as "model's own values —
  not from your documents" (cheap; the existing `declaredGroundingClass('core.extract')='transform'` becomes
  an explicit model's-own frame with a visible label). Matches the 593 caveat wording; no backend.
  (b) **Make extraction grounded** — wire RAGContext + the matcher into ExtractShape so values come from
  cited sources, then the FE refines the frame by actual outcome (a BACKEND slice; bigger; a product change
  to what extraction *is*). The cheap (a) is viable now; (b) is a separate decision about extraction's intent.
- **U4/U5 (C2 plumbing) — CONFIRMED, and Head-only (good news).** Conversation history does NOT reach
  retrieval for RAGAsk (498's `ExternalContextInjector` → LLM prompt only; `RAGContext` retrieves on the raw
  question; `KnowledgeSearchRequest`/the proto have no history field). The agent tier's LLM *sees* history
  and writes the query (partial self-decontextualization). The minimal seam is **Head-only**: a new
  pre-retrieval `ContextInjector` that rewrites the question to standalone form using the conversation
  context **already present in the `ConversationContext`**, stored for `RAGContext` to consume — **no
  `KnowledgeSearchRequest`/proto/worker changes** (the head-never-touches-lucene invariant is respected).
  PART III's "consume the 498 channel" was imprecise (498 feeds the LLM, not retrieval) but the conclusion
  (Head-side rewrite, no proto/worker) holds and is cleaner than feared. Tier scope: RAGAsk is the target;
  the agent tier likely doesn't need it (verify with a small eval).
- **U6 (gate) — FINE.** `check-run-renderers` `groundingSemantics` only forbids a threshold-near-a-class
  literal OUTSIDE `evidenceProjection`. Re-pointing the tier input + any faithfulness helper stays inside the
  authority; `CitationsPanel` only counts matches + calls the authority's helpers (no threshold literal). No
  gate change.
- **U7 (live) — DEFERRED.** The dev stack is running but owned by another agent session (callerIsOwner=false);
  per branch-safety I did not take it over. The static traces are conclusive for U1/U3/U4; U2's sparsity is a
  known matcher property; a live coverage sample is a cheap implementation-phase confirmation.

### 23. Per-slice confidence (0–10) + biggest remaining risk

- **Slice I — C1 (faithfulness display): 8/10.** Clean FE-only join, gated-authority-internal, post-stream
  badge. Risk: matcher sparsity/unavailability — word uncited sources neutrally and degrade gracefully when
  the matcher can't run.
- **Slice I — C3 (extraction grounding): 5/10.** The investigation corrected the design: extraction has NO
  grounding signal and doesn't retrieve, so it needs a DECISION — cheap FE-only "always model's-own" honest
  frame (viable now) vs a backend "make extraction grounded" slice. Until that's chosen, C3 is the least
  certain. (The cheap option alone would lift this to ~8.)
- **Slice II — C2 (decontextualization): 7/10.** Confirmed Head-only, no proto/worker, reuses the injector
  SPI + the in-`ctx` conversation context. Risk: a new pre-retrieval LLM call (latency/budget — mirror the
  EXPANSION stage's async budget-gating) + confirming the agent tier doesn't also need it (small eval).

**Net:** the design is implementable; the one genuine surprise is C3 (extraction emits no grounding signal —
PART III §14's premise is corrected here, and C3 now carries a product decision). No code written.

## PART VI — cross-work interference scan (parallel worktrees, 2026-06-17)

> The 593 family is being implemented in parallel: active `-impl` worktrees for **598** (capability-derived
> retrieval), **599** (folder-indexing status), **600** (degradation cause), **601** (progress/ETA). Checked
> each worktree's changed files against 603's target set.

- **598 — the ONE real overlap (manageable, coordinate).** 598 is rewriting the **Head-side retrieval/query
  pipeline** — `KnowledgeSearchEngine.java`, `SearchPipelinePresets.java`, `ipc-common/.../indexing.proto`,
  `GrpcSearchService` — to make dense the capability-derived default. That is the territory 603 **Slice II**
  (decontextualization before retrieval) operates near. Three mitigations keep it clean:
  (a) **Plug C2 into the conversation/RAGContext layer** (a new pre-retrieval `ContextInjector` on
  `RAGAskShape`, per §22/U4) — this lands in `app-services/.../conversation/spi/`, NOT `KnowledgeSearchEngine`,
  so it **avoids 598's hotspot** (C2 produces a rewritten standalone question; 598's pipeline then retrieves on it).
  (b) **Stay proto-free** — `indexing.proto` is hot (BOTH 598 and 599 edit it); §22/U4 confirmed C2 needs no
  proto change, so 603 must keep it that way.
  (c) **Sequence/compose** — C2's decontextualized query feeds 598's capability-derived (dense-default)
  retrieval; prefer landing 598 first (or rebasing) so C2 layers on the new default. A minor `UnifiedChatView.ts`
  co-edit is possible (598 may add retrieval-mode UI; 603 Slice I adds grounding badges — same file, different
  regions); resolvable.
- **599 / 600 / 601 — NO interference with 603.** 599 (WatchedRoots / IndexingService / LibrarySurface /
  folderStatus), 600 (verdict.ts / readinessNotice.ts / observability rules — a DIFFERENT FE authority than
  603's `evidenceProjection`), and 601 (InferenceLifecycleManager / availability) touch **none** of 603's
  grounding-display (`evidenceProjection`/`CitationsPanel`) or RAG-retrieval (`RAGContext`/`RAGAskShape`/
  `ExtractShape`) files. (599 vs 598 share `indexing.proto` — that is THEIR conflict, not 603's.)

**Coordination takeaway for 603's implementation:** Slice I (FE grounding display) is conflict-free against all
four. Slice II (decontextualization) shares the retrieval path with **598 only** — keep C2 in the conversation
layer + proto-free + sequenced after 598, and the two compose cleanly.

## PART VII — as-built (IMPLEMENTED, static-green; live pass pending) — 2026-06-17

All three slices implemented in worktree `603-rag-trust` (extending existing structure, no new authority,
no proto/worker change — exactly the PART III/IV/V design). Sequenced C1 → C3 → C2.

- **Slice I — C1 (faithfulness display).** `evidenceProjection.sourceGrounding`/`sourceGroundingLabel` join
  the answer's `CitationMatch[]` to each source by `{parentDocId, chunkIndex}`; `CitationsPanel`
  `renderTieredSources` groups by the GROUNDING tier (the ONE `evidenceTier` authority fed faithfulness, not
  BM25), a cited source reads "Grounds N sentences" under "Grounds the answer", a retrieved-but-uncited source
  is demoted to the collapsed "retrieved · not cited" group, and the bare "100% Relevance" BM25 number is
  dropped (559 §5). No-matches → neutral flat "N sources retrieved" (U2). The `run-renderers` groundingSemantics
  gate stays green (change is within the authority).
- **Slice I — C3 (extraction marker).** `answerFrameLabel('transform')` is no longer null — it returns
  "Model-generated structure — not retrieved from your documents", rendered as an UNMISSABLE tinted/bordered
  strip (`.answer-frame-transform`) abutting the JSON (extraction has no RAGContext, so it never grounds).
- **Slice II — C2 (decontextualization).** New `QueryRewriteInjector` (conversation/spi) rewrites a follow-up
  into a standalone question via `OnlineAiService.chatCompletion` (deterministic, ~1500 ms budget, total
  graceful fallback), stashes it in `ctx.attributes()`; `RAGContext` retrieves on it. Wired into `RAGAskShape`
  before `RAGContext` + registered in `ConversationApiAssembly`. Head-only — no proto/worker/KnowledgeSearchEngine
  change (composes with 598). Transparency: a conversation-layer `rag.rewrite` SSE event → `UnifiedChatView`
  "Interpreted as: …" line. RAGAsk tier only.

**Verification — static, all green:** FE `npm run typecheck` clean · full ui-web suite **3138** · `./gradlew.bat
build -x test` BUILD SUCCESSFUL (compile + spotless + PMD + gates) · `:modules:app-services:test` +
`:modules:ui:test` green (incl. new `QueryRewriteInjectorTest` 6 cases; updated `CitationsPanel`/
`evidenceProjection`/`answerFrameLabel` tests to the faithfulness model) · gates: `run-renderers`
(groundingSemantics), `color-tokens`, `presentation-purity`, `theme-token-closure` · shapes fixture + FE
shape-handlers regenerated for the `rag.rewrite` event.

**Decision recorded:** C3 took option (a) — an honest "model's own / not document-grounded" frame (FE-only).
Option (b) "make extraction grounded" (wire retrieval into ExtractShape) is OUT (a product feature, not
trust-calibration).

**⚠ Live browser validation — PENDING (environment-blocked), NOT done.** The user-visible C1/C3/C2 surfaces
must still be confirmed live (the SOURCES panel grouping by grounding + no "100% · High confidence" under a
"Partly grounded" banner; the unmissable extraction marker; a coherent multi-turn follow-up + the "interpreted
as" line). This turn it could not run: the `justsearch-dev` MCP disconnected (no handle to start a *worktree*
stack carrying the Java changes) and the machine was contended (two other-agent backends + ~23 java/node
processes; earlier stack-starts each hit the 15 s JVM-bind timeout). The logic is unit/gate-covered, but per
the project rule a user-visible feature is complete only after the live pass — so this slice is **code-complete
+ static-green, with the live pass explicitly outstanding** (run when the dev stack frees up / the MCP
reconnects: `:modules:ui:installDist` for the worktree, start the backend, `ai_activate`, then a Documents Q&A
+ a follow-up + an extraction).

## PART VIII — LIVE BROWSER VALIDATION (DONE) + C2 persistence fix — 2026-06-17

The live pass that PART VII flagged outstanding is now **complete**. The worktree backend was stood up and
all three surfaces were confirmed against a running model + real index (no demo data).

**Stack provisioning (worktree, fresh data dir).** Built the head dist (`:modules:ui:installDist`); started
the worktree backend via `dev-runner` (API `:60129`, FE `:5173`). The fresh `.dev-data` carried no installed
runtime variant, so AI activation failed `RUNTIME_VARIANT_NOT_INSTALLED`. Fix: junctioned
`worktree/modules/ui/native-bin` → `F:\JustSearch\modules\ui\native-bin` (so `resolveVariantsRoot`'s dev
fallback finds `cuda12`) + copied the dev `settings.json` (model path), restarted, then
`POST /api/ai/runtime/activate {variantId:"cuda12"}` → `completed/passed` (Qwen Qwen3.5-9B online). Indexed
`docs/explanation` (34 docs). Retrieval ran BM25 (`EMBEDDING_UNAVAILABLE`) but the faithfulness cross-encoder
(`CitationMatch`) still ran — exactly C1's signal source.

**C1 — faithfulness display: CONFIRMED.** First-turn Documents Q&A ("the three main processes") rendered a
"Partly grounded" banner. The SSE made the target fork concrete: `rag.citations` cited `chunkIndex 4`
(BM25 3.6) while `rag.citation_matches` grounded `chunkIndex 3` (similarity 0.99) — the cited chunk is not the
grounding chunk. DOM probe of `jf-citations-panel`: **no `%`, no "Relevance", no "High confidence"** — the
panel reads "5 retrieved (not cited)" (neutral, grounding-based, U2), coherent with the "Partly grounded"
banner. The BM25 "100% · High confidence" fork is **gone**.

**C3 — extraction marker: CONFIRMED.** A Structured/extract turn (schema `{processes:string[]}`) rendered the
`.answer-frame-transform` strip **"Model-generated structure — not retrieved from your documents"** as an
unmissable tinted/bordered block abutting the JSON (`["Main Process","Knowledge Server","Inference Server"]`).

**C2 — decontextualization: CONFIRMED (+ a live-only defect found & fixed).** Follow-up "why is the first one
called that?" → SSE `rag.rewrite` `{original, standalone:"Why is the Main Process (\"The Head\") called
that?"}`; retrieval ran on the standalone (citations shifted to `07-ui-host-architecture.md` — Head-specific,
proving coherence). The FE "Interpreted as: …" line rendered **during streaming**.
  - **Live-only defect (caught by the browser pass, invisible to unit tests):** the note was bound to the live
    streaming render (`this.rewriteNote` + `renderStreamingBlock`) only, so it **vanished the moment the turn
    completed** — even same-session, before any reload. A user reading the finished answer never saw what was
    interpreted, undercutting the transparency goal. The backend SSE emitted correctly; the gap was purely the
    FE note lifecycle. (Classic `static-green ≠ live-working`.)
  - **Fix (FE-only, `UnifiedChatView.ts`):** persist the standalone onto the committed turn, mirroring how
    `citations`/`ragMeta` already persist — `ThreadMessage.standaloneQuestion?`, set in `onDone` from
    `rewriteNote`, rendered in `renderMessage` (the evidence-bearing committed path). Re-validated live: after
    completion (same session, `streaming:false`) the note **persists** — "Interpreted as: Why is the Main
    Process (\"The Head\") called that?" sits above the grounded answer across both rendered turns.
  - **Documented limitation (scope boundary, not a regression):** across a HARD reload the note is not shown —
    `loadConversation` rebuilds the thread role/content-only and the committed turn then renders from the
    canonical record (561 P-B), which does not carry the rewrite (same as evidence on reload, per the
    `renderUnifiedItem` comment). Persisting the rewrite to the backend conversation record + projecting it in
    `renderUnifiedItem` is the model-aligned way to make it reload-durable; it is **out of the FE-only C2
    scope** (the design specified a transient transparency line) and left as a clean follow-up. The note is
    present for the entire live session in which the follow-up is asked — the moment of maximum relevance.

**Verification after the C2 persistence fix:** FE `npm run typecheck` clean; full ui-web unit suite re-run
(render change is additive, gated on the new optional field; no authority/gate touched — `run-renderers`
groundingSemantics unaffected). The new `standaloneQuestion` field flows through the existing per-turn
persistence pattern, so no new harness was introduced (AHA — no speculative scaffolding).

**Status: all three slices IMPLEMENTED + LIVE-VALIDATED.** C1/C3/C2 confirmed against a running Qwen model +
real index, with the C2 note now persisting through turn completion.

## PART IX — POST-SHIP RESEARCH & IDEATION (documentational; nothing implemented this round) — 2026-06-17

Goal (user, research-only): given 603 is shipped + live-validated, explore what to polish / simplify / extend /
build next on its three primitives (C1 faithfulness display · C2 decontextualization · C3 honest framing). Method:
four parallel web-research probes (faithfulness/citation UX · conversational query understanding · abstention/
honest framing · the human-AI *trust-calibration* HCI literature), cross-checked against our producer code. No code
changed this round — findings + a ranked backlog only.

### IX.0 — HEADLINE CORRECTNESS FINDING: C1's faithfulness join keys on mismatched `chunkIndex` namespaces (real bug)

The live pass (PART VIII) showed "5 retrieved · not cited" for an answer that was clearly grounded (a 0.99 sentence
match). A producer-side trace explains why, and it is a **defect, not data luck**:

- `rag.citations[].chunkIndex` is emitted as `ContextCitation.chunkIndex()` — the **within-document chunk ordinal**
  (`RAGContext.java:193`).
- `rag.citation_matches[].chunkIndex` is `CitationScorer.ScoredMatch.chunkIndex`, documented as *"index into the
  chunks list"* (`CitationScorer.java:271`; the integration test asserts position semantics) — the **0-based position
  in the retrieved-citations list**. `CitationMatchOps.java:174` `.setChunkIndex(match.chunkIndex())` carries that
  position with **no remap** to the real ordinal; `RemoteDocumentService.matchCitations` passes it straight through.
- C1's `sourceGrounding` (`evidenceProjection.ts`) joins `m.chunkIndex === source.chunkIndex` — i.e. **list-position vs
  doc-ordinal**. They coincide only when a chunk's ordinal happens to equal its list slot. In general (and in the live
  test: match position 3 vs displayed ordinals 4,3,5,…) the join fails → every source falls to the "retrieved · not
  cited" group → C1's core grouping ("Grounds N sentences") **silently almost never fires in production**.
- Why tests pass anyway: `CitationsPanel.test.ts` / `evidenceProjection.test.ts` fixtures use citations whose
  doc-ordinal == list position, so the mismatch is invisible. Classic static-green ≠ live-working.

**Two clean fixes (for a follow-up implementation turn — NOT done here):**
- **FE-only (preferred, smallest):** the matcher's `chunkIndex` IS the global position in the SAME ordered list that
  `rag.citations` is emitted from (RAGContext emits `kept`; RemoteDocumentService iterates `kept` to build the worker
  request; the scorer indexes that order). So join on the **source's array position in `rag.citations`**, not its doc
  ordinal — `CitationsPanel.renderTieredSources` already maps with the index in hand. Make `sourceGrounding` take the
  position and match `m.chunkIndex === position` (parentDocId stays a redundant safety check).
- **Worker-side (more globally correct):** `CitationMatchOps` remap `setChunkIndex(chunkIndices.get(match.chunkIndex()))`
  so BOTH events speak the doc-ordinal namespace — also fixes any other consumer that assumes a doc ordinal.
- **Regression that would have caught it:** a unit test with citations whose doc-ordinals are NON-sequential (e.g. [4,3,5])
  and a match at list position 1 → assert the source with the right *identity* reads "Grounds N", the others "not cited".
  (audit-without-test / critical-analysis-pass.)

Confidence: high (documented field semantics + integration-test assertion + the live repro all agree). Residual to
confirm at fix time: whether the inline per-sentence marks (`rag.citation_delta`) share the same join or carry the
index directly (the live answer DID show inline superscripts, suggesting the delta path is unaffected — so the bug is
scoped to the SOURCES-panel join, the C1 surface).

### IX.1 — What the research VALIDATED about the shipped 603 design (keep doing)

- **Dropping the "100% relevance" number was right.** Numeric per-answer confidence is the format users reason about
  WORST and LLM/retrieval self-scores are poorly calibrated → false precision. SOTA (Google Check-Grounding exposes a
  score but leaves display to the app; HCI: frequency/categorical > numeric). Our categorical tiers are informationally
  superior. **Do not reintroduce a per-answer %.**
- **Decontextualize-then-retrieve (C2) is the consensus highest-leverage move** for conversational RAG (CANARD/QReCC/
  TREC CAsT): every retriever degrades sharply on raw elliptical follow-ups; one rewrite call fixes it.
- **Honest framing / "not from your documents" (C3) matches the NotebookLM posture** — surfacing the ABSENCE of grounding
  is a recognised trust primitive, not over-caution.
- **Caution the research raised about our own approach:** (a) explanations inflate trust *regardless of correctness* —
  so a grounding panel that mis-attributes (see IX.0!) is worse than none; (b) a transparency line shown on EVERY turn
  becomes "explanation theater" and is habituated; (c) more signals ≠ better calibration past ~2-3 legible cues.

### IX.2 — Ranked idea backlog (deduped across the four probes)

**Tier 1 — correctness (do first)**
1. **Fix the C1 `chunkIndex` namespace join (IX.0)** — [polish/bug, S]. Without it C1's grouping is effectively inert.
2. **Faithfulness regression + spot-audit** — [validation, S]. The IX.0 test + sample a few live answers: do highlighted
   passages actually entail the claims? (RAG "post-rationalization": a citation can be retrofitted, not the real basis.)

**Tier 2 — high value, cheap, research-backed**
3. **Verbatim grounding passage in the source card** — [extend, S–M]. On expand, show the answer sentence(s) a source
   grounded + its excerpt; the single highest-trust add per the citation-UX research (turns a link into checkable
   evidence). We already have `sentenceText` per match + the chunk excerpt.
4. **Suppress "Interpreted as:" when the rewrite barely differs from the raw question** — [simplify, S]. We already drop
   the exact-equal case; extend to near-equal (normalized edit/semantic distance) so the line means "something changed."
   Directly answers the "explanation theater"/habituation finding.
5. **Rewrite-only-when-needed gate** — [polish, S]. Skip the LLM rewrite when the follow-up has no pronoun/ellipsis cue
   and is already standalone-length; saves latency and stops the rewriter paraphrasing already-good queries. Add
   "return the question verbatim if already standalone" to the rewrite prompt as the cheap half.

**Tier 3 — worthwhile extensions**
6. **Click a sentence → highlight its grounding source** — [new, M]. Sentence↔source navigation using the per-sentence
   `rag.citation_delta` we already emit; makes grounding data actionable, not decorative.
7. **Distinct "Not found in your documents" state** for ~0-coverage answers — [new, S–M]. Stronger than today's generic
   "ungrounded" banner; reuses `groundingDegraded`. NotebookLM posture: an ungrounded-but-confident answer is more
   dangerous than a graceful "couldn't find it."
8. **Per-sentence "not backed" marks in PARTLY-grounded answers** — [extend, M]. The banner says THAT some statements
   aren't backed; mark WHICH (subtly, factual sentences only — HCI caution against inline-marker noise).
9. **Collapse the grounding panel for well-grounded answers; surface it for partial/ungrounded** — [simplify, S]. Avoid
   "wallpaper" habituation; make the panel a meaningful interrupt that appears when it differentiates.

**Tier 4 — deliberate NON-actions (research-backed restraint)**
10. Keep avoiding numeric confidence %s (false precision). 11. Don't add a 4th grounding tier or pile on signals (past
    ~2-3 cues calibration degrades). 12. Skip HyDE / RAG-Fusion / multi-query for now — cloud-scale recall techniques
    whose latency/complexity isn't earned in a single-user local-first app absent eval evidence of a recall gap.

**Tier 5 — future / heavier**
13. **Sufficient-context pre-check** before generation (decline when retrieved context is insufficient) — addresses the
    Google finding that *insufficient* context INFLATES hallucination vs no context; autorater/threshold gate, no
    training. [new, L]. 14. **Contradiction flag** for a retrieved source that contradicts the answer (needs an NLI pass;
    our cross-encoder is similarity-only) — [extend, L]. 15. **One first-use explainer of the 3-feature trust layer**
    (better calibration than per-feature tooltips; HAX G1/G2) — [new, M].

### IX.3 — Sources (representative; full list in the research log)
RAGAS (arXiv 2309.15217) · ALCE / "Correctness ≠ Faithfulness in RAG Attributions" (arXiv 2412.18004) · Google
Check-Grounding + "Sufficient Context" (research.google) · CANARD/QReCC conversational rewriting · HyDE (Gao et al.) ·
Microsoft HAX guidelines (Amershi CHI 2019) · Google PAIR Explainability+Trust · "To Rely or Not to Rely" (arXiv
2412.15584) · "Designing for Appropriate Reliance" (arXiv 2401.05612) · NotebookLM grounding posture · ShapeofAI
citation patterns.

**Status: research round complete. The C1 join bug (IX.0) is the one finding that rises above "nice idea" — it means
C1's core grouping is largely inert in production; recommend it as the next implementation turn (with the IX.0
regression test). Everything else in IX.2 is an optional, ranked backlog. Nothing implemented this round.**

## PART X — LONG-TERM DESIGN THEORIZATION (the grounding-relation identity) — 2026-06-17

This part theorizes the correct long-term design for the PART IX cluster. It is design-level (no code). The
investigation (PART IX.0 + a producer-side trace through `AgentCitationResolver`, `StreamingCitationMatcher`,
`CitationMatchOps`, `CitationScorer`, `RemoteDocumentService`) reframed the problem and **shrank** the warranted
change: the consumers are already correct; one producer emits an incomplete identity. The design follows from that.

### X.1 — The real problem: a match must carry a COMPLETE, STABLE source identity

C1, and the three highest-value follow-on ideas (IX.2 #3 grounding-passage-in-card, #6 sentence→source navigation,
#8 per-sentence "not backed" marks) all rest on ONE relation: **which answer sentence is grounded by which source
chunk.** That relation already exists in two forms — it does not need inventing. The defect is that the relation's
*key* is not a complete identity:

- A source chunk's STABLE identity in this system is **(`parentDocId`, `chunkIndex`-as-document-ordinal)** — the same
  pair `ContextCitation` carries, that `rag.citations` emits, that the agent's `AgentSource` carries, and that the
  `chunk:<docId>#<ordinal>` doc-uid encodes. Every *consumer* already joins on this pair (`evidenceProjection.
  sourceGrounding` for the RAG panel; `AgentCitationResolver.indexOfSource` for the agent inline marks).
- The worker citation-matcher, however, **half-resolves** that identity: `CitationScorer` maps its internal loop index
  to the real `parentDocId` (via `resolvedChunkDocIds[i]`) but leaves `chunkIndex` as the **raw list position**, and
  `CitationMatchOps` emits it unremapped. So a match crosses the worker boundary carrying a CORRECT doc but a
  POSITION-not-ordinal `chunkIndex` — an identity that is only half-resolved.

That half-resolved identity is the root cause. The RAG consumer (no fallback) breaks outright ("everything uncited");
the agent consumer survives only because `indexOfSource` has a **fuzzy "same-document" fallback** that papers over the
mismatch (attributing to the right doc, possibly the wrong chunk). Two consumers, the same latent defect, masked
unequally — the signature of a missing producer-side invariant, not a consumer bug.

### X.2 — The design principle (root cause, scope-matched)

**An answer↔source match must carry the complete stable identity of the source it grounds — `parentDocId` AND the
document-ordinal `chunkIndex` — fully resolved at the one boundary that owns the mapping (the worker), never a raw
internal index that leaks to consumers.** The matcher's list-position is an implementation artifact of scoring; it
must be translated back to the source's real ordinal before the match is emitted, exactly as `parentDocId` already is.

Consequences of adopting this principle:

- The fix lives at the **producer boundary** (the worker resolves `chunkIndex` the same way it already resolves
  `parentDocId`). The field's documented contract becomes honest: `chunkIndex` = the source chunk's **document
  ordinal** (matching `ContextCitation.chunkIndex`), and "index into the chunks list" is named/treated as the internal
  artifact it is, resolved before it crosses the boundary.
- **No consumer is redesigned.** `evidenceProjection.sourceGrounding` and `AgentCitationResolver.indexOfSource` already
  join on the stable pair; once the producer supplies it, both become correct *by construction*. The agent's fuzzy
  same-document fallback drops from load-bearing to defense-in-depth.
- The **persisted record** (renderUnifiedItem's `claimMatches`) and any future consumer inherit the correct identity
  for free, and it is **stable under reordering / dedup / persistence** — a property a list-position key cannot offer.

**Explicitly reject the short-term alternative.** "Have the FE join on the source's array position in `rag.citations`"
(IX.0's tactical FE-only option) would fix the one panel while (a) leaving the agent join subtly wrong, (b) leaving the
proto field's meaning a position masquerading as an ordinal, and (c) being fragile to any reorder/dedup between the two
events. It is a symptom fix; the producer-boundary resolution is the root cause. (Per "do not design short-term fixes.")

### X.3 — The relation is the shared substrate; its two forms stay separate (no over-DRY)

The sentence↔source relation is emitted twice, by design, and should STAY two representations sharing ONE identity
contract — unifying them would be over-DRY (AHA: only merge what shares a reason to change):

- **`rag.citation_delta`** (streaming, per sentence) carries the matched source(s) **inline** with each sentence — it is
  self-contained, needs no join, and is why inline superscript marks render correctly today. Its reason to exist is
  *low-latency, during-stream* marking.
- **`rag.citation_matches`** (final, on done) carries a **reference** (sentence → source identity + similarity) that is
  joined against the sources panel and persisted on the record. Its reason to exist is *the complete, post-hoc,
  persistable grounding map*. This is the one whose identity is half-resolved (X.1).

The design unifies their **identity contract** (both speak `parentDocId`+document-ordinal `chunkIndex`), not their
shape. The delta path already honors it; the matches path must.

### X.4 — The follow-on features are derivations over the one corrected relation (extend, don't add structure)

Once the relation is correctly keyed, the Tier-2/3 ideas are **pure derivations** over it and belong in the existing
grounding authority (`evidenceProjection.ts`, already "THE grounding projection" per 559/565) — not new types or
authorities:

- **#3 grounding passage in the source card** = a reverse index of the matches by source (the sentence(s) a source
  grounded + the chunk excerpt we already carry). A derivation; the source card already has the data once the join works.
- **#6 sentence→source navigation** = the forward index of the same relation (per sentence, its source) — the
  `citation_delta` already carries it; this is wiring a selection→focus, not new data.
- **#8 per-sentence "not backed" marks** = the complement of coverage (sentences with no match), already computed by
  `groundingCoverage`. A presentation of existing data, used sparingly (HCI: inline-marker noise).

No "grounding-map" type, registry, or projection-authority is warranted: the relation already exists as the two SSE
events + `GroundingCoverage`; the corrected identity makes the existing `evidenceProjection` derivations correct. Adding
a new authority for a relation that already has one (mis-keyed) would be structure for its own sake.

### X.5 — The C2 / C3 idea-clusters: their design already has a home (refine, don't restructure)

- **C2 transparency meaningfulness (IX.2 #4 suppress near-identical rewrites, #5 rewrite-only-when-needed):** the
  `QueryRewriteInjector` already OWNS the "is this rewrite worth surfacing" decision (it rejects blank / over-long /
  exactly-equal today). The design principle stands: **the injector decides meaningfulness; the FE renders only what it
  is told.** #4/#5 TIGHTEN the injector's existing predicate (near-equality; a cheap pre-gate that skips the LLM call
  for already-standalone follow-ups) — an extension of one predicate, no new structure, and it keeps the FE a pure
  renderer. This also satisfies the HCI "explanation-theater / habituation" caution at its source.
- **C3 "not found in your documents" (IX.2 #7):** the `answerFrame` authority already distinguishes `ungrounded` and,
  via `groundingDegraded`, "searched your documents but found nothing to cite" vs "this mode doesn't search." A
  ~zero-coverage answer IS the former, and the authority already produces that wording. So #7 is a **presentation
  refinement** (give that already-modeled state a stronger, distinct empty-state treatment) — not a new state or
  authority. The honest-framing structure 603 C3 shipped already covers the meaning.

### X.6 — What is explicitly NOT warranted

- No new "grounding relation / map" type, record, registry, or projection authority — the relation is already modeled.
- No proto SHAPE change — the `chunkIndex` field stays; only the VALUE the worker puts in it is corrected (resolved
  ordinal, not position). (This is also why the change is conflict-free with the proto-editing worktrees, X.7.)
- No merging of `citation_delta` and `citation_matches` — different reasons to change (streaming marks vs persistable map).
- No numeric confidence %, no extra grounding tier, no HyDE/RAG-Fusion (per IX.2 Tier-4 restraint, unchanged).

### X.7 — Adjacency & conflict

No tempdoc in 590–602 designs in the citation/grounding area (grep-confirmed; 603 is the sole owner). The active
worktrees — 598 (semantic-search/embedding GPU), 599 (folder indexing), 600 (verdict), 601 (availability) — touch none
of `CitationMatchOps` / `CitationScorer` / `evidenceProjection` / the injector. 598/599 edit `indexing.proto`, but this
design makes **no proto-shape change** (only the worker's emitted value), so even the proto file is not contended. The
producer-boundary fix is owned entirely within 603's domain.

### X.8 — Design summary

The PART IX cluster is not a feature-design problem; it is one missing producer invariant. **A grounding match must
emit the complete, stable source identity (`parentDocId` + document-ordinal `chunkIndex`), resolved at the worker
boundary — the same boundary already resolving `parentDocId`.** That single root-cause correction makes both existing
consumers correct by construction, retires a fuzzy fallback's load-bearing role, and turns the three highest-value
follow-on features into derivations over the already-existing grounding relation inside the already-existing
`evidenceProjection` authority. The C2/C3 ideas refine predicates/presentation in authorities that already exist. The
warranted change is therefore small and localized — an outcome of the diagnosis, not a target — and no new structure,
abstraction, or generality is introduced.

## PART X.A — confidence-building / pre-implementation verification (read-only) — 2026-06-17

Plan-approved confidence pass over the PART X design. Seven uncertainties (U1–U7) retired by reading the producer
code + replaying the saved live SSE captures. No feature code written. Net: the diagnosis and fix direction are now
**measured, not inferred**, with one real correction to PART X.

- **U1 (remap validity) — CONFIRMED.** `CitationScorer.scoreAll` iterates `ci` over the FULL input `chunkTexts`
  array, `continue`-skips empty chunks but **does not renumber** (`bestChunkIdx = ci`). So `ScoredMatch.chunkIndex`
  is a position into the *unfiltered* input list → the worker remap `chunkIndices.get(pos)` is valid (the
  `chunkTexts`/`chunkDocIds`/`chunkIndices` inputs are 1:1, same order).
- **U2 (both producer paths) — CONFIRMED.** The embedding-cosine FALLBACK in `CitationMatchOps` also emits
  `bestChunkIdx` as a list-position (nulls kept, not renumbered). BOTH the cross-encoder path and the fallback path
  need the remap — **two construction sites** in `CitationMatchOps` (≈ lines 174 and 258).
- **U3 (consumers/tests) — SAFE.** `RetrieveContextController`'s `/match-citations` debug endpoint echoes the match
  `chunkIndex`; its input `chunk_refs` carry doc-ordinals, so emitting the ordinal back makes input/output ALIGN
  (strictly more correct). `GrpcSearchServiceMatchCitationsTest` happy-path sends ALIGNED indices (`[0,1]` == ordinals
  0,1) so it stays green under both behaviors; `CitationScorerIntegrationTest` asserts position semantics at the
  *scorer* level (unchanged by a worker-side remap). **No consumer or test breaks.** The regression test MUST use
  NON-aligned ordinals (e.g. retrieved ordinals [4,6,4,0,0], match at list-position 3) — the existing tests' blind spot.
- **U4 (the bug + fix direction) — PROVEN by measured SSE** (`/tmp/603_turn1.sse`, `_turn2.sse`):
  - Turn 1 displayed doc-ordinals `[4,6,4,0,0]`; all matches `chunkIndex=3`, doc `01-system-overview.md`, sim 0.85–0.996.
  - Join as DOC-ORDINAL (today's FE join): no displayed chunk has ordinal 3 → **all uncited** (reproduces the live bug).
  - Join as LIST-POSITION: `chunkIndex=3` → list-pos 3, whose `parentDocId` matches the match's → the CORRECT source.
  - Worker remap `chunkIndices.get(3)` → ordinal 0 of `01-system-overview.md`; `(parentDocId, ordinal)` then joins
    uniquely to list-pos 3 (NOT list-pos 4, a different doc at ordinal 0) — `parentDocId` disambiguates. Fix validated.
- **U5 (persisted/reload path) — CORRECTION to PART X.** The reload/record render (`renderUnifiedItem`'s RAG branch,
  `UnifiedChatView.ts`) passes `<jf-citations-panel .citations=${[]}>` — it feeds the panel NO match data. So the
  worker remap fixes only the LIVE path (`renderMessage` passes `m.citations`); **grounding-on-reload is NOT "free"** —
  it needs the record branch to pass the persisted `claimMatches` to the panel (a bounded extra FE wiring, mirroring how
  the C2 note didn't persist on reload). PART X's "inherits the fix for free" was too strong; corrected here.
- **U6 (data-flow) — CLARIFIED.** The inline per-sentence marks + the frame/coverage ride `rag.citation_delta` →
  `m.claims` (self-contained per sentence), NOT the `rag.citation_matches` join. So **idea #8 (per-sentence "not backed"
  marks) is INDEPENDENT of the bug** — it already works. Only the SOURCES-panel grounding (C1) and #3 (grounding passage
  in the card) ride the buggy `citation_matches` join.
- **U7 (C2/C3 touch-points) — CONFIRMED.** #4/#5 extend the `QueryRewriteInjector` reject predicate (blank/over-long/
  equals/first-turn already there) — low risk. #7 maps to the EXISTING zero-sources `ungrounded`+`groundingDegraded`
  state ("Searched your documents but found nothing to cite"), so it is mostly a stronger empty-state PRESENTATION of an
  already-modeled state (PART X right). Noted rare edge: an answer WITH sources but zero grounded sentences currently
  reads `grounded` (the `cited===0` "marks-pending" branch) — out of #7's core scope, flagged for the implementer.

**Design adjustments folded back:** (a) the worker remap is the validated root-cause fix and must cover BOTH producer
paths; (b) grounding-on-reload needs an explicit `renderUnifiedItem` wiring to pass `claimMatches` — it is a small,
bounded add, NOT free; (c) the regression test (worker and/or FE) must use NON-aligned ordinals or it proves nothing;
(d) #8 is independent and needs no join fix.

**Confidence after this pass: 8.5/10** for the remaining work. The bug and fix direction are measured-proven; the fix is
small, both producer paths and the remap arithmetic are confirmed, and no consumer/test breaks. The residual −1.5 is
execution-side: the bounded reload-path wiring (U5) and the FE derivation features (#3/#6) still need their own small
design + live re-verification, and the FE record-path mapping of `claimMatches` → `CitationMatch` should be eyeballed
when implementing.

## PART X.B — DESIGN CORRECTION at implementation time: FE position-join, NOT a worker remap — 2026-06-17

PART X recommended resolving a stable doc-ordinal identity at the worker boundary. Implementation-time tracing of
the FE consumers shows that would be **wrong** and is hereby superseded. The match field `rag.citation_matches[].chunkIndex`
is consumed two ways, and they DISAGREE on its meaning:

- `claimsToCitations` (`components/chat/citationResolve.ts:28-29`) reads it as an **array index** into the sources
  list — `const s = sources[refIdx]`, `label: refIdx + 1`. The inline `[n]` marks, the mark label, and
  `Claim.sourceRefs` (populated from `m.chunkIndex` at `UnifiedChatView.ts` 4262/4323 and `claimsFromRecord` 3744-3754)
  ALL treat it as the **list position** — and they render correctly today precisely because the worker emits that
  position.
- `sourceGrounding` (`evidenceProjection.ts:351-365`) is the LONE consumer that read it as a **document ordinal**
  (`m.chunkIndex === source.chunkIndex`), so its join almost never matched → the "everything uncited" bug.

So the system's established convention is **chunkIndex = array position in the `rag.citations` list**; only
`sourceGrounding` deviated. A worker remap to doc-ordinals would have fixed the panel but BROKEN the inline marks /
`[n]` labels. The correct, root-cause-consistent, smaller fix is the opposite and **FE-only**: align `sourceGrounding`
to the array-position convention (join `m.chunkIndex === sourceIndex`, with `parentDocId` as a guard). No worker, no
proto, no marks change. Ordering is safe — `this.sources` is the `rag.citations` payload, emitted from the same
ordered `kept` list the matcher indexes, so array index i == matcher position i.

Also corrected: grounding-on-reload is NOT free (PART X.A U5) — the record path passes `.citations=[]`; a small
`matchesFromRecord` wiring feeds the persisted matches so the reload panel groups by grounding too. (The earlier
PART X.A "stable identity at the producer boundary" framing is retained only as history; this PART X.B is the
operative design.)

## PART X.C — as-built + LIVE VALIDATION (DONE) — 2026-06-17

The C1 fix (PART X.B design) is implemented, statically green, and **live-validated** against a running
Qwen3.5-9B + the real 34-doc `docs/explanation` index.

**As-built (FE-only, no worker/proto change):**
- `evidenceProjection.sourceGrounding(sourceIndex, matches, parentDocId?)` — joins by the source's ARRAY
  POSITION (`m.chunkIndex === sourceIndex`, `parentDocId` guard), the convention the inline marks already use.
- `CitationsPanel.renderTieredSources` — computes grounding once per `(index, source)` into a `Map`, groups by
  tier, threads the carried grounding to `renderSourceCard`.
- `UnifiedChatView`: new `matchesFromRecord(claimMatches)` (mirror of `claimsFromRecord`, yields `CitationMatch[]`)
  wired into the reload/record render so the SOURCES panel groups by grounding on reload too (was `.citations=[]`).
  Safe because `RAGDoneEnricher` persists `citations` in `rag.citations` order, so the position-join holds.
- Tests: `evidenceProjection.test.ts` rewritten to the index signature + a decisive NON-aligned-ordinal case;
  `CitationsPanel.test.ts` grouping test asserts the position-join (source display-ordinal 2 grounded via match
  position 0); fixture for the collapse test now supplies a match per position.

**Static:** typecheck clean · ui-web unit suite **3140 passed** · gates green (`run-renderers` groundingSemantics,
color-tokens, presentation-purity, theme-token-closure). FE-only → no Java build needed.

**Live (measured via DOM probe + render):** a Documents Q&A ("the three main processes…") retrieved 5 sources
with doc-ordinals `[4,6,4,0,0]` and matches at list-positions `[3,4]`. The panel now renders under "Grounds the
answer": **"Grounds 4 sentences"** (source index 3, `01-system-overview.md`) + **"Grounds 1 sentence"** (index 4,
`02-process-coordination.md`), with three **"Retrieved · not cited"** — and NO `%`, NO "high confidence". The old
doc-ordinal join would have found nothing (no source has ordinal 3) → the "everything uncited" bug; the
position-join correctly attributes. After a hard reload + Continue (restore from the record), the panel shows the
IDENTICAL grounding via `matchesFromRecord` (5 sources, 5 matches, same badges) — Step 3 verified. Inline `[n]`
marks render unchanged.

**Dev-stack note:** the shared stack was heavily contended (multiple concurrent agents; the dev-runner supervisor +
managed Vite were repeatedly killed). Validation proceeded by running a STANDALONE Vite (`VITE_JUSTSEARCH_API_PORT`
override → the orphaned-but-healthy backend), which is independent of the dev-runner and survived. Both dev
processes were stopped afterward.

**Status: C1 implemented + static-green + LIVE-VALIDATED (live & reload). The required remaining work of tempdoc
603 is complete.** The IX.2 backlog (#3–#8) remains the documented OPTIONAL deferral.

---

## REOPENED — second pass (2026-06-17, 593 regression sweep)

> The C1 over-confidence fix verified (sweep ✅#9: off-topic bm25 sources no longer slapped with
> "100% RELEVANCE / HIGH CONFIDENCE"). But the re-run found the calibration **over-corrected** in
> the opposite direction.

### D-1 — "No grounded sources" under-credits correctly-cited answers (NEW #4)

- **Observed (sweep NEW #4):** answers that carry **correct inline `[1][2]` citations** still show
  **"No grounded sources for the latest answer."** The inline-citation story (right) and the
  SOURCES-panel verdict (wrong) **disagree** — the calibration swung from over-confident to
  **over-conservative**.
- **Hypothesised mechanism (to verify):** the grounded-source panel appears **gated on vector
  retrieval**, which **BLOCKED_LEGACY disables** — so in the keyword-only state the panel finds no
  "grounded" sources even though the answer is grounded on the bm25-retrieved passages it cites
  inline. If so, D-1 is the C1/PART-X grounding-attribution join (array-position) being keyed to a
  signal that goes empty under BLOCKED_LEGACY, while the inline marks derive from the actual
  CitationMatch and stay correct.
- **Entanglement with 598:** D-1 surfaces *because* the environment is BLOCKED_LEGACY (598's
  reopened core). It is still **603's own bug** — the SOURCES panel must agree with the inline
  citations regardless of retrieval mode (a grounded answer over keyword-retrieved passages is
  still grounded). Verify against a non-BLOCKED_LEGACY state once 598 makes one reachable, but do
  not *depend* on 598 to fix it: the panel's grounding verdict should project from the same
  faithfulness evidence the inline marks already use (the C1 thesis), not from a vector-retrieval
  precondition.

### Reopen scope (design only — not yet implemented)

- Make the SOURCES-panel grounding verdict **consistent with the inline citations** — both project
  from the CitationMatch / faithfulness evidence (C1's authority), so "answer has inline [1][2]"
  and "SOURCES panel says no grounded sources" cannot coexist.
- Confirm the gating mechanism (is the panel keyed on a vector-retrieval signal that BLOCKED_LEGACY
  empties?) with a live oracle, and add a regression test that an inline-cited answer renders
  grounded sources in keyword-only mode.

### Disposition

- **Reopened for D-1 only.** C1/C2/C3 + PART X stand as merged history; this is a calibration
  *symmetry* completion (don't trade over-confident for over-conservative). Cross-referenced to
  598 (the BLOCKED_LEGACY state that exposes it) without depending on it for the fix.

## PART D-2 — autonomous investigation of D-1 (agent takeover, 2026-06-17): the reframe + corrected design

> Takeover directive: investigate D-1 against the codebase + external best practice before implementing;
> question the reopen note's assumptions. Result: the reopen note's **mechanism and target surface are both
> wrong**, in a way that changes the fix. The symptom is real; the cause is NOT a C1 over-correction, NOT the
> Documents-tier faithfulness authority, and NOT (directly) "the panel is gated on vector retrieval." The
> diagnosis below is static-conclusive (the forks are in the code, cited file:line); the one residual is a
> live-oracle confirmation, currently blocked by dev-stack contention (§D-2.8).

### D-2.1 — Method

Three source-cited traces: (a) which surface emits the literal "No grounded sources for the latest answer";
(b) where each tier's inline `[n]` marks come from (to test "marks present but sources empty"); (c) the
backend producer of each tier's source list, and how it behaves under BLOCKED_LEGACY. Plus a git-archaeology
check of what 603's merged C1 actually touched, and an external sanity check on the failure class.

### D-2.2 — DECISIVE FINDING: the string is the AGENT pane, and the `[1][2]` are MODEL-AUTHORED, not FE marks

- The exact phrase **"No grounded sources for the latest answer"** exists in **one** place:
  `shell-v0/components/SourcesPane.ts:242` — the **agent-tier** evidence drawer (tempdoc 565 §3.A). It renders
  purely on `getAgentSessionController().answerSources.length === 0` (`SourcesPane.ts:91-93, 232, 241-242`).
  The **Documents-tier** `CitationsPanel` never emits that string — its empty/neutral text is "N sources
  retrieved" (`CitationsPanel.ts:405`). So D-1 is an **agent-tier** observation, not Documents.
- **Both tiers' FE inline `[n]` marks require a non-empty source list.** `citationResolve.ts`:
  `claimsToCitations` returns `[]` when `sources.length === 0` (line 24); `resolveAnswerCitations` (agent)
  returns `[]` when `sources.length === 0 || cites.length === 0` (line 62). And the agent's per-sentence
  cites are themselves derived **from** the sources — `AgentCitationResolver.resolve` returns `List.of()` the
  moment `sources.isEmpty()` (`AgentCitationResolver.java:47`). **Therefore FE marks and the sources pane
  cannot disagree: they are the same `answerSources`.** A genuinely-empty source list yields **no FE marks**.
- **Conclusion:** the "correct inline `[1][2]` citations" the sweep saw alongside the empty pane were **the
  model's own literal `[1][2]` text**, not FE citation marks. The agent's `SearchTool` formats results to the
  LLM as `"[1] <title> (score…) … [2] …"` (`SearchTool.java:383`), so the model naturally writes `[1][2]`
  into its prose. Those survive as plain markdown; the FE adds no marks because `answerSources` is empty.
  This is the textbook **"citation generation vs retrieval mismatch / orphaned citations"** RAG failure —
  the model emits citation markers while the display layer has no source objects to bind them to (external:
  RAG attribution literature; the markers are decoupled from retrieval).

### D-2.3 — The "C1 over-corrected" framing is INACCURATE (git-confirmed)

603's merged C1/C2/C3 touched **only** the Documents-tier projection — `evidenceProjection.ts`,
`CitationsPanel.ts`, `UnifiedChatView.ts` (RAG render + `matchesFromRecord`), and the C2 backend
(`QueryRewriteInjector`/`RAGContext`/`RAGAskShape`). It **never** touched `SourcesPane.ts`,
`AgentSession.collectGroundingSources`, or `SearchTool` (verified: `git show --name-only` over
`059e9c627 f1e98afdb 49d8060f2 493c0cb27 198a13908`). The agent-tier empty-sources state is **pre-existing**
— it is not a swing introduced by C1. So D-1 is **not** "the calibration over-corrected from over-confident
to over-conservative" (that story is Documents-tier; C1's `evidenceProjection` change cannot reach the agent
pane). D-1 is a **separate, pre-existing agent-grounding gap** that the regression sweep surfaced while
BLOCKED_LEGACY. (The over-correction worry was the right instinct for the wrong surface.)

### D-2.4 — ROOT MECHANISM: agent grounds on the DOC-level main pipeline; RAG grounds on the CHUNK path

The two tiers retrieve their grounding through **different** engines, and that asymmetry is the whole bug:

- **Agent** `SearchTool` → `agentSearchAdapter::search` (`AgentToolFactory.java:69`) → the **main**
  `KnowledgeSearchEngine` pipeline → `KnowledgeSearchResponse`. The main text/BM25 query searches the
  **document** `CONTENT` field (`TextQueryOps.buildTextQuery`, line 98-126) and the SPLADE leg explicitly
  **excludes** chunk docs (`IS_CHUNK MUST_NOT true`, `TextQueryOps.java:500-508`). So the sparse legs return
  **document-level** hits. `parent_doc_id` is a **chunk-only** field (written only on `is_chunk=true` docs —
  `ChunkDocumentWriter.java:118`), so a document-level hit carries **no `parent_doc_id`**.
- `AgentSession.collectGroundingSources` keeps **only** hits whose `parent_doc_id` is non-blank
  (`AgentSession.java:204-206` `continue`s otherwise) — "only chunk-identified hits are citable." Under
  BLOCKED_LEGACY (dense/chunk-KNN disabled), every main-pipeline hit is document-level → **all dropped** →
  `answerSources = []`. The code already KNOWS this happens: it WARNs "search hit(s) but none were citable
  (no parentDocId/chunk identity)… check chunk-enrichment readiness, the search mode, or a stale Worker"
  (`AgentSession.java:227-233`). D-1 is exactly that WARN branch, made user-visible.
- **Documents RAG** retrieves grounding via a **different** path — `RagContextOps.searchChunksWithMeta` →
  `chunkSearchOps.searchChunksForDocs/Hybrid` — which is **chunk-native** (BM25 over `CHUNK_CONTENT`,
  filtered to `is_chunk=true`; `ChunkSearchOps.java:95-134`). Chunk hits always carry `parent_doc_id`, so
  Documents grounding **works under pure BM25** — which is precisely why PART X.C live-validated grounded
  sources under BLOCKED_LEGACY while the agent pane goes empty.

So the reopen note's "**gated on vector retrieval**" is *directionally* right (chunk-identified grounding is
unavailable when only the doc-level legs run) but **imprecise**: it is gated on **chunk-level retrieval**,
and the agent tier reaches chunks only via the dense/KNN leg, whereas the Documents tier reaches them via a
dedicated chunk-BM25 stage that survives BLOCKED_LEGACY.

### D-2.5 — Critique of the reopen scope (it mis-targets the authority)

The reopen scope says: *"both project from the CitationMatch / faithfulness evidence (C1's authority), so
'answer has inline [1][2]' and 'SOURCES panel says no grounded sources' cannot coexist."* This is **wrong for
the agent tier**:

- The agent `SourcesPane` has **never** consumed `CitationMatch` / `evidenceProjection`. Its sources are
  `collectGroundingSources` over `SearchTool` hits; its inline marks are `AgentCitationResolver` (which runs
  the matcher only **after** sources exist, to *add marks to* sources — not to *produce* them). There is no
  `CitationMatch`-as-source-of-truth to "re-point" the pane at; re-pointing would be a non-sequitur.
- Worse, the reopen prescription would not fire at all in the BLOCKED_LEGACY repro: with zero chunk-
  identified sources, the matcher has nothing to match, so a "project from faithfulness" rule yields the
  *same* empty pane. The fix has to restore the **sources**, not re-route how they're tiered.

The reopen instinct ("the pane must agree with the answer's citations regardless of retrieval mode") is
**correct**; its mechanism ("project from CitationMatch") is borrowed from the Documents-tier C1 and does not
transfer.

### D-2.6 — CORRECTED DESIGN: degrade to document-level sources (don't drop them)

The answer's `[1][2]` reference the documents the agent actually retrieved. Those documents **are** real,
attributable sources — they merely lack chunk identity (and therefore line-level deep-link precision) under
BM25-only. The faithful fix, scope-matched and 603-owned:

- **Relax `collectGroundingSources` to emit a document-level `AgentSource` when chunk identity is absent**
  but the hit is otherwise a real search result (has a `path`/`title`/`excerpt`). Use the hit's doc id
  (`hit.id()` / `path`) as the source identity; `chunkIndex` absent (or a sentinel), `startLine` 0 (deep-link
  opens the file at top, not a precise line). Dedup by doc id. This converts the silent-WARN branch
  (`totalSearchHits > 0 && out.isEmpty()`) from "drop everything → No grounded sources" into "list the
  document sources the answer drew from." The pane then **agrees with the answer's `[1][2]`** in *any*
  retrieval mode — the reopen goal, achieved without depending on 598.
- **Inline marks degrade gracefully (already the contract).** Doc-level sources have no `parent_doc_id`, so
  `AgentCitationResolver` cannot chunk-match them → it returns no marks (its existing 565 §10 guard:
  "degrade to source-only"). Result: the user sees the model's `[1][2]` prose + a populated document Sources
  list (no FE-rendered marks during this mode). That is coherent and honest — strictly better than an empty
  pane that contradicts the answer.
- **Numbering aligns for the common case.** `SearchTool.formatResults` numbers `[1]…[N]` in
  `response.results()` order; `collectGroundingSources` preserves first-seen order. For a single search call
  the model's `[1]`/`[2]` map to the listed sources in order (cross-call dedup can perturb this — a known,
  acceptable imprecision, far better than empty).
- **Preserve the true-empty case.** When the agent genuinely did **not** search (no `searchResults`) or got
  **zero** hits, the pane stays empty and "No grounded sources" is correct. The fix fires only in the
  `totalSearchHits > 0 && no-chunk-identity` branch — distinguishing "searched, doc-level hits" from "didn't
  search / nothing found," which `collectGroundingSources` already tracks via `totalSearchHits`.

**Alternatives considered and rejected:**
- *(A) Make the agent search chunk-native (like RAG's two-stage).* This would give the agent `parent_doc_id`
  + line-precise deep-links in all modes — the "real" fix — but it is a **retrieval-architecture** change in
  598's hot path (`KnowledgeSearchEngine`/pipeline), heavier, and couples 603 to 598. Out of scope for a
  symmetry completion; record as a future option if doc-level precision proves insufficient.
- *(B) The reopen scope's "project from CitationMatch."* Rejected per §D-2.5 (wrong authority; no-op in the
  repro).
- *(C) Suppress the model's `[1][2]` when grounding is unverified.* Tempting (it removes the contradiction by
  hiding the markers) but it destroys legibility and fights the model's honest behavior; the reopen explicitly
  warns against over-conservatism. Showing the real sources is the trust-positive direction.

### D-2.7 — The deeper, genuinely-out-of-scope issue (flagged, not fixed here)

The model's `[1][2]` are **self-asserted**, not system-verified: the LLM numbers them off the tool output it
read, and nothing checks that sentence-N actually entails source-K (the agent tier has no faithfulness gate
equivalent to the Documents `CitationMatch` cross-encoder when chunk identity is absent). §D-2.6's fix makes
the pane show the **retrieved** sources (honest about *what was retrieved*), but it does **not** verify that
each `[n]` is *faithfully grounded*. That faithfulness gap is the agent-tier analogue of C1's faithfulness
axis and would require either chunk-native agent retrieval (alt A) or a doc-level entailment pass — a larger,
separate piece. Recorded here as the honest scope boundary; not part of the symmetry completion.

### D-2.8 — Remaining work + the one live confirmation (currently blocked)

- **Live-oracle confirmation (the reopen's own gate).** Reproduce in agent mode under BLOCKED_LEGACY: a
  Documents/agent query that searches, confirm the worker log emits the `AgentSession` "none were citable"
  WARN, confirm the answer carries model-authored `[1][2]` and the pane reads "No grounded sources," then
  confirm the fix lists the document sources. **Blocked this turn:** the dev stack is owned by another agent
  session (`quick_health` → `callerIsOwner=false`, fresh lease); per branch-safety I did not take it over.
  The static trace is conclusive on the *mechanism*; the live pass confirms the *symptom path* end-to-end.
- **Regression test (audit-without-test / critical-analysis-pass).** A `collectGroundingSources` unit test
  with `executedTools` whose `searchResults` carry **no `parentDocId`** (doc-level hits) + a non-zero hit
  count → assert the result lists document-level sources (not empty), and that a **zero-hit** run still
  yields empty. This is the test the existing fixtures lack (they all carry chunk identity, so the drop-branch
  was never exercised — the same static-green-≠-live-working blind spot that hid the C1 join bug, IX.0).

### D-2.9 — Confidence + the decision to confirm with the user

- **Diagnosis confidence: high.** Surface, the model-authored-`[1][2]` deduction, the git-confirmed
  "C1 untouched," and the doc-vs-chunk pipeline asymmetry are all source-cited. The residual −is the live
  symptom-path confirmation (§D-2.8), blocked by stack contention, not by uncertainty about the mechanism.
- **Design confidence: high for the direction.** §D-2.6's code direction (agent-tier
  `collectGroundingSources`, a small app-agent change — NOT the Documents `evidenceProjection` authority the
  reopen named) is the scope-matched, 598-independent fix that achieves the reopen goal. **PART D-3 below
  reframes it from a "fallback when empty" patch into its root-cause form** (the provenance-vs-precision
  separation) so it is a structural correction, not a band-aid.

## PART D-3 — long-term design: separate grounding PROVENANCE from grounding PRECISION

> Design theorization (general, not implementation-level). Method directive: root cause, not short-term fix;
> extend existing structure; scope matched to the problem; no speculative abstraction. The investigation
> (PART D-2) located the defect precisely enough that the warranted change is small — that smallness is an
> *outcome* of the diagnosis, not a target. Adjacent tempdocs read: 598 (the BLOCKED_LEGACY/retrieval state
> that exposes D-1; confirms the agent gets chunk hits only when the dense leg runs — §536/§547), 605 (the
> sibling agent-window sweep finding; same "reframe the sweep hypothesis to a deeper structural defect"
> method, different subsystem — approval-gate run-scoping), 565 (the agent grounding-source authority this
> extends), and 595/600/602 (the single-authority depth-round pattern 603 already follows).

### D-3.1 — The real problem, stated structurally

`AgentSession.collectGroundingSources` conflates two questions that the rest of the system keeps separate:

1. **PROVENANCE** — *did the answer draw on this source?* For the agent, the answer's `[1][2]` reference
   whatever the `SearchTool` returned; a real retrieved hit IS a source, full stop.
2. **PRECISION** — *can we cite it at chunk granularity?* Only when the hit carries chunk identity
   (`parent_doc_id`+`chunk_index`) — which enables the line-precise deep-link AND the answer↔source matcher's
   inline marks.

The collector answers (1) using (2)'s gate (`continue` past any hit lacking `parent_doc_id`,
`AgentSession.java:204-206`). That gate is **wrong** because the answer's citations never applied it: the
model cites the documents it retrieved regardless of chunk identity. So the pane shows a *stricter subset*
than the answer cited — and when retrieval is document-level (BLOCKED_LEGACY: the dense/chunk-KNN leg is off,
the sparse legs return doc-level hits, 598 §536/§547), the subset is **empty** and the pane contradicts the
answer. The defect is a **granularity assumption baked into the provenance collector**, not a missing
fallback.

### D-3.2 — The design principle (root cause, scope-matched)

**An agent grounding source's IDENTITY is the document it came from (`parentDocId`/path — always present on a
real hit); chunk identity is OPTIONAL ENRICHMENT that upgrades a source to line-precise + matcher-eligible.
Source existence is never gated on the enrichment.** Concretely:

- The collector keys a source by **document identity** and includes every real retrieved hit. Chunk fields
  (`chunkIndex`, `startLine`/`endLine`, `headingText`) are populated **when present**, absent otherwise. The
  `totalSearchHits>0 && out.isEmpty()` branch (today a silent WARN, `AgentSession.java:227`) stops existing —
  those hits become document-level sources instead of being dropped.
- This is the **same faithful-projection principle 603 is built on** (PART X.8: "the trust surface must
  project the SAME grounding evidence the answer used"), now applied to the agent provenance surface: the
  pane projects exactly what was retrieved, at the granularity retrieval produced, with **no silent filter
  the answer's citations didn't apply**. The contradiction becomes unrepresentable in any retrieval mode.
- The **faithfulness/inline-mark layer already degrades correctly** and is unchanged: `AgentCitationResolver`
  returns source-only when the chunk matcher can't run (its existing 565 §10 guard). Document-level sources
  simply carry no inline marks — the established graceful-degrade contract, now exercised in a mode it wasn't
  before. The matcher remains the agent-tier faithfulness signal **when** chunk identity exists.
- The **"No grounded sources" empty state recovers its true meaning**: it shows only when the agent genuinely
  did not retrieve (no `searchResults` / zero hits) — honest, not a false negative under BM25.

This extends `AgentSource` (565 §3.A) **without new structure**: the record already carries `path`/`title`/
`excerpt` beside the chunk fields, so a document-level source is representable today (chunk fields at a
sentinel/zero; the FE deep-link opens the file at its top rather than a precise line). No new type, no new
authority, no proto-shape change, no retrieval-engine change.

### D-3.3 — What is deliberately NOT in scope (and why it would be over-design)

- **Routing the agent through chunk-native two-stage retrieval** (the way the Documents RAG path always gets
  chunks — `RagContextOps.searchChunksForDocs`). This would give the agent chunk precision in every mode, but
  it is a retrieval-architecture change in 598's hot path, couples 603 to 598, and **the present problem does
  not require chunk precision** — it requires faithful provenance. Adding it now is speculative generality for
  a precision need D-1 does not have. Recorded as a future option *only if* document-level deep-links prove
  insufficient in practice.
- **A document-level faithfulness/entailment verifier** for the agent (so each model `[n]` is *verified*, not
  just *provenance-listed*). The Documents tier verifies via `CitationMatch`; the agent tier verifies only
  when chunk identity is present (the matcher). Document-level entailment without chunks is a **larger,
  separate capability** the present problem does not require. The honest boundary: the agent pane is a
  *provenance* surface ("what the answer drew on"); inline marks are the *faithfulness* enrichment ("which
  sentence used which passage") **when chunk-matchable**. D-1 is a provenance-faithfulness bug, not a
  sentence-entailment bug.
- **Unifying the agent and Documents grounding pipelines.** They differ for a reason (AHA — the agent
  `SearchTool` is a general, model-driven, multi-call tool; the Documents RAG is a single-shot grounded
  answer), so unifying the *engines* is over-DRY. What must be unified is the **invariant** — "the trust
  surface projects the same evidence the answer used, at the granularity retrieval produced" — which D-3.2
  enforces per-tier without merging the pipelines.
- **A new FE numbering reconciliation** between the model's `[1][2]` and the source list order. The collector
  already preserves first-seen order, which matches `SearchTool.formatResults`' `[1]…[N]` for the common
  single-call case; cross-call dedup imprecision is a known, acceptable residual — engineering a guaranteed
  alignment is completeness for its own sake.

### D-3.4 — Why this is the long-term design, not a patch

The band-aid would be "if the source list comes out empty, show *something*." This design instead removes the
*incorrect gate* that made it empty: it corrects the model of what an agent grounding source IS (a document,
optionally refined to a chunk), so the pane is faithful **by construction** across retrieval modes, the empty
state regains its true meaning, and the chunk-precision/faithfulness path is preserved unchanged where it
applies. It is the agent-tier analogue of the Documents-tier C1/PART-X correction — the trust surface
projects the actual grounding evidence rather than a fork (C1: a BM25 score fork; D-1: a chunk-identity-gate
fork) — completing 603's single thesis across both tiers.

### D-3.5 — Deliverables (general)

- **Backend (app-agent):** `collectGroundingSources` keys by document identity, emits document-level sources
  for chunk-identity-absent hits, drops only genuinely non-retrieved/zero-hit runs. The silent-WARN branch is
  replaced by faithful emission.
- **FE (verify, likely no change):** confirm the `SourcesPane` card + `citation-select` deep-link render a
  document-level source (no precise line) acceptably — open the file at top; no inline marks. The 565 §10
  source-only degrade already covers the mark-less case.
- **Regression test:** the test the existing fixtures lack — a run whose `searchResults` carry **no**
  `parentDocId` (+ non-zero hit count) asserts the pane lists document-level sources, while a **zero-hit** run
  still asserts empty. (Closes the static-green-≠-live-working blind spot, same class as the IX.0 join bug.)
- **Live-oracle confirmation** (the reopen's own gate, §D-2.8): currently blocked by dev-stack contention;
  run when the stack frees. The static diagnosis is conclusive on mechanism; the live pass confirms the
  end-to-end symptom path and the fix.

## PART D-4 — frontend / user-facing design: the agent trust surfaces must distinguish SOURCED from GROUNDED

> The D-3 backend change is **directly user-facing** (it repopulates the agent evidence surfaces), so the
> user-facing consequences were inspected against the **live shell-v0 component code** (the authoritative
> render), not the tempdoc. **Live browser screenshots were attempted and blocked** this turn: the dev stack
> + FE are owned by another agent session (`quick_health` → `callerIsOwner=false`), and a freshly-created tab
> self-upgraded to `https` (HSTS) → cert-error, so a clean read-only tab could not load; I did not hijack the
> other session's working tabs or run a query against its backend. The render facts below are read verbatim
> from `SourcesPane.ts`, `UnifiedChatView.renderGroundingBadge`/`renderSourceChips`/`renderUnifiedConversation`,
> `MarkdownBlock.ts`, and `citationResolve.ts` (file:line cited) — a live screenshot pass is a cheap
> confirmation when the stack frees, not a design dependency.

### D-4.1 — What in D-3 is user-visible (direct + indirect)

D-3 emits document-level `AgentSource`s where today the pane is empty. That flows into **four** existing
agent-tier trust surfaces, all keyed on the same `answerSources`/`answerCitations`:

1. **The Sources pane / docked evidence rail** (`SourcesPane.ts`) — today renders the empty-state string
   "No grounded sources for the latest answer" (`:242`); each card shows `title` + **`line ${startLine}`**
   (`:258`) + excerpt.
2. **The grounding-honesty badge** (`renderGroundingBadge`, `UnifiedChatView.ts:3208`) — "Grounded ·
   `cited` of `total` sentences" + a "Why uncited?" disclosure. **Hidden when `sources.length === 0`**
   (`:3213`); coverage is computed from the matcher's `citations` via `groundingCoverage` (`:3215`).
3. **The under-answer source chips** (`renderSourceChips`, `:3250`) — a collapsible "Sources · N" echo with
   `[n]` chips that deep-link; hidden when empty (`:3251`).
4. **The inline `[n]` marks in the answer prose** — woven only when `resolveAnswerCitations` has both sources
   AND matcher cites (`citationResolve.ts:62`); under D-1 there are none, so the model's literal `[1][2]`
   render as **plain text** (the live agent answer passes **no** `frame`, `:3187-3190`, so the 577-Move-3
   `ungrounded` `[n]`-neutralization does **not** fire either — the markers look like real citations).

### D-4.2 — The decisive user-facing consequence: D-3's backend fix ALONE introduces a NEW lie

If the collector simply emits doc-level sources and nothing else changes, the grounding **badge** flips from
*hidden* to *visible* — and renders **"Grounded · 0 of N sentences"**, because doc-level sources carry **no**
matcher `citations` (`cov.cited === 0`, `cov.total === N`). It then adds "N sentences are not backed by a
retrieved passage… treat them as the model's own wording." So the badge would simultaneously assert
**"Grounded"** (the word + the green dot, `:3222-3224`) and **"0 of N"** — a fresh self-contradiction, and
the card would read **"line 0"** (`SourcesPane.ts:258`), and the `[n]` would look like working citations that
click to nothing precise. **That is the C1 disease in a new place** (a trust word decoupled from the evidence)
— trading the over-conservative empty pane for an over-confident "Grounded · 0 of N." So D-3 is **not
complete at the backend**: the FE must represent the new state honestly.

### D-4.3 — Root cause restated for the FE: the trust surfaces conflate SOURCED with GROUNDED

The agent trust surfaces today model a **binary**: either an answer has chunk-precise, sentence-matched
grounding (badge "Grounded · k of N", inline marks, line-precise rail cards) or it has **nothing** (badge
hidden, "No grounded sources"). D-3 introduces a real **third** state the surfaces have no vocabulary for:
**SOURCED** — the answer demonstrably drew on retrieved documents, but per-sentence grounding was **not
verified** (keyword-only retrieval produced doc-level hits with no chunk identity, so the matcher could not
run). The FE design is to give that state an honest, distinct representation — the user-facing completion of
D-3's provenance-vs-precision split.

### D-4.4 — The correct frontend design (three honest states, extending existing surfaces)

A grounded ANSWER renders in exactly one of three states; the surfaces project the state, never a word
decoupled from it:

- **GROUNDED (chunk-precise) — unchanged.** Sources carry chunk identity, the matcher ran. Inline `[n]`
  marks woven; badge "Grounded · k of N sentences"; rail/chip cards show `line N` + deep-link to the exact
  passage. This is today's correct behavior and must not regress.
- **SOURCED (provenance, doc-level) — the new state.**
  - **Sources pane / rail / chips:** list the document sources (filename + excerpt). **Omit the `line N`
    locator** when the source is doc-level (no `startLine`) — render the file alone (e.g. "whole document"
    is implied by the absence of a line), never "line 0". The card click deep-links to the **file at top**
    (no passage highlight) — an honest, lesser affordance, not a broken precise one.
  - **Badge:** must NOT say "Grounded · 0 of N". Render a distinct provenance verdict — e.g. *"Based on N
    document(s) · per-sentence grounding unavailable (keyword-only retrieval)"* — wording at design time, but
    the axis is **"the answer used these documents; which sentence came from which was not verified."** It is
    a peer of "Grounded", not a degraded form of it (it does not claim, then retract, sentence coverage).
  - **Inline `[n]`:** leave the model's `[1][2]` as **plain, unwoven text** — do **NOT** synthesize clickable
    marks by positionally mapping `[n]→sources[n-1]`. That would assert a *verified* sentence→source link the
    system does not have, re-introducing exactly the over-confidence C1 killed (and the IX.1 research caution:
    a mis-attributing grounding cue is worse than none). Equally, do **NOT** apply the full 577-Move-3
    `ungrounded` neutralization (muting them as non-credible) — there ARE real sources here, so muting would
    under-credit. The honest middle: the markers are the model's own reference numbers, and the SOURCED badge
    + the listed documents are what the user inspects. (Whether the markers should carry a subtle "unverified"
    affordance is a *minor* secondary question — default to leaving them plain to avoid signal pile-up, per
    IX.1's "past ~2-3 cues calibration degrades.")
- **UNGROUNDED (no retrieval) — unchanged.** The agent did not search / got zero hits: pane genuinely empty
  ("No grounded sources" — now TRUE, not a false negative), no badge/chips, and the existing `ungrounded`
  `[n]`-neutralization is the right treatment for fabricated markers.

### D-4.5 — Where this lives (extend existing structure; no new authority)

- The three-state distinction is a small extension of the **existing** agent grounding-honesty path
  (`renderGroundingBadge` + `SourcesPane` + `renderSourceChips`), driven by a property the backend already
  knows and D-3 already carries: **whether a source has chunk identity** (precise) **or not** (provenance).
  No new FE authority, no new wire field beyond what D-3 emits, no numeric confidence (IX.1 Tier-4 restraint
  holds). The "SOURCED" verdict is computed from "sources exist AND matcher cites are absent/zero" — the same
  inputs the badge reads today, classified into the third bucket instead of forced into "Grounded".
- This mirrors the Documents-tier `answerFrame` authority's own "declared × actual" logic (grounded /
  ungrounded / degraded), now given the agent tier's missing middle. It is the **user-facing half** of D-3's
  provenance-vs-precision principle: the backend stops dropping doc-level sources; the FE stops describing
  them with a word ("Grounded") that their evidence does not support.

### D-4.6 — Frontend scope boundary (what NOT to build)

- **No per-sentence verification of doc-level sources** (no entailment pass to turn SOURCED into GROUNDED) —
  that is the larger capability D-3.3 already rules out of scope; the SOURCED state is honest about its own
  limit.
- **No synthesized clickable marks** from the model's numbering (§D-4.4 — would fabricate verified links).
- **No numeric confidence / relevance %** (the original C1 sin; IX.1 Tier-4).
- **No new view, drawer, or fourth trust tier** — the three states reuse the existing badge / pane / chips /
  marks surfaces; the change is *what they say in the doc-level case*, not *new chrome*.
- **No precise-line fakery** — a doc-level card must not display a fabricated `line 0`/`line 1`; absence of a
  precise locator is the honest signal.

### D-4.7 — Frontend deliverables (general) + verification

- **`SourcesPane` card:** render the locator conditionally — `line N` only for chunk-precise sources; omit it
  (and any cross-highlight that assumes a passage) for doc-level sources; click deep-links to the file top.
- **`renderGroundingBadge`:** classify into GROUNDED / SOURCED / (hidden when truly UNGROUNDED); the SOURCED
  verdict states provenance without claiming sentence coverage. The `sources.length===0` guard stays for the
  genuinely-empty case.
- **`renderSourceChips`:** unchanged structurally; chips for doc-level sources deep-link to file top.
- **Inline marks:** unchanged (no weave without verified cites; no neutralization when real sources exist).
- **Verification:** unit/FE tests for the three states (esp. SOURCED renders no "Grounded · 0 of N" and no
  "line 0"); the **live screenshot pass** (blocked this turn) confirms the SOURCED badge wording + the
  doc-level card + the coherent answer in the BLOCKED_LEGACY agent repro, alongside the §D-3.5 backend live
  oracle. Both run when the shared stack frees.

## PART D-5 — pre-implementation confidence pass (read-only; no feature code) — 2026-06-17

> Plan-approved confidence pass over the D-3 + D-4 design. Seven uncertainties (U1–U7) tested against the
> producer + consumer code (file:line). Net: the diagnosis holds and the backend fix is small; the FE design
> is **more entangled than PART D-4 stated** and is **adjusted below in two material ways** (a granularity
> sentinel that does triple duty; the SOURCED state must extend the GATED `answerFrame` authority + both
> render paths, not just the badge). No feature code written.

- **U1 (BM25 hits lack `parent_doc_id`) — CONFIRMED structurally; one residual.** `parent_doc_id` is written
  ONLY on chunk docs (`ChunkDocumentWriter.java:118`); the main pipeline's sparse legs query the document
  `CONTENT` field and SPLADE excludes chunks (`TextQueryOps.java:486-508`), so doc-level hits structurally
  carry no `parent_doc_id`, and `SearchTool.buildSearchEvidence` only attaches chunk identity when the field
  is present (`SearchTool.java:317-327`). Residual: the main pipeline has a whole-vs-chunk **branch-fusion**
  path (`HitProvenanceProjector.attachBranchFusion`) that merges chunk results up to the PARENT docId; I did
  not trace whether a merged hit's `fields()` map ever re-exposes `parent_doc_id`. The live symptom (empty
  pane) was already observed (593 sweep), so the WARN branch (`AgentSession.java:227`) demonstrably fired —
  a live hit-field probe (U7) would remove the last doubt. **Diagnosis stands.**
- **U2 (granularity signal) — CONFIRMED gap; sentinel decided.** `AgentSource.chunkIndex` is a primitive
  `int` (Java `AgentEvent.java:155`) / `number` (FE `shape-handlers/shared.ts:41`), so `chunkIndex==0` is a
  VALID first-chunk ordinal and cannot mark "doc-level." **Decision (design-level): a doc-level source
  carries `chunkIndex = -1` AND `startLine = -1`** — a sentinel that needs NO new wire field (cheapest), is
  trivially classifiable on the FE (`startLine < 0` / `chunkIndex < 0` ⇒ doc-level), and — see U3 — also
  suppresses the false highlight. (A boolean `chunkPrecise` was the alternative; rejected as a wire-schema
  add the sentinel makes unnecessary.)
- **U3 (doc-level deep-link) — WORKS, with one wart the sentinel fixes.** `Shell.onCitationSelect` needs only
  a non-empty `parentDocId` (`Shell.ts:490`) — a doc id qualifies, so the file opens. BUT
  `InspectorPane.highlightCitation`/`renderPreview` key the highlight on `startLine >= 0`
  (`InspectorPane.ts:475`), so `startLine 0` would **falsely highlight line 0/the first line**. The
  `startLine = -1` sentinel (U2) makes `hl` false → file opens at top, NO bogus highlight. **The card must
  also omit the `line N` locator when `startLine < 0`** (`SourcesPane.ts:258` currently always renders
  `line ${startLine}` → would show "line -1").
- **U4 (badge + all render paths) — the BIG finding; D-4 under-scoped.** Two facts:
  (a) **One badge method, two call-sites** — `renderGroundingBadge` is called from BOTH the live
  (`UnifiedChatView.ts:3191`) and persisted/record (`:3647`) agent renders, so the SOURCED fix has ONE home.
  (b) **But the frame authority already classifies "sources>0, cited===0" as `'grounded'`** — the explicit
  "marks pending" branch (`evidenceProjection.answerFrame`, `:82-87` + comment `:83-86`). So with D-3's
  doc-level sources: frame = `'grounded'` → `answerFrameLabel` returns `null` (no banner), the markdown block
  does NOT neutralize the model's `[n]` (good — resolves the earlier worry), AND `renderGroundingBadge`
  renders **"Grounded · 0 of N"** (it hardcodes the "Grounded ·" summary, ignoring `groundingCoverage`'s
  already-computed `ready:false`/`label:'Not grounded'`, `:327`). **So the "marks-pending ⇒ grounded"
  semantics PRE-EXIST** (PART X.A U7 flagged exactly this) — they are correct only when chunk-matching is
  POSSIBLE (marks are *coming*, e.g. mid-stream); for doc-level sources marks can NEVER arrive, so it is a
  PERMANENT misframe. **Design adjustment: the SOURCED state must extend the GATED `answerFrame` authority**
  (add a `sourced` frame distinguishing "marks pending / chunk-precise" from "doc-level provenance, no
  per-sentence verification"), drive `answerFrameLabel` + `renderGroundingBadge` from it, and **make the
  LIVE path pass the same frame** (today `:3187` passes none → defaults `'grounded'`, while `:3646` passes a
  frame — a pre-existing live/persisted inconsistency the fix must close). This is larger than D-4's
  "tweak `renderGroundingBadge`", but it stays INSIDE the one gated authority (no new authority), so the
  `check-run-renderers` groundingSemantics gate stays green if the SOURCED predicate lives in
  `evidenceProjection`.
- **U5 (agent tier) — CONFIRMED by code (deductive).** The exact string "No grounded sources for the latest
  answer" exists only in the agent `SourcesPane` (`:242`); the Documents `CitationsPanel` says "N sources
  retrieved" (`:405`); and BOTH tiers' FE inline marks require non-empty sources (`citationResolve.ts:24,62`).
  So the observed `[1][2]`-with-empty-pane can only be agent-mode model-authored text. A live repro (U7)
  would confirm end-to-end but is not needed to fix the right surface.
- **U6 (598 cross-work) — NO active worktree; COMPOSES, no file conflict.** `git worktree list` shows ONLY
  `main` registered (the `.claude/worktrees/*` dirs — incl. `598-r4` — are stale, NOT live worktrees; a
  `git` run inside `598-r4` resolves to main's `.git`). Of the in-range tempdocs modified <5h (593, 595, 597,
  598, 600, 602, 604, 605), only **598** references any of my targets — and only as *evidence* (it cites
  `SearchTool`/`buildSearchEvidence` to argue the agent already runs hybrid), not as edit targets. 598's
  reopened edit scope is the engine/pipeline/`/api/status` dense-serviceable predicate, NOT
  `collectGroundingSources`/`AgentSource`/`SourcesPane`/`renderGroundingBadge`/`answerFrame`. **They compose:**
  598 changes WHICH legs run (so it lowers D-1's *frequency* when dense becomes serviceable); D-3/D-4
  faithfully represent whatever was retrieved in any mode. No file conflict. (Re-scanned after 598's 20:56
  edit — still no grounding-file overlap.)
  - **605 (agent-window stacked-run) — file-level adjacency only, NO semantic interference.** 605 now
    references `AgentSession.java` + `AgentSessionController.ts`, but in the **approval-ceremony / run-identity**
    region (the `sessionId`/approval-gate `CompletableFuture` at `AgentSession.java:34,246-270`; the FE
    singleton + `cancelSession`), NOT the grounding region (`collectGroundingSources` at `:190-235`). 605's
    PART IV says "Backend unchanged (already correct)" — its edits are FE (ToolCallCard / AgentAuthorityPanel /
    AgentSessionController / authorization host). 603-D3 edits `AgentSession.collectGroundingSources` (a
    DIFFERENT region of the same file → trivial merge); 603-D4 may co-edit `UnifiedChatView.ts` (605 = the
    pending tool-call card; 603 = the grounding badge — different regions). Concerns are orthogonal (605 =
    per-run approval safety; 603 = grounding provenance). Coordinate region-level if both land near-term; no
    design interference. **591** (dependency hygiene) is an incidental ArchUnit reference to
    `AgentSession.budgetGateHeld` — unrelated.
- **U7 (live oracle) — STILL BLOCKED, residual.** The dev stack + FE are owned by another agent session
  (`callerIsOwner=false`); I did not take it over and did not query its backend. A browser screenshot pass
  was attempted and blocked by an HSTS self-upgrade on fresh tabs. The static checks (U1–U5) substitute for
  the mechanism; the live pass remains the one end-to-end confirmation (agent query under BLOCKED_LEGACY →
  the `AgentSession:227` WARN + empty pane → after fix, doc-level SOURCED render).

**Design adjustments folded back (the surprises this pass removed):**
1. **Granularity sentinel** `chunkIndex = -1, startLine = -1` for doc-level sources — does triple duty
   (classifiable, suppresses the false highlight, drives the card's locator omission). No new wire field.
2. **SOURCED extends the gated `answerFrame` authority + both render paths**, not just `renderGroundingBadge`
   — because the "marks-pending ⇒ grounded / Grounded · 0 of N" semantics already exist and would
   permanently mislabel doc-level provenance. The live path must pass the same frame the persisted path does.
3. **`[n]` neutralization is NOT triggered** for doc-level (frame is `grounded`/`sourced`, never `ungrounded`)
   — an earlier worry, now retired.

**Critical confidence rating for the remaining D-3 + D-4 work: 7/10.**
- *Backend D-3 (emit doc-level sources, sentinel):* ~9/10 — small, localized, the WARN branch is the exact
  edit site; one regression test (chunk-absent hits ⇒ non-empty, zero hits ⇒ empty).
- *FE D-4 (SOURCED state):* ~6/10 — coherent and correctly homed in the one gated authority, but more
  entangled than first written: it touches `answerFrame` + `answerFrameLabel` + `renderGroundingBadge` +
  `SourcesPane` card + BOTH render paths, and must close the pre-existing live/persisted frame inconsistency
  without tripping the `check-run-renderers` gate.
- *Residual −:* U1's branch-fusion edge + the U7 live repro are unconfirmed against a running stack; both are
  cheap to close when the shared stack frees, and neither threatens the design direction.

## PART D-6 — as-built (D-3 + D-4 IMPLEMENTED, static-green; live verification DEFERRED) — 2026-06-17

Implemented in worktree `603-rag-trust-2` (branch `worktree-603-rag-trust-2`) per the approved plan —
the provenance-vs-precision design, extending existing structure (no new authority, no wire-schema
change; a `-1` sentinel on the existing `int` fields). Tempdoc prose (D-2…D-6) is kept in the main
working tree alongside the analysis; code is isolated in the worktree.

**Backend — D-3 (`AgentSession.collectGroundingSources`, `app-agent`).** A chunk-identity-absent hit
that carries a `path` is no longer dropped — it is emitted as a DOCUMENT-LEVEL `AgentSource`
(identity = `path`, `chunkIndex`/`startLine`/`endLine` = the new `DOC_LEVEL_SENTINEL = -1`), deduped by
`doc#<path>`. Only a hit with neither `parentDocId` nor `path` is now uncitable (the narrowed WARN).
Chunk-precise hits are unchanged. `AgentSource` wire type unchanged (`-1` on existing ints → no schema
regen). `AgentSessionGroundingTest` extended: doc-level → non-empty (asserts the `-1` sentinels);
neither-identity → empty; document dedup; the chunk-precise + no-search cases retained.

**Frontend — D-4 (`ui-web/shell-v0`).** The third honest state, all inside the gated authority + its
consumers:
- `evidenceProjection.ts` — new `'sourced'` `AnswerFrame`; `answerFrame(…, chunkPrecise = true)` returns
  `sourced` when `cited === 0 && sourceCount > 0 && !chunkPrecise` (doc-level: marks can never arrive —
  not "marks pending"); default `true` preserves every existing (RAG) caller. `answerFrameLabel('sourced')`
  = "Based on your documents — per-sentence grounding not verified". New `sourcesAreChunkPrecise()` helper
  (doc-level iff every source carries the `-1` sentinel; a source type without `chunkIndex` — RAG
  `RetrievalCitation` — counts as chunk-precise), so the SOURCED↔GROUNDED split is derived in ONE place.
- `UnifiedChatView.ts` — `frameFor(…, chunkPrecise)` threaded; the agent persisted (`renderUnifiedItem`)
  and `renderMessage` call-sites pass `sourcesAreChunkPrecise(sources)` (RAG passes the default `true`).
  `renderGroundingBadge` derives chunk-precision from its own `sources` and, for the doc-level case,
  renders the provenance verdict "Based on N document(s)" (+ a plain-language "why") with NO "Grounded"
  word / green dot — never "Grounded · 0 of N". Token-only `.answer-frame-sourced` CSS (secondary tone,
  not warning). **Deliberate refinement vs the plan:** the transient live STREAMING markdown-block
  (`renderUnifiedConversation`) is left frameless — passing a frame mid-stream (before sources land →
  `ungrounded`) would muting-neutralize the model's `[n]` while streaming; the badge fix (shared method)
  already covers the post-done moment, and the durable committed render carries the frame line. `[n]`
  neutralization stays off for `sourced` (it is not `ungrounded`).
- `SourcesPane.ts` — the card omits the line locator (and the "at line N" open-hint) when `startLine < 0`;
  it still deep-links (`citation-select` → `/api/preview?docId=<path>` opens the file at top; `startLine
  -1` makes `highlightCitation`'s `startLine >= 0` test false → NO false highlight).
- Tests: `evidenceProjection.test.ts` (the `sourced` frame + label + the `sourcesAreChunkPrecise`
  discriminator incl. the RAG-no-chunkIndex and all-sentinel cases), `SourcesPane.test.ts` (doc-level →
  no locator, still deep-links with `startLine -1`), `UnifiedChatView.test.ts` (a doc-level agent answer
  renders "Based on 2 documents" + the SOURCED frame line, NEVER "Grounded · 0").

**Static verification — ALL GREEN (surfaced in the transcript):**
- `npm run typecheck` clean; touched FE files 78/78; **full FE unit suite 3186 passed (330 files)**.
- `:modules:app-agent:test` + `:modules:app-api:test` green (incl. the extended `AgentSessionGroundingTest`).
- `./gradlew.bat build -x test` BUILD SUCCESSFUL (compile + spotlessApply + PMD).
- Gates: `check-run-renderers` (groundingSemantics — change stayed inside the evidenceProjection
  authority, 4 consumer sites, no out-of-authority threshold), `check-color-tokens`,
  `check-presentation-purity`, `check-theme-token-closure` — all OK.

**Live browser verification — DEFERRED (dev stack owned by another session).** Per the goal's
non-takeover rule: at implementation-completion the shared dev stack + FE (`:5173`, api `:53182`) were
owned by another agent session (`quick_health` → `callerIsOwner=false`, fresh lease, `aiActive=null`), so
neither a takeover nor a query against their backend (which would load the model on their stack) was
permissible, and a competing second stack is precluded by the one-at-a-time memory/port constraint. The
implementation is therefore **code-complete + static-green with the live pass explicitly OUTSTANDING** —
NOT a finished feature. Remaining live steps (run when the stack frees / with a dedicated worktree stack):
in a BLOCKED_LEGACY agent run, confirm in the real UI that (a) the Sources pane lists the document
sources (not "No grounded sources"), (b) the grounding badge reads "Based on N documents" (not "Grounded ·
0 of N"), (c) no card shows "line -1"/"line 0", (d) clicking a source opens the file preview with no bogus
highlight, (e) a chunk-precise (dense-available) answer still renders the GROUNDED state unchanged. Capture
screenshots/DOM as evidence and record the result here.

### D-6.1 — LIVE BROWSER VALIDATION of D-4 (DONE, real UI) + the D-3 E2E limitation — 2026-06-17

After the user authorized a takeover, the live pass ran — but with a structural constraint surfaced: the
**dev-runner is main-bound** (its state path resolves to `F:/JustSearch` even when invoked from the worktree),
so it builds/runs `main`'s code. Running a real agent query through it would exercise the OLD
`collectGroundingSources` (no D-3) and reproduce the bug, not verify the fix; and merging the worktree is out
of scope per the goal. So a full backend agent-run E2E of D-3 is gated behind the merge.

**What WAS verified live (the user-visible D-4 behavior, real UI, real worktree FE code).** Served the
worktree FE on a standalone Vite (`127.0.0.1:5174`, the `localhost`-HSTS dodge) and drove the real
`jf-unified-chat-view` (path `jf-shell → jf-stage → jf-unified-chat-view`), injecting the EXACT
`AgentSource` shape D-3 emits (document-level: `chunkIndex = -1`, `startLine = -1`) — the contract is
unit-pinned by `AgentSessionGroundingTest`, so the seam is identical to a real run. Evidence (DOM probe +
two screenshots in the transcript):
- **SOURCED badge, not the lie:** the grounding badge renders **"Based on 2 documents"** (`.grounding-badge-sourced`);
  `Grounded · 0` is ABSENT. The frame line reads **"Based on your documents — per-sentence grounding not
  verified"** (`.answer-frame-sourced`). The model's `[1][2]` render as plain text (not neutralized).
- **Sources listed, no fabricated locator:** the "Sources · 2" disclosure expands to two document chips
  ("1 01-system-overview.md", "2 07-ui-host-architecture.md"); the whole view contains **no "line -1"/"line 0"**.
- **Deep-link opens the file, no bogus highlight:** clicking a source opened the inspector with the file
  selected (`selectedPath` set) and **`highlightStartLine === -1` → no highlight painted** (the U3 concern,
  confirmed). The preview returned `HTTP 404` ONLY because the injected id was a repo-relative synthetic path;
  a real D-3 emission carries the backend's stored `path`, which resolves — the inspector degraded gracefully.
- **GROUNDED unchanged (regression):** injecting a chunk-precise source (`chunkIndex 0`, lines 12–18) + a
  per-sentence cite renders **"Grounded · 1 of 1 sentences"**, no SOURCED badge, no "Based on" — the existing
  path is intact.

**Honest status.** D-4 (the user-visible change) is **live-verified in the real UI**. D-3 (the produce side)
is **unit-verified** (`AgentSessionGroundingTest`); its output is the exact shape the FE was live-tested
against. The only unrun item is a single end-to-end agent run through the D-3 BACKEND code, which requires
merging the worktree to main (the main-bound dev-runner cannot load worktree code) — a post-merge smoke, not
a design risk. Recommended at merge time: `:modules:ui:installDist` on main after merge, start the stack in a
BLOCKED_LEGACY state, run an agent query that searches, and confirm the live answer shows the SOURCED state
end-to-end (the worker log's narrowed `AgentSession` WARN should no longer fire for path-bearing hits).
