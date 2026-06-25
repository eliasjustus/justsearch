#!/usr/bin/env node
/**
 * 574 B5 one-shot codemod — fold raw `font-size: <length>` literals onto the --font-size-* scale.
 *
 * The single typographic authority is tokens.css `--font-size-{xs,sm,md,lg,xl}`; the §14 finding was
 * the size re-picked inline ~380×. This maps each raw rem/px value (in css`` blocks + inline styles
 * across the ui-web .ts tree) to the NEAREST scale token — the single-authority normalization. Values
 * far outside the scale are reported (not silently clamped-then-forgotten) so they can get a dedicated
 * token or be confirmed. `--font-size-*` references and keyword values (inherit/…) are skipped.
 *
 * One-shot: run once, review the report, then the `style-literal-ratchet` gate keeps font-size at the
 * post-migration floor. `--check` reports without writing.
 *
 * NOTE (574 critical-analysis F1): the live 574 tokenization ran with the original nearest-tie-break,
 * which on a tie picked the SMALLER step (12px→xs/11, 14px→sm/13). The `nearest()` below now rounds a
 * tie UP (12px→sm/13, 14px→md/15) so a future re-run CONSOLIDATES each tie with its nearest-larger
 * neighbour instead of splitting it down. The already-committed sites were a *valid* nearest-snap (both
 * neighbours are equidistant) and render correctly, so they were deliberately left in place rather than
 * re-churned across ~40 files (the risk outweighed a 1px move on a valid choice). See tempdoc 574 §22.B.
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = 'modules/ui-web/src';
const CHECK = process.argv.includes('--check');

const SCALE = [
  ['--font-size-xs', 0.6875],
  ['--font-size-sm', 0.8125],
  ['--font-size-md', 0.9375],
  ['--font-size-lg', 1.125],
  ['--font-size-xl', 1.375],
];

/** Map a raw length (rem or px) to the nearest scale token + the relative error. */
function nearest(remValue) {
  // 574 critical-analysis F1 — round-HALF-UP on a tie. A value equidistant between two steps
  // (e.g. 0.75rem/12px between xs=11 and sm=13, or 0.875rem/14px between sm=13 and md=15) snaps to
  // the LARGER step, so it CONSOLIDATES with its nearest-larger neighbour (12→sm joins the 13px sm
  // cluster; 14→md joins 15px md) instead of splitting it off downward. `<=` makes the later
  // (larger, since SCALE is ascending) step win ties.
  let best = SCALE[0];
  let bestErr = Infinity;
  for (const entry of SCALE) {
    const err = Math.abs(entry[1] - remValue);
    if (err <= bestErr) {
      bestErr = err;
      best = entry;
    }
  }
  return { token: best[0], rel: bestErr / best[1] };
}

const files = [];
(function walk(d) {
  for (const e of readdirSync(d)) {
    const p = join(d, e).replace(/\\/g, '/');
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
      files.push(p);
    }
  }
})(SRC);

let replaced = 0;
const outliers = [];
const FONT_SIZE = /font-size:\s*(\d*\.?\d+)(rem|px)/g;

for (const f of files) {
  const orig = readFileSync(f, 'utf8');
  const next = orig.replace(FONT_SIZE, (m, num, unit) => {
    const rem = unit === 'px' ? Number(num) / 16 : Number(num);
    const { token, rel } = nearest(rem);
    if (rel > 0.12) outliers.push(`${f.slice(SRC.length + 1)}: ${m} → ${token} (rel ${(rel * 100) | 0}%)`);
    replaced++;
    return `font-size: var(${token})`;
  });
  if (next !== orig && !CHECK) writeFileSync(f, next);
}

console.log(`${CHECK ? '[check] ' : ''}font-size codemod — ${replaced} literals → --font-size-* tokens.`);
if (outliers.length) {
  console.log(`\n${outliers.length} value(s) >12% from their nearest token (review for a dedicated token):`);
  for (const o of outliers) console.log('  • ' + o);
}
