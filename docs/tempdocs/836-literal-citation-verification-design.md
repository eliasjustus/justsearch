# 836 — Literal-text citation verification: design

```
status: DESIGN (no implementation) — rev 2, probes P1/P2 run, independent review incorporated
created: 2026-08-14
updated: 2026-08-14
related: 833 §W4 + finding 4 (the theorization this designs), 822 (the citation
  chain: F-047 / F-048 "claim-score provenance" / F-049 / F-050), register Q-021
  (should summarize get a cross-encoder pass), FW-009 (the citation threshold is
  uncalibrated)
```

> **Register citation note.** `origin/main`'s search-quality register currently carries **two**
> entries numbered F-048 (a concurrent-session id collision, logged to the inbox; renumber handled
> separately). Throughout this doc, **F-048 means the *claim-score provenance* entry** (register
> `:933`, "claim scores were `Math.max`-ed across two producers"), **not** the paraphrase-bridging
> entry at `:869`. Cite it as "F-048 claim-score provenance (F-048b pending renumber)".

## 0. Scope, and one correction to this document's own standing

Design only. It specifies one Worker-facing seam — a `matchCitations` that scores **supplied**
passage text instead of re-fetching chunk text from Lucene — plus staged consumer work, measured
cost, and the tests that make the failure mode this seam exists to avoid *unconstructible*.

**Rev-1 honesty defect, recorded rather than quietly fixed.** Rev 1 asserted that every `file:line`
had been re-verified. That was not true of one: the grounding-class citation `MarkdownBlock.ts:342-350`
was copied from F-048's register prose rather than checked, and it is stale — `:342-350` is CSS
custom-property declarations for code/quote styling. The real grounding classes are `:371-379`.
That single unverified borrow is what produced rev 1's wrong §3.5(0) premise (see §3.6). The rule
this violated is the one this document spends §5 enforcing on code: *a claim that passes for the
wrong reason is not a passing claim.* Every `file:line` in rev 2 was opened.

The thesis, now stronger than rev 1 could argue it: **the naive wiring is not a future hazard. On
the live summarize-selection route, chunk-0-derived grounding marks render today and are durably
persisted to the conversation record.**

---

## A. Corrections to the 833 §W4 brief (and to rev 1)

**A.1 — There is no "ask selection path". The ask tier is already correct.**
`RAGAskShape` declares `List.of(ExternalContextInjector.ID, QueryRewriteInjector.ID, RAGContext.ID)`
(`shapes/RAGAskShape.java:69`) — no `SelectionContextInjector`. Only `SummarizeShape` (`:65`) and
`ExtractShape` (`:70`) register it. Ask's citations come from `RAGContext`, which stashes real
retrieval citations with true document-relative ordinals (`spi/RAGContext.java:248`), so the
Worker's `(parentDocId, chunkIndex)` re-fetch resolves the right text there. **The `chunkIndex=0`
defect is confined to summarize and extract.**

**A.2 — The fabrication is live; its severity is NOT what rev 2 claimed. (Rev 1 named the wrong
blocker; rev 2 then overstated the consequence. Measured in §9.8.)**

> **Correction from P3 (§9.8).** The heading below read "The wrong marks render and persist TODAY".
> Measured: the `chunkIndex: 0` fabrication *is* real and on the wire (§9.8.1), and it *does* feed
> the matcher — but scoring summary sentences against the wrong chunk yields near-zero
> cross-encoder scores, so the claims are **dropped** and the surface renders **markless**. The
> defect's measured expression is silent under-marking, not a confidently-wrong mark. Wrong marks
> stay *constructible* — the score ordering is already inverted (chunk 0 outscored the true chunk on
> one sentence) and only the 0.5 threshold suppresses it — but that case is unmeasured. Read the
> chain below as mechanism; read §9.8 for what it actually produces.

Rev 1 said the marks were invisible because `SummarizeView` has no `onRagCitationMatches` handler.
That is true of `SummarizeView` and irrelevant, because **`SummarizeView` is not the dispatching
surface for a selection-summarize** — it never sends `body.selection`
(`views/SummarizeView.ts:313-316`), so `SelectionContextInjector` never fires there and
`ATTR_CITATIONS` is never stashed on that surface at all.

The live route is `UnifiedChatView`:

1. **Entry** — the "Summarize selection" action (`shell-v0/.../coreSelectionActions.ts:25-40`).
2. **Compose** — sets a one-shot pending selection + a forced shape (`controllers/compose.ts:209-218`).
3. **Dispatch** — `UnifiedChatView.ts:5754-5771` takes the forced shape through an **unchecked
   `ShapeId` cast** (`core.summarize` is not in `CORE_INTERACTION_SHAPES`), drains the pending
   selection, and puts it on `body.selection`; `:5779` stamps `body.conversationId`.
4. **Handlers are shape-INDEPENDENT** (`:5830`, dispatch `:6086`) — so a `core.summarize` stream is
   handed the same handler map the ask tier uses.
5. **`onRagCitationMatches` (`:6003`)** writes the Worker's `m.similarity` straight into
   `verifiedScore` (`:6044`, `Math.max(existing.verifiedScore ?? 0, sim)`) and the sourceIndex into
   `verifiedRefs` (`:6045`). `claimsToCitations` then mints real citations — the F-048 provenance
   gate passes, because these *are* cross-encoder scores. They are simply scores of **the wrong
   text**.
6. **And it persists.** `SummarizeShape` records to thread (`ConversationShape.java:133-135`;
   `threadRecordId` resolves because `body.conversationId` was stamped at step 3 —
   `ConversationEngine.java:702-719`), and `claimMatches` is written onto the record
   (`:755-757`) and re-rendered on reload (`UnifiedChatView.ts:5199-5200`).

Backing this: `ConversationEngine` forwards consumer events to the sink with **no filtering against
the shape's declared `EVENT_SCHEMA`** (`:381`), so the undeclared `rag.citation_matches` reaches the
browser; the citations it scores are the injector's `chunkIndex=0` fabrications
(`SelectionContextInjector.java:190, 226, 254, 274` → `stashCitation :402`); and the Worker
faithfully re-fetches chunk 0 (`CitationMatchOps.java:160` → `lookupChunkContent :339-358`).

So the staging conclusion holds *a fortiori*: **the FE handler is not a follow-up to gate — it is
already live.** S1 is not prevention, it is repair of a shipping defect that writes to durable
storage.

**A.3 — Supplied text does not remove truncation; it creates a worse one. (Now measured — §9 P1.)**
`RerankerTokenizer` hard-truncates the pair and pads (`:59-65`); `max_position_embeddings` is 512
(model `config.json`), and `tokenizer.json` declares no truncation section, so **DJL's own default
does the cutting** — invisibly to the wrapper. Measured saturation onset on real repo prose:
**~1.9 KB of passage** (§9 P1c). A selection can be 200,000 chars
(`SelectionContextInjector.java:57`; `DocAccess.java:51`). Un-windowed, ~99% of a large selection
would be silently discarded and the result reported as verification. **Windowing is part of the
seam's correctness.**

**A.4 — Extract has no matcher at all.** `ExtractShape` stream consumers are
`List.of(ValidationConsumer.ID)` (`:71`); event schema is `chunk, reasoning_chunk, rag.citations,
done, error` (`:51-52`). Extract needs the injector fix (shared, free from S1) **plus** matcher
registration plus schema+codegen+FE. Largest, not smallest.

**A.5 — `injectResultSet` is starved, and it is now IN SCOPE (moved into S1).**
It emits a `rag.citations` SSE from a hand-built map but never calls `stashCitation`, so it sets
neither `ATTR_CITATIONS` nor `ATTR_USED_RAG` (`SelectionContextInjector.java:280-314`, cf. `:402-403`).
Rev 1 pushed this to the inbox. That was wrong under *retire-with-a-sweep*: once S1 converts the
other three variants to literal text, this becomes **the only starved arm of the same class**, and
it is reachable from the same floating menu the user just used. A fourth variant left behind is
exactly the residue that outlives its reason. It ships with S1.

---

## 1. The seam

### 1.1 What changes, end to end

| Layer | File | Change |
|---|---|---|
| Proto | `modules/ipc-common/src/main/proto/indexing.proto:498-503` | additive `repeated string passage_texts = 5;`; additive provenance + coverage fields on the response (§3.6, §4) |
| Head API | `modules/app-api/.../DocumentService.java:306-310` | new `matchCitations(String, List<VerificationSource>, double)`; existing overload becomes a delegating default |
| **Head lazy proxy** | **`modules/app-services/.../conversation/LazyDocumentService.java:78-82`** | **override the new overload and forward.** Without this the call lands on `DocumentService`'s empty default (`:306-310` returns `CitationMatchResult(List.of(),0,0,0)`) — green build, **silently inert seam**. This is the wiring site (`ConversationApiAssembly.java:106-107`) |
| Head client | `modules/app-services/.../worker/RemoteDocumentService.java:513-544` | the one site that unpacks `List<VerificationSource>` into the parallel proto arrays |
| Head RPC | `modules/app-services/.../worker/SearchRpcOps.java:409-423` | `addAllPassageTexts(...)` |
| Worker entry | `modules/worker-services/.../GrpcSearchService.java:799-812` | pass `request.getPassageTextsList()`; reject length mismatch (§1.4) |
| Worker core | `modules/worker-services/.../CitationMatchOps.java:156-163` (CE path) **and** `:220-233` (cosine path) | supplied text wins, blank falls back to `lookupChunkContent`; then window (§1.3) |
| Worker scorer | `modules/reranker/.../CitationScorer.java:128-170`, `:187-220` | sub-batching + per-sentence budget (§1.3) — a **precondition**, not a tuning knob |

Both `CitationMatchOps` paths are listed deliberately: the cosine fallback is a **second producer**
with its own copy of the lookup loop, and fixing only the cross-encoder branch leaves a path that
silently reverts to the wrong text whenever the scorer is absent.

### 1.2 Additive field vs. new method — argued

**Decision: additive field on `MatchCitationsRequest`, one RPC.**

- **The operation is unchanged** — same answer text, sources, threshold, response shape, deadline,
  scorer selection, fallback ladder, provenance question. A second RPC forks all of it, and the
  copies drift the way F-048's two score producers drifted.
- **The arrays keep their meaning.** `chunk_doc_ids[i]` / `chunk_indices[i]` remain the *provenance*
  of source `i` (they populate `parent_doc_id` on a match, `CitationMatchOps.java:179`) and the
  *fallback lookup key*. Nothing is repurposed — the distinction F-049 was born from.
- **Per-source granularity** — a blank entry at `i` means "look this one up". A `bool` flag or a
  separate RPC forces an all-or-nothing choice consumers don't have.
- **Governance is cheap.** The `wire` gate's spec dir is `contracts/wire`
  (`scripts/governance/gates/wire/enforcer.mjs:54-56`); `indexing.proto` is outside it, so no wire
  changeset. The applicable authority is the `workflow-grpc-method` recipe
  (`governance/consult-register.v1.json:79-92`) — with one correction: for a *search* RPC the
  forwarding wrapper is `DelegatingSearchService.java` (`matchCitations` forward at `:82`), not the
  `DelegatingIngestService` the recipe names.
- **Four in-tree callers keep compiling unchanged** via the delegating default:
  `AgentCitationResolver.java:77`, `LazyDocumentService.java:81`,
  `StreamingCitationMatcher.java:140`, and `RetrieveContextController.java:292` — the last being a
  REST surface (`/api/…/citations`) whose request body has no place to put literal text, so a new
  RPC would either strand it or force a second REST contract change.
- **Degrades correctly.** Head and Worker ship together, but an old Worker receiving the field just
  ignores it and does today's lookup; a new RPC would return `UNIMPLEMENTED`.

Against: *"supplied text deserves its own name."* It doesn't — it is the same operation with text
resolution short-circuited. The real risk the objection points at is **parallel-array desync**,
answered on the Java side (§1.4) and by a hard wire-level reject.

### 1.3 Windowing, per-sentence budget, and sub-batching

**Where: the Worker, inside `CitationMatchOps`.** `ChunkSplitter` is already available
(`modules/worker-services/build.gradle.kts:15`; `RagContextOps.java:854` already calls it).
Worker-side keeps one authority for "how a passage is prepared for this scorer" next to the scorer
that owns `maxSequenceLength` (`CitationScorerConfig.java:44-49`, threaded via
`InferenceCompositionRoot.java:465`).

**The index-space invariant** — windowing adds a new ordinal beside an existing one, which is the
shape of F-049:

> A window is scored; a **source** is reported. `CitationMatchEntry.source_index` is always a
> position in the request arrays, never a window ordinal. A source's score is the max over its
> windows; the winning window's identity does not travel.

Mechanically: a flat window list plus a `window → sourceIndex` back-map; the winning index is mapped
back before the entry is built. `CitationMatchOps.java:175-177` currently passes
`ScoredMatch.chunkIndex` straight through as `source_index` — correct only while windows and sources
are 1:1. **That line must become a back-map lookup**, and it is the single most likely place to
reintroduce F-049 (test §5.4).

