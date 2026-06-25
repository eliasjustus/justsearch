#!/usr/bin/env node
/**
 * color-tokens gate — tempdoc 557 §2.C / P1d.
 *
 * Bans bare *colored* (non-neutral) color literals — `rgba(<hue>, …)` / `#hex`
 * — in production FE source. Accent colors must come from the alpha-graded
 * design tokens (--accent-{hue}-{08|16|30|45} or the base accent), so they are
 * theme-aware; a hardcoded accent literal is a token leak that won't follow the
 * theme/palette (the §2.C defect class).
 *
 * NOT banned (intentional, low-false-positive design):
 *  - Neutral black/white/gray literals (shadows, overlays, scrims) — they don't
 *    carry accent semantics; channel spread ≤ NEUTRAL_SPREAD.
 *  - Literals inside `var(--known, …)` fallbacks (handled by the strip gate).
 *  - Comment lines.
 *  - An explicit allowlist of files where raw color is legitimate (e.g. console
 *    `%c` styling in logging utils — not app CSS).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = 'modules/ui-web/src';
const NEUTRAL_SPREAD = 14; // max(r,g,b)-min(r,g,b) ≤ this ⇒ neutral (allowed)

// Files where raw color literals are legitimately NOT app CSS.
const ALLOW_FILES = new Set([
  'modules/ui-web/src/utils/logger.ts', // console %c styling, not css``
  'modules/ui-web/src/shell-v0/themes/designTokenTree.ts', // theme infra: example/default values
]);

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e); const s = statSync(p);
    if (s.isDirectory()) { if (e !== 'node_modules' && e !== 'generated') walk(p); }
    else if (['.ts', '.tsx'].includes(extname(p)) && !/\.(test|spec)\.tsx?$/.test(p) && !p.includes('__fixtures__')) files.push(p);
  }
})(SRC);

const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const RGB = /rgba?\([^)]*\)/g;
const VARFB = /var\(\s*--[a-zA-Z0-9-]+\s*,/;
function rgbOf(lit) {
  if (lit.startsWith('#')) { let h = lit.slice(1); if (h.length === 3) h = h.split('').map((c) => c + c).join(''); if (h.length < 6) return null; return [parseInt(h.slice(0,2),16), parseInt(h.slice(2,4),16), parseInt(h.slice(4,6),16)]; }
  const n = lit.match(/[\d.]+/g); return n && n.length >= 3 ? [+n[0], +n[1], +n[2]] : null;
}
const isNeutral = (rgb) => rgb && Math.max(...rgb) - Math.min(...rgb) <= NEUTRAL_SPREAD;

const failures = [];
for (const f of files) {
  const norm = f.replace(/\\/g, '/');
  if (ALLOW_FILES.has(norm)) continue;
  for (const raw of readFileSync(f, 'utf8').split('\n')) {
    const line = raw.trim();
    if (line.startsWith('//') || line.startsWith('*') || line.startsWith('/*')) continue;
    if (VARFB.test(line)) continue;
    for (const lit of [...(line.match(HEX) || []), ...(line.match(RGB) || [])]) {
      const rgb = rgbOf(lit);
      if (!rgb || isNeutral(rgb)) continue;
      failures.push(`${f}: bare colored literal \`${lit}\` — use an accent token (--accent-{hue}-{08|16|30|45} or base) so it's theme-aware (§2.C / P1d).`);
    }
  }
}

if (failures.length) {
  console.error(`color-tokens FAIL: ${failures.length} bare colored literal(s) (accent leak — won't follow the theme):`);
  for (const f of failures) console.error('  ' + f);
  process.exit(1);
}
console.log('color-tokens OK — no bare colored literals; accent colors come from the theme-aware tokens.');
