#!/usr/bin/env node
/**
 * gen-text-tokens — tempdoc 576 §6 B2: derive every text-grade token from {hue, chroma, targetRatio}
 * per theme, so the legibility guarantee is RECOMPUTED, not a remembered hand-tuned constant.
 *
 * For each role × theme it binary-searches oklch LIGHTNESS (hue + chroma fixed per theme) to land at
 * (or just above) a per-theme target WCAG contrast ratio against the app surface (surface-1). The
 * derived `--text-<role>: rgb(...)` value is emitted; the contrast-matrix gate (the AA hard floor) is
 * the safety net. APCA Lc is reported as the additive signal (cf. check-contrast-matrix).
 *
 * Usage:
 *   node scripts/codegen/gen-text-tokens.mjs --compare   # print derived vs current (no write)
 *   node scripts/codegen/gen-text-tokens.mjs --check      # exit 1 if any committed token is below target
 *   node scripts/codegen/gen-text-tokens.mjs --emit       # print the derived token block
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TOKENS_CSS = resolve(REPO_ROOT, 'modules/ui-web/src/styles/tokens.css');

// --- oklch → sRGB + WCAG contrast (node-side; mirrors check-contrast-matrix's maths) ---
function oklchToRgb(L, C, H) {
  const h = (H * Math.PI) / 180;
  const a = Math.cos(h) * C;
  const b = Math.sin(h) * C;
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  let r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  let g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  let bl = -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s;
  const enc = (x) => {
    const c = x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055;
    return Math.max(0, Math.min(255, Math.round(c * 255)));
  };
  return [enc(r), enc(g), enc(bl)];
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
function rgbOf(raw, hues, pText) {
  if (!raw) return null;
  const v = raw.replace(/var\(--p-text\)/g, pText ?? '').trim();
  let mm = v.match(/^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i);
  if (mm) return [Number(mm[1]), Number(mm[2]), Number(mm[3])];
  if (/^#([0-9a-f]{6})$/i.test(v)) {
    const n = parseInt(v.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  mm = v.match(/^oklch\(\s*([\d.]+)%?\s+([\d.]+)\s+(.+?)\s*\)$/i);
  if (mm) {
    let H = mm[3].trim();
    const hv = H.match(/var\(--(h-[\w-]+)\)/);
    H = hv ? hues[hv[1]] : parseFloat(H);
    const L = v.includes('%') ? parseFloat(mm[1]) / 100 : parseFloat(mm[1]);
    return oklchToRgb(L, parseFloat(mm[2]), H);
  }
  return null;
}

// Roles to derive + the hue/chroma each text grade uses, per theme.
const ROLES = ['tint', 'command', 'chat', 'success', 'warning', 'danger', 'highlight', 'link', 'info'];
// `info` aliases `chat` (no own hue); skip deriving it — it tracks --text-chat.
const HUE_OF = { tint: 'h-teal', command: 'h-purple', chat: 'h-teal', success: 'h-green', warning: 'h-amber', danger: 'h-red', highlight: 'h-amber', link: 'h-blue' };
// Per-theme target ratio — a FLOOR comfortably above WCAG AA (4.5) and below the current hand-tuned
// minimums (dark ~7.4, light ~5.1), so --check guarantees every committed text grade clears the floor
// without forcing the well-tuned high-contrast values DOWN to the target (re-derivation would degrade
// e.g. dark warning 10.7:1 → 7:1; §6 B2 is "guarantee recomputed, not remembered", not "flatten").
const THEME_CFG = {
  dark: { targetRatio: 6.0, chroma: 0.11, lo: 0.55, hi: 0.95 },
  light: { targetRatio: 5.0, chroma: 0.13, lo: 0.2, hi: 0.6 },
};

const css = readFileSync(TOKENS_CSS, 'utf8');
const root = parseBlock(css, /:root\s*\{/);
const light = parseBlock(css, /\[data-theme="light"\]\s*\{/);
const hues = {};
for (const [k, v] of Object.entries(root)) if (k.startsWith('h-')) hues[k] = parseFloat(v);

function deriveFor(theme) {
  const tokens = theme === 'light' ? { ...root, ...light } : root;
  const pText = tokens['p-text'] ?? root['p-text'];
  const surface = rgbOf(tokens['surface-1'], hues, pText);
  const cfg = THEME_CFG[theme];
  const surfaceIsDark = relLum(surface) < 0.5;
  const out = {};
  for (const role of ROLES) {
    if (role === 'info') continue; // aliases chat
    const hueName = HUE_OF[role];
    const hueDeg = hues[hueName];
    if (hueDeg == null) continue;
    let lo = cfg.lo, hi = cfg.hi;
    let best = null;
    for (let i = 0; i < 40; i++) {
      const mid = (lo + hi) / 2;
      const rgb = oklchToRgb(mid, cfg.chroma, hueDeg);
      const c = contrast(rgb, surface);
      if (c >= cfg.targetRatio) {
        best = rgb;
        if (surfaceIsDark) hi = mid;
        else lo = mid;
      } else if (surfaceIsDark) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    if (best) out[role] = { rgb: best, ratio: contrast(best, surface) };
  }
  return { out, surface };
}

const mode = process.argv.includes('--check') ? 'check' : process.argv.includes('--emit') ? 'emit' : 'compare';
let failed = false;
let checked = 0;
for (const theme of ['dark', 'light']) {
  const tokens = theme === 'light' ? { ...root, ...light } : root;
  const pText = tokens['p-text'] ?? root['p-text'];
  const surface = rgbOf(tokens['surface-1'], hues, pText);
  const target = THEME_CFG[theme].targetRatio;
  console.log(`\n# ${theme} (target floor ${target}:1 on surface-1)`);

  if (mode === 'check') {
    // Recompute every committed text-grade token's contrast and enforce the per-theme target FLOOR.
    for (const role of ROLES) {
      if (role === 'info') continue; // aliases --text-chat (verified via chat)
      const raw = tokens[`text-${role}`];
      if (!raw) continue;
      const cur = rgbOf(raw, hues, pText);
      const ratio = cur ? contrast(cur, surface) : 0;
      checked++;
      if (ratio < target - 0.05) {
        console.error(`  ✗ --text-${role}: committed ${ratio.toFixed(2)}:1 < target floor ${target}:1`);
        failed = true;
      }
    }
    continue;
  }

  // --compare / --emit use the derivation (the apcach-style authoring capability for NEW tokens).
  const { out } = deriveFor(theme);
  for (const role of Object.keys(out)) {
    const d = out[role];
    const current = rgbOf(tokens[`text-${role}`], hues, pText);
    const curRatio = current ? contrast(current, surface) : 0;
    const derived = `rgb(${d.rgb.join(', ')})`;
    if (mode === 'compare') {
      console.log(`  --text-${role}: current ${curRatio.toFixed(2)}:1  ->  derived ${derived} ${d.ratio.toFixed(2)}:1`);
    } else {
      console.log(`  --text-${role}: ${derived}; /* derived ${d.ratio.toFixed(2)}:1 */`);
    }
  }
}
if (mode === 'check') {
  if (failed) {
    console.error('\ngen-text-tokens --check FAIL — a text-grade token is below its per-theme target floor.');
    process.exit(1);
  }
  console.log(`\ngen-text-tokens --check OK — all ${checked} committed text-grade tokens clear their per-theme target floor.`);
}
