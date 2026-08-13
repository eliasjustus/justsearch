# F4–F8 design-coherence audit — do the adopted capabilities FIT the donor system?

```
status: COMPLETE
created: 2026-08-13
auditor: independent (implemented none of F1–F8)
scope: composition, not per-slice compliance
method: source reading + live measurement (Playwright, the running stack's own FE at
  http://localhost:5173, 1568x900, read-only — no ask sent, no run delegated, no stack
  lifecycle touched; fixture turns pinned onto the live regions via property override,
  the same technique F7/F8 used for their own live passes)
rubric: 822 §2 donor laws; 822-donor/t3code-system.md §5 motion / §6 anatomy / §7 density
```

## 0. Verdict in one paragraph

**The window v3 AUTHORS has not re-accreted.** Resting-visible interactive elements are
**16 in every one of the three states measured** — hero, settled conversation, live run
holding — against the donor's 27 home-screen baseline. Every fact F4–F8 added is either
content, hover-revealed on the donor's own idiom, or disclosure-gated; the one duplicate-fact
risk (source count) is structurally guarded. **The window v3 IMPORTS has.** Six shipped
components now render inside the donor frame; three of them (`jf-tool-call-card`,
`jf-reasoning-block`, `jf-citation-hover-card`) have **no token bridge at all** and resolve
**every** custom property from the shipped app's `:root`, which is currently the LIGHT
palette — measured: a tool-call card paints `rgb(248,249,252)` (near-white) with its tool
name at `oklch(0.97 0 0)` (near-white) inside a window whose background is `oklch(14.5% 0 0)`.
The re-accretion is not in the chrome count; it is in the **number of design systems on
screen at once**, which F4→F8 took from one to two, and from one typeface to four.

---

## 1. AXIS 1 — Chrome-economy accounting

### 1.1 Resting-visible interactive elements (deep shadow walk, opacity-aware)

| State | Interactive at rest | Hidden until hover/focus | Where measured |
|---|---|---|---|
| Hero / empty | **16** | 6 (row pins) | `s1_hero.json` |
| Settled conversation, real record-restored | **16** | 6 | `s2_settled.json` |
| Settled + full honesty pack (fixture turn, panel collapsed) | **22** (16 + 6 in-transcript) | 7 (6 pins + Copy answer) | `s3_pin.json` |
| Settled, citations panel opened by the reader | **27** | 7 | `s3_pin.json` |
| Live agent run holding on an approval + a budget gate | **32** | 7 | `s3_pin.json` |

The 16 are invariant across states and are the window's actual chrome:
`New search`, `Toggle sidebar`, 6 × session row, sidebar grip, 3 × topbar control,
composer field, 2 × scope chip, primary-action slot. **Nothing F4–F8 added is resting
chrome.** Every increment above 16 is attached to a turn, and disappears with it:
2 citation marks, 1 markdown link, 1 reasoning header + its copy button, 1 sources
disclosure; and in the run state 2 tool-card controls + 3 budget-decision buttons.

