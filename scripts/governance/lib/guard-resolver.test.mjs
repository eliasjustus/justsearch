/**
 * Tests for the guard grammar + the §3.1 rung-1 exempt kind (tempdoc 576 §3.1):
 *   - parseGuards          — self/none-yet/absent collapse to []; exempt:<reason> parses to a token.
 *   - resolveGuardToken    — exempt resolves ONLY with a non-empty reason; gate: resolves vs registry.
 *   - verdictForInvalidGuardForm — bare self/none-yet/absent → fail; clean → pass.
 *
 * Run: `node scripts/governance/lib/guard-resolver.test.mjs` (exits non-zero on failure)
 */

import assert from 'node:assert/strict';

import { parseGuards, resolveGuardToken, guardStrength, detectGuardDowngradeEvents } from './guard-resolver.mjs';
import { verdictForInvalidGuardForm } from '../gates/register-guard-resolution/truth-table.mjs';

let passed = 0;
const failures = [];
function ok(label, cond) {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
}

// --- parseGuards: bare unguarded forms all collapse to [] (kept, so existing length===0 checks hold) ---
ok('self → []', parseGuards('self').length === 0);
ok('none-yet → []', parseGuards('none-yet').length === 0);
ok('absent → []', parseGuards(undefined).length === 0 && parseGuards('').length === 0);

// --- parseGuards: exempt parses to a single exempt token (not bare-unguarded) ---
const ex = parseGuards('exempt:canonical record type — nothing to guard');
ok('exempt parses to one token', ex.length === 1);
ok('exempt token kind is exempt', ex[0].kind === 'exempt');
ok('exempt token carries the reason', ex[0].value === 'canonical record type — nothing to guard');

// --- parseGuards: real guards still parse ---
const real = parseGuards('gate:wire + test:Foo');
ok('gate+test → two tokens', real.length === 2 && real[0].kind === 'gate' && real[1].kind === 'test');

// --- resolveGuardToken: exempt resolves only with a non-empty reason ---
const gateIds = new Set(['operation-surface', 'wire']);
ok('exempt with reason resolves', resolveGuardToken({ kind: 'exempt', value: 'opaque carrier' }, { root: '', gateIds }).resolved === true);
ok('exempt with empty reason does NOT resolve', resolveGuardToken({ kind: 'exempt', value: '' }, { root: '', gateIds }).resolved === false);
ok('exempt with whitespace reason does NOT resolve', resolveGuardToken({ kind: 'exempt', value: '   ' }, { root: '', gateIds }).resolved === false);

// --- resolveGuardToken: gate resolves against the registry id set ---
ok('gate:operation-surface resolves', resolveGuardToken({ kind: 'gate', value: 'operation-surface' }, { root: '', gateIds }).resolved === true);
ok('gate:nonesuch does not resolve', resolveGuardToken({ kind: 'gate', value: 'nonesuch' }, { root: '', gateIds }).resolved === false);

// --- verdictForInvalidGuardForm: bare-unguarded surfaces fail; clean passes ---
ok('invalid list → fail', verdictForInvalidGuardForm({ invalid: ['reg :: x (kind=source, guard=self)'] }).status === 'fail');
ok('empty invalid list → pass', verdictForInvalidGuardForm({ invalid: [] }).status === 'pass');

// --- guardStrength: real-guard 2 > exempt 1 > bare 0 (the §5 rung axis) ---
ok('gate: is strength 2', guardStrength('gate:wire') === 2);
ok('test: is strength 2', guardStrength('test:Foo') === 2);
ok('exempt: is strength 1', guardStrength('exempt:canonical type') === 1);
ok('self is strength 0', guardStrength('self') === 0);
ok('absent is strength 0', guardStrength(undefined) === 0);

// --- detectGuardDowngradeEvents: a baseline→HEAD strength DECREASE is a downgrade event ---
const base = [
  { id: 'a', guard: 'gate:wire' },      // 2
  { id: 'b', guard: 'exempt:reason' },  // 1
  { id: 'c', guard: 'test:Foo' },       // 2 (unchanged at head)
  { id: 'd', guard: 'self' },           // 0 (upgraded at head)
];
const head = [
  { id: 'a', guard: 'exempt:dropped the gate' }, // 2→1 DOWNGRADE
  { id: 'b', guard: 'self' },                    // 1→0 DOWNGRADE (also caught by invalid-guard-form)
  { id: 'c', guard: 'test:Foo' },                // unchanged
  { id: 'd', guard: 'gate:wire' },               // 0→2 upgrade (not a downgrade)
  { id: 'e', guard: 'exempt:new entry' },        // new (no baseline) — not a downgrade
];
const evSilent = detectGuardDowngradeEvents({ baselineSurfaces: base, headSurfaces: head, regRel: 'r', covered: false });
ok('detects exactly the two downgrades', evSilent.length === 2);
ok('downgrade events are raised + uncovered', evSilent.every((e) => e.raised === true && e.covered === false));
ok('downgrade names the silent rule', evSilent.every((e) => e.silentRuleId === 'register-guard-resolution/silent-guard-downgrade'));
const evCovered = detectGuardDowngradeEvents({ baselineSurfaces: base, headSurfaces: head, regRel: 'r', covered: true });
ok('covered flag propagates', evCovered.every((e) => e.covered === true));
ok('no baseline → no events', detectGuardDowngradeEvents({ baselineSurfaces: [], headSurfaces: head, regRel: 'r', covered: false }).length === 0);

if (failures.length > 0) {
  console.error(`guard-resolver.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`guard-resolver.test: all ${passed} checks passed`);
