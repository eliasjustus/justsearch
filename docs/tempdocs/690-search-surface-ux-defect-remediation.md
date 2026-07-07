---
title: "Search surface UX defects: the register and its remediation"
type: tempdoc
status: implemented
created: 2026-07-07
updated: 2026-07-07
related: [687]
---

# 690 — Search surface UX defects: the register and its remediation

Companion to tempdoc 687 (the Search Thread interaction model). 687 carries the
*model* decision; this register names the concrete UX defects that motivated and
accompanied it, and how each was fixed. All fixes live on branch
`worktree-search-thread`; evidence pointers are in 687's "State of record".

## Structural defects (fixed by the 687 model itself)

| # | Defect | Fix |
|---|--------|-----|
| D1 | Users had to pre-classify their own intent across four affordance tabs before typing | Tabs retired; the standing tier is computed (`deriveAffordance`: explicit choice > schema attachment > route > retrieve floor); a per-turn route chip carries the search/ask decision |
| D2 | The same corpus question had three homes: the standalone Search view, the chat window's tier, and the inspector's Ask tab | One surface; standalone SearchSurface deleted with its registry footprint; inspector tabs retired; deep links alias to the one window |
| D3 | No escalation path from search results to AI — the default tier dead-ended | Route chip (Enter sends via the inferred route, Ctrl+Enter the other), card-level "Ask AI", scope chips ("Ask about this" on any hit) |
| D4 | Search results were ephemeral — leaving the view or asking a question discarded what the user had found | Commit-on-consequence: opening/asking/pinning freezes the live card into a snapshot thread event with a provenance header; persisted server-side (`POST /api/thread/{id}/events`) and restored as excerpt cards |
| D5 | Agent tool-searches rendered through a bespoke evidence renderer, unrelated to user-facing results | One card everywhere: agent searches render the shared `jf-results-card` (excerpt variant) exposing the agent's actually-executed query |
| D6 | The AI capability appearing/disappearing silently yanked the user between tiers | Auto-upgrades deleted; capability changes alter availability chrome only; route pins to Search with a reachable reason when the model is offline |
| D7 | Navigation toasts bridged the split surfaces; back-navigation depended on them | Toasts retired; `previousActiveId` bookkeeping moved into `setActiveSurface` so back keeps working |
| D8 | "Chat" naming misdescribed the product's center of gravity | The surface is Search everywhere (label authority, rail, tab title); conversation is a state of Search |

## Interaction defects

| # | Defect | Fix |
|---|--------|-----|
| I1 | Count line could contradict the visible list ("1 matches"; shown > matched unexplained) | `matchCountLabel` classifies the surplus ("N results · M matched exactly") with correct pluralization; the card owns count coherence |
| I2 | The landing→docked transition re-parented the composer, dropping keystrokes racing the first render (a fast typist's sentence truncated to its first character) | Stable-slot rule: the composer never re-parents; landing is CSS-only; node identity pinned by test |
| I3 | Two empty-state messages rendered for one empty result set | One authority: the card's meta line carries "No matches for …" |
| I4 | The bar was unreachable by keyboard from elsewhere in the app | Ctrl+L and `/` focus it; a dispatcher guard keeps modifier-less bindings from stealing keystrokes inside editable controls |
| I5 | Full-width submit slab dominated the bar | Root cause was a light-DOM class collision between the view's column layout and the composer's own row; fixed; Enter is the verb, the button is compact |
| I6 | Delegating to the agent had no entry point once the tabs died | The landing strip's Delegate line is a real, availability-gated entry |
| I7 | New chat could strand the user in a stale state (old thread events kept the landing away) | `newConversation` clears the unified record (events/lifecycles/budget) |
| I8 | First switch to Auto autonomy acted without informed consent; a pre-existing 'auto' level bypassed the new consent entirely | A consent dialog gates the transition AND the standing state; cancel downgrades to Assist |

## Presentation defects (round 2)

| # | Defect | Fix |
|---|--------|-----|
| P1 | The degradation banner could outweigh the content it sat above and restated its own headline as a bullet | Collapses to one line after first sighting (persisted per cause-set; a changed cause-set re-expands once); reindex headline/bullet dedup |
| P2 | Grounding underlines marked nearly every sentence — an always-on indicator carrying no information | Inverted: well-grounded prose renders plain; only weak/unsupported tiers carry a mark |
| P3 | Citation notation was mixed: renderer superscripts alongside the model's literal "[n]" | Literal tokens normalize in the render pass — deduplicated against woven marks, upgraded to real marks otherwise; code blocks and non-citation numbers untouched |
| P4 | The citation highlight was a permanent solid block; passage and chunk tiers were indistinguishable | Lands strong, decays to a quiet tint + edge marker (reduced-motion lands quiet directly); the chunk tier never gets the loud phase |
| P5 | The reading pane's path header truncated away the filename | Shared filename-preserving middle truncation (`formatDisplayPath`); full path in the tooltip |
| P6 | Card "Ask AI" staged silently while Enter sent — two escalation gestures, two behaviors | Both send; a Shift-modified activation stages for rephrasing |
| P7 | Secondary bar affordances (pin, schema, delegate) rendered louder than the primary route chip — a broad `.composer button` accent rule overrode every quiet single-class style in light DOM | Conformance, not specificity war: they compose the `jf-control` atom (shadow-immune) in one shared quiet tier |
| P8 | The landing's intro and bar were two layouts approximating one composition; their bands interleaved at short viewports | One flex column owns title→corpus→bar→strip, centered for real in the freed space |
| P9 | At narrow widths the reading pane's stacked row collided with the composer | The grid mount is wide-only; narrow presents the same pane through the OverlayHost right-drawer slot |
| P10 | The first query after boot paid a ~870ms cold start against the product's instant-search identity | A boot-time warm pass through the real search path (worker-internal, below every telemetry/feedback writer — proven by a differential-metrics test); first query now ~330ms |

## Still open (carried in 687's follow-up list F1–F8)

The confirmed ingest-scope leak (a runtime transcript artifact counted and ranked as a
user document), the bullet-list citation-weave gap, the duration-authority conflict,
the trailing-"[n]" stripper shape, the pane's undesigned offline state, and the
systemic working-surface density question (awaiting an independent visual audit before
further changes).
