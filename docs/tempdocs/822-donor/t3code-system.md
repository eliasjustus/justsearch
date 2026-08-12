# T3 Code — Donor Design *System* Spec

Companion to `decision-sheet.md` (which captures the **T3 Chat** *screen* — measured from
live screenshots). This doc captures the **T3 Code** *source* — tokens, geometry, motion,
component CSS — mined from the repository. Where the two overlap I defer to the
decision-sheet's vocabulary and only note deltas.

**Source:** T3 Code, cloned at
`C:\Users\Elias\AppData\Local\Temp\claude\F--justsearch-public\bccfc163-7b8f-4b1a-b9e4-0c011632d8a1\scratchpad\t3code\`
All `file:line` citations below are relative to that root. `.repos/` is a vendored
third-party tree and was excluded.

**License & attribution obligation.** T3 Code is **MIT licensed** — `LICENSE:1-3`,
"Copyright (c) 2026 T3 Tools Inc." Verbatim CSS blocks are reproduced below under that
license. If JustSearch ships derived CSS, the MIT copyright notice and permission text must
travel with it (a `THIRD-PARTY-NOTICES` entry naming T3 Tools Inc. satisfies this). The one
font binary in the tree, `apps/web/src/terminal/ghostty/fonts/SymbolsNerdFontMono-Regular.woff2`,
is separately MIT (`apps/web/src/terminal/ghostty/fonts/LICENSE:1-3`, Ryan L McIntyre / Nerd
Fonts). **No proprietary or licensed brand font exists in the repo** — see §2.

---

## Contents

1. [Token layer](#1-token-layer)
2. [Type system](#2-type-system)
3. [Shell geometry](#3-shell-geometry)
4. [Elevation & surfaces](#4-elevation--surfaces)
5. [Motion system](#5-motion-system)
6. [Component anatomy](#6-component-anatomy)
7. [Density decisions](#7-density-decisions)
8. [Lit translation notes](#8-lit-translation-notes)
9. [What not to copy](#9-what-not-to-copy)

---

## 1. Token layer

### 1.1 Architecture — four tiers, one direction of flow

T3 Code's token graph flows strictly **primitive → semantic → component**, and dark mode is
applied by *redefining semantic tokens*, never by re-authoring components.

| Tier | Where | Example |
|---|---|---|
| **T0 Primitive** | Tailwind v4's built-in palette (`--color-zinc-800`, `--color-neutral-950`, `--color-red-500`) + one custom addition `--color-zinc-25` (`apps/web/src/index.css:143`) | `oklch(99.2% 0 0)` |
| **T1 Semantic role** | one big `:root { … @variant dark { … } }` block, `apps/web/src/index.css:1059-1192` | `--background`, `--foreground`, `--card`, `--popover`, `--muted-foreground`, `--sidebar-row-hover` |
| **T2 Geometry / material** | a *separate* early `:root`, `apps/web/src/index.css:78-105` | `--control-radius`, `--sidebar-content-inset`, `--glass-blur`, `--workspace-topbar-height` |
| **T3 Tailwind bridge** | `@theme inline { … }`, `apps/web/src/index.css:142-265` | `--color-foreground: var(--foreground)` — this is what makes `bg-background` / `text-muted-foreground` resolve |

Three architectural decisions worth stealing:

**(a) `@theme inline` is a pure aliasing layer.** Every entry is `--color-X: var(--X)`
(`index.css:150-198`). Tailwind v4's `@theme` normally *bakes* values; `inline` makes it emit
`var()` references instead, so a runtime override of `--foreground` (theme switch, user
setting) propagates through every `text-foreground` utility without a rebuild. This is the
mechanism that lets themes be data, not CSS.

**(b) Fonts live *outside* `@theme inline`,** in a plain `@theme` — with the reason stated:

> ```css
> /* The font tokens are declared outside the inline theme so utilities reference
>    the variables and Settings -> Appearance can override them at runtime. The
>    default stacks are mirrored in `appearanceFonts.ts`. */
> @theme {
>   --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
>   --font-mono:
>     ui-monospace, "SF Mono", "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace;
> }
> ```
> — `apps/web/src/index.css:133-140`

**(c) Dark mode is scoped by a class custom-variant, not a media query:**

```css
@custom-variant dark (&:is(.dark, .dark *));
/* Window Controls Overlay: active when Electron exposes native titlebar control geometry. */
@custom-variant wco (&:is(.wco, .wco *));
```
— `apps/web/src/index.css:3-5`

`wco` is the same trick applied to a *platform capability* rather than a color scheme —
a second, orthogonal variant axis. See §3.4.

**(d) A fourth, optional override layer for user themes.** `html[data-theme-id]`
(`index.css:1250-1317`) remaps every T1 semantic token onto an `--app-theme-*` role set
(48 roles enumerated at `apps/web/src/themePalette.ts:1888-1937`). The comment states the
policy on which roles are themeable:

> ```
> Theme files are expressed in app color roles and mapped to the existing
> semantic surface tokens here. […] Success, info, provider, and channel identity
> colors remain independent; error, warning, and update roles are themeable below.
> ```
> — `apps/web/src/index.css:1245-1249`

That's a genuinely load-bearing rule: **semantic *status* colors that mean something
(success/info) are not user-themeable; decorative and severity-of-your-own-content colors
are.** Worth adopting verbatim in JustSearch.

### 1.2 Geometry tokens — verbatim

```css
:root {
  --app-scrollbar-width: 6px;
  --app-scrollbar-thumb: rgb(217 217 217);
  --app-scrollbar-thumb-hover: rgb(191 191 191);
  /*
   * Compact UI geometry. Keep these values semantic so sidebar, palette,
   * tooltip, and toolbar controls cannot quietly drift apart.
   */
  --control-radius: 0.5rem;
  --sidebar-content-inset: 0.5rem;
  --sidebar-control-gap: 0.5rem;
  --sidebar-icon-color: color-mix(in srgb, var(--sidebar-muted-foreground) 60%, var(--sidebar));
  --sidebar-row-content-inset: 0.625rem;
  --command-shell-inset: 0.5rem;
  --command-content-inset: 1rem;
  --floating-content-inset: 0.75rem;
  --glass-blur: 12px;
  --glass-opacity: 80%;
  --glass-saturation: 1.14;
  --desktop-window-right-resize-inset: 0px;
  --workspace-topbar-height: 52px;
  --workspace-controls-top: 0px;
  --workspace-controls-left: calc(env(safe-area-inset-left) + 0.75rem);
  --workspace-controls-right: calc(env(safe-area-inset-right) + 0.75rem);
  --workspace-native-controls-inset: 0px;
  --workspace-titlebar-control-size: 1.75rem;
  --workspace-titlebar-control-gap: 0.75rem;
}