**Per-sentence passage budget, not a fixed reserve.** A constant "512 minus N reserve" does not make
truncation unconstructible: sentence length varies, and a long sentence silently eats the window's
tail. Measured spread (§9 P1c): a 10-word sentence leaves ~1934 chars of real-text budget, a 60-word
sentence ~1712 — a 12% swing, and unbounded above. Two acceptable forms:

- **(a) Budget per sentence**: size the window against *this* sentence's token count, so the pair is
  provably under the cut. Costs a re-window per sentence.
- **(b) Make truncation observable**: have `RerankerTokenizer` report `ids.length > maxLength` (it
  already computes `copyLen = Math.min(ids.length, maxLength)`, `:60`) and treat overflow as an
  error rather than a silent cut.

**(b) is preferred and (a) is the fallback if (b) proves expensive** — (b) converts a whole class of
silent truncation into a loud failure at every call site, including the ask tier's existing one.

There is a second, sharper reason for (b): the wrapper's `System.arraycopy(ids, 0, …, copyLen)`
(`:61`) keeps the **first** `maxLength` tokens, so a truncated pair loses its trailing `[SEP]`. The
model is not merely fed a short passage — it is fed a **malformed** pair (no closing separator, and
the second segment's `token_type_ids` cut mid-run). That is a correctness argument for detecting
truncation, not just a budgeting one.

**Sub-batching inside `scoreSentenceAgainstChunks` is a PRECONDITION, not an optimization.** The
deadline is checked only *between* sentences (`CitationScorer.java:129-133`), so one sentence against
N windows is one uninterruptible ONNX call. Measured (§9 P2c): 1 sentence × 400 windows with a
2000 ms budget ran **12,763 ms — a 10.8 s overrun**; at 600 windows the grid recorded a single call
of **49,770 ms**. A 200 KB selection over 5 sources is ~670 windows, i.e. a ~6 MB tensor set in one
call that no budget can stop and that the Head has already abandoned (its cap is 5 s,
`StreamingCitationMatcher.java:49`). The scorer must chunk the window list into sub-batches and check
the deadline between them.

**Window size: 1500 chars.** Derived from P1c (real-text budget 1712–1934 chars across a 10–60-word
sentence range) with headroom. Not a guess; see §9.

### 1.4 The Head-side shape, and the sentinel

```java
/** A source the matcher may verify against: its citation identity + optionally its literal text. */
record VerificationSource(ContextCitation citation, String literalText) { }
```

`matchCitations(String answerText, List<VerificationSource> sources, double threshold)` is the real
method; the `List<ContextCitation>` signature stays as a default mapping each citation to
`new VerificationSource(c, "")`. One position authority instead of three parallel lists kept equal by
discipline — the F-049 lesson applied to the API rather than restated.

**The wire's parallel arrays get a hard reject, never a `Math.min`.** `CitationMatchOps` currently
absorbs desync with `int chunkCount = Math.min(chunkDocIds.size(), chunkIndices.size())` (`:156`,
again `:220`) — silently dropping the tail of the longer array. **Do not extend that idiom to a third
array.** A `passage_texts` length that is neither `0` nor `sources.size()` is a caller bug and must
fail with `INVALID_ARGUMENT`, because a silently-shortened passage list would mis-align text to
sources — the exact mis-targeting class F-049 closed, re-opened through the back door.

**The empty-string sentinel is triple-overloaded and must be specified.** `""` already means
"lookup failed" (`CitationMatchOps.java:161`) and "skip this chunk" (`CitationScorer.java:147-149`).
Adding "not supplied" without a spec makes three facts one value. Specification, to be written into
the proto comment:

> `passage_texts` is either **empty** (no source supplies text) or **exactly `chunk_doc_ids.size()`**
> — any other length is `INVALID_ARGUMENT`. A **blank** entry means *this source supplies no text*,
> so the Worker looks it up; a lookup that then fails yields a blank passage, which the scorer skips,
> and the source is reported as unverifiable rather than unmatched.

**What must NOT change: `ContextCitation`.** It is also the FE-facing retrieval shape (`rag.citations`
→ `citationTypes.ts:74-86`) and its `excerpt` is a deliberate 200-char display preview
(`SelectionContextInjector.java:195, 226, 259, 274`). A multi-KB verification payload on a
browser-serialized record fuses two facts with different reasons to change — and tempts a future
implementer to verify against the 200-char preview (test §5.7).

---

## 2. Staging (revised)

Four slices. **S1 is repair of a shipping defect** (§A.2), not preparation.

### S1 (proving slice) — the seam + all four injector variants. Backend only.

§1 in full (proto, `DocumentService`, **`LazyDocumentService`**, `RemoteDocumentService`,
`SearchRpcOps`, `CitationMatchOps` on both producer paths, Worker windowing, sub-batching, truncation
detection, coverage + provenance fields), plus `SelectionContextInjector` supplying the text it
already computed — in each variant the `truncated` string that goes into the user message
(`injectTextRange :184-185`, `injectItem :222-224`, `injectCitation :248-250`,
`injectInlineExcerpt :270`), **not** `citation.excerpt()` — **plus A.5's `injectResultSet`**, which
gains the `stashCitation` (`ATTR_CITATIONS` + `ATTR_USED_RAG`) it never had.

Why first:

1. **It stops a live wrong mark that is being written to durable storage** (§A.2). Nothing else here
   is fixing something already happening.
2. **The literal text is free** — already computed and truncated by the injector.
3. **Bounded and reversible** — no FE change, no shape-schema change, no codegen; the chunk path is
   byte-identical when `passage_texts` is empty (test §5.8).
4. **It fixes extract's injector for free** (`ExtractShape.java:70` shares the class).
5. **`chunkIndex=0` stops being load-bearing** rather than being "corrected". A selected passage has
   no chunk ordinal; the honest fix is to stop needing one.

Verification: §5 tests, plus P3 (§7) on the **`UnifiedChatView` summarize-selection route** — scores
must move when the selected passage changes and must *not* move when only chunk 0 changes.

### S2+S3 — MERGED, and the old order was backwards. `DocAccess` first.

Rev 1 shipped the summarize FE chain (S2) before the `DocAccess` population (S3). That was wrong:
`SummarizeView` never sends `body.selection` (`:313-316`), so on that surface
`SelectionContextInjector` never runs and `ATTR_CITATIONS` is never stashed — **S2's deliverable
would have been dead code until S3 existed.** The prerequisite is the producer.

Merged slice, in order:

1. **`DocAccess.inject` populates the doc path.** It currently returns
   `InjectorResult.messagesOnly(...)` (`DocAccess.java:98`) — no `ATTR_CITATIONS`, no `rag.citations`
   event; that is why finding 4 calls the matcher "starved" (it returns empty at
   `StreamingCitationMatcher.java:134`). Population belongs in the **injector**, not the shape: the
   shape is a manifest of ids and modes; the injector is what knows which document it resolved and
   what text it injected. Mirror `stashCitation` (`:401-404`) + `citationsEvent` (`:406-415`), and
   supply the injected `truncated` string as verification text.
2. **Schema declaration** — `SummarizeShape.EVENT_SCHEMA` (`:46-47`) gains `"rag.citation_matches"`.
   This part stands on its own merit even ahead of (1): it is a **declaration catching up with
   reality**, since the event already flows (`ConversationEngine.java:381`).
3. **Codegen** — `ConversationShapeFixtureGenTest` regenerates `scripts/codegen/shapes.fixture.json`;
   `node scripts/codegen/gen-shape-handlers.mjs` regenerates
   `modules/ui-web/src/api/generated/shape-handlers/core-summarize.ts` (gated by
   `scripts/ci/check-shape-handler-regen.mjs`).
4. **`SummarizeView.ts:361-372`** stops hardcoding `verifiedScore: null`, subject to §4's
   discriminator.

**On the default-off flag.** Q-021 asks for one, and rev 1 accepted that uncritically. It does not
work as stated: **S1 already fixes the marks on `UnifiedChatView`, unflagged** — that surface is
where selection-summarize actually renders (§A.2), and it needs no flag because S1 makes it *correct*
rather than *new*. A flag on `SummarizeView` would gate a surface that shows nothing until step (1)
lands anyway. So:

- **Drop the flag.** The mechanism it would use is F-050's `ARM_SWITCH_KEY` pattern — a request-body
  key read by the component, default-off for an absent or non-`true` value
  (`AnswerShapeGrammar.java:71, 116-125`) — and it is the right pattern for an *A/B*, which is what
  P5 is. Gate on **P5's measurement**, not on a shipped-but-dark control. A dead control in a window
  whose law is "no dead controls" (833 finding 3) is not a safer default; it is the same defect in a
  different place.

### S4 — extract.

Injector fixed by S1. Still needs `StreamingCitationMatcher.ID` in stream consumers (`:71`),
`rag.citation_delta` + `rag.citation_matches` in `EVENT_SCHEMA` (`:51-52`), the codegen chain, and an
extract-side consumer. Last — and whether per-sentence grounding is even the right evidence shape for
schema-constrained JSON output is a product question this design does not answer.

---

## 3. Cost — measured, and what it forces

Rev 1 pre-registered a decision rule. §9 records the raw numbers; this section states the
consequences.

### 3.1 The measured cost model

**~25 ms per (sentence × window) pair, CPU, and essentially LINEAR in batch size** — 21.0 ms/pair at
batch 1, 26.3 ms/pair at batch 128 (§9 P2a). Batching buys nothing; total cost is
`pairs × ~25 ms`. Two corollaries:

- **The 2000 ms budget buys ~80 pairs per turn.** The 5 s Head cap
  (`StreamingCitationMatcher.java:49`) buys ~200.
- **Since batching does not amortize, sub-batching for deadline granularity (§1.3) costs
  approximately nothing.** The precondition is free.

### 3.2 The pre-registered rule fired

> *If L = 10 KB, S = 15, N = 1 does not complete within 2000 ms, the degradation design is mandatory
> for S1.*

Measured: **2045 ms, 12 of 15 sentences scored — MISS** (§9 P2b). **The degradation tier is
mandatory.** It was not close and it was not marginal: only the 2 KB single-source cells pass the
budget at all. A 10 KB selection — an ordinary long paragraph — already fails.

### 3.3 The capacity envelope this implies

At ~25 ms/pair against 2000 ms (≈80 pairs), literal-text verification fits **only**:

| Answer sentences | Max total windows | ≈ max passage (1500 chars/window) |
|---|---|---|
| 5 | 16 | ~24 KB across all sources |
| 15 | 5 | ~7.5 KB |
| 40 | 2 | ~3 KB |

A whole-document summarize (S3's case, up to 200 KB) is **two orders of magnitude outside the
envelope**. Literal-text verification of a whole document is not a budgeting problem; it is out of
scope for a synchronous per-turn pass, and S3 must say so rather than degrade to scoring the first
2 KB.

### 3.4 The Head/Worker timeout inversion is now a measured defect

Cells above ~5 s are **completed by the Worker and discarded by the Head**: the Head's
`MATCH_TIMEOUT` is 5 s and the worst measured cell burned **49.8 s** of CPU producing a result nobody
reads (§9 P2b, 200 KB × 5 sources). So S1 needs an **admission check before scoring**: estimate
`pairs × unitCost` and, if it exceeds the budget, degrade *up front* (§3.5) instead of starting work
that will be thrown away. Any deadline change must move the Worker budget and the Head timeout
together, or a raised Worker budget just produces discarded completions.

### 3.5 Degradation design — ranked

1. **Admission control + window prefiltering (required for S1).** Cap total pairs at the measured
   envelope (§3.3). When windows exceed the cap, select candidates per source by cheap lexical
   overlap and cross-encode only the top-K. The lexical score is a **candidate selector that never
   leaves the Worker** — nothing lexical is ever reported — so F-048's provenance law is untouched.
   **Implementation caveat (fork risk):** `matchSentenceLexical` lives Head-side in
   `StreamingCitationMatcher.java:241-282` and `worker-services` cannot depend on it. Either extract
   it to a shared module or **explicitly accept a documented duplicate** — do not let a second copy
   appear silently, which is how two producers of one quantity happen (F-048, again).
2. **Report coverage honestly (required, and retargeted — see §3.6).**
3. **Per-call deadline for the doc path**, moving the Head cap with it (§3.4). Bounded: the true
   ceiling is 5 s ≈ 200 pairs, which does not rescue the 10 KB × 15-sentence cell by much and does
   not touch the document case at all.
4. **Never:** score less and report full coverage.

### 3.6 Coverage honesty — REWRITTEN (rev 1's premise was wrong)

**Rev 1 claimed** a deadline-skipped sentence renders as a confidently-wrong *ungrounded* (warning)
mark. **That is false**, and the error traces to the stale citation admitted in §0:

- The Worker only ever emits matches **at or above threshold** (`CitationScorer.java:157`), so a
  skipped sentence produces no match.
- No match ⇒ no claim ⇒ `claimsToCitations` mints nothing: the score gate
  (`citationResolve.ts:45`) and the ref gate (`:49-52`) both drop it.
- No citation ⇒ no `.cite-sentence` class ⇒ plain prose. And **grounded prose is also plain**:
  `.cite-sentence.grounding-grounded { border-bottom: none }` (`MarkdownBlock.ts:371-373`), with
  uncited prose deliberately unmarked (`:369-370`). A skipped sentence is **pixel-identical to a
  grounded one** — under-marking, not mis-marking.

**The real defect is the coverage line, and it is a fork.** `groundingCoverage`
(`evidenceProjection.ts:368-384`) computes `total = Math.max(countSentences(answerText), cited)` and
labels `"Grounded · ${cited} of ${total} sentences"`. A truncated scoring run lowers `cited`, so the
frame **silently understates coverage** — it attributes a *deadline* to the *evidence*, telling the
user the answer is less grounded than it is. And `countSentences` is a regex terminator count
(`evidenceProjection.ts:360-361`) while the backend segments with `BreakIterator`
(`CitationMatchOps.java:290-305`): **two sentence-count authorities that already disagree**, one of
which is the denominator of a user-facing honesty claim.

Retarget:

- Add `sentences_scored` to `MatchCitationsResponse` and carry it through `CitationMatchResult`.
- Make it the coverage **denominator** — or, when `sentences_scored < sentences_total`, drive an
  explicit *scoring-incomplete* state rather than a smaller ratio. "12 of 15 scored" is a different
  statement from "12 of 15 grounded", and only the second is about evidence.
- Close the M-source fork: the backend's `BreakIterator` count is the authority; the FE regex
  counter stops being a second one.

---

## 4. The provenance discriminator

**Recommendation: wire field + Worker population in S1. The FE gate flip lands with S1 *if* P4 shows
the fallback is common — P4 is now a blocker, not a follow-up.**

### The defect

Two producers write the same `similarity` field: the CPU cross-encoder (`CitationMatchOps.java:167-181`,
sigmoid-normalized relevance) and the embedding cosine fallback (`:236-266`, raw cosine). Nothing on
the wire says which ran, and the FE reads it as `Citation.similarity`, declared "cross-encoder
similarity", feeding `groundingClass`. That is **F-048 claim-score provenance (F-048b pending
renumber)** — two producers, one field — surviving on the head↔worker wire. F-048 separated the two
producers meeting in the *browser*; it never touched the two meeting in the *Worker*.

**Correction to rev 1's fallback triggers.** Rev 1 said the fallback fires when
`!scorer.isAvailable()`. `isAvailable()` is merely `!closed` (`CitationScorer.java:81-83`) — it is
true for any live scorer. The real triggers are **`citationScorer == null`** (never wired: dev mode
without contract, model absent, tokenizer load failure — `CitationMatchOps.java:117-119, 148`) and the
**catch-all** at `:190-193`, which swallows *any* cross-encoder exception at WARN and falls through.

### Why it belongs in this seam

This seam takes the producer space from 2 (which scorer) to 4 (which scorer × supplied-vs-looked-up
text). Adding a dimension to an already-ambiguous field, in the slice whose purpose is "the marks must
be about the right text", is the move F-048 forbids. Without a discriminator a reviewer cannot
distinguish "cross-encoder scored the supplied passage" from "no scorer, so cosine scored a looked-up
chunk" — the best and worst outcomes of this design, reported identically.

Shape: `string scorer = 6;` on the response (`CROSS_ENCODER` | `EMBEDDING_COSINE` | `NONE`) and
`string text_source = 6;` per match (`SUPPLIED` | `CHUNK_LOOKUP`) — per-match because the fallback is
per-source (§1.2) and a response-level answer would be a lie for mixed requests. Both onto
`DocumentService.CitationMatchResult` / `CitationMatchEntry`.

### Does the FE gate flip ship WITH S1? — ANSWERED BY P4: no, it stays separate

Rev 2 argued it might have to, on a *prior* that cosine similarities sit around 0.6–0.85 so a
scorer-less install would render nearly every sentence as grounded. **P4 measured it and the prior
was wrong** (§9.7): off-topic sentences score 0.38–0.42 under cosine and none cross 0.5 or 0.6.
There is no mass false-grounding to stop, so the gate flip is **not** correctness-urgent for S1 and
remains its own slice, as rev 1 had it.

P4 strengthened the case for the **wire field** while removing the case for rushing the FE flip. The
two producers are measurably on incomparable scales — CE effectively bimodal (0.89–0.999 supporting,
below 0.001 off-topic, no match emitted), cosine compressed into 0.38–0.72 with the supported and
unsupported bands **interleaving** (separation 0.0049). One 0.5 constant is being applied to both.
That is exactly the F-048 shape, measured rather than argued, and it is why `scorer` /
`text_source` ship with S1 even though nothing consumes them yet.

---

## 5. Wrong-marks safety — invariant test shapes

Each test makes a specific confidently-wrong output unconstructible, and names the mistake it catches.

**5.1 Supplied text wins over chunk text — on *both* producer paths.** Index a document whose chunk 0
is semantically disjoint from the supplied passage. Assert a sentence quoting the *passage* matches
and a sentence quoting *chunk 0* does not. Parameterized over {cross-encoder present, scorer `null` →
cosine}. *Catches:* the naive wiring; and the half-fix that patches only the CE branch, leaving
`:220-233` looking up chunks.

**5.2 No lookup happens when text is supplied.** With `passage_texts` populated for every source,
a `ReadPathOps` spy records **zero** searches. *Catches:* "the fallback silently ran anyway", which a
score-only assertion cannot distinguish from success.

**5.3 Fallback granularity is per source.** `[A: supplied, B: blank]` → A scored on supplied text, B
looked up, both present. *Catches:* an all-or-nothing implementation.

**5.4 Windowing preserves the numbering contract.** Property form: for a source whose text yields
N>1 windows, *every* returned match satisfies `0 <= source_index < sources.size()` **and**
`parent_doc_id == chunk_doc_ids[source_index]`. *Catches:* F-049 re-entering through the new window
index space — the highest-risk line in the design (`CitationMatchOps.java:175-177`).

**5.5 Truncation is impossible, not merely unlikely.** With the §1.3(b) detector: a pair exceeding
`maxLength` raises rather than silently copying a prefix. Plus the behavioural half — a claim
supported **only by a passage's tail** still matches. *Catches:* §A.3, including the malformed-pair
variant (lost trailing `[SEP]`) that a length-only assertion would miss.

**5.6 Deadline exhaustion is reported, not absorbed. — REWRITTEN.** Rev 1's version asserted that
skipped sentences "mint no claim", which **passes today, before any fix** (§3.6) — the wrong-reason
pass this section exists to forbid. The test that fails pre-fix: with a budget that stops scoring
early, assert `sentences_scored < sentences_total` **and** that the coverage projection reports a
scoring-incomplete state rather than a reduced grounded-ratio over a full denominator. Companion:
`countSentences(answerText)` equals the backend's `BreakIterator` count for a fixture set including
abbreviations and decimals — a test that fails today and pins the fork closed.

**5.7 The injector supplies the injected text, not the preview.** For each of the **four** variants
(including `injectResultSet`), the verification text equals the string placed in the user message,
not the 200-char `excerpt`. *Catches:* reaching for `citation.excerpt()` because it is the field named
"the text" — which would verify a 200 KB document against 200 characters.

**5.8 The chunk path is byte-identical when no text is supplied.**
`GrpcSearchServiceMatchCitationsTest` (`:56, :75, :94, :108, :136`) passes **unedited**. *Catches:*
regression of the ask tier, which §A.1 establishes is currently correct. A test in this class needing
a change is the signal to stop, not to edit.

**5.9 Length mismatch is rejected, never absorbed.** `passage_texts.size()` ∉ {0, sources.size()} ⇒
`INVALID_ARGUMENT`. *Catches:* the `Math.min` idiom (`:156`, `:220`) being extended to a third array,
which would mis-align text to sources.

**5.10 Provenance is populated on every path.** Every response carries `scorer`, every match
`text_source`, across all four producer combinations including the `EMBEDDING_UNAVAILABLE` return
(`:196-203`). *Catches:* the phantom-field class — a discriminator that exists and is never set reads
as an answer.

**5.11 Declared schema matches registered producers** (with S2+S3 / S4). For the shapes touched,
`EVENT_SCHEMA` contains `rag.citation_matches` **iff** `StreamingCitationMatcher.ID` is a stream
consumer. *Catches:* the unfiltered-event gap (`ConversationEngine.java:381`), pinned for these shapes
rather than left substrate-wide.

---

## 6. Risks and non-goals

**GPU non-contention holds; CPU contention is the new question.** `CitationScorer` is CPU-only by
construction (`:29-30`, `:86-94`). Nothing here moves it. But §9's numbers were taken on an idle
machine, and windowing multiplies CPU work at the moment llama-server is also running. P6 must record
CPU saturation, not just wall-clock.

**Deadline interaction with the stream.** The matcher runs at `onDone`
(`ConversationEngine.java:369-381`), delaying the `done` event, not the visible answer — but a
multi-second gap after the text stops reads as a hang, and §9 shows multi-second is the *normal* case
above 2 KB. Decoupling (emit `done` first, matches after) is not free: `claimMatches` rides the done
payload (`StreamingCitationMatcher.java:151-155`) so the evidence record can be rebuilt on reload
(561 P-A / F-049), and decoupling without a replacement persistence route recreates the render-path
divergence that mechanism prevents. **Open question, not a decision** (§8.1).

**Scorer absent.** `citationScorer == null` → cosine fallback (`CitationMatchOps.java:148`); embedding
provider also unavailable → `error: "EMBEDDING_UNAVAILABLE"`, zero matches (`:196-203`) → no claims →
honestly markless, the correct output. §4's discriminator makes this legible. Non-goal: making the
scorer mandatory.

**Non-goals, explicitly:**

- **Making summarize marks *good*.** This makes them *about the right text*. Whether cross-encoder
  verification of summary prose is useful is Q-021's measurement (P5); the threshold is uncalibrated
  on real content (FW-009) and summary prose is a content type no calibration has seen.
- **Whole-document literal verification** — §3.3 puts it two orders of magnitude outside the
  envelope. S3 supplies citations for the doc path; it does not promise to verify 200 KB in a turn.
- **Ask-tier retrieval verification.** `RAGContext` already supplies true ordinals (`:248`).
  (But see §7 P1's finding — the ask tier has a *separate*, pre-existing truncation problem.)
- **Calibration, thresholds, or the streaming lexical matcher.**
- **A schema-enforcement mechanism in `ConversationEngine`** — pinned per-shape by 5.11 instead of
  growing a substrate feature inside a citation slice.

---

## 7. Probes — reranked (P1, P2 DONE; P4 promoted to blocker)

**P1 — Truncation. DONE (§9).** Verdict: truncation is real, invisible, and at ~1.9 KB of real prose.
**It also widened, as predicted: Q6 is answered YES** — a default indexed chunk already overflows the
pair window on real text, so the **ask tier has been verifying against truncated chunks all along**
(§9 Q6). This is language-dependent in a way that touches **Hard Invariant 6**: `ChunkSplitter` sizes
chunks by a *character* heuristic (`:92` 500 tokens, `:112` latin 5.0/1.3, `:117` CJK 1.0), so how
much of a chunk survives the 512-token cut varies by script and by technical density. Measured
here: 3.92 chars/token on real English repo prose vs 5.87 on a low-entropy synthetic vocabulary — a
**50% swing from text character alone**, on the same nominal chunk size. CJK and code-dense text will
sit further from the heuristic. Follow-up scope: an ask-tier truncation fix is *not* part of W4, but
it is now a named, measured defect (§8.6) rather than a suspicion.

**P2 — Cost grid. DONE (§9).** Decision rule fired; degradation is mandatory (§3.2).

**P4 — Cosine-fallback prevalence. DONE (§9.7).** Answer: the tier is installed by every shipped
intent, so reachability is narrower than rev 2 assumed — partial install, or the `:190-193`
catch-all. The "everything renders grounded" claim is **refuted**; the real finding is a 0.0049
class separation under cosine. FE gate flip stays a separate slice (§4).

**P3 — Live confirmation of §A.2. PARTIAL (§9.8).** The `chunkIndex: 0` fabrication is confirmed on
the wire; the *severity* is refuted — under-marking, not wrong marks. It was genuinely falsifying,
and it falsified half the claim. **Re-run target, sharpened:** a selection whose summary is
semantically close to the document's *opening* (these docs open with an abstract restating the whole
file), which is the case where a chunk-0 score can clear 0.5 and mint a mark about the wrong
passage. Needs a browser for the render/reload half (§9.8.3) and the §9.9 stall resolved.

**P5 — Q-021 quality measurement.** After S1 + S2/S3. In-range rate, mark density, added latency on
real summaries. Gated on the seam being correct — measuring mark quality earlier measures the chunk-0
artifact. This, not a shipped flag, is what gates the summarize tier (§2).

**P6 — Live cost cross-check under CPU contention.** Confirms or refutes §9 with llama-server
running. Last; §9 already produced the model.

---

## 8. Open questions

1. **Does the `done`-event delay need decoupling, and where do `claimMatches` live if so?** (§6.)
   Sharpened by §9: multi-second matcher latency is the normal case, not the tail. Decide before the
   S2+S3 merge makes marks visible on a second surface.
2. **Does the FE provenance-gate flip ship with S1?** P4 decides (§4).
3. **~~Does overlap inflate max-over-windows scores?~~ ANSWERED: deferring this is not safe, and it
   is not about overlap.** The score reported for a source is a **max over N windows** — an order
   statistic whose expected value rises with N even under a null. Today N ≈ 5 (one window per
   retrieved chunk); after windowing N reaches the hundreds. So the *same* threshold becomes
   progressively more permissive as passages get longer: a long selection would look better-grounded
   than a short one for purely statistical reasons. **This belongs in P2's grid as a measured
   false-positive rate versus window count** (score distribution of a sentence against windows known
   to be irrelevant, swept over N) — not deferred to a later calibration pass. It is a threshold
   question the seam creates.
4. **~~Should `DocAccess`'s citation carry a `chunkIndex`?~~ ANSWERED: model the absence.** The `0` is
   what made this whole defect constructible; replacing one `0` with another in a new call site
   repeats it. The field should express "not applicable" rather than carry a plausible number —
   whether by `OptionalInt`, a sentinel the Worker rejects, or splitting the record. Decided in
   S2+S3, but decided, not defaulted.
5. **Is per-sentence grounding the right evidence shape for extract?** (§2 S4.) If no, S4 collapses to
   the injector fix S1 already delivers.
6. **How is the ask tier's chunk truncation fixed?** (New, from §9 Q6.) Options: raise chunk-side
   headroom, window chunks at scoring time (the same machinery S1 builds), or detect-and-fail per
   §1.3(b). Out of W4's scope; needs an owner because it is currently silent on every ask turn.

---

## 9. Probe results (2026-08-14, offline; no dev stack)

Harness: a throwaway `Probe836Harness` in `modules/reranker/src/test/java/` — **uncommitted, not part
of this design's deliverable**. Model: `ms-marco-MiniLM-L-6-v2` (`config.json`:
`max_position_embeddings: 512`, 6 layers, hidden 384) from the shared
`F:/JustSearch/models/onnx/citation-scorer`. CPU-only session via the production
`OrtSessionAssembler.buildManager` path. Machine idle; llama-server not running.

