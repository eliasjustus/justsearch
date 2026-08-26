---
status: in-progress
created: 2026-08-26
updated: 2026-08-26
---

# 874 — Search v3 chat-width presets

> **Numbering note.** This work was briefed as "tempdoc 872". By the time the doc was written, 872
> was already claimed by a sibling worktree (`872-memory-retirement.md`) and 873 by another, so
> `check-tempdoc-numbers` — the cross-worktree collision guard — failed. Renumbered to the next free
> number, 874, and every code comment updated to match.

The Search v3 chat column was 48rem, fixed, with the number written out four times. This makes the
width a reader-adjustable preference (Narrow / Default / Wide) and, as the precondition for that,
collapses the four sites onto one token.

## 1. The four width sites, and the consolidation

| Site | Before | After |
|---|---|---|
| `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:364` (`.transcript`) | `max-inline-size: 48rem` | `var(--measure-prose)` |
| `modules/ui-web/src/shell-v0/views/search-v3/Sv3Composer.ts:239` (`.band`) | `max-inline-size: 48rem` | `var(--measure-prose)` |
| `modules/ui-web/src/shell-v0/views/search-v3/Sv3ContextBar.ts:72` (`.bar`) | `max-inline-size: 48rem` | `var(--measure-prose)` |
| `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts:587` (`.answer`) | `var(--measure-prose)` | unchanged — it was already the token |

`--measure-prose` is declared at `modules/ui-web/src/shell-v0/views/search-v3/sv3-tokens.css.ts:308`
inside the sheet's `:host { … }` block, and that sheet is adopted on the `SearchV3View` host only —
never `:root`, which `sv3-tokens.test.ts:42-47` already pins. The global
`modules/ui-web/src/styles/tokens.css:352` declares `--measure-prose: 88ch` for the rest of the app;
the sv3 host declaration shadowing it is exactly what scopes this feature to Search v3 and leaves
every other surface alone.

