---
title: "Visual-identity kernel — the mark ships, and every identity surface derives from one place"
status: "implemented 2026-08-06 — brand/ authored, generate.mjs derives all 8 binaries, tauri + NSIS + ui-web wired, gate extended with an asset class (28 assertions), stock Tauri icon.ico/icon.icns and vite.svg deleted. One open question for the owner: §7 icon contrast on light grounds."
created: 2026-08-06
updated: 2026-08-06
related: [810, 812, 807, 806]
---

# 815 — Visual-identity kernel

## What this document is

The implementation record for the visual-identity **kernel**: the minimum that makes JustSearch look
like a product rather than a framework template, built so that nothing has to be re-drawn to extend
it. The mark itself was chosen in a prior round (Mark E, "The Slot") and its record is public at
[`docs/ui-explorations/2026-08-06-brand-identity/`](../ui-explorations/2026-08-06-brand-identity/) —
this document is what happened *after* the choice.

The single organising rule: **identity surfaces derive from `brand/`; a surface never invents brand.**
Every binary in the tree now traces to a committed SVG plus a committed script, and the gate fails the
build if a surface drifts off that line.

## 1. What shipped

**Authored (the authority) — `brand/`**

| File | What it is |
|---|---|
| `mark-light.svg` / `mark-dark.svg` | master, 64-unit artboard, footnote included |
| `mark-small-light.svg` / `mark-small-dark.svg` | the dedicated ≤24px cut, 16-unit grid |
| `mark-24-light.svg` / `mark-24-dark.svg` | the 24px raster frame, hand-snapped (§4) |
| `lockup-dark.svg` | mark + wordmark as live `<text>`, Segoe UI Variable stack + documented fallback |
| `wordmark-outlines.svg` | the wordmark as outlines — generated once, committed |
| `extract-wordmark.py` | the one-off that produces the outlines from Segoe UI Variable |
| `generate.mjs` | renders every derived binary |
| `README.md` | one screen: what derives from what, the ladder, the regenerate command, the rule |

**Derived (`node brand/generate.mjs`, 8 outputs)**

`modules/shell/src-tauri/icons/{32x32,128x128,128x128@2x}.png` ·
`icons/icon.ico` (16, 24, 32, 48, 256) · `icons/icon.icns` (11 entries, 16→1024) ·
`modules/shell/src-tauri/nsis/sidebar.bmp` (164×314) · `nsis/header.bmp` (150×57) ·
`modules/ui-web/public/favicon.svg`.

**Wired** — `tauri.conf.json`
`bundle.windows.nsis.{installerIcon,uninstallerIcon,headerImage,sidebarImage}`
(`modules/shell/src-tauri/tauri.conf.json:26-32`); `modules/ui-web/index.html:5`;
`.gitignore:102-108` (three narrow un-ignore lines under the blanket `*.png`).

**Gated** — `scripts/ci/check-installer-branding.mjs` gains `checkBundleIcons`, `checkWizardImages`,
`checkFaviconWiring`; `check-installer-branding.test.mjs` goes 12 → 29 assertions, every new branch
bite-tested.

## 2. What this orphans (swept in the same change)

- **The stock Tauri `icon.ico` and `icon.icns`** — replaced, not left beside. The old
  `icon.ico` is pinned by sha256 in the gate (`392206b5…`) so it can never come back silently.
- **`modules/ui-web/public/vite.svg`** — the Vite scaffold logo, deleted; it had been the app's
  favicon for the frontend's whole life. The gate fails on any surviving `vite.svg` reference.
- **Three phantom `bundle.icon` entries** — `32x32.png`, `128x128.png`, `128x128@2x.png` were listed
  in `tauri.conf.json` and did not exist on disk. `tauri-bundler` canonicalises every entry
  (`nsis/mod.rs:347-395`), so this would have failed any local installer build — which nobody hit,
  because Smart App Control makes local installer builds impossible on the dev machine. Now real.
- **The stock NSIS wizard artwork** — `win.bmp` on the Welcome/Finish sidebar, `nsis.bmp` in the
  interior header, `modern-install.ico` / `modern-uninstall.ico` on the two wizard windows.
