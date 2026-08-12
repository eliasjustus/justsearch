// SPDX-License-Identifier: Apache-2.0
/**
 * sv3Tokens — the Search v3 window's token sheet (tempdoc 822 slice 1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The whole sheet is scoped to the WINDOW HOST, never `:root`: custom properties inherit down
 * through every nested shadow root, so a host-scoped declaration reaches the window's whole tree
 * while the shipped app's `:root` palette stays untouched. Dark is the default set (`:host`);
 * the light set sits behind `:host([theme='light'])` for a later theme seam.
 *
 * Three tiers, flowing one way: T0 primitives → T1 semantic roles → T2 geometry/material.
 * A component reads T1/T2 only. A theme change is a T1 redefinition, never a component edit.
 */
import { css } from 'lit';

export const sv3Tokens = css`
  :host {
    color-scheme: dark;

    /* ── T0 primitives ─────────────────────────────────────────────────────
       The donor's palette resolves through Tailwind v4's built-in scale, which is not part of
       its source tree; these are the Tailwind v4 default values, pinned here as literals. */
    --color-white: oklch(100% 0 0);
    --color-zinc-25: oklch(99.2% 0 0);
    --color-zinc-50: oklch(98.5% 0 0);
    --color-zinc-100: oklch(96.7% 0.001 286.375);
    --color-zinc-200: oklch(92% 0.004 286.32);
    --color-zinc-300: oklch(87.1% 0.006 286.286);
    --color-zinc-500: oklch(55.2% 0.016 285.938);
    --color-zinc-800: oklch(27.4% 0.006 286.033);
    --color-zinc-900: oklch(21% 0.006 285.885);
    --color-neutral-100: oklch(97% 0 0);
    --color-neutral-500: oklch(55.6% 0 0);
    --color-neutral-950: oklch(14.5% 0 0);
    --color-red-400: oklch(70.4% 0.191 22.216);
    --color-red-500: oklch(63.7% 0.237 25.331);
    --color-red-700: oklch(50.5% 0.213 27.518);
    --color-blue-400: oklch(70.7% 0.165 254.624);
    --color-blue-500: oklch(62.3% 0.214 259.815);
    --color-blue-700: oklch(48.8% 0.243 264.376);
    --color-emerald-400: oklch(76.5% 0.177 163.223);
    --color-emerald-500: oklch(69.6% 0.17 162.48);
    --color-emerald-700: oklch(50.8% 0.118 165.612);
    --color-amber-400: oklch(82.8% 0.189 84.429);
    --color-amber-500: oklch(76.9% 0.188 70.08);
    --color-amber-700: oklch(55.5% 0.163 48.998);
    /* The donor's brand hue is not copied; the primary is JustSearch's own teal accent
       (h 180), in the app's dark/light contrast pairing. */
    --color-teal-accent: oklch(75% 0.15 180);
    --color-teal-accent-ink: oklch(22% 0.06 180);

    /* ── T1 semantic roles — dark (the window's default) ───────────────────
       Dark surfaces are white-at-low-alpha over a near-black base, so every one composites
       correctly over whatever sits behind it; elevation is a color-mix ladder, not a shadow
       ladder. Light (below) inverts that: opaque named grays, separated by shadow. */
    --background: var(--color-neutral-950);
    --app-chrome-background: var(--background);
    --foreground: var(--color-neutral-100);
    --card: color-mix(in srgb, var(--background) 97%, var(--color-white));
    --card-foreground: var(--color-neutral-100);
    --popover: color-mix(in srgb, var(--background) 94%, var(--color-white));
    --popover-foreground: var(--color-neutral-100);
    --secondary: color-mix(in srgb, var(--color-white) 4%, transparent);
    --secondary-foreground: var(--color-neutral-100);
    --surface-raised: var(--secondary);
    --muted: color-mix(in srgb, var(--color-white) 4%, transparent);
    --muted-foreground: color-mix(in srgb, var(--color-neutral-500) 90%, var(--color-white));
    --accent: color-mix(in srgb, var(--color-white) 4%, transparent);
    --accent-foreground: var(--color-neutral-100);
    --primary: var(--color-teal-accent);
    --primary-foreground: var(--color-teal-accent-ink);
    /* Three named intents on one value: a future divergence is a one-line change, not a grep. */
    --placeholder: var(--muted-foreground);
    --secondary-label: var(--muted-foreground);
    --icon-muted: var(--muted-foreground);
    --border: color-mix(in srgb, var(--color-white) 6%, transparent);
    --input: color-mix(in srgb, var(--color-white) 8%, transparent);
    --ring: var(--primary);
    --toolbar-background: var(--app-chrome-background);
    --toolbar-foreground: var(--foreground);
    --toolbar-border: var(--border);
    --toolbar-control: var(--popover);
    --toolbar-control-foreground: var(--foreground);
    --toolbar-control-hover: var(--accent);
    /* Status is a fixed five, each with a foreground and three with a surface tint. The tints are
       roughly double their light-mode strength: a low-alpha tint over near-black barely reads. */
    --error: color-mix(in srgb, var(--color-red-500) 90%, var(--color-white));
    --error-foreground: var(--color-red-400);
    --error-surface: color-mix(in srgb, var(--error) 16%, transparent);
    --destructive: var(--error);
    --destructive-foreground: var(--error-foreground);
    --info: var(--color-blue-500);
    --info-foreground: var(--color-blue-400);
    --success: var(--color-emerald-500);
    --success-foreground: var(--color-emerald-400);
    --warning: var(--color-amber-500);
    --warning-foreground: var(--color-amber-400);
    --warning-surface: color-mix(in srgb, var(--warning) 16%, transparent);
    --update: var(--primary);
    --update-foreground: var(--primary);
    --update-surface: color-mix(in srgb, var(--update) 18%, transparent);
    --sidebar: var(--card);
    --sidebar-foreground: var(--foreground);
    --sidebar-muted-foreground: var(--muted-foreground);
    --sidebar-control-surface: var(--muted);
    --sidebar-row-hover: var(--accent);
    --sidebar-row-active: var(--accent);
    --sidebar-row-selected: var(--muted);
    --sidebar-border: var(--border);
    /* Dialog material (slice 4). The palette is the window's one dialog surface, and its glass is a
       DIFFERENT recipe from the composer's: a dialog is the densest tint in the system and, unlike
       every other dark surface, keeps its drop shadow — it has to separate from a live window behind
       it, so it catches light on the top edge AND casts. The backdrop is one formula in both modes. */
    --dialog-backdrop: color-mix(in srgb, var(--background) 60%, transparent);
    --dialog-backdrop-blur: 4px;
    --dialog-border: color-mix(in srgb, var(--color-white) 8%, transparent);
    --dialog-shadow:
      inset 0 1px rgb(255 255 255 / 4%), 0 24px 72px -20px rgb(0 0 0 / 90%);
    /* The empty-state tile's edge is the elevation inversion at its smallest: a hairline BELOW the
       tile in light, ABOVE it in dark. */
    --empty-tile-shadow: none;
    --empty-tile-edge: 0 -1px rgb(255 255 255 / 6%);

    /* Composer material. The donor expresses its dark mode as dark-class RULES on the component; a
       selector inside a shadow root cannot see a class on the document element, so the whole
       inversion is carried as tokens instead (donor §8.3's own recommendation). Dark catches light —
       a 1px inset top highlight and NO drop shadow; light casts one down. */
    --composer-glass-surface: color-mix(in srgb, var(--background) 96%, var(--color-white));
    --composer-outline: color-mix(in srgb, var(--color-white) 5%, transparent);
    --composer-shadow: none;
    --composer-highlight: inset 0 1px rgb(255 255 255 / 3%);
    /* The USER message's fill (donor 'bg-message' / 'text-message-foreground'). Both are pure
       indirections off tokens the light block already redefines, so the light theme inherits the
       inversion without a second declaration — a light copy of an identical value would be a fork
       waiting to drift. The response block deliberately has NO surface of its own: the donor gives
       the assistant plain content on the panel, which is what makes the user's turn the only thing
       with a fill and therefore readable as the punctuation of the transcript. */
    --message-surface: var(--accent);
    --message-foreground: var(--foreground);
    /* The primary action's material is one indirection off --primary, so a future accent change
       reaches the send button without touching it. */
    --message-action: var(--primary);
    --message-action-foreground: var(--primary-foreground);
    --message-action-hover: color-mix(in srgb, var(--primary) 90%, var(--background));
    /* A filled control's press physics: a top highlight at rest that FLIPS dark while pressed, with
       the drop shadow dropped at the same time, so the control presses INTO the surface. */
    --control-inset-highlight: inset 0 1px rgb(255 255 255 / 16%);
    --control-inset-pressed: inset 0 1px rgb(0 0 0 / 8%);

    /* ── T2 geometry / material ────────────────────────────────────────────
       Semantic on purpose: sidebar, palette, tooltip and toolbar controls cannot quietly drift
       apart, because they read the same names. Enforced by sv3-tokens.test.ts. */
    --control-radius: 0.5rem;
    --sidebar-width: 16rem;
    --sidebar-content-inset: 0.5rem;
    --sidebar-control-gap: 0.5rem;
    --sidebar-row-content-inset: 0.625rem;
    --sidebar-icon-color: color-mix(in srgb, var(--sidebar-muted-foreground) 60%, var(--sidebar));
    --command-shell-inset: 0.5rem;
    --command-content-inset: 1rem;
    /* The palette popup's box (donor max-h-105 / max-w-xl). */
    --command-popup-max-height: 26.25rem;
    --command-popup-max-width: 36rem;
    /* The donor's scroll-fade band is 2.5rem over a chat timeline and cut to 1.5rem for its one DENSE
       list sitting directly under its own chrome, "so the fade stays while the controls start near
       the chrome" — which is exactly the palette list. */
    --command-scroll-fade-height: 1.5rem;
    /* The composer field's two floors. The donor ships ONE editor at 70px and swaps the whole block
       out for a single truncating line when the composer is compact; the window keeps a typable field
       in both forms, so the compact form is expressed as a one-LINE floor instead. The ceiling is
       shared, which is what keeps a docked draft growing. */
    --composer-field-min-hero: 4.375rem;
    --composer-field-min-docked: 1lh;
    --composer-field-max: 12.5rem;
    --floating-content-inset: 0.75rem;
    --workspace-topbar-height: 52px;
    --workspace-controls-top: 0px;
    /* Plain constants until Tauri window metrics feed them; the donor's Electron window-controls
       env() values do not port, only this token indirection does. */
    --workspace-controls-left: 0.75rem;
    --workspace-controls-right: 0.75rem;
    --workspace-native-controls-inset: 0px;
    --workspace-titlebar-control-size: 1.75rem;
    --workspace-titlebar-control-gap: 0.75rem;
    --desktop-window-right-resize-inset: 0px;
    /* Dark gets more blur and less saturation: on near-black, blur needs radius to read as
       depth and a saturation boost reads as garish. */
    --glass-blur: 16px;
    --glass-opacity: 80%;
    --glass-saturation: 1.08;
    --app-scrollbar-width: 6px;
    --app-scrollbar-thumb: rgb(255 255 255 / 8%);
    --app-scrollbar-thumb-hover: rgb(255 255 255 / 12%);

    /* ── Radius ladder — one knob, additive ────────────────────────────────
       --radius shifts the whole window's roundness while the 4px differences between tiers
       hold. --control-radius above is the SECOND, independent knob: controls are not surfaces. */
    --radius: 0.625rem;
    --radius-sm: calc(var(--radius) - 4px);
    --radius-md: calc(var(--radius) - 2px);
    --radius-lg: var(--radius);
    --radius-xl: calc(var(--radius) + 4px);
    --radius-2xl: calc(var(--radius) + 8px);
    --radius-3xl: calc(var(--radius) + 12px);
    --radius-4xl: calc(var(--radius) + 16px);

    /* ── Z-scale (donor improvement: the donor has none) ───────────────────
       Tooltips deliberately sit above dialogs. */
    --z-content: 0;
    --z-sticky: 10;
    --z-overlay: 20;
    --z-dialog: 50;
    --z-tooltip: 70;
    --z-toast: 100;

    /* ── Spacing ladder (donor improvement: 4px steps, not inlined calc) ───── */
    --space-1: 4px;
    --space-2: 8px;
    --space-3: 12px;
    --space-4: 16px;
    --space-5: 20px;
    --space-6: 24px;
    --space-7: 28px;
    --space-8: 32px;
    --space-9: 36px;
    --space-10: 40px;
    --space-11: 44px;
    --space-12: 48px;
    /* The donor's ladder is Tailwind's 0.25rem scale, which carries half-steps; the window's densest
       regions spend all three (palette item / input-row / group-label py-1.5 and footer py-2.5; the
       transcript's response block py-0.5). They are named here rather than inlined so the ladder
       stays the one authority. */
    --space-0-5: 2px;
    --space-1-5: 6px;
    --space-2-5: 10px;
    /* Donor improvement: the 1px border is taken out of the padding ONCE, here, instead of being
       re-derived at every control size, so a control's visual inset equals the spacing step. */
    --control-pad-3: calc(0.75rem - 1px);

    /* ── Type ──────────────────────────────────────────────────────────────
       No shipped face: the platform stack, four effective sizes, weights 400/500/600. */
    --font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
    --font-mono: ui-monospace, 'SF Mono', 'SFMono-Regular', Menlo, Consolas, 'Liberation Mono',
      monospace;
    --font-size-sv3-xs: 0.75rem;
    --font-size-sv3-sm: 0.875rem;
    --font-size-sv3-base: 1rem;
    --font-size-sv3-lg: 1.125rem;
    --font-size-sv3-xl: 1.25rem;
    /* The hero headline is the ONE display size outside the four-size UI ramp, and the one place the
       desktop ramp steps UP rather than down. */
    --font-size-sv3-display: 1.875rem;

    /* ── Motion budget ─────────────────────────────────────────────────────
       Effectively two values (micro / layout), with one reserved for the signature morph.
       Enter eases out, exit eases in, a drag-tied geometric change is linear. */
    --duration-sv3-micro: 150ms;
    --duration-sv3-layout: 200ms;
    --duration-sv3-morph: 180ms;
    --ease-sv3-enter: ease-out;
    --ease-sv3-exit: ease-in;
    --ease-sv3-linear: linear;
    --ease-sv3-morph: cubic-bezier(0.32, 0.72, 0, 1);
  }

  :host([theme='light']) {
    color-scheme: light;

    --background: var(--color-zinc-25);
    --app-chrome-background: var(--background);
    --foreground: var(--color-zinc-800);
    --card: var(--color-white);
    --card-foreground: var(--color-zinc-800);
    --popover: var(--color-white);
    --popover-foreground: var(--color-zinc-800);
    --secondary: var(--color-zinc-50);
    --secondary-foreground: var(--color-zinc-800);
    --surface-raised: color-mix(in srgb, var(--card) 20%, transparent);
    --muted: var(--color-zinc-50);
    --muted-foreground: var(--color-zinc-500);
    --accent: var(--color-zinc-100);
    --accent-foreground: var(--color-zinc-900);
    --primary: oklch(45% 0.18 180);
    --primary-foreground: oklch(99% 0.01 180);
    --placeholder: var(--muted-foreground);
    --secondary-label: var(--muted-foreground);
    --icon-muted: var(--muted-foreground);
    --border: var(--color-zinc-200);
    --input: var(--color-zinc-300);
    --ring: var(--primary);
    --toolbar-control: var(--popover);
    --toolbar-control-hover: var(--accent);
    --error: var(--color-red-500);
    --error-foreground: var(--color-red-700);
    --error-surface: color-mix(in srgb, var(--error) 8%, transparent);
    --destructive: var(--error);
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
    --sidebar: var(--color-zinc-50);
    --sidebar-control-surface: var(--color-zinc-100);
    --sidebar-row-hover: var(--color-zinc-25);
    --sidebar-row-active: var(--color-white);
    --sidebar-row-selected: var(--color-white);
    --composer-glass-surface: var(--card);
    --composer-outline: rgb(0 0 0 / 8%);
    --composer-shadow: 0 12px 28px -18px rgb(0 0 0 / 40%);
    --composer-highlight: none;
    --dialog-border: color-mix(in srgb, var(--foreground) 10%, transparent);
    --dialog-shadow: 0 24px 64px -24px rgb(0 0 0 / 65%);
    --empty-tile-shadow: 0 1px 2px 0 rgb(0 0 0 / 5%);
    --empty-tile-edge: 0 1px rgb(0 0 0 / 4%);

    --glass-blur: 12px;
    --glass-saturation: 1.14;
    --app-scrollbar-thumb: rgb(217 217 217);
    --app-scrollbar-thumb-hover: rgb(191 191 191);
  }
`;
