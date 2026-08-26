---
status: in-progress
created: 2026-08-26
updated: 2026-08-26
---

# 870 — Search v3 visual polish batch

Owner visual pass over the Search v3 chat surface, 2026-08-26: seven reported defects, each one a
small presentation fault with a traceable mechanism rather than a design question. They are batched
because they share a review (one screen, one sitting) and touch three components between them — the
composer, the reasoning disclosure, and the shared citations panel. No behaviour changes; every item
is CSS, an icon, or a render condition. `components/chat/MarkdownBlock.ts` is **excluded from this
batch**: a concurrent worktree (tempdoc 869) holds uncommitted work there, so the one item that
would have touched it — the `.cite-ref:hover` snap, a sibling of item 6 — is deliberately left for
that thread.

## The seven items

### 1. Composer selector menus let the placeholder text bleed through

**Root cause.** The mode/effort menus are authored inline in `Sv3Composer.ts` (`effortMenu()`,
`tierMenu()`; `.menu` CSS at `Sv3Composer.ts:722`). `.menu` used `background: var(--dropdown-surface)`
(~84 % opaque, `sv3-tokens.css.ts:165-173`) plus a `backdrop-filter`. But `.glass` declares
`isolation: isolate` (`Sv3Composer.ts:448-452`), which scopes the backdrop-filter to sample only the
composer's own painted content — so the placeholder glyphs under the menu blurred slightly and then
composited through the remaining ~16 % transparency.

**Fix.** `background: var(--popover)` (the opaque surface the removed no-blur fallback already used)
and both `backdrop-filter` declarations deleted — from inside an isolated context they can never
sample the page, so they were dead weight. Radius, border, shadow and spacing unchanged.
`--dropdown-surface` itself is untouched; its other consumers sit over non-isolated contexts.

### 2. Loud keyboard focus ring around the whole composer

**Root cause.** TWO rings painted at once. (a) The app-global ambient rule
`:focus-visible { outline: 2px solid var(--focus-ring-color) }` (`primitives/ambientStyles.ts:55-58`)
is adopted into the composer's shadow root and lands on the `<textarea>`, out-specifying the local
bare-type reset `textarea { outline: none }` — (0,1,0) vs (0,0,1). (b) The composer's own
`.glass:has(textarea:focus-visible)::after` added `outline: 3px solid color-mix(… --ring 24% …)`.

**Fix.** In `Sv3Composer.ts` only: a local `textarea:focus-visible { outline: none }` at (0,1,1)
takes the field back from the ambient rule, and the 3px halo declaration is removed while
`border-color: var(--ring)` stays. Keyboard focus is still carried in two channels — the frame's hue
and the resting→engaged surface lift already driven by `--composer-rest` — so the indication survives
without a high-contrast halo. `ambientStyles.ts` is app-global and unchanged. The now-dead
`.glass:has(textarea[aria-invalid='true']:focus-visible)::after { outline-color: … }` rule went with
the outline (retire-with-a-sweep); invalid-while-focused still reads as invalid because the
`[aria-invalid]` border rule follows the focus rule at equal specificity.

### 3. The full-width "Thought for Ns" card should be a slim inline disclosure

**Root cause.** `components/chat/ReasoningBlock.ts` `.container` carried card chrome (3px border-left,
`--surface-subtle` fill, radius, padding) and `:host { display: block }` made it full width. The
run-timeline site already opted out via the `inline` attribute; the ask-arm site
(`Sv3Main.ts` `reasoningBlocks()`, `data-testid="sv3-turn-reasoning"`) got the card.

**Fix.** The card declarations moved OFF the base rule (rather than being overridden back off it), so
`[inline]` restates the one thing it keeps — the hairline "aside" rule. The non-inline form is now an
inline-flex header at `--font-size-xs` (12 px under the v3 bridge, matching the tail's
`Sources ›` trigger), with the chevron moved after the label by flex `order` so DOM/reading order
still puts the label first. The expanded trace keeps a left hairline + indent. The colour grade stays
`--text-secondary`: tempdoc 853 F-07 measured `--text-muted` at 4.11:1 on this component's own label,
so the de-emphasis is bought with size and box, which cost no contrast.

