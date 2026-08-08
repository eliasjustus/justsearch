# 818 — Search v2: the from-scratch window skeleton (strangler, dev-gated)

```
status: ACTIVE
created: 2026-08-08
owner-decisions: 817 §4 #4 (rewrite planned, design-first) — sequencing REVISED here (owner, 2026-08-08)
design-inputs: 818-prototype/ (v5 structure prototype + identity layer), NOTES-for-818.md (harvest)
supersedes: nothing yet — UnifiedChatView remains the shipped window until the sunset criterion below
```

## 0. Decision and sequencing revision

817 §4 #4 recorded: *"Search-window rewrite: planned, post-0.2.0, design-first — a rewrite
tempdoc settles the interaction model before any code."* This tempdoc is that design doc.
The design phase happened as an interactive structure prototype (2026-08-07/08, preserved
verbatim under `818-prototype/` — serve the directory statically and open `index3.html`;
`?theme` adds the identity layer). The prototype settled the interaction model to the point
of twelve testable laws, a component decomposition with fixtures, and one live-proven
transition (the commit).

**Sequencing revision (owner, 2026-08-08):** the *skeleton* — a from-scratch, dev-gated
sibling window ("Search v2") — is design-phase work and starts now, pre-0.2.0. It is not
user-reachable: no rail entry, hidden route only. **User-facing cutover remains post-0.2.0**
and requires the sunset criterion in §5. Rationale for building beside rather than editing:
`UnifiedChatView.ts` (6,083 lines) has fan-in of exactly one (a side-effect registration in
`Shell.ts`) and its defects are model-level (three parallel conversation representations,
authored counts, state-gated affordances) — a copy would import the disease; a sibling
window makes the comparison empirical.

## 1. The design (settled by the prototype; authority = 818-prototype/index3.html)

One window, one input, meaning escalates. The core object is **one records array**;
transcript, session index, session name, and context ledger are all projections of it.
A live search is not a record; it lives in the deck and becomes a record only by commit.

### The twelve laws (the behavioural test list — each becomes a unit test)

1. **L1** the destination pill is a pure function of visible facts; the ⇥ flip is a
   one-shot lens that dies with the submission or Escape — never stored across turns.
   (Wording tightened 2026-08-08: the flip deliberately survives typing — the pill
   always displays the post-flip truth, so it cannot surprise; "dies with the draft"
   misread as per-keystroke was a slice-5 false alarm.)
2. **L2** a run claims the alt slot (STEER), nothing else — ASK stays reachable mid-run.
3. **L3** scope chips narrow every destination; they change none.
4. **L4** a frozen block is append-only; staleness is a labelled "re-run as new", never mutation.
5. **L5** ask-about-these-N means these N — the frozen snapshot is the retrieval scope.
6. **L6** every count on screen derives from the set it describes.
7. **L7** decisions are incompressible; every deck occupant has a minimum honest form;
   only the list body is compressible.
8. **L8** the transcript records commitments, not attention. Corollary: a session is
   *named and indexed by its first committed record*. (Deliberate asymmetry: user reading
   leaves no record; agent reading leaves a receipt.)
9. **L9** lock gates the *session*, not a button: identical refusal on every send path,
   draft never swallowed, refusal names its exits.
10. **L10** an empty draft submits nowhere; the pill previews, dimmed.
11. **L11** one fragment, many windows: every region renders from one component of
    (data, options); screens compose, never author.
12. **L12** the rail never yields item-by-item. Mode A (no committed record) = conventional
    session sidebar, identical in every pre-session state, collapses whole. Mode B
    (records exist) = session index. The flip is the L8 corollary.

### The commit choreography (the signature transition, live-proven in the prototype)

Causal order is the meaning: (1) the turn lands, (2) the search freezes into the
transcript, (3) the deck collapses, (4) the rail flips and the name appears, (5) the
answer arrives last. The periphery never moves before the record. ~700 ms; instant
under reduced-motion.

### The sidebar (rail mode A): copied convention, five protected divergences

