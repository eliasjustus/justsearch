---
title: "596 — Operability authority, deeper: the AVAILABILITY-PROJECTION half (typed availability + reachable reason). A 558/594/595-style depth round on tempdoc 559 Part II Authority V (Operability). The deepened finding (§9): 559 built jf-control as the operability authority's OPERABLE+NAMED half — every control is keyboard-operable and projects its NAME from one declaration (present()) — but there is NO AVAILABILITY half, so a control's availability is a bare boolean (`?disabled` / 543 Action.enabled) and the 'why' is hand-derived inline at every site onto a channel that fails in the unavailable state: a `title` on a `?disabled` button (browser suppresses the tooltip), a `compose()` that returns `false` and is discarded by an un-disabled button, or a native-disabled click swallowed with no signal. The correct long-term structure is the symmetric completion of the operability authority: availability becomes a typed projection (`available | unavailable{reason, transient}`) resolved by ONE projector from the observed-state authority (composing 594's projected facts + 595's connection phase for the reason), which jf-control OWNS rendering — a reachable reason (aria-disabled, NOT native title) and a non-silent blocked activation through the one message channel. Surfaced by the 593 walkthrough (§9 disabled tabs with no reachable why, §8c 'Ask AI' silent no-op offline, obs #420 swallowed reload click)."
type: tempdocs
status: "IMPLEMENTED & MERGED to main (merge 1cfcd504e, 2026-06-17) — typed availability on jf-control + reachable WCAG-1.4.13 reason tooltip + one reason-and-remedy authority (realized via readinessNotice.reasonFor post-595, §15.3) + the controls-a11y gate ratchet; full ui-web suite (3095) + gates + production build green. The design-theory passes below (§8–§17) are dated history. ── design-theory (correct end-state stated; feasibility/phasing deliberately out of scope per the 557/594/595 genre). User 2026-06-16: keep as design doc, do not implement yet. Two passes complete: (§8) investigation + critical analysis — all three faces verified verbatim against `main`, a fourth face added (8.2 availability-boolean fork: Documents tab gates on `rag`, Ask-AI on `chat`), and two §4 moves corrected (the 543 substrate is declaration-only — no engine renders these affordances — so the structural home is `jf-control`, the 559 Authority V operability primitive; Move 2's feedback must route through the gate-enforced `emitEphemeralToast`; §6 Q2 resolved via `aria-disabled` per W3C APG, closing 1.1+1.3 with one mechanism). (§9) long-term design theorization — the AVAILABILITY half of the operability authority: availability as a typed projection (`available | unavailable{reason, transient}`) resolved by ONE projector from the observed-state substrate (composing 594's facts + 595's phase), OWNED by jf-control (reachable reason + non-silent block), with the 543 declaration's bare `enabled` boolean subsumed into the availability spec. The 594/595 boundary drawn (§9.6: 596 is the operability BINDING, not a leaf fact nor a system verdict). (§10) pre-implementation confidence pass (read-only): REFUTED §9.3's central leverage claim — the `controls-a11y` gate accepts native `<button>` as coequal, so it does NOT force `jf-control` and the structural close needs a load-bearing Gate dimension, not just the primitive (C1); sharpened the typed value into `available | blocked (hard, intent gate, stays native disabled) | unavailable{reason} (soft, aria-disabled)` because 9 consumers incl. 4 confirmation gates rely on native disabled (C2); held a scope line against `Action.enabled` (applicability/hide ≠ availability/show+explain, C3); and confirmed 596 is DECOUPLED from the unbuilt 594/595 — all reason inputs exist on `aiStateStore` today (C4). Confidence for eventual impl: 6/10. (§11) browser-verified user-facing design against the LIVE degraded stack: faces 1.1/1.2 confirmed in the running UI (disabled tab is NOT focusable → reason sealed in; 'Ask AI' live-looking while backend unresponsive), and the KEY finding — the app ALREADY ships the correct idiom (`state/readinessNotice.ts`: projects observed-state → headline + worded cause from the backend's CLOSED reason-code vocab + a REMEDY button), rendered on the search surface but NOT the chat affordance bar. So the reason should be projected from the same `ReadinessView`/`reasonCodes` authority (not a hand-typed `title`), delivered inline+reachable+remedy-bearing (not a tooltip), and the standalone win is rendering `readinessNotice` on the chat surface. (§12) git-calibrated effort estimate: ~3–5 agent sessions / ~1–2 calendar days, variance dominated by the aria-disabled shared-primitive blast radius (4 confirmation gates) + its live-verify loop; floor = render `readinessNotice` on the chat surface (~1 session, independently shippable). (§14) confidence-building pass #2 (read-only + probes): resolved the six remaining implementation uncertainties — U1 the 595 coupling is a STABLE SEAM (595 layers, doesn't restructure; `projectAvailability` reads raw fields today, swaps to 595's verdict later with no signature change); U2 the aria-disabled blast radius is REFUTED/additive (all ~10 `disabled` consumers rely solely on native disabled, so keep `disabled`=hard-block default + add an opt-in soft mode → zero existing-site edits); U3 the gate reaches face 1.1 (title-on-disabled) but NOT face 1.2 (discarded `compose()` boolean is data-flow, beyond the grep ceiling → face 1.2 is primitive-closed, not gate-closed); U4 readinessNotice-on-chat is cheap (UnifiedChatView already subscribes to the store); U5 the loading/null window is real (~5s poll cycle, 15s grace — `transient` justified); U6 jf-control active-state parity is feasible via `::part(control)` (aria-pressed needs a small add). Rating raised 6/10 → 8/10; effort revised ~3–5 → ~2–3 sessions (blast radius shrank). IMPLEMENTED 2026-06-17 (worktree `596-availability`, unmerged): §15 as-built (typed availability + jf-control soft mode + affordance bar/Ask-AI routing + chat banner + gate ratchet) and §15.1 (the visible reason-surface tooltip); full ui-web suite + gates green + live-browser-verified (genuinely-offline backend). (§16) forward-research survey of what the substrate enables (polish/simplify/extend/new-features, sources cited). (§17) scope-matched correct-design for the REMAINING work — deliberately NOT the §16 menu: exactly two items the tempdoc's own thesis requires, both extending existing designs — (A) dissolve the reason-vocabulary fork the implementation introduced (`availability.ts REASON_WORDING` drifted from `readinessNotice.CAUSE_ROWS`: 'AI is offline' vs 'The local AI model is offline', and drops the remedy) into ONE shared reason-and-remedy authority with two scoped projections (window banner = system scope, `projectAvailability` = control scope), the same shape 594 shipped and the seam 595 §10.5 converges on; (B) make the visible reason-surface a genuinely REACHABLE tooltip (WCAG 1.4.13 dismissable+hoverable+collision-safe, in jf-control, reusing the Popover API). Everything else in §16 is explicitly out-of-scope speculation. If you read one section, read §9, then §17."
created: 2026-06-16
author: agent
extends: tempdoc 559 Part II Authority V (operability — operable controls + accessible names; the `controls-a11y` gate) and tempdoc 543 (the Action+Effect affordance substrate). THIS DOC EXTENDS them; it does not re-derive the operability authority.
category: frontend / ux / design-theory / single-authority / presentation / operability
related:
  - tempdoc 557 (presentation = three single-authority projections: Display / Observed-state / Theme; the prevention ladder Collapse > Unrepresentable > Generate > Gate). The lineage; operability is the behavioral sibling 559 Part II added.
  - tempdoc 594 (Display authority depth — factual content of chips) and tempdoc 595 (Observed-state authority depth — the health verdict + transition state). THE TWO SIBLINGS: 594 = a fact the FE can't show right; 595 = a verdict the FE computes divergently; THIS = a reason the FE knows but can't deliver. Same 558 shape, three different authorities.
  - tempdoc 559 (the presentation projection, completed — Part II Authority V operability/`controls-a11y`; the grep-gate ceiling on Lit-template scanning). OPEN. The home authority.
  - tempdoc 543 (Action+Effect substrate — the most generalized affordance declaration; the seam where 'availability' should be a typed state, not a bare `?disabled`).
  - tempdoc 526 (typed compose/dispatch — `compose()` is the §4.5 helper whose `false`-on-unavailable return is discarded at the Ask-AI call site).
  - tempdoc 593 (UX walkthrough — §9 disabled mode-tabs with no reachable why; §8c 'Ask AI' no feedback offline; §1 rail icons no tooltips). CLOSED.
  - observations.md #420 (the Agent affordance tab `?disabled=${!aiReady}`; first post-reload click swallowed while aiState is null). The third evidenced face.
  - tempdoc 504 (systematic UX audit taxonomy — D7 'no-feedback-on-action' / 'dead-affordance' adjacency).
  - CLAUDE.md `structural-defects-no-repeat` (one documented silent bug proves the class; three evidenced faces here, two provable in code — bar cleared).
---

# 596 — Operability authority, deeper: the availability-projection half (typed availability + reachable reason)

> **Reading order.** §1–§7 are the original move-level proposal (the three-face entry point). §8 is the
> investigation + critical analysis against `main`. **§9 is the deepened design-theory centerpiece —
> the correct long-term structure** (the availability-projection half of the operability authority);
> §4 is the move-level approximation of it. If you read one section, read §9.

> **What this document is.** A *design theory* for one place the JustSearch frontend's **operability**
> authority (the behavioral authority 559 Part II added: every activation handler on a keyboard-operable
> element with an accessible name) does not yet reach — *the reason an affordance is unavailable*. It is
> **not** a new framework. It inherits 557's invariant + ladder and 559's operability authority, and adds
> depth where the 593 walkthrough proved an unavailable affordance gives the user no reachable "why."

> **The meta-finding (why this doc exists).** This is the third sibling of the 594/595 pair, on the
> third authority. 594: the **Display** projection can't show a chip's factual content. 595: the
> **Observed-state** projection computes the health verdict divergently. **596: the operability authority
> models availability as a bare boolean and delivers the "why" through a channel that fails in the
> unavailable state — so the FE *knows* why a control is unavailable and never tells the user.** The
> recurring 557-line pattern holds: every independent audit finds fresh user-visible drift in whatever
> sub-layer still lacks a single authority. Here it's *affordance unavailability*.

---

## 1. The defects (three faces of one root)

### 1.1 The reason is authored — onto a channel the browser suppresses (PROVABLE in code)

The mode tabs (`UnifiedChatView.ts:2021–2044`) are plain `<button>`s that carry **both** `?disabled`
**and** a `title` containing the real reason:

```ts
<button ?disabled=${this.isStreaming || !this.aiState?.capabilities?.rag}
        @click=${() => this.toggleAffordance('documents')}
        title=${this.aiState?.capabilities?.rag
          ? 'Ground answers in your indexed documents'
          : this.docCount === 0 ? 'No documents indexed' : 'AI offline'}>
  Documents
</button>
```

The author **did** write the why (`'No documents indexed'`, `'AI offline'`). But a `disabled` HTML
`<button>` **does not fire pointer/hover events**, so the browser **never renders the native `title`
tooltip while the button is disabled**. The explanation is in the source and **unreachable in exactly
the state it describes.** This is 593 §9 verbatim: *"hovering it surfaced no tooltip explaining why."*
Same on the Structured (`:2033`) and Agent (`:2041`) tabs. No live stack needed — it's the
DOM-semantics of `title`-on-`disabled`.

### 1.2 The gate returns `false` — and the call site throws it away (PROVABLE in code)

The opposite failure on the same root. The "Ask AI" button (`SearchSurface.ts:1360–1363`) is a plain
button that is **never disabled**:

```ts
<button class="copy-btn ask-ai-btn" title="Ask AI about these results"
        @click=${() => this.handleAskAi()}>Ask AI</button>
```

`handleAskAi` (`:822–829`) unconditionally calls `compose({ operation: 'core.ask', affordance:
'documents', … })`. And `compose()` **already knows** AI is unavailable — its own contract
(`compose.ts:16–17`): *"compose() gates on AI capability and returns `false` when AI is unavailable
(mirrors the legacy askAi contract)."* But `handleAskAi` **discards the boolean** — no `if (!ok)`
branch, no message, no nav. So offline: the button looks fully live, the click fires, `compose`
silently returns `false`, **nothing happens.** This is 593 §8c verbatim: *"no navigation, no toast, no
inline error, no spinner. As a user I could not tell whether it failed, is disabled, or did nothing."*