.dark {
  --app-scrollbar-thumb: rgb(255 255 255 / 8%);
  --app-scrollbar-thumb-hover: rgb(255 255 255 / 12%);
  --glass-blur: 16px;
  --glass-saturation: 1.08;
}
```
— `apps/web/src/index.css:78-112`

Note the header comment's *intent*: these exist **so surfaces cannot drift apart**, not to
save keystrokes. And the `.dark` block only overrides four of them — dark mode gets *more*
blur (16px vs 12px) and *less* saturation boost (1.08 vs 1.14). That asymmetry is deliberate:
on dark backgrounds blur needs more radius to read as depth, and saturation boost reads as
garish.

The geometry tokens are **enforced by unit test**, not just convention:
`apps/web/src/components/ui/button.test.tsx:15` asserts `rounded-[var(--control-radius)]`;
`apps/web/src/components/ui/sidebar.test.tsx:58-65` asserts four of them on the sidebar row;
`apps/web/src/components/ui/command.test.tsx:13-28` asserts the two command-palette insets.
That's the "cannot quietly drift" comment made real.

### 1.3 Radius scale — derived, not enumerated

```css
--radius: 0.625rem;              /* index.css:1061 */
--radius-sm:  calc(var(--radius) - 4px);   /*  6px */
--radius-md:  calc(var(--radius) - 2px);   /*  8px */
--radius-lg:  var(--radius);               /* 10px */
--radius-xl:  calc(var(--radius) + 4px);   /* 14px */
--radius-2xl: calc(var(--radius) + 8px);   /* 18px */
--radius-3xl: calc(var(--radius) + 12px);  /* 22px */
--radius-4xl: calc(var(--radius) + 16px);  /* 26px */
```
— `apps/web/src/index.css:199-205`

**Additive, not multiplicative** — a 4px arithmetic ladder off one root. One knob
(`--radius`) shifts the whole app's roundness while preserving the *differences* between
tiers. Compare T3 Chat's captured radii in `decision-sheet.md §6`, which are enumerated
absolutes; the Code app's derived ladder is the more portable idea.

Separately, `--control-radius: 0.5rem` (8px) is **independent of `--radius`** — controls
(buttons, sidebar rows, palette items) have their own knob from surfaces (cards, dialogs).
Two-knob system.

A recurring inner-radius idiom throughout: an inset `::before` ring uses
`calc(var(--control-radius) - 1px)` / `calc(var(--radius-md) - 1px)` so the 1px-inset
highlight stays concentric with the outer border
(`apps/web/src/components/ui/button.tsx:11`, `apps/web/src/components/ui/tooltip.tsx:44`).

### 1.4 Semantic color tokens — verbatim (light + dark)

```css
:root {
  color-scheme: light;
  --radius: 0.625rem;
  --background: var(--color-zinc-25);
  --app-chrome-background: var(--background);
  --toolbar-background: var(--app-chrome-background);
  --toolbar-foreground: var(--foreground);
  --toolbar-border: var(--border);
  --toolbar-control: var(--popover);
  --toolbar-control-foreground: var(--foreground);
  --toolbar-control-hover: var(--accent);
  --surface-raised: color-mix(in srgb, var(--card) 20%, transparent);
  --foreground: var(--color-zinc-800);
  --card: var(--color-white);
  --card-foreground: var(--color-zinc-800);
  --popover: var(--color-white);
  --popover-foreground: var(--color-zinc-800);
  --primary: oklch(0.488 0.217 264);
  --primary-foreground: var(--color-white);
  --secondary: var(--color-zinc-50);
  --secondary-foreground: var(--color-zinc-800);
  --muted: var(--color-zinc-50);
  --muted-foreground: var(--color-zinc-500);
  --placeholder: var(--muted-foreground);
  --secondary-label: var(--muted-foreground);
  --icon-muted: var(--muted-foreground);
  --message-surface: var(--accent);
  --message-foreground: var(--foreground);
  --message-action: var(--primary);
  --message-action-foreground: var(--primary-foreground);
  --message-action-hover: color-mix(in srgb, var(--primary) 90%, var(--background));
  --accent: var(--color-zinc-100);
  --accent-foreground: var(--color-zinc-900);
  --error: var(--color-red-500);
  --error-foreground: var(--color-red-700);
  --error-surface: color-mix(in srgb, var(--error) 8%, transparent);
  --destructive: var(--error);
  --border: var(--color-zinc-200);
  --input: var(--color-zinc-300);
  --ring: var(--primary);
  --destructive-foreground: var(--error-foreground);
  --info: var(--color-blue-500);
  --info-foreground: var(--color-blue-700);
  --success: var(--color-emerald-500);
  --success-foreground: var(--color-emerald-700);
  --warning: var(--color-amber-500);
  --warning-foreground: var(--color-amber-700);
  --warning-surface: color-mix(in srgb, var(--warning) 8%, transparent);
  --update: var(--primary);
  --update-foreground: var(--primary);
  --update-surface: color-mix(in srgb, var(--update) 12%, transparent);
  /* Keep every sidebar primitive on the same light surface hierarchy, including
     portaled mobile sheets and settings navigation outside the app sidebar. */
  --sidebar: var(--color-zinc-50);
  --sidebar-foreground: var(--foreground);
  --sidebar-muted-foreground: var(--muted-foreground);
  --sidebar-control-surface: var(--color-zinc-100);
  --sidebar-row-hover: var(--color-zinc-25);
  --sidebar-row-active: var(--color-white);
  --sidebar-row-selected: var(--color-white);
  --sidebar-border: var(--border);
  --sidebar-stage-fade: var(--sidebar);
  --code-background: color-mix(in srgb, var(--card) 90%, var(--background));
  --code-foreground: var(--foreground);
  --terminal-background: var(--background);
  --terminal-foreground: var(--foreground);
  --terminal-cursor: rgb(38 56 78);
  --terminal-selection-background: rgb(37 63 99 / 20%);
  --terminal-scrollbar: rgb(0 0 0 / 15%);
  --terminal-scrollbar-hover: rgb(0 0 0 / 25%);

  @variant dark {
    color-scheme: dark;
    /* Keep the workspace in the same neutral-black family as sidebar v2.
       Surfaces lift from this base instead of starting from a milky gray. */
    --background: var(--color-neutral-950);
    --app-chrome-background: var(--background);
    --surface-raised: var(--secondary);
    --foreground: var(--color-neutral-100);
    --card: color-mix(in srgb, var(--background) 97%, var(--color-white));
    --card-foreground: var(--color-neutral-100);
    --popover: color-mix(in srgb, var(--background) 94%, var(--color-white));
    --popover-foreground: var(--color-neutral-100);
    --primary: oklch(0.571 0.21 264);
    --primary-foreground: var(--color-white);
    --secondary: --alpha(var(--color-white) / 4%);
    --secondary-foreground: var(--color-neutral-100);
    --muted: --alpha(var(--color-white) / 4%);
    --muted-foreground: color-mix(in srgb, var(--color-neutral-500) 90%, var(--color-white));
    --placeholder: var(--muted-foreground);
    --secondary-label: var(--muted-foreground);
    --icon-muted: var(--muted-foreground);
    --message-surface: var(--accent);
    --message-foreground: var(--foreground);
    --message-action: var(--primary);
    --message-action-foreground: var(--primary-foreground);
    --message-action-hover: color-mix(in srgb, var(--primary) 90%, var(--background));
    --accent: --alpha(var(--color-white) / 4%);
    --accent-foreground: var(--color-neutral-100);
    --error: color-mix(in srgb, var(--color-red-500) 90%, var(--color-white));
    --error-foreground: var(--color-red-400);
    --error-surface: color-mix(in srgb, var(--error) 16%, transparent);
    --destructive: var(--error);
    --border: --alpha(var(--color-white) / 6%);
    --input: --alpha(var(--color-white) / 8%);
    --ring: var(--primary);
    --destructive-foreground: var(--error-foreground);
    --info: var(--color-blue-500);
    --info-foreground: var(--color-blue-400);
    --success: var(--color-emerald-500);
    --success-foreground: var(--color-emerald-400);
    --warning: var(--color-amber-500);
    --warning-foreground: var(--color-amber-400);
    --warning-surface: color-mix(in srgb, var(--warning) 16%, transparent);
    --update: var(--primary);
    --update-foreground: var(--color-blue-400);
    --update-surface: color-mix(in srgb, var(--update) 18%, transparent);
    --sidebar: var(--card);
    --sidebar-foreground: var(--foreground);
    --sidebar-muted-foreground: var(--muted-foreground);
    --sidebar-control-surface: var(--muted);
    --sidebar-row-hover: var(--accent);
    --sidebar-row-active: var(--accent);
    --sidebar-row-selected: var(--muted);
    --sidebar-border: var(--border);
    --sidebar-stage-fade: var(--card);
    --terminal-background: var(--background);
    --terminal-foreground: var(--foreground);
    --terminal-cursor: rgb(180 203 255);
    --terminal-selection-background: rgb(180 203 255 / 25%);
    --terminal-scrollbar: rgb(255 255 255 / 10%);
    --terminal-scrollbar-hover: rgb(255 255 255 / 18%);
  }
}
```
— `apps/web/src/index.css:1059-1192`

**Five patterns worth extracting from that block:**

1. **Light mode surfaces are *opaque named grays*; dark mode surfaces are *alpha-over-base*.**
   Light: `--muted: var(--color-zinc-50)`. Dark: `--muted: --alpha(var(--color-white) / 4%)`.
   The dark palette is built almost entirely from white-at-low-alpha over
   `--color-neutral-950`, which means every dark surface automatically composites correctly
   over *whatever* is behind it (including glass). Light mode can't do this because
   white-over-white is invisible.

2. **Dark elevation is a `color-mix` ladder, not a shadow ladder.**
   `--card: mix(background 97%, white)` → `--popover: mix(background 94%, white)`. Two steps,
   3% apart. See §4.

3. **`--background` in dark is `neutral-950` with a stated reason** — "Surfaces lift from
   this base instead of starting from a milky gray." Near-black base, surfaces go *up*.

4. **Semantic status colors are a fixed 5 (`error` / `info` / `success` / `warning` /
   `update`), each with a `-foreground` and three of them with a `-surface`.** The `-surface`
   variants are `color-mix(… N%, transparent)` — 8%/12% in light, 16%/18% in dark. Roughly
   **double the tint in dark**, because a low-alpha tint over near-black is nearly invisible.

5. **`--secondary-label`, `--placeholder`, `--icon-muted` all alias `--muted-foreground`.**
   Three *named intents* pointing at one value. This is the "name the role even when the value
   is shared" discipline — it means a future divergence is a one-line change, not a grep.

### 1.5 Scrollbars

```css
::-webkit-scrollbar        { width: var(--app-scrollbar-width); }        /* 6px */
::-webkit-scrollbar-track  { background: transparent; }
::-webkit-scrollbar-thumb  { background: var(--app-scrollbar-thumb); border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: var(--app-scrollbar-thumb-hover); }
```
— `apps/web/src/index.css:1672-1688`

6px wide, fully-round (3px = half of 6), transparent track, thumb at **8% white in dark**
rising to 12% on hover (`index.css:108-109`). Code surfaces use a slightly different pair
(`scrollbar-width: thin` + `scrollbar-color: color-mix(in srgb, var(--border) 78%, transparent) transparent`,
7px webkit height — `index.css:2007-2023`). One strip (`.turn-chip-strip`) hides its
scrollbar entirely (`index.css:1780-1788`).

**The 6px width is load-bearing in three unrelated places** — scroll-fade masks reserve
`var(--app-scrollbar-width)` as an *unmasked* right column so the fade gradient doesn't dim
the scrollbar itself (`index.css:544`, `1748`, `1754`). That's a detail almost everyone gets
wrong.

### 1.6 Z-layers

There is **no z-token scale**; z-indexes are literal utilities. Observed inventory across
`apps/web/src` (occurrence counts):

| z | count | role |
|---|---|---|
| `z-0` / `z-1` / `z-2` | 7/2/1 | intra-component stacking (glass `::before` at 0, outline `::after` at 1) |
| `z-10` | 38 | content above a decorative backdrop |
| `z-20` | 18 | expanded banner stack, dragging row |
| `z-30` | 3 | — |
| `z-40` | 4 | — |
| `z-50` | 14 | dialogs/backdrops (`DIALOG_BACKDROP_CLASS`), titlebar controls |
| `z-70` | 1 | tooltip positioner (`apps/web/src/components/ui/tooltip.tsx:36`) |
| `z-100` | 2 | topmost |

Effective ladder: **backdrop 50 → dialog content 50 → tooltip 70 → 100**. Tooltips
deliberately sit *above* dialogs. This is the one part of the token layer T3 Code did **not**
systematize, and is a place JustSearch should improve on rather than copy (§8).

---

## 2. Type system

### 2.1 No shipped UI font. At all.

The entire product UI runs on the platform system stack:

```
--font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif
--font-mono: ui-monospace, "SF Mono", "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace
```
— `apps/web/src/index.css:137-139`, mirrored as `DEFAULT_SANS_FONT_STACK` /
`DEFAULT_CODE_FONT_STACK` at `apps/web/src/appearanceFonts.ts:21-26`.

There are **zero `@font-face` rules and zero webfont files** in `apps/web` beyond the terminal
symbol font. The only font binaries in the whole repo are
`apps/mobile/.../MesloLGS-NF-{Bold,Regular}.ttf` and
`apps/web/src/terminal/ghostty/fonts/SymbolsNerdFontMono-Regular.woff2` — all terminal glyph
coverage, all MIT.

The mono stack carries its own rationale comment, which is a real trap worth knowing:

> ```
> // Concrete names first: some engines alias `ui-monospace` to the
> // proportional system UI font, which would break every code surface.
> ```
> — `apps/web/src/appearanceFonts.ts:24-25`

(Note `--font-mono` in `index.css:138` *does* lead with `ui-monospace`; the
`DEFAULT_CODE_FONT_STACK` used by the user-preference path does not. Minor internal
inconsistency in the donor, not a pattern to copy — take the `appearanceFonts.ts` ordering.)

### 2.2 User-controlled sizes, with contract-defined bounds

Three independently-adjustable sizes, defined in `packages/contracts/src/settings.ts:71-93`:

| Preference | Min | **Default** | Max | Applied as |
|---|---|---|---|---|
| Interface | 12 | **16** | 20 | `root.style.fontSize` — *drives every rem* |
| Prompt (composer) | 12 | **14** | 20 | `--font-size-prompt`, absolute px |
| Code | 10 | **13** | 18 | `--font-size-code`, absolute px |

The split is deliberate and documented:

> ```
> * Sizes are always written: the interface size drives the root font size (and
> * with it every rem-based dimension), while the prompt and code sizes stay in
> * absolute pixels so they do not scale twice.
> ```
> — `apps/web/src/appearanceFonts.ts:92-95`

**This is the single most valuable typography idea in the donor for JustSearch.** One user
control that scales *the whole layout* (because all geometry is rem), plus opt-out absolute
sizes for the surfaces where scaling twice would be wrong. Also note the mobile floor guard:

> ```css
> /* Touch browsers zoom the page when a focused field is under 16px, so keep
>    the floor there regardless of the preference. Gated on a coarse pointer:
>    the zoom quirk does not exist on desktop, where a narrow window must not
>    silently override a smaller chosen prompt size. */
> @media (max-width: 39.999rem) and (pointer: coarse) {
>   .composer-editor-surface { font-size: max(var(--font-size-prompt, 1rem), 16px); }
> }
> ```
> — `apps/web/src/index.css:1698-1706`

### 2.3 The effective ramp — measured by utility frequency

Counting Tailwind text utilities across `apps/web/src` (`*.tsx`, `*.ts`):

| Utility | px @16 root | Count | Role |
|---|---|---|---|
| `text-xs` | 12 | **403** | dominant — sidebar rows, metadata, chips, timestamps, code |
| `text-sm` | 14 | **234** | body / primary row titles / menu items |
| `text-base` | 16 | 32 | *mostly the mobile half of a `text-base sm:text-sm` pair* |
| `text-2xl` | 24 | 9 | |
| `text-xl` | 20 | 7 | |
| `text-lg` | 18 | 7 | |
| `text-3xl` | 30 | 7 | |
| `text-4xl` | 36 | 1 | |

Weights:

| Utility | Count |
|---|---|
| `font-medium` (500) | **280** |
| `font-semibold` (600) | 113 |
| `font-normal` (400) | 22 (almost always an *override back* to normal for receded rows) |
| `font-bold` (700) | 1 |

**Tracking: zero occurrences. Leading: zero occurrences.** No `tracking-*` and no `leading-*`
utilities anywhere in `apps/web/src/**/*.tsx`. Line-height is entirely the Tailwind default
per size, with two exceptions: markdown headings pin `line-height: 1.3`
(`index.css:1835`) and the `input` component sets `leading-` to exactly match its own height
(`leading-8.5` on `h-8.5` — `apps/web/src/components/ui/input.tsx:22-24`), a single-line
vertical-centering trick rather than a type decision. One `tracking-widest` exists, on the
command-palette keyboard shortcut only (`apps/web/src/components/ui/command.tsx:211`).

### 2.4 The desktop ramp is one step *down* from the mobile ramp

Nearly every control declares `text-base sm:text-sm` or `text-sm sm:text-xs` — the `sm:`
breakpoint is 40rem, so **desktop always gets the smaller of the pair**. Effective desktop
ramp:

| Role | Desktop | Mobile |
|---|---|---|
| Button / input label | 14 (`sm:text-sm`) | 16 (`text-base`) |
| Sidebar row title | 14 (`text-sm`) | 14 |
| Metadata / timestamps / badges / group labels | 12 (`text-xs`) | 12–14 |
| Badge `sm` | 10 (`sm:text-[.625rem]`) | 12 |

— `apps/web/src/components/ui/button.tsx:11`, `badge.tsx:18-21`, `sidebar.tsx:807-810`,
`input.tsx:61`.

### 2.5 Comparison to T3 Chat's captured ramp

`decision-sheet.md §3` records T3 Chat at **30 / 16 / 14 / 12**, weights **400/500/600**,
ProximaVara with **+0.24px tracking**.

**Same weight DNA, different type identity.** The 500/600 dominance with a sparse 400 is
identical — both apps say "medium is the body weight, semibold is the only emphasis, bold
doesn't exist." The size ramp overlaps in its working range (16/14/12 in Chat vs a
14/12-dominated desktop range in Code), and 30 in Chat is the greeting hero, matched by
Code's 7 uses of `text-3xl`.

But the **identity diverges completely on two axes**: (1) Chat ships a licensed brand
face (ProximaVara) with hand-tuned positive tracking, which is a *branding* decision; Code
ships **nothing** and defers to the OS, which is a *native-app* decision, and consequently has
zero tracking anywhere. (2) Code's centre of gravity is a step *tighter* — `text-xs` outnumbers
`text-sm` nearly 2:1, whereas Chat's 14/16 pair carries the transcript. Code is an information-
dense supervision console; Chat is a reading surface.

**For JustSearch (a search window — dense, scannable, results-per-screen matters):** take
T3 Code's ramp and its system-stack decision, not T3 Chat's. The xs-dominant ramp plus a
user-adjustable rem root is exactly the right shape for a result list. Skip the brand-font
+ tracking layer entirely — it is Chat's identity, has a license cost, and buys nothing in a
utility window.

---

## 3. Shell geometry

### 3.1 Topbar

```css
.workspace-topbar {
  display: flex;
  height: var(--workspace-topbar-height);
  min-height: var(--workspace-topbar-height);
  flex-shrink: 0;
  align-items: center;
}
```
— `apps/web/src/index.css:513-519`

**52px**, fixed, non-shrinking (`--workspace-topbar-height`, `index.css:98`). Horizontal
padding is responsive and safe-area-aware:
`px-3 sm:px-5` in the Electron/drag case, and in the non-Electron case
`pl-[calc(env(safe-area-inset-left)+0.75rem)] … sm:…+1.25rem`
(`apps/web/src/components/ChatView.tsx:6140-6145`).

One panel locally overrides the token rather than hardcoding:
`[--workspace-topbar-height:--spacing(11)]` (44px) for the non-inline right panel
(`apps/web/src/components/RightPanelTabs.tsx:596`). Token override as a layout API — good
pattern.

### 3.2 Sidebar

| Dimension | Value | Source |
|---|---|---|
| Default width | `16rem` (256px) | `apps/web/src/components/ui/sidebar.tsx:27`; `THREAD_SIDEBAR_DEFAULT_WIDTH = 16*16` at `threadSidebarWidth.ts:2` |
| Min width (drag) | `13rem` (208px) | `threadSidebarWidth.ts:3` |
| Max width (drag) | `viewport − 40rem` | `threadSidebarWidth.ts:4,6-11` — i.e. **the main pane's 640px minimum defines the sidebar's maximum** |
| Icon-collapsed width | `3rem` (48px) | `sidebar.tsx:29` |
| Mobile width | `calc(100vw - var(--spacing(3)))` | `sidebar.tsx:28` |
| Content padding | `--sidebar-content-inset` = 8px | `sidebar.tsx:716` |
| Row horizontal inset | `--sidebar-row-content-inset` = 10px | `sidebar.tsx:807` |
| Icon↔label gap | `--sidebar-control-gap` = 8px | `sidebar.tsx:798` |
| Group label row | `h-8`, `px-2`, `text-xs`, `font-medium` | `sidebar.tsx:729` |
| Resize handle | `w-4` hit area, `after:w-[2px]` visible line, `hover:after:bg-sidebar-border` | `sidebar.tsx:602` |

Menu-button size ladder (`sidebar.tsx:806-810`):

| size | height | radius | padding | text |
|---|---|---|---|---|
| `sm` | `h-7` (28) | `rounded-lg` | `p-2` | `text-xs` |
| `default` | `h-8` (32) | `var(--control-radius)` | `px-[--sidebar-row-content-inset] py-1.5` | `text-sm` |
| `icon` | `size-8` | `var(--control-radius)` | `p-0` | — |
| `lg` | `h-12` (48) | `rounded-lg` | `p-2` | `text-sm` |

The **10px row inset against the 8px panel inset** is the subtle part: rows are inset 8px
from the panel edge, then their content is inset another 10px, giving 18px from window edge
to glyph — but the hover/selected fill starts at 8px, so the fill reads as a proper pill
rather than a full-bleed band. Copy this two-level inset exactly.

### 3.3 Command palette

| Part | Spec | Source |
|---|---|---|
| Popup | `max-h-105` (26.25rem / 420px), `max-w-xl` (36rem / 576px) | `apps/web/src/components/ui/command.tsx:68` |
| Shell (input row) | `px-[var(--command-shell-inset)]` (8px), `py-1.5` | `command.tsx:108` |
| Search icon offset | `ps-[calc(var(--command-shell-inset)+1.5rem)]` on desktop; `ps-9` mobile | `command.tsx:115` |
| List | `p-2`, `scroll-py-2` | `command.tsx:129` |
| Item | `py-1.5`; selected `bg-foreground/[0.06]`; highlighted `bg-foreground/[0.09]` | `command.tsx:180-181` |
| Empty | `py-6` | `command.tsx:143` |
| Separator | `my-2` | `command.tsx:196` |
| Footer | `px-[var(--command-content-inset)]` (16px), `py-2.5`, `bg-foreground/[0.025]`, `text-sm text-muted-foreground`, `rounded-b-[calc(var(--radius-2xl)-1px)]` | `command.tsx:219` |
| Shortcut kbd | `text-xs font-medium font-sans tracking-widest text-secondary-label` | `command.tsx:211` |
| Panel corners | `rounded-t-xl`, `not-has-[+[data-slot=command-footer]]:rounded-b-2xl` | `command.tsx:153` |

Two things to steal: (a) **two different insets — 8px for the input shell, 16px for the
footer** — the input row is tighter than the chrome around it, which makes the field feel
edge-to-edge; (b) **selection and highlight are distinct at 6% and 9% foreground-alpha**, and
when a row is both, it takes the 9% — the palette never shows two competing fills.

### 3.4 Window-controls overlay (Electron) and safe areas

```css
.wco {
  --workspace-topbar-height: env(titlebar-area-height, 52px);
  --workspace-controls-top: env(titlebar-area-y, 0px);
  --workspace-controls-left: calc(env(titlebar-area-x, 0px) + 0.75rem);
  --workspace-controls-right: calc(
    100vw - env(titlebar-area-width, 100vw) - env(titlebar-area-x, 0px) + 0.75rem
  );
  --workspace-native-controls-inset: calc(
    100vw - env(titlebar-area-width, 100vw) - env(titlebar-area-x, 0px) + 0.75rem
  );
}
```
— `apps/web/src/index.css:121-131`

The `wco` class **re-points the same four geometry tokens at `env(titlebar-area-*)`**, so no
component needs a WCO branch — they all read `--workspace-controls-right` and get the right
answer either way. Components that must still react use the `wco:` variant, e.g.
`wco:pr-[var(--workspace-native-controls-inset)]` (`ChatView.tsx:6143`) and
`wco:h-[env(titlebar-area-height)]` (`routes/settings.tsx:96`).

Derived token for the brand slot, so the logo clears the native buttons:

```css
[data-slot="sidebar-wrapper"] {
  --workspace-titlebar-content-left: calc(
    var(--workspace-controls-left) + var(--workspace-titlebar-control-size) +
      var(--workspace-titlebar-control-gap)
  );
}
```
— `apps/web/src/index.css:114-119`

macOS traffic lights get a JS-set constant: `MACOS_TRAFFIC_LIGHTS_LEFT_INSET = "90px"`,
applied as an inline `--workspace-controls-left` override **only when not fullscreen**
(`apps/web/src/components/AppSidebarLayout.tsx:44,165-169`).

Windows gets a 6px right gutter so the OS resize grip is reachable:
`.electron-windows { --desktop-window-right-resize-inset: 6px; }` consumed by
`#root { padding-right: var(--desktop-window-right-resize-inset); }`
(`index.css:1601,1604-1606`).

