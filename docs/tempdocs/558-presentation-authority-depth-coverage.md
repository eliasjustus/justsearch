---
title: "558 — Presentation authority, deeper: the accessible color-pair, projector coverage, and projection totality (557 extended by an independent post-merge audit)"
type: tempdocs
status: "closed — implemented + browser-validated (2026-06-15). D1 closed (576 §6 `check-contrast-matrix` + the 558-impl palette bake + `check-accent-as-text`); D2 acute raw-key leak fixed (559 ADV-1) + regression-guarded; D3 timing-projection shipped (§T); the §U convergence — ONE shared event-row across Activity/History/Timeline + relative-time + structured outcome glyph/tone + who/outcome filter — shipped, full-suite + gates green, and live-validated in the real browser (§V). §7 moot (`independent-review`/`ux-audit-closure` gates retired, 563). Deferred-by-evidence (§V.2): drill-down · inline-undo · catch-up · agent-transparency already exist in the product (AUDIT tab / 'Undo all AI actions' / 'since you last looked') or overlap 565/577. Deferred-by-design: per-op `duration` wire-change, `contrast-color()` enhancement."
created: 2026-05-29
updated: 2026-06-15
extends: tempdoc 557 §2.C (theme/token authority) and §2.A (display authority); tempdoc 550 (lifecycle record completeness)
category: frontend / ux / design-theory / single-authority / accessibility / presentation
related:
  - tempdoc 557 (the presentation projection — display / observed-state / theme as single-authority projections; MERGED). THIS DOC EXTENDS 557, it does not re-derive it.
  - tempdoc 548 (ui-web correct design — the one invariant "two authorities for one concept, neither subordinate"; the prevention-tier ladder Collapse>Unrepresentable>Generate>Gate; §5.2 "a gate's coverage must itself project from a catalog").
  - tempdoc 550 (agent action lifecycle — the canonical LifecycleEvent record + bounded governed projections; the home for the "render occurredAt / carry duration" completeness work in §4).
  - tempdoc 549 / 553 (unified SearchTrace / canonical search-execution record — the structured trace the §5 search-trace item projects).
  - tempdoc 504 (systematic UX audit — defect taxonomy; D6 "internal-state-leaked" is the display-coverage symptom; D3 "metadata-not-surfaced" / D4 "missing-state-narrative" are the totality symptom).
  - tempdoc 530 / 531 (discipline-gate kernel + consumer-drift gate — the enforcement machinery the tier-4 gates here reuse; the §5.2 coverage-as-projection mechanism).
  - tier-register row 30 (independent-review discipline gate) — the process conclusion in §7 extends its scope.
---

# 558 — Presentation authority, deeper

> **What this document is.** A *design theory* for three specific places the JustSearch frontend's
> presentation layer is still wrong — and the correct long-term structure for each. It is **not** a
> new framework. Tempdoc 557 already stated the correct framework (presentation = three
> single-authority projections: **Display**, **Observed-state**, **Theme**) and **it is merged into
> `main`** (commit `32da6fa27`: `present()`, the tri-state `ConnectionPhase`, `TokenName` /
> `applyAppearance`, six CI gates). This doc inherits 557's invariant and ladder wholesale and only
> adds depth where an independent audit proved the framework does not yet bite.

> **The meta-finding (why this doc exists).** A live browser UX audit on 2026-05-29 was run
> **against the merged 557 work** — and still found fresh, user-visible instances of *all three* of
> 557's authority classes. That recurrence is itself the result: 557's **invariant is correct**, but
> its **depth (clause-b unrepresentability) and coverage (the §5.2 meta-point) are incomplete** in
> three generalizable ways. This is not a critique of 557; it is the second half of the same work,
> and it is exactly the pattern 557's own postmortem warned about ("I *saw and narrated* the TaskList
> flood but dismissed it as pre-existing / not-my-scope"). An implementer's validation shares the
> implementer's blind spots — see §7.

---

## 1. Inherited frame (from 548 / 557, not restated)

548 §1: the recurring defect is **two authorities for one concept, neither subordinate**. The
correct invariant is two clauses — **(a) single authority** (one definitional owner; every other
site a typed projection) and **(b) unrepresentability** (the subordinate form cannot be hand-authored
divergently; clause (b) does the prevention). 548 §2's ladder, strongest first: **1 Collapse · 2
Unrepresentable-by-type · 3 Generate · 4 Gate.** 548 §5.2's meta-point: a gate is only as good as
its coverage, and **its coverage must itself be a projection of a catalog** — "the registry
enumerates from the authority," so a newly-added member cannot silently escape.

557 applied this to presentation's three sub-authorities and reached **collapse + a tier-4 gate** for
each. The three deepenings below are each an instance of the *same* invariant 557 used, applied one
level further — to the **unit** an authority models, the **coverage** of its one projector, and the
**totality** of its projections. None of them needs a new framework; each is 548's ladder, un-finished.

| # | Deepening | 557 reached | The gap this doc closes |
|---|---|---|---|
| **1** | Theme authority — the *unit* is a color **pair**, not two atoms | token-*existence* closure (every token resolves) | token-*pair* closure: the (fg,bg) contrast is the concept; model & gate it |
| **2** | Display authority — *coverage* of the one projector | `present()` exists; most consumers routed | a second display authority re-appeared (advisory drawer); gate coverage must enumerate event-classes |
| **3** | Observed-state / lifecycle — *totality* of the projection | tri-state status projected; reconnect solved | the activity projection drops a field the record has; the record lacks a field its surface promises |

---

## 2. Deepening 1 — the theme authority's unit is a color *pair* (HEADLINE)

**Concept.** "A legible interactive surface" — an accent fill *and* the text that sits on it. The
two are one concept: the fill is not legible without a foreground that contrasts with it.

**Defect today (the same two-authority shape, one level down).** The fill and its on-color are
modeled as **two independent tokens**: `--accent-tint: oklch(75% 0.15 …)` (`tokens.css:56`) and
`--accent-on-tint: var(--text-primary)` (`tokens.css:115`) — the on-color is just *near-white*, defined
with **no reference to the accent it must sit on**, and **no palette overrides it** (Nord/Sepia/High-Vis
override `--accent-tint` only). So the on-color cannot follow when the fill changes. There is **no
contrast logic anywhere in the repo** (agent-confirmed: no relative-luminance / WCAG / ratio code).
557's `theme-token-closure` and `color-tokens` gates guarantee every token *exists / is non-literal* —
**not** that a co-occurring (fg,bg) pair is *adequate*. This is precisely 548 §1's "two authorities for
one concept, neither subordinate": the legible-accent-surface has two owners (`accent-tint`,
`accent-on-tint`) that drift apart in contrast.

**Measured proof that existence-closure ≠ contrast-closure.** White-on-accent measured **≈1.96:1** on
the default theme (WCAG AA needs 4.5; 3.0 for large text) on the Continue CTA, the Documents/Structured
scope toggles, the Online/Advanced/Auto toggles, and **every chat user-message bubble** (figures
re-confirmed by a browser canvas oracle — see §De-risk). Applying the **"High Vis" palette — explicitly
sold as "High-contrast for visibility-focused workflows" — made it WORSE: ≈1.35:1** (it brightens the
fill to cyan but leaves the on-color near-white). The **light theme passes (5.56:1)** because its accent
darkens — so the defect is specifically *light foreground on a light-enough accent* (dark / High-Vis),
which is exactly why the pairing invariant must hold *per theme*. 557 even *added*
24 alpha-graded accent tokens and a color-literal gate, and the defect still ships — because the
missing invariant is about the *pair*, not the *atom*.

**Correct end-state.** The on-color is a **projection of the surface role**, not an independent token.
Two structural moves, in ladder order:

