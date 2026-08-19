/**
 * Tests for the contrast matrix's palette resolution (tempdoc 853, remediation 2).
 *
 * The gate used to see two palettes; it now sees four, and the two new ones INHERIT most of their
 * tokens from the palette beneath them. That makes resolution — not the colour maths — the part that
 * can be silently wrong: resolve too little and the gate reports gaps that do not exist; resolve the
 * layers in the wrong order and it checks the wrong colour and passes when it should fail. These
 * assertions pin the three cases that distinguish those outcomes, plus a constructed sub-AA HC pair
 * that proves the extension can actually FAIL (an unfailable gate is the defect being repaired).
 *
 * Run: `node scripts/ci/check-contrast-matrix.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  resolvePalettes,
  resolvePair,
  buildPairs,
  evaluatePairs,
  PALETTES,
  ROLES,
} from './check-contrast-matrix.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const TOKENS_CSS = resolve(REPO_ROOT, 'modules/ui-web/src/styles/tokens.css');

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
const eq = (label, actual, expected) => {
  try {
    assert.deepEqual(actual, expected, `${label} — got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

// --- 1. The real tree ---------------------------------------------------------------------------
const real = resolvePalettes(readFileSync(TOKENS_CSS, 'utf8'));
const colorOf = (palette, token) => resolvePair(real[palette], { fg: token, bg: token }).fg;

ok('all four palettes resolve', PALETTES.every((p) => Object.keys(real[p].tokens).length > 0));

// HC-DECLARED tokens are checked at their HC values, not the base palette's.
// `[data-theme="light"].high-contrast` sets `--surface-1: #f5f5f5`; plain light sets rgb(248,249,252).
eq('hc-light surface-1 takes the HC declaration', colorOf('hc-light', 'surface-1'), [245, 245, 245]);
ok('…which is NOT the light palette value', String(colorOf('light', 'surface-1')) !== String(colorOf('hc-light', 'surface-1')));
// `.high-contrast` sets `--text-primary: #ffffff`; hc-light's own block sets `#000000`.
eq('hc-light text-primary takes the light-HC declaration', colorOf('hc-light', 'text-primary'), [0, 0, 0]);
eq('hc-dark text-primary takes the dark-HC declaration', colorOf('hc-dark', 'text-primary'), [255, 255, 255]);

// HC-INHERITED tokens are checked at the inherited value. Neither HC block declares any role token,
// so every `text-<role>` / `accent-<role>` must resolve to the palette beneath it — this is what
// stops the gate inventing "could not resolve" failures for the 2 x 17 inherited pairings.
for (const r of ROLES) {
  eq(`hc-dark text-${r} inherits from :root`, colorOf('hc-dark', `text-${r}`), colorOf('dark', `text-${r}`));
  eq(`hc-light accent-${r} inherits from the light theme`, colorOf('hc-light', `accent-${r}`), colorOf('light', `accent-${r}`));
}
// `--surface-1` is redeclared by hc-light but NOT by hc-dark, so hc-dark must fall through to :root.
eq('hc-dark surface-1 inherits from :root', colorOf('hc-dark', 'surface-1'), colorOf('dark', 'surface-1'));
// Neither HC block declares `--surface-3`, so both fall through to their own base theme.
eq('hc-light surface-3 inherits from the light theme', colorOf('hc-light', 'surface-3'), colorOf('light', 'surface-3'));

// The layer that is easy to drop: `.high-contrast` also applies in light+HC (same specificity as
// `[data-theme="light"]`, declared later and unlayered), so a token only the DARK HC block declares
// wins over the light theme's. `--text-ghost` is exactly that token.
eq('hc-light text-ghost comes from the dark HC block, not the light theme', colorOf('hc-light', 'text-ghost'), [102, 102, 102]);

// The shipped tree is green, and the matrix really did grow.
const realPairs = buildPairs();
eq('matrix covers every palette x pairing', realPairs.length, PALETTES.length * (ROLES.length * 2 + 1));
const realResult = evaluatePairs(real, realPairs);
eq('tokens.css clears WCAG AA in all four palettes', realResult.failures, []);

// --- 2. A constructed sub-AA HC pair must FAIL ---------------------------------------------------
const SYNTHETIC = `
:root {
  --p-text: 255, 255, 255;
  --surface-1: #ffffff;
  --text-primary: #000000;
}
[data-theme="light"] {
  --p-text: 15, 23, 42;
  --surface-1: #ffffff;
  --text-primary: #111111;
  --text-ghost: #cccccc;
}
.high-contrast {
  --text-primary: #999999;
}
[data-theme="light"].high-contrast,
[data-theme="light"] .high-contrast {
  --surface-1: #f5f5f5;
}
`;
const synth = resolvePalettes(SYNTHETIC);
const probe = (theme) => ({ theme, label: 'probe', fg: 'text-primary', bg: 'surface-1' });

const darkProbe = evaluatePairs(synth, [probe('dark')]);
eq('the base palette passes on the same pairing', darkProbe.failures, []);

const hcDarkProbe = evaluatePairs(synth, [probe('hc-dark')]);
ok('a sub-AA HC pairing FAILS the gate', hcDarkProbe.failures.length === 1);
ok('…and the message names the palette and the ratio', /^hc-dark\/probe: 2\.\d\d:1 < AA 4\.5/.test(hcDarkProbe.failures[0] ?? ''));
// The failing pairing's background is INHERITED (`--surface-1` from `:root`) — so this also proves a
// failure can be composed from one HC-declared and one inherited token, which is the shape F-07 had.
eq('the failing pair composed an HC fg with an inherited bg', resolvePair(synth['hc-dark'], probe('hc-dark')), {
  fg: [153, 153, 153],
  bg: [255, 255, 255],
});

// The hc-light chain, on colours chosen so each layer is distinguishable.
eq('hc-light fg comes from `.high-contrast` (layer 3), beating the light theme', resolvePair(synth['hc-light'], probe('hc-light')).fg, [153, 153, 153]);
eq('hc-light bg comes from `[data-theme="light"].high-contrast` (layer 4)', resolvePair(synth['hc-light'], probe('hc-light')).bg, [245, 245, 245]);
eq('hc-light inherits text-ghost from the light theme when no HC block declares it', resolvePair(synth['hc-light'], { fg: 'text-ghost', bg: 'text-ghost' }).fg, [204, 204, 204]);

if (failures.length > 0) {
  console.error(`check-contrast-matrix.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`check-contrast-matrix.test: all ${passed} checks passed`);