Safe areas: four `@utility` rules (`pt-safe`/`pb-safe`/`pl-safe`/`pr-safe`) using
`max(env(safe-area-inset-*), 0px)` (`index.css:1025-1039`), plus
`html, body { min-height: calc(100svh + env(safe-area-inset-top)); overscroll-behavior: none; }`
(`index.css:1581-1585`) and `#root { padding-top: max(env(safe-area-inset-top), 0px); }`
(`index.css:1600`).

Drag region:

```css
.drag-region { -webkit-app-region: drag; }
.drag-region button, .drag-region input, .drag-region textarea,
.drag-region select, .drag-region a { -webkit-app-region: no-drag; }
```
— `apps/web/src/index.css:1659-1670`

**Opt-out by element type, not by class.** Every interactive tag inside a drag region is
automatically no-drag. Only exotic cases (`role="button"` divs) need the explicit
`[-webkit-app-region:no-drag]` seen at `ChatView.tsx:6010`.

### 3.5 Other shell numbers

- Right panel switches to inline layout at `(max-width: 980px)` — `apps/web/src/rightPanelLayout.ts:1`.
- `body { height: 100%; overflow: hidden; }` and `#root { overflow-x: clip; overflow-y: hidden; overscroll-behavior-y: none; }` — the app never scrolls; only inner regions do (`index.css:1587-1602`).
- Subheader band: `h-10`, `border-b border-border/60`, `bg-background` (`index.css:604-606`), compressed to `28px` with a transparent border in inline preview mode (`index.css:608-613`).