- **Collapse / make-unrepresentable (the unit).** A *semantic color role* (`accent-fill`,
  `danger-fill`, `selected`, …) is the authority; its foreground is *derived from* its background to
  meet a contrast floor (or the pair is declared together and the foreground cannot be set
  independently). Components reference the **role**, never a free `color` + `background` pair — so
  "white text on a bright fill" becomes **unrepresentable**: there is no seam at which a component
  picks a foreground that doesn't contrast with its own background. This also **collapses the two
  selected-state visual languages** the audit found (good: outline + accent-*text*, e.g. Settings
  cards = readable; bad: fill + *white*, e.g. toggles/bubbles = failing) into **one** state primitive.
- **Tier-4 contrast-closure gate (the coverage).** A gate computes the WCAG ratio for **every (fg,bg)
  role-pair that can co-occur, across every theme × palette × high-contrast variant**, and fails the
  build below AA. Per 548 §5.2, its coverage **enumerates from the role catalog** — so a *new accent,
  a new palette, or a new role* cannot silently ship a failing pair (the exact escape High-Vis took).

**Honest ceiling (inherited from 557 §5).** Inside Lit `css\`\`` templates, `var()`/literals are
unchecked strings — so tier-2 "compile error" is not reachable *in the template*; the realistic
strongest tier is **collapse (role primitive) at the authoring seam + tier-4 contrast-closure gate**,
not pure unrepresentability. This matches 557's documented stack limit; it is recorded, not papered over.

**Extend, don't reinvent.** The accent tokens, `gen-token-names`, `theme-token-closure`, and
`check-color-tokens` gates already exist; the oklch→sRGB + relative-luminance math is ~30 lines. The
work is *adding the pair as the modelled unit* and *one more gate keyed on contrast*, not greenfield.

---

## 3. Deepening 2 — display authority coverage: every consumer through the one projector

**Concept.** "How an entity is presented" (label/icon/title) — 557 §2.A's display authority, owned by
`present()` projecting the ContributionManifest / catalog.

**Defect today (a re-introduced second authority).** The Advisories drawer does **not** use
`present()`. It resolves titles through a *separate* hand-maintained map,
`advisoryClassChrome(classId)` (`AdvisoryClassChrome.ts:17` — ~2 entries, default fallback
`{label:'Advisory'}`), so an unmapped class renders its **raw machine key** (audit:
`schema.reindex-required (worker.schema)` / tag `ReindexRequired`, `AdvisoryInboxDrawer.ts:604`).
Meanwhile Health "RECENT EVENTS" renders the **same events** through `present({kind:'resource',
key:event.i18nKey})` with friendly prose (`healthEventActivityRow.ts:124`). One event, two
presentations — 548's defect, in the surface 557's `present()` was built to prevent. (This is 504's
defect class **D6 internal-state-leaked**, recurred.)

**Correct end-state.** **Event/advisory-class is a declared entity kind** with a presentation
declaration (a `labelKey` + body + severity in the manifest/catalog), projected through the **one**
`present()` — the drawer and Health read the identical projection. The hand map
(`advisoryClassChrome`) survives only as the *declared fallback inside the projector*, never as a
parallel authority. Per §5.2, the `presentation-purity` gate's **coverage enumerates the event-class
catalog**, so a new event class cannot fall back to raw — the gate fails if a class has no declared
presentation. Tier: **collapse** (delete the second resolver path) + **tier-4 gate with enumerated
coverage**.

**Localized members of the same class (not separate problems).** The "??" keyboard-shortcut badge
(a binding rendered raw) and the `/` vs `\` path-style inconsistency are display/normalization
instances — the same authority, smaller blast radius; they are *projected* (a binding has one
canonical render; a path has one canonical separator), not hand-formatted per site.

---

## 4. Deepening 3 — projection totality + authority completeness

**Concept.** A surface is a **total function over its authority's record** — it renders every field
the record carries that the surface claims to show; and an authority **carries every field its
surfaces promise**. (557 §2.B made *status* total over the tri-state; this generalizes it to the
lifecycle/action record.)

**Defect today (two halves).** (i) **Lossy projection:** the canonical `UnifiedActionEntry` carries
`occurredAt` (`ActionLedgerClient.ts:29-71`) but the Activity row drops it —
`ActionLedgerView.ts:117` renders only `originator · label · source`, no time. (ii) **Incomplete
authority:** there is **no `duration`/timing field at all** on the record, yet the surface subtitle
promises "a structured audit … with outcomes and **timing**" (`ActivitySurface.ts:101`). The prose
promise is itself a **third authority** that has drifted from both the record and the projection.

**Correct end-state.** (i) The Activity row is a **total projection of `LifecycleEvent`/
`UnifiedActionEntry`** — it renders `occurredAt` (and outcome) by construction; a surface cannot
render a record while silently dropping a field it advertises. (ii) The **authority carries what its
surfaces promise**: `duration`/outcome become fields on 550's `LifecycleEvent` (the canonical record),
so "timing" is *projected*, never asserted in prose. The general rule: **a surface's descriptive
promise is a claim about the record and must be a projection of it** — copy that out-runs the record
is the same two-authority defect as a stale label. Tier: **collapse** (one row projection over the
full record) + extend the canonical record (550) so the promised field exists to project.

---

## 5. Already-solved and honestly-scoped (do not over-design)

Not every audit finding is a new structure. Recording what 557 already covers, and what is genuinely
localized, keeps this doc from inventing problems:

- **Reconnect flicker → already solved by 557 §2.B.** The alarming, recurring "Reconnecting…" pill
  (seen against a healthy backend) is the observed-state authority's job, and 557's `ConnectionPhase`
  (`connecting|connected|stale|disconnected`) with **stale-retains-last-known → degraded** is exactly
  the debounce/reassure structure. Residual is *threshold tuning* + verifying in the **packaged Tauri
  shell** (the audit ran in the dev Vite-proxy browser, which amplifies transient poll stalls). No new
  design.
- **Search-trace run-on → a localized rendering bug, NOT architectural.** The trace is *already* a
  correct structured projection of the SearchTrace (549/553): `searchTraceExplain.ts:130` iterates
  `trace.stages[]` and renders one `<span>` per stage. The defect is purely a **missing visual
  separator** between adjacent spans (a CSS/markup fix). It is correctly Advanced-mode-gated (Simple
  mode hides it — verified). This belongs to a scoped slice, not this design.
- **Token Editor as a default user surface → surface *audience* as a declared field.** The fix is not
  to delete it but to make a surface's **audience (developer vs end-user)** a declared field projected
  into the rail (557 already added a `uiMode` store + advanced-gating; missing token-editor/palette
  catalog entries are logged to observations). The rail is a projection of declared audience × uiMode —
  an instance of the display/contribution authority, not a bespoke decision.
- **Polish (button heights, localhost footer, status-bar wrap at extreme widths, empty chat band, the
  full-width red Stop, per-nav toast)** — localized correctness/visual items; no structural home.

---

## 6. Long-term prevention (mirror 557 §3; reach the strongest achievable tier)

| Deepening | Tier-1 collapse | Tier-2 unrepresentable (+ Lit ceiling) | Tier-4 gate — coverage projected from a catalog (§5.2) |
|---|---|---|---|
| **1 Theme pair** | one color-**role** primitive; on-color derived from surface; delete the independent `accent-on-tint` + the two selected-state languages | role reference at the authoring seam (no free `color`+`background`); **ceiling:** `var()` in `css\`\`` is unchecked → not pure tier-2 | **contrast-closure gate**: every (fg,bg) role-pair × theme × palette × high-contrast ≥ AA; coverage enumerates the **role catalog** so a new accent/palette can't escape |
| **2 Display coverage** | delete the second resolver (`advisoryClassChrome` → fallback inside `present()`) | branded `Presented<T>` at the `present()` seam (557) | extend `presentation-purity`: coverage enumerates the **event-class catalog** — an undeclared class fails the gate, never falls back to raw |
| **3 Projection totality** | one row projection total over `LifecycleEvent`; extend the canonical record with the promised `duration`/outcome | a surface that advertises a field must branch over it (totality) | a register (553-style) declaring each lifecycle surface's projected field set; gate that the projection covers the record's advertised fields |