Copied from the thread-sidebar convention (New session + search-sessions header, pinned
group, time buckets, wholesale collapse, hover actions, active highlight, never morphs).
Divergences — deliberate, do not "fix" back:
1. rows wrap, never truncate; 2. objects are SESSIONS, never "chats" (687);
3. hover = pin + lock, DELETE behind … + confirm; 4. New session emphasised, not filled
(the approval keeps the one-filled-element rule); 5. LOCKED badge exists.
The query trail is NOT a rail occupant — it is omnibox-style history in the input band.

## 2. Component decomposition (from the prototype gallery)

`jf-search-v2` (host) · `jf-sv2-session-sidebar` · `jf-sv2-session-index` ·
`jf-sv2-live-search` · `jf-sv2-frozen-search` · `jf-sv2-answer-card` ·
`jf-sv2-approval-card` · `jf-sv2-run-controls` · `jf-sv2-input-band` ·
`jf-sv2-material-rail`. Gallery fixtures in the prototype are the initial test fixtures;
gallery captions are the component contracts. `AgentSessionController` is *kept and
hosted*, not rewritten (799/audit: it is a genuine framework-agnostic seam).

## 3. Slice plan

**Slice 1 — the skeleton (this tempdoc's implementation scope):**
- New directory `modules/ui-web/src/shell-v0/views/search-v2/` — from scratch, no code
  copied from UnifiedChatView.
- The records core: a typed records array + projection functions (transcript items,
  index nodes, session name). This is the load-bearing novelty; everything else reads it.
- `route()` as a pure function + the pill (L1/L2/L10), input band with one-shot flip.
- Live search wired to the real backend (the thing the prototype cannot validate:
  real latency, real result sets, real counts) with derived count labels (L6).
- Commit: live search → frozen record with derived header + the choreography (L8 corollary
  live: name + index appear at first commit).
- Sidebar mode A (sessions may be stubbed or read from the existing conversation list —
  whichever the integration recipe makes cheap) and index mode B as projections.
- Static placeholders only: agent run, context ledger, lock, material rail.
- The laws as unit tests from day one — the from-scratch payoff: L1/L2/L3/L6/L8/L10/L12
  are directly testable in slice 1; L4/L5 partially; L7/L9/L11 as structure permits.
- Mounted at a hidden route, dev-only. **No rail entry** (578: one task, one window —
  a visible peer would recreate the exact defect 687 deleted).

**Slice 2+ (future, separate passes):** agent-run hosting (deck occupancy per L7 with the
computed floor), context ledger + meter, lock semantics end-to-end (L9 across all send
paths), material rail + citation channels, identity layer application, comparison
campaign vs UnifiedChatView, then sunset per §5.

## 4. Guardrails (repo-law compliance)

- **From-scratch components, shared authorities**: consume the same stores/APIs as the
  shipped window. If/when a search-v2 component consumes `SearchTrace`, it must be
  registered in `governance/execution-surfaces.v1.json` (the execution-surface gate fails
  the build on unregistered referencers). Slice 1 consumes the search response only;
  register at the moment a trace referencer appears.
- Custom-element prefix per `customElementPrefix.test.ts` convention.
- ui-web gate set fires on `modules/ui-web/src/**` edits — run the pushed recipe.
- `check-ui-step-coverage` triggers on new RAIL surfaces; the hidden route adds none.

## 5. Sunset criterion (written up front, per retire-with-a-sweep)

Two windows is a phase, not a state. The comparison ends when **either**:
- Search v2 passes the full law suite + a feature-parity checklist (to be enumerated in
  the cutover tempdoc) + a measured UX audit → it is promoted, and **the same PR** sweeps
  UnifiedChatView and its fingerprints (grep names/paths across code, config, gates,
  baselines, docs — label or delete every hit); **or**
- the comparison falsifies the model → search-v2 is deleted in one PR and this tempdoc
  records why.
Predictable evasion, pre-named: "we'll keep both for a while" / "a follow-up PR will
sweep it." 742's corpus is follow-ups that never came.

## 5b. Comparison-campaign scaffolding (authored 2026-08-08; produces §5's evidence, does not make its decision)

**Parity checklist** — v2 must match or consciously supersede each before promotion
(checked = present in v2 as of slice 5):
- [x] live search + facets + honest counts (shared card)  ·  [x] frozen/committed searches
- [x] grounded ASK with citations + grounding stats  ·  [x] answer refusals (locked/error)
- [x] agent run hosting: feed, tool cards, approvals, steer, halt, budget
- [x] document reading pane + citation follow  ·  [x] scope chips (file + set)
- [x] sessions sidebar + session index  ·  [x] lock discipline on all paths
- [x] context meter  ·  [x] zero-result + AI-offline honesty  ·  [x] small-window (≤820px)
- [ ] conversation resume/branch/edit-retry (v2 renders history read-only; no branch/edit)
- [ ] export  ·  [ ] compaction/context floor + exclusion ledger  ·  [ ] extract tier (schema)
- [ ] workflow trigger  ·  [ ] Simple/Detailed projection toggle
- [ ] walkthroughs/empty-state registry integration, command-palette actions

**Procedure** (dev stack + `serve-worktree-fe`, model active, same corpus): run each task
on BOTH windows, record task success, count honesty (every on-screen number vs its set),
and interaction dead-ends: (1) find a named file; (2) refine with facets; (3) open + read
a result; (4) ask about a committed set, follow a citation; (5) delegate a bounded task,
approve one step, steer, halt; (6) hit a lock mid-flow; (7) zero-result query; (8) all of
it at 790px height. Measured pass: axe + the contrast oracle via the ui-shot harness
(`/ui-check`), whole-screen, both windows, auditor ≠ implementer (slice-execution
discipline). Score against §6's defect-class table: each class must be demonstrably
absent in v2 and (where applicable) still present in the shipped window.

**Output**: a dated results table appended here; §5's sunset call then needs only the
owner's judgment on top of it.

## 6. Defect classes in the shipped window this design kills by construction

| Shipped-window defect (evidence) | Killed by |
|---|---|
| count-truthfulness recurrence (597 → 690 I1 → 817 S5) | L6: derived labels + count audit |
| stale search-results-as-transcript (817 S6) | freeze model (L4/L8) |
| New chat state-gated/unreachable (obs. `UnifiedChatView.ts:2114`, seen 6×) | sidebar header: always-visible New session |
| affordance/schema desync on card fork (obs. `:3735`) | pure `route()` + one-shot flip (L1) |
| locked-path text loss (obs. `:5673`) | L9 session-level gate |
| three parallel conversation models (`mergedTimeline:3816`) | one records array (L11) |
| triplicated reset protocol (`settleTransients:1077` et al.) | projections have no state to reset |

## 6b. The presentation pass (slices 4–5, designed 2026-08-08; owner additions incorporated)

Scope: bring the in-app window to the prototype's spatial/motion discipline, plus two
owner-directed principles, formalised as laws:

13. **L13 — every movable boundary is clamped by the minimum honest forms on both
    sides.** The deck's vertical grip generalises: BOTH rails (session rail, material
    rail) gain horizontal grips. Clamps derive from content (rail floor = its collapsed
    strip / minimum readable form; the centre column never drops below the reading
    measure). Difference in memory: **rails remember** (a width is a user preference,
    persisted), **the deck resets** (a height is per-session-shape). Widths must respect
    the 816 sizing-role register; double-click returns any boundary to automatic.
