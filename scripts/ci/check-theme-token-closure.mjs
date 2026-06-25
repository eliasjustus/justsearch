#!/usr/bin/env node
/**
 * theme-token-closure gate — tempdoc 557 §2.C / §3.
 *
 * Every CSS custom property referenced via `var(--x ...)` anywhere in
 * modules/ui-web/src must be DEFINED somewhere (tokens.css, a component
 * `:host`/`static styles` block, or a theme palette). A referenced-but-
 * undefined "ghost" token silently resolves to its hardcoded fallback,
 * which is why every non-Dark theme broke before this gate (the §2.C bug:
 * --surface-secondary etc. used in ~40 files, defined nowhere).
 *
 * Exit non-zero (fail) if any referenced token is defined nowhere.
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';

const SRC = process.argv[2] || 'modules/ui-web/src';
const PALETTE_DIR = 'modules/ui-web/public/themes';

const files = [];
(function walk(dir) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    const s = statSync(p);
    if (s.isDirectory()) {
      if (e === 'node_modules' || e === 'generated') continue;
      walk(p);
    } else if (['.ts', '.tsx', '.css'].includes(extname(p))) {
      files.push(p);
    }
  }
})(SRC);

const DEF_RE = /(--[a-zA-Z0-9-]+)\s*:/g; // definition site: `--name:`
const REF_RE = /var\(\s*(--[a-zA-Z0-9-]+)/g; // reference site: `var(--name`
const defined = new Set();
const referenced = new Map(); // name -> example file

for (const f of files) {
  const src = readFileSync(f, 'utf8');
  let m;
  while ((m = DEF_RE.exec(src))) defined.add(m[1]);
  while ((m = REF_RE.exec(src))) {
    // Skip partial captures from dynamic `var(--prefix-${expr})` references —
    // a trailing '-' means the real token name is composed at runtime.
    if (m[1].endsWith('-')) continue;
    if (!referenced.has(m[1])) referenced.set(m[1], f);
  }
}

// Theme palettes (JSON DesignTokenTrees) may define tokens applied at runtime.
// DesignTokenTree.tokens keys are BARE (no `--` prefix), e.g. "accent-tint" — so
// parse the tokens object and add the `--`-prefixed name (mirrors
// gen-token-names.mjs / strip-token-fallbacks.mjs). A future palette that
// introduces a token ONLY in JSON would otherwise be a false ghost.
if (existsSync(PALETTE_DIR)) {
  for (const e of readdirSync(PALETTE_DIR)) {
    if (extname(e) !== '.json') continue;
    try {
      const parsed = JSON.parse(readFileSync(join(PALETTE_DIR, e), 'utf8'));
      if (parsed && typeof parsed.tokens === 'object' && parsed.tokens) {
        for (const k of Object.keys(parsed.tokens)) {
          defined.add(k.startsWith('--') ? k : `--${k}`);
        }
      }
    } catch {
      // Non-DesignTokenTree JSON (manifest, schema) — skip.
    }
  }
}

const ghosts = [...referenced.keys()].filter((n) => !defined.has(n)).sort();
if (ghosts.length) {
  console.error(
    `theme-token-closure FAIL: ${ghosts.length} referenced token(s) defined NOWHERE (ghost → silent fallback, breaks non-Dark themes):`,
  );
  for (const g of ghosts) console.error(`  ${g}   (e.g. ${referenced.get(g)})`);
  process.exit(1);
}

// ── C2: base-totality ──────────────────────────────────────────────────────
// Every DESIGN token a component references must resolve in the UNCONDITIONAL
// base `:root` of tokens.css — not only in a `[data-theme="light"]` (or other
// conditional) block. A token defined only in a variant block is undefined in
// the default theme → invisible content (the inverse of the original §2.C bug).
// tokens.css is one-statement/brace-per-line, so a line-based block tracker is
// reliable here. Limitation (no current violation): a GROUPED base selector like
// `:root, [data-theme="light"] {` would be missed (the innerSelector exact-match
// is `:root`, not the grouped string) — keep base token defs in a plain `:root`
// block, not grouped with a variant selector.
const TOKENS_CSS = 'modules/ui-web/src/styles/tokens.css';
const cssDefined = new Set(); // any tokens.css def (any selector)
const baseRoot = new Set(); // defs in an unconditional `:root` block
{
  const stack = []; // block-header stack (selector / at-rule text before `{`)
  for (const raw of readFileSync(TOKENS_CSS, 'utf8').split('\n')) {
    const line = raw.trim();
    if (line.endsWith('{')) {
      stack.push(line.slice(0, -1).trim());
      continue;
    }
    if (line === '}') {
      stack.pop();
      continue;
    }
    const defs = [...line.matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)];
    if (defs.length === 0) continue;
    const inConditional = stack.some((h) => h.startsWith('@media') || h.startsWith('@supports'));
    const innerSelector = [...stack].reverse().find((h) => !h.startsWith('@'));
    for (const d of defs) {
      cssDefined.add(d[1]);
      if (!inConditional && innerSelector === ':root') baseRoot.add(d[1]);
    }
  }
}
const missingBase = [...referenced.keys()]
  .filter((n) => cssDefined.has(n) && !baseRoot.has(n))
  .sort();
if (missingBase.length) {
  console.error(
    `theme-token-closure FAIL: ${missingBase.length} referenced design token(s) are defined only in a ` +
      `conditional/variant block, NOT the unconditional base \`:root\` — undefined in the default theme:`,
  );
  for (const g of missingBase) console.error(`  ${g}   (e.g. ${referenced.get(g)})`);
  process.exit(1);
}

console.log(
  `theme-token-closure OK — ${referenced.size} referenced tokens defined (no ghosts); ` +
    `all ${[...referenced.keys()].filter((n) => cssDefined.has(n)).length} referenced design tokens have a base :root default.`,
);
