---
title: "One window, one thread: the Search Thread interaction model"
type: tempdoc
status: implemented
updated: 2026-07-07
implemented: 2026-07-07 (branch worktree-search-thread, S1-S8; live-verified end-to-end with the local model)
round2: 2026-07-07 implemented + live-verified (R1-R5; residue: agent-history ingest guard — see observations)
created: 2026-07-04
related: [497, 526, 561, 577, 596, 602, 609, 613, 678]
---


# 687 — One window, one thread: the Search Thread interaction model

## Context

The unified surface currently presents four affordance tabs (Search / Documents / Structured / Agent) inside a window titled "Chat," while a vestigial deep-linkable Search view survives beside it and the inspector pane carries a third conversational context (its Ask/Answer tabs). A 2026-07 design audit (private research; only decisions cross per ADR-0045) found this split to be the frontend's deepest UX debt: users must pre-classify their own intent before typing; the same corpus question has three homes; and the escalation path from results to AI is absent from the default search tier entirely.

The direction request: users increasingly expect to simply converse with an agent that can search their files, in one place — without losing the instant, no-AI search that is this product's identity and the entire product for model-less installs.

## Decision

Adopt the **Search Thread** model:

1. **One surface: Search.** "Chat" retires as a place-name; conversation is a state of Search. The vestigial standalone Search view and its bridging navigation toasts are removed; deep-link aliases resolve to the one surface.
2. **Floor rule (invariant).** Every input queries the index as-you-type; instant results render in a **live card** in milliseconds, before and regardless of routing. The LLM is never between the user and search. With no model installed, the floor *is* the product — never presented as a fallback mode.
3. **Route, per turn.** Each committed turn routes to *Search* or *Ask* via a visible **route chip** on the bar — heuristic-inferred, toggleable before commit, correctable in one click after (card → "Ask AI about this"; agent turn → "Just search this instead"). No affordance tabs. With no model, route is pinned to Search and *Ask* renders as typed-Availability disabled-with-reason (596 pattern).
4. **One results card.** The shared result presenter (602) is the only rendering of search results anywhere: the live card (one instance, mutates in place during query iteration, owns a local query trail); **snapshot cards** (committed thread events with provenance headers stating the actually-executed query — agent reformulations are thereby exposed); **excerpt chips** (collapsed one-liners inside agent narration, expandable). Interacting with an old snapshot forks a new live search; thread history is append-only. Agent tool-searches render as these same cards.
5. **Scope chips** replace the Documents tier, the inspector's Ask tab, and any per-card interaction dropdowns: "Ask about this" on any file/result-set adds a removable chip to the bar; chips scope both the instant-search floor and agent retrieval. (Folder scope deferred to the Browse redesign.)
6. **Structured** becomes an output-shape request (natural language + an optional schema attachment on the bar), not a mode. **Agent** becomes the autonomy dial (Watch/Assist/Auto) on the bar, governing how much an *Ask* turn may do unattended; the run frame attaches to the turn block; completed runs collapse into a neutral receipt.
7. **The Reading Stage** (document viewing) is a split view of document + *the* thread, auto-adding the document's scope chip. The inspector's Preview/Context/Answer/Ask tabs are retired (Preview → rendered document pane with block-level source mapping; Ask/Answer → the thread; Context → removed as an unshipped placeholder). There is exactly one conversational context in the product.
8. **The bar** is centered on an empty surface (landing composition) and bottom-docks in an active thread, directly under the live card. Full-width submit buttons are removed; Enter is the verb.

## Supersedes / preserves

- **Completes** the one-window consolidation (tempdocs 497 → 561 → 577): the retrieve-default is preserved *as the floor* — strengthened from a default tab to an invariant.
- **Preserves** 602 (one shared presenter → the card), 526 (multi-select → scope), 596 (typed Availability → the pinned route chip), 609 (instance retention; restoration becomes all-or-nothing per turn — a restored draft restores its route), 613 (receipt locality — extended by the attention policy).
- **Does not re-litigate** 497-v1.1's retired RAG-first default: nothing routes to the LLM by default; the floor always answers first.
- **Retires**: the affordance tab row; the standalone Search view; navigation toasts; the inspector's tab set; the "Chat" surface name; full-width Send/Search composer buttons.

## Consequences

- The result card component absorbs the standalone SearchSurface's honesty instrumentation (truthful funnel count, latency, effective-mode indicator, quick→refined lifecycle with a terminal "refined ✓") — closing the current parity gap where the default tier lacks all of it, including any Ask-AI escalation.
- Grounding verdicts, sources (grouped by document), and budget/context accounting land in a shared per-turn **receipt** component; sentence-level citation marking inverts (grounded = clean text; weak/own-words = marked).
- One degradation ladder (no models → encoders-only → full) on one surface; capability state surfaces as a bar chip sourced from the single verdict authority, not per-surface banners.