So `--measure-prose` is now the **single width authority for the chat column**. The transcript, the
composer band and the context bar share one edge by construction rather than by three components
agreeing to restate the same literal — which is the property the design spec asked for ("a turn and
the field that produced it share an edge", `Sv3Main.ts:358-361`) and the property three literals
could only approximate. The use sites are bare `var(--measure-prose)` with no fallback: the
`strip-token-fallbacks` gate forbids the `var(--x, y)` form in this tree, and a fallback would also
silently re-hardcode the literal it was meant to remove.

Left alone deliberately: the `@media (min-width: 48rem)` in `Sv3Empty.ts:40` and the equivalent in
`PresentationEditorSurface.ts`. Those are breakpoints, not width caps — same number, unrelated fact.

## 2. Presets, not a slider

`narrow: 42rem` · `default: 48rem` · `wide: 56rem`
(`modules/ui-web/src/shell-v0/state/chatWidthState.ts`, `CHAT_WIDTH_MEASURE`).

Three named stops rather than a free px slider, for one reason: **a bounded vocabulary is pinnable.**
`chatWidthState.test.ts` asserts the three literals verbatim, so re-tuning a stop has to edit a test
that says what the product's reading measure is. A continuous slider has no such assertion available
— every value is as valid as every other, and the width degrades into per-pixel drift that no test
can distinguish from a deliberate choice. The stop set also gives the settings control a shape the
window already uses (the ordered three-stop `<jf-discrete-slider>`), so no new control was invented.

**The measure caveat.** 48rem at the shipped 14-15px body size is roughly 90-100 characters per line
— already at or above the classic 45-75ch comfortable-reading band. So `Default` is not "the middle
of a good range", it is the shipped value kept where it is; `Narrow` (42rem) moves *towards* the
band, and `Wide` (56rem) trades measure for density. Wide is a deliberate reader choice, not a
recommended default, and the setting's description says as much.

## 3. The settings pattern found and followed

This is a **frontend-local** preference. It persists in the user-state document
(`localStorage['justsearch.userState.v2']`), cross-profile, with **no backend and no
`/api/settings`** behind it — like `surfaceMode`, which is a rendering preference rather than a
configured behaviour. Two exemplars carried the whole shape:

- **`surfaceMode`**, the cross-profile FE-only enum: declared on `UserStateV2Storage`
  (`UserStateDocument.ts:275-279`) and on the flat view `UserStateV1` (`:314-315`), projected
  storage→view at `:413`, re-keyed view→storage at `:458`, sanitized on parse at `:969-972` and
  emitted at `:999`; getter/setter at `themeState.ts:393-401`. `chatWidth` mirrors it at all five
  sites — a missed one drops the preference silently on reload, which is why §5 pins the round-trip.
- **`viewerAudienceState.ts`** (97 lines): the canonical small-preference module shape — signal tick
  + `subscribeProjection` + `getX` / `subscribeX` / `setX` via `mutateDocument`. `chatWidthState.ts`
  is the same file with a different slice, including the tick-over-live-read rationale
  (`viewerAudienceState.ts:55-65`): the getter reads the value live from the document and only
  *tracks* the tick, so a cleared listener set (a test reset) cannot leave a mirrored signal stale.

No eslint allowlist entry was needed: the `no-restricted-imports` ignore list at
`modules/ui-web/eslint.config.js:233-254` exempts modules that import **wire types**
(`api/types/registry`, `api/generated/wire-types`), which is why `viewerAudienceState.ts` is listed
there — it imports `Audience`. `chatWidthState.ts` imports no wire type, so it is not in scope.

Settings side:

- The control copies the **Density row** — `SettingsSurface.DENSITY_STEPS` (`SettingsSurface.ts:1374-1379`)
  feeding a `<jf-discrete-slider>` (`:1388-1404`) inside a `renderSettingRow(…, { below: true })`
  (`:1165-1190`).
- The section is declared in the register, `settingsRegister.ts:84-102`, in the `appearance`
  category between `accessibility` and `token-editor`; dispatched from `sectionRenderers()`
  (`SettingsSurface.ts:2572-2593`); labelled from `settings.section.chat-width` in
  `modules/app-api/src/main/resources/messages/registry-surface.en.properties` (the only copy of
  that catalog in the repo). The register key, the dispatch key and the properties key are the same
  string, which is what makes the section reachable at all.

### Why its own section, not a row in `renderAppearance()`

`renderAppearance()` (`SettingsSurface.ts:1281`) is **dead on the production declared path** — its
own neighbours say so: production boot applies `CORE_DECLARED`, so the Interface+Appearance region
renders the declared `<jf-declared-surface>` and never the built-in Lit branch
(`SettingsSurface.ts:1318-1324`, the comment that explains why the cross-link row had to be lifted
out of it). A row added there would be invisible in the shipped app. A registered section renders
through `renderRegisteredSection` regardless, so that is where the setting lives.

## 4. Live application

`SearchV3View` subscribes on connect and writes the measure inline on its own host:

```ts
this.chatWidthUnsubscribe = subscribeChatWidth((w) => this.applyChatWidth(w));
// …
private applyChatWidth(width: ChatWidth): void {
  this.style.setProperty(CHAT_WIDTH_VAR, chatWidthMeasure(width));
}
```

`subscribeProjection` fires immediately on subscribe, so the subscription is also the initial apply
— there is no separate read-on-connect that could mask a missing subscription. The inline property
sits on the same element the token sheet's `:host` rule targets, so it wins and inherits into the
whole shadow subtree; `<jf-sv3-composer>` and `<jf-sv3-context-bar>` are mounted only inside that
subtree, which is why one write moves all three components. This mirrors the host-property writes
already there (`SearchV3View.ts` `publishOcclusion`, `--sidebar-width`, `--pane-width`). Settings
never reaches across into the window: the store is the entire channel.

## 5. Tests

- `modules/ui-web/src/shell-v0/state/chatWidthState.test.ts` — the 42/48/56rem vocabulary verbatim;
  the unknown-input fallback (an empty write would collapse the column to content width); the
  default with nothing stored; and the **serialization round-trip** (`setChatWidth` → assert the
  top-level `chatWidth` in the stored JSON → `__resetInMemoryStateForTest()` → re-read), which is
  the assertion that catches a missed spread in §3's five sites. Plus malformed-literal rejection
  and the subscribe/notify/dispose contract.

  Also (review, LOW) the **pre-874 document** case: a genuine persisted v2 body with `chatWidth`
  deleted, re-parsed from storage, must read `'default'` without throwing and must still be
  writable. Every upgrading user has exactly that document, and the "no migration needed" claim was
  previously only exercised against a fresh in-memory default — which is not the same object and
  never goes through the sanitizer.
- `sv3-tokens.test.ts` (appended block) — each of `Sv3Main` / `Sv3Composer` / `Sv3ContextBar` caps on
  `var(--measure-prose)`, contains no `48rem`, and **never declares the property** (`not.toMatch(/--measure-prose\s*:/)`),
  and the sheet still declares the default. Scoped per-component via the file's existing
  `styleTextOf` helper rather than repo-wide, because `Sv3Empty.ts` legitimately uses `48rem` as a
  breakpoint.

  The exclusivity assertion is the one review added (independent review of PR #573, LOW). Reading
  the token is only half the contract: a consumer that later grew its own
  `:host { --measure-prose: … }` would **shadow** the inline value the view host writes and silently
  stop responding to the preset, with every other assertion still green. happy-dom does not compute
  the cascade, so no runtime test in this suite can observe that regression — the static pin on the
  *declaration* is the only place it is catchable. (The reviewer confirmed the cascade itself works
  by a real Chromium probe; these pins are what make the assumption fail-able in CI.)
- `SearchV3View.chatWidth.test.ts` — the live contract: default measure on mount, a pre-chosen preset
  on mount, all three presets applied while mounted, and an unmounted window that stops following.
  Test 1 fails outright if the subscription is deleted (nothing else writes the property inline);
  test 3 fails if the implementation reads once at mount instead of subscribing. Verified by
  mutation, not by reasoning: replacing the `subscribeChatWidth` call with a no-op turned all four
  red (`expected '' to be '48rem'`), and restoring it turned them green again.
- `SettingsSurface.chatWidth.test.ts` — the setting is REACHABLE. `chat-width` is one string in three
  files that never reference each other (register key, `sectionRenderers()` dispatch key, catalog
  key); a typo in any of them yields a silently blank sub-anchor. This mounts the real surface,
  asserts the anchor AND the section render, that the slider carries the three presets with the
  stored value selected, and that driving the real range input writes through to the store.

## Verification

`npm run typecheck` clean. `npm run test:unit:run`: 460 files / 6126 tests passed. The ui-web gate
set + the six kernel gates run one at a time: 41/41 pass.
