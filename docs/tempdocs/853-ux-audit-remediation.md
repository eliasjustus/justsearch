---
number: 853
title: UX audit remediation — the three SERIOUS a11y findings from 2026-08-19
status: implementing (round 1 = the three SERIOUS fixes; round 2 = F-09 + the contrast-gate blind spot, see §remediation-2)
created: 2026-08-19
updated: 2026-08-19
charter: remediation of the measured closure audit for tempdocs 846 / 847 / 848
---

# 853 — UX audit remediation (F-05, F-07, F-08)

## 0. Provenance

The 2026-08-19 measured UX closure audit (independent auditor, axe-core `wcag2a+2aa+21a+21aa`
plus a shadow-piercing WCAG contrast oracle, live dev stack, four palettes) raised **13 findings**
against `main` @ `34cc8d86`. Three were **SERIOUS**; this tempdoc is their remediation and nothing
else. The audit's other ten findings — F-00, F-01/02 (INFO), F-03/04, F-06 (the unwired sv3 light
seam), F-09, F-10, F-11 (the known `controls-a11y` red), F-12/13 (847 citation behaviour) — are
chartered elsewhere and are deliberately **not** touched here.

`ux-audit-closure` note: the auditor was not a committer of #489 / #488 / #494 / #492, and this
remediation agent is not the auditor. The measurements below are the auditor's; the ratios stated
for the *fixed* state are computed from the token values (WCAG 2.x relative luminance) and marked as
such — a live re-measure on the running stack is the confirming step, not a claim made here.

## §remediation

### F-05 — `scrollable-region-focusable` on the reading pane (SERIOUS, NEW in #489)

| | |
|---|---|
| **Measured** | axe `scrollable-region-focusable`, impact serious, **n=2**, WCAG 2.1.1 (`wcag21a`), in **all four palettes**. Sites: `jf-document-pane` → `div[data-line-start="61"] > table` and `div[data-line-start="82"] > pre`. |
| **Cause** | `markdownStyles.ts` makes both elements horizontal scroll containers (`pre { overflow-x: auto }` on the default path; `:host([prose]) table { display: block; overflow-x: auto }`). Neither is focusable, so the clipped half of a wide table or fence is unreachable by keyboard — on the surface whose entire job is reading a document. `DocumentPane`'s own `.scroll-region` (already `tabindex="0"` + `role="region"`) does not reach content clipped *inside* a block. |
| **Fix** | New post-render pass `components/markdown/markdownScrollRegions.ts` → `markScrollableRegions(root)`, wired next to `highlightCodeBlocks` in `DocumentPane.updated()` (root `.blocks`) and `MarkdownBlock.updated()` (root `.md-content`). `<pre>` gets `tabindex="0"` + `role="group"` + `aria-label="Code block"`; `<table>` gets `tabindex="0"` + `aria-label="Table"` and **keeps its own role**. |
| **Why a pass, not markup** | The renderer emits bare `<pre>`/`<table>` through `unsafeHTML`, and the sheet's own comment already rules out synthesising a wrapper (every re-render rebuilds the subtree). A post-render attribute pass is the shape this directory already uses for exactly that reason; it is re-applied per render because Lit's rebuild takes the attributes with it. |
| **Why `group` on `<pre>` but no role on `<table>`** | `<pre>` maps to `generic`, where `aria-label` is *prohibited* (the name would be dropped), so it needs a role to carry a name — but `role="region"` + a name is a **landmark**, and one landmark per code fence on screen is landmark spam. `group` names it without that. On `<table>` any role override would cost the reader the table semantics the element already carries. |
| **Why unconditional, not measured** | A declared scroll container is one at *some* viewport width; a render-time `scrollWidth > clientWidth` probe would leave the region unreachable exactly when the window is narrow enough to clip it. Same reasoning as `DocumentPane`'s own `.scroll-region` and `UnifiedChatView`'s transcript, which both carry a static `tabindex="0"`. |
| **Expected after** | axe `scrollable-region-focusable` **n=0** on the reading pane in all four palettes (was n=2). Keyboard: Tab reaches each wide fence/table, then arrow/Page scrolls it. |

