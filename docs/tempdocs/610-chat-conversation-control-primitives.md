---
title: "Chat conversation-control primitives: transcript lineage + a new effective-context lineage"
type: tempdocs
status: merged
created: 2026-06-18
updated: 2026-06-19
merged: 2026-06-19
author: user walkthrough notes, filed by agent
category: frontend / ux / chat / conversation / product
related:
  - conversation-branching
  - cross-shape-context-threading
  - llm-interaction-correct-structure
  - search-and-agent-window-convergence
  - agent-window-presentation-and-evidence
  - agent-window-stacked-run-reliability
---

# 610 - Chat transcript needs conversation-control primitives

> **Status: MERGED to main (2026-06-19, merge commit `b279b2773`) — functionally complete.**
> All four phases (A edit/retry/per-turn menu · B inline sibling pager · C effective-context floor/rewind
> · D compaction) + the §C.2 inline-Edit alignment + the §D.2 mainstream-parity icon action bar are
> implemented and live-verified (§C/§C.1/§D.5). The one open thread is the **U4 product question** (does the
> product surface a long persistent multi-turn thread where compaction has real day-to-day value?) — a
> product decision, not implementation work; the floor/compaction mechanism is complete and correct
> regardless. The class-size pin drift carried in during merge (StatusLifecycleHandler/CoreOperationCatalog/
> GrpcIngestService — none touched by 610) was reconciled via the `610-pin-realign-preexisting-base-drift`
> merge-import changeset; the unrelated main-red `checkNoDirectJustsearchSysProp`/OCR gate is logged for the
> OCR owner in `docs/observations.md`.

> What this document is. A short problem statement from a user walkthrough. It records the idea for a
> future design pass only; it deliberately does not prescribe implementation.

## Problem

The chat surface needs more conversation-control features. Expected affordances include editing earlier
messages, resetting or rewinding context to a specific point, branching or retrying from a point in the
conversation, compacting long context, and copying answers cleanly.

This should not be treated as inventing a new branching substrate. The existing conversation-branching
work already establishes message identity, parent pointers, branch creation from a message, lazy inherited
history, and branch indicators. The remaining gap is the product control layer that makes these primitives
coherent and reachable from the chat transcript.

## Why it matters

An AI chat surface is not only an append-only message log. Users need to steer, correct, reuse, and
recover conversation state. Without these controls, a single bad prompt or stale context can force a
full restart, and long conversations become harder to trust or manage.

## Boundary

This tempdoc is about control over the transcript, conversation timeline, and effective context lineage.
It is separate from the `chat-composer-add-action-entrypoint` tempdoc, which is about how new capabilities
enter the composer.

The control layer should distinguish at least three classes of behavior:

- Static transcript controls: copy answer, retry, branch here, continue from here, or edit-and-branch.
- Effective context controls: reset what future turns see, compact long context, or continue with a
  summarized boundary while preserving the visible transcript.
- Live agent-run controls: steer, halt, supersede, or recover from an active run. These controls have
  different semantics from ordinary transcript branching because tool calls, approvals, budgets, and
  reversible side effects may be involved.

Editing an earlier message should likely be modeled as creating a branch from the point before that
message with the edited content as the first divergent turn, rather than mutating the old transcript in
place. That preserves the old conversation and avoids destabilizing existing branches that inherit parent
history.

Later design work should decide which primitives are first-class product concepts and which are UI
shortcuts over existing backend/session behavior. This document only records the product gap.

---

# Long-term design (2026-06-19, agent — autonomous investigation + design theorization)

> The problem statement above is preserved verbatim. This section is the design pass. Every claim is
> grounded against `main` with `file:line`. The method follows the 605 discipline: make the missing
> structure *structural* by **extending existing authorities**, add **exactly the one genuinely-new datum**
> the problem requires, and **reject speculative structure** for cases the product does not yet include.
> No code changed yet.

## 1. Thesis

The three control classes in the Boundary section are **three lineages**, and the codebase already owns two
of the three machineries:

| Lineage | "What it answers" | Owner today | 610's job |
|---|---|---|---|
| **Transcript** | which messages exist and how they fork | branching substrate (513/515/522, done) | thin product controls + one tiny correctness extension |
| **Effective-context** | what the *next turn's prompt* actually contains | **nothing — display and prompt are coupled** | the one genuinely-new structure |
| **Live agent-run direction** | how a human steers an *in-flight* run | `dispatchRunControl` steering seam (565/577/604/605) | **defer entirely; do not duplicate** |

The decisive structural observation: **`loadHistory` already *is* a lineage resolver.** Branching taught it
to walk parent pointers and take an *inclusive cut* of resolved history
(`FileConversationStore.java:56-89`, prefix `= parentHistory.subList(0, idx+1)` at `:79`). Every control
this tempdoc wants is **the same cut operation**, applied at a different scope:

- **Branch / edit / retry** = a cut taken *across* sessions (fork to a new session, navigate away). *Exists.*
- **Rewind / compact** = a cut taken *within* one session, where the cut governs the **prompt** while the
  **display** keeps the full thread.

So the only thing genuinely absent is the **decoupling of "history for display" from "history for prompt"**
inside a single session. That decoupling is the whole of the new structure — and it is small, because the
resolver and the storage shape it plugs into already exist.

## 2. What already exists (verified against `main`)

### 2.1 — Transcript lineage / branching (513 / 515 / 522, `done`)

- Per-message `id` (UUID v4) + `hash` (per-message sha-256 fingerprint, **not** a Merkle chain — 515
  FIX-6); legacy messages synthesize `id = "idx-<index>"` on read.
- `ConversationStore.branchFrom(parentSessionId, branchPointMessageId, newSessionId)`
  (`FileConversationStore.java:217`); parent pointers in `meta.json`, **inclusive** prefix, lazy walk with a
  cycle guard. REST `POST /api/chat/conversations/{id}/branch?fromMsgId=...` (400 bad point, 409 parent
  with branches).
- FE: `branchConversation(...)` (`conversationListStore.ts`); **"Branch here"** on each non-inherited
  assistant message (`UnifiedChatView.ts:3900`, gate `canBranch` `~:3876`); inherited prefix dimmed with `↪`.

### 2.2 — Per-message transcript controls today

| Affordance | State | Evidence |
|---|---|---|
| Copy answer | **exists** | `copyText()` `UnifiedChatView.ts:713`; hover button `~:3896` |
| Branch here (fork to new thread) | **exists** | `~:3900` → `branchHere()` `~:3990` |
| Retry / regenerate | **absent** | no message-level regenerate |
| Edit an earlier message | **absent** for chat | only agent-run "Fork & edit last question" (`~:3162`) — forks a *run*, not chat history |
| Continue from here | **run-level only** | live-run context gate (`~:2864`) |
| Reset / rewind effective context | **absent** | no in-session prompt cut |
| Compact / summarize context | **absent as a durable feature** | see 2.4 — the only "summarize" path is *lossy drop*, in-run, transient |
| Per-message hover toolbar | **assistant only** | user turns are a plain div, no toolbar (`~:3870`) |

### 2.3 — How a turn becomes a prompt (the seam the new structure plugs into)

The conversation (non-agent) path has a **single clean assembly seam**: `ConversationEngine` seeds context
from `conversationStore.loadHistory(sessionId)` (`ConversationEngine.java:243`), runs injectors (RAG, etc.),
and every LLM call goes through `buildLlmInput(systemPrompt, ctx.messages())` (`ConversationEngine.java:576`).
**Effective context == `loadHistory` output**, today. That is exactly the one chokepoint a per-session
context floor needs.

The agent path is *separate*: `AgentLoopService`/`AgentLlmCaller` pass the transient `AgentSession.messages()`
list straight to the LLM (no `buildLlmInput` equivalent), persisted via `AgentRunStore` checkpoints. This is
the deliberate `ConversationStore` ↔ `AgentRunStore` split (510 §G U6/U9; 513 §A.5). **610 operates on the
conversation lineage only** — see §6.

### 2.4 — There is no conversation summarizer (correction to the earlier draft)

The live-run "Summarize" context-gate decision does **not** summarize. `resolveContextGate(SUMMARIZE)` calls
`AgentSession.compactOlderTurns(keepRecent)` (`AgentStepRunner.java:304`), which **drops the oldest
non-system messages** — lossy truncation, no LLM-generated summary, scoped to the transient in-run message
list. `AgentContextCompressor` likewise only truncates/strips *tool results*. `AgentRunStore.resumeNote` is a
free-text human annotation ("stopped at context gate"), not a summary. **Net: "compact long context with a
summarized boundary" is a genuinely-new capability — nothing in the codebase produces a conversation
summary today.**

### 2.5 — Live agent-run controls (565 / 577 / 604 / 605) — mature, governed, owned elsewhere

steer (`ctrl.steer()` → interject), halt (`ctrl.cancelSession()`), recover (cross-tab `activeRunPointer` +
`reattachActiveRunOnLoad` + liveness watchdog), plus raiseBudget / resume / budget+context gates — all behind
the single `dispatchRunControl` seam (`controllers/runControlIntent.ts:84`), enforced by the
`steering-arbitration` gate. "Supersede" for agent runs is **already** modeled in 605 as initiate-as-supersede
through that same DIRECTION seam. 610 must not re-implement any of this.

## 3. The one new structure: an effective-context floor (display ≠ prompt)

### 3.1 — Why a floor, and why it must be its own datum (not branching)

The requirement "continue with a summarized boundary **while preserving the visible transcript**" cannot be
met by branching: a branch's `loadHistory` returns only the prefix, so the post-cut messages stop being
visible (they live in the parent). Preserving the visible transcript *requires* a representation where
**display history and prompt history diverge in the same session.** That divergence is the new structure.
It is structure the real problem requires — not speculative — and it is the *only* new structure required.

### 3.2 — Shape of the addition (minimal, mirrors how 513 extended the store)

Exactly like 513 added two optional `meta.json` fields for cross-session lineage, the in-session floor is
**one optional `meta.json` record**:

```
contextFloor : { floorMessageId, summaryText? }   // optional; absent ⇒ full history (today's behavior)
```

and **one new read path** on the resolver:

- `loadHistory(sessionId)` — unchanged; remains the **display** resolver (returns the full thread).
- `loadEffectiveContext(sessionId)` — new; the **prompt** resolver. With no floor it equals `loadHistory`
  (zero behavior change). With a floor it returns `[summaryText as a synthetic context message]? ++
  messages[floor..end]`.

`ConversationEngine` seeds from `loadEffectiveContext` instead of `loadHistory` (one line at
`ConversationEngine.java:243`); `buildLlmInput` and everything downstream are untouched. The FE display path
keeps reading the full thread. This is the entire substrate delta.

### 3.3 — The two controls fall out of the one floor

- **Rewind / reset context to point X** = set `contextFloor = { floorMessageId: X }`, no summary. The next
  turn's prompt starts at X; the transcript still shows everything above X, visibly marked out-of-context.
  *Reversible:* clearing the floor restores full context. **No summarizer needed — this is the cheap half.**
- **Compact at point X** = set `contextFloor = { floorMessageId: X, summaryText: <generated> }`. Same cut,
  plus a synthetic summary message standing in for the dropped prefix. **Requires the new summarizer (2.4).**

So rewind is strictly a subset of compact, and rewind can ship first as the proof of the floor mechanism
before the summarizer exists.

### 3.4 — The summarizer (compaction only)

A new capability: summarize `messages[0..floor)` into `summaryText` via one LLM call through the existing
`onlineAiService`, with the prompt template living in `prompt-support` (its natural home; reuse here is the
template, not the lossy `compactOlderTurns` drop). It is invoked once, at compaction time, and its output is
persisted in the floor — **not** re-run per turn. This is the single genuinely-new behavior the tempdoc adds
beyond wiring.

### 3.5 — Interactions, decided so they don't become hidden structure

- **Floor × branching.** A branch is built from the parent's **display** prefix (`loadHistory`), so a branch
  **does not inherit** the parent's prompt-floor. Rationale: the floor is a per-session prompt optimization,
  not forked content; inheriting it would silently shrink a new exploration's context. No new datum.
- **Floor × RAG.** RAG injectors prepend retrieved context *after* history seeding; the floor only trims the
  conversation turns, so RAG is unaffected. Verify the floor is applied before injector run (it is, if
  applied inside `loadEffectiveContext`).
- **Single floor, not a stack.** Re-compacting **moves the floor forward** and re-summarizes; the product
  problem is "keep this conversation usable," which one active boundary satisfies. A multi-floor stack /
  summary-of-summaries tree is structure for a case the problem does not include — **omit it** until a need
  appears (AHA / 605 discipline).

## 4. Transcript-lineage controls: thin shortcuts over `branchFrom` + one correctness fix

These need **no new lineage** — they are product wiring over the existing branch substrate, plus one small
correctness extension.

- **Edit-and-branch** (the model the problem statement itself proposes): branch from *before* the edited
  message, edited content becomes the first divergent turn. **Retry/regenerate** is the same with no edit:
  branch from the prompting user turn and regenerate.
- **Correctness extension — branch-from-*before* / empty prefix.** `branchFrom` requires a non-null
  branch-point that exists, and the prefix is **inclusive** (`:79`). Editing message *k* needs the prefix to
  end at *k−1*; editing the **first** message needs an **empty** prefix — not expressible today.
  **Correction (confidence pass §B/U2):** `null`/blank `branchPointMessageId` already means *full parent
  prefix* (`:81`), so the empty-prefix marker must be **distinct** — a reserved constant in the id field,
  or (cleaner) a separate `emptyPrefix` boolean in `meta.json`. Additive: one new branch in `loadHistory` +
  a validation skip in `branchFrom`. This is the only backend change the transcript controls need.
- **Retry = re-dispatch, not generate-from-history.** **Correction (confidence pass §B/U1):** the engine
  *cannot* generate from a prefix ending on a user turn — every dispatch runs injectors that abort on an
  empty prompt (`RAGContext.java:105`, `UserPromptInjector.java:47`). So retry is **branch from *before* the
  prompting user turn (excluding the old turn + its answer), then re-dispatch that turn's text unchanged.**
  The engine appends the same user message and generates a fresh answer — fully expressible today, **no
  engine change**; it is edit-and-branch with the text left unedited. Caveat: under greedy decoding
  (temperature 0) re-dispatch is identical, so retry's value assumes sampling temperature > 0.

### 4.1 — Inline sibling navigation (the biggest UX gap), with no new substrate

Today the only fork affordance **navigates away** to a new session, parent reachable only via the History
dropdown. Mainstream chat (ChatGPT, Claude.ai, LibreChat) exposes a *second* pattern for edit/retry: the UI
**stays in place** and a `‹ 2/3 ›` pager on the turn swaps between sibling versions, re-rendering the
transcript below. ChatGPT ships **both** — explicit "Create a branch" (navigate away, = what we have) *and*
inline version arrows (stay, = what we lack).

**Design call:** keep "Branch here → new thread" for deliberate exploration; add the inline sibling pager for
edit/retry ("a better version of *this* turn, in place"). The substrate already supports it — siblings are
exactly "all sessions with the same `(parentSessionId, branchPointMessageId)`", and `listSessions` already
exposes both fields per row (`FileConversationStore.java:195-204`). So the pager is a **read-side projection**
(FE filter, or a thin `GET .../siblings?fromMsgId=`), **no schema change.** Implementing edit/retry as the
cheaper navigate-away would ship a product worse than the mainstream and is the failure mode to avoid.

## 5. What 610 deliberately does **not** build (scope discipline)

- **No unified "lineage" abstraction collapsing branch + floor.** They share the *cut* mechanism but differ
  in intent and persistence (new-session pointer vs in-session marker; navigate vs stay). Per AHA, unify only
  what shares a reason to change — these don't. Keep `branchFrom` and `setContextFloor` as two operations
  that happen to be cuts; document the symmetry, don't force one primitive.
- **No `AgentRunStore` / live-run changes.** steer/halt/recover/supersede for agent runs are owned by the
  steering seam and 605. The conversation floor is the `ConversationStore` lineage only.
- **No conversation-as-Resource refactor** — still no-go (510 §G; carried from 513). The floor is a localized
  store extension, exactly as branching was — no Category/governance ceremony.
- **No "supersede" verb for conversation turns.** Halt + re-initiate and (for agent runs) the existing
  initiate-as-supersede already cover replacement. Adding one would be speculative structure.
- **No multi-floor stack, version tree, or Merkle/CAS** (513 already rejected the latter; the former is §3.5).

## 6. Scope boundary (one nuance worth stating)

The chat surface renders a *unified* thread that interleaves ordinary conversation turns with agent-run items
(merged at display). Branching and the floor operate on the **conversation message lineage**; agent-run items
carry their own run lifecycle. "Rewind across an agent run" (a floor straddling run items) is an edge case the
product problem does not raise — treat it as out of scope until it does, rather than designing for it now.

## 7. The first-class vs UI-shortcut determination (what the Boundary section defers)

| Affordance | Verdict | New structure? |
|---|---|---|
| Copy answer | UI shortcut (shipped) | none |
| Branch here | UI shortcut (shipped) | none |
| Continue from here | **subsumed** — *not a separate control* | none (it IS the branch-from-a-point gesture) |
| Edit-and-branch · Retry | UI shortcut over `branchFrom` | only the empty-prefix sentinel (§4) |
| Inline sibling pager | read-side projection | none (siblings already discoverable) |
| Rewind / reset context | **first-class** (effective-context lineage) | the floor record + `loadEffectiveContext` (§3) |
| Compact / summarized boundary | **first-class** | the floor **+** the new summarizer (§3.4) |
| Steer / halt / recover / supersede | shipped elsewhere | none in 610 |