---

## 4. Elevation & surfaces

### 4.1 Surface levels — four, plus glass

| Level | Light | Dark | Cue |
|---|---|---|---|
| L0 **chrome/body** | `zinc-25` | `neutral-950` | grain texture, no border |
| L1 **sidebar** | `zinc-50` | `= --card` | 1px border only |
| L2 **card** | `white` | `mix(bg 97%, white)` | border + `shadow-xs/5` |
| L3 **popover** | `white` | `mix(bg 94%, white)` | border + `shadow-md/5` |
| L4 **glass** (dialog / dropdown / composer) | translucent + blur | translucent + blur | blur + heavy shadow + inset highlight |
| L(-1) **raised-in-place** | `mix(card 20%, transparent)` | `= --secondary` (white 4%) | no border — a *tint*, not a surface |

— `apps/web/src/index.css:1062-1192`

Note L2 and L3 are **identical in light** (both `white`) and separated only in dark (97% vs
94%). Light mode separates them with shadow; dark mode separates them with lightness. That's
the correct instinct: shadows don't read on near-black, lightness doesn't read on white.

### 4.2 Glass — three recipes, not one

```css
/* Ambient glass: composer bar, alerts */
.chat-composer-glass {
  background: color-mix(in srgb, var(--background) var(--glass-opacity), transparent);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
}
```
— `apps/web/src/index.css:620-624`

```css
.dropdown-glass {
  /*
   * Elevated glass needs a denser tint than broad ambient surfaces. Nesting
   * the user-controlled mix inside an 18% popover tint preserves the full
   * opacity setting range (40% -> 51%, 80% -> 84%, 100% -> 100%) while
   * keeping high-contrast page content from blooming through menus.
   */
  background: color-mix(
    in srgb,
    var(--popover) 18%,
    color-mix(in srgb, var(--popover) var(--glass-opacity), transparent)
  );
  -webkit-backdrop-filter: blur(var(--glass-blur));
  backdrop-filter: blur(var(--glass-blur));
  border: 1px solid color-mix(in srgb, var(--foreground) 10%, transparent);
  box-shadow: 0 16px 40px -18px rgb(0 0 0 / 55%);
}
```
— `apps/web/src/index.css:835-851`

That nested-mix comment is the best single idea in the elevation system: **the user's glass-
opacity slider is remapped through a floor so that even at its most transparent setting, a
menu stays legible over busy content** — and the comment gives the exact remapping
(40→51, 80→84, 100→100). Steal the technique and the comment.

```css
.dialog-backdrop {
  background: color-mix(in srgb, var(--background) 60%, transparent);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
}
.dark .dialog-backdrop { background: color-mix(in srgb, var(--background) 64%, transparent); }
```
— `apps/web/src/index.css:829-833,877-879`

Backdrop blur is **4px, not 12/16** — a fifth as much as the surfaces themselves. Scrim, not glass.

Alert glass tints by severity via one variable:

```css
.alert-glass { --alert-glass-tint: transparent;
  background:
    linear-gradient(color-mix(in srgb, var(--alert-glass-tint) 4%, transparent),
                    color-mix(in srgb, var(--alert-glass-tint) 4%, transparent)),
    color-mix(in srgb, var(--background) var(--glass-opacity), transparent) !important;
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-saturation));
}
.alert-glass[data-variant="error"]   { --alert-glass-tint: var(--destructive); }
.alert-glass[data-variant="info"]    { --alert-glass-tint: var(--info); }
.alert-glass[data-variant="success"] { --alert-glass-tint: var(--success); }
.alert-glass[data-variant="warning"] { --alert-glass-tint: var(--warning); }
```
— `apps/web/src/index.css:794-821`

A **4% severity wash** layered over the standard glass. Four states, one rule, four
one-line variants.

### 4.3 Shadow inventory

| Surface | Light | Dark |
|---|---|---|
| Composer host | `0 12px 28px -18px rgb(0 0 0 / 40%)` | **none** — replaced by `inset 0 1px rgb(255 255 255 / 3%)` |
| Dropdown | `0 16px 40px -18px rgb(0 0 0 / 55%)` | `0 18px 44px -18px rgb(0 0 0 / 80%)` |
| Dialog | `0 24px 64px -24px rgb(0 0 0 / 65%)` | `inset 0 1px rgb(255 255 255 / 4%)`, `0 24px 72px -20px rgb(0 0 0 / 90%)` |
| Button (outline) | `0 1px --theme(--color-black/4%)` on `::before` | `0 -1px --theme(--color-white/6%)` on `::before` |

— `index.css:692,733,743-749,850,855,858-875`; `components/ui/button.tsx:43`

**The dark-mode elevation inversion is the pattern.** In dark mode, drop shadows are either
removed or made near-black-and-huge, and elevation is signalled instead by a **1px inset
highlight on the top edge** — `inset 0 1px white/3–4%`. Same in the button: light gets
`0 1px black/4%` (a bottom shadow), dark gets `0 -1px white/6%` (a top highlight). Light says
"this casts down"; dark says "this catches light."

Blur-radius ladder is consistent: `28 → 40 → 64` px for composer → dropdown → dialog, with
a large negative spread (`-18` to `-24`) so the shadow is a tight contact shadow, not a halo.

### 4.4 Grain

```css
:root {
  --surface-grain: url("data:image/svg+xml,…feTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'…opacity='0.035'…");
  --surface-grain-size: 256px 256px;
}
body { background-image: var(--surface-grain); background-repeat: repeat; background-size: var(--surface-grain-size); }
```
— `apps/web/src/index.css:1615-1632`; utility form at `1044-1048`

With a performance rationale worth reproducing in full:

> ```
> /* App-chrome grain. Baked into each surface's own background (behind
>    content) rather than a fixed overlay on top: a full-viewport overlay
>    forces the compositor to re-blend every frame any animation produces,
>    which multiplied idle GPU cost. The overlay's 0.035 opacity lives in the
>    SVG rect instead. Surfaces that float over the body (e.g. the inset main
>    card) must opt in via the surface-grain utility, or they lose the
>    grain's subtle brightening and stand out against the body. */
> ```
> — `apps/web/src/index.css:1608-1614`

Procedural SVG turbulence at **3.5% opacity, 256px tile**, as a `background-image` on each
surface — never as a fixed overlay. The branded preset drops to a 128px tile at 2.745%
opacity to match the real T3 Chat texture (`index.css:1620-1626`).

---

## 5. Motion system

This is the section live captures cannot produce. T3 Code's motion is unusually
*principled* — the through-line is **"animate on the compositor, and hold still most of the
time."**

### 5.1 The duration/easing budget

Measured across `apps/web/src`:

| Duration | Count | Used for |
|---|---|---|
| **200ms** | 35 | layout: height, padding-left, sidebar width, dialog scale/opacity, chevron rotation, hover-reveal of action bars |
| **150ms** | 27 | micro-interaction: color, opacity, banner reveal, button scale |
| **180ms** | 5 | the composer-context morph (and the mobile view transition) |
| **120ms** | 5 (CSS) | slider thumb, scrollbar thumb color |
| **130ms** | 2 (CSS) | mobile draft-headline exit |
| **220ms** | 1 | banner dismiss (`DISMISS_TRANSITION_MS`) |
| **250 / 500 / 2000ms** | 1 / 2 / 4 | one-offs; 2000ms is the connecting-state ping |
| **650ms × 2** | 1 | settings search-target pulse |

| Easing | Count | Used for |
|---|---|---|
| `ease-out` | 16 | anything appearing/expanding |
| `ease-linear` | 13 | width/padding (sidebar open, topbar reflow) |
| `cubic-bezier(0.32, 0.72, 0, 1)` | 4 | the composer-context compaction morph |
| `ease-in` | 2 | exits (banner dismiss) |
| `ease-in-out` | 1 | dialog |
| `cubic-bezier(0.22, 1, 0.36, 1)` | 1 | |
| `cubic-bezier(0.4, 0, 0.2, 1)` | (CSS) | mobile composer view-transition group |

**Rules that fall out:** enter = `ease-out`; exit = `ease-in`; continuous geometric change
tied to a drag or a toggle = `ease-linear`; expressive morph = a custom bezier. Durations are
essentially a **two-value budget (150 / 200)** with 180 reserved for one signature morph.

### 5.2 Reduced motion

`motion-reduce:` appears in 22 files. The distribution shows what "reduce" means here:
`motion-reduce:transition-none` ×21, `motion-reduce:transition-opacity` ×4 (i.e. *keep the
fade, drop the transform*), `motion-reduce:transform-none` ×4,
`motion-reduce:animate-none` ×2. Plus a media block:

```css
@media (prefers-reduced-motion: reduce) {
  .prompt-stash-count-enter { animation: none; }
  [data-slot="skeleton"]::after { content: none; }
}
```
— `apps/web/src/index.css:2167-2174`

And a redundancy rule worth copying — when the motion *is* the affordance, something must
replace it:

> ```css
> /* The pulse is the destination indicator; without it (reduced motion), the
>    focus outline takes over, so exactly one indicator shows at a time. */
> .settings-page-scroll-fade .settings-search-target-pulse:focus { outline: none; }
> ```
> — `apps/web/src/index.css:588-592`

### 5.3 Theme-swap transition suppression

```css
/* Suppress all transitions during theme changes */
.no-transitions,
.no-transitions *,
.no-transitions *::before,
.no-transitions *::after {
  transition-duration: 0s !important;
  animation-duration: 0s !important;
}
```
— `apps/web/src/index.css:1050-1057`

A one-frame class toggled around a theme swap so 200 elements don't each animate their
color independently. Mandatory if JustSearch keeps a theme switcher.

### 5.4 Duty-cycled indicator animations — the performance thesis

The four looping keyframes all use **stepped timing functions with long holds**, and the
`@theme inline` block states why:

```css
/* Duty-cycled indicator animations: long holds with stepped ramps, so the
   compositor updates discrete frames instead of every vsync. */
--animate-status-pulse: status-pulse 2s infinite;
--animate-ghost-pulse: ghost-pulse 2.4s infinite;
--animate-status-ping: status-ping 2s infinite;
--animate-skeleton: skeleton 2s infinite linear;
```
— `apps/web/src/index.css:144-149`

```css
@keyframes skeleton {
  /* Transform-only so the highlight sweep stays on the compositor, then a
     long hold with the band parked off-screen instead of a constant shimmer. */
  0%        { transform: translateX(-100%); }
  60%, 100% { transform: translateX(100%); }
}
@keyframes ghost-pulse {
  /* The loading ghosts' breath. Stepped like the status indicators, so however many bars a
     ghost holds, the compositor draws a handful of discrete frames per cycle rather than one
     per vsync — which on a 120Hz display is the difference between ~14 and ~288 updates. */
  0%,  42% { opacity: 1;    animation-timing-function: steps(4); }
  50%, 92% { opacity: 0.55; animation-timing-function: steps(4); }
  100%     { opacity: 1; }
}
@keyframes status-pulse {
  0%,  40% { opacity: 1;   animation-timing-function: steps(6); }
  50%, 90% { opacity: 0.5; animation-timing-function: steps(6); }
  100%     { opacity: 1; }
}
@keyframes status-ping {
  /* Burst first (immediate feedback for click ripples), then hold
     invisible for the rest of the cycle. Mirrors animate-ping's
     75%-scale start. */
  0%        { opacity: 0.9; scale: 0.75; animation-timing-function: steps(8); }
  40%, 100% { opacity: 0;   scale: 2; }
}
```
— `apps/web/src/index.css:206-264`