14. **L14 — the resting surface shows the identifying minimum; elaboration extends on
    hover AND keyboard focus, never by default.** Applies to sidebar rows (resting:
    title + LOCKED; extended: meta + actions), index nodes (extended: record detail),
    context meter (extended: breakdown), frozen headers and receipts (extended:
    timings/mode). Hard boundary: **honesty facts never hide behind hover** — counts,
    verdicts, LOCKED, grounding stay resting-visible; only elaboration extends. Focus
    parity is mandatory (hover-only fails accessibility); the shared results card
    already follows this principle and is not forked.

Slice 4 (motion + vertical space): commit choreography (record lands first, periphery
follows, ~700 ms, reduced-motion instant); deck grip with computed floor + collapse to
count line; unhappy states (zero-results with n=0 escalation labels, AI-offline pill
honesty); small-window pass at ~790 px bound to the 814 height-budget substrate.

Slice 5 (horizontal space + hover + input): L13 rail grips; L14 hover/focus extension
sweep; omnibox query-trail dropdown (pinned + recent searches — the rail never shows
them, L12); citation-follow landing (document pane at cited range, land-strong-then-
settle); keyboard pass (⌥↑/⌥↓ index nav — never while typing, Escape order); copy audit
(one verb per action, user-side naming).

Parallel tracks (not in these slices): identity directions as swappable theme files
(owner picks); comparison-campaign scaffolding (§5 evidence); matchCount-inversion
diagnosis (observations inbox item).