1.1 **over-disables and hides the why**; 1.2 **under-disables and drops the why.** Both are the same
root: **availability is known to the code, never delivered to the user.**

### 1.3 The disabled click is swallowed — worsened by a state-timing gap (observations.md #420)

The Agent tab is `?disabled=${!aiReady}` where `aiReady = aiState?.capabilities?.chat === true`. On
reload `aiState` is `null` until `/api/status` lands, so the **first** Agent-tab click after reload
hits a disabled button and is **silently swallowed**; the second (post-load) click works. A disabled
button eats the activation with no "unavailable because still loading" signal — and there's no
distinction between *unavailable: AI offline* and *unavailable: still loading* (a transient that
should clear), echoing 595's transient-vs-settled confusion on the operability axis.

---

## 2. Root, stated once

> The FE models an affordance's availability as a **bare boolean** (`?disabled`, or "is the button
> even rendered"), and attaches the **reason** for unavailability to a delivery channel that **fails in
> the unavailable state** — a `title` the browser suppresses on `disabled` controls (1.1), or a
> `compose()` boolean the call site discards while leaving the control un-disabled (1.2), or nothing at
> all with the click swallowed (1.3). There is no first-class **`{ available } | { unavailable, reason }`**
> affordance state that the engine renders with a **reachable** explanation and a **non-silent** blocked
> interaction. So an unavailable affordance is, in practice, an **unexplained** one.