**Pinned by a test: YES, two of them.** `markdownScrollRegions.test.ts` (7 cases) pins the pass
itself — both container kinds, multi-block reach, idempotency, and that an author's own `tabindex`
is left alone. `DocumentPane.test.ts` gains the **wiring** pin: a real fetch-stubbed pane renders a
document containing a table and a fenced block and both carry `tabindex="0"` + a name. Fixture note:
both constructs are nested one level inside a blockquote on purpose — this repo already records
(`markdownBlockMap.test.ts`) that happy-dom's DOMPurify strips exactly the single *outermost* element
of a standalone fragment, a test-environment artifact that would otherwise delete the very elements
under test.

### F-07 — reasoning label + body below AA in both light palettes (SERIOUS, pre-existing, exposed by #492)

| palette | measured before | AA 4.5 |
|---|---|---|
| dark | 5.75 | pass |
| light | **4.11** | **FAIL** |
| hc-dark | 6.27 | pass |
| hc-light | **4.40** | **FAIL** |

Confirmed by two oracles agreeing to ±0.02 (axe `color-contrast` serious, and the audit's own
shadow-piercing oracle) on both `<span class="label">Thought for 7s</span>` and the reasoning
body `<p>`, at 13px. `ReasoningBlock.ts` has never been modified since `29579e51` (v0.1.0), so this
is pre-existing markup; what #492 changed is *reach* — the block now renders from the record in both
windows and survives a reload, so the failing text is on screen far more often.

The audit named **two independent remedies**, and both are implemented, because neither alone closes
the finding:

1. **`styles/tokens.css` — the systemic half.** The HC blocks redeclare `--text-primary` /
   `-secondary` / `-tertiary` (and `-ghost` in dark) but **not `--text-muted`**, which therefore kept
   the base `rgba(var(--p-text), 0.58)` and composited that alpha over an *opaque* HC surface. This
   is the sharp cell: a user who explicitly asked for high contrast was served 4.40:1. Added
   `--text-muted: #a0a0a0` to `.high-contrast` and `--text-muted: #4d4d4d` to
   `[data-theme="light"].high-contrast`. Computed: **8.03:1** on the HC `#000000` surface (7.22:1 on
   `#111111`), and **8.45:1** on `#ffffff` (7.75:1 on `--surface-1` `#f5f5f5`) — AAA on every HC
   surface, and still dimmer than `--text-tertiary`, so the grade ordering the base palette expresses
   through alpha survives the opaque override. This benefits **every** `--text-muted` consumer, not
   just this component.
2. **`ReasoningBlock.ts` — the component half.** The HC fix cannot reach the plain `light` palette's
   4.11:1, so the container colour and the `--text-primary` re-point that dresses the whole
   transcript both move from `--text-muted` to `--text-secondary` — the next grade up, redeclared by
   every palette including both HC blocks (9.62 / 11.02 / 11.93 / 13.39 measured by the audit on the
   same surface family). Hover targets move up one grade to match.

**Expected after:** axe `color-contrast` clears on the reasoning label and body in all four palettes;
the two failing cells go 4.11 → ~11 and 4.40 → ~13.

**Pinned by a test: YES.** `themes/highContrastTextRoles.test.ts` gates the *closure*, not one
component's use of it: both HC blocks must redeclare **every** text grade, each as an opaque literal
(the exact shape of the bug was an alpha over an opaque surface), each clearing **AAA (7:1)** on every
HC surface — AA is the floor for the base palettes; a palette whose entire purpose is contrast is held
to the higher one — and the grades must stay ordered. It reuses the production contrast authority
(`themes/contrast.ts`), no second copy of the WCAG maths. `ReasoningBlock.test.ts` separately pins
that no text in that component rides `--text-muted` any more.

