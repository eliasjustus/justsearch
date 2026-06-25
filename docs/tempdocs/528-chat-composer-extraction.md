---
title: Chat composer extraction
type: tempdoc
status: shipped
---

# 528 — Chat Composer Extraction (`<jf-composer>`)

**Date**: 2026-05-19
**Status**: shipped
**Origin**: Side-quest. Not derived from `527-substrate-consumer-audit`
§8 — the user prioritized this over 527's named follow-ups. Logging it
here so the work has a contract.

---

## Motivation

Six chat-shaped views (`FreeChatView`, `AskView`, `NavigateView`,
`SummarizeView`, `UnifiedChatView`, `AgentView`) each carry an
ad-hoc input row: textarea + submit button + keyboard handler. The
four simple views — Free, Ask, Navigate, Summarize — duplicate the
same `.composer` CSS block (~50 LOC each) and the same Lit
markup (~15 LOC each) with cosmetic differences (button label,
placeholder, monospace font, submit-mode). UnifiedChatView extends
the pattern with cancel-while-streaming; AgentView reuses the
keyboard helper but otherwise diverges (icon-only square button, app-
shell footer layout).

The extraction:

1. **Removes the four-way duplication** of textarea+button markup and
   CSS from the simple views.
2. **Establishes one substrate slot** (`<jf-composer>`) for the chat-
   input pattern, with the four simple views as the founding
   consumers (C-018-satisfied on day one, no speculative slot).
3. **Adds cancel-while-streaming as an opt-in** (`?cancellable` flag
   + `composer-cancel` event) — UnifiedChatView is the first
   consumer; flipping it on for the four simple views is a deferred
   single-line slice once user-facing cancel is a goal.

AgentView is **not** migrated. Its composer is structurally tied to
the agent-pane footer layout (border-top, padding), uses an icon-only
square button, and locks the textarea size. Migrating would cost
three new flags (`submit-icon`/slot, `compact`, `fixed-size`) on
`<jf-composer>` for one consumer with no plausible second.
Documented as a non-goal in §Out-of-Scope.

---

## Substrate decision

`<jf-composer>` is a new substrate slot in the shell-v0 component
catalog. C-018 named-consumer check:

| Consumer | Migrated? | Lands in |
|---|---|---|
| `FreeChatView` | yes | Phase 1 (shipped) |
| `AskView` | yes | Phase 1 (shipped) |
| `NavigateView` | yes | Phase 1 (shipped) |
| `SummarizeView` | yes | Phase 1 (shipped) |
| `UnifiedChatView` (`.composer-row` only) | yes | Phase 2 (this tempdoc) |
| `AgentView` | **no — non-goal** | n/a |

Five concrete consumers at the moment substrate ships. No phantom slot.

---

## Implementation

### Phase 1 — Extraction + four simple views (shipped)

Already on `main` at the time this tempdoc opened. Recap:

- New file `modules/ui-web/src/shell-v0/components/Composer.ts` —
  `JfComposer` (Light DOM via `createRenderRoot() { return this }`) +
  exported `composerStyles` `CSSResult`.
- Props: `value`, `placeholder`, `streaming`, `submitDisabled`,
  `submitLabel`, `streamingLabel`, `mono`, `rows`, `submitMode`
  (`'enter' | 'ctrl-enter'`).
- Events: `composer-input` (with `{ value }` detail),
  `composer-submit`.
- `FreeChatView` / `AskView` / `NavigateView` / `SummarizeView`
  updated to `<jf-composer>` + `composerStyles` in
  `static styles = [composerStyles, css\`…\`]`.

Light DOM choice rationale: existing view tests query
`shadowRoot.querySelector('textarea' | 'button')`. Light DOM means
the composer's children live in the view's shadow tree, so the
selectors continue to resolve unchanged. Tradeoff: composer styles
must come from a CSSResult shared with each consumer, not from
`static styles` on the composer itself.

Visual change: FreeChatView's textarea `min-height` shifted
`2.5rem → 3rem` (matching the other three). Acceptable normalization.

Verification:
- `npm run typecheck` — pass.
- `npm run test:unit:run` — 1603/1603 pass, including the four
  affected view test files (`AskView.test`, `NavigateView.test`,
  `SummarizeView.test`, `FreeChatView.restartBanner.test`).

LOC accounting (honest): 4 views went 1694 → 1523 (−171); new
`Composer.ts` is 172. **Net repo LOC: flat.** The win is
structural, not raw. The `~200 LOC saved` framing in the original
task description was wrong.

### Phase 2 — `?cancellable` + UnifiedChatView migration