### 9.1 Instrument correction (recorded, per interrogate-results)

Run 1 reported "pair tokens = 512" for every passage from 3 KB to 200 KB and a "surviving prefix" of
exactly the search ceiling. That was a **saturated instrument, not a finding**: DJL's
`HuggingFaceTokenizer` applies its own default truncation, so a count of 512 means "cut", not
"measured". `tokenizer.json` declares **no** truncation section and the model caps at 512, so the cut
is DJL's default — **invisible to `RerankerTokenizer`, and not governed by the `maxLength` the code
passes it**. Consequence worth recording independently: `JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN` above
512 would widen the arrays while DJL still cuts at 512 — a silently-ignored config lever. Run 2
re-measured by searching for the *saturation onset* (largest prefix scoring < 512 tokens) and swapped
the synthetic corpus for real repo prose after finding the synthetic vocabulary tokenized ~50% more
efficiently than real text.

### 9.2 P1 — truncation

```
P1a  pair-token count vs passage length (probe sentence: 24 tokens)
        1000 chars ->  192 tokens
        2000 chars ->  365 tokens
        3000 chars ->  512  SATURATED (truncated; true count unknown)
   10000/50000/200000 chars ->  512  SATURATED

P1c  SATURATION ONSET — passage prefix that still fits the 512-token pair
   answer sentence 10 words ( 17 tok): synthetic 2892 chars | REAL TEXT 1934 chars
   answer sentence 20 words ( 26 tok): synthetic 2854 chars | REAL TEXT 1903 chars
   answer sentence 35 words ( 44 tok): synthetic 2740 chars | REAL TEXT 1827 chars
   answer sentence 60 words ( 72 tok): synthetic 2606 chars | REAL TEXT 1712 chars

Q6  does an indexed 500-token chunk overflow the pair window?
   ChunkSplitter DEFAULT_CHUNK_TOKENS=500 -> tokensToChars = 1923 chars
   pair tokens, synthetic chunk =  350   fits
   pair tokens, REAL chunk      =  512   TRUNCATES
   real-text prefix that saturates = 1903 chars
```