**CONFORMANT.** The founding 40 %-chrome complaint is not reproduced by the window's own
surface. (It *is* reproduced by the shipped shell *around* the window — the screenshot at
`scratchpad/s3_run.png` shows the rail, the Simple/Detailed toggle, Copy URL, the status
bar's five counters, a Tasks panel and a 4-step walkthrough toast all overlapping the
window box — but that is cutover scope, already recorded in the A1 log entry, and not
this audit's subject.)

### 1.2 Resting-visible FACTS, classified

| Fact | State | Tier | Verdict |
|---|---|---|---|
| Session-row title × 6 | all | resting-earned | CONFORMANT |
| Session-row timestamp × 6 | all | resting-earned (donor §6.1: the label that yields) | CONFORMANT |
| Shelf label ("Recent") | all | resting-earned | CONFORMANT |
| Window title ("Search v3") | all | resting-earned | CONFORMANT |
| Hero headline, corpus line ("Searching 851 files") | hero only | resting-earned; evaporates on dock | CONFORMANT |
| Composer placeholder | all | resting-earned | CONFORMANT |
| Question bubble | settled | content | CONFORMANT |
| Answer body (markdown) | settled | content | CONFORMANT |
| "Interpreted as: …" (C8 rewrite note) | settled | resting-earned (honesty tier) | CONFORMANT |
| Answer frame ("Partly grounded — … · 4.3 s · Qwen Qwen3.5-9B") | settled | resting-earned (honesty tier) | CONFORMANT |
| Turn note (halted / failed / source count) | settled | resting-earned, **suppressed when the panel speaks** | CONFORMANT |
| Reasoning block header ("Thought for 2s") | settled | disclosure-gated (content collapsed) | CONFORMANT |
| Citations "▸ 3 SOURCES" | settled | disclosure-gated | CONFORMANT (see axis 5 on the caps) |
| Per-source cards, tier headers, doc-group labels | settled, opened | disclosure-gated | CONFORMANT |
| Copy answer | settled | hover/focus-revealed (opacity 0 → 1, 200 ms, measured) | CONFORMANT |
| Row pin | all | hover/focus-revealed slot swap | CONFORMANT |
| Run feed prose / tool card / "Progress …" note | run | content | CONFORMANT |
| Approval prompt + budget prompt | run | resting-earned (act-now, incompressible by design) | CONFORMANT |
| **Topbar `⋮` "Window settings" and `◫` "Window layout"** | all | **arguably-creep — inert** | **DEFECT** |

**DEFECT-1 (chrome).** `Sv3Topbar.ts:112-127` renders two buttons with `aria-label`s and
no `@click` handler and no consumer. They are 2 of the 16 resting interactive elements in
every state — 12.5 % of the window's entire resting chrome budget is affordances that do
nothing. Slice-1 scaffolding that survived F2–F8 untouched. Repro: click either; nothing
happens, no event leaves the topbar.

### 1.3 Transcript content-to-chrome ratio (settled state)

Measured as the union of occupied vertical bands inside the transcript column
(`scratchpad/ratio.py`, over the measured rects):

- Panel collapsed: content **377 px**, chrome **87 px** → **chrome 18.8 %**.
- Panel expanded by the reader: content 377 px, "chrome" 309 px → 45.0 % — but the
  expanded evidence is reader-requested content, so the honest number is the first one.

The 87 px of chrome is: rewrite note (17 px), answer frame (18 px), reasoning header
(20 px), sources disclosure (31 px). **CONFORMANT** against the 40 % complaint.

**Caveat worth recording:** a *real* record-restored conversation measures **less** chrome
than the fixture — 0 px, because a cold-loaded turn carries no frame line and no citations
panel (measured: `record_hasFrame = 0`, `record_hasCitations = 0` on a real conversation
claimed from the sidebar). That is F6's recorded honest-never-told gap, and it means the
honesty pack's chrome cost is currently only paid by turns this window itself streamed.

---

## 2. AXIS 2 — The shipped-component seam

### 2.1 The root cause, measured

`document.documentElement[data-theme] = "light"`. The sv3 window carries **no `theme`
attribute and no code that sets one** (`grep -n theme SearchV3View.ts` → zero hits;
`sv3-tokens.css.ts:276` defines `:host([theme='light'])` for "a later theme seam" that was
never wired). So the window is permanently dark while the app is light, and **every custom
property the window does not itself define falls through to the app's light `:root`.**

Measured inside the window, at the component hosts:

| Token | Resolved inside sv3 | sv3's own nearest | Consumers |
|---|---|---|---|
| `--surface-secondary` | `rgb(248, 249, 252)` | `--card` (dark) | tool card fill |
| `--surface-subtle` | `rgba(0, 30, 60, 0.08)` | `--muted` | reasoning container |
| `--text-primary` | `rgba(15, 23, 42, 0.95)` | `--foreground` | tool card, hover card |
| `--text-secondary` | `rgba(15, 23, 42, 0.88)` | `--muted-foreground` | all three |
| `--text-muted` | `rgba(15, 23, 42, 0.58)` | `--secondary-label` | reasoning, hover card |
| `--border-muted` / `--border-subtle` | `rgba(0, 30, 60, 0.30)` | `--border` | all three |
| `--accent-primary` / `--accent-tint` | `oklch(45% 0.18 180)` | `--primary` `oklch(75% 0.15 180)` | focus rings, marks |
| `--accent-success` | `oklch(38% 0.18 145)` | `--success` `oklch(69.6% 0.17 162)` | tool status word |
| `--font-size-sm` | **0.8125 rem (13 px)** | `--font-size-sv3-sm` **14 px** | all three |
| `--font-size-xs` | **0.6875 rem (11 px)** | `--font-size-sv3-xs` **12 px** | all three |
| `--shadow-float` | `0 8px 32px rgba(0,30,60,.14) …` | `--dialog-shadow` | hover card |
| `--z-modal` | `100` | `--z-tooltip` / `--z-overlay` | hover card |

### 2.2 Per-component verdict

**`jf-tool-call-card` — CLASH (DEFECT-2).** Measured: `.tool-card` background
`rgb(248,249,252)`, `.tool-name` colour `oklch(0.97 0 0)`. **White text on a white card in
a dark window** — see `scratchpad/s3_run.png`, where the tool name is invisible and only
the dark "LOW · COMPLETED" is legible. Type ramp 13/11 px inside a 14/12 px window.
Bridgeable: `--surface-secondary --surface-tertiary --surface-2 --text-primary
--text-secondary --text-warning --border-subtle --border-strong --accent --accent-tint
--accent-on-tint --accent-danger-45 --accent-warning-45 --font-size-sm --font-size-xs
--duration-normal --font-display`. **Not** bridgeable: `ToolCallCard.ts:354` sets the status
word's colour with an inline `style="color: ${statusAccent(...)}"` — a second colour
authority inside the component, immune to any host token (and an `accent-as-text` instance
its own `utils/statusTone.ts:112-118` header warns against).

**`jf-reasoning-block` — CLASH (DEFECT-3).** Measured: `.container` background
`rgba(0,30,60,0.08)` (a light-theme wash) with text at `rgba(15,23,42,0.58)` — dark slate
on `oklch(14.5% 0 0)`. This is a contrast failure, not a taste question. Sizes 13/11 px.
Bridgeable in full: `--surface-subtle --text-muted --text-secondary --border-muted
--accent-primary --font-size-sm --font-size-xs --duration-fast --ease-standard`. Its own
`ReasoningBlock.ts:120-123` re-points `--text-primary` on its nested markdown block, which
is *proof the bridge pattern works on these components* — the shipped app already does it.

**`jf-citation-hover-card` — CLASH (unmeasured in situ, same mechanism).** Every one of its
11 tokens resolves from `:root`; `.card` background `var(--surface-2)` → `rgb(241,242,248)`
with `--text-primary` dark. It is also the one component with a hard-coded literal
(`box-shadow: 0 4px 12px rgba(0,0,0,0.3)`, `CitationHoverCard.ts:61`) — harmless in dark,
wrong in the donor's elevation-inversion law (law 6: dark removes drop shadows).
Bridgeable: `--surface-2 --text-primary --text-secondary --text-muted --text-tint
--border-subtle --font-size-sm --font-size-xs --z-modal --duration-fast --ease-standard`.

**`jf-markdown-block` — BLENDS on colour, CLASHES on type and geometry.** The
`Sv3Main.ts:252-279` bridge works: measured body `oklch(0.97 0 0)` at 14 px, code spans at
12 px on `--muted`. What the bridge cannot reach, measured:
- **`h2` = 21 px / weight 700; `h3` = 16.38 px / 700; `th` = 700.** These are UA defaults
  (1.5 em / 1.17 em of 14 px, `bold`). Donor law 10 is *four effective sizes, weights
  400/500/600, **no bold***. The answer body is the largest text region in the window and
  it is the one place the donor's type law does not hold. **DEFECT-4.**
- **`code`/`pre` font-family resolves to the generic `monospace`** (Courier New on this
  platform), not `--font-mono` (`ui-monospace, 'SF Mono', … Consolas`). Meanwhile
  `CitationsPanel`'s `.doc-group-label` in the *same transcript* resolves
  `ui-monospace, monospace`. **Two mono faces, one turn.**
- Radii 4 px (code) and 6 px (pre) against the sv3 ladder (`--control-radius` 8,
  `--radius-md` 8, `--radius-lg` 10, `--radius-2xl` 18).
This is the F4 "recorded MarkdownBlock geometry gap", and **it generalises**: the same
"declares nothing / hard-codes geometry" shape recurs in CitationsPanel (6 px cards),
ReasoningBlock (6 px container, 3 px copy button) and DocumentPane (6 px toggle group).
Needs the shared components to expose parts or geometry tokens — a change to authorities
three surfaces render through.

**`jf-citations-panel` — BLENDS on colour and size, CLASHES on typeface and case.**
The bridge reaches it: `--font-size-sm/xs` → 14/12 px, `--surface-2/3`, `--text-*`,
`--border-subtle` all measured as sv3 values. Two residues:
- **`button.source`, `button.source-open` and everything inside them compute
  `font-family: Arial`** — the UA button default, because the panel never declares
  `font: inherit` on its buttons. The same is true of `ReasoningBlock`'s `.copy-btn` and
  `ToolCallCard`'s `.expand-toggle`. sv3's own buttons all set `font-family: inherit`
  (`Sv3Main.ts:361,398,479`), so the window authored the rule correctly and the imports
  ignore it. **Four typefaces render in one transcript: system-ui/-apple-system (donor),
  Arial (imported buttons), Courier (markdown code), ui-monospace (panel doc labels).**
  **DEFECT-5.**
- `text-transform: uppercase` on `.panel-header`, `.tier-header` and `.doc-group-label`
  (`CitationsPanel.ts:87,216,249`) — see axis 5.

**`jf-document-pane` — BLENDS.** `Sv3Pane.ts:78-94` is the best bridge in the window:
measured host background `oklch(0.145 0 0)`, foreground `oklch(0.97 0 0)`, 14/12 px. Two
residues, both from the deliberately-unbridged `--accent-tint*` family: the selected
Rendered/Source toggle computes `rgb(0,80,120)` on `oklch(0.45 0.18 180 / 0.16)` (a dark
teal on a dark teal wash — low contrast), and `.path` uses the generic `monospace` again.
Toggle radius 6 px, off-ladder. The deliberate exclusion is defensible for the *passage
highlight*; it should not extend to the pane's own chrome.

**Bridgeable vs component-change split:** of the seam's defects, DEFECT-2/3 and the hover
card are **TOKEN-BRIDGEABLE today** (three CSS blocks in `Sv3Main.ts` / `Sv3Pane.ts`, the
token lists above). DEFECT-4 (headings/tables), DEFECT-5 (button `font: inherit`) and the
inline `statusAccent` colour need edits inside the shared components.

### 2.3 The accidental name collision — DEFECT-6

`sv3-tokens.css.ts` defines `--accent` as a **4 % white fill**
(`color-mix(in srgb, oklch(100% 0 0) 4%, transparent)`, its row-hover material). The
shipped `:root` defines `--accent` as a **colour** (`oklch(45% 0.18 180)`), and
`ToolCallCard.ts:245` reads it as one: `.tool-resource a { color: var(--accent) }`. Inside
sv3 that link therefore paints at ~4 % opacity — effectively invisible. Measured:
`--accent` is the one token reported as "BRIDGED" at all five component hosts, and none of
those bridges was intended. A window-scoped token sheet that reuses a shipped token *name*
for a different *meaning* silently rewrites every nested shipped component that reads it.

---

## 3. AXIS 3 — Reveal-idiom coherence

**One authored grammar, three imported dialects.**

*The authored grammar* (donor §6.1 / §7 "every hover-reveal also listens to focus"):
- Sidebar row status → pin slot swap: `:host(:hover…)` **and** `button.row:focus-visible`
  and `button.pin:focus-visible` (`Sv3SessionRow.ts:199,207-214`), hidden state out of
  flow, `min-inline-size` floor, **never-yields** for `act-now`/`broken`
  (`Sv3SessionRow.ts:227-231`). Measured 32 px slot in every state.
- Per-turn action bar: `opacity: 0 → 1` on `.turn:hover`, `.turn:focus-within`,
  `.turn-actions:focus-within`, 200 ms (`Sv3Main.ts:332-349`; measured opacity 1 and
  `transition-duration: 0.2s` under the pointer).
These two are the same grammar, and they are the donor's. **CONFORMANT.**

*The imported dialects* — three click-disclosures, no shared affordance treatment:

| Disclosure | Trigger glyph | Label treatment | Size | Source |
|---|---|---|---|---|
| Citations | `▸` (U+25B8) | UPPERCASE + `letter-spacing: .04em` | 14 px | `CitationsPanel.ts:464` |
| Reasoning | `▶` (U+25B6) | sentence case, weight 500 | 13 px | `ReasoningBlock.ts:169` |
| Tool call | `▶`/`▼` | none (bare glyph) | 11 px | `ToolCallCard.ts:362` |

Three glyphs, three cases, three type sizes for **one class of act** ("show me the rest of
this"). None of the three is reachable by the authored hover/focus grammar, and none
matches the other two. **NEEDS-DECISION:** either the window declares one disclosure
affordance and the shared components adopt it, or it accepts that "expand" is a
component-owned idiom and stops treating the citations panel as a v3 surface.

*Two further reveal triggers*, both coherent with their own rules and with each other:
the pane (opens on a citation click, closes on Escape / its own control) and the palette
(Ctrl+K, click-outside, Escape) — see axis 6 for where the Escape half breaks.

**One inconsistency inside the authored grammar** (already recorded in the F3 log, restated
because it is a composition finding): `in-motion` yields on hover while `act-now` and
`broken` do not. A run that is *streaming right now* is arguably as much an honesty fact as
a broken one; the donor's counterpart (their PR badge) is a *state*, not a *motion*. Leave
as-is or extend the never-yields set — **NEEDS-DECISION**, low stakes.

---

## 4. AXIS 4 — Status & colour budget under load

### 4.1 The authored budget is intact

Every colour-spending site in `search-v3/*.ts` (`grep var(--success|--warning|--destructive|--error|--info|--primary)`):

| Site | Token | Rung |
|---|---|---|
| Session row dot | `--success` / `--warning` (+ 60 % ping) / `--destructive` | act-now / in-motion / broken |
| Composer slot: Stop | `--destructive` @ 90 % | act-now (halting) |
| Composer slot: Answer | `--success` | act-now |
| Composer field invalid | `--destructive` @ 36 % border, 16 % ring | broken |
| Run prompt block | `--success` @ 40 % border, 8 % fill | act-now |
| Turn note broken / run "Error" label | `--error-foreground` | broken |

**Exactly three roles, no fourth. Resting rows spend none** — measured: all 6 rows resting,
`.status-slot` background `rgba(0,0,0,0)`, no dot, timestamp only. **CONFORMANT** to law 5.
The composer availability banner (`Sv3Composer.ts:198-207`) is deliberately **uncoloured**
(`--muted` fill, `--foreground` text) — correct: unavailability is neither act-now,
in-motion nor broken. The locked view and the record notice likewise spend no colour.

### 4.2 The imported budget is not

Colours reaching the screen from the six imports, none of them on the three-colour budget:
- `--text-tint` → `oklch(70.7% .165 254)` blue on `[1]` marks and on "Grounds 1 sentence"
  (bridged to `--info-foreground`, a deliberate F4 mapping of donor `index.css:1908`).
- `--accent-tint` → `--primary` teal on the selected mark and the pane highlight.
- `--accent-success` → `oklch(38% .18 145)` dark green on the tool card's status word,
  **via an inline style**, unreachable from any token.
- `--accent-danger-45` / `--accent-warning-45` on the tool card's risk borders.
- `--accent-tint-16` + `rgb(0,80,120)` on the pane's selected mode toggle.
- The pane's error alert: `oklch(0.42 0.2 25)` text on `oklch(0.42 0.2 25 / 0.16)`.

That is **four to six additional hues** in the settled/run states. The donor does license a
second palette — "semantic tokens for system health; raw palette for domain taxonomy"
(§6.2) — and citation/grounding hues are plausibly domain taxonomy. But the donor *chose*
its taxonomy; here it arrived. **NEEDS-DECISION:** ratify a v3 evidence-hue set (grounding
tiers, risk tiers) as the donor's second palette, or bridge them onto the three-colour
budget. Do not leave it undeclared.

### 4.3 Same-fact-twice audit

| Fact | Renders where | Verdict |
|---|---|---|
| Source count | `turnNote` **suppressed** whenever `panelSpeaks(turn)` (`Sv3Main.ts:932-935,1145`) — the panel's own "▸ 3 sources" is the only one | **CONFORMANT — explicitly guarded, the best composition decision in F4–F8** |
| Grounding verdict | answer-frame line (whole-answer) + panel `.grounding` per source + `.cite-sentence.grounding-weak` underline + `[n]` tier colour | Different granularities; defensible. **NEEDS-DECISION** on whether the frame's basis clause and the panel's "GROUNDS THE ANSWER" tier header are one claim said twice |
| Cited document identity | panel `.doc-group-label` + `Open <file>` per card (×N) + pane header `.path` | **3–4 renderings of one filename** in the open-panel + pane state. Imported repetition, not authored |
| Model label / duration | answer frame only | CONFORMANT |
| Run outcome | live feed (attention) → receipt line (record), never both — `run.phase !== 'ended'` gate (`Sv3Main.ts:785`) | CONFORMANT |
| Corpus size | composer landing "Searching 851 files"; the shell status bar also shows 853 | Outside the window's authority (cutover scope) |

No v3-form/shipped-form duplicate of the same fact was found. The disease the old window
had ("three facts render six times") has **not** been reproduced.

---

## 5. AXIS 5 — Copy / vocabulary coherence

**The authored copy is one voice.** `fixtures.ts` is effectively a copy register: sentence
case throughout, user-side naming, one verb per act — `Copy answer` / `Copied`,
`New search`, `Stop the run`, `Add 4,096 tokens`, `Finish with what it has`,
`Interpreted as:`, `Stopped by you.`, `Your last message was not sent.`,
`Add folders in Library to start searching`, `Cited document` / `Close the cited document`.
Refusals name the reader's act, never the system's ("Stopped by you", not "Cancelled").
Readiness wording is read from `reasonFor()` rather than re-authored. **CONFORMANT** to
818's copy law.

**The imported copy is three other voices.** Measured on screen in the settled/run states:

| Clash | Source | Against |
|---|---|---|
| `▸ 3 SOURCES`, `GROUNDS THE ANSWER`, `01-SYSTEM-OVERVIEW.MD` (uppercase + letter-spacing) | `CitationsPanel.ts:87,216,249` | v3 uses UPPERCASE **nowhere**; the donor's own micro-labels are sentence case at `--font-size-xs` |
| `LOW · COMPLETED` (uppercase status), `Risk tier LOW. Assist mode — read-only (LOW)` | `ToolCallCard.ts:147-155` | v3's own run copy: "waiting for your approval (medium risk)" — lower case, reader-side |
| `Model reasoning trace` (aria) / `Thought for 2s` (label) | `ReasoningBlock.ts:156,165` | Two names for one object in one component; v3 names things once |
| `Rendered` / `Source` mode toggle | `DocumentPane.ts` | v3 has no other mode-toggle vocabulary; "Source" collides with the citations panel's "sources" meaning *documents* |
| `Show 1 retrieved (not cited)` | `CitationsPanel.ts` | Parenthetical negation; v3's equivalent register is "never told ≠ zero" stated positively |
| `Open 01-system-overview.md` | `CitationsPanel.ts` | Verb is consistent with v3 (`Open the command palette`) — **no clash** |
| `Copy reasoning` | `ReasoningBlock.ts` | Consistent with `Copy answer` — **no clash** |

**NEEDS-DECISION.** The two genuine one-product breaks are (a) the uppercase micro-caps
idiom, which is a *visual* dialect as much as a copy one, and (b) "Source(s)" meaning three
different things across the transcript (a retrieved document, a rendering mode, and the
grounding tier header).

---

## 6. AXIS 6 — Interaction-model frictions

### 6.1 Escape order — DEFECT-7, severity confirmed live

Repro (`scratchpad/s5_interact.json`, states `after_dblclick` → `rename_after_esc1` →
`rename_after_esc2`): open the pane on a citation → double-click a session row to rename it
(focus lands in the rename input) → press **Escape**.

**Measured: the PANE closes and the rename input survives** (`panePresent` true→false,
`renaming` stays 1). A second Escape cancels the rename. The reader's Escape, pressed while
typing in a text field, destroys a different region. This is the F8 log's noted-not-fixed
residual — it reproduces exactly, and the severity is higher than "loses its cancel key":
the key does something *else*, silently, to a region the reader was not looking at.
Fix shape: the host's capture-phase handler must yield when an inline editor inside the
window owns focus (an `:focus-within` test on the sidebar's rename input), i.e. innermost
transient state wins Escape.

### 6.2 The palette can be left open and keyboard-unreachable — DEFECT-8

Repro (`after_ctrlk` → `after_esc1..3`): focus the sv3 composer, press **Ctrl+K**.
**Both palettes open** — sv3's (`open`, visible) *and* the shipped `jf-command-palette`
(`open`, visible) — and **focus lands in the shipped palette's input**
(`activeElement` path: `jf-shell>jf-command-palette>input`). Pressing Escape closes the
shipped palette and returns focus to `body`, which is **outside the window host**, so
sv3's capture-phase Escape listener never fires again: three consecutive Escapes leave the
sv3 palette open and visible. Only a pointer click dismisses it.

The double-palette itself is the recorded slice-4 cutover-scope item
(`KeybindingRegistry.ts:178`). What is new — and what F8's Escape-ordering work did not
account for — is that the *focus theft* turns a cosmetic duplicate into a **keyboard trap
for the donor palette**. Same fix family as the recorded when-clause; the audit's addition
is that it is now a defect, not an oddity.

### 6.3 Focus flow composer ↔ pane ↔ palette

Composer → pane: opening the pane by clicking a source card leaves focus on the card
(inside the transcript); the pane does not steal focus, which is right for a reading
surface, but nothing moves focus *into* it either, so a keyboard reader must Tab through
the whole remaining transcript to reach the pane's own controls. **NEEDS-DECISION** (the
donor's right panel is not keyboard-entered either; a `tabindex="-1"`+focus on the pane
region on open is the cheap fix). Pane → composer on close: not verified.

### 6.4 The two-window shared store, in-window

Measured on the live stack: the sidebar lists **6 conversations minted by other windows /
earlier sessions** (F6's product behaviour, surfaced to the owner). Claiming one live:
the thread renders from the record in ~2 s, 2 turns, markdown intact, **0 citations panels
and 0 answer-frame lines**. That is *honestly absent* (the record carries no receipt and
F6 refuses to invent one), not wrongly rendered — **CONFORMANT**. No capability of those
conversations was found rendered *wrongly*; the gap is uniform and silent. The one thing a
reader cannot tell is *why* an old turn has no frame while a new one does — there is no
"this conversation predates the receipt" note. **NEEDS-DECISION**, low cost, high honesty
value.

Zero page errors across all six live sessions.

---

## 7. Verdict tally

| Axis | CONFORMANT | TOKEN-BRIDGEABLE | NEEDS-DECISION | DEFECT |
|---|---|---|---|---|
| 1 Chrome economy | 18 facts + all 3 counts | — | — | 1 (inert topbar pair) |
| 2 Component seam | 2 (pane, panel colour) | 3 (tool card, reasoning, hover card) | 1 (theme seam) | 5 (2,3,4,5,6) |
| 3 Reveal idiom | 2 (authored grammar) | — | 2 (3 dialects; in-motion yields) | — |
| 4 Colour budget | 6 sites + 4 no-dup checks | — | 2 (imported hues; grounding granularity) | — |
| 5 Copy | authored register | — | 2 (micro-caps; "source" overload) | — |
| 6 Interaction | 2 (record honesty, no wrong renders) | — | 2 (pane focus entry; pre-receipt turns) | 2 (7, 8) |
| **Total** | — | **3** | **11** | **8** |

---

## 8. Top 5 actions, ranked by fit-improvement per effort

1. **Bridge the three unbridged components** (`jf-tool-call-card`, `jf-reasoning-block`,
   `jf-citation-hover-card`) with the token lists in §2.2, **and resolve the `--accent`
   collision** (§2.3) — one CSS block each in `Sv3Main.ts`/`Sv3Pane.ts`, the pattern the
   window already uses twice. Removes DEFECT-2, -3, -6 and half of the type-ramp split.
   *Highest ratio by a wide margin: ~30 lines of CSS closes the single most visible clash
   in the window (a white card with invisible text).*
2. **Decide the theme seam.** The window is dark-by-construction while the app is
   `data-theme=light`, and that mismatch is what turns every missed token into a polarity
   inversion. Either wire `theme` from the app's theme onto the host (the light set already
   exists, `sv3-tokens.css.ts:276`), or declare the window dark-only and treat *every*
   unbridged token as a defect by policy. **This is the owner choice that decides whether
   action 1 is a fix or a patch.**
3. **Fix the two Escape defects** (§6.1, §6.2): an inline editor inside the window wins
   Escape over the pane; and Ctrl+K must not hand focus to the shipped palette while sv3's
   is open. Small handler changes, and they close a keyboard trap.
4. **Retire or wire the two inert topbar controls** (`Sv3Topbar.ts:112-127`). One deletion
   returns 12.5 % of the resting chrome budget and removes the only pure creep the audit
   found.
5. **Open the shared components' geometry** — `MarkdownBlock` heading/table scale + mono
   face, and `font: inherit` on every imported component's buttons (§2.2, DEFECT-4/-5).
   Largest effort (three surfaces render through these authorities) and the only item that
   cannot be done from inside `search-v3/`, but it is what stops the answer body — the
   window's largest text region — from carrying browser-default bold headings and Arial
   buttons.

---

## 9. The re-accretion question, with numbers

**Not re-accreting, on the axis the complaint was about.** Resting interactive chrome:
**16 → 16 → 16** across hero, settled and run-holding. Transcript chrome share **18.8 %**
against a founding complaint of 40 %. Thirteen honesty facts landed in F7 and **zero** of
them became resting chrome that a state change cannot remove; the one action bar added
hides at opacity 0 and yields on the donor's own trigger; the one count that could have
duplicated is structurally suppressed. Judged as composition, F4–F8 spent the donor's
economy correctly.

**Re-accreting, on an axis the complaint did not name.** The window went from rendering
its own design system to hosting **two**: 6 imported components, of which **3 have no
bridge at all** and **1 more is only half-bridged**; **4 typefaces** in one transcript
(system-ui, Arial, Courier, ui-monospace); **two type ramps** (14/12 authored vs 13/11
inherited); **five off-ladder radii** (3, 4, 6 px against 8/10/14/18/22); **3 disclosure
dialects** for one class of act; and **4–6 hues** beyond the ratified three-colour budget.
The trend is directional: F4 bridged what it imported (2 of 2), F7 and F8 imported 4 more
and bridged 1 (the pane). *The bridge is not keeping pace with the adoption.*

So: the chrome did not come back; **the second design system did**. Every individual F4–F8
decision was lawful under 822 §2 — the laws govern what the window *authors*, and say
nothing about what it *mounts*. That is the gap this audit found, and action 2 is the
owner decision that closes it.

---

## 10. Re-audit after the deferred items land

- **After the keybinding registry / when-clause** (deferred backlog): re-run §6.2 — the
  double-palette *and* the focus theft, since the fix may close one and not the other.
- **After a record-side evidence projection** (F6 residual): re-run §1.3 with a *real*
  cold-loaded turn; the honesty pack's chrome cost has never been measured on record data,
  only on fixtures.
- **After the deferred search integration**: §1.1's invariant 16 is the number to defend —
  result rows, scope chips and a count line are exactly where a search axis re-spends it,
  and §1.2's classification table is the register to check them against.
- **After the shared-component geometry slice** (action 5): re-measure §2.2's heading
  scale, mono faces and the four-typeface count.
- **After any new shipped-component adoption**: re-run the §2.1 token-resolution probe
  before the slice closes — `scratchpad/s4_seam.py`'s `RESOLVE` walk over the component's
  consumed-token list is a 5-minute check that would have caught DEFECT-2 and -3 at F7.
- **If the theme seam is wired** (action 2): the entire §2 measurement set must be re-taken
  in *both* themes; every number in this audit was measured with the app in light.