## Resolved parameters (settled 2026-07-05)

- Keys: **Ctrl+Enter = send via the other route** (no standing toggle; route chip clickable); bar focus = **Ctrl+L** always, **`/`** when the bar is empty/unfocused.
- Live→snapshot commit rule: **commit on consequence** (open / ask / pin / agent-context) — never on plain query iteration; the live card keeps a local query trail.
- Default autonomy: **Assist**; Auto is opt-in behind a one-time consent moment, sticky per user.
- Run-spine minimap: **not in v1**; revisit only if real run lengths exceed a screenful.
- Rail: **expanded with labels** by default, collapsible, persisted.
- Theming (context for the card/receipt styling): accent = **teal family**, amber exclusively semantic warning; **one theme model** (Default + High Vis first-party; Nord/Sepia as bundled presets; accent-skins layer retired into theme properties).

## Open items

- Thread auto-collapse thresholds (tune against real thread lengths).
- Folder-scope semantics — deliberately deferred to the Browse redesign (recursion/liveness answered there).


## Round 2 — attention, trust, and geometry (design settled 2026-07-07)

A post-implementation audit (live, dev environment; measured element geometry) found the
shipped model correct but its ATTENTION ECONOMY wrong in places, plus a handful of trust-surface
gaps. The round-2 design below is decision-level; all rulings confirmed by the maintainer.

### R1 — Attention: loud states decay; the exception carries the signal
- **Degradation banner**: stays inside the one verdict authority, but a notice seen once
  collapses to a single line (cause count + strongest remedy), expandable. The headline and
  its first bullet must never restate each other (single-cause dedup). *Supersedes:* the
  always-expanded multi-bullet rendering. Audit measurement (dev run, 2026-07): the expanded
  banner consumed more vertical space than the content it sat above.
- **Citation highlight**: reuses the card's existing decaying-emphasis idiom (the refined-✓
  stamp): land strong, decay to a quiet tint + edge marker; the surrounding-chunk tier never
  gets the loud phase. *Supersedes:* the permanent solid-block highlight.
- **Grounding marks invert**: grounded text renders plain; only spans BELOW the per-sentence
  support threshold get marked (the per-claim score the RAG path already accumulates is the
  data source). An indicator that is on almost always carries no information; the rare
  unsupported sentence is what needs the reader's eye. *Supersedes:* the always-on
  grounded-span underline.
- **Active-citation mark** unifies with its sibling notation (see R3) instead of swapping to
  a filled box.

### R2 — One gesture, one meaning
The card's "Ask AI" sends immediately, exactly like the route chip's Enter (a modified
activation stages without sending, for the rephrase case). Two escalation affordances with
two behaviors was an unforced inconsistency. *Supersedes:* the stage-only card behavior.

### R3 — Trust surfaces are literal
- **Citation notation**: model-emitted "[n]" normalizes at render into the same superscript
  citation marks the renderer already draws (only for n that resolve to a real citation on
  that answer). One notation per answer.
- **Document count**: the status-bar count must partition cleanly (user corpus vs seeded
  help collection vs runtime artifacts); an investigation item verifies the interaction-event
  run store cannot leak into it. A count a user can't reconcile is a trust defect, not a
  cosmetic one.
- **Auto consent**: consent is required for the STANDING state, not just the transition — a
  profile already at 'auto' with no recorded consent is asked once. Grandfathering only ever
  benefited pre-release dev profiles.
- **First-query latency**: the instant floor's identity claim is tested hardest on the very
  first query after boot; a worker-ready warmup pass (one throwaway query through the live
  path) removes the cold-start penalty rather than decorating it with UI.

### R4 — The bar conforms to its own atom
The composer row's secondary affordances (pin, schema attach, delegate entry) become
compositions of the existing `jf-control` atom in a single QUIET tier; the route chip is the
row's one visually primary element, grouped with its functional siblings instead of floating
at the viewport edge. *Supersedes (orphans I introduced in round 1):* the bespoke
`.pin-toggle` / `.schema-attach` / `.escalation-delegate` button styling — deleted with this
work, not a later sweep.
### R5 — Layout owns its composition
- **Landing**: ONE flex column owns title → corpus → bar → strip. *Supersedes:* the split
  composition (intro in the conversation column + a viewport-margin-positioned bar) whose
  bands interleave at short viewports — the audit's measured overlap.
- **Narrow viewport**: below the wide breakpoint the reading pane presents through the
  shell's existing OverlayHost (the one sanctioned overlay seam), not an implicit grid row —
  conforming to the same layout rule that forbade a bespoke fixed overlay in the first place.
  *Supersedes:* the auto-placement stacked row (measured colliding with the composer).
