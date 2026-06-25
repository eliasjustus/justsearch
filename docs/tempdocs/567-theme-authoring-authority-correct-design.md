---
title: "567 — Theme Authoring Authority: the correct producer-side design for the appearance system (the half of 557 Authority C that was never designed)"
type: tempdocs
status: open — §8 core SHIPPED; §9 (2026-06-15) ships ALL 4 remaining deferred items (import · rename · tinted foreground · surface-mode glass/solid). Only the 533/560 plugin-signing B-track stays out of scope (§7)
created: 2026-06-05
updated: 2026-06-15 (§9 — take-over: all 4 deferred items built + the 474/478 phantom-reference correction; surface-mode's blur-centralization prerequisite discovered AND resolved)
extends: tempdoc 557 §2.C (Theme/token authority — consumer side), 558 §2 (semantic colour roles), 559 §3 (the projection kernel), 474 (Theme-as-DesignTokenTree manifest — NB §9.1: "474" is a phantom reference; the real DesignTokenTree work is 478 §4.E and neither doc exists in `docs/`, both are retired-421-draft numbers)
cross-refs: tempdoc 560 §16–§26 (the token editor — the ad-hoc producer whose defects motivated this design); tempdoc 568 (the prior-art end-user-theming feasibility analysis that motivates this producer-side design)
---

# 567 — Theme Authoring Authority (producer side)

## §1 Thesis — the authority whose consumer half is designed and whose producer half is not

557 established that the ui-web presentation layer carries the same **two-authority defect** as the rest
of the codebase, and named **Theme/token** as one of its three sub-authorities (Authority C). It then
designed that authority's **consumer** side completely:

- one closed `TokenName` vocabulary; component styles reference tokens only through a **typed accessor**,
  so a non-existent ("ghost") token is a **compile error** (tier-2);
- every theme variant is **total** — a theme is `Record<TokenName, Value>`, so an incomplete variant
  fails a closure gate;
- there is **one** theme-application writer (`applyTheme`);
- the `var(--x, #fallback)` escape hatch — the mechanism that hid ghost tokens behind hardcoded
  literals — is banned by a tier-4 lint (557 §2.C / §3 ladder, line 176).

558 §2 deepened it: a colour is a **semantic role** (`accent-fill`, `danger-fill`, `selected`, …) whose
foreground is *derived from* its background to a contrast floor — components reference the **role**, never
a free `(color, background)` pair, so "white text on a bright fill" is **unrepresentable**. 559 §3 named
the engine: the UI as a **projection of a typed declaration** (declaration → projection → gate). 474
designed the artifact: a theme is a **`DesignTokenTree`** (DTCG-aligned, host-compiled, **never
`cssText`**), so a malicious theme cannot emit raw CSS by construction.

**All four design how a theme is *consumed*. None design how a theme is *produced*.** There is no
designed answer to: *what is editable, who may author it, what a "theme" is as a user artifact, how it is
created, named, persisted, applied, and shared.* That half was left to be filled ad-hoc — and it was, by
the token editor (560 §16–§26), which filled it **wrong**: it exposed all ~70 tokens — primitives,
**derived** semantics, and component dimensions alike — as independently hand-editable raw values, applied
through a **transient overlay** that is never saved as a theme. That is precisely the 557 defect, on the
authoring side: **a second, flattening authority over a tiered single-authority system.**

Every symptom we hit is a corollary of that one structural mistake:

| Symptom (560 §16–§26) | It is a corollary of… |
|---|---|
| "Token soup" — 70 flat raw knobs | authoring the **derived** tier instead of the seed/role tier |
| Editing `--p-glass` (a channel triplet) corrupts `rgb(var(--p-glass))` | a derived/primitive-internal value being **authorable at all** |
| Previews but never saves a theme | no **Theme-as-artifact**; the producer emits a transient overlay, not a declaration |
| Core surface vs plugin drift; two "Token Editors" | two **producers**, because there is no single producer authority they must both be |
| Plainer, role-blind UI | the editor is a token-poker, not a projection of a role model |

This doc designs the missing half: **one Theme/token *authoring* authority**, the structural mirror of
557's consumer authority, such that the flattening defect becomes **unrepresentable** rather than merely
discouraged.

## §2 Move 1 — the authored surface is SEEDS + ROLES; everything else is an unauthored projection (the keystone)

The theme *system* is already tiered and already derives: primitives (`p-*` channels, `h-*` hue angles)
→ semantics (`--accent-tint: oklch(75% 0.15 var(--h-teal))`, `--glass-surface: rgba(var(--p-glass), …)`)
→ component dimensions, re-derived per light/dark through `@layer core-theme/user-theme/user-override`.
The derivation tier exists. The producer simply ignored it and edited the *outputs*.

**Correct design: the authoring model is the *authored tier only*, and the authored tier is two things —**

1. **Seeds** — the small set of source design decisions the derivation consumes: hue anchors, primitive
   channels, the scalar scales (radius, density, type scale, motion). On the order of a dozen, not 70.
2. **Roles** (558 §2) — semantic colour roles (`accent-fill`, `surface`, `danger-fill`, `selected`, …)
   whose foreground / on-colour / weak / strong variants are **derived** from the role's background to a
   contrast floor.

Everything below — the ~70 `KNOWN_TOKEN_NAMES`, the channel triplets, the component dimensions — is a
**projection** of the seeds+roles through the existing derivation. It is *output*, not *input*.

The keystone is not "expose seeds in the UI" (a UI choice). It is **structural unrepresentability**: the
producer's typed model has **no field** for a derived token, a raw channel, or a free `(fg, bg)` pair.
You cannot hand-author `--accent-tint` because the model has no slot for it; you author the hue/role it
derives from. This is 557 clause (b) — *the subordinate form must not be expressible* — applied to
authoring. Token-soup, channel-triplet corruption, and inaccessible colour pairs do not need a gate or a
lint or editor discipline to prevent: they are **not expressible** in the model.

This single move dissolves the bulk of the 560 §24 hardening (value sanitization, the channel-triplet
widget heuristic, the "don't hand-edit derived tokens" caution) — not by fixing them, but by removing the
ability to author the values that needed fixing.

## §3 Move 2 — a Theme is a first-class, persisted DECLARATION (the unit of create / save / apply / share)

The producer emits a **Theme**: a typed declaration of seed + role values (with per-mode variants),
**DTCG-aligned per 474, never `cssText`**. It is not a transient overlay and not an ad-hoc JSON blob — it
is a first-class artifact with a lifecycle:

> **create → preview (ephemeral) → save-as-named → apply → manage (rename / duplicate / delete) → import / export (DTCG)**

A Theme flows through the *existing* single pipe: validated → compiled by the **one** host derivation pass
→ applied by the **one** `applyTheme` writer → stored in the **same** `ThemeCatalog` as the built-ins
(Nord, Sepia, High-Vis), persisted alongside them. A user-created theme is not a different *kind* of thing
from a built-in theme; it is the same declaration with a different origin.

Two consequences fall out structurally:

- **Preview ≠ create drift collapses.** Today the editor's preview (a transient `@layer` overlay) and any
  "saved theme" are two different mechanisms; the editor produces the former and can't produce the latter.
  When the producer's *only* output is a Theme declaration, "preview" is just *that declaration applied
  un-persisted*, and "save" is *persisting the same declaration*. One authority, one projection — the
  long-missing "save as theme" (521 §5 #5, designed and never built) is no longer a feature to add but
  the natural terminal of the model.
- **The producer is single.** There cannot be a "core token editor" and a "plugin token editor" producing
  divergent artifacts, because there is one Theme authority and one catalog; every editor is a *producer
  into* it. The 560 §25/§26 "one token editor" decision becomes a structural fact rather than a cleanup.

## §4 Move 3 — the editor is a thin, tier-aware CONSUMER+PRODUCER; not special; trust-split read/write

The editor is not the authority and not privileged. It is a **consumer** of the seed/role model + the
derived preview, and a **producer** of Theme declarations. Because its only output is a declaration that
flows through the host's single pipe, it composes the authority and can never fork it — which is exactly
why it can be a *plugin* (the 560 §22 dogfood) without weakening anything: a producer of declarations is
safe to attenuate.

This yields the natural trust split — the **read/write calibration** 533 wanted and the read-only token
editor never exercised:

- **Read** the seed/role model + the derived preview, and apply an **ephemeral** preview — universal
  (every tier; it mutates nothing persistent).
- **Create / save / apply / delete** a persisted Theme — **TRUSTED** writes (a theme written to the
  catalog is a durable, app-wide artifact).

It also yields the two-surface shape, both over the **same** seed/role model — never two models:

- **Simple** — a few high-value roles (accent, surface, mode, density, type) in **Settings → Appearance**,
  next to the existing palette picker. This is the end-user appearance control.
- **Advanced** — the full seed/role tree with **read-only** inspection of the derived outputs (so a power
  user can see what their seeds produce without being able to edit the outputs). This is the power surface.

Light/dark and glass/solid are properties of the **declaration**, not of the editor: a Theme declares
per-mode seed/role values, mediated by 474's unified `--surface-mode` abstraction, so a theme is not
silently locked to one mode-model the way today's dark=opacity / light=solid split locks it.

## §5 The prevention — completing 557 Authority C's ladder on the producer side

557 §3 gives the Theme/token authority a four-tier prevention ladder; it designed the rungs for the
*consumer*. This design supplies the *producer* rungs, so the authority is closed end-to-end:

| Rung | 557's consumer side (designed) | This design's producer side |
|---|---|---|
| **1 — collapse** | one `applyTheme` writer | one **Theme declaration** as the only producer output; one `ThemeCatalog` (built-in ∪ custom); one derivation pass |
| **2 — unrepresentable** | typed `token()` accessor — ghost ref = compile error | the producer model exposes **only** seeds + roles as typed fields; derived tokens, raw channels, raw CSS, and free `(fg,bg)` pairs **have no slot** — authoring them is a type error / not expressible |
| **3 — generate** | `TokenName` union generated from the token source | the **derived tree + the role/seed schema** are generated from the same `TokenName`/role source 557 already owns — the editor's fields are a projection of it |
| **4 — gate** | `theme-token-closure`: every variant covers every `TokenName`; ban `var(--x,#fallback)` | a **producer-side closure**: every Theme declaration covers every role (totality of the *authored* surface); no derived-token authoring; no `cssText` (474) |

**Why the defect class cannot recur.** The class is: *a presentation feature flattens or forks the single
authority, exposes raw internals, and loses the produce→consume contract.* Under this design:

- authoring is **seed/role-only**, so flattening the tiered system back into a bag is **unrepresentable**;
- the Theme is the **single persisted declaration**, so there is no overlay-vs-saved split and no
  core-vs-plugin fork to drift;
- **derivation + the contrast floor are the single producer** of derived tokens and colour pairs, so
  channel-triplet corruption and inaccessible combinations are **unrepresentable**;
- the editor hangs on the **same projection engine** (559) the rest of the UI does, so there is never a
  hand-authored second copy.

The prevention is the same shape 557 proved for the consumer side: *single authority + an unrepresentable
subordinate form.* This doc just turns it around to face the author.

## §6 What already exists vs what is new (extend, do not rewrite)

This is deliberately an *extension* of a usable existing design, not a greenfield rewrite. Honest ledger:

**Already exists and is correct — extend it:**

- the tiered token model (primitives → derived semantics → component) and the **active derivation**
  (`oklch()` / `color-mix()` / `rgba(var(--p-*))`) — `themes/designTokenTree.ts`, `styles/tokens.css`;
- the **single apply authority** + `@layer core-theme/user-theme/user-override` cascade —
  `state/themeState.ts` (`applyAppearance`/`applyTheme`);
- the **`ThemeCatalog`** with built-in themes + `activeThemeId` persistence — `themes/themesCatalog.ts`,
  `UserStateDocument`;
- 474's **Theme-as-`DesignTokenTree`** (DTCG, host-compiled, no `cssText`) and the `--surface-mode`
  abstraction;
- 558's **semantic colour roles** and the contrast-floor derivation principle;
- 559's **projection kernel** (declaration → projection → gate).

**New / missing structure — what this design adds:**

- the **producer authority** itself: a typed seed/role *authoring* model that is the *only* input surface,
  with derived/component tokens structurally non-authorable;
- **roles as the authored colour unit** (fg derived from bg + contrast), not token atoms — 558's role
  concept promoted from a consumer rule to the authoring vocabulary;
- **Theme as a first-class, persisted, user-creatable artifact** with the full create→save→apply→manage→
  import/export lifecycle, and **custom-theme persistence** in the same catalog as built-ins (the
  genuinely-absent piece);
- the **editor as a thin, tier-aware producer** with the read/write trust split and the simple/advanced
  surfaces over one model.

## §7 Non-goals

Per the design-theory genre (557/561/564): **feasibility, phasing, and implementation are out of scope** —
this is the correct *structure*, not a build plan. Also explicitly out of scope:

- **component replacement** and **layout customization** (changing *what* renders, not its appearance) —
  separate product decisions (474 L3 / the chrome-replacement workstream);
- the **consumer-side** ghost-token / `theme-token-closure` / `presentation-purity` work — owned by 557,
  not re-litigated here;
- **signature/trust verification** for themes contributed by untrusted *plugins* — owned by the 533/560
  signing workstream; this design only requires that the *write* capability be TRUSTED-gated, not how
  trust is established.

The single claim of this doc: the appearance system has a designed consumer authority and an undesigned
producer authority, and the correct producer authority is **one theme-authoring authority whose only
authored surface is seeds + roles and whose only output is a first-class persisted Theme declaration** —
which completes 557 Authority C and forecloses the flattening-second-authority defect on the side it was
never designed for.

## §8 Implementation — Phases 1–4b (2026-06-05)

This design shipped. Built directly on `main` in six commits, each browser-verified on the MSW dev stack
in **both** light and dark modes (the producer half — A1 — landed earlier via the
`worktree-plugin-declaration` merge `f09b6bf7a`).

**What shipped, by move:**

- **Move 1 (seeds + roles, the keystone)** — the authorable surface is now *structurally* seeds ∪ roles,
  enforced at the `host.theme.saveTheme` capability boundary (not just the editor UI): a saved theme may
  carry only the seeds (`SEED_TOKEN_NAMES` = `p-*`/`h-*`) and the role foregrounds (`ROLE_FG_TOKEN_NAMES`
  = `accent-on-*`); every other (derived) token is rejected. `themes/themeRoles.ts` is the role catalog.
- **Move 2 (Theme as a persisted declaration)** — `saveTheme`/`deleteTheme`/`listThemes`/`exportTheme`
  persist a `DesignTokenTree` to `UserStateDocument.customThemes`; `themesCatalog` carries a custom layer;
  `loadAndApplyTheme` compiles the tree (no file fetch). Custom themes are listed + apply/delete in
  **both** the editor and Settings → Appearance (host-owned management, independent of the plugin's
  lifecycle).
- **Move 3 (thin tier-aware editor)** — the bundled Token Editor authors seeds (hue sliders + primitive
  channels), shows the auto-derived role foregrounds + live WCAG ratios, and writes only through the
  trust-split `host.theme` capability (reads every tier; writes TRUSTED/CORE only).

**Beyond the original design — two implementation theses that proved necessary:**

- **Per-mode authoring (A3).** Primitive channels (`p-*`) and role foregrounds (`accent-on-*`) are
  *mode-variant* (a theme's surface tint / accent ink differ between light and dark), so a single value is
  wrong. `DesignTokenTree` gained `tokensByMode {light,dark}`; the compiler emits `:root` (dark base) +
  `[data-theme="light"]`, mirroring `tokens.css`. The editor buckets edits by the live `data-theme` and
  bakes both modes at save (a synchronous attribute toggle ⇒ no paint ⇒ no flicker). Mode-invariant hues
  stay shared.
- **Roles auto-derive to a WCAG floor (A2).** A pure contrast utility (`themes/contrast.ts`: `parseColor`
  / `relativeLuminance` / `contrastRatio` / `deriveForeground`) derives a readable foreground (black/white)
  over each accent; the editor displays the achieved ratio + an AA/AAA badge and bakes the result.
  Defaults: `#000` (dark) / `#fff` (light) — equal to the derivation and consistent with the codebase's
  existing near-black accent inks. The ~22 text-bearing accent-background consumer sites were migrated to
  consume `accent-on-*` (fixing several real `white`-on-bright-accent ≈ 2:1 bugs).

**Correction to the planning record.** A mid-flight worry held that 557's ghost-token consumer debt was
the load-bearing blocker for custom themes ("they'd only half-apply"). A read-only de-risk pass refuted it:
557's named ghosts were already defined, the main shell themes fully, and the only residual core ghost was
`--font-body` (a one-line fix, now `--font-native`). The consumer migration here is the *role-foreground*
adoption, **not** ghost-token closure (still 557's lane per §7).

**Commits (on `main`):** `ae3db565d` (seeds-only #4 + export #2) · `9d3da4ccc` (Settings management #3) ·
`16b1dc6b0` (per-mode A3) · `2f16b6c20` (contrast util) · `9e78b7279` (role producer + `--font-body`) ·
`0477f1fd3` (consumer migration).

**Deferred (recorded, not built):** tinted foreground derivation (an oklch-lightness search, vs the pure
black/white shipped) · theme **import** (the counterpart to export) · custom-theme **rename** ·
`--surface-mode` (glass/solid) · the 533/560 plugin signing / file-write B-track. All consistent with §7.

> **Update (§9, 2026-06-15):** all four of those deferred items are now **built** — import, rename, tinted
> foreground, and `--surface-mode` glass/solid (a prerequisite was discovered AND resolved — §9.4). The
> 533/560 signing B-track stays out of scope per §7. See §9.

## §9 Take-over — the four deferred items, built where sound, designed where blocked (2026-06-15)

This section picks up §8's deferred list. Method: source-verbatim codebase audit of the shipped §8 system,
targeted web research on accessible tinted-foreground derivation, and **live in-page measurement** against a
running worktree dev stack (the `--accent-*` values were resolved by browser paint and the derivation output
checked against them — the "interrogate results" discipline). **All four remaining items shipped** (import,
rename, tinted foreground, surface-mode); surface-mode's prerequisite (§9.4) was discovered *and resolved*.

### §9.1 Correction — "474" and "478" are phantom references (verify-don't-guess, applied to the doc itself)

The doc's frontmatter, §1, §4, and §6 lean on **474** ("474's unified `--surface-mode` abstraction", "474
designed the artifact: a theme is a `DesignTokenTree`", "474 L3"). The shipped code instead cites **478 §4.E**
(`designTokenTree.ts`, `themesCatalog.ts`, `themeManifest.ts`). **Neither 474 nor 478 exists** as a tempdoc or
slice anywhere under `docs/` — both are numbers from the **retired 421 FE-rewrite draft** (CLAUDE.md: "the
retired 421 FE-rewrite draft"). The substance both refer to (Theme-as-`DesignTokenTree`, DTCG-aligned,
host-compiled, no `cssText`) **is real and shipped** — the *number* is just dangling. The one claim that turned
out to have **no referent at all in code** is the `--surface-mode` abstraction (§9.4): it exists nowhere — not
in 474 (which doesn't exist), not in `tokens.css`, not as a token. Treat every "474" in this doc as "the shipped
`DesignTokenTree` substrate (478 §4.E lineage)"; treat "474's `--surface-mode`" as **unbuilt aspiration**.

### §9.2 Import + Rename — host-owned producer writes through the ONE gate (BUILT)

Both are management operations on a persisted Theme, so they live where §8 #3 put delete: **host-owned, in
Settings → Appearance, independent of the editor plugin** — not in the editor (whose lifecycle is a plugin's).

- **The keystone (import is not a backdoor).** §8's `saveTheme` enforced the seeds+roles authorable surface at
  the capability boundary. §9 **extracts that into one `persistTree(tree, who)` gate** (validate → seeds+roles
  authorable check → persist replace-by-id → catalog sync) that *all three* writes flow through — `saveTheme`,
  `importTheme`, `renameTheme`. So an **imported** JSON theme (possibly hand-edited / shared by another user) is
  held to the **identical** authorable surface as an editor save: it **cannot** carry a derived/non-authorable
  token. Import being a second producer would have been exactly the §1 flattening defect; routing it through the
  one gate forecloses it. `importTheme` additionally rejects malformed JSON, an invalid tree, and a built-in id
  collision (a custom entry shadowing a built-in would double-list).
- **Rename is `displayName`-only, by construction.** The `id` is the stable key (`activeThemeId` references it;
  `customThemes` is keyed by it). `renameTheme(id, label)` changes **only** `displayName` and re-persists through
  the gate — so there is **no dangling-active-theme cascade**: rename can never orphan a selection. (Tested:
  rename a theme that is currently applied → the active selection survives.)
- **Surfaces.** Capability: `importTheme`/`renameTheme` added to `PluginThemeState` + `createThemeApi`, TRUSTED+
  only (UNTRUSTED structurally omits them, same tier attenuation as the other writes). UI: a paste-JSON import
  box + an inline rename input in `SettingsSurface.renderThemes()` (two new Lucide glyphs — `pencil`, `upload`).
  Tests: `theme.test.ts` (round-trip export→import, derived-token rejection, malformed JSON, built-in-id refusal;
  rename id-stability + blank/unknown rejection; UNTRUSTED omission).
- **Not a second producer (Theme ⊂ Presentation).** A live-stack pass surfaced 569's **Presentation** surfaces
  (the "Skins" gallery `PresentationGallerySurface` with its own "Import a skin" + "Export active", and the
  "Editor"), which *also* import/export appearance JSON. This is **not** the §1 two-producers defect: a 569
  **Presentation** (`PresentationDeclaration`, `presentationCatalog`) is the **full skin** that *wraps* a 567
  **Theme** (`DesignTokenTree`, `host.theme`/`customThemes`) — they are two **distinct substrates**, each with
  **one** producer over **its own** artifact (Presentation ⊃ Theme). `SettingsSurface.renderThemes()` remains the
  live, unconditional home for the Theme substrate (569 §19 Phase 6 moved only *presentation* authoring out of
  Settings; the Theme picker stayed), so theme import/rename belongs exactly there. A standalone Theme is the
  "just my colours" artifact; a Presentation is "my whole skin" — both legitimately import/export.

### §9.3 Tinted foreground — an sRGB-blend derivation, wired at a measured 6:1 floor (BUILT)

The §8 deferred note framed this as "an oklch-lightness search". **Rejected that mechanism** and built a better
one. `contrast.ts` states its own contract — *stay in sRGB; never re-implement the oklch→sRGB transform; let the
browser resolve oklch via paint*. An oklch-lightness search would violate that. Instead, `deriveTintedForeground`
is a **straight sRGB blend FROM the maximal-contrast extreme (the black/white `deriveForeground` already picks)
TOWARD the background**. Contrast is **monotonic** along that segment (each channel moves linearly between the
extreme and the bg; WCAG relative luminance is monotonic per channel), so a binary search finds the most-tinted
foreground still clearing a target floor. The result carries the fill's **own hue** (a dark-teal ink on a teal
fill) — the "of the same hue family" ink the §1 "plainer, role-blind UI" symptom asked for — **correct-by-
construction**: it is mathematically impossible to return a foreground below the floor.

**Self-regulating safety + the measured floor.** Tinting is applied only where the plain extreme has headroom
above the (higher) tint floor; otherwise the maximal-contrast pole is returned unchanged — the floor is never
spent on the tint. The tint floor was set by **measurement, not guess**. Live-resolved default-theme accents and
their black/white headroom: chat 10.8, link 8.6, warning 9.2, success 8.4, command 7.5, tint 6.9, danger 5.9,
highlight 6.2(white). At an **AAA (7:1)** tint floor, the effect was near-invisible (tint 6.9 and danger 5.9 lack
the headroom; command tints to a near-black `#0c0b13`). At **6:1** — deliberately between AA 4.5 and AAA 7 — 7 of
8 roles take a visible, tasteful hue-bearing ink, every one a comfortable **33% above the AA standard**, and
`danger` (no 6:1 headroom) keeps the maximal-contrast pole (appropriate for danger). **6:1 was the user's call**
(a global appearance + accessibility-posture decision surfaced explicitly with the measured options).

- **Wiring.** `roleForegrounds.deriveOnColorDecls` (the runtime authority that injects `--accent-on-*` into the
  topmost `user-override` layer) now derives the tinted ink at the `ROLE_TINT_FLOOR = 6` constant. The **static**
  baked `--accent-on-*` in `tokens.css`/palettes/saved custom themes **stays pure black/white** — the safe
  fallback if JS never runs — so the `builtin-palette-contrast` and `contrast-matrix` gates (which read the
  static values) are unaffected and stay green.
- **Tests.** `contrast.test.ts` (tinted result clears the tint floor; carries the bg hue; never drops below the
  requested floor across a 4-accent sweep; falls back to the pole on a mid-tone with no headroom).
  `roleForegrounds.test.ts` updated from the exact-`#000000` assertion to its intent — a readable, hue-bearing,
  **dark** on-colour clearing the floor (a deliberate behavior change, not a weakened assertion).

### §9.4 `--surface-mode` (glass/solid) — a discovered prerequisite, resolved, then BUILT

This one had a **real architectural prerequisite** the doc never named (it assumed "474's `--surface-mode`
abstraction" already existed — §9.1: it does not). The prerequisite was found, resolved, and the mode built.
Findings from the codebase that defined the prerequisite:

1. **There is no `--surface-mode` anything** — no token, no attribute, no abstraction. Surfaces are glass by
   construction: `--glass-surface: rgba(var(--p-glass), 0.03)` (dark) / `0.08` (light), translucent over a blur.
2. **`backdrop-filter` blur is NOT centralized** — 13 sites set it with **mixed literals** (`blur(8px)`,
   `blur(4px)`, `blur(1px)`) and **two different token names** (`--glass-blur`, `--glass-blur-elevated`). The
   `--backdrop-filter-blur` token a `tokens.css` comment claims components consume **does not exist**.
3. **Even `.high-contrast` can't turn the blur off.** Its block overrides surface *colours* to opaque, but the
   `tokens.css` comment (~L540) records that the universal-selector blur-kill was **deleted as dead code**
   (a universal selector can't pierce Lit shadow DOM, where the `backdrop-filter`s live).

**Consequence:** overriding the surface colours to opaque while the blur stays on yields **opaque-but-still-
blurred** surfaces — visibly broken. So the blur had to be single-sourced *first*. The real scope was smaller
than the raw grep implied: only **3** component literals (`DragOverlay` 8px, `IndexingOverlay` 4px,
`ProvenanceBadge` 8px) — the two `.glass-panel` utility classes already read `--glass-blur`.

**The keystone insight: CSS custom properties inherit *through* shadow DOM.** The old universal-selector
blur-kill failed precisely because a selector can't pierce a Lit shadow boundary — but an *inherited custom
property* does. So one `--glass-blur-scale` on `:root` reaches every component's `backdrop-filter`. That turned
the "impossible" prerequisite into a one-token change.

**As built (BUILT + verified, §9.5):**

- **P0 — blur single-sourced.** New `--glass-blur-scale: 1` token (`tokens.css`). Every blur site now reads
  `blur(calc(<base> * var(--glass-blur-scale)))` — the 3 component literals + the 2 `.glass-panel` utilities.
  One inherited multiplier behind every backdrop-filter, across shadow DOM.
- **P1 — the mode.** A `data-surface-mode="solid"` attribute (default unset = glass) set by the **one** appearance
  writer `applyAppearance` (mirroring its existing `data-theme` attribute + `high-contrast` class). An **unlayered**
  `[data-surface-mode="solid"]` block in `tokens.css` (same placement discipline as high-contrast — unlayered beats
  `@layer core-theme`) sets `--glass-blur-scale: 0` and makes the glass surfaces opaque by compositing the
  per-theme `--p-glass` tint over the opaque `--surface-1` via `color-mix` (so panels stay subtly differentiated
  without translucency) + `--layer-base: var(--layer-base-solid)`. **Theme-aware by construction** — it references
  only tokens both themes redefine, so one block is correct in dark, light, and any palette (verified live in both).
- **P2 — the preference.** `surfaceMode` is a **global rendering preference** (the sibling of `highContrast`,
  composing with any theme) — a deliberate refinement of the doc's "per-theme declaration" framing: surface-mode is
  a user accessibility/perf choice like high-contrast, not a per-theme colour decision. A "Solid surfaces" toggle in
  Settings → Appearance (next to High contrast) calls `setSurfaceMode` (the theme authority bundles apply + persist),
  persisted FE-only in `UserStateDocument` (cross-profile, alongside `customThemes`/`activeThemeId` — keeps it a pure
  presentation pref with no backend `UiSettingsV2` change) and replayed by `restoreAppearanceOnBoot`. It is one
  switch, never N hand-edited tokens (which would re-introduce the §1 flattening defect).

### §9.5 Verification ledger

- **Static + unit:** `npm run typecheck` clean; **full suite 2987 tests / 314 files green** (+5 over the §8
  baseline). New/updated tests in `theme.test.ts` (import/rename), `contrast.test.ts` + `roleForegrounds.test.ts`
  (tinted), `themeState.test.ts` + `UserStateDocument.test.ts` (surface-mode apply/persist/reparse).
- **Governance gates:** `check-contrast-matrix` OK (all 32 role pairings clear AA in both themes);
  `check-color-tokens` OK; `check-layout-purity` OK; `style-literal-ratchet` + `ambient-purity` pass. ESLint 0
  errors. `check-accent-as-text` fails — but on `FolderCardRenderer.ts` + `PresentationEditorSurface.ts`, **files
  not in this diff** (pre-existing on `main`; logged to `observations.md`).
- **Live (Chrome, worktree dev stack):** app boots clean, no console errors from this change.
  - *Tinted foreground:* runtime `jf-role-foregrounds` emits the §9.3 inks (`accent-on-chat: #004037`, … `danger`
    stays `#000000`), verified against the live-resolved accents; the tinted ink renders legibly on the teal pills.
  - *Surface-mode:* solid flips `--glass-blur-scale` 1→0 and the glass surfaces translucent→opaque `color-mix`
    (confirmed via computed values in **both** dark and light); the Health surface renders cleanly in dark+solid and
    light+solid (opaque panels, all text legible, no breakage). NB the *blur removal* itself is invisible in
    screenshots — Playwright/CDP renders backdrop-blur flat regardless — so the `--glass-blur-scale: 0` computed
    value is the proof of that half.
  - *Harness notes (pre-existing, logged):* `jseval ui-shot` waits for the `search-input` testid 577 retired from
    the boot surface; the Settings surface intermittently freezes the renderer on its lazy-load (independent of this
    change — it predates it). The Settings import/rename/solid-toggle controls are unit-covered + mirror the proven
    high-contrast/delete patterns; a clean Settings screenshot is the one harness-blocked smoke.
