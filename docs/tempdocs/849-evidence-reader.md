# 849 — The Search v3 pane is an evidence reader, not a file previewer

```
status:  SLICES 1-3 IMPLEMENTED (merged) — live round run 2026-08-19 (§0.3):
         overflow confirmed, `sv3-citation-dropped` now required=True.
         Remaining: the measured four-palette ux-audit-closure pass.
created: 2026-08-19
updated: 2026-08-19
related: 822 F8 (the pane's original charter), 845 (RAG budget honesty — IMPLEMENTING,
         this design's direct upstream), 836 (literal-citation verification; the
         SourceExamination tri-state precedent), 839 (citation-mark presentation),
         526 §3.2 (the coordinate-system conflation this design closes),
         846 (typography ramp — consumed, not redesigned here)
number:  849 provisional. `check-tempdoc-numbers` reports one live collision (#840,
         not ours); 845 is the highest on main and 846 does not exist on main, so
         846-849 are unclaimed there. Re-run the check before merge — parallel
         worktrees cannot see each other's in-flight numbers.
```

---

## 0. Implementation status (2026-08-19)

**Slice 1 (§8 "The honest record") is implemented and merged as one PR; Slice 2 (§8 "Anchoring
fidelity") followed as a second, frontend-only PR (§0.1); Slice 3 (§8 "The citation header +
measured closure") is the third, frontend-plus-harness (§0.2).** Everything below §8 Slice 3 that
§0.2 does not claim remains design, not description.

What Slice 1 landed, and where it differs from the design as written:

| §8 Slice 1 item | Status |
|---|---|
| 1. Four-site `STOPPED_BUDGET` fix | Done. `AppendOutcome` became a FOUR-state enum (`STOPPED_WITHOUT_APPEND` added); both `mapAppendResult` overloads and both `runBudgetLoop` consumers updated. `RagContextOpsBudgetLoopTest` runs every case against **both** budgeters (review F1: Java resolves the overloads statically, so the first draft — which only ever constructed a `ContextBudgeter` — left the `TokenAwareBudgeter` overload, i.e. the production-default open-retrieval path, at zero coverage while its prose claimed "reverting either overload fails"). Reverting the `TokenAwareBudgeter` overload now fails 3 tests; reverting the `ContextBudgeter` overload fails 3 |
| 2. Extend `ContextCitation`, constructed absent, + `withInclusion(...)` | Done. The component is ONE nested record `ContextInclusion(State, includedChars)` rather than two flat components, so each of the 33 construction sites gains one argument, not two |
| 3. Branch the cut | Done, keyed on an explicit `wholeDocumentFallback` local as specified. **Two added guards** — see the honesty note below |
| 4. Emit on `rag.citations` + persist via `RAGDoneEnricher` | Done, both sourced from the record component |
| 5. Update the `RAGContext` class javadoc | Done — the paragraph that documented the gap as permanent now documents the branch |

**The two guards on the cut, with their status stated honestly (review F6).**

- **Zero-sections fall-through — DEFENSIVE, not a reachable case.** The sectioned branch also routes
  to `truncateIfNeeded` when the retrieval arrived with no sections. An earlier draft of this note
  claimed a pre-existing 845 test "proves the guard is required"; that was wrong, and the correction
  matters. On current `main` the shape is **not producible**: both worker assembly paths pass
  `budgeter.sections()` (`RagContextOps.java:657, :683`) and the virtual-chunk fallback populates
  sections too (`:1153`), while every path that *does* return zero sections also returns an empty
  context or `chunksUsed == 0` (`:584`, `:1694`, `RemoteDocumentService.retrieveContextFallback`),
  so `RAGContext`'s own `isBlank() || chunksUsed == 0` condition routes it to the fallback branch
  first. The 845 test that exercises the shape (`oversizedContextIsTrimmedAndReported`,
  `chunksUsed=3` with `sections=List.of()`) is a **stub** shape, not evidence of a live path. The
  guard is kept because a stub, an older worker, or a future assembly path can produce it and the
  failure mode is silent (assembling from an empty list would stop truncating altogether) — but it
  is insurance, and calling a test fixture "proof of a live path" was exactly the kind of
  overstatement this tempdoc exists to remove.
- **Assembled-total re-check — a real defect, with a measured counter-example.** The section loop
  budgets the SUM of its parts, but `estimateTokens` is **not additive**: it takes
  `max(wordEstimate, charEstimate)` and switches `charEstimate` on the whitespace and non-ASCII
  *ratios* of the string it is handed. A mixture of sparse-ASCII and dense-CJK sections can leave
  every part the safe side of both thresholds while the concatenation crosses both. Measured on the
  regression fixture: parts sum to 350 tokens against a 460 cap, the assembly estimates **549**.
  The guard re-checks the assembled string and falls back, dropping the per-section record (a record
  describing sections is not true of a string that was then cut blind).
  **And the fallback alone was insufficient** — `truncateIfNeeded` sizes its head/tail windows in
  *whitespace-delimited words*, so on whitespace-poor text it has almost nothing to drop and returned
  **571** tokens for that same fixture, still over cap. The whitespace-poor case is precisely what
  trips the ratio thresholds, so the guard would have fired on exactly the inputs its remedy could
  not fix. Enforcement therefore ends with the code-point-safe binary search (`fitToTokenBudget`)
  already in the file. This was found because the new test asserted the *invariant* (the returned
  string fits) rather than "the fallback ran".

Costs the design priced, as actually paid:

- **33 construction sites, not 29.** The design's count came from `new ContextCitation(`; four more
  sites spell it `new DocumentService.ContextCitation(` (`AgentCitationResolver:70`,
  `RetrieveContextController:287`, `StreamingCitationIntegrationTest:195`,
  `McpProtocolHandlerTest:241`). The direction of the miss is worth recording: a grep-derived cost
  estimate under-counts qualified references.
- **One consumer the design did not name: `McpEvidenceProjection`.** It is a registered evidence
  surface with a REFLECTIVE totality guard (`assertCovers(ContextCitation.class, …)`), so widening
  the record forced a decision there — projected as a nested `inclusion` map, emitted only when
  resolved. This is the design's own §10 Principle 1 biting on the design: a new per-item record
  must be carried the last hop to every consumer that would ask.
- **`execution-surface` + `register-guard-resolution`: both green.** No new register entry was
  needed — every touched surface was already registered; five entries gained notes.
- **`--gate wire`: not engaged**, exactly as §5.3 predicted (nothing crosses the proto boundary).

Two small widenings the tests required, both deliberate:

- `TokenEstimation.effectiveContextCap(int)` is new and public — the `MIN_BUDGET` floor
  `truncateIfNeeded` always applied, now readable by the section-aware cut so the two branches
  cannot disagree about what a zero budget means (D-5). `computeSafeInputBudgetTokens` is untouched
  and `TokenEstimationBudgetTest` passes unmodified.
- `InferenceLifecycleManager.formatContextAsNumberedPassages` (the existing package-private
  delegate to `OnlineModeOps`) is now public, so the flattening regression can round-trip through
  the REAL prompt parser rather than a re-implementation of it. `OnlineModeOps` itself stays
  package-private, and the delegate keeps its `@SuppressWarnings("unused")` + a corrected
  `UnreferencedCodeTest` row naming the real caller (review F5 — the row still said
  `OnlineModeOpsTest`, which calls `OnlineModeOps` directly and not this delegate).

One residue swept while here (review F4): `SeparatorConstantDriftTest` allowlisted
`SelectionContextInjector`'s raw `"\n\n---\n\n"` on the stated ground that "no canonical constant is
reachable across its module boundary". That justification is false — the file already imports
`DocumentService`, whose `SECTION_SEPARATOR` is the app-api mirror — and this slice's own
`RAGContext` import of `ContextBudgeter` falsifies it twice over. The file now uses the constant and
the allowlist entry is gone. An allowlist whose reason is untrue is worse than none: it teaches the
next reader a constraint that does not exist.

Open questions §9 answered by implementation: **Q6 is decided — record-only.**
`contextIncludedChars` is classified `DROPPED` in the FE totality guard with that reason.

### 0.1 Slice 2 (§8 "Anchoring fidelity") — implemented, frontend only

| §8 Slice 2 item | Status |
|---|---|
| 1. `Sv3Main` stops discarding the primary | Done. `Sv3CitationOpen` now carries `{docPath, anchor, turnId, sourceIndex}`; the derived line span is GONE from the event rather than carried alongside — see the deviation note below |
| 2. Char-anchored addressing + `offsetChars` window + the `truncated` flag | Done. `DocumentPane.citation` (`DocumentCitationAnchor`) is the anchor when set; the fetch is a lead-in/span/trail window around `startChar`, and `truncated` (plus a non-zero offset) renders a "this is a window" note |
| 3. Excerpt-as-witness | Done, as SUPPRESSION with two distinguished reasons: `witness` (the excerpt is not at the offsets) and `window` (the served slice does not contain them). Whitespace- and case-insensitive, because the chunker's quote and the extractor's text differ in whitespace far more often than in words; an EMPTY excerpt confirms (absence of a witness is not evidence of a move) |
| 4. `chunkRange` gains its first production writer | Done, via the anchor: weak = the cited chunk, strong = the claim-matched sentence located inside it, none = no claim match |
| 5. Weak tier scrollable + late-match upgrade | Done. `updated()` fires for EITHER tier; `SearchV3View.upgradeOpenPaneAnchor` re-resolves the open pane when `rag.citation_matches` lands |
| 6. The false "0-based inclusive line span" comment | Gone with the field it described |

**Three deviations from the design as written, all deliberate.**

- **The scroll selector is two queries, not one.** §4/D-2 specified `.hl-strong, .hl-weak` on the
  ground that "`.hl-strong` precedes `.hl-weak` in document order whenever both exist". That is
  false whenever the matched sentence sits anywhere but the head of its chunk — the chunk's opening
  line comes first — and a single selector returns the first DOCUMENT-order match, so it would
  scroll past the emphasis. The pane asks for `.hl-strong` first and falls back to `.hl-weak`.
- **The event carries identity, not copies.** §8.2(1) listed `chunkIndex/chunkTotal/score/
  headingText/headingLevel` as fields to widen `Sv3CitationOpen` with. They are not on
  `CitationSelectDetail` at all (`citationTypes.ts:138-147`), so forwarding them would mean widening
  the shared detail AND minting a second copy of the citation record on an event — a fork of
  `RetrievalCitation`. `turnId` + `sourceIndex` let Slice 3's header read those fields off the one
  citation record instead, which is the same projection-not-fork rule §5.3 applied backend-side.
- **`citation` is read through one normalizer.** Lit leaves an unbound property `undefined`, and
  three of the reader's four mount sites are line-addressed and never bind it, so `=== null` reads
  sent exactly those consumers down the anchored path with nothing to anchor on. Pinned by its own
  test.

**The critical-analysis pass found one real defect and one false alarm, and both are recorded.**

- **Real: the mid-line trim could cut away the evidence.** A window cut at an arbitrary character
  starts mid-line, so the reader drops the leading remainder to make line 0 a real line. On text
  whose first line break falls AFTER the cited span — an extracted PDF is routinely one very long
  line — that trim moved the window past the citation, which then reported itself as *outside the
  part of this document that could be loaded*: a suppression notice manufactured by the reader's own
  arithmetic. The trim is now taken only when the whole remainder precedes the span. Pinned by a
  test that FAILS with the guard removed, and whose fixture asserts it actually exercises the trim
  (the first draft's fixture did not — the line break fell one character outside the window, so the
  test passed for a wrong reason and the probe is what exposed it).
- **False alarm: re-arming the decay from `willUpdate` was NOT broken.** The pass suspected that
  arming off `changed.has('anchorState')` would read the derived range a cycle late. Probed by
  reverting: 33/33 still green, because Lit records a property changed *during* `willUpdate` in the
  same `changedProperties`. The arming call now sits next to the derivation because that is the
  shorter path, and the code comment says so rather than claiming a repair it did not make.

**Independent review (PR #502) — APPROVE-WITH-FIXES, all applied.** The reviewer verified the
central premise end to end (one character coordinate system from chunker to store to `/api/preview`,
with no CRLF/BOM rewrite between the buffers) and confirmed deviation 1's document-order claim
against the DOM spec. Eight findings, each now pinned by a test that fails with the fix reverted:

| # | Finding | Fix |
|---|---|---|
| S1 | `normalize()` desynced its origin map on U+0130 (`'İ'.toLowerCase()` is 2 code units — the only length-changing lowercase in U+0000-U+2FFFF), so `locateText` returned `end: NaN`: the strong highlight silently vanished or landed wrong. **Failed OPEN** | every produced unit maps back to its one source index |
| S2 | `end > content.length` was always reported as an under-loaded *window*, even when the slice ran to EOF with `truncated === false` — that document is SHRUNK, and blaming the reader's window for it is the same dishonesty inverted. Also covers `offsetChars` past EOF (the worker clamps and returns empty) | third reason `shrunk`, keyed on the `truncated` flag |
| S3 | The window note and a suppression notice rendered together — "showing the part around the cited passage" beside "the passage could not be confirmed" | the window note yields to any suppression |
| S4 | The witness comment claimed 48 chars defeats boilerplate, but `slice(0, 48)` is a MAXIMUM: a 12-char excerpt ("Introduction") false-confirms a rewritten document | `WITNESS_MIN_CHARS = 24` — too short to testify is treated as **no usable witness** (confirm-by-absence, like an empty excerpt), which is the honest statement rather than dressing a 12-character match as verification |
| S5 | The witness confirmed ANYWHERE in the span, so a 500-char insertion before the passage still confirmed while the tint had silently shifted | `WITNESS_DRIFT_CHARS = 64` — the excerpt is a word-clamped PREFIX of the chunk (`RagContextOps.clampExcerptToWordBoundary(content, 240)`), so it belongs at the span's start |
| S6 | The comment claimed the endpoint echoes the SERVED offset; `PreviewController.java:167` echoes the request parameter and the worker clamps silently | comment corrected, the echo dropped (`nextOffsetChars` is the only served-position fact); a clamp is caught by S2's coverage check |
| S7 | The `citation` branch consulted `windowCovers()` while the first fetch was still in flight (`previewWindow` still null) → a duplicate identical request | coverage also consults the in-flight request |
| S8 | `upgradeOpenPaneAnchor` wrote state from `updated()` (Lit dev-build warns) | moved to `willUpdate`, matching the reader's own derivation |

Also added: the missing **different-turn** case for the late-match upgrade (the turn-id half of the
key was code-verified but untested; it fails when the lookup is swapped for "the latest turn").
Logged rather than fixed: S9 (`pane-visible-range` emits window-relative lines against a documented
absolute contract — latent, no consumer), S10 (a degenerate `endChar <= startChar` span opens the
pane with no message; the pane cannot tell "no citation" from "unusable span" without a new event
field, so it belongs with slice 3's header), S11 (pre-existing backend: `RagContextOps` fabricates
`startChar = searchFrom` on an `indexOf` miss, so those citations will now suppress with a
"document may have changed" explanation that is false — the producer guessed).

**Tests (all in `DocumentPane.test.ts` + `SearchV3View.pane.test.ts`), each verified to fail before
the change** — the eight new pane cases were run against the pre-change component and failed 8/8;
the ninth ("leaves the line-addressed consumers alone") passed both sides, which is what makes it a
regression guard rather than a new claim. The late-match case was additionally verified to fail with
`upgradeOpenPaneAnchor` removed, so it discriminates the mechanism and not merely the event shape.
The off-by-one fixture asserts the RENDERED text of the tinted line (`line three..` at 0-based 3,
1-based 4), so a surviving off-by-one names a different line rather than a different number.

### 0.2 Slice 3 (§8 "The citation header + measured closure") — implemented, frontend + harness

| §8 Slice 3 item | Status |
|---|---|
| 1. New label/projection functions in `evidenceProjection.ts` with unit tests | Done. `inclusionBadge`, `claimMatch`, `citingTurnLabel`, `citationHeader`, `suppressGroundingFor`, `sameCitationHeader`, `CITATION_SPAN_UNUSABLE`, `CLAIM_MATCH_METRIC`. `contextInclusionOf` went from private to exported for the same reason. A `retrievalMatch` band shipped in the first revision and was REMOVED at review — see "the band that was wrong" below |
| 2. Header renders per §7; extraction-provenance line untouched | Done. `DocumentPane.citationHeader` is a new property beside — never merged into — `provenance`, which keeps its name, its line and its tempdoc-671 render-even-when-empty behaviour |
| 3. Typography consumed from 846 | Done by consumption, not authorship: the header wears `--font-size-xs` + the `--text-*` roles the pane's own notices already use, and this slice authors no scale |
| 4. Harness — extend, don't duplicate; new rows for `Sv3Pane.ts`/`SearchV3View.ts`; the live route for dropped/partial | Done, with one deliberate split — see below |
| 5. `Sv3Pane.test.ts` (also the register guard) | Done, 5 cases; `evidence-fe-sv3-pane`'s `test:Sv3Pane` guard resolves against it |
| Inclusion badge in the SOURCES panel (R3, §5.1) | Done in the shared `CitationsPanel.renderSourceCard`, so the shipped chat window and Search v3 gain it from one edit |

**The data path is slice 2's deviation paying off.** `Sv3CitationOpen` carries `turnId` +
`sourceIndex` and no copies, so `sv3CitationHeader` reads `chunkIndex`/`chunkTotal`/`score`/
`contextInclusion` off the ONE citation record the turn already holds. The header is resolved twice
by the same join — on open, and again in `upgradeOpenPaneAnchor` when a late `rag.citation_matches`
lands — because the match is exactly what turns "Retrieved · not cited" into "Grounds N sentences"
and mints the claim band. A header left on the pre-match join would have the pane emphasise a
matched sentence while its own header still said no claim used this source.

**Two §7 decisions, stated because each one chooses SILENCE over a plausible default.**

- **No score is a percentage.** §7 rule 1 (never adjacent as bare numbers) is enforced structurally:
  there is no number in the header at all. The one score is a metric NAME plus a band word.
- **`included` gets a badge too**, quietly. The design lists three states plus absence, and a
  `dropped` badge sitting alone would be read as an exception rather than as one value of a field.

**The band that was wrong, and why the first revision's reasoning did not save it (review HIGH-1 /
HIGH-2).** The first revision shipped a second band, `retrievalMatch`, gated by an allow-list of
"comparable" retrieval modes. The gating was the wrong shape of caution, and the review's numeric
reproduction is what settled it:

- `ContextCitation.score` is the **raw Lucene hit score** — `RagContextOps.java:395` does
  `setScore(hit.score())`, and the chunk reranker at `:1317-1322` REORDERS candidates without ever
  writing its cross-encoder scores back. Meanwhile `TIER_HIGH = 0.6` / `TIER_MEDIUM = 0.5` are
  anchored to the cross-encoder cutoff (`evidenceProjection.ts`, §15.C-fix). So the two are not on
  one scale in any mode.
- The consequence is not imprecision, it is **constancy**: RRF-fused hybrid scores cap around 0.09,
  so every hybrid citation would have read "weak"; raw BM25 scores are unbounded, so every BM25
  citation would have clamped to "strong". A band that cannot vary with the evidence is not a weak
  measurement, it is **negative information** — it looks like one and carries none. Worse than the
  bare percentage §7 set out to replace.
- **The allow-list was also derived from a stale source, which is the transferable half.**
  `HYBRID`/`BM25` came from `DocumentService.java:175`'s javadoc; the actual emitter
  (`RagContextOps.java:534/541/550`) makes **`CHUNK_HYBRID` the default live mode**, and
  `RAGContext.java:363` adds `FALLBACK_FAILED`. So the band was silent on the PRIMARY healthy path
  and nobody would have noticed, because its failure mode was silence. Rule taken from this: **a
  mode-conditional vocabulary must be derived from the emitter, never from a doc comment about the
  emitter.** No mode-conditional logic survives in this slice, so there is nothing left to re-derive
  — but the next one starts at the emitter.
- **Correcting this section's own earlier cost analysis:** it said the omission cost was
  "`FULLTEXT_FALLBACK` + every reloaded conversation". That was already an understatement of a
  feature that should not have existed — the default live retrieval path was silenced too. The
  register note that recorded the band as per-mode calibrated has been corrected; a register entry
  asserting a calibration that was never true is exactly the false authority the register exists to
  prevent.
- **What replaces it: nothing, for now.** Deliberately, rather than substituting a second guess. The
  mode-independent alternative — a source's RANK within the turn's own list, which needs no
  calibration and is already available FE-side — is logged as a follow-up. It is a design question,
  not a gap this PR should fill under review pressure.

**A dropped passage may not also claim it grounded the answer (review MEDIUM-3).** The pair is
REACHABLE, not hypothetical: `RAGContext.java:429` stashes every kept citation for the matcher
regardless of what the cut did with it, and `StreamingCitationMatcher` scores answer sentences
against chunk text it **re-fetches** by `(parentDocId, chunkIndex)` — not against what the model was
shown. So a card could read "Retrieved · never sent to the model" beside "Grounds 1 sentence", and
the first revision's tests *enshrined that pair as correct*. Both surfaces now withhold the grounding
label and the claim band for `dropped` (`suppressGroundingFor`, one predicate, both surfaces), and
the tests pin the suppression plus a discriminator proving it is scoped to `dropped` and is not a
blanket removal. §5.5 predicted this contradiction would become *visible*; it did, and the honest
response is to stop printing one half rather than to print both and let the reader choose. The deeper
fix — never showing the matcher a dropped citation — is a backend follow-up, logged.

**S10 is closed, and the mechanism is the header's presence, not a new event field.** Slice 2 logged
that a degenerate `endChar <= startChar` span opens the pane in silence, indistinguishable from "no
citation", and concluded it "belongs with slice 3's header". It does, and it needed no widening of
`Sv3CitationOpen`: `spanUnusable` is derived from `sv3CitationAnchor` returning `null` — the one
authority on what a usable span is — so the header being PRESENT with `spanUnusable: true` is what
distinguishes it from the header being absent, which is what a line-addressed mount site gets.

**What was NOT done, and why.** The unbound `.sourceCoverage` binding in `Sv3Main`'s
`jf-citations-panel` mount (inbox-logged) stays unbound. §5.5 names `SourceExamination` only as the
ORTHOGONAL axis and §7's table reuses `sourceGroundingLabel` verbatim; nothing in R3 or R5 scopes
wiring `sourceCoverage` through `Sv3TurnEvidence`, `sv3-ask.ts` and `sv3-record.ts`, and doing it
here would be scope the design did not ask for. Consequence, stated rather than hidden: in Search v3
an unexamined source still reads "Retrieved · not cited" in both the panel and the new header.

**The harness, and the one place it departs from §D-8's letter.** `sv3-citation-selected` is
EXTENDED as instructed — the card click it already performs opens the pane, so the step now waits on
`[data-testid="sv3-pane-document"]` and `[data-testid="citation-header"]`, which is what earns
`Sv3Pane.ts` and `SearchV3View.ts` their first step-index rows (plus `evidenceProjection.ts`, which
now owns the words those captures show). The departure is that `dropped`/`partial` gets its OWN step,
`sv3-citation-dropped`, rather than a further extension: the two states are mutually exclusive within
a turn, because `sv3-citation-selected`'s ask is chosen so the context FITS (that is what makes its
grounded marks reliable) and this one needs an ask whose context provably does not. Its determinism
is 845's arithmetic driven from the UI — the composer's THOROUGH rung sends `topK: 12` **and**
`maxTokens: 3072` (`sv3-ask.ts:158-168`), so one control simultaneously maximises the retrieved set
and shrinks, via the completion reserve, the budget it must fit in. ~~**Its first live capture is not in
this PR**: the definition ships now, the baseline folds into the program's next dev-stack session.~~
**That capture ran 2026-08-19 — see §0.3.**