### 4. Copy button on the reasoning block renders while collapsed

**Root cause.** `ReasoningBlock.ts` rendered the copy button on `!streaming && text`, with no
collapsed check — it lives in `.header`, outside the hidden `.content`.

**Fix.** Condition is `showContent && !streaming && text`. Regression tests: collapsed → no button;
expanded + settled → button; expanded + streaming → still none (the two halves are independent).

### 5. Replace the complex copy glyph with lucide's minimal `copy`

**Root cause.** `components/Icon.ts` carried `clipboard-copy` (five paths: clipboard body, lid rect,
inbound arrow), which reads as a smudge at 11-15 px.

**Fix.** Added `copy` (two rounded rects) to `IconName` + `PATHS` and switched every call site, then
deleted `clipboard-copy` and its union member. Six sites in total: `ReasoningBlock.ts`,
`Sv3Main.ts` (tail "Copy answer"), plus the sweep's finds — `chrome/Shell.ts`,
`commands/searchResultActions.ts`, `components/searchResults/ResultsCard.ts`,
`views/UnifiedChatView.ts`. All six are copy affordances, so all six moved.

### 6. Hover-expand surfaces that snap instead of animating

**(a) `CitationsPanel.ts` source preview.** `display: none` → `display: block` on hover; `display`
is discrete, so the reveal could never transition. Fixed with `opacity` + `transition-behavior:
allow-discrete` on `display` + an `@starting-style` block for the entering state, guarded by the
file's existing `prefers-reduced-motion` posture.

**(b) `Sv3SessionRow.ts` `.status-slot`.** The hover widening (`--space-8` floor →
`--sv3-row-actions-inline`) snapped in one frame while the sibling `.slot-content` faded over
`--duration-sv3-micro`. Both ends are real lengths, so a matching `transition: min-inline-size …` on
the same duration and curve was all it needed; `.status-slot` joined the reduced-motion opt-out list
with its sibling.

**Out of scope.** `.cite-ref:hover` in `MarkdownBlock.ts` — see the exclusion note above.

### 7. Sources/citations panel typography normalization

**Root cause.** `components/chat/CitationsPanel.ts` declared eight distinct type roles that resolved
to two sizes, with three `text-transform: uppercase` sites and hand-authored `letter-spacing` — while
Search v3's own stated law (`Sv3Main.ts:236-238`) is "v3 uses UPPERCASE nowhere".

**Fix.** All three uppercase sites and all `letter-spacing` removed (`.panel-header`, `.tier-header`,
`.score-metric`); roles consolidated to two — meta/labels at `--font-size-xs` with one weight
treatment (500 where a label carries emphasis, 400 where it does not), content (`.sentence`,
`.preview`) at `--font-size-sm` with `line-height: 1.5`. The dead `.excerpt` rule is deleted (grep
confirmed no template applies that class). The monospace `.doc-group-label` (filenames), the
`.sv3-markdown` / `.sv3-citations` token bridge in `Sv3Main.ts`, and the `.cite-legend` are untouched.
This is a shared component: the change lands in `UnifiedChatView` and `SummarizeView` too, which is
accepted — sentence case is the app-wide direction.

## Verification

- `npm run typecheck` clean; `npm run test:unit:run` 6060 passed / 457 files, 0 failing.
- Tests updated or added for every item that changed a pinned invariant: `ReasoningBlock.test.ts`
  (expand-first helper + a new 870 block for items 3/4/5), `sv3-tokens.test.ts` (menu opacity, the
  single focus mark, the blur count, the slot transition), `CitationsPanel.test.ts` (new 870 block
  for items 6a/7), `SearchV3View.tail.test.ts` (glyph comment).
- Full `ui-web-gates` recipe green (34 scripts + 6 kernel gates).
- Backend untouched — no `.java` in the diff, so no Gradle run.