- **Round-14 F1** (tempdoc 810 §c item 1, "is the stock NSIS `win.bmp` acceptable?") — closed by
  implementation, and the question it raised ("what does a healthy Welcome page look like?") is no
  longer a judgement call: the gate answers it.

## 3. Tooling path chosen: none

`brand/generate.mjs` is a pure-Node rasteriser and container writer with **zero dependencies** — not
sharp, not resvg, not ImageMagick, not Python. The reason is that each of those is absent from at
least one machine that has to be able to run this (a fresh worktree with no `npm install`, a CI
container, a checkout on a non-Windows host). The mark is a handful of straight edges, four
quarter-arcs and two rectangles; PNG, ICO, ICNS and 24-bit BMP are all trivially writable; Node's
built-in `zlib` does the only non-trivial part. So the dependency count is zero and the output is
byte-deterministic.

- **Rasteriser**: scanline, 16 sub-scanlines per pixel row, exact horizontal span coverage, nonzero
  winding (what both SVG's default fill-rule and TrueType outlines use).
- **ICO**: `ICONDIR` + `ICONDIRENTRY` per frame; DIB payloads (BITMAPINFOHEADER, doubled height,
  bottom-up BGRA, plus a real 1bpp AND mask derived from alpha) below 256, PNG payload at 256 per the
  modern convention — a raw DIB there would cost 256 KB.
- **ICNS**: PNG payloads (legal since OS X 10.7, and what `iconutil` itself emits). macOS is not a
  shipped target, but `bundle.icon` lists the file and the bundler canonicalises it, so it has to be
  real.
- **The wordmark is the one thing a rasteriser cannot do**, so it is pre-outlined by
  `brand/extract-wordmark.py` (fontTools) and committed. Segoe UI Variable is instanced at exactly
  wght 650 / 350 — the spec's weights, not the nearest statics. One wrinkle worth recording: the
  face's GPOS carries a kern-pair variation index fontTools cannot resolve (`KeyError` inside
  `varLib.merger`), so the extractor drops GPOS before instancing. That costs nothing here because no
  kerning is applied and no pair in "JustSearch" needs it; without it the script silently falls back
  to static Semibold/Semilight and the 650 is only approximated.

## 4. The 24px hand-snap

The ≤24px cut is drawn on a 16-unit grid, and 24 / 16 = 1.5 — so every **odd** coordinate lands on a
half-pixel at 24px (1 → 1.5, 5 → 7.5, 11 → 16.5). A half-pixel edge is a grey row; a grey row at 24px
is a blurred taskbar icon. `brand/mark-24-{light,dark}.svg` is therefore authored, not scaled. Three
edges moved, silhouette preserved:

| Edge | Raw at 1.5× | Snapped | Why that direction |
|---|---|---|---|
| mass left wall | 1.5 | **2** | gives a 2px outer margin |
| slot mouth (notch) | 7.5 | **8** | slot depth 7px, was 7.5 |
| answer-line left | 16.5 | **16** | puts the line's right edge on 22, so the right margin is also 2px — symmetric with the left |

Every **y** coordinate was already whole at 1.5× (3, 9, 15, 21) and was not touched. The snap happens
to regularise the drawing: back wall, top arm and bottom arm all land on exactly 6px, and the 1px gap
between mass and answer-line survives.

## 5. Where the NSIS artwork actually attaches (the wrong-gate check)

The charter allowed for the wizard bitmaps being wired from `nsis/installer-hooks.nsh`. They are
**not** — the correct seam is `tauri.conf.json`, and using the hooks file would have half-worked in a
way that is easy to mistake for working.

Primary source, read at the **exact version this repo pins** — `@tauri-apps/cli` resolves to
`2.11.4` in `modules/shell/package-lock.json:32-33`, so the tag is `tauri-cli-v2.11.4`, and the
template is identical there to `dev` (line numbers verified against both):
`crates/tauri-bundler/src/bundle/windows/nsis/installer.nsi`.

- `installer.nsi:34-36` — `{{#if installer_hooks}} !include "{{installer_hooks}}"` — the hooks file is
  included very early.
- `installer.nsi:47-49` — `!define INSTALLERICON "{{installer_icon}}"`, `!define SIDEBARIMAGE
  "{{sidebar_image}}"`, `!define HEADERIMAGE "{{header_image}}"` — **after** the hooks include.