Derived chars/token: real repo prose **3.92**, synthetic 48-word vocabulary **5.87**.

**What it does to the design.** (a) Confirms windowing as a correctness precondition (§A.3) and
**fixes the window size at 1500 chars** (§1.3) — below the 1712-char worst measured budget. (b) The
budget varies 1712–1934 chars with answer-sentence length, which is why §1.3 requires a per-sentence
budget or a truncation detector rather than a fixed reserve. (c) **Q6 is YES**: `ChunkSplitter`'s
1923-char chunk saturates at 1903 chars of real text — the ask tier truncates the tail of a
default-sized chunk on every turn, today (§7 P1, §8.6). (d) The 50% synthetic-vs-real swing is the
language-dependence flag against Hard Invariant 6.

### 9.3 P2a — unit cost curve (1 sentence, 1500-char windows, no deadline; best of 3, warmed)

```
  batch |   ms   | ms/pair
      1 |     21 |   21.00
      2 |     46 |   23.00
      4 |     99 |   24.75
      8 |    196 |   24.50
     16 |    395 |   24.69
     32 |    839 |   26.22
     64 |   1693 |   26.45
    128 |   3372 |   26.34
```

**~25 ms/pair, linear — batching does not amortize.** Hence §3.1's envelope and §3.3's table, and
hence sub-batching for deadline granularity is free.

### 9.4 P2c — deadline granularity

```
  1 sentence x  32 windows, deadline 2000ms -> actual   817 ms  (-1183)
  1 sentence x 128 windows, deadline 2000ms -> actual  3552 ms  (+1552)
  1 sentence x 400 windows, deadline 2000ms -> actual 12763 ms (+10763)
```

**The budget cannot stop a single sentence's batch** (`CitationScorer.java:129-133`). Makes
sub-batching a precondition (§1.3), not a tuning knob.

### 9.5 P2b — cost grid (deadline 2000 ms; threshold 0.0 so `sentencesMatched` == sentences *scored*)

```
  L(chars) | W(chars) | N src | S sent | windows |  ms   | scored/total | verdict
      2000 |     1500 |     1 |      5 |       2 |   233 |     5/5      | OK
      2000 |     1500 |     1 |     15 |       2 |   644 |    15/15     | OK
      2000 |     1500 |     1 |     40 |       2 |  1923 |    40/40     | OK
      2000 |     1500 |     5 |      5 |      10 |  1264 |     5/5      | OK
      2000 |     1500 |     5 |     15 |      10 |  2166 |     9/15     | MISS
      2000 |     1500 |     5 |     40 |      10 |  2179 |     9/40     | MISS
     10000 |     1500 |     1 |      5 |       7 |   840 |     5/5      | OK
     10000 |     1500 |     1 |     15 |       7 |  2045 |    12/15     | MISS  <- decision-rule cell
     10000 |     1500 |     1 |     40 |       7 |  2011 |    12/40     | MISS
     10000 |     1500 |     5 |      5 |      35 |  2556 |     3/5      | MISS
     10000 |     1500 |     5 |     40 |      35 |  2568 |     3/40     | MISS
     50000 |     1500 |     1 |     15 |      34 |  2566 |     3/15     | MISS
     50000 |     1500 |     5 |     15 |     170 |  4482 |     1/15     | MISS
    200000 |     1500 |     1 |     15 |     134 |  3357 |     1/15     | MISS
    200000 |     1500 |     5 |      5 |     600 | 49770 |     1/5      | MISS (windows capped 600)
    200000 |     1125 |     5 |      5 |     600 | 57243 |     1/5      | MISS (windows capped 600)
```

(Abridged; the 1125-char window arm and the remaining sentence counts behave identically — the
narrower window buys nothing because cost tracks pair count, not window size. Full 48-cell grid in the
harness output.)

**What it does to the design.** (a) **The pre-registered rule fired at 2045 ms / 12-of-15 — the
degradation tier is mandatory** (§3.2). (b) Only 2 KB single-source cells pass; the envelope is ~80
pairs (§3.3). (c) **Cells above 5 s are discarded by the Head** — 49.8 s of Worker CPU for a result
nobody reads — which is why S1 needs admission control *before* scoring, not just a deadline during it
(§3.4). (d) The narrower-window arm confirms cost is pair-count-driven, so "use smaller windows" is
not a degradation lever; only "score fewer pairs" is.

### 9.6 Not measured (offline round)

Q3's false-positive-rate-versus-window-count sweep (§8.3) — identified during review as belonging in
this grid, after it had run. It needs an irrelevant-passage control set the harness does not build.
**Open, and it gates the threshold story, not the cost story.** (§9.7's unsupported-sentence control
set is the missing ingredient; a follow-up sweep can now reuse it.)

---

## 9.7 P4 — cosine-fallback prevalence (live, 2026-08-14)

Stack: this worktree's dist, own lease, torn down and verified afterwards (§9.10). Corpus:
`docs/explanation` (30 docs). Instrument: `POST /api/knowledge/match-citations` —
`RetrieveContextController.java:292` calls the **same** `documents.matchCitations` the ask/summarize
matcher calls (`StreamingCitationMatcher.java:140`), so this measures the production producer with
controlled inputs and no LLM in the loop. Both arms used identical inputs; only the producer changed.

### 9.7.1 Two instrument corrections (recorded, per interrogate-results)

1. **`threshold: 0.0` is silently coerced to 0.5.** `GrpcSearchService.java:805-807` replaces any
   non-positive threshold with `DEFAULT_SIMILARITY_THRESHOLD`. Run 1 therefore returned only
   ≥0.5 matches while appearing to return a full distribution. All reported runs use `0.001`.
