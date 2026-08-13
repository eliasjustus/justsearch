#!/usr/bin/env python
# SPDX-License-Identifier: Apache-2.0
"""One-off: convert the JustSearch wordmark to outlines so raster targets need no font.

WHY THIS EXISTS
---------------
`brand/lockup-dark.svg` sets the wordmark as live `<text>` in the Segoe UI Variable stack, which
is right for HTML/SVG surfaces that have the font. The raster targets (the NSIS sidebar and header
BMPs) have no text engine at all -- `brand/generate.mjs` is a pure-Node rasteriser with no font
support and no native dependency, by design. So the wordmark is converted to vector outlines ONCE,
here, and committed as `brand/wordmark-outlines.svg`. After that, `node brand/generate.mjs` runs
anywhere, needs no font installed, and is byte-deterministic.

Run it only when the wordmark itself changes (different string, different weights, different face):

    F:/scoop/apps/python/current/python.exe brand/extract-wordmark.py

Requires fontTools and the Windows Segoe UI faces. Weight split per the mark spec: "Just" ~650,
"Search" ~350. Segoe UI Variable (SegUIVar.ttf) is instanced at those exact weights when present;
otherwise the nearest static faces are used (Semibold 600 / Semilight 350) and the file records
which source was taken.

Output coordinate system: 1000 units/em, y DOWN, baseline at y = 0, pen start at x = 0. Data
attributes on the root carry the metrics `generate.mjs` needs (advance, cap height) so the layout
code never has to guess.
"""

from __future__ import annotations

import sys
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.misc.transform import Transform

FONT_DIR = Path("C:/Windows/Fonts")
VARIABLE_FACE = FONT_DIR / "SegUIVar.ttf"
STATIC_FALLBACK = {650: FONT_DIR / "seguisb.ttf", 350: FONT_DIR / "segoeuisl.ttf"}
SEGMENTS = [("Just", 650), ("Search", 350)]
EM = 1000


def load_face(weight: int):
    """Return (TTFont, source-description) for a requested weight."""
    if VARIABLE_FACE.exists():
        try:
            from fontTools.varLib import instancer

            font = TTFont(str(VARIABLE_FACE))
            # Segoe UI Variable's GPOS carries a kern-pair variation index fontTools cannot
            # resolve (KeyError inside varLib.merger). We take advance widths only and apply no
            # kerning, so dropping GPOS before instancing costs nothing and unblocks exact weights.
            if "GPOS" in font:
                del font["GPOS"]
            axes = {a.axisTag: a for a in font["fvar"].axes}
            location = {"wght": weight}
            if "opsz" in axes:
                # Display/lockup use, not body text: take the largest optical size the face offers.
                location["opsz"] = axes["opsz"].maxValue
            instanced = instancer.instantiateVariableFont(font, location, inplace=False)
            return instanced, f"SegUIVar.ttf instanced at {location}"
        except Exception as error:  # noqa: BLE001 - fall back rather than fail the one-off
            print(f"  variable instancing failed ({error}); falling back to a static face")
    static = STATIC_FALLBACK[weight]
    if not static.exists():
        sys.exit(f"neither {VARIABLE_FACE} nor {static} is present; cannot extract the wordmark")
    return TTFont(str(static)), f"{static.name} (static fallback for wght {weight})"


def outline_segment(text: str, weight: int, x_start: float):
    font, source = load_face(weight)
    upem = font["head"].unitsPerEm
    scale = EM / upem
    cmap = font.getBestCmap()
    glyphs = font.getGlyphSet()
    hmtx = font["hmtx"]
    cap = getattr(font.get("OS/2"), "sCapHeight", 0) or 0

    pen_out = SVGPathPen(glyphs, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
    x = x_start
    for char in text:
        name = cmap[ord(char)]
        # y is flipped so the emitted path is upright in SVG's y-down space with baseline at y=0.
        transform = Transform(scale, 0, 0, -scale, x, 0)
        glyphs[name].draw(TransformPen(pen_out, transform))
        x += hmtx[name][0] * scale
    return pen_out.getCommands(), x, cap * scale, source


def main() -> None:
    paths = []
    sources = []
    cap_height = 0.0
    x = 0.0
    for text, weight in SEGMENTS:
        commands, x, cap, source = outline_segment(text, weight, x)
        cap_height = max(cap_height, cap)
        paths.append((text, weight, commands))
        sources.append(f"{text} (wght {weight}): {source}")
        print(f"  {text} @ {weight} -> pen x = {x:.1f}, source = {source}")

    advance = x
    lines = [
        "<!--",
        "  JustSearch wordmark as OUTLINES. GENERATED - do not hand-edit.",
        "  Regenerate with: python brand/extract-wordmark.py",
        "",
        "  Sources:",
        *[f"    {s}" for s in sources],
        "",
        "  1000 units/em, y down, baseline at y=0, pen starts at x=0. Consumed by",
        "  brand/generate.mjs for the NSIS bitmaps, which have no text engine.",
        "  Kerning is NOT applied (plain advance widths); no pair in \"JustSearch\" needs it.",
        "-->",
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -{EM} {advance:.1f} {EM * 1.3:.0f}"',
        f'     data-advance="{advance:.1f}" data-cap-height="{cap_height:.1f}"'
        f' data-units-per-em="{EM}">',
    ]
    for text, weight, commands in paths:
        lines.append(f'  <path id="word-{text.lower()}" data-weight="{weight}" d="{commands}"/>')
    lines.append("</svg>")

    out = Path(__file__).with_name("wordmark-outlines.svg")
    # newline="" suppresses Windows CRLF translation: the committed file has to be byte-identical
    # to a fresh run on any platform, or "regenerate and diff" stops being a usable check.
    with out.open("w", encoding="utf-8", newline="") as handle:
        handle.write("\n".join(lines) + "\n")
    print(f"wrote {out} (advance {advance:.1f}, cap height {cap_height:.1f})")


if __name__ == "__main__":
    main()
