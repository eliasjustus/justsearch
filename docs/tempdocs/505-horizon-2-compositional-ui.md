---
title: "505 — Horizon 2: Compositional UI"
---

# 505 — Horizon 2: Compositional UI

**Date**: 2026-05-17
**Status**: draft
**Predecessor**: 504 §Long-term — Horizon 2 (extracted)
**Related**: 486 (consumer feature inventory), 421 (framework kernel)
**Horizon**: ≈ 6–12 months out

---

## Premise

JustSearch today is a **page-at-a-time product**: one surface is
active, the rest are backgrounded. The 421 framework substrate
(Resource categories, ConversationShape SPIs, navigation intents, the
citation substrate) already supports a much richer compositional
model. Horizon 2 is the move from "active surface" to **workspace**.

This horizon is downstream of Horizon 1 (504 §Long-term). The naming
pass, palette, and first-run flow must land first — premature
compositional UI on inconsistent foundations just hides the
inconsistency.

---

## The thesis

Today the user thinks in **surfaces**: "I'm on Search, I clicked
Brain, I went back to Library." Tomorrow they think in
**workspaces**: "I'm working on this document with the model and a
result-set". The framework already declares the primitives — what's
missing is the product composition layer that combines them.

Three composition primitives the framework already provides:

- **Routes encode state.** A URL can carry a query + a selection + a
  scroll position + a chat thread. (486 G124 substrate-design.)
- **Surfaces are mountable Lit components.** Nothing in the framework
  requires one-active-at-a-time. The rail enforces it today; product
  composition could replace that constraint with a multi-pane layout.
- **Citations + navigation intents bridge surfaces.** The G140
  substrate produces typed cross-surface coordinates
  (`{parentDocId, startChar, endChar, startLine, endLine}`). Already
  the seed of a connective tissue between surfaces.

---

## Items

### H2-1 — URL-as-state-of-truth (486 G124)

Routes encode complete surface state. Bookmarks, deep-links,
share-this-view, forward/back across complex workflows. Today the URL
hash carries surface ID + a query parameter; the rest of state
(selection, scroll, pinned filters, chat thread) lives in singletons.

Concrete consequences:

- "Open this view in a new pane" becomes the same primitive as "share
  this URL".
- Workspace presets ("morning triage", "research mode") are saved
  URLs, not custom code.
- Time-travel through workflow history is browser-native (back / forward).

### H2-2 — "Chat as engine, document as dashboard" (486 G139)

Conversation scrollback shows shape-activity chips ("retrieving",
"summarizing", "extracting"). The work artifact (preview, results,
extracted JSON) lives in a **stable pane that survives the chat
ending**. JustSearch already has artifacts (search results, file
preview, citations panel, extracted JSON) that exist separately from
chat — Horizon 2 makes that relationship first-class.

Implications:

- The chat surface stops being a destination; it becomes a tool that
  produces and modifies content in the workspace.
- Reopening a workspace later restores the artifacts; the chat
  thread is a sidebar history of how they got there.
- Composes with H2-1: the workspace URL carries both artifact state
  and chat ref.

### H2-3 — Inspector becomes the workspace

Today: InspectorPane is a preview pane next to results. Tomorrow:
**Inspector is the canvas**. Chat anchored to the document (extends
486 G69). Citations active bidirectionally (extends G140 → reverse
"which answers cite this passage?"). Annotations + highlights
persistent across sessions. The Ask / Answer tabs already point this
direction; the move is to invert the hierarchy so Inspector is the
host, not the sub-pane.

This eats much of the cross-surface fragmentation (504 F-22) — three
"ask the LLM" entry points reduce to one when the document is the
locus.

### H2-4 — Result-set as a first-class object

Today: a search returns results; user picks one; the rest evaporates.
Tomorrow: result-sets are saveable, nameable, comparable objects.

- Multi-select for batch Operations ("summarize all", "extract
  entities from all").
- Save result-set as named view → generalizes 486 G36 pinned searches.
- Compare result-sets across queries (diff view, A/B view).
- Result-set becomes the input to chat shapes (RAG over a saved set,
  Extract over a saved set).

### H2-5 — Reasoning UX maturation

Default-collapsed reasoning blocks (504 F-20 is the immediate
prerequisite). Per-session "show reasoning by default" preference.
Reasoning search ("when did the model think about X across all
sessions?"). Composes with 486 G143 (conversation memory + dreaming)
— reasoning becomes a queryable corpus.

### H2-6 — Cross-shape composition (486 G133 ChainShape)

RAG → Translate → Summarize as a single user gesture. Saved chains
become named "agents-in-the-small" without crossing the boundary
into a full agent runtime. Pairs with 486 G142 plugin shapes once a
plugin contributes one.

This is the substrate-design slice already on the 486 roadmap; H2-6
just names the product affordance over it.

### H2-7 — Citation graph

486 G140 substrate is the seed. Spatial views over the citation
network:

- "What else cites this passage?" (reverse-citation traversal)
- "Which docs informed this answer?" (forward-citation cluster)
- "Diff this doc since last week" (longitudinal view over the same
  passage)

This is the most speculative H2 item — needs a graph rendering
component the framework doesn't have today. Could be deferred to
Horizon 3 if it doesn't fit the timeline.

---

## Cross-horizon discipline (referenced from 504)

Horizon 2 honors the five disciplines named in 504:

1. **Substrate-to-surface latency** — every item here cites an
   existing 486 substrate slot. H2 ships consumers, not new substrate.
2. **Surface metadata** — workspace presets surface `Audience`
   filtering inherently.
3. **First-class empty / degraded states** — workspaces need their
   own empty states; design them up front.
4. **One name per capability** — the workspace primitives (pane,
   artifact, chat thread, result-set) get one name each.
5. **Compositional middle path** — Horizon 2 *is* the operationalized
   middle path between "chat eats everything" and "chat is a sidebar".

---

## Risks

- **Premature composition.** Building H2-3 (Inspector-as-workspace)
  before the cross-surface naming pass (504 A-5) lands repeats the
  fragmentation in a new shape.
- **State-management explosion.** URL-as-state plus multi-pane plus
  saved workspaces multiplies state surface area. Needs a strong
  state-management thesis (single source per pane; URL as canonical
  serialization; presets are pure functions over URL).
- **Reasoning corpus privacy.** H2-5 implies persisting reasoning
  traces — match the local-first promise (default off; per-session
  retention toggle; no external transmission).
- **Performance.** Multi-pane workspaces under SSE streaming + Lit
  reactivity need a profiling pass before they ship.

---

## Next-step gates

This document doesn't unlock work. Each item only opens when the
following are true:

| Gate | Required before |
|---|---|
| 504 A-1 + A-2 + A-3 + A-4 + A-9 landed | any H2 item |
| 504 A-5 (naming pass) landed | H2-3, H2-4 |
| 486 G124 substrate-design slice ratified | H2-1, H2-2 |
| 486 G133 substrate-design slice ratified | H2-6 |
| Performance + state-management thesis written | H2-3, H2-7 |

A dedicated slice spawns from H2 when the gate is met *and* a
specific item is user-ratified. This document is **not** the
ratification.