**This is the highest-value transferable idea in the whole donor.** A local-first desktop app
sits idle with indicators visible for hours; a naive `animate-pulse` costs a compositor
update every vsync per element. The pattern is: (1) hold at each extreme for ~40% of the
cycle, (2) ramp with `steps(N)` instead of a smooth curve, (3) animate only
`transform`/`opacity`/`scale`. The `ghost-pulse` comment quantifies it: **~14 vs ~288 updates
per cycle at 120Hz.**

### 5.5 View-transition choreography (the composer morph)

This is scoped to mobile, gated on `html[data-mobile-composer-route-transition="true"]`, and
guarded in JS by `mobileViewport && !prefersReducedMotion && document.startViewTransition`
(`apps/web/src/components/chat/draftHeroTransition.ts:52`). Reproduced verbatim:

```css
/* On mobile, morph the expanded hero composer into the compact docked
   composer while it moves; the rest of the app remains visually stationary. */
html[data-mobile-composer-route-transition="true"]::view-transition-old(root),
html[data-mobile-composer-route-transition="true"]::view-transition-new(root) {
  animation: none;
}

html[data-mobile-composer-route-transition="true"]::view-transition-group(t3-mobile-composer) {
  animation-duration: 180ms;
  animation-timing-function: cubic-bezier(0.4, 0, 0.2, 1);
}

html[data-mobile-composer-route-transition="true"]::view-transition-image-pair(t3-mobile-composer) {
  isolation: isolate;
}

html[data-mobile-composer-route-transition="true"]::view-transition-old(t3-mobile-composer) {
  animation: t3-mobile-composer-old 180ms linear both;
  mix-blend-mode: normal;
}

html[data-mobile-composer-route-transition="true"]::view-transition-new(t3-mobile-composer) {
  animation: t3-mobile-composer-new 180ms linear both;
  mix-blend-mode: normal;
}

/* The expanded and compact composers have different internal layouts. Use a
   brief crossfade while their shared wrapper moves so the layout change does
   not read as a single-frame cut. */
@keyframes t3-mobile-composer-old {
  0%,  35%  { opacity: 1; }
  65%, 100% { opacity: 0; }
}

@keyframes t3-mobile-composer-new {
  0%,  35%  { opacity: 0; }
  65%, 100% { opacity: 1; }
}

html[data-mobile-composer-route-transition="true"]::view-transition-group(t3-mobile-draft-headline) {
  animation-duration: 130ms;
}

html[data-mobile-composer-route-transition="true"]::view-transition-old(t3-mobile-draft-headline) {
  animation: t3-mobile-draft-headline-exit 130ms cubic-bezier(0.4, 0, 1, 1) both;
  mix-blend-mode: normal;
}

@keyframes t3-mobile-draft-headline-exit {
  from { opacity: 1; transform: translateY(0); }
  to   { opacity: 0; transform: translateY(-6px); }
}
```
— `apps/web/src/index.css:7-76`

**Three techniques here that generalize directly to a search window:**

1. **Kill the root transition, keep only the named ones.** `::view-transition-old(root)`
   and `-new(root)` get `animation: none`, so the page does *not* crossfade; only the
   elements with a `view-transition-name` move. The result: "the rest of the app remains
   visually stationary." For a search window morphing a hero search field into a docked
   header field, this is exactly the effect you want.
2. **Delayed-crossfade inside a moving group.** The old and new images hold their opacity
   for the first 35%, cross between 35% and 65%, and hold for the last 35%. The *position*
   animates the whole 180ms, but the *content swap* happens only in the middle third — so
   the eye tracks the moving box and never sees a cut.
3. **Sub-elements exit faster than the container moves** — the headline exits in 130ms with
   `cubic-bezier(0.4, 0, 1, 1)` (ease-in, accelerating out) and a 6px upward drift, while
   the composer group takes 180ms. Departing content leaves before the container settles.

`viewTransitionName` is applied in React at `apps/web/src/components/ChatView.tsx:6284,6306`.

### 5.6 Nested-dialog stacking

```
DIALOG_POPUP_CLASS =
  "dialog-glass -translate-y-[calc(1.25rem*var(--nested-dialogs))] … scale-[calc(1-0.1*var(--nested-dialogs))] …
   opacity-[calc(1-0.1*var(--nested-dialogs))] … transition-[scale,opacity,translate] duration-200 ease-in-out
   will-change-transform data-nested:data-ending-style:translate-y-8 data-nested:data-starting-style:translate-y-8
   data-nested-dialog-open:origin-top data-ending-style:scale-98 data-starting-style:scale-98
   data-ending-style:opacity-0 data-starting-style:opacity-0"
DIALOG_BACKDROP_CLASS =
  "dialog-backdrop fixed inset-0 z-50 transition-all duration-200 data-ending-style:opacity-0 data-starting-style:opacity-0"
```
— `apps/web/src/components/ui/dialog-styles.ts:1-8`

A dialog stack driven by one counter variable: each level of nesting pushes the parent
**up 1.25rem, down 10% in scale, down 10% in opacity**, all interpolated over 200ms
`ease-in-out`. Enter/exit is a `scale-98` + `opacity-0` pair — a 2% scale, which is the
smallest scale that still reads as motion.

### 5.7 Banner stack dismissal

```js
const DISMISS_TRANSITION_MS = 220;
const frontExitStyle   = { opacity: 0, transform: "translate3d(0, 4rem, 0)" };
const stackedExitStyle = { opacity: 0, transform: "translate3d(0, 7rem, 0)" };
const restingStyle     = { opacity: 1, transform: "none" };
const exitTransitionStyle = {
  transition: `transform ${DISMISS_TRANSITION_MS}ms ease-in, opacity ${DISMISS_TRANSITION_MS}ms ease-in`,
};
```
— `apps/web/src/components/chat/ComposerBannerStack.tsx:8-22`

Front banner exits 4rem downward; a *stacked* banner exits 7rem — further, because it starts
higher. `translate3d` forces a compositor layer. Exit is `ease-in`, matching §5.1.

The stack **expands on hover** via a grid-rows trick (the standard "animate to auto height"
workaround):

```
"relative z-20 grid grid-rows-[0fr] transition-[grid-template-rows] duration-150 ease-out",
"group-hover/banner-stack:grid-rows-[1fr] group-focus-within/banner-stack:grid-rows-[1fr]",
```
— `ComposerBannerStack.tsx:141-142`

…with contents doing `translate-y-1 → 0` + `opacity-0 → 100` over 150ms `ease-out`, and
`will-change-[opacity,transform]` (`:149`). A collapsed "stack cap" (a 3px-tall, 96%-wide
rounded lip peeking above the front banner) fades out on hover (`:110-118`) — the visual
promise that more is underneath.

Note `group-focus-within` mirrors `group-hover` in every rule — hover-reveal is always
keyboard-reachable.

### 5.8 Height animation

```jsx
<div className="transition-[height] duration-200 ease-out motion-reduce:transition-none"
     style={{ height: heightState.height, overflow: heightState.isClipping ? "hidden" : "visible" }}
     onTransitionEnd={…} />
```
— `apps/web/src/components/AnimatedHeight.tsx:72-88`

A `ResizeObserver` + double-`requestAnimationFrame` measures content, then height animates
200ms `ease-out`. Crucially, **`overflow: hidden` is applied only *during* the transition**
and cleared on `transitionend` — so popovers/tooltips inside the region aren't clipped at
rest. Common bug, cleanly solved.

### 5.9 The composer-context compaction morph (the signature move)

```
"block w-full min-w-0 max-w-[240px] origin-left truncate
 transition-[opacity,transform] duration-180 ease-[cubic-bezier(0.32,0.72,0,1)]
 group-data-[compact]/composer-context:[transform:translateX(-0.25rem)_scaleX(0.95)]
 group-data-[compact]/composer-context:opacity-0
 motion-reduce:transform-none motion-reduce:transition-opacity"
```
— `apps/web/src/components/BranchToolbarEnvironmentSelector.tsx:66` (identical at
`BranchToolbarBranchSelector.tsx:761`, `BranchToolbarEnvModeSelector.tsx:105`)

When the composer compacts, toolbar *labels* collapse: `origin-left`, slide 4px left, squash
horizontally to 95%, fade to 0 — over 180ms with a snappy custom bezier. The icons stay. This
is the "text label evaporates leftward into its icon" effect. Under reduced motion it
degrades to a pure opacity fade.

### 5.10 Micro-interactions

| Element | Motion | Source |
|---|---|---|
| Send / stop button | `transition-all duration-150`, `hover:scale-105`, `active:` swaps inset highlight `white/16%` → `black/8%` and drops the shadow | `chat/ComposerPrimaryActions.tsx:88,221` |
| Slider thumb | `transform 120ms ease, box-shadow 120ms ease`; `hover: scale(1.08)`, `active: scale(0.94)` | `index.css:918-960` |
| Color swatch | `transition-transform duration-200 active:scale-90` | `components/color-selector.tsx:78` |
| Scrollbar thumb | `background-color 120ms ease-out` | `index.css:1730` |
| Message action bar | `opacity-0 → 100` on `group-hover` / `focus-within`, `duration-200` | `chat/MessagesTimeline.tsx:1043,1131` |
| Chevron | `transition-transform duration-200` | `chat/MessagesTimeline.tsx:1408,2319` |
| Preview thumbnail | `transition duration-200 group-hover:scale-[1.03]` | `chat/ComposerPreviewAnnotationCards.tsx:62` |
| Tooltip enter/exit | `data-starting-style:scale-98 opacity-0` → rest; `data-instant:duration-0` for tooltip-to-tooltip moves | `ui/tooltip.tsx:44` |
| Sidebar collapse | `transition-[width] duration-200 ease-linear`; main pane `transition-[padding-left] duration-200 ease-linear` | `ui/sidebar.tsx:282,293`; `ChatView.tsx:6137` |
| Prompt-stash count | one-shot `180ms ease-out` fade-up from `translateY(2px)`, remounted by key | `index.css:2149-2165` |
| Provider-update pill | `scaleX(1) → scaleX(0)` `linear forwards` over `--provider-update-pill-dismiss-ms` — a countdown bar | `index.css:2176-2187` |
| Settings search target | `box-shadow 0 0 0 2px primary/70%` pulse, `650ms ease-in-out`, **iteration count 2** | `index.css:572-586` |

The `active:` states on the send button are worth singling out: pressing doesn't just scale —
it **flips the inset highlight from top-light to top-dark and removes the drop shadow**, i.e.
the button physically presses into the surface. Three simultaneous cues, 150ms.

### 5.11 Scroll fades (mask, not gradient overlay)

```css
/* Fade rows themselves as they pass beneath the top chrome. A mask remains
   visible even when the header and timeline share the same background. */
.chat-timeline-scroll-fade,
.settings-page-scroll-fade,
.pull-requests-scroll-fade {
  --topbar-scroll-fade-height: 2.5rem;
  mask-image:
    linear-gradient(to bottom, transparent 0%, rgb(0 0 0 / 10%) 10%, rgb(0 0 0 / 30%) 24%,
      rgb(0 0 0 / 58%) 42%, rgb(0 0 0 / 82%) 62%, rgb(0 0 0 / 96%) 82%, black 100%),
    linear-gradient(black, black), linear-gradient(black, black);
  mask-position: top, bottom, right;
  mask-repeat: no-repeat;
  mask-size:
    100% var(--topbar-scroll-fade-height),
    100% calc(100% - var(--topbar-scroll-fade-height)),
    var(--app-scrollbar-width) 100%;
}
```
— `apps/web/src/index.css:521-563` (webkit-prefixed twin at `:527-544`)