2. **The backend re-segments `answer_text`.** A multi-sentence payload is re-split by
   `BreakIterator` (`CitationMatchOps.java:290-305`), so array indices do not survive — run 1
   mislabelled a document sentence as "unsupported". All reported runs send **one sentence per
   call**. (This is §3.6's sentence-count fork, encountered as an experimental hazard.)

### 9.7.2 Is the fallback reachable on a real install? — static, and it corrects rev 2

Rev 2 speculated that a user could exclude the tier. **They cannot:** every shipped `InstallIntent`
wants `RETRIEVAL_ENRICHMENT` (`InstallIntent.java:69-73` — `case RETRIEVAL_CORE,
RETRIEVAL_ENRICHMENT, RUNTIME -> true`; only the LLM tier is intent-gated), the package is in the
shipped registry (`modules/ui/src/main/resources/ai/model-registry.v2.json:193-215`, CPU/INT8,
`minVramBytes: 0`), and no hardware profile excludes it. So **tier selection is not a reachability
path**. The remaining paths, narrower than rev 2 implied:

- **Install AI not yet run / partially completed** — the models are downloaded post-install;
  `repairNeeded` on `/api/ai/install/status` exists precisely because partial installs are a real
  state. A partial install that got the LLM but not the scorer yields RAG answers with cosine scores.
- **The catch-all at `CitationMatchOps.java:190-193`** — *any* cross-encoder exception falls through
  to cosine at WARN, on a fully-installed system. Intent-independent, and invisible on the wire.
- **Dev**: the dev-runner's comment that "reranker/citation-scorer are inactive" in dev
  (`scripts/dev/dev-runner.cjs:1378-1381`) is **stale** — `JUSTSEARCH_MODELS_DIR` auto-discovery
  (`:502-507`) resolved the scorer from the shared checkout and it wired cleanly
  (`Citation scorer wired: model=model.onnx, sha256=a13ec391...`, matching the registry hash).

### 9.7.3 The measurement

Arm B was produced by renaming `model.onnx` + `model.onnx.optimized` in the resolved model dir for
one run (worker then logged `ONNX model 'citation-scorer': not found at any standard location`),
then restoring — verified byte-identical by SHA-256 afterwards (§9.10).

Inputs, both arms: 5 sentences drawn from the indexed document ("SUPPORTED") and 5 plainly
off-topic sentences about music, baking, athletics, penguins and saffron ("unsupported" — the
discriminating control). 8 chunk_refs, threshold 0.001.

```
ARM A — cross-encoder PRESENT
  kind          sim      >=0.5  >=0.6   src
  SUPPORTED     0.8962    YES    YES     0
  SUPPORTED     0.9991    YES    YES     6
  SUPPORTED     0.9946    YES    YES     6
  SUPPORTED     0.1082    no     no      3
  SUPPORTED     0.0012    no     no      5
  unsupported   (none)    no     no      -      <- no match returned at all, i.e. < 0.001
  unsupported   (none)    no     no      -
  unsupported   (none)    no     no      -
  unsupported   (none)    no     no      -
  unsupported   (none)    no     no      -
  SUPPORTED   mean 0.5999 (3 of 5 above 0.89)
  UNSUPPORTED scored 0/5 — every off-topic sentence fell below the 0.001 floor

ARM B — cross-encoder ABSENT (embedding cosine fallback)
  kind          sim      >=0.5  >=0.6   src
  SUPPORTED     0.7108    YES    YES     1
  SUPPORTED     0.6574    YES    YES     5
  SUPPORTED     0.7223    YES    YES     6
  SUPPORTED     0.6913    YES    YES     7
  SUPPORTED     0.4206    no     no      1
  unsupported   0.4119    no     no      6
  unsupported   0.4157    no     no      6
  unsupported   0.3831    no     no      4
  unsupported   0.3825    no     no      3
  unsupported   0.3848    no     no      5
  SUPPORTED   min 0.4206  max 0.7223  mean 0.6405
  UNSUPPORTED min 0.3825  max 0.4157  mean 0.3956  — 0/5 cross 0.5, 0/5 cross 0.6
  separation (min supported − max unsupported) = 0.0049
```

### 9.7.4 Verdict — the "everything renders grounded" claim is REFUTED as stated

Rev 2 §4 predicted cosine similarities of roughly 0.6–0.85 on related text, so that a scorer-less
install would render "nearly every sentence as grounded". **Measured: no.** Off-topic sentences
score 0.38–0.42 and **none** cross 0.5 or 0.6. The prior was explicitly flagged as a prior, and it
was wrong.

What the data shows instead is worse in a different way, and it *strengthens* the discriminator
argument rather than weakening it:

- **The two producers occupy incomparable scales.** CE is effectively bimodal — supporting text
  0.89–0.999, off-topic below 0.001 (no match emitted at all). Cosine is unimodal and compressed
  into 0.38–0.72. The *same* 0.5 constant means "confidently supported" on one scale and "somewhat
  above the domain floor" on the other.
- **Cosine barely separates the classes at all.** Supported-min 0.4206 versus unsupported-max
  0.4157: the distributions **interleave**, with a margin of 0.0049. The threshold is not
  distinguishing grounded from ungrounded; it is cutting through the middle of one blurred band.
  That the cut currently lands above the off-topic cluster is luck, not calibration — cosine bands
  are domain-sensitive, and a corpus that shifts the band up ~0.1 puts off-topic sentences over 0.5.
- **Cosine always names a source.** Every off-topic sentence got a `source_index` (3–6); CE returned
  no match. So the fallback's failure mode is "a nearest chunk always exists, just under threshold",
  one calibration shift away from marking.

**Consequence for §4 — the rev-2 hedge resolves back toward rev 1.** The FE gate flip is *not*
correctness-urgent for S1: there is no mass false-grounding to stop. It stays a **separate slice**.
The wire discriminator, by contrast, is now better justified than when argued from theory: two
measured, non-monotone-related scales are being written into one field, and nothing on the wire says
which one arrived. Ship the field with S1 as §4 states.

**Honest limit:** one corpus (English technical Markdown), one embedding model, 10 sentences. This
refutes "everything renders grounded" but does not establish a safe margin — the 0.0049 separation
is the finding, and it says the cosine arm is close to uninformative rather than safely calibrated.

---

## 9.8 P3 — live hazard confirmation (partial: mechanism confirmed, severity REFUTED)

Two halves. The first is confirmed on the wire; the second is measured and **contradicts rev 2's
severity claim**.

### 9.8.1 The fabrication is real and on the wire — CONFIRMED

Replicating the `UnifiedChatView` dispatch at the API level (`POST /api/chat/dispatch`, the endpoint
`host.ai.streamShape` posts to — `capabilities/ai.ts:44-50`), with `shapeId: core.summarize` and a
`body.selection` text-range over chars **[15000, 17500)** of a 22,800-char document, the very first
SSE frame is:

```
event: rag.citations
data: {"citations":[{"parentDocId":"…18-adapters-lucene-deep-dive.md",
       "chunkIndex":0,"startChar":15000,"endChar":17500,"score":1.0,
       "excerpt":"``java\nTopDocs topDocs = searcher.searchAfter(after, query, limit + 1, sort);…"}]}
```

`startChar`/`endChar` carry the true selection; **`chunkIndex` is 0** — a passage 15 KB into the
document announced as the document's first chunk. §A.2's fabrication is confirmed live, exactly as
`SelectionContextInjector.java:190` writes it.

### 9.8.2 What the matcher then does with it — measured, and it is NOT wrong marks

Rev 2 asserted that chunk-0-derived grounding marks "render and are durably persisted today". The
stream stalled before the matcher ran (§9.9), so I measured the second half directly through the
same `documents.matchCitations` call the matcher makes, supplying the summary sentences the LLM
would have produced (four faithful restatements of the *selected* passage — pagination /
`searchAfter` / cursor / `FieldMapper`):

```
locating the chunk that truly holds the selection:
   c0: -   c1: -   c2: -  c3: -  c4: -  c5: -  c6: -  c7: -  c8:0.1746  c9: -  c10: -  c11: -  c12: -
   => the selection lives in chunk 8

  sim@chunk0   sim@chunk8   >=0.5@c0   sentence
     (none)      0.1746        no      Pagination uses searchAfter with a cursor …
     (none)      0.3079        no      The cursor encodes a sort key and a document identifier …
     0.0470      0.0298        no      FieldMapper converts typed values into Lucene fields …
     (none)      (none)        no      The reader requests one more hit than the page limit …

  at chunk 0 (the injector's claim): 1/4 scored, mean 0.0470, 0 cross 0.5
  at chunk 8 (the truth):            3/4 scored, mean 0.1708, 0 cross 0.5
```

**Verdict: the chunk-0 lie produces NO marks here, not wrong marks.** Scoring summary sentences
against the wrong chunk yields near-zero cross-encoder scores, so the claims are dropped and the
surface renders markless. The practical expression of the defect on this input is **silent
under-marking**, which is the same class §3.6 identified — not the confidently-wrong mark rev 2
claimed.

**So rev 2's severity framing was wrong and is retracted.** What survives, and still justifies S1
shipping first:

- The fabrication is real, on the wire, and feeds the matcher (§9.8.1) — the chain runs end to end.
- The tier is silently unverifiable: correct grounding is *unreachable* because the matcher is
  pointed at the wrong text, and the failure is indistinguishable from "this answer isn't grounded".
- **Wrong marks remain constructible, just not on this input.** The observed value is *rank* — one
  sentence scored **higher** against chunk 0 (0.0470) than against the true chunk 8 (0.0298). The
  ordering is already wrong; only the 0.5 threshold is suppressing it. A selection whose summary is
  semantically close to the document's opening — extremely common, since these documents open with
  an abstract that restates the whole file — would clear the threshold against chunk 0 and mint a
  mark that is wrong about *which* passage supports the claim. That case is **unmeasured**, and it
  is the specific follow-up P3 should target if re-run.
- Note also that even against the **correct** chunk nothing cleared 0.5 (max 0.3079) — consistent
  with FW-009 (uncalibrated threshold) and F-050 (compression starves per-sentence matching), and a
  caution for P5: the seam being correct does not imply the marks will appear.

### 9.8.3 What could NOT be confirmed without a browser

- That `UnifiedChatView` renders these as `verifiedScore`-bearing marks, and that a reload
  re-renders them from the persisted `claimMatches`. The FE code path is read-verified
  (`:6003`, `:6044-6045`, `:5199-5200`) but not executed. Given §9.8.2, the honest expectation is
  now that **no marks would render** on this input, so a browser round should target the
  chunk-0-collision case above, not the generic one.
- The `X-JustSearch-Audience: USER` dispatch was accepted (HTTP 200, `text/event-stream`), so the
  request shape itself is confirmed to match what the FE sends.

## 9.9 Blocked: the summarize stream never completes

Every `core.summarize` + `body.selection` dispatch emitted `rag.citations` and then **stalled** — no
`chunk`, no `done`, for 90–180 s, across three runs on two stack instances. The Head did reach the
LLM on the first run (`llama-server` logged `POST /v1/chat/completions … 200`, 788-token prompt,
with `reasoning-budget: budget=0, forcing immediately` / `forced sequence complete, done`) but no
token or terminal event ever reached the client; later runs produced no LLM call at all. Because
`StreamingCitationMatcher.onDone` returns early on blank text (`:128-130`), a stalled or empty
completion means the matcher never runs — which is why no `Citation scoring completed` line appears
for any summarize run, while the P4 calls logged one each.

Not diagnosed further (out of W4's scope) and logged to the inbox. It is worth flagging for W1/W5:
it sits exactly where 833 finding 3 (`reasoning_budget` default 0) and 833 W1 (no heartbeat, no
client-gone handling — `ChatController.java:169`) predict trouble, and it currently makes the
selection-summarize path unusable end to end, independently of citations.

## 9.10 Teardown

Both runs stopped via the dev tool (`portsClosed: true`, `devRunnerOk: true`). Verified afterwards:
no `llama-server.exe` process, no `dev-runner` node process, ports 56443 / 65527 / 5173 all closed,
`quick_health` reports `running: false`. Model files restored and verified **byte-identical by
SHA-256** — `a13ec391ca99f49886694e12d3e800521f36d4267d7d448c34421c541a2baf50`, matching the shipped
registry hash for `citation-model.onnx`.

## 9.11 Probe status after this round

| Probe | Status | Result |
|---|---|---|
| P1 truncation | DONE (offline) | ~1.9 KB real-text budget; window = 1500 chars; Q6 = YES |
| P2 cost grid | DONE (offline) | ~25 ms/pair linear; decision cell missed → degradation mandatory |
| P4 fallback prevalence | **DONE (live)** | Tier always installed; reachable only via partial install or the `:190-193` catch-all. "Everything grounded" **refuted**; the finding is a 0.0049 class separation |
| P3 hazard confirmation | **PARTIAL (live)** | Fabrication confirmed on the wire; severity **refuted** — under-marking, not wrong marks. Chunk-0-collision case unmeasured |
| P5 Q-021 quality | parked | after S1 + S2/S3 |
| P6 live cost cross-check | parked | needs the stall (§9.9) resolved to drive real summarize turns |

---

## 10. S1 live verification (2026-08-14, real Worker + real cross-encoder)

Run against PR #466's code. Stack: this worktree's dist (`distFrom`), own lease, pinned API port
57843, torn down and verified afterwards (§10.9). Corpus: `docs/explanation` (35 docs). Scorer: the
shipped `ms-marco-MiniLM-L-6-v2`, SHA-256 `a13ec391…` — the same registry model §9.10 used
(`Citation scorer enabled: threshold=0.5, maxSeqLen=512, deadline=2000ms`). Instrument:
`POST /api/knowledge/match-citations`, which S1 extends with an optional per-ref `passage_text`
plus `scorer` / `sentences_scored` / `scoring_incomplete` / `took_ms` / per-match `text_source`.
Without that field the seam had **no** live-exercisable caller at all: the only supplying path is
selection-summarize, which still stalls (§9.9).

### 10.1 Instrument correction (recorded, per interrogate-results)

Round 1 used the selection range §9.8 used, `[15000,17500)`, and reported near-zero scores for
sentences about pagination. That was a **wrong probe premise, not a finding**: at this revision of
`18-adapters-lucene-deep-dive.md` that range is mostly a Markdown table of field-type mappings —
the pagination prose lives at ~`[14050,15150)`. The cross-encoder was right to score those
sentences low. Round 1 also ran the chunk-lookup control while `chunkEmbeddingReady` was still
`false`, so a null lookup and a non-supporting lookup were indistinguishable. Round 2 fixed both:
real prose as the selection, and a **positive** lookup control that must score HIGH before any
negative lookup result is allowed to mean anything.

### 10.2 The seam works end to end

```
sentence about the TRUE selected passage, text SUPPLIED
  "Cursor-based pagination uses Lucene's searchAfter with an encoded cursor string."
     scorer=CROSS_ENCODER  text_source=SUPPLIED  sim=0.9984  scored=1/1  took=37ms
  "Requesting one more document than the limit determines hasMore without an extra query."
     scorer=CROSS_ENCODER  text_source=SUPPLIED  sim=0.7851  scored=1/1  took=33ms

off-topic controls vs the same supplied passage (saffron / penguins / sourdough)
     3 of 3 returned NO match at threshold 0.001
```

A separate smoke used `parent_doc_id: "probe-doc"` — a document **not in the index at all** — and
still scored 0.9997 from supplied text. A lookup could not have produced that: it is direct
evidence the supplied path bypasses the index entirely.

### 10.3 Provenance and coverage are on the wire, on every path

`scorer` was `CROSS_ENCODER` on every response; `text_source` was `SUPPLIED` when text was
supplied and `CHUNK_LOOKUP` when it was not; `sentences_scored` / `scoring_incomplete` tracked the
budget (§10.6). The positive lookup control returned `text_source=CHUNK_LOOKUP` at **0.9996**,
proving the fallback path is live rather than silently returning nothing.

### 10.4 P3 sharpened — the chunk-0 collision, re-measured with TRUE text available

Each sentence scored three ways: true passage supplied | document opening supplied | chunk-0
lookup (the pre-S1 path). The opening is the file's abstract (HNSW / SPLADE / hybrid fusion),
semantically disjoint from the pagination passage.

```
sentence                                    true      openingSupplied   chunk0Lookup
"Cursor-based pagination uses searchAfter"  0.9984        0.0647           0.0549
"Requesting one more document than limit"   0.7851        (none)           (none)
reverse: sentence about the OPENING, scored against the TRUE passage -> (none)
```

**Verdict.** With the true text supplied, supported claims clear 0.5 decisively (0.79–0.998) while
the wrong text sits at 0.05–0.06 — a ~15x gap, ordering CORRECT on both sentences. Two
consequences:

- **The pre-S1 path would have dropped both claims** (0.0549 / none against chunk 0). That is
  §9.8.2's silent under-marking, reproduced live — and S1 removes it: the same claims now mark.
- **The rev-2 "wrong marks" hazard did not reproduce here.** §9.8.2 had observed one INVERTED
  ordering (chunk 0 outscoring the true chunk); on this passage the ordering is correct both times
  and the collision never approaches 0.5. So the chunk-0 collision remains **unproven as a
  mark-minting hazard**, on top of being moot once true text is supplied. Honest limit: one
  document, one selection, two sentences.

### 10.5 The back-map holds live on a multi-window source

A 14,081-char passage (≈10 windows) whose supporting text sits in the **last** window, behind a
short unrelated source at position 0:

```
match: source_index=1  similarity=0.4682  text_source=SUPPLIED
       parent_doc_id == chunk_refs[1]  -> true
```

A window-ordinal pass-through would have reported ~9. It reported **1**. This is the §5.4 contract
observed on the wire, not only in the property test.

**Side finding — window-boundary dilution.** The same sentence scored 0.9984 against the bare
1,100-char passage but **0.4682** once that text was the tail of a 1500-char window shared with
filler. A claim whose support straddles or shares a window can fall *below* the 0.5 threshold. The
window size is a correctness floor for truncation (§9 P1c) but it is also, unavoidably, a
score-dilution knob. Not addressed by S1; relevant to P5 and to FW-009.

### 10.6 P6 — live cost cross-check, and the decision cell now PASSES

Idle machine (a sibling worktree's Gradle suite overlapped an earlier round; that round was
discarded and the grid re-run at CPU ~0%). Worker budget 2000 ms.

```
 L(chars) | N | S  | windows | uncapped-pairs | took_ms | scored | incomplete
     2000 | 1 |  5 |       2 |             10 |     212 |  5/5   | false
     2000 | 1 | 15 |       2 |             30 |     650 | 15/15  | false
     2000 | 1 | 40 |       2 |             80 |    1740 | 40/40  | false
     2000 | 5 |  5 |      10 |             50 |    1150 |  5/5   | false
     2000 | 5 | 15 |      10 |            150 |    1692 | 15/15  | false
    10000 | 1 |  5 |       7 |             35 |     769 |  5/5   | false
    10000 | 1 | 15 |       7 |            105 |    1723 | 15/15  | false   <- decision cell
    10000 | 1 | 40 |       7 |            280 |    1762 | 40/40  | false
    10000 | 5 |  5 |      35 |            175 |    1922 |  5/5   | false
    10000 | 5 | 40 |      35 |           1400 |    1776 | 40/40  | false
    50000 | 1 | 15 |      34 |            510 |    1735 | 15/15  | false
    50000 | 5 | 15 |     170 |           2550 |    1763 | 15/15  | false
   200000 | 1 | 15 |     134 |           2010 |    1766 | 15/15  | false
```

Against §9.5's offline grid (same cells, pre-S1):

| cell | offline (pre-S1) | live (post-S1) |
|---|---|---|
| **10000/1/15 (the pre-registered decision cell)** | 2045 ms, **12/15 MISS** | 1723 ms, **15/15 OK** |
| 2000/5/15 | 2166 ms, 9/15 MISS | 1692 ms, 15/15 OK |
| 10000/5/5 | 2556 ms, 3/5 MISS | 1922 ms, 5/5 OK |
| 50000/1/15 | 2566 ms, 3/15 MISS | 1735 ms, 15/15 OK |
| 50000/5/15 | 4482 ms, 1/15 MISS | 1763 ms, 15/15 OK |
| 200000/1/15 | 3357 ms, 1/15 MISS | 1766 ms, 15/15 OK |
| 200000/5/5 | **49,770 ms**, 1/5 MISS | no live counterpart above ~1.9 s |

**§3.2's pre-registered rule is answered: the cell that fired offline now passes.** Every cell
completes inside the budget with FULL sentence coverage, and the 49.8 s catastrophe (§3.4: work
the Head had already abandoned) has no live counterpart. The mechanism is confirmed from the
Worker's own log, not inferred from timings:

```
Citation admission control: scoring  5 of  34 windows (15 sentences, 2000ms budget)
Citation admission control: scoring  5 of 170 windows (15 sentences, 2000ms budget)
Citation admission control: scoring  5 of 134 windows (15 sentences, 2000ms budget)
Citation admission control: scoring 16 of  35 windows ( 5 sentences, 2000ms budget)
Citation admission control: scoring  2 of  35 windows (40 sentences, 2000ms budget)
Citation scoring deadline exceeded after 11 of 15 sentences        (contended round)
```

`floor(2000/25)=80` pairs, divided by the sentence count — 80/15 = 5, 80/5 = 16, 80/40 = 2 — the
§3.5 cap arithmetic, observed live.

### 10.7 Two honesty gaps the live leg exposed (NEW — not in the design)

Both follow from admission control preserving *sentence* coverage by cutting *windows*, and
neither is visible to a caller.

1. **`scoring_incomplete: false` can mean "I scored every sentence against 4% of your text."**
   At 200 KB the response reports `sentences_scored = 15/15, scoring_incomplete = false` while the
   Worker scored **5 of 134 windows**. §3.6 made `sentences_scored` the honest denominator for the
   *sentence* axis, and it is — but windowing added a second coverage axis that the wire does not
   carry. §3.3 says whole-document verification is out of scope; the live surface now reports it
   as complete. Candidate fix: `windows_scored` / `windows_considered` on the response, and a
   coverage state that is a function of both axes.
2. **A source can be starved of every window and still look merely unsupported.** At
   `10000/5/40` the cap was 2 windows across 5 sources: three sources received no window at all.
   The per-source round-robin guarantees representation only *while slots remain*. Those sources
   are uncitable for reasons of budget, reported identically to "nothing in this source supports
   the claim."

Both are S2/S3 material — the FE coverage line (§3.6) is where they would surface — and neither
changes S1's correctness claim.

### 10.8 Not exercised live, stated plainly

- **The cosine fallback arm.** The 4 GB embedding blob was not provisioned into this worktree, so
  chunk *content* indexed but the cosine producer never ran. Both branches are covered by the
  parameterized unit tests; the live leg is cross-encoder only.
- **`INVALID_ARGUMENT` on a length mismatch.** The REST instrument builds all three arrays from
  one list and cannot emit a mismatched request; covered by `GrpcSearchServicePassageTextsRejectTest`.
- **The 200000 × 5 cell** returned HTTP 413 — a ~1 MB REST body limit on the probe instrument, not
  a Worker or seam limit. The 200 KB single-source cell did go through (1766 ms, 15/15).
- **The selection-summarize route** still stalls after `rag.citations` (§9.9), so the seam was
  driven through `documents.matchCitations` directly, as §9.8.2 did.

### 10.9 Teardown

Stopped via the dev tool (`portsClosed: true`, `devRunnerOk: true`, exit 0). Verified afterwards:
no `llama-server.exe`, no `IndexerWorker`, no `HeadlessApp`, no `dev-runner` process; ports 57843
and 5173 closed; `quick_health` reports `running: false`; no JustSearch process on the GPU. Model
files were read-only sources — the worktree copy of `citation-scorer` proved redundant, since the
dev-runner resolves models from the repo root (`F:\justsearch-public\models\onnx\citation-scorer`,
SHA-256 verified identical).

### 10.10 Probe status after this round

| Probe | Status | Result |
|---|---|---|
| P1 truncation | DONE (offline) | unchanged; window = 1500 chars |
| P2 cost grid | DONE (offline) | superseded live by §10.6 |
| P3 hazard confirmation | **DONE (live, S1)** | Pre-S1 under-marking reproduced (0.0549 at chunk 0) and **removed** (0.9984 with true text). Wrong-mark hazard did **not** reproduce: ordering correct, collision ≤0.065 |
| P4 fallback prevalence | DONE (live) | unchanged |
| P6 live cost cross-check | **DONE (live, S1)** | Decision cell PASSES (1723 ms, 15/15); 49.8 s catastrophe gone; admission arithmetic confirmed in the Worker log. CPU-contention variant (llama-server running) still unmeasured |
| P5 Q-021 quality | parked | after S2/S3; §10.5's window dilution and §10.7's coverage gaps are inputs to it |

---

# S2S3-amendment — coverage honesty, and the S2/S3 implementation handoff

```
added: 2026-08-18 (post-S1, PR #466 on main)
absorbs: §10.7 gaps 1 and 2 + a third gap found while writing this (§S2S3-A.0 item 3)
status: DESIGN — amends §2 (staging), §3.6 (coverage), §4 (provenance), §5 (tests)
```

## S2S3-A.0 Why this amendment exists

S1 shipped admission control, which preserves **sentence** coverage by cutting **windows**. That
trade was correct — §10.6 turned the pre-registered decision cell from MISS into PASS — but it
created a coverage axis the wire does not carry, and the live leg caught two consequences (§10.7).
Writing the fix surfaced a third, which is the enabling one:

1. **`scoring_incomplete: false` can mean "every sentence scored against 4% of your text."**
   (§10.7 gap 1.)
2. **A source can be starved of every window and read as merely unsupported.** (§10.7 gap 2.)
3. **S1's honesty fields stop at the Head.** `scorer` and `sentences_scored` reach
   `CitationMatchResult` (`DocumentService.java:413-433`) but `toCitationMatchPayload`
   (`StreamingCitationMatcher.java:309-326`) still emits only
   `sentencesTotal / sentencesMatched / tookMs / matches[]`. Nothing S1 added is visible to a
   browser. So §4's FE gate ("only `CROSS_ENCODER` may set `verifiedScore`") and §3.6's coverage
   honesty are **currently unimplementable FE-side** — not deferred, unimplementable. Found by
   reading the payload builder, not by a test, because no test asserts the Head-to-FE hop carries
   them.

Gap 3 is why this amendment is a precondition of S2/S3 rather than a follow-up: without it, S2/S3
would wire `SummarizeView.verifiedScore` to a payload that cannot say which producer wrote the
score.

## S2S3-A.1 Gaps 1 and 2 are one fact — per-source coverage

Both questions ("how much of my text was examined?" and "was *this* source examined at all?") are
answered by the same data, and it already exists: `PassageWindows.Prepared` carries `windowTexts`
(kept), `windowToSource`, and `windowsConsidered` (`PassageWindows.java:68-74`). Nothing new must
be computed — only carried.

**Decision: per-source coverage is the single authority; the aggregate is derived, never
transmitted.** Emitting both a response-level ratio and per-source counts would put one fact under
two authorities — the exact shape this document criticises in F-048 (two producers, one field) and
833 finding 6 (two authorities for one degradation). The response-level answer is
`sum(windows_scored) / sum(windows_considered)`, computed by whoever needs it.

### Wire (`indexing.proto`, additive)

```proto
// Tempdoc 836 S2S3-A.1 — the TEXT-coverage axis. Admission control (836 section 3.5) preserves
// sentence coverage by cutting windows, so `sentences_scored == sentences_total` can hold while
// most of the caller's text was never looked at. This is per source, not a response-level ratio:
// an aggregate cannot express "source 3 got no window at all", which reads identically to
// "source 3 supports nothing" without it.
message SourceCoverage {
  int32 source_index = 1;        // position in the request arrays (the 836 section 5.4 contract)
  int32 windows_considered = 2;  // windows this source's text produced, before admission
  int32 windows_scored = 3;      // how many survived admission and were actually scored
}

message MatchCitationsResponse {
  // ... fields 1-7 unchanged ...
  repeated SourceCoverage source_coverage = 8;
}
```

The four states, and the distinction gap 2 asks for:

| `considered` | `scored` | meaning | may a caller say "unsupported"? |
|---|---|---|---|
| 0 | 0 | no text at all — blank supply and a failed lookup | **no** — unverifiable |
| >0 | 0 | **starved**: text existed, budget gave it no window | **no** — never examined |
| >0 | `< considered` | partially examined | only about the part examined |
| >0 | `== considered` | fully examined | **yes** |

`windows_considered > 0 && windows_scored == 0` is the gap-2 discriminator. It is a state the
Worker already knows — `selectWindows`'s javadoc names the risk explicitly ("every source that has
any text keeps at least one window **while slots remain** ... a source silently losing all
representation cannot be cited at all, which would read as 'not grounded' rather than 'not
scored'") — and this field is what makes the Worker's own caveat legible to a caller.

### Head record

`CitationMatchResult` gains `List<SourceCoverage> sourceCoverage` with
`record SourceCoverage(int sourceIndex, int windowsConsidered, int windowsScored)` plus derived
predicates — derived, so no second authority:

```java
boolean textCoverageComplete();   // every source with considered>0 has scored == considered
List<Integer> starvedSources();   // considered>0 && scored==0
```

`scoringIncomplete()` (`:430-432`) stays exactly as it is — it is the **sentence** axis and remains
correct. The two axes are reported separately and never blended into one ratio.

### SSE payload (closes gap 3)

`toCitationMatchPayload` gains `scorer`, `sentencesScored`, and `sourceCoverage`, and each match
entry gains `textSource`. **No `EVENT_SCHEMA` change and no codegen change**: the shape-handler
codegen generates handler *names* from event names with `payload: unknown`
(`gen-shape-handlers.mjs` header), so adding payload fields costs nothing in the codegen chain.
That is worth stating plainly — it means this amendment adds zero surface to S2/S3's regen burden.

## S2S3-A.2 Consumer semantics — what the honest frame line says

Today `groundingCoverage` (`evidenceProjection.ts:368-384`) computes
`total = Math.max(countSentences(answerText), cited)` and labels
`Grounded - ${cited} of ${total} sentences`, or `Not grounded` when nothing rendered. Two things
are wrong with that under S1's behaviour, and §3.6 only fixed the first in design:

- A truncated pass lowers `cited`, so the line **understates grounding** — it attributes a budget
  to the evidence.
- `Not grounded` is an **evidence verdict**. When nothing was scored, the truthful statement is
  that verification did not run.

**The projection carries both axes as flags, and ONE label function reads them.** Not a precedence
chain that collapses them early — a set, so the label can evolve without a second classifier
appearing (the §3.6 fork lesson applied to its own fix):

```ts
interface CoverageHonesty {
  readonly sentencesScored: number;
  readonly sentencesTotal: number;
  readonly sentencesIncomplete: boolean;   // sentencesScored < sentencesTotal
  readonly textIncomplete: boolean;        // any source 0 < scored < considered
  readonly unexaminedSources: number;      // count of starved sources (considered>0, scored==0)
}
```

Label rules — the only place the two axes meet:

| condition | line |
|---|---|
| complete, `cited > 0` | `Grounded - N of M sentences` (unchanged) |
| `unexaminedSources > 0` | `Grounded - N of M sentences - K sources not examined` |
| `textIncomplete` | `Grounded - N of M sentences - part of the text examined` |
| `sentencesIncomplete` | `Grounded - N of M sentences - S of T sentences scored` |
| `cited == 0` **and** any incompleteness | **`Not scored`** |
| `cited == 0` and coverage complete | `Not grounded` (unchanged — a real verdict) |

**The `Not scored` / `Not grounded` split is the load-bearing change.** It composes exactly with
F-049: F-049 made an unresolvable claim mint nothing and let coverage degrade honestly "because
coverage counts what renders". This amendment adds the missing half — coverage must also say *why*
it degraded, evidence or budget. Rendering "Not grounded" over a pass that never ran is the same
class of confident wrongness this whole document exists to remove, pointed at the answer instead of
at a sentence.

Deliberately **not** doing: a percentage of text examined in the primary line. "4% of your text"
invites the reader to treat coverage as a quality score. The count of unexamined sources is
actionable; a ratio is not. Keep the ratio available on the projection for a detail view.

## S2S3-A.3 Gap 2 in the sources panel — a third source state

`evidenceProjection.ts:389-392` documents today's binary: `cited: false` means "the source was
retrieved but never grounded a sentence, so it MUST NOT read 'high confidence'". A starved source
would land there and read as *examined and found wanting*. It becomes three states:

- `cited` — grounded at least one claim.
- `examined-uncited` — scored, supported nothing. Today's `cited: false`, meaning preserved.
- `unexamined` — `windows_considered > 0 && windows_scored == 0` (starved), or `considered == 0`
  (no text). Must not carry the "retrieved but never grounded" copy.

`unexamined` is a *budget* fact, so it must not feed any grounding tier or count — the same
containment `Claim.lexicalScore` gets under F-048: present because it is true, never a tier input.

## S2S3-A.4 Composition with S2/S3's existing contract

- **`DocAccess` (S3) is the first consumer that will hit both gaps in the wild.** It injects up to
  `MAX_CONTENT_CHARS = 200_000` as ONE source — precisely §10.7's 200 KB cell, where the Worker
  scored 5 of 134 windows and reported complete. So the coverage fields must land **with**
  `DocAccess`'s `ATTR_VERIFICATION_SOURCES` population, not after it. Ordering inside S2+S3:
  wire+payload (A.1) then `DocAccess` then FE consumption. Publishing the doc path first without
  the coverage fields would ship the exact misreport §10.7 caught.
- **`EVENT_SCHEMA` + codegen**: unchanged from §2 — one string added to `SummarizeShape:46-47`,
  fixture regen, `gen-shape-handlers.mjs`, `check-shape-handler-regen.mjs`. Payload fields ride
  free (A.1).
- **`SummarizeView.verifiedScore`**: gains the §4 gate, now implementable. Same for
  `UnifiedChatView`, which has **two** write sites — `:6044` (live) and `:5304` (the persisted
  `claimMatches` replay). Both need the gate, or a reloaded conversation renders under different
  rules from the live one, which is the render-path divergence 561 P-A exists to prevent.
- **F-049 drop-the-claim**: untouched. Nothing here promotes a claim; the coverage fields only
  explain what an absent claim means.

## S2S3-A.5 P5 calibration — S2/S3 must not promise marks

Three measured results say the summarize tier may show few or no marks even when everything is
correct:

- Even against the **correct** chunk, probe sentences did not clear 0.5 (max 0.3079, §9.8.2).
- **Window-boundary dilution**: the same sentence scored 0.9984 against a bare 1,100-char passage
  and **0.4682** once that text was the tail of a 1500-char window shared with filler (§10.5). The
  window size is a correctness floor for truncation *and* a score-dilution knob.
- The threshold is uncalibrated on real content (FW-009), and summary prose is a content type no
  calibration has seen (Q-021); F-050 measured that compression starves per-sentence matching.

**So S2/S3's acceptance criterion is that the chain is correct and honest — not that marks
appear.** An implementer must not tune the threshold, the window size, or the tier boundaries to
make marks show up; that is measurement work belonging to P5, and doing it inside S2/S3 would be
fitting a constant to a demo.

**No default-off flag** — §2's decision stands, and the P5 warning does not reopen it. The reason
is now stronger, not weaker: if P5 shows few marks, the surface renders markless *with an honest
coverage line* (A.2), which is a correct shipped state rather than a regression to hide. Mis-marks
— the thing a flag would exist to contain — are unconstructible by F-049 + §5.4. P5 therefore gates
**further investment** (calibration, window sizing), not shipping.

## S2S3-A.6 Invariant tests this amendment adds

Existing §5 shapes stand. New ones, each naming the wrong output it forbids:

**A.6a — complete-looking response over partial text.** One 200 KB source, 15 sentences, real
budget: assert `sentencesScored == sentencesTotal` **and** `windowsScored < windowsConsidered`
**and** the projection does NOT report complete. *Forbids:* §10.7 gap 1 verbatim. Must fail before
the fix — a test that passes pre-fix is the §5.6 mistake repeated.

**A.6b — starved source is distinguishable.** Cap forcing starvation across at least 3 sources: the
starved source has `considered > 0, scored == 0`, appears in no match, and projects as
`unexamined`, not `examined-uncited`. *Forbids:* §10.7 gap 2.

**A.6c — no false incompleteness.** A small request fully within budget reports
`scored == considered` for every source and the unchanged `Grounded - N of M sentences` line.
*Forbids:* a fix that makes every answer look partial.

**A.6d — the Head-to-FE hop carries the honesty fields.** `toCitationMatchPayload` output contains
`scorer`, `sentencesScored`, `sourceCoverage`, and per-match `textSource`. *Forbids:* gap 3
recurring — no test asserted this hop, which is why S1's fields stopped at the Head.

**A.6e — `Not scored` is not `Not grounded`.** Zero cited claims with incomplete coverage renders
`Not scored`; zero cited claims with complete coverage renders `Not grounded`. *Forbids:* asserting
an evidence verdict over a pass that never ran.

**A.6f — replay parity.** The same `claimMatches` payload through `UnifiedChatView:5304`
(persisted) and `:6044` (live) yields identical marks and identical coverage line. *Forbids:* the
561 P-A divergence re-entering through the new gate.

## S2S3-A.7 Implementation handoff — line map against `origin/main`

Ordered by dependency. Line numbers are `origin/main` at the time of writing; re-verify before
editing (`tempdocs-are-dated-history` applies to line maps most of all).

### Stage 1 — carry the coverage fact (backend)

| # | File | Line | Change |
|---|---|---|---|
| 1 | `modules/ipc-common/src/main/proto/indexing.proto` | 533-547 | add `message SourceCoverage` + `repeated SourceCoverage source_coverage = 8;` to `MatchCitationsResponse` |
| 2 | `.../indexerworker/services/PassageWindows.java` | 68-74 | `Prepared` gains per-source counts: `windowsConsideredBySource`, with scored-per-source derivable from `windowToSource` — compute both in `prepare` (`:119-183`) |
| 3 | `.../indexerworker/services/CitationMatchOps.java` | 216-232 | build the `SourceCoverage` list from `Prepared` after admission; set it on the response in **both** producer branches (CE ~`:316`, cosine ~`:313`) and on the early-return paths |
| 4 | `.../indexerworker/services/GrpcSearchService.java` | 799-822 | pass through (no logic) |
| 5 | `modules/app-api/.../DocumentService.java` | 413-433 | `CitationMatchResult` gains `List<SourceCoverage>`; add the `SourceCoverage` record + `textCoverageComplete()` / `starvedSources()` derived predicates |
| 6 | `modules/app-services/.../worker/RemoteDocumentService.java` | 523, 554-563 | map `resp.getSourceCoverageList()`; the two `List.of(), 0,0,0,0, NONE` early returns need the empty list |

### Stage 2 — close gap 3 (Head to browser)

| # | File | Line | Change |
|---|---|---|---|
| 7 | `.../conversation/spi/StreamingCitationMatcher.java` | 309-326 | `toCitationMatchPayload` emits `scorer`, `sentencesScored`, `sourceCoverage`; per-entry `textSource` |
| 8 | `modules/ui-web/src/api/streams.ts` | 33-38 | `CitationMatchesPayload` gains `scorer`, `sentencesScored`, `sourceCoverage`; `CitationMatch` (`:20-31`) gains `textSource` |

No codegen step — payload fields are `unknown` to the shape-handler generator (A.1).

### Stage 3 — DocAccess (the S3 half; must follow stages 1-2)

| # | File | Line | Change |
|---|---|---|---|
| 9 | `.../conversation/spi/DocAccess.java` | 98 | replace `InjectorResult.messagesOnly(...)` with citations + verification sources: stash `ATTR_CITATIONS` **and** `ATTR_VERIFICATION_SOURCES` (`RAGContext.java:78`) carrying the injected `truncated` string, and emit `rag.citations` — mirror `SelectionContextInjector.stashCitation` (`:401-404`) and `citationsEvent` (`:406-415`) |
| 10 | `.../conversation/spi/DocAccess.java` | 51 | `MAX_CONTENT_CHARS = 200_000` is unchanged, but §3.3 says whole-document verification is out of envelope — the coverage fields are what make that honest rather than silent |
| 11 | — | — | **§8.4 decision required**: what `chunkIndex` does `DocAccess`'s citation carry? Model the absence; do not default to `0` (that is the residue that made this defect possible) |

### Stage 4 — summarize FE chain (the S2 half)

| # | File | Line | Change |
|---|---|---|---|
| 12 | `.../conversation/shapes/SummarizeShape.java` | 46-47 | `EVENT_SCHEMA` += `"rag.citation_matches"` |
| 13 | `modules/app-services/src/test/.../ConversationShapeFixtureGenTest.java` | — | regenerate `scripts/codegen/shapes.fixture.json` |
| 14 | `scripts/codegen/gen-shape-handlers.mjs` | — | run; regenerates `modules/ui-web/src/api/generated/shape-handlers/core-summarize.ts` (gains `onRagCitationMatches`). Gate: `scripts/ci/check-shape-handler-regen.mjs` |
| 15 | `modules/ui-web/src/shell-v0/views/SummarizeView.ts` | 343-372 | add `onRagCitationMatches`; stop hardcoding `verifiedScore: null` (`:366`); apply the §4 scorer gate |

### Stage 5 — coverage semantics (FE projection)

| # | File | Line | Change |
|---|---|---|---|
| 16 | `.../components/chat/evidenceProjection.ts` | 368-384 | `groundingCoverage` takes the coverage facts; implement the A.2 label table; keep `groundingClass` as the ONE tier authority |
| 17 | `.../components/chat/evidenceProjection.ts` | 360-361 | retire `countSentences` as the denominator — the backend's `BreakIterator` count (`CitationMatchOps.java:290-305`) is the authority (§3.6 fork) |
| 18 | `.../components/chat/evidenceProjection.ts` | 389-392 | source state becomes three-valued (A.3) |
| 19 | `.../components/chat/citationResolve.ts` | 39, 49-52 | existing gates unchanged; add the §4 producer gate — a non-`CROSS_ENCODER` score may not populate `Citation.similarity` |
| 20 | `.../views/UnifiedChatView.ts` | 6044 **and** 5304 | apply the scorer gate at **both** write sites (live + persisted replay) |

### Verification

`./gradlew.bat build -x test` plus `:modules:worker-services:test :modules:app-services:test
:modules:app-api:test`; `cd modules/ui-web && npm run typecheck && npm run test:unit:run`;
`node scripts/ci/check-shape-handler-regen.mjs`. Live leg: the seam has no supplying caller through
the UI while selection-summarize stalls (§9.9), so drive `DocAccess` via a `core.summarize` dispatch
with `docId` (no selection) — that path does not depend on the stall.

## S2S3-A.8 Open questions

1. **Does the stall (§9.9) block S2/S3's live verification?** The `docId` path should avoid it, but
   that is an inference from where the stall appears, not a measurement. Verify early — if the
   `docId` path stalls too, S2/S3 has no live leg at all and that changes its acceptance bar.
2. **Should a starved source be retried outside the turn?** A budget-starved source is knowable and
   re-scorable later; nothing here proposes it, and a background pass would need its own
   evidence-record story (561 P-A). Named so it is a decision, not an omission.
3. **Is `unexamined` a per-turn or per-source-lifetime fact?** If the same source is examined in a
   later turn, does the earlier turn's record update? Current answer: no — the evidence record is
   per turn — but the sources panel spans turns, so the projection needs a rule.
4. **§8.4 remains open and is now blocking stage 3** (`DocAccess`'s `chunkIndex`).
5. **Does the A.2 label survive a narrow viewport?** Four variants, the longest being
   `Grounded - N of M sentences - K sources not examined`. Presentation-authority work needs the
   measured UX audit (`ux-audit-closure`), not an eyeball.

---

## S2S3-IMPL — implementation log (S2+S3, 2026-08-18)

```
implements: S2S3-A.1 … A.7 (all five stages, all 20 edit sites)
branch: citation-coverage-s2-s3
base: origin/main @ 010d59f8
status: IMPLEMENTED — offline verification complete; the LIVE leg is PENDING (S2S3-IMPL.6)
```

### IMPL.1 What landed, by stage

**Stage 1 — carry the coverage fact.**

| # | Site | What |
|---|---|---|
| 1 | `indexing.proto:533-570` | `message SourceCoverage` + `repeated SourceCoverage source_coverage = 8` on `MatchCitationsResponse`, with the four-state table in the comment |
| 2 | `PassageWindows.java:68-150` | `Prepared` gains `windowsConsideredBySource`; `windowsScoredAt(i)` is DERIVED from `windowToSource` (the admitted set) rather than stored beside it, so the two counts cannot drift |
| 3 | `CitationMatchOps.java:266, 329, 341-362` | `sourceCoverage(prepared, scored)` set on the CE branch, the cosine branch, and the post-preparation early returns |
| 4 | `GrpcSearchService.java` | **no edit needed** — `matchCitations` already forwards the response object whole; A.7's "pass through (no logic)" is satisfied by construction |
| 5 | `DocumentService.java:431-505` (+ `CHUNK_INDEX_ABSENT` at `:273`) | `record SourceCoverage(sourceIndex, windowsConsidered, windowsScored)` with `starved()` / `noText()` / `fullyExamined()`, plus `CitationMatchResult.textCoverageComplete()` / `starvedSources()` — all derived, no second authority |
| 6 | `RemoteDocumentService.java:523, 554-577` | maps `resp.getSourceCoverageList()` verbatim; the two early returns carry `List.of()` |

**Stage 2 — close gap 3.** `StreamingCitationMatcher.toCitationMatchPayload` (`:326-365`) emits `scorer`,
`sentencesScored`, `sourceCoverage`, and per-match `textSource`. `streams.ts:24-80` follows
(`CitationMatch.textSource?`, `SourceCoverage`, `CitationMatchesPayload.scorer/sentencesScored/sourceCoverage`).

**Stage 3 — `DocAccess`.** `DocAccess.java:71-160` stashes `ATTR_VERIFICATION_SOURCES` +
`ATTR_CITATIONS` + `ATTR_USED_RAG` and emits `rag.citations`, supplying the injected `truncated`
string as the verification text.

**Stage 4 — summarize FE chain.** `SummarizeShape.EVENT_SCHEMA` += `rag.citation_matches`;
`shapes.fixture.json` regenerated via `-Dupdate.shapes.fixture=true`; `gen-shape-handlers.mjs` run
(`core-summarize.ts` gains `onRagCitationMatches`); `check-shape-handler-regen` green.
`SummarizeView.ts` gains the handler with the §4 gate and stops being verified-score-less by
construction.

**Stage 5 — coverage semantics.** `evidenceProjection.ts` gains `CoverageHonesty` +
`coverageHonesty()` (flags as a SET), the A.2 label table inside ONE `coverageLabel` function,
`SourceExamination` three-valued state on `sourceGrounding`, and the `isVerifiedProducer` gate.
`citationResolve.ts:45` adds the producer gate beside the existing score gate. `UnifiedChatView.ts`
applies the gate at BOTH write sites (`:6158` live, `:5386` persisted replay). `CitationsPanel.ts`
gives unexamined sources their own group.

### IMPL.2 Three decisions the handoff left open

**§8.4 — what `chunkIndex` does `DocAccess` carry (A.7 item 11).** The absence is already modelled
in this repo: `AgentSession.DOC_LEVEL_SENTINEL = -1` and the frontend's `DOC_LEVEL_CHUNK_SENTINEL =
-1` both mean "document-level, no chunk identity". So the answer is not a new representation but the
existing one — `ContextCitation.CHUNK_INDEX_ABSENT = -1`.

Making it *usable* required a fix the handoff did not anticipate: `ContextCitation`'s compact
constructor clamped `chunkIndex = Math.max(0, chunkIndex)`, which **destroyed the sentinel** —
`-1` became `0`. That was already live on the agent path (`AgentCitationResolver.java:71` passes a
doc-level source's `-1` straight into a `ContextCitation`), so an agent's document-level source was
silently being verified against chunk 0 of its document: the 836 defect, in a second place, unnoticed.
The clamp is now `Math.max(CHUNK_INDEX_ABSENT, chunkIndex)`, and `CitationMatchOps.prepareWindows`
refuses to look up a negative ordinal rather than searching for "chunk -1".

**A zero-match result now emits.** `StreamingCitationMatcher.onDone` returned early on
`matches().isEmpty()`. That is precisely the case A.2 is about — "nothing was examined" and
"everything was examined and supports nothing" are the same empty match list — so suppressing the
event left the consumer no choice but to render an evidence verdict over a pass that may never have
run. It now emits whenever the result is non-null. No existing test asserted the suppression.

**Where the label renders.** A.2 treats `groundingCoverage`'s `label` as the user-facing line. It was
not: `UnifiedChatView.renderGroundingBadge` re-composed `Grounded · N of M sentences` inline, so the
projection's label had no render site at all — the same one-fact-two-authorities shape this
amendment exists to close, one level up. The badge now renders `cov.label`, and `renderMessage`
passes a `coverageNote` into the existing answer-frame receipt line. The note renders ONLY when the
run reported an incomplete pass (`coverageNote` returns `null` otherwise): a permanent
"…and all of it was examined" line is noise, and noise is what gets skipped.

### IMPL.3 Wording

The A.2 table renders with the shipped `·` separator, not the `-` of the doc's plain-text table —
A.2 marks the base line "(unchanged)", and unchanged means the shipped `Grounded · N of M sentences`.
Variants, verbatim otherwise: `· K sources not examined` (singular "source" at K=1),
`· part of the text examined`, `· S of T sentences scored`, `Not scored`, `Not grounded`.
The measured UX audit (`ux-audit-closure`, A.8 question 5 — four variants, narrow viewport) is a
**named follow-up, not done here**.

### IMPL.4 The producer gate's one deliberate allowance

`isVerifiedProducer` admits an ABSENT `scorer`. The field is emitted on every response since S1, so
absence means a record persisted *before* the field existed — treating those as unverified would
strip marks from every historical conversation, an evidence claim about data we have no evidence
about. A KNOWN non-cross-encoder producer fails closed. Pinned by
`UnifiedChatView.test.ts` ("a record written before the producer field existed keeps its marks").

### IMPL.5 Verification — what was run, and the fail-first evidence

**A.6 tests.** a/b/c + the no-text and no-producer cases:
`CitationMatchOpsCoverageTest` (5 tests, worker-services). d:
`StreamingCitationMatcherPayloadTest` (4, app-services). e + the A.6a/A.6c projection halves +
the A.6b source-state half + the gate: `evidenceProjection.coverage.test.ts` (20, ui-web) and 3
added to `citationResolve.test.ts` for the resolve-site gate. f:
`UnifiedChatView.test.ts` (4 new). Plus `DocAccessCitationTest` (6) for stage 3 and
`CitationMatchesDeclarationTest` (2) for §5.11.

**A.6d fails before the fix — recorded.** Reverting `toCitationMatchPayload` to its pre-S2/S3 shape
(dropping `scorer`, `sentencesScored`, `sourceCoverage`, per-match `textSource`) fails 3 of the 4
payload tests. The 4th (live payload == persisted payload) still passes, correctly: it is a parity
test, not a field test, and both sides are the same map either way.

**A.6a fails before the fix — recorded.** Mutating `sourceCoverage(...)` to report
`windowsScored := windowsConsidered` — the dishonest "everything was examined" report, i.e. exactly
what §10.7 gap 1 describes — fails BOTH `completeSentencesOverPartialText` (A.6a) and
`starvedSourceIsDistinguishable` (A.6b, the gap-2 discriminator). Restored; green.

**Two-write-site gate, mutation-probed both ways.** Removing the producer gate from the LIVE site
only fails `A.6f — a cosine-fallback payload mints no verified score on EITHER path`; removing it
from the PERSISTED-REPLAY site only fails the same test. Restored; 199/199 green. That is the
551/561 P-A property under test: neither half alone satisfies it.

**Suites.** `./gradlew.bat build -x test -PskipWebBuild=true` green; `./gradlew.bat test` green
(full unit suite, 4m17s). `npm run typecheck` clean; `npm run test:unit:run` **422 files / 5184
tests, 0 failures** (baseline before this work: 421 / 5157).

**Gates.** The full `ui-web-gates` recipe + shell-v0 subsets + the kernel set
(`ambient-purity`, `style-literal-ratchet`, `atom-fork-ratchet`, `modality-contract`,
`transient-arbitration`, `modal-arbitration`) + `execution-surface` / `operation-surface`: green.
Three gates are RED and were RED on `origin/main` before this branch:
`check-theme-token-closure` and `check-accent-as-text` (both in `expected-state.v1.json`), and
`check-controls-a11y` (**not** in expected-state — verified pre-existing by stashing this branch's
`UnifiedChatView.ts` and re-running; logged to the observations inbox).

### IMPL.5b Post-implementation critical-analysis pass — two findings, both fixed

Run per `critical-analysis-pass` after the suites were green.

1. **The resolver's producer gate was passing for the wrong reason.** A.7 item 19 asks for the §4
   gate in `claimsToCitations`, and the test that appeared to cover it asserted `resolved === []`
   for a cosine payload — but the write sites had already refused to set a verified score, so the
   EXISTING score gate is what dropped the claim. The producer gate could have been absent and the
   test would still pass. Fixed by testing the resolver directly with a fully-formed claim (numeric
   score, valid ref, cosine producer); mutation-probed — removing the gate fails 2 tests.
2. **`SummarizeView` stored a coverage it never rendered.** The handler set `this.coverage` and
   nothing read it — substrate without a consumer, on the very surface S2 exists to make honest.
   Whole-document summarize is §3.3's out-of-envelope case, so a markless answer there is usually a
   budget fact, and saying nothing leaves the reader to conclude the document supports none of it.
   The view now renders the same `coverageNote` the chat window does, through the same projection.

### IMPL.6 The live leg — PENDING, with the caveat A.8 asked for

Not run. Per A.7's Verification note the selection-summarize path still stalls (§9.9), so the
route would be a `core.summarize` dispatch carrying `docId` and NO selection — which now reaches
`DocAccess`'s new population. **That route is an inference from where the stall appears, not a
measurement** (A.8 question 1 is still open). If the `docId` path stalls too, S2/S3 has no live leg
at all and its acceptance bar is the offline one recorded above.

What a live run would add that the offline evidence does not: that a real `MatchCitationsResponse`
over a real 200 KB document produces the coverage numbers the §10.7 cell measured, and that the
summarize surface renders the resulting line. The seam itself was already exercised live in §10.

### IMPL.7 P5 — no thresholds were touched

No threshold, window size, or tier boundary was changed. Per A.5 the acceptance criterion is that
the chain is correct and honest, not that marks appear; `PassageWindows.WINDOW_CHARS`,
`DEFAULT_SIMILARITY_THRESHOLD`, `UNIT_COST_MS` and the grounding tiers are untouched, and no test
here asserts that a mark appears.

### IMPL.8 Named follow-ups (logged to the inbox, not done here)

- The measured UX audit of the four coverage-label variants (A.8 question 5).
- `SelectionContextInjector.citationsEvent` still hardcodes `chunkIndex: 0` in its SSE map; §8.4's
  answer applies there too, but S1 shipped it and changing it moves `sourcesAreChunkPrecise` for
  selection sources — out of this slice's scope.
- `search-v2/records.ts:435` `groundedSentencesLabel` is a THIRD phrasing of the coverage line,
  independent of `groundingCoverage`.
- The agent path (`renderGroundingBadge`) reads the label authority but has no coverage facts to
  give it: `AgentEventPayloads` does not carry `CitationMatchResult`'s coverage. The honest variants
  therefore cannot appear there yet.
- A.8 questions 2 (retry a starved source out of turn) and 3 (is `unexamined` per-turn or
  per-source-lifetime) remain open decisions, untouched.
