# 839 — Citation-mark presentation: what a selected source looks like

    status:  IMPLEMENTED + live-validated + independently reviewed
    created: 2026-08-14
    updated: 2026-08-14
    follows: 822 (Search v3 window — closed); shares `MarkdownBlock.ts` with the
             rendering-remediation workstream, whose ownership split gave the mark
             *appearance* to this work

## 1. Subject

How a **selected** citation source is presented — the state entered by clicking an inline `[n]` or a
source card, carried by the `selectedSource` store.

## 2. What was wrong

Source-verified, then confirmed in a real browser.

| # | Finding |
|---|---|
| **F1** | **The "cross-surface" selection had no far side.** `MarkdownBlock` promised the mark was "highlighted in sync with the rail card", but Search v3 renders `jf-citations-panel`, and that component contained **zero** references to the selection store. Selecting a citation lit a two-character superscript and nothing else. |
| **F2** | **Selection erased the honesty tiers.** `.cite-weak` and `.cite-ungrounded` carry the grounding tier in `color`; `.cite-selected` also set `color`, at equal specificity and later in source, and the classes are always co-applied. **Clicking the amber "not supported" numeral hid that it was unsupported** — the signal died at the moment of scrutiny. Live in the shipped window, not only in v3. |
| **F3** | Selection was painted in `--primary` — the same swatch as the composer's send button. Colour is reserved for act-now / in-motion / broken; a resting selection is none of those. |
| **F4** | The highlight was on the handle, not the payload: nothing showed *which sentences a source supports*, though `.cite-sentence` spans already existed. |
| **F5** | Selecting widened the mark by 6px and reflowed prose. |
| **F6** | The state was visual-only — no `aria-current`, no accessible-name change. |
| **F7** | Five mark types, no legend. `.pseudo-cite` and `.cite-weak` were both grey, meaning "the model invented this" and "real but weakly supported". |
| **F8** | `text-decoration: none` cancelled the hover underline on the selected mark. |

**Why it survived eleven slices:** nothing asserted `.cite-selected` anywhere in the repo, the
contrast harness had no text-on-selection pair, no measured fixture entered the state, and no
screenshot of it existed. The defect was not missed by inattention; it was **unreachable by the
harness**.

## 3. Principle

One reassignment, from the design spec's own recorded lesson — **surface encodes interaction,
content encodes status**:

- **Selection → a neutral surface wash.** Never a hue, never the accent, never a polarity inversion.
- **Grounding → stays in content.** Glyph colour and the sentence underline, untouched by selection.

## 4. Shape of the change

- `.cite-selected` loses `color` (the repair) and `text-decoration: none`. Selection paints surface only.
- New `--md-cite-*` / `--cp-*` tokens, **defaults equal to today's values**, so shipped rendering is
  byte-identical except three deliberate behaviour changes; v3 opts in via its bridge.
- Selecting a source tints the sentences it supports (`.cite-sentence-selected`, transparent default).
- `CitationsPanel` consumes the selection store; the binding is two-ended (mark→card and card→mark).
- `aria-current` on both the mark and the card, using the *removal* idiom rather than `"false"`.
- A mark legend inside the existing `Sources` disclosure — gated, so zero resting chrome.
- `sv3-citation-selected` ui-shot step: the state nothing in the harness could previously enter.

## 5. Live validation (real browser)

v3 boots backendless in ~31 s, so this needed no dev-stack contention for the first pass: real
components were mounted inside the live window (real token cascade) and the marks **clicked**, then
the step was captured against a live backend with the model loaded.

Confirmed in pixels and computed styles: the amber tier survives selection; glyph positions are
identical between states; the panel binding works both directions; the legend renders.

**Two defects only the browser could show, both in the design rather than the code:**

1. The region wash was a full-bleed band reading as a text-selection smear → tokenized
   **horizontal-only** inset (vertical padding would push a selected sentence's `border-bottom`
   underline lower than an unselected one's).
2. **The panel's selected card was invisible.** `[data-selected]` was set and every unit test passed.
   The mark's ladder was derived against the window **background** (0 %), but a source card already
   carries a 4 % fill, so the 5 % region rung was a *one-point* step.

**Lesson (generalises beyond this slice):** the spec's 6/9 selection idiom assumes a **transparent
resting row**. Ported onto a surface that already carries a fill it must be **re-based against the
surface it lands on**, not copied by number.

## 6. Independent review (reviewer ≠ implementer)

Every finding below was confirmed and fixed; the review is the reason they were found.

| Finding | Outcome |
|---|---|
| **The repair broke WCAG AA for the tier it protects.** The 9 % wash behind a 12px numeral pushed low tiers under 4.5:1 — the design predicted this in its verification list and then did not test it. | Fixed. The lift is carried on the **tier**, never on the wash. dark weak 4.22→**4.94**; light weak 3.97→**4.95**; light ungrounded 4.14→**4.92**. A normal blue mark computes 6.24 on the same wash, so weak still reads as weak — asserted, not observed. |
| The source card announced its state to nobody (`data-*` only) — the same "visual-only" defect the slice fixed on the mark. | Fixed: `aria-current` on the card. |
| A selected card lost its hover feedback in every shipped consumer — a change the containment claim denied. | Fixed, with its own token so v3 holds its strong edge instead of weakening on hover. |
| The ui-shot step was `required=False`, so the one thing that can see this regression could not fail a run; its positional card locator could miss the state and pass silently. | Now required, keyed to a real mark, and targets a low-tier mark when present. |
| The containment test passed with a *wrong* nested fallback (happy-dom drops nested `var()`). | Strengthened until a wrong fallback goes red. |
| Nothing asserted the bridge existed — deleting any bridge line reverted the whole design to the accent colour with a green suite. | Pinned, in both themes. |
| Duplicate state announcement (`aria-current` **and** "selected" in the accessible name); legend outside `aria-controls`. | Both fixed. |

A **card-fill contrast regression** was separately caught by capturing the new step and reading axe,
then A/B-ing the wash: 9 % produced three new serious violations inside the selected card, 5 % was
invisible. Resolved by moving the card's signal to its **edge** — a fill sits behind text, a border
does not.

## 7. Deliberately not done

- **`.cite-claimed` styling** — handed to this work by the ownership split, but the class is not
  minted yet (blocked on the literal-citation disposition). A rule for it would be dead CSS and its
  test green for the wrong reason.
- **F5 in the shipped window** — the 6px reflow is closed in v3 via reserved rest-padding and left
  standing in the shipped window, because closing it there moves every citation mark. Worth doing as
  its own deliberate layout change.
- `SourcesPane` writes `aria-current="false"` while the mark and the card use the removal idiom.
  Three surfaces of one selection, two idioms — out of scope here, recorded so it is not lost.

## 8. Verification

421 files / 5140 tests green; typecheck clean; ui-web gate recipe green except the documented
pre-existing reds (`RecentsMenu` ghost tokens, `ActionLedgerView` fallbacks/accent-as-text,
`UnifiedChatView` title-on-disabled). `check-ui-step-coverage` green.

Regression protection added where there was none: both tiers surviving selection, the width
invariant, aria state, the region toggle, frozen-defaults containment, the bridge's existence, and
the contrast floor in both themes.
