#!/usr/bin/env node
/**
 * printable-keybinding-policy gate — tempdoc 864 Layer 4.
 *
 * THE POLICY
 * ----------
 *   A modifier-less PRINTABLE character may be a global keybinding only if it carries a `when`
 *   clause scoping it to named surfaces, and it is inert by default on any surface that owns a
 *   primary text input.
 *
 * The subject is the BEHAVIOUR, not the mechanism: it applies to any modifier-less printable
 * handled at `window` or `document` scope, however it is wired. Enforcement of that subject is
 * split, and the split is the honest part —
 *
 *   - **Registered bindings — this gate.** `registerKeybindingEntry({ key: '…' })` sites are
 *     enumerable in source, so a bare printable registered without a `when` fails the build here.
 *   - **Raw `window`/`document` listeners — REVIEW TIER (~70%), EXEMPT from this gate.** Search v3's
 *     `j`/`k` (`Sv3Main.ts` window listener) and `UnifiedChatView`'s are not registered bindings, so
 *     no `when` clause can reach them and no gate can see them today. They are governed by review
 *     plus the runtime guards 864 Layer 2 added (typing target, IME/repeat, modal ownership). Layer
 *     2(b1) — routing every global chord through the one dispatcher — is what would close this
 *     exemption and make the whole policy gate-enforceable; it is deferred, not done.
 *   - **Dynamically-keyed registrations** (`Shell.ts`'s plugin bridge passes a runtime `key`) are
 *     likewise unenumerable in source and out of this gate's reach — same review tier.
 *
 * Non-printable keys (Escape, Enter, arrows, F-keys) are out of scope: they are not typed into a
 * text field, so they do not steal characters from a reader who believes they are typing.
 *
 * Positive-coverage scan over the FE source: every `registerKeybindingEntry(...)` /
 * `registerKeybinding({...})` call whose `key` is a string literal is classified, and the printable
 * modifier-less ones must carry `when`.
 *
 * Self-test: `node scripts/ci/check-printable-keybinding-policy.test.mjs` drives {@link findViolations}
 * with a fixture pair (a bare `'j'` must fail; `'mod+j'`, a `when`-scoped key and a dynamic key must
 * pass), so the gate's BITE is asserted in the repo rather than reproduced by hand.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const ROOTS = ['modules/ui-web/src'];

/**
 * The EXEMPTING modifiers — deliberately the same set `KeybindingRegistry.attachKeybindingDispatcher`
 * treats as making a binding non-modifier-less: `!parsed.mod && !parsed.ctrl && !parsed.meta &&
 * !parsed.alt`. **`shift` is NOT in it, and that is the point** (864 review F3): the runtime does not
 * count Shift, so a `shift+/` binding is still dispatched against a reader who is typing `?`. A gate
 * that exempted Shift would bless exactly the bindings the runtime still treats as printable — two
 * answers to one question, which is the fork this tempdoc exists to end. Change one side and this
 * comment plus `KeybindingRegistry.editableGuard.test.ts`'s Shift case both have to move.
 */
const MODIFIERS = new Set(['mod', 'ctrl', 'control', 'cmd', 'meta', 'command', 'alt', 'option']);

const norm = (p) => p.replace(/\\/g, '/');

/**
 * Comments are stripped before anything is read (the tempdoc-698 precedent, shared helper), so a
 * commented-out `when:` cannot satisfy the policy and a doc-comment naming a bare key cannot trip it.
 */
const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'node_modules' || name === 'dist') continue;
      yield* walk(full);
    } else if (/\.ts$/.test(name) && !/\.test\.ts$/.test(name) && !/\.d\.ts$/.test(name)) {
      yield full;
    }
  }
}

/**
 * A key combo is in scope when it names no EXEMPTING modifier (see {@link MODIFIERS} — Shift is not
 * one) and its key is a single printable character: the class a reader can type into a text field
 * (`/`, `j`, `?`). `parseKey` in the registry splits on '+', so this mirrors it.
 */
export function isModifierlessPrintable(combo) {
  const parts = combo.toLowerCase().split('+').map((p) => p.trim());
  if (parts.some((p) => MODIFIERS.has(p))) return false;
  const key = parts[parts.length - 1] ?? '';
  return [...key].length === 1 && /[^\s\p{C}]/u.test(key);
}

/**
 * Pure per-source detection: read each `registerKeybinding…(` call's argument object by
 * brace-matching, then ask two questions of the literal — is the key a modifier-less printable, and
 * does the same literal carry `when`. Exported so the self-test can drive it with source strings
 * instead of a fixture tree on disk.
 */
export function findViolations(rawSrc, file = '<source>') {
  const violations = [];
  let scanned = 0;
  let printable = 0;
  if (!rawSrc.includes('registerKeybinding')) return { violations, scanned, printable };
  const src = stripComments(rawSrc);
  const re = /registerKeybinding(?:Entry)?\s*\(\s*\{/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const open = src.indexOf('{', m.index);
    let depth = 0;
    let end = open;
    for (; end < src.length; end++) {
      if (src[end] === '{') depth++;
      else if (src[end] === '}' && --depth === 0) break;
    }
    const literal = src.slice(open, end + 1);
    scanned++;
    const keyMatch = literal.match(/\bkey\s*:\s*(['"])([^'"]*)\1/);
    if (keyMatch === null) continue; // dynamic key — see the header's honest limit
    const combo = keyMatch[2];
    if (!isModifierlessPrintable(combo)) continue;
    printable++;
    if (/\bwhen\s*:/.test(literal)) continue;
    const line = src.slice(0, m.index).split('\n').length;
    violations.push({
      file,
      line,
      rule: 'unscoped-printable-binding',
      message:
        `${file}:${line}: the modifier-less printable binding '${combo}' has no \`when\` clause. ` +
        `Tempdoc 864 Layer 4: a bare printable is a character a reader can type, so it may only be ` +
        `global where a \`when\` clause names the surfaces it belongs to (e.g. ` +
        `"activeSurface == 'core.search-v3-surface'"). Scope it, or register it on the surface that ` +
        `owns it. Shift does NOT exempt a binding — the runtime dispatcher does not count Shift ` +
        `either.`,
    });
  }
  return { violations, scanned, printable };
}

/** Whole-tree scan. Rooted (`root`) so a fixture tree can be scanned instead of the repo. */
export function detect({ root = '.' } = {}) {
  const violations = [];
  let scanned = 0;
  let printable = 0;
  for (const rootDir of ROOTS) {
    const abs = resolve(root, rootDir);
    for (const file of walk(abs)) {
      const found = findViolations(readFileSync(file, 'utf8'), norm(relative(resolve(root), file)));
      violations.push(...found.violations);
      scanned += found.scanned;
      printable += found.printable;
    }
  }
  return { violations, scanned, printable };
}

// ── CLI ───────────────────────────────────────────────────────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const { violations, scanned, printable } = detect({});
  if (violations.length > 0) {
    console.error(
      '✗ printable-keybinding-policy gate FAILED:\n' + violations.map((x) => '  - ' + x.message).join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `✓ printable-keybinding-policy gate OK — ${scanned} registered binding(s) scanned, ${printable} ` +
      `modifier-less printable, all \`when\`-scoped (864 Layer 4). Raw window/document listeners are ` +
      `review-tier and deliberately out of this gate's reach.`,
  );
}