**Mask, not an overlay gradient.** The comment names the reason: an overlay only works when
the chrome and content share a background; a mask works always. Height is 2.5rem, bumped to
3rem at `≥40rem` (`index.css:996-1000`), and cut to 1.5rem for the PR list with its own
rationale (`index.css:565-570`). The seven-stop non-linear ramp (10/24/42/62/82/100 at
10/30/58/82/96% black) is an eased fade, not a linear one — and the third mask layer keeps
the scrollbar column at full opacity (§1.5).

---

## 6. Component anatomy

### 6.1 Sidebar session row

**One surface model for all row states**, with the reasoning preserved:

```js
// All sidebar rows share one surface model. Live threads used to look
// like elevated cards while settled threads were plain rows, leaving neither
// a useful hierarchy nor a reliable hover cue. Status now lives in the row
// content; surface is reserved for interaction (hover, multi-select, route).
const rowSurfaceClassName = cn(
  "group/sidebar-row relative w-full cursor-pointer overflow-hidden rounded-md text-left outline-none select-none",
  props.isActive
    ? "bg-sidebar-row-active text-sidebar-foreground"
    : isSelected
      ? "bg-sidebar-row-selected text-sidebar-foreground"
      : shouldRecede
        ? "text-sidebar-muted-foreground/75 hover:bg-sidebar-row-hover hover:text-sidebar-foreground"
        : "bg-transparent text-sidebar-foreground hover:bg-sidebar-row-hover",
  isInFlight && !props.isActive && !isSelected && "opacity-70 transition-opacity hover:opacity-100",
);
```
— `apps/web/src/components/Sidebar.tsx:1023-1040`

**That comment is the single most important design lesson in the donor**: *surface encodes
interaction state; content encodes status.* They tried the other way (elevated cards for
live threads) and it produced neither hierarchy nor a hover cue.

Five surface states, in precedence order: **active** (`--sidebar-row-active`) > **selected**
(`--sidebar-row-selected`) > **receded** (dimmed text, hover fill) > **rest** (transparent) —
plus an orthogonal **in-flight** 70% opacity that lifts to 100% on hover.

Two row variants:

| | slim | card |
|---|---|---|
| Height | `h-9` (36px) | `h-[4.875rem]` (78px) |
| Padding | `gap-2.5 px-2.5` | `px-[--sidebar-row-content-inset] py-[--sidebar-content-inset]` |
| Radius | `rounded-md` (8px) | `rounded-md` |
| Virtualization hint | `[content-visibility:auto] [contain-intrinsic-size:auto_34px]` | `…auto_96px` + `py-0.5` |
| Contents | favicon · title · terminal icon · PR badge · time/action slot | header row (favicon · project · pin · status slot) + body |

— `apps/web/src/components/Sidebar.tsx:1117-1131, 1255-1287`

`content-visibility: auto` with `contain-intrinsic-size` is how a long session list stays
cheap — worth adopting directly for a result list.

**Title treatment** (`Sidebar.tsx:1056-1083`) — a five-way text-color ladder in *one* element:
`text-sm`, weight flips `font-medium` → `font-normal` when receded, and color goes
`text-foreground` (unread/woke) → `text-foreground/90` (normal) → `text-foreground/95`
(failed) → `text-secondary-label` (receded) → `text-secondary-label/70` (slim at rest, with
`group-hover/sidebar-row:text-foreground`). Emphasis is carried by **opacity of the foreground
color, not by a different hue**.

**Favicon recede** — a lovely detail:

```jsx
{/* Settled history recedes: dimmed favicon at rest, restored on
  hover so the tail stays scannable when you're hunting. */}
"shrink-0 transition-opacity",
!props.isActive && "opacity-40 grayscale group-hover/sidebar-row:opacity-100 group-hover/sidebar-row:grayscale-0"
```
— `apps/web/src/components/Sidebar.tsx:1139-1146`

`grayscale` + 40% opacity at rest, full color on hover. The colored favicons stop competing
for attention in the settled tail but come back the moment you scan it.

**The status/action slot swap** — the core density trick:

```jsx
{/* The visible state owns this slot's width: status at rest,
    actions on hover/keyboard focus or while the popover is open. Keeping
    the hidden state out of flow lets the project label reclaim
    space without either state overlapping it. */}
<span className="group/sidebar-status-slot relative ml-auto flex h-5 min-w-8 shrink-0 items-stretch justify-end text-xs">
```
— `apps/web/src/components/Sidebar.tsx:1325-1329`

The at-rest status label goes `position: absolute; right: 0; opacity: 0` on
`group-hover/sidebar-row` **and** on `group-has-[:focus-visible]/sidebar-status-slot`
**and** while the snooze menu is open (`:1337-1339`). Taking the hidden state out of flow is
what lets the title reclaim the width. `min-w-8` reserves a floor so rows don't jitter.

A counterpoint that shows the rule has exceptions:

```jsx
{/* The PR badge stays outside the hover-fading slot: it must
  remain visible AND clickable while the row is hovered. Only
  the time/jump label yields to the settle affordance. */}
```
— `apps/web/src/components/Sidebar.tsx:1163-1165`

### 6.2 Status indicators — the color budget

**Connection dots: exactly three colors plus a null state.**

```js
export function connectionPhaseDotClassName(phase) {
  switch (phase) {
    case "connected":                  return "bg-success";
    case "connecting": case "reconnecting": return "bg-warning";
    case "error":                      return "bg-destructive";
    default:                           return "bg-muted-foreground/40";
  }
}
/** Ping halo for transitional phases; null renders no ping. */
export function connectionPhasePingClassName(phase) {
  return phase === "connecting" || phase === "reconnecting" ? "bg-warning/60 duration-2000" : null;
}
```
— `apps/web/src/components/ConnectionStatusDot.tsx:6-24`

Geometry: `size-2` (8px) dot centred in a `size-3` (12px) box, `rounded-full`; the ping is an
absolutely-positioned full-size sibling running `animate-status-ping` at 60% alpha over 2s
(`ConnectionStatusDot.tsx:42-53`). **Motion is reserved for the transitional state only** —
steady states are static dots. Tooltip trigger uses `cursor-help`.

**PR state colors are a *separate, decorative* 3-set** — emerald (open), violet (merged), red
(closed) — with paired light/dark values and a hover-only variant:

```js
case "open":   return "text-emerald-600 dark:text-emerald-300/90";
case "merged": return "text-violet-600 dark:text-violet-300/90";
case "closed": return "text-red-600 dark:text-red-300/90";
```
— `apps/web/src/components/ThreadStatusIndicators.tsx:66-91`; hover variants at `:38-46`

Note dark values are `-300` **at 90% alpha** while light values are `-600` at full — dark-mode
accents need to be lighter *and* softer.

Other row-level status hues use direct Tailwind pairs, never semantic tokens:
sky-600/sky-400, amber-700/amber-300, indigo-600/indigo-300, red-700/red-300,
emerald-700/emerald-300 (`Sidebar.tsx:802-841`). **Semantic tokens for system health;
raw palette for domain taxonomy.** Deliberate separation.

### 6.3 Buttons

Base (`apps/web/src/components/ui/button.tsx:11`), key parts:

```
[--control-icon-color:currentColor] [&_svg]:-mx-0.5 relative inline-flex shrink-0 cursor-pointer
items-center justify-center gap-2 whitespace-nowrap rounded-[var(--control-radius)] border
font-medium text-base outline-none transition-shadow
before:pointer-events-none before:absolute before:inset-0 before:rounded-[calc(var(--control-radius)-1px)]
pointer-coarse:after:absolute pointer-coarse:after:size-full pointer-coarse:after:min-h-11 pointer-coarse:after:min-w-11
focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-background
disabled:pointer-events-none disabled:opacity-64
sm:text-sm
[&_svg:not([class*='size-'])]:size-4.5 sm:[&_svg:not([class*='size-'])]:size-4
```

Size ladder (`button.tsx:18-31`) — **every row is `mobile → sm:desktop`, 4px smaller on desktop**:

| size | mobile | desktop | padding |
|---|---|---|---|
| `xs` | `h-7` | `h-6` | `px-[calc(--spacing(2)-1px)]`, `gap-1`, `text-sm sm:text-xs` |
| `sm` | `h-8` | `h-7` | `px-[calc(--spacing(2.5)-1px)]`, `gap-1.5` |
| `default` | `h-9` | `h-8` | `px-[calc(--spacing(3)-1px)]` |
| `lg` | `h-10` | `h-9` | `px-[calc(--spacing(3.5)-1px)]` |
| `xl` | `h-11` | `h-10` | `px-[calc(--spacing(4)-1px)]`, `text-lg sm:text-base` |
| `icon-xs`…`icon-xl` | `size-7`…`size-11` | `size-6`…`size-10` | — |

Four techniques to steal:

1. **`px-[calc(--spacing(3)-1px)]`** — padding is reduced by exactly the 1px border, so the
   *visual* inset matches the spacing scale rather than exceeding it by the border. Applied
   uniformly across every size.
2. **`pointer-coarse:after:min-h-11 min-w-11`** — on touch devices an invisible pseudo-element
   expands the hit target to 44px **without changing layout**. A 24px icon button stays 24px
   visually and is 44px to a finger.
3. **`[&_svg]:-mx-0.5`** — icons get a −2px horizontal margin so optical icon spacing matches
   text spacing.
4. **`[--control-icon-color]` as a per-variant variable** — ghost/outline variants set
   `[--control-icon-color:var(--muted-foreground)]` so icons dim while the label stays full
   contrast, with a single-token override point.

`transition-shadow` only — buttons never transition color, so hover fills are instant while
the elevation change eases.

### 6.4 Inputs

```
h-8.5 w-full min-w-0 rounded-[inherit] px-[calc(--spacing(3)-1px)] leading-8.5 outline-none
placeholder:text-placeholder sm:h-7.5 sm:leading-7.5
[transition:background-color_5000000s_ease-in-out_0s]
```
— `apps/web/src/components/ui/input.tsx:22` (sizes `sm`/`lg` at `:23-24`)

`leading-*` always equals `h-*` — single-line vertical centering without flex. The absurd
`5000000s` background-color transition is the standard trick to **suppress Chrome's yellow
autofill background** (it never reaches the target).

Wrapper (`input.tsx:61`) carries the visual: `rounded-lg border border-input bg-background`,
`shadow-xs/5`, `ring-ring/24`, and a `has-*` state machine — `has-focus-visible:border-ring
has-focus-visible:ring-[3px]`, `has-aria-invalid:border-destructive/36`,
`has-focus-visible:has-aria-invalid:ring-destructive/16`, `has-autofill:bg-foreground/4`,
`has-disabled:opacity-64`. All state lives on the parent via `:has()`, so the input element
itself stays unstyled.

### 6.5 Tooltip

```
relative flex h-(--popup-height,auto) w-(--popup-width,auto) origin-(--transform-origin)
text-balance rounded-md text-popover-foreground text-xs
transition-[width,height,scale,opacity]
before:pointer-events-none before:absolute before:inset-0 before:rounded-[calc(var(--radius-md)-1px)]
before:shadow-[0_1px_--theme(--color-black/4%)]
data-ending-style:scale-98 data-starting-style:scale-98
data-ending-style:opacity-0 data-starting-style:opacity-0
data-instant:duration-0
dark:before:shadow-[0_-1px_--theme(--color-white/6%)]
```
— `apps/web/src/components/ui/tooltip.tsx:44`

