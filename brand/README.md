# JustSearch brand assets

**The rule: identity surfaces derive from these files. A surface never invents brand.** If a screen,
installer page, icon slot or document needs the mark, it takes an existing output of
`brand/generate.mjs` — or gets a new output added to it. Hand-drawing "close enough" artwork into a
surface is how a product ends up with five slightly different logos and no way to change any of them.

The mark is **"The Slot"**: a sealed mass with one slot cut through its wall, an answer-line standing
*outside* it, and a superscript footnote square. It draws the product's sentence — nothing leaves the
machine but the answer, and the answer leaves cited. Design record (directions A–D, the comparison
sheet, the self-critique): [`docs/ui-explorations/2026-08-06-brand-identity/`](../docs/ui-explorations/2026-08-06-brand-identity/).
Implementation record: [`docs/tempdocs/815-visual-identity-kernel.md`](../docs/tempdocs/815-visual-identity-kernel.md).

## What derives from what

```
mark-{light,dark}.svg ........ master, 64-unit artboard, footnote included
mark-small-{light,dark}.svg .. the dedicated <=24px cut, 16-unit grid
mark-24-{light,dark}.svg ..... the 24px raster frame, HAND-SNAPPED to whole pixels
lockup-dark.svg .............. mark + wordmark as live <text> (for HTML/SVG surfaces)
wordmark-outlines.svg ........ the wordmark as vector outlines (GENERATED, see below)
        |
        |  node brand/generate.mjs
        v
modules/shell/src-tauri/icons/32x32.png, 128x128.png, 128x128@2x.png   app icons (tauri bundle.icon)
modules/shell/src-tauri/icons/icon.ico                                 Windows icon, 5 frames
modules/shell/src-tauri/icons/icon.icns                                macOS icon, PNG payloads
modules/shell/src-tauri/nsis/sidebar.bmp                               NSIS Welcome/Finish, 164x314
modules/shell/src-tauri/nsis/header.bmp                                NSIS interior header, 150x57
modules/ui-web/public/favicon.svg                                      web/webview favicon
```

Everything below the arrow is **derived**: delete it and regenerate, never hand-edit it. Everything
above the arrow is **authored** — except `wordmark-outlines.svg`, which is produced once by
`brand/extract-wordmark.py` from Segoe UI Variable and committed so that `generate.mjs` needs no font
installed and no native image dependency.

## Regenerate

```bash
node brand/generate.mjs          # all derived assets; no dependencies, no npm install
```

Only if the wordmark itself changes (different string, weights, or typeface):

```bash
python brand/extract-wordmark.py   # rewrites wordmark-outlines.svg; needs fontTools + Windows fonts
node brand/generate.mjs
```

`node scripts/ci/check-installer-branding.mjs` is the gate: it fails the build if a wired asset is
missing, empty, the wrong pixel size, not actually a BMP, still the stock Tauri icon, or if the web
favicon has drifted back to Vite's logo.

## The size ladder

| Size | What renders | Source |
|---|---|---|
| **>= 48px** | master, footnote **included** | `mark-{light,dark}.svg` |
| **25–47px** | master, footnote **dropped** | `mark-{light,dark}.svg` minus `#footnote` |
| **<= 24px** | the dedicated cut — footnote gone, slot deepened, corners squared | `mark-small-{light,dark}.svg` |
| **exactly 24px raster** | the hand-snapped frame | `mark-24-{light,dark}.svg` |

The 16-unit cut is pixel-exact at 16px and 32px. At **24px** the 1.5× scale puts every odd coordinate
on a half-pixel, so the `.ico`'s 24px frame is a separately authored, hand-snapped file — three edges
nudged to whole pixels with the silhouette preserved (the full derivation is in the file's own
comment). The result is verifiably crisp: the 16px and 24px `.ico` frames contain only alpha 0 or 255
and only the two brand colours — no anti-aliased pixel anywhere.

## Colour

| | Ink | Accent |
|---|---|---|
| Light ground | `#16181c` | `#00655B` |
| Dark ground | `#eceef1` | `#00CCB2` |

Mass = ink. Answer-line + footnote = accent. Ground is transparent. In single-colour contexts every
shape is ink.

**Icons take the dark-ground colorway** (`icon.ico`, `icon.icns`, the app PNGs) because an OS icon
cannot follow the OS theme, and the chrome it spends its life in — taskbar, Start, title bar — is
dark by default. The honest cost: over a light background the pale mass has little contrast. The NSIS
**header** bitmap therefore takes the *light* colorway instead, because MUI's header ground is white
(`MUI_BGCOLOR` defaults to `FFFFFF`); the **sidebar** takes dark on its own `#0e0f12` field.

## Wordmark and lockup

"JustSearch" is **one word**, weight-split — `Just` at ~650, `Search` at ~350 — in the system
grotesque (Segoe UI Variable stack). The mark never contains text. Below 32px the mark stands alone;
mark + wordmark is for installer and hero scale. `lockup-dark.svg` sets it as live `<text>` so web
surfaces get real text; fixed rasters use `wordmark-outlines.svg`, because an installer bitmap has to
look identical on a machine that has never heard of Segoe UI.
