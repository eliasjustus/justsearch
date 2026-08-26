---
status: in-progress
created: 2026-08-26
updated: 2026-08-26
---

# 873 — Chat prose rhythm and contrast

Owner review of the Search v3 chat surface, 2026-08-26: the answer reads as a wall of uniform text.
Not one defect — every contrast knob in the prose stack is set one notch too timid, and the effect
compounds. Nothing here changes behaviour; it is a typography pass over two stylesheets.

> **Number note.** This work was briefed as "871". `871-search-tool-card-seam.md` and `872` were
> already claimed (871 merged in PR #570), so it takes the next free number, 873.
> `check-tempdoc-numbers` is green.

## 1. The diagnosis

Five findings, one theme — every signal that is supposed to separate one thing from another was
spending less than the smallest step a reader can see.

1. **The two block-gap tokens were collapsed onto one value.** `Sv3Main.ts`'s `.sv3-markdown` bridge
   set `--md-block-gap` and `--md-block-gap-wide` both to `--space-2-5` (10px). The renderer keeps
   two names precisely so a code fence, a table and a blockquote can sit in more air than the
   paragraph before them. Pointed at one value, they cannot: an answer's fences and tables read as
   more paragraphs. This is the single biggest contributor to the wall.
2. **Bold was 600 and headings were also 600.** Against a 400 body, 600 is a half-step most UI faces
   barely resolve at reading size — emphasis registered as "slightly darker", not as emphasis. And
   because the heading weight was that same 600, emphasis and structure looked alike.
3. **A heading's air above (20px) was the same order as the gaps around it.** A heading that does
   not out-space its neighbours does not end the previous section; it just starts a line.
4. **List indent was 20px.** Bullet text hung barely clear of its marker, so a list read as
   paragraph text with dots in front of it rather than as its own column. `li + li` was 4px, which a
   multi-line item's own leading swallows entirely.
5. **`hr` shared `--md-block-gap-wide` with `pre` and `blockquote`.** A section break spaced exactly
   like a code fence reads as one more block, not as the end of something.

Compared against ChatGPT and t3.chat on the same content: both spend visibly more on the
paragraph→block step, both set bold at 700, and both give a horizontal rule far more room than any
other block. None of that is exotic — it is the default prose rhythm this stack had tuned away.

## 2. Before → after

Shared sheet — `modules/ui-web/src/shell-v0/components/markdown/markdownStyles.ts`:

| Knob | Before | After | Why |
|---|---|---|---|
| `.md-content strong` weight | 600 | **700** | a step a scanning reader can actually see |
| `--md-heading-weight` | 600 | **700** | moves with bold, for the same reason |
| `--md-heading-margin` | `1.25rem 0 0.5rem` | **`1.75rem 0 0.5rem`** | 28px above ends the previous section; 8px below keeps the heading with its own |
| `--md-list-indent` | `1.25rem` | **`1.75rem`** | the list becomes its own column |
| `--md-item-adjacent-gap` | `0.25rem` | **`0.375rem`** | items stop running together |
| `hr` margin | `var(--md-block-gap-wide)` | **`var(--md-rule-margin)`** (new token, `1.5rem`) | a section break is not a block |

Table `th` stays at 600 — a column header is a label, not prose emphasis, and 700 there reads as
shouting. The `:host([prose]) .md-content > :first-child { margin-block-start: 0 }` rule (unchanged)
still zeroes a leading heading's new 28px, so an answer that opens with a heading gains no top pad.

sv3 answer-prose bridge — `modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.ts`:

| Knob | Before | After | Why |
|---|---|---|---|
| `--md-block-gap` | `var(--space-2-5)` (10px) | **`var(--space-3)`** (12px) | the paragraph rhythm |
| `--md-block-gap-wide` | `var(--space-2-5)` (10px) | **`var(--space-5)`** (20px) | fences, tables, quotes get their own air back |
| `--font-size-sm` (in the `.sv3-markdown`-only rule) | *(inherited 14px)* | **`0.9375rem`** (15px) | body prose steps up — see §4 |

## 3. The rhythm, as ratios

Measured on sv3 answer prose (15px body, line-height 1.625 → a 24.4px line box). Adjacent margins
collapse, so a gap between two blocks is the larger of the two, not their sum.

| Break | Space | vs paragraph gap |
|---|---|---|
| between paragraphs | 12px | 1× |
| around a fence / table / quote | 20px | **≈1.7×** |
| above a heading | 28px | **≈2.3×** |
| below a heading | 8px | 0.67× (deliberately tight — proximity) |
| across an `hr` | 24px + 24px = **48px** | **4×** |
| `li` to `li` | 6px | 0.5× |
| list indent | 28px | — |

The `hr` is the largest break in prose even though its single-side margin (24px) is smaller than a
heading's lead-in (28px): a heading spends its space on ONE side because it leads what follows,
while a rule spends on both because it separates. Total air is the honest comparison, and by that
measure the ladder is strictly ordered: 12 → 20 → 28 → 48.

## 4. Body size is scoped; the shared-sheet changes are not

**Deliberately app-wide.** `markdownStyles.ts` is the one typography ramp for every markdown surface
(tempdoc 846 §2.3). Changing its defaults changes bold weight and list indent on *every*
`<jf-markdown-block>` — Navigate, Summarize, UnifiedChat, the reasoning trace — and changes the
heading/`hr`/`li + li` rhythm on both `[prose]` opt-ins (sv3's two transcript call sites and
`DocumentPane`'s Rendered mode, which sets `prose` by default). That spread is intended, not
collateral: the wall-of-text diagnosis is about prose, and it is not sv3-specific. The alternative —
re-pointing each value from the sv3 bridge — would fork one window off a decision that belongs to
all of them, which is the exact failure mode tempdoc 822's containment work exists to prevent.

**Deliberately NOT app-wide.** The 15px body is sv3 answer prose only. It arrives as a
`--font-size-sm` re-point inside the `.sv3-markdown`-ONLY rule (not the shared
`.sv3-markdown, .sv3-citations` rule), so `--font-size-sv3-sm` stays 0.875rem and tool cards, the
reasoning trace, the sources panel and the citation hover card all stay at 14px.

One declaration carries both halves of the size change, which is why there is no second line for the
heading ramp: the renderer's `:host` reads `--font-size-sm` for the block's own `font-size`, and the
variant's `h4-h6` read the same name. Without the re-point, lifting the body to 15px would have put
the ramp's bottom step (14px) *below* the body it leads. With it, `h4-h6` sit AT body size,
distinguished by weight — which is what that step has always meant. There is no sv3 ramp step at
15px (`sm` is 14, `base` is 16) and minting one for a single consumer would fork the window's ramp,
so the value is written at its use site. `line-height` stays 1.625; `.answer`'s own `font-size`
needed no change (its only non-markdown child is the empty-answer placeholder, which should stay at
the window's step).

## 5. Verification

- `npm run typecheck` — clean.
- `npm run test:unit:run` — **457 files / 6116 tests, 0 failures.**
- ui-web gate set (`ui-web-gates` recipe): 30 script checks + 6 kernel gates (`ambient-purity`,
  `style-literal-ratchet`, `atom-fork-ratchet`, `modality-contract`, `transient-arbitration`,
  `modal-arbitration`) + `gen-token-names --check`, `gen-component-vocabulary --check`,
  `strip-token-fallbacks --check`, the keybinding-policy self-test — **37 checks, 0 failed**.
  `check-tempdoc-numbers` green.

Test pins updated to the new values (none deleted; each retune gained a successor assertion):

- `MarkdownBlock.geometry.test.ts` — a new `RETUNED_873` record names each default that moved off the
  822 freeze as `[822 literal, 873 value]`, and a new describe pins **both ends**: the current
  default must be the 873 value *and* must no longer be the 822 one, so a silent revert fails as
  loudly as a drift. A second assertion cross-checks `RETUNED_873` against the two frozen-default
  tables, so a fifth undeclared change cannot hide beside four declared ones. `--md-rule-margin`
  joins `VARIANT_DEFAULTS` (nine names → ten) and a new test pins that `hr` reads it and *not*
  `--md-block-gap-wide` — the mechanism, not just the value.
- `Sv3Main.imports.test.ts` — the two gap pins take their new values plus an **inequality**
  assertion, so a later "tidy" that re-flattens them onto one token fails even though both
  equalities could be rewritten. New test for the 15px lift asserts the containment half too:
  `--font-size-sv3-sm` is still 0.875rem and the three component bridges plus `.sv3-citations` still
  read it, so an edit that lifted the token instead of the bridge would pass the first line and fail
  the rest.

Caught by the suite during implementation: a code sample inside a new comment in `Sv3Main.ts` used
literal `<jf-markdown-block class="sv3-markdown">` markup, which the geometry test's source-scan
counted as a third call site. Comment reworded. Worth recording — the naive source scan is doing its
job, and prose in this file is scanned like code.

## 6. Consequence worth naming

With the body at 15px, sv3's heading ramp resolves to **20 / 18 / 16 / 15 / 15 / 15px** (h1…h6). The
`h3` step is now only 1px above body text — it leans almost entirely on weight 700 and on the 28px
lead-in to read as a heading. That is coherent (it is the same argument `h4-h6` have always rested
on) but it is the ramp's weakest link, and it is the thing to look at first if the retuned surface
still reads flat. Fixing it means moving the *window's* ramp, not the shared sheet, and that is a
separate decision — deliberately not taken here.

## 7. Not done here

- No live-stack visual capture. `jseval ui-shot` / `ui-diff` on the sv3 steps
  (`sv3-citation-selected`, `sv3-citation-dropped`, `sv3-composer-occlusion`) would confirm the
  rhythm in a real browser rather than in the cascade on paper; that needs the shared dev stack.
- No independent measured UX audit (`ux-audit-closure` — honor-system since tempdoc 563). This is
  presentation-authority work, so a second agent's measured pass before close is expected practice.