**Gate gap worth recording:** `check-contrast-matrix.mjs` parses `:root` and `[data-theme="light"]`
**only**, so it structurally cannot see either HC palette — which is precisely why this gap survived
to an audit. The new test is the coverage; extending the gate itself is a larger change than this
remediation, and is left as a named follow-up rather than done silently here.

### F-08 — `nested-interactive` on the reasoning header (SERIOUS, pre-existing, exposed by #492)

| | |
|---|---|
| **Measured** | axe `nested-interactive`, impact serious, **n=1**, WCAG 4.1.2 (`wcag21a`), in **every palette and both windows** whenever a reasoning block is on screen. Site: `<div class="header" role="button" tabindex="0" aria-label="Model reasoning trace">` — "Element has focusable descendants". |
| **Cause** | `ReasoningBlock.ts` nested `<button class="copy-btn">` inside the `role="button"` header. The two roles conflict and AT is free not to expose the copy control at all. Pre-existing since v0.1.0; #492's record-path rendering turned it from a mid-stream flicker into a persistent state. |
| **Fix** | `.header` is now inert layout. The disclosure is a real `<button class="disclosure">` carrying `aria-expanded` + `aria-label`, and the copy control is its **sibling** in the same row. Same disclosure shape `ToolCallCard`'s `.expand-toggle` already uses. |
| **Behaviour preserved** | Pointer: `.disclosure` takes `flex: 1`, so the row still toggles across its width. Keyboard: a native `<button>` brings **Enter and Space** for free — strictly better than the hand-rolled `role="button"` + `keydown` handler it replaces, which the audit measured as Enter→true / Space→false. The `stopPropagation` on copy is deleted because siblings make it structurally unnecessary (nothing to bubble into). `:focus-visible` rings on both controls. |
| **Expected after** | axe `nested-interactive` **n=0** in all four palettes and both windows (was n=1). |

**Pinned by a test: YES.** `ReasoningBlock.test.ts` is a new file — the component had **no test file
at all** before this, which is the reason both F-07 and F-08 survived to an audit. It pins the header
as non-interactive, the disclosure as a native focusable `<button>` with the expanded state, the copy
control as a sibling rather than a descendant, and — the structural form of the axe rule itself — that
no interactive element in the shadow root contains another. Behaviour is pinned separately: toggle
round-trip, no double-activation when copy is activated, no copy control while streaming.

### Deliberately NOT fixed here

**F-09 (MODERATE)** — the copy button's accessible name computes from its emoji content
(`"clipboard"`, not `title="Copy reasoning"`), and both it (23x19) and the header row (541x20) are
under the WCAG 2.2 2.5.8 24x24 minimum. The copy button's markup was moved by F-08 but its *naming
mechanism and hit area were left exactly as found*: F-09 is a separate finding on a separate success
criterion (2.5.8 is WCAG 2.2; this audit ran 2.1), and taking it here would blur the boundary of what
this remediation is accountable for. Named explicitly so it is not mistaken for an oversight.

## §verification