- `installer.nsi:130-155` — guarded by `!if "${SIDEBARIMAGE}" != ""` etc., the template itself emits
  `!define MUI_ICON`, `!define MUI_WELCOMEFINISHPAGE_BITMAP`, `!define MUI_HEADERIMAGE` and
  `!define MUI_HEADERIMAGE_BITMAP`.
- `installer.nsi:170` / `:417` — `!insertmacro MUI_PAGE_WELCOME` / `MUI_PAGE_FINISH`, after all of it.
- The handlebars variables are fed from config: `nsis/mod.rs:349`, `:359`, `:369` insert
  `installer_icon`, `header_image`, `sidebar_image` from `bundle.windows.nsis.*` and
  `dunce::canonicalize` each — so a path that does not exist fails the bundle rather than silently
  skipping. (`installer_hooks` goes in at `:402`.)

Consequence: defining `MUI_WELCOMEFINISHPAGE_BITMAP` in the hooks file would **collide** with the
template's own `!define` at line 137 (NSIS keeps the first and warns), and — the part that would
actually bite — `MUI_HEADERIMAGE` is only ever enabled inside `!if "${HEADERIMAGE}" != ""`, which is
driven by config alone. A hooks-file `MUI_HEADERIMAGE_BITMAP` would therefore have been defined and
never rendered. This is exactly the `wrong-gate` shape: the symbol exists, the gate never fires.

The 807-era text defines (`MUI_WELCOMEPAGE_TEXT`, `MUI_FINISHPAGE_TEXT`) stay in the hooks file
unchanged — those have no config seam and MUI takes them through `MUI_DEFAULT` (an `!ifndef`), so an
early define does win there. Two different mechanisms, and only one of them is the hooks file.

**The uninstaller, swept in the same pass.** Read against the MUI2 sources tauri actually ships
(`%LOCALAPPDATA%/tauri/NSIS/Contrib/Modern UI 2/`): `MUI_HEADERIMAGE_UNBITMAP` **does** inherit from
`MUI_HEADERIMAGE_BITMAP` when unset (`Interface.nsh:68-70`), so the uninstaller's header picks up
`header.bmp` for free — but `MUI_UNICON` inherits nothing and defaults to NSIS's own
`modern-uninstall.ico` (`Interface.nsh:49-50`). Setting only `installerIcon` would therefore have
left stock framework artwork on a wizard the user does see, and tempdoc 810 §c notes this candidate's
uninstaller has never been run by any validation round — precisely the blind spot residue survives
in. `uninstallerIcon` is set alongside it and the gate requires both. (There is no uninstaller
sidebar to worry about: the template's uninstaller has only `MUI_UNPAGE_CONFIRM` and
`MUI_UNPAGE_INSTFILES`, `installer.nsi:463`/`:466`, so `MUI_UNWELCOMEFINISHPAGE_BITMAP` is never
read.)

**Header ground.** MUI2's `MUI_BGCOLOR` defaults to `FFFFFF`, so the header band is white and
`header.bmp` takes the **light** colorway (ink `#16181c`, accent `#00655B`) while every other shipped
raster takes the dark one. The sidebar carries its own `#0e0f12` field, so it stays dark.

## 6. Visual self-check (what was actually looked at)

Every generated binary was decoded with **PIL** — an independent decoder, not the writer that produced
it — and rendered to PNG for inspection. That doubles as format validation: PIL read all five ICO
frames, all eleven ICNS entries and both BMPs without complaint.

- **`sidebar.bmp` (164×314)** — flat `#0e0f12` field edge to edge; the seam at the right is a clean
  hard vertical (flat fill, no gradient, no feather). Mark in the upper area with its mass left wall
  flush to the same 24px margin the wordmark starts from; footnote square present and legible.
  Wordmark lower-left, and the **weight split is plainly visible** — "Just" reads noticeably heavier
  than "Search" at 18px cap. The middle of the panel is empty; it reads as a deliberate poster
  layout rather than as unfinished, but it is the one composition choice worth a second opinion.
- **`header.bmp` (150×57)** — white ground, 24px small-cut mark + 14px-cap wordmark, left-anchored,
  ~12px right margin. Crisp; the accent line is the dark-ground-appropriate `#00655B`, not the bright
  teal, so it does not glare on white.
