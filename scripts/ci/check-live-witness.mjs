#!/usr/bin/env node
/**
 * live-witness register-integrity check — tempdoc 560 §4b/§5 / ADR-0042.
 *
 * Consumer-presence has three tiers (see governance/live-witness.v1.json): the static
 * `consumer-presence` gate, the static AGENT-channel `runtime-witness` gate, and the LIVE-REGISTRY
 * witness this register names. The live witness is enforced by a backend live-registry test — the only
 * tier that sees runtime composition, since both static tiers are structurally blind to it (DR-A). An
 * offline gate therefore cannot run the witness itself. What it CAN do — and what this script does,
 * mirroring the tempdoc 575 in-flight-liveness early-warning — is keep the authority from being
 * silently deleted or forked:
 *
 *   (1) the authority class exists and declares the authority symbol;
 *   (2) the enforcing test exists and references the authority symbol (the teeth are wired);
 *   (3) the reused build-tier merge exists and declares its symbol (the witness is NOT a second
 *       authority — it reuses the one consumer-presence merge).
 *
 * Early-warning, the FE/offline import-register ceiling (565 §12.10): it cannot prove the live test
 * still ASSERTS the invariant, only that the wiring is present. The invariant itself is the green
 * `:modules:app-services:test --tests *LiveWitnessTest*` run.
 *
 * Usage: `node scripts/ci/check-live-witness.mjs` (from the repo root). Exit 0 = OK, 1 = drift.
 */

import { existsSync, readFileSync } from 'node:fs';

const REGISTER = 'governance/live-witness.v1.json';

function fail(messages) {
  console.error('live-witness register-integrity: FAIL\n  - ' + messages.join('\n  - '));
  process.exit(1);
}

if (!existsSync(REGISTER)) {
  fail([`register missing: ${REGISTER}`]);
}

const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));
const failures = [];

/** Assert a registered file exists and contains a required token; collect failures. */
function requireFileContains(role, rel, token) {
  if (!rel) {
    failures.push(`${role}: register has no path`);
    return;
  }
  if (!existsSync(rel)) {
    failures.push(`${role}: file missing — ${rel}`);
    return;
  }
  if (token) {
    const src = readFileSync(rel, 'utf8');
    if (!src.includes(token)) {
      failures.push(`${role}: ${rel} does not reference '${token}'`);
    }
  }
}

// (1) the authority class declares the authority symbol.
requireFileContains('authority', reg.authority?.class, reg.authority?.symbol);

// (2) the enforcing test exists and references the authority symbol (the teeth are wired).
const authoritySimpleName = (reg.authority?.class ?? '').split('/').pop()?.replace(/\.java$/, '');
requireFileContains('enforcingTest', reg.enforcingTest, authoritySimpleName);

// (3) the reused build-tier merge exists and declares its symbol (no-fork: the witness reuses it).
requireFileContains('reuses', reg.reuses?.class, reg.reuses?.symbol);

if (failures.length > 0) {
  fail(failures);
}

console.log(
  'live-witness register-integrity: OK — authority + enforcing test + reused build-tier merge ' +
    'all present and wired (tempdoc 560 §4b/§5, ADR-0042). The invariant itself is the green ' +
    'LiveWitnessTest run (early-warning ceiling, 565 §12.10).',
);