| check | result |
|---|---|
| `npm run typecheck` | clean |
| `npm run test:unit:run` | **425 files / 5340 tests, all pass** (pre-change baseline was also green; this run includes the 4 new/extended files, 26 new cases) |
| **mutation check** | Each fix was reverted in turn and the corresponding assertions went red — and only those: removing the `markScrollableRegions` call failed the DocumentPane wiring pin; removing `--text-muted` from the hc-light block failed all four hc-light closure cases; restoring `role="button"` on `.header` failed both structural F-08 cases. The tests fail for the reason they claim to. |
| `check-contrast-matrix` | OK (34 pairings, exit 0) |
| `check-theme-token-closure` | RED — **pre-existing**, exactly the 3 known `RecentsMenu.ts` ghosts (`expected-state.v1.json` `theme-token-closure-red`); no new ghost |
| `check-controls-a11y` | RED — **pre-existing**, exactly the known `UnifiedChatView.ts` title-on-disabled (audit F-11); count unchanged at 1 |
| `check-accent-as-text` | RED — **pre-existing**, `ActionLedgerView.ts` (`expected-state.v1.json` `accent-as-text-red`) |
| `strip-token-fallbacks --check` | RED — **pre-existing**, `ActionLedgerView.ts` + `RecentsMenu.ts` only |
| rest of the `ui-web-gates` recipe | **all green** — 23 script gates (presentation-purity, observed-state-collapse, color-tokens, a11y-closure, adaptive-closure, layout-purity, surface-composition, message-single-model, run-renderers, inflight-liveness, composition-surfaces, declared-surfaces, live-channels, offline-single-sense, steering-arbitration, search-issuance, verdict-derivation, ai-verdict-derivation, message-classes, capability-availability, realized-capability, consequence-classification, folder-status-derivation), `gen-token-names --check`, `gen-component-vocabulary --check`, `check-ui-step-coverage`, and the 6 kernel gates (ambient-purity, style-literal-ratchet, atom-fork-ratchet, modality-contract, transient-arbitration, modal-arbitration) |
| `check-tempdoc-numbers` | OK — 556 distinct numbers, no collision across 21 worktrees + `origin/main` |

**Honest limit.** Every ratio stated for the *fixed* state is computed from the token literals, and
every axe expectation is a prediction from the rule's own definition — this remediation was verified
by unit test and gate, not by a second live axe run. The confirming re-measure belongs to the next
independent auditor (`ux-audit-closure`: the reviewer should not be the committer), and the two
coverage gaps the audit already recorded still apply — `governance/ui-a11y-baseline.v1.json` has no
surface entry for the search-v3 window, the `DocumentPane` reading surface, or the reasoning block,
so `jseval ui-a11y-gate` structurally cannot see F-05 or F-08 either before or after this change.

### Live structural confirmation of F-05 and F-08, 2026-08-19 (partial — NOT the audit)

Run as a rider on the 849/852 live-confirmation round (dev stack, compact profile, real corpus,
real streamed turns), by an agent who is neither the auditor nor the committer of this remediation.
This confirms the fixes are **present and correct in the live DOM**; it is explicitly **not** the
measured four-palette axe re-measure the paragraph above reserves for the next auditor.

**Scope in time:** these readings were taken against **round 1's** code, before §remediation-2
(#507) landed. They confirm round 1's structural claims and say nothing about R2.1's copy-control
naming and hit-area changes, which postdate the measurement and need their own live check.

**F-05 — `scrollable-region-focusable`.** In the live reading pane, `DocumentPane`'s own
`.scroll-region` carries `tabindex="0"`, `role="region"`, `aria-label="Document content"`. On a
cited document containing tables, the markdown pass had marked **all 5** `<table>` elements with
`tabindex="0"` and `aria-label="Table"`, and `role` left unset — which is `markScrollableRegions`'
deliberate choice to let a table keep its own role. **3 of the 5 genuinely overflowed**
horizontally at a 1500px viewport (`scrollWidth` 635/690/712 vs `clientWidth` 620/511/509), so the
elements made focusable are real clipped scroll containers and not a vacuous pass on non-scrolling
tables — the failure mode `scrollable-region-focusable` actually describes.

**F-08 — `nested-interactive`.** The live reasoning block's disclosure is a real
`<button>` (`tagName === 'BUTTON'`), the block contains **zero** `[role="button"]` shims, and the
copy control is a SIBLING — `disclosure.contains(copy)` is `false` with 2 buttons in the block.
That is the exact structural shape the fix claims, observed on a streamed reasoning turn rather
than in a fixture.

**Rider finding (tempdoc 848 regression, re-checked here because the same block is involved):** a
reasoning turn survives a full window reload — after `page.goto` plus re-entry from the sidebar, the
settled turn re-rendered its reasoning block (count 1, same real-button structure). The 848 fix
holds live.

---

## §remediation-2 — F-09, and the gate blind spot that let F-07 reach a human

Round 1 fixed the three SERIOUS findings and **explicitly deferred F-09** (see above). Round 2 takes
it, plus one structural item round 1 surfaced as a side effect: the reason F-07 had to be found by
hand.

### R2.1 — F-09: the copy control's name and both controls' hit areas

`modules/ui-web/src/shell-v0/components/chat/ReasoningBlock.ts`

The audit measured, on the copy control: accessible name **the clipboard glyph itself** (computed
from *content*, so `title="Copy reasoning"` could never win), hit area **23 x 19**; and on the header
row **541 x 20**. Both sizes are under the WCAG 2.2 **2.5.8** (Target Size, Minimum) 24 x 24 CSS-px
floor. axe flagged neither — `button-name` is satisfied by the emoji, and 2.5.8 is WCAG 2.2 while the
audit ran 2.1 — so this finding exists only because a human looked.

Built on the **post-#499** markup (round 1 restructured the header into a real disclosure `<button>`
with the copy control as its *sibling*), not on the v0.1.0 shape the audit measured:

- **Name.** `aria-label="Copy reasoning"` on the button, and the glyph moved into a
  `<span aria-hidden="true">` so there is no content left to compute a name from. `title` is kept —
  the pointer affordance is unchanged. This is the `title` + `aria-label` pairing
  `UnifiedChatView.ts:1602-1609` already uses for its own Copy action, so the repo has one shape for
  naming a glyph-only copy control, not two.
- **Target size.** `.copy-btn` gets `min-width`/`min-height: 24px` plus `display: inline-flex` and
  centring, so the box grows around the glyph without moving or resizing it; `.disclosure` gets
  `min-height: 24px` (it is already wide by `flex: 1` — only the block axis was short). 24px is
  written as the literal the criterion names rather than routed through a design-scale token, so a
  later scale re-tune cannot quietly drop the row back under the floor. Neither rule changes the
  resting appearance: both controls were already taller/wider than their ink at rest.

Regression pins in `ReasoningBlock.test.ts` (`describe('ReasoningBlock — F-09 …')`): the accessible
name comes from `aria-label` and the glyph is `aria-hidden`, `title` survives, and the size
declarations are asserted **scoped to their own rule body** (a `ruleBody()` helper), so a stray
`24px` elsewhere in the sheet cannot satisfy them. **Honest limit:** happy-dom does not lay out
shadow content, so there is no truthful *computed* box to read — the declarations are what is
asserted, and the measured proof stays with the next live audit.

### R2.2 — the contrast gate could not see the high-contrast palettes

`scripts/ci/check-contrast-matrix.mjs` (+ new `scripts/ci/check-contrast-matrix.test.mjs`)

Found while fixing F-07 in round 1 and logged then: the gate parsed **`:root` and
`[data-theme="light"]` only**. The two high-contrast blocks in `styles/tokens.css` (`.high-contrast`,
`[data-theme="light"].high-contrast`) were structurally invisible to it — an HC pairing could not
fail this gate no matter how bad it was. That is a gate that cannot bite on half the shipped
palettes, and it is why an HC contrast defect had to reach a manual auditor.

**What changed.** The gate now resolves four palettes and runs the same pairing matrix on each:
**34 → 68 pairings**. The hard part is not the maths, it is that the HC blocks *inherit* — each
declares a handful of tokens and leaves the rest to the palette beneath it, so an effective value has
to be resolved the way the cascade resolves it or the gate reports gaps that do not exist. The chains
implemented:

| palette | resolution chain |
|---|---|
| `dark` | `:root` |
| `light` | `:root` → `[data-theme="light"]` |
| `hc-dark` | `:root` → `.high-contrast` |
| `hc-light` | `:root` → `[data-theme="light"]` → `.high-contrast` → `[data-theme="light"].high-contrast` |