So the honest scope of *new* structure in 610 is exactly: **one optional `meta.json` floor record, one store
resolver method, one branch-point sentinel, and one summarizer** — and nothing else.

## 8. Recommended sequencing (all in-scope; ordering, not dropping)

1. **Transcript shortcuts:** user-message toolbar (`~:3870`/`~:3893`), edit-and-branch + retry over
   `branchFrom`, the empty-prefix sentinel (§4), retry-as-generate check. Every fork/edit/retry path must
   `abortController?.abort()` before mutating `sessionId`/`thread` (522 FIX-T1).
2. **Inline sibling pager** (§4.1) — highest UX payoff per unit work; no backend schema change.
3. **Effective-context floor — rewind first** (§3.3 plain floor): `contextFloor` + `loadEffectiveContext` +
   the floor divider in the transcript. Proves the decouple with no summarizer.
4. **Compaction** (§3.4): the summarizer feeding `summaryText` into the same floor.

## 9. Open questions for the user

1. **Effective-context floor in 610 or its own tempdoc?** It is the only new substrate. The Boundary section
   lists it as in-scope, and the design keeps it minimal (one meta field + one resolver). Recommend keeping
   it here; rewind (step 3) and compaction (step 4) can land separately within 610.
2. **Confirm the inline sibling-pager** model for edit/retry over the cheaper navigate-away (§4.1).
3. **Empty-prefix sentinel** on `branchFrom` (§4) — acceptable, vs only ever passing the preceding message
   id (which can't express first-message edit)?
4. **"Supersede"** for conversation turns — agreed to omit (§5), given halt+re-initiate / the agent-run
   supersede already cover it?

## 10. Critical files

**Backend:** `modules/app-services/.../conversation/FileConversationStore.java` (branchFrom, loadHistory →
add `loadEffectiveContext`); `modules/app-agent-api/.../conversation/ConversationStore.java` (interface +
floor methods); `modules/app-services/.../conversation/ConversationEngine.java` (seed from effective context,
`:243`/`:576`); `modules/ui/.../api/ChatController.java` (branch/history endpoints + floor set/clear);
`modules/prompt-support` (summarizer template).
**Frontend:** `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` (toolbars, sibling pager, floor divider);
`modules/ui-web/src/shell-v0/state/conversationListStore.ts` (branch + sibling query + floor calls);
`modules/ui-web/src/shell-v0/components/chat/ConversationHistory.ts` (branch indicators).
**Governance (do not fork):** `governance/steering-surfaces.v1.json` + `check-steering-arbitration.mjs` —
any live-run control must route through `dispatchRunControl`; 610 adds none.

## Sources (competitive UX)

- [ChatGPT branching — "Create a Branch" (aivancity)](https://aivancity.ai/en/blog/chatgpt-se-ramifie-loutil-creer-une-branche-ouvre-la-voie-a-une-navigation-plus-intelligente/)
- [ChatGPT removed message version arrows (OpenAI community)](https://community.openai.com/t/chatgpt-web-update-removed-message-version-arrows-cannot-access-edited-message-history/1374666)
- [LibreChat — remember branch/sibling index on load (#3278)](https://github.com/danny-avila/LibreChat/issues/3278)
- [Conversation branching spec — fork/merge/tree navigation (claude-code #32631)](https://github.com/anthropics/claude-code/issues/32631)

---

# Frontend / user-facing design (2026-06-19, agent — live UI inspection)

> Nearly all of this tempdoc is user-facing, so this is not optional polish — it is the design. This
> section is written **after inspecting the running chat surface** (dev stack + browser), not from the
> tempdoc alone. It records what the real UX looks like today, the consequences that only became visible
> on screen, and the resulting frontend design. It stays at the design level (placement, states, IA,
> legibility), not CSS/component detail.

## 11. What the chat surface actually looks like today (observed live)

The chat surface (`core.unified-chat-surface`) is a **single-input, tier-tabbed** surface. One composer;
a tab row — **Search · Documents · Structured · Agent · History** — re-labels the input and the send
button and changes behavior (Search = base retrieval, Documents = grounded "Ask", Agent = delegated run).
Transcript turns with controls appear in the Ask/Agent tiers. Live findings that change the design:

- **O1 — The transcript viewport is small and banner-dominated.** A degradation banner ("⚠ Reindex
  required… Force Rebuild") plus the tier-tab row consume the top ~330px of a 714px window, leaving the
  thread itself a cramped band. **Vertical space is the scarce resource.** Any new per-message chrome,
  pager, or context divider competes with the messages for it — so the control set must be
  progressive-disclosure (hover + overflow), and dividers must be single compact rows, not panels.
- **O2 — Per-message controls exist on assistant turns only, as low-prominence text-links.** On hover the
  assistant message's header (shape-tag) row reveals `COPY` and `BRANCH HERE` — small uppercase links,
  inline beside the shape label (e.g. `DOCUMENT Q&A   COPY   BRANCH HERE`). **User turns are a plain
  right-aligned bubble with only a relative timestamp ("2m ago") — no controls at all.** So edit + retry
  have *no surface to hang on today*; the user-turn affordance is genuinely new UI.
- **O3 — "Branch here" navigates away.** Clicking it **replaces the whole view** with the new branch:
  the inherited prefix + a `↪ Branched from "<parent's first question>"` banner (italic, left accent
  rail). The parent's *divergent continuation* vanishes from view — only History reaches it. There is no
  inline way to compare or swap between the original and the branch. This is the navigate-away cost the
  design flagged, now confirmed on screen.
- **O4 — The inherited-prefix de-emphasis is barely perceptible.** The 0.7-opacity dimming of inherited
  messages is so subtle it reads as full-opacity in practice. **Lesson for the floor design:** an
  "out-of-context" treatment cannot rely on a faint opacity drop — above-floor messages need a clearly
  legible muted/banded treatment, and it must be *visually distinct* from the `↪` inherited-prefix
  language (they mean different things: "this came from a parent" vs "the assistant no longer sees this").
- **O5 — History is a flat list; siblings are scattered.** The History dropdown lists conversations
  flatly, branches marked only by a teal `↪`. A just-created branch shows "**0 messages**" (it has
  inherited context but no own turns) — confusing. There is **no tree/version grouping**: siblings of the
  same turn are undiscoverable as versions. This is exactly the gap the inline sibling pager closes.
- **O6 — A "Continue your last conversation? '<q>'" resume card** (Continue / Start fresh) already exists
  as a context-continuity pattern — useful precedent for how the surface offers stateful choices inline.
- **O7 — Rich per-message frame already exists.** Assistant turns carry a frame line ("Model answer — this
  mode does not search your documents" / "DOCUMENT Q&A"), inline citation superscripts, and
  grounded-sentence underlines. There is already an established header/frame zone to extend — new
  controls and the floor divider should *join* this vocabulary, not invent a parallel one.

## 12. User-visible consequences the design must now address

1. **A user-turn control surface must be invented** (O2). Edit and retry act on the *user* turn, which has
   no affordance today. This is the single largest net-new piece of UI.
2. **The control set is outgrowing inline text-links** (O1+O2). Copy/Branch are already two links; adding
   retry, edit, "reset context here", "compact here", and "branch to new thread" inline would crowd the
   cramped header. The IA must split **inline (highest-frequency) vs overflow (the rest)**.
3. **"Branch" is currently overloaded** (O3+O5). It is the *only* fork verb, so it carries both "give me a
   better version of this turn" and "let me explore a separate thread." Edit/retry-as-in-place-siblings
   lets "Branch here → new thread" return to being the *deliberate exploration* verb, and the sibling
   pager (O5) makes versions legible where History cannot.
4. **The floor needs a first-class, legible transcript element** (O4+O7) that is distinct from the `↪`
   banner and readable at a glance — the faint-opacity approach is proven insufficient.
5. **Trust: the user must see what the assistant sees** (O7). When context is reset or compacted, the
   transcript still shows everything (per the requirement), so the surface must make unmistakably clear
   *which* messages are still in the prompt and, for compaction, *what the summary says* — otherwise the
   user silently loses the mental model of the conversation's effective state (a trust regression,
   adjacent to 603 RAG-trust and 612 "surface what the system is doing").
6. **Streaming/active turns must suppress these controls** — you cannot edit/branch/retry a half-streamed
   message; the affordances appear only on settled turns (matches today's post-completion hover reveal).

## 13. The frontend design

### 13.1 — One per-turn action model, progressive-disclosure (resolves O1/O2 density)

Give **every settled turn (user and assistant)** a hover-revealed action affordance with a deliberate
inline-vs-overflow split, so the surface scales without crowding the cramped header:

- **Assistant turn:** keep `Copy` inline (highest-frequency, already there). Move the rest behind a single
  `⋯ More` per-turn menu: **Retry**, **Branch to new thread**, **Reset context to here**, **Compact up to
  here**.
- **User turn (new surface):** a matching hover affordance with **Edit** inline (its defining action) and
  `⋯ More` holding **Retry from here**, **Branch to new thread**, **Reset context to here**.

Keep the visual language of O7 (the existing shape-tag/header zone). This is an information-architecture
choice, not new substrate — it is the transcript-side answer to the same "don't crowd the primary input"
problem 611 solves for the composer, and the two must read as *different zones* (past-turn controls hang
off messages; next-turn material hangs off the composer) so the user is never unsure what a control's
scope is.

### 13.2 — Edit is in-place-then-branch, not navigate-away (the model the Problem section proposed)

`Edit` turns the user bubble into an inline editable field with **Save / Cancel**. Save creates the
branch-from-before (§4) and **the conversation continues in place** — the view does *not* jump to a
separate session as "Branch here" does today (O3). The edited turn gains a sibling pager (13.3). Cancel
restores the bubble untouched. Retry is the same gesture with no edit (regenerate the answer to the
existing turn).

### 13.3 — The sibling pager makes versions legible where History cannot (resolves O5)

On any turn that has siblings (produced by edit or retry), render a compact **`‹ 2/3 ›` pager** on that
turn. Stepping it **swaps the visible continuation in place** (re-renders the turns below to the selected
sibling) — no navigation, no losing your place. This is the mainstream pattern (ChatGPT/Claude.ai version
arrows) and the antidote to O5's flat, scattered, "0 messages" history rows. It is a **read-side
projection** over siblings already discoverable via `listSessions` (§4.1) — no new substrate.

`Branch to new thread` (today's navigate-away "Branch here", O3) **stays** as the explicit
*exploration* verb for a deliberately separate conversation — a different intent from "a better version
of this turn," and the right tool when the user wants the fork to live on its own in History.

### 13.4 — The context floor is a distinct, legible, reversible transcript divider (resolves O4)

When a floor is set, render a **full-width horizontal divider** at the floor point — a single compact row
(O1), visually distinct from the `↪` inherited-prefix banner (O4). Two variants:

- **Reset:** `↺  Context reset — the assistant no longer sees messages above this line   [Restore]`.
- **Compacted:** `❏  Context compacted — N earlier messages summarized   [Show summary ▸]   [Restore]`.

Messages **above** the floor stay visible but get a clearly-legible **out-of-context band** (a muted
treatment with a left rail and an "out of context" affordance) — *not* the imperceptible 0.7-opacity dim
(O4). The divider is always present while a floor exists and always carries **Restore** (clear the floor →
full context returns), so the state is visible and reversible at a glance.

### 13.5 — Compaction is inspectable (trust; resolves consequence 5)

The compacted divider's **Show summary** expands the generated `summaryText` inline, so the user can read
exactly what now stands in for the dropped turns. The user can therefore always answer "what does the
assistant actually see right now?" — the full transcript for display, minus the out-of-context band, plus
the (readable) summary. Without this, compaction silently rewrites the conversation's effective state — a
trust regression this surface must not ship (adjacent to 603 / 612).

### 13.6 — States and edges (so the surface never lies about effective state)

- Controls appear only on **settled** turns; suppressed during streaming/active runs (consequence 6).
- A floor divider sitting **inside** the out-of-context band (floor moved forward past an old reset) keeps
  only the most-recent floor authoritative — one active floor (§3.5), one divider.
- Editing a turn that is **above** the floor is allowed but should warn that it forks history the assistant
  currently doesn't see; keep it simple — do not invent multi-floor semantics for this edge (§6 / §3.5).
- The floor and the `↪` branch banner can co-exist on one thread; their visual languages must remain
  separable (O4) so "from a parent" and "out of the assistant's context" never blur.

### 13.7 — Explicitly out of frontend scope here

- **History tree/version grouping.** O5's flat list would be improved by grouping siblings under one
  conversation, but the inline pager (13.3) already closes the *in-conversation* legibility gap. A
  History-view redesign is a separate surface and a separate tempdoc — note it, don't absorb it.
- **Live agent-run control chrome** (steer/halt/recover) — owned by the steering seam + 605 (§5).
- **The degradation banner's footprint** (O1) — its prominence is 600/602's concern, not 610's; the design
  must merely *survive* the cramped viewport, not fix the banner.

## 14. Updated open questions (supersedes §9 where they overlap)

1. **Per-turn overflow vs all-inline:** confirm the `⋯ More` overflow split (13.1) over piling every verb
   into the header — recommended given the observed density (O1).
2. **Effective-context floor in 610 or its own tempdoc** (carried from §9.1): the floor's UI (13.4/13.5) is
   the bulk of the *new* user-facing surface; still recommend keeping it here, shipping reset before
   compaction.
3. **Inline sibling pager** (13.3) confirmed as the edit/retry model over navigate-away (carried from §9.2;
   live evidence O3/O5 strengthens it).
4. **History sibling-grouping** (13.7): out of scope for 610, or fold in? Recommend separate.

---

# §B — Verified facts (confidence pass, 2026-06-19, agent)

> Before implementation, six load-bearing assumptions in the design above were probed against `main` by
> read-only source tracing (three parallel audits, every claim file:line). Static evidence was decisive for
> all six, so no live dev-stack probe was needed. Results below; the two flat corrections are already folded
> into §4. One finding (U4) materially narrows the floor's scope and is the main residual risk.

### U1 — Retry — RESOLVED, risk lowered ✅
**Probed:** can the engine regenerate an assistant turn from a prefix ending on a *user* turn with no new
input? **Verdict: no — and it doesn't need to.** Every substrate dispatch runs injectors that abort on an
empty prompt (`RAGContext.inject` terminal `NO_QUESTION` at `RAGContext.java:105-106`;
`UserPromptInjector` terminal `MISSING_PROMPT` at `UserPromptInjector.java:47-53`); `appendMessages` is
package-private (`EngineConversationContext.java:87`). So "generate-from-history" is unsupported — **but
retry = branch-from-before-the-turn + re-dispatch the same text**, which is the ordinary dispatch path and
needs **no engine change**. The earlier "retry-as-generate" framing (§4) was wrong and is corrected.
*Impact:* the highest-risk item is retired; retry and edit are the *same* flow (branch-from-before + dispatch,
text unedited vs edited).

### U2 — Empty-prefix marker — RESOLVED, design corrected ✅
**Probed:** does `null` express an empty-prefix branch? **Verdict: no.** Blank/missing `branchPointMessageId`
already resolves to the **full** parent prefix (`FileConversationStore.java:81`), and `branchFrom` rejects
`null` outright (`requireNonNull`, `:220`). Empty-prefix therefore needs a **distinct** marker — a reserved
constant in the id field, or (cleaner) a separate `emptyPrefix` boolean in `meta.json` so the id field's
type meaning isn't overloaded. Additive: one new branch in `loadHistory`, one validation skip in
`branchFrom`. §4 corrected.

### U3 — Sibling-set model — RESOLVED ✅
**Probed:** is the data sufficient to group "versions" of a turn? **Verdict: yes.** `listSessions` emits both
`parentSessionId` + `branchPointMessageId` per row (`FileConversationStore.java:194-204`); the FE surfaces
them (`conversationListStore.ts:121-126`, interface `:19-20`); `branchFrom` stores the branch-point id
**verbatim** (`:253-254`), so N branches from the same message M share the key `(P, M)`. The sibling set =
`{parent P as version 1} ∪ {branches with parentSessionId=P ∧ branchPointMessageId=M}` is computable with no
new storage. *Keying note for §13.3:* edit/retry deliberately key on the message **before** the turn
(`k−1`), so all edits/retries of turn *k* group; today's "Branch here → new thread" keys on the message
itself (a different key) and **intentionally does not join** the in-place pager — correct, since it is the
*explore* intent, not a *version* of the turn.

### U4 — Effective-context seam & scope — RESOLVED, scope narrowed ⚠️ (main residual risk)
**Probed:** is `loadHistory→buildLlmInput` the single prompt-assembly chokepoint, and where does RAG
injection sit? **Verdict: confirmed, with a material discovery.** Both Ask and Free-Chat are
`SUBSTRATE_DRIVEN` → `dispatchSubstrateDriven` (`ConversationEngine.java:173`); `loadHistory` is the sole
history source (`:243`); injectors run **after** seeding and the LLM order is `[system, …history…,
…injected…]` (`:267-296`, `buildLlmInput` `:576`), so a floor that trims history composes cleanly with RAG;
the agent path is separate (`AgentLlmCaller.java:185`). **Discovery:** history is seeded **only for PERSISTENT
shapes** with a `sessionId`; **RAG-Ask is EPHEMERAL and passes `sessionId=null`** (`:238-244`) — it records
turns to the thread for display/branching (`threadUserMessage`, `:282`) but **does not feed prior turns into
the prompt.** *Impact:* the effective-context floor (rewind/compact) is only meaningful where multi-turn
prompt-context actually accrues — i.e. **persistent free-chat**, not the per-turn-stateless Ask tier. This
**narrows the floor's applicability** and raises a product question (below). `loadEffectiveContext` still
slots in at `:243`, affecting PERSISTENT shapes only.

### U5 — Summarizer precedent — RESOLVED, risk lowered ✅
**Probed:** is a one-shot summary call reachable, with a precedent? **Verdict: yes, strongly.**
`OnlineAiService` already exposes one-shot non-streaming `summarize(String)` / `summarize(String,int)` and
`chatCompletion(...)` (`OnlineAiService.java:293-302`, impl `OnlineAiServiceImpl.java:305-314`), and there is
a `SummarizeShape` + `SummarizationStyle` `PromptContributor` precedent (`SummarizeShape.java`,
`SummarizationStyle.java:28-34`). Title-gen (`conversationListStore.ts:164-212`) is a second precedent for an
LLM call from the conversation layer. *Impact:* compaction's summarizer is **low mechanism-risk** — §3.4 can
reuse `summarize()` + the `SummarizationStyle` template pattern rather than build new.

### U6 — Floor wire round-trip — RESOLVED ✅
**Probed:** can `contextFloor` ride the history response to the FE? **Verdict: yes, additive.**
`handleLoadHistory` returns an open `LinkedHashMap` and already null-check-then-puts the branch fields
(`ChatController.java:171-189`, incl. `parentFirstUserMessage` FIX-8); the FE destructures in
`resumeConversation` (`conversationListStore.ts:253-258`, `ResumedConversation` `:231-244`) and consumes in
`UnifiedChatView.resumeSessionHistory` (`:751-767`) — exactly where the divider field would be read. Needs a
`SessionSummary.contextFloor()` accessor mirroring `parentSessionId()`. Round-trip is additive.

## §B.1 — What shifted, and the residual risk

- **Retry de-risked** (U1): no engine change; retry ≡ edit-and-branch with unedited text.
- **Empty-prefix corrected** (U2): distinct marker, not `null`.
- **Compaction summarizer de-risked** (U5): `summarize()` + `SummarizeShape` already exist.
- **Floor scope narrowed** (U4) — *the one finding that changes the product calculus.* Rewind/compact act on
  multi-turn prompt-context, which accrues only in **persistent free-chat**, not the EPHEMERAL Ask tier. So
  the open question for the floor is now sharper: **where in the product does a long multi-turn thread that
  needs compaction actually exist?** If the dominant chat tier (Ask) is per-turn-stateless, compaction's
  *value* (not its mechanism) is the thing to confirm with the user before building it — the transcript
  controls (edit/retry/branch/sibling-pager) and **rewind** stand on their own regardless. This should be
  verified live during implementation (send two persistent-free-chat turns; confirm turn 2's prompt includes
  turn 1) — the code is unambiguous, but the product exposure of persistent free-chat in the current UI was
  not exercised in this pass.

## §B.2 — Confidence rating for remaining implementation work

**7.5 / 10.**

- **Transcript-lineage controls (edit / retry / branch / sibling pager): ~8.5.** All expressible with
  `branchFrom` + the empty-prefix marker + the existing dispatch path; sibling data is sufficient; wire is
  additive. No engine changes. Residual is FE execution (user-turn toolbar, pager rendering, abort-on-switch
  per 522 FIX-T1) in a cramped viewport — effort, not unknowns.
- **Effective-context floor — rewind: ~7.5.** Clean seam (`loadEffectiveContext` at `:243`), additive wire,
  reversible. Residual is the FE out-of-context band/divider legibility (O4) and confirming PERSISTENT-shape
  reach.
- **Compaction: ~6.** Mechanism is low-risk (U5), but its *value* depends on the U4 product question — if no
  long multi-turn thread exists outside agent runs, compaction is solving a problem the product may not have
  yet. Worth a user decision before building.

The number sits below 9 mainly because of the U4 product-scope question (does compaction have a real target?)
and the inherent UX-execution risk of the sibling pager + floor divider in a dense, banner-dominated viewport
— both judgment/verify items, not unresolved mechanisms. The mechanism uncertainties that opened this pass
are now closed.

---

# §C — As-built (2026-06-19, agent — implemented in worktree `tempdoc-610`)

All four phases implemented per the approved plan, reusing existing primitives (no new atoms).
Backend `./gradlew.bat build -x test` green; `:modules:app-services:test` + `:app-agent-api:test` +
`:ui:test` green (FileConversationStoreTest 20, SubstrateDrivenEngineTest 18). FE typecheck clean;
full unit suite 3252 pass (334 files). FE gates pass: controls-a11y, theme-token-closure,
presentation-purity, color-tokens, style-literal-ratchet, message-single-model,
transient/modal-arbitration, atom-fork-ratchet.

## What shipped, by phase

- **A — edit / retry / per-turn menu.** `ConversationStore.EMPTY_PREFIX_SENTINEL` (`"__empty_prefix__"`)
  handled in `FileConversationStore.loadHistory` (special-cased before the id-search) + `branchFrom`
  (validation skipped for the sentinel). FE: user-turn hover toolbar + a per-turn `⋯` menu reusing
  `openContextMenu` (rides `TransientController`); edit-in-place native `<textarea>`; `commitEdit`/
  `retryFrom`/`branchAndResend` = branch-from-before-the-user-turn (sentinel for the first turn) then
  re-dispatch through the normal `send()`. **Retry is re-dispatch, not generate-from-history (U1).**
- **B — inline sibling pager.** Pure `siblingSessionsAt(conversations, baseId, branchKey)` selector in
  `conversationListStore` (base = version 1, branches by createdAt) over the already-loaded list — **no
  new endpoint.** `pagerForTurn` places `‹ n/m ›` on the divergent turn (branch-side + base-side);
  stepping calls `loadConversation`.
- **C — effective-context floor (rewind).** `ConversationStore.setContextFloor` + `loadEffectiveContext`
  + a `contextFloor` `meta.json` field + `SessionSummary.contextFloor`; `ConversationEngine` seeds
  PERSISTENT shapes from `loadEffectiveContext` (`:243`); `ChatController` POST/DELETE `…/context-floor`
  + `contextFloor` on the history response. FE: "Reset context to here" → floor divider + **out-of-context
  band** (distinct opacity+grayscale, not the `↪` language) + Restore.
- **D — compaction.** `ConversationStore.compactContext` + a `contextFloorSummary` meta field +
  `SessionSummary.contextFloorSummary`; `loadEffectiveContext` prepends the summary as a synthetic
  `system` message; `ChatController.handleCompact` summarizes the above-floor prefix and stores it.
  FE: "Compact up to here" → compacted divider variant with **Show summary** (the §13.5 trust feature).

## OnlineAiService ↔ ChatController wiring (the flagged §B residual)

Resolved without a user decision: `ChatController` gained a `Supplier<OnlineAiService> onlineAi`
constructor param, wired from the **existing** `onlineAiSupplier` already present in
`ConversationApiAssembly` (the compaction summarizer reuses `OnlineAiService.summarize(...)` — no new
inference plumbing). The 4-arg/3-arg constructors default to `OnlineAiService::unavailable`, so existing
callers/tests compile unchanged; `handleCompact` returns 503 when the runtime is unavailable.

## Decisions / notes

- The compaction summary is injected as a `system` message (`"Summary of earlier conversation:\n…"`),
  consistent with being injected background context. (RAGContext injects a `user` message because it
  bundles the question; a standalone summary is background → `system`.) Confirm template handling at the
  live tier.
- Pre-existing class-size drift at the base commit (`CoreOperationCatalog` 1016→1022,
  `StatusLifecycleHandler` 1163→1208 — neither touched by 610) was realigned via a `merge-import`
  pin changeset so the worktree build is green; logged to `docs/observations.md` (main's gate is red
  pending the same bump under manual-CI).

## §C.1 — Live verification (browser) — pending in this turn's final batch


Live browser verification (dev stack from this worktree's dist, api 63419, ui 5173, Qwen Qwen3.5-9B on
GPU via cuda12; worktree pointed at main's `models` + `modules/ui/native-bin` via directory junctions,
settings.json copied for the chat-model path). Verified with screenshots in the loop transcript:

- ✅ **Per-turn ⋯ menu** — assistant turn shows `DOCUMENT Q&A · Copy · ⋯`; the ⋯ opens the reused
  `ContextMenu` with **Retry from here · Branch to new thread · Reset context to here · Compact up to
  here**. User turn ⋯ shows **Edit · Retry · Branch · Reset** (Edit user-only; Compact omitted on turn 0
  with nothing above). Phase A toolbar + the C/D actions confirmed.
- ✅ **Edit-in-place** — the user turn becomes a `<textarea>` with Save/Cancel.
- ✅ **Edit-and-branch (first message → empty-prefix)** — editing turn 0's text and Saving re-dispatched
  the edited question and retrieved fresh sources (the empty-prefix branch path).
- ✅ **Retry** — produced a *different* answer (sibling) than the original.
- ✅ **Inline sibling pager** — `‹ 2/2 ›` rendered on the (record-path) user turn; stepping to `‹ 1/2 ›`
  **swapped the answer in place** (version 2 "JustSearch does not have three distinct processes…" ⇄
  version 1 "Partly grounded … The provided documents do not list…"), no navigation.

**Record-path defect found AND fixed during live verification (root-cause, not symptom):** the per-turn
controls + pager initially did NOT appear on *persisted/branched* turns because the canonical timeline
(`renderUnifiedItem`) renders user turns as a bare div, while the controls lived only in `renderMessage`
(the live-overlay path). Fixed by delegating record user turns with a matching live-thread message to
`renderMessage` (mirroring the existing assistant-delegation at the same site). Re-verified live: pager +
Edit + ⋯ menu now render on reloaded turns. Minor accepted tradeoff: the ambient per-turn timestamp is
not shown on delegated user turns (noted; cosmetic).

**Not captured on screen (environmental, not a code defect):** the **context-floor reset divider** and the
**compaction summary divider** visuals, and the **U4 planted-fact prompt-exclusion**. Their menu items are
screenshot-confirmed and their renders are unit-tested (FE `context-floor divider` + `compacted-variant
Show summary` tests; backend floor round-trip + compaction-summary-prepend tests). After extended use the
browser renderer became unresponsive (repeated CDP `Page.captureScreenshot` / `Runtime.evaluate`
timeouts; fresh loads rendered blank with no console errors). Recovered via the tab-drain procedure twice;
the degradation persisted at the Vite/renderer layer (no Chrome-restart tool exists). The FE mounted and
worked through all the checks above before degrading, and the full unit suite (3252) is green.

**U4 live note:** the verification ran in the Documents (`core.rag-ask`) tier, which is EPHEMERAL — it does
not seed prior turns into the prompt — so the floor's prompt-EXCLUSION is a no-op there by construction
(the divider/band/Restore/summary UI still render). Demonstrating prompt-exclusion requires a PERSISTENT
free-chat thread; confirming the product surfaces a long multi-turn persistent thread (where compaction has
real value) remains the open U4 product question.

**§C.1 UPDATE — live verification COMPLETED (2026-06-19, after dev-stack takeover + restart):** the
two divider visuals and the U4 exclusion that earlier could not be captured (browser-renderer degradation)
were captured after a clean stack restart (api 53280, Qwen on GPU). A second dual-path defect was found
and fixed during this pass: the floor divider + out-of-context band only rendered on the live-overlay path
(`renderMessage`), so on persisted **assistant** turns they were missing — fixed by `recordFloorParts(it.id)`
in `renderUnifiedItem` (prepends the divider + adds the out-of-context class on the record path; same
root-cause family as the pager fix). Re-verified live (screenshots + DOM in the loop transcript):

- ✅ **Context-floor reset divider** — `↺ Context reset — the assistant no longer sees messages above this
  line` with **Restore**; the turn(s) above the floor render dimmed (out-of-context band). Confirmed on the
  record path (assistant + user turns).
- ✅ **Compaction summary divider** — `❏ Context compacted — earlier messages summarized for the assistant`
  with **Show summary** + **Restore**; the summarizer ran end-to-end (FE → POST `/compact` → `ChatController`
  → `OnlineAiService.summarize()` → store → `loadEffectiveContext` prepends), producing a real summary
  ("JustSearch is a single application that may run multiple background tasks, but documentation only
  mentions one `java.exe` process in Task Manager."), and **Show summary** expands it inline (the §13.5
  trust feature). Confirms the **OnlineAiService↔ChatController wiring** works live.
- ✅ **U4 planted-fact prompt-exclusion (free-chat, PERSISTENT)** — planted "my favorite fruit is durian"
  → without a floor the model recalled it ("based on our earlier conversation"); with the floor set above
  the plant, the re-ask answered **"unknown"** (no durian). The floor demonstrably trims the prompt while
  the transcript still displays the dimmed plant.

**U4 live finding (multi-turn value):** the exclusion is observable only in a **PERSISTENT free-chat** thread
(reached here by setting the affordance to `none`); the dominant **Documents/`core.rag-ask` tier is
EPHEMERAL** (passes `sessionId=null`, seeds no prior turns), so the floor/compaction have no prompt effect
there by construction — only the display UI renders. So compaction's *value* tracks where the product
surfaces long persistent multi-turn threads (free-chat / future agent-conversation), not the per-turn Ask
tier. Recommend confirming the product's persistent-multi-turn surface before investing further in
compaction depth (it is mechanism-complete and correct regardless).

**Live verification status: COMPLETE.** All five phase behaviors (edit-and-branch incl. first-message,
retry sibling, ‹n/m› pager swap-in-place, context-floor divider + planted-fact exclusion + Restore,
compaction summary divider) demonstrated with screenshots + observations.

---

# §C.2 — Design-alignment close-out (2026-06-19, agent)

Two gaps from a conceptual re-read against the design were closed:

- **Edit is now INLINE on user turns (§13.1).** The first build buried Edit in the `⋯` overflow; it now
  renders as an inline hover affordance on the user turn (`.turn-edit-btn`, gated by `canTurnControl`,
  calling `startEdit`), and the `edit` action was removed from the `⋯` overflow so it isn't offered twice.
  The overflow keeps Retry / Branch to new thread / Reset context / Compact. Assistant turns are unchanged
  (Copy inline + `⋯`), preserving the deliberate inline-vs-overflow split. (`openTurnMenu` lost its now-unused
  `role` param.) Unit-tested (inline Edit renders on a settled user turn; clicking it opens the textarea)
  and live-verified.

- **"Continue from here" is resolved as SUBSUMED — not a separate control** (§7 table). Under the
  branch-don't-mutate model, "continue from an earlier point" has no distinct, non-redundant mechanism:
  it IS the branch-from-a-point gesture, already delivered by **Branch to new thread** + **Retry** + the
  **inline sibling pager** (which keeps the parent reachable as version 1, so you never lose your place).
  Adding a near-duplicate verb beside "Branch to new thread" would violate the §5 anti-over-build
  discipline and confuse the action vocabulary. No new control; this is a deliberate design decision, not
  an omission.

---

# §D — Presentation parity with ChatGPT/Claude + long-term design (2026-06-19, agent)

> **Decision recorded:** for the **transcript-lineage controls** (copy, edit, retry, branch, version
> pager), looking and behaving like ChatGPT / Claude.ai **is an explicit goal** — these are commodity
> chat affordances users already know, so familiarity beats novelty. (The **effective-context** controls
> — context-floor reset, compaction — are the opposite: they have no mainstream counterpart and stay
> distinctive *by design*; see §D.3.) This section records where the shipped build diverges from that
> goal and theorizes the correct long-term presentation.

## §D.1 — Finding: behavior matches the mainstream, presentation does not

A critical comparison against ChatGPT/Claude found the transcript controls are **behaviorally aligned**
(edit→branch→`‹ n/m ›` version arrows; retry→sibling; branch-to-new-thread in an overflow) but
**presentationally divergent**:

- **Text-links + a `⋯`, not an icon action row.** ChatGPT/Claude render a consistent row of **icons**
  (pencil · copy · ↻ regenerate) **below** the message. The build uses text labels ("COPY", "Edit") in the
  assistant's **header tag-row** and at the **top-right corner of the user bubble** — a utilitarian, less
  iconographic, less conventional placement.
- **Retry is hidden in the `⋯` overflow.** Mainstream surfaces regenerate as a prominent, always-near
  icon; burying it is less discoverable than either product.
- **Edit stretches to a full-width textarea** instead of morphing the bubble in place (ChatGPT edits
  inside the bubble with a primary "Send").
- **Version pager anchors to the user turn always**; ChatGPT shows the arrows on the **assistant** message
  for regenerations (the turn whose content actually varies).

So the gap is **presentational, not behavioral** — and it is now in-scope remaining work, not an
acceptable deviation.

## §D.2 — Long-term design: ONE per-turn "action bar" authority (icon-based, below the message)

The structural fix is the same shape as the rest of 610: collapse the scattered, ad-hoc controls (header
text-links on assistant, corner buttons on user, retry-in-overflow) into **one per-turn action-bar
presentation authority** that every turn renderer composes — matching both the mainstream affordance
grammar *and* the codebase's own single-presentation-authority discipline (kernel doc 27). Concretely:

- **One hover-revealed action row rendered *beneath* each settled turn** (a small `jf-button size="icon"`
  row), replacing the header/corner placement. Reuse the existing atoms — the icons already exist
  (`copy`/`clipboard`, `refresh-cw`, `pencil`, `git-branch`, `chevron-left`/`chevron-right`, `history`)
  and `jf-button size="icon"` gives keyboard-operable, accessible-named icon buttons for free (controls
  a11y gate satisfied). The only additive piece is a `more-horizontal` icon for the overflow trigger (the
  `⋯` glyph is a fine interim).
- **Primary verbs become visible icons; only secondary verbs stay in `⋯`.** This mirrors ChatGPT/Claude
  exactly:
  - *Assistant turn:* `copy` · `↻ retry` (visible icons) · `⋯` → Branch to new thread · Reset context ·
    Compact.
  - *User turn:* `✎ edit` (visible icon) · `⋯` → Retry · Branch · Reset context. (Edit stays the user
    turn's defining inline action — §13.1 — now as an *icon* in the bar, not a corner text-link.)
- **Edit morphs the bubble in place** (an edit *mode* of the same bubble: same alignment/width, primary
  "Send"/secondary "Cancel"), not a full-width stretch — so editing reads as "this turn, now editable,"
  the mainstream model.
- **The version pager attaches to the turn whose content varies:** the **assistant** message for retries,
  the **user** message for edits (the sibling-set math already supports both; only placement changes).
- **One zone, one grammar:** the action bar is the single home for per-turn controls; "past-turn controls
  hang below the message" / "next-turn material hangs off the composer (611)" stays the clean separation
  (§13.1). This dissolves the "where do controls live / why are they inconsistent" problem structurally
  rather than per-turn.

This is **presentational re-housing of existing, working handlers** (`copyText`, `startEdit`,
`retryFrom`, `branchHere`, `resetContextTo`, `compactTo`) into one icon action-bar zone + an in-bubble
edit mode — no new substrate, no behavior change, and it must render on **both** the live-overlay and
record paths (the §C.1 delegation already routes user turns through `renderMessage`; the action bar lives
there).

## §D.3 — The effective-context controls stay distinctive (borrow the grammar, not the look)

Context-floor **reset** and **compaction** have no ChatGPT/Claude precedent — those products manage
context silently and expose nothing. The long-term call: these controls **borrow the invocation grammar**
(reached from the same per-turn `⋯` overflow, so the interaction model is uniform) but keep their
**in-transcript rendering distinctive** (the `↺`/`❏` divider, the out-of-context band, the inspectable
"Show summary"), because there is no mainstream pattern to converge toward and their transparency is the
point (§13.4/§13.5). Familiar grammar for invocation; a purpose-built, legible language for the artifact.

## §D.4 — Remaining work + out of scope

- **Remaining work (presentational, §D.2):** introduce the one per-turn icon action-bar authority;
  surface copy/retry/edit as visible icons with branch/reset/compact behind `⋯`; move the action row
  beneath the message; in-bubble edit morph; pager on the versioned turn; add a `more-horizontal` icon.
  Behavior and substrate are unchanged — this is a re-housing of shipped handlers.
- **Out of scope (framing, owned elsewhere):** the surrounding shell — tier tabs
  (Search/Documents/Structured/Agent), the persistent degradation banner, the cramped transcript viewport
  — makes the whole surface read more "tool" than "chat," but that is the surface-convergence / 600·602
  territory, not 610's transcript-control layer. 610 should make the *controls* feel like mainstream chat;
  it cannot make the *whole surface* feel like ChatGPT/Claude on its own.

---

# §D.5 — As-built: the §D.2 action bar shipped (2026-06-19, agent — supersedes §D.4 "remaining work")

The §D.2 presentational re-housing is **implemented and verified** in worktree `tempdoc-610` — the §D.4
"Remaining work" list is now done, with one documented divergence carried as a follow-up (the retry pager).

**What shipped (presentation only — no substrate, no handler behavior change):**

- **One per-turn icon action bar beneath each settled turn** — `renderTurnActionBar(m, idx)` composes a
  hover-revealed `.turn-actions` row of native icon buttons (`.turn-act-btn`), gated by `canTurnControl(m)`.
  *Assistant:* `copy` + `↻ retry` visible, then `⋯` (`more-horizontal`) → Branch to new thread · Reset
  context · Compact. *User:* `✎ edit` visible, then `⋯` → Retry · Branch · Reset. The scattered sites are
  gone: the assistant `.message-shape-tag` header keeps only the shape label (a transparency element, not a
  control); the user bubble's corner buttons are removed.
- **Edit morphs the bubble in place** — `.message.user.editing` keeps the bubble's `align-self: flex-end` +
  max-width (no full-width stretch); the textarea sizes to the bubble, primary **Send** / secondary
  **Cancel**.
- **`more-horizontal` icon** added to `Icon.ts` (the only additive primitive — lucide three-dots for `⋯`).
- **Dual-path coverage** (the §C.1 lesson): the bar renders on the live path (`renderMessage`) *and* both
  `renderUnifiedItem` assistant record branches via `recordActionBar(itemId)` — so controls appear on
  reloaded/persisted turns, not just live ones.

**Verification (this close-out pass):** FE `tsc` clean; full unit suite **3254 pass / 334 files** (incl.
the updated action-bar + in-bubble-edit tests); FE gates green — `controls-a11y`, `presentation-purity`,
`theme-token-closure`, `message-single-model`, `style-literal-ratchet`, `atom-fork-ratchet`. The action bar
was DOM-confirmed live in the prior batch (user bar `[Edit, ⋯]` below the bubble; assistant bar
`[Copy, Retry, ⋯]` in the message box; in-bubble edit morph with Send/Cancel); the assistant-bar pixels were
clipped by the cramped viewport but DOM-proven.

**Documented follow-up (NOT a §D.2 gap):** the version pager attaches to the **divergent (first-own) turn**,
which for *retries* is the re-dispatched **user** turn — ChatGPT shows regen arrows on the **assistant** turn.
This is a coherent consequence of the re-dispatch branch model (the engine cannot regenerate an answer in
place — §B/U1 — so a retry forks the whole user+assistant pair). Exact answer-only placement needs a
branch-after-user model and is a follow-up, not part of this re-housing. For *edits* the pager already sits
on the user turn, matching ChatGPT.

**610 status:** all four phases (A–D) + the §C.2 inline-Edit alignment + the §D.2 mainstream-parity action
bar are implemented and live-verified. The only open thread is the **U4 product question** (does the product
surface a long persistent multi-turn thread where compaction has real day-to-day value?) — a product
decision, not implementation work; compaction is mechanism-complete and correct regardless.

---

# §E — Post-merge research: the long-term design space (2026-06-19, agent, autonomous research)

> Pure-research pass (no code), 2 rounds: competitive/frontier web survey + codebase seam-mapping +
> sibling-tempdoc adjacency. Goal: what could we *theoretically* build on the shipped substrate — polish,
> simplify, extend, or net-new — with no specific target and no rush (app is pre-production, no users).
> Sources are cited inline; the through-line is a single thesis.

## §E.1 — The thesis: make the AI's context a manipulable *workspace*, not an invisible side-effect

The shipped transcript controls (edit/retry/branch/version-pager) are **commodity** — they deliberately
mirror ChatGPT/Claude, and the survey confirms we're at or ahead of that frontier (ChatGPT's Sept-2025
"Branch in new chat" only forks to a *new sidebar thread*; Gemini's draft-pager is *last-turn-only*; **no
mainstream product renders the conversation tree in place** the way our inline `‹ n/m ›` pager does).
[ChatGPT branch](https://eu.36kr.com/en/p/3453602336593541) ·
[Gemini drafts](https://support.google.com/gemini/answer/14262426) ·
[LibreChat fork](https://www.librechat.ai/docs/features/fork)

The **effective-context lineage** (floor + inspectable compaction — display decoupled from prompt) is the
real asset. The survey's single strongest finding: **mainstream chat manages context silently and exposes
nothing**, and the one place it surfaced (Anthropic's 2025 API `context editing` + `compaction`) is
*automatic, API-only, and non-inspectable* — and was promptly criticized as "a garbage collector without
write barriers" precisely because agents *can't inspect what was removed* and can't review the summary
before it stands in.
[Anthropic context-management](https://claude.com/blog/context-management) ·
[critique](https://conikeec.substack.com/p/context-editing-looks-like-a-feature)
**Our design — visible, inspectable, reversible context control — is the answer to that critique.** Two
independent essays effectively spec our roadmap: Blake Crosley's "chat is the wrong interface" names a
missing **"Context Budget Meter"** and **"Memory Browser"** as core gaps
([Crosley](https://blakecrosley.com/blog/chat-is-the-wrong-interface)); Simon Willison's memory critique
asks for *visible, scoped, toggleable* context
([Willison](https://simonwillison.net/2025/May/21/chatgpt-new-memory/)).

So the long-term arc is **one object (the effective context) with a small, coherent set of operations** —
and crucially most of them *compose primitives we already shipped*, honoring §5's anti-over-build rule:

| Operation | Verb | Status | Substrate it composes |
|---|---|---|---|
| **MEASURE** | "how full / how healthy is the context" | new (cheap) | real `promptTokens` already flow via `OnlineAiService…onUsage`; `TokenEstimation.estimateTokens` exists (used by `RAGContext`) |
| **SUBTRACT** | floor / rewind | **shipped** | `setContextFloor` + `loadEffectiveContext` |
| **SUBTRACT (fine)** | per-message include/exclude | new (moderate) | generalize the floor: a filter predicate in `loadEffectiveContext` + a meta flag |
| **SUMMARIZE** | compaction | **shipped** (inspectable) | `compactContext` (summary as synthetic system message) |
| **SUMMARIZE (edit)** | correct the summary | new (cheap) | the summary is already stored editable text |
| **TRANSPLANT** | merge a sibling branch's gist back in | new (moderate) | **the same compaction primitive**, pointed at a sibling instead of the prefix |
| **INSPECT** | "show me exactly what the AI sees now" | new (moderate) | one read-only projection over `loadEffectiveContext` |
| **ADD** | @-mention to pull docs/messages in | **belongs to 611** (composer), not 610 | boundary note in §E.6 |

This is the differentiated long-term design: a **context workspace**. It is mostly *re-pointing existing
seams*, not new substrate.

## §E.2 — POLISH (small, on the as-built)

- **Editable compaction summary (not just inspectable).** Directly answers the "no write barriers"
  critique: let the user *correct* what the summarizer got wrong before it stands in for the dropped turns.
  Cheap — the summary is already stored text; add an edit affordance to the "Show summary" expander and a
  `PUT …/context-floor/summary`. **Strong external pull, low cost.**
- **Restore-scope menu on the floor's Restore.** Borrow Claude Code `/rewind`'s "conversation / code /
  both" model ([/rewind](https://blog.vincentqiao.com/en/posts/claude-code-rewind/)) — here it'd be
  "restore context-visibility only" vs "also restore the transcript view," making Restore explicit. Tiny.
- **Retry pager on the assistant turn (the one documented §D.5 divergence).** Mainstream puts regen-arrows
  on the *answer*; ours sits on the re-dispatched user turn because the engine can't regenerate in place
  (U1). Closing this needs a *branch-after-user* model (fork keeping the user turn, varying only the
  answer). Real but low-value polish; record, don't rush.

## §E.3 — SIMPLIFY (reduce internal complexity — the one real smell)

- **Unify the dual render paths.** `renderMessage` (live overlay) and `renderUnifiedItem` (persisted record)
  duplicate the turn *frame* (role label, floor divider, out-of-context band, action bar). This split is the
  root cause of **both** bugs found live during 610 (controls/pager, then floor divider, each missing on the
  record path until patched). The content rendering legitimately differs (stream state ≠ record state), but
  the *frame* should be one `renderTurnFrame(...)` both paths call. Seam-mapping estimate: ~400–600 lines,
  moderate, **high upside** — it collapses two bug-injection points into one. This is the highest-value
  *internal* cleanup; everything else is additive.

## §E.4 — EXTEND (build on existing primitives — the highest-leverage near-term work)

- **★ Context-budget meter — the single best next build.** Most-requested unbuilt surface in the survey
  (Crosley's "No Context Budget Meter"; every context-engineering guide). It makes the floor/compaction
  legible as a **quality lever, not just cost**: ground the bands in the "context rot" research — below
  **~50% utilization** models favor start+end (U-shaped), above 50% "lost-in-the-middle" worsens, and
  degradation accelerates past ~30K tokens; 65% of enterprise AI failures were context *drift*, not
  overflow. [Chroma/context-rot](https://www.producttalk.org/context-rot/) ·
  [understandingai](https://www.understandingai.org/p/context-rot). So the meter should show **effective
  (post-floor/post-compaction) utilization with a quality band**, not a raw token count. Cheap on our
  substrate: real `promptTokens` already arrive via `onUsage`; `TokenEstimation` covers pre-send estimates;
  per-message token counts are a one-line add in `enrichMessage`. **High value × low cost × strong external
  validation = do this first.** *(Cost corrected in §G: the tokens reach `onUsage` but are discarded by the
  chat loop, so the meter needs a small backend wire to surface them + the FE caching the window from
  settings — cheap, but not FE-only.)*
- **Per-message include/exclude toggles.** Generalize the binary floor into granular curation. Honest
  novelty check: this is now *emerging*, not unbuilt — TypingMind shipped "Exclude from Context" per message
  (Nov 2025) and Vercel AI SDK has `pruneMessages`.
  [TypingMind](https://feedback.typingmind.com/changelog/bulk-delete-messages-exclude-specific-messages-from-context-and-uxui)
  Our differentiation is **integration**: the toggle alongside the *visible* floor + budget meter +
  inspectable summary as one coherent surface, which nobody has assembled. Moderate (a filter predicate in
  `loadEffectiveContext` + a per-message meta flag).

## §E.5 — NEW (net-new UX, bigger bets)

- **Branch-merge-back ("transplant").** open-webui's top unmet ask; real research prior art (GitChat's
  `/merge`, ContextBranch's `inject`, Claude Code issue #32631's fork/merge/tree spec).
  [ContextBranch](https://arxiv.org/html/2512.13914v1) The pattern is *"inject an LLM summary of branch B
  into branch A as a system message"* — which **is our existing compaction primitive** pointed at a sibling
  instead of the prefix. So the substrate cost is low-moderate (reuse `compactContext`'s
  summary-as-system-message path; add a sibling picker). Only feasible because we already model context as a
  manipulable object. **The most "wow" feature with the smallest substrate surprise.**
- **A "context inspector" / memory-browser panel.** One read-only surface answering "what does the AI see
  *right now*?" — active turns + the standing summary + any excluded items — directly filling Crosley's "No
  Memory Browser." A projection over `loadEffectiveContext`; pairs naturally with the meter. Moderate,
  mostly FE.
- **Promote an EPHEMERAL Ask turn into a PERSISTENT thread.** The survey + adjacency both note our
  document-grounded Ask tier is per-turn-stateless; a "continue this as a conversation" affordance would let
  a one-shot answer become a durable, controllable thread. Seam-mapping *initially* called this manifest-cheap, but
  §G corrects it: flipping a *shape's* persistence mode is cheap yet changes *all* Ask turns; promoting
  *this one* existing ephemeral thread is **Moderate** — a directory-tree copy (JSONL+meta) + state
  reconciliation across the 561 dual-key model, no built-in API. Still the most direct *product* response
  to U4 (§E.7).

## §E.6 — Boundaries & adjacency (harmonize, don't collide)

- **ADD-context belongs to 611, not 610.** The "@-mention to pull docs/messages into context" half of the
  workspace is *additive next-turn material* — 610 §13.1's boundary puts that on the **composer (611)**.
  The clean split is **"611 adds, 610 subtracts/summarizes/measures."** Stated so a future builder doesn't
  grow an @-picker on the transcript.
- **612/613 (activity/notifications):** setting a floor / compacting / editing a turn are *causal user
  actions*; 613's PUSH-event routing is ready and 612 should classify them as audit-relevant (not routine).
  A "you reset context to turn 5" Activity row is a transparency win that composes cleanly. No collision.
- **609 (tab-state) is a hard prerequisite, not an idea.** Every 610 control operates on a persistent
  `sessionId`; 609 §J.2's repro (the "Continue your last conversation?" card vanishing on navigate-away)
  shows the thread can be *lost* today. 609's identity-recoverable class must keep the `sessionId` +
  `contextFloor` alive across navigation or the whole workspace is orphaned. Flag as a dependency.

## §E.7 — The U4 product question, sharpened by the research

610's open question was *"where does a long persistent multi-turn thread that needs compaction actually
exist?"* The adjacency pass found the **answer in 609 §J.2**: persistent chat exists today only as a
**"Continue your last conversation?" resume card** — *reactive recall*, not a continuous long-running
thread. So:

- **Rewind, the budget meter, per-message exclude, and the inspector all stand regardless** — they are
  correctness/quality levers that bite even in short threads (context rot starts well before any limit).
- **Compaction *depth* and merge-back are the parts gated on product direction** — they only pay off if
  persistent long threads (or agent-conversations) become a *first-class* surface rather than a resume card.
  The cleanest way to *create* that surface is the **EPHEMERAL→PERSISTENT "continue as a conversation"
  promotion** (§E.5) — it manufactures the long-thread use case the compaction half is waiting for.

## §E.8 — Recommended sequencing (if/when any of this is picked up; nothing is committed)

1. **Context-budget meter** (cheap, validated, makes the shipped floor/compaction legible) — best ROI.
2. **Editable summary + Restore-scope menu** (cheap polish that closes the "no write barriers" critique).
3. **Render-frame unification** (internal simplify; removes the recurring dual-path bug-class).
4. **Per-message include/exclude** + the **context-inspector panel** (the workspace's curation + visibility).
5. **EPHEMERAL→PERSISTENT promotion** (the product move that answers U4 and unlocks compaction's value).
6. **Branch-merge-back** (the marquee feature; reuses the compaction primitive) — once persistent threads exist.

All of the above are *additive over the shipped substrate*; none require a new store, a new lineage, or the
unified-lineage abstraction §5 rejected. The one *subtractive* item (render-frame unification) is the only
internal refactor and the only place a bug-class actually lives.

---

# §F — Settled long-term design + reach judgment (2026-06-19, agent, design theorization)

> §E surveyed the *idea space*. §F settles the *structural design* for that space and judges its reach.
> Method: investigate what already exists, conform to it rather than fork it, and size new structure to the
> problem the tempdoc actually has now — recognizing a principle without prematurely building it.

## §F.1 — What already exists (investigated, verbatim against source)

The model's input today is assembled in exactly one place — `ConversationEngine.dispatchSubstrateDriven`
(`:241–276`) — as three concatenated parts:

```
[ systemPrompt ]  +  loadEffectiveContext(history)  +  injector messages
   (contributors)      (SUBTRACT / SUMMARIZE phase)      (ADD phase)
```

Two of these are already *declared, ordered, registry-resolved pipelines*:

- **The additive phase is the `ContextInjector` seam** (tempdoc 491): an `id()` + `inject(ctx)→InjectorResult`
  SPI, resolved by `ContextInjectorRegistry`, **composed by declaration order in the shape manifest**
  (`ConversationShape.contextInjectorIds`), tier-gated by `allowedShapeTiers`. It only ever *prepends*
  messages ("side-data… before the user message"). Instances: `RAGContext`, `ExternalContextInjector`,
  `UserPromptInjector`, `DocAccess`, `SelectionContextInjector`.
- **Tempdoc 498 is the precedent that matters most.** When the team needed to thread *prior conversation
  context across shapes*, they did **not** invent a mechanism — they shipped one more injector
  (`ExternalContextInjector`, id `core.external-context`) and wired it into shape manifests
  (`[ExternalContextInjector.ID, RAGContext.ID]`). The established rule is therefore already in the codebase:
  **a new way of shaping what the model sees is a new *declared, manifest-ordered stage*, not an ad-hoc rewrite.**

The **subtractive/summarizing phase**, by contrast, is *not yet* a pipeline — `loadEffectiveContext` is a
hardcoded two-step (trim-at-floor, then prepend-summary-as-synthetic-system-message). That is correct *today*
because there are exactly two operations and they don't interact.

## §F.2 — The design: the effective context is a *declared projection of the transcript*

The right long-term shape for §E's roadmap is **not** a new abstraction; it is the **subtractive counterpart
to the injector seam**, conforming to the same shape it already has:

- **The effective context is a projection of the canonical transcript** produced by an **ordered, declared,
  inspectable** set of *history operations* — `floor(cut)`, `exclude(message)`, `compact(range→summary)`,
  `transplant(sibling→summary)` — that **rewrite** the base history before the additive injectors run. The
  injector chain answers "what to ADD"; this phase answers "what the model SEES of the prior turns." Same
  shape (id · ordered · registry-resolved · manifest/-session-declared · inspectable), different *contract*
  (rewrite vs prepend — the one reason it can't simply be more injectors: `InjectorResult` can only prepend,
  never remove or replace existing history).
- **The shipped floor+summary is the degenerate, one-or-two-stage instance of exactly this projection.** It
  is already on the right side of the line; nothing about it needs to change.
- **Scope discipline — when to build the pipeline, and not before.** The structure earns its place precisely
  when **two or more rewriting operations beyond floor+summary are committed** (e.g. per-message `exclude`
  *and* `transplant`). That is the point where *ordering and interaction* become real (does exclude apply
  above or below the floor? does a transplanted summary sit before or after a compaction summary?) — and
  ad-hoc `meta.json` fields + special-cased `loadEffectiveContext` branches would otherwise become the bug
  surface. Below that line, keep adding minimally and ad-hoc **but ensure each new op is expressible as a
  future projection stage** (don't special-case into a corner the pipeline can't later absorb). This matches
  §5's rule: do not build the unified abstraction for a problem that has one or two non-interacting steps.
- **The measure/inspect verbs are reads over this projection, not new state.** The budget meter measures the
  projection's output (real `promptTokens` already arrive via `onUsage`); the inspector renders the
  operation list + output. Neither needs the pipeline to exist first; both *strengthen* the case for it by
  making the projection legible.

So the design is: **one projection (transcript → effective context) with two declared transform phases —
the existing additive injector chain and a subtractive history-operation phase that the floor/summary
already seed.** No new store, no new lineage, no parallel mechanism.

## §F.3 — The one piece of structure the *present* problem already requires

Everything in §F.2 is *deferred* (the roadmap is uncommitted). The single exception — structure warranted
**now** — is the **render-frame projection** (§E.3): `renderMessage` (live) and `renderUnifiedItem` (record)
fork the turn *frame* (role label, floor divider, out-of-context band, action bar), and that fork is the
proven root cause of **both** bugs found live during 610. The fix is local and conformant: a single
`renderTurnFrame(turn)` that both paths call — i.e., **the rendered frame is a single projection of the
canonical turn record**, the display-side analog of §F.2's prompt-side projection. This is warranted now
because the problem is *present and recurring*, not speculative; it is a one-function consolidation, not a
framework.

## §F.4 — Reach judgment: the principle, its scope, and the deliberate defer

**Is this an instance of an existing seam/principle?** Yes — twice over. The effective-context projection is
an instance of the **`ContextInjector` pipeline seam** (same declared-ordered-inspectable shape), and both
are instances of the codebase's broader **projection-vs-fork / single-derivation** discipline already
enforced elsewhere: the `SearchTrace` projection register (553), `computeVerdict` single-derivation (595),
`buildSearchIntent` single-issuer (577), and the presentation kernel's single-authority rule (doc 27). The
effective context is to the transcript what a `SearchTrace` projection is to the canonical trace — a *derived
view, not a second authority*. **Conform to that; do not create a parallel "context engine."**

**The principle, named plainly:**

> **Both of a turn's derived views — what the *model* sees (effective context) and what the *user* sees
> (rendered frame) — must each be a single, declared, ordered, inspectable projection of the one canonical
> turn record; never an ad-hoc, hidden, or forked parallel computation.**

**Where else it applies (candidate scope — recorded, not built):**
- **Prompt-input axis:** the additive injector chain (491/498), the subtractive effective-context phase
  (610), 611's composer "@-add" (additive — an injector instance, *not* a transcript control: §E.6's
  "611 adds, 610 subtracts" is really *two ends of one projection*), and `AgentPromptComposer` for agent
  runs (likely a third instance worth auditing against the same shape).
- **Display axis:** the turn frame (§F.3), and any future surface that renders the same turn record in more
  than one place.

**Does existing code already violate it?**
- **Present, real violation (display axis):** the dual render-path fork — fixed by §F.3, which is why that
  one piece is built now.
- **Latent, not-yet-harmful (prompt axis):** the subtractive phase is hand-coded rather than a declared
  pipeline. This is *acceptable at two operations* and becomes a violation only if a third interacting op is
  bolted on ad-hoc. The principle's value here is **preventive** — it tells a future builder which side a new
  op belongs to (add→injector, rewrite→effective-context phase) so the fork never forms.

**Do we build the generalized structure now?** No — and the separation is deliberate. The *render-frame*
projection is built now because the present problem (a live bug-class) requires it. The *effective-context
pipeline* is **recognized and recorded, not built**: its trigger is the commitment of ≥2 interacting
rewriting operations, which the tempdoc does not yet have. Recognizing the principle is not a license to
build the framework — it is a guard so that when the roadmap does commit those operations, they conform to
the projection shape instead of forking a parallel one.

---

# §G — Confidence-building findings (2026-06-19, agent; corrects §E/§F where the probes disagreed)

> Read-only source verification of the load-bearing §E/§F claims (several had come from unverified
> subagents — the `audit-without-test` risk). Four parallel probes (file:line-cited) plus one attempted live
> check. **Two cost claims were wrong and are corrected here; the rest confirmed.** No feature code was
> written.

**Probe A — the context-budget meter is NOT "nearly free" (corrects §E.4 / the §F.1 MEASURE row).**
Token counts *are* produced — `AiUsage(promptTokens, completionTokens, totalTokens)` (`OnlineAiService.java:33`) —
but the substrate-driven chat loop **discards them**: the `onUsage` callback is a no-op (`usage -> {}`,
`ConversationEngine.java:413`); only the *agent* shape emits a `budget_update`. The window denominator
(`InferenceConfig.contextSize`, default 4096) is exposed to the FE **once** via `GET /api/settings/v2`
(`LlmSettingsV2.contextWindow`), **not in the chat stream**. `TokenEstimation.estimateTokens` exists but is a
heuristic (word/char), and `RAGContext` uses it against a **hard-coded 8192**, not the live window. **So the
meter needs ~2–3 small backend wires (surface `onUsage` tokens on an SSE/done event) + the FE caching the
window from settings** — cheap, but *not* the FE-only "real tokens already flow" I wrote. Still the best-ROI
item; just price in the backend wire.

**Probe B — render-frame unification is ~60%, not a clean single function (refines §E.3 / §F.3).** The frame
*was* confirmed as the root cause of both 610 bugs. But `renderMessage(ThreadMessage)` and
`renderUnifiedItem(UnifiedTurnItem)` only share ~60% of the frame (floor divider, frame line, and action bar
already route through shared helpers — `recordFloorParts`/`recordActionBar` are existing adapters). The
other ~40% **can't** unify cleanly: `UnifiedTurnItem` lacks `shapeId` and `inheritedFromParent` (the record
path uses the global `currentShapeId()`), so a shared `renderTurnFrame` needs optional/nullable params.
Realistic size **~150–200 LOC** (smaller than the §F.3 "~400–600" guess), recommended as
*extract-pure-helpers-first, then a frame-assembly interface* — not a big-bang merge. Still the one
present-warranted internal fix; the type-gap is the caveat to design around.

**Probe C — the cheap items are confirmed cheap; promotion is NOT "manifest-cheap" (corrects §E.5).**
Confirmed: messages carry stable UUID ids (`FileConversationStore.java:158`); `meta.json` takes new optional
fields with no migration; `loadEffectiveContext` (`:365–393`) is the **single** filter/seed point (a
per-message `exclude` slots in there); `compactContext` stores **arbitrary** `summaryText`, re-read every
load (so *editable summary* and *merge-back* both ride the shipped primitive). **Correction:** promoting an
*existing* EPHEMERAL Ask thread to PERSISTENT is **Moderate**, not manifest-cheap — the 561 dual-key model
writes Ask turns under a `conversationId`/`threadId` key, so promotion is a **directory-tree copy
(JSONL+meta) + state reconciliation**, with no built-in API. (Flipping a *shape's* `persistenceMode` is
cheap, but that changes *all* Ask turns and is not the "continue this one as a conversation" feature §E.5
meant.)

**Probe D — governance cost confirmed minimal.** None of the three near-term items (meter / editable summary
/ per-message exclude) introduces a new `ConversationShape`, so **no 3-file `intent-tier-coverage`
registration**. Gates that bite: `controls-a11y` + `presentation-purity` for the two interactive ones; the
read-only meter trips **zero** gates. Use `<jf-control>` + i18n label keys and they pass — the same
discipline every existing control follows.

**Probe E — the U4 live check was not re-run, by design.** The stack came up but `ai_activate` failed
(`Variant not installed: cuda12` — model-env friction in main's dev-data, not a code issue). I did not chase
it because the *mechanism* is already triply-covered: free-chat is code-confirmed PERSISTENT
(`ConversationEngine:248` seeds from `loadEffectiveContext`), Probe C confirmed that is the single seeding
point, **and §C.1 already live-verified it** (the planted-"durian" test — turn 2 recalled turn 1; with a
floor set, it did not). The *product* half (is there a long-thread surface vs. a "Continue?" resume card)
is a product-direction judgment already evidenced by 609 §J.2, which a screenshot would not settle. So U4's
status is unchanged: the deep half (compaction-depth, merge-back) remains gated on the product decision, not
on a feasibility unknown.

## §G.1 — Net effect on the design and confidence

The probes **did not move the design** (§F's projection thesis stands — `loadEffectiveContext` confirmed as
the single subtractive seam; `compactContext`'s arbitrary summary confirmed as the merge-back substrate).
They **re-priced two items**: the meter gains a small backend wire (no longer FE-only), and per-thread
promotion is Moderate (a copy), not a flag flip. Everything else confirmed cheap/feasible, with the
render-frame fix smaller-but-caveated. Net: fewer unknowns, two honest cost corrections, no new structure
required.

---

# §H — As-built: the near-term roadmap implemented (2026-06-20, agent, worktree `610-context-controls`)

The §E.8-sequenced, §G-repriced near-term bucket is implemented and **statically verified**; the one live
step is **deferred (not skipped)** — see the gate below.

**Shipped (4 phases, all extending shipped seams — no new store/lineage/`ConversationShape`):**

1. **§E.4 context-budget meter.** Backend: `ConversationEngine.streamLlm` now captures the previously-discarded
   `AiUsage` (the `usage -> {}` no-op) and surfaces `promptTokens`/`totalTokens` on the chat `done` payload.
   FE: `UnifiedChatView` reads it in `onDone`, and `renderContextMeter()` shows a footer bar reusing the
   existing `projectContextHorizon` projection + `aiState.runtime.contextWindow` + the shared `budget-bar`
   visual + the one fullness→colour authority (broadened the §577 fill rules). Hidden in agent mode (the
   activity rail owns headroom there) and when occupancy/denominator are unknown.
2. **§E.2 editable compaction summary.** `renderFloorDivider` gains an inline **Edit** → textarea morph
   (Save/Cancel); Save → `editContextFloorSummary` → `POST …/context-floor/summary` → `handleEditContextFloorSummary`,
   which **reuses `compactContext`** with the session's current floor (no re-summarization). Answers the §E.1
   "no write barriers" critique.
3. **§E.3 per-message exclude.** Store: a `meta.excludedMessageIds` list + a filter in `loadEffectiveContext`
   applied **before** the floor trim; `ConversationStore.excludeMessage`/`excludedMessageIds` (+ NoOp /
   RecordingStore stubs). API: `POST …/messages/{id}/exclude` + `excludedMessageIds` on the history response.
   FE: a `⋯` Exclude/Include toggle, a dashed-rail dim treatment (distinct from the floor band), restored on
   reload. The *first* new rewriting op — added ad-hoc on the single `loadEffectiveContext` seam, deliberately
   **not** the pipeline (§F: the pipeline's trigger is the *2nd* interacting op).
4. **§F.3 render-frame single-sourcing.** Extracted `floorFrameParts` as the one authority for a turn's floor
   divider + out-of-context/excluded dim-class; both the live (`renderMessage`) and record (`recordFloorParts`)
   paths derive from it, closing the bug-class (a control landing on one path, forgotten on the other) that
   produced the two §C.1/§D.5 bugs. The deeper full `renderTurnFrame` merge stays a documented follow-up (the
   record `UnifiedTurnItem` lacks `shapeId`/`inheritedFromParent`, so a shared frame would weaken the contract —
   Probe B).

**Dropped, with reason:** the "Restore-scope menu" (§E.2 idea) — our transcript is always fully displayed and
Restore does the single meaningful thing (clear the floor); the Claude Code multi-scope split has no second
axis here.

**Deferred by design (unchanged from §F):** the effective-context operation pipeline, branch-merge-back, and
EPHEMERAL→PERSISTENT promotion (product-gated / the ≥2-interacting-ops trigger); the full context-inspector
panel.

**Verification.** Static: **all green** — FE `tsc` clean; full FE unit suite **3322 pass / 341 files** (incl.
+8 new tests: meter, editable-summary commit + render, exclude toggle + filter-ordering, frame-parity); FE
gates pass (`controls-a11y`, `presentation-purity`, `theme-token-closure`, `style-literal-ratchet`); backend
`:app-agent-api:test :app-services:test :ui:test` green (the only 2 reds were the environmental
`AiInstallServiceLateBindTest` missing-worker-dist, which pass once `installDist` is built — unrelated to this
work). The exclude-before-floor *ordering* and the compaction-summary *reuse* are unit-pinned in
`FileConversationStoreTest`.

**Live-browser GATE — DEFERRED (not skipped).** The plan makes live UI validation the success condition for
the 3 user-visible phases. At close-out the shared dev stack was **actively owned by another agent**
(`quick_health` verdict `CONTENTION`, fresh lease, different session). Per branch-safety + the plan's own
rule, the stack was **NOT taken over**. So the live batch (meter % moves as a thread grows; edit-summary morph
+ persisted re-injection; exclude dims a turn and the model stops seeing it — the §C.1 "durian" check; no
visual regression from §F.3 on live + reloaded turns) remains **outstanding** and must run before this work is
considered fully closed. Work is committed in worktree `610-context-controls`; **not merged**.

## §H.1 — Live-browser validation result (2026-06-20, agent — supersedes the §H "deferred" gate)

After user-approved dev-stack takeover, the FE was loaded in a real browser (Chrome) against the
**worktree's own backend** (api 50883, launched via `distFrom`). The app **mounts cleanly with all four
phases' changes — no breakage**. The model runtime is **environmentally unavailable** in this box (the
`cuda12` llama-server variant is not installed in `native-bin`, and fixing it would mutate shared resources),
so the *rendering + interaction* of every feature was validated by driving the live `<jf-unified-chat-view>`
component's real state and reading the **real rendered DOM + computed styles** (a legitimate real-browser
validation), while the *LLM-dependent behaviors* are covered by the backend unit tests.

**Validated live (DOM + computed style + screenshots):**
- **§E.4 meter** — `.context-meter [role="meter"]` with `aria-valuenow="25"`, label "Context 25% · 1024 /
  4096 tokens", fill class `context-fill-green` (the correct <50% band).
- **§E.2 editable summary** — the compacted floor divider (`❏ Context compacted …`) with **Edit** beside
  Show/Hide summary + Restore; clicking Edit morphs the summary into a `<textarea>` seeded with the text +
  **Save/Cancel** (screenshot-captured).
- **§E.3 per-message exclude** — the excluded turn renders with `.message.excluded` and a real computed
  `border-left-style: dashed` (the distinct-from-floor dashed rail; screenshot-captured).
- **§F.3 / §D.2** — the floor divider and the per-turn action bar (copy/retry/⋯) render on the thread.

**Covered by unit tests, not live (model runtime unavailable):** the meter's `promptTokens` arriving from a
real turn's `done` event; compaction summarization; exclude/edited-summary's actual prompt effect. These are
pinned by `FileConversationStoreTest` (exclude-before-floor ordering; `compactContext` arbitrary-summary
re-read) + `SubstrateDrivenEngineTest` + the FE unit suite (meter bar, edit morph, exclude toggle,
frame-parity). The §C.1 "durian" planted-fact check remains the procedure to run once a model runtime is
present.

**Net:** the live gate is **met at the rendering/interaction tier**; the LLM-behavior tier is unit-verified
and re-runnable when a model variant is installed. Work committed in worktree `610-context-controls`; **not
merged**.

---

# §I — Post-implementation research: the *whole-prompt* workspace + manual→assisted (2026-06-20, agent)

> Second research pass — now that the conversation context-workspace is **shipped** (meter, editable summary,
> per-message exclude, partial render-frame; §H, merged). Two rounds (frontier web survey of the angles §E
> skipped + codebase seam-mapping of the shipped code). Goal: polish / simplify / extend / new on the
> *as-built*. The §E thesis evolves in two concrete ways.

## §I.1 — Two design advances the research reveals

**(A) The workspace covers only *conversation turns* — but the prompt is bigger, and the rest dominates.**
The model's input is `system + effective-conversation + injected-retrieval`. §H's workspace (floor / exclude /
summary / meter) governs only the *conversation* slice. In a **document-grounded** app the **retrieved
documents are 80–99% of the prompt** ([arXiv 2512.13438](https://arxiv.org/pdf/2512.13438); the same lesson
Claude Code's `/context` surfaces — "file reads dominate context usage"
[Claude Code context window](https://code.claude.com/docs/en/context-window)). So the workspace is *incomplete
on its most important axis*. The natural completion: **make the workspace span the WHOLE prompt** — the meter
*attributes* across system / conversation / retrieved-docs; exclude/pin extend to *retrieved sources*; the
inspector shows *everything* the AI sees. This is our differentiator: source-level on/off exists (NotebookLM
checkboxes, Perplexity de-select, AnythingLLM pin-to-full-text), but **per-source/per-chunk *token cost* shown
to the user, and interactive exclude/re-rank of retrieved context, is unbuilt at the end-user tier** (it lives
only in dev telemetry). [NotebookLM](https://www.jeffsu.org/notebooklm-changed-completely-heres-what-matters-in-2026/) ·
[AnythingLLM](https://docs.anythingllm.com/chatting-with-documents/introduction) ·
[Chat-with-RAG telemetry](https://vrraj.github.io/chat-with-rag/technical-overview.html)

**(B) Manual is now "dated"; pure-auto is table-stakes; the gap is *assisted*.** Auto-compaction with a
threshold slider + before/after token diff + an expandable "what was condensed" summary is now standard
(Claude Code auto-compact; Roo Code "Intelligent Context Condensing"; Cline).
[Roo Code](https://roocodeinc.github.io/Roo-Code/features/intelligent-context-condensing) ·
[Claude Code auto-compact](https://claudelog.com/faqs/what-is-claude-code-auto-compact/) Our floor/compaction
is **manual-only**. The genuinely *unbuilt* frontier (confirmed: only patents + engineering practice, no
end-user product): the assistant **SUGGESTS** which turns/sources to drop (a surfaced importance signal) and
the user **accepts / undoes** — assisted, not silently-automatic. We already have the *mechanism* (exclude) and
the *trigger* (the meter); "suggest-then-confirm" sits on top.

These don't replace §E.1's "context workspace" thesis — they **complete** it (span the whole prompt) and
**level it up** (manual → assisted), still composing shipped seams.

## §I.2 — POLISH (on the as-built)

- **★ Meter → attribution breakdown.** The shipped meter is one bar (whole-prompt occupancy). Port Claude
  Code's `/context` model: a small breakdown of **system vs conversation vs retrieved-docs vs tool output**,
  since retrieved-docs dominate. Seam (Moderate): `TokenEstimation.estimateTokens` (already used by
  `RAGContext`) applied per prompt-segment (contributors + injector outputs), tagged onto the done payload.
  Most-validated, most-differentiated polish.
- **Exclude aggregate + bulk.** Show "N turns hidden from context" (trivial — the FE already holds the
  `excludedMessageIds` Set) and a "hide all above the floor" bulk action (Moderate; UX care so "above-floor"
  and "individually-excluded" stay visually distinct — they already are: muted band vs dashed rail).
- **Editable-summary savings feedback.** Show "this summary stands in for N turns / ~M tokens" so the trust
  feature also reads as a budget lever (ties the summary to the meter).
- **Predictive meter (low priority).** A pre-send "this will use ~N tokens / X% full" estimate. Cheap as an
  FE heuristic over `loadEffectiveContext` + pending input; **expensive** live (injectors are stateful). Weak
  external demand for *raw* token preview (Claude Code closed the request, #32436) — only worth it framed as
  **"which retrieved sources will be assembled,"** not a raw count.

## §I.3 — SIMPLIFY (a correction to §F.3 / §H)

**The full `renderTurnFrame` type-merge is now judged *unnecessary* — do not build it.** §F.3/§H carried it as
a follow-up. The seam re-map finds the `ThreadMessage` (live, conversation-scoped) vs `UnifiedTurnItem`
(record, unified-thread-scoped) schemas differ **by design**, not by a bug; the shipped `floorFrameParts`
helper + the record→`renderMessage` delegation already single-source the two duplication points that caused the
bug-class. Forcing one type would flatten the domain model for the 90% of projectors that don't carry
chat-specific fields. So the *partial* I shipped is the correct stopping point — §F.3's "one `renderTurnFrame`"
was over-specified; the bug-class is closed without it. (Records the judgment so a future agent doesn't
"finish" a merge that shouldn't happen.)

## §I.4 — EXTEND (compose shipped seams; the differentiated layer)

- **★ Retrieved-source exclude / pin (the differentiator).** Let the user de-select an irrelevant retrieved
  source or pin a doc to always-include — the turn-level exclude, applied to *retrieved sources*. **Key
  architectural finding:** this is **NOT** the `loadEffectiveContext` seam (610's conversation lineage) — it
  is the **injector seam** (`RAGContext.inject`), which runs *after* the effective conversation is seeded.
  Retrieved chunks carry a stable `(parentDocId, chunkIndex)` id, so the filter is feasible: Cheap as an
  ephemeral per-request filter in `RAGContext.inject`; Moderate to persist (a source-exclusion set, parallel
  to `excludedMessageIds`). This refines the §E.6 boundary (below).
- **The context inspector / "what does the AI see now" panel.** Cheap: one read-only endpoint
  (`GET …/effective-context`) returning `loadEffectiveContext`'s output for display; the panel then shows the
  *complete* picture (standing summary + in-context turns + retrieved sources). Fills Crosley's "Memory
  Browser" and makes the whole workspace legible. (Caveat: it's a dispatch-time snapshot — present it as
  "what the next turn will see," not a historical replay.)

## §I.5 — NEW (bigger bets; record the frontier, surface as *assisted*)

- **Assisted context management — "suggest, don't auto-decide."** When the meter approaches the red band, the
  assistant proposes a specific set of turns/sources to drop or compact (a surfaced importance signal), and the
  user accepts or undoes. Genuinely unbuilt at the end-user tier; builds on exclude (mechanism) + meter
  (trigger). Skeptic's guard: surface as *suggestion with undo*, never silent eviction.
- **Auto-compaction at a threshold** (Roo Code-style slider + before/after diff). Table-stakes elsewhere; we
  have manual compaction, so this is the auto-trigger on top. **Inherits the U4 product gate** — auto-compaction
  only pays off where long persistent multi-turn threads exist (§E.7), so it follows the same product decision.
- **Per-chunk visibility + interactive re-rank** (show the actual retrieved chunks, their token cost, drop/
  reorder before answering). The strongest *unbuilt* differentiator — and the heaviest. Skeptic's guard:
  per-chunk UI risks overwhelming, and the model may not use every displayed chunk; surface as *assisted*
  (cost + a suggested keep/drop), not a raw chunk editor. Record as frontier; do not rush.

## §I.6 — Boundary refinement + reach judgment

**The §E.6 boundary refines.** "611 adds, 610 subtracts" still holds, but the research shows 610's
*subtract / measure / inspect* must span the **whole assembled prompt**, which means it now touches **two
seams**: the conversation store (`loadEffectiveContext` — subtract/summarize turns) *and* the injector chain
(`RAGContext` — filter retrieved sources). The **meter** and the **inspector** are *reads over the full
assembled prompt* (cross-seam); **retrieved-source exclude** is a *subtract on the injector's output*
(injector-seam). The §F.2 conversation-store pipeline trigger is **unchanged** — retrieved-source control is a
*different axis*, not the 2nd interacting op on `loadEffectiveContext`.

**Reach / the principle, extended.** §F named "what the model sees is a single declared, inspectable projection
of the canonical record." The research sharpens its *scope*: the projection is assembled in **three phases** —
`system (contributors) + effective-conversation (subtract phase) + retrieved (inject phase)` — and the
user-facing workspace should make **each phase visible and controllable**, not just the conversation phase.
That is the same projection principle, now seen to span the **full prompt-assembly pipeline**. Candidate scope
where it already applies: `AgentPromptComposer` (agent runs assemble the same three phases) and the 611
composer (the *add* end of the inject phase). **Existing code "violates" it only by omission** — the meter and
the (shipped) controls cover one of three phases. As before: *record the principle and its scope; build only
the phase the present problem makes valuable* (for a document-grounded app, the retrieved phase). Not a
framework — the same conform-don't-fork discipline.

## §I.7 — Recommended sequencing (nothing committed)

1. **Context inspector panel** (one endpoint; makes the whole workspace legible; cheap) — and **meter
   attribution breakdown** (the most-validated polish; turns the meter into "where the tokens go").
2. **Exclude aggregate + bulk** + **editable-summary savings** (cheap polish that finishes the shipped set).
3. **Retrieved-source exclude / pin** (the differentiator; injector-seam; cheap ephemeral first).
4. **Assisted "suggest what to drop"** (the frontier; composes meter + exclude) — and **auto-compaction at a
   threshold** *iff* the U4 product question resolves toward persistent multi-turn.
5. **Per-chunk re-rank** (heaviest; only once the simpler retrieved-source controls prove out).

Everything stays *additive over shipped seams* (no new store/lineage); the one *subtractive* item — the full
`renderTurnFrame` — is explicitly **dropped** (§I.3), not deferred.

---

# §J — Settled long-term design for the whole-prompt workspace + reach judgment (2026-06-20, agent)

> §I surveyed the next-layer ideas. §J settles the *structural* design for the whole-prompt workspace (the §I.1
> thesis) and judges its reach. Method: investigate the assembly seam, conform to the existing trace pattern,
> size the structure to what the §I roadmap actually requires.

## §J.1 — What already exists (investigated, source-verified)

The prompt is assembled at **exactly one point** (`ConversationEngine` dispatch / `buildLlmInput`) into an
**opaque `List<Map>`** — the three phases (`system` contributors, `loadEffectiveContext`, the `ContextInjector`
messages) are concatenated and flattened. **No per-segment provenance is carried** and **no prompt-assembly
trace exists anywhere** (grep-confirmed: no `PromptTrace`/`AssembledPrompt`/per-message phase/origin). The
provenance the workspace needs *does* exist, but **decoupled** from the assembled prompt: conversation segments
carry message ids; retrieved segments carry citations (`parentDocId`/`chunkIndex`) that the RAG injector emits
as *separate events*, never attached to the injected "Documents:…" blob the model sees. And the real total
(`AiUsage.promptTokens`) is an *aggregate* — the model returns no per-segment split.

The pattern to conform to is already in the codebase: **`SearchTrace`** (the retrieval trace) — a derived,
ordered, declared, inspectable projection of *how a search was assembled* (stages, status, provenance), a
"derived view, not a second authority." The prompt-assembly is to the prompt what `SearchTrace` is to retrieval.

## §J.2 — The settled design: the workspace is reads/filters over a derived *prompt-assembly projection*

The whole-prompt workspace does **not** need a new store or lineage. It needs the assembled prompt to stop being
opaque — to be reconstructable as a **derived, attributed projection**: an ordered list of *segments*, each
tagged with its **phase** (system · conversation · retrieved · tool), its **source identity** (the contributor
id / the message ids / the doc-source ids it already carries), and a **token estimate** (`TokenEstimation`,
reconciled against the real aggregate `promptTokens`). The shape **mirrors `SearchTrace`** (ordered, declared,
inspectable). The three §I verbs are then *reads and filters over this one projection*:

- **MEASURE** (meter attribution) = sum token-estimate per phase.
- **INSPECT** (the "what the AI sees" panel) = render the segments with their provenance.
- **SUBTRACT** (exclude a turn / exclude a retrieved source) = filters the projection honours.

**It is a PROJECTION, not persisted state** (the key correction to the naive "store the trace in `meta.json`"
instinct — that would fork a second authority, the §F warning). Like `loadEffectiveContext`, it is *computed on
demand* from the authorities that *are* stored — the messages, the floor, the excluded-turn set, and the turn's
already-persisted **citations** — so it reconstructs the **last completed turn's** prompt *without re-running
retrieval* (post-hoc, matching the meter's last-turn `promptTokens` anchor; the §I.4 inspector caveat —
"snapshot, not replay" — is exactly this). The only *new stored authority* the workspace adds is the
**source-exclusion set** (user intent, parallel to `excludedMessageIds`), which the injector reads to filter
retrieval and which the projection reflects.

**Scope discipline — when this structure is warranted.** It earns its place at the **first whole-prompt
feature** (meter-attribution OR the full-prompt inspector OR retrieved-source control *with visibility*) —
because all three converge on needing the prompt to be attributed. The **shipped opaque single-bar meter +
conversation-only `loadEffectiveContext` is the degenerate, no-attribution instance** of exactly this projection
— already on the right side of the line; it does not change until a whole-prompt feature is committed. Below
that line, the cheap degenerate forms (a one-bar meter, a conversation-only inspector, an *invisible* ephemeral
source filter) are correct and need none of this. This matches §5/§F: do not build the attributed projection for
a problem that still has one opaque phase.

So the design is: **one derived prompt-assembly projection (mirroring `SearchTrace`), computed post-hoc from
already-stored provenance, that the meter/inspector read and the exclude-controls filter — adding exactly one new
stored authority (retrieved-source exclusions) and no new store or lineage.**

## §J.3 — Boundary (with 611 and the injector phase)

The triangle is now precise: **611** owns the *add* entry point (the composer surfaces documents / modes /
templates into the **inject phase**); **the injectors** (`RAGContext` etc.) assemble that added material; **610's
whole-prompt workspace** *measures / inspects / subtracts* the assembled result. So the new stored authorities
across the two tempdocs are: messages + floor + excluded-turns + **excluded-sources** (610), and the
contribution-backed add-actions (611). The prompt-assembly projection is the **shared read surface** that makes
the whole assembly legible — it belongs to 610 (the subtract/measure/inspect side), reading provenance that 611's
adds and the injectors produce.

## §J.4 — Reach judgment: the principle, its scope, and the deliberate defer

**Is this an instance of an existing seam/principle?** Yes — twice. The prompt-assembly projection is a direct
instance of the **`SearchTrace` trace-as-projection** pattern, and both are instances of the codebase's
**projection-vs-fork / single-derivation** discipline (the assembly is a *derived view of its inputs, not a
second authority*). **Conform to `SearchTrace`'s shape; do not invent a parallel trace mechanism, and do not
persist the prompt trace as a second authority.**

**The principle, named plainly:**

> **Anything the system assembles from multiple declared phases — a search, a chat prompt, an agent prompt —
> should be reconstructable as a *derived, attributed, inspectable projection of its inputs*: never an opaque
> artifact, and never a persisted second authority.**

**Where else it applies (candidate scope — recorded, not built):**
- **Retrieval:** `SearchTrace` already *is* this (the conforming instance).
- **The chat prompt:** this design (§J.2) — the next instance.
- **The agent prompt:** `AgentPromptComposer` assembles the same three phases and is, today, equally opaque —
  the same "violation by omission." A future agent-window attribution/inspector would be the third instance.
- **The 611 add-actions:** the *add* end of the inject phase — its contributed material should be attributable
  in the projection (the boundary in §J.3).

**Does existing code already violate it?** By omission, yes: the chat prompt-assembly and the agent
prompt-assembly are both opaque (no trace), and the *shipped* meter is the one-bar degenerate. This is not a
harmful fork (there is no *competing* authority) — it is *absence*, which the whole-prompt work fills for the
chat phase. The agent-prompt phase stays opaque until that surface is built.

**Do we build the generalized structure now?** No — and the separation is deliberate. Recognize the principle
("assembly = traceable projection") and record its scope. Build the **chat** prompt-assembly projection only
when the first whole-prompt feature is committed; the shipped degenerate meter holds until then. **Do not build
a shared `AssemblyTrace` framework** spanning search + chat + agent — `SearchTrace` and the chat projection are
two *instances of one shape*, conforming to it without a common base; a unifying framework is warranted only if a
third instance (the agent prompt) lands and the duplication becomes real. Recognizing the shape is the guard;
the framework is the premature abstraction to avoid.

---

# §K — Frontend / user-facing design for the §I/§J workspace (2026-06-20, agent, live-inspected)

> The §I/§J roadmap is heavily user-facing. This section settles the **frontend** design, grounded in a live
> inspection of the existing surfaces (the served FE predated 610 and the model was offline, so: DOM-injection
> screenshots + the as-built §H.1 screenshots + reading the shipped FE on `main`). Do not design from the
> tempdoc alone — the inspection changed the design (it found an *existing* assisted-context surface to conform
> to, and the right home for source controls).

## §K.1 — What the user sees today (inspected)

- **The shipped context-budget meter** (§H.1) — a small single bar above the composer: "Context 25% · 1024 /
  4096 tokens", colour-banded. Compact and passive (read-only).
- **The retrieved-sources surface** — a document-grounded answer renders a **"N SOURCES RETRIEVED"** list of
  **excerpt cards**, each with a single **"Open ‹doc›"** deep-link (live-injected screenshot), plus a
  collapsible **"Sources · N" chip row** (`renderSourceChips`, 565 §12.3.E) and a docked **`jf-sources-pane`**
  rail. Today the *only* per-source action is select/open — there is **no per-source context control**.
- **The shipped floor divider / editable summary / per-message exclude** (§H.1) — the `↺`/`❏` divider with
  Show-summary + Edit + Restore; excluded turns dimmed with a dashed rail; the `⋯` per-turn menu.
- **The agent activity-rail (577) — the sibling that already does "assisted context."** It carries the
  context-headroom meter AND a **context-pressure gate**: when the window fills it surfaces *"Context filling
  up: N of M tokens — [Compact older turns] [Continue anyway] [Stop]"*. This is the exact assisted-management
  pattern §I.5 proposed — **already shipped on the agent side.**

## §K.2 — The design: the meter is the *entry point*; one **context inspector** is the workspace

The cramped, banner-dominated viewport (§C.1/§D.4) forbids scattering new panels into the transcript. The
clean shape: **keep the always-visible meter as the at-a-glance MEASURE, and make it the single entry point to
a "context inspector" — a drawer that is the consolidated workspace (INSPECT + the controls).** This avoids
crowding the transcript, gives every §I/§J read/control one home, and matches the single-presentation-authority
discipline (doc 27) — one inspector, not N panels.

- **Meter → attribution, progressively.** The single bar stays as the summary; **hover/click reveals the
  breakdown** (system · conversation · retrieved-docs · tools — the §I.2 attribution), reading the §J
  prompt-assembly projection. Default stays one compact bar (viewport-respecting); the breakdown is on demand.
- **The context inspector (the one new surface) is a drawer opened from the meter** ("what the assistant sees
  now"). It renders the prompt-assembly projection as the legible whole: the standing summary, the in-context
  turns, and the **retrieved sources** — each with its token cost — so the user can answer "what does the AI
  see, and what's it costing." This is the §I.4 inspector + the §I.2 attribution, unified. (Reuses the
  `jf-sources-pane` *drawer* pattern that already exists for sources — the inspector is its context sibling,
  not a fork.)
- **Controls stay inline, where their content lives** (the inspector *reflects* them, doesn't relocate them):
  per-turn exclude stays in the transcript `⋯` menu (shipped); **per-source exclude/pin lives on the existing
  source cards / chips** (a small exclude-eye + pin per card, or a per-source `⋯`, mirroring the per-turn menu)
  — that is the inspected home for it, consistent with where sources already are. Excluded sources dim like
  excluded turns; pinned sources carry a pin marker.
- **Cheap polish hangs off surfaces that already exist:** the floor divider gains "N turns hidden" + "hide all
  above" (aggregate/bulk) and "summarized N turns → ~M tokens" (savings); the meter's red band is the trigger
  below.

## §K.3 — Conformance (what the inspection changed)

- **★ Assisted "suggest what to drop" must CONFORM to the agent context-gate (577), not fork it.** The agent
  rail already ships *"Context filling up — [Compact older turns][Continue anyway][Stop]"*. The chat-side
  assisted flow is the **same gate on the chat meter's red band**, offering the chat-appropriate remedies
  (compact / hide-suggested-turns / hide-suggested-sources), opening the inspector with the suggestions
  pre-flagged for accept/undo. So §I.5's "frontier" idea is partly **a port of an existing surface to the chat
  tier** — lower novelty, higher coherence, and it keeps one assisted-context grammar across agent + chat.
- **Reuse the shipped visual + token authorities.** The meter and any new bar use the existing `budget-bar` /
  `projectContextHorizon` colour authority and design tokens (no raw colours) — as the shipped chat meter
  already does (§H.1). The inspector reuses the sources-pane drawer chrome.
- **The inspector is a read of the §J projection** — it shows what the assembly projection computes, so it can
  never disagree with the meter (one derived source; §J's projection-not-a-second-authority rule reaches the
  FE: the meter and inspector are two *reads* of one projection, never two computations).
- **Viewport reality.** Everything stays compact-by-default: the meter is one bar; the breakdown is on-demand;
  the workspace is a drawer; source controls ride existing cards. No new always-on transcript chrome (the
  §D.4 surface-convergence constraint holds).

## §K.4 — User-visible scope discipline

Only the meter (shipped) and the per-turn/per-source controls are *always visible*; the attribution breakdown,
the inspector, and the assisted-gate are **on-demand or threshold-triggered** — they cost the user nothing until
needed. Per §J's altitude rule, the single-bar meter is the degenerate visible form today; the breakdown +
inspector appear only when a whole-prompt feature is built. The frontend therefore grows **one new surface (the
inspector drawer)** and **one new inline control family (per-source exclude/pin on existing cards)**; everything
else is progressive disclosure on surfaces that already exist. Nothing here is built now — this records the
correct user-facing shape so a future build conforms (one inspector, controls inline, assisted-gate mirrors the
agent rail, all reading the one §J projection).

---

# §L — Research-currency check + two design refinements (2026-06-20, agent)

> A *broad* re-survey was judged unnecessary (§E + §I were < 36 h old; the global frontier does not move in a
> day, and the fast-moving algorithmic axes — context-compression, memory architectures — are deliberately out
> of this UX-level scope). A **narrow** pass on the two questions that bear on the *newer* §J/§K layers (the
> inspector's honesty; whether a context-GUI shipped since §I) returned two findings that **refine the design**.

## §L.1 — Position is a first-class signal, not just volume ("lost in the middle")

Still-unsolved and structural in 2026: models attend to the **start and end** of the context and systematically
under-use the **middle** (>30% accuracy drop for middle-positioned info; "no production model has fully
eliminated position bias"). [Lost-in-the-middle overview](https://atlan.com/know/llm/lost-in-the-middle-problem/) ·
[2026 context management](https://zylos.ai/research/2026-01-19-llm-context-management/) The shipped meter and the
§E.4/§G framing are about **volume** (context *rot* — how full); this is its **positional** sibling — *where*
content sits changes whether it is effectively used.

**Refinement (a READ over the §J projection — no new structure).** The §J prompt-assembly projection is already
an *ordered* list of segments, so **position is derivable for free**. So:
- **The inspector should surface position, not just presence/tokens** — e.g. mark mid-context segments as
  "weak position." This also resolves the inspector's *honesty* question cleanly: it shows what the AI is
  **given** (input), and position is the visible caveat that *given ≠ uniformly used* (so the inspector never
  implies the AI "used" everything shown — the §I.5/§K honesty guard, now concrete).
- **The assisted-gate gains a positional remedy:** beyond "drop/compact" it can suggest **pin/move** an
  important but middle-buried source to a strong position — a research-grounded action the volume-only framing
  missed, composing the §I "pin" idea with the position read.

Scope: this is *recognized, not built* — it changes *what the inspector reads from the projection*, not the
projection or any store. Build it with the inspector, if at all.

## §L.2 — Generic context-transparency is now an emerging 2026 *standard* (updates §I's "unbuilt")

Context-window transparency has crossed into convention: chat UI kits now ship **token meters with hover
popovers showing a category breakdown** ("15K/128K"), and `assistant-ui` has a dedicated **"Context Display"**
component. [assistant-ui Context Display](https://www.assistant-ui.com/docs/ui/context-display) ·
[AI chat UI best practices 2026](https://thefrontkit.com/blogs/ai-chat-ui-best-practices) This **updates §I.2/§I.4**:
the *generic* meter + category-breakdown layer is **no longer unbuilt — it is becoming table-stakes**.

**Refinement (validates §K's shape; sharpens the differentiation).** §K's "compact bar → hover breakdown →
inspector drawer" is exactly the emerging convention — so it should **conform** (use the standard
bar+hover-popover idiom), not present as novel. The genuine, still-unbuilt differentiation narrows to: **(a) the
retrieved-document / per-source axis** (per-source token cost + interactive exclude/pin in a document-grounded
app — confirmed still absent at the end-user tier), and **(b) the position signal** (§L.1). The commodity layer
(meter + breakdown) follows the convention; we differentiate on documents + position. `assistant-ui`'s
Context Display is the reference idiom to conform to for the commodity layer.

**Net:** no design *reversal* — the §J projection + §K surfaces stand. Two refinements: surface **position**
(not just volume), and **conform** the commodity meter/breakdown to the now-standard idiom while keeping the
differentiation on the document + position axes. Both are reads over the existing projection; no new structure.

---

# §M — Confidence-building findings: backbone verified + three corrections (2026-06-20, agent)

> Read-only source verification of the load-bearing §I/§J/§K claims (theorized largely from unverified
> subagents). The §J backbone **holds**; three things are **corrected** below. No feature code; the model
> runtime stays unavailable (live behaviour uncheckable — code is the ceiling).

**VERIFIED — the §J post-hoc whole-prompt projection backbone holds (no re-retrieval, no extra persistence).**
- Retrieved sources **are persisted on the assistant turn with content + ids + position**: `RAGContext` stashes
  citations → `RAGDoneEnricher` projects them onto the done-payload → `ConversationEngine.persistedAssistant`
  writes `citations` (+ `calibration`) onto the stored turn. Each `ContextCitation` carries `parentDocId` +
  `chunkIndex` + **`excerpt` (the text)** + `startChar/endChar`/`startLine/endLine` — enough to estimate tokens,
  show in an inspector, and order by position, **without re-running retrieval.** (Retires uncertainties 1 + 6.)
- `loadEffectiveContext` returns **conversation + summary only** (NOT the injected docs — those are added by the
  injector chain at dispatch). So the retrieved phase exists post-hoc **only on the persisted turn's citations.**
- Per-source **exclude** is confirmed a **cheap ephemeral / moderate persistent filter on the `RAGContext`
  injector seam** (filter the `kept` citations by an `excludedSourceIds` set before they join the "Documents:…"
  message), with the stable `(parentDocId, chunkIndex)` id verified end-to-end (retrieval → citation → SSE → FE
  source-card). Persistent set mirrors `excludedMessageIds` in `meta.json`. Architectural boundary holds (the
  injector seam, separate from `loadEffectiveContext`). (Retires uncertainty 5; §J.3 stands.)

**CORRECTION 1 — the workspace is anchored on the LAST COMPLETED TURN (post-hoc), and there are TWO inspector
tiers** (resolves the §I.4↔§K temporal inconsistency, uncertainties 2 + 4). The §I.4 "cheap one-endpoint
inspector" over `loadEffectiveContext` shows **only the conversation phase** — it is **not** the whole-prompt
inspector. The **whole-prompt** inspector (§K) is the §J projection reconstructed from the **last turn**: its
conversation phase + its **persisted citations** (retrieved phase) + the real `promptTokens`. So §K's "what the
assistant sees **now**" should read "what the **last turn** saw" (post-hoc) — a *next-turn* preview would need
re-running retrieval (the stateful/expensive path §I.2 already flagged). Two honest tiers: a cheap
conversation-only peek (`loadEffectiveContext`) and the whole-prompt attributed view (last-turn projection).

**CORRECTION 2 — token attribution is ESTIMATED; only the aggregate is real** (refines §I.2/§J.2/§L.2,
uncertainty 3). `TokenEstimation` is a deliberately *over-estimating* heuristic; per-segment estimates will **not
sum to** the real `promptTokens`. So the breakdown must present **the meter total as authoritative (real
`promptTokens`) and the per-phase split as estimates** — scaled to the real total and/or labelled "≈" — never
false per-segment precision. (The §L.1 position signal is exact — it's ordering, not tokens — so it is
unaffected.)

**CORRECTION 3 — the chat assisted-gate conforms to the agent gate's VISUAL/threshold, NOT its hold/control
contract** (refines §K.3, uncertainty 7). Reusable: `projectContextHorizon`, the `budget-bar` visual, the
fullness→colour authority, and the "Context filling up: N of M" grammar. **Not** reusable: the agent gate is a
*synchronous run-hold* (the run is parked, resolved via `dispatchRunControl` → `context-decision`); chat turns
are fire-and-forget and the occupancy is known only *after* `onDone`. So the chat assisted-gate is a **post-turn
retrospective sibling** (when the meter hits red after a turn: "[hide suggested turns] [compact up to here]
[dismiss]"), reusing the agent gate's *look*, not its *parked-decision plumbing*. It is more bespoke than "mirror
the agent gate" implied, and its suggestion quality rests on an importance heuristic that stays deferred.

## §M.1 — Critical confidence rating for the remaining work

**Overall: 7.5 / 10** for the near-term whole-prompt workspace; lower for the assisted layer. By layer:

- **§J prompt-assembly projection (the backbone): 8.5.** Source-verified end-to-end — citations persisted with
  content+ids+position, `loadEffectiveContext` conversation-only, no re-retrieval/extra storage. Residual: the
  honest token presentation (Correction 2) and that it is *new* (no existing trace to extend — but conformant to
  `SearchTrace`).
- **§K surfaces — meter attribution + whole-prompt inspector: 7.5.** Data path verified; the temporal model is
  now settled (last-turn, post-hoc). Residual: FE execution in the cramped viewport (the drawer — low risk, the
  `jf-sources-pane` drawer proves the pattern), and the standing **env cap** (no live model → attribution/visual
  validated only via DOM-injection + unit tests, as in §H.1).
- **Retrieved-source exclude/pin (the differentiator): 8.** Clean filter seam, stable id, clear boundary,
  cheap-ephemeral/moderate-persistent confirmed. Residual: the persistent set + the per-source FE control, and
  live behaviour unverifiable (model).
- **Assisted-gate: 6.5.** Visual/threshold reuse confirmed, but it is a *new post-turn affordance* (Correction
  3), not a mirror, and "suggest what to drop" quality depends on a still-deferred importance heuristic.

The number sits below 9 chiefly because of (a) the env cap — no live model means the highest-value verification
tier (does excluding a source change the real prompt? does attribution track real tokens?) stays unrun, code-only
(unchanged from §H.1); and (b) the assisted-gate being more bespoke than theorized. The *mechanism* uncertainties
that opened this pass are now closed — the backbone holds and the two internal inconsistencies are resolved.

---

# §N — As-built: the whole-prompt workspace (Phases 1-3 shipped; Phase 4 deferred with a correction) (2026-06-20, agent)

Implemented the §I/§J/§K/§L slice on branch `worktree-610-whole-prompt` (off the §M-corrected design),
low-risk-first. **Phases 1-3 shipped** (faithful, unit-tested, gates green); **Phase 4 (retrieved-source
exclude) deferred** on a verified mechanism correction (below).

## Shipped

- **§I.2 exclude aggregate + bulk include-all** (`UnifiedChatView.renderExcludedSummary` + `includeAll`). A
  "N turns hidden from context · Include all" row above the composer, shown when any turn is individually
  hidden; bulk-undo reuses the shipped `setMessageExcluded`. (The earlier "hide all above the floor" idea was
  dropped — above-floor turns are already out of context, so it was redundant.) +1 unit test.
- **§I.2 meter attribution breakdown.** Backend: `ConversationEngine` estimates the three phases
  (`TokenEstimation` over `systemPrompt` / the effective-context conversation / the injected RAG messages) and
  rides a `contextBreakdown {system,conversation,retrieved}` map on the **done payload** next to the real
  `promptTokens` — a live reporting field, **not persisted** (§M Correction 2). FE: captured in `onDone`; the
  meter reveals the split on hover/focus (compact bar by default) + carries it in the `title` for SR/keyboard;
  framed honestly (bar total = real occupancy, split labelled "estimated"). +1 unit test.
- **§K/§J context inspector drawer** (`<jf-context-inspector-pane>` + `contextInspectorDrawer` store). Shows the
  whole prompt the **last completed turn** saw (the §M Correction 1 temporal anchor): system phase, in-context
  conversation turns (+ standing summary), and the last turn's retrieved sources — each with a §L.1 **position
  marker** (start/end attend well; the middle is flagged "weak") over the combined prompt order. Reuses the
  `SourcesPane` drawer chrome (`TransientController` `right-drawer` layer → single-open arbitration vs the
  sources drawer). The meter label is the trigger. +2 unit tests. Gates: controls-a11y, presentation-purity,
  style-literal-ratchet all green.

## Phase 4 (retrieved-source exclude) — DEFERRED, and why (a §M Probe-C correction)

**§M Probe C was wrong about the mechanism.** It claimed source-exclude is a cheap post-retrieval filter on the
`RAGContext` citations. Source verification during implementation showed otherwise:

- The text the model sees is the **worker-built `context` string** (`RagContextOps` joins full chunk content via
  `budgeter.appendSection`), **not** the citations. The `ContextCitation.excerpt` is a **240-char truncated
  snippet** (`clampExcerptToWordBoundary(content, 240)`) — so rebuilding `context` from kept excerpts would feed
  the model *truncated* chunks (a quality regression), and the `ContextResult.sections` (full content) can't be
  unambiguously matched to a `parentDocId:chunkIndex` in multi-doc retrieval.
- RAG **re-retrieves fresh every turn**, so any client-side post-hoc filter is a patch; the only faithful place
  to remove a source is **at retrieval (the worker)** — the one site with `parentDocId` + `chunkIndex` + full
  content together.

**The faithful design (for when it's picked up):** add `repeated string excluded_source_ids` to the RAG retrieve
request proto; `RagContextOps` filters chunks by `(parentDocId, chunkIndex)` **before** building context +
citations (prefer **pre-rank** exclusion — drop from the candidate set so the top-K still fills with non-excluded
chunks — over post-rank, which just yields fewer chunks); thread it `RemoteDocumentService` → `RetrieveContextParams`
→ `RAGContext.inject` (reads the set the engine seeds on `ctx` from a new `ConversationStore.excludedSourceIds`,
mirroring `excludedMessageIds` in `meta.json`); plus the `ChatController` toggle endpoint + the FE per-source
control. **ID caveat:** `parentDocId` is a Windows path containing colons, so the id must be a structured
`(docId, chunkIndex)` pair end-to-end, not a `"docId:chunkIndex"` string split on `:`.

**Why deferred, not shipped (confidence ~4/10 to do it *correctly* now):** it is the highest-blast-radius piece
(core retrieval — a filter bug degrades *all* retrieval, not just the exclude case); and the model runtime is
unavailable (cuda12), so it **cannot be live-validated** — and retrieval changes specifically require an eval +
the Q-010 relevance ratchet (the `search-engine-hint` codifies this), neither of which runs without the backend.
Shipping a core-retrieval change unit-test-only would violate the engine's own quality discipline, and a
client-side shortcut would be a symptom-patch. So Phase 4 waits for a model-available session; the store-half
(`excludedSourceIds` in `meta.json`) is trivial to re-add then.

## §N.1 — Live validation (worktree FE against the running backend, model offline)

Validated Phases 1-3 in a real browser via `npx vite` from the worktree's `ui-web` pinned at the running
backend's API port (the tempdoc 618 §7 "serve worktree FE" approach — read-only, no dev-stack takeover; the
backend was idle-held by another session). The model was offline (cuda12), so the §H.1 DOM-injection approach
drove the components with real-shaped state (a thread with turns + retrieved sources + a `contextBreakdown`):

- **Phase 1** — the "N turns hidden from context · Include all" aggregate renders above the composer (singular/
  plural correct).
- **Phase 2** — the meter renders "Context 25% · 1024 / 4096 tokens"; the hover breakdown reads
  "system ~120 · conversation ~600 · documents ~300 (estimated)".
- **Phase 3** — the inspector opens as a **right drawer** with the system/conversation/documents phases, token
  estimates, the real-total footer, and the §L.1 "mid-context · the model attends to this least" marker on
  exactly the middle turns (ends unmarked). The `updated()` push keeps it live: toggling excludes re-rendered
  the open drawer with the now-excluded turns dropped.

**Integration bug caught + fixed by the live pass (not by unit tests):** the inspector first rendered *inline*
in the chat flow, because the `OverlayHost` owns right-drawer positioning via `slot="right-drawer"` (the
`TransientController` only arbitrates open/close) and a pane mounted inside `UnifiedChatView` never receives
that slot. Fixed the design-consistent way (mirrors `SourcesPane`): the pane is mounted at the shell's
right-drawer slot and fed via the `contextInspectorDrawer` store (`UnifiedChatView` pushes
`buildInspectorView()`). This is the `static-green ≠ live-working` lesson in miniature — happy-dom couldn't see
the missing slot.

## §O — Phase-4 confidence findings: the env cap doesn't bind, and a cleaner design (2026-06-20, agent)

A read-only confidence pass on the deferred Phase 4 (worker-side retrieved-source exclude) overturned the
assumption that drove its ~4/10 rating, and surfaced a cleaner mechanism. Source-verified; one offline test
actually run.

**FINDING 1 (the rating-mover) — Phase 4 IS validatable offline; the model cap does NOT bind it.** Retrieval is
Lucene + ONNX; only *answer generation* needs llama-server (the unavailable cuda12 variant). Verified by
*running* `:modules:worker-services:test --tests *GrpcSearchServiceRetrieveContextTest` +
`:modules:adapters-lucene:test --tests *ChunkSearchIntegrationTest` — **BUILD SUCCESSFUL in 13s, no model, no
dev stack**. These build a test Lucene index, retrieve chunks, and assert on the retrieved set. So an
"exclude chunk X" change gets a real regression test (index chunks → retrieve with an exclusion → assert X
absent, others present) — plus `jseval run --modes hybrid --start-backend` (no `--llm`) gives an nDCG@10 delta
and the Q-010 relevance-gate. My ~4/10 was dominated by "can't validate" — which is **false**.

**FINDING 2 (cleaner mechanism — supersedes §N's "filter chunks before building context")** — `RagContextOps`
**over-retrieves** a candidate set (`overRetrieveK = min(topK·factor, 30)`) and already takes a `chunkFilter`
Lucene query (`RagContextOps.java:478`, via `QueryFilterBuilder`). Filtering at the **search query** (a
`MUST_NOT` over the excluded `(parentDocId, chunkIndex)` pairs) is strictly better than a post-retrieval filter:
(a) over-retrieval **refills top-K** from the remaining candidates (not "fewer chunks"), and (b) **every
downstream signal computes from the already-filtered set**, so the desync hazard I flagged in §N — stale
`chunksConsidered` (line 524), `bestChunkScore`/`scoreGap` quality signals, `sections` indices, the token
budgeter — simply **does not arise**. `SearchHit.fields()` carries both `PARENT_DOC_ID` + `CHUNK_INDEX`
throughout, so the match is a structured pair (no colon-split trap).

**FINDING 3 (plumbing is clean)** — ONE proto request message (`RetrieveContextRequest`, indexing.proto:363);
add `repeated string excluded_source_ids = 24;` (proto3 empty default = backward-compatible no-op; **no
byte-pin/conformance tests** → zero regen cost beyond `gradlew build`). Retrieval is **per-turn, not cached**
(injectors run once per dispatch). The param threads `RAGContext.inject` → `RetrieveContextParams` (new field) →
`RemoteDocumentService` → `SearchRpcOps` gRPC builder (`.addAllExcludedSourceIds`) — the rich-params overload
already threads new `RetrieveContextParams` fields; only the positional overload needs a new arg.

**Revised confidence for Phase 4: 7/10 (was 4).** Up because the dominant risk (unvalidatable core-retrieval
change) is gone — offline retrieval tests run in seconds — and the mechanism is now a clean pre-search
query-filter that avoids the invariant desync. Residual risks (the −3): (a) `QueryFilterBuilder`'s
`buildChunkFilterQuery` today expresses *metadata* filters; a chunk-level `(parentDocId, chunkIndex)` `MUST_NOT`
is a small **extension**, not a pure reuse — to verify it composes cleanly with the existing chunk filter; (b)
multi-module breadth (proto + worker + app-api + app-services + store + FE) is real surface area even though
each hop is mapped; (c) excluding more chunks than the over-retrieve buffer (>~30−topK) still yields fewer
chunks — acceptable, but worth a UX note; (d) the FE half + full end-to-end (control → store → engine → worker
→ prompt) still wants the dev stack for a live pass, though the *core* behaviour is offline-testable and the FE
is DOM-injection-validatable like Phases 1-3.

---

# §P — As-built: retrieved-source exclude (Phase 4), with a live-validation bug caught (2026-06-20, agent)

Phase 4 (§J.3) is implemented end-to-end on `worktree-610-whole-prompt` and **validated live, full-chain**.
The faithful worker-side design from §O held; live validation caught one real bug that compile + unit tests
could not.

## Shipped (Phases A–D)

- **Worker filter (A):** proto `ChunkRef {parent_doc_id, chunk_index}` + `RetrieveContextRequest.excluded_chunks`;
  `ChunkExclusionQuery.compose` builds a pre-search `MUST_NOT` over the excluded `(parentDocId, chunkIndex)`
  pairs (`TermQuery` + `LongPoint.newExactQuery`, `MatchAllDocs` positive base) composed with the existing
  chunk filter in `RagContextOps`. Offline regression test in `GrpcSearchServiceRetrieveContextTest`.
- **Param thread (B):** `RetrieveContextParams.excludedSourceIds` (unit-separator-joined ids) →
  `SearchRpcOps` splits each to a `ChunkRef`. `ConversationEngine` seeds the set onto `ctx` from the store;
  `RAGContext` reads `ATTR_EXCLUDED_SOURCES` and threads both retrieval paths (now via the rich params path,
  which additionally surfaces CRAG quality on the scoped path — a latent additive fix).
- **Store + API (C):** `ConversationStore.excludeSource/excludedSourceIds` over `meta.json`;
  `POST /api/chat/conversations/{sessionId}/sources/exclude` (sourceId in the body); `excludedSourceIds` on
  history load. Store round-trip test.
- **FE control (D):** `setSourceExcluded` + a per-source hide (×) / restore (↺) toggle on each answer source
  chip, dimmed when hidden, restored on reload. Unit test. (The docked `SourcesPane` rail control is a noted
  follow-up — it needs `sessionId` wiring.)

## The live-validation bug (static-green ≠ live-working)

Standing up my own dev stack from the worktree and driving a real retrieval revealed that **`core.rag-ask`
(the documents affordance) is EPHEMERAL**, so the engine set `sessionId=null` and my original store-seed —
guarded on `sessionId != null` — **never fired for the primary RAG path**. The exclusion silently did nothing
there. The FE stamps `conversationId = this.sessionId` on every dispatch, and for EPHEMERAL recordsToThread
shapes that is the engine's `threadId`; the fix seeds from `excludeKey = sessionId != null ? sessionId :
threadId`. A `SubstrateDrivenEngineTest` regression guard now bites this (the EPHEMERAL path's null sessionId
would leave the seed null without the fix). Compile + all unit tests had been green *before* the fix — the bug
was only reachable by running it.

## Live full-chain proof (model offline; retrieval runs before the AI_OFFLINE check)

Against my worktree stack with the 3 explanation docs indexed: a real `POST /api/chat/dispatch` (rag-ask)
retrieved `01-system-overview.md` chunk **2** as the top source. Excluding exactly that `(parentDocId,
chunkIndex)` via the endpoint and re-dispatching dropped chunk 2 from retrieval and **over-retrieval refilled
the slot with chunk 4** — precisely the §O design (pre-search filter + top-K refill, no signal desync). This
exercises the whole chain live: endpoint → store (under conversationId) → engine seed (threadId) → RAGContext
→ ChunkRef → worker `MUST_NOT` → real retrieval. Reproducible via the documented dispatch+exclude+re-dispatch
sequence.

## Env cap + scope notes

- **No LLM:** no model variant is installed (cuda12/default absent), so the LLM *answer* generation tier stays
  unrun — but it is irrelevant to the exclusion behaviour, which is validated above. The FE source-chip
  *render* is unit-tested + code-reviewed (the documents affordance is FE-gated off when the model is offline,
  so a live chip render needs a model).
- **Full-doc fallback:** exclusion is chunk-level; the whole-document fallback path (no chunks) does not apply
  it — acceptable, it is a different retrieval mode.
- **Over-retrieve buffer:** excluding more than ~`30 − topK` chunks yields fewer chunks (the buffer is finite)
  — acceptable; a UX note for a future "too many hidden" hint.

## §Q — Review-fix pass: fallback exclusion + the rail control (2026-06-20, agent)

A critical review of the shipped Phase 4 found two substantive issues (one correctness, one consistency)
plus one confirmed-intended design point; both fixed + validated.

**Fix 1 (correctness) — the whole-doc fallback no longer defeats the exclusion.** The exclusion is a
chunk-level pre-search MUST_NOT. When ALL retrieved chunks of a *scoped* doc were hidden, chunk search
returned 0 and the WHOLE-DOC fallback re-injected that doc's full text — silently. The fallback fires at the
**worker** (`buildFallbackWithVirtualChunks` returns virtual chunks → `usedChunks=true`, so the Head's
fallback never ran). Fixed at BOTH fallback sites (needed together — filtering only the worker would empty
its result and re-trigger the Head's `fetchBatchFallback`): the worker drops excluded parent docs from
`effectiveDocIds` (`ChunkExclusionQuery.dropExcludedParents`) and the Head filters `fetchBatchFallback`
(`RAGContext.dropExcludedParentDocs`). `parentDocId == docId`, so the match is exact. **Offline regression
test:** excluding all chunks of a scoped doc keeps its marker out of the assembled context.

**Fix 2 (consistency) — the docked `SourcesPane` rail now offers the same hide control as the inline chips.**
Introduced one FE source of truth, `state/excludedSources.ts` (the hidden-source set + `sourceExcludeKey` +
`toggleExcludedSource`); `UnifiedChatView` dropped its local Set for the store, and `SourcesPane` reads the
active conversation id + the store to render the same ×/↺ toggle + dim on each source row. **Validated live**
(model offline; dynamic-imported the live store + agent controller via Vite): hiding a source via the store
renders it struck-through with the ↺ restore control in the rail, and clearing reactively un-dims it — the two
views share one store. Unit tests + controls-a11y/presentation-purity/style gates pass.

**Confirmed NOT a defect:** exclusion is per-chunk and over-retrieval refills with the next-best chunk
(possibly the same doc) — the deliberate §O design, matching the per-passage chip UI. Granularity unchanged.

## §R — Roadmap decision: defer all but auto-compaction (2026-06-20, user direction)

The remaining 610 roadmap items are resolved per user direction:

- **Deferred for good (not building):** per-chunk re-ranking (the §I.8 item 5 "let users tune the relevance
  order" idea — touches the scoring/reranker, design unworked, low marginal value), the **assisted
  "suggest what to drop" gate** (§M Correction 3 — bespoke + needs an importance heuristic), and the
  **predictive "next-turn" meter** (§M Correction 1 — weak demand; the shipped meter is last-turn by design).
- **The one live next item — auto-compaction at a threshold** (the §I.8 / U4 item). Chosen posture:
  **silent auto-compact + a Restore-able notice.** When the context meter crosses ~85% fullness after a turn,
  automatically run the SHIPPED `compactContext` (summarize older turns → set the floor, keeping the last N
  recent turns), then surface a one-line *"Compacted earlier turns to fit the window — Restore"* notice via
  the existing message channel. It is a **trigger on already-shipped machinery** (`compactContext` + the
  context floor + Restore + the §E.4 meter), not new mechanism. Care points for the build: debounce (don't
  re-fire every turn near the line), which turn becomes the floor, and idempotency with manual compaction.
  **Env-cap (same as Phase 4):** the trigger + notice + floor-selection + debounce are model-free and live-
  validatable; the LLM *summarize* step is unit/offline-tested (no model variant installed here). **Not yet
  implemented** — recorded as the next planned work.