Goal: migrate the inner `.composer-row` markup in UnifiedChatView to
`<jf-composer>` without touching the affordance-bar, the affordance
preview, or the schema-input textarea (those remain inline).

**Composer API extension:**
- Add `cancellable: boolean` property (attr `cancellable`).
- When `cancellable && streaming` is true, the button renders:
  - text = `cancelLabel` (default: `Cancel`), reflected via attr
    `cancel-label`. UnifiedChatView passes `Stop` when the model
    has produced any output, `Cancel` otherwise — that selection
    happens in the view, not in the composer.
  - CSS class `cancel` applied to the button (so a shared
    `.composer button.cancel { background: var(--accent-danger,
    #ef4444) }` rule in `composerStyles` paints it red).
  - Click / Enter / Ctrl+Enter fires `composer-cancel` instead of
    `composer-submit`.
- The default (`cancellable=false`) path is unchanged: button stays
  a styled submit button and shows `streamingLabel` while disabled.

**Style addition** in `composerStyles`:
```css
.composer button.cancel {
  background: var(--accent-danger, #ef4444);
}
.composer button.cancel:hover {
  filter: brightness(1.05);
}
```

**UnifiedChatView migration:**
- Drop the inline `.composer textarea`, `.composer button`,
  `.composer button.cancel`, `.composer textarea.mono` rules. Add
  `composerStyles` to `static styles`.
