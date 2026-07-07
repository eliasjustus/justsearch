---
title: "One window, one thread: the Search Thread interaction model"
type: tempdoc
status: implemented
updated: 2026-07-07
implemented: 2026-07-07 (branch worktree-search-thread, S1-S8; live-verified end-to-end with the local model)
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