**The meta-point, again:** the prevention is never "remember to map the new accent / event-class /
field." It is "the gate's coverage is a projection of the authority's catalog," so the new member is
covered the moment it is declared. Where the Lit template ceiling blocks tier-2, the strongest
*achievable* combination is **tier-1 collapse + a tier-4 gate whose coverage is the full catalog** —
the same honest ceiling 557 reached.

---

## 7. Process conclusion — independent UX audit as a closure gate

The strongest evidence in this document is its own existence: an **independent** audit, run one day
**after** 557 merged, found user-visible instances of all three of 557's authority classes (D1 theme,
D2 display, D6 totality) that 557's own validation — by the implementer — did not surface. 557's
postmortem already named the mechanism ("I saw and narrated the TaskList flood but dismissed it as
pre-existing / not-my-scope"). The lesson is structural, not personal: **an implementer's validation
shares the implementer's blind spots**, and presentation defects in particular hide in the gap between
"my change works" and "the whole screen is right."

Correct structure: **a structural presentation/UX-authority slice is not closed until an independent
reviewer (reviewer ≠ committer) performs a whole-screen adversarial UX audit**, by the same logic the
`independent-review` discipline gate (tier-register row 30, `gate:independent-review`) already applies
to substrate slices. The proposal is to **extend that gate's scope** to declare presentation-authority
slices as requiring an independent live UX re-audit (not just a static code review) before closure —
the audit is a merge gate, not a courtesy.

---

## Appendix — empirical grounding (the 2026-05-29 browser audit, condensed)

Method: drove the real frontend in Chrome against a real backend with the LLM active (Qwen3.5-9B) and
real, **degraded** data (584 docs; reindex-required → bm25-only). All 13 nav surfaces + persistent
chrome + the Advisories drawer + responsiveness (826px, 560px) + a theme-switch experiment. Findings
map onto the three deepenings (this is the proof-by-example):

**→ §2 Theme pair.** **D1** filled teal controls = white text @ **≈1.96:1**; "High Vis" theme *worsens*
to **≈1.35:1** (canvas-oracle rgb: white on bg 0,242,255); **light theme passes (5.56:1)** — the
headline. The app ships **three** accent-foreground strategies (white/on-tint on fill = failing;
accent-text on translucent = readable; hardcoded `#000` = readable-but-bypassing).

**→ §3 Display coverage.** **D2** Advisories title = raw `schema.reindex-required (worker.schema)` /
tag `ReindexRequired`, while Health RECENT EVENTS shows friendly prose for the same condition. **D8**
"Enter AI chat mode" shortcut badge = `??`. **D10** Brain Model-path `/` vs Server-exe `\`.

**→ §4 Totality.** **D6** Activity rows = `actor · action · "backend"` — no timestamp/duration, though
the subtitle promises "outcomes and timing"; `occurredAt` exists on the record but is dropped, and no
`duration` field exists at all.

**→ §5 Already-solved / scoped.** **D4** "Reconnecting…" flicker against a healthy backend (557 §2.B
solves by design). **D3** search-trace run-on — structured projection with a missing span separator
(localized). **D5** Token Editor as a default first-class nav surface (audience-as-declared-field).

**→ Polish (no structural home).** **D7** Library header button heights (42 vs 54px); **D9** persistent
bottom-right `http://localhost:5173`; **D11** Settings rail-config order ≠ render order; **D12** Browse
single-click selects but doesn't open; **D13** status-bar wraps at ≤560px; the large empty band above
chat answers; the full-width **red** Stop button; the per-navigation toast.

**Environment caveats.** Browser (Vite dev server) view, **not** the packaged Tauri shell — D4
(reconnect), D9 (localhost footer), and the "browser mode" banner may be dev/proxy artifacts and must
be re-verified in the shell. The dev backend is *permanently* degraded (no GPL reranker; chunk
embedding mid-flight), so a healthy-state path (e.g. a banner-hidden case) could not be induced.

**Not verified.** Agent task *execution*; Agent Sessions/Timeline/History tabs; Chat
Export/History/Document-Q&A toggle/command-mode; bookmark/Copy-URL outputs; triggering
Reindex/Fix/Restart (avoided long mutations on a shared stack); a true backend-error state; Light/System
appearance end-to-end; manual keyboard focus-order / focus-trap in the drawer (the shadow DOM defeated
the automated a11y-tree tool — native `<button>`s + aria-labels were confirmed to exist).

## Honest limits / residual risk

- **Contrast-closure tier ceiling.** A gate computing oklch→sRGB contrast is trivial and high-confidence;
  making white-on-accent *unrepresentable inside Lit `css\`\``* is not reachable (same stack limit 557
  documented). The realistic guarantee is collapse-to-role + gate, not compile-error.
- **High-Vis palette location.** The 1.39:1 figure is a *runtime measurement* (authoritative); a code
  agent located base + Nord + Sepia palette files but not a `high-vis` file — High Vis is defined
  elsewhere/generated. The root cause (on-color independent of fill, no palette override) is code-confirmed
  regardless.
- **Role-pair authoring ergonomics** at scale are unproven (557 flagged the analogous risk for
  `Presented<T>`/typed-token authoring).
- **Packaged-shell verification** of all of the above is outstanding; the audit was dev-server + Chrome only.

---

## § De-risk pass (2026-05-29) — measured reality + confidence

A confidence pass validated this doc's load-bearing claims against the running stack (run `8ebdcb7e`,
LLM active) and the source, before any implementation. Method: a **browser canvas oracle** (render a
token to a pixel, read back actual sRGB bytes — independent of the audit's hand-written converter), a
30-second connection-phase observation, a throwaway converter cross-check, and direct source reads
(agent-reported file:lines re-verified first-hand). Net: the three deepenings **hold**; two numbers and
two blast-radius claims are **corrected**; D4 is **upgraded** to HIGH "already solved."

- **Headline contrast — CONFIRMED, numbers corrected (HIGH).** Canvas oracle resolved the accents to
  `rgb(0,204,178)` default / `rgb(0,242,255)` High-Vis / `rgb(0,113,89)` light — byte-identical to the
  audit's measurements *and* to the audit's oklch→sRGB converter (so the converter, hence a gate's
  numeric core, is **validated**). White-95% on accent: **default 1.96:1, High-Vis 1.35:1** (corrects
  the audit's 2.04/1.39 — same verdict, far below AA 4.5). New nuance: **light theme PASSES at 5.56:1**
  (its accent darkens to `oklch(45%)`), so the defect is *light-fg-on-light-accent*, and the pairing
  invariant must hold **per theme** — exactly what a contrast-closure gate over (pair × theme) checks.

- **Root cause — CONFIRMED (HIGH).** `public/themes/core.high-vis.json` overrides `accent-tint`→
  `oklch(85% 0.20 200)` and `text-primary`→white but **does not override `accent-on-tint`**, which is
  `var(--text-primary)` (`tokens.css:115`). Fill and on-color are independent; the palette moves the
  fill and the foreground can't follow. Exactly Deepening 1.

- **Method correction (HIGH): `getComputedStyle` does NOT resolve `oklch()`.** It returns the literal
  `oklch(…)` string. Consequence for the prescription: a static contrast gate **must do its own
  oklch→sRGB conversion** (the validated converter / a lib) — it cannot read resolved rgb from the DOM.
  The "≈30-line converter" claim stands and is proven correct against the oracle.

- **Gate feasibility — HIGH (numeric core).** oklch→sRGB→WCAG ratio is proven. Coverage ("every (fg,bg)
  pair × theme") is trivial *iff* roles are declared as pairs (the gate iterates declarations); the
  residual unknown is migrating the consumer sites to a role primitive, not the math.

- **Deepening 1 blast radius — MODERATE / bounded (HIGH).** `--accent-on-tint` is consumed in **7**
  component CSS sites (ConfirmDialog, SettingsSurface, InspectorPane, IndexingOverlay, BrainSurface×2,
  LibrarySurface) + the chat affordance/resume buttons (white-on-accent) + a CommandPalette `#000`
  outlier ≈ **~10–12 foreground sites + 1 token redefinition**. Confirms "extend, not greenfield."

- **D4 reconnect — UPGRADED to HIGH "already solved by 557 §2.B."** Code: `aiStateStore.ts`
  `ConnectionPhase` = connecting|connected|stale|disconnected; `stale` (last poll > `STALE_THRESHOLD_MS
  = 15s`) shows "Reconnecting…" **while retaining last-known values** (never wiped); `statusLabel`
  returns "Reconnecting…" *only* in `stale`. Experiment: 30 s on the healthy stack stayed "Online" with
  **zero flicker**. So the earlier "Reconnecting…" was a genuine >15 s poll stall (the stopped stack +
  the dev Vite-proxy/545), surfaced honestly — not a per-poll bug. §5 stands; residual = packaged-shell
  verification + a visual-aggressiveness opinion (orange at 15 s while data is retained). No
  reclassification needed.

- **Deepening 2 (advisory) — CONFIRMED + sharpened (HIGH).** The raw title is built by
  `AdvisoryInboxDrawer.deriveTitle()` (`:684-696`): for `health.recoverable` it returns
  `${extras.conditionId} (${extras.subject})` → literally "schema.reindex-required (worker.schema)" —
  a 2-case hand switch on **raw** ids, bypassing `present()`. `present()` **already has a `condition`
  kind** (557 added it for the identical Health "Fixable now" leak). So the fix is precise and small:
  route `deriveTitle`'s conditionId/operationId through `present({kind:'condition'|'operation'})`; the
  coverage gate enumerates the advisory-class registry (the BE-side strict registry the chrome map
  mirrors). Smaller than "new event-class catalog."

- **Deepening 3 (totality) — CONFIRMED + split by cost (HIGH).** `BackendLedgerEntry` is backend-owned
  (`GET /api/action-ledger`) and **already carries `occurredAt` AND `outcome`** (`ActionLedgerClient.ts:34,38`);
  the FE `UnifiedActionEntry` carries `occurredAt` but the row (`ActionLedgerView.ts:117`) drops it, and
  the projection drops `outcome`; **`duration` exists nowhere**. So: rendering `occurredAt` (the "timing"
  the subtitle promises) + `outcome` = **FE-only, cheap** (fields already exist — a pure render/projection
  gap); only per-op **`duration` is a cross-process wire change** (record + `ActionLedgerController`).
  Correction: the "timing" promise is largely satisfiable FE-only — only true durations need the wire change.

- **D3 search-trace — CONFIRMED localized (HIGH).** `searchTraceExplain.ts:131-144` maps `trace.stages[]`
  to `<span class="search-explain-stage">` with **no separator between spans and no CSS gap/margin/::after**
  (no rule found) → adjacent spans concatenate ("executed"+"Dense"). Structured projection; missing
  separator only — not architectural.

- **Residual / lower confidence.** D5 surface-audience-as-declared-field: the `uiMode` store exists (557
  Q8), but an explicit "audience" field was not deeply verified (MEDIUM). Packaged-Tauri-shell behavior
  (D4, localhost footer, browser-mode banner) still unverified. Role-pair authoring *ergonomics* at scale
  unproven (same risk 557 flagged for `Presented<T>`).

**Net confidence.** Diagnosis: **HIGH** (first-hand + canvas oracle). Prescriptions: Deepening 1 — numeric
gate **HIGH**, role migration moderate/bounded; Deepening 2 — small + **HIGH**; Deepening 3 — core
(occurredAt+outcome) FE-only/cheap **HIGH**, `duration` = a wire change; D4 — already-solved **HIGH**. The
corrections (1.96/1.35; light-theme-passes; Deepening-3 cost split; getComputedStyle-can't-resolve-oklch)
are folded into §2 and the Appendix above.

---

# Consolidated satellites (folded 2026-06-09, post-400 hygiene pass)

> The accessible color-pair implementation record folded in.

## Accessible color-pair — realized (was 558-impl)

*(folded from `558-impl-accessible-color-pair.md`)*

### 558-impl — the accessible color-pair, realized

## What this is

main's 558 #1 specified — but left unbuilt ("DESIGN THEORY, feasibility disregarded") — that an accent
fill and the text on it are ONE concept whose foreground must meet a contrast floor ("model & gate it").
567 later built the *mechanism* (`contrast.ts` `deriveForeground` + the `accent-on-*` vocabulary + the
Token Editor baking it for **custom** themes) but never applied it to the **built-in** palettes. This
slice closes that gap. It reuses main's own engine; it does **not** port the parallel
`worktree-558-presentation-pairs` machinery (whose single-mode-lock thesis main superseded with
dual-mode `tokensByMode`).

## The live defect (confirmed on `main`, then fixed)

Built-in palettes override the accent **fills** (`--accent-*`) but not the **on-colors**
(`--accent-on-*`), so the on-color fell through to the appearance-flipping base values in `tokens.css`
(near-white under `[data-theme="light"]`). Measured (contrast.ts math):

- **Nord + Light:** white-on-bright-fill across **all six** accents — 1.56–4.09:1 (AA needs 4.5).
- **Sepia:** tint 3.11, warning 3.45 (light); deeper ambers ~4.0 (dark).
- **High-Vis:** 1.39–3.17:1 (light) on its four oklch fills.

Nothing caught it — main has the contrast *math* but no build-time *coverage* over the built-ins.

## The fix (the chosen approach: reuse `deriveForeground`)

Bake the `deriveForeground` result for each palette's fills into the palette file, so the on-color
**tracks the fill** and no longer inherits the appearance-flipping base. Because a built-in palette's
fill + baked on-color are both mode/HC-invariant, the pair is correct in **all four** appearance states
(light/dark × ±high-contrast).

- `core.nord.css`: 6 on-colors → all `#000000` (bright Nord accents; 5.1–13.5:1).
- `core.sepia-focus.css`: tint/warning `#000000`, command/chat/success/danger `#ffffff` (4.9–6.7:1).
- `core.high-vis.json`: 4 on-colors → `#000000` (6.6–15.1:1); `accent-on-*` are in `KNOWN_TOKEN_NAMES`
  so the tree still validates.

Consistent with how 567 bakes custom themes — built-ins now get the same treatment custom themes get.

## The gate (audit-driven-fixes-need-a-test)

`modules/ui-web/src/shell-v0/themes/builtinPaletteContrast.test.ts` — **reuses** the production contrast
authority (`contrast.ts` `contrastRatio`) + role catalog (`themeRoles.ts` `ROLE_CATALOG`), no second
copy. For every role a palette overrides a fill for, it asserts the declared on-color meets AA against
that fill. **RED before the fix (12 fail) → GREEN after (16 across all 3 palettes).** oklch fills (High-
Vis) use a bounded, cross-validated colour-space shim (contrast.ts delegates oklch to the browser, which
a unit test lacks); the WCAG math stays in contrast.ts. Full `ui-web` suite: **2497/2497**.

## Decisions with measured rationale

- **A (sole-writer gate) — DROPPED.** It existed to make the *branch's* single-mode-lock coercion sound
  (the branch's gate omitted cross-product states because coercion made them unreachable; A enforced the
  sole writer). **main has no coercion** — dual-mode + a free toggle, so a stray `data-theme` write just
  switches between two *legible* modes; and the Token Editor legitimately toggles `data-theme` transiently
  (`deriveRoleFgs`). A protects nothing here, and the palette-drift it conceptually guards is already
  caught by this slice's contrast test.
- **D (APCA advisory) — DROPPED.** It surfaced perceptually-marginal *tinted* on-colors; main's
  `deriveForeground` yields pure black/white (maximal contrast), so there are no marginal pairs to flag.
  The perceptual-oracle concern belongs to the future browser gate, not this slice.
- **Phase 2 (migrate ~176 raw-`var(--accent-*)`-as-text sites) — DEFERRED, condition measured FALSE.**
  The accents are mode-tuned to their surfaces, so raw-accent-as-text meets AA in the default theme
  (5.38–9.41:1 dark, 5.65–6.18:1 light on surface-1). It is preventive hygiene (the role-token
  discipline), not a live defect — defer until a browser gate measures any real sub-AA text per theme.

## Remaining / future hardening

- **Default (no-palette) theme's base accents** (oklch) — owned by main's 559 work; a **Playwright**
  cross-product gate (every theme × {light,dark} × {±HC}) reusing `getComputedStyle` + `contrast.ts` is
  the faithful browser-resolved coverage (tempdoc 558 §11.4) — future hardening, not this live-bug fix.
- **558 #2** (projector / advisory-event-class coverage) and **#3** (projection totality) — separate
  slices.

## Verification summary

`builtinPaletteContrast.test.ts` 16/16 (RED→GREEN); `high-vis.json` valid; full `ui-web` unit suite
2497/2497; no Java touched. Branch `worktree-558-color-pair`, 3 commits off `main`.

---

# §T. Takeover & implementation pass (2026-06-15)

> **What this section is.** A takeover of 558 by a fresh agent: read the whole doc, re-verify every
> claim against `main` first-hand (this doc is dated 2026-05-29 history — `tempdocs-are-dated-history`),
> research the open design questions, critique the original solutions, and **ship the genuinely-remaining
> tractable work.** Verified against `main` at `f6c11bf7f`. The headline: of the three deepenings, **D1 is
> closed** (superseded by shipped gates), **D2's acute defect is fixed** (and now regression-guarded), and
> **D3 was the one real remaining slice — now implemented here.** §7's process proposal is **moot** (its
> target gate was retired). Net new code this pass is small and FE-only; the larger value is the
> reconciliation + the critique that keeps the doc from re-prescribing already-shipped or disproportionate work.

## §T.1 Current state vs the three deepenings (re-verified first-hand)

| Deepening | 558's prescription | State on `main` (2026-06-15) | Verdict |
|---|---|---|---|
| **D1 color-pair** | role-pair primitive + a **contrast-closure gate** whose coverage enumerates the role catalog | **Closed.** The gate shipped as `scripts/ci/check-contrast-matrix.mjs` (**576 §6**) — it resolves the role tokens from `tokens.css` per theme and asserts every `accent-on-<role>`-on-`accent-<role>` **and** `text-<role>`-on-`surface-1` pairing clears WCAG AA, iterating `ROLE_CATALOG` (`themeRoles.ts`) — exactly §5.2 "coverage projects from the catalog". The built-in palettes are covered by the shipped `builtinPaletteContrast.test.ts` (the 558-impl satellite); the accent-as-text tail by `check-accent-as-text.mjs` (576 §6, a shrinking ratchet). All three are CI-wired (`.github/workflows/ci.yml`). | **No work remains.** |
| **D2 advisory display** | route `deriveTitle` through `present()`; **delete** the `advisoryClassChrome` second resolver; extend `presentation-purity` to enumerate the event-class catalog | **Acute defect fixed** (559 ADV-1): `AdvisoryInboxDrawer.deriveTitle` routes the `conditionId`/`operationId` through `present({kind:'condition'\|'operation'})` (`AdvisoryInboxDrawer.ts:660,666`); `present()` humanizes unmapped ids structurally, so no raw dotted key can reach a title. `advisoryClassChrome` **still exists** (2-entry map + default). The enumerated gate was **not** built (no FE advisory-class catalog; `check-presentation-purity.mjs` guards label-deriver imports, not class coverage). | **Acute bug closed; the rest is partly mis-diagnosed / disproportionate — see §T.2. Regression-guarded this pass.** |
| **D3 projection totality** | row = total projection of the record (render `occurredAt` + `outcome`); add `duration` to 550's record | **Was untouched.** `ActionLedgerView` rendered only `originator · label · source` (dropped `occurredAt`); the subtitle promised "outcomes and timing"; `duration` exists nowhere. | **Implemented this pass (§T.3).** |
| **§7 audit-as-merge-gate** | extend the `independent-review` gate to require a measured whole-screen UX re-audit before closing presentation slices | **Moot.** Both the `independent-review` gate **and** the later `ux-audit-closure` gate were **retired (563)**; they are honor-system now (`tier-register` rows 30–31). The §7 frontmatter ref to "tier-register row 30 (independent-review **discipline gate**)" is stale. | **The structural lesson stands; the gate proposal cannot — record, don't build.** |

## §T.2 Critical analysis & alternative designs

**D1 — the "honest ceiling" claim is now falsifiable (CSS `contrast-color()`).** 558 §2/§6 repeatedly concedes a hard ceiling: *"making white-on-accent unrepresentable inside Lit `css\`\`` is not reachable"*, so the strongest achievable tier is collapse-to-role + a tier-4 gate, not tier-2 unrepresentability. That was true in May 2026. It is **no longer categorically true**: the CSS `contrast-color()` function reached cross-engine support in 2026 (**Chromium/Edge 147+, Firefox 146+, Safari 26**; ~74% global by mid-2026) [1][2]. With `color: contrast-color(var(--accent-tint))`, the developer **does not author the foreground at all** — the engine derives the maximally-contrasting black/white — so "white text on a bright fill" becomes *unrepresentable at the authoring seam*, which is precisely the tier-2 rung 558 declared out of reach. Honest caveats that keep this a *complement, not a replacement*: (a) it returns only black/white — but that is exactly what `deriveForeground` already yields, so nothing is lost; (b) it does **not** replace the build-time gate (you still want `check-contrast-matrix` for coverage proof and for the static catalog); (c) the Tauri shell is WebView2 (Evergreen Chromium) — current for most users, but a fixed/old-runtime tail needs an `@supports` fallback to the baked on-color. **Proposed (deferred) hardening:** layer `contrast-color()` as progressive enhancement over the baked value — `color: var(--accent-on-tint); @supports (color: contrast-color(white)) { color: contrast-color(var(--accent-tint)); }`. This is optional: the baked values + the gate already deliver AA; `contrast-color()` would buy *authoring-seam unrepresentability* on modern engines. Recorded as an alternative, not scheduled — the live defect is already cured.

**D1 — APCA: 558 under-shot by dropping the advisory; 576 corrected it.** The 558-impl satellite dropped "Decision D (APCA advisory)" on the grounds that `deriveForeground` yields pure black/white (no perceptually-marginal tints to flag). But a perceptual signal still has value for the *text-on-surface* pairings (tinted role text, not just on-fill). The codebase's `check-contrast-matrix` already does the right thing: **WCAG 2 AA is the hard floor, APCA Lc is reported as an additive advisory** (live this pass: `dark/text-success`, `text-danger`, `text-link` clear AA at 7–9:1 but sit below the APCA Lc 60 advisory). This matches 2026 best practice precisely — APCA was **pulled from the WCAG 3 draft in mid-2023**, WCAG 3's contrast algorithm is "yet to be determined", and the standard is unlikely to finalize before ~2030, so the field's guidance is *conform to WCAG 2 and treat APCA as a perceptual signal* [3][4]. So the codebase is well-aligned; 558's instinct to drop APCA entirely was the weaker call, already overtaken.

**D2 — question the "second display authority" framing.** 558 calls `advisoryClassChrome` *"a re-introduced second authority … 548's defect, in the surface 557's `present()` was built to prevent"* and prescribes deleting it. First-hand reading complicates that: the audit's actual user-visible defect — `schema.reindex-required (worker.schema)` — was a **raw entity id concatenated into the title**, and *that* is fixed (the `conditionId` now flows through `present({kind:'condition'})`). What `advisoryClassChrome` still owns is the **advisory *class* chrome** (icon `🔧`/`⚡`/`ℹ`, tone class, a curated category label) over a **closed 2-entry registry** (`AdvisoryClassRegistry`/`AdvisoryClassId`, BE-side, slice 494). That is a *different concept* from "how an entity is presented" — it is the category's glyph/tone, with graceful fallback for deployment-skew. Folding it into `present()` as a new `advisory-class` kind would only humanize the dotted `classId` (yielding "Operation Completed" from `operation.completed`) — *worse* than the curated "Operation completed" + glyph it has now. So the "delete the second resolver" half is partly **mis-diagnosed**, and the "enumerated event-class gate" is **disproportionate** for a closed 2-class space that already has a BE strict registry + FE graceful fallback (YAGNI — `structural-defects-no-repeat` cuts the other way only when the defect is real; the title-leak defect is real and fixed, the coverage-gap defect is hypothetical at n=2). **Proportionate alternative (implemented §T.3):** regression tests that *pin the no-raw-leak property* (the exact failure that shipped past 557), rather than a new catalog + gate + `present()` kind. Residual micro-question recorded, not fixed: the `subject` (`worker.schema`) is still shown raw in parens — a deliberate disambiguator, low-severity; revisit only if a subject ever carries a user-hostile token.

**D3 — "timing" is satisfied by `occurredAt`; `outcome` was never actually dropped; `duration` is a real (deferred) cost.** 558 §4 frames timing as needing two things: render `occurredAt` (FE-only) *and* add a `duration` field (wire change). Two refinements from first-hand reading: (1) the subtitle promises **"timing"**, which `occurredAt` (a per-event timestamp) honestly satisfies; **`duration`** (elapsed wall-time) is a *stronger* claim the record genuinely cannot make yet, and is correctly **deferred** — building a cross-process field for a promise the weaker reading already meets is speculative (YAGNI until a surface needs elapsed-time specifically). (2) 558 says the projection "drops `outcome`"; in fact `outcome` **is** projected — it rides the `label` for operation rows (`ActionLedgerClient.ts:159`, `"Reindex — SUCCESS"`), and the equivalent for gate (`disposition`) and index (`state`) rows. So the only genuine lossy-projection gap was **`occurredAt`** — which is what this pass renders. (The 2026-05-29 de-risk pass already half-noted this; §T.3 closes it.)

**§7 — the lesson outlives its gate.** 558's strongest empirical point — *an independent audit one day after merge found user-visible drift the implementer's own validation missed* — is sound and is exactly why this takeover re-verified everything first-hand rather than trusting the doc. But the **mechanism it proposed (a merge gate) no longer exists**: `independent-review` and `ux-audit-closure` were both retired in 563 as honor-system guidance. So §7 is recorded as a *standing practice* (independent measured audit before closing presentation work — `tier-register` rows 30–31), not a buildable gate. No action.

## §T.3 Implementation (the Deepening-3 timing projection) + verification

**Change (FE-only, `modules/ui-web/src/shell-v0/components/ActionLedgerView.ts`).** The Activity row is now a **total projection** of the record's time: a semantic `<time class="when" datetime=…>` (machine-readable ISO for a11y/tooltip; compact local time as visible text) is rendered per row, projected from the `occurredAt` the record + the unified projection already carry. The collapsed indexing-burst summary also carries and renders the most-recent `occurredAt` of its group (so the one summarized row is not the one row with no time). `outcome` continues to ride the label (it was never dropped). Tokens only (`--text-secondary`, `--font-size-xs`); `tabular-nums` for column alignment. Consulted the presentation kernel (`docs/explanation/27`, ADR-0032): a timestamp is **bespoke leaf content** under 559 §8's AHA boundary — there is no time-presentation *authority* it must compose, so it is not a re-authored governed element.

**Tests (`audit-driven-fixes-need-test`).**
- `ActionLedgerView.test.ts` — new: a row projects `occurredAt` as a `<time datetime=ISO>` (asserted on the locale-independent `datetime`, not the rendered text) + `outcome` rides the label; extended: the burst summary carries the group's most-recent `occurredAt`.
- `AdvisoryInboxDrawer.test.ts` (D2 regression guard) — the unknown-class fallback title shows the generic chrome label and **not** the raw dotted `classId`; a `health.recoverable` condition (`schema.reindex-required`) humanizes to "Schema Reindex Required" and the raw dotted key does **not** appear. These pin the exact `present()`-humanization property 559 ADV-1 introduced.

**Verification.** `npm run typecheck` clean; full `ui-web` suite **314 files / 2993 tests green**; the two touched files 28/28. Presentation gates green: `check-presentation-purity`, `check-layout-purity`, `check-controls-a11y`, `check-a11y-closure`, `check-accent-as-text`, and `check-contrast-matrix` (all 32 role pairings clear the WCAG AA hard floor; APCA advisories reported as a signal, as above). No Java touched; no wire change. **Live smoke (recommended, not run here — the dev stack is shared):** open Activity, confirm each row shows a timestamp aligned with the event order and the subtitle's "timing" promise reads true; `jseval ui-shot` the Activity surface.

## §T.4 Disposition — what is closed, deferred, and not-done

- **Closed:** D1 (gates shipped, 576 §6 + 558-impl); D2 acute leak (559 ADV-1) + regression guard (this pass); D3 timing projection (this pass).
- **Deferred-by-design (decided, not remaining work):** per-op `duration` cross-process field (YAGNI — the weaker "timing" reading is met by `occurredAt`); the `contrast-color()` progressive-enhancement (optional authoring-seam hardening; the AA floor is already delivered); the structured-`outcome`-as-its-own-cell idea (outcome already projects via the label).
- **Not-done by deliberate judgment (see §T.2), not omission:** deleting `advisoryClassChrome` / a new `advisory-class` `present()` kind / an enumerated event-class gate — disproportionate for a closed 2-class registry with graceful fallback; the proportionate guard (no-raw-leak tests) is in place instead.
- **Moot:** §7's merge-gate proposal (target gates retired, 563).

**Sources (§T.2 research).**
[1] MDN — `contrast-color()` (Baseline 2026): <https://developer.mozilla.org/en-US/docs/Web/CSS/color_value/contrast-color>
[2] Can I use — `contrast-color()` (Chromium/Edge 147+, Firefox 146+, Safari 26): <https://caniuse.com/wf-contrast-color>
[3] Adrian Roselli — "WCAG3 Contrast as of April 2026": <https://adrianroselli.com/2026/04/wcag3-contrast-as-of-april-2026.html>
[4] W3C — APCA / WCAG 3 contrast status (algorithm "to be determined"): <https://github.com/w3c/wcag3/issues/29>

---

# §U. Forward research — what the timing/totality projection unlocks (2026-06-15)

> **Status of this section: research, not committed work.** Per the codebase convention
> (`tempdocs-are-dated-history`; cf. 569 §18, 575 Tier-4), forward research is recorded so the *next*
> deepening — if pursued — is informed, not so it is owed. Nothing here is scheduled. The point of the
> exercise was to think broadly about what a unified, attributed, time-aware, outcome-aware activity
> stream (550's "Outcome face", now with D3 timing) enables — across **polish · simplify · extend ·
> new-feature · architectural** — and to ground every idea in substrate that already exists so the
> cheap, high-fit moves are visible. Internet research grounding the UX claims is in §U.4.

## §U.1 The headline finding — 558's own thesis recurs at the row level

Implementing D3 surfaced a *third* presentation of the one ledger record, and that is the most useful
result of this research. The same `BackendLedgerEntry`/`UnifiedActionEntry` record is rendered by **two
divergent row authorities today**:

| | `ActionLedgerView` (the Activity surface — what I edited) | `RetrospectivePanel` → History tab (561 P-B1) |
|---|---|---|
| **Time** | absolute local time (`toLocaleTimeString`, this pass) | **relative** (`formatRelativeIso`, `utils/relativeTime.ts`) |
| **Outcome** | folded into the label string (`"Reindex — SUCCESS"`) | **toned** (`outcomeToTone`/`toneAccent`, `utils/statusTone.ts`) |
| **Label** | `present()` | `present()` (shared — the one part that already converged) |

This is **548 §1's defect — "two authorities for one concept, neither subordinate" — recurring one level
down, at the row.** The Activity row and the agent-History row are two presentations of *the same record*
that have drifted in their time and outcome treatment; D3 added a third time-format. So the *correct*
continuation of 558 is not a new feature — it is to **converge the row on the authorities that already
exist** (`present` for label, `statusTone` for outcome, and a time authority for `occurredAt`). The
research below is organized so that convergence is the spine, with extensions and new-feature ideas
hanging off it.

## §U.2 Ideas register (grounded in existing substrate; ranked by leverage × fit)

Legend — **Fit** = how well it extends a proven substrate (the codebase's "extend, don't invent" rule);
**Tier** = A (cheap + high-fit, do first) · B (strong, real cost) · C (forward / YAGNI pre-prod).

### Polish (on the row / timestamp)
| # | Idea | Builds on (exists today) | Tier |
|---|---|---|---|
| P1 | **Relative time + absolute-on-hover** in `ActionLedgerView` (e.g. "2m ago", ISO already in `<time datetime/title>`). Cloudscape/Facebook rule: relative for recent, absolute beyond yesterday. | the `<time>` substrate (this pass) + `formatRelativeIso` (already used by RetrospectivePanel) | **A** |
| P2 | **Outcome as a visual facet** (tint/glyph failed rows) instead of a label suffix. | `outcomeToTone`/`toneAccent` (`statusTone.ts`), already used in History; the D1 colour-roles | **A** |
| P3 | **Day / session separators** ("Today", "Yesterday", or per agent run). Timeline-pattern: stable grouping → muscle memory. | `correlationId` join key; `collapseBursts` ordering | **B** |

### Simplify
| # | Idea | Builds on | Tier |
|---|---|---|---|
| S1 | **One event-row projection** shared by `ActionLedgerView` + History tab (+ Logs). Resolves the §U.1 divergence by construction — the row becomes one projection of the record (label·outcome-tone·time·source·drill-down). | the divergence is the proof-by-example; 565 §12.6 run-renderers; 575 observed-happening register | **B** |
| S2 | **`presentTime()` as a named display authority** in the `present()`/`token()` family — the *time facet* of the presentation kernel, so every surface formats time one way. | `relativeTime.ts` (promote to an authority); `docs/explanation/27` kernel | **B** |
| S3 | **Structured `outcome` field** on `UnifiedActionEntry` (vs string concatenation) so the view composes it. Enables P2 cleanly. | `ActionLedgerClient.projectBackend` | **A** |

### Extend (on the stream)
| # | Idea | Builds on | Tier |
|---|---|---|---|
| E1 | **Filter / facet** by originator · kind · outcome · session. Audit-log best practice (filter by event/user/source/date). | the `FilterChip` pattern already in `AdvisoryInboxDrawer`; ledger fields | **B** |
| E2 | **Row drill-down in a right drawer** — provenance (transport/executor/initiator), diagnostics, grant, undo. Audit-log best practice: in-context drawer, field-level, arrow-key nav, no reload. | the right-drawer arbiter (565 §7.3); ledger carries `provenance`/`disposition`/`executionId` already unsurfaced | **B** |
| E3 | **Inline undo / reversible-action history** on rows with an `executionId`. Undo/time-travel pattern (canUndo, jumpTo, Ctrl+Z). Very fitting for an agent app. | 550 G6 `markUndoableOperation`/`getUndoableOperation` (substrate exists, UI doesn't) | **B** |
| E4 | **Duration rendering** (the deferred D3 wire field) — elapsed time per operation. | needs the 550 record + `ActionLedgerController` change | **C** |
| E5 | **Export / durable receipt** of the audit trail (self-describing: tz, filters). | ring buffer is ~200 non-durable → needs persistence; lower value pre-users | **C** |

### New UX features (enabled by the unified stream) — gap-checks, not asserted gaps
| # | Idea | Builds on / status | Tier |
|---|---|---|---|
| N1 | **Agent-run transparency, deepened.** The run retrospective already EXISTS (`RetrospectivePanel`, P-B1). The 2026 agent-UX consensus says check it for: *what's done / running / blocked / next at a glance*, **tool-use disclosure** (what each tool returned + inspect — "hiding tool calls produces unverifiable outputs"), **recovery routing** (failure-type → fix path), **confidence signaling** (binary > numeric). These are **gap-checks to run against the live run view**, not confirmed gaps. | `RetrospectivePanel`, `runStepPresentation.ts` (565 §12.6), the 561 P-D autonomy dial (for progressive-delegation) | **B** |
| N2 | **Catch-up / "while you were away"** digest of agent/system activity since last view. | advisory `unreadCount`; Slack/Air pattern | **C** |
| N3 | **Receipts** — a transient "what just happened" after an action. | 550 receipt concept; 559 Authority III single message model | **C** |

### Architectural generalization (the projection-totality discipline)
| # | Idea | Builds on | Tier |
|---|---|---|---|
| A1 | **Totality register + gate** — each surface declares its promised field-set; a gate fails if the projection drops an advertised field (D3's defect, mechanized). 558 §6 already sketched this; the prose-promise half is hard to mechanize, the declared-field-set half is not. | 553-style register + the 530 gate kernel; 575 | **C** |
| A2 | = S2 (`presentTime` authority) — the time facet completing the 557/559 projection family. | the presentation kernel | **B** |

## §U.3 Critical reading — what NOT to do, and why

- **Don't build N2/N3/E5 yet (YAGNI pre-prod).** Catch-up digests, receipts, and durable export earn
  their keep when there are *users who leave and return* and *records that must outlive a session*. With
  no users and a deliberate ~200-row ring buffer, they are speculative. Record, don't build.
- **Don't over-converge (AHA).** S1's one-row projection is right **only** where the rows share a reason
  to change. The **Activity** surface (user-facing audit) and **Logs** (raw diagnostic) have different
  audiences and altitudes (571) — converge Activity↔History (same record, same audience) first; fold Logs
  in only if it stops earning its separate shape. 575's lesson: unify the *stateful observed-happening*,
  not every list.
- **The flagship is not a new feature — it is a convergence + a gap-check.** The most valuable, lowest-risk
  work is Tier-A (P1/P2/S3: adopt `formatRelativeIso` + `statusTone` + a structured outcome in the row I
  just touched) → then S1 (one row) → then the N1 gap-check against the *already-built* run view. Proposing
  a brand-new "agent activity panel" would re-invent `RetrospectivePanel`; the honest move is to *deepen*
  it with the 2026 patterns it may lack.
- **APCA, again.** If P2/colour work proceeds, treat `apcaLc` (already in `contrast.ts`, surfaced by
  `check-contrast-matrix` as an advisory) as the perceptual signal over the WCAG-AA floor — not a second
  floor (§T.2; WCAG 3 algorithm is unresolved to ~2030).

## §U.4 Research notes (sources)

- **Time presentation** — mix relative + absolute; relative for recent (today/yesterday) then absolute;
  always pair with a descriptive past/future-tense label and the source-of-change; expose absolute via
  `<time datetime/title>` for hover/a11y; don't abbreviate units. Cloudscape Design System — Timestamps
  <https://cloudscape.design/patterns/general/timestamps/>; UX Movement
  <https://uxmovement.com/content/absolute-vs-relative-timestamps-when-to-use-which/>.
- **Timeline / activity-feed** — entry = description + actor + metadata + time; stable ordering/grouping
  for muscle memory; equal care for loading/empty/error/stale; explicit drill-down; semantic HTML first;
  window large lists; keyboard + non-colour-only + 200%-zoom + reduced-motion. UX Patterns for Developers —
  Timeline <https://uxpatterns.dev/patterns/data-display/timeline> & Activity Feed
  <https://uxpatterns.dev/patterns/social/activity-feed>.
- **AI-agent UX 2026** — four principles (Transparency / User-Control / Proactive-Status / Structured
  Error-Recovery) and patterns: Plan-and-Execute step list, **Activity-Panel separation** (done/running/
  blocked/next at a glance, separate from chat — "chat cadence ≠ workflow cadence"), **Tool-Use Disclosure**,
  Progressive Delegation (autonomy grows with approval history), Confidence Signaling (binary > numeric),
  Recovery Routing. Fuse Lab Creative <https://fuselabcreative.com/ui-design-for-ai-agents/>; agent
  observability timelines (Langfuse) <https://aimultiple.com/agentic-monitoring>.
- **Audit-log / provenance** — preserve actor↔action↔target↔outcome-in-time; in-context right-drawer with
  field-level detail + arrow-key nav; filter by event/user/source/date; self-describing export; tamper-
  evidence via hash-chaining (cf. JustSearch's `pathHash`). Logz.io <https://logz.io/blog/audit-trail/>;
  audit-trail overview <https://optro.ai/blog/what-is-an-audit-trail>.
- **Undo / time-travel** — Command/Memento; canUndo/canRedo + jumpTo(index); Ctrl+Z/Y; disable when
  unavailable. Redux undo-history <https://redux.js.org/usage/implementing-undo-history>.
- **Catch-up / "while you were away"** — unread filtering + periodic digest + notification dot/badge. Slack
  Catch-up <https://slack.com/help/articles/226410907-View-all-your-unread-messages>.

## §U.5 If you pursue any of it — the one-line recommendation

Do **Tier A first** (it lands directly on the row I just touched and removes the §U.1 divergence at near-zero
cost): adopt `formatRelativeIso` + `statusTone` + a structured `outcome` in `ActionLedgerView`, so the
Activity surface and the agent-History view present the same record identically. Then **S1** (extract the one
event-row projection) makes that convergence structural. Everything else (drill-down, filter, undo, the N1
agent-transparency deepening) is real but optional, and the C-tier items are deliberately deferred. None of
this is owed — it is the map for the next deepening, should one be opened (likely its own tempdoc, not 558).

---

# §V. Implementation pass on the §U register (2026-06-15) — convergence shipped + browser-validated

> Picked up the §U register as an implementation plan. Built the Tier-A/B **convergence** (the §U.1
> headline), validated it through the **real UI in the browser** (worktree Vite → the live backend +
> local model), and — per `explore-before-implementing` — *stopped* the speculative extensions when the
> live UI proved they already exist. Branch `worktree-558-presentation-depth`.

## §V.1 Shipped (unit + gate + **browser** validated)

- **§U P1/P2/S3 — honest row.** `ActionLedgerView` rows now project **relative time** (`formatRelativeIso`,
  absolute on hover via `<time datetime/title>`) and a **structured outcome** (glyph via `statusGlyph`/
  `glyphChar` + legible **text-grade** tone via a new `toneText` in `statusTone.ts` — never the accent
  fill, so `check-accent-as-text`/contrast stay clean). Taught the status authority the operation-outcome
  vocab `FAILURE` (was unhandled → a failure read as neutral). Outcome left the label string.
- **§U S1 — one shared row (the headline).** New `components/eventRow.ts` (`renderEventRow` +
  `eventRowStyles`) is the ONE ledger-row projection; the **Activity surface, agent History tab, and
  Timeline tab** all compose it over `UnifiedActionEntry`. Retired the forked `HistoryEntry`
  (`AgentSessionController.loadHistory` now reuses the exported `projectBackend`). This dissolves the
  §U.1 "two row authorities for one record" divergence by construction.
- **§U E1 — filter.** Who/outcome facet chips on the Activity surface, composing the `FilterChip` atom +
  the `toChips`/`toggleFilter`/`matchesFilter` pattern.
- **Two governance fixes the work exposed (root-cause, not symptom):** (a) `check-controls-a11y` didn't
  recognize `jf-filter-chip` as operable — it renders a native `<button aria-pressed>` (its own 559
  contract); AdvisoryInboxDrawer only "passed" via the gate's §12.10 interpolation blind spot. Added it
  to the gate's operable-atom set. (b) Registered `eventRow.ts` in `governance/run-renderers.v1.json`
  `toneSites` (it consumes the one status authority; not a second classifier).

**Verification.** `npm run typecheck` clean; full `ui-web` suite **314 files / 2995 tests green**;
presentation gates green (`presentation-purity`, `controls-a11y`, `contrast-matrix`, `accent-as-text`,
`layout-purity`, `a11y-closure`, `run-renderers`). **Browser (real UI, dev stack + local model, took
over per user OK):** drove an agent run to populate the ledger, then loaded the Activity surface from
this worktree's source. Confirmed live: relative timestamps ("just now" / "N minutes ago" / "4 days
ago"), **"✓ SUCCESS" in legible green** on agent operation rows, the burst summary ("Indexed 5 ·
justsearch-help") carrying a time, the shared row identical across rows, and the **filter facets**
(`who: user/system/agent`, `outcome: DONE/ISSUED/SUCCESS` with counts) **narrowing the list** when a
chip is activated (the `agent (2)` chip → exactly the two agent rows, chip showing its active state).

## §V.2 Stopped here, by evidence — the remaining §U extensions largely already exist

The live System host (which now hosts Activity as a member tab alongside System Health / Logs / **AUDIT**)
revealed that the product **already ships** most of the remaining §U ideas, so building them would
duplicate, not add:

- **§U E3 (inline undo) + N2 (catch-up)** — the run surface already shows a **"Since you last looked, the
  assistant: N operations"** banner with **"Undo all AI actions"**, "Save as macro", "Mark as seen". The
  undo + catch-up affordances exist (riding the same 550/effects substrate E3 would have used). A per-row
  undo would be a finer-grained *variant*, not a missing capability — defer unless a concrete need appears.
- **§U E2 (drill-down / provenance)** — the host already exposes an **AUDIT** tab; the per-row detail-drawer
  should be designed *against* that existing audit view, not as a parallel one. Defer pending that review.
- **§U N1 / Phase-4 (agent-run transparency)** — overlaps the agent-window tempdocs (565/577) and the
  existing run spine; out of 558's lineage, as already flagged. Not built.

Net: the genuinely-valuable, non-duplicative work (the convergence + honest presentation + filter) is
**shipped and browser-validated**; the rest is either already in the product or speculative for a
pre-production, no-users app, so it is **deferred by evidence** rather than built as duplicate UI
(`explore-before-implementing`). A future slice that wants per-row undo / a richer detail drawer should
start from the existing "Undo all AI actions" + AUDIT surfaces.