**And it ships `required=False`, against §D-8's explicit "it must be `required=True`" (review
LOW-6).** The deviation is recorded here rather than buried, because §D-8's instruction exists to stop
exactly this move. Its determinism argument was that `maxTokens: 3072` shrinks the input budget;
review established that `maxTokens` is the completion RESERVE, which makes overflow **plausible but
not proven** — nobody has yet shown twelve passages exceed what remains on a real corpus. A required
step that cannot be shown to reach its state fails every run for a reason that is not a regression,
and a harness that cries wolf is worse than one step short. So the flag is temporary and its reversal
trigger is named in the step's own comment: at the first live capture, overflow confirmed → flip to
`required=True`; not confirmed → retune the ask (more documents, longer passages, a higher rung)
until it overflows, then flip. It does not stay `False` by default, and "we never got round to the
capture" is the predictable evasion this sentence exists to foreclose.

> **SUPERSEDED 2026-08-19 — the trigger fired as written.** The live capture ran, overflow is
> confirmed with measured numbers, and the step is now `required=True`. See **§0.3**. The paragraph
> above is kept as the record of why it shipped `False` for one slice, not as current state.

**One gate finding, and the fix moved twice (review MEDIUM-4).** `check-verdict-derivation` failed on
`evidenceProjection.ts`: its predicate was the string `retrieval\s*[=!]==`, and the header's own
emptiness check contained `header.retrieval !== null`. The first response restructured the PRODUCT
code to avoid the string — which passes the gate while leaving the false-positive class in place for
the next file that names a field `retrieval`. The right fix is the predicate, and it is now
`retrieval\s*[=!]==\s*['"]`: forming a verdict means comparing the retrieval readiness to a STATE
LITERAL, which is what the gate's own note says it guards.

