#!/usr/bin/env node
/**
 * strip-token-fallbacks — C3 (re-scoped) codemod (tempdoc 557 §2.C).
 *
 * Rewrites `var(--x, <fallback>)` → `var(--x)` for DESIGN-TOKEN names (those
 * defined in tokens.css / palettes). The hardcoded fallback is provably dead:
 * every design token has a default (enforced by the theme-token-closure gate),
 * so the fallback never renders. Removing it kills the divergent SECOND source
 * of truth — the token's value lives only in tokens.css / the active palette
 * (the §2.C single-authority goal; aligns with the 548 "no subordinate
 * hand-authorable authority that can drift" thesis).
 *
 * var() stays LITERAL CSS (it is plain CSS text inside css`` / style="" — NOT a
 * JS interpolation; Lit's css tag rejects ${string} interpolation). Name-safety
 * is provided by the closure gate, not a JS accessor.
 *
 * Scope: only known design tokens (component-local vars are left alone). Skips
 * dynamic `var(--prefix-${…})` and nested `var(--x, var(--y))`.
 *
 * Usage:
 *   node scripts/ci/strip-token-fallbacks.mjs <dir-or-file> [...]   # rewrite
 *   node scripts/ci/strip-token-fallbacks.mjs --check <dir> [...]    # report only, exit 1 if any remain
 */
import { readFileSync, readdirSync, statSync, writeFileSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';

const TOKENS_CSS = 'modules/ui-web/src/styles/tokens.css';
const PALETTE_DIR = 'modules/ui-web/public/themes';

const names = new Set();
const DEF_RE = /(--[a-zA-Z0-9-]+)\s*:/g;
let mm;
const css = readFileSync(TOKENS_CSS, 'utf8');
while ((mm = DEF_RE.exec(css))) names.add(mm[1]);
if (existsSync(PALETTE_DIR)) {
  for (const e of readdirSync(PALETTE_DIR)) {
    if (extname(e) !== '.json') continue;
    try {
      const p = JSON.parse(readFileSync(join(PALETTE_DIR, e), 'utf8'));
      if (p?.tokens) for (const k of Object.keys(p.tokens)) names.add(k.startsWith('--') ? k : `--${k}`);
    } catch {
      /* skip */
    }
  }
}

const check = process.argv.includes('--check');
const args = process.argv.slice(2).filter((a) => a !== '--check');
if (args.length === 0) {
  console.error('usage: strip-token-fallbacks.mjs [--check] <dir-or-file> [...]');
  process.exit(2);
}

const files = [];
function collect(p) {
  const s = statSync(p);
  if (s.isDirectory()) {
    for (const e of readdirSync(p)) {
      if (e === 'node_modules' || e === 'generated') continue;
      collect(join(p, e));
    }
  } else if (
    ['.ts', '.tsx', '.css'].includes(extname(p)) &&
    !/\.(test|spec)\.tsx?$/.test(p) &&
    !p.includes('__fixtures__')
  ) {
    // Test/fixture files carry `var(--x, fallback)` inside EXPECTATION strings
    // (data, not live CSS) — stripping those would corrupt assertions.
    files.push(p);
  }
}
for (const a of args) collect(a);

// `var(--name, <fallback>)` — the fallback may contain ONE level of balanced
// parens (e.g. `rgba(…)`, `hsl(…)`, `color-mix(…)`), so the inner-paren
// alternation `\([^()]*\)` is required (a plain `[^()]*?` can't span them — the
// gap that left 34 `var(--known, rgba(…))` fallbacks unstripped). Nested-var and
// dynamic fallbacks are still skipped by the callback guards below.
const VAR_FB_RE = /var\(\s*(--[a-zA-Z0-9-]+)\s*,\s*((?:[^()]|\([^()]*\))*?)\s*\)/g;

let totalFiles = 0;
let totalRepl = 0;
const remaining = [];

for (const file of files) {
  const src = readFileSync(file, 'utf8');
  let count = 0;
  const next = src.replace(VAR_FB_RE, (full, name, fallback) => {
    if (!names.has(name)) return full; // component-local / unknown → leave
    if (fallback.includes('${')) return full; // dynamic fallback → leave
    if (fallback.includes('var(')) return full; // nested → leave (rare)
    count++;
    return `var(${name})`;
  });
  if (count === 0) continue;
  if (check) {
    remaining.push(`  ${file}: ${count} design-token fallback(s) remain`);
  } else {
    writeFileSync(file, next);
    console.log(`  ${file}: stripped ${count}`);
  }
  totalFiles++;
  totalRepl += count;
}

if (check) {
  if (remaining.length) {
    console.error(`strip-token-fallbacks --check FAIL: ${totalRepl} design-token fallback(s) remain:`);
    for (const r of remaining) console.error(r);
    console.error('Run: node scripts/ci/strip-token-fallbacks.mjs <dir>');
    process.exit(1);
  }
  console.log('strip-token-fallbacks OK — no design-token fallbacks remain.');
} else {
  console.log(`strip-token-fallbacks: stripped ${totalRepl} fallback(s) across ${totalFiles} file(s).`);
}