One root, three faces — the operability analog of 594 (Display can't show the fact) and 595
(Observed-state computes the verdict twice).

---

## 3. Why the existing operability authority doesn't prevent it

559 Part II Authority V (`controls-a11y`) enforces that an activation handler sits on a
keyboard-operable element **with an accessible name** — it guarantees the control is *operable and
named*, not that its *unavailability carries a surfaced reason*. And, as 559 itself established, the
gate bottoms out at **grepping Lit template strings**, which can't see whether a `title` is reachable
(it's on a `?disabled` element) or whether a `@click` handler surfaces feedback on a `false` return.
So:

- "accessible name present" ✓ (the `title`/label exists) — but "reason reachable when unavailable" is
  unchecked and, in 1.1, structurally impossible via that channel.
- The `compose()` availability gate exists and is correct — but nothing makes the **call site** honor
  the `false` (1.2). The authority covers *operability*, not *unavailability feedback*.

No gate catches it because there's no single typed "affordance availability" the engine owns; each
button hand-rolls `?disabled` + a `title` and each handler decides (or forgets) to surface feedback.

---

## 4. Design (ladder-ordered, smallest footprint first)

> **This section is the move-level approximation of §9.** §8 corrects two of its moves (the "engine"
> below is `jf-control`, not the 543 renderer — there is none; the feedback channel is the gate-enforced
> `emitEphemeralToast`). **§9 states the structure these moves approximate.** Read §9 for the end-state.

### Move 1 — model availability as a typed state, engine-rendered (rung-2, Unrepresentable)

Lift availability from a bare boolean to a typed value on the affordance declaration (the 543
Action+Effect substrate is the natural home):

```ts
type Availability =
  | { kind: 'available' }
  | { kind: 'unavailable'; reason: string; transient?: boolean };  // transient ⇒ 'still loading', not 'offline'
```

The engine renders `unavailable` with a **reachable** reason and a **non-silent** blocked interaction —
e.g. the disabled control wrapped so the reason shows on hover/focus (a wrapper that is NOT
pointer-disabled, or `aria-describedby` + a visible hint), and an activation attempt emits the reason
(toast/inline) instead of dying. The point is rung-2: **"unavailable without a surfaced reason" becomes
unrepresentable** — you cannot declare unavailability without supplying the reason the engine will show.
This closes 1.1 and 1.3 structurally, and gives 1.2 a place for its already-known reason to land.

### Move 2 — the call site must honor the availability gate (rung-1, Collapse)

`compose()` already returns `false` on unavailable. The fix is to route Ask-AI (and peers) through the
Move-1 availability state so the button is **either** declared unavailable (engine shows "AI offline"
and blocks with feedback) **or** the `@click` honors the `false` and surfaces the reason — never the
current "looks live, silently no-ops." Collapse the "is this actionable?" decision to the one
availability authority instead of each call site re-deciding (or forgetting).

### Move 3 — the discoverability sub-case (rung-2, but lower severity; in/out below)

Rail icons with no `title` at all (593 §1) are the *enabled*-but-unlabeled cousin. They're milder (an
enabled affordance that's merely unlabeled, not a blocked one with a hidden reason). Candidate to fold
in *if* Move 1's declaration carries the always-on label too; otherwise its own small fix. Flagged, not
assumed.

### Ladder placement

Move 1 (typed availability the engine renders) is the structural core — it makes the unexplained-
unavailable state unrepresentable. Move 2 collapses the call-site decision onto it. A spot-fix
(e.g. wrap each `title` in a non-disabled span, or add `if (!compose(...)) toast(...)` at one call
site) is rung-0 — it patches one button and the next author re-introduces the pattern. The structural
close is the typed state + the engine ownership.

---

## 5. Scope boundary (what this is NOT — guarding against over-unification)

**IN (same root — unavailability without a surfaced/reachable reason):** the disabled mode-tabs with a
suppressed `title` (1.1), the never-disabled "Ask AI" that drops `compose()`'s `false` (1.2), the
swallowed disabled-click + loading-vs-offline conflation (1.3), and any affordance whose unavailability
is decided by a bare `?disabled` with the reason on an unreachable channel or absent.

**OUT (different roots — do NOT bundle):**
- **The 384-d chip / capability strip** — the *Display* projection (factual content). Owned by **594**.
- **The split health verdict / transition state** — the *Observed-state* projection. Owned by **595**.
  (1.3's loading-vs-offline conflation is adjacent to 595's transient modeling, but here it's about an
  *affordance's* availability feedback, not the *system* health verdict. Cross-reference, don't merge.)
- **Facet count > result count / 136 cap** (593 §8/§10) — search-response semantics. Their own traces.
- **Pure discoverability of ENABLED rail icons** (593 §1) — milder; Move 3 candidate, not core.

---

## 6. Open questions / decisions (before impl)

1. **Move 1+2, or Move 2 only?** Move 2 (honor the `compose()` `false` at the Ask-AI call site) is a
   cheap honest fix for 1.2 alone. Move 1 (typed availability + engine render) is the structural close
   for all three faces but is the larger lift (touches the 543 substrate + the affordance renderers).
   Recommendation: Move 1 as the design target; Move 2's call-site honoring falls out of it. A pure
   Move-2 spot-fix without Move 1 leaves 1.1's suppressed-`title` class open.
2. **Reason-delivery mechanism for a disabled control.** Native `title` is out (suppressed on
   `disabled`). Options: a non-pointer-disabled wrapper with a custom tooltip; `aria-disabled="true"`
   + `tabindex` (operable-but-inert, so hover/focus fire) instead of the native `disabled`; or an
   inline hint row. Each has a11y trade-offs (`aria-disabled` keeps the control focusable — good for
   discoverability, but the handler must then guard). Decide the canonical pattern once.
3. **transient vs settled unavailability.** 1.3 shows `aiState===null` (loading) renders identically to
   `chat===false` (offline). Should `Availability.transient` drive a distinct "still loading…" affordance
   (optimistic/▢) vs a settled "AI offline"? Ties to 595's transition modeling — coordinate, don't
   duplicate.
4. **Home check (done).** Extends 559 Part II Authority V (operability) + 543 (Action+Effect). 559 was
   considered as the literal home but it's the broad element-catalog completion doc; this is a focused
   depth round, sibling to 594/595. If you'd rather fold it into 559 Part II, say so.
5. **Doc maintenance.** On impl, 559 Part II Authority V gains a sub-section: operability now covers
   *unavailability reason delivery*, not just operable+named. ADR-0032 / explanation/27 unaffected (the
   authority model's reach widens; the model is unchanged).

---

## 7. As-built so far

- **Nothing implemented.** Design proposal awaiting the §6 decisions. No code touched by this doc.
- **1.1 and 1.2 are independently verifiable now**: `UnifiedChatView.ts:2021–2044` (a `title` on a
  `?disabled` `<button>`) and `SearchSurface.ts:822–829` + `compose.ts:16–17` (an un-disabled button
  whose handler discards `compose()`'s `false`-on-unavailable). Both are static-readable; no live stack.

---

## 8. Investigation & critical analysis (agent takeover, 2026-06-16)

A source-verbatim verification + infra-feasibility pass. **All three faces are confirmed exactly as
written**, one new face is added, and two of the §4 design moves are corrected because they presuppose
infrastructure that does not exist. Net verdict: **the diagnosis is sound; the prescription overstates
the available "engine" and needs a smaller, more honest structural target.**

### 8.1 Claims verified verbatim against `main`

| Claim | Source checked | Verdict |
|---|---|---|
| 1.1 — `title` on a `?disabled` `<button>` (mode tabs) | `UnifiedChatView.ts:2014–2044` (line numbers shifted ~7 vs the doc; substance identical) | **Confirmed.** All three escalation tabs (Documents `:2023`, Structured `:2031`, Agent `:2039`) carry both `?disabled` and a reason-bearing `title`. |
| 1.2 — `compose()` returns `false`; call site discards it | `SearchSurface.ts:822–829` (`handleAskAi`), `:1359–1363` (never-disabled button), `compose.ts:159–163` (`if (!ai.capabilities.chat) return false;`) | **Confirmed.** `handleAskAi` ignores the return; the button has no `?disabled`. |
| 1.3 — disabled Agent-tab click swallowed + loading/offline conflation | `UnifiedChatView.ts:2039`, obs #420 | **Confirmed.** `?disabled=${!aiReady}`; `aiReady = chat===true`; `aiState` is `null` until `/api/status` lands. |
| compose's `false`-on-unavailable contract | `compose.ts:16–17` doc + `:159–163` impl | **Confirmed.** Gate is real and correct; only the call site is at fault. |

The `structural-defects-no-repeat` bar is cleared: two faces provable in code, a third evidenced in
observations. No live stack was needed — all four are DOM-semantics / static-readable.

### 8.2 New face found — the availability *boolean itself* forks (strengthens the root)

The doc's root says the **reason** is delivered on a failing channel. There is a sharper version: for
the *same logical "documents/ask" affordance*, the availability **boolean** is computed from **two
different capability fields** in two places:

- The **Documents mode tab** gates on `capabilities.rag` (`UnifiedChatView.ts:2023`), where
  `rag = chat && docs > 0` (`aiStateStore.ts:185`).
- The **"Ask AI"** path gates on `capabilities.chat` (`compose.ts:161`) — `docs` count is irrelevant.

So with AI online but **zero documents indexed**: the Documents tab is disabled with reason "No
documents indexed", yet `compose()` returns `true` and proceeds (chat is up). The two surfaces disagree
on whether "ask the documents" is even available, and the reason the tab would show ("No documents
indexed") is not a condition the Ask-AI gate even checks. This is **availability-authority fork**, the
operability analog of 595's split-verdict — and it is a second, independent reason the §4 design's
"one availability authority" is the right cut, not just "one reason channel." **Add this as face 1.4.**

### 8.3 Correction to Move 1 — the "543 substrate is the natural home" overstates the infra

The doc calls the 543 Action+Effect substrate "the natural home" for a typed `Availability` and frames
Move 1 as "the **engine** renders `unavailable`." **There is no such engine for these affordances.**
Verified:

- The 543 substrate (`shell-v0/substrates/actions/index.ts`, `substrates/effect.ts`) is
  **declaration + dispatch + effect-journal only**. Nothing turns an `Action` declaration into a
  rendered `<button>`. (The `Effect` union *does* already carry a `toast` kind and imports
  `emitEphemeralToast` — relevant to Move 2, see 8.5.)
- The mode tabs are **hand-rolled `<button>` Lit templates** in `renderAffordanceBar`
  (`UnifiedChatView.ts:2014–2044`) — they do **not** flow through the 543 substrate, nor through any
  affordance-rendering component. "Ask AI" is likewise a hand-rolled button.
- Per 559 §3, the only kernel that *is* applied (the presentation projection / `SurfaceFactory`)
  projects **surfaces**, not controls. Controls are explicitly still hand-authored.

So putting `Availability` on the `Action` declaration buys nothing for the three evidenced faces,
because nothing reads `Action` to render these buttons. **Move 1 as written presupposes a renderer that
isn't there.** The realistic structural home is the one operability primitive that *does* exist:

> **`jf-control` (`components/Control.ts`)** — the single operable affordance primitive 559 Authority V
> already mandates (a `<div @click>` is banned by the `controls-a11y` gate). It today has only a **bare
> `disabled` boolean and no reason**. The honest Move 1 is: **(a)** give `jf-control` a typed
> `unavailable: { reason, transient? }` state that it renders with a *reachable* explanation and a
> *non-silent* blocked activation; **(b)** migrate the affordance-bar tabs + "Ask AI" off raw
> `<button>` onto `jf-control`. That migration is an **unstated precursor** the doc should name — the
> tabs currently bypass the very primitive the authority is built on.

This is still rung-2 (unavailable-without-a-reason becomes unrepresentable *in the primitive every
control already composes*), but it is a far smaller and more truthful lift than "the engine renders it."

### 8.4 §6 Q2 resolved — `aria-disabled`, and it closes 1.1 **and** 1.3 with one mechanism

The open question "reason-delivery mechanism for a disabled control" has a settled answer. The W3C ARIA
APG **Button Pattern** states: *when the action associated with a button is unavailable, the button has
`aria-disabled` set to true* — it stays in the focus order (discoverable) and a tooltip on it is
reachable via focus/hover, whereas native `disabled` removes it from focus order, suppresses the
`title`, **and swallows the click** (which is exactly 1.3's bug). So a single switch — native
`disabled` → `aria-disabled="true"` + a guarded handler — closes **1.1** (tooltip now reachable) **and**
**1.3** (click fires, handler surfaces the reason instead of dying) simultaneously. This belongs inside
`jf-control` so every control inherits it. Trade-off the doc already noted is real and the right one:
`aria-disabled` keeps the control operable, so **the handler must guard** (check availability, and on
unavailable surface the reason rather than perform the action) — which is precisely the "non-silent
blocked interaction" Move 1 wants. ([W3C APG Button Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/button/),
[MDN aria-disabled](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-disabled),
[CSS-Tricks: Making Disabled Buttons More Inclusive](https://css-tricks.com/making-disabled-buttons-more-inclusive/))

### 8.5 Correction to Move 2 — "toast(...)" must route through the gate-enforced single channel

Move 2 sketches `if (!compose(...)) toast(...)`. A *new* `toast()` would trip the **`check-message-
single-model`** gate (559 Authority III), which enforces exactly one emit seam. The feedback **must**
go through `emitEphemeralToast({ message, severity })` (`components/advisory/ephemeralToast.ts:40` →
`AdvisoryStore` → `AdvisoryToastHost`). Note the 543 `Effect` union's `toast` kind already lands there,
so if/when these affordances route through the substrate, the channel is consistent. **State the
channel explicitly in Move 2** so an implementer doesn't hand-roll a second toast and fail the build.

### 8.6 Scope / severity check — is the typed state worth it, or gold-plating?

The blast radius is ~4 affordances (3 mode tabs + Ask-AI). Against that, a typed `Availability` on
`jf-control` is **not** speculative abstraction — it is "finish the operability primitive that 559
already requires every control to compose," and it dissolves three evidenced silent-failure faces plus
the 8.2 boolean-fork. That is in-character for the 557/559 ladder (rung-2, prevention-by-construction),
not YAGNI. The one piece I'd flag as *optional* is the `transient` flag (loading-vs-offline): 1.3's
minimal fix is just "don't use native `disabled`" (un-swallows the click) + "say 'still loading' when
`aiState` is `null`". The `transient` boolean is cheap *once the typed state exists* and gives the
honest "still loading…" vs "AI offline" distinction (§6 Q3) — keep it, but it rides for free; it is not
its own lift.

### 8.7 Recommendation on the §6 decisions

1. **Q1 (Move 1+2 vs Move 2-only):** Do the **`jf-control`-scoped Move 1** (8.3) — *not* the
   543-substrate Move 1 as literally worded, and *not* Move 2 alone (which leaves 1.1's suppressed-
   `title` class open). Move 2's call-site honoring falls out once "Ask AI" is a `jf-control` with a
   typed availability derived from the **same** authority as the Documents tab (fixing 8.2's fork).
2. **Q2 (mechanism):** `aria-disabled="true"` + guarded handler, inside `jf-control` (8.4). Settled.
3. **Q3 (transient vs settled):** Keep `Availability.transient`; it rides on the typed state (8.6).
   Coordinate the "loading" wording with 595's transition modeling; don't duplicate a phase machine.
4. **Q4 (home):** Stays a focused depth-round sibling of 594/595 — **but** retarget the named home from
   "the 543 substrate / an engine" to "**`jf-control`, the 559 Authority V operability primitive**,"
   which is where the gap actually lives and what the `controls-a11y` gate already governs.
5. **Q5 (doc maintenance):** On impl, 559 Part II Authority V gains the sub-section as planned; also
   note the precursor migration (affordance-bar tabs + Ask-AI → `jf-control`) since that is the visible
   diff. ADR-0032 / explanation/27 unaffected.

### 8.8 One open question for the user before implementing

The honest Move 1 (8.3) has a **precursor cost the original doc did not budget**: the mode tabs and
"Ask AI" must first be migrated from hand-rolled `<button>` onto `jf-control`. Does `jf-control` today
support the affordance-bar's needs (the `active`/selected visual state, the compact tab styling)? If
not, that migration is its own small slice. **Decision needed:** (a) extend `jf-control` + migrate
these affordances onto it (full structural close, larger diff), or (b) a narrower `jf-control`-internal
availability state applied only where controls *already* use `jf-control`, plus a tracked follow-up to
migrate the affordance bar. I recommend (a) but it is a scope call for the user — flagged per
`ask-when-uncertain`, not silently chosen.

---

## 9. The correct long-term structure (design-theory deepening, 2026-06-16)

> **Genre note** (per 557/594/595). This section states the *correct end-state* at the bar the
> category sets. Feasibility, phasing, and migration cost are deliberately out of scope; major
> refactors are in scope. §4/§8 are the move-level proposal + its critique; **this is the structure
> they are an approximation of.** Current-behaviour claims are cited to `main` (§8, the Explore trace,
> and the file:line citations inline); re-verify before relying.

### 9.1 The real gap: the operability authority has an OPERABLE+NAMED half and no AVAILABILITY half

559 Part II Authority V is explicit about what it built: `jf-control` is "THE single control every
clickable affordance is built from, so operability — keyboard activation, focus, role, **accessible
name** — is a property of the primitive." It guarantees a control is **operable** and **named**, and it
projects the name from one declaration — `present({kind:'operation', id})` (`Control.ts:76–79`), the
Display authority's name half (594 §9.1). That is the *"can you reach it, and what is it called"* half.

It models the third operability question — *"is this control available right now, and if not, why"* —
as a **bare `disabled: boolean`** (`Control.ts:29`) whose guard **silently swallows** the activation
(`activate()`: `if (this.disabled) return;`, `:81–84`) and whose native `?disabled` render (`:105`)
also suppresses any `title`. The **reason** is left to each call site to hand-derive inline (the mode
tabs' `title` ternary). So the authority is complete for *operable + named* and **absent for
*available + why-not, delivered reachably*.**

This is the exact symmetric shape of the two siblings — the same authority, one face short:

| Sibling | Authority (557 projection) | Built half | Missing half this doc/sibling theorizes |
|---|---|---|---|
| **594** | Display | `present()` projects every **name** | `projectFact()` projects every **value** |
| **595** | Observed-state | `ConnectionPhase` tri-state (connect/stale/connected) | a derived **system-health verdict** + `transitioning` phase |
| **596 (this)** | **Operability** | `jf-control` guarantees **operable + named** | a typed **availability** projection (`available \| unavailable{reason}`) the primitive renders |

The same gap, several severities (the `structural-defects-no-repeat` bar, cleared in §1/§8):

| Site | How availability + reason is modeled today | Severity |
|---|---|---|
| **Mode tabs** (`UnifiedChatView.ts:2021–2044`) | `?disabled` boolean + reason on a **suppressed** `title` | high (reason authored, structurally unreachable) |
| **"Ask AI"** (`SearchSurface.ts:1359–1363`) | **never** disabled; `compose()`'s `false`-on-unavailable **discarded** | high (looks live, silent no-op) |
| **Agent tab on reload** (obs #420) | native-`disabled` click **swallowed**; loading ≡ offline | medium (no "still loading" signal) |
| **The 543 *declaration* layer** (`substrates/actions/index.ts`: `enabled?: (addressable) => boolean`) | a **bare boolean predicate** with **no reason slot** | structural (the gap exists one layer up, at the declaration) |

The pattern is unmistakable: **the operable+named half is centralised (`jf-control` + `present`), the
availability+reason half is a bare boolean — at the primitive *and* at the declaration.** So the durable
structure is to **complete the operability authority's availability half**, with the three evidenced
affordances as its first adopters.

### 9.2 The end-state: availability as a typed observable + ONE availability projector

An affordance's availability has three parts: **is it available** (the verdict), **why not** (a reason,
when unavailable), and **is the unavailability transient** (still-loading, self-clearing) **or settled**
(offline). The correct structure makes availability a **typed value**, and adds the operability-side
sibling of `present()` / `projectFact()`:

```ts
type Availability =
  | { kind: 'available' }
  | { kind: 'unavailable'; reason: string; transient?: boolean };
```

- **A `projectAvailability(affordanceRef) → Availability`** — the operability-side twin of `present()`
  (name) and `projectFact()` (value). It is the ONE place an affordance's availability + reason is
  resolved, from the observed-state authority (`aiStateStore` capabilities + `ConnectionPhase`). It
  dispatches on the affordance's declared availability condition (below). This is what dissolves the
  §8.2 fork: the Documents tab and "Ask AI" stop each re-deciding "is documents/ask available" (one on
  `rag`, one on `chat`) — they consume **one** projection for the same logical affordance.
- **The reason is itself a projection, not a new string source.** "No documents indexed" is a **594
  leaf fact** over `docCount`; "still loading…" is the **595 connection phase** (a transient); "AI
  offline" is a capability fact. So 596 *composes* 594 and 595 — it **binds** an affordance's operability
  to facts/phases those authorities already own, instead of re-deriving them inline (the inline
  re-derivation is exactly what produces the `rag`-vs-`chat` fork and the hand-typed `title` strings).
  **Decoupling note (§10/C4):** 594 and 595 are unbuilt design docs, but the *raw* inputs
  (`capabilities.chat`, `index.documentCount`, `phase`) all exist on `aiStateStore` **today** — so
  `projectAvailability` reads the store fields now and consumes 594/595 projections *when they land*.
  596 is independently implementable; the "composes" framing is the eventual shape, not a prerequisite.

### 9.3 Author declares the condition; the primitive owns the rendering (569's contract)

Availability has two concerns, and they split along 569's "author declares, engine owns" line:

- **The DECLARATION owns the *condition*.** The 543 `Action.enabled?: (addressable) => boolean` is
  subsumed into an **availability spec** — a ref/predicate over the observed-state authority that yields
  `available | unavailable{reason, transient}`, not a bare boolean. The author declares *what makes this
  affordance unavailable and what to say*; it cannot declare unavailability without supplying the reason.
- **The PRIMITIVE owns the *rendering*.** `jf-control`'s `disabled: boolean` becomes
  `availability: Availability`. The primitive renders `unavailable` with a **reachable** reason and a
  **non-silent** blocked activation (§9.4). A wrong/absent reason has nowhere to live because the typed
  form has no bare-boolean slot.

The leverage point — **corrected by §10/C1.** The first draft argued the `controls-a11y` gate *already
requires* every affordance to be a `jf-control`, so availability-on-`jf-control` would be unrepresentable
app-wide by construction. **That is false:** the gate accepts native `<button>` as a coequal operable
form, so a raw button is a legal bypass and the primitive alone cannot make the bad state unrepresentable.
The honest enforcement (see §10/C1, which amends this) is **Unrepresentable on the primitive PLUS a
load-bearing Gate dimension** that fails the build on the *failure patterns* (a reason on a `?disabled`
control; a discarded availability boolean) regardless of element. Migrating the mode tabs + "Ask AI"
onto `jf-control` is still the right adoption path (it gives those affordances the typed availability),
but it is **a migration this design must budget, not a free consequence of an existing gate.** Also keep
the two-kind split (§10/C2): the `aria-disabled` soft form is for capability-blocks, **not** a blanket
replacement of the primitive's hard `disabled` (the confirmation gates must stay inert).

### 9.4 Two delivery requirements — and a genuinely missing primitive

Unavailability must be delivered **two** ways, and the codebase has neither correctly today:

1. **Passively reachable (discovery).** Native `title` is the bug — suppressed on `disabled`. The W3C
   APG answer is `aria-disabled="true"` (the control stays focusable/hoverable, so a reason is
   reachable) **plus a real reason-surface** — `aria-describedby` pointing at a visible/AT-reachable
   hint. **There is no accessible tooltip primitive in the codebase — only native `title`** (Explore
   trace; the very channel that fails). So the end-state *implies a missing primitive*: an accessible
   reason-surface owned by `jf-control`, not native `title`. Named here as a first-class gap.
2. **Non-silent on attempt (feedback).** An activation attempt on an unavailable control surfaces the
   reason through the **one** message channel — `emitEphemeralToast` → `AdvisoryStore` (559 Authority
   III, gate-enforced by `check-message-single-model`) — **never** a new toast (§8.5). Using
   `aria-disabled` instead of native `disabled` is precisely what lets the click reach the handler so it
   *can* surface the reason — which is also what closes 1.3's swallow. One mechanism, both faces.

### 9.5 Why this is rung-2 (Unrepresentable), and the gate degrades to a thin backstop

With availability typed on the primitive and projected from one authority, a **bare `disabled` boolean**
and an **inline `title` reason** have nowhere to live *for affordances that go through `jf-control`* —
the 557 ladder's **Unrepresentable** rung (rung-2). **But (corrected by §10/C1) the Gate rung is NOT
demoted to a thin backstop here**, unlike 594 §9.3: because the `controls-a11y` gate accepts native
`<button>` as a coequal form, a raw button remains a legal place for the failure pattern to live. So the
end-state is genuinely **two-tiered**: Unrepresentable *on the primitive* **and** a load-bearing
`controls-a11y` Gate dimension that fails the build on the failure patterns themselves — a reason on a
`?disabled` control, a discarded availability/`compose()` boolean — **on any element**. The Gate is what
makes the class unrepresentable in the presence of legal native buttons; it is load-bearing, not a
backstop.

### 9.6 Boundary with 594/595 (the sibling cut) — an operability BINDING, not a fact, not a verdict

The three depth rounds share `aiStateStore` as substrate, so the cut must be explicit:

- **594 projects a LEAF fact's *value*** — a measured/declared datum with a single source (a dimension,
  a GPU type, a capability's on/off).
- **595 derives a *verdict/phase*** — a computed rollup over many leaves (the system-health verdict; the
  `transitioning` phase).
- **596 projects an AFFORDANCE's *availability*** — the **binding** from observed-state to *this
  control's* operability: "given the capabilities and phase, is this action available, and if not, why."
  It **consumes** 594-facts and the 595-phase to compose the reason; it does **not** reproduce the health
  verdict or own the leaf facts.

Concretely: *"what is the embed dimension?"* is a **594** fact (a chip). *"is the system healthy / mid-
transition?"* is a **595** verdict (a header badge). *"is the Documents affordance available, and why
not?"* is a **596** availability (a control's state). Three authorities, one observed-state substrate.
Keeping `projectAvailability` strictly a **binding** (observed-state → this control's availability +
reason) is what stops 596 re-growing a capability interpreter — the `rag`-vs-`chat` fork (§8.2) is
exactly that regrowth, and the single projection is what forbids it.

### 9.7 The transient axis ties to 595, doesn't duplicate it

`Availability.transient` distinguishes *"unavailable: still loading"* (`aiState === null`, self-clears
when `/api/status` lands) from *"unavailable: AI offline"* (settled). This is the **operability
projection of 595's transition phase** — `projectAvailability` *reads* the `ConnectionPhase`/loading
state, it does not model its own phase machine. An optimistic "still loading…" affordance versus a
settled "AI offline" one (§6 Q3). Coordinate the wording with 595's `transitioning` kind; do not grow a
second transient interpreter (the same discipline 595 §9.5 / 594 §9.5 impose on each other).

### 9.8 Scope discipline (AHA / YAGNI guard, mirroring 594 §9.6 / 587 §7)

The justification is **empirical recurrence**, not aesthetics: three evidenced silent-failure faces
(§1) + the declaration-layer bare boolean (§9.1) + the `rag`-vs-`chat` availability fork (§8.2) — the
*same* "availability modeled as a bare boolean, reason on a failing channel" loss, several documented
members. Guards:

1. **Only *affordances* go through `projectAvailability`.** Not every boolean — a control that is
   always available declares nothing (absence ⇒ available). The projector is for affordances whose
   availability depends on observed-state.
2. **Make the single-condition case free.** Most affordances gate on one capability; the declared ref +
   a one-line reason derivation is the whole cost — no precedence ceremony.
3. **Don't force-migrate beyond the gate's existing reach.** The `controls-a11y` gate *already* mandates
   every affordance be a `jf-control`; the availability half rides that mandate. It is not a license to
   route non-affordance state through the projector.

### 9.9 End-state, one paragraph

The operability authority (559 Part II Authority V) is completed when it has **both halves**:
`jf-control` guarantees every affordance is **operable and named** (via `present()`), and a sibling
`projectAvailability()` projects every affordance's **availability** — `available | unavailable{reason,
transient}` — from one observed-state authority, *composing* 594's projected facts and 595's connection
phase for the reason rather than re-deriving them inline. The **declaration** (543) carries the
availability *condition* (the bare `enabled` boolean subsumed), and the **primitive** (`jf-control`)
*owns* rendering it: a reachable reason (`aria-disabled` + an accessible reason-surface, **not** native
`title`) and a non-silent blocked activation through the **one** message channel. Because every
affordance is already required to be a `jf-control`, the whole app inherits correct unavailability by
construction; a `disabled` boolean with no reason, an inline suppressed `title`, a dropped `compose()`
`false`, and a swallowed click all become **unrepresentable**. The "Ask AI" that silently no-ops offline
is not fixed by an `if (!ok) toast()` — it is fixed by **availability becoming an authority the control
consumes**, the operability sibling of the value authority 594 gives Display and the verdict authority
595 gives Observed-state.

---

## 10. Pre-implementation confidence findings (read-only verification, 2026-06-16)

Before implementing, a read-only pass verified §9's load-bearing assumptions against `main` (gate
source, `jf-control` consumers, store fields, `Action.enabled` readers). **It refuted one central claim
and sharpened three more.** The §9 direction holds (typed availability + reachable reason + non-silent
block, owned by `jf-control`), but the four corrections below are now folded back into §9.3/§9.5/§9.6 —
read §10 as the authoritative amendment where it conflicts with the first draft of those subsections.

### C1 — the `controls-a11y` gate does NOT force `jf-control` (refutes §9.3's leverage)

`scripts/ci/check-controls-a11y.mjs` accepts **native `<button>` as a first-class, coequal, permanent**
operable form (`NATIVE_OK` includes `'button'`); `jf-control`, native `<button>`, and the
role+tabindex+keydown triad are *coequal*, and **no companion gate forces affordances onto `jf-control`**.
The mode tabs + "Ask AI" pass purely as native buttons (no allowlist). The gate inspects **nothing**
about `disabled`/`title`/reason.

→ §9.3's *"because the gate already requires every affordance to be a `jf-control`, putting availability
on it makes the bad state unrepresentable app-wide"* is **wrong**. A native `<button>` is a legal bypass,
so the Unrepresentable rung **cannot be reached by the primitive alone.** The honest end-state is
**Unrepresentable-on-the-primitive *plus* a load-bearing Gate dimension** — a new `controls-a11y`
extension that fails the build on the *failure patterns themselves*, on **any** element: a `title` (or
any reason text) on a `?disabled` control, and a discarded availability/`compose()` boolean. Unlike
594 §9.3 (where Gate demotes to a thin backstop), **here Gate is not optional** — native buttons remain
legal, so the pattern-catching gate is what makes the class unrepresentable in practice. (The alternative
— making `jf-control` the *sole* affordance path — is a far larger migration that contradicts the gate's
deliberate coequal-native design; not recommended.) **§9.5 is amended accordingly.**

### C2 — availability has TWO sub-kinds; do NOT re-semantic the shared `disabled` (sharpens §9.2/§9.3)

9 `jf-control`/`jf-button` consumers set `disabled`, and **all rely on native `disabled` to block the
click**. Four are **intent/confirmation gates** — `AuthorizationHost.ts:333` (Approve),
`AgentActivityPanel.ts:243` (Confirm), `ConfirmDialog.ts:299`, `AdvisoryInboxDrawer.ts:445`. A global
flip of `jf-control`'s `disabled` → focusable `aria-disabled` that still fires `@click` would let clicks
reach these gate handlers — a real regression (bypassed confirmation).

→ Availability is **not one thing**. The typed value must distinguish:
- **`blocked`** — a *hard* gate (intent not yet satisfied: unconfirmed, empty-input, mid-operation).
  Stays native `disabled`; the click is genuinely inert. This is the correct, existing behaviour for the
  4 confirmation gates — they must **not** become click-through.
- **`unavailable{ reason, transient? }`** — a *soft*, capability-driven block (AI offline, no docs,
  still loading). Renders `aria-disabled` + a reachable reason + a non-silent activation that surfaces
  the reason.

So `aria-disabled` (§8.4) is the mechanism for the **soft** kind only — it is **not** a blanket
replacement of the primitive's `disabled`. The refined type:

```ts
type Availability =
  | { kind: 'available' }
  | { kind: 'blocked' }                                        // hard: intent gate, native disabled, inert
  | { kind: 'unavailable'; reason: string; transient?: boolean }; // soft: aria-disabled, reachable reason
```

### C3 — `availability` ≠ `Action.enabled` applicability (scope line for §9.3)

`Action.enabled` IS consumed — `ContextActionRegistry.ts:78`, `ContextMenu.ts:326`,
`substrates/actions/index.ts:314` — but **only to filter unavailable actions OUT of menus (hide them)**.
That is *applicability* ("this action doesn't apply to this object → don't show it"), the **opposite** UX
of *availability* ("this affordance applies but is temporarily blocked → show it and explain why").

→ §9.3's "the bare `enabled` boolean is subsumed into the availability spec" **over-unifies** (AHA
violation). Keep them separate: `enabled`/applicability stays a hide-filter for context-menu actions;
`availability` is the show-but-block-with-reason concept for *persistently-visible* affordances (the
mode tabs, "Ask AI"). 596 owns the latter only. The declaration layer 596 touches is the affordance's
**availability spec**, not `Action.enabled`.

### C4 — 596 is DECOUPLED from the unbuilt siblings (softens §9.2/§9.6)

594 (`projectFact`) and 595 (`SystemHealthVerdict`/`transitioning`) are **design-only, absent on
`main`**. But every reason input `projectAvailability` needs already lives on `aiStateStore` today:
`capabilities.chat` (→ "AI offline"), `index.documentCount` (→ "No documents indexed"),
`phase === 'connecting'` / `aiState === null` (→ "still loading").

→ §9.2/§9.6's "composes 594's facts and 595's phase" is the *eventual* shape, not a prerequisite.
**Today** `projectAvailability` reads the store fields directly; **when** 594/595 land it consumes their
projections instead of the raw fields (raw `docCount` → a 594 fact; raw `phase` → a 595 verdict/phase).
596 is independently implementable now — a strength, not a blocker.

### Net confidence

The mechanism is sound, decoupled from unbuilt work (C4), and the primitive change is feasible
(active-state styling works via `:host(.active)::part(control)` or an added `aria-pressed` forward).
But the enforcement story is weaker than the draft claimed (C1 — a real Gate dimension is required, not
just the primitive), the shared-primitive change carries regression risk that mandates the two-kind
split (C2), and a scope line must be held against `Action.enabled` (C3). These are design refinements,
not open unknowns. **Confidence for the eventual implementation: 6/10** (up from ~4 pre-verification).

---

## 11. User-facing design (browser-verified against the live degraded stack, 2026-06-16)

This section is the result of *looking at the running UI*, not reasoning from the tempdoc. It changes
the reason-delivery design materially: the app **already has the correct idiom for "unavailable + why +
what to do"**, and §9's native-`title`/`aria-disabled`-tooltip plan is a *weaker, parallel* idiom that
would fork it. The user-facing design should **harmonise with the existing pattern**, not invent a
tooltip.

### 11.1 What is user-visible (the scope of this doc's UX surface)

All three faces are directly user-facing chrome on the two primary windows:
- **Chat surface affordance bar** — `Search · Documents · Structured · Agent · History` (face 1.1/1.3).
  When AI is offline, Documents/Structured/Agent go `?disabled`; the reason is on a `title`.
- **Search surface result actions** — `MD · JSON · Paths · Ask AI` (face 1.2). "Ask AI" is a plain,
  never-disabled button.

### 11.2 Browser evidence (live, real backend in a genuinely degraded state)

The dev stack was up and **actually degraded** during inspection (status footer "Reconnecting…", a
"Semantic search degraded" banner, `/api/status` fetch hung) — i.e. the exact unavailable condition.

- **Face 1.2 confirmed live.** DOM probe of the "Ask AI" button: `tag=BUTTON, disabled=false,
  hasDisabledAttr=false, aria-disabled=null, title="Ask AI about these results"`. It looks fully live
  while the backend is unresponsive — clicking it would hit `compose()`'s `false` path and silently
  no-op, exactly as §1.2 claims.
- **Face 1.1 confirmed *empirically*, not just by DOM semantics.** I reproduced the offline render in
  the page (`Agent` tab → native `disabled` + `title="AI offline"`) and measured: **the disabled tab
  is NOT focusable** (`reasonReachable_viaFocus: false` — `.focus()` left `activeElement` elsewhere),
  so the authored reason is unreachable by keyboard/AT, **and** `pointer-events` computed to `auto`
  (the swallow is the HTML `disabled` attribute itself, not a CSS opt-out). The reason "AI offline" is
  genuinely sealed inside the disabled control. (Synthetic `dispatchEvent` *does* reach the handler —
  that is JS bypassing the disabled gate, not a user event; real user clicks/hover are blocked.)
- **Asymmetry found (the key UX finding).** The **search surface renders a rich self-explaining notice**
  — *"Semantic search degraded. Showing keyword results; relevance ranking may be reduced. · Learned
  re-ranking (LambdaMART) is not configured · [Open Health]"* — while the **chat surface affordance bar
  renders no such thing**; its only "why" is the suppressed `title`. The same app explains the same
  observed-state degradation **richly in one window and invisibly in the other.**

### 11.3 The existing correct idiom — `readinessNotice` (already shipped)

`state/readinessNotice.ts` is **exactly the projection 596 is reaching for, one level up.** It projects
the observed-state readiness record (`ReadinessView`) into a notice that carries **state** (headline),
**cause** (worded from the backend's *closed reason-code vocabulary* — `inference.offline`,
`lambdamart.not_configured`, …), and **remedy** (an actionable reference: an operation button like
*Reload AI* / *Rebuild index*, or an *Open Health* navigation). Its contract: *"a notice minted from
this projection cannot be cause-less"* — unknown codes word generically and keep the Health remedy,
**never silence.**

Two lessons this forces onto §9's design:

1. **The reason is NOT a hand-typed string and NOT a tooltip.** The mode tabs hand-type `'AI offline'` /
   `'No documents indexed'` into a `title`; `readinessNotice` projects the *same* conditions from the
   backend's closed `reasonCodes` into an inline, AT-reachable, **remedy-bearing** notice. The honest
   reason source for affordance unavailability is **the same `ReadinessView`/`reasonCodes` authority**,
   not a per-button literal. This is the §8.2 fork finding taken to its root: not only is the
   availability *boolean* forked (`rag` vs `chat`), the *reason* is forked too (hand-typed title vs
   projected reason-code) — and `readinessNotice` is the unforked authority to consume.
2. **The bar is "reason + remedy," not "reason."** §9 says surface the reason. The app's own pattern is
   higher: surface the reason **and a next action** (*Reload AI*, *Open Health*). An unavailable
   affordance that only explains, without offering the remedy the user needs, is below the standard the
   codebase already sets.

### 11.4 The corrected user-facing design

> Availability and degradation are **two projections of one observed-state**. The affordance bar should
> consume the **same** authority the search window's `readinessNotice` already consumes, and deliver the
> reason through the **same inline, remedy-bearing idiom** — not a native `title`, not a bespoke toast.

Concretely, the end-state UX:

- **Close the window asymmetry first.** The **chat surface should render the `readinessNotice` banner**
  when AI/readiness is degraded (it does not today — §11.2). That single change gives the chat window a
  window-level *"AI offline · [Reload AI]"* explanation that is reachable and actionable, independent of
  any per-tab tooltip. This is the highest-value, lowest-risk user-facing move and it reuses a shipped
  component.
- **The affordance's own state references that shared reason, it does not re-author it.** A disabled
  Documents/Agent tab is `aria-disabled` (focusable, so discoverable) with an accessible description
  that names the *same worded cause* the banner shows (`projectAvailability` reads `reasonCodes`, not a
  literal). Hover/focus reveals the cause via an **accessible description surface** (`aria-describedby`
  → a visible hint), **never** the native `title` (which §11.2 proved unreachable).
- **A blocked activation is non-silent and points at the remedy.** Clicking an unavailable tab (or the
  offline "Ask AI") does not no-op: it surfaces the reason **and** routes to the remedy — ideally by
  drawing attention to / scrolling to the already-rendered `readinessNotice` banner (the remedy button
  lives there), falling back to the one message channel (`emitEphemeralToast`) with the worded cause.
  This unifies faces 1.1, 1.2, 1.3 onto one behaviour.
- **Transient vs settled is the user-meaningful distinction the reason vocab already encodes.**
  `readinessNotice` already separates `inference.starting` ("still starting") from `inference.offline`
  ("offline · Reload AI"). The affordance's transient state ("still loading…", optimistic, no remedy
  button yet) vs settled ("AI offline · Reload AI") should read the *same* codes — closing face 1.3's
  loading≡offline conflation with no new vocabulary.

### 11.5 How this revises §9 (and the two-kind split from §10/C2)

- §9.4's "missing tooltip primitive" is **re-scoped**: the missing primitive is not a generic tooltip,
  it is the **affordance-level analogue of `readinessNotice`** — a per-control accessible reason/remedy
  surface that *projects the same reason-codes*. Building a generic hover-tooltip would fork the idiom;
  extending the readiness projection to control granularity harmonises it.
- §9.2's `Availability.reason: string` should become a **reason-code reference** (projected to wording +
  remedy via the readinessNotice vocabulary), not a free string — so an affordance cannot state an
  unworded or remedy-less reason.
- The §10/C2 two-kind split now has a clean **reason-source** mapping for the user: `blocked` (hard
  intent gate) carries a *local* reason ("type CONFIRM to enable" — a form-validation truth, no backend
  remedy); `unavailable` carries an *observed-state* reason (a `readinessNotice` reason-code + its
  remedy). Two kinds, two reason sources, one delivery idiom (inline, reachable, actionable).

### 11.6 Net effect on confidence

This *raises* design confidence on the reason-delivery half (the hardest open question, §6 Q2): instead
of inventing and a11y-hardening a tooltip, the design **consumes a shipped, gate-respecting,
reason-code-projecting, remedy-bearing component** and extends it to a second window + control
granularity. It also *adds* one concrete, independently-shippable user-facing win (render `readinessNotice`
on the chat surface) that closes the §11.2 asymmetry on its own. The implementation-confidence rating is
unchanged at **6/10** (the §10 enforcement/blast-radius risks stand), but the *user-facing* design is now
evidence-grounded and idiom-consistent rather than a parallel invention.

---

## 12. Implementation effort estimate (git-calibrated, 2026-06-16)

Calibrated against the actual landing of the closest analogue — **559 Part II Authority V** (the
`jf-control` primitive + the `controls-a11y` gate + ~13 affordance migrations + tests), which landed in
**one commit ~56 min after its design commit** (`bb3feb18f`, 17 files / 527 insertions) — and against the
**565 §13** tier (composition primitive + register + gate + 2 bindings, design→"SHIPPED" in **~72 min**,
commits 4–8 min apart). In this repo an agent-driven "primitive + gate + register + a dozen call-site
migrations + tests" slice is a **~1-hour session**, not the multi-day effort a human-developer prior
suggests.

596 sits **above** that baseline because of three multipliers (all already flagged in §10):

1. **Shared-primitive *semantic* change (the real time sink).** Unlike 559's greenfield migrations
   (adding operability to bare `<div @click>`), 596 changes `jf-control`'s existing `disabled` semantics
   across **9 consumers, 4 of them confirmation/security gates** (§10/C2). This needs per-consumer care +
   **live-stack regression verification** (a wrongly click-through Approve/Confirm is a real defect), not
   a mechanical sweep. This is the dominant variance.
2. **A new projector + reason harmonisation** — `projectAvailability` over `aiStateStore`/`reasonCodes`,
   plus rendering `readinessNotice` on the chat surface (§11). ≈ one `readinessNotice`-sized unit.
3. **A new `controls-a11y` gate dimension** (catch reason-on-`?disabled` / discarded availability boolean
   on any element). ≈ the 190-line `controls-a11y` gate; ~1 session, low risk.

**Estimate: ~3–5 focused agent sessions, ~1–2 calendar days (~6–12 commits)** — variance dominated by
multiplier 1 (the aria-disabled blast radius + its live-verify loop). **Floor:** the standalone §11.4 win
(render `readinessNotice` on the chat surface) is a **single ~1-hour session** and is independently
shippable. The durable estimating lesson: in this repo the only reliable time-multipliers are
*changing existing shared semantics* and *needing live-stack regression verification* — 596 hits the
first squarely, which is exactly why it is "a few sessions," not "one."

---

## 13. Neighbor-interference note (sibling tempdocs, 2026-06-16)

A scan of the sibling design docs (594/595/597 — all open, none implemented, all edited within the hour
in this checkout; no worktrees active) surfaces **one material sequencing dependency that qualifies
§10/C4's "build now on the raw store."**

- **595 is an UPSTREAM authority 596 must consume, not race.** 595's current redesign centralises a
  single derived **`SystemHealthVerdict`** + a **Stability axis** (`settled | provisional·cause`) on
  `aiStateStore`, **and** — per 595 §10.5 — adds a **severity dimension + a per-reason tone/wording table**
  (an FE projection of the reason vocabulary, *"like `readinessNotice.CAUSE_ROWS`"*). 595 §10 found the
  live `retrieval='degraded'` is **over-alarming** — degraded solely because an *optional* re-ranker
  (LambdaMART) is unconfigured while semantic search is fine, so the search banner is factually wrong.
  → **Consequence for 596:** §11.4's "consume `readinessNotice`/`reasonCodes` for the affordance reason"
  must consume the **595 severity-aware verdict**, not the raw `reasonCodes`/`degraded` boolean — else
  596 (a) re-forks the reason interpretation 595 is explicitly unforking, and (b) **inherits the same
  over-alarming bug** (an affordance would read "unavailable: degraded" for a cosmetic re-ranker gap).
  The §10/C4 decoupling keeps 596 *unblocked*, but 596's reason-projection should **sequence after, or
  co-design with, 595** and consume its output. The `transient` axis is already cleanly cut (596.transient
  = downstream projection of 595.Stability — both docs agree; §9.7/§11.5). The safest standalone piece is
  still §11.4's "render `readinessNotice` on the chat surface," but ideally the 595-severity-aware version.
- **594 is a soft, additive upstream** (capability-fact *wording* via its Fact catalog); 596 §10/C4 is
  already decoupled. No blocking.
- **597 is a different lineage** (search-response `totalHits` contract) and self-scopes OUT of the
  presentation-authority series. Its FE work is the SearchSurface *headline/facets/trace panel*; 596's
  face 1.2 is the *Ask-AI/copy-actions* region of the same file — **same-file merge adjacency only, no
  design interference.**

Net: no blocker, but **596's reason-projection should treat 595's verdict+severity as its source** rather
than independently reading raw observed-state — the one place an "implement 596 in isolation" plan would
later need rework.

---

## 14. Confidence-building pass #2 — six implementation uncertainties resolved (2026-06-16)

Read-only inspection + code-grounded probes (one live probe blocked by the degraded-stack renderer
freeze, resolved from source). Outcome: the dominant time-multiplier is **refuted**, and the 595 coupling
is a **stable seam**, not a rework trap. Rating rises 6 → **8/10**.

- **U1 — the 595 seam is STABLE (positive).** 595 is a *layering*, not a restructuring: its §4.5 adds
  `Stability` + `SystemHealthVerdict` (and §10.5's `severity`) as **new derived fields on top of** the raw
  `capabilities` / `readiness.reasonCodes` / `phase` / `index.documentCount`, which **stay intact**. So
  596's `projectAvailability(affordance) → Availability` can read raw fields **today** and swap to 595's
  verdict **later with no signature/call-site change**; the reason vocabulary (`reasonCodes` + a
  `CAUSE_ROWS`-style table) is the stable contract both consume. §13's coordination concern stands (596
  should *target the seam* and adopt 595's severity to avoid the over-alarming bug), but there is **no
  hard ordering dependency** — confirmed, not assumed.
- **U2 — the aria-disabled blast radius is REFUTED (the big de-risk).** All four confirmation gates
  (`AuthorizationHost:338`, `AgentActivityPanel:248`, `ConfirmDialog:299`, `AdvisoryInboxDrawer:449`) and
  the other `disabled` consumers (4× `EffectAuditLog`, `Button.ts`) rely **solely on native `disabled`**
  to block — none re-guards in its click handler. Therefore the two-kind split (§10/C2) is **purely
  additive**: keep `disabled` = hard-block as the default (the `blocked` kind), **add** a separate opt-in
  `availability`/`unavailable-reason` prop that only the 3–4 capability affordances use. **All ~10
  existing consumers stay byte-for-byte unchanged**; no global re-semantic, so the live-verify regression
  loop that dominated the §12 estimate **largely evaporates**. (Caveat: because the gates depend on native
  `disabled`, the hard-block path MUST remain native `disabled` — exactly what `blocked` preserves.)
- **U3 — the gate reaches face 1.1 but NOT face 1.2 (confirms §10/C1, sharpened).** The
  `controls-a11y` gate is a grep-grade scan over `<tag …@click…>` attribute strings. A `title`-on-a-
  `?disabled` element (face 1.1) **is** statically detectable (a small `disabled=.*title=` attribute
  rule). A **discarded `compose()` boolean** (face 1.2) is a handler-body data-flow property **out of
  reach** for a grep gate (559's stated Lit-template ceiling). → **Face 1.2 is closed by the primitive,
  not the gate**: route "Ask AI" through the typed-availability `jf-control` so there is no boolean to
  discard (Unrepresentable). §10/C1's "load-bearing gate" is honestly scoped to face 1.1; face 1.2 is
  primitive-closed. Design clarity, not a feasibility loss.
- **U4 — `readinessNotice`-on-chat is cheap (positive).** `UnifiedChatView` **already** subscribes to the
  store (`subscribeAiState`, `UnifiedChatView.ts:565`; `aiState.readiness` in scope) and already reads
  `documentCount`/`capabilities`. The standalone §11.4 win is `readinessNotice(this.aiState.readiness)`
  rendered near the existing `renderAffordanceBar` — store access and data are already present.
- **U5 — the loading window is real (positive; `transient` justified).** The `connecting`/null window
  runs from page load to the **first** successful poll, bounded by a ~5 s poll cycle and a **15 s grace
  window** (`STALE_THRESHOLD_MS = 15_000`, `aiStateStore.ts:136`) — not sub-100 ms — and stretches to
  seconds under exactly the degraded conditions observed live (`/api/status` hanging). obs #420's
  swallowed-first-click is a genuine, perceptible window, so `transient` (loading vs offline) earns its
  keep.
- **U6 — jf-control active-state parity is feasible (code-confirmed).** `Control.ts` renders
  `<button part="control">`; `Button.ts` already styles `::part(control)` from outside, so the active-tab
  look reproduces via `:host(.active)::part(control)`. `aria-pressed` is **not** forwarded today — a small
  add (or host-class styling). The live in-page probe was blocked by the degraded-stack renderer freeze
  (the §11 symptom), but the structural facts are settled from source.

### Updated rating: **8/10** (was 6/10)

The two risks that held it at 6 are resolved: the 595 coupling is a stable seam (U1), and the
aria-disabled blast radius — the dominant time multiplier — is additive with zero existing-site edits
(U2). Residual (why not higher): face 1.2's primitive-closure means **routing "Ask AI" through the
typed-availability `jf-control`** (a real, well-understood change that still needs live-verify on the 3–4
capability affordances), and the 595 *coordination* (adopt its severity to avoid over-alarming) is a
judgment call, not a mechanical one. **Effort revision:** §12's "~3–5 sessions, variance dominated by the
aria-disabled blast radius" should drop toward **~2–3 sessions** — the blast radius shrank from "every
disabled consumer + live regression" to "3–4 opt-in affordances + one new prop on the primitive."

---

## 15. As-built (implemented 2026-06-17, worktree `596-availability`)

The §9 design landed structurally (typed availability owned by the primitive), per §10/§11/§14.
Static verification all green: **typecheck clean · full ui-web unit suite 3022 passed · controls-a11y
gate OK**. Live browser verification is the final batch (see the goal's verification condition).

- **Typed availability + projector** — NEW `state/availability.ts`: `Availability = available | blocked
  | unavailable{reason, transient?}` (the §10/C2 two-kind split) + `projectAvailability(affordance, AiState|null)`
  reading raw `aiStateStore` fields (the §14/U1 595-stable seam: `phase==='connecting'`/null → transient
  "Connecting to the AI…"; `!capabilities.chat` → "AI is offline"; documents & `documentCount===0` →
  "No documents indexed yet"). Reason wording parallels `readinessNotice.CAUSE_ROWS`; the capability gate
  keys on chat/docs, NOT the `retrieval==='degraded'` signal, so an optional-reranker degraded never marks
  an affordance unavailable (avoids 595 §10's over-alarming). 7 unit tests.
- **`jf-control` soft-unavailable mode** (`Control.ts`, ADDITIVE) — kept native `disabled` (hard `blocked`,
  the ~10 existing consumers untouched); added an optional `.availability` property: `unavailable` →
  `aria-disabled` (focusable, NOT native-disabled) + `aria-describedby` → a `.visually-hidden` reason span
  (reused from `ambientStyles`), and an activation attempt emits `emitEphemeralToast` (the one 559
  Authority III channel) instead of a silent no-op. 5 new `Control.test.ts` cases (incl. the §11.2 focus-
  reachability flip: a soft-unavailable control IS focusable).
- **Affordance bar + Ask AI routed through it** (faces 1.1/1.2/1.3) — the three capability tabs
  (Documents/Structured/Agent) in `UnifiedChatView.renderAffordanceBar` are now `<jf-control>` with
  `.availability=${tabAvailability(...)}` (`isStreaming` → `blocked`; else `projectAvailability`); the
  suppressed `title` ternary is GONE; active styled via `jf-control.affordance-btn.active::part(control)`.
  "Ask AI" (`SearchSurface`) is a `<jf-control>` with `.availability`, and `handleAskAi` now HONORS
  `compose()`'s `false` (toast) — the discarded boolean is gone (face 1.2 primitive-closed, §14/U3).
- **Chat degradation banner** (§11.4 standalone win) — `UnifiedChatView.renderDegradationBanner` mirrors
  `SearchSurface`'s (same `readinessNotice` projection + `<jf-system-notice>` + `<jf-op-button>`/nav remedy),
  mounted above the affordance bar, reading the SAME `aiState.readiness` authority — closes the §11.2
  cross-window asymmetry.
- **Gate dimension** (`check-controls-a11y.mjs`, §14/U3 static half) — a `<tag>` carrying BOTH a
  `?disabled`/`disabled` binding AND a `title=` (excludes `aria-disabled`) fails. Introduced as a
  SHRINKING per-file baseline ratchet (`controls-a11y-disabled-title-baseline.v1.json`, `--rebalance`) —
  the idiomatic way to gate over pre-existing debt: NEW instances hard-fail; 11 pre-existing instances
  across 7 OUT-of-scope surfaces (§5) are pinned for migration (logged to `observations.md`). The
  in-scope affordance bar removed its instances, so the bar cannot regress.
- **Out of scope / deferred:** the 11 pinned title-on-disabled debts (separate surfaces, §5); adopting
  595's severity-aware verdict once 595 lands (the seam is stable, §14/U1 — no rework needed); `aria-pressed`
  forwarding on `jf-control` (not needed — active state styles via host-class `::part`).

### 15.1 Follow-up — the VISIBLE reason-surface (the §9.4 "missing primitive", 2026-06-17)

A conceptual re-review found the first pass delivered the reason to screen readers (`aria-describedby`)
and on click (toast), but **not as a *visible* hint** for sighted users hovering/focusing an unavailable
control — leaving the §1.1 symptom (*"hovering it surfaced no tooltip explaining why"*) partly open. Built
the visible reason-surface §9.4 named, entirely inside `jf-control`: the reason element is now a
`role="tooltip"` host SIBLING of the button (so the button's `aria-disabled` `opacity:0.5` doesn't dim
it), styled from existing tokens (`--surface-3` / `--border-subtle` / `--shadow-lift` / `--z-overlay-transient`),
hidden by `opacity:0` (NOT `display`/`visibility`, so it stays the `aria-describedby` AT target) and
revealed on `:host(:hover)` / `button:focus-visible` via CSS. A CSS-only reveal — no new component, no
`TransientController` registration (`check-transient-arbitration.mjs` exempt, green). Verified live:
hovering an unavailable Documents tab shows the **"AI is offline"** tooltip (screenshot),
`getComputedStyle().opacity === '1'` while hovered, `role="tooltip"`, `aria-describedby` retained; full
ui-web suite (3022) + controls-a11y + transient-arbitration gates green. Every `jf-control` affordance
inherits it by construction.

### 15.2 As-built — §17 remaining work (one reason authority + WCAG-1.4.13 tooltip, 2026-06-17)

Both §17 moves shipped, scope-matched (no §16 menu items). Static green (typecheck · full ui-web suite
**3022** · `controls-a11y` · `transient-arbitration`) + live-browser-verified against the running stack.

- **Move A — one reason-and-remedy authority, two scoped projections.** Lifted the private
  `readinessNotice.CAUSE_ROWS` (reason-code → `{ wording, remedy? }`) + `OPEN_HEALTH` + `NoticeRemedy`
  into a new shared `state/reasonVocabulary.ts` exporting a single `reasonFor(code)` read-seam (+ one
  FE-derived `no_documents` row the backend doesn't emit). `readinessNotice` now consumes it (system
  scope; output byte-identical — its 8 tests pass untouched). `availability.ts` **deleted its
  `REASON_WORDING` fork** and projects per-gap via `reasonFor` (control scope), and its `unavailable`
  variant gained `remedy?: NoticeRemedy`. So the control's reason and the window's reason — and their
  remedies — now read the ONE table (the shape 594 shipped; the seam 595 §10.5 will add `severity` to).
  The blocked-activation toast now **points at the remedy** (§11.4) via `emitEphemeralToast`'s existing
  `actionLabel`/`onAction` — a `navigate` remedy dispatches `navigate-with-context`; an `operation`
  remedy points at Health (where the op-button + consent/risk ceremony live) rather than firing it
  from a toast.
- **Move B — the reason-surface is a genuinely reachable WCAG-1.4.13 tooltip.** In `jf-control` only
  (one consumer; no new component, no `TransientController` — a tooltip is point-wise, like the
  unregistered `HoverPreviewHost`). The reason element is `popover="manual"` where the Popover API is
  available (top-layer ⇒ **collision-safe, never clipped**), with a small self-contained controller:
  show on host hover/focus + JS position above/flip-below, **Esc to dismiss**, and a **hover bridge**
  (the tooltip's own `mouseenter` cancels the close). Feature-detected — the §15.1 CSS reveal remains
  the no-popover fallback; `aria-describedby` + `role="tooltip"` always present for AT.
- **Live evidence (DOM probes + screenshot, AI-offline backend).** Fork dissolved: the tab tooltip reads
  the SHARED **"The local AI model is offline"** (not the old forked "AI is offline"); `popover="manual"`,
  `role="tooltip"`. 1.4.13: `:popover-open` true on hover, `getBoundingClientRect` on-screen above the
  control (collision-safe), `opacity 1`, **Esc dismisses** (at the document capture listener, as a real
  composed Escape arrives), and the **hover bridge keeps it open**. Remedy: clicking the offline tab
  emits a toast `{ message:"The local AI model is offline", actionLabel:"Open Health", onAction }`.
- **Files:** NEW `state/reasonVocabulary.ts`; `state/readinessNotice.ts`, `state/availability.ts`
  (+ test), `components/Control.ts` (+ test). Unmerged on `worktree-596-availability`.

### 15.3 Merge reconciliation with 595 — and the SHIPPED shape (merged to main 2026-06-17, `1cfcd504e`)

596 merged into `main` after 595 (SystemHealthVerdict + Stability) and 594 (projectFact) had already
landed. The merge **realized §17's predicted convergence** and corrected the as-built accordingly:
- 595 made `readinessNotice` the reason-vocabulary OWNER (`CAUSE_ROWS` gained a `severity` column;
  `severityForCodes` is consumed by the verdict). So a SEPARATE `reasonVocabulary.ts` would now **fork
  against** that established authority. **`reasonVocabulary.ts` was DELETED**; instead `readinessNotice`
  gained an exported **`reasonFor(code) → { wording, remedy? }`** seam (+ the `no_documents` row), and
  `availability.ts` imports `reasonFor` from `readinessNotice`. Net: the §17 "one reason-and-remedy
  authority, scoped projections" is realized by `readinessNotice.CAUSE_ROWS` feeding **three** scopes —
  the 595 verdict (`severityForCodes`), the window banner (`readinessNotice`), and the per-affordance
  availability (`reasonFor`) — exactly the convergence §17.2 named.
- The banners (`SearchSurface` + the §11.4 chat banner in `UnifiedChatView`) now consume the ONE 595
  verdict (`readinessNotice(verdict)`); the chat banner + Ask-AI availability layer on top.
- Verified on `main` post-merge: typecheck clean · full ui-web suite **3095** · controls-a11y +
  transient-arbitration gates · production web build all green.

---

## 16. Forward research — the design space the availability substrate opens (research-only survey, 2026-06-17)

> **Genre note** (mirrors 594 §18). A *survey of what the shipped substrate enables* — polish, simplify,
> extend, new UX — grounded in external best-practice research. Nothing here is committed; it is the
> menu, ranked by value/fit, with anti-patterns flagged. The app is pre-production with no users, so all
> of it is viable and none is urgent. Sources cited inline.

### 16.1 What the research VALIDATES (we got the core right)
The shipped design matches current best practice on every axis I could find:
- **`aria-disabled` (keep focus) + a reachable reason** is the recommended middle path between native
  `disabled` (drops focus, hides the why) and fully-enabled ([MDN aria-disabled], [CSS-Tricks: Making
  Disabled Buttons More Inclusive], [Smashing: Hidden vs. Disabled, 2024]).
- **Non-silent on attempt** (the click → toast) is the "enable and explain on attempt" school the
  literature increasingly prefers to a dead disabled control ([Smashing 2024]; Nielsen/[UX Tigers:
  Inactive GUI Controls]).
- **`role="tooltip"` + `aria-describedby` + reveal on focus (not just hover)** is the tooltip-a11y
  baseline ([Floating UI: Tooltip]; tooltip-UX guides).
- **One primitive ⇒ one consistent behaviour everywhere** — the research repeatedly names *consistency*
  as a top tooltip/disabled-state win; we get it by construction (the `jf-control` ownership), not by
  convention.
- **Our applicability(hide) vs availability(show+explain) split (§10/C3) IS the Smashing decision rule**:
  *"will the user EVER be able to use this? No → hide; Yes → disable + explain + say how to re-enable."*
  `Action.enabled` (hide inapplicable menu items) is the "hide" branch; `Availability` is the
  "disable+explain" branch. The substrate already encodes the recommended framework.

### 16.2 Polish (refine what shipped)
- **[Real a11y gap] WCAG 1.4.13 "Content on Hover or Focus" — dismissable + hoverable.** A *custom*
  tooltip (unlike native `title`, which is user-agent-exempt) must be **dismissable via Esc** and
  **hoverable** (the user can move onto it to read without it vanishing) ([WCAG 1.4.13]; [BOIA]). Our
  pure-CSS `:host(:hover)` tooltip is neither (no Esc; `pointer-events:none` + host-only hover means
  moving toward it closes it). Counter-intuitive but important: by making the reason *more* reachable we
  took on a 1.4.13 obligation the suppressed native `title` never had. The fix is a small tooltip
  controller (Esc handler + a hoverable bridge), or the native **Popover API** (`BookmarksPopover`
  already uses it) — both keep the CSS reveal for the common case.
- **Collision-aware positioning / flip.** The hint is pinned `bottom: 100%` (above); near the viewport
  top it can clip (it grazed the banner in the live shot). Flip-below-when-no-room (or CSS anchor
  positioning / Floating UI) ([Floating UI]).
- **Hover/focus open-delay** to avoid flicker on pass-through, ideally a *shared* delay across the bar
  (a named tooltip-UX win).
- **Touch support** — CSS `:hover` doesn't fire on touch; a tap-to-reveal path would be needed if the
  shell ever runs on a touch device (low priority for a desktop app).
- **Contrast** — fold the tooltip's `--surface-3`/`--text-primary` pairing into the existing
  `check-contrast-matrix.mjs` so it provably clears WCAG AA in both themes.

### 16.3 Simplify (consolidate)
- **One reason vocabulary.** `availability.ts`'s `REASON_WORDING` is a tiny parallel of
  `readinessNotice.CAUSE_ROWS`. Collapse them so the per-affordance reason and the window banner literally
  read one reason-code→wording source (the §11.3 "one vocabulary" goal, finished). When 595 lands its
  severity/tone table, this becomes the single adopter (§14/U1 seam).
- **Generalize beyond AI.** `Availability` is capability-agnostic, but `projectAvailability` only knows AI
  affordances. The same typed state fits *any* state-gated control — no selection, empty results,
  mid-operation, permission-pending — turning it into the app's one affordance-availability authority.

### 16.4 Extend the substrate (richer types)
- **[Top pick] A remedy on the affordance** — `unavailable{ reason, transient?, remedy? }`. The window
  banner already carries a remedy (*Reload AI* / *Force Rebuild*); the literature is emphatic that a
  blocked control should say **how to fix it**, not just that it's blocked ([Smashing]; GitLab's *"you'll
  be able to merge once approved"*). So the tooltip/menu of an offline "Ask AI" offers **[Reload AI]**
  inline — the affordance-level analog of `readinessNotice.remedy`, reusing the same `NoticeRemedy`
  (operation / navigate). Highest value-per-effort: turns every dead-end into a one-click fix.
- **Forward-looking reason / `availableWhen`.** *"Available once indexing finishes"* / *"Ready in ~10s"*
  reads far better than a bare "unavailable" (GitLab; Smashing's *"Export in progress…"*). Ties to the
  `transient` flag + 595's transition phase — the FE already knows the resolution condition for the
  loading case.
- **A `degraded` kind** — available-but-with-caveats (works, slower / lower quality), distinct from
  unavailable. The optional-reranker case (595's over-alarming "degraded") is exactly this: the affordance
  stays usable but the tooltip notes *"reduced ranking quality."* Keeps the binary honest.

### 16.5 New UX features (built on the substrate)
- **[Top delight] Queue-and-auto-run for *transient* unavailability.** Because `transient` distinguishes
  "still loading" (self-clears) from "offline" (settled), a click on a transient-unavailable affordance can
  **queue the action and auto-run it when ready** — *"Queued — runs when the AI is ready"* → auto-fire,
  with an explicit toast on success/rollback ([Optimistic-UI / offline-queue patterns]). Strictly
  transient-only (never queue a settled-offline action — that would mislead). A genuinely delightful use
  of the typed transient flag.
- **Remedy-driven onboarding.** An affordance unavailable for *"No documents indexed"* + remedy
  *"[Add a folder]"* turns a dead-end into the first-run path. Generalised: every capability gap becomes a
  guided step — first-run UX falls out of the availability authority for free.
- **A "what can I do right now" capability map.** Project *all* affordances' availability into one view
  (AI offline ⇒ Documents/Structured/Agent unavailable, Search available) — a single system-state
  explainer, the read-model the chat banner is one slice of.
- **Availability telemetry.** Centralised projection ⇒ one place to log *how often each affordance is
  unavailable and why* — a free product-health signal ("what blocks users") for when there are users.

### 16.6 Anti-patterns the research says to avoid (we already do)
- Never disable without an explanation ([Smashing]) — closed by the gate + the reachable reason.
- Don't auto-remove options the user might want ([Smashing]) — we show+explain; only *inapplicable*
  (never-usable) items hide, per the §16.1 framework.
- Don't optimistically queue/run a *settled*-unavailable action — only transient (§16.5 caveat).
- Avoid layout shift on reveal — the absolutely-positioned tooltip already avoids it.

### 16.7 If we did exactly one thing
**The affordance-level remedy (§16.4 top pick) + closing the WCAG 1.4.13 tooltip gap (§16.2).** Together
they complete the loop the whole tempdoc is about: an unavailable affordance not only *says why*
(reachably, compliantly) but *offers the fix* — the full "explain + remediate" the best-practice
literature converges on, delivered by the one primitive so every affordance inherits it.

**Sources:** [Smashing — Hidden vs. Disabled in UX (2024)](https://www.smashingmagazine.com/2024/05/hidden-vs-disabled-ux/) ·
[UX Tigers / Nielsen — Inactive GUI Controls: Show, Disable, or Hide?](https://www.uxtigers.com/post/inactive-buttons) ·
[MDN — aria-disabled](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-disabled) ·
[CSS-Tricks — Making Disabled Buttons More Inclusive](https://css-tricks.com/making-disabled-buttons-more-inclusive/) ·
[WCAG 1.4.13 — Content on Hover or Focus](https://www.w3.org/WAI/WCAG21/Understanding/content-on-hover-or-focus.html) ·
[BOIA — Tips for Meeting WCAG 1.4.13](https://www.boia.org/blog/tips-for-meeting-wcag-1.4.13-content-on-hover-or-focus) ·
[Floating UI — Tooltip](https://floating-ui.com/docs/tooltip) ·
[Optimistic UI patterns](https://simonhearne.com/2021/optimistic-ui-patterns/).

---

## 17. The correct long-term design for the remaining work (scope-matched, 2026-06-17)

> **Discipline note.** §16 is a *menu*; this section is the *judgment*. The rule: design structure only
> for what the present problem actually has. Most of §16 (availableWhen, a `degraded` kind, queue-and-
> auto-run, onboarding funnels, a capability map, telemetry, generalising beyond AI, touch/hover-delay)
> is flexibility for cases 596 does **not** yet include — explicitly OUT below. Exactly **two** items are
> genuine remaining work, because each is required to make a promise the tempdoc *already* makes true —
> and both **extend designs that already exist**, adding no speculative abstraction.

### 17.1 The real remaining problem, stated honestly
The core is shipped (typed availability, rendered by `jf-control`, all three faces closed). Two things the
tempdoc's *own* thesis requires are not yet true:

1. **A second reason authority drifted in.** The implementation introduced `availability.ts REASON_WORDING`
   — a reason-code→wording map *beside* `readinessNotice.CAUSE_ROWS`. It has **already drifted**: the
   affordance says *"AI is offline"* while the window banner, for the same `inference.offline` state on the
   same screen, says *"The local AI model is offline"* — and the affordance map **drops the remedy**
   (`core.reload-inference`) the banner carries. This is not untidiness; it is *the exact drift-class the
   594/595/596 line exists to eliminate*, now living inside 596's own code. Killing it is in-scope by the
   tempdoc's first principle, not by completeness.
2. **"Reachable reason" is not yet true for everyone.** The visible tooltip (§15.1) is a CSS-only reveal,
   so it fails WCAG 1.4.13: not **dismissable** (no Esc) and not **hoverable** (moving toward it closes
   it), and it can **clip** off the viewport top. A reason a keyboard/low-vision user cannot dismiss or
   read is not *reachable* — the tempdoc's literal promise (§2) is still partly unmet.

### 17.2 Move A — one reason authority, two scoped projections (dissolve the fork)
The structure already exists; it is just **private and single-consumer**. `readinessNotice.CAUSE_ROWS`
*is* the reason authority: reason-code → `{ wording, remedy? }` over the backend's closed vocabulary, with
`NoticeRemedy` already an exported, catalog-backed type. The correct end-state lifts that table to a
**shared reason vocabulary** that more than one site projects from:

- **One vocabulary** (reason-code → `{ wording, remedy? }`), the existing `CAUSE_ROWS` promoted from a
  `readinessNotice`-private const to a shared authority, growing the one row the affordance needs that the
  backend doesn't emit (`no_documents`).
- **Two scoped projections over it, no third vocabulary.** `readinessNotice` is the **system-scoped**
  projection (the window banner, keyed on `readiness.reasonCodes`). `projectAvailability` becomes the
  **control-scoped** projection: it maps each capability gap to a reason-code (`!chat → inference.offline`;
  zero-docs → `no_documents`; `connecting → inference.starting`, transient) and reads `{ wording, remedy }`
  from the one vocabulary. `REASON_WORDING` is deleted.

This is the **same shape 594 shipped** — *"`projectFact` unifies the projection (one resolver, one render
path) while keeping the sources distinct"* — applied to reasons instead of facts: one vocabulary, two
projectors whose *inputs* differ (backend codes vs FE capability state) but whose *wording+remedy* cannot.
Two payoffs fall out, both finishing intents the tempdoc already stated, neither new structure:
- The control's reason and the window's reason (and their remedies) **cannot drift** — the §8.2 / §11.3
  "one vocabulary" goal, actually achieved (today it is forked).
- Because the shared rows carry remedies, the affordance projection now yields `{ reason, remedy }` *for
  free* — so the §11.3/§11.4 "reason **+ remedy**, not reason alone" bar is reachable: the blocked
  affordance can offer *"AI offline → [Reload AI]"*, the control-scoped twin of the banner's remedy. (The
  required structure is the shared authority; *rendering* the affordance-side remedy is the natural
  completion that the authority enables — not a separate abstraction.)

**Convergence, not coupling.** 595 §10.5 independently plans "a per-reason tone/wording table … *like
`readinessNotice.CAUSE_ROWS`*" + a severity dimension. So the shared vocabulary is the seam **three**
scopes meet at — system verdict (595), window banner (`readinessNotice`), per-affordance (596). 596's move
is only *"stop forking against it."* 595 later adds `severity` to the *same* table; 596 needs nothing from
that to dissolve its fork now (the §14/U1 stable seam). This is alignment by sharing one authority, not a
dependency.

### 17.3 Move B — the reason-surface becomes a genuinely reachable tooltip (make the promise true)
"Reachable" must hold for keyboard and low-vision users, so the rendered reason must satisfy WCAG 1.4.13:
**dismissable** (Esc closes it without moving focus), **hoverable** (the pointer can move onto it to read
it), and **collision-safe** (it flips when there is no room above). This is a correctness completion of the
*existing* surface, not a new feature:

- It stays **inside `jf-control`** — there is exactly one consumer, so a general tooltip primitive would be
  speculative; the behaviour lives on the primitive that owns availability.
- It **reuses an existing mechanism**: the native **Popover API** (already used by `BookmarksPopover`)
  gives top-layer rendering (no clipping) + light-dismiss, or a minimal tooltip behaviour adds the Esc +
  hoverable bridge. Either keeps the §15.1 hover/focus reveal for the common case.

The honest framing the research surfaced: by making the reason *visible* we took on a 1.4.13 obligation the
suppressed native `title` never had (user-agent tooltips are exempt). Move B is paying that obligation —
the cost of the reason being genuinely reachable, which is the whole point of the tempdoc.

### 17.4 Why this scope, and not more or less
- **Not less:** both moves fix promises the tempdoc *already* makes (single authority; a reachable reason).
  Leaving the fork is leaving a live drift of exactly the class 596 condemns; leaving the tooltip
  non-compliant leaves "reachable" false for some users.
- **Not more:** everything else in §16 is structure for cases 596 does not have. A `degraded` kind, an
  `availableWhen` ETA, queue-and-auto-run, an onboarding funnel, a capability map, telemetry, and
  generalising `Availability` beyond AI are all real *possible* features — but the present problem ("an
  unavailable affordance must surface its reason") needs none of them, and adding them now is flexibility
  for a future that has not arrived. They remain a recorded menu (§16), not a design.

### 17.5 End-state, one paragraph
The window degradation banner and the per-affordance unavailability are **two scoped projections of one
reason-and-remedy authority** — the `reason-code → { wording, remedy? }` vocabulary `readinessNotice`
already embodies, promoted from private to shared — so a control's reason and the window's reason cannot
disagree and the affordance inherits its remedy; and the surface that renders the reason is a genuinely
**reachable** tooltip (WCAG 1.4.13 dismissable + hoverable + collision-safe), owned by `jf-control`,
reusing the Popover mechanism the codebase already has. That is the whole of the correct long-term design
for 596's remaining work: dissolve the one fork the implementation introduced, and make the one promise the
tempdoc makes — a *reachable* reason — actually true. No new authorities, no speculative kinds, no features
the present problem does not contain.

---

## 18. As-built — the §16 forward-research menu + the 11 a11y debts, IMPLEMENTED (2026-06-17)

> Genre: a later turn directed implementing **all** remaining work (the §16 menu — previously deferred as
> a pre-production menu — plus the 11 tracked title-on-disabled a11y debts). Built in worktree
> `596-remaining` on a base that merged the shipped §17. Every item extends the existing authority; no new
> route surface, no second reason vocabulary. 3120 ui-web unit tests green; ~25 governance gates green;
> production build green; live-verified in the browser for the demo-reachable states.

### 18.1 What shipped
- **The 11 a11y debts → 0 (Phase 1a).** `jf-button` gained an additive `availability` passthrough to its
  composed `jf-control`; the 9 `jf-button` sites (EffectAuditLog ×4, AdvisoryInboxDrawer, BrainSurface,
  BrowseSurface, HealthSurface, UnifiedChatView Steer) + the 2 raw `<button>` Shell.ts nav buttons now use
  typed `.availability` (a reachable reason) instead of a `title` suppressed on a disabled control. New
  helper `unavailableBecause(reason, transient?)` for LITERAL local-gap reasons (not the CAUSE_ROWS
  capability vocabulary). `controls-a11y-disabled-title-baseline.v1.json` rebalanced to `{}` (0 pinned).
- **Tooltip polish (Phase 1b).** A shared hover open-delay (`HOVER_OPEN_DELAY_MS`, focus stays instant);
  the touch path is the activation toast (documented — a tap on an aria-disabled control fires the reason
  toast, a better touch affordance than a hover tooltip); the tooltip's `text-primary`-on-`surface-3`
  pairing folded into `check-contrast-matrix.mjs` (clears AA both themes — 34 pairings).
- **Condition-based `availableWhen` (Phase 1c).** A zero-doc `documents` affordance WHILE indexing is in
  flight (`runtime.mode==='indexing'` / `index.pendingJobs>0`) reads the TRANSIENT forward-looking
  "Indexing in progress — available once indexing finishes", not the settled "No documents indexed yet".
  No numeric ETA (none exists in the snapshots — D1); the condition is stated, never a fabricated "~Ns".
- **Onboarding remedy (Phase 1d).** The shipped `no_documents` → "Add documents" navigate remedy
  (`core.library-surface`) is rendered by `jf-control`'s remedy toast; pinned by a unit assertion.
- **`degraded` kind (Phase 2a).** New `Availability` kind `{kind:'degraded', caveat, remedy?}` — OPERABLE
  (fires `onActivate`) with the caveat on the same reachable tooltip. Projects by CONSUMING the ONE 595
  verdict (`s.verdict.kind==='degraded'`, which `computeVerdict` emits exactly for retrieval degradation),
  NOT re-deriving from `readiness.retrieval` (that forked the verdict authority — caught + fixed by the
  `verdict-derivation` gate). Severity calibrates tone (info = calm, warn = keyword-fallback).
- **Generalize beyond AI (Phase 2b).** Realized as the explicit TWO-TIER authority: the capability-agnostic
  `Availability` type + `unavailableBecause` (now the home of 6+ non-AI gates) vs the AI-store
  `projectAvailability`. Per AHA, the AI projector and arbitrary local gates are NOT one signature.
- **Queue-and-auto-run (Phase 3a).** A click on a TRANSIENT-unavailable control queues the host's
  `onActivate` and auto-fires it when the control becomes operable (success toast); a settle-to-non-transient
  block drops the queue with an honest rollback toast. Re-firing replays the host closure — no seam bypassed.
- **Capability map (Phase 3b).** "What you can do right now" — `jf-capability-map`, a read-only projection
  of every affordance's availability + inline remedy, EMBEDDED in the Health surface (the system-state home)
  rather than a new route surface (the "extend, don't invent" cut: no Java catalog / i18n / route churn).
- **Availability telemetry (Phase 3c).** `state/availabilityTelemetry.ts` — a localStorage ring (mirrors
  `resolutionTelemetry`) recording a user's blocked ATTEMPT at the one block site in `jf-control.activate`
  ("what blocks users"); `summarizeBlocks()` aggregates by reason. No remote flush (loopback, no users).

### 18.2 Out (with reason)
- **Numeric ETA** ("~10s") — no progress/ETA field exists in `inferencePoll`/`statusPoll`; would need a new
  backend signal. The condition-based wording (18.1 Phase 1c) is the honest FE-only realization.

### 18.3 Verification
Static: `npm run typecheck` clean; `npm run test:unit:run` 3120 pass (incl. new availability/Control/
Button/CapabilityMap/availabilityTelemetry cases); production `npm run build` green; gates green —
controls-a11y (baseline 0), contrast-matrix (+tooltip pairing), verdict-derivation, layout-purity,
a11y-closure, surface-composition, presentation-purity, color-tokens, message-single-model, atom-fork /
style-literal / ambient ratchets, modality/transient/modal arbitration, search-issuance, intent-tier,
run-renderers, inflight-liveness, composition-surfaces, declared-surfaces, steering-arbitration.
Live (worktree Vite proxied to the REAL backend, DOM-probed not just screenshotted):
- **AI online + retrieval degraded:** the Health CapabilityMap renders every affordance row; `documents`
  shows the **degraded** caveat ("An optional ranking model is unavailable — results are complete, ranking
  may be simpler", warn tone) consistent with the chat degradation banner (one authority).
- **AI offline** (took over the dev stack with user approval, restarted with AI unactivated): the
  Documents/Structured/Agent affordance tabs are `aria-disabled` (focusable, `nativeDisabled:false`) with
  the reachable shared reason **"The local AI model is offline"** on a `role="tooltip"`; the migrated
  buttons show their typed reasons live (`Go forward` → "No later view to go forward to"; `Undo last/all
  AI actions` → "No reversible AI actions to undo"; `Save selected (0)…`). Clicking an unavailable control
  emits the reason TOAST (non-silent) AND records an **availability-telemetry** entry
  (`{reason, transient:false}`). Re-activating AI flips the CapabilityMap rows back to Available (the
  offline→online transition).
- **Live gap (residual, unit-test-covered):** queue-and-auto-run (its transient window is the sub-second
  initial-connect phase), the indexing-in-progress wording (needs an active reindex), and the no-docs
  remedy render (needs an empty index) were not hand-driven live — each is pinned by a unit test, and the
  reachable-tooltip + remedy mechanism they ride is the §17 one already validated live.
