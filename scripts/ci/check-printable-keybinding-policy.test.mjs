/**
 * Self-test for the printable-keybinding-policy gate (tempdoc 864 Layer 4).
 *
 * A gate is only worth its line in the recipe if it BITES. The pair below is the fixture the 864
 * review had to reproduce by hand: a bare `'j'` registration must fail, and the shapes that are
 * legitimately out of scope must not. The Shift case is review finding F3 — the runtime dispatcher's
 * modifier-less test is `!mod && !ctrl && !meta && !alt`, so Shift does NOT make a printable
 * non-printable, and the gate must agree or it blesses bindings the runtime still dispatches over a
 * typing reader.
 *
 * Run: `node scripts/ci/check-printable-keybinding-policy.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { findViolations, isModifierlessPrintable } from './check-printable-keybinding-policy.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

const reg = (body) => `registerKeybindingEntry({\n${body}\n});\n`;
const fails = (src) => findViolations(src, 'f.ts').violations.length === 1;
const clean = (src) => findViolations(src, 'f.ts').violations.length === 0;

// ── the bite ──────────────────────────────────────────────────────────────────
ok(
  'FAILS: a bare printable with no `when`',
  fails(reg("  key: 'j',\n  commandId: 'x',\n  source: 'default',\n  provenance: P,")),
);
ok(
  'FAILS: Shift does not exempt — the runtime does not count Shift either (F3)',
  fails(reg("  key: 'shift+/',\n  commandId: 'x',\n  source: 'default',\n  provenance: P,")),
);
ok(
  'FAILS: the violation names the file, the line and the key',
  (() => {
    const [v] = findViolations(reg("  key: '/',\n  commandId: 'x',"), 'chrome/Shell.ts').violations;
    return v.file === 'chrome/Shell.ts' && v.line === 1 && v.message.includes("'/'");
  })(),
);

// ── the shapes that must NOT trip it ──────────────────────────────────────────
ok('PASSES: a chorded binding', clean(reg("  key: 'mod+j',\n  commandId: 'x',")));
ok('PASSES: alt/ctrl/meta chords', clean(reg("  key: 'alt+j',") + reg("  key: 'ctrl+k',")));
ok(
  'PASSES: a `when`-scoped printable',
  clean(reg("  key: '?',\n  when: \"activeSurface == 'core.search-v3-surface'\",")),
);
ok('PASSES: a non-printable key (Escape/F2/arrows are out of scope)', clean(reg("  key: 'f2',")));
ok('PASSES: a dynamic key the scan cannot classify', clean('registerKeybindingEntry({ key, commandId, source: 3 });'));
ok('PASSES: a file that registers nothing', clean('const key = "j"; // not a registration\n'));

// ── comments cannot satisfy or trip it (F5, the 698 precedent) ────────────────
ok(
  'FAILS: a commented-out `when:` does not satisfy the policy',
  fails(reg("  key: 'j',\n  // when: \"activeSurface == 'core.search-v3-surface'\",\n  commandId: 'x',")),
);
ok(
  'FAILS: a block-commented `when:` does not satisfy it either',
  fails(reg("  key: 'j',\n  /* when: \"activeSurface == 'x'\", */\n  commandId: 'x',")),
);
ok(
  'PASSES: a doc-comment DESCRIBING a bare binding is not a registration',
  clean("/**\n * registerKeybindingEntry({ key: 'j', commandId: 'x' }) would be a violation.\n */\nconst x = 1;\n"),
);

// ── the classifier itself ─────────────────────────────────────────────────────
ok('classifier: bare letters and punctuation are printable', isModifierlessPrintable('j') && isModifierlessPrintable('/'));
ok('classifier: shift+printable stays printable', isModifierlessPrintable('shift+?'));
ok('classifier: the four exempting modifiers exempt', ['mod+j', 'ctrl+j', 'cmd+j', 'alt+j'].every((k) => !isModifierlessPrintable(k)));
ok('classifier: multi-character keys are not printable', !isModifierlessPrintable('escape') && !isModifierlessPrintable('arrowleft'));

if (failures.length > 0) {
  console.error(`✗ check-printable-keybinding-policy self-test: ${failures.length} failed\n  - ${failures.join('\n  - ')}`);
  process.exit(1);
}
console.log(`✓ check-printable-keybinding-policy self-test: ${passed} assertions pass (the gate bites, and only where it should).`);