The reviewer proposed scoping to `readiness\.` instead. That would have **broken the gate**: the seam
itself destructures (`verdict.ts:318-324` reads `r.retrieval === 'degraded'`), so a receiver-name
pattern fails the seam-integrity leg and would miss any second site that aliased the same way.
Verified by probe rather than by reading — a crafted `readiness.retrieval === 'DEGRADED'` in a
non-allow-listed `shell-v0` file still FAILS the gate; the crafted `header.retrieval !== null` shape
now PASSES; the clean tree passes before and after. Both legs still bite. The `allowed` list was
never touched: an allow-list entry buys a passing gate by permanently blinding it to the file most
likely to grow a real violation.

**Tests — 37 new, of which 32 were verified to FAIL against the pre-slice-3 sources** (the six
touched source files checked out at the preceding commit, the tests left in place). The five that
passed both sides are regression guards by construction and are named as such: the two absence cases
in `CitationsPanel.test.ts` (a citation that says nothing renders nothing; an unrecognised value is
not coerced), `DocumentPane`'s "renders no header at all for the line-addressed mount sites", and
`Sv3Pane`'s "renders nothing until a document is set" + "re-raises the close as the window's own
event". Honest caveat on one of the 32: `DocumentPane`'s "a citation WITH a usable span shows no
such notice" fails there because its fixture builder calls the not-yet-existing `citationHeader`, not
because the pane rendered a notice — it is a discriminator for the S10 case beside it, not an
independent pre-change claim.