- **ICO frames** — inspected at 1:1 and at 8× nearest-neighbour. **16px and 24px are provably crisp**,
  not by eye but by oracle: both frames contain only alpha `{0, 255}` and only the two brand colours
  `{#eceef1, #00ccb2}` — zero anti-aliased pixels. The 24px row map matches the §4 snap exactly (mass
  cols 2–14, slot cols 8–14 × rows 9–14, line cols 16–21, 2px margins both sides, 6px arms).
  **32px carries the master without the footnote**, and shows the expected AA on its 2px corner radii
  (alphas `{0, 80, 232, 233, 255}`). **The footnote appears at 48px and 256px and nowhere below** —
  the ladder is honoured by construction, since `markFrame()` picks the source file by size.
- **`icon.icns`** — the 1024 entry renders the master correctly: mass, slot, answer-line and footnote
  in the right places with the spec's `y=22` footnote (not the exploration card's `y=20`).

## 7. Open question for the owner — icon contrast on light grounds

The spec says the shipped `.ico`/`.icns` take the **dark-ground** colorway because an icon cannot
follow the OS theme. That was implemented as written. Rendering the app PNGs over both grounds makes
the cost visible and it is larger than "slightly lower contrast": over **white**, the `#eceef1` mass
is very nearly invisible, and because the slot is cut *through* the mass, the mark loses its whole
subject and reads as a pale smudge with a teal dash beside it. Over `#0e0f12` it is perfect.

This is not hypothetical — Windows 11 shows app icons on light Explorer backgrounds, on light-mode
taskbars, and over arbitrary wallpaper. Three options if the owner wants it addressed, none taken
here because the colorway is a spec decision, not an implementation detail:

1. Accept it (dark chrome is the common case; light-mode users see a faint icon).
2. Ship the **light** colorway in the icon instead — inverts the problem onto dark chrome, which is
   Windows 11's default, so this is probably worse.
3. Give the icon an opaque plate. The mark's own self-critique explicitly refuses the
   "glyph-in-rounded-app-square formula", so this needs an owner decision, not an agent's.

## 8. Ride-alongs

- **Tempdoc 810 §c item 1 (F1)** marked **CLOSED**, with the original finding text kept.
- **`docs/reference/security/threat-model.md`** — new STRIDE subsection *Information disclosure at
  rest — the action-ledger audit journal*: the journal persists plaintext at rest under the data dir
  by owner decision 2026-08-06 (accept-and-document); it is metadata, not content; the
  encrypted-store catalog deliberately excludes it because an entry without a cipher would falsely
  claim sealing; revisit if audit rows ever carry content.
- **Tempdoc 812** — the same posture recorded in one paragraph next to the implementation record.

## 9. Verification

| Step | Result |
|---|---|
| `./gradlew.bat spotlessApply build -x test` | exit 0 |
| `./gradlew.bat test` (full suite) | exit 0 |
| `node scripts/ci/check-installer-branding.mjs` | OK, with the new asset class |
| `node scripts/ci/check-installer-branding.test.mjs` | OK — 29 assertions (was 12); every new branch bite-tested |
| `node scripts/ci/check-update-preserves-models.mjs` + `.test.mjs` | OK, 12 assertions (the premerge subject for NSIS/bundle edits) |
| `node scripts/ci/check-tempdoc-numbers.mjs` | OK — 520 distinct numbers, no collisions |
| ui-web `npm run typecheck` | exit 0 |
| ui-web `npm run test:unit:run` | 386 files, 4219 tests, all passed |
| Determinism | `node brand/generate.mjs` run twice; all 8 outputs byte-identical (`sha256sum -c`) |
| ICO structure | 5 entries; `ICONDIR` count correct, per-entry planes=1 bpp=32, offsets contiguous, last entry ends exactly at EOF; 16/24/32/48 carry DIBs with doubled height + real AND mask, 256 carries a PNG payload |
| ICNS structure | decodes to 11 entries, 16→1024 including the @2x aliases |
| `.gitignore` narrowness | a stray `.png` dropped in `icons/` **and** in `brand/` is still ignored; only the three named files are trackable |

Both CI lanes that consume this already exist: `.github/workflows/ci.yml:183` runs the test and
`:190` runs the gate, so the asset class is enforced on every PR without new wiring.