- Keep `.composer` (outer column flex) and `.composer-row` (inner
  row) **own** rules — they describe the multi-row layout, not the
  composer-row's textarea+button widget, so they don't conflict with
  the imported `.composer` row-flex from `composerStyles`. The
  class-name collision is real but resolved by CSS source-order:
  UnifiedChatView's rules come after the imported ones and win.

  Edge case: the imported `composerStyles` `.composer` selector
  applies a row flex with gap 0.5rem to UnifiedChatView's outer
  container. UnifiedChatView's later `.composer { display: flex;
  flex-direction: column; gap: 0.35rem }` overrides it. Net effect:
  unchanged.
- Replace the inner `<div class="composer-row">…</div>` with a
  `<jf-composer ?cancellable .value=… …>`. The `composer-row` div
  goes away.
- Wire `@composer-input` → `this.inputDraft = e.detail.value`.
- Wire `@composer-submit` → `void this.send()`.
- Wire `@composer-cancel` → `this.abortController?.abort()`.
- Keep schema-input (`renderSchemaInput`) inline — it's a textarea
  with no submit button and a different rows count; not in scope for
  `<jf-composer>`.

**Behavior preserved:**
- Cancel button text: `Stop` if streaming has produced output,
  `Cancel` if it hasn't yet. View computes the label, passes via
  `cancel-label`.
- Send button text: `Send` if `aiState.capabilities.chat`, else
  `AI Offline`. View computes via `submit-label`.
- Send button disabled when `!inputDraft.trim() ||
  !aiState.capabilities.chat`. Passed via `?submit-disabled`.

**Verification (Phase 2):**
- `npm run typecheck`.
- `npm run test:unit:run` — `UnifiedChatView.test.ts` must
  continue to pass. The current tests use
  `shadowRoot.querySelector('textarea')` / `'button'` style probes,
  which continue to work under Light DOM.

### Phase 3 — Flip `?cancellable` on the simple four (shipped)

Reversed the earlier "defer" decision per the user directive
"Everything in its scope is in your scope; do not defer items
because they are substantial or inconvenient." The cancel-while-
streaming capability exists in `<jf-composer>` (Phase 2); each view
already owns an `AbortController` that was only being used on
`disconnectedCallback`. Wiring user-facing cancel exposes existing
capability with no plausible downside.

Per-view diff (3 lines):
- `cancellable` attribute on `<jf-composer>`
- `cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}`
- `@composer-cancel=${() => this.abortController?.abort()}`

Applied to FreeChatView, AskView, NavigateView, SummarizeView.

---

## Out-of-Scope (with rationale)

- **AgentView.** Different layout role (`.input-row` carries
  app-shell footer chrome), icon-only square submit button, locked
  textarea size, different surface tokens. Migration cost is three
  new flags (`submit-icon`/slot, `compact`, `fixed-size`) on
  `<jf-composer>` for one consumer. No plausible second consumer.
  Violates 512 §F1 if migrated. **Decision: leave as-is.**

- **UnifiedChatView's schema-input textarea.** A standalone mono
  textarea with no submit button. Adding `?hide-submit` to
  `<jf-composer>` for one consumer is speculative. **Decision:
  leave inline.**

- **AgentSurface / other agent panes.** Not investigated; same
  reasoning as AgentView applies if structurally similar.

---

## Verification gate

| Item | Tier | Result |
|---|---|---|
| `npm run typecheck` (Phase 1+2+3) | auto | pass |
| `npm run test:unit:run` (Phase 1+2+3) | auto | 1615/1615 pass (1603 pre-existing + 12 new `Composer.test.ts` cases) |
| FreeChatView: send + stream + Stop button aborts | live | 2026-05-19 — verified via `claude-in-chrome` MCP. Stream reached 2759 chars; Stop click aborted; button reverted to "Send". |
| AskView: send + stream + Stop button aborts | live | 2026-05-19 — verified. Stream reached 2303 chars; Stop click aborted. |
| NavigateView: composer renders with `?cancellable` | live | 2026-05-19 — verified via programmatic mount of `<jf-chat-shape-mount shape-id="core.navigate-chat">`. `cancellable=true`, `submit-label="Send"`, `cancel-label="Cancel"`. |
| SummarizeView: composer renders with `?cancellable + mono + submit-mode='enter'` | live | 2026-05-19 — verified. `cancellable=true`, `mono=true`, `submit-mode="enter"`, `submit-label="Summarize"`. |
| UnifiedChatView: send + stream + Stop button aborts | live | 2026-05-19 — verified. Stream reached 182 chars; Stop click aborted; button class `cancel`; `compCancelLabel="Stop"`. |
| UnifiedChatView AI-offline: `submit-label="AI Offline"` + disabled | live | 2026-05-19 — verified by setting `aiState.capabilities.chat=false`. Button text = "AI Offline", `disabled=true`. |

Live verification ran against the worktree-spawned dev stack
(`scripts/dev/dev-runner.cjs` from worktree, backend on port 56779,
Vite on 5173). Required workarounds: (a) copied
`modules/ui/native-bin/llama-server/variants/cuda12/` from main into
the worktree because variant binaries are not part of the
`installDist` task output; (b) copied
`modules/ui-web/.dev-data/inference-model-id.txt` and
`modules/ui-web/.dev-data/ui/settings.json` from main into the
worktree's data dir because the worktree's `.dev-data` was fresh
and lacked the chat-model wiring. The MCP-spawned dev stack runs
from main's source tree and is not usable for verifying worktree
edits; future worktree-FE work should use the dev-runner.cjs CLI
directly.

---

## Cross-reference

- `<jf-resource>` (511) — substrate-with-single-consumer fragility
  signal; `<jf-composer>` lands with five day-one consumers so it
  doesn't appear in 527's §7 single-consumer table.
- `527-substrate-consumer-audit` §6 — the C-018 framing that
  motivates the consumer-counting discipline applied here.
- `512-codebase-investigation-and-critique` §F1 — speculative
  generality warning that drives the AgentView non-goal decision.

---

## Appendix A — Critical-analysis follow-up fixes (2026-05-19)

After 528 shipped to main, a critical re-analysis pass identified
two real defects, three verification gaps, and one workflow gap.
Each is addressed below or explicitly punted with rationale.

### D1 — Dead keyboard-cancel branch in `Composer.onKeydown` (fixed)

**Issue.** The composer's keydown handler had a
`cancellable && streaming → fireCancel()` branch. The handler is
attached to the textarea, which is `?disabled=${this.streaming}`
during streaming. Real browsers do not deliver keyboard events to
disabled inputs — the branch was unreachable in production. The
unit test "cancellable: Enter while streaming fires composer-
cancel" passed only because happy-dom dispatches synthetic
keydown events even to disabled elements. The test was asserting
behavior that never happens live.

**Fix.** Removed the branch. Replaced the test with one that
asserts the textarea is disabled while streaming and verifies the
button-click cancel path (which is the only reachable cancel
route). JSDoc updated to note cancel-via-keyboard requires the
user to tab to the button (native button Enter→click activation).

### D2 — `title="AI offline"` tooltip regression on UnifiedChatView (fixed)

**Issue.** Pre-migration UnifiedChatView's submit button had
`title=${!chat ? 'AI offline' : ''}`. Post-migration only the
button label was preserved ("AI Offline"); the hover tooltip
attribute disappeared because `<jf-composer>` didn't expose
button-title props.

**Fix.** Added `submitTitle` and `cancelTitle` properties (attrs
`submit-title` / `cancel-title`), rendered as
`title=${this.submitTitle || nothing}` so empty values omit the
attribute. Wired UnifiedChatView:
`submit-title=${!chat ? 'AI offline' : ''}`. Added two
`Composer.test.ts` cases to verify the title-attr behavior.

### V1 — Navigate + Summarize cancel end-to-end (now live-verified)

**Issue.** The original verification-gate table listed Nav +
Summarize as "live-verified" with evidence that was only static
DOM inspection (`cancellable=true` present). No send-stream-cancel
cycle was actually exercised. Per `static-green ≠ live-working`.

**Fix.** Restarted the worktree dev-runner (same workaround as the
original V1 — copy `native-bin/llama-server/variants/cuda12/`
and `.dev-data` wiring from main into worktree), activated AI,
and drove full streams for each view via programmatic
`<jf-chat-shape-mount>`:

| View | Submit mode | Stream length at cancel | Post-cancel state |
|---|---|---|---|
| NavigateView (`core.navigate-chat`) | enter | 2117 chars | `streaming=false`, `btnText="Send"`, `btnClass=""` |
| SummarizeView (`core.summarize`) | enter | 858 chars | `streaming=false`, `btnText="Summarize"`, `btnClass=""` |
| SummarizeView (`core.batch-summarize`) | ctrl-enter | 295 chars | `streaming=false`, `btnText="Batch Summarize"`, `btnClass=""` |

For batch-summarize the ctrl-enter semantics were verified
independently:
- Plain Enter on textarea → `isStreaming=false` (did NOT submit) ✓
- Ctrl+Enter → `isStreaming=true`, stream began ✓

### V2 — FreeChat composer min-height visual check (acceptable)

**Issue.** The min-height shift `2.5rem → 3rem` on FreeChatView
was called "acceptable normalization" without anyone looking at
the UI.

**Fix.** Screenshot of `/justsearch://surface/core.free-chat`
captured 2026-05-19. The composer renders comfortably: textarea is
appropriately sized relative to the stage, Send button is
proportionate. No regression observed.

