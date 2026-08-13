# F11 — The answer tail, in one line (design only)

```
status: DESIGN — DO NOT EXECUTE until the orchestrator clears the queue
created: 2026-08-13
scope: everything below a settled answer in the Search v3 window
parent: docs/tempdocs/822-t3code-search-window.md (§2 laws, §4b F-series, §5 log F7/F8/F9)
donor: t3code @ b73232bdd31e83914a8a943960c7dc4b6390b39b (session clone, scratchpad/t3code-clone)
owner directions this design executes (verbatim, 2026-08-13):
  1. "the entire content should at most be one line below the response."
  2. "the model name could move into the input bar."
  3. "the source should simply say 'Sources' and the arrow to expand right next to it."
  4. "sources should also have the same small text size"
blocked-on: F10 (composer effort control) owns Sv3Composer.ts right now — see §3.4
```

---

## 1. Current-state inventory (what is there today, with cites)

### 1.1 The three stacked rows

| # | Row | Rendered by | Style | Height (computed) |
|---|---|---|---|---|
| 1 | `Partly grounded — some statements are not backed by your documents · 10.0 s · Qwen Qwen3.5-9B` | `Sv3Main.ts:958-967` (`answerFrameLine`) → `<p class="answer-frame" role="note" data-testid="sv3-answer-frame">` | `Sv3Main.ts:414-423`: `margin: var(--space-1) 0 0`, `padding-inline: var(--space-1)`, `color: var(--secondary-label)`, `font-size: var(--font-size-sv3-xs)` (12px), `line-height: 1.5`; receipt span `font-variant-numeric: tabular-nums` | 4 + 18 = **22px** (fit audit measured the line at 18px) |
| 2 | `▸ 5 SOURCES` | the SHARED `jf-citations-panel` (`Sv3Main.ts:1039-1049` mounts it; the disclosure itself is `CitationsPanel.ts:457-467`) | `CitationsPanel.ts:82-96`: `font-size: var(--font-size-sm)` (bridged to 14px at `Sv3Main.ts:271`), `text-transform: uppercase`, `letter-spacing: 0.04em`, `padding: .25rem 0 .5rem`, glyph `▸` U+25B8 rotating 90° when open (`:100-109`) | 10 (`.sv3-citations` margin, `Sv3Main.ts:289-292`) + 8 (`:host` margin, `CitationsPanel.ts:78-81`) + 31 (fit audit measured) = **49px** |
| 3 | `Copy answer` (hover/focus-revealed) | `Sv3Main.ts:977-993` (`turnActions`) | `Sv3Main.ts:429-470`: `.turn-actions` `opacity: 0` → 1 on `.turn:hover` / `.turn:focus-within` / `.turn-actions:focus-within`, `--duration-sv3-layout` 200ms, `padding-inline: var(--space-0-5)` (2px — inconsistent with row 1's 4px); `.turn-action` `padding: 2px 8px`, 1px transparent border, `--control-radius`, 12px | 4 + 20 = **24px** |

Assembly: `Sv3Main.ts:910` (`answerFrameLine` then `citations`) and `:912` (`turnNote` then `turnActions`).
Turn rhythm: `.turn { padding-bottom: var(--space-4) }` = 16px, donor `chat/MessagesTimeline.tsx:936-939` (`pb-4`) — `Sv3Main.ts:199-204`.

**Tail total today ≈ 95–103px** below the answer block, before the 16px turn gap.

### 1.2 The derivations behind them

- `sv3-honesty.ts:140-161` `projectSv3AnswerFrame(turn)` → `{ label, tail }`. `label` is the SHARED authority's string (`evidenceProjection.ts:171-188`), `null` for a fully-grounded answer. `tail` is `sv3ReceiptTail(durationMs, modelLabel)` (`:107-114`) — `"10.0 s · Qwen Qwen3.5-9B"`, each part omitted when unknown, never fabricated.
- The model is stamped **at the terminal** (`SearchV3View.ts:1341-1350`, `this.aiSnapshot?.runtime.modelLabel ?? null`) — deliberately, so a transcript re-read after a model swap still names the model that wrote each answer (the shipped window's re-read-at-render behaviour is the recorded defect this avoids: `sv3-honesty.ts:126-127`).
- The five frame labels, all of them `"<verdict> — <elaboration>"` on an em dash (U+2014), `evidenceProjection.ts:171-188`:
  - `transform` → `Model-generated structure — not retrieved from your documents`
  - `partially-grounded` → `Partly grounded — some statements are not backed by your documents`
  - `sourced` → `Based on your documents — per-sentence grounding not verified`
  - `ungrounded` (degraded) → `Searched your documents but found nothing to cite — treat this as the model's own answer`
  - `ungrounded` → `Model answer — this mode does not search your documents`
  - `grounded` → `null` (no line; the inline marks already say it)
- **The count-suppression rule (F7, the fit audit's "best composition decision in F4–F8", audit §4 line 308):** `Sv3Main.ts:1029-1032` `panelSpeaks(turn)` is the ONE test; `turnNote` (`:1218-1249`, decision at `:1242`) renders `"N sources"` **only when the panel is silent**, and `null` (never told) is not `0`.

### 1.3 The disclosure is not ours

`sourcesExpanded` is `state: true` (`CitationsPanel.ts:49-55`) — internal. The component has three header paths and **no header-less path**:

| Path | Header | Body |
|---|---|---|
| `renderTieredSources` (`:457-467`) | `<button class="panel-header">▸ N source(s)` — the disclosure | gated on `sourcesExpanded` |
| `renderFlatSources` (`:402-412`) | `<div class="panel-header">N sources retrieved` | always rendered |
| citation-only fallback (`:379-394`) | `<div class="panel-header">N citations` | always rendered |

Consumers: `SearchV2View.ts:2221`, `Sv3Main.ts:1041`, `SummarizeView.ts:243`. Register rows exist for it already (`governance/execution-surfaces.v1.json:394`, `governance/run-renderers.v1.json:60`).

### 1.4 What the fit audit already said about this region

- `f-series-fit-audit.md:71-76` — frame line CONFORMANT (resting-earned, honesty tier); citations disclosure CONFORMANT "see axis 5 on the caps"; copy CONFORMANT (opacity 0 → 1, 200ms, measured).
- `:97-98` — the 87px of settled chrome is: rewrite note 17 + answer frame 18 + reasoning header 20 + sources disclosure 31.
- `:224-250` **AXIS 3, NEEDS-DECISION**: three imported disclosure dialects (`▸` UPPERCASE 14px / `▶` sentence-case 13px / bare `▶` 11px) for one class of act — "either the window declares one disclosure affordance … or it accepts that expand is a component-owned idiom".
- `:331-345` **AXIS 5, NEEDS-DECISION**: "v3 uses UPPERCASE **nowhere**"; `▸ 3 SOURCES` / `GROUNDS THE ANSWER` are an imported dialect (`CitationsPanel.ts:87,216,249`).
- `:390-402` record-restored turns render 0 frame lines and 0 citation panels — *honestly absent*, CONFORMANT; the open question is only whether a reader can tell **why**.
- `response-rendering-gap.md:318-330` §(e) owner-taste — E1–E5 are all answer-**body** items (underline density, raw `[n]`, fallback superscripts, body alpha, file chips). **None of them touches the tail.** The tail's open owner questions are the two audit NEEDS-DECISIONs above, which §4 below closes.

**This design answers axis 3 and axis 5 for the window**: v3 declares ONE disclosure affordance (the donor's own inline one), owns it, and stops rendering the panel's dialect.

---

## 2. The one-line tail — implementable spec

### 2.1 Donor authority (mined from the clone, not the spec doc)

| Donor element | File:line | Anatomy |
|---|---|---|
| Assistant message footer | `chat/MessagesTimeline.tsx:1131-1146` | `mt-1.5 flex items-center gap-2 text-xs tabular-nums opacity-0 transition-opacity duration-200 focus-within:opacity-100 group-hover/assistant:opacity-100` — **one row**, copy button then timestamp; timestamp hidden while streaming (`!row.message.streaming`) |
| Copy button | `chat/MessageCopyButton.tsx:60-79` | ghost `Button size="xs"`, `text-muted-foreground hover:text-foreground`, `CopyIcon size-3` (12px) → `CheckIcon size-3 text-primary` when copied, `aria-label`, tooltip "Copy to clipboard" |
| `xs` button ladder | `ui/button.tsx:30` | desktop `h-6` (24px), `gap-1` (4px), `px-[calc(8px-1px)]`, `text-xs` |
| **Inline disclosure** (the "Sources" idiom) | `chat/MessagesTimeline.tsx:1090-1106` (`TurnFoldTimelineRow`) | `<button aria-expanded>` · `flex items-center gap-1 rounded-md px-1 text-xs text-muted-foreground tabular-nums transition-colors hover:text-foreground` · label span · `ChevronRightIcon`/`ChevronDownIcon` `size-3.5` (14px). **Sentence case. No letter-spacing. Same 12px as the metadata around it.** |

The owner's four directions and the donor's own footer are the same object: one 12px row, sentence case, gap-2, action revealed on hover/focus.

### 2.2 Composition

```
[facts]  [Sources ▸]  [⧉]
 ↑ resting, opacity 1   ↑ resting, opacity 1   ↑ opacity 0 → 1 on turn hover/focus-within
```

Order and reasoning:

1. **facts** — `<span data-testid="sv3-answer-frame">` (testid preserved so F7's assertions keep meaning): `"<verdict> · <duration>"`, internal separator `" · "` (unchanged composition rules from `sv3-honesty.ts`).
2. **`Sources ▸`** — the window's own disclosure button.
3. **copy** — icon-only, last. The donor puts copy first; we put it last because ours is the only revealed element in the row and a leading hidden control would leave a 24px hole before the resting text at rest.

Separators: `" · "` lives **only inside the facts text node**. Between the facts block and the controls there is no dot — an 8px flex gap, exactly the donor's `gap-2`. A middle dot between a sentence and a button is neither the donor's idiom nor ours.

### 2.3 Exact style spec (all values are existing sv3 tokens — no new tokens)

```css
/* ONE tail row per turn. Donor chat/MessagesTimeline.tsx:1131 (mt-1.5, gap-2, text-xs,
   tabular-nums, items-center). Honesty facts hold at opacity 1 (818 §6b L14); only .tail-copy
   yields, on its own, exactly as the F7 action bar did. */
.tail {
  display: flex;
  flex-wrap: wrap;          /* the honest overflow: it wraps, it never truncates or hides */
  align-items: center;
  gap: var(--space-2);                 /* 8px  — donor gap-2 */
  min-height: var(--space-6);          /* 24px — donor xs control height; no jitter on reveal */
  margin-top: var(--space-1-5);        /* 6px  — donor mt-1.5 */
  padding-inline: var(--space-1);      /* 4px  — aligns with .answer's own inset (Sv3Main.ts:233) */
  color: var(--secondary-label);
  font-size: var(--font-size-sv3-xs);  /* 12px — owner direction 4 */
  line-height: 1.5;
  font-variant-numeric: tabular-nums;  /* donor applies it at the row */
}

/* The disclosure. Donor TurnFoldTimelineRow (chat/MessagesTimeline.tsx:1101): sentence case,
   gap-1, rounded, px-1, muted → foreground on hover. NO uppercase, NO letter-spacing. */
.tail-sources {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);                 /* 4px — donor gap-1 */
  height: var(--space-6);              /* 24px */
  padding-inline: var(--space-1);      /* 4px — donor px-1 */
  border: 0;
  border-radius: var(--control-radius);/* 8px — charter law 3: controls take --control-radius */
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.tail-sources:hover { color: var(--foreground); }
.tail-sources:focus-visible { outline: 2px solid var(--ring); outline-offset: 1px; }
.tail-chevron { flex-shrink: 0; color: var(--icon-muted); }   /* 14px — donor size-3.5 */

/* The one thing that yields. */
.tail-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  inline-size: var(--space-6);
  block-size: var(--space-6);          /* 24×24 — donor Button size="xs" */
  border: 0;
  border-radius: var(--control-radius);
  background: none;
  color: inherit;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--duration-sv3-layout) var(--ease-sv3-enter);  /* 200ms — donor duration-200 */
}
/* SEPARATE rules, never a nested :has(:focus-visible) — that selector is a Chrome syntax error
   and killed the whole list in F3 (tempdoc 822 §5, `static-green ≠ live-working`). */
.turn:hover .tail-copy { opacity: 1; }
.turn:focus-within .tail-copy { opacity: 1; }
.tail-copy:focus-visible { opacity: 1; outline: 2px solid var(--ring); outline-offset: 1px; }
.tail-copy:hover { background: var(--accent-surface); color: var(--foreground); }
@media (prefers-reduced-motion: reduce) { .tail-copy { transition: none; } }

/* The expanded body sits under the row. An outer-tree rule on the host beats the component's own
   :host margin (the F8 pane lesson, tempdoc 822 §5) — so the panel's 0.5rem is neutralised here
   rather than inside the shared component. */
.sv3-citations { display: block; margin: var(--space-2) 0 0; }
```

Glyphs (all already in the shared set, `components/Icon.ts` — zero shared-file edits):
`chevron-right` (collapsed) / `chevron-down` (expanded) at **14px**; `clipboard-copy` at **12px**; `check-circle-2` at **12px** for the copied state. The donor's exact `CopyIcon`/`CheckIcon` would be a two-line addition to `Icon.ts` — offered in §4, not assumed.

### 2.4 The facts segment

`projectSv3AnswerFrame` gains a second argument and returns three fields:

```ts
export interface Sv3AnswerFrame {
  readonly verdict: string | null;      // "Partly grounded"          — RESTING
  readonly elaboration: string;         // "some statements are …"    — accessible name + title
  readonly tail: string;                // "10.0 s" (+ " · <model>" only when it differs)
}
export function projectSv3AnswerFrame(turn: Sv3Turn, currentModelLabel: string | null): Sv3AnswerFrame | null
```

**Split mechanism** — a new pure helper in `sv3-honesty.ts`:

```ts
/** The shared authority's label is "<verdict> — <elaboration>" for every frame it words
 *  (evidenceProjection.ts:171-188). We RE-WORD NOTHING: the two halves are the authority's own
 *  substrings and the whole string survives verbatim in the accessible name. If the authority ever
 *  words a label without the em dash, the WHOLE string rests — the fail-safe is more text, never less. */
export function splitSv3FrameLabel(label: string): { verdict: string; elaboration: string };
```

Rejected alternative: adding `answerFrameParts()` to `evidenceProjection.ts`. It is tidier (one table, no substring work) but edits a shared authority three windows render through for a v3-local presentation need. Recorded as the follow-up if a second window ever wants the split.

**Markup** (the elaboration is never hidden from assistive tech — see §2.6):

```html
<span class="tail-facts" data-testid="sv3-answer-frame" title="{verdict} — {elaboration} · {tail}">
  <span class="visually-hidden">{verdict} — {elaboration} · {tail}</span>
  <span aria-hidden="true">{verdict} · {tail}</span>
</span>
```

**The model, and why it can still appear here (owner direction 2 has an honesty edge):**
today the model is stamped per turn at its terminal, precisely so an old answer is not re-labelled after a model swap (`sv3-honesty.ts:126-127`). Moving the name to the composer makes the composer name the *current* model. Rule:

```ts
sv3TailModelLabel(stamped: string | null, current: string | null): string | null
// null      when stamped === current      → the composer already says it (the ordinary case)
// stamped   when they differ              → this answer was written by a different model
// stamped   when current is null          → unknown is not "the same"
```

With one local model this is `null` on every turn, so the resting tail is exactly what the owner asked for; the name reappears only in the one case where the composer would otherwise be lying about this answer. Owner may overrule (§4, choice 4).

### 2.5 The Sources trigger

| Condition | Trigger |
|---|---|
| `evidence.sources.length > 0` | `Sources` |
| `sources.length === 0 && matches.length > 0` | `Citations` (the panel holds citation-matches, not retrieved sources — the word must not over-claim) |
| both empty, or `evidence === null`, or streaming | no trigger (today's `panelSpeaks`, `Sv3Main.ts:1029-1032`, unchanged) |

- Label: **bare, no count** (owner direction 3 — the default; see §4 choice 1 for the alternative, which is one constant).
- `aria-expanded`, `aria-controls` pointing at the panel; accessible name carries the count always: `aria-label="Sources: 5"`. Expanded/collapsed state lives on the turn: `Sv3Main` holds `expandedSources: Set<turnId>` — per turn, never global, so expanding turn 3 does not expand turn 7.
- Chevron: `chevron-right` collapsed → `chevron-down` expanded (donor `TurnFoldTimelineRow`). The donor's rotation-of-one-glyph (`CitationsPanel.ts:107-109`) is not copied; the donor's own inline disclosure swaps glyphs.

**The shared-component change this requires** (additive, default-preserving, owner-authorised in §4 choice 5):

```ts
// CitationsPanel.ts
externalDisclosure: { type: Boolean, attribute: false },   // default false → today's markup exactly
sourcesExpanded:   { type: Boolean, attribute: false },    // promoted from `state: true`
```

When `externalDisclosure` is true: **all three** header paths (`:379-394`, `:402-412`, `:457-467`) render no header, and **all three** bodies are gated on `sourcesExpanded`. When false: byte-identical to today for `SearchV2View` and `SummarizeView`.

Why not the cheaper alternatives:
- `part="panel-header"` + restyle from sv3: the trigger stays on its own row → the tail is 2 rows → fails owner direction 1.
- `display: contents` on the host to hoist the header into the row: hoists the body too. Rejected.
- A v3-local sources list: forks the product's evidence-rendering authority. Refused by the charter.

### 2.6 L14 compliance analysis

L14 (`818-search-v2-skeleton.md:188-196`): *"the resting surface shows the identifying minimum; elaboration extends on hover AND keyboard focus, never by default. Hard boundary: honesty facts never hide behind hover — counts, verdicts, LOCKED, grounding stay resting-visible; only elaboration extends. Focus parity is mandatory."*

| Fact | Tier | Resting? | Verdict |
|---|---|---|---|
| Grounding **verdict** ("Partly grounded") | honesty fact | **yes**, opacity 1 | COMPLIANT |
| Duration | honesty fact (measured receipt) | **yes** | COMPLIANT |
| Model name | honesty fact | **yes** — relocated to the composer, and re-appears in the tail whenever it would otherwise mislead (§2.4) | COMPLIANT, with §4 choice 4 open |
| **Elaboration** ("some statements are not backed…") | elaboration by L14's own words | no — `title` for pointer, **always in the accessible name** for AT | see below |
| Source **count** | honesty fact today | **no** under owner direction 3 | see below + §4 choice 1 |
| Copy action | action, not a fact | no (revealed) | COMPLIANT — unchanged from F7 |

**Elaboration mechanism, honestly stated.** L14 demands focus parity because hover-only fails accessibility. A native `title` on a non-focusable span is hover-only in every browser — so `title` alone would violate the clause. The specified markup instead keeps the *entire* authority string permanently in the accessible name (`.visually-hidden`), so assistive tech never loses a word: nothing is "revealed" to AT because nothing was hidden from it. The residual gap is a **sighted keyboard-only reader**, who sees the verdict but not the elaboration. That is accepted, with the reason: the alternatives are (a) an inline expansion that resizes the turn under the pointer — the exact jitter the F7 action bar was built to avoid (`Sv3Main.ts:425-428`), or (b) a second tab stop per turn for a sentence fragment. Owner may pick (a) or "keep it resting" in §4 choice 2.

**THE COUNT QUESTION.** The count is a resting honesty fact today, and F7 deliberately routed it: `panelSpeaks` → the panel's header owns it, else `turnNote` says it (`Sv3Main.ts:1242`). Suppressing the panel header without putting the count in the trigger removes it from the resting surface for sighted readers (AT keeps it via `aria-label="Sources: 5"`).

- Is it *elaboration*? Argument for: the verdict already tells you what the answer stood on; the arithmetic ("5") is detail you open the panel for. Argument against: 1 source vs 12 is the cheapest calibration signal an answer has, and hiding a number behind a click is the move L14 was written against.
- The three shapes, with what each costs, are in §4 choice 1. **The default implemented is the owner's literal direction (bare "Sources"), and the switch is one constant** (`SV3_SOURCES_COUNT_IN_TRIGGER`), with both branches unit-tested.

### 2.7 State behaviour

| Turn state | Facts | Sources | Copy | Row |
|---|---|---|---|---|
| **Streaming** | absent (`projectSv3AnswerFrame` refuses a non-complete turn, `sv3-honesty.ts:144`) | absent (`panelSpeaks` false while streaming) | absent (`Sv3Main.ts:978`) | **no row at all** — unchanged from today, and the donor's own rule (`!row.message.streaming`, `MessagesTimeline.tsx:1136`) |
| **Settled, grounded** | `10.0 s` (verdict `null` by design) | present | present | one row |
| **Settled, partial/sourced/transform/ungrounded** | `<verdict> · 10.0 s` | present when the panel speaks | present | one row |
| **Record-restored** (F6: no receipt, no evidence) | absent — `durationMs` and `modelLabel` are `null` (`sv3-record.ts:173`), frame is `null` | absent | present | row renders with **only** the revealed copy: visually empty at rest, 24px tall. **Honest never-told** — no dash, no "unknown", no stray separator |
| **Halted / refused / failed** | the turn note's own words (`Sv3Main.ts:1232-1244`) move into the facts slot | absent | absent | one row, wraps if the failure detail is long |
| **Agent turn** | the run receipt (`sv3RunReceiptLabel`) in the facts slot | n/a | n/a | one row |

Known adjacency, deliberately **not** changed here: a completed ask with `evidence !== null` but zero sources can render both the degraded-ungrounded verdict *and* a `"0 sources"` note — the same fact twice. Pre-existing (`Sv3Main.ts:1231-1244` + `evidenceProjection.ts:184-185`), out of this design's scope, logged to the inbox.

### 2.8 Turn rhythm re-measured (question E)

| | today | one-line tail | Δ |
|---|---|---|---|
| answer → facts | 4px | **6px** (donor `mt-1.5`) | +2 |
| facts row | 18px | **24px** (row min-height = donor xs control) | +6 |
| facts → disclosure | 18px | — | −18 |
| disclosure | 31px | — | −31 |
| disclosure → actions | 12px | — | −12 |
| actions row | 20px | — | −20 |
| **tail total** | **103px** | **30px** | **−73px (−71%)** |
| `.turn` bottom padding | 16px | **16px — unchanged** | 0 |

**The 16px turn rhythm does NOT need respecifying.** The donor's own timeline is exactly this stack: message body → `mt-1.5` footer → `pb-4` (`MessagesTimeline.tsx:936-939,1131`). Our tail becomes the donor's footer, so 16px is now *more* donor-faithful than it was with 103px of evidence chrome inside the turn. Do not add compensating space — the answer-to-tail gap is respecified (4 → 6px) and nothing else is.
Expanded state: the panel body pushes down from the row with its own `--space-2` (8px) and the turn grows; the rhythm below it is untouched.

**One-line guarantee.** Worst realistic resting width at 12px: longest verdict (48 chars ≈ 293px) + `· 10.0 s` (≈ 58px) + gap + `Sources ▸` (≈ 80px) + gap + copy (24px) ≈ **463px**, against a 768px measure (`.transcript max-inline-size: 48rem`, `Sv3Main.ts:193-198`) and a 640px main-column floor (`sv3-boundaries.ts`). With a quiet count (+14px) and a differing model (+110px) the worst case is ≈ 587px — still one line at the floor. `flex-wrap: wrap` is the honest fallback for the pathological case: **the tail wraps, it never truncates and never hides.** No `text-overflow` anywhere in the facts.

---

## 3. The model name in the composer (owner direction 2) + the F10 handshake

### 3.1 Donor authority

`chat/ProviderModelPicker.tsx:149-187` — the model identity IS a `ComposerControl` in the composer's control row: ghost, `h-7`, `gap-1.5`, `px-2.5`, `text-secondary-label hover:text-foreground` (`chat/ComposerControl.tsx:9`), containing a provider icon (16px), a **truncating** title (`min-w-0 flex-1 overflow-hidden truncate`) whose full label lives in a tooltip, and a chevron. Width `max-w-48 shrink sm:max-w-56` (192/224px), compact `max-w-42 shrink-0` (168px). Compact overflow route: `CompactComposerControlsMenu.tsx:34` (ellipsis menu).

Ours degenerates: one local model, no provider concept (§4b, already ratified for the effort control). So the donor's *picker* becomes a **static label** — same slot, same row, no chevron, no menu.

### 3.2 Spec

Placement: inside the existing `.controls` row (`Sv3Composer.ts:849`), **after** the effort control (control first, fact second — the donor's own footer order, `MessagesTimeline.tsx:1131`).

```css
/* A FACT in a row of controls: it must not look clickable. One step down from the control's
   14px/500 so the eye separates "thing I can change" from "thing I am told". */
.model-label {
  min-width: 0;
  max-inline-size: 12rem;          /* 192px — donor max-w-48 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--secondary-label);
  font-size: var(--font-size-sv3-xs);   /* 12px */
  font-weight: 400;
  cursor: default;
}
```

- Markup: `<span class="model-label" data-testid="sv3-composer-model" title=${modelLabel}>${modelLabel}</span>` — `title` carries the full string when the 192px clamp truncates (donor's tooltip-on-truncation, `ProviderModelPicker.tsx:179-183`). Not focusable, not a button, no `aria-label` inventing a role.
- Resting form: **the label verbatim from the authority** — `aiSnapshot.runtime.modelLabel`, the same expression `SearchV3View.ts:1349` stamps on a turn. No shortening, no re-casing, no vendor-stripping: the window does not author model names. (If the owner wants `Qwen3.5-9B` instead of `Qwen Qwen3.5-9B`, that is a *backend label* question, not a v3 presentation one — §4 choice 6.)
- **Compact/docked behaviour — THE anti-pattern to avoid:** the effort control's label evaporates when docked (`Sv3Composer.ts:518-541`: `max-inline-size: 0`, `opacity: 0`, `translateX(-0.25rem) scaleX(0.95)`). The model label **must NOT** adopt that pattern — docked is the transcript-reading state, i.e. exactly when "which model wrote this" is being asked. It stays at full width in both `hero` and `docked`. Budget check at the 640px main-column floor: docked control row = effort control glyph-only (≈56px) + gap 4 + label ≤192px = 252px against a ≈600px band with a 32px primary action. Fits with room.
- **AI-offline interplay (E8):** the label states **identity only, never state**. When `modelLabel` is `null`/empty the label is **absent** — no "no model", no "offline", no em dash. The availability notice above the box (`Sv3Composer.ts:225-234`, fed by `projectAvailability` at `SearchV3View.ts:1281-1296`) is the ONE place a state is said, in the authority's own wording. A second sense of "offline" in the same box is the duplicate-fact class the audit measured zero of.
- **Verification item, not an assumption:** whether `runtime.modelLabel` is non-null while the model is *unloaded* has not been probed. If it is, the composer names a configured-but-not-loaded model. That is acceptable only because the notice says the state — but it must be **live-checked** (§5.3) and, if the label persists with no runtime at all, gated on the availability projection instead.

### 3.3 Rejected: the effort control carries the model

`Effort: Standard · Qwen3.5-9B` in one trigger fails three ways: (1) the label evaporates when docked, taking the model with it; (2) it implies the model is one of the things the menu changes — it is not; (3) it re-merges two facts the donor keeps in two controls.

### 3.4 THE F10 HANDSHAKE — what F10's implementer must consume

`Sv3Composer.ts` is **currently modified in the working tree by the in-flight F10 slice**. This design does not touch it. F10 lands first; this slice then adds the label. What F10 must do, or must not do, for that to be a clean add:

| # | Handshake item |
|---|---|
| H1 | **Do not put the model name inside `.control-label`.** It evaporates on dock (`:518-541`). The model is a sibling of the effort control, not part of it. |
| H2 | **Leave `.controls` a plain flex row that accepts a second child** (`:354-361` already does — `gap: var(--space-1)`, `min-width: 0`). Do not make it single-child-assuming (no `:only-child` styling, no fixed width). |
| H3 | **Do not add a second chevron/menu affordance to the row.** One local model = no picker. §4b's ratified adaptation already spent the donor's picker slot on effort. |
| H4 | **Keep `.composer-control` styles scoped to `button.composer-control`** (`:370`) so a non-button `.model-label` sibling does not inherit control chrome. Already true — keep it. |
| H5 | F10 owns `effort` as a reflected attribute (`:652`). This slice adds `model-label` the same way: a reflected string attribute on `jf-sv3-composer`, bound at `SearchV3View.ts:2013-2023` next to `effort=${this.effort}` as `model-label=${this.aiSnapshot?.runtime.modelLabel ?? ''}`. |
| H6 | The `@focusout` handler on `.controls` (`:849`, `onControlsFocusOut` closes the effort menu) must tolerate a **non-focusable** sibling — a `<span>` never fires focusout, but the handler must not assume `relatedTarget` is inside a control. Verify at `:986` (`querySelector('.controls')`). |
| H7 | If F10 introduces a compact/overflow menu for the control row (donor `CompactComposerControlsMenu`), the model label **does not go into it** — it is a fact, not a control, and an overflow menu is a hover-gated hiding place for an honesty fact (L14). |

---

## 4. Owner-choice menu

**Choice 1 — the source count (the one real L14 tension).**

| | Resting tail | Cost | Notes |
|---|---|---|---|
| **(i) bare** — *your literal direction, the implemented default* | `Partly grounded · 10.0 s   Sources ▸   ⧉` | count leaves the sighted resting surface; kept in the accessible name | quietest line; the count is one click away and in the panel |
| **(ii) quiet count** — *recommended* | `… Sources · 5 ▸` or `5 Sources ▸` | ≈ 14px of width | keeps a resting honesty fact that F7 placed deliberately; 1-vs-12 sources is the cheapest calibration an answer offers |
| (iii) bare + count in the expanded panel header | as (i), count reappears on expand | needs the suppressed header back as a body heading — re-creates the thing this design removes | not recommended: it re-imports the uppercase dialect |

**Recommendation: (ii).** It costs one character-width and it is the difference between "the answer had sources" and "the answer had *five* sources", which is exactly the sort of number L14's hard boundary was written to keep resting. The spec implements (i) by default and makes (ii) a one-constant flip with both branches tested — say the word and it is a one-line change, not a redesign.

**Choice 2 — the elaboration ("some statements are not backed by your documents").**
(a) **default/recommended**: verdict rests, elaboration in `title` + permanently in the accessible name (§2.6); (b) elaboration stays resting — honest and simple, but the line will wrap at the 640px column floor for the two long labels; (c) elaboration moves into the expanded panel — conflates "why" with "which".

**Choice 3 — the copy affordance.** (a) **default**: icon-only, 24×24, donor-faithful (`MessageCopyButton`), accessible name stays `Copy answer`; (b) keep the `Copy answer` text button (≈ +75px on the line, and the only text in the row that is not a fact).

**Choice 4 — the model name when it differs from the composer's.** (a) **default/recommended**: it re-appears in that turn's tail (the composer would otherwise mislabel an old answer); (b) never in the tail — accepts that after a model swap the window silently attributes old answers to the new model, which is the exact defect `sv3-honesty.ts:126-127` was written to avoid.

**Choice 5 — authorise the shared-component edit.** `CitationsPanel.ts` gains an additive, default-false `externalDisclosure` (§2.5). Without it the tail cannot be one line — the disclosure stays in its own row inside the imported component. (a) **default/recommended**: authorise, with regression tests pinning `SearchV2View`/`SummarizeView` unchanged; (b) decline → the tail is 2 rows (facts row + the panel's own uppercase disclosure) and owner directions 3 and 4 go unmet.

**Choice 6 — the uppercase sweep (question D), and the residual it leaves.** See §6. (a) **default/recommended**: kill it where the window authors it (the disclosure) and leave the shared panel's *body* dialect (`tier-header`, `score-metric`) to its own slice; (b) restyle `CitationsPanel` for all three consumers now — bigger, touches two shipped windows.

**Choice 7 — the record-restored silence** (fit audit `:398-401`, NEEDS-DECISION). A cold-loaded turn has no receipt and now shows an entirely empty tail row at rest. Add a quiet "no receipt recorded for this conversation" note? **Recommendation: no** — never-told stays never-told, and a per-turn apology is 100 turns of chrome for one fact that belongs to the conversation, not the turn. Owner's call.

**Choice 8 — the model label's wording.** The composer shows `runtime.modelLabel` verbatim (`Qwen Qwen3.5-9B`). Shortening it (`Qwen3.5-9B`) is a backend-label change, not a presentation one; the window will not re-author a model name. Say so if you want it shortened and it becomes a backend item.

---

## 5. Implementation plan — **DO NOT EXECUTE until the orchestrator clears the queue**

Sequencing: **F10 must land first** (it owns `Sv3Composer.ts`); the citation-mark design session must not be mid-edit in `Sv3Main.ts`. One worker, opus, Edit/Write only (UTF-8 rule).

### 5.1 Files

| # | File | Change |
|---|---|---|
| 1 | `views/search-v3/sv3-honesty.ts` | `splitSv3FrameLabel`; `sv3TailModelLabel`; `sv3SourcesTrigger(evidence)` → `'Sources' \| 'Citations' \| null`; `SV3_SOURCES_COUNT_IN_TRIGGER` constant; `projectSv3AnswerFrame(turn, currentModelLabel)` returns `{verdict, elaboration, tail}` |
| 2 | `views/search-v3/Sv3Main.ts` | one `.tail` row replacing `.answer-frame` + `.turn-actions` + the panel's disclosure; `currentModelLabel` property; per-turn `expandedSources: Set<string>`; `externalDisclosure` + `.sourcesExpanded` on the panel; **delete** the dead `.turn-action`/`.turn-actions`/`.answer-frame` rules (retire-with-a-sweep — no residue) |
| 3 | `components/chat/CitationsPanel.ts` | additive `externalDisclosure` (default false) + `sourcesExpanded` promoted to a public property; three header paths suppressed and three bodies gated when external. **Owner choice 5.** |
| 4 | `views/search-v3/SearchV3View.ts` | `.currentModelLabel` → `jf-sv3-main`; `model-label` → `jf-sv3-composer` (one expression, shared with `:1349`) |
| 5 | `views/search-v3/Sv3Composer.ts` | the static `.model-label` span in `.controls` (**after F10 lands**) |
| 6 | `views/search-v3/fixtures.ts` | copy register: keep `TURN_COPY_LABEL` as the accessible name; add `SOURCES_LABEL` / `CITATIONS_LABEL` |
| 7 | `governance/run-renderers.v1.json` | update the `sv3-honesty.ts` `consumerNotes` (`:67`): it no longer renders the authority's label as one node — it renders both halves, re-wording neither, with the whole string in the accessible name |

### 5.2 Tests (write red first; every one of these must bite)

Pure (`sv3-honesty.test.ts`):
1. `splitSv3FrameLabel` over **all five** current authority labels (table-driven through `answerFrameLabel(frame, degraded)`): each splits into two non-empty halves; recomposition `verdict + ' — ' + elaboration` equals the authority string byte-for-byte.
2. Fail-safe: a label with no `' — '` → `{verdict: whole, elaboration: ''}`.
3. `sv3SourcesTrigger`: sources>0 → `Sources`; sources=0 & matches>0 → `Citations`; both 0 → `null`.
4. Count constant: both branches render `Sources` and `5 Sources` (so §4 choice 1 is a flip, not a rewrite).
5. `sv3TailModelLabel`: equal → `null`; different → stamped; `current === null` → stamped.
6. `projectSv3AnswerFrame` never fabricates: `durationMs: null, modelLabel: null, evidence: null` → `null`.

DOM (`SearchV3View.tail.test.ts` new + `SearchV3View.honesty.test.ts` updated):
7. A settled turn renders **exactly one** `[data-testid=sv3-turn-tail]` and **zero** `<p class="answer-frame">` / `.turn-actions` nodes (structural — pins the sweep).
8. **L14 resting**: the CSS text sets `opacity: 0` on `.tail-copy` and on nothing else in the row; three separate reveal rules exist; **no `:has(:focus-visible)` nesting anywhere** (the F3 keeper).
9. Resting text of the facts span (the `aria-hidden` half) is exactly `"<verdict> · <tail>"` — the elaboration is NOT in it; the `.visually-hidden` half contains the whole authority string.
10. Streaming → no tail row at all.
11. Record-restored (`durationMs`/`modelLabel`/`evidence` all null) → row present, facts span absent, no stray `·`.
12. **Suppression no-regression**: with the panel speaking, the number `5` appears in the turn subtree exactly once (trigger `aria-label`) — probe: delete the `panelSpeaks` guard in `turnNote` and this fails. With the panel silent and the count known, it appears exactly once in the facts.
13. `aria-expanded` false→true→false on click; the panel body renders only when expanded; chevron name swaps `chevron-right`↔`chevron-down`; expanding turn A leaves turn B collapsed.
14. `jf-citations-panel` in sv3 renders **no** `.panel-header` in its shadow root; the same component **without** `externalDisclosure` still does (guards `SearchV2View`/`SummarizeView`).
15. `CitationsPanel.test.ts`: default-path markup byte-identical to today (all three header paths).
16. Copy: click writes the answer and swaps to the check glyph; a failed write shows nothing; the accessible name stays `Copy answer`; the live region announces `Copied`.
17. sv3 keeper: the window's own CSS contains **no** `text-transform: uppercase` (question D, made permanent).
18. Composer: `.model-label` renders when `model-label` is non-empty, is **absent** when empty (no "unknown"), is **not** inside `.control-label`, and **no** rule sets `max-inline-size: 0` on it in the docked state (the anti-evaporation pin).
19. Composer E8: the model label's text contains no availability vocabulary; the `unavailable-reason` notice is unchanged.
20. `Sv3Main.imports.test.ts`: add the tail's computed-contrast pairs (`--secondary-label` on `--background`, `--foreground` on `--accent-surface` for the hovered copy) ≥ 4.5:1 — the F9 import-bridge clause applies to the row's own colours too.

Mutation probes to declare in the log: (a) remove the model-equality suppression → 5 fails; (b) render the elaboration in the resting half → 9 fails; (c) remove `externalDisclosure` from the sv3 mount → 14 fails; (d) drop the `panelSpeaks` guard → 12 fails; (e) collapse the three reveal rules into a nested `:has()` → 8 fails.

### 5.3 Live verification (the tier the unit tests cannot reach — F3's lesson)

1. Settled turn, real answer: measure the tail row's rect — **height ≤ 24px, exactly one row**, top within 6–8px of the answer block's bottom; no wrap at the 768px measure *and* with the sidebar and pane pushed to their ceilings (640px main-column floor).
2. `getComputedStyle` at rest: facts `opacity: 1`, trigger `opacity: 1`, copy `opacity: 0`; under the pointer copy `opacity: 1` with `transition-duration: 0.2s`; **and again via keyboard focus** (Tab to the copy button) — the F3 defect was invisible to CSS-text tests.
3. Expand: panel body opens with **no second header**; record (screenshot) that the body's `tier-header`/`score-metric` are still uppercase — the known residual of choice 6.
4. Tab order from the answer: trigger → copy, both with a visible focus ring.
5. Composer: model label visible in **hero and docked**, at the 640px floor, not truncated for the real label; `title` present.
6. **Probe `runtime.modelLabel` with the model unloaded / AI offline** (§3.2): if it still reports a name, record it and decide whether to gate on availability.
7. Record-restored conversation: claim one from the sidebar — the tail row is visually empty at rest and yields only the copy icon; zero page errors.
8. Turn rhythm: measure two consecutive settled turns — tail bottom → next question bubble top = 16px.

---

## 6. Question D — the uppercase / letter-spaced idiom, swept

**The window authors none of it.** `grep text-transform modules/ui-web/src/shell-v0/views/search-v3/*.ts` → **zero hits** (non-test). Every uppercase micro-label visible inside the v3 window arrives from an imported component. The three letter-spacing uses sv3 *does* author are all a different role and **all stay** — there is no v3-authored micro-caps label to sweep:

| Site | Value | Role | Verdict |
|---|---|---|---|
| `Sv3Palette.ts:272` `.shortcut` | `0.1em` | keycap tracking on `Ctrl K` glyph text | **stays** — key caps, not a label idiom |
| `Sv3Palette.ts:312-327` `.key` | `0.1em` | keycap tracking on the footer's key chips | **stays** — same keycap role |
| `Sv3Composer.ts:184` `.headline` | `-0.025em` | negative display tracking | **stays** — donor's own display type |

**The imported dialect, and what falls with the disclosure:**

| Site | Idiom | Fate |
|---|---|---|
| `CitationsPanel.ts:82-96` `.panel-header` | UPPERCASE + `0.04em`, 14px | **dies in v3** — suppressed by `externalDisclosure`; the v3 trigger is sentence case at 12px. Survives untouched in `SearchV2View` / `SummarizeView`. |
| `CitationsPanel.ts:247-251` `.tier-header` (`GROUNDS THE ANSWER`) | UPPERCASE + `0.06em` | **stays** — inside the panel body, unreachable from sv3 CSS (shadow DOM, no `part`). Visible only when the reader expands. |
| `CitationsPanel.ts:210-217` `.score-metric` | UPPERCASE + `0.04em` | **stays**, same reason |
| `CitationsPanel.ts:252+` `.doc-group-label` | (audit `:195` lists it with the other two) | **stays**, same reason |
| `ToolCallCard.ts:147-155` `LOW · COMPLETED` | UPPERCASE status | **stays** — out of the tail's scope; already a NEEDS-DECISION in fit-audit axis 5 |

**Recommendation:** kill it where the window authors the affordance (the disclosure, which is what the owner is looking at), and **leave the panel-body dialect to its own slice**. Reasons: (1) it is behind a disclosure, so it is not on the resting surface the owner is judging; (2) restyling it means editing a component two shipped windows render through, for taste, not correctness; (3) F4 already recorded this exact class ("needs the shared component to expose parts or geometry tokens — its own slice"). The consequence is honest and should be recorded in the log: **after this slice, expanding Sources reveals a dialect the collapsed row no longer speaks.** That is the price of not forking the product's evidence renderer, and it is visible only on demand.

---

## 7. What this design closes and what it leaves open

**Closes:** owner directions 1–4; fit-audit axis 3 for the tail (v3 declares ONE disclosure affordance — the donor's inline one — and owns it); axis 5's headline case (`▸ N SOURCES` leaves the window); the 2px/4px inset inconsistency between the old frame line and action bar; 73px of vertical budget per settled turn.

**Leaves open:** the panel-body uppercase (choice 6b); the reasoning-block and tool-card disclosure dialects (axis 3's other two — a later coherence slice, and the tail's trigger is now the reference form for it); the record-restored "why is there no receipt" question (choice 7); the ungrounded-verdict + `0 sources` duplicate (inbox); whether `runtime.modelLabel` is honest while the model is unloaded (§5.3 item 6).