Default variant: `border bg-popover not-dark:bg-clip-padding shadow-md/5`.
Glass variant: `dropdown-glass shadow-xl shadow-black/25 before:hidden` (`:45-47`).
Viewport padding: `px-(--viewport-inline-padding) py-1` with
`[--viewport-inline-padding:--spacing(2)]` (8px) (`:53`). `text-xs`, `z-70`,
`pointer-events-none` on the positioner.

Two refined details: (a) **`transition-[width,height,…]`** means a tooltip that changes
content *resizes smoothly* rather than snapping — with the outgoing text truncating
(`**:data-previous:truncate`) while it crossfades; (b) **`data-instant:duration-0`** — moving
between adjacent tooltip triggers is instant, only the first appearance animates.

### 6.6 Empty state

```
flex min-w-0 flex-1 flex-col items-center justify-center gap-6 text-balance p-6 text-center md:p-12
```
Header: `flex max-w-sm flex-col items-center text-center` (24rem measure).
— `apps/web/src/components/ui/empty.tsx:5-25`

The `icon` media variant is a **fanned three-card stack**: a `size-9 rounded-md border bg-card`
tile, with two `aria-hidden` copies behind it at `scale-84` and `±rotate-10`, origins
`bottom-left` / `bottom-right`, offset `∓translate-x-0.5`, `shadow-none`
(`empty.tsx:28-72`). Cheap, memorable, no illustration asset needed.

### 6.7 Composer glass shell (the most elaborate component in the file)

Three stacked pseudo-elements:

- `.chat-composer-glass-shell::before` — the glass fill, `border-radius: 22px`, z-index 0
  (`index.css:634-648`)
- `.chat-composer-glass-host` — `box-shadow: 0 12px 28px -18px rgb(0 0 0 / 40%)`
  (`index.css:690-693`)
- `.chat-composer-glass-host::after` — the 1px outline ring, `z-index: 1`,
  `border-radius: inherit` (`index.css:695-703`)

Dark mode swaps material entirely (`index.css:737-749`): surface becomes
`mix(background 96%, white)`, outline becomes `white/5%`, the drop shadow is **removed**, and
an `inset 0 1px white/3%` highlight replaces it.

When an attachment strip is docked below, a **CSS `shape()` clip-path** welds a 22px-radius
composer to a 16px-radius strip as one continuous glass sheet
(`index.css:654-681`), with the outline ring clipped open at the seam via a polygon
(`index.css:705-717`), and a `@supports not (clip-path: shape(…))` fallback that separates
them into two independently-blurred boxes (`index.css:764-792`).

```css
/* Start the strip outline exactly where the composer's 22px curve reaches its tangent. */
-webkit-mask-image: linear-gradient(to bottom, transparent 0 1rem, black 1rem);
```
— `apps/web/src/index.css:730-732`

This is far more than a search window needs — but the **pattern** is worth naming: *one
continuous material with a clip-path silhouette, rather than two boxes that must be made to
look joined.*

### 6.8 Global backdrop-filter fallback

```css
@supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  .chat-composer-glass, .alert-glass       { background: var(--background) !important; }
  .chat-composer-glass-shell::before       { background: var(--chat-composer-glass-surface); }
  .dialog-glass, .dropdown-glass           { background: var(--popover) !important; }
}
```
— `apps/web/src/index.css:1008-1022`

One block makes every glass surface opaque where blur is unsupported. Mandatory companion to
any glass system.

---

## 7. Density decisions

**Icon-only vs labelled.** Icons alone: sidebar row actions, titlebar controls
(`--workspace-titlebar-control-size: 1.75rem` = 28px), pin, terminal-running, chevrons.
Icon+label: sidebar navigation rows, menu items, buttons. Label-only: PR number badge
(`#1234`), timestamps, group headers. Icon sizes are a tight ladder — `size-3` (12px) for
in-row markers, `size-3.5` (14px) for status glyphs, `size-4` (16px) for the standard row
icon and `size-4.5` (18px) for the mobile button icon.

**Hidden until hover/focus** (`opacity-0 … group-hover:opacity-100 focus-within:opacity-100`):
message action bars (`chat/MessagesTimeline.tsx:790,1043,1131`), sidebar row settle/snooze
actions (`Sidebar.tsx:1207-1210`), banner stack overflow (`ComposerBannerStack.tsx:141-152`),
timestamps yielding to actions (`Sidebar.tsx:1169-1172`). **Every single hover-reveal also
listens to `focus-within` or `focus-visible`** — no exceptions found. Two of them additionally
stay revealed while a related popover is open (`snoozeMenuOpen`, `Sidebar.tsx:1339`), which is
the detail that stops the row from collapsing under an open menu.

**Row height vs content.**
- Sidebar slim row 36px for: favicon(16) + title(14px) + optional terminal icon + PR badge + timestamp. Five information units in 36px.
- Sidebar card row 78px = a 20px header line (favicon + project + pin + status) plus body.
- Command palette item: `py-1.5` (12px total) on 14px text ≈ 26px.
- Menu-button default: 32px.
- Topbar: 52px.
- Subheader: 40px.

**Measure discipline.** Content max-widths are explicit and few:

| Width | Count | Use |
|---|---|---|
| `max-w-xl` (36rem/576px) | 18 | command palette popup, dialogs |
| `max-w-md` (28rem) | 10 | |
| `max-w-80` (20rem) | 10 | tooltips (`ConnectionStatusDot.tsx:73`), sidebar popovers |
| `max-w-3xl` (48rem/768px) | 7 | **transcript / banner stack column** (`ComposerBannerStack.tsx:100`) |
| `max-w-sm` (24rem) | 4 | empty-state header |
| `max-w-[240px]` | 8 | truncating toolbar labels |
| `max-w-[min(440px,calc(100vw-2rem))]` | 4 | dialogs — always with a viewport clamp |

The reading column is **48rem**; controls cap at **36rem**; tooltips at **20rem**; empty-state
copy at **24rem**. Every fixed-px max-width is wrapped in `min(…, calc(100vw - 2rem))`.

**Truncation is the default, wrapping is the exception.** `truncate` on every row title,
`[&>span:last-child]:truncate` baked into the sidebar menu-button base
(`ui/sidebar.tsx:798`). Markdown tables truncate cells at `max-w-24rem` and only wrap when
explicitly expanded (`index.css:2127-2140`) — with the reason stated: "single-line cells
truncate so arbitrary chat content cannot blow the column out."

**Tabular numerals everywhere numbers change in place** — `tabular-nums` on PR numbers,
timestamps, countdowns, token counts, with the reason inline:

> ```
> // Sidebar chrome follows the interface font; tabular digits keep the
> // number from reflowing as PR states stream in.
> ```
> — `apps/web/src/components/Sidebar.tsx:1092-1093`

**Virtualization hints instead of a virtualizer**: `[content-visibility:auto]` +
`[contain-intrinsic-size:auto_34px]` / `auto_96px` on list items
(`Sidebar.tsx:1121,1266`) — the browser skips rendering off-screen rows while keeping
scrollbar geometry correct, with no library.

---

## 8. Lit translation notes

JustSearch is Lit + CSS custom properties + shadow DOM. Mapping:

### 8.1 Ports as-is (copy the text)

- **Every `:root` / `.dark` token block** (§1.2, §1.4, §1.3 radius ladder). Custom properties
  **inherit through shadow boundaries**, so a global `:root` declaration reaches every
  component's shadow root without any bridging. T3 Code already relies on exactly this for a
  third-party shadow-DOM library, and says so:

  > ```
  > /* @pierre/diffs surfaces … render inside shadow roots but consult these hooks with their own
  >    literal stacks as fallback. Custom properties inherit across the shadow boundary, so
  >    defining them once here routes every code surface through the appearance font tokens. */
  > :root { --diffs-font-family: var(--font-mono); --diffs-header-font-family: var(--font-sans); }
  > ```
  > — `apps/web/src/index.css:1649-1657`

  That is a **verified-in-production statement of the exact mechanism JustSearch needs**.
- **All `@keyframes`** — `skeleton`, `ghost-pulse`, `status-pulse`, `status-ping`,
  `prompt-stash-count-enter`, `provider-update-pill-countdown`,
  `settings-search-target-pulse`. Note: keyframes are **not** inherited into shadow roots —
  they must be declared *inside each shadow root's* stylesheet, or the animated element must
  live in the light DOM. Practical answer: put the `@keyframes` in a shared
  `CSSStyleSheet` and adopt it in every component (see 8.3).
- **All view-transition CSS** (§5.5) — `::view-transition-*` pseudo-elements are
  document-level and unaffected by shadow DOM. `view-transition-name` set on a shadow-DOM
  element still participates.
- **Scrollbar rules, drag-region rules, safe-area calcs, the grain data-URI,
  `.no-transitions`, the `@supports not (backdrop-filter)` fallback** — all
  document-level or inheritable.
- **`env(titlebar-area-*)` geometry** conceptually ports to Tauri, but the values do not — see §9.

### 8.2 Needs re-expression (Tailwind utility → component CSS)

| T3 Code pattern | Lit equivalent |
|---|---|
| `cva()` variant maps (button/badge/sidebar-row sizes) | `static styles` with `:host([size="sm"])`, `:host([variant="ghost"])` attribute selectors. The *values* in §6.3's table transfer 1:1. |
| `group/name` + `group-hover/name:` | shadow DOM makes this **easier**: `:host(:hover) .child` works within a component. Cross-component grouping needs an explicit attribute reflected by the parent (`<result-row hovered>`), or `::part()`. |
| `data-[active=true]:bg-…` | `:host([active])` — reflect the property. |
| `has-focus-visible:`, `has-aria-invalid:` (§6.4) | `:host(:has(:focus-visible))` works inside a shadow root over its own content. |
| `sm:` responsive pairs (§2.4, §6.3) | container queries (`@container`) are the better fit for a resizable panel — T3 Code already uses one (`@container sidebar-header (min-width: 15.75rem)`, `index.css:347`). |
| `--spacing(3)` function, `--alpha(… / 4%)`, `--theme(--color-black/4%)` | Tailwind-v4-only. Replace with literal values or `color-mix(in srgb, #fff 4%, transparent)`. **`--alpha()` and `--theme()` are the two things that will silently not work if pasted.** |
| `@utility pt-safe` etc. | plain classes or `:host` padding using the same `max(env(...), 0px)`. |
| `text-balance`, `tabular-nums`, `truncate` | `text-wrap: balance`, `font-variant-numeric: tabular-nums`, the three-line ellipsis idiom. |

### 8.3 Shadow-DOM gotchas, concretely

