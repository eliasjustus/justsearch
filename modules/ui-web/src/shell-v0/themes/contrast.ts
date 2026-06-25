// SPDX-License-Identifier: Apache-2.0
/**
 * contrast — Tempdoc 567 §8 / A2 — the WCAG contrast utility behind semantic colour ROLES.
 *
 * A "role" is a (background, foreground) pair whose foreground is auto-derived to meet a contrast
 * floor against the authored background — so a custom theme can never produce unreadable text by
 * construction (the 558 §2 vision: fg derived from bg + a contrast target).
 *
 * Pure math, no DOM. Colours are parsed from the formats the theme system actually carries: hex
 * (`#rgb`/`#rrggbb`/`#rrggbbaa`), `rgb()/rgba()`, and the bare `r, g, b` channel triplet used by the
 * `p-*` primitives. For tokens authored as `oklch()` (the `accent-*` family), resolve them to `rgb()`
 * first via `getComputedStyle` and pass that — this module intentionally does not re-implement the
 * oklch→sRGB transform (the browser already does it).
 */

export type Rgb = readonly [number, number, number];

/** Parse a CSS colour string into an [r,g,b] triple (0–255), or null if unparseable. */
export function parseColor(value: string): Rgb | null {
  const v = value.trim();
  if (v === '') return null;

  // #rgb / #rgba / #rrggbb / #rrggbbaa
  const hex = v.match(/^#([0-9a-f]{3,8})$/i);
  if (hex) {
    const h = hex[1] ?? '';
    if (h.length === 3 || h.length === 4) {
      const r = h.charAt(0);
      const g = h.charAt(1);
      const b = h.charAt(2);
      return [parseInt(r + r, 16), parseInt(g + g, 16), parseInt(b + b, 16)];
    }
    if (h.length === 6 || h.length === 8) {
      return [
        parseInt(h.slice(0, 2), 16),
        parseInt(h.slice(2, 4), 16),
        parseInt(h.slice(4, 6), 16),
      ];
    }
    return null;
  }

  // rgb(...) / rgba(...)
  const fn = v.match(/^rgba?\(([^)]+)\)$/i);
  const body = fn?.[1] ?? v; // also accept a bare "r, g, b" / "r g b" triplet (the p-* format)
  const parts = body.split(/[,\s/]+/).filter((s) => s !== '');
  if (parts.length >= 3) {
    const [n0, n1, n2] = parts
      .slice(0, 3)
      .map((p) => (p.endsWith('%') ? Math.round((parseFloat(p) / 100) * 255) : parseFloat(p)));
    if (
      n0 !== undefined &&
      n1 !== undefined &&
      n2 !== undefined &&
      Number.isFinite(n0) &&
      Number.isFinite(n1) &&
      Number.isFinite(n2)
    ) {
      return [clamp255(n0), clamp255(n1), clamp255(n2)];
    }
  }
  return null;
}

function clamp255(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)));
}

