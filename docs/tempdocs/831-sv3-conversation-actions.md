---
title: "831 — Search v3 conversation actions: the status→action slot swap"
type: tempdocs
status: "IMPLEMENTED (2026-08-14) — built, unit-tested, gate-checked and live-measured in a backendless browser."
created: 2026-08-14
updated: 2026-08-14
author: agent session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1
category: frontend / presentation-authority / Search v3 window
related:
  - 822 Phase F3 (the original one-control swap, and the never-yields exception it wrote)
  - 822 Phase F5 (the rename triad this reuses rather than re-authoring)
  - 822 Phase F6 (the persistence boundary: existence is the conversation store's)
---

# 831 — Search v3 conversation actions

The Search v3 sidebar row had ONE action (the pin) hiding in its trailing slot. This makes the slot
carry an action **set** — rename, pin, discard — without breaking the two laws Phase F3 wrote into
that slot: the honesty facts never yield, and the row never changes height.

## What was built

| Piece | Where |
|---|---|
| `Sv3RunGate` + `sv3SessionIsLive` + `removeSession` | `modules/ui-web/src/shell-v0/views/search-v3/sv3-sessions.ts:698`, `:714`, `:738` |
| `live` on the row projection | `sv3-sessions.ts:870` (`Sv3SessionRowView.live`), derived at `sv3-sessions.ts:943` |
| The action set (markup + CSS) | `Sv3SessionRow.ts:244-325` (styles), `Sv3SessionRow.ts:652-690` (template) |
| `SV3_SESSION_REMOVE_REQUEST` + `discard` | `Sv3SessionRow.ts:66`, `Sv3SessionRow.ts:504` |
| Panel re-raise `SV3_SESSION_REMOVE` | `Sv3Sidebar.ts:52`, `Sv3Sidebar.ts:264` |
| Window handler + write-through | `SearchV3View.ts:1853` (`runGate`), `SearchV3View.ts:1881` (`onSessionRemove`) |

### The action set

Three controls, each backed by an operation the session list really has — no control asks the window
for something it cannot do:

- **Rename** raises the SAME `sv3-session-rename-start` the F5 double-click and `F2` raise. It is a
  second affordance for one intent, not a second rename path; the title is still decided once, in
  `resolveSv3Rename`.
- **Pin** is the existing `toggleSessionPin`. It kept its class, its `data-testid` and its
  `aria-pressed`; it moved into the set rather than being duplicated beside it.
- **Discard** is new (`removeSession`), and is the only piece of store surface this work added.

### The never-yields rule, as implemented

Phase F3's exception was written for one control; it holds unchanged for three:

- Only the **time label** yields. Each yield rule is guarded
  `:not([status='act-now']):not([status='broken'])`, so an act-now or broken status keeps its place
  and its opacity while the row is hovered or focused (`Sv3SessionRow.ts:218-237`).
- The actions appear **beside** those statuses, in a gutter reserved **at rest**
  (`Sv3SessionRow.ts:245-249`). Reserving it on hover would move the dot, which is the jitter the
  slot's width floor exists to prevent.
- The gutter's width is the action set's own width, as a host-scoped token
  (`--sv3-row-actions-inline`, `Sv3SessionRow.ts:84-88`) rather than a copied figure — so the gutter
  cannot come to measure a set that no longer exists. A live conversation offers no discard, so the
  token is one square narrower there (`:host([live])`).
- In-motion **does** yield: it is not an honesty fact in the same sense (nothing is blocked on the
  reader), and the spec's rule names act-now and broken only.

### The safety gate on discard

A conversation with work in flight is never removed. The judgement is ONE predicate,
`sv3SessionIsLive`, consulted by both halves:

- the **projection** sets `Sv3SessionRowView.live`, which withholds the control (withheld, not
  disabled — a present-but-inert control asks the reader to find out by pressing it), and
- `removeSession` **refuses** a live id, returning the same list.

Because the row projection derives its status colour from the same predicate, `live` is exactly
"act-now or in-motion" by construction rather than by agreement — `sv3-sessions.test.ts` walks a
matrix and asserts all three readings agree, including that the matrix produced at least one live row
(so it cannot pass on vacuity).

Three ways to be live, and they are three different things: a delegated run parked on the reader's
decision, a turn still streaming, and the process-wide search flag (which only the ACTIVE session may
claim, since the store cannot say who asked).

### Where a discard is written

Existence is the conversation store's (822 F6's persistence boundary), so `onSessionRemove` writes
the deletion through to `deleteConversation` — the same shape the rename write-through takes. If the
authority declines, the next list load projects the row back through `mergeStoreConversations`: the
window never claims a deletion that did not happen. Discarding the conversation **on screen** routes
through `onSessionNew` first, so the window leaves the transcript by the one exit that already clears
every pointer, rather than through a second partial teardown.