1. **`@custom-variant dark (&:is(.dark, .dark *))` will not pierce shadow roots.** The
   `.dark` class sits on `<html>`; a selector *inside* a shadow root cannot see an ancestor's
   class. Three working substitutes, best first:
   - **Preferred: don't scope by selector at all.** Because custom properties inherit, put
     the entire dark palette on `:root.dark` in the document stylesheet (exactly as
     §1.4 does) and have components reference only `var(--foreground)` etc. Components then
     need **no dark-mode CSS whatsoever**. This is the architecture T3 Code already has —
     the `dark:` utilities that remain are the exceptions (§4.3 shadow inversions), and each
     of those can be expressed as a token instead (e.g. `--elevation-composer-shadow`).
   - For the genuine exceptions: `:host-context(.dark)` — works in Chromium (and therefore
     in Tauri's WebView2 on Windows), but is **not implemented in Firefox/Safari**. Acceptable
     for a Tauri-only target; note the constraint.
   - Portable fallback: reflect the theme onto each host (`<x-el theme="dark">`) or use
     `@media (prefers-color-scheme: dark)` inside the shadow root, which *does* work but
     ignores an explicit user toggle.

   **Recommendation for JustSearch: convert every remaining `dark:` rule into a token.**
   The four `.dark` geometry overrides (`--glass-blur`, `--glass-saturation`, the two
   scrollbar thumbs — `index.css:107-112`) already demonstrate the technique; extend it to
   the shadow inversions in §4.3 and the dark-mode-only inset highlights, and the
   `:host-context` problem disappears entirely.

2. **`@keyframes` do not inherit into shadow roots.** Declare them in a shared adopted
   stylesheet. `animation-name` referencing an undefined keyframe fails silently — this will
   look like "the pulse just doesn't run."

3. **`::-webkit-scrollbar` inside a shadow root** must be declared inside that root; the
   document rule at `index.css:1673` won't reach a scrollable element in a shadow tree. Use
   `--app-scrollbar-*` tokens (which *do* inherit) plus a small mixin adopted per component.

4. **`backdrop-filter` creates a containing block and a stacking context.** Combined with
   shadow roots, a glass panel will clip `position: fixed` descendants. T3 Code sidesteps this
   with portals; Lit needs either a top-layer `<dialog>`/popover or a document-level overlay
   host.

5. **`:host` cannot be styled by the parent's descendant selectors**, so the "parent sets a
   variable, child reads it" pattern (`--control-icon-color`, `--alert-glass-tint`,
   `--chat-composer-glass-surface`) is *more* natural in Lit than in React. Lean into it —
   §4.2's `--alert-glass-tint` and §6.3's `--control-icon-color` are the model.

6. **`content-visibility: auto` + `contain-intrinsic-size`** work fine on shadow hosts and are
   the cheapest result-list virtualization available (§7).

### 8.4 What JustSearch should improve on, not copy

- **Z-layers (§1.6).** T3 Code has no z-scale. Define one (`--z-content`, `--z-sticky`,
  `--z-overlay`, `--z-dialog`, `--z-tooltip`, `--z-toast`) before the first overlay ships.
- **The `--spacing()` function.** Without Tailwind, adopt an explicit 4px-step spacing token
  ladder rather than inlining `calc()`.
- **The 1px-border padding compensation (§6.3)** deserves a token
  (`--control-pad-3: calc(0.75rem - 1px)`) rather than being re-derived per size.

---

## 9. What not to copy

**Brand identity.**
- Product names and wordmarks: `APP_BASE_NAME = "T3 Code"`, `APP_STAGE_LABEL`,
  `APP_DISPLAY_NAME` (`apps/web/src/branding.ts:22-27`); `t3-` CSS class prefixes
  (`.t3-ghostty-*`, `t3-mobile-composer`); the `data-theme-id="t3-chat"` branded preset.
- **The `.sidebar-brand` / `.sidebar-stage-backdrop` "stage art" system**
  (`index.css:334-511`) — ~180 lines of seven-pigment `--stage-art-*` palettes across five
  named maintainer themes (`t3-chat`, `grove`, `ocean`, `ember`, `iris`), each with a
  light/dark and a "night" variant. This is T3's decorative sidebar-header illustration and
  its identity. **The one transferable idea** is the fade mechanism — a mask plus an `::after`
  gradient that ramps to a *panel-local* `--sidebar-stage-fade` rather than the global chrome
  background, with the reason stated at `index.css:1239-1242` ("or the fade shows a seam").
  Take the technique; leave the pigments.
- **The T3 Chat grain tile calibration** (`index.css:1620-1626`) is explicitly reverse-
  engineered from T3's live product texture. Use the generic 256px/3.5% version at
  `index.css:1615-1618`, not the branded one.
- **`.ultrathink-*`** (`index.css:2221-2306`) — an animated rainbow-spectrum border/pill/text
  treatment, 10s infinite hue rotation. Both a brand flourish and a violation of the
  duty-cycling principle in §5.4 (it is the one continuously-animating element in the app).
- **`--primary: oklch(0.488 0.217 264)`** is T3's brand blue. Keep the *structure* (one
  primary, `--ring: var(--primary)`, `--update: var(--primary)`); pick your own hue.

**Fonts.** Nothing to avoid — there is no proprietary font (§2.1). Both bundled faces are MIT.
The only caution is not to copy the *terminal* font machinery, which JustSearch has no use for.

**Electron-specific chrome (irrelevant or wrong under Tauri).**
- `-webkit-app-region: drag / no-drag` (`index.css:1659-1670`) — Tauri uses
  `data-tauri-drag-region` instead. The *policy* (opt every interactive tag out
  automatically) ports; the property does not.
- `env(titlebar-area-x/y/width/height)` and the whole `.wco` variant
  (`index.css:121-131`) — these come from the Electron/Chromium **Window Controls Overlay**
  API. Tauri's WebView2 does not expose them by default. Keep the *token indirection*
  (§3.4 — components read `--workspace-controls-right`, never `env()` directly), and feed
  those tokens from Tauri window metrics.
- `MACOS_TRAFFIC_LIGHTS_LEFT_INSET = "90px"` and the fullscreen listener
  (`AppSidebarLayout.tsx:44,165-169`) — macOS-only; JustSearch is Windows-first.
- `.electron-windows { --desktop-window-right-resize-inset: 6px; }`
  (`index.css:1604-1606`) — the *problem* (OS resize grip unreachable in a frameless window)
  may well recur under Tauri; verify empirically rather than porting the 6px.
- `window.desktopBridge?.*` — Electron preload API surface.

**Code-agent-domain-specific.**
- Everything under `.chat-markdown` (`index.css:1803-2147`) — a full markdown renderer:
  footnotes, GitHub task lists, Shiki bridging, code-block chrome, table expand/collapse.
  A search window renders snippets, not markdown documents. **Exception worth lifting:**
  the table truncate/expand rule (`index.css:2127-2140`) and the "restore word-boundary
  wrapping so the min column width is the longest word" note at `:2096-2100`.
- `.diff-*` / `diffs-container` / `--diffs-*` bridging (`index.css:2189-2219`).
- `.t3-ghostty-*` terminal canvas and its custom scrollbar (`index.css:1708-1736`) —
  though the scrollbar itself (`120ms ease-out` thumb color, `top/bottom: 4px` inset) is a
  clean, portable custom-scrollbar implementation if JustSearch ever needs one outside
  `::-webkit-scrollbar`.
- Model picker, provider update pills, PR/branch/worktree status taxonomy, terminal-process
  indicators, `reasoning-effort` select — domain furniture.
- The composer's `clip-path: shape()` welded-strip geometry (§6.7) — extraordinary
  craftsmanship, but it exists to attach a file-attachment tray to a chat composer. Note the
  *technique* (§6.7 closing note); don't port the 30-line path.

---

## Appendix — quick citation index

| Topic | File:line |
|---|---|
| License | `LICENSE:1-3` |
| Dark + WCO custom variants | `apps/web/src/index.css:3-5` |
| Mobile composer view transitions | `apps/web/src/index.css:7-76` |
| Geometry tokens | `apps/web/src/index.css:78-112` |
| WCO token remap | `apps/web/src/index.css:121-131` |
| Font tokens (`@theme`) | `apps/web/src/index.css:133-140` |
| Tailwind bridge (`@theme inline`) | `apps/web/src/index.css:142-205` |
| Duty-cycled keyframes | `apps/web/src/index.css:206-264` |
| Components layer | `apps/web/src/index.css:333-1023` |
| Stage art (brand — skip) | `apps/web/src/index.css:334-511` |
| `.workspace-topbar` | `apps/web/src/index.css:513-519` |
| Scroll-fade masks | `apps/web/src/index.css:521-570` |
| Composer glass | `apps/web/src/index.css:620-792` |
| Alert / dialog / dropdown glass | `apps/web/src/index.css:794-880` |
| Slider | `apps/web/src/index.css:881-994` |
| backdrop-filter fallback | `apps/web/src/index.css:1008-1022` |
| Safe-area utilities | `apps/web/src/index.css:1025-1048` |
| `.no-transitions` | `apps/web/src/index.css:1050-1057` |
| **Semantic color tokens (light + dark)** | `apps/web/src/index.css:1059-1192` |
| Sidebar palette overrides | `apps/web/src/index.css:1194-1243` |
| Theme-id role mapping | `apps/web/src/index.css:1245-1317` |
| Body / `#root` shell rules | `apps/web/src/index.css:1573-1606` |
| Grain | `apps/web/src/index.css:1608-1632` |
| Shadow-DOM custom-property note | `apps/web/src/index.css:1649-1657` |
| Drag region | `apps/web/src/index.css:1659-1670` |
| Scrollbars | `apps/web/src/index.css:1672-1688` |
| Composer font + mobile floor | `apps/web/src/index.css:1690-1706` |
| chat-markdown (skip) | `apps/web/src/index.css:1803-2147` |
| Reduced motion | `apps/web/src/index.css:2167-2174` |
| Font stacks + size application | `apps/web/src/appearanceFonts.ts:21-26,92-120` |
| Font size bounds | `packages/contracts/src/settings.ts:71-93` |
| Button variants | `apps/web/src/components/ui/button.tsx:10-31` |
| Badge variants | `apps/web/src/components/ui/badge.tsx:9-21` |
| Input | `apps/web/src/components/ui/input.tsx:22-61` |
| Tooltip | `apps/web/src/components/ui/tooltip.tsx:36-56` |
| Empty state | `apps/web/src/components/ui/empty.tsx:5-72` |
| Dialog motion | `apps/web/src/components/ui/dialog-styles.ts:1-8` |
| Command palette | `apps/web/src/components/ui/command.tsx:68-224` |
| Sidebar widths + row ladder | `apps/web/src/components/ui/sidebar.tsx:27-29,716,729,797-810` |
| Sidebar resize bounds | `apps/web/src/components/threadSidebarWidth.ts:1-22` |
| **Sidebar row surface model** | `apps/web/src/components/Sidebar.tsx:1023-1040` |
| Sidebar row variants | `apps/web/src/components/Sidebar.tsx:1117-1131,1255-1287` |
| Status/action slot swap | `apps/web/src/components/Sidebar.tsx:1325-1340` |
| Connection status dot | `apps/web/src/components/ConnectionStatusDot.tsx:6-53` |
| PR status colors | `apps/web/src/components/ThreadStatusIndicators.tsx:38-91` |
| Banner stack motion | `apps/web/src/components/chat/ComposerBannerStack.tsx:8-22,100-152` |
| AnimatedHeight | `apps/web/src/components/AnimatedHeight.tsx:72-88` |
| Composer-context morph | `apps/web/src/components/BranchToolbarEnvironmentSelector.tsx:66` |
| Send button press states | `apps/web/src/components/chat/ComposerPrimaryActions.tsx:88,221` |
| Topbar consumers | `apps/web/src/components/ChatView.tsx:6010,6137-6145` |
| Shell layout + traffic lights | `apps/web/src/components/AppSidebarLayout.tsx:44,100,165-169` |
| Theme role token names | `apps/web/src/themePalette.ts:1888-1937` |
| Right panel breakpoint | `apps/web/src/rightPanelLayout.ts:1` |