/** WCAG relative luminance (0–1) of an [r,g,b] colour. */
export function relativeLuminance([r, g, b]: Rgb): number {
  const lin = (c8: number): number => {
    const c = c8 / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

/** WCAG contrast ratio (1–21) between two colours. Order-independent. */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const hi = Math.max(la, lb);
  const lo = Math.min(la, lb);
  return (hi + 0.05) / (lo + 0.05);
}

/** WCAG floors. AA body text = 4.5:1; AAA body = 7:1. */
export const WCAG_AA = 4.5;
export const WCAG_AAA = 7;

/**
 * APCA Lc — a perceptual contrast signal (faithful APCA-W3 0.1.9 / SAPC), the SAME maths the
 * check-contrast-matrix gate uses (tempdoc 576 §6 B4). Returns the signed Lightness contrast; its
 * magnitude is what matters. APCA penalises the mid-tone saturated hues WCAG-2 under-scores — so it is
 * the right signal for "this clears WCAG-AA but is still hard to read" (the #3 amber-as-text bug class).
 * NOT a W3C standard (as of 2026) → a SIGNAL, never the sole floor; WCAG-AA stays the hard gate.
 */
export function apcaLc(text: Rgb, bg: Rgb): number {
  const y = ([r, g, b]: Rgb): number =>
    0.2126729 * (r / 255) ** 2.4 + 0.7151522 * (g / 255) ** 2.4 + 0.072175 * (b / 255) ** 2.4;
  let ytxt = y(text);
  let ybg = y(bg);
  if (ytxt <= 0.022) ytxt += (0.022 - ytxt) ** 1.414;
  if (ybg <= 0.022) ybg += (0.022 - ybg) ** 1.414;
  if (Math.abs(ybg - ytxt) < 0.0005) return 0;
  let lc: number;
  if (ybg > ytxt) {
    const sapc = (ybg ** 0.56 - ytxt ** 0.57) * 1.14;
    lc = sapc < 0.1 ? 0 : (sapc - 0.027) * 100;
  } else {
    const sapc = (ybg ** 0.65 - ytxt ** 0.62) * 1.14;
    lc = sapc > -0.1 ? 0 : (sapc + 0.027) * 100;
  }
  return lc;
}

/** APCA advisory floor: |Lc| >= 60 ≈ APCA's threshold for normal-weight body text. Below it = weak. */
export const APCA_SOFT = 60;

export interface DerivedForeground {
  /** The chosen foreground colour as `#rrggbb`. */
  readonly fg: string;
  /** The contrast ratio it achieves against the background. */
  readonly ratio: number;
  /** Whether {@link ratio} meets the requested floor. */
  readonly meets: boolean;
}

/**
 * Derive a readable foreground for {@code bg}: pick pure black or white — whichever yields the higher
 * contrast — and report the achieved ratio + whether it clears {@code floor}. Black/white is the
 * maximal-contrast choice for any background, so if neither meets the floor the background itself is
 * the problem (too mid-tone) and the caller should surface a warning.
 */
export function deriveForeground(bg: Rgb, floor: number = WCAG_AA): DerivedForeground {
  const white: Rgb = [255, 255, 255];
  const black: Rgb = [0, 0, 0];
  const cw = contrastRatio(bg, white);
  const cb = contrastRatio(bg, black);
  const useWhite = cw >= cb;
  const ratio = useWhite ? cw : cb;
  return { fg: useWhite ? '#ffffff' : '#000000', ratio, meets: ratio >= floor };
}

/** Round a contrast ratio for display, e.g. 12.345 → "12.3". */
export function formatRatio(ratio: number): string {
  return ratio.toFixed(1);
}

function toHex([r, g, b]: Rgb): string {
  const h = (n: number): string => clamp255(n).toString(16).padStart(2, '0');
  return `#${h(r)}${h(g)}${h(b)}`;
}

/** Linear interpolation between two colours in sRGB, fraction t∈[0,1] (t=0 → a, t=1 → b). */
function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

/**
 * Tempdoc 567 §8 (deferred → built) — derive a TINTED foreground that carries the background's own hue
 * instead of the flat black/white {@link deriveForeground} returns — the "of the same hue family" ink a
 * designer would pick (dark-teal text on a teal fill), the fix for the "plainer, role-blind UI" symptom.
 *
 * Correct-by-construction, and deliberately NOT an oklch-lightness search: this module's contract is to
 * stay in sRGB and never re-implement the oklch→sRGB transform (see the file header). The tint is a
 * straight sRGB blend FROM the maximal-contrast extreme (the black/white {@link deriveForeground} picks)
 * TOWARD the background. Contrast is monotonic along that segment — each channel moves linearly between
 * the extreme and the background, and WCAG relative luminance is monotonic per channel — so a binary
 * search finds the most-tinted foreground that still clears {@code tintFloor}.
 *
 * Self-regulating safety: tinting is applied ONLY when the plain extreme already clears the (higher)
 * {@code tintFloor} — i.e. only where there is contrast headroom to spend. On a mid-tone background where
 * even black/white can't reach {@code tintFloor}, it returns the plain maximal-contrast foreground
 * unchanged. So a tinted result is ALWAYS ≥ {@code tintFloor} (AAA by default) and a non-tinted result is
 * the safest possible — the contrast floor is never traded away for the tint.
 */
export function deriveTintedForeground(
  bg: Rgb,
  floor: number = WCAG_AA,
  tintFloor: number = WCAG_AAA,
): DerivedForeground {
  const plain = deriveForeground(bg, floor);
  // Only tint where the plain extreme has headroom above the (higher) tint floor; otherwise keep the
  // maximal-contrast choice — never spend the floor itself on a tint.
  if (plain.ratio < tintFloor) return plain;
  const anchor: Rgb = plain.fg === '#ffffff' ? [255, 255, 255] : [0, 0, 0];
  // Binary-search the largest blend fraction toward bg whose contrast still clears tintFloor. lo always
  // satisfies (starts at the anchor, contrast = plain.ratio ≥ tintFloor); hi always fails (or is bg).
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 24; i++) {
    const mid = (lo + hi) / 2;
    if (contrastRatio(bg, mix(anchor, bg, mid)) >= tintFloor) lo = mid;
    else hi = mid;
  }
  const fg = mix(anchor, bg, lo);
  const ratio = contrastRatio(bg, fg);
  return { fg: toHex(fg), ratio, meets: ratio >= floor };
}
