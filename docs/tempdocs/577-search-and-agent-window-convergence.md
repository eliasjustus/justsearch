---
title: Search & Agent Window Convergence — per-window correct design first
type: tempdocs
status: active
created: 2026-06-11
updated: 2026-06-14
---

# 577 — Search & Agent Window Convergence

> **Nature of this doc.** Long-term goal tempdoc with a deliberate sequencing
> constraint: **mature each window on its own before theorizing the unification.**
> Goals 1 and 2 derive from live UI audits (2026-06-11, dev stack at
> `localhost:5173`, 34-doc index, Meta-Llama-3.1-8B online). Goal 3
> (unification theory) is intentionally **not yet written** — placeholder with
> only the constraints already known.
>
> **Goal 1 was theorized 2026-06-11** (design-theory genre per 557/559/567/570:
> correct long-term structure, feasibility/phasing deliberately disregarded,
> general not implementation-level). The investigation found the correct design
> for the search window **already exists** — `search-window-competitive-uiux-research`
> (570) Part II, complete with a de-risk audit (§18). Goal 1 therefore **adopts
> 570 Part II and extends it** with three structural extensions plus a fresh
> evidence ledger, rather than re-theorizing.
>
> **Goal 2 was theorized 2026-06-11** (same genre, same method, second agent).
> The investigation reached the mirror verdict: the agent window's correct
> design **already exists across two tempdocs** —
> `agent-window-presentation-and-evidence` (565, content tier, largely shipped)
> and `llm-interaction-correct-structure` (561, surface tier, designed) — so
> Goal 2 likewise **adopts 565+561 and extends them** with three structural
> extensions plus an evidence ledger from two live audits.
>
> **Goal 2 second wave theorized 2026-06-13** (§2.13 ledger + §2.14 design, by
> the search-window agent's cross-window pass after Goal 1 shipped): eight
> further classes (§2.13 #13–#20) the §2.11 ledger did not name, reducing to
> **three structural roots** that generalize §2.12's three moves one altitude
> up — the run as an *observed entity*, the run's *resource family*, and
> provenance/authority as declared facets of *all* rendered content. Roots I
> and III are Goal-3 prerequisites. Implementation of §2.13/§2.14 is NOT done
> (theory only; the §2.12 Moves 1–4 implementation status is recorded in
> §2.10's ledger).
>
> **Goal 2 was then implemented, merged, and live-validated 2026-06-12**
> (§2.10), and the post-validation issue ledger (§2.11) was theorized into a
> second-round design (§2.12, four moves: lifecycle-projected affordances,
> budget as a held gate, the epistemic answer frame, narratable decisions
> with reason codes) — each extending substrate that already exists
> (`LifecycleState` + the approval-gate machinery, the 561 grounding axis,
> the 575 liveness pattern, the Goal 1 reason-vocabulary cut).
>
> **Current state (2026-06-13).** **Goal 1 (search window) is SHIPPED, GATED,
> and CLOSED-OUT** — terminal in the search-window agent's lane: §1.9 records the
> implementation (merged `d2c5129fa`), §1.10 the close-out (URL-restore verified
> not-a-regression + the **search-issuance durability gate**, the single-seam
> half of 570 Move H — `governance/search-issuance.v1.json` +
> `scripts/ci/check-search-issuance.mjs`). Canonical-doc promotion + the full
> Move H (coverage-from-mode-catalog) are deferred to the tempdoc's terminal
> state / Goal 3. **Goal 2 (agent window)** is mid-implementation (the parallel
> agent's R2 second-wave; §2.16). **Goal 3 (unification)** remains
> deferred-by-design. The tempdoc stays **`active`** until Goals 2–3 land.
>
> **Current state (2026-06-14).** **Goal 3 (unification) is PARTIALLY SHIPPED**
> (merged to local `main` `3cfc28286`, not pushed): §3 records the design
> (synthesis verdict §3.0 — assemble, don't invent: three of four intent tiers
> already exist as window shapes); §3.10 the **retrieve base tier** (the one
> genuinely-new piece — pure retrieval as the window's base interaction reusing
> the FE `searchState` store, NOT a conversation shape per the §3.3 correction;
> live browser-validated); §3.11 the **rail collapse** (`core.search-surface`
> RAIL → DEEPLINK, boot default → the one window; backend-catalog-confirmed).
> **Remaining for Goal 3** (this plan's scope): (a) the §3.9a parity
> consolidation — fold the Goal-1 facet chips / trace altitude / "Why this
> result?" into the in-window retrieve tier so the standalone is truly
> redundant; (b) the **full Move H coverage gate** (§3.7 — intent-tier coverage
> projecting from the shape catalog, the second half of 570 Move H); (c) live
> validation of the LLM-tier escalation (Ask/Delegate) with a chat model; (d)
> canonical-doc promotion + tempdoc → terminal once Goals 2–3 fully land.

## 0. The long-term goal in one paragraph

The search window and the agent window are converging on the same loop
(query → evidence → refinement), and the seams already leak in both directions:
search has an "Ask AI" button and an Answer/Ask preview tab; the agent's tool
cards are literally search executions rendering a *worse* view of the same
results the search window renders well. Long term, there should be **one
surface with one entry point and escalating intent tiers** (instant hit-list →
cited answer → delegated agent run) — unifying the *substrate and entry point*,
not flattening the two interaction contracts. But the cheapest way to find out
whether that surface is right is to first make each window individually
excellent, sharing primitives as we go. Hence the ordering below.

## 1. Why this ordering (and not unify-first)

1. **The agent window's evidence rendering is the degraded copy of search's.**
   Fixing each window's own presentation quality forces extraction of shared
   primitives (result anatomy, citation peek, trace disclosure) — which *is*
   the unification substrate, bought incrementally and de-risked.
2. **The governance work already points this way.** The single ordered-run
   projection (`projectUnifiedThread`, the `run-renderers` register), the one
   tool-call render primitive, the steering seam (`dispatchRunControl`), and
   the canonical `SearchTrace` + execution-surfaces register exist precisely
   so multiple windows don't fork the model. The expensive prerequisite is
   mostly paid; what's left is presentation — per window first.
3. **Unify-first would commit a layout before the interaction contracts are
   understood.** Search is 2ms/keystroke-reactive/throwaway; an agent run is
   seconds-to-minutes/stateful/thread-accumulating. Getting each contract
   right separately produces the constraints the unified design must satisfy.

Related prior work (cite by slug per tempdoc README):
- `agent-window-presentation-and-evidence` (565) — the agent window's
  presentation substrate, run projections, steering seam.
- `search-window-competitive-uiux-research` (570) — Part I competitive
  research; **Part II (§11–§18) is the correct-design theory Goal 1 adopts**.
- `functional-core-user-authored-presentation` (569) — declaration-default
  rendering engine the unified surface would likely build on.
- `llm-interaction-correct-structure` (561) — posture/autonomy store the
  agent chrome consumes.
- `per-instance-vs-global-frontend-scope` (574) — presentation primitives
  (atoms, modality, transients) both windows must compose.

---

## Goal 1 — The search window: the correct long-term design (theorized 2026-06-11)

### 1.0 Investigation verdict — a usable design already exists; adopt and extend it

**Code investigation** (2026-06-11, against `main` + live dev stack): the
search window is `<jf-search-surface>`
(`modules/ui-web/src/shell-v0/views/SearchSurface.ts`, ~1,280 lines of
hand-authored Lit) over a centralized `searchState.ts` (debounced query,
parse-boundary Zod validation of the wire). The window is **already a
projection at the data tier**: `SearchTrace` is canonical and
register-guarded (`governance/execution-surfaces.v1.json` lists the surface,
state, explain-strategy and explain-component as registered referencers); the
degradation banner reads the one observed-state authority (`aiStateStore`
readiness); the trace explain panel (`<jf-search-trace>`, 549 D1) renders
from a closed `STAGE_LABELS` vocabulary. But it remains a **hand-authored
authority at the presentation tier**: bespoke result rows (the
`SearchResultsRenderer` control exists, unused), a hard-coded action set
bypassing `ContextActionRegistry`/`listContextActions()`, a date-only filter,
an Answer tab (`InspectorPane`) that streams `/api/chat/agent` but discards
the `sources`/`citations` the done-event already carries.

**Design-history investigation**: `search-window-competitive-uiux-research`
(570) Part II (§11–§17) is a complete correct-design theory for exactly this
window, in this genre, with a de-risk audit (§18) that verified each move
against the live tree. Its diagnosis: every deficit is one defect class —
*the search window is a second presentation authority for concepts that
already have canonical authorities; the correct design deletes its authority
status and makes it a projection* (§12 spine; §13 extend-don't-invent ledger;
§14 Moves A–H; §15 prevention ladder). Nothing in the present investigation
contradicts it; the 2026-06-11 audit independently re-derived its defect
class from the live UI (§1.6 below).

**Verdict (explore-before-implementing, applied to design):** Goal 1 adopts
570 Part II as its base theory and does not restate it. What follows is only
(a) the scope cut that fits it into this tempdoc's sequencing, (b) three
structural **extensions** where the live audit exposed facets 570 under-specifies,
and (c) the evidence ledger binding audit findings to the moves and updating
570 §18's residuals.

### 1.1 The adopted spine, in one paragraph

The search window stops being a hand-authored surface and becomes a
projection of the canonical records — `SearchTrace` (execution),
`Hit`/`ExcerptRegion`/`ContextCitation` via `evidenceProjection` (evidence),
the operation catalog through the `evaluateIntent` seam (verbs), the emitted
facet payload (the set), semantic colour roles (appearance), observed-state
(liveness) — with the breaking forms (a second result authority, a
hand-authored row, a hard-coded action set, a raw-string query UI, a free
`(fg,bg)` pair, a forked retrieval surface) **unrepresentable by
construction**, kept so by a register+gate whose coverage projects from the
catalog (570 §12/§14/§15). Read 570 §18 alongside: Move F is a pure FE-render
extension (HIGH confidence), Move B needs the 559 element engine or the §18
D3 interim, Move E is a backend emission gap, Move C must stay two-layer.

### 1.2 Scope cut — Goal 1 is the window-internal moves; Move A/H close in Goal 3

570 Move A (search becomes a *mode* of the one interaction surface, retiring
`core.search-surface`) **is** this tempdoc's Goal 3 — 570 §18.4 D1 names it
an unresolved design-fit decision ("is a spatial results-list a legitimate
mode/panel of the conversation window?"). The sequencing reconciliation:

- **Goal 1 scope = Moves B, C, D, E, F, G** — every projection that matures
  the window *in place*, all implementable while `core.search-surface` still
  exists, and all of which survive unchanged if/when Move A lands (they bind
  the window's regions to canonical authorities, not to the surface's
  identity).
- **Move A + the Move H register/gate close in Goal 3**, where D1 is decided
  with Goals 1–2's lessons in hand. Gating note: a `search-surface` register
  authored *before* D1 would pin the wrong coverage shape; the interim
  prevention for Goal 1 is the existing gate family (execution-surface,
  contract-projection, the 557/574 presentation gates) plus the extensions'
  own seams (§1.4).

This is also the resolution of the apparent tension between this tempdoc's
"per-window first" ordering and 561's "search is the missing mode" thesis:
both are honored — the window-internal projections are exactly the work that
does not depend on the mode decision.

### 1.3 Extension I — execution transparency needs an **altitude cut** (per-facet audience on the trace projection)

570 Moves D/G say *project the trace* (query-understanding, mode legibility,
stages-as-progress). The live audit shows what happens when that projection
has no audience model: the trace panel renders **above the results**, with
"BM25 search" as the largest text on the page, a `QPP: maxIdf=5.91 ·
avgIctf=8.07 · queryScope=0.13` scalar line, and twelve stage spans
colliding into an unseparated wall
(`…skipped (not-triggered)Sparse (BM25): executedDense (vector): skipped…`).
Per-hit "Why this result?" renders `sparse-retrieval #2 3.32cross-encoder
0.34` — unlabeled, unscaled, score values colliding with the next stage id.
**Diagnostics dominate the visual hierarchy over results — projection at the
wrong altitude.**

The correct structure: **every facet of the execution record carries a
declared audience altitude, and prominence/grammar are engine-derived from
that altitude, never authored per-surface.** Concretely at the conceptual
level (this is the 571-axis idea applied *inside* a surface, to a record's
facets, and the 575 KIND-facet idea applied to presentation):

- **User-tier facets** — `effectiveMode` legibility ("hybrid" / "keyword
  only"), `correction`/"searched instead for X", degradation-in-words, and
  the per-hit *rank rationale* — project into the primary flow in a
  human grammar: labeled, scaled (a score is a bar/tier, never a bare
  float; a negative reranker delta is "ranked down", not "−0.20"), worded.
  The "Explain in words" LLM escalation stays — as the *third* tier, not a
  patch over an illegible second.
- **Diagnostic-tier facets** — QPP scalars, per-stage status/reason/ms, raw
  fusion scores — project into a disclosure at diagnostic altitude
  (collapsed by default, subordinate type scale), or onto a DIAGNOSTIC-
  altitude surface entirely (the 571 axis: consumes-a-diagnostic-channel ⟹
  DIAGNOSTIC).
- **The breaking form made unrepresentable:** a trace facet rendered without
  an altitude assignment, or a diagnostic-tier facet minted into the primary
  flow, cannot be expressed — the projection engine takes (facet, altitude)
  and owns prominence; the surface has no field for "make QPP a headline".
  The 576 target rung is 1–2 (unrepresentable via the projection grammar);
  the run-on rendering and hierarchy inversion observed live are then not
  bugs to fix but forms that cannot recur.

This extension also subsumes the differentiator framing (570 §17): "why this
rank" is only a differentiator if it is *legible* — the altitude cut is what
turns the unique data into a unique feature.

### 1.4 Extension II — the query is **one declared intent** with one dispatch seam

570 Move D projects the query-understanding facets *outward* (display). The
audit exposed the *inward* half: the same literal query (`pipeline`, same
index) returned **13 results** in one state and **31 results** re-issued via
Enter, with the trace differing (`Chunk merge: executed` vs `skipped
(SKIPPED_QUERY_SYNTAX)`). The code investigation found the structural cause-
class: **multiple issuance paths with non-identical semantics** — the
surface's debounced `searchState` POST, the pinned-chip restore path through
the intent router, and a higher-level API client (`api/domains/search.ts`)
carrying a `querySyntax: 'simple' | 'lucene'` option the surface never sets.
What was asked is not a single declared thing; each path re-derives it.

The correct structure mirrors the seam pattern the codebase has already
proven for run control (565 §30 `dispatchRunControl`: one control-intent
channel, one dispatch site per directive): **one typed query-intent
declaration** (query text, syntax mode, filters, scope, limit) that every
issuance path — keystroke debounce, Enter, pinned-chip restore, URL
`?query=`, a future Ask escalation — *constructs identically* and submits
through **one dispatch seam**. The trace's query-understanding stage then
echoes that same record back (Move D's display half), closing the loop:
what-was-asked has one authority on the way in and one projection on the way
out. Breaking form made unrepresentable: a second search-issuance call site
with its own request-shaping cannot be written (single-dispatch-site check,
same teeth as the steering-arbitration gate). The observed 13-vs-31
instability stops being a bug to chase and becomes a class that cannot
recur. (The live instance still warrants an interrogate-results root-cause
before any work touching the results header — see §4.)

### 1.5 Extension III — degradation liveness projects **cause and remedy**, not just state

The banner is already a projection of the one observed-state authority
(`aiStateStore` readiness → `<jf-system-notice>`), which is the right bones —
but it projects only a boolean's worth of meaning. Live, it reads "Semantic
search degraded. Showing keyword results" with no cause, no remedy, while
the status bar shows the LLM *Online* (the user-visible contradiction:
semantic-retrieval degradation concerns the encoder stack, not the chat
model — the banner gives the user no way to know that). The trace meanwhile
carries per-stage `reason` codes (`sparse-shortcut`, `AI_UNAVAILABLE`) that
name causes precisely — at the wrong altitude for this purpose.

The correct structure: **the readiness record projects three facets, not
one** — *state* (degraded/ready), *cause* (which capability, from the same
closed reason vocabulary the trace stages use — one vocabulary, two
altitudes), and *remedy* (an operation reference into the catalog: load the
model pack, open Health, trigger reindex — dispatched through the same
`evaluateIntent` action seam as Move C, so a remedial action is a gated ref
like any other). A degradation notice minted without its cause+remedy facets
is unrepresentable — the notice is *minted from* the readiness record, and
the record's type requires them. This also generalizes: the same
notice-from-record grammar serves "reindex required" (already a second
branch of the same banner today) and any future capability degradation.
Scoping (when the banner shows: globally vs at the moment of a degraded
*search*) is presentation judgment — what the structure guarantees is that
whenever it shows, it can explain itself and offer the fix.

### 1.6 Evidence ledger — the 2026-06-11 live audit, mapped to the design

Audit on a model-equipped stack (LLM online), browser-automated. Each
finding is recorded as *evidence of the defect class*, not as a fix-list —
the moves/extensions dissolve them.

| # | Live finding (2026-06-11) | Defect-class reading | Dissolved by |
|---|---|---|---|
| 1 | Trace panel above results; "BM25 search" largest text on page; QPP scalars in primary flow; 12 stage spans rendered run-on without separators | Diagnostic facets projected at user altitude; prominence authored, not derived | Ext I (§1.3); 570 Move D/G |
| 2 | "Why this result?" renders `sparse-retrieval #2 3.32cross-encoder 0.34`; bare floats incl. negatives; "Explain in words" patches over it | Rank rationale (the 570 §17 differentiator) projected without a user-tier grammar | Ext I (§1.3); 570 Move F |
| 3 | Same query: 13 results ("Chunk merge: executed") vs 31 results ("skipped (SKIPPED_QUERY_SYNTAX)") | Query intent not a single declared authority; multiple issuance paths re-derive semantics | Ext II (§1.4); 570 Move D |
| 4 | Degraded banner: no cause, no remedy, contradicts LLM-Online status chip; shows on empty surface | Liveness projected as bare state; cause/remedy facets exist (trace reasons) but unprojected | Ext III (§1.5); 570 Move G |
| 5 | Snippets truncate mid-word, no ellipsis; homogeneous title+path+snippet rows | Hand-authored row forks `excerptRegions`/`matchSpans`; type-blind rendering | 570 Move B (+ §18 D3 interim) |
| 6 | `MD / JSON / Paths / Ask AI` cryptic button row; 2-item context menu | Verb-space authored on the surface, bypassing `listContextActions()`/operation catalog | 570 Move C (two-layer per §18.3) |
| 7 | Date-only filter row, always visible | Set self-description unprojected (and unemitted — §18.3: backend gap) | 570 Move E |
| 8 | Answer tab streams agent response; citations absent (the done-event's `sources`/`citations` discarded) | Grounded-answer fork — same class as the agent window's §2.2 | 570 Move F (HIGH per §18.1) |
| 9 | "non-core: 1 layout override" governance badge in user chrome | Diagnostic-altitude content on a USER surface (571 axis) | Ext I's altitude principle, applied to chrome |

**Keep (the audit's positive findings):** result anatomy with query-term
highlighting; the Preview/Context/Answer/Ask inspector; URL query
persistence; honest degradation messaging as a concept; "Why this result?"
existing at all (no competitor has it — 570 §8.2 AHEAD).

**Updates to 570 §18's residuals from this audit:**
- §18.5 asked whether semantic engages on a model-equipped stack: on this
  stack (chat LLM online) retrieval still ran keyword-only with the
  degradation banner up — consistent with semantic degradation being an
  encoder-stack condition, orthogonal to chat-model presence. The
  `sparse_shortcut`-vs-genuine-degradation distinction still needs a probe
  on a stack with the *encoder* models active.
- §18.2 found per-hit `HitStage.detail` empty on the throwaway stack: on
  this stack per-hit rank+score render live (`#2 3.32`, cross-encoder
  scores incl. negative), so the basic per-hit signal tier is emitted in
  practice; the *rich* detail tier remains unverified.
- New residual: the 549 explain panel (`jf-search-trace`) shipped after
  570's baseline and is advanced-mode-gated — the altitude problem (Ext I)
  exists *within* the advanced tier, i.e. gating the panel did not solve
  prominence, confirming altitude must be per-facet, not per-panel.

### 1.7 Boundaries and honest limits (Goal 1)

- **The interaction long tail stays team-authored by design** (569 §E):
  keystroke loop, shift-select, context-menu mechanics, drag. The projection
  spine governs content, identity, verbs, and chrome — not the input loop.
  Forcing the loop through the declared-default engine today would regress it.
- **The performance contract is inherited, not negotiable:** sub-100ms
  perceived as-you-type response (570 §6.9); projections that add a frame to
  the keystroke path are wrong regardless of structural elegance.
- **Judgment-tier residue** (570 §16): density, answer-default vs
  result-default, primary-action choice, banner scoping (§1.5) — measured
  UX-audit discipline (honor-system per tier-register rows 30–31), not gates.
- **Known substrate gaps carried from 570 §18:** Move E's backend facet
  emission; Move B's element-engine dependency (D3 interim available);
  chunk-identity for raw-hit citation matching (agent-answer path needs
  none). These are named decisions, not silent assumptions.
  *(§1.8 update: Move E's "backend gap" is corrected — facets verified
  working end-to-end live; only the FE request flag is missing.)*

### 1.8 De-risk pass (2026-06-11) — confidence audit before implementation

A confidence-building pass in the 570 §18 mould, run before any Goal 1
implementation: code probes against `main` + live wire probes against the
running dev stack (same-origin `fetch` from the FE origin; LLM online;
34-doc index). **Where §1.8 disagrees with §1.3–§1.5 or the §1.6 ledger,
§1.8 wins** (dated-history rule). Per-uncertainty verdicts:

**U1 — the 13-vs-31 instability is ROOT-CAUSED, and §1.4's diagnosis is
CORRECTED.** The flipping variable is **query expansion, not issuance-path
divergence and not chunk-merge**. Live: `querySyntax:'lucene'` → expansion
`skipped(LUCENE_SYNTAX)` → 12 hits; absent/`'simple'` → expansion
`executed` (LLM-backed) → 31 hits; and the original audit's 13-state showed
`Expansion: skipped (TIMEOUT)`. So the same query nondeterministically
returns ~12–13 vs 31 results depending on whether the **LLM expansion
succeeds within its timeout** — server-side nondeterminism, which **no FE
intent seam dissolves**. Ext II (§1.4) survives as architecture hygiene
(the issuance paths *are* divergent in shaping power: `querySyntax` flips
results 31↔12 live, and only one client can set it), but its claim that it
dissolves the observed instability class is **withdrawn**; the honest
dissolver is **Move D legibility** ("expanded to N terms" / "expansion
timed out — keyword-only" projected from the trace's `expansion` stage,
whose reason codes are emitted live today) plus a backend determinism
question that belongs to the search-quality register, not this theory
(logged to observations).

**U2 — Move E is NOT a backend gap; 570 §18.3 #3 is CORRECTED.** Live POST
with `facets:{include:true, fields:[{field:'extension'},{field:'file_kind'}]}`
returned populated `facets` keys on the wire. The whole chain is built:
worker computation (`SearchResponseBuilder.computeAndAttachFacets`, both
SparseShortcut and MultiLeg paths), proto field, HTTP mapping
(`KnowledgeSearchController` `out.put("facets", …)`), FE extraction
(`api/domains/search.ts`). The only gap: `searchState.runSearch` never
*requests* facets. **Move E confidence: LOW → HIGH (FE-only).**

**U3 — the wire is richer than assumed.** Per-hit `fields` carries typed
metadata (filename, title, mime/mime_base, path, content_preview, doc_uid,
extraction/embedding status…) — Move B's per-type minting has its data.
`matchSpans` are emitted (note: `term` fields arrive as empty strings —
minor data-quality issue). With `debug:true`, the per-hit rich
`HitStage.detail` tier IS emitted live (`{sparse: 3.32}`, `{vector: 0}`) —
**closes 570 §18.2's "detail empty" residual**. The trace is fully
populated (12 stages with status/reason/ms; QPP; degradation). Residual:
`excerptRegions` emission under `includeExcerpts:true` was not conclusively
observed (empty in one probe, truncated in another) — re-verify before
Move B/F rely on it.

**U4 — Ext I needs a NEW (small) mechanism; scope corrected.** No per-facet
audience tier exists anywhere: 571's altitude is **surface-level only**
(`surface-altitude.v1.json`, derived from consumed authorities); advanced
mode (`uiModeState`) is a binary preference gating *whole panels*; the only
partial precedent is the Health surface's render-time operator-only
redaction (slice 482 §3.4). The trace-explain strategy classifies all
SearchTrace fields as one role (`'visual'`). So Ext I's per-facet altitude
grammar is a **new substrate piece** (the same class of correction 570 §18
made to Move B) — small, but it must be *designed*, not extended into
existence. Named decision for implementation planning.

**U5 — Ext III is even more buildable than theorized.** The FE
`ReadinessView` **already carries `reasonCodes: string[]`**
(`aiStateStore.ts`) parsed from `/api/status`'s readiness envelope
(`ReadinessCompositeView` per composite: retrieval / aiFeatures) — the
banner just doesn't render them. Remedy-shaped operations exist in
`CoreOperationCatalog` (`core.reload-inference`, `core.rebuild-index`,
`core.trigger-offline-processing`, `core.activate-runtime-variant`…), and
`LifecycleSnapshotTap` already maps conditions → recovery operations.
Cause + remedy are projections of existing records. **Confidence: HIGH.**

**U6 — Move C's seam is healthy but the operation wiring is new plumbing.**
`ContextActionRegistry.listContextActions()` + the `'search-result'`
addressable + `searchResultProjector` all exist and work; `SearchSurface`
builds the addressable but passes a hard-coded 2-action array
(`copy-path`, `open-in-inspector`) instead of calling the registry.
Context actions today are plain callbacks — **no path to
`evaluateIntent`/operations exists yet**, confirming 570 §18.3's two-layer
cut: simple actions ride platform capabilities; the operation/verdict layer
is genuinely new.

**U7 — Move F VERIFIED LIVE (closes 570 §18.5 E3).** A live
`/api/chat/agent` round (Answer-tab request shape, `tools:[]`) returned a
`done` event carrying populated `sources` (with `parentDocId`,
`chunkIndex`, `path`, `startLine`/`endLine`, `excerpt`, `title`) and
`citations`. The deep-link data is complete on the wire; the Answer tab
discards it. **Move F: HIGH → VERIFIED; pure FE-render work.**

**U8 — latency reality check (new risk, the biggest surprise).** With AI
online, every search costs **~1.2–1.5s wall** (expansion + cross-encoder
~190ms; no caching observed on repeat queries); the lucene-syntax path is
~140ms. Two consequences: (a) the UI meta-line showing **"2ms"** while the
round trip is ~1.2s is a latency-display integrity defect (evidence for
Ext I's altitude/honesty framing — the displayed number is not the user's
experienced latency); (b) 570 §6.9's sub-100ms as-you-type bar is **not
remotely met by the current full-pipeline-per-keystroke model** — Goal 1's
performance boundary (§1.7) is not "don't regress a fast loop", it is
"the loop is already slow when AI is on, and Move G's progressive
disclosure + a staged cheap-pass/enrich-pass design is load-bearing, not
polish". This reframes Move G from cosmetic to structural.

**New residuals (honest limits of this pass):**
1. **Live-vs-source mismatch on chunk-merge gating**: the running worker
   returns `chunk-merge: skipped(SKIPPED_QUERY_SYNTAX)` even for
   absent/`'simple'` syntax, while `SearchPlanner.planChunkMerge` on `main`
   only skips for LUCENE (`SearchPlanner.java:252–255`), and the
   controller→adapter→`KnowledgeSearchEngine` routing provably parses
   absent → SIMPLE. Strongest hypothesis: the stack runs a stale worker
   dist (the documented `installDist` pitfall) or predates a recent merge.
   Re-verify chunk-merge behavior on a freshly built stack before any work
   relies on it; until then, treat the audit's "13 with chunk-merge
   executed" first-state as unexplained-but-superseded (the count delta is
   expansion's, per U1).
2. `excerptRegions` emission unverified (above).
3. The "2ms" display's source (which field the FE renders) not traced.

**Net:** the substrate is *richer and closer* than the theory assumed
(facets end-to-end, readiness reasonCodes already in the FE store, agent
sources live on the wire, rich hit metadata + detail tier) — three moves
upgraded (E, F, and Ext III to HIGH/VERIFIED). Two pieces are confirmed
greenfield-but-small (Ext I's per-facet altitude grammar; Move C's
operation layer), one stays interim-shaped (Move B via 570 §18 D3), one
diagnosis was corrected (Ext II's motivating instance was expansion
nondeterminism), and one new structural risk surfaced (U8 latency: Move G
is load-bearing). Implementation planning should start from this §1.8 +
570 §18 together.

### 1.9 Implementation outcome (2026-06-12) — Goal 1 shipped on `worktree-577-search`

All Goal 1 work (Moves B–G + Ext I–III, as corrected by §1.8) is implemented
on branch `worktree-577-search` (8 commits, P1–P8 + a harness fix), unit-
tested (FE suite green: 303 files / 2856 tests), gate-clean (presentation
suite + contrast matrix 32/32 AA both themes + execution-surface), and
**live-browser-validated** against the worktree FE (ui-shot Vite :5174 →
shared backend, read-only) — screenshots in `scripts/jseval/tmp/ui-shot/`
plus interactive validation via Chrome.

| Item | Shipped as | Live-validated |
|---|---|---|
| Move F (P1) | done-event `sources`/`citations` → inspectorState; the one `citationResolve` authority (resolver extracted from UnifiedChatView); MarkdownBlock weave + source chips | ✅ ask → 10 source chips → chip click → Preview highlights the exact cited lines (deep-link end-to-end) |
| Ext III (P2) | `readinessNotice` projection (state+cause+remedy); `<jf-op-button>` remedy / Open Health navigate | ✅ banner shows worded cause + "Force Rebuild" catalog button (consent click not exercised on the shared stack) |
| Ext I + Move D (P3) | `TRACE_FACET_ALTITUDE` (the new per-facet authority); user line + collapsed diagnostics; labeled per-hit chips; missing explain/hit-why CSS added (the run-on root cause) | ✅ "Keyword search · AI-expanded query" + collapsed "Pipeline details"; results own the hierarchy |
| Move G p1 (P4) | `processingTimeMs` = measured wall; fusion ms → `retrievalMs` (diagnostic); slow-search hint | ✅ "32 results · 1.3s" (was "2ms") |
| Move G p2 + Ext II (P5) | `buildSearchIntent` one-seam; staged quick (BM25-only override, ~10×) → refined; generation guard; provisional labels; `submitQuery` plugin API | ✅ "30 results · 42ms · quick results" then refined replacement |
| Move E (P6) | refined pass requests facets; chip row with counts; selections → the wire's EXISTING keyword filters (NO backend change — §1.8-era "backend gap" fully evaporated: `fileKind`/`mimeBase`/`language`/`metaAuthor` filters already parsed by the controller) | ✅ Type/Format/Language chips with real counts (chip-click narrowing not yet exercised live) |
| Move B D3 (P7) | `searchResultViewModel` typed projection; per-kind rows + glyphs; excerpt-first word-boundary snippets; **`highlight` + `link` roles** added via the role-catalog mechanism (tokens both themes, contrast gate extended, 32/32 AA) | ✅ kind glyphs, link titles, highlight marks, "…" snippets |
| Move C (P8) | `searchResultActions` registers open/reveal/copy-path through ContextActionRegistry at boot; `revealLocalPath` extracted (one invoke site); per-row "⋯"; operation/verdict layer DEFERRED (no destructive result action exists — the §18.3 D2 two-layer cut) | ✅ right-click menu shows registry verbs + the one surface-coupled action |

**Residuals / named follow-ups:** chunk-merge live-vs-source mismatch
(§1.8 #1) still unverified on a fresh stack (the shared stack stayed owned
by another session all session); image-row thumbnails deferred; facet
selections not yet pin-snapshotted; consolidating the source-chip template
with UnifiedChatView's into one component once Goal 2's slice lands;
ui-shot harness drift + banner-wording items in observations.md.

**Not yet done:** independent measured UX audit (auditor ≠ committer —
honor-system closure for presentation-authority work) and the merge to
`main` (deliberately held until that audit + coordination with the
parallel Goal-2 agent's merges).
> **Superseded by §1.10 (2026-06-13):** the merge DID land (Goal 1 merged at
> `d2c5129fa`; the durability gate at the §1.10 close-out) — the "merge held"
> note above is stale. Still owed: the independent measured UX audit.

### 1.10 Close-out pass (2026-06-13) — re-inspection findings + the durability gate

A live re-inspection of the shipped window (now merged on `main` since the §1.9
draft — Goal 1 landed at merge `d2c5129fa`) re-confirmed Ext I (the labeled
"Pipeline details" table + per-hit chips `Sparse (BM25) · #2 · 3.43`), Move E
(facet click 31→30 + selected chip + recount), Move C (the right-click verb
menu: Open in inspector / Open file / Reveal in Explorer / Copy path), Move G
(honest "·1.2s" latency + the live "Searching — the AI is working on your
query…" hint), Ext III (cause+remedy banner), and Move B (typed glyph rows +
link/highlight roles) all working. Three close-out items resolved:

- **URL query-restore — VERIFIED, not a regression.** A cold-boot reproduction
  at `?query=pipeline` correctly populated the input ("pipeline") and fired the
  staged search; a `git show` of the P4+P5 commit confirmed `restoreSearch`'s
  behavior was unchanged (only its comment). The earlier empty-state sightings
  were **environmental** — the shared backend was "Reconnecting…", so searches
  hung. No code change.

- **Durability gate SHIPPED (the §1.9 structural gap).** Goal 1 shipped the
  projections (570 ladder rungs 2–3) but no prevention gate (rung 4), so a
  future edit could silently re-introduce a second search-issuance path and
  bypass the Ext II one-intent seam. The **single-issuance-seam half of 570
  Move H** now lands: `governance/search-issuance.v1.json` +
  `scripts/ci/check-search-issuance.mjs` (mirroring `check-steering-arbitration`)
  — seam-integrity + single-issuer within `shell-v0`; teeth proven (a temporary
  second issuer exits 1); wired into ci.yml + the CLAUDE.md pre-merge list. The
  **full** Move H (coverage projecting from the search-as-a-mode catalog) still
  waits for Goal 3 / Move A, by design.

- **Honest scope corrections.** The §1.6 "#6 cryptic copy-button row" is
  withdrawn — the `MD/JSON/Paths/Ask AI` buttons already carry descriptive
  `title` tooltips. The "#9 non-core: 1 layout override badge" stays **out of
  scope** — it is app/shell chrome, not the search surface (and appears
  conditionally); never in Goal 1's implementation scope.

**Residuals carried forward.** The chunk-merge `SKIPPED_QUERY_SYNTAX`
truthfulness probe (extends §1.8 residual #1) stayed **blocked** this pass — the
shared backend was persistently hung (a same-origin `fetch` and the FE search
both timed out at 45s); re-probe on a stable/fresh stack. If the reason is
genuinely emitted for a SIMPLE query, the trace truthfully renders it and the
fix is backend (search-quality lane), not presentation. The independent measured
UX audit (auditor ≠ committer) and the source-chip consolidation with the agent
window remain owed; image thumbnails + facet pin-snapshots remain deferred.

### 2.0 Investigation verdict — the design exists in 565+561; adopt and extend it

**Code investigation** (2026-06-11, against `main` + live dev stack): the
agent window is `UnifiedChatView` over a substrate that is already, at the
content tier, the governed structure 565 theorized — and most of it is
shipped. One ordered run projection (`projectUnifiedThread` /
`projectLiveAgentActivity`, with run-segmentation a branded type only the
projector can mint); one step-presentation authority (`stepPresentation`,
branded) deriving tone/glyph/label/prominence from one `statusTone` mapper
and one `composeToolLabel` Display projection; one tool-call primitive
(`ToolCallCard`), one answer renderer (`MarkdownBlock` with a citation-weave
authority), one grounding-tier authority (`evidenceProjection`) — all held by
the `run-renderers` register. One control-intent seam (`dispatchRunControl`:
initiate / interject / halt) held by the steering-arbitration gate. Posture
is the one global autonomy store (561 P-D) consumed by graded chrome
(`agencyPosture`/`postureChrome`) — the live audit confirmed it echoes
coherently in all three chrome sites. The retrospective panel re-mounts
already-governed projections (Sessions ← `/api/chat/sessions`, Timeline ←
the unified action ledger, History ← ledger by correlation, Inbox ←
presence). Backend-side, the canonical run record exists (`AgentRunStore`
meta + append-only event log; sealed `AgentEvent` hierarchy), the approval
lattice has one issuance authority (`IntentGateEvaluator.agentGate`:
risk × autonomy × reversibility → gate behavior, with the C-4
irreversible-write floor), budgets are typed per-run token meters
(`AgentBudgetUpdate`), and — decisively for §2.2 below — **the done-event
already carries `sources` and per-sentence `citations`** (565 §3.A landed
the carrier: chunk-identified search evidence collected at `AgentDone`,
resolved by the agent citation resolver).

**Design-history investigation**: 565 is this window's correct-design theory
at the content tier (grounded answer §3.A, status tone §3.B, answer block +
one ordered timeline §3.C, risk-graded approval §3.D, composition/prominence
§12, step presentation §17, segmentation §26.A, steering §30) and is ~85%
shipped with its named deferrals (markdown-aware claim-weave; agent-path
evidence parity). 561 is the correct-design theory at the surface tier (one
interaction surface; modes as points on `autonomy × grounding × persistence`;
panels as re-mounts of governed projections; the `interaction-surface`
register+gate) — data tier shipped, surface tier designed and partially
built. Nothing in the live audits contradicts either; every defect found is
evidence that a 565/561 authority is **incompletely landed or
under-specified at one facet**, not that the theory is wrong.

**Verdict (explore-before-implementing, applied to design):** Goal 2 adopts
565 (content) + 561 (surface) as its base theory and does not restate them.
What follows is (a) the scope cut fitting them into this tempdoc's
sequencing, (b) three structural **extensions** where the live audits exposed
facets the base theory under-specifies, and (c) the evidence ledger binding
both audits' findings to the design.

### 2.1 The adopted spine, in one paragraph

A delegated run is **one canonical record** (the persisted event log of the
session) and the window is its governed projection: the thread is the one
ordered projection (live SSE rendering as the in-flight tail of the same
projection, never a second structure); every step's tone, glyph, label and
prominence derive from branded single authorities; evidence rides the
grounded-answer carrier from tool execution through `AgentDone` into the one
citation weave and sources pane, deep-linking to local passages; control
intent flows through the one steering seam; posture is one global store
consumed — never stored — by graded chrome; retrospective panels re-mount
governed projections of the same ledger. The breaking forms (a second run
projection, a hand-rolled status glyph, a re-derived grounding threshold, a
second steer/stop call site, an answer renderer fork) are already
unrepresentable-or-gated by the `run-renderers` + steering registers. The
extensions below add the three facets the audits showed this spine still
leaves free: **outcome**, **narration altitude**, and the **live-run
accountability record**.

### 2.2 Scope cut — Goal 2 is the window-internal facets; the mode axis closes in Goal 3

The agent already runs inside the one interaction surface — 561's "retire
`core.agent-surface`" re-pointing, the declarative mode catalog
(`autonomy × grounding × persistence`), the Documents/Structured/Agent tab
dissolution, and the `interaction-surface` register+gate are exactly this
tempdoc's Goal 3 (they are the same decision as 570 Move A / §18.4 D1 seen
from the other window). Goal 2's scope is everything that matures the run's
own presentation contract *in place* — all of it survives unchanged under
any Goal 3 outcome, because it binds the run's projection to the canonical
record, not to the surface's identity. (Mirror of Goal 1 §1.2.)

### 2.3 Extension I — **outcome is a first-class facet, distinct from lifecycle**

The live audit's most serious finding: a tool call that *failed* (schema
rejection — `string found, integer expected`) renders the identical
`✓ LOW · COMPLETED` chip as the call that succeeded, while the History tab —
a different projection of the same ledger — correctly shows it red and
`failed`. Two projections of one record disagreeing about the one fact that
matters is precisely the drift class the 553/561 register family exists to
kill; it survived here because the spine's status model has only a
**lifecycle axis** (proposed → pending → approved → executing → completed →
rejected) and lets *outcome* hide inside the result payload
(`OperationResult.success`), where a projection can — and did — drop it.

The correct structure: **the canonical tool-call facet carries outcome
(succeeded / failed / rejected / cancelled) as a typed axis alongside
lifecycle phase, and every status authority consumes both.** The one
status→tone/glyph mapper takes (phase, outcome); a terminal-phase
presentation that has not consumed outcome is unrepresentable at the type
level (the branded step-presentation is minted only from a record whose
terminal states require an outcome). The same axis flows to the run
summary ("2 steps · 1.3s" becomes outcome-aware: a run containing a failure
cannot summarize as unqualified success), to the retrospective History
(which stops being the *only* honest projection), and to the answer's
epistemic frame — an answer synthesized after a failed retrieval is a
different trust object than one after clean retrieval, and the projection
must be able to say so. **Breaking form made unrepresentable:** rendering a
terminal tool state from lifecycle alone. (The arg-validation rejection
*itself* — whether the tool boundary should coerce `"10"` → `10` rather than
burn an iteration — is an agent-loop robustness question outside the
presentation contract; tracked in observations, not here.)

### 2.4 Extension II — **the run narrates in acts at declared altitude, not in runtime phases**

The thread's step labels are backend-authored free strings at user altitude:
"Starting agent session", "Calling LLM" ×2 — the runtime's diary, not the
agent's narrative. 565 §17/§12.3.C solved *prominence* (primary / secondary /
ambient grades) but left the **label vocabulary** open: `AgentProgress`
carries prose minted in the backend, so process-speak flows straight to the
primary flow with nothing to stop it. This is Goal 1 Ext I's altitude cut
appearing in the run dimension, and the two should be understood as one
principle: **every facet of an execution record declares its audience
altitude, and user-tier rendering is projected through the Display
authority from typed references — never authored as free prose at the
emission site.**

Concretely at the conceptual level: progress events carry a typed phase
from a closed vocabulary plus operation references; the user-tier label is
composed by the same Display authority that already turns `core_search_index
+ args` into "Search · <query>" — so the thread reads as *what the agent
did* ("Searched the index for X — 10 hits", "Drafted the answer from 4
sources"), while runtime internals (iteration counters, LLM-call phases,
token phases) project at ambient/diagnostic altitude — present, collapsed,
subordinate. The same cut applies to the chrome the audit flagged: the risk
chip ("LOW") is a catalog fact whose user-tier projection must explain
itself (it is the *approval-lattice input*, rendered today as an unexplained
internal token); the History tab's raw operation IDs are the identical
defect in the retrospective projection. **Breaking form:** a backend-minted
free-string surfacing at user altitude; a catalog token rendered without its
Display projection.

### 2.5 Extension III — **the live run has one accountability record; it is not the retrospective**

Three different things answer to "Activity" in the live window (the
retrospective side panel, the budget drawer, a rail surface), the budget
projects as a bare figure ("Over budget +707" — no unit, no named ceiling,
no action), and the drawer's collapsed line renders the Auto *policy* in the
grammatical position of a live *status*. These are one structural gap: the
spine governs the **thread** (what happened, in order) and the
**retrospective** (the ledger, looking back), but the **live run's
accountability state** — budget consumed/ceiling/overrun, posture-in-effect,
pending approvals, liveness (575: every in-flight record has a live owner) —
has no declared record of its own. Each fragment is rendered ad-hoc, so each
invents its own chrome and they collide in namespace and grammar.

The correct structure: **one typed run-accountability record per live run,
projected at graded prominence, with remedies as operation references.**
Its facets: the budget (typed unit — tokens; named ceiling; honest overrun —
the projection already computes this; and its *meaning*: the backend treats
budget as a meter that ends iteration, not a hard gate — the projection must
say which), the posture in effect (as *policy* text, typographically and
grammatically distinct from status), pending approvals (the 565 §3.D graded
cards are projections of this record), and liveness (the 575 live-owner
authority). Prominence is state-derived: ambient chip when nominal,
escalated when attention-worthy (over budget, pending approval) — never a
pegged 100% bar with no consequence. Every remedy the record names — halt,
raise-the-ceiling, answer-an-approval — is an operation reference dispatched
through the one control seam (halt already is; the others join it), so an
over-budget signal is *actionable by construction*. And the naming collision
dissolves structurally, not editorially: the accountability record (live,
present-tense, attached to the run) and the retrospective (ledger,
past-tense, attached to the session history) are **two different records
and may not share a name or chrome**. The resume affordance is the same
principle at the session level: "continue vs fresh" is a *derived state* of
(last-session record × current-thread state) — a projection that cannot
render once a turn exists, not a dismissable card (the live audit showed it
pinned above an already-continued thread precisely because it is
event-shown, not state-derived).

### 2.6 Evidence ledger — both 2026-06-11 live audits, mapped to the design

First audit (window survey) + second audit (interactive: drawer toggles,
panel tabs, card expansion, DOM probes; browser-automated on the live
stack). Recorded as evidence of defect classes — the adopted theory plus
extensions dissolve them.

| # | Live finding (2026-06-11) | Defect-class reading | Dissolved by |
|---|---|---|---|
| 1 | Failed tool call (schema rejection) renders identical `✓ LOW · COMPLETED` to the success; History tab shows the same call red/`failed` | Outcome not a typed facet; two projections of one record disagree | Ext I (§2.3) |
| 2 | Citations `[1]`–`[4]` inert; DOM probe: raw text inside the markdown block, no elements | **CORRECTED by §2.9 (V1):** the grounded chain (weave + badge + chips + pane) is fully landed and live-verified; the inert render is the **zero-sources fallback** — that session's done-event carried no citable sources (the backend WARNs on exactly this). Residual defect: an ungrounded answer renders bare `[n]` with no honesty signal | §2.9 V1; Ext I's epistemic-frame facet (ungrounded honesty); zero-sources data condition root-cause |
| 3 | Step labels "Starting agent session", "Calling LLM" ×2; risk chip "LOW" unexplained (no title/aria anywhere); History tab shows raw `core_search_index` | Backend free-prose and catalog tokens at user altitude; Display projection bypassed | Ext II (§2.4) |
| 4 | "Over budget +707": no unit/ceiling/action; bar pegged 100%; posture *policy* text in live-status position | Live-run accountability state has no declared record; fragments invent chrome | Ext III (§2.5) |
| 5 | Three UI elements named "Activity" (retrospective panel, budget drawer, rail surface); panel toggle reads as a fourth posture | Two distinct records (live accountability vs retrospective) sharing one name | Ext III (§2.5) |
| 6 | Resume card pinned above an already-continued thread, across posture switches | Affordance event-shown, not derived from (session × thread) state | Ext III (§2.5) |
| 7 | Timeline tab: every navigation event twice at identical timestamps; Sessions tab: Resume button overlaps title | One ledger row projected twice (cause unconfirmed — §2.9 V6: not corroborated in 561/observations; root-cause before fixing) | 561 §2.3 re-mount discipline (one row → one projection); judgment-tier polish |
| 8 | Thread at ~40% of viewport with vast empty left region; answer wraps ~520px vs the declared 74ch measure; "Send & auto-run" the largest, lowest-information element; status bar icon-soup | Composition declared (565 §13, 74ch §3.C, StatusDeck `OverflowController`) but unlanded/untuned | 565 §12/§13 landing; judgment-tier residue (§2.7) |

**Keep (the audits' positive findings):** the collapsed "2 steps · 1.3s"
disclosure (hides the whole step timeline — correct shape); one card per
tool call with query + status visible; posture echo coherent across all
three chrome sites ("Send for review" / "Send" / "Send & auto-run");
a11y bones solid — the thread gutter is a real
`nav[aria-label="Run timeline — jump to a turn"]` with labeled per-turn
nodes, the dial is labeled "Agent autonomy", rail buttons labeled.

### 2.7 Boundaries and honest limits (Goal 2)

- **Answer quality is not a window concern.** The live answer conflated
  "testing strategy" with ranking strategy and padded a non-answer —
  retrieval/prompting work, tracked separately if pursued. What the window
  *does* owe (Ext I) is the epistemic frame: an answer after failed
  retrieval may not present as cleanly grounded.
- **The tool-boundary coercion question** (`"10"` vs `10` costing an
  iteration) is agent-loop robustness, not presentation — named here so it
  isn't lost, decided elsewhere.
- **Judgment-tier residue** (565 §16-equivalents): composition density, the
  send button's weight, status-bar label treatment (composes the governed
  `OverflowController` path), drawer default-open state — measured UX-audit
  discipline (honor-system per tier-register rows 30–31), not gates.
- **Known deferrals carried from 565 §10, named not silent:** the
  markdown-aware claim-weave re-implementation (ledger row 2's landing
  dependency); chunk-identity choice for raw-hit matching. 565 §6's
  decision that evidence/status authorities are discipline-plus-test, not
  hard gates, stands — Ext I's type-level outcome requirement is the one
  place this tempdoc asks for a stronger rung (576 ladder: unrepresentable
  via the branded-type seam already proven by `stepPresentation`).
- **The agent window stays kernel-owned** (569 §21.D): out of scope for the
  user-authored presentation inversion; it renders through the functional
  core. Nothing here changes that.
- **Streaming cost is the window's performance contract** (the analog of
  Goal 1's keystroke loop): the ordered-projection-with-live-tail design
  exists precisely so streaming never grows a second cheaper path; the
  extensions add facets to records, not work to the stream path.

### 2.9 De-risk audit (2026-06-11) — every load-bearing claim verified against source + a live wire capture

Method (the 570 §18 discipline applied to Goal 2): each §2.0–§2.5 claim was
re-verified primary-source (the theory had been synthesized from subagent
reports — the `audit-without-test` exposure), then a fresh agent run was
driven on the live stack (model online) with the SSE stream captured
verbatim at the fetch boundary. Verdicts:

- **V1 (CONFIRMED, stronger than claimed) — the grounded-answer chain is
  fully landed and works live.** The captured `done` payload carried
  **7 sources + 3 per-sentence citations** (similarity + sourceIndex), and
  the answer rendered the complete 565 §3.A design: woven cite-sentences
  with grounded superscript marks (`cite-ref cite-grounded`), the
  "Grounded · 3 of 5 sentences" badge, the collapsible "Sources · 7" chips,
  the docked sources pane. The first audit's inert `[1]–[4]` is the
  **zero-sources fallback branch** (`UnifiedChatView.renderUnifiedItem`:
  `attributes.sources` empty → `format="plain"`, no weave), and the backend
  already WARNs on its cause ("search hits but none citable — no
  parentDocId"; `AgentSession.collectGroundingSources`). So ledger row 2's
  work is NOT "land the weave" — it is (a) root-cause the zero-sources data
  condition that session hit, (b) Ext I's honesty facet: an ungrounded
  answer must not render bare `[n]` markers with no signal.
- **V2 (CONFIRMED, cheaper than claimed) — Ext I is presentation-tier
  only.** Outcome already survives the whole pipe: `tool_exec_completed`
  serializes `success` explicitly (`AgentController.toolCompletedPayload`),
  the FE controller stores it on the ToolCall record and even branches on
  it for the Effect Journal (`AgentSessionController.onToolExecCompleted`)
  — only `ToolCallCard`/`statusTone` render from lifecycle alone. Live
  reproduction: the captured run's first call was `success: false` (and
  rendered ✓ COMPLETED), second `success: true`. `outcomeToTone` already
  exists as a registered tone symbol in `run-renderers.v1.json` — the
  outcome axis has a reserved slot. No wire change needed.
- **V3 (CONFIRMED, smaller than claimed) — Ext II's vocabulary problem is
  tiny.** Exactly TWO `AgentProgress` emission sites exist (`init` /
  "Starting agent session", `llm_call` / "Calling LLM"), and the wire
  already carries the machine token in `phase` alongside the prose
  `message` (live-verified). The FE renders `message`
  (`runStepPresentation` label) — switching the user-tier label to project
  from `phase` via the Display authority is the whole move; the closed
  vocabulary already exists de facto.
- **V4 (CORRECTED — real bug found) — the budget ceiling derivation is
  wrong for multi-iteration runs.** `AgentBudgetUpdate.tokensConsumed` is
  **per-phase** (the `llm_response` emission sends that single call's
  `usage.totalTokens()`; `AgentLlmCaller`), but `budgetProjection.ts`
  documents and relies on the invariant `consumed + rawRemaining ===
  initialBudget` — which only holds on the first update. The projected
  `ceiling`/`pct` are therefore wrong after iteration 1 (live capture: 6
  budget events across 3 iterations, per-phase values). Ext III's
  accountability record must include a **correct cumulative/ceiling
  emission** (or fix the FE to accumulate) — a small wire-semantics fix the
  theory had assumed away. The drawer meanwhile already grew a lifecycle
  line ("2 turns · 3 iterations · 2 tools · DONE") since the first audit.
- **V5 (CONFIRMED) — no gate fights the extensions.** The `run-renderers`
  register constrains import/mount SITES, not behavior, and every Ext
  touch-point (`statusTone.ts`, `runStepPresentation.ts`, `ToolCallCard`,
  `UnifiedChatView`) is already a registered site. The steering gate
  requires exactly one dispatch site per directive — Ext III's
  raise-budget/approval remedies enter as NEW directives through
  `dispatchRunControl` + a `steering-surfaces.v1.json` row (a designed
  discovery-step, not a fight).
- **V6 (CORRECTED) — the timeline-duplication "known 561 carry-forward"
  attribution was a subagent claim not corroborated by 561 or
  observations.md; downgraded to cause-unconfirmed (root-cause first).**
- **New systemic observation:** the `limit:"10"` schema rejection recurred
  in the fresh run (first call of the session, again) — it is not a one-off;
  every session burns iteration 1 on it. Strengthens (but stays outside)
  the §2.7 tool-boundary coercion item.

### 2.10 Implementation record (2026-06-12, worktree `577-agent-window`)

All three extensions + the ledger defects implemented by extending the
registered authorities in place (no new primitives, no new registers; two
register rows added as designed discovery-steps). Branch
`worktree-577-agent-window`.

- **Ext I (outcome axis)** — `presentedToolStatus(status, success)` added to
  `statusTone.ts` (registered as a tone symbol in `run-renderers.v1.json`);
  consumed by `stepPresentation` (tool items read `attributes.success`),
  `ToolCallCard` (status word; failed terminal cards stay expanded), and the
  run-trace summary (`N steps · M failed · t s`). Reload path verified:
  `AgentInteractionMapper` already persists `success` on TOOL_ACTIVITY.
  Ungrounded honesty: the zero-sources assistant branch renders an ambient
  "citations could not be verified" note when `[n]` markers have no grounding.
- **Ext II (narration altitude)** — progress labels project from the typed
  `phase` token through a closed map in `runStepPresentation`
  (`init`/`llm_call`; prose message = unknown-phase fallback); `phase`
  threaded through `ConversationEntry` → live projection attributes. Risk
  chip self-explains via the existing `becauseLine` authority (hover).
  History rows lead with `present({kind:'operation'})` labels (wire id
  demoted to detail). Dispatch-serializer drift folded in: `severity` now
  rides `ToolIteratingShapeRunner` + `AgentRunShape` like the legacy path.
- **Ext III (accountability record)** — wire: `AgentBudgetUpdate` gains
  run-cumulative `totalTokensConsumed` (both emitters, BOTH serializers,
  `AgentRunShape` descriptor, fixture + generated `core-agent-run.ts`
  regenerated, SSE contract test pins it); `budgetProjection` prefers it
  (the §2.9 V4 ceiling fix; per-phase fallback for old records, unit-pinned).
  Drawer renamed "This run" with policy text grammatically marked
  ("Policy: …"), token units + ceiling stated, over-budget escalation in the
  collapsed summary + remedies [Add tokens]/[Stop run] dispatched through
  `dispatchRunControl` (`raise-budget` is a new seam directive + register
  row; backend `POST /api/chat/agent/budget` → `AgentSession.addBudget`,
  mirroring the autonomy/steer endpoints). Panel toggle + header renamed
  "History", spaced off the posture dial. Resume card render is derived
  (`recentSession × thread empty`), not event-dismissed.
- **Ledger defects** — Timeline duplication ROOT-CAUSED + fixed: the FE
  posts each journal effect to the backend log under `fe-effect:<id>`
  (`startEffectIngest`) and `unifiedActivity` merged both copies; the
  designed id-dedup was never implemented — now it is (backend ingested row
  stands), unit-pinned. Sessions Resume overlap fixed (flex `min-width:0` +
  shrink-protected action). Zero-sources condition CLASSIFIED from persisted
  records: the old run's hits were whole-document (no `parentDocId` —
  0 of 5), the new run's chunk-identified (7 of 10) — result-composition,
  not regression; whole-doc-hit citability logged as a design follow-up in
  observations.
- **Verification** — FE: typecheck + full unit suite green (299 files /
  2823 tests, incl. new outcome/budget/dedup/card pins). Backend:
  `:modules:app-agent` (+ new `AgentSessionBudgetTest`), `:modules:app-services`
  (conformance + fixture), `:modules:ui` (SSE contract), `:modules:app-agent-api`
  green. Gate battery green (run-renderers, steering-arbitration,
  presentation/a11y/layout/adaptive/contrast suites) — except
  `check-accent-as-text`, which fails on two files untouched by this branch
  (pre-existing on `main`; logged in observations).
- **Live E2E (2026-06-12, merged to main, Vite HMR against the running
  stack, model online; browser-driven).** Verified live: act labels
  ("Session started" / "Thinking" — process-speak gone); risk chip
  tooltip ("Risk tier LOW. Auto mode — LOW-risk actions run
  automatically."); drawer "This run · *Policy: Auto-running · confirming
  irreversible writes* · Over budget +420 tokens" with the expanded row
  "Over budget by 420 tokens (granted N) [Add 4096 tokens] [Stop run]";
  panel renamed History with clean Sessions rows (overlap gone); History
  rows lead with Display labels + ok/failed; **Timeline duplication gone
  live** (each "Navigate to Chat" once); resume card correctly shows on
  an empty thread and cannot render on a populated one; the grounded
  answer chain unaffected (woven marks + "Grounded · 2 of 5" + Sources ·
  10). A failed resume of a terminated session rendered as an honest ✕
  error row.
- **Backend-restart validation (2026-06-12, user-approved takeover; new
  jar + model reactivated).** The pending items all verified live on a
  fresh agent run (SSE captured at the fetch boundary): every
  `budget_update` carries `totalTokensConsumed` and the drawer ceiling is
  cumulative-derived ("Over budget by 1071 tokens (granted 3840)" —
  vs the legacy-fallback 2381 the old build showed); dispatch-path
  `progress` now carries `severity`; and **the failed-call card rendered
  live**: the limit-bait query reproduced the `"limit":"10"` rejection —
  "Search Index … ✕ LOW · FAILED" (red, stays expanded, error output
  visible), retry card ✓ COMPLETED, run summary "2 steps · 1 failed ·
  1.5s", and the grounded answer (10 sources, 4 cites) wove beneath it.
  One real finding: the raise-budget POST round-tripped to the new
  endpoint but returned a correct 404 — the backend evicts a FINISHED
  session, so remedies on a DONE run were inert. Fixed forward
  (`87328ee5a`): remedies render only while the run's stream is in
  flight (`runInFlight` — control chrome attaches to the LIVE run,
  which is the Ext III principle applied to its own remedy row). The
  mid-flight raise (200 path) is covered by `AgentSessionBudgetTest` +
  the endpoint handler; a live mid-run button press wasn't reliably
  automatable and remains the one unexercised live path.
- **Also observed (logged, not fixed):** after a full page reload the
  drawer can show the PREVIOUS session's final budget in a fresh
  conversation; and a free-chat answer fabricates `(n)`-style citation
  markers with no grounding (the agent path's honesty note does not
  cover the free-chat shape).

### 2.11 Post-validation issue ledger (2026-06-12) — what the window still gets wrong

Compiled after the full implementation + two live-validation passes.
Ordered observed-fact → structural → theoretical; each row names its class.

| # | Issue | Class | Evidence |
|---|---|---|---|
| 1 | Sessions tab renders Resume on EVERY session; resuming a finished one 500s (backend evicts terminal sessions; `resumable` exists on the wire, unconsumed) | Affordance rendered without consulting run lifecycle — the same class as the raise-budget 404 (§2.10), now seen TWICE | observed live |
| 2 | Over-budget is discovered post-mortem: the loop terminates at the iteration boundary, so the user meets "Over budget" on a finished run where no remedy can act (the §2.10 fix hides the buttons there — honest, but the decision window is structurally near-empty for short runs) | Budget is a terminal meter, not a decision gate | observed live |
| 3 | Evidence durability across reload is inconsistent: a previously-grounded answer rendered plain + uncited-note after resume (record path lost sources the live path had) — 561 P-A non-divergence violated on ≥1 path | Persistence gap, made visible (not caused) by the Ext I note | observed live |
| 4 | Cross-mode trust blur: a free-chat answer fabricates `(n)` citations and renders in the IDENTICAL answer bubble as a grounded agent answer — Goal 3 constraint 3 is violated *inside* the window today | Epistemic provenance is not a declared facet of the answer | observed live |
| 5 | Stale previous-session budget after hard reload; journal popover overlaps/intercepts the posture row (574 transient-arbitration miss); a mode-tab click can silently not take effect; History rows label as `Core_search_index` (no catalog `labelKey` for agent tools) | Assorted: per-run state outliving its run; transient placement; mode/deeplink state; Display-authority data gap | observed live |
| 6 | Narration vocabulary is honest but empty: two phases → "Thinking / Thinking / Thinking"; the most important control-flow story (failed call → corrected arguments → retry) is never narrated, only inferable from expanded cards | The closed vocabulary exists; the loop's DECISIONS are not first-class narratable events | structural |
| 7 | Partial grounding has no explanation tier: "Grounded · 2 of 5 sentences" cannot answer "why are 3 uncited?" (padding? whole-doc hits not citable — the §2.9 V1 condition? threshold?) | The grounding verdict carries no reason codes (the search window got "Why this result?"; the answer plane has no "why uncited?") | structural |
| 8 | The approval/posture machinery is the least-validated surface: every audit ran Auto×LOW; inline-confirm / typed-confirm ceremonies, Watch-mode behavior, and the C-4 floor have had zero presentation audit | Validation debt (the failed-as-✓ bug lived in the MOST exercised path; the least exercised is where the next one hides) | structural |
| 9 | Iteration 1 burned every session on the same `"limit":"10"` schema rejection (reproduced 3×) | Agent-loop robustness (out of presentation scope; observations) | observed live |
| 10 | Scale unproven: retrospective tabs load whole lists (no pagination); `budgetUpdates` grows unbounded; timeline+spine recompute per streaming chunk | Engineering debt | theoretical |
| 11 | The new explanation tier rides on hover: risk-chip `title` tooltip is keyboard/screen-reader-invisible; drawer escalation has no live-region announcement; new labels bypass i18n | A11y/i18n of the Ext II tier | theoretical |
| 12 | Layout economy still unlanded: ~40% thread width, 74ch measure unmet, send-button weight, status-bar icon soup (565 §12/§13 declared, untuned) | Judgment-tier residue; first targets for the owed independent measured audit | observed (audit row 8) |

### 2.12 The correct long-term design for what remains (theorized 2026-06-12)

**Investigation verdict first (explore-before-implementing):** every §2.11
structural row already has its substrate in the codebase; nothing needs a new
kernel. `LifecycleState` is a typed enum with `isTerminal()` and an existing
held-gate state (`WAITING_APPROVAL`) backed by the approval-gate machinery
(per-call futures + the one authorization ceremony); `resumable` is already
on the sessions wire; 575 contributes the liveness-owner authority pattern;
561 §2.2's mode catalog already declares **grounding as an axis**; the
grounding-tier authority (`evidenceProjection`) and the coverage verdict
exist; Goal 1 Ext I/III established the reason-code-vocabulary-at-two-
altitudes pattern on the search side. The four moves below extend these — no
replacement.

**Move 1 — affordances are projections of the run's lifecycle (the rule the
404/500 class demands).** Twice now a per-run affordance rendered without
consulting the state it acts on (raise-budget on a DONE run; Resume on an
evicted session). The correct structure: every per-run affordance is a
*projection of (capability × lifecycle)* — the steering register's directive
rows each declare the lifecycle predicate under which the directive is
dispatchable (halt/interject/raise-budget ⇒ non-terminal; resume ⇒
`resumable`), and the render seam takes the typed lifecycle facet alongside
the directive, so an affordance without a lifecycle predicate is
unrepresentable. The §2.10 `runInFlight` gate is this rule's first instance,
hand-rolled; the move makes it the register's grammar. It also dissolves
§2.11 #5's stale budget: the accountability record is keyed by run identity
and *dies with its run* — a fresh conversation has no record to project.

**Move 2 — budget becomes a held gate, not a terminal meter.** The window
cannot make over-budget actionable while the loop's only budget behavior is
"terminate at the boundary." The correct structure reuses the held-gate
machinery the loop already has for approvals: budget exhaustion at an
iteration boundary parks the run in a sibling held state (the budget
analogue of `WAITING_APPROVAL`), emits the same kind of pending event the
approval ceremony consumes, and the over-budget escalation row becomes the
*decision point* — continue +N (the existing raise endpoint), finish
gracefully, or stop — while the run is genuinely alive and waiting. An
*approaching-budget* facet (the projection's existing bands) escalates the
ambient chip before the gate is reached. This is the design under which Ext
III's remedies stop being decorative; posture can grade it (Auto may
auto-finish-gracefully; Watch always parks).

**Move 3 — the epistemic frame is a declared facet of the mode, projected
onto every answer.** The free-chat fabricated-citations defect is not a
free-chat bug; it is the absence of provenance as a first-class facet. 561
already declares grounding as a mode axis — the correct structure is that
every conversation shape's declared grounding class (deterministic-index /
grounded-LLM / ungrounded-LLM) plus the run's *actual* grounding outcome
project into one answer-frame authority (extending `evidenceProjection` /
the coverage verdict): a grounded answer carries its marks and badge; an
ungrounded mode's answer is FRAMED as ungrounded by construction — and
citation-shaped text inside it cannot render as bare credible markers (the
Ext I note generalized from a special case to the frame's ungrounded arm).
This is Goal 3 constraint 3 ("the LLM must not borrow the index's
credibility") implemented window-internally, ahead of unification — and it
subsumes §2.11 #3: the frame derives from the RECORD's evidence, so a
record that lost its sources renders honestly as what the record supports.

**Move 4 — the loop's decisions are narratable events with reasons.** Two
extensions of the same closed-vocabulary discipline: (a) the phase
vocabulary grows at the EMISSION site to cover the loop's control-flow
decisions — retry-after-validation-failure, handoff, budget-gate-held,
graceful-finalize — so the thread narrates "Retried with corrected
arguments" instead of two cards the user must diff; (b) the grounding
verdict carries per-sentence *reason codes* from a closed vocabulary
(no-match-above-threshold / source-not-citable / unsupported-claim), giving
the answer plane its "why uncited?" — the exact mirror of Goal 1 Ext I's
"why this rank?" altitude cut, and deliberately the SAME
one-vocabulary-two-altitudes pattern as the search trace's reason codes (a
Goal 3 convergence bridge bought early). Accessibility is part of this
move's contract, not an afterthought: an explanation tier that exists only
on hover is at the wrong altitude for keyboard/AT users — explanations
project into focusable disclosures.

**Boundaries (honest limits).** §2.11 #8 is validation debt, not design —
the approval ceremonies need a dedicated live audit pass (and remain part of
the owed independent measured audit, with #12's layout economy). #9 is
agent-loop robustness, out of presentation scope. #10 is engineering debt
with known shapes (pagination, bounded buffers) — no design decision needed.
The free-chat shape's *prompt-side* behavior (whether to discourage the
model from emitting citation-shaped text at all) is a prompting question
Move 3's frame makes safe either way.
- **Honor-system closure note:** presentation-authority closure expects an
  independent, measured (axe/contrast-oracle), live-verified whole-screen
  UX audit by an auditor ≠ committer — NOT self-certified here; flagged
  for a second agent after the backend restart validates the pending
  items.

### 2.13 Second-wave issue ledger (2026-06-13) — classes the §2.11 ledger did not name

Surfaced by a cross-window pass (the search-window agent, after Goal 1
shipped), examining the agent window's *contracts* rather than its rendered
state. These are NOT in §2.11 and are NOT addressed by §2.12's four moves —
they are a second wave at a deeper altitude (the run-as-entity, the run's
resources, content provenance beyond the answer-frame). Each row names its
class and the substrate the investigation found already present.

| # | Issue | Class | Substrate found (extend, don't build) |
|---|---|---|---|
| 13 | The window has no model of the **run-observer relationship**: zero observers (close the tab mid-Auto-run — does the loop continue / park / die? where do Watch-mode approvals go with nobody watching?) and two observers (same session in two tabs — two singleton controllers, two SSE consumers, two steer surfaces) are both undefined; the catch-up card implies a "while you weren't looking" concept with undefined semantics | The window conflates *the run* with *my live view of the run* | `getAgentSessionController` is a **singleton** `_ctrl` (one observer per JS context — the conflation); 550 federated ledger keyed by `originator` + `correlationId` (session join key); `LifecycleState.isTerminal()`; `resumable` on the sessions wire; 575 liveness-owner authority |
| 14 | **Context-window honesty**: the thread renders the full history as if the model still sees all of it; on long runs older turns fall out of the prompt while still rendering — the window asserts a shared memory that may not exist, and marks no *horizon* | A run-resource the window must meter (cognitive/memory) is unprojected; budget is the only token meter and it is *economic* | `AgentSession.promptTokensConsumed` is tracked but routed only to `budgetRemaining`; `AgentLoopService.truncateForContext` (Layer-2 hard cut) already happens, unnarrated; no `n_ctx`-utilization facet on the wire |
| 15 | **Mid-run input is silently swallowed** (`if (!text \|\| this.isStreaming) return`) — the composer and the steer/interject seam are two channels with no declared contract; and **posture scope is undeclared**: the dial reads as per-run chrome but writes the *global* 561 store, so "Watch for this run" re-postures every run | Input channels + their binding scope are not declared | `UnifiedChatView.ts:3093` composer guard; `dispatchRunControl`/`steer` is the second channel; posture is the global 561 store consumed (not stored) per §2.1 |
| 16 | **"Undo all AI actions" claims a totality the journal can't guarantee**: actions without inverses, user edits interleaved since the agent acted (compensation without conflict detection), and granularity collapse (one button erases the per-action decision — the most irreversible-feeling control on screen) | Reversibility is not a declared, per-action, conflict-aware facet | The 550 ledger already tracks undo-support per op (`getUndoableOperation`/`markUndoableOperation`, `executionId`); non-undoable ops are known and render as two rows — the *knowledge* exists, the bulk control flattens it |
| 17 | **The agent's capability/authority space is invisible** until a run trips over it: no projection of *what tools the agent has, at what risk tiers, which need approval under the current posture*; users can't calibrate trust before delegating (and §2.11 #8's ceremony validation-debt persists partly because ceremonies are unreachable by inspection) | The agent's verb-space is unprojected — the exact Goal-1 Move C gap, other window | The operation catalog + risk tiers + `evaluateIntent` lattice exist; the window reads only coarse `capabilities.{rag,extract,chat}` booleans, never the catalog inventory; Goal-1 Move C is the proven projection pattern |
| 18 | **Text lineage is not a render facet**: agent-authored prose, tool-returned document excerpts (`ToolCallCard` renders `tc.output` as a raw dump), and runtime text render in near-identical typography — a corpus excerpt containing citation/instruction-shaped text reads as the agent's own claim | Provenance-of-text beyond the answer-frame (Move 3 covers grounded-vs-ungrounded *answers*, not quoted-vs-authored *spans*) | `ToolCallCard.tool-output`; the structured-search-evidence path (561 #6) already special-cases one tool's result — the seam to generalize |
| 19 | **Time is anchored nowhere**: turns carry no visible timestamps; resumed sessions render yesterday + today seamlessly; run/session boundaries (resume points, backend restarts — §2.11 #3's evidence-loss seam) are invisible in a thread that reads as continuous | Temporal anchoring + run/session boundaries are not render facets | The 550 ledger carries `occurredAt`; the thread projection drops it; session identity exists (`correlationId`) but no boundary marker projects |
| 20 | **Recall is fragmenting into four surfaces** — catch-up card, History tab, Inbox tab, journal popover — each growing its own dismissal/"seen" state around one concept | Representation-drift (553) in its earliest, cheapest-to-stop form | The **one recall authority already exists**: the 550 federated log (FE effects now *ingest* into the one backend log under `fe-effect:<id>`; `unifiedActivity`/`openActionLedgerStream`); `AgentActivityPanel` already projects from it — the divergence risk is a per-surface "seen" cursor, not a forked store |

### 2.14 The correct long-term design for the second wave (theorized 2026-06-13)

**Investigation verdict (explore-before-implementing):** as in §2.12, every
row's substrate is already present — the singleton controller, the federated
550 ledger, `promptTokensConsumed`, `truncateForContext`, the operation
catalog + risk lattice, the journal's undo-support tracking, `occurredAt`,
`LifecycleState`/`resumable`/575 liveness. Nothing needs a new kernel. And
the eight classes are not eight features: they are **three structural
generalizations of the three moves §2.12 already made**, plus Move 4 as the
cross-cutting channel. §2.12 fixed the run's *content* facets (outcome,
narration, accountability) at the altitude of a singly-observed live run;
the second wave lifts the same authorities one level — to the run *as an
observed entity*, the run's *resources*, and *all* rendered content's
provenance.

**Root I — the run is a first-class observed entity; the window is a
projection of (run × observer-relationship), not the run itself.**
*(Generalizes §2.12 Move 1: "affordances are projections of capability ×
lifecycle" lifts to "the whole window is a projection of the run entity as
seen by this observer.")* The deep defect under #13/#19 (and the posture-scope
half of #15, and #20) is that the window **conflates the run with the live
view of it** — the singleton `_ctrl` *is* that conflation. The correct
structure makes the run a backend-owned entity with identity, a lifecycle
(575/`LifecycleState`), a resource ledger (Root II), an accountability record
(§2.5), and a *set of observers*; the window **attaches** as one observer
carrying its own last-seen cursor over the one 550 ledger. Consequences that
become representable by construction: **zero observers** is a declared run
state (the loop's behavior with no attached view — continue / park at the
next gate / die — is a *declared policy of the run*, graded by posture, not
an accident of whether a tab is open; Watch-with-no-watcher parks rather than
deadlocks); **N observers** share the one backend run and each project it
independently (the steering register already guarantees one *dispatch site*;
this guarantees one *run authority* behind N views); **recall (#20)** is the
observer-relationship's "what changed since my cursor" — ONE seen-cursor per
observer over the one log, with the catch-up card / History / Inbox / popover
as projections of *that*, never four cursors (kills the 553 drift before it
sets); **boundaries + time (#19)** are facets of the run/session entity
(`occurredAt`, `correlationId`), so a thread is a projection of a *sequence of
run-entities* and their seams (resume points, restarts) render by
construction; **posture scope (#15)** is declared on the binding — what binds
to *the run* vs *the session* vs *the app global* is a property of the entity,
so "Watch for this run" is representably different from re-posturing the
global store. *Goal-3 load-bearing:* the observer model must be settled before
unification — a merged search+agent window is inherently multi-panel and
multi-view of possibly-shared runs; inheriting the conflation doubled is the
failure mode. *Honest limit:* zero-observer continuation and N-observer SSE
fan-out are **backend behavioral/transport decisions**, not pure projection
(named like §2.9's chunk-identity gap) — the presentation contract is correct
regardless, but the backend must declare the run's no-observer policy and
support reattach.

**Root II — the run's resources are a metered family; budget is one meter,
context-memory is the missing sibling, compaction is a narrated event.**
*(Generalizes §2.12 Move 2's held-gate from the budget meter to the
*resource family*.)* #14's defect is that the only run-resource the window
projects is budget — an *economic* meter — while the model's finite context
is a *cognitive* meter that is consumed, truncated (`truncateForContext`),
and never shown, so the thread asserts a memory that may not exist. The
correct structure: the run projects a typed set of **resource facets** from
the one usage authority (`promptTokensConsumed` already exists; today it is
*mis-routed* to budget alone) — economic budget and context-window
utilization are siblings, not the same number; the thread renders a **horizon
marker** distinguishing in-context history from history the model has
compacted out; and a compaction/truncation **becomes a first-class narratable
event** ("compacted earlier turns to stay within the model's memory") rather
than a silent backend cut — which is exactly §2.12 Move 4's channel, so the
two moves share one mechanism. Move 2's *held-gate* pattern then applies to
the cognitive meter too: approaching the context limit can park or summarize
under a posture-graded policy, the way budget exhaustion parks. *Dissolves:*
#14 entirely; gives the budget chip (§2.10) its honest sibling so the user
distinguishes "ran out of money" from "ran out of memory."

**Root III — provenance and authority are declared facets of *all* rendered
content, not visual conventions.** *(Generalizes §2.12 Move 3's epistemic
answer-frame from the answer to the whole surface.)* Move 3 declares whether
*the answer* is grounded; the second wave shows three more provenance/authority
axes the window renders by convention, not declaration:
- **Text lineage (#18):** every rendered span carries its source — agent-
  authored / corpus-quoted / runtime — as a render facet, so a tool-returned
  excerpt is framed as *quoted from your documents* and citation- or
  instruction-shaped text inside it can never render as the agent's own
  credible claim (the display half of prompt-injection safety; the search
  window's `evidenceProjection` already frames corpus excerpts — the seam to
  share).
- **Action reversibility (#16):** each accountability-record entry declares
  its *reversibility* (the journal already knows undo-support per op) and its
  *conflict status* (was the target touched since the agent acted); "undo all"
  becomes a **projection over per-entry reversibility** — honest about
  partial-undoability (non-invertible actions are shown as such, not silently
  skipped) and conflict (a user-edited target is flagged, not blindly
  reverted), never a totality claim. The bulk control stops being the most
  irreversible thing on screen.
- **Authority space (#17):** the agent's verb-space — the operation catalog ×
  risk tiers × current posture — projects into "what this agent can do, and
  what will ask first," the exact agent-window analogue of Goal-1 Move C
  (which projected the *result's* verb-space through the same catalog). Users
  calibrate trust before delegating; the §2.11 #8 ceremony surface becomes
  reachable by inspection, not only by tripping it.

These three axes plus Move 3 are one frame: **the window declares the
epistemic/authority status of everything it shows** — answer grounding (Move
3), text lineage, action reversibility, agent authority — extending one
authority (`evidenceProjection` + the catalog + the journal's
reversibility-knowledge) rather than four bespoke treatments. *Goal-3
load-bearing:* text lineage and the epistemic frame must hold *across modes*
before search and agent unify (constraint 3 — "the LLM must not borrow the
index's credibility" — is a cross-mode provenance guarantee).

**Move 4 is the shared channel, restated.** §2.12 Move 4 (the loop's
decisions are narratable events with reason codes) is not a fourth root —
it is the *emission channel* all three roots feed: attachment/detachment and
zero-observer parking (Root I), compaction and budget/context held-gates
(Root II), and reversibility/conflict outcomes (Root III) are all
control-flow events that narrate through the one growing closed vocabulary at
the declared altitude, with the reason-code "why?" disclosure focusable (not
hover-only). One narration authority carries the second wave's events as it
carries the first's.

**Boundaries & honest limits (second wave).**
- **Backend decisions, not projection:** the run's zero-observer policy and
  multi-observer SSE fan-out (Root I), and emitting context-utilization vs
  `n_ctx` as a distinct wire facet (Root II), are substrate work the
  presentation contract *names but does not perform* — flagged explicitly,
  per the §2.9 / §1.8 honest-residual discipline, so they are not silently
  assumed solved.
- **Prompt-side vs display-side:** whether the model is *discouraged* from
  emitting citation-shaped text in an ungrounded mode is a prompting
  question; Root III's text-lineage frame makes the *display* safe either way
  (the display guarantee does not depend on model compliance).
- **Sequencing:** Roots I and III are Goal-3 prerequisites (settle the
  observer model and cross-mode provenance before merging the windows); Root
  II and the recall-cursor unification (#20) are window-internal and
  shippable independently. None require a new kernel; all extend §2.12's
  moves and the 550/561/565/575 authorities.
- The independent measured UX audit (§2.11 #12, §2.12 closure note) and the
  approval-ceremony validation debt (§2.11 #8) remain owed and are not
  discharged by this theory pass.

### 2.15 De-risk pass (2026-06-13) — §2.14 claims verified against source

A confidence-building pass (read-only code probes; the shared stack stayed
owned, so no live experiment) run before any §2.14 implementation, in the
§1.8 mould. **Where §2.15 disagrees with §2.13/§2.14, §2.15 wins**
(dated-history rule). The headline: **no root is invalidated, two premises
are corrected, and the FE-projection half of each root is better-supported
than theorized — but each root also has a genuine backend-substrate half
that is new work, not projection, now named precisely.**

**V1 — Root II's *mechanism* is CORRECTED (premise partly wrong).** There is
**no app-side conversation-history compaction**: the only message trim in
`AgentStepRunner` is a handoff-specific one (`AgentStepRunner.java:376`);
`truncateForContext` is a *per-tool-result* char cap (`MAX_TOOL_RESULT_CHARS`,
referenced from `SearchTool.java:39`), not history-level. So history
**accumulates unbounded** and is sent every iteration until the llama-server
`n_ctx` boundary (where llama.cpp may context-shift oldest turns *server-side*,
invisibly, or the call fails). §2.13 #14's "older turns silently compacted out"
is reframed: the app neither compacts nor meters; the boundary behavior (shift
or fail) is unmanaged and unshown. **Root II's direction survives and
strengthens** (context-memory is not just unmetered but *unmanaged*); its
narratable event is **context-pressure / boundary**, not "compaction" (there is
nothing app-side to narrate today — the event appears when management is added).

**V8 — Root II's meter is FEASIBLE (favorable).** Each LLM call returns
`usage.promptTokens()` (the prompt size *that* iteration ≈ current context
occupancy) and it is already emitted on the budget event
(`AgentLlmCaller.java:206–227`); the `n_ctx` denominator is parseable from
llama-server `/props` (the `LlamaServerPropsParsingTest` path). So the horizon
meter (latest-call promptTokens ÷ n_ctx) needs the two existing halves
*joined* into a utilization facet — a bounded gap, not a blocker. (Corrects my
worry that `promptTokensConsumed` was cumulative-only: it is *also* per-call.)

**V2 + V-tools — Root III's authority source is CONFIRMED, and #17 is nearly
pure FE-render (favorable).** The agent loop is *driven by* `OperationCatalog`
+ `RiskTier` (`AgentLoopService.java:753`, `RiskTier` imported); `AgentToolEmitter`
is the model-visible projection seam; and `/api/chat/agent/tools`
(`handleListTools`) **already emits per-tool `tier` (CORE/TRUSTED/UNTRUSTED),
`supportsUndo`, contributor provenance, and `parameterSchema`**
(`AgentController.java:964`). The agent's authority space (§2.13 #17) is a
populated endpoint the window simply does not project — Root III's Goal-1-Move-C
analogue is mostly a render of existing data.

**V6 — Root III reversibility is HALF-substrated.** `op.policy().undoSupported()`
is a declared per-op facet (emitted on `/tools`); undo is per-execution
(`undoOperation(toolName, executionId)`, `AgentController.java:682`). So
partial-undoability is already representable and "undo all" is a client
projection over `undoSupported` entries. **Conflict-detection is ABSENT** (no
content-hash / modified-since on the undo path) — Root III's conflict-aware arm
is genuine new substrate, correctly flagged as a gap.

**V9 — Root III text-lineage is PARTIALLY substrated.** Structured tool output
exists for *some* paths (`hasSearchEvidence(tc.structuredData)` 561 #6; MCP
`mcpContent` 560), but `ToolCallCard` renders `tc.output` as opaque text
otherwise. General quoted-span lineage (#18) needs the tool result to carry a
lineage facet broadly; the search-evidence path is the seam to generalize.

**V3 — Root I's premise is SHARPENED (more compelling, not wrong).** The loop
is **not** cancelled by client disconnect: `CancelTrigger` is exactly
`{USER, BUDGET, TOOL_LOOP}` (`CancelTrigger.java`); `engine.run(...)` executes
synchronously on the request thread, writing to the SSE callback
(`AgentController.java:142`). So on disconnect the **run continues but its live
events are written to a dead socket and lost — there is no buffer and no
reattach to a live run; recovery is only `resume-last` from `AgentRunStore`.**
Root I's "zero observers" gap is thus precise: not "does the loop die" (it
doesn't) but "**a live run has no reattachable event stream**" — a stronger
motivation. (Residual: whether the loop thread keeps running after the HTTP
request/socket closes under Javalin is the one live-experiment question the
owned stack blocked — code says the loop is request-thread-coupled, so it likely
dies *with the request*, not the client; verify on a free stack.)

**V4 — single observer CONFIRMED (scope, not validity).** The stream is the
response body of the run-start POST (`/api/chat/agent`); there is no
broadcast/fan-out or multi-subscriber attach. Multi-observer + a reattach
buffer is genuine backend transport work — correctly named as a Root I limit,
not a projection.

**V5 — `resumable` CONFIRMED on the wire** (`AgentRunStore.java:108,157`,
`isResumableState`). Root I's lifecycle-predicate projection has its facet.

**V7 — Root I's recall-drift is CONFIRMED and already materialized.** The
catch-up card (`AiActivityDigest`) reads the **FE Effect Journal** via
`summarizeAgentActivity(lastSeen)` against a **localStorage `last seen`
cursor** (`readLastSeen`/`writeLastSeen`; `lastSeen = digest.latestId`) — a
parallel projection that does **not** use the one 550 federated ledger
(`ActionLedgerClient`/`unifiedActivity`) that History/Timeline project from.
So #20's drift is not hypothetical: two "what happened" sources already exist,
and "seen" lives only on the card's localStorage cursor. Root I's
"one seen-cursor per observer over the one log" is the confirmed fix.

**V10 — process residual.** The Goal-2 worktree (`577-agent-window`) is
**active and ahead of its last main merge** (`git worktree list`: `7c3a33c65`
> merged `903742829`). §2.13–§2.15 are append-only after §2.12, so a clean
merge is likely, but the parallel agent may also be editing the tempdoc tail —
**coordinate before they next sync** to avoid a tail conflict.

**Net + per-root confidence.** No root invalidated; every root's FE-projection
half is *better*-supported than theorized (the `/tools` endpoint, per-call
promptTokens, `undoSupported`, `resumable` all already exist), and two premises
are corrected (V1 no-compaction; V3 continues-but-events-lost). What the pass
surfaced is that **each root has a backend-substrate half that is new work, not
projection**, now named precisely:
- **Root I (observed entity):** FE projection (one seen-cursor over the one
  ledger; lifecycle-predicate affordances; boundary/time facets) is
  high-confidence and partly an existing-data render. **Backend new work:** a
  reattachable live-event buffer + multi-observer fan-out + a declared
  zero-observer run policy. *Confidence: MED-HIGH (FE) / MED (backend).*
- **Root II (resource family):** the meter+horizon is feasible from existing
  halves (V8). **Backend new work:** a context-management strategy (the
  held-gate/summarize behavior) — there is none today, so the "narratable
  compaction event" only exists once management does. *Confidence: MED-HIGH
  (meter) / MED (management).*
- **Root III (provenance/authority):** authority-space (#17) is near-pure
  FE-render of `/tools` (*HIGH*); reversibility (#16) has the per-op facet but
  **needs new conflict-detection substrate** (*MED*); text-lineage (#18) is
  partially substrated, needs broad tool-result lineage emission (*MED*).

**Overall remaining-work confidence: 7/10.** The theory is sound and now
corrected against source; nothing is invalidated; the cheap, high-value FE
projections (authority space, recall-cursor unification, lifecycle-predicate
affordances, the token-headroom meter) are well-supported and partly render
existing endpoints. The 3 points withheld are real: three roots each carry a
genuine **backend-substrate half** (reattach/fan-out, context-management,
conflict-detection) that is new work rather than projection, and the one
behavioral question only a live disconnect experiment settles
(request-thread-coupling of the loop) stayed **code-evidence-only** because the
shared stack was owned the entire pass.

### 2.16 Round-2 implementation record (2026-06-13, worktree `577-agent-window`)

The §2.12 four-move design (the §2.11 ledger's structural fixes) implemented,
merged to `main`, and live-validated end-to-end. Every move extended existing
substrate (`LifecycleState` + the approval-gate machinery, the 561 mode axis,
the 575 liveness pattern, the Goal 1 reason-vocabulary cut) — no new render
primitives, two register rows as designed discovery-steps. (Distinct from
§2.13–§2.15, the parallel agent's *second-wave* contract ledger + its own
design line, which remain open future work.)

- **Move 1 — affordances project from run lifecycle.** `directiveAvailable`
  in `runControlIntent` declares the lifecycle predicate per directive
  (interject/halt/raise-budget ⟹ run-in-flight; resume ⟹ resumable &&
  !in-flight; budget-decision ⟹ gate-held); `dispatchRunControl` refuses
  (typed no-op) when it fails, so the affordance and the dispatch share ONE
  predicate. Resume joined the seam; `resumable` mapped onto `SessionListItem`
  (the wire always carried it); the steering register grew a
  `lifecyclePredicates` block + the gate verifies every channel declares one.
  Accountability dies with its run (budget/lifecycle reset on conversation
  switch; the drawer projects controller budget only for the current session;
  `budgetUpdates` capped). **Live:** the just-finished session rendered as
  "3 iter · Finished" with NO Resume button (terminal ⟹ not resumable).
- **Move 2 — budget is a held gate, not a terminal meter.** `WAITING_BUDGET`
  lifecycle state (the budget sibling of `WAITING_APPROVAL`); `AgentSession`
  create/resolve/held/clear gate methods mirror the approval machinery. An
  interactive budget-exhausted run PARKS (emits `BudgetGatePending`,
  checkpoints `WAITING_BUDGET`, blocks); CONTINUE arrives from the raise
  endpoint (raising IS continue), FINALIZE/STOP from a new decision endpoint;
  the 120s timeout (0s under test) falls back to the EXACT legacy
  finalize-else-error, E0a/DECIDING bypasses intact, background never parks.
  New `budget_gate` wire event in all three serializers + descriptor +
  regenerated fixture/handlers. **Live:** the run parked at "Paused: needs
  ~1612 tokens, 771 left" with [Add 4096 tokens]/[Finish with what it has]/
  [Stop]; clicking Add resolved CONTINUE, the run resumed to DONE, and the
  ceiling grew 3840 → 7936 (+4096) honestly.
- **Move 3 — the epistemic answer frame.** `evidenceProjection` grew the one
  frame authority: `declaredGroundingClass` (exhaustive shape→class map;
  compile-time totality = "every shape declares") + `answerFrame` (declared
  class refined by the actual outcome) + `answerFrameLabel`. `MarkdownBlock`
  gained a `frame` prop neutralizing model-authored `[n]`/`(n)` into muted
  non-interactive spans in the ungrounded frame; every answer render site
  computes the frame and renders the frame line. The round-1 uncited-note
  special case dissolved into the frame's ungrounded arm; registered in
  run-renderers. **Live:** the answer carried "Partly grounded — some
  statements are not backed by your documents".
- **Move 4 — narratable decisions + accessible explanations + Why-uncited.**
  `AgentStepRunner` emits the loop's typed decision phases
  (`retry_after_tool_failure`, `finalizing`, `budget_gate_held`); the FE label
  map projects them as acts. The risk tier became a FOCUSABLE disclosure
  `<button>` (keyboard/AT-reachable; full explanation as accessible name),
  the grounding badge a native `<details>` "Why uncited?" disclosure;
  `humanizeId` now humanizes agent wire names ("Search Index", not
  "Core_search_index"). **Live (SSE):** phases
  `[init, llm_call, retry_after_tool_failure, llm_call, budget_gate_held]`;
  DOM: risk `<button aria-expanded>` + grounding `<details>`.
- **Phase 5 — bounded fixes.** The "since you last looked" digest moved off
  the colliding top-right slot to top-center (**live-confirmed** clear of the
  posture dial + History toggle). The mode-tab first-click-swallowed
  fragility was root-caused (Agent tab disabled while `aiState` is null on
  reload — a deliberate capability gate) and logged as a UX-design decision,
  not a drive-by change.
- **Verification.** Full FE suite (304 files / 2890), backend
  app-agent/app-services/ui/app-agent-api, the presentation/a11y/steering/
  run-renderers gate suite, schema idempotency — all green (the pre-existing
  `accent-as-text` failures in two untouched files excepted, logged). Class-
  size declared-growth changeset for the budget-gate endpoints. Live E2E on
  the merged stack with the model active validated all four moves + Phase 5
  in one pass (above).
- **Honor-system residuals (NOT done here):** (a) the approval-ceremony live
  audit (Assist × MEDIUM-write inline/typed-confirm, Watch behavior) — the
  §2.11 #8 validation debt, still owed; (b) Move 3's ungrounded free-chat arm
  + pseudo-cite neutralization were unit-verified but not live-reached (the
  UI path is hard to force); (c) the precise `groundingDegraded` backend flag
  (searched-but-uncitable vs no-search) logged as a refinement; (d) the
  independent measured whole-screen UX audit (auditor ≠ committer) now covers
  rounds 1+2 together.

### 2.17 Round-3 (second-wave) implementation record (2026-06-13, worktree `577-agent-window`)

The full §2.14 second wave shipped this round, each item via its long-term
structural substrate (no FE-only shim), driven by a `/goal` completion loop.
Eight commits, all green at the unit/gate tier:

- **§2.13 #17 — authority-space panel.** `<jf-agent-authority-panel>` +
  `display/authoritySpace.ts` (the verb-space × posture → disposition
  projection) behind an *Abilities* affordance. Reuses `present` (Display) +
  `becauseLine` (gate explanation); no new classifier. The §2.11 #8 ceremony
  reachable by inspection.
- **#19 — temporal + run/session anchoring.** Relative time on turn
  boundaries; a run/session boundary seam between restored record history and
  the live run (derived once by the `mergedTimeline` authority).
- **#20 — recall-cursor + log unification (user chose "full").**
  `substrates/recall/recallCursor.ts` (the ONE seen authority, an ISO cursor
  over the unified log) + `agentRecall.ts` (the digest as a pure projection of
  the one 550 ledger). `AiActivityDigest` now reads the SAME
  `openActionLedgerStream` the timeline does; undo/macro stay on the journal
  (the undo authority). Kills the 553 two-read drift.
- **#14 — context-headroom meter + context-management gate.**
  `AgentBudgetUpdate` gained `promptTokens` + `contextWindow` (n_ctx) through
  the full four-serializer + descriptor + fixture discipline; a horizon meter
  beside the budget. The COGNITIVE held-gate (`WAITING_CONTEXT`,
  `ContextGatePending` + `ContextCompacted`, continue/summarize/stop) mirrors
  the Move-2 budget gate and adds the compaction the budget gate lacks, via
  `POST /api/chat/agent/context-decision`.
- **#16 — reversibility honesty + conflict-detection.**
  `FileOperationsTool.undo` now skips a target the user modified-since (mtime >
  the recorded op time + tolerance) instead of blindly reverting — the genuine
  new substrate (§2.15 V6). The digest undo-confirm names the irreversible
  remainder.
- **#18 — text-lineage facet.** `OutputLineage` (corpus-quoted | runtime |
  agent-authored) stamped authoritatively into `structuredData.lineage` at the
  one dispatch seam; `toolOutputLineage` (the registered FE authority) frames
  corpus-quoted tool output as quoted so injection/citation-shaped text inside
  it cannot read as the agent's own claim.
- **#13 — run as a reattachable observed entity (Root I).** `RunEventHub`
  (bounded buffer + fan-out) is the run's ONE event authority; the loop's sink
  routes through it, so a closed socket can no longer kill the loop — **the
  §2.15 V3 root cause resolved at source** (code-evidence confirmed the loop
  was synchronous on the request thread; the hub swallows observer exceptions).
  `POST /api/chat/agent/{sessionId}/attach` replays the buffer + streams
  ongoing for a reattaching tab; `zeroObserverPolicy()` is posture-graded
  (Watch-no-watcher PARKS).
- **§2.16 (c) closed — `groundingDegraded`.** The precise flag now
  distinguishes searched-but-uncitable (a grounded-index shape, zero sources)
  from no-search (free-chat), refining the `ungrounded` label.

### 2.18 Critical-review remediation + live confirmation (2026-06-13)

A post-implementation critical-analysis pass found **6 substantive issues** —
the Root I reattach substrate was plumbed but **under-wired**, plus three
narrower logic/UX defects — all remediated (commit `85af7be8`, merged to main):

- **Fix 3 (FE reattach unwired):** `attachToRun` existed but was never called.
  `send()` now reattaches on a genuine mid-run drop (one-shot, gated on
  `runStartedThisStream` so an initial POST failure over a stale id doesn't
  mis-fire). Surfaced + fixed a latent bug: `attach_not_live` closes the SSE
  with no terminal → `STREAM_INCOMPLETE` → a false error; `onAttachNotLive`
  now marks the stream handled.
- **Fix 2 (zero-observer policy dead):** wired into `AgentStepRunner` (parks an
  unobserved Watch run, narrated, bounded). `RunEventHub` now **evicts** a
  dead-socket observer on a throwing write, so `observerCount` reflects LIVE
  observers — without which "no watcher" never registered.
- **Fix 4 (seam false-positive):** the run/session seam mis-fired between a
  question and its own answer when the live answer failed to dedup against the
  record. Restricted to a live USER turn (a run boundary always begins with a
  user turn).
- **Fix 5 (context-meter flicker):** projected from the last budget update that
  carries occupancy (not the absolute latest, which is `iteration_start`'s 0).
- **Fix 6 (lineage reach):** extended the corpus-quoted frame to the structured
  search-evidence path (the main corpus reader bypassed the raw-output frame).
- **Fix 1 (loop-decoupling) — V3 RESOLVED:** the synchronous loop routes events
  through `RunEventHub`, which now SWALLOWS + EVICTS a dead-socket observer's
  write (the loop never sees it), and Jetty does not interrupt the SSE handler
  thread on disconnect. Confirmed observationally (a parked run survived the FE
  "Reconnecting"; agent_chat runs continued past their orchestrating socket).
  **Verdict: the loop survives socket close as-is — no executor decouple needed.**

**Live-confirmed on the merged build (screenshots in transcript):** the
authority panel + live posture-grading (Watch ⇒ all "asks first"); turn-time
("just now"→"4m ago"); **no false seam** on a fresh single turn (Fix 4); the
context-headroom meter visible + persisting "Context: 1857 of 4096 (45%)"
(Fix 5); the search tool card framed **"Quoted from your documents"** (Fix 6);
the recall digest from the one log; grounded answers with [n] citations + the
budget projection; RAG honesty ("the documents do not contain the answer").
A second live pass added: the **§2.11 #8 approval ceremony** — a HIGH-risk
`core_file_operations` raised the typed-confirm "Authorize action" modal
(type-the-tool-name + Deny/Approve); the **Deny/reject path** executed and the
run trace recorded "1 denied"; the **ungrounded answer-frame** rendered "Model
answer — this mode does not search your documents" (the §2.16 no-search label);
the **budget held-gate** parked ("Over budget +1638 tokens", Add 4096 / Finish /
Stop); the **digest undo flow** is wired ("Nothing to undo" when no agent effect
is FE-journal-undoable).

**Remaining (covered by unit tests + the static a11y gates; the 8B dev model +
34-doc corpus + n_ctx=4096 block a clean live trigger):** the approval *Approve*
arm (symmetric to the confirmed Deny — the model would not reliably re-propose a
file op once the conversation degraded); the file-op conflict + per-entry
reversibility-honesty line (need a SUCCESSFUL undoable file op — the 8B model
emits malformed `operations` args, so none completes); reattach-across-two-tabs
and zero-observer parking (the FE reattach is drop-triggered, and a clean
mid-active-run socket drop is hard to force; zero-observer is reachable only
after eviction, which needs that drop). Each is unit-tested; the `check-a11y-closure` /
`check-controls-a11y` gates are the measured a11y guarantee.

**Cognitive context gate — LIVE CONFIRMED (2026-06-13).** Triggered end-to-end in
the real browser against the 8B / n_ctx=4096 dev stack. The drawer rendered
**"Context filling up: 3604 of 4096 tokens"** with the gate's three decision
controls — **Compact older turns · Continue anyway · Stop** (the compaction option
the budget gate structurally lacks, §2.14 Root II). The backend `events.ndjson`
corroborates: `progress phase=context_gate_held` then a `context_gate`
(`ContextGatePending`) event `{promptTokens:3604, contextWindow:4096}`, emitted on
**iteration 0** with `tokensRemaining:3840` (budget full) — 3604 ≥ ⌊0.8·4096⌋=3276
fired it, and 3604 < 3840 so the budget gate did not pre-empt. The earlier "budget
fires first" reasoning was half-right but mis-stated the mechanism: the gate keys
off the **pre-call `countPromptTokens` ESTIMATE**, not the post-call actual. The
estimate under-counts (a measured run: estimate 2562 vs llama-server actual 3599),
so (a) on iteration 0 you must size the *estimate* — not the displayed actual — to
≥3276 while budget is full (achieved with ~9 repeated paragraph cycles), and (b) on
later iterations the cumulative budget gate trips first because budget=n_ctx−256 is
nearly spent. A real soft spot follows: because the gate reads the under-counting
estimate, an *actual* prompt can reach ~88% of n_ctx and still pass the 80% gate
(logged to `observations.md` — consider gating on a corrected/post-call count).
The decision round-trip (Compact) wedged on the Vite SSE proxy and was not
delivered, but the held-gate state — the thing under test — is conclusively shown
in both the UI screenshot and the backend event log. Same pass re-confirmed the
**approval ceremony Deny** arm live (HIGH-risk `core_file_operations` → typed-confirm
modal → Deny → run records the denied op) and **run survival across a reconnect**
(a budget-parked run resolved to a 9-source grounded answer after a full tab reload
re-subscribed to the run's `RunEventHub`).

**Per-entry reversibility + file-op conflict detection (§2.14 Root III #16) — LIVE
CONFIRMED (2026-06-13) via real file operations.** The 8B model cannot emit valid
file-op `operations` JSON unprompted (it fumbles even an integer `limit` —
"string found, integer expected"), so the op was driven with an explicit
instruction; the typed-confirm approval was resolved by POSTing `/api/chat/approve`
straight to the loopback backend (the Vite SSE proxy wedges after ~1 interaction, so
the in-browser Approve 404'd as a stale gate — itself the §Ext-III stale-gate
handling, observed live). Two real ops executed end-to-end:
- **MKDIR** `…/docs/explanation/demo-577-reversibility` → `success:true`, dir created,
  indexed, and the **Authority Panel** rendered **"File Operations · undoable · HIGH"**
  under ALWAYS-CONFIRMS (the per-entry `supportsUndo` chip, `AgentAuthorityPanel.ts:121`)
  with the full posture grading of all 8 tools; the workspace **Timeline** showed the
  lifecycle **grant ISSUED → CONSUMED → APPROVED (TYPED_CONFIRM) → File Operations SUCCESS
  → Indexed**.
- **COPY** `01-system-overview.md → demo-577-copy.md` → `success:true`; the destination
  was then edited (8719→8753 bytes, ~9 s after the op, outside the 2 s `CONFLICT_TOLERANCE`).
  The digest's **"Undo all"** confirm rendered the reversibility honesty line — **"Undo
  these 2 AI actions … 9 other actions can't be undone and will remain"** (undoable file
  ops vs non-invertible searches/grants) — and on confirm the conflict-detection skip
  fired exactly as `FileOperationsTool.undo` specifies (lines 234-258): the since-edited
  copy was **PRESERVED, not deleted** — the Root III "never destroys a since-edited file"
  guarantee, verified by the file surviving with the user edit intact. (The literal
  "changed since — review before undo" string is the backend `OperationResult` message;
  the FE digest surfaced a generic "Undid 2 AI actions" toast, so the *behaviour* is
  conclusively shown live while the exact phrase is code-verified, not screenshotted.)
  Both test artifacts were removed afterward; the dev index self-corrects on the next
  clean reindex.

**Still not screenshotted live — blocked by browser-tool / dev-model ceilings, not
implementation (all three substrates unit-tested + backend-verified):** (1) *two
concurrent browser tabs* — the Claude-in-Chrome MCP cannot navigate a freshly-created
tab (5 attempts stuck on `chrome://newtab`; `window.open` lands outside the trackable
group), so reattach is shown via page-reload re-subscription (a new SSE observer
replaying the in-flight run) rather than a second tab; (2) *zero-observer parking* —
needs a precisely-timed observer drop at an iteration boundary (not an approval wait),
which the Watch-approval cadence + proxy wedge make unschedulable; covered by
`RunEventHubTest` (eviction-to-zero) + the `AgentStepRunner` park path.

**Final verification round (2026-06-13) — Playwright-driven, after recovering a clean
browser context (close the wedged MCP tab group + `createIfEmpty`).** Three results:

- **Measured axe pass — DONE, 0 violations.** The project's own `accessibility-audit.spec.ts`
  is blocked by a *pre-existing* broken demo-mode/MSW harness (`waitForDemoData` times
  out — `[data-selected]` rows never load; the MSW `worker.start()` gap already in
  `observations.md`), so axe never ran there. A standalone `@axe-core/playwright` scan of
  the live shell + Agent view (with `?lockdown=0` to opt out of SES so axe can inject past
  the frozen intrinsics — main.jsx:37) returned **0 WCAG 2.1 AA violations** (both contexts).
  Complements the green `check-a11y-closure` / `check-controls-a11y` / `check-contrast-matrix`
  gates.

- **Reattach across observers — DEMONSTRATED (substrate).** Playwright held a Watch run at
  its first approval (`tabA-run-held.png`: Session started → Thinking → Search Index PENDING
  → Authorize modal); closing that tab left the run **held server-side with zero browser
  observers**, and a `POST /api/chat/agent/{sessionId}/attach` from a second client **replayed
  the entire run** (`init → llm_call → llm_response → core_search_index "search pipeline
  overview" → tool_call_pending`). That is the Root I N-observer/reattach capability live. The
  *simultaneous two-browser-tab* screenshot stays blocked by (a) the FE persisting the active
  run in **sessionStorage (per-tab)** — a reload reattaches, a fresh tab does not auto-discover
  it — and (b) the Vite dev-proxy starving connections when one tab holds an SSE; neither is a
  backend defect.

- **Zero-observer park — GAP FOUND *and FIXED*.** Driving a Watch run with no attached observer
  and approving each step via the backend, the loop **proceeded to the next `llm_call` instead of
  parking**, twice. Root cause (more precise than the onClose hypothesis): the park gates on
  `observerCount()==0`, but the agent SSE writers IGNORED `SseWriter.writeEvent`'s documented
  `false` (client-disconnected) return — `writeAgentEvent` and the engine consumer never threw, so
  the hub's throw-eviction never fired, a dead socket lingered in the subscriber set, and
  `observerCount()` never reached 0. A Watch run thus proceeded UNWATCHED (safety goal unmet).
  **Fix shipped** (`fix(577 Root I): evict a disconnected SSE observer`): the one write path becomes
  `writeOrEvict(...)` whose pure seam `evictIfGone(boolean)` THROWS on a `false` write, so
  `RunEventHub.deliver` evicts the observer; applied at BOTH observer chokepoints (the initiating
  engine consumer `AgentController:146` + the `writeAgentEvent` attach/resume path), verified the
  throw propagates through the shape-runner's translating lambda to the hub's catch (the `runAgent`
  try/catch is a different path). No wire change. **Tests:** `AgentControllerSseEvictionTest` (the
  seam throws on disconnect) + the END-TO-END `AgentLoopServiceTest.zeroObserverPark_…` (a real
  WATCH run whose observer's write fails is evicted → `observerCount==0` → the loop PARKS, narrates
  `run_unobserved_parked`, and a reattacher replays it + releases the park) — the in-build,
  no-deploy live-verification that the park now works. `declared-growth` changeset for
  AgentController 1089→1128.

### 2.19 Final live pass (2026-06-13) — the last two browser screenshots + the cross-tab seam

The user authorized a verification deploy + dev-stack takeover. Because main carried another agent's
uncommitted `observations.md` (a clean `git merge` would clobber it — branch-safety #6), the deploy
used the **build-artifact overlay** path (no source merge): the eviction-fix `ui` jar overlaid onto the
dev install + launched via `skipBuild`, with a **standalone worktree Vite on :5175** serving the
cross-tab FE against that backend. Both remaining condition-6 items are now LIVE-screenshotted:

- **Run reattach across two tabs — LIVE CONFIRMED.** New FE seam (`feat(577 Root I): cross-tab
  reattach`): a single-authority `activeRunPointer` (the ONE shared-`localStorage` key) publishes the
  live agent run on `session_started`, clears it on terminal, and a fresh tab's first agent mount
  calls `reattachActiveRunOnLoad()`. Screenshot: tab A started a Watch run (held at the
  `core_search_index` approval); after tab A closed, a SECOND tab auto-discovered the run via the
  pointer and reattached — same session, same pending approval, same trace. (A *simultaneous* two-tab
  load is blocked by the Vite dev-proxy starving connections while one tab holds an SSE — environmental,
  not a defect; the run is genuinely tab-independent.) Tests: `activeRunPointer.test` + 3 controller
  tests (reattaches-on-load / skips-when-owning / no-op-when-empty).

- **Zero-observer park — LIVE CONFIRMED, with a real disconnect-detection nuance.** The eviction fix
  fires ONLY when the backend SEES the observer's socket close (`writeEvent`→`false`). Through the
  **Vite dev-proxy** the browser's tab-close is MASKED (Vite keeps its upstream SSE open), so the park
  did not fire when driven purely through the browser. Driven through a **direct** SSE to the backend
  (no proxy) — the production topology (the Tauri shell connects directly) — killing the observer then
  approving made the loop evict (`observerCount==0`) and PARK on the next boundary
  (`…retry_after_tool_failure → run_unobserved_parked`, confirmed in the run record). A browser tab
  then reattached to the parked run and rendered the narration **"Paused — no one is watching"** in the
  trace (screenshot). So: fix correct + live-proven; the dev-stack-only non-firing is the Vite proxy
  masking the disconnect, logged to `observations.md` as a dev-harness note (consider Javalin managed
  `SseClient.onClose` for proxy-independent detection).

Placeholder only. The theory pass happens after Goals 1–2, but the
constraints already known must survive into it. Goal 3 now has a named
anchor: it is where 570 Move A (search as a mode of the one interaction
surface; retire `core.search-surface`) and the Move H register/gate land,
and where 570 §18.4 **D1** ("is a spatial results-list a legitimate
mode/panel of the conversation window, or does search stay a distinct
surface the gate explicitly allows?") gets decided — with 561's
modes-as-axis-points (`autonomy × grounding × persistence`) as the
governing frame. From the agent side (Goal 2 §2.2's cut), the same
landing zone holds 561's declarative mode catalog, the
Documents/Structured/Agent tab dissolution, and the `interaction-surface`
register+gate. Constraints:

1. **Escalation tiers, not mode tabs.** One input; typing → instant hit-list;
   explicit "Ask" → cited inline answer; explicit "Delegate" → agent run with
   timeline + posture chrome. Documents/Structured/Agent tabs dissolve;
   Structured becomes a query capability, not a destination.
2. **The hit-list stays ephemeral; only escalations become thread history.**
   Search is ten-queries-a-minute throwaway iteration; if every exploratory
   query lands in a transcript, the transcript becomes noise and the instant
   feel dies.
3. **Trust semantics stay visually distinct.** Deterministic index evidence
   vs LLM synthesis must not blur; the LLM must not borrow the index's
   credibility.
4. **Control chrome attaches to the run, not the window.** Posture, budgets,
   irreversible-write confirmation, steering appear only once a run exists
   (consistent with the 565 §30 steering-seam design).
5. **Performance contract: the 2ms keystroke loop must not gain one frame of
   chat-chrome weight.** Any regression here is experienced as the
   unification failing, regardless of architectural elegance.
6. **Sequencing within Goal 3 itself**: (a) shared evidence/citation
   primitives (largely bought by Goals 1–2), (b) unified session/history so
   "Ask AI"-from-search and a chat thread are the same object, (c) only then
   collapse the windows. (a) and (b) are worth shipping even if (c) is
   ultimately rejected.

### Goal 3 — pre-theory de-risk note (2026-06-14)

A read-only pass *before* the Goal-3 theory is written, to ground it in the
now-shipped substrate (the landscape shifted hugely: `578` shipped the taxonomy
frame; Goal 2's second wave = §2.14 → "R3" shipped). Same §1.8/§2.15 discipline.
The prerequisite the placeholder named ("after Goals 1–2 land") is now **met** —
Goal 1 shipped+gated (§1.10), Goal 2's second wave shipped (§2.17–§2.19), and the
§2.14 Roots I & III flagged as *Goal-3 prerequisites* are **implemented** (verified
below). Per-assumption verdict:

- **U1 — the merge mechanism rides EXISTING substrate (HOLDS).** The 561
  `interaction-surfaces` register (`governance/interaction-surfaces.v1.json`)
  enforces "at most ONE visible USER RAIL/STAGE surface consumes a core
  interaction shape — `core.unified-chat-surface`; DEEPLINK routes in." Search is
  a *peer RAIL surface, not yet a shape* (578 §9.1, source-true). So Goal 3 =
  **add a retrieval interaction shape to `CoreConversationShapeCatalog` + retire
  `core.search-surface` as a peer RAIL surface** (≡ 570 Move A) + render the
  retrieval tier in the one window. The gate **already guards the end-state
  invariant** (it forbids a second visible interaction surface). Synthesis + a
  bounded build, **not new substrate**.
- **U2 — CORRECTED (de-risk payoff).** Search⊕Agent does **NOT** compose 578's
  host/member + `<jf-surface-tabs>` primitive — that serves the *other* merges
  (rows 3–15; 578 §9.2). Search⊕Agent rides the **561 interaction-shape**
  mechanism, which is independent of 578's just-built frame and **stable**. My
  earlier "Goal 3 composes 578's frame" was imprecise; correcting it *lowers*
  collision risk with the active 578/agent-window agent.
- **U3 — the Goal-3 prerequisites are GENUINELY IMPLEMENTED.** Root I observer
  model: `RunEventHub.java` (+`RunEventHubTest`), `AgentControllerSseEvictionTest`,
  `activeRunPointer.ts` — reattach / zero-observer park real and tested. Root III
  cross-mode provenance: `toolOutputLineage.test.ts`, `groundingDegraded`,
  `ToolCallCard` lineage — real. The "must hold before merge" prereqs are not
  vapor.
- **U4 — D1 RESOLVED toward merge.** 578 §5.1/§6 settle the end-state as one
  window (escalation tiers, ephemeral hit-list never entering thread history) and
  delegate the *design* to 577 Goal 3. No dissent found.
- **U5 — the perf constraint needs RE-FRAMING, mechanism EXISTS.** §1.8 U8 already
  falsified the literal "2ms loop" (search is ~1.2s with AI on). But the staged
  search I shipped (the quick BM25-only pass, ~40–140ms) **is** the fast
  keystroke tier the constraint *intends* — so the constraint is reframed
  ("preserve an instant provisional tier"), not newly built.
- **U6 — the shared citation primitive is ALREADY shared.** `citationResolve.ts`
  exports both `claimsToCitations` (RAG) and `resolveAnswerCitations` (agent
  answer) — both windows use it. The §1.9 consolidation bridge is partly built
  (mapping authority unified; the source-chip render component may still
  duplicate — a contained follow-up).
- **U7 — coordination is the main process risk.** The agent-window agent is still
  active (worktree `577-agent-window` has unmerged Root-I commits); the merge
  touches `core.unified-chat-surface` / `UnifiedChatView`, which it owns. But
  U2's mechanism-independence means Goal 3 adds a *shape* rather than rewriting
  their window — coordinate, don't collide.
- **U8 — live verification BLOCKED (honest residual).** The shared stack stayed
  owned by the other session all session; the merge's live behaviors are
  code-evidence-only until a free stack — same residual as §1.10/§2.15.

**Net:** no foundation invalidated; one imprecision corrected (U2, which *reduces*
risk); the merge mechanism is confirmed to ride stable existing substrate (561),
the prereqs are done, and the keystroke-perf answer already exists (staged
search). The genuine remaining work is **the interaction-contract design**
(throwaway hit-list ⊕ stateful thread in one window via intent-tier escalation)
— still unwritten and the hard core — plus a bounded build (the retrieval shape,
the intent-tier UI, retiring the search RAIL peer), on a surface another agent is
actively evolving, with live validation blocked. Implementation planning starts
from this note + 578 §9 + the six constraints.

### Goal 3 — the design (theorized 2026-06-14)

> Design-theory genre (557/559/567/570/578): correct long-term structure,
> feasibility/phasing disregarded, general not implementation-level. **Synthesis-
> first** (like §1.0/§2.0): the de-risk pass (above) established the design mostly
> *already exists* — this section assembles it for the Search⊕Agent row, designs
> the one genuinely-unwritten part (the interaction-contract coexistence), and
> resolves the constraints. Supersedes the "Placeholder only" opener above.

**§3.0 Investigation verdict — assemble, don't invent.** Three of the four
intent tiers already exist as shapes of the one window (`core.rag-ask` = the
"Ask" cited-answer tier; `core.agent-run` = the "Delegate" run tier;
`core.free-chat` = ungrounded ask); the window already routes per-message shapes
by affordance (`UnifiedChatView`, `POST /api/chat/dispatch {shapeId}`); the
container is the 561 one-interaction-window (gate-enforced); the instant-hits
engine (`buildSearchIntent` + the staged quick pass) and result rendering
(`searchResultViewModel`, facet chips, trace altitude) shipped in Goal 1; the
citation primitive (`citationResolve`) is already shared. **The only new shape is
the pure-retrieval bottom tier.** So Goal 3 = (a) add `core.retrieve`, (b) the
intent-tier escalation UX, (c) retire `core.search-surface` as a RAIL peer — all
composing existing substrate.

**§3.1 The spine.** *The unified window is the ONE interaction surface
(`core.unified-chat-surface`) whose modes are intent-graded shapes; the user's
one input escalates **type → instant hits (`core.retrieve`, ephemeral, no LLM) →
"Ask" (`core.rag-ask`, cited answer, enters thread) → "Delegate" (`core.agent-run`,
run, enters thread)**. The breaking forms — a second visible interaction surface,
a forked search-issuance path, the hit-list entering thread history, an
unframed-provenance answer — are unrepresentable by the `interaction-surface`
gate + the `check-search-issuance` gate + the ephemeral-tier contract + the
Root III text-lineage facet.*

**§3.2 The hard core — how the two contracts coexist (the genuinely new design).**
577 §0's central tension: the 2ms throwaway hit-list loop vs the
seconds-to-minutes stateful thread are *opposite* interaction contracts. They
coexist not as two windows but as **two intent tiers of one input, separated by
an explicit escalation act**:
- The **hit-list tier is PRE-thread**: a live, keystroke-reactive, *ephemeral*
  response rendered in a transient results region — never a thread message. It is
  the "still formulating" state; it owns no history.
- **Escalation is an explicit user act** — Enter-on-a-result (open), "Ask"
  (promote the query to a grounded-answer turn), "Delegate" (promote to a run).
  *Only escalation writes thread history.*
- Therefore the throwaway loop **cannot degrade the thread** (it is not in it),
  and the thread **cannot slow the keystroke loop** (the hit-list is the cheap
  staged pass). The coexistence is by **tier-separation**, which is the
  structural resolution of §0's tension — not a compromise between the two.
- Persisted = thread turns (Ask/Delegate). Ephemeral = the hit-list + in-progress
  query. Run-chrome (posture/budget/steering) attaches **only at the Delegate
  tier** (constraint 4) — never on the search box.

**§3.3 The retrieve tier.** Pure retrieval, no LLM dispatch; dispatches through
the one `buildSearchIntent` seam (the `check-search-issuance` gate follows it
into the window); renders `searchResultViewModel` rows + facet chips + the
trace-altitude panel in-window; ephemeral by contract. The other tiers reuse
existing shapes.

> **§3.3 CORRECTION (2026-06-14, implementation altitude — supersedes the
> sentence above):** retrieve is **NOT** a conversation shape and must **NOT**
> be added to `CoreConversationShapeCatalog.CORE_USER_INTERACTION_SHAPES`.
> Source-verified: `ConversationShape` is *"a runnable **LLM-output flow**"*
> (`ConversationShape.java:11,15`) dispatched via `/api/chat/dispatch`; retrieve
> has no LLM output and dispatches via `/api/knowledge/search`. So the retrieve
> tier is the unified window's **base interaction** (the search input + ephemeral
> hit-list, reusing the FE `searchState` store directly), NOT a registered shape.
> Consequence: Goal 3 adds **no** conversation shape and makes **no** shape-catalog
> change; the `interaction-surface` gate is unaffected by retrieve (it counts
> shape-consuming surfaces; the base tier consumes none). Only the *escalation*
> tiers (Ask=`core.rag-ask`, Delegate=`core.agent-run`) are LLM shapes. This is
> the "explore-before-implementing" discipline catching a theory-altitude error
> before code (§1.0 pattern).

**§3.4 Cross-mode trust (constraint 3, on the implemented Root III).** Each tier
declares its epistemic frame as a *facet*, not a visual convention: hits are
deterministic index evidence (the differentiator — "why this result" rank
transparency); the Ask answer carries its grounding + local-passage citations;
an ungrounded free-chat answer is *framed* as ungrounded; the run carries its
provenance. The Root III text-lineage facet keeps corpus-quoted vs agent-authored
distinct. **The LLM cannot borrow the index's credibility** because provenance is
declared per tier — the cross-mode guarantee §2.14 flagged as a Goal-3
prerequisite, now resting on shipped substrate.

**§3.5 Perf contract, reframed.** The literal "2ms loop" (constraint 5) is
obsolete (§1.8 U8: search is ~1.2s with AI on). Its *intent* — an instant
provisional response to typing — is honored by the **staged quick BM25 pass**
(~40–140ms) as the hits tier; the refined/LLM tiers are explicit escalations the
user opts into, so they never block the keystroke loop. The constraint becomes:
*the hits tier stays on the cheap pass; anything slower is an explicit
escalation.*

**§3.6 Retiring `core.search-surface` (570 Move A).** Its `Placement` →
DEEPLINK/non-RAIL (routes into the one window's retrieve tier); the rail loses a
peer (578 taxonomy). The `interaction-surface` gate enforces *one* visible
interaction surface **by construction** — a second would fail the build — so the
merge's end-state is gate-guaranteed, not remembered.

**§3.7 Prevention — completing Move H.** Three gates make the breaking forms
unrepresentable: `interaction-surface` (no second window), `check-search-issuance`
(no second issuance path — follows search in), and the full 570 Move H now
buildable — *intent-tier coverage projecting from the shape catalog* (every tier
maps to a registered shape; a tier rendered without a shape, or a shape rendered
outside the window, fails). The shape catalog IS the mode authority, so coverage
projects from it (557 §5.2 form).

**§3.8 Boundaries & honest limits.** The interaction-contract (§3.2) is the
design risk — only a live, measured UX pass validates that the tier-separation
*feels* like one tool, not a mode switch. The merge is high-blast-radius on
`core.unified-chat-surface`, which the agent-window agent actively owns —
coordination is a hard gate. Live validation has been blocked all session
(shared stack owned). The source-chip render consolidation (§1.9) is a contained
follow-up. 578's other merges (Library/Brain/Settings/System) are out of scope.

**§3.9 Sequencing (constraint 6).** (a) shared evidence/citation primitives
(largely shipped) → (b) `core.retrieve` + the intent-tier UX + unified session →
(c) retire the RAIL peer (collapse). (a)/(b) are worth shipping even if (c) is
deferred — they give "Ask AI from search" without committing the rail change.

### 3.10 Implementation record — the retrieve base tier (2026-06-14, worktree `577-goal3-unify`)

Step (b)'s core landed: **the retrieve base tier is live in the one window**
(`UnifiedChatView`), per the §3.3 correction (it is the window's base interaction
reusing the FE `searchState` store — **no** conversation shape, **no** catalog row).

- **Affordance.** `Affordance` gains `'retrieve'`; a leading **Search** button
  selects it (`unifiedChatState.ts`, `UnifiedChatView.renderAffordanceBar`).
- **Composer dual-mode.** In retrieve, `@composer-input` drives `setQuery` (the
  staged quick pass) and `@composer-submit` calls `submitSearch` — the LLM `send`
  path is bypassed; submit is no longer gated on `capabilities.chat`.
- **Ephemeral hit-list.** `renderRetrieveTier` replaces the chat thread in the
  conversation-zone while `affordance==='retrieve'`; rows are typed via the shared
  `projectResultView` (Goal 1 Move B); click opens through the shared host
  inspector seam (`host_.search.hitToSelectedItem` → `host_.ui.showInspector`).
  The list owns **no** thread history.
- **Lifecycle.** `searchState` subscribed in `connectedCallback` (apiBase shared),
  unsubscribed on disconnect; `searchSnapshot` is a reactive `@state`.

**Verification.** typecheck clean; full FE suite 2959/2959 green (3 new
retrieve-tier tests: affordance selects the tier; hit-list renders from the store
and never as thread turns; input/submit drive the search store). Gate suite green
(search-issuance, run-renderers, a11y-closure, layout-purity, controls-a11y,
contrast-matrix, ambient-purity, atom-fork-ratchet, style-literal-ratchet;
theme-token-closure clean of my code — one **pre-existing** `--font-sans` ghost in
`ToolCallCard.ts` + a pre-existing `accent-as-text` failure, both logged to
observations, neither mine).

**Live browser validation (worktree Vite on :5180 → live backend :63024, 49-doc
corpus).** Confirmed end-to-end: Search affordance selects the tier → typing fires
the staged quick pass (`POST /api/knowledge/search` 200) → "45 results" with the
ephemeral typed hit-list (icon/title/path/2-line snippet) → click a row opens the
shared inspector (Preview/Context/Answer/**Ask** tabs — the escalation affordances
in place) → toggling Search off clears the hit-list, thread stays empty (ephemerality).
**Honest residual:** the Documents (Ask) / Agent (Delegate) escalation tiers require
the LLM and correctly **disable** while AI is offline; this stack's data dir has no
chat model configured (`ai_activate` → "No chat model configured"), so the
LLM-tier hand-off to the pre-existing `core.rag-ask` / `core.agent-run` shapes was
not live-exercised here. Those are separately-validated existing shapes; the new
code (the retrieve tier) is fully validated.

### 3.11 Retirement record — `core.search-surface` off the rail (2026-06-14, step (c))

Per the user's decision (retire now), step (c) landed: the standalone Search window
is **retired as a RAIL peer**.

- **`CoreSurfaceCatalog.java`:** `SEARCH_SURFACE_ID` Placement `RAIL` → `DEEPLINK`.
  The rich standalone surface (facets / trace / "why this result?") stays
  **URL-routable** — nothing deleted; the rail loses the peer (578 taxonomy: one
  fewer rail surface). The FE rail filter (`placement === 'RAIL'`) drops it
  automatically.
- **`Shell.ts` boot default:** Search is no longer in `this.surfaces` (rail-only),
  so the landing default changed from `core.search-surface` to the ONE interaction
  window (`core.unified-chat-surface`), whose `retrieve` affordance is the search
  entry tier; falls back to first-non-bottom, then any.
- Existing `core.search-surface` deep-link navigations (folder-search, "modified
  since") still resolve to the standalone surface (DEEPLINK is URL-routable).

**Verification.** `spotlessApply` + `:app-observability:test` + `:app-agent-api:test`
green (the catalog change compiles + the catalog/altitude/matcher tests pass);
surface-altitude / a11y-closure / layout-purity gates green; FE typecheck clean;
shell/rail tests green (incl. the deep-link-to-search test — DEEPLINK still
resolves); full FE suite 2959/2959. (Pre-existing, not mine: an `operation-surface`
gate failure on `IndexingJobView` references in `AiActivityDigest.ts` /
`TrustChannel.ts` / `agentRecall.ts` — logged to observations.)

**Known parity gap (fast-follow, §3.9a).** The unified window's retrieve tier is a
leaner hit-list than `SearchSurface` — it does **not** yet carry the Goal-1 facet
chips / trace altitude / "Why this result?". Those remain reachable via the
standalone surface's deep-link; bringing them into the in-window tier is the
shared hit-row consolidation (§3.9a) and is the recommended next step before the
standalone is considered fully redundant.

**Remaining for Goal 3:** the Move H intent-tier coverage gate (§3.7); the §3.9a
hit-row/feature consolidation (close the parity gap); live validation of the
LLM-tier escalation (Ask/Delegate) with a chat model configured.

### 3.12 Close-out — parity + Move H + full live validation (2026-06-14)

The §3.11 "remaining for Goal 3" list is **done**, all three live-validated against
a running model (Qwen3.5-9B; worktree Vite → live backend, 206-doc corpus).

- **§3.9a parity (Phase B).** Two shared render modules under
  `components/searchResults/` — `whyThisResult.ts` (the per-hit "Why this result?"
  trace-chip disclosure + the "Explain in words" → `core.summarize` action) and
  `facetChips.ts` (the facet-chip render). SearchSurface refactored to consume
  both (net **−260** lines there, proving no fork); the retrieve tier composes
  both. The `run-renderers` register gained a `searchResultRendering` authority so
  a third fork of the why/facet render is import-visibly caught.
  **Live:** "court" → 123 results with the facet chips (Type/Format/Language)
  above the list and "Why this result?" expanding to the labeled trace chips
  (Sparse BM25 · Chunk merge · Branch fusion · Cross-encoder + "Explain in words").
- **Move H gate (Phase C).** `governance/intent-tier-coverage.v1.json` +
  `scripts/ci/check-intent-tier-coverage.mjs` — the third §3.7 leg (beside
  `interaction-surface` + `search-issuance`): coverage (every
  `CORE_USER_INTERACTION_SHAPES` shape maps to exactly one tier) + routing (the
  window's `presetByShape` == the declared tier→shape map) + the base-tier
  invariant (`retrieve` is shape-less and dispatches through `submitSearch`, never
  an LLM shape — §3.3). Four teeth proven; wired into ci.yml + CLAUDE.md.
- **Escalation hand-off + LLM tier (Phase A).** No new code needed — the composer
  dual-mode already keeps the typed query in `inputDraft` across an affordance
  switch. **Live:** retrieve "court" → switch to **Documents** (the query carried)
  → grounded **DOCUMENT Q&A** answer ("Partly grounded" epistemic frame, "Based on
  the provided documents … Nebraska courts [10]" with inline [1]/[2] citations +
  grounding underlines, through the shared answer renderer + citation weave) →
  **Agent** crossing (the Watch·Assist·Auto dial, the action-plane composer, the
  run-control posture row, Abilities) — all in the ONE window / ONE thread; the
  123-hit list never became a thread turn (ephemerality preserved through escalation).
- **Canonical-doc promotion (narrow).** The search-as-a-rail-peer drift my change
  introduced was corrected in `docs/explanation/10-ui-ux-design.md`,
  `docs/reference/search-ui-behavior.md`, and `docs/reference/secondary-views-behavior.md`
  (search is the unified window's `retrieve` tier; the standalone is DEEPLINK). The
  broader pre-578/React-era staleness in `secondary-views-behavior.md` stays tracked
  by tempdoc 579 (not in this scope).

**Tempdoc status (updated 2026-06-15).** **All three goals are implemented and merged
to `main`** — Goal 1 (search window), Goal 2 + Root I (agent window), Goal 3
(unification: the retrieve base tier, the rail collapse, §3.9a parity, the Move H
intent-tier-coverage gate, and the §3.13 boot/label/A2 remediation). The deferred
**canonical-doc reconciliation is done** (2026-06-15): `docs/explanation/10-ui-ux-design.md`
now records the landing-tier behavior — the window lands in `retrieve`, auto-escalates
to Documents when a chat model is online, and keeps past chats reachable from the
retrieve landing (the resume card). The only **open thread is §3.14** — the affordance-bar
modality design question, which is **unbuilt design-theory and cross-lane** (the Agent
affordance is Goal 2's surface). So 577 is **implementation-complete**; the frontmatter
stays **`active`** solely to carry §3.14's design exploration — a hard flip to terminal
(or spinning §3.14 into its own tempdoc) is a lane-owner decision, not done unilaterally
here.

### 3.13 Remediation — the retrieve tier as a first-class boot/label citizen (2026-06-15, worktree `577-agent-window`)

A cross-window critical pass (code + live browser) found the retrieve tier was wired
as a toggle but not as a first-class boot/label citizen — three defects on one root.
A confidence-building investigation (Explore agents + git/source read + the affected
vitest suites) resolved intent, scoped blast radius, and reclassified one finding.

- **Boot tier (Fix A).** `UnifiedChatView` constructor defaulted `affordance = 'none'`
  (free-chat), so the AI-offline cold start landed in a dead "AI Offline" composer —
  contradicting §3.11 ("the ONE interaction window whose `retrieve` affordance is the
  search entry tier"). Default → `'retrieve'`. The existing aiState auto-upgrade
  (`connectedCallback`: `!userToggledAffordance && capabilities.rag → 'documents'`)
  **contains** the change: AI-online landing is unchanged (retrieve→documents on the
  same tick); only the AI-offline case improves (working search instead of a dead
  composer). `retrieve` stays ephemeral (not persisted; the restore coercion excluding
  it is **intentional**, not a bug — a candidate "Fix C" was dropped).
- **Submit label (Fix B).** `getSubmitLabel()` returned `'AI Offline'`/`'Send'` for the
  retrieve tier (search needs no LLM); added a leading `retrieve → 'Search'` branch and
  gated the `submit-title` "AI offline" tooltip on `affordance !== 'retrieve'`.
- **Nits.** `aria-label="Search results"` on the retrieve list (parity with the
  standalone surface) + a first-pass "Searching…" cue (was a bare "0 results" while
  in-flight). `aria-current` was intentionally **not** added — the retrieve tier has no
  in-list selection model (it opens through the host inspector), unlike `SearchSurface`.
- **Resume card in retrieve (A2).** Because Fix A lands the AI-offline window in the
  retrieve tier — which previously suppressed the "Continue your last conversation?" card
  (it rendered only when `affordance !== retrieve`) — the card is now rendered BEFORE the
  tier split (`renderResumePrompt`), so past chats stay reachable offline (restoring LOADS
  a conversation to read; only a NEW turn needs the model). In retrieve it shows only on
  the bare landing (no query) so it never sits above a live hit-list, and
  `restoreRecentConversation` leaves retrieve (`affordance → 'none'`) so the restored
  thread renders instead of staying behind the hit-list. (Adopted A2 over the minimal A1.)

**Verification.** typecheck clean; FE suite green incl. **3 new tests** (`boots into the
retrieve base tier`; `labels the retrieve submit control "Search"`; `keeps past chats
reachable on the retrieve landing, hidden once searching`). Gates green:
intent-tier-coverage, search-issuance, a11y-closure, controls-a11y, layout-purity,
run-renderers, presentation-purity, observed-state-collapse, message-single-model,
ambient-purity, style-literal-ratchet, atom-fork-ratchet (the `theme-token-closure`
`--font-sans` ghost in `ToolCallCard.ts` is pre-existing, not this change). **Live
(worktree Vite :5199 → backend :61179, AI offline, 206-doc corpus):** cold boot lands in
Search with a working **"Search"** submit (not "AI Offline"); "court" → 122 results with
facet chips + per-hit "Why this result?"; the 122-hit list never became a thread turn
(`thread.length === 0`) — ephemerality preserved.

### 3.14 Open design question — the affordance bar reads as "pick a mode first" (theorized 2026-06-15, NOT implemented)

A post-ship review (user-raised) flagged that the one window's affordance bar
(Search · Documents · Structured · Agent · History) **presents as a modal tab
strip** — it looks like you must choose a mode *before* typing — even though the
substrate is already modeless-entry + escalation (the retrieve hit-list is the
default tier; the query carries when you escalate, §3.10). **The critique is about
PRESENTATION, not the substrate** — and it is narrow to Documents/Structured/Agent
(the in-window search/`retrieve` entry was not in question).

Each affordance still maps to a genuinely different behavior (the §3.7 gate keeps
that 1:1), so the modes are not arbitrary skins:
`Documents → core.rag-ask` (grounded cited answer), `Structured → core.extract`
(needs a user-supplied schema — a *different input*), `Agent → core.agent-run`
(*takes actions* — irreversible risk).

**Researched pattern (2026-06-15).** The industry norm is *auto-route the common
case, keep an explicit control only where behavior is genuinely different or
risky* — Perplexity/ChatGPT keep mode pickers for exactly these distinctions, not
for cosmetic ones. Classic UX (Tesler, "minimize modes, make the current one
obvious") says **reduce modes, don't eliminate them**. Mode errors come from a
*hidden* or *forced-upfront* mode, not from a mode that is a visible consequence
of intent.

**Proposed direction (design-theory, unbuilt) — refines §3.2's "explicit
escalation act" by narrowing WHICH crossings deserve an explicit control:**
- **Documents:** fold into an **inline escalation FROM the results** ("Ask the AI
  about these") rather than a peer tab — modeless entry, answer-on-demand.
  Optionally auto-route a natural-language *question* → answer vs a *keyword* → hits.
- **Structured:** demote from a primary tab to a secondary/"more" control — a
  distinct affordance stays honest (it needs a schema input), just not front-and-centre.
- **Agent:** **keep** it an explicit, deliberate switch — the mode boundary before
  the AI can act is a **safety feature, not a UX cost**; auto-guessing into it
  would be the wrong kind of clever.

Net: keep all four capabilities; collapse the *primary* surface toward one fluid
input (search-by-default, escalate-to-answer inline), and reserve visible explicit
mode controls for the genuinely-different / irreversible cases (Agent especially).
Scope/feasibility deliberately disregarded (design-theory genre); **not** yet
planned or implemented, and it coordinates with Goal 2 (the agent affordance is the
other lane's surface).

## 4. Verification discipline for this tempdoc

- The result-count instability (Goal 1 ledger row 3 / §1.4) was
  **root-caused in §1.8 U1** (LLM expansion nondeterminism, not issuance-path
  divergence); §1.4's dissolution claim is corrected there.
- All presentation changes run the 557/574/559 gate suite for
  `modules/ui-web/src/**` (presentation purity, ambient purity, ratchets,
  a11y closure, layout purity, etc. — see CLAUDE.md pre-merge list).
- Presentation-authority closure expects an independent, measured
  (axe/contrast-oracle), live-verified whole-screen UX audit by an auditor ≠
  committer (honor-system per tier-register rows 30–31).
- Visual verification via `jseval ui-shot` (`/ui-check`); live end-to-end via
  the dev stack with `ai_activate` where the agent window is involved
  (`ai-offline-isnt-a-wall`).