**The review fixes are pinned at their own granularity**, which is the stricter check: reverting the
FIX rather than the slice. Switching `suppressGroundingFor` off fails **5** tests across all four
surfaces (projection, panel, pane, window); restoring the shared early return in
`upgradeOpenPaneAnchor` fails the LOW-5 case; the removed retrieval band is held out by an
EXHAUSTIVE assertion (the header's only band-shaped member is the claim one) rather than by a
`not.toContain`, so re-adding it under any label fails. `sameCitationHeader`'s member-count assertion
earned itself immediately — it caught a miscount in its own first draft.

Full suite green: 428 files / 5441 tests, typecheck clean, `check-ui-step-coverage` + the ui-web
gate set green (four pre-existing reds — `theme-token-closure`/`strip-token-fallbacks` on
`RecentsMenu.ts`, `accent-as-text` on `ActionLedgerView.ts`, `controls-a11y` on
`UnifiedChatView.ts:2137` — in files this slice does not touch).

**Correcting this section's own earlier claim about the kernel (review LOW-7):** it said "the kernel
gates green", which was true only of the eight gates actually run. The FULL kernel is **35 gates, of
which 8 fail on this branch** (`hook-integrity`, `npm-audit`, `ts-any`, `module-deps`, `dead-code`,
`dead-code-jvm`, `contract-projection`, `config-surface`) — all pre-existing. What makes that
harmless here is not the word "green" but the overlap check, which is the claim actually worth making
and is now measured: all **79** findings across those 8 gates were scanned for this slice's file
names and **zero** name any of them. `subset-isnt-the-suite`, applied to the sentence rather than
only to the run.

**Presentation-authority closure is NOT complete.** `ux-audit-closure` asks for an independent,
measured (axe + contrast oracle) live-verified audit by an auditor ≠ committer. This slice adds two
new rendered vocabularies — the inclusion badge (three states, `dropped` on `--text-warning`) and the
citation header — and the measured audit of both **in all four palettes**, plus the degraded states
(absent header, absent inclusion, S10 notice), folds into the next audit round. Recorded here rather
than left implicit, since the honor-system gate was retired in 563 and nothing will fail the build
for its absence.

### 0.3 The live round, run 2026-08-19 — overflow CONFIRMED, `sv3-citation-dropped` now `required=True`

Slice 3 shipped `sv3-citation-dropped` at `required=False` against §D-8's explicit instruction, with
one named reversal trigger: **the first live capture**. That capture has now run, and this section
is the record the trigger asked for.

**Conditions.** Dev stack from this worktree's dist, compact chat profile, `contextWindow` 4096,
corpus `docs/{explanation,reference,how-to}` = 111 documents / 1149 chunks, all four enrichment
stages at 100%.

**The measurement, and what it settles.** Review's objection was correct — `maxTokens: 3072` is the
completion RESERVE, so the THOROUGH rung shrinking the input budget was *plausible but unproven*.
Live, the reserve leaves roughly **1024 tokens of input budget against the 4096-token window**, and
the THOROUGH turn's own context meter read **630 / 4096**. Read against the window that is 15% and
looks like abundant headroom; read against what the reserve actually leaves it is ~60% — and it is
the second number the budget enforces. Twelve retrieved passages do not fit in it.

| Turn | Rung | Meter | Inclusion badges observed |
|---|---|---|---|
| "What does the JustSearch Worker do?" | default | 2474 / 4096 | 2 × `included`, **1 × `partial`** |
| §D-8's broad five-area ask | THOROUGH | 630 / 4096 | **1 × `partial`** |

**A non-included badge renders, so the step's assertion reaches its state.** Two consecutive
`jseval ui-shot sv3-citation-dropped --ui-url http://127.0.0.1:5173` runs both reached it (the
step's `wait_for_function` RAISES on timeout, so completing IS the assertion passing) — it is not a
coin flip. Accordingly the step is flipped to **`required=True`**, and the Step() comment now
carries these numbers instead of the reversal trigger.

**Honest scope of the confirmation.** What was observed is `partial`, not `dropped`. The step
accepts either, and `partial` is the same budget boundary seen from one passage in; but the
strictly-flagship "retrieved and never sent at all" state was not the one this corpus produced, and
saying otherwise would overclaim. Worth noting the default rung ALSO overflowed — at a 4096-token
dev context the boundary is easy to reach, which is what makes the step deterministic here and is
also a caution: on a larger production context window the same ask may stop overflowing, so the
step's determinism is a property of the dev context, not of the ask alone.

**The provenance header renders live**, resolved by the same join and carrying all four facts §7
specifies:

> `Cited in the answer to What does the JustSearch Worker do?` · `Passage 7 of 8` ·
> `Sent to the model` · `Grounds 2 sentences` · `Claim match strong`

and on the THOROUGH turn, `Passage 11 of 14` · `Partly sent to the model` · `Grounds 5 sentences` ·
`Claim match strong`. **No number appears in the header** — the one score is a metric name plus a
band word, which is §7 rule 1 holding in the live render. The inclusion badge's absence discipline
also holds: every badge on screen corresponded to a real inclusion state, and no placeholder or
"unknown" caveat was rendered anywhere.

**Still NOT discharged:** the measured four-palette `ux-audit-closure` audit of the two new
vocabularies, above. This round confirmed the states are REACHABLE and correct in one theme; it is
not the independent measured audit, and does not stand in for it.

---

## 1. Re-charter

`DocumentPane` was built (822 F8 prep, "Reading Stage") as *a standalone, integration-ready
reading surface for a single document* — its own file-level javadoc, `DocumentPane.ts:5-8`. Search
v3 then gave it one job and only one: `Sv3Pane`'s scope guard makes citations the **sole** route
into it (`Sv3Pane.ts:18-22` — `docPath` is `attribute: false`, and `SearchV3View.onCitationOpen`
is the window's one writer, `:1137-1146`). No search result, no browse row, no typed path can
reach it.

So the pane already has an evidence-reader's *scope* and a previewer's *specification*. The CSS
gap is the cheapest symptom; the expensive ones are below. The re-charter:

> **The pane's job is to let a reader check whether a claim is honestly supported by its source.**
> Every requirement follows from "can the reader confirm this citation?", not from "can the reader
> read this file?"

That test reclassifies the existing machinery. The block map, the highlight tiers and the
land-strong-then-settle decay are **kept** — they are the right machinery. What changes is what is
fed into them, what is said around them, and one backend record that has existed, unread, since
"Phase 4".

**What this design supersedes — corrected (review D-1, D-4).** An earlier revision of this document
claimed `TokenEstimation.truncateIfNeeded` (`TokenEstimation.java:138-172`) and its
`TruncationResult` record (`:187`) would be **deleted**. That was wrong on two counts, both verified
against source:

- **It keeps a caller.** §5.2 replaces the cut only on the *sectioned* branch. The whole-document
  fallback branch (`RAGContext.java:331-372`, `context = fallback` at `:370`) produces **zero
  sections**, so section-aware re-assembly has nothing to work with there and the structure-blind
  cut must be retained for it. Deleting it would leave a whole-document context **entirely
  untruncated** into a live 4096-token window — reintroducing precisely the overcommit 845 closed.
- **"and their tests" was false.** `truncateIfNeeded` has **zero** tests: grep across
  `modules/**` finds it only in `TokenEstimation.java:138` and `RAGContext.java:378`.
  `TokenEstimationBudgetTest` is 845's `computeSafeInputBudgetTokens` regression suite (every
  assertion in it targets that method) and **must not be touched**.

So this design supersedes **one branch of one call site**, not a utility. Nothing is orphaned, and
Slice 1 carries no deletion. That is a smaller teardown than the first revision claimed, and the
honest one.

---

## 2. Verified current state

Every line reference below was opened. Corrections to the briefing premise are marked **[C]**.

### 2.1 The pane and its feed

| Fact | Anchor |
|---|---|
| `Sv3Pane` mounts the shared reader passing **only** `docPath`, `highlightRange`, `api-base` | `Sv3Pane.ts:215-221` |
| `Sv3CitationOpen` carries **only** `{docPath, range}` | `Sv3Main.ts:99-103` |
| …and is built by discarding the rest of `CitationSelectDetail` | `Sv3Main.ts:1402-1417` |
| `CitationSelectDetail` already carries `startChar, endChar, excerpt` | `citationTypes.ts:117-125` |
| `RetrievalCitation` carries all 11 fields incl. `chunkIndex/chunkTotal/score/headingText/headingLevel` | `citationTypes.ts:102-114` |
| Reader fetch is hard-capped: `offsetChars=0&maxChars=5000` | `DocumentPane.ts:279` |
| `/api/preview` returns a `truncated` flag — **the reader never reads it** | `PreviewController.java:171` vs `DocumentPane.ts:286-290` |
| Backend supports up to 200K chars and arbitrary `offsetChars` | `PreviewController.java:34,126`; `GrpcSearchService.java:80-81,638-640` |
| Mode defaults `source`, flips to `rendered` only for `.md`/`.markdown` | `DocumentPane.ts:156, 199, 97-99` |
| Block map cached at `:305`, per-block render at `:659-677` | `DocumentPane.ts` |
| Only content CSS is a block margin — zero markdown typography | `DocumentPane.ts:552-554` |
| `chunkRange` property + `hl-weak` tier exist and are **tested** | `DocumentPane.ts:117,131,258-259,564-569`; `DocumentPane.test.ts:155-183` |
| **`chunkRange` has zero production writers** — three consumer surfaces set only `highlightRange` | `Shell.ts:2106`, `SearchV2View.ts:2083`, `Sv3Pane.ts:218` |

That last row is `substrate-without-consumer-flavors`: a built, tested affordance for exactly the
"show the wider retrieved chunk" requirement, waiting since S6 for a caller.

### 2.2 The anchoring defects

Three stack, and each is independently sufficient to land the highlight in the wrong place or
nowhere:

- **D1 — off-by-one, systemwide.** The producer computes `startLine = countNewlinesBefore(...) + 1`
  — **1-based** (`RagContextOps.java:872, 1061`; `ChunkDocumentWriter.java:138-149`, whose comment
  confirms document-relative, 1-based, `endChar` exclusive). The consumer's `DocumentLineRange` is
  documented **0-based inclusive** (`DocumentPane.ts:56-60`; block descriptors likewise,
  `markdownBlockMap.ts:43-46`; source mode uses the 0-based array index, `DocumentPane.ts:683-687`).
  **No hop subtracts one** — grep for `startLine` across `SearchV3View.ts`, `SearchV2View.ts`,
  `Shell.ts` returns pass-throughs only (`SearchV2View.ts:948-949`, `Shell.ts:551, 2099`).
  `Sv3Main.ts:101`'s "0-based inclusive line span" comment is **false**.
- **D2 — the 5,000-char blind spot.** Any citation past ~char 5,000 is simply not in `content`;
  `scrollToHighlight` queries `.hl-strong`, finds nothing, and **silently does nothing**
  (`DocumentPane.ts:314-319`). Tempdoc 526 §3.2 already named this window and concluded the
  coordinate ambiguity it creates "is the **dominant** case, not an edge case."
- **D3 — the derived quantity crossed the boundary and the primary did not.** `startChar/endChar`
  are the producer's primary, exact, document-relative quantity; `startLine/endLine` are derived
  from them. `CitationSelectDetail` carries both (`citationTypes.ts:117-125`) — and `Sv3Main.ts:1409-1411`
  keeps the derived one and drops the primary.

### 2.3 The honesty gap — sharper than logged

The inbox entry (`obs:ragcontext`) says citations are emitted for passages the truncation cut. True,
and the class javadoc says so verbatim (`RAGContext.java:41-45`: *"citations are NOT filtered down
to the sections the truncation actually kept. Section-aware citation filtering is not
implemented."*). Three findings make it sharper.

**[C] The head-side truncation is not a prefix.** `TokenEstimation.truncateIfNeeded`
(`TokenEstimation.java:138-172`) is **structure-blind**: it `split("\\s+")`, keeps a head word-window
+ `"\n\n[... content truncated ...]"` + a tail word-window, and rejoins with single spaces. So:

- middle passages vanish entirely; the **last** passage survives;
- boundary passages are cut **mid-passage** — inclusion is genuinely tri-state, not binary;
- every newline is flattened, destroying the `[n] label` headers and `\n\n---\n\n` separators that
  `ContextBudgeter` wrote (`ContextBudgeter.java:36-38`, `SECTION_SEPARATOR`). **The citation
  ordinals the model is asked to cite by are shredded exactly when the prompt is most crowded.**
  Any post-hoc "which sections survived?" parse of the final prompt string is therefore impossible.

**845 is the direct upstream and stops one step short.** It landed
`ragMeta.put("context_truncated", contextTruncated || truncation.truncated())` (`RAGContext.java:386`)
with a comment that names this design's seam exactly: *"the trimmed string still carries every
citation (see the class javadoc): this flag is what keeps that limit visible rather than hidden."*
845 made the limit **visible at turn granularity**. 849 resolves it **per citation**. 845 also made
the safety net genuinely reachable (an honest budget against a 4096-token window), so this is a live
path, not a theoretical one.

**The per-passage authority already exists, end to end, and is dropped at the last hop.**

```
ContextBudgeter.Section(sourceLabel, content, truncated, sectionIndex)   ContextBudgeter.java:53-56
   javadoc: "Phase 4: Enables structured section tracking for citation filtering on truncation."
      ↓  proto ContextSection{source_label, content, truncated, section_index, chunk_index}
                                                                 indexing.proto:457-463, :477
      ↓  DocumentService.ContextResult.sections                   DocumentService.java:189
      ↓  RemoteDocumentService.java:225-232
      ✗  RAGContext reads context/chunksUsed/chunksFound/citations/mode/quality — never sections()
                                                                 RAGContext.java:300-306
```

`ContextSection.chunk_index` is documented *"Index into chunks array for citation linkage"*
(`indexing.proto:462`). The record was built for this and never consumed for it.

**[C] A worker-side defect must be fixed alongside — at FOUR sites, and its severity is not
misalignment.** `STOPPED_BUDGET` means *nothing was appended*; `APPENDED_TRUNCATED` means *something
was*. Both collapse into one `AppendOutcome.APPENDED_AND_STOPPED` in **both** `mapAppendResult`
overloads — `TokenAwareBudgeter` (`RagContextOps.java:1706-1712`) and `ContextBudgeter`
(`:1715-1721`) — and **both** consumers then `used.add(hit)` on that value: the primary loop
(`:1757-1760`) and the overflow backfill (`:1776-1783`). All four need the fix.

**Severity, stated precisely** (review D-6 — the first revision overstated it). Because the loop
`break`s immediately after, the extra entry is always the **tail**: indices `0..k-1` still align
with `sections`, so the design's positional join is not wholesale-broken. What the defect actually
produces is a **fabricated inclusion claim** — the final passage of a budget-exhausted retrieval gets
a citation asserting it reached the model when it contributed zero characters — plus an inflated
`chunksIncluded`, since that count is `used.size()` (`:649`, `:676`). That is exactly the dishonesty
this tempdoc exists to remove, one layer down, which is why it lands in the same slice rather than
being logged.

**The correct local already exists — mirror it.** The virtual-chunk fallback in the same file
(`:1091-1104`) gets this right: it adds to `usedChunks` only on `APPENDED || APPENDED_TRUNCATED`,
and treats `STOPPED_BUDGET` as break-without-add. The fix is to make the tri-state distinguish the
two outcomes and bring the four sites into line with the path that is already honest.

### 2.4 The evidence authorities that already exist (do not fork)

- **`components/chat/evidenceProjection.ts` is the registered evidence authority.** It owns
  `evidenceTier` (the ONE branded tier mint, `:275`), `groundingClass`/`groundingLabel`,
  `groundingCoverage`, `coverageHonesty`, `coverageNote`, `sourceGrounding`/`sourceGroundingLabel`,
  `DOC_LEVEL_CHUNK_SENTINEL`, `evidenceScore`, `toEvidenceItem`.
- **`SourceExamination = 'cited' | 'examined-uncited' | 'unexamined'`** (`:597-598`, minted by 836
  S2S3-A.3) is the precedent this design copies — including its absence discipline: *"Absent ⇒ the
  state stays the established binary, so a producer that says nothing about coverage does not get
  'unexamined' assumed on its behalf"* (`:615-618`), and its containment rule: a budget fact
  *"never feeds a grounding tier or count"* (`:591-592`).
- **`CoverageHonesty`** (`:404-463`) is a projection of backend truth and returns `null` when the
  producer said nothing (`:443`) — absence ≠ zero. `coverageNote` deliberately renders **no
  percentage** (`:531-567`).
- **`RagMetaPayload`** already reaches the FE with `context_truncated` (`api/streams.ts:93-109`).
- **`CitationHoverCard`** is the closest prior art to a provenance header: heading-or-filename,
  clamped excerpt, `groundingLabel(score)` (`CitationHoverCard.ts:15-20, 88-100`).
- **The prior art to *avoid*** is `renderPreamble` (`UnifiedChatView.ts:5723-5745`), which joins
  ragMeta into `"N passages used (M found) · hybrid · coverage 42% · context truncated"` — a raw
  percentage of exactly the kind 836 S2S3-A.2 argued against.
- **[C] `ragMeta` is not persisted.** `ConversationEngine.persistedAssistant` stores citations,
  calibration and claimMatches only (`ConversationEngine.java:846-865`; noted FE-side at
  `UnifiedChatView.ts:5243-5253`). A reloaded conversation loses `context_truncated` entirely.
- **[C] `claimMatches` has no `chunkIndex`.** Linkage is positional `sourceIndex`
  (`StreamingCitationMatcher.java:326-355`; the convention is spelled out at
  `evidenceProjection.ts:603-609`). The briefing's field list was wrong here, and also missed
  `textSource`, `scorer`, `sentencesScored`, `sourceCoverage`.

---

## 3. R1 — Anchoring: address by character, derive lines locally

**Diagnosis.** The pane consumes a *derived* coordinate (1-based lines) that crossed a process
boundary while its *primary* (0-based document char offsets) was discarded one hop earlier. Every
one of D1/D2/D3 is a consequence.

**Design.**

1. **`startChar/endChar` become the anchor.** They are already on `CitationSelectDetail`
   (`citationTypes.ts:117-125`) — Slice 2 stops discarding them at `Sv3Main.ts:1409-1411`.
2. **The pane fetches a window around the citation, not the file's head.** `/api/preview` already
   takes `offsetChars` (`PreviewController.java:126`) and allows up to 200K
   (`GrpcSearchService.java:81`). The reader requests a window containing `[startChar, endChar)`
   with lead-in context, and reads the response's `truncated` flag it currently ignores.
   This alone kills D2.
3. **Lines are derived locally, in the reader's own 0-based coordinate system**, by counting
   newlines in the fetched slice. `highlightRange`/`chunkRange` keep their existing 0-based
   contract and their existing tier machinery unchanged. D1 and D3 die with the conversion.
4. **Excerpt-as-witness** (the staleness answer). §7-E establishes that **no** content hash,
   doc version or index timestamp exists anywhere for a citation — a reader cannot ask the index
   "did this change?". But the citation carries `excerpt` (240 chars, word-clamped,
   `RagContextOps.java:1140`). If the text at the anchored offsets does not contain the excerpt's
   leading run, **the offsets are stale and the reader says so** instead of highlighting confidently
   wrong text. This needs no index change and no new field, and it fails in the safe direction:
   a false "possibly moved" notice costs a sentence of chrome; a false confident highlight is the
   pane lending its authority to a wrong location.

   **Witness failure renders as suppression, not as a caveat** (review D-11 — decided, not left
   open). When the witness fails the pane **does not highlight at all**: it shows the document and
   says the cited location could not be confirmed. A *caveated* highlight — tinted text plus a "may
   have moved" note — is the worse option precisely because the tint is the strongest signal on the
   surface and the caveat is the weakest, so the reader takes the emphasis and discards the words.
   Drawing attention to text we know is at possibly-wrong offsets is the exact failure mode this
   design exists to prevent, and a hedge does not convert it into an honest one.

**Rejected: excerpt-text search as the primary anchor.** It is unstable under repeated text
(headers, boilerplate, tables) and would silently pick the wrong occurrence — the same class of
confidently-wrong the design exists to remove. Witness, not anchor.

**Explicitly out of scope, and why.** A real staleness signal (content hash on the chunk) is an
*indexing-side* change with a writer, a proto field and a migration. It is the right long-term
answer and it is not this tempdoc's problem. §9 Q3 records it.

---

## 4. R2 — Chunk boundaries: give the dead substrate its consumer

**The machinery is built.** `chunkRange` + the `hl-weak` tier + the invariant "the chunkRange tier
NEVER gets the strong phase" (`DocumentPane.ts:216-221, 253-261`), all under test
(`DocumentPane.test.ts:155-183, 262-268`). It needs a writer, not a redesign.

**One honest complication the briefing did not anticipate.** On a `RetrievalCitation`,
`startChar/endChar` **are** the chunk span — there is no narrower "the cited sentence" span on the
citation itself. So naively feeding both properties yields two identical ranges and a strong tier
that means nothing.

The narrower span exists elsewhere: `CitationMatch.sentenceText` (`citationTypes.ts:71-84`) is the
answer sentence a claim matched, joined to its source positionally by `sourceIndex`. So:

- **weak (`chunkRange`) = the retrieved chunk** — what the retriever selected.
- **strong (`highlightRange`) = the matched sentence located within that chunk**, when a claim
  match exists for this source.
- **no claim match ⇒ no strong phase, chunk tint only.** That is the honest rendering: we do not
  know which part of the passage was used, so the pane does not invent a sentence. This uses the
  same absence discipline `sourceGrounding` enforces.

**And that honest rendering breaks scroll-to-evidence unless the tier machinery is widened**
(review D-2 — verified). Two guards are `hl-strong`-only today:

```ts
updated():264-270       if (this.highlightRange && !this.loading && …) this.scrollToHighlight();
scrollToHighlight():314-319   this.shadowRoot?.querySelector('.hl-strong')?.scrollIntoView(…)
```

So a chunk-tint-only landing sets neither `highlightRange` nor any `.hl-strong`, and the pane opens
**at the top of the document** with the evidence tinted somewhere off-screen. This is not an edge
case: `rag.citations` is emitted at retrieval time (`RAGContext.java:419`, "so the FE has them
before any LLM tokens") and `rag.citation_matches` only after the answer streams
(`StreamingCitationMatcher.java:156`; both declared in `RAGAskShape.java:46-51`) — so **every
mid-stream citation click hits this path**, and it is the common case, not the rare one.

Design:

1. **The weak tier becomes scrollable.** Widen the `updated()` guard to fire when *either* range is
   set, and the selector to `.hl-strong, .hl-weak` (first match wins, and `.hl-strong` precedes
   `.hl-weak` in document order whenever both exist, so the strong target still wins when there is
   one). The existing invariant "`chunkRange` never gets the strong *phase*"
   (`DocumentPane.ts:216-221`) is about **emphasis** and is untouched — scrollability and emphasis
   are different properties, and conflating them is what produced the defect.
2. **A late-arriving claim match upgrades an open pane** — explicitly specified rather than left to
   fall out. When `rag.citation_matches` lands for a source whose pane is open, the pane gains its
   `highlightRange`; the existing `armedHighlightKey` guard (`:223-245`) already makes a *new*
   distinct range arm the strong phase exactly once, so the upgrade re-scrolls and lands strong
   without a second emphasis. A match for a *different* source does not touch the open pane.

**The Slice-2 test asserts scroll position, not class absence.** Asserting "no `.hl-strong`" would
pass while the reader sits at line 0 — the precise "passes for the wrong reason" the critical-analysis
pass exists to catch. The assertion is that the tinted chunk is scrolled into view.

**"Passage N of M".** Render **only** from `ContextCitation.chunkIndex/chunkTotal` (the document
ordinal), and **suppress entirely** when `chunkIndex === CHUNK_INDEX_ABSENT (-1)` — the sentinel
exists precisely because `0` is not "unknown" but a claim that this is the document's first chunk,
which is the 836 fabrication (`DocumentService.java:273-286`).

**Do not read the ordinal off a section.** `obs:registry-v1` logged a live semantic collision:
`chunk_index` means "index into the chunks array" on `ContextSection` (`indexing.proto:462`) but
"ordinal within the parent doc" on `ContextChunk`. Two meanings, one name, one file. The design
touches both records, so it names the collision and takes the citation's meaning only; renaming the
proto field is a separate, additive change this tempdoc does not take on.

---

## 5. R3 — Retrieved vs received: the flagship

### 5.1 The shape

Modelled on `SourceExamination`, one pipeline stage earlier:

```ts
type ContextInclusion = 'included' | 'partial' | 'dropped';
```

- `included` — the whole passage reached the model.
- `partial` — it reached the model with its tail cut (worker `Section.truncated`, or a head-side
  boundary cut).
- `dropped` — a citation with no surviving text.
- **absent (`undefined`) — the producer said nothing, and the reader says nothing.** Not
  "included". This is the discipline `evidenceProjection.ts:615-618` already enforces, and it is
  what keeps a pre-849 persisted conversation from being retroactively described.

**It is a projection, not a fork.** The `execution-surfaces` register's whole point is that a second
authority drifts. Here the authority is `ContextBudgeter.Section` — built for citation filtering,
carried to the Head on the wire, never read. 849 consumes it; it does not mint a rival.

### 5.2 The mechanism: make the truncation section-aware **on the sectioned branch only**

Post-hoc classification is **impossible** — §2.3 shows the final string has no newlines, no headers
and no separators left to parse. The record must be produced *at* the cut.

**The cut branches on what it is cutting** (review D-1). By `RAGContext.java:378` the `context`
variable holds one of two structurally different things, and only one of them has sections:

| Branch | How `context` got there | Sections | Cut |
|---|---|---|---|
| **Sectioned** | the retrieval's own `retrieval.context()`, assembled by `ContextBudgeter` | `retrieval.sections()`, non-empty | **section-aware re-assembly** (new) |
| **Unsectioned fallback** | replaced by `fetchBatchFallback` at `:370` when `context == null \|\| isBlank \|\| chunksUsed == 0` (`:331`) | **none** — whole-document text | **retained `truncateIfNeeded`** |

The predicate is *not* `sections.isEmpty()` and *not* `chunksUsed == 0` — it is **whether the
fallback branch replaced `context`**, which the implementation must track with an explicit local
(the branch at `:331-372` already knows). Review D-7 is why the naive predicate fails: a third
assembly path, `RemoteDocumentService.java:497-503` (`FULLTEXT_FALLBACK`), returns a `ContextResult`
with **sections > 0 and `citations == List.of()` and `chunksUsed = 0`** — so it satisfies `:331` and
has its context *replaced*, discarding sections it did carry. Today that path is masked behind the
fallback branch; a predicate keyed on `sections.isEmpty()` would mis-route it the moment anything
upstream changes.

On the unsectioned branch every citation list is empty by construction (`kept = usedRag ? citations
: List.of()`, `:392`), so no inclusion state is emitted there and nothing is claimed — the absence
discipline of §5.1 covers it exactly.

**Why the sectioned branch changes,** in order of weight:

1. **It is the only way to produce a truthful per-citation record.** Everything else in R3 depends
   on it.
2. **The flattening already corrupts a live shipped consumer.** This is the strongest motivation and
   it is not hypothetical: `OnlineModeOps.formatContextAsNumberedPassages`
   (`OnlineModeOps.java:1129-1168`) re-tags the assembled context by **splitting on
   `DocumentService.SECTION_SEPARATOR` and parsing the `[n] label` headers**. Its own javadoc
   (`:1121-1127`) states why the parsed ordinal must be used: *"that ordinal is what the prompt asks
   the model to cite and what the FE resolves against `sources[n - 1]`, so a second,
   independently-derived numbering here could silently disagree with it (tempdoc 822 §3a). A section
   whose header does not parse falls back to the running counter."* When the safety net fires, the
   flattening destroys every separator and header — so **every** header fails to parse, the fallback
   counter takes over, and the numbering silently diverges from `sources[n-1]`. The exact
   disagreement that javadoc was written to prevent is what the current cut manufactures.
3. **It removes a second, contradictory budget authority.** `ContextBudgeter` / `TokenAwareBudgeter`
   know what a section is; `truncateIfNeeded` at this call site does not. Two authorities
   disagreeing about the same cut is how the record went missing in the first place.

**Two arithmetic obligations the re-assembly inherits** (review D-5):

- **Count the overhead.** `ContextBudgeter`'s contract is explicit — *"the budget counts **all**
  output characters, including section separators and headers (e.g. `[1] file.pdf`)"*
  (`ContextBudgeter.java:10-13`). Re-assembly that budgets only section *content* will overcommit by
  the header + separator total, i.e. re-open a smaller copy of the defect 845 closed. Slice 1 tests
  this directly.
- **Name the zero-budget behaviour change.** `truncateIfNeeded` floors the budget today —
  `int cap = Math.max(MIN_BUDGET, maxContextTokens)` (`TokenEstimation.java:142`) — so a `0` budget
  silently becomes `MIN_BUDGET` (256). Since 845, `inputBudgetTokens` genuinely **can** return `0`
  (`TokenEstimationBudgetTest:102-103` pins `computeSafeInputBudgetTokens` returning `0` when the
  reserve swallows the window). Under naive re-assembly `0` yields **zero sections** — a real
  behaviour change from "a 256-token floor of context" to "no context at all". Decide it explicitly
  rather than inherit it by accident: **carry the same floor into the re-assembly**, so the two
  branches agree about what a zero budget means, and test it.

**No teardown.** The utility keeps the fallback branch (§1).

### 5.3 The carrier: extend `ContextCitation` (route (a)) — chosen, and priced

Review D-3 found a real hole: the first revision said the field would "ride
`RAGDoneEnricher.toCitationMap`", but that method reads **only** the 11 components of
`ContextCitation` (`RAGDoneEnricher.java:94-107`; record at `DocumentService.java:252-291`, which has
no inclusion component). A map key added at `RAGContext.java:405-416` and the persistence path were
**two independent emitters with no bridge** — the live stream would carry inclusion and the saved
record would not.

Two routes were open. **Route (a) — extend `ContextCitation` — is chosen**, and route (b) (a
parallel index-aligned list of inclusion states) is rejected for a reason this document cannot
coherently waive: **(b) mints a second positional authority**, which is exactly what §10 Principle 2
forbids and exactly the fork the `execution-surfaces` register exists to prevent. A design whose
thesis is *"consume the authority that exists, don't fork it"* cannot pay for its own cheapness with
a fork.

**The one real objection to (a), and its answer.** Inclusion is not known when the worker constructs
the citation — it is decided later, at the head's cut. So (a) appears to force a value nobody has.
It does not, because the record **already models exactly this**: `CHUNK_INDEX_ABSENT = -1`
(`DocumentService.java:273-286`) exists precisely because `0` is not "unknown". The new component
follows that idiom — it is constructed **absent**, and `RAGContext` derives a resolved citation list
at the cut via an immutable `withInclusion(...)` transformation. One record, one authority, absence
modelled explicitly. This also makes the FE's absence discipline (§5.1) true by construction rather
than by convention.

**Priced honestly** (the first revision's "no gate impact" is **false** as a blanket claim):

| Cost | Detail |
|---|---|
| Construction sites | **29** `new ContextCitation(` across **13** files — 3 production (`RemoteDocumentService.toCitation:444-460` incl. its empty-citation default, `DocAccess`, `SelectionContextInjector`), 10 test |
| Canonical sibling | the register's `ContextCitation` entry |
| feMirror | `citationTypes.ts` `RetrievalCitation` (register `:203-205`) |
| feProjection | `evidenceProjection.ts` (register `:388`), `CitationsPanel.ts` (register `:396`) |
| `execution-surface` gate | **Engaged in Slice 1**, not only Slice 3 — the register's `tsRefPattern` is `(SearchTrace\|RetrievalCitation)` (`:44`) |
| `--gate wire` | **Still not engaged**, and now for a stronger reason than "SSE isn't gated": the inclusion state is computed **head-side and never crosses the proto boundary**. The worker→head wire is unchanged; `ContextSection.truncated` (`indexing.proto:460`) already carries everything the head needs |

A 29-site record widening is a real cost. It is the correct one: the alternative buys a smaller diff
by creating the second authority this whole tempdoc exists to remove.

### 5.3a The SSE payload

Add to each citation map at `RAGContext.java:405-416`: `contextInclusion` (string) and
`contextIncludedChars` (int) — now sourced from the record component, so the stream and the
persisted record cannot disagree.

- **`--gate wire` is not engaged.** `rag.citations` / `rag.meta` are SSE JSON emitted from Java
  (`RAGContext.java:387, 419`) and appear **nowhere** under `contracts/**`. The gate runs
  `buf breaking` over `contracts/wire` protos only (`governance/registry.v1.json:350-367`).
  No proto field is needed either — `ContextSection.truncated` is already on the wire
  (`indexing.proto:460`).
- **That is itself a finding, and this design does not hide behind it.** The flagship honesty field
  lands on a wire no gate watches; `RAGAskShape.EVENT_SCHEMA` declares event *names* only
  (`RAGAskShape.java:46-51`) and `scripts/codegen/shapes.fixture.json:940-975` mirrors them with
  `"fields": []`. `obs:registry-v1` already logged the ungoverned-citation-contract condition.
  Slice 1 therefore carries its own regression tests as the guarantee (§8), and §9 Q4 asks whether
  the SSE payload vocabulary should be brought under a gate — a question larger than this tempdoc.

### 5.4 Put it on the citation, not on meta — for a load-bearing reason

`ragMeta` is **not persisted** (§2.4). With route (a) in place, `RAGDoneEnricher.toCitationMap`
(`RAGDoneEnricher.java:94-107`) gains the component and carries it into the conversation record, so a
reloaded conversation still knows which of its sources reached the model. A meta-level list would
evaporate on reload — the pane would be honest live and silent on the record it is most likely
inspected from. This is the second independent argument for route (a): route (b)'s parallel list
would have had to be persisted *and* re-aligned on load, and a positional alignment that must survive
serialization is a fork with extra steps.

### 5.5 Two budgets, two axes — do not merge them

```
retrieved  →  included (context budget)  →  examined (verification budget)  →  cited
                    ↑ THIS design                  ↑ SourceExamination (836)
```

`SourceExamination` answers "did the *matcher* score it?"; `ContextInclusion` answers "did the
*model* see it?". They are orthogonal and must render as separate facts.

**And the reason it matters.** `StreamingCitationMatcher` scores answer sentences against chunk text
**re-fetched by `(parentDocId, chunkIndex)`** (836 §A.1), not against what the model was actually
shown. So a `dropped` source can still be examined, scored, and rendered as *"Grounds 3 sentences"*.
Stated with 836's own discipline: this is **constructible, not measured**. No claim is made here that
it is occurring. What this design changes is that it becomes *visible* — a source badged `dropped`
sitting next to a grounding label is a contradiction the reader can see, where today it is invisible
to everyone.

---

## 6. R4 — Modes and the cross-block limit: acceptable, and say why

**Keep both defaults.** `source` for non-markdown, `rendered` for `.md`/`.markdown`
(`DocumentPane.ts:156, 199`). Keep per-block parsing — block descriptors carrying line ranges are
what make any highlight possible at all (`markdownBlockMap.ts:5-8`).

**The cross-block context loss is acceptable for an evidence reader, and this is the test that
settles it.** The documented limits (`markdownBlockMap.ts:29-34`) are: reference-style links whose
definition sits in another block, setext headings, and lazy-continuation lists absorbed into a
preceding paragraph. Run each against "can this make the reader believe something false about the
evidence?":

- A broken reference link renders as literal `[text][ref]` — **visibly** unresolved, not silently
  wrong.
- A setext heading renders as a paragraph — visually demoted, not misattributed.
- An absorbed list renders correctly; only the *block boundary* is wider than ideal.

None can produce a false belief about the evidence. Contrast R1's anchoring defects, which put the
highlight on the wrong text — those can, which is why they are fixed and this is not. The
honest-limits comment gains one line recording this evidence-reader rationale, so the next agent
inherits the judgment rather than re-litigating it.

**One real precision loss does need saying.** In rendered mode the highlight granularity is the
**block**, not the span: a one-sentence citation inside a six-line paragraph tints the whole
paragraph. Source mode is exact. The header states the granularity when it differs; the reader can
switch. This is chrome, not a new mode. §9 Q1 records the open call on whether a citation landing
should default to source mode regardless of extension.

---

## 7. R5 — The citation header

**Resolve a name collision first.** `DocumentPane.provenance` (`:134`, `renderProvenanceLine`
`:646-657`, `.preview-source`) is **text-extraction** provenance — the OCR/text-layer route, and it
is deliberately rendered even for empty content (tempdoc 671 diagnostic, `:10-15`). It keeps its
line, its name and its behaviour. The new thing is the **citation header**. Do not overload
"provenance".

**Contents — every one projected from an existing authority:**

| Element | Source | Rule |
|---|---|---|
| Which turn cited this | `Sv3CitationOpen` gains the turn id (`ThreadMessage.id`, `unifiedChatRequest.ts:33-66`) | The pane is opened by a citation; it should be able to say by which |
| Passage N of M | `ContextCitation.chunkIndex/chunkTotal` | Suppressed on `CHUNK_INDEX_ABSENT` (§4) |
| **Inclusion badge** | §5 `ContextInclusion` | Absent ⇒ render nothing |
| Claim tier | `groundingLabel` / `sourceGroundingLabel` (`evidenceProjection.ts:647-653`) | Reuse verbatim — it already refuses "high confidence" for an uncited source |
| Retrieval score | `ContextCitation.score` | **Labelled by mode** — see below |
| Extraction provenance | existing `.preview-source` line | Unchanged |

**The two scores are different quantities, and one of them is secretly two.**
`ContextCitation.score` is the *retrieval* score; `CitationMatch.similarity` is the
*claim-verification* score. Worse: on the fallback path `score` is `scoreByTermOverlap`
(`RagContextOps.java:1063`), not a cross-encoder score — the same field name carrying
non-comparable quantities depending on `retrieval_mode`. Design rules:

1. **Never place the two adjacent as bare numbers.** Each is labelled by what it measures.
2. **Prefer a label to a percentage** on the retrieval side. `renderPreamble`'s raw
   `coverage 42%` (`UnifiedChatView.ts:5723-5745`) is the prior art to not copy.
3. **Qualify the retrieval score by `ragMeta.retrieval_mode`**, which the FE already holds
   (`api/streams.ts:93-109`), or omit it when the mode makes it non-comparable.

**All new label logic goes into `evidenceProjection.ts`, not into the pane.** It is the registered
authority; a label minted in a view is the fork the register exists to prevent.

**Typography.** The header and body consume charter 846's shared ramp. This tempdoc authors no type
scale. (846 does not exist on `main` — §9 Q5 records the sequencing dependency.)

---

## 8. Slices

Ordered by dependency, not by size. Three, and each is independently shippable.

### Slice 1 — The honest record (backend only, no UI)

1. Fix the worker defect at **all four** sites: both `mapAppendResult` overloads
   (`RagContextOps.java:1706-1712`, `:1715-1721`), the primary loop (`:1757-1760`) and the overflow
   backfill (`:1776-1783`), mirroring the already-correct `:1091-1104` (§2.3).
2. Extend `ContextCitation` with the inclusion component, constructed **absent**, plus
   `withInclusion(...)` (§5.3, route (a)) — 29 construction sites across 13 files, feMirror and
   feProjection included.
3. Branch the cut at `RAGContext.java:378` (§5.2): sectioned → section-aware re-assembly over
   `retrieval.sections()` counting header + separator overhead and carrying the `MIN_BUDGET` floor;
   unsectioned fallback → **retained** `truncateIfNeeded`. **No deletion** — `truncateIfNeeded`
   keeps this caller and has no tests of its own; `TokenEstimationBudgetTest` is 845's suite and is
   not touched (§1).
4. Emit on `rag.citations` (`:405-416`) from the record component; persistence follows via
   `RAGDoneEnricher.toCitationMap` (`:94-107`).
5. Update the `RAGContext` class javadoc `:41-45`, which currently documents the gap as permanent.

**Tests — the point is precision, not coverage** (`audit-without-test`: the audit says the sections
align; only a test proves it):
- a budget-overflowing turn where a *known* passage is cut asserts `dropped` **on that citation**,
  not merely "some citation is dropped";
- a boundary passage asserts `partial` with a plausible `contextIncludedChars`;
- a fitting turn asserts every citation `included` **and** that `context_truncated` is false — so
  the test distinguishes "passes because nothing was cut" from "passes because the field defaults";
- **the flattening regression**: the re-assembled context still contains intact `[n] label` headers
  and `SECTION_SEPARATOR`s, asserted by round-tripping it through
  `OnlineModeOps.formatContextAsNumberedPassages` and checking the passage ids match the section
  ordinals — this pins the §5.2(2) motivation, not merely the output shape;
- **the fallback-branch regression (D-1)**: a whole-document fallback context exceeding the budget
  is still truncated **and** `context_truncated == true`. Without this, the branch introduced in
  step 3 could silently stop truncating the path 845 fixed;
- **overhead arithmetic (D-5)**: a re-assembly whose sections' *content* fits but whose
  content + headers + separators does not, stays within budget;
- **zero budget (D-5)**: `inputBudgetTokens == 0` behaves identically on both branches;
- a `STOPPED_BUDGET` retrieval asserts `citations.size() == sections.size()` **and** that
  `chunksIncluded` no longer counts the non-appended tail passage (§2.3);
- **persistence + reload (D-11)**: a turn with a `dropped` citation is persisted and re-read with the
  inclusion state intact — the §5.4 argument is a claim about the record, so a stream-only assertion
  would prove the wrong thing.

**On Slice 1's own substrate-without-consumer exposure (D-10).** Slice 1 emits a field that nothing
renders until Slice 3 — the exact shape §2.1 criticises `chunkRange` for. Justified, not waived, on
two grounds: (i) the field is *load-bearing for the record*, and a persisted conversation created
between Slice 1 and Slice 3 would otherwise be permanently unable to answer the question (absence is
indistinguishable from `included` by design, so it cannot be backfilled); (ii) the slice's value is
not only the field — the four-site fix, the branch, and the flattening repair are corrections that
stand alone with or without a reader. If Slices 2-3 are abandoned, Slice 1 is still net-positive,
which is the test `chunkRange` failed.

### Slice 2 — Anchoring fidelity (event + reader)

1. `Sv3Main.ts:1402-1417` stops discarding: `startChar/endChar/chunkIndex/chunkTotal/excerpt/score/
   headingText/headingLevel` + inclusion + turn id widen `Sv3CitationOpen` (`:99-103`) and flow
   through `SearchV3View.onCitationOpen` (`:1137-1146`) and `Sv3Pane` (`:215-221`).
2. `DocumentPane` gains char-anchored addressing: window fetch around `startChar` via `offsetChars`,
   local char→0-based-line mapping, and it starts reading `/api/preview`'s `truncated` flag.
3. Excerpt-as-witness staleness notice (§3.4).
4. Wire `chunkRange` from the citation; `highlightRange` from the matched sentence when one exists,
   else no strong phase (§4).
5. **Make the weak tier scrollable** and specify the late-match upgrade (§4, review D-2):
   widen `DocumentPane.updated():264-270`'s guard and `scrollToHighlight():314-319`'s selector.
6. Correct the false "0-based inclusive" comment at `Sv3Main.ts:101`.

**Tests:** a citation at char ~40,000 lands (D2); a fixture with known line positions proves the
mapping is 0-based and off-by-one-free (D1) — asserting the *rendered* highlighted text, not the
computed number, so it cannot pass for the wrong reason; a mutated document suppresses the highlight
and shows the witness notice; a citation with no claim match renders chunk tint **and is scrolled
into view** — the assertion is scroll position, never the absence of `.hl-strong` (D-2); a
late-arriving claim match on an open pane upgrades it to strong exactly once.

Existing coverage to extend rather than duplicate: `SearchV3View.pane.test.ts` already pins the pane
wiring (e.g. `:255` asserts the forwarded `highlightRange`, `:602` the `attribute: false` scope
guard). There is no `Sv3Pane.test.ts`; Slice 3 creates one.

### Slice 3 — The citation header + measured closure

1. New label/projection functions in `evidenceProjection.ts` (inclusion label; retrieval-vs-claim
   score labelling) with unit tests beside the existing `evidenceProjection.test.ts`.
2. Header renders per §7; extraction-provenance line untouched.
3. Typography consumed from 846.
4. **Harness reachability — corrected (review D-8).** The first revision claimed zero step coverage.
   That is wrong for two of the four files: `ui_step_index.json` already maps
   `DocumentPane.ts → ["inspector-open", "citation-highlight"]` (`:11-13`) and
   `Sv3Main.ts → ["sv3-citation-selected"]` (`:22`), all three live in `ui_check.py`
   (`:1630`, `:1635`, `:1680`). **Extend those steps** rather than mint parallel ones. What is
   genuinely absent is `Sv3Pane.ts` and `SearchV3View.ts` — zero index entries — so those gain rows
   against the extended steps (index governed by `governance/ui-step-coverage.v1.json` via
   `check-ui-step-coverage`).

   **The mechanism for entering `dropped`/`partial` must be named, or 3.4 repeats 839.** A first
   draft of this section proposed seeding the states from `fixtures.ts`. **That is not available**,
   and the harness says so in the `sv3-citation-selected` step's own comment
   (`ui_check.py:1670-1681`): *"a v3 turn's evidence comes only from the live stream … NOT registered
   in the proportion baseline, whose gate captures under `--fixtures` where this state is
   unreachable."* (`fixtures.ts` in this directory is a copy/constants module, not a turn-seeding
   fixture source.) So the route is the one 839 already established and Slice 3 extends:
   **live stack + `ai_activate`, `isolated=True`**.

   What makes it *deterministic* is 845's own arithmetic: the input budget is now derived from the
   live context window and the turn's real completion reserve, so a scoped ask over documents whose
   assembled context provably exceeds that budget forces `partial` at the boundary section and
   `dropped` beyond it, every run. The step drives that ask rather than hoping a natural query
   overflows.

   **And it must be `required=True`.** The same comment records the near-miss: the one step that
   could see the 839 regression was declared `required=False`, and `EvalResult.ok` consults only
   required steps — *"A step whose verdict is discarded is a screenshot, not a check."* The new
   captures inherit that lesson explicitly, not by default.
5. There is no `Sv3Pane.test.ts` today — Slice 3 creates it (also the register guard, §8.1).

**Measured UX audit at closure** (`ux-audit-closure` — honor-system since tempdoc 563, not
build-enforced): an independent auditor, **not the committer**, runs a measured axe + contrast-oracle
pass on a live surface. Must cover the inclusion badge in **both** themes and the degraded states,
not only the happy path.

### 8.1 Register and gate impact

| Gate | Impact |
|---|---|
| `execution-surface` | **Engaged in Slice 1 AND Slice 3** — corrected (review D-3). Route (a) widens `ContextCitation` and its `citationTypes.ts` `RetrievalCitation` feMirror, and the register's `tsRefPattern` is `(SearchTrace\|RetrievalCitation)` (`execution-surfaces.v1.json:44`), so the canonical-sibling + feMirror + feProjection entries (`:203-205`, `:388`, `:396`) are all touched by Slice 1. Slice 3's pane additionally needs its own entry (`kind: consumer`) with a **resolving** `guard` — `test:<Name>` requires the file to exist, so the Slice 3 test file is the guard's referent. Dangling guards fail, and `register-guard-resolution` cross-checks. |
| `wire` | **Not engaged**, for a stronger reason than the first revision gave (§5.3): the inclusion state is computed head-side and **never crosses the proto boundary**. `contracts/wire` is unchanged and no `indexing.proto` field is added — `ContextSection.truncated` (`:460`) already carries what the head needs. |
| `check-ui-step-coverage` | **Engaged** by Slice 3's extended steps + the new `Sv3Pane.ts` / `SearchV3View.ts` index rows (D-8). |
| `operation-surface` | Not applicable — indexing-job lifecycle only. |
| `check-language-agnostic-analysis` | Not touched. |

The blanket "no gate impact" of the first revision is withdrawn. Route (a) buys correctness with
register work, and that is the honest trade.

---

## 9. Open questions

1. **Should a citation landing default to `source` mode regardless of extension?** Rendered is more
   readable; source is span-exact (§6). Leaning: keep rendered for `.md` and state the granularity,
   because an evidence reader whose evidence is unreadable prose has traded one honesty problem for
   another. Decide with the measured audit in hand.
2. **Should `dropped` sources be suppressed from the citations panel entirely, or shown badged?**
   Leaning strongly to **shown and badged**: suppression is a second, quieter kind of dishonesty,
   and the contradiction in §5.5 is only legible if the source is visible.
3. **Real staleness signal** — a content hash on the chunk (§3.4 establishes none exists anywhere).
   Indexing-side change with a writer, an additive proto field and a migration. Out of scope here;
   excerpt-as-witness is the honest interim. Worth its own tempdoc.
4. **Should the SSE payload vocabulary come under a gate?** `RAGAskShape.EVENT_SCHEMA` declares names
   only; the fixture carries `"fields": []`. Larger than 849; `obs:registry-v1` already logged the
   condition.
5. **846 sequencing — resolved into a merge plan, no longer an open question** (review D-9).
   846 is uncommitted work on the *same two files* this design edits (`DocumentPane.ts`,
   `markdownBlockMap.ts`): it deletes the `.blocks .block` margin rule that §2.1 cites as the
   "zero typography" baseline, and adds a prose/`md-content` layer plus its own highlight pass.
   **846 lands first; Slices 2 and 3 rebase onto it.** Consequences to carry:
   - §2.1's "only content CSS is a block margin" is a *pre-846* observation and stops being true on
     846's merge. It is retained here as the dated justification for the re-charter, not as a
     forward claim.
   - Slice 2 touches `DocumentPane`'s fetch/anchoring/scroll paths and Slice 3 its header —
     largely disjoint from 846's typography layer, but the highlight-pass overlap is real and is
     where the conflict will land. Slice 2's scroll-selector widening (§4/D-2) must be re-applied
     against 846's version of the tier rendering, not merged blindly.
   - Slice 3 authors no type scale under any ordering.
6. **Does `partial` deserve a char count in the UI at all**, or only in the record? Leaning:
   record-only. "Partially included" is the honest fact; a char count invites false precision about
   a boundary the reader cannot see.

---

## 10. Reach

### Principle 1 — A budget that drops something must name what it dropped, at the granularity the consumer will ask about

Where it already applies in this codebase:

| Site | Status |
|---|---|
| `ContextBudgeter.Section` | **Complies** — and was unconsumed for its stated purpose until now |
| `TokenEstimation.truncateIfNeeded` | **Violates** — §5 removes the violating call site |
| Verification budget (`sourceCoverage` / `SourceExamination`) | **Complies** — 836 built it deliberately |
| `/api/preview` `maxChars` | **Violates in the consumer**: the endpoint *does* return `truncated` (`PreviewController.java:171`) and `DocumentPane` never reads it (`:286-290`) — a live violation in the very file this tempdoc edits |

The recurring failure is not that budgets drop things; it is that the drop record is produced and
then **not carried the last hop to the consumer that would ask**. Both instances here — sections
stopping at `RemoteDocumentService`, `truncated` stopping at the fetch parse — are last-hop losses,
not missing records.

*Evidence it earns its keep:* a truncation-caused honesty defect caught by a consumer before it
ships, at least once, within two quarters.
*Retirement condition:* if two consecutive slices add a per-item drop record that no surface ever
consumes, the principle is manufacturing substrate without consumers — retire it and let each
consumer ask for what it needs.

### Principle 2 — A derived coordinate must not cross a process boundary while its primary is discarded

`startLine` is computed from `startChar` (`ChunkDocumentWriter.java:138-149`). Shipping the derived
value across the wire and dropping the primary at `Sv3Main.ts:1409-1411` is what made a systemwide
off-by-one invisible: nothing downstream could recompute or check it. The same shape is worth
looking for wherever a producer derives a convenience representation — line numbers from offsets,
percentages from counts, labels from scores.

*Evidence:* the D1/D2/D3 cluster, all three consequences of one discard.
*Retirement condition:* a hop where the consumer genuinely cannot use the primary (a line-oriented
viewer with no character index) — there the derived value is the right thing to send, and the rule
does not apply.

**Not built now.** Both principles are recorded, not generalized into structure. 849 fixes the two
instances the present problem requires and names the third (`/api/preview`'s unread `truncated`) for
whoever touches it next.