- **Reading-pane provenance header** truncates through the shared `formatDisplayPath`
  authority (filename-preserving), not CSS end-truncation.

### Principles recognized (recorded, deliberately NOT built as structure)
- **P1 — Emphasis is a transient, not a state.** Existing conforming instance: the card's
  refined-✓ decay. Violations found: citation highlight, degradation banner, active-citation
  box. Candidate scope: any persistent accent-filled state on any surface. Earns its keep if
  future audits stop finding "screaming" persistent states and new surfaces adopt the decay
  idiom unprompted. Retire if measured use shows decayed states get MISSED (findability
  regressions) — then decay was the wrong tool, not a law.
- **P2 — Mark the exception, not the rule.** Conforming: route-chip pinning (signals only the
  pinned exception). Violations: grounded-span underlining, banner headline/bullet
  duplication. Candidate scope: any per-item quality marking (extraction quality chips, OCR
  confidence). Earns its keep if exception-marks show real engagement (clicks/hovers) where
  wall-to-wall marks showed none. Retire if the underlying scorer is too noisy — false-positive
  exception marks erode more trust than no marks; in that case mark nothing until the scorer
  improves.
- **P3 — Trust surfaces are literal.** Counts, consents, provenance lines mean exactly one
  reconcilable thing. Conforming: the answer receipt. Violations: the unexplained document
  count; grandfathered consent. Candidate scope: every status-bar figure and every
  "N sources" line. Earns its keep as a review question that keeps catching drift. Retire
  (narrow) if it starts forcing raw-number dumps where users need derived summaries — the
  principle governs reconcilability, not rawness.
- **P4 — Chrome yields to content (vertical budget).** The audit measured chrome outweighing
  content ~2.6:1 in a degraded dev state. Candidate structure (NOT built now): a
  chrome:content ratio assertion in the existing measured ui-shot pass. Build it only if a
  second independent audit finds a chrome regression this check would have caught; retire the
  idea if layouts stabilize and manual audits stop finding vertical-budget faults.

Round-2 scope judgment: everything above is refinement INSIDE the shipped model — no new
surfaces, no new state authorities, no schema changes. The one new mechanism (worker-ready
warmup) rides an existing lifecycle hook. Size of change follows from that: small, spread
across existing seams, with four orphaned styling/composition fragments deleted in the same
work.


## Round-2 research annex (external landscape check, 2026-07-07)

Targeted pass over the three actively-moving areas the round-2 design bets on. Design
references only — no external code/text/assets copied or adapted (nothing to attribute
beyond the citations below).

- **Grounding/attribution UX**: current research converges on the exact failure mode P2
  addresses — users treat citation PRESENCE as verification while models emit unsupported
  sentences at high rates (ALCE-line benchmarks; industry writeups). Research interfaces
  grade evidence by RELATION (supports/contradicts), and "attribution gradients" work argues
  for graduated unfolding (mark → excerpt → source) — which the card → reading-pane pipeline
  already implements. Refinement recorded for P2's candidate scope: CONTRADICTED spans are a
  distinct tier from unsupported ones; the per-claim scorer only supports the latter today —
  do not build the contradiction tier until the scorer can carry it honestly.
- **Unified search/answer interfaces**: the converged commercial pattern (answer engines)
  routes EVERY query through model synthesis. The floor rule is therefore a genuine
  differentiator, not a lag — instant, model-free results as the primary loop, with visible
  user-correctable routing (the route chip) where competitors route silently. No design
  change; raises the floor rule's standing from "decision 2" to the model's identity claim.
- **Agent autonomy consent**: 2026 practice has standardized on the same three-tier shape as
  Watch/Assist/Auto (suggest / co-pilot / autopilot) and names the "autonomy dial +
  progressive authorization" pattern this tempdoc shipped. The published approval-flow
  architecture for high-stakes actions (action + reasoning + impact + rollback + expiry)
  exceeds our Assist ceremony by two fields (impact estimate, expiry); the undo ledger
  already covers rollback. Recorded as candidate scope, not built.


## State of record (2026-07-07, implementing session closed)

For continuation WITHOUT the implementing session's context. All work lives on branch
`worktree-search-thread`; no PR yet (maintainer-gated).