The third layer of the `hc-light` chain is the one that is easy to drop: `.high-contrast` (0,1,0) is
declared *after* `[data-theme="light"]` (0,1,0) and *outside* the `@layer core-theme` the theme blocks
sit in, so in light+HC it wins for every token the light-specific HC block does not redeclare
(`--text-ghost`, `--glass-border-strong`). Omitting it would resolve those to the light theme's values
— a wrong answer that still *looks* like a resolved one.

**Scope boundary (no fork).** The gate owns the **role** pairings (`accent-on-<role>` on
`accent-<role>`; `text-<role>` on `surface-1`; the 596 tooltip pair). The achromatic **text-grade**
ramp under HC — which is what F-07 actually was — stays owned by round 1's
`shell-v0/themes/highContrastTextRoles.test.ts`, which holds it to the stricter AAA floor plus grade
ordering. Two authorities on the same tokens would be a fork, so the gate carries a pointer instead.
**Said plainly: this extension would not by itself have caught F-07** — it closes the structural
blindness for the role pairings, and round 1's test closes the grade ramp. The pair of them is the
closure; neither alone is.

**Findings on the real tree: none.** All 68 pairings clear the WCAG AA hard floor, so no
expected-state/baseline entry was needed and none was added. The HC palettes add 9 new APCA
advisories (18 total, up from 9) — all `hc-dark` duplicates of the `dark` rows, which is the
*finding*, not noise: neither HC block redeclares a single role token, so **hc-dark is byte-identical
to dark for every role pairing**. That is the measured form of audit finding **F-01 (INFO)** — HC
delivers no uplift where the chromatic colour actually lives. Recorded, not acted on: repainting role
tokens per palette is presentation-authority work that needs its own pass and its own measured audit.

**Test sibling** (`check-contrast-matrix.test.mjs`, node + `assert`, the
`check-accent-as-text.test.mjs` shape): 33 checks covering an HC-**declared** token read at its HC
value (`hc-light --surface-1` = `#f5f5f5`, not light's), every HC-**inherited** role token read at the
inherited value (both directions, all 8 roles), the third-layer cascade case
(`hc-light --text-ghost` = the *dark* HC block's `#666666`), the real tree evaluating to zero
failures, and — the one that proves the extension can bite — a **constructed sub-AA HC pair that
FAILS** (`#999999` on an *inherited* `#ffffff`, 2.85:1), asserted to fail *only* in the HC palette
while the same pairing passes in the base palette beneath it.

### §verification (round 2)

| check | result |
|---|---|
| `npm run typecheck` (ui-web) | clean |
| `npm run test:unit:run` (ui-web) | **430 files / 5480 tests, all pass** |
| `node scripts/ci/check-contrast-matrix.mjs` | **OK — 68 pairings across 4 palettes**, exit 0 (was 34 across 2) |
| `node scripts/ci/check-contrast-matrix.test.mjs` | **OK — 33 checks**, exit 0 |
| rest of the `ui-web-gates` recipe | green, except the three known reds below |
| `check-theme-token-closure` | RED — **pre-existing**, the 3 known `RecentsMenu.ts` ghosts (`expected-state.v1.json` `theme-token-closure-red`) |
| `check-accent-as-text` | RED — **pre-existing**, `ActionLedgerView.ts` (`expected-state.v1.json` `accent-as-text-red`) |
| `check-controls-a11y` | RED — **pre-existing**, the known `UnifiedChatView.ts` title-on-disabled (audit F-11), count unchanged at 1 |
| non-ASCII audit of the diff | clean — added non-ASCII is prose punctuation only; the copy glyph stays an HTML entity |

**Honest limit, unchanged from round 1.** Both items are verified by unit test and gate, not by a
second live axe/oracle run. The 2.5.8 sizes in particular are asserted as *declarations*; the
confirming measurement belongs to the next independent auditor (`ux-audit-closure`: reviewer is not
the committer).