## 7. Log

- 2026-08-08 — tempdoc created from the prototype harvest; worktree `818-search-v2`;
  prototype v5 (+identity layer) preserved under `818-prototype/`. Slice 1 delegated.
- 2026-08-08 — **Slice 1 implemented** (opus worker + independent orchestrator review).
  `views/search-v2/`: `records.ts` (the one records array + projections), `route.ts`
  (pure routing + flip lens), `SearchV2View.ts` (host `jf-search-v2`), 27 law-named
  tests (route 8 / records 11 / view 8). Mounted DEEPLINK/DEVELOPER at
  `#justsearch://surface/core.search-v2-surface`; registration = lazySurfaceRegistry +
  CorePlugin + registry-surface properties (label catalog); component-vocabulary
  regenerated. Full ui-web gate recipe green (one root-cause fix: the destination pill's
  class renamed `.rung-pill` — it is not a status-badge atom, per the atom-fork ratchet).
  Typecheck clean, 4329 unit tests green, `gradlew build -x test` green.
  Accepted worker deviations: strict L10 (empty-draft commit is a no-op, stricter than
  the prototype's default-text fallback); no `setConversationApiBase`/`setQuery('')`
  writes into shared singletons (correct — shared-state hygiene).
  **Live-verified** against the real dev stack via `serve-worktree-fe`: mount, no rail
  peer, real search (backend hits), commit → derived name + index flip + frozen block +
  pending answer, all on screen. Live finding logged to the observations shard:
  backend returned 5 result rows with `matchCount=4` (597-class count inversion at the
  API level — pre-existing, not a search-v2 defect; the frozen header derived honestly
  from its captured set).
- 2026-08-08 — **Slices 2+3 implemented** (two sequential opus workers + orchestrator
  review + full live verification). Slice 2: `jf-results-card` reuse for live AND frozen
  lists (the inline rows were a fork of the product's one results projection — deleted,
  along with search-v2's own count-label fork, in favour of the card's `matchCountLabel`
  authority); the real ASK tier (`askClient.ts` — the ONE dispatch site by construction,
  `core.rag-ask` via `buildRequestBody` + `consumeShapeStream`, typed handlers);
  `AnswerRecord`/`refused-answer` slot terminals (filling the slot is the record's own
  lifecycle; everything else carried by identity — L4 tested); `jf-citations-panel` +
  `citationResolve` reuse; `jf-document-pane` on card-open + file scope chip (shared
  `searchState` chip authority — L3 real); L5 literal (ask docIds === frozen set);
  L9: one `refuseLocked` path, 423-caught, draft never swallowed. Slice 3: agent hosting
  via the shared `AgentSessionController` (all directives through the
  `dispatchRunControl` seam — `check-steering-arbitration` green); live run feed with
  `jf-tool-call-card` reuse; ONE `agent-run` receipt record at run end with derived
  counts; L7 deck (controls outside every scroll container, DOM-asserted); L2 live
  (`runInFlight` claims the alt slot only, releases on end); `refuseIfLocked` pre-gate
  shared by commit AND delegate (the shipped agent-branch bypass class, killed);
  time-bucketed sessions sidebar (pure, `now`-injected). Registers: +3
  execution-surfaces, +1 live-channels, run-renderers mountSites, steering-surfaces
  adopters. 82 law tests in search-v2 (suite 4384 green), typecheck clean, full gate
  recipe green (3 worker-caught root-cause fixes: `groundedSentencesLabel` rename,
  `.pin` atom rename, live-channel registration; 4 known pre-existing failures in
  other files unchanged). **Live-verified with an ACTIVE GPU model**: real streamed
  cited answer (correct content from the docs corpus, 5 sources in the citations
  panel, context meter at real 43%/4096), real agent run (feed + tool card + budget
  37%/3840 + STEER claiming the alt slot), halt through the seam → derived receipt
  "Run halted by you · 2 tool calls", index/entries projections consistent at 5,
  alt slot released. Known residuals recorded: no chat-issuance gate exists
  (askClient is single-site by review, not by gate — candidate for a register if the
  two-window phase persists); mid-run lock (a lock taken between status polls) is not
  refused on the agent path — needs a typed lock terminal on the agent stream
  (controller work, out of this window's scope).
- 2026-08-08 — **Slices 4+5 implemented** (presentation pass; two sequential opus
  workers + orchestrator review + live verification). Slice 4: commit choreography
  (prototype timing ported, last-animation-owns-teardown, reduced-motion doubly
  disabled); deck grip with drag-time computed floor (`deckSizing.ts` pure math,
  keyboard-operable native button, run controls raise the floor); zero-result honest
  empty + n=0 affordance labels; AI-offline via the `projectAvailability` authority
  with a REACHABLE reason (a11y gate caught title-on-disabled — root-caused, not
  suppressed); 814 binding by consuming the `shortViewportMax` breakpoint authority +
  one-scroller-per-region (registration deferred with evidence: the proportion register
  is keyed by ui-shot step; obligation written where the §5 cutover will read it).
  Slice 5: rail grips per L13 (`railSizing.ts`; floors/ceilings cited to tokens.css +
  ui-proportion-baseline minWidthPx rows; rails remember via localStorage, forget on
  automatic); L14 hover/focus extension as ONE mechanism (`.ext` + clip-path, AT-visible;
  hard boundary structurally tested — counts/verdicts never inside `.ext`); omnibox
  query trail (pinned via `pinnedSearchState`, recents = session frozen queries +
  capped submitted-trail; fills-never-commits); citation landing via the shared panel's
  own `citation-select` → `jf-document-pane` at the cited range (land-strong-settle
  already lives in the pane — nothing forked); keyboard pass (⌥↑⌥↓ never-while-typing,
  Escape order trail→pane→flip, full Tab traversal); copy audit (verb set unified on
  ASK, user-side naming, ~20 strings — table in the slice-5 worker report).
  145 search-v2 tests (suite 4447), typecheck + full gate recipe + eslint green; the 4
  known pre-existing failures unchanged in other files. **Live-verified** (stack +
  worktree FE + active GPU model): mount, both grips (document grip appears with the
  pane), commit choreography fires and tears down, real cited answer, citations panel,
  citation click landed the pane at `629-data-at-rest-encryption.md` lines 2360-2381.
  Live-inconclusive (unit-covered): omnibox open-on-focus — synthetic focus events are
  untrusted in Chrome and the screenshot path was down, so no real-click check.
  L1 wording tightened (above) — the slice-5 "flip survives typing" observation was
  the designed behavior, not a defect.
  Parallel tracks: §5b campaign scaffolding authored; matchCount inversion ROOT-CAUSED
  to lexical-only `matchCount` vs 3-leg fusion window admitting dense/SPLADE-only rows
  (full chain in the observations shard; needs its own count-semantics tempdoc);
  identity directions authored as three swappable themes: **Registrar's Ledger**
  (light — sage paper, iron-gall ink, "green is evidence", wax is risk),
  **Night Registry** (dark — reading-room green-black, lamp-green evidence, ember risk),
  **Cyanotype** (mid-dark chromatic Prussian ground — "cyan means exposure"; its one
  declared risk). All under `modules/ui-web/public/themes/` + manifest entries; default
  UNCHANGED — owner picks. Every text/surface pair measured (body ≥4.5 everywhere;
  matrices in the worker report), plus a CVD simulation (protan/deutan/tritan ΔE) that
  drove three value corrections; validation + manifest schema green. Owner notes before
  adopting one: the chosen theme's filename must join `builtinPaletteContrast.test.ts`
  `BUILT_IN_PALETTES` and ideally `themesCatalog.ts` `BUILT_IN_THEMES`; the ledger's
  intended IBM Plex faces need local bundling (three related out-of-scope findings in
  the observations shard, incl. sepia-focus's 1.39:1 text-grade gaps).