### Implementation map (commit → scope)
Round 1 (the model, S1–S8): `4ffd677` card · `a325c84` route/floor/landing + scope state +
thread tolerance · `d704e8e` scope mount + SEARCH events · `631bd7e` committed cards ·
`84a39e6` computed affordance · `794b73f`+`b528c80` the retirement · `0d0ec83` reading stage ·
`0f826f3` agent scoping (backend) · `a0b611e` agent cards/consent/receipt · `adddaf4` e2e fixes.
Round 2 (R1–R5): `532584d` grounding inversion + [n] normalization + boot warmup · `8cb6f4b`
banner/decay/truncation/ask-sends/consent/bar-conformance · `a74c7b7` landing column + narrow
overlay mount · `67a5c36` close + Q6 finding.

### Verification evidence (claim → pointer)
- FE suite green: `npm run test:unit:run` — 362 files / 3714 tests (last run at `a74c7b7`).
- Java suite green: `./gradlew.bat test -PskipWebBuild=true` exit 0 (last at `0f826f3`/`532584d`).
- R1c/R3a: `MarkdownBlock.test.ts` suites "687 R3a — literal [n] normalization" and
  "687 R1c — grounding-mark inversion" (30/30).
- R3d: `GrpcSearchServiceWarmUpTest` (3 tests; the differential `OperationalMetrics` check is the
  no-side-effects proof); live: "Search path ready" in the worker log of dev run `9185c4a8…`;
  first-query HTTP 870ms→330ms (curl timings, implementing session).
- R1a: UnifiedChatView round-2 banner tests; live: collapsed state persisted across reload
  (dev run `9185c4a8…`).
- R2/R3c/R1b/R5c/R4: suites in `ResultsCard.test.ts` / `AutonomyDial.test.ts` /
  `DocumentPane.test.ts` / `UnifiedChatView.test.ts` (137 in the view suite); the R4 accent
  culprit was `composerStyles`' `.composer button` light-DOM reach — fix is jf-control shadow
  conformance, gate-verified (controls-a11y, atom-fork net reduction).
- R5a: the stable-slot node-identity test (composer textarea identity across landing↔docked)
  + live centered-column screenshots (implementing session).
- Gates: full ui-web check list green EXCEPT the four pre-existing-at-base failures below.

### Unverified assumptions & deferred checks (each needs its 15–60 min)
1. **R5b at a real CSS breakpoint** — verified at the state level only (grid pane unmounts,
   drawer pane mounts in the OverlayHost right-drawer slot with `overlay` sizing 448×733);
   the automation could not physically resize the window. Verify in a real narrow window.
2. **Live with-citations weave** — strip/upgrade paths are unit-pinned but were not observed
   live (the live test answer carried no citation records; see follow-up F2).
3. **Uncited-span exception marking** — deliberately deferred pending live claim-score
   distribution sampling (P2's retirement condition governs; do the sampling before choosing
   a threshold).
4. **Residual ~350ms steady-state hybrid latency** on a 6-doc corpus — separate scope from
   the (fixed) cold start; unprofiled.

### Follow-ups that must not be lost
- **F1 (confirmed trust bug)** -> tempdoc 692: a dataDir runtime artifact (`.dev-data/agent-history/<uuid>.md`)
  was ingested UNTAGGED by the generic watcher, bypassing the 585-D4b reserved-collection
  exclusion (doc count 5→6; ranked in results). Structural fix: the worker refuses to ingest
  under its own dataDir (prefix guard at the scanner/watcher).
- **F2** -> tempdoc 693: bullet-list answers defeat the citation sentence-matcher — sources retrieved but ZERO
  marks woven, leaving the model's dangling "[n]" unlinked. The weave needs list-item-aware
  segmentation.
- **F3**: two duration authorities disagree on-screen (run frame wall-clock vs the receipt's
  generation time). One authority, or label both.
- **F4** -> tempdoc 693: a bare trailing "[n]" line escapes `stripTrailingCitationBlock`'s shape.
- **F5**: DocumentPane's fetch-failure state renders a raw error string; needs the designed
  disconnected/diagnostic presentation.
- **F6** -> tempdoc 694 (stub): visual-grammar / density: the working surface over-spends space systemically
  (attention inversion, row-per-scrap stratification, metadata-before-content duplication,
  implementation leakage, no alignment spine). An INDEPENDENT visual audit (fresh agent,
  screenshot-based) is planned as the next design input — do not pre-empt it with piecemeal
  padding fixes.
- **F7**: the Python jseval ui-shot harness still assumes the retired InspectorPane's tab
  structure; `InspectorTabRegistry` is an orphaned contribution point.
- **F8**: pre-existing-at-base gate failures, NOT from this branch (verified against base
  `2ef7396`): `contract-projection` ×2 (schema-types drift `vduProcessing`; register-drift
  `InferenceStatusResponse`), `ts-any` (`MultiplexedStream.ts:60`), `check-theme-token-closure`
  (`RecentsMenu.ts`), `check-accent-as-text` (`ActionLedgerView.ts`) — they belong to other
  work streams' areas.