### S3 — `streaming-label` dead-when-cancellable JSDoc (clarified)

**Issue.** When `?cancellable` is set, the cancel button replaces
the streaming-state submit button entirely — `streaming-label`
never renders. Consumers passing both `streaming-label` and
`cancel-label` are passing dead weight. The original JSDoc didn't
say this.

**Fix.** Composer.ts header now explicitly states:
> Note: when `?cancellable` is set, `streaming-label` is unused —
> the cancel button takes the streaming slot. `streaming-label`
> only renders in the non-cancellable label-swap path.

### W1 — `dev-runner.cjs` worktree-FE gap (logged in observations)

**Issue.** The MCP-spawned dev stack runs from main worktree's
source; agents working FE changes in a worktree don't see their
changes live. The workaround (manual dev-runner launch + copy
native-bin + .dev-data wiring from main) is documented in this
tempdoc but future agents need a discovery path.

**Resolution.** Single-line entry appended to
`docs/observations.md` Inbox pointing back to this tempdoc's
verification-gate section. Not fixing the dev-runner itself in
this slice — that's a separate workflow-tooling change.

### Kept-as-is decisions (questioned but not fixed)

- **S1 Light DOM.** Test-driven choice; right long-term answer is
  Shadow DOM + `::part()` but requires updating the view tests'
  `shadowRoot.querySelector(...)` pattern. Out of scope for
  followup; flag for a future `composer-substrate-hardening`
  tempdoc.
- **S2 cancel-label duplication.** Every consumer writes
  `cancel-label=${this.streamingText ? 'Stop' : 'Cancel'}`. Each
  consumer might legitimately want different labels; the would-be
  substrate fix (composer-internal Stop/Cancel toggle gated by a
  consumer-passed `has-output` flag) just moves the state without
  eliminating it.
- **Phase 3 cancel-on-simple-four.** Shipped without prior
  product decision per user directive "everything in scope is in
  scope." Each of the four simple views' AbortController was
  already used on disconnect; wiring the cancel button exposes
  existing capability with no plausible downside.

### Verification deltas for this appendix

| Tier | Item | Result |
|---|---|---|
| auto | `npm run typecheck` | pass |
| auto | `npm run test:unit:run` | 1617/1617 (was 1615 + 2 new title tests; replaced the dead keyboard-cancel test) |
| live | FreeChat send + cancel re-spot-check (post-D1) | verified |
| live | UnifiedChat AI-offline → submit-title="AI offline" tooltip | verified by setting `aiState.capabilities.chat=false` then reading `button.title` |
| live | NavigateView send + stream + cancel | verified (see table above) |
| live | SummarizeView both submit-modes + cancel | verified (see table above) |
| visual | FreeChat composer screenshot | acceptable |