## Decisions worth recording

1. **`live` is a new row attribute, not a reuse of `inflight`.** `inflight` exists on the row (a
   dimming treatment) and is wired by nothing; `live` is specifically the destructive-action gate.
   Reusing `inflight` would have switched on a dimming that has never been active. The unwired
   `inflight` prop is logged to the observations inbox rather than fixed here.
2. **`discard`, not `remove`, as the method name.** On an `HTMLElement` subclass a private
   `remove()` shadows `Element.remove()`; the typechecker caught it, and the name change is the fix
   rather than a cast.
3. **The row's event is `sv3-session-remove-request`; the panel's is `sv3-session-remove`.** The same
   two-name shape the rename triad uses — the row raises intent, the panel names the row.
4. **Distinct event names over a shared one** so the panel's re-raise cannot be mistaken for (or
   re-caught as) the row's own.

## What the critical-analysis pass caught

The action set is an absolutely-positioned box over the row's trailing strip. As first written the
GROUP was pointer-targetable, so at rest — when it shows nothing to press — it would have swallowed
the claim click over the row's right-hand ~72px, and only the buttons inside it had
`pointer-events: none`. Fixed by making the group itself untargetable
(`Sv3SessionRow.ts:268-272`); a child that re-enables itself still receives events, which is exactly
what the reveal does. Measured live with `elementFromPoint`: at rest the trailing strip resolves to
the row's own claim button on all three rows, and on hover it resolves to the discard. Pinned in CI
by a CSS-text case (`sv3-tokens.test.ts`, "never lets the action GROUP take a hit the row should have
had"), since CI has no browser.

The same pass also collapsed a second projection of the run per render: `runGate()` now takes the
already-projected run the render pass made, instead of projecting the feed twice a frame.

## Tests changed rather than added, and why

Three existing cases pinned the ONE-pin shape. Their intent survives; the literal did not:

| Case | Change |
|---|---|
| `sv3-tokens.test.ts` "reserves the pin gutter at REST" | now asserts the gutter reserves `var(--sv3-row-actions-inline)` **and** that the token is the set's width (3 squares, 2 while live) — strictly more than the old `var(--space-7)` literal. |
| `sv3-tokens.test.ts` "keeps the pin action out of the pointer's way" | now asserts the shared `button.act` rest state, and additionally that NO per-action base rule exists — so a fourth action cannot be added visible-at-rest by forgetting a rule. |
| `SearchV3View.sidebar.test.ts` icon-rail case | `button.pin` → `.actions` in the compact-hide rule. |

No assertion was weakened; each of the three gained a claim.

## Evidence

**Unit** — `cd modules/ui-web && npm run typecheck && npm run test:unit:run`: typecheck clean,
**420 files / 5068 tests green** (the window's own subset: 21 files / 515 tests, up from 20 / 490).
New: 13 cases in `Sv3SessionRow.actions.test.ts`, 7 in
`sv3-sessions.test.ts` (`describe('a conversation can be discarded…')`), 5 window-level mutation
probes in `SearchV3View.sessions.test.ts` (`describe('the row actions each change the state they
name')`).

**Mutation probes** (every action changes the state it names, driven through the affordance):
rename → the stored title changes (the row's label is a projection of the record); pin → shelf
membership moves Recent↔Pinned; discard → the row leaves the list **and** a `DELETE
/api/chat/conversations/…` is issued; discard of the on-screen conversation → the transcript empties
too; a streaming conversation offers no discard and gains one the moment it settles.

**Live, backendless vite (`npx vite --port 5176`) + Chromium** — the measurement that CSS-text cases
cannot make (822 F3's static-green/live-dead defect):

| Row | state | height | time label | status dot | actions |
|---|---|---|---|---|---|
| resting | rest / hover / focus | 36 / 36 / 36 | 1 → **0** → **0** | — | 0 → 1 → 1 |
| broken | rest / hover / focus | 36 / 36 / 36 | — | **1 / 1 / 1** | 0 → 1 → 1 |
| act-now | rest / hover / focus | 36 / 36 / 36 | — | **1 / 1 / 1** | 0 → 1 → 1 (no discard) |

Plus: the act-now and broken dots do not MOVE between rest and hover (identical rects), each dot is
the top element at its own centre (`elementFromPoint`) so it stays clickable, and the actions box
never overlaps the dot box. Keyboard: a real TAB walk reaches
`row button → rename → pin → discard`, each at opacity 1 with `pointer-events: auto` when focused.
Zero page errors in the seed/measure/hover/focus phases; the one console error observed during a
synthetic 60-Tab sweep of the whole shell is a surface-swap view-transition timeout from
`shell-v0/chrome/viewTransition.ts` (untouched here), and a control run that boots the window and
touches nothing shows only the absent-backend fetch failures.

Artefacts (scratchpad, not committed): `sv3-actions-resting.png`, `sv3-actions-hover.png`,
`sv3-actions-hover-broken.png`, `sv3-actions-focus.png`, `sv3-actions-measure.json`.

**Gates** — the `ui-web-gates` recipe ran green: presentation-purity, observed-state-collapse,
color-tokens, a11y-closure, adaptive-closure, layout-purity, surface-composition, inflight-liveness,
message-single-model, run-renderers, composition-surfaces, declared-surfaces, live-channels,
contrast-matrix, offline-single-sense, gen-token-names --check, gen-component-vocabulary --check,
steering-arbitration, search-issuance, verdict-derivation, ai-verdict-derivation, message-classes,
capability-availability, realized-capability, consequence-classification, folder-status-derivation,
surface-task-state-retention, thread-event-kinds, ui-step-coverage, and the kernel gates
(ambient-purity, style-literal-ratchet, atom-fork-ratchet, modality-contract, transient-arbitration,
modal-arbitration).

Three gates are RED on files this change does not touch, and were RED before it:
`check-theme-token-closure` and `strip-token-fallbacks --check` (RecentsMenu.ts / ActionLedgerView.ts
— the first is a recorded expected-state), `check-accent-as-text` (ActionLedgerView.ts — also
recorded), `check-controls-a11y` (UnifiedChatView.ts:2096). None of those files appears in this
branch's diff.

## Not verified

- **The DELETE round trip against a real backend.** The write-through is asserted at the fetch
  boundary (the request is issued with the right method and path); no dev stack was started, so the
  server's own behaviour — and the "authority declined, the row comes back" path — is reasoned from
  `deleteConversation`'s contract, not observed.
- **act-now, end to end.** In the live check the act-now row was driven through the row's own inputs
  (`row.status`/`row.live`), because the window derives act-now from a delegated run parked on a
  decision, which needs a backend. The window-level derivation itself is covered by unit tests.
- **No confirmation step on discard.** Discard deletes immediately; there is no undo. That matches
  the window's other row actions, but it is a product decision worth a second look.
