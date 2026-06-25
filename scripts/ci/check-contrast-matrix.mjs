#!/usr/bin/env node
/**
 * check-contrast-matrix — tempdoc 576 §6 (rung 2). A headless contrast fitness function: resolves the
 * semantic role tokens from tokens.css per theme and asserts every INTENDED pairing clears WCAG AA:
 *   - on-grade:        accent-on-<role> legible ON accent-<role>     (text/icon sitting on the fill)
 *   - text-on-surface: text-<role> legible ON the app surface        (the #3 amber-as-text bug-class)
 * in BOTH the dark (`:root`) and light (`[data-theme="light"]`) themes. The role tokens are direct
 * `oklch()`/hex/rgb (hue via `var(--h-*)`, text base via `var(--p-text)`); `color-mix()` alpha-grades
 * are out of scope (not used by the role tokens). The oklch→sRGB transform is node-side here because
 * the FE resolves oklch via getComputedStyle (no pure-math need there), so there is no shared module
 * across the TS/MJS boundary — this gate owns the only place that needs the transform.
 *
 * Exit 0 when every pairing clears AA; exit 1 (with the offending pairings) otherwise.
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TOKENS_CSS = resolve(REPO_ROOT, 'modules/ui-web/src/styles/tokens.css');
const WCAG_AA = 4.5;

// --- colour math (node-side; the FE resolves oklch via the browser, so this isn't shared) ---
function oklchToRgb(L, C, H) {
  const hr = (H * Math.PI) / 180;
  const a = C * Math.cos(hr);
  const b = C * Math.sin(hr);
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  const lin2srgb = (v) => {
    const c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    return Math.max(0, Math.min(255, Math.round(c * 255)));
  };
  return [
    lin2srgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    lin2srgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    lin2srgb(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}
function parseColor(v) {
  const s = String(v).trim();
  let m = s.match(/^#([0-9a-f]{6})$/i);
  if (m) return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
  m = s.match(/^#([0-9a-f]{3})$/i);
  if (m) return [0, 1, 2].map((i) => parseInt(m[1][i] + m[1][i], 16));
  m = s.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i);
  if (m) return [Math.round(+m[1]), Math.round(+m[2]), Math.round(+m[3])];
  return null;
}
function resolveColor(raw, hues, prims) {
  if (raw == null) return null;
  let value = String(raw).trim();
  for (const [name, val] of Object.entries(prims)) value = value.replaceAll(`var(--${name})`, val);
  const direct = parseColor(value);
  if (direct) return direct;
  const mm = value.match(/^oklch\(\s*([\d.]+)%?\s+([\d.]+)\s+(.+?)\s*\)$/i);
  if (mm) {
    const L = parseFloat(mm[1]);
    const hueRaw = mm[3].trim();
    const hv = hueRaw.match(/^var\(\s*--(h-[\w-]+)\s*\)$/);
    const H = hv ? hues[hv[1]] : parseFloat(hueRaw);
    if (H === undefined || Number.isNaN(H)) return null;
    return oklchToRgb(value.includes('%') ? L / 100 : L, parseFloat(mm[2]), H);
  }
  return null;
}
function relLum([r, g, b]) {
  const f = (c) => {
    c /= 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}
function contrast(a, b) {
  const la = relLum(a), lb = relLum(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

// --- APCA Lc — an ADDITIONAL perceptual signal (tempdoc 576 §6 B4). WCAG-2 AA above stays the HARD
// floor; APCA is advisory only (it is not a W3C standard as of 2026, and mid-tone saturated hues that
// WCAG-2 under-penalizes are exactly where APCA's perceptual model is sharper — so it is a NOTE, never a
// gate failure). Faithful APCA-W3 0.1.9 (SAPC) maths: sRGB → screen luminance, soft-clamp, polarity. ---
function apcaY([r, g, b]) {
  const s = (c) => (c / 255) ** 2.4;
  return 0.2126729 * s(r) + 0.7151522 * s(g) + 0.0721750 * s(b);
}
function apcaLc(txt, bg) {
  let ytxt = apcaY(txt);
  let ybg = apcaY(bg);
  if (ytxt <= 0.022) ytxt += (0.022 - ytxt) ** 1.414;
  if (ybg <= 0.022) ybg += (0.022 - ybg) ** 1.414;
  if (Math.abs(ybg - ytxt) < 0.0005) return 0;
  let lc;
  if (ybg > ytxt) {
    const sapc = (ybg ** 0.56 - ytxt ** 0.57) * 1.14;
    lc = sapc < 0.1 ? 0 : (sapc - 0.027) * 100;
  } else {
    const sapc = (ybg ** 0.65 - ytxt ** 0.62) * 1.14;
    lc = sapc > -0.1 ? 0 : (sapc + 0.027) * 100;
  }
  return lc;
}
// Advisory floor: |Lc| >= 60 is APCA's threshold for normal-weight body-ish text. Below it is a NOTE.
const APCA_SOFT = 60;

// --- parse a CSS rule's custom properties (token blocks contain no nested braces) ---
function parseBlock(css, selectorRe) {
  const m = selectorRe.exec(css);
  if (!m) return {};
  const open = css.indexOf('{', m.index);
  const close = css.indexOf('}', open);
  const out = {};
  for (const line of css.slice(open + 1, close).split('\n')) {
    const d = line.match(/^\s*--([\w-]+)\s*:\s*([^;]+);/);
    if (d) out[d[1]] = d[2].trim();
  }
  return out;
}

const css = readFileSync(TOKENS_CSS, 'utf8');
const root = parseBlock(css, /:root\s*\{/);
const light = parseBlock(css, /\[data-theme="light"\]\s*\{/);
const hues = {};
for (const [k, v] of Object.entries(root)) if (k.startsWith('h-')) hues[k] = parseFloat(v);

const THEMES = {
  dark: { tokens: root, pText: root['p-text'] },
  light: { tokens: { ...root, ...light }, pText: light['p-text'] ?? root['p-text'] },
};
// 577 Phase 7 — `highlight` + `link` joined the role catalog (themeRoles.ts).
const ROLES = ['tint', 'command', 'chat', 'success', 'warning', 'danger', 'highlight', 'link'];

const pairs = [];
for (const theme of ['dark', 'light']) {
  // on-grade: the foreground sitting ON the accent fill.
  for (const r of ROLES) pairs.push({ theme, label: `on-${r}`, fg: `accent-on-${r}`, bg: `accent-${r}` });
  // text-on-surface: every role's text-grade token legible on the app surface (the #3 amber bug-class —
  // tempdoc 576 §6 rung-1). All roles now carry a `text-<role>` token.
  for (const r of ROLES) pairs.push({ theme, label: `text-${r}-on-surface`, fg: `text-${r}`, bg: 'surface-1' });
  // Tempdoc 596 §16.2 — the jf-control reason tooltip's OWN pairing: its text (`text-primary`) sits on the
  // elevated popover background (`surface-3`), not `surface-1`. Folding it into the matrix proves the
  // WCAG-1.4.13 reason surface clears AA in both themes (the §16.2 "fold the tooltip colours into the gate").
  pairs.push({ theme, label: 'tooltip-text-on-surface-3', fg: 'text-primary', bg: 'surface-3' });
}

const failures = [];
const apcaNotes = [];
for (const p of pairs) {
  const t = THEMES[p.theme];
  const prims = { 'p-text': t.pText };
  const fg = resolveColor(t.tokens[p.fg], hues, prims);
  const bg = resolveColor(t.tokens[p.bg], hues, prims);
  if (!fg || !bg) {
    failures.push(`${p.theme}/${p.label}: could not resolve (${p.fg}=${t.tokens[p.fg]}, ${p.bg}=${t.tokens[p.bg]})`);
    continue;
  }
  const ratio = contrast(fg, bg);
  if (ratio < WCAG_AA) {
    failures.push(`${p.theme}/${p.label}: ${ratio.toFixed(2)}:1 < AA ${WCAG_AA} (${p.fg} on ${p.bg})`);
  }
  // Additive APCA signal (never fails the gate).
  const lc = Math.abs(apcaLc(fg, bg));
  if (lc < APCA_SOFT) {
    apcaNotes.push(`${p.theme}/${p.label}: APCA Lc ${lc.toFixed(0)} < ${APCA_SOFT} (clears AA ${ratio.toFixed(2)}:1, but APCA-soft)`);
  }
}

// APCA is advisory — print the signal regardless of pass/fail, but it never changes the exit code.
if (apcaNotes.length > 0) {
  console.log(`check-contrast-matrix — ${apcaNotes.length} pairing(s) below the APCA Lc ${APCA_SOFT} advisory (signal only, not a floor):`);
  for (const n of apcaNotes) console.log(`  · ${n}`);
}

if (failures.length > 0) {
  console.error(`check-contrast-matrix FAIL — ${failures.length} role pairing(s) below WCAG AA (the hard floor):`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`check-contrast-matrix OK — all ${pairs.length} role pairings clear WCAG AA (hard floor) in both themes; APCA Lc reported as an additive signal.`);
